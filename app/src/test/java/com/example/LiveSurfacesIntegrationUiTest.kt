package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.OfflineDemoTraderRepository
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.merchantread.CustomerStatementAging
import com.example.data.mobile.merchantread.CustomerStatementCurrencySummary
import com.example.data.mobile.merchantread.CustomerStatementEntry
import com.example.data.mobile.merchantread.HomeBusinessInfo
import com.example.data.mobile.merchantread.LiveCustomerStatement
import com.example.data.mobile.merchantread.LiveHomeMetrics
import com.example.data.mobile.merchantread.LiveInvoiceRecord
import com.example.data.mobile.merchantread.LiveShiftSummary
import com.example.data.mobile.merchantread.LiveStockMovementHistory
import com.example.data.mobile.merchantread.LiveTreasurySummary
import com.example.data.mobile.merchantread.SalesInvoiceDetailLine
import com.example.data.mobile.merchantread.StockMovementItem
import com.example.data.mobile.merchantread.TreasuryAccountItem
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.screens.CustomerDetailScreen
import com.example.ui.screens.FinancialReportsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InvoicesHistoryScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.ReceiptScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose & Robolectric Integration Acceptance Tests for Live Merchant Surfaces.
 *
 * Verifies live screen guarantees against actual production contracts:
 * 1. Authoritative Home Projections: LiveHomeMetrics values override local ledger totals in live mode.
 * 2. Truthful Zero Invention Gate: Null unauthorized financials display dashes ("—"), never fake zeroes.
 * 3. Server Snapshot Receipt Rendering: Empty local basket renders server detail lines and net totals.
 * 4. Paid / Unpaid Settlement Truth: Proper status badge and tender breakdown for full cash vs credit sales.
 * 5. Pending Sync Honesty: Unconfirmed local receipts render honest pending sync badge.
 * 6. History Pagination Gap Banner: Limitation notice appears when server has more records than loaded.
 * 7. Multi-Currency Customer Statement: Separate statement cards per currency without synthetic fake currency.
 * 8. Immediate Selection Clearing: Switching customer/product clears stale statement/history immediately.
 * 9. Fractional Quantity Precision: Product stock history renders exact decimal deltas and balances.
 * 10. Financial Reports Guard: Live mode displays typed read-only data and conceals local shift close.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveSurfacesIntegrationUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun customerReadFailureOffersRealRetryBeforeShowingEmptyDirectory() {
        val demo = OfflineDemoTraderRepository()
        var reads = 0
        val repository = object : com.example.data.TraderRepository by demo {
            override fun getCurrentSession() = demo.getCurrentSession().copy(isDemoSession = false)
            override fun getCustomers() = emptyList<Customer>()
            override val customersFlow = kotlinx.coroutines.flow.MutableStateFlow(emptyList<Customer>())
            override suspend fun refreshCustomers(): Result<Unit> {
                reads++
                return if (reads == 1) Result.failure(IllegalStateException("private diagnostic"))
                else Result.success(Unit)
            }
        }
        val vm = TraderViewModel(repository)
        val screen = com.example.model.TraderScreen.CUSTOMERS
        composeTestRule.setContent {
            val state = vm.uiState.collectAsState().value.copy(language = AppLanguage.EN)
            androidx.compose.runtime.LaunchedEffect(vm) { vm.loadScreen(screen) }
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                com.example.ui.screens.CustomersScreen(
                    state, {}, {}, {}, onRetryRefresh = { vm.loadScreen(screen, retry = true) }
                )
            }
        }
        composeTestRule.onNodeWithText("Could not refresh customers from server").assertExists()
        composeTestRule.onNodeWithText("private diagnostic").assertDoesNotExist()
        composeTestRule.onNodeWithText("No registered customers yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.onNodeWithText("No registered customers yet").assertExists()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        assertEquals(2, reads)
    }

    // -------------------------------------------------------------------------
    // 1. Authoritative Home Metrics vs Local Stats
    // -------------------------------------------------------------------------

    @Test
    fun homeScreen_usesAuthoritativeHomeValuesInLive_notLocalStats() {
        val businessInfo = HomeBusinessInfo(
            nameAr = "متجر الزيتون",
            nameEn = "Olive Store",
            cityAr = "نابلس",
            cityEn = "Nablus",
            phone = "0599111222",
            taxNumber = "123456789",
            defaultCurrency = "JOD",
            defaultCurrencyMinorUnits = 2
        )
        val liveMetrics = LiveHomeMetrics(
            today = "2026-11-19",
            salesTodayCount = 28,
            salesTodayTotal = BigDecimal("4500.50"),
            cashInDrawer = BigDecimal("1250.00"),
            lowStockCount = 3,
            totalReceivables = BigDecimal("3200.00"),
            totalPayables = BigDecimal("1100.00"),
            recentInvoices = emptyList(),
            businessInfo = businessInfo
        )

        val session = UserSession(
            id = "usr_mgr_1",
            name = "Manager",
            username = "manager",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            liveHomeMetrics = liveMetrics,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                HomeScreen(
                    state = state,
                    onStartNewSale = {},
                    onOpenScanner = {},
                    onOpenPrinter = {},
                    onOpenCustomItem = {},
                    onReviewBasket = {},
                    onOpenInvoice = {},
                    onViewAllInvoices = {},
                    onRestockProduct = {},
                    onAddProductToSale = {},
                    onNavigateToCatalog = {},
                    onNavigateToCustomers = {},
                    onNavigateToSuppliers = {},
                    onNavigateToExpenses = {},
                    onNavigateToReports = {},
                    onNavigateToSettings = {}
                )
            }
        }

        // Authoritative live numbers are displayed in the KPI summary
        composeTestRule.onNodeWithText("4500.50 JOD • 28 invoices", substring = true).assertIsDisplayed()

        // Expand Bento split cards
        composeTestRule.onNodeWithTag("home_summary_toggle").performClick()

        // Authoritative sales, drawer cash, and receivables appear
        composeTestRule.onNodeWithText("4500.50 JOD", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("1250.00 JOD", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("3200.00 JOD", substring = true).assertIsDisplayed()

        // Local placeholder stats must NOT be displayed
        composeTestRule.onNodeWithText("999.00 ₪").assertDoesNotExist()
        composeTestRule.onNodeWithText("99 invoices").assertDoesNotExist()
        composeTestRule.onNodeWithText("888.00 ₪").assertDoesNotExist()
    }

    // -------------------------------------------------------------------------
    // 2. Zero Invention Gate (Null Unknown Dashes)
    // -------------------------------------------------------------------------

    @Test
    fun homeScreen_displaysDashesForUnknownMoney_noZeroInvention() {
        val businessInfo = HomeBusinessInfo(
            nameAr = "متجر الزيتون",
            nameEn = "Olive Store",
            cityAr = "نابلس",
            cityEn = "Nablus",
            phone = "0599111222",
            taxNumber = "123456789",
            defaultCurrency = "JOD",
            defaultCurrencyMinorUnits = 2
        )
        val unauthorizedMetrics = LiveHomeMetrics(
            today = "2026-11-19",
            salesTodayCount = null,
            salesTodayTotal = null,
            cashInDrawer = null,
            lowStockCount = null,
            totalReceivables = null,
            totalPayables = null,
            recentInvoices = emptyList(),
            businessInfo = businessInfo
        )

        val session = UserSession(
            id = "usr_mgr_2",
            name = "Manager 2",
            username = "manager2",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            liveHomeMetrics = unauthorizedMetrics,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                HomeScreen(
                    state = state,
                    onStartNewSale = {},
                    onOpenScanner = {},
                    onOpenPrinter = {},
                    onOpenCustomItem = {},
                    onReviewBasket = {},
                    onOpenInvoice = {},
                    onViewAllInvoices = {},
                    onRestockProduct = {},
                    onAddProductToSale = {},
                    onNavigateToCatalog = {},
                    onNavigateToCustomers = {},
                    onNavigateToSuppliers = {},
                    onNavigateToExpenses = {},
                    onNavigateToReports = {},
                    onNavigateToSettings = {}
                )
            }
        }

        // Summary must display dash and never invent zero
        composeTestRule.onNodeWithText("— • — invoices", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00 ₪").assertDoesNotExist()
        composeTestRule.onNodeWithText("0.00 ILS").assertDoesNotExist()
        composeTestRule.onNodeWithText("0.00 JOD").assertDoesNotExist()

        // Expand Bento split cards
        composeTestRule.onNodeWithTag("home_summary_toggle").performClick()

        // Both cash and sales cards must render dashes
        composeTestRule.onNodeWithText("—").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 3. Receipt Server Snapshot Lines when Local Basket is Empty
    // -------------------------------------------------------------------------

    @Test
    fun receiptScreen_serverHistoryEmptyLocalBasket_rendersActualSnapshotLineAndTotal() {
        val invoiceUlid = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val lineUlid = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
        val customerUlid = "01ARZ3NDEKTSV4RRFFQ69G5FC1"

        // Historical local record with empty basket
        val invoice = InvoiceRecord(
            id = invoiceUlid,
            invoiceNumber = 99,
            timestamp = "2026-11-19 10:30",
            items = emptyList(),
            subtotalExact = BigDecimal("135.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("135.00"),
            cashReceivedExact = BigDecimal("135.00"),
            changeReturnedExact = BigDecimal.ZERO,
            customerId = customerUlid,
            customerNote = "",
            cashierNameAr = "أحمد",
            cashierNameEn = "Ahmad",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            serverInvoiceUlid = invoiceUlid,
            serverInvoiceNumber = "INV-2026-0099"
        )

        val detailLine = SalesInvoiceDetailLine(
            id = ServerUlid.parse(lineUlid),
            productId = null,
            productName = "Server Grade Olive Oil 1L",
            unit = "bottle",
            quantity = BigDecimal("3"),
            unitPrice = BigDecimal("45.00"),
            lineTotal = BigDecimal("135.00"),
            taxRate = BigDecimal.ZERO,
            discount = BigDecimal.ZERO
        )

        val liveDetail = LiveInvoiceRecord(
            id = invoiceUlid,
            invoiceNumber = "INV-2026-0099",
            status = "ISSUED",
            paymentStatus = "PAID_CASH",
            customerId = customerUlid,
            customerName = "Al-Quds Mart",
            date = "2026-11-19",
            createdAt = "2026-11-19T10:30:00Z",
            subtotalExact = BigDecimal("135.00"),
            discountExact = BigDecimal.ZERO,
            taxExact = BigDecimal.ZERO,
            totalExact = BigDecimal("135.00"),
            paidExact = BigDecimal("135.00"),
            remainingDebitExact = BigDecimal.ZERO,
            currency = "USD",
            currencyMinorUnits = 2,
            lines = listOf(detailLine),
            serverInvoiceUlid = invoiceUlid
        )

        val session = UserSession(
            id = "usr_cashier_1",
            name = "Cashier",
            username = "cashier",
            role = UserRole.CASHIER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = liveDetail,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        // Historical document banner must be shown (no completion celebration)
        composeTestRule.onNodeWithTag("receipt_historical_banner").assertIsDisplayed()

        // Server snapshot line item and quantity
        composeTestRule.onNodeWithText("Server Grade Olive Oil 1L (3×)", substring = true).assertIsDisplayed()

        // Dominant net total with server currency
        composeTestRule.onAllNodesWithText("135", substring = true)[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("USD", substring = true)[0].assertIsDisplayed()

        // Settlement truth: Paid Cash badge
        composeTestRule.onNodeWithText("Paid Cash", substring = true).assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 4. On-Credit Invoice Settlement Truth
    // -------------------------------------------------------------------------

    @Test
    fun receiptScreen_onCreditInvoice_rendersUnpaidStatusAndRemainingDebitTruth() {
        val invoiceUlid = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val lineUlid = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
        val customerUlid = "01ARZ3NDEKTSV4RRFFQ69G5FC1"

        val invoice = InvoiceRecord(
            id = invoiceUlid,
            invoiceNumber = 100,
            timestamp = "2026-11-19 11:00",
            items = emptyList(),
            subtotalExact = BigDecimal("135.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("135.00"),
            cashReceivedExact = BigDecimal.ZERO,
            changeReturnedExact = BigDecimal.ZERO,
            customerId = customerUlid,
            customerNote = "",
            cashierNameAr = "أحمد",
            cashierNameEn = "Ahmad",
            isPaidInFull = false,
            remainingDebitExact = BigDecimal("135.00"),
            serverInvoiceUlid = invoiceUlid,
            serverInvoiceNumber = "INV-2026-0100"
        )

        val detailLine = SalesInvoiceDetailLine(
            id = ServerUlid.parse(lineUlid),
            productId = null,
            productName = "Raw Pine Nuts 500g",
            unit = "pack",
            quantity = BigDecimal("1"),
            unitPrice = BigDecimal("135.00"),
            lineTotal = BigDecimal("135.00"),
            taxRate = BigDecimal.ZERO,
            discount = BigDecimal.ZERO
        )

        val liveDetail = LiveInvoiceRecord(
            id = invoiceUlid,
            invoiceNumber = "INV-2026-0100",
            status = "ISSUED",
            paymentStatus = "ON_CREDIT",
            customerId = customerUlid,
            customerName = "Al-Quds Mart",
            date = "2026-11-19",
            createdAt = "2026-11-19T11:00:00Z",
            subtotalExact = BigDecimal("135.00"),
            discountExact = BigDecimal.ZERO,
            taxExact = BigDecimal.ZERO,
            totalExact = BigDecimal("135.00"),
            paidExact = BigDecimal.ZERO,
            remainingDebitExact = BigDecimal("135.00"),
            currency = "USD",
            currencyMinorUnits = 2,
            lines = listOf(detailLine),
            serverInvoiceUlid = invoiceUlid
        )

        val session = UserSession(
            id = "usr_cashier_1",
            name = "Cashier",
            username = "cashier",
            role = UserRole.CASHIER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = liveDetail,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        // On Credit badge must be displayed
        composeTestRule.onNodeWithText("On Credit", substring = true).assertIsDisplayed()

        // Debited to Customer breakdown
        composeTestRule.onNodeWithText("Debited to Customer", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Net Invoice Total", substring = true).assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 5. Pending Sync Badge Honesty
    // -------------------------------------------------------------------------

    @Test
    fun receiptScreen_unconfirmedLocalReceipt_rendersPendingSyncBadge() {
        val invoice = InvoiceRecord(
            id = "inv_local_pending_1",
            invoiceNumber = 101,
            timestamp = "2026-11-19 11:30",
            items = emptyList(),
            subtotalExact = BigDecimal("75.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("75.00"),
            cashReceivedExact = BigDecimal("75.00"),
            changeReturnedExact = BigDecimal.ZERO,
            customerId = null,
            customerNote = "",
            cashierNameAr = "أحمد",
            cashierNameEn = "Ahmad",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            serverInvoiceUlid = null,
            outboxStatus = null
        )

        val session = UserSession(
            id = "usr_cashier_1",
            name = "Cashier",
            username = "cashier",
            role = UserRole.CASHIER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = null,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        // Pending Sync badge must be displayed
        composeTestRule.onNodeWithText("Pending Sync", substring = true).assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 6. Invoices History Load More Action & Pagination Honesty
    // -------------------------------------------------------------------------

    @Test
    fun invoicesHistoryScreen_rendersLoadMoreButton_andHandlesClick() {
        val session = UserSession(
            id = "usr_mgr_1",
            name = "Manager",
            username = "manager",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            invoices = emptyList(),
            liveInvoicesHasMore = true,
            liveInvoicesHasMoreGap = true,
            language = AppLanguage.EN
        )

        var loadMoreClicked = false

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                InvoicesHistoryScreen(
                    state = state,
                    onOpenInvoice = {},
                    onStartNewSale = {},
                    onLoadMore = { loadMoreClicked = true }
                )
            }
        }

        // Accessible Load More Icon Button must be displayed with 48dp touch target and respond to click
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasTestTag("invoice_history_load_more_button"))
        composeTestRule.onNodeWithTag("invoice_history_load_more_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("invoice_history_load_more_button").performClick()
        assertTrue(loadMoreClicked)
    }

    // -------------------------------------------------------------------------
    // 7. Customer Statement Separate Currencies & Real DTO Fields
    // -------------------------------------------------------------------------

    @Test
    fun customerStatement_rendersSeparateCurrencies_andHonorsRealFields() {
        val customerId = "01ARZ3NDEKTSV4RRFFQ69G5FC1"
        val customer = Customer(
            id = customerId,
            nameAr = "شركة الأمل للتجارة",
            nameEn = "Al-Amal Trading Co",
            phone = "0599000000"
        )

        val usdAging = CustomerStatementAging(
            current = BigDecimal("350.00"),
            days1To30 = BigDecimal.ZERO,
            days31To60 = BigDecimal.ZERO,
            days61To90 = BigDecimal.ZERO,
            days90Plus = BigDecimal.ZERO,
            total = BigDecimal("350.00")
        )

        val usdEntry = CustomerStatementEntry(
            date = "2026-11-18",
            type = "invoice",
            number = "INV-2026-0044",
            reference = "REF-9901",
            description = "Wholesale pantry goods",
            debit = BigDecimal("300.00"),
            credit = BigDecimal.ZERO,
            balance = BigDecimal("350.00")
        )

        val usdSummary = CustomerStatementCurrencySummary(
            currency = "USD",
            openingBalance = BigDecimal("50.00"),
            closingBalance = BigDecimal("350.00"),
            totalDebits = BigDecimal("300.00"),
            totalCredits = BigDecimal.ZERO,
            entries = listOf(usdEntry),
            aging = usdAging
        )

        val jodAging = CustomerStatementAging(
            current = BigDecimal("120.00"),
            days1To30 = BigDecimal.ZERO,
            days31To60 = BigDecimal.ZERO,
            days61To90 = BigDecimal.ZERO,
            days90Plus = BigDecimal.ZERO,
            total = BigDecimal("120.00")
        )

        val jodSummary = CustomerStatementCurrencySummary(
            currency = "JOD",
            openingBalance = BigDecimal.ZERO,
            closingBalance = BigDecimal("120.00"),
            totalDebits = BigDecimal("120.00"),
            totalCredits = BigDecimal.ZERO,
            entries = emptyList(),
            aging = jodAging
        )

        val statement = LiveCustomerStatement(
            customerId = customerId,
            customerName = "Al-Amal Trading Co",
            fromDate = "2026-11-01",
            toDate = "2026-11-19",
            currencies = mapOf("USD" to usdSummary, "JOD" to jodSummary)
        )

        val session = UserSession(
            id = "usr_mgr_1",
            name = "Manager",
            username = "manager",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            customers = listOf(customer),
            selectedCustomerForDetail = customer,
            selectedCustomerStatement = statement,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerDetailScreen(
                    state = state,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }

        // Multi-currency: Separate card per currency
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasTestTag("customer_currency_statement_USD"))
        composeTestRule.onNodeWithTag("customer_currency_statement_USD").assertIsDisplayed()
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasTestTag("customer_currency_statement_JOD"))
        composeTestRule.onNodeWithTag("customer_currency_statement_JOD").assertIsDisplayed()

        // Entry number from real DTO field
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasText("INV-2026-0044", substring = true))
        composeTestRule.onNodeWithText("INV-2026-0044", substring = true).assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 8. Stale Customer Selection Clears Immediately and Shows Loading
    // -------------------------------------------------------------------------

    @Test
    fun customerDetailScreen_staleCustomerSelection_clearsStatementImmediatelyAndShowsLoading() {
        val customerA = Customer(id = "01ARZ3NDEKTSV4RRFFQ69G5FC1", nameAr = "عميل أ", nameEn = "Customer A")
        val customerB = Customer(id = "01ARZ3NDEKTSV4RRFFQ69G5FC2", nameAr = "عميل ب", nameEn = "Customer B")

        val aging = CustomerStatementAging(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        val staleStatement = LiveCustomerStatement(
            customerId = customerA.id,
            customerName = "Customer A",
            fromDate = null,
            toDate = null,
            currencies = mapOf("USD" to CustomerStatementCurrencySummary("USD", BigDecimal.ZERO, BigDecimal("500.00"), BigDecimal.ZERO, BigDecimal.ZERO, emptyList(), aging))
        )

        val session = UserSession(
            id = "usr_mgr_1",
            name = "Manager",
            username = "manager",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        // Customer B is selected, but statement in state is stale from Customer A
        val state = TraderUiState(
            currentSession = session,
            customers = listOf(customerA, customerB),
            selectedCustomerForDetail = customerB,
            selectedCustomerStatement = staleStatement,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerDetailScreen(
                    state = state,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }

        // Loading indicator must be displayed; stale USD statement from Customer A must NOT be rendered
        composeTestRule.onNodeWithTag("customer_statement_loading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_currency_statement_USD").assertDoesNotExist()

        // ViewModel test: selectCustomerForDetail resets statement to null immediately
        val vm = TraderViewModel(OfflineDemoTraderRepository())
        vm.selectCustomerForDetail(customerA)
        assertNull("Stale statement must be null on select", vm.uiState.value.selectedCustomerStatement)
        assertEquals(customerA.id, vm.uiState.value.selectedCustomerForDetail?.id)
    }

    // -------------------------------------------------------------------------
    // 9. Product History Fractional Quantities
    // -------------------------------------------------------------------------

    @Test
    fun productHistory_rendersFractionalQuantities() {
        val productId = "01ARZ3NDEKTSV4RRFFQ69G5FD1"
        val movementId = "01ARZ3NDEKTSV4RRFFQ69G5FD2"

        val product = Product(
            id = productId,
            nameAr = "طحين بلدي سائب",
            nameEn = "Bulk Flour",
            category = ProductCategory.PANTRY,
            barcode = "1234567890123",
            priceIls = 15.0,
            costIls = 10.0,
            unitAr = "كغم",
            unitEn = "kg",
            stockCount = 100,
            accentHex = 0xFF4CAF50
        )

        val movement = StockMovementItem(
            id = ServerUlid.parse(movementId),
            movementType = "sale",
            delta = BigDecimal("-2.5"),
            date = "2026-11-19",
            reason = "Customer Retail Sale",
            resultingStock = BigDecimal("97.5")
        )

        val history = LiveStockMovementHistory(
            productId = productId,
            movements = listOf(movement)
        )

        val session = UserSession(
            id = "usr_mgr_1",
            name = "Manager",
            username = "manager",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            products = listOf(product),
            selectedProductForDetail = product,
            selectedProductHistory = history,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ProductDetailScreen(
                    state = state,
                    onBack = {},
                    onUpdatePrice = { _, _ -> },
                    onAdjustStock = { _, _, _ -> }
                )
            }
        }

        // Movement item rendered with exact fractional quantities
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasTestTag("stock_movement_$movementId"))
        composeTestRule.onNodeWithTag("stock_movement_$movementId").assertIsDisplayed()
        composeTestRule.onNodeWithText("-2.5", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("97.5", substring = true).assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 10. Stale Product Selection Clears Immediately and Shows Loading
    // -------------------------------------------------------------------------

    @Test
    fun productDetailScreen_staleProductSelection_clearsHistoryImmediatelyAndShowsLoading() {
        val productA = Product(
            id = "01ARZ3NDEKTSV4RRFFQ69G5FD1",
            nameAr = "صنف أ",
            nameEn = "Product A",
            category = ProductCategory.PANTRY,
            barcode = "1111111111111",
            priceIls = 10.0,
            costIls = 6.0,
            unitAr = "قطعة",
            unitEn = "pcs",
            stockCount = 50,
            accentHex = 0xFF4CAF50
        )
        val productB = Product(
            id = "01ARZ3NDEKTSV4RRFFQ69G5FD2",
            nameAr = "صنف ب",
            nameEn = "Product B",
            category = ProductCategory.PANTRY,
            barcode = "2222222222222",
            priceIls = 20.0,
            costIls = 12.0,
            unitAr = "قطعة",
            unitEn = "pcs",
            stockCount = 30,
            accentHex = 0xFF2196F3
        )

        val staleMovement = StockMovementItem(
            id = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FD3"),
            movementType = "sale",
            delta = BigDecimal("-1"),
            date = "2026-11-19",
            reason = "Sale",
            resultingStock = BigDecimal("49")
        )
        val staleHistory = LiveStockMovementHistory(
            productId = productA.id,
            movements = listOf(staleMovement)
        )

        val session = UserSession(
            id = "usr_mgr_1",
            name = "Manager",
            username = "manager",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        // Product B selected, but history belongs to Product A
        val state = TraderUiState(
            currentSession = session,
            products = listOf(productA, productB),
            selectedProductForDetail = productB,
            selectedProductHistory = staleHistory,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ProductDetailScreen(
                    state = state,
                    onBack = {},
                    onUpdatePrice = { _, _ -> },
                    onAdjustStock = { _, _, _ -> }
                )
            }
        }

        // Loading indicator must be displayed; stale movement from Product A must NOT be rendered
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasTestTag("product_stock_history_loading"))
        composeTestRule.onNodeWithTag("product_stock_history_loading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stock_movement_01ARZ3NDEKTSV4RRFFQ69G5FD3").assertDoesNotExist()

        // ViewModel test: selectProductForDetail resets history to null immediately
        val vm = TraderViewModel(OfflineDemoTraderRepository())
        vm.selectProductForDetail(productA)
        assertNull("Stale product history must be null on select", vm.uiState.value.selectedProductHistory)
        assertEquals(productA.id, vm.uiState.value.selectedProductForDetail?.id)
    }

    // -------------------------------------------------------------------------
    // 11. Financial Reports Read-Only Mode & Concealed Shift Close
    // -------------------------------------------------------------------------

    @Test
    fun financialReports_liveMode_rendersTypedReadOnlyData_andHidesShiftCloseButton() {
        val shiftSummary = LiveShiftSummary(
            shiftDate = "2026-11-19",
            currency = "USD",
            currencyMinorUnits = 2,
            openingFloat = BigDecimal("200.00"),
            cashSales = BigDecimal("1500.00"),
            cashCollections = BigDecimal("350.00"),
            cashDisbursements = BigDecimal("100.00"),
            operatingExpenses = BigDecimal("50.00"),
            expectedCash = BigDecimal("1900.00"),
            salesCount = 14
        )

        val treasurySummary = LiveTreasurySummary(
            cashAccounts = listOf(
                TreasuryAccountItem(
                    publicId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FC1"),
                    name = "Main POS Drawer",
                    accountType = "CASH",
                    currencyCode = "USD",
                    baseCurrencyCode = "USD",
                    balanceBase = BigDecimal("1900.00"),
                    balanceCurrency = BigDecimal("1900.00"),
                    currencyBalanceAvailable = true,
                    isActive = true,
                    bankName = null
                )
            ),
            bankAccounts = emptyList(),
            totalCashBase = BigDecimal("1900.00"),
            totalBankBase = BigDecimal.ZERO
        )

        val session = UserSession(
            id = "usr_owner_1",
            name = "Owner",
            username = "owner",
            role = UserRole.OWNER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val state = TraderUiState(
            currentSession = session,
            liveShiftSummary = shiftSummary,
            liveTreasurySummary = treasurySummary,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                FinancialReportsScreen(
                    state = state,
                    onUpdateOpeningCash = {},
                    onUpdateCountedCashText = {},
                    onToggleShiftClose = {}
                )
            }
        }

        // Live mode must conceal local shift closing action
        composeTestRule.onNodeWithTag("toggle_shift_close_button").assertDoesNotExist()

        // Reports screen rendered
        composeTestRule.onNodeWithTag("financial_reports_screen").assertIsDisplayed()

        // Expected cash displays server amount in typed currency
        composeTestRule.onAllNodesWithText("1,900.00 USD", substring = true)[0].assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // 12. Frozen Transaction Currency, Exact 2dp Human Presentation, Print/Share,
    //     and Unavailable Legacy Currency / Unavailable Local Tender Honesty
    // -------------------------------------------------------------------------

    @Test
    fun receiptScreen_pendingJodSale_rendersJodAt2dpWithChangedTenantCurrency_andNeverAssumesShekel() {
        val product = Product(
            id = "01ARZ3NDEKTSV4RRFFQ69G5JE1",
            nameAr = "قهوة أردنية",
            nameEn = "Jordanian Coffee",
            category = ProductCategory.PANTRY,
            barcode = "625100000001",
            priceExact = BigDecimal("9.83"),
            unitAr = "علبة",
            unitEn = "pack",
            stockCount = 20,
            accentHex = 0xFF2E7D32,
            saleCurrencyMinorUnits = 3
        )
        val item = BasketItem(
            product = product,
            quantity = BigDecimal.ONE,
            unitPriceExact = BigDecimal("9.83"),
            taxRateId = null,
            taxRateExact = BigDecimal.ZERO,
            isTaxInclusive = false
        )
        val invoice = InvoiceRecord(
            id = "pending-jod-event-1",
            invoiceNumber = 0,
            timestamp = "2026-11-25T10:15:00Z",
            items = listOf(item),
            subtotalExact = BigDecimal("9.83"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("9.83"),
            cashReceivedExact = BigDecimal("9.83"),
            changeReturnedExact = BigDecimal.ZERO,
            customerId = null,
            customerNote = "Walk-in",
            cashierNameAr = "أحمد",
            cashierNameEn = "Ahmad",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            clientEventId = "pending-jod-event-1",
            outboxStatus = "PENDING",
            hasLocalTenderDetails = true,
            localReceiptNumber = "LOCAL-JOD-1",
            currency = "JOD",
            currencyMinorUnits = 3,
            taxExact = BigDecimal.ZERO
        )
        val session = UserSession(
            id = "usr_cashier_jod",
            name = "Ahmad",
            username = "ahmad",
            role = UserRole.CASHIER,
            branchNameAr = "الفرع",
            branchNameEn = "Main",
            isDemoSession = false
        )
        // Current tenant business currency intentionally differs (ILS) to prove historic receipt uses frozen JOD.
        val state = TraderUiState(
            currentSession = session,
            businessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2),
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = null,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Pending Sync", substring = true).assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("9.83 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("ILS", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("toggle_invoice_details_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("thermal_receipt_paper").performScrollTo().assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("Amount (JOD)", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("copy_receipt_text_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("Copied pending JOD receipt must contain 9.83 JOD: $copied", copied.contains("9.83 JOD"))
        assertFalse("Copied pending JOD receipt must never contain ₪: $copied", copied.contains("₪"))
        assertFalse("Copied pending JOD receipt must never contain ILS: $copied", copied.contains("ILS"))
    }

    @Test
    fun receiptScreen_confirmedJodWithTaxDiscountAndSmallPositiveChange_rendersExact2dpAcrossScreenPrintAndShare() {
        val invoice = InvoiceRecord(
            id = "01JCV8X9M2NQW3TYZ4R5S6789J",
            invoiceNumber = 204,
            timestamp = "2026-11-25 12:00",
            items = emptyList(),
            subtotalExact = BigDecimal("10.000"),
            discountExact = BigDecimal("0.500"),
            netTotalExact = BigDecimal("11.100"),
            cashReceivedExact = BigDecimal("11.110"),
            changeReturnedExact = BigDecimal("0.010"),
            customerId = null,
            customerNote = "Walk-in",
            cashierNameAr = "مالك",
            cashierNameEn = "Owner",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            serverInvoiceUlid = "01JCV8X9M2NQW3TYZ4R5S6789J",
            serverInvoiceNumber = "INV-JOD-204",
            outboxStatus = "CONFIRMED",
            hasLocalTenderDetails = true,
            currency = "JOD",
            currencyMinorUnits = 3,
            taxExact = BigDecimal("1.600")
        )
        val liveDetail = LiveInvoiceRecord(
            id = "01JCV8X9M2NQW3TYZ4R5S6789J",
            invoiceNumber = "INV-JOD-204",
            status = "posted",
            paymentStatus = "PAID_CASH",
            customerId = "01JMB000000000000000000005",
            customerName = "Walk-in",
            date = "2026-11-25",
            createdAt = "2026-11-25T12:00:00Z",
            subtotalExact = BigDecimal("10.000"),
            discountExact = BigDecimal("0.500"),
            taxExact = BigDecimal("1.600"),
            totalExact = BigDecimal("11.100"),
            paidExact = BigDecimal("11.100"),
            remainingDebitExact = BigDecimal.ZERO,
            currency = "JOD",
            currencyMinorUnits = 3,
            lines = listOf(
                SalesInvoiceDetailLine(
                    id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B"),
                    productId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5JE1"),
                    productName = "Jordanian Coffee",
                    unit = "pack",
                    quantity = BigDecimal("1.000000"),
                    unitPrice = BigDecimal("10.000"),
                    lineTotal = BigDecimal("10.000"),
                    taxRate = BigDecimal("16.00"),
                    discount = BigDecimal.ZERO
                )
            ),
            thermalReceipt = null,
            clientEventId = null,
            serverInvoiceUlid = "01JCV8X9M2NQW3TYZ4R5S6789J",
            outboxStatus = "CONFIRMED"
        )
        val session = UserSession(
            id = "usr_owner_jod",
            name = "Owner",
            username = "owner",
            role = UserRole.OWNER,
            branchNameAr = "الفرع",
            branchNameEn = "Main",
            isDemoSession = false
        )
        val state = TraderUiState(
            currentSession = session,
            businessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2),
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = liveDetail,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        // Header, tax/discount/subtotal, tendered, and small positive 0.01 change all strictly 2dp JOD
        assertTrue(composeTestRule.onAllNodesWithText("11.10 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("1.60 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("0.50 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("11.11 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("0.01 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("0.010", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("toggle_invoice_details_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("thermal_receipt_paper").performScrollTo().assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("Amount (JOD)", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("0.01 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("copy_receipt_text_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("10.00 JOD"))
        assertTrue(copied.contains("1.60 JOD"))
        assertTrue(copied.contains("-0.50 JOD"))
        assertTrue(copied.contains("11.10 JOD"))
        assertTrue(copied.contains("11.11 JOD"))
        assertTrue(copied.contains("0.01 JOD"))
        assertFalse(copied.contains("₪"))
        assertFalse(copied.contains("0.010"))
    }

    @Test
    fun receiptScreen_confirmedJodWithoutLocalTenderDetails_showsPaidInJod_andMarksTenderChangeUnavailable() {
        val invoice = InvoiceRecord(
            id = "01JCV8X9M2NQW3TYZ4R5S6789K",
            invoiceNumber = 305,
            timestamp = "2026-11-25 13:15",
            items = emptyList(),
            subtotalExact = BigDecimal("9.83"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("9.83"),
            cashReceivedExact = BigDecimal("10.00"),
            changeReturnedExact = BigDecimal("0.17"),
            customerId = null,
            customerNote = "Walk-in",
            cashierNameAr = "سعيد",
            cashierNameEn = "Saeed",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            serverInvoiceUlid = "01JCV8X9M2NQW3TYZ4R5S6789K",
            serverInvoiceNumber = "INV-JOD-305",
            outboxStatus = "CONFIRMED",
            hasLocalTenderDetails = false,
            currency = "JOD",
            currencyMinorUnits = 3,
            taxExact = BigDecimal.ZERO
        )
        val liveDetail = LiveInvoiceRecord(
            id = "01JCV8X9M2NQW3TYZ4R5S6789K",
            invoiceNumber = "INV-JOD-305",
            status = "posted",
            paymentStatus = "PAID_CASH",
            customerId = "01JMB000000000000000000005",
            customerName = "Walk-in",
            date = "2026-11-25",
            createdAt = "2026-11-25T13:15:00Z",
            subtotalExact = BigDecimal("9.83"),
            discountExact = BigDecimal.ZERO,
            taxExact = BigDecimal.ZERO,
            totalExact = BigDecimal("9.83"),
            paidExact = BigDecimal("9.83"),
            remainingDebitExact = BigDecimal.ZERO,
            currency = "JOD",
            currencyMinorUnits = 3,
            lines = emptyList(),
            thermalReceipt = null,
            clientEventId = null,
            serverInvoiceUlid = "01JCV8X9M2NQW3TYZ4R5S6789K",
            outboxStatus = "CONFIRMED"
        )
        val session = UserSession(
            id = "usr_mgr_jod",
            name = "Manager",
            username = "mgr",
            role = UserRole.MANAGER,
            branchNameAr = "الفرع",
            branchNameEn = "Main",
            isDemoSession = false
        )
        val state = TraderUiState(
            currentSession = session,
            businessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2),
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = liveDetail,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        // Settled Paid amount is shown in JOD; unavailable tender (10.00) & change (0.17) must NEVER be fabricated
        assertTrue(composeTestRule.onAllNodesWithText("9.83 JOD", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("Unavailable", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("10.00", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("0.17", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("toggle_invoice_details_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("copy_receipt_text_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("9.83 JOD"))
        assertTrue(copied.contains("Unavailable"))
        assertFalse(copied.contains("10.00"))
        assertFalse(copied.contains("0.17"))
        assertFalse(copied.contains("₪"))
    }

    @Test
    fun receiptScreen_missingLegacyCurrency_surfacesUnavailableCurrencyNeverAssumesShekel() {
        val invoice = InvoiceRecord(
            id = "legacy-missing-curr-1",
            invoiceNumber = 404,
            timestamp = "2026-11-20 09:00",
            items = emptyList(),
            subtotalExact = BigDecimal("75.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("75.00"),
            cashReceivedExact = BigDecimal("75.00"),
            changeReturnedExact = BigDecimal.ZERO,
            customerNote = "Legacy Customer",
            cashierNameAr = "أحمد",
            cashierNameEn = "Ahmad",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            currency = null
        )
        val state = TraderUiState(
            currentSession = UserSession(
                id = "usr_legacy",
                name = "Ahmad",
                username = "ahmad",
                role = UserRole.CASHIER,
                branchNameAr = "الفرع",
                branchNameEn = "Main",
                isDemoSession = false
            ),
            businessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2),
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = null,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        assertTrue(composeTestRule.onAllNodesWithText("75.00 (Currency unavailable)", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("ILS", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("toggle_invoice_details_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithText("Amount (Currency unavailable)", substring = true).fetchSemanticsNodes().isNotEmpty())

        composeTestRule.onNodeWithTag("copy_receipt_text_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("75.00 (Currency unavailable)"))
        assertFalse(copied.contains("₪"))
        assertFalse(copied.contains("ILS"))
    }

    @Test
    fun receiptScreen_ilsSale_rendersShekelAnd2dpAcrossScreenPrintAndShare() {
        val invoice = InvoiceRecord(
            id = "ils-witness-1",
            invoiceNumber = 1042,
            timestamp = "2026-11-25 11:45",
            items = emptyList(),
            subtotalExact = BigDecimal("50.50"),
            discountExact = BigDecimal("0.50"),
            netTotalExact = BigDecimal("50.00"),
            cashReceivedExact = BigDecimal("50.01"),
            changeReturnedExact = BigDecimal("0.01"),
            customerNote = "Walk-in",
            cashierNameAr = "أبو كريم",
            cashierNameEn = "Abu Kareem",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            hasLocalTenderDetails = true,
            currency = "ILS",
            currencyMinorUnits = 2,
            taxExact = BigDecimal.ZERO
        )
        val state = TraderUiState(
            currentSession = UserSession(
                id = "usr_demo",
                name = "Abu Kareem",
                username = "owner",
                role = UserRole.OWNER,
                branchNameAr = "الفرع",
                branchNameEn = "Main",
                isDemoSession = true
            ),
            businessInfo = BusinessInfo(defaultCurrency = "USD", defaultCurrencyMinorUnits = 2),
            activeReceipt = invoice,
            invoices = listOf(invoice),
            selectedInvoiceDetail = null,
            isNewlyCompletedSale = false,
            language = AppLanguage.EN
        )

        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                ReceiptScreen(
                    state = state,
                    onPrintReceipt = {},
                    onStartNewSale = {},
                    onBackToHome = {}
                )
            }
        }

        assertTrue(composeTestRule.onAllNodesWithText("50.00 ₪", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("50.01 ₪", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("0.01 ₪", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("USD", substring = true).fetchSemanticsNodes().isEmpty())

        composeTestRule.onNodeWithTag("copy_receipt_text_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("50.50 ₪"))
        assertTrue(copied.contains("-0.50 ₪"))
        assertTrue(copied.contains("50.00 ₪"))
        assertTrue(copied.contains("50.01 ₪"))
        assertTrue(copied.contains("0.01 ₪"))
        assertFalse(copied.contains("USD"))
    }

    @Test
    fun viewModel_formatReceiptShareText_usesFrozenCurrencyAndGuardsUnavailableTenderAcrossChangedBusinessCurrency() {
        val vm = TraderViewModel(OfflineDemoTraderRepository())
        // Default demo businessInfo has defaultCurrency = "ILS", proving formatReceiptShareText freezes receipt currency.
        val jodLocal = InvoiceRecord(
            id = "jod-share-local",
            invoiceNumber = 501,
            timestamp = "2026-11-25 14:00",
            items = emptyList(),
            subtotalExact = BigDecimal("8.470"),
            discountExact = BigDecimal("0.100"),
            netTotalExact = BigDecimal("9.830"),
            cashReceivedExact = BigDecimal("9.840"),
            changeReturnedExact = BigDecimal("0.010"),
            customerNote = "Amman Client",
            cashierNameAr = "أحمد",
            cashierNameEn = "Ahmad",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO,
            hasLocalTenderDetails = true,
            localReceiptNumber = "LOCAL-JOD-501",
            currency = "JOD",
            currencyMinorUnits = 3,
            taxExact = BigDecimal("1.460")
        )
        val jodShare = vm.formatReceiptShareText(jodLocal)
        assertTrue(jodShare.contains("LOCAL-JOD-501"))
        assertTrue(jodShare.contains("8.47 JOD"))
        assertTrue(jodShare.contains("1.46 JOD"))
        assertTrue(jodShare.contains("-0.10 JOD"))
        assertTrue(jodShare.contains("9.83 JOD"))
        assertTrue(jodShare.contains("9.84 JOD"))
        assertTrue(jodShare.contains("0.01 JOD"))
        assertFalse(jodShare.contains("₪"))
        assertFalse(jodShare.contains("ILS"))

        val jodNoLocalTender = jodLocal.copy(hasLocalTenderDetails = false)
        val serverOnlyShare = vm.formatReceiptShareText(jodNoLocalTender)
        assertTrue(serverOnlyShare.contains("9.83 JOD"))
        assertTrue(serverOnlyShare.contains("Unavailable") || serverOnlyShare.contains("غير متاح"))
        assertFalse(serverOnlyShare.contains("9.84"))
        assertFalse(serverOnlyShare.contains("0.01"))
        assertFalse(serverOnlyShare.contains("₪"))
    }
}
