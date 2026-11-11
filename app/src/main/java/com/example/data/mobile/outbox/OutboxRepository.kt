package com.example.data.mobile.outbox

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.OfflineSaleEvent
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2
import com.example.data.mobile.contract.v2.PaymentDecimalConsistency
import com.example.data.mobile.contract.ContractConstants
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

interface OutboxRepository {

    companion object {
        const val MAX_BATCH_SIZE = 100
        const val MAX_LEASE_DURATION_MILLIS = 300_000L // 5 minutes
    }

    /**
     * Atomically captures a validated offline sale event.
     *
     * Invariants:
     * - Strict UTF-8 validation with REPORT error action (rejection of malformed bytes without silent replacement).
     * - Defensive copies of byte payloads to eliminate mutable aliasing.
     * - Cross-tenant and cross-scope isolation: same event UUID across companies is strictly isolated;
     *   same company foreign actor/device collision is rejected with zero record or state leakage.
     * - Queue lock respect: captures occurring while auth is denied are stored as AUTH_BLOCKED,
     *   preserving cash and bytes while preventing worker drains.
     * - Idempotent replay: identical scope and digest returns existing record unmodified.
     * - Conflict preservation: altered payload marks CONFLICT without replacing original event bytes,
     *   and cannot demote a CONFIRMED record.
     */
    fun captureSale(
        event: OfflineSaleEvent,
        rawUtf8Json: ByteArray? = null
    ): CaptureResult

    /**
     * Atomically captures a validated Contract Revision 3 (API v2) offline sale event.
     *
     * Invariants enforced:
     * - Strict UTF-8 validation with REPORT error action (rejection of malformed bytes without silent replacement).
     * - Explicit scope parameters (companyId, actorId, deviceId) MUST match the captured event identity before
     *   any existing row is queried or disclosed.
     * - Mandatory qualified currencyMinorUnits (0..6): rejects null, negative, or > 6; no fallback to 2 or currency guessing.
     * - Money semantics validation before database write:
     *   - Payment settled must be strictly positive and conform to currencyMinorUnits scale.
     *   - Cash tender conservation: amountTendered == amountSettled + changeReturned, with tendered and change
     *     conforming to currencyMinorUnits scale.
     *   - Sum of line totals must exactly equal amountSettled.
     *   - Each lineTotal and taxAmount must conform to currencyMinorUnits scale.
     *   - Quantity, unitPrice, and taxRate preserve up to 6 decimal scale according to candidate specification without silent rounding.
     * - Defensive copies of byte payloads to eliminate mutable source aliasing (TOCTOU protection).
     * - Stored payload_bytes is the exact canonical UTF-8 JSON bytes of the bare event, with stable eventVersion = 2.
     * - rawCanonicalEventUtf8 represents CANONICAL BARE EVENT UTF-8 ONLY. Intake envelopes, fixture wrappers,
     *   arbitrary JSON roots, noncanonical whitespace/key-ordering/decimal lexemes are strictly rejected before write.
     *   Zero silent re-encoding or fallback serialization.
     * - Input bounded to 1 MiB (ContractConstants.MAX_PAYLOAD_BYTES) before decode allocation.
     * - Same company, event unique identity spans v1/v2:
     *   - Same identity + matching payload digest: idempotent duplicate replay (existing record unmodified).
     *   - Same identity + altered digest: scope-owned conflict, marking CONFLICT without replacing original event bytes.
     *   - Terminal confirmed state immutability: CONFIRMED records can never be demoted by altered captures.
     *   - Cross-scope collision (same company, foreign actor or device): generic rejection with zero existing digests or state leakage.
     *   - Top-level cross-tenant isolation: identical clientEventId under a different companyId is completely independent.
     * - Persistent auth lock respect: captures occurring while auth is denied are stored as AUTH_BLOCKED atomically,
     *   preserving cash and bytes while preventing worker drains; capture NEVER unlocks auth.
     *
     * NOTE: This storage API provides local persistence and durability invariants ONLY.
     * It does NOT authorize cash sales, does NOT enforce offline business policy, does NOT issue
     * token/lease/master authorization proofs, and does NOT claim live capture.
     */
    fun captureSaleV2(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        currencyMinorUnits: Int,
        intakeRequest: OfflineSaleIntakeRequestV2,
        rawCanonicalEventUtf8: ByteArray? = null
    ): CaptureResult

    fun captureSaleV2(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        currencyMinorUnits: Int,
        event: OfflineSaleEventV2,
        rawCanonicalEventUtf8: ByteArray? = null
    ): CaptureResult

