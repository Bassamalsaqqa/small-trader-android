package com.example

import com.example.data.OfflineDemoTraderRepository
import com.example.data.TraderRepository
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserSession
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-Blocking Asynchronous Production Architecture Unit Tests.
 *
 * Verifies:
 * 1. [UI Liveness] UI thread returns synchronously (<50ms) without blocking on suspend IO.
 * 2. [No Premature Completion] Callback and draft operation ID rotation do not fire while in-flight.
 * 3. [CAS Concurrency Guard] Rapid concurrent user taps launch exactly one operation intent.
 * 4. [Fault Tolerance & Idempotency] Server failures keep draft operation ID intact for retry and surface truthful error.
 * 5. [Authoritative Completion] Draft key rotates only after authoritative success response.
 *
 * NOTE: As per root test runner instructions, this test suite is unexecuted in this run
 * and structured for regression suites.
 */
class MerchantNonBlockingAsyncTest {

    /**
     * Fake production repository that simulates asynchronous background IO with latches.
     * Being a class distinct from [OfflineDemoTraderRepository], [TraderViewModel] routes
     * all calls through the production [kotlinx.coroutines.CoroutineScope] and [Dispatchers.IO].
     */
    class DelayedFakeProductionRepository(
        private val delegate: OfflineDemoTraderRepository = OfflineDemoTraderRepository()
    ) : TraderRepository by delegate {

        val enterLatch = CountDownLatch(1)
        val proceedLatch = CountDownLatch(1)
        val executedCount = AtomicInteger(0)
        var simulateFailure: Boolean = false
        var failureMessage: String = "Server 503: Service Unavailable"

        override suspend fun updateProductPrice(
            productId: String,
            newPriceIls: Double,
            operator: UserSession
        ): Result<Unit> {
            executedCount.incrementAndGet()
            enterLatch.countDown()
            proceedLatch.await(5, TimeUnit.SECONDS)
            if (simulateFailure) {
                return Result.failure(IllegalStateException(failureMessage))
            }
            return delegate.updateProductPrice(productId, newPriceIls, operator)
        }

        override suspend fun recordSupplierPayment(
            supplierId: String,
            amount: java.math.BigDecimal,
            note: String,
            operator: UserSession,
            paymentId: String,
            cashAccountId: String?,
            paymentDate: String?,
            allocations: List<com.example.model.PaymentAllocationInput>
        ): Result<SupplierPayment> {
            executedCount.incrementAndGet()
            enterLatch.countDown()
            proceedLatch.await(5, TimeUnit.SECONDS)
            if (simulateFailure) {
                return Result.failure(IllegalStateException(failureMessage))
            }
            return delegate.recordSupplierPayment(
                supplierId = supplierId,
                amount = amount,
                note = note,
                operator = operator,
                paymentId = paymentId,
                cashAccountId = cashAccountId,
                paymentDate = paymentDate,
                allocations = allocations
            )
        }

        override suspend fun recordSupplierPayment(
            supplierId: String,
            amountIls: Double,
            note: String,
            operator: UserSession,
            paymentId: String
        ): Result<SupplierPayment> = recordSupplierPayment(
            supplierId = supplierId,
            amount = java.math.BigDecimal(amountIls.toString()),
            note = note,
            operator = operator,
            paymentId = paymentId,
            cashAccountId = null,
            paymentDate = null,
            allocations = emptyList()
        )

        override suspend fun confirmPhysicalGoodsReceipt(
            billId: String,
            productId: String,
            receivedQuantity: java.math.BigDecimal,
            batchNote: String,
            operator: UserSession,
            operationId: String,
            lineId: String?
        ): Result<StockMovement> {
            executedCount.incrementAndGet()
            enterLatch.countDown()
            proceedLatch.await(5, TimeUnit.SECONDS)
            if (simulateFailure) {
                return Result.failure(IllegalStateException(failureMessage))
            }
            return delegate.confirmPhysicalGoodsReceipt(billId, productId, receivedQuantity, batchNote, operator, operationId, lineId)
        }
    }

    // =========================================================================
    // 1. UI Thread Liveness: Calls return immediately without blocking
    // =========================================================================

    @Test
    fun uiThreadReturnsImmediately_whenProductionSuspendIOIsDelayed() {
        val fakeRepo = DelayedFakeProductionRepository()
        val viewModel = TraderViewModel(fakeRepo)
        val targetProduct = viewModel.uiState.value.products.first()

        val startTime = System.currentTimeMillis()
        val syncResult = viewModel.updateProductPrice(
            productId = targetProduct.id,
            newPriceIls = 99.99
        )
        val elapsed = System.currentTimeMillis() - startTime

        // UI caller must not be blocked: returns synchronously in under 100ms
        assertTrue("UI thread took $elapsed ms, expected < 100ms", elapsed < 100)
        // In production mode, synchronous return cannot claim premature success
        assertFalse("Production call must not return true before authoritative completion", syncResult)

        // Verify the background IO task actually arrived at the latch
        assertTrue("Background IO operation did not reach enter latch", fakeRepo.enterLatch.await(2, TimeUnit.SECONDS))
        assertEquals(1, fakeRepo.executedCount.get())

        // Cleanup
        fakeRepo.proceedLatch.countDown()
    }

    // =========================================================================
    // 2. In-Flight Safety: No premature callback or operation ID rotation
    // =========================================================================

