package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import com.example.data.OfflineDemoTraderRepository
import com.example.model.AppLanguage
import com.example.model.PurchaseBill
import com.example.model.StockMovement
import com.example.model.SupplierPayment
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.screens.PhysicalGoodsReceiptDialog
import com.example.ui.screens.ReceivePurchaseBillDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MerchantRound3ComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun paySupplierDialog_ui_firstFailureRetainsInputs_and_retrySucceeds() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()
        var isDismissed = false
        var callCount = 0
        var recordedOpId: String? = null

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                PaySupplierDialog(
                    supplier = supplier,
                    lang = AppLanguage.AR,
                    onDismiss = { isDismissed = true },
                    onPaySupplier = { s, amount, opId ->
                        callCount++
                        recordedOpId = opId
                        if (callCount == 1) {
                            Result.failure(IllegalArgumentException("رصيد الصندوق غير كافٍ للصرف"))
                        } else {
                            Result.success(
                                SupplierPayment(
                                    id = opId,
                                    supplierId = supplier.id,
                                    date = "2026-11-09",
                                    amountIls = amount,
                                    note = "سداد نقدي"
                                )
                            )
                        }
                    }
                )
            }
        }

        // Enter disbursement amount
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").performTextClearance()
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").performTextInput("150.00")

        // First attempt: triggers failure
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").performClick()
        composeTestRule.waitForIdle()

        // Assert inline error surfaces on current composition frame
        composeTestRule.onNodeWithTag("supplier_pay_inline_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("supplier_pay_inline_error").assertTextContains("رصيد الصندوق غير كافٍ", substring = true)

        // Assert user's entered input is retained
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").assertTextContains("150.00", substring = true)
        val firstOpId = recordedOpId
        assertTrue("Submission ID must be generated", !firstOpId.isNullOrBlank())
        assertFalse("Dialog must not close on failure", isDismissed)

        // Second attempt: succeeds with same submission ID
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Submission ID must be preserved across retry", firstOpId, recordedOpId)
        assertTrue("Dialog must dismiss after successful payment", isDismissed)
    }

    @Test
    fun physicalGoodsReceiptDialog_ui_firstFailureRetainsInputs_and_retrySucceeds() {
        val repo = OfflineDemoTraderRepository()
        val bill = repo.getPurchaseBills().first { it.expectedProductId != null && it.pendingQuantity > 0 }
        var isDismissed = false
        var callCount = 0
        var recordedOpId: String? = null

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                PhysicalGoodsReceiptDialog(
                    bill = bill,
                    products = repo.getProducts(),
                    lang = AppLanguage.AR,
                    onDismiss = { isDismissed = true },
                    onConfirmGrnResult = { opId, prodId, qty, note ->
                        callCount++
                        recordedOpId = opId
                        if (callCount == 1) {
                            Result.failure(IllegalArgumentException("الكمية المدخلة تتجاوز رصيد الفاتورة"))
                        } else {
                            Result.success(
                                StockMovement(
                                    id = opId,
                                    productId = prodId,
                                    date = "2026-11-09",
                                    delta = qty,
                                    reasonAr = "سند استلام مخزني",
                                    reasonEn = "GRN",
                                    resultingStock = 50
                                )
                            )
                        }
                    }
                )
            }
        }

        // Enter quantity (within receivable bounds e.g. 2) and batch note
        composeTestRule.onNodeWithTag("grn_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("grn_qty_input").performTextInput("2")
        composeTestRule.onNodeWithTag("grn_note_input").performTextClearance()
        composeTestRule.onNodeWithTag("grn_note_input").performTextInput("تشغيلة 2026-B")

        // First attempt: triggers failure
        composeTestRule.onNodeWithTag("confirm_grn_submit_button").performClick()
        composeTestRule.waitForIdle()

        // Assert inline error surfaces on current composition frame
        composeTestRule.onNodeWithTag("goods_receipt_inline_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("goods_receipt_inline_error").assertTextContains("تتجاوز رصيد الفاتورة", substring = true)

        // Assert inputs and submission ID are retained
        composeTestRule.onNodeWithTag("grn_qty_input").assertTextContains("2", substring = true)
        composeTestRule.onNodeWithTag("grn_note_input").assertTextContains("تشغيلة 2026-B", substring = true)
        val firstOpId = recordedOpId
        assertTrue("GRN submission ID must be generated", !firstOpId.isNullOrBlank())
        assertFalse("Dialog must not close on failure", isDismissed)

        // Second attempt: succeeds
        composeTestRule.onNodeWithTag("confirm_grn_submit_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("GRN submission ID must be preserved across retry", firstOpId, recordedOpId)
        assertTrue("Dialog must dismiss after successful GRN", isDismissed)
    }

    @Test
    fun receivePurchaseBillDialog_ui_multiLineAndFailureRetry() {
        val repo = OfflineDemoTraderRepository()
        val state = TraderUiState(
            products = repo.getProducts(),
            suppliers = repo.getSuppliers()
        )
        var isDismissed = false
        var callCount = 0
        var submittedLinesCount = 0

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                ReceivePurchaseBillDialog(
                    state = state,
                    onDismiss = { isDismissed = true },
                    onConfirmStructuredBill = { supplierId, lines, paidNow, note ->
                        callCount++
                        submittedLinesCount = lines.size
                        if (callCount == 1) {
                            Result.failure(IllegalArgumentException("بيانات الفاتورة غير مقبولة محاسبياً"))
                        } else {
                            Result.success(
                                PurchaseBill(
                                    id = "pb_test",
                                    billNumber = 999,
                                    supplierId = supplierId,
                                    date = "2026-11-09",
                                    totalIls = 300.0,
                                    paidIls = paidNow,
                                    itemsSummary = note.ifBlank { "توريد بضاعة" },
                                    lines = lines
                                )
                            )
                        }
                    }
                )
            }
        }

        // Enter summary
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextInput("فاتورة توريد خضار")

        // Select tracked product chip
        val firstTracked = state.products.first { it.isStockTracked }
        composeTestRule.onNodeWithTag("chip_product_${firstTracked.id}").performClick()
        composeTestRule.waitForIdle()

        // Enter quantity and unit cost
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("5")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("20.0")

        // Add line
        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Assert structured lines container is added
        composeTestRule.onNodeWithTag("structured_purchase_lines_container").assertExists()

        // First submit triggers error
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purchase_bill_inline_error").assertExists()
        composeTestRule.onNodeWithTag("purchase_bill_inline_error").assertTextContains("غير مقبولة محاسبياً", substring = true)
        assertFalse("Dialog must not close on failure", isDismissed)

        // Retry succeeds
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Dialog must dismiss after successful bill recording", isDismissed)
        assertTrue("Must submit structured lines", submittedLinesCount >= 1)
    }
}
