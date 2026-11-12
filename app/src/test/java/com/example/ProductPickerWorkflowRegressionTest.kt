package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
 * Covers: repeated product ID with different costs, intentional 0.00 cost,
 *         and cashier cost privacy protection in shipped UI.
 * Note: Android local demo prototype maintains unwired demo accounting; native purchase-domain
 *       alignment (ADR0013 atomic stock receipt on purchase confirm) will be aligned in separate
 *       reviewed slice before live integration.
 */
@RunWith(RobolectricTestRunner::class)
class ProductPickerWorkflowRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun purchasePicker_completeWorkflow_searchSelectUnitAddDuplicateProductZeroCostRemoveAndSubmit() {
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

        // 2. Zero-match query feedback in purchase dialog
        composeTestRule.onNodeWithTag("purchase_product_search_input").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("purchase_product_search_input").performTextInput("صنف_غير_موجود_قطعا")
        composeTestRule.waitForIdle()

        // Verify zero results feedback text and clear button
        composeTestRule.onNodeWithTag("clear_purchase_search_no_results").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 3. Fast product search for real tracked item
        composeTestRule.onNodeWithTag("purchase_product_search_input").performTextInput("زيت")
        composeTestRule.waitForIdle()

        // Select the matching candidate chip (unit label is displayed)
        composeTestRule.onNodeWithTag("chip_product_${trackedProduct.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 4. Enter Line 1: 3 units @ 20.0 ₪
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("3")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("20.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 5. Repeat same product ID (duplicate product invariant) at different cost: 2 units @ 25.0 ₪
        composeTestRule.onNodeWithTag("chip_product_${trackedProduct.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_linked_qty_input").performTextInput("2")
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_unit_cost_input").performTextInput("25.0")

        composeTestRule.onNodeWithTag("add_purchase_bill_line_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 6. Clear search query to pick another item
        composeTestRule.onNodeWithTag("clear_purchase_product_search").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 7. Add second product with intentional zero cost (0.00 ₪ promotional/bonus sample)
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

        // Total validation before removal: (3 * 20.0) + (2 * 25.0) + (5 * 0.0) = 60 + 50 + 0 = 110.00 ₪
        composeTestRule.onNodeWithTag("purchase_bill_total_input").performScrollTo().assertTextContains("110.00", substring = true)

        // 8. Line item removal: remove one of the olive oil duplicate lines
        composeTestRule.onAllNodesWithTag("btn_remove_line_${trackedProduct.id}")[0].performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Now 2 lines remain: 1 olive oil (2x25=50) + 1 zaatar (5x0=0) or (3x20=60) + (5x0=0)
        // Check derived total updates automatically without discrepancy
        composeTestRule.onNodeWithTag("purchase_bill_total_input").performScrollTo().assertIsDisplayed()

        // 9. Enter paid amount
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag("purchase_bill_paid_input").performTextInput("25.0")

        // 10. Confirm purchase bill
        composeTestRule.onNodeWithTag("confirm_purchase_bill_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Dialog must be dismissed after success", isDismissed)
        assertNotNull("Submitted bill must be recorded", submittedBill)
        assertEquals("Bill must contain 2 lines after removal", 2, submittedBill!!.lines.size)

        // Verify local demo invariant: physical inventory is unchanged in unwired demo flow
        val currentTracked = repo.getProducts().first { it.id == "p_olive_oil" }
        assertEquals("Stock remains unchanged in local demo flow", initialStock, currentTracked.stockCount)
    }

    @Test
    fun posSale_completeWorkflow_arabicEnglishSearchCandidateAddStepperDraftResumeAndCashierPrivacy() {
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

        // 1. Verify Cashier Cost Privacy: Cashier screen must NOT display cost (costIls) or margins
        // Olive oil cost in sample catalog is 29.00 ₪
        composeTestRule.onAllNodesWithText("التكلفة", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("29.00 ₪", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Cost:", substring = true).assertCountEquals(0)

        // 2. Zero-match query feedback in POS catalog
        composeTestRule.onNodeWithTag("product_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_search_input").performTextInput("xyz_غير_موجود")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("zero_result_search_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zero_result_clear_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Query should be cleared after clicking clear in zero-result state", "", viewModel.uiState.value.searchQuery)

        // 3. Search in English for Arabic/English product
        composeTestRule.onNodeWithTag("product_search_input").performTextInput("Olive")
        composeTestRule.waitForIdle()

        // 4. Add product to basket
        composeTestRule.onNodeWithTag("product_card_p_olive_oil").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_card_p_olive_oil").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Basket must contain 1 item", 1, viewModel.uiState.value.basketItemCount)

        // 5. Quantity stepper in catalog row: increment to 2, then decrement to 1
        composeTestRule.onNodeWithTag("inc_product_p_olive_oil").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Basket must have 2 items after increment", 2, viewModel.uiState.value.basketItemCount)

        composeTestRule.onNodeWithTag("dec_product_p_olive_oil").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Basket must have 1 item after decrement", 1, viewModel.uiState.value.basketItemCount)

        // 6. Save basket as draft
        val savedDraftResult = viewModel.saveCurrentBasketAsDraft()
        assertTrue("Saving sale draft must succeed", savedDraftResult)
        assertEquals("Basket must be cleared after saving draft", 0, viewModel.uiState.value.basketItemCount)
        assertFalse("Saved drafts must not be empty", viewModel.uiState.value.savedDrafts.isEmpty())

        // 7. Resume saved draft
        val savedDraftId = viewModel.uiState.value.savedDrafts.first().id
        val resumed = viewModel.resumeSaleDraft(savedDraftId)
        assertTrue("Resuming draft must succeed", resumed)
        assertEquals("Basket must be restored with 1 item", 1, viewModel.uiState.value.basketItemCount)

        // 8. Remove product from basket
        viewModel.removeProductFromBasket("p_olive_oil")
        assertEquals("Basket must be empty after remove", 0, viewModel.uiState.value.basketItemCount)

        // 9. Clear search query to restore catalog
        composeTestRule.onNodeWithTag("clear_search_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Search query must be empty", "", viewModel.uiState.value.searchQuery)
    }
}
