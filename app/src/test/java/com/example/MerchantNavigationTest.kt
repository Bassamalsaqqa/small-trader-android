package com.example

import com.example.model.TraderScreen
import com.example.ui.navigation.MerchantAction
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.*
import org.junit.Test

class MerchantNavigationTest {
    @Test fun quickAddCancelKeepsSaleAndOrigin() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        vm.navigateTo(TraderScreen.NEW_SALE)
        vm.addProductToBasket(vm.uiState.value.products.first { it.stockCount > 0 }, 1)
        vm.updateSearchQuery("oil")
        vm.setQuickActionOpen(true)
        assertTrue(vm.navigateBack())
        assertFalse(vm.uiState.value.isQuickActionOpen)
        assertEquals(TraderScreen.NEW_SALE, vm.uiState.value.currentScreen)
        assertEquals(1, vm.uiState.value.basketItemCount)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.PRODUCTS_CATALOG, vm.uiState.value.currentScreen)
        vm.updateSearchQuery("stock")
        vm.navigateTo(TraderScreen.NEW_SALE)
        assertEquals("oil", vm.uiState.value.searchQuery)
    }

    @Test fun rootsReselectAndSaleOptionsPreserveDraft() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PEOPLE)
        vm.navigateTo(TraderScreen.PEOPLE)
        assertEquals(listOf(TraderScreen.HOME), vm.uiState.value.screenHistory)
        vm.openMerchantAction(MerchantAction.SALE)
        assertFalse(vm.uiState.value.isRootDestination)
        vm.navigateTo(TraderScreen.INVOICE_REVIEW)
        vm.navigateTo(TraderScreen.NEW_SALE)
        vm.navigateBack()
        assertEquals(TraderScreen.PEOPLE, vm.uiState.value.currentScreen)
    }

    @Test fun purchaseEntryOpensFormAndBackDismissesItFirst() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        vm.openMerchantAction(MerchantAction.PURCHASE)
        assertEquals(TraderScreen.PURCHASES, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.isReceivePurchaseDialogOpen)
        vm.navigateBack()
        assertFalse(vm.uiState.value.isReceivePurchaseDialogOpen)
        assertEquals(TraderScreen.PURCHASES, vm.uiState.value.currentScreen)
        vm.navigateBack()
        assertEquals(TraderScreen.PRODUCTS_CATALOG, vm.uiState.value.currentScreen)
    }

    @Test fun collectStartsChooserWithoutPosting() {
        val vm = TraderViewModel()
        val cash = vm.uiState.value.expectedBookCashIls
        vm.openMerchantAction(MerchantAction.COLLECT)
        assertEquals(MerchantAction.COLLECT, vm.uiState.value.pendingAction)
        vm.navigateBack()
        assertNull(vm.uiState.value.pendingAction)
        assertEquals(cash, vm.uiState.value.expectedBookCashIls, 0.001)
    }
    @Test fun historicalReceiptReturnsToSalesWithoutClearingActiveBasket() {
        val vm = TraderViewModel()
        vm.addProductToBasket(vm.uiState.value.products.first { it.stockCount > 0 }, 1)
        vm.navigateTo(TraderScreen.INVOICES_HISTORY)
        vm.openInvoiceReceipt(vm.uiState.value.invoices.first())
        assertFalse(vm.uiState.value.isNewlyCompletedSale)
        assertTrue(vm.navigateBack())
        assertEquals(TraderScreen.INVOICES_HISTORY, vm.uiState.value.currentScreen)
        assertEquals(1, vm.uiState.value.basketItemCount)
    }

    @Test fun backRestoresSearchAndCategoryForEachWorkspace() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        vm.updateSearchQuery("inventory")
        vm.selectCategory(com.example.model.ProductCategory.DAIRY)
        vm.navigateTo(TraderScreen.NEW_SALE)
        vm.updateSearchQuery("sale")
        vm.selectCategory(com.example.model.ProductCategory.PANTRY)
        vm.navigateBack()
        assertEquals("inventory", vm.uiState.value.searchQuery)
        assertEquals(com.example.model.ProductCategory.DAIRY, vm.uiState.value.selectedCategory)
        vm.navigateTo(TraderScreen.NEW_SALE)
        assertEquals("sale", vm.uiState.value.searchQuery)
        assertEquals(com.example.model.ProductCategory.PANTRY, vm.uiState.value.selectedCategory)
    }

    @Test fun moneyChooserCancelRestoresExactOriginWithoutNavigation() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        val before = vm.uiState.value
        listOf(MerchantAction.COLLECT, MerchantAction.PAY).forEach { action ->
            vm.openMerchantAction(action)
            assertEquals(before.currentScreen, vm.uiState.value.currentScreen)
            assertEquals(before.screenHistory, vm.uiState.value.screenHistory)
            vm.navigateBack()
            assertNull(vm.uiState.value.pendingAction)
            assertEquals(before.currentScreen, vm.uiState.value.currentScreen)
        }
    }

    @Test fun lowStockEntryClearsInventoryFiltersWithoutMutatingSaleOrStock() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.NEW_SALE)
        vm.updateSearchQuery("olive")
        vm.addProductToBasket(vm.uiState.value.products.first { it.stockCount > 0 }, 1)
        val before = vm.uiState.value
        vm.openLowStockInventory()
        assertTrue(vm.uiState.value.inventoryLowStockRequested)
        assertEquals("", vm.uiState.value.searchQuery)
        assertEquals(before.products, vm.uiState.value.products)
        assertEquals(before.basket, vm.uiState.value.basket)
        vm.consumeLowStockRequest()
        assertFalse(vm.uiState.value.inventoryLowStockRequested)
        vm.navigateTo(TraderScreen.NEW_SALE)
        assertEquals("olive", vm.uiState.value.searchQuery)
    }
    @Test fun quickCollectionShowsUpdatedAccountAndKeepsSuccessFeedback() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        val customer = vm.uiState.value.customers.first { it.balanceIls > 10 }
        vm.openPaymentCollectionForCustomer(customer)
        assertTrue(vm.confirmCustomerCollection(customer.id, 5.0, "", "review_collection"))
        assertEquals(TraderScreen.CUSTOMER_DETAIL, vm.uiState.value.currentScreen)
        assertFalse(vm.uiState.value.isPaymentCollectionDialogOpen)
        assertEquals(customer.balanceIls - 5.0, vm.uiState.value.selectedCustomerForDetail!!.balanceIls, 0.001)
        assertNotNull(vm.uiState.value.lastCustomerPaymentMessageEn)
    }

    @Test fun newCustomerQuickActionUsesCustomerDirectory() {
        val vm = TraderViewModel()
        vm.navigateTo(TraderScreen.PEOPLE)
        vm.openMerchantAction(MerchantAction.CUSTOMER)
        assertEquals(TraderScreen.CUSTOMERS, vm.uiState.value.currentScreen)
        assertTrue(vm.uiState.value.isAddCustomerDialogOpen)
    }

    @Test fun openMerchantActionConsumesPreviousPendingAndRoutesProductAndSupplierFromHome() {
        val vm = TraderViewModel()
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertNull(vm.uiState.value.pendingAction)

        // 1. From HOME: open PRODUCT action
        vm.openMerchantAction(MerchantAction.PRODUCT)
        assertEquals(TraderScreen.PRODUCTS_CATALOG, vm.uiState.value.currentScreen)
        assertEquals(MerchantAction.PRODUCT, vm.uiState.value.pendingAction)

        // 2. Reselect current destination with another action while pending is active
        // Verifies previous pendingAction is consumed before dispatching new action
        vm.openMerchantAction(MerchantAction.SUPPLIER)
        assertEquals(TraderScreen.SUPPLIERS, vm.uiState.value.currentScreen)
        assertEquals(MerchantAction.SUPPLIER, vm.uiState.value.pendingAction)

        // 3. Reselect same destination: invoking action when already on SUPPLIERS clears and sets cleanly
        vm.openMerchantAction(MerchantAction.SUPPLIER)
        assertEquals(TraderScreen.SUPPLIERS, vm.uiState.value.currentScreen)
        assertEquals(MerchantAction.SUPPLIER, vm.uiState.value.pendingAction)

        // 4. Consume action
        vm.consumeAction()
        assertNull(vm.uiState.value.pendingAction)
    }
}
