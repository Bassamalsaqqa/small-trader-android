package com.example.data.mobile.contract

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact-decimal quantity primitive matching DECIMAL(20,6) boundary constraints:
 * - Up to 14 integer digits and up to 6 decimal places (max 99999999999999.999999)
 * - Positive quantities accept trailing zeros (e.g. "0.100000")
 * - Strictly positive instances reject all-zero ("0.000000") and negative quantities
 * - Nonnegative instances accept zero
 * - Never uses Double/Float; backed by arbitrary-precision BigDecimal normalized to 6 decimal places.
 */
data class DecimalQuantity(val value: BigDecimal) : Comparable<DecimalQuantity> {
    init {
        require(value.scale() <= ContractConstants.DECIMAL_SCALE) {
            "DecimalQuantity scale ${value.scale()} exceeds maximum allowed scale ${ContractConstants.DECIMAL_SCALE}"
        }
        val integerDigits = value.precision() - value.scale()
        require(integerDigits <= ContractConstants.MAX_INTEGER_DIGITS) {
            "DecimalQuantity integer digits $integerDigits exceeds maximum allowed ${ContractConstants.MAX_INTEGER_DIGITS}"
        }
    }

    fun toPlainString(): String = value.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString()

    override fun toString(): String = toPlainString()

    override fun compareTo(other: DecimalQuantity): Int = value.compareTo(other.value)

    companion object {
        val MAX_MAGNITUDE: BigDecimal = BigDecimal("99999999999999.999999")

        fun fromPositiveString(raw: String): DecimalQuantity {
            require(ContractConstants.POSITIVE_QUANTITY_REGEX.matches(raw)) {
                "Invalid positive DecimalQuantity: '$raw'. Must be strictly positive with up to 14 integer digits and up to 6 decimal places"
            }
            val parsed = BigDecimal(raw)
            require(parsed > BigDecimal.ZERO) {
                "DecimalQuantity must be strictly positive, got '$raw'"
            }
            require(parsed <= MAX_MAGNITUDE) {
                "DecimalQuantity exceeds maximum magnitude $MAX_MAGNITUDE"
            }
            return DecimalQuantity(parsed.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY))
        }

        fun fromNonnegativeString(raw: String): DecimalQuantity {
            require(ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(raw)) {
                "Invalid nonnegative DecimalQuantity: '$raw'. Must be >= 0 with up to 14 integer digits and up to 6 decimal places"
            }
            val parsed = BigDecimal(raw)
            require(parsed >= BigDecimal.ZERO) {
                "DecimalQuantity must be nonnegative, got '$raw'"
            }
            require(parsed <= MAX_MAGNITUDE) {
                "DecimalQuantity exceeds maximum magnitude $MAX_MAGNITUDE"
            }
            return DecimalQuantity(parsed.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY))
        }

        fun of(number: Long): DecimalQuantity {
            require(number > 0) { "Number must be strictly positive" }
            return DecimalQuantity(BigDecimal.valueOf(number).setScale(ContractConstants.DECIMAL_SCALE))
        }
    }
}

/**
 * Exact-decimal monetary amount primitive matching DECIMAL(20,6) boundary constraints:
 * - Up to 14 integer digits and up to 6 decimal places (max 99999999999999.999999)
 * - Negative amounts are strictly forbidden
 * - Never uses Double/Float; backed by arbitrary-precision BigDecimal normalized to 6 decimal places.
 */
data class MoneyAmount(val value: BigDecimal) : Comparable<MoneyAmount> {
    init {
        require(value.scale() <= ContractConstants.DECIMAL_SCALE) {
            "MoneyAmount scale ${value.scale()} exceeds maximum allowed scale ${ContractConstants.DECIMAL_SCALE}"
        }
        require(value >= BigDecimal.ZERO) {
            "MoneyAmount must be nonnegative, got $value"
        }
        val integerDigits = value.precision() - value.scale()
        require(integerDigits <= ContractConstants.MAX_INTEGER_DIGITS) {
            "MoneyAmount integer digits $integerDigits exceeds maximum allowed ${ContractConstants.MAX_INTEGER_DIGITS}"
        }
    }

    fun toPlainString(): String = value.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString()

    override fun toString(): String = toPlainString()

    override fun compareTo(other: MoneyAmount): Int = value.compareTo(other.value)

