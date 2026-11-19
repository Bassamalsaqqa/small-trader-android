package com.example.data.mobile.merchantread

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import java.util.concurrent.CancellationException

/**
 * Functional interface for resolving the active scoped token.
 * Retains generation, actor, device, and company bounds; strictly rejects fallback to old/stale tokens.
 */
fun interface MerchantReadTokenProvider {
    /**
     * Resolves the active Bearer token for [companyId] under [retainedGeneration].
     * Returns null if generation has changed, token is revoked, or scope is not active.
     */
    fun getActiveToken(companyId: ServerUlid, retainedGeneration: Long?): String?
}

/**
 * Bounded query criteria for sales invoices list.
 */
data class SalesInvoicesQuery(
    val limit: Int = 30,
    val status: String? = null,
    val search: String? = null,
    val customerId: ServerUlid? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val cursor: String? = null
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100, got $limit" }
        if (status != null) {
            require(status in ALLOWED_STATUSES) {
                "Invalid invoice status filter: '$status'. Allowed: $ALLOWED_STATUSES"
            }
        }
        if (search != null) {
            require(search.length <= 100) {
                "Search query exceeds maximum 100 characters, got ${search.length}"
            }
        }
        if (dateFrom != null) {
            MerchantReadValidationUtils.validateDateString(dateFrom, "date_from")
        }
        if (dateTo != null) {
            MerchantReadValidationUtils.validateDateString(dateTo, "date_to")
        }
        if (cursor != null) {
            require(cursor.length <= 255) {
                "cursor exceeds maximum 255 characters, got ${cursor.length}"
            }
        }
    }

    fun toQueryParams(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["limit"] = limit.toString()
        if (status != null) map["status"] = status
        if (search != null && search.isNotBlank()) map["search"] = search
        if (customerId != null) map["customer_id"] = customerId.value
        if (dateFrom != null) map["date_from"] = dateFrom
        if (dateTo != null) map["date_to"] = dateTo
        if (cursor != null && cursor.isNotBlank()) map["cursor"] = cursor
        return map
    }

    companion object {
        private val ALLOWED_STATUSES = setOf(
            "DRAFT", "POSTED", "VOID", "PAID_CASH", "PAID", "PARTIAL", "ON_CREDIT",
            "draft", "posted", "void", "paid"
        )
    }
}

/**
 * Pure Kotlin Mobile V1 Merchant Read Client.
 *
 * Implements strict GET-only projections for:
 * 1.  GET /api/mobile/v1/companies/{companyId}/products/{productId}/movements (Inventory movement history)
 * 2.  GET /api/mobile/v1/companies/{companyId}/home (Live merchant dashboard shell)
 * 3.  GET /api/mobile/v1/companies/{companyId}/sales/invoices (Paginated sales invoices list)
 * 4.  GET /api/mobile/v1/companies/{companyId}/sales/invoices/{publicId} (Sales invoice detail & thermal receipt)
 * 5.  GET /api/mobile/v1/companies/{companyId}/customers/{customerId}/statement (Customer chronological statement & aging)
 * 6.  GET /api/mobile/v1/companies/{companyId}/suppliers (Suppliers list with balances)
 * 7.  GET /api/mobile/v1/companies/{companyId}/suppliers/{supplierId} (Supplier profile & owed balance)
 * 8.  GET /api/mobile/v1/companies/{companyId}/reports/shift-summary (Shift cash movements & expected drawer cash)
 * 9.  GET /api/mobile/v1/companies/{companyId}/reports/treasury (Canonical cash and bank accounts balance)
 * 10. GET /api/mobile/v1/companies/{companyId}/settings/business (Store profile, taxes, warehouses & cash drawer mappings)
 *
 * Security & Design Invariants:
 * - All HTTP requests are strictly GET through [ReadClientTransport].
 * - Configured HTTPS base origin validated via [EndpointSecurity.validateBaseOrigin].
 * - Token provider or explicit token enforces current retained scope generation (actor/device/company); NO fallback to stale tokens.
 * - Strict ULID verification in paths prevents URI injection.
 * - Result envelope [MerchantReadResult] preserves distinct failure categories:
 *   [MerchantReadResult.AuthDenial], [MerchantReadResult.Forbidden], [MerchantReadResult.NotFound],
 *   [MerchantReadResult.HttpError], [MerchantReadResult.SchemaError], [MerchantReadResult.NetworkError].
 * - Genuine empty 200 responses are parsed cleanly; server failures are never swallowed or masked as empty arrays.
 * - Untrusted server error text, raw tokens, and personal customer names/phones are redacted in toString().
 * - Reports preserve nullable values when unauthorized; zeroes are never invented on the client.
 */
