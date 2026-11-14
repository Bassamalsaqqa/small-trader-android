package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Hermetic parser and admission tests for CashPilotHandoffConfig.
 *
 * Enforces:
 * - Isolated fake private temporary files (0600 / 0700) ONLY.
 * - Zero real network or background daemons.
 * - Strict schema, TTL, permissions, origin, and key allowlisting fail-closed behavior.
 * - Distinct lifecycle grant: READINESS_ONLY vs FINANCIAL_PAIRED_RUN.
 * - Self-authorization prevention: FINANCIAL_PAIRED_RUN rejected without root approval PIN.
 */
class CashPilotHandoffConfigTest {

    private fun createPrivateDir(): File {
        val dir = Files.createTempDirectory("cash-pilot-test-").toFile()
        try {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        } catch (_: UnsupportedOperationException) {}
        return dir
    }

    private fun createPrivateFile(parentDir: File, name: String, content: String): File {
        val file = File(parentDir, name)
        file.writeText(content, Charsets.UTF_8)
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {}
        return file
    }

    private fun createValidFixtures(tempDir: File): Pair<File, File> {
        val credFile = createPrivateFile(
            tempDir,
            "runtime_credentials.json",
            """
            {
              "raw_token": "st_m_harness_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "token_hash": "401dbb3c2528a30edfb6beaa5065e89bf225c348a4d88eba9d47d7f93cff543a",
              "staff_email": "staff@smalltrader.example",
              "staff_password": "SecretPass123!",
              "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
              "device_name": "Pixel 8 Cash Pilot",
              "schema": "accounting_p8_tmp_cash_test",
              "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
              "app_version": "2.0.0",
              "platform": "android"
            }
            """.trimIndent()
        )

        val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
        val handoffFile = createPrivateFile(
            tempDir,
            "cash_pilot_handoff.json",
            """
            {
              "status": "READY",
              "baseUrl": "https://127.0.0.1:8453",
              "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
              "clientCommitSha": "019ea39af09558c2622356cb22235572747e3ddc",
              "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
              "schemaIdentity": "accounting_p8_tmp_cash_test",
              "harnessNonce": "harness-nonce-0123456789abcdef",
              "ttlExpiresAt": "$futureTtl",
              "lifecycleGrant": "READINESS_ONLY",
              "credentialsPath": "${credFile.absolutePath}",
              "allowHttpLoopback": true,
              "fixture": {
                "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                "company_base_currency": "ILS",
                "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                "tax_rate": "16.000000",
                "unit_price": "50.000000",
                "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8",
                "sample_event_id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
                "currency_minor_units": 2
              },
              "ports": {
                "backend_loopback_port": 8091,
                "tls_proxy_port": 8453
              },
              "pids": {
                "php_pid": 1001,
                "caddy_pid": 1002
              }
            }
            """.trimIndent()
        )

        return Pair(handoffFile, credFile)
    }

