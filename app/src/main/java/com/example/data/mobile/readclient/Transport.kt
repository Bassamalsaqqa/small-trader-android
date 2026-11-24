package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ServerUlid
import java.net.URI
import java.util.Collections
import java.util.Locale

enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE
}

/**
 * Standard typed request envelope for transport dispatch.
 * Sensitive data in headers (Authorization), query params (search/tokens), and body (credentials/payloads)
 * are strictly redacted in toString() representations to prevent secret leakage.
 * Defensive copies protect queryParams and headers from external mutable alias modification.
 */
class TransportRequest(
    val method: HttpMethod,
    val path: String,
    val baseOrigin: String,
    queryParams: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    val body: String? = null
) {
    val queryParams: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(queryParams))
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))

    fun copy(
        method: HttpMethod = this.method,
        path: String = this.path,
        baseOrigin: String = this.baseOrigin,
        queryParams: Map<String, String> = this.queryParams,
        headers: Map<String, String> = this.headers,
        body: String? = this.body
    ): TransportRequest = TransportRequest(method, path, baseOrigin, queryParams, headers, body)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportRequest) return false
        return method == other.method &&
                path == other.path &&
                baseOrigin == other.baseOrigin &&
                queryParams == other.queryParams &&
                headers == other.headers &&
                body == other.body
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + baseOrigin.hashCode()
        result = 31 * result + queryParams.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        val safeHeaders = headers.mapValues { (k, _) ->
            if (k.equals("Authorization", ignoreCase = true)) {
                "Bearer [REDACTED]"
            } else {
                "[REDACTED]"
            }
        }
        val safeQueryParams = if (queryParams.isNotEmpty()) "[REDACTED]" else "{}"
        val safeBody = when {
            path.endsWith("/auth/login") -> "[LOGIN_CREDENTIALS_REDACTED]"
            body != null -> "[REDACTED]"
            else -> "null"
        }
        return "TransportRequest(method=$method, path='$path', baseOrigin='$baseOrigin', queryParams=$safeQueryParams, headers=$safeHeaders, body=$safeBody)"
    }
}

/**
 * Standard typed response envelope returned by transport.
 * Defensive copy protects headers from external alias modification,
 * and toString() redacts the raw body to prevent data leaks.
 */
class TransportResponse(
    val statusCode: Int,
    headers: Map<String, String> = emptyMap(),
    val body: String
) {
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))

    fun copy(
        statusCode: Int = this.statusCode,
        headers: Map<String, String> = this.headers,
        body: String = this.body
    ): TransportResponse = TransportResponse(statusCode, headers, body)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportResponse) return false
        return statusCode == other.statusCode &&
                headers == other.headers &&
                body == other.body
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.hashCode()
        return result
    }

    override fun toString(): String =
        "TransportResponse(statusCode=$statusCode, headers=${if (headers.isNotEmpty()) "[REDACTED]" else "{}"}, body=${if (body.isNotEmpty()) "[REDACTED]" else "\"\""})"
}

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
 * - Base origin must be valid HTTPS (or loopback HTTP only with explicit dev/test opt-in).
 * - Cursors are opaque tokens only, rejecting arbitrary URL following while permitting standard base64/opaque characters.
 */
object EndpointSecurity {

