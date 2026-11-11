package com.example.data.mobile.readclient.http

import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.TransportRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class HttpUrlConnectionTransportSecurityTest {

    private val configuredOrigin = "https://api.smalltrader.example"
    private val validBootstrapPath = "/api/mobile/v1/companies/01ARZ3NDEKTSV4RRFFQ69G5FAV/bootstrap"
    private val validProductsPath = "/api/mobile/v1/companies/01ARZ3NDEKTSV4RRFFQ69G5FAV/products"
    private val validChallengePath = "/api/mobile/v1/auth/challenge"
    private val validLoginPath = "/api/mobile/v1/auth/login"
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"

    private class TrackingFactory : HttpUrlConnectionFactory {
        var callCount = 0
        var lastUrl: URL? = null

        override fun openConnection(url: URL): MockHttpURLConnection {
            callCount++
            lastUrl = url
            return MockHttpURLConnection(url)
        }
    }

    @Test
    fun testWrongOriginRejectedBeforeFactoryInvoked() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        val mismatchedRequest = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = "https://evil.smalltrader.example",
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(mismatchedRequest)
        }

        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex.category)
        assertTrue(ex.message!!.contains("Request baseOrigin does not match configured transport origin"))
        assertEquals(0, factory.callCount)
        assertNull(factory.lastUrl)
    }

    @Test
    fun testDisallowedPathRejectedBeforeFactoryInvoked() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        val unallowlistedPaths = listOf(
            "/api/v1/sales",
            "/api/mobile/v1/unknown",
            "/admin/users",
            "/api/mobile/v1/auth/login/bypass"
        )

        for (path in unallowlistedPaths) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = path,
                baseOrigin = configuredOrigin,
                headers = mapOf("Accept" to "application/json")
            )
            val ex = assertThrows(HttpTransportException::class.java) {
                transport.execute(req)
            }
            assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex.category)
        }

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testDisallowedMethodAndPathCombinationsRejectedBeforeFactory() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        // POST to products (only GET allowed)
        val postProducts = TransportRequest(
            method = HttpMethod.POST,
            path = validProductsPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = "{}"
        )
        val ex1 = assertThrows(HttpTransportException::class.java) {
            transport.execute(postProducts)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex1.category)

        // GET to login (only POST allowed)
        val getLogin = TransportRequest(
            method = HttpMethod.GET,
            path = validLoginPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )
        val ex2 = assertThrows(HttpTransportException::class.java) {
            transport.execute(getLogin)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex2.category)

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testDisallowedHeadersRejectedBeforeFactory() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        val requestWithCookie = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Cookie" to "session=secret_cookie_val"
            )
        )
        val ex1 = assertThrows(HttpTransportException::class.java) {
            transport.execute(requestWithCookie)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex1.category)
        assertTrue(ex1.message!!.contains("Disallowed request header"))

        val requestWithArbitraryHeader = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "X-Custom-Auth" to "attacker_header"
            )
        )
        val ex2 = assertThrows(HttpTransportException::class.java) {
            transport.execute(requestWithArbitraryHeader)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex2.category)

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testControlledHeadersValidation() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        val badAccept = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "text/html")
        )
        val ex1 = assertThrows(HttpTransportException::class.java) {
            transport.execute(badAccept)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex1.category)

        val badContentType = TransportRequest(
            method = HttpMethod.POST,
            path = validChallengePath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "text/plain"
            ),
            body = "{}"
        )
        val ex2 = assertThrows(HttpTransportException::class.java) {
            transport.execute(badContentType)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex2.category)

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testAuthorizationTokenValidation() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        // Missing "Bearer " prefix
        val noBearer = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to validToken
            )
        )
        val ex1 = assertThrows(HttpTransportException::class.java) {
            transport.execute(noBearer)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex1.category)
        assertTrue(ex1.message!!.contains("must begin with 'Bearer '"))

        // CRLF injection in token
        val crlfToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer valid_prefix_token_12345\r\nInjected-Header: evil"
            )
        )
        val ex2 = assertThrows(HttpTransportException::class.java) {
            transport.execute(crlfToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex2.category)

        // Control characters in token
        val controlCharToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer valid_token_with_null\u0000char_12345"
            )
        )
        val ex3 = assertThrows(HttpTransportException::class.java) {
            transport.execute(controlCharToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex3.category)

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testPaginationCursorValidation() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        // Cursor exceeding 255 chars
        val longCursor = "a".repeat(256)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validProductsPath,
            baseOrigin = configuredOrigin,
            queryParams = mapOf("cursor" to longCursor),
            headers = mapOf("Accept" to "application/json")
        )
        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex.category)
        assertEquals(0, factory.callCount)
    }

    @Test
    fun testOriginConfigurationInvariants() {
        // HTTPS is required by default
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport("http://api.smalltrader.example")
        }

        // Loopback HTTP without explicit flag is rejected
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport("http://127.0.0.1:8000")
        }

        // Broad remote HTTP is ALWAYS rejected even with allowHttpLoopback
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport("http://api.smalltrader.example", allowHttpLoopback = true)
        }

        // Valid loopback hosts with explicit opt-in
        val loopback1 = HttpUrlConnectionTransport("http://127.0.0.1:8000", allowHttpLoopback = true)
        assertEquals("http://127.0.0.1:8000", loopback1.validatedOrigin)

        val loopback2 = HttpUrlConnectionTransport("http://localhost:8080", allowHttpLoopback = true)
        assertEquals("http://localhost:8080", loopback2.validatedOrigin)

        val loopback3 = HttpUrlConnectionTransport("http://10.0.2.2:8000", allowHttpLoopback = true)
        assertEquals("http://10.0.2.2:8000", loopback3.validatedOrigin)
    }

    @Test
    fun testTimeoutBoundsEnforcedInConstructor() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport(configuredOrigin, connectTimeoutMs = 50) // below 100ms
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport(configuredOrigin, connectTimeoutMs = 70_000) // above 60s
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport(configuredOrigin, readTimeoutMs = 10) // below 100ms
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpUrlConnectionTransport(configuredOrigin, readTimeoutMs = 90_000) // above 60s
        }
    }

    @Test
    fun testZeroSensitiveLeakageInToStringAndExceptions() {
        val ex = HttpTransportException(
            category = HttpTransportException.CATEGORY_SECURITY_VIOLATION,
            message = "Disallowed request header"
        )
        val str = ex.toString()
        assertEquals("HttpTransportException(category='SECURITY_VIOLATION', message='Disallowed request header')", str)
        assertFalse(str.contains("secret"))
        assertFalse(str.contains("token"))
        assertNull(ex.cause)

        val transport = HttpUrlConnectionTransport(configuredOrigin)
        val transportStr = transport.toString()
        assertEquals("HttpUrlConnectionTransport(configuredOrigin='https://api.smalltrader.example', allowHttpLoopback=false)", transportStr)
        assertFalse(transportStr.contains("password"))
        assertFalse(transportStr.contains("secret"))
    }
}
