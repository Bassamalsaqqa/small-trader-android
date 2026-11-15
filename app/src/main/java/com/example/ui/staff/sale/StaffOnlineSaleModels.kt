package com.example.ui.staff.sale

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.onlinecash.OnlineCashSaleReceipt
import com.example.data.mobile.onlinecash.PreparedOnlineSaleIntent
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductPackagingUnit
import com.example.data.mobile.session.StaffSessionRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Traced authority resolution for online sales.
 *
 * Verifies that all 7 required root authorities, plus currency minor units context and line tax policies,
 * are authoritatively present from session, bootstrap, or catalog.
 *
 * Fails closed on any missing contract without inventing fallback IDs, default currencies,
 * or synthetic tax rates.
 */
data class StaffOnlineSaleAuthorityResolution(
    val companyId: ServerUlid?,
    val actorId: ServerUlid?,
    val deviceId: ClientUuid?,
    val warehouseId: ServerUlid?,
    val cashAccountId: ServerUlid?,
    val walkInCustomerId: ServerUlid?,
    val currency: String?,
    val currencyMinorUnits: Int?,
    val missingContracts: List<String>
) {
    val isDispatchPermitted: Boolean
        get() = companyId != null &&
                actorId != null &&
                deviceId != null &&
                warehouseId != null &&
                cashAccountId != null &&
                walkInCustomerId != null &&
                currency != null &&
                currencyMinorUnits != null &&
                missingContracts.isEmpty()

    companion object {
        fun resolve(
            bootstrap: BootstrapResponse?,
            sessionRecord: StaffSessionRecord?,
            currencyMinorUnitsOverride: Int? = null
        ): StaffOnlineSaleAuthorityResolution {
            val missing = mutableListOf<String>()

            val companyId = sessionRecord?.selectedCompanyId ?: bootstrap?.companyId
            if (companyId == null) {
                missing.add("Missing authoritative companyId from active session")
            }

            val actorId = sessionRecord?.actorUlid ?: bootstrap?.staff?.id
            if (actorId == null) {
                missing.add("Missing authoritative staff actorId from active session")
            }

            val deviceId = sessionRecord?.deviceUuid
            if (deviceId == null) {
                missing.add("Missing authenticated deviceId from session record")
            }

            val warehouseId = bootstrap?.defaultWarehouse?.id
            if (warehouseId == null) {
                missing.add("Missing defaultWarehouse from bootstrap response")
            }

            val cashAccountId = bootstrap?.approvedCashAccount?.id
            if (cashAccountId == null) {
                missing.add("Missing approvedCashAccount from bootstrap response")
            }

            val walkInCustomerId = bootstrap?.walkInCustomerId
            if (walkInCustomerId == null) {
                missing.add("Missing walkInCustomerId from bootstrap response")
            }

            val currency = bootstrap?.baseCurrency
            if (currency == null) {
                missing.add("Missing baseCurrency from bootstrap response")
            }

            // V1 bootstrap contract gap: currency_minor_units is absent in V1 BootstrapResponse
            val cmu = currencyMinorUnitsOverride
            if (cmu == null) {
                missing.add("V1 Bootstrap lacks currency_minor_units (required for OnlineSaleContext scale validation)")
            } else if (cmu !in 0..6) {
                missing.add("currency_minor_units must be in 0..6, got $cmu")
            }

            return StaffOnlineSaleAuthorityResolution(
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                warehouseId = warehouseId,
                cashAccountId = cashAccountId,
                walkInCustomerId = walkInCustomerId,
                currency = currency,
                currencyMinorUnits = if (cmu != null && cmu in 0..6) cmu else null,
                missingContracts = missing
            )
        }
    }
}

/**
 * Line item in the staff online sale draft basket.
 *
 * All financial quantities, unit prices, and subtotals preserve exact decimal representations (BigDecimal).
 * Uses RoundingMode.HALF_UP (Laravel platform standard). Zero Double/Float usage.
 */
