package com.aicamerapro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Displays the processed photo without a comparison slider or original-photo toggle. */
class CompareView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var before: android.graphics.Bitmap? = null
    private var after: android.graphics.Bitmap? = null

    fun setImages(original: android.graphics.Bitmap?, enhanced: android.graphics.Bitmap?) {
        before = original
        after = enhanced
        invalidate()
    }

    fun showOriginalOnly(show: Boolean) {
        // Kept as a no-op for compatibility with older callers.
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val bitmap = after ?: before ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dst = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
}
