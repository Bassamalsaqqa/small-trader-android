package com.example.data.mobile.readclient.http

import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.readclient.ValidationUtils
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal, hardened, Android API 24+ compatible HTTP transport adapter for Small Trader Read Client.
 *
 * Implements [ReadClientTransport] using a dedicated, private, non-injected [OkHttpClient] instance.
 * Replaces the unsafe platform HttpURLConnection adapter to guarantee strict process-ambient isolation:
 * - Completely immune to ambient [java.net.CookieHandler.getDefault] leakage.
 * - Completely immune to ambient [java.net.Authenticator.getDefault] challenge-retry loops on 401/407.
 * - Completely immune to ambient [java.net.ResponseCache.getDefault] cache pollution.
 * - Zero mutation of JVM process-global state.
 *
 * Security & Reliability Invariants:
 * 1. Dedicated Private Client (No Caller Injection):
 *    - Uses a private, sealed [OkHttpClient] configured with [CookieJar.NO_COOKIES],
 *      [Authenticator.NONE], cache(null), followRedirects(false), followSslRedirects(false),
 *      and retryOnConnectionFailure(false).
 *    - Arbitrary caller client injection is disallowed to prevent enabling cookies, caching, or arbitrary interceptors.
 * 2. Origin Equality & HTTPS Default:
 *    - Base origin validated via [EndpointSecurity.validateBaseOrigin] (HTTPS default; HTTP loopback only with explicit opt-in).
 *    - Dispatched requests must strictly match this transport's configured origin. Mismatches are rejected before opening a connection.
 * 3. Method and Path Allowlisting:
 *    - All requests verified against [EndpointSecurity.validateMethodAndPath] before opening a connection.
 *    - GET requests with a request body are strictly rejected before connection establishment.
 * 4. Controlled Headers:
 *    - Only 'Accept', 'Content-Type', and validated 'Authorization' (Bearer token) are permitted. Arbitrary headers are rejected.
 *    - Exact allowed MIME types enforced (no 'contains' matching).
 *    - Case-insensitive duplicate headers are strictly rejected.
 *    - Authorization exact raw value validated without trim and dispatched same bytes (rejecting leading/trailing whitespace and CRLF).
 *    - Safe constant error messages with zero leakage of raw header names, paths, or custom tokens.
 * 5. UTF-8 Query Parameter Percent-Encoding:
 *    - Every query parameter key and value is UTF-8 URL-encoded. CRLF characters remain safely percent-encoded query characters.
 *    - Cursors are treated strictly as opaque query data and bounded via [EndpointSecurity.validateCursor], never followed as URLs.
 * 6. Standard TLS Verification:
 *    - Uses standard platform TLS chain and host verification. No custom trust managers, cert bypass, or invented pinning.
 * 7. Finite Bounded Timeouts:
 *    - Connect, read, write, and call total deadlines are finite and bounded (default 10s connect, 15s read, 25s call; bounded between 100ms and 60s).
 * 8. Strict Bounded Payload & UTF-8 Stream Decoding:
 *    - Maximum request and response size is strictly 1 MiB (1,048,576 bytes).
 *    - Request body UTF-8 surrogate check protects against malformed sequences without unbounded memory allocation.
 *    - Content-Length pre-check rejects responses exceeding 1 MiB immediately.
 *    - Stream limit reading enforces 1 MiB bound even for chunked or unknown content length.
 *    - Strict UTF-8 decoder rejects malformed or unmappable byte sequences without replacement characters.
 * 9. Resource Management & Error Handling:
 *    - Response body streams and underlying calls are deterministically closed/canceled on all failure paths.
 *    - 401 responses with empty bodies return authoritative status code without swallowing stream errors.
 * 10. Cancellation & Safe Exceptions:
 *    - [CancellationException] and [VirtualMachineError] are never swallowed.
 *    - Exceptions are mapped to safe [HttpTransportException] with fixed typed categories and zero credential/token leakage.
 */
