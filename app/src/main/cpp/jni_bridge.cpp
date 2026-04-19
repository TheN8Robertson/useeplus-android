// SPDX-License-Identifier: CC0-1.0
// JNI bridge exposing SupercameraCapture to Kotlin.

#include "supercamera_core.hpp"

#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <deque>
#include <exception>
#include <jni.h>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#define LOG_TAG "useeplus-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

class AndroidCapture {
public:
    AndroidCapture(int fd, uint16_t source_id)
        : capture_(fd, source_id, [this]() { button_pressed_ = true; }) {
        worker_ = std::thread([this]() {
            try {
                capture_.run([this](const supercamera::CapturedFrame &f) {
                    std::lock_guard lock(mutex_);
                    latest_ = f.jpeg;
                    latest_frame_id_ = f.frame_id;
                    has_new_ = true;
                });
            } catch (const std::exception &e) {
                LOGE("capture thread terminated: %s", e.what());
                std::lock_guard lock(error_mutex_);
                error_message_ = e.what();
            } catch (...) {
                LOGE("capture thread terminated: unknown error");
                std::lock_guard lock(error_mutex_);
                error_message_ = "unknown error";
            }
            stopped_ = true;
        });
    }

    ~AndroidCapture() {
        capture_.request_stop();
        if (worker_.joinable()) {
            worker_.join();
        }
    }

    AndroidCapture(const AndroidCapture &) = delete;
    AndroidCapture &operator=(const AndroidCapture &) = delete;

    bool pop_frame(std::vector<uint8_t> &out, uint32_t &frame_id_out) {
        if (!has_new_.exchange(false)) {
            return false;
        }
        std::lock_guard lock(mutex_);
        out = latest_;
        frame_id_out = latest_frame_id_;
        return !out.empty();
    }

    bool consume_button_press() {
        return button_pressed_.exchange(false);
    }

    bool is_stopped() const { return stopped_.load(); }

    std::string take_error() {
        std::lock_guard lock(error_mutex_);
        std::string e = std::move(error_message_);
        error_message_.clear();
        return e;
    }

private:
    supercamera::SupercameraCapture capture_;
    std::thread worker_;
    std::atomic_bool has_new_{false};
    std::atomic_bool button_pressed_{false};
    std::atomic_bool stopped_{false};
    std::mutex mutex_;
    std::vector<uint8_t> latest_;
    uint32_t latest_frame_id_ = 0;
    std::mutex error_mutex_;
    std::string error_message_;
};

inline AndroidCapture *from_handle(jlong handle) {
    return reinterpret_cast<AndroidCapture *>(static_cast<intptr_t>(handle));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_naterobertson_useeplus_NativeBridge_nativeInit(
    JNIEnv *env, jclass /*clazz*/, jint fd, jint source_id) {
    try {
        auto *capture = new AndroidCapture(
            static_cast<int>(fd), static_cast<uint16_t>(source_id));
        return static_cast<jlong>(reinterpret_cast<intptr_t>(capture));
    } catch (const std::exception &e) {
        LOGE("nativeInit failed: %s", e.what());
        jclass ex = env->FindClass("java/lang/RuntimeException");
        if (ex) env->ThrowNew(ex, e.what());
        return 0;
    } catch (...) {
        LOGE("nativeInit failed: unknown");
        jclass ex = env->FindClass("java/lang/RuntimeException");
        if (ex) env->ThrowNew(ex, "unknown native error");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_naterobertson_useeplus_NativeBridge_nativeDestroy(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *capture = from_handle(handle);
    if (capture) delete capture;
}

JNIEXPORT jbyteArray JNICALL
Java_com_naterobertson_useeplus_NativeBridge_nativePollFrame(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *capture = from_handle(handle);
    if (!capture) return nullptr;

    std::vector<uint8_t> bytes;
    uint32_t frame_id = 0;
    if (!capture->pop_frame(bytes, frame_id)) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(bytes.size()),
        reinterpret_cast<const jbyte *>(bytes.data()));
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_naterobertson_useeplus_NativeBridge_nativeConsumeButton(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *capture = from_handle(handle);
    if (!capture) return JNI_FALSE;
    return capture->consume_button_press() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_naterobertson_useeplus_NativeBridge_nativeIsStopped(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    auto *capture = from_handle(handle);
    if (!capture) return JNI_TRUE;
    return capture->is_stopped() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_naterobertson_useeplus_NativeBridge_nativeTakeError(
    JNIEnv *env, jclass /*clazz*/, jlong handle) {
    auto *capture = from_handle(handle);
    if (!capture) return nullptr;
    std::string err = capture->take_error();
    if (err.empty()) return nullptr;
    return env->NewStringUTF(err.c_str());
}

} // extern "C"
