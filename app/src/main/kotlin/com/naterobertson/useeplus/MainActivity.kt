package com.naterobertson.useeplus

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var permissionHelper: UsbPermissionHelper

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var snapshotBtn: MaterialButton
    private lateinit var recordBtn: MaterialButton

    private var connection: UsbDeviceConnection? = null
    private var captureHandle: Long = 0L
    private var pollJob: Job? = null
    private var statusJob: Job? = null
    private var videoRecorder: VideoRecorder? = null

    // Sliding 1s window of frame-arrival timestamps → fps.
    private val frameTimestamps = ArrayDeque<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        snapshotBtn = findViewById(R.id.snapshotBtn)
        recordBtn = findViewById(R.id.recordBtn)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        permissionHelper = UsbPermissionHelper(this)
        permissionHelper.register()

        snapshotBtn.setOnClickListener { onSnapshot() }
        recordBtn.setOnClickListener { onToggleRecord() }

        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onPause() {
        super.onPause()
        stopCapture()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionHelper.unregister()
    }

    private fun refreshDevices() {
        val devices = UsbDeviceFinder.find(usbManager)
        if (devices.isEmpty()) {
            status.setText(R.string.status_no_device)
            deviceSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item, listOf("—"),
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            return
        }

        val labels = devices.map { UsbDeviceFinder.describe(it) }
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // Install the listener first, then set the adapter so that the
        // initial selection triggers onItemSelected exactly once.
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onDeviceSelected(devices[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        deviceSpinner.adapter = adapter
    }

    private fun onDeviceSelected(device: UsbDevice) {
        stopCapture()
        status.setText(R.string.status_awaiting_permission)
        permissionHelper.request(usbManager, device) { dev, granted ->
            if (!granted) {
                status.text = "Permission denied for ${UsbDeviceFinder.describe(dev)}"
                return@request
            }
            openAndStart(dev)
        }
    }

    private fun openAndStart(device: UsbDevice) {
        val conn = usbManager.openDevice(device) ?: run {
            status.text = "openDevice failed"
            return
        }
        connection = conn
        try {
            captureHandle = NativeBridge.nativeInit(conn.fileDescriptor)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit threw", t)
            status.text = "Native init failed: ${t.message}"
            conn.close()
            connection = null
            return
        }
        if (captureHandle == 0L) {
            status.text = "Native init returned null handle"
            conn.close()
            connection = null
            return
        }

        status.setText(R.string.status_streaming)
        startPollLoop()
        startStatusLoop()
    }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val handle = captureHandle
                if (handle == 0L) break
                if (NativeBridge.nativeIsStopped(handle)) break

                if (NativeBridge.nativeConsumeButton(handle)) {
                    lifecycleScope.launch(Dispatchers.Main) { onSnapshot() }
                }

                val frame = NativeBridge.nativePollFrame(handle)
                if (frame != null) {
                    preview.pushJpeg(frame)
                    videoRecorder?.pushJpeg(frame)
                    registerFrame()
                }
                delay(8)
            }
        }
    }

    private fun startStatusLoop() {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val handle = captureHandle
                if (handle == 0L) {
                    delay(500); continue
                }
                if (NativeBridge.nativeIsStopped(handle)) {
                    val err = NativeBridge.nativeTakeError(handle)
                    status.text = "Capture stopped${err?.let { ": $it" } ?: ""}"
                    break
                }
                status.text = "Streaming — %.1f fps".format(computeFps())
                delay(500)
            }
        }
    }

    private fun registerFrame() {
        val now = System.currentTimeMillis()
        synchronized(frameTimestamps) {
            frameTimestamps.addLast(now)
            while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000) {
                frameTimestamps.removeFirst()
            }
        }
    }

    private fun computeFps(): Double {
        val now = System.currentTimeMillis()
        synchronized(frameTimestamps) {
            while (frameTimestamps.isNotEmpty() && now - frameTimestamps.first() > 1000) {
                frameTimestamps.removeFirst()
            }
            return frameTimestamps.size.toDouble()
        }
    }

    private fun stopCapture() {
        pollJob?.cancel()
        pollJob = null
        statusJob?.cancel()
        statusJob = null
        val handle = captureHandle
        captureHandle = 0L
        if (handle != 0L) NativeBridge.nativeDestroy(handle)
        connection?.close()
        connection = null
        synchronized(frameTimestamps) { frameTimestamps.clear() }
    }

    private fun onSnapshot() {
        val jpeg = preview.lastJpeg ?: run {
            Toast.makeText(this, "No frame yet", Toast.LENGTH_SHORT).show(); return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = runCatching { GallerySaver.saveJpeg(this@MainActivity, jpeg) }.getOrNull()
            lifecycleScope.launch(Dispatchers.Main) {
                val msg = if (uri != null) getString(R.string.msg_snapshot_saved)
                          else "Snapshot save failed"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onToggleRecord() {
        if (videoRecorder == null) {
            videoRecorder = VideoRecorder.start(this)
            recordBtn.setText(R.string.action_record_stop)
        } else {
            stopRecording()
        }
    }

    private fun stopRecording() {
        val rec = videoRecorder ?: return
        videoRecorder = null
        val frames = rec.stop()
        recordBtn.setText(R.string.action_record_start)
        val msg = "Saved $frames frames to Pictures/USEEPLUS/${rec.folder}"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "useeplus-main"
    }
}
