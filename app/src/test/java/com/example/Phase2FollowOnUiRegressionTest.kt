package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.OfflineDemoTraderRepository
import com.example.model.AppLanguage
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.SettlementTarget
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.CustomerDetailScreen
import com.example.ui.screens.CustomerPaymentCollectionDialog
import com.example.ui.screens.CustomerPickerModalSheet
import com.example.ui.screens.InvoiceReviewScreen
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
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
class Phase2FollowOnUiRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun vmAndCashPaymentScreen_subCentCreditExcessAndUnrepresentable_useStitchBidi2dpWithoutRaw3dp() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3)
        )
        val vm = TraderViewModel(repo)
        val customer = Customer(
            id = "cust_jod_limit",
            nameAr = "زبون دينار",
            nameEn = "JOD Customer",
            phone = "0790000001",
            balanceExact = BigDecimal("10.000"),
            creditLimitExact = BigDecimal("10.000"),
            isDetailLoaded = true
        )

        // 1. VM unrepresentable total (10.001 JOD) -> formats 2dp with LTR isolation, never raw 10.001
        vm.addCustomQuickItem("Exact 10.001", "10.001")
        vm.completeCashSale(asCreditDebt = false)
        val unrepError = vm.uiState.value.checkoutErrorMessage.orEmpty()
        assertTrue("Unrepresentable error should use Stitch 2dp display: $unrepError", unrepError.contains("\u206610.00 JOD\u2069"))
        assertFalse("Unrepresentable error must NOT interpolate raw 10.001: $unrepError", unrepError.contains("10.001"))

        val jOdThreeDpItem = vm.uiState.value.basket.first()

        // 2. VM credit excess by 0.001 JOD -> renders < 0.01 JOD (never 0.00 JOD)
        vm.removeProductFromBasket(jOdThreeDpItem.product.id)
        vm.addCustomQuickItem("Representable 10", "10.00")
        vm.assignCustomerToSale(customer.copy(balanceExact = BigDecimal("0.001"), creditLimitExact = BigDecimal("10.00")))
        vm.completeCashSale(asCreditDebt = true)
        val excessError = vm.uiState.value.checkoutErrorMessage.orEmpty()
        assertTrue("Credit excess by 0.001 must render \u2066< 0.01 JOD\u2069 not 0.00 JOD: $excessError", excessError.contains("\u2066< 0.01 JOD\u2069"))
        assertFalse("Credit excess error must NOT show 0.00 JOD: $excessError", excessError.contains("0.00 JOD"))

        // 3. CashPaymentScreen renders unrepresentable banner without raw 10.001 and credit ceiling excess < 0.01 JOD with LTR isolation
        val screenState = TraderUiState(
            language = AppLanguage.AR,
            businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
            basket = listOf(jOdThreeDpItem),
            cashInputText = "10.00",
            selectedCustomerId = customer.id,
            customers = listOf(customer.copy(balanceExact = BigDecimal("0.000"), creditLimitExact = BigDecimal("10.000")))
        )
        assertEquals(0, BigDecimal("0.001").compareTo(screenState.remainingUnpaidExact))
        assertEquals(0, BigDecimal("0.001").compareTo(screenState.creditCeilingExcessExact))

        composeTestRule.setContent {
            SmallTraderTheme(isRtl = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    CashPaymentScreen(
                        state = screenState,
                        onSetExactAmount = {},
                        onSelectDenomination = {},
                        onCompleteSale = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cash_collection_unrepresentable_warning").performScrollTo().assertIsDisplayed()
        assertEquals("Must NOT leak raw 10.001 in CashPaymentScreen", 0, composeTestRule.onAllNodesWithText("10.001", substring = true).fetchSemanticsNodes().size)
        assertTrue(composeTestRule.onAllNodesWithText("\u206610.00 JOD\u2069", substring = true).fetchSemanticsNodes().isNotEmpty())

        composeTestRule.onNodeWithTag("payment_mode_credit_tab").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("payment_credit_limit_warning").performScrollTo().assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("\u2066< 0.01 JOD\u2069", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun cashPaymentShortfall_vmAndScreen_useStitchBidi2dpWithLtrIsolation() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3)
        )
        val vm = TraderViewModel(repo)
        vm.addCustomQuickItem("Item", "10.00")
        vm.updateCashInputText("9.99")
        vm.completeCashSale(asCreditDebt = false)
        val err = vm.uiState.value.checkoutErrorMessage.orEmpty()
        assertTrue("Shortfall message must wrap 0.01 JOD in LTR isolates: $err", err.contains("\u20660.01 JOD\u2069"))
        assertTrue("Full total must wrap 10.00 JOD in LTR isolates: $err", err.contains("\u206610.00 JOD\u2069"))

        // Sub-cent (< 0.005) collect-full message on CashPaymentScreen when net total = 0.001 JOD
        val itemTemplate = vm.uiState.value.basket.first()
        val screenState = TraderUiState(
            language = AppLanguage.EN,
            businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
            basket = listOf(itemTemplate.copy(precomputedLineTotalExact = BigDecimal("0.001"))),
            cashInputText = ""
        )
        assertEquals(0, BigDecimal("0.001").compareTo(screenState.remainingUnpaidExact))
        composeTestRule.setContent {
            SmallTraderTheme(isRtl = false) {
                CashPaymentScreen(
                    state = screenState,
                    onSetExactAmount = {},
                    onSelectDenomination = {},
                    onCompleteSale = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Collect full amount (\u2066< 0.01 JOD\u2069) to complete cash sale").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun invoiceReviewScreen_usesStitchBidi2dpWithoutRawGreaterThan2dp() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3)
        )
        val vm = TraderViewModel(repo)
        vm.addCustomQuickItem("SubCent Item", "10.00")
        val itemTemplate = vm.uiState.value.basket.first()
        val reviewState = TraderUiState(
            language = AppLanguage.AR,
            businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
            basket = listOf(itemTemplate),
            discountPreset = com.example.model.DiscountPreset.CUSTOM_ILS,
            customDiscountText = "0.001",
            currentSession = com.example.model.UserSession(
                id = "act_1",
                name = "Cashier",
                username = "cashier",
                role = com.example.model.UserRole.CASHIER,
                branchNameAr = "الفرع",
                branchNameEn = "Main",
                isDemoSession = false,
                permissions = setOf("pos.sale")
            )
        )
        composeTestRule.setContent {
            SmallTraderTheme(isRtl = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    InvoiceReviewScreen(
                        state = reviewState,
                        onAddMoreItems = {},
                        onIncrementProduct = {},
                        onDecrementProduct = {},
                        onRemoveProduct = {},
                        onSelectDiscountPreset = {},
                        onUpdateCustomDiscount = {},
                        onUpdateCustomerNote = {},
                        onAssignCustomer = {},
                        onProceedToCashPayment = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).let { scrollable ->
            scrollable.performScrollToNode(androidx.compose.ui.test.hasTestTag("unsupported_discount_recovery_card"))
        }
        assertTrue(composeTestRule.onAllNodesWithText("\u2066< 0.01 JOD\u2069", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("\u206610.00 JOD\u2069", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals("Unsupported discount notice must not leak raw 0.001", 0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun customerSettlementTargets_useTargetCurrency_andShowInline2dpHintOnUnrepresentableAllocation() {
        val custTarget = SettlementTarget(
            targetType = "invoice",
            targetId = "inv_usd_3dp",
            targetNumber = "INV-USD-77",
            businessDate = "2026-11-28",
            dueDate = null,
            daysOverdue = 0,
            currency = "USD",
            currencyMinorUnits = 3,
            originalAmountExact = BigDecimal("45.505"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("45.505")
        )
        val customer = Customer(id = "cust_1", nameAr = "زبون", nameEn = "Customer", balanceExact = BigDecimal("10.125"))

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPaymentCollectionDialog(
                    customer = customer,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onConfirmPayment = { _, _, _, _ -> },
                    onConfirmPaymentExact = { _, _, _, _, _ -> },
                    settlementTargets = listOf(custTarget),
                    currency = "JOD",
                    minorUnits = 3
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Remaining: \u206645.51 USD\u2069").assertExists()
        assertEquals(0, composeTestRule.onAllNodesWithText("45.51 JOD", substring = true).fetchSemanticsNodes().size)

        composeTestRule.onNodeWithTag("target_checkbox_invoice_inv_usd_3dp").performClick()
        composeTestRule.onNodeWithTag("target_2dp_hint_invoice_inv_usd_3dp").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("target_allocation_input_invoice_inv_usd_3dp").performScrollTo().performTextInput("45.50")
        composeTestRule.onNodeWithTag("payment_amount_input").performScrollTo().performTextInput("45.50")
        composeTestRule.waitForIdle()
        assertEquals(0, composeTestRule.onAllNodesWithText("cannot auto-fill", substring = true).fetchSemanticsNodes().size)
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsEnabled()
    }

    @Test
    fun supplierSettlementTargets_useTargetCurrency_andShowInline2dpHintOnUnrepresentableAllocation() {
        val supTarget = SettlementTarget(
            targetType = "purchase",
            targetId = "pur_eur_3dp",
            targetNumber = "BILL-EUR-88",
            businessDate = "2026-11-28",
            dueDate = null,
            daysOverdue = 0,
            currency = "EUR",
            currencyMinorUnits = 3,
            originalAmountExact = BigDecimal("72.125"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("72.125")
        )
        val supplier = Supplier(id = "sup_1", nameAr = "مورد", nameEn = "Supplier", balanceOwedExact = BigDecimal("15.125"))

        composeTestRule.setContent {
            SmallTraderTheme {
                PaySupplierDialog(
                    supplier = supplier,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onPaySupplier = { _, _, _ -> Result.success(SupplierPayment("sp_1", "sup_1", "today", 1.0, "")) },
                    onPaySupplierExactWithResult = { _, amount, _, _, _, onComplete ->
                        onComplete(Result.success(SupplierPayment("sp_ack", supplier.id, "today", amount.toDouble(), "")))
                    },
                    settlementTargets = listOf(supTarget),
                    currency = "JOD",
                    minorUnits = 3
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Remaining: \u206672.13 EUR\u2069").assertExists()
        assertEquals(0, composeTestRule.onAllNodesWithText("72.13 JOD", substring = true).fetchSemanticsNodes().size)

        composeTestRule.onNodeWithTag("target_checkbox_purchase_pur_eur_3dp").performClick()
        composeTestRule.onNodeWithTag("target_2dp_hint_purchase_pur_eur_3dp").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("target_allocation_input_purchase_pur_eur_3dp").performScrollTo().performTextInput("72.12")
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").performScrollTo().performTextInput("72.12")
        composeTestRule.waitForIdle()
        assertEquals(0, composeTestRule.onAllNodesWithText("cannot auto-fill", substring = true).fetchSemanticsNodes().size)
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsEnabled()
    }

    @Test
    fun customerPicker_zeroCreditLimit_consistentlyShowsExceedsLimitBadge() {
        val repo = OfflineDemoTraderRepository(
            initialBusinessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3)
        )
        val vm = TraderViewModel(repo)
        vm.addCustomQuickItem("Picker Item", "10.00")
        val itemTemplate = vm.uiState.value.basket.first()

        val zeroLimitCust = Customer(
            id = "cust_zero_limit",
            nameAr = "زبون سقف صفر",
            nameEn = "Zero Limit Customer",
            phone = "0599000000",
            balanceExact = BigDecimal.ZERO,
            creditLimitExact = BigDecimal.ZERO,
            isDetailLoaded = true
        )
        val okLimitCust = Customer(
            id = "cust_ok_limit",
            nameAr = "زبون مسموح",
            nameEn = "OK Limit Customer",
            phone = "0599000001",
            balanceExact = BigDecimal.ZERO,
            creditLimitExact = BigDecimal("500.00"),
            isDetailLoaded = true
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPickerModalSheet(
                    customers = listOf(zeroLimitCust, okLimitCust),
                    selectedCustomerId = null,
                    basketNetTotalIls = 10.0,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onSelectCustomer = {},
                    state = TraderUiState(
                        language = AppLanguage.EN,
                        businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
                        basket = listOf(itemTemplate)
                    )
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Zero Limit Customer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exceeds limit").assertIsDisplayed()
    }

    @Test
    fun editCustomerDialog_usesActiveBusinessCurrency_insteadOfHardcodedShekel() {
        val cust = Customer(
            id = "cust_jod_edit",
            nameAr = "زبون التعديل",
            nameEn = "Editable JOD Customer",
            phone = "0791234567",
            balanceExact = BigDecimal("25.00"),
            creditLimitExact = BigDecimal("100.00"),
            isDetailLoaded = true
        )
        val uiState = TraderUiState(
            language = AppLanguage.EN,
            businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
            customers = listOf(cust),
            selectedCustomerForDetail = cust
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerDetailScreen(
                    state = uiState,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {},
                    onUpdateCustomerWithDetails = { _, _, _, _, _, _, _, _, onComplete -> onComplete(true) }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("edit_customer_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Credit Limit (JOD)", substring = true).assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("Credit Limit (₪)", substring = true).fetchSemanticsNodes().size)
    }
}
