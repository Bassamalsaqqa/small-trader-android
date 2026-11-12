package com.example.data.mobile.contract

import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Strict RFC 3339 / ISO 8601 date-time validator compatible with minSdk 24.
 *
 * Invariants:
 * - Does NOT use java.time (compatible with Android API 24).
 * - Enforces strict calendar parsing with [GregorianCalendar.isLenient] = false.
 * - Supports RFC 3339 date-time profile including UTC ('Z'/'z') and numeric offsets ([+-]HH:MM).
 * - Allows optional fractional seconds (1 to 9 digits).
 * - Year bounded to supported contract window [2020..2100].
 * - Rejects invalid days for the month (e.g. February 30th, non-leap year February 29th).
 */
object Rfc3339Validator {

    private val RFC3339_REGEX = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.[0-9]{1,9})?(?:[Zz]|([+-])(\d{2}):(\d{2}))${'$'}"""
    )

    /**
     * Checks if [value] is a valid RFC 3339 date-time string within supported bounds.
     */
    fun isValid(value: String): Boolean {
        if (value.isBlank()) return false
        val match = RFC3339_REGEX.matchEntire(value) ?: return false
        val groups = match.groupValues

        val year = groups[1].toIntOrNull() ?: return false
        if (year !in 2020..2100) return false

        val month = groups[2].toIntOrNull() ?: return false
        val day = groups[3].toIntOrNull() ?: return false
        val hour = groups[4].toIntOrNull() ?: return false
        val minute = groups[5].toIntOrNull() ?: return false
        val second = groups[6].toIntOrNull() ?: return false

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return false
        }

        // Validate offset if present
        if (groups[7].isNotEmpty()) {
            val offsetHour = groups[8].toIntOrNull() ?: return false
            val offsetMinute = groups[9].toIntOrNull() ?: return false
            if (offsetHour > 23 || offsetMinute > 59) return false
        }

        return try {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
            }
            // Trigger calendar validation of day of month and leap years
            calendar.timeInMillis
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Requires that [value] is a valid RFC 3339 date-time string, throwing [IllegalArgumentException] otherwise.
     */
    fun requireValid(value: String, fieldName: String = "serverAcknowledgedAt") {
        require(isValid(value)) {
            "Invalid $fieldName: '$value' must be a valid RFC 3339 timestamp bounded to 2020..2100"
        }
    }
}
