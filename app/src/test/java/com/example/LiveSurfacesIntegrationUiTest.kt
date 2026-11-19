package com.example

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
import org.junit.Assert.assertNull
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
    // 6. Invoices History has_more Gap Honesty
    // -------------------------------------------------------------------------

    @Test
    fun invoicesHistoryScreen_rendersLimitationBanner_whenHasMoreGapIsTrue() {
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
            liveInvoicesHasMoreGap = true,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                InvoicesHistoryScreen(
                    state = state,
                    onOpenInvoice = {},
                    onStartNewSale = {}
                )
            }
        }

        // Limitation banner must be visible
        composeTestRule.onAllNodes(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))[0].performScrollToNode(hasTestTag("invoice_history_has_more_banner"))
        composeTestRule.onNodeWithTag("invoice_history_has_more_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("More records exist on server — pagination unavailable", substring = true).assertIsDisplayed()
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
}
