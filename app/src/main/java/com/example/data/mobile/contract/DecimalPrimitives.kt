package com.example.data.mobile.contract

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact-decimal quantity primitive matching DECIMAL(20,6) boundary constraints:
 * - Up to 14 integer digits and up to 6 decimal places (max 99999999999999.999999)
 * - Positive quantities accept trailing zeros (e.g. "0.100000", "1")
 * - Strictly positive instances reject all-zero ("0.000000", "0") and negative quantities on EVERY construction/copy path
 * - Backed by exact captured string lexeme to preserve immutable canonical event digests
 * - Exposes BigDecimal view for arithmetic calculations without silent normalization.
 */
data class PositiveDecimalQuantity(val lexeme: String) : Comparable<PositiveDecimalQuantity> {
    val value: BigDecimal = BigDecimal(lexeme)

    init {
        require(ContractConstants.POSITIVE_QUANTITY_REGEX.matches(lexeme)) {
            "Invalid positive DecimalQuantity: '$lexeme'. Must be strictly positive with up to 14 integer digits and up to 6 decimal places"
        }
        require(value > BigDecimal.ZERO) {
            "PositiveDecimalQuantity must be strictly positive, got '$lexeme'"
        }
        require(value <= MAX_MAGNITUDE) {
            "PositiveDecimalQuantity exceeds maximum magnitude $MAX_MAGNITUDE"
        }
    }

    constructor(bd: BigDecimal) : this(bd.toPlainString())

    fun toPlainString(): String = lexeme

    override fun toString(): String = lexeme

    override fun compareTo(other: PositiveDecimalQuantity): Int = value.compareTo(other.value)

    companion object {
        val MAX_MAGNITUDE: BigDecimal = BigDecimal("99999999999999.999999")

        fun fromPositiveString(raw: String): PositiveDecimalQuantity = PositiveDecimalQuantity(raw)

        fun of(number: Long): PositiveDecimalQuantity {
            require(number > 0) { "Number must be strictly positive, got $number" }
            return PositiveDecimalQuantity(number.toString())
        }
    }
}

/**
 * Typealias preserving contract source compatibility for sales line positive quantities.
 */
typealias DecimalQuantity = PositiveDecimalQuantity

/**
 * Nonnegative exact-decimal quantity primitive (e.g. for inventory available_quantity).
 * Rejects negative quantities on all construction paths; accepts zero.
 */
data class NonnegativeDecimalQuantity(val lexeme: String) : Comparable<NonnegativeDecimalQuantity> {
    val value: BigDecimal = BigDecimal(lexeme)

    init {
        require(ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(lexeme)) {
            "Invalid nonnegative DecimalQuantity: '$lexeme'. Must be >= 0 with up to 14 integer digits and up to 6 decimal places"
        }
        require(value >= BigDecimal.ZERO) {
            "NonnegativeDecimalQuantity must be nonnegative, got '$lexeme'"
        }
        require(value <= MAX_MAGNITUDE) {
            "NonnegativeDecimalQuantity exceeds maximum magnitude $MAX_MAGNITUDE"
        }
    }

    constructor(bd: BigDecimal) : this(bd.toPlainString())

    fun toPlainString(): String = lexeme

    override fun toString(): String = lexeme

    override fun compareTo(other: NonnegativeDecimalQuantity): Int = value.compareTo(other.value)

    companion object {
        val MAX_MAGNITUDE: BigDecimal = BigDecimal("99999999999999.999999")
        val ZERO: NonnegativeDecimalQuantity = NonnegativeDecimalQuantity("0.000000")

        fun fromNonnegativeString(raw: String): NonnegativeDecimalQuantity = NonnegativeDecimalQuantity(raw)

        fun of(number: Long): NonnegativeDecimalQuantity {
            require(number >= 0) { "Number must be nonnegative, got $number" }
            return NonnegativeDecimalQuantity(number.toString())
        }
    }
}

/**
 * Exact-decimal monetary amount primitive matching DECIMAL(20,6) boundary constraints:
 * - Up to 14 integer digits and up to 6 decimal places (max 99999999999999.999999)
 * - Negative amounts are strictly forbidden on every path (constructors and copies)
 * - Preserves exact captured decimal lexemes for canonical serialization
 * - No silent rounding: arbitrary unscaled BigDecimals must not be silently rounded.
 */
