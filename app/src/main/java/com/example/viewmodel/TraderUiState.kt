package com.example.viewmodel

import com.example.ui.navigation.TraderNavigationPolicy
import com.example.ui.navigation.MerchantAction
import com.example.ui.navigation.allowed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OfflineDemoTraderRepository
import com.example.data.ProductSearch
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
import com.example.model.PurchaseLineItem
import com.example.model.SaleDraft
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
    val selectedRoot: TraderScreen = TraderScreen.HOME,
    val saleSessionId: Long = 0,
    val isQuickActionOpen: Boolean = false,
    val pendingAction: MerchantAction? = null,
    val saleSearchQuery: String = "",
    val inventorySearchQuery: String = "",
    val saleCategory: ProductCategory = ProductCategory.ALL,
    val inventoryCategory: ProductCategory = ProductCategory.ALL,
    val inventoryLowStockRequested: Boolean = false,
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

    // Active Basket, Saved Drafts & POS
    val basket: List<BasketItem> = emptyList(),
    val savedDrafts: List<SaleDraft> = SampleCatalog.initialSaleDrafts(SampleCatalog.initialProducts),
    val discountPreset: DiscountPreset = DiscountPreset.NONE,
    val customDiscountText: String = "",
    val customerNote: String = "",
    val selectedCustomerId: String? = null,
    val cashInputText: String = "",
    val isSubmittingSale: Boolean = false,
    val checkoutErrorAr: String? = null,
    val checkoutErrorEn: String? = null,

    // Invoices, Cash Shift & Accounting
    val invoices: List<InvoiceRecord> = emptyList(),
    val activeReceipt: InvoiceRecord? = null,
    val isNewlyCompletedSale: Boolean = false,
    val receiptShareCount: Int = 0,
    val receiptCopyCount: Int = 0,
    val lastSharedReceiptText: String? = null,
    val startingDrawerCashIls: Double = 350.00,
    val countedPhysicalCashText: String = "",
    val isShiftClosedPreview: Boolean = false,
    val shiftClosedTimestamp: String? = null,

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
        return ProductSearch.computeScore(product, normalizedQuery) > 0
    }

    @Volatile
    private var cachedFilteredQuery: String? = null
    @Volatile
    private var cachedFilteredCategory: ProductCategory? = null
    @Volatile
    private var cachedFilteredProductsRef: List<Product>? = null
    @Volatile
    private var cachedFilteredResult: List<Product>? = null

    val filteredProducts: List<Product>
        get() {
            val currentProducts = products
            val currentQuery = searchQuery
            val currentCategory = selectedCategory
            val cachedResult = cachedFilteredResult
            if (cachedResult != null &&
                cachedFilteredQuery == currentQuery &&
                cachedFilteredCategory == currentCategory &&
                cachedFilteredProductsRef === currentProducts
            ) {
                return cachedResult
            }
            val result = ProductSearch.search(
                products = currentProducts,
                query = currentQuery,
                category = currentCategory,
                limit = 30,
                stockTrackedOnly = false
            )
            cachedFilteredQuery = currentQuery
            cachedFilteredCategory = currentCategory
            cachedFilteredProductsRef = currentProducts
            cachedFilteredResult = result
            return result
        }

    @Volatile
    private var cachedCountQuery: String? = null
    @Volatile
    private var cachedCountProductsRef: List<Product>? = null
    @Volatile
    private var cachedCountResult: Int? = null

    val allCategoriesMatchCount: Int
        get() {
            val currentProducts = products
            val currentQuery = searchQuery
            val cachedCount = cachedCountResult
            if (cachedCount != null &&
                cachedCountQuery == currentQuery &&
                cachedCountProductsRef === currentProducts
            ) {
                return cachedCount
            }
            val count = ProductSearch.countMatches(
                products = currentProducts,
                query = currentQuery,
                category = ProductCategory.ALL,
                stockTrackedOnly = false
            )
            cachedCountQuery = currentQuery
            cachedCountProductsRef = currentProducts
            cachedCountResult = count
            return count
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

    val expectedBookCashIls: Double
        get() = drawerTotalCashIls

    val countedPhysicalCashIls: Double?
        get() {
            val sanitized = WesternFormatters.sanitizeWesternDigits(countedPhysicalCashText.trim())
            if (sanitized.isEmpty()) return null
            val parsed = sanitized.toDoubleOrNull() ?: return null
            return BigDecimal(parsed.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
        }

    val shiftCashVarianceIls: Double?
        get() {
            val counted = countedPhysicalCashIls ?: return null
            return BigDecimal(counted.toString())
                .subtract(BigDecimal(expectedBookCashIls.toString()))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
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

    fun supplierActionMessage(lang: AppLanguage): String =
        if (lang == AppLanguage.AR) lastSupplierActionMessageAr.orEmpty() else lastSupplierActionMessageEn.orEmpty()

    companion object {
        val ROOT_DESTINATIONS = setOf(
            TraderScreen.HOME,
            TraderScreen.PRODUCTS_CATALOG,
            TraderScreen.INVOICES_HISTORY,
            TraderScreen.PEOPLE
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

        fun normalizeForSearch(raw: String): String = ProductSearch.normalize(raw)
    }
}

