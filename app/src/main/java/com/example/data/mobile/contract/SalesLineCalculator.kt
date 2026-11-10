package com.example.data.mobile.contract

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic line calculation matching Laravel SalesLineCalculator.php parity:
 * (commit f8a0cf7012c4875bec7320d42e0f8dde58593b52, SalesLineCalculator.php)
 *
 * POLICY NOTICE:
 * The Laravel backend (`account`) is the sole and authoritative posting authority
 * for all accounting ledger, inventory movement, and official tax/invoice records.
 * This class provides deterministic client-side calculation parity for local preview
 * and verification only, and does not establish independent financial authority.
 *
 * Sequence:
 * 1. Line Subtotal: rawSubtotal = quantity * unitPrice -> rounded to minorUnits with RoundingMode.HALF_UP
 * 2. Line Discount: (subtotal * discountRate) rounded to minorUnits -> netBeforeTax = subtotal - discount
 * 3. Tax Calculation:
 *    - Inclusive: actualNet = netBeforeTax / (1 + rate) rounded to minorUnits; tax = netBeforeTax - actualNet; total = netBeforeTax
 *    - Exclusive: tax = (netBeforeTax * rate) rounded to minorUnits; total = netBeforeTax + tax
 * 4. Scale Normalization: all figures returned with 6 decimal places (DECIMAL(20,6)).
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
        // Step 1: Line Subtotal
        val rawSubtotal = quantity.value.multiply(unitPrice.value)
        val subtotalMinor = rawSubtotal.setScale(minorUnits, RoundingMode.HALF_UP)

        val netBeforeTaxMinor: BigDecimal
        val taxAmountMinor: BigDecimal
        val lineTotalMinor: BigDecimal

        val rate = taxPercentage.value.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)

        if (taxInclusive) {
            // Step 3 (Inclusive):
            // actualNet = netBeforeTax / (1 + rate) -> rounded to minorUnits
            val onePlusRate = BigDecimal.ONE.add(rate)
            netBeforeTaxMinor = subtotalMinor.divide(onePlusRate, minorUnits, RoundingMode.HALF_UP)
            // tax = netBeforeTax - actualNet
            taxAmountMinor = subtotalMinor.subtract(netBeforeTaxMinor)
            // total = gross discounted subtotal
            lineTotalMinor = subtotalMinor
        } else {
            // Step 3 (Exclusive):
            netBeforeTaxMinor = subtotalMinor
            // tax = (netBeforeTax * rate) -> rounded to minorUnits
            taxAmountMinor = subtotalMinor.multiply(rate).setScale(minorUnits, RoundingMode.HALF_UP)
            // total = netBeforeTax + tax
            lineTotalMinor = subtotalMinor.add(taxAmountMinor)
        }

        // Step 4: Scale Normalization to 6 decimal places (DECIMAL(20,6))
        return CalculationResult(
            subtotal = MoneyAmount(subtotalMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)),
            netBeforeTax = MoneyAmount(netBeforeTaxMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)),
            taxAmount = MoneyAmount(taxAmountMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)),
            lineTotal = MoneyAmount(lineTotalMinor.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY))
        )
    }
}
