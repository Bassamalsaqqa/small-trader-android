package com.example

import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.ui.navigation.*
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.*
import org.junit.Test

class MerchantRoleTest {
    @Test fun cashierCannotEnterRestrictedDestinationsOrLaunchActions() {
        val vm = TraderViewModel()
        val cashier = vm.uiState.value.currentSession.copy(role = UserRole.CASHIER)
        vm.switchUserSession(cashier)
        listOf(TraderScreen.SUPPLIERS, TraderScreen.SUPPLIER_DETAIL, TraderScreen.PURCHASES,
            TraderScreen.EXPENSES, TraderScreen.FINANCIAL_REPORTS).forEach {
            assertFalse(TraderNavigationPolicy.allows(cashier, it))
            vm.navigateTo(it)
            assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        }
        listOf(MerchantAction.PURCHASE, MerchantAction.PAY, MerchantAction.PRODUCT,
            MerchantAction.SUPPLIER, MerchantAction.EXPENSE).forEach {
            assertFalse(it.allowed(cashier))
            vm.openMerchantAction(it)
            assertNull(vm.uiState.value.pendingAction)
            assertFalse(vm.uiState.value.isReceivePurchaseDialogOpen)
            assertFalse(vm.uiState.value.isAddExpenseDialogOpen)
        }
        vm.selectSupplierForDetail(vm.uiState.value.suppliers.first())
        assertNull(vm.uiState.value.selectedSupplierForDetail)
    }

    @Test fun switchingToCashierEvictsRestrictedTaskAndOverlay() {
        val vm = TraderViewModel()
        vm.openMerchantAction(MerchantAction.PURCHASE)
        vm.switchUserSession(vm.uiState.value.currentSession.copy(role = UserRole.CASHIER))
        assertEquals(TraderScreen.HOME, vm.uiState.value.currentScreen)
        assertFalse(vm.uiState.value.isReceivePurchaseDialogOpen)
        assertNull(vm.uiState.value.pendingAction)
        assertTrue(vm.uiState.value.screenHistory.all { TraderNavigationPolicy.allows(vm.uiState.value.currentSession, it) })
    }
}
