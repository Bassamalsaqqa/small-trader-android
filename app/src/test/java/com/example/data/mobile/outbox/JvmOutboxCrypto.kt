package com.example.data.mobile.outbox

import com.example.data.mobile.session.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class JvmOutboxCrypto : SessionCryptoEngine {
    var missing = false
    var failure: Throwable? = null
    var keyGenerationCount = 0
    private var key: javax.crypto.SecretKey? = null

    init {
        key = generateKey()
    }

    private fun generateKey(): javax.crypto.SecretKey {
        keyGenerationCount++
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun ensureKeyUsable(forExistingRecord: Boolean) {
        failure?.let { throw it }
        if (missing) {
            if (forExistingRecord) {
                throw SessionStorageException(SessionStorageError.Unavailable("Key is missing for existing record"))
            } else {
                key = generateKey()
                missing = false
            }
        }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        ensureKeyUsable(false)
        val currentKey = key ?: throw SessionStorageException(SessionStorageError.Unavailable())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, currentKey)
        cipher.updateAAD(aad)
        return cipher.iv to cipher.doFinal(plaintext)
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        ensureKeyUsable(true)
        val currentKey = key ?: throw SessionStorageException(SessionStorageError.Unavailable())
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, currentKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertextWithTag)
        } catch (e: java.security.GeneralSecurityException) {
            throw SessionStorageException(SessionStorageError.Corrupt())
        }
    }
}
internal val testCipher = OutboxPayloadCipher(JvmOutboxCrypto())
internal fun testOutboxRepository(database: OutboxDatabase, clock: () -> Long = { System.currentTimeMillis() }) =
    OutboxRepositoryImpl(database, clock, testCipher)

