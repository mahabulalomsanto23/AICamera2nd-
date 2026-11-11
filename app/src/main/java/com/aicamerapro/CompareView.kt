package com.aicamerapro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Drag the vertical divider to compare original and enhanced photos. */
class CompareView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var before: android.graphics.Bitmap? = null
    private var after: android.graphics.Bitmap? = null
    private var split = 0.5f
    private var originalOnly = false

    fun setImages(original: android.graphics.Bitmap?, enhanced: android.graphics.Bitmap?) {
        before = original
        after = enhanced
        originalOnly = false
        invalidate()
    }

    fun showOriginalOnly(show: Boolean) {
        originalOnly = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.BLACK)
        val src = if (originalOnly) before else after ?: before
        if (src == null || width == 0 || height == 0) return
        val scale = min(width.toFloat() / src.width, height.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val dst = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(src, null, dst, paint)
        val original = before
        val enhanced = after
        if (!originalOnly && original != null && enhanced != null) {
            canvas.save()
            val divider = dst.left + dst.width() * split
            canvas.clipRect(dst.left, dst.top, divider, dst.bottom)
            canvas.drawBitmap(original, null, dst, paint)
            canvas.restore()
            paint.color = android.graphics.Color.WHITE
            paint.strokeWidth = resources.displayMetrics.density * 2
            canvas.drawLine(divider, dst.top, divider, dst.bottom, paint)
            paint.color = 0xEE8B5CF6.toInt()
            canvas.drawCircle(divider, height / 2f, resources.displayMetrics.density * 18, paint)
            paint.color = android.graphics.Color.WHITE
            paint.strokeWidth = resources.displayMetrics.density * 2
            canvas.drawLine(divider - 5, height / 2f - 7, divider + 5, height / 2f, paint)
            canvas.drawLine(divider + 5, height / 2f, divider - 5, height / 2f + 7, paint)
            paint.color = android.graphics.Color.WHITE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (before == null || after == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                split = (event.x / width.coerceAtLeast(1)).coerceIn(0.02f, 0.98f)
                originalOnly = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
