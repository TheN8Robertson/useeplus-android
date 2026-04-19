package com.naterobertson.useeplus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object GallerySaver {

    fun saveJpeg(context: Context, bytes: ByteArray): Uri? {
        val filename = "useeplus_${timestamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/USEEPLUS",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun timestamp(): String {
        val sdf = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS", java.util.Locale.US,
        )
        return sdf.format(java.util.Date())
    }
}
