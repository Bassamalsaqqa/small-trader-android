package com.example.data.mobile.readclient.http

import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.readclient.ValidationUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Minimal Android API 24+ compatible HTTP transport adapter for Small Trader Read Client.
 *
 * Implements [ReadClientTransport] using standard platform [HttpURLConnection] / [javax.net.ssl.HttpsURLConnection]
 * with strict security boundaries, hermetic testability, and zero third-party dependencies.
 *
 * Security & Reliability Invariants:
 * 1. Origin Equality & HTTPS Default:
 *    - Base origin validated via [EndpointSecurity.validateBaseOrigin] (HTTPS default; HTTP loopback only with explicit opt-in).
 *    - Dispatched requests must strictly match this transport's configured origin. Mismatches are rejected before opening a connection.
 * 2. Method and Path Allowlisting:
 *    - All requests verified against [EndpointSecurity.validateMethodAndPath] before opening a connection.
 * 3. Controlled Headers:
 *    - Only 'Accept', 'Content-Type', and validated 'Authorization' (Bearer token) are permitted. Arbitrary headers are rejected.
 * 4. UTF-8 Query Parameter Percent-Encoding:
 *    - Every query parameter key and value is UTF-8 URL-encoded.
 *    - Cursors are treated strictly as opaque query data and bounded via [EndpointSecurity.validateCursor], never followed as URLs.
 * 5. Global & Per-Connection Redirect Suppression:
 *    - Automatic redirects are disabled globally and per-connection (instanceFollowRedirects = false).
 *    - 3xx redirects are never automatically followed, preventing Authorization token forwarding across origins.
 * 6. Standard TLS Verification:
 *    - Uses standard platform TLS chain and host verification. No custom trust managers, cert bypass, or invented pinning.
 * 7. Finite Bounded Timeouts:
 *    - Connect and read timeouts are finite and bounded (default 10s connect, 15s read; bounded between 100ms and 60s).
 * 8. Strict Bounded Payload & UTF-8 Stream Decoding:
 *    - Maximum response size is strictly 1 MiB (1,048,576 bytes).
 *    - Content-Length pre-check rejects responses exceeding 1 MiB immediately.
 *    - Stream limit reading enforces 1 MiB bound even for chunked or unknown content length.
 *    - Strict UTF-8 decoder rejects malformed or unmappable byte sequences without replacement characters.
 * 9. Resource Management:
 *    - Input, error, output streams and connections are deterministically closed/disconnected on all execution paths.
 * 10. Cancellation & Safe Exceptions:
 *    - [CancellationException] and [VirtualMachineError] are never swallowed.
 *    - Exceptions are mapped to safe [HttpTransportException] with fixed typed categories and zero credential/token leakage.
 */
