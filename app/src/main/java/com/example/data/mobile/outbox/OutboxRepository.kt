package com.example.data.mobile.outbox

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.OfflineSaleEvent
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import java.util.UUID

interface OutboxRepository {

    /**
     * Atomically captures a validated offline sale event.
     *
     * Invariants:
     * - Writes validated byte-preserved OfflineSaleEvent plus pending projection before returning success.
     * - Same clientEventId with matching payload digest returns existing record (idempotent replay).
     * - Same clientEventId with differing payload digest marks local CONFLICT with NO replacement.
     * - Disk write failure propagates exception and returns no success.
     */
    fun captureSale(
        event: OfflineSaleEvent,
        rawUtf8Json: ByteArray? = null
    ): CaptureResult

    /**
     * Claims the next batch of eligible records using lease-bounded Compare-And-Set (CAS).
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
     * NEVER confirms on timeout.
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
     * Confirms delivery strictly upon verified server acknowledgement.
     *
     * Invariants:
     * - Verified server acknowledgement must match original clientEventId,
     *   companyId, and payloadDigest.
     * - Server entity mapping must be a valid 26-char Crockford ULID.
     * - Throws InvalidAcknowledgementException if any verification check fails.
     */
    fun confirmDelivery(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        serverUlid: ServerUlid,
        serverAcknowledgedAt: String,
        claimToken: String? = null
    ): Boolean

    /**
     * Locks queue upon HTTP 401 authentication denial.
     * Transitions pending, in_flight, and unknown_ack to auth_blocked.
     * Preserves pending and conflict records without deletion.
     */
    fun lockQueueOnAuthDenied(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        reason: String
    ): Int

