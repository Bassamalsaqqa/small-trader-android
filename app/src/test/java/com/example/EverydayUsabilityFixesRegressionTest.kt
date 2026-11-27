package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.data.CurrentAuthScope
import com.example.data.OfflineDemoTraderRepository
import com.example.data.ProductionTraderRepository
import com.example.data.TraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.merchantread.CustomerStatementAging
import com.example.data.mobile.merchantread.CustomerStatementCurrencySummary
import com.example.data.mobile.merchantread.CustomerStatementEntry
import com.example.data.mobile.merchantread.CustomerStatementResponse
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.merchantread.ProductHistoryResponse
import com.example.data.mobile.merchantread.StockMovementItem
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.PaymentAllocationInput
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.navigation.MerchantAction
import com.example.ui.screens.CashPaymentScreen
import com.example.ui.screens.CustomerDetailScreen
import com.example.ui.screens.CustomerPickerModalSheet
import com.example.ui.screens.CustomersScreen
import com.example.ui.screens.NewSaleScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.ProductsCatalogScreen
import com.example.ui.shell.QuickActionSheet
import com.example.ui.shell.QuickPartyPickerDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EverydayUsabilityFixesRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class ControllableLiveRepo(
        val delegate: OfflineDemoTraderRepository = OfflineDemoTraderRepository(),
        private val initialBusinessInfo: BusinessInfo = BusinessInfo(defaultCurrency = "ILS", defaultCurrencyMinorUnits = 2)
    ) : TraderRepository by delegate {

        var scopeActive: Boolean = true
        var extraCustomers: MutableList<Customer> = mutableListOf()
        var extraSuppliers: MutableList<Supplier> = mutableListOf()
        var serverProducts: MutableList<Product> = delegate.getProducts().toMutableList()
        var searchProductsHandler: (suspend (String, Int) -> Result<List<Product>>)? = null
        var searchCustomersHandler: (suspend (String) -> Result<List<Customer>>)? = null
        var searchSuppliersHandler: (suspend (String) -> Result<List<Supplier>>)? = null
        var customerStatementHandler: (suspend (String) -> Result<CustomerStatementResponse>)? = null
        var productHistoryHandler: (suspend (String) -> Result<ProductHistoryResponse>)? = null

        override fun getBusinessInfo(): BusinessInfo = initialBusinessInfo
        override fun isScopeActive(): Boolean = scopeActive

        override fun getCustomers(): List<Customer> = delegate.getCustomers()
        override fun getSuppliers(): List<Supplier> = delegate.getSuppliers()

        override suspend fun searchProductsOnServer(query: String, limit: Int): Result<List<Product>> {
            val custom = searchProductsHandler
            if (custom != null) return custom(query, limit)
            return Result.success(
                serverProducts.filter {
                    it.nameAr.contains(query, ignoreCase = true) ||
                        it.nameEn.contains(query, ignoreCase = true) ||
                        it.barcode.contains(query, ignoreCase = true)
                }.take(limit)
            )
        }

        override suspend fun searchCustomers(query: String): Result<List<Customer>> {
            val custom = searchCustomersHandler
            if (custom != null) return custom(query)
            val all = delegate.getCustomers() + extraCustomers
            val cleanPhoneQ = query.replace("-", "").replace(" ", "")
            return Result.success(
                all.filter { c ->
                    c.nameAr.contains(query, ignoreCase = true) ||
                        c.nameEn.contains(query, ignoreCase = true) ||
                        (c.code?.contains(query, ignoreCase = true) == true) ||
                        (cleanPhoneQ.isNotBlank() && (c.phone?.replace("-", "")?.contains(cleanPhoneQ) == true))
                }
            )
        }

        override suspend fun searchSuppliers(query: String): Result<List<Supplier>> {
            val custom = searchSuppliersHandler
            if (custom != null) return custom(query)
            val all = delegate.getSuppliers() + extraSuppliers
            val cleanPhoneQ = query.replace("-", "").replace(" ", "")
            return Result.success(
                all.filter { s ->
                    s.nameAr.contains(query, ignoreCase = true) ||
                        s.nameEn.contains(query, ignoreCase = true) ||
                        (s.code?.contains(query, ignoreCase = true) == true) ||
                        (cleanPhoneQ.isNotBlank() && (s.phone?.replace("-", "")?.contains(cleanPhoneQ) == true))
                }
            )
        }

        override suspend fun getCustomerDetail(customerId: String): Result<Customer> {
            extraCustomers.firstOrNull { it.id == customerId }?.let { return Result.success(it) }
            return delegate.getCustomerDetail(customerId)
        }

        override suspend fun recordCustomerPayment(
            customerId: String,
            amount: BigDecimal,
            note: String,
            paymentId: String,
            cashAccountId: String?,
            paymentDate: String?,
            allocations: List<PaymentAllocationInput>
        ): Result<CustomerTransaction> {
            val idx = extraCustomers.indexOfFirst { it.id == customerId }
            if (idx >= 0) {
                val c = extraCustomers[idx]
                val nextBal = (c.balanceExact ?: BigDecimal.ZERO).subtract(amount)
                extraCustomers[idx] = c.copy(balanceExact = nextBal)
                val tx = CustomerTransaction(
                    id = "tx_$paymentId",
                    customerId = customerId,
                    date = "2026-11-27",
                    typeAr = "قبض نقدي",
                    typeEn = "Cash Receipt",
                    debitExact = BigDecimal.ZERO,
                    creditExact = amount,
                    runningBalanceExact = nextBal,
                    note = paymentId
                )
                return Result.success(tx)
            }
            return delegate.recordCustomerPayment(customerId, amount, note, paymentId, cashAccountId, paymentDate, allocations)
        }

        override suspend fun getCustomerStatement(
            customerId: String,
            fromDate: String?,
            toDate: String?
        ): Result<CustomerStatementResponse> {
            val custom = customerStatementHandler
            if (custom != null) return custom(customerId)
            return Result.success(
                CustomerStatementResponse(
                    customerId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C"),
                    customerName = "Customer",
                    fromDate = null,
                    toDate = null,
                    currencies = emptyMap()
                )
            )
        }

        override suspend fun getProductStockHistory(productId: String, limit: Int): Result<ProductHistoryResponse> {
            val custom = productHistoryHandler
            if (custom != null) return custom(productId)
            return Result.success(
                ProductHistoryResponse(
                    productId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D"),
                    movements = emptyList()
                )
            )
        }
    }

    private fun productionSession(
        role: UserRole = UserRole.OWNER,
        permissions: Set<String> = setOf(
            "pos.sale", "sales.invoice.create", "customers.view", "customers.manage",
            "money.receipt.create", "vendors.view", "vendors.manage", "money.vendor_payment.create",
            "inventory.product.manage", "inventory.stock.view"
        )
    ) = UserSession(
        id = "staff_1",
        name = "Test Operator",
        username = "operator",
        role = role,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch",
        isDemoSession = false,
        permissions = permissions
    )

    // -------------------------------------------------------------------------
    // 1. Cross-Screen Product Search + Keep Current Authorized Results During Debounce
    // -------------------------------------------------------------------------
    @Test
    fun testCrossScreenProductSearch_restoresQueries_keepsCurrentAuthorizedResultsDuringDebounce_withoutPermanentSpinner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repo = ControllableLiveRepo()
            val oliveOil = Product(
                id = "p_olive_99",
                nameAr = "زيت زيتون بكر",
                nameEn = "Extra Virgin Olive Oil",
                category = ProductCategory.PANTRY,
                barcode = "629000099",
                priceExact = BigDecimal("42.00"),
                unitAr = "لتر",
                unitEn = "Liter",
                stockCount = 20,
                accentHex = 0xFF1B5E20
            )
            val sugar = Product(
                id = "p_sugar_99",
                nameAr = "سكر أبيض ناعم",
                nameEn = "Fine White Sugar",
                category = ProductCategory.PANTRY,
                barcode = "629000088",
                priceExact = BigDecimal("14.00"),
                unitAr = "كغم",
                unitEn = "Kg",
                stockCount = 15,
                accentHex = 0xFF0D47A1
            )
            repo.serverProducts.addAll(listOf(oliveOil, sugar))

            val catalogDeferred = CompletableDeferred<Result<List<Product>>>()
            repo.searchProductsHandler = { query, _ ->
                if (query.equals("Sugar", ignoreCase = true)) {
                    catalogDeferred.await()
                } else {
                    Result.success(
                        repo.serverProducts.filter {
                            it.nameEn.contains(query, ignoreCase = true) ||
                                it.nameAr.contains(query, ignoreCase = true)
                        }
                    )
                }
            }

            val vm = TraderViewModel(repo)
            advanceUntilIdle()
            vm.switchUserSession(productionSession())
            vm.navigateTo(TraderScreen.NEW_SALE)
            advanceUntilIdle()

            // Search "Olive" on NEW_SALE and let it commit
            vm.updateSearchQuery("Olive")
            advanceTimeBy(300)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isSearchingProducts)
            assertEquals("Olive", vm.uiState.value.productSearchQuery)
            assertTrue(vm.uiState.value.filteredProducts.any { it.id == "p_olive_99" })

            // Refine "Olive" -> "Olive O" (during debounce before 250ms, matching authorized results remain visible)
            vm.updateSearchQuery("Olive O")
            assertTrue(vm.uiState.value.isSearchingProducts)
            assertTrue(
                "Current matching authorized results should stay visible during refinement debounce",
                vm.uiState.value.filteredProducts.any { it.id == "p_olive_99" }
            )

            // Jump to distinct query "Coca" before debounce completes: must NOT expose "Olive" server results
            vm.updateSearchQuery("Coca")
            assertTrue(vm.uiState.value.isSearchingProducts)
            assertFalse(vm.uiState.value.filteredProducts.any { it.id == "p_olive_99" })

            // Back to "Olive", let it settle
            vm.updateSearchQuery("Olive")
            advanceTimeBy(300)
            advanceUntilIdle()
            assertEquals("Olive", vm.uiState.value.saleSearchQuery)

            // Switch to PRODUCTS_CATALOG (has empty inventorySearchQuery): NO permanent spinner!
            vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.searchQuery)
            assertEquals("", vm.uiState.value.productSearchQuery)
            assertFalse(vm.uiState.value.isSearchingProducts)
            assertNull(vm.uiState.value.productSearchResults)

            // Start "Sugar" search on PRODUCTS_CATALOG, then switch back to NEW_SALE while in-flight
            vm.updateSearchQuery("Sugar")
            advanceTimeBy(300)
            assertTrue(vm.uiState.value.isSearchingProducts)

            vm.navigateTo(TraderScreen.NEW_SALE)
            // Restored NEW_SALE query "Olive" restarts search cleanly without keeping "Sugar"
            assertEquals("Olive", vm.uiState.value.searchQuery)
            advanceTimeBy(300)
            advanceUntilIdle()

            // Complete delayed "Sugar" response after switching back: must NEVER clobber NEW_SALE "Olive" results
            catalogDeferred.complete(Result.success(listOf(sugar)))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isSearchingProducts)
            assertEquals("Olive", vm.uiState.value.productSearchQuery)
            assertTrue(vm.uiState.value.filteredProducts.any { it.id == "p_olive_99" })
            assertFalse(vm.uiState.value.filteredProducts.any { it.id == "p_sugar_99" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Wire + VM Scope Invalidation Blocks Stale Reads, Results & Actions
    // -------------------------------------------------------------------------
    @Test
    fun testScopeInvalidation_wireAndVm_blocksStaleSearchResultsDetailWritesAndActions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val companyA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
            val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
            val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
            val custId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
            val prodId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
            val validToken = "bearer_scope_validation_token_20chars_min"

            var generation = 1L
            var activeCompany: ServerUlid? = companyA

            val transport = object : ReadClientTransport {
                override fun execute(request: TransportRequest): TransportResponse {
                    // Simulate scope generation change happening right before response returns
                    generation = 2L
                    return when {
                        request.path.contains("/statement") -> TransportResponse(
                            200,
                            mapOf("Content-Type" to "application/json"),
                            """{"customer_id":"${custId.value}","customer_name":"Test","from_date":null,"to_date":null,"currencies":{"ILS":{"currency":"ILS","opening_balance":"0.00","closing_balance":"100.00","total_debits":"100.00","total_credits":"0.00","entries":[{"date":"2026-11-18","type":"invoice","number":"INV-1","reference":null,"description":"Sale","debit":"100.00","credit":"0.00","balance":"100.00"}],"aging":{"current":"100.00","days_1_30":"0.00","days_31_60":"0.00","days_61_90":"0.00","days_90_plus":"0.00","total":"100.00"}}}}"""
                        )
                        request.path.contains("/movements") -> TransportResponse(
                            200,
                            mapOf("Content-Type" to "application/json"),
                            """{"product_id":"${prodId.value}","movements":[{"id":"01JCV8X9M2NQW3TYZ4R5S6789H","movement_type":"manual_adjustment","delta":"-2.0000","date":"2026-11-18","reason":"Expired","resulting_stock":"18.0000"}]}"""
                        )
                        else -> TransportResponse(200, emptyMap(), "{}")
                    }
                }
            }

            val scopeProvider: () -> CurrentAuthScope? = {
                activeCompany?.let { comp ->
                    CurrentAuthScope(
                        companyId = comp,
                        actorId = actorId,
                        deviceId = deviceId,
                        token = validToken,
                        generation = generation
                    )
                }
            }
            val tokenProvider = MerchantReadTokenProvider { reqComp, reqGen ->
                if (reqComp == activeCompany && (reqGen == null || reqGen == generation)) validToken else null
            }
            val readClient = MerchantReadClient(
                transport = transport,
                baseOrigin = "https://api.example.com",
                tokenProvider = tokenProvider
            )
            val apiClient = MobileMerchantApiClient(transport, "https://api.example.com", { validToken })
            val wireRepo = ProductionTraderRepository.forTesting(
                apiClient = apiClient,
                companyId = companyA,
                actorId = actorId,
                deviceId = deviceId,
                authScopeProvider = scopeProvider,
                merchantReadClient = readClient,
                ioDispatcher = dispatcher
            )

            // Wire guard on getCustomerStatement when generation mutates in-flight
            val stmtRes = wireRepo.getCustomerStatement(custId.value)
            assertTrue("Stale generation response must fail safely", stmtRes.isFailure)
            assertTrue(wireRepo.customerTransactionsFlow.value.isEmpty())

            // Reset generation for product history check -> mutates again mid-flight
            generation = 1L
            val histRes = wireRepo.getProductStockHistory(prodId.value, limit = 50)
            assertTrue("Stale generation stock history must fail safely", histRes.isFailure)
            assertTrue(wireRepo.stockMovementsFlow.value.isEmpty())

            // VM scope invalidation guard
            val vmRepo = ControllableLiveRepo()
            val delayedSearch = CompletableDeferred<Result<List<Product>>>()
            vmRepo.searchProductsHandler = { _, _ -> delayedSearch.await() }
            val vm = TraderViewModel(vmRepo)
            advanceUntilIdle()
            vm.switchUserSession(productionSession())
            vm.navigateTo(TraderScreen.NEW_SALE)
            vm.updateSearchQuery("Olive")
            advanceTimeBy(300)

            // Company scope becomes inactive before search returns
            vmRepo.scopeActive = false
            delayedSearch.complete(Result.success(vmRepo.delegate.getProducts()))
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isScopeInvalidated)
            assertTrue(vm.uiState.value.filteredProducts.isEmpty())
            assertTrue(vm.uiState.value.products.isEmpty())
            assertNull(vm.uiState.value.productSearchResults)

            // Stale actions must be blocked after scope invalidation
            vm.addProductToBasket(vmRepo.serverProducts.first(), 1)
            vm.openMerchantAction(MerchantAction.SALE)
            vm.openPaymentCollectionDialog(true)
            assertTrue(vm.uiState.value.basket.isEmpty())
            assertFalse(vm.uiState.value.isPaymentCollectionDialogOpen)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Production Category Filters Hide Categories That Cannot Match Mapped Products
    // -------------------------------------------------------------------------
    @Test
    fun testProductionCategoryFilters_hideCategoriesWithNoMappedProducts_andRevealMatchingCategory() {
        val unmappedProducts = listOf(
            Product(
                id = "p_all_1",
                nameAr = "قهوة عربية",
                nameEn = "Arabic Coffee",
                category = ProductCategory.ALL,
                barcode = "111",
                priceExact = BigDecimal("15.00"),
                unitAr = "كيس",
                unitEn = "Bag",
                stockCount = 10,
                accentHex = 0xFF14804A
            )
        )
        val prodNoCatsState = TraderUiState(
            currentSession = productionSession(),
            products = unmappedProducts,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                NewSaleScreen(
                    state = prodNoCatsState,
                    onSearchQueryChange = {},
                    onSelectCategory = {},
                    onAddProduct = {},
                    onDecrementProduct = {},
                    onRemoveProduct = {},
                    onClearBasket = {},
                    onOpenScanner = {},
                    onOpenCustomItem = {},
                    onProceedToInvoiceReview = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("category_chip_ALL").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_chip_PANTRY").assertDoesNotExist()
        composeTestRule.onNodeWithTag("category_chip_DAIRY").assertDoesNotExist()
        composeTestRule.onNodeWithTag("category_chip_ROASTERY").assertDoesNotExist()
        composeTestRule.onNodeWithTag("category_chip_BEVERAGES").assertDoesNotExist()
        composeTestRule.onNodeWithTag("category_chip_HOUSEHOLD").assertDoesNotExist()
    }

    @Test
    fun testProductsCatalogScreen_hidesCategoryStripWhenOnlyAllPresent_andShowsMappedCategory() {
        val catalogState = MutableStateFlow(
            TraderUiState(
                currentSession = productionSession(),
                products = listOf(
                    Product(
                        id = "p_unmapped",
                        nameAr = "صنف عام",
                        nameEn = "Generic Item",
                        category = ProductCategory.ALL,
                        barcode = "200",
                        priceExact = BigDecimal("5.00"),
                        unitAr = "حبة",
                        unitEn = "Pc",
                        stockCount = 5,
                        accentHex = 0xFF14804A
                    )
                ),
                language = AppLanguage.EN
            )
        )

        composeTestRule.setContent {
            val state = catalogState.value
            SmallTraderTheme {
                ProductsCatalogScreen(
                    state = state,
                    onSearchChange = {},
                    onCategorySelect = {},
                    onProductClick = {},
                    onAddProductToSale = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("catalog_category_ALL").assertDoesNotExist()
        composeTestRule.onNodeWithTag("catalog_category_PANTRY").assertDoesNotExist()
    }

    // -------------------------------------------------------------------------
    // 4. CashPaymentScreen Restored Credit Button, Change Due Formula, and JOD Labels
    // -------------------------------------------------------------------------
    @Test
    fun testCashPaymentScreen_creditButtonRestored_changeDueFormula_andJodCurrencyWithoutIlsLeak() {
        val jodCustomer = Customer(
            id = "cust_jod_1",
            nameAr = "شركة الأردن التجارية",
            nameEn = "Jordan Trading Co",
            phone = "0790123456",
            addressAr = "عمان",
            addressEn = "Amman",
            balanceExact = BigDecimal("12.50"),
            creditLimitExact = BigDecimal("50.00"),
            code = "JOD-01"
        )
        val prod = Product(
            id = "prod_jod_1",
            nameAr = "زيت نباتي",
            nameEn = "Vegetable Oil",
            category = ProductCategory.ALL,
            barcode = "333",
            priceExact = BigDecimal("10.00"),
            unitAr = "عبوة",
            unitEn = "Bottle",
            stockCount = 30,
            accentHex = 0xFF14804A,
            saleCurrencyMinorUnits = 3
        )
        val jodState = TraderUiState(
            currentSession = productionSession(),
            businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
            customers = listOf(jodCustomer),
            selectedCustomerId = jodCustomer.id,
            basket = listOf(BasketItem(prod, 1)),
            cashInputText = "20.00",
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(isRtl = false) {
                CashPaymentScreen(
                    state = jodState,
                    onSetExactAmount = {},
                    onSelectDenomination = {},
                    onKeypadPress = {},
                    onUpdateCashText = {},
                    onAssignCustomer = {},
                    onDismissCheckoutError = {},
                    onCompleteSale = {},
                    onCompleteCreditSale = {}
                )
            }
        }

        // Tender field uses JOD instead of ILS
        composeTestRule.onNodeWithText("Tender Amount (JOD)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tender Amount (ILS)").assertDoesNotExist()
        // Bill chips use JOD instead of ₪
        composeTestRule.onAllNodesWithText("JOD").apply {
            assertTrue("Bill denomination chips must render JOD currency tag", fetchSemanticsNodes().isNotEmpty())
        }
        // Restored Change Due footer breakdown row
        composeTestRule.onNodeWithText("20.00 JOD - 10.00 JOD").performScrollTo().assertIsDisplayed()

        // Restored localized Convert to Credit Sale button & click transitions to Credit mode
        val creditBtn = composeTestRule.onNodeWithTag("convert_to_credit_sale_button")
        creditBtn.performScrollTo()
        creditBtn.assertIsDisplayed()
        composeTestRule.onNodeWithText("Convert to Credit Sale").assertIsDisplayed()
        creditBtn.performClick()

        // Credit mode customer card & ledger note use JOD cleanly with zero ₪/ILS leak
        composeTestRule.onNodeWithText("12.50 JOD").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("50.00 JOD").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("22.50 JOD").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("12.50 JOD + 10.00 JOD").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Within safe credit ceiling (27.50 JOD available)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Full amount (10.00 JOD) will be debited to customer account. Cash received: 0.00 JOD."
        ).performScrollTo().assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("0.00 ₪", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun testCashPaymentScreen_customerPickerSheet_formatsJodBalances() {
        val cust = Customer(
            id = "cust_jod_picker",
            nameAr = "سامي",
            nameEn = "Sami",
            phone = "0799999999",
            addressAr = "إربد",
            addressEn = "Irbid",
            balanceExact = BigDecimal("18.75"),
            creditLimitExact = BigDecimal("40.00")
        )
        val jodState = TraderUiState(
            currentSession = productionSession(),
            businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
            customers = listOf(cust),
            basket = emptyList(),
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPickerModalSheet(
                    customers = listOf(cust),
                    selectedCustomerId = null,
                    basketNetTotalIls = 0.0,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onSelectCustomer = {},
                    state = jodState
                )
            }
        }
        composeTestRule.onNodeWithText("18.75 JOD").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("₪", substring = true).fetchSemanticsNodes().size)
    }

    // -------------------------------------------------------------------------
    // 5. Unauthorized Quick Collect & Quick Pay Gating (UI + ViewModel)
    // -------------------------------------------------------------------------
    @Test
    fun testQuickCollectAndPay_policyGating_hidesUnauthorizedButtonsAndBlocksVmActions() {
        val cashierNoReceiptOrPay = productionSession(
            role = UserRole.CASHIER,
            permissions = setOf("pos.sale", "sales.invoice.create", "customers.view", "vendors.view")
        )
        val cust = Customer(
            id = "c_debt",
            nameAr = "زبون مدين",
            nameEn = "Debtor Customer",
            phone = "0599000111",
            balanceExact = BigDecimal("100.00")
        )
        val uiState = TraderUiState(
            currentSession = cashierNoReceiptOrPay,
            customers = listOf(cust),
            selectedCustomerForDetail = cust,
            isQuickActionOpen = true,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                QuickActionSheet(
                    state = uiState,
                    onDismiss = {},
                    onAction = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("quick_sale").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_collect").assertDoesNotExist()
        composeTestRule.onNodeWithTag("quick_pay").assertDoesNotExist()

        // Also check ViewModel refuses unauthorized actions
        val vm = TraderViewModel(ControllableLiveRepo())
        vm.switchUserSession(cashierNoReceiptOrPay)
        vm.openMerchantAction(MerchantAction.COLLECT)
        assertNull(vm.uiState.value.pendingAction)
        vm.openMerchantAction(MerchantAction.PAY)
        assertNull(vm.uiState.value.pendingAction)
        vm.openPaymentCollectionForCustomer(cust)
        assertFalse(vm.uiState.value.isPaymentCollectionDialogOpen)
        vm.openPaymentCollectionDialog(true)
        assertFalse(vm.uiState.value.isPaymentCollectionDialogOpen)

        val sup = Supplier(id = "s_1", nameAr = "مورد", nameEn = "Supplier", balanceOwedExact = BigDecimal("50.00"))
        vm.openSupplierPayment(sup)
        assertNull(vm.uiState.value.pendingAction)
    }

    @Test
    fun testCustomersScreenAndDetail_hideCollectButtonsWithoutReceiptPermission() {
        val noCollectSession = productionSession(
            role = UserRole.CASHIER,
            permissions = setOf("pos.sale", "customers.view")
        )
        val debtor = Customer(
            id = "c_no_collect",
            nameAr = "خالد",
            nameEn = "Khaled",
            phone = "0599123123",
            balanceExact = BigDecimal("75.00")
        )
        val state = TraderUiState(
            currentSession = noCollectSession,
            customers = listOf(debtor),
            selectedCustomerForDetail = debtor,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomersScreen(
                    state = state,
                    onCustomerClick = {},
                    onOpenAddCustomerDialog = {},
                    onStartSaleForCustomer = {},
                    onQuickCollectPayment = {}
                )
                CustomerDetailScreen(
                    state = state,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("quick_collect_${debtor.id}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("collect_payment_button").assertDoesNotExist()
    }

    // -------------------------------------------------------------------------
    // 6. Quick Collect Party #35 Beyond First 30 + Phone/Code Disambiguation + Detail Retention
    // -------------------------------------------------------------------------
    @Test
    fun testQuickCollectPartyPicker_reachesCustomer35BeyondFirst30_disambiguatesAndPreservesDetailPostCollect() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repo = ControllableLiveRepo(
                initialBusinessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3)
            )
            val customer1SameName = repo.getCustomers().first().copy(
                id = "c_page1_dup",
                nameAr = "أحمد محمود",
                nameEn = "Ahmad Mahmoud",
                phone = "0599-111-001",
                code = "C-001",
                balanceExact = BigDecimal("40.00")
            )
            val customer35 = Customer(
                id = "01JCV8X9M2NQW3TYZ4R5S67035",
                nameAr = "أحمد محمود",
                nameEn = "Ahmad Mahmoud",
                phone = "0599-999-035",
                code = "C-035",
                addressAr = "طولكرم",
                addressEn = "Tulkarm",
                balanceExact = BigDecimal("120.00"),
                creditLimitExact = BigDecimal("500.00")
            )
            repo.extraCustomers.add(customer35)
            repo.searchCustomersHandler = { query ->
                val cleanQ = query.replace("-", "").replace(" ", "")
                if (cleanQ.contains("0599999035") || query.contains("035") || query.contains("Ahmad", ignoreCase = true)) {
                    Result.success(listOf(customer1SameName, customer35))
                } else {
                    Result.success(emptyList())
                }
            }

            val vm = TraderViewModel(repo)
            advanceUntilIdle()
            vm.switchUserSession(productionSession())
            vm.openMerchantAction(MerchantAction.COLLECT)
            assertEquals(MerchantAction.COLLECT, vm.uiState.value.pendingAction)

            // Drive QuickPartyPickerDialog with live VM state
            var selectedFromPicker: Customer? = null
            composeTestRule.setContent {
                val vmState by vm.uiState.collectAsState()
                SmallTraderTheme {
                    QuickPartyPickerDialog(
                        state = vmState.copy(customersHasMore = true),
                        action = MerchantAction.COLLECT,
                        onDismiss = vm::consumeAction,
                        onSearchCustomers = vm::searchCustomers,
                        onSearchSuppliers = vm::searchSuppliers,
                        onSelectCustomer = { selected ->
                            selectedFromPicker = selected
                            vm.consumeAction()
                            vm.openPaymentCollectionForCustomer(selected)
                        },
                        onSelectSupplier = {}
                    )
                }
            }

            // Search for customer #35 by phone without hyphens via UI input
            composeTestRule.onNodeWithTag("quick_party_search_input").performTextInput("0599999035")
            advanceTimeBy(300)
            advanceUntilIdle()

            // Wait for Compose recomposition
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("quick_party_option_${customer35.id}").assertIsDisplayed()
            composeTestRule.onNodeWithText("C-035").assertIsDisplayed()
            composeTestRule.onNodeWithTag("quick_party_option_${customer35.id}").performClick()

            assertEquals(customer35.id, selectedFromPicker?.id)
            assertTrue(vm.uiState.value.isPaymentCollectionDialogOpen)
            assertEquals(customer35.id, vm.uiState.value.selectedCustomerForDetail?.id)

            // Confirm collection for customer #35 (who is NOT in page-1 vm.uiState.value.customers!)
            val collectionDone = CompletableDeferred<Boolean>()
            vm.confirmCustomerCollection(
                customerId = customer35.id,
                amount = BigDecimal("20.00"),
                note = "دفعة 35",
                paymentId = "pay_35_001"
            ) { ok ->
                collectionDone.complete(ok)
            }
            val deadlineNs = System.nanoTime() + 5_000_000_000L
            while (!collectionDone.isCompleted && System.nanoTime() < deadlineNs) {
                advanceUntilIdle()
                if (!collectionDone.isCompleted) Thread.sleep(10)
            }
            assertTrue(collectionDone.isCompleted && collectionDone.getCompleted())
            advanceUntilIdle()

            assertEquals(TraderScreen.CUSTOMER_DETAIL, vm.uiState.value.currentScreen)
            assertNotNull(
                "Customer #35 must remain in selectedCustomerForDetail after collection",
                vm.uiState.value.selectedCustomerForDetail
            )
            assertEquals(customer35.id, vm.uiState.value.selectedCustomerForDetail?.id)
            assertEquals(BigDecimal("100.000"), vm.uiState.value.selectedCustomerForDetail?.balanceExact)
            assertTrue(vm.uiState.value.lastCustomerPaymentMessageEn.orEmpty().contains("20.00 JOD"))
            assertTrue(vm.uiState.value.lastCustomerPaymentMessageEn.orEmpty().contains("100.00 JOD"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testQuickPayPartyPicker_reachesSupplier35BeyondFirst30_disambiguatesCodeAndPhone() {
        val supplier1 = Supplier(
            id = "s_001",
            nameAr = "مورد الشرق",
            nameEn = "East Supplier",
            phone = "0598-111-001",
            code = "SUP-001",
            balanceOwedExact = BigDecimal("30.00")
        )
        val supplier35 = Supplier(
            id = "01JCV8X9M2NQW3TYZ4R5S69035",
            nameAr = "مورد الشرق",
            nameEn = "East Supplier",
            phone = "0598-999-035",
            code = "SUP-035",
            companyAr = "فرع الجنوب",
            companyEn = "South Branch",
            balanceOwedExact = BigDecimal("410.00")
        )
        var state by mutableStateOf(
            TraderUiState(
                currentSession = productionSession(),
                businessInfo = BusinessInfo(defaultCurrency = "JOD", defaultCurrencyMinorUnits = 3),
                suppliers = listOf(supplier1),
                suppliersHasMore = true,
                language = AppLanguage.EN
            )
        )
        var pickedSupplier: Supplier? = null
        var searchedQuery = ""

        composeTestRule.setContent {
            SmallTraderTheme {
                QuickPartyPickerDialog(
                    state = state,
                    action = MerchantAction.PAY,
                    onDismiss = {},
                    onSearchCustomers = {},
                    onSearchSuppliers = { q ->
                        searchedQuery = q
                        state = state.copy(
                            supplierSearchQuery = q,
                            supplierSearchResults = listOf(supplier1, supplier35),
                            isSearchingSuppliers = false
                        )
                    },
                    onSelectCustomer = {},
                    onSelectSupplier = { pickedSupplier = it }
                )
            }
        }
        composeTestRule.onNodeWithTag("quick_party_search_input").performTextInput("0598999035")
        assertTrue(searchedQuery.contains("0598999035"))
        composeTestRule.onNodeWithTag("quick_party_option_${supplier35.id}").assertIsDisplayed()
        composeTestRule.onNodeWithText("SUP-035").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_party_option_${supplier35.id}").performClick()
        assertEquals(supplier35.id, pickedSupplier?.id)
    }

    // -------------------------------------------------------------------------
    // 7. Customer Statement & Product History Error/Retry, 403 Scope Safety, and Generation Isolation
    // -------------------------------------------------------------------------
    @Test
    fun testDetailStatementAndStockHistory_403ForbiddenSafeError_networkRetry_andStaleGenerationIsolation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
            val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
            val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
            val custAId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
            val custBId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E")
            val prodId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
            val validToken = "bearer_403_and_retry_token_min20_chars"

            val transport = object : ReadClientTransport {
                val responses = ArrayDeque<TransportResponse>()
                override fun execute(request: TransportRequest): TransportResponse =
                    responses.removeFirst()
            }
            // 1) Customer A statement 403 Forbidden with raw secret message -> must NOT expose raw text or invalidate scope
            transport.responses.addLast(
                TransportResponse(
                    403,
                    emptyMap(),
                    """{"error":{"code":"FORBIDDEN","message":"secret internal sql trace diagnostic"}}"""
                )
            )
            // 2) Customer A detail GET fails 403 too (selectCustomerForDetail launches both)
            transport.responses.addLast(TransportResponse(403, emptyMap(), """{}"""))
            // 3) Retry Customer A statement -> 200 OK
            transport.responses.addLast(
                TransportResponse(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    """{"customer_id":"${custAId.value}","customer_name":"Cust A","from_date":null,"to_date":null,"currencies":{"ILS":{"currency":"ILS","opening_balance":"0.00","closing_balance":"55.00","total_debits":"55.00","total_credits":"0.00","entries":[{"date":"2026-11-18","type":"invoice","number":"INV-1","reference":null,"description":"Sale","debit":"55.00","credit":"0.00","balance":"55.00"}],"aging":{"current":"55.00","days_1_30":"0.00","days_31_60":"0.00","days_61_90":"0.00","days_90_plus":"0.00","total":"55.00"}}}}"""
                )
            )
            // 4) Product history 403 Forbidden -> must NOT invalidate scope or leak secret text
            transport.responses.addLast(
                TransportResponse(
                    403,
                    emptyMap(),
                    """{"error":{"code":"FORBIDDEN","message":"secret product sql diagnostic"}}"""
                )
            )

            val scope = CurrentAuthScope(companyId, actorId, deviceId, validToken, 1L)
            val wireRepo = ProductionTraderRepository.forTesting(
                apiClient = MobileMerchantApiClient(transport, "https://api.example.com", { validToken }),
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                authScopeProvider = { scope },
                merchantReadClient = MerchantReadClient(
                    transport = transport,
                    baseOrigin = "https://api.example.com",
                    tokenProvider = MerchantReadTokenProvider { _, _ -> validToken }
                ),
                ioDispatcher = dispatcher
            )

            val vm = TraderViewModel(wireRepo)
            advanceUntilIdle()
            vm.switchUserSession(productionSession())

            val custA = Customer(id = custAId.value, nameAr = "زبون أ", nameEn = "Cust A", balanceExact = BigDecimal("55.00"))
            vm.selectCustomerForDetail(custA)
            advanceUntilIdle()

            assertFalse("HTTP 403 on statement must NOT invalidate active company scope", vm.uiState.value.isScopeInvalidated)
            assertFalse(vm.uiState.value.isLoadingCustomerStatement)
            assertNotNull(vm.uiState.value.customerStatementError)
            assertFalse(vm.uiState.value.customerStatementError.orEmpty().contains("secret internal sql trace diagnostic"))
            assertNull(vm.uiState.value.selectedCustomerStatement)

            // Retry loads statement cleanly
            vm.loadCustomerStatement(custA.id)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoadingCustomerStatement)
            assertNull(vm.uiState.value.customerStatementError)
            assertNotNull(vm.uiState.value.selectedCustomerStatement)

            // Product stock history 403 Forbidden check
            val prod = Product(
                id = prodId.value,
                nameAr = "منتج",
                nameEn = "Product",
                category = ProductCategory.ALL,
                barcode = "999",
                priceExact = BigDecimal("10.00"),
                unitAr = "حبة",
                unitEn = "Pc",
                stockCount = 5,
                accentHex = 0xFF14804A
            )
            vm.selectProductForDetail(prod)
            advanceUntilIdle()

            assertFalse("HTTP 403 on product history must NOT invalidate scope", vm.uiState.value.isScopeInvalidated)
            assertFalse(vm.uiState.value.isLoadingProductHistory)
            assertNotNull(vm.uiState.value.productHistoryError)
            assertFalse(vm.uiState.value.productHistoryError.orEmpty().contains("secret product sql diagnostic"))

            // Generation race check: slow failure for Customer A after switching to Customer B cannot corrupt Customer B
            val slowA = CompletableDeferred<Result<CustomerStatementResponse>>()
            val fastB = CompletableDeferred<Result<CustomerStatementResponse>>()
            val raceRepo = ControllableLiveRepo()
            raceRepo.customerStatementHandler = { cid -> if (cid == custAId.value) slowA.await() else fastB.await() }
            val raceVm = TraderViewModel(raceRepo)
            advanceUntilIdle()
            raceVm.switchUserSession(productionSession())

            raceVm.selectCustomerForDetail(custA)
            val custB = Customer(id = custBId.value, nameAr = "زبون ب", nameEn = "Cust B", balanceExact = BigDecimal("20.00"))
            raceVm.selectCustomerForDetail(custB)
            fastB.complete(
                Result.success(
                    CustomerStatementResponse(
                        customerId = custBId,
                        customerName = "Cust B",
                        fromDate = null,
                        toDate = null,
                        currencies = mapOf(
                            "ILS" to CustomerStatementCurrencySummary(
                                currency = "ILS",
                                openingBalance = BigDecimal.ZERO,
                                closingBalance = BigDecimal("20.00"),
                                totalDebits = BigDecimal("20.00"),
                                totalCredits = BigDecimal.ZERO,
                                entries = emptyList(),
                                aging = CustomerStatementAging(
                                    BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("20.00")
                                )
                            )
                        )
                    )
                )
            )
            advanceUntilIdle()
            slowA.complete(Result.failure(IOException("late failure for A")))
            advanceUntilIdle()

            assertEquals(custBId.value, raceVm.uiState.value.selectedCustomerStatement?.customerId)
            assertNull(raceVm.uiState.value.customerStatementError)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testCustomerStatement_composeErrorRetry_clearsSpinnerAndNeverFallsBackToDemoData() {
        val cust = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789C",
            nameAr = "زبون الخطأ",
            nameEn = "Error Customer",
            balanceExact = BigDecimal("100.00")
        )
        val errState = TraderUiState(
            currentSession = productionSession(),
            customers = listOf(cust),
            selectedCustomerForDetail = cust,
            customerStatementError = "تعذر الاتصال بالخادم أثناء تحميل كشف حساب الزبون | Network error while loading customer statement",
            isLoadingCustomerStatement = false,
            customerTransactions = listOf(
                CustomerTransaction(
                    id = "stale_tx_1",
                    customerId = cust.id,
                    date = "2026-11-01",
                    typeAr = "سجل محلي قديم",
                    typeEn = "Stale Local Fallback Tx",
                    debitExact = BigDecimal("99.00"),
                    creditExact = BigDecimal.ZERO,
                    runningBalanceExact = BigDecimal("99.00"),
                    note = "STALE-TX"
                )
            ),
            language = AppLanguage.EN
        )

        var retriedCustomerId: String? = null
        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerDetailScreen(
                    state = errState,
                    onBack = {},
                    onOpenPaymentDialog = {},
                    onStartSaleForCustomer = {},
                    onRetryStatement = { retriedCustomerId = it }
                )
            }
        }
        composeTestRule.onNodeWithTag("customer_statement_loading").assertDoesNotExist()
        composeTestRule.onNodeWithTag("customer_statement_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stale Local Fallback Tx", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("customer_statement_retry_button").performClick()
        assertEquals(cust.id, retriedCustomerId)
    }

    @Test
    fun testProductStockHistory_composeErrorRetry_clearsSpinnerAndNeverFallsBackToDemoData() {
        val prod = Product(
            id = "01JCV8X9M2NQW3TYZ4R5S6789D",
            nameAr = "صنف الخطأ",
            nameEn = "Error Product",
            category = ProductCategory.ALL,
            barcode = "888",
            priceExact = BigDecimal("12.00"),
            unitAr = "حبة",
            unitEn = "Pc",
            stockCount = 10,
            accentHex = 0xFF14804A
        )
        val errState = TraderUiState(
            currentSession = productionSession(),
            products = listOf(prod),
            selectedProductForDetail = prod,
            productHistoryError = "تعذر الاتصال بالخادم أثناء تحميل حركات المخزون | Network error while loading stock movement history",
            isLoadingProductHistory = false,
            stockMovements = listOf(
                StockMovement(
                    id = "stale_sm_1",
                    productId = prod.id,
                    date = "2026-11-01",
                    deltaExact = BigDecimal("-5"),
                    reasonAr = "حركة محلية قديمة",
                    reasonEn = "Stale Local Fallback Movement",
                    resultingStockExact = BigDecimal("5")
                )
            ),
            language = AppLanguage.EN
        )

        var retriedProductId: String? = null
        composeTestRule.setContent {
            SmallTraderTheme {
                ProductDetailScreen(
                    state = errState,
                    onBack = {},
                    onUpdatePrice = { _, _ -> },
                    onAdjustStock = { _, _, _ -> },
                    onRetryHistory = { retriedProductId = it }
                )
            }
        }
        composeTestRule.onNodeWithTag("product_stock_history_loading").assertDoesNotExist()
        composeTestRule.onAllNodes(
            hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        )[0].performScrollToNode(hasTestTag("product_stock_history_error"))
        composeTestRule.onNodeWithTag("product_stock_history_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stale Local Fallback Movement", substring = true).assertDoesNotExist()
        composeTestRule.onAllNodes(
            hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        )[0].performScrollToNode(hasTestTag("product_stock_history_retry_button"))
        composeTestRule.onNodeWithTag("product_stock_history_retry_button").performClick()
        assertEquals(prod.id, retriedProductId)
    }
}
