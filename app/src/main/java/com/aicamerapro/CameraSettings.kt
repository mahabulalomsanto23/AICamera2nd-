package com.aicamerapro

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cameraSettingsDataStore by preferencesDataStore(name = "camera_settings")

object CameraSettings {
    private val saveModeKey = stringPreferencesKey("save_mode")
    private val startupModeKey = stringPreferencesKey("startup_mode")
    private val portraitBlurKey = floatPreferencesKey("portrait_blur")
    private val flashEnabledKey = booleanPreferencesKey("flash_enabled")

    suspend fun readSaveMode(context: Context): String =
        context.cameraSettingsDataStore.data.map { it[saveModeKey] ?: "both" }.first()

    suspend fun writeSaveMode(context: Context, mode: String) {
        context.cameraSettingsDataStore.edit { it[saveModeKey] = if (mode == "enhanced") "enhanced" else "both" }
    }

    suspend fun readStartupMode(context: Context): String =
        context.cameraSettingsDataStore.data.map { it[startupModeKey] ?: "AI" }.first()

    suspend fun writeStartupMode(context: Context, mode: String) {
        val allowed = setOf("AI", "VIVID", "NORMAL", "PORTRAIT")
        context.cameraSettingsDataStore.edit { it[startupModeKey] = if (mode in allowed) mode else "AI" }
    }

    suspend fun readPortraitBlur(context: Context): Float =
        context.cameraSettingsDataStore.data.map { it[portraitBlurKey] ?: 0.50f }.first()

    suspend fun writePortraitBlur(context: Context, value: Float) {
        context.cameraSettingsDataStore.edit { it[portraitBlurKey] = value.coerceIn(0.25f, 1f) }
    }

    suspend fun readFlashEnabled(context: Context): Boolean =
        context.cameraSettingsDataStore.data.map { it[flashEnabledKey] ?: false }.first()

    suspend fun writeFlashEnabled(context: Context, enabled: Boolean) {
        context.cameraSettingsDataStore.edit { it[flashEnabledKey] = enabled }
    }
}
