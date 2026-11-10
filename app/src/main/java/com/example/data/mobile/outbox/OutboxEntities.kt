package com.example.data.mobile.outbox

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Immutable economic event bytes and identity.
 *
 * CRITICAL INVARIANT:
 * Once inserted, this record must NEVER be updated, repriced, rekeyed, or deleted.
 * Stored payload_bytes is the exact captured UTF-8 JSON bytes to guarantee cryptographic
 * digest integrity.
 *
 * Composite Primary Key: (company_id, client_event_id).
 * Per contract, clientEventId is unique within a company scope.
 * Different companies with identical clientEventIds remain strictly isolated.
 */
@Entity(
    tableName = "outbox_events",
    primaryKeys = ["company_id", "client_event_id"],
    indices = [
        Index(
            value = ["company_id", "actor_id", "device_id"],
            name = "index_outbox_events_scope"
        )
    ]
)
data class OutboxEventEntity(
    @ColumnInfo(name = "client_event_id")
    val clientEventId: String,

    @ColumnInfo(name = "company_id")
    val companyId: String,

    @ColumnInfo(name = "actor_id")
    val actorId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "device_lease_id")
    val deviceLeaseId: String,

    @ColumnInfo(name = "captured_at")
    val capturedAt: String,

    @ColumnInfo(name = "payload_digest")
    val payloadDigest: String,

    @ColumnInfo(name = "payload_bytes", typeAffinity = ColumnInfo.BLOB)
    val payloadBytes: ByteArray,

    @ColumnInfo(name = "event_version")
    val eventVersion: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEventEntity) return false
        return clientEventId == other.clientEventId &&
                companyId == other.companyId &&
                actorId == other.actorId &&
                deviceId == other.deviceId &&
                deviceLeaseId == other.deviceLeaseId &&
                capturedAt == other.capturedAt &&
                payloadDigest == other.payloadDigest &&
                payloadBytes.contentEquals(other.payloadBytes) &&
                eventVersion == other.eventVersion
    }

    override fun hashCode(): Int {
        var result = clientEventId.hashCode()
        result = 31 * result + companyId.hashCode()
        result = 31 * result + actorId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + deviceLeaseId.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + payloadDigest.hashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        result = 31 * result + eventVersion
        return result
    }
}

/**
 * Mutable delivery state projection.
 *
 * Separated from the immutable event payload to allow delivery attempts, leases,
 * server acknowledgements, and error statuses to mutate without risking corruption
 * of the immutable event bytes or digest.
 *
 * Composite Primary Key: (company_id, client_event_id).
 * Foreign key references outbox_events(company_id, client_event_id) with RESTRICT.
 */
@Entity(
    tableName = "outbox_delivery_states",
    primaryKeys = ["company_id", "client_event_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutboxEventEntity::class,
            parentColumns = ["company_id", "client_event_id"],
            childColumns = ["company_id", "client_event_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(
            value = ["company_id", "actor_id", "device_id", "status"],
            name = "index_outbox_states_scope_status"
        ),
        Index(
            value = ["status", "claim_expires_at"],
            name = "index_outbox_states_status_claim"
        )
    ]
)
data class OutboxDeliveryStateEntity(
    @ColumnInfo(name = "client_event_id")
    val clientEventId: String,

    @ColumnInfo(name = "company_id")
    val companyId: String,

    @ColumnInfo(name = "actor_id")
    val actorId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "claim_token")
    val claimToken: String? = null,

    @ColumnInfo(name = "claim_expires_at")
    val claimExpiresAt: Long? = null,

    @ColumnInfo(name = "invoice_ulid")
    val invoiceUlid: String? = null,

    @ColumnInfo(name = "payment_ulid")
    val paymentUlid: String? = null,

    @ColumnInfo(name = "server_ulid")
    val serverUlid: String? = null,

    @ColumnInfo(name = "server_acknowledged_at")
    val serverAcknowledgedAt: String? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

/**
 * Persisted scope lock for authentication denial (HTTP 401).
 *
 * Scoped by (company_id, actor_id, device_id).
 * Guarantees that even if the queue has zero events at the time of auth denial,
 * the lock persists across process restarts, disk reopens, and subsequent captures.
 */
@Entity(
    tableName = "outbox_scope_locks",
    primaryKeys = ["company_id", "actor_id", "device_id"]
)
data class OutboxScopeLockEntity(
    @ColumnInfo(name = "company_id")
    val companyId: String,

    @ColumnInfo(name = "actor_id")
    val actorId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "locked_at")
    val lockedAt: Long,

    @ColumnInfo(name = "reason")
    val reason: String
)

/**
 * Composite model binding immutable event payload and mutable delivery state.
 */
data class OutboxRecord(
    val event: OutboxEventEntity,
    val deliveryState: OutboxDeliveryStateEntity
) {
    val clientEventId: String get() = event.clientEventId
    val companyId: String get() = event.companyId
    val actorId: String get() = event.actorId
    val deviceId: String get() = event.deviceId
    val status: OutboxStatus get() = OutboxStatus.fromWireValue(deliveryState.status)
    val payloadDigest: String get() = event.payloadDigest
    val payloadBytes: ByteArray get() = event.payloadBytes.clone()
}
