package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryCompatibilityTest {
    @Test fun timestampsValidateCalendarFieldsWithoutJavaTime() {
        validateCapturedAt("2024-02-29T23:59:59.123456789+02:30")
        validateCapturedAt("2026-11-11T00:00:00Z")
        for (invalid in listOf("2025-02-29T00:00:00Z", "2026-13-01T00:00:00Z",
            "2026-11-11T24:00:00Z", "2026-11-11T00:00:00+24:00", "2026-11-11")) {
            assertThrows(IllegalArgumentException::class.java) { validateCapturedAt(invalid) }
        }
    }

    @Test fun payloadLimitCountsUtf8Bytes() {
        val oversized = "[" + List(9) { "\"" + "ع".repeat(60_000) + "\"" }.joinToString(",") + "]"
        assertTrue(oversized.length < ContractConstants.MAX_PAYLOAD_BYTES)
        val error = assertThrows(CanonicalSerializationException::class.java) {
            StrictJsonParser.parse(oversized)
        }
        assertTrue(error.message.orEmpty().contains("Payload size"))
    }

    @Test fun threeMinorCurrencyRetainsThirdDecimal() {
        val result = SalesLineCalculator.calculate(
            DecimalQuantity("1"), MoneyAmount("2.505"), TaxPercentage("0"), false, 3
        )
        assertEquals("2.505000", result.lineTotal.toPlainString())
    }
}
