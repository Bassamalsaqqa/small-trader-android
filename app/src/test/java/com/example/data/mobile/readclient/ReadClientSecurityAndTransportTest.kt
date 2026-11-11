package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadClientSecurityAndTransportTest {

    @Test
    fun testBaseOriginValidation() {
        // Valid origins
        assertEquals("https://api.smalltrader.example", EndpointSecurity.validateBaseOrigin("https://api.smalltrader.example"))
        assertEquals("http://10.0.2.2:8000", EndpointSecurity.validateBaseOrigin("http://10.0.2.2:8000/"))

        // Invalid origins
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
            headers = mapOf("Authorization" to "Bearer my_super_secret_bearer_token"),
            body = req.toJsonString()
        )
        val transportStr = transportReq.toString()
        assertFalse("Bearer token must be redacted in TransportRequest.toString()", transportStr.contains("my_super_secret_bearer_token"))
        assertFalse("Login password must be redacted in TransportRequest.toString()", transportStr.contains("SuperSecretPassword123!"))
        assertTrue(transportStr.contains("[LOGIN_CREDENTIALS_REDACTED]"))
        assertTrue(transportStr.contains("Bearer [REDACTED]"))
    }

    @Test
    fun testNoArbitraryExternalUrlFollowingInCursor() {
        // Valid opaque token
        EndpointSecurity.validateCursor("cursor_token_12345")
        val query = PaginationQuery(cursor = "cursor_token_12345")
        assertEquals("cursor_token_12345", query.cursor)

        // External URLs or path traversals rejected
        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(cursor = "https://evil.com/leak")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(cursor = "/api/mobile/v1/other")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(cursor = "cursor?query=param")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaginationQuery(cursor = "cursor#fragment")
        }
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
}
