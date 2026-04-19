// SPDX-License-Identifier: CC0-1.0
// Derived from https://github.com/jmz3/EndoscopeCamera (CC0-1.0)
// Modified for Android: opens libusb via an FD supplied by the JVM
// (UsbDeviceConnection.getFileDescriptor) rather than scanning
// /dev/bus/usb, which non-rooted Android processes cannot access.

#include "supercamera_core.hpp"

#include <bit>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <stdexcept>
#include <utility>
#include <vector>

#include <libusb.h>

namespace supercamera {
namespace {

class UsbSupercamera {
    static constexpr int INTERFACE_A_NUMBER = 0;
    static constexpr int INTERFACE_B_NUMBER = 1;
    static constexpr int INTERFACE_B_ALTERNATE_SETTING = 1;
    static constexpr unsigned char ENDPOINT_1 = 1;
    static constexpr unsigned char ENDPOINT_2 = 2;
    static constexpr unsigned int USB_TIMEOUT = 1000;

    libusb_context *ctx_ = nullptr;
    libusb_device_handle *handle_ = nullptr;
    bool interface_a_claimed_ = false;
    bool interface_b_claimed_ = false;

    int usb_read(unsigned char endpoint, ByteVector &buf, size_t max_size) {
        int transferred = 0;
        buf.resize(max_size);
        int ret = libusb_bulk_transfer(
            handle_, LIBUSB_ENDPOINT_IN | endpoint, buf.data(),
            static_cast<int>(buf.size()), &transferred, USB_TIMEOUT);
        if (ret != 0) {
            buf.clear();
            return ret;
        }
        buf.resize(static_cast<size_t>(transferred));
        return 0;
    }

    int usb_write(unsigned char endpoint, const ByteVector &buf) {
        int transferred = 0;
        int ret = libusb_bulk_transfer(
            handle_, LIBUSB_ENDPOINT_OUT | endpoint,
            const_cast<uint8_t *>(buf.data()),
            static_cast<int>(buf.size()), &transferred, USB_TIMEOUT);
        if (ret != 0) {
            return ret;
        }
        if (transferred != static_cast<int>(buf.size())) {
            return LIBUSB_ERROR_IO;
        }
        return 0;
    }

public:
    explicit UsbSupercamera(int usb_fd) {
        try {
            // On Android we cannot scan /dev/bus/usb; the JVM supplies an FD.
            libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

            int ret = libusb_init(&ctx_);
            if (ret < 0) {
                throw std::runtime_error("libusb_init failed");
            }

            ret = libusb_wrap_sys_device(ctx_,
                                         static_cast<intptr_t>(usb_fd),
                                         &handle_);
            if (ret < 0 || handle_ == nullptr) {
                std::ostringstream ss;
                ss << "libusb_wrap_sys_device failed: "
                   << libusb_error_name(ret);
                throw std::runtime_error(ss.str());
            }

            ret = libusb_claim_interface(handle_, INTERFACE_A_NUMBER);
            if (ret < 0) {
                throw std::runtime_error("claim_interface A failed");
            }
            interface_a_claimed_ = true;

            ret = libusb_claim_interface(handle_, INTERFACE_B_NUMBER);
            if (ret < 0) {
                throw std::runtime_error("claim_interface B failed");
            }
            interface_b_claimed_ = true;

            ret = libusb_set_interface_alt_setting(
                handle_, INTERFACE_B_NUMBER, INTERFACE_B_ALTERNATE_SETTING);
            if (ret < 0) {
                throw std::runtime_error("set_interface_alt_setting failed");
            }

            ret = libusb_clear_halt(handle_, ENDPOINT_1);
            if (ret < 0) {
                throw std::runtime_error("clear_halt EP1 failed");
            }

            const ByteVector ep2_buf = {0xFF, 0x55, 0xFF, 0x55, 0xEE, 0x10};
            ret = usb_write(ENDPOINT_2, ep2_buf);
            if (ret != 0) {
                throw std::runtime_error("start sequence EP2 failed");
            }

            const ByteVector start_stream = {0xBB, 0xAA, 5, 0, 0};
            ret = usb_write(ENDPOINT_1, start_stream);
            if (ret != 0) {
                throw std::runtime_error("start stream command failed");
            }
        } catch (...) {
            cleanup();
            throw;
        }
    }

    ~UsbSupercamera() { cleanup(); }

    void cleanup() {
        if (handle_ != nullptr) {
            if (interface_b_claimed_) {
                libusb_release_interface(handle_, INTERFACE_B_NUMBER);
                interface_b_claimed_ = false;
            }
            if (interface_a_claimed_) {
                libusb_release_interface(handle_, INTERFACE_A_NUMBER);
                interface_a_claimed_ = false;
            }
            libusb_close(handle_);
            handle_ = nullptr;
        }
        if (ctx_ != nullptr) {
            libusb_exit(ctx_);
            ctx_ = nullptr;
        }
    }

    int read_frame(ByteVector &read_buf) {
        return usb_read(ENDPOINT_1, read_buf, 0x400);
    }
};

class UPPCameraParser {
    static_assert(std::endian::native == std::endian::little);

