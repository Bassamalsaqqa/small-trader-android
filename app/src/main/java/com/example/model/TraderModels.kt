package com.example.model

import com.example.data.ProductSearch
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.toNullableString
import com.example.data.mobile.contract.toNullableBigDecimal
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class AppLanguage(val code: String, val isRtl: Boolean) {
    AR("ar", true),
    EN("en", false);

    fun tr(ar: String, en: String): String = if (this == AR) ar else en
}

enum class TraderScreen {
    HOME,
    PEOPLE,
    PURCHASES,
    NEW_SALE,
    INVOICE_REVIEW,
    CASH_PAYMENT,
    RECEIPT,
    INVOICES_HISTORY,
    PRODUCTS_CATALOG,
    PRODUCT_DETAIL,
    CUSTOMERS,
    CUSTOMER_DETAIL,
    SUPPLIERS,
    SUPPLIER_DETAIL,
    EXPENSES,
    FINANCIAL_REPORTS,
    USER_PROFILE,
    BUSINESS_SETTINGS
}

enum class UserRole(val labelAr: String, val labelEn: String) {
    OWNER("مالك المتجر", "Store Owner"),
    MANAGER("مدير مبيعات", "Sales Manager"),
    CASHIER("كاشير مبيعات", "Cashier");

    fun label(lang: AppLanguage): String = lang.tr(labelAr, labelEn)
}

data class UserSession(
    val id: String,
    val name: String,
    val username: String,
    val role: UserRole,
    val branchNameAr: String,
    val branchNameEn: String,
    val isDemoSession: Boolean = true,
    val deviceSessionName: String = "POS Terminal (Demo)",
    val permissions: Set<String>? = null
) {
    fun branch(lang: AppLanguage): String = lang.tr(branchNameAr, branchNameEn)
    fun canManageInventory(): Boolean = permissions?.any { it in setOf("inventory.product.manage", "inventory.stock.view", "inventory.stock.adjust") } ?: (role == UserRole.OWNER || role == UserRole.MANAGER)
    fun canViewReports(): Boolean = permissions?.contains("reports.financial.view") ?: (role == UserRole.OWNER || role == UserRole.MANAGER)
    fun canManageSuppliers(): Boolean = permissions?.contains("vendors.view") ?: (role == UserRole.OWNER || role == UserRole.MANAGER)
    fun canManageExpenses(): Boolean = permissions?.any { it in setOf("money.expense.view", "money.expense.manage") } ?: (role == UserRole.OWNER || role == UserRole.MANAGER)
    fun canApplyDiscounts(): Boolean = true
}

data class BusinessInfo(
    val nameAr: String = "سوبرماركت ومحمصة الزيتونة",
    val nameEn: String = "Al-Zaytouna Market & Roastery",
    val cityAr: String = "نابلس - البلدة القديمة",
    val cityEn: String = "Old City, Nablus",
    val phone: String = "09-2381042",
    val taxNumber: String = "901248731",
    val defaultCurrency: String = "ILS",
    val defaultTaxPercent: Double = 0.0, // Most small grocery / roastery items in local market retail are zero-rated / inclusive
    val laravelSyncStatus: String = "جاهز للمزامنة مع نظام الحسابات (Laravel API v1)",
    val defaultCurrencyMinorUnits: Int = 2
) {
    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun city(lang: AppLanguage): String = lang.tr(cityAr, cityEn)
}

enum class ProductCategory(val labelAr: String, val labelEn: String) {
    ALL("الكل", "All"),
    PANTRY("مواد تموينية", "Pantry"),
    DAIRY("ألبان وأجبان", "Dairy & Cheese"),
    ROASTERY("محمصة وبهارات", "Roastery & Spices"),
    BEVERAGES("مشروبات", "Beverages"),
    HOUSEHOLD("منظفات وعناية", "Household");

    fun label(lang: AppLanguage): String = lang.tr(labelAr, labelEn)
}

