package com.example.data.mobile.sync

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid

/**
 * Authoritative server receipt acknowledgement for a posted offline sale event.
 *
 * Invariant:
 * Contains the authoritative backend identifiers and canonical digest.
 * Exact match against the local event is strictly validated prior to confirmation.
 * Malformed or mismatched acks are NEVER confirmed and local payloads are NEVER replaced.
 */
data class ServerSaleAck(
    val companyId: ServerUlid,
    val clientEventId: ClientUuid,
    val payloadDigest: PayloadDigest,
    val invoiceUlid: ServerUlid,
    val paymentUlid: ServerUlid,
    val serverAcknowledgedAt: String
)

/**
 * Typed outcomes for checking the synchronization status of an event on the backend.
 * Models the proposed v2 synchronization gateway contract.
 * Note: Actual HTTP / v2 route endpoints are not live; this contract defines the typed abstraction.
 */
sealed interface SyncStatusOutcome {
    /**
     * The event was already accepted, ledgered, and confirmed on the server.
     */
    data class Posted(val ack: ServerSaleAck) : SyncStatusOutcome

    /**
     * The server has no record of this clientEventId for this company.
     * Permits submission of the original canonical bare event bytes.
     */
    data object NotFound : SyncStatusOutcome

    /**
     * 409 Business conflict: The event conflicts with existing server business rules or ledger state.
     */
    data class BusinessConflict(val reason: String) : SyncStatusOutcome

    /**
     * 401 Authentication denied: Caller credentials/token are invalid, revoked, or expired.
     * Requires scope queue lock on auth denial across entire company/actor/device scope.
     */
    data class AuthDenied(val reason: String) : SyncStatusOutcome

    /**
     * 403 Membership / Forbidden denial: Actor lacks tenant membership or permission.
     * Fails closed without retry loop.
     */
    data class MembershipDenied(val reason: String) : SyncStatusOutcome

    /**
     * Transient network error, timeout, connection drop, or 5xx server failure.
     */
    data class TransientUnknown(
        val errorMessage: String,
        val isTimeout: Boolean = false
    ) : SyncStatusOutcome
}

/**
 * Typed outcomes for submitting a sale event to the gateway.
 */
sealed interface SyncSubmitOutcome {
    /**
     * Successfully accepted and committed by the server, returning authoritative receipt.
     */
    data class Posted(val ack: ServerSaleAck) : SyncSubmitOutcome

    /**
     * 409 Business conflict: rejected by server ledger/validation.
     */
    data class BusinessConflict(val reason: String) : SyncSubmitOutcome

    /**
     * 401 Authentication denied: Caller credentials/token are invalid, revoked, or expired.
     */
    data class AuthDenied(val reason: String) : SyncSubmitOutcome

    /**
     * 403 Membership / Forbidden denial.
     */
    data class MembershipDenied(val reason: String) : SyncSubmitOutcome

    /**
     * Transient network error, timeout, connection drop, or 5xx server failure.
     */
    data class TransientUnknown(
        val errorMessage: String,
        val isTimeout: Boolean = false
    ) : SyncSubmitOutcome
}

/**
 * Interface for typed sale synchronization gateway.
 *
 * SCOPE AND PROOF BOUNDARY NOTICE:
 * - Production has NO actual HTTP gateway implementation in this phase.
 * - This interface is injected into [SaleSyncCoordinator].
 * - In tests, fake gateways implement this interface with explicit counters and behaviors.
 * - Fresh server checks are authoritative; caller context provides identity, not cryptographic proof.
 * - Offline lease/caps/business approval are NOT encoded as fake defaults; backend is sole authority.
 * - Zero financial math duplication: model, bytes, and receipt mappings only.
 */
interface SaleSyncGateway {

    /**
     * Checks if the event is already known/posted on the backend.
     * Must be called BEFORE attempting submission for each claimed record.
     */
    fun checkStatus(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest
    ): SyncStatusOutcome

    /**
     * Submits the canonical event bytes for a sale.
     *
     * INVARIANTS:
     * - ONLY the original canonical bare event bytes ([canonicalEventUtf8]) are transmitted.
     * - [canonicalEventUtf8] MUST be defensively copied.
     * - No new draft, token, or receipt is created locally; no repricing or rekeying.
     * - Outer envelope construction is the future adapter's responsibility; coordinator never reserializes.
     */
    fun submitSale(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        canonicalEventUtf8: ByteArray
    ): SyncSubmitOutcome
}
