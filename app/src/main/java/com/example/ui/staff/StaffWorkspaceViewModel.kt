package com.example.ui.staff

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.EndpointSecurity
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
 * 2. Deduplication & Intent Guarding: Button submissions and UI intents are guarded by active sequence IDs.
 * 3. Scope and Generation Verification: Async responses verify coordinator generation and active company
 *    scope before committing to UI state. Obsolete or stale responses are rejected before modifying state.
 * 4. Immediate List Invalidation: On company switch, logout, or 401 Auth Denial, catalog and directory lists
 *    are wiped immediately from memory. Late responses never repopulate foreign lists.
 * 5. Secret Privacy: Passwords, MFA codes, and recovery codes exist only in transient in-memory references,
 *    never in SavedStateHandle, disk, or UI state classes. Bearer tokens are never exposed in UI state.
 * 6. Challenge-Before-Login: A fresh challenge nonce must be acquired before dispatching credentials to the wire.
 * 7. Tracked Logout: Logout job runs with UI reads denied and submissions blocked until local purge completes.
 */
class StaffWorkspaceViewModel(
    private var coordinator: StaffSessionCoordinator?,
    private val configuredOrigin: String = "",
    private val allowHttpLoopback: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authDenialListener: AuthDenialListener? = null,
    initialPhase: StaffWorkspacePhase? = null,
    initialStorageError: String? = null,
    private val coordinatorFactory: (() -> StaffSessionCoordinator)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        when {
            initialPhase != null -> StaffWorkspaceUiState(phase = initialPhase, storageErrorMessage = initialStorageError)
            coordinator == null || configuredOrigin.isBlank() -> StaffWorkspaceUiState(phase = StaffWorkspacePhase.UNCONFIGURED)
            else -> StaffWorkspaceUiState(phase = StaffWorkspacePhase.INITIALIZING)
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

    // Main-thread UI intent sequence to invalidate stale completion handlers
    private var activeIntentSeq: Long = 0L
    private var productRequestSeq: Long = 0L
    private var customerRequestSeq: Long = 0L

    init {
        if (coordinator != null && configuredOrigin.isNotBlank() && _uiState.value.phase == StaffWorkspacePhase.INITIALIZING) {
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
     * Clears secondary MFA codes between attempts while preserving pending password for bounded retry.
     */
    fun clearTransientCodes() {
        transientMfaCode = ""
        transientRecoveryCode = ""
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
     * Toggles UI language between Arabic (RTL) and English (LTR).
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
        val coord = coordinator ?: run {
            retryComposition()
            return
        }
        if (_uiState.value.isSubmitting) return

        val thisIntent = ++activeIntentSeq
        authJob?.cancel()
        productsJob?.cancel()
        customersJob?.cancel()

        authJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = StaffWorkspacePhase.INITIALIZING,
                    isSubmitting = true,
                    errorMessage = null,
                    lockedReason = null
                )
            }

            val result = try {
                withContext(ioDispatcher) {
                    coord.restoreSession()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Failed to restore session")
            } finally {
                if (thisIntent == activeIntentSeq) {
                    _uiState.update { it.copy(isSubmitting = false) }
                }
            }

            if (thisIntent != activeIntentSeq) return@launch

            when (result) {
                is CoordinatorResult.Success -> {
                    when (val state = result.value) {
                        is StaffAuthState.ReadReady -> {
                            val selectedId = state.session.selectedCompanyId
                            if (selectedId != null && coord.isScopeActive(result.generation, selectedId)) {
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = state.bootstrap.companyId.value,
                                            companyName = state.bootstrap.companyName,
                                            currency = state.bootstrap.baseCurrency
                                        ),
                                        staffActor = SafeStaffActorViewData(
                                            actorId = state.bootstrap.staff.id.value,
                                            actorEmail = state.session.actorEmail ?: "",
                                            actorName = state.bootstrap.staff.name,
                                            roleLabel = state.bootstrap.staff.role
                                        ),
                                        availableCompanies = emptyList(),
                                        errorMessage = null
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
                    when (val state = coord.authState) {
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
                    // Stale operation ignored
                }
            }
        }
    }

    /**
     * Authenticates staff with email and password.
     * Orchestrates acquiring a fresh authentication challenge nonce before submitting credentials to the wire.
     */
    fun signIn(
        email: String,
        passwordOverride: String? = null,
        deviceName: String = "Android Staff Device"
    ) {
        val coord = coordinator ?: run {
            _uiState.update {
                it.copy(
                    phase = StaffWorkspacePhase.UNCONFIGURED,
                    errorMessage = if (it.language == AppLanguage.AR) "مساحة استعلام الموظفين غير مهيأة" else "Staff workspace unconfigured"
                )
            }
            return
        }
        if (_uiState.value.isSubmitting) return
        val pwd = passwordOverride ?: transientPassword
        if (email.isBlank() || pwd.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = if (it.language == AppLanguage.AR) "يرجى إدخال البريد الإلكتروني وكلمة المرور" else "Please enter email and password")
            }
            return
        }

        val thisIntent = ++activeIntentSeq
        authJob?.cancel()
        productsJob?.cancel()
        customersJob?.cancel()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            pendingEmail = email.trim()
            pendingPassword = pwd

            // Step 1: Real challenge request
            val challengeResult = try {
                withContext(ioDispatcher) {
                    coord.requestChallenge()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Failed to acquire challenge")
            }

            if (thisIntent != activeIntentSeq) return@launch

            if (challengeResult !is CoordinatorResult.Success) {
                clearTransientCredentials()
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = if (it.language == AppLanguage.AR)
                            "تعذر الحصول على رمز التحدي للمصادقة"
                        else
                            "Failed to acquire authentication challenge"
                    )
                }
                return@launch
            }

            // Step 2: Login with credentials and acquired challenge
            val result = try {
                withContext(ioDispatcher) {
                    coord.login(
                        email = pendingEmail,
                        password = pwd,
                        deviceName = deviceName
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Login failed")
            } finally {
                if (thisIntent == activeIntentSeq && coord.authState !is StaffAuthState.MfaRequired) {
                    clearTransientCredentials()
                }
            }

            if (thisIntent != activeIntentSeq) return@launch

            when (result) {
                is CoordinatorResult.Success -> {
                    when (val state = coord.authState) {
                        is StaffAuthState.ReadReady -> {
                            clearTransientCredentials()
                            val selectedId = state.session.selectedCompanyId
                            if (selectedId != null && coord.isScopeActive(result.generation, selectedId)) {
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = state.bootstrap.companyId.value,
                                            companyName = state.bootstrap.companyName,
                                            currency = state.bootstrap.baseCurrency
                                        ),
                                        staffActor = SafeStaffActorViewData(
                                            actorId = state.bootstrap.staff.id.value,
                                            actorEmail = state.session.actorEmail ?: "",
                                            actorName = state.bootstrap.staff.name,
                                            roleLabel = state.bootstrap.staff.role
                                        ),
                                        availableCompanies = emptyList(),
                                        errorMessage = null
                                    )
                                }
                                loadProducts(refresh = true)
                                loadCustomers(refresh = true)
                            }
                        }
                        is StaffAuthState.MfaRequired -> {
                            clearTransientCodes()
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
                            clearTransientCredentials()
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
                            clearTransientCredentials()
                            _uiState.update {
                                it.copy(
                                    isSubmitting = false,
                                    errorMessage = if (it.language == AppLanguage.AR) "حالة مصادقة غير متوقعة" else "Unexpected authentication state"
                                )
                            }
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    clearTransientCredentials()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "بيانات الاعتماد غير صحيحة" else "Invalid credentials"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    clearTransientCredentials()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "تم رفض الوصول للمنشأة" else "Access forbidden"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    clearTransientCredentials()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "فشل تسجيل الدخول" else "Could not complete the request. Try again"
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
     * Preserves pendingPassword across failed attempts so the user can re-enter their code.
     */
    fun retryWithMfa(
        email: String? = null,
        passwordOverride: String? = null,
        codeOverride: String? = null,
        isRecovery: Boolean = false,
        deviceName: String = "Android Staff Device"
    ) {
        val coord = coordinator ?: return
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

        val thisIntent = ++activeIntentSeq
        authJob?.cancel()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            // Clear code input on each attempt
            clearTransientCodes()

            val result = try {
                withContext(ioDispatcher) {
                    coord.retryLoginWithMfa(
                        email = emailToUse,
                        password = pwd,
                        deviceName = deviceName,
                        mfaCode = mfaCode,
                        recoveryCode = recoveryCode
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("MFA verification failed")
            } finally {
                if (thisIntent == activeIntentSeq) {
                    _uiState.update { it.copy(isSubmitting = false) }
                }
            }

            if (thisIntent != activeIntentSeq) return@launch

            when (result) {
                is CoordinatorResult.Success -> {
                    // Password fulfilled: clear secret from transient memory
                    clearTransientCredentials()
                    when (val state = coord.authState) {
                        is StaffAuthState.ReadReady -> {
                            val selectedId = state.session.selectedCompanyId
                            if (selectedId != null && coord.isScopeActive(result.generation, selectedId)) {
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = state.bootstrap.companyId.value,
                                            companyName = state.bootstrap.companyName,
                                            currency = state.bootstrap.baseCurrency
                                        ),
                                        staffActor = SafeStaffActorViewData(
                                            actorId = state.bootstrap.staff.id.value,
                                            actorEmail = state.session.actorEmail ?: "",
                                            actorName = state.bootstrap.staff.name,
                                            roleLabel = state.bootstrap.staff.role
                                        ),
                                        availableCompanies = emptyList(),
                                        errorMessage = null
                                    )
                                }
                                loadProducts(refresh = true)
                                loadCustomers(refresh = true)
                            }
                        }
                        is StaffAuthState.CompanySelectionRequired -> {
                            clearTransientCredentials()
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
                                    errorMessage = if (it.language == AppLanguage.AR) "حالة غير متوقعة بعد التحقق" else "Unexpected state after verification"
                                )
                            }
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    // Bad MFA code: clear codes only, keep pendingPassword so staff can retry entering code!
                    clearTransientCodes()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "رمز التحقق غير صحيح" else "Invalid verification code"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    clearTransientCredentials()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "تم رفض الوصول للمنشأة" else "Access forbidden"
                        )
                    }
                }
                is CoordinatorResult.Error -> {
                    clearTransientCodes()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (it.language == AppLanguage.AR) "فشل التحقق من الرمز" else "Could not complete the request. Try again"
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
     * Navigates to company selection view if multiple companies are known in memory,
     * or initiates honest change-company re-authentication for restored sessions.
     */
    fun openCompanySelection() {
        if (_uiState.value.availableCompanies.isNotEmpty()) {
            _uiState.update { it.copy(phase = StaffWorkspacePhase.COMPANY_SELECTION) }
        } else {
            // Restored session cannot recover membership list from frozen contract: honest re-auth flow
            val lang = _uiState.value.language
            val msg = if (lang == AppLanguage.AR)
                "لتغيير المنشأة، يرجى تسجيل الدخول مجدداً لاختيار منشأة أخرى"
            else
                "To switch companies, please sign in again to select a different company"
            logout(messageOnSuccess = msg)
        }
    }

    /**
     * Cancels company selection: returns to active ReadReady workspace if valid, or logs out if initial login.
     */
    fun cancelCompanySelection() {
        val active = _uiState.value.activeCompany
        val coord = coordinator
        if (active != null && coord != null) {
            val ulid = runCatching { ServerUlid.parse(active.companyId) }.getOrNull()
            if (ulid != null && coord.isScopeActive(coord.currentGeneration, ulid)) {
                _uiState.update { it.copy(phase = StaffWorkspacePhase.READ_READY, errorMessage = null) }
                return
            }
        }
        logout()
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

        val coord = coordinator ?: return
        val thisIntent = ++activeIntentSeq
        authJob?.cancel()
        productsJob?.cancel()
        customersJob?.cancel()
        clearListsImmediately()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val result = try {
                withContext(ioDispatcher) {
                    coord.selectCompany(serverUlid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Failed to select company")
            } finally {
                if (thisIntent == activeIntentSeq) {
                    _uiState.update { it.copy(isSubmitting = false) }
                }
            }

            if (thisIntent != activeIntentSeq) return@launch

            when (result) {
                is CoordinatorResult.Success -> {
                    clearTransientCredentials()
                    val state = coord.authState
                    if (state is StaffAuthState.ReadReady &&
                        coord.isScopeActive(result.generation, serverUlid)
                    ) {
                        _uiState.update { s ->
                            s.copy(
                                phase = StaffWorkspacePhase.READ_READY,
                                isSubmitting = false,
                                activeCompany = SafeCompanyViewData(
                                    companyId = state.bootstrap.companyId.value,
                                    companyName = state.bootstrap.companyName,
                                    currency = state.bootstrap.baseCurrency
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
                    clearTransientCredentials()
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
                        it.copy(isSubmitting = false, errorMessage = if (it.language == AppLanguage.AR) "تم رفض الوصول للمنشأة المحددة" else "Access forbidden for selected company")
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = if (it.language == AppLanguage.AR) "تعذر اختيار المنشأة" else "Could not complete the request. Try again")
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

        val coord = coordinator ?: return
        val thisIntent = ++activeIntentSeq
        productsJob?.cancel()
        customersJob?.cancel()
        authJob?.cancel()
        clearListsImmediately()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val result = try {
                withContext(ioDispatcher) {
                    coord.switchCompany(serverUlid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Failed to switch company")
            } finally {
                if (thisIntent == activeIntentSeq) {
                    _uiState.update { it.copy(isSubmitting = false) }
                }
            }

            if (thisIntent != activeIntentSeq) return@launch

            when (result) {
                is CoordinatorResult.Success -> {
                    val state = coord.authState
                    if (state is StaffAuthState.ReadReady &&
                        coord.isScopeActive(result.generation, serverUlid)
                    ) {
                        _uiState.update { s ->
                            s.copy(
                                phase = StaffWorkspacePhase.READ_READY,
                                isSubmitting = false,
                                activeCompany = SafeCompanyViewData(
                                    companyId = state.bootstrap.companyId.value,
                                    companyName = state.bootstrap.companyName,
                                    currency = state.bootstrap.baseCurrency
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
                        it.copy(isSubmitting = false, errorMessage = if (it.language == AppLanguage.AR) "تم رفض الوصول للمنشأة الجديدة" else "Access forbidden for new company")
                    }
                }
                is CoordinatorResult.Error -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = if (it.language == AppLanguage.AR) "تعذر تبديل المنشأة" else "Could not complete the request. Try again")
                    }
                }
                is CoordinatorResult.StaleOperation -> {
                    // Stale operation discarded
                }
            }
        }
    }

    /**
     * Logs out active staff: cancels in-flight jobs, wipes lists, clears transient secrets,
     * and tracks coordinator.logout() execution while disabling submissions until local purge completes.
     */
    fun logout(messageOnSuccess: String? = null) {
        val thisIntent = ++activeIntentSeq
        productsJob?.cancel()
        customersJob?.cancel()
        authJob?.cancel()

        clearTransientCredentials()
        clearListsImmediately()

        // Disable new login until local purge result is known
        _uiState.update {
            it.copy(
                isSubmitting = true,
                activeCompany = null,
                staffActor = null,
                availableCompanies = emptyList(),
                errorMessage = messageOnSuccess
            )
        }

        val coord = coordinator ?: run {
            _uiState.update { it.copy(
                phase = if (coordinatorFactory != null) StaffWorkspacePhase.STORAGE_FAILED else StaffWorkspacePhase.UNCONFIGURED,
                isSubmitting = false
            ) }
            return
        }

        authJob = viewModelScope.launch {
            val logoutResult = try {
                withContext(ioDispatcher) {
                    coord.logout()
                }
            } catch (e: CancellationException) {
                if (thisIntent == activeIntentSeq) {
                    _uiState.update { it.copy(phase = StaffWorkspacePhase.LOCKED,
                        isSubmitting = false, lockedReason = "Local session clear not confirmed") }
                }
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (thisIntent != activeIntentSeq) return@launch

            when (logoutResult) {
                is LogoutResult.StorageClearFailed -> {
                    // Storage clear failed: stays locked / error recovery
                    _uiState.update {
                        it.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isSubmitting = false,
                            lockedReason = "Local session clear failed on logout"
                        )
                    }
                }
                is LogoutResult.Success -> {
                    _uiState.update {
                        it.copy(
                            phase = StaffWorkspacePhase.SIGN_IN,
                            isSubmitting = false,
                            errorMessage = messageOnSuccess
                        )
                    }
                }
                null -> {
                    _uiState.update { it.copy(phase = StaffWorkspacePhase.LOCKED,
                        isSubmitting = false, lockedReason = "Local session clear not confirmed") }
                }
            }
        }
    }

    /**
     * Fetches bounded product catalog for the selected company.
     * Enforces ownership guard before processing any result branch, resets pagination safely on refresh,
     * and deduplicates item IDs before exposing to Compose.
     */
    fun loadProducts(refresh: Boolean = false, loadMore: Boolean = false) {
        val activeCompany = _uiState.value.activeCompany ?: return
        val companyUlid = try {
            ServerUlid.parse(activeCompany.companyId)
        } catch (_: Exception) {
            return
        }
        val coord = coordinator ?: return

        if (loadMore) {
            if (_uiState.value.isProductsLoading || _uiState.value.isProductsLoadingMore || !_uiState.value.productsHasMore || _uiState.value.productsNextCursor == null) {
                return
            }
        } else if (refresh) {
            productsJob?.cancel()
        } else if (_uiState.value.isProductsLoading) {
            return
        }

        val thisSeq = ++productRequestSeq
        val opGen = coord.currentGeneration
        val cursor = if (loadMore) _uiState.value.productsNextCursor else null
        val targetSearch = _uiState.value.productSearchQuery.ifBlank { null }

        productsJob = viewModelScope.launch {
            _uiState.update {
                if (loadMore) {
                    it.copy(isProductsLoadingMore = true, productsError = null)
                } else {
                    it.copy(
                        isProductsLoading = true,
                        isProductsLoadingMore = false,
                        productsError = null,
                        productsNextCursor = null,
                        productsHasMore = false
                    )
                }
            }

            val query = PaginationQuery(
                limit = 50,
                cursor = cursor,
                search = targetSearch
            )

            val result = try {
                withContext(ioDispatcher) {
                    coord.getProducts(query)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Failed to load products")
            } finally {
                if (thisSeq == productRequestSeq && opGen == coord.currentGeneration) {
                    _uiState.update {
                        if (it.activeCompany?.companyId == companyUlid.value) {
                            it.copy(isProductsLoading = false, isProductsLoadingMore = false)
                        } else it
                    }
                }
            }

            // Ownership check BEFORE every result branch:
            // 1. Intent / search sequence check
            if (thisSeq != productRequestSeq) {
                return@launch
            }
            // 2. Active company check
            if (_uiState.value.activeCompany?.companyId != companyUlid.value) {
                return@launch
            }
            // 3. Coordinator generation check
            if (opGen != coord.currentGeneration) {
                return@launch
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    if (coord.isScopeActive(opGen, companyUlid)) {
                        val response = result.value
                        _uiState.update { state ->
                            if (state.activeCompany?.companyId != companyUlid.value) return@update state
                            val combined = if (loadMore) {
                                state.products + response.products
                            } else {
                                response.products
                            }
                            val deduped = combined.distinctBy { it.id.value }
                            state.copy(
                                products = deduped,
                                isProductsLoading = false,
                                isProductsLoadingMore = false,
                                productsNextCursor = response.nextCursor,
                                productsHasMore = response.hasMore,
                                productsError = null
                            )
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    clearListsImmediately()
                    _uiState.update { state ->
                        state.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isProductsLoading = false,
                            isProductsLoadingMore = false,
                            lockedReason = "Authentication revoked or invalid (HTTP 401)"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    clearListsImmediately()
                    _uiState.update { state ->
                        state.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isProductsLoading = false,
                            isProductsLoadingMore = false,
                            lockedReason = "Tenant access forbidden (HTTP 403)"
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
                            productsError = if (state.language == AppLanguage.AR) "تعذر تحميل سجل الأصناف" else "Could not complete the request. Try again"
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
     * Updates product search query and re-executes search with cursor and loading reset.
     */
    fun updateProductSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                productSearchQuery = query,
                productsNextCursor = null,
                productsHasMore = false
            )
        }
        loadProducts(refresh = true)
    }

    /**
     * Fetches bounded customer directory for the selected company.
     * Enforces ownership guard before processing any result branch, resets pagination safely on refresh,
     * and deduplicates item IDs before exposing to Compose.
     */
    fun loadCustomers(refresh: Boolean = false, loadMore: Boolean = false) {
        val activeCompany = _uiState.value.activeCompany ?: return
        val companyUlid = try {
            ServerUlid.parse(activeCompany.companyId)
        } catch (_: Exception) {
            return
        }
        val coord = coordinator ?: return

        if (loadMore) {
            if (_uiState.value.isCustomersLoading || _uiState.value.isCustomersLoadingMore || !_uiState.value.customersHasMore || _uiState.value.customersNextCursor == null) {
                return
            }
        } else if (refresh) {
            customersJob?.cancel()
        } else if (_uiState.value.isCustomersLoading) {
            return
        }

        val thisSeq = ++customerRequestSeq
        val opGen = coord.currentGeneration
        val cursor = if (loadMore) _uiState.value.customersNextCursor else null
        val targetSearch = _uiState.value.customerSearchQuery.ifBlank { null }

        customersJob = viewModelScope.launch {
            _uiState.update {
                if (loadMore) {
                    it.copy(isCustomersLoadingMore = true, customersError = null)
                } else {
                    it.copy(
                        isCustomersLoading = true,
                        isCustomersLoadingMore = false,
                        customersError = null,
                        customersNextCursor = null,
                        customersHasMore = false
                    )
                }
            }

            val query = PaginationQuery(
                limit = 50,
                cursor = cursor,
                search = targetSearch
            )

            val result = try {
                withContext(ioDispatcher) {
                    coord.getCustomers(query)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                CoordinatorResult.Error("Failed to load customers")
            } finally {
                if (thisSeq == customerRequestSeq && opGen == coord.currentGeneration) {
                    _uiState.update {
                        if (it.activeCompany?.companyId == companyUlid.value) {
                            it.copy(isCustomersLoading = false, isCustomersLoadingMore = false)
                        } else it
                    }
                }
            }

            // Ownership check BEFORE every result branch:
            // 1. Intent / search sequence check
            if (thisSeq != customerRequestSeq) {
                return@launch
            }
            // 2. Active company check
            if (_uiState.value.activeCompany?.companyId != companyUlid.value) {
                return@launch
            }
            // 3. Coordinator generation check
            if (opGen != coord.currentGeneration) {
                return@launch
            }

            when (result) {
                is CoordinatorResult.Success -> {
                    if (coord.isScopeActive(opGen, companyUlid)) {
                        val response = result.value
                        _uiState.update { state ->
                            if (state.activeCompany?.companyId != companyUlid.value) return@update state
                            val combined = if (loadMore) {
                                state.customers + response.customers
                            } else {
                                response.customers
                            }
                            val deduped = combined.distinctBy { it.id.value }
                            state.copy(
                                customers = deduped,
                                isCustomersLoading = false,
                                isCustomersLoadingMore = false,
                                customersNextCursor = response.nextCursor,
                                customersHasMore = response.hasMore,
                                customersError = null
                            )
                        }
                    }
                }
                is CoordinatorResult.AuthDenied -> {
                    clearListsImmediately()
                    _uiState.update { state ->
                        state.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isCustomersLoading = false,
                            isCustomersLoadingMore = false,
                            lockedReason = "Authentication revoked or invalid (HTTP 401)"
                        )
                    }
                }
                is CoordinatorResult.Forbidden -> {
                    clearListsImmediately()
                    _uiState.update { state ->
                        state.copy(
                            phase = StaffWorkspacePhase.LOCKED,
                            isCustomersLoading = false,
                            isCustomersLoadingMore = false,
                            lockedReason = "Tenant access forbidden (HTTP 403)"
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
                            customersError = if (state.language == AppLanguage.AR) "تعذر تحميل دليل العملاء" else "Could not complete the request. Try again"
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
     * Updates customer directory search query and re-executes search with cursor and loading reset.
     */
    fun updateCustomerSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                customerSearchQuery = query,
                customersNextCursor = null,
                customersHasMore = false
            )
        }
        loadCustomers(refresh = true)
    }

    /**
     * Clears products and customers lists immediately from memory.
     */
    private fun clearListsImmediately() {
        productRequestSeq++
        customerRequestSeq++
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
     * Helper to lock session immediately if coordinator transitioned to Locked.
     */
    private fun checkAndHandleAuthDenied(errorMessage: String) {
        val coord = coordinator ?: return
        val state = coord.authState
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

    override fun onCleared() {
        super.onCleared()
        clearTransientCredentials()
        authJob?.cancel()
        productsJob?.cancel()
        customersJob?.cancel()
    }

    private fun retryComposition() {
        val factory = coordinatorFactory
        if (factory == null) {
            _uiState.update { it.copy(phase = StaffWorkspacePhase.UNCONFIGURED, isSubmitting = false) }
            return
        }
        if (_uiState.value.isSubmitting) return
        val intent = ++activeIntentSeq
        _uiState.update { it.copy(phase = StaffWorkspacePhase.INITIALIZING, isSubmitting = true) }
        authJob = viewModelScope.launch {
            try {
                val created = withContext(ioDispatcher) { factory() }
                if (intent != activeIntentSeq) return@launch
                coordinator = created
                _uiState.update { it.copy(isSubmitting = false) }
                restoreSession()
            } catch (e: CancellationException) {
                if (intent == activeIntentSeq) {
                    _uiState.update { it.copy(phase = StaffWorkspacePhase.STORAGE_FAILED, isSubmitting = false) }
                }
                throw e
            } catch (_: Exception) {
                if (intent == activeIntentSeq) {
                    _uiState.update { it.copy(phase = StaffWorkspacePhase.STORAGE_FAILED,
                        isSubmitting = false, storageErrorMessage = "Secure storage initialization failed") }
                }
            }
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
            val effectiveLoopback = BuildConfig.DEBUG && allowHttpLoopback

            // 1. Validate configuredOrigin safely before attempting composition
            if (configuredOrigin.isBlank()) {
                return StaffWorkspaceViewModel(
                    coordinator = null,
                    configuredOrigin = configuredOrigin,
                    allowHttpLoopback = effectiveLoopback,
                    ioDispatcher = ioDispatcher,
                    authDenialListener = authDenialListener,
                    initialPhase = StaffWorkspacePhase.UNCONFIGURED
                ) as T
            }

            val isValidOrigin = try {
                EndpointSecurity.validateBaseOrigin(configuredOrigin, effectiveLoopback)
                true
            } catch (_: Exception) {
                false
            }

            if (!isValidOrigin) {
                return StaffWorkspaceViewModel(
                    coordinator = null,
                    configuredOrigin = configuredOrigin,
                    allowHttpLoopback = effectiveLoopback,
                    ioDispatcher = ioDispatcher,
                    authDenialListener = authDenialListener,
                    initialPhase = StaffWorkspacePhase.UNCONFIGURED
                ) as T
            }

            // 2. Safe coordinator composition: handle Keystore/Storage unavailability gracefully without crash
            val coordinator = try {
                StaffSessionCoordinator.create(
                    context = context.applicationContext,
                    baseOrigin = configuredOrigin,
                    allowHttpLoopback = effectiveLoopback,
                    authDenialListener = authDenialListener
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                return StaffWorkspaceViewModel(
                    coordinator = null,
                    configuredOrigin = configuredOrigin,
                    allowHttpLoopback = effectiveLoopback,
                    ioDispatcher = ioDispatcher,
                    authDenialListener = authDenialListener,
                    initialPhase = StaffWorkspacePhase.STORAGE_FAILED,
                    initialStorageError = "Secure storage initialization failed",
                    coordinatorFactory = {
                        StaffSessionCoordinator.create(context.applicationContext, configuredOrigin,
                            effectiveLoopback, authDenialListener)
                    }
                ) as T
            }

            return StaffWorkspaceViewModel(
                coordinator = coordinator,
                configuredOrigin = configuredOrigin,
                allowHttpLoopback = effectiveLoopback,
                ioDispatcher = ioDispatcher,
                authDenialListener = authDenialListener
            ) as T
        }
    }
}