data class Product(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val category: ProductCategory,
    val barcode: String,
    val priceExact: BigDecimal?,
    val costExact: BigDecimal? = null,
    val unitAr: String,
    val unitEn: String,
    val stockCount: Int,
    val minStockThreshold: Int = 8,
    val accentHex: Long,
    val isStockTracked: Boolean = true,
    val warehouseName: String? = null,
    val lotBatchCode: String? = null,
    val expiryDate: String? = null,
    val isWeightedDisplayOnly: Boolean = false,
    val productUnitId: String? = null,
    val taxRateId: String? = null,
    val taxRateExact: BigDecimal? = null,
    val isTaxInclusive: Boolean = false,
    val stockCountExact: BigDecimal = BigDecimal.valueOf(stockCount.toLong()),
    val minStockThresholdExact: BigDecimal? = BigDecimal.valueOf(minStockThreshold.toLong()),
    val conversionToBaseExact: BigDecimal = BigDecimal.ONE,
    val sku: String? = null
) {
    val code: String? get() = sku
    constructor(
        id: String,
        nameAr: String,
        nameEn: String,
        category: ProductCategory,
        barcode: String,
        priceIls: Double,
        costIls: Double,
        unitAr: String,
        unitEn: String,
        stockCount: Int,
        minStockThreshold: Int = 8,
        accentHex: Long,
        isStockTracked: Boolean = true,
        warehouseAr: String? = null,
        warehouseEn: String? = null,
        lotBatchCode: String? = null,
        expiryDate: String? = null,
        isWeightedDisplayOnly: Boolean = false,
        sku: String? = null
    ) : this(
        id = id,
        nameAr = nameAr,
        nameEn = nameEn,
        category = category,
        barcode = barcode,
        priceExact = if (priceIls.isNaN() || priceIls.isInfinite()) null else BigDecimal(priceIls.toString()),
        costExact = if (costIls.isNaN() || costIls.isInfinite()) null else BigDecimal(costIls.toString()),
        unitAr = unitAr,
        unitEn = unitEn,
        stockCount = stockCount,
        minStockThreshold = minStockThreshold,
        accentHex = accentHex,
        isStockTracked = isStockTracked,
        warehouseName = warehouseAr ?: warehouseEn,
        lotBatchCode = lotBatchCode,
        expiryDate = expiryDate,
        isWeightedDisplayOnly = isWeightedDisplayOnly,
        productUnitId = null,
        taxRateId = null,
        taxRateExact = null,
        isTaxInclusive = false,
        stockCountExact = BigDecimal.valueOf(stockCount.toLong()),
        minStockThresholdExact = BigDecimal.valueOf(minStockThreshold.toLong()),
        conversionToBaseExact = BigDecimal.ONE,
        sku = sku
    )

    constructor(
        id: String,
        nameAr: String,
        nameEn: String,
        category: ProductCategory,
        barcode: String,
        priceExact: BigDecimal?,
        costExact: BigDecimal? = null,
        unitAr: String,
        unitEn: String,
        stockCountExact: BigDecimal,
        minStockThresholdExact: BigDecimal? = BigDecimal.valueOf(8),
        accentHex: Long,
        isStockTracked: Boolean = true,
        warehouseName: String? = null,
        lotBatchCode: String? = null,
        expiryDate: String? = null,
        isWeightedDisplayOnly: Boolean = false,
        productUnitId: String? = null,
        taxRateId: String? = null,
        taxRateExact: BigDecimal? = null,
        isTaxInclusive: Boolean = false,
        conversionToBaseExact: BigDecimal = BigDecimal.ONE,
        sku: String? = null
    ) : this(
        id = id,
        nameAr = nameAr,
        nameEn = nameEn,
        category = category,
        barcode = barcode,
        priceExact = priceExact,
        costExact = costExact,
        unitAr = unitAr,
        unitEn = unitEn,
        stockCount = stockCountExact.toInt(),
        minStockThreshold = minStockThresholdExact?.toInt() ?: 8,
        accentHex = accentHex,
        isStockTracked = isStockTracked,
        warehouseName = warehouseName,
        lotBatchCode = lotBatchCode,
        expiryDate = expiryDate,
        isWeightedDisplayOnly = isWeightedDisplayOnly,
        productUnitId = productUnitId,
        taxRateId = taxRateId,
        taxRateExact = taxRateExact,
        isTaxInclusive = isTaxInclusive,
        stockCountExact = stockCountExact,
        minStockThresholdExact = minStockThresholdExact,
        conversionToBaseExact = conversionToBaseExact,
        sku = sku
    )

    val priceIls: Double get() = priceExact?.toDouble() ?: 0.0
    val costIls: Double get() = costExact?.toDouble() ?: 0.0
    val priceString: String get() = priceExact?.toPlainString() ?: ""
    val costString: String? get() = costExact?.toPlainString()
    val stockCountInt: Int get() = stockCount
    val minStockThresholdInt: Int get() = minStockThreshold
    val warehouseAr: String? get() = warehouseName
    val warehouseEn: String? get() = warehouseName
    val hasExplicitPrice: Boolean get() = priceExact != null

    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun unit(lang: AppLanguage): String = lang.tr(unitAr, unitEn)
    fun warehouse(lang: AppLanguage): String = warehouseName ?: lang.tr("المستودع الرئيسي", "Main Warehouse")
    val isLowStock: Boolean
        get() = isStockTracked && stockCountExact <= (minStockThresholdExact ?: BigDecimal.valueOf(8))

    val normalizedSearchTokens: String = ProductSearch.normalize("$nameAr $nameEn $unitAr $unitEn $barcode ${sku ?: ""} ${category.labelAr} ${category.labelEn}")
    val normalizedBarcode: String = ProductSearch.normalize(barcode)
    val normalizedSku: String = ProductSearch.normalize(sku ?: "")
    val normalizedNameAr: String = ProductSearch.normalize(nameAr)
    val normalizedNameEn: String = ProductSearch.normalize(nameEn)
    val normalizedUnitAr: String = ProductSearch.normalize(unitAr)
    val normalizedUnitEn: String = ProductSearch.normalize(unitEn)

    fun computeSearchScore(normalizedQuery: String): Int = ProductSearch.computeScore(this, normalizedQuery)

    companion object {
        fun normalizeKey(raw: String): String = ProductSearch.normalize(raw)
    }
}

data class BasketItem(
    val product: Product,
    val quantity: BigDecimal,
    val unitPriceExact: BigDecimal = product.priceExact ?: BigDecimal.ZERO,
    val taxRateId: String? = product.taxRateId,
    val taxRateExact: BigDecimal? = product.taxRateExact,
    val isTaxInclusive: Boolean = product.isTaxInclusive
) {
    constructor(product: Product, quantity: Int) : this(
        product = product,
        quantity = BigDecimal.valueOf(quantity.toLong()),
        unitPriceExact = product.priceExact ?: BigDecimal.ZERO,
        taxRateId = product.taxRateId,
        taxRateExact = product.taxRateExact,
        isTaxInclusive = product.isTaxInclusive
    )

    val lineTotalExact: BigDecimal
        get() {
            val rawSubtotal = quantity.multiply(unitPriceExact)
            val taxRate = if (taxRateId != null) (taxRateExact ?: BigDecimal.ZERO) else BigDecimal.ZERO
            if (taxRateId == null || taxRate.compareTo(BigDecimal.ZERO) == 0) {
                return rawSubtotal
            }
            if (isTaxInclusive) {
                return rawSubtotal
            }
            val rate = taxRate.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)
            val tax = rawSubtotal.multiply(rate)
            return rawSubtotal.add(tax)
        }

    val lineTotalDecimal: BigDecimal
        get() = lineTotalExact

    val lineTotal: Double
        get() = lineTotalExact.toDouble()
}

data class SaleDraft(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val timestamp: String,
    val items: List<BasketItem>,
    val customerId: String? = null,
    val customerNote: String = "",
    val discountPreset: DiscountPreset = DiscountPreset.NONE,
    val customDiscountText: String = "",
    val subtotalIls: Double = items.sumOf { it.lineTotal },
    val netTotalIls: Double = items.sumOf { it.lineTotal }
) {
    fun title(lang: AppLanguage): String = lang.tr(titleAr, titleEn)
    val totalQuantityDecimal: BigDecimal
        get() = items.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.quantity) }
    val totalQuantity: Int
        get() = totalQuantityDecimal.toInt()
}