data class MoneyAmount(val lexeme: String) : Comparable<MoneyAmount> {
    val value: BigDecimal = BigDecimal(lexeme)

    init {
        require(ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(lexeme)) {
            "Invalid MoneyAmount: '$lexeme'. Must be nonnegative decimal with up to 14 integer digits and up to 6 decimal places"
        }
        require(value >= BigDecimal.ZERO) {
            "MoneyAmount must be nonnegative, got '$lexeme'"
        }
        require(value <= MAX_MAGNITUDE) {
            "MoneyAmount exceeds maximum magnitude $MAX_MAGNITUDE"
        }
    }

    constructor(bd: BigDecimal) : this(bd.toPlainString())

    fun toPlainString(): String = lexeme

    override fun toString(): String = lexeme

    override fun compareTo(other: MoneyAmount): Int = value.compareTo(other.value)

    operator fun plus(other: MoneyAmount): MoneyAmount {
        val sum = value.add(other.value)
        require(sum <= MAX_MAGNITUDE) { "Sum exceeds maximum magnitude $MAX_MAGNITUDE" }
        return MoneyAmount(sum.toPlainString())
    }

    operator fun minus(other: MoneyAmount): MoneyAmount {
        require(value >= other.value) { "Resulting MoneyAmount cannot be negative: $value - ${other.value}" }
        val diff = value.subtract(other.value)
        return MoneyAmount(diff.toPlainString())
    }

    companion object {
        val MAX_MAGNITUDE: BigDecimal = BigDecimal("99999999999999.999999")
        val ZERO: MoneyAmount = MoneyAmount("0.000000")

        fun fromNonnegativeString(raw: String): MoneyAmount = MoneyAmount(raw)

        fun fromPositiveString(raw: String): MoneyAmount {
            require(ContractConstants.POSITIVE_DECIMAL_REGEX.matches(raw)) {
                "Invalid positive MoneyAmount: '$raw'. Must be strictly positive decimal with up to 14 integer digits and up to 6 decimal places"
            }
            val m = MoneyAmount(raw)
            require(m.value > BigDecimal.ZERO) { "MoneyAmount must be strictly positive, got '$raw'" }
            return m
        }

        fun of(number: Long): MoneyAmount {
            require(number >= 0) { "Number must be nonnegative, got $number" }
            return MoneyAmount(number.toString())
        }
    }
}

/**
 * Exact tax percentage between 0 and 100 with up to 6 decimal places (e.g. "16.000000" for 16%, "0.000000" for zero-rated).
 * Conforms to Laravel TaxPercentage.php authority (commit f8a0cf7012c4875bec7320d42e0f8dde58593b52).
 *
 * Preserves exact captured decimal lexemes; rejects values > 100 and negative values on every construction path.
 */
data class TaxPercentage(val lexeme: String) : Comparable<TaxPercentage> {
    val value: BigDecimal = BigDecimal(lexeme)

    init {
        require(ContractConstants.TAX_PERCENTAGE_REGEX.matches(lexeme)) {
            "Invalid TaxPercentage: '$lexeme'. Must be between 0.000000 and 100.000000 with up to 6 decimal places"
        }
        require(value >= BigDecimal.ZERO) { "TaxPercentage cannot be negative, got '$lexeme'" }
        require(value <= MAX_PERCENTAGE) { "TaxPercentage cannot exceed 100%, got '$lexeme'" }
    }

    constructor(bd: BigDecimal) : this(bd.toPlainString())

    /**
     * Converts the tax percentage to a fractional rate: rate = taxPercentage / 100.
     * Scale 8 allows precision during fractional calculations.
     */
    fun toRate(): BigDecimal = value.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)

    fun toPlainString(): String = lexeme

    override fun toString(): String = lexeme

    override fun compareTo(other: TaxPercentage): Int = value.compareTo(other.value)

    companion object {
        val MAX_PERCENTAGE: BigDecimal = BigDecimal("100.000000")
        val ZERO: TaxPercentage = TaxPercentage("0.000000")

        fun fromString(raw: String): TaxPercentage = TaxPercentage(raw)
    }
}
