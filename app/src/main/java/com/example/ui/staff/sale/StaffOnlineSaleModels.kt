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
import com.example.data.mobile.readclient.BootstrapUnit
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
    val productUnitId: ServerUlid, // Qualified online product_unit_id wire property (holds Unit.public_id, i.e., DTO unitId)
    val unitCode: String,
    val conversionToBase: PositiveDecimalQuantity,
    val quantity: PositiveDecimalQuantity,
    val unitPrice: MoneyAmount,
    val taxRateId: ServerUlid?,
    val taxRate: TaxPercentage?, // Null when product has unassigned tax association in catalog (fail closed)
    val taxInclusive: Boolean? // Nullable: V1 ProductCatalogItem lacks tax_inclusive for taxed products!
) {
    val estimatedSubtotal: MoneyAmount
        get() {
            val sub = quantity.value.multiply(unitPrice.value).setScale(6, RoundingMode.HALF_UP)
            return MoneyAmount(sub.toPlainString())
        }

    val estimatedTax: MoneyAmount
        get() {
            val r = taxRate
            if (r == null || taxRateId == null || r.toPlainString() == "0.000000") {
                return MoneyAmount.ZERO
            }
            val rate = r.toRate()
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

    val hasUnresolvedUnitContract: Boolean
        get() = unitCode == "UNKNOWN_UNIT"

    val hasUnresolvedTaxContract: Boolean
        get() = (taxRateId != null && taxInclusive == null) || (taxRateId == null && taxRate == null)

    val hasUnresolvedContract: Boolean
        get() = hasUnresolvedTaxContract || hasUnresolvedUnitContract

    companion object {
        fun fromProduct(
            product: ProductCatalogItem,
            packagingUnit: ProductPackagingUnit? = null,
            quantity: PositiveDecimalQuantity = PositiveDecimalQuantity.of(1),
            taxInclusiveOverride: Boolean? = null,
            explicitNoTax: Boolean = false,
            bootstrapUnits: List<BootstrapUnit> = emptyList()
        ): StaffOnlineSaleDraftLine {
            // Qualified online product_unit_id means Unit.public_id -> DTO unitId, not productUnitId pivot!
            val unitId = packagingUnit?.unitId ?: product.defaultUnit.unitId

            // Unit label resolution: default code NOT base code if default unit is different.
            // 1. Packaging unit code if explicitly selected.
            // 2. If defaultUnit.unitId matches baseUnit.id -> baseUnit.code.
            // 3. Search product.units matching defaultUnit.unitId.
            // 4. Search bootstrapUnits matching defaultUnit.unitId.
            // 5. Fail closed as UNKNOWN_UNIT if unresolvable without guessing.
            val code = packagingUnit?.code ?: run {
                if (product.defaultUnit.unitId == product.baseUnit.id) {
                    product.baseUnit.code
                } else {
                    product.units.firstOrNull { it.unitId == product.defaultUnit.unitId }?.code
                        ?: bootstrapUnits.firstOrNull { it.id == product.defaultUnit.unitId }?.code
                        ?: "UNKNOWN_UNIT"
                }
            }

            val conversion = packagingUnit?.conversionToBase ?: product.defaultUnit.conversionToBase
            val price = packagingUnit?.salePrice ?: product.defaultUnit.salePrice ?: product.unitPrice

            // Truthful paired tax rate handling:
            // (taxRateId == null && taxRate == null) || (taxRateId != null && taxRate != null)
            // Null pair means unassigned tax association, NEVER an inferred zero tax or exemption.
            val (tRateId, tRate, tInc) = if (product.taxRateId == null) {
                if (explicitNoTax) {
                    Triple(null, TaxPercentage.fromString("0.000000"), false)
                } else {
                    // Fail closed: typed unresolved instead of inferred zero
                    Triple(null, null, null)
                }
            } else {
                val rate = product.taxRate ?: throw IllegalStateException(
                    "Product '${product.sku}' has broken tax pair: taxRateId is non-null (${product.taxRateId}) but taxRate is null"
                )
                Triple(product.taxRateId, rate, taxInclusiveOverride)
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
    val isIndeterminate: Boolean = false, // Frozen state during indeterminate recovery
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

    val hasUnresolvedContracts: Boolean
        get() = lines.any { it.hasUnresolvedContract }

    fun canPrepareIntent(authority: StaffOnlineSaleAuthorityResolution): Boolean {
        if (!authority.isDispatchPermitted) return false
        if (lines.isEmpty()) return false
        if (hasUnresolvedContracts) return false
        if (isIndeterminate) return false
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
        require(!draft.hasUnresolvedContracts) {
            "Cannot build wire payload: draft contains lines with unresolved contracts"
        }

        val cmu = authority.currencyMinorUnits!!
        val paymentAmount = formatTenderAmount(draft.estimatedTotal, cmu)

        val customerId = draft.selectedCustomer?.id ?: authority.walkInCustomerId!!
        val warehouseId = authority.warehouseId!!
        val cashAccountId = authority.cashAccountId!!
        val deviceId = authority.deviceId!!
        val currency = authority.currency!!

        val linesWire = draft.lines.map { line ->
            if (line.hasUnresolvedUnitContract) {
                throw IllegalStateException("Cannot build wire line '${line.product.name}': unresolvable unit ID '${line.productUnitId.value}'")
            }
            val rate = line.taxRate ?: throw IllegalStateException(
                "Cannot build wire line '${line.product.name}': tax_rate is unassigned"
            )
            val inc = line.taxInclusive ?: throw IllegalStateException(
                "Cannot build wire line '${line.product.name}': tax_inclusive is unresolved"
            )
            mapOf(
                "product_id" to line.product.id.value,
                "product_unit_id" to line.productUnitId.value,
                "conversion_to_base" to line.conversionToBase.toPlainString(),
                "quantity" to line.quantity.toPlainString(),
                "unit_price" to line.unitPrice.toPlainString(),
                "tax_rate_id" to line.taxRateId?.value,
                "tax_rate" to rate.toPlainString(),
                "tax_inclusive" to inc
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
