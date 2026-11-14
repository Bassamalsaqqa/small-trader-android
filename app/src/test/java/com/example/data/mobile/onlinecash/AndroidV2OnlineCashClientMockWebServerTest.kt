package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.contract.v2.OnlineSaleContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertThrows
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AndroidV2OnlineCashClientMockWebServerTest {

    private var server: MockWebServer? = null
    private var baseOrigin: String = ""
    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val clientEventId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val validDigest = PayloadDigest.parse("sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9")
    private val validToken = "st_m_test_valid_session_token_1234567890"
    private val sampleCanonicalBytes = """{"client_event_id":"3fa85f64-5717-4562-b3fc-2c963f66afa6","currency":"ILS"}""".toByteArray(Charsets.UTF_8)

    private val sampleCommitJson = """{
        "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "status": "posted",
        "invoice_id": "01JCV8X9ME7890123456789ABC",
        "invoice_number": "INV-2026-00042",
        "payment_id": "01JCV8X9MFGHJKMNPQRSTVWXYZ",
        "payment_receipt_number": "RCT-2026-00042",
        "grand_total": "58.000000",
        "amount_paid": "58.000000",
        "change_returned": "0.000000",
        "posted_at": "2026-11-10T12:01:05Z"
    }"""

    private val sampleStatusPostedJson = """{
        "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
        "status": "posted",
        "payload_digest": "sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9",
        "official_invoice": {
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "invoice_number": "INV-2026-00042",
            "payment_id": "01JCV8X9MFGHJKMNPQRSTVWXYZ",
            "payment_receipt_number": "RCT-2026-00042",
            "grand_total": "58.000000",
            "posted_at": "2026-11-10T12:01:05Z"
        },
        "conflict": null,
        "queried_at": "2026-11-10T12:05:00Z"
    }"""

    private val sampleStatusUnknownJson = """{
        "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
        "status": "unknown",
        "payload_digest": null,
        "official_invoice": null,
        "conflict": null,
        "queried_at": "2026-11-10T12:05:00Z"
    }"""

    @Before
    fun setUp() {
        val s = MockWebServer()
        s.start()
        val port = s.port
        baseOrigin = "http://127.0.0.1:$port"
        this.server = s
    }

    @After
    fun tearDown() {
        server?.shutdown()
        server = null
    }

    private fun createClient(connectTimeoutMs: Int = 5000, readTimeoutMs: Int = 5000): AndroidV2OnlineCashClient {
        val transport = OkHttpTransport(
            configuredOrigin = baseOrigin,
            allowHttpLoopback = true,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = readTimeoutMs + 2000
        )
        return AndroidV2OnlineCashClient(transport, baseOrigin, allowHttpLoopback = true)
    }

    @Test
    fun testSubmitFreshCashSale201Created() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleCommitJson)
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected Created result", result is OnlineCashSubmitResult.Created)
        val created = (result as OnlineCashSubmitResult.Created).response
        assertEquals(clientEventId, created.clientEventId)
        assertEquals("posted", created.status)
        assertEquals("01JCV8X9ME7890123456789ABC", created.invoiceId.value)
        assertEquals("58.000000", created.grandTotal.lexeme)

        val recorded = server!!.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/api/mobile/v2/companies/01JCV8X9M2NQW3TYZ4R5S6789A/sales/online-cash", recorded.path)
        assertEquals("Bearer $validToken", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun testSubmitReplayCashSale200Ok() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleCommitJson)
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected Replayed result", result is OnlineCashSubmitResult.Replayed)
        val replayed = (result as OnlineCashSubmitResult.Replayed).response
        assertEquals(clientEventId, replayed.clientEventId)
        assertEquals("01JCV8X9ME7890123456789ABC", replayed.invoiceId.value)
    }

    @Test
    fun testSubmitTamperedPayload409Conflict() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"CONFLICT","message":"Resource state conflict."}}""")
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected Conflict409", result is OnlineCashSubmitResult.Conflict409)
        val conflict = (result as OnlineCashSubmitResult.Conflict409).error
        assertEquals("CONFLICT", conflict.code)
    }

    @Test
    fun testSubmitAuthRevoked401Unauthorized() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"Authentication required or token expired."}}""")
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected AuthDenied401", result is OnlineCashSubmitResult.AuthDenied401)
        val auth = (result as OnlineCashSubmitResult.AuthDenied401).error
        assertEquals("UNAUTHENTICATED", auth.code)
    }

    @Test
    fun testSubmitForbidden403RoleRevoked() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"FORBIDDEN","message":"Access forbidden."}}""")
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected Forbidden403", result is OnlineCashSubmitResult.Forbidden403)
        val forbidden = (result as OnlineCashSubmitResult.Forbidden403).error
        assertEquals("FORBIDDEN", forbidden.code)
    }

    @Test
    fun testSubmitValidationFailed422() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"VALIDATION_FAILED","message":"Invalid line quantity."}}""")
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected ValidationFailed422", result is OnlineCashSubmitResult.ValidationFailed422)
        val valErr = (result as OnlineCashSubmitResult.ValidationFailed422).error
        assertEquals("VALIDATION_FAILED", valErr.code)
    }

    @Test
    fun testStatusUnknown200() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleStatusUnknownJson)
        )

        val client = createClient()
        val result = client.checkStatus(validToken, companyId, clientEventId)

        assertTrue("Expected Unknown status", result is OnlineCashStatusResult.Unknown)
        val unknown = (result as OnlineCashStatusResult.Unknown).response
        assertEquals(clientEventId, unknown.clientEventId)
        assertEquals("unknown", unknown.status)
        assertNull(unknown.officialInvoice)
    }

    @Test
    fun testStatusPosted200() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleStatusPostedJson)
        )

        val client = createClient()
        val result = client.checkStatus(validToken, companyId, clientEventId)

        assertTrue("Expected Posted status", result is OnlineCashStatusResult.Posted)
        val posted = (result as OnlineCashStatusResult.Posted).response
        assertEquals(clientEventId, posted.clientEventId)
        assertEquals("posted", posted.status)
        assertNotNull(posted.officialInvoice)
        assertEquals("01JCV8X9ME7890123456789ABC", posted.officialInvoice!!.invoiceId.value)
    }

    @Test
    fun testStatusTenantMismatchRejected() {
        val foreignCompanyJson = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "company_id": "01JCV8X9M99999999999999999",
            "status": "unknown",
            "payload_digest": null,
            "official_invoice": null,
            "conflict": null,
            "queried_at": "2026-11-10T12:05:00Z"
        }"""

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(foreignCompanyJson)
        )

        val client = createClient()
        val result = client.checkStatus(validToken, companyId, clientEventId)

        assertTrue("Expected ParseFailure on tenant mismatch", result is OnlineCashStatusResult.ParseFailure)
    }

    @Test
    fun testStatusEventMismatchRejected() {
        val foreignEventJson = """{
            "client_event_id": "99999999-9999-9999-9999-999999999999",
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "status": "unknown",
            "payload_digest": null,
            "official_invoice": null,
            "conflict": null,
            "queried_at": "2026-11-10T12:05:00Z"
        }"""

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(foreignEventJson)
        )

        val client = createClient()
        val result = client.checkStatus(validToken, companyId, clientEventId)

        assertTrue("Expected ParseFailure on event mismatch", result is OnlineCashStatusResult.ParseFailure)
    }

    @Test
    fun testBadResponseLexemesRejected() {
        val badDecimalJson = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "status": "posted",
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "invoice_number": "INV-2026-00042",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": 58.00,
            "amount_paid": "58.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-10T12:01:05Z"
        }"""

        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(badDecimalJson)
        )

        val client = createClient()
        val result = client.submitSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected ParseFailure on float lexeme", result is OnlineCashSubmitResult.ParseFailure)
    }

    @Test
    fun testLostAckRecoveryWhenStatusPosted() {
        // First request: Socket timeout during POST online-cash
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // Recovery request: GET status returns 200 posted
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleStatusPostedJson)
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val result = client.executeOnlineCashSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected Success result through status recovery", result is OnlineCashExecutionResult.Success)
        val success = result as OnlineCashExecutionResult.Success
        assertTrue(success.wasReplay)
        assertNotNull(success.statusResponse)
        assertEquals("posted", success.statusResponse!!.status)
        assertEquals("01JCV8X9ME7890123456789ABC", success.statusResponse!!.officialInvoice!!.invoiceId.value)
    }

    @Test
    fun testLostAckRecoveryWhenStatusUnknownSafeReplay() {
        // 1. Initial POST drops with socket disconnect
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // 2. Status check returns unknown
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleStatusUnknownJson)
        )
        // 3. Safe replay of original bytes succeeds with 200 replay
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleCommitJson)
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val result = client.executeOnlineCashSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected Success on safe replay", result is OnlineCashExecutionResult.Success)
        val success = result as OnlineCashExecutionResult.Success
        assertTrue(success.wasReplay)
        assertNotNull(success.commitResponse)
        assertEquals("01JCV8X9ME7890123456789ABC", success.commitResponse!!.invoiceId.value)
    }

    @Test
    fun testLostAckRecoveryAuthDeniedPreservesLock() {
        // 1. Initial POST drops
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // 2. Status check returns 401 Unauthorized
        server!!.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"Token revoked during recovery."}}""")
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val result = client.executeOnlineCashSale(validToken, companyId, clientEventId, validDigest, sampleCanonicalBytes)

        assertTrue("Expected AuthDenied to preserve lock", result is OnlineCashExecutionResult.AuthDenied)
        val auth = (result as OnlineCashExecutionResult.AuthDenied).error
        assertEquals("UNAUTHENTICATED", auth.code)
    }

    // -------------------------------------------------------------
    // Online Cash Orchestrator Integration Tests
    // -------------------------------------------------------------

    private val authCompanyId = ServerUlid.parse("01M7CZPF6TP283F3ZRXF5P8GT2")
    private val authActorId = ServerUlid.parse("01M7CZPF6TP283F3ZRXF5P8GT1")
    private val authEventId = ClientUuid.parse("c0686be5-888f-4890-90bf-1f05f416a19a")
    private val authDeviceId = ClientUuid.parse("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")
    private val authDigest = PayloadDigest.parse("sha256:0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630")

    private val sampleContext = OnlineSaleContext(
        companyId = authCompanyId,
        actorId = authActorId,
        currencyMinorUnits = 2
    )

    private val syntheticRawWirePayload = """{
        "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
        "device_id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
        "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
        "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
        "currency": "ILS",
        "lines": [
            {
                "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                "conversion_to_base": "1.000000",
                "quantity": "1.000000",
                "unit_price": "50.000000",
                "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                "tax_rate": "16.000000",
                "tax_inclusive": false
            }
        ],
        "payment": {
            "settlement_mode": "full",
            "payment_method": "cash",
            "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8",
            "amount": "58.000000"
        }
    }""".toByteArray(Charsets.UTF_8)

    private val syntheticCommitJson = """{
        "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
        "status": "posted",
        "invoice_id": "01M7CZPF6TP283F3ZRXF5P8GT9",
        "invoice_number": "INV-2026-00099",
        "payment_id": "01M7CZPF6TP283F3ZRXF5P8GTA",
        "payment_receipt_number": "RCT-2026-00099",
        "grand_total": "58.000000",
        "amount_paid": "58.000000",
        "change_returned": "0.000000",
        "posted_at": "2026-11-14T12:00:00Z"
    }"""

    @Test
    fun testSubmitAuthoritativeSyntheticTemplateWirePayload() {
        val rawWirePayload = syntheticRawWirePayload

        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(syntheticCommitJson)
        )

        val client = createClient()
        val result = client.submitSale(
            token = validToken,
            companyId = authCompanyId,
            clientEventId = authEventId,
            expectedPayloadDigest = authDigest,
            canonicalBytes = rawWirePayload
        )

        assertTrue("Expected Created outcome", result is OnlineCashSubmitResult.Created)
        val created = (result as OnlineCashSubmitResult.Created).response
        assertEquals(authEventId, created.clientEventId)
        assertEquals("01M7CZPF6TP283F3ZRXF5P8GT9", created.invoiceId.value)
        assertEquals("58.000000", created.grandTotal.toPlainString())

        val recorded = server!!.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/api/mobile/v2/companies/01M7CZPF6TP283F3ZRXF5P8GT2/sales/online-cash", recorded.path)
    }

    @Test
    fun testOnlineOrchestration7KeyRawProfileSyntheticFixtureSuccess() {
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(syntheticCommitJson)
        )

        val client = createClient()
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        assertTrue("Expected ConfirmedFinancialSuccess", result is OnlineCashOrchestrationResult.ConfirmedFinancialSuccess)
        val receipt = (result as OnlineCashOrchestrationResult.ConfirmedFinancialSuccess).receipt
        assertEquals(authEventId, receipt.clientEventId)
        assertEquals(authCompanyId, receipt.companyId)
        assertEquals("01M7CZPF6TP283F3ZRXF5P8GT9", receipt.invoiceId.value)
        assertEquals("01M7CZPF6TP283F3ZRXF5P8GTA", receipt.paymentId?.value)
        assertEquals("58.000000", receipt.grandTotal.toPlainString())
        assertFalse(receipt.wasReplay)
    }

    @Test
    fun testOnlineOrchestrationNullPaymentNoSyntheticFabrication() {
        val commitWithNullPayment = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "status": "posted",
            "invoice_id": "01M7CZPF6TP283F3ZRXF5P8GT9",
            "invoice_number": "INV-2026-00099",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": "58.000000",
            "amount_paid": "58.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-14T12:00:00Z"
        }"""

        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        server!!.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(commitWithNullPayment)
        )

        val client = createClient()
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        // Schema honesty: null payment_id MUST NOT be synthetically fabricated via invoice_id
        assertTrue("Expected PostedWithoutPaymentReference", result is OnlineCashOrchestrationResult.PostedWithoutPaymentReference)
        val receipt = (result as OnlineCashOrchestrationResult.PostedWithoutPaymentReference).receipt
        assertNull("paymentId must be schema-honest null without synthetic fabrication", receipt.paymentId)
        assertEquals("01M7CZPF6TP283F3ZRXF5P8GT9", receipt.invoiceId.value)
    }

    @Test
    fun testOnlineOrchestrationLostAckStatusPostedMatchingDigestRecovery() {
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        val statusPostedJson = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
            "status": "posted",
            "payload_digest": "sha256:0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630",
            "official_invoice": {
                "invoice_id": "01M7CZPF6TP283F3ZRXF5P8GT9",
                "invoice_number": "INV-2026-00099",
                "payment_id": "01M7CZPF6TP283F3ZRXF5P8GTA",
                "payment_receipt_number": "RCT-2026-00099",
                "grand_total": "58.000000",
                "posted_at": "2026-11-14T12:00:00Z"
            },
            "conflict": null,
            "queried_at": "2026-11-14T12:05:00Z"
        }"""

        // 1. Initial POST drops with socket disconnect
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // 2. Recovery GET status returns 200 posted with matching non-null payload digest
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(statusPostedJson)
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        assertTrue("Expected ConfirmedFinancialSuccess on status recovery", result is OnlineCashOrchestrationResult.ConfirmedFinancialSuccess)
        val receipt = (result as OnlineCashOrchestrationResult.ConfirmedFinancialSuccess).receipt
        assertTrue(receipt.wasReplay)
        assertEquals("01M7CZPF6TP283F3ZRXF5P8GT9", receipt.invoiceId.value)
        assertEquals("01M7CZPF6TP283F3ZRXF5P8GTA", receipt.paymentId?.value)
    }

    @Test
    fun testOnlineOrchestrationLostAckStatusPostedMissingDigestFailsClosed() {
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        val statusPostedNoDigestJson = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
            "status": "posted",
            "payload_digest": null,
            "official_invoice": {
                "invoice_id": "01M7CZPF6TP283F3ZRXF5P8GT9",
                "invoice_number": "INV-2026-00099",
                "payment_id": "01M7CZPF6TP283F3ZRXF5P8GTA",
                "payment_receipt_number": "RCT-2026-00099",
                "grand_total": "58.000000",
                "posted_at": "2026-11-14T12:00:00Z"
            },
            "conflict": null,
            "queried_at": "2026-11-14T12:05:00Z"
        }"""

        // 1. Initial POST drops
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // 2. Status response has payload_digest: null (missing digest)
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(statusPostedNoDigestJson)
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        // Must FAIL CLOSED on missing payload digest
        assertTrue("Expected StatusDigestMismatch on null digest", result is OnlineCashOrchestrationResult.StatusDigestMismatch)
        val mismatch = result as OnlineCashOrchestrationResult.StatusDigestMismatch
        assertEquals(authDigest, mismatch.expected)
        assertNull(mismatch.actual)
    }

    @Test
    fun testOnlineOrchestrationLostAckStatusPostedWrongDigestFailsClosed() {
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        val statusPostedWrongDigestJson = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
            "status": "posted",
            "payload_digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            "official_invoice": {
                "invoice_id": "01M7CZPF6TP283F3ZRXF5P8GT9",
                "invoice_number": "INV-2026-00099",
                "payment_id": "01M7CZPF6TP283F3ZRXF5P8GTA",
                "payment_receipt_number": "RCT-2026-00099",
                "grand_total": "58.000000",
                "posted_at": "2026-11-14T12:00:00Z"
            },
            "conflict": null,
            "queried_at": "2026-11-14T12:05:00Z"
        }"""

        // 1. Initial POST drops
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // 2. Status response has wrong digest
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(statusPostedWrongDigestJson)
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        // Must FAIL CLOSED on mismatched payload digest
        assertTrue("Expected StatusDigestMismatch on wrong digest", result is OnlineCashOrchestrationResult.StatusDigestMismatch)
    }

    @Test
    fun testOnlineOrchestrationLostAckStatusUnknownSafeExactReplay409() {
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        val statusUnknownJson = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
            "status": "unknown",
            "payload_digest": null,
            "official_invoice": null,
            "conflict": null,
            "queried_at": "2026-11-14T12:05:00Z"
        }"""

        // 1. Initial POST drops
        server!!.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        // 2. Status check returns 200 unknown (safe to replay original wire bytes)
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(statusUnknownJson)
        )
        // 3. Replay POST of EXACT SAME original bytes returns 409 Conflict
        server!!.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"CONFLICT","message":"Replay business conflict."}}""")
        )

        val client = createClient(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        assertTrue("Expected BusinessConflict on 409 replay", result is OnlineCashOrchestrationResult.BusinessConflict)
        assertEquals("CONFLICT", (result as OnlineCashOrchestrationResult.BusinessConflict).error.code)

        // Verify request sequence: POST -> GET status -> replay POST
        assertEquals(3, server!!.requestCount)
        val req1 = server!!.takeRequest()
        val req2 = server!!.takeRequest()
        val req3 = server!!.takeRequest()
        assertEquals("POST", req1.method)
        assertEquals("GET", req2.method)
        assertEquals("POST", req3.method)
        // Verify EXACT SAME original wire bytes replayed
        assertEquals(req1.body.readUtf8(), req3.body.readUtf8())
    }

    @Test
    fun testOnlineOrchestrationLocalTokenUnavailableNoQueueAuthLock() {
        val lockScopeInvoked = AtomicBoolean(false)
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        val client = createClient()
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { null }, // Missing token locally
            scopeGenerationProvider = { 1L },
            onAuthDeniedLockScope = { _, _ -> lockScopeInvoked.set(true) }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        assertTrue("Expected LocalTokenUnavailable", result is OnlineCashOrchestrationResult.LocalTokenUnavailable)
        assertFalse("Missing local token MUST NOT lock queue as server AuthDenied", lockScopeInvoked.get())
        assertEquals("Zero HTTP requests dispatched when local token unavailable", 0, server!!.requestCount)
    }

    @Test
    fun testOnlineOrchestrationCorruptUtf8ZeroHttp() {
        val corruptBytes = byteArrayOf(0xC0.toByte(), 0xAF.toByte())

        assertThrows(IllegalArgumentException::class.java) {
            PreparedOnlineSaleIntent.prepare(
                context = sampleContext,
                rawWireBytes = corruptBytes,
                scopeGeneration = 1L
            )
        }
        assertEquals("Zero HTTP requests on corrupt UTF-8", 0, server!!.requestCount)
    }

    @Test
    fun testOnlineOrchestrationOversizePayloadZeroHttp() {
        val oversizeBytes = ByteArray(1024 * 1024 + 1) { 0x20 }

        assertThrows(IllegalArgumentException::class.java) {
            PreparedOnlineSaleIntent.prepare(
                context = sampleContext,
                rawWireBytes = oversizeBytes,
                scopeGeneration = 1L
            )
        }
        assertEquals("Zero HTTP requests on oversize payload", 0, server!!.requestCount)
    }

    @Test
    fun testOnlineOrchestrationNonCanonicalDoubleZeroHttp() {
        val floatPayload = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "device_id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
            "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
            "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
            "currency": "ILS",
            "lines": [],
            "payment": { "amount": 58.5 }
        }""".toByteArray(Charsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            PreparedOnlineSaleIntent.prepare(
                context = sampleContext,
                rawWireBytes = floatPayload,
                scopeGeneration = 1L
            )
        }
        assertEquals("Zero HTTP requests on float payload", 0, server!!.requestCount)
    }

    @Test
    fun testOnlineOrchestrationWrongNormativeDigestZeroHttp() {
        val wrongDigest = PayloadDigest.parse("sha256:1111111111111111111111111111111111111111111111111111111111111111")

        assertThrows(IllegalArgumentException::class.java) {
            PreparedOnlineSaleIntent.prepare(
                context = sampleContext,
                rawWireBytes = syntheticRawWirePayload,
                scopeGeneration = 1L,
                expectedDigest = wrongDigest
            )
        }
        assertEquals("Zero HTTP requests on wrong expected digest", 0, server!!.requestCount)
    }

    @Test
    fun testOnlineOrchestrationPreDispatchMismatchCasesZeroHttp() {
        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        val client = createClient()
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L }
        )

        // 1. Company mismatch
        val mismatchCompany = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
        val res1 = orchestrator.dispatchOnlineCashSale(intent, mismatchCompany, authDeviceId, "ILS")
        assertTrue(res1 is OnlineCashOrchestrationResult.PreDispatchValidationError)

        // 2. Device mismatch
        val mismatchDevice = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10")
        val res2 = orchestrator.dispatchOnlineCashSale(intent, authCompanyId, mismatchDevice, "ILS")
        assertTrue(res2 is OnlineCashOrchestrationResult.PreDispatchValidationError)

        // 3. Currency mismatch
        val res3 = orchestrator.dispatchOnlineCashSale(intent, authCompanyId, authDeviceId, "USD")
        assertTrue(res3 is OnlineCashOrchestrationResult.PreDispatchValidationError)

        // 4. Scope generation mismatch before dispatch
        val mismatchedScopeOrchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 2L } // Provider at 2L, intent at 1L
        )
        val res4 = mismatchedScopeOrchestrator.dispatchOnlineCashSale(intent, authCompanyId, authDeviceId, "ILS")
        assertTrue(res4 is OnlineCashOrchestrationResult.ScopeMismatch)

        assertEquals("Zero HTTP requests on pre-dispatch mismatches", 0, server!!.requestCount)
    }

    @Test
    fun testOnlineOrchestrationScopeGenerationStale401NoCallback() {
        val lockScopeInvoked = AtomicBoolean(false)
        val scopeGen = AtomicLong(1L)

        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        server!!.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"Token expired."}}""")
        )

        val client = createClient()
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = {
                // Generation advanced to 2L while request was in-flight
                scopeGen.getAndSet(2L)
                2L
            },
            onAuthDeniedLockScope = { _, _ -> lockScopeInvoked.set(true) }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        assertTrue("Expected StaleAuthDenied", result is OnlineCashOrchestrationResult.StaleAuthDenied)
        assertFalse("Stale 401 callback for previous scope MUST NOT lock new scope", lockScopeInvoked.get())
    }

    @Test
    fun testOnlineOrchestrationFresh401InvokesLockCallback() {
        val lockedGeneration = AtomicLong(-1L)

        val intent = PreparedOnlineSaleIntent.prepare(
            context = sampleContext,
            rawWireBytes = syntheticRawWirePayload,
            scopeGeneration = 1L,
            expectedDigest = authDigest
        )

        server!!.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"Token expired."}}""")
        )

        val client = createClient()
        val orchestrator = OnlineCashSaleOrchestrator(
            client = client,
            tokenProvider = { validToken },
            scopeGenerationProvider = { 1L },
            onAuthDeniedLockScope = { gen, _ -> lockedGeneration.set(gen) }
        )

        val result = orchestrator.dispatchOnlineCashSale(
            intent = intent,
            targetCompanyId = authCompanyId,
            targetDeviceId = authDeviceId,
            targetCurrency = "ILS"
        )

        assertTrue("Expected AuthDenied", result is OnlineCashOrchestrationResult.AuthDenied)
        assertEquals("Fresh 401 on matching generation MUST invoke lock callback", 1L, lockedGeneration.get())
    }
}
