package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ServerUlid
import java.net.URI
import java.util.Collections
import java.util.Locale

enum class HttpMethod {
    GET,
    POST
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
        Regex("""^/api/mobile/v1/auth/challenge$"""),
        Regex("""^/api/mobile/v1/auth/login$"""),
        Regex("""^/api/mobile/v1/auth/logout$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/bootstrap$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products$"""),
        Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers$""")
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

    fun validatePath(path: String) {
        val matchesAny = ALLOWED_PATH_PATTERNS.any { it.matches(path) }
        require(matchesAny) {
            "Unauthorized API path: '$path'. Must match an allowlisted Mobile V1 read endpoint."
        }
    }

    fun validateMethodAndPath(method: HttpMethod, path: String) {
        when (method) {
            HttpMethod.POST -> {
                require(
                    path == "/api/mobile/v1/auth/challenge" ||
                    path == "/api/mobile/v1/auth/login" ||
                    path == "/api/mobile/v1/auth/logout"
                ) {
                    "Unauthorized POST endpoint: '$path'. Only challenge, login, and logout support POST."
                }
            }
            HttpMethod.GET -> {
                val isBootstrap = Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/bootstrap$""").matches(path)
                val isProducts = Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/products$""").matches(path)
                val isCustomers = Regex("""^/api/mobile/v1/companies/[0-7][0-9A-HJKMNP-TV-Z]{25}/customers$""").matches(path)
                require(isBootstrap || isProducts || isCustomers) {
                    "Unauthorized GET endpoint: '$path'. Only bootstrap, products, and customers support GET."
                }
            }
        }
    }

    fun validateCursor(cursor: String?) {
        if (cursor == null) return
        require(cursor.length <= 255) {
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
}
