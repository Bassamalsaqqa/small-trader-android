package com.example.data.mobile.contract

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic line calculation preview conforming to Laravel SalesLineCalculator.php parity:
 * (commit f8a0cf7012c4875bec7320d42e0f8dde58593b52, SalesLineCalculator.php)
 *
 * POLICY & SCOPE NOTICE:
 * 1. Scope Boundary: Supported no-discount base currency line calculator preview only. Line discounts
 *    and multi-currency conversions are not supported in this offline sales contract scope.
 * 2. Laravel Sole Posting Authority: The central Laravel backend (`account`) is the sole and authoritative
 *    posting authority for all general ledger entries, inventory lot movements, and fiscal tax/invoice records.
 *    This class provides deterministic client-side calculation parity for local preview and verification only,
 *    and does not establish independent financial authority.
 * 3. Currency Minor Units: Callers must ensure `minorUnits` matches currency precision (0..6). Standard default
 *    is 2 minor units (e.g. ILS/USD/EUR), but 3-minor currencies (e.g. KWD/BHD/OMR/JOD) or 0-minor (JPY) must explicitly
 *    pass their authoritative scale.
 *
 * Sequence:
 * 1. Line Subtotal: rawSubtotal = quantity * unitPrice -> rounded to minorUnits with RoundingMode.HALF_UP
 * 2. Tax Calculation:
 *    - Inclusive: actualNet = subtotal / (1 + rate) rounded to minorUnits; tax = subtotal - actualNet; total = subtotal
 *    - Exclusive: tax = (subtotal * rate) rounded to minorUnits; total = subtotal + tax
 * 3. Scale Normalization: all figures returned with 6 decimal places (DECIMAL(20,6)).
 */
object SalesLineCalculator {

    data class CalculationResult(
        val subtotal: MoneyAmount,
        val netBeforeTax: MoneyAmount,
        val taxAmount: MoneyAmount,
        val lineTotal: MoneyAmount
    )

    fun calculate(
        quantity: DecimalQuantity,
        unitPrice: MoneyAmount,
        taxPercentage: TaxPercentage,
        taxInclusive: Boolean,
        minorUnits: Int = ContractConstants.MINOR_UNITS
    ): CalculationResult {
        require(minorUnits in 0..6) {
            "minorUnits must be explicitly bounded between 0 and 6, got $minorUnits"
        }

        // Step 1: Line Subtotal
        val rawSubtotal = quantity.value.multiply(unitPrice.value)
        val subtotalMinor = rawSubtotal.setScale(minorUnits, RoundingMode.HALF_UP)

        val netBeforeTaxMinor: BigDecimal
        val taxAmountMinor: BigDecimal
        val lineTotalMinor: BigDecimal

        val rate = taxPercentage.value.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)

        if (taxInclusive) {
            // Step 2 (Inclusive):
            // actualNet = netBeforeTax / (1 + rate) -> rounded to minorUnits
            val onePlusRate = BigDecimal.ONE.add(rate)
            netBeforeTaxMinor = subtotalMinor.divide(onePlusRate, minorUnits, RoundingMode.HALF_UP)
            // tax = netBeforeTax - actualNet
            taxAmountMinor = subtotalMinor.subtract(netBeforeTaxMinor)
            // total = gross subtotal
            lineTotalMinor = subtotalMinor
        } else {
            // Step 2 (Exclusive):
            netBeforeTaxMinor = subtotalMinor
            // tax = (netBeforeTax * rate) -> rounded to minorUnits
            taxAmountMinor = subtotalMinor.multiply(rate).setScale(minorUnits, RoundingMode.HALF_UP)
            // total = netBeforeTax + tax
            lineTotalMinor = subtotalMinor.add(taxAmountMinor)
        }

        // Step 3: Scale Normalization to 6 decimal places (DECIMAL(20,6))
        return CalculationResult(
            subtotal = MoneyAmount(subtotalMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString()),
            netBeforeTax = MoneyAmount(netBeforeTaxMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString()),
            taxAmount = MoneyAmount(taxAmountMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString()),
            lineTotal = MoneyAmount(lineTotalMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString())
        )
    }
}
