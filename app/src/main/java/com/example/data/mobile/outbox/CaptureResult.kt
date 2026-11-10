package com.example.data.mobile.outbox

/**
 * Result of an atomic sale capture operation.
 */
sealed class CaptureResult {
    /**
     * Newly created event and initial pending delivery state.
     */
    data class Created(val record: OutboxRecord) : CaptureResult()

    /**
     * Idempotent replay: an event with the exact same client_event_id and matching
     * payload digest was already captured. Existing record returned unmodified.
     */
    data class Duplicate(val record: OutboxRecord) : CaptureResult()

    /**
     * Local conflict: an event with the same client_event_id was presented with an
     * altered payload digest. The original event is NEVER overwritten; the delivery
     * state is marked as CONFLICT.
     */
    data class Conflict(
        val clientEventId: String,
        val existingDigest: String,
        val incomingDigest: String,
        val existingRecord: OutboxRecord
    ) : CaptureResult()
}

/**
 * Batch of claimed records bounded by an active lease token.
 */
data class ClaimedBatch(
    val claimToken: String,
    val records: List<OutboxRecord>
)

class OutboxConflictException(message: String) : IllegalStateException(message)
class InvalidAcknowledgementException(message: String) : IllegalArgumentException(message)
class QueueLockedException(message: String) : IllegalStateException(message)
