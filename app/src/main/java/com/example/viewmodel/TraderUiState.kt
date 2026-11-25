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
import com.example.model.SettlementTarget
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.data.mobile.merchantread.ShiftCloseResponse
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

enum class ReadStatus { LOADING, FAILED, READY }

data class TraderUiState(
    // Mobile V1 Merchant Live Read Authoritative Projections
    val liveHomeMetrics: com.example.data.mobile.merchantread.LiveHomeMetrics? = null,
    val liveShiftSummary: com.example.data.mobile.merchantread.LiveShiftSummary? = null,
    val liveTreasurySummary: com.example.data.mobile.merchantread.LiveTreasurySummary? = null,
    val liveInvoicesHasMoreGap: Boolean = false,
    val liveInvoicesHasMore: Boolean = false,
    val liveInvoicesNextCursor: String? = null,
    val isLiveInvoicesLoading: Boolean = false,
    val selectedCustomerStatement: com.example.data.mobile.merchantread.LiveCustomerStatement? = null,
    val selectedProductHistory: com.example.data.mobile.merchantread.LiveStockMovementHistory? = null,
    val selectedInvoiceDetail: com.example.data.mobile.merchantread.LiveInvoiceRecord? = null,
    val readStatus: Map<TraderScreen, ReadStatus> = emptyMap(),

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
    val productsHasMore: Boolean = false,
    val productSearchQuery: String = "",
    val productSearchResults: List<Product>? = null,
    val isSearchingProducts: Boolean = false,
    val productSearchError: String? = null,
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
    val isActionBusy: Boolean = false,
    val currentSaleClientEventId: String = java.util.UUID.randomUUID().toString(),
    val draftExpenseOperationId: String = java.util.UUID.randomUUID().toString(),
    val draftStockAdjustOperationId: String = java.util.UUID.randomUUID().toString(),
    val draftPurchaseOperationId: String = java.util.UUID.randomUUID().toString(),
    val draftSupplierPaymentOperationId: String = java.util.UUID.randomUUID().toString(),
    val draftCustomerPaymentOperationId: String = java.util.UUID.randomUUID().toString(),
    val draftGrnOperationId: String = java.util.UUID.randomUUID().toString(),
    val draftCustomerCreateOperationId: String = "",
    val draftSupplierCreateOperationId: String = "",
    val draftShiftCloseOperationId: String = "",
    val isShiftCloseRecovering: Boolean = false,
    val activeShiftCloseDraft: com.example.data.mobile.contract.StrictJsonObject? = null,
    val isPaymentUnverifiedHold: Boolean = false,
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
    val shiftObservedTimestamp: String? = null,
    val isShiftCloseDialogOpen: Boolean = false,
    val isShiftCloseSubmitting: Boolean = false,
    val shiftCloseError: String? = null,
    val shiftCloseSuccessSnapshot: ShiftCloseResponse? = null,
    val shiftCloseCountedCashInput: String = "",
    val shiftCloseNotesInput: String = "",

    // Customers & Balances
    val customers: List<Customer> = emptyList(),
    val customersHasMore: Boolean = false,
    val customerSearchQuery: String = "",
    val customerSearchResults: List<Customer>? = null,
    val isSearchingCustomers: Boolean = false,
    val customerSearchError: String? = null,
    val customerTransactions: List<CustomerTransaction> = emptyList(),
    val selectedCustomerForDetail: Customer? = null,
    val lastCustomerPaymentMessageAr: String? = null,
    val lastCustomerPaymentMessageEn: String? = null,
    val activeCustomerCreateDraft: com.example.data.mobile.contract.StrictJsonObject? = null,
    val isCustomerCreateRecovering: Boolean = false,
    val customerSettlementTargets: List<SettlementTarget> = emptyList(),
    val isLoadingCustomerSettlementTargets: Boolean = false,
    val customerSettlementTargetsError: String? = null,
    val customerSettlementTargetsHasMore: Boolean = false,
    val customerSettlementTargetsNextCursor: String? = null,

    // Suppliers & Purchasing
    val suppliers: List<Supplier> = emptyList(),
    val suppliersHasMore: Boolean = false,
    val supplierSearchQuery: String = "",
    val supplierSearchResults: List<Supplier>? = null,
    val isSearchingSuppliers: Boolean = false,
    val supplierSearchError: String? = null,
    val activeSupplierCreateDraft: com.example.data.mobile.contract.StrictJsonObject? = null,
    val isSupplierCreateRecovering: Boolean = false,
    val supplierSettlementTargets: List<SettlementTarget> = emptyList(),
    val isLoadingSupplierSettlementTargets: Boolean = false,
    val supplierSettlementTargetsError: String? = null,
    val supplierSettlementTargetsHasMore: Boolean = false,
    val supplierSettlementTargetsNextCursor: String? = null,
    val purchaseBills: List<PurchaseBill> = emptyList(),
    val supplierPayments: List<SupplierPayment> = emptyList(),
    val selectedSupplierForDetail: Supplier? = null,
    val lastSupplierActionMessageAr: String? = null,
    val lastSupplierActionMessageEn: String? = null,
    val isSupplierActionError: Boolean = false,

    // Expenses
    val expenses: List<ExpenseEntry> = emptyList(),
    val serverExpenseCategories: List<com.example.model.ServerExpenseCategory> = emptyList(),
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
    fun readStatusFor(screen: TraderScreen): ReadStatus =
        readStatus[screen] ?: if (currentSession.isDemoSession) ReadStatus.READY else ReadStatus.LOADING

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
            val currentQuery = searchQuery
            val currentCategory = selectedCategory
            val sourceProducts = if (currentQuery.isNotBlank() &&
                productSearchQuery.trim().equals(currentQuery.trim(), ignoreCase = true) &&
                productSearchResults != null
            ) {
                productSearchResults!!
            } else {
                products
            }
            val cachedResult = cachedFilteredResult
            if (cachedResult != null &&
                cachedFilteredQuery == currentQuery &&
                cachedFilteredCategory == currentCategory &&
                cachedFilteredProductsRef === sourceProducts
            ) {
                return cachedResult
            }
            val result = ProductSearch.search(
                products = sourceProducts,
                query = currentQuery,
                category = currentCategory,
                limit = 30,
                stockTrackedOnly = false
            )
            cachedFilteredQuery = currentQuery
            cachedFilteredCategory = currentCategory
            cachedFilteredProductsRef = sourceProducts
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
            val currentQuery = searchQuery
            val sourceProducts = if (currentQuery.isNotBlank() &&
                productSearchQuery.trim().equals(currentQuery.trim(), ignoreCase = true) &&
                productSearchResults != null
            ) {
                productSearchResults!!
            } else {
                products
            }
            val cachedCount = cachedCountResult
            if (cachedCount != null &&
                cachedCountQuery == currentQuery &&
                cachedCountProductsRef === sourceProducts
            ) {
                return cachedCount
            }
            val count = ProductSearch.countMatches(
                products = sourceProducts,
                query = currentQuery,
                category = ProductCategory.ALL,
                stockTrackedOnly = false
            )
            cachedCountQuery = currentQuery
            cachedCountProductsRef = sourceProducts
            cachedCountResult = count
            return count
        }

    val basketItemCount: Int
        get() = basket.fold(BigDecimal.ZERO) { acc, item -> acc + item.quantity }.toInt()

    val basketItemCountExact: BigDecimal
        get() = basket.fold(BigDecimal.ZERO) { acc, item -> acc + item.quantity }

    val basketSubtotalExact: BigDecimal
        get() = basket.fold(BigDecimal.ZERO) { acc, item ->
            acc.add(item.lineTotalExact)
        }

    val basketSubtotalIls: Double
        get() = basketSubtotalExact.toDouble()

    // Authoritative exact decimal discount input
    val calculatedDiscountExact: BigDecimal
        get() {
            val sub = basketSubtotalExact
            if (sub <= BigDecimal.ZERO) return BigDecimal.ZERO
            val rawDiscount = when (discountPreset) {
                DiscountPreset.NONE -> BigDecimal.ZERO
                DiscountPreset.PERCENT_5 -> sub.multiply(BigDecimal("0.05"))
                DiscountPreset.PERCENT_10 -> sub.multiply(BigDecimal("0.10"))
                DiscountPreset.ROUND_DOWN -> {
                    val subDouble = sub.toDouble()
                    val rounded5 = floor(subDouble / 5.0) * 5.0
                    val diff = if (subDouble > 5.0 && subDouble - rounded5 > 0.0) subDouble - rounded5 else subDouble - floor(subDouble)
                    BigDecimal(diff.toString()).setScale(2, RoundingMode.HALF_UP)
                }
                DiscountPreset.CUSTOM_ILS -> {
                    val sanitized = WesternFormatters.sanitizeWesternDigits(customDiscountText).trim()
                    try {
                        if (sanitized.isNotEmpty()) BigDecimal(sanitized) else BigDecimal.ZERO
                    } catch (_: NumberFormatException) {
                        BigDecimal.ZERO
                    }
                }
            }
            val discount = if (currentSession.isDemoSession) {
                rawDiscount.setScale(2, RoundingMode.HALF_UP)
            } else {
                rawDiscount
            }
            return discount.coerceIn(BigDecimal.ZERO, sub)
        }

    // Exact money value for discounts (Double projection of authoritative calculatedDiscountExact)
    val calculatedDiscountIls: Double
        get() = calculatedDiscountExact.toDouble()

    // Single exact nonzero check derived from authoritative decimal discount input
    val hasDiscount: Boolean
        get() = calculatedDiscountExact.compareTo(BigDecimal.ZERO) != 0

    val hasUnsupportedProductionDiscount: Boolean
        get() = !currentSession.isDemoSession && hasDiscount

    val basketNetTotalExact: BigDecimal
        get() {
            val sub = basketSubtotalExact
            val disc = calculatedDiscountExact
            return sub.subtract(disc).coerceAtLeast(BigDecimal.ZERO)
        }

    val basketNetTotalIls: Double
        get() = basketNetTotalExact.setScale(2, RoundingMode.HALF_UP).toDouble()

    val cashTenderedExact: BigDecimal
        get() {
            val sanitized = WesternFormatters.sanitizeWesternDigits(cashInputText).trim()
            if (sanitized.isEmpty()) return BigDecimal.ZERO
            return try {
                BigDecimal(sanitized)
            } catch (_: NumberFormatException) {
                BigDecimal.ZERO
            }
        }

    val cashTenderedIls: Double
        get() = cashTenderedExact.toDouble()

    val changeDueExact: BigDecimal
        get() = if (cashTenderedExact > basketNetTotalExact) {
            cashTenderedExact.subtract(basketNetTotalExact)
        } else {
            BigDecimal.ZERO
        }

    val changeDueIls: Double
        get() = changeDueExact.toDouble()

    val remainingUnpaidExact: BigDecimal
        get() = if (basketNetTotalExact > cashTenderedExact) {
            basketNetTotalExact.subtract(cashTenderedExact)
        } else {
            BigDecimal.ZERO
        }

    val remainingUnpaidIls: Double
        get() = remainingUnpaidExact.toDouble()

    /**
     * Fails safe when cash collection cannot represent the required amount with 2-decimal money entry.
     * Prevents arbitrary rounding (e.g. JOD 9.833 to 9.83 payment) before cash collection.
     */
    val isCashCollectionUnrepresentable: Boolean
        get() {
            if (basket.isEmpty()) return false
            if (basketNetTotalExact.stripTrailingZeros().scale() > 2) return true
            if (cashTenderedExact.stripTrailingZeros().scale() > 2) return true
            return false
        }

    val isCashSufficient: Boolean
        get() = basket.isNotEmpty() && !isCashCollectionUnrepresentable && cashTenderedExact >= basketNetTotalExact

    val isExactCashTendered: Boolean
        get() = isCashSufficient && cashTenderedExact.compareTo(basketNetTotalExact) == 0

    val selectedCustomer: Customer?
        get() = customers.firstOrNull { it.id == selectedCustomerId }

    val stockUnitsToDeduct: Int
        get() = basket.filter { it.product.isStockTracked }.fold(BigDecimal.ZERO) { acc, item -> acc + item.quantity }.toInt()

    val stockUnitsToDeductExact: BigDecimal
        get() = basket.filter { it.product.isStockTracked }.fold(BigDecimal.ZERO) { acc, item -> acc + item.quantity }

    val customLinesCount: Int
        get() = basket.count { !it.product.isStockTracked }

    val isCustomerBalanceKnown: Boolean
        get() = selectedCustomer?.let { it.balanceExact != null && !it.balanceIls.isNaN() } ?: false

    val isCreditSaleBlockedByUnknownBalance: Boolean
        get() = selectedCustomer?.let { it.creditLimitExact != null && !isCustomerBalanceKnown } ?: false

    val projectedCustomerBalanceIfCreditExact: BigDecimal?
        get() = if (!isCustomerBalanceKnown) null else selectedCustomer?.let { customer ->
            val bal = customer.balanceExact ?: return null
            bal.add(basketNetTotalExact)
        }

    val projectedCustomerBalanceIfCreditIls: Double?
        get() = projectedCustomerBalanceIfCreditExact?.toDouble()

    val wouldCreditExceedCustomerLimit: Boolean
        get() {
            if (!isCustomerBalanceKnown) return false
            val customer = selectedCustomer ?: return false
            val limitExact = customer.creditLimitExact ?: return false
            val projected = projectedCustomerBalanceIfCreditExact ?: return false
            return projected > limitExact
        }

    val creditCeilingExcessExact: BigDecimal
        get() {
            if (!isCustomerBalanceKnown) return BigDecimal.ZERO
            val customer = selectedCustomer ?: return BigDecimal.ZERO
            val limitExact = customer.creditLimitExact ?: return BigDecimal.ZERO
            val projected = projectedCustomerBalanceIfCreditExact ?: return BigDecimal.ZERO
            return if (projected > limitExact) projected.subtract(limitExact) else BigDecimal.ZERO
        }

    val creditCeilingExcessIls: Double
        get() = creditCeilingExcessExact.toDouble()

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

    val isShiftClosed: Boolean
        get() {
            val snap = shiftCloseSuccessSnapshot
            if (snap != null) {
                val expectedDate = liveShiftSummary?.shiftDate ?: todayDate
                if (snap.shiftDate == expectedDate && snap.isShiftClosed) {
                    return true
                }
            }
            return isShiftClosedPreview
        }

    val shiftClosureTimestamp: String?
        get() {
            val snap = shiftCloseSuccessSnapshot
            if (snap != null) {
                val expectedDate = liveShiftSummary?.shiftDate ?: todayDate
                if (snap.shiftDate == expectedDate && snap.isShiftClosed) {
                    return snap.closedAt ?: snap.observedAt
                }
            }
            return shiftClosedTimestamp
        }

    val shiftObservationTimestamp: String?
        get() {
            val snap = shiftCloseSuccessSnapshot
            if (snap != null) {
                val expectedDate = liveShiftSummary?.shiftDate ?: todayDate
                if (snap.shiftDate == expectedDate) {
                    return snap.observedAt ?: snap.closedAt
                }
            }
            return shiftObservedTimestamp
        }

    val drawerCashSalesIls: Double get() = shiftCashSalesIls
    val totalCustomerCollectionsIls: Double get() = shiftCustomerCollectionsIls
    val totalSupplierDisbursementsIls: Double get() = shiftSupplierDisbursementsIls

    // A directory without authorized balances cannot establish an aggregate.
    private fun directoryTotal(values: List<BigDecimal?>, sign: Int): Double {
        if (values.any { it == null }) return Double.NaN // Legacy display-only unknown sentinel.
        return values.filterNotNull().filter { it.signum() == sign }
            .fold(BigDecimal.ZERO) { sum, value -> sum.add(value.abs()) }.toDouble()
    }
    val totalCustomerDebtsIls: Double get() = directoryTotal(customers.map { it.balanceExact }, 1)
    val totalCustomerAdvancesIls: Double get() = directoryTotal(customers.map { it.balanceExact }, -1)
    val totalSupplierPayablesIls: Double
        get() {
            if (suppliersHasMore) return Double.NaN
            return directoryTotal(suppliers.map { it.balanceOwedExact }, 1)
        }
    val totalSupplierAdvancesIls: Double
        get() {
            if (suppliersHasMore) return Double.NaN
            val effectiveAdvances = suppliers.map { s ->
                when {
                    s.advancesExact != null -> s.advancesExact
                    s.balanceOwedExact != null && s.balanceOwedExact < BigDecimal.ZERO -> s.balanceOwedExact.abs()
                    s.balanceOwedExact != null -> BigDecimal.ZERO
                    else -> null
                }
            }
            return directoryTotal(effectiveAdvances, 1)
        }

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
        basket.firstOrNull { it.product.id == productId }?.quantity?.toInt() ?: 0

    fun quantityInBasketExact(productId: String): BigDecimal =
        basket.firstOrNull { it.product.id == productId }?.quantity ?: BigDecimal.ZERO

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