class HttpUrlConnectionTransport(
    configuredOrigin: String,
    val allowHttpLoopback: Boolean = false,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val connectionFactory: HttpUrlConnectionFactory = DefaultHttpUrlConnectionFactory
) : ReadClientTransport {

    val validatedOrigin: String = EndpointSecurity.validateBaseOrigin(configuredOrigin, allowHttpLoopback)

    init {
        require(connectTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "connectTimeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms, got $connectTimeoutMs"
        }
        require(readTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "readTimeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms, got $readTimeoutMs"
        }
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
                "Disallowed HTTP method or endpoint path: ${request.method} ${request.path}"
            )
        }

        // 3. Header Inspection & Controlled Validation
        for ((name, value) in request.headers) {
            when {
                name.equals("Accept", ignoreCase = true) -> {
                    if (!value.contains("application/json")) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Controlled header 'Accept' must specify application/json"
                        )
                    }
                }
                name.equals("Content-Type", ignoreCase = true) -> {
                    if (!value.contains("application/json")) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Controlled header 'Content-Type' must specify application/json"
                        )
                    }
                }
                name.equals("Authorization", ignoreCase = true) -> {
                    if (!value.startsWith("Bearer ")) {
                        throw HttpTransportException(
                            HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                            "Controlled header 'Authorization' must begin with 'Bearer '"
                        )
                    }
                    val token = value.removePrefix("Bearer ").trim()
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
                }
                else -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_SECURITY_VIOLATION,
                        "Disallowed request header: $name"
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

        val targetUrl = buildTargetUrl(this.validatedOrigin, request.path, request.queryParams)

        // 5. Connect and Execute HTTP Request
        var connection: HttpURLConnection? = null
        var outputStream: OutputStream? = null
        var inputStream: InputStream? = null
        var errorStream: InputStream? = null

        try {
            // Disable global redirects as defense-in-depth
            HttpURLConnection.setFollowRedirects(false)

            connection = connectionFactory.openConnection(targetUrl)

            // Disable per-connection redirects strictly
            connection.instanceFollowRedirects = false

            // Set finite bounded timeouts
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            // Configure HTTP Method
            connection.requestMethod = request.method.name

            // Set Controlled Request Headers
            connection.setRequestProperty("Accept", "application/json")
            if (request.body != null || request.method == HttpMethod.POST) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val authHeader = request.headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
            if (authHeader != null) {
                connection.setRequestProperty("Authorization", authHeader)
            }

            connection.doInput = true

            // Transmit Request Body if present
            if (request.body != null) {
                connection.doOutput = true
                val bodyBytes = request.body.toByteArray(StandardCharsets.UTF_8)
                outputStream = connection.outputStream
                outputStream.write(bodyBytes)
                outputStream.flush()
                outputStream.close()
                outputStream = null
            }

            // Retrieve Response Status Code
            val statusCode = connection.responseCode

            // Extract Response Headers (case-preserving, defensive copy)
            val responseHeaders = mutableMapOf<String, String>()
            for ((key, values) in connection.headerFields) {
                if (key != null && values.isNotEmpty()) {
                    responseHeaders[key] = values.joinToString(", ")
                }
            }

            // Pre-check Content-Length header against 1 MiB bound
            val contentLengthHeader = connection.getHeaderField("Content-Length")
            val reportedContentLength = contentLengthHeader?.toLongOrNull() ?: connection.contentLength.toLong()
            if (reportedContentLength > MAX_RESPONSE_BYTES) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE,
                    "Response Content-Length ($reportedContentLength bytes) exceeds maximum limit of $MAX_RESPONSE_BYTES bytes"
                )
            }

            // Determine input stream based on status code
            val isSuccess = statusCode in 200..299
            val streamToRead = if (isSuccess) {
                inputStream = connection.inputStream
                inputStream
            } else {
                val err = connection.errorStream
                if (err != null) {
                    errorStream = err
                    err
                } else {
                    try {
                        connection.inputStream?.also { inputStream = it }
                    } catch (_: IOException) {
                        null
                    }
                }
            }

            // Read Bounded UTF-8 Response Body
            val responseBody = if (streamToRead != null) {
                readBoundedUtf8Stream(streamToRead)
            } else {
                ""
            }

            return TransportResponse(
                statusCode = statusCode,
                headers = Collections.unmodifiableMap(LinkedHashMap(responseHeaders)),
                body = responseBody
            )
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                is VirtualMachineError -> throw e
                is HttpTransportException -> throw e
                is SocketTimeoutException -> {
                    throw HttpTransportException(
                        HttpTransportException.CATEGORY_TIMEOUT,
                        "Network socket timeout occurred"
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
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { errorStream?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
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
            if (totalBytesRead > MAX_RESPONSE_BYTES) {
                throw HttpTransportException(
                    HttpTransportException.CATEGORY_PAYLOAD_TOO_LARGE,
                    "Response stream exceeded maximum allowed limit of $MAX_RESPONSE_BYTES bytes"
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

    override fun toString(): String =
        "HttpUrlConnectionTransport(configuredOrigin='$validatedOrigin', allowHttpLoopback=$allowHttpLoopback)"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000 // 10 seconds
        const val DEFAULT_READ_TIMEOUT_MS = 15_000 // 15 seconds
        const val MIN_TIMEOUT_MS = 100 // 100 ms
        const val MAX_TIMEOUT_MS = 60_000 // 60 seconds
        const val MAX_RESPONSE_BYTES = 1024 * 1024 // 1 MiB = 1,048,576 bytes
        private const val STREAM_BUFFER_SIZE = 8192
    }
}
