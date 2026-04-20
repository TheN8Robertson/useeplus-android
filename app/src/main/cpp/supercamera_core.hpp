// SPDX-License-Identifier: CC0-1.0
// Derived from https://github.com/jmz3/EndoscopeCamera (CC0-1.0)
// Modified for Android: constructor accepts a USB FD from UsbDeviceConnection.

#ifndef SUPERCAMERA_CORE_HPP
#define SUPERCAMERA_CORE_HPP

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

namespace supercamera {

using ByteVector = std::vector<uint8_t>;

struct CapturedFrame {
    ByteVector jpeg;
    uint16_t source_id;
    uint32_t frame_id;
    uint64_t timestamp_us;
};

using FrameCallback = std::function<void(const CapturedFrame &)>;
using ButtonCallback = std::function<void()>;

class SupercameraCapture {
public:
    // Android-friendly constructor: caller passes an already-opened USB FD
    // (from UsbDeviceConnection.getFileDescriptor()). The Java side must keep
    // the UsbDeviceConnection alive for the lifetime of this object.
    //
    // cam_num selects which camera of a dual-lens endoscope to keep (0 or 1).
    // Switch at runtime via set_cam_num(); re-initing the USB device is
    // avoided because USEEPLUS firmware doesn't accept a second init sequence
    // without a physical reconnect.
    explicit SupercameraCapture(int usb_fd,
                                uint8_t cam_num = 0,
                                ButtonCallback button_callback = {});
    ~SupercameraCapture();

    SupercameraCapture(const SupercameraCapture &) = delete;
    SupercameraCapture &operator=(const SupercameraCapture &) = delete;

    void run(const FrameCallback &frame_callback);
    void request_stop();

    // Thread-safe: live-switch the camera filter without tearing down USB.
    void set_cam_num(uint8_t cam_num) { target_cam_num_.store(cam_num); }
    uint8_t cam_num() const { return target_cam_num_.load(); }

    // Diagnostics: cumulative packet counts observed per cam_num.
    uint32_t packets_seen_cam(uint8_t cam_num) const {
        if (cam_num == 0) return packets_cam0_.load();
        if (cam_num == 1) return packets_cam1_.load();
        return 0;
    }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::atomic_bool stop_requested_ = false;
    std::atomic<uint8_t> target_cam_num_{0};
    std::atomic<uint32_t> packets_cam0_{0};
    std::atomic<uint32_t> packets_cam1_{0};
    ButtonCallback button_callback_;
};

} // namespace supercamera

#endif
