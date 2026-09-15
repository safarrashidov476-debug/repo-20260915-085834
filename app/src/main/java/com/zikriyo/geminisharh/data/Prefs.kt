package com.zikriyo.geminisharh.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gemini_sharh_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** Azure Speech subscription key (Sardor / Madina uchun) */
    var azureKey: String
        get() = prefs.getString(KEY_AZURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AZURE, value.trim()).apply()

    var azureRegion: String
        get() = prefs.getString(KEY_AZURE_REGION, "eastus") ?: "eastus"
        set(value) = prefs.edit().putString(KEY_AZURE_REGION, value.trim().ifBlank { "eastus" }).apply()

    var requestDelaySec: Float
        get() = prefs.getFloat(KEY_DELAY, 3f)
        set(value) = prefs.edit().putFloat(KEY_DELAY, value.coerceIn(0f, 30f)).apply()

    var voiceGender: String
        get() = prefs.getString(KEY_GENDER, "male") ?: "male"
        set(value) = prefs.edit().putString(KEY_GENDER, value).apply()

    var selectedVoiceId: String
        get() {
            val saved = prefs.getString(KEY_VOICE, null)
            if (!saved.isNullOrBlank()) return saved
            return VoiceModels.defaultVoice(voiceGender)
        }
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var selectedVisionModel: String
        get() = prefs.getString(KEY_VISION_MODEL, "models/gemini-3.6-flash")
            ?: "models/gemini-3.6-flash"
        set(value) = prefs.edit().putString(KEY_VISION_MODEL, value).apply()

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_AZURE = "azure_key"
        private const val KEY_AZURE_REGION = "azure_region"
        private const val KEY_DELAY = "request_delay"
        private const val KEY_GENDER = "voice_gender"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_VISION_MODEL = "vision_model"
    }
}
