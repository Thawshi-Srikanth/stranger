package com.tk.stranger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object SecurityConfig {
    private const val PREFS_NAME = "sec_vault_prefs"
    private const val KEY_WRAPPED_MASTER = "wrapped_master_key"
    private const val KEYSTORE_ALIAS = "stranger_hardware_master_key"
    private const val PBKDF2_ITERATIONS = 100000
    private const val KEY_BITS = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private val salt = byteArrayOf(
        0x73.toByte(), 0x74.toByte(), 0x72.toByte(), 0x61.toByte(), 0x6E.toByte(), 0x67.toByte(), 0x65.toByte(), 0x72.toByte(),
        0x5F.toByte(), 0x6B.toByte(), 0x64.toByte(), 0x66.toByte(), 0x5F.toByte(), 0x76.toByte(), 0x31.toByte(), 0x21.toByte()
    )

    // AES-256-GCM encrypted R2 credentials (12-byte IV + ciphertext + 128-bit auth tag).
    // Decryption key does not exist anywhere in the binary; derived at runtime from the user's PIN.
    // BEGIN_ENDPOINT_ENC
    private val endpointEnc = byteArrayOf(
        0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x77.toByte(), 0x88.toByte(),
        0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x92.toByte(), 0x15.toByte(), 0x94.toByte(), 0x7E.toByte(),
        0x5F.toByte(), 0x4A.toByte(), 0x3A.toByte(), 0x2D.toByte(), 0x00.toByte(), 0xD0.toByte(), 0xF7.toByte(), 0x48.toByte(),
        0xA2.toByte(), 0xA5.toByte(), 0xAC.toByte(), 0xF8.toByte(), 0xDF.toByte(), 0xF4.toByte(), 0xAA.toByte(), 0x1C.toByte(),
        0x13.toByte(), 0x89.toByte(), 0x6C.toByte(), 0xC8.toByte(), 0x6F.toByte(), 0xA4.toByte(), 0xBC.toByte(), 0x0B.toByte(),
        0x88.toByte(), 0xC4.toByte(), 0x18.toByte(), 0xCA.toByte(), 0x33.toByte(), 0x91.toByte(), 0xFC.toByte(), 0x61.toByte(),
        0x90.toByte(), 0xE5.toByte(), 0xBD.toByte(), 0x43.toByte(), 0x85.toByte(), 0x85.toByte(), 0x26.toByte(), 0xAD.toByte(),
        0x85.toByte(), 0xBB.toByte(), 0xFB.toByte(), 0x47.toByte(), 0x2E.toByte(), 0x08.toByte(), 0x8D.toByte(), 0x56.toByte(),
        0x6B.toByte(), 0xE1.toByte(), 0x83.toByte(), 0xCF.toByte(), 0xB1.toByte(), 0x8C.toByte(), 0x71.toByte(), 0x7C.toByte(),
        0x2A.toByte(), 0x1A.toByte(), 0x51.toByte(), 0xE2.toByte(), 0x05.toByte(), 0x03.toByte(), 0x3F.toByte(), 0x38.toByte(),
        0xD1.toByte(), 0xF2.toByte(), 0x08.toByte(), 0xF0.toByte(), 0xC3.toByte(), 0xB6.toByte(), 0x7B.toByte(), 0x9A.toByte(),
        0x67.toByte(), 0xFD.toByte(), 0xCE.toByte(), 0xE8.toByte(), 0x34.toByte()
    )
    // END_ENDPOINT_ENC

    // BEGIN_BUCKET_ENC
    private val bucketEnc = byteArrayOf(
        0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x77.toByte(), 0x88.toByte(), 0x99.toByte(),
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0x2E.toByte(), 0x8F.toByte(), 0x14.toByte(), 0x12.toByte(),
        0x13.toByte(), 0x0C.toByte(), 0x05.toByte(), 0xAF.toByte(), 0x21.toByte(), 0x38.toByte(), 0xD1.toByte(), 0x48.toByte(),
        0x59.toByte(), 0x68.toByte(), 0x5A.toByte(), 0xAA.toByte(), 0x27.toByte(), 0x07.toByte(), 0xCE.toByte(), 0xC7.toByte()
    )
    // END_BUCKET_ENC

    // BEGIN_ACCESS_ENC
    private val accessKeyEnc = byteArrayOf(
        0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x77.toByte(), 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(),
        0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xD8.toByte(), 0x40.toByte(), 0x92.toByte(), 0x14.toByte(),
        0x45.toByte(), 0x07.toByte(), 0x20.toByte(), 0x58.toByte(), 0x61.toByte(), 0x9A.toByte(), 0x22.toByte(), 0x25.toByte(),
        0xEA.toByte(), 0xF8.toByte(), 0x55.toByte(), 0x74.toByte(), 0x2F.toByte(), 0xBC.toByte(), 0x14.toByte(), 0x27.toByte(),
        0x43.toByte(), 0xF6.toByte(), 0x1A.toByte(), 0x3C.toByte(), 0x78.toByte(), 0x8F.toByte(), 0x32.toByte(), 0xF1.toByte(),
        0x3F.toByte(), 0x47.toByte(), 0xB3.toByte(), 0xB6.toByte(), 0x3D.toByte(), 0x28.toByte(), 0x28.toByte(), 0x8D.toByte(),
        0x61.toByte(), 0xDC.toByte(), 0x51.toByte(), 0x69.toByte(), 0xF8.toByte(), 0x50.toByte(), 0xC3.toByte(), 0xD4.toByte(),
        0xC1.toByte(), 0x35.toByte(), 0xD7.toByte(), 0x91.toByte()
    )
    // END_ACCESS_ENC

    // BEGIN_SECRET_ENC
    private val secretKeyEnc = byteArrayOf(
        0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x77.toByte(), 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
        0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(), 0x52.toByte(), 0x1F.toByte(), 0xC4.toByte(), 0x88.toByte(),
        0xD1.toByte(), 0x3C.toByte(), 0x1B.toByte(), 0x15.toByte(), 0xC7.toByte(), 0x9E.toByte(), 0x51.toByte(), 0x94.toByte(),
        0x88.toByte(), 0x2F.toByte(), 0x7A.toByte(), 0x85.toByte(), 0xE1.toByte(), 0x1B.toByte(), 0xE1.toByte(), 0x19.toByte(),
        0xA1.toByte(), 0x6D.toByte(), 0xF2.toByte(), 0xFC.toByte(), 0x88.toByte(), 0x3A.toByte(), 0x33.toByte(), 0xF5.toByte(),
        0x8B.toByte(), 0x69.toByte(), 0x51.toByte(), 0xCF.toByte(), 0x65.toByte(), 0x08.toByte(), 0xA7.toByte(), 0x2F.toByte(),
        0x88.toByte(), 0xDB.toByte(), 0x75.toByte(), 0xD5.toByte(), 0xA5.toByte(), 0xDD.toByte(), 0x9B.toByte(), 0xDC.toByte(),
        0x3C.toByte(), 0x46.toByte(), 0x67.toByte(), 0x8C.toByte(), 0x77.toByte(), 0x4F.toByte(), 0x73.toByte(), 0xFD.toByte(),
        0x11.toByte(), 0x74.toByte(), 0x70.toByte(), 0x5B.toByte(), 0xA9.toByte(), 0x73.toByte(), 0x3B.toByte(), 0x1C.toByte(),
        0x3A.toByte(), 0xE3.toByte(), 0x9C.toByte(), 0x76.toByte(), 0x86.toByte(), 0x2C.toByte(), 0x34.toByte(), 0x57.toByte(),
        0x32.toByte(), 0xDE.toByte(), 0x4D.toByte(), 0xEA.toByte(), 0xC4.toByte(), 0x6A.toByte(), 0x0B.toByte(), 0x32.toByte(),
        0x46.toByte(), 0x86.toByte(), 0xAF.toByte(), 0x87.toByte()
    )
    // END_SECRET_ENC

    @Volatile
    private var cachedMasterKey: ByteArray? = null

    private fun deriveKeyFromPin(pin: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            skf.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aesGcmDecrypt(key: ByteArray, combinedIvAndCiphertext: ByteArray): ByteArray {
        val iv = combinedIvAndCiphertext.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combinedIvAndCiphertext.copyOfRange(GCM_IV_LENGTH, combinedIvAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Unlocks the AES-256-GCM credentials using the archive PIN (e.g. 0529).
     * Returns true only if the PIN cryptographically authenticates the GCM tag.
     */
    fun unlockWithPin(pin: String, context: Context): Boolean {
        return try {
            val candidateKey = deriveKeyFromPin(pin)
            // Verify key against AES-GCM auth tag of bucketEnc
            aesGcmDecrypt(candidateKey, bucketEnc)
            cachedMasterKey = candidateKey
            saveKeyToHardwareKeystore(candidateKey, context)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Restores the unlocked master key from the hardware-backed Android Keystore
     * after the user passes daily PasscodeManager verification.
     */
    fun restoreFromKeystore(context: Context): Boolean {
        if (cachedMasterKey != null) return true
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wrappedB64 = prefs.getString(KEY_WRAPPED_MASTER, null) ?: return false
            val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
            val iv = wrapped.copyOfRange(0, GCM_IV_LENGTH)
            val ct = wrapped.copyOfRange(GCM_IV_LENGTH, wrapped.size)

            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val key = cipher.doFinal(ct)
            // Verify decrypted key
            aesGcmDecrypt(key, bucketEnc)
            cachedMasterKey = key
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveKeyToHardwareKeystore(masterKey: ByteArray, context: Context) {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val hardwareKey: SecretKey = if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.getKey(KEYSTORE_ALIAS, null) as SecretKey
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGen.generateKey()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, hardwareKey)
            val iv = cipher.iv
            val ct = cipher.doFinal(masterKey)
            val combined = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ct, 0, combined, iv.size, ct.size)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WRAPPED_MASTER, Base64.encodeToString(combined, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            // Ignore hardware keystore errors; key remains in RAM for session
        }
    }

    private fun requireKey(): ByteArray {
        return cachedMasterKey ?: throw IllegalStateException("Archive is locked. Enter PIN first.")
    }

    fun decryptBytes(encryptedPayload: ByteArray): ByteArray {
        return aesGcmDecrypt(requireKey(), encryptedPayload)
    }

    val r2Endpoint: String
        get() = aesGcmDecrypt(requireKey(), endpointEnc).decodeToString()

    val r2BucketName: String
        get() = aesGcmDecrypt(requireKey(), bucketEnc).decodeToString()

    val r2AccessKeyId: String
        get() = aesGcmDecrypt(requireKey(), accessKeyEnc).decodeToString()

    val r2SecretAccessKey: String
        get() = aesGcmDecrypt(requireKey(), secretKeyEnc).decodeToString()
}
