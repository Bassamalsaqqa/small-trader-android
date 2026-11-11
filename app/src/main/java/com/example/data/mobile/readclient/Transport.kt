package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.ServerUlid

enum class HttpMethod {
    GET,
    POST
}

/**
 * Standard typed request envelope for transport dispatch.
 * Sensitive data in headers (Authorization) and body (credentials) are protected
 * and redacted in toString() representations to prevent secret leakage.
 */
data class TransportRequest(
    val method: HttpMethod,
    val path: String,
    val queryParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
) {
    override fun toString(): String {
        val safeHeaders = headers.mapValues { (k, v) ->
            if (k.equals("Authorization", ignoreCase = true)) {
                "Bearer [REDACTED]"
            } else {
                v
            }
        }
        val safeBody = if (path.endsWith("/auth/login")) {
            "[LOGIN_CREDENTIALS_REDACTED]"
        } else if (body != null && body.length > 256) {
            body.take(256) + "...[truncated]"
        } else {
            body
        }
        return "TransportRequest(method=$method, path='$path', queryParams=$queryParams, headers=$safeHeaders, body=$safeBody)"
    }
}

/**
 * Standard typed response envelope returned by transport.
 */
data class TransportResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String
)

/**
 * Pluggable pure Kotlin transport interface.
 * Implemented by test mock harnesses and future HTTP engines.
 */
interface ReadClientTransport {
    fun execute(request: TransportRequest): TransportResponse
}

/**
 * Strict endpoint allowlist and base-origin validator.
 * Enforces:
 * - Only allowlisted API paths for challenge, login, logout, bootstrap, products, customers.
 * - Base origin must be valid http/https without path traversal or userinfo.
 * - Cursors are opaque tokens only, rejecting arbitrary URL following.
 */
object EndpointSecurity {

    private val ALLOWED_PATH_PATTERNS = listOf(
        Regex("""^/api/mobile/v1/auth/challenge$"""),
        Regex("""^/api/mobile/v1/auth/login$"""),
        Regex("""^/api/mobile/v1/auth/logout$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/bootstrap$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers$""")
    )

    private val ORIGIN_REGEX = Regex(
        """^https?://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$"""
    )

    fun validateBaseOrigin(origin: String): String {
        val trimmed = origin.trimEnd('/')
        require(ORIGIN_REGEX.matches(trimmed)) {
            "Invalid base origin: '$origin'. Must be 'http://<host>[:<port>]' or 'https://<host>[:<port>]' without path, query or userinfo."
        }
        return trimmed
    }

    fun validatePath(path: String) {
        val matchesAny = ALLOWED_PATH_PATTERNS.any { it.matches(path) }
        require(matchesAny) {
            "Unauthorized API path: '$path'. Must match an allowlisted Mobile V1 read endpoint."
        }
    }

    fun validateCursor(cursor: String?) {
        if (cursor == null) return
        require(cursor.length <= 255) {
            "Pagination cursor exceeds 255 characters, got ${cursor.length}"
        }
        require(!cursor.contains("://") && !cursor.contains("/") && !cursor.contains("?") && !cursor.contains("#")) {
            "Cursor must be an opaque token, external URL or path traversal is strictly prohibited: '$cursor'"
        }
    }

    fun authChallengePath(): String = "/api/mobile/v1/auth/challenge"
    fun authLoginPath(): String = "/api/mobile/v1/auth/login"
    fun authLogoutPath(): String = "/api/mobile/v1/auth/logout"
    fun bootstrapPath(companyId: ServerUlid): String = "/api/mobile/v1/companies/${companyId.value}/bootstrap"
    fun productsPath(companyId: ServerUlid): String = "/api/mobile/v1/companies/${companyId.value}/products"
    fun customersPath(companyId: ServerUlid): String = "/api/mobile/v1/companies/${companyId.value}/customers"
}
