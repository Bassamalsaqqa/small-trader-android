package com.example.data.mobile.session

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Isolated test cryptographic engine using standard JVM AES/GCM/NoPadding.
 *
 * Implements [SessionCryptoEngine] for unit testing without requiring AndroidKeyStore
 * or on-device hardware. Provides deterministic test seams for simulating locked keys,
 * missing keys, and corrupted authentication tags.
 */
internal class JvmAesGcmCryptoEngine(
    var secretKey: SecretKey? = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"),
    var isLocked: Boolean = false
) : SessionCryptoEngine {

    private val secureRandom = SecureRandom()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        if (isLocked) {
            throw SessionStorageException(
                SessionStorageError.Locked("Simulated key locked or permanently invalidated")
            )
        }
        val key = secretKey ?: throw SessionStorageException(
            SessionStorageError.Unavailable("Simulated secret key not found in keystore")
        )

        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        cipher.updateAAD(aad)
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return Pair(iv, ciphertextWithTag)
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        if (isLocked) {
            throw SessionStorageException(
                SessionStorageError.Locked("Simulated key locked or permanently invalidated")
            )
        }
        val key = secretKey ?: throw SessionStorageException(
            SessionStorageError.Unavailable("Simulated secret key not found in keystore")
        )

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextWithTag)
        } catch (e: AEADBadTagException) {
            throw SessionStorageException(
                SessionStorageError.Corrupt("Ciphertext authentication tag verification failed")
            )
        } catch (e: BadPaddingException) {
            throw SessionStorageException(
                SessionStorageError.Corrupt("Ciphertext padding/tag corrupted")
            )
        } catch (e: Exception) {
            throw SessionStorageException(
                SessionStorageError.Corrupt("Decryption failed: ${e.message}")
            )
        }
    }
}