    struct [[gnu::packed]] upp_usb_frame_t {
        uint16_t magic;
        uint8_t cid;
        uint16_t length;
    };

    struct [[gnu::packed]] upp_cam_frame_t {
        uint8_t fid;
        uint8_t cam_num;
        unsigned char has_g : 1;
        unsigned char button_press : 1;
        unsigned char other : 6;
        uint32_t g_sensor;
    };

    static constexpr uint16_t UPP_USB_MAGIC = 0xBBAA;
    static constexpr uint8_t UPP_CAMID_7 = 7;
    static constexpr uint8_t UPP_CAMID_11 = 11;

    ByteVector camera_buffer_;
    uint16_t source_id_ = 0;
    upp_cam_frame_t cam_header_ = {};
    uint32_t frame_id_ = 0;

    FrameCallback frame_callback_;
    ButtonCallback button_callback_;

    static uint64_t now_us() {
        const auto now = std::chrono::system_clock::now().time_since_epoch();
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(now).count());
    }

    void emit_frame() {
        if (camera_buffer_.empty()) {
            return;
        }
        CapturedFrame frame = {
            .jpeg = camera_buffer_,
            .source_id = source_id_,
            .frame_id = frame_id_++,
            .timestamp_us = now_us(),
        };
        frame_callback_(frame);
        camera_buffer_.clear();
    }

public:
    UPPCameraParser(FrameCallback frame_callback,
                    ButtonCallback button_callback,
                    uint16_t source_id)
        : source_id_(source_id),
          frame_callback_(std::move(frame_callback)),
          button_callback_(std::move(button_callback)) {}

    void flush_pending() { emit_frame(); }

    void handle_upp_frame(const ByteVector &data) {
        const size_t usb_header_len = sizeof(upp_usb_frame_t);
        if (data.size() < usb_header_len) {
            return;
        }

        upp_usb_frame_t frame = {};
        std::memcpy(&frame, data.data(), usb_header_len);

        if (frame.magic != UPP_USB_MAGIC) {
            return;
        }
        if ((frame.cid != UPP_CAMID_7) && (frame.cid != UPP_CAMID_11)) {
            return;
        }
        if (usb_header_len + frame.length > data.size()) {
            return;
        }

        const size_t cam_header_len = sizeof(upp_cam_frame_t);
        if (data.size() - usb_header_len < cam_header_len) {
            return;
        }
        if (frame.length < cam_header_len) {
            return;
        }

        upp_cam_frame_t cam_part = {};
        std::memcpy(&cam_part, data.data() + usb_header_len, cam_header_len);

        if (!camera_buffer_.empty() && cam_header_.fid != cam_part.fid) {
            emit_frame();
        }

        if (camera_buffer_.empty()) {
            cam_header_ = cam_part;
            if (!((cam_header_.cam_num < 2) && (cam_header_.has_g == 0) &&
                  (cam_header_.other == 0))) {
                return;
            }
        } else {
            if (!((cam_header_.fid == cam_part.fid) &&
                  (cam_header_.cam_num == cam_part.cam_num) &&
                  (cam_header_.has_g == cam_part.has_g) &&
                  (cam_header_.other == cam_part.other))) {
                return;
            }
        }

        if (cam_part.button_press && button_callback_) {
            button_callback_();
        }

        const auto cam_data_start =
            data.begin() + static_cast<std::ptrdiff_t>(usb_header_len +
                                                       cam_header_len);
        const auto cam_data_end =
            data.begin() +
            static_cast<std::ptrdiff_t>(usb_header_len + frame.length);
        if (cam_data_start > cam_data_end) {
            return;
        }
        camera_buffer_.insert(camera_buffer_.end(), cam_data_start,
                              cam_data_end);
    }
};

} // namespace

struct SupercameraCapture::Impl {
    UsbSupercamera usb;
    explicit Impl(int usb_fd) : usb(usb_fd) {}
};

SupercameraCapture::SupercameraCapture(int usb_fd, uint16_t source_id,
                                       ButtonCallback button_callback)
    : impl_(std::make_unique<Impl>(usb_fd)),
      source_id_(source_id),
      button_callback_(std::move(button_callback)) {}

SupercameraCapture::~SupercameraCapture() = default;

void SupercameraCapture::request_stop() { stop_requested_ = true; }

void SupercameraCapture::run(const FrameCallback &frame_callback) {
    if (!frame_callback) {
        throw std::invalid_argument("frame callback is required");
    }

    stop_requested_ = false;
    UPPCameraParser parser(frame_callback, button_callback_, source_id_);
    ByteVector read_buf;

    while (!stop_requested_) {
        const int ret = impl_->usb.read_frame(read_buf);
        if (ret == 0) {
            parser.handle_upp_frame(read_buf);
            continue;
        }
        if (ret == LIBUSB_ERROR_NO_DEVICE) {
            break;
        }
        if (ret == LIBUSB_ERROR_TIMEOUT) {
            continue;
        }
        // Other errors: retry briefly. Hard failures will eventually get
        // NO_DEVICE and break.
    }

    parser.flush_pending();
}

} // namespace supercamera
