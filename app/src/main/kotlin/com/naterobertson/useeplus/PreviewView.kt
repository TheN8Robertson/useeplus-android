package com.naterobertson.useeplus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View

class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    @Volatile
    private var currentBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val decodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    var lastJpeg: ByteArray? = null
        private set

    private var pushCount = 0
    private var lastLoggedSize = 0

    fun pushJpeg(bytes: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        pushCount++
        if (bmp == null) {
            if (pushCount <= 5 || pushCount % 30 == 0) {
                Log.w(TAG, "JPEG decode failed size=${bytes.size} " +
                    "head=${bytes.take(4).joinToString("") { "%02x".format(it) }} " +
                    "tail=${bytes.takeLast(4).joinToString("") { "%02x".format(it) }}")
            }
            return
        }
        if (pushCount <= 5 || bytes.size != lastLoggedSize) {
            Log.i(TAG, "JPEG ok size=${bytes.size} ${bmp.width}x${bmp.height}")
            lastLoggedSize = bytes.size
        }
        lastJpeg = bytes
        currentBitmap = bmp
        postInvalidateOnAnimation()
    }

    companion object {
        private const val TAG = "useeplus-preview"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap ?: return

        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (vw <= 0f || vh <= 0f || bw <= 0f || bh <= 0f) return

        val scale = minOf(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val dx = (vw - dw) / 2f
        val dy = (vh - dh) / 2f
        val dst = Rect(
            dx.toInt(), dy.toInt(),
            (dx + dw).toInt(), (dy + dh).toInt(),
        )
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}
