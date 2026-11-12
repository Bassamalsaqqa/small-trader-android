package com.example.data.mobile.readclient.pilot

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Isolated JVM-level TLS trust configuration for paired HTTPS testing against
 * staging environments using a local test CA.
 *
 * OkHttp 4.10 ignores SSLContext.getDefault(); its platform trust manager delegates to
 * TrustManagerFactory.init(null), which reads standard JVM system properties:
 * - javax.net.ssl.trustStore
 * - javax.net.ssl.trustStorePassword
 * - javax.net.ssl.trustStoreType
 *
 * Invariants:
 * 1. Creates an ephemeral, private (chmod 0600) PKCS12 test truststore containing the local test CA.
 * 2. Sets JVM system properties BEFORE OkHttpClient / OkHttpTransport initialization.
 * 3. Restores previous system properties and deterministically deletes the ephemeral truststore on close/finally.
 * 4. Never disables hostname verification or certificate chain validation.
 * 5. Never modifies production OkHttpTransport or OkHttpClient code.
 */
object JvmTlsTrustConfigurator {

    private const val DEFAULT_PASSWORD = "changeit"

    fun configureJvmTestCa(caCertFile: File): Closeable {
        require(caCertFile.exists()) {
            "Test CA certificate file not found: ${caCertFile.name}"
        }
        require(caCertFile.isFile && !Files.isSymbolicLink(caCertFile.toPath())) {
            "Test CA certificate must be a regular non-symlink file: ${caCertFile.name}"
        }

        val certFactory = CertificateFactory.getInstance("X.509")
        val certificate = FileInputStream(caCertFile).use { fis ->
            certFactory.generateCertificate(fis) as? X509Certificate
                ?: throw IllegalArgumentException("Failed to decode X.509 certificate from ${caCertFile.name}")
        }

        // Initialize in-memory truststore containing the test CA
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("smalltrader-test-ca", certificate)
        }

        // Create ephemeral file with 0600 POSIX permissions if supported
        val tempTrustStore = File.createTempFile("smalltrader-test-truststore-", ".p12")
        try {
            try {
                Files.setPosixFilePermissions(
                    tempTrustStore.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            } catch (_: UnsupportedOperationException) {}

            FileOutputStream(tempTrustStore).use { fos ->
                trustStore.store(fos, DEFAULT_PASSWORD.toCharArray())
            }
        } catch (e: Throwable) {
            tempTrustStore.delete()
            throw e
        }

        return activateTrustStore(tempTrustStore, DEFAULT_PASSWORD, "PKCS12", isEphemeral = true)
    }

    fun configureTrustStore(trustStoreFile: File, password: String?): Closeable {
        require(trustStoreFile.exists()) {
            "Truststore file not found: ${trustStoreFile.name}"
        }
        require(trustStoreFile.isFile && !Files.isSymbolicLink(trustStoreFile.toPath())) {
            "Truststore must be a regular non-symlink file: ${trustStoreFile.name}"
        }

        val type = if (trustStoreFile.extension.equals("jks", ignoreCase = true)) "JKS" else "PKCS12"
        return activateTrustStore(trustStoreFile, password, type, isEphemeral = false)
    }

    private fun activateTrustStore(
        trustStoreFile: File,
        password: String?,
        type: String,
        isEphemeral: Boolean
    ): Closeable {
        val prevTrustStore = System.getProperty("javax.net.ssl.trustStore")
        val prevPassword = System.getProperty("javax.net.ssl.trustStorePassword")
        val prevType = System.getProperty("javax.net.ssl.trustStoreType")

        System.setProperty("javax.net.ssl.trustStore", trustStoreFile.absolutePath)
        if (password != null) {
            System.setProperty("javax.net.ssl.trustStorePassword", password)
        } else {
            System.clearProperty("javax.net.ssl.trustStorePassword")
        }
        System.setProperty("javax.net.ssl.trustStoreType", type)

        return Closeable {
            try {
                if (prevTrustStore != null) {
                    System.setProperty("javax.net.ssl.trustStore", prevTrustStore)
                } else {
                    System.clearProperty("javax.net.ssl.trustStore")
                }
                if (prevPassword != null) {
                    System.setProperty("javax.net.ssl.trustStorePassword", prevPassword)
                } else {
                    System.clearProperty("javax.net.ssl.trustStorePassword")
                }
                if (prevType != null) {
                    System.setProperty("javax.net.ssl.trustStoreType", prevType)
                } else {
                    System.clearProperty("javax.net.ssl.trustStoreType")
                }
            } finally {
                if (isEphemeral) {
                    try { trustStoreFile.delete() } catch (_: Throwable) {}
                }
            }
        }
    }
}
