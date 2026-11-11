package com.example.data.mobile.readclient.http

/**
 * Safe, bounded transport exception representing network or protocol-level failures.
 *
 * Security & Sanitization Invariants:
 * - Retains only a fixed typed failure category and a sanitized message.
 * - Strictly does NOT store or attach underlying raw exception causes (which can leak socket addresses,
 *   raw URLs containing sensitive query tokens, or low-level connection secrets).
 * - Safe toString() guarantees zero credential or token leakage.
 */
class HttpTransportException(
    val category: String,
    message: String
) : RuntimeException(message) {

    override fun toString(): String =
        "HttpTransportException(category='$category', message='$message')"

    companion object {
        const val CATEGORY_SECURITY_VIOLATION = "SECURITY_VIOLATION"
        const val CATEGORY_CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
        const val CATEGORY_READ_TIMEOUT = "READ_TIMEOUT"
        const val CATEGORY_TIMEOUT = "NETWORK_TIMEOUT"
        const val CATEGORY_NETWORK_IO = "NETWORK_IO_ERROR"
        const val CATEGORY_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
        const val CATEGORY_MALFORMED_UTF8 = "MALFORMED_UTF8"
        const val CATEGORY_UNSUPPORTED_PROTOCOL = "UNSUPPORTED_PROTOCOL"
    }
}
