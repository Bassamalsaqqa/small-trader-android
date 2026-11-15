package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.contract.v2.OnlineSaleIntentProfile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Arrays

/**
 * Explicit, typed, immutable prepared ONLINE sale intent.
 *
 * Encapsulates:
 * - Authoritative server context (companyId, actorId, currencyMinorUnits).
 * - Target identifiers: clientEventId, deviceId, currency.
 * - Raw wire payload bytes bounded strictly to 1 MiB, validated for strict canonical UTF-8 JSON.
 * - Authoritative normative profile digest derived via [OnlineSaleIntentProfile.fromContextAndRequest].
 * - Monotonic scope generation tracking to guard against stale authentication callbacks.
 *
 * Invariants:
 * - Wire bytes are immutable and defensively copied on access.
 * - Wire bytes strictly match canonical RFC 8785 representation of the RAW 7 request keys.
 * - No Double or unquoted float lexemes permitted.
 * - Pre-dispatch validation guarantees strict event/company/device/currency parity.
 * - Sanitized toString() prevents credential or wire leaks.
 */
class PreparedOnlineSaleIntent private constructor(
    val context: OnlineSaleContext,
    val clientEventId: ClientUuid,
    val deviceId: ClientUuid,
    val currency: String,
    val expectedNormativeProfileDigest: PayloadDigest,
    val profile: OnlineSaleIntentProfile,
    rawWireBytes: ByteArray,
    val scopeGeneration: Long
) {
    private val defensiveWireBytes: ByteArray = rawWireBytes.clone()

    val rawWireBytes: ByteArray
        get() = defensiveWireBytes.clone()

    val byteCount: Int
        get() = defensiveWireBytes.size

    /**
     * Validates that this prepared intent strictly matches the expected runtime dispatch scope.
     * Throws [IllegalArgumentException] on any discrepancy BEFORE any network I/O.
     */
    fun validateExactScope(
        expectedCompanyId: ServerUlid,
        expectedDeviceId: ClientUuid,
        expectedCurrency: String,
        expectedClientEventId: ClientUuid? = null
    ) {
        require(context.companyId == expectedCompanyId) { "COMPANY_MISMATCH" }
        require(deviceId == expectedDeviceId) { "DEVICE_MISMATCH" }
        require(currency == expectedCurrency) { "CURRENCY_MISMATCH" }
        if (expectedClientEventId != null) {
            require(clientEventId == expectedClientEventId) { "CLIENT_EVENT_ID_MISMATCH" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedOnlineSaleIntent) return false
        return context == other.context &&
                clientEventId == other.clientEventId &&
                deviceId == other.deviceId &&
                currency == other.currency &&
                expectedNormativeProfileDigest == other.expectedNormativeProfileDigest &&
                scopeGeneration == other.scopeGeneration &&
                Arrays.equals(defensiveWireBytes, other.defensiveWireBytes)
    }

    override fun hashCode(): Int {
        var result = context.hashCode()
        result = 31 * result + clientEventId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + currency.hashCode()
        result = 31 * result + expectedNormativeProfileDigest.hashCode()
        result = 31 * result + scopeGeneration.hashCode()
        result = 31 * result + Arrays.hashCode(defensiveWireBytes)
        return result
    }

    override fun toString(): String =
        "PreparedOnlineSaleIntent(companyId=${context.companyId}, clientEventId=$clientEventId, " +
                "deviceId=$deviceId, currency='$currency', digest=$expectedNormativeProfileDigest, " +
                "bytes=${defensiveWireBytes.size}, scopeGen=$scopeGeneration)"

    companion object {
        const val MAX_WIRE_BYTES = 1024 * 1024 // Strict 1 MiB bound

        val ALLOWED_RAW_WIRE_KEYS: Set<String> = setOf(
            "client_event_id",
            "device_id",
            "customer_id",
            "warehouse_id",
            "currency",
            "lines",
            "payment"
        )

        /**
         * Parses, validates, and prepares an immutable online intent from raw wire bytes.
         *
         * @param context Authoritative company/actor/minor-units context
         * @param rawWireBytes Raw request bytes in UTF-8
         * @param scopeGeneration Session generation at preparation time
         * @param expectedDigest Optional authoritative golden digest for verification
         */
        fun prepare(
            context: OnlineSaleContext,
            rawWireBytes: ByteArray,
            scopeGeneration: Long = 0L,
            expectedDigest: PayloadDigest? = null
        ): PreparedOnlineSaleIntent {
            require(rawWireBytes.isNotEmpty()) { "Wire payload cannot be empty" }
            require(rawWireBytes.size <= MAX_WIRE_BYTES) {
                "Wire payload exceeds 1 MiB limit"
            }

            // Strict UTF-8 decoding with REPORT on malformed or unmappable bytes
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = try {
                decoder.decode(ByteBuffer.wrap(rawWireBytes.clone()))
            } catch (_: Exception) {
                throw IllegalArgumentException("Malformed UTF-8 wire payload")
            }
            val rawJsonString = charBuffer.toString()

            // Strict canonical JSON parsing: rejects duplicates, floats/Doubles, invalid lexemes
            val rootObj = try {
                StrictJsonParser.parse(rawJsonString).asObject()
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid canonical JSON wire payload")
            }

            // Strict raw 7-key validation
            val unexpected = rootObj.properties.keys - ALLOWED_RAW_WIRE_KEYS
            require(unexpected.isEmpty()) { "Unexpected keys in wire payload: $unexpected" }
            val missing = ALLOWED_RAW_WIRE_KEYS - rootObj.properties.keys
            require(missing.isEmpty()) { "Missing required wire payload keys: $missing" }

            // Strict canonical byte equality check against RFC 8785 canonical bytes of the raw 7 keys
            val canonicalBytes = try {
                CanonicalJsonSerializer.serializeToUtf8Bytes(rootObj)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid canonical JSON wire payload")
            }
            require(Arrays.equals(rawWireBytes, canonicalBytes)) {
                "Wire payload does not match strict canonical JSON byte representation"
            }

            val clientEventId = ClientUuid.parse(rootObj.require("client_event_id").asString())
            val deviceId = ClientUuid.parse(rootObj.require("device_id").asString())
            val currency = rootObj.require("currency").asString()

            // Derive authoritative normative profile from context and wire request
            val profile = OnlineSaleIntentProfile.fromContextAndRequest(context, rootObj)
            val computedDigest = profile.computePayloadDigest()

            if (expectedDigest != null) {
                require(computedDigest == expectedDigest) {
                    "Computed normative profile digest does not match expected digest"
                }
            }

            return PreparedOnlineSaleIntent(
                context = context,
                clientEventId = clientEventId,
                deviceId = deviceId,
                currency = currency,
                expectedNormativeProfileDigest = computedDigest,
                profile = profile,
                rawWireBytes = rawWireBytes,
                scopeGeneration = scopeGeneration
            )
        }
    }
}