class OkHttpTransport(
    configuredOrigin: String,
    val allowHttpLoopback: Boolean = false,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    val callTimeoutMs: Int = DEFAULT_CALL_TIMEOUT_MS
) : ReadClientTransport {

    val validatedOrigin: String = EndpointSecurity.validateBaseOrigin(configuredOrigin, allowHttpLoopback)

    private val okHttpClient: OkHttpClient

    init {
        require(connectTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "connectTimeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms, got $connectTimeoutMs"
        }
        require(readTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "readTimeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms, got $readTimeoutMs"
        }
        require(callTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "callTimeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms, got $callTimeoutMs"
        }

        // Dedicated private client enforcing isolation from ambient JVM handlers
        this.okHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor(NoRetryApplicationInterceptor())
            .addNetworkInterceptor(NoRetryNetworkInterceptor())
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    override fun execute(request: TransportRequest): TransportResponse {
        // 1. Origin Equality Verification: Request baseOrigin must strictly match transport configured origin
        if (request.baseOrigin != this.validatedOrigin) {
            throw HttpTransportException(
                HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                "Request baseOrigin does not match configured transport origin"
            )
        }

        // 2. Method and Path Allowlisting Verification
        try {
            EndpointSecurity.validateMethodAndPath(request.method, request.path)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is VirtualMachineError) throw e
            throw HttpTransportException(
                HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                "Disallowed HTTP method or endpoint path"
            )
        }

        // Reject GET requests with a body before opening connection (prevents method-flip or protocol ambiguity)
        if (request.method == HttpMethod.GET && request.body != null) {
            throw HttpTransportException(
                HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                "GET requests must not contain a request body"
            )
        }

        // 3. Header Inspection & Controlled Validation
        var acceptCount = 0
        var contentTypeCount = 0
        var authCount = 0
        var validatedAuthHeader: String? = null

        for ((name, value) in request.headers) {
            when {
                name.equals("Accept", ignoreCase = true) -> {
                    acceptCount++
                    if (acceptCount > 1) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Duplicate Accept header"
                        )
                    }
                    if (value.lowercase(Locale.ROOT) !in ALLOWED_ACCEPT_MIMES) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Controlled header 'Accept' must specify application/json"
                        )
                    }
                }
                name.equals("Content-Type", ignoreCase = true) -> {
                    contentTypeCount++
                    if (contentTypeCount > 1) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Duplicate Content-Type header"
                        )
                    }
                    if (value.lowercase(Locale.ROOT) !in ALLOWED_CONTENT_TYPE_MIMES) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Controlled header 'Content-Type' must specify application/json"
                        )
                    }
                }
                name.equals("Authorization", ignoreCase = true) -> {
                    authCount++
                    if (authCount > 1) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Duplicate Authorization header"
                        )
                    }
                    if (!value.startsWith("Bearer ")) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Controlled header 'Authorization' must begin with 'Bearer '"
                        )
                    }
                    val token = value.substring(7)
                    try {
                        ValidationUtils.validateToken(token, "token")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is VirtualMachineError) throw e
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Invalid Bearer token in Authorization header"
                        )
                    }
                    validatedAuthHeader = value
                }
                else -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                        "Disallowed request header"
                    )
                }
            }
        }

        // 4. Cursor validation & UTF-8 Query Parameter Percent-Encoding
        val cursor = request.queryParams["cursor"]
        if (cursor != null) {
            try {
                EndpointSecurity.validateCursor(cursor)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is VirtualMachineError) throw e
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                    "Invalid pagination cursor query parameter"
                )
            }
        }

        // 5. Request Body Pre-Validation & Bounding (up to 1 MiB, strict UTF-8 surrogate check)
        val preparedRequestBodyBytes: ByteArray? = if (request.body != null) {
            if (request.body.length > MAX_PAYLOAD_BYTES) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE,
                    "Request body exceeds maximum allowed limit of $MAX_PAYLOAD_BYTES bytes"
                )
            }
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val byteBuffer = try {
                encoder.encode(CharBuffer.wrap(request.body))
            } catch (e: CharacterCodingException) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                    "Request body contains malformed or unmappable UTF-8 character sequences"
                )
            }
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            if (bytes.size > MAX_PAYLOAD_BYTES) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE,
                    "Request body exceeds maximum allowed limit of $MAX_PAYLOAD_BYTES bytes"
                )
            }
            bytes
        } else {
            null
        }

        val targetUrl = buildTargetUrl(this.validatedOrigin, request.path, request.queryParams)

        // 6. Build OkHttp Request
        val requestBuilder = Request.Builder()
            .url(targetUrl)

        val callExecutionState = CallExecutionState()
        requestBuilder.tag(CallExecutionState::class.java, callExecutionState)

        requestBuilder.header("Accept", "application/json")
        if (preparedRequestBodyBytes != null || request.method == HttpMethod.POST) {
            requestBuilder.header("Content-Type", "application/json; charset=utf-8")
        }
        if (validatedAuthHeader != null) {
            requestBuilder.header("Authorization", validatedAuthHeader)
        }

        val requestBody = when (request.method) {
            HttpMethod.GET -> null
            HttpMethod.POST -> {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val bytes = preparedRequestBodyBytes ?: ByteArray(0)
                createOneShotRequestBody(bytes, mediaType)
            }
        }

        requestBuilder.method(request.method.name, requestBody)
        val okHttpRequest = requestBuilder.build()

        // 7. Execute Call with deterministic resource lifecycle
        var call: Call? = null
        var response: Response? = null
        var isSuccess = false

        try {
            call = okHttpClient.newCall(okHttpRequest)
            response = call.execute()

            val statusCode = response.code

            // Extract Response Headers (case-preserving, defensive copy, null-safe)
            val responseHeaders = mutableMapOf<String, String>()
            val rawHeaders = response.headers
            for (name in rawHeaders.names()) {
                val values = rawHeaders.values(name)
                if (values.isNotEmpty()) {
                    responseHeaders[name] = values.joinToString(", ")
                }
            }

            // Pre-check Content-Length header against 1 MiB bound
            val contentLengthHeader = response.header("Content-Length")
            val reportedContentLength = contentLengthHeader?.toLongOrNull() ?: response.body?.contentLength() ?: -1L
            if (reportedContentLength > MAX_PAYLOAD_BYTES) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE,
                    "Response Content-Length ($reportedContentLength bytes) exceeds maximum limit of $MAX_PAYLOAD_BYTES bytes"
                )
            }

            // Read Bounded UTF-8 Response Body
            val responseBodyStream = response.body?.byteStream()
            val responseBody = if (responseBodyStream != null) {
                readBoundedUtf8Stream(responseBodyStream)
            } else {
                ""
            }

            isSuccess = true
            return TransportResponse(
                statusCode = statusCode,
                headers = Collections.unmodifiableMap(LinkedHashMap(responseHeaders)),
                body = responseBody
            )
        } catch (t: Throwable) {
            when (t) {
                is VirtualMachineError -> throw t
                is CancellationException -> throw t
                is HttpTransportException -> throw t
                is SocketTimeoutException -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_TIMEOUT,
                        "Network socket timeout occurred"
                    )
                }
                is InterruptedIOException -> {
                    // OkHttp callTimeout fires as InterruptedIOException with timeout detail
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_TIMEOUT,
                        "Network socket timeout occurred"
                    )
                }
                is SSLException -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_TLS_ERROR,
                        "TLS handshake or certificate verification failed"
                    )
                }
                is IOException -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_NETWORK_IO,
                        "Network I/O failure during HTTP transport"
                    )
                }
                else -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_NETWORK_IO,
                        "Unexpected transport execution error"
                    )
                }
            }
        } finally {
            try { response?.close() } catch (_: Throwable) {}
            if (!isSuccess) {
                try { call?.cancel() } catch (_: Throwable) {}
            }
        }
    }

    private fun buildTargetUrl(
        baseOrigin: String,
        path: String,
        queryParams: Map<String, String>
    ): URL {
        val cleanBase = baseOrigin.trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val queryString = if (queryParams.isNotEmpty()) {
            val sortedParams = queryParams.toSortedMap()
            val encodedEntries = sortedParams.entries.map { (key, value) ->
                val encodedKey = URLEncoder.encode(key, "UTF-8")
                val encodedVal = URLEncoder.encode(value, "UTF-8")
                "$encodedKey=$encodedVal"
            }
            "?" + encodedEntries.joinToString("&")
        } else {
            ""
        }

        val fullUrlString = "$cleanBase$cleanPath$queryString"
        val url = try {
            URL(fullUrlString)
        } catch (e: Exception) {
            throw HttpTransportException(
                HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                "Failed to construct valid target URL"
            )
        }

        if (url.userInfo != null) {
            throw HttpTransportException(
                HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                "Target URL must not contain userinfo credentials"
            )
        }

        return url
    }

    private fun readBoundedUtf8Stream(stream: InputStream): String {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        val baos = ByteArrayOutputStream()
        var totalBytesRead = 0

        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            totalBytesRead += read
            if (totalBytesRead > MAX_PAYLOAD_BYTES) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE,
                    "Response stream exceeded maximum allowed limit of $MAX_PAYLOAD_BYTES bytes"
                )
            }
            baos.write(buffer, 0, read)
        }

        val bytes = baos.toByteArray()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw HttpTransportException(
                HttpTransportException.CATEGORY_MALFORMED_UTF8,
                "Response body contained malformed or unmappable UTF-8 byte sequences"
            )
        }
    }

    private fun createOneShotRequestBody(bytes: ByteArray, mediaType: MediaType): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType = mediaType
            override fun contentLength(): Long = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(bytes)
            }
            override fun isOneShot(): Boolean = true
        }
    }

    private class CallExecutionState {
        val wireRequestsAttempted = AtomicInteger(0)
        @Volatile var preservedRetryAfterHeaders: List<String>? = null
    }

    private class NoRetryNetworkInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val state = request.tag(CallExecutionState::class.java)
            if (state != null) {
                val attempts = state.wireRequestsAttempted.incrementAndGet()
                if (attempts > 1) {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                        "Automatic network retry or follow-up is prohibited"
                    )
                }
            }

            val response = chain.proceed(request)

            val retryAfterHeaders = response.headers("Retry-After")
            return if (retryAfterHeaders.isNotEmpty()) {
                state?.preservedRetryAfterHeaders = retryAfterHeaders
                response.newBuilder()
                    .removeHeader("Retry-After")
                    .build()
            } else {
                response
            }
        }
    }

    private class NoRetryApplicationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val state = request.tag(CallExecutionState::class.java)
            val response = chain.proceed(request)

            val preservedHeaders = state?.preservedRetryAfterHeaders
            return if (preservedHeaders != null && response.headers("Retry-After").isEmpty()) {
                val builder = response.newBuilder()
                for (value in preservedHeaders) {
                    builder.addHeader("Retry-After", value)
                }
                builder.build()
            } else {
                response
            }
        }
    }

    override fun toString(): String =
        "OkHttpTransport(configuredOrigin='$validatedOrigin', allowHttpLoopback=$allowHttpLoopback)"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000 // 10 seconds
        const val DEFAULT_READ_TIMEOUT_MS = 15_000 // 15 seconds
        const val DEFAULT_CALL_TIMEOUT_MS = 25_000 // 25 seconds
        const val MIN_TIMEOUT_MS = 100 // 100 ms
        const val MAX_TIMEOUT_MS = 60_000 // 60 seconds
        const val MAX_PAYLOAD_BYTES = 1024 * 1024 // 1 MiB = 1,048,576 bytes

        val ALLOWED_ACCEPT_MIMES: Set<String> = Collections.unmodifiableSet(
            setOf("application/json")
        )

        val ALLOWED_CONTENT_TYPE_MIMES: Set<String> = Collections.unmodifiableSet(
            setOf("application/json", "application/json; charset=utf-8")
        )

        private const val STREAM_BUFFER_SIZE = 8192
    }
}

/**
 * Migration compatibility alias for callers migrating from the previous platform transport name.
 */
@Deprecated(
    message = "Replaced by OkHttpTransport for dedicated client isolation and safety against ambient state",
    replaceWith = ReplaceWith("OkHttpTransport")
)
typealias HttpUrlConnectionTransport = OkHttpTransport
