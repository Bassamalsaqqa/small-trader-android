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
            assertEquals("Disallowed HTTP method or endpoint path", ex.message)
            assertFalse("Error message must not leak requested path", ex.message!!.contains(path))
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
    fun testGetWithBodyRejectedBeforeFactory() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        val getWithBody = TransportRequest(
            method = HttpMethod.GET,
            path = validProductsPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json"),
            body = "{\"illegal\":\"body_on_get\"}"
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(getWithBody)
        }

        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex.category)
        assertEquals("GET requests must not contain a request body", ex.message)
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
        assertEquals("Disallowed request header", ex1.message)
        assertFalse("Error message must not leak header name", ex1.message!!.contains("Cookie"))

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
        assertEquals("Disallowed request header", ex2.message)
        assertFalse("Error message must not leak header name", ex2.message!!.contains("X-Custom-Auth"))

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testControlledHeadersValidation() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        val badAccepts = listOf(
            "text/html",
            "text/html; application/json",
            "application/json-evil",
            "*/*"
        )
        for (badAccept in badAccepts) {
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = validBootstrapPath,
                baseOrigin = configuredOrigin,
                headers = mapOf("Accept" to badAccept)
            )
            val ex = assertThrows(HttpTransportException::class.java) {
                transport.execute(req)
            }
            assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex.category)
            assertEquals("Controlled header 'Accept' must specify application/json", ex.message)
        }

        val badContentTypes = listOf(
            "text/plain",
            "application/json-evil",
            "text/html; charset=utf-8",
            "application/xml"
        )
        for (badContentType in badContentTypes) {
            val req = TransportRequest(
                method = HttpMethod.POST,
                path = validChallengePath,
                baseOrigin = configuredOrigin,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to badContentType
                ),
                body = "{}"
            )
            val ex = assertThrows(HttpTransportException::class.java) {
                transport.execute(req)
            }
            assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex.category)
            assertEquals("Controlled header 'Content-Type' must specify application/json", ex.message)
        }

        // Duplicate Accept headers rejected
        val duplicateAcceptReq = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "accept" to "application/json"
            )
        )
        val dupAcceptEx = assertThrows(HttpTransportException::class.java) {
            transport.execute(duplicateAcceptReq)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, dupAcceptEx.category)
        assertEquals("Duplicate Accept header", dupAcceptEx.message)

        // Duplicate Content-Type headers rejected
        val duplicateContentTypeReq = TransportRequest(
            method = HttpMethod.POST,
            path = validChallengePath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "content-type" to "application/json"
            ),
            body = "{}"
        )
        val dupContentTypeEx = assertThrows(HttpTransportException::class.java) {
            transport.execute(duplicateContentTypeReq)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, dupContentTypeEx.category)
        assertEquals("Duplicate Content-Type header", dupContentTypeEx.message)

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
        assertEquals("Controlled header 'Authorization' must begin with 'Bearer '", ex1.message)

        // Leading whitespace after "Bearer " (token starts with space)
        val leadingSpaceToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer  1|sanctum_valid_token_test_1234567890_abcdef"
            )
        )
        val exLeading = assertThrows(HttpTransportException::class.java) {
            transport.execute(leadingSpaceToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, exLeading.category)
        assertEquals("Invalid Bearer token in Authorization header", exLeading.message)

        // Trailing whitespace in Authorization token
        val trailingSpaceToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer 1|sanctum_valid_token_test_1234567890_abcdef "
            )
        )
        val exTrailing = assertThrows(HttpTransportException::class.java) {
            transport.execute(trailingSpaceToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, exTrailing.category)
        assertEquals("Invalid Bearer token in Authorization header", exTrailing.message)

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

        // Tab character in token
        val tabToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer valid_token_with_\ttab_12345"
            )
        )
        val exTab = assertThrows(HttpTransportException::class.java) {
            transport.execute(tabToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, exTab.category)

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

        // Token too short (< 20 characters)
        val shortToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer short_token_123"
            )
        )
        val exShort = assertThrows(HttpTransportException::class.java) {
            transport.execute(shortToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, exShort.category)

        // Token too long (> 2048 characters)
        val longToken = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer " + "a".repeat(2049)
            )
        )
        val exLong = assertThrows(HttpTransportException::class.java) {
            transport.execute(longToken)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, exLong.category)

        // Case-insensitive duplicate Authorization headers rejected
        val duplicateAuthReq = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Authorization" to "Bearer $validToken",
                "authorization" to "Bearer $validToken"
            )
        )
        val exDup = assertThrows(HttpTransportException::class.java) {
            transport.execute(duplicateAuthReq)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, exDup.category)
        assertEquals("Duplicate Authorization header", exDup.message)

        assertEquals(0, factory.callCount)
    }

    @Test
    fun testRequestBodyBoundingAndSurrogateProtection() {
        val factory = TrackingFactory()
        val transport = HttpUrlConnectionTransport(
            configuredOrigin = configuredOrigin,
            connectionFactory = factory
        )

        // Body exceeding 1 MiB rejected before factory
        val oversizeBody = "a".repeat(1024 * 1024 + 1)
        val oversizeReq = TransportRequest(
            method = HttpMethod.POST,
            path = validChallengePath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = oversizeBody
        )
        val ex1 = assertThrows(HttpTransportException::class.java) {
            transport.execute(oversizeReq)
        }
        assertEquals(HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE, ex1.category)

        // Body with invalid unpaired UTF-16 surrogate
        val malformedSurrogateBody = "{\"name\":\"unpaired\uD800surrogate\"}"
        val malformedReq = TransportRequest(
            method = HttpMethod.POST,
            path = validChallengePath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = malformedSurrogateBody
        )
        val ex2 = assertThrows(HttpTransportException::class.java) {
            transport.execute(malformedReq)
        }
        assertEquals(HttpTransportException.CATEGORY_SECURITY_VIOLATION, ex2.category)
        assertTrue(ex2.message!!.contains("malformed or unmappable UTF-8"))

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
