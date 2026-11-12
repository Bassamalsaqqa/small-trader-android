package com.example.data.mobile.sync

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid

/**
 * Authoritative server receipt acknowledgement for a posted offline sale event.
 *
 * Invariant:
 * Contains the authoritative backend identifiers, canonical digest, and receipt timestamp.
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
) {
    override fun toString(): String {
        return "ServerSaleAck(companyId=$companyId, clientEventId=$clientEventId, payloadDigest=$payloadDigest, invoiceUlid=$invoiceUlid, paymentUlid=$paymentUlid, serverAcknowledgedAt='$serverAcknowledgedAt')"
    }
}

/**
 * Fixed diagnostic categories for gateway outcomes.
 * Discards untrusted strings and provides safe typed metadata for logging and error reporting.
 */
enum class GatewayDiagnosticCategory {
    BUSINESS_CONFLICT,
    AUTH_DENIED,
    MEMBERSHIP_DENIED,
    TRANSIENT_ERROR,
    TRANSIENT_TIMEOUT
}

/**
 * Typed outcomes for checking the synchronization status of an event on the backend.
 * Models the proposed v2 synchronization gateway contract.
 * Note: Actual HTTP / v2 route endpoints are not live; this contract defines the typed abstraction.
 *
 * Invariants:
 * - toString() never leaks raw untrusted reason, errorMessage, or header text from hostile gateways.
 * - Typed category metadata provides safe inspection for callers.
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
    data class BusinessConflict(
        val reason: String = "",
        val category: GatewayDiagnosticCategory = GatewayDiagnosticCategory.BUSINESS_CONFLICT
    ) : SyncStatusOutcome {
        override fun toString(): String = "SyncStatusOutcome.BusinessConflict(category=$category)"
    }

    /**
     * 401 Authentication denied: Caller credentials/token are invalid, revoked, or expired.
     * Requires scope queue lock on auth denial across entire company/actor/device scope.
     */
    data class AuthDenied(
        val reason: String = "",
        val category: GatewayDiagnosticCategory = GatewayDiagnosticCategory.AUTH_DENIED
    ) : SyncStatusOutcome {
        override fun toString(): String = "SyncStatusOutcome.AuthDenied(category=$category)"
    }

    /**
     * 403 Membership / Forbidden denial: Actor lacks tenant membership or permission.
     * Fails closed without retry loop.
     */
    data class MembershipDenied(
        val reason: String = "",
        val category: GatewayDiagnosticCategory = GatewayDiagnosticCategory.MEMBERSHIP_DENIED
    ) : SyncStatusOutcome {
        override fun toString(): String = "SyncStatusOutcome.MembershipDenied(category=$category)"
    }

    /**
     * Transient network error, timeout, connection drop, or 5xx server failure.
     */
    data class TransientUnknown(
        val errorMessage: String = "",
        val isTimeout: Boolean = false,
        val category: GatewayDiagnosticCategory = if (isTimeout) GatewayDiagnosticCategory.TRANSIENT_TIMEOUT else GatewayDiagnosticCategory.TRANSIENT_ERROR
    ) : SyncStatusOutcome {
        override fun toString(): String = "SyncStatusOutcome.TransientUnknown(category=$category, isTimeout=$isTimeout)"
    }
}

/**
 * Typed outcomes for submitting a sale event to the gateway.
 *
 * Invariants:
 * - toString() never leaks raw untrusted reason, errorMessage, or header text from hostile gateways.
 * - Typed category metadata provides safe inspection for callers.
 */
sealed interface SyncSubmitOutcome {
    /**
     * Successfully accepted and committed by the server, returning authoritative receipt.
     */
    data class Posted(val ack: ServerSaleAck) : SyncSubmitOutcome

    /**
     * 409 Business conflict: rejected by server ledger/validation.
     */
    data class BusinessConflict(
        val reason: String = "",
        val category: GatewayDiagnosticCategory = GatewayDiagnosticCategory.BUSINESS_CONFLICT
    ) : SyncSubmitOutcome {
        override fun toString(): String = "SyncSubmitOutcome.BusinessConflict(category=$category)"
    }

    /**
     * 401 Authentication denied: Caller credentials/token are invalid, revoked, or expired.
     */
    data class AuthDenied(
        val reason: String = "",
        val category: GatewayDiagnosticCategory = GatewayDiagnosticCategory.AUTH_DENIED
    ) : SyncSubmitOutcome {
        override fun toString(): String = "SyncSubmitOutcome.AuthDenied(category=$category)"
    }

    /**
     * 403 Membership / Forbidden denial.
     */
    data class MembershipDenied(
        val reason: String = "",
        val category: GatewayDiagnosticCategory = GatewayDiagnosticCategory.MEMBERSHIP_DENIED
    ) : SyncSubmitOutcome {
        override fun toString(): String = "SyncSubmitOutcome.MembershipDenied(category=$category)"
    }

    /**
     * Transient network error, timeout, connection drop, or 5xx server failure.
     */
    data class TransientUnknown(
        val errorMessage: String = "",
        val isTimeout: Boolean = false,
        val category: GatewayDiagnosticCategory = if (isTimeout) GatewayDiagnosticCategory.TRANSIENT_TIMEOUT else GatewayDiagnosticCategory.TRANSIENT_ERROR
    ) : SyncSubmitOutcome {
        override fun toString(): String = "SyncSubmitOutcome.TransientUnknown(category=$category, isTimeout=$isTimeout)"
    }
}

/**
 * Interface for typed sale synchronization gateway.
 *
 * SCOPE AND PROOF BOUNDARY NOTICE:
 * - Production has NO actual HTTP gateway implementation in this phase.
 * - This interface is injected into [SaleSyncCoordinator].
 * - In tests, fake gateways implement this interface with explicit counters and behaviors.
 * - Fake gateways are for testing only; they provide NO network token authorization proofs.
 * - Authoritative receipt and settlement proof rests exclusively on the central backend.
 * - Tests do NOT constitute real device hardware power-loss proofs.
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
