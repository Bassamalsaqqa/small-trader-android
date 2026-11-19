package com.example.data

import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadResult
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.merchantread.SalesInvoicesQuery
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.model.BasketItem
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.ProductCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Pure Wire DTO Contract and Repository Integration Tests for ProductionTraderRepository Live Reads.
 *
 * UNEXECUTED: Designed as executable reference fixtures without requiring live Android/Gradle daemon execution.
 * Validates:
 * 1. Merging persisted server history with pending outbox invoices without dropping UNKNOWN_ACK events.
 * 2. Strict BigDecimal economics without Double conversion or synthetic fake basket items.
 * 3. Nullable balances and missing permissions (403 Forbidden) remaining null without fake 0 invention.
 * 4. On-demand single GET reads for customer statements and product movements (Zero N+1 full catalog queries).
 * 5. Bounded paging detection of has_more without cursor.
 * 6. Scoped generation token verification pre- and post-GET rejecting stale tokens.
 */
class ProductionTraderRepositoryReadWireTest {

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val customerId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val productId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"

    class QueueMockTransport : ReadClientTransport {
        val requests = mutableListOf<TransportRequest>()
        val responseQueue = mutableListOf<TransportResponse>()
        var defaultResponse = TransportResponse(200, emptyMap(), "{}")

        override fun execute(request: TransportRequest): TransportResponse {
            requests.add(request)
            return if (responseQueue.isNotEmpty()) responseQueue.removeAt(0) else defaultResponse
        }
    }

