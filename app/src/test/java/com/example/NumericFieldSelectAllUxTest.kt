package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BusinessInfo
import com.example.model.DiscountPreset
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.StockMovement
import com.example.ui.components.CustomQuickItemDialog
import com.example.ui.components.SelectAllNumericOutlinedTextField
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.InvoiceReviewScreen
import com.example.ui.screens.PhysicalGoodsReceiptDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumericFieldSelectAllUxTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun selectAllNumericField_firstPointerClickReplacesExistingValue_andSubsequentTypingAppends() {
        var latestValue = ""
        composeTestRule.setContent {
            var text by remember { mutableStateOf("10.00") }
            latestValue = text
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                SelectAllNumericOutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("num_field")
                )
            }
        }

        // First tap on unfocused pre-filled field selects full "10.00", so immediate typing replaces it.
        composeTestRule.onNodeWithTag("num_field").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("num_field").performTextInput("2")
        composeTestRule.waitForIdle()
        assertEquals("2", latestValue)

        // Subsequent keystroke while already focused must append instead of re-selecting all.
        composeTestRule.onNodeWithTag("num_field").performTextInput("5")
        composeTestRule.waitForIdle()
        assertEquals("25", latestValue)
    }

    @Test
    fun selectAllNumericField_blurWithoutTypingPreservesValue_andRefocusSelectsAllAgain() {
        var latestValue = ""
        val numRequester = FocusRequester()
        var focusManagerRef: androidx.compose.ui.focus.FocusManager? = null

        composeTestRule.setContent {
            var text by remember { mutableStateOf("48.50") }
            latestValue = text
            focusManagerRef = LocalFocusManager.current
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                Column {
                    SelectAllNumericOutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .focusRequester(numRequester)
                            .testTag("num_field")
                    )
                    OutlinedTextField(
                        value = "other",
                        onValueChange = {},
                        modifier = Modifier.testTag("other_field")
                    )
                }
            }
        }

        // Focus via keyboard / FocusRequester, then blur without typing -> "48.50" remains intact.
        composeTestRule.runOnIdle { numRequester.requestFocus() }
        composeTestRule.waitForIdle()
        assertEquals("48.50", latestValue)

        composeTestRule.runOnIdle { checkNotNull(focusManagerRef).clearFocus() }
        composeTestRule.waitForIdle()
        assertEquals("48.50", latestValue)

        // Fresh focus session selects all again and replaces on input.
        composeTestRule.runOnIdle { numRequester.requestFocus() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("num_field").performTextInput("75")
        composeTestRule.waitForIdle()
        assertEquals("75", latestValue)
    }

    @Test
    fun selectAllNumericField_secondClickMovesCursorInsideWithoutReplacingWholeValue() {
        var latestValue = ""
        composeTestRule.setContent {
            var text by remember { mutableStateOf("10.00") }
            latestValue = text
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                SelectAllNumericOutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("num_field")
                )
            }
        }

        // First click focuses + selects full range.
        composeTestRule.onNodeWithTag("num_field").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("num_field").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 5))
        )

        // Second click on already-focused field collapses selection at tap cursor so typing inserts instead of replacing.
        composeTestRule.onNodeWithTag("num_field").performClick()
        composeTestRule.waitForIdle()
        val afterSecondClickRange = composeTestRule.onNodeWithTag("num_field")
            .fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue(afterSecondClickRange.collapsed)
        composeTestRule.onNodeWithTag("num_field").performSemanticsAction(SemanticsActions.SetSelection) {
            it(2, 2, true)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("num_field").performTextInput("9")
        composeTestRule.waitForIdle()
        assertEquals("109.00", latestValue)
    }

    @Test
    fun selectAllNumericField_preservesErrorsSupportingTextExternalUpdatesAndRtl() {
        var latestValue = ""
        composeTestRule.setContent {
            var text by remember { mutableStateOf("15.00") }
            latestValue = text
            SmallTraderTheme(darkTheme = true, isRtl = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        SelectAllNumericOutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            isError = true,
                            label = { Text("المبلغ") },
                            supportingText = { Text("دقة غير صالحة", modifier = Modifier.testTag("err_msg")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.testTag("rtl_num_field")
                        )
                        Button(
                            onClick = { text = "120.00" },
                            modifier = Modifier.testTag("preset_btn")
                        ) {
                            Text("120")
                        }
                    }
                }
            }
        }

        // Error and supporting text are preserved cleanly in RTL.
        assertTrue(composeTestRule.onAllNodesWithText("دقة غير صالحة").fetchSemanticsNodes().isNotEmpty())

        // External preset update propagates cleanly and still selects-all on next first click.
        composeTestRule.onNodeWithTag("preset_btn").performClick()
        composeTestRule.waitForIdle()
        assertEquals("120.00", latestValue)

        composeTestRule.onNodeWithTag("rtl_num_field").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rtl_num_field").performTextInput("9.50")
        composeTestRule.waitForIdle()
        assertEquals("9.50", latestValue)
    }

    @Test
    fun cashPaymentScreen_tenderFieldReplacesPrefilledAmountOnFirstClick() {
        var capturedTender = ""
        composeTestRule.setContent {
            var cashText by remember { mutableStateOf("20.00") }
            capturedTender = cashText
            val item = BasketItem(
                product = Product(
                    id = "p1", nameAr = "بن", nameEn = "Coffee",
                    category = ProductCategory.ALL, barcode = "123",
                    priceExact = BigDecimal("20.00"), unitAr = "قطعة", unitEn = "pc",
                    stockCount = 10, accentHex = 0xFF2E7D32
                ),
                quantity = BigDecimal.ONE,
                unitPriceExact = BigDecimal("20.00")
            )
            val state = TraderUiState(
                basket = listOf(item),
                cashInputText = cashText,
                businessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2),
                language = AppLanguage.EN
            )
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CashPaymentScreen(
                    state = state,
                    onSetExactAmount = { cashText = "20.00" },
                    onSelectDenomination = { cashText = it.toString() },
                    onUpdateCashText = { cashText = it },
                    onCompleteSale = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("cash_tender_input").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cash_tender_input").performTextInput("50")
        composeTestRule.waitForIdle()
        assertEquals("50", capturedTender)
    }

    @Test
    fun invoiceReviewScreen_customDiscountFieldReplacesPrefilledValueOnFirstClick() {
        var capturedDiscount = ""
        composeTestRule.setContent {
            var discountText by remember { mutableStateOf("5.00") }
            capturedDiscount = discountText
            val item = BasketItem(
                product = Product(
                    id = "p1", nameAr = "بن", nameEn = "Coffee",
                    category = ProductCategory.ALL, barcode = "123",
                    priceExact = BigDecimal("30.00"), unitAr = "قطعة", unitEn = "pc",
                    stockCount = 10, accentHex = 0xFF2E7D32
                ),
                quantity = BigDecimal.ONE,
                unitPriceExact = BigDecimal("30.00")
            )
            val state = TraderUiState(
                basket = listOf(item),
                discountPreset = DiscountPreset.CUSTOM_ILS,
                customDiscountText = discountText,
                businessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2),
                language = AppLanguage.EN
            )
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                InvoiceReviewScreen(
                    state = state,
                    onAddMoreItems = {},
                    onIncrementProduct = {},
                    onDecrementProduct = {},
                    onRemoveProduct = {},
                    onSelectDiscountPreset = {},
                    onUpdateCustomDiscount = { discountText = it },
                    onUpdateCustomerNote = {},
                    onAssignCustomer = {},
                    onProceedToCashPayment = {}
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("custom_discount_input"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_discount_input").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_discount_input").performTextInput("2.50")
        composeTestRule.waitForIdle()
        assertEquals("2.50", capturedDiscount)
    }

    @Test
    fun physicalGoodsReceiptDialog_quantityFieldReplacesPrefilledValueOnFirstClick() {
        var confirmedQty: Int? = null
        val prod = Product(
            id = "p1", nameAr = "سكر", nameEn = "Sugar",
            category = ProductCategory.ALL, barcode = "456",
            priceExact = BigDecimal("10.00"), unitAr = "كغ", unitEn = "kg",
            stockCount = 10, accentHex = 0xFF2E7D32
        )
        val bill = PurchaseBill(
            id = "bill-1",
            billNumber = "BILL-1",
            supplierId = "sup-1",
            date = "2026-11-25",
            totalExact = BigDecimal("50.00"),
            paidExact = BigDecimal.ZERO,
            lines = listOf(
                PurchaseLineItem(
                    productId = "p1",
                    productNameAr = "سكر",
                    productNameEn = "Sugar",
                    quantityExact = BigDecimal("5"),
                    unitCostExact = BigDecimal("10.00"),
                    receivedQuantityExact = BigDecimal.ZERO
                )
            )
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                PhysicalGoodsReceiptDialog(
                    bill = bill,
                    products = listOf(prod),
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onConfirmGrnResult = { _, _, qty, _ ->
                        confirmedQty = qty
                        Result.success(
                            StockMovement(
                                id = "sm-1",
                                productId = "p1",
                                date = "2026-11-25",
                                deltaExact = BigDecimal.valueOf(qty.toLong()),
                                reasonAr = "استلام",
                                reasonEn = "Received"
                            )
                        )
                    }
                )
            }
        }

        // Prefilled with remaining receivable quantity "5"; clicking and typing "3" must replace "5" with "3".
        composeTestRule.onNodeWithTag("grn_qty_input").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("grn_qty_input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 1))
        )
        composeTestRule.onNodeWithTag("grn_qty_input").performTextInput("3")
        composeTestRule.waitForIdle()
        val qtyText = composeTestRule.onNodeWithTag("grn_qty_input")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("3", qtyText)
        composeTestRule.onNodeWithTag("confirm_grn_submit_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(3, confirmedQty)
    }

    @Test
    fun customQuickItemDialog_nameFieldStaysAppendableWhilePriceFieldSelectsAllOnRefocus() {
        var confirmedName = ""
        var confirmedPrice = ""
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomQuickItemDialog(
                    language = AppLanguage.EN,
                    onDismiss = {},
                    onConfirm = { name, price ->
                        confirmedName = name
                        confirmedPrice = price
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("custom_item_name_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_item_name_input").performTextInput("Tea")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("custom_item_price_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_item_price_input").performTextInput("12.50")
        composeTestRule.waitForIdle()

        // Returning to name field should NOT overwrite "Tea"; appending " Box" produces "Tea Box".
        composeTestRule.onNodeWithTag("custom_item_name_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_item_name_input").performSemanticsAction(SemanticsActions.SetSelection) {
            it(3, 3, true)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_item_name_input").performTextInput(" Box")
        composeTestRule.waitForIdle()

        // Re-focusing price field selects "12.50" in full so typing "9.00" replaces it cleanly.
        composeTestRule.onNodeWithTag("custom_item_price_input").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("custom_item_price_input").performTextInput("9.00")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("custom_item_confirm_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Tea Box", confirmedName)
        assertEquals("9.00", confirmedPrice)
        assertFalse(confirmedPrice.contains("12.50"))
    }
}
