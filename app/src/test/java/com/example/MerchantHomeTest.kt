package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.OfflineDemoTraderRepository
import com.example.data.SampleCatalog
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Meaningful Compose Semantics and Interaction Tests for Calm Merchant Home Screen.
 *
 * Verifies core Concept A architectural guarantees:
 * 1. Single Dominant Register Action: Dominant "New Sale" when empty vs. dominant "Resume Sale" when active.
 *    No duplicate hero_new_sale_button when active sale is in progress.
 * 2. Elimination of Stock Mutation: Low stock summary row strictly navigates to catalog without
 *    invoking onRestockProduct callback or mutating repository stock. Deprecated +10 restock controls do not exist.
 * 3. Role-Based Visibility & Financial Privacy: Authorized Purchase action ('شراء جديد') and Reports are
 *    displayed for Owner/Manager and completely excluded from Cashier semantics. Cashier sees no owner totals.
 *    Obsolete secondary expense chip expectation removed (accessible via shell).
 * 4. Honest Merchant Copy: No misleading receive-stock text ('توريد بضاعة') or persistent demo notice banners.
 * 5. Viewport Robustness: Uses performScrollTo() for lazy list offscreen nodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
class MerchantHomeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_emptyBasket_showsNewSalePrimaryAction_andNoResumeButton() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                HomeScreen(
                    state = state,
                    onStartNewSale = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenPrinter = { viewModel.setPrinterSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onReviewBasket = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onOpenInvoice = { invoice -> viewModel.openInvoiceReceipt(invoice) },
                    onViewAllInvoices = { viewModel.navigateTo(TraderScreen.INVOICES_HISTORY) },
                    onRestockProduct = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onAddProductToSale = { product -> viewModel.addProductToBasket(product, 1) },
                    onNavigateToCatalog = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onNavigateToCustomers = { viewModel.navigateTo(TraderScreen.CUSTOMERS) },
                    onNavigateToSuppliers = { viewModel.navigateTo(TraderScreen.SUPPLIERS) },
                    onNavigateToExpenses = { viewModel.navigateTo(TraderScreen.EXPENSES) },
                    onNavigateToReports = { viewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS) },
                    onNavigateToSettings = { viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Dominant New Sale button is displayed and enabled
        composeTestRule.onNodeWithTag("hero_new_sale_button").assertIsDisplayed().assertIsEnabled()

        // 2. Resume Sale button does NOT exist when basket is empty
        composeTestRule.onNodeWithTag("home_resume_review_button").assertDoesNotExist()

        // 3. Tactile 48dp secondary scan and custom item actions are accessible
        composeTestRule.onNodeWithTag("hero_scan_barcode_button").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("home_quick_custom_item_button").assertIsDisplayed().assertIsEnabled()

        // 4. Tapping New Sale button navigates directly to NEW_SALE screen
        composeTestRule.onNodeWithTag("hero_new_sale_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(TraderScreen.NEW_SALE, viewModel.uiState.value.currentScreen)
    }

    @Test
    fun homeScreen_activeBasket_showsResumeButtonAsDominantAction_andNavigatesToReview() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Seed basket with an item
        val sampleProduct = viewModel.uiState.value.products.first()
        viewModel.addProductToBasket(sampleProduct, 2)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                HomeScreen(
                    state = state,
                    onStartNewSale = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenPrinter = { viewModel.setPrinterSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onReviewBasket = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onOpenInvoice = { invoice -> viewModel.openInvoiceReceipt(invoice) },
                    onViewAllInvoices = { viewModel.navigateTo(TraderScreen.INVOICES_HISTORY) },
                    onRestockProduct = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onAddProductToSale = { product -> viewModel.addProductToBasket(product, 1) },
                    onNavigateToCatalog = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onNavigateToCustomers = { viewModel.navigateTo(TraderScreen.CUSTOMERS) },
                    onNavigateToSuppliers = { viewModel.navigateTo(TraderScreen.SUPPLIERS) },
                    onNavigateToExpenses = { viewModel.navigateTo(TraderScreen.EXPENSES) },
                    onNavigateToReports = { viewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS) },
                    onNavigateToSettings = { viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Dominant Resume button is displayed and enabled
        composeTestRule.onNodeWithTag("home_resume_review_button").assertIsDisplayed().assertIsEnabled()

        // 2. Active primary has NO competing hero_new_sale_button (no duplicate hero buttons)
        composeTestRule.onNodeWithTag("hero_new_sale_button").assertDoesNotExist()

        // 3. Tapping Resume button navigates directly to INVOICE_REVIEW
        composeTestRule.onNodeWithTag("home_resume_review_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(TraderScreen.INVOICE_REVIEW, viewModel.uiState.value.currentScreen)
    }

    @Test
    fun homeScreen_stockSummaryRow_navigatesToCatalog_andNeverMutatesRepositoryStock() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val lowStockProducts = viewModel.uiState.value.lowStockProducts
        assert(lowStockProducts.isNotEmpty()) { "Test prerequisite: Sample catalog must contain low-stock items" }

        // Snapshot initial stock counts to verify NO repository stock was mutated
        val initialStockMap = repo.getProducts().associate { it.id to it.stockCount }

        var restockProductCallbackInvoked = false

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                HomeScreen(
                    state = state,
                    onStartNewSale = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenPrinter = { viewModel.setPrinterSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onReviewBasket = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onOpenInvoice = { invoice -> viewModel.openInvoiceReceipt(invoice) },
                    onViewAllInvoices = { viewModel.navigateTo(TraderScreen.INVOICES_HISTORY) },
                    onRestockProduct = { restockProductCallbackInvoked = true },
                    onAddProductToSale = { product -> viewModel.addProductToBasket(product, 1) },
                    onNavigateToCatalog = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onNavigateToCustomers = { viewModel.navigateTo(TraderScreen.CUSTOMERS) },
                    onNavigateToSuppliers = { viewModel.navigateTo(TraderScreen.SUPPLIERS) },
                    onNavigateToExpenses = { viewModel.navigateTo(TraderScreen.EXPENSES) },
                    onNavigateToReports = { viewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS) },
                    onNavigateToSettings = { viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // 1. Verify deprecated +10 restock mutation control does NOT exist anywhere
        for (product in lowStockProducts.take(3)) {
            composeTestRule.onNodeWithTag("restock_${product.id}").assertDoesNotExist()
        }

        // 2. Low stock summary row exists, uses performScrollTo for lazy column visibility, and navigates to catalog
        composeTestRule.onNodeWithTag("home_screen_list")
            .performScrollToNode(hasTestTag("home_catalog_button"))
        composeTestRule.onNodeWithTag("home_catalog_button")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.waitForIdle()

        // 3. Assert navigation succeeded
        assertEquals(TraderScreen.PRODUCTS_CATALOG, viewModel.uiState.value.currentScreen)

        // 4. Assert onRestockProduct callback was NEVER invoked
        assertEquals(false, restockProductCallbackInvoked)

        // 5. Assert repository stock remained strictly unchanged
        val afterStockMap = repo.getProducts().associate { it.id to it.stockCount }
        assertEquals(initialStockMap, afterStockMap)
    }

    @Test
    fun homeScreen_roleGating_ownerVsCashier_purchaseAffordanceAndFinancialPrivacy() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Session 1: Owner (Abu Kareem)
        val ownerSession = SampleCatalog.demoStaff.first { it.role == UserRole.OWNER }
        viewModel.switchUserSession(ownerSession)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                HomeScreen(
                    state = state,
                    onStartNewSale = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenPrinter = { viewModel.setPrinterSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onReviewBasket = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onOpenInvoice = { invoice -> viewModel.openInvoiceReceipt(invoice) },
                    onViewAllInvoices = { viewModel.navigateTo(TraderScreen.INVOICES_HISTORY) },
                    onRestockProduct = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onAddProductToSale = { product -> viewModel.addProductToBasket(product, 1) },
                    onNavigateToCatalog = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onNavigateToCustomers = { viewModel.navigateTo(TraderScreen.CUSTOMERS) },
                    onNavigateToSuppliers = { viewModel.navigateTo(TraderScreen.SUPPLIERS) },
                    onNavigateToExpenses = { viewModel.navigateTo(TraderScreen.EXPENSES) },
                    onNavigateToReports = { viewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS) },
                    onNavigateToSettings = { viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) },
                    onOpenPurchase = { viewModel.navigateTo(TraderScreen.PURCHASES) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Owner View: Named Purchase button 'شراء جديد' is displayed
        composeTestRule.onNodeWithTag("home_screen_list")
            .performScrollToNode(hasTestTag("home_purchase_button"))
        composeTestRule.onNodeWithTag("home_purchase_button")
            .assertIsDisplayed()

        // Owner View: Reports navigation action is displayed
        composeTestRule.onNodeWithTag("home_screen_list")
            .performScrollToNode(hasTestTag("home_reports_button"))
        composeTestRule.onNodeWithTag("home_reports_button")
            .assertIsDisplayed()

        // Owner View: Obsolete secondary expense chip expectation removed (accessible via shell)
        composeTestRule.onNodeWithTag("home_expenses_button").assertDoesNotExist()

        // Switch to Session 2: Cashier (Tareq)
        val cashierSession = SampleCatalog.demoStaff.first { it.role == UserRole.CASHIER }
        viewModel.switchUserSession(cashierSession)
        composeTestRule.waitForIdle()

        // Cashier View: Named Purchase button MUST NOT exist
        composeTestRule.onNodeWithTag("home_purchase_button").assertDoesNotExist()

        // Cashier View: Owner Reports MUST NOT exist (no owner figures)
        composeTestRule.onNodeWithTag("home_reports_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("home_expenses_button").assertDoesNotExist()

        // Cashier View: Shift invoices history link remains accessible
        composeTestRule.onNodeWithTag("view_all_invoices_button")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun homeScreen_assertsNoMisleadingReceiveStockOrDemoBanners() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                HomeScreen(
                    state = state,
                    onStartNewSale = { viewModel.navigateTo(TraderScreen.NEW_SALE) },
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenPrinter = { viewModel.setPrinterSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onReviewBasket = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) },
                    onOpenInvoice = { invoice -> viewModel.openInvoiceReceipt(invoice) },
                    onViewAllInvoices = { viewModel.navigateTo(TraderScreen.INVOICES_HISTORY) },
                    onRestockProduct = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onAddProductToSale = { product -> viewModel.addProductToBasket(product, 1) },
                    onNavigateToCatalog = { viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG) },
                    onNavigateToCustomers = { viewModel.navigateTo(TraderScreen.CUSTOMERS) },
                    onNavigateToSuppliers = { viewModel.navigateTo(TraderScreen.SUPPLIERS) },
                    onNavigateToExpenses = { viewModel.navigateTo(TraderScreen.EXPENSES) },
                    onNavigateToReports = { viewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS) },
                    onNavigateToSettings = { viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS) },
                    onOpenPurchase = { viewModel.navigateTo(TraderScreen.PURCHASES) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Assert no false conflation of purchase bill with physical goods receipt
        composeTestRule.onNodeWithText("توريد بضاعة", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("receive stock", substring = true, ignoreCase = true).assertDoesNotExist()

        // Assert no persistent demo banners on normal merchant workflow
        composeTestRule.onNodeWithText("نسخة تجريبية — البيانات على الجهاز فقط", substring = true).assertDoesNotExist()
    }
}
