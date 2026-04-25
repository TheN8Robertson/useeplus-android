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
    // Lens switching on dual-cam USEEPLUS units is hardware-controlled: the
    // user long-presses the button on the endoscope's plug, and the firmware
    // routes whichever lens is currently active through the same cam_num=0
    // stream. cam_num=1 in the protocol is a different (smaller, lower-rate)
    // multiplexed stream we don't use, so we just filter it out.
    explicit SupercameraCapture(int usb_fd, ButtonCallback button_callback = {});
    ~SupercameraCapture();

    SupercameraCapture(const SupercameraCapture &) = delete;
    SupercameraCapture &operator=(const SupercameraCapture &) = delete;

    void run(const FrameCallback &frame_callback);
    void request_stop();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::atomic_bool stop_requested_ = false;
    ButtonCallback button_callback_;
};

} // namespace supercamera

#endif
