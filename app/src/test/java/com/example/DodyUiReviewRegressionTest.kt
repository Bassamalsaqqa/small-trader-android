package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
import com.example.data.CurrentAuthScope
import com.example.data.OfflineDemoTraderRepository
import com.example.data.ProductionTraderRepository
import com.example.data.TraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.DiscountPreset
import com.example.model.PaymentAllocationInput
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.SaleDraft
import com.example.model.SettlementTarget
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.CustomerDetailScreen
import com.example.ui.screens.CustomerPaymentCollectionDialog
import com.example.ui.screens.InvoiceReviewScreen
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DodyUiReviewRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ownerSession = UserSession(
        id = "usr_owner",
        name = "Owner",
        username = "owner",
        role = UserRole.OWNER,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch",
        isDemoSession = true
    )

    @Test
    fun customerDetail_zeroLimitWithPositiveDebt_setsCreditUsageBarTo100Percent_andZeroDebtStays0Percent() {
        var currentCustomer by mutableStateOf(
            Customer(
                id = "c_zero_pos",
                nameAr = "زبون حد صفر",
                nameEn = "Zero Limit Debtor",
                phone = "0791112222",
                balanceExact = BigDecimal("42.50"),
                creditLimitExact = BigDecimal.ZERO
            )
        )

        composeTestRule.setContent {
            val state = TraderUiState(
                currentSession = ownerSession,
                businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 2),
                customers = listOf(currentCustomer),
                selectedCustomerForDetail = currentCustomer,
                language = AppLanguage.EN
            )
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerDetailScreen(
                    state = state,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Credit Limit: \u20660.00 JOD\u2069").assertIsDisplayed()
        composeTestRule.onNodeWithText("Available: \u20660.00 JOD\u2069").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("customer_detail_credit_usage_track"))
        composeTestRule.waitForIdle()

        val trackWidthPositive = composeTestRule
            .onNodeWithTag("customer_detail_credit_usage_track", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val fillWidthPositive = composeTestRule
            .onNodeWithTag("customer_detail_credit_usage_fill", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width

        assertTrue("Track width must be > 0", trackWidthPositive > 0f)
        assertEquals(
            "Zero credit limit + positive debt must fill bar to 100% (1f)",
            trackWidthPositive,
            fillWidthPositive,
            1.0f
        )

        // Zero debt against zero credit limit should remain 0f
        currentCustomer = currentCustomer.copy(balanceExact = BigDecimal.ZERO)
        composeTestRule.waitForIdle()
        val fillWidthZero = composeTestRule
            .onNodeWithTag("customer_detail_credit_usage_fill", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        assertEquals("Zero limit with zero debt must keep bar at 0%", 0f, fillWidthZero, 0.5f)
    }

    @Test
    fun cashPaymentScreen_remainingLabels_useStitchBidiIsolates() {
        val custWithLimit = Customer(
            id = "c_jod_limit",
            nameAr = "أحمد",
            nameEn = "Ahmad",
            phone = "0792223333",
            balanceExact = BigDecimal("12.50"),
            creditLimitExact = BigDecimal("50.00")
        )
        val custNoLimit = custWithLimit.copy(id = "c_jod_nolimit", creditLimitExact = null)
        val productJod = Product(
            id = "p_jod_1",
            nameAr = "منتج أردني",
            nameEn = "Jordan Item",
            category = ProductCategory.PANTRY,
            barcode = "62510001",
            priceExact = BigDecimal("10.00"),
            unitAr = "حبة",
            unitEn = "Pc",
            stockCount = 20,
            accentHex = 0xFF14804A
        )

        var selectedCustomer by mutableStateOf(custWithLimit)

        composeTestRule.setContent {
            val state = TraderUiState(
                currentSession = ownerSession,
                businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 2),
                products = listOf(productJod),
                basket = listOf(BasketItem(productJod, 1)),
                customers = listOf(custWithLimit, custNoLimit),
                selectedCustomerId = selectedCustomer.id,
                cashInputText = "20.00",
                language = AppLanguage.EN
            )
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CashPaymentScreen(
                    state = state,
                    onSetExactAmount = {},
                    onSelectDenomination = {},
                    onUpdateCashText = {},
                    onAssignCustomer = {},
                    onDismissCheckoutError = {},
                    onEditInvoice = {},
                    onCompleteSale = {},
                    onCompleteCreditSale = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("\u206620.00 JOD\u2069 - \u206610.00 JOD\u2069")
            .performScrollTo()
            .assertIsDisplayed()

        // Switch to credit mode and check every BiDi-isolated credit string
        composeTestRule.onNodeWithTag("payment_mode_credit_tab").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("\u206612.50 JOD\u2069").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("\u206650.00 JOD\u2069").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("\u206622.50 JOD\u2069").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("\u206612.50 JOD\u2069 + \u206610.00 JOD\u2069")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Within safe credit ceiling (\u206627.50 JOD\u2069 available)")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Full amount (\u206610.00 JOD\u2069) will be debited to customer account. Cash received: \u20660.00 JOD\u2069."
        ).performScrollTo().assertIsDisplayed()

        // Also check advisory unset-limit message uses isolated projected balance
        selectedCustomer = custNoLimit
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(
            "No credit limit set for customer (advisory) — Projected balance after sale: \u206622.50 JOD\u2069"
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xxhdpi")
    fun invoiceReviewScreen_usesExactBusinessCurrencyBidiIsolates_andUnavailableWhenTaxUnresolved() {
        val custJod = Customer(
            id = "c_rev_jod",
            nameAr = "رامي",
            nameEn = "Rami",
            phone = "0795556666",
            balanceExact = BigDecimal("12.50"),
            creditLimitExact = BigDecimal("100.00")
        )
        val resolvedProduct = Product(
            id = "p_rev_resolved",
            nameAr = "قهوة",
            nameEn = "Coffee",
            category = ProductCategory.BEVERAGES,
            barcode = "123456",
            priceExact = BigDecimal("25.00"),
            unitAr = "كيس",
            unitEn = "Bag",
            stockCount = 30,
            accentHex = 0xFF14804A
        )
        val unresolvedTaxProduct = Product(
            id = "p_rev_unresolved_tax",
            nameAr = "صنف بضريبة غير محددة",
            nameEn = "Tax Pending Item",
            category = ProductCategory.BEVERAGES,
            barcode = "999888",
            priceExact = BigDecimal("25.00"),
            unitAr = "كيس",
            unitEn = "Bag",
            stockCount = 15,
            accentHex = 0xFF14804A,
            taxRateId = "tax_pending",
            taxRateExact = null,
            saleCurrencyMinorUnits = 2
        )
        val savedDraft = SaleDraft(
            id = "draft_jod_1",
            titleAr = "مسودة أردنية",
            titleEn = "JOD Draft",
            timestamp = "2026-11-28 10:15",
            items = listOf(BasketItem(resolvedProduct, 2)),
            netTotalIls = 45.0
        )

        var currentBasket by mutableStateOf(listOf(BasketItem(resolvedProduct, 2)))
        val currentPreset = DiscountPreset.CUSTOM_ILS
        val customDiscount = "60.00"

        composeTestRule.setContent {
            val state = TraderUiState(
                currentSession = ownerSession,
                businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 2),
                products = listOf(resolvedProduct, unresolvedTaxProduct),
                basket = currentBasket,
                customers = listOf(custJod),
                selectedCustomerId = custJod.id,
                discountPreset = currentPreset,
                customDiscountText = customDiscount,
                savedDrafts = listOf(savedDraft),
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
                    onUpdateCustomDiscount = {},
                    onUpdateCustomerNote = {},
                    onAssignCustomer = {},
                    onProceedToCashPayment = {},
                    onResumeDraft = {},
                    onFormatQuotation = { "" }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Never leak hardcoded ₪ or ILS when active currency is JOD
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("ILS", substring = true).fetchSemanticsNodes().size)

        // Customer collapsed subtitle & dock subtotal/discount use BiDi + business currency
        composeTestRule.onNodeWithText("\u206612.50 JOD\u2069 • 0795556666").assertIsDisplayed()
        composeTestRule.onNodeWithText("Subtotal: \u206650.00 JOD\u2069").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discount: - \u206650.00 JOD\u2069").assertIsDisplayed()

        // Customer section starts expanded when selectedCustomer != null; check isolated current & projected balance
        assertTrue(composeTestRule.onAllNodesWithText("\u206612.50 JOD\u2069").fetchSemanticsNodes().size >= 2)

        // Scroll LazyColumn to invoice line and check unit price in business currency
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("invoice_line_p_rev_resolved"))
        composeTestRule.onNodeWithText("Qty: 2 • Unit: \u206625.00 JOD\u2069 (Bag)", substring = true)
            .assertIsDisplayed()

        // DiscountAndRoundingCard shows JOD chip, JOD input label, BiDi subtotal, BiDi discount pill, capped warning & net total
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("discount_chip_CUSTOM_ILS"))
        composeTestRule.onNodeWithTag("discount_chip_CUSTOM_ILS").assertTextContains("Fixed JOD")
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("custom_discount_input"))
        composeTestRule.onNodeWithText("Discount Amount (JOD)").assertIsDisplayed()
        composeTestRule.onNodeWithText("- \u206650.00 JOD\u2069").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Discount capped at subtotal (\u206650.00 JOD\u2069)"))
        composeTestRule.onNodeWithText("Discount capped at subtotal (\u206650.00 JOD\u2069)").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("\u20660.00 JOD\u2069").fetchSemanticsNodes().isNotEmpty())

        // SavedSaleDraftsCard formats Double netTotalIls with business currency JOD (not ₪)
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("toggle_saved_drafts_button"))
        composeTestRule.onNodeWithTag("toggle_saved_drafts_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("saved_sale_drafts_card"))
        composeTestRule.onNodeWithText("2 units • \u206645.00 JOD\u2069 • 2026-11-28 10:15").assertIsDisplayed()

        // Quotation fallback dialog formats net total in business currency without ₪
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("open_quotation_preview_button"))
        composeTestRule.onNodeWithTag("open_quotation_preview_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quotation_preview_text").assertTextContains("\u20660.00 JOD\u2069", substring = true)
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
        composeTestRule.onNodeWithText("Close").performClick()
        composeTestRule.waitForIdle()

        // Now switch basket to unresolved tax product -> subtotal, discount, net total, capped warning & quote show Unavailable
        currentBasket = listOf(BasketItem(unresolvedTaxProduct, 2))
        composeTestRule.waitForIdle()

        assertTrue(composeTestRule.onAllNodesWithText("Unavailable", substring = true).fetchSemanticsNodes().isNotEmpty())
        composeTestRule.onNodeWithText("Subtotal: Unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discount: Unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("proceed_to_cash_payment_button").assertIsNotEnabled()
        assertEquals(
            "Unresolved tax must NOT show 50.00 JOD subtotal/discount",
            0,
            composeTestRule.onAllNodesWithText("50.00 JOD", substring = true).fetchSemanticsNodes().size
        )

        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("open_quotation_preview_button"))
        composeTestRule.onNodeWithTag("open_quotation_preview_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quotation_preview_text")
            .assertTextContains("Estimated Net Total: Unavailable", substring = true)
    }

    @Test
    fun allocationCurrencyLabel_customerAndSupplierDialogs_useTargetDocumentCurrency() {
        val customer = Customer(
            id = "cust_alloc_lbl",
            nameAr = "عميل",
            nameEn = "Client",
            balanceExact = BigDecimal("100.00")
        )
        val custTarget = SettlementTarget(
            targetType = "invoice",
            targetId = "inv_usd_1",
            targetNumber = "INV-USD-1",
            businessDate = "2026-11-28",
            currency = "USD",
            currencyMinorUnits = 2,
            originalAmountExact = BigDecimal("45.51"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("45.51")
        )
        val supplier = Supplier(
            id = "sup_alloc_lbl",
            nameAr = "مورد",
            nameEn = "Vendor",
            balanceOwedExact = BigDecimal("100.00")
        )
        val supTarget = SettlementTarget(
            targetType = "purchase",
            targetId = "pur_eur_1",
            targetNumber = "BILL-EUR-1",
            businessDate = "2026-11-28",
            currency = "EUR",
            currencyMinorUnits = 2,
            originalAmountExact = BigDecimal("72.13"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("72.13")
        )

        var dialogMode by mutableStateOf("custEn")

        composeTestRule.setContent {
            SmallTraderTheme {
                when (dialogMode) {
                    "custEn", "custAr" -> CustomerPaymentCollectionDialog(
                        customer = customer,
                        lang = if (dialogMode == "custAr") AppLanguage.AR else AppLanguage.EN,
                        onDismiss = {},
                        onConfirmPayment = { _, _, _, _ -> },
                        onConfirmPaymentExact = { _, _, _, _, _ -> },
                        settlementTargets = listOf(custTarget),
                        currency = "JOD",
                        minorUnits = 2
                    )
                    "supEn", "supAr" -> PaySupplierDialog(
                        supplier = supplier,
                        lang = if (dialogMode == "supAr") AppLanguage.AR else AppLanguage.EN,
                        onDismiss = {},
                        onPaySupplier = { _, _, _ -> Result.success(SupplierPayment("sp", supplier.id, "2026-11-28", 0.0, "")) },
                        onPaySupplierExactWithResult = { _, _, _, _, _, _ -> },
                        settlementTargets = listOf(supTarget),
                        currency = "JOD",
                        minorUnits = 2
                    )
                }
            }
        }

        // Customer EN
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("target_checkbox_invoice_inv_usd_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Alloc USD").assertExists()
        assertEquals(0, composeTestRule.onAllNodesWithText("Alloc JOD").fetchSemanticsNodes().size)

        // Customer AR
        dialogMode = "custAr"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("المخصص USD").assertExists()
        assertEquals(0, composeTestRule.onAllNodesWithText("المخصص JOD").fetchSemanticsNodes().size)

        // Supplier EN
        dialogMode = "supEn"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("target_checkbox_purchase_pur_eur_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Alloc EUR").assertExists()
        assertEquals(0, composeTestRule.onAllNodesWithText("Alloc JOD").fetchSemanticsNodes().size)

        // Supplier AR
        dialogMode = "supAr"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("المخصص EUR").assertExists()
        assertEquals(0, composeTestRule.onAllNodesWithText("المخصص JOD").fetchSemanticsNodes().size)
    }

    @Test
    fun traderViewModel_offPageCustomerDetailPayment_withNullRunningBalance_clearsStaleDetailBalance() {
        val offPageCustomer = Customer(
            id = "01JCVOFFPAGECUST0000000001",
            nameAr = "زبون خارج الصفحة",
            nameEn = "Off-page Customer",
            phone = "0599000111",
            balanceExact = BigDecimal("120.00"),
            creditLimitExact = BigDecimal("500.00")
        )
        val delegate = OfflineDemoTraderRepository()
        val offPageRepo = object : TraderRepository by delegate {
            override fun getCustomers(): List<Customer> = emptyList()

            override suspend fun searchCustomers(query: String): Result<List<Customer>> =
                Result.success(listOf(offPageCustomer))

            override suspend fun refreshCustomers(): Result<Unit> =
                Result.success(Unit)

            override suspend fun recordCustomerPayment(
                customerId: String,
                amount: BigDecimal,
                note: String,
                paymentId: String,
                cashAccountId: String?,
                paymentDate: String?,
                allocations: List<PaymentAllocationInput>
            ): Result<CustomerTransaction> {
                return Result.success(
                    CustomerTransaction(
                        id = "pay_offpage_null_bal",
                        customerId = customerId,
                        date = "2026-11-28",
                        typeAr = "قبض نقدي",
                        typeEn = "Cash Receipt",
                        debitExact = BigDecimal.ZERO,
                        creditExact = amount,
                        runningBalanceExact = null,
                        note = note,
                        currency = "ILS"
                    )
                )
            }
        }

        val viewModel = TraderViewModel(offPageRepo)
        viewModel.searchCustomers("Off-page")
        val waitSearchUntil = System.currentTimeMillis() + 3_000L
        while (viewModel.uiState.value.customerSearchResults == null && System.currentTimeMillis() < waitSearchUntil) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(15)
        }
        viewModel.selectCustomerForDetail(offPageCustomer)
        assertEquals(BigDecimal("120.00"), viewModel.uiState.value.selectedCustomerForDetail?.balanceExact)

        val latch = CountDownLatch(1)
        var callbackResult: Result<CustomerTransaction>? = null
        viewModel.recordCustomerPayment(
            customerId = offPageCustomer.id,
            amount = BigDecimal("40.00"),
            note = "Partial cash",
            paymentId = "offpage-pay-1"
        ) { res ->
            callbackResult = res
            latch.countDown()
        }

        val deadline = System.currentTimeMillis() + 5_000L
        while (!latch.await(15, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
        }
        ShadowLooper.idleMainLooper()

        assertTrue("Payment must succeed", callbackResult?.isSuccess == true)
        val detail = viewModel.uiState.value.selectedCustomerForDetail
        assertNotNull(detail)
        assertNull(
            "Off-page customer detail must clear stale balanceExact when tx.runningBalanceExact is null",
            detail!!.balanceExact
        )
        assertTrue(detail.balanceIls.isNaN())
        val searchEntry = viewModel.uiState.value.customerSearchResults?.firstOrNull { it.id == offPageCustomer.id }
        if (searchEntry != null) {
            assertNull("Search result entry must also clear stale balanceExact", searchEntry.balanceExact)
        }
        val msgEn = viewModel.uiState.value.lastCustomerPaymentMessageEn.orEmpty()
        assertTrue("Feedback must report balance unavailable", msgEn.contains("unavailable", ignoreCase = true))
        assertFalse("Feedback must NOT report stale 120.00", msgEn.contains("120.00"))
    }

    @Test
    fun productionViewModel_offPageCustomerDetailPayment_withNullRunningBalance_clearsStaleDetailBalance() {
        val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
        val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
        val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val offPageCustomerId = "01JCV8X9M2NQW3TYZ4R5S6789Z"
        val token = "bearer_sample_token_length_min_20_chars_xyz"

        val offPageCustomer = Customer(
            id = offPageCustomerId,
            nameAr = "زبون إنتاج خارج الصفحة",
            nameEn = "Prod Off-page Customer",
            phone = "0599111222",
            balanceExact = BigDecimal("120.00"),
            creditLimitExact = BigDecimal("500.00")
        )

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                val path = request.path
                val method = request.method.name
                return when {
                    path.contains("/receipts/") && path.endsWith("/status") -> TransportResponse(
                        statusCode = 404,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"error":{"code":"NOT_FOUND","message":"Receipt not found"}}"""
                    )
                    method == "POST" && path.endsWith("/customers/$offPageCustomerId/payments") -> TransportResponse(
                        statusCode = 201,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"id":"01JCVPAYOFFPAGE0000000001","customer_id":"$offPageCustomerId","amount":"40.00","posted_at":"2026-11-28","balance":null}"""
                    )
                    method == "GET" && path.contains("/customers/$offPageCustomerId/statement") -> TransportResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"customer_id":"$offPageCustomerId","transactions":[]}"""
                    )
                    method == "GET" && path.endsWith("/customers/$offPageCustomerId") -> TransportResponse(
                        statusCode = 503,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"error":{"code":"UNAVAILABLE","message":"Detail refresh temporarily unavailable"}}"""
                    )
                    method == "GET" && path.contains("/customers") -> TransportResponse(
                        statusCode = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"company_id":"${companyId.value}","generated_at":"2026-11-28T10:00:00Z","next_cursor":null,"has_more":true,"customers":[]}"""
                    )
                    else -> TransportResponse(
                        statusCode = 404,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = """{"error":{"code":"NOT_FOUND","message":"Not mocked"}}"""
                    )
                }
            }
        }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(transport, "https://api.example.com") { token },
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = {
                CurrentAuthScope(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    token = token,
                    generation = 1L
                )
            },
            merchantReadClient = MerchantReadClient(
                transport = transport,
                baseOrigin = "https://api.example.com",
                tokenProvider = MerchantReadTokenProvider { reqComp, _ ->
                    if (reqComp == companyId) token else null
                }
            )
        )

        val viewModel = TraderViewModel(repo)
        viewModel.selectCustomerForDetail(offPageCustomer)
        ShadowLooper.idleMainLooper()

        assertTrue(
            "Off-page customer must remain absent from state.customers",
            viewModel.uiState.value.customers.none { it.id == offPageCustomerId }
        )
        assertEquals(BigDecimal("120.00"), viewModel.uiState.value.selectedCustomerForDetail?.balanceExact)

        val latch = CountDownLatch(1)
        var callbackResult: Result<CustomerTransaction>? = null
        viewModel.recordCustomerPayment(
            customerId = offPageCustomerId,
            amount = BigDecimal("40.00"),
            note = "Off-page production receipt",
            paymentId = viewModel.uiState.value.draftCustomerPaymentOperationId
        ) { res ->
            callbackResult = res
            latch.countDown()
        }

        val deadline = System.currentTimeMillis() + 5_000L
        while (!latch.await(15, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
        }
        ShadowLooper.idleMainLooper()

        assertTrue(
            "Production payment must succeed: ${callbackResult?.exceptionOrNull()}",
            callbackResult?.isSuccess == true
        )
        assertNull(
            "Production tx.runningBalanceExact must be null when detail refresh fails and response has null balance",
            callbackResult?.getOrNull()?.runningBalanceExact
        )
        val detail = viewModel.uiState.value.selectedCustomerForDetail
        assertNotNull(detail)
        assertNull(
            "Production VM off-page detail fallback must copy null tx.runningBalanceExact onto selectedCustomerForDetail",
            detail!!.balanceExact
        )
        assertTrue(detail.balanceIls.isNaN())
        val msgEn = viewModel.uiState.value.lastCustomerPaymentMessageEn.orEmpty()
        assertTrue("Feedback must report balance unavailable", msgEn.contains("unavailable", ignoreCase = true))
        assertFalse("Feedback must NOT report stale 120.00", msgEn.contains("120.00"))
    }
}
