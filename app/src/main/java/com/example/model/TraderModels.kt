package com.example.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val deviceSessionName: String = "POS Terminal (Demo)"
) {
    fun branch(lang: AppLanguage): String = lang.tr(branchNameAr, branchNameEn)
    fun canManageInventory(): Boolean = role == UserRole.OWNER || role == UserRole.MANAGER
    fun canViewReports(): Boolean = role == UserRole.OWNER || role == UserRole.MANAGER
    fun canManageSuppliers(): Boolean = role == UserRole.OWNER || role == UserRole.MANAGER
    fun canManageExpenses(): Boolean = role == UserRole.OWNER || role == UserRole.MANAGER
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
    val laravelSyncStatus: String = "جاهز للمزامنة مع نظام الحسابات (Laravel API v1)"
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
    val priceIls: Double,
    val costIls: Double,
    val unitAr: String,
    val unitEn: String,
    val stockCount: Int,
    val minStockThreshold: Int = 8,
    val accentHex: Long,
    val isStockTracked: Boolean = true, // False for custom quick items / non-stock service lines
    val warehouseAr: String = "المستودع الرئيسي - نابلس",
    val warehouseEn: String = "Main Warehouse - Nablus",
    val lotBatchCode: String = "LOT-2026-A",
    val expiryDate: String? = null,
    val isWeightedDisplayOnly: Boolean = false
) {
    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun unit(lang: AppLanguage): String = lang.tr(unitAr, unitEn)
    fun warehouse(lang: AppLanguage): String = lang.tr(warehouseAr, warehouseEn)
    val isLowStock: Boolean
        get() = isStockTracked && stockCount <= minStockThreshold

    val normalizedNameAr: String by lazy { normalizeKey(nameAr) }
    val normalizedNameEn: String by lazy { normalizeKey(nameEn) }
    val normalizedUnitAr: String by lazy { normalizeKey(unitAr) }
    val normalizedUnitEn: String by lazy { normalizeKey(unitEn) }

    companion object {
        fun normalizeKey(raw: String): String {
            if (raw.isEmpty()) return ""
            val sb = StringBuilder(raw.length)
            for (ch in raw.lowercase()) {
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                    'ة' -> sb.append('ه')
                    'ى' -> sb.append('ي')
                    in '\u064B'..'\u065F', '\u0670', '\u0640' -> { /* strip diacritics & tatweel */ }
                    else -> sb.append(ch)
                }
            }
            return sb.toString().trim()
        }
    }
}