    fun captureSaleV2(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        currencyMinorUnits: Int,
        rawCanonicalEventUtf8: ByteArray
    ): CaptureResult

    /**
     * Claims the next batch of eligible records using lease-bounded Compare-And-Set (CAS).
     * Guarded by transaction and persisted scope lock.
     * Prevents parallel drains across concurrent workers.
     * Recovers stale in-flight records whose leases have expired.
     */
    fun claimNextBatch(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        batchSize: Int = 10,
        leaseDurationMillis: Long = 30_000L
    ): ClaimedBatch

    /**
     * Records a transmission timeout. Transitions record to 'unknown_ack'.
     * Requires status to be 'in_flight' and matching claimToken.
     * NEVER confirms on timeout and NEVER regresses a confirmed record.
     */
    fun recordTimeout(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        claimToken: String,
        errorMessage: String
    ): Boolean

    /**
     * Confirms delivery strictly upon verified server acknowledgement via worker claim.
     *
     * Invariants:
     * - Requires active claim lease (non-blank claimToken, status in_flight, lease not expired).
     * - Validates server ULID format and payload digest parity.
     * - Completed record exact mapping replay: re-confirming identical mapping returns true without mutation.
     * - Conflicting mapping on confirmed record is rejected, preserving original.
     * - Rejects un-claimed records (pending, auth_blocked) and conflict records.
     */
    fun confirmDelivery(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        invoiceUlid: ServerUlid,
        paymentUlid: ServerUlid,
        serverAcknowledgedAt: String,
        claimToken: String
    ): Boolean

    /**
     * Reconciles delivery confirmation from status query without worker claim token.
     * Explicit path separate from worker claims. Only transitions in_flight or unknown_ack.
     */
    fun reconcileDeliveryConfirmation(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        invoiceUlid: ServerUlid,
        paymentUlid: ServerUlid,
        serverAcknowledgedAt: String
    ): Boolean

    /**
     * Locks queue upon HTTP 401 authentication denial.
     * Persists an explicit scope lock entity keyed by (company_id, actor_id, device_id).
     * Transitions active records (pending, in_flight, unknown_ack) to auth_blocked.
     * Preserves confirmed and conflict records intact without deletion.
     */
    fun lockQueueOnAuthDenied(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        reason: String
    ): Int

    /**
     * Restores queue processing after manager re-authentication for this specific scope.
     * Removes the persisted scope lock and transitions auth_blocked records back to pending.
     */
    fun unlockQueueAfterReauth(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Int

    /**
     * Checks if the queue is locked for the given scope via persisted lock table.
     */
    fun isQueueLocked(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Boolean

    /**
     * Retrieves an outbox record strictly scoped to company, actor, and device.
     */
    fun getRecord(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid
    ): OutboxRecord?

    /**
     * Retrieves all records within the specified tenant/user/device scope.
     */
    fun getRecordsByScope(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): List<OutboxRecord>

    /**
     * Retrieves records filtered by scope and status.
     */
    fun getRecordsByStatus(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        status: OutboxStatus
    ): List<OutboxRecord>
}

class OutboxRepositoryImpl(
    private val database: OutboxDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : OutboxRepository {

    override fun captureSale(
        event: OfflineSaleEvent,
        rawUtf8Json: ByteArray?
    ): CaptureResult {
        // Defensive copy at entry BEFORE validation (TOCTOU defensive copy)
        val clonedRaw = rawUtf8Json?.clone()

        if (clonedRaw != null) {
            // Bound input 1 MiB BEFORE decode allocation
            require(clonedRaw.size <= ContractConstants.MAX_PAYLOAD_BYTES) {
                "rawUtf8Json payload size ${clonedRaw.size} exceeds maximum allowed ${ContractConstants.MAX_PAYLOAD_BYTES} bytes"
            }
        }

        // 1. Strict UTF-8 decoding & validation
        val canonicalBytes = if (clonedRaw != null) {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val jsonString = try {
                decoder.decode(ByteBuffer.wrap(clonedRaw)).toString()
            } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("Malformed UTF-8 byte sequence in rawUtf8Json for event ${event.clientEventId}", e)
            }
            val parsedJson = StrictJsonParser.parse(jsonString).asObject()
            val parsedEvent = OfflineSaleEvent.fromStrictJson(parsedJson)
            require(parsedEvent == event) {
                "Provided rawUtf8Json does not match OfflineSaleEvent (client_event_id: ${event.clientEventId})"
            }
            val canonicalSerializerBytes = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)
            require(clonedRaw.contentEquals(canonicalSerializerBytes)) {
                "Provided rawUtf8Json is not canonical (byte exact parity required) for event ${event.clientEventId}"
            }
            clonedRaw
        } else {
            CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)
        }

        val computedDigest = event.computePayloadDigest()
        val byteDigest = PayloadDigest.fromBytes(canonicalBytes)
        require(computedDigest == byteDigest) {
            "Canonical bytes digest ($byteDigest) does not match event digest ($computedDigest) for event ${event.clientEventId}"
        }

        return executeCaptureTransaction(
            companyIdStr = event.companyId.value,
            actorIdStr = event.actorId.value,
            deviceIdStr = event.deviceId.value,
            deviceLeaseIdStr = event.deviceLeaseId.value,
            clientEventIdStr = event.clientEventId.value,
            capturedAtStr = event.capturedAt,
            eventVersion = event.eventVersion,
            payloadDigestStr = computedDigest.value,
            canonicalBytes = canonicalBytes
        )
    }

