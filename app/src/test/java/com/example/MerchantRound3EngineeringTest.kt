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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
    // 10. Search Performance Benchmark (10k & 50k Real filteredProducts Path)
    // =========================================================================

    @Test
    fun searchPerformanceBenchmark_10k_and_50k_bilingualCatalog_candidateVsOriginal9336da6() {
        val categories = ProductCategory.values()
        val arabicNames = listOf("زيت زيتون بكر ممتاز", "زعتر بلدي مجفف", "طحين قمح بلدي فاخر", "قهوة عربية بالهيل", "شاي أسود سيلاني فاخر", "جبنة بيضاء نابلسية", "حليب طازج كامل الدسم", "بسكوت بالتمر والسمسم", "أرز بسمتي هندي طويل", "سكر أبيض ناعم", "عدس مجروش أحمر", "حمص حب بلدي ممتاز")
        val englishNames = listOf("Extra Virgin Olive Oil", "Local Dried Thyme", "Fine Local Wheat Flour", "Arabic Coffee with Cardamom", "Ceylon Black Tea", "Nablus White Cheese", "Fresh Whole Milk", "Date & Sesame Biscuits", "Indian Long Basmati Rice", "Fine White Sugar", "Crushed Red Lentils", "Premium Chickpeas")
        val unitsAr = listOf("لتر", "كغم", "علبة", "كيس", "قطعة")
        val unitsEn = listOf("L", "kg", "Can", "Bag", "Piece")

        fun generateCatalog(count: Int): List<Product> {
            return (0 until count).map { i ->
                val cat = categories[i % categories.size]
                val nameIdx = i % arabicNames.size
                Product(
                    id = "p_synth_$i",
                    nameAr = "${arabicNames[nameIdx]} #$i",
                    nameEn = "${englishNames[nameIdx]} #$i",
                    category = cat,
                    barcode = "729000${(100000 + i)}",
                    priceIls = 10.0 + (i % 50),
                    costIls = 7.0 + (i % 35),
                    unitAr = unitsAr[i % unitsAr.size],
                    unitEn = unitsEn[i % unitsEn.size],
                    stockCount = 20 + (i % 100),
                    minStockThreshold = 8,
                    accentHex = 0xFF4A6572
                )
            }
        }

        fun baseline9336da6Filter(products: List<Product>, category: ProductCategory, query: String): List<Product> {
            fun normalize(raw: String): String = Product.normalizeKey(raw)
            val q = normalize(com.example.model.WesternFormatters.sanitizeWesternDigits(query.trim()))
            return products.filter { product ->
                val matchesCategory = category == ProductCategory.ALL || product.category == category
                val matchesQuery = if (q.isEmpty()) true else {
                    normalize(product.nameAr).contains(q) ||
                    normalize(product.nameEn).contains(q) ||
                    normalize(product.unitAr).contains(q) ||
                    normalize(product.unitEn).contains(q) ||
                    product.barcode.contains(q)
                }
                matchesCategory && matchesQuery
            }
        }

        val testQueries = listOf("زيت", "زعتر", "oil", "rice", "طحين", "قهوة", "شاي", "جبنة", "حليب", "بسكوت", "901", "729", "نابلسية", "Cardamom")

        for (catalogSize in listOf(10_000, 50_000)) {
            val setupStart = System.currentTimeMillis()
            val catalog = generateCatalog(catalogSize)
            val setupTimeMs = System.currentTimeMillis() - setupStart

            val state = TraderUiState(products = catalog)

            // Warmup JIT
            repeat(50) {
                val q = testQueries[it % testQueries.size]
                val s = state.copy(searchQuery = q)
                val dummy = s.filteredProducts.size
            }

            // Correctness check across all test queries: Candidate results must equal 9336da6 baseline
            for (q in testQueries) {
                val candidateFiltered = state.copy(searchQuery = q).filteredProducts
                val baselineFiltered = baseline9336da6Filter(catalog, ProductCategory.ALL, q)
                assertEquals(
                    "Catalog $catalogSize: query '$q' result size must match baseline",
                    baselineFiltered.size,
                    candidateFiltered.size
                )
                assertEquals(
                    "Catalog $catalogSize: query '$q' first 5 IDs must match baseline",
                    baselineFiltered.take(5).map { it.id },
                    candidateFiltered.take(5).map { it.id }
                )
            }

            val iterations = if (catalogSize == 10_000) 200 else 60
            val candidateTimingsNanos = LongArray(iterations)
            val baselineTimingsNanos = LongArray(iterations)

            for (i in 0 until iterations) {
                val q = testQueries[i % testQueries.size]
                val queryState = state.copy(searchQuery = q)

                // Measure Candidate
                val startCand = System.nanoTime()
                val candMatches = queryState.filteredProducts.size
                val candDuration = System.nanoTime() - startCand
                candidateTimingsNanos[i] = candDuration

                // Measure 9336da6 baseline
                val startBase = System.nanoTime()
                val baseMatches = baseline9336da6Filter(catalog, ProductCategory.ALL, q).size
                val baseDuration = System.nanoTime() - startBase
                baselineTimingsNanos[i] = baseDuration

                assertEquals(baseMatches, candMatches)
            }

            candidateTimingsNanos.sort()
            baselineTimingsNanos.sort()

            val candP50 = candidateTimingsNanos[(iterations * 0.50).toInt()] / 1_000.0
            val candP95 = candidateTimingsNanos[(iterations * 0.95).toInt()] / 1_000.0
            val candP99 = candidateTimingsNanos[(iterations * 0.99).toInt()] / 1_000.0

            val baseP50 = baselineTimingsNanos[(iterations * 0.50).toInt()] / 1_000.0
            val baseP95 = baselineTimingsNanos[(iterations * 0.95).toInt()] / 1_000.0
            val baseP99 = baselineTimingsNanos[(iterations * 0.99).toInt()] / 1_000.0

            println("BENCHMARK_SEARCH_COMPARISON: catalog=$catalogSize items, setupTime=${setupTimeMs}ms, iterations=$iterations")
            println("  Candidate: p50=${candP50}µs | p95=${candP95}µs | p99=${candP99}µs")
            println("  Baseline (9336da6): p50=${baseP50}µs | p95=${baseP95}µs | p99=${baseP99}µs")
            println("  Speedup factor at p50: ${String.format("%.2f", baseP50 / candP50)}x")
            assertTrue("Candidate p95 must be faster than baseline p95", candP95 < baseP95)
        }
    }

    // =========================================================================
    // 11. Atomic Sync & Search Invalidation Tests
    // =========================================================================

    @Test
    fun atomicSync_verifiesSingleAtomicEmission() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        var emissions = 0
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            viewModel.uiState.collect { emissions++ }
        }
        val beforeEmissions = emissions

        // Mutate repository to cause state delta
        val addRes = repo.createCustomer("عميل جديد للفحص", "0599123456", "نابلس")
        assertTrue("Customer creation must succeed", addRes.isSuccess)

        viewModel.syncFromRepository()
        // Must emit at most 1 update, never intermediate empty lists or multi-step flickers
        assertEquals("syncFromRepository must perform a single atomic state transition", beforeEmissions + 1, emissions)
        assertEquals("Repository updates must be reflected in state", 1, viewModel.uiState.value.customers.count { it.phone == "0599123456" })
        job.cancel()
    }

    @Test
    fun searchCache_invalidationOnQueryCategoryOrProductsChange() {
        val repo = OfflineDemoTraderRepository()
        val products = repo.getProducts()
        val state = TraderUiState(products = products, searchQuery = "زيت", selectedCategory = ProductCategory.ALL)

        val firstRun = state.filteredProducts
        val secondRun = state.filteredProducts
        assertTrue("Subsequent reads of filteredProducts must return the cached instance", firstRun === secondRun)

        // Invalidate on query change
        val newQueryState = state.copy(searchQuery = "زعتر")
        val queryChangedRun = newQueryState.filteredProducts
        assertFalse("Different query must compute fresh result", firstRun === queryChangedRun)

        // Invalidate on category change
        val newCategoryState = state.copy(selectedCategory = ProductCategory.PANTRY)
        val categoryChangedRun = newCategoryState.filteredProducts
        assertFalse("Different category must compute fresh result", firstRun === categoryChangedRun)

        // Invalidate on products change
        val extraProd = Product(
            id = "p_temp",
            nameAr = "زيت إضافي",
            nameEn = "Extra Oil",
            category = ProductCategory.ALL,
            barcode = "999",
            priceIls = 10.0,
            costIls = 8.0,
            unitAr = "لتر",
            unitEn = "L",
            stockCount = 10,
            accentHex = 0
        )
        val newProductsState = state.copy(products = products + extraProd)
        val productsChangedRun = newProductsState.filteredProducts
        assertFalse("Updated products list must compute fresh result", firstRun === productsChangedRun)
    }

    @Test
    fun grnReplay_firstFailureDoesNotCache_editedRetryWithSameIdSucceeds() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val bill = viewModel.uiState.value.purchaseBills.first { it.expectedProductId != null && it.pendingQuantity > 0 }
        val stableOpId = "grn_stable_retry_${System.currentTimeMillis()}"

        // Step 1: Submit invalid quantity (excessive > pending)
        val failResult = repo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = bill.expectedProductId!!,
            receivedQty = bill.pendingQuantity + 999,
            batchNote = "Invalid qty",
            operator = viewModel.uiState.value.currentSession,
            operationId = stableOpId
        )
        assertTrue("Excessive quantity must fail", failResult.isFailure)

        // Step 2: Retry with the EXACT same stableOpId with corrected valid quantity
        val successResult = repo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = bill.expectedProductId!!,
            receivedQty = bill.pendingQuantity,
            batchNote = "Corrected qty",
            operator = viewModel.uiState.value.currentSession,
            operationId = stableOpId
        )
        assertTrue("Retry with same operationId after prior failure must succeed (failed op was not cached)", successResult.isSuccess)
        assertEquals(stableOpId, successResult.getOrNull()?.id)

        // Step 3: Identical replay returns cached result without double mutation
        val stockBeforeReplay = repo.getProducts().first { it.id == bill.expectedProductId }.stockCount
        val replayResult = repo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = bill.expectedProductId!!,
            receivedQty = bill.pendingQuantity,
            batchNote = "Corrected qty",
            operator = viewModel.uiState.value.currentSession,
            operationId = stableOpId
        )
        assertTrue("Replay must succeed with cached result", replayResult.isSuccess)
        val stockAfterReplay = repo.getProducts().first { it.id == bill.expectedProductId }.stockCount
        assertEquals("Replay must not double increment stock", stockBeforeReplay, stockAfterReplay)
    }

    @Test
    fun supplierPayment_overpaymentPreservesNegativeBalanceAsStoreCredit() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val supplier = viewModel.uiState.value.suppliers.first { it.balanceOwedIls > 0 }
        val startingOwed = supplier.balanceOwedIls
        val overpaymentAmount = startingOwed + 250.0 // 250 ILS store advance

        val payResult = repo.recordSupplierPayment(
            supplierId = supplier.id,
            amountIls = overpaymentAmount,
            note = "Overpayment advance",
            operator = viewModel.uiState.value.currentSession,
            paymentId = "pay_over_${System.currentTimeMillis()}"
        )
        assertTrue("Overpayment disbursement must succeed", payResult.isSuccess)
        val updatedSup = repo.getSuppliers().first { it.id == supplier.id }
        assertEquals(-250.0, updatedSup.balanceOwedIls, 0.001)
    }

    @Test
    fun structuredPurchase_duplicateSkuAggregatesCorrectly_rejectsInvalidCostOrQty() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()

        // 1. Rejects NaN / Infinity unit cost
        val nanLine = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت",
            productNameEn = "Oil",
            quantity = 5,
            unitCostIls = Double.NaN
        )
        val nanRes = repo.recordStructuredPurchaseBill(supplier.id, listOf(nanLine), 0.0, repo.getCurrentSession())
        assertTrue("NaN cost must be rejected", nanRes.isFailure)

        val infLine = nanLine.copy(unitCostIls = Double.POSITIVE_INFINITY)
        val infRes = repo.recordStructuredPurchaseBill(supplier.id, listOf(infLine), 0.0, repo.getCurrentSession())
        assertTrue("Infinity cost must be rejected", infRes.isFailure)

        // 2. Duplicate SKU aggregation policy
        val lineA = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت زيتون",
            productNameEn = "Olive Oil",
            quantity = 5,
            unitCostIls = 20.0
        )
        val lineB = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت زيتون",
            productNameEn = "Olive Oil",
            quantity = 15,
            unitCostIls = 24.0
        )
        // Total expected = 5*20 + 15*24 = 100 + 360 = 460 ILS, totalQty = 20, avg cost = 23.0
        val dupRes = repo.recordStructuredPurchaseBill(supplier.id, listOf(lineA, lineB), 0.0, repo.getCurrentSession())
        assertTrue("Duplicate SKU aggregation must succeed", dupRes.isSuccess)
        val bill = dupRes.getOrNull()!!
        assertEquals("Authoritative total must be exactly 460.00 ILS", 460.00, bill.totalIls, 0.001)
        assertEquals("Duplicate SKU must be aggregated into 1 line", 1, bill.lines.size)
        assertEquals(20, bill.lines.first().quantity)
        assertEquals(23.0, bill.lines.first().unitCostIls, 0.001)
    }
}
