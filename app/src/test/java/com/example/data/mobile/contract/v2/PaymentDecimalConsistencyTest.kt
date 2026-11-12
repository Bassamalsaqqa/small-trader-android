package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Payment and settlement qualification tests conforming to Python validate-mobile-contract.py
 * Checks 33, 34, and 35.
 */
class PaymentDecimalConsistencyTest {

    private fun onlinePayment(mode: String, amount: String): StrictJsonObject = StrictJsonObject(
        mapOf(
            "settlement_mode" to StrictJsonString(mode),
            "amount" to StrictJsonString(amount)
        )
    )

    private fun offlinePayment(
        tendered: String,
        settled: String,
        change: String,
        mode: String = "full"
    ): StrictJsonObject = StrictJsonObject(
        mapOf(
            "mode" to StrictJsonString(mode),
            "amount_tendered" to StrictJsonString(tendered),
            "amount_settled" to StrictJsonString(settled),
            "change_returned" to StrictJsonString(change)
        )
    )

    @Test
    fun testCheck33aUnpaidWithPositiveAmountRejected() {
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("unpaid", "0.000001"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("unpaid", "50.000000"), "50.000000", 2))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("unpaid", "0.000000"), "50.000000", 2))
    }

    @Test
    fun testCheck33bPartialWithZeroAmountRejected() {
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "0.000000"), "50.000000", 2))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "20.000000"), "50.000000", 2))
    }

    @Test
    fun testCheck33cPartialWithAmountGreaterThanOrEqualToGrandTotalRejected() {
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "50.000000"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "55.000000"), "50.000000", 2))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "49.990000"), "50.000000", 2))
    }

    @Test
    fun testCheck33dFullModeWithAmountNotEqualToGrandTotalRejected() {
        // Underpayment
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "49.990000"), "50.000000", 2))
        // Overpayment
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.010000"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "60.000000"), "50.000000", 2))
        // Exact
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), "50.000000", 2))
    }

    @Test
    fun testCheck33eNegativePaymentAmountRejected() {
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "-50.000000"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("unpaid", "-0.000001"), "50.000000", 2))
    }

    @Test
    fun testCheck33fInvalidDecimalStringLexemesRejected() {
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "not_a_number"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.00.00"), "50.000000", 2))
    }

    @Test
    fun testCheck33gOfflinePaymentTenderBalanceMismatchRejected() {
        // tendered (50) != settled (50) + change (5)
        val badTender = offlinePayment(tendered = "50.000000", settled = "50.000000", change = "5.000000")
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(badTender, "50.000000", 2))

        // Valid tender: 60 == 50 + 10
        val validTender = offlinePayment(tendered = "60.000000", settled = "50.000000", change = "10.000000")
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(validTender, "50.000000", 2))
    }

    @Test
    fun testCheck33hOfflinePaymentSettledNotEqualToGrandTotalRejected() {
        // settled (40) != grand total (50)
        val badSettled = offlinePayment(tendered = "40.000000", settled = "40.000000", change = "0.000000")
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(badSettled, "50.000000", 2))
    }

    @Test
    fun testCheck33iZeroOrNegativeGrandTotalRejected() {
        // Full with zero grand total rejected
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "0.000000"), "0.000000", 2))
        // Unpaid with zero grand total rejected (first-release positive financial grand total only policy)
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("unpaid", "0.000000"), "0.000000", 2))
        // Offline with zero grand total rejected
        val zeroOffline = offlinePayment(tendered = "0.000000", settled = "0.000000", change = "0.000000")
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(zeroOffline, "0.000000", 2))
        // Negative grand total rejected
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "-50.000000"), "-50.000000", 2))
    }

    @Test
    fun testCheck33jCurrencyMinorUnitsScaleValidation() {
        // Out of range minor units (<0 or >6)
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), "50.000000", -1))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), "50.000000", 7))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), "50.000000", null))
    }

    @Test
    fun testCheck33kMinimumPositiveAmountAllowedAtCurrencyMinorUnitsScale() {
        // 0.010000 at cmu 2
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "0.010000"), "50.000000", 2))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "0.010000"), "0.010000", 2))

        // 0.001000 at cmu 3 (JOD)
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "0.001000"), "50.000000", 3))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "0.001000"), "0.001000", 3))

        // 0.000001 at cmu 6
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "0.000001"), "50.000000", 6))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "0.000001"), "0.000001", 6))
    }

    @Test
    fun testCheck33lOverprecisionBeyondCurrencyMinorUnitsRejectedWithoutRounding() {
        // 0.000001 beyond cmu 2 (ILS) rejected
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "0.000001"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "10.005000"), "50.000000", 2))
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.005000"), "50.005000", 2))

        // 10.000500 beyond cmu 3 (JOD) rejected
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "10.000500"), "50.000000", 3))

        // Offline overprecision
        val badOverprecisionOffline = offlinePayment(tendered = "60.000001", settled = "50.000000", change = "10.000001")
        assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(badOverprecisionOffline, "50.000000", 2))
    }

    @Test
    fun testCheck33mTrailingZerosRepresentingIntegerCurrencyMinorUnitsAccepted() {
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), "50.000000", 2))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("partial", "20.500000"), "50.000000", 2))
        assertTrue(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), "50.000000", 3))
    }

    @Test
    fun testCheck33nMalformedNaNInfinityExponentsFailClosed() {
        for (badLexeme in listOf("NaN", "-NaN", "Infinity", "-Infinity", "+50.00", "1e5", "1.5E2")) {
            assertNull(PaymentDecimalConsistency.parseAndValidateMonetaryAmount(badLexeme, 2))
            assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", badLexeme), "50.000000", 2))
            assertFalse(PaymentDecimalConsistency.verifyPaymentConsistency(onlinePayment("full", "50.000000"), badLexeme, 2))
        }
    }

    @Test
    fun testCheck35aOfflineCashCaptureAdaptationToNetRetainedSettled() {
        val adapted = PaymentDecimalConsistency.adaptOfflineCashToCanonicalReceipt(
            amountTenderedStr = "60.000000",
            amountSettledStr = "50.000000",
            changeReturnedStr = "10.000000",
            grandTotalStr = "50.000000",
            currencyMinorUnits = 2
        )
        // Receipt amount reflects net retained settled amount (50.000000), not tendered (60.000000)
        assertEquals("50.000000", adapted.receiptAmount)
        assertEquals("60.000000", adapted.amountTendered)
        assertEquals("10.000000", adapted.changeReturned)
        assertEquals("60.000000", adapted.auditTrail["amount_tendered"])
        assertEquals("10.000000", adapted.auditTrail["change_returned"])
    }
}
