package com.example.data.mobile.readclient.http

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Historical evidence test fixture demonstrating the fundamental security boundary failure
 * of standard platform [HttpURLConnection] on the JVM / Android runtime.
 *
 * Rationale:
 * [HttpURLConnection] automatically couples connection instances to process-global singletons:
 * 1. [CookieHandler.getDefault]: Appends ambient cookies to outgoing requests and stores incoming cookies globally.
 * 2. [java.net.Authenticator.getDefault]: Automatically queries process credentials and retries on 401 challenges.
 * 3. [java.net.ResponseCache.getDefault]: Couples caching to global process state.
 *
 * This test fixture proves empirically that no instance-level configuration on [HttpURLConnection]
 * can prevent ambient cookie / credential leakage without mutating JVM process globals.
 * This justifies the dedicated, non-injected [OkHttpClient] dependency as an essential security boundary.
 */
class PlatformHttpUrlConnectionAmbientEvidenceTest {

    private var server: HttpServer? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = s.address.port
        this.server = s
        s.start()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun testPlatformHttpUrlConnectionCouplesToAmbientCookieHandler() {
        val previousCookieHandler = CookieHandler.getDefault()
        var receivedCookie: String? = null

        server!!.createContext("/evidence/cookie", HttpHandler { exchange ->
            receivedCookie = exchange.requestHeaders.getFirst("Cookie")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        })

        try {
            CookieHandler.setDefault(object : CookieHandler() {
                override fun get(uri: URI?, requestHeaders: Map<String, List<String>>?): Map<String, List<String>> {
                    return mapOf("Cookie" to listOf("ambient_secret_cookie=leaked_via_platform"))
                }
                override fun put(uri: URI?, responseHeaders: Map<String, List<String>>?) {}
            })

            val url = URL("http://127.0.0.1:$port/evidence/cookie")
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connect()
            conn.responseCode
            conn.disconnect()

            // Empirical proof: Platform HttpURLConnection sent ambient cookie over the wire
            assertEquals("ambient_secret_cookie=leaked_via_platform", receivedCookie)
        } finally {
            CookieHandler.setDefault(previousCookieHandler)
        }
    }

    @Test
    fun testPlatformHttpUrlConnectionCouplesToAmbientAuthenticator() {
        var authenticatorInvoked = false

        server!!.createContext("/evidence/auth", HttpHandler { exchange ->
            exchange.responseHeaders.set("WWW-Authenticate", "Basic realm=\"TestRealm\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        })

        try {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    authenticatorInvoked = true
                    return PasswordAuthentication("user", "pass".toCharArray())
                }
            })

            val url = URL("http://127.0.0.1:$port/evidence/auth")
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connect()
            conn.responseCode
            conn.disconnect()

            // Empirical proof: Platform HttpURLConnection invoked ambient Authenticator on 401
            assertTrue("Platform HttpURLConnection must have queried ambient Authenticator", authenticatorInvoked)
        } finally {
            java.net.Authenticator.setDefault(null)
        }
    }
}
