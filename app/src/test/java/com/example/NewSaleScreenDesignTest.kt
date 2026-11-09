package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.OfflineDemoTraderRepository
import com.example.model.DiscountPreset
import com.example.model.ProductCategory
import com.example.model.TraderScreen
import com.example.ui.screens.NewSaleScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rEG-w393dp-h852dp-xxhdpi")
class NewSaleScreenDesignTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun newSaleScreen_rendersNormalSearchActiveBasketFilledAndCheckoutReadyStates_inLightAndDarkThemes() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        viewModel.navigateTo(TraderScreen.NEW_SALE)

        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SmallTraderTheme(
                darkTheme = state.isDarkTheme,
                isRtl = state.language.isRtl
            ) {
                NewSaleScreen(
                    state = state,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onSelectCategory = viewModel::selectCategory,
                    onAddProduct = { product -> viewModel.addProductToBasket(product, 1) },
                    onDecrementProduct = viewModel::decrementProductInBasket,
                    onRemoveProduct = viewModel::removeProductFromBasket,
                    onClearBasket = viewModel::clearBasket,
                    onOpenScanner = { viewModel.setScannerSheetOpen(true) },
                    onOpenCustomItem = { viewModel.openCustomItemDialog(true) },
                    onProceedToInvoiceReview = { viewModel.navigateTo(TraderScreen.INVOICE_REVIEW) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // =====================================================================
        // 1. NORMAL STATE (Empty basket, unified search + scanner, exception-based stock)
        // =====================================================================
        composeTestRule.onNodeWithTag("new_sale_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sale_scan_barcode_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sale_custom_item_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("category_chip_ALL").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_catalog_list").assertIsDisplayed()

        // Quiet empty-basket status bar shows 0.00 ₪
        composeTestRule.onNodeWithTag("basket_subtotal_text", useUnmergedTree = true)
            .assertTextContains("0.00 ₪", substring = true)

        // =====================================================================
        // 2. SEARCH-ACTIVE STATE (Query filter, clear button, zero-result recovery)
        // =====================================================================
        viewModel.updateSearchQuery("زيت")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("clear_search_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_card_p_olive_oil").assertIsDisplayed()

        // Switch to Beverages while searching "زيت" -> Zero-result state with cross-category recovery
        viewModel.selectCategory(ProductCategory.BEVERAGES)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("zero_result_search_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_all_categories_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("zero_result_clear_button").assertIsDisplayed()

        // Tap "Show in All" recovery button -> returns to ALL categories with matching items
        composeTestRule.onNodeWithTag("search_all_categories_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ProductCategory.ALL, viewModel.uiState.value.selectedCategory)

        // Clear search query
        composeTestRule.onNodeWithTag("clear_search_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("", viewModel.uiState.value.searchQuery)

        // =====================================================================
        // 3. BASKET-FILLED STATE (Inline line math, tactile stepper, active checkout dock)
        // =====================================================================
        composeTestRule.onNodeWithTag("product_card_p_olive_oil").performClick()
        composeTestRule.waitForIdle()

        // First tap adds 1 unit (38.00 ₪) and reveals inline stepper (- 1 +)
        composeTestRule.onNodeWithTag("qty_product_p_olive_oil", useUnmergedTree = true)
            .assertTextContains("1")
        composeTestRule.onNodeWithTag("inc_product_p_olive_oil").performClick()
        composeTestRule.waitForIdle()

        // Second tap increments to 2 units -> inline line math "38.00 ₪ × 2 = 76.00 ₪"
        composeTestRule.onNodeWithTag("qty_product_p_olive_oil", useUnmergedTree = true)
            .assertTextContains("2")
        composeTestRule.onNodeWithText("38.00 ₪ × 2 = 76.00 ₪", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()

        // Add Jenin Za'atar (16.50 ₪) -> total 92.50 ₪ (3 units across 2 lines)
        composeTestRule.onNodeWithTag("add_product_p_zaatar_jenin").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("basket_subtotal_text", useUnmergedTree = true)
            .assertTextContains("92.50 ₪", substring = true)
        composeTestRule.onNodeWithTag("review_invoice_button").assertIsDisplayed().assertIsEnabled()

        // =====================================================================
        // 4. CHECKOUT-READY STATE (Expanded basket drawer, customer/discount, proceed to Review)
        // =====================================================================
        val abuTareq = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        viewModel.assignCustomerToSale(abuTareq)
        viewModel.selectDiscountPreset(DiscountPreset.ROUND_DOWN) // 92.50 -> 90.00 (-2.50 discount)

        // Expand quick basket drawer from checkout dock
        composeTestRule.onNodeWithTag("toggle_basket_drawer_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("clear_basket_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("basket_inc_p_zaatar_jenin").assertIsDisplayed()
        composeTestRule.onNodeWithTag("basket_dec_p_olive_oil").assertIsDisplayed()
        composeTestRule.onNodeWithTag("basket_remove_p_zaatar_jenin").assertIsDisplayed()
        composeTestRule.onNodeWithTag("basket_subtotal_text", useUnmergedTree = true)
            .assertTextContains("90.00 ₪", substring = true)

        // Verify coherent Dark theme presentation on the checkout-ready state
        viewModel.toggleTheme()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.isDarkTheme)
        composeTestRule.onNodeWithTag("new_sale_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("review_invoice_button").assertIsDisplayed()

        // Switch back to Light theme and proceed to Invoice Review
        viewModel.toggleTheme()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("review_invoice_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(TraderScreen.INVOICE_REVIEW, viewModel.uiState.value.currentScreen)
    }
}
