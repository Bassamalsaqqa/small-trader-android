package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser

/**
 * Bounded query parameters for catalog and customer directory pagination.
 * Bounded strictly per OpenAPI specification:
 * - limit: 1..100 (default 50)
 * - cursor: opaque token string max 255 chars, rejecting external URLs
 * - search: query string max 100 chars
 */
data class PaginationQuery(
    val limit: Int = 50,
    val cursor: String? = null,
    val search: String? = null
) {
    init {
        require(limit in 1..100) {
            "Pagination limit must be between 1 and 100, got $limit"
        }
        EndpointSecurity.validateCursor(cursor)
        if (search != null) {
            require(search.length <= 100) {
                "Search query exceeds maximum allowed 100 characters, got ${search.length}"
            }
        }
    }

    fun toQueryParams(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["limit"] = limit.toString()
        if (cursor != null) {
            map["cursor"] = cursor
        }
        if (search != null) {
            map["search"] = search
        }
        return map
    }
}

/**
 * Bounded, pure Kotlin Android Read Client Foundation for Small Trader Mobile V1 API.
 *
 * Scoped strictly to read/auth endpoints:
 * 1. POST /api/mobile/v1/auth/challenge
 * 2. POST /api/mobile/v1/auth/login
 * 3. POST /api/mobile/v1/auth/logout
 * 4. GET  /api/mobile/v1/companies/{companyId}/bootstrap
 * 5. GET  /api/mobile/v1/companies/{companyId}/products
 * 6. GET  /api/mobile/v1/companies/{companyId}/customers
 *
 * Security & Boundary Invariants:
 * - Injected transport with explicit endpoint allowlisting and base-origin validation.
 * - Bearer tokens passed ONLY in Authorization header; login credentials ONLY in request body.
 * - No secrets or credentials in logs, exceptions, or error serialization.
 * - Cursors are opaque tokens; arbitrary URL following is strictly rejected.
 * - Exact revision 2 wire shape parsing with extra/missing property checks, exact decimal preservation.
 * - Server 401 returns explicit Auth401 outcome for caller lock handling.
 * - MFA_REQUIRED returns explicit fresh_challenge retry indicator.
 * - Does not wire network engine, UI, ViewModel, Keystore, Room, or sales endpoints.
 */
