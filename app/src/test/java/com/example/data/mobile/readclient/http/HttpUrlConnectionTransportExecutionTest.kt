package com.example.data.mobile.readclient.http

import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.TransportRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

class HttpUrlConnectionTransportExecutionTest {

    private val configuredOrigin = "https://api.smalltrader.example"
    private val validBootstrapPath = "/api/mobile/v1/companies/01ARZ3NDEKTSV4RRFFQ69G5FAV/bootstrap"
    private val validProductsPath = "/api/mobile/v1/companies/01ARZ3NDEKTSV4RRFFQ69G5FAV/products"
    private val validChallengePath = "/api/mobile/v1/auth/challenge"
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"

    @Test
    fun testSuccessfulGetDispatchWithHeadersAndQueryEncoding() {
        var capturedConnection: MockHttpURLConnection? = null
        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = "{\"data\":\"products_ok\"}".toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validProductsPath,
            baseOrigin = configuredOrigin,
            queryParams = mapOf(
                "cursor" to "opaque/token+chunk==",
                "limit" to "25",
                "search" to "coffee & pastries"
            ),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val response = transport.execute(req)

        assertEquals(200, response.statusCode)
        assertEquals("{\"data\":\"products_ok\"}", response.body)

        val conn = capturedConnection!!
        assertTrue(conn.isConnected())
        assertTrue(conn.disconnected)
        assertTrue(conn.inputStreamClosed)
        assertFalse(conn.instanceFollowRedirects)
        assertFalse("useCaches must be false per connection", conn.useCaches)
        assertEquals("GET", conn.requestMethod)
        assertEquals(HttpUrlConnectionTransport.DEFAULT_CONNECT_TIMEOUT_MS, conn.connectTimeout)
        assertEquals(HttpUrlConnectionTransport.DEFAULT_READ_TIMEOUT_MS, conn.readTimeout)
        assertEquals("application/json", conn.getRequestProperty("Accept"))
        assertEquals("Bearer $validToken", conn.getRequestProperty("Authorization"))

        // Verify deterministic UTF-8 percent-encoded query string in URL
        val urlString = conn.url.toString()
        assertTrue(urlString.startsWith("https://api.smalltrader.example$validProductsPath?"))
        assertTrue(urlString.contains("cursor=opaque%2Ftoken%2Bchunk%3D%3D"))
        assertTrue(urlString.contains("limit=25"))
        assertTrue(urlString.contains("search=coffee+%26+pastries"))
    }

