package com.example.data.mobile.outbox

import com.example.data.mobile.session.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class JvmOutboxCrypto : SessionCryptoEngine {
    var missing = false
    var failure: Throwable? = null
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun ensureKeyUsable(forExistingRecord: Boolean) {
        failure?.let { throw it }
        if (missing) throw SessionStorageException(SessionStorageError.Unavailable())
    }
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        ensureKeyUsable(false)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return cipher.iv to cipher.doFinal(plaintext)
    }
    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        ensureKeyUsable(true)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
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