class SmallTraderReadClient(
    private val transport: ReadClientTransport,
    baseOrigin: String
) {
    val validatedBaseOrigin: String = EndpointSecurity.validateBaseOrigin(baseOrigin)

    /**
     * Request an authentication challenge nonce and TTL for login.
     * POST /api/mobile/v1/auth/challenge
     */
    fun authChallenge(request: AuthChallengeRequest): ReadClientResult<AuthChallengeResponse> {
        val path = EndpointSecurity.authChallengePath()
        val transportRequest = TransportRequest(
            method = HttpMethod.POST,
            path = path,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            body = request.toJsonString()
        )
        return executeRequest(transportRequest) { obj ->
            AuthChallengeResponse.fromStrictJson(obj)
        }
    }

    /**
     * Authenticate with challenge nonce and credentials.
     * POST /api/mobile/v1/auth/login
     *
     * Credentials are placed strictly in the JSON body.
     * Returns either Success (with immutable actor and companies) or MfaRequired (with fresh_challenge retry).
     */
    fun authLogin(request: AuthLoginRequest): ReadClientResult<AuthLoginResult> {
        val path = EndpointSecurity.authLoginPath()
        val transportRequest = TransportRequest(
            method = HttpMethod.POST,
            path = path,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            body = request.toJsonString()
        )
        return executeRequest(transportRequest) { obj ->
            AuthLoginResult.fromStrictJson(obj)
        }
    }

    /**
     * Revoke active mobile session token.
     * POST /api/mobile/v1/auth/logout
     *
     * Bearer token is passed ONLY in Authorization header.
     */
    fun authLogout(token: String, request: AuthLogoutRequest): ReadClientResult<AuthLogoutResponse> {
        val path = EndpointSecurity.authLogoutPath()
        val transportRequest = TransportRequest(
            method = HttpMethod.POST,
            path = path,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to buildAuthHeader(token)
            ),
            body = request.toJsonString()
        )
        return executeRequest(transportRequest) { obj ->
            AuthLogoutResponse.fromStrictJson(obj)
        }
    }

    /**
     * Fetch authoritative bootstrap configuration for tenant company.
     * GET /api/mobile/v1/companies/{companyId}/bootstrap
     *
     * Bearer token is passed ONLY in Authorization header.
     */
    fun getBootstrap(token: String, companyId: ServerUlid): ReadClientResult<BootstrapResponse> {
        val path = EndpointSecurity.bootstrapPath(companyId)
        val transportRequest = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to buildAuthHeader(token)
            )
        )
        return executeRequest(transportRequest) { obj ->
            BootstrapResponse.fromStrictJson(obj)
        }
    }

    /**
     * Fetch bounded product catalog for tenant company.
     * GET /api/mobile/v1/companies/{companyId}/products
     *
     * Pagination is bounded via limit/cursor/search query params; empty results are returned honestly.
     */
    fun getProducts(
        token: String,
        companyId: ServerUlid,
        query: PaginationQuery = PaginationQuery()
    ): ReadClientResult<ProductCatalogResponse> {
        val path = EndpointSecurity.productsPath(companyId)
        val transportRequest = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            queryParams = query.toQueryParams(),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to buildAuthHeader(token)
            )
        )
        return executeRequest(transportRequest) { obj ->
            ProductCatalogResponse.fromStrictJson(obj)
        }
    }

    /**
     * Fetch bounded customer directory for tenant company.
     * GET /api/mobile/v1/companies/{companyId}/customers
     *
     * Pagination is bounded via limit/cursor/search query params; empty results are returned honestly.
     */
    fun getCustomers(
        token: String,
        companyId: ServerUlid,
        query: PaginationQuery = PaginationQuery()
    ): ReadClientResult<CustomerDirectoryResponse> {
        val path = EndpointSecurity.customersPath(companyId)
        val transportRequest = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            queryParams = query.toQueryParams(),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to buildAuthHeader(token)
            )
        )
        return executeRequest(transportRequest) { obj ->
            CustomerDirectoryResponse.fromStrictJson(obj)
        }
    }

    private fun buildAuthHeader(token: String): String {
        require(token.isNotBlank() && token.length >= 20) {
            "Invalid bearer token: length must be at least 20 characters"
        }
        return "Bearer $token"
    }

    private fun <T> executeRequest(
        request: TransportRequest,
        parser: (StrictJsonObject) -> T
    ): ReadClientResult<T> {
        return try {
            EndpointSecurity.validatePath(request.path)
            val response = transport.execute(request)
            when (response.statusCode) {
                in 200..299 -> {
                    try {
                        val rootElement = StrictJsonParser.parse(response.body)
                        val rootObj = rootElement.asObject()
                        ReadClientResult.Success(parser(rootObj))
                    } catch (e: Throwable) {
                        ReadClientResult.ParseFailure("Failed to parse successful response: ${e.message}", e)
                    }
                }
                401 -> {
                    val err = parseErrorBody(response.body, "UNAUTHORIZED", "HTTP 401 Unauthorized")
                    ReadClientResult.Auth401(err)
                }
                403 -> {
                    val err = parseErrorBody(response.body, "FORBIDDEN", "HTTP 403 Forbidden")
                    ReadClientResult.Forbidden403(err)
                }
                409 -> {
                    val err = parseErrorBody(response.body, "CONFLICT", "HTTP 409 Conflict")
                    ReadClientResult.Conflict409(err)
                }
                else -> {
                    val err = try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        ReadClientErrorResponse.fromStrictJson(rootObj)
                    } catch (_: Throwable) {
                        null
                    }
                    ReadClientResult.HttpError(response.statusCode, err)
                }
            }
        } catch (e: Throwable) {
            ReadClientResult.NetworkUnknown("Transport execution failure: ${e.message}", e)
        }
    }

    private fun parseErrorBody(body: String, fallbackCode: String, fallbackMessage: String): ReadClientErrorResponse {
        return try {
            val rootObj = StrictJsonParser.parse(body).asObject()
            ReadClientErrorResponse.fromStrictJson(rootObj)
        } catch (_: Throwable) {
            ReadClientErrorResponse(code = fallbackCode, message = fallbackMessage)
        }
    }
}
