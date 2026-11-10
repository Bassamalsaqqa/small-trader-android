package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.data.OfflineDemoTraderRepository
import com.example.model.Customer
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.ui.screens.CustomersScreen
import com.example.ui.screens.ProductDetailScreen
import com.example.ui.screens.ProductsCatalogScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Meaningful Robolectric Compose Regression Tests for Merchant Financial Privacy & Role Boundaries:
 *
 * 1. Customer Directory Financial Privacy:
 *    - Owner renders store aggregate debts and advances tags (total_customer_debts_value, total_customer_advances_value).
 *    - Live role downgrade to Cashier immediately removes both aggregate tags from composition and semantics.
 *    - Cashier retains directory individual customer balances and quick-action buttons (customer_row_*, quick_collect_*).
 *
 * 2. Product Catalog Create/Edit Dialog Eviction & Stale Dialog Suppression:
 *    - Opening create-product editor via catalog launchCreate displays cost input and save dialog.
 *    - Live role downgrade to Cashier immediately dismisses the editor and removes cost input/dialog from composition.
 *    - Restoring Owner session does NOT reopen the stale create dialog automatically.
 *    - Optional counterpart: ProductDetailScreen edit dialog and cost section are evicted on Cashier downgrade;
 *      restoring Owner restores detail cost section without reopening the stale edit dialog.
 *
 * 3. Product Cost Integrity & Honest Valuation (No Fabricated 75% Formula):
 *    - Blank cost prevents saving: filling name and price still leaves confirm button disabled (0 callbacks posted).
 *    - Explicitly entering 0 enables submit, and clicking save invokes callback with cost = 0.0 (no fabricated 75%).
 *    - Explicitly entering a non-zero cost captures the exact entered value, proving cost field fidelity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MerchantPrivacyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // =========================================================================
    // 1. Customer Directory Aggregate Privacy vs Individual Balances
    // =========================================================================

    @Test
    fun customerDirectory_ownerRendersDebtAndAdvanceAggregates_downgradeToCashierRemovesBoth_whileIndividualBalancesAndActionsRemain() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        val debtorCustomer = viewModel.uiState.value.customers.first { it.balanceIls > 0.005 }
        val advanceCustomer = viewModel.uiState.value.customers.first { it.balanceIls < -0.005 }

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                CustomersScreen(
                    state = state,
                    onCustomerClick = viewModel::selectCustomerForDetail,
                    onOpenAddCustomerDialog = { viewModel.openAddCustomerDialog(true) },
                    onStartSaleForCustomer = { cust ->
                        viewModel.assignCustomerToSale(cust)
                        viewModel.navigateTo(TraderScreen.NEW_SALE)
                    },
                    onQuickCollectPayment = { cust ->
                        viewModel.openPaymentCollectionForCustomer(cust)
                    },
                    onDismissFeedback = viewModel::dismissCustomerFeedback
                )
            }
        }

        composeTestRule.waitForIdle()

        // Step A: Owner sees aggregate store debts and customer advances tags
        composeTestRule.onNodeWithTag("total_customer_debts_value").assertIsDisplayed()
        composeTestRule.onNodeWithTag("total_customer_advances_value").assertIsDisplayed()

        // Step B: Directory individual customer rows and actions are displayed
        composeTestRule.onNodeWithTag("customer_row_${debtorCustomer.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_collect_${debtorCustomer.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_${advanceCustomer.id}"))
        composeTestRule.onNodeWithTag("customer_row_${advanceCustomer.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_customer_button").assertIsDisplayed()

        // Step C: Live role downgrade to Cashier
        val cashierSession = viewModel.uiState.value.currentSession.copy(role = UserRole.CASHIER)
        viewModel.switchUserSession(cashierSession)
        composeTestRule.waitForIdle()

        // Step D: Both aggregate totals are strictly removed from composition/semantics
        composeTestRule.onNodeWithTag("total_customer_debts_value").assertDoesNotExist()
        composeTestRule.onNodeWithTag("total_customer_advances_value").assertDoesNotExist()

        // Step E: Directory individual customer balances and operational actions remain fully accessible
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_${debtorCustomer.id}"))
        composeTestRule.onNodeWithTag("customer_row_${debtorCustomer.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_collect_${debtorCustomer.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("customers_directory_list").performScrollToNode(hasTestTag("customer_row_${advanceCustomer.id}"))
        composeTestRule.onNodeWithTag("customer_row_${advanceCustomer.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("add_customer_button").assertIsDisplayed()
    }

    // =========================================================================
    // 2. Product Catalog Create Dialog Eviction on Role Downgrade
    // =========================================================================

    @Test
    fun productCatalog_launchCreateEditor_downgradeToCashierDismissesEditorAndCostInput_restoreOwnerDoesNotReopenStaleDialog() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        var launchCreate by mutableStateOf(true)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                ProductsCatalogScreen(
                    state = state,
                    onSearchChange = viewModel::updateSearchQuery,
                    onCategorySelect = viewModel::selectCategory,
                    onProductClick = viewModel::selectProductForDetail,
                    launchCreate = launchCreate,
                    onLaunchConsumed = { launchCreate = false },
                    onSaveProduct = { pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted ->
                        viewModel.saveProduct(
                            productId = pid,
                            nameAr = nameAr,
                            category = cat,
                            unitAr = unitAr,
                            barcode = barcode,
                            priceIls = price,
                            costIls = cost,
                            minStockThreshold = minThreshold,
                            initialStock = initialStock,
                            warehouseAr = warehouse,
                            lotBatchCode = lot,
                            expiryDate = expiry,
                            isWeightedDisplayOnly = isWeighted
                        )
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Step A: In Owner session with launchCreate=true, create product editor dialog is open with cost input
        composeTestRule.onNodeWithTag("product_editor_cost_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsDisplayed()

        // Step B: Live role downgrade to Cashier
        val cashierSession = viewModel.uiState.value.currentSession.copy(role = UserRole.CASHIER)
        viewModel.switchUserSession(cashierSession)
        composeTestRule.waitForIdle()

        // Step C: Cost input and create dialog are immediately evicted from composition
        composeTestRule.onNodeWithTag("product_editor_cost_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertDoesNotExist()
        // Catalog add_product_button must also be absent for Cashier
        composeTestRule.onNodeWithTag("add_product_button").assertDoesNotExist()

        // Step D: Restoring Owner session must NOT reopen stale create dialog automatically
        viewModel.switchUserSession(ownerSession)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("product_editor_cost_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertDoesNotExist()
        // Authorized add button is restored for Owner
        composeTestRule.onNodeWithTag("add_product_button").assertIsDisplayed()
    }

    // =========================================================================
    // 2 (Optional Counterpart). Product Detail Edit Dialog Eviction on Downgrade
    // =========================================================================

    @Test
    fun productDetail_editDialog_downgradeToCashierDismissesEditorAndCostSection_restoreOwnerDoesNotReopenStaleDialog() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        val product = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                ProductDetailScreen(
                    state = state.copy(selectedProductForDetail = product),
                    onBack = { viewModel.navigateBack() },
                    onUpdatePrice = { id, price -> viewModel.updateProductPrice(id, price) },
                    onAdjustStock = { id, delta, reason -> viewModel.adjustProductStock(id, delta, reason) },
                    onSaveProduct = { pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted ->
                        viewModel.saveProduct(
                            productId = pid,
                            nameAr = nameAr,
                            category = cat,
                            unitAr = unitAr,
                            barcode = barcode,
                            priceIls = price,
                            costIls = cost,
                            minStockThreshold = minThreshold,
                            initialStock = initialStock,
                            warehouseAr = warehouse,
                            lotBatchCode = lot,
                            expiryDate = expiry,
                            isWeightedDisplayOnly = isWeighted
                        )
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Step A: Owner sees cost price & margin section
        composeTestRule.onNodeWithTag("product_detail_cost_section").assertIsDisplayed()

        // Step B: Owner opens full product edit dialog
        composeTestRule.onNodeWithTag("product_detail_list")
            .performScrollToNode(hasTestTag("edit_product_button"))
        composeTestRule.onNodeWithTag("edit_product_button")
            .performClick()

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("product_editor_cost_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsDisplayed()

        // Step C: Live role downgrade to Cashier
        val cashierSession = viewModel.uiState.value.currentSession.copy(role = UserRole.CASHIER)
        viewModel.switchUserSession(cashierSession)
        composeTestRule.waitForIdle()

        // Step D: Edit dialog, cost input, and cost section are all evicted
        composeTestRule.onNodeWithTag("product_editor_cost_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("product_detail_cost_section").assertDoesNotExist()

        // Step E: Restore Owner session
        viewModel.switchUserSession(ownerSession)
        composeTestRule.waitForIdle()

        // Stale edit dialog must NOT reopen automatically
        composeTestRule.onNodeWithTag("product_editor_cost_input").assertDoesNotExist()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertDoesNotExist()

        // Cost section on detail card is restored for authorized Owner
        composeTestRule.onNodeWithTag("product_detail_list")
            .performScrollToNode(hasTestTag("product_detail_cost_section"))
        composeTestRule.onNodeWithTag("product_detail_cost_section").assertIsDisplayed()
    }

    // =========================================================================
    // 3. Product Cost Integrity: Blank Cost Rejected, Explicit 0 Allowed, No 75%
    // =========================================================================

    @Test
    fun productEditor_blankCostPreventsSubmit_explicitZeroCostEnablesAndCapturesZeroCost_neverFabricatesSeventyFivePercent() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        var saveCallbackCount = 0
        var capturedProductId: String? = null
        var capturedNameAr: String? = null
        var capturedCategory: ProductCategory? = null
        var capturedUnitAr: String? = null
        var capturedBarcode: String? = null
        var capturedPriceIls: Double? = null
        var capturedCostIls: Double? = null
        var capturedMinStock: Int? = null
        var capturedInitialStock: Int? = null
        var capturedWarehouse: String? = null
        var capturedLotBatch: String? = null
        var capturedExpiryDate: String? = null
        var capturedIsWeighted: Boolean? = null

        var launchCreate by mutableStateOf(true)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                ProductsCatalogScreen(
                    state = state,
                    onSearchChange = {},
                    onCategorySelect = {},
                    onProductClick = {},
                    launchCreate = launchCreate,
                    onLaunchConsumed = { launchCreate = false },
                    onSaveProduct = { pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted ->
                        saveCallbackCount++
                        capturedProductId = pid
                        capturedNameAr = nameAr
                        capturedCategory = cat
                        capturedUnitAr = unitAr
                        capturedBarcode = barcode
                        capturedPriceIls = price
                        capturedCostIls = cost
                        capturedMinStock = minThreshold
                        capturedInitialStock = initialStock
                        capturedWarehouse = warehouse
                        capturedLotBatch = lot
                        capturedExpiryDate = expiry
                        capturedIsWeighted = isWeighted
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Step A: Dialog is open, but submit is disabled initially (name, price, cost all empty)
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsNotEnabled()
        assertEquals("No callback before user action", 0, saveCallbackCount)

        // Step B: Fill product name (>= 2 chars) and sale price (> 0), but leave cost BLANK
        composeTestRule.onNodeWithTag("product_editor_name_input")
            .performScrollTo()
            .performTextInput("زعتر بلدي ممتاز")

        composeTestRule.onNodeWithTag("product_editor_price_input")
            .performScrollTo()
            .performTextInput("20.00")

        composeTestRule.waitForIdle()

        // Step C: Blank cost MUST prevent saving — button remains disabled, exactly 0 callbacks
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsNotEnabled()
        assertEquals("Blank cost must never post save callback", 0, saveCallbackCount)

        // Step D: Explicitly enter "0" in unit cost field
        composeTestRule.onNodeWithTag("product_editor_cost_input")
            .performScrollTo()
            .performTextInput("0")

        composeTestRule.waitForIdle()

        // Step E: With explicit zero cost, submit button becomes enabled
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsEnabled()
        assertEquals("Still 0 callbacks before clicking save", 0, saveCallbackCount)

        // Step F: Click save button
        composeTestRule.onNodeWithTag("confirm_save_product_button").performClick()
        composeTestRule.waitForIdle()

        // Step G: Verify exact 1 callback invocation capturing cost = 0.0, NOT fabricated 75%
        assertEquals("Exactly one save callback must be invoked", 1, saveCallbackCount)
        assertEquals("زعتر بلدي ممتاز", capturedNameAr)
        assertEquals(20.00, capturedPriceIls!!, 0.0001)
        assertEquals("Captured cost must be explicit 0.0", 0.0, capturedCostIls!!, 0.0001)

        // Verify that cost was NOT fabricated using the old 75% formula (which would be 20.00 * 0.75 = 15.00)
        assertNotEquals("Must not fabricate 75% sale price for cost", 20.00 * 0.75, capturedCostIls!!, 0.0001)
    }

    @Test
    fun productEditor_explicitNonZeroCost_enablesSubmitAndCapturesExactCost() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val ownerSession = viewModel.uiState.value.currentSession.copy(role = UserRole.OWNER)
        viewModel.switchUserSession(ownerSession)

        var saveCallbackCount = 0
        var capturedCostIls: Double? = null
        var capturedPriceIls: Double? = null

        var launchCreate by mutableStateOf(true)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                ProductsCatalogScreen(
                    state = state,
                    onSearchChange = {},
                    onCategorySelect = {},
                    onProductClick = {},
                    launchCreate = launchCreate,
                    onLaunchConsumed = { launchCreate = false },
                    onSaveProduct = { _, _, _, _, _, price, cost, _, _, _, _, _, _ ->
                        saveCallbackCount++
                        capturedPriceIls = price
                        capturedCostIls = cost
                    }
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("product_editor_name_input")
            .performScrollTo()
            .performTextInput("زيت زيتون بكر")

        composeTestRule.onNodeWithTag("product_editor_price_input")
            .performScrollTo()
            .performTextInput("40.00")

        // Cost is blank -> button remains disabled
        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsNotEnabled()
        assertEquals(0, saveCallbackCount)

        // Enter explicit non-zero cost (e.g. 28.50)
        composeTestRule.onNodeWithTag("product_editor_cost_input")
            .performScrollTo()
            .performTextInput("28.50")

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("confirm_save_product_button").assertIsEnabled()
        assertEquals(0, saveCallbackCount)

        composeTestRule.onNodeWithTag("confirm_save_product_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, saveCallbackCount)
        assertEquals(40.00, capturedPriceIls!!, 0.0001)
        assertEquals(28.50, capturedCostIls!!, 0.0001)
        // 40.00 * 0.75 would be 30.00; verify it's the exact user input (28.50)
        assertNotEquals(30.00, capturedCostIls!!, 0.0001)
    }
}