    /**
     * Restores queue processing after manager re-authentication.
     */
    fun unlockQueueAfterReauth(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Int

    fun isQueueLocked(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Boolean

    fun getRecord(clientEventId: ClientUuid): OutboxRecord?

    fun getRecordsByScope(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): List<OutboxRecord>

    fun getRecordsByStatus(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        status: OutboxStatus
    ): List<OutboxRecord>
}

class OutboxRepositoryImpl(
    private val database: OutboxDatabase
) : OutboxRepository {

    override fun captureSale(
        event: OfflineSaleEvent,
        rawUtf8Json: ByteArray?
    ): CaptureResult {
        // 1. Determine exact byte payload
        val canonicalBytes = if (rawUtf8Json != null) {
            val jsonString = String(rawUtf8Json, Charsets.UTF_8)
            val parsedJson = StrictJsonParser.parse(jsonString).asObject()
            val parsedEvent = OfflineSaleEvent.fromStrictJson(parsedJson)
            require(parsedEvent == event) {
                "Provided rawUtf8Json does not match OfflineSaleEvent"
            }
            rawUtf8Json
        } else {
            CanonicalJsonSerializer.serialize(event).toByteArray(Charsets.UTF_8)
        }

        val computedDigest = event.computePayloadDigest()
        val byteDigest = PayloadDigest.fromBytes(canonicalBytes)
        require(computedDigest == byteDigest) {
            "Canonical bytes digest ($byteDigest) does not match event digest ($computedDigest)"
        }

        val now = System.currentTimeMillis()

        // 2. Perform atomic transaction
        return database.runInTransaction<CaptureResult> {
            val existingEvent = database.outboxDao().getEvent(event.clientEventId.value)
            if (existingEvent != null) {
                val existingState = database.outboxDao().getDeliveryState(event.clientEventId.value)
                    ?: throw IllegalStateException("Missing delivery state for existing event: ${event.clientEventId}")

                if (existingEvent.payloadDigest == computedDigest.value) {
                    // Idempotent replay: return existing record unmodified
                    CaptureResult.Duplicate(OutboxRecord(existingEvent, existingState))
                } else {
                    // Altered payload conflict: mark CONFLICT with NO replacement of original event
                    database.outboxDao().markConflict(
                        clientEventId = existingEvent.clientEventId,
                        reason = "Local conflict: incoming digest ${computedDigest.value} != existing ${existingEvent.payloadDigest}",
                        nowMillis = now
                    )
                    val updatedState = database.outboxDao().getDeliveryState(existingEvent.clientEventId)!!
                    CaptureResult.Conflict(
                        clientEventId = existingEvent.clientEventId,
                        existingDigest = existingEvent.payloadDigest,
                        incomingDigest = computedDigest.value,
                        existingRecord = OutboxRecord(existingEvent, updatedState)
                    )
                }
            } else {
                val eventEntity = OutboxEventEntity(
                    clientEventId = event.clientEventId.value,
                    companyId = event.companyId.value,
                    actorId = event.actorId.value,
                    deviceId = event.deviceId.value,
                    deviceLeaseId = event.deviceLeaseId.value,
                    capturedAt = event.capturedAt,
                    payloadDigest = computedDigest.value,
                    payloadBytes = canonicalBytes,
                    eventVersion = event.eventVersion
                )
                val stateEntity = OutboxDeliveryStateEntity(
                    clientEventId = event.clientEventId.value,
                    companyId = event.companyId.value,
                    actorId = event.actorId.value,
                    deviceId = event.deviceId.value,
                    status = OutboxStatus.PENDING.wireValue,
                    attemptCount = 0,
                    lastAttemptAt = null,
                    claimToken = null,
                    claimExpiresAt = null,
                    serverUlid = null,
                    serverAcknowledgedAt = null,
                    errorMessage = null,
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
        require(batchSize > 0) { "batchSize must be positive" }
        require(leaseDurationMillis > 0) { "leaseDurationMillis must be positive" }

        if (isQueueLocked(companyId, actorId, deviceId)) {
            throw QueueLockedException("Queue is locked due to authentication denial for company $companyId, actor $actorId")
        }

        val now = System.currentTimeMillis()
        val claimToken = UUID.randomUUID().toString()
        val claimExpiresAt = now + leaseDurationMillis

        val claimedRecords = mutableListOf<OutboxRecord>()

        database.runInTransaction {
            val candidateIds = database.outboxDao().findClaimCandidateIds(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                nowMillis = now,
                limit = batchSize
            )

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
                    val record = database.outboxDao().getRecord(candidateId)
                    if (record != null) {
                        claimedRecords.add(record)
                    }
                }
            }
        }

        return ClaimedBatch(claimToken = claimToken, records = claimedRecords)
    }

    override fun recordTimeout(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        claimToken: String,
        errorMessage: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val updated = database.outboxDao().recordTimeout(
            clientEventId = clientEventId.value,
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            claimToken = claimToken,
            errorMessage = errorMessage,
            nowMillis = now
        )
        return updated == 1
    }

    override fun confirmDelivery(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        serverUlid: ServerUlid,
        serverAcknowledgedAt: String,
        claimToken: String?
    ): Boolean {
        require(ServerUlid.isValid(serverUlid.value)) {
            "Invalid server ULID: '${serverUlid.value}'"
        }

        val now = System.currentTimeMillis()

        return database.runInTransaction<Boolean> {
            val event = database.outboxDao().getEvent(clientEventId.value)
                ?: throw InvalidAcknowledgementException("No event found for clientEventId: $clientEventId")

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

            val state = database.outboxDao().getDeliveryState(clientEventId.value)
                ?: throw InvalidAcknowledgementException("No delivery state found for clientEventId: $clientEventId")

            if (state.companyId != companyId.value || state.actorId != actorId.value || state.deviceId != deviceId.value) {
                throw InvalidAcknowledgementException("Scope mismatch on delivery confirmation")
            }

            if (state.status == OutboxStatus.CONFLICT.wireValue) {
                throw InvalidAcknowledgementException("Cannot confirm an event in CONFLICT state")
            }

            if (claimToken != null && state.claimToken != null && state.claimToken != claimToken) {
                throw InvalidAcknowledgementException("Claim token mismatch: active lease held by another worker")
            }

            val updated = database.outboxDao().confirmDelivery(
                clientEventId = clientEventId.value,
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = deviceId.value,
                serverUlid = serverUlid.value,
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
        val now = System.currentTimeMillis()
        return database.outboxDao().lockQueueOnAuthDenied(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            reason = reason,
            nowMillis = now
        )
    }

    override fun unlockQueueAfterReauth(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Int {
        val now = System.currentTimeMillis()
        return database.outboxDao().unlockQueueAfterReauth(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            nowMillis = now
        )
    }

    override fun isQueueLocked(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid
    ): Boolean {
        return database.outboxDao().countAuthBlocked(
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value
        ) > 0
    }

    override fun getRecord(clientEventId: ClientUuid): OutboxRecord? {
        return database.outboxDao().getRecord(clientEventId.value)
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