    override fun captureSaleV2(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        currencyMinorUnits: Int,
        rawCanonicalEventUtf8: ByteArray
    ): CaptureResult {
        // 1. Defensive copy at entry BEFORE validation (TOCTOU defensive copy)
        val clonedRaw = rawCanonicalEventUtf8.clone()

        // 2. Bound input 1 MiB BEFORE decode allocation
        require(clonedRaw.size <= ContractConstants.MAX_PAYLOAD_BYTES) {
            "rawCanonicalEventUtf8 size ${clonedRaw.size} exceeds maximum allowed 1 MiB (${ContractConstants.MAX_PAYLOAD_BYTES} bytes)"
        }

        // 3. Strict UTF-8 decoding with REPORT (no silent replacement)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val jsonString = try {
            decoder.decode(ByteBuffer.wrap(clonedRaw)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Malformed UTF-8 byte sequence in rawCanonicalEventUtf8", e)
        }

        // 4. Strict parse direct root OfflineSaleEventV2:
        // Strictly rejects intake envelope (e.g. "event", "payload_digest"), fixture wrapper (e.g. "request"),
        // arbitrary root (e.g. {}), or unknown keys.
        val parsedJson = StrictJsonParser.parse(jsonString).asObject()
        val event = OfflineSaleEventV2.fromStrictJson(parsedJson)

        return captureSaleV2(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = currencyMinorUnits,
            event = event,
            rawCanonicalEventUtf8 = clonedRaw
        )
    }

    override fun captureSaleV2(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        currencyMinorUnits: Int,
        event: OfflineSaleEventV2,
        rawCanonicalEventUtf8: ByteArray?
    ): CaptureResult {
        val intakeReq = OfflineSaleIntakeRequestV2(
            clientEventId = event.clientEventId,
            deviceId = event.deviceId,
            capturedAt = event.capturedAt,
            payloadDigest = event.computePayloadDigest(),
            event = event
        )
        return captureSaleV2(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = currencyMinorUnits,
            intakeRequest = intakeReq,
            rawCanonicalEventUtf8 = rawCanonicalEventUtf8
        )
    }

