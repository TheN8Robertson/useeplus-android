package com.naterobertson.useeplus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.1 video recording: writes each JPEG frame verbatim into a timestamped
 * subfolder of Pictures/USEEPLUS/. Convert to MP4 offline:
 *
 *     ffmpeg -framerate 30 -pattern_type glob -i 'frame_*.jpg' \
 *            -c:v libx264 -pix_fmt yuv420p out.mp4
 *
 * A proper on-device MP4 encoder (MediaCodec + GL input surface) is a
 * planned follow-up.
 */
class VideoRecorder private constructor(
    private val context: Context,
    private val folderName: String,
) {

    private val running = AtomicBoolean(true)
    private var frameCount = 0

    @Synchronized
    fun pushJpeg(jpeg: ByteArray) {
        if (!running.get()) return
        val filename = "frame_%06d.jpg".format(frameCount)
        val relPath = "${Environment.DIRECTORY_PICTURES}/USEEPLUS/$folderName"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
            frameCount++
        }.onFailure {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Synchronized
    fun stop(): Int {
        running.set(false)
        return frameCount
    }

    val folder: String get() = folderName

    companion object {
        fun start(context: Context): VideoRecorder {
            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.US,
            ).format(java.util.Date())
            return VideoRecorder(context, "rec_$stamp")
        }
    }
}
