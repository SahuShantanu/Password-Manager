package com.example.security

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class SecurityPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "vaultx_security_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_BIOMETRICS_ENABLED = "biometrics_enabled"
        private const val KEY_AUTO_LOCK_TIME = "auto_lock_time"
        private const val KEY_CLIPBOARD_TIME = "clipboard_time"
        private const val KEY_RECYCLE_RETENTION = "recycle_retention"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_SECURE_SCREENSHOTS = "secure_screenshots"
        private const val KEY_FAILED_LOGS = "failed_logs"
    }

    fun isPinSetup(): Boolean {
        return prefs.getString(KEY_PIN_HASH, null) != null
    }

    fun setupPin(pin: String) {
        val salt = CryptoEngine.generateSalt()
        val hash = CryptoEngine.hashPin(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        return CryptoEngine.hashPin(pin, salt) == hash
    }

    var isBiometricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRICS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRICS_ENABLED, value).apply()

    var autoLockTimeoutSeconds: Int
        get() = prefs.getInt(KEY_AUTO_LOCK_TIME, 60) // Default 1 min (60s)
        set(value) = prefs.edit().putInt(KEY_AUTO_LOCK_TIME, value).apply()

    var clipboardTimeoutSeconds: Int
        get() = prefs.getInt(KEY_CLIPBOARD_TIME, 30) // Default 30s
        set(value) = prefs.edit().putInt(KEY_CLIPBOARD_TIME, value).apply()

    var recycleBinRetentionDays: Int
        get() = prefs.getInt(KEY_RECYCLE_RETENTION, 30) // Default 30 days
        set(value) = prefs.edit().putInt(KEY_RECYCLE_RETENTION, value).apply()

    var failedAttemptsCount: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    var secureScreenshotsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SECURE_SCREENSHOTS, true) // Secure by default
        set(value) = prefs.edit().putBoolean(KEY_SECURE_SCREENSHOTS, value).apply()

    fun logFailedAttempt() {
        val count = failedAttemptsCount + 1
        failedAttemptsCount = count
        
        val logs = getFailedLogs()
        val logObj = JSONObject()
        logObj.put("timestamp", System.currentTimeMillis())
        logObj.put("attempt_number", count)
        
        val updatedLogs = JSONArray(logs).put(logObj)
        prefs.edit().putString(KEY_FAILED_LOGS, updatedLogs.toString()).apply()
    }

    fun clearFailedAttempts() {
        failedAttemptsCount = 0
    }

    fun getFailedLogs(): String {
        return prefs.getString(KEY_FAILED_LOGS, "[]") ?: "[]"
    }

    fun clearAllLogs() {
        prefs.edit().remove(KEY_FAILED_LOGS).apply()
        clearFailedAttempts()
    }
}