enum class DiscountPreset(val labelAr: String, val labelEn: String) {
    NONE("بدون خصم", "No Discount"),
    PERCENT_5("خصم 5%", "5% Off"),
    PERCENT_10("خصم 10%", "10% Off"),
    ROUND_DOWN("جبر الكسر", "Round Down"),
    CUSTOM_ILS("خصم ثابت ₪", "Fixed ₪")
}

enum class InvoicePaymentStatus(val labelAr: String, val labelEn: String) {
    PAID_CASH("مسددة نقداً", "Paid Cash"),
    PARTIAL("سداد جزئي", "Partial"),
    ON_CREDIT("آجلة بالذمة", "On Credit");

    fun label(lang: AppLanguage): String = lang.tr(labelAr, labelEn)
}

data class InvoiceRecord(
    val id: String,
    val invoiceNumber: Int,
    val timestamp: String,
    val items: List<BasketItem>,
    val subtotalExact: BigDecimal,
    val discountExact: BigDecimal,
    val netTotalExact: BigDecimal,
    val cashReceivedExact: BigDecimal,
    val changeReturnedExact: BigDecimal,
    val customerId: String? = null,
    val customerNote: String,
    val cashierNameAr: String,
    val cashierNameEn: String,
    val isPaidInFull: Boolean = true,
    val remainingDebitExact: BigDecimal = BigDecimal.ZERO,
    val printCount: Int = 0,
    val clientEventId: String? = null,
    val serverInvoiceUlid: String? = null,
    val serverInvoiceNumber: String? = null,
    val serverPaymentUlid: String? = null,
    val serverPaymentReceiptNumber: String? = null,
    val serverPostedAt: String? = null,
    val outboxStatus: String? = null
) {
    constructor(
        id: String,
        invoiceNumber: Int,
        timestamp: String,
        items: List<BasketItem>,
        subtotalIls: Double,
        discountIls: Double,
        netTotalIls: Double,
        cashReceivedIls: Double,
        changeReturnedIls: Double,
        customerId: String? = null,
        customerNote: String,
        cashierNameAr: String,
        cashierNameEn: String,
        isPaidInFull: Boolean = true,
        remainingDebitIls: Double = 0.0,
        printCount: Int = 0,
        clientEventId: String? = null,
        serverInvoiceUlid: String? = null,
        serverInvoiceNumber: String? = null,
        serverPaymentUlid: String? = null,
        serverPaymentReceiptNumber: String? = null,
        serverPostedAt: String? = null,
        outboxStatus: String? = null
    ) : this(
        id = id,
        invoiceNumber = invoiceNumber,
        timestamp = timestamp,
        items = items,
        subtotalExact = BigDecimal(subtotalIls.toString()).setScale(2, RoundingMode.HALF_UP),
        discountExact = BigDecimal(discountIls.toString()).setScale(2, RoundingMode.HALF_UP),
        netTotalExact = BigDecimal(netTotalIls.toString()).setScale(2, RoundingMode.HALF_UP),
        cashReceivedExact = BigDecimal(cashReceivedIls.toString()).setScale(2, RoundingMode.HALF_UP),
        changeReturnedExact = BigDecimal(changeReturnedIls.toString()).setScale(2, RoundingMode.HALF_UP),
        customerId = customerId,
        customerNote = customerNote,
        cashierNameAr = cashierNameAr,
        cashierNameEn = cashierNameEn,
        isPaidInFull = isPaidInFull,
        remainingDebitExact = BigDecimal(remainingDebitIls.toString()).setScale(2, RoundingMode.HALF_UP),
        printCount = printCount,
        clientEventId = clientEventId,
        serverInvoiceUlid = serverInvoiceUlid,
        serverInvoiceNumber = serverInvoiceNumber,
        serverPaymentUlid = serverPaymentUlid,
        serverPaymentReceiptNumber = serverPaymentReceiptNumber,
        serverPostedAt = serverPostedAt,
        outboxStatus = outboxStatus
    )

    val subtotalIls: Double get() = subtotalExact.toDouble()
    val discountIls: Double get() = discountExact.toDouble()
    val netTotalIls: Double get() = netTotalExact.toDouble()
    val cashReceivedIls: Double get() = cashReceivedExact.toDouble()
    val changeReturnedIls: Double get() = changeReturnedExact.toDouble()
    val remainingDebitIls: Double get() = remainingDebitExact.toDouble()

    val totalQuantityDecimal: BigDecimal
        get() = items.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.quantity) }

    val totalQuantity: Int
        get() = totalQuantityDecimal.toInt()

    val paymentStatus: InvoicePaymentStatus
        get() = when {
            isPaidInFull || remainingDebitExact <= BigDecimal("0.005") -> InvoicePaymentStatus.PAID_CASH
            cashReceivedExact > BigDecimal("0.005") -> InvoicePaymentStatus.PARTIAL
            else -> InvoicePaymentStatus.ON_CREDIT
        }
}

data class Customer(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val phone: String? = null,
    val addressAr: String? = null,
    val addressEn: String? = null,
    val balanceExact: BigDecimal? = null,
    val creditLimitExact: BigDecimal? = null,
    val totalPurchasesExact: BigDecimal? = null,
    val isWalkIn: Boolean = false,
    val code: String? = null,
    val isDetailLoaded: Boolean = false
) {
    constructor(
        id: String,
        nameAr: String,
        nameEn: String,
        phone: String,
        addressAr: String,
        addressEn: String,
        balanceIls: Double,
        creditLimitIls: Double = 1000.0,
        totalPurchasesIls: Double = 0.0
    ) : this(
        id = id,
        nameAr = nameAr,
        nameEn = nameEn,
        phone = phone.ifBlank { null },
        addressAr = addressAr.ifBlank { null },
        addressEn = addressEn.ifBlank { null },
        balanceExact = BigDecimal(balanceIls.toString()).setScale(2, RoundingMode.HALF_UP),
        creditLimitExact = BigDecimal(creditLimitIls.toString()).setScale(2, RoundingMode.HALF_UP),
        totalPurchasesExact = BigDecimal(totalPurchasesIls.toString()).setScale(2, RoundingMode.HALF_UP),
        isDetailLoaded = true
    )

    // Legacy presentation bridge: NaN means unavailable, never an economic zero. Use balanceExact for arithmetic.
    val balanceIls: Double get() = balanceExact?.toDouble() ?: Double.NaN
    val creditLimitIls: Double get() = creditLimitExact?.toDouble() ?: 0.0
    val totalPurchasesIls: Double get() = totalPurchasesExact?.toDouble() ?: 0.0

    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun address(lang: AppLanguage): String = lang.tr(addressAr ?: "", addressEn ?: "")
}

