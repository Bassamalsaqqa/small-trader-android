package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalSerializable
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.Rfc3339Validator
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.TaxPercentage
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Authoritative Offline Sale Lease representation under Small Trader Mobile V2 Contract Revision 3.
 *
 * Returned by POST /api/mobile/v2/companies/{companyId}/offline-sale-lease.
 *
 * Invariants:
 * - One active lease per company, staff actor, and device.
 * - Leased catalog snapshot version must match complete cached catalog (length 1..64).
 * - Exact policy limits: maxOfflineHours > 0, maxOfflineSalesCount > 0, maxOfflineTotalAmount > 0.
 * - Currency must match base currency (3 uppercase letters), precision 0..6.
 * - Authoritative cash account, warehouse, walk-in customer.
 * - Explicit tax setup: taxRateId non-null requires taxRate and taxCalculation ("inclusive"|"exclusive");
 *   taxRateId null requires taxRate null or "0.000000" and taxCalculation null.
 */
data class OfflineSaleLease(
    val leaseId: ClientUuid,
    val companyId: ServerUlid,
    val actorId: ServerUlid,
    val deviceId: ClientUuid,
    val catalogSnapshotVersion: String,
    val issuedAt: String,
    val expiresAt: String,
    val postedSalesCount: Int,
    val postedTotalAmount: BigDecimal,
    val currency: String,
    val currencyMinorUnits: Int,
    val warehouseId: ServerUlid,
    val cashAccountId: ServerUlid,
    val walkInCustomerId: ServerUlid,
    val taxRateId: ServerUlid?,
    val taxRate: TaxPercentage?,
    val taxCalculation: String?,
    val maxOfflineHours: Long,
    val maxOfflineSalesCount: Long,
    val maxOfflineTotalAmount: BigDecimal
) : CanonicalSerializable {

    init {
        require(catalogSnapshotVersion.isNotBlank() && catalogSnapshotVersion.length <= 64) {
            "catalogSnapshotVersion must be 1..64 chars, got '$catalogSnapshotVersion'"
        }
        Rfc3339Validator.requireValid(issuedAt, "issued_at")
        Rfc3339Validator.requireValid(expiresAt, "expires_at")
        require(postedSalesCount >= 0) { "postedSalesCount must be non-negative" }
        require(postedTotalAmount >= BigDecimal.ZERO && postedTotalAmount.scale() <= 6) {
            "postedTotalAmount must be non-negative with scale <= 6"
        }
        require(ContractConstants.CURRENCY_CODE_REGEX.matches(currency)) {
            "currency must be 3 uppercase letters, got '$currency'"
        }
        require(currencyMinorUnits in 0..6) {
            "currencyMinorUnits must be in 0..6, got $currencyMinorUnits"
        }
        if (taxRateId != null) {
            requireNotNull(taxRate) { "taxRate must be non-null when taxRateId is present" }
            require(taxCalculation == "inclusive" || taxCalculation == "exclusive") {
                "taxCalculation must be 'inclusive' or 'exclusive', got '$taxCalculation'"
            }
        } else {
            require(taxRate == null || taxRate.toPlainString() == "0.000000") {
                "taxRate must be null or '0.000000' for ExplicitNoTax lease"
            }
            require(taxCalculation == null) {
                "taxCalculation must be null for ExplicitNoTax lease"
            }
        }
        require(maxOfflineHours > 0) { "maxOfflineHours must be > 0" }
        require(maxOfflineSalesCount > 0) { "maxOfflineSalesCount must be > 0" }
        require(maxOfflineTotalAmount > BigDecimal.ZERO && maxOfflineTotalAmount.scale() <= 6) {
            "maxOfflineTotalAmount must be > 0 with scale <= 6"
        }
    }

    /**
     * Checks if current clock time is strictly within [issuedAt, expiresAt).
     * Fails closed if clock is before issuedAt (clock uncertainty / backwards skew)
     * or if clock is at or past expiresAt (lease expired).
     */
    fun isTimeValid(nowMillis: Long): Boolean {
        val issuedMillis = parseRfc3339Millis(issuedAt) ?: return false
        val expiresMillis = parseRfc3339Millis(expiresAt) ?: return false
        return nowMillis in issuedMillis until expiresMillis
    }

    override fun toCanonicalStructure(): Map<String, Any?> = mapOf(
        "lease_id" to leaseId.value,
        "company_id" to companyId.value,
        "actor_id" to actorId.value,
        "device_id" to deviceId.value,
        "catalog_snapshot_version" to catalogSnapshotVersion,
        "issued_at" to issuedAt,
        "expires_at" to expiresAt,
        "posted_sales_count" to postedSalesCount,
        "posted_total_amount" to postedTotalAmount.setScale(6, RoundingMode.UNNECESSARY).toPlainString(),
        "currency" to currency,
        "currency_minor_units" to currencyMinorUnits,
        "warehouse_id" to warehouseId.value,
        "cash_account_id" to cashAccountId.value,
        "walk_in_customer_id" to walkInCustomerId.value,
        "tax_rate_id" to taxRateId?.value,
        "tax_rate" to taxRate?.toPlainString(),
        "tax_calculation" to taxCalculation,
        "max_offline_hours" to maxOfflineHours,
        "max_offline_sales_count" to maxOfflineSalesCount,
        "max_offline_total_amount" to maxOfflineTotalAmount.setScale(6, RoundingMode.UNNECESSARY).toPlainString()
    )

    companion object {
        private val ALLOWED_KEYS = setOf(
            "lease_id", "company_id", "actor_id", "device_id",
            "catalog_snapshot_version", "issued_at", "expires_at",
            "posted_sales_count", "posted_total_amount",
            "currency", "currency_minor_units",
            "warehouse_id", "cash_account_id", "walk_in_customer_id",
            "tax_rate_id", "tax_rate", "tax_calculation",
            "max_offline_hours", "max_offline_sales_count", "max_offline_total_amount"
        )

        fun fromStrictJson(obj: StrictJsonObject): OfflineSaleLease {
            val unexpected = obj.properties.keys - ALLOWED_KEYS
            if (unexpected.isNotEmpty()) {
                throw CanonicalSerializationException("Unexpected fields in OfflineSaleLease: $unexpected")
            }

            val rawTaxRateId = obj["tax_rate_id"]
            val taxRateId = if (rawTaxRateId == null || rawTaxRateId is StrictJsonNull) {
                null
            } else {
                ServerUlid.parse(rawTaxRateId.asString())
            }

            val rawTaxRate = obj["tax_rate"]
            val taxRate = if (rawTaxRate == null || rawTaxRate is StrictJsonNull) {
                null
            } else {
                TaxPercentage.fromString(rawTaxRate.asString())
            }

            val rawTaxCalc = obj["tax_calculation"]
            val taxCalculation = if (rawTaxCalc == null || rawTaxCalc is StrictJsonNull) {
                null
            } else {
                rawTaxCalc.asString()
            }

            val rawPostedTotal = obj.require("posted_total_amount").asString()
            val postedTotalAmount = BigDecimal(rawPostedTotal).setScale(6, RoundingMode.UNNECESSARY)

            val rawMaxTotal = obj.require("max_offline_total_amount").asString()
            val maxOfflineTotalAmount = BigDecimal(rawMaxTotal).setScale(6, RoundingMode.UNNECESSARY)

            val rawPostedCount = when (val elem = obj.require("posted_sales_count")) {
                is StrictJsonNumber -> elem.asInt()
                is StrictJsonString -> elem.value.toInt()
                else -> throw CanonicalSerializationException("Invalid posted_sales_count")
            }

            val rawMaxHours = when (val elem = obj.require("max_offline_hours")) {
                is StrictJsonNumber -> elem.asLong()
                is StrictJsonString -> elem.value.toLong()
                else -> throw CanonicalSerializationException("Invalid max_offline_hours")
            }

            val rawMaxCount = when (val elem = obj.require("max_offline_sales_count")) {
                is StrictJsonNumber -> elem.asLong()
                is StrictJsonString -> elem.value.toLong()
                else -> throw CanonicalSerializationException("Invalid max_offline_sales_count")
            }

            return OfflineSaleLease(
                leaseId = ClientUuid.parse(obj.require("lease_id").asString()),
                companyId = ServerUlid.parse(obj.require("company_id").asString()),
                actorId = ServerUlid.parse(obj.require("actor_id").asString()),
                deviceId = ClientUuid.parse(obj.require("device_id").asString()),
                catalogSnapshotVersion = obj.require("catalog_snapshot_version").asString(),
                issuedAt = obj.require("issued_at").asString(),
                expiresAt = obj.require("expires_at").asString(),
                postedSalesCount = rawPostedCount,
                postedTotalAmount = postedTotalAmount,
                currency = obj.require("currency").asString(),
                currencyMinorUnits = obj.require("currency_minor_units").asInt(),
                warehouseId = ServerUlid.parse(obj.require("warehouse_id").asString()),
                cashAccountId = ServerUlid.parse(obj.require("cash_account_id").asString()),
                walkInCustomerId = ServerUlid.parse(obj.require("walk_in_customer_id").asString()),
                taxRateId = taxRateId,
                taxRate = taxRate,
                taxCalculation = taxCalculation,
                maxOfflineHours = rawMaxHours,
                maxOfflineSalesCount = rawMaxCount,
                maxOfflineTotalAmount = maxOfflineTotalAmount
            )
        }

        internal fun parseRfc3339Millis(value: String): Long? {
            val match = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.([0-9]{1,9}))?(?:[Zz]|([+-])(\d{2}):(\d{2}))${'$'}""").matchEntire(value)
                ?: return null
            val groups = match.groupValues
            val year = groups[1].toIntOrNull() ?: return null
            val month = groups[2].toIntOrNull() ?: return null
            val day = groups[3].toIntOrNull() ?: return null
            val hour = groups[4].toIntOrNull() ?: return null
            val minute = groups[5].toIntOrNull() ?: return null
            val second = groups[6].toIntOrNull() ?: return null
            val millisFraction = if (groups[7].isNotEmpty()) {
                val fracStr = groups[7].padEnd(3, '0').substring(0, 3)
                fracStr.toIntOrNull() ?: 0
            } else 0

            return try {
                val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                    isLenient = false
                    clear()
                    set(year, month - 1, day, hour, minute, second)
                    set(GregorianCalendar.MILLISECOND, millisFraction)
                }
                cal.timeInMillis
            } catch (_: Exception) {
                null
            }
        }
    }
}
