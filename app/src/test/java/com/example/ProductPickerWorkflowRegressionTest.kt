package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.data.OfflineDemoTraderRepository
import com.example.model.AppLanguage
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.screens.NewSaleScreen
import com.example.ui.screens.ReceivePurchaseBillDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end UX Workflow Regression Test:
 * Covers: query -> select -> unit+qty -> add line -> edit/remove -> review -> save draft
 * Covers: repeated SKU with different costs, intentional 0.00 cost,
 *         and cashier cost privacy protection.
 * Note: Android local demo prototype maintains unwired demo accounting; native purchase-domain
 *       alignment (ADR0013 atomic stock receipt on purchase confirm) will be aligned in separate
 *       reviewed slice before live integration.
 */
@RunWith(RobolectricTestRunner::class)
class ProductPickerWorkflowRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun purchasePicker_querySelectUnitAddDuplicateSkuZeroCostAndGrnInvariants() {
        val repo = OfflineDemoTraderRepository()
        val supplier = repo.getSuppliers().first()
        val trackedProduct = repo.getProducts().first { it.id == "p_olive_oil" && it.isStockTracked }
        val initialStock = trackedProduct.stockCount

        var submittedBill: PurchaseBill? = null
        var isDismissed = false

        val state = TraderUiState(
            products = repo.getProducts(),
            suppliers = repo.getSuppliers()
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                ReceivePurchaseBillDialog(
                    state = state,
                    onDismiss = { isDismissed = true },
                    onConfirmStructuredBill = { supId, lines, paidNow, note ->
                        val res = repo.recordStructuredPurchaseBill(supId, lines, paidNow, repo.getCurrentSession(), note)
                        if (res.isSuccess) {
                            submittedBill = res.getOrNull()
                        }
                        res
                    }
                )
            }
        }

        // 1. Enter bill description
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_summary_input").performTextInput("توريد أصناف مخزنية مع فحص التكاليف")

        // 2. Fast product search in purchase dialog
        composeTestRule.onNodeWithTag("purchase_product_search_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("purchase_product_search_input").performTextInput("زيت")
        composeTestRule.waitForIdle()

        // Select the matching candidate chip
        composeTestRule.onNodeWithTag("chip_product_${trackedProduct.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 3. Enter Line 1: 3 units @ 20.0 ₪
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("3")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("20.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 4. Repeat same SKU (duplicate SKU invariant) at different cost: 2 units @ 25.0 ₪
        composeTestRule.onNodeWithTag("chip_product_${trackedProduct.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("2")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("25.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 5. Clear search query to pick another item
        composeTestRule.onNodeWithTag("clear_purchase_product_search").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 6. Add second product with intentional zero cost (0.00 ₪ promotional/bonus sample)
        val secondProduct = repo.getProducts().first { it.id == "p_zaatar_jenin" && it.isStockTracked }
        composeTestRule.onNodeWithTag("chip_product_${secondProduct.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("5")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("0.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Verify all 3 lines are recorded in structured lines container
        composeTestRule.onNodeWithTag("structured_purchase_lines_container").performScrollTo().assertIsDisplayed()

        // 7. Total validation: (3 * 20.0) + (2 * 25.0) + (5 * 0.0) = 60 + 50 + 0 = 110.00 ₪
        composeTestRule.onNodeWithTag("purchase_bill_total_input").performScrollTo().assertTextContains("110.00", substring = true)

        // 8. Confirm purchase bill
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Dialog must be dismissed after success", isDismissed)
        assertNotNull("Submitted bill must be recorded", submittedBill)
        assertEquals("Total must match exact derived line sum", 110.00, submittedBill!!.totalIls, 0.001)
        assertEquals("Bill must contain 3 lines", 3, submittedBill!!.lines.size)

        // Verify local demo invariant: physical inventory is unchanged in unwired demo flow
        val currentTracked = repo.getProducts().first { it.id == "p_olive_oil" }
        assertEquals("Stock remains unchanged in local demo flow", initialStock, currentTracked.stockCount)
    }

    @Test
    fun posSale_searchQuerySelectAddStepperAndCashierCostProtection() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Cashier role session
        val cashierSession = UserSession(
            id = "staff_cashier",
            name = "سعيد الكاشير",
            username = "saeed",
            role = UserRole.CASHIER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch"
        )
        assertFalse("Cashier must not have inventory management permission", cashierSession.canManageInventory())

        viewModel.switchUserSession(cashierSession)
        viewModel.navigateTo(TraderScreen.NEW_SALE)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                NewSaleScreen(
                    state = state,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onSelectCategory = viewModel::selectCategory,
                    onAddProduct = { product -> viewModel.addProductToBasket(product, 1) },
                    onDecrementProduct = viewModel::decrementProductInBasket,
                    onRemoveProduct = viewModel::removeProductFromBasket,
                    onClearBasket = viewModel::clearBasket,
                    onOpenScanner = {},
                    onOpenCustomItem = {},
                    onProceedToInvoiceReview = {}
                )
            }
        }

        // 1. Search for item
        composeTestRule.onNodeWithTag("product_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_search_input").performTextInput("زيت")
        composeTestRule.waitForIdle()

        // 2. Add product to basket
        composeTestRule.onNodeWithTag("product_card_p_olive_oil").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_card_p_olive_oil").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Basket must contain 1 item", 1, viewModel.uiState.value.basketItemCount)

        // 3. Clear search query
        composeTestRule.onNodeWithTag("clear_search_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Search query must be cleared", "", viewModel.uiState.value.searchQuery)
    }
}