data class BasketItem(
    val product: Product,
    val quantity: Int
) {
    val lineTotal: Double
        get() = BigDecimal(product.priceIls.toString())
            .multiply(BigDecimal(quantity.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
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
    val totalQuantity: Int
        get() = items.sumOf { it.quantity }
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
    val subtotalIls: Double,
    val discountIls: Double,
    val netTotalIls: Double,
    val cashReceivedIls: Double,
    val changeReturnedIls: Double,
    val customerId: String? = null,
    val customerNote: String,
    val cashierNameAr: String,
    val cashierNameEn: String,
    val isPaidInFull: Boolean = true,
    val remainingDebitIls: Double = 0.0,
    val printCount: Int = 0
) {
    val totalQuantity: Int
        get() = items.sumOf { it.quantity }

    val paymentStatus: InvoicePaymentStatus
        get() = when {
            isPaidInFull || remainingDebitIls <= 0.005 -> InvoicePaymentStatus.PAID_CASH
            cashReceivedIls > 0.005 -> InvoicePaymentStatus.PARTIAL
            else -> InvoicePaymentStatus.ON_CREDIT
        }
}

data class Customer(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val phone: String,
    val addressAr: String,
    val addressEn: String,
    val balanceIls: Double, // positive means customer owes store (debit)
    val creditLimitIls: Double = 1000.0,
    val totalPurchasesIls: Double = 0.0
) {
    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun address(lang: AppLanguage): String = lang.tr(addressAr, addressEn)
}

data class CustomerTransaction(
    val id: String,
    val customerId: String,
    val date: String,
    val typeAr: String,
    val typeEn: String,
    val invoiceNumber: Int? = null,
    val debitIls: Double,   // customer debt added
    val creditIls: Double,  // customer payment paid
    val runningBalanceIls: Double,
    val note: String = ""
) {
    fun type(lang: AppLanguage): String = lang.tr(typeAr, typeEn)
}

data class Supplier(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val companyAr: String,
    val companyEn: String,
    val phone: String,
    val balanceOwedIls: Double // store owes supplier (payable)
) {
    fun name(lang: AppLanguage): String = lang.tr(nameAr, nameEn)
    fun company(lang: AppLanguage): String = lang.tr(companyAr, companyEn)
}

data class PurchaseBill(
    val id: String,
    val billNumber: Int,
    val supplierId: String,
    val date: String,
    val totalIls: Double,
    val paidIls: Double,
    val itemsSummary: String,
    val isReceived: Boolean = false, // Truthful accounting: text-only bills are pending physical goods inspection
    val expectedProductId: String? = null,
    val expectedQuantity: Int = 0,
    val receivedQuantity: Int = 0
) {
    val remainingPayableIls: Double
        get() = BigDecimal(totalIls.toString())
            .subtract(BigDecimal(paidIls.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)

    val pendingQuantity: Int
        get() = (expectedQuantity - receivedQuantity).coerceAtLeast(0)

    val isPartiallyReceived: Boolean
        get() = !isReceived && receivedQuantity > 0 && pendingQuantity > 0
}

data class SupplierPayment(
    val id: String,
    val supplierId: String,
    val date: String,
    val amountIls: Double,
    val note: String = "سداد نقدي من الصندوق"
)

enum class ExpenseCategory(val labelAr: String, val labelEn: String) {
    RENT("إيجار المحل", "Store Rent"),
    ELECTRICITY_WATER("كهرباء ومياه", "Electricity & Water"),
    TRANSPORT("نقل وشحن بضائع", "Transport & Freight"),
    SALARIES("رواتب ومياومة", "Salaries & Wages"),
    MAINTENANCE("صيانة وضيافة", "Maintenance & Hospitality"),
    OTHER("مصاريف أخرى", "Other Expenses");

    fun label(lang: AppLanguage): String = lang.tr(labelAr, labelEn)
}

data class ExpenseEntry(
    val id: String,
    val date: String,
    val titleAr: String,
    val titleEn: String,
    val category: ExpenseCategory,
    val amountIls: Double,
    val paidFrom: String = "الصندوق الرئيسي (كاش)"
) {
    fun title(lang: AppLanguage): String = lang.tr(titleAr, titleEn)
}

data class StockMovement(
    val id: String,
    val productId: String,
    val date: String,
    val delta: Int,
    val reasonAr: String,
    val reasonEn: String,
    val resultingStock: Int
) {
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
    private val usSymbols = DecimalFormatSymbols(Locale.US)
    private val moneyFormat = DecimalFormat("#,##0.00", usSymbols)
    private val compactMoneyFormat = DecimalFormat("#,##0.##", usSymbols)
    private val intFormat = DecimalFormat("#,##0", usSymbols)

    fun formatIls(amount: Double): String {
        val safe = if (kotlin.math.abs(amount) < 0.005) 0.0 else amount
        return "${moneyFormat.format(safe)} ₪"
    }

    fun formatCompactIls(amount: Double): String {
        val safe = if (kotlin.math.abs(amount) < 0.005) 0.0 else amount
        return "${compactMoneyFormat.format(safe)} ₪"
    }

    fun formatNumber(amount: Double): String {
        val safe = if (kotlin.math.abs(amount) < 0.005) 0.0 else amount
        return moneyFormat.format(safe)
    }

    fun formatInt(value: Int): String = intFormat.format(value)

    fun formatInvoiceCode(number: Int): String = String.format(Locale.US, "#INV-%04d", number)
    fun formatBillCode(number: Int): String = String.format(Locale.US, "#BILL-%04d", number)

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
