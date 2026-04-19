package com.naterobertson.useeplus

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object UsbDeviceFinder {

    // vendor:product pairs known to enumerate as USEEPLUS / supercamera.
    private val SUPPORTED: List<Pair<Int, Int>> = listOf(
        0x2ce3 to 0x3828,
        0x0329 to 0x2022,
    )

    fun find(usbManager: UsbManager): List<UsbDevice> =
        usbManager.deviceList.values
            .filter { dev ->
                SUPPORTED.any { it.first == dev.vendorId && it.second == dev.productId }
            }
            .sortedBy { it.deviceName }

    fun describe(device: UsbDevice): String {
        val vid = "%04x".format(device.vendorId)
        val pid = "%04x".format(device.productId)
        return "USEEPLUS $vid:$pid (${device.deviceName})"
    }
}
