package com.example.data.mobile.merchantread

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import java.math.BigDecimal
import java.util.Collections
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Strict internal validation utilities for Mobile V1 Merchant Read projections.
 * Fully compatible with Android API 24 (pure GregorianCalendar and regex; no java.time without desugaring).
 */
internal object MerchantReadValidationUtils {

    private val RFC3339_REGEX = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.[0-9]{1,9})?(?:[Zz]|([+-])(\d{2}):(\d{2}))$"""
    )

    private val DATE_REGEX = Regex(
        """^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$"""
    )

    private val SIGNED_DECIMAL_REGEX = Regex(
        """^-?(?:0|[1-9][0-9]{0,13})(?:\.[0-9]{1,6})?$"""
    )

    private val CURRENCY_CODE_REGEX = Regex("^[A-Z]{3}$")

    fun validateDateString(value: String, fieldName: String) {
        val match = DATE_REGEX.matchEntire(value)
            ?: throw CanonicalSerializationException("$fieldName must be a YYYY-MM-DD date string, got '$value'")
        val groups = match.groupValues
        val year = groups[1].toInt()
        val month = groups[2].toInt()
        val day = groups[3].toInt()
        try {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(year, month - 1, day)
            calendar.timeInMillis
        } catch (_: Exception) {
            throw CanonicalSerializationException("Invalid calendar date in $fieldName: '$value'")
        }
    }

    fun validateDateTime(value: String, fieldName: String) {
        val match = RFC3339_REGEX.matchEntire(value)
            ?: throw CanonicalSerializationException("$fieldName must be an RFC 3339 timestamp, got '$value'")
        val groups = match.groupValues
        if (groups[7].isNotEmpty()) {
            val hourOffset = groups[8].toInt()
            val minOffset = groups[9].toInt()
            if (hourOffset > 23 || minOffset > 59) {
                throw CanonicalSerializationException("Invalid timestamp timezone offset in $fieldName: '$value'")
            }
        }
        try {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(
                groups[1].toInt(),
                groups[2].toInt() - 1,
                groups[3].toInt(),
                groups[4].toInt(),
                groups[5].toInt(),
                groups[6].toInt()
            )
            calendar.timeInMillis
        } catch (_: Exception) {
            throw CanonicalSerializationException("Invalid calendar date in $fieldName: '$value'")
        }
    }

    fun validateCurrencyCode(code: String, fieldName: String = "currency") {
        if (!CURRENCY_CODE_REGEX.matches(code)) {
            throw CanonicalSerializationException("$fieldName must be an ISO 4217 3-letter currency code, got '$code'")
        }
    }

    fun validateMinorUnits(minorUnits: Int, fieldName: String = "currency_minor_units") {
        if (minorUnits !in 0..6) {
            throw CanonicalSerializationException("$fieldName must be between 0 and 6, got $minorUnits")
        }
    }

    fun validateStringBounds(value: String, fieldName: String, minLength: Int = 1, maxLength: Int) {
        if (value.length < minLength) {
            throw CanonicalSerializationException("$fieldName length must be at least $minLength, got ${value.length}")
        }
        if (value.length > maxLength) {
            throw CanonicalSerializationException("$fieldName length exceeds maximum allowed $maxLength UTF-16 code units, got ${value.length}")
        }
    }

    fun checkAllowedKeys(obj: StrictJsonObject, allowedKeys: Set<String>, scopeName: String) {
        val unexpected = obj.properties.keys - allowedKeys
        if (unexpected.isNotEmpty()) {
            throw CanonicalSerializationException("Unexpected fields in $scopeName: $unexpected")
        }
    }

    fun requireString(obj: StrictJsonObject, key: String, minLength: Int = 1, maxLength: Int = 255): String {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON string, got ${element::class.simpleName}")
        validateStringBounds(s, key, minLength, maxLength)
        return s
    }

    fun optionalString(obj: StrictJsonObject, key: String, minLength: Int = 1, maxLength: Int = 255): String? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON string or null, got ${element::class.simpleName}")
        validateStringBounds(s, key, minLength, maxLength)
        return s
    }

    fun requireNullableString(obj: StrictJsonObject, key: String, minLength: Int = 1, maxLength: Int = 255): String? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON string or null, got ${element::class.simpleName}")
        validateStringBounds(s, key, minLength, maxLength)
        return s
    }

    fun requireBoolean(obj: StrictJsonObject, key: String): Boolean {
        val element = obj.require(key)
        return (element as? StrictJsonBoolean)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a boolean, got ${element::class.simpleName}")
    }

    fun requireInt(obj: StrictJsonObject, key: String, min: Int? = null, max: Int? = null): Int {
        val element = obj.require(key)
        val num = (element as? StrictJsonNumber)
            ?: throw CanonicalSerializationException("Property '$key' must be a number, got ${element::class.simpleName}")
        if (!num.isInt) {
            throw CanonicalSerializationException("Property '$key' must be an integer, got float '${num.rawString}'")
        }
        val intVal = num.rawString.toIntOrNull()
            ?: throw CanonicalSerializationException("Property '$key' integer value '${num.rawString}' is out of 32-bit range")
        if (min != null && intVal < min) {
            throw CanonicalSerializationException("Property '$key' must be >= $min, got $intVal")
        }
        if (max != null && intVal > max) {
            throw CanonicalSerializationException("Property '$key' must be <= $max, got $intVal")
        }
        return intVal
    }

    fun requireNullableInt(obj: StrictJsonObject, key: String, min: Int? = null, max: Int? = null): Int? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val num = (element as? StrictJsonNumber)
            ?: throw CanonicalSerializationException("Property '$key' must be a number or null, got ${element::class.simpleName}")
        if (!num.isInt) {
            throw CanonicalSerializationException("Property '$key' must be an integer, got float '${num.rawString}'")
        }
        val intVal = num.rawString.toIntOrNull()
            ?: throw CanonicalSerializationException("Property '$key' integer value '${num.rawString}' is out of 32-bit range")
        if (min != null && intVal < min) {
            throw CanonicalSerializationException("Property '$key' must be >= $min, got $intVal")
        }
        if (max != null && intVal > max) {
            throw CanonicalSerializationException("Property '$key' must be <= $max, got $intVal")
        }
        return intVal
    }

    fun requireUlid(obj: StrictJsonObject, key: String): ServerUlid {
        val s = requireString(obj, key, minLength = 26, maxLength = 26)
        if (!ServerUlid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid 26-char Crockford ULID, got '$s'")
        }
        return ServerUlid.parse(s)
    }

    fun optionalUlid(obj: StrictJsonObject, key: String): ServerUlid? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a ULID string or null, got ${element::class.simpleName}")
        if (!ServerUlid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid 26-char Crockford ULID or null, got '$s'")
        }
        return ServerUlid.parse(s)
    }

    fun requireNullableUlid(obj: StrictJsonObject, key: String): ServerUlid? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a ULID string or null, got ${element::class.simpleName}")
        if (!ServerUlid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid 26-char Crockford ULID or null, got '$s'")
        }
        return ServerUlid.parse(s)
    }

    fun requireObject(obj: StrictJsonObject, key: String): StrictJsonObject {
        val element = obj.require(key)
        return (element as? StrictJsonObject)
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON object, got ${element::class.simpleName}")
    }

    fun optionalObject(obj: StrictJsonObject, key: String): StrictJsonObject? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        return (element as? StrictJsonObject)
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON object or null, got ${element::class.simpleName}")
    }

    fun requireArray(obj: StrictJsonObject, key: String): StrictJsonArray {
        val element = obj.require(key)
        return (element as? StrictJsonArray)
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON array, got ${element::class.simpleName}")
    }

    fun requireBigDecimal(obj: StrictJsonObject, key: String): BigDecimal {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a string decimal, got ${element::class.simpleName}")
        if (!SIGNED_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Decimal property '$key' has invalid format: '$s'")
        }
        return try {
            BigDecimal(s)
        } catch (_: Exception) {
            throw CanonicalSerializationException("Decimal property '$key' cannot be parsed: '$s'")
        }
    }

    fun optionalBigDecimal(obj: StrictJsonObject, key: String): BigDecimal? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a string decimal or null, got ${element::class.simpleName}")
        if (!SIGNED_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Decimal property '$key' has invalid format: '$s'")
        }
        return try {
            BigDecimal(s)
        } catch (_: Exception) {
            throw CanonicalSerializationException("Decimal property '$key' cannot be parsed: '$s'")
        }
    }

    fun requireNullableBigDecimal(obj: StrictJsonObject, key: String): BigDecimal? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a string decimal or null, got ${element::class.simpleName}")
        if (!SIGNED_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Decimal property '$key' has invalid format: '$s'")
        }
        return try {
            BigDecimal(s)
        } catch (_: Exception) {
            throw CanonicalSerializationException("Decimal property '$key' cannot be parsed: '$s'")
        }
    }
}

// ============================================================================
// 1. Inventory & Product History Projections
// GET /api/mobile/v1/companies/{companyId}/products/{productId}/movements
// ============================================================================

data class StockMovementItem(
    val id: ServerUlid,
    val movementType: String,
    val delta: BigDecimal,
    val date: String,
    val reason: String,
    val resultingStock: BigDecimal
) {
    init {
        MerchantReadValidationUtils.validateStringBounds(movementType, "movement.movement_type", minLength = 1, maxLength = 64)
        MerchantReadValidationUtils.validateDateString(date, "movement.date")
        MerchantReadValidationUtils.validateStringBounds(reason, "movement.reason", minLength = 1, maxLength = 255)
    }

    override fun toString(): String =
        "StockMovementItem(id=$id, movementType='$movementType', delta=$delta, date='$date', reason='[REDACTED]', resultingStock=$resultingStock)"

    companion object {
        private val ALLOWED_KEYS = setOf("id", "movement_type", "delta", "date", "reason", "resulting_stock")

        fun fromStrictJson(obj: StrictJsonObject): StockMovementItem {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "StockMovementItem")
            return StockMovementItem(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                movementType = MerchantReadValidationUtils.requireString(obj, "movement_type", minLength = 1, maxLength = 64),
                delta = MerchantReadValidationUtils.requireBigDecimal(obj, "delta"),
                date = MerchantReadValidationUtils.requireString(obj, "date", minLength = 10, maxLength = 10),
                reason = MerchantReadValidationUtils.requireString(obj, "reason", minLength = 1, maxLength = 255),
                resultingStock = MerchantReadValidationUtils.requireBigDecimal(obj, "resulting_stock")
            )
        }
    }
}

class ProductHistoryResponse(
    val productId: ServerUlid,
    movements: List<StockMovementItem>
) {
    val movements: List<StockMovementItem> = Collections.unmodifiableList(movements.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductHistoryResponse) return false
        return productId == other.productId && movements == other.movements
    }

    override fun hashCode(): Int = 31 * productId.hashCode() + movements.hashCode()

    override fun toString(): String =
        "ProductHistoryResponse(productId=$productId, movementCount=${movements.size})"

    companion object {
        private val ALLOWED_KEYS = setOf("product_id", "movements")

        fun fromStrictJson(obj: StrictJsonObject): ProductHistoryResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "ProductHistoryResponse")
            val rawMovements = MerchantReadValidationUtils.requireArray(obj, "movements")
            val list = rawMovements.elements.map {
                StockMovementItem.fromStrictJson(it.asObject())
            }
            return ProductHistoryResponse(
                productId = MerchantReadValidationUtils.requireUlid(obj, "product_id"),
                movements = list
            )
        }
    }
}

// ============================================================================
// 2. Home Dashboard Shell Projection
// GET /api/mobile/v1/companies/{companyId}/home
// ============================================================================

data class HomeBusinessInfo(
    val nameAr: String,
    val nameEn: String,
    val cityAr: String,
    val cityEn: String,
    val phone: String,
    val taxNumber: String,
    val defaultCurrency: String,
    val defaultCurrencyMinorUnits: Int
) {
    init {
        MerchantReadValidationUtils.validateCurrencyCode(defaultCurrency, "default_currency")
        MerchantReadValidationUtils.validateMinorUnits(defaultCurrencyMinorUnits, "default_currency_minor_units")
    }

    override fun toString(): String =
        "HomeBusinessInfo(nameAr='$nameAr', nameEn='$nameEn', cityAr='$cityAr', cityEn='$cityEn', phone='[REDACTED]', taxNumber='$taxNumber', defaultCurrency='$defaultCurrency', defaultCurrencyMinorUnits=$defaultCurrencyMinorUnits)"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "name_ar", "name_en", "city_ar", "city_en", "phone",
            "tax_number", "default_currency", "default_currency_minor_units"
        )

        fun fromStrictJson(obj: StrictJsonObject): HomeBusinessInfo {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "HomeBusinessInfo")
            return HomeBusinessInfo(
                nameAr = MerchantReadValidationUtils.requireString(obj, "name_ar", minLength = 0, maxLength = 255),
                nameEn = MerchantReadValidationUtils.requireString(obj, "name_en", minLength = 0, maxLength = 255),
                cityAr = MerchantReadValidationUtils.requireString(obj, "city_ar", minLength = 0, maxLength = 255),
                cityEn = MerchantReadValidationUtils.requireString(obj, "city_en", minLength = 0, maxLength = 255),
                phone = MerchantReadValidationUtils.requireString(obj, "phone", minLength = 0, maxLength = 64),
                taxNumber = MerchantReadValidationUtils.requireString(obj, "tax_number", minLength = 0, maxLength = 64),
                defaultCurrency = MerchantReadValidationUtils.requireString(obj, "default_currency", minLength = 3, maxLength = 3),
                defaultCurrencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "default_currency_minor_units", min = 0, max = 6)
            )
        }
    }
}

data class HomeRecentInvoice(
    val id: ServerUlid,
    val invoiceNumber: String,
    val customerName: String,
    val date: String,
    val total: BigDecimal,
    val paid: BigDecimal,
    val remaining: BigDecimal,
    val currency: String,
    val paymentStatus: String
) {
    init {
        MerchantReadValidationUtils.validateStringBounds(invoiceNumber, "recent_invoice.invoice_number", minLength = 1, maxLength = 64)
        MerchantReadValidationUtils.validateDateString(date, "recent_invoice.date")
        MerchantReadValidationUtils.validateCurrencyCode(currency, "recent_invoice.currency")
        MerchantReadValidationUtils.validateStringBounds(paymentStatus, "recent_invoice.payment_status", minLength = 1, maxLength = 32)
    }

    val isCreditSale: Boolean get() = paid.compareTo(BigDecimal.ZERO) <= 0
    val isPaidInFull: Boolean get() = remaining.compareTo(BigDecimal.ZERO) <= 0

    override fun toString(): String =
        "HomeRecentInvoice(id=$id, invoiceNumber='$invoiceNumber', customerName='[REDACTED]', date='$date', total=$total, paid=$paid, remaining=$remaining, currency='$currency', paymentStatus='$paymentStatus')"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "invoice_number", "customer_name", "date", "total",
            "paid", "remaining", "currency", "payment_status"
        )

        fun fromStrictJson(obj: StrictJsonObject): HomeRecentInvoice {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "HomeRecentInvoice")
            return HomeRecentInvoice(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                invoiceNumber = MerchantReadValidationUtils.requireString(obj, "invoice_number", minLength = 1, maxLength = 64),
                customerName = MerchantReadValidationUtils.requireString(obj, "customer_name", minLength = 0, maxLength = 255),
                date = MerchantReadValidationUtils.requireString(obj, "date", minLength = 10, maxLength = 10),
                total = MerchantReadValidationUtils.requireBigDecimal(obj, "total"),
                paid = MerchantReadValidationUtils.requireBigDecimal(obj, "paid"),
                remaining = MerchantReadValidationUtils.requireBigDecimal(obj, "remaining"),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                paymentStatus = MerchantReadValidationUtils.requireString(obj, "payment_status", minLength = 1, maxLength = 32)
            )
        }
    }
}

class HomeResponse(
    val companyId: ServerUlid,
    val today: String,
    val salesTodayCount: Int?,
    val salesTodayTotal: BigDecimal?,
    val cashInDrawer: BigDecimal?,
    val lowStockCount: Int?,
    val totalReceivables: BigDecimal?,
    val totalPayables: BigDecimal?,
    recentInvoices: List<HomeRecentInvoice>,
    val businessInfo: HomeBusinessInfo
) {
    val recentInvoices: List<HomeRecentInvoice> = Collections.unmodifiableList(recentInvoices.toList())

    init {
        MerchantReadValidationUtils.validateDateString(today, "home.today")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HomeResponse) return false
        return companyId == other.companyId &&
                today == other.today &&
                salesTodayCount == other.salesTodayCount &&
                salesTodayTotal == other.salesTodayTotal &&
                cashInDrawer == other.cashInDrawer &&
                lowStockCount == other.lowStockCount &&
                totalReceivables == other.totalReceivables &&
                totalPayables == other.totalPayables &&
                recentInvoices == other.recentInvoices &&
                businessInfo == other.businessInfo
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + today.hashCode()
        result = 31 * result + (salesTodayCount ?: 0)
        result = 31 * result + (salesTodayTotal?.hashCode() ?: 0)
        result = 31 * result + (cashInDrawer?.hashCode() ?: 0)
        result = 31 * result + (lowStockCount ?: 0)
        result = 31 * result + (totalReceivables?.hashCode() ?: 0)
        result = 31 * result + (totalPayables?.hashCode() ?: 0)
        result = 31 * result + recentInvoices.hashCode()
        result = 31 * result + businessInfo.hashCode()
        return result
    }

    override fun toString(): String =
        "HomeResponse(companyId=$companyId, today='$today', salesTodayCount=$salesTodayCount, salesTodayTotal=$salesTodayTotal, cashInDrawer=$cashInDrawer, lowStockCount=$lowStockCount, totalReceivables=$totalReceivables, totalPayables=$totalPayables, recentInvoiceCount=${recentInvoices.size}, businessInfo=$businessInfo)"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "company_id", "today", "sales_today_count", "sales_today_total",
            "cash_in_drawer", "low_stock_count", "total_receivables",
            "total_payables", "recent_invoices", "business_info"
        )

        fun fromStrictJson(obj: StrictJsonObject): HomeResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "HomeResponse")
            val rawRecent = MerchantReadValidationUtils.requireArray(obj, "recent_invoices")
            val invoices = rawRecent.elements.map { HomeRecentInvoice.fromStrictJson(it.asObject()) }
            return HomeResponse(
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                today = MerchantReadValidationUtils.requireString(obj, "today", minLength = 10, maxLength = 10),
                salesTodayCount = MerchantReadValidationUtils.requireNullableInt(obj, "sales_today_count", min = 0),
                salesTodayTotal = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "sales_today_total"),
                cashInDrawer = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "cash_in_drawer"),
                lowStockCount = MerchantReadValidationUtils.requireNullableInt(obj, "low_stock_count", min = 0),
                totalReceivables = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "total_receivables"),
                totalPayables = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "total_payables"),
                recentInvoices = invoices,
                businessInfo = HomeBusinessInfo.fromStrictJson(MerchantReadValidationUtils.requireObject(obj, "business_info"))
            )
        }
    }
}

// ============================================================================
// 3. Sales Invoices List & Detail Projections
// GET /api/mobile/v1/companies/{companyId}/sales/invoices
// GET /api/mobile/v1/companies/{companyId}/sales/invoices/{publicId}
// ============================================================================

data class SalesInvoiceListItem(
    val id: ServerUlid,
    val invoiceNumber: String,
    val status: String,
    val customerId: ServerUlid,
    val customerName: String?,
    val date: String,
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val tax: BigDecimal,
    val total: BigDecimal,
    val paid: BigDecimal,
    val remaining: BigDecimal,
    val currency: String,
    val currencyMinorUnits: Int,
    val paymentStatus: String,
    val createdAt: String?
) {
    init {
        MerchantReadValidationUtils.validateStringBounds(invoiceNumber, "invoice.invoice_number", minLength = 1, maxLength = 64)
        MerchantReadValidationUtils.validateStringBounds(status, "invoice.status", minLength = 1, maxLength = 32)
        if (customerName != null) {
            MerchantReadValidationUtils.validateStringBounds(customerName, "invoice.customer_name", minLength = 1, maxLength = 255)
        }
        MerchantReadValidationUtils.validateDateString(date, "invoice.date")
        MerchantReadValidationUtils.validateCurrencyCode(currency, "invoice.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "invoice.currency_minor_units")
        MerchantReadValidationUtils.validateStringBounds(paymentStatus, "invoice.payment_status", minLength = 1, maxLength = 32)
        if (createdAt != null) {
            MerchantReadValidationUtils.validateDateTime(createdAt, "invoice.created_at")
        }
    }

    val isCreditSale: Boolean get() = paymentStatus == "ON_CREDIT" || paid.compareTo(BigDecimal.ZERO) <= 0
    val isPaidInFull: Boolean get() = remaining.compareTo(BigDecimal.ZERO) <= 0
    val isFullCashSale: Boolean get() = paymentStatus == "PAID_CASH"

    override fun toString(): String =
        "SalesInvoiceListItem(id=$id, invoiceNumber='$invoiceNumber', status='$status', customerId=$customerId, customerName='[REDACTED]', date='$date', total=$total, paid=$paid, remaining=$remaining, currency='$currency', currencyMinorUnits=$currencyMinorUnits, paymentStatus='$paymentStatus')"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "invoice_number", "status", "customer_id", "customer_name",
            "date", "subtotal", "discount", "tax", "total", "paid",
            "remaining", "currency", "currency_minor_units", "payment_status", "created_at"
        )

        fun fromStrictJson(obj: StrictJsonObject): SalesInvoiceListItem {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SalesInvoiceListItem")
            return SalesInvoiceListItem(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                invoiceNumber = MerchantReadValidationUtils.requireString(obj, "invoice_number", minLength = 1, maxLength = 64),
                status = MerchantReadValidationUtils.requireString(obj, "status", minLength = 1, maxLength = 32),
                customerId = MerchantReadValidationUtils.requireUlid(obj, "customer_id"),
                customerName = MerchantReadValidationUtils.optionalString(obj, "customer_name", minLength = 1, maxLength = 255),
                date = MerchantReadValidationUtils.requireString(obj, "date", minLength = 10, maxLength = 10),
                subtotal = MerchantReadValidationUtils.requireBigDecimal(obj, "subtotal"),
                discount = MerchantReadValidationUtils.requireBigDecimal(obj, "discount"),
                tax = MerchantReadValidationUtils.requireBigDecimal(obj, "tax"),
                total = MerchantReadValidationUtils.requireBigDecimal(obj, "total"),
                paid = MerchantReadValidationUtils.requireBigDecimal(obj, "paid"),
                remaining = MerchantReadValidationUtils.requireBigDecimal(obj, "remaining"),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6),
                paymentStatus = MerchantReadValidationUtils.requireString(obj, "payment_status", minLength = 1, maxLength = 32),
                createdAt = MerchantReadValidationUtils.requireNullableString(obj, "created_at", minLength = 1, maxLength = 64)
            )
        }
    }
}

class SalesInvoiceListResponse(
    val companyId: ServerUlid,
    val count: Int,
    val hasMore: Boolean,
    val nextCursor: String? = null,
    invoices: List<SalesInvoiceListItem>
) {
    val invoices: List<SalesInvoiceListItem> = Collections.unmodifiableList(invoices.toList())

    init {
        require(count >= 0) { "count must be non-negative, got $count" }
        require(invoices.size == count) { "invoices list size (${invoices.size}) does not match count ($count)" }
        if (nextCursor != null) {
            require(nextCursor.length <= 255) { "next_cursor exceeds maximum 255 characters, got ${nextCursor.length}" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SalesInvoiceListResponse) return false
        return companyId == other.companyId && count == other.count && hasMore == other.hasMore && nextCursor == other.nextCursor && invoices == other.invoices
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + count
        result = 31 * result + hasMore.hashCode()
        result = 31 * result + (nextCursor?.hashCode() ?: 0)
        result = 31 * result + invoices.hashCode()
        return result
    }

    override fun toString(): String =
        "SalesInvoiceListResponse(companyId=$companyId, count=$count, hasMore=$hasMore, nextCursor=${if (nextCursor != null) "[OPAQUE]" else "null"})"

    companion object {
        private val ALLOWED_KEYS = setOf("company_id", "count", "has_more", "next_cursor", "invoices")

        fun fromStrictJson(obj: StrictJsonObject): SalesInvoiceListResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SalesInvoiceListResponse")
            val rawInvoices = MerchantReadValidationUtils.requireArray(obj, "invoices")
            val items = rawInvoices.elements.map { SalesInvoiceListItem.fromStrictJson(it.asObject()) }
            return SalesInvoiceListResponse(
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                count = MerchantReadValidationUtils.requireInt(obj, "count", min = 0),
                hasMore = MerchantReadValidationUtils.requireBoolean(obj, "has_more"),
                nextCursor = MerchantReadValidationUtils.requireNullableString(obj, "next_cursor", minLength = 1, maxLength = 255),
                invoices = items
            )
        }
    }
}

data class SalesInvoiceDetailLine(
    val id: ServerUlid,
    val productId: ServerUlid?,
    val productName: String,
    val unit: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineTotal: BigDecimal,
    val taxRate: BigDecimal,
    val discount: BigDecimal
) {
    init {
        MerchantReadValidationUtils.validateStringBounds(productName, "line.product_name", minLength = 1, maxLength = 255)
        MerchantReadValidationUtils.validateStringBounds(unit, "line.unit", minLength = 0, maxLength = 64)
    }

    override fun toString(): String =
        "SalesInvoiceDetailLine(id=$id, productId=$productId, productName='$productName', unit='$unit', quantity=$quantity, unitPrice=$unitPrice, lineTotal=$lineTotal, taxRate=$taxRate, discount=$discount)"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "product_id", "product_name", "unit", "quantity",
            "unit_price", "line_total", "tax_rate", "discount"
        )

        fun fromStrictJson(obj: StrictJsonObject): SalesInvoiceDetailLine {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SalesInvoiceDetailLine")
            return SalesInvoiceDetailLine(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                productId = MerchantReadValidationUtils.optionalUlid(obj, "product_id"),
                productName = MerchantReadValidationUtils.requireString(obj, "product_name", minLength = 1, maxLength = 255),
                unit = MerchantReadValidationUtils.requireString(obj, "unit", minLength = 0, maxLength = 64),
                quantity = MerchantReadValidationUtils.requireBigDecimal(obj, "quantity"),
                unitPrice = MerchantReadValidationUtils.requireBigDecimal(obj, "unit_price"),
                lineTotal = MerchantReadValidationUtils.requireBigDecimal(obj, "line_total"),
                taxRate = MerchantReadValidationUtils.requireBigDecimal(obj, "tax_rate"),
                discount = MerchantReadValidationUtils.requireBigDecimal(obj, "discount")
            )
        }
    }
}

class ThermalReceiptData(
    val storeNameAr: String?,
    val storeNameEn: String?,
    val cityAr: String?,
    val cityEn: String?,
    val phone: String?,
    val taxNumber: String?,
    val invoiceNumber: String,
    val dateTime: String,
    val cashier: String?,
    val customerName: String?,
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val tax: BigDecimal,
    val grandTotal: BigDecimal,
    val paidAmount: BigDecimal,
    val remainingBalance: BigDecimal,
    val currency: String,
    lines: List<SalesInvoiceDetailLine>
) {
    val lines: List<SalesInvoiceDetailLine> = Collections.unmodifiableList(lines.toList())

    init {
        MerchantReadValidationUtils.validateStringBounds(invoiceNumber, "thermal_receipt.invoice_number", minLength = 1, maxLength = 64)
        MerchantReadValidationUtils.validateCurrencyCode(currency, "thermal_receipt.currency")
        if (storeNameAr != null) MerchantReadValidationUtils.validateStringBounds(storeNameAr, "thermal_receipt.store_name_ar", minLength = 0, maxLength = 255)
        if (storeNameEn != null) MerchantReadValidationUtils.validateStringBounds(storeNameEn, "thermal_receipt.store_name_en", minLength = 0, maxLength = 255)
        if (cityAr != null) MerchantReadValidationUtils.validateStringBounds(cityAr, "thermal_receipt.city_ar", minLength = 0, maxLength = 255)
        if (cityEn != null) MerchantReadValidationUtils.validateStringBounds(cityEn, "thermal_receipt.city_en", minLength = 0, maxLength = 255)
        if (phone != null) MerchantReadValidationUtils.validateStringBounds(phone, "thermal_receipt.phone", minLength = 0, maxLength = 64)
        if (taxNumber != null) MerchantReadValidationUtils.validateStringBounds(taxNumber, "thermal_receipt.tax_number", minLength = 0, maxLength = 64)
        if (cashier != null) MerchantReadValidationUtils.validateStringBounds(cashier, "thermal_receipt.cashier", minLength = 0, maxLength = 255)
        if (customerName != null) MerchantReadValidationUtils.validateStringBounds(customerName, "thermal_receipt.customer_name", minLength = 0, maxLength = 255)
    }

    val isCreditReceipt: Boolean get() = paidAmount.compareTo(BigDecimal.ZERO) <= 0
    val isPartialReceipt: Boolean get() = paidAmount.compareTo(BigDecimal.ZERO) > 0 && remainingBalance.compareTo(BigDecimal.ZERO) > 0
    val isPaidInFull: Boolean get() = remainingBalance.compareTo(BigDecimal.ZERO) <= 0
    val isFullyPaidReceipt: Boolean get() = remainingBalance.compareTo(BigDecimal.ZERO) <= 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThermalReceiptData) return false
        return storeNameAr == other.storeNameAr &&
                storeNameEn == other.storeNameEn &&
                cityAr == other.cityAr &&
                cityEn == other.cityEn &&
                phone == other.phone &&
                taxNumber == other.taxNumber &&
                invoiceNumber == other.invoiceNumber &&
                dateTime == other.dateTime &&
                cashier == other.cashier &&
                customerName == other.customerName &&
                subtotal == other.subtotal &&
                discount == other.discount &&
                tax == other.tax &&
                grandTotal == other.grandTotal &&
                paidAmount == other.paidAmount &&
                remainingBalance == other.remainingBalance &&
                currency == other.currency &&
                lines == other.lines
    }

    override fun hashCode(): Int {
        var result = invoiceNumber.hashCode()
        result = 31 * result + grandTotal.hashCode()
        result = 31 * result + paidAmount.hashCode()
        result = 31 * result + remainingBalance.hashCode()
        result = 31 * result + lines.hashCode()
        return result
    }

    override fun toString(): String =
        "ThermalReceiptData(storeNameAr='$storeNameAr', storeNameEn='$storeNameEn', invoiceNumber='$invoiceNumber', cashier='[REDACTED]', customerName='[REDACTED]', grandTotal=$grandTotal, paidAmount=$paidAmount, remainingBalance=$remainingBalance, currency='$currency', lineCount=${lines.size})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "store_name_ar", "store_name_en", "city_ar", "city_en", "phone",
            "tax_number", "invoice_number", "date_time", "cashier",
            "customer_name", "subtotal", "discount", "tax", "grand_total",
            "paid_amount", "remaining_balance", "currency", "lines"
        )

        fun fromStrictJson(obj: StrictJsonObject): ThermalReceiptData {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "ThermalReceiptData")
            val rawLines = MerchantReadValidationUtils.requireArray(obj, "lines")
            val parsedLines = rawLines.elements.map { SalesInvoiceDetailLine.fromStrictJson(it.asObject()) }
            return ThermalReceiptData(
                storeNameAr = MerchantReadValidationUtils.optionalString(obj, "store_name_ar", minLength = 0, maxLength = 255),
                storeNameEn = MerchantReadValidationUtils.optionalString(obj, "store_name_en", minLength = 0, maxLength = 255),
                cityAr = MerchantReadValidationUtils.optionalString(obj, "city_ar", minLength = 0, maxLength = 255),
                cityEn = MerchantReadValidationUtils.optionalString(obj, "city_en", minLength = 0, maxLength = 255),
                phone = MerchantReadValidationUtils.optionalString(obj, "phone", minLength = 0, maxLength = 64),
                taxNumber = MerchantReadValidationUtils.optionalString(obj, "tax_number", minLength = 0, maxLength = 64),
                invoiceNumber = MerchantReadValidationUtils.requireString(obj, "invoice_number", minLength = 1, maxLength = 64),
                dateTime = MerchantReadValidationUtils.requireString(obj, "date_time", minLength = 1, maxLength = 64),
                cashier = MerchantReadValidationUtils.optionalString(obj, "cashier", minLength = 0, maxLength = 255),
                customerName = MerchantReadValidationUtils.optionalString(obj, "customer_name", minLength = 0, maxLength = 255),
                subtotal = MerchantReadValidationUtils.requireBigDecimal(obj, "subtotal"),
                discount = MerchantReadValidationUtils.requireBigDecimal(obj, "discount"),
                tax = MerchantReadValidationUtils.requireBigDecimal(obj, "tax"),
                grandTotal = MerchantReadValidationUtils.requireBigDecimal(obj, "grand_total"),
                paidAmount = MerchantReadValidationUtils.requireBigDecimal(obj, "paid_amount"),
                remainingBalance = MerchantReadValidationUtils.requireBigDecimal(obj, "remaining_balance"),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                lines = parsedLines
            )
        }
    }
}

class SalesInvoiceDetailResponse(
    val id: ServerUlid,
    val companyId: ServerUlid,
    val invoiceNumber: String,
    val status: String,
    val paymentStatus: String,
    val customerId: ServerUlid,
    val customerName: String?,
    val date: String,
    val currency: String,
    val currencyMinorUnits: Int,
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val tax: BigDecimal,
    val total: BigDecimal,
    val paid: BigDecimal,
    val remaining: BigDecimal,
    lines: List<SalesInvoiceDetailLine>,
    val thermalReceipt: ThermalReceiptData?
) {
    val lines: List<SalesInvoiceDetailLine> = Collections.unmodifiableList(lines.toList())

    init {
        MerchantReadValidationUtils.validateStringBounds(invoiceNumber, "invoice.invoice_number", minLength = 1, maxLength = 64)
        if (customerName != null) {
            MerchantReadValidationUtils.validateStringBounds(customerName, "invoice.customer_name", minLength = 1, maxLength = 255)
        }
        MerchantReadValidationUtils.validateDateString(date, "invoice.date")
        MerchantReadValidationUtils.validateCurrencyCode(currency, "invoice.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "invoice.currency_minor_units")
    }

    val isCreditSale: Boolean get() = paymentStatus == "ON_CREDIT" || paid.compareTo(BigDecimal.ZERO) <= 0
    val isPaidInFull: Boolean get() = remaining.compareTo(BigDecimal.ZERO) <= 0
    val isFullCashSale: Boolean get() = paymentStatus == "PAID_CASH"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SalesInvoiceDetailResponse) return false
        return id == other.id &&
                companyId == other.companyId &&
                invoiceNumber == other.invoiceNumber &&
                status == other.status &&
                paymentStatus == other.paymentStatus &&
                customerId == other.customerId &&
                customerName == other.customerName &&
                date == other.date &&
                currency == other.currency &&
                currencyMinorUnits == other.currencyMinorUnits &&
                subtotal == other.subtotal &&
                discount == other.discount &&
                tax == other.tax &&
                total == other.total &&
                paid == other.paid &&
                remaining == other.remaining &&
                lines == other.lines &&
                thermalReceipt == other.thermalReceipt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + companyId.hashCode()
        result = 31 * result + invoiceNumber.hashCode()
        result = 31 * result + total.hashCode()
        result = 31 * result + lines.hashCode()
        return result
    }

    override fun toString(): String =
        "SalesInvoiceDetailResponse(id=$id, companyId=$companyId, invoiceNumber='$invoiceNumber', status='$status', paymentStatus='$paymentStatus', customerId=$customerId, customerName='[REDACTED]', date='$date', total=$total, paid=$paid, remaining=$remaining, currency='$currency', currencyMinorUnits=$currencyMinorUnits, lineCount=${lines.size}, hasThermalReceipt=${thermalReceipt != null})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "company_id", "invoice_number", "status", "payment_status",
            "customer_id", "customer_name", "date", "currency", "currency_minor_units", "subtotal",
            "discount", "tax", "total", "paid", "remaining", "lines", "thermal_receipt"
        )

        fun fromStrictJson(obj: StrictJsonObject): SalesInvoiceDetailResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SalesInvoiceDetailResponse")
            val rawLines = MerchantReadValidationUtils.requireArray(obj, "lines")
            val parsedLines = rawLines.elements.map { SalesInvoiceDetailLine.fromStrictJson(it.asObject()) }
            val rawThermal = MerchantReadValidationUtils.optionalObject(obj, "thermal_receipt")
            val thermal = rawThermal?.let { ThermalReceiptData.fromStrictJson(it) }
            return SalesInvoiceDetailResponse(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                invoiceNumber = MerchantReadValidationUtils.requireString(obj, "invoice_number", minLength = 1, maxLength = 64),
                status = MerchantReadValidationUtils.requireString(obj, "status", minLength = 1, maxLength = 32),
                paymentStatus = MerchantReadValidationUtils.requireString(obj, "payment_status", minLength = 1, maxLength = 32),
                customerId = MerchantReadValidationUtils.requireUlid(obj, "customer_id"),
                customerName = MerchantReadValidationUtils.optionalString(obj, "customer_name", minLength = 1, maxLength = 255),
                date = MerchantReadValidationUtils.requireString(obj, "date", minLength = 10, maxLength = 10),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6),
                subtotal = MerchantReadValidationUtils.requireBigDecimal(obj, "subtotal"),
                discount = MerchantReadValidationUtils.requireBigDecimal(obj, "discount"),
                tax = MerchantReadValidationUtils.requireBigDecimal(obj, "tax"),
                total = MerchantReadValidationUtils.requireBigDecimal(obj, "total"),
                paid = MerchantReadValidationUtils.requireBigDecimal(obj, "paid"),
                remaining = MerchantReadValidationUtils.requireBigDecimal(obj, "remaining"),
                lines = parsedLines,
                thermalReceipt = thermal
            )
        }
    }
}

// ============================================================================
// 4. Customer Statement Projection
// GET /api/mobile/v1/companies/{companyId}/customers/{customerId}/statement
// ============================================================================

data class CustomerStatementEntry(
    val date: String,
    val type: String,
    val number: String,
    val reference: String?,
    val description: String,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val balance: BigDecimal
) {
    init {
        MerchantReadValidationUtils.validateDateString(date, "statement_entry.date")
        MerchantReadValidationUtils.validateStringBounds(type, "statement_entry.type", minLength = 1, maxLength = 32)
        MerchantReadValidationUtils.validateStringBounds(number, "statement_entry.number", minLength = 1, maxLength = 64)
        if (reference != null) {
            MerchantReadValidationUtils.validateStringBounds(reference, "statement_entry.reference", minLength = 1, maxLength = 64)
        }
    }

    override fun toString(): String =
        "CustomerStatementEntry(date='$date', type='$type', number='$number', reference=${if (reference != null) "'$reference'" else "null"}, description='[REDACTED]', debit=$debit, credit=$credit, balance=$balance)"

    companion object {
        private val ALLOWED_KEYS = setOf("date", "type", "number", "reference", "description", "debit", "credit", "balance")

        fun fromStrictJson(obj: StrictJsonObject): CustomerStatementEntry {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "CustomerStatementEntry")
            return CustomerStatementEntry(
                date = MerchantReadValidationUtils.requireString(obj, "date", minLength = 10, maxLength = 10),
                type = MerchantReadValidationUtils.requireString(obj, "type", minLength = 1, maxLength = 32),
                number = MerchantReadValidationUtils.requireString(obj, "number", minLength = 1, maxLength = 64),
                reference = MerchantReadValidationUtils.requireNullableString(obj, "reference", minLength = 1, maxLength = 64),
                description = MerchantReadValidationUtils.requireString(obj, "description", minLength = 0, maxLength = 255),
                debit = MerchantReadValidationUtils.requireBigDecimal(obj, "debit"),
                credit = MerchantReadValidationUtils.requireBigDecimal(obj, "credit"),
                balance = MerchantReadValidationUtils.requireBigDecimal(obj, "balance")
            )
        }
    }
}

data class CustomerStatementAging(
    val current: BigDecimal,
    val days1To30: BigDecimal,
    val days31To60: BigDecimal,
    val days61To90: BigDecimal,
    val days90Plus: BigDecimal,
    val total: BigDecimal
) {
    companion object {
        private val ALLOWED_KEYS = setOf("current", "days_1_30", "days_31_60", "days_61_90", "days_90_plus", "total")

        fun fromStrictJson(obj: StrictJsonObject): CustomerStatementAging {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "CustomerStatementAging")
            return CustomerStatementAging(
                current = MerchantReadValidationUtils.requireBigDecimal(obj, "current"),
                days1To30 = MerchantReadValidationUtils.requireBigDecimal(obj, "days_1_30"),
                days31To60 = MerchantReadValidationUtils.requireBigDecimal(obj, "days_31_60"),
                days61To90 = MerchantReadValidationUtils.requireBigDecimal(obj, "days_61_90"),
                days90Plus = MerchantReadValidationUtils.requireBigDecimal(obj, "days_90_plus"),
                total = MerchantReadValidationUtils.requireBigDecimal(obj, "total")
            )
        }
    }
}

class CustomerStatementCurrencySummary(
    val currency: String,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val totalDebits: BigDecimal,
    val totalCredits: BigDecimal,
    entries: List<CustomerStatementEntry>,
    val aging: CustomerStatementAging
) {
    val entries: List<CustomerStatementEntry> = Collections.unmodifiableList(entries.toList())

    init {
        MerchantReadValidationUtils.validateCurrencyCode(currency, "currency_summary.currency")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomerStatementCurrencySummary) return false
        return currency == other.currency &&
                openingBalance == other.openingBalance &&
                closingBalance == other.closingBalance &&
                totalDebits == other.totalDebits &&
                totalCredits == other.totalCredits &&
                entries == other.entries &&
                aging == other.aging
    }

    override fun hashCode(): Int = 31 * currency.hashCode() + closingBalance.hashCode()

    override fun toString(): String =
        "CustomerStatementCurrencySummary(currency='$currency', openingBalance=$openingBalance, closingBalance=$closingBalance, totalDebits=$totalDebits, totalCredits=$totalCredits, entryCount=${entries.size}, aging=$aging)"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "currency", "opening_balance", "closing_balance",
            "total_debits", "total_credits", "entries", "aging"
        )

        fun fromStrictJson(obj: StrictJsonObject): CustomerStatementCurrencySummary {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "CustomerStatementCurrencySummary")
            val rawEntries = MerchantReadValidationUtils.requireArray(obj, "entries")
            val parsedEntries = rawEntries.elements.map { CustomerStatementEntry.fromStrictJson(it.asObject()) }
            return CustomerStatementCurrencySummary(
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                openingBalance = MerchantReadValidationUtils.requireBigDecimal(obj, "opening_balance"),
                closingBalance = MerchantReadValidationUtils.requireBigDecimal(obj, "closing_balance"),
                totalDebits = MerchantReadValidationUtils.requireBigDecimal(obj, "total_debits"),
                totalCredits = MerchantReadValidationUtils.requireBigDecimal(obj, "total_credits"),
                entries = parsedEntries,
                aging = CustomerStatementAging.fromStrictJson(MerchantReadValidationUtils.requireObject(obj, "aging"))
            )
        }
    }
}

class CustomerStatementResponse(
    val customerId: ServerUlid,
    val customerName: String,
    val fromDate: String?,
    val toDate: String?,
    currencies: Map<String, CustomerStatementCurrencySummary>
) {
    val currencies: Map<String, CustomerStatementCurrencySummary> = Collections.unmodifiableMap(LinkedHashMap(currencies))

    init {
        if (fromDate != null) MerchantReadValidationUtils.validateDateString(fromDate, "statement.from_date")
        if (toDate != null) MerchantReadValidationUtils.validateDateString(toDate, "statement.to_date")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomerStatementResponse) return false
        return customerId == other.customerId &&
                customerName == other.customerName &&
                fromDate == other.fromDate &&
                toDate == other.toDate &&
                currencies == other.currencies
    }

    override fun hashCode(): Int = 31 * customerId.hashCode() + currencies.hashCode()

    override fun toString(): String =
        "CustomerStatementResponse(customerId=$customerId, customerName='[REDACTED]', fromDate=$fromDate, toDate=$toDate, currencyCount=${currencies.size})"

    companion object {
        private val ALLOWED_KEYS = setOf("customer_id", "customer_name", "from_date", "to_date", "currencies")

        fun fromStrictJson(obj: StrictJsonObject): CustomerStatementResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "CustomerStatementResponse")
            val rawCurrencies = MerchantReadValidationUtils.requireObject(obj, "currencies")
            val summaryMap = mutableMapOf<String, CustomerStatementCurrencySummary>()
            for ((currKey, elem) in rawCurrencies.properties) {
                summaryMap[currKey] = CustomerStatementCurrencySummary.fromStrictJson(elem.asObject())
            }
            return CustomerStatementResponse(
                customerId = MerchantReadValidationUtils.requireUlid(obj, "customer_id"),
                customerName = MerchantReadValidationUtils.requireString(obj, "customer_name", minLength = 1, maxLength = 255),
                fromDate = MerchantReadValidationUtils.requireNullableString(obj, "from_date", minLength = 10, maxLength = 10),
                toDate = MerchantReadValidationUtils.requireNullableString(obj, "to_date", minLength = 10, maxLength = 10),
                currencies = summaryMap
            )
        }
    }
}

// ============================================================================
// 5. Suppliers Projections
// GET /api/mobile/v1/companies/{companyId}/suppliers
// GET /api/mobile/v1/companies/{companyId}/suppliers/{supplierId}
// ============================================================================

data class SupplierListItem(
    val id: ServerUlid,
    val code: String?,
    val name: String,
    val nameAr: String?,
    val nameEn: String?,
    val phone: String?,
    val currency: String,
    val currencyMinorUnits: Int,
    val balanceOwed: BigDecimal?,
    val advances: BigDecimal?
) {
    init {
        MerchantReadValidationUtils.validateCurrencyCode(currency, "supplier.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "supplier.currency_minor_units")
    }

    override fun toString(): String =
        "SupplierListItem(id=$id, code=${if (code != null) "'$code'" else "null"}, name='[REDACTED]', phone='[REDACTED]', currency='$currency', currencyMinorUnits=$currencyMinorUnits, balanceOwed=$balanceOwed, advances=$advances)"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "code", "name", "name_ar", "name_en", "phone",
            "currency", "currency_minor_units", "balance_owed", "owed", "advances"
        )

        fun fromStrictJson(obj: StrictJsonObject): SupplierListItem {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SupplierListItem")
            return SupplierListItem(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                code = MerchantReadValidationUtils.requireNullableString(obj, "code", minLength = 1, maxLength = 64),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255),
                nameAr = MerchantReadValidationUtils.requireNullableString(obj, "name_ar", minLength = 1, maxLength = 255),
                nameEn = MerchantReadValidationUtils.requireNullableString(obj, "name_en", minLength = 1, maxLength = 255),
                phone = MerchantReadValidationUtils.requireNullableString(obj, "phone", minLength = 1, maxLength = 64),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6),
                balanceOwed = if (obj.properties.containsKey("balance_owed")) {
                    MerchantReadValidationUtils.requireNullableBigDecimal(obj, "balance_owed")
                } else if (obj.properties.containsKey("owed")) {
                    MerchantReadValidationUtils.requireNullableBigDecimal(obj, "owed")
                } else {
                    throw CanonicalSerializationException("Missing required property 'balance_owed' (or 'owed')")
                },
                advances = if (obj.properties.containsKey("advances")) {
                    MerchantReadValidationUtils.requireNullableBigDecimal(obj, "advances")
                } else null
            )
        }
    }
}

class SupplierListResponse(
    val companyId: ServerUlid,
    val count: Int,
    val hasMore: Boolean,
    suppliers: List<SupplierListItem>
) {
    val suppliers: List<SupplierListItem> = Collections.unmodifiableList(suppliers.toList())

    init {
        require(count >= 0) { "count must be non-negative, got $count" }
        require(suppliers.size == count) { "suppliers list size (${suppliers.size}) does not match count ($count)" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SupplierListResponse) return false
        return companyId == other.companyId && count == other.count && hasMore == other.hasMore && suppliers == other.suppliers
    }

    override fun hashCode(): Int = 31 * companyId.hashCode() + suppliers.hashCode()

    override fun toString(): String =
        "SupplierListResponse(companyId=$companyId, count=$count, hasMore=$hasMore)"

    companion object {
        private val ALLOWED_KEYS = setOf("company_id", "count", "has_more", "suppliers")

        fun fromStrictJson(obj: StrictJsonObject): SupplierListResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SupplierListResponse")
            val rawList = MerchantReadValidationUtils.requireArray(obj, "suppliers")
            val items = rawList.elements.map { SupplierListItem.fromStrictJson(it.asObject()) }
            return SupplierListResponse(
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                count = MerchantReadValidationUtils.requireInt(obj, "count", min = 0),
                hasMore = MerchantReadValidationUtils.requireBoolean(obj, "has_more"),
                suppliers = items
            )
        }
    }
}

data class SupplierDetailResponse(
    val id: ServerUlid,
    val code: String?,
    val name: String,
    val nameAr: String?,
    val nameEn: String?,
    val phone: String?,
    val whatsapp: String?,
    val email: String?,
    val taxNumber: String?,
    val address: String?,
    val currency: String,
    val currencyMinorUnits: Int,
    val balanceOwed: BigDecimal?,
    val advances: BigDecimal? = null,
    val status: String
) {
    init {
        MerchantReadValidationUtils.validateCurrencyCode(currency, "supplier_detail.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "supplier_detail.currency_minor_units")
        MerchantReadValidationUtils.validateStringBounds(status, "supplier_detail.status", minLength = 1, maxLength = 32)
    }

    override fun toString(): String =
        "SupplierDetailResponse(id=$id, code=${if (code != null) "'$code'" else "null"}, name='[REDACTED]', phone='[REDACTED]', whatsapp='[REDACTED]', email='[REDACTED]', taxNumber='$taxNumber', address='[REDACTED]', currency='$currency', currencyMinorUnits=$currencyMinorUnits, balanceOwed=$balanceOwed, advances=$advances, status='$status')"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "code", "name", "name_ar", "name_en", "phone",
            "whatsapp", "email", "tax_number", "address",
            "currency", "currency_minor_units", "balance_owed", "owed", "advances", "status"
        )

        fun fromStrictJson(obj: StrictJsonObject): SupplierDetailResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "SupplierDetailResponse")
            return SupplierDetailResponse(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                code = MerchantReadValidationUtils.requireNullableString(obj, "code", minLength = 1, maxLength = 64),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255),
                nameAr = MerchantReadValidationUtils.requireNullableString(obj, "name_ar", minLength = 1, maxLength = 255),
                nameEn = MerchantReadValidationUtils.requireNullableString(obj, "name_en", minLength = 1, maxLength = 255),
                phone = MerchantReadValidationUtils.requireNullableString(obj, "phone", minLength = 1, maxLength = 64),
                whatsapp = MerchantReadValidationUtils.requireNullableString(obj, "whatsapp", minLength = 1, maxLength = 64),
                email = MerchantReadValidationUtils.requireNullableString(obj, "email", minLength = 1, maxLength = 255),
                taxNumber = MerchantReadValidationUtils.requireNullableString(obj, "tax_number", minLength = 1, maxLength = 64),
                address = MerchantReadValidationUtils.requireNullableString(obj, "address", minLength = 1, maxLength = 255),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6),
                balanceOwed = if (obj.properties.containsKey("balance_owed")) {
                    MerchantReadValidationUtils.requireNullableBigDecimal(obj, "balance_owed")
                } else if (obj.properties.containsKey("owed")) {
                    MerchantReadValidationUtils.requireNullableBigDecimal(obj, "owed")
                } else {
                    throw CanonicalSerializationException("Missing required property 'balance_owed' (or 'owed')")
                },
                advances = if (obj.properties.containsKey("advances")) {
                    MerchantReadValidationUtils.requireNullableBigDecimal(obj, "advances")
                } else null,
                status = MerchantReadValidationUtils.requireString(obj, "status", minLength = 1, maxLength = 32)
            )
        }
    }
}

// ============================================================================
// 6. Reports Projections (Shift Summary & Treasury)
// GET /api/mobile/v1/companies/{companyId}/reports/shift-summary
// GET /api/mobile/v1/companies/{companyId}/reports/treasury
// ============================================================================

data class ShiftSummaryReportResponse(
    val companyId: ServerUlid,
    val shiftDate: String,
    val currency: String,
    val currencyMinorUnits: Int,
    val openingFloat: BigDecimal?,
    val cashSales: BigDecimal,
    val cashCollections: BigDecimal,
    val cashDisbursements: BigDecimal,
    val operatingExpenses: BigDecimal,
    val expectedCash: BigDecimal?,
    val salesCount: Int
) {
    init {
        MerchantReadValidationUtils.validateDateString(shiftDate, "shift_summary.shift_date")
        MerchantReadValidationUtils.validateCurrencyCode(currency, "shift_summary.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "shift_summary.currency_minor_units")
        require(salesCount >= 0) { "sales_count must be non-negative, got $salesCount" }
    }

    override fun toString(): String =
        "ShiftSummaryReportResponse(companyId=$companyId, shiftDate='$shiftDate', currency='$currency', currencyMinorUnits=$currencyMinorUnits, openingFloat=$openingFloat, cashSales=$cashSales, cashCollections=$cashCollections, cashDisbursements=$cashDisbursements, operatingExpenses=$operatingExpenses, expectedCash=$expectedCash, salesCount=$salesCount)"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "company_id", "shift_date", "currency", "currency_minor_units",
            "opening_float", "cash_sales", "cash_collections",
            "cash_disbursements", "operating_expenses", "expected_cash", "sales_count"
        )

        fun fromStrictJson(obj: StrictJsonObject): ShiftSummaryReportResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "ShiftSummaryReportResponse")
            return ShiftSummaryReportResponse(
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                shiftDate = MerchantReadValidationUtils.requireString(obj, "shift_date", minLength = 10, maxLength = 10),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6),
                openingFloat = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "opening_float"),
                cashSales = MerchantReadValidationUtils.requireBigDecimal(obj, "cash_sales"),
                cashCollections = MerchantReadValidationUtils.requireBigDecimal(obj, "cash_collections"),
                cashDisbursements = MerchantReadValidationUtils.requireBigDecimal(obj, "cash_disbursements"),
                operatingExpenses = MerchantReadValidationUtils.requireBigDecimal(obj, "operating_expenses"),
                expectedCash = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "expected_cash"),
                salesCount = MerchantReadValidationUtils.requireInt(obj, "sales_count", min = 0)
            )
        }
    }
}

// ============================================================================
// 6b. Shift Close Response
// POST /api/mobile/v1/companies/{companyId}/reports/shift-close
// ============================================================================

data class ShiftCloseResponse(
    val id: ServerUlid,
    val shiftClosureId: ServerUlid,
    val shiftDate: String,
    val currency: String,
    val currencyMinorUnits: Int,
    val expectedCash: BigDecimal,
    val countedCash: BigDecimal,
    val variance: BigDecimal,
    val varianceStatus: String,
    val closedAt: String,
    val success: Boolean = true,
    val observationRecorded: Boolean = true,
    val isShiftClosed: Boolean = false
) {
    init {
        MerchantReadValidationUtils.validateDateString(shiftDate, "shift_close.shift_date")
        MerchantReadValidationUtils.validateCurrencyCode(currency, "shift_close.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "shift_close.currency_minor_units")
        require(varianceStatus in listOf("EXACT", "OVERAGE", "SHORTAGE")) {
            "variance_status must be EXACT, OVERAGE, or SHORTAGE, got $varianceStatus"
        }
    }

    override fun toString(): String =
        "ShiftCloseResponse(id=$id, shiftClosureId=$shiftClosureId, shiftDate='$shiftDate', currency='$currency', currencyMinorUnits=$currencyMinorUnits, expectedCash=$expectedCash, countedCash=$countedCash, variance=$variance, varianceStatus='$varianceStatus', closedAt='$closedAt')"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "id", "shift_closure_id", "shift_date", "currency", "currency_minor_units",
            "expected_cash", "counted_cash", "variance", "variance_status", "closed_at",
            "success", "observation_recorded", "is_shift_closed"
        )

        fun fromStrictJson(obj: StrictJsonObject): ShiftCloseResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "ShiftCloseResponse")
            val id = MerchantReadValidationUtils.requireUlid(obj, "id")
            val shiftClosureId = if (obj.properties.containsKey("shift_closure_id")) {
                MerchantReadValidationUtils.requireUlid(obj, "shift_closure_id")
            } else {
                id
            }
            return ShiftCloseResponse(
                id = id,
                shiftClosureId = shiftClosureId,
                shiftDate = MerchantReadValidationUtils.requireString(obj, "shift_date", minLength = 10, maxLength = 10),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6),
                expectedCash = MerchantReadValidationUtils.requireBigDecimal(obj, "expected_cash"),
                countedCash = MerchantReadValidationUtils.requireBigDecimal(obj, "counted_cash"),
                variance = MerchantReadValidationUtils.requireBigDecimal(obj, "variance"),
                varianceStatus = MerchantReadValidationUtils.requireString(obj, "variance_status", minLength = 5, maxLength = 8),
                closedAt = MerchantReadValidationUtils.requireString(obj, "closed_at", minLength = 10, maxLength = 40),
                success = (obj.properties["success"] as? StrictJsonBoolean)?.value ?: true,
                observationRecorded = (obj.properties["observation_recorded"] as? StrictJsonBoolean)?.value ?: true,
                isShiftClosed = (obj.properties["is_shift_closed"] as? StrictJsonBoolean)?.value ?: false
            )
        }
    }
}

data class TreasuryAccountItem(
    val publicId: ServerUlid,
    val name: String,
    val accountType: String,
    val currencyCode: String,
    val baseCurrencyCode: String,
    val balanceBase: BigDecimal,
    val balanceCurrency: BigDecimal?,
    val currencyBalanceAvailable: Boolean,
    val isActive: Boolean,
    val bankName: String?
) {
    init {
        MerchantReadValidationUtils.validateStringBounds(name, "treasury_account.name", minLength = 1, maxLength = 255)
        MerchantReadValidationUtils.validateStringBounds(accountType, "treasury_account.account_type", minLength = 1, maxLength = 32)
        MerchantReadValidationUtils.validateCurrencyCode(currencyCode, "treasury_account.currency_code")
        MerchantReadValidationUtils.validateCurrencyCode(baseCurrencyCode, "treasury_account.base_currency_code")
    }

    override fun toString(): String =
        "TreasuryAccountItem(publicId=$publicId, name='$name', accountType='$accountType', currencyCode='$currencyCode', baseCurrencyCode='$baseCurrencyCode', balanceBase=$balanceBase, balanceCurrency=$balanceCurrency, currencyBalanceAvailable=$currencyBalanceAvailable, isActive=$isActive, bankName=${if (bankName != null) "'$bankName'" else "null"})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "public_id", "name", "account_type", "currency_code",
            "base_currency_code", "balance_base", "balance_currency",
            "currency_balance_available", "is_active", "bank_name"
        )

        fun fromStrictJson(obj: StrictJsonObject): TreasuryAccountItem {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "TreasuryAccountItem")
            return TreasuryAccountItem(
                publicId = MerchantReadValidationUtils.requireUlid(obj, "public_id"),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255),
                accountType = MerchantReadValidationUtils.requireString(obj, "account_type", minLength = 1, maxLength = 32),
                currencyCode = MerchantReadValidationUtils.requireString(obj, "currency_code", minLength = 3, maxLength = 3),
                baseCurrencyCode = MerchantReadValidationUtils.requireString(obj, "base_currency_code", minLength = 3, maxLength = 3),
                balanceBase = MerchantReadValidationUtils.requireBigDecimal(obj, "balance_base"),
                balanceCurrency = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "balance_currency"),
                currencyBalanceAvailable = MerchantReadValidationUtils.requireBoolean(obj, "currency_balance_available"),
                isActive = MerchantReadValidationUtils.requireBoolean(obj, "is_active"),
                bankName = MerchantReadValidationUtils.requireNullableString(obj, "bank_name", minLength = 1, maxLength = 255)
            )
        }
    }
}

class TreasuryReportResponse(
    val companyId: ServerUlid,
    cashAccounts: List<TreasuryAccountItem>,
    bankAccounts: List<TreasuryAccountItem>
) {
    val cashAccounts: List<TreasuryAccountItem> = Collections.unmodifiableList(cashAccounts.toList())
    val bankAccounts: List<TreasuryAccountItem> = Collections.unmodifiableList(bankAccounts.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TreasuryReportResponse) return false
        return companyId == other.companyId && cashAccounts == other.cashAccounts && bankAccounts == other.bankAccounts
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + cashAccounts.hashCode()
        result = 31 * result + bankAccounts.hashCode()
        return result
    }

    override fun toString(): String =
        "TreasuryReportResponse(companyId=$companyId, cashAccountCount=${cashAccounts.size}, bankAccountCount=${bankAccounts.size})"

    companion object {
        private val ALLOWED_KEYS = setOf("company_id", "cash_accounts", "bank_accounts")

        fun fromStrictJson(obj: StrictJsonObject): TreasuryReportResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "TreasuryReportResponse")
            val rawCash = MerchantReadValidationUtils.requireArray(obj, "cash_accounts")
            val rawBank = MerchantReadValidationUtils.requireArray(obj, "bank_accounts")
            return TreasuryReportResponse(
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                cashAccounts = rawCash.elements.map { TreasuryAccountItem.fromStrictJson(it.asObject()) },
                bankAccounts = rawBank.elements.map { TreasuryAccountItem.fromStrictJson(it.asObject()) }
            )
        }
    }
}

// ============================================================================
// 7. Business Settings Projection
// GET /api/mobile/v1/companies/{companyId}/settings/business
// ============================================================================

data class BusinessWarehouseRef(
    val id: ServerUlid,
    val code: String,
    val name: String
) {
    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name")

        fun fromStrictJson(obj: StrictJsonObject): BusinessWarehouseRef {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "BusinessWarehouseRef")
            return BusinessWarehouseRef(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                code = MerchantReadValidationUtils.requireString(obj, "code", minLength = 1, maxLength = 64),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255)
            )
        }
    }
}

data class BusinessCashAccountRef(
    val id: ServerUlid,
    val code: String,
    val name: String,
    val currency: String,
    val currencyMinorUnits: Int
) {
    init {
        MerchantReadValidationUtils.validateCurrencyCode(currency, "cash_account.currency")
        MerchantReadValidationUtils.validateMinorUnits(currencyMinorUnits, "cash_account.currency_minor_units")
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name", "currency", "currency_minor_units")

        fun fromStrictJson(obj: StrictJsonObject): BusinessCashAccountRef {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "BusinessCashAccountRef")
            return BusinessCashAccountRef(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                code = MerchantReadValidationUtils.requireString(obj, "code", minLength = 0, maxLength = 64),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255),
                currency = MerchantReadValidationUtils.requireString(obj, "currency", minLength = 3, maxLength = 3),
                currencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "currency_minor_units", min = 0, max = 6)
            )
        }
    }
}

data class BusinessCustomerRef(
    val id: ServerUlid,
    val code: String?,
    val name: String
) {
    override fun toString(): String =
        "BusinessCustomerRef(id=$id, code=${if (code != null) "'$code'" else "null"}, name='[REDACTED]')"

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name")

        fun fromStrictJson(obj: StrictJsonObject): BusinessCustomerRef {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "BusinessCustomerRef")
            return BusinessCustomerRef(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                code = MerchantReadValidationUtils.requireNullableString(obj, "code", minLength = 1, maxLength = 64),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255)
            )
        }
    }
}

data class BusinessCurrencyItem(
    val code: String,
    val name: String,
    val symbol: String,
    val minorUnits: Int,
    val isBase: Boolean
) {
    init {
        MerchantReadValidationUtils.validateCurrencyCode(code, "currency.code")
        MerchantReadValidationUtils.validateMinorUnits(minorUnits, "currency.minor_units")
    }

    companion object {
        private val ALLOWED_KEYS = setOf("code", "name", "symbol", "minor_units", "is_base")

        fun fromStrictJson(obj: StrictJsonObject): BusinessCurrencyItem {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "BusinessCurrencyItem")
            return BusinessCurrencyItem(
                code = MerchantReadValidationUtils.requireString(obj, "code", minLength = 3, maxLength = 3),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 64),
                symbol = MerchantReadValidationUtils.requireString(obj, "symbol", minLength = 1, maxLength = 16),
                minorUnits = MerchantReadValidationUtils.requireInt(obj, "minor_units", min = 0, max = 6),
                isBase = MerchantReadValidationUtils.requireBoolean(obj, "is_base")
            )
        }
    }
}

data class BusinessTaxItem(
    val id: ServerUlid,
    val code: String,
    val name: String,
    val rate: BigDecimal,
    val calculation: String,
    val isDefault: Boolean
) {
    init {
        MerchantReadValidationUtils.validateStringBounds(calculation, "tax.calculation", minLength = 1, maxLength = 32)
    }

    companion object {
        private val ALLOWED_KEYS = setOf("id", "code", "name", "rate", "calculation", "is_default")

        fun fromStrictJson(obj: StrictJsonObject): BusinessTaxItem {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "BusinessTaxItem")
            return BusinessTaxItem(
                id = MerchantReadValidationUtils.requireUlid(obj, "id"),
                code = MerchantReadValidationUtils.requireString(obj, "code", minLength = 1, maxLength = 64),
                name = MerchantReadValidationUtils.requireString(obj, "name", minLength = 1, maxLength = 255),
                rate = MerchantReadValidationUtils.requireBigDecimal(obj, "rate"),
                calculation = MerchantReadValidationUtils.requireString(obj, "calculation", minLength = 1, maxLength = 32),
                isDefault = MerchantReadValidationUtils.requireBoolean(obj, "is_default")
            )
        }
    }
}

class BusinessSettingsResponse(
    val companyId: ServerUlid,
    val nameAr: String,
    val nameEn: String,
    val cityAr: String,
    val cityEn: String,
    val phone: String,
    val taxNumber: String,
    val defaultCurrency: String,
    val defaultCurrencyMinorUnits: Int,
    val timezone: String,
    val defaultTaxPercent: BigDecimal?,
    val defaultWarehouse: BusinessWarehouseRef?,
    val approvedCashAccount: BusinessCashAccountRef?,
    val walkInCustomer: BusinessCustomerRef?,
    currencies: List<BusinessCurrencyItem>,
    taxes: List<BusinessTaxItem>,
    val laravelSyncStatus: String?
) {
    val currencies: List<BusinessCurrencyItem> = Collections.unmodifiableList(currencies.toList())
    val taxes: List<BusinessTaxItem> = Collections.unmodifiableList(taxes.toList())

    init {
        MerchantReadValidationUtils.validateCurrencyCode(defaultCurrency, "business_settings.default_currency")
        MerchantReadValidationUtils.validateMinorUnits(defaultCurrencyMinorUnits, "business_settings.default_currency_minor_units")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BusinessSettingsResponse) return false
        return companyId == other.companyId &&
                nameAr == other.nameAr &&
                nameEn == other.nameEn &&
                cityAr == other.cityAr &&
                cityEn == other.cityEn &&
                phone == other.phone &&
                taxNumber == other.taxNumber &&
                defaultCurrency == other.defaultCurrency &&
                defaultCurrencyMinorUnits == other.defaultCurrencyMinorUnits &&
                timezone == other.timezone &&
                defaultTaxPercent == other.defaultTaxPercent &&
                defaultWarehouse == other.defaultWarehouse &&
                approvedCashAccount == other.approvedCashAccount &&
                walkInCustomer == other.walkInCustomer &&
                currencies == other.currencies &&
                taxes == other.taxes &&
                laravelSyncStatus == other.laravelSyncStatus
    }

    override fun hashCode(): Int = 31 * companyId.hashCode() + defaultCurrency.hashCode()

    override fun toString(): String =
        "BusinessSettingsResponse(companyId=$companyId, nameAr='$nameAr', nameEn='$nameEn', phone='[REDACTED]', defaultCurrency='$defaultCurrency', defaultCurrencyMinorUnits=$defaultCurrencyMinorUnits, timezone='$timezone', currencyCount=${currencies.size}, taxCount=${taxes.size})"

    companion object {
        private val ALLOWED_KEYS = setOf(
            "company_id", "name_ar", "name_en", "city_ar", "city_en", "phone",
            "tax_number", "default_currency", "default_currency_minor_units",
            "timezone", "default_tax_percent", "default_warehouse",
            "approved_cash_account", "walk_in_customer", "currencies",
            "taxes", "laravel_sync_status"
        )

        fun fromStrictJson(obj: StrictJsonObject): BusinessSettingsResponse {
            MerchantReadValidationUtils.checkAllowedKeys(obj, ALLOWED_KEYS, "BusinessSettingsResponse")
            val rawCurrencies = MerchantReadValidationUtils.requireArray(obj, "currencies")
            val rawTaxes = MerchantReadValidationUtils.requireArray(obj, "taxes")
            val rawWh = MerchantReadValidationUtils.optionalObject(obj, "default_warehouse")
            val rawCa = MerchantReadValidationUtils.optionalObject(obj, "approved_cash_account")
            val rawWc = MerchantReadValidationUtils.optionalObject(obj, "walk_in_customer")

            return BusinessSettingsResponse(
                companyId = MerchantReadValidationUtils.requireUlid(obj, "company_id"),
                nameAr = MerchantReadValidationUtils.requireString(obj, "name_ar", minLength = 0, maxLength = 255),
                nameEn = MerchantReadValidationUtils.requireString(obj, "name_en", minLength = 0, maxLength = 255),
                cityAr = MerchantReadValidationUtils.requireString(obj, "city_ar", minLength = 0, maxLength = 255),
                cityEn = MerchantReadValidationUtils.requireString(obj, "city_en", minLength = 0, maxLength = 255),
                phone = MerchantReadValidationUtils.requireString(obj, "phone", minLength = 0, maxLength = 64),
                taxNumber = MerchantReadValidationUtils.requireString(obj, "tax_number", minLength = 0, maxLength = 64),
                defaultCurrency = MerchantReadValidationUtils.requireString(obj, "default_currency", minLength = 3, maxLength = 3),
                defaultCurrencyMinorUnits = MerchantReadValidationUtils.requireInt(obj, "default_currency_minor_units", min = 0, max = 6),
                timezone = MerchantReadValidationUtils.requireString(obj, "timezone", minLength = 1, maxLength = 64),
                defaultTaxPercent = MerchantReadValidationUtils.requireNullableBigDecimal(obj, "default_tax_percent"),
                defaultWarehouse = rawWh?.let { BusinessWarehouseRef.fromStrictJson(it) },
                approvedCashAccount = rawCa?.let { BusinessCashAccountRef.fromStrictJson(it) },
                walkInCustomer = rawWc?.let { BusinessCustomerRef.fromStrictJson(it) },
                currencies = rawCurrencies.elements.map { BusinessCurrencyItem.fromStrictJson(it.asObject()) },
                taxes = rawTaxes.elements.map { BusinessTaxItem.fromStrictJson(it.asObject()) },
                laravelSyncStatus = MerchantReadValidationUtils.requireNullableString(obj, "laravel_sync_status", minLength = 0, maxLength = 255)
            )
        }
    }
}

// ============================================================================
// 8. Typed Errors and Result Envelope
// ============================================================================

data class MerchantReadErrorResponse(
    val code: String,
    val message: String,
    val details: StrictJsonElement? = null
) {
    init {
        require(code.isNotEmpty()) { "error.code must not be empty" }
        require(message.isNotEmpty()) { "error.message must not be empty" }
    }

    override fun toString(): String {
        val safeCode = if (SAFE_CODE_REGEX.matches(code)) code else "[UNTRUSTED_CODE]"
        val hasDetails = details != null && details !is StrictJsonNull
        return "MerchantReadErrorResponse(code='$safeCode', message='[REDACTED]', details=${if (hasDetails) "[REDACTED]" else "null"})"
    }

    companion object {
        private val ROOT_ALLOWED_KEYS = setOf("error")
        private val ERROR_ALLOWED_KEYS = setOf("code", "message", "details")
        private val SAFE_CODE_REGEX = Regex("^[A-Z0-9_]{1,64}$")

        fun fromStrictJson(rootObj: StrictJsonObject): MerchantReadErrorResponse {
            val errObj = try {
                MerchantReadValidationUtils.requireObject(rootObj, "error")
            } catch (_: Exception) {
                // If top-level contains code/message directly
                rootObj
            }
            val code = MerchantReadValidationUtils.requireString(errObj, "code", minLength = 1, maxLength = 255)
            val message = MerchantReadValidationUtils.requireString(errObj, "message", minLength = 1, maxLength = 2048)
            val details = errObj.properties["details"]?.let {
                if (it is StrictJsonNull) null else it
            }
            return MerchantReadErrorResponse(code = code, message = message, details = details)
        }
    }
}

sealed class MerchantReadResult<out T> {
    data class Success<out T>(val value: T) : MerchantReadResult<T>() {
        override fun toString(): String = "MerchantReadResult.Success(value=$value)"
    }

    data class AuthDenial(val statusCode: Int, val error: MerchantReadErrorResponse) : MerchantReadResult<Nothing>() {
        override fun toString(): String = "MerchantReadResult.AuthDenial(statusCode=$statusCode, error=$error)"
    }

    data class Forbidden(val error: MerchantReadErrorResponse) : MerchantReadResult<Nothing>() {
        override fun toString(): String = "MerchantReadResult.Forbidden(error=$error)"
    }

    data class NotFound(val error: MerchantReadErrorResponse) : MerchantReadResult<Nothing>() {
        override fun toString(): String = "MerchantReadResult.NotFound(error=$error)"
    }

    data class HttpError(val statusCode: Int, val error: MerchantReadErrorResponse?) : MerchantReadResult<Nothing>() {
        override fun toString(): String = "MerchantReadResult.HttpError(statusCode=$statusCode, error=$error)"
    }

    data class SchemaError(
        val category: String = "SCHEMA_ERROR",
        val message: String = "Response schema validation failed"
    ) : MerchantReadResult<Nothing>() {
        override fun toString(): String = "MerchantReadResult.SchemaError(category='$category')"
    }

    data class NetworkError(
        val category: String = "NETWORK_ERROR",
        val message: String = "Network transport error"
    ) : MerchantReadResult<Nothing>() {
        override fun toString(): String = "MerchantReadResult.NetworkError(category='$category')"
    }
}
