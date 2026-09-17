package com.aiconverse.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores API keys on-device, encrypted. Keys are entered once in the app's Settings
 * screen at runtime — no rebuilding the APK required to change or update them.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ai_converse_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback so the app still works even if encrypted prefs fail on a given device.
        context.getSharedPreferences("ai_converse_prefs_fallback", Context.MODE_PRIVATE)
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI, value).apply()

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENROUTER, value).apply()

    var openRouterModel: String
        get() = prefs.getString(KEY_OR_MODEL, DEFAULT_OPENROUTER_MODEL) ?: DEFAULT_OPENROUTER_MODEL
        set(value) = prefs.edit().putString(KEY_OR_MODEL, value).apply()

    var aiOneName: String
        get() = prefs.getString(KEY_NAME_1, "Nova") ?: "Nova"
        set(value) = prefs.edit().putString(KEY_NAME_1, value).apply()

    var aiTwoName: String
        get() = prefs.getString(KEY_NAME_2, "Echo") ?: "Echo"
        set(value) = prefs.edit().putString(KEY_NAME_2, value).apply()

    fun hasRequiredKeys(): Boolean = geminiApiKey.isNotBlank() && openRouterApiKey.isNotBlank()

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_OR_MODEL = "openrouter_model"
        private const val KEY_NAME_1 = "ai_one_name"
        private const val KEY_NAME_2 = "ai_two_name"

        const val DEFAULT_OPENROUTER_MODEL = "meta-llama/llama-3.1-8b-instruct:free"
    }
}
