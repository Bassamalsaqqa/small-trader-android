package com.example.data.mobile.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Exception wrapper carrying fail-closed [SessionStorageError].
 * Discards any underlying exception causes or secret payloads.
 */
class SessionStorageException(val error: SessionStorageError) : Exception(error.message)

/**
 * Cryptographic engine interface for AES-256-GCM authenticated encryption.
 * Encapsulates IV generation, AAD binding, and key lifecycle.
 */
internal interface SessionCryptoEngine {
    /**
     * Encrypts [plaintext] with [aad] authentication data.
     * Returns a [Pair] of (12-byte IV, ciphertext with 128-bit authentication tag).
     */
    fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>

    /**
     * Decrypts [ciphertextWithTag] using [iv] and authenticates [aad].
     * Throws [SessionStorageException] with typed fail-closed errors on failure.
     */
    fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Production AndroidKeyStore AES-256-GCM cryptographic engine for API 24+.
 *
 * Keys are managed within AndroidKeyStore without user secrets or external keytool calls.
 * Hardware-backed keystore (TEE) is preferred automatically by the platform if available.
 * StrongBox is NOT unconditionally forced to avoid failing on devices without dedicated StrongBox chips.
 *
 * If a key is missing, permanently invalidated, or ciphertext is tampered, operations fail closed
 * with typed errors ([SessionStorageError.Locked], [SessionStorageError.Unavailable], [SessionStorageError.Corrupt])
 * and NEVER silently recreate keys or delete stored records.
 */
internal class AndroidKeyStoreCryptoEngine(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SessionCryptoEngine {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply {
            load(null)
        }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = getOrCreateKey()
        try {
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: throw SessionStorageException(
                SessionStorageError.Unavailable("Cipher failed to generate unique IV")
            )
            require(iv.size == GCM_IV_LENGTH_BYTES) {
                "Expected $GCM_IV_LENGTH_BYTES bytes IV, got ${iv.size}"
            }
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            return Pair(iv, ciphertext)
        } catch (e: Exception) {
            throw translateException(e)
        }
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        val secretKey = getKey() ?: throw SessionStorageException(
            SessionStorageError.Unavailable("Secret key '$keyAlias' not found in keystore")
        )
        try {
            val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertextWithTag)
        } catch (e: Exception) {
            throw translateException(e)
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        getKey()?.let { return it }

        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            throw SessionStorageException(
                SessionStorageError.Unavailable("Failed to generate AES-256 key in AndroidKeyStore")
            )
        }
    }

    private fun getKey(): SecretKey? {
        return try {
            if (!keyStore.containsAlias(keyAlias)) {
                null
            } else {
                keyStore.getKey(keyAlias, null) as? SecretKey
            }
        } catch (e: Exception) {
            throw translateException(e)
        }
    }

    private fun translateException(e: Exception): SessionStorageException {
        return when (e) {
            is SessionStorageException -> e
            is KeyPermanentlyInvalidatedException -> SessionStorageException(
                SessionStorageError.Locked("Key permanently invalidated")
            )
            is UnrecoverableKeyException, is KeyStoreException -> SessionStorageException(
                SessionStorageError.Unavailable("Key is unrecoverable or keystore unavailable")
            )
            is AEADBadTagException, is BadPaddingException -> SessionStorageException(
                SessionStorageError.Corrupt("Ciphertext authentication tag verification failed")
            )
            is GeneralSecurityException -> SessionStorageException(
                SessionStorageError.Corrupt("Cryptographic operation failed")
            )
            else -> SessionStorageException(
                SessionStorageError.Unavailable("Keystore provider error")
            )
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "small_trader_staff_session_key_v1"
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