    private val ALLOWED_PATH_PATTERNS = listOf(
        // Auth
        Regex("""^/api/mobile/v1/auth/challenge$"""),
        Regex("""^/api/mobile/v1/auth/login$"""),
        Regex("""^/api/mobile/v1/auth/logout$"""),
        // Session
        Regex("""^/api/mobile/v1/session$"""),
        Regex("""^/api/mobile/v1/sessions$"""),
        Regex("""^/api/mobile/v1/sessions/[0-9a-fA-F-]{36}$"""),
        // Companies V1
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/bootstrap$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/home$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products/[0-7][0-9A-HJKMNP-TV-Z]{25}$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products/[0-7][0-9A-HJKMNP-TV-Z]{25}/movements$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/inventory/adjustments$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/inventory/transfers/preview$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}/statement$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}/payments$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}/settlement-targets$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}/outstanding$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers/[0-7][0-9A-HJKMNP-TV-Z]{25}$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers/[0-7][0-9A-HJKMNP-TV-Z]{25}/payments$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers/[0-7][0-9A-HJKMNP-TV-Z]{25}/settlement-targets$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers/[0-7][0-9A-HJKMNP-TV-Z]{25}/outstanding$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/[0-7][0-9A-HJKMNP-TV-Z]{25}$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/draft$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/preview$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/[0-7][0-9A-HJKMNP-TV-Z]{25}/execute$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/returns/preview$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/returns$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/[0-7][0-9A-HJKMNP-TV-Z]{25}$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/[0-7][0-9A-HJKMNP-TV-Z]{25}/grn$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/returns/preview$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/returns$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/expenses$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/expenses/categories$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/reports/shift-summary$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/reports/shift-close$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/reports/treasury$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/settings/business$"""),
        // Receipts & Command Status
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/receipts/status$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/receipts/[a-zA-Z0-9_.-]{1,128}/status$"""),
        // Companies V2
        Regex("""^/api/mobile/v2/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/online-cash$"""),
        Regex("""^/api/mobile/v2/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/offline-events/[0-9a-fA-F-]{36}/status$""")
    )

    fun validateBaseOrigin(origin: String, allowHttpLoopback: Boolean = false): String {
        val trimmed = origin.trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid base origin URI")
        }

        require(uri.isAbsolute) {
            "Base origin must be an absolute URI, got [REDACTED]"
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme != null) {
            "Base origin must specify a scheme, got [REDACTED]"
        }

        val host = uri.host
        require(!host.isNullOrBlank()) {
            "Base origin must specify a non-empty host, got [REDACTED]"
        }

        val isLoopback = isLoopbackHost(host)
        if (scheme == "http") {
            require(allowHttpLoopback && isLoopback) {
                "Insecure HTTP base origin [REDACTED] is prohibited. HTTPS is required by default; loopback HTTP is only permitted with explicit opt-in."
            }
        } else if (scheme != "https") {
            throw IllegalArgumentException("Unsupported scheme '$scheme' in base origin. Only HTTPS is allowed (or HTTP loopback with explicit opt-in).")
        }

        require(uri.rawUserInfo == null) {
            "Base origin must not contain userinfo credentials: [REDACTED]"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "Base origin must not contain a path segment: [REDACTED]"
        }
        require(uri.rawQuery == null) {
            "Base origin must not contain query parameters: [REDACTED]"
        }
        require(uri.rawFragment == null) {
            "Base origin must not contain a fragment identifier: [REDACTED]"
        }

        val port = uri.port
        if (port != -1) {
            require(port in 1..65535) {
                "Invalid port $port in base origin. Port must be between 1 and 65535."
            }
        }

        return if (port != -1) "$scheme://$host:$port" else "$scheme://$host"
    }

    private fun isLoopbackHost(host: String): Boolean =
        host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

    fun validateSearchQuery(search: String?) {
        if (search == null) return
        val trimmed = search.trim()
        require(trimmed.length <= 100) { "Search query exceeds maximum allowed length of 100 characters" }
        require(trimmed.none { it == '\r' || it == '\n' || it.code < 32 || it.code == 127 }) {
            "Search query contains prohibited control characters"
        }
    }

    fun validateLimit(limit: Int?, maxLimit: Int = 100) {
        if (limit == null) return
        require(limit in 1..maxLimit) { "Limit must be between 1 and $maxLimit, got: $limit" }
    }

    fun validateIdempotencyKey(key: String?) {
        if (key == null) return
        require(key.length in 1..128) { "Idempotency key length must be between 1 and 128 characters, got ${key.length}" }
        require(Regex("""^[a-zA-Z0-9_.-]+$""").matches(key)) {
            "Idempotency key contains invalid characters: '$key'"
        }
    }

    fun validateCurrency(currency: String?) {
        if (currency == null) return
        require(currency.length == 3 && Regex("""^[A-Z]{3}$""").matches(currency)) {
            "Invalid currency parameter: '$currency'. Must be a 3-letter ISO-4217 code."
        }
    }

    fun validateEndpointQueryParams(basePath: String, queryParams: Map<String, String>) {
        for ((key, value) in queryParams) {
            val isAllowed = when (key) {
                "search" -> basePath.endsWith("/products") || basePath.endsWith("/customers") || basePath.endsWith("/suppliers") || basePath.endsWith("/sales/invoices")
                "limit" -> basePath.endsWith("/products") || basePath.endsWith("/customers") || basePath.endsWith("/suppliers") || basePath.endsWith("/sales/invoices") || basePath.endsWith("/movements") || basePath.endsWith("/statement") || basePath.endsWith("/settlement-targets") || basePath.endsWith("/outstanding")
                "cursor" -> basePath.endsWith("/products") || basePath.endsWith("/customers") || basePath.endsWith("/sales/invoices") || basePath.endsWith("/movements") || basePath.endsWith("/statement") || basePath.endsWith("/settlement-targets") || basePath.endsWith("/outstanding")
                "currency" -> basePath.endsWith("/settlement-targets") || basePath.endsWith("/outstanding")
                "idempotency_key" -> basePath.endsWith("/receipts/status")
                "status" -> basePath.endsWith("/sales/invoices")
                "customer_id" -> basePath.endsWith("/sales/invoices")
                "date_from", "date_to", "from_date", "to_date" -> basePath.endsWith("/sales/invoices") || basePath.endsWith("/statement") || basePath.endsWith("/shift-summary")
                else -> false
            }
            require(isAllowed) {
                "Disallowed query parameter '$key' for endpoint '$basePath'"
            }
            when (key) {
                "search" -> validateSearchQuery(value)
                "limit" -> {
                    val max = if (basePath.endsWith("/settlement-targets") || basePath.endsWith("/outstanding")) 250 else 100
                    validateLimit(value.toIntOrNull() ?: throw IllegalArgumentException("Invalid limit"), maxLimit = max)
                }
                "cursor" -> {
                    val isSettlementOrOutstanding = basePath.endsWith("/settlement-targets") || basePath.endsWith("/outstanding")
                    val maxLimit = if (isSettlementOrOutstanding) 512 else 255
                    validateCursor(value, maxLimit = maxLimit)
                }
                "currency" -> validateCurrency(value)
                "idempotency_key" -> validateIdempotencyKey(value)
                "status" -> {
                    require(value.length <= 32) { "Status query parameter exceeds length bound" }
                    val allowed = setOf(
                        "DRAFT", "POSTED", "VOID", "PAID_CASH", "PAID", "PARTIAL", "ON_CREDIT",
                        "draft", "posted", "void", "paid"
                    )
                    require(allowed.contains(value)) { "Invalid status query parameter: '$value'" }
                }
                "customer_id" -> {
                    require(value.length in 1..64) { "Customer ID query parameter length bound exceeded" }
                    require(Regex("^[a-zA-Z0-9_-]+$").matches(value)) { "Invalid customer_id format: '$value'" }
                }
                "date_from", "date_to", "from_date", "to_date" -> {
                    require(value.length in 10..32) { "Date query parameter invalid length" }
                    require(value.none { it == '\r' || it == '\n' || it.code < 32 || it.code == 127 }) {
                        "Date query parameter contains prohibited control characters"
                    }
                    require(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(value)) {
                        "Invalid $key format: Date query parameter must follow YYYY-MM-DD format, got '$value'"
                    }
                }
            }
        }
    }

    fun validateRawQueryString(basePath: String, queryString: String) {
        val isSettlementOrOutstanding = basePath.endsWith("/settlement-targets") || basePath.endsWith("/outstanding")
        val maxLen = if (isSettlementOrOutstanding) 1024 else 512
        require(queryString.length <= maxLen) { "Query string exceeds maximum length bound" }
        require(queryString.none { it == '\r' || it == '\n' || it.code < 32 || it.code == 127 }) {
            "Query string contains prohibited control characters"
        }
        val pairs = queryString.split('&')
        val map = mutableMapOf<String, String>()
        for (pair in pairs) {
            if (pair.isBlank()) continue
            val parts = pair.split('=', limit = 2)
            val key = try { java.net.URLDecoder.decode(parts[0], "UTF-8") } catch (_: Exception) { parts[0] }
            val value = if (parts.size > 1) {
                try { java.net.URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
            } else ""
            map[key] = value
        }
        validateEndpointQueryParams(basePath, map)
    }

    fun validatePath(path: String) {
        val basePath = if (path.contains('?')) path.substringBefore('?') else path
        val matchesAny = ALLOWED_PATH_PATTERNS.any { it.matches(basePath) }
        require(matchesAny) {
            "Unauthorized API path: '$path'. Must match an allowlisted Mobile V1 read endpoint."
        }
        if (path.contains('?')) {
            val queryString = path.substringAfter('?')
            validateRawQueryString(basePath, queryString)
        }
    }

    fun validateMethodAndPath(method: HttpMethod, path: String) {
        validatePath(path)
        val basePath = if (path.contains('?')) path.substringBefore('?') else path
        when (method) {
            HttpMethod.POST -> {
                val isChallenge = basePath == "/api/mobile/v1/auth/challenge"
                val isLogin = basePath == "/api/mobile/v1/auth/login"
                val isLogout = basePath == "/api/mobile/v1/auth/logout"
                val isPostEndpoint = isChallenge || isLogin || isLogout ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/inventory/adjustments$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/inventory/transfers/preview$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}/payments$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers/[0-7][0-9A-HJKMNP-TV-Z]{25}/payments$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/draft$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/preview$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/[0-7][0-9A-HJKMNP-TV-Z]{25}/execute$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/returns/preview$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/returns$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/[0-7][0-9A-HJKMNP-TV-Z]{25}/grn$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/returns/preview$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/purchases/returns$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/expenses$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/reports/shift-close$""").matches(basePath) ||
                    Regex("""^/api/mobile/v2/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/online-cash$""").matches(basePath)
                require(isPostEndpoint) {
                    "Unauthorized POST endpoint: '$path'."
                }
            }
            HttpMethod.GET -> {
                val isNotAllowedGet = basePath == "/api/mobile/v1/auth/login" ||
                    basePath == "/api/mobile/v1/auth/challenge" ||
                    basePath == "/api/mobile/v1/auth/logout" ||
                    basePath.endsWith("/sales/online-cash") ||
                    basePath.endsWith("/inventory/adjustments") ||
                    basePath.endsWith("/shift-close") ||
                    basePath.endsWith("/payments") ||
                    basePath.endsWith("/execute") ||
                    basePath.endsWith("/grn") ||
                    basePath.endsWith("/sales/invoices/preview") ||
                    basePath.endsWith("/sales/invoices/draft") ||
                    basePath.endsWith("/sales/returns") ||
                    basePath.endsWith("/sales/returns/preview") ||
                    basePath.endsWith("/purchases/returns") ||
                    basePath.endsWith("/purchases/returns/preview")
                require(!isNotAllowedGet) {
                    "Unauthorized GET endpoint: '$path'."
                }
            }
            HttpMethod.PUT -> {
                val isPutEndpoint =
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products/[0-7][0-9A-HJKMNP-TV-Z]{25}$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers/[0-7][0-9A-HJKMNP-TV-Z]{25}$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/suppliers/[0-7][0-9A-HJKMNP-TV-Z]{25}$""").matches(basePath) ||
                    Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/sales/invoices/[0-7][0-9A-HJKMNP-TV-Z]{25}$""").matches(basePath)
                require(isPutEndpoint) {
                    "Unauthorized PUT endpoint: '$path'."
                }
            }
            HttpMethod.DELETE -> {
                val isDeleteEndpoint = Regex("""^/api/mobile/v1/sessions/[0-9a-fA-F-]{36}$""").matches(basePath)
                require(isDeleteEndpoint) {
                    "Unauthorized DELETE endpoint: '$path'."
                }
            }
        }
    }

    fun validateCursor(cursor: String?, maxLimit: Int = 255) {
        if (cursor == null) return
        require(cursor.length <= maxLimit) {
            "Pagination cursor exceeds contract length bound"
        }
        // Opaque data only: the transport must encode this query value, never follow it.
    }

    fun authChallengePath(): String = "/api/mobile/v1/auth/challenge"
    fun authLoginPath(): String = "/api/mobile/v1/auth/login"
    fun authLogoutPath(): String = "/api/mobile/v1/auth/logout"
    fun bootstrapPath(companyId: ServerUlid): String = "/api/mobile/v1/companies/${companyId.value}/bootstrap"
    fun productsPath(companyId: ServerUlid): String = "/api/mobile/v1/companies/${companyId.value}/products"
    fun customersPath(companyId: ServerUlid): String = "/api/mobile/v1/companies/${companyId.value}/customers"
    fun customerSettlementTargetsPath(companyId: ServerUlid, customerId: ServerUlid): String =
        "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/settlement-targets"
    fun supplierSettlementTargetsPath(companyId: ServerUlid, supplierId: ServerUlid): String =
        "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}/settlement-targets"
}