data class CustomerTransaction(
    val id: String,
    val customerId: String,
    val date: String,
    val typeAr: String,
    val typeEn: String,
    val invoiceNumber: Int? = null,
    val debitExact: BigDecimal,
    val creditExact: BigDecimal,
    val runningBalanceExact: BigDecimal? = null,
    val note: String = "",
    val isAdvance: Boolean = false,
    val allocatedAmountExact: BigDecimal = BigDecimal.ZERO,
    val unallocatedAmountExact: BigDecimal = BigDecimal.ZERO,
    val allocations: List<PaymentAllocationResult> = emptyList(),
    val currency: String = "ILS"
) {
    constructor(
        id: String,
        customerId: String,
        date: String,
        typeAr: String,
        typeEn: String,
        invoiceNumber: Int? = null,
        debitIls: Double,
        creditIls: Double,
        runningBalanceIls: Double,
        note: String = ""
    ) : this(
        id = id,
        customerId = customerId,
        date = date,
        typeAr = typeAr,
        typeEn = typeEn,
        invoiceNumber = invoiceNumber,
        debitExact = BigDecimal(debitIls.toString()).setScale(2, RoundingMode.HALF_UP),
        creditExact = BigDecimal(creditIls.toString()).setScale(2, RoundingMode.HALF_UP),
        runningBalanceExact = BigDecimal(runningBalanceIls.toString()).setScale(2, RoundingMode.HALF_UP),
        note = note,
        isAdvance = false,
        allocatedAmountExact = BigDecimal.ZERO,
        unallocatedAmountExact = BigDecimal.ZERO,
        allocations = emptyList(),
        currency = "ILS"
    )

    val debitIls: Double get() = debitExact.toDouble()
    val creditIls: Double get() = creditExact.toDouble()
    val runningBalanceIls: Double? get() = runningBalanceExact?.toDouble()

    fun type(lang: AppLanguage): String = lang.tr(typeAr, typeEn)
}

data class Supplier(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val companyAr: String? = null,
    val companyEn: String? = null,
    val phone: String? = null,
    val balanceOwedExact: BigDecimal? = null,
    val advancesExact: BigDecimal? = null,
    val code: String? = null
) {
    constructor(
        id: String,
        nameAr: String,
        nameEn: String,
        companyAr: String,
        companyEn: String,
        phone: String,
        balanceOwedIls: Double
    ) : this(
        id = id,
        nameAr = nameAr,
        nameEn = nameEn,
        companyAr = companyAr.ifBlank { null },
        companyEn = companyEn.ifBlank { null },
        phone = phone.ifBlank { null },
        balanceOwedExact = BigDecimal(balanceOwedIls.toString()).setScale(2, RoundingMode.HALF_UP),
        advancesExact = null,
        code = null
    )

    // Legacy presentation bridge; calculations must use nullable balanceOwedExact / advancesExact.
    val balanceOwedIls: Double get() = balanceOwedExact?.toDouble() ?: Double.NaN
    val advancesIls: Double
        get() = advancesExact?.toDouble()
            ?: if (!balanceOwedIls.isNaN() && balanceOwedIls < -0.005) -balanceOwedIls
            else if (!balanceOwedIls.isNaN()) 0.0
            else Double.NaN

    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun company(lang: AppLanguage): String = lang.tr(companyAr ?: "", companyEn ?: "")
}

data class PurchaseLineItem(
    val productId: String,
    val productNameAr: String = "",
    val productNameEn: String = "",
    val quantity: Int = 0,
    val unitCostExact: BigDecimal? = null,
    val receivedQuantity: Int = 0,
    val id: String = UUID.randomUUID().toString(),
    val quantityExact: BigDecimal = BigDecimal.valueOf(quantity.toLong()),
    val receivedQuantityExact: BigDecimal = BigDecimal.valueOf(receivedQuantity.toLong())
) {
    constructor(
        productId: String,
        productNameAr: String = "",
        productNameEn: String = "",
        quantity: Int,
        unitCostIls: Double,
        receivedQuantity: Int = 0,
        id: String = UUID.randomUUID().toString()
    ) : this(
        productId = productId,
        productNameAr = productNameAr,
        productNameEn = productNameEn,
        quantity = quantity,
        unitCostExact = if (unitCostIls.isNaN() || unitCostIls.isInfinite()) {
            null
        } else {
            try { BigDecimal(unitCostIls.toString()).setScale(2, RoundingMode.HALF_UP) } catch (_: Exception) { null }
        },
        receivedQuantity = receivedQuantity,
        id = id,
        quantityExact = BigDecimal.valueOf(quantity.toLong()),
        receivedQuantityExact = BigDecimal.valueOf(receivedQuantity.toLong())
    )

    constructor(
        productId: String,
        productNameAr: String = "",
        productNameEn: String = "",
        quantityExact: BigDecimal,
        unitCostExact: BigDecimal? = null,
        receivedQuantityExact: BigDecimal = BigDecimal.ZERO,
        id: String = UUID.randomUUID().toString()
    ) : this(
        productId = productId,
        productNameAr = productNameAr,
        productNameEn = productNameEn,
        quantity = quantityExact.toInt(),
        unitCostExact = unitCostExact,
        receivedQuantity = receivedQuantityExact.toInt(),
        id = id,
        quantityExact = quantityExact,
        receivedQuantityExact = receivedQuantityExact
    )

    fun name(lang: AppLanguage): String = lang.tr(productNameAr, productNameEn)

    val unitCostIls: Double? get() = unitCostExact?.toDouble()
    val totalCostExact: BigDecimal? get() = unitCostExact?.let { quantityExact.multiply(it).setScale(2, RoundingMode.HALF_UP) }
    val totalCostIls: Double? get() = totalCostExact?.toDouble()
    val pendingQuantity: Int get() = (quantity - receivedQuantity).coerceAtLeast(0)
    val pendingQuantityExact: BigDecimal get() = (quantityExact - receivedQuantityExact).coerceAtLeast(BigDecimal.ZERO)
    val isReceived: Boolean get() = quantityExact > BigDecimal.ZERO && receivedQuantityExact >= quantityExact
    val isPartiallyReceived: Boolean get() = !isReceived && receivedQuantityExact > BigDecimal.ZERO
}

