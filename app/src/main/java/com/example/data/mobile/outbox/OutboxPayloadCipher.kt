package com.example.data.mobile.outbox

import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.session.AndroidKeyStoreCryptoEngine
import com.example.data.mobile.session.SessionCryptoEngine
import com.example.data.mobile.session.SessionStorageException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

enum class OutboxStorageError { KEY_UNAVAILABLE, KEY_LOCKED, CORRUPT, OVERSIZED, LEGACY_CONVERSION_REQUIRED }
class OutboxStorageException(val error: OutboxStorageError) : Exception(error.name)

/** Payload encryption only: indexed identity and delivery metadata remain plaintext. */
class OutboxPayloadCipher internal constructor(private val engine: SessionCryptoEngine) {
    constructor() : this(AndroidKeyStoreCryptoEngine("small_trader_economic_outbox_v1", allowCreationOnEncrypt = false))

    private val magic = byteArrayOf(0x53, 0x54, 0x4f, 0x01)
    private val max = ContractConstants.MAX_PAYLOAD_BYTES
    internal fun isEncrypted(bytes: ByteArray) = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(magic)

    private fun aad(event: OutboxEventEntity): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            listOf("small_trader:economic_outbox:1", event.companyId, event.actorId,
                event.deviceId, event.clientEventId, event.deviceLeaseId, event.capturedAt,
                event.payloadDigest, event.eventVersion.toString()).forEach {
                val bytes = it.toByteArray(Charsets.UTF_8)
                if (bytes.size > 4096) throw OutboxStorageException(OutboxStorageError.OVERSIZED)
                stream.writeInt(bytes.size)
                stream.write(bytes)
            }
        }
        return output.toByteArray()
    }

    internal fun prepare(existing: Boolean) = safe { engine.ensureKeyUsable(existing) }

    internal fun encrypt(event: OutboxEventEntity): OutboxEventEntity {
        val bytes = event.payloadBytes
        if (bytes.size > max) throw OutboxStorageException(OutboxStorageError.OVERSIZED)
        verify(event, bytes)
        val (nonce, encrypted) = safe { engine.encrypt(bytes, aad(event)) }
        if (nonce.size != 12 || encrypted.size != bytes.size + 16)
            throw OutboxStorageException(OutboxStorageError.CORRUPT)
        return event.withPayload(magic + nonce + encrypted)
    }

    internal fun decrypt(event: OutboxEventEntity): OutboxEventEntity {
        val bytes = event.payloadBytes
        if (bytes.size > max + 32) throw OutboxStorageException(OutboxStorageError.OVERSIZED)
        if (!isEncrypted(bytes)) throw OutboxStorageException(OutboxStorageError.LEGACY_CONVERSION_REQUIRED)
        if (bytes.size < 32) throw OutboxStorageException(OutboxStorageError.CORRUPT)
        prepare(true)
        val plain = safe { engine.decrypt(bytes.copyOfRange(4, 16), bytes.copyOfRange(16, bytes.size), aad(event)) }
        verify(event, plain)
        return event.withPayload(plain)
    }

    internal fun verifyLegacy(event: OutboxEventEntity) = verify(event, event.payloadBytes)

    private fun verify(event: OutboxEventEntity, bytes: ByteArray) {
        if (bytes.size > max) throw OutboxStorageException(OutboxStorageError.OVERSIZED)
        if (PayloadDigest.fromBytes(bytes).value != event.payloadDigest)
            throw OutboxStorageException(OutboxStorageError.CORRUPT)
    }

    private fun <T> safe(block: () -> T): T = try { block() } catch (e: SessionStorageException) {
        throw OutboxStorageException(
            when (e.error) {
                is com.example.data.mobile.session.SessionStorageError.Corrupt -> OutboxStorageError.CORRUPT
                is com.example.data.mobile.session.SessionStorageError.Locked -> OutboxStorageError.KEY_LOCKED
                else -> OutboxStorageError.KEY_UNAVAILABLE
            })
    }
}

internal fun OutboxEventEntity.withPayload(bytes: ByteArray) = OutboxEventEntity(
    clientEventId, companyId, actorId, deviceId, deviceLeaseId, capturedAt, payloadDigest, bytes, eventVersion)
