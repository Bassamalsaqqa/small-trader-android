package com.example.data.mobile.contract

/**
 * Authoritative constants and contract constraints for Small Trader Mobile V1 API.
 * Contract Revision: 2 (Draft)
 * Contract Manifest SHA-256: ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659
 */
object ContractConstants {
    const val CONTRACT_VERSION: String = "mobile-v1"
    const val CONTRACT_REVISION: Int = 2
    const val MANIFEST_SHA256: String = "ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659"

    // Server entity public ID: strict 26-character Crockford Base32 ULID starting with [0-7]
    val CROCKFORD_ULID_REGEX: Regex = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}\$")

    // Client and ephemeral entity public ID: RFC 4122 UUID
    val CLIENT_UUID_REGEX: Regex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")

    // Cryptographic payload digest: sha256:<64 lowercase hex>
    val SHA256_DIGEST_REGEX: Regex = Regex("^sha256:[0-9a-f]{64}\$")

    // Positive quantity: strictly positive, up to 14 integer digits, 1 to 6 decimal places (allows trailing zeros)
    val POSITIVE_QUANTITY_REGEX: Regex = Regex("^(?:0\\.(?=[0-9]{1,6}\$)[0-9]*[1-9][0-9]*|[1-9][0-9]{0,13}(?:\\.[0-9]{1,6})?)\$")

    // Nonnegative money/amount: 0 or positive, up to 14 integer digits, up to 6 decimal places
    val NONNEGATIVE_DECIMAL_REGEX: Regex = Regex("^(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?\$")

    // Positive money/amount: strictly positive, up to 14 integer digits, up to 6 decimal places
    val POSITIVE_DECIMAL_REGEX: Regex = Regex("^(?:0\\.(?=[0-9]{1,6}\$)[0-9]*[1-9][0-9]*|[1-9][0-9]{0,13}(?:\\.[0-9]{1,6})?)\$")

    // Tax percentage: 0..100 with up to 6 decimal places
    val TAX_PERCENTAGE_REGEX: Regex = Regex("^(?:100(?:\\.0{1,6})?|(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,6})?)\$")

    // Currency code: ISO-4217 3 uppercase ASCII letters
    val CURRENCY_CODE_REGEX: Regex = Regex("^[A-Z]{3}\$")

    const val DECIMAL_SCALE: Int = 6
    const val MINOR_UNITS: Int = 2
    const val MAX_INTEGER_DIGITS: Int = 14
    const val MAX_LINE_ITEMS: Int = 100

    // Small Trader Restricted Canonical JSON Profile v1 bounds
    const val MAX_PAYLOAD_BYTES: Int = 1_048_576       // 1 MB payload bound
    const val MAX_NESTING_DEPTH: Int = 32              // Maximum recursion / nesting depth
    const val MAX_STRING_LENGTH: Int = 65_536          // Decoded UTF-16 code units (not encoded bytes)
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L   // 2^53 - 1 (RFC 8785 IEEE 754 safe integer)
    const val MIN_SAFE_INTEGER: Long = -9_007_199_254_740_991L  // -(2^53 - 1)
}
