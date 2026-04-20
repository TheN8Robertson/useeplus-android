package com.naterobertson.useeplus

object NativeBridge {
    init {
        System.loadLibrary("useeplus")
    }

    @JvmStatic external fun nativeInit(fd: Int, camNum: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativePollFrame(handle: Long): ByteArray?
    @JvmStatic external fun nativeConsumeButton(handle: Long): Boolean
    @JvmStatic external fun nativeIsStopped(handle: Long): Boolean
    @JvmStatic external fun nativeTakeError(handle: Long): String?
    @JvmStatic external fun nativeSetCamNum(handle: Long, camNum: Int)
    @JvmStatic external fun nativeGetPacketCounts(handle: Long): IntArray?
}