    @Test
    fun testGlobalRedirectUnchangedAndInstanceFlagRetained() {
        val originalGlobalRedirect = HttpURLConnection.getFollowRedirects()
        try {
            // Set global redirect state to true
            HttpURLConnection.setFollowRedirects(true)
            assertTrue("Global follow redirects must be true before execute", HttpURLConnection.getFollowRedirects())

            var capturedConnection: MockHttpURLConnection? = null
            val factory = HttpUrlConnectionFactory { url ->
                MockHttpURLConnection(
                    url = url,
                    statusCodeToReturn = 200,
                    responseBodyBytes = "{}".toByteArray(StandardCharsets.UTF_8),
                    responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
                ).also { capturedConnection = it }
            }

            val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)
            val req = TransportRequest(
                method = HttpMethod.GET,
                path = validBootstrapPath,
                baseOrigin = configuredOrigin,
                headers = mapOf("Accept" to "application/json")
            )

            transport.execute(req)

            // Global process-wide follow redirects MUST remain unchanged (true)
            assertTrue(
                "execute() must NOT mutate process-global HttpURLConnection.setFollowRedirects",
                HttpURLConnection.getFollowRedirects()
            )
            // But the individual connection instance MUST have instanceFollowRedirects = false
            assertFalse(
                "connection.instanceFollowRedirects must be strictly false",
                capturedConnection!!.instanceFollowRedirects
            )
        } finally {
            HttpURLConnection.setFollowRedirects(originalGlobalRedirect)
        }
    }

    @Test
    fun testPerConnectionUseCachesDisabledAndDefaultUseCachesUnmutated() {
        var capturedConnection: MockHttpURLConnection? = null
        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = "{}".toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        transport.execute(req)

        val conn = capturedConnection!!
        assertFalse("Per-connection useCaches must be disabled", conn.useCaches)
        // defaultUseCaches on URLConnection should remain untouched (typically true)
        assertTrue("defaultUseCaches global protocol defaults must not be mutated to false", conn.defaultUseCaches)
    }

    @Test
    fun testSuccessfulPostDispatchWithRequestBody() {
        var capturedConnection: MockHttpURLConnection? = null
        val requestJson = "{\"device_id\":\"8e2b8bc6-f80c-4034-8c88-59368d1f2e10\",\"app_version\":\"1.0.0\",\"platform\":\"android\"}"
        val responseJson = "{\"challenge\":\"nonce_1234567890\",\"expires_at\":\"2026-11-11T20:00:00Z\"}"

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = responseJson.toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.POST,
            path = validChallengePath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = requestJson
        )

        val response = transport.execute(req)

        assertEquals(200, response.statusCode)
        assertEquals(responseJson, response.body)

        val conn = capturedConnection!!
        assertTrue(conn.outputStreamClosed)
        assertTrue(conn.inputStreamClosed)
        assertTrue(conn.disconnected)
        assertEquals("POST", conn.requestMethod)
        assertEquals("application/json; charset=utf-8", conn.getRequestProperty("Content-Type"))
        assertEquals(requestJson, conn.writtenOutput.toString("UTF-8"))
    }

    @Test
    fun testRedirectNotFollowedAndCredentialsNotForwarded() {
        var capturedConnection: MockHttpURLConnection? = null
        var requestCount = 0

        val factory = HttpUrlConnectionFactory { url ->
            requestCount++
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 302,
                responseBodyBytes = "{\"error\":\"Found redirect\"}".toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = mapOf(
                    "Location" to listOf("https://attacker.example/drain"),
                    "Content-Type" to listOf("application/json")
                )
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val response = transport.execute(req)

        // Verifies redirect status passed back directly without following
        assertEquals(302, response.statusCode)
        assertEquals("https://attacker.example/drain", response.headers["Location"])
        assertEquals("{\"error\":\"Found redirect\"}", response.body)

        // Verifies exactly ONE request was made (no following of redirect)
        assertEquals(1, requestCount)
        assertFalse(capturedConnection!!.instanceFollowRedirects)
    }

    @Test
    fun test3xxResponseReadsInputStreamBody() {
        val redirectPayload = "{\"message\":\"Moved Temporarily\"}"
        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 307,
                responseBodyBytes = redirectPayload.toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = mapOf(
                    "Location" to listOf("https://api.smalltrader.example/next"),
                    "Content-Type" to listOf("application/json")
                )
            )
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val response = transport.execute(req)
        assertEquals(307, response.statusCode)
        assertEquals("https://api.smalltrader.example/next", response.headers["Location"])
        assertEquals(redirectPayload, response.body)
    }

    @Test
    fun testContentLengthPrecheckRejectsPayloadExceeding1MiB() {
        var capturedConnection: MockHttpURLConnection? = null
        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = ByteArray(10),
                responseHeadersToReturn = mapOf("Content-Length" to listOf("1048577")) // 1 MiB + 1 byte
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }

        assertEquals(HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE, ex.category)
        assertTrue(ex.message!!.contains("exceeds maximum limit"))
        assertTrue(capturedConnection!!.disconnected)
    }

    @Test
    fun testStreamingOversizeRejectionWhenContentLengthUnknownOrChunked() {
        var capturedConnection: MockHttpURLConnection? = null
        // 1 MiB + 100 bytes
        val oversizeBytes = ByteArray(1024 * 1024 + 100) { 0x61 } // 'a' characters

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = oversizeBytes,
                responseHeadersToReturn = mapOf("Transfer-Encoding" to listOf("chunked")) // Unknown content length
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }

        assertEquals(HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE, ex.category)
        assertTrue(ex.message!!.contains("stream exceeded maximum allowed limit"))
        assertTrue(capturedConnection!!.disconnected)
        assertTrue(capturedConnection!!.inputStreamClosed)
    }

    @Test
    fun testStrictMalformedUtf8Rejection() {
        var capturedConnection: MockHttpURLConnection? = null
        // Malformed UTF-8 byte sequence: 0xC0 0xAF (invalid overlong sequence in UTF-8)
        val malformedBytes = byteArrayOf(0xC0.toByte(), 0xAF.toByte())

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = malformedBytes,
                responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }

        assertEquals(HttpTransportException.CATEGORY_MALFORMED_UTF8, ex.category)
        assertTrue(ex.message!!.contains("malformed or unmappable UTF-8"))
        assertTrue(capturedConnection!!.disconnected)
        assertTrue(capturedConnection!!.inputStreamClosed)
    }

    @Test
    fun testProperResourceCloseOnErrorBodyPath() {
        var capturedConnection: MockHttpURLConnection? = null
        val errorJson = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Token expired\"}}"

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 401,
                responseBodyBytes = ByteArray(0),
                errorBodyBytes = errorJson.toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val response = transport.execute(req)

        assertEquals(401, response.statusCode)
        assertEquals(errorJson, response.body)

        val conn = capturedConnection!!
        assertTrue(conn.errorStreamClosed)
        assertTrue(conn.disconnected)
    }

    @Test
    fun test401WithNullErrorStreamReturnsAuthoritativeStatusWithEmptyBody() {
        var capturedConnection: MockHttpURLConnection? = null

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 401,
                responseBodyBytes = ByteArray(0),
                errorBodyBytes = null, // null errorStream, e.g. empty 401 response
                responseHeadersToReturn = mapOf("Content-Type" to listOf("application/json"))
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val response = transport.execute(req)

        // Verifies authoritative 401 status code is returned even when error body is absent
        assertEquals(401, response.statusCode)
        assertEquals("", response.body)
        assertTrue(capturedConnection!!.disconnected)
    }

    @Test
    fun testTimeoutDuringErrorStreamReadingThrowsNetworkTimeoutException() {
        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 500,
                errorStreamException = SocketTimeoutException("Read timed out on error stream")
            )
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }

        assertEquals(HttpTransportException.CATEGORY_TIMEOUT, ex.category)
        assertEquals("Network socket timeout occurred", ex.message)
    }

    @Test
    fun testResponseHeadersNullSafeHandling() {
        val factory = HttpUrlConnectionFactory { url ->
            // Simulates status line with null key and a list containing a null entry
            val rawMap = HashMap<String?, List<String?>?>()
            rawMap[null] = listOf("HTTP/1.1 200 OK")
            rawMap["Content-Type"] = listOf("application/json", null)
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                responseBodyBytes = "{}".toByteArray(StandardCharsets.UTF_8),
                responseHeadersToReturn = rawMap
            )
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val response = transport.execute(req)
        assertEquals(200, response.statusCode)
        assertFalse(response.headers.containsKey(""))
        assertEquals("application/json", response.headers["Content-Type"])
    }

    @Test
    fun testProperResourceCloseOnServerErrorWithoutBody() {
        var capturedConnection: MockHttpURLConnection? = null

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 500,
                responseBodyBytes = ByteArray(0),
                errorBodyBytes = null,
                inputStreamException = IOException("Server error stream closed")
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val response = transport.execute(req)

        assertEquals(500, response.statusCode)
        assertEquals("", response.body)
        assertTrue(capturedConnection!!.disconnected)
    }

    @Test
    fun testTimeoutHandling() {
        var capturedConnection: MockHttpURLConnection? = null

        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                inputStreamException = SocketTimeoutException("Read timed out")
            ).also { capturedConnection = it }
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }

        assertEquals(HttpTransportException.CATEGORY_TIMEOUT, ex.category)
        assertTrue(capturedConnection!!.disconnected)
    }

    @Test
    fun testNetworkIoHandling() {
        val factory = HttpUrlConnectionFactory { _ ->
            throw IOException("Failed to establish TCP socket")
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }

        assertEquals(HttpTransportException.CATEGORY_NETWORK_IO, ex.category)
    }

    @Test
    fun testCancellationPropagation() {
        val factory = HttpUrlConnectionFactory { url ->
            MockHttpURLConnection(
                url = url,
                statusCodeToReturn = 200,
                inputStreamException = CancellationException("Coroutine execution cancelled")
            )
        }

        val transport = HttpUrlConnectionTransport(configuredOrigin, connectionFactory = factory)

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = validBootstrapPath,
            baseOrigin = configuredOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        // Verifies CancellationException is NOT swallowed or converted into HttpTransportException
        assertThrows(CancellationException::class.java) {
            transport.execute(req)
        }
    }
}