class MerchantReadClient(
    val transport: ReadClientTransport,
    baseOrigin: String,
    allowHttpLoopback: Boolean = false,
    val tokenProvider: MerchantReadTokenProvider? = null
) {
    val validatedBaseOrigin: String = EndpointSecurity.validateBaseOrigin(baseOrigin, allowHttpLoopback)

    /**
     * Fetch product movement history.
     * GET /api/mobile/v1/companies/{companyId}/products/{productId}/movements
     */
    fun getProductHistory(
        companyId: ServerUlid,
        productId: ServerUlid,
        limit: Int = 50,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<ProductHistoryResponse> {
        require(limit in 1..100) { "limit must be between 1 and 100, got $limit" }
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/products/${productId.value}/movements"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            queryParams = mapOf("limit" to limit.toString()),
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = ProductHistoryResponse.fromStrictJson(obj)
            if (response.productId != productId) {
                throw CanonicalSerializationException("Product ID mismatch: response product_id does not match requested productId")
            }
            response
        }
    }

    /**
     * Fetch merchant dashboard home shell metrics.
     * GET /api/mobile/v1/companies/{companyId}/home
     */
    fun getHome(
        companyId: ServerUlid,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<HomeResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/home"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = HomeResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            response
        }
    }

    /**
     * Fetch paginated sales invoices list.
     * GET /api/mobile/v1/companies/{companyId}/sales/invoices
     */
    fun getSalesInvoices(
        companyId: ServerUlid,
        query: SalesInvoicesQuery = SalesInvoicesQuery(),
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<SalesInvoiceListResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/sales/invoices"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            queryParams = query.toQueryParams(),
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = SalesInvoiceListResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            response
        }
    }

    /**
     * Fetch detailed sales invoice with lines and thermal receipt layout.
     * GET /api/mobile/v1/companies/{companyId}/sales/invoices/{publicId}
     */
    fun getSalesInvoiceDetail(
        companyId: ServerUlid,
        invoicePublicId: ServerUlid,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<SalesInvoiceDetailResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/sales/invoices/${invoicePublicId.value}"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = SalesInvoiceDetailResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            if (response.id != invoicePublicId) {
                throw CanonicalSerializationException("Invoice ID mismatch: response id does not match requested invoicePublicId")
            }
            response
        }
    }

    /**
     * Fetch chronological customer statement with aging breakdown.
     * GET /api/mobile/v1/companies/{companyId}/customers/{customerId}/statement
     */
    fun getCustomerStatement(
        companyId: ServerUlid,
        customerId: ServerUlid,
        fromDate: String? = null,
        toDate: String? = null,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<CustomerStatementResponse> {
        if (fromDate != null) MerchantReadValidationUtils.validateDateString(fromDate, "from_date")
        if (toDate != null) MerchantReadValidationUtils.validateDateString(toDate, "to_date")

        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val queryParams = mutableMapOf<String, String>()
        if (fromDate != null) queryParams["from_date"] = fromDate
        if (toDate != null) queryParams["to_date"] = toDate

        val path = "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/statement"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            queryParams = queryParams,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = CustomerStatementResponse.fromStrictJson(obj)
            if (response.customerId != customerId) {
                throw CanonicalSerializationException("Customer ID mismatch: response customer_id does not match requested customerId")
            }
            response
        }
    }

    /**
     * Fetch suppliers list with balances.
     * GET /api/mobile/v1/companies/{companyId}/suppliers
     */
    fun getSuppliers(
        companyId: ServerUlid,
        limit: Int = 30,
        search: String? = null,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<SupplierListResponse> {
        require(limit in 1..100) { "limit must be between 1 and 100, got $limit" }
        if (search != null) {
            require(search.length <= 100) { "search query exceeds maximum 100 characters, got ${search.length}" }
        }

        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val queryParams = mutableMapOf<String, String>()
        queryParams["limit"] = limit.toString()
        if (search != null && search.isNotBlank()) {
            queryParams["search"] = search
        }

        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            queryParams = queryParams,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = SupplierListResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            response
        }
    }

    /**
     * Fetch supplier detail and owed balance.
     * GET /api/mobile/v1/companies/{companyId}/suppliers/{supplierId}
     */
    fun getSupplierDetail(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<SupplierDetailResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = SupplierDetailResponse.fromStrictJson(obj)
            if (response.id != supplierId) {
                throw CanonicalSerializationException("Supplier ID mismatch: response id does not match requested supplierId")
            }
            response
        }
    }

    /**
     * Fetch shift financial summary and expected cash.
     * GET /api/mobile/v1/companies/{companyId}/reports/shift-summary
     */
    fun getShiftSummary(
        companyId: ServerUlid,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<ShiftSummaryReportResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/reports/shift-summary"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = ShiftSummaryReportResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            response
        }
    }

    /**
     * Fetch treasury cash and bank account balances from canonical ledger.
     * GET /api/mobile/v1/companies/{companyId}/reports/treasury
     */
    fun getTreasury(
        companyId: ServerUlid,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<TreasuryReportResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/reports/treasury"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = TreasuryReportResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            response
        }
    }

    /**
     * Fetch business settings, taxes, warehouse and approved cash drawer account.
     * GET /api/mobile/v1/companies/{companyId}/settings/business
     */
    fun getBusinessSettings(
        companyId: ServerUlid,
        token: String? = null,
        retainedGeneration: Long? = null
    ): MerchantReadResult<BusinessSettingsResponse> {
        val resolvedToken = resolveToken(companyId, token, retainedGeneration)
            ?: return tokenMissingDenial()

        val path = "/api/mobile/v1/companies/${companyId.value}/settings/business"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = validatedBaseOrigin,
            headers = buildHeaders(resolvedToken)
        )

        return executeRequest(request, companyId, retainedGeneration, resolvedToken) { obj ->
            val response = BusinessSettingsResponse.fromStrictJson(obj)
            if (response.companyId != companyId) {
                throw CanonicalSerializationException("Tenant mismatch: response company_id does not match requested companyId")
            }
            response
        }
    }

    private fun resolveToken(
        companyId: ServerUlid,
        explicitToken: String?,
        retainedGeneration: Long?
    ): String? {
        if (tokenProvider != null) {
            return tokenProvider.getActiveToken(companyId, retainedGeneration)
        }
        if (retainedGeneration != null) {
            return null
        }
        return explicitToken?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun tokenMissingDenial(
        message: String = "Active scoped token unavailable for company and generation"
    ): MerchantReadResult<Nothing> =
        MerchantReadResult.AuthDenial(
            statusCode = 401,
            error = MerchantReadErrorResponse(
                code = "UNAUTHENTICATED",
                message = message
            )
        )

    private fun buildHeaders(token: String): Map<String, String> {
        MerchantReadValidationUtils.validateStringBounds(token, "token", minLength = 20, maxLength = 2048)
        return mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $token"
        )
    }

    private fun <T> executeRequest(
        request: TransportRequest,
        companyId: ServerUlid,
        retainedGeneration: Long?,
        tokenBefore: String,
        parser: (StrictJsonObject) -> T
    ): MerchantReadResult<T> {
        return try {
            EndpointSecurity.validateMethodAndPath(request.method, request.path)
            val response = transport.execute(request)

            // Re-validate authoritative token provider scope after HTTP GET response
            if (tokenProvider != null) {
                val tokenAfter = tokenProvider.getActiveToken(companyId, retainedGeneration)
                if (tokenAfter == null || tokenAfter != tokenBefore) {
                    return tokenMissingDenial("Active scoped token generation changed during read")
                }
            }

            when (response.statusCode) {
                200 -> {
                    try {
                        val rootElement = StrictJsonParser.parse(response.body)
                        val rootObj = rootElement.asObject()
                        val parsed = parser(rootObj)

                        // Strict current generation recheck after parser result
                        if (tokenProvider != null) {
                            val tokenAfterParser = tokenProvider.getActiveToken(companyId, retainedGeneration)
                            if (tokenAfterParser == null || tokenAfterParser != tokenBefore) {
                                return tokenMissingDenial("Active scoped token generation changed during read")
                            }
                        }
                        MerchantReadResult.Success(parsed)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is VirtualMachineError) throw e
                        MerchantReadResult.SchemaError(
                            category = "PARSE_ERROR",
                            message = "Schema validation error: ${e.message}"
                        )
                    }
                }
                401 -> {
                    val err = parseErrorBody(response.body, "UNAUTHORIZED", "HTTP 401 Unauthorized")
                    MerchantReadResult.AuthDenial(401, err)
                }
                403 -> {
                    val err = parseErrorBody(response.body, "FORBIDDEN", "HTTP 403 Forbidden")
                    MerchantReadResult.Forbidden(err)
                }
                404 -> {
                    val err = parseErrorBody(response.body, "NOT_FOUND", "HTTP 404 Not Found")
                    MerchantReadResult.NotFound(err)
                }
                else -> {
                    val err = try {
                        val rootObj = StrictJsonParser.parse(response.body).asObject()
                        MerchantReadErrorResponse.fromStrictJson(rootObj)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is VirtualMachineError) throw e
                        null
                    }
                    MerchantReadResult.HttpError(response.statusCode, err)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is VirtualMachineError) throw e
            MerchantReadResult.NetworkError(
                category = "NETWORK_ERROR",
                message = "Transport execution error: ${e.message}"
            )
        }
    }

    private fun parseErrorBody(body: String, fallbackCode: String, fallbackMessage: String): MerchantReadErrorResponse {
        return try {
            val rootObj = StrictJsonParser.parse(body).asObject()
            MerchantReadErrorResponse.fromStrictJson(rootObj)
        } catch (_: Exception) {
            MerchantReadErrorResponse(code = fallbackCode, message = fallbackMessage)
        }
    }
}
