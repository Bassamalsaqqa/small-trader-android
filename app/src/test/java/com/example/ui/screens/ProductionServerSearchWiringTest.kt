package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CurrentAuthScope
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ProductDefaultUnit
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionServerSearchWiringTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"
    private val validScope = CurrentAuthScope(companyId, actorId, deviceId, token = validToken, generation = 1L)

    private val session = UserSession(
        id = "session_test",
        name = "Trader Owner",
        username = "user_01",
        role = UserRole.OWNER,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch"
    )

    private val baseUnit = ProductBaseUnit(
        id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E"),
        code = "PCS",
        name = "حبة"
    )
    private val defaultUnit = ProductDefaultUnit(
        unitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E"),
        productUnitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789P"),
        conversionToBase = PositiveDecimalQuantity("1.000000"),
        salePrice = MoneyAmount("10.00")
    )

    private fun createProduct(
        id: String,
        sku: String,
        name: String,
        price: String = "10.00",
        qty: String = "100.000000",
        barcode: String? = null
    ): ProductCatalogItem = ProductCatalogItem(
        id = ServerUlid.parse(id),
        sku = sku,
        barcode = barcode,
        name = name,
        unitPrice = MoneyAmount(price),
        taxRate = TaxPercentage("16.00"),
        taxRateId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789T"),
        isTracked = true,
        availableQuantity = NonnegativeDecimalQuantity(qty),
        baseUnit = baseUnit,
        defaultUnit = defaultUnit.copy(salePrice = MoneyAmount(price)),
        units = emptyList()
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        ProductionTraderRepository.clearInFlightForTest()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ProductionTraderRepository.clearInFlightForTest()
    }

    @Test
    fun testLargeCatalog50k_boundedFirst30_andServerSearchBeyondFirst30_cartSurvivesClear() = runTest(testDispatcher) {
        val totalProductsOnServer = 50_000
        val totalCustomersOnServer = 10_000

        // Simulated first 30 products on server
        val first30Products = (1..30).map { i ->
            createProduct(
                id = "01JMBPRD0%017d".format(i),
                sku = "PRD-%05d".format(i),
                name = "صنف محلي رقم $i",
                price = "10.00",
                barcode = "629104820%04d".format(i)
            )
        }

        // Special items located far beyond the first 30 (e.g. index 35000, 45000, 49999)
        val arabicSpecialProduct = createProduct(
            id = "01JMBPRD000000000000045000",
            sku = "PRD-45000",
            name = "زيت زيتون بكر ممتاز 1 لتر",
            price = "35.00",
            qty = "50.000000",
            barcode = "6291048204500"
        )
        val englishSpecialProduct = createProduct(
            id = "01JMBPRD000000000000035000",
            sku = "PRD-35000",
            name = "Flour Premium 1kg",
            price = "5.00",
            qty = "200.000000",
            barcode = "6291048203500"
        )
        val skuBarcodeProduct = createProduct(
            id = "01JMBPRD000000000000049999",
            sku = "SKU-TARGET-99",
            name = "مسحوق غسيل أوتوماتيك 5 كغم",
            price = "45.00",
            qty = "80.000000",
            barcode = "6291048209999"
        )

        val searchedQueries = mutableListOf<String>()
        val productPageRequests = AtomicInteger(0)

        val mockTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.GET && request.path.endsWith("/products")) {
                    val search = request.queryParams["search"]
                    val limit = request.queryParams["limit"]?.toIntOrNull() ?: 30
                    if (search != null) {
                        searchedQueries.add(search)
                        val matching = when {
                            search.contains("ز") -> listOf(arabicSpecialProduct)
                            search.contains("flour", ignoreCase = true) || search.contains("f", ignoreCase = true) -> listOf(englishSpecialProduct)
                            search.contains("SKU-TARGET") || search.contains("6291048209999") || search.contains("PRD-49999") -> listOf(skuBarcodeProduct)
                            else -> emptyList()
                        }
                        val itemsJson = matching.joinToString(",") { it.toJsonString() }
                        return TransportResponse(
                            200,
                            emptyMap(),
                            """{"company_id":"${companyId.value}","catalog_snapshot_version":"v1","generated_at":"2026-11-22T10:00:00Z","products":[$itemsJson],"has_more":false,"next_cursor":null}"""
                        )
                    } else {
                        productPageRequests.incrementAndGet()
                        val itemsJson = first30Products.joinToString(",") { it.toJsonString() }
                        return TransportResponse(
                            200,
                            emptyMap(),
                            """{"company_id":"${companyId.value}","catalog_snapshot_version":"v1","generated_at":"2026-11-22T10:00:00Z","products":[$itemsJson],"has_more":true,"next_cursor":"cur_p2_offset30"}"""
                        )
                    }
                }
                return TransportResponse(200, emptyMap(), """{"company_id":"${companyId.value}","generated_at":"2026-11-22T10:00:00Z","customers":[],"has_more":false,"next_cursor":null}""")
            }
        }

        val apiClient = MobileMerchantApiClient(mockTransport, "https://api.example.com") { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = InMemoryMerchantCommandStore(),
            authScopeProvider = { validScope },
            ioDispatcher = testDispatcher
        )

        // 1. Initial catalog fetch: must only fetch bounded first 30, never eager all 50k
        repo.refreshProducts()
        advanceUntilIdle()
        val vm = TraderViewModel(repo)
        advanceUntilIdle()
        assertEquals("Initial catalog fetch must request exactly 1 bounded page (30 items)", 1, productPageRequests.get())
        assertEquals("Catalog must contain first 30 items initially", 30, vm.uiState.value.products.size)
        assertTrue("Catalog must indicate has_more=true for 50k items", vm.uiState.value.productsHasMore)

        // 2. Search on server with 1-char Arabic ("ز")
        vm.updateSearchQuery("ز")
        advanceTimeBy(350)
        advanceUntilIdle()
        val filteredArabic = vm.uiState.value.filteredProducts
        assertEquals("Search with 1-char Arabic must return the special product located far beyond first 30", 1, filteredArabic.size)
        assertEquals("PRD-45000", filteredArabic[0].code)
        assertEquals("زيت زيتون بكر ممتاز 1 لتر", filteredArabic[0].nameAr)

        // Add to basket
        vm.addProductToBasket(filteredArabic[0], 2)
        assertEquals("Basket must have 1 item", 1, vm.uiState.value.basket.size)
        assertEquals(2, vm.uiState.value.basket[0].quantity.toInt())

        // 3. Search on server with 1-char English ("f")
        vm.updateSearchQuery("f")
        advanceTimeBy(350)
        advanceUntilIdle()
        val filteredEnglish = vm.uiState.value.filteredProducts
        assertEquals("Search with 1-char English must return the English item located far beyond first 30", 1, filteredEnglish.size)
        assertEquals("Flour Premium 1kg", filteredEnglish[0].nameEn)

        // Add second product to basket
        vm.addProductToBasket(filteredEnglish[0], 1)
        assertEquals("Basket must now have 2 items", 2, vm.uiState.value.basket.size)

        // 4. Search by SKU / Barcode / Code ("6291048209999")
        vm.updateSearchQuery("6291048209999")
        advanceTimeBy(350)
        advanceUntilIdle()
        val filteredBarcode = vm.uiState.value.filteredProducts
        assertEquals("Search by Barcode must return matching item beyond first 30", 1, filteredBarcode.size)
        assertEquals("SKU-TARGET-99", filteredBarcode[0].sku)

        // Add third product to basket
        vm.addProductToBasket(filteredBarcode[0], 3)
        assertEquals("Basket must now have 3 items", 3, vm.uiState.value.basket.size)

        // 5. CRITICAL INVARIANT: Clearing search query must NOT destroy or clear cart contents!
        vm.clearSearchQuery()
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals("Search query must be empty", "", vm.uiState.value.searchQuery)
        assertEquals("Filtered products resets to first 30 products", 30, vm.uiState.value.filteredProducts.size)

        // Basket must STILL contain all 3 added items with their quantities and prices!
        assertEquals("Basket must still contain 3 items after search clear", 3, vm.uiState.value.basket.size)
        assertEquals("Item 1 ID preserved", "01JMBPRD000000000000045000", vm.uiState.value.basket[0].product.id)
        assertEquals("Item 1 qty preserved", 2, vm.uiState.value.basket[0].quantity.toInt())
        assertEquals("Item 2 ID preserved", "01JMBPRD000000000000035000", vm.uiState.value.basket[1].product.id)
        assertEquals("Item 3 ID preserved", "01JMBPRD000000000000049999", vm.uiState.value.basket[2].product.id)
    }

    @Test
    fun testCustomerSearchBeyondFirst30_andCustomerSelectionSurvivesSearchClear() = runTest(testDispatcher) {
        val specialCustomer = CustomerDirectoryItem(
            id = ServerUlid.parse("01JMBCST000000000000008500"),
            code = "CUST-08500",
            name = "طارق العسلي للتجارة",
            phone = "0599887766",
            isWalkIn = false
        )

        var customerSearchedQuery: String? = null
        val mockTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.GET && request.path.endsWith("/customers")) {
                    val search = request.queryParams["search"]
                    customerSearchedQuery = search
                    if (search != null && (search.contains("طارق") || search.contains("0599887766"))) {
                        val itemsJson = specialCustomer.toJsonString()
                        return TransportResponse(
                            200,
                            emptyMap(),
                            """{"company_id":"${companyId.value}","generated_at":"2026-11-22T10:00:00Z","customers":[$itemsJson],"has_more":false,"next_cursor":null}"""
                        )
                    }
                    val itemsJson = (1..30).joinToString(",") { i ->
                        """{"id":"01JMBCST0%017d","code":"CUST-%05d","name":"زبون عادي $i","is_walk_in":false}""".format(i, i)
                    }
                    return TransportResponse(
                        200,
                        emptyMap(),
                        """{"company_id":"${companyId.value}","generated_at":"2026-11-22T10:00:00Z","customers":[$itemsJson],"has_more":true,"next_cursor":"cur_c2"}"""
                    )
                }
                return TransportResponse(200, emptyMap(), """{"company_id":"${companyId.value}","catalog_snapshot_version":"v1","generated_at":"2026-11-22T10:00:00Z","products":[],"has_more":false,"next_cursor":null}""")
            }
        }

        val apiClient = MobileMerchantApiClient(mockTransport, "https://api.example.com") { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = InMemoryMerchantCommandStore(),
            authScopeProvider = { validScope },
            ioDispatcher = testDispatcher
        )

        // Initial customers load: first 30
        repo.refreshCustomers()
        advanceUntilIdle()
        val vm = TraderViewModel(repo)
        advanceUntilIdle()
        assertEquals(30, vm.uiState.value.customers.size)
        assertTrue(vm.uiState.value.customersHasMore)

        // Search customers on server
        vm.searchCustomers("طارق")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals("طارق", customerSearchedQuery)
        val searchResults = vm.uiState.value.customerSearchResults
        assertNotNull(searchResults)
        assertEquals(1, searchResults!!.size)
        assertEquals("01JMBCST000000000000008500", searchResults[0].id)
        assertEquals("طارق العسلي للتجارة", searchResults[0].nameAr)

        // Select the customer for sale
        vm.assignCustomerToSale(searchResults[0])
        assertEquals("01JMBCST000000000000008500", vm.uiState.value.selectedCustomer?.id)

        // Clearing customer search must NOT unassign selected customer!
        vm.searchCustomers("")
        advanceTimeBy(350)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.customerSearchResults)
        assertEquals("Selected customer must survive clearing search", "01JMBCST000000000000008500", vm.uiState.value.selectedCustomer?.id)
    }

    private fun ProductCatalogItem.toJsonString(): String =
        """{"id":"${id.value}","sku":"$sku","barcode":${if (barcode != null) "\"$barcode\"" else "null"},"name":"$name","unit_price":${if (unitPrice != null) "\"${unitPrice.lexeme}\"" else "null"},"tax_rate":${if (taxRate != null) "\"${taxRate.lexeme}\"" else "null"},"tax_rate_id":${if (taxRateId != null) "\"${taxRateId.value}\"" else "null"},"is_tracked":$isTracked,"available_quantity":"${availableQuantity.lexeme}","base_unit":{"id":"${baseUnit.id.value}","code":"${baseUnit.code}","name":"${baseUnit.name}"},"default_unit":{"unit_id":"${defaultUnit.unitId.value}","product_unit_id":"${defaultUnit.productUnitId.value}","conversion_to_base":"${defaultUnit.conversionToBase.lexeme}","sale_price":${if (defaultUnit.salePrice != null) "\"${defaultUnit.salePrice.lexeme}\"" else "null"}},"units":[]}"""

    private fun CustomerDirectoryItem.toJsonString(): String =
        """{"id":"${id.value}","code":${if (code != null) "\"$code\"" else "null"},"name":"$name","phone":${if (phone != null) "\"$phone\"" else "null"},"is_walk_in":$isWalkIn}"""
}
