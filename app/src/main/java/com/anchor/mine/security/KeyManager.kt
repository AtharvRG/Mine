package com.anchor.mine.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class KeyManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "mine_encrypted_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveGroqKey(key: String) {
        sharedPreferences.edit().putString("GROQ_API_KEY", key).apply()
    }

    fun getGroqKey(): String? {
        return sharedPreferences.getString("GROQ_API_KEY", null)
    }

    fun saveNimKey(key: String) {
        sharedPreferences.edit().putString("NIM_API_KEY", key).apply()
    }

    fun getNimKey(): String? {
        return sharedPreferences.getString("NIM_API_KEY", null)
    }

    fun completeOnboarding() {
        sharedPreferences.edit().putBoolean("IS_ONBOARDING_COMPLETE", true).apply()
    }

    fun isOnboardingComplete(): Boolean {
        return sharedPreferences.getBoolean("IS_ONBOARDING_COMPLETE", false)
    }

    // --- TOGGLE STATES ---
    fun setAmbientActive(isActive: Boolean) {
        sharedPreferences.edit().putBoolean("AMBIENT_ACTIVE", isActive).apply()
    }

    fun isAmbientActive(): Boolean {
        return sharedPreferences.getBoolean("AMBIENT_ACTIVE", false)
    }

    fun setNudgeActive(isActive: Boolean) {
        sharedPreferences.edit().putBoolean("NUDGE_ACTIVE", isActive).apply()
    }

    fun isNudgeActive(): Boolean {
        return sharedPreferences.getBoolean("NUDGE_ACTIVE", false)
    }
}