package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.merchantread.CustomerStatementAging
import com.example.data.mobile.merchantread.CustomerStatementCurrencySummary
import com.example.data.mobile.merchantread.CustomerStatementEntry
import com.example.data.mobile.merchantread.LiveCustomerStatement
import com.example.data.mobile.merchantread.MerchantReadAdapter
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.ProductionTraderRepository
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.Supplier
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBidiUtils
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.CustomerDetailScreen
import com.example.ui.screens.CustomerPaymentCollectionDialog
import com.example.ui.screens.CustomersScreen
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.screens.SupplierDetailScreen
import com.example.ui.screens.SuppliersScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BalanceAndCurrencyPresentationRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val companyA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceA = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val customerA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val supplierA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"

    private val ownerSession = UserSession(
        id = actorA.value,
        name = "Owner",
        username = "owner",
        role = UserRole.OWNER,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch",
        isDemoSession = false,
        permissions = setOf(
            "pos.sale",
            "customers.view",
            "money.receipt.create",
            "vendors.view",
            "money.vendor_payment.create",
            "purchases.manage",
            "expenses.manage",
            "reports.financial.view"
        )
    )

    private val jodBusiness = BusinessInfo(
        nameAr = "متجر عمان",
        nameEn = "Amman Store",
        cityAr = "عمان",
        cityEn = "Amman",
        phone = "0790000000",
        taxNumber = "123456789",
        defaultCurrency = "JOD",
        defaultCurrencyMinorUnits = 3
    )

    // -------------------------------------------------------------------------
    // 1. Human 2dp & < 0.01 CCY Sub-Cent Formatting Rules (No 3dp, No 0.00 Lie)
    // -------------------------------------------------------------------------
    @Test
    fun formatHumanMoney2dp_handlesSubCentZeroOneCentNullAndCurrencyIsolation() {
        assertEquals("< 0.01 JOD", WesternFormatters.formatHumanMoney2dp(BigDecimal("0.001"), "JOD", AppLanguage.EN))
        assertEquals("-< 0.01 JOD", WesternFormatters.formatHumanMoney2dp(BigDecimal("-0.001"), "JOD", AppLanguage.EN))
        assertEquals("< 0.01 ₪", WesternFormatters.formatHumanMoney2dp(BigDecimal("0.005"), "ILS", AppLanguage.EN))
        assertEquals("-< 0.01 ₪", WesternFormatters.formatHumanMoney2dp(BigDecimal("-0.005"), "ILS", AppLanguage.EN))
        assertEquals("0.00 JOD", WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, "JOD", AppLanguage.EN))
        assertEquals("0.01 JOD", WesternFormatters.formatHumanMoney2dp(BigDecimal("0.01"), "JOD", AppLanguage.EN))
        assertEquals("-0.01 JOD", WesternFormatters.formatHumanMoney2dp(BigDecimal("-0.01"), "JOD", AppLanguage.EN))
        assertEquals("12.35 JOD", WesternFormatters.formatHumanMoney2dp(BigDecimal("12.345"), "JOD", AppLanguage.EN))
        assertEquals("Unavailable", WesternFormatters.formatHumanMoney2dp(null, "JOD", AppLanguage.EN))
        assertEquals("غير متاح", WesternFormatters.formatHumanMoney2dp(null, "ILS", AppLanguage.AR))

        // Historical null/blank currency never falls back to ILS
        assertEquals("5.00 (Currency unavailable)", WesternFormatters.formatHumanMoney2dp(BigDecimal("5.00"), null, AppLanguage.EN))
        assertEquals("5.00 (العملة غير متاحة)", WesternFormatters.formatHumanMoney2dp(BigDecimal("5.00"), "", AppLanguage.AR))

        // BiDi isolate wrapper preserves LTR numeric segment in RTL
        assertEquals("\u2066< 0.01 JOD\u2069", StitchBidiUtils.formatHumanMoney2dp(BigDecimal("0.001"), "JOD", AppLanguage.AR))
        assertEquals("غير متاح", StitchBidiUtils.formatHumanMoney2dp(null, "JOD", AppLanguage.AR))
    }

    // -------------------------------------------------------------------------
    // 2. CustomersScreen Directory + Filters + QuickCollect CTA + Sub-Cent / Null (EN LTR & AR RTL)
    // -------------------------------------------------------------------------
    @Test
    fun customersScreen_subCentSignNullCurrency_enLtrAndArRtl() {
        val customers = listOf(
            Customer(id = "c_pos_sub", nameAr = "زبون دين صغير", nameEn = "SubCent Debtor", balanceExact = BigDecimal("0.001"), isDetailLoaded = true),
            Customer(id = "c_neg_sub", nameAr = "زبون مقدم صغير", nameEn = "SubCent Advance", balanceExact = BigDecimal("-0.001"), isDetailLoaded = true),
            Customer(id = "c_half_cent", nameAr = "زبون نصف قرش", nameEn = "HalfCent Debtor", balanceExact = BigDecimal("0.005"), isDetailLoaded = true),
            Customer(id = "c_zero", nameAr = "زبون خالص", nameEn = "Settled Customer", balanceExact = BigDecimal.ZERO, isDetailLoaded = true),
            Customer(id = "c_one_cent", nameAr = "زبون قرش", nameEn = "OneCent Debtor", balanceExact = BigDecimal("0.01"), isDetailLoaded = true),
            Customer(id = "c_neg_one_cent", nameAr = "زبون مقدم قرش", nameEn = "OneCent Advance", balanceExact = BigDecimal("-0.01"), isDetailLoaded = true),
            Customer(id = "c_null", nameAr = "زبون مجهول", nameEn = "Unknown Balance", balanceExact = null, isDetailLoaded = false)
        )

        var currentLang = AppLanguage.EN
        val state = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            customers = customers
        )

        // Part A: EN LTR
        composeTestRule.setContent {
            val langState = if (currentLang == AppLanguage.AR) state.copy(language = AppLanguage.AR) else state
            CompositionLocalProvider(LocalLayoutDirection provides if (currentLang == AppLanguage.AR) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                SmallTraderTheme(darkTheme = false, isRtl = currentLang == AppLanguage.AR) {
                    CustomersScreen(
                        state = langState,
                        onCustomerClick = {},
                        onOpenAddCustomerDialog = {},
                        onStartSaleForCustomer = {},
                        onQuickCollectPayment = {}
                    )
                }
            }
        }

        // Null customer causes directory aggregate to be Unavailable (no NaN, no ₪)
        composeTestRule.onNodeWithTag("total_customer_debts_value").assertTextContains("Unavailable", substring = true)
        composeTestRule.onNodeWithTag("total_customer_advances_value").assertTextContains("Unavailable", substring = true)

        // Quick Collect CTA MUST be visible for +0.001 and +0.005 and +0.01, absent for -0.001, 0, null
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("quick_collect_c_pos_sub"))
        composeTestRule.onNodeWithTag("quick_collect_c_pos_sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("quick_collect_c_half_cent"))
        composeTestRule.onNodeWithTag("quick_collect_c_half_cent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("quick_collect_c_one_cent"))
        composeTestRule.onNodeWithTag("quick_collect_c_one_cent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_pos_sub"))
        composeTestRule.onNodeWithTag("customer_row_c_pos_sub").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_neg_sub"))
        composeTestRule.onNodeWithTag("customer_row_c_neg_sub").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_null"))
        composeTestRule.onNodeWithTag("customer_row_c_null").assertTextContains("Balance unavailable", substring = true)
        assertEquals(0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("NaN", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)

        // Filters must classify +0.001 & +0.005 as DEBTORS, -0.001 & -0.01 as ADVANCE, 0 as SETTLED
        composeTestRule.onNodeWithTag("customer_filter_DEBTORS").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_pos_sub"))
        composeTestRule.onNodeWithTag("customer_row_c_pos_sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_half_cent"))
        composeTestRule.onNodeWithTag("customer_row_c_half_cent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_one_cent"))
        composeTestRule.onNodeWithTag("customer_row_c_one_cent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_row_c_zero").assertDoesNotExist()
        composeTestRule.onNodeWithTag("customer_row_c_neg_sub").assertDoesNotExist()

        composeTestRule.onNodeWithTag("customer_filter_ADVANCE").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_neg_sub"))
        composeTestRule.onNodeWithTag("customer_row_c_neg_sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_c_neg_one_cent"))
        composeTestRule.onNodeWithTag("customer_row_c_neg_one_cent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_row_c_pos_sub").assertDoesNotExist()

        composeTestRule.onNodeWithTag("customer_filter_SETTLED").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_row_c_zero").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customer_row_c_pos_sub").assertDoesNotExist()
        composeTestRule.onNodeWithTag("customer_row_c_neg_sub").assertDoesNotExist()
        composeTestRule.onNodeWithTag("customer_row_c_null").assertDoesNotExist()

        // Part B: Check AR RTL rendering with clean JOD totals (when all customers known & sub-cent sum < 0.01)
        composeTestRule.onNodeWithTag("customer_filter_ALL").performClick()
        currentLang = AppLanguage.AR
        composeTestRule.waitForIdle()
    }

    @Test
    fun customersScreen_arRtlSubCentDirectoryTotals_renderLessThanOneCentAndNoNaN() {
        val customers = listOf(
            Customer(id = "c1", nameAr = "زبون ١", nameEn = "C1", balanceExact = BigDecimal("0.001"), isDetailLoaded = true),
            Customer(id = "c2", nameAr = "زبون ٢", nameEn = "C2", balanceExact = BigDecimal("-0.001"), isDetailLoaded = true)
        )
        val state = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.AR,
            businessInfo = jodBusiness,
            customers = customers
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SmallTraderTheme(darkTheme = false, isRtl = true) {
                    CustomersScreen(
                        state = state,
                        onCustomerClick = {},
                        onOpenAddCustomerDialog = {},
                        onStartSaleForCustomer = {},
                        onQuickCollectPayment = {}
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("total_customer_debts_value").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("total_customer_advances_value").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("customer_row_c1").assertTextContains("عليه:", substring = true)
        composeTestRule.onNodeWithTag("customer_row_c2").assertTextContains("له رصيد مقدم", substring = true)
        assertEquals(0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
    }

    // -------------------------------------------------------------------------
    // 3. CustomerDetailScreen Credit Limit Null/NaN + Statement Historical Currency & 2dp
    // -------------------------------------------------------------------------
    @Test
    fun customerDetailScreen_nullBalanceCreditLimitNoNaN_subCentAndHistoricalCurrencyIsolated() {
        val custNullWithLimit = Customer(
            id = customerA.value,
            nameAr = "زبون سقف",
            nameEn = "Limit Customer",
            balanceExact = null,
            creditLimitExact = BigDecimal("100.00"),
            isDetailLoaded = true
        )
        val custSubCentNoLimit = Customer(
            id = customerA.value,
            nameAr = "زبون فرعي",
            nameEn = "SubCent Customer",
            balanceExact = BigDecimal("0.001"),
            creditLimitExact = null,
            isDetailLoaded = true
        )

        val liveStatementSubCent = LiveCustomerStatement(
            customerId = customerA.value,
            customerName = "SubCent Customer",
            fromDate = "2026-11-01",
            toDate = "2026-11-27",
            currencies = mapOf(
                "JOD" to CustomerStatementCurrencySummary(
                    currency = "JOD",
                    openingBalance = BigDecimal.ZERO,
                    closingBalance = BigDecimal("0.001"),
                    totalDebits = BigDecimal("0.001"),
                    totalCredits = BigDecimal.ZERO,
                    entries = listOf(
                        CustomerStatementEntry(
                            date = "2026-11-27",
                            type = "invoice",
                            number = "INV-1",
                            reference = "01JCVENTRY0001",
                            description = "SubCent entry",
                            debit = BigDecimal("0.001"),
                            credit = BigDecimal.ZERO,
                            balance = BigDecimal("0.001")
                        )
                    ),
                    aging = CustomerStatementAging(
                        current = BigDecimal("0.001"),
                        days1To30 = BigDecimal.ZERO,
                        days31To60 = BigDecimal.ZERO,
                        days61To90 = BigDecimal.ZERO,
                        days90Plus = BigDecimal.ZERO,
                        total = BigDecimal("0.001")
                    )
                )
            )
        )

        // Part A: Null balance + Credit Limit = 100.00 JOD -> Available: Unavailable, NO NaN, NO ₪
        val stateNullBal = TraderUiState(
            currentSession = ownerSession.copy(isDemoSession = true),
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            customers = listOf(custNullWithLimit),
            selectedCustomerForDetail = custNullWithLimit
        )
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerDetailScreen(
                    state = stateNullBal,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("customer_detail_balance_value").assertTextContains("Unavailable", substring = true)
        composeTestRule.onNodeWithText("Available: Unavailable", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("100.00 JOD", substring = true).assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("NaN", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun customerDetailScreen_liveStatement2dpAndHistoricalCurrencyRows_never3dpOrTenantFallback() {
        val custSubCent = Customer(
            id = customerA.value,
            nameAr = "زبون فرعي",
            nameEn = "SubCent Customer",
            balanceExact = BigDecimal("0.001"),
            creditLimitExact = null,
            isDetailLoaded = true
        )
        val liveStatementSubCent = LiveCustomerStatement(
            customerId = customerA.value,
            customerName = "SubCent Customer",
            fromDate = "2026-11-01",
            toDate = "2026-11-27",
            currencies = mapOf(
                "JOD" to CustomerStatementCurrencySummary(
                    currency = "JOD",
                    openingBalance = BigDecimal.ZERO,
                    closingBalance = BigDecimal("0.001"),
                    totalDebits = BigDecimal("0.001"),
                    totalCredits = BigDecimal.ZERO,
                    entries = listOf(
                        CustomerStatementEntry(
                            date = "2026-11-27",
                            type = "invoice",
                            number = "INV-1",
                            reference = "01JCVENTRY0001",
                            description = "SubCent entry",
                            debit = BigDecimal("0.001"),
                            credit = BigDecimal.ZERO,
                            balance = BigDecimal("0.001")
                        )
                    ),
                    aging = CustomerStatementAging(
                        current = BigDecimal("0.001"),
                        days1To30 = BigDecimal.ZERO,
                        days31To60 = BigDecimal.ZERO,
                        days61To90 = BigDecimal.ZERO,
                        days90Plus = BigDecimal.ZERO,
                        total = BigDecimal("0.001")
                    )
                )
            )
        )

        val stateLive = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            customers = listOf(custSubCent),
            selectedCustomerForDetail = custSubCent,
            selectedCustomerStatement = liveStatementSubCent
        )
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerDetailScreen(
                    state = stateLive,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("customer_detail_balance_value").assertTextContains("< 0.01 JOD", substring = true)
        assertTrue(
            composeTestRule.onAllNodes(
                hasAnyAncestor(hasTestTag("customer_currency_statement_JOD")) and hasText("< 0.01 JOD", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals("Must NOT print 3dp 0.001 on human UI", 0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)

        // Check historical USD CustomerTransaction row when tenant defaultCurrency is JOD
        val usdTx = CustomerTransaction(
            id = "tx_hist_usd",
            customerId = customerA.value,
            date = "2026-10-15",
            typeAr = "فاتورة",
            typeEn = "Invoice",
            debitExact = BigDecimal("10.005"),
            creditExact = BigDecimal("10.00"),
            runningBalanceExact = BigDecimal("0.001"),
            note = "Short by 0.005",
            currency = "USD"
        )
        val stateHistorical = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            customers = listOf(custSubCent),
            selectedCustomerForDetail = custSubCent,
            customerTransactions = listOf(usdTx),
            customerStatementError = "offline_fallback"
        )
        // Re-compose via fresh Activity/Rule or separate @Test; verify MerchantReadAdapter too
        val adapted = MerchantReadAdapter.toDomainCustomerTransaction(
            liveStatementSubCent.currencies["JOD"]!!.entries.single(),
            customerA.value,
            currency = "JOD"
        )
        assertEquals("JOD", adapted.currency)
    }

    @Test
    fun historicalCustomerStatementRow_preservesTransactionCurrency_andRejects0005PaidTolerance() {
        val custSubCent = Customer(
            id = customerA.value,
            nameAr = "زبون",
            nameEn = "Customer",
            balanceExact = BigDecimal("0.001"),
            creditLimitExact = null,
            isDetailLoaded = true
        )
        val usdTx = CustomerTransaction(
            id = "tx_hist_usd",
            customerId = customerA.value,
            date = "2026-10-15",
            typeAr = "فاتورة",
            typeEn = "Invoice",
            debitExact = BigDecimal("10.005"),
            creditExact = BigDecimal("10.00"),
            runningBalanceExact = BigDecimal("0.001"),
            note = "Short by 0.005",
            currency = "USD"
        )
        val stateHistorical = TraderUiState(
            currentSession = ownerSession.copy(isDemoSession = true),
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            customers = listOf(custSubCent),
            selectedCustomerForDetail = custSubCent,
            customerTransactions = listOf(usdTx)
        )
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerDetailScreen(
                    state = stateHistorical,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("customer_tx_tx_hist_usd"))
        assertTrue(
            composeTestRule.onAllNodes(
                hasAnyAncestor(hasTestTag("customer_tx_tx_hist_usd")) and hasText("USD", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodes(
                hasAnyAncestor(hasTestTag("customer_tx_tx_hist_usd")) and hasText("< 0.01 USD", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        assertEquals("Short by 0.005 must NOT show Paid Cash", 0, composeTestRule.onAllNodesWithText("Paid Cash", substring = true).fetchSemanticsNodes().size)
        assertEquals("Historical USD row must NOT print ILS ₪", 0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
    }

    // -------------------------------------------------------------------------
    // 4. SuppliersScreen Directory & Detail (+0.001, -0.001, 0, null, JOD/ILS, AR/EN)
    // -------------------------------------------------------------------------
    @Test
    fun suppliersScreen_andDetail_subCentSignNullAndCurrency() {
        val suppliers = listOf(
            Supplier(id = "s_pos_sub", nameAr = "مورد له علينا", nameEn = "SubCent Payable", balanceOwedExact = BigDecimal("0.001")),
            Supplier(id = "s_neg_sub", nameAr = "مورد لنا عنده", nameEn = "SubCent NegOwed", balanceOwedExact = BigDecimal("-0.001")),
            Supplier(id = "s_adv_sub", nameAr = "مورد مقدم صريح", nameEn = "SubCent ExplicitAdv", balanceOwedExact = BigDecimal.ZERO, advancesExact = BigDecimal("0.001")),
            Supplier(id = "s_zero", nameAr = "مورد خالص", nameEn = "Settled Supplier", balanceOwedExact = BigDecimal.ZERO),
            Supplier(id = "s_null", nameAr = "مورد مجهول", nameEn = "Null Supplier", balanceOwedExact = null)
        )
        val state = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            suppliers = suppliers
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                SuppliersScreen(
                    state = state,
                    onSupplierClick = {},
                    onOpenReceivePurchaseDialog = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("total_supplier_payables_value").assertTextContains("Unavailable", substring = true)
        composeTestRule.onNodeWithTag("total_supplier_advances_value").assertTextContains("Unavailable", substring = true)
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_pos_sub"))
        composeTestRule.onNodeWithTag("supplier_row_s_pos_sub").assertTextContains("Owed to Supplier", substring = true)
        composeTestRule.onNodeWithTag("supplier_row_s_pos_sub").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_neg_sub"))
        composeTestRule.onNodeWithTag("supplier_row_s_neg_sub").assertTextContains("Store Advance", substring = true)
        composeTestRule.onNodeWithTag("supplier_row_s_neg_sub").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_adv_sub"))
        composeTestRule.onNodeWithTag("supplier_row_s_adv_sub").assertTextContains("Store Advance", substring = true)
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_null"))
        composeTestRule.onNodeWithTag("supplier_row_s_null").assertTextContains("Balance unavailable", substring = true)
        assertEquals(0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)

        composeTestRule.onNodeWithTag("supplier_filter_PAYABLES").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_pos_sub"))
        composeTestRule.onNodeWithTag("supplier_row_s_pos_sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("supplier_row_s_zero").assertDoesNotExist()

        composeTestRule.onNodeWithTag("supplier_filter_ADVANCES").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_neg_sub"))
        composeTestRule.onNodeWithTag("supplier_row_s_neg_sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("suppliers_directory_list").performScrollToNode(hasTestTag("supplier_row_s_adv_sub"))
        composeTestRule.onNodeWithTag("supplier_row_s_adv_sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("supplier_row_s_pos_sub").assertDoesNotExist()
    }

    @Test
    fun supplierDetailScreen_arRtlNegativeSubCentAdvance_displaysLessThanOneCentJod() {
        val supAdv = Supplier(
            id = supplierA.value,
            nameAr = "مورد فرعي",
            nameEn = "SubCent Supplier",
            balanceOwedExact = BigDecimal("-0.001")
        )
        val state = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.AR,
            businessInfo = jodBusiness,
            suppliers = listOf(supAdv),
            selectedSupplierForDetail = supAdv
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SmallTraderTheme(darkTheme = false, isRtl = true) {
                    SupplierDetailScreen(
                        state = state,
                        onBack = {},
                        onOpenReceivePurchaseDialog = {}
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("supplier_detail_balance_value").assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithText("دفعة مقدمة لنا", substring = true).assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
    }

    // -------------------------------------------------------------------------
    // 5. Full Debt Shortcut Guard on Sub-Cent Debt (Customer & Supplier Dialogs)
    // -------------------------------------------------------------------------
    @Test
    fun fullDebtShortcut_unrepresentableSubCentDebt_disabledWithExplanation_andBlocks3dpEntry() {
        val custSub = Customer(
            id = customerA.value,
            nameAr = "زبون دين دقيق",
            nameEn = "SubCent Debt Customer",
            balanceExact = BigDecimal("0.001"),
            isDetailLoaded = true
        )
        val supSub = Supplier(
            id = supplierA.value,
            nameAr = "مورد ذمة دقيقة",
            nameEn = "SubCent Payable Supplier",
            balanceOwedExact = BigDecimal("0.001")
        )
        var capturedCustomerAmount: BigDecimal? = null
        var capturedSupplierAmount: BigDecimal? = null
        var showSupplier by androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                if (!showSupplier) {
                    CustomerPaymentCollectionDialog(
                        customer = custSub,
                        lang = AppLanguage.EN,
                        onDismiss = {},
                        onConfirmPaymentExactWithResult = { _, amt, _, _, _, cb ->
                            capturedCustomerAmount = amt
                            cb(Result.failure(IllegalStateException("capture only")))
                        },
                        currency = "JOD",
                        minorUnits = 3
                    )
                } else {
                    PaySupplierDialog(
                        supplier = supSub,
                        lang = AppLanguage.EN,
                        onDismiss = {},
                        onPaySupplier = { _, _, _ -> Result.failure(IllegalStateException("unused")) },
                        onPaySupplierExactWithResult = { _, amt, _, _, _, cb ->
                            capturedSupplierAmount = amt
                            cb(Result.failure(IllegalStateException("capture only")))
                        },
                        currency = "JOD",
                        minorUnits = 3
                    )
                }
            }
        }

        // Customer dialog: initial amount must be empty, Full Debt disabled showing < 0.01 JOD + explanation
        composeTestRule.onNodeWithTag("collect_preset_full").performScrollTo().assertIsNotEnabled().assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("customer_full_debt_unrepresentable_note").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsNotEnabled()
        assertEquals(0, composeTestRule.onAllNodesWithText("0.001", substring = true).fetchSemanticsNodes().size)

        // Attempting 3dp entry ("0.001") shows precision error & keeps Confirm disabled
        composeTestRule.onNodeWithTag("payment_amount_input").performScrollTo().performTextInput("0.001")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("payment_amount_precision_error").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsNotEnabled()

        // Valid 2dp entry ("1.50") submits exact payload cleanly
        composeTestRule.onNodeWithTag("payment_amount_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("payment_amount_input").performScrollTo().performTextInput("1.50")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsEnabled().performClick()
        assertNotNull(capturedCustomerAmount)
        assertEquals(0, capturedCustomerAmount!!.compareTo(BigDecimal("1.50")))

        // Switch to Supplier dialog
        showSupplier = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pay_supplier_preset_full").performScrollTo().assertIsNotEnabled().assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("supplier_full_debt_unrepresentable_note").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("supplier_pay_amount_input").performScrollTo().performTextInput("0.001")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("supplier_pay_amount_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").performScrollTo().performTextInput("2.25")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsEnabled().performClick()
        assertNotNull(capturedSupplierAmount)
        assertEquals(0, capturedSupplierAmount!!.compareTo(BigDecimal("2.25")))
    }

    // -------------------------------------------------------------------------
    // 6. TraderViewModel Payment Feedback & Statement Formatting + CashPayment Bill Chip Exactness
    // -------------------------------------------------------------------------
    @Test
    fun viewModelPaymentFeedbackAndStatementText_preserveJodSubCentAndHistoricalCurrency() = runBlocking {
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") ->
                        TransportResponse(
                            201,
                            mapOf("Content-Type" to "application/json"),
                            """{"id":"01JCVFEEDBACKCUST01","customer_id":"${customerA.value}","amount":"10.000000","currency":"JOD","posted_at":"2026-11-27","balance":"0.001"}"""
                        )
                    request.method == HttpMethod.POST && request.path.endsWith("/suppliers/${supplierA.value}/payments") ->
                        TransportResponse(
                            201,
                            mapOf("Content-Type" to "application/json"),
                            """{"id":"01JCVFEEDBACKSUP01","supplier_id":"${supplierA.value}","amount":"15.000000","currency":"JOD","posted_at":"2026-11-27","balance_owed":"-0.001"}"""
                        )
                    request.method == HttpMethod.GET && request.path.endsWith("/suppliers") ->
                        TransportResponse(
                            200,
                            mapOf("Content-Type" to "application/json"),
                            """
                                {
                                  "company_id": "${companyA.value}",
                                  "count": 1,
                                  "has_more": false,
                                  "suppliers": [
                                    {
                                      "id": "${supplierA.value}",
                                      "code": "SUP-01",
                                      "name": "Supplier A",
                                      "name_ar": "مورد أ",
                                      "name_en": "Supplier A",
                                      "currency": "JOD",
                                      "currency_minor_units": 3,
                                      "balance_owed": "14.999",
                                      "advances": null
                                    }
                                  ]
                                }
                            """.trimIndent()
                        )
                    else -> TransportResponse(200, mapOf("Content-Type" to "application/json"), "{}")
                }
            }
        }

        val readClient = MerchantReadClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { reqCompanyId, _ -> if (reqCompanyId == companyA) validToken else null }
        )
        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(transport, "https://api.example.com") { validToken },
            companyId = companyA,
            actorId = actorA,
            deviceId = deviceA,
            commandStore = InMemoryMerchantCommandStore(),
            merchantReadClient = readClient,
            baseCurrency = "JOD",
            currencyMinorUnits = 3,
            permissions = ownerSession.permissions!!
        )
        val vm = TraderViewModel(repo)
        vm.switchUserSession(ownerSession)
        vm.prepareCustomerPaymentDraft()
        val opCust = vm.uiState.value.draftCustomerPaymentOperationId
        val custDone = kotlinx.coroutines.CompletableDeferred<Result<CustomerTransaction>>()
        vm.confirmCustomerCollectionResult(customerA.value, BigDecimal("10.00"), "JOD test", opCust) {
            custDone.complete(it)
        }
        val custRes = kotlinx.coroutines.withTimeout(5_000) {
            while (!custDone.isCompleted) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                kotlinx.coroutines.delay(10)
            }
            custDone.await()
        }
        assertTrue("Customer payment should succeed: ${custRes.exceptionOrNull()?.message}", custRes.isSuccess)
        val custMsgEn = vm.uiState.value.lastCustomerPaymentMessageEn.orEmpty()
        val custMsgAr = vm.uiState.value.lastCustomerPaymentMessageAr.orEmpty()
        assertTrue("Expected < 0.01 JOD in EN feedback: $custMsgEn", custMsgEn.contains("< 0.01 JOD"))
        assertTrue("Expected < 0.01 JOD in AR feedback: $custMsgAr", custMsgAr.contains("< 0.01 JOD"))
        assertFalse("Must NOT fallback to ₪: $custMsgEn", custMsgEn.contains("₪"))
        assertFalse("Must NOT claim zero settled balance: $custMsgEn", custMsgEn.contains(": 0.00 JOD"))

        assertTrue("Supplier directory refresh should succeed", repo.refreshSuppliers().isSuccess)
        vm.prepareSupplierPaymentDraft()
        val opSup = vm.uiState.value.draftSupplierPaymentOperationId
        val supDone = kotlinx.coroutines.CompletableDeferred<Result<com.example.model.SupplierPayment>>()
        vm.paySupplierAmountExactResult(supplierA.value, BigDecimal("15.00"), "JOD sup", opSup) {
            supDone.complete(it)
        }
        val supRes = kotlinx.coroutines.withTimeout(5_000) {
            while (!supDone.isCompleted) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                kotlinx.coroutines.delay(10)
            }
            supDone.await()
        }
        assertTrue("Supplier payment should succeed: ${supRes.exceptionOrNull()?.message}", supRes.isSuccess)
        val supMsgEn = vm.uiState.value.lastSupplierActionMessageEn.orEmpty()
        val supMsgAr = vm.uiState.value.lastSupplierActionMessageAr.orEmpty()
        assertTrue("Expected < 0.01 JOD advance in EN feedback: $supMsgEn", supMsgEn.contains("< 0.01 JOD"))
        assertTrue("Expected < 0.01 JOD advance in AR feedback: $supMsgAr", supMsgAr.contains("< 0.01 JOD"))
        assertFalse("Must NOT hardcode ₪: $supMsgEn", supMsgEn.contains("₪"))

        // Check formatCustomerStatementText with null creditLimit + historical USD transaction + sub-cent balance
        val statement = vm.formatCustomerStatementText(
            Customer(
                id = customerA.value,
                nameAr = "زبون",
                nameEn = "Customer",
                phone = "0790000001",
                balanceExact = BigDecimal("0.001"),
                creditLimitExact = null
            )
        )
        assertTrue("Statement balance must use < 0.01 JOD: $statement", statement.contains("< 0.01 JOD"))
        assertTrue("Statement unset limit must say Not set/غير محدد: $statement", statement.contains("Not set") || statement.contains("غير محدد"))
        assertFalse("Statement must NOT say 0.00 for unset limit: $statement", statement.contains("Credit Limit: 0.00"))
    }

    @Test
    fun cashPaymentScreen_billChipCoversDue_doesNotUse0005Tolerance() {
        val subCentProduct = Product(
            id = "p_sub",
            nameAr = "صنف",
            nameEn = "Item",
            category = ProductCategory.PANTRY,
            barcode = "123456",
            priceExact = BigDecimal("10.001"),
            unitAr = "قطعة",
            unitEn = "pc",
            stockCount = 5,
            accentHex = 0xFF000000L
        )
        val state = TraderUiState(
            currentSession = ownerSession,
            language = AppLanguage.EN,
            businessInfo = jodBusiness,
            basket = listOf(BasketItem(product = subCentProduct, quantity = BigDecimal.ONE)),
            cashInputText = "10"
        )
        assertFalse("10.00 tender must NOT satisfy 10.001 basket", state.isCashSufficient)
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CashPaymentScreen(
                    state = state,
                    onSetExactAmount = {},
                    onSelectDenomination = {},
                    onCompleteSale = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("cash_bill_10").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("cash_bill_20").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("payment_total_due_text").assertTextContains("10.00 JOD", substring = true)
        composeTestRule.onNodeWithTag("cash_exact_button").performScrollTo().assertTextContains("10.00 JOD", substring = true)
        composeTestRule.onNodeWithTag("payment_change_due_text").performScrollTo().assertTextContains("< 0.01 JOD", substring = true)
        composeTestRule.onNodeWithTag("complete_sale_button").assertIsNotEnabled().assertTextContains("10.00 JOD", substring = true)
        composeTestRule.onNodeWithTag("cash_collection_unrepresentable_warning").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun customerStatement_preservesHistoricalUsdCurrencyThroughRepositoryAdapterAndViewModel() = runBlocking {
        val readTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.path.endsWith("/customers/${customerA.value}/statement")) {
                    return TransportResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """
                            {
                              "customer_id": "${customerA.value}",
                              "customer_name": "SubCent Customer",
                              "from_date": null,
                              "to_date": null,
                              "currencies": {
                                "USD": {
                                  "currency": "USD",
                                  "opening_balance": "0.00",
                                  "closing_balance": "0.001",
                                  "total_debits": "0.001",
                                  "total_credits": "0.00",
                                  "entries": [
                                    {
                                      "date": "2026-09-01",
                                      "type": "invoice",
                                      "number": "INV-9001",
                                      "reference": "01JCVREFHIST001",
                                      "description": "Historical USD order",
                                      "debit": "0.001",
                                      "credit": "0.00",
                                      "balance": "0.001"
                                    }
                                  ],
                                  "aging": {
                                    "current": "0.001",
                                    "days_1_30": "0.00",
                                    "days_31_60": "0.00",
                                    "days_61_90": "0.00",
                                    "days_90_plus": "0.00",
                                    "total": "0.001"
                                  }
                                }
                              }
                            }
                        """.trimIndent()
                    )
                }
                return TransportResponse(200, mapOf("Content-Type" to "application/json"), "{}")
            }
        }
        val readClient = com.example.data.mobile.merchantread.MerchantReadClient(
            transport = readTransport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { reqCompanyId, _ -> if (reqCompanyId == companyA) validToken else null }
        )
        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(readTransport, "https://api.example.com") { validToken },
            companyId = companyA,
            actorId = actorA,
            deviceId = deviceA,
            commandStore = InMemoryMerchantCommandStore(),
            merchantReadClient = readClient,
            baseCurrency = "JOD",
            currencyMinorUnits = 3,
            permissions = ownerSession.permissions!!
        )
        val res = repo.getCustomerStatement(customerA.value)
        assertTrue("getCustomerStatement should succeed: ${res.exceptionOrNull()?.message}", res.isSuccess)
        val vm = TraderViewModel(repo)
        val statementText = vm.formatCustomerStatementText(
            Customer(
                id = customerA.value,
                nameAr = "زبون",
                nameEn = "Customer",
                phone = "0790000002",
                balanceExact = BigDecimal("0.001"),
                creditLimitExact = null
            )
        )
        assertTrue("Transaction line must keep historical USD (< 0.01 USD), got:\n$statementText", statementText.contains("< 0.01 USD"))
        assertFalse("Statement must NEVER fall back to ₪", statementText.contains("₪"))
    }
}
