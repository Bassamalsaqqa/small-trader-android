package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.model.TraderScreen
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MerchantSaleLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creditModeSurvivesSameSaleBackButNextSaleDefaultsToCash() {
        val vm = TraderViewModel()
        val product = vm.uiState.value.products.first { it.id == "p_olive_oil" }
        vm.navigateTo(TraderScreen.NEW_SALE)
        vm.addProductToBasket(product, 1)
        vm.navigateTo(TraderScreen.CASH_PAYMENT)
        compose.setContent { SmallTraderApp(vm) }
        compose.onNodeWithTag("payment_mode_credit_tab").performClick()
        compose.onNodeWithTag("complete_credit_sale_button").assertIsDisplayed()
        vm.navigateBack()
        compose.waitForIdle()
        vm.navigateTo(TraderScreen.CASH_PAYMENT)
        compose.waitForIdle()
        compose.onNodeWithTag("complete_credit_sale_button").assertIsDisplayed()
        val generation = vm.uiState.value.saleSessionId
        vm.assignCustomerToSale(vm.uiState.value.customers.first { it.balanceIls + product.priceIls < it.creditLimitIls })
        vm.completeCashSale(asCreditDebt = true)
        compose.waitForIdle()
        assertEquals(TraderScreen.RECEIPT, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.saleSessionId > generation)
        vm.startFreshSale()
        vm.addProductToBasket(product, 1)
        vm.navigateTo(TraderScreen.CASH_PAYMENT)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("payment_mode_cash_tab").assertIsSelected()
        compose.onNodeWithTag("complete_sale_button").assertIsDisplayed()
        compose.onNodeWithTag("complete_credit_sale_button").assertDoesNotExist()
    }

    @Test fun emptyCatalogCanReachAndResumeSavedDraftWithoutAddingUnrelatedProduct() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.NEW_SALE)
        vm.addProductToBasket(vm.uiState.value.products.first { it.stockCount > 0 }, 1)
        assertTrue(vm.saveCurrentBasketAsDraft("Held sale"))
        val draft = vm.uiState.value.savedDrafts.first()
        assertTrue(vm.uiState.value.basket.isEmpty())
        compose.setContent { SmallTraderApp(vm) }
        compose.onNodeWithTag("open_saved_drafts_button").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("invoice_review_screen").assertIsDisplayed()
        compose.onNodeWithTag("resume_draft_${draft.id}").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(vm.uiState.value.basket.isNotEmpty())
        assertFalse(vm.uiState.value.savedDrafts.any { it.id == draft.id })
    }
}