data class StaffOnlineSaleDraftLine(
    val lineId: String = UUID.randomUUID().toString(),
    val product: ProductCatalogItem,
    val productUnitId: ServerUlid,
    val unitCode: String,
    val conversionToBase: PositiveDecimalQuantity,
    val quantity: PositiveDecimalQuantity,
    val unitPrice: MoneyAmount,
    val taxRateId: ServerUlid?,
    val taxRate: TaxPercentage,
    val taxInclusive: Boolean? // Nullable: V1 ProductCatalogItem lacks tax_inclusive for taxed products!
) {
    val estimatedSubtotal: MoneyAmount
        get() {
            val sub = quantity.value.multiply(unitPrice.value).setScale(6, RoundingMode.HALF_UP)
            return MoneyAmount(sub.toPlainString())
        }

    val estimatedTax: MoneyAmount
        get() {
            if (taxRateId == null || taxRate.toPlainString() == "0.000000") {
                return MoneyAmount("0.000000")
            }
            val rate = taxRate.toRate()
            val sub = quantity.value.multiply(unitPrice.value)
            val tax = if (taxInclusive == true) {
                val divisor = BigDecimal.ONE.add(rate)
                val base = sub.divide(divisor, 8, RoundingMode.HALF_UP)
                sub.subtract(base).setScale(6, RoundingMode.HALF_UP)
            } else {
                sub.multiply(rate).setScale(6, RoundingMode.HALF_UP)
            }
            return MoneyAmount(tax.toPlainString())
        }

    val hasUnresolvedTaxContract: Boolean
        get() = taxRateId != null && taxInclusive == null

    companion object {
        fun fromProduct(
            product: ProductCatalogItem,
            packagingUnit: ProductPackagingUnit? = null,
            quantity: PositiveDecimalQuantity = PositiveDecimalQuantity.of(1),
            taxInclusiveOverride: Boolean? = null
        ): StaffOnlineSaleDraftLine {
            val unitId = packagingUnit?.productUnitId ?: product.defaultUnit.productUnitId
            val code = packagingUnit?.code ?: product.baseUnit.code
            val conversion = packagingUnit?.conversionToBase ?: product.defaultUnit.conversionToBase
            val price = packagingUnit?.salePrice ?: product.defaultUnit.salePrice ?: product.unitPrice

            val (tRateId, tRate, tInc) = if (product.taxRateId == null) {
                Triple(null, TaxPercentage.fromString("0.000000"), false)
            } else {
                Triple(product.taxRateId, product.taxRate ?: TaxPercentage.fromString("0.000000"), taxInclusiveOverride)
            }

            return StaffOnlineSaleDraftLine(
                product = product,
                productUnitId = unitId,
                unitCode = code,
                conversionToBase = conversion,
                quantity = quantity,
                unitPrice = price,
                taxRateId = tRateId,
                taxRate = tRate,
                taxInclusive = tInc
            )
        }
    }
}

/**
 * Immutable staff online sale draft basket and state.
 *
 * Basket preview totals in UI are strictly non-authoritative client ESTIMATES.
 * The central Laravel backend is the sole financial and posting authority.
 */
data class StaffOnlineSaleDraft(
    val lines: List<StaffOnlineSaleDraftLine> = emptyList(),
    val selectedCustomer: CustomerDirectoryItem? = null,
    val cashTendered: MoneyAmount? = null,
    val isSubmitting: Boolean = false,
    val submissionError: String? = null,
    val preparedIntent: PreparedOnlineSaleIntent? = null,
    val receipt: OnlineCashSaleReceipt? = null
) {
    val estimatedSubtotal: MoneyAmount
        get() {
            var sum = BigDecimal.ZERO
            for (line in lines) {
                sum = sum.add(line.estimatedSubtotal.value)
            }
            return MoneyAmount(sum.setScale(6, RoundingMode.HALF_UP).toPlainString())
        }

    val estimatedTax: MoneyAmount
        get() {
            var sum = BigDecimal.ZERO
            for (line in lines) {
                sum = sum.add(line.estimatedTax.value)
            }
            return MoneyAmount(sum.setScale(6, RoundingMode.HALF_UP).toPlainString())
        }

    val estimatedTotal: MoneyAmount
        get() {
            var total = BigDecimal.ZERO
            for (line in lines) {
                if (line.taxInclusive == true) {
                    total = total.add(line.estimatedSubtotal.value)
                } else {
                    total = total.add(line.estimatedSubtotal.value).add(line.estimatedTax.value)
                }
            }
            return MoneyAmount(total.setScale(6, RoundingMode.HALF_UP).toPlainString())
        }

    val hasUnresolvedTaxContract: Boolean
        get() = lines.any { it.hasUnresolvedTaxContract }

    fun canPrepareIntent(authority: StaffOnlineSaleAuthorityResolution): Boolean {
        if (!authority.isDispatchPermitted) return false
        if (lines.isEmpty()) return false
        if (hasUnresolvedTaxContract) return false
        return true
    }
}

