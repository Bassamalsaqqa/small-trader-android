package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.ReadClientErrorResponse

/**
 * Immutable active execution scope encapsulating actor, device, company, and monotonic generation.
 */
data class OnlineSaleScope(
    val actorId: ServerUlid,
    val deviceId: ClientUuid,
    val companyId: ServerUlid,
    val generation: Long
)

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
                "payloadDigest=$payloadDigest, invoiceId=$invoiceId, invoiceNumber='$invoiceNumber', " +
                "paymentId=$paymentId, grandTotal=$grandTotal, postedAt='$postedAt', wasReplay=$wasReplay)"
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

    /** Scope generation or active scope mismatch; zero HTTP requests or discarded response */
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
 * - Completely separate from the durable offline outbox synchronization coordinator.
 * - Missing local credentials never lock the outbox queue.
 * - Enforces status-first recovery on lost ACK before replaying the EXACT original prepared wire bytes.
 * - Status recovery strictly requires a matching non-null payload digest (fails closed on null or mismatch).
 * - Enforces schema honesty: never fabricates payment references.
 * - Guards ALL result branches post-network against stale session scope.
 */
class OnlineCashSaleOrchestrator(
    private val client: AndroidV2OnlineCashClient,
    private val tokenProvider: () -> String?,
    private val scopeGenerationProvider: () -> Long = { 0L },
    private val onAuthDeniedLockScope: ((scopeGeneration: Long, error: ReadClientErrorResponse) -> Unit)? = null,
    private val activeScopeSupplier: (() -> OnlineSaleScope?)? = null
) {

    private fun isScopeMatching(
        currentGen: Long,
        intent: PreparedOnlineSaleIntent,
        targetCompanyId: ServerUlid,
        targetDeviceId: ClientUuid
    ): Boolean {
        if (currentGen != intent.scopeGeneration) return false
        if (activeScopeSupplier != null) {
            val active = activeScopeSupplier.invoke() ?: return false
            return active.generation == intent.scopeGeneration &&
                    active.actorId == intent.context.actorId &&
                    active.companyId == targetCompanyId &&
                    active.deviceId == targetDeviceId
        }
        return true
    }

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
        } catch (_: IllegalArgumentException) {
            return OnlineCashOrchestrationResult.PreDispatchValidationError("SCOPE_VALIDATION_FAILED")
        }

        // 2. Pre-dispatch scope generation and active scope check (ZERO HTTP)
        val initialScopeGen = scopeGenerationProvider()
        if (!isScopeMatching(initialScopeGen, intent, targetCompanyId, targetDeviceId)) {
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

        // 4. Dispatch via V2 Online Cash Client with Lost-ACK recovery and active scope guard
        val isScopeActive = {
            val currentGen = scopeGenerationProvider()
            isScopeMatching(currentGen, intent, targetCompanyId, targetDeviceId)
        }

        val executionResult = client.executeOnlineCashSale(
            token = token,
            intent = intent,
            isScopeActive = isScopeActive
        )

        // 5. Post-network scope validation guarding ALL result branches
        val postNetworkScopeGen = scopeGenerationProvider()
        val scopeStillActive = isScopeMatching(postNetworkScopeGen, intent, targetCompanyId, targetDeviceId)

        if (!scopeStillActive) {
            return when (executionResult) {
                is OnlineCashExecutionResult.AuthDenied -> OnlineCashOrchestrationResult.StaleAuthDenied(
                    error = executionResult.error,
                    intentScopeGeneration = intent.scopeGeneration,
                    currentScopeGeneration = postNetworkScopeGen
                )
                else -> OnlineCashOrchestrationResult.ScopeMismatch(
                    expected = intent.scopeGeneration,
                    actual = postNetworkScopeGen
                )
            }
        }

        // 6. Evaluate execution result with strict financial consistency and schema honesty
        return when (executionResult) {
            is OnlineCashExecutionResult.Success -> {
                if (executionResult.commitResponse != null) {
                    val commit = executionResult.commitResponse

                    // Event parity check
                    if (commit.clientEventId != intent.clientEventId) {
                        return OnlineCashOrchestrationResult.ParseFailure("EVENT_PARITY_MISMATCH")
                    }

                    // Single shared receipt construction
                    val receipt = OnlineCashSaleReceipt(
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

                    val isFullCash = intent.profile.payment.settlementMode == "full"
                    if (isFullCash) {
                        // Aligned receipt-completeness validation: must have non-null paymentId AND paymentReceiptNumber
                        if (receipt.paymentId == null || receipt.paymentReceiptNumber.isNullOrBlank()) {
                            return OnlineCashOrchestrationResult.PostedWithoutPaymentReference(receipt)
                        }

                        // Verify paid/total/change consistency: amountPaid - changeReturned == grandTotal
                        val computedTotal = commit.amountPaid.value.subtract(commit.changeReturned.value)
                        if (computedTotal.compareTo(commit.grandTotal.value) != 0) {
                            return OnlineCashOrchestrationResult.ParseFailure("FINANCIAL_CONSISTENCY_MISMATCH")
                        }

                        // Verify amountPaid matches prepared profile payment amount
                        if (commit.amountPaid.value.compareTo(intent.profile.payment.amount.value) != 0) {
                            return OnlineCashOrchestrationResult.ParseFailure("FINANCIAL_CONSISTENCY_MISMATCH")
                        }

                        // Qualified online cash slice: exact settlement with change 0 (grand_total == payment.amount)
                        if (commit.grandTotal.value.compareTo(intent.profile.payment.amount.value) != 0) {
                            return OnlineCashOrchestrationResult.ParseFailure("FINANCIAL_CONSISTENCY_MISMATCH")
                        }

                        OnlineCashOrchestrationResult.ConfirmedFinancialSuccess(receipt)
                    } else {
                        if (receipt.paymentId != null && !receipt.paymentReceiptNumber.isNullOrBlank()) {
                            OnlineCashOrchestrationResult.ConfirmedFinancialSuccess(receipt)
                        } else {
                            OnlineCashOrchestrationResult.PostedWithoutPaymentReference(receipt)
                        }
                    }
                } else if (executionResult.statusResponse != null) {
                    val status = executionResult.statusResponse
                    val official = status.officialInvoice
                        ?: return OnlineCashOrchestrationResult.ParseFailure("MISSING_OFFICIAL_INVOICE")

                    if (status.clientEventId != intent.clientEventId) {
                        return OnlineCashOrchestrationResult.ParseFailure("EVENT_PARITY_MISMATCH")
                    }
                    if (status.companyId != intent.context.companyId) {
                        return OnlineCashOrchestrationResult.ParseFailure("TENANT_PARITY_MISMATCH")
                    }

                    // Status digest MUST be non-null and match expected normative digest
                    if (status.payloadDigest == null || status.payloadDigest != intent.expectedNormativeProfileDigest) {
                        return OnlineCashOrchestrationResult.StatusDigestMismatch(
                            expected = intent.expectedNormativeProfileDigest,
                            actual = status.payloadDigest
                        )
                    }

                    // Single shared receipt construction
                    val receipt = OnlineCashSaleReceipt(
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

                    val isFullCash = intent.profile.payment.settlementMode == "full"
                    if (isFullCash) {
                        // Aligned receipt-completeness validation: must have non-null paymentId AND paymentReceiptNumber
                        if (receipt.paymentId == null || receipt.paymentReceiptNumber.isNullOrBlank()) {
                            return OnlineCashOrchestrationResult.PostedWithoutPaymentReference(receipt)
                        }

                        // Authoritative financial reconciliation: official grand total must equal prepared online payment amount
                        // Qualified online cash slice specifies exact settlement with change 0 (payment.amount == grand_total)
                        if (official.grandTotal.value.compareTo(intent.profile.payment.amount.value) != 0) {
                            return OnlineCashOrchestrationResult.ParseFailure("FINANCIAL_CONSISTENCY_MISMATCH")
                        }

                        OnlineCashOrchestrationResult.ConfirmedFinancialSuccess(receipt)
                    } else {
                        if (receipt.paymentId != null && !receipt.paymentReceiptNumber.isNullOrBlank()) {
                            OnlineCashOrchestrationResult.ConfirmedFinancialSuccess(receipt)
                        } else {
                            OnlineCashOrchestrationResult.PostedWithoutPaymentReference(receipt)
                        }
                    }
                } else {
                    OnlineCashOrchestrationResult.ParseFailure("MISSING_RESPONSE_DTO")
                }
            }
            is OnlineCashExecutionResult.Conflict -> {
                OnlineCashOrchestrationResult.BusinessConflict(executionResult.error)
            }
            is OnlineCashExecutionResult.AuthDenied -> {
                onAuthDeniedLockScope?.invoke(postNetworkScopeGen, executionResult.error)
                OnlineCashOrchestrationResult.AuthDenied(executionResult.error, postNetworkScopeGen)
            }
            is OnlineCashExecutionResult.Forbidden -> {
                OnlineCashOrchestrationResult.Forbidden(executionResult.error)
            }
            is OnlineCashExecutionResult.ValidationFailed -> {
                OnlineCashOrchestrationResult.ValidationFailed(executionResult.error)
            }
            is OnlineCashExecutionResult.ParseFailure -> {
                if (executionResult.category.contains("DIGEST", ignoreCase = true)) {
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
