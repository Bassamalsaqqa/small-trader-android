package com.example.data.mobile.contract

import java.math.BigDecimal
import java.security.MessageDigest

class CanonicalSerializationException(message: String) : RuntimeException(message)

/**
 * Interface implemented by contract models to convert themselves to a canonical map/list structure
 * suitable for Small Trader Restricted Canonical JSON Profile v1 serialization.
 */
interface CanonicalSerializable {
    fun toCanonicalStructure(): Any?
}

/**
 * Serializes objects following the Small Trader Restricted Canonical JSON Profile v1 (RFC 8785 subset):
 * - All economic/monetary and decimal figures must be string-encoded (IEEE 754 floats/doubles are strictly forbidden).
 * - Keys are sorted by UTF-16 code units (RFC 8785 section 3.2.3).
 * - Characters < 0x20 escaped as \u00xx or standard short escapes (\b, \t, \n, \f, \r, \", \\).
 * - Non-ASCII UTF-8 characters are emitted unescaped in UTF-8.
 * - Lone / unpaired surrogates are strictly rejected.
 * - No whitespace between tokens.
 * - Distinguishes empty objects {} from empty lists [].
 * - Bounded integer domain: non-economic small integers strictly bounded to RFC 8785 IEEE 754 safe integer range [-(2^53-1), 2^53-1].
 * - Canonical integer negative zero ('-0') is strictly forbidden.
 * - Recursion depth bounded to 32.
 * - Computes SHA-256 digests formatted as 'sha256:<64 lowercase hex>'.
 */
object CanonicalJsonSerializer {

    fun serialize(obj: Any?): String = serializeInternal(obj, depth = 0)

    fun serializeToUtf8Bytes(obj: Any?): ByteArray = serialize(obj).toByteArray(Charsets.UTF_8)

    fun computeDigest(obj: Any?): PayloadDigest {
        val bytes = serializeToUtf8Bytes(obj)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return PayloadDigest("sha256:$hex")
    }

