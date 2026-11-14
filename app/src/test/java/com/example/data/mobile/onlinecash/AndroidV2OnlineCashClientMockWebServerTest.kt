package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.sync.SyncStatusOutcome
import com.example.data.mobile.sync.SyncSubmitOutcome
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
        assertEquals("application/json", recorded.getHeader("Content-Type"))
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

    @Test
    fun testSaleSyncGatewayAdapterParity() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleStatusPostedJson)
        )

        val client = createClient()
        val gateway = OnlineCashSaleSyncGateway(client) { validToken }

        val statusOutcome = gateway.checkStatus(
            companyId = companyId,
            actorId = ServerUlid.parse("01JCV8X9M11111111111111111"),
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            clientEventId = clientEventId,
            payloadDigest = validDigest
        )

        assertTrue("Expected SyncStatusOutcome.Posted", statusOutcome is SyncStatusOutcome.Posted)
        val ack = (statusOutcome as SyncStatusOutcome.Posted).ack
        assertEquals(companyId, ack.companyId)
        assertEquals(clientEventId, ack.clientEventId)
        assertEquals("01JCV8X9ME7890123456789ABC", ack.invoiceUlid.value)
    }
}
