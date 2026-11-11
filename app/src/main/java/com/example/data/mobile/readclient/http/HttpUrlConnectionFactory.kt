package com.example.data.mobile.readclient.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pluggable connection factory seam for HttpUrlConnection.
 * Allows hermetic unit testing with mock connections or local test servers
 * while ensuring production code uses standard platform HttpURLConnection/HttpsURLConnection.
 *
 * Security Note:
 * This factory is strictly an opening seam. It cannot bypass any transport security invariants:
 * origin validation, method/path allowlisting, header verification, redirect suppression,
 * payload bounds (1 MiB), timeouts, and strict UTF-8 validation are strictly enforced in
 * [HttpUrlConnectionTransport] regardless of the factory implementation.
 */
fun interface HttpUrlConnectionFactory {
    @Throws(IOException::class)
    fun openConnection(url: URL): HttpURLConnection
}

/**
 * Standard production implementation opening real platform connections via [URL.openConnection].
 */
object DefaultHttpUrlConnectionFactory : HttpUrlConnectionFactory {
    @Throws(IOException::class)
    override fun openConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection()
        if (connection !is HttpURLConnection) {
            throw HttpTransportException(
                HttpTransportException.CATEGORY_UNSUPPORTED_PROTOCOL,
                "Expected HttpURLConnection or HttpsURLConnection, got ${connection::class.java.simpleName}"
            )
        }
        return connection
    }
}