    @Test
    fun test_valid_readiness_handoff_parses_successfully() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _) = createValidFixtures(tempDir)
            val config = CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                requireIndependentSha = true
            )

            assertEquals("https://127.0.0.1:8453", config.baseUrl)
            assertEquals("4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1", config.backendCommitSha)
            assertEquals("019ea39af09558c2622356cb22235572747e3ddc", config.clientCommitSha)
            assertEquals("accounting_p8_tmp_cash_test", config.schemaIdentity)
            assertEquals("harness-nonce-0123456789abcdef", config.harnessNonce)
            assertEquals(CashPilotHandoffConfig.GRANT_READINESS_ONLY, config.lifecycleGrant)
            assertEquals(ServerUlid.parse("01M7CZPF6TP283F3ZRXF5P8GT2"), config.fixture.companyId)
            assertEquals("ILS", config.fixture.companyBaseCurrency)
            assertEquals("16.000000", config.fixture.taxRate)
            assertEquals("50.000000", config.fixture.unitPrice)
            assertNotNull(config.credentials.rawToken)
            assertEquals("staff@smalltrader.example", config.credentials.staffEmail)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_missing_handoff_file_throws() {
        try {
            CashPilotHandoffConfig.fromFile(
                file = File("/nonexistent/path/handoff.json"),
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
            )
            fail("Expected IllegalArgumentException for nonexistent file")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun test_non_existent_credentials_file_throws() {
        val tempDir = createPrivateDir()
        try {
            val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
            val handoffFile = createPrivateFile(
                tempDir,
                "handoff.json",
                """
                {
                  "baseUrl": "https://127.0.0.1:8453",
                  "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                  "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                  "schemaIdentity": "accounting_p8_tmp_cash_test",
                  "harnessNonce": "harness-nonce-0123456789abcdef",
                  "ttlExpiresAt": "$futureTtl",
                  "credentialsPath": "/nonexistent/credentials.json",
                  "allowHttpLoopback": true,
                  "fixture": {
                    "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                    "company_base_currency": "ILS",
                    "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                    "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                    "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                    "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "unit_price": "50.000000",
                    "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8"
                  }
                }
                """.trimIndent()
            )

            CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
            )
            fail("Expected IllegalArgumentException for missing credentials file")
        } catch (_: IllegalArgumentException) {
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_insecure_file_permissions_rejected() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _) = createValidFixtures(tempDir)
            try {
                Files.setPosixFilePermissions(handoffFile.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))
            } catch (_: UnsupportedOperationException) {
                return
            }

            try {
                CashPilotHandoffConfig.fromFile(
                    file = handoffFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
                )
                fail("Expected SecurityException for world-writable file permissions")
            } catch (_: SecurityException) {}
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_inline_credentials_in_handoff_rejected() {
        val tempDir = createPrivateDir()
        try {
            val credFile = createPrivateFile(tempDir, "cred.json", "{"raw_token":"tok123"}")
            val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
            val handoffFile = createPrivateFile(
                tempDir,
                "handoff.json",
                """
                {
                  "baseUrl": "https://127.0.0.1:8453",
                  "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                  "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                  "schemaIdentity": "accounting_p8_tmp_cash_test",
                  "harnessNonce": "harness-nonce-0123456789abcdef",
                  "ttlExpiresAt": "$futureTtl",
                  "credentialsPath": "${credFile.absolutePath}",
                  "raw_token": "st_m_forbidden_inline_token",
                  "fixture": {
                    "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                    "company_base_currency": "ILS",
                    "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                    "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                    "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                    "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "unit_price": "50.000000",
                    "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8"
                  }
                }
                """.trimIndent()
            )

            CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
            )
            fail("Expected SecurityException for inline credentials in handoff")
        } catch (_: SecurityException) {
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_unknown_keys_in_handoff_fail_closed() {
        val tempDir = createPrivateDir()
        try {
            val credFile = createPrivateFile(tempDir, "cred.json", "{"raw_token":"tok123"}")
            val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
            val handoffFile = createPrivateFile(
                tempDir,
                "handoff.json",
                """
                {
                  "baseUrl": "https://127.0.0.1:8453",
                  "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                  "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                  "schemaIdentity": "accounting_p8_tmp_cash_test",
                  "harnessNonce": "harness-nonce-0123456789abcdef",
                  "ttlExpiresAt": "$futureTtl",
                  "credentialsPath": "${credFile.absolutePath}",
                  "unexpected_untracked_key": "fail_closed_please",
                  "fixture": {
                    "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                    "company_base_currency": "ILS",
                    "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                    "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                    "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                    "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "unit_price": "50.000000",
                    "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8"
                  }
                }
                """.trimIndent()
            )

            CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
            )
            fail("Expected IllegalArgumentException for unexpected key in handoff")
        } catch (_: IllegalArgumentException) {
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_expired_ttl_rejected() {
        val tempDir = createPrivateDir()
        try {
            val credFile = createPrivateFile(tempDir, "cred.json", "{"raw_token":"tok123"}")
            val pastTtl = Instant.now().minus(2, ChronoUnit.HOURS).toString()
            val handoffFile = createPrivateFile(
                tempDir,
                "handoff.json",
                """
                {
                  "baseUrl": "https://127.0.0.1:8453",
                  "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                  "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                  "schemaIdentity": "accounting_p8_tmp_cash_test",
                  "harnessNonce": "harness-nonce-0123456789abcdef",
                  "ttlExpiresAt": "$pastTtl",
                  "credentialsPath": "${credFile.absolutePath}",
                  "fixture": {
                    "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                    "company_base_currency": "ILS",
                    "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                    "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                    "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                    "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "unit_price": "50.000000",
                    "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8"
                  }
                }
                """.trimIndent()
            )

            CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
            )
            fail("Expected IllegalArgumentException for expired TTL")
        } catch (_: IllegalArgumentException) {
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_backend_commit_sha_mismatch_vs_independent_expected_sha_rejected() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _) = createValidFixtures(tempDir)
            CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "0000000000000000000000000000000000000000",
                requireIndependentSha = true
            )
            fail("Expected IllegalArgumentException for backend commit SHA mismatch")
        } catch (_: IllegalArgumentException) {
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_self_authorization_refused_when_financial_grant_without_root_pin() {
        val tempDir = createPrivateDir()
        try {
            val credFile = createPrivateFile(tempDir, "cred.json", "{"raw_token":"tok123"}")
            val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
            val handoffFile = createPrivateFile(
                tempDir,
                "handoff.json",
                """
                {
                  "baseUrl": "https://127.0.0.1:8453",
                  "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                  "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                  "schemaIdentity": "accounting_p8_tmp_cash_test",
                  "harnessNonce": "harness-nonce-0123456789abcdef",
                  "ttlExpiresAt": "$futureTtl",
                  "lifecycleGrant": "FINANCIAL_PAIRED_RUN",
                  "credentialsPath": "${credFile.absolutePath}",
                  "allowHttpLoopback": true,
                  "fixture": {
                    "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                    "company_base_currency": "ILS",
                    "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                    "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                    "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                    "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "unit_price": "50.000000",
                    "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8"
                  }
                }
                """.trimIndent()
            )

            CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                rootApprovalPin = null // No root PIN supplied!
            )
            fail("Expected SecurityException when FINANCIAL_PAIRED_RUN has no root approval PIN")
        } catch (_: SecurityException) {
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_financial_grant_accepted_when_root_pin_present() {
        val tempDir = createPrivateDir()
        try {
            val credFile = createPrivateFile(tempDir, "cred.json", "{"raw_token":"tok123"}")
            val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
            val handoffFile = createPrivateFile(
                tempDir,
                "handoff.json",
                """
                {
                  "baseUrl": "https://127.0.0.1:8453",
                  "backendCommitSha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                  "manifestDigest": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                  "schemaIdentity": "accounting_p8_tmp_cash_test",
                  "harnessNonce": "harness-nonce-0123456789abcdef",
                  "ttlExpiresAt": "$futureTtl",
                  "lifecycleGrant": "FINANCIAL_PAIRED_RUN",
                  "credentialsPath": "${credFile.absolutePath}",
                  "allowHttpLoopback": true,
                  "fixture": {
                    "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                    "company_base_currency": "ILS",
                    "staff_actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                    "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                    "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                    "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "unit_price": "50.000000",
                    "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8"
                  }
                }
                """.trimIndent()
            )

            val config = CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                rootApprovalPin = "root_pin_987654"
            )
            assertEquals(CashPilotHandoffConfig.GRANT_FINANCIAL_PAIRED_RUN, config.lifecycleGrant)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun test_to_string_redacts_credentials_tokens_and_paths() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _) = createValidFixtures(tempDir)
            val config = CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1"
            )

            val configStr = config.toString()
            val credsStr = config.credentials.toString()

            assertFalse("Raw token must not appear in toString", configStr.contains("st_m_harness"))
            assertFalse("Password must not appear in toString", configStr.contains("SecretPass123"))
            assertFalse("Real credentials path must be redacted", configStr.contains(tempDir.absolutePath))
            assertTrue("CredentialsPath should be redacted", configStr.contains("[REDACTED_PATH]"))

            assertFalse("Password must not appear in credentials toString", credsStr.contains("SecretPass123"))
            assertTrue("RawToken should show redacted", credsStr.contains("[REDACTED]"))
            assertTrue("Email should show redacted", credsStr.contains("[REDACTED_EMAIL]"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