    override fun captureSaleV2(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        currencyMinorUnits: Int,
        intakeRequest: OfflineSaleIntakeRequestV2,
        rawCanonicalEventUtf8: ByteArray?
    ): CaptureResult {
        // Defensive copy at entry BEFORE validation (TOCTOU defensive copy)
        val clonedRaw = rawCanonicalEventUtf8?.clone()

        if (clonedRaw != null) {
            // Bound input 1 MiB BEFORE decode allocation
            require(clonedRaw.size <= ContractConstants.MAX_PAYLOAD_BYTES) {
                "rawCanonicalEventUtf8 size ${clonedRaw.size} exceeds maximum allowed 1 MiB (${ContractConstants.MAX_PAYLOAD_BYTES} bytes)"
            }
        }

        val event = intakeRequest.event

        // Guard 1: Scope parameters MUST match captured identity BEFORE disclosing existing row
        require(companyId == event.companyId) {
            "Explicit companyId ($companyId) does not match captured event companyId (${event.companyId})"
        }
        require(actorId == event.actorId) {
            "Explicit actorId ($actorId) does not match captured event actorId (${event.actorId})"
        }
        require(deviceId == event.deviceId && deviceId == intakeRequest.deviceId) {
            "Explicit deviceId ($deviceId) does not match captured event deviceId (${event.deviceId})"
        }

        // Intake request envelope field bindings match event
        require(intakeRequest.clientEventId == event.clientEventId) {
            "Intake envelope clientEventId (${intakeRequest.clientEventId}) does not match event clientEventId (${event.clientEventId})"
        }
        require(intakeRequest.capturedAt == event.capturedAt) {
            "Intake envelope capturedAt (${intakeRequest.capturedAt}) does not match event capturedAt (${event.capturedAt})"
        }
        val computedDigest = event.computePayloadDigest()
        require(intakeRequest.payloadDigest == computedDigest) {
            "Intake envelope payloadDigest (${intakeRequest.payloadDigest}) does not match event canonical digest ($computedDigest)"
        }

        // Guard 2: Mandatory qualified currencyMinorUnits in 0..6 (no fallback / guessing)
        require(currencyMinorUnits in 0..6) {
            "currencyMinorUnits must be between 0 and 6, got $currencyMinorUnits (no fallback or guessing permitted)"
        }

        // Guard 3: Validate money semantics and exact minor units before transaction
        val payment = event.payment
        val settledBd = PaymentDecimalConsistency.parseAndValidateMonetaryAmount(payment.amountSettled.toPlainString(), currencyMinorUnits)
            ?: throw IllegalArgumentException("payment.amountSettled (${payment.amountSettled.lexeme}) has invalid scale for currencyMinorUnits=$currencyMinorUnits")
        require(settledBd > BigDecimal.ZERO) {
            "payment.amountSettled must be strictly positive according to schema, got ${payment.amountSettled.lexeme}"
        }

        val tenderedBd = PaymentDecimalConsistency.parseAndValidateMonetaryAmount(payment.amountTendered.toPlainString(), currencyMinorUnits)
            ?: throw IllegalArgumentException("payment.amountTendered (${payment.amountTendered.lexeme}) has invalid scale for currencyMinorUnits=$currencyMinorUnits")

        val changeBd = PaymentDecimalConsistency.parseAndValidateMonetaryAmount(payment.changeReturned.toPlainString(), currencyMinorUnits)
            ?: throw IllegalArgumentException("payment.changeReturned (${payment.changeReturned.lexeme}) has invalid scale for currencyMinorUnits=$currencyMinorUnits")

        require(tenderedBd.compareTo(settledBd.add(changeBd)) == 0) {
            "Payment tender conservation violated: tendered ($tenderedBd) != settled ($settledBd) + change ($changeBd)"
        }

        var lineSum = BigDecimal.ZERO
        for (line in event.lines) {
            val lineTotalBd = PaymentDecimalConsistency.parseAndValidateMonetaryAmount(line.lineTotal.toPlainString(), currencyMinorUnits)
                ?: throw IllegalArgumentException("line.lineTotal (${line.lineTotal.lexeme}) has invalid scale for currencyMinorUnits=$currencyMinorUnits")
            require(lineTotalBd > BigDecimal.ZERO) {
                "lineTotal must be strictly positive according to schema, got ${line.lineTotal.lexeme}"
            }

            val taxAmountBd = PaymentDecimalConsistency.parseAndValidateMonetaryAmount(line.taxAmount.toPlainString(), currencyMinorUnits)
                ?: throw IllegalArgumentException("line.taxAmount (${line.taxAmount.lexeme}) has invalid scale for currencyMinorUnits=$currencyMinorUnits")
            require(taxAmountBd >= BigDecimal.ZERO) {
                "taxAmount must be non-negative, got ${line.taxAmount.lexeme}"
            }

            lineSum = lineSum.add(line.lineTotal.value)
        }

        require(lineSum.compareTo(settledBd) == 0) {
            "Sum of line totals ($lineSum) does not match payment settled ($settledBd)"
        }

        // Guard 4: Canonical UTF-8 bytes and digest verification
        val canonicalBytes = if (clonedRaw != null) {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val jsonString = try {
                decoder.decode(ByteBuffer.wrap(clonedRaw)).toString()
            } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("Malformed UTF-8 byte sequence in rawCanonicalEventUtf8 for event ${event.clientEventId}", e)
            }
            val parsedJson = StrictJsonParser.parse(jsonString).asObject()
            val parsedEvent = OfflineSaleEventV2.fromStrictJson(parsedJson)
            require(parsedEvent == event) {
                "Provided rawCanonicalEventUtf8 does not match OfflineSaleEventV2 (client_event_id: ${event.clientEventId})"
            }

            // Require byte-exact parity with canonical serializer bytes:
            // Fails closed on formatted whitespace, key ordering differences, or decimal lexeme alterations.
            val canonicalSerializerBytes = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)
            require(clonedRaw.contentEquals(canonicalSerializerBytes)) {
                "Provided rawCanonicalEventUtf8 is not canonical (byte exact parity required; formatted whitespace, key order differences, or decimal lexeme alterations are rejected) for event ${event.clientEventId}"
            }