data class PurchaseBill(
    val id: String,
    val billNumber: String,
    val supplierId: String,
    val date: String = "",
    val totalExact: BigDecimal = BigDecimal.ZERO,
    val paidExact: BigDecimal = BigDecimal.ZERO,
    val itemsSummary: String = "",
    val isReceived: Boolean = false,
    val lines: List<PurchaseLineItem> = emptyList(),
    val expectedProductId: String? = null,
    val expectedQuantity: Int = 0,
    val receivedQuantity: Int = 0,
    val expectedQuantityExact: BigDecimal = BigDecimal.valueOf(expectedQuantity.toLong()),
    val receivedQuantityExact: BigDecimal = BigDecimal.valueOf(receivedQuantity.toLong()),
    val currency: String = "ILS",
    val currencyMinorUnits: Int = 2
) {
    val billNumberInt: Int get() = billNumber.filter { it.isDigit() }.toIntOrNull() ?: 0

    constructor(
        id: String,
        supplierId: String,
        billNumber: String,
        totalCostIls: Double = 0.0,
        paidNowIls: Double = 0.0,
        date: String = "",
        itemsSummary: String = "",
        isReceived: Boolean = false,
        lines: List<PurchaseLineItem> = emptyList()
    ) : this(
        id = id,
        billNumber = billNumber,
        supplierId = supplierId,
        date = date,
        totalExact = BigDecimal(totalCostIls.toString()).setScale(2, RoundingMode.HALF_UP),
        paidExact = BigDecimal(paidNowIls.toString()).setScale(2, RoundingMode.HALF_UP),
        itemsSummary = itemsSummary,
        isReceived = isReceived,
        lines = lines
    )

    constructor(
        id: String,
        billNumber: Int,
        supplierId: String,
        date: String,
        totalIls: Double,
        paidIls: Double,
        itemsSummary: String,
        isReceived: Boolean = false,
        lines: List<PurchaseLineItem> = emptyList(),
        expectedProductId: String? = null,
        expectedQuantity: Int = 0,
        receivedQuantity: Int = 0
    ) : this(
        id = id,
        billNumber = billNumber.toString(),
        supplierId = supplierId,
        date = date,
        totalExact = BigDecimal(totalIls.toString()).setScale(2, RoundingMode.HALF_UP),
        paidExact = BigDecimal(paidIls.toString()).setScale(2, RoundingMode.HALF_UP),
        itemsSummary = itemsSummary,
        isReceived = isReceived,
        lines = lines,
        expectedProductId = expectedProductId,
        expectedQuantity = expectedQuantity,
        receivedQuantity = receivedQuantity,
        expectedQuantityExact = BigDecimal.valueOf(expectedQuantity.toLong()),
        receivedQuantityExact = BigDecimal.valueOf(receivedQuantity.toLong())
    )

    constructor(
        id: String,
        billNumber: Int,
        supplierId: String,
        date: String,
        totalExact: BigDecimal,
        paidExact: BigDecimal,
        itemsSummary: String,
        isReceived: Boolean = false,
        lines: List<PurchaseLineItem> = emptyList(),
        expectedProductId: String? = null,
        expectedQuantityExact: BigDecimal,
        receivedQuantityExact: BigDecimal = BigDecimal.ZERO
    ) : this(
        id = id,
        billNumber = billNumber.toString(),
        supplierId = supplierId,
        date = date,
        totalExact = totalExact,
        paidExact = paidExact,
        itemsSummary = itemsSummary,
        isReceived = isReceived,
        lines = lines,
        expectedProductId = expectedProductId,
        expectedQuantity = expectedQuantityExact.toInt(),
        receivedQuantity = receivedQuantityExact.toInt(),
        expectedQuantityExact = expectedQuantityExact,
        receivedQuantityExact = receivedQuantityExact
    )

    val totalIls: Double get() = totalExact.toDouble()
    val paidIls: Double get() = paidExact.toDouble()
    val remainingPayableExact: BigDecimal get() = (totalExact - paidExact).coerceAtLeast(BigDecimal.ZERO)
    val remainingPayableIls: Double get() = remainingPayableExact.toDouble()
    val isIls: Boolean get() = currency.equals("ILS", ignoreCase = true)
    val formattedTotal: String get() = WesternFormatters.formatCurrency(totalExact, currency, currencyMinorUnits)
    val formattedPaid: String get() = WesternFormatters.formatCurrency(paidExact, currency, currencyMinorUnits)
    val formattedRemainingPayable: String get() = WesternFormatters.formatCurrency(remainingPayableExact, currency, currencyMinorUnits)

    val effectiveLines: List<PurchaseLineItem>
        get() = if (lines.isNotEmpty()) {
            lines
        } else if (!expectedProductId.isNullOrBlank() && expectedQuantityExact > BigDecimal.ZERO) {
            listOf(
                PurchaseLineItem(
                    productId = expectedProductId,
                    productNameAr = itemsSummary,
                    productNameEn = itemsSummary,
                    quantityExact = expectedQuantityExact,
                    unitCostExact = if (expectedQuantityExact > BigDecimal.ZERO) totalExact.divide(expectedQuantityExact, 4, RoundingMode.HALF_UP) else BigDecimal.ZERO,
                    receivedQuantityExact = receivedQuantityExact
                )
            )
        } else {
            emptyList()
        }

    val pendingQuantity: Int
        get() = if (lines.isNotEmpty()) {
            lines.sumOf { it.pendingQuantity }
        } else {
            (expectedQuantity - receivedQuantity).coerceAtLeast(0)
        }

    val pendingQuantityExact: BigDecimal
        get() = if (lines.isNotEmpty()) {
            lines.fold(BigDecimal.ZERO) { acc, it -> acc.add(it.pendingQuantityExact) }
        } else {
            (expectedQuantityExact - receivedQuantityExact).coerceAtLeast(BigDecimal.ZERO)
        }

    val isPartiallyReceived: Boolean
        get() = !isReceived && (
            if (lines.isNotEmpty()) lines.any { it.isPartiallyReceived || it.isReceived } && lines.any { !it.isReceived }
            else receivedQuantityExact > BigDecimal.ZERO && receivedQuantityExact < expectedQuantityExact
        )
}

