package com.aicamerapro.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.Locale
import java.util.concurrent.Executors

/** Bundled on-device image-labeling model used for lightweight scene-aware tuning. */
object SceneAnalyzer {
    enum class Profile { PEOPLE, OUTDOOR, SUNSET, NIGHT, INDOOR, FOOD, NATURE, ARCHITECTURE, FLOWERS, ANIMALS, GENERAL }

    private val executor = Executors.newSingleThreadExecutor()
    private val labelerLazy = lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val labeler: ImageLabeler by labelerLazy

    fun analyze(bitmap: Bitmap, onResult: (Profile) -> Unit, onFailure: () -> Unit) {
        executor.execute {
            try {
                val maxSide = 512
                val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
                val sample = if (scale < 1f) Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true
                ) else bitmap
                labeler.process(InputImage.fromBitmap(sample, 0))
                    .addOnSuccessListener(executor) { labels ->
                        if (sample !== bitmap) sample.recycle()
                        val names = labels.filter { it.confidence >= 0.45f }
                            .map { it.text.lowercase(Locale.US) }
                        val has = { terms: Set<String> -> names.any { label -> terms.any { term -> label.contains(term) } } }
                        val profile = when {
                            has(setOf("person", "people", "face", "portrait", "human")) -> Profile.PEOPLE
                            has(setOf("sunset", "sunrise", "dusk", "dawn")) -> Profile.SUNSET
                            has(setOf("night", "darkness", "nighttime", "starry")) -> Profile.NIGHT
                            has(setOf("food", "dish", "cuisine", "fruit", "meal", "dessert")) -> Profile.FOOD
                            has(setOf("flower", "blossom", "bouquet")) -> Profile.FLOWERS
                            has(setOf("animal", "dog", "cat", "bird", "wildlife", "horse")) -> Profile.ANIMALS
                            has(setOf("building", "architecture", "bridge", "skyscraper", "city")) -> Profile.ARCHITECTURE
                            has(setOf("indoor", "room", "kitchen", "office", "furniture")) -> Profile.INDOOR
                            has(setOf("forest", "tree", "plant", "grass", "garden", "vegetation")) -> Profile.NATURE
                            has(setOf("sky", "landscape", "mountain", "water", "beach", "ocean", "lake")) -> Profile.OUTDOOR
                            else -> Profile.GENERAL
                        }
                        onResult(profile)
                    }
                    .addOnFailureListener(executor) {
                        if (sample !== bitmap) sample.recycle()
                        onFailure()
                    }
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    fun tune(settings: EnhanceSettings, profile: Profile): EnhanceSettings = when (profile) {
        Profile.PEOPLE -> settings.copy(intensity = settings.intensity * 0.82f, warmth = 0f, contrast = 1.06f)
        Profile.OUTDOOR, Profile.NATURE -> settings.copy(intensity = (settings.intensity * 0.92f).coerceAtMost(1f), warmth = 0f, contrast = 1.06f)
        Profile.SUNSET -> settings.copy(intensity = (settings.intensity * 0.82f).coerceAtMost(1f), warmth = 0.015f, contrast = 1.06f)
        Profile.NIGHT -> settings.copy(intensity = settings.intensity * 0.55f, contrast = 1.06f, warmth = 0f)
        Profile.INDOOR -> settings.copy(intensity = settings.intensity * 0.78f, warmth = 0.01f, contrast = 1.06f)
        Profile.FOOD -> settings.copy(intensity = (settings.intensity * 0.88f).coerceAtMost(1f), warmth = 0.015f, contrast = 1.06f)
        Profile.FLOWERS -> settings.copy(intensity = (settings.intensity * 0.96f).coerceAtMost(1f), contrast = 1.06f)
        Profile.ANIMALS -> settings.copy(intensity = settings.intensity * 0.82f, contrast = 1.06f)
        Profile.ARCHITECTURE -> settings.copy(intensity = settings.intensity * 0.72f, contrast = 1.06f)
        Profile.GENERAL -> settings.copy(contrast = 1.06f, warmth = 0f)
    }

    fun close() {
        if (labelerLazy.isInitialized()) labeler.close()
        executor.shutdown()
    }
}