            val rawDigest = PayloadDigest.fromBytes(clonedRaw)
            require(rawDigest == computedDigest) {
                "Canonical bytes digest ($rawDigest) does not match event digest ($computedDigest) for event ${event.clientEventId}"
            }
            require(rawDigest == intakeRequest.payloadDigest) {
                "Canonical bytes digest ($rawDigest) does not match intake request payload digest (${intakeRequest.payloadDigest})"
            }

            clonedRaw
        } else {
            // Typed model without supplied raw: serialize once for initial capture (expressly new capture, not normalizing existing bytes)
            val serialized = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)
            val byteDigest = PayloadDigest.fromBytes(serialized)
            require(computedDigest == byteDigest) {
                "Canonical bytes digest ($byteDigest) does not match event digest ($computedDigest) for event ${event.clientEventId}"
            }
            require(computedDigest == intakeRequest.payloadDigest) {
                "Event digest ($computedDigest) does not match intake request payload digest (${intakeRequest.payloadDigest})"
            }
            serialized
        }

        // Guard 5: Execute atomic capture transaction sharing verified machinery
        return executeCaptureTransaction(
            companyIdStr = event.companyId.value,
            actorIdStr = event.actorId.value,
            deviceIdStr = event.deviceId.value,
            deviceLeaseIdStr = event.deviceLeaseId.value,
            clientEventIdStr = event.clientEventId.value,
            capturedAtStr = event.capturedAt,
            eventVersion = event.eventVersion,
            payloadDigestStr = computedDigest.value,
            canonicalBytes = canonicalBytes
        )
    }

    private fun executeCaptureTransaction(
        companyIdStr: String,
        actorIdStr: String,
        deviceIdStr: String,
        deviceLeaseIdStr: String,
        clientEventIdStr: String,
        capturedAtStr: String,
        eventVersion: Int,
        payloadDigestStr: String,
        canonicalBytes: ByteArray
    ): CaptureResult {
        val now = clock()
        return database.runInTransaction<CaptureResult> {
            val existingEvent = database.outboxDao().getEvent(companyIdStr, clientEventIdStr)
            if (existingEvent != null) {
                // Check scope isolation: actor and device MUST match!
                if (existingEvent.actorId != actorIdStr || existingEvent.deviceId != deviceIdStr) {
                    // Cross-scope capture collision!
                    // Must NOT mutate existing event or state.
                    // Must NOT return existing record to the caller (zero data leakage).
                    return@runInTransaction CaptureResult.ScopeRejected(
                        clientEventId = clientEventIdStr,
                        message = "Capture rejected: clientEventId already exists under another actor or device in company $companyIdStr"
                    )
                }

                val existingState = database.outboxDao().getDeliveryState(companyIdStr, clientEventIdStr)
                    ?: throw IllegalStateException("Missing delivery state for existing event: $clientEventIdStr")

                if (existingEvent.eventVersion == eventVersion && existingEvent.payloadDigest == payloadDigestStr) {
                    // Idempotent replay: return existing record unmodified
                    CaptureResult.Duplicate(OutboxRecord(existingEvent, existingState))
                } else {
                    // Altered payload / version conflict:
                    // Terminal immutability check: if existing record is CONFIRMED, it CANNOT be demoted to CONFLICT!
                    if (existingState.status == OutboxStatus.CONFIRMED.wireValue) {
                        CaptureResult.Conflict(
                            clientEventId = clientEventIdStr,
                            existingDigest = existingEvent.payloadDigest,
                            incomingDigest = payloadDigestStr,
                            existingRecord = OutboxRecord(existingEvent, existingState)
                        )
                    } else {
                        // Mark CONFLICT on delivery state with NO replacement of original event
                        database.outboxDao().markConflict(
                            companyId = companyIdStr,
                            clientEventId = clientEventIdStr,
                            reason = "Local conflict: incoming digest $payloadDigestStr != existing ${existingEvent.payloadDigest}",
                            nowMillis = now
                        )
                        val updatedState = database.outboxDao().getDeliveryState(companyIdStr, clientEventIdStr)!!
                        CaptureResult.Conflict(
                            clientEventId = clientEventIdStr,
                            existingDigest = existingEvent.payloadDigest,
                            incomingDigest = payloadDigestStr,
                            existingRecord = OutboxRecord(existingEvent, updatedState)
                        )
                    }
                }
            } else {
                // New event insertion.
                // Check if queue is locked due to auth denial:
                val isLocked = database.outboxDao().isScopeLocked(companyIdStr, actorIdStr, deviceIdStr)
                val initialStatus = if (isLocked) {
                    OutboxStatus.AUTH_BLOCKED.wireValue
                } else {
                    OutboxStatus.PENDING.wireValue
                }
                val initialError = if (isLocked) "Captured while queue locked due to auth denial" else null

                val eventEntity = OutboxEventEntity(
                    clientEventId = clientEventIdStr,
                    companyId = companyIdStr,
                    actorId = actorIdStr,
                    deviceId = deviceIdStr,
                    deviceLeaseId = deviceLeaseIdStr,
                    capturedAt = capturedAtStr,
                    payloadDigest = payloadDigestStr,
                    payloadBytes = canonicalBytes.clone(),
                    eventVersion = eventVersion
                )
                val stateEntity = OutboxDeliveryStateEntity(
                    clientEventId = clientEventIdStr,
                    companyId = companyIdStr,
                    actorId = actorIdStr,
                    deviceId = deviceIdStr,
                    status = initialStatus,
                    attemptCount = 0,
                    lastAttemptAt = null,
                    claimToken = null,
                    claimExpiresAt = null,
                    invoiceUlid = null,
                    paymentUlid = null,
                    serverUlid = null,
                    serverAcknowledgedAt = null,
                    errorMessage = initialError,
                    createdAt = now,
                    updatedAt = now
                )
                database.outboxDao().insertEvent(eventEntity)
                database.outboxDao().insertDeliveryState(stateEntity)
                CaptureResult.Created(OutboxRecord(eventEntity, stateEntity))
            }
        }
    }

    override fun claimNextBatch(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        batchSize: Int,
        leaseDurationMillis: Long
    ): ClaimedBatch {
        require(batchSize in 1..OutboxRepository.MAX_BATCH_SIZE) {
            "batchSize must be between 1 and ${OutboxRepository.MAX_BATCH_SIZE} (was $batchSize)"
        }
        require(leaseDurationMillis in 1_000L..OutboxRepository.MAX_LEASE_DURATION_MILLIS) {
            "leaseDurationMillis must be between 1,000 and ${OutboxRepository.MAX_LEASE_DURATION_MILLIS} ms (was $leaseDurationMillis)"
        }

        val now = clock()
        val claimExpiresAt = try {
            Math.addExact(now, leaseDurationMillis)
        } catch (e: ArithmeticException) {
            Long.MAX_VALUE
        }

        return database.runInTransaction<ClaimedBatch> {
            if (database.outboxDao().isScopeLocked(companyId.value, actorId.value, deviceId.value)) {
                throw QueueLockedException("Queue is locked due to authentication denial for company $companyId, actor $actorId")
            }

            val claimToken = UUID.randomUUID().toString()
            val candidateIds = database.outboxDao().findClaimCandidateIds(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                nowMillis = now,
                limit = batchSize
            )

            val claimedRecords = mutableListOf<OutboxRecord>()
            for (candidateId in candidateIds) {
                val updated = database.outboxDao().executeClaimCas(
                    clientEventId = candidateId,
                    companyId = companyId.value,
                    actorId = actorId.value,
                    deviceId = deviceId.value,
                    claimToken = claimToken,
                    claimExpiresAt = claimExpiresAt,
                    nowMillis = now
                )
                if (updated == 1) {
                    val record = database.outboxDao().getRecord(companyId.value, actorId.value, deviceId.value, candidateId)
                    if (record != null) {
                        claimedRecords.add(record)
                    }
                }
            }
            ClaimedBatch(claimToken = claimToken, records = claimedRecords)
        }
    }

    override fun recordTimeout(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        claimToken: String,
        errorMessage: String
    ): Boolean {
        require(claimToken.isNotBlank()) { "claimToken must not be blank" }
        val now = clock()
        return database.runInTransaction<Boolean> {
            val state = database.outboxDao().getDeliveryState(companyId.value, clientEventId.value)
                ?: return@runInTransaction false

            if (state.companyId != companyId.value || state.actorId != actorId.value || state.deviceId != deviceId.value) {
                return@runInTransaction false
            }
            if (state.status != OutboxStatus.IN_FLIGHT.wireValue || state.claimToken != claimToken) {
                // Terminal or state mismatch: cannot timeout a record that is already confirmed, expired, or claimed elsewhere
                return@runInTransaction false
            }

            val updated = database.outboxDao().recordTimeout(
                clientEventId = clientEventId.value,
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                claimToken = claimToken,
                errorMessage = errorMessage,
                nowMillis = now
            )
            updated == 1
        }
    }

    override fun confirmDelivery(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        invoiceUlid: ServerUlid,
        paymentUlid: ServerUlid,
        serverAcknowledgedAt: String,
        claimToken: String
    ): Boolean {
        require(claimToken.isNotBlank()) { "claimToken must not be blank for worker delivery confirmation" }
        require(ServerUlid.isValid(invoiceUlid.value)) { "Invalid invoice ULID: '${invoiceUlid.value}'" }
        require(ServerUlid.isValid(paymentUlid.value)) { "Invalid payment ULID: '${paymentUlid.value}'" }

        val now = clock()

        return database.runInTransaction<Boolean> {
            val event = database.outboxDao().getEvent(companyId.value, clientEventId.value)
                ?.takeIf { it.actorId == actorId.value && it.deviceId == deviceId.value }
                ?: throw InvalidAcknowledgementException("No event found for requested scope")

            if (event.companyId != companyId.value) {
                throw InvalidAcknowledgementException(
                    "Company ID mismatch: ack has ${companyId.value}, event has ${event.companyId}"
                )
            }
            if (event.payloadDigest != payloadDigest.value) {
                throw InvalidAcknowledgementException(
                    "Payload digest mismatch: ack has ${payloadDigest.value}, event has ${event.payloadDigest}"
                )
            }

            val state = database.outboxDao().getDeliveryState(companyId.value, clientEventId.value)
                ?: throw InvalidAcknowledgementException("No delivery state found for clientEventId: $clientEventId")

            if (state.companyId != companyId.value || state.actorId != actorId.value || state.deviceId != deviceId.value) {
                throw InvalidAcknowledgementException("Scope mismatch on delivery confirmation")
            }

            // Terminal check: CONFLICT
            if (state.status == OutboxStatus.CONFLICT.wireValue) {
                throw InvalidAcknowledgementException("Cannot confirm an event in CONFLICT state")
            }

            // Terminal check: CONFIRMED
            if (state.status == OutboxStatus.CONFIRMED.wireValue) {
                val currentInvoice = state.invoiceUlid
                val currentPayment = state.paymentUlid
                val matches = currentInvoice == invoiceUlid.value &&
                        currentPayment == paymentUlid.value
                if (matches) {
                    // Exact mapping replay: return true without mutating existing record
                    return@runInTransaction true
                } else {
                    // Conflicting mapping rejected; preserve original
                    throw InvalidAcknowledgementException(
                        "Conflicting server mapping for already-confirmed record (existing invoice: $currentInvoice, payment: $currentPayment; incoming invoice: ${invoiceUlid.value}, payment: ${paymentUlid.value}); original preserved"
                    )
                }
            }

            // Status checks: cannot confirm from PENDING or AUTH_BLOCKED
            if (state.status == OutboxStatus.PENDING.wireValue) {
                throw InvalidAcknowledgementException("Cannot confirm an event in PENDING state; record must be claimed first")
            }
            if (state.status == OutboxStatus.AUTH_BLOCKED.wireValue) {
                throw InvalidAcknowledgementException("Cannot confirm an event in AUTH_BLOCKED state")
            }

            // Worker claim check: must be in_flight
            if (state.status != OutboxStatus.IN_FLIGHT.wireValue) {
                throw InvalidAcknowledgementException("Cannot confirm delivery: state is '${state.status}', expected 'in_flight'")
            }

            // Claim token check
            if (state.claimToken != claimToken) {
                throw InvalidAcknowledgementException("Claim token mismatch: active lease held by another worker")
            }

            // Lease expiry check
            if (state.claimExpiresAt != null && state.claimExpiresAt < now) {
                throw InvalidAcknowledgementException("Claim lease expired at ${state.claimExpiresAt}, current time is $now")
            }

            val updated = database.outboxDao().confirmDelivery(
                clientEventId = clientEventId.value,
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                claimToken = claimToken,
                invoiceUlid = invoiceUlid.value,
                paymentUlid = paymentUlid.value,
                serverUlid = invoiceUlid.value,
                serverAcknowledgedAt = serverAcknowledgedAt,
                nowMillis = now
            )
            updated == 1
        }
    }

    override fun reconcileDeliveryConfirmation(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        invoiceUlid: ServerUlid,
        paymentUlid: ServerUlid,
        serverAcknowledgedAt: String
    ): Boolean {
        require(ServerUlid.isValid(invoiceUlid.value)) { "Invalid invoice ULID: '${invoiceUlid.value}'" }
        require(ServerUlid.isValid(paymentUlid.value)) { "Invalid payment ULID: '${paymentUlid.value}'" }

        val now = clock()

        return database.runInTransaction<Boolean> {
            val event = database.outboxDao().getEvent(companyId.value, clientEventId.value)
                ?.takeIf { it.actorId == actorId.value && it.deviceId == deviceId.value }
                ?: throw InvalidAcknowledgementException("No event found for requested scope")

            if (event.companyId != companyId.value) {
                throw InvalidAcknowledgementException("Company ID mismatch on reconciliation")
            }
            if (event.payloadDigest != payloadDigest.value) {
                throw InvalidAcknowledgementException("Payload digest mismatch on reconciliation")
            }

            val state = database.outboxDao().getDeliveryState(companyId.value, clientEventId.value)
                ?: throw InvalidAcknowledgementException("No delivery state found for clientEventId: $clientEventId")

            if (state.companyId != companyId.value || state.actorId != actorId.value || state.deviceId != deviceId.value) {
                throw InvalidAcknowledgementException("Scope mismatch on reconciliation")
            }

            if (state.status == OutboxStatus.CONFLICT.wireValue) {
                throw InvalidAcknowledgementException("Cannot reconcile an event in CONFLICT state")
            }

            if (state.status == OutboxStatus.CONFIRMED.wireValue) {
                val currentInvoice = state.invoiceUlid
                val currentPayment = state.paymentUlid
                val matches = currentInvoice == invoiceUlid.value &&
                        currentPayment == paymentUlid.value
                if (matches) {
                    return@runInTransaction true
                } else {
                    throw InvalidAcknowledgementException(
                        "Conflicting server mapping on reconciliation for already-confirmed record; original preserved"
                    )
                }
            }

            if (state.status !in listOf(OutboxStatus.IN_FLIGHT.wireValue, OutboxStatus.UNKNOWN_ACK.wireValue)) {
                throw InvalidAcknowledgementException("Cannot reconcile confirmation for record in state '${state.status}'; must be in_flight or unknown_ack")
            }

            val updated = database.outboxDao().reconcileConfirmation(
                clientEventId = clientEventId.value,
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                invoiceUlid = invoiceUlid.value,
                paymentUlid = paymentUlid.value,
                serverUlid = invoiceUlid.value,
                serverAcknowledgedAt = serverAcknowledgedAt,
                nowMillis = now
            )
            updated == 1
        }
    }

    override fun lockQueueOnAuthDenied(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        reason: String
    ): Int {
        val now = clock()
        return database.runInTransaction<Int> {
            val lockEntity = OutboxScopeLockEntity(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                lockedAt = now,
                reason = reason
            )
            database.outboxDao().insertScopeLock(lockEntity)
            database.outboxDao().lockQueueOnAuthDenied(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                reason = reason,
                nowMillis = now
            )
        }
    }

    override fun unlockQueueAfterReauth(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Int {
        val now = clock()
        return database.runInTransaction<Int> {
            database.outboxDao().deleteScopeLock(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value
            )
            database.outboxDao().unlockQueueAfterReauth(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                nowMillis = now
            )
        }
    }

    override fun isQueueLocked(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Boolean {
        return database.outboxDao().isScopeLocked(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value
        )
    }

    override fun getRecord(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid
    ): OutboxRecord? {
        return database.outboxDao().getRecord(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            clientEventId = clientEventId.value
        )
    }

    override fun getRecordsByScope(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): List<OutboxRecord> {
        return database.outboxDao().getRecordsByScope(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value
        )
    }

    override fun getRecordsByStatus(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        status: OutboxStatus
    ): List<OutboxRecord> {
        return database.outboxDao().getRecordsByScopeAndStatus(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            status = status.wireValue
        )
    }
}