data class SupplierPayment(
    val id: String,
    val supplierId: String,
    val date: String,
    val amountExact: BigDecimal,
    val note: String = "سداد نقدي من الصندوق",
    val isAdvance: Boolean = false,
    val allocatedAmountExact: BigDecimal = BigDecimal.ZERO,
    val unallocatedAmountExact: BigDecimal = BigDecimal.ZERO,
    val allocations: List<PaymentAllocationResult> = emptyList(),
    val currency: String = "ILS"
) {
    constructor(
        id: String,
        supplierId: String,
        date: String,
        amountIls: Double,
        note: String = "سداد نقدي من الصندوق"
    ) : this(
        id = id,
        supplierId = supplierId,
        date = date,
        amountExact = BigDecimal(amountIls.toString()).setScale(2, RoundingMode.HALF_UP),
        note = note,
        isAdvance = false,
        allocatedAmountExact = BigDecimal.ZERO,
        unallocatedAmountExact = BigDecimal.ZERO,
        allocations = emptyList(),
        currency = "ILS"
    )

    val amountIls: Double get() = amountExact.toDouble()
}

/**
 * Typed allocation result returned from authoritative backend payment responses.
 */
data class PaymentAllocationResult(
    val targetType: String,
    val targetId: String,
    val amountExact: BigDecimal,
    val allocatedAmountExact: BigDecimal = amountExact
)

/**
 * Client input for allocating a payment to a specific invoice, purchase bill, or opening trade item.
 */
data class PaymentAllocationInput(
    val targetType: String,
    val targetId: String,
    val amountExact: BigDecimal
) {
    init {
        require(amountExact > BigDecimal.ZERO) { "Allocation amount must be strictly positive" }
    }
}

/**
 * Authoritative open settlement target (Invoice, Purchase Bill, or Opening Trade Item).
 */
data class SettlementTarget(
    val targetType: String,
    val targetId: String,
    val targetNumber: String,
    val businessDate: String,
    val dueDate: String? = null,
    val daysOverdue: Int = 0,
    val currency: String = "ILS",
    val currencyMinorUnits: Int = 2,
    val originalAmountExact: BigDecimal,
    val allocatedAmountExact: BigDecimal,
    val remainingAmountExact: BigDecimal
) {
    init {
        require(remainingAmountExact >= BigDecimal.ZERO) { "Remaining amount must be non-negative" }
    }
}

data class SettlementCurrencyTotal(
    val amountExact: BigDecimal,
    val minorUnits: Int
)

