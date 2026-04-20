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
import android.widget.SeekBar
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
    private lateinit var ledSeek: SeekBar
    private lateinit var snapshotBtn: MaterialButton
    private lateinit var recordBtn: MaterialButton
    private lateinit var camToggleBtn: MaterialButton

    private var connection: UsbDeviceConnection? = null
    private var captureHandle: Long = 0L
    private var pollJob: Job? = null
    private var videoRecorder: VideoRecorder? = null
    private var currentDevice: UsbDevice? = null
    private var currentCamNum: Int = 0

    private var frameCounter = 0
    private var lastFpsMillis = System.currentTimeMillis()
    private var measuredFps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        ledSeek = findViewById(R.id.ledSeek)
        snapshotBtn = findViewById(R.id.snapshotBtn)
        recordBtn = findViewById(R.id.recordBtn)
        camToggleBtn = findViewById(R.id.camToggleBtn)
        updateCamToggleLabel()

        camToggleBtn.setOnClickListener { onToggleCam() }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        permissionHelper = UsbPermissionHelper(this)
        permissionHelper.register()

        snapshotBtn.setOnClickListener { onSnapshot() }
        recordBtn.setOnClickListener { onToggleRecord() }

        ledSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                Toast.makeText(
                    this@MainActivity, R.string.msg_led_stub, Toast.LENGTH_SHORT,
                ).show()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

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
        currentDevice = device
        val conn = usbManager.openDevice(device) ?: run {
            status.text = "openDevice failed"
            return
        }
        connection = conn
        try {
            captureHandle = NativeBridge.nativeInit(conn.fileDescriptor, currentCamNum)
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
    }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val handle = captureHandle
                if (handle == 0L) break

                if (NativeBridge.nativeIsStopped(handle)) {
                    val err = NativeBridge.nativeTakeError(handle)
                    lifecycleScope.launch(Dispatchers.Main) {
                        status.text = "Capture stopped${err?.let { ": $it" } ?: ""}"
                    }
                    break
                }

                if (NativeBridge.nativeConsumeButton(handle)) {
                    lifecycleScope.launch(Dispatchers.Main) { onSnapshot() }
                }

                val frame = NativeBridge.nativePollFrame(handle)
                if (frame != null) {
                    preview.pushJpeg(frame)
                    videoRecorder?.pushJpeg(frame)
                    tickFps()
                }
                delay(8)
            }
        }
    }

    private fun tickFps() {
        frameCounter++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsMillis
        if (elapsed >= 1000) {
            measuredFps = frameCounter * 1000.0 / elapsed
            frameCounter = 0
            lastFpsMillis = now
            lifecycleScope.launch(Dispatchers.Main) {
                status.text = "Streaming — %.1f fps".format(measuredFps)
            }
        }
    }

    private fun stopCapture() {
        pollJob?.cancel()
        pollJob = null
        val handle = captureHandle
        captureHandle = 0L
        if (handle != 0L) NativeBridge.nativeDestroy(handle)
        connection?.close()
        connection = null
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

    private fun onToggleCam() {
        currentCamNum = if (currentCamNum == 0) 1 else 0
        updateCamToggleLabel()
        // Tear down the native capture + USB connection and reopen with the
        // new cam_num filter. If the hardware streams both cams simultaneously
        // this will switch streams within a second; if only cam 0 streams,
        // the view will stop updating and we'll know cam 1 needs an
        // activation command we haven't reverse-engineered yet.
        val dev = currentDevice ?: return
        stopCapture()
        openAndStart(dev)
    }

    private fun updateCamToggleLabel() {
        camToggleBtn.text = getString(R.string.cam_label, currentCamNum + 1)
    }

    companion object {
        private const val TAG = "useeplus-main"
    }
}