    private fun createRepository(
        transport: ReadClientTransport,
        generation: Long = 1L
    ): Pair<ProductionTraderRepository, MerchantReadClient> {
        val tokenProvider = MerchantReadTokenProvider { reqComp, reqGen ->
            if (reqComp == companyId && (reqGen == null || reqGen == generation)) validToken else null
        }

        val readClient = MerchantReadClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = tokenProvider
        )

        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = {
                CurrentAuthScope(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    token = validToken,
                    generation = generation
                )
            },
            merchantReadClient = readClient
        )

        return Pair(repo, readClient)
    }

    // ------------------------------------------------------------------------
    // 1. Invoices Refresh & Outbox Merge Test
    // ------------------------------------------------------------------------
    @Test
    fun testRefreshInvoices_MergesServerWithPendingOutboxWithoutDroppingUnknownAck() = runBlocking {
        val transport = QueueMockTransport()

        // 1. Response for sales invoices index
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "company_id": "${companyId.value}",
                    "count": 2,
                    "has_more": false,
                    "invoices": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789E",
                            "invoice_number": "INV-202611-0001",
                            "status": "posted",
                            "customer_id": "${customerId.value}",
                            "customer_name": "Tariq Hardware",
                            "date": "2026-11-18",
                            "subtotal": "250.00",
                            "discount": "0.00",
                            "tax": "0.00",
                            "total": "250.00",
                            "paid": "250.00",
                            "remaining": "0.00",
                            "currency": "ILS",
                            "currency_minor_units": 2,
                            "payment_status": "PAID_CASH",
                            "created_at": "2026-11-18T14:30:00Z"
                        },
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789F",
                            "invoice_number": "INV-202611-0002",
                            "status": "posted",
                            "customer_id": "${customerId.value}",
                            "customer_name": "Nablus Wholesale",
                            "date": "2026-11-18",
                            "subtotal": "800.00",
                            "discount": "50.00",
                            "tax": "0.00",
                            "total": "750.00",
                            "paid": "200.00",
                            "remaining": "550.00",
                            "currency": "ILS",
                            "currency_minor_units": 2,
                            "payment_status": "PARTIAL",
                            "created_at": "2026-11-18T15:10:00Z"
                        }
                    ]
                }
                """.trimIndent()
            )
        )

        val (repo, _) = createRepository(transport)

        // Seed local outbox with 1 pending sale and 1 unknown ACK sale
        val demoProduct = Product(
            id = productId.value,
            nameAr = "سكر 1 كغم",
            nameEn = "Sugar 1kg",
            category = ProductCategory.PANTRY,
            barcode = "7290001234567",
            priceExact = BigDecimal("10.00"),
            unitAr = "كغم",
            unitEn = "kg",
            stockCount = 100,
            accentHex = 0xFF2E7D32
        )

        val pendingSale = InvoiceRecord(
            id = "local-pending-uuid-001",
            invoiceNumber = 9991,
            timestamp = "2026-11-18 16:00:00",
            items = listOf(BasketItem(product = demoProduct, quantity = BigDecimal("2"))),
            subtotalExact = BigDecimal("20.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("20.00"),
            cashReceivedExact = BigDecimal("20.00"),
            changeReturnedExact = BigDecimal.ZERO,
            customerNote = "Local Pending Outbox",
            cashierNameAr = "كاشير",
            cashierNameEn = "Cashier",
            isPaidInFull = true,
            clientEventId = "550e8400-e29b-41d4-a716-446655440001",
            outboxStatus = "PENDING_SYNC"
        )

        val unknownAckSale = InvoiceRecord(
            id = "local-unknown-ack-uuid-002",
            invoiceNumber = 9992,
            timestamp = "2026-11-18 16:05:00",
            items = listOf(BasketItem(product = demoProduct, quantity = BigDecimal("5"))),
            subtotalExact = BigDecimal("50.00"),
            discountExact = BigDecimal.ZERO,
            netTotalExact = BigDecimal("50.00"),
            cashReceivedExact = BigDecimal("50.00"),
            changeReturnedExact = BigDecimal.ZERO,
            customerNote = "Unknown Ack Outbox",
            cashierNameAr = "كاشير",
            cashierNameEn = "Cashier",
            isPaidInFull = true,
            clientEventId = "550e8400-e29b-41d4-a716-446655440002",
            outboxStatus = "UNKNOWN_ACK"
        )

        // Pre-seed local repository invoices flow
        val invoicesFlowField = ProductionTraderRepository::class.java.getDeclaredField("_invoicesFlow")
        invoicesFlowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = invoicesFlowField.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<InvoiceRecord>>
        flow.value = listOf(pendingSale, unknownAckSale)

        // Execute refreshInvoices
        val result = repo.refreshInvoices(SalesInvoicesQuery(limit = 30))

        assertTrue(result.exceptionOrNull()?.toString() ?: "Expected success", result.isSuccess)
        val mergedList = result.getOrThrow()

        // 4 total: 2 pending/unknown local outbox + 2 server invoices
        assertEquals(4, mergedList.size)

        // Verify pending local outbox invoices were preserved without being dropped
        val pendingFound = mergedList.firstOrNull { it.id == "local-pending-uuid-001" }
        assertNotNull(pendingFound)
        assertEquals("PENDING_SYNC", pendingFound!!.outboxStatus)
        assertEquals(1, pendingFound.items.size)

        val unknownAckFound = mergedList.firstOrNull { it.id == "local-unknown-ack-uuid-002" }
        assertNotNull(unknownAckFound)
        assertEquals("UNKNOWN_ACK", unknownAckFound!!.outboxStatus)

        // Verify server-originated invoice: items must be empty list (NOT fake synthesized items!)
        val serverInv1 = mergedList.firstOrNull { it.serverInvoiceUlid == "01JCV8X9M2NQW3TYZ4R5S6789E" }
        assertNotNull(serverInv1)
        assertEquals("INV-202611-0001", serverInv1!!.serverInvoiceNumber)
        assertEquals(BigDecimal("250.00"), serverInv1.netTotalExact)
        assertEquals(BigDecimal("250.00"), serverInv1.cashReceivedExact)
        assertEquals(BigDecimal("0.00"), serverInv1.remainingDebitExact)
        assertTrue(serverInv1.isPaidInFull)
        assertTrue(serverInv1.items.isEmpty()) // Invariant: No synthetic basket lines

        // Verify partial invoice
        val serverInv2 = mergedList.firstOrNull { it.serverInvoiceUlid == "01JCV8X9M2NQW3TYZ4R5S6789F" }
        assertNotNull(serverInv2)
        assertEquals(BigDecimal("750.00"), serverInv2!!.netTotalExact)
        assertEquals(BigDecimal("200.00"), serverInv2.cashReceivedExact)
        assertEquals(BigDecimal("550.00"), serverInv2.remainingDebitExact)
        assertFalse(serverInv2.isPaidInFull)

        // Verify bounded paging gap status
        assertFalse(repo.invoicesHasMoreGapFlow.value)
    }

    // ------------------------------------------------------------------------
    // 2. Bounded Paging has_more Without Cursor Test
    // ------------------------------------------------------------------------
    @Test
    fun testRefreshInvoices_SurfacesBoundedPagingHasMoreWithoutCursorGap() = runBlocking {
        val transport = QueueMockTransport()
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "company_id": "${companyId.value}",
                    "count": 1,
                    "has_more": true,
                    "invoices": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789G",
                            "invoice_number": "INV-202611-0099",
                            "status": "posted",
                            "customer_id": "${customerId.value}",
                            "customer_name": null,
                            "date": "2026-11-18",
                            "subtotal": "100.00",
                            "discount": "0.00",
                            "tax": "0.00",
                            "total": "100.00",
                            "paid": "100.00",
                            "remaining": "0.00",
                            "currency": "ILS",
                            "currency_minor_units": 2,
                            "payment_status": "PAID_CASH",
                            "created_at": null
                        }
                    ]
                }
                """.trimIndent()
            )
        )

        val (repo, _) = createRepository(transport)
        val result = repo.refreshInvoices(SalesInvoicesQuery(limit = 1))

        assertTrue(result.exceptionOrNull()?.toString() ?: "Expected success", result.isSuccess)
        // has_more without cursor is surfaced faithfully
        assertTrue(repo.invoicesHasMoreGapFlow.value)

        // Ensure request did not invent offset or page parameter
        val req = transport.requests.last()
        assertEquals(HttpMethod.GET, req.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/sales/invoices", req.path)
        assertEquals("1", req.queryParams["limit"])
        assertNull(req.queryParams["offset"])
        assertNull(req.queryParams["page"])
        assertNull(req.queryParams["cursor"])
    }

    // ------------------------------------------------------------------------
    // 3. Home Dashboard Metrics: Null Preserved (No Fake Zeroes)
    // ------------------------------------------------------------------------
    @Test
    fun testRefreshHomeMetrics_Forbidden403PreservesNullNoFakeZeroes() = runBlocking {
        val transport = QueueMockTransport()
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 403,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "code": "FORBIDDEN",
                    "message": "Action is unauthorized. Missing required permissions."
                }
                """.trimIndent()
            )
        )

        val (repo, _) = createRepository(transport)
        val result = repo.refreshHomeMetrics()

        assertTrue(result.exceptionOrNull()?.toString() ?: "Expected success", result.isSuccess)
        // Must remain null; never coerced to 0.00 ₪
        assertNull(result.getOrThrow())
        assertNull(repo.homeMetricsFlow.value)
    }

    @Test
    fun testRefreshHomeMetrics_SuccessPreservesExactBigDecimalsAndNullDrawer() = runBlocking {
        val transport = QueueMockTransport()
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "company_id": "${companyId.value}",
                    "today": "2026-11-18",
                    "sales_today_count": 14,
                    "sales_today_total": "3420.50",
                    "cash_in_drawer": null,
                    "low_stock_count": 3,
                    "total_receivables": "8900.25",
                    "total_payables": null,
                    "recent_invoices": [],
                    "business_info": {
                        "name_ar": "سوبرماركت التاجر",
                        "name_en": "Trader Market",
                        "city_ar": "", "city_en": "", "phone": "", "tax_number": "",
                        "default_currency": "ILS",
                        "default_currency_minor_units": 2
                    }
                }
                """.trimIndent()
            )
        )

        val (repo, _) = createRepository(transport)
        val result = repo.refreshHomeMetrics()

        assertTrue(result.exceptionOrNull()?.toString() ?: "Expected success", result.isSuccess)
        val home = result.getOrThrow()
        assertNotNull(home)
        assertEquals(14, home!!.salesTodayCount)
        assertEquals(BigDecimal("3420.50"), home.salesTodayTotal)
        assertNull(home.cashInDrawer) // Invariant: Null preserved when drawer unauthorized
        assertEquals(BigDecimal("8900.25"), home.totalReceivables)
        assertNull(home.totalPayables) // Invariant: Null preserved
    }

    // ------------------------------------------------------------------------
    // 4. On-Demand Customer Statement (Zero N+1 Full Directory Queries)
    // ------------------------------------------------------------------------
    @Test
    fun testOnDemandCustomerStatement_RealGetWithoutNPlusOneFullCatalog() = runBlocking {
        val transport = QueueMockTransport()
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "customer_id": "${customerId.value}",
                    "customer_name": "Al-Arabi Trading",
                    "from_date": "2026-11-01",
                    "to_date": "2026-11-18",
                    "currencies": {
                        "ILS": {
                            "currency": "ILS",
                            "opening_balance": "1000.00",
                            "closing_balance": "1250.00",
                            "total_debits": "500.00",
                            "total_credits": "250.00",
                            "entries": [
                                {
                                    "date": "2026-11-05",
                                    "type": "invoice",
                                    "number": "INV-202611-0005",
                                    "reference": null,
                                    "description": "Sales invoice on credit",
                                    "debit": "500.00",
                                    "credit": "0.00",
                                    "balance": "1500.00"
                                },
                                {
                                    "date": "2026-11-10",
                                    "type": "payment",
                                    "number": "RCP-202611-0002",
                                    "reference": "cash-01",
                                    "description": "Customer cash payment",
                                    "debit": "0.00",
                                    "credit": "250.00",
                                    "balance": "1250.00"
                                }
                            ],
                            "aging": {
                                "current": "250.00",
                                "days_1_30": "1000.00",
                                "days_31_60": "0.00",
                                "days_61_90": "0.00",
                                "days_90_plus": "0.00",
                                "total": "1250.00"
                            }
                        }
                    }
                }
                """.trimIndent()
            )
        )

        val (repo, _) = createRepository(transport)

        // Call on-demand
        val result = repo.getCustomerStatement(customerId.value, fromDate = "2026-11-01", toDate = "2026-11-18")

        assertTrue(result.exceptionOrNull()?.toString() ?: "Expected success", result.isSuccess)
        val statement = result.getOrThrow()
        assertEquals(customerId, statement.customerId)
        assertEquals("Al-Arabi Trading", statement.customerName)

        // Verify exact single request was made
        assertEquals(1, transport.requests.size)
        val req = transport.requests.first()
        assertEquals(HttpMethod.GET, req.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/customers/${customerId.value}/statement", req.path)
        assertEquals("2026-11-01", req.queryParams["from_date"])
        assertEquals("2026-11-18", req.queryParams["to_date"])

        // Verify customerTransactionsFlow was populated truthfully
        val txs = repo.customerTransactionsFlow.value
        assertEquals(2, txs.size)
        assertEquals("فاتورة مبيعات", txs[0].typeAr)
        assertEquals(BigDecimal("500.00"), txs[0].debitExact)
        assertEquals(BigDecimal("1500.00"), txs[0].runningBalanceExact)
        assertEquals("سداد دفعة نقداً", txs[1].typeAr)
        assertEquals(BigDecimal("250.00"), txs[1].creditExact)
        assertEquals(BigDecimal("1250.00"), txs[1].runningBalanceExact)
    }

    // ------------------------------------------------------------------------
    // 5. On-Demand Product Stock Movement History (Zero N+1 Full Catalog)
    // ------------------------------------------------------------------------
    @Test
    fun testOnDemandProductStockHistory_RealGetWithoutNPlusOneFullCatalog() = runBlocking {
        val transport = QueueMockTransport()
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "product_id": "${productId.value}",
                    "movements": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789H",
                            "movement_type": "manual_adjustment",
                            "delta": "-3.0000",
                            "date": "2026-11-18",
                            "reason": "Expired batch",
                            "resulting_stock": "47.0000"
                        }
                    ]
                }
                """.trimIndent()
            )
        )

        val (repo, _) = createRepository(transport)

        // Call on-demand
        val result = repo.getProductStockHistory(productId.value, limit = 50)

        assertTrue(result.exceptionOrNull()?.toString() ?: "Expected success", result.isSuccess)
        val history = result.getOrThrow()
        assertEquals(productId, history.productId)
        assertEquals(1, history.movements.size)

        // Verify single request made
        assertEquals(1, transport.requests.size)
        val req = transport.requests.first()
        assertEquals(HttpMethod.GET, req.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/products/${productId.value}/movements", req.path)
        assertEquals("50", req.queryParams["limit"])

        // Verify stockMovementsFlow was updated
        val movements = repo.stockMovementsFlow.value
        assertEquals(1, movements.size)
        assertEquals("تسوية مخزنية", movements[0].reasonAr)
        assertEquals(BigDecimal("-3.0000"), movements[0].deltaExact)
        assertEquals(BigDecimal("47.0000"), movements[0].resultingStockExact)
    }

    // ------------------------------------------------------------------------
    // 6. Scoped Generation Token Verification Pre- and Post-GET
    // ------------------------------------------------------------------------
    @Test
    fun testTokenProvider_RejectsGenerationChangePreAndPostGet() = runBlocking {
        var currentGen = 1L
        val transport = QueueMockTransport()
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "company_id": "${companyId.value}",
                    "today": "2026-11-18",
                    "sales_today_count": 5,
                    "sales_today_total": "100.00",
                    "cash_in_drawer": "100.00",
                    "low_stock_count": 0,
                    "total_receivables": "0.00",
                    "total_payables": "0.00",
                    "recent_invoices": [],
                    "business_info": {
                        "name_ar": "Company",
                        "name_en": "Company",
                        "city_ar": "", "city_en": "", "phone": "", "tax_number": "",
                        "default_currency": "ILS",
                        "default_currency_minor_units": 2
                    }
                }
                """.trimIndent()
            )
        )

        // Token provider strictly checks that requested generation matches active generation
        val dynamicTokenProvider = MerchantReadTokenProvider { reqComp, reqGen ->
            if (reqComp == companyId && (reqGen == null || reqGen == currentGen)) "test-only-token-for-generation-$currentGen" else null
        }

        val client = MerchantReadClient(
            transport = object : ReadClientTransport {
                override fun execute(request: TransportRequest): TransportResponse {
                    // Simulate generation rotation (e.g. concurrent logout or session switch) while GET in-flight
                    currentGen = 2L
                    return TransportResponse(
                        200,
                        mapOf("Content-Type" to "application/json"),
                        """
                        {
                            "company_id": "${companyId.value}",
                            "today": "2026-11-18",
                            "sales_today_count": 5,
                            "sales_today_total": "100.00",
                            "cash_in_drawer": "100.00",
                            "low_stock_count": 0,
                            "total_receivables": "0.00",
                            "total_payables": "0.00",
                            "recent_invoices": [],
                            "business_info": {
                                "name_ar": "Company",
                                "name_en": "Company",
                                "city_ar": "", "city_en": "", "phone": "", "tax_number": "",
                        "default_currency": "ILS",
                                "default_currency_minor_units": 2
                            }
                        }
                        """.trimIndent()
                    )
                }
            },
            baseOrigin = "https://api.example.com",
            tokenProvider = dynamicTokenProvider
        )

        // Read initiated under generation 1L
        val res = client.getHome(companyId, retainedGeneration = 1L)

        // Must fail with AuthDenial because generation changed during GET execution (NO fallback to old token!)
        assertTrue(res is MerchantReadResult.AuthDenial)
        assertEquals(401, (res as MerchantReadResult.AuthDenial).statusCode)
        assertEquals("UNAUTHENTICATED", res.error.code)
        assertTrue(res.error.message.contains("generation changed"))
    }
}
