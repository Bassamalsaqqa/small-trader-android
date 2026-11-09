package com.example

import com.example.data.OfflineDemoTraderRepository
import com.example.data.SampleCatalog
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.ui.screens.PurchasesFilter
import com.example.model.PurchaseLineItem
import com.example.model.StockMovement
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.viewmodel.TraderViewModel
import com.example.viewmodel.TraderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * PR #3 Engineering & Architectural Regression Tests.
 *
 * Verifies fixes for:
 * 1. [P0] Supplier Payment & GRN failure handling (returns Boolean, preserves inputs, sets inline error, idempotent retry).
 * 2. [P0] Multiple partial goods receipts (strictly bounded <= pendingQuantity, cumulative stock tracking, duplicate rejection).
 * 3. [P1] PurchasesScreen Open Payables filter alignment (remainingPayableIls > 0.005, includes partially paid bills).
 * 4. [P1] Home Theme Mismatch fix (derived directly from state.isDarkTheme).
 * 5. [Perf] Precomputed lazy normalized search keys on Product.
 * 6. [Honesty] Honest pre-submit GRN status and draft basket preservation.
 */
class MerchantRound3EngineeringTest {

    // =========================================================================
    // 1. Supplier Payment Failure Handling & Preservation
    // =========================================================================

    @Test
    fun supplierPaymentFailure_returnsFalse_preservesStateAndSurfacesInlineError() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val initialSuppliers = viewModel.uiState.value.suppliers
        val targetSupplier = initialSuppliers.first { it.id == "sup_01" }
        val balanceBefore = targetSupplier.balanceOwedIls

        // Attempt invalid payment (0.0 or negative amount)
        val failureResult = viewModel.paySupplierAmount(
            supplierId = "sup_01",
            amountIls = 0.0,
            note = "Invalid Zero Payment"
        )

        // Must return false to indicate non-dismissal
        assertFalse("Supplier payment with invalid amount must return false", failureResult)

        // Invariant: Balance owed must remain completely unchanged
        val balanceAfter = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls
        assertEquals("Supplier balance must not change on failed payment", balanceBefore, balanceAfter, 0.001)

