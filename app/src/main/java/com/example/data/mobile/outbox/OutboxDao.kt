package com.example.data.mobile.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertEvent(event: OutboxEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDeliveryState(state: OutboxDeliveryStateEntity)

    @Update
    fun updateDeliveryState(state: OutboxDeliveryStateEntity): Int

    @Query("SELECT * FROM outbox_events WHERE client_event_id = :clientEventId")
    fun getEvent(clientEventId: String): OutboxEventEntity?

    @Query("SELECT * FROM outbox_events WHERE company_id = :companyId AND client_event_id = :clientEventId")
    fun getEventByCompany(companyId: String, clientEventId: String): OutboxEventEntity?

    @Query("SELECT * FROM outbox_delivery_states WHERE client_event_id = :clientEventId")
    fun getDeliveryState(clientEventId: String): OutboxDeliveryStateEntity?

    @Transaction
    @Query("SELECT * FROM outbox_events WHERE client_event_id = :clientEventId")
    fun getRecord(clientEventId: String): OutboxRecord?

    @Transaction
    @Query("""
        SELECT * FROM outbox_events 
        WHERE company_id = :companyId 
          AND actor_id = :actorId 
          AND device_id = :deviceId
        ORDER BY captured_at ASC
    """)
    fun getRecordsByScope(
        companyId: String,
        actorId: String,
        deviceId: String
    ): List<OutboxRecord>

    @Transaction
    @Query("""
        SELECT e.* FROM outbox_events e
        INNER JOIN outbox_delivery_states s ON e.client_event_id = s.client_event_id
        WHERE s.company_id = :companyId 
          AND s.actor_id = :actorId 
          AND s.device_id = :deviceId
          AND s.status = :status
        ORDER BY s.created_at ASC
    """)
    fun getRecordsByScopeAndStatus(
        companyId: String,
        actorId: String,
        deviceId: String,
        status: String
    ): List<OutboxRecord>

    /**
     * Finds candidate event IDs eligible for claim CAS:
     * - status is 'pending'
     * - status is 'in_flight' with expired lease (claim_expires_at < :nowMillis)
     * - status is 'unknown_ack' with expired or null lease (claim_expires_at IS NULL OR claim_expires_at < :nowMillis)
     */
    @Query("""
        SELECT client_event_id FROM outbox_delivery_states
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND (
            status = 'pending'
            OR (status IN ('in_flight', 'unknown_ack') AND (claim_expires_at IS NULL OR claim_expires_at < :nowMillis))
          )
        ORDER BY created_at ASC
        LIMIT :limit
    """)
    fun findClaimCandidateIds(
        companyId: String,
        actorId: String,
        deviceId: String,
        nowMillis: Long,
        limit: Int
    ): List<String>

    /**
     * Atomic Compare-And-Set (CAS) claim update.
     * Guarantees only one worker can transition an eligible record to 'in_flight'.
     */
    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'in_flight',
            claim_token = :claimToken,
            claim_expires_at = :claimExpiresAt,
            attempt_count = attempt_count + 1,
            last_attempt_at = :nowMillis,
            updated_at = :nowMillis
        WHERE client_event_id = :clientEventId
          AND company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND (
            status = 'pending'
            OR (status IN ('in_flight', 'unknown_ack') AND (claim_expires_at IS NULL OR claim_expires_at < :nowMillis))
          )
    """)
    fun executeClaimCas(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        claimExpiresAt: Long,
        nowMillis: Long
    ): Int

    /**
     * Transitions an in-flight claimed record to unknown_ack upon network timeout.
     * Prevents false confirmation while recording timeout error.
     */
    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'unknown_ack',
            claim_token = NULL,
            claim_expires_at = NULL,
            error_message = :errorMessage,
            updated_at = :nowMillis
        WHERE client_event_id = :clientEventId
          AND company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND claim_token = :claimToken
    """)
    fun recordTimeout(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        errorMessage: String,
        nowMillis: Long
    ): Int

    /**
     * Marks an existing delivery state as CONFLICT upon detecting an altered payload digest.
     * Never touches the original immutable outbox_events record.
     */
    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'conflict',
            error_message = :reason,
            claim_token = NULL,
            claim_expires_at = NULL,
            updated_at = :nowMillis
        WHERE client_event_id = :clientEventId
    """)
    fun markConflict(
        clientEventId: String,
        reason: String,
        nowMillis: Long
    ): Int

    /**
     * Confirms delivery upon verified server acknowledgement.
     * Sets server ULID and verified server timestamp.
     */
    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'confirmed',
            server_ulid = :serverUlid,
            server_acknowledged_at = :serverAcknowledgedAt,
            claim_token = NULL,
            claim_expires_at = NULL,
            updated_at = :nowMillis
        WHERE client_event_id = :clientEventId
          AND company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
    """)
    fun confirmDelivery(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        serverUlid: String,
        serverAcknowledgedAt: String,
        nowMillis: Long
    ): Int

    /**
     * Locks queue upon authentication denial (HTTP 401).
     * Transitions all active delivery states (pending, in_flight, unknown_ack)
     * for the given company, actor, and device to 'auth_blocked'.
     * Preserves 'conflict' and 'confirmed' records untouched.
     */
    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'auth_blocked',
            claim_token = NULL,
            claim_expires_at = NULL,
            error_message = :reason,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND status IN ('pending', 'in_flight', 'unknown_ack')
    """)
    fun lockQueueOnAuthDenied(
        companyId: String,
        actorId: String,
        deviceId: String,
        reason: String,
        nowMillis: Long
    ): Int

    /**
     * Unlocks queue after authenticated manager re-establishes valid credentials.
     * Restores 'auth_blocked' records to 'pending'.
     * Leaves 'conflict' and 'confirmed' records untouched.
     */
    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'pending',
            error_message = NULL,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND status = 'auth_blocked'
    """)
    fun unlockQueueAfterReauth(
        companyId: String,
        actorId: String,
        deviceId: String,
        nowMillis: Long
    ): Int

    @Query("""
        SELECT COUNT(*) FROM outbox_delivery_states
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND status = 'auth_blocked'
    """)
    fun countAuthBlocked(
        companyId: String,
        actorId: String,
        deviceId: String
    ): Int
}
