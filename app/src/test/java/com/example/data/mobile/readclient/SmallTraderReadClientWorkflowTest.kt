package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class SmallTraderReadClientWorkflowTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    private class MockTransport : ReadClientTransport {
        var lastRequest: TransportRequest? = null
        var responseToReturn: TransportResponse = TransportResponse(statusCode = 200, body = "{}")
        var exceptionToThrow: Throwable? = null

        override fun execute(request: TransportRequest): TransportResponse {
            lastRequest = request
            exceptionToThrow?.let { throw it }
            return responseToReturn
        }
    }

    private val fixturesDir by lazy { locateFixturesDir() }
    private val baseOrigin = "https://api.smalltrader.example"
    private val dummyToken = "sanctum_1092837465_mobile_token_982347109238471092384710"
    private val dummyCompanyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")

    @Test
    fun testAuthChallengeWorkflow() {
        val transport = MockTransport()
        val golden = File(fixturesDir, "golden-auth-challenge.json").readText(Charsets.UTF_8)
        val goldenRoot = StrictJsonParser.parse(golden).asObject()
        val goldenRes = goldenRoot.require("response")
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(goldenRes)
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthChallengeRequest(
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            appVersion = "1.0.0-rc1",
            platform = "android"
        )
        val result = client.authChallenge(request)

        assertTrue("Expected Success, got $result", result is ReadClientResult.Success)
        val response = (result as ReadClientResult.Success).value
        assertEquals("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d", response.challengeId.value)
        assertEquals("POST", transport.lastRequest?.method?.name)
        assertEquals("/api/mobile/v1/auth/challenge", transport.lastRequest?.path)
    }

    @Test
    fun testAuthLoginSuccessWorkflow() {
        val transport = MockTransport()
        val golden = File(fixturesDir, "golden-auth-login.json").readText(Charsets.UTF_8)
        val goldenRoot = StrictJsonParser.parse(golden).asObject()
        val goldenRes = goldenRoot.require("response")
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(goldenRes)
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthLoginRequest(
            challengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"),
            nonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef",
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceName = "Samsung Galaxy Tab A8",
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!"
        )
        val result = client.authLogin(request)

        assertTrue(result is ReadClientResult.Success)
        val authResult = (result as ReadClientResult.Success).value
        assertTrue(authResult is AuthLoginResult.Success)
        val success = (authResult as AuthLoginResult.Success).response
        assertEquals(dummyToken, success.token)
        assertEquals(1, success.companies.size)

        // Verify request payload placement: body only, no headers carrying credentials
        assertEquals("/api/mobile/v1/auth/login", transport.lastRequest?.path)
        assertFalse(transport.lastRequest?.headers?.containsKey("Authorization") == true)
        assertTrue(transport.lastRequest?.body?.contains("cashier@smalltrader.example") == true)
    }

    @Test
    fun testAuthLoginMfaRequiredWorkflow() {
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = """{"status":"MFA_REQUIRED","retry":"fresh_challenge"}"""
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthLoginRequest(
            challengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"),
            nonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef",
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceName = "Samsung Galaxy Tab A8",
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!"
        )
        val result = client.authLogin(request)

        assertTrue(result is ReadClientResult.Success)
        val authResult = (result as ReadClientResult.Success).value
        assertTrue("Must be MfaRequired outcome", authResult is AuthLoginResult.MfaRequired)
        val mfa = (authResult as AuthLoginResult.MfaRequired).response
        assertEquals("fresh_challenge", mfa.retry)
    }

    @Test
    fun testAuthLogoutWorkflow() {
        val transport = MockTransport()
        val golden = File(fixturesDir, "golden-auth-logout.json").readText(Charsets.UTF_8)
        val goldenRes = StrictJsonParser.parse(golden).asObject().require("response")
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(goldenRes)
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthLogoutRequest(deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"))
        val result = client.authLogout(dummyToken, request)

        assertTrue(result is ReadClientResult.Success)
        val response = (result as ReadClientResult.Success).value
        assertEquals("logged_out", response.status)

        // Verify token in Authorization header
        assertEquals("Bearer $dummyToken", transport.lastRequest?.headers?.get("Authorization"))
    }

    @Test
    fun testGetBootstrapWorkflow() {
        val transport = MockTransport()
        val golden = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
        transport.responseToReturn = TransportResponse(statusCode = 200, body = golden)

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getBootstrap(dummyToken, dummyCompanyId)

        assertTrue(result is ReadClientResult.Success)
        val bootstrap = (result as ReadClientResult.Success).value
        assertEquals(dummyCompanyId, bootstrap.companyId)
        assertFalse(bootstrap.offlinePolicy.enabled)
        assertNull(bootstrap.deviceLease)
        assertEquals("Bearer $dummyToken", transport.lastRequest?.headers?.get("Authorization"))
    }

    @Test
    fun testGetProductsWithBoundedPaginationWorkflow() {
        val transport = MockTransport()
        val golden = File(fixturesDir, "golden-product-catalog.json").readText(Charsets.UTF_8)
        transport.responseToReturn = TransportResponse(statusCode = 200, body = golden)

        val client = SmallTraderReadClient(transport, baseOrigin)
        val query = PaginationQuery(limit = 25, cursor = "cursor_abc123", search = "oil")
        val result = client.getProducts(dummyToken, dummyCompanyId, query)

        assertTrue(result is ReadClientResult.Success)
        val catalog = (result as ReadClientResult.Success).value
        assertEquals(2, catalog.products.size)

        // Verify query parameters
        val req = transport.lastRequest
        assertNotNull(req)
        assertEquals("25", req?.queryParams?.get("limit"))
        assertEquals("cursor_abc123", req?.queryParams?.get("cursor"))
        assertEquals("oil", req?.queryParams?.get("search"))
        assertEquals("Bearer $dummyToken", req?.headers?.get("Authorization"))
    }

    @Test
    fun testGetCustomersHonestEmptyResultsWorkflow() {
        val transport = MockTransport()
        val emptyCustomersJson = """
            {
              "company_id": "${dummyCompanyId.value}",
              "generated_at": "2026-11-10T12:00:00Z",
              "next_cursor": null,
              "has_more": false,
              "customers": []
            }
        """.trimIndent()
        transport.responseToReturn = TransportResponse(statusCode = 200, body = emptyCustomersJson)

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getCustomers(dummyToken, dummyCompanyId)

        assertTrue(result is ReadClientResult.Success)
        val directory = (result as ReadClientResult.Success).value
        assertTrue("Empty customer directory must be preserved honestly", directory.customers.isEmpty())
        assertFalse(directory.hasMore)
        assertNull(directory.nextCursor)
    }

    @Test
    fun testAuth401ExplicitOutcomeForCallerLockHandling() {
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 401,
            body = """
                {
                  "error": {
                    "code": "AUTH_TOKEN_REVOKED",
                    "message": "Token was revoked or expired; authenticate fresh"
                  }
                }
            """.trimIndent()
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getBootstrap(dummyToken, dummyCompanyId)

        assertTrue("Must yield explicit Auth401 outcome", result is ReadClientResult.Auth401)
        val err = (result as ReadClientResult.Auth401).error
        assertEquals("AUTH_TOKEN_REVOKED", err.code)
    }

    @Test
    fun testForbidden403ExplicitOutcome() {
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 403,
            body = """
                {
                  "error": {
                    "code": "TENANT_ACCESS_DENIED",
                    "message": "Actor is not authorized for target company"
                  }
                }
            """.trimIndent()
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getBootstrap(dummyToken, dummyCompanyId)

        assertTrue(result is ReadClientResult.Forbidden403)
        val err = (result as ReadClientResult.Forbidden403).error
        assertEquals("TENANT_ACCESS_DENIED", err.code)
    }

    @Test
    fun testConflict409ExplicitOutcomeWithEvidence() {
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 409,
            body = """
                {
                  "error": {
                    "code": "CATALOG_SNAPSHOT_CONFLICT",
                    "message": "Catalog snapshot conflict",
                    "conflict_evidence": {
                      "client_event_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
                      "stored_payload_digest": "sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9",
                      "incoming_payload_digest": "sha256:e81412a748b809981b56c60308332c03ce554771f754178efccce2081209f131",
                      "conflict_detected_at": "2026-11-10T12:00:00Z"
                    }
                  }
                }
            """.trimIndent()
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getProducts(dummyToken, dummyCompanyId)

        assertTrue(result is ReadClientResult.Conflict409)
        val err = (result as ReadClientResult.Conflict409).error
        assertEquals("CATALOG_SNAPSHOT_CONFLICT", err.code)
        assertNotNull(err.conflictEvidence)
        assertEquals("8e2b8bc6-f80c-4034-8c88-59368d1f2e10", err.conflictEvidence?.clientEventId?.value)
    }

    @Test
    fun testParseFailureOnSchemaViolation() {
        val transport = MockTransport()
        // Response with extra property violated additionalProperties: false
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = """
                {
                  "company_id": "${dummyCompanyId.value}",
                  "generated_at": "2026-11-10T12:00:00Z",
                  "next_cursor": null,
                  "has_more": false,
                  "customers": [],
                  "unexpected_injected_field": true
                }
            """.trimIndent()
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getCustomers(dummyToken, dummyCompanyId)

        assertTrue("Expected ParseFailure, got $result", result is ReadClientResult.ParseFailure)
        val pf = result as ReadClientResult.ParseFailure
        assertEquals("PARSE_ERROR", pf.category)
        assertFalse("Raw exception message must not leak in toString()", pf.toString().contains("unexpected_injected_field"))
    }

    @Test
    fun testNetworkUnknownOnTransportException() {
        val transport = MockTransport()
        transport.exceptionToThrow = IOException("Connection refused by peer")

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getBootstrap(dummyToken, dummyCompanyId)

        assertTrue("Expected NetworkUnknown, got $result", result is ReadClientResult.NetworkUnknown)
        val nu = result as ReadClientResult.NetworkUnknown
        assertEquals("NETWORK_ERROR", nu.category)
        assertFalse("Raw exception message must not leak in toString()", nu.toString().contains("Connection refused by peer"))
    }

    @Test
    fun testCompanyIdMismatchRejectedOnBootstrap() {
        val foreignCompanyId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890")
        val golden = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(statusCode = 200, body = golden)

        val client = SmallTraderReadClient(transport, baseOrigin)
        // Request for foreignCompanyId but response contains dummyCompanyId (Al-Amal)
        val result = client.getBootstrap(dummyToken, foreignCompanyId)

        assertTrue("Expected ParseFailure on company_id mismatch, got $result", result is ReadClientResult.ParseFailure)
        val pf = result as ReadClientResult.ParseFailure
        assertEquals("PARSE_ERROR", pf.category)
    }

    @Test
    fun testCompanyIdMismatchRejectedOnProducts() {
        val foreignCompanyId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890")
        val golden = File(fixturesDir, "golden-product-catalog.json").readText(Charsets.UTF_8)
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(statusCode = 200, body = golden)

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getProducts(dummyToken, foreignCompanyId)

        assertTrue("Expected ParseFailure on products company_id mismatch, got $result", result is ReadClientResult.ParseFailure)
    }

    @Test
    fun testCompanyIdMismatchRejectedOnCustomers() {
        val foreignCompanyId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890")
        val golden = File(fixturesDir, "golden-customer-directory.json").readText(Charsets.UTF_8)
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(statusCode = 200, body = golden)

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getCustomers(dummyToken, foreignCompanyId)

        assertTrue("Expected ParseFailure on customers company_id mismatch, got $result", result is ReadClientResult.ParseFailure)
    }

    @Test
    fun testLoginActorEmailMismatchRejected() {
        val golden = File(fixturesDir, "golden-auth-login.json").readText(Charsets.UTF_8)
        val goldenRes = StrictJsonParser.parse(golden).asObject().require("response")
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(goldenRes)
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthLoginRequest(
            challengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"),
            nonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef",
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceName = "Tablet A",
            email = "different.user@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!"
        )
        val result = client.authLogin(request)

        assertTrue("Actor email mismatch must yield ParseFailure", result is ReadClientResult.ParseFailure)
    }

    @Test
    fun testLoginActorEmailCaseInsensitiveMatchAccepted() {
        val golden = File(fixturesDir, "golden-auth-login.json").readText(Charsets.UTF_8)
        val goldenRes = StrictJsonParser.parse(golden).asObject().require("response")
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(goldenRes)
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthLoginRequest(
            challengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"),
            nonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef",
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceName = "Tablet A",
            email = "CASHIER@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!"
        )
        val result = client.authLogin(request)

        assertTrue("Case-insensitive email match must succeed", result is ReadClientResult.Success)
    }

    @Test
    fun testHostileTransportExceptionNoSecretLeak() {
        val transport = MockTransport()
        transport.exceptionToThrow = IOException("Hostile leak: password=SuperSecretSecret123! token=sanctum_secret_token_abc")

        val client = SmallTraderReadClient(transport, baseOrigin)
        val result = client.getBootstrap(dummyToken, dummyCompanyId)

        assertTrue(result is ReadClientResult.NetworkUnknown)
        val nu = result as ReadClientResult.NetworkUnknown
        assertEquals("NETWORK_ERROR", nu.category)
        assertFalse("Must not leak password", nu.toString().contains("SuperSecretSecret123!"))
        assertFalse("Must not leak token", nu.toString().contains("sanctum_secret_token_abc"))
        assertFalse("Must not expose raw exception message", nu.toString().contains("Hostile leak"))
    }

    @Test
    fun testMalformedLoginSecretBodyNoSecretLeak() {
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(
            statusCode = 200,
            body = "{\"status\": 42, \"password\": \"SuperSecretSecret123!\""
        )

        val client = SmallTraderReadClient(transport, baseOrigin)
        val request = AuthLoginRequest(
            challengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"),
            nonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef",
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceName = "Tablet A",
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!"
        )
        val result = client.authLogin(request)

        assertTrue(result is ReadClientResult.ParseFailure)
        val pf = result as ReadClientResult.ParseFailure
        assertEquals("PARSE_ERROR", pf.category)
        assertFalse("Must not leak secret in toString", pf.toString().contains("SuperSecretSecret123!"))
    }

    @Test
    fun testUnexpected201And204NotParsedAsSuccess() {
        val transport = MockTransport()
        transport.responseToReturn = TransportResponse(statusCode = 201, body = "{}")

        val client = SmallTraderReadClient(transport, baseOrigin)
        val res201 = client.getBootstrap(dummyToken, dummyCompanyId)
        assertTrue("Unexpected 201 must yield HttpError, got $res201", res201 is ReadClientResult.HttpError)
        assertEquals(201, (res201 as ReadClientResult.HttpError).statusCode)

        transport.responseToReturn = TransportResponse(statusCode = 204, body = "")
        val res204 = client.getBootstrap(dummyToken, dummyCompanyId)
        assertTrue("Unexpected 204 must yield HttpError, got $res204", res204 is ReadClientResult.HttpError)
        assertEquals(204, (res204 as ReadClientResult.HttpError).statusCode)
    }

    @Test
    fun testDispatchedRequestCarriesOriginAndValidatesMethodPath() {
        val transport = MockTransport()
        val golden = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
        transport.responseToReturn = TransportResponse(statusCode = 200, body = golden)

        val client = SmallTraderReadClient(transport, baseOrigin)
        client.getBootstrap(dummyToken, dummyCompanyId)

        val req = transport.lastRequest
        assertNotNull(req)
        assertEquals("https://api.smalltrader.example", req?.baseOrigin)
        assertEquals(HttpMethod.GET, req?.method)
        assertEquals("/api/mobile/v1/companies/${dummyCompanyId.value}/bootstrap", req?.path)
    }
}
