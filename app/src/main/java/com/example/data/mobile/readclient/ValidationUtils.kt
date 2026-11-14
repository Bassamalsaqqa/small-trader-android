package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.TaxPercentage
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Strict validation and JSON extraction helpers for Small Trader Read Client models.
 */
internal object ValidationUtils {

    private val RFC3339_REGEX = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.[0-9]{1,9})?(?:[Zz]|([+-])(\d{2}):(\d{2}))$"""
    )

    private val EMAIL_REGEX = Regex(
        "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$"
    )

    fun validateDateTime(value: String, fieldName: String) {
        val match = RFC3339_REGEX.matchEntire(value)
            ?: throw CanonicalSerializationException("$fieldName must be an RFC 3339 timestamp, got '$value'")
        val groups = match.groupValues
        val year = groups[1].toInt()
        if (year !in 2020..2100) {
            throw CanonicalSerializationException("$fieldName year $year is outside supported bounds [2020..2100]")
        }
        if (groups[7].isNotEmpty()) {
            val hourOffset = groups[8].toInt()
            val minOffset = groups[9].toInt()
            if (hourOffset > 23 || minOffset > 59) {
                throw CanonicalSerializationException("Invalid timestamp timezone offset in $fieldName: '$value'")
            }
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
            calendar.timeInMillis // Force validation of month length and leap year
        } catch (e: Exception) {
            throw CanonicalSerializationException("Invalid calendar date in $fieldName: '$value'")
        }
    }

    fun validateEmail(value: String, fieldName: String) {
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
                "$fieldName length exceeds maximum allowed $maxLength UTF-16 code units, got ${value.length}"
            )
        }
    }

    fun checkAllowedKeys(obj: StrictJsonObject, allowedKeys: Set<String>, scopeName: String) {
        val unexpected = obj.properties.keys - allowedKeys
        if (unexpected.isNotEmpty()) {
            throw CanonicalSerializationException("Unexpected fields in $scopeName: $unexpected")
        }
    }

    fun requireString(obj: StrictJsonObject, key: String, minLength: Int = 1, maxLength: Int = 255): String {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON string, got ${element::class.simpleName}")
        validateStringBounds(s, key, minLength, maxLength)
        return s
    }

    fun optionalString(obj: StrictJsonObject, key: String, minLength: Int = 1, maxLength: Int = 255): String? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON string or null, got ${element::class.simpleName}")
        validateStringBounds(s, key, minLength, maxLength)
        return s
    }

    fun requireNullableString(obj: StrictJsonObject, key: String, minLength: Int = 1, maxLength: Int = 255): String? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON string or null, got ${element::class.simpleName}")
        validateStringBounds(s, key, minLength, maxLength)
        return s
    }

    fun requireBoolean(obj: StrictJsonObject, key: String): Boolean {
        val element = obj.require(key)
        return (element as? StrictJsonBoolean)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a boolean, got ${element::class.simpleName}")
    }

    fun requireInt(obj: StrictJsonObject, key: String, min: Int? = null, max: Int? = null): Int {
        val element = obj.require(key)
        val num = (element as? StrictJsonNumber)
            ?: throw CanonicalSerializationException("Property '$key' must be a number, got ${element::class.simpleName}")
        if (!num.isInt) {
            throw CanonicalSerializationException("Property '$key' must be an integer, got float '${num.rawString}'")
        }
        val intVal = num.rawString.toIntOrNull()
            ?: throw CanonicalSerializationException("Property '$key' integer value '${num.rawString}' is out of 32-bit range")
        if (min != null && intVal < min) {
            throw CanonicalSerializationException("Property '$key' must be >= $min, got $intVal")
        }
        if (max != null && intVal > max) {
            throw CanonicalSerializationException("Property '$key' must be <= $max, got $intVal")
        }
        return intVal
    }

    fun requireLong(obj: StrictJsonObject, key: String, min: Long? = null, max: Long? = null): Long {
        val element = obj.require(key)
        val num = (element as? StrictJsonNumber)
            ?: throw CanonicalSerializationException("Property '$key' must be a number, got ${element::class.simpleName}")
        if (!num.isInt) {
            throw CanonicalSerializationException("Property '$key' must be an integer, got float '${num.rawString}'")
        }
        val longVal = num.rawString.toLongOrNull()
            ?: throw CanonicalSerializationException("Property '$key' integer value '${num.rawString}' is out of 64-bit range")
        if (longVal < ContractConstants.MIN_SAFE_INTEGER || longVal > ContractConstants.MAX_SAFE_INTEGER) {
            throw CanonicalSerializationException("Property '$key' exceeds canonical safe integer domain")
        }
        if (min != null && longVal < min) {
            throw CanonicalSerializationException("Property '$key' must be >= $min, got $longVal")
        }
        if (max != null && longVal > max) {
            throw CanonicalSerializationException("Property '$key' must be <= $max, got $longVal")
        }
        return longVal
    }

    fun requireUlid(obj: StrictJsonObject, key: String): ServerUlid {
        val s = requireString(obj, key, minLength = 26, maxLength = 26)
        if (!ServerUlid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid 26-char Crockford ULID, got '$s'")
        }
        return ServerUlid.parse(s)
    }

    fun optionalUlid(obj: StrictJsonObject, key: String): ServerUlid? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a ULID string or null, got ${element::class.simpleName}")
        if (!ServerUlid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid 26-char Crockford ULID or null, got '$s'")
        }
        return ServerUlid.parse(s)
    }

    fun requireNullableUlid(obj: StrictJsonObject, key: String): ServerUlid? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a ULID string or null, got ${element::class.simpleName}")
        if (!ServerUlid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid 26-char Crockford ULID or null, got '$s'")
        }
        return ServerUlid.parse(s)
    }

    fun requireUuid(obj: StrictJsonObject, key: String): ClientUuid {
        val s = requireString(obj, key, minLength = 36, maxLength = 36)
        if (!ClientUuid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid RFC 4122 UUID, got '$s'")
        }
        return ClientUuid.parse(s)
    }

    fun optionalUuid(obj: StrictJsonObject, key: String): ClientUuid? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a UUID string or null, got ${element::class.simpleName}")
        if (!ClientUuid.isValid(s)) {
            throw CanonicalSerializationException("Property '$key' must be a valid RFC 4122 UUID or null, got '$s'")
        }
        return ClientUuid.parse(s)
    }

    fun requireObject(obj: StrictJsonObject, key: String): StrictJsonObject {
        val element = obj.require(key)
        return (element as? StrictJsonObject)
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON object, got ${element::class.simpleName}")
    }

    fun optionalObject(obj: StrictJsonObject, key: String): StrictJsonObject? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        return (element as? StrictJsonObject)
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON object or null, got ${element::class.simpleName}")
    }

    fun requireArray(obj: StrictJsonObject, key: String): StrictJsonArray {
        val element = obj.require(key)
        return (element as? StrictJsonArray)
            ?: throw CanonicalSerializationException("Property '$key' must be a JSON array, got ${element::class.simpleName}")
    }

    fun requireMoney(obj: StrictJsonObject, key: String): MoneyAmount {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Money property '$key' must be a string decimal, got ${element::class.simpleName}")
        if (!ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Money property '$key' has invalid decimal lexeme: '$s'")
        }
        return MoneyAmount.fromNonnegativeString(s)
    }

    fun requirePositiveMoney(obj: StrictJsonObject, key: String): MoneyAmount {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Money property '$key' must be a string decimal, got ${element::class.simpleName}")
        if (!ContractConstants.POSITIVE_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Money property '$key' has invalid positive decimal lexeme: '$s'")
        }
        return MoneyAmount.fromPositiveString(s)
    }

    fun optionalMoney(obj: StrictJsonObject, key: String): MoneyAmount? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Money property '$key' must be a string decimal or null, got ${element::class.simpleName}")
        if (!ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Money property '$key' has invalid decimal lexeme: '$s'")
        }
        return MoneyAmount.fromNonnegativeString(s)
    }

    fun requireNullableMoney(obj: StrictJsonObject, key: String): MoneyAmount? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Money property '$key' must be a string decimal or null, got ${element::class.simpleName}")
        if (!ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Money property '$key' has invalid decimal lexeme: '$s'")
        }
        return MoneyAmount.fromNonnegativeString(s)
    }

    fun requireTaxPercentage(obj: StrictJsonObject, key: String): TaxPercentage {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Tax rate property '$key' must be a string decimal, got ${element::class.simpleName}")
        if (!ContractConstants.TAX_PERCENTAGE_REGEX.matches(s)) {
            throw CanonicalSerializationException("Tax rate property '$key' has invalid tax percentage format: '$s'")
        }
        return TaxPercentage.fromString(s)
    }

    fun optionalTaxPercentage(obj: StrictJsonObject, key: String): TaxPercentage? {
        val element = obj.properties[key] ?: return null
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Tax rate property '$key' must be a string decimal or null, got ${element::class.simpleName}")
        if (!ContractConstants.TAX_PERCENTAGE_REGEX.matches(s)) {
            throw CanonicalSerializationException("Tax rate property '$key' has invalid tax percentage format: '$s'")
        }
        return TaxPercentage.fromString(s)
    }

    fun requireNullableTaxPercentage(obj: StrictJsonObject, key: String): TaxPercentage? {
        if (!obj.properties.containsKey(key)) {
            throw CanonicalSerializationException("Missing required property '$key'")
        }
        val element = obj.properties[key]!!
        if (element is StrictJsonNull) return null
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Tax rate property '$key' must be a string decimal or null, got ${element::class.simpleName}")
        if (!ContractConstants.TAX_PERCENTAGE_REGEX.matches(s)) {
            throw CanonicalSerializationException("Tax rate property '$key' has invalid tax percentage format: '$s'")
        }
        return TaxPercentage.fromString(s)
    }

    fun requirePositiveQuantity(obj: StrictJsonObject, key: String): PositiveDecimalQuantity {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Quantity property '$key' must be a string decimal, got ${element::class.simpleName}")
        if (!ContractConstants.POSITIVE_QUANTITY_REGEX.matches(s)) {
            throw CanonicalSerializationException("Quantity property '$key' has invalid positive quantity format: '$s'")
        }
        return PositiveDecimalQuantity.fromPositiveString(s)
    }

    fun requireNonnegativeQuantity(obj: StrictJsonObject, key: String): NonnegativeDecimalQuantity {
        val element = obj.require(key)
        val s = (element as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Quantity property '$key' must be a string decimal, got ${element::class.simpleName}")
        if (!ContractConstants.NONNEGATIVE_DECIMAL_REGEX.matches(s)) {
            throw CanonicalSerializationException("Quantity property '$key' has invalid nonnegative quantity format: '$s'")
        }
        return NonnegativeDecimalQuantity.fromNonnegativeString(s)
    }
}
