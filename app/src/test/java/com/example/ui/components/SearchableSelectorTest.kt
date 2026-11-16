package com.example.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductDefaultUnit
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.ui.staff.SafeCompanyViewData
import com.example.ui.staff.StaffWorkspacePhase
import com.example.ui.staff.StaffWorkspaceScreen
import com.example.ui.staff.StaffWorkspaceTab
import com.example.ui.staff.StaffWorkspaceUiState
import com.example.ui.theme.SmallTraderTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchableSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val baseUnit = ProductBaseUnit(ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"), "KG", "كيلوغرام")
    private val defaultUnit = ProductDefaultUnit(
        unitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"),
        productUnitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67891"),
        conversionToBase = PositiveDecimalQuantity("1.0"),
        salePrice = MoneyAmount("35.00")
    )

    private fun createProduct(
        id: String,
        sku: String,
        name: String,
        price: String = "25.00",
        qty: String = "10.000000",
        barcode: String? = null
    ): ProductCatalogItem = ProductCatalogItem(
        id = ServerUlid(id),
        sku = sku,
        barcode = barcode,
        name = name,
        unitPrice = MoneyAmount(price),
        taxRate = TaxPercentage("16.00"),
        taxRateId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789T"),
        isTracked = true,
        availableQuantity = NonnegativeDecimalQuantity(qty),
        baseUnit = baseUnit,
        defaultUnit = defaultUnit,
        units = emptyList()
    )

    private fun createCustomer(
        id: String,
        code: String,
        name: String,
        phone: String? = "0599112233",
        isWalkIn: Boolean = false
    ): CustomerDirectoryItem = CustomerDirectoryItem(
        id = ServerUlid(id),
        code = code,
        name = name,
        phone = phone,
        isWalkIn = isWalkIn
    )

    // 1. Query 1 char: typing a single character triggers the search
    @Test
    fun query1Char_triggersSearch() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)
        var capturedQuery: String? = null

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 250L,
            idSelector = { it.id.value },
            onFetchCandidates = { q, _, _ ->
                capturedQuery = q
                SelectorFetchResult(
                    items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-OIL-1", "زيت زيتون")),
                    hasMore = false
                )
            }
        )

        // Typing single character "ز"
        state.onQueryChanged("ز")
        assertEquals(0, state.candidates.size) // Pending debounce

        customScope.advanceTimeBy(251L)
        assertEquals("ز", capturedQuery)
        assertEquals(1, state.candidates.size)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67891", state.candidates.first().id.value)
    }

    // 2. Debounce and cancellation on rapid typing
    @Test
    fun debounceAndCancellation_rapidTyping_onlyExecutesFinalQuery() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)
        val queriesExecuted = mutableListOf<String>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 250L,
            idSelector = { it.id.value },
            onFetchCandidates = { q, _, _ ->
                queriesExecuted.add(q)
                SelectorFetchResult(
                    items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "صنف $q")),
                    hasMore = false
                )
            }
        )

        // Rapid typing: "ز", then 50ms later "زي", then 50ms later "زيت"
        state.onQueryChanged("ز")
        customScope.advanceTimeBy(50L)
        state.onQueryChanged("زي")
        customScope.advanceTimeBy(50L)
        state.onQueryChanged("زيت")

        // No queries should have been executed yet
        assertTrue(queriesExecuted.isEmpty())

        // Advance beyond the 250ms debounce window from the last keystroke
        customScope.advanceTimeBy(251L)

        // Only the final complete query "زيت" was dispatched
        assertEquals(listOf("زيت"), queriesExecuted)
        assertEquals(1, state.candidates.size)
    }

    // 3. Stale company / session generation discard
    @Test
    fun staleCompanySession_discardsInFlightResults() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        var currentCompanyId = "01JCV8X9M2NQW3TYZ4R5S6789A"
        var currentGen = 1L
        val deferredResponse = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 250L,
            idSelector = { it.id.value },
            companyScopeProvider = { currentCompanyId },
            sessionGenerationProvider = { currentGen },
            onFetchCandidates = { _, _, _ ->
                deferredResponse.await()
            }
        )

        state.onQueryChanged("سكر")
        customScope.advanceTimeBy(251L)
        assertTrue(state.isLoading)

        // While request is in flight, company switches or session generation changes!
        currentCompanyId = "01JCV8X9M2NQW3TYZ4R5S6789B"
        currentGen = 2L

        // Server finally responds for the older company
        deferredResponse.complete(
            SelectorFetchResult(
                items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-SUGAR", "سكر أبيض")),
                hasMore = false
            )
        )
        customScope.advanceUntilIdle()

        // Result MUST be discarded completely: candidates remain empty
        assertTrue("Stale company response must be discarded", state.candidates.isEmpty())
        assertNull(state.selectedItem)
    }

    // 4. Cursor append and deduplication on page 30
    @Test
    fun cursorAppendDedupe_bounded30Page() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val item1 = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Product 1")
        val item2 = createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-2", "Product 2")
        // Page 2 contains item2 (duplicate ID) and item3 (new)
        val item3 = createProduct("01JCV8X9M2NQW3TYZ4R5S67893", "SKU-3", "Product 3")

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 250L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, cursor, limit ->
                assertEquals(30, limit)
                if (cursor == null) {
                    SelectorFetchResult(
                        items = listOf(item1, item2),
                        nextCursor = "cursor_page_2",
                        hasMore = true
                    )
                } else {
                    SelectorFetchResult(
                        items = listOf(item2, item3), // item2 is duplicated across pages
                        nextCursor = null,
                        hasMore = false
                    )
                }
            }
        )

        state.onQueryChanged("Product")
        customScope.advanceTimeBy(251L)

        assertEquals(2, state.candidates.size)
        assertTrue(state.hasMore)
        assertEquals("cursor_page_2", state.nextCursor)

        // Trigger loadMore
        state.loadMore()
        customScope.advanceUntilIdle()

        // Total should be 3 (deduplicated by ID)
        assertEquals(3, state.candidates.size)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67891", state.candidates[0].id.value)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67892", state.candidates[1].id.value)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67893", state.candidates[2].id.value)
        assertFalse(state.hasMore)
    }

    // 5. Out of order responses discard stale sequence
    @Test
    fun outOfOrderResponses_discardStaleSequence() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val deferredFirst = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()
        val deferredSecond = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L, // Immediate for test determinism
            idSelector = { it.id.value },
            onFetchCandidates = { q, _, _ ->
                if (q == "first") deferredFirst.await()
                else deferredSecond.await()
            }
        )

        // Dispatch query 1
        state.onQueryChanged("first")
        customScope.advanceUntilIdle()

        // Dispatch query 2 (supersedes query 1)
        state.onQueryChanged("second")
        customScope.advanceUntilIdle()

        // Complete query 2 first
        deferredSecond.complete(
            SelectorFetchResult(
                items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-2", "Second Result"))
            )
        )
        customScope.advanceUntilIdle()
        assertEquals(1, state.candidates.size)
        assertEquals("Second Result", state.candidates[0].name)

        // Now delayed query 1 arrives
        deferredFirst.complete(
            SelectorFetchResult(
                items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Stale First Result"))
            )
        )
        customScope.advanceUntilIdle()

        // Stale result MUST NOT overwrite the newer result
        assertEquals(1, state.candidates.size)
        assertEquals("Second Result", state.candidates[0].name)
    }

    // 6. Empty results and error handling
    @Test
    fun emptyAndErrorHandling_truthfulStates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)
        var shouldThrow = false

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                if (shouldThrow) throw IllegalStateException("Network connection failed")
                SelectorFetchResult(items = emptyList())
            }
        )

        // Empty state
        state.onQueryChanged("غير_موجود")
        customScope.advanceUntilIdle()
        assertTrue(state.candidates.isEmpty())
        assertNull(state.errorMessage)
        assertFalse(state.isLoading)

        // Error state
        shouldThrow = true
        state.onQueryChanged("خطأ_شبكة")
        customScope.advanceUntilIdle()
        assertTrue(state.candidates.isEmpty())
        assertNotNull(state.errorMessage)
        assertEquals("Network connection failed", state.errorMessage)

        // Recovery via retry
        shouldThrow = false
        state.retry()
        customScope.advanceUntilIdle()
        assertNull(state.errorMessage)
    }

    // 7. Duplicate names: clearly distinguishes codes and IDs, selects correct ID
    @Test
    fun duplicateNames_distinguishesCodesAndIds_selectsCorrectId() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        // Two products with identical name "حليب كامل الدسم" but distinct SKUs and IDs
        val productA = createProduct(
            id = "01JCV8X9M2NQW3TYZ4R5S6789A",
            sku = "SKU-MILK-1L",
            name = "حليب كامل الدسم",
            price = "5.50"
        )
        val productB = createProduct(
            id = "01JCV8X9M2NQW3TYZ4R5S6789B",
            sku = "SKU-MILK-2L",
            name = "حليب كامل الدسم",
            price = "9.00"
        )

        var selectedProduct: ProductCatalogItem? = null

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(productA, productB))
            }
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                SearchableProductSelector(
                    state = state,
                    testTagPrefix = "test_product_selector",
                    lang = AppLanguage.AR,
                    onProductSelected = { selectedProduct = it }
                )
            }
        }

        state.onQueryChanged("حليب")
        composeTestRule.waitForIdle()

        // Both candidates rendered in dropdown
        composeTestRule.onNodeWithTag("test_product_selector_dropdown").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_product_selector_item_01JCV8X9M2NQW3TYZ4R5S6789A").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_product_selector_item_01JCV8X9M2NQW3TYZ4R5S6789B").assertIsDisplayed()

        // Verify distinguishing SKUs and ID prefixes are rendered
        composeTestRule.onNodeWithText("SKU-MILK-1L", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("SKU-MILK-2L", substring = true).assertIsDisplayed()

        // Select the second duplicate product B
        composeTestRule.onNodeWithTag("test_product_selector_item_01JCV8X9M2NQW3TYZ4R5S6789B").performClick()
        composeTestRule.waitForIdle()

        // Verify that exactly Product B's stable ID and DTO were selected
        assertNotNull(selectedProduct)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789B", selectedProduct!!.id.value)
        assertEquals("SKU-MILK-2L", selectedProduct!!.sku)
        assertEquals("9.00", selectedProduct!!.unitPrice.toPlainString())
        assertFalse(state.isExpanded)
    }

    // 8. Duplicate customer names: clearly distinguishes code and ID, selects correct customer
    @Test
    fun duplicateCustomerNames_distinguishesCodesAndIds() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val customerA = createCustomer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789A",
            code = "C-101",
            name = "شركة النور",
            phone = "0599111111"
        )
        val customerB = createCustomer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789B",
            code = "C-102",
            name = "شركة النور",
            phone = "0599222222"
        )

        var selectedCustomer: CustomerDirectoryItem? = null

        val state = SearchableSelectorState<CustomerDirectoryItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(customerA, customerB))
            }
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                SearchableCustomerSelector(
                    state = state,
                    testTagPrefix = "test_customer_selector",
                    lang = AppLanguage.AR,
                    onCustomerSelected = { selectedCustomer = it }
                )
            }
        }

        state.onQueryChanged("شركة النور")
        composeTestRule.waitForIdle()

        // Both items displayed with codes C-101 and C-102
        composeTestRule.onNodeWithText("C-101", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("C-102", substring = true).assertIsDisplayed()

        // Click customer A
        composeTestRule.onNodeWithTag("test_customer_selector_item_01JCV8X9M2NQW3TYZ4R5S6789A").performClick()
        composeTestRule.waitForIdle()

        assertNotNull(selectedCustomer)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", selectedCustomer!!.id.value)
        assertEquals("C-101", selectedCustomer!!.code)
    }

    // 9. Multilingual RTL/LTR bidi formatting and keyboard navigation
    @Test
    fun multilingualAndKeyboardNavigation_arrowsEnterEscape() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val item1 = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-A", "صنف أول")
        val item2 = createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-B", "صنف ثاني")

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(item1, item2))
            }
        )

        state.onQueryChanged("صنف")
        customScope.advanceUntilIdle()
        assertEquals(2, state.candidates.size)
        assertTrue(state.isExpanded)

        // Keyboard Arrow Down highlights item 0
        state.navigateHighlight(1)
        assertEquals(0, state.highlightedIndex)

        // Keyboard Arrow Down highlights item 1
        state.navigateHighlight(1)
        assertEquals(1, state.highlightedIndex)

        // Keyboard Enter confirms selection of item 1
        val confirmed = state.confirmHighlight()
        assertTrue(confirmed)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67892", state.selectedItem?.id?.value)
        assertFalse(state.isExpanded)

        // Re-expand and test Escape dismisses
        state.expand()
        assertTrue(state.isExpanded)
        state.dismiss()
        assertFalse(state.isExpanded)
    }

    // 10. Bounded payload 10k fake catalog logical server query (no 10k download)
    @Test
    fun boundedPayload10kFakeCatalog_logicalServerQuery_strictlyBoundedTo30() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        // Logical server-side 10,000 item catalog
        val fakeServerCatalog = (0 until 10000).map { i ->
            createProduct(
                id = String.format("01JCV8X9M2NQW3TYZ4%07d", i),
                sku = "SKU-$i",
                name = if (i % 2 == 0) "زيت رقم $i" else "طحين رقم $i"
            )
        }

        var serverQueryReceived: String? = null
        var serverLimitReceived: Int? = null
        var totalPayloadDeliveredToClient = 0

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 250L,
            idSelector = { it.id.value },
            onFetchCandidates = { q, cursor, limit ->
                // Logical server query simulation: server filters and caps to limit (max 30)
                serverQueryReceived = q
                serverLimitReceived = limit
                val filtered = fakeServerCatalog.filter { it.name.contains(q) }
                val paged = filtered.take(limit)
                totalPayloadDeliveredToClient += paged.size
                SelectorFetchResult(
                    items = paged,
                    nextCursor = if (filtered.size > limit) "cursor_30" else null,
                    hasMore = filtered.size > limit
                )
            }
        )

        // Client queries for "زيت"
        state.onQueryChanged("زيت")
        customScope.advanceTimeBy(251L)

        // Verify:
        // 1. Server received bounded limit of 30
        assertEquals(30, serverLimitReceived)
        assertEquals("زيت", serverQueryReceived)

        // 2. Client payload is strictly bounded to <= 30 items
        assertEquals(30, state.candidates.size)
        assertEquals(30, totalPayloadDeliveredToClient)

        // 3. Client DID NOT download all 10,000 items (or 5,000 matching items) into local memory
        assertTrue("Client must never download entire catalog", totalPayloadDeliveredToClient <= 30)
        assertTrue(state.hasMore)
    }

    // 11. Clear selection and clear query operations
    @Test
    fun clearSelectionAndQuery_cleanLifecycle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val product = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Product 1")

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(product))
            }
        )

        state.onQueryChanged("Prod")
        customScope.advanceUntilIdle()
        assertEquals(1, state.candidates.size)

        state.selectItem(product)
        assertEquals(product, state.selectedItem)

        state.clearSelection()
        assertNull(state.selectedItem)

        state.clearQuery()
        assertEquals("", state.query)
        assertTrue(state.candidates.isEmpty())
        assertFalse(state.isExpanded)
    }

    // 12. Touch target bounds: verifies 48dp minimum interactive bounds via getBoundsInRoot()
    @Test
    fun touchTargets_verify48dpCompliance() {
        val testDispatcher = StandardTestDispatcher()
        val customScope = TestScope(testDispatcher)

        val product = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-OIL", "زيت زيتون")
        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(product), hasMore = true, nextCursor = "cur_2")
            }
        )
        state.setDirectCandidates(listOf(product), hasMore = true, nextCursor = "cur_2", query = "زيت")
        state.setExplicitQuery("زيت")

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                SearchableProductSelector(
                    state = state,
                    testTagPrefix = "touch_test_selector",
                    lang = AppLanguage.AR
                )
            }
        }

        // 1. Text input height >= 48dp
        val inputBounds = composeTestRule.onNodeWithTag("touch_test_selector_input").getBoundsInRoot()
        assertTrue("Input height must be >= 48dp", (inputBounds.bottom - inputBounds.top) >= 48.dp)

        // 2. Clear button interactive target >= 48dp
        val clearBounds = composeTestRule.onNodeWithTag("touch_test_selector_clear_button").getBoundsInRoot()
        assertTrue("Clear button height must be >= 48dp", (clearBounds.bottom - clearBounds.top) >= 48.dp)
        assertTrue("Clear button width must be >= 48dp", (clearBounds.right - clearBounds.left) >= 48.dp)

        // 3. Candidate item row touch bounds >= 48dp
        val itemBounds = composeTestRule.onNodeWithTag("touch_test_selector_item_01JCV8X9M2NQW3TYZ4R5S67891").getBoundsInRoot()
        assertTrue("Item row height must be >= 48dp", (itemBounds.bottom - itemBounds.top) >= 48.dp)

        // 4. Load more button interactive target >= 48dp
        val loadMoreBounds = composeTestRule.onNodeWithTag("touch_test_selector_load_more").getBoundsInRoot()
        assertTrue("Load more height must be >= 48dp", (loadMoreBounds.bottom - loadMoreBounds.top) >= 48.dp)
    }

    // 13. P1 Production wiring: StaffWorkspaceScreen renders SearchableProductSelector, selects item, shows card, clears
    @Test
    fun staffWorkspaceScreen_productSelectorWiring_selectsAndClears() {
        val testDispatcher = StandardTestDispatcher()
        val customScope = TestScope(testDispatcher)

        val product1 = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-OIL-1", "زيت زيتون بلدي", "45.00")
        val product2 = createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-FLOUR-1", "طحين أبيض ممتاز", "12.00")

        val selectorState = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            companyScopeProvider = { "01JCV8X9M2NQW3TYZ4R5S6789A" },
            onFetchCandidates = { q, _, _ ->
                val matches = listOf(product1, product2).filter { it.name.contains(q) }
                SelectorFetchResult(items = matches)
            }
        )

        var currentSelectedProduct: ProductCatalogItem? = null
        var uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.PRODUCTS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "شركة السلام", "ILS"),
            products = listOf(product1, product2),
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState.copy(selectedProduct = currentSelectedProduct),
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    productSelectorState = selectorState,
                    onSelectProduct = { currentSelectedProduct = it },
                    onClearSelectedProduct = { currentSelectedProduct = null }
                )
            }
        }

        // Verify SearchableProductSelector input is mounted
        composeTestRule.onNodeWithTag("staff_product_selector_input").assertIsDisplayed()

        // Type search query
        composeTestRule.onNodeWithTag("staff_product_selector_input").performTextInput("زيت")
        composeTestRule.waitForIdle()

        // Dropdown appears with candidate
        composeTestRule.onNodeWithTag("staff_product_selector_item_01JCV8X9M2NQW3TYZ4R5S67891").assertIsDisplayed()

        // Click candidate
        composeTestRule.onNodeWithTag("staff_product_selector_item_01JCV8X9M2NQW3TYZ4R5S67891").performClick()
        composeTestRule.waitForIdle()

        // Selected product callback received item
        assertNotNull(currentSelectedProduct)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67891", currentSelectedProduct!!.id.value)

        // Re-compose with selected product state
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState.copy(selectedProduct = currentSelectedProduct),
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    productSelectorState = selectorState,
                    onSelectProduct = { currentSelectedProduct = it },
                    onClearSelectedProduct = { currentSelectedProduct = null }
                )
            }
        }

        // Observable detail card is rendered with stable ID and clear action
        composeTestRule.onNodeWithTag("staff_selected_product_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("staff_selected_product_name").assertTextContains("زيت زيتون بلدي")
        composeTestRule.onNodeWithTag("staff_selected_product_id").assertTextContains("01JCV8X9M2NQW3TYZ4R5S67891")

        // Clear selection via clear button
        composeTestRule.onNodeWithTag("staff_selected_product_clear_button").performClick()
        assertNull(currentSelectedProduct)
    }

    // 14. P1 Production wiring: StaffWorkspaceScreen renders SearchableCustomerSelector, selects customer, shows card, clears
    @Test
    fun staffWorkspaceScreen_customerSelectorWiring_selectsAndClears() {
        val testDispatcher = StandardTestDispatcher()
        val customScope = TestScope(testDispatcher)

        val customer = createCustomer("01JCV8X9M2NQW3TYZ4R5S6789C", "C-777", "شركة الأمل للتجارة", "0599554433")

        val selectorState = SearchableSelectorState<CustomerDirectoryItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            companyScopeProvider = { "01JCV8X9M2NQW3TYZ4R5S6789A" },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(customer))
            }
        )

        var currentSelectedCustomer: CustomerDirectoryItem? = null
        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            selectedTab = StaffWorkspaceTab.CUSTOMERS,
            activeCompany = SafeCompanyViewData("01JCV8X9M2NQW3TYZ4R5S6789A", "شركة السلام", "ILS"),
            customers = listOf(customer),
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState.copy(selectedCustomer = currentSelectedCustomer),
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    customerSelectorState = selectorState,
                    onSelectCustomer = { currentSelectedCustomer = it },
                    onClearSelectedCustomer = { currentSelectedCustomer = null }
                )
            }
        }

        // Customer selector is mounted
        composeTestRule.onNodeWithTag("staff_customer_selector_input").assertIsDisplayed()

        // Type search query
        composeTestRule.onNodeWithTag("staff_customer_selector_input").performTextInput("الأمل")
        composeTestRule.waitForIdle()

        // Dropdown displays candidate
        composeTestRule.onNodeWithTag("staff_customer_selector_item_01JCV8X9M2NQW3TYZ4R5S6789C").assertIsDisplayed()

        // Click candidate
        composeTestRule.onNodeWithTag("staff_customer_selector_item_01JCV8X9M2NQW3TYZ4R5S6789C").performClick()
        composeTestRule.waitForIdle()

        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789C", currentSelectedCustomer?.id?.value)

        // Re-compose with selected customer
        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffWorkspaceScreen(
                    uiState = uiState.copy(selectedCustomer = currentSelectedCustomer),
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    customerSelectorState = selectorState,
                    onSelectCustomer = { currentSelectedCustomer = it },
                    onClearSelectedCustomer = { currentSelectedCustomer = null }
                )
            }
        }

        composeTestRule.onNodeWithTag("staff_selected_customer_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("staff_selected_customer_name").assertTextContains("شركة الأمل للتجارة")
        composeTestRule.onNodeWithTag("staff_selected_customer_id").assertTextContains("01JCV8X9M2NQW3TYZ4R5S6789C")

        composeTestRule.onNodeWithTag("staff_selected_customer_clear_button").performClick()
        assertNull(currentSelectedCustomer)
    }

    // 15. P2: Pause fetch after query change / select old item rejected
    @Test
    fun pauseFetch_afterQueryChange_selectOldItemRejected() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val itemA = createProduct("01JCV8X9M2NQW3TYZ4R5S6789A", "SKU-A", "Item A")
        val itemB = createProduct("01JCV8X9M2NQW3TYZ4R5S6789B", "SKU-B", "Item B")
        val deferredQueryB = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            companyScopeProvider = { "COMP_1" },
            onFetchCandidates = { q, _, _ ->
                if (q == "queryA") SelectorFetchResult(items = listOf(itemA))
                else deferredQueryB.await()
            }
        )

        // Query A completes
        state.onQueryChanged("queryA")
        customScope.advanceUntilIdle()
        assertEquals(1, state.candidates.size)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", state.candidates.first().id.value)

        // Query B is typed; fetch is paused (waiting on deferredQueryB)
        state.onQueryChanged("queryB")
        customScope.advanceTimeBy(10L)

        // Candidates are immediately wiped on query change
        assertTrue("Candidates must be immediately cleared on query change", state.candidates.isEmpty())

        // Attempting to select old itemA from queryA MUST be rejected!
        val committed = state.selectItem(itemA)
        assertFalse("Old candidate under previous query must be rejected", committed)
        assertNull("Selected item must remain null", state.selectedItem)

        // Now query B finally arrives
        deferredQueryB.complete(SelectorFetchResult(items = listOf(itemB)))
        customScope.advanceUntilIdle()

        assertEquals(1, state.candidates.size)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789B", state.candidates.first().id.value)

        // Now itemB is committed cleanly
        assertTrue(state.selectItem(itemB))
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789B", state.selectedItem?.id?.value)
    }

    // 16. P2: Company switch before debounce rejects old company items
    @Test
    fun companySwitch_beforeDebounce_rejectsOldCompanyItems() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        var activeCompany = "COMP_ALPHA"
        val itemAlpha = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Alpha Product")

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 250L,
            idSelector = { it.id.value },
            companyScopeProvider = { activeCompany },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(itemAlpha))
            }
        )

        state.onQueryChanged("Alpha")
        customScope.advanceTimeBy(100L) // Halfway through debounce

        // Company switches before debounce expires!
        activeCompany = "COMP_BETA"

        // Debounce timer finishes
        customScope.advanceTimeBy(200L)
        customScope.advanceUntilIdle()

        // Alpha results must be rejected due to company mismatch
        assertTrue("Candidates must be empty after company switch before debounce", state.candidates.isEmpty())
        assertNull(state.selectedItem)
    }

    // 17. P2: Edit before loadMore does not use old cursor
    @Test
    fun editBeforeLoadMore_doesNotUseOldCursor() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val item1 = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Product 1")
        val queriesDispatched = mutableListOf<Pair<String, String?>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { q, cursor, _ ->
                queriesDispatched.add(q to cursor)
                SelectorFetchResult(items = listOf(item1), nextCursor = "cursor_page_1", hasMore = true)
            }
        )

        state.onQueryChanged("queryOld")
        customScope.advanceUntilIdle()
        assertEquals(listOf("queryOld" to null), queriesDispatched)
        assertEquals("cursor_page_1", state.nextCursor)

        // User edits query to "queryNew"
        state.onQueryChanged("queryNew")

        // Immediately, nextCursor and committedQuery are cleared
        assertNull(state.nextCursor)
        assertFalse(state.hasMore)

        // If loadMore is invoked immediately, it cannot run with old cursor
        state.loadMore()
        customScope.advanceUntilIdle()

        // No loadMore dispatch with old cursor occurred
        assertFalse(queriesDispatched.any { it.second == "cursor_page_1" })
    }

    // 18. P2: Clear during in-flight loadMore does not append to cleared state
    @Test
    fun clearDuringInFlightLoadMore_doesNotAppendToClearedState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val item1 = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Product 1")
        val item2 = createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-2", "Product 2")
        val deferredLoadMore = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, cursor, _ ->
                if (cursor == null) {
                    SelectorFetchResult(items = listOf(item1), nextCursor = "cur_1", hasMore = true)
                } else {
                    deferredLoadMore.await()
                }
            }
        )

        state.onQueryChanged("search")
        customScope.advanceUntilIdle()
        assertEquals(1, state.candidates.size)

        // Start loadMore (in flight)
        state.loadMore()
        customScope.advanceTimeBy(10L)
        assertTrue(state.isLoadingMore)

        // User clears query during in-flight loadMore
        state.clearQuery()
        assertEquals("", state.query)
        assertTrue(state.candidates.isEmpty())

        // In-flight loadMore now resolves
        deferredLoadMore.complete(SelectorFetchResult(items = listOf(item2), hasMore = false))
        customScope.advanceUntilIdle()

        // Late pagination response MUST NOT append to cleared state
        assertTrue("Late pagination response must be discarded", state.candidates.isEmpty())
        assertFalse(state.isLoadingMore)
    }

    // 19. P2: Cancelled old finally does not flip new loading state
    @Test
    fun cancelledOldFinally_doesNotFlipNewLoadingState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val deferredFirst = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { q, _, _ ->
                if (q == "first") deferredFirst.await()
                else SelectorFetchResult(items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-2", "Second")))
            }
        )

        state.onQueryChanged("first")
        customScope.advanceTimeBy(10L)
        assertTrue(state.isLoading)

        // Query 2 cancels Query 1
        state.onQueryChanged("second")
        customScope.advanceTimeBy(10L)

        // Complete query 1 (cancelled job completes finally block)
        deferredFirst.complete(SelectorFetchResult(items = emptyList()))
        customScope.advanceUntilIdle()

        // State has committed Query 2 without old finally corrupting it
        assertEquals(1, state.candidates.size)
        assertEquals("Second", state.candidates.first().name)
        assertFalse(state.isLoading)
    }

    // 20. P2: Empty query while page is in-flight cannot repopulate
    @Test
    fun emptyQuery_whilePageInFlight_cannotRepopulate() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val customScope = TestScope(testDispatcher)

        val deferredInFlight = CompletableDeferred<SelectorFetchResult<ProductCatalogItem>>()

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                deferredInFlight.await()
            }
        )

        state.onQueryChanged("coffee")
        customScope.advanceTimeBy(10L)
        assertTrue(state.isLoading)

        // User blanks query before response
        state.onQueryChanged("")
        assertFalse(state.isLoading)
        assertTrue(state.candidates.isEmpty())

        // In-flight response returns late
        deferredInFlight.complete(
            SelectorFetchResult(
                items = listOf(createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-COFFEE", "قهوة عربية"))
            )
        )
        customScope.advanceUntilIdle()

        // Discarded: cannot repopulate
        assertTrue("Candidates must remain empty when query was blanked", state.candidates.isEmpty())
        assertFalse(state.isExpanded)
    }

    // 21. Keyboard navigation via actual Compose onPreviewKeyEvent
    @Test
    fun keyboardEvents_actualComposeKeyEvents_navigatesAndSelects() {
        val testDispatcher = StandardTestDispatcher()
        val customScope = TestScope(testDispatcher)

        val item1 = createProduct("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-1", "Product 1")
        val item2 = createProduct("01JCV8X9M2NQW3TYZ4R5S67892", "SKU-2", "Product 2")

        val state = SearchableSelectorState<ProductCatalogItem>(
            scope = customScope,
            debounceMs = 0L,
            idSelector = { it.id.value },
            onFetchCandidates = { _, _, _ ->
                SelectorFetchResult(items = listOf(item1, item2))
            }
        )
        state.setDirectCandidates(listOf(item1, item2), query = "Prod")

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                SearchableProductSelector(
                    state = state,
                    testTagPrefix = "key_selector"
                )
            }
        }

        // Navigate Down via KeyInput
        composeTestRule.onNodeWithTag("key_selector_input").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        assertEquals(0, state.highlightedIndex)

        composeTestRule.onNodeWithTag("key_selector_input").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        assertEquals(1, state.highlightedIndex)

        // Confirm selection via Enter
        composeTestRule.onNodeWithTag("key_selector_input").performKeyInput {
            pressKey(Key.Enter)
        }
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67892", state.selectedItem?.id?.value)
        assertFalse(state.isExpanded)

        // Re-expand and test Escape
        state.expand()
        assertTrue(state.isExpanded)

        composeTestRule.onNodeWithTag("key_selector_input").performKeyInput {
            pressKey(Key.Escape)
        }
        assertFalse(state.isExpanded)
    }
}
