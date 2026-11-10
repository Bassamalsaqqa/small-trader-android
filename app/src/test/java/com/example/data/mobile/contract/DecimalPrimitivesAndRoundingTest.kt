package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

class DecimalPrimitivesAndRoundingTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testRegressionDecimalEdgeCasesFromContractFixture() {
        val fixturesDir = locateFixturesDir()
        val regFile = File(fixturesDir, "regression-decimal-edge-cases.json")
        assertTrue("Regression fixture file must exist", regFile.exists())

        val content = regFile.readText(Charsets.UTF_8)
        val root = StrictJsonParser.parse(content).asObject()
        val testCases = root.require("test_cases").asArray()

        for (i in 0 until testCases.size) {
            val tc = testCases[i].asObject()
            val name = tc.require("name").asString()

            when (name) {
                "sales_line_calculator_exclusive_16_percent" -> {
                    val q = DecimalQuantity.fromPositiveString(tc.require("quantity").asString())
                    val p = MoneyAmount.fromNonnegativeString(tc.require("unit_price").asString())
                    val taxRate = TaxPercentage.fromString(tc.require("tax_rate").asString())
                    val taxInclusive = tc.require("tax_inclusive").let { (it as StrictJsonBoolean).value }

                    val res = SalesLineCalculator.calculate(
                        quantity = q,
                        unitPrice = p,
                        taxPercentage = taxRate,
                        taxInclusive = taxInclusive,
                        minorUnits = 2
                    )

                    assertEquals("Subtotal mismatch in $name", tc.require("subtotal").asString(), res.subtotal.toPlainString())
                    assertEquals("Tax amount mismatch in $name", tc.require("tax_amount").asString(), res.taxAmount.toPlainString())
                    assertEquals("Line total mismatch in $name", tc.require("line_total").asString(), res.lineTotal.toPlainString())
                }

                "sales_line_calculator_inclusive_16_percent" -> {
                    val q = DecimalQuantity.fromPositiveString(tc.require("quantity").asString())
                    val p = MoneyAmount.fromNonnegativeString(tc.require("unit_price").asString())
                    val taxRate = TaxPercentage.fromString(tc.require("tax_rate").asString())
                    val taxInclusive = tc.require("tax_inclusive").let { (it as StrictJsonBoolean).value }

                    val res = SalesLineCalculator.calculate(
                        quantity = q,
                        unitPrice = p,
                        taxPercentage = taxRate,
                        taxInclusive = taxInclusive,
                        minorUnits = 2
                    )

                    assertEquals("Subtotal mismatch in $name", tc.require("subtotal").asString(), res.subtotal.toPlainString())
                    assertEquals("Net mismatch in $name", tc.require("net_before_tax").asString(), res.netBeforeTax.toPlainString())
                    assertEquals("Tax amount mismatch in $name", tc.require("tax_amount").asString(), res.taxAmount.toPlainString())
                    assertEquals("Line total mismatch in $name", tc.require("line_total").asString(), res.lineTotal.toPlainString())
                }

                "trailing_zero_quantity_support" -> {
                    val qStr = tc.require("quantity").asString()
                    val q = DecimalQuantity.fromPositiveString(qStr)
                    assertEquals("0.100000", q.toPlainString())

                    val p = MoneyAmount.fromNonnegativeString(tc.require("unit_price").asString())
                    val taxRate = TaxPercentage.fromString(tc.require("tax_rate").asString())
                    val taxInclusive = tc.require("tax_inclusive").let { (it as StrictJsonBoolean).value }

                    val res = SalesLineCalculator.calculate(
                        quantity = q,
                        unitPrice = p,
                        taxPercentage = taxRate,
                        taxInclusive = taxInclusive,
                        minorUnits = 2
                    )

                    assertEquals("Subtotal mismatch in $name", tc.require("subtotal").asString(), res.subtotal.toPlainString())
                    assertEquals("Tax amount mismatch in $name", tc.require("tax_amount").asString(), res.taxAmount.toPlainString())
                    assertEquals("Line total mismatch in $name", tc.require("line_total").asString(), res.lineTotal.toPlainString())
                }

                "half_up_vs_half_even_parity" -> {
                    val amountStr = tc.require("amount").asString()
                    val expectedHalfUp = tc.require("expected_half_up").asString()
                    val bankersDivergence = tc.require("bankers_half_even_divergence").asString()

                    val bd = BigDecimal(amountStr)
                    val halfUp = bd.setScale(2, RoundingMode.HALF_UP).setScale(6).toPlainString()
                    val halfEven = bd.setScale(2, RoundingMode.HALF_EVEN).setScale(6).toPlainString()

                    assertEquals("HALF_UP must match Laravel policy", expectedHalfUp, halfUp)
                    assertEquals("HALF_EVEN divergence must match fixture", bankersDivergence, halfEven)
                    assertNotEquals("Critical divergence: HALF_UP must differ from HALF_EVEN", halfUp, halfEven)
                }

                "exact_three_cent_sum" -> {
                    val line1 = MoneyAmount.fromNonnegativeString(tc.require("line_1").asString())
                    val line2 = MoneyAmount.fromNonnegativeString(tc.require("line_2").asString())
                    val sum = line1 + line2

                    assertEquals("Exact sum must match without float drift", tc.require("exact_sum").asString(), sum.toPlainString())
                    assertNotEquals(
                        "Must not suffer from binary float drift",
                        tc.require("float_corrupted_sum").asString(),
                        sum.toPlainString()
                    )
                }

                "max_magnitude_boundaries" -> {
                    val maxQty = tc.require("max_quantity").asString()
                    val maxMoney = tc.require("max_money").asString()

                    val q = DecimalQuantity.fromPositiveString(maxQty)
                    val m = MoneyAmount.fromPositiveString(maxMoney)

                    assertEquals("99999999999999.999999", q.toPlainString())
                    assertEquals("99999999999999.999999", m.toPlainString())
                }
            }
        }
    }

    @Test
    fun testStrictlyPositiveQuantityRejectsZeroAndNegative() {
        // Zero quantity rejected
        assertThrows(IllegalArgumentException::class.java) {
            DecimalQuantity.fromPositiveString("0.000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DecimalQuantity.fromPositiveString("0")
        }

        // Negative quantity rejected
        assertThrows(IllegalArgumentException::class.java) {
            DecimalQuantity.fromPositiveString("-1.000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DecimalQuantity.fromPositiveString("-0.000001")
        }
    }

    @Test
    fun testConstructorAndCopyEnforceInvariantsOnAllPaths() {
        // PositiveDecimalQuantity primary constructor & copy
        assertThrows(IllegalArgumentException::class.java) {
            PositiveDecimalQuantity("-1.000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositiveDecimalQuantity("0.000000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositiveDecimalQuantity(BigDecimal("-5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PositiveDecimalQuantity(BigDecimal.ZERO)
        }
        val validQty = PositiveDecimalQuantity("1.500000")
        assertThrows(IllegalArgumentException::class.java) {
            validQty.copy(lexeme = "-2.000000")
        }

        // NonnegativeDecimalQuantity accepts zero, rejects negative
        val zeroQty = NonnegativeDecimalQuantity("0.000000")
        assertEquals("0.000000", zeroQty.toPlainString())
        assertEquals(0, zeroQty.value.compareTo(BigDecimal.ZERO))
        val plainZero = NonnegativeDecimalQuantity("0")
        assertEquals("0", plainZero.toPlainString())
        assertEquals(0, plainZero.value.compareTo(BigDecimal.ZERO))
        assertThrows(IllegalArgumentException::class.java) {
            NonnegativeDecimalQuantity("-0.000001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NonnegativeDecimalQuantity(BigDecimal("-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            zeroQty.copy(lexeme = "-1.000000")
        }

        // MoneyAmount primary constructor & copy
        assertThrows(IllegalArgumentException::class.java) {
            MoneyAmount("-0.010000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoneyAmount(BigDecimal("-10"))
        }
        val validMoney = MoneyAmount("10.000000")
        assertThrows(IllegalArgumentException::class.java) {
            validMoney.copy(lexeme = "-5.000000")
        }
    }

    @Test
    fun testRejectsOverScaleDecimals() {
        // 7 decimals rejected (> 6 scale)
        assertThrows(IllegalArgumentException::class.java) {
            DecimalQuantity.fromPositiveString("0.1000001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoneyAmount.fromNonnegativeString("10.1234567")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaxPercentage.fromString("16.0000001")
        }
    }

    @Test
    fun testRejectsOverMagnitude() {
        // 15 integer digits (> 14 allowed)
        val overMagnitude = "100000000000000.000000"
        assertThrows(IllegalArgumentException::class.java) {
            DecimalQuantity.fromPositiveString(overMagnitude)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MoneyAmount.fromNonnegativeString(overMagnitude)
        }
    }

    @Test
    fun testTaxPercentageBounds0To100() {
        // Valid boundaries preserve exact lexeme
        assertEquals("0.000000", TaxPercentage.fromString("0.000000").toPlainString())
        assertEquals("0", TaxPercentage.fromString("0").toPlainString())
        assertEquals("16.000000", TaxPercentage.fromString("16.000000").toPlainString())
        assertEquals("100.000000", TaxPercentage.fromString("100.000000").toPlainString())
        assertEquals("100", TaxPercentage.fromString("100").toPlainString())

        // Over 100 rejected
        assertThrows(IllegalArgumentException::class.java) {
            TaxPercentage.fromString("100.000001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaxPercentage.fromString("101.000000")
        }

        // Negative rejected
        assertThrows(IllegalArgumentException::class.java) {
            TaxPercentage.fromString("-0.000001")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaxPercentage(BigDecimal("-1"))
        }
    }

    @Test
    fun testSalesLineCalculatorMinorUnitsValidation() {
        val q = DecimalQuantity.fromPositiveString("1.000000")
        val p = MoneyAmount.fromNonnegativeString("10.000000")
        val tax = TaxPercentage.fromString("16.000000")

        // Valid minor units 0..6
        for (mu in 0..6) {
            val res = SalesLineCalculator.calculate(
                quantity = q,
                unitPrice = p,
                taxPercentage = tax,
                taxInclusive = false,
                minorUnits = mu
            )
            assertTrue(res.lineTotal.value > BigDecimal.ZERO)
        }

        // minorUnits < 0 rejected
        assertThrows(IllegalArgumentException::class.java) {
            SalesLineCalculator.calculate(
                quantity = q,
                unitPrice = p,
                taxPercentage = tax,
                taxInclusive = false,
                minorUnits = -1
            )
        }

        // minorUnits > 6 rejected
        assertThrows(IllegalArgumentException::class.java) {
            SalesLineCalculator.calculate(
                quantity = q,
                unitPrice = p,
                taxPercentage = tax,
                taxInclusive = false,
                minorUnits = 7
            )
        }
    }
}
