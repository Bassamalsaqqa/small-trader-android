package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import com.example.data.OfflineDemoTraderRepository
import com.example.model.TraderScreen
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.InvoiceReviewScreen
import com.example.ui.screens.NewSaleScreen
import com.example.ui.screens.ReceiptScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Targeted UI & Financial Safety Test Suite for Stage 3 Checkout:
 * 1. Exact Cash Tender Prefill: Fills tender amount, but NEVER posts sale or alters cash drawer.
 * 2. Credit Mode Customer Bounds: Credit button is disabled without a named customer and blocked over limit.
 * 3. Optional Sale Options Callback: Verifies onOpenSaleOptions is exposed and reachable from NewSaleScreen.
 * 4. Quotation Preview: Verifies non-posting commercial quotation dialog is accessible with copy affordance.
 * 5. Double-Submit Guards: Buttons are disabled while submission is in progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MerchantCheckoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun exactCashTender_onlyFillsInput_neverPostsSaleOrAltersDrawer() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" } // 38.00 ILS
        viewModel.addProductToBasket(product, 1)

        val initialDrawer = viewModel.uiState.value.drawerTotalCashIls
        val initialInvoices = viewModel.uiState.value.invoices.size

        var saleCompleted = false

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = state.language.isRtl) {
                CashPaymentScreen(
                    state = state,
                    onSetExactAmount = viewModel::setCashExactAmount,
                    onSelectDenomination = viewModel::setCashQuickDenomination,
                    onUpdateCashText = viewModel::updateCashInputText,
                    onKeypadPress = viewModel::onCashKeypadPress,
                    onAssignCustomer = viewModel::assignCustomerToSale,
                    onDismissCheckoutError = viewModel::dismissCheckoutError,
                    onEditInvoice = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onCompleteSale = {
                        saleCompleted = true
                        viewModel.completeCashSale(asCreditDebt = false)
                    },
                    onCompleteCreditSale = {
                        viewModel.completeCashSale(asCreditDebt = true)
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify Amount Due displays 38.00 ₪
        composeTestRule.onNodeWithTag("payment_total_due_text")
            .assertIsDisplayed()
            .assertTextContains("38.00 ₪", substring = true)

        // Clear cash input to simulate fresh entry
        viewModel.updateCashInputText("")
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isCashSufficient)

        // Tap Exact Button ("بالضبط")
        composeTestRule.onNodeWithTag("cash_exact_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // VERIFY: Tender input is pre-filled, but sale is NOT completed
        assertEquals("38.00", viewModel.uiState.value.cashInputText)
        assertTrue(viewModel.uiState.value.isCashSufficient)
        assertTrue(viewModel.uiState.value.isExactCashTendered)
        assertFalse(saleCompleted)

        // VERIFY FINANCIAL REPOSITORY INVARIANTS: zero postings, drawer untouched, basket intact
        assertEquals(initialInvoices, viewModel.uiState.value.invoices.size)
        assertEquals(initialDrawer, viewModel.uiState.value.drawerTotalCashIls, 0.001)
        assertEquals(1, viewModel.uiState.value.basket.size)

        // Now confirm sale deliberately through primary button
        composeTestRule.onNodeWithTag("complete_sale_button").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()

        assertTrue(saleCompleted)
        assertEquals(initialInvoices + 1, viewModel.uiState.value.invoices.size)
        assertEquals(initialDrawer + 38.00, viewModel.uiState.value.drawerTotalCashIls, 0.001)
        assertNotNull(viewModel.uiState.value.activeReceipt)
    }

    @Test
    fun creditSale_requiresAssignedCustomer_andBlockedOverLimit() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.addCustomQuickItem("طلبية بضاعة", "750.00")

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = state.language.isRtl) {
                CashPaymentScreen(
                    state = state,
                    onSetExactAmount = viewModel::setCashExactAmount,
                    onSelectDenomination = viewModel::setCashQuickDenomination,
                    onUpdateCashText = viewModel::updateCashInputText,
                    onKeypadPress = viewModel::onCashKeypadPress,
                    onAssignCustomer = viewModel::assignCustomerToSale,
                    onDismissCheckoutError = viewModel::dismissCheckoutError,
                    onEditInvoice = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onCompleteSale = { viewModel.completeCashSale(asCreditDebt = false) },
                    onCompleteCreditSale = { viewModel.completeCashSale(asCreditDebt = true) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Switch to Credit Mode tab
        composeTestRule.onNodeWithTag("payment_mode_credit_tab").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // With NO customer linked: Credit confirmation button MUST be disabled
        composeTestRule.onNodeWithTag("complete_credit_sale_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // 2. Assign Um Samer (debt limit 800.00, balance 90.00 -> 90 + 750 = 840 > 800 -> 40 excess)
        val umSamer = viewModel.uiState.value.customers.first { it.id == "cust_02" }
        viewModel.assignCustomerToSale(umSamer)
        composeTestRule.waitForIdle()

        assertTrue(viewModel.uiState.value.wouldCreditExceedCustomerLimit)
        composeTestRule.onNodeWithTag("payment_credit_limit_warning").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("complete_credit_sale_button").assertIsNotEnabled()

        // 3. Assign Abu Tareq (debt limit 1500.00, balance 245.00 -> 245 + 750 = 995 <= 1500)
        val abuTareq = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        viewModel.assignCustomerToSale(abuTareq)
        composeTestRule.waitForIdle()

        assertFalse(viewModel.uiState.value.wouldCreditExceedCustomerLimit)
        composeTestRule.onNodeWithTag("complete_credit_sale_button").assertIsEnabled()

        // 4. Confirm Credit Sale -> 0.00 drawer cash impact, debt added to customer
        val drawerBeforeCredit = viewModel.uiState.value.drawerTotalCashIls
        composeTestRule.onNodeWithTag("complete_credit_sale_button").performClick()
        composeTestRule.waitForIdle()

        val creditReceipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(750.00, creditReceipt.netTotalIls, 0.001)
        assertEquals(0.0, creditReceipt.cashReceivedIls, 0.001)
        assertEquals(750.00, creditReceipt.remainingDebitIls, 0.001)
        assertEquals(drawerBeforeCredit, viewModel.uiState.value.drawerTotalCashIls, 0.001)
    }

    @Test
    fun newSaleScreen_exposesOptionalSaleOptionsCallback() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.stockCount > 0 }
        viewModel.addProductToBasket(product, 2)

        var paymentProceeded = false
        var optionsOpened = false

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = state.language.isRtl) {
                NewSaleScreen(
                    state = state,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onSelectCategory = viewModel::selectCategory,
                    onAddProduct = { p -> viewModel.addProductToBasket(p, 1) },
                    onDecrementProduct = viewModel::decrementProductInBasket,
                    onRemoveProduct = viewModel::removeProductFromBasket,
                    onClearBasket = viewModel::clearBasket,
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onProceedToInvoiceReview = { paymentProceeded = true },
                    onOpenSaleOptions = { optionsOpened = true }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Verify options button ("خيارات") is displayed and clicking it invokes onOpenSaleOptions
        composeTestRule.onNodeWithTag("open_sale_options_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue("onOpenSaleOptions must be called when tapping options button", optionsOpened)
        assertFalse(paymentProceeded)

        // Verify primary continue payment button invokes onProceedToInvoiceReview
        composeTestRule.onNodeWithTag("review_invoice_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue("onProceedToInvoiceReview must be called when tapping continue payment", paymentProceeded)
    }

    @Test
    fun invoiceReviewScreen_safeQuotation_doesNotPostOrAlterState() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(product, 2)

        val initialInvoices = viewModel.uiState.value.invoices.size
        val initialDrawer = viewModel.uiState.value.drawerTotalCashIls

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = state.language.isRtl) {
                InvoiceReviewScreen(
                    state = state,
                    onAddMoreItems = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onIncrementProduct = { p -> viewModel.addProductToBasket(p, 1) },
                    onDecrementProduct = viewModel::decrementProductInBasket,
                    onRemoveProduct = viewModel::removeProductFromBasket,
                    onSelectDiscountPreset = viewModel::selectDiscountPreset,
                    onUpdateCustomDiscount = viewModel::updateCustomDiscountText,
                    onUpdateCustomerNote = viewModel::updateCustomerNote,
                    onAssignCustomer = viewModel::assignCustomerToSale,
                    onProceedToCashPayment = { viewModel.navigateTo(TraderScreen.CASH_PAYMENT) },
                    onSaveDraft = viewModel::saveCurrentBasketAsDraft,
                    onResumeDraft = viewModel::resumeSaleDraft,
                    onDeleteDraft = viewModel::deleteSaleDraft,
                    onFormatQuotation = viewModel::formatQuotationShareText
                )
            }
        }

        composeTestRule.waitForIdle()

        // Open Quotation Preview
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("open_quotation_preview_button"))
        composeTestRule.onNodeWithTag("open_quotation_preview_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        // Verify Quotation dialog and non-posting preview
        composeTestRule.onNodeWithTag("quotation_preview_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("copy_quotation_button").assertIsDisplayed()

        // Verify no financial posting occurred
        assertEquals(initialInvoices, viewModel.uiState.value.invoices.size)
        assertEquals(initialDrawer, viewModel.uiState.value.drawerTotalCashIls, 0.001)
    }

    @Test
    fun invoiceReviewScreen_progressiveDisclosure_expandsCustomerAndDiscountSections() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(product, 1)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = state.language.isRtl) {
                InvoiceReviewScreen(
                    state = state,
                    onAddMoreItems = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onIncrementProduct = { p -> viewModel.addProductToBasket(p, 1) },
                    onDecrementProduct = viewModel::decrementProductInBasket,
                    onRemoveProduct = viewModel::removeProductFromBasket,
                    onSelectDiscountPreset = viewModel::selectDiscountPreset,
                    onUpdateCustomDiscount = viewModel::updateCustomDiscountText,
                    onUpdateCustomerNote = viewModel::updateCustomerNote,
                    onAssignCustomer = viewModel::assignCustomerToSale,
                    onProceedToCashPayment = { viewModel.navigateTo(TraderScreen.CASH_PAYMENT) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Verify basket line is displayed directly in the ledger
        composeTestRule.onNodeWithTag("invoice_line_${product.id}").assertIsDisplayed()

        // 2. Expand Customer Section
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("toggle_customer_section_button"))
        composeTestRule.onNodeWithTag("toggle_customer_section_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        // Customer note and select customer buttons become visible
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("select_customer_button"))
        composeTestRule.onNodeWithTag("select_customer_button").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("customer_note_input"))
        composeTestRule.onNodeWithTag("customer_note_input").assertIsDisplayed()

        // 3. Expand Discount Section
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("toggle_discount_section_button"))
        composeTestRule.onNodeWithTag("toggle_discount_section_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        // Discount preset chips become visible
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("discount_chip_PERCENT_5"))
        composeTestRule.onNodeWithTag("discount_chip_PERCENT_5").assertIsDisplayed()
    }

    @Test
    fun receiptScreen_compactResult_revealsDetailedThermalPaperOnToggle() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        viewModel.addProductToBasket(product, 1)
        viewModel.setCashExactAmount()
        viewModel.completeCashSale(asCreditDebt = false)

        var newSaleStarted = false

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = state.isDarkTheme, isRtl = state.language.isRtl) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = viewModel::simulateThermalPrint,
                    onOpenPrinterSettings = {},
                    onStartNewSale = { newSaleStarted = true },
                    onBackToHome = { viewModel.navigateTo(TraderScreen.HOME) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Verify quiet result header is displayed with completion banner
        composeTestRule.onNodeWithTag("receipt_completion_banner").assertIsDisplayed()

        // 2. Verify primary next sale action is displayed and clickable
        composeTestRule.onNodeWithTag("receipt_new_sale_button")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue("onStartNewSale must be triggered when tapping new sale", newSaleStarted)

        // 3. Verify toggle for invoice details expands thermal receipt paper
        composeTestRule.onNodeWithTag("toggle_invoice_details_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("thermal_receipt_paper").performScrollTo().assertIsDisplayed()
    }
}
