package com.example.ui.navigation

import com.example.model.TraderScreen
import com.example.model.UserSession

object TraderNavigationPolicy {
    val roots = setOf(TraderScreen.HOME, TraderScreen.INVOICES_HISTORY, TraderScreen.PRODUCTS_CATALOG, TraderScreen.PEOPLE)
    fun allows(user: UserSession, screen: TraderScreen): Boolean = when (screen) {
        TraderScreen.SUPPLIERS, TraderScreen.SUPPLIER_DETAIL, TraderScreen.PURCHASES -> user.canManageSuppliers()
        TraderScreen.EXPENSES -> user.canManageExpenses()
        TraderScreen.FINANCIAL_REPORTS -> user.canViewReports()
        else -> true
    }
}

enum class MerchantAction { SALE, PURCHASE, COLLECT, PAY, PRODUCT, CUSTOMER, SUPPLIER, EXPENSE }
fun MerchantAction.allowed(user: UserSession): Boolean = when (this) {
    MerchantAction.PURCHASE, MerchantAction.PAY, MerchantAction.SUPPLIER -> user.canManageSuppliers()
    MerchantAction.PRODUCT -> user.canManageInventory()
    MerchantAction.EXPENSE -> user.canManageExpenses()
    else -> true
}
