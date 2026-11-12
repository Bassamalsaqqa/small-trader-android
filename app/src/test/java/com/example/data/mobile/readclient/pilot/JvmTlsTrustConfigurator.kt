package com.example.data.mobile.readclient.pilot

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Isolated JVM-level TLS trust configuration for paired HTTPS testing against
 * staging environments using a local test CA.
 *
 * Invariants:
 * 1. Confined strictly to the isolated JVM test process.
 * 2. Never disables hostname verification or certificate chain validation.
 * 3. Never modifies production OkHttpTransport or OkHttpClient code.
 * 4. Fails closed if the configured CA certificate does not exist or is malformed.
 */
object JvmTlsTrustConfigurator {

    fun configureJvmTestCa(caCertFile: File) {
        require(caCertFile.exists()) {
            "Test CA certificate file not found: ${caCertFile.absolutePath}"
        }

        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = FileInputStream(caCertFile).use { fis ->
            certFactory.generateCertificate(fis) as? X509Certificate
                ?: throw IllegalArgumentException("Failed to decode X.509 certificate from ${caCertFile.absolutePath}")
        }

        // Initialize in-memory truststore containing the test CA
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("smalltrader-test-ca", certificate)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, SecureRandom())
        }

        // Set JVM process-default SSLContext so OkHttp's default builder inherits the test truststore
        SSLContext.setDefault(sslContext)
    }

    fun configureTrustStore(trustStoreFile: File, password: String?) {
        require(trustStoreFile.exists()) {
            "Truststore file not found: ${trustStoreFile.absolutePath}"
        }
        System.setProperty("javax.net.ssl.trustStore", trustStoreFile.absolutePath)
        if (password != null) {
            System.setProperty("javax.net.ssl.trustStorePassword", password)
        }
    }
}
