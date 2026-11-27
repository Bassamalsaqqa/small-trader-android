package com.example.viewmodel

import com.example.ui.navigation.TraderNavigationPolicy
import com.example.ui.navigation.MerchantAction
import com.example.ui.navigation.allowed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfflineDemoTraderRepository
import com.example.data.SampleCatalog
import com.example.data.TraderRepository
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.contract.v2.PaymentDecimalConsistency
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.BluetoothPrinterDevice
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.DiscountPreset
import com.example.model.ExpenseCategory
import com.example.model.ExpenseEntry
import com.example.model.InvoiceRecord
import com.example.model.PrinterStatus
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.SaleDraft
import com.example.model.PaymentAllocationInput
import com.example.model.SettlementTarget
import com.example.model.SettlementTargetsResponse
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class TraderViewModelFactory(
    private val repository: TraderRepository
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TraderViewModel(repository = repository) as T
    }
}

class TraderViewModel(
    private val repository: TraderRepository = OfflineDemoTraderRepository()
) : ViewModel() {

    private val productionRepository = repository as? ProductionTraderRepository
    private val recoverSales = productionRepository?.takeIf { it.supportsSaleRecovery }

    private val _uiState = MutableStateFlow(
        TraderUiState(
            products = repository.getProducts(),
            stockMovements = repository.getStockMovements(),
            invoices = repository.getInvoices(),
            customers = repository.getCustomers(),
            customerTransactions = repository.getCustomerTransactions(),
            suppliers = repository.getSuppliers(),
            purchaseBills = repository.getPurchaseBills(),
            supplierPayments = repository.getSupplierPayments(),
            expenses = repository.getExpenses(),
            currentSession = repository.getCurrentSession(),
            businessInfo = repository.getBusinessInfo(),
            saleTaxOptions = productionRepository?.bootstrapTaxes.orEmpty(),
            draftExpenseOperationId = repository.getDraftOperationId("expense"),
            draftStockAdjustOperationId = repository.getDraftOperationId("inventory.adjustment"),
            draftPurchaseOperationId = repository.getDraftOperationId("purchase"),
            draftSupplierPaymentOperationId = repository.getDraftOperationId("supplier_payment"),
            draftCustomerPaymentOperationId = repository.getDraftOperationId("customer_payment"),
            activeCustomerPaymentDraft = repository.getActiveDraftPayload("customer_payment"),
            isCustomerPaymentRecovering = repository.isDraftRecovering("customer_payment"),
            activeSupplierPaymentDraft = repository.getActiveDraftPayload("supplier_payment"),
            isSupplierPaymentRecovering = repository.isDraftRecovering("supplier_payment"),
            draftCustomerCreateOperationId = repository.getDraftOperationId("customer_create"),
            draftSupplierCreateOperationId = repository.getDraftOperationId("supplier_create"),
            draftGrnOperationId = repository.getDraftOperationId("goods_receipt_note"),
            draftShiftCloseOperationId = repository.getDraftOperationId("shift_close"),
            activeShiftCloseDraft = repository.getActiveDraftPayload("shift_close"),
            isShiftCloseRecovering = repository.isDraftRecovering("shift_close"),
            shiftCloseCountedCashInput = repository.getActiveDraftPayload("shift_close")?.properties?.get("counted_cash")?.asString() ?: "",
            shiftCloseNotesInput = repository.getActiveDraftPayload("shift_close")?.properties?.get("notes")?.asString() ?: "",
            shiftCloseSuccessSnapshot = repository.getLatestCompletedShiftClose(),
            shiftClosedTimestamp = repository.getLatestCompletedShiftClose()?.closedAt,
            isPaymentUnverifiedHold = recoverSales != null,
            isSubmittingSale = recoverSales != null,
            productsHasMore = repository.productsHasMoreFlow.value,
            customersHasMore = repository.customersHasMoreFlow.value,
            suppliersHasMore = repository.suppliersHasMoreFlow.value
        )
    )
    val uiState: StateFlow<TraderUiState> = _uiState.asStateFlow()

    init {
        if (recoverSales != null) {
            viewModelScope.launch { restoreSaleHold(recoverSales) }
        } else {
            repository.getInvoices().firstOrNull {
                it.clientEventId != null && !isInvoiceCanonicallyConfirmed(it, it.clientEventId)
            }?.let(::showHeldSale)
        }
        viewModelScope.launch {
            repository.productsFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        products = list,
                        selectedProductForDetail = current.selectedProductForDetail?.let { sel ->
                            list.firstOrNull { it.id == sel.id } ?: sel
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.customersFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        customers = list,
                        selectedCustomerForDetail = current.selectedCustomerForDetail?.let { sel ->
                            list.firstOrNull { it.id == sel.id } ?: sel
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.suppliersFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        suppliers = list,
                        selectedSupplierForDetail = current.selectedSupplierForDetail?.let { sel ->
                            list.firstOrNull { it.id == sel.id } ?: sel
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.suppliersHasMoreFlow.collect { hasMore ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(suppliersHasMore = hasMore) }
            }
        }
        viewModelScope.launch {
            repository.productsHasMoreFlow.collect { hasMore ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(productsHasMore = hasMore) }
            }
        }
        viewModelScope.launch {
            repository.customersHasMoreFlow.collect { hasMore ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(customersHasMore = hasMore) }
            }
        }
        viewModelScope.launch {
            repository.purchasesFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        purchaseBills = list
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.expensesFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(expenses = list) }
            }
        }
        viewModelScope.launch {
            repository.invoicesFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        invoices = list,
                        activeReceipt = current.activeReceipt?.let { ar ->
                            list.firstOrNull { it.id == ar.id || (!ar.clientEventId.isNullOrBlank() && it.clientEventId == ar.clientEventId) } ?: ar
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.stockMovementsFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(stockMovements = list) }
            }
        }
        viewModelScope.launch {
            repository.customerTransactionsFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(customerTransactions = list) }
            }
        }
        viewModelScope.launch {
            repository.supplierPaymentsFlow.collect { list ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(supplierPayments = list) }
            }
        }
        viewModelScope.launch {
            try {
                if (!ensureActiveScope()) return@launch
                val cats = repository.getServerExpenseCategories()
                if (ensureActiveScope() && cats.isNotEmpty()) {
                    _uiState.update { current -> current.copy(serverExpenseCategories = cats) }
                }
            } catch (_: Exception) {}
        }

    }

    private var printJob: Job? = null
    private var bannerJob: Job? = null
    private var customerDetailJob: Job? = null
    private var customerDetailGeneration = 0L
    private var customerStatementJob: Job? = null
    private var customerStatementGeneration = 0L
    private var productHistoryJob: Job? = null
    private var productHistoryGeneration = 0L
    private var customItemCounter = 1

    private fun ensureActiveScope(): Boolean {
        if (repository.isScopeActive()) return true
        invalidateForInactiveScope()
        return false
    }

    private fun invalidateForInactiveScope() {
        productSearchJob?.cancel()
        productSearchGeneration++
        customerSearchJob?.cancel()
        customerSearchGeneration++
        supplierSearchJob?.cancel()
        supplierSearchGeneration++
        customerStatementJob?.cancel()
        customerStatementGeneration++
        customerDetailJob?.cancel()
        customerDetailGeneration++
        productHistoryJob?.cancel()
        productHistoryGeneration++
        customerSettlementJob?.cancel()
        customerSettlementGeneration++
        supplierSettlementJob?.cancel()
        supplierSettlementGeneration++
        _uiState.update { state ->
            state.copy(
                isScopeInvalidated = true,
                liveHomeMetrics = null,
                liveTreasurySummary = null,
                liveShiftSummary = null,
                shiftCloseSuccessSnapshot = null,
                shiftClosedTimestamp = null,
                shiftObservedTimestamp = null,
                isShiftClosedPreview = false,
                isShiftCloseDialogOpen = false,
                isShiftCloseSubmitting = false,
                shiftCloseError = null,
                shiftCloseCountedCashInput = "",
                shiftCloseNotesInput = "",
                countedPhysicalCashText = "",
                products = emptyList(),
                stockMovements = emptyList(),
                customers = emptyList(),
                customerTransactions = emptyList(),
                suppliers = emptyList(),
                purchaseBills = emptyList(),
                supplierPayments = emptyList(),
                expenses = emptyList(),
                invoices = emptyList(),
                basket = emptyList(),
                selectedCustomerId = null,
                selectedProductForDetail = null,
                selectedProductHistory = null,
                isLoadingProductHistory = false,
                productHistoryError = null,
                selectedCustomerForDetail = null,
                selectedCustomerStatement = null,
                isLoadingCustomerStatement = false,
                customerStatementError = null,
                selectedSupplierForDetail = null,
                activeReceipt = null,
                selectedInvoiceDetail = null,
                pendingAction = null,
                isQuickActionOpen = false,
                isPaymentCollectionDialogOpen = false,
                isCustomerPaymentSubmitting = false,
                customerPaymentError = null,
                isSupplierPaymentSubmitting = false,
                supplierPaymentError = null,
                isAddCustomerDialogOpen = false,
                isAddExpenseDialogOpen = false,
                isReceivePurchaseDialogOpen = false,
                productSearchQuery = "",
                productSearchResults = null,
                isSearchingProducts = false,
                productSearchError = null,
                customerSearchQuery = "",
                customerSearchResults = null,
                isSearchingCustomers = false,
                customerSearchError = null,
                supplierSearchQuery = "",
                supplierSearchResults = null,
                isSearchingSuppliers = false,
                supplierSearchError = null,
                customerSettlementTargets = emptyList(),
                isLoadingCustomerSettlementTargets = false,
                customerSettlementTargetsError = null,
                customerSettlementTargetsHasMore = false,
                customerSettlementTargetsNextCursor = null,
                supplierSettlementTargets = emptyList(),
                isLoadingSupplierSettlementTargets = false,
                supplierSettlementTargetsError = null,
                supplierSettlementTargetsHasMore = false,
                supplierSettlementTargetsNextCursor = null
            )
        }
    }

    private fun mapSafeDetailReadError(ex: Throwable?, subjectAr: String, subjectEn: String): String {
        val raw = ex?.message.orEmpty()
        return when {
            ex is NoSuchElementException ->
                "تعذر العثور على $subjectAr على الخادم | Not found: $subjectEn is unavailable"
            ex is SecurityException && (raw.startsWith("Forbidden:", ignoreCase = true) || raw.contains("403")) ->
                "غير مصرح: لا تملك صلاحية عرض $subjectAr | Permission denied: insufficient access to view $subjectEn"
            ex is SecurityException ->
                "انتهت صلاحية الجلسة أو يلزم إعادة الدخول لعرض $subjectAr | Authentication expired or revoked while loading $subjectEn"
            ex is java.io.IOException ->
                "تعذر الاتصال بالخادم أثناء تحميل $subjectAr | Network error while loading $subjectEn"
            else ->
                "تعذر تحميل $subjectAr. يرجى المحاولة مرة أخرى. | Failed to load $subjectEn. Please try again."
        }
    }

    private fun syncProductsAndInventory() {
        val updatedProducts = repository.getProducts()
        _uiState.update { current ->
            current.copy(
                products = updatedProducts,
                stockMovements = repository.getStockMovements(),
                selectedProductForDetail = current.selectedProductForDetail?.let { sel ->
                    updatedProducts.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    private fun syncCustomers() {
        val updatedCustomers = repository.getCustomers()
        _uiState.update { current ->
            current.copy(
                customers = updatedCustomers,
                customerTransactions = repository.getCustomerTransactions(),
                selectedCustomerForDetail = current.selectedCustomerForDetail?.let { sel ->
                    updatedCustomers.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    private fun syncSuppliersAndPurchases() {
        val updatedSuppliers = repository.getSuppliers()
        _uiState.update { current ->
            current.copy(
                suppliers = updatedSuppliers,
                purchaseBills = repository.getPurchaseBills(),
                supplierPayments = repository.getSupplierPayments(),
                selectedSupplierForDetail = current.selectedSupplierForDetail?.let { sel ->
                    updatedSuppliers.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    private fun syncExpenses() {
        _uiState.update { current ->
            current.copy(expenses = repository.getExpenses())
        }
    }

    fun syncFromRepository() {
        val updatedProducts = repository.getProducts()
        val updatedCustomers = repository.getCustomers()
        val updatedSuppliers = repository.getSuppliers()
        _uiState.update { current ->
            current.copy(
                products = updatedProducts,
                stockMovements = repository.getStockMovements(),
                invoices = repository.getInvoices(),
                customers = updatedCustomers,
                customerTransactions = repository.getCustomerTransactions(),
                suppliers = updatedSuppliers,
                purchaseBills = repository.getPurchaseBills(),
                supplierPayments = repository.getSupplierPayments(),
                expenses = repository.getExpenses(),
                businessInfo = repository.getBusinessInfo(),
                selectedProductForDetail = current.selectedProductForDetail?.let { sel ->
                    updatedProducts.firstOrNull { it.id == sel.id } ?: sel
                },
                selectedCustomerForDetail = current.selectedCustomerForDetail?.let { sel ->
                    updatedCustomers.firstOrNull { it.id == sel.id } ?: sel
                },
                selectedSupplierForDetail = current.selectedSupplierForDetail?.let { sel ->
                    updatedSuppliers.firstOrNull { it.id == sel.id } ?: sel
                }
            )
        }
    }

    fun toggleLanguage() {
        _uiState.update { state ->
            val next = if (state.language == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR
            state.copy(language = next)
        }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(isDarkTheme = !it.isDarkTheme) }
    }

    fun setDarkTheme(enabled: Boolean) {
        _uiState.update { it.copy(isDarkTheme = enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
    }

    fun switchUserSession(user: UserSession) {
        if (!ensureActiveScope()) return
        productSearchJob?.cancel()
        productSearchGeneration++
        customerSearchJob?.cancel()
        customerSearchGeneration++
        supplierSearchJob?.cancel()
        supplierSearchGeneration++
        customerStatementJob?.cancel()
        customerStatementGeneration++
        customerDetailJob?.cancel()
        customerDetailGeneration++
        productHistoryJob?.cancel()
        productHistoryGeneration++
        customerSettlementJob?.cancel()
        customerSettlementGeneration++
        supplierSettlementJob?.cancel()
        supplierSettlementGeneration++

        _uiState.update { state ->
            val safe = TraderNavigationPolicy.allows(user, state.currentScreen)
            val nextScreen = if (safe) state.currentScreen else TraderScreen.HOME
            val allowCustomerDetail = TraderNavigationPolicy.allows(user, TraderScreen.CUSTOMER_DETAIL) &&
                (user.permissions == null || "customers.view" in user.permissions)
            val allowProductDetail = TraderNavigationPolicy.allows(user, TraderScreen.PRODUCT_DETAIL)
            state.copy(
                currentSession = user,
                currentScreen = nextScreen,
                selectedRoot = if (safe) state.selectedRoot else TraderScreen.HOME,
                screenHistory = state.screenHistory.filter { TraderNavigationPolicy.allows(user, it) },
                searchQuery = when (nextScreen) {
                    TraderScreen.PRODUCTS_CATALOG -> state.inventorySearchQuery
                    TraderScreen.NEW_SALE -> state.saleSearchQuery
                    else -> state.searchQuery
                },
                selectedCategory = when (nextScreen) {
                    TraderScreen.PRODUCTS_CATALOG -> state.inventoryCategory
                    TraderScreen.NEW_SALE -> state.saleCategory
                    else -> state.selectedCategory
                },
                pendingAction = null,
                isQuickActionOpen = false,
                isPaymentCollectionDialogOpen = false,
                isReceivePurchaseDialogOpen = false,
                isAddExpenseDialogOpen = false,
                selectedSupplierForDetail = null,
                selectedCustomerForDetail = if (allowCustomerDetail) state.selectedCustomerForDetail else null,
                selectedCustomerStatement = null,
                isLoadingCustomerStatement = false,
                customerStatementError = null,
                selectedProductForDetail = if (allowProductDetail) state.selectedProductForDetail else null,
                selectedProductHistory = null,
                isLoadingProductHistory = false,
                productHistoryError = null,
                productSearchQuery = "",
                productSearchResults = null,
                isSearchingProducts = false,
                productSearchError = null,
                customerSearchQuery = "",
                customerSearchResults = null,
                isSearchingCustomers = false,
                customerSearchError = null,
                supplierSearchQuery = "",
                supplierSearchResults = null,
                isSearchingSuppliers = false,
                supplierSearchError = null,
                customerSettlementTargets = emptyList(),
                isLoadingCustomerSettlementTargets = false,
                customerSettlementTargetsError = null,
                customerSettlementTargetsHasMore = false,
                customerSettlementTargetsNextCursor = null,
                supplierSettlementTargets = emptyList(),
                isLoadingSupplierSettlementTargets = false,
                supplierSettlementTargetsError = null,
                supplierSettlementTargetsHasMore = false,
                supplierSettlementTargetsNextCursor = null
            )
        }
        val after = _uiState.value
        syncProductSearchForScreen(after.currentScreen)
        if (repository !is OfflineDemoTraderRepository && !after.currentSession.isDemoSession) {
            if (after.currentScreen == TraderScreen.CUSTOMER_DETAIL) {
                after.selectedCustomerForDetail?.let { loadCustomerStatement(it.id) }
            } else if (after.currentScreen == TraderScreen.PRODUCT_DETAIL) {
                after.selectedProductForDetail?.let { loadProductStockHistory(it.id) }
            }
        }
    }

    fun setQuickActionOpen(open: Boolean) {
        if (open && !ensureActiveScope()) return
        _uiState.update { it.copy(isQuickActionOpen = open) }
    }

    fun consumeAction() {
        val currentAction = _uiState.value.pendingAction
        if (currentAction == MerchantAction.COLLECT) {
            customerSearchJob?.cancel()
            customerSearchGeneration++
            _uiState.update {
                it.copy(
                    pendingAction = null,
                    customerSearchQuery = "",
                    customerSearchResults = null,
                    isSearchingCustomers = false,
                    customerSearchError = null
                )
            }
        } else if (currentAction == MerchantAction.PAY) {
            supplierSearchJob?.cancel()
            supplierSearchGeneration++
            _uiState.update {
                it.copy(
                    pendingAction = null,
                    supplierSearchQuery = "",
                    supplierSearchResults = null,
                    isSearchingSuppliers = false,
                    supplierSearchError = null
                )
            }
        } else {
            _uiState.update { it.copy(pendingAction = null) }
        }
    }

    fun openMerchantAction(action: MerchantAction) {
        if (!ensureActiveScope()) return
        if (!action.allowed(_uiState.value.currentSession)) return
        _uiState.update { it.copy(pendingAction = null, isQuickActionOpen = false) }
        when (action) {
            MerchantAction.SALE -> navigateTo(TraderScreen.NEW_SALE)
            MerchantAction.PURCHASE -> { navigateTo(TraderScreen.PURCHASES); openReceivePurchaseDialog(true) }
            MerchantAction.CUSTOMER -> { navigateTo(TraderScreen.CUSTOMERS); openAddCustomerDialog(true) }
            MerchantAction.EXPENSE -> { navigateTo(TraderScreen.EXPENSES); openAddExpenseDialog(true) }
            MerchantAction.COLLECT -> {
                customerSearchJob?.cancel()
                customerSearchGeneration++
                _uiState.update {
                    it.copy(
                        pendingAction = MerchantAction.COLLECT,
                        customerSearchQuery = "",
                        customerSearchResults = null,
                        isSearchingCustomers = false,
                        customerSearchError = null
                    )
                }
                val snap = _uiState.value
                if (!snap.currentSession.isDemoSession &&
                    (snap.customers.isEmpty() || snap.readStatus[TraderScreen.CUSTOMERS] == ReadStatus.FAILED)) {
                    loadScreen(TraderScreen.CUSTOMERS, retry = true)
                }
            }
            MerchantAction.PAY -> {
                supplierSearchJob?.cancel()
                supplierSearchGeneration++
                _uiState.update {
                    it.copy(
                        pendingAction = MerchantAction.PAY,
                        supplierSearchQuery = "",
                        supplierSearchResults = null,
                        isSearchingSuppliers = false,
                        supplierSearchError = null
                    )
                }
                val snap = _uiState.value
                if (!snap.currentSession.isDemoSession &&
                    (snap.suppliers.isEmpty() || snap.readStatus[TraderScreen.SUPPLIERS] == ReadStatus.FAILED)) {
                    loadScreen(TraderScreen.SUPPLIERS, retry = true)
                }
            }
            else -> {
                navigateTo(when (action) {
                    MerchantAction.PRODUCT -> TraderScreen.PRODUCTS_CATALOG
                    else -> TraderScreen.SUPPLIERS
                })
                _uiState.update { it.copy(pendingAction = action) }
            }
        }
    }

    fun navigateTo(target: TraderScreen) {
        if (!ensureActiveScope()) return
        if (!TraderNavigationPolicy.allows(_uiState.value.currentSession, target)) return
        val currentNavState = _uiState.value
        if (currentNavState.isPaymentUnverifiedHold && (target == TraderScreen.INVOICE_REVIEW || target == TraderScreen.NEW_SALE)) {
            _uiState.update { it.copy(currentScreen = TraderScreen.CASH_PAYMENT) }
            syncProductSearchForScreen(TraderScreen.CASH_PAYMENT)
            return
        }
        if (target == TraderScreen.CASH_PAYMENT && currentNavState.isNetworkOffline &&
            currentNavState.selectedCustomerId != null &&
            currentNavState.selectedCustomerId != currentNavState.activeOfflineSaleLease?.walkInCustomerId?.value) {
            showBanner("البيع دون اتصال متاح للزبون النقدي فقط؛ اختيارك محفوظ", "Offline capture supports walk-in sales only; your customer selection is retained", isError = true)
            return
        }
        if (target == TraderScreen.CASH_PAYMENT && currentNavState.hasUnresolvedSaleTax) {
            showBanner("اختر ضريبة كل صنف", "Select a tax treatment for each item", isError = true)
            return
        }
        if (target == TraderScreen.CASH_PAYMENT && currentNavState.hasUnsupportedProductionDiscount) {
            val ar = "لا يمكن المتابعة للدفع مع وجود خصم في الإصدار السحابي الحالي — يرجى إزالة الخصم مع الاحتفاظ بالمسودة"
            val en = "Cannot proceed to payment with a discount in current cloud version — please remove the discount to proceed"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }
        val movedScreen = currentNavState.currentScreen != target
        _uiState.update { state ->
            if (state.currentScreen == target) {
                state
            } else {
                val updatedHistory = when {
                    target == TraderScreen.HOME -> emptyList()
                    target in TraderUiState.ROOT_DESTINATIONS -> {
                        listOf(TraderScreen.HOME)
                    }
                    target in state.screenHistory -> {
                        state.screenHistory.takeWhile { it != target }
                    }
                    else -> {
                        (state.screenHistory + state.currentScreen).takeLast(8)
                    }
                }
                val defaultCashText = if (target == TraderScreen.CASH_PAYMENT && state.cashInputText.isBlank()) {
                    exactCashEntry(state).orEmpty()
                } else {
                    state.cashInputText
                }
                state.copy(
                    currentScreen = target,
                    selectedRoot = if (target in TraderUiState.ROOT_DESTINATIONS) target else state.selectedRoot,
                    isQuickActionOpen = false,
                    pendingAction = null,
                    searchQuery = if (target == TraderScreen.PRODUCTS_CATALOG) state.inventorySearchQuery else if (target == TraderScreen.NEW_SALE) state.saleSearchQuery else state.searchQuery,
                    selectedCategory = if (target == TraderScreen.PRODUCTS_CATALOG) state.inventoryCategory else if (target == TraderScreen.NEW_SALE) state.saleCategory else state.selectedCategory,
                    screenHistory = updatedHistory,
                    cashInputText = defaultCashText,
                    checkoutErrorAr = null,
                    checkoutErrorEn = null
                )
            }
        }
        if (movedScreen) {
            syncProductSearchForScreen(target)
        }
    }

    fun navigateBack(): Boolean {
        val activeScope = ensureActiveScope()
        val current = _uiState.value
        if (current.isQuickActionOpen) { setQuickActionOpen(false); return true }
        if (current.pendingAction != null) { consumeAction(); return true }
        if (current.isScannerSheetOpen) {
            _uiState.update { it.copy(isScannerSheetOpen = false) }
            return true
        }
        if (current.isPrinterSheetOpen) {
            _uiState.update { it.copy(isPrinterSheetOpen = false) }
            return true
        }
        if (current.isCustomItemDialogOpen) {
            _uiState.update { it.copy(isCustomItemDialogOpen = false) }
            return true
        }
        if (current.isPaymentCollectionDialogOpen) {
            openPaymentCollectionDialog(false)
            return true
        }
        if (current.isAddCustomerDialogOpen) {
            _uiState.update { it.copy(isAddCustomerDialogOpen = false) }
            return true
        }
        if (current.isAddExpenseDialogOpen) {
            _uiState.update { it.copy(isAddExpenseDialogOpen = false) }
            return true
        }
        if (current.isReceivePurchaseDialogOpen) {
            _uiState.update { it.copy(isReceivePurchaseDialogOpen = false) }
            return true
        }
        if (current.isShiftCloseDialogOpen) {
            _uiState.update { it.copy(isShiftCloseDialogOpen = false) }
            return true
        }
        if (current.currentScreen == TraderScreen.HOME) return false
        val previous = current.screenHistory.lastOrNull() ?: TraderScreen.HOME
        if (current.currentScreen == TraderScreen.CUSTOMER_DETAIL && previous != TraderScreen.CUSTOMER_DETAIL) {
            customerDetailJob?.cancel()
            customerDetailGeneration++
            customerStatementJob?.cancel()
            customerStatementGeneration++
        }
        if (current.currentScreen == TraderScreen.PRODUCT_DETAIL && previous != TraderScreen.PRODUCT_DETAIL) {
            productHistoryJob?.cancel()
            productHistoryGeneration++
        }
        _uiState.update { state ->
            state.copy(
                currentScreen = previous,
                searchQuery = when (previous) {
                    TraderScreen.NEW_SALE -> state.saleSearchQuery
                    TraderScreen.PRODUCTS_CATALOG -> state.inventorySearchQuery
                    else -> state.searchQuery
                },
                selectedCategory = when (previous) {
                    TraderScreen.NEW_SALE -> state.saleCategory
                    TraderScreen.PRODUCTS_CATALOG -> state.inventoryCategory
                    else -> state.selectedCategory
                },
                selectedRoot = if (previous in TraderUiState.ROOT_DESTINATIONS) previous else state.selectedRoot,
                screenHistory = state.screenHistory.dropLast(1),
                isLoadingCustomerStatement = if (previous == TraderScreen.CUSTOMER_DETAIL) state.isLoadingCustomerStatement else false,
                isLoadingProductHistory = if (previous == TraderScreen.PRODUCT_DETAIL) state.isLoadingProductHistory else false
            )
        }
        if (activeScope) {
            syncProductSearchForScreen(previous)
            val updated = _uiState.value
            if (repository !is OfflineDemoTraderRepository && !updated.currentSession.isDemoSession) {
                if (previous == TraderScreen.CUSTOMER_DETAIL && updated.selectedCustomerForDetail != null &&
                    updated.selectedCustomerStatement == null && !updated.isLoadingCustomerStatement) {
                    loadCustomerStatement(updated.selectedCustomerForDetail.id)
                } else if (previous == TraderScreen.PRODUCT_DETAIL && updated.selectedProductForDetail != null &&
                    updated.selectedProductHistory == null && !updated.isLoadingProductHistory) {
                    loadProductStockHistory(updated.selectedProductForDetail.id)
                }
            }
        }
        return true
    }

    // Product Catalog & Inventory
    fun updateSearchQuery(query: String) {
        if (!ensureActiveScope()) return
        _uiState.update { it.copy(searchQuery = query,
            saleSearchQuery = if (it.currentScreen == TraderScreen.NEW_SALE) query else it.saleSearchQuery,
            inventorySearchQuery = if (it.currentScreen == TraderScreen.PRODUCTS_CATALOG) query else it.inventorySearchQuery) }
        searchProductsOnServer(query)
    }

    fun clearSearchQuery() {
        updateSearchQuery("")
    }

    private fun syncProductSearchForScreen(target: TraderScreen) {
        if (!ensureActiveScope()) return
        val state = _uiState.value
        if (target != TraderScreen.NEW_SALE && target != TraderScreen.PRODUCTS_CATALOG) {
            productSearchJob?.cancel()
            productSearchGeneration++
            _uiState.update {
                it.copy(
                    isSearchingProducts = false,
                    productSearchError = null
                )
            }
            return
        }
        val restored = state.searchQuery
        if (restored.isBlank()) {
            productSearchJob?.cancel()
            productSearchGeneration++
            _uiState.update {
                it.copy(
                    productSearchQuery = "",
                    productSearchResults = null,
                    isSearchingProducts = false,
                    productSearchError = null
                )
            }
            return
        }
        if (state.currentSession.isDemoSession) {
            productSearchJob?.cancel()
            productSearchGeneration++
            _uiState.update {
                it.copy(
                    productSearchQuery = restored,
                    productSearchResults = null,
                    isSearchingProducts = false,
                    productSearchError = null
                )
            }
            return
        }
        val alreadyMatchesCommitted =
            state.productSearchQuery.trim().equals(restored.trim(), ignoreCase = true) &&
                state.productSearchResults != null &&
                state.productSearchError == null &&
                !state.isSearchingProducts
        if (!alreadyMatchesCommitted) {
            productSearchJob?.cancel()
            productSearchGeneration++
            _uiState.update {
                it.copy(
                    productSearchQuery = "",
                    productSearchResults = null,
                    isSearchingProducts = false,
                    productSearchError = null
                )
            }
            searchProductsOnServer(restored)
        }
    }

    fun openLowStockInventory() {
        if (!ensureActiveScope()) return
        navigateTo(TraderScreen.PRODUCTS_CATALOG)
        _uiState.update { it.copy(inventoryLowStockRequested = true,
            inventorySearchQuery = "", searchQuery = "",
            inventoryCategory = ProductCategory.ALL, selectedCategory = ProductCategory.ALL) }
        syncProductSearchForScreen(TraderScreen.PRODUCTS_CATALOG)
    }

    fun consumeLowStockRequest() {
        _uiState.update { it.copy(inventoryLowStockRequested = false) }
    }

    fun selectCategory(category: ProductCategory) {
        if (!ensureActiveScope()) return
        _uiState.update { state -> state.copy(selectedCategory = category,
            saleCategory = if (state.currentScreen == TraderScreen.NEW_SALE) category else state.saleCategory,
            inventoryCategory = if (state.currentScreen == TraderScreen.PRODUCTS_CATALOG) category else state.inventoryCategory) }
    }

    fun selectProductForDetail(product: Product) {
        if (!ensureActiveScope()) return
        val freshProduct = _uiState.value.products.firstOrNull { it.id == product.id } ?: product
        val isLive = repository !is OfflineDemoTraderRepository
        productHistoryJob?.cancel()
        productHistoryGeneration++
        productSearchJob?.cancel()
        productSearchGeneration++
        _uiState.update { state ->
            state.copy(
                selectedProductForDetail = freshProduct,
                selectedProductHistory = null,
                isLoadingProductHistory = isLive,
                productHistoryError = null,
                isSearchingProducts = false,
                lastInventoryActionMessageAr = null,
                lastInventoryActionMessageEn = null,
                isInventoryActionError = false,
                currentScreen = TraderScreen.PRODUCT_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
        if (isLive) loadProductStockHistory(freshProduct.id)
    }

    fun dismissInventoryFeedback() {
        _uiState.update {
            it.copy(
                lastInventoryActionMessageAr = null,
                lastInventoryActionMessageEn = null,
                isInventoryActionError = false
            )
        }
    }

    init {
        viewModelScope.launch {
            repository.homeMetricsFlow.collect { home ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        liveHomeMetrics = home?.let { com.example.data.mobile.merchantread.MerchantReadAdapter.toLiveHomeMetrics(it) }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.shiftSummaryFlow.collect { shift ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        liveShiftSummary = shift?.let { com.example.data.mobile.merchantread.MerchantReadAdapter.toLiveShiftSummary(it) }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.shiftCloseSuccessFlow.collect { closeSnap ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        shiftCloseSuccessSnapshot = closeSnap,
                        shiftClosedTimestamp = closeSnap?.closedAt ?: current.shiftClosedTimestamp
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.treasuryReportFlow.collect { treasury ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        liveTreasurySummary = treasury?.let { com.example.data.mobile.merchantread.MerchantReadAdapter.toLiveTreasurySummary(it) }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.invoicesHasMoreFlow.collect { hasMore ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current ->
                    current.copy(
                        liveInvoicesHasMore = hasMore,
                        liveInvoicesHasMoreGap = hasMore
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.invoicesNextCursorFlow.collect { cursor ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(liveInvoicesNextCursor = cursor) }
            }
        }
        viewModelScope.launch {
            repository.isInvoicesLoadingFlow.collect { loading ->
                if (!ensureActiveScope()) return@collect
                _uiState.update { current -> current.copy(isLiveInvoicesLoading = loading) }
            }
        }
    }

    fun loadMoreInvoices(onComplete: ((Boolean) -> Unit)? = null) {
        if (!ensureActiveScope()) {
            onComplete?.invoke(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.loadMoreInvoices()
            withContext(Dispatchers.Main) {
                if (!ensureActiveScope()) {
                    onComplete?.invoke(false)
                    return@withContext
                }
                onComplete?.invoke(result.isSuccess)
            }
        }
    }

    fun loadCustomerStatement(customerId: String, fromDate: String? = null, toDate: String? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (!ensureActiveScope()) {
            onComplete?.invoke(false)
            return
        }
        customerStatementJob?.cancel()
        val generation = ++customerStatementGeneration
        _uiState.update { current ->
            current.copy(
                isLoadingCustomerStatement = true,
                customerStatementError = null
            )
        }
        customerStatementJob = viewModelScope.launch {
            try {
                val result = repository.getCustomerStatement(customerId, fromDate, toDate)
                if (!ensureActiveScope()) {
                    onComplete?.invoke(false)
                    return@launch
                }
                if (generation != customerStatementGeneration) {
                    onComplete?.invoke(false)
                    return@launch
                }
                result.onSuccess { statement ->
                    _uiState.update { current ->
                        if (current.selectedCustomerForDetail?.id != customerId) {
                            current.copy(isLoadingCustomerStatement = false)
                        } else {
                            current.copy(
                                selectedCustomerStatement = com.example.data.mobile.merchantread.LiveCustomerStatement(
                                    customerId = statement.customerId.value,
                                    customerName = statement.customerName,
                                    fromDate = statement.fromDate,
                                    toDate = statement.toDate,
                                    currencies = statement.currencies
                                ),
                                isLoadingCustomerStatement = false,
                                customerStatementError = null
                            )
                        }
                    }
                    onComplete?.invoke(true)
                }.onFailure { err ->
                    if (!ensureActiveScope()) {
                        onComplete?.invoke(false)
                        return@onFailure
                    }
                    val safeMsg = mapSafeDetailReadError(err, "كشف حساب الزبون", "customer statement")
                    _uiState.update { current ->
                        if (current.selectedCustomerForDetail?.id != customerId) {
                            current.copy(isLoadingCustomerStatement = false)
                        } else {
                            current.copy(
                                selectedCustomerStatement = null,
                                isLoadingCustomerStatement = false,
                                customerStatementError = safeMsg
                            )
                        }
                    }
                    onComplete?.invoke(false)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!ensureActiveScope()) {
                    onComplete?.invoke(false)
                    return@launch
                }
                if (generation == customerStatementGeneration) {
                    val safeMsg = mapSafeDetailReadError(e, "كشف حساب الزبون", "customer statement")
                    _uiState.update { current ->
                        if (current.selectedCustomerForDetail?.id != customerId) {
                            current.copy(isLoadingCustomerStatement = false)
                        } else {
                            current.copy(
                                selectedCustomerStatement = null,
                                isLoadingCustomerStatement = false,
                                customerStatementError = safeMsg
                            )
                        }
                    }
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun loadProductStockHistory(productId: String, limit: Int = 50, onComplete: ((Boolean) -> Unit)? = null) {
        if (!ensureActiveScope()) {
            onComplete?.invoke(false)
            return
        }
        productHistoryJob?.cancel()
        val generation = ++productHistoryGeneration
        _uiState.update { current ->
            current.copy(
                isLoadingProductHistory = true,
                productHistoryError = null
            )
        }
        productHistoryJob = viewModelScope.launch {
            try {
                val result = repository.getProductStockHistory(productId, limit)
                if (!ensureActiveScope()) {
                    onComplete?.invoke(false)
                    return@launch
                }
                if (generation != productHistoryGeneration) {
                    onComplete?.invoke(false)
                    return@launch
                }
                result.onSuccess { history ->
                    _uiState.update { current ->
                        if (current.selectedProductForDetail?.id != productId) {
                            current.copy(isLoadingProductHistory = false)
                        } else {
                            current.copy(
                                selectedProductHistory = com.example.data.mobile.merchantread.LiveStockMovementHistory(
                                    productId = history.productId.value,
                                    movements = history.movements
                                ),
                                isLoadingProductHistory = false,
                                productHistoryError = null
                            )
                        }
                    }
                    onComplete?.invoke(true)
                }.onFailure { err ->
                    if (!ensureActiveScope()) {
                        onComplete?.invoke(false)
                        return@onFailure
                    }
                    val safeMsg = mapSafeDetailReadError(err, "حركات المخزون", "stock movement history")
                    _uiState.update { current ->
                        if (current.selectedProductForDetail?.id != productId) {
                            current.copy(isLoadingProductHistory = false)
                        } else {
                            current.copy(
                                selectedProductHistory = null,
                                isLoadingProductHistory = false,
                                productHistoryError = safeMsg
                            )
                        }
                    }
                    onComplete?.invoke(false)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!ensureActiveScope()) {
                    onComplete?.invoke(false)
                    return@launch
                }
                if (generation == productHistoryGeneration) {
                    val safeMsg = mapSafeDetailReadError(e, "حركات المخزون", "stock movement history")
                    _uiState.update { current ->
                        if (current.selectedProductForDetail?.id != productId) {
                            current.copy(isLoadingProductHistory = false)
                        } else {
                            current.copy(
                                selectedProductHistory = null,
                                isLoadingProductHistory = false,
                                productHistoryError = safeMsg
                            )
                        }
                    }
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun loadInvoiceDetail(invoicePublicId: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (!ensureActiveScope()) {
            onComplete?.invoke(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.getSalesInvoiceDetail(invoicePublicId)
            withContext(Dispatchers.Main) {
                if (!ensureActiveScope()) {
                    onComplete?.invoke(false)
                    return@withContext
                }
                result.onSuccess { detail ->
                    _uiState.update { current ->
                        val currentActive = current.activeReceipt
                        if (currentActive == null || (currentActive.id != invoicePublicId && currentActive.serverInvoiceUlid != invoicePublicId)) {
                            current
                        } else {
                            val localMatch = current.invoices.firstOrNull { it.id == invoicePublicId || it.serverInvoiceUlid == invoicePublicId }
                            current.copy(
                                selectedInvoiceDetail = com.example.data.mobile.merchantread.MerchantReadAdapter.toLiveInvoiceRecord(detail, localMatch)
                            )
                        }
                    }
                    onComplete?.invoke(true)
                }.onFailure {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun refreshLiveMerchantReads() {
        if (!ensureActiveScope()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.refreshInvoices()
            repository.refreshHomeMetrics()
            repository.refreshShiftSummary()
            repository.refreshTreasuryReport()
        }
    }

    fun loadScreen(screen: TraderScreen, retry: Boolean = false) {
        if (!ensureActiveScope()) return
        val state = _uiState.value
        if (state.currentSession.isDemoSession) return
        val previous = state.readStatus[screen]
        if (previous != null && !(retry && previous == ReadStatus.FAILED)) return
        _uiState.update { it.copy(readStatus = it.readStatus + (screen to ReadStatus.LOADING)) }
        viewModelScope.launch {
            val result = when (screen) {
                TraderScreen.HOME -> repository.refreshHomeMetrics()
                TraderScreen.PRODUCTS_CATALOG -> repository.refreshProducts()
                TraderScreen.CUSTOMERS -> repository.refreshCustomers()
                TraderScreen.SUPPLIERS -> {
                    if (state.currentSession.canManageSuppliers()) {
                        repository.refreshSuppliers()
                    } else {
                        Result.success(Unit)
                    }
                }
                else -> error("Unsupported read screen")
            }
            if (!ensureActiveScope()) return@launch
            val status = if (result.isSuccess) ReadStatus.READY else ReadStatus.FAILED
            _uiState.update { it.copy(readStatus = it.readStatus + (screen to status)) }
        }
    }

    private fun acquireActionLock(): Boolean {
        var acquired = false
        _uiState.update { current ->
            if (current.isActionBusy) {
                acquired = false
                current
            } else {
                acquired = true
                current.copy(isActionBusy = true)
            }
        }
        return acquired
    }

    private fun releaseActionLock() {
        _uiState.update { it.copy(isActionBusy = false) }
    }

    fun updateProductPrice(
        productId: String,
        newPriceIls: Double,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        if (repository is OfflineDemoTraderRepository) {
            try {
                val session = _uiState.value.currentSession
                val result = kotlinx.coroutines.runBlocking { repository.updateProductPrice(productId, newPriceIls, session) }
                val success = if (result.isSuccess) {
                    syncFromRepository()
                    val formattedPrice = WesternFormatters.formatIls(newPriceIls)
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = "تم تحديث سعر البيع إلى $formattedPrice",
                            lastInventoryActionMessageEn = "Selling price updated to $formattedPrice",
                            isInventoryActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Price update rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = errAr,
                            lastInventoryActionMessageEn = errEn,
                            isInventoryActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val session = _uiState.value.currentSession
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.updateProductPrice(productId, newPriceIls, session)
                }
                val success = if (result.isSuccess) {
                    syncFromRepository()
                    val formattedPrice = WesternFormatters.formatIls(newPriceIls)
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = "تم تحديث سعر البيع إلى $formattedPrice",
                            lastInventoryActionMessageEn = "Selling price updated to $formattedPrice",
                            isInventoryActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Price update rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = errAr,
                            lastInventoryActionMessageEn = errEn,
                            isInventoryActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Price update failed"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    fun adjustProductStock(
        productId: String,
        adjustment: Int,
        reason: String,
        operationId: String = "",
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        val opId = if (operationId.isNotBlank()) operationId else _uiState.value.draftStockAdjustOperationId
        val session = _uiState.value.currentSession

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking { repository.adjustProductStock(productId, adjustment, reason, session, opId) }
                val success = if (result.isSuccess) {
                    val movement = result.getOrNull()
                    syncFromRepository()
                    val signedDelta = if (adjustment > 0) "+${WesternFormatters.formatInt(adjustment)}" else WesternFormatters.formatInt(adjustment)
                    val newStock = movement?.resultingStockExact ?: (_uiState.value.products.firstOrNull { it.id == productId }?.stockCountExact ?: BigDecimal.ZERO)
                    _uiState.update {
                        it.copy(
                            draftStockAdjustOperationId = repository.rotateDraftOperationId("inventory.adjustment"),
                            lastInventoryActionMessageAr = "تم تسجيل حركة المخزون ($signedDelta) — الرصيد الحالي: ${WesternFormatters.formatQuantity(newStock)}",
                            lastInventoryActionMessageEn = "Stock movement recorded ($signedDelta) — Current stock: ${WesternFormatters.formatQuantity(newStock)}",
                            isInventoryActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Stock adjustment rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = errAr,
                            lastInventoryActionMessageEn = errEn,
                            isInventoryActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.adjustProductStock(productId, adjustment, reason, session, opId)
                }
                val success = if (result.isSuccess) {
                    val movement = result.getOrNull()
                    syncFromRepository()
                    val signedDelta = if (adjustment > 0) "+${WesternFormatters.formatInt(adjustment)}" else WesternFormatters.formatInt(adjustment)
                    val newStock = movement?.resultingStockExact ?: (_uiState.value.products.firstOrNull { it.id == productId }?.stockCountExact ?: BigDecimal.ZERO)
                    _uiState.update {
                        it.copy(
                            draftStockAdjustOperationId = repository.rotateDraftOperationId("inventory.adjustment"),
                            lastInventoryActionMessageAr = "تم تسجيل حركة المخزون ($signedDelta) — الرصيد الحالي: ${WesternFormatters.formatQuantity(newStock)}",
                            lastInventoryActionMessageEn = "Stock movement recorded ($signedDelta) — Current stock: ${WesternFormatters.formatQuantity(newStock)}",
                            isInventoryActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Stock adjustment rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = errAr,
                            lastInventoryActionMessageEn = errEn,
                            isInventoryActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Stock adjustment failed"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    fun restockProduct(productId: String, amount: Int = 10) {
        adjustProductStock(productId, amount, "إعادة تزويد سريع للرفوف (+${amount})")
    }

    // POS Basket with Overselling Prevention Safeguard
    fun addProductToBasket(product: Product, count: Int = 1) {
        if (!ensureActiveScope()) return
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        addProductToBasket(product, BigDecimal(count))
    }

    fun addProductToBasket(product: Product, count: BigDecimal) {
        if (!ensureActiveScope()) return
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        if (count <= BigDecimal.ZERO) return
        _uiState.update { state ->
            val productInCatalog = state.products.firstOrNull { it.id == product.id } ?: product
            val existingIndex = state.basket.indexOfFirst { it.product.id == product.id }
            val currentQty = if (existingIndex >= 0) state.basket[existingIndex].quantity else BigDecimal.ZERO

            // If product is stock-tracked, enforce physical inventory cap
            if (productInCatalog.isStockTracked) {
                if (productInCatalog.stockCountExact <= BigDecimal.ZERO) {
                    return@update state.copy(
                        lastScannedBannerAr = "غير متوفر (نفد المخزون): ${productInCatalog.nameAr}",
                        lastScannedBannerEn = "Out of stock: ${productInCatalog.nameEn}",
                        isBannerError = true
                    )
                }
                if (currentQty >= productInCatalog.stockCountExact) {
                    return@update state.copy(
                        lastScannedBannerAr = "تم الوصول للحد الأقصى لمخزون: ${productInCatalog.nameAr} (${WesternFormatters.formatQuantity(productInCatalog.stockCountExact)})",
                        lastScannedBannerEn = "Maximum available stock reached for ${productInCatalog.nameEn} (${WesternFormatters.formatQuantity(productInCatalog.stockCountExact)})",
                        isBannerError = true
                    )
                }
                val allowedCount = (productInCatalog.stockCountExact - currentQty).coerceAtMost(count)
                if (allowedCount <= BigDecimal.ZERO) return@update state

                val updatedBasket = if (existingIndex >= 0) {
                    state.basket.mapIndexed { idx, item ->
                        if (idx == existingIndex) item.copy(quantity = item.quantity + allowedCount) else item
                    }
                } else {
                    state.basket + BasketItem(product = productInCatalog, quantity = allowedCount)
                }
                state.copy(basket = updatedBasket)
            } else {
                // Non-stock line (custom item): permissible addition without stock caps
                val updatedBasket = if (existingIndex >= 0) {
                    state.basket.mapIndexed { idx, item ->
                        if (idx == existingIndex) item.copy(quantity = item.quantity + count) else item
                    }
                } else {
                    state.basket + BasketItem(product = productInCatalog, quantity = count)
                }
                state.copy(basket = updatedBasket)
            }
        }
    }

    fun decrementProductInBasket(productId: String) {
        if (!ensureActiveScope()) return
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            val updatedBasket = state.basket.mapNotNull { item ->
                if (item.product.id == productId) {
                    if (item.quantity > BigDecimal.ONE) item.copy(quantity = item.quantity - BigDecimal.ONE) else null
                } else {
                    item
                }
            }
            state.copy(basket = updatedBasket)
        }
    }

    fun removeProductFromBasket(productId: String) {
        if (!ensureActiveScope()) return
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            state.copy(basket = state.basket.filterNot { it.product.id == productId })
        }
    }

    fun clearBasket() {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            state.copy(
                basket = emptyList(),
                currentSaleClientEventId = java.util.UUID.randomUUID().toString(),
                saleSessionId = state.saleSessionId + 1,
                discountPreset = DiscountPreset.NONE,
                customDiscountText = "",
                customerNote = "",
                selectedCustomerId = null,
                cashInputText = "",
                isSubmittingSale = false,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun openCustomItemDialog(open: Boolean) {
        _uiState.update { it.copy(isCustomItemDialogOpen = open) }
    }

    fun addCustomQuickItem(nameInput: String, priceInput: String) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        val sanitized = WesternFormatters.sanitizeWesternDigits(priceInput)
        val priceExact = try { BigDecimal(sanitized) } catch (_: Exception) { null } ?: return
        if (priceExact <= BigDecimal.ZERO) return
        val cleanName = nameInput.trim()
        val index = customItemCounter++
        val nameAr = cleanName.ifEmpty { "مادة سريعة #$index" }
        val nameEn = cleanName.ifEmpty { "Quick Line #$index" }
        // Truthful modeling: permissible non-stock line with 0 invented cost and 0 invented stock
        val customProduct = Product(
            id = "custom_${System.currentTimeMillis()}_$index",
            nameAr = nameAr,
            nameEn = nameEn,
            category = ProductCategory.PANTRY,
            barcode = String.format(java.util.Locale.US, "625999000%04d", index),
            priceExact = priceExact,
            costExact = BigDecimal.ZERO,
            unitAr = "مادة سريعة",
            unitEn = "Quick Line",
            stockCountExact = BigDecimal.ZERO,
            accentHex = 0xFF1D4E89,
            isStockTracked = false // Permissible non-stock line
        )
        val save: suspend () -> Unit = {
            val result = repository.addCustomProduct(customProduct)
            result.onSuccess { saved ->
                syncFromRepository()
                addProductToBasket(saved, 1)
                _uiState.update { it.copy(isCustomItemDialogOpen = false) }
            }.onFailure {
                showBanner("تعذر حفظ الصنف", "Could not save item", isError = true)
            }
        }
        if (repository is OfflineDemoTraderRepository) kotlinx.coroutines.runBlocking { save() }
        else viewModelScope.launch { save() }
    }

    // Barcode Scanner & Flashlight Hardware Control
    fun setScannerSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isScannerSheetOpen = open) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun updateManualBarcodeInput(input: String) {
        _uiState.update { it.copy(manualBarcodeInput = WesternFormatters.sanitizeWesternDigits(input)) }
    }

    fun simulateBarcodeScan(product: Product) {
        val currentState = _uiState.value
        val productInCatalog = currentState.products.firstOrNull { it.id == product.id } ?: product
        val currentQty = currentState.quantityInBasketExact(productInCatalog.id)

        if (productInCatalog.isStockTracked && productInCatalog.stockCountExact <= BigDecimal.ZERO) {
            showBanner(
                ar = "غير متوفر (نفد المخزون): ${productInCatalog.nameAr}",
                en = "Out of stock: ${productInCatalog.nameEn}",
                isError = true
            )
            return
        }

        if (productInCatalog.isStockTracked && currentQty >= productInCatalog.stockCountExact) {
            showBanner(
                ar = "تم الوصول للحد الأقصى لمخزون: ${productInCatalog.nameAr} (${WesternFormatters.formatQuantity(productInCatalog.stockCountExact)})",
                en = "Maximum available stock reached for ${productInCatalog.nameEn} (${WesternFormatters.formatQuantity(productInCatalog.stockCountExact)})",
                isError = true
            )
            return
        }

        addProductToBasket(productInCatalog, 1)
        val newQty = _uiState.value.quantityInBasket(productInCatalog.id)
        val priceFormatted = WesternFormatters.formatIls(productInCatalog.priceIls)
        if (newQty > 1) {
            showBanner(
                ar = "تم مسح (×${WesternFormatters.formatInt(newQty)} في السلة): ${productInCatalog.nameAr} ($priceFormatted)",
                en = "Scanned (×${WesternFormatters.formatInt(newQty)} in basket): ${productInCatalog.nameEn} ($priceFormatted)",
                isError = false
            )
        } else {
            showBanner(
                ar = "تم مسح: ${productInCatalog.nameAr} ($priceFormatted)",
                en = "Scanned: ${productInCatalog.nameEn} ($priceFormatted)",
                isError = false
            )
        }
        _uiState.update { it.copy(scanCountSession = it.scanCountSession + 1) }
    }

    fun simulateRandomBarcodeScan() {
        val state = _uiState.value
        val catalog = state.products
        if (catalog.isEmpty()) return
        val available = catalog.filter { !it.isStockTracked || state.quantityInBasketExact(it.id) < it.stockCountExact }
        val chosen = (available.ifEmpty { catalog }).random()
        simulateBarcodeScan(chosen)
    }

    fun submitManualBarcode(): Boolean {
        val code = WesternFormatters.sanitizeWesternDigits(_uiState.value.manualBarcodeInput.trim())
        if (code.isEmpty()) return false
        val matched = _uiState.value.products.firstOrNull {
            it.barcode == code || it.barcode.endsWith(code)
        }
        return if (matched != null) {
            simulateBarcodeScan(matched)
            _uiState.update { it.copy(manualBarcodeInput = "") }
            true
        } else {
            showBanner(
                ar = "لم يتم العثور على باركود: $code",
                en = "Barcode not found: $code",
                isError = true
            )
            false
        }
    }

    fun selectSaleLineTax(productId: String, taxId: String?) {
        val production = productionRepository ?: return
        val tax = taxId?.let { id -> production.bootstrapTaxes.singleOrNull { it.id.value == id } ?: return }
        _uiState.update { state ->
            if (state.isPaymentUnverifiedHold || state.isSubmittingSale) return@update state
            state.copy(
                basket = state.basket.map { item ->
                    if (item.product.id != productId) item else item.copy(
                        taxRateId = tax?.id?.value,
                        taxRateExact = tax?.rate?.value ?: BigDecimal.ZERO,
                        isTaxInclusive = tax?.calculation == "inclusive"
                    )
                },
                cashInputText = ""
            )
        }
    }

    // Discounts & Customer Notes
    fun selectDiscountPreset(preset: DiscountPreset) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            state.copy(
                discountPreset = preset,
                customDiscountText = if (preset != DiscountPreset.CUSTOM_ILS) "" else state.customDiscountText,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCustomDiscountText(text: String) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        val sanitized = WesternFormatters.sanitizeWesternDigits(text)
        _uiState.update {
            it.copy(
                discountPreset = DiscountPreset.CUSTOM_ILS,
                customDiscountText = sanitized,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCustomerNote(note: String) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { it.copy(customerNote = note) }
    }

    fun assignCustomerToSale(customer: Customer?) {
        if (!ensureActiveScope()) return
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            val currentNote = state.customerNote.trim()
            val wasPreviousCustomerName = state.customers.any {
                it.nameAr == currentNote || it.nameEn == currentNote
            }
            val updatedNote = if (wasPreviousCustomerName) "" else state.customerNote
            val updatedCustomers = if (customer != null && state.customers.none { it.id == customer.id }) {
                state.customers + customer
            } else {
                state.customers
            }
            state.copy(
                customers = updatedCustomers,
                selectedCustomerId = customer?.id,
                customerNote = updatedNote,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
        if (customer != null && !customer.isDetailLoaded && repository !is OfflineDemoTraderRepository) {
            viewModelScope.launch {
                try {
                    repository.loadCustomerDetail(customer.id)
                } catch (_: Exception) {}
            }
        }
    }

    fun dismissCheckoutError() {
        _uiState.update {
            it.copy(
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    // Cash Payment & Native / Quick Tender Entry
    private fun exactCashEntry(state: TraderUiState): String? =
        PaymentDecimalConsistency.cashEntryText(
            state.basketNetTotalExact, state.businessInfo.defaultCurrencyMinorUnits
        )

    fun setCashExactAmount() {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            val entry = exactCashEntry(state)
            if (entry == null) {
                val exactTotal = state.basketNetTotalExact.toPlainString()
                val ar = "المبلغ ($exactTotal) يحتوي على كسور تتجاوز دقة النقد بهذه العملة — اختر البيع الآجل أو عدّل السلة"
                val en = "Amount ($exactTotal) exceeds the cash decimal limit for this currency — select credit sale or adjust the basket"
                state.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            } else {
                state.copy(
                    cashInputText = entry,
                    checkoutErrorAr = null,
                    checkoutErrorEn = null
                )
            }
        }
    }

    fun setCashQuickDenomination(amountIls: Int) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            state.copy(
                cashInputText = amountIls.toString(),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun addCashIncrement(deltaIls: Int) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            val next = state.cashTenderedExact + BigDecimal.valueOf(deltaIls.toLong())
            state.copy(
                cashInputText = next.stripTrailingZeros().toPlainString(),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCashInputText(rawInput: String) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        val filtered = WesternFormatters.sanitizeWesternDigits(rawInput)
        if (!Regex("[0-9]{0,14}(\\.[0-9]{0,6})?").matches(filtered)) return
        _uiState.update { state ->
            state.copy(
                cashInputText = filtered,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun onCashKeypadPress(key: String) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        _uiState.update { state ->
            val current = state.cashInputText
            val updated = when (key) {
                "C" -> ""
                "BACK" -> if (current.isNotEmpty()) current.dropLast(1) else ""
                "." -> {
                    if (current.contains(".")) current
                    else if (current.isEmpty()) "0."
                    else "$current."
                }
                else -> {
                    val base = if (current == "0" || current == exactCashEntry(state)) {
                        ""
                    } else {
                        current
                    }
                    val candidate = base + key
                    val parts = candidate.split(".")
                    if (parts.size == 2 && parts[1].length > 2) current
                    else if (candidate.length > 8) current
                    else candidate
                }
            }
            state.copy(
                cashInputText = updated,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    private fun isInvoiceCanonicallyConfirmed(
        invoice: InvoiceRecord,
        expectedClientEventId: String?
    ): Boolean {
        if (expectedClientEventId.isNullOrBlank() || invoice.clientEventId != expectedClientEventId) {
            return false
        }
        if (repository is OfflineDemoTraderRepository) {
            return invoice.outboxStatus != "PENDING_SYNC"
        }
        val status = invoice.outboxStatus ?: return false
        if (!status.equals("CONFIRMED", ignoreCase = true) &&
            !status.equals(com.example.data.mobile.outbox.OutboxStatus.CONFIRMED.wireValue, ignoreCase = true)
        ) {
            return false
        }
        if (invoice.serverInvoiceUlid.isNullOrBlank()) {
            return false
        }
        val isPureCredit = !invoice.isPaidInFull && invoice.cashReceivedExact.compareTo(BigDecimal.ZERO) == 0
        if (!isPureCredit && invoice.serverPaymentUlid.isNullOrBlank()) {
            return false
        }
        return true
    }

    // Final Sale Posting via Repository
    fun completeCashSale(asCreditDebt: Boolean = false) {
        if (!ensureActiveScope()) return
        val state = _uiState.value
        if (state.basket.isEmpty() || state.isSubmittingSale || state.isActionBusy) return
        val currency = state.businessInfo.defaultCurrency
        if (state.isPaymentUnverifiedHold) {
            val ar = "العملية قيد التحقق — يرجى الانتظار لتأكيد الدفع"
            val en = "Payment pending verification — awaiting confirmation"
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        if (state.hasUnresolvedSaleTax) {
            showBanner("اختر ضريبة كل صنف", "Select a tax treatment for each item", isError = true)
            return
        }

        // Safeguard 0: Rejection of unsupported nonzero discounts in authenticated production V2
        if (state.hasUnsupportedProductionDiscount) {
            val ar = "الخصومات غير مدعومة في إصدار نقطة البيع الحالي — يرجى إزالة الخصم للمتابعة"
            val en = "Discounts are not supported in the current POS version — please remove the discount to proceed"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        // Safeguard 0b: Block unrepresentable collection before tender with clear message
        if (!asCreditDebt && state.isCashCollectionUnrepresentable) {
            val exactTotal = state.basketNetTotalExact.toPlainString()
            val ar = "المبلغ ($exactTotal) يحتوي على كسور تتجاوز دقة النقد بهذه العملة — اختر البيع الآجل أو عدّل السلة"
            val en = "Amount ($exactTotal) exceeds the cash decimal limit for this currency — select credit sale or adjust the basket"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        // Safeguard 1: Cash sale requires sufficient cash tendered (no partial cash accepted)
        if (!asCreditDebt && !state.isCashSufficient) {
            val shortfall = WesternFormatters.formatCurrency(state.remainingUnpaidExact, currency)
            val totalFormatted = WesternFormatters.formatCurrency(state.basketNetTotalExact, currency)
            val ar = "النقد المستلم ناقص بمقدار $shortfall — استلم كامل المبلغ ($totalFormatted) أو اختر الترحيل الآجل لزبون مسجل"
            val en = "Cash received is short by $shortfall — collect full $totalFormatted or post on a customer account"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        // Safeguard 2: Credit sale requires a registered customer account
        if (asCreditDebt && state.selectedCustomerId == null) {
            val ar = "البيع على الحساب يتطلب تحديد حساب زبون مسجل أولاً"
            val en = "Credit sale requires selecting a registered customer account first"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        // Safeguard 3: Credit sale blocked if customer has a configured credit limit but balance is unknown
        if (asCreditDebt && state.selectedCustomer?.creditLimitExact != null && !state.isCustomerBalanceKnown) {
            val ar = "البيع الآجل غير متاح لأن رصيد الزبون الحالي غير معروف للتحقق من سقف الائتمان"
            val en = "Credit sale blocked because customer balance is unknown to verify credit limit"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        // Safeguard 4: Credit sale blocked if credit would exceed limit
        if (asCreditDebt && state.wouldCreditExceedCustomerLimit) {
            val excess = WesternFormatters.formatCurrency(state.creditCeilingExcessExact, currency)
            val ar = "متوقف لتجاوز سقف الدين بمقدار $excess"
            val en = "Credit blocked: Over limit by $excess"
            _uiState.update {
                it.copy(
                    checkoutErrorAr = ar,
                    checkoutErrorEn = en
                )
            }
            showBanner(ar = ar, en = en, isError = true)
            return
        }

        val tenderedExact: BigDecimal
        val changeExact: BigDecimal
        val remainingDebtExact: BigDecimal
        val isPaidInFull: Boolean

        if (asCreditDebt) {
            // Truthful full-credit sale: 0.00 cash into drawer, 100% of netTotal recorded as customer debt
            tenderedExact = BigDecimal.ZERO
            changeExact = BigDecimal.ZERO
            remainingDebtExact = state.basketNetTotalExact
            isPaidInFull = (remainingDebtExact.compareTo(BigDecimal.ZERO) == 0)
        } else {
            tenderedExact = state.cashTenderedExact
            changeExact = state.changeDueExact
            remainingDebtExact = BigDecimal.ZERO
            isPaidInFull = true
        }

        val nextNumber = (state.invoices.maxOfOrNull { it.invoiceNumber } ?: 1040) + 1
        val timestamp = WesternFormatters.currentTimestamp()
        val linkedCustomer = state.selectedCustomer
        val noteTrimmed = state.customerNote.trim()
        val customerName = when {
            linkedCustomer != null && noteTrimmed.isNotEmpty() &&
                noteTrimmed != linkedCustomer.nameAr && noteTrimmed != linkedCustomer.nameEn ->
                "${linkedCustomer.name(state.language)} • $noteTrimmed"
            linkedCustomer != null ->
                linkedCustomer.name(state.language)
            noteTrimmed.isNotEmpty() ->
                noteTrimmed
            else ->
                state.language.tr("زبون نقدي", "Walk-in Customer")
        }

        val currencyMinorUnits = state.businessInfo.defaultCurrencyMinorUnits.coerceIn(0, 6)
        val taxExact = state.basket.fold(java.math.BigDecimal.ZERO) { acc, item ->
            acc.add(
                if (item.hasResolvedTax) {
                    item.calculateLine(currencyMinorUnits).taxAmount.value
                } else {
                    java.math.BigDecimal.ZERO
                }
            )
        }

        val newInvoice = InvoiceRecord(
            id = "inv_$nextNumber",
            invoiceNumber = nextNumber,
            timestamp = timestamp,
            items = state.basket,
            subtotalExact = state.basketSubtotalExact,
            discountExact = state.calculatedDiscountExact,
            netTotalExact = state.basketNetTotalExact,
            cashReceivedExact = tenderedExact,
            changeReturnedExact = changeExact,
            customerId = state.selectedCustomerId,
            customerNote = customerName,
            cashierNameAr = state.currentSession.name,
            cashierNameEn = state.currentSession.name,
            isPaidInFull = isPaidInFull,
            remainingDebitExact = remainingDebtExact,
            printCount = 0,
            clientEventId = state.currentSaleClientEventId,
            currency = state.businessInfo.defaultCurrency,
            currencyMinorUnits = state.businessInfo.defaultCurrencyMinorUnits,
            taxExact = taxExact
        )

        if (!_uiState.compareAndSet(state, state.copy(isSubmittingSale = true, isActionBusy = true))) return
        val complete: suspend () -> Unit = complete@{
            try {
                // Authoritative posting through the repository contract
                val result = repository.recordSale(newInvoice)
                if (result.isSuccess) {
                    val recorded = result.getOrNull()
                    if (recorded != null && isInvoiceCanonicallyConfirmed(recorded, state.currentSaleClientEventId)) {
                        showConfirmedSale(recorded)
                    } else if (recorded != null && recorded.localReceiptNumber != null) {
                        showOfflineCompletedSale(recorded)
                    } else {
                        showHeldSale(recorded ?: newInvoice.copy(outboxStatus = "PENDING_SYNC"))
                        showBanner(
                            ar = "العملية قيد التحقق — يرجى الانتظار لتأكيد الدفع",
                            en = "Payment pending verification — awaiting confirmation",
                            isError = false
                        )
                    }
                } else {
                    if (recoverSales != null && restoreSaleHold(recoverSales, keepDraftWhenEmpty = true)) return@complete
                    val rawErr = result.exceptionOrNull()?.message ?: "Sale rejected by domain engine"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            isSubmittingSale = false,
                            isActionBusy = false,
                            checkoutErrorAr = errAr,
                            checkoutErrorEn = errEn
                        )
                    }
                    showBanner(ar = errAr, en = errEn, isError = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (recoverSales != null && restoreSaleHold(recoverSales, keepDraftWhenEmpty = true)) return@complete
                val msg = e.message ?: "Sale failed"
                _uiState.update {
                    it.copy(
                        isSubmittingSale = false,
                        isActionBusy = false,
                        checkoutErrorAr = msg,
                        checkoutErrorEn = msg
                    )
                }
                showBanner(ar = msg, en = msg, isError = true)
            }
        }
        if (repository is OfflineDemoTraderRepository) kotlinx.coroutines.runBlocking { complete() }
        else viewModelScope.launch { complete() }
    }

    private fun showHeldSale(invoice: InvoiceRecord?) {
        _uiState.update { current ->
            current.copy(
                activeReceipt = invoice,
                isPaymentUnverifiedHold = true,
                isNewlyCompletedSale = false,
                basket = invoice?.items ?: current.basket,
                currentSaleClientEventId = invoice?.clientEventId ?: current.currentSaleClientEventId,
                selectedCustomerId = invoice?.customerId,
                currentScreen = TraderScreen.CASH_PAYMENT,
                isSubmittingSale = false,
                isActionBusy = false,
                checkoutErrorAr = "تعذر تأكيد حالة البيع. تحقق من حالة الدفع.",
                checkoutErrorEn = "Sale confirmation is unavailable. Check payment status."
            )
        }
    }

    private suspend fun restoreSaleHold(
        production: ProductionTraderRepository,
        keepDraftWhenEmpty: Boolean = false
    ): Boolean {
        val restored = production.restoreHeldSale()
        val invoice = restored.getOrNull()
        if (invoice != null || restored.isFailure) {
            showHeldSale(invoice)
            return true
        }
        _uiState.update {
            it.copy(
                isPaymentUnverifiedHold = false,
                isSubmittingSale = if (keepDraftWhenEmpty) it.isSubmittingSale else false,
                isActionBusy = if (keepDraftWhenEmpty) it.isActionBusy else false,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
        return false
    }

    fun checkHeldSaleStatus() {
        val state = _uiState.value
        if (!state.isPaymentUnverifiedHold || state.isSubmittingSale) return
        val receipt = state.activeReceipt
        if (!_uiState.compareAndSet(state, state.copy(isSubmittingSale = true))) return
        viewModelScope.launch {
            if (receipt == null) {
                if (recoverSales != null) restoreSaleHold(recoverSales)
                else _uiState.update { it.copy(isSubmittingSale = false) }
                return@launch
            }
            val eventId = receipt.clientEventId
            val checked = if (recoverSales != null) {
                eventId?.let { recoverSales.checkHeldSaleStatus(it).getOrNull() }
            } else repository.getInvoices().firstOrNull { isInvoiceCanonicallyConfirmed(it, eventId) }
            if (checked == null || !isInvoiceCanonicallyConfirmed(checked, eventId)) {
                showHeldSale(checked ?: receipt)
                return@launch
            }

            showConfirmedSale(checked)
        }
    }

    private fun showConfirmedSale(receipt: InvoiceRecord) {
        syncFromRepository()
        _uiState.update { current ->
            val printer = current.connectedPrinter
            val ready = printer != null && printer.isPaired
            current.copy(
                activeReceipt = receipt,
                selectedInvoiceDetail = null,
                isNewlyCompletedSale = true,
                isPaymentUnverifiedHold = false,
                receiptShareCount = 0,
                receiptCopyCount = 0,
                lastSharedReceiptText = null,
                basket = emptyList(),
                currentSaleClientEventId = java.util.UUID.randomUUID().toString(),
                saleSessionId = current.saleSessionId + 1,
                discountPreset = DiscountPreset.NONE,
                customDiscountText = "",
                customerNote = "",
                selectedCustomerId = null,
                cashInputText = "",
                isSubmittingSale = false,
                isActionBusy = false,
                checkoutErrorAr = null,
                checkoutErrorEn = null,
                currentScreen = TraderScreen.RECEIPT,
                screenHistory = listOf(TraderScreen.HOME),
                printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                printProgressFraction = 0f,
                printProgressStepAr = if (ready) {
                    "وضع المحاكاة: جاهز لمعاينة الطباعة عبر ${printer!!.name} (${current.receiptPaperWidthMm}mm)"
                } else {
                    "وضع المحاكاة: لا توجد طابعة متصلة — اختر طابعة أو شارك النص"
                },
                printProgressStepEn = if (ready) {
                    "Simulation mode: Ready for print preview via ${printer!!.name} (${current.receiptPaperWidthMm}mm)"
                } else {
                    "Simulation mode: No printer connected — select a printer or share text"
                }
            )
        }
    }

    fun openInvoiceReceipt(invoice: InvoiceRecord) {
        if (_uiState.value.isPaymentUnverifiedHold || _uiState.value.isSubmittingSale) return
        if (repository !is OfflineDemoTraderRepository) invoice.serverInvoiceUlid?.let { loadInvoiceDetail(it) }
        _uiState.update { state ->
            val printer = state.connectedPrinter
            val ready = printer != null && printer.isPaired
            state.copy(
                activeReceipt = invoice,
                selectedInvoiceDetail = null,
                isNewlyCompletedSale = false,
                receiptShareCount = 0,
                receiptCopyCount = 0,
                lastSharedReceiptText = null,
                currentScreen = TraderScreen.RECEIPT,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8),
                printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                printProgressFraction = 0f,
                printProgressStepAr = if (ready) {
                    "وضع المحاكاة: جاهز لمعاينة الطباعة عبر ${printer!!.name} (${state.receiptPaperWidthMm}mm)"
                } else {
                    "وضع المحاكاة: لا توجد طابعة متصلة — اختر طابعة أو شارك النص"
                },
                printProgressStepEn = if (ready) {
                    "Simulation mode: Ready for print preview via ${printer!!.name} (${state.receiptPaperWidthMm}mm)"
                } else {
                    "Simulation mode: No printer connected — select a printer or share text"
                }
            )
        }
    }

    private fun showOfflineCompletedSale(receipt: InvoiceRecord) {
        syncFromRepository()
        _uiState.update { current ->
            val printer = current.connectedPrinter
            val ready = printer != null && printer.isPaired
            current.copy(
                activeReceipt = receipt,
                selectedInvoiceDetail = null,
                isNewlyCompletedSale = true,
                isPaymentUnverifiedHold = false,
                receiptShareCount = 0,
                receiptCopyCount = 0,
                lastSharedReceiptText = null,
                basket = emptyList(),
                currentSaleClientEventId = java.util.UUID.randomUUID().toString(),
                saleSessionId = current.saleSessionId + 1,
                discountPreset = DiscountPreset.NONE,
                customDiscountText = "",
                customerNote = "",
                selectedCustomerId = null,
                cashInputText = "",
                isSubmittingSale = false,
                isActionBusy = false,
                checkoutErrorAr = null,
                checkoutErrorEn = null,
                currentScreen = TraderScreen.RECEIPT,
                screenHistory = listOf(TraderScreen.HOME),
                printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                printProgressFraction = 0f,
                printProgressStepAr = if (ready) {
                    "وضع المحاكاة: جاهز لمعاينة الطباعة عبر ${printer!!.name} (${current.receiptPaperWidthMm}mm)"
                } else {
                    "وضع المحاكاة: لا توجد طابعة متصلة — اختر طابعة أو شارك النص"
                },
                printProgressStepEn = if (ready) {
                    "Simulation mode: Ready for print preview via ${printer!!.name} (${current.receiptPaperWidthMm}mm)"
                } else {
                    "Simulation mode: No printer connected — select a printer or share text"
                }
            )
        }
        showBanner(
            ar = "تم تسجيل البيع محلياً برقم إيصال مؤقت (${receipt.localReceiptNumber})",
            en = "Sale recorded locally with pending receipt (${receipt.localReceiptNumber})",
            isError = false
        )
    }

    fun setOfflineSalesCapabilityEnabled(enabled: Boolean) {
        if (repository is ProductionTraderRepository) {
            repository.isOfflineSalesCapabilityEnabled = enabled
        }
        _uiState.update { it.copy(isOfflineSalesCapabilityEnabled = enabled) }
        refreshOfflineLeaseState()
    }

    fun setNetworkOffline(isOffline: Boolean) {
        if (repository is ProductionTraderRepository) {
            repository.isNetworkOffline = isOffline
        }
        _uiState.update { it.copy(isNetworkOffline = isOffline) }
    }

    fun refreshOfflineLeaseState() {
        if (repository !is ProductionTraderRepository) return
        viewModelScope.launch {
            val summary = repository.offlineQueueSummary()
            val lease = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.getActiveOfflineSaleLease() }
            _uiState.update { current -> current.copy(
                isOfflineSalesCapabilityEnabled = repository.isOfflineSalesCapabilityEnabled,
                isNetworkOffline = repository.isNetworkOffline,
                activeOfflineSaleLease = lease,
                pendingOfflineSalesCount = summary.count,
                pendingOfflineSalesTotalExact = summary.total,
                rejectedCashSales = summary.rejected
            ) }
        }
    }

    fun syncOfflineSales() {
        if (repository !is ProductionTraderRepository) return
        viewModelScope.launch {
            _uiState.update { it.copy(isOfflineSyncing = true) }
            val res = repository.syncOfflineSales()
            _uiState.update { it.copy(isOfflineSyncing = false, offlineSyncSummary = res.getOrNull()?.summary) }
            refreshOfflineLeaseState()
        }
    }

    fun startFreshSale() {
        val state = _uiState.value
        if (state.isSubmittingSale && !state.isPaymentUnverifiedHold) return
        if (state.isPaymentUnverifiedHold) {
            navigateTo(TraderScreen.NEW_SALE)
            return
        }
        clearBasket()
        _uiState.update { state ->
            state.copy(
                currentScreen = TraderScreen.NEW_SALE,
                screenHistory = listOf(TraderScreen.HOME)
            )
        }
    }

    // Customer Ledger & Debt Collections
    fun selectCustomerForDetail(customer: Customer) {
        if (!ensureActiveScope()) return
        val freshCustomer = _uiState.value.customers.firstOrNull { it.id == customer.id } ?: customer
        val isLive = repository !is OfflineDemoTraderRepository
        customerDetailJob?.cancel()
        customerStatementJob?.cancel()
        val detailGen = ++customerDetailGeneration
        _uiState.update { state ->
            state.copy(
                selectedCustomerForDetail = freshCustomer,
                selectedCustomerStatement = null,
                isLoadingCustomerStatement = isLive,
                customerStatementError = null,
                lastCustomerPaymentMessageAr = null,
                lastCustomerPaymentMessageEn = null,
                currentScreen = TraderScreen.CUSTOMER_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
        if (isLive) {
            loadCustomerStatement(freshCustomer.id)
            customerDetailJob = viewModelScope.launch {
                try {
                    val detailRes = repository.getCustomerDetail(freshCustomer.id)
                    if (!ensureActiveScope()) return@launch
                    if (detailGen == customerDetailGeneration && detailRes.isSuccess) {
                        val detail = detailRes.getOrNull()
                        if (detail != null) {
                            _uiState.update { current ->
                                if (current.selectedCustomerForDetail?.id == freshCustomer.id) {
                                    current.copy(selectedCustomerForDetail = detail)
                                } else current
                            }
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    if (!repository.isScopeActive()) ensureActiveScope()
                }
            }
        }
    }

    fun openPaymentCollectionDialog(open: Boolean) {
        if (!open) {
            resetCustomerSettlementTargets()
            _uiState.update {
                it.copy(
                    isPaymentCollectionDialogOpen = false,
                    customerPaymentError = null
                )
            }
            return
        }
        if (!ensureActiveScope()) return
        if (!MerchantAction.COLLECT.allowed(_uiState.value.currentSession)) return
        val customer = _uiState.value.selectedCustomerForDetail
        if (customer != null) {
            loadCustomerSettlementTargets(customer.id, resetPagination = true)
        }
        val draftId = repository.getDraftOperationId("customer_payment")
        val draftPayload = repository.getActiveDraftPayload("customer_payment")
        val recovering = repository.isDraftRecovering("customer_payment")
        _uiState.update {
            it.copy(
                isPaymentCollectionDialogOpen = true,
                draftCustomerPaymentOperationId = draftId,
                activeCustomerPaymentDraft = draftPayload,
                isCustomerPaymentRecovering = recovering,
                customerPaymentError = null
            )
        }
    }

    fun openPaymentCollectionForCustomer(customer: Customer) {
        if (!ensureActiveScope()) return
        if (!MerchantAction.COLLECT.allowed(_uiState.value.currentSession)) return
        loadCustomerSettlementTargets(customer.id, resetPagination = true)
        val draftId = repository.getDraftOperationId("customer_payment")
        val draftPayload = repository.getActiveDraftPayload("customer_payment")
        val recovering = repository.isDraftRecovering("customer_payment")
        _uiState.update { state ->
            val freshCustomer = state.customers.firstOrNull { it.id == customer.id } ?: customer
            state.copy(
                selectedCustomerForDetail = freshCustomer,
                isPaymentCollectionDialogOpen = true,
                draftCustomerPaymentOperationId = draftId,
                activeCustomerPaymentDraft = draftPayload,
                isCustomerPaymentRecovering = recovering,
                customerPaymentError = null
            )
        }
    }

    fun dismissCustomerFeedback() {
        _uiState.update {
            it.copy(
                lastCustomerPaymentMessageAr = null,
                lastCustomerPaymentMessageEn = null
            )
        }
    }

    fun confirmCustomerCollection(
        customerId: String,
        amount: BigDecimal,
        note: String,
        paymentId: String,
        allocations: List<PaymentAllocationInput> = emptyList(),
        onComplete: (Boolean) -> Unit = {}
    ): Boolean = confirmCustomerCollectionResult(
        customerId = customerId,
        amount = amount,
        note = note,
        paymentId = paymentId,
        allocations = allocations
    ) { res ->
        onComplete(res.isSuccess)
    }

    fun confirmCustomerCollectionResult(
        customerId: String,
        amount: BigDecimal,
        note: String,
        paymentId: String,
        allocations: List<PaymentAllocationInput> = emptyList(),
        onComplete: (Result<CustomerTransaction>) -> Unit = {}
    ): Boolean {
        if (!ensureActiveScope() || !MerchantAction.COLLECT.allowed(_uiState.value.currentSession)) {
            onComplete(Result.failure(SecurityException("Unauthorized or inactive session")))
            return false
        }
        return recordCustomerPayment(
            customerId = customerId,
            amount = amount,
            note = note,
            paymentId = paymentId,
            cashAccountId = null,
            paymentDate = null,
            allocations = allocations
        ) { res ->
            if (res.isSuccess) {
                navigateTo(TraderScreen.CUSTOMER_DETAIL)
                if (repository !is OfflineDemoTraderRepository && !_uiState.value.currentSession.isDemoSession) {
                    loadCustomerStatement(customerId)
                }
            }
            onComplete(res)
        }
    }

    fun confirmCustomerCollection(
        customerId: String,
        paymentAmountIls: Double,
        note: String,
        paymentId: String,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean = confirmCustomerCollection(
        customerId = customerId,
        amount = BigDecimal(paymentAmountIls.toString()),
        note = note,
        paymentId = paymentId,
        allocations = emptyList(),
        onComplete = onComplete
    )

    fun recordCustomerPayment(
        customerId: String,
        paymentAmountIls: Double,
        note: String,
        paymentId: String = "",
        onComplete: (Boolean) -> Unit = {}
    ): Boolean = recordCustomerPayment(
        customerId = customerId,
        amount = BigDecimal(paymentAmountIls.toString()),
        note = note,
        paymentId = paymentId,
        cashAccountId = null,
        paymentDate = null,
        allocations = emptyList()
    ) { res ->
        onComplete(res.isSuccess)
    }

    fun recordCustomerPayment(
        customerId: String,
        amount: BigDecimal,
        note: String,
        paymentId: String = "",
        cashAccountId: String? = null,
        paymentDate: String? = null,
        allocations: List<PaymentAllocationInput> = emptyList(),
        onComplete: (Result<CustomerTransaction>) -> Unit = {}
    ): Boolean {
        if (repository is ProductionTraderRepository && !ensureActiveScope()) {
            val err = Result.failure<CustomerTransaction>(SecurityException("Authenticated scope is inactive or revoked"))
            onComplete(err)
            return false
        }
        if (!acquireActionLock()) {
            val err = Result.failure<CustomerTransaction>(IllegalStateException("Action in progress"))
            onComplete(err)
            return false
        }
        val pid = if (repository is ProductionTraderRepository) {
            val persistedDraftId = repository.getDraftOperationId("customer_payment")
            if (paymentId.isNotBlank() && paymentId != persistedDraftId) {
                releaseActionLock()
                val currentLang = _uiState.value.language
                val errEn = "Payment operation ID mismatch with persisted draft"
                val errAr = "تعارض معرّف العملية مع المسودة المحفوظة"
                val localized = currentLang.tr(errAr, errEn)
                _uiState.update {
                    it.copy(
                        draftCustomerPaymentOperationId = persistedDraftId,
                        activeCustomerPaymentDraft = repository.getActiveDraftPayload("customer_payment"),
                        isCustomerPaymentRecovering = repository.isDraftRecovering("customer_payment"),
                        customerPaymentError = localized
                    )
                }
                showBanner(errAr, errEn, isError = true)
                onComplete(Result.failure(IllegalStateException(errEn)))
                return false
            }
            persistedDraftId
        } else {
            if (paymentId.isNotBlank()) paymentId else _uiState.value.draftCustomerPaymentOperationId
        }
        val currencyMinorUnits = _uiState.value.businessInfo.defaultCurrencyMinorUnits
        val amountExact = try {
            amount.setScale(currencyMinorUnits, RoundingMode.UNNECESSARY)
        } catch (e: ArithmeticException) {
            releaseActionLock()
            val errAr = "دقة المبلغ تتجاوز الحد المسموح ($currencyMinorUnits خانات)"
            val errEn = "Amount precision exceeds allowed limit ($currencyMinorUnits decimals)"
            val localized = _uiState.value.language.tr(errAr, errEn)
            _uiState.update { it.copy(customerPaymentError = localized) }
            showBanner(errAr, errEn, isError = true)
            val err = Result.failure<CustomerTransaction>(IllegalArgumentException(errEn))
            onComplete(err)
            return false
        }

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.recordCustomerPayment(
                        customerId = customerId,
                        amount = amountExact,
                        note = note,
                        paymentId = pid,
                        cashAccountId = cashAccountId,
                        paymentDate = paymentDate,
                        allocations = allocations
                    )
                }
                if (result.isSuccess) {
                    val tx = result.getOrNull()
                    syncCustomers()
                    _uiState.update { state ->
                        val currency = state.businessInfo.defaultCurrency
                        val inDirectory = state.customers.firstOrNull { it.id == customerId }
                        val fallbackCust = state.selectedCustomerForDetail?.takeIf { it.id == customerId }
                            ?: state.customerSearchResults?.firstOrNull { it.id == customerId }
                        val updatedCust = (inDirectory ?: fallbackCust)?.let { cust ->
                            if (tx?.runningBalanceExact != null) cust.copy(balanceExact = tx.runningBalanceExact) else cust
                        }
                        val lang = state.language
                        val paidStr = WesternFormatters.formatHumanMoney2dp(amountExact, currency, lang)
                        val balExact = tx?.runningBalanceExact ?: updatedCust?.balanceExact
                        val isBalKnown = balExact != null
                        val hasAllocs = allocations.isNotEmpty()
                        val totalAllocExact = allocations.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.amountExact) }
                        val allocStr = WesternFormatters.formatHumanMoney2dp(totalAllocExact, currency, lang)

                        val msgAr = when {
                            !hasAllocs -> {
                                when {
                                    balExact == null -> "تم قبض $paidStr نقداً (دفعة مقدمة غير مخصصة) — الرصيد الحالي غير متاح (يتطلب تحديث كشف الحساب)"
                                    balExact > BigDecimal.ZERO -> "تم قبض $paidStr نقداً (دفعة غير مخصصة) — الرصيد الدفتري المتبقي: ${WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)}"
                                    balExact < BigDecimal.ZERO -> "تم قبض $paidStr نقداً — رصيد الزبون المقدم (دفعة غير مخصصة): ${WesternFormatters.formatHumanMoney2dp(balExact.negate(), currency, lang)}"
                                    else -> "تم قبض $paidStr نقداً (دفعة غير مخصصة) — الرصيد الدفتري: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                                }
                            }
                            else -> {
                                val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                                val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                                val balDisplay = if (balExact == null) "غير متاح" else if (balExact < BigDecimal.ZERO) "رصيد مقدم ${WesternFormatters.formatHumanMoney2dp(balExact.abs(), currency, lang)}" else WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)
                                if (unallocExact > BigDecimal.ZERO) {
                                    "تم قبض $paidStr نقداً (مخصص: $allocStr، دفعة مقدمة غير مخصصة: $unallocStr) — الرصيد الدفتري: $balDisplay"
                                } else {
                                    "تم قبض $paidStr نقداً وتخصيصها للذمم المحددة — الرصيد الدفتري المتبقي: $balDisplay"
                                }
                            }
                        }

                        val msgEn = when {
                            !hasAllocs -> {
                                when {
                                    balExact == null -> "Collected $paidStr in cash (unapplied advance) — Current balance unavailable (statement refresh required)"
                                    balExact > BigDecimal.ZERO -> "Collected $paidStr in cash (unapplied advance) — Remaining ledger balance: ${WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)}"
                                    balExact < BigDecimal.ZERO -> "Collected $paidStr in cash — Customer advance credit: ${WesternFormatters.formatHumanMoney2dp(balExact.negate(), currency, lang)}"
                                    else -> "Collected $paidStr in cash (unapplied advance) — Ledger balance: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                                }
                            }
                            else -> {
                                val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                                val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                                val balDisplay = if (!isBalKnown) "unavailable" else if (balExact!! < BigDecimal.ZERO) "advance credit ${WesternFormatters.formatHumanMoney2dp(balExact.abs(), currency, lang)}" else WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)
                                if (unallocExact > BigDecimal.ZERO) {
                                    "Collected $paidStr in cash (Allocated: $allocStr, Unapplied advance: $unallocStr) — Ledger balance: $balDisplay"
                                } else {
                                    "Collected $paidStr in cash applied to selected targets — Remaining ledger balance: $balDisplay"
                                }
                            }
                        }

                        state.copy(
                            draftCustomerPaymentOperationId = repository.rotateDraftOperationId("customer_payment"),
                            isPaymentCollectionDialogOpen = false,
                            isCustomerPaymentSubmitting = false,
                            customerPaymentError = null,
                            selectedCustomerForDetail = updatedCust,
                            lastCustomerPaymentMessageAr = msgAr,
                            lastCustomerPaymentMessageEn = msgEn
                        )
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Payment rejected"
                    _uiState.update { it.copy(isCustomerPaymentSubmitting = false, customerPaymentError = err) }
                    showBanner(err, err, isError = true)
                }
                onComplete(result)
                return result.isSuccess
            } finally {
                releaseActionLock()
            }
        }

        _uiState.update {
            it.copy(
                isCustomerPaymentSubmitting = true,
                customerPaymentError = null,
                draftCustomerPaymentOperationId = pid
            )
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.recordCustomerPayment(
                        customerId = customerId,
                        amount = amountExact,
                        note = note,
                        paymentId = pid,
                        cashAccountId = cashAccountId,
                        paymentDate = paymentDate,
                        allocations = allocations
                    )
                }
                if (result.isSuccess) {
                    val tx = result.getOrNull()
                    syncCustomers()
                    val nextDraftId = if (repository is ProductionTraderRepository) {
                        repository.getDraftOperationId("customer_payment")
                    } else {
                        repository.rotateDraftOperationId("customer_payment")
                    }
                    val nextActiveDraft = repository.getActiveDraftPayload("customer_payment")
                    val nextRecovering = repository.isDraftRecovering("customer_payment")
                    _uiState.update { state ->
                        val currency = state.businessInfo.defaultCurrency
                        val inDirectory = state.customers.firstOrNull { it.id == customerId }
                        val fallbackCust = state.selectedCustomerForDetail?.takeIf { it.id == customerId }
                            ?: state.customerSearchResults?.firstOrNull { it.id == customerId }
                        val updatedCust = (inDirectory ?: fallbackCust)?.let { cust ->
                            if (tx?.runningBalanceExact != null) cust.copy(balanceExact = tx.runningBalanceExact) else cust
                        }
                        val lang = state.language
                        val paidStr = WesternFormatters.formatHumanMoney2dp(amountExact, currency, lang)
                        val balExact = tx?.runningBalanceExact ?: updatedCust?.balanceExact
                        val isBalKnown = balExact != null
                        val hasAllocs = allocations.isNotEmpty()
                        val totalAllocExact = allocations.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.amountExact) }
                        val allocStr = WesternFormatters.formatHumanMoney2dp(totalAllocExact, currency, lang)

                        val msgAr = when {
                            !hasAllocs -> {
                                when {
                                    balExact == null -> "تم قبض $paidStr نقداً (دفعة مقدمة غير مخصصة) — الرصيد الحالي غير متاح (يتطلب تحديث كشف الحساب)"
                                    balExact > BigDecimal.ZERO -> "تم قبض $paidStr نقداً (دفعة غير مخصصة) — الرصيد الدفتري المتبقي: ${WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)}"
                                    balExact < BigDecimal.ZERO -> "تم قبض $paidStr نقداً — رصيد الزبون المقدم (دفعة غير مخصصة): ${WesternFormatters.formatHumanMoney2dp(balExact.negate(), currency, lang)}"
                                    else -> "تم قبض $paidStr نقداً (دفعة غير مخصصة) — الرصيد الدفتري: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                                }
                            }
                            else -> {
                                val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                                val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                                val balDisplay = if (balExact == null) "غير متاح" else if (balExact < BigDecimal.ZERO) "رصيد مقدم ${WesternFormatters.formatHumanMoney2dp(balExact.abs(), currency, lang)}" else WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)
                                if (unallocExact > BigDecimal.ZERO) {
                                    "تم قبض $paidStr نقداً (مخصص: $allocStr، دفعة مقدمة غير مخصصة: $unallocStr) — الرصيد الدفتري: $balDisplay"
                                } else {
                                    "تم قبض $paidStr نقداً وتخصيصها للذمم المحددة — الرصيد الدفتري المتبقي: $balDisplay"
                                }
                            }
                        }

                        val msgEn = when {
                            !hasAllocs -> {
                                when {
                                    balExact == null -> "Collected $paidStr in cash (unapplied advance) — Current balance unavailable (statement refresh required)"
                                    balExact > BigDecimal.ZERO -> "Collected $paidStr in cash (unapplied advance) — Remaining ledger balance: ${WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)}"
                                    balExact < BigDecimal.ZERO -> "Collected $paidStr in cash — Customer advance credit: ${WesternFormatters.formatHumanMoney2dp(balExact.negate(), currency, lang)}"
                                    else -> "Collected $paidStr in cash (unapplied advance) — Ledger balance: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                                }
                            }
                            else -> {
                                val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                                val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                                val balDisplay = if (!isBalKnown) "unavailable" else if (balExact!! < BigDecimal.ZERO) "advance credit ${WesternFormatters.formatHumanMoney2dp(balExact.abs(), currency, lang)}" else WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)
                                if (unallocExact > BigDecimal.ZERO) {
                                    "Collected $paidStr in cash (Allocated: $allocStr, Unapplied advance: $unallocStr) — Ledger balance: $balDisplay"
                                } else {
                                    "Collected $paidStr in cash applied to selected targets — Remaining ledger balance: $balDisplay"
                                }
                            }
                        }

                        state.copy(
                            draftCustomerPaymentOperationId = nextDraftId,
                            activeCustomerPaymentDraft = nextActiveDraft,
                            isCustomerPaymentRecovering = nextRecovering,
                            isCustomerPaymentSubmitting = false,
                            customerPaymentError = null,
                            isPaymentCollectionDialogOpen = false,
                            selectedCustomerForDetail = updatedCust,
                            lastCustomerPaymentMessageAr = msgAr,
                            lastCustomerPaymentMessageEn = msgEn
                        )
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Payment rejected"
                    val isScopeActive = repository.isScopeActive()
                    val currentDraftId = if (isScopeActive) repository.getDraftOperationId("customer_payment") else _uiState.value.draftCustomerPaymentOperationId
                    val activeDraft = if (isScopeActive) repository.getActiveDraftPayload("customer_payment") else null
                    val stillRecovering = if (isScopeActive) repository.isDraftRecovering("customer_payment") else false
                    _uiState.update {
                        it.copy(
                            draftCustomerPaymentOperationId = currentDraftId,
                            activeCustomerPaymentDraft = activeDraft,
                            isCustomerPaymentRecovering = stillRecovering,
                            isCustomerPaymentSubmitting = false,
                            customerPaymentError = err
                        )
                    }
                    showBanner(err, err, isError = true)
                }
                onComplete(result)
            } catch (e: Exception) {
                val err = e.message ?: "Payment rejected"
                val isScopeActive = repository.isScopeActive()
                val currentDraftId = if (isScopeActive) repository.getDraftOperationId("customer_payment") else _uiState.value.draftCustomerPaymentOperationId
                val activeDraft = if (isScopeActive) repository.getActiveDraftPayload("customer_payment") else null
                val stillRecovering = if (isScopeActive) repository.isDraftRecovering("customer_payment") else false
                _uiState.update {
                    it.copy(
                        draftCustomerPaymentOperationId = currentDraftId,
                        activeCustomerPaymentDraft = activeDraft,
                        isCustomerPaymentRecovering = stillRecovering,
                        isCustomerPaymentSubmitting = false,
                        customerPaymentError = err
                    )
                }
                showBanner(err, err, isError = true)
                onComplete(Result.failure(e))
            } finally {
                releaseActionLock()
            }
        }
        return true
    }

    fun createCustomer(name: String, phone: String, address: String, onComplete: (Boolean) -> Unit = {}) {
        if (!acquireActionLock()) {
            onComplete(false)
            return
        }

        val opId = _uiState.value.draftCustomerCreateOperationId

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking { repository.createCustomer(name, phone, address, opId) }
                val success = if (result.isSuccess) {
                    val created = result.getOrNull()
                    syncCustomers()
                    val nextOpId = repository.rotateDraftOperationId("customer_create")
                    _uiState.update {
                        it.copy(
                            draftCustomerCreateOperationId = nextOpId,
                            isAddCustomerDialogOpen = false,
                            activeCustomerCreateDraft = null,
                            isCustomerCreateRecovering = false,
                            lastCustomerPaymentMessageAr = if (created != null) "تمت إضافة الزبون «${created.nameAr}» إلى دفتر الحسابات" else null,
                            lastCustomerPaymentMessageEn = if (created != null) "Added customer \"${created.nameEn}\" to ledger" else null
                        )
                    }
                    true
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Customer creation failed"
                    showBanner(err, err, isError = true)
                    false
                }
                onComplete(success)
            } finally {
                releaseActionLock()
            }
            return
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.createCustomer(name, phone, address, opId)
                }
                val success = if (result.isSuccess) {
                    val created = result.getOrNull()
                    syncCustomers()
                    val nextOpId = repository.rotateDraftOperationId("customer_create")
                    _uiState.update {
                        it.copy(
                            draftCustomerCreateOperationId = nextOpId,
                            isAddCustomerDialogOpen = false,
                            activeCustomerCreateDraft = null,
                            isCustomerCreateRecovering = false,
                            lastCustomerPaymentMessageAr = if (created != null) "تمت إضافة الزبون «${created.nameAr}» إلى دفتر الحسابات" else null,
                            lastCustomerPaymentMessageEn = if (created != null) "Added customer \"${created.nameEn}\" to ledger" else null
                        )
                    }
                    true
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Customer creation failed"
                    showBanner(err, err, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Customer creation failed"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
    }

    fun openAddCustomerDialog(open: Boolean) {
        if (open) {
            val payload = repository.getActiveDraftPayload("customer_create")
            val isRecovering = repository.isDraftRecovering("customer_create")
            _uiState.update {
                it.copy(
                    isAddCustomerDialogOpen = true,
                    activeCustomerCreateDraft = payload,
                    isCustomerCreateRecovering = isRecovering
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isAddCustomerDialogOpen = false,
                    activeCustomerCreateDraft = null,
                    isCustomerCreateRecovering = false
                )
            }
        }
    }

    fun prepareSupplierCreateDraft() {
        val payload = repository.getActiveDraftPayload("supplier_create")
        val isRecovering = repository.isDraftRecovering("supplier_create")
        _uiState.update {
            it.copy(
                activeSupplierCreateDraft = payload,
                isSupplierCreateRecovering = isRecovering
            )
        }
    }

    fun prepareCustomerPaymentDraft() {
        val draftId = repository.getDraftOperationId("customer_payment")
        val payload = repository.getActiveDraftPayload("customer_payment")
        val isRecovering = repository.isDraftRecovering("customer_payment")
        _uiState.update {
            it.copy(
                draftCustomerPaymentOperationId = draftId,
                activeCustomerPaymentDraft = payload,
                isCustomerPaymentRecovering = isRecovering,
                customerPaymentError = null
            )
        }
    }

    fun prepareSupplierPaymentDraft() {
        val draftId = repository.getDraftOperationId("supplier_payment")
        val payload = repository.getActiveDraftPayload("supplier_payment")
        val isRecovering = repository.isDraftRecovering("supplier_payment")
        _uiState.update {
            it.copy(
                draftSupplierPaymentOperationId = draftId,
                activeSupplierPaymentDraft = payload,
                isSupplierPaymentRecovering = isRecovering,
                supplierPaymentError = null
            )
        }
    }

    // Suppliers & Purchasing Operations
    fun openSupplierPayment(supplier: Supplier) {
        if (!ensureActiveScope()) return
        if (!MerchantAction.PAY.allowed(_uiState.value.currentSession)) return
        supplierSearchJob?.cancel()
        supplierSearchGeneration++
        prepareSupplierPaymentDraft()
        selectSupplierForDetail(supplier)
        if (_uiState.value.currentScreen == TraderScreen.SUPPLIER_DETAIL) {
            _uiState.update {
                it.copy(
                    pendingAction = MerchantAction.PAY,
                    supplierSearchQuery = "",
                    supplierSearchResults = null,
                    isSearchingSuppliers = false,
                    supplierSearchError = null
                )
            }
        }
    }

    fun selectSupplierForDetail(supplier: Supplier) {
        if (!ensureActiveScope()) return
        if (!_uiState.value.currentSession.canManageSuppliers()) return
        _uiState.update { state ->
            val freshSupplier = state.suppliers.firstOrNull { it.id == supplier.id } ?: supplier
            state.copy(
                selectedSupplierForDetail = freshSupplier,
                lastSupplierActionMessageAr = null,
                lastSupplierActionMessageEn = null,
                isSupplierActionError = false,
                currentScreen = TraderScreen.SUPPLIER_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
        viewModelScope.launch {
            val res = repository.getSupplierDetail(supplier.id)
            if (res.isSuccess) {
                val detailed = res.getOrThrow()
                _uiState.update { state ->
                    if (state.selectedSupplierForDetail?.id == supplier.id) {
                        state.copy(selectedSupplierForDetail = detailed)
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun dismissSupplierFeedback() {
        _uiState.update {
            it.copy(
                lastSupplierActionMessageAr = null,
                lastSupplierActionMessageEn = null,
                isSupplierActionError = false
            )
        }
    }

    fun openReceivePurchaseDialog(open: Boolean) {
        if (open && !_uiState.value.currentSession.canManageSuppliers()) return
        _uiState.update { it.copy(isReceivePurchaseDialogOpen = open) }
    }

    fun recordPurchaseBill(
        supplierId: String,
        itemsSummary: String,
        totalIls: Double,
        paidNowIls: Double,
        expectedProductId: String? = null,
        expectedQuantity: Int = 0,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        val session = _uiState.value.currentSession
        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.recordPurchaseBill(
                        supplierId = supplierId,
                        itemsSummary = itemsSummary,
                        totalIls = totalIls,
                        paidNowIls = paidNowIls,
                        operator = session,
                        expectedProductId = expectedProductId,
                        expectedQuantity = expectedQuantity
                    )
                }
                val success = if (result.isSuccess) {
                    val bill = result.getOrNull()
                    syncSuppliersAndPurchases()
                    _uiState.update { state ->
                        val billCode = bill?.let { WesternFormatters.formatBillCode(it.billNumber) } ?: ""
                        val remStr = bill?.let { WesternFormatters.formatHumanMoney2dp(it.remainingPayableExact, it.currency, state.language) } ?: ""
                        val stockNoteAr = if (expectedProductId != null && expectedQuantity > 0) {
                            " — مسجل بانتظار استلام (${WesternFormatters.formatInt(expectedQuantity)} وحدة) مخزنياً"
                        } else {
                            " — قيد مالي فقط دون إضافة مخزون تلقائي"
                        }
                        val stockNoteEn = if (expectedProductId != null && expectedQuantity > 0) {
                            " — pending physical goods receipt (${expectedQuantity} units)"
                        } else {
                            " — financial entry only, stock not auto-incremented"
                        }
                        state.copy(
                            isReceivePurchaseDialogOpen = false,
                            selectedSupplierForDetail = state.suppliers.firstOrNull { it.id == supplierId },
                            lastSupplierActionMessageAr = "تم قيد فاتورة الشراء $billCode (المتبقي للمورد: $remStr)$stockNoteAr",
                            lastSupplierActionMessageEn = "Purchase bill $billCode recorded (Payable added: $remStr)$stockNoteEn",
                            isSupplierActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Purchase bill rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.recordPurchaseBill(
                        supplierId = supplierId,
                        itemsSummary = itemsSummary,
                        totalIls = totalIls,
                        paidNowIls = paidNowIls,
                        operator = session,
                        expectedProductId = expectedProductId,
                        expectedQuantity = expectedQuantity
                    )
                }
                val success = if (result.isSuccess) {
                    val bill = result.getOrNull()
                    syncSuppliersAndPurchases()
                    _uiState.update { state ->
                        val billCode = bill?.let { WesternFormatters.formatBillCode(it.billNumber) } ?: ""
                        val remStr = bill?.let { WesternFormatters.formatHumanMoney2dp(it.remainingPayableExact, it.currency, state.language) } ?: ""
                        val stockNoteAr = if (expectedProductId != null && expectedQuantity > 0) {
                            " — مسجل بانتظار استلام (${WesternFormatters.formatInt(expectedQuantity)} وحدة) مخزنياً"
                        } else {
                            " — قيد مالي فقط دون إضافة مخزون تلقائي"
                        }
                        val stockNoteEn = if (expectedProductId != null && expectedQuantity > 0) {
                            " — pending physical goods receipt (${expectedQuantity} units)"
                        } else {
                            " — financial entry only, stock not auto-incremented"
                        }
                        state.copy(
                            isReceivePurchaseDialogOpen = false,
                            selectedSupplierForDetail = state.suppliers.firstOrNull { it.id == supplierId },
                            lastSupplierActionMessageAr = "تم قيد فاتورة الشراء $billCode (المتبقي للمورد: $remStr)$stockNoteAr",
                            lastSupplierActionMessageEn = "Purchase bill $billCode recorded (Payable added: $remStr)$stockNoteEn",
                            isSupplierActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Purchase bill rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Purchase bill rejected"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    fun recordStructuredPurchaseBill(
        supplierId: String,
        lines: List<PurchaseLineItem>,
        paidNowIls: Double,
        note: String = "",
        operationId: String = "",
        onComplete: (Result<PurchaseBill>) -> Unit = {}
    ): Result<PurchaseBill> {
        if (!acquireActionLock()) {
            val err = Result.failure<PurchaseBill>(IllegalStateException("Action in progress"))
            onComplete(err)
            return err
        }

        val opId = if (operationId.isNotBlank()) operationId else _uiState.value.draftPurchaseOperationId
        val session = _uiState.value.currentSession

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.recordStructuredPurchaseBill(supplierId, lines, paidNowIls, session, note, opId)
                }
                if (result.isSuccess) {
                    val created = result.getOrNull()
                    val updatedSuppliers = repository.getSuppliers()
                    val updatedBills = repository.getPurchaseBills()
                    val updatedSup = updatedSuppliers.firstOrNull { it.id == supplierId }
                    _uiState.update { state ->
                        state.copy(
                            draftPurchaseOperationId = repository.rotateDraftOperationId("purchase"),
                            isReceivePurchaseDialogOpen = false,
                            suppliers = updatedSuppliers,
                            purchaseBills = updatedBills,
                            selectedSupplierForDetail = updatedSup ?: state.selectedSupplierForDetail,
                            lastSupplierActionMessageAr = "تم اعتماد فاتورة الشراء #${created?.billNumber ?: ""} بنجاح",
                            lastSupplierActionMessageEn = "Purchase bill #${created?.billNumber ?: ""} recorded successfully",
                            isSupplierActionError = false
                        )
                    }
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Purchase bill rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                }
                onComplete(result)
                return result
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.recordStructuredPurchaseBill(supplierId, lines, paidNowIls, session, note, opId)
                }
                if (result.isSuccess) {
                    val created = result.getOrNull()
                    val updatedSuppliers = repository.getSuppliers()
                    val updatedBills = repository.getPurchaseBills()
                    val updatedSup = updatedSuppliers.firstOrNull { it.id == supplierId }
                    _uiState.update { state ->
                        state.copy(
                            draftPurchaseOperationId = repository.rotateDraftOperationId("purchase"),
                            isReceivePurchaseDialogOpen = false,
                            suppliers = updatedSuppliers,
                            purchaseBills = updatedBills,
                            selectedSupplierForDetail = updatedSup ?: state.selectedSupplierForDetail,
                            lastSupplierActionMessageAr = "تم اعتماد فاتورة الشراء #${created?.billNumber ?: ""} بنجاح",
                            lastSupplierActionMessageEn = "Purchase bill #${created?.billNumber ?: ""} recorded successfully",
                            isSupplierActionError = false
                        )
                    }
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Purchase bill rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                }
                onComplete(result)
            } catch (e: Exception) {
                val err = Result.failure<PurchaseBill>(e)
                val msg = e.message ?: "Purchase bill rejected"
                showBanner(msg, msg, isError = true)
                onComplete(err)
            } finally {
                releaseActionLock()
            }
        }
        return Result.failure(IllegalStateException("Action in progress"))
    }

    fun paySupplierAmount(
        supplierId: String,
        amountIls: Double,
        note: String = "سداد نقدي للمورد من الصندوق",
        paymentId: String = "",
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        val result = paySupplierAmountResult(supplierId, amountIls, note, paymentId) { res ->
            onComplete(res.isSuccess)
        }
        return result.isSuccess
    }

    fun paySupplierAmountResult(
        supplierId: String,
        amountIls: Double,
        note: String = "سداد نقدي للمورد من الصندوق",
        paymentId: String = "",
        onComplete: (Result<SupplierPayment>) -> Unit = {}
    ): Result<SupplierPayment> = paySupplierAmountExactResult(
        supplierId = supplierId,
        amount = BigDecimal(amountIls.toString()),
        note = note,
        paymentId = paymentId,
        cashAccountId = null,
        paymentDate = null,
        allocations = emptyList(),
        onComplete = onComplete
    )

    fun paySupplierAmountExactResult(
        supplierId: String,
        amount: BigDecimal,
        note: String = "سداد نقدي للمورد من الصندوق",
        paymentId: String = "",
        cashAccountId: String? = null,
        paymentDate: String? = null,
        allocations: List<PaymentAllocationInput> = emptyList(),
        onComplete: (Result<SupplierPayment>) -> Unit = {}
    ): Result<SupplierPayment> {
        if (repository is ProductionTraderRepository && !ensureActiveScope()) {
            val err = Result.failure<SupplierPayment>(SecurityException("Authenticated scope is inactive or revoked"))
            onComplete(err)
            return err
        }
        if (!acquireActionLock()) {
            val err = Result.failure<SupplierPayment>(IllegalStateException("Action in progress"))
            onComplete(err)
            return err
        }

        val pid = if (repository is ProductionTraderRepository) {
            val persistedDraftId = repository.getDraftOperationId("supplier_payment")
            if (paymentId.isNotBlank() && paymentId != persistedDraftId) {
                releaseActionLock()
                val currentLang = _uiState.value.language
                val errEn = "Supplier payment operation ID mismatch with persisted draft"
                val errAr = "تعارض معرّف عملية الصرف مع المسودة المحفوظة"
                val localized = currentLang.tr(errAr, errEn)
                _uiState.update {
                    it.copy(
                        draftSupplierPaymentOperationId = persistedDraftId,
                        activeSupplierPaymentDraft = repository.getActiveDraftPayload("supplier_payment"),
                        isSupplierPaymentRecovering = repository.isDraftRecovering("supplier_payment"),
                        supplierPaymentError = localized
                    )
                }
                showBanner(errAr, errEn, isError = true)
                val err = Result.failure<SupplierPayment>(IllegalStateException(errEn))
                onComplete(err)
                return err
            }
            persistedDraftId
        } else {
            if (paymentId.isNotBlank()) paymentId else _uiState.value.draftSupplierPaymentOperationId
        }
        val session = _uiState.value.currentSession
        val currencyMinorUnits = _uiState.value.businessInfo.defaultCurrencyMinorUnits
        val amountExact = try {
            amount.setScale(currencyMinorUnits, RoundingMode.UNNECESSARY)
        } catch (e: ArithmeticException) {
            releaseActionLock()
            val errAr = "دقة المبلغ تتجاوز الحد المسموح ($currencyMinorUnits خانات)"
            val errEn = "Amount precision exceeds allowed limit ($currencyMinorUnits decimals)"
            val localized = _uiState.value.language.tr(errAr, errEn)
            _uiState.update { it.copy(supplierPaymentError = localized) }
            showBanner(errAr, errEn, isError = true)
            val err = Result.failure<SupplierPayment>(IllegalArgumentException(errEn))
            onComplete(err)
            return err
        }

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.recordSupplierPayment(
                        supplierId = supplierId,
                        amount = amountExact,
                        note = note,
                        operator = session,
                        paymentId = pid,
                        cashAccountId = cashAccountId,
                        paymentDate = paymentDate,
                        allocations = allocations
                    )
                }
                if (result.isSuccess) {
                    val updatedSuppliers = repository.getSuppliers()
                    val updatedBills = repository.getPurchaseBills()
                    val updatedPayments = repository.getSupplierPayments()
                    val updatedSup = updatedSuppliers.firstOrNull { it.id == supplierId }
                    val currency = _uiState.value.businessInfo.defaultCurrency
                    val lang = _uiState.value.language
                    val paidStr = WesternFormatters.formatHumanMoney2dp(amountExact, currency, lang)
                    val owedExact = updatedSup?.balanceOwedExact
                    val advExact = updatedSup?.advancesExact
                    val effectiveAdvanceExact = when {
                        advExact != null && advExact > BigDecimal.ZERO -> advExact
                        owedExact != null && owedExact < BigDecimal.ZERO -> owedExact.abs()
                        else -> null
                    }
                    val hasAllocs = allocations.isNotEmpty()
                    val totalAllocExact = allocations.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.amountExact) }
                    val allocStr = WesternFormatters.formatHumanMoney2dp(totalAllocExact, currency, lang)

                    val msgAr = when {
                        !hasAllocs -> {
                            when {
                                effectiveAdvanceExact != null -> "تم صرف $paidStr للمورد — أصبح لنا دفعة مقدمة عنده: ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "تم صرف $paidStr للمورد — الرصيد الحالي غير متاح (يتطلب تحديث البيانات)"
                                owedExact > BigDecimal.ZERO -> "تم صرف $paidStr للمورد — المتبقي له علينا: ${WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)}"
                                else -> "تم صرف $paidStr للمورد (دفعة غير مخصصة) — الرصيد الدفتري: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                            }
                        }
                        else -> {
                            val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                            val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                            val balDisplay = when {
                                effectiveAdvanceExact != null -> "رصيد مقدم لنا ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "غير متاح"
                                else -> WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)
                            }
                            if (unallocExact > BigDecimal.ZERO) {
                                "تم صرف $paidStr للمورد (مخصص: $allocStr، دفعة مقدمة غير مخصصة: $unallocStr) — الرصيد: $balDisplay"
                            } else {
                                "تم صرف $paidStr للمورد وتخصيصها للذمم المحددة — الرصيد المتبقي له: $balDisplay"
                            }
                        }
                    }

                    val msgEn = when {
                        !hasAllocs -> {
                            when {
                                effectiveAdvanceExact != null -> "Paid $paidStr to supplier — Store prepayment credit: ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "Paid $paidStr to supplier — Current balance unavailable"
                                owedExact > BigDecimal.ZERO -> "Paid $paidStr to supplier — Remaining payable: ${WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)}"
                                else -> "Paid $paidStr to supplier (unapplied advance) — Ledger balance: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                            }
                        }
                        else -> {
                            val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                            val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                            val balDisplay = when {
                                effectiveAdvanceExact != null -> "store advance credit ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "unavailable"
                                else -> WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)
                            }
                            if (unallocExact > BigDecimal.ZERO) {
                                "Paid $paidStr to supplier (Allocated: $allocStr, Unapplied advance: $unallocStr) — Ledger balance: $balDisplay"
                            } else {
                                "Paid $paidStr to supplier applied to selected targets — Remaining payable: $balDisplay"
                            }
                        }
                    }

                    _uiState.update { state ->
                        state.copy(
                            draftSupplierPaymentOperationId = repository.rotateDraftOperationId("supplier_payment"),
                            isSupplierPaymentSubmitting = false,
                            supplierPaymentError = null,
                            suppliers = updatedSuppliers,
                            purchaseBills = updatedBills,
                            supplierPayments = updatedPayments,
                            selectedSupplierForDetail = updatedSup ?: state.selectedSupplierForDetail,
                            lastSupplierActionMessageAr = msgAr,
                            lastSupplierActionMessageEn = msgEn,
                            isSupplierActionError = false
                        )
                    }
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Supplier disbursement rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            isSupplierPaymentSubmitting = false,
                            supplierPaymentError = it.language.tr(errAr, errEn),
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                }
                releaseActionLock()
                onComplete(result)
                return result
            } finally {
                releaseActionLock()
            }
        }

        _uiState.update {
            it.copy(
                isSupplierPaymentSubmitting = true,
                supplierPaymentError = null,
                draftSupplierPaymentOperationId = pid
            )
        }

        viewModelScope.launch {
            var completionResult: Result<SupplierPayment>? = null
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.recordSupplierPayment(
                        supplierId = supplierId,
                        amount = amountExact,
                        note = note,
                        operator = session,
                        paymentId = pid,
                        cashAccountId = cashAccountId,
                        paymentDate = paymentDate,
                        allocations = allocations
                    )
                }
                if (result.isSuccess) {
                    val updatedSuppliers = repository.getSuppliers()
                    val updatedBills = repository.getPurchaseBills()
                    val updatedPayments = repository.getSupplierPayments()
                    val updatedSup = updatedSuppliers.firstOrNull { it.id == supplierId }
                    val currency = _uiState.value.businessInfo.defaultCurrency
                    val lang = _uiState.value.language
                    val paidStr = WesternFormatters.formatHumanMoney2dp(amountExact, currency, lang)
                    val owedExact = updatedSup?.balanceOwedExact
                    val advExact = updatedSup?.advancesExact
                    val effectiveAdvanceExact = when {
                        advExact != null && advExact > BigDecimal.ZERO -> advExact
                        owedExact != null && owedExact < BigDecimal.ZERO -> owedExact.abs()
                        else -> null
                    }
                    val hasAllocs = allocations.isNotEmpty()
                    val totalAllocExact = allocations.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.amountExact) }
                    val allocStr = WesternFormatters.formatHumanMoney2dp(totalAllocExact, currency, lang)

                    val msgAr = when {
                        !hasAllocs -> {
                            when {
                                effectiveAdvanceExact != null -> "تم صرف $paidStr للمورد — أصبح لنا دفعة مقدمة عنده: ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "تم صرف $paidStr للمورد — الرصيد الحالي غير متاح (يتطلب تحديث البيانات)"
                                owedExact > BigDecimal.ZERO -> "تم صرف $paidStr للمورد — المتبقي له علينا: ${WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)}"
                                else -> "تم صرف $paidStr للمورد (دفعة غير مخصصة) — الرصيد الدفتري: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                            }
                        }
                        else -> {
                            val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                            val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                            val balDisplay = when {
                                effectiveAdvanceExact != null -> "رصيد مقدم لنا ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "غير متاح"
                                else -> WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)
                            }
                            if (unallocExact > BigDecimal.ZERO) {
                                "تم صرف $paidStr للمورد (مخصص: $allocStr، دفعة مقدمة غير مخصصة: $unallocStr) — الرصيد: $balDisplay"
                            } else {
                                "تم صرف $paidStr للمورد وتخصيصها للذمم المحددة — الرصيد المتبقي له: $balDisplay"
                            }
                        }
                    }

                    val msgEn = when {
                        !hasAllocs -> {
                            when {
                                effectiveAdvanceExact != null -> "Paid $paidStr to supplier — Store prepayment credit: ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "Paid $paidStr to supplier — Current balance unavailable"
                                owedExact > BigDecimal.ZERO -> "Paid $paidStr to supplier — Remaining payable: ${WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)}"
                                else -> "Paid $paidStr to supplier (unapplied advance) — Ledger balance: ${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)}"
                            }
                        }
                        else -> {
                            val unallocExact = amountExact.subtract(totalAllocExact).coerceAtLeast(BigDecimal.ZERO)
                            val unallocStr = WesternFormatters.formatHumanMoney2dp(unallocExact, currency, lang)
                            val balDisplay = when {
                                effectiveAdvanceExact != null -> "store advance credit ${WesternFormatters.formatHumanMoney2dp(effectiveAdvanceExact, currency, lang)}"
                                owedExact == null -> "unavailable"
                                else -> WesternFormatters.formatHumanMoney2dp(owedExact, currency, lang)
                            }
                            if (unallocExact > BigDecimal.ZERO) {
                                "Paid $paidStr to supplier (Allocated: $allocStr, Unapplied advance: $unallocStr) — Ledger balance: $balDisplay"
                            } else {
                                "Paid $paidStr to supplier applied to selected targets — Remaining payable: $balDisplay"
                            }
                        }
                    }

                    val nextDraftId = if (repository is ProductionTraderRepository) {
                        repository.getDraftOperationId("supplier_payment")
                    } else {
                        repository.rotateDraftOperationId("supplier_payment")
                    }
                    val nextActiveDraft = repository.getActiveDraftPayload("supplier_payment")
                    val nextRecovering = repository.isDraftRecovering("supplier_payment")

                    _uiState.update { state ->
                        state.copy(
                            draftSupplierPaymentOperationId = nextDraftId,
                            activeSupplierPaymentDraft = nextActiveDraft,
                            isSupplierPaymentRecovering = nextRecovering,
                            isSupplierPaymentSubmitting = false,
                            supplierPaymentError = null,
                            suppliers = updatedSuppliers,
                            purchaseBills = updatedBills,
                            supplierPayments = updatedPayments,
                            selectedSupplierForDetail = updatedSup ?: state.selectedSupplierForDetail,
                            lastSupplierActionMessageAr = msgAr,
                            lastSupplierActionMessageEn = msgEn,
                            isSupplierActionError = false
                        )
                    }
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Supplier disbursement rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val isScopeActive = repository.isScopeActive()
                    val currentDraftId = if (isScopeActive) repository.getDraftOperationId("supplier_payment") else _uiState.value.draftSupplierPaymentOperationId
                    val activeDraft = if (isScopeActive) repository.getActiveDraftPayload("supplier_payment") else null
                    val stillRecovering = if (isScopeActive) repository.isDraftRecovering("supplier_payment") else false
                    _uiState.update {
                        it.copy(
                            draftSupplierPaymentOperationId = currentDraftId,
                            activeSupplierPaymentDraft = activeDraft,
                            isSupplierPaymentRecovering = stillRecovering,
                            isSupplierPaymentSubmitting = false,
                            supplierPaymentError = it.language.tr(errAr, errEn),
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                }
                completionResult = result
            } catch (e: Exception) {
                val err = Result.failure<SupplierPayment>(e)
                val msg = e.message ?: "Supplier payment rejected"
                val isScopeActive = repository.isScopeActive()
                val currentDraftId = if (isScopeActive) repository.getDraftOperationId("supplier_payment") else _uiState.value.draftSupplierPaymentOperationId
                val activeDraft = if (isScopeActive) repository.getActiveDraftPayload("supplier_payment") else null
                val stillRecovering = if (isScopeActive) repository.isDraftRecovering("supplier_payment") else false
                _uiState.update {
                    it.copy(
                        draftSupplierPaymentOperationId = currentDraftId,
                        activeSupplierPaymentDraft = activeDraft,
                        isSupplierPaymentRecovering = stillRecovering,
                        isSupplierPaymentSubmitting = false,
                        supplierPaymentError = msg
                    )
                }
                showBanner(msg, msg, isError = true)
                completionResult = err
            } finally {
                releaseActionLock()
            }
            completionResult?.let { onComplete(it) }
        }
        return Result.failure(IllegalStateException("Action in progress"))
    }

    fun openAddExpenseDialog(open: Boolean) {
        if (open && !_uiState.value.currentSession.canManageExpenses()) return
        _uiState.update { it.copy(isAddExpenseDialogOpen = open) }
    }

    fun dismissExpenseFeedback() {
        _uiState.update {
            it.copy(
                lastExpenseActionMessageAr = null,
                lastExpenseActionMessageEn = null,
                isExpenseActionError = false
            )
        }
    }

    fun addExpense(
        title: String,
        category: ExpenseCategory,
        amountIls: Double,
        categoryId: String? = null,
        operationId: String = "",
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        val opId = if (operationId.isNotBlank()) operationId else _uiState.value.draftExpenseOperationId
        val session = _uiState.value.currentSession

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.recordExpense(title, category, amountIls, session, categoryId, opId)
                }
                val success = if (result.isSuccess) {
                    val recorded = result.getOrNull()
                    syncExpenses()
                    val amtStr = WesternFormatters.formatIls(recorded?.amountIls ?: amountIls)
                    _uiState.update {
                        it.copy(
                            draftExpenseOperationId = repository.rotateDraftOperationId("expense"),
                            isAddExpenseDialogOpen = false,
                            lastExpenseActionMessageAr = "تم تسجيل وصرف $amtStr (${category.labelAr}) من نقد الصندوق لليوم",
                            lastExpenseActionMessageEn = "Recorded and disbursed $amtStr (${category.labelEn}) from today's cash drawer",
                            isExpenseActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Expense rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastExpenseActionMessageAr = errAr,
                            lastExpenseActionMessageEn = errEn,
                            isExpenseActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.recordExpense(title, category, amountIls, session, categoryId, opId)
                }
                val success = if (result.isSuccess) {
                    val recorded = result.getOrNull()
                    syncExpenses()
                    val amtStr = WesternFormatters.formatIls(recorded?.amountIls ?: amountIls)
                    _uiState.update {
                        it.copy(
                            draftExpenseOperationId = repository.rotateDraftOperationId("expense"),
                            isAddExpenseDialogOpen = false,
                            lastExpenseActionMessageAr = "تم تسجيل وصرف $amtStr (${category.labelAr}) من نقد الصندوق لليوم",
                            lastExpenseActionMessageEn = "Recorded and disbursed $amtStr (${category.labelEn}) from today's cash drawer",
                            isExpenseActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Expense rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastExpenseActionMessageAr = errAr,
                            lastExpenseActionMessageEn = errEn,
                            isExpenseActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Expense rejected"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    fun setPrinterSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isPrinterSheetOpen = open) }
    }

    fun selectPaperWidth(widthMm: Int) {
        _uiState.update { state ->
            val printer = state.connectedPrinter
            state.copy(
                receiptPaperWidthMm = widthMm,
                printProgressStepAr = if (printer != null) {
                    "وضع المحاكاة: تم ضبط عرض الورق ${widthMm}mm (${printer.name})"
                } else {
                    "وضع المحاكاة: تم ضبط عرض المعاينة ${widthMm}mm (لا توجد طابعة محددة)"
                },
                printProgressStepEn = if (printer != null) {
                    "Simulation mode: Paper width set to ${widthMm}mm (${printer.name})"
                } else {
                    "Simulation mode: Preview width set to ${widthMm}mm (no printer selected)"
                }
            )
        }
    }

    fun scanBluetoothPrinters() {
        printJob?.cancel()
        _uiState.update { state ->
            val ready = state.connectedPrinter != null && state.connectedPrinter.isPaired
            val pairedCount = state.availablePrinters.count { it.isPaired }
            state.copy(
                printerStatus = if (ready) PrinterStatus.READY else PrinterStatus.DISCONNECTED,
                printProgressStepAr = "وضع المحاكاة: ${state.availablePrinters.size} أجهزة افتراضية (${pairedCount} متاحة، 1 خارج النطاق)",
                printProgressStepEn = "Simulation mode: ${state.availablePrinters.size} virtual devices (${pairedCount} available, 1 offline)"
            )
        }
    }

    fun connectBluetoothPrinter(device: BluetoothPrinterDevice) {
        printJob?.cancel()
        if (!device.isPaired) {
            _uiState.update {
                it.copy(
                    connectedPrinter = null,
                    printerStatus = PrinterStatus.ERROR,
                    printProgressFraction = 0f,
                    printProgressStepAr = "تعذر الاتصال المحاكى بـ ${device.name} (خارج التغطية / غير مقترنة) — اختر طابعة متاحة أو أعد المحاولة",
                    printProgressStepEn = "Simulated connection to ${device.name} failed (offline / unpaired) — select an available printer or retry"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                connectedPrinter = device,
                receiptPaperWidthMm = device.paperWidthMm,
                printerStatus = PrinterStatus.READY,
                printProgressFraction = 0f,
                printProgressStepAr = "وضع المحاكاة: متصل افتراضياً بـ ${device.name} (${device.paperWidthMm}mm)",
                printProgressStepEn = "Simulation mode: Virtually connected to ${device.name} (${device.paperWidthMm}mm)"
            )
        }
    }

    fun disconnectBluetoothPrinter() {
        printJob?.cancel()
        _uiState.update {
            it.copy(
                connectedPrinter = null,
                printerStatus = PrinterStatus.DISCONNECTED,
                printProgressFraction = 0f,
                printProgressStepAr = "وضع المحاكاة: غير متصل بأي طابعة — اختر طابعة أو شارك الإيصال كنص",
                printProgressStepEn = "Simulation mode: No printer selected — select a printer or share receipt text"
            )
        }
    }

    fun simulateThermalPrint() {
        val state = _uiState.value
        val activeReceipt = state.activeReceipt ?: state.invoices.firstOrNull() ?: return
        val printer = state.connectedPrinter

        printJob?.cancel()
        if (printer == null || !printer.isPaired) {
            _uiState.update {
                it.copy(
                    printerStatus = PrinterStatus.ERROR,
                    printProgressFraction = 0f,
                    printProgressStepAr = "تعذرت محاكاة الطباعة: لا توجد طابعة متصلة. اختر طابعة من الإعدادات أو شارك النص.",
                    printProgressStepEn = "Simulated print failed: No printer connected. Select a printer in settings or share text."
                )
            }
            return
        }

        _uiState.update { current ->
            val updatedReceipt = activeReceipt.copy(printCount = activeReceipt.printCount + 1)
            val updatedInvoices = current.invoices.map { inv ->
                if (inv.id == updatedReceipt.id) updatedReceipt else inv
            }
            current.copy(
                activeReceipt = updatedReceipt,
                invoices = updatedInvoices,
                printerStatus = PrinterStatus.PRINTED_SUCCESS,
                printProgressFraction = 1.0f,
                printProgressStepAr = "تمت محاكاة الطباعة (#${updatedReceipt.printCount}) عبر ${printer.name} (${current.receiptPaperWidthMm}mm)",
                printProgressStepEn = "Simulated print (#${updatedReceipt.printCount}) completed via ${printer.name} (${current.receiptPaperWidthMm}mm)"
            )
        }
    }

    fun formatReceiptShareText(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String =
        formatReceiptShareText(receipt, overrideDetail = null)

    internal fun formatReceiptShareText(
        receipt: InvoiceRecord?,
        overrideDetail: com.example.data.mobile.merchantread.LiveInvoiceRecord?
    ): String {
        val target = receipt ?: return ""
        val state = _uiState.value
        val lang = state.language
        val business = state.businessInfo
        val matchingDetail = (overrideDetail ?: state.selectedInvoiceDetail)?.takeIf {
            target.serverInvoiceUlid == it.id || target.id == it.id
        }
        val txCurrency = matchingDetail?.currency?.trim()?.takeIf { it.isNotEmpty() }
            ?: target.normalizedCurrency
        val unavailCurrency = lang.tr("العملة غير متاحة", "Currency unavailable")
        fun fmtMoney(amount: BigDecimal?): String = WesternFormatters.formatReceiptMoney(
            amount = amount,
            currency = txCurrency,
            unavailableCurrencyLabel = unavailCurrency
        )

        val sb = StringBuilder()
        val settledPaid = matchingDetail?.paidExact ?: target.settledPaidExact
        val remainingDue = matchingDetail?.remainingDebitExact ?: target.remainingDebitExact
        val isPaidFull = matchingDetail?.isPaidInFull ?: (target.isPaidInFull || remainingDue.signum() <= 0)
        val isCashFull = matchingDetail?.isFullCashSale ?: isPaidFull
        val isPartial = !isPaidFull && (
            if (matchingDetail != null) settledPaid.signum() > 0 else target.paymentStatus == com.example.model.InvoicePaymentStatus.PARTIAL
        )

        val docTitle = when {
            isPaidFull && isCashFull -> lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
            isPaidFull -> lang.tr("فاتورة مبيعات مدفوعة", "Paid Sales Invoice")
            isPartial -> lang.tr("فاتورة مبيعات بسداد جزئي", "Partial-Payment Sales Invoice")
            else -> lang.tr("فاتورة مبيعات آجلة (ذمة على الحساب)", "Credit Sales Invoice (On Account)")
        }
        val docCode = matchingDetail?.invoiceNumber?.takeIf { it.isNotBlank() }
            ?: target.serverInvoiceNumber?.takeIf { it.isNotBlank() }
            ?: target.localReceiptNumber?.takeIf { it.isNotBlank() }
            ?: WesternFormatters.formatInvoiceCode(target.invoiceNumber)

        val storeName = matchingDetail?.thermalReceipt?.let { tr ->
            val ar = tr.storeNameAr?.takeIf { it.isNotBlank() } ?: tr.storeNameEn.orEmpty()
            val en = tr.storeNameEn?.takeIf { it.isNotBlank() } ?: tr.storeNameAr.orEmpty()
            lang.tr(ar, en).takeIf { it.isNotBlank() }
        } ?: business.name(lang)
        val city = matchingDetail?.thermalReceipt?.let { tr ->
            val ar = tr.cityAr?.takeIf { it.isNotBlank() } ?: tr.cityEn.orEmpty()
            val en = tr.cityEn?.takeIf { it.isNotBlank() } ?: tr.cityAr.orEmpty()
            lang.tr(ar, en).takeIf { it.isNotBlank() }
        } ?: business.city(lang)
        val phone = matchingDetail?.thermalReceipt?.phone?.takeIf { it.isNotBlank() } ?: business.phone
        val customerLabel = matchingDetail?.thermalReceipt?.customerName?.takeIf { it.isNotBlank() }
            ?: matchingDetail?.customerName?.takeIf { it.isNotBlank() }
            ?: target.customerNote
        val cashierLabel = matchingDetail?.thermalReceipt?.cashier?.takeIf { it.isNotBlank() }
            ?: lang.tr(target.cashierNameAr, target.cashierNameEn)
        val dateLabel = matchingDetail?.thermalReceipt?.dateTime?.takeIf { it.isNotBlank() }
            ?: matchingDetail?.date?.takeIf { it.isNotBlank() }
            ?: target.timestamp

        sb.appendLine(storeName)
        sb.appendLine("$city • $phone")
        sb.appendLine("--------------------------------")
        sb.appendLine("$docTitle: $docCode")
        sb.appendLine("${lang.tr("التاريخ", "Date")}: $dateLabel")
        sb.appendLine("${lang.tr("الزبون", "Customer")}: $customerLabel")
        sb.appendLine("${lang.tr("الكاشير", "Cashier")}: $cashierLabel")
        sb.appendLine("--------------------------------")
        val detailLines = matchingDetail?.thermalReceipt?.lines?.takeIf { it.isNotEmpty() }
            ?: matchingDetail?.lines
            ?: emptyList()
        if (detailLines.isNotEmpty()) {
            detailLines.forEachIndexed { idx, line ->
                sb.appendLine(
                    "${idx + 1}. ${line.productName} (${WesternFormatters.formatQuantity(line.quantity)} × ${fmtMoney(line.unitPrice)}) = ${fmtMoney(line.lineTotal)}"
                )
            }
        } else {
            target.items.forEachIndexed { idx, item ->
                sb.appendLine(
                    "${idx + 1}. ${item.product.name(lang)} (${WesternFormatters.formatQuantity(item.quantity)} × ${fmtMoney(item.unitPriceExact)}) = ${fmtMoney(item.lineTotalExact)}"
                )
            }
        }
        sb.appendLine("--------------------------------")
        val subtotal = matchingDetail?.subtotalExact ?: target.subtotalExact
        val discount = matchingDetail?.discountExact ?: target.discountExact
        val tax = matchingDetail?.taxExact ?: target.taxExact
        val netTotal = matchingDetail?.totalExact ?: target.netTotalExact
        val hasDiscount = discount.signum() > 0
        val hasTax = tax != null && tax.signum() > 0
        if (hasDiscount || hasTax) {
            sb.appendLine("${lang.tr("المجموع الفرعي", "Subtotal")}: ${fmtMoney(subtotal)}")
            if (hasTax) {
                sb.appendLine("${lang.tr("الضريبة", "Tax")}: ${fmtMoney(tax)}")
            }
            if (hasDiscount) {
                sb.appendLine("${lang.tr("الخصم", "Discount")}: -${fmtMoney(discount)}")
            }
        }
        sb.appendLine("${lang.tr("الصافي المطلوب", "Net Total")}: ${fmtMoney(netTotal)}")

        if (isPaidFull) {
            val statusStr = if (isCashFull) {
                lang.tr("مدفوع نقداً بالكامل", "Paid in Full (Cash)")
            } else {
                lang.tr("مدفوع بالكامل", "Paid in Full")
            }
            sb.appendLine("${lang.tr("الحالة", "Status")}: $statusStr")
            if (target.hasLocalTenderDetails && isCashFull) {
                sb.appendLine("${lang.tr("المقبوض نقداً", "Cash Tendered")}: ${fmtMoney(target.cashReceivedExact)}")
                if (target.changeReturnedExact.signum() > 0) {
                    sb.appendLine("${lang.tr("الباقي للزبون", "Change Returned")}: ${fmtMoney(target.changeReturnedExact)}")
                }
            } else {
                sb.appendLine("${lang.tr("المدفوع", "Paid")}: ${fmtMoney(settledPaid)}")
                sb.appendLine("${lang.tr("المقبوض نقداً", "Cash Tendered")}: ${lang.tr("غير متاح", "Unavailable")}")
                sb.appendLine("${lang.tr("الباقي للزبون", "Change Returned")}: ${lang.tr("غير متاح", "Unavailable")}")
            }
        } else {
            val statusStr = if (settledPaid.signum() > 0) {
                lang.tr("سداد جزئي على حساب الزبون", "Partial Payment — Balance on Account")
            } else {
                lang.tr("ذمة آجلة على حساب الزبون", "Unpaid — On Customer Account")
            }
            sb.appendLine("${lang.tr("الحالة", "Status")}: $statusStr")
            if (target.hasLocalTenderDetails) {
                if (target.cashReceivedExact.signum() > 0) {
                    sb.appendLine("${lang.tr("المدفوع عند الإصدار", "Cash Paid at Issue")}: ${fmtMoney(target.cashReceivedExact)}")
                }
            } else {
                if (settledPaid.signum() > 0) {
                    sb.appendLine("${lang.tr("المدفوع", "Paid")}: ${fmtMoney(settledPaid)}")
                }
                sb.appendLine("${lang.tr("تفاصيل المقبوض والباقي", "Tender & Change")}: ${lang.tr("غير متاح", "Unavailable")}")
            }
            sb.appendLine("${lang.tr("المتبقي بالذمة", "Balance Due on Account")}: ${fmtMoney(remainingDue)}")
        }
        return sb.toString().trim()
    }

    fun recordReceiptShare(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String {
        val text = formatReceiptShareText(receipt)
        if (text.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    receiptShareCount = it.receiptShareCount + 1,
                    lastSharedReceiptText = text
                )
            }
        }
        return text
    }

    fun recordReceiptCopy(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String {
        val text = formatReceiptShareText(receipt)
        if (text.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    receiptCopyCount = it.receiptCopyCount + 1,
                    lastSharedReceiptText = text
                )
            }
        }
        return text
    }

    // =========================================================================
    // Interactive Demo Extensions: Product CRUD, Drafts, Quotations, GRN & Shift
    // =========================================================================

    fun saveProduct(
        productId: String?,
        nameAr: String,
        nameEn: String = "",
        category: ProductCategory,
        unitAr: String,
        unitEn: String = "",
        barcode: String,
        priceIls: Double,
        costIls: Double,
        minStockThreshold: Int,
        initialStock: Int,
        warehouseAr: String = "المستودع الرئيسي - نابلس",
        lotBatchCode: String = "LOT-2026-A",
        expiryDate: String? = null,
        isWeightedDisplayOnly: Boolean = false,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        if (repository is OfflineDemoTraderRepository) {
            try {
                val session = _uiState.value.currentSession
                val result = kotlinx.coroutines.runBlocking {
                    repository.saveProduct(
                        productId = productId,
                        nameAr = nameAr,
                        nameEn = nameEn,
                        category = category,
                        unitAr = unitAr,
                        unitEn = unitEn,
                        barcode = barcode,
                        priceIls = priceIls,
                        costIls = costIls,
                        minStockThreshold = minStockThreshold,
                        initialStock = initialStock,
                        warehouseAr = warehouseAr,
                        lotBatchCode = lotBatchCode,
                        expiryDate = expiryDate,
                        isWeightedDisplayOnly = isWeightedDisplayOnly,
                        operator = session
                    )
                }
                val success = if (result.isSuccess) {
                    val saved = result.getOrNull()
                    syncFromRepository()
                    _uiState.update { state ->
                        val actionAr = if (productId == null) "تمت إضافة الصنف «${saved?.nameAr}» إلى الكتالوج" else "تم حفظ تعديلات الصنف «${saved?.nameAr}»"
                        val actionEn = if (productId == null) "Added product ${saved?.nameEn} to catalog" else "Saved changes for ${saved?.nameEn}"
                        state.copy(
                            selectedProductForDetail = saved ?: state.selectedProductForDetail,
                            lastInventoryActionMessageAr = actionAr,
                            lastInventoryActionMessageEn = actionEn,
                            isInventoryActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Product save failed"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = errAr,
                            lastInventoryActionMessageEn = errEn,
                            isInventoryActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val session = _uiState.value.currentSession
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.saveProduct(
                        productId = productId,
                        nameAr = nameAr,
                        nameEn = nameEn,
                        category = category,
                        unitAr = unitAr,
                        unitEn = unitEn,
                        barcode = barcode,
                        priceIls = priceIls,
                        costIls = costIls,
                        minStockThreshold = minStockThreshold,
                        initialStock = initialStock,
                        warehouseAr = warehouseAr,
                        lotBatchCode = lotBatchCode,
                        expiryDate = expiryDate,
                        isWeightedDisplayOnly = isWeightedDisplayOnly,
                        operator = session
                    )
                }
                val success = if (result.isSuccess) {
                    val saved = result.getOrNull()
                    syncFromRepository()
                    _uiState.update { state ->
                        val actionAr = if (productId == null) "تمت إضافة الصنف «${saved?.nameAr}» إلى الكتالوج" else "تم حفظ تعديلات الصنف «${saved?.nameAr}»"
                        val actionEn = if (productId == null) "Added product ${saved?.nameEn} to catalog" else "Saved changes for ${saved?.nameEn}"
                        state.copy(
                            selectedProductForDetail = saved ?: state.selectedProductForDetail,
                            lastInventoryActionMessageAr = actionAr,
                            lastInventoryActionMessageEn = actionEn,
                            isInventoryActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Product save failed"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastInventoryActionMessageAr = errAr,
                            lastInventoryActionMessageEn = errEn,
                            isInventoryActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Product save failed"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    fun saveCurrentBasketAsDraft(customTitle: String = ""): Boolean {
        val state = _uiState.value
        if (state.basket.isEmpty() || state.isPaymentUnverifiedHold || state.isSubmittingSale) return false
        val linkedCust = state.selectedCustomer
        val fallbackAr = linkedCust?.let { "مسودة: ${it.nameAr}" }
            ?: state.customerNote.trim().takeIf { it.isNotEmpty() }?.let { "مسودة: $it" }
            ?: "مسودة بيع (${WesternFormatters.formatInt(state.basketItemCount)} وحدات)"
        val fallbackEn = linkedCust?.let { "Draft: ${it.nameEn}" }
            ?: state.customerNote.trim().takeIf { it.isNotEmpty() }?.let { "Draft: $it" }
            ?: "Sale Draft (${WesternFormatters.formatInt(state.basketItemCount)} units)"
        val cleanTitle = customTitle.trim()
        val draft = SaleDraft(
            id = "draft_${System.currentTimeMillis()}",
            titleAr = cleanTitle.ifEmpty { fallbackAr },
            titleEn = cleanTitle.ifEmpty { fallbackEn },
            timestamp = WesternFormatters.currentTimestamp(),
            items = state.basket,
            customerId = state.selectedCustomerId,
            customerNote = state.customerNote,
            discountPreset = state.discountPreset,
            customDiscountText = state.customDiscountText,
            subtotalIls = state.basketSubtotalIls,
            netTotalIls = state.basketNetTotalIls
        )
        _uiState.update { current ->
            current.copy(
                savedDrafts = listOf(draft) + current.savedDrafts,
                basket = emptyList(),
                saleSessionId = current.saleSessionId + 1,
                discountPreset = DiscountPreset.NONE,
                customDiscountText = "",
                customerNote = "",
                selectedCustomerId = null,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
        showBanner(
            ar = "تم حفظ السلة كمسودة بيع: ${draft.titleAr}",
            en = "Saved basket as sale draft: ${draft.titleEn}",
            isError = false
        )
        return true
    }

    fun resumeSaleDraft(draftId: String): Boolean {
        val state = _uiState.value
        if (state.isPaymentUnverifiedHold || state.isSubmittingSale) return false
        val draft = state.savedDrafts.firstOrNull { it.id == draftId } ?: return false
        val restoredItems = draft.items.mapNotNull { item ->
            val freshProd = state.products.firstOrNull { it.id == item.product.id } ?: item.product
            if (freshProd.isStockTracked) {
                val allowedQty = item.quantity.coerceAtMost(freshProd.stockCountExact)
                if (allowedQty > BigDecimal.ZERO) item.copy(product = freshProd, quantity = allowedQty) else null
            } else {
                item.copy(product = freshProd)
            }
        }
        if (restoredItems.isEmpty()) {
            showBanner(
                ar = "تعذر استئناف المسودة لعدم توفر مخزون للأصناف المحفوظة",
                en = "Cannot resume draft: saved items are currently out of stock",
                isError = true
            )
            return false
        }
        // Preserve original draft discount intent without mutating persisted record
        val implicitDiscount = BigDecimal(draft.subtotalIls.toString()).subtract(BigDecimal(draft.netTotalIls.toString()))
        val (effectivePreset, effectiveCustomText) = if (draft.discountPreset == DiscountPreset.NONE &&
            draft.customDiscountText.isBlank() &&
            implicitDiscount > BigDecimal.ZERO
        ) {
            DiscountPreset.CUSTOM_ILS to implicitDiscount.stripTrailingZeros().toPlainString()
        } else {
            draft.discountPreset to draft.customDiscountText
        }

        _uiState.update { current ->
            current.copy(
                basket = restoredItems,
                saleSessionId = current.saleSessionId + 1,
                savedDrafts = current.savedDrafts.filterNot { it.id == draftId },
                selectedCustomerId = draft.customerId,
                customerNote = draft.customerNote,
                discountPreset = effectivePreset,
                customDiscountText = effectiveCustomText,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
        showBanner(
            ar = "تم استئناف المسودة: ${draft.titleAr}",
            en = "Resumed sale draft: ${draft.titleEn}",
            isError = false
        )
        return true
    }

    fun deleteSaleDraft(draftId: String) {
        _uiState.update { current ->
            current.copy(savedDrafts = current.savedDrafts.filterNot { it.id == draftId })
        }
    }

    fun formatQuotationShareText(): String {
        val state = _uiState.value
        if (state.basket.isEmpty()) return ""
        val lang = state.language
        val b = state.businessInfo
        val custName = state.selectedCustomer?.name(lang)
            ?: state.customerNote.trim().takeIf { it.isNotEmpty() }
            ?: lang.tr("زبون كريم", "Valued Customer")
        val sb = StringBuilder()
        sb.appendLine(b.name(lang))
        sb.appendLine("${b.city(lang)} • ${b.phone}")
        sb.appendLine("--------------------------------")
        sb.appendLine(lang.tr("عرض سعر تجاري (وثيقة غير مرحلة محاسبياً)", "Price Quotation (Non-Posting Commercial Document)"))
        sb.appendLine("${lang.tr("التاريخ", "Date")}: ${WesternFormatters.currentTimestamp()}")
        sb.appendLine("${lang.tr("موجه إلى", "Prepared for")}: $custName")
        sb.appendLine("--------------------------------")
        state.basket.forEachIndexed { idx, item ->
            sb.appendLine(
                "${idx + 1}. ${item.product.name(lang)} (${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)}) = ${WesternFormatters.formatIls(item.lineTotal)}"
            )
        }
        sb.appendLine("--------------------------------")
        if (state.hasDiscount) {
            sb.appendLine("${lang.tr("المجموع الفرعي", "Subtotal")}: ${WesternFormatters.formatIls(state.basketSubtotalExact)}")
            sb.appendLine("${lang.tr("الخصم المقترح", "Proposed Discount")}: -${WesternFormatters.formatIls(state.calculatedDiscountExact)}")
        }
        sb.appendLine("${lang.tr("الصافي الإجمالي لعرض السعر", "Quoted Net Total")}: ${WesternFormatters.formatIls(state.basketNetTotalExact)}")
        sb.appendLine(lang.tr("* ملاحظة: عرض السعر لا يخصم من المخزون ولا يقيد ذمة مالية حتى اعتماد البيع.", "* Note: This quotation does not deduct stock or post financial debt until confirmed as a sale."))
        return sb.toString().trim()
    }

    fun updateCustomer(
        customerId: String,
        name: String,
        phone: String,
        address: String,
        creditLimitIls: Double = 1000.0,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        return updateCustomer(
            customerId = customerId,
            name = name,
            phone = phone,
            address = address,
            updatePhone = true,
            updateAddress = true,
            updateCreditLimit = true,
            creditLimit = BigDecimal.valueOf(creditLimitIls),
            onComplete = onComplete
        )
    }

    fun updateCustomer(
        customerId: String,
        name: String,
        phone: String,
        address: String,
        updatePhone: Boolean,
        updateAddress: Boolean,
        updateCreditLimit: Boolean,
        creditLimit: BigDecimal?,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.updateCustomer(
                        customerId = customerId,
                        name = name,
                        phone = phone,
                        address = address,
                        updatePhone = updatePhone,
                        updateAddress = updateAddress,
                        updateCreditLimit = updateCreditLimit,
                        creditLimit = creditLimit
                    )
                }
                val success = if (result.isSuccess) {
                    val updated = result.getOrNull()
                    syncFromRepository()
                    _uiState.update { state ->
                        val currency = state.businessInfo.defaultCurrency
                        val limitDesc = if (updateCreditLimit) {
                            creditLimit?.let { " (${WesternFormatters.formatHumanMoney2dp(it, currency, state.language)})" } ?: " (${state.language.tr("غير محدد", "Not set")})"
                        } else ""
                        state.copy(
                            selectedCustomerForDetail = updated ?: state.selectedCustomerForDetail,
                            lastCustomerPaymentMessageAr = "تم تحديث بيانات الزبون «${updated?.nameAr}»$limitDesc",
                            lastCustomerPaymentMessageEn = "Updated customer ${updated?.nameEn}$limitDesc"
                        )
                    }
                    true
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Customer update failed"
                    showBanner(err, err, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.updateCustomer(
                        customerId = customerId,
                        name = name,
                        phone = phone,
                        address = address,
                        updatePhone = updatePhone,
                        updateAddress = updateAddress,
                        updateCreditLimit = updateCreditLimit,
                        creditLimit = creditLimit
                    )
                }
                val success = if (result.isSuccess) {
                    val updated = result.getOrNull()
                    syncFromRepository()
                    _uiState.update { state ->
                        val currency = state.businessInfo.defaultCurrency
                        val limitDesc = if (updateCreditLimit) {
                            creditLimit?.let { " (${WesternFormatters.formatHumanMoney2dp(it, currency, state.language)})" } ?: " (${state.language.tr("غير محدد", "Not set")})"
                        } else ""
                        state.copy(
                            selectedCustomerForDetail = updated ?: state.selectedCustomerForDetail,
                            lastCustomerPaymentMessageAr = "تم تحديث بيانات الزبون «${updated?.nameAr}»$limitDesc",
                            lastCustomerPaymentMessageEn = "Updated customer ${updated?.nameEn}$limitDesc"
                        )
                    }
                    true
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Customer update failed"
                    showBanner(err, err, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val err = e.message ?: "Customer update failed"
                showBanner(err, err, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    fun formatCustomerStatementText(customer: Customer): String {
        val state = _uiState.value
        val lang = state.language
        val b = state.businessInfo
        val txs = state.customerTransactions.filter { it.customerId == customer.id }
        val sb = StringBuilder()
        sb.appendLine(b.name(lang))
        sb.appendLine("${b.city(lang)} • ${b.phone}")
        sb.appendLine("--------------------------------")
        sb.appendLine(lang.tr("كشف حساب زبون تفصيلي", "Customer Account Statement"))
        sb.appendLine("${lang.tr("الزبون", "Customer")}: ${customer.name(lang)} (${customer.phone})")
        sb.appendLine("${lang.tr("تاريخ الكشف", "Statement Date")}: ${WesternFormatters.currentTimestamp()}")
        val currency = b.defaultCurrency
        val balExact = customer.balanceExact
        val balStr = when {
            balExact == null -> lang.tr("غير متاح (يتطلب تحديث كشف الحساب)", "Unavailable (statement refresh required)")
            balExact > BigDecimal.ZERO -> lang.tr(
                "ذمة مستحقة للمتجر: ${WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)}",
                "Balance Due to Store: ${WesternFormatters.formatHumanMoney2dp(balExact, currency, lang)}"
            )
            balExact < BigDecimal.ZERO -> lang.tr(
                "رصيد مقدم للزبون: ${WesternFormatters.formatHumanMoney2dp(balExact.abs(), currency, lang)}",
                "Customer Advance Credit: ${WesternFormatters.formatHumanMoney2dp(balExact.abs(), currency, lang)}"
            )
            else -> lang.tr(
                "الحساب خالص (${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)})",
                "Settled Account (${WesternFormatters.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)})"
            )
        }
        sb.appendLine("${lang.tr("الرصيد الحالي", "Current Balance")}: $balStr")
        val limitDisplay = customer.creditLimitExact?.let { WesternFormatters.formatHumanMoney2dp(it, currency, lang) }
            ?: lang.tr("غير محدد", "Not set")
        sb.appendLine("${lang.tr("سقف الائتمان", "Credit Limit")}: $limitDisplay")
        sb.appendLine("--------------------------------")
        txs.take(10).forEach { tx ->
            val txCurr = tx.currency
            val mov = if (tx.debitExact > BigDecimal.ZERO && tx.creditExact <= BigDecimal.ZERO) {
                "+${WesternFormatters.formatHumanMoney2dp(tx.debitExact, txCurr, lang)}"
            } else if (tx.creditExact > BigDecimal.ZERO && tx.debitExact <= BigDecimal.ZERO) {
                "-${WesternFormatters.formatHumanMoney2dp(tx.creditExact, txCurr, lang)}"
            } else if (tx.debitExact > BigDecimal.ZERO && tx.creditExact > BigDecimal.ZERO && tx.debitExact > tx.creditExact) {
                val netDebit = tx.debitExact.subtract(tx.creditExact)
                lang.tr(
                    "إجمالي ${WesternFormatters.formatHumanMoney2dp(tx.debitExact, txCurr, lang)} / مدفوع ${WesternFormatters.formatHumanMoney2dp(tx.creditExact, txCurr, lang)} / متبقي +${WesternFormatters.formatHumanMoney2dp(netDebit, txCurr, lang)}",
                    "Total ${WesternFormatters.formatHumanMoney2dp(tx.debitExact, txCurr, lang)} / Paid ${WesternFormatters.formatHumanMoney2dp(tx.creditExact, txCurr, lang)} / Rem +${WesternFormatters.formatHumanMoney2dp(netDebit, txCurr, lang)}"
                )
            } else {
                WesternFormatters.formatHumanMoney2dp(tx.debitExact, txCurr, lang)
            }
            val runBalStr = tx.runningBalanceExact?.let { WesternFormatters.formatHumanMoney2dp(it, txCurr, lang) }
                ?: lang.tr("غير متاح", "Unavailable")
            sb.appendLine("${tx.date} | ${tx.type(lang)} | $mov | ${lang.tr("الرصيد", "Bal")}: $runBalStr")
        }
        return sb.toString().trim()
    }

    fun saveSupplier(
        supplierId: String?,
        name: String,
        company: String,
        phone: String,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (!acquireActionLock()) {
            onComplete(false)
            return false
        }

        val opId = if (supplierId == null) _uiState.value.draftSupplierCreateOperationId else ""
        if (repository is OfflineDemoTraderRepository) {
            try {
                val session = _uiState.value.currentSession
                val result = kotlinx.coroutines.runBlocking {
                    repository.saveSupplier(supplierId, name, company, phone, session, opId)
                }
                val success = if (result.isSuccess) {
                    val saved = result.getOrNull()
                    syncFromRepository()
                    val nextOpId = if (supplierId == null) repository.rotateDraftOperationId("supplier_create") else _uiState.value.draftSupplierCreateOperationId
                    _uiState.update { state ->
                        val msgAr = if (supplierId == null) "تمت إضافة المورد «${saved?.nameAr}»" else "تم تحديث بيانات المورد «${saved?.nameAr}»"
                        val msgEn = if (supplierId == null) "Added supplier ${saved?.nameEn}" else "Updated supplier ${saved?.nameEn}"
                        state.copy(
                            draftSupplierCreateOperationId = nextOpId,
                            activeSupplierCreateDraft = if (supplierId == null) null else state.activeSupplierCreateDraft,
                            isSupplierCreateRecovering = if (supplierId == null) false else state.isSupplierCreateRecovering,
                            selectedSupplierForDetail = saved ?: state.selectedSupplierForDetail,
                            lastSupplierActionMessageAr = msgAr,
                            lastSupplierActionMessageEn = msgEn,
                            isSupplierActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Supplier save failed"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
                return success
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val session = _uiState.value.currentSession
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.saveSupplier(supplierId, name, company, phone, session, opId)
                }
                val success = if (result.isSuccess) {
                    val saved = result.getOrNull()
                    syncFromRepository()
                    val nextOpId = if (supplierId == null) repository.rotateDraftOperationId("supplier_create") else _uiState.value.draftSupplierCreateOperationId
                    _uiState.update { state ->
                        val msgAr = if (supplierId == null) "تمت إضافة المورد «${saved?.nameAr}»" else "تم تحديث بيانات المورد «${saved?.nameAr}»"
                        val msgEn = if (supplierId == null) "Added supplier ${saved?.nameEn}" else "Updated supplier ${saved?.nameEn}"
                        state.copy(
                            draftSupplierCreateOperationId = nextOpId,
                            activeSupplierCreateDraft = if (supplierId == null) null else state.activeSupplierCreateDraft,
                            isSupplierCreateRecovering = if (supplierId == null) false else state.isSupplierCreateRecovering,
                            selectedSupplierForDetail = saved ?: state.selectedSupplierForDetail,
                            lastSupplierActionMessageAr = msgAr,
                            lastSupplierActionMessageEn = msgEn,
                            isSupplierActionError = false
                        )
                    }
                    true
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Supplier save failed"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                    false
                }
                onComplete(success)
            } catch (e: Exception) {
                val rawErr = e.message ?: "Supplier save failed"
                val parts = rawErr.split(" | ", limit = 2)
                val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                _uiState.update {
                    it.copy(
                        lastSupplierActionMessageAr = errAr,
                        lastSupplierActionMessageEn = errEn,
                        isSupplierActionError = true
                    )
                }
                showBanner(errAr, errEn, isError = true)
                onComplete(false)
            } finally {
                releaseActionLock()
            }
        }
        return false
    }

    private var supplierSearchJob: Job? = null
    private var supplierSearchGeneration = 0L

    fun searchSuppliers(query: String) {
        if (!ensureActiveScope()) return
        supplierSearchJob?.cancel()
        val trimmed = query.trim()
        val generation = ++supplierSearchGeneration
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(
                    supplierSearchQuery = query,
                    supplierSearchResults = null,
                    isSearchingSuppliers = false,
                    supplierSearchError = null
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                supplierSearchQuery = query,
                supplierSearchResults = null,
                isSearchingSuppliers = true,
                supplierSearchError = null
            )
        }
        supplierSearchJob = viewModelScope.launch {
            delay(250)
            if (!ensureActiveScope()) return@launch
            try {
                val res = repository.searchSuppliers(trimmed)
                if (!ensureActiveScope()) return@launch
                if (generation == supplierSearchGeneration) {
                    if (res.isSuccess) {
                        _uiState.update {
                            it.copy(
                                supplierSearchResults = res.getOrThrow(),
                                isSearchingSuppliers = false,
                                supplierSearchError = null
                            )
                        }
                    } else {
                        val errMsg = res.exceptionOrNull()?.message ?: "Supplier search failed"
                        _uiState.update {
                            it.copy(
                                supplierSearchResults = emptyList(),
                                isSearchingSuppliers = false,
                                supplierSearchError = errMsg
                            )
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!ensureActiveScope()) return@launch
                if (generation == supplierSearchGeneration) {
                    _uiState.update {
                        it.copy(
                            supplierSearchResults = emptyList(),
                            isSearchingSuppliers = false,
                            supplierSearchError = e.message
                        )
                    }
                }
            }
        }
    }

    private var customerSearchJob: Job? = null
    private var customerSearchGeneration = 0L

    fun searchCustomers(query: String) {
        if (!ensureActiveScope()) return
        customerSearchJob?.cancel()
        val trimmed = query.trim()
        val generation = ++customerSearchGeneration
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(
                    customerSearchQuery = query,
                    customerSearchResults = null,
                    isSearchingCustomers = false,
                    customerSearchError = null
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                customerSearchQuery = query,
                customerSearchResults = null,
                isSearchingCustomers = true,
                customerSearchError = null
            )
        }
        customerSearchJob = viewModelScope.launch {
            delay(250)
            if (!ensureActiveScope()) return@launch
            try {
                val res = repository.searchCustomers(trimmed)
                if (!ensureActiveScope()) return@launch
                if (generation == customerSearchGeneration) {
                    if (res.isSuccess) {
                        _uiState.update {
                            it.copy(
                                customerSearchResults = res.getOrThrow(),
                                isSearchingCustomers = false,
                                customerSearchError = null
                            )
                        }
                    } else {
                        val errMsg = res.exceptionOrNull()?.message ?: "Customer search failed"
                        _uiState.update {
                            it.copy(
                                customerSearchResults = emptyList(),
                                isSearchingCustomers = false,
                                customerSearchError = errMsg
                            )
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!ensureActiveScope()) return@launch
                if (generation == customerSearchGeneration) {
                    _uiState.update {
                        it.copy(
                            customerSearchResults = emptyList(),
                            isSearchingCustomers = false,
                            customerSearchError = e.message
                        )
                    }
                }
            }
        }
    }

    private var productSearchJob: Job? = null
    private var productSearchGeneration = 0L

    fun searchProductsOnServer(query: String) {
        if (!ensureActiveScope()) return
        val session = _uiState.value.currentSession
        if (session.isDemoSession) {
            productSearchJob?.cancel()
            productSearchGeneration++
            _uiState.update {
                it.copy(
                    productSearchQuery = query,
                    productSearchResults = null,
                    isSearchingProducts = false,
                    productSearchError = null
                )
            }
            return
        }
        productSearchJob?.cancel()
        val trimmed = query.trim()
        val generation = ++productSearchGeneration
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(
                    productSearchQuery = "",
                    productSearchResults = null,
                    isSearchingProducts = false,
                    productSearchError = null
                )
            }
            return
        }
        _uiState.update { state ->
            val committed = state.productSearchQuery.trim()
            val canKeepDuringDebounce = state.productSearchResults != null &&
                committed.isNotEmpty() &&
                (trimmed.startsWith(committed, ignoreCase = true) ||
                    committed.startsWith(trimmed, ignoreCase = true))
            state.copy(
                productSearchQuery = if (canKeepDuringDebounce) state.productSearchQuery else "",
                productSearchResults = if (canKeepDuringDebounce) state.productSearchResults else null,
                isSearchingProducts = true,
                productSearchError = null
            )
        }
        productSearchJob = viewModelScope.launch {
            delay(250)
            if (!ensureActiveScope()) return@launch
            try {
                val res = repository.searchProductsOnServer(trimmed, limit = 30)
                if (!ensureActiveScope()) return@launch
                if (generation == productSearchGeneration) {
                    if (res.isSuccess) {
                        _uiState.update {
                            it.copy(
                                productSearchQuery = query,
                                productSearchResults = res.getOrThrow(),
                                isSearchingProducts = false,
                                productSearchError = null
                            )
                        }
                    } else {
                        val errMsg = res.exceptionOrNull()?.message ?: "Product search failed"
                        _uiState.update {
                            it.copy(
                                productSearchQuery = query,
                                productSearchResults = emptyList(),
                                isSearchingProducts = false,
                                productSearchError = errMsg
                            )
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!ensureActiveScope()) return@launch
                if (generation == productSearchGeneration) {
                    _uiState.update {
                        it.copy(
                            productSearchQuery = query,
                            productSearchResults = emptyList(),
                            isSearchingProducts = false,
                            productSearchError = e.message
                        )
                    }
                }
            }
        }
    }

    fun confirmPhysicalGoodsReceiptResult(
        billId: String,
        productId: String,
        receivedQty: BigDecimal,
        batchNote: String = "",
        operationId: String = "",
        lineId: String? = null,
        onComplete: (Result<StockMovement>) -> Unit = {}
    ): Result<StockMovement> {
        if (!acquireActionLock()) {
            val err = Result.failure<StockMovement>(IllegalStateException("Action in progress"))
            onComplete(err)
            return err
        }

        val opId = if (operationId.isNotBlank()) operationId else _uiState.value.draftGrnOperationId
        val session = _uiState.value.currentSession

        if (repository is OfflineDemoTraderRepository) {
            try {
                val result = kotlinx.coroutines.runBlocking {
                    repository.confirmPhysicalGoodsReceipt(billId, productId, receivedQty, batchNote, session, opId, lineId)
                }
                if (result.isSuccess) {
                    val movement = result.getOrNull()
                    val updatedProducts = repository.getProducts()
                    val updatedSuppliers = repository.getSuppliers()
                    val updatedBills = repository.getPurchaseBills()
                    val prod = updatedProducts.firstOrNull { it.id == productId }
                    val resultingStock = movement?.resultingStockExact ?: BigDecimal.ZERO
                    _uiState.update { state ->
                        state.copy(
                            draftGrnOperationId = repository.rotateDraftOperationId("goods_receipt_note"),
                            products = updatedProducts,
                            stockMovements = repository.getStockMovements(),
                            suppliers = updatedSuppliers,
                            purchaseBills = updatedBills,
                            supplierPayments = repository.getSupplierPayments(),
                            lastSupplierActionMessageAr = "تم اعتماد سند الاستلام المخزني (+${WesternFormatters.formatQuantity(receivedQty)} من «${prod?.nameAr ?: ""}») — المخزون الحالي: ${WesternFormatters.formatQuantity(resultingStock)}",
                            lastSupplierActionMessageEn = "Physical Goods Receipt confirmed (+${WesternFormatters.formatQuantity(receivedQty)} of \"${prod?.nameEn ?: ""}\") — Stock: ${WesternFormatters.formatQuantity(resultingStock)}",
                            isSupplierActionError = false
                        )
                    }
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Goods receipt rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                }
                onComplete(result)
                return result
            } finally {
                releaseActionLock()
            }
        }

        viewModelScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    repository.confirmPhysicalGoodsReceipt(billId, productId, receivedQty, batchNote, session, opId, lineId)
                }
                if (result.isSuccess) {
                    val movement = result.getOrNull()
                    val updatedProducts = repository.getProducts()
                    val updatedSuppliers = repository.getSuppliers()
                    val updatedBills = repository.getPurchaseBills()
                    val prod = updatedProducts.firstOrNull { it.id == productId }
                    val resultingStock = movement?.resultingStockExact ?: BigDecimal.ZERO
                    _uiState.update { state ->
                        state.copy(
                            draftGrnOperationId = repository.rotateDraftOperationId("goods_receipt_note"),
                            products = updatedProducts,
                            stockMovements = repository.getStockMovements(),
                            suppliers = updatedSuppliers,
                            purchaseBills = updatedBills,
                            supplierPayments = repository.getSupplierPayments(),
                            lastSupplierActionMessageAr = "تم اعتماد سند الاستلام المخزني (+${WesternFormatters.formatQuantity(receivedQty)} من «${prod?.nameAr ?: ""}») — المخزون الحالي: ${WesternFormatters.formatQuantity(resultingStock)}",
                            lastSupplierActionMessageEn = "Physical Goods Receipt confirmed (+${WesternFormatters.formatQuantity(receivedQty)} of \"${prod?.nameEn ?: ""}\") — Stock: ${WesternFormatters.formatQuantity(resultingStock)}",
                            isSupplierActionError = false
                        )
                    }
                } else {
                    val rawErr = result.exceptionOrNull()?.message ?: "Goods receipt rejected"
                    val parts = rawErr.split(" | ", limit = 2)
                    val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
                    _uiState.update {
                        it.copy(
                            lastSupplierActionMessageAr = errAr,
                            lastSupplierActionMessageEn = errEn,
                            isSupplierActionError = true
                        )
                    }
                    showBanner(errAr, errEn, isError = true)
                }
                onComplete(result)
            } catch (e: Exception) {
                val err = Result.failure<StockMovement>(e)
                val msg = e.message ?: "Goods receipt rejected"
                showBanner(msg, msg, isError = true)
                onComplete(err)
            } finally {
                releaseActionLock()
            }
        }
        return Result.failure(IllegalStateException("Action in progress"))
    }

    fun confirmPhysicalGoodsReceiptResult(
        billId: String,
        productId: String,
        receivedQty: Int,
        batchNote: String = "",
        operationId: String = "",
        lineId: String? = null,
        onComplete: (Result<StockMovement>) -> Unit = {}
    ): Result<StockMovement> = confirmPhysicalGoodsReceiptResult(billId, productId, BigDecimal(receivedQty), batchNote, operationId, lineId, onComplete)

    fun confirmPhysicalGoodsReceipt(
        billId: String,
        productId: String,
        receivedQty: Int,
        batchNote: String = "",
        operationId: String = "",
        lineId: String? = null,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        val result = confirmPhysicalGoodsReceiptResult(billId, productId, BigDecimal(receivedQty), batchNote, operationId, lineId) { res ->
            onComplete(res.isSuccess)
        }
        return result.isSuccess
    }

    fun updateStartingDrawerCash(openingIls: Double): Boolean {
        val session = _uiState.value.currentSession
        if (!session.canViewReports()) return false
        if (openingIls < 0.0) return false
        val exactOpening = BigDecimal(openingIls.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
        _uiState.update {
            it.copy(
                startingDrawerCashIls = exactOpening,
                isShiftClosedPreview = false,
                shiftClosedTimestamp = null
            )
        }
        return true
    }

    private var customerSettlementJob: Job? = null
    private var customerSettlementGeneration = 0L

    fun loadCustomerSettlementTargets(customerId: String, resetPagination: Boolean = false): Job {
        if (customerId.isBlank()) return Job().apply { complete() }
        if (resetPagination) {
            customerSettlementJob?.cancel()
        }
        val generation = if (resetPagination) ++customerSettlementGeneration else customerSettlementGeneration
        val currentCursor = if (resetPagination) null else _uiState.value.customerSettlementTargetsNextCursor

        _uiState.update { state ->
            state.copy(
                isLoadingCustomerSettlementTargets = true,
                customerSettlementTargetsError = null,
                customerSettlementTargets = if (resetPagination) emptyList() else state.customerSettlementTargets,
                customerSettlementTargetsNextCursor = if (resetPagination) null else state.customerSettlementTargetsNextCursor,
                customerSettlementTargetsHasMore = if (resetPagination) false else state.customerSettlementTargetsHasMore
            )
        }

        val job = viewModelScope.launch {
            try {
                val res = repository.getCustomerSettlementTargets(
                    customerId = customerId,
                    currency = productionRepository?.baseCurrency,
                    limit = 50,
                    cursor = currentCursor
                )
                if (generation == customerSettlementGeneration) {
                    if (res.isSuccess) {
                        val data = res.getOrThrow()
                        _uiState.update { state ->
                            val existing = if (resetPagination) emptyList() else state.customerSettlementTargets
                            val existingKeys = existing.map { "${it.targetType}:${it.targetId}" }.toSet()
                            val newTargets = data.targets.filter { "${it.targetType}:${it.targetId}" !in existingKeys }
                            state.copy(
                                customerSettlementTargets = existing + newTargets,
                                customerSettlementTargetsHasMore = data.hasMore,
                                customerSettlementTargetsNextCursor = data.nextCursor,
                                isLoadingCustomerSettlementTargets = false,
                                customerSettlementTargetsError = null
                            )
                        }
                    } else {
                        val ex = res.exceptionOrNull()
                        val err = ex?.message ?: "Failed to load customer settlement targets"
                        _uiState.update { state ->
                            state.copy(
                                isLoadingCustomerSettlementTargets = false,
                                customerSettlementTargetsError = err
                            )
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (generation == customerSettlementGeneration) {
                    _uiState.update { state ->
                        state.copy(
                            isLoadingCustomerSettlementTargets = false,
                            customerSettlementTargetsError = e.message ?: "Error loading customer settlement targets"
                        )
                    }
                }
            }
        }
        customerSettlementJob = job
        return job
    }

    fun resetCustomerSettlementTargets() {
        customerSettlementJob?.cancel()
        customerSettlementGeneration++
        _uiState.update { state ->
            state.copy(
                customerSettlementTargets = emptyList(),
                isLoadingCustomerSettlementTargets = false,
                customerSettlementTargetsError = null,
                customerSettlementTargetsHasMore = false,
                customerSettlementTargetsNextCursor = null
            )
        }
    }

    private var supplierSettlementJob: Job? = null
    private var supplierSettlementGeneration = 0L

    fun loadSupplierSettlementTargets(supplierId: String, resetPagination: Boolean = false): Job {
        if (supplierId.isBlank()) return Job().apply { complete() }
        if (resetPagination) {
            supplierSettlementJob?.cancel()
        }
        val generation = if (resetPagination) ++supplierSettlementGeneration else supplierSettlementGeneration
        val currentCursor = if (resetPagination) null else _uiState.value.supplierSettlementTargetsNextCursor

        _uiState.update { state ->
            state.copy(
                isLoadingSupplierSettlementTargets = true,
                supplierSettlementTargetsError = null,
                supplierSettlementTargets = if (resetPagination) emptyList() else state.supplierSettlementTargets,
                supplierSettlementTargetsNextCursor = if (resetPagination) null else state.supplierSettlementTargetsNextCursor,
                supplierSettlementTargetsHasMore = if (resetPagination) false else state.supplierSettlementTargetsHasMore
            )
        }

        val job = viewModelScope.launch {
            try {
                val res = repository.getSupplierSettlementTargets(
                    supplierId = supplierId,
                    currency = productionRepository?.baseCurrency,
                    limit = 50,
                    cursor = currentCursor
                )
                if (generation == supplierSettlementGeneration) {
                    if (res.isSuccess) {
                        val data = res.getOrThrow()
                        _uiState.update { state ->
                            val existing = if (resetPagination) emptyList() else state.supplierSettlementTargets
                            val existingKeys = existing.map { "${it.targetType}:${it.targetId}" }.toSet()
                            val newTargets = data.targets.filter { "${it.targetType}:${it.targetId}" !in existingKeys }
                            state.copy(
                                supplierSettlementTargets = existing + newTargets,
                                supplierSettlementTargetsHasMore = data.hasMore,
                                supplierSettlementTargetsNextCursor = data.nextCursor,
                                isLoadingSupplierSettlementTargets = false,
                                supplierSettlementTargetsError = null
                            )
                        }
                    } else {
                        val ex = res.exceptionOrNull()
                        val rawErr = ex?.message ?: "Failed to load supplier settlement targets"
                        val isCostDenied = rawErr.contains("403") || rawErr.contains("FORBIDDEN") || rawErr.contains("purchasing cost")
                        val err = if (isCostDenied) {
                            "غير مصرح: استعراض الذمم المالية للموردين يتطلب صلاحية تكلفة المشتريات | Permission denied: Viewing supplier financial settlement targets requires purchasing cost permission"
                        } else {
                            rawErr
                        }
                        _uiState.update { state ->
                            state.copy(
                                isLoadingSupplierSettlementTargets = false,
                                supplierSettlementTargetsError = err
                            )
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (generation == supplierSettlementGeneration) {
                    _uiState.update { state ->
                        state.copy(
                            isLoadingSupplierSettlementTargets = false,
                            supplierSettlementTargetsError = e.message ?: "Error loading supplier settlement targets"
                        )
                    }
                }
            }
        }
        supplierSettlementJob = job
        return job
    }

    fun resetSupplierSettlementTargets() {
        supplierSettlementJob?.cancel()
        supplierSettlementGeneration++
        _uiState.update { state ->
            state.copy(
                supplierSettlementTargets = emptyList(),
                isLoadingSupplierSettlementTargets = false,
                supplierSettlementTargetsError = null,
                supplierSettlementTargetsHasMore = false,
                supplierSettlementTargetsNextCursor = null
            )
        }
    }

    fun updateCountedPhysicalCashText(rawInput: String) {
        val sanitized = WesternFormatters.sanitizeWesternDigits(rawInput)
        _uiState.update {
            it.copy(countedPhysicalCashText = sanitized)
        }
    }

    fun toggleShiftClosePreview(closed: Boolean) {
        if (!_uiState.value.currentSession.isDemoSession) {
            openShiftCloseDialog(closed)
            return
        }
        _uiState.update {
            it.copy(
                isShiftClosedPreview = closed,
                shiftClosedTimestamp = if (closed) WesternFormatters.currentTimestamp() else null
            )
        }
    }

    fun openShiftCloseDialog(open: Boolean) {
        if (open) {
            val draftId = repository.getDraftOperationId("shift_close")
            val payload = repository.getActiveDraftPayload("shift_close")
            val isRecovering = repository.isDraftRecovering("shift_close")
            val recCount = payload?.properties?.get("counted_cash")?.asString()
            val recNotes = payload?.properties?.get("notes")?.asString()
            _uiState.update { current ->
                val isDemo = current.currentSession.isDemoSession
                val countInput = when {
                    isRecovering && recCount != null -> recCount
                    current.shiftCloseCountedCashInput.isNotBlank() -> current.shiftCloseCountedCashInput
                    recCount != null -> recCount
                    !isDemo -> "" // Production requires explicitly entered actual count: no book/demo copy or defaults
                    else -> current.countedPhysicalCashText
                }
                val notesInput = when {
                    isRecovering && recNotes != null -> recNotes
                    current.shiftCloseNotesInput.isNotBlank() -> current.shiftCloseNotesInput
                    recNotes != null -> recNotes
                    else -> ""
                }
                current.copy(
                    isShiftCloseDialogOpen = true,
                    draftShiftCloseOperationId = draftId,
                    activeShiftCloseDraft = payload,
                    isShiftCloseRecovering = isRecovering,
                    shiftCloseCountedCashInput = countInput,
                    shiftCloseNotesInput = notesInput,
                    shiftCloseError = null
                )
            }
        } else {
            _uiState.update { current ->
                current.copy(
                    isShiftCloseDialogOpen = false,
                    shiftCloseError = null
                )
            }
        }
    }

    fun updateShiftCloseCountedCash(text: String) {
        if (_uiState.value.isShiftCloseRecovering) return
        val sanitized = WesternFormatters.sanitizeWesternDigits(text)
        _uiState.update {
            it.copy(shiftCloseCountedCashInput = sanitized, shiftCloseError = null)
        }
    }

    fun updateShiftCloseNotes(text: String) {
        if (_uiState.value.isShiftCloseRecovering) return
        _uiState.update {
            it.copy(shiftCloseNotesInput = text)
        }
    }

    fun closeShift(
        countedCashText: String? = null,
        notesText: String? = null,
        commandId: String? = null,
        onComplete: ((Result<com.example.data.mobile.merchantread.ShiftCloseResponse>) -> Unit)? = null
    ) {
        val current = _uiState.value
        val effectiveCommandId = when {
            !commandId.isNullOrBlank() -> commandId
            current.draftShiftCloseOperationId.isNotBlank() -> current.draftShiftCloseOperationId
            else -> repository.getDraftOperationId("shift_close")
        }

        val rawCount = (countedCashText ?: current.shiftCloseCountedCashInput).trim()
        val effectiveNotes = notesText ?: current.shiftCloseNotesInput.ifBlank { null }

        val activePayload = repository.getActiveDraftPayload("shift_close")
        val isRecovering = repository.isDraftRecovering("shift_close")
        if (isRecovering && activePayload != null) {
            val origCount = activePayload.properties["counted_cash"]?.asString() ?: ""
            val origNotes = activePayload.properties["notes"]?.asString() ?: ""
            if (rawCount != origCount || (effectiveNotes ?: "") != origNotes) {
                val err = current.language.tr(
                    "لا يمكن تعديل بيانات جرد معلق قيد الإرسال. جاري استعادة القيم الأصلية",
                    "Cannot modify parameters of a pending in-flight shift close. Restoring original values"
                )
                _uiState.update {
                    it.copy(
                        shiftCloseCountedCashInput = origCount,
                        shiftCloseNotesInput = origNotes,
                        shiftCloseError = err
                    )
                }
                onComplete?.invoke(Result.failure(IllegalStateException(err)))
                return
            }
        }

        if (rawCount.isEmpty()) {
            val err = current.language.tr("يرجى إدخال المبلغ الفعلي بالدرج", "Please enter counted cash in drawer")
            _uiState.update { it.copy(shiftCloseError = err) }
            onComplete?.invoke(Result.failure(IllegalArgumentException(err)))
            return
        }

        val parsed = try {
            BigDecimal(rawCount)
        } catch (_: Exception) {
            val err = current.language.tr("المبلغ المدخل غير صالح", "Invalid cash amount")
            _uiState.update { it.copy(shiftCloseError = err) }
            onComplete?.invoke(Result.failure(IllegalArgumentException(err)))
            return
        }

        if (parsed.signum() < 0) {
            val err = current.language.tr("لا يمكن أن يكون المبلغ سالباً", "Counted cash cannot be negative")
            _uiState.update { it.copy(shiftCloseError = err) }
            onComplete?.invoke(Result.failure(IllegalArgumentException(err)))
            return
        }

        val minorUnits = current.liveShiftSummary?.currencyMinorUnits ?: current.businessInfo.defaultCurrencyMinorUnits
        if (parsed.scale() > minorUnits) {
            val err = current.language.tr(
                "دقة المبلغ تتجاوز الحد المسموح ($minorUnits خانات)",
                "Counted cash precision exceeds $minorUnits decimal places"
            )
            _uiState.update { it.copy(shiftCloseError = err) }
            onComplete?.invoke(Result.failure(IllegalArgumentException(err)))
            return
        }

        if (current.isShiftCloseSubmitting) {
            return
        }

        _uiState.update {
            it.copy(
                isShiftCloseSubmitting = true,
                shiftCloseError = null,
                draftShiftCloseOperationId = effectiveCommandId,
                shiftCloseCountedCashInput = rawCount,
                shiftCloseNotesInput = effectiveNotes ?: ""
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.closeShift(
                countedCash = parsed,
                notes = effectiveNotes,
                commandId = effectiveCommandId
            )
            if (result.isSuccess) {
                val snapshot = result.getOrThrow()
                val nextDraftId = repository.getDraftOperationId("shift_close")
                _uiState.update {
                    it.copy(
                        isShiftCloseSubmitting = false,
                        isShiftCloseDialogOpen = false,
                        isShiftCloseRecovering = false,
                        activeShiftCloseDraft = null,
                        draftShiftCloseOperationId = nextDraftId,
                        shiftCloseSuccessSnapshot = snapshot,
                        shiftClosedTimestamp = if (snapshot.isShiftClosed) (snapshot.closedAt ?: snapshot.observedAt) else null,
                        shiftObservedTimestamp = snapshot.observedAt ?: snapshot.closedAt,
                        shiftCloseCountedCashInput = "",
                        shiftCloseNotesInput = "",
                        shiftCloseError = null
                    )
                }
                showBanner(
                    ar = if (snapshot.isShiftClosed) "تم إغلاق الوردية بنجاح (${snapshot.varianceStatus})" else "تم تسجيل جرد الصندوق ومطابقة الوردية بنجاح (${snapshot.varianceStatus})",
                    en = if (snapshot.isShiftClosed) "Shift closed successfully (${snapshot.varianceStatus})" else "Cash count and shift reconciliation recorded successfully (${snapshot.varianceStatus})"
                )
                onComplete?.invoke(Result.success(snapshot))
            } else {
                val ex = result.exceptionOrNull() ?: RuntimeException("Failed to record shift count")
                val errMsg = ex.message ?: "Failed to record shift count"
                val isStillRecovering = repository.isDraftRecovering("shift_close")
                val activeDraft = repository.getActiveDraftPayload("shift_close")
                _uiState.update {
                    it.copy(
                        isShiftCloseSubmitting = false,
                        isShiftCloseRecovering = isStillRecovering,
                        activeShiftCloseDraft = activeDraft,
                        shiftCloseError = errMsg
                    )
                }
                showBanner(
                    ar = "فشل تسجيل جرد الوردية: $errMsg",
                    en = "Shift count failed: $errMsg",
                    isError = true
                )
                onComplete?.invoke(Result.failure(ex))
            }
        }
    }

    fun startNewShiftWorkflow() {
        if (_uiState.value.isShiftCloseSubmitting || _uiState.value.isShiftCloseRecovering ||
            repository.getActiveDraftPayload("shift_close") != null) {
            return
        }
        val nextDraftId = repository.rotateDraftOperationId("shift_close")
        _uiState.update {
            it.copy(
                draftShiftCloseOperationId = nextDraftId,
                isShiftCloseRecovering = false,
                activeShiftCloseDraft = null,
                shiftCloseCountedCashInput = "",
                shiftCloseNotesInput = "",
                shiftCloseSuccessSnapshot = null,
                shiftClosedTimestamp = null,
                isShiftClosedPreview = false,
                shiftCloseError = null
            )
        }
    }

    private fun showBanner(ar: String, en: String, isError: Boolean = false) {
        _uiState.update {
            it.copy(
                lastScannedBannerAr = ar,
                lastScannedBannerEn = en,
                isBannerError = isError
            )
        }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(4000)
            _uiState.update {
                it.copy(lastScannedBannerAr = null, lastScannedBannerEn = null, isBannerError = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}
