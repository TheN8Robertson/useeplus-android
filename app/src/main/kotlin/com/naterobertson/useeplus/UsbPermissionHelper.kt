package com.naterobertson.useeplus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbPermissionHelper(private val context: Context) {

    private val action = "${context.packageName}.USB_PERMISSION"
    private var pending: ((UsbDevice, Boolean) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != action) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val cb = pending
            pending = null
            if (device != null) cb?.invoke(device, granted)
        }
    }

    fun register() {
        val flags = if (Build.VERSION.SDK_INT >= 33)
            Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(receiver, IntentFilter(action), flags)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun request(
        usbManager: UsbManager,
        device: UsbDevice,
        onResult: (UsbDevice, Boolean) -> Unit,
    ) {
        if (usbManager.hasPermission(device)) {
            onResult(device, true)
            return
        }
        pending = onResult
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, intent)
    }
}
