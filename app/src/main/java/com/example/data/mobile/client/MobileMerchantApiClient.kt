package com.example.data.mobile.client

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonNull
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.http.HttpTransportException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import com.example.model.PaymentAllocationInput
import com.example.model.WesternFormatters

/**
 * Typed client for native Mobile V1 and V2 merchant domain endpoints.
 * All requests enforce strict endpoint security allowlisting, token authorization,
 * exact decimal serialization without floating point conversion, and safe error parsing.
 */
class MobileMerchantApiClient(
    private val transport: ReadClientTransport,
    val baseOrigin: String,
    private val tokenProvider: () -> String
) {

    private fun authHeaders(idempotencyKey: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer ${tokenProvider()}"
        )
        if (idempotencyKey != null) {
            headers["Idempotency-Key"] = idempotencyKey
        }
        return headers
    }

    private fun execute(
        method: HttpMethod,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
        queryParams: Map<String, String> = emptyMap()
    ): Result<StrictJsonObject> {
        val request = TransportRequest(
            method = method,
            path = path,
            baseOrigin = baseOrigin,
            queryParams = queryParams,
            headers = authHeaders(idempotencyKey),
            body = body
        )
        return try {
            val response = transport.execute(request)
            if (response.statusCode in 200..299) {
                if (response.body.isBlank()) {
                    Result.success(StrictJsonObject(emptyMap()))
                } else {
                    val root = StrictJsonParser.parse(response.body)
                    if (root is StrictJsonObject) {
                        Result.success(root)
                    } else {
                        Result.success(StrictJsonObject(mapOf("data" to root)))
                    }
                }
            } else {
                val errorMsg = parseErrorMessage(response.body, response.statusCode)
                Result.failure(HttpTransportException(HttpTransportException.CATEGORY_SERVER_ERROR, errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseErrorMessage(body: String, statusCode: Int): String {
        return try {
            val obj = StrictJsonParser.parseObject(body)
            val errProp = obj.properties["error"]
            val msgFromErr = when (errProp) {
                is StrictJsonObject -> (errProp.properties["message"] as? StrictJsonString)?.value
                    ?: (errProp.properties["code"] as? StrictJsonString)?.value
                is StrictJsonString -> errProp.value
                else -> null
            }
            (obj.properties["message"] as? StrictJsonString)?.value
                ?: msgFromErr
                ?: "HTTP $statusCode"
        } catch (_: Exception) {
            "HTTP $statusCode"
        }
    }

    // -------------------------------------------------------------------------
    // Products
    // -------------------------------------------------------------------------

    fun getProducts(companyId: ServerUlid, limit: Int = 30, cursor: String? = null, search: String? = null): Result<ProductCatalogResponse> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products"
        val params = mutableMapOf("limit" to limit.coerceIn(1, 100).toString())
        if (cursor != null) params["cursor"] = cursor
        if (search != null && search.isNotBlank()) params["search"] = search.trim()
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = baseOrigin,
            queryParams = params,
            headers = authHeaders()
        )
        return try {
            val response = transport.execute(request)
            if (response.statusCode in 200..299) {
                val obj = StrictJsonParser.parseObject(response.body)
                Result.success(ProductCatalogResponse.fromStrictJson(obj))
            } else {
                Result.failure(HttpTransportException(HttpTransportException.CATEGORY_SERVER_ERROR, parseErrorMessage(response.body, response.statusCode)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createProduct(
        companyId: ServerUlid,
        nameAr: String,
        nameEn: String,
        barcode: String,
        priceIls: BigDecimal,
        costIls: BigDecimal?,
        unitId: ServerUlid?
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products"
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(nameAr)).append("\",")
            append("\"name_en\":\"").append(escapeJson(nameEn)).append("\",")
            append("\"barcode\":\"").append(escapeJson(barcode)).append("\",")
            append("\"price_ils\":\"").append(priceIls.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\"")
            if (costIls != null) {
                append(",\"cost_ils\":\"").append(costIls.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\"")
            }
            if (unitId != null) {
                append(",\"base_unit_id\":\"").append(unitId.value).append("\"")
            }
            append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    fun updateProduct(
        companyId: ServerUlid,
        productId: ServerUlid,
        nameAr: String,
        nameEn: String,
        barcode: String,
        priceIls: BigDecimal,
        costIls: BigDecimal?
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products/${productId.value}"
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(nameAr)).append("\",")
            append("\"name_en\":\"").append(escapeJson(nameEn)).append("\",")
            append("\"barcode\":\"").append(escapeJson(barcode)).append("\",")
            append("\"price_ils\":\"").append(priceIls.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\"")
            if (costIls != null) {
                append(",\"cost_ils\":\"").append(costIls.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\"")
            }
            append("}")
        }
        return execute(HttpMethod.PUT, path, body = payload)
    }

    fun getProduct(companyId: ServerUlid, productId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products/${productId.value}"
        return execute(HttpMethod.GET, path)
    }

    fun getProductMovements(companyId: ServerUlid, productId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products/${productId.value}/movements"
        return execute(HttpMethod.GET, path)
    }

    fun adjustStock(
        companyId: ServerUlid,
        productId: ServerUlid,
        warehouseId: ServerUlid,
        delta: BigDecimal,
        reason: String,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/inventory/adjustments"
        val payload = buildString {
            append("{")
            append("\"product_id\":\"").append(productId.value).append("\",")
            append("\"warehouse_id\":\"").append(warehouseId.value).append("\",")
            append("\"delta\":\"").append(delta.stripTrailingZeros().toPlainString()).append("\",")
            append("\"reason\":\"").append(escapeJson(reason)).append("\"")
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    // -------------------------------------------------------------------------
    // Customers
    // -------------------------------------------------------------------------

    fun getCustomers(companyId: ServerUlid, limit: Int = 30, cursor: String? = null, search: String? = null): Result<CustomerDirectoryResponse> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers"
        val params = mutableMapOf("limit" to limit.coerceIn(1, 100).toString())
        if (cursor != null) params["cursor"] = cursor
        if (search != null && search.isNotBlank()) params["search"] = search.trim()
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = baseOrigin,
            queryParams = params,
            headers = authHeaders()
        )
        return try {
            val response = transport.execute(request)
            if (response.statusCode in 200..299) {
                val obj = StrictJsonParser.parseObject(response.body)
                Result.success(CustomerDirectoryResponse.fromStrictJson(obj))
            } else {
                Result.failure(HttpTransportException(HttpTransportException.CATEGORY_SERVER_ERROR, parseErrorMessage(response.body, response.statusCode)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getCustomer(companyId: ServerUlid, customerId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}"
        return execute(HttpMethod.GET, path)
    }

    fun createCustomer(
        companyId: ServerUlid,
        nameAr: String,
        phone: String?,
        creditLimit: BigDecimal?,
        address: String? = null,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers"
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(nameAr)).append("\"")
            if (!phone.isNullOrBlank()) {
                append(",\"phone\":\"").append(escapeJson(phone)).append("\"")
            }
            if (!address.isNullOrBlank()) {
                append(",\"address_ar\":\"").append(escapeJson(address)).append("\"")
            }
            if (creditLimit != null) {
                append(",\"credit_limit\":\"").append(creditLimit.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\"")
            }
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    fun updateCustomer(
        companyId: ServerUlid,
        customerId: ServerUlid,
        name: String,
        phone: String? = null,
        address: String? = null,
        creditLimit: BigDecimal? = null,
        updatePhone: Boolean = false,
        updateAddress: Boolean = false,
        updateCreditLimit: Boolean = false,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(name)).append("\"")
            if (updatePhone) {
                if (phone.isNullOrBlank()) {
                    append(",\"phone\":\"\"")
                } else {
                    append(",\"phone\":\"").append(escapeJson(phone)).append("\"")
                }
            }
            if (updateAddress) {
                if (address.isNullOrBlank()) {
                    append(",\"address_ar\":null,\"address_en\":null,\"address\":null")
                } else {
                    append(",\"address_ar\":\"").append(escapeJson(address)).append("\"")
                    append(",\"address\":\"").append(escapeJson(address)).append("\"")
                }
            }
            if (updateCreditLimit) {
                if (creditLimit != null) {
                    append(",\"credit_limit\":\"").append(creditLimit.toPlainString()).append("\"")
                } else {
                    append(",\"credit_limit\":null")
                }
            }
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.PUT, "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}", body = payload, idempotencyKey = key)
    }

    fun updateSupplier(companyId: ServerUlid, supplierId: ServerUlid, name: String, phone: String?): Result<StrictJsonObject> {
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(name)).append("\"")
            run {
                append(",\"phone\":\"").append(escapeJson(phone.orEmpty())).append("\"")
            }
            append("}")
        }
        return execute(HttpMethod.PUT, "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}", body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    fun getCustomerSettlementTargets(
        companyId: ServerUlid,
        customerId: ServerUlid,
        currency: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ): Result<StrictJsonObject> {
        val qParams = mutableMapOf<String, String>()
        if (!currency.isNullOrBlank()) {
            val trimmedCurr = currency.trim().uppercase()
            com.example.data.mobile.readclient.EndpointSecurity.validateCurrency(trimmedCurr)
            qParams["currency"] = trimmedCurr
        }
        if (limit != null) {
            com.example.data.mobile.readclient.EndpointSecurity.validateLimit(limit, maxLimit = 250)
            qParams["limit"] = limit.toString()
        }
        if (!cursor.isNullOrBlank()) {
            com.example.data.mobile.readclient.EndpointSecurity.validateCursor(cursor)
            qParams["cursor"] = cursor
        }
        val path = "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/settlement-targets"
        return execute(HttpMethod.GET, path, queryParams = qParams)
    }

    fun getCustomerOutstanding(
        companyId: ServerUlid,
        customerId: ServerUlid,
        currency: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ): Result<StrictJsonObject> {
        val qParams = mutableMapOf<String, String>()
        if (!currency.isNullOrBlank()) {
            val trimmedCurr = currency.trim().uppercase()
            com.example.data.mobile.readclient.EndpointSecurity.validateCurrency(trimmedCurr)
            qParams["currency"] = trimmedCurr
        }
        if (limit != null) {
            com.example.data.mobile.readclient.EndpointSecurity.validateLimit(limit, maxLimit = 250)
            qParams["limit"] = limit.toString()
        }
        if (!cursor.isNullOrBlank()) {
            com.example.data.mobile.readclient.EndpointSecurity.validateCursor(cursor)
            qParams["cursor"] = cursor
        }
        val path = "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/outstanding"
        return execute(HttpMethod.GET, path, queryParams = qParams)
    }

    fun recordCustomerPayment(
        companyId: ServerUlid,
        customerId: ServerUlid,
        amount: BigDecimal,
        cashAccountId: ServerUlid?,
        note: String,
        idempotencyKey: String? = null,
        paymentDate: String? = null,
        allocations: List<PaymentAllocationInput> = emptyList(),
        currency: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/payments"
        val payload = buildString {
            append("{")
            append("\"amount\":\"").append(amount.setScale(6, RoundingMode.HALF_UP).toPlainString()).append("\",")
            if (!currency.isNullOrBlank()) {
                append("\"currency\":\"").append(escapeJson(currency)).append("\",")
            }
            val pDate = paymentDate ?: WesternFormatters.currentDate()
            append("\"payment_date\":\"").append(escapeJson(pDate)).append("\",")
            if (cashAccountId != null) {
                append("\"money_account_id\":\"").append(cashAccountId.value).append("\",")
            }
            append("\"notes\":\"").append(escapeJson(note)).append("\",")
            append("\"note\":\"").append(escapeJson(note)).append("\",")
            append("\"allocations\":[")
            allocations.forEachIndexed { idx, alloc ->
                if (idx > 0) append(",")
                append("{")
                append("\"target_type\":\"").append(escapeJson(alloc.targetType)).append("\",")
                append("\"target_id\":\"").append(escapeJson(alloc.targetId)).append("\",")
                append("\"amount\":\"").append(alloc.amountExact.setScale(6, RoundingMode.HALF_UP).toPlainString()).append("\"")
                append("}")
            }
            append("]")
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    // -------------------------------------------------------------------------
    // Suppliers
    // -------------------------------------------------------------------------

    fun getSuppliers(companyId: ServerUlid, search: String? = null, limit: Int = 30): Result<StrictJsonObject> {
        val qParams = mutableMapOf<String, String>()
        if (!search.isNullOrBlank()) {
            val trimmed = search.trim()
            com.example.data.mobile.readclient.EndpointSecurity.validateSearchQuery(trimmed)
            qParams["search"] = trimmed
        }
        if (limit != 30) {
            com.example.data.mobile.readclient.EndpointSecurity.validateLimit(limit)
            qParams["limit"] = limit.toString()
        }
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers"
        return execute(HttpMethod.GET, path, queryParams = qParams)
    }

    fun getSupplier(companyId: ServerUlid, supplierId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}"
        return execute(HttpMethod.GET, path)
    }

    fun createSupplier(
        companyId: ServerUlid,
        nameAr: String,
        company: String?,
        phone: String?,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers"
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(nameAr)).append("\"")
            if (!company.isNullOrBlank()) {
                append(",\"company_name\":\"").append(escapeJson(company)).append("\"")
            }
            if (!phone.isNullOrBlank()) {
                append(",\"phone\":\"").append(escapeJson(phone)).append("\"")
            }
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    fun getSupplierSettlementTargets(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        currency: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ): Result<StrictJsonObject> {
        val qParams = mutableMapOf<String, String>()
        if (!currency.isNullOrBlank()) {
            val trimmedCurr = currency.trim().uppercase()
            com.example.data.mobile.readclient.EndpointSecurity.validateCurrency(trimmedCurr)
            qParams["currency"] = trimmedCurr
        }
        if (limit != null) {
            com.example.data.mobile.readclient.EndpointSecurity.validateLimit(limit, maxLimit = 250)
            qParams["limit"] = limit.toString()
        }
        if (!cursor.isNullOrBlank()) {
            com.example.data.mobile.readclient.EndpointSecurity.validateCursor(cursor)
            qParams["cursor"] = cursor
        }
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}/settlement-targets"
        return execute(HttpMethod.GET, path, queryParams = qParams)
    }

    fun getSupplierOutstanding(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        currency: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ): Result<StrictJsonObject> {
        val qParams = mutableMapOf<String, String>()
        if (!currency.isNullOrBlank()) {
            val trimmedCurr = currency.trim().uppercase()
            com.example.data.mobile.readclient.EndpointSecurity.validateCurrency(trimmedCurr)
            qParams["currency"] = trimmedCurr
        }
        if (limit != null) {
            com.example.data.mobile.readclient.EndpointSecurity.validateLimit(limit, maxLimit = 250)
            qParams["limit"] = limit.toString()
        }
        if (!cursor.isNullOrBlank()) {
            com.example.data.mobile.readclient.EndpointSecurity.validateCursor(cursor)
            qParams["cursor"] = cursor
        }
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}/outstanding"
        return execute(HttpMethod.GET, path, queryParams = qParams)
    }

    fun recordSupplierPayment(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        amount: BigDecimal,
        cashAccountId: ServerUlid?,
        note: String,
        idempotencyKey: String? = null,
        paymentDate: String? = null,
        allocations: List<PaymentAllocationInput> = emptyList(),
        currency: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}/payments"
        val payload = buildString {
            append("{")
            append("\"amount\":\"").append(amount.setScale(6, RoundingMode.HALF_UP).toPlainString()).append("\",")
            if (!currency.isNullOrBlank()) {
                append("\"currency\":\"").append(escapeJson(currency)).append("\",")
            }
            val pDate = paymentDate ?: WesternFormatters.currentDate()
            append("\"payment_date\":\"").append(escapeJson(pDate)).append("\",")
            if (cashAccountId != null) {
                append("\"money_account_id\":\"").append(cashAccountId.value).append("\",")
            }
            append("\"notes\":\"").append(escapeJson(note)).append("\",")
            append("\"note\":\"").append(escapeJson(note)).append("\",")
            append("\"allocations\":[")
            allocations.forEachIndexed { idx, alloc ->
                if (idx > 0) append(",")
                append("{")
                append("\"target_type\":\"").append(escapeJson(alloc.targetType)).append("\",")
                append("\"target_id\":\"").append(escapeJson(alloc.targetId)).append("\",")
                append("\"amount\":\"").append(alloc.amountExact.setScale(6, RoundingMode.HALF_UP).toPlainString()).append("\"")
                append("}")
            }
            append("]")
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    // -------------------------------------------------------------------------
    // Purchases & GRN
    // -------------------------------------------------------------------------

    fun getPurchases(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases"
        return execute(HttpMethod.GET, path)
    }

    fun getPurchase(companyId: ServerUlid, purchaseId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases/${purchaseId.value}"
        return execute(HttpMethod.GET, path)
    }

    fun createPurchase(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        linesJson: String,
        paidNow: BigDecimal = BigDecimal.ZERO,
        billNumber: String? = null,
        accountId: ServerUlid? = null,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases"
        val payload = buildString {
            append("{")
            append("\"supplier_id\":\"").append(supplierId.value).append("\",")
            append("\"paid_now\":\"").append(paidNow.setScale(6, RoundingMode.HALF_UP).toPlainString()).append("\"")
            if (!billNumber.isNullOrBlank()) {
                append(",\"bill_number\":\"").append(escapeJson(billNumber)).append("\"")
            }
            if (accountId != null) {
                append(",\"account_id\":\"").append(accountId.value).append("\"")
            }
            append(",\"lines\":").append(linesJson)
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    fun receiveGoods(
        companyId: ServerUlid,
        purchaseId: ServerUlid,
        linesJson: String,
        destinationWarehouseId: ServerUlid? = null,
        receiptDate: String? = null,
        notes: String? = null,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases/${purchaseId.value}/grn"
        val payload = buildString {
            append("{")
            append("\"lines\":").append(linesJson)
            if (destinationWarehouseId != null) {
                append(",\"destination_warehouse_id\":\"").append(destinationWarehouseId.value).append("\"")
            }
            if (!receiptDate.isNullOrBlank()) {
                append(",\"receipt_date\":\"").append(escapeJson(receiptDate)).append("\"")
            }
            if (!notes.isNullOrBlank()) {
                append(",\"notes\":\"").append(escapeJson(notes)).append("\"")
            }
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    // -------------------------------------------------------------------------
    // Expenses
    // -------------------------------------------------------------------------

    fun getExpenses(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/expenses"
        return execute(HttpMethod.GET, path)
    }

    fun getExpenseCategories(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/expenses/categories"
        return execute(HttpMethod.GET, path)
    }

    fun createExpense(
        companyId: ServerUlid,
        amount: BigDecimal,
        categoryId: ServerUlid,
        description: String,
        idempotencyKey: String? = null
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/expenses"
        val payload = buildString {
            append("{")
            append("\"amount\":\"").append(amount.setScale(6, RoundingMode.HALF_UP).toPlainString()).append("\",")
            append("\"expense_category_id\":\"").append(categoryId.value).append("\",")
            append("\"category_id\":\"").append(categoryId.value).append("\",")
            append("\"description\":\"").append(escapeJson(description)).append("\"")
            append("}")
        }
        val key = idempotencyKey ?: UUID.randomUUID().toString()
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = key)
    }

    // -------------------------------------------------------------------------
    // Command Receipts Status
    // -------------------------------------------------------------------------

    sealed class ReceiptStatusResult {
        data class Found(val snapshot: StrictJsonObject) : ReceiptStatusResult()
        object NotFound : ReceiptStatusResult()
        data class Error(val exception: Throwable) : ReceiptStatusResult()
    }

    fun getReceiptStatus(
        companyId: ServerUlid,
        idempotencyKey: String
    ): ReceiptStatusResult {
        com.example.data.mobile.readclient.EndpointSecurity.validateIdempotencyKey(idempotencyKey)
        val path = "/api/mobile/v1/companies/${companyId.value}/receipts/status"
        val request = TransportRequest(
            method = HttpMethod.GET,
            path = path,
            baseOrigin = baseOrigin,
            queryParams = mapOf("idempotency_key" to idempotencyKey),
            headers = authHeaders()
        )
        return try {
            val response = transport.execute(request)
            when (response.statusCode) {
                200 -> {
                    val obj = StrictJsonParser.parseObject(response.body)
                    ReceiptStatusResult.Found(obj)
                }
                404 -> ReceiptStatusResult.NotFound
                else -> {
                    val errorMsg = parseErrorMessage(response.body, response.statusCode)
                    ReceiptStatusResult.Error(HttpTransportException(HttpTransportException.CATEGORY_SERVER_ERROR, errorMsg))
                }
            }
        } catch (e: Exception) {
            ReceiptStatusResult.Error(e)
        }
    }

    // -------------------------------------------------------------------------
    // Reports & Settings
    // -------------------------------------------------------------------------

    fun getShiftSummary(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/reports/shift-summary"
        return execute(HttpMethod.GET, path)
    }

    fun closeShift(
        companyId: ServerUlid,
        countedCash: BigDecimal,
        idempotencyKey: String = UUID.randomUUID().toString(),
        cashAccountId: ServerUlid? = null,
        notes: String? = null,
        currencyMinorUnits: Int = 2
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/reports/shift-close"
        if (countedCash.signum() < 0) {
            return Result.failure(IllegalArgumentException("Counted physical cash must be non-negative"))
        }
        if (countedCash.scale() > currencyMinorUnits) {
            return Result.failure(IllegalArgumentException("Counted physical cash precision (${countedCash.scale()}) exceeds currency minor units ($currencyMinorUnits)"))
        }
        val jsonMap = mutableMapOf<String, StrictJsonElement>(
            "counted_cash" to StrictJsonString(countedCash.toPlainString())
        )
        if (cashAccountId != null) {
            jsonMap["cash_account_id"] = StrictJsonString(cashAccountId.value)
        }
        if (!notes.isNullOrBlank()) {
            jsonMap["notes"] = StrictJsonString(notes.trim())
        }
        val payload = CanonicalJsonSerializer.serialize(StrictJsonObject(jsonMap))
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = idempotencyKey)
    }

    fun getBusinessSettings(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/settings/business"
        return execute(HttpMethod.GET, path)
    }

    private fun escapeJson(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