data class SettlementTargetsResponse(
    val partyId: String,
    val partyName: String,
    val currency: String?,
    val currencyMinorUnits: Int,
    val totalTargets: Int,
    val targets: List<SettlementTarget>,
    val hasMore: Boolean,
    val nextCursor: String?,
    val totalsRemainingByCurrency: Map<String, SettlementCurrencyTotal> = emptyMap(),
    val totalsOriginalByCurrency: Map<String, SettlementCurrencyTotal> = emptyMap()
) {
    companion object {
        fun fromStrictJson(
            root: StrictJsonObject,
            isSupplier: Boolean = false,
            fallbackMinorUnits: Int = 2
        ): SettlementTargetsResponse {
            val partyId = if (isSupplier) {
                root.properties["supplier_id"].toNullableString() ?: ""
            } else {
                root.properties["customer_id"].toNullableString() ?: ""
            }
            val partyName = if (isSupplier) {
                root.properties["supplier_name"].toNullableString() ?: ""
            } else {
                root.properties["customer_name"].toNullableString() ?: ""
            }
            val currency = root.properties["currency"].toNullableString()
            val minorUnits = root.properties["currency_minor_units"]?.asNumber()?.toInt() ?: fallbackMinorUnits
            val totalTargets = root.properties["total_targets"]?.asNumber()?.toInt() ?: 0

            val targetsList = mutableListOf<SettlementTarget>()
            val targetsEl = root.properties["targets"]
            if (targetsEl is StrictJsonArray) {
                for (item in targetsEl.elements) {
                    if (item is StrictJsonObject) {
                        val tType = item.properties["target_type"].toNullableString() ?: continue
                        val tId = item.properties["target_id"].toNullableString() ?: continue
                        val tNum = item.properties["target_number"].toNullableString() ?: tId
                        val bDate = item.properties["business_date"].toNullableString()
                            ?: item.properties["opening_date"].toNullableString()
                            ?: item.properties["issue_date"].toNullableString()
                            ?: item.properties["purchase_date"].toNullableString()
                            ?: WesternFormatters.currentDate()
                        val dDate = item.properties["due_date"].toNullableString()
                        val overdue = item.properties["days_overdue"]?.asNumber()?.toInt() ?: 0
                        val curr = item.properties["currency"].toNullableString() ?: currency ?: "ILS"
                        val itemMinUnits = item.properties["currency_minor_units"]?.asNumber()?.toInt() ?: minorUnits

                        val orig = item.properties["original_amount_exact"].toNullableBigDecimal()
                            ?: item.properties["original_amount"].toNullableBigDecimal(itemMinUnits)
                            ?: BigDecimal.ZERO
                        val alloc = item.properties["allocated_amount_exact"].toNullableBigDecimal()
                            ?: item.properties["allocated_amount"].toNullableBigDecimal(itemMinUnits)
                            ?: BigDecimal.ZERO
                        val rem = item.properties["remaining_amount_exact"].toNullableBigDecimal()
                            ?: item.properties["remaining_amount"].toNullableBigDecimal(itemMinUnits)
                            ?: BigDecimal.ZERO

                        targetsList.add(
                            SettlementTarget(
                                targetType = tType,
                                targetId = tId,
                                targetNumber = tNum,
                                businessDate = bDate,
                                dueDate = dDate,
                                daysOverdue = overdue,
                                currency = curr,
                                currencyMinorUnits = itemMinUnits,
                                originalAmountExact = orig,
                                allocatedAmountExact = alloc,
                                remainingAmountExact = rem
                            )
                        )
                    }
                }
            }

            var hasMore = false
            var nextCursor: String? = null
            val pagEl = root.properties["pagination"]
            if (pagEl is StrictJsonObject) {
                hasMore = pagEl.properties["has_more"]?.asBoolean() ?: false
                nextCursor = pagEl.properties["next_cursor"].toNullableString()
            }

            val remainingTotals = mutableMapOf<String, SettlementCurrencyTotal>()
            val originalTotals = mutableMapOf<String, SettlementCurrencyTotal>()
            val totalsEl = root.properties["totals"]
            if (totalsEl is StrictJsonObject) {
                val remEl = totalsEl.properties["remaining_by_currency"]
                if (remEl is StrictJsonObject) {
                    for ((cCode, cVal) in remEl.properties) {
                        if (cVal is StrictJsonObject) {
                            val mUnits = cVal.properties["minor_units"]?.asNumber()?.toInt() ?: minorUnits
                            val amt = cVal.properties["amount_exact"].toNullableBigDecimal(mUnits)
                                ?: cVal.properties["amount"].toNullableBigDecimal(mUnits)
                                ?: BigDecimal.ZERO
                            remainingTotals[cCode] = SettlementCurrencyTotal(amt, mUnits)
                        }
                    }
                }
                val origEl = totalsEl.properties["original_by_currency"]
                if (origEl is StrictJsonObject) {
                    for ((cCode, cVal) in origEl.properties) {
                        if (cVal is StrictJsonObject) {
                            val mUnits = cVal.properties["minor_units"]?.asNumber()?.toInt() ?: minorUnits
                            val amt = cVal.properties["amount_exact"].toNullableBigDecimal(mUnits)
                                ?: cVal.properties["amount"].toNullableBigDecimal(mUnits)
                                ?: BigDecimal.ZERO
                            originalTotals[cCode] = SettlementCurrencyTotal(amt, mUnits)
                        }
                    }
                }
            }

            return SettlementTargetsResponse(
                partyId = partyId,
                partyName = partyName,
                currency = currency,
                currencyMinorUnits = minorUnits,
                totalTargets = totalTargets,
                targets = targetsList,
                hasMore = hasMore,
                nextCursor = nextCursor,
                totalsRemainingByCurrency = remainingTotals,
                totalsOriginalByCurrency = originalTotals
            )
        }
    }
}

enum class ExpenseCategory(val labelAr: String, val labelEn: String) {
    RENT("إيجار المحل", "Store Rent"),
    ELECTRICITY_WATER("كهرباء ومياه", "Electricity & Water"),
    TRANSPORT("نقل وشحن بضائع", "Transport & Freight"),
    SALARIES("رواتب ومياومة", "Salaries & Wages"),
    MAINTENANCE("صيانة وضيافة", "Maintenance & Hospitality"),
    OTHER("مصاريف أخرى", "Other Expenses");

    fun label(lang: AppLanguage): String = lang.tr(labelAr, labelEn)
}

data class ServerExpenseCategory(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val code: String? = null
) {
    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
}

data class ExpenseEntry(
    val id: String,
    val date: String,
    val titleAr: String,
    val titleEn: String,
    val category: ExpenseCategory,
    val amountExact: BigDecimal,
    val paidFrom: String = "الصندوق الرئيسي (كاش)"
) {
    constructor(
        id: String,
        date: String,
        titleAr: String,
        titleEn: String,
        category: ExpenseCategory,
        amountIls: Double,
        paidFrom: String = "الصندوق الرئيسي (كاش)"
    ) : this(
        id = id,
        date = date,
        titleAr = titleAr,
        titleEn = titleEn,
        category = category,
        amountExact = BigDecimal(amountIls.toString()).setScale(2, RoundingMode.HALF_UP),
        paidFrom = paidFrom
    )

    val amountIls: Double get() = amountExact.toDouble()
    fun title(lang: AppLanguage): String = lang.tr(titleAr, titleEn)
}

data class StockMovement(
    val id: String,
    val productId: String,
    val date: String,
    val deltaExact: BigDecimal,
    val reasonAr: String,
    val reasonEn: String,
    val resultingStockExact: BigDecimal? = null
) {
    constructor(
        id: String,
        productId: String,
        date: String,
        delta: Int,
        reasonAr: String,
        reasonEn: String,
        resultingStock: Int
    ) : this(
        id = id,
        productId = productId,
        date = date,
        deltaExact = BigDecimal.valueOf(delta.toLong()),
        reasonAr = reasonAr,
        reasonEn = reasonEn,
        resultingStockExact = BigDecimal.valueOf(resultingStock.toLong())
    )

    val delta: Int get() = deltaExact.toInt()
    val resultingStock: Int get() = resultingStockExact?.toInt() ?: 0

    fun reason(lang: AppLanguage): String = lang.tr(reasonAr, reasonEn)
}

data class BluetoothPrinterDevice(
    val id: String,
    val name: String,
    val address: String,
    val paperWidthMm: Int,
    val batteryPercent: Int,
    val isPaired: Boolean = true
)

