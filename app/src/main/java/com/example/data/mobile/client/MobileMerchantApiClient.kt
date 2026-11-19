package com.example.data.mobile.client

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
            obj.properties["message"]?.asString()
                ?: obj.properties["error"]?.asString()
                ?: "HTTP $statusCode"
        } catch (_: Exception) {
            "HTTP $statusCode"
        }
    }

    // -------------------------------------------------------------------------
    // Products
    // -------------------------------------------------------------------------

    fun getProducts(companyId: ServerUlid, limit: Int = 30, cursor: String? = null): Result<ProductCatalogResponse> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products"
        val params = mutableMapOf("limit" to limit.coerceIn(1, 100).toString())
        if (cursor != null) params["cursor"] = cursor
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

    fun getProductMovements(companyId: ServerUlid, productId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/products/${productId.value}/movements"
        return execute(HttpMethod.GET, path)
    }

    fun adjustStock(
        companyId: ServerUlid,
        productId: ServerUlid,
        warehouseId: ServerUlid,
        delta: BigDecimal,
        reason: String
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
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    // -------------------------------------------------------------------------
    // Customers
    // -------------------------------------------------------------------------

    fun getCustomers(companyId: ServerUlid, limit: Int = 30, cursor: String? = null): Result<CustomerDirectoryResponse> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers"
        val params = mutableMapOf("limit" to limit.coerceIn(1, 100).toString())
        if (cursor != null) params["cursor"] = cursor
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
        creditLimit: BigDecimal?
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers"
        val payload = buildString {
            append("{")
            append("\"name_ar\":\"").append(escapeJson(nameAr)).append("\"")
            if (!phone.isNullOrBlank()) {
                append(",\"phone\":\"").append(escapeJson(phone)).append("\"")
            }
            if (creditLimit != null) {
                append(",\"credit_limit\":\"").append(creditLimit.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\"")
            }
            append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    fun recordCustomerPayment(
        companyId: ServerUlid,
        customerId: ServerUlid,
        amount: BigDecimal,
        cashAccountId: ServerUlid,
        note: String
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/payments"
        val payload = buildString {
            append("{")
            append("\"amount\":\"").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\",")
            append("\"money_account_id\":\"").append(cashAccountId.value).append("\",")
            append("\"note\":\"").append(escapeJson(note)).append("\"")
            append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    // -------------------------------------------------------------------------
    // Suppliers
    // -------------------------------------------------------------------------

    fun getSuppliers(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers"
        return execute(HttpMethod.GET, path)
    }

    fun getSupplier(companyId: ServerUlid, supplierId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}"
        return execute(HttpMethod.GET, path)
    }

    fun createSupplier(
        companyId: ServerUlid,
        nameAr: String,
        company: String?,
        phone: String?
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
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    fun recordSupplierPayment(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        amount: BigDecimal,
        cashAccountId: ServerUlid,
        note: String
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${supplierId.value}/payments"
        val payload = buildString {
            append("{")
            append("\"amount\":\"").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\",")
            append("\"money_account_id\":\"").append(cashAccountId.value).append("\",")
            append("\"note\":\"").append(escapeJson(note)).append("\"")
            append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    // -------------------------------------------------------------------------
    // Purchases & GRN
    // -------------------------------------------------------------------------

    fun getPurchases(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases"
        return execute(HttpMethod.GET, path)
    }

    fun createPurchase(
        companyId: ServerUlid,
        supplierId: ServerUlid,
        linesJson: String,
        paidNow: BigDecimal
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases"
        val payload = buildString {
            append("{")
            append("\"supplier_id\":\"").append(supplierId.value).append("\",")
            append("\"paid_now\":\"").append(paidNow.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\",")
            append("\"lines\":").append(linesJson)
            append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    fun receiveGoods(
        companyId: ServerUlid,
        purchaseId: ServerUlid,
        receivedLinesJson: String
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/purchases/${purchaseId.value}/grn"
        val payload = buildString {
            append("{\"received_lines\":").append(receivedLinesJson).append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
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
        categoryId: ServerUlid?,
        description: String
    ): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/expenses"
        val payload = buildString {
            append("{")
            append("\"amount\":\"").append(amount.setScale(2, RoundingMode.HALF_UP).toPlainString()).append("\",")
            append("\"description\":\"").append(escapeJson(description)).append("\"")
            if (categoryId != null) {
                append(",\"category_id\":\"").append(categoryId.value).append("\"")
            }
            append("}")
        }
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
    }

    // -------------------------------------------------------------------------
    // Reports & Settings
    // -------------------------------------------------------------------------

    fun getShiftSummary(companyId: ServerUlid): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/reports/shift-summary"
        return execute(HttpMethod.GET, path)
    }

    fun closeShift(companyId: ServerUlid, countedPhysicalCash: BigDecimal): Result<StrictJsonObject> {
        val path = "/api/mobile/v1/companies/${companyId.value}/reports/shift-close"
        val payload = "{\"counted_physical_cash\":\"${countedPhysicalCash.setScale(2, RoundingMode.HALF_UP).toPlainString()}\"}"
        return execute(HttpMethod.POST, path, body = payload, idempotencyKey = UUID.randomUUID().toString())
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
