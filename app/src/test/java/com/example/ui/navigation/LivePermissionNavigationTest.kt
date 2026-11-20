package com.example.ui.navigation

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test

class LivePermissionNavigationTest {
    private fun session(role: UserRole, permissions: Set<String>) = UserSession("actor", "Staff", "staff", role, "Company", "Company", isDemoSession = false, permissions = permissions)
    @Test fun ownerLabelDoesNotGrantMissingPermissions() {
        val user = session(UserRole.OWNER, emptySet())
        assertFalse(user.canManageInventory())
        assertFalse(user.canManageSuppliers())
        assertFalse(user.canViewReports())
        assertFalse(MerchantAction.SALE.allowed(user))
        assertFalse(MerchantAction.PAY.allowed(user))
    }
    @Test fun customRoleUsesActualServerPermissions() {
        val user = session(UserRole.CASHIER, setOf("inventory.product.manage", "vendors.view", "money.receipt.create"))
        assertTrue(user.canManageInventory())
        assertTrue(user.canManageSuppliers())
        assertTrue(MerchantAction.PRODUCT.allowed(user))
        assertTrue(MerchantAction.COLLECT.allowed(user))
        assertFalse(MerchantAction.PAY.allowed(user))
    }
}
