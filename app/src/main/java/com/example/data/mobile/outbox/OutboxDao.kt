package com.example.data.mobile.outbox

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertEvent(event: OutboxEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertDeliveryState(state: OutboxDeliveryStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertScopeLock(lock: OutboxScopeLockEntity)

    @Query("DELETE FROM outbox_scope_locks WHERE company_id = :companyId AND actor_id = :actorId AND device_id = :deviceId")
    abstract fun deleteScopeLock(companyId: String, actorId: String, deviceId: String): Int

    @Query("SELECT COUNT(*) > 0 FROM outbox_scope_locks WHERE company_id = :companyId AND actor_id = :actorId AND device_id = :deviceId")
    abstract fun isScopeLocked(companyId: String, actorId: String, deviceId: String): Boolean

    @Query("SELECT * FROM outbox_events WHERE company_id = :companyId AND client_event_id = :clientEventId")
    abstract fun getEvent(companyId: String, clientEventId: String): OutboxEventEntity?

    @Query("SELECT * FROM outbox_delivery_states WHERE company_id = :companyId AND client_event_id = :clientEventId")
    abstract fun getDeliveryState(companyId: String, clientEventId: String): OutboxDeliveryStateEntity?

    @Query("""
        SELECT * FROM outbox_events
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
        ORDER BY captured_at ASC
    """)
    abstract fun getEventsByScope(
        companyId: String,
        actorId: String,
        deviceId: String
    ): List<OutboxEventEntity>

    @Query("""
        SELECT * FROM outbox_delivery_states
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
    """)
    abstract fun getDeliveryStatesByScope(
        companyId: String,
        actorId: String,
        deviceId: String
    ): List<OutboxDeliveryStateEntity>

    @Query("""
        SELECT e.* FROM outbox_events e
        INNER JOIN outbox_delivery_states s 
          ON e.company_id = s.company_id AND e.client_event_id = s.client_event_id
        WHERE s.company_id = :companyId 
          AND s.actor_id = :actorId 
          AND s.device_id = :deviceId
          AND s.status = :status
        ORDER BY s.created_at ASC
    """)
    abstract fun getEventsByScopeAndStatus(
        companyId: String,
        actorId: String,
        deviceId: String,
        status: String
    ): List<OutboxEventEntity>

    @Transaction
    open fun getRecord(
        companyId: String,
        actorId: String,
        deviceId: String,
        clientEventId: String
    ): OutboxRecord? {
        val event = getEvent(companyId, clientEventId) ?: return null
        if (event.actorId != actorId || event.deviceId != deviceId) return null
        val state = getDeliveryState(companyId, clientEventId) ?: return null
        return OutboxRecord(event, state)
    }

    @Transaction
    open fun getRecordsByScope(
        companyId: String,
        actorId: String,
        deviceId: String
    ): List<OutboxRecord> {
        val events = getEventsByScope(companyId, actorId, deviceId)
        val states = getDeliveryStatesByScope(companyId, actorId, deviceId).associateBy { it.clientEventId }
        return events.mapNotNull { event ->
            states[event.clientEventId]?.let { state -> OutboxRecord(event, state) }
        }
    }

    @Transaction
    open fun getRecordsByScopeAndStatus(
        companyId: String,
        actorId: String,
        deviceId: String,
        status: String
    ): List<OutboxRecord> {
        val events = getEventsByScopeAndStatus(companyId, actorId, deviceId, status)
        val states = getDeliveryStatesByScope(companyId, actorId, deviceId).associateBy { it.clientEventId }
        return events.mapNotNull { event ->
            states[event.clientEventId]?.let { state -> OutboxRecord(event, state) }
        }
    }

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
    abstract fun findClaimCandidateIds(
        companyId: String,
        actorId: String,
        deviceId: String,
        nowMillis: Long,
        limit: Int
    ): List<String>

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'in_flight',
            claim_token = :claimToken,
            claim_expires_at = :claimExpiresAt,
            attempt_count = attempt_count + 1,
            last_attempt_at = :nowMillis,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND client_event_id = :clientEventId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND (
            status = 'pending'
            OR (status IN ('in_flight', 'unknown_ack') AND (claim_expires_at IS NULL OR claim_expires_at < :nowMillis))
          )
    """)
    abstract fun executeClaimCas(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        claimExpiresAt: Long,
        nowMillis: Long
    ): Int

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'unknown_ack',
            claim_token = NULL,
            claim_expires_at = NULL,
            error_message = :errorMessage,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND client_event_id = :clientEventId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND claim_token = :claimToken
          AND status = 'in_flight'
    """)
    abstract fun recordTimeout(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        errorMessage: String,
        nowMillis: Long
    ): Int

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'conflict',
            error_message = :reason,
            claim_token = NULL,
            claim_expires_at = NULL,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND client_event_id = :clientEventId
          AND status != 'confirmed'
    """)
    abstract fun markConflict(
        companyId: String,
        clientEventId: String,
        reason: String,
        nowMillis: Long
    ): Int

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'conflict',
            error_message = :reason,
            claim_token = NULL,
            claim_expires_at = NULL,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND client_event_id = :clientEventId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND claim_token = :claimToken
          AND status = 'in_flight'
    """)
    abstract fun markClaimConflict(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        reason: String,
        nowMillis: Long
    ): Int

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'pending',
            claim_token = NULL,
            claim_expires_at = NULL,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND claim_token = :claimToken
          AND status = 'in_flight'
    """)
    abstract fun releaseClaimedBatch(
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        nowMillis: Long
    ): Int

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'confirmed',
            invoice_ulid = :invoiceUlid,
            payment_ulid = :paymentUlid,
            server_ulid = :serverUlid,
            server_acknowledged_at = :serverAcknowledgedAt,
            claim_token = NULL,
            claim_expires_at = NULL,
            error_message = NULL,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND client_event_id = :clientEventId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND claim_token = :claimToken
          AND status = 'in_flight'
    """)
    abstract fun confirmDelivery(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        claimToken: String,
        invoiceUlid: String,
        paymentUlid: String,
        serverUlid: String,
        serverAcknowledgedAt: String,
        nowMillis: Long
    ): Int

    @Query("""
        UPDATE outbox_delivery_states
        SET status = 'confirmed',
            invoice_ulid = :invoiceUlid,
            payment_ulid = :paymentUlid,
            server_ulid = :serverUlid,
            server_acknowledged_at = :serverAcknowledgedAt,
            claim_token = NULL,
            claim_expires_at = NULL,
            error_message = NULL,
            updated_at = :nowMillis
        WHERE company_id = :companyId
          AND client_event_id = :clientEventId
          AND actor_id = :actorId
          AND device_id = :deviceId
          AND status IN ('in_flight', 'unknown_ack')
    """)
    abstract fun reconcileConfirmation(
        clientEventId: String,
        companyId: String,
        actorId: String,
        deviceId: String,
        invoiceUlid: String,
        paymentUlid: String,
        serverUlid: String,
        serverAcknowledgedAt: String,
        nowMillis: Long
    ): Int

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
    abstract fun lockQueueOnAuthDenied(
        companyId: String,
        actorId: String,
        deviceId: String,
        reason: String,
        nowMillis: Long
    ): Int

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
    abstract fun unlockQueueAfterReauth(
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
    abstract fun countAuthBlocked(
        companyId: String,
        actorId: String,
        deviceId: String
    ): Int
}
