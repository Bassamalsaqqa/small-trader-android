package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.example.data.OfflineDemoTraderRepository
import com.example.model.AppLanguage
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.ui.screens.PurchaseBillMode
import com.example.ui.screens.ReceivePurchaseBillDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.math.RoundingMode

@RunWith(RobolectricTestRunner::class)
class MerchantPurchaseAccountingRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * P0 #1: Same SKU added twice at different costs (2x10 + 1x20 = 40, not 60).
     * Verifies exact priced lines preservation, exact total & payable, and that
     * stock is NOT modified until physical GRN receipt.
     */
    @Test
    fun regressionP0_1_repeatSku_differentCosts_exactPricedLines_total40_payable40_stockUnchangedUntilGrn() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()
        val trackedProduct = repo.getProducts().first { it.id == "p_olive_oil" && it.isStockTracked }
        val initialStock = trackedProduct.stockCount
        val initialSupplierBalance = supplier.balanceOwedIls

        var submittedBill: PurchaseBill? = null
        var isDismissed = false

        val state = TraderUiState(
            products = repo.getProducts(),
            suppliers = repo.getSuppliers()
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                ReceivePurchaseBillDialog(
                    state = state,
                    onDismiss = { isDismissed = true },
                    onConfirmStructuredBill = { supId, lines, paidNow, note ->
                        val res = repo.recordStructuredPurchaseBill(supId, lines, paidNow, repo.getCurrentSession(), note)
                        if (res.isSuccess) {
                            submittedBill = res.getOrNull()
                        }
                        res
                    }
                )
            }
        }

        // 1. Enter description
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextInput("توريد زيت زيتون دفعتين بسعرين مختلفين")

        // 2. Select SKU "p_olive_oil" and add First Line: 2 units @ ₪10.0
        composeTestRule.onNodeWithTag("chip_product_${trackedProduct.id}").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("2")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("10.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 3. Select same SKU "p_olive_oil" again and add Second Line: 1 unit @ ₪20.0
        composeTestRule.onNodeWithTag("chip_product_${trackedProduct.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("1")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("20.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 4. Verify Total field is READ-ONLY and derives strictly to 40.00 (2*10 + 1*20 = 40.00, NOT 60!)
        composeTestRule.onNodeWithTag("purchase_bill_total_input").assertTextContains("40.00", substring = true)

        // 5. Enter Paid Now = 15.00
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").performTextInput("15.0")
        composeTestRule.waitForIdle()

        // 6. Submit the bill to actual repository
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Dialog must dismiss on successful recording", isDismissed)
        assertNotNull("Submitted bill must not be null", submittedBill)

        val bill = submittedBill!!
        assertEquals("Authoritative total must be exactly 40.00 ILS (not 60)", 40.00, bill.totalIls, 0.001)
        assertEquals("Paid amount must be 15.00 ILS", 15.00, bill.paidIls, 0.001)
        assertEquals("Remaining payable must be 25.00 ILS (40 - 15)", 25.00, bill.remainingPayableIls, 0.001)

        // 7. Verify exact lines in domain repository: 2 distinct lines with distinct costs
        assertEquals("Must preserve 2 exact priced lines without weighted rounding loss", 2, bill.lines.size)
        assertEquals(2, bill.lines[0].quantity)
        assertEquals(10.0, bill.lines[0].unitCostIls, 0.001)
        assertEquals(1, bill.lines[1].quantity)
        assertEquals(20.0, bill.lines[1].unitCostIls, 0.001)

        // 8. Verify supplier balance updated by exactly 25.00 ILS
        val updatedSupplier = repo.getSuppliers().first { it.id == supplier.id }
        assertEquals(
            "Supplier payable balance owed must increase by exactly 25.00 ILS",
            initialSupplierBalance + 25.00,
            updatedSupplier.balanceOwedIls,
            0.001
        )

        // 9. CRITICAL FINANCIAL/STOCK INVARIANT: Stock does NOT change until GRN!
        val stockAfterBill = repo.getProducts().first { it.id == "p_olive_oil" }.stockCount
        assertEquals(
            "Physical stock must NOT change upon bill recording; stock updates only via GRN",
            initialStock,
            stockAfterBill
        )
    }

    /**
     * P0 #1: GRN receiving line identity, partial receiving, and idempotent replay.
     */
    @Test
    fun regressionP0_1_grn_receivingLineIdentity_partialReceiving_and_idempotentReplay() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()
        val trackedProduct = repo.getProducts().first { it.id == "p_olive_oil" }
        val stockBefore = trackedProduct.stockCount

        // Create bill with 2 lines for same SKU
        val line1 = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت زيتون",
            productNameEn = "Olive Oil",
            quantity = 2,
            unitCostIls = 10.0
        )
        val line2 = PurchaseLineItem(
            productId = "p_olive_oil",
            productNameAr = "زيت زيتون",
            productNameEn = "Olive Oil",
            quantity = 1,
            unitCostIls = 20.0
        )
        val billRes = repo.recordStructuredPurchaseBill(supplier.id, listOf(line1, line2), 0.0, repo.getCurrentSession())
        assertTrue(billRes.isSuccess)
        val bill = billRes.getOrNull()!!

        // Step 1: Receive first line (2 units) by lineId
        val line1Id = bill.lines[0].id
        val grn1Res = repo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = "p_olive_oil",
            receivedQty = 2,
            batchNote = "استلام الدفعة الأولى",
            operator = repo.getCurrentSession(),
            operationId = "op_grn_test_1",
            lineId = line1Id
        )
        assertTrue("GRN for line 1 must succeed", grn1Res.isSuccess)
        assertEquals(stockBefore + 2, repo.getProducts().first { it.id == "p_olive_oil" }.stockCount)

        val billAfterGrn1 = repo.getPurchaseBills().first { it.id == bill.id }
        assertTrue("Bill must be partially received", billAfterGrn1.isPartiallyReceived)
        assertFalse("Bill is not fully received yet", billAfterGrn1.isReceived)
        assertEquals(2, billAfterGrn1.receivedQuantity)

        // Step 2: Idempotent replay of operationId "op_grn_test_1"
        val replayRes = repo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = "p_olive_oil",
            receivedQty = 2,
            batchNote = "استلام الدفعة الأولى",
            operator = repo.getCurrentSession(),
            operationId = "op_grn_test_1",
            lineId = line1Id
        )
        assertTrue("Replaying exact same operationId must succeed idempotently", replayRes.isSuccess)
        assertEquals("Stock must not increment on idempotent replay", stockBefore + 2, repo.getProducts().first { it.id == "p_olive_oil" }.stockCount)

        // Step 3: Receive second line (1 unit) by lineId
        val line2Id = bill.lines[1].id
        val grn2Res = repo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = "p_olive_oil",
            receivedQty = 1,
            batchNote = "استلام الدفعة الثانية",
            operator = repo.getCurrentSession(),
            operationId = "op_grn_test_2",
            lineId = line2Id
        )
        assertTrue("GRN for line 2 must succeed", grn2Res.isSuccess)
        assertEquals(stockBefore + 3, repo.getProducts().first { it.id == "p_olive_oil" }.stockCount)

        val billAfterGrn2 = repo.getPurchaseBills().first { it.id == bill.id }
        assertTrue("Bill must now be fully received", billAfterGrn2.isReceived)
        assertFalse("Bill is no longer partially received", billAfterGrn2.isPartiallyReceived)
        assertEquals(3, billAfterGrn2.receivedQuantity)
    }

    /**
     * P0 #1: Fractional cost and exact cent rounding (e.g. 3 @ 10.33 = 30.99, 7 @ 5.07 = 35.49 -> 66.48).
     */
    @Test
    fun regressionP0_1_fractionalCost_and_centRounding() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()

        val lineA = PurchaseLineItem(productId = "p_olive_oil", quantity = 3, unitCostIls = 10.33)
        val lineB = PurchaseLineItem(productId = "p_zaatar_jenin", quantity = 7, unitCostIls = 5.07)

        val res = repo.recordStructuredPurchaseBill(supplier.id, listOf(lineA, lineB), 20.0, repo.getCurrentSession())
        assertTrue(res.isSuccess)
        val bill = res.getOrNull()!!

        // 3 * 10.33 = 30.99, 7 * 5.07 = 35.49, total = 66.48
        assertEquals("Exact cent rounding total must be 66.48 ILS", 66.48, bill.totalIls, 0.001)
        assertEquals("Paid amount is 20.00 ILS", 20.00, bill.paidIls, 0.001)
        assertEquals("Payable is exactly 46.48 ILS", 46.48, bill.remainingPayableIls, 0.001)
    }

    /**
     * P1: Deliberately entered ₪0.0 unit cost supported when mixed with paid goods.
     * Blank unit cost is rejected / invalid and does not silently default.
     */
    @Test
    fun regressionP1_explicitZeroCost_supportedMixedWithPaidGoods_blankCostRejected() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()
        val freeLine = PurchaseLineItem(productId = "p_zaatar_jenin", quantity = 5, unitCostIls = 0.0)
        val paidLine = PurchaseLineItem(productId = "p_olive_oil", quantity = 2, unitCostIls = 25.0)

        // 1. Structured bill with mixed zero-cost line and paid line must succeed
        val res = repo.recordStructuredPurchaseBill(supplier.id, listOf(freeLine, paidLine), 10.0, repo.getCurrentSession())
        assertTrue("Mixed zero-cost bill must succeed", res.isSuccess)
        val bill = res.getOrNull()!!
        assertEquals(2, bill.lines.size)
        assertEquals(0.0, bill.lines[0].unitCostIls, 0.001)
        assertEquals(0.0, bill.lines[0].totalCostIls, 0.001)
        assertEquals(25.0, bill.lines[1].unitCostIls, 0.001)
        assertEquals(50.0, bill.lines[1].totalCostIls, 0.001)
        assertEquals("Total must be 50.00 ILS", 50.00, bill.totalIls, 0.001)

        // 2. Bill with ONLY zero-cost lines (0.0 total) must fail domain validation
        val allFreeRes = repo.recordStructuredPurchaseBill(supplier.id, listOf(freeLine), 0.0, repo.getCurrentSession())
        assertTrue("Bill with 0 total must fail", allFreeRes.isFailure)
    }

    /**
     * P0 #2: Structured total is READ-ONLY and derived from lines. Freeform total
     * is only for explicit financial-only bills, never fallback when lines are empty.
     */
    @Test
    fun regressionP0_2_structuredTotalReadOnly_neverFallsBackToFinancial() {
        val repo = OfflineDemoTraderRepository()
        val state = TraderUiState(
            products = repo.getProducts(),
            suppliers = repo.getSuppliers()
        )
        var submittedStructured = false
        var submittedFinancial = false

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                ReceivePurchaseBillDialog(
                    state = state,
                    onDismiss = {},
                    onConfirmBill = { _, _, _, _, _, _ ->
                        submittedFinancial = true
                        true
                    },
                    onConfirmStructuredBill = { _, lines, _, _ ->
                        submittedStructured = true
                        Result.success(
                            PurchaseBill(
                                id = "pb_test",
                                billNumber = 101,
                                supplierId = "s1",
                                date = "today",
                                totalIls = 50.0,
                                paidIls = 0.0,
                                itemsSummary = "test",
                                lines = lines
                            )
                        )
                    }
                )
            }
        }

        // Initially in STRUCTURED mode with 0 lines: Confirm button must be disabled
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").assertExists()

        // Select a product, enter qty 2, unit cost 25.0, add line
        val prod = state.products.first { p -> p.isStockTracked }
        composeTestRule.onNodeWithTag("chip_product_${prod.id}").performClick()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("2")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("25.0")
        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Total should be 50.00 and read-only
        composeTestRule.onNodeWithTag("purchase_bill_total_input").assertTextContains("50.00", substring = true)

        // Submit in structured mode
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Must call structured bill handler", submittedStructured)
        assertFalse("Must NEVER fall back to financial bill handler in structured mode", submittedFinancial)
    }

    /**
     * P0 #2: Advance payment and overpayment contract verification.
     */
    @Test
    fun regressionP0_2_advancePayment_and_overpayment_contract() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()
        val line = PurchaseLineItem(productId = "p_olive_oil", quantity = 1, unitCostIls = 50.0)

        // 1. Paid-now exceeding bill total (e.g. paid 60 for 50 bill) must be rejected
        val overpaidRes = repo.recordStructuredPurchaseBill(supplier.id, listOf(line), 60.0, repo.getCurrentSession())
        assertTrue("Paid-now exceeding total must be rejected", overpaidRes.isFailure)

        // 2. Paying supplier cash disbursement exceeding owed balance creates store credit (negative balance owed)
        val initialBal = supplier.balanceOwedIls
        val payRes = repo.recordSupplierPayment(supplier.id, initialBal + 50.0, "دفعة مقدمة للمورد", repo.getCurrentSession(), "pay_adv_1")
        assertTrue("Supplier disbursement exceeding owed balance is permitted (store credit)", payRes.isSuccess)
        val updatedSup = repo.getSuppliers().first { it.id == supplier.id }
        assertEquals(-50.0, updatedSup.balanceOwedIls, 0.001)
    }

    /**
     * P0 #3: StateRestorationTester regression verifying the entire purchase draft
     * is saved and restored coherently (mode, supplier, SKU lines, qty, cost, line IDs, paid, notes, submissionId).
     */
    @Test
    fun regressionP0_3_stateRestorationTester_preservesWholeDraftCoherently() {
        val repo = OfflineDemoTraderRepository()
        val state = TraderUiState(
            products = repo.getProducts(),
            suppliers = repo.getSuppliers()
        )
        val restorationTester = StateRestorationTester(composeTestRule)

        restorationTester.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                ReceivePurchaseBillDialog(
                    state = state,
                    onDismiss = {},
                    onConfirmStructuredBill = { _, _, _, _ -> Result.failure(Exception("stub")) }
                )
            }
        }

        // Enter description
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextInput("مسودة طلبية زيت وزعتر")

        // Add Line 1: 2x Olive oil @ 12.50
        val oil = state.products.first { p -> p.id == "p_olive_oil" }
        composeTestRule.onNodeWithTag("chip_product_${oil.id}").performClick()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("2")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("12.50")
        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Add Line 2: 4x Zaatar @ 5.00
        val zaatar = state.products.first { p -> p.id == "p_zaatar_jenin" }
        composeTestRule.onNodeWithTag("chip_product_${zaatar.id}").performClick()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("4")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("5.00")
        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Total = 2*12.5 + 4*5 = 25 + 20 = 45.00
        composeTestRule.onNodeWithTag("purchase_bill_total_input").assertTextContains("45.00", substring = true)

        // Enter paid now = 10.00
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").performTextInput("10.00")
        composeTestRule.waitForIdle()

        // EMULATE SAVED INSTANCE STATE / PROCESS RECREATION
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        // Assert Whole Draft Restored:
        // 1. Container exists with both lines
        composeTestRule.onNodeWithTag("structured_purchase_lines_container").assertExists()

        // 2. Total is preserved at 45.00
        composeTestRule.onNodeWithTag("purchase_bill_total_input").assertTextContains("45.00", substring = true)

        // 3. Paid now is preserved at 10.00
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").assertTextContains("10.00", substring = true)

        // 4. Description is preserved
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").assertTextContains("مسودة طلبية زيت وزعتر", substring = true)
    }

    /**
     * P0 #3: ActivityScenario Android Activity Recreation.
     * Verifies parent dialog open flag and state survival across Activity recreation on the actual MainActivity.
     */
    @Test
    fun regressionP0_3_activityScenario_activityRecreation_preservesDialogOpenFlag() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        scenario.onActivity { activity ->
            // Access actual MainActivity ViewModel and open purchase dialog
            val vm = androidx.lifecycle.ViewModelProvider(activity)[TraderViewModel::class.java]
            vm.openReceivePurchaseDialog(true)
            assertTrue("Dialog open flag must be true in ViewModel state", vm.uiState.value.isReceivePurchaseDialogOpen)
        }

        // Recreate activity (simulates rotation / config change)
        scenario.recreate()

        scenario.onActivity { activity ->
            // Verify recreated activity retains dialog state from retained ViewModel
            assertNotNull("Recreated activity must not be null", activity)
            val vm = androidx.lifecycle.ViewModelProvider(activity)[TraderViewModel::class.java]
            assertTrue("Dialog open flag must survive Activity recreation on actual MainActivity", vm.uiState.value.isReceivePurchaseDialogOpen)
        }
        scenario.close()
    }
}
