package com.aicamerapro.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

enum class EnhanceMode { NORMAL, VIVID, AI, PORTRAIT }
data class EnhanceSettings(val mode: EnhanceMode = EnhanceMode.AI, val intensity: Float = 0.78f, val warmth: Float = 0.08f, val contrast: Float = 1.12f)

/**
 * Offline adaptive color pipeline. Uses scanline buffers to avoid multiple full-frame
 * IntArray/FloatArray allocations on high-resolution Snapdragon camera images.
 */
object VividProcessor {
    fun enhance(source: Bitmap, settings: EnhanceSettings): Bitmap {
        if (settings.mode == EnhanceMode.NORMAL) return source.copy(Bitmap.Config.ARGB_8888, false)
        val w = source.width
        val h = source.height
        if (w < 2 || h < 2) return source.copy(Bitmap.Config.ARGB_8888, false)

        // Estimate global exposure and saturation from a bounded sample.
        val sampleCount = 12000
        val step = max(1, (w.toLong() * h / sampleCount).toInt())
        var lumSum = 0.0
        var satSum = 0.0
        var samples = 0
        var index = 0L
        val total = w.toLong() * h
        while (index < total) {
            val x = (index % w).toInt()
            val y = (index / w).toInt()
            val color = source.getPixel(x, y)
            val r = (color shr 16 and 255) / 255f
            val g = (color shr 8 and 255) / 255f
            val b = (color and 255) / 255f
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            lumSum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            satSum += if (high <= 0f) 0.0 else ((high - low) / high).toDouble()
            samples++
            index += step.toLong()
        }
        val averageLuma = (lumSum / max(1, samples)).toFloat()
        val averageSaturation = (satSum / max(1, samples)).toFloat()
        val adaptiveBoost = if (settings.mode == EnhanceMode.AI) {
            (1.04f + (0.42f - averageSaturation).coerceIn(0f, 0.42f) * 0.32f).coerceIn(1.04f, 1.18f)
        } else 1f
        val exposureGain = (1f + (0.46f - averageLuma) * 0.28f).coerceIn(0.90f, 1.10f)
        val vividBoost = if (settings.mode == EnhanceMode.VIVID) 1.18f else 1f
        val vibrance = (1f + settings.intensity.coerceIn(0f, 1f) * 0.62f) * adaptiveBoost * vividBoost
        val contrast = settings.contrast.coerceIn(0.95f, 1.35f) + if (settings.mode == EnhanceMode.AI) 0.015f else 0f
        val warmth = settings.warmth * settings.intensity.coerceIn(0f, 1f)

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val previous = IntArray(w)
        val current = IntArray(w)
        val next = IntArray(w)
        val outputRow = IntArray(w)
        for (y in 0 until h) {
            val y0 = max(0, y - 1)
            val y1 = min(h - 1, y + 1)
            source.getPixels(previous, 0, w, 0, y0, w, 1)
            source.getPixels(current, 0, w, 0, y, w, 1)
            source.getPixels(next, 0, w, 0, y1, w, 1)
            for (x in 0 until w) {
                val color = current[x]
                var r = (color shr 16 and 255) / 255f
                var g = (color shr 8 and 255) / 255f
                var b = (color and 255) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val high = max(r, max(g, b))
                val low = min(r, min(g, b))
                val saturation = if (high <= 0f) 0f else (high - low) / high
                val vibranceFactor = 1f + (1f - saturation) * (vibrance - 1f)

                val leftColor = current[max(0, x - 1)]
                val rightColor = current[min(w - 1, x + 1)]
                val upColor = previous[x]
                val downColor = next[x]
                fun luminance(c: Int) =
                    0.2126f * ((c shr 16 and 255) / 255f) +
                    0.7152f * ((c shr 8 and 255) / 255f) +
                    0.0722f * ((c and 255) / 255f)
                val localAverage = (luminance(leftColor) + luminance(rightColor) +
                    luminance(upColor) + luminance(downColor)) * 0.25f
                val detail = (luma - localAverage) * 0.12f * settings.intensity.coerceIn(0f, 1f)
                val shadowLift = (0.30f - luma).coerceAtLeast(0f) * 0.035f * settings.intensity

                val midpoint = 0.46f
                r = (midpoint + (r - midpoint) * contrast) * vibranceFactor * exposureGain + warmth * 0.045f + detail + shadowLift
                g = (midpoint + (g - midpoint) * contrast) * vibranceFactor * exposureGain + detail + shadowLift
                b = (midpoint + (b - midpoint) * contrast) * vibranceFactor * exposureGain - warmth * 0.035f + detail + shadowLift
                fun rollOff(value: Float): Float {
                    val v = value.coerceAtLeast(0f)
                    return if (v > 0.88f) 0.88f + (v - 0.88f) * 0.55f else v
                }
                r = rollOff(r).coerceIn(0f, 1f)
                g = rollOff(g).coerceIn(0f, 1f)
                b = rollOff(b).coerceIn(0f, 1f)
                outputRow[x] = (color and -0x1000000) or
                    ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255f).toInt().coerceIn(0, 255)
            }
            output.setPixels(outputRow, 0, w, 0, y, w, 1)
        }
        return output
    }
}