    private fun serializeInternal(obj: Any?, depth: Int): String {
        if (depth > ContractConstants.MAX_NESTING_DEPTH) {
            throw CanonicalSerializationException(
                "Serialization recursion depth exceeded maximum allowed ${ContractConstants.MAX_NESTING_DEPTH}"
            )
        }

        if (obj == null) return "null"
        if (obj is Float || obj is Double) {
            throw CanonicalSerializationException(
                "IEEE 754 floats are strictly forbidden in Restricted Canonical JSON Profile; all decimals must be string-encoded"
            )
        }
        if (obj is BigDecimal) {
            throw CanonicalSerializationException(
                "Raw BigDecimal cannot be emitted directly as a JSON number in Restricted Canonical JSON Profile; format as a scaled decimal string"
            )
        }
        if (obj is Boolean) return if (obj) "true" else "false"

        if (obj is Int || obj is Long || obj is Short || obj is Byte) {
            val longVal = (obj as Number).toLong()
            if (longVal < ContractConstants.MIN_SAFE_INTEGER || longVal > ContractConstants.MAX_SAFE_INTEGER) {
                throw CanonicalSerializationException(
                    "Integer '$longVal' exceeds canonical safe integer domain [-(2^53-1), 2^53-1]"
                )
            }
            return longVal.toString()
        }

        if (obj is String) return escapeString(obj)

        // Value primitives
        if (obj is ServerUlid) return escapeString(obj.value)
        if (obj is ClientUuid) return escapeString(obj.value)
        if (obj is PayloadDigest) return escapeString(obj.value)
        if (obj is PositiveDecimalQuantity) return escapeString(obj.toPlainString())
        if (obj is NonnegativeDecimalQuantity) return escapeString(obj.toPlainString())
        if (obj is MoneyAmount) return escapeString(obj.toPlainString())
        if (obj is TaxPercentage) return escapeString(obj.toPlainString())

        if (obj is StrictJsonElement) {
            return when (obj) {
                is StrictJsonNull -> "null"
                is StrictJsonBoolean -> if (obj.value) "true" else "false"
                is StrictJsonNumber -> {
                    if (!obj.isInt) {
                        throw CanonicalSerializationException(
                            "IEEE 754 floats are strictly forbidden in Restricted Canonical JSON Profile; all decimals must be string-encoded"
                        )
                    }
                    if (obj.rawString == "-0" || (obj.rawString.startsWith("-") && obj.rawString.drop(1).all { it == '0' })) {
                        throw CanonicalSerializationException("Negative zero '-0' is forbidden in canonical JSON profile")
                    }
                    val longVal = obj.rawString.toLongOrNull()
                        ?: throw CanonicalSerializationException("Integer '${obj.rawString}' exceeds canonical integer range")
                    if (longVal < ContractConstants.MIN_SAFE_INTEGER || longVal > ContractConstants.MAX_SAFE_INTEGER) {
                        throw CanonicalSerializationException(
                            "Integer '${obj.rawString}' exceeds canonical safe integer domain [-(2^53-1), 2^53-1]"
                        )
                    }
                    obj.rawString
                }
                is StrictJsonString -> escapeString(obj.value)
                is StrictJsonArray -> {
                    "[" + obj.elements.joinToString(",") { serializeInternal(it, depth + 1) } + "]"
                }
                is StrictJsonObject -> {
                    val sortedKeys = obj.properties.keys.sortedWith { a, b -> a.compareTo(b) }
                    val parts = sortedKeys.map { k ->
                        escapeString(k) + ":" + serializeInternal(obj.properties[k], depth + 1)
                    }
                    "{" + parts.joinToString(",") + "}"
                }
            }
        }

        if (obj is List<*>) {
            return "[" + obj.joinToString(",") { serializeInternal(it, depth + 1) } + "]"
        }
        if (obj is Array<*>) {
            return "[" + obj.joinToString(",") { serializeInternal(it, depth + 1) } + "]"
        }
        if (obj is Map<*, *>) {
            val stringMap = mutableMapOf<String, Any?>()
            for ((k, v) in obj) {
                if (k !is String) {
                    throw CanonicalSerializationException("Object keys must be strings, found: ${k?.javaClass?.name}")
                }
                stringMap[k] = v
            }
            // Sort keys by UTF-16 code units (String.compareTo in Java/Kotlin compares code units lexicographically)
            val sortedKeys = stringMap.keys.sortedWith { a, b -> a.compareTo(b) }
            val parts = sortedKeys.map { k ->
                escapeString(k) + ":" + serializeInternal(stringMap[k], depth + 1)
            }
            return "{" + parts.joinToString(",") + "}"
        }

        if (obj is CanonicalSerializable) {
            return serializeInternal(obj.toCanonicalStructure(), depth + 1)
        }

        throw CanonicalSerializationException("Unsupported data type for canonical serialization: ${obj.javaClass.name}")
    }

    fun escapeString(s: String): String {
        if (s.length > ContractConstants.MAX_STRING_LENGTH) {
            throw CanonicalSerializationException("JSON string length exceeds maximum allowed ${ContractConstants.MAX_STRING_LENGTH} UTF-16 code units")
        }
        val out = StringBuilder()
        out.append('"')
        var i = 0
        val len = s.length
        while (i < len) {
            val c = s[i]
            val code = c.code

            if (Character.isHighSurrogate(c)) {
                if (i + 1 < len && Character.isLowSurrogate(s[i + 1])) {
                    out.append(c)
                    out.append(s[i + 1])
                    i += 2
                    continue
                } else {
                    throw CanonicalSerializationException(
                        "Unpaired Unicode surrogate detected at index $i: 0x${Integer.toHexString(code)}"
                    )
                }
            } else if (Character.isLowSurrogate(c)) {
                throw CanonicalSerializationException(
                    "Unpaired low Unicode surrogate detected at index $i: 0x${Integer.toHexString(code)}"
                )
            }

            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (code < 0x20) {
                        out.append(String.format("\\u%04x", code))
                    } else {
                        out.append(c)
                    }
                }
            }
            i++
        }
        out.append('"')
        return out.toString()
    }
}
