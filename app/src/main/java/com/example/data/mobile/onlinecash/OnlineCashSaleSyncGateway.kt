package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.sync.SaleSyncGateway
import com.example.data.mobile.sync.ServerSaleAck
import com.example.data.mobile.sync.SyncStatusOutcome
import com.example.data.mobile.sync.SyncSubmitOutcome

/**
 * Adapter implementing [SaleSyncGateway] backed by [AndroidV2OnlineCashClient].
 *
 * Bridges the V2 online cash/status HTTP client directly into the synchronization
 * coordinator without duplicating writers or modifying production defaults.
 *
 * Invariants:
 * - Uses active staff session token supplied by [tokenProvider].
 * - On 401 auth denial, propagates [SyncStatusOutcome.AuthDenied] / [SyncSubmitOutcome.AuthDenied]
 *   which triggers outbox queue locking across tenant scope.
 * - On 409 conflict, propagates typed [SyncStatusOutcome.BusinessConflict] / [SyncSubmitOutcome.BusinessConflict].
 * - Canonical event bytes are passed immutably to client without local re-serialization.
 */
class OnlineCashSaleSyncGateway(
    private val client: AndroidV2OnlineCashClient,
    private val tokenProvider: () -> String?
) : SaleSyncGateway {

    override fun checkStatus(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest
    ): SyncStatusOutcome {
        val token = tokenProvider()
            ?: return SyncStatusOutcome.AuthDenied(reason = "No active staff session token available")

        return when (val result = client.checkStatus(token, companyId, clientEventId)) {
            is OnlineCashStatusResult.Posted -> {
                val resp = result.response
                val official = resp.officialInvoice
                    ?: return SyncStatusOutcome.TransientUnknown(errorMessage = "Posted status missing official_invoice")
                val paymentUlid = official.paymentId ?: official.invoiceId
                SyncStatusOutcome.Posted(
                    ServerSaleAck(
                        companyId = resp.companyId,
                        clientEventId = resp.clientEventId,
                        payloadDigest = resp.payloadDigest ?: payloadDigest,
                        invoiceUlid = official.invoiceId,
                        paymentUlid = paymentUlid,
                        serverAcknowledgedAt = official.postedAt
                    )
                )
            }
            is OnlineCashStatusResult.Unknown -> SyncStatusOutcome.NotFound
            is OnlineCashStatusResult.ParkedConflict -> SyncStatusOutcome.BusinessConflict(
                reason = "Event is in parked_conflict on backend"
            )
            is OnlineCashStatusResult.AuthDenied401 -> SyncStatusOutcome.AuthDenied(
                reason = result.error.code
            )
            is OnlineCashStatusResult.Forbidden403 -> SyncStatusOutcome.MembershipDenied(
                reason = result.error.code
            )
            is OnlineCashStatusResult.TransientError -> SyncStatusOutcome.TransientUnknown(
                errorMessage = result.category,
                isTimeout = result.isTimeout
            )
            is OnlineCashStatusResult.ValidationFailed422 -> SyncStatusOutcome.BusinessConflict(
                reason = "VALIDATION_FAILED: ${result.error.code}"
            )
            is OnlineCashStatusResult.HttpError -> SyncStatusOutcome.TransientUnknown(
                errorMessage = "HTTP_${result.statusCode}"
            )
            is OnlineCashStatusResult.ParseFailure -> SyncStatusOutcome.TransientUnknown(
                errorMessage = result.category
            )
        }
    }

    override fun submitSale(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        clientEventId: ClientUuid,
        payloadDigest: PayloadDigest,
        canonicalEventUtf8: ByteArray
    ): SyncSubmitOutcome {
        val token = tokenProvider()
            ?: return SyncSubmitOutcome.AuthDenied(reason = "No active staff session token available")

        return when (val result = client.submitSale(token, companyId, clientEventId, payloadDigest, canonicalEventUtf8)) {
            is OnlineCashSubmitResult.Created -> {
                val resp = result.response
                SyncSubmitOutcome.Posted(
                    ServerSaleAck(
                        companyId = companyId,
                        clientEventId = resp.clientEventId,
                        payloadDigest = payloadDigest,
                        invoiceUlid = resp.invoiceId,
                        paymentUlid = resp.paymentId ?: resp.invoiceId,
                        serverAcknowledgedAt = resp.postedAt
                    )
                )
            }
            is OnlineCashSubmitResult.Replayed -> {
                val resp = result.response
                SyncSubmitOutcome.Posted(
                    ServerSaleAck(
                        companyId = companyId,
                        clientEventId = resp.clientEventId,
                        payloadDigest = payloadDigest,
                        invoiceUlid = resp.invoiceId,
                        paymentUlid = resp.paymentId ?: resp.invoiceId,
                        serverAcknowledgedAt = resp.postedAt
                    )
                )
            }
            is OnlineCashSubmitResult.Conflict409 -> SyncSubmitOutcome.BusinessConflict(
                reason = result.error.code
            )
            is OnlineCashSubmitResult.AuthDenied401 -> SyncSubmitOutcome.AuthDenied(
                reason = result.error.code
            )
            is OnlineCashSubmitResult.Forbidden403 -> SyncSubmitOutcome.MembershipDenied(
                reason = result.error.code
            )
            is OnlineCashSubmitResult.ValidationFailed422 -> SyncSubmitOutcome.BusinessConflict(
                reason = "VALIDATION_FAILED: ${result.error.code}"
            )
            is OnlineCashSubmitResult.TransientError -> SyncSubmitOutcome.TransientUnknown(
                errorMessage = result.category,
                isTimeout = result.isTimeout
            )
            is OnlineCashSubmitResult.HttpError -> SyncSubmitOutcome.TransientUnknown(
                errorMessage = "HTTP_${result.statusCode}"
            )
            is OnlineCashSubmitResult.ParseFailure -> SyncSubmitOutcome.TransientUnknown(
                errorMessage = result.category
            )
        }
    }
}
