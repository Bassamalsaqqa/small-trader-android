package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfflineDemoTraderRepository
import com.example.data.SampleCatalog
import com.example.data.TraderRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TraderUiState(
    val language: AppLanguage = AppLanguage.AR,
    val isDarkTheme: Boolean = false,
    val currentScreen: TraderScreen = TraderScreen.HOME,
    val screenHistory: List<TraderScreen> = emptyList(),

    // Business & Authentication
    val currentSession: UserSession = SampleCatalog.demoStaff.first(),
    val availableUsers: List<UserSession> = SampleCatalog.demoStaff,
    val businessInfo: BusinessInfo = SampleCatalog.defaultBusiness,

    // Products & Inventory
    val products: List<Product> = emptyList(),
    val stockMovements: List<StockMovement> = emptyList(),
    val selectedProductForDetail: Product? = null,
    val searchQuery: String = "",
    val selectedCategory: ProductCategory = ProductCategory.ALL,
    val lastInventoryActionMessageAr: String? = null,
    val lastInventoryActionMessageEn: String? = null,
    val isInventoryActionError: Boolean = false,

    // Active Basket & POS
    val basket: List<BasketItem> = emptyList(),
    val discountPreset: DiscountPreset = DiscountPreset.NONE,
    val customDiscountText: String = "",
    val customerNote: String = "",
    val selectedCustomerId: String? = null,
    val cashInputText: String = "",
    val isSubmittingSale: Boolean = false,
    val checkoutErrorAr: String? = null,
    val checkoutErrorEn: String? = null,

    // Invoices & Accounting
    val invoices: List<InvoiceRecord> = emptyList(),
    val activeReceipt: InvoiceRecord? = null,
    val isNewlyCompletedSale: Boolean = false,
    val receiptShareCount: Int = 0,
    val receiptCopyCount: Int = 0,
    val lastSharedReceiptText: String? = null,
    val startingDrawerCashIls: Double = 350.00,

    // Customers & Balances
    val customers: List<Customer> = emptyList(),
    val customerTransactions: List<CustomerTransaction> = emptyList(),
    val selectedCustomerForDetail: Customer? = null,
    val lastCustomerPaymentMessageAr: String? = null,
    val lastCustomerPaymentMessageEn: String? = null,

    // Suppliers & Purchasing
    val suppliers: List<Supplier> = emptyList(),
    val purchaseBills: List<PurchaseBill> = emptyList(),
    val supplierPayments: List<SupplierPayment> = emptyList(),
    val selectedSupplierForDetail: Supplier? = null,
    val lastSupplierActionMessageAr: String? = null,
    val lastSupplierActionMessageEn: String? = null,
    val isSupplierActionError: Boolean = false,

    // Expenses
    val expenses: List<ExpenseEntry> = emptyList(),
    val lastExpenseActionMessageAr: String? = null,
    val lastExpenseActionMessageEn: String? = null,
    val isExpenseActionError: Boolean = false,

    // Hardware: Barcode scanner simulation + Camera status
    val isScannerSheetOpen: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val manualBarcodeInput: String = "",
    val lastScannedBannerAr: String? = null,
    val lastScannedBannerEn: String? = null,
    val isBannerError: Boolean = false,
    val scanCountSession: Int = 0,

    // Dialog states
    val isCustomItemDialogOpen: Boolean = false,
    val isPaymentCollectionDialogOpen: Boolean = false,
    val isAddCustomerDialogOpen: Boolean = false,
    val isAddExpenseDialogOpen: Boolean = false,
    val isReceivePurchaseDialogOpen: Boolean = false,

    // Hardware: Simulated Bluetooth printer state
    val isPrinterSheetOpen: Boolean = false,
    val availablePrinters: List<BluetoothPrinterDevice> = SampleCatalog.simulatedPrinters,
    val connectedPrinter: BluetoothPrinterDevice? = SampleCatalog.simulatedPrinters.first(),
    val printerStatus: PrinterStatus = PrinterStatus.READY,
    val printProgressStepAr: String = "وضع المحاكاة: جاهز للمعاينة (58mm)",
    val printProgressStepEn: String = "Simulation mode: Ready for preview (58mm)",
    val printProgressFraction: Float = 0f,
    val receiptPaperWidthMm: Int = 58
) {
    private fun matchesSearchQuery(product: Product, normalizedQuery: String): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return normalizeForSearch(product.nameAr).contains(normalizedQuery) ||
            normalizeForSearch(product.nameEn).contains(normalizedQuery) ||
            normalizeForSearch(product.unitAr).contains(normalizedQuery) ||
            normalizeForSearch(product.unitEn).contains(normalizedQuery) ||
            product.barcode.contains(normalizedQuery)
    }

    val filteredProducts: List<Product>
        get() {
            val q = normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
            return products.filter { product ->
                val matchesCategory = selectedCategory == ProductCategory.ALL || product.category == selectedCategory
                matchesCategory && matchesSearchQuery(product, q)
            }
        }

    val allCategoriesMatchCount: Int
        get() {
            val q = normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
            if (q.isEmpty()) return products.size
            return products.count { product -> matchesSearchQuery(product, q) }
        }

    val basketItemCount: Int
        get() = basket.sumOf { it.quantity }

    val basketSubtotalIls: Double
        get() = basket.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(BigDecimal(item.lineTotal.toString()))
        }.setScale(2, RoundingMode.HALF_UP).toDouble()

    // Exact, explicitly rounded money values for discounts
    val calculatedDiscountIls: Double
        get() {
            val sub = basketSubtotalIls
            if (sub <= 0.0) return 0.0
            val subBd = BigDecimal(sub.toString()).setScale(2, RoundingMode.HALF_UP)
            val rawDiscount = when (discountPreset) {
                DiscountPreset.NONE -> BigDecimal.ZERO
                DiscountPreset.PERCENT_5 -> subBd.multiply(BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP)
                DiscountPreset.PERCENT_10 -> subBd.multiply(BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP)
                DiscountPreset.ROUND_DOWN -> {
                    val rounded5 = floor(sub / 5.0) * 5.0
                    val diff = if (sub > 5.0 && sub - rounded5 > 0.0) sub - rounded5 else sub - floor(sub)
                    BigDecimal(diff.toString()).setScale(2, RoundingMode.HALF_UP)
                }
                DiscountPreset.CUSTOM_ILS -> {
                    val parsed = WesternFormatters.sanitizeWesternDigits(customDiscountText).toDoubleOrNull() ?: 0.0
                    BigDecimal(parsed.toString()).setScale(2, RoundingMode.HALF_UP)
                }
            }
            return rawDiscount.toDouble().coerceIn(0.0, sub)
        }

    val basketNetTotalIls: Double
        get() {
            val sub = BigDecimal(basketSubtotalIls.toString()).setScale(2, RoundingMode.HALF_UP)
            val disc = BigDecimal(calculatedDiscountIls.toString()).setScale(2, RoundingMode.HALF_UP)
            return sub.subtract(disc).setScale(2, RoundingMode.HALF_UP).toDouble().coerceAtLeast(0.0)
        }

    val cashTenderedIls: Double
        get() = WesternFormatters.sanitizeWesternDigits(cashInputText).toDoubleOrNull() ?: 0.0

    val changeDueIls: Double
        get() {
            val tendered = BigDecimal(cashTenderedIls.toString()).setScale(2, RoundingMode.HALF_UP)
            val net = BigDecimal(basketNetTotalIls.toString()).setScale(2, RoundingMode.HALF_UP)
            return tendered.subtract(net).setScale(2, RoundingMode.HALF_UP).toDouble().coerceAtLeast(0.0)
        }

    val remainingUnpaidIls: Double
        get() {
            val tendered = BigDecimal(cashTenderedIls.toString()).setScale(2, RoundingMode.HALF_UP)
            val net = BigDecimal(basketNetTotalIls.toString()).setScale(2, RoundingMode.HALF_UP)
            return net.subtract(tendered).setScale(2, RoundingMode.HALF_UP).toDouble().coerceAtLeast(0.0)
        }

    val isCashSufficient: Boolean
        get() = basket.isNotEmpty() && cashTenderedIls + 0.005 >= basketNetTotalIls

    val isExactCashTendered: Boolean
        get() = isCashSufficient && changeDueIls <= 0.004

    val selectedCustomer: Customer?
        get() = customers.firstOrNull { it.id == selectedCustomerId }

    val stockUnitsToDeduct: Int
        get() = basket.filter { it.product.isStockTracked }.sumOf { it.quantity }

    val customLinesCount: Int
        get() = basket.count { !it.product.isStockTracked }

    val projectedCustomerBalanceIfCreditIls: Double?
        get() = selectedCustomer?.let { customer ->
            BigDecimal(customer.balanceIls.toString())
                .add(BigDecimal(basketNetTotalIls.toString()))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
        }

    val wouldCreditExceedCustomerLimit: Boolean
        get() {
            val customer = selectedCustomer ?: return false
            val projected = projectedCustomerBalanceIfCreditIls ?: return false
            return projected > customer.creditLimitIls + 0.001
        }

    val creditCeilingExcessIls: Double
        get() {
            val customer = selectedCustomer ?: return 0.0
            val projected = projectedCustomerBalanceIfCreditIls ?: return 0.0
            val projBd = BigDecimal(projected.toString()).setScale(2, RoundingMode.HALF_UP)
            val limitBd = BigDecimal(customer.creditLimitIls.toString()).setScale(2, RoundingMode.HALF_UP)
            return projBd.subtract(limitBd).setScale(2, RoundingMode.HALF_UP).toDouble().coerceAtLeast(0.0)
        }

    val checkoutErrorMessage: String?
        get() = language.tr(checkoutErrorAr ?: "", checkoutErrorEn ?: "").takeIf { it.isNotBlank() }

    // Defined Dates & Shift Boundaries
    val todayDate: String
        get() = WesternFormatters.currentDate()

    val todayInvoices: List<InvoiceRecord>
        get() = invoices.filter { it.timestamp.startsWith(todayDate) }

    val todaySalesTotalIls: Double
        get() = todayInvoices.fold(BigDecimal.ZERO) { acc, inv ->
            acc.add(BigDecimal(inv.netTotalIls.toString()))
        }.setScale(2, RoundingMode.HALF_UP).toDouble()

    val todayInvoiceCount: Int
        get() = todayInvoices.size

    val averageBasketIls: Double
        get() = if (todayInvoiceCount == 0) 0.0 else todaySalesTotalIls / todayInvoiceCount

    val totalExpensesIls: Double
        get() = expenses.fold(BigDecimal.ZERO) { acc, exp ->
            acc.add(BigDecimal(exp.amountIls.toString()))
        }.setScale(2, RoundingMode.HALF_UP).toDouble()

    // Physical cash drawer reflects only net cash physically collected or disbursed in this shift
    val shiftCashSalesIls: Double
        get() = todayInvoices.fold(BigDecimal.ZERO) { acc, inv ->
            val netCash = max(0.0, inv.cashReceivedIls - inv.changeReturnedIls)
            acc.add(BigDecimal(netCash.toString()))
        }.setScale(2, RoundingMode.HALF_UP).toDouble()

    val shiftCustomerCollectionsIls: Double
        get() = customerTransactions
            .filter { it.date.startsWith(todayDate) && it.invoiceNumber == null && it.creditIls > 0.0 }
            .fold(BigDecimal.ZERO) { acc, tx -> acc.add(BigDecimal(tx.creditIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    val shiftSupplierDisbursementsIls: Double
        get() {
            val fromBills = purchaseBills
                .filter { it.date.startsWith(todayDate) && it.paidIls > 0.0 }
                .fold(BigDecimal.ZERO) { acc, bill -> acc.add(BigDecimal(bill.paidIls.toString())) }
            val fromPayments = supplierPayments
                .filter { it.date.startsWith(todayDate) && it.amountIls > 0.0 }
                .fold(BigDecimal.ZERO) { acc, pay -> acc.add(BigDecimal(pay.amountIls.toString())) }
            return fromBills.add(fromPayments).setScale(2, RoundingMode.HALF_UP).toDouble()
        }

    val shiftExpensesIls: Double
        get() = expenses
            .filter { it.date.startsWith(todayDate) && it.amountIls > 0.0 }
            .fold(BigDecimal.ZERO) { acc, exp -> acc.add(BigDecimal(exp.amountIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    val historicalExpensesIls: Double
        get() = BigDecimal(totalExpensesIls.toString())
            .subtract(BigDecimal(shiftExpensesIls.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)

    val shiftCreditSalesIls: Double
        get() = todayInvoices
            .fold(BigDecimal.ZERO) { acc, inv -> acc.add(BigDecimal(inv.remainingDebitIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()

    val drawerTotalCashIls: Double
        get() {
            val starting = BigDecimal(startingDrawerCashIls.toString())
            val salesCash = BigDecimal(shiftCashSalesIls.toString())
            val collections = BigDecimal(shiftCustomerCollectionsIls.toString())
            val disbursements = BigDecimal(shiftSupplierDisbursementsIls.toString())
            val exp = BigDecimal(shiftExpensesIls.toString())
            return starting.add(salesCash).add(collections).subtract(disbursements).subtract(exp)
                .setScale(2, RoundingMode.HALF_UP).toDouble()
        }

    val drawerCashSalesIls: Double get() = shiftCashSalesIls
    val totalCustomerCollectionsIls: Double get() = shiftCustomerCollectionsIls
    val totalSupplierDisbursementsIls: Double get() = shiftSupplierDisbursementsIls

    // Separate customer debts (accounts receivable) from customer deposits/advances (store liabilities)
    val totalCustomerDebtsIls: Double
        get() = customers.filter { it.balanceIls > 0.0 }
            .fold(BigDecimal.ZERO) { acc, c -> acc.add(BigDecimal(c.balanceIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    val totalCustomerAdvancesIls: Double
        get() = customers.filter { it.balanceIls < 0.0 }
            .fold(BigDecimal.ZERO) { acc, c -> acc.add(BigDecimal((-c.balanceIls).toString())) }
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    // Separate supplier payables (accounts payable) from supplier advances/prepayments (store assets)
    val totalSupplierPayablesIls: Double
        get() = suppliers.filter { it.balanceOwedIls > 0.0 }
            .fold(BigDecimal.ZERO) { acc, s -> acc.add(BigDecimal(s.balanceOwedIls.toString())) }
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    val totalSupplierAdvancesIls: Double
        get() = suppliers.filter { it.balanceOwedIls < 0.0 }
            .fold(BigDecimal.ZERO) { acc, s -> acc.add(BigDecimal((-s.balanceOwedIls).toString())) }
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    val lowStockProducts: List<Product>
        get() = products.filter { it.isLowStock }.sortedBy { it.stockCount }

    val isRootDestination: Boolean
        get() = currentScreen in ROOT_DESTINATIONS

    val isStoreOperationDestination: Boolean
        get() = currentScreen in STORE_OPERATION_DESTINATIONS

    val isAccountOrSettingsDestination: Boolean
        get() = currentScreen in ACCOUNT_SETTINGS_DESTINATIONS

    val isCheckoutFlowDestination: Boolean
        get() = currentScreen in CHECKOUT_FLOW_DESTINATIONS

    fun quantityInBasket(productId: String): Int =
        basket.firstOrNull { it.product.id == productId }?.quantity ?: 0

    companion object {
        val ROOT_DESTINATIONS = setOf(
            TraderScreen.HOME,
            TraderScreen.NEW_SALE,
            TraderScreen.INVOICES_HISTORY,
            TraderScreen.CUSTOMERS
        )

        val STORE_OPERATION_DESTINATIONS = setOf(
            TraderScreen.PRODUCTS_CATALOG,
            TraderScreen.SUPPLIERS,
            TraderScreen.EXPENSES,
            TraderScreen.FINANCIAL_REPORTS
        )

        val ACCOUNT_SETTINGS_DESTINATIONS = setOf(
            TraderScreen.USER_PROFILE,
            TraderScreen.BUSINESS_SETTINGS
        )

        val CHECKOUT_FLOW_DESTINATIONS = setOf(
            TraderScreen.NEW_SALE,
            TraderScreen.INVOICE_REVIEW,
            TraderScreen.CASH_PAYMENT
        )

        fun normalizeForSearch(raw: String): String {
            if (raw.isEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (ch in raw.lowercase()) {
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                    'ة' -> sb.append('ه')
                    'ى' -> sb.append('ي')
                    in '\u064B'..'\u065F', '\u0670', '\u0640' -> { /* strip Arabic diacritics & tatweel */ }
                    else -> sb.append(ch)
                }
            }
            return sb.toString().trim()
        }
    }
}

class TraderViewModel(
    private val repository: TraderRepository = OfflineDemoTraderRepository()
) : ViewModel() {

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
            businessInfo = repository.getBusinessInfo()
        )
    )
    val uiState: StateFlow<TraderUiState> = _uiState.asStateFlow()

    private var printJob: Job? = null
    private var bannerJob: Job? = null
    private var customItemCounter = 1

    private fun syncFromRepository() {
        _uiState.update { current ->
            val updatedProducts = repository.getProducts()
            val updatedCustomers = repository.getCustomers()
            val updatedSuppliers = repository.getSuppliers()
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
        _uiState.update { it.copy(currentSession = user) }
    }

    fun navigateTo(target: TraderScreen) {
        _uiState.update { state ->
            if (state.currentScreen == target) {
                state
            } else {
                val updatedHistory = when {
                    target == TraderScreen.HOME -> emptyList()
                    target in TraderUiState.ROOT_DESTINATIONS -> {
                        listOf(TraderScreen.HOME)
                    }
                    target in TraderUiState.STORE_OPERATION_DESTINATIONS &&
                        state.currentScreen in TraderUiState.STORE_OPERATION_DESTINATIONS -> {
                        state.screenHistory.ifEmpty { listOf(TraderScreen.HOME) }
                    }
                    target in TraderUiState.ACCOUNT_SETTINGS_DESTINATIONS &&
                        state.currentScreen in TraderUiState.ACCOUNT_SETTINGS_DESTINATIONS -> {
                        state.screenHistory.ifEmpty { listOf(TraderScreen.HOME) }
                    }
                    target in state.screenHistory -> {
                        state.screenHistory.takeWhile { it != target }
                    }
                    else -> {
                        (state.screenHistory + state.currentScreen).takeLast(8)
                    }
                }
                val defaultCashText = if (target == TraderScreen.CASH_PAYMENT && state.cashInputText.isBlank()) {
                    WesternFormatters.formatNumber(state.basketNetTotalIls)
                } else {
                    state.cashInputText
                }
                state.copy(
                    currentScreen = target,
                    screenHistory = updatedHistory,
                    cashInputText = defaultCashText,
                    checkoutErrorAr = null,
                    checkoutErrorEn = null
                )
            }
        }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value
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
            _uiState.update { it.copy(isPaymentCollectionDialogOpen = false) }
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
        if (current.currentScreen == TraderScreen.HOME) return false
        val previous = current.screenHistory.lastOrNull() ?: TraderScreen.HOME
        _uiState.update { state ->
            state.copy(
                currentScreen = previous,
                screenHistory = state.screenHistory.dropLast(1)
            )
        }
        return true
    }

    // Product Catalog & Inventory
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectCategory(category: ProductCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun selectProductForDetail(product: Product) {
        _uiState.update { state ->
            val freshProduct = state.products.firstOrNull { it.id == product.id } ?: product
            state.copy(
                selectedProductForDetail = freshProduct,
                lastInventoryActionMessageAr = null,
                lastInventoryActionMessageEn = null,
                isInventoryActionError = false,
                currentScreen = TraderScreen.PRODUCT_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
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

    fun updateProductPrice(productId: String, newPriceIls: Double): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.updateProductPrice(productId, newPriceIls, session)
        return if (result.isSuccess) {
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
    }

    fun adjustProductStock(productId: String, adjustment: Int, reason: String): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.adjustProductStock(productId, adjustment, reason, session)
        return if (result.isSuccess) {
            val movement = result.getOrNull()
            syncFromRepository()
            val signedDelta = if (adjustment > 0) "+${WesternFormatters.formatInt(adjustment)}" else WesternFormatters.formatInt(adjustment)
            val newStock = movement?.resultingStock ?: (_uiState.value.products.firstOrNull { it.id == productId }?.stockCount ?: 0)
            _uiState.update {
                it.copy(
                    lastInventoryActionMessageAr = "تم تسجيل حركة المخزون ($signedDelta) — الرصيد الحالي: ${WesternFormatters.formatInt(newStock)}",
                    lastInventoryActionMessageEn = "Stock movement recorded ($signedDelta) — Current stock: ${WesternFormatters.formatInt(newStock)}",
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
    }

    fun restockProduct(productId: String, amount: Int = 10) {
        adjustProductStock(productId, amount, "إعادة تزويد سريع للرفوف (+${amount})")
    }

    // POS Basket with Overselling Prevention Safeguard
    fun addProductToBasket(product: Product, count: Int = 1) {
        if (count <= 0) return
        _uiState.update { state ->
            val productInCatalog = state.products.firstOrNull { it.id == product.id } ?: product
            val existingIndex = state.basket.indexOfFirst { it.product.id == product.id }
            val currentQty = if (existingIndex >= 0) state.basket[existingIndex].quantity else 0

            // If product is stock-tracked, enforce physical inventory cap
            if (productInCatalog.isStockTracked) {
                if (productInCatalog.stockCount <= 0) {
                    return@update state.copy(
                        lastScannedBannerAr = "غير متوفر (نفد المخزون): ${productInCatalog.nameAr}",
                        lastScannedBannerEn = "Out of stock: ${productInCatalog.nameEn}",
                        isBannerError = true
                    )
                }
                if (currentQty >= productInCatalog.stockCount) {
                    return@update state.copy(
                        lastScannedBannerAr = "تم الوصول للحد الأقصى لمخزون: ${productInCatalog.nameAr} (${productInCatalog.stockCount})",
                        lastScannedBannerEn = "Maximum available stock reached for ${productInCatalog.nameEn} (${productInCatalog.stockCount})",
                        isBannerError = true
                    )
                }
                val allowedCount = minOf(count, productInCatalog.stockCount - currentQty)
                if (allowedCount <= 0) return@update state

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
        _uiState.update { state ->
            val updatedBasket = state.basket.mapNotNull { item ->
                if (item.product.id == productId) {
                    if (item.quantity > 1) item.copy(quantity = item.quantity - 1) else null
                } else {
                    item
                }
            }
            state.copy(basket = updatedBasket)
        }
    }

    fun removeProductFromBasket(productId: String) {
        _uiState.update { state ->
            state.copy(basket = state.basket.filterNot { it.product.id == productId })
        }
    }

    fun clearBasket() {
        _uiState.update { state ->
            state.copy(
                basket = emptyList(),
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
        val price = WesternFormatters.sanitizeWesternDigits(priceInput).toDoubleOrNull() ?: return
        if (price <= 0.0) return
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
            priceIls = price,
            costIls = 0.0, // Zero invented cost
            unitAr = "مادة سريعة",
            unitEn = "Quick Line",
            stockCount = 0, // Zero invented stock
            minStockThreshold = 0,
            accentHex = 0xFF1D4E89,
            isStockTracked = false // Permissible non-stock line
        )
        repository.addCustomProduct(customProduct)
        syncFromRepository()
        addProductToBasket(customProduct, 1)
        _uiState.update { it.copy(isCustomItemDialogOpen = false) }
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
        val currentQty = currentState.quantityInBasket(productInCatalog.id)

        if (productInCatalog.isStockTracked && productInCatalog.stockCount <= 0) {
            showBanner(
                ar = "غير متوفر (نفد المخزون): ${productInCatalog.nameAr}",
                en = "Out of stock: ${productInCatalog.nameEn}",
                isError = true
            )
            return
        }

        if (productInCatalog.isStockTracked && currentQty >= productInCatalog.stockCount) {
            showBanner(
                ar = "تم الوصول للحد الأقصى لمخزون: ${productInCatalog.nameAr} (${WesternFormatters.formatInt(productInCatalog.stockCount)})",
                en = "Maximum available stock reached for ${productInCatalog.nameEn} (${WesternFormatters.formatInt(productInCatalog.stockCount)})",
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
        val available = catalog.filter { !it.isStockTracked || state.quantityInBasket(it.id) < it.stockCount }
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

    // Discounts & Customer Notes
    fun selectDiscountPreset(preset: DiscountPreset) {
        _uiState.update { state ->
            state.copy(
                discountPreset = preset,
                cashInputText = "",
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCustomDiscountText(text: String) {
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
        _uiState.update { it.copy(customerNote = note) }
    }

    fun assignCustomerToSale(customer: Customer?) {
        _uiState.update { state ->
            val currentNote = state.customerNote.trim()
            val wasPreviousCustomerName = state.customers.any {
                it.nameAr == currentNote || it.nameEn == currentNote
            }
            val updatedNote = if (wasPreviousCustomerName) "" else state.customerNote
            state.copy(
                selectedCustomerId = customer?.id,
                customerNote = updatedNote,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
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
    fun setCashExactAmount() {
        _uiState.update { state ->
            state.copy(
                cashInputText = WesternFormatters.formatNumber(state.basketNetTotalIls),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun setCashQuickDenomination(amountIls: Int) {
        _uiState.update { state ->
            state.copy(
                cashInputText = amountIls.toString(),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun addCashIncrement(deltaIls: Int) {
        _uiState.update { state ->
            val current = state.cashTenderedIls
            val next = current + deltaIls
            state.copy(
                cashInputText = WesternFormatters.formatNumber(next).removeSuffix(".00"),
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun updateCashInputText(rawInput: String) {
        val western = WesternFormatters.sanitizeWesternDigits(rawInput)
        val filtered = buildString {
            var hasDot = false
            var decimals = 0
            for (ch in western) {
                if (ch.isDigit()) {
                    if (hasDot) {
                        if (decimals < 2) {
                            append(ch)
                            decimals++
                        }
                    } else if (length < 6) {
                        append(ch)
                    }
                } else if (ch == '.' && !hasDot) {
                    if (isEmpty()) append('0')
                    append('.')
                    hasDot = true
                }
            }
        }
        _uiState.update { state ->
            state.copy(
                cashInputText = filtered,
                checkoutErrorAr = null,
                checkoutErrorEn = null
            )
        }
    }

    fun onCashKeypadPress(key: String) {
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
                    val base = if (current == "0" || current == WesternFormatters.formatNumber(state.basketNetTotalIls)) {
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

    // Final Sale Posting via Repository
    fun completeCashSale(asCreditDebt: Boolean = false) {
        val state = _uiState.value
        if (state.basket.isEmpty() || state.isSubmittingSale) return

        val netTotal = state.basketNetTotalIls

        // Safeguard 1: Cash sale requires sufficient cash tendered (no partial cash accepted)
        if (!asCreditDebt && !state.isCashSufficient) {
            val shortfall = WesternFormatters.formatIls(state.remainingUnpaidIls)
            val totalFormatted = WesternFormatters.formatIls(netTotal)
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

        _uiState.update { it.copy(isSubmittingSale = true) }

        val tendered: Double
        val change: Double
        val remainingDebt: Double
        val isPaidInFull: Boolean

        if (asCreditDebt) {
            // Truthful full-credit sale: 0.00 cash into drawer, 100% of netTotal recorded as customer debt
            tendered = 0.0
            change = 0.0
            remainingDebt = BigDecimal(netTotal.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
            isPaidInFull = (remainingDebt <= 0.001)
        } else {
            tendered = BigDecimal(state.cashTenderedIls.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
            val changeBd = BigDecimal(tendered.toString()).subtract(BigDecimal(netTotal.toString())).setScale(2, RoundingMode.HALF_UP)
            change = changeBd.toDouble().coerceAtLeast(0.0)
            remainingDebt = 0.0
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

        val newInvoice = InvoiceRecord(
            id = "inv_$nextNumber",
            invoiceNumber = nextNumber,
            timestamp = timestamp,
            items = state.basket,
            subtotalIls = state.basketSubtotalIls,
            discountIls = state.calculatedDiscountIls,
            netTotalIls = netTotal,
            cashReceivedIls = tendered,
            changeReturnedIls = change,
            customerId = state.selectedCustomerId,
            customerNote = customerName,
            cashierNameAr = state.currentSession.name,
            cashierNameEn = state.currentSession.name,
            isPaidInFull = isPaidInFull,
            remainingDebitIls = remainingDebt,
            printCount = 0
        )

        // Authoritative posting through the repository contract
        val result = repository.recordSale(newInvoice)
        if (result.isSuccess) {
            syncFromRepository()
            _uiState.update { current ->
                val printer = current.connectedPrinter
                val ready = printer != null && printer.isPaired
                current.copy(
                    activeReceipt = newInvoice,
                    isNewlyCompletedSale = true,
                    receiptShareCount = 0,
                    receiptCopyCount = 0,
                    lastSharedReceiptText = null,
                    basket = emptyList(),
                    discountPreset = DiscountPreset.NONE,
                    customDiscountText = "",
                    customerNote = "",
                    selectedCustomerId = null,
                    cashInputText = "",
                    isSubmittingSale = false,
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
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Sale rejected by domain engine"
            val parts = rawErr.split(" | ", limit = 2)
            val errAr = parts.firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            val errEn = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() } ?: rawErr
            _uiState.update {
                it.copy(
                    isSubmittingSale = false,
                    checkoutErrorAr = errAr,
                    checkoutErrorEn = errEn
                )
            }
            showBanner(ar = errAr, en = errEn, isError = true)
        }
    }

    fun openInvoiceReceipt(invoice: InvoiceRecord) {
        _uiState.update { state ->
            val printer = state.connectedPrinter
            val ready = printer != null && printer.isPaired
            state.copy(
                activeReceipt = invoice,
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

    fun startFreshSale() {
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
        _uiState.update { state ->
            val freshCustomer = state.customers.firstOrNull { it.id == customer.id } ?: customer
            state.copy(
                selectedCustomerForDetail = freshCustomer,
                lastCustomerPaymentMessageAr = null,
                lastCustomerPaymentMessageEn = null,
                currentScreen = TraderScreen.CUSTOMER_DETAIL,
                screenHistory = (state.screenHistory + state.currentScreen).takeLast(8)
            )
        }
    }

    fun openPaymentCollectionDialog(open: Boolean) {
        _uiState.update { it.copy(isPaymentCollectionDialogOpen = open) }
    }

    fun openPaymentCollectionForCustomer(customer: Customer) {
        _uiState.update { state ->
            val freshCustomer = state.customers.firstOrNull { it.id == customer.id } ?: customer
            state.copy(
                selectedCustomerForDetail = freshCustomer,
                isPaymentCollectionDialogOpen = true
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

    fun recordCustomerPayment(customerId: String, paymentAmountIls: Double, note: String, paymentId: String = ""): Boolean {
        val pid = if (paymentId.isNotBlank()) paymentId else "cpay_${System.currentTimeMillis()}"
        val result = repository.recordCustomerPayment(customerId, paymentAmountIls, note, pid)
        return if (result.isSuccess) {
            syncFromRepository()
            _uiState.update { state ->
                val updatedCust = state.customers.firstOrNull { it.id == customerId }
                val paidStr = WesternFormatters.formatIls(paymentAmountIls)
                val newBal = updatedCust?.balanceIls ?: 0.0
                val msgAr = when {
                    newBal > 0.005 -> "تم قبض $paidStr نقداً — المتبقي على الزبون: ${WesternFormatters.formatIls(newBal)}"
                    newBal < -0.005 -> "تم قبض $paidStr نقداً — أصبح للزبون رصيد مقدم: ${WesternFormatters.formatIls(-newBal)}"
                    else -> "تم قبض $paidStr نقداً — أصبح الحساب خالصاً (0.00 ₪)"
                }
                val msgEn = when {
                    newBal > 0.005 -> "Collected $paidStr in cash — Remaining debt: ${WesternFormatters.formatIls(newBal)}"
                    newBal < -0.005 -> "Collected $paidStr in cash — Customer advance credit: ${WesternFormatters.formatIls(-newBal)}"
                    else -> "Collected $paidStr in cash — Account is now settled (0.00 ₪)"
                }
                state.copy(
                    isPaymentCollectionDialogOpen = false,
                    selectedCustomerForDetail = updatedCust,
                    lastCustomerPaymentMessageAr = msgAr,
                    lastCustomerPaymentMessageEn = msgEn
                )
            }
            true
        } else {
            val err = result.exceptionOrNull()?.message ?: "Payment rejected"
            showBanner(err, err, isError = true)
            false
        }
    }

    fun createCustomer(name: String, phone: String, address: String) {
        val result = repository.createCustomer(name, phone, address)
        if (result.isSuccess) {
            val created = result.getOrNull()
            syncFromRepository()
            _uiState.update {
                it.copy(
                    isAddCustomerDialogOpen = false,
                    lastCustomerPaymentMessageAr = if (created != null) "تمت إضافة الزبون «${created.nameAr}» إلى دفتر الحسابات" else null,
                    lastCustomerPaymentMessageEn = if (created != null) "Added customer \"${created.nameEn}\" to ledger" else null
                )
            }
        }
    }

    fun openAddCustomerDialog(open: Boolean) {
        _uiState.update { it.copy(isAddCustomerDialogOpen = open) }
    }

    // Suppliers & Purchasing Operations
    fun selectSupplierForDetail(supplier: Supplier) {
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
        _uiState.update { it.copy(isReceivePurchaseDialogOpen = open) }
    }

    fun recordPurchaseBill(
        supplierId: String,
        itemsSummary: String,
        totalIls: Double,
        paidNowIls: Double
    ): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.recordPurchaseBill(supplierId, itemsSummary, totalIls, paidNowIls, session)
        return if (result.isSuccess) {
            val bill = result.getOrNull()
            syncFromRepository()
            _uiState.update { state ->
                val billCode = bill?.let { WesternFormatters.formatBillCode(it.billNumber) } ?: ""
                val remStr = bill?.let { WesternFormatters.formatIls(it.remainingPayableIls) } ?: ""
                state.copy(
                    isReceivePurchaseDialogOpen = false,
                    selectedSupplierForDetail = state.suppliers.firstOrNull { it.id == supplierId },
                    lastSupplierActionMessageAr = "تم قيد فاتورة الشراء المالية $billCode (المتبقي للمورد: $remStr) — قيد مالي فقط دون إضافة مخزون تلقائي",
                    lastSupplierActionMessageEn = "Financial purchase bill $billCode recorded (Payable added: $remStr) — financial entry only, stock not auto-incremented",
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
    }

    fun paySupplierAmount(
        supplierId: String,
        amountIls: Double,
        note: String = "سداد نقدي للمورد من الصندوق"
    ): Boolean {
        val session = _uiState.value.currentSession
        val result = repository.recordSupplierPayment(supplierId, amountIls, note, session)
        return if (result.isSuccess) {
            syncFromRepository()
            _uiState.update { state ->
                val updatedSup = state.suppliers.firstOrNull { it.id == supplierId }
                val paidStr = WesternFormatters.formatIls(amountIls)
                val newOwed = updatedSup?.balanceOwedIls ?: 0.0
                val msgAr = when {
                    newOwed > 0.005 -> "تم صرف $paidStr للمورد — المتبقي له علينا: ${WesternFormatters.formatIls(newOwed)}"
                    newOwed < -0.005 -> "تم صرف $paidStr للمورد — أصبح لنا دفعة مقدمة عنده: ${WesternFormatters.formatIls(-newOwed)}"
                    else -> "تم صرف $paidStr للمورد — أصبح حساب المورد خالصاً (0.00 ₪)"
                }
                val msgEn = when {
                    newOwed > 0.005 -> "Paid $paidStr to supplier — Remaining payable: ${WesternFormatters.formatIls(newOwed)}"
                    newOwed < -0.005 -> "Paid $paidStr to supplier — Store prepayment credit: ${WesternFormatters.formatIls(-newOwed)}"
                    else -> "Paid $paidStr to supplier — Supplier account settled (0.00 ₪)"
                }
                state.copy(
                    selectedSupplierForDetail = updatedSup,
                    lastSupplierActionMessageAr = msgAr,
                    lastSupplierActionMessageEn = msgEn,
                    isSupplierActionError = false
                )
            }
            true
        } else {
            val rawErr = result.exceptionOrNull()?.message ?: "Supplier disbursement rejected"
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
    }

    // Expenses & Cash Outflow
    fun openAddExpenseDialog(open: Boolean) {
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

    fun addExpense(title: String, category: ExpenseCategory, amountIls: Double): Boolean {
        val result = repository.recordExpense(title, category, amountIls, _uiState.value.currentSession)
        return if (result.isSuccess) {
            val recorded = result.getOrNull()
            syncFromRepository()
            val amtStr = WesternFormatters.formatIls(recorded?.amountIls ?: amountIls)
            _uiState.update {
                it.copy(
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
    }

    // Bluetooth Thermal Printer Simulation & Document Sharing
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

    fun formatReceiptShareText(receipt: InvoiceRecord? = _uiState.value.activeReceipt ?: _uiState.value.invoices.firstOrNull()): String {
        val target = receipt ?: return ""
        val state = _uiState.value
        val lang = state.language
        val business = state.businessInfo
        val sb = StringBuilder()

        val docTitle = if (target.isPaidInFull) {
            lang.tr("إيصال قبض وفاتورة مبيعات نقدية", "Cash Sales Invoice & Receipt")
        } else {
            lang.tr("فاتورة مبيعات آجلة (ذمة على الحساب)", "Credit Sales Invoice (On Account)")
        }

        sb.appendLine(business.name(lang))
        sb.appendLine("${business.city(lang)} • ${business.phone}")
        sb.appendLine("--------------------------------")
        sb.appendLine("$docTitle: ${WesternFormatters.formatInvoiceCode(target.invoiceNumber)}")
        sb.appendLine("${lang.tr("التاريخ", "Date")}: ${target.timestamp}")
        sb.appendLine("${lang.tr("الزبون", "Customer")}: ${target.customerNote}")
        sb.appendLine("${lang.tr("الكاشير", "Cashier")}: ${lang.tr(target.cashierNameAr, target.cashierNameEn)}")
        sb.appendLine("--------------------------------")
        target.items.forEachIndexed { idx, item ->
            sb.appendLine(
                "${idx + 1}. ${item.product.name(lang)} (${WesternFormatters.formatInt(item.quantity)} × ${WesternFormatters.formatIls(item.product.priceIls)}) = ${WesternFormatters.formatIls(item.lineTotal)}"
            )
        }
        sb.appendLine("--------------------------------")
        if (target.discountIls > 0.005) {
            sb.appendLine("${lang.tr("المجموع الفرعي", "Subtotal")}: ${WesternFormatters.formatIls(target.subtotalIls)}")
            sb.appendLine("${lang.tr("الخصم", "Discount")}: -${WesternFormatters.formatIls(target.discountIls)}")
        }
        sb.appendLine("${lang.tr("الصافي المطلوب", "Net Total")}: ${WesternFormatters.formatIls(target.netTotalIls)}")
        if (target.isPaidInFull) {
            sb.appendLine("${lang.tr("الحالة", "Status")}: ${lang.tr("مدفوع نقداً بالكامل", "Paid in Full (Cash)")}")
            sb.appendLine("${lang.tr("المقبوض نقداً", "Cash Received")}: ${WesternFormatters.formatIls(target.cashReceivedIls)}")
            sb.appendLine("${lang.tr("الباقي للزبون", "Change Returned")}: ${WesternFormatters.formatIls(target.changeReturnedIls)}")
        } else {
            sb.appendLine("${lang.tr("الحالة", "Status")}: ${lang.tr("ذمة آجلة على حساب الزبون", "Unpaid — On Customer Account")}")
            sb.appendLine("${lang.tr("المدفوع عند الإصدار", "Cash Paid at Issue")}: ${WesternFormatters.formatIls(target.cashReceivedIls)}")
            sb.appendLine("${lang.tr("المتبقي بالذمة", "Balance Due on Account")}: ${WesternFormatters.formatIls(target.remainingDebitIls)}")
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
}
