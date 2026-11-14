package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.ReadClientErrorResponse

/**
 * Authoritative receipt returned upon successful online sale processing.
 *
 * Invariant:
 * [paymentId] and [paymentReceiptNumber] accurately reflect backend server state.
 * Payment references are NEVER synthetically fabricated (no "paymentId ?: invoiceId").
 */
data class OnlineCashSaleReceipt(
    val companyId: ServerUlid,
    val clientEventId: ClientUuid,
    val payloadDigest: PayloadDigest,
    val invoiceId: ServerUlid,
    val invoiceNumber: String,
    val paymentId: ServerUlid?,
    val paymentReceiptNumber: String?,
    val grandTotal: MoneyAmount,
    val postedAt: String,
    val wasReplay: Boolean
) {
    override fun toString(): String =
        "OnlineCashSaleReceipt(companyId=$companyId, clientEventId=$clientEventId, " +
                "payloadDigest=$payloadDigest, invoiceId=$invoiceId, invoiceNumber="$invoiceNumber", " +
                "paymentId=$paymentId, grandTotal=$grandTotal, postedAt="$postedAt", wasReplay=$wasReplay)"
}

/**
 * Typed outcomes for online cash orchestration.
 *
 * Invariants:
 * - Differentiates between local token absence ([LocalTokenUnavailable]) and central server 401 ([AuthDenied]).
 * - Discards stale 401 callbacks when the local scope generation has advanced ([StaleAuthDenied]).
 * - Preserves schema-honest null payment references without synthetic fallbacks ([PostedWithoutPaymentReference]).
 * - Sanitized toString() prevents credential or raw unvalidated wire leaks.
 */
sealed class OnlineCashOrchestrationResult {
    /** Confirmed financial success with verified non-null server payment reference */
    data class ConfirmedFinancialSuccess(val receipt: OnlineCashSaleReceipt) : OnlineCashOrchestrationResult()

    /** Schema-honest posted outcome where the backend acknowledged invoice but returned null payment reference */
    data class PostedWithoutPaymentReference(val receipt: OnlineCashSaleReceipt) : OnlineCashOrchestrationResult()

    /** Local session token was unavailable; zero HTTP requests dispatched, no queue lock initiated */
    data class LocalTokenUnavailable(val reason: String = "No active staff session token available") : OnlineCashOrchestrationResult()

    /** 409 Business conflict on initial submission or recovery replay */
    data class BusinessConflict(val error: ReadClientErrorResponse) : OnlineCashOrchestrationResult()

    /** 401 Authentication denied on the active scope generation; triggers scope queue lock */
    data class AuthDenied(val error: ReadClientErrorResponse, val scopeGeneration: Long) : OnlineCashOrchestrationResult()

    /** Stale 401 received for an expired scope generation; discarded without triggering scope queue lock */
    data class StaleAuthDenied(
        val error: ReadClientErrorResponse,
        val intentScopeGeneration: Long,
        val currentScopeGeneration: Long
    ) : OnlineCashOrchestrationResult()

    /** 403 Forbidden access denial */
    data class Forbidden(val error: ReadClientErrorResponse) : OnlineCashOrchestrationResult()

    /** 422 Unprocessable entity / validation error from backend */
    data class ValidationFailed(val error: ReadClientErrorResponse) : OnlineCashOrchestrationResult()

    /** Status recovery payload digest was null or mismatched normative profile digest; fails closed */
    data class StatusDigestMismatch(
        val expected: PayloadDigest,
        val actual: PayloadDigest?
    ) : OnlineCashOrchestrationResult()

    /** Pre-dispatch scope generation mismatch; zero HTTP requests dispatched */
    data class ScopeMismatch(val expected: Long, val actual: Long) : OnlineCashOrchestrationResult()

    /** Pre-dispatch scope or argument validation error; zero HTTP requests dispatched */
    data class PreDispatchValidationError(val message: String) : OnlineCashOrchestrationResult()