enum class PrinterStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    READY,
    PRINTING,
    PRINTED_SUCCESS,
    ERROR
}

/**
 * Enforces Western digits (0-9) across both Arabic RTL and English LTR experiences.
 * Uses exact BigDecimal math for financial totals.
 */
object WesternFormatters {
    fun formatExactOrPlain(value: java.math.BigDecimal?): String = value?.toPlainString() ?: "—"

    private val usSymbols = DecimalFormatSymbols(Locale.US)
    private val moneyFormat = DecimalFormat("#,##0.00", usSymbols)
    private val compactMoneyFormat = DecimalFormat("#,##0.##", usSymbols)
    private val intFormat = DecimalFormat("#,##0", usSymbols)

    fun formatIls(amount: BigDecimal?): String {
        if (amount == null) return "-- ₪"
        val safe = if (amount.abs() < BigDecimal("0.005")) BigDecimal.ZERO else amount
        return "${moneyFormat.format(safe)} ₪"
    }

    fun formatCurrency(amount: BigDecimal?, currency: String = "ILS", minorUnits: Int = 2): String {
        if (amount == null) return if (currency == "ILS") "-- ₪" else "-- $currency"
        val pattern = if (minorUnits <= 0) "#,##0" else "#,##0." + "0".repeat(minorUnits)
        val fmt = DecimalFormat(pattern, usSymbols)
        val threshold = if (minorUnits <= 0) BigDecimal("0.5") else BigDecimal("0.5").movePointLeft(minorUnits)
        val safe = if (amount.abs() < threshold) BigDecimal.ZERO.setScale(minorUnits, RoundingMode.HALF_UP) else amount.setScale(minorUnits, RoundingMode.HALF_UP)
        return if (currency == "ILS") "${fmt.format(safe)} ₪" else "${fmt.format(safe)} $currency"
    }

    fun formatCurrency(amount: Double, currency: String = "ILS", minorUnits: Int = 2): String {
        if (!amount.isFinite()) return if (currency == "ILS") "-- ₪" else "-- $currency"
        return formatCurrency(BigDecimal(amount.toString()), currency, minorUnits)
    }

    fun formatIls(amount: Double): String {
        if (!amount.isFinite()) return "-- ₪"
        val safe = if (kotlin.math.abs(amount) < 0.005) 0.0 else amount
        return "${moneyFormat.format(safe)} ₪"
    }

    fun formatCompactIls(amount: BigDecimal?): String {
        if (amount == null) return "-- ₪"
        val safe = if (amount.abs() < BigDecimal("0.005")) BigDecimal.ZERO else amount
        return "${compactMoneyFormat.format(safe)} ₪"
    }

    fun formatCompactIls(amount: Double): String {
        if (!amount.isFinite()) return "-- ₪"
        val safe = if (kotlin.math.abs(amount) < 0.005) 0.0 else amount
        return "${compactMoneyFormat.format(safe)} ₪"
    }

    fun formatNumber(amount: BigDecimal?): String {
        if (amount == null) return "--"
        val safe = if (amount.abs() < BigDecimal("0.005")) BigDecimal.ZERO else amount
        return moneyFormat.format(safe)
    }

    fun formatNumber(amount: Double): String {
        val safe = if (kotlin.math.abs(amount) < 0.005) 0.0 else amount
        return moneyFormat.format(safe)
    }

    fun formatQuantity(qty: BigDecimal): String {
        return if (qty.stripTrailingZeros().scale() <= 0) {
            intFormat.format(qty.toLong())
        } else {
            qty.stripTrailingZeros().toPlainString()
        }
    }
    fun formatQuantity(qty: Int): String = intFormat.format(qty)

    fun formatInt(value: Int): String = intFormat.format(value)
    fun formatInt(value: BigDecimal): String = formatQuantity(value)

    fun formatInvoiceCode(number: Int): String = String.format(Locale.US, "#INV-%04d", number)
    fun formatBillCode(number: Int): String = String.format(Locale.US, "#BILL-%04d", number)
    fun formatBillCode(code: String): String = if (code.startsWith("#")) code else "#$code"

    fun currentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return sdf.format(Date())
    }

    fun currentDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date())
    }

    fun sanitizeWesternDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                '٫' -> sb.append('.')
                '٬' -> { /* ignore Arabic thousands separator */ }
                else -> sb.append(ch)
            }
        }
        val converted = sb.toString().trim()
        if (converted.isEmpty()) return ""

        // If it contains both comma and dot (e.g. "1,234.50" or "1.234,50")
        if (converted.contains(',') && converted.contains('.')) {
            val firstComma = converted.indexOf(',')
            val firstDot = converted.indexOf('.')
            return if (firstComma < firstDot) {
                // Standard format: 1,234.50 -> remove commas
                converted.replace(",", "")
            } else {
                // European format: 1.234,50 -> remove dots, replace comma with dot
                converted.replace(".", "").replace(',', '.')
            }
        }
        // If it has multiple commas (e.g. "1,000,000") -> remove all commas
        if (converted.count { it == ',' } > 1) {
            return converted.replace(",", "")
        }
        // If it has a comma followed by 3 digits at the end (e.g. "1,234") -> thousands separator
        if (converted.matches(Regex(".*,\\d{3}"))) {
            return converted.replace(",", "")
        }
        // Single comma used as decimal separator (e.g. "12,50" -> "12.50")
        return converted.replace(',', '.')
    }
}

operator fun BigDecimal.compareTo(other: Int): Int = this.compareTo(BigDecimal.valueOf(other.toLong()))
operator fun BigDecimal.compareTo(other: Double): Int = this.compareTo(BigDecimal(other.toString()))
operator fun BigDecimal.plus(other: Int): BigDecimal = this.add(BigDecimal.valueOf(other.toLong()))
operator fun BigDecimal.minus(other: Int): BigDecimal = this.subtract(BigDecimal.valueOf(other.toLong()))
operator fun BigDecimal.times(other: Int): BigDecimal = this.multiply(BigDecimal.valueOf(other.toLong()))
