package com.tk.stranger

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PasscodeManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("passcode_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LEGACY_PASSCODE = "passcode"
        private const val KEY_PASSCODE_HASH = "passcode_hash"
        private const val KEY_PASSCODE_SALT = "passcode_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL_MS = "lockout_until_ms"

        private const val PBKDF2_ITERATIONS = 65536
        private const val HASH_BITS = 256
        private const val SALT_BYTES = 16
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
    }

    init {
        migrateLegacyPasscodeIfNeeded()
    }

    private fun migrateLegacyPasscodeIfNeeded() {
        val legacyPlain = prefs.getString(KEY_LEGACY_PASSCODE, null)
        if (legacyPlain != null) {
            setPasscode(legacyPlain)
            prefs.edit().remove(KEY_LEGACY_PASSCODE).apply()
        }
    }

    fun setPasscode(passcode: String) {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val hash = pbkdf2(passcode, salt)
        prefs.edit()
            .putString(KEY_PASSCODE_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PASSCODE_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(KEY_LEGACY_PASSCODE)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .apply()
    }

    fun hasPasscode(): Boolean {
        migrateLegacyPasscodeIfNeeded()
        return prefs.getString(KEY_PASSCODE_HASH, null) != null
    }

    fun getRemainingLockoutSeconds(): Int {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        val remainingMs = lockoutUntil - System.currentTimeMillis()
        return if (remainingMs > 0) ((remainingMs + 999) / 1000).toInt() else 0
    }

    fun recordFailedAttempt() {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (attempts >= MAX_ATTEMPTS) {
            editor.putLong(KEY_LOCKOUT_UNTIL_MS, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
            editor.putInt(KEY_FAILED_ATTEMPTS, 0)
        }
        editor.apply()
    }

    fun verifyPasscode(passcode: String): Boolean {
        migrateLegacyPasscodeIfNeeded()
        if (getRemainingLockoutSeconds() > 0) {
            return false
        }

        val saltB64 = prefs.getString(KEY_PASSCODE_SALT, null) ?: return false
        val expectedHashB64 = prefs.getString(KEY_PASSCODE_HASH, null) ?: return false

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(expectedHashB64, Base64.NO_WRAP)
        val actualHash = pbkdf2(passcode, salt)

        val matched = MessageDigest.isEqual(expectedHash, actualHash)
        if (matched) {
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
                .apply()
        } else {
            recordFailedAttempt()
        }
        return matched
    }

    private fun pbkdf2(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
