package com.example.data.mobile.readclient.http

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.AuthChallengeRequest
import com.example.data.mobile.readclient.AuthLoginRequest
import com.example.data.mobile.readclient.AuthLogoutRequest
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ReadClientResult
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.TransportRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Hermetic integration tests using a local in-process JDK HttpServer on 127.0.0.1.
 * Tests real socket connections, real network streams, and real platform HttpURLConnection behavior
 * without any external internet traffic or live backend dependencies.
 */
class HttpUrlConnectionTransportLoopbackServerTest {

    private var server: HttpServer? = null
    private var baseOrigin: String = ""
    private val validCompanyUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"

    @Before
    fun setUp() {
        val serverInstance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = serverInstance.address.port
        baseOrigin = "http://127.0.0.1:$port"
        this.server = serverInstance
        serverInstance.start()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun testRealLoopbackGetRoundTrip() {
        server!!.createContext("/api/mobile/v1/companies/${validCompanyUlid.value}/products", HttpHandler { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
            assertEquals("Bearer $validToken", exchange.requestHeaders.getFirst("Authorization"))
            val query = exchange.requestURI.rawQuery
            assertTrue(query.contains("limit=20"))
            assertTrue(query.contains("cursor=opaque%2Fcursor%3D%3D"))

            val responseBody = "{\"data\":\"products_ok\"}"
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        })

        val transport = HttpUrlConnectionTransport(baseOrigin, allowHttpLoopback = true)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${validCompanyUlid.value}/products",
            baseOrigin = baseOrigin,
            queryParams = mapOf(
                "limit" to "20",
                "cursor" to "opaque/cursor=="
            ),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val response = transport.execute(req)
        assertEquals(200, response.statusCode)
        assertEquals("{\"data\":\"products_ok\"}", response.body)
    }

    @Test
    fun testRealLoopbackPostRoundTripWithBody() {
        val expectedRequestBody = "{\"device_id\":\"8e2b8bc6-f80c-4034-8c88-59368d1f2e10\",\"app_version\":\"1.0.0\",\"platform\":\"android\"}"
        val responseBody = "{\"challenge\":\"nonce_live_123\",\"expires_at\":\"2026-11-11T20:00:00Z\"}"

        server!!.createContext("/api/mobile/v1/auth/challenge", HttpHandler { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
            val receivedBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
            assertEquals(expectedRequestBody, receivedBody)

            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        })

        val transport = HttpUrlConnectionTransport(baseOrigin, allowHttpLoopback = true)
        val req = TransportRequest(
            method = HttpMethod.POST,
            path = "/api/mobile/v1/auth/challenge",
            baseOrigin = baseOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = expectedRequestBody
        )

        val response = transport.execute(req)
        assertEquals(200, response.statusCode)
        assertEquals(responseBody, response.body)
    }

    @Test
    fun testRealLoopbackRedirectNotFollowed() {
        server!!.createContext("/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap", HttpHandler { exchange ->
            exchange.responseHeaders.set("Location", "http://127.0.0.1:9999/leaked")
            exchange.responseHeaders.set("Content-Type", "application/json")
            val bytes = "{\"redirect\":\"true\"}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(302, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        })

        val transport = HttpUrlConnectionTransport(baseOrigin, allowHttpLoopback = true)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap",
            baseOrigin = baseOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val response = transport.execute(req)
        // Verifies redirect status 302 returned directly to caller without following
        assertEquals(302, response.statusCode)
        assertEquals("http://127.0.0.1:9999/leaked", response.headers["Location"])
        assertEquals("{\"redirect\":\"true\"}", response.body)
    }

    @Test
    fun testRealLoopbackOversizeStreamRejected() {
        server!!.createContext("/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap", HttpHandler { exchange ->
            // Chunked response (size 0 in sendResponseHeaders)
            exchange.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024) { 0x41 } // 64 KiB chunks
            // Send 17 chunks = 1,114,112 bytes (> 1 MiB)
            for (i in 0 until 17) {
                try {
                    exchange.responseBody.write(chunk)
                    exchange.responseBody.flush()
                } catch (_: Exception) {
                    break
                }
            }
            exchange.close()
        })

        val transport = HttpUrlConnectionTransport(baseOrigin, allowHttpLoopback = true)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap",
            baseOrigin = baseOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }
        assertEquals(HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE, ex.category)
    }

    @Test
    fun testRealLoopbackErrorBodyRead() {
        val errorJson = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Bearer token invalid\"}}"
        server!!.createContext("/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap", HttpHandler { exchange ->
            val bytes = errorJson.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        })

        val transport = HttpUrlConnectionTransport(baseOrigin, allowHttpLoopback = true)
        val req = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap",
            baseOrigin = baseOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $validToken"
            )
        )

        val response = transport.execute(req)
        assertEquals(401, response.statusCode)
        assertEquals(errorJson, response.body)
    }

    @Test
    fun testRealLoopbackIntegrationWithSmallTraderReadClient() {
        // Wire a realistic auth challenge response
        val challengeJson = "{\"challenge_id\":\"8e2b8bc6-f80c-4034-8c88-59368d1f2e10\",\"nonce\":\"test_nonce_abcdef1234567890abcdef1234567890\",\"expires_at\":\"2026-11-11T21:00:00Z\"}"
        server!!.createContext("/api/mobile/v1/auth/challenge", HttpHandler { exchange ->
            val bytes = challengeJson.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        })

        val transport = HttpUrlConnectionTransport(baseOrigin, allowHttpLoopback = true)
        val client = SmallTraderReadClient(transport, baseOrigin, allowHttpLoopback = true)

        val challengeResult = client.authChallenge(
            AuthChallengeRequest(
                deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
                appVersion = "1.0.0",
                platform = "android"
            )
        )

        assertTrue(challengeResult is ReadClientResult.Success)
        val response = (challengeResult as ReadClientResult.Success).value
        assertEquals(ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"), response.challengeId)
        assertEquals("test_nonce_abcdef1234567890abcdef1234567890", response.nonce)
        assertEquals("2026-11-11T21:00:00Z", response.expiresAt)
    }
}
