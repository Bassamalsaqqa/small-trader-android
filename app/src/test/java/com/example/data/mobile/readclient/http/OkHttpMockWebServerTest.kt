package com.example.data.mobile.readclient.http

import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.TransportRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpMockWebServerTest {

    private var server: MockWebServer? = null
    private var baseOrigin: String = ""
    private val validCompanyUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"

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

    @Test
    fun testMockWebServerExactGetEnvelopeInspection() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":\"products_ok\"}")
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
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

        val recorded = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals("Bearer $validToken", recorded.getHeader("Authorization"))
        assertNull("No Cookie header should be transmitted", recorded.getHeader("Cookie"))
        val requestUrl = recorded.requestUrl.toString()
        assertTrue(requestUrl.contains("cursor=opaque%2Fcursor%3D%3D"))
        assertTrue(requestUrl.contains("limit=20"))
    }

    @Test
    fun testMockWebServerExactPostRequestBody() {
        val requestPayload = "{\"device_id\":\"8e2b8bc6-f80c-4034-8c88-59368d1f2e10\",\"app_version\":\"1.0.0\",\"platform\":\"android\"}"
        val responsePayload = "{\"challenge\":\"nonce_live_123\"}"

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responsePayload)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val req = TransportRequest(
            method = HttpMethod.POST,
            path = "/api/mobile/v1/auth/challenge",
            baseOrigin = baseOrigin,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = requestPayload
        )

        val response = transport.execute(req)
        assertEquals(200, response.statusCode)
        assertEquals(responsePayload, response.body)

        val recorded = server!!.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals(requestPayload, recorded.body.readUtf8())
    }

    @Test
    fun testMockWebServerRedirectNotFollowedNoCredentialForward() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:9999/leaked")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"redirect\":\"true\"}")
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
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
        assertEquals(302, response.statusCode)
        assertEquals("http://127.0.0.1:9999/leaked", response.headers["Location"])
        assertEquals("{\"redirect\":\"true\"}", response.body)
        assertEquals(1, server!!.requestCount)
    }

    @Test
    fun testMockWebServer401ChallengeNoRetry() {
        server!!.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", "Basic realm=\"SmallTrader\"")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"code\":\"UNAUTHORIZED\"}}")
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
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
        assertEquals("{\"error\":{\"code\":\"UNAUTHORIZED\"}}", response.body)
        assertEquals(1, server!!.requestCount)
    }

    @Test
    fun testMockWebServerSlowTrickleCallTimeout() {
        // Body throttled to 1 byte every 300ms; with callTimeoutMs=200 it must time out
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"slow\":\"stream_trickle_payload\"}")
                .throttleBody(1, 300, TimeUnit.MILLISECONDS)
        )

        val transport = OkHttpTransport(
            configuredOrigin = baseOrigin,
            allowHttpLoopback = true,
            connectTimeoutMs = 1000,
            readTimeoutMs = 1000,
            callTimeoutMs = 200
        )

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap",
            baseOrigin = baseOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }
        assertEquals(HttpTransportException.CATEGORY_TIMEOUT, ex.category)
        assertTrue(ex.message!!.contains("timeout"))
    }

    @Test
    fun testMockWebServerMidStreamDisconnectionDoesNotSwallowIntoEmptyBody() {
        // Server sends 401 status and then disconnects mid-stream
        server!!.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"partial\":\"error_data_that_disconnects\"}")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val transport = OkHttpTransport(
            configuredOrigin = baseOrigin,
            allowHttpLoopback = true,
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000,
            callTimeoutMs = 2000
        )

        val req = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${validCompanyUlid.value}/bootstrap",
            baseOrigin = baseOrigin,
            headers = mapOf("Accept" to "application/json")
        )

        // Must NOT swallow stream error into an empty 401 response; must throw
        val ex = assertThrows(HttpTransportException::class.java) {
            transport.execute(req)
        }
        assertTrue(
            ex.category == HttpTransportException.CATEGORY_NETWORK_IO ||
                    ex.category == HttpTransportException.CATEGORY_TIMEOUT
        )
    }

    @Test
    fun testMockWebServerOversizedBodyChunkRejected() {
        val largeBuffer = Buffer()
        val chunk = ByteArray(64 * 1024) { 0x42 }
        for (i in 0 until 17) { // 17 * 64 KiB = 1,114,112 bytes (> 1 MiB)
            largeBuffer.write(chunk)
        }

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(largeBuffer)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
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
}
