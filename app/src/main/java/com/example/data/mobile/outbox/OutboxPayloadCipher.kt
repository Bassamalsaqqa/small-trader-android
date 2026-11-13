package com.example.data.mobile.outbox

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.OfflineSaleEvent
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.session.AndroidKeyStoreCryptoEngine
import com.example.data.mobile.session.SessionCryptoEngine
import com.example.data.mobile.session.SessionStorageException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class OutboxStorageError { KEY_UNAVAILABLE, KEY_LOCKED, CORRUPT, OVERSIZED, LEGACY_CONVERSION_REQUIRED }
class OutboxStorageException(val error: OutboxStorageError) : Exception(error.name)

/** Payload encryption only: indexed identity and delivery metadata remain plaintext. */
class OutboxPayloadCipher internal constructor(private val engine: SessionCryptoEngine) {
    constructor() : this(AndroidKeyStoreCryptoEngine("small_trader_economic_outbox_v1", allowCreationOnEncrypt = false))

    private val magic = byteArrayOf(0x53, 0x54, 0x4f, 0x01)
    private val max = ContractConstants.MAX_PAYLOAD_BYTES

    internal fun hasStoEnvelope(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0x53.toByte() && bytes[1] == 0x54.toByte() && bytes[2] == 0x4f.toByte()

    internal fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= 32 && bytes.copyOfRange(0, 4).contentEquals(magic)

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
        if (!isEncrypted(bytes)) {
            if (hasStoEnvelope(bytes)) throw OutboxStorageException(OutboxStorageError.CORRUPT)
            throw OutboxStorageException(OutboxStorageError.LEGACY_CONVERSION_REQUIRED)
        }
        if (bytes.size < 32) throw OutboxStorageException(OutboxStorageError.CORRUPT)
        prepare(true)
        val plain = safe { engine.decrypt(bytes.copyOfRange(4, 16), bytes.copyOfRange(16, bytes.size), aad(event)) }
        verify(event, plain)
        return event.withPayload(plain)
    }

    internal fun verifyLegacy(event: OutboxEventEntity) {
        val bytes = event.payloadBytes
        if (bytes.isEmpty() || bytes.size > max) {
            throw OutboxStorageException(if (bytes.size > max) OutboxStorageError.OVERSIZED else OutboxStorageError.CORRUPT)
        }
        if (hasStoEnvelope(bytes)) {
            throw OutboxStorageException(OutboxStorageError.CORRUPT)
        }
        if (PayloadDigest.fromBytes(bytes).value != event.payloadDigest) {
            throw OutboxStorageException(OutboxStorageError.CORRUPT)
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val jsonString = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw OutboxStorageException(OutboxStorageError.CORRUPT)
        }

        val parsedJson = try {
            StrictJsonParser.parse(jsonString).asObject()
        } catch (e: Exception) {
            throw OutboxStorageException(OutboxStorageError.CORRUPT)
        }

        when (event.eventVersion) {
            1 -> {
                val model = try {
                    OfflineSaleEvent.fromStrictJson(parsedJson)
                } catch (e: Exception) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
                if (model.eventVersion != 1 ||
                    model.clientEventId.value != event.clientEventId ||
                    model.companyId.value != event.companyId ||
                    model.actorId.value != event.actorId ||
                    model.deviceId.value != event.deviceId ||
                    model.deviceLeaseId.value != event.deviceLeaseId ||
                    model.capturedAt != event.capturedAt ||
                    model.computePayloadDigest().value != event.payloadDigest
                ) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
                val canonicalBytes = try {
                    CanonicalJsonSerializer.serialize(model).toByteArray(StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
                if (!bytes.contentEquals(canonicalBytes)) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
            }
            2 -> {
                val model = try {
                    OfflineSaleEventV2.fromStrictJson(parsedJson)
                } catch (e: Exception) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
                if (model.eventVersion != 2 ||
                    model.clientEventId.value != event.clientEventId ||
                    model.companyId.value != event.companyId ||
                    model.actorId.value != event.actorId ||
                    model.deviceId.value != event.deviceId ||
                    model.deviceLeaseId.value != event.deviceLeaseId ||
                    model.capturedAt != event.capturedAt ||
                    model.computePayloadDigest().value != event.payloadDigest
                ) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
                val canonicalBytes = try {
                    CanonicalJsonSerializer.serialize(model).toByteArray(StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
                if (!bytes.contentEquals(canonicalBytes)) {
                    throw OutboxStorageException(OutboxStorageError.CORRUPT)
                }
            }
            else -> throw OutboxStorageException(OutboxStorageError.CORRUPT)
        }
    }

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
