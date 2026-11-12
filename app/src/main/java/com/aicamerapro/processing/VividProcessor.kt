package com.aicamerapro.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

enum class EnhanceMode { NORMAL, VIVID, AI, PORTRAIT }
data class EnhanceSettings(
    val mode: EnhanceMode = EnhanceMode.AI,
    val intensity: Float = 0.50f,
    val warmth: Float = 0.02f,
    val contrast: Float = 1.0f
)

/**
 * Conservative daylight-first color finishing.
 *
 * This is deterministic on-device image processing, not a generative AI model.
 * It deliberately avoids global exposure gain and strong contrast changes. Chroma
 * is increased around the original luminance so bright skies and skin highlights
 * are less likely to clip. The source bitmap is never modified.
 */
object VividProcessor {
    fun enhance(source: Bitmap, settings: EnhanceSettings): Bitmap {
        if (settings.mode == EnhanceMode.NORMAL) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val width = source.width
        val height = source.height
        if (width < 2 || height < 2) return source.copy(Bitmap.Config.ARGB_8888, false)

        val intensity = settings.intensity.coerceIn(0f, 1f)
        val sampleCount = 10000
        val step = max(1, (width.toLong() * height / sampleCount).toInt())
        var saturationSum = 0.0
        var samples = 0
        var index = 0L
        val total = width.toLong() * height
        while (index < total) {
            val color = source.getPixel((index % width).toInt(), (index / width).toInt())
            val r = (color shr 16 and 255) / 255f
            val g = (color shr 8 and 255) / 255f
            val b = (color and 255) / 255f
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            saturationSum += if (high < 0.0001f) 0.0 else ((high - low) / high).toDouble()
            samples++
            index += step.toLong()
        }
        val averageSaturation = (saturationSum / max(1, samples)).toFloat()
        val adaptive = if (settings.mode == EnhanceMode.AI) {
            (1.0f + (0.34f - averageSaturation).coerceIn(0f, 0.34f) * 0.22f)
        } else 1.0f
        val modeBoost = if (settings.mode == EnhanceMode.VIVID) 1.12f else 1.0f
        val saturationBoost = (1f + intensity * 0.24f) * adaptive * modeBoost
        val gentleContrast = 1f + ((settings.contrast.coerceIn(0.96f, 1.08f) - 1f) * 0.18f)
        val warmth = settings.warmth.coerceIn(-0.04f, 0.04f) * intensity

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val color = row[x]
                val originalR = (color shr 16 and 255) / 255f
                val originalG = (color shr 8 and 255) / 255f
                val originalB = (color and 255) / 255f
                val luma = 0.2126f * originalR + 0.7152f * originalG + 0.0722f * originalB

                // Protect bright daylight highlights by tapering color changes near white.
                val highlightProtection = (1f - ((luma - 0.72f) / 0.28f).coerceIn(0f, 1f) * 0.55f)
                val sat = 1f + (saturationBoost - 1f) * highlightProtection
                var r = luma + (originalR - luma) * sat
                var g = luma + (originalG - luma) * sat
                var b = luma + (originalB - luma) * sat

                // Very small tone shaping; no automatic exposure lift.
                r = 0.5f + (r - 0.5f) * gentleContrast + warmth * 0.18f
                g = 0.5f + (g - 0.5f) * gentleContrast
                b = 0.5f + (b - 0.5f) * gentleContrast - warmth * 0.12f

                row[x] = (color and -0x1000000) or
                    ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                    ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                    (b.coerceIn(0f, 1f) * 255f).toInt()
            }
            output.setPixels(row, 0, width, 0, y, width, 1)
        }
        return output
    }
}
