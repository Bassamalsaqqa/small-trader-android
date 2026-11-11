package com.example.data.mobile.readclient.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hermetic test mock for HttpURLConnection.
 * Tracks header properties, written output bytes, stream closures, and connection disconnects.
 */
class MockHttpURLConnection(
    url: URL,
    var statusCodeToReturn: Int = 200,
    var responseBodyBytes: ByteArray = ByteArray(0),
    var errorBodyBytes: ByteArray? = null,
    var responseHeadersToReturn: Map<String, List<String>> = emptyMap(),
    var inputStreamException: Throwable? = null,
    var outputStreamException: Throwable? = null
) : HttpURLConnection(url) {

    var disconnected: Boolean = false
    var inputStreamClosed: Boolean = false
    var errorStreamClosed: Boolean = false
    var outputStreamClosed: Boolean = false

    val capturedRequestProperties = mutableMapOf<String, String>()
    val writtenOutput = ByteArrayOutputStream()

    fun isConnected(): Boolean = connected

    override fun connect() {
        connected = true
    }

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun setRequestProperty(key: String, value: String) {
        capturedRequestProperties[key] = value
        super.setRequestProperty(key, value)
    }

    override fun getRequestProperty(key: String): String? =
        capturedRequestProperties[key]

    override fun getResponseCode(): Int {
        if (!connected) connect()
        return statusCodeToReturn
    }

    override fun getHeaderField(name: String): String? {
        val entry = responseHeadersToReturn.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        return entry?.value?.firstOrNull()
    }

    override fun getHeaderFields(): Map<String, List<String>> =
        responseHeadersToReturn

    override fun getInputStream(): InputStream {
        if (!connected) connect()
        inputStreamException?.let { throw it }
        val stream = ByteArrayInputStream(responseBodyBytes)
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                inputStreamClosed = true
                super.close()
            }
        }
    }

    override fun getErrorStream(): InputStream? {
        val bytes = errorBodyBytes ?: return null
        val stream = ByteArrayInputStream(bytes)
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                errorStreamClosed = true
                super.close()
            }
        }
    }

    override fun getOutputStream(): OutputStream {
        outputStreamException?.let { throw it }
        return object : OutputStream() {
            override fun write(b: Int) {
                writtenOutput.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                writtenOutput.write(b, off, len)
            }
            override fun close() {
                outputStreamClosed = true
                super.close()
            }
        }
    }
}