/**
 * Builds canonical RFC 8785 JSON wire payloads and prepares typed [PreparedOnlineSaleIntent] instances.
 *
 * Follows exact wire specification for POST /api/mobile/v2/companies/{companyId}/sales/online-cash:
 * - 7 root keys: client_event_id, device_id, customer_id, warehouse_id, currency, lines, payment
 * - 8 line keys: product_id, product_unit_id, conversion_to_base, quantity, unit_price, tax_rate_id, tax_rate, tax_inclusive
 * - 4 payment keys: settlement_mode, payment_method, cash_account_id, amount
 */
object StaffOnlineSaleRequestBuilder {

    fun formatTenderAmount(total: MoneyAmount, currencyMinorUnits: Int): MoneyAmount {
        val scaled = total.value.setScale(currencyMinorUnits, RoundingMode.HALF_UP)
        return MoneyAmount(scaled.toPlainString())
    }

    fun buildRawWireMap(
        draft: StaffOnlineSaleDraft,
        authority: StaffOnlineSaleAuthorityResolution,
        clientEventId: ClientUuid = ClientUuid.random()
    ): Map<String, Any?> {
        require(authority.isDispatchPermitted) {
            "Cannot build wire payload: authority resolution incomplete (${authority.missingContracts})"
        }
        require(draft.lines.isNotEmpty()) {
            "Cannot build wire payload: draft must contain at least one line item"
        }
        require(!draft.hasUnresolvedTaxContract) {
            "Cannot build wire payload: draft contains lines with unresolved tax contract"
        }

        val cmu = authority.currencyMinorUnits!!
        val paymentAmount = formatTenderAmount(draft.estimatedTotal, cmu)

        val customerId = draft.selectedCustomer?.id ?: authority.walkInCustomerId!!
        val warehouseId = authority.warehouseId!!
        val cashAccountId = authority.cashAccountId!!
        val deviceId = authority.deviceId!!
        val currency = authority.currency!!

        val linesWire = draft.lines.map { line ->
            mapOf(
                "product_id" to line.product.id.value,
                "product_unit_id" to line.productUnitId.value,
                "conversion_to_base" to line.conversionToBase.toPlainString(),
                "quantity" to line.quantity.toPlainString(),
                "unit_price" to line.unitPrice.toPlainString(),
                "tax_rate_id" to line.taxRateId?.value,
                "tax_rate" to line.taxRate.toPlainString(),
                "tax_inclusive" to (line.taxInclusive ?: false)
            )
        }

        val paymentWire = mapOf(
            "settlement_mode" to "full",
            "payment_method" to "cash",
            "cash_account_id" to cashAccountId.value,
            "amount" to paymentAmount.toPlainString()
        )

        return mapOf(
            "client_event_id" to clientEventId.value,
            "device_id" to deviceId.value,
            "customer_id" to customerId.value,
            "warehouse_id" to warehouseId.value,
            "currency" to currency,
            "lines" to linesWire,
            "payment" to paymentWire
        )
    }

    fun prepareIntent(
        draft: StaffOnlineSaleDraft,
        authority: StaffOnlineSaleAuthorityResolution,
        scopeGeneration: Long,
        clientEventId: ClientUuid = ClientUuid.random()
    ): PreparedOnlineSaleIntent {
        val wireMap = buildRawWireMap(draft, authority, clientEventId)
        val canonicalBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(wireMap)
        val context = OnlineSaleContext(
            actorId = authority.actorId!!,
            companyId = authority.companyId!!,
            currencyMinorUnits = authority.currencyMinorUnits!!
        )
        return PreparedOnlineSaleIntent.prepare(
            context = context,
            rawWireBytes = canonicalBytes,
            scopeGeneration = scopeGeneration
        )
    }
}
