package com.example.ui.staff

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.session.AuthDenialListener
import com.example.data.mobile.session.CoordinatorResult
import com.example.data.mobile.session.LockReason
import com.example.data.mobile.session.LogoutResult
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionCoordinator
import com.example.model.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel managing the lifecycle and state transitions of the Staff Read Workspace.
 *
 * Invariants:
 * 1. Off-main-thread I/O: All coordinator operations run on [ioDispatcher] (default [Dispatchers.IO]).
 * 2. Deduplication: Button submissions are deduplicated while in-flight.
 * 3. Scope and Generation Verification: Async responses verify coordinator generation and active company
 *    scope before committing to UI state. Obsolete or stale responses are rejected.
 * 4. Immediate List Invalidation: On company switch, logout, or 401 Auth Denial, catalog and directory lists
 *    are wiped immediately from memory. Late responses never repopulate foreign lists.
 * 5. Secret Privacy: Passwords, MFA codes, and recovery codes exist only in transient in-memory references,
 *    never in SavedStateHandle, disk, or UI state classes. Bearer tokens are never exposed in UI state.
 */
class StaffWorkspaceViewModel(
    private val coordinator: StaffSessionCoordinator,
    private val configuredOrigin: String = "",
    private val allowHttpLoopback: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authDenialListener: AuthDenialListener? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        if (configuredOrigin.isBlank()) {
            StaffWorkspaceUiState(phase = StaffWorkspacePhase.UNCONFIGURED)
        } else {
            StaffWorkspaceUiState(phase = StaffWorkspacePhase.INITIALIZING)
        }
    )
    val uiState: StateFlow<StaffWorkspaceUiState> = _uiState.asStateFlow()

    // In-memory transient credentials (cleared immediately upon consumption/logout/exit)
    private var transientPassword: String = ""
    private var transientMfaCode: String = ""
    private var transientRecoveryCode: String = ""
    private var pendingEmail: String = ""
    private var pendingPassword: String = ""

    // In-flight job handles for cooperative cancellation
    private var authJob: Job? = null
    private var productsJob: Job? = null
    private var customersJob: Job? = null

    init {
        if (configuredOrigin.isNotBlank()) {
            restoreSession()
        }
    }

    /**
     * Clears all in-memory transient credential variables.
     */
    fun clearTransientCredentials() {
        transientPassword = ""
        transientMfaCode = ""
        transientRecoveryCode = ""
        pendingPassword = ""
    }

    /**
     * Updates transient password memory.
     */
    fun setTransientPassword(password: String) {
        transientPassword = password
    }

    /**
     * Updates transient MFA code memory.
     */
    fun setTransientMfaCode(code: String) {
        transientMfaCode = code
    }

    /**
     * Updates transient recovery code memory.
     */
    fun setTransientRecoveryCode(code: String) {
        transientRecoveryCode = code
    }

    /**
     * Toggles mutually exclusive MFA input mode.
     */
    fun setMfaMode(mode: MfaMode) {
        _uiState.update { it.copy(mfaMode = mode, errorMessage = null) }
        transientMfaCode = ""
        transientRecoveryCode = ""
    }

    /**
     * Switches selected read workspace tab (Products / Customers).
     */
    fun selectTab(tab: StaffWorkspaceTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /**
     * Toggles presentation language between Arabic and English.
     */
    fun toggleLanguage() {
        _uiState.update {
            it.copy(language = if (it.language == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR)
        }
    }

    /**
     * Toggles dark/light theme.
     */
    fun toggleTheme() {
        _uiState.update { it.copy(isDarkTheme = !it.isDarkTheme) }
    }

    /**
     * Restores persisted session from secure storage, verifying token expiry and bootstrap with server.
     */
    fun restoreSession() {
        if (_uiState.value.isSubmitting) return
        authJob?.cancel()

        authJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = StaffWorkspacePhase.INITIALIZING,
                    isSubmitting = true,
                    errorMessage = null,
                    lockedReason = null
                )
            }

            val result = withContext(ioDispatcher) {
                coordinator.restoreSession()
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    when (val state = result.value) {
                        is StaffAuthState.ReadReady -> {
                            val selectedId = state.session.selectedCompanyId
                            if (selectedId != null && coordinator.isScopeActive(result.generation, selectedId)) {
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = state.bootstrap.companyId.value,
                                            companyName = state.bootstrap.companyName
                                        ),
                                        staffActor = SafeStaffActorViewData(
                                            actorId = state.bootstrap.staff.id.value,
                                            actorEmail = state.session.actorEmail ?: "",
                                            actorName = state.bootstrap.staff.name,
                                            roleLabel = state.bootstrap.staff.role
                                        )
                                    )
                                }
                                loadProducts(refresh = true)
                                loadCustomers(refresh = true)
                            } else {
                                transitionToSignIn()
                            }
                        }
                        is StaffAuthState.LoggedOut -> {
                            transitionToSignIn()
                        }
                        else -> {
                            transitionToSignIn()
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    clearListsImmediately()
                    _uiState.update {
                        it.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isSubmitting = false,
                            lockedReason = "Authentication revoked or invalid (HTTP 401)"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    clearListsImmediately()
                    _uiState.update {
                        it.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isSubmitting = false,
                            lockedReason = "Tenant access forbidden (HTTP 403)"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    when (val state = coordinator.authState) {
                        is StaffAuthState.Locked -> {
                            _uiState.update {
                                it.copy(
                                    phase = StaffWorkspacePhase.LOCKED,
                                    isSubmitting = false,
                                    lockedReason = extractLockReasonMessage(state.reason)
                                )
                            }
                        }
                        is StaffAuthState.StorageFailed -> {
                            _uiState.update {
                                it.copy(
                                    phase = StaffWorkspacePhase.STORAGE_FAILED,
                                    isSubmitting = false,
                                    storageErrorMessage = state.message
                                )
                            }
                        }
                        is StaffAuthState.TransientError -> {
                            _uiState.update {
                                it.copy(
                                    phase = StaffWorkspacePhase.OFFLINE_ERROR,
                                    isSubmitting = false,
                                    offlineErrorMessage = state.category
                                )
                            }
                        }
                        else -> {
                            transitionToSignIn()
                        }
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Stale operation ignored; newer lifecycle intent active
                }
            }
        }
    }

    /**
     * Authenticates staff with email and password.
     * The coordinator orchestrates acquiring a fresh challenge nonce hidden from the caller.
     */
    fun signIn(
        email: String,
        passwordOverride: String? = null,
        deviceName: String = "Android Staff Device"
    ) {
        if (_uiState.value.isSubmitting) return
        val pwd = passwordOverride ?: transientPassword
        if (email.isBlank() || pwd.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = if (it.language == AppLanguage.AR) "يرجى إدخال البريد الإلكتروني وكلمة المرور" else "Please enter email and password")
            }
            return
        }

        authJob?.cancel()
        productsJob?.cancel()
        customersJob?.cancel()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            pendingEmail = email.trim()
            pendingPassword = pwd

            val result = withContext(ioDispatcher) {
                coordinator.login(
                    email = pendingEmail,
                    password = pwd,
                    deviceName = deviceName
                )
            }

            if (coordinator.authState !is StaffAuthState.MfaRequired) {
                clearTransientCredentials()
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    when (val state = coordinator.authState) {
                        is StaffAuthState.ReadReady -> {
                            val selectedId = state.session.selectedCompanyId
                            if (selectedId != null && coordinator.isScopeActive(result.generation, selectedId)) {
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = state.bootstrap.companyId.value,
                                            companyName = state.bootstrap.companyName
                                        ),
                                        staffActor = SafeStaffActorViewData(
                                            actorId = state.bootstrap.staff.id.value,
                                            actorEmail = state.session.actorEmail ?: "",
                                            actorName = state.bootstrap.staff.name,
                                            roleLabel = state.bootstrap.staff.role
                                        ),
                                        errorMessage = null
                                    )
                                }
                                loadProducts(refresh = true)
                                loadCustomers(refresh = true)
                            }
                        }
                        is StaffAuthState.MfaRequired -> {
                            _uiState.update {
                                it.copy(
                                    phase = StaffWorkspacePhase.MFA_REQUIRED,
                                    isSubmitting = false,
                                    mfaMode = MfaMode.TOTP,
                                    errorMessage = null
                                )
                            }
                        }
                        is StaffAuthState.CompanySelectionRequired -> {
                            val companies = state.availableCompanies.map { c ->
                                SafeCompanyViewData(companyId = c.id.value, companyName = c.name)
                            }
                            _uiState.update {
                                it.copy(
                                    phase = StaffWorkspacePhase.COMPANY_SELECTION,
                                    isSubmitting = false,
                                    availableCompanies = companies,
                                    errorMessage = null
                                )
                            }
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    isSubmitting = false,
                                    errorMessage = "Unexpected coordinator state after login"
                                )
                            }
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "بيانات الاعتماد غير صحيحة" else "Invalid credentials"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "تم رفض الوصول للمنشأة" else "Access forbidden"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = result.message
                        )
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Stale operation discarded
                }
            }
        }
    }

    /**
     * Retries login with secondary authentication (MFA code or Recovery code).
     * The coordinator orchestrates acquiring a FRESH challenge nonce before dispatching credentials.
     */
    fun retryWithMfa(
        email: String? = null,
        passwordOverride: String? = null,
        codeOverride: String? = null,
        isRecovery: Boolean = false,
        deviceName: String = "Android Staff Device"
    ) {
        if (_uiState.value.isSubmitting) return
        val emailToUse = email?.trim()?.ifBlank { null } ?: pendingEmail
        val pwd = passwordOverride ?: pendingPassword
        val code = codeOverride ?: if (_uiState.value.mfaMode == MfaMode.TOTP) transientMfaCode else transientRecoveryCode

        if (code.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = if (it.language == AppLanguage.AR) "يرجى إدخال رمز التحقق" else "Please enter verification code"
                )
            }
            return
        }

        val mfaCode = if (!isRecovery && _uiState.value.mfaMode == MfaMode.TOTP) code.trim() else null
        val recoveryCode = if (isRecovery || _uiState.value.mfaMode == MfaMode.RECOVERY_CODE) code.trim() else null

        authJob?.cancel()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val result = withContext(ioDispatcher) {
                try {
                    coordinator.retryLoginWithMfa(
                        email = emailToUse,
                        password = pwd,
                        deviceName = deviceName,
                        mfaCode = mfaCode,
                        recoveryCode = recoveryCode
                    )
                } finally {
                    clearTransientCredentials()
                }
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    when (val state = coordinator.authState) {
                        is StaffAuthState.ReadReady -> {
                            val selectedId = state.session.selectedCompanyId
                            if (selectedId != null && coordinator.isScopeActive(result.generation, selectedId)) {
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = state.bootstrap.companyId.value,
                                            companyName = state.bootstrap.companyName
                                        ),
                                        staffActor = SafeStaffActorViewData(
                                            actorId = state.bootstrap.staff.id.value,
                                            actorEmail = state.session.actorEmail ?: "",
                                            actorName = state.bootstrap.staff.name,
                                            roleLabel = state.bootstrap.staff.role
                                        ),
                                        errorMessage = null
                                    )
                                }
                                loadProducts(refresh = true)
                                loadCustomers(refresh = true)
                            }
                        }
                        is StaffAuthState.CompanySelectionRequired -> {
                            val companies = state.availableCompanies.map { c ->
                                SafeCompanyViewData(companyId = c.id.value, companyName = c.name)
                            }
                            _uiState.update {
                                it.copy(
                                    phase = StaffWorkspacePhase.COMPANY_SELECTION,
                                    isSubmitting = false,
                                    availableCompanies = companies,
                                    errorMessage = null
                                )
                            }
                        }
                        else -> {
                            _uiState.update {
                                it.copy(
                                    isSubmitting = false,
                                    errorMessage = "Unexpected coordinator state after MFA"
                                )
                            }
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "رمز التحقق غير صحيح" else "Invalid verification code"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "تم رفض الوصول للمنشأة" else "Access forbidden"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = result.message
                        )
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Stale operation discarded
                }
            }
        }
    }

    /**
     * Navigates to company selection view if multiple companies are available.
     */
    fun openCompanySelection() {
        if (_uiState.value.availableCompanies.isNotEmpty()) {
            _uiState.update { it.copy(phase = StaffWorkspacePhase.COMPANY_SELECTION) }
        }
    }

    /**
     * Selects active company when staff has multiple company memberships.
     */
    fun selectCompany(companyId: String) {
        if (_uiState.value.isSubmitting) return
        val serverUlid = try {
            ServerUlid.parse(companyId)
        } catch (_: Exception) {
            _uiState.update { it.copy(errorMessage = "Invalid company identifier") }
            return
        }

        if (_uiState.value.activeCompany != null) {
            switchCompany(companyId)
            return
        }

        // Immediately clear previous company lists
        clearListsImmediately()

        authJob?.cancel()
        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val result = withContext(ioDispatcher) {
                coordinator.selectCompany(serverUlid)
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    val state = coordinator.authState
                    if (state is StaffAuthState.ReadReady &&
                        coordinator.isScopeActive(result.generation, serverUlid)
                    ) {
                        _uiState.update { s ->
                            s.copy(
                                phase = StaffWorkspacePhase.READ_READY,
                                isSubmitting = false,
                                activeCompany = SafeCompanyViewData(
                                    companyId = state.bootstrap.companyId.value,
                                    companyName = state.bootstrap.companyName
                                ),
                                staffActor = SafeStaffActorViewData(
                                    actorId = state.bootstrap.staff.id.value,
                                    actorEmail = state.session.actorEmail ?: "",
                                    actorName = state.bootstrap.staff.name,
                                    roleLabel = state.bootstrap.staff.role
                                ),
                                errorMessage = null
                            )
                        }
                        loadProducts(refresh = true)
                        loadCustomers(refresh = true)
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    clearListsImmediately()
                    _uiState.update {
                        it.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isSubmitting = false,
                            lockedReason = "Authentication revoked or invalid (HTTP 401)"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = "Access forbidden for selected company")
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = result.message)
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Stale operation discarded
                }
            }
        }
    }

    /**
     * Switches company for an active session. Cancels obsolete in-flight requests
     * and clears old product/customer lists immediately.
     */
    fun switchCompany(newCompanyId: String) {
        if (_uiState.value.isSubmitting) return
        val serverUlid = try {
            ServerUlid.parse(newCompanyId)
        } catch (_: Exception) {
            _uiState.update { it.copy(errorMessage = "Invalid company identifier") }
            return
        }

        // Cooperative cancellation of in-flight catalog and directory jobs
        productsJob?.cancel()
        customersJob?.cancel()
        authJob?.cancel()

        // Invalidate foreign lists immediately
        clearListsImmediately()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val result = withContext(ioDispatcher) {
                coordinator.switchCompany(serverUlid)
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    val state = coordinator.authState
                    if (state is StaffAuthState.ReadReady &&
                        coordinator.isScopeActive(result.generation, serverUlid)
                    ) {
                        _uiState.update { s ->
                            s.copy(
                                phase = StaffWorkspacePhase.READ_READY,
                                isSubmitting = false,
                                activeCompany = SafeCompanyViewData(
                                    companyId = state.bootstrap.companyId.value,
                                    companyName = state.bootstrap.companyName
                                ),
                                errorMessage = null
                            )
                        }
                        loadProducts(refresh = true)
                        loadCustomers(refresh = true)
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    clearListsImmediately()
                    _uiState.update {
                        it.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isSubmitting = false,
                            lockedReason = "Authentication revoked during company switch (HTTP 401)"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = "Access forbidden for new company")
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = result.message)
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Stale operation discarded
                }
            }
        }
    }

    /**
     * Logs out active staff: cancels all in-flight jobs, purges in-memory lists and transient
     * credentials, invalidates generation, and transitions immediately to SIGN_IN.
     */
    fun logout() {
        productsJob?.cancel()
        customersJob?.cancel()
        authJob?.cancel()

        clearTransientCredentials()
        clearListsImmediately()

        _uiState.update {
            it.copy(
                phase = StaffWorkspacePhase.SIGN_IN,
                activeCompany = null,
                staffActor = null,
                availableCompanies = emptyList(),
                isSubmitting = false,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            withContext(ioDispatcher) {
                coordinator.logout()
            }
        }
    }

    /**
     * Fetches bounded product catalog for the selected company.
     * Enforces generation and active company scope check before committing to UI state.
     */
    fun loadProducts(refresh: Boolean = false, loadMore: Boolean = false) {
        val activeCompany = _uiState.value.activeCompany ?: return
        val companyUlid = try {
            ServerUlid.parse(activeCompany.companyId)
        } catch (_: Exception) {
            return
        }

        if (loadMore) {
            if (_uiState.value.isProductsLoadingMore || !_uiState.value.productsHasMore || _uiState.value.productsNextCursor == null) {
                return
            }
        } else if (refresh) {
            productsJob?.cancel()
        } else if (_uiState.value.isProductsLoading) {
            return
        }

        val opGen = coordinator.currentGeneration
        val cursor = if (loadMore) _uiState.value.productsNextCursor else null

        productsJob = viewModelScope.launch {
            _uiState.update {
                if (loadMore) {
                    it.copy(isProductsLoadingMore = true, productsError = null)
                } else {
                    it.copy(isProductsLoading = true, productsError = null)
                }
            }

            val query = PaginationQuery(
                limit = 50,
                cursor = cursor,
                search = _uiState.value.productSearchQuery.ifBlank { null }
            )

            val result = withContext(ioDispatcher) {
                coordinator.getProducts(query)
            }

            if (result is CoordinatorResult.AuthDenied) {
                clearListsImmediately()
                _uiState.update { state ->
                    state.copy(
                        phase = StaffWorkspacePhase.LOCKED,
                        isProductsLoading = false,
                        isProductsLoadingMore = false,
                        lockedReason = "Authentication revoked or invalid (HTTP 401)"
                    )
                }
                return@launch
            }

            // Verify active scope before state commit
            if (!coordinator.isScopeActive(opGen, companyUlid)) {
                return@launch
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    val response = result.value
                    _uiState.update { state ->
                        if (state.activeCompany?.companyId != companyUlid.value) return@update state
                        val updatedList = if (loadMore) {
                            state.products + response.products
                        } else {
                            response.products
                        }
                        state.copy(
                            products = updatedList,
                            isProductsLoading = false,
                            isProductsLoadingMore = false,
                            productsNextCursor = response.nextCursor,
                            productsHasMore = response.hasMore,
                            productsError = null
                        )
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    // Handled above before scope check
                }
                is CoordinatorResult.Forbidden -> {
                    _uiState.update { state ->
                        if (state.activeCompany?.companyId != companyUlid.value) return@update state
                        state.copy(
                            isProductsLoading = false,
                            isProductsLoadingMore = false,
                            productsError = "Catalog access forbidden"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    checkAndHandleAuthDenied(result.message)
                    _uiState.update { state ->
                        if (state.activeCompany?.companyId != companyUlid.value) return@update state
                        state.copy(
                            isProductsLoading = false,
                            isProductsLoadingMore = false,
                            productsError = result.message
                        )
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Obsolete operation discarded
                }
            }
        }
    }

    /**
     * Updates product search query and re-executes search.
     */
    fun updateProductSearchQuery(query: String) {
        _uiState.update { it.copy(productSearchQuery = query) }
        loadProducts(refresh = true)
    }

    /**
     * Fetches bounded customer directory for the selected company.
     * Enforces generation and active company scope check before committing to UI state.
     */
    fun loadCustomers(refresh: Boolean = false, loadMore: Boolean = false) {
        val activeCompany = _uiState.value.activeCompany ?: return
        val companyUlid = try {
            ServerUlid.parse(activeCompany.companyId)
        } catch (_: Exception) {
            return
        }

        if (loadMore) {
            if (_uiState.value.isCustomersLoadingMore || !_uiState.value.customersHasMore || _uiState.value.customersNextCursor == null) {
                return
            }
        } else if (refresh) {
            customersJob?.cancel()
        } else if (_uiState.value.isCustomersLoading) {
            return
        }

        val opGen = coordinator.currentGeneration
        val cursor = if (loadMore) _uiState.value.customersNextCursor else null

        customersJob = viewModelScope.launch {
            _uiState.update {
                if (loadMore) {
                    it.copy(isCustomersLoadingMore = true, customersError = null)
                } else {
                    it.copy(isCustomersLoading = true, customersError = null)
                }
            }

            val query = PaginationQuery(
                limit = 50,
                cursor = cursor,
                search = _uiState.value.customerSearchQuery.ifBlank { null }
            )

            val result = withContext(ioDispatcher) {
                coordinator.getCustomers(query)
            }

            if (result is CoordinatorResult.AuthDenied) {
                clearListsImmediately()
                _uiState.update { state ->
                    state.copy(
                        phase = StaffWorkspacePhase.LOCKED,
                        isCustomersLoading = false,
                        isCustomersLoadingMore = false,
                        lockedReason = "Authentication revoked or invalid (HTTP 401)"
                    )
                }
                return@launch
            }

            // Verify active scope before state commit
            if (!coordinator.isScopeActive(opGen, companyUlid)) {
                return@launch
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    val response = result.value
                    _uiState.update { state ->
                        if (state.activeCompany?.companyId != companyUlid.value) return@update state
                        val updatedList = if (loadMore) {
                            state.customers + response.customers
                        } else {
                            response.customers
                        }
                        state.copy(
                            customers = updatedList,
                            isCustomersLoading = false,
                            isCustomersLoadingMore = false,
                            customersNextCursor = response.nextCursor,
                            customersHasMore = response.hasMore,
                            customersError = null
                        )
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    // Handled above before scope check
                }
                is CoordinatorResult.Forbidden -> {
                    _uiState.update { state ->
                        if (state.activeCompany?.companyId != companyUlid.value) return@update state
                        state.copy(
                            isCustomersLoading = false,
                            isCustomersLoadingMore = false,
                            customersError = "Customer directory access forbidden"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    checkAndHandleAuthDenied(result.message)
                    _uiState.update { state ->
                        if (state.activeCompany?.companyId != companyUlid.value) return@update state
                        state.copy(
                            isCustomersLoading = false,
                            isCustomersLoadingMore = false,
                            customersError = result.message
                        )
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Obsolete operation discarded
                }
            }
        }
    }

    /**
     * Updates customer directory search query and re-executes search.
     */
    fun updateCustomerSearchQuery(query: String) {
        _uiState.update { it.copy(customerSearchQuery = query) }
        loadCustomers(refresh = true)
    }

    /**
     * Clears products and customers lists immediately from memory.
     */
    private fun clearListsImmediately() {
        _uiState.update {
            it.copy(
                products = emptyList(),
                isProductsLoading = false,
                isProductsLoadingMore = false,
                productsNextCursor = null,
                productsHasMore = false,
                productsError = null,
                customers = emptyList(),
                isCustomersLoading = false,
                isCustomersLoadingMore = false,
                customersNextCursor = null,
                customersHasMore = false,
                customersError = null
            )
        }
    }

    /**
     * Checks if a returned error indicates 401 Auth Denial, in which case
     * in-memory data is cleared immediately and UI transitions to LOCKED.
     */
    private fun checkAndHandleAuthDenied(errorMessage: String) {
        val state = coordinator.authState
        if (state is StaffAuthState.Locked) {
            clearListsImmediately()
            _uiState.update {
                it.copy(
                    phase = StaffWorkspacePhase.LOCKED,
                    lockedReason = extractLockReasonMessage(state.reason)
                )
            }
        }
    }

    private fun extractLockReasonMessage(reason: LockReason): String {
        return when (reason) {
            is LockReason.AuthDenied -> reason.message
            is LockReason.MembershipDenied -> reason.message
            is LockReason.KeyUnavailable -> reason.message
            is LockReason.KeyLocked -> reason.message
            is LockReason.StorageClearFailed -> reason.message
            is LockReason.ScopeMismatch -> reason.message
            is LockReason.CorruptSession -> reason.message
        }
    }

    private fun transitionToSignIn() {
        clearTransientCredentials()
        _uiState.update {
            it.copy(
                phase = StaffWorkspacePhase.SIGN_IN,
                isSubmitting = false,
                errorMessage = null
            )
        }
    }

    class Factory(
        private val context: Context,
        private val configuredOrigin: String,
        private val allowHttpLoopback: Boolean = false,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val authDenialListener: AuthDenialListener? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val coordinator = StaffSessionCoordinator.create(
                context = context.applicationContext,
                baseOrigin = configuredOrigin,
                allowHttpLoopback = allowHttpLoopback,
                authDenialListener = authDenialListener
            )
            return StaffWorkspaceViewModel(
                coordinator = coordinator,
                configuredOrigin = configuredOrigin,
                allowHttpLoopback = allowHttpLoopback,
                ioDispatcher = ioDispatcher,
                authDenialListener = authDenialListener
            ) as T
        }
    }
}
