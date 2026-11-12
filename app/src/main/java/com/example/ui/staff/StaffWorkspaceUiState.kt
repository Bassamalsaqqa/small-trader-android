package com.example.ui.staff

import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.model.AppLanguage

/**
 * Safe presentation view data for an authenticated staff actor.
 * Bearer tokens and sensitive passwords/secrets are strictly excluded.
 */
data class SafeStaffActorViewData(
    val actorId: String,
    val actorEmail: String,
    val actorName: String,
    val roleLabel: String
) {
    override fun toString(): String =
        "SafeStaffActorViewData(actorId=$actorId, roleLabel='$roleLabel', actorName='[REDACTED]')"
}

/**
 * Safe presentation view data for a company tenant.
 */
data class SafeCompanyViewData(
    val companyId: String,
    val companyName: String,
    val currency: String = "USD"
) {
    override fun toString(): String =
        "SafeCompanyViewData(companyId=$companyId, companyName='[REDACTED]', currency='$currency')"
}

/**
 * Mutually exclusive secondary authentication mode.
 */
enum class MfaMode {
    TOTP,
    RECOVERY_CODE
}

/**
 * High-level lifecycle phase of the Staff Read Workspace.
 */
enum class StaffWorkspacePhase {
    UNCONFIGURED,
    INITIALIZING,
    SIGN_IN,
    MFA_REQUIRED,
    COMPANY_SELECTION,
    READ_READY,
    LOCKED,
    STORAGE_FAILED,
    OFFLINE_ERROR
}

/**
 * Active tab within the ReadReady workspace.
 */
enum class StaffWorkspaceTab {
    PRODUCTS,
    CUSTOMERS
}

/**
 * Immutable UI state for the Staff Read Workspace.
 *
 * Security Invariants:
 * 1. Bearer tokens are NEVER stored in this UI state.
 * 2. Passwords and MFA codes are NEVER stored in this UI state.
 * 3. Client-side role strings are strictly informative and convey zero local posting authority.
 */
data class StaffWorkspaceUiState(
    val phase: StaffWorkspacePhase = StaffWorkspacePhase.INITIALIZING,
    val selectedTab: StaffWorkspaceTab = StaffWorkspaceTab.PRODUCTS,
    val activeCompany: SafeCompanyViewData? = null,
    val staffActor: SafeStaffActorViewData? = null,
    val availableCompanies: List<SafeCompanyViewData> = emptyList(),
    val mfaMode: MfaMode = MfaMode.TOTP,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val lockedReason: String? = null,
    val storageErrorMessage: String? = null,
    val offlineErrorMessage: String? = null,

    // Product catalog state
    val products: List<ProductCatalogItem> = emptyList(),
    val isProductsLoading: Boolean = false,
    val isProductsLoadingMore: Boolean = false,
    val productsNextCursor: String? = null,
    val productsHasMore: Boolean = false,
    val productsError: String? = null,
    val productSearchQuery: String = "",

    // Customer directory state
    val customers: List<CustomerDirectoryItem> = emptyList(),
    val isCustomersLoading: Boolean = false,
    val isCustomersLoadingMore: Boolean = false,
    val customersNextCursor: String? = null,
    val customersHasMore: Boolean = false,
    val customersError: String? = null,
    val customerSearchQuery: String = "",

    // Presentation preferences
    val language: AppLanguage = AppLanguage.AR,
    val isDarkTheme: Boolean = false
) {
    override fun toString(): String =
        "StaffWorkspaceUiState(phase=$phase, selectedTab=$selectedTab, isSubmitting=$isSubmitting, " +
                "productsCount=${products.size}, customersCount=${customers.size}, language=${language.code})"
}
