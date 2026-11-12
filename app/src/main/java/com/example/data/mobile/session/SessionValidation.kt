package com.example.data.mobile.session

import com.example.data.mobile.contract.CanonicalSerializationException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Strict validation and RFC 3339 timestamp helpers for secure session management.
 * Compatible with Android API 24+ without requiring core library desugaring or API 26+ java.time.
 */
internal object SessionValidation {

    // Capture groups:
    // 1: Year (4 digits)
    // 2: Month (2 digits)
    // 3: Day (2 digits)
    // 4: Hour (2 digits)
    // 5: Minute (2 digits)
    // 6: Second (2 digits)
    // 7: Fraction (1..9 digits, optional)
    // 8: Sign (+ or -, optional if Z)
    // 9: Offset hours (2 digits, optional if Z)
    // 10: Offset minutes (2 digits, optional if Z)
    private val RFC3339_REGEX = Regex(
        """\A(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.([0-9]{1,9}))?(?:[Zz]|([+-])(\d{2}):(\d{2}))\z"""
    )

    private val EMAIL_REGEX = Regex(
        "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$"
    )

    fun validateDateTime(value: String, fieldName: String) {
        parseRfc3339ToEpochMs(value, fieldName)
    }

    fun parseRfc3339ToEpochMs(value: String, fieldName: String = "timestamp"): Long {
        val match = RFC3339_REGEX.matchEntire(value)
            ?: throw CanonicalSerializationException("Invalid RFC 3339 timestamp format in $fieldName")
        val groups = match.groupValues
        val year = groups[1].toInt()
        if (year !in 2020..2100) {
            throw CanonicalSerializationException("Year in $fieldName is outside supported bounds [2020..2100]")
        }

        val month = groups[2].toInt()
        val day = groups[3].toInt()
        val hour = groups[4].toInt()
        val minute = groups[5].toInt()
        val second = groups[6].toInt()

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            throw CanonicalSerializationException("Invalid calendar date in $fieldName")
        }

        var offsetMinutes = 0
        if (groups[8].isNotEmpty()) {
            val sign = if (groups[8] == "-") -1 else 1
            val hourOffset = groups[9].toInt()
            val minOffset = groups[10].toInt()
            if (hourOffset > 23 || minOffset > 59) {
                throw CanonicalSerializationException("Invalid timestamp timezone offset in $fieldName")
            }
            offsetMinutes = sign * (hourOffset * 60 + minOffset)
        }

        val fractionMs = if (groups[7].isNotEmpty()) {
            val nanoStr = groups[7].padEnd(9, '0').take(3)
            nanoStr.toInt()
        } else {
            0
        }

        try {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(year, month - 1, day, hour, minute, second)
            calendar.set(Calendar.MILLISECOND, 0)
            val localMillis = calendar.timeInMillis
            return localMillis - (offsetMinutes * 60_000L) + fractionMs
        } catch (e: Exception) {
            throw CanonicalSerializationException("Invalid calendar date in $fieldName")
        }
    }

    fun validateEmail(value: String, fieldName: String = "email") {
        validateStringBounds(value, fieldName, minLength = 3, maxLength = 255)
        if (!EMAIL_REGEX.matches(value)) {
            throw CanonicalSerializationException("Invalid email format in $fieldName")
        }
    }

    fun validateToken(token: String, fieldName: String = "token") {
        if (token.length < 20) {
            throw CanonicalSerializationException("$fieldName length is below minimum required length")
        }
        if (token.length > 2048) {
            throw CanonicalSerializationException("$fieldName length exceeds maximum allowed characters")
        }
        for (i in token.indices) {
            val c = token[i]
            if (c <= ' ' || c > '~') {
                throw CanonicalSerializationException(
                    "Invalid $fieldName: token must not contain whitespace, CRLF, or control characters"
                )
            }
        }
    }

    fun validateStringBounds(value: String, fieldName: String, minLength: Int = 1, maxLength: Int) {
        if (value.length < minLength) {
            throw CanonicalSerializationException("$fieldName length is below minimum required length")
        }
        if (value.length > maxLength) {
            throw CanonicalSerializationException("$fieldName length exceeds maximum allowed characters")
        }
    }
}
