package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.http.HttpTransportException
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.PaymentAllocationInput
import com.example.model.SettlementTarget
import com.example.model.SettlementTargetsResponse
import com.example.model.UserRole
import com.example.model.UserSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettlementTargetsRealOkHttpSecurityTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var server: MockWebServer? = null
    private var baseOrigin: String = ""
    private val cryptoEngine = JvmAesGcmCryptoEngine()

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val targetCustomerId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val targetSupplierId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"
    private val validScope = CurrentAuthScope(companyId, actorId, deviceId, token = validToken, generation = 1L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_settlement_security_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()

        val s = MockWebServer()
        s.start()
        val port = s.port
        baseOrigin = "http://127.0.0.1:$port"
        this.server = s
        ProductionTraderRepository.clearInFlightForTest()
    }

    @After
    fun tearDown() {
        ProductionTraderRepository.clearInFlightForTest()
        server?.shutdown()
        server = null
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 1. Real OkHttp + MockWebServer: Valid Customer & Supplier Reads
    // -------------------------------------------------------------------------

    @Test
    fun testSettlementTargetsAndOutstanding_realOkHttpMockWebServer_validParamsAllowed() = runBlocking {
        val targetsBody = """
            {
                "customer_id": "${targetCustomerId.value}",
                "customer_name": "شركة النجاح للتجارة",
                "currency": "ILS",
                "currency_minor_units": 2,
                "total_targets": 1,
                "targets": [
                    {
                        "target_type": "invoice",
                        "target_id": "01JCVINV000000000000000010",
                        "target_number": "INV-2026-00042",
                        "business_date": "2026-02-01",
                        "currency": "ILS",
                        "currency_minor_units": 2,
                        "original_amount_exact": "750.000000",
                        "allocated_amount_exact": "250.000000",
                        "remaining_amount_exact": "500.000000"
                    }
                ],
                "pagination": {
                    "limit": 50,
                    "has_more": false,
                    "next_cursor": null
                },
                "totals": {
                    "remaining_by_currency": {
                        "ILS": {
                            "amount_exact": "500.000000",
                            "minor_units": 2
                        }
                    }
                }
            }
        """.trimIndent()

        // Enqueue responses for customer settlement-targets, customer outstanding, supplier settlement-targets, supplier outstanding
        server!!.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(targetsBody))
        server!!.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(targetsBody))
        server!!.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(targetsBody))
        server!!.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(targetsBody))

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        // 1. Customer settlement-targets with currency, limit=50, cursor
        val custTargetsRes = SettlementTargetsResponse.fromStrictJson(
            apiClient.getCustomerSettlementTargets(
                companyId = companyId,
                customerId = targetCustomerId,
                currency = "ILS",
                limit = 50,
                cursor = "c29tZV9jdXJzb3I"
            ).getOrThrow()
        )

        val req1 = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", req1.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/settlement-targets?currency=ILS&cursor=c29tZV9jdXJzb3I&limit=50", req1.path)
        assertEquals("Bearer $validToken", req1.getHeader("Authorization"))
        assertEquals("application/json", req1.getHeader("Accept"))
        assertEquals(1, custTargetsRes.targets.size)
        assertEquals(BigDecimal("500.000000"), custTargetsRes.targets[0].remainingAmountExact)

        // 2. Customer outstanding alias endpoint with limit=250 (max allowed)
        val custOutRes = apiClient.getCustomerOutstanding(
            companyId = companyId,
            customerId = targetCustomerId,
            currency = "JOD",
            limit = 250
        ).getOrThrow()

        val req2 = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/outstanding?currency=JOD&limit=250", req2.path)

        // 3. Supplier settlement-targets
        val supTargetsRes = apiClient.getSupplierSettlementTargets(
            companyId = companyId,
            supplierId = targetSupplierId,
            currency = "USD",
            limit = 100
        ).getOrThrow()

        val req3 = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/mobile/v1/companies/${companyId.value}/suppliers/${targetSupplierId.value}/settlement-targets?currency=USD&limit=100", req3.path)

        // 4. Supplier outstanding alias
        val supOutRes = apiClient.getSupplierOutstanding(
            companyId = companyId,
            supplierId = targetSupplierId
        ).getOrThrow()

        val req4 = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/mobile/v1/companies/${companyId.value}/suppliers/${targetSupplierId.value}/outstanding", req4.path)
    }

    // -------------------------------------------------------------------------
    // 2. EndpointSecurity Rejection of Invalid Query Parameters (Before Wire)
    // -------------------------------------------------------------------------

    @Test
    fun testSettlementTargets_invalidQueryParameters_rejectedByEndpointSecurityBeforeWire() {
        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)

        // Invalid currency: not 3 uppercase letters
        assertThrows(HttpTransportException::class.java) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = "/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/settlement-targets",
                baseOrigin = baseOrigin,
                queryParams = mapOf("currency" to "INVALID_CURR"),
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $validToken")
            )
            transport.execute(req)
        }
        assertThrows(HttpTransportException::class.java) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = "/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/settlement-targets",
                baseOrigin = baseOrigin,
                queryParams = mapOf("currency" to "ils"), // lowercase prohibited
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $validToken")
            )
            transport.execute(req)
        }

        // Invalid limit: 0 or > 250
        assertThrows(HttpTransportException::class.java) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = "/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/settlement-targets",
                baseOrigin = baseOrigin,
                queryParams = mapOf("limit" to "0"),
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $validToken")
            )
            transport.execute(req)
        }
        assertThrows(HttpTransportException::class.java) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = "/api/mobile/v1/companies/${companyId.value}/suppliers/${targetSupplierId.value}/settlement-targets",
                baseOrigin = baseOrigin,
                queryParams = mapOf("limit" to "251"), // maximum is 250 per contract
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $validToken")
            )
            transport.execute(req)
        }

        // Disallowed query parameter (e.g. SQL/script injection or unexpected param)
        assertThrows(HttpTransportException::class.java) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = "/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/settlement-targets",
                baseOrigin = baseOrigin,
                queryParams = mapOf("malicious_extra" to "1"),
                headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $validToken")
            )
            transport.execute(req)
        }

        // Zero requests should have reached MockWebServer
        assertEquals("Invalid requests must be rejected before touching network socket", 0, server!!.requestCount)
    }

    // -------------------------------------------------------------------------
    // 3. Typed Allocation POST: Real Wire Inspection & Exact Decimal Strings
    // -------------------------------------------------------------------------

    @Test
    fun testTypedAllocationPost_realWireInspection_andExactBigDecimalStrings() = runBlocking {
        val ackResponse = """
            {
                "success": true,
                "id": "01JCV8X9M2PAYACK0000000001",
                "payment_number": "PAY-2026-00015",
                "amount_exact": "500.000000",
                "currency": "ILS",
                "customer_id": "${targetCustomerId.value}",
                "is_advance": false,
                "allocated_amount_exact": "500.000000",
                "unallocated_amount_exact": "0.000000",
                "allocations": [
                    {
                        "target_type": "opening_trade_item",
                        "target_id": "01JM7XYZ000000000000000010",
                        "amount_exact": "200.000000",
                        "allocated_amount_exact": "200.000000"
                    },
                    {
                        "target_type": "invoice",
                        "target_id": "01JM7XYZ000000000000000020",
                        "amount_exact": "300.000000",
                        "allocated_amount_exact": "300.000000"
                    }
                ]
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(ackResponse)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val allocations = listOf(
            PaymentAllocationInput(
                targetType = "opening_trade_item",
                targetId = "01JM7XYZ000000000000000010",
                amountExact = BigDecimal("200.000000")
            ),
            PaymentAllocationInput(
                targetType = "invoice",
                targetId = "01JM7XYZ000000000000000020",
                amountExact = BigDecimal("300.000000")
            )
        )

        val res = apiClient.recordCustomerPayment(
            companyId = companyId,
            customerId = targetCustomerId,
            amount = BigDecimal("500.000000"),
            cashAccountId = null,
            note = "سداد رصيد افتتاحي وفاتورة",
            paymentDate = "2026-03-15",
            allocations = allocations,
            idempotencyKey = "idemp_alloc_wire_001"
        )

        assertTrue(res.isSuccess)

        // Inspect the actual HTTP wire request received by MockWebServer
        val recorded = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/customers/${targetCustomerId.value}/payments", recorded.path)
        assertEquals("idemp_alloc_wire_001", recorded.getHeader("Idempotency-Key"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))

        val wireBody = recorded.body.readUtf8()
        val parsedJson = StrictJsonParser.parse(wireBody).asObject()

        // Verify exact decimal strings on wire - NO DOUBLE SCIENTIFIC NOTATION
        assertEquals("500.000000", parsedJson.properties["amount"]?.asString())
        assertEquals("2026-03-15", parsedJson.properties["payment_date"]?.asString())
        assertEquals("سداد رصيد افتتاحي وفاتورة", parsedJson.properties["notes"]?.asString())

        val allocsArray = parsedJson.properties["allocations"]?.asArray()
        assertNotNull(allocsArray)
        assertEquals(2, allocsArray!!.elements.size)

        val a1 = allocsArray.elements[0].asObject()
        assertEquals("opening_trade_item", a1.properties["target_type"]?.asString())
        assertEquals("01JM7XYZ000000000000000010", a1.properties["target_id"]?.asString())
        assertEquals("200.000000", a1.properties["amount"]?.asString())

        val a2 = allocsArray.elements[1].asObject()
        assertEquals("invoice", a2.properties["target_type"]?.asString())
        assertEquals("01JM7XYZ000000000000000020", a2.properties["target_id"]?.asString())
        assertEquals("300.000000", a2.properties["amount"]?.asString())
    }

    // -------------------------------------------------------------------------
    // 4. Immutable Room Replay: Returns Cached Snapshot Without Second Outbound POST
    // -------------------------------------------------------------------------

    @Test
    fun testImmutableRoomReplay_returnsCachedSnapshotWithoutSecondOutboundRequest() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val ackResponse = """
            {
                "success": true,
                "id": "01JCV8X9M2PAYACK0000000099",
                "amount_exact": "100.000000",
                "balance": "400.000000",
                "is_advance": true,
                "allocated_amount_exact": "0.000000",
                "unallocated_amount_exact": "100.000000",
                "allocations": []
            }
        """.trimIndent()

        // Enqueue only 1 response on server
        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(ackResponse)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope }
        )

        val idempotencyKey = "idemp_room_replay_001"
        val operationId = "01JCV8X9M2OPREPLAY000000001"

        // First execution hits the server
        val res1 = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("100.00"),
            paymentDate = "2026-03-20",
            note = "دفعة تحت الحساب",
            allocations = emptyList(),
            paymentId = operationId
        )
        assertTrue(res1.isSuccess)
        assertEquals(1, server!!.requestCount)

        // Second execution with same operationId / idempotency key MUST replay from Room without network request
        val res2 = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("100.00"),
            paymentDate = "2026-03-20",
            note = "دفعة تحت الحساب",
            allocations = emptyList(),
            paymentId = operationId
        )
        assertTrue(res2.isSuccess)
        assertEquals("Server must NOT receive a second request on idempotent Room replay", 1, server!!.requestCount)

        db.close()
    }

    // -------------------------------------------------------------------------
    // 5. Continuation Semantics: empty targets with has_more=true is NOT zero debt
    // -------------------------------------------------------------------------

    @Test
    fun testContinuationSemantics_emptyTargetsWithHasMoreTrue_doesNotClaimDebtFree() = runBlocking {
        val emptyContinuationBody = """
            {
                "customer_id": "${targetCustomerId.value}",
                "customer_name": "تاجر تاريخي",
                "currency": "ILS",
                "currency_minor_units": 2,
                "total_targets": 0,
                "targets": [],
                "pagination": {
                    "limit": 50,
                    "has_more": true,
                    "next_cursor": "cursor_slice_candidate_scan_budget_2000"
                },
                "totals": {
                    "remaining_by_currency": {
                        "ILS": {
                            "amount_exact": "1500.000000",
                            "minor_units": 2
                        }
                    }
                }
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(emptyContinuationBody)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val resp = SettlementTargetsResponse.fromStrictJson(
            apiClient.getCustomerSettlementTargets(companyId, targetCustomerId).getOrThrow()
        )

        // Contract verification: targets is empty, but has_more is true!
        assertTrue("targets list must be empty for this scan slice", resp.targets.isEmpty())
        assertTrue("has_more MUST be true per backend 789de563 continuation contract", resp.hasMore)
        assertEquals("cursor_slice_candidate_scan_budget_2000", resp.nextCursor)
        assertFalse("Must NOT claim customer has settled all debt while has_more is true", resp.hasMore == false)
    }

    // -------------------------------------------------------------------------
    // 6. Strict JSON Negative Vectors: Rejected Before Economic Math
    // -------------------------------------------------------------------------

    @Test
    fun testStrictJsonNegativeVectors_rejectedWithoutDoubleMath() {
        // Negative amount rejected by MoneyAmount
        assertThrows(IllegalArgumentException::class.java) {
            MoneyAmount("-50.00")
        }

        // Negative decimal string rejected by PaymentAllocationInput validation
        assertThrows(IllegalArgumentException::class.java) {
            PaymentAllocationInput(
                targetType = "invoice",
                targetId = "inv_1",
                amountExact = BigDecimal("-10.00")
            )
        }

        // Malformed scientific notation with exponent 'e' in strict parser
        assertThrows(Exception::class.java) {
            StrictJsonParser.parse("""{"amount": 1e}""")
        }
    }

    // -------------------------------------------------------------------------
    // 7. P1: Conflicting retry parameters rejected across all states (Zero Network Writes)
    // -------------------------------------------------------------------------

    @Test
    fun testPaymentRetryConflictingParameters_rejectedAcrossCommandStates_zeroNetworkWrites() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope }
        )

        // 1. PENDING state in store with original amount 50.00
        val paymentId1 = "01JCV8X9M2PAYCONF000000001"
        val ackJson = """{"success":true,"id":"ack_1","balance":"100.000000","amount_exact":"50.000000","allocations":[]}"""
        server!!.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(ackJson))

        val resOk = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("50.00"),
            paymentDate = "2026-03-20",
            note = "سداد أصلي",
            allocations = emptyList(),
            paymentId = paymentId1
        )
        assertTrue(resOk.isSuccess)
        assertEquals(1, server!!.requestCount)

        // Retry with same paymentId but DIFFERENT AMOUNT (70.00 instead of 50.00)
        val resConflictingAmount = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("70.00"),
            paymentDate = "2026-03-20",
            note = "سداد أصلي",
            allocations = emptyList(),
            paymentId = paymentId1
        )
        assertTrue(resConflictingAmount.isFailure)
        assertTrue(resConflictingAmount.exceptionOrNull() is IllegalStateException)
        assertEquals("Conflicting retry must NOT make outbound network call", 1, server!!.requestCount)

        // Retry with same paymentId but DIFFERENT DATE
        val resConflictingDate = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("50.00"),
            paymentDate = "2026-03-21",
            note = "سداد أصلي",
            allocations = emptyList(),
            paymentId = paymentId1
        )
        assertTrue(resConflictingDate.isFailure)
        assertTrue(resConflictingDate.exceptionOrNull() is IllegalStateException)
        assertEquals("Conflicting date retry must NOT make network call", 1, server!!.requestCount)

        // Retry with same paymentId but DIFFERENT ALLOCATIONS
        val resConflictingAllocs = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("50.00"),
            paymentDate = "2026-03-20",
            note = "سداد أصلي",
            allocations = listOf(PaymentAllocationInput("invoice", "inv_99", BigDecimal("50.000000"))),
            paymentId = paymentId1
        )
        assertTrue(resConflictingAllocs.isFailure)
        assertTrue(resConflictingAllocs.exceptionOrNull() is IllegalStateException)
        assertEquals("Conflicting allocations retry must NOT make network call", 1, server!!.requestCount)

        // Simulate process restart with Room reopen
        db.close()
        val dbReopened = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val storeReopened = RoomMerchantCommandStore(dbReopened, cryptoEngine)
        val repoReopened = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = storeReopened,
            authScopeProvider = { validScope }
        )

        // After process restart, conflicting retry is STILL rejected with ZERO network writes
        val resAfterRestart = repoReopened.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("80.00"),
            paymentDate = "2026-03-20",
            note = "سداد",
            allocations = emptyList(),
            paymentId = paymentId1
        )
        assertTrue(resAfterRestart.isFailure)
        assertTrue(resAfterRestart.exceptionOrNull() is IllegalStateException)
        assertEquals("No network call on conflicting retry after Room reopen", 1, server!!.requestCount)

        // Supplier conflicting retry rejection
        val supPaymentId = "01JCV8X9M2SUPCONF000000001"
        val supAckJson = """{"success":true,"id":"ack_sup_1","balance_owed":"200.000000","amount_exact":"60.000000","allocations":[]}"""
        server!!.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(supAckJson))

        val session = UserSession(
            id = "session_test",
            name = "Trader Owner",
            username = "user_01",
            role = UserRole.OWNER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch"
        )

        val supOk = repoReopened.recordSupplierPayment(
            supplierId = targetSupplierId.value,
            amount = BigDecimal("60.00"),
            paymentDate = "2026-03-20",
            note = "دفعة مورد أصلية",
            operator = session,
            allocations = emptyList(),
            paymentId = supPaymentId
        )
        assertTrue(supOk.isSuccess)
        assertEquals(2, server!!.requestCount)

        val supConflict = repoReopened.recordSupplierPayment(
            supplierId = targetSupplierId.value,
            amount = BigDecimal("90.00"),
            paymentDate = "2026-03-20",
            note = "دفعة مورد مختلفة",
            operator = session,
            allocations = emptyList(),
            paymentId = supPaymentId
        )
        assertTrue(supConflict.isFailure)
        assertTrue(supConflict.exceptionOrNull() is IllegalStateException)
        assertEquals("Conflicting supplier payment retry must NOT make network call", 2, server!!.requestCount)

        dbReopened.close()
    }

    // -------------------------------------------------------------------------
    // 8. P2: Currency precision & durable intent (JOD vs ILS, no silent rounding)
    // -------------------------------------------------------------------------

    @Test
    fun testCurrencyPrecisionAndDurableIntent_jodVsIls() = runBlocking {
        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        // ILS Repository (2 minor units)
        val repoIls = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            baseCurrency = "ILS",
            currencyMinorUnits = 2,
            authScopeProvider = { validScope }
        )

        // 1.005 ILS must be REJECTED (exceeds 2 decimal places, no silent rounding to 1.01)
        val resIlsOverPrecision = repoIls.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("1.005"),
            note = "فحص دقة الشيكل",
            paymentId = "01JCV8X9M2PAYILS000000001"
        )
        assertTrue("ILS 1.005 must be rejected without silent rounding", resIlsOverPrecision.isFailure)
        assertTrue(resIlsOverPrecision.exceptionOrNull() is IllegalArgumentException)

        // JOD Repository (3 minor units)
        val repoJod = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            baseCurrency = "JOD",
            currencyMinorUnits = 3,
            authScopeProvider = { validScope }
        )

        // 1.005 JOD is VALID (exactly 3 decimal places)
        val jodAckJson = """{"success":true,"id":"ack_jod_1","balance":"50.000","amount_exact":"1.005000","allocations":[]}"""
        server!!.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(jodAckJson))

        val resJodValid = repoJod.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("1.005"),
            note = "سداد دينار أردني",
            paymentId = "01JCV8X9M2PAYJOD000000001"
        )
        assertTrue("JOD 1.005 must be accepted for 3 minor units", resJodValid.isSuccess)

        // Inspect recorded request to verify durable intent currency is JOD, NOT hardcoded ILS
        val jodReq = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        val jodBody = StrictJsonParser.parse(jodReq.body.readUtf8()).asObject()
        assertEquals("Durable intent must record authenticated base currency JOD", "1.005000", jodBody.properties["amount"]?.asString())
        assertEquals("JOD", jodBody.properties["currency"]?.asString())

        // 1.0005 JOD must be REJECTED (exceeds 3 decimal places)
        val resJodOverPrecision = repoJod.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("1.0005"),
            note = "فحص تجاوز دقة الدينار",
            paymentId = "01JCV8X9M2PAYJOD000000002"
        )
        assertTrue("JOD 1.0005 must be rejected without silent rounding", resJodOverPrecision.isFailure)
        assertTrue(resJodOverPrecision.exceptionOrNull() is IllegalArgumentException)
    }
}
