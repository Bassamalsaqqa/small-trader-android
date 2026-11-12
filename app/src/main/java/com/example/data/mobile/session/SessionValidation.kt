package com.example.data.mobile.session

import com.example.data.mobile.contract.CanonicalSerializationException
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Strict validation and RFC 3339 timestamp helpers for secure session management.
 * Compatible with Android API 24+ without requiring core library desugaring or API 26+ java.time.
 */
internal object SessionValidation {

    private val RFC3339_REGEX = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.([0-9]{1,9}))?(?:[Zz]|([+-])(\d{2}):(\d{2}))$"""
    )

    private val EMAIL_REGEX = Regex(
        "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$"
    )

    fun validateDateTime(value: String, fieldName: String) {
        parseRfc3339ToEpochMs(value, fieldName)
    }

    fun parseRfc3339ToEpochMs(value: String, fieldName: String = "timestamp"): Long {
        val match = RFC3339_REGEX.matchEntire(value)
            ?: throw CanonicalSerializationException("$fieldName must be an RFC 3339 timestamp, got '$value'")
        val groups = match.groupValues
        val year = groups[1].toInt()
        if (year !in 2020..2100) {
            throw CanonicalSerializationException("$fieldName year $year is outside supported bounds [2020..2100]")
        }

        var offsetMinutes = 0
        if (groups[7].isNotEmpty()) {
            val sign = if (groups[7] == "-") -1 else 1
            val hourOffset = groups[8].toInt()
            val minOffset = groups[9].toInt()
            if (hourOffset > 23 || minOffset > 59) {
                throw CanonicalSerializationException("Invalid timestamp timezone offset in $fieldName: '$value'")
            }
            offsetMinutes = sign * (hourOffset * 60 + minOffset)
        }

        val fractionMs = if (groups[6].isNotEmpty()) {
            val nanoStr = groups[6].padEnd(9, '0').take(3)
            nanoStr.toInt()
        } else {
            0
        }

        try {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(
                groups[1].toInt(),
                groups[2].toInt() - 1,
                groups[3].toInt(),
                groups[4].toInt(),
                groups[5].toInt(),
                groups[6].toInt()
            )
            val utcMillis = calendar.timeInMillis
            return utcMillis - (offsetMinutes * 60_000L) + fractionMs
        } catch (e: Exception) {
            throw CanonicalSerializationException("Invalid calendar date in $fieldName: '$value'")
        }
    }

    fun validateEmail(value: String, fieldName: String = "email") {
        validateStringBounds(value, fieldName, minLength = 3, maxLength = 255)
        if (!EMAIL_REGEX.matches(value)) {
            throw CanonicalSerializationException("$fieldName is not a valid email: '$value'")
        }
    }

    fun validateToken(token: String, fieldName: String = "token") {
        if (token.length < 20) {
            throw CanonicalSerializationException(
                "$fieldName length must be at least 20 characters, got ${token.length}"
            )
        }
        if (token.length > 2048) {
            throw CanonicalSerializationException(
                "$fieldName length exceeds maximum allowed 2048 characters, got ${token.length}"
            )
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
            throw CanonicalSerializationException(
                "$fieldName length must be at least $minLength, got ${value.length}"
            )
        }
        if (value.length > maxLength) {
            throw CanonicalSerializationException(
                "$fieldName length exceeds maximum allowed $maxLength, got ${value.length}"
            )
        }
    }
}
