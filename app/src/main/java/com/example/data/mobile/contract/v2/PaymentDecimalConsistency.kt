package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import java.math.BigDecimal

/**
 * Payment and settlement qualification verifying decimal consistency and economic boundary rules
 * conforming to Small Trader Contract Revision 3 (API v2 / event_version 2).
 *
 * Enforces:
 * 1. Positive financial grand total ONLY policy: total <= 0 is rejected for all paid, unpaid, and offline sales.
 * 2. Exact currency minor units representation without implicit rounding (RoundingMode.UNNECESSARY parity).
 * 3. Exact tender conservation for offline cash: amountTendered == amountSettled + changeReturned,
 *    settled == grand_total (strictly positive).
 * 4. Online payment intent modes:
 *    - "unpaid": amount == 0 (grand_total > 0)
 *    - "partial": 0 < amount < grand_total
 *    - "full": amount == grand_total (strictly positive)
 * 5. Fails closed (returns false) on malformed lexemes, NaN, Infinity, exponent notation, negative amounts,
 *    and overprecision without throwing unexpected exceptions.
 */
object PaymentDecimalConsistency {

    private val DECIMAL_MONEY_REGEX = Regex("^(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?\$")

    /**
     * Parses and validates a monetary string lexeme against the currency minor units scale:
     * - Must be a string lexeme matching DECIMAL_MONEY_REGEX.
     * - Must represent an integer number of currency minor units (cmu in 0..6) without rounding.
     *   Trailing zeros (e.g. "50.000000" at cmu 2) are accepted; fractions beyond cmu (e.g. "0.000001" at cmu 2) are rejected.
     * Returns BigDecimal if valid, or null if invalid (fail closed).
     */
    fun parseAndValidateMonetaryAmount(raw: String?, currencyMinorUnits: Int): BigDecimal? {
        if (raw == null) return null
        if (!DECIMAL_MONEY_REGEX.matches(raw)) return null
        if (currencyMinorUnits !in 0..6) return null

        val bd: BigDecimal
        try {
            bd = BigDecimal(raw)
        } catch (_: Exception) {
            return null
        }

        if (bd < BigDecimal.ZERO) return null

        // Check if value represents an integer count of minor units (cents = bd * 10^cmu)
        val multiplier = BigDecimal.TEN.pow(currencyMinorUnits)
        val cents = bd.multiply(multiplier)
        if (cents.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            return null
        }
        return bd
    }

    /**
     * Verifies payment consistency against grand total and currency minor units.
     * Fails closed (returns false) if currency minor units is missing or out of range,
     * grand total <= 0, or payment parameters violate contract invariants.
     */
    fun verifyPaymentConsistency(
        payment: StrictJsonObject,
        grandTotalStr: String,
        currencyMinorUnits: Int?
    ): Boolean {
        if (currencyMinorUnits == null || currencyMinorUnits !in 0..6) {
            return false
        }

        val gt = parseAndValidateMonetaryAmount(grandTotalStr, currencyMinorUnits) ?: return false
        if (gt <= BigDecimal.ZERO) {
            return false
        }

        // 1. Offline payment branch
        if ("amount_tendered" in payment.properties || "amount_settled" in payment.properties) {
            if ("amount_tendered" !in payment.properties ||
                "amount_settled" !in payment.properties ||
                "change_returned" !in payment.properties) {
                return false
            }

            val tenderedStr = (payment["amount_tendered"] as? StrictJsonString)?.value ?: return false
            val settledStr = (payment["amount_settled"] as? StrictJsonString)?.value ?: return false
            val changeStr = (payment["change_returned"] as? StrictJsonString)?.value ?: return false

            val tendered = parseAndValidateMonetaryAmount(tenderedStr, currencyMinorUnits) ?: return false
            val settled = parseAndValidateMonetaryAmount(settledStr, currencyMinorUnits) ?: return false
            val change = parseAndValidateMonetaryAmount(changeStr, currencyMinorUnits) ?: return false

            val modeStr = (payment["mode"] as? StrictJsonString)?.value
            if (modeStr != "full") return false

            if (settled <= BigDecimal.ZERO) return false
            if (settled.compareTo(gt) != 0) return false

            val expectedTender = settled.add(change)
            return tendered.compareTo(expectedTender) == 0
        }

        // 2. Online payment intent branch
        if ("settlement_mode" in payment.properties) {
            val modeStr = (payment["settlement_mode"] as? StrictJsonString)?.value ?: return false
            val amtStr = (payment["amount"] as? StrictJsonString)?.value ?: return false

            val amt = parseAndValidateMonetaryAmount(amtStr, currencyMinorUnits) ?: return false

            return when (modeStr) {
                "unpaid" -> amt.compareTo(BigDecimal.ZERO) == 0
                "partial" -> amt > BigDecimal.ZERO && amt < gt
                "full" -> amt.compareTo(gt) == 0
                else -> false
            }
        }

        return false
    }

    /**
     * Offline cash capture adaptation illustration (conforming to Python Check 35a):
     * Adapts raw offline cash tender (e.g. tendered 60, settled 50, change 10) to a canonical
     * receipt of the net retained settled amount (50.000000), while preserving audit trail
     * fields separately without leaking them into receipt command.
     */
    data class AdaptedOfflineReceipt(
        val receiptAmount: String,
        val amountTendered: String,
        val changeReturned: String,
        val auditTrail: Map<String, String>
    )

    fun adaptOfflineCashToCanonicalReceipt(
        amountTenderedStr: String,
        amountSettledStr: String,
        changeReturnedStr: String,
        grandTotalStr: String,
        currencyMinorUnits: Int
    ): AdaptedOfflineReceipt {
        require(currencyMinorUnits in 0..6) { "currencyMinorUnits must be in 0..6" }
        val tendered = parseAndValidateMonetaryAmount(amountTenderedStr, currencyMinorUnits)
            ?: throw IllegalArgumentException("Invalid amount_tendered: '$amountTenderedStr'")
        val settled = parseAndValidateMonetaryAmount(amountSettledStr, currencyMinorUnits)
            ?: throw IllegalArgumentException("Invalid amount_settled: '$amountSettledStr'")
        val change = parseAndValidateMonetaryAmount(changeReturnedStr, currencyMinorUnits)
            ?: throw IllegalArgumentException("Invalid change_returned: '$changeReturnedStr'")
        val gt = parseAndValidateMonetaryAmount(grandTotalStr, currencyMinorUnits)
            ?: throw IllegalArgumentException("Invalid grand_total: '$grandTotalStr'")

        require(gt > BigDecimal.ZERO) { "grand_total must be strictly positive" }
        require(settled.compareTo(gt) == 0) { "settled ($amountSettledStr) must equal grand_total ($grandTotalStr)" }
        require(tendered.compareTo(settled.add(change)) == 0) { "tendered must equal settled + change" }

        return AdaptedOfflineReceipt(
            receiptAmount = amountSettledStr,
            amountTendered = amountTenderedStr,
            changeReturned = changeReturnedStr,
            auditTrail = mapOf(
                "amount_tendered" to amountTenderedStr,
                "amount_settled" to amountSettledStr,
                "change_returned" to changeReturnedStr
            )
        )
    }
}