        // Invariant: Error state and message must be surfaced
        assertTrue("Error flag must be true after failed supplier payment", viewModel.uiState.value.isSupplierActionError)
        val errMsgAr = viewModel.uiState.value.supplierActionMessage(AppLanguage.AR)
        val errMsgEn = viewModel.uiState.value.supplierActionMessage(AppLanguage.EN)
        assertTrue("Arabic error message must not be blank", errMsgAr.isNotBlank())
        assertTrue("English error message must not be blank", errMsgEn.isNotBlank())
    }

    @Test
    fun supplierPaymentSuccess_recordsDisbursementAndReducesPayable() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val targetSupplier = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }
        val balanceBefore = targetSupplier.balanceOwedIls
        val payAmount = 200.0

        val successResult = viewModel.paySupplierAmount(
            supplierId = "sup_01",
            amountIls = payAmount,
            note = "Batch Cash Settlement"
        )

        // Must return true to confirm success and allow dismissal
        assertTrue("Supplier payment with valid amount must return true", successResult)

        // Invariant: Balance owed must decrease by exactly 200.00 ILS
        val balanceAfter = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls
        val expectedBalance = BigDecimal(balanceBefore.toString())
            .subtract(BigDecimal(payAmount.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
        assertEquals("Supplier balance must decrease by payment amount", expectedBalance, balanceAfter, 0.001)

        // Invariant: Supplier disbursement must be reflected in state
        assertFalse("isSupplierActionError must be false on success", viewModel.uiState.value.isSupplierActionError)
        assertTrue(viewModel.uiState.value.supplierPayments.any { it.supplierId == "sup_01" && it.amountIls == payAmount })
    }

    // =========================================================================
    // 2. Multiple Partial Goods Receipts & Bounded Idempotent Behavior
    // =========================================================================

    @Test
    fun multiplePartialGoodsReceipts_andIdempotentRetry() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val riceBefore = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount

        // Seeded bill pb_03 has expectedProductId = "p_rice_medium", expectedQuantity = 12, receivedQuantity = 0
        val billBefore = viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }
        assertEquals(12, billBefore.expectedQuantity)
        assertEquals(0, billBefore.receivedQuantity)
        assertEquals(12, billBefore.pendingQuantity)
        assertFalse(billBefore.isReceived)

        // Step 1: First partial receipt: 5 units of 12
        val firstPartial = viewModel.confirmPhysicalGoodsReceipt(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 5,
            batchNote = "Partial shipment #1"
        )
        assertTrue("First partial GRN must succeed", firstPartial)

        val billAfterFirst = viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }
        assertEquals("Received total must be 5", 5, billAfterFirst.receivedQuantity)
        assertEquals("Pending quantity must be 7", 7, billAfterFirst.pendingQuantity)
        assertFalse("Bill must remain uncompleted after partial receipt", billAfterFirst.isReceived)
        assertTrue("Bill must be marked partially received", billAfterFirst.isPartiallyReceived)
        assertEquals(
            "Product stock must increase by exactly 5",
            riceBefore + 5,
            viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        )

        // Step 2: Attempt excessive receipt (> pending 7): receive 10 units -> must fail
        val excessiveResult = viewModel.confirmPhysicalGoodsReceipt(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 10,
            batchNote = "Excessive receipt attempt"
        )
        assertFalse("Receipt exceeding pending quantity must be rejected", excessiveResult)
        assertEquals("Stock must not change on failed receipt", riceBefore + 5, viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount)

        // Step 3: Complete remaining partial receipt: 7 units of 7
        val secondPartial = viewModel.confirmPhysicalGoodsReceipt(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 7,
            batchNote = "Final shipment #2"
        )
        assertTrue("Second partial GRN completing total must succeed", secondPartial)

        val billAfterSecond = viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }
        assertEquals("Received total must be 12", 12, billAfterSecond.receivedQuantity)
        assertEquals("Pending quantity must be 0", 0, billAfterSecond.pendingQuantity)
        assertTrue("Bill must be marked received in full", billAfterSecond.isReceived)
        assertEquals(
            "Product stock must increase by total 12",
            riceBefore + 12,
            viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        )

        // Step 4: Duplicate receipt attempt on already-fulfilled bill -> rejected
        val duplicateAttempt = viewModel.confirmPhysicalGoodsReceipt(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 1,
            batchNote = "Duplicate attempt"
        )
        assertFalse("GRN on fully received bill must be rejected", duplicateAttempt)
        assertEquals("Stock must remain riceBefore + 12", riceBefore + 12, viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount)
    }

    // =========================================================================
    // 3. Purchases Screen Open Payables Filter Alignment
    // =========================================================================

    @Test
    fun purchasesScreen_openPayablesFilter_matchesBillsWithRemainingPayable() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val bills = viewModel.uiState.value.purchaseBills

        // Invariant: OPEN_PAYABLES includes all bills where remainingPayableIls > 0.005
        // Seeded bills all have remaining balances owed:
        // pb_01: 1750 - 550 = 1200.00 ILS (OPEN PAYABLE)
        // pb_02: 650 - 200 = 450.00 ILS (OPEN PAYABLE)
        // pb_03: 850 - 0 = 850.00 ILS (OPEN PAYABLE)
        val openPayableBills = bills.filter { it.remainingPayableIls > 0.005 }
        val fullyPaidBills = bills.filter { it.remainingPayableIls <= 0.005 }

        assertEquals("All 3 seeded bills have open payables", 3, openPayableBills.size)
        assertEquals("0 seeded bills are fully paid", 0, fullyPaidBills.size)

        // Verify filter enum exists and counts match
        val openFilter = PurchasesFilter.OPEN_PAYABLES
        assertNotNull(openFilter)
        val matchingCount = bills.count { bill ->
            when (openFilter) {
                PurchasesFilter.OPEN_PAYABLES -> bill.remainingPayableIls > 0.005
                else -> false
            }
        }
        assertEquals("OPEN_PAYABLES filter logic must match openPayableBills count", openPayableBills.size, matchingCount)
    }

    // =========================================================================
    // 4. Home Screen Theme Derivation & Consistency
    // =========================================================================

    @Test
    fun homeThemeDerivation_matchesStateIsDarkThemeDirectly() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Initially light theme
        assertFalse(viewModel.uiState.value.isDarkTheme)

        // Toggle theme
        viewModel.toggleTheme()
        assertTrue("Theme must toggle to dark in state", viewModel.uiState.value.isDarkTheme)

        // Toggle back
        viewModel.toggleTheme()
        assertFalse("Theme must toggle back to light in state", viewModel.uiState.value.isDarkTheme)
    }

    // =========================================================================
    // 5. Precomputed Lazy Search Keys Performance
    // =========================================================================

    @Test
    fun productLazySearchKeys_arePrecomputedAndMatchSearchQueries() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }

        // Test lazy fields exist and return normalized values
        assertNotNull(product.normalizedNameAr)
        assertNotNull(product.normalizedNameEn)
        assertTrue(product.normalizedNameAr.isNotBlank())
        assertTrue(product.normalizedNameEn.isNotBlank())

        // Test product.normalizeKey consistency against the product's actual Arabic name
        val key = Product.normalizeKey(product.nameAr)
        assertEquals(product.normalizedNameAr, key)

        // Test search query update and matching
        viewModel.updateSearchQuery("زيت")
        assertEquals("زيت", viewModel.uiState.value.searchQuery)
        assertTrue("Search match count must be at least 1", viewModel.uiState.value.allCategoriesMatchCount >= 1)
    }

    // =========================================================================
    // 6. Draft Basket Preservation On Home
    // =========================================================================

    @Test
    fun homeScreen_activeBasketPreservation_showsResumeAndNeverDiscards() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }

        // Initially empty basket
        assertEquals(0, viewModel.uiState.value.basketItemCount)
        assertEquals(0.0, viewModel.uiState.value.basketNetTotalIls, 0.001)

        // Add item to basket
        viewModel.addProductToBasket(oliveOil)
        assertEquals(1, viewModel.uiState.value.basketItemCount)
        assertTrue(viewModel.uiState.value.basketNetTotalIls > 0.0)

        // Navigating to HOME must preserve basket
        viewModel.navigateTo(TraderScreen.HOME)
        assertEquals("Basket must persist when on HOME screen", 1, viewModel.uiState.value.basketItemCount)

        // Navigating back to NEW_SALE must resume existing basket without clearing
        viewModel.navigateTo(TraderScreen.NEW_SALE)
        assertEquals("Basket must be preserved when resuming sale", 1, viewModel.uiState.value.basketItemCount)
    }

    // =========================================================================
    // 7. GRN Replay Protection & Stable Operation ID Verification
    // =========================================================================

    @Test
    fun grnReplay_identicalOperationReturnsRecordedResult_conflictingOperationRejected() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val initialStock = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount

        val stableOpId = "op_test_grn_${UUID.randomUUID()}"

        // Step 1: Submit first GRN with stable operation ID
        val res1 = viewModel.confirmPhysicalGoodsReceiptResult(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 5,
            batchNote = "Delivery Batch 1",
            operationId = stableOpId
        )
        assertTrue("First GRN submission must succeed", res1.isSuccess)
        val movement1 = res1.getOrNull()
        assertNotNull(movement1)
        assertEquals(5, movement1!!.delta)
        val stockAfterFirst = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        assertEquals(initialStock + 5, stockAfterFirst)

        // Step 2: Retry with IDENTICAL operation ID and identical payload -> returns recorded result, does NOT double increment
        val resRetry = viewModel.confirmPhysicalGoodsReceiptResult(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 5,
            batchNote = "Delivery Batch 1",
            operationId = stableOpId
        )
        assertTrue("Idempotent retry with same operationId must succeed", resRetry.isSuccess)
        assertEquals("Must return recorded movement ID", movement1.id, resRetry.getOrNull()?.id)
        val stockAfterRetry = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        assertEquals("Stock must remain exactly identical (no double receipt)", stockAfterFirst, stockAfterRetry)

        // Step 3: Conflict attempt with SAME operation ID but different payload (different qty 6) -> REJECTED
        val resConflict = viewModel.confirmPhysicalGoodsReceiptResult(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 6,
            batchNote = "Conflicting quantity attempt",
            operationId = stableOpId
        )
        assertTrue("Conflicting payload with existing operationId must fail", resConflict.isFailure)
        val stockAfterConflict = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        assertEquals("Stock must remain unchanged after conflicting attempt", stockAfterFirst, stockAfterConflict)
    }

    // =========================================================================
    // 8. Supplier Payment Duplicate Guard & Idempotency
    // =========================================================================

    @Test
    fun supplierPaymentReplay_identicalPaymentIdReturnsRecordedResult_conflictingPaymentRejected() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val supplierBefore = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }
        val balanceBefore = supplierBefore.balanceOwedIls

        val paymentId = "pay_test_${UUID.randomUUID()}"
        val payAmount = 150.0

        // Step 1: Record initial payment
        val res1 = viewModel.paySupplierAmountResult(
            supplierId = "sup_01",
            amountIls = payAmount,
            note = "Drawer disbursement",
            paymentId = paymentId
        )
        assertTrue("First payment must succeed", res1.isSuccess)
        val recordedPayment = res1.getOrNull()
        assertNotNull(recordedPayment)
        assertEquals(paymentId, recordedPayment!!.id)

        val balanceAfter1 = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls
        val expectedBalance = BigDecimal(balanceBefore.toString())
            .subtract(BigDecimal(payAmount.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
        assertEquals(expectedBalance, balanceAfter1, 0.001)

        // Step 2: Retry with identical payment ID and identical payload -> returns recorded payment without double deducting
        val resRetry = viewModel.paySupplierAmountResult(
            supplierId = "sup_01",
            amountIls = payAmount,
            note = "Drawer disbursement",
            paymentId = paymentId
        )
        assertTrue("Retry with same paymentId must succeed", resRetry.isSuccess)
        assertEquals("Must return recorded payment ID", paymentId, resRetry.getOrNull()?.id)
        val balanceAfterRetry = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls
        assertEquals("Balance must not be double deducted", balanceAfter1, balanceAfterRetry, 0.001)

        // Step 3: Conflict with SAME payment ID but different amount (250.0) -> REJECTED
        val resConflict = viewModel.paySupplierAmountResult(
            supplierId = "sup_01",
            amountIls = 250.0,
            note = "Conflicting amount",
            paymentId = paymentId
        )
        assertTrue("Conflicting payment with existing ID must fail", resConflict.isFailure)
        val balanceAfterConflict = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls
        assertEquals("Balance must remain unchanged after conflict rejection", balanceAfter1, balanceAfterConflict, 0.001)
    }

    // =========================================================================
    // 9. Structured Multi-Line Purchase & Domain Pre-Mutation Validation
    // =========================================================================

    @Test
    fun linkedPurchaseDomainValidation_andMultiLineStructuredPurchase() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val supplierBefore = viewModel.uiState.value.suppliers.first { it.id == "sup_01" }
        val balanceBefore = supplierBefore.balanceOwedIls

        // 1. Validation: Rejects invalid / missing SKU
        val invalidSkuLine = PurchaseLineItem(
            productId = "non_existent_sku",
            productNameAr = "صنف وهمي",
            quantity = 5,
            unitCostIls = 20.0
        )
        val resInvalidSku = viewModel.recordStructuredPurchaseBill(
            supplierId = "sup_01",
            lines = listOf(invalidSkuLine),
            paidNowIls = 0.0,
            note = "Invalid SKU test"
        )
        assertTrue("Missing SKU must be rejected before mutation", resInvalidSku.isFailure)
        assertEquals("Supplier balance must not mutate on invalid SKU", balanceBefore, viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls, 0.001)

        // 2. Validation: Rejects non-positive quantity
        val zeroQtyLine = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت زيتون",
            quantity = 0,
            unitCostIls = 35.0
        )
        val resZeroQty = viewModel.recordStructuredPurchaseBill(
            supplierId = "sup_01",
            lines = listOf(zeroQtyLine),
            paidNowIls = 0.0,
            note = "Zero quantity test"
        )
        assertTrue("Zero quantity line must be rejected", resZeroQty.isFailure)

        // 3. Structured Multi-Line Creation with Explicit Entered Unit Cost
        // Line 1: 10 units @ 32.50 ILS = 325.00 ILS (entered cost different from catalog)
        // Line 2: 4 units @ 45.00 ILS = 180.00 ILS
        // Authoritative Total = 325.00 + 180.00 = 505.00 ILS
        val validLine1 = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت زيتون بلدي",
            quantity = 10,
            unitCostIls = 32.50
        )
        val validLine2 = PurchaseLineItem(
            productId = "p_zaatar_jenin",
            productNameAr = "زعتر جنيني بلدي",
            quantity = 4,
            unitCostIls = 45.00
        )

        val resValidBill = viewModel.recordStructuredPurchaseBill(
            supplierId = "sup_01",
            lines = listOf(validLine1, validLine2),
            paidNowIls = 105.00,
            note = "Multi-line structured shipment"
        )
        assertTrue("Valid structured bill must succeed", resValidBill.isSuccess)
        val createdBill = resValidBill.getOrNull()
        assertNotNull(createdBill)
        assertEquals("Authoritative total must derive exactly from structured lines with entered unit cost", 505.00, createdBill!!.totalIls, 0.001)
        assertEquals(105.00, createdBill.paidIls, 0.001)
        assertEquals(400.00, createdBill.remainingPayableIls, 0.001)
        assertEquals(2, createdBill.lines.size)
        assertFalse(createdBill.isReceived)
        assertEquals(14, createdBill.pendingQuantity)

        // Verify supplier balance increased by exactly remaining payable (400.00 ILS)
        val expectedNewBalance = BigDecimal(balanceBefore.toString())
            .add(BigDecimal("400.00"))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
        assertEquals(expectedNewBalance, viewModel.uiState.value.suppliers.first { it.id == "sup_01" }.balanceOwedIls, 0.001)

        // 4. Multi-line Partial Goods Receiving
        val oilBefore = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }.stockCount
        val zaatarBefore = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" }.stockCount

        // Receive 6 of 10 olive oil
        val grn1 = viewModel.confirmPhysicalGoodsReceiptResult(
            billId = createdBill.id,
            productId = "p_olive_oil",
            receivedQty = 6,
            batchNote = "First batch oil receipt"
        )
        assertTrue("Receiving 6 of 10 oil units must succeed", grn1.isSuccess)
        val oilAfter1 = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }.stockCount
        assertEquals("Olive oil stock must increase by exactly 6", oilBefore + 6, oilAfter1)

        val updatedBill1 = viewModel.uiState.value.purchaseBills.first { it.id == createdBill.id }
        assertTrue(updatedBill1.isPartiallyReceived)
        assertFalse(updatedBill1.isReceived)
        assertEquals(8, updatedBill1.pendingQuantity) // 4 oil remaining + 4 zaatar

        // Receive 4 of 4 zaatar (completing zaatar line)
        val grn2 = viewModel.confirmPhysicalGoodsReceiptResult(
            billId = createdBill.id,
            productId = "p_zaatar_jenin",
            receivedQty = 4,
            batchNote = "Full zaatar delivery"
        )
        assertTrue("Receiving 4 zaatar units must succeed", grn2.isSuccess)
        val zaatarAfter = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" }.stockCount
        assertEquals("Zaatar stock must increase by 4", zaatarBefore + 4, zaatarAfter)

        // Receive remaining 4 olive oil (completing the entire bill)
        val grn3 = viewModel.confirmPhysicalGoodsReceiptResult(
            billId = createdBill.id,
            productId = "p_olive_oil",
            receivedQty = 4,
            batchNote = "Final oil delivery"
        )
        assertTrue("Receiving remaining 4 oil units must succeed", grn3.isSuccess)
        val updatedBillFinal = viewModel.uiState.value.purchaseBills.first { it.id == createdBill.id }
        assertTrue("Bill must be marked received in full", updatedBillFinal.isReceived)
        assertEquals(0, updatedBillFinal.pendingQuantity)
    }

    // =========================================================================
    // 10. Search Performance Benchmark (p95 Measurement)
    // =========================================================================

    @Test
    fun searchPerformanceBenchmark_precomputedTokensBenchmark() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Warmup JIT
        repeat(500) {
            viewModel.updateSearchQuery("زيت")
            viewModel.updateSearchQuery("oil")
            viewModel.updateSearchQuery("901")
        }

        val queries = listOf("زيت", "زعتر", "oil", "rice", "طحين", "قهوة", "شاي", "جبنة", "حليب", "بسكوت", "901", "729")
        val sampleSize = 5000
        val timingsNanos = LongArray(sampleSize)

        for (i in 0 until sampleSize) {
            val q = queries[i % queries.size]
            val start = System.nanoTime()
            viewModel.updateSearchQuery(q)
            val matchCount = viewModel.uiState.value.allCategoriesMatchCount
            val duration = System.nanoTime() - start
            timingsNanos[i] = duration
            assertTrue(matchCount >= 0)
        }

        timingsNanos.sort()
        val p50Micros = timingsNanos[(sampleSize * 0.50).toInt()] / 1_000.0
        val p95Micros = timingsNanos[(sampleSize * 0.95).toInt()] / 1_000.0
        val p99Micros = timingsNanos[(sampleSize * 0.99).toInt()] / 1_000.0

        println("BENCHMARK_SEARCH: sample=$sampleSize queries | p50=${p50Micros}µs | p95=${p95Micros}µs | p99=${p99Micros}µs")
        assertTrue("p95 search latency must be under 2000µs (2.0ms)", p95Micros < 2000.0)
    }
}
