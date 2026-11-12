package com.example.data.mobile.outbox

/**
 * Lifecycle delivery states for bounded offline sales outbox.
 *
 * State Machine:
 * - PENDING: Newly captured offline sale event awaiting transmission.
 * - IN_FLIGHT: Claimed by a worker with an active lease for transmission.
 * - UNKNOWN_ACK: Delivery attempted but network timed out or response was unparseable.
 *                Awaiting recovery/re-query without premature confirmation.
 * - CONFIRMED: Authoritatively confirmed by server response containing matching
 *              original event ID, payload digest, company, and valid server ULID.
 * - CONFLICT: Payload digest mismatch or client collision. Never overwritten or deleted.
 * - AUTH_BLOCKED: Authentication denied (HTTP 401). Queue is locked to prevent token
 *                 drain until an authenticated manager re-authenticates the device.
 */
enum class OutboxStatus(val wireValue: String) {
    PENDING("pending"),
    IN_FLIGHT("in_flight"),
    UNKNOWN_ACK("unknown_ack"),
    CONFIRMED("confirmed"),
    CONFLICT("conflict"),
    AUTH_BLOCKED("auth_blocked");

    companion object {
        fun fromWireValue(value: String): OutboxStatus =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown OutboxStatus wire value: '$value'")
    }
}