    operator fun plus(other: MoneyAmount): MoneyAmount {
        val sum = value.add(other.value).setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)
        require(sum <= MAX_MAGNITUDE) { "Sum exceeds maximum magnitude $MAX_MAGNITUDE" }
        return MoneyAmount(sum)
    }

    operator fun minus(other: MoneyAmount): MoneyAmount {
        require(value >= other.value) { "Resulting MoneyAmount cannot be negative: $value - ${other.value}" }
        val diff = value.subtract(other.value).setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY)
        return MoneyAmount(diff)
    }

    companion object {
        val MAX_MAGNITUDE: BigDecimal = BigDecimal("99999999999999.999999")
        val ZERO: MoneyAmount = MoneyAmount(BigDecimal.ZERO.setScale(ContractConstants.DECIMAL_SCALE))

        fun fromNonnegativeString(raw: String): MoneyAmount {
            require(ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(raw)) {
                "Invalid MoneyAmount: '$raw'. Must be nonnegative decimal with up to 14 integer digits and up to 6 decimal places"
            }
            val parsed = BigDecimal(raw)
            require(parsed >= BigDecimal.ZERO) { "MoneyAmount must be >= 0, got '$raw'" }
            require(parsed <= MAX_MAGNITUDE) { "MoneyAmount exceeds maximum magnitude $MAX_MAGNITUDE" }
            return MoneyAmount(parsed.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY))
        }

        fun fromPositiveString(raw: String): MoneyAmount {
            require(ContractConstants.POSITIVE_DECIMAL_REGEX.matches(raw)) {
                "Invalid positive MoneyAmount: '$raw'. Must be strictly positive decimal with up to 14 integer digits and up to 6 decimal places"
            }
            val parsed = BigDecimal(raw)
            require(parsed > BigDecimal.ZERO) { "MoneyAmount must be strictly positive, got '$raw'" }
            require(parsed <= MAX_MAGNITUDE) { "MoneyAmount exceeds maximum magnitude $MAX_MAGNITUDE" }
            return MoneyAmount(parsed.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY))
        }

        fun fromScaledBigDecimal(bd: BigDecimal): MoneyAmount {
            val scaled = bd.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.HALF_UP)
            return MoneyAmount(scaled)
        }
    }
}

/**
 * Exact tax percentage between 0 and 100 with up to 6 decimal places (e.g. "16.000000" for 16%, "0.000000" for zero-rated).
 * Conforms to Laravel TaxPercentage.php authority (commit f8a0cf7012c4875bec7320d42e0f8dde58593b52).
 *
 * Rejects values > 100 (e.g. "100.000001"), negative values, and over-scale fractions.
 */
data class TaxPercentage(val value: BigDecimal) : Comparable<TaxPercentage> {
    init {
        require(value >= BigDecimal.ZERO) { "TaxPercentage cannot be negative, got $value" }
        require(value <= BigDecimal("100.000000")) { "TaxPercentage cannot exceed 100%, got $value" }
        require(value.scale() <= ContractConstants.DECIMAL_SCALE) {
            "TaxPercentage scale ${value.scale()} exceeds maximum allowed ${ContractConstants.DECIMAL_SCALE}"
        }
    }

    /**
     * Converts the tax percentage to a fractional rate: rate = taxPercentage / 100.
     * Scale 8 allows precision during fractional calculations.
     */
    fun toRate(): BigDecimal = value.divide(BigDecimal(100), 8, RoundingMode.HALF_UP)

    fun toPlainString(): String = value.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY).toPlainString()

    override fun toString(): String = toPlainString()

    override fun compareTo(other: TaxPercentage): Int = value.compareTo(other.value)

    companion object {
        val MAX_PERCENTAGE: BigDecimal = BigDecimal("100.000000")
        val ZERO: TaxPercentage = TaxPercentage(BigDecimal.ZERO.setScale(ContractConstants.DECIMAL_SCALE))

        fun fromString(raw: String): TaxPercentage {
            require(ContractConstants.TAX_PERCENTAGE_REGEX.matches(raw)) {
                "Invalid TaxPercentage: '$raw'. Must be between 0.000000 and 100.000000 with up to 6 decimal places"
            }
            val parsed = BigDecimal(raw)
            require(parsed >= BigDecimal.ZERO && parsed <= MAX_PERCENTAGE) {
                "TaxPercentage must be within 0..100, got '$raw'"
            }
            return TaxPercentage(parsed.setScale(ContractConstants.DECIMAL_SCALE, RoundingMode.UNNECESSARY))
        }
    }
}
