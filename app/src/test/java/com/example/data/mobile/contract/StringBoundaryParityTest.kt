package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StringBoundaryParityTest {
    @Test fun escapedSupplementaryPairCountsTwoUtf16UnitsAtLimit() {
        val prefix = "a".repeat(ContractConstants.MAX_STRING_LENGTH - 2)
        val decoded = StrictJsonParser.parse("\"" + prefix + "\\uD83D\\uDE00\"").asString()
        assertEquals(ContractConstants.MAX_STRING_LENGTH, decoded.length)
        assertEquals("\"" + decoded + "\"", CanonicalJsonSerializer.serialize(decoded))
    }

    @Test fun supplementaryPairCannotOvershootLimitBeforeClosingQuote() {
        val prefix = "a".repeat(ContractConstants.MAX_STRING_LENGTH - 1)
        for (suffix in listOf("\\uD83D\\uDE00", "\uD83D\uDE00")) {
            assertThrows(CanonicalSerializationException::class.java) {
                StrictJsonParser.parse("\"" + prefix + suffix + "\"")
            }
        }
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(prefix + "\uD83D\uDE00")
        }
    }

    @Test fun literalAndEscapedBmpStringsRespectExactBoundary() {
        val maximum = "a".repeat(ContractConstants.MAX_STRING_LENGTH)
        assertEquals(maximum, StrictJsonParser.parse("\"$maximum\"").asString())
        assertEquals(maximum, StrictJsonParser.parse("\"" + "\\u0061".repeat(maximum.length) + "\"").asString())
        assertThrows(CanonicalSerializationException::class.java) {
            StrictJsonParser.parse("\"" + maximum + "\\u0061\"")
        }
    }

    @Test fun unescapedControlsAndDecimalNewlinesAreRejected() {
        assertThrows(CanonicalSerializationException::class.java) { StrictJsonParser.parse("\"raw\nline\"") }
        assertEquals("raw\nline", StrictJsonParser.parse("\"raw\\nline\"").asString())
        assertThrows(IllegalArgumentException::class.java) { MoneyAmount("1.00\n") }
        assertThrows(IllegalArgumentException::class.java) { PositiveDecimalQuantity("1\n") }
    }
}