    /** Unparseable or malformed server response */
    data class ParseFailure(val category: String = "PARSE_ERROR") : OnlineCashOrchestrationResult()

    /** Indeterminate transient network or server timeout; original event ID and bytes preserved */
    data class IndeterminateTransient(
        val clientEventId: ClientUuid,
        val payloadDigest: PayloadDigest,
        val reason: String
    ) : OnlineCashOrchestrationResult()
}

/**
 * Orchestrator for direct online cash sales.
 *
 * SCOPE AND BOUNDARY ARCHITECTURE:
 * - Operates strictly with [AndroidV2OnlineCashClient] and [PreparedOnlineSaleIntent].
 * - Completely separate from the durable offline outbox synchronization coordinator ([com.example.data.mobile.sync.SaleSyncCoordinator]).
 * - Does NOT reuse offline queue claims or translate 15-field offline events to online cash.
 * - Missing local credentials never lock the outbox queue.
 * - Enforces status-first recovery on lost ACK before replaying the EXACT original prepared wire bytes.
 * - Status recovery strictly requires a matching non-null payload digest (fails closed on null or mismatch).
 * - Enforces schema honesty: never fabricates payment references.
 */
class OnlineCashSaleOrchestrator(
    private val client: AndroidV2OnlineCashClient,
    private val tokenProvider: () -> String?,
    private val scopeGenerationProvider: () -> Long = { 0L },
    private val onAuthDeniedLockScope: ((scopeGeneration: Long, error: ReadClientErrorResponse) -> Unit)? = null
) {

    /**
     * Dispatches a prepared online cash sale.
     *
     * @param intent Prepared immutable online sale intent
     * @param targetCompanyId Authoritative tenant company ULID
     * @param targetDeviceId Authenticated device UUID
     * @param targetCurrency Expected sale currency
     */
    fun dispatchOnlineCashSale(
        intent: PreparedOnlineSaleIntent,
        targetCompanyId: ServerUlid,
        targetDeviceId: ClientUuid,
        targetCurrency: String
    ): OnlineCashOrchestrationResult {
        // 1. Pre-dispatch scope and argument validation (ZERO HTTP)
        try {
            intent.validateExactScope(
                expectedCompanyId = targetCompanyId,
                expectedDeviceId = targetDeviceId,
                expectedCurrency = targetCurrency,
                expectedClientEventId = intent.clientEventId
            )
        } catch (e: IllegalArgumentException) {
            return OnlineCashOrchestrationResult.PreDispatchValidationError(e.message ?: "Scope validation failed")
        }

        // 2. Pre-dispatch scope generation check (ZERO HTTP)
        val initialScopeGen = scopeGenerationProvider()
        if (intent.scopeGeneration != initialScopeGen) {
            return OnlineCashOrchestrationResult.ScopeMismatch(
                expected = initialScopeGen,
                actual = intent.scopeGeneration
            )
        }

        // 3. Local token validation: missing token is LOCAL UNAVAILABLE, never server AuthDenied (ZERO HTTP)
        val token = tokenProvider()?.trim()
        if (token.isNullOrEmpty()) {
            return OnlineCashOrchestrationResult.LocalTokenUnavailable(
                "No active staff session token available"
            )
        }

        // 4. Dispatch via V2 Online Cash Client with Lost-ACK recovery
        val executionResult = client.executeOnlineCashSale(
            token = token,
            companyId = intent.context.companyId,
            clientEventId = intent.clientEventId,
            expectedPayloadDigest = intent.expectedNormativeProfileDigest,
            canonicalBytes = intent.rawWireBytes
        )

        // 5. Evaluate execution result with strict scope generation and schema honesty
        return when (executionResult) {
            is OnlineCashExecutionResult.Success -> {
                val receipt = if (executionResult.commitResponse != null) {
                    val commit = executionResult.commitResponse
                    OnlineCashSaleReceipt(
                        companyId = intent.context.companyId,
                        clientEventId = commit.clientEventId,
                        payloadDigest = intent.expectedNormativeProfileDigest,
                        invoiceId = commit.invoiceId,
                        invoiceNumber = commit.invoiceNumber,
                        paymentId = commit.paymentId,
                        paymentReceiptNumber = commit.paymentReceiptNumber,
                        grandTotal = commit.grandTotal,
                        postedAt = commit.postedAt,
                        wasReplay = executionResult.wasReplay
                    )
                } else if (executionResult.statusResponse != null) {
                    val status = executionResult.statusResponse
                    val official = status.officialInvoice
                        ?: return OnlineCashOrchestrationResult.ParseFailure("Posted status response missing official_invoice")

                    // Status digest MUST be non-null and match expected normative digest
                    if (status.payloadDigest == null || status.payloadDigest != intent.expectedNormativeProfileDigest) {
                        return OnlineCashOrchestrationResult.StatusDigestMismatch(
                            expected = intent.expectedNormativeProfileDigest,
                            actual = status.payloadDigest
                        )
                    }

                    OnlineCashSaleReceipt(
                        companyId = status.companyId,
                        clientEventId = status.clientEventId,
                        payloadDigest = status.payloadDigest,
                        invoiceId = official.invoiceId,
                        invoiceNumber = official.invoiceNumber,
                        paymentId = official.paymentId,
                        paymentReceiptNumber = official.paymentReceiptNumber,
                        grandTotal = official.grandTotal,
                        postedAt = official.postedAt,
                        wasReplay = executionResult.wasReplay
                    )
                } else {
                    return OnlineCashOrchestrationResult.ParseFailure("Execution success missing response DTO")
                }

                // Schema honesty check: do NOT fabricate payment references
                if (receipt.paymentId != null) {
                    OnlineCashOrchestrationResult.ConfirmedFinancialSuccess(receipt)
                } else {
                    OnlineCashOrchestrationResult.PostedWithoutPaymentReference(receipt)
                }
            }
            is OnlineCashExecutionResult.Conflict -> {
                OnlineCashOrchestrationResult.BusinessConflict(executionResult.error)
            }
            is OnlineCashExecutionResult.AuthDenied -> {
                val currentScopeGen = scopeGenerationProvider()
                if (currentScopeGen == intent.scopeGeneration) {
                    onAuthDeniedLockScope?.invoke(currentScopeGen, executionResult.error)
                    OnlineCashOrchestrationResult.AuthDenied(executionResult.error, currentScopeGen)
                } else {
                    // Stale 401 callback for previous session scope: do NOT lock current scope
                    OnlineCashOrchestrationResult.StaleAuthDenied(
                        error = executionResult.error,
                        intentScopeGeneration = intent.scopeGeneration,
                        currentScopeGeneration = currentScopeGen
                    )
                }
            }
            is OnlineCashExecutionResult.Forbidden -> {
                OnlineCashOrchestrationResult.Forbidden(executionResult.error)
            }
            is OnlineCashExecutionResult.ValidationFailed -> {
                OnlineCashOrchestrationResult.ValidationFailed(executionResult.error)
            }
            is OnlineCashExecutionResult.ParseFailure -> {
                if (executionResult.category.contains("Payload digest missing or mismatch", ignoreCase = true)) {
                    OnlineCashOrchestrationResult.StatusDigestMismatch(
                        expected = intent.expectedNormativeProfileDigest,
                        actual = null
                    )
                } else {
                    OnlineCashOrchestrationResult.ParseFailure(executionResult.category)
                }
            }
            is OnlineCashExecutionResult.IndeterminateTransient -> {
                OnlineCashOrchestrationResult.IndeterminateTransient(
                    clientEventId = executionResult.clientEventId,
                    payloadDigest = executionResult.payloadDigest,
                    reason = executionResult.reason
                )
            }
        }
    }
}
