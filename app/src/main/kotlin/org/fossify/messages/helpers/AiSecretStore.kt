package org.fossify.messages.helpers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets at rest with an AES-256/GCM key that never leaves the Android Keystore.
 *
 * The stored representation is `base64(iv):base64(ciphertext)`. Every failure (missing key,
 * corrupted payload, tampered data) degrades to an empty string so callers never leak exceptions
 * or partial secrets.
 */
object AiSecretStore {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "fossify_messages_ai_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SEPARATOR = ":"
    private const val BASE64_FLAGS = Base64.NO_WRAP

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, BASE64_FLAGS) +
                SEPARATOR +
                Base64.encodeToString(ciphertext, BASE64_FLAGS)
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return ""
        return try {
            val iv = Base64.decode(parts[0], BASE64_FLAGS)
            val ciphertext = Base64.decode(parts[1], BASE64_FLAGS)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
