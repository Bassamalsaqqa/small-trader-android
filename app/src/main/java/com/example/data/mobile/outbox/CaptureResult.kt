package com.example.data.mobile.outbox

/**
 * Result of an atomic sale capture operation.
 */
sealed class CaptureResult {
    /**
     * Newly created event and initial delivery state (PENDING or AUTH_BLOCKED if queue is locked).
     */
    data class Created(val record: OutboxRecord) : CaptureResult()

    /**
     * Idempotent replay: an event with the exact same company_id, client_event_id,
     * actor_id, device_id, and matching payload digest was already captured.
     * Existing record returned unmodified.
     */
    data class Duplicate(val record: OutboxRecord) : CaptureResult()

    /**
     * Local conflict: an event with the same client_event_id in this scope was presented
     * with an altered payload digest. The original event is NEVER overwritten; the delivery
     * state is marked as CONFLICT (unless already CONFIRMED).
     */
    data class Conflict(
        val clientEventId: String,
        val existingDigest: String,
        val incomingDigest: String,
        val existingRecord: OutboxRecord? = null
    ) : CaptureResult()

    /**
     * Scope rejection: an event with the same client_event_id already exists in this company
     * under a different actor or device. The capture is rejected without mutating state
     * and without leaking the foreign scope record.
     */
    data class ScopeRejected(
        val clientEventId: String,
        val message: String
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
class OutboxScopeCollisionException(message: String) : IllegalStateException(message)