    @Test
    fun inFlightOperation_maintainsBusyGuard_doesNotPrematurelyRotateOrComplete() {
        val fakeRepo = DelayedFakeProductionRepository()
        val viewModel = TraderViewModel(fakeRepo)
        val targetSupplier = viewModel.uiState.value.suppliers.first()
        val originalKey = viewModel.uiState.value.draftSupplierPaymentOperationId
        val callbackInvoked = AtomicBoolean(false)

        viewModel.paySupplierAmount(
            supplierId = targetSupplier.id,
            amountIls = 50.0,
            paymentId = originalKey,
            onComplete = { callbackInvoked.set(true) }
        )

        // Wait until coroutine reaches the suspend IO gate
        assertTrue("IO suspend did not start", fakeRepo.enterLatch.await(2, TimeUnit.SECONDS))

        // While in-flight:
        assertTrue("isActionBusy must be true while in-flight", viewModel.uiState.value.isActionBusy)
        assertFalse("onComplete must NOT be called before completion", callbackInvoked.get())
        assertEquals("Draft key must NOT rotate before completion", originalKey, viewModel.uiState.value.draftSupplierPaymentOperationId)

        // Release latch to let it succeed
        fakeRepo.proceedLatch.countDown()
    }

    // =========================================================================
    // 3. CAS Concurrency Guard: Rejects rapid duplicate taps
    // =========================================================================

    @Test
    fun concurrentTap_rejectedByCASGuard_onlyOneIntentReachesRepository() {
        val fakeRepo = DelayedFakeProductionRepository()
        val viewModel = TraderViewModel(fakeRepo)
        val targetSupplier = viewModel.uiState.value.suppliers.first()
        val secondCallbackResult = AtomicReference<Boolean?>(null)

        // Tap 1: Launches async operation
        val firstLaunch = viewModel.paySupplierAmount(
            supplierId = targetSupplier.id,
            amountIls = 25.0
        )
        assertFalse(firstLaunch)

        // Wait for first tap to enter IO gate
        assertTrue(fakeRepo.enterLatch.await(2, TimeUnit.SECONDS))
        assertTrue("isActionBusy must be active", viewModel.uiState.value.isActionBusy)

        // Tap 2: Rapid secondary tap while first is in-flight
        val secondLaunch = viewModel.paySupplierAmount(
            supplierId = targetSupplier.id,
            amountIls = 25.0,
            onComplete = { secondCallbackResult.set(it) }
        )

        // CAS guard must reject the secondary tap immediately
        assertFalse(secondLaunch)
        assertEquals(false, secondCallbackResult.get())
        assertEquals("Repository must only receive 1 execution intent", 1, fakeRepo.executedCount.get())

        // Cleanup
        fakeRepo.proceedLatch.countDown()
    }

    // =========================================================================
    // 4. Failure Resilience: Preserves draft key for idempotent retry & resets busy
    // =========================================================================

    @Test
    fun serverFailure_preservesDraftOperationId_resetsBusy_surfacesError() {
        val fakeRepo = DelayedFakeProductionRepository()
        fakeRepo.simulateFailure = true
        fakeRepo.failureMessage = "Server 500: Database Connection Failed"

        val viewModel = TraderViewModel(fakeRepo)
        val targetSupplier = viewModel.uiState.value.suppliers.first()
        val originalKey = viewModel.uiState.value.draftSupplierPaymentOperationId
        val completionLatch = CountDownLatch(1)
        val callbackResult = AtomicBoolean(true)

        viewModel.paySupplierAmount(
            supplierId = targetSupplier.id,
            amountIls = 75.0,
            paymentId = originalKey,
            onComplete = { success ->
                callbackResult.set(success)
                completionLatch.countDown()
            }
        )

        // Unblock backend failure
        assertTrue(fakeRepo.enterLatch.await(2, TimeUnit.SECONDS))
        fakeRepo.proceedLatch.countDown()

        // Wait for completion
        assertTrue(completionLatch.await(3, TimeUnit.SECONDS))

        // Assert failure contract:
        assertFalse("Callback must receive false on failure", callbackResult.get())
        assertFalse("isActionBusy must be released in finally block", viewModel.uiState.value.isActionBusy)
        assertEquals("Draft key must be PRESERVED on failure for idempotent retry", originalKey, viewModel.uiState.value.draftSupplierPaymentOperationId)
        assertTrue("Supplier action error flag must be true", viewModel.uiState.value.isSupplierActionError)
    }

    // =========================================================================
    // 5. Authoritative Success: Rotates draft key only after success response
    // =========================================================================

    @Test
    fun serverSuccess_rotatesDraftOperationId_clearsBusyState() {
        val fakeRepo = DelayedFakeProductionRepository()
        fakeRepo.simulateFailure = false

        val viewModel = TraderViewModel(fakeRepo)
        val targetSupplier = viewModel.uiState.value.suppliers.first()
        val originalKey = viewModel.uiState.value.draftSupplierPaymentOperationId
        val completionLatch = CountDownLatch(1)
        val callbackResult = AtomicBoolean(false)

        viewModel.paySupplierAmount(
            supplierId = targetSupplier.id,
            amountIls = 30.0,
            paymentId = originalKey,
            onComplete = { success ->
                callbackResult.set(success)
                completionLatch.countDown()
            }
        )

        // Unblock backend success
        assertTrue(fakeRepo.enterLatch.await(2, TimeUnit.SECONDS))
        fakeRepo.proceedLatch.countDown()

        // Wait for completion
        assertTrue(completionLatch.await(3, TimeUnit.SECONDS))

        // Assert success contract:
        assertTrue("Callback must receive true on success", callbackResult.get())
        assertFalse("isActionBusy must be released", viewModel.uiState.value.isActionBusy)
        assertNotEquals("Draft key must be rotated after authoritative success", originalKey, viewModel.uiState.value.draftSupplierPaymentOperationId)
    }
}
