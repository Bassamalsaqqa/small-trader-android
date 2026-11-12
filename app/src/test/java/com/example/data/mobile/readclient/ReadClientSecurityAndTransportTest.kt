package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonElement
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ReadClientSecurityAndTransportTest {

    @Test
    fun testBaseOriginValidation() {
        // Valid HTTPS origins (default)
        assertEquals("https://api.smalltrader.example", EndpointSecurity.validateBaseOrigin("https://api.smalltrader.example"))
        assertEquals("https://api.smalltrader.example:8443", EndpointSecurity.validateBaseOrigin("https://api.smalltrader.example:8443/"))

        // Explicit dev/test loopback opt-in allows HTTP loopback only
        assertEquals("http://10.0.2.2:8000", EndpointSecurity.validateBaseOrigin("http://10.0.2.2:8000/", allowHttpLoopback = true))
        assertEquals("http://127.0.0.1:8000", EndpointSecurity.validateBaseOrigin("http://127.0.0.1:8000", allowHttpLoopback = true))
        assertEquals("http://localhost:8000", EndpointSecurity.validateBaseOrigin("http://localhost:8000", allowHttpLoopback = true))

        // Loopback HTTP without explicit opt-in is strictly rejected
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("http://10.0.2.2:8000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("http://127.0.0.1:8000")
        }

        // Broad remote HTTP is ALWAYS rejected even with opt-in
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("http://api.smalltrader.example", allowHttpLoopback = true)
        }

        // Invalid origins: path, ftp, userinfo, query, fragment, invalid port
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("https://api.smalltrader.example/api/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("ftp://smalltrader.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("https://user:pass@smalltrader.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("https://smalltrader.example?foo=bar")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("https://smalltrader.example#fragment")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("https://smalltrader.example:99999")
        }
    }

    @Test
    fun testEndpointAllowlistEnforcement() {
        // Allowlisted endpoints pass
        EndpointSecurity.validatePath("/api/mobile/v1/auth/challenge")
        EndpointSecurity.validatePath("/api/mobile/v1/auth/login")
        EndpointSecurity.validatePath("/api/mobile/v1/auth/logout")
        EndpointSecurity.validatePath("/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")
        EndpointSecurity.validatePath("/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")
        EndpointSecurity.validatePath("/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/customers")

        // Non-allowlisted paths are rejected
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/mobile/v1/devices/8e2b8bc6-f80c-4034-8c88-59368d1f2e10/revoke")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/sales/commit")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validatePath("/api/v2/arbitrary")
        }
    }

    @Test
    fun testMethodAndPathValidation() {
        // Valid POST endpoints
        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/auth/challenge")
        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/auth/login")
        EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/auth/logout")

        // Valid GET endpoints
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/customers")

        // Invalid pairs: GET on login or POST on bootstrap
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/auth/login")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateMethodAndPath(HttpMethod.POST, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")
        }
    }

    @Test
    fun testSecretRedactionInToString() {
        val req = AuthLoginRequest(
            challengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"),
            nonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef",
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceName = "Tablet A",
            email = "cashier@smalltrader.example",
            password = "SuperSecretPassword123!",
            mfaCode = "889977"
        )
        val reqStr = req.toString()
        assertFalse("Password must be redacted in toString()", reqStr.contains("SuperSecretPassword123!"))
        assertFalse("mfaCode must be redacted in toString()", reqStr.contains("889977"))
        assertTrue("Redaction tag must be present", reqStr.contains("[REDACTED]"))

        val transportReq = TransportRequest(
            method = HttpMethod.POST,
            path = "/api/mobile/v1/auth/login",
            baseOrigin = "https://api.smalltrader.example",
            headers = mapOf("Authorization" to "Bearer my_super_secret_bearer_token"),
            body = req.toJsonString()
        )
        val transportStr = transportReq.toString()
        assertFalse("Bearer token must be redacted in TransportRequest.toString()", transportStr.contains("my_super_secret_bearer_token"))
        assertFalse("Login password must be redacted in TransportRequest.toString()", transportStr.contains("SuperSecretPassword123!"))
        assertTrue(transportStr.contains("[LOGIN_CREDENTIALS_REDACTED]"))
        assertTrue(transportStr.contains("Bearer [REDACTED]"))

        val transportRes = TransportResponse(
            statusCode = 200,
            headers = mapOf("Server" to "Apache"),
            body = "{\"secret_internal_key\":\"classified_value\"}"
        )
        val resStr = transportRes.toString()
        assertFalse("TransportResponse body must be redacted", resStr.contains("classified_value"))
        assertTrue("TransportResponse must show [REDACTED]", resStr.contains("[REDACTED]"))
    }

    @Test
    fun testErrorResponseToStringRedactionAndUntrustedCode() {
        // Trusted uppercase error code
        val safeErr = ReadClientErrorResponse(
            code = "TENANT_ACCESS_DENIED",
            message = "Arbitrary sensitive server error message with internal stack traces",
            details = StrictJsonObject(mapOf("secret" to StrictJsonString("sensitive")))
        )
        val safeStr = safeErr.toString()
        assertTrue("Safe code preserved", safeStr.contains("code='TENANT_ACCESS_DENIED'"))
        assertFalse("Message must be redacted", safeStr.contains("internal stack traces"))
        assertFalse("Details must be redacted", safeStr.contains("sensitive"))
        assertTrue("Message must be [REDACTED]", safeStr.contains("message='[REDACTED]'"))

        // Untrusted code shape (e.g. lowercase, script, injection)
        val untrustedErr = ReadClientErrorResponse(
            code = "<script>alert(1)</script>",
            message = "Some error"
        )
        val untrustedStr = untrustedErr.toString()
        assertTrue("Untrusted code shape masked as [UNTRUSTED_CODE]", untrustedStr.contains("code='[UNTRUSTED_CODE]'"))
    }

    @Test
    fun testNoArbitraryExternalUrlFollowingInCursor() {
        // Valid opaque tokens including '/' and '=' (e.g. base64-encoded pagination cursors)
        EndpointSecurity.validateCursor("cursor_token_12345")
        EndpointSecurity.validateCursor("c29tZS9jdXJzb3I9PQ==")
        val query = PaginationQuery(cursor = "c29tZS9jdXJzb3I9PQ==")
        assertEquals("c29tZS9jdXJzb3I9PQ==", query.cursor)

        // Even URL-looking text stays in one opaque query value; it cannot replace the path.
        for (cursor in listOf("", "a/b+=", "https://example.invalid/?q=x#part", "//opaque", "بيانات", "cursor with space", "cursor\r\ndata")) {
            assertEquals(cursor, PaginationQuery(cursor = cursor).toQueryParams()["cursor"])
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(cursor = "a".repeat(256))
        }
    }

    @Test
    fun testOriginValidationDoesNotResolveNamesOrExposeCredentials() {
        // Numeric shorthand resolves to loopback in InetAddress, but is not an approved local origin.
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("http://127.1", allowHttpLoopback = true)
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            EndpointSecurity.validateBaseOrigin("https://user:secret-password@example.invalid")
        }
        assertFalse(error.toString().contains("secret-password"))
        assertEquals("https://unresolvable.invalid", EndpointSecurity.validateBaseOrigin("https://unresolvable.invalid"))
    }

    @Test
    fun testPaginationLimitBounds() {
        assertEquals(50, PaginationQuery().limit)
        assertEquals(1, PaginationQuery(limit = 1).limit)
        assertEquals(100, PaginationQuery(limit = 100).limit)

        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(limit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(limit = 101)
        }
    }

    @Test
    fun testBearerTokenValidationAndSanctumPipeSupport() {
        // Valid Laravel Sanctum token with pipe '|'
        val sanctumToken = "42|sanctum_mobile_token_982347109238471092384710"
        ValidationUtils.validateToken(sanctumToken)

        // Length < 20 rejected
        assertThrows(com.example.data.mobile.contract.CanonicalSerializationException::class.java) {
            ValidationUtils.validateToken("short_token_12345")
        }

        // Whitespace rejected
        assertThrows(com.example.data.mobile.contract.CanonicalSerializationException::class.java) {
            ValidationUtils.validateToken("sanctum token with whitespace 12345")
        }

        // CRLF rejected
        assertThrows(com.example.data.mobile.contract.CanonicalSerializationException::class.java) {
            ValidationUtils.validateToken("sanctum_token\r\ninjection_12345")
        }
    }

    @Test
    fun testDefensiveCopiesProtectAgainstExternalMutation() {
        // Mutable list passed to CustomerDirectoryResponse
        val mutableCustomers = ArrayList<CustomerDirectoryItem>()
        mutableCustomers.add(
            CustomerDirectoryItem(
                id = ServerUlid.parse("01JCV8X9M4K1P2Q3R4S5T6V7W8"),
                code = "CUST-001",
                name = "Initial Customer",
                phone = null,
                isWalkIn = false
            )
        )
        val directory = CustomerDirectoryResponse(
            companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"),
            generatedAt = "2026-11-10T12:00:00Z",
            nextCursor = null,
            hasMore = false,
            customers = mutableCustomers
        )
        assertEquals(1, directory.customers.size)

        // Mutating original list externally must NOT mutate directory.customers
        mutableCustomers.add(
            CustomerDirectoryItem(
                id = ServerUlid.parse("01JCV8X9M5T2V3V4W5X6Y7Z890"),
                code = "CUST-002",
                name = "Injected Customer",
                phone = null,
                isWalkIn = false
            )
        )
        assertEquals("Defensive copy must isolate directory.customers", 1, directory.customers.size)

        // Directory list must be unmodifiable
        assertThrows(UnsupportedOperationException::class.java) {
            (directory.customers as MutableList).clear()
        }
    }
}
