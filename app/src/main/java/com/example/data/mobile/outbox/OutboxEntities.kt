package com.example.data.mobile.outbox

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Immutable economic event bytes and identity.
 *
 * CRITICAL INVARIANT:
 * Once inserted, this record must NEVER be updated, repriced, rekeyed, or deleted.
 * Stored payload_bytes is the exact captured UTF-8 JSON bytes to guarantee cryptographic
 * digest integrity.
 */
@Entity(
    tableName = "outbox_events",
    indices = [
        Index(
            value = ["company_id", "client_event_id"],
            unique = true,
            name = "index_outbox_events_company_event"
        ),
        Index(
            value = ["company_id", "actor_id", "device_id"],
            name = "index_outbox_events_scope"
        )
    ]
)
data class OutboxEventEntity(
    @PrimaryKey
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
 */
@Entity(
    tableName = "outbox_delivery_states",
    foreignKeys = [
        ForeignKey(
            entity = OutboxEventEntity::class,
            parentColumns = ["client_event_id"],
            childColumns = ["client_event_id"],
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
    @PrimaryKey
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
 * Composite model binding immutable event payload and mutable delivery state.
 */
data class OutboxRecord(
    @Embedded
    val event: OutboxEventEntity,

    @Relation(
        parentColumn = "client_event_id",
        entityColumn = "client_event_id"
    )
    val deliveryState: OutboxDeliveryStateEntity
) {
    val clientEventId: String get() = event.clientEventId
    val companyId: String get() = event.companyId
    val actorId: String get() = event.actorId
    val deviceId: String get() = event.deviceId
    val status: OutboxStatus get() = OutboxStatus.fromWireValue(deliveryState.status)
    val payloadDigest: String get() = event.payloadDigest
    val payloadBytes: ByteArray get() = event.payloadBytes
}
