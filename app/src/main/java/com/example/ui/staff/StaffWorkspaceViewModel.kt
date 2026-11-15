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
import com.example.data.mobile.outbox.OutboxHealthInspector
import com.example.data.mobile.outbox.OutboxHealthResult
import com.example.data.mobile.outbox.OutboxHealthStatus
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.onlinecash.AndroidV2OnlineCashClient
import com.example.data.mobile.onlinecash.OnlineCashSaleOrchestrator
import com.example.data.mobile.onlinecash.OnlineCashOrchestrationResult
import com.example.data.mobile.onlinecash.OnlineSaleScope
import com.example.data.mobile.onlinecash.PreparedOnlineSaleIntent
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.readclient.BootstrapTax
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductPackagingUnit
import com.example.data.mobile.readclient.ReadClientErrorResponse
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.session.AuthDenialListener
import com.example.data.mobile.session.AuthDenialScope
import com.example.data.mobile.session.CoordinatorResult
import com.example.data.mobile.session.LockReason
import com.example.data.mobile.session.LogoutResult
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionCoordinator
import com.example.model.AppLanguage
import com.example.ui.staff.sale.StaffOnlineSaleAuthorityResolution
import com.example.ui.staff.sale.StaffOnlineSaleDraft
import com.example.ui.staff.sale.StaffOnlineSaleDraftLine
import com.example.ui.staff.sale.StaffOnlineSaleRequestBuilder
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
    private val coordinatorFactory: (() -> StaffSessionCoordinator)? = null,
    outboxHealthInspector: OutboxHealthInspector? = null,
    private val orchestratorFactory: ((
        tokenProvider: () -> String?,
        scopeGenerationProvider: () -> Long,
        onAuthDeniedLockScope: ((scopeGeneration: Long, error: ReadClientErrorResponse) -> Unit)?,
        activeScopeSupplier: (() -> OnlineSaleScope?)?
    ) -> OnlineCashSaleOrchestrator)? = null,
    private val onlineCashEnabled: Boolean = false,
    private val currencyMinorUnitsOverride: Int? = null
) : ViewModel() {

    private val outboxHealthInspector: OutboxHealthInspector =
        outboxHealthInspector ?: com.example.data.mobile.outbox.UnavailableOutboxHealthInspector()

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
    private var outboxHealthJob: Job? = null
    private var saleJob: Job? = null

    // Main-thread UI intent sequence to invalidate stale completion handlers
    private var activeIntentSeq: Long = 0L
    private var productRequestSeq: Long = 0L
    private var customerRequestSeq: Long = 0L

    // Dedicated outbox health intent sequence so health checks do NOT invalidate unrelated catalog/auth jobs
    private var outboxHealthIntentSeq: Long = 0L

    // Cached health result for restoring warning+retry on cancelCompanySelection
    private var cachedOutboxHealthResult: OutboxHealthResult? = null
    private var cachedHealthCompanyId: String? = null
    private var cachedHealthActorId: String? = null
    private var cachedHealthDeviceId: String? = null

    // Active authenticated tenant and device scope for outbox inspection
    private var activeScopeCompanyId: String? = null
    private var activeScopeActorId: String? = null
    private var activeScopeDeviceId: String? = null

    init {
        if (coordinator != null && configuredOrigin.isNotBlank() && _uiState.value.phase == StaffWorkspacePhase.INITIALIZING) {
            restoreSession()
        }
    }

    /**
     * Inspects local outbox health scoped strictly to the authenticated (companyId, actorId, deviceId).
     * Derives immutable actual coordinator ReadReady session scope, not caller IDs.
     * Validates phase, coordinator generation, scope activity, actor, and device BEFORE any inspector I/O.
     * Runs off the main thread after entering READ_READY.
     * Never blocks normal read workspace navigation or product/customer queries.
     */
    internal fun triggerScopedOutboxHealth() {
        if (_uiState.value.phase != StaffWorkspacePhase.READ_READY) return
        val coord = coordinator ?: return
        val readyState = coord.authState as? StaffAuthState.ReadReady ?: return
        val currentGen = coord.currentGeneration
        val activeComp = _uiState.value.activeCompany ?: return
        val companyUlid = readyState.session.selectedCompanyId ?: return
        if (activeComp.companyId != companyUlid.value) return
        if (!coord.isScopeActive(currentGen, companyUlid)) return

        val actorId = readyState.session.actorUlid.value
        val deviceId = readyState.session.deviceUuid.value
        val staffActor = _uiState.value.staffActor
        if (staffActor != null && staffActor.actorId != actorId) return

        val companyId = companyUlid.value
        activeScopeCompanyId = companyId
        activeScopeActorId = actorId
        activeScopeDeviceId = deviceId

        val intentId = ++outboxHealthIntentSeq
        outboxHealthJob?.cancel()
        outboxHealthJob = viewModelScope.launch {
            if (_uiState.value.phase != StaffWorkspacePhase.READ_READY ||
                coord.currentGeneration != currentGen ||
                !coord.isScopeActive(currentGen, companyUlid) ||
                coord.authState !is StaffAuthState.ReadReady
            ) {
                return@launch
            }

            val health = try {
                outboxHealthInspector.inspectHealth(companyId, actorId, deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                OutboxHealthResult(
                    status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                    totalEvents = 0,
                    probedCount = 0,
                    isSampleProbe = false,
                    isScopeLocked = false,
                    messageEn = "We have not changed stored sales. Some records need help before syncing.",
                    messageAr = "لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                    detailMessageEn = "Contact support",
                    detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                    canRetry = true
                )
            }

            if (intentId != outboxHealthIntentSeq) return@launch
            if (coord.currentGeneration != currentGen) return@launch
            if (!coord.isScopeActive(currentGen, companyUlid)) return@launch
            if (coord.authState !is StaffAuthState.ReadReady) return@launch
            val currentReady = coord.authState as StaffAuthState.ReadReady
            if (currentReady.session.selectedCompanyId?.value != companyId ||
                currentReady.session.actorUlid.value != actorId ||
                currentReady.session.deviceUuid.value != deviceId
            ) {
                return@launch
            }
            if (_uiState.value.phase != StaffWorkspacePhase.READ_READY ||
                _uiState.value.activeCompany?.companyId != companyId ||
                _uiState.value.staffActor?.actorId != actorId
            ) {
                return@launch
            }

            cachedOutboxHealthResult = health
            cachedHealthCompanyId = companyId
            cachedHealthActorId = actorId
            cachedHealthDeviceId = deviceId

            _uiState.update { it.copy(outboxHealthResult = health, isOutboxRetrying = false) }
        }
    }

    /**
     * Retries scoped outbox health inspection off the main thread.
     * Derives immutable actual coordinator ReadReady session scope.
     * Validates phase, coordinator generation, scope activity, actor, and device BEFORE any inspector I/O.
     * Never regenerates key or modifies/destroys outbox records.
     */
    fun retryOutboxHealth() {
        if (_uiState.value.phase != StaffWorkspacePhase.READ_READY) return
        val coord = coordinator ?: return
        val readyState = coord.authState as? StaffAuthState.ReadReady ?: return
        val currentGen = coord.currentGeneration
        val activeComp = _uiState.value.activeCompany ?: return
        val companyUlid = readyState.session.selectedCompanyId ?: return
        if (activeComp.companyId != companyUlid.value) return
        if (!coord.isScopeActive(currentGen, companyUlid)) return

        val companyId = companyUlid.value
        val actorId = readyState.session.actorUlid.value
        val deviceId = readyState.session.deviceUuid.value
        val staffActor = _uiState.value.staffActor
        if (staffActor != null && staffActor.actorId != actorId) return

        activeScopeCompanyId = companyId
        activeScopeActorId = actorId
        activeScopeDeviceId = deviceId

        val intentId = ++outboxHealthIntentSeq
        _uiState.update { it.copy(isOutboxRetrying = true) }
        outboxHealthJob?.cancel()
        outboxHealthJob = viewModelScope.launch {
            if (_uiState.value.phase != StaffWorkspacePhase.READ_READY ||
                coord.currentGeneration != currentGen ||
                !coord.isScopeActive(currentGen, companyUlid) ||
                coord.authState !is StaffAuthState.ReadReady
            ) {
                _uiState.update { it.copy(isOutboxRetrying = false) }
                return@launch
            }

            val health = try {
                outboxHealthInspector.inspectHealth(companyId, actorId, deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                OutboxHealthResult(
                    status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                    totalEvents = 0,
                    probedCount = 0,
                    isSampleProbe = false,
                    isScopeLocked = false,
                    messageEn = "We have not changed stored sales. Some records need help before syncing.",
                    messageAr = "لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                    detailMessageEn = "Contact support",
                    detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                    canRetry = true
                )
            }

            if (intentId != outboxHealthIntentSeq) return@launch
            if (coord.currentGeneration != currentGen) return@launch
            if (!coord.isScopeActive(currentGen, companyUlid)) return@launch
            if (coord.authState !is StaffAuthState.ReadReady) return@launch
            val currentReady = coord.authState as StaffAuthState.ReadReady
            if (currentReady.session.selectedCompanyId?.value != companyId ||
                currentReady.session.actorUlid.value != actorId ||
                currentReady.session.deviceUuid.value != deviceId
            ) {
                return@launch
            }
            if (_uiState.value.phase != StaffWorkspacePhase.READ_READY ||
                _uiState.value.activeCompany?.companyId != companyId ||
                _uiState.value.staffActor?.actorId != actorId
            ) {
                return@launch
            }

            cachedOutboxHealthResult = health
            cachedHealthCompanyId = companyId
            cachedHealthActorId = actorId
            cachedHealthDeviceId = deviceId

            _uiState.update { it.copy(outboxHealthResult = health, isOutboxRetrying = false) }
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
     * Switches selected read workspace tab (Products / Customers / Cash Sale).
     */
    fun selectTab(tab: StaffWorkspaceTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == StaffWorkspaceTab.CASH_SALE && _uiState.value.saleAuthority == null) {
            (coordinator?.authState as? StaffAuthState.ReadReady)?.let {
                updateSaleAuthorityOnReadReady(it)
            }
        }
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

    private fun updateSaleAuthorityOnReadReady(state: StaffAuthState.ReadReady) {
        val authority = StaffOnlineSaleAuthorityResolution.resolve(
            bootstrap = state.bootstrap,
            sessionRecord = state.session,
            currencyMinorUnitsOverride = currencyMinorUnitsOverride
        )
        _uiState.update { s ->
            s.copy(
                saleAuthority = authority,
                availableTaxes = state.bootstrap.taxes,
                isOnlineCashEnabled = onlineCashEnabled || BuildConfig.STAFF_ONLINE_CASH_ENABLED
            )
        }
    }

    private var cachedOrchestrator: OnlineCashSaleOrchestrator? = null
    private var cachedOrchestratorGeneration: Long? = null

    internal fun getOrchestrator(): OnlineCashSaleOrchestrator {
        val coord = coordinator ?: throw IllegalStateException("Cannot create orchestrator: coordinator is null")
        val currentGen = coord.currentGeneration
        val cached = cachedOrchestrator
        if (cached != null && cachedOrchestratorGeneration == currentGen) {
            return cached
        }

        val factory = orchestratorFactory
        val tokenProvider: () -> String? = {
            (coordinator?.authState as? StaffAuthState.ReadReady)?.session?.token
        }
        val scopeGenerationProvider: () -> Long = {
            coordinator?.currentGeneration ?: throw IllegalStateException("Coordinator is unconfigured")
        }
        val onAuthDeniedLockScope: ((Long, ReadClientErrorResponse) -> Unit)? = { scopeGen, _ ->
            val ready = coord.authState as? StaffAuthState.ReadReady
            val companyId = ready?.session?.selectedCompanyId
            if (ready != null && companyId != null && coord.isScopeActive(scopeGen, companyId)) {
                authDenialListener?.onAuthDenied(
                    AuthDenialScope(
                        companyId = companyId,
                        actorId = ready.session.actorUlid,
                        deviceId = ready.session.deviceUuid,
                        deniedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(java.util.Date())
                    )
                )
            }
        }
        val activeScopeSupplier: (() -> OnlineSaleScope?)? = {
            val ready = coord.authState as? StaffAuthState.ReadReady
            val companyId = ready?.session?.selectedCompanyId
            if (ready != null && companyId != null) {
                OnlineSaleScope(
                    actorId = ready.session.actorUlid,
                    deviceId = ready.session.deviceUuid,
                    companyId = companyId,
                    generation = coord.currentGeneration
                )
            } else null
        }

        val created = if (factory != null) {
            factory.invoke(tokenProvider, scopeGenerationProvider, onAuthDeniedLockScope, activeScopeSupplier)
        } else {
            val transport = OkHttpTransport(configuredOrigin, allowHttpLoopback)
            val client = AndroidV2OnlineCashClient(transport, configuredOrigin, allowHttpLoopback)
            OnlineCashSaleOrchestrator(
                client = client,
                tokenProvider = tokenProvider,
                scopeGenerationProvider = scopeGenerationProvider,
                onAuthDeniedLockScope = onAuthDeniedLockScope,
                activeScopeSupplier = activeScopeSupplier
            )
        }
        cachedOrchestrator = created
        cachedOrchestratorGeneration = currentGen
        return created
    }

    fun addProductToSaleDraft(
        product: ProductCatalogItem,
        packagingUnit: ProductPackagingUnit? = null,
        quantity: PositiveDecimalQuantity = PositiveDecimalQuantity.of(1),
        taxInclusiveOverride: Boolean? = null,
        explicitNoTax: Boolean = false,
        selectedTax: BootstrapTax? = null
    ) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        val readyState = coordinator?.authState as? StaffAuthState.ReadReady
        val bUnits = readyState?.bootstrap?.units ?: emptyList()
        val bTaxes = readyState?.bootstrap?.taxes ?: emptyList()

        _uiState.update { s ->
            val currentLines = s.saleDraft.lines.toMutableList()
            val targetUnitId = packagingUnit?.unitId ?: product.defaultUnit.unitId
            val existingIndex = currentLines.indexOfFirst {
                it.product.id == product.id && it.productUnitId == targetUnitId
            }
            if (existingIndex >= 0) {
                val existing = currentLines[existingIndex]
                val newQtyBd = existing.quantity.value.add(quantity.value)
                val updatedLine = existing.copy(
                    quantity = PositiveDecimalQuantity(newQtyBd.toPlainString())
                )
                currentLines[existingIndex] = updatedLine
            } else {
                val newLine = StaffOnlineSaleDraftLine.fromProduct(
                    product = product,
                    packagingUnit = packagingUnit,
                    quantity = quantity,
                    taxInclusiveOverride = taxInclusiveOverride,
                    explicitNoTax = explicitNoTax,
                    bootstrapUnits = bUnits,
                    bootstrapTaxes = bTaxes,
                    selectedTax = selectedTax
                )
                currentLines.add(newLine)
            }
            s.copy(
                saleDraft = s.saleDraft.copy(
                    lines = currentLines,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun updateDraftLineQuantity(lineId: String, newQuantity: PositiveDecimalQuantity) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        _uiState.update { s ->
            val currentLines = s.saleDraft.lines.map { line ->
                if (line.lineId == lineId) {
                    line.copy(quantity = newQuantity)
                } else line
            }
            s.copy(
                saleDraft = s.saleDraft.copy(
                    lines = currentLines,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun removeDraftLine(lineId: String) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        _uiState.update { s ->
            val currentLines = s.saleDraft.lines.filterNot { it.lineId == lineId }
            s.copy(
                saleDraft = s.saleDraft.copy(
                    lines = currentLines,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun setLineTax(lineId: String, tax: BootstrapTax) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        val coord = coordinator ?: return
        val ready = coord.authState as? StaffAuthState.ReadReady ?: return
        val currentTaxes = ready.bootstrap.taxes
        val matchingTax = currentTaxes.find {
            it.id == tax.id &&
            it.rate.value.compareTo(tax.rate.value) == 0 &&
            it.calculation == tax.calculation
        } ?: return
        if (matchingTax.calculation != "inclusive" && matchingTax.calculation != "exclusive") {
            return
        }
        _uiState.update { s ->
            val currentLines = s.saleDraft.lines.map { line ->
                if (line.lineId == lineId) {
                    line.copy(
                        taxRateId = matchingTax.id,
                        taxRate = matchingTax.rate,
                        taxInclusive = (matchingTax.calculation == "inclusive")
                    )
                } else line
            }
            s.copy(
                saleDraft = s.saleDraft.copy(
                    lines = currentLines,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun setLineExplicitNoTax(lineId: String) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        val coord = coordinator ?: return
        if (coord.authState !is StaffAuthState.ReadReady) return
        _uiState.update { s ->
            val currentLines = s.saleDraft.lines.map { line ->
                if (line.lineId == lineId) {
                    line.copy(
                        taxRateId = null,
                        taxRate = TaxPercentage.fromString("0.000000"),
                        taxInclusive = false
                    )
                } else line
            }
            s.copy(
                saleDraft = s.saleDraft.copy(
                    lines = currentLines,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun selectCustomerForSale(customer: CustomerDirectoryItem?) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        _uiState.update { s ->
            s.copy(
                saleDraft = s.saleDraft.copy(
                    selectedCustomer = customer,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun updateCashTendered(amount: MoneyAmount?) {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        _uiState.update { s ->
            s.copy(
                saleDraft = s.saleDraft.copy(
                    cashTendered = amount,
                    submissionError = null,
                    preparedIntent = null
                )
            )
        }
    }

    fun clearSaleDraft() {
        if (_uiState.value.saleDraft.isIndeterminate ||
            _uiState.value.saleDraft.isPaymentUnverifiedHold ||
            _uiState.value.saleDraft.isSubmitting
        ) return
        saleJob?.cancel()
        _uiState.update { s ->
            s.copy(saleDraft = StaffOnlineSaleDraft())
        }
    }

    fun submitOnlineCashSale() {
        val currentState = _uiState.value
        // Guard against concurrent submit
        if (currentState.saleDraft.isSubmitting) return

        // Guard against hold
        if (currentState.saleDraft.isPaymentUnverifiedHold) return

        if (currentState.phase != StaffWorkspacePhase.READ_READY) {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Staff workspace is not in READ_READY phase")) }
            return
        }

        val coord = coordinator ?: run {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Coordinator is unconfigured")) }
            return
        }

        val readyState = coord.authState as? StaffAuthState.ReadReady ?: run {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Session is not ready")) }
            return
        }

        val authority = currentState.saleAuthority ?: StaffOnlineSaleAuthorityResolution.resolve(
            readyState.bootstrap,
            readyState.session,
            currencyMinorUnitsOverride
        )

        if (!authority.isDispatchPermitted) {
            val missing = authority.missingContracts.joinToString("; ")
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Authority resolution failed: $missing")) }
            return
        }

        val draft = currentState.saleDraft
        if (draft.lines.isEmpty()) {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Sale basket is empty")) }
            return
        }

        if (draft.hasUnresolvedContracts) {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Draft contains lines with unresolved contracts")) }
            return
        }

        val generation = coord.currentGeneration

        // P1 IDEMPOTENCY:
        // If draft already has a preparedIntent (e.g. from an indeterminate transient attempt),
        // RETAIN AND REUSE the EXACT same preparedIntent (same clientEventId, same wire bytes)!
        val preparedIntent = draft.preparedIntent ?: run {
            val clientEventId = ClientUuid.random()
            try {
                StaffOnlineSaleRequestBuilder.prepareIntent(
                    draft = draft,
                    authority = authority,
                    scopeGeneration = generation,
                    clientEventId = clientEventId
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Failed to prepare sale intent: ${e.message}")) }
                return
            }
        }

        val thisIntent = ++activeIntentSeq

        // Set isSubmitting = true and retain preparedIntent idempotently
        _uiState.update { s ->
            s.copy(
                saleDraft = s.saleDraft.copy(
                    isSubmitting = true,
                    submissionError = null,
                    preparedIntent = preparedIntent
                )
            )
        }

        saleJob?.cancel()
        saleJob = viewModelScope.launch {
            val targetCompanyId = authority.companyId!!
            val targetDeviceId = authority.deviceId!!
            val targetCurrency = authority.currency!!

            val result = try {
                withContext(ioDispatcher) {
                    val orchestrator = getOrchestrator()
                    orchestrator.dispatchOnlineCashSale(
                        intent = preparedIntent,
                        targetCompanyId = targetCompanyId,
                        targetDeviceId = targetDeviceId,
                        targetCurrency = targetCurrency
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (thisIntent != activeIntentSeq) return@launch

            // Guard against scope change
            if (!coord.isScopeActive(generation, targetCompanyId)) {
                _uiState.update { s ->
                    s.copy(
                        saleDraft = s.saleDraft.copy(
                            isSubmitting = false,
                            submissionError = "Session scope expired during sale dispatch"
                        )
                    )
                }
                return@launch
            }

            when (result) {
                is OnlineCashOrchestrationResult.ConfirmedFinancialSuccess -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = false,
                                isPaymentUnverifiedHold = false,
                                receipt = result.receipt,
                                unverifiedReceipt = null,
                                submissionError = null,
                                preparedIntent = null,
                                lines = emptyList(),
                                cashTendered = null
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.PostedWithoutPaymentReference -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = false,
                                isPaymentUnverifiedHold = true,
                                unverifiedReceipt = result.receipt,
                                receipt = null,
                                submissionError = "Invoice posted (${result.receipt.invoiceNumber}), but payment confirmation is unverified. Sale is held in read-only status."
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.BusinessConflict -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = true,
                                submissionError = "Business conflict (409): event may have committed on server. Status lookup required."
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.AuthDenied -> {
                    if (coord.isScopeActive(result.scopeGeneration, targetCompanyId)) {
                        clearListsImmediately()
                        _uiState.update {
                            it.copy(
                                phase = StaffWorkspacePhase.LOCKED,
                                isSubmitting = false,
                                lockedReason = "Authentication revoked or invalid (HTTP 401)"
                            )
                        }
                    } else {
                        _uiState.update { s ->
                            s.copy(
                                saleDraft = s.saleDraft.copy(
                                    isSubmitting = false,
                                    submissionError = "Stale authentication rejection from prior session ignored"
                                )
                            )
                        }
                    }
                }
                is OnlineCashOrchestrationResult.IndeterminateTransient -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = true,
                                submissionError = "Network timeout or transient failure: ${result.reason}. Retrying will verify status first using same event ID."
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.LocalTokenUnavailable -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                preparedIntent = null,
                                submissionError = result.reason
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.ValidationFailed -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                preparedIntent = null,
                                submissionError = "Validation failed (422): ${result.error.message}"
                            )
                        )
                    }
                }
                else -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Sale dispatch failed or indeterminate"
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Executes a read-only GET status check for the retained sale intent during PaymentUnverifiedHold.
     *
     * Invariants:
     * - Scoped GET-status query ONLY via orchestrator / client.checkStatus (ZERO HTTP POST).
     * - Re-uses the exact retained preparedIntent; NEVER mutates clientEventId or generates a new UUID.
     * - Verifies tenant company, clientEventId, payloadDigest, and grand total parity.
     * - Transitions to ConfirmedFinancialSuccess ONLY when paymentId and non-blank paymentReceiptNumber are returned.
     * - Keeps basket, preparedIntent, and HOLD state intact if payment remains unverified or status is unknown.
     */
    fun checkOnlineCashSaleStatus() {
        val currentState = _uiState.value
        if (currentState.saleDraft.isSubmitting) return

        val preparedIntent = currentState.saleDraft.preparedIntent ?: run {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "No retained intent to check status for")) }
            return
        }

        if (currentState.phase != StaffWorkspacePhase.READ_READY) {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Staff workspace is not in READ_READY phase")) }
            return
        }

        val coord = coordinator ?: run {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Coordinator is unconfigured")) }
            return
        }

        val readyState = coord.authState as? StaffAuthState.ReadReady ?: run {
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Session is not ready")) }
            return
        }

        val authority = currentState.saleAuthority ?: StaffOnlineSaleAuthorityResolution.resolve(
            readyState.bootstrap,
            readyState.session,
            currencyMinorUnitsOverride
        )

        if (!authority.isDispatchPermitted) {
            val missing = authority.missingContracts.joinToString("; ")
            _uiState.update { it.copy(saleDraft = it.saleDraft.copy(submissionError = "Authority resolution failed: $missing")) }
            return
        }

        val generation = coord.currentGeneration
        val thisIntent = ++activeIntentSeq

        _uiState.update { s ->
            s.copy(
                saleDraft = s.saleDraft.copy(
                    isSubmitting = true,
                    submissionError = null
                )
            )
        }

        saleJob?.cancel()
        saleJob = viewModelScope.launch {
            val targetCompanyId = authority.companyId!!
            val targetDeviceId = authority.deviceId!!
            val targetCurrency = authority.currency!!

            val result = try {
                withContext(ioDispatcher) {
                    val orchestrator = getOrchestrator()
                    orchestrator.checkOnlineCashSaleStatus(
                        intent = preparedIntent,
                        targetCompanyId = targetCompanyId,
                        targetDeviceId = targetDeviceId,
                        targetCurrency = targetCurrency
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (thisIntent != activeIntentSeq) return@launch

            if (!coord.isScopeActive(generation, targetCompanyId)) {
                _uiState.update { s ->
                    s.copy(
                        saleDraft = s.saleDraft.copy(
                            isSubmitting = false,
                            submissionError = "Session scope expired during sale status check"
                        )
                    )
                }
                return@launch
            }

            when (result) {
                is OnlineCashOrchestrationResult.ConfirmedFinancialSuccess -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = false,
                                isPaymentUnverifiedHold = false,
                                receipt = result.receipt,
                                unverifiedReceipt = null,
                                submissionError = null,
                                preparedIntent = null,
                                lines = emptyList(),
                                cashTendered = null
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.PostedWithoutPaymentReference -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = false,
                                isPaymentUnverifiedHold = true,
                                unverifiedReceipt = result.receipt,
                                receipt = null,
                                submissionError = "Invoice posted (${result.receipt.invoiceNumber}), but payment confirmation remains unverified. Sale is held in read-only status."
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.BusinessConflict -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                isIndeterminate = true,
                                submissionError = "Business conflict (409): event status indicates parked conflict on server."
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.AuthDenied -> {
                    if (coord.isScopeActive(result.scopeGeneration, targetCompanyId)) {
                        clearListsImmediately()
                        _uiState.update {
                            it.copy(
                                phase = StaffWorkspacePhase.LOCKED,
                                lockedReason = result.error.message,
                                isSubmitting = false
                            )
                        }
                    } else {
                        _uiState.update { s ->
                            s.copy(
                                saleDraft = s.saleDraft.copy(
                                    isSubmitting = false,
                                    submissionError = "Stale authentication rejection from prior session ignored"
                                )
                            )
                        }
                    }
                }
                is OnlineCashOrchestrationResult.Forbidden -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Forbidden (403): ${result.error.message}"
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.ValidationFailed -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Validation failed (422): ${result.error.message}"
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.StatusDigestMismatch -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Security error: server status payload digest mismatch (expected ${result.expected}, got ${result.actual})"
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.ScopeMismatch -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Session scope changed during sale status check"
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.StaleAuthDenied -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Stale authentication error received from previous scope"
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.LocalTokenUnavailable -> {
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = "Local token unavailable: ${result.reason}"
                            )
                        )
                    }
                }
                is OnlineCashOrchestrationResult.IndeterminateTransient,
                is OnlineCashOrchestrationResult.ParseFailure,
                is OnlineCashOrchestrationResult.PreDispatchValidationError,
                null -> {
                    val msg = when (result) {
                        is OnlineCashOrchestrationResult.IndeterminateTransient -> "Transient error while checking status: ${result.reason}"
                        is OnlineCashOrchestrationResult.ParseFailure -> "Failed to parse status response: ${result.category}"
                        is OnlineCashOrchestrationResult.PreDispatchValidationError -> "Validation error: ${result.message}"
                        else -> "Network or unexpected error while checking status"
                    }
                    _uiState.update { s ->
                        s.copy(
                            saleDraft = s.saleDraft.copy(
                                isSubmitting = false,
                                submissionError = msg
                            )
                        )
                    }
                }
            }
        }
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        ++outboxHealthIntentSeq
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null
        cachedOutboxHealthResult = null
        cachedHealthCompanyId = null
        cachedHealthActorId = null
        cachedHealthDeviceId = null

        authJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = StaffWorkspacePhase.INITIALIZING,
                    isSubmitting = true,
                    errorMessage = null,
                    lockedReason = null,
                    outboxHealthResult = null,
                    isOutboxRetrying = false
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
                                val compId = state.bootstrap.companyId.value
                                val actId = state.session.actorUlid.value
                                val devId = state.session.deviceUuid.value
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = compId,
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
                                triggerScopedOutboxHealth()
                                updateSaleAuthorityOnReadReady(state)
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        ++outboxHealthIntentSeq
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null
        cachedOutboxHealthResult = null
        cachedHealthCompanyId = null
        cachedHealthActorId = null
        cachedHealthDeviceId = null

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, outboxHealthResult = null, isOutboxRetrying = false) }
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
                                val compId = state.bootstrap.companyId.value
                                val actId = state.session.actorUlid.value
                                val devId = state.session.deviceUuid.value
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = compId,
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
                                triggerScopedOutboxHealth()
                                updateSaleAuthorityOnReadReady(state)
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, outboxHealthResult = null, isOutboxRetrying = false) }
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
                                val compId = state.bootstrap.companyId.value
                                val actId = state.session.actorUlid.value
                                val devId = state.session.deviceUuid.value
                                _uiState.update { s ->
                                    s.copy(
                                        phase = StaffWorkspacePhase.READ_READY,
                                        isSubmitting = false,
                                        activeCompany = SafeCompanyViewData(
                                            companyId = compId,
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
                                triggerScopedOutboxHealth()
                                updateSaleAuthorityOnReadReady(state)
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        ++outboxHealthIntentSeq
        if (_uiState.value.availableCompanies.isNotEmpty()) {
            _uiState.update { it.copy(phase = StaffWorkspacePhase.COMPANY_SELECTION, outboxHealthResult = null, isOutboxRetrying = false) }
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
     * Restores owned cached health result if scope is still active and matches, or triggers auth-derived inspection.
     * Ensures warning and Retry button remain/reappear for legacy/corrupt states, without blocking normal catalog reads.
     */
    fun cancelCompanySelection() {
        outboxHealthJob?.cancel()
        ++outboxHealthIntentSeq
        val active = _uiState.value.activeCompany
        val coord = coordinator
        if (active != null && coord != null) {
            val ulid = runCatching { ServerUlid.parse(active.companyId) }.getOrNull()
            if (ulid != null && coord.isScopeActive(coord.currentGeneration, ulid)) {
                val restoredHealth = if (cachedHealthCompanyId == active.companyId &&
                    cachedHealthActorId == _uiState.value.staffActor?.actorId &&
                    cachedOutboxHealthResult != null
                ) {
                    cachedOutboxHealthResult
                } else null

                _uiState.update {
                    it.copy(
                        phase = StaffWorkspacePhase.READ_READY,
                        outboxHealthResult = restoredHealth,
                        isOutboxRetrying = false,
                        errorMessage = null
                    )
                }
                if (restoredHealth == null) {
                    triggerScopedOutboxHealth()
                }
                (coord.authState as? StaffAuthState.ReadReady)?.let {
                    updateSaleAuthorityOnReadReady(it)
                }
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        ++outboxHealthIntentSeq
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null
        cachedOutboxHealthResult = null
        cachedHealthCompanyId = null
        cachedHealthActorId = null
        cachedHealthDeviceId = null
        clearListsImmediately()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, outboxHealthResult = null, isOutboxRetrying = false) }

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
                        val compId = state.bootstrap.companyId.value
                        val actId = state.session.actorUlid.value
                        val devId = state.session.deviceUuid.value
                        _uiState.update { s ->
                            s.copy(
                                phase = StaffWorkspacePhase.READ_READY,
                                isSubmitting = false,
                                activeCompany = SafeCompanyViewData(
                                    companyId = compId,
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
                        triggerScopedOutboxHealth()
                        updateSaleAuthorityOnReadReady(state)
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
                    checkAndHandleAuthDenied(result.message)
                    _uiState.update {
                        if (it.phase == StaffWorkspacePhase.LOCKED) return@update it
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        ++outboxHealthIntentSeq
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null
        cachedOutboxHealthResult = null
        cachedHealthCompanyId = null
        cachedHealthActorId = null
        cachedHealthDeviceId = null
        clearListsImmediately()

        authJob = viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, outboxHealthResult = null, isOutboxRetrying = false) }

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
                        val compId = state.bootstrap.companyId.value
                        val actId = state.session.actorUlid.value
                        val devId = state.session.deviceUuid.value
                        _uiState.update { s ->
                            s.copy(
                                phase = StaffWorkspacePhase.READ_READY,
                                isSubmitting = false,
                                activeCompany = SafeCompanyViewData(
                                    companyId = compId,
                                    companyName = state.bootstrap.companyName,
                                    currency = state.bootstrap.baseCurrency
                                ),
                                errorMessage = null
                            )
                        }
                        loadProducts(refresh = true)
                        loadCustomers(refresh = true)
                        triggerScopedOutboxHealth()
                        updateSaleAuthorityOnReadReady(state)
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
                    checkAndHandleAuthDenied(result.message)
                    _uiState.update {
                        if (it.phase == StaffWorkspacePhase.LOCKED) return@update it
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
        ++outboxHealthIntentSeq
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null
        cachedOutboxHealthResult = null
        cachedHealthCompanyId = null
        cachedHealthActorId = null
        cachedHealthDeviceId = null

        clearTransientCredentials()
        clearListsImmediately()

        // Disable new login until local purge result is known
        _uiState.update {
            it.copy(
                isSubmitting = true,
                activeCompany = null,
                staffActor = null,
                availableCompanies = emptyList(),
                errorMessage = messageOnSuccess,
                outboxHealthResult = null,
                isOutboxRetrying = false
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
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
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
                customersError = null,
                saleDraft = StaffOnlineSaleDraft(),
                saleAuthority = null,
                availableTaxes = emptyList()
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
        outboxHealthJob?.cancel()
        saleJob?.cancel()
        cachedOrchestrator = null
        cachedOrchestratorGeneration = null
    }

    internal fun performOnClearedForTesting() {
        onCleared()
    }

    internal fun hasCachedOrchestratorForTesting(): Boolean = cachedOrchestrator != null

    internal fun isSaleJobActiveForTesting(): Boolean = saleJob?.isActive == true

    private fun retryComposition() {
        val factory = coordinatorFactory
        if (factory == null) {
            _uiState.update { it.copy(phase = StaffWorkspacePhase.UNCONFIGURED, isSubmitting = false) }
            return
        }
        if (_uiState.value.isSubmitting) return
        val intent = ++activeIntentSeq
        outboxHealthJob?.cancel()
        activeScopeCompanyId = null
        activeScopeActorId = null
        activeScopeDeviceId = null
        _uiState.update { it.copy(phase = StaffWorkspacePhase.INITIALIZING, isSubmitting = true, outboxHealthResult = null, isOutboxRetrying = false) }
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
        private val authDenialListener: AuthDenialListener? = null,
        private val outboxHealthInspector: OutboxHealthInspector? = null,
        private val orchestratorFactory: ((
            tokenProvider: () -> String?,
            scopeGenerationProvider: () -> Long,
            onAuthDeniedLockScope: ((scopeGeneration: Long, error: ReadClientErrorResponse) -> Unit)?,
            activeScopeSupplier: (() -> OnlineSaleScope?)?
        ) -> OnlineCashSaleOrchestrator)? = null,
        private val onlineCashEnabled: Boolean = false,
        private val currencyMinorUnitsOverride: Int? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val effectiveLoopback = BuildConfig.DEBUG && allowHttpLoopback
            val effectiveInspector: OutboxHealthInspector = outboxHealthInspector ?: try {
                val db = com.example.data.mobile.outbox.OutboxDatabase.buildDatabase(context.applicationContext)
                com.example.data.mobile.outbox.DefaultOutboxHealthInspector(db, ioDispatcher = ioDispatcher)
            } catch (_: Exception) {
                com.example.data.mobile.outbox.UnavailableOutboxHealthInspector()
            }

            // 1. Validate configuredOrigin safely before attempting composition
            if (configuredOrigin.isBlank()) {
                return StaffWorkspaceViewModel(
                    coordinator = null,
                    configuredOrigin = configuredOrigin,
                    allowHttpLoopback = effectiveLoopback,
                    ioDispatcher = ioDispatcher,
                    authDenialListener = authDenialListener,
                    initialPhase = StaffWorkspacePhase.UNCONFIGURED,
                    outboxHealthInspector = effectiveInspector,
                    orchestratorFactory = orchestratorFactory,
                    onlineCashEnabled = onlineCashEnabled,
                    currencyMinorUnitsOverride = currencyMinorUnitsOverride
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
                    initialPhase = StaffWorkspacePhase.UNCONFIGURED,
                    outboxHealthInspector = effectiveInspector,
                    orchestratorFactory = orchestratorFactory,
                    onlineCashEnabled = onlineCashEnabled,
                    currencyMinorUnitsOverride = currencyMinorUnitsOverride
                ) as T
            }

            // 2. Safe coordinator composition: handle Keystore/Storage unavailability gracefully without crash
            val effectiveIncludeCurrencyMinorUnits = onlineCashEnabled || BuildConfig.STAFF_ONLINE_CASH_ENABLED
            val coordinator = try {
                StaffSessionCoordinator.create(
                    context = context.applicationContext,
                    baseOrigin = configuredOrigin,
                    allowHttpLoopback = effectiveLoopback,
                    authDenialListener = authDenialListener,
                    includeCurrencyMinorUnits = effectiveIncludeCurrencyMinorUnits
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
                        StaffSessionCoordinator.create(
                            context.applicationContext,
                            configuredOrigin,
                            effectiveLoopback,
                            authDenialListener,
                            includeCurrencyMinorUnits = effectiveIncludeCurrencyMinorUnits
                        )
                    },
                    outboxHealthInspector = effectiveInspector,
                    orchestratorFactory = orchestratorFactory,
                    onlineCashEnabled = onlineCashEnabled,
                    currencyMinorUnitsOverride = currencyMinorUnitsOverride
                ) as T
            }

            return StaffWorkspaceViewModel(
                coordinator = coordinator,
                configuredOrigin = configuredOrigin,
                allowHttpLoopback = effectiveLoopback,
                ioDispatcher = ioDispatcher,
                authDenialListener = authDenialListener,
                outboxHealthInspector = effectiveInspector,
                orchestratorFactory = orchestratorFactory,
                onlineCashEnabled = onlineCashEnabled,
                currencyMinorUnitsOverride = currencyMinorUnitsOverride
            ) as T
        }
    }
}
