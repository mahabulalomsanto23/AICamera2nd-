package com.aicamerapro.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

enum class EnhanceMode { NORMAL, VIVID, AI, PORTRAIT }
data class EnhanceSettings(
    val mode: EnhanceMode = EnhanceMode.AI,
    val intensity: Float = 0.50f,
    val warmth: Float = 0.02f,
    val contrast: Float = 1.0f,
    val sceneProfile: String = "GENERAL"
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
            1.0f + (0.42f - averageSaturation).coerceIn(0f, 0.42f) * 0.34f
        } else 1.0f
        // AI is adaptive and restrained; VIVID keeps its stronger established color profile.
        val modeBoost = if (settings.mode == EnhanceMode.VIVID) 1.38f else 1.0f
        val saturationBoost = (1f + intensity * if (settings.mode == EnhanceMode.AI) 0.76f else 0.58f) * adaptive * modeBoost
        // Ambiance adds a restrained midtone lift/depth without changing exposure.
        val ambianceBase = 1f + ((settings.contrast.coerceIn(0.96f, 1.12f) - 1f) * 0.55f)
        // Separate iPhone-inspired Vivid rendering: richer color and a touch more tonal depth.
        val gentleContrast = ambianceBase * if (settings.mode == EnhanceMode.VIVID) 1.035f else 1f
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

                // Vibrance: boost muted colors more than colors already near saturation.
                val high = max(originalR, max(originalG, originalB))
                val low = min(originalR, min(originalG, originalB))
                val sourceSat = if (high < 0.0001f) 0f else (high - low) / high
                val vibranceFactor = 1f + (saturationBoost - 1f) * (1f - sourceSat * 0.42f)

                // Protect daylight highlights and likely skin hues from strong color shifts.
                val hue = rgbHueDegrees(originalR, originalG, originalB)
                val likelySkin = hue in 0f..52f && sourceSat in 0.10f..0.62f && luma in 0.18f..0.88f
                val skinProtection = if (likelySkin) {
                    if (settings.mode == EnhanceMode.VIVID) 0.48f else 0.42f
                } else 1f
                val highlightProtection = 1f - ((luma - 0.76f) / 0.24f).coerceIn(0f, 1f) *
                    if (settings.mode == EnhanceMode.VIVID) 0.40f else 0.48f
                val profileHueBoost = if (settings.mode == EnhanceMode.AI) {
                    when (settings.sceneProfile) {
                        "OUTDOOR", "NATURE" -> when { hue in 75f..165f -> 1.22f; hue in 175f..255f -> 1.12f; else -> 1f }
                        "FOOD" -> if (hue in 5f..55f) 1.16f else 1f
                        "FLOWERS" -> if (hue in 265f..360f) 1.18f else 1f
                        "SUNSET" -> if (hue in 0f..48f) 1.12f else 1f
                        "ARCHITECTURE" -> if (sourceSat < 0.22f) 1.08f else 1f
                        else -> 1f
                    }
                } else 1f
                val sat = 1f + (vibranceFactor - 1f) * highlightProtection * skinProtection * profileHueBoost
                var r = luma + (originalR - luma) * sat
                var g = luma + (originalG - luma) * sat
                var b = luma + (originalB - luma) * sat

                // Scene-aware AI tone curve: gently opens shadows, protects bright skies and keeps midtones natural.
                val aiShadowLift = if (settings.mode == EnhanceMode.AI) {
                    (1f - luma).coerceIn(0f, 1f).let { it * it } * 0.045f
                } else 0f
                val aiHighlightRollOff = if (settings.mode == EnhanceMode.AI) {
                    ((luma - 0.70f) / 0.30f).coerceIn(0f, 1f).let { it * it } * 0.050f
                } else 0f
                val toneShift = aiShadowLift - aiHighlightRollOff
                r = 0.5f + (r - 0.5f) * gentleContrast + toneShift + warmth * 0.18f
                g = 0.5f + (g - 0.5f) * gentleContrast + toneShift
                b = 0.5f + (b - 0.5f) * gentleContrast + toneShift - warmth * 0.12f

                row[x] = (color and -0x1000000) or
                    ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
                    ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or
                    (b.coerceIn(0f, 1f) * 255f).toInt()
            }
            output.setPixels(row, 0, width, 0, y, width, 1)
        }
        return output
    }

    private fun rgbHueDegrees(r: Float, g: Float, b: Float): Float {
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        if (delta < 0.00001f) return 0f
        val hue = when (high) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }
}
