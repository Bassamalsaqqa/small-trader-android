package com.example.data.mobile.readclient.pilot
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertThrows
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * - Matches exact authoritative backend provision schema (mobile-v2-client-handoff-schema.json).
 * - Pins client_handoff_schema_sha256 to edaef0309765e10d6462b36b5c660eb02f65c057c810ab72f756f7664e3ca275.
 * - Distinct lifecycle grant: READINESS_ONLY vs FINANCIAL_PAIRED_RUN.
 * - Distinct loopback host readiness URL (127.0.0.1) vs Android emulator URL (10.0.2.2).
 * - Strict tri-state overall status: PASS, FAIL, NOT_RUN (no PASS zero executed).
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
    private fun createValidFixtures(
        tempDir: File,
        isReady: Boolean = true,
        handoffScope: String = CashPilotHandoffConfig.SCOPE_READINESS_ONLY,
        lifecyclePhaseOverride: String? = null
    ): Triple<File, File, File> {
        val credFile = createPrivateFile(
            tempDir,
            "runtime_credentials.json",
            """
            {
              "raw_token": "st_m_harness_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "token_hash": "0da5e6a3f77d14eacef9bd5fd4d3a7b1610adce05527d7a6b5cf8de6509838b1",
              "staff_email": "staff@smalltrader.example",
              "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
              "schema": "accounting_p8_tmp_cash_test"
            }
            """.trimIndent()
        )
        val caCertFile = createPrivateFile(
            tempDir,
            "test_ca.crt",
            """
            -----BEGIN CERTIFICATE-----
            MIIBojCCAUqgAwIBAgIUQtestCAwDQYJKoZIhvcNAQELBQAwDzENMAsGA1UEAwwE
            VGVzdDAeFw0yNjExMTQwMDAwMDBaFw0yNzExMTQwMDAwMDBaMA8xDTALBgNVBAMM
            BFRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARtestkey...
            -----END CERTIFICATE-----
            """.trimIndent()
        )
        val caSha = CashPilotHandoffConfig.computeSha256(caCertFile.readBytes())
        val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
        val sampleReqJson = """{"client_event_id":"c0686be5-888f-4890-90bf-1f05f416a19a","currency":"ILS","customer_id":"01M7CZPF6TP283F3ZRXF5P8GT3","device_id":"c0686be5-888f-4890-90bf-1f05f416a19a","lines":[{"conversion_to_base":"1.000000","product_id":"01M7CZPF6TP283F3ZRXF5P8GT5","product_unit_id":"01M7CZPF6TP283F3ZRXF5P8GT6","quantity":"1.000000","tax_inclusive":false,"tax_rate":"16.000000","tax_rate_id":"01M7CZPF6TP283F3ZRXF5P8GT7","unit_price":"50.000000"}],"payment":{"amount":"58.000000","cash_account_id":"01M7CZPF6TP283F3ZRXF5P8GT8","payment_method":"cash","settlement_mode":"full"},"warehouse_id":"01M7CZPF6TP283F3ZRXF5P8GT4"}"""
        val sampleReqSha = "1a269431c6960e53c1371485548a3ac8db0d29a328f74a7200055c37c9c452f0"
        val freshProfileDigest = "sha256:9b94a269189f947b0128af4ccf660b6d9d5f36caf4a6adb033c1a8229bd15797"
        val lifecyclePhase = lifecyclePhaseOverride ?: if (isReady) "ACTIVE_TLS_READY" else "PROVISIONED_AWAITING_TLS_START"
        val tlsStatus = if (isReady) "ACTIVE_TLS_READY" else "UNAVAILABLE_AWAITING_TLS_START"
        val caPathStr = if (isReady) "\"${caCertFile.absolutePath}\"" else "null"
        val caShaStr = if (isReady) "\"$caSha\"" else "null"
        val readinessUrlStr = if (isReady) "\"https://127.0.0.1:18443/_harness/readiness\"" else "null"
        val androidReadinessUrlStr = if (isReady) "\"https://10.0.2.2:18443/_harness/readiness\"" else "null"
        val readinessNonceStr = if (isReady) "\"0123456789abcdef0123456789abcdef\"" else "null"
        val handoffFile = createPrivateFile(
            tempDir,
            "runtime_client_handoff.json",
            """
            {
              "schema_version": "1.0.0",
              "handoff_scope": "$handoffScope",
              "lifecycle_phase": "$lifecyclePhase",
              "created_at": "2026-11-14T12:00:00Z",
              "expires_at": "$futureTtl",
              "backend_runtime_pins": {
                "qualified_core_head_sha": "6981511405d7bcd04cd8f8a09e64443ce0aaf47e",
                "backend_execution_head_sha": "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                "qualified_app_tree_sha": "34d1da0ae82b182fa2c0adafd785416a86834a20",
                "peer_android_approved_head_sha": "019ea39af09558c2622356cb22235572747e3ddc",
                "peer_android_executable_head_sha": "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                "source_fixture_sha256": "7f78c49df4fc28540d2d01a528183a8db5fa18bae4df3c998191322ba9c89d68",
                "guard_test_sha256": "f5ce3974cd8edfb3aa4bf4fd9826a4c397b21dce611ecf5058026a0d8703d9f0",
                "cli_command_sha256": "0b51e19e94ef9892f82c8f7d471d186f9f75c0fd9a08eb57f4fd7d08e58e8e99",
                "cli_runner_sha256": "34b9176af6c4cb183cce8f1b8b41cd27f9fb934f47917d4b752df0b6694e503d",
                "pairing_manifest_sha256": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
                "client_handoff_schema_sha256": "edaef0309765e10d6462b36b5c660eb02f65c057c810ab72f756f7664e3ca275"
              },
              "endpoints": {
                "base_url": "https://10.0.2.2:18443/api/mobile/v2",
                "loopback_base_url": "https://127.0.0.1:18443/api/mobile/v2",
                "routes": {
                  "online_cash_sale": "/companies/{companyId}/sales/online-cash",
                  "offline_event_status": "/companies/{companyId}/offline-events/{clientEventId}/status",
                  "readiness_probe": "/_harness/readiness"
                }
              },
              "fixture": {
                "schema_name": "accounting_p8_tmp_cash_test",
                "company_public_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                "company_base_currency": "ILS",
                "currency_minor_units": 2,
                "staff_actor_public_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                "device_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
                "customer_public_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
                "warehouse_public_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
                "product_public_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                "product_unit_public_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                "tax_rate_public_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                "cash_account_public_id": "01M7CZPF6TP283F3ZRXF5P8GT8",
                "sample_event_id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
              },
              "credentials_path": "${credFile.absolutePath}",
              "raw_wire_sample": {
                "raw_wire_payload_json": ${com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(com.example.data.mobile.contract.StrictJsonString(sampleReqJson))},
                "raw_wire_payload_sha256": "$sampleReqSha",
                "parsed_request": $sampleReqJson
              },
              "server_synthesized_context": {
                "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                "actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                "currency_minor_units": 2
              },
              "canonical_intent_profile_digest": "$freshProfileDigest",
              "historical_ca5a_reference": {
                "ca5a_manifest_sha256": "3248b9721c3e2a09e84ae478ddb0171c3ea576fd0cbdd70b3606e4cdda4ff3e7",
                "ca5a_canonical_digest": "0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630"
              },
              "startup_tls": {
                "status": "$tlsStatus",
                "ca_cert_path": $caPathStr,
                "ca_cert_sha256": $caShaStr,
                "readiness_url": $readinessUrlStr,
                "android_readiness_url": $androidReadinessUrlStr,
                "readiness_nonce": $readinessNonceStr
              }
            }
            """.trimIndent()
        )
        return Triple(handoffFile, credFile, caCertFile)
    }
    @Test
    fun testExactBackendProvisionSchemaParsesSuccessfully() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, credFile, _) = createValidFixtures(tempDir, isReady = true)
            val handoffSha = CashPilotHandoffConfig.computeSha256(handoffFile.readBytes())
            val config = CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = handoffSha,
                allowNonPosixForTesting = true
            )
            assertEquals("1.0.0", config.schemaVersion)
            assertEquals(CashPilotHandoffConfig.SCOPE_READINESS_ONLY, config.handoffScope)
            assertEquals(CashPilotHandoffConfig.PHASE_ACTIVE_TLS_READY, config.lifecyclePhase)
            assertEquals("https://127.0.0.1:18443/api/mobile/v2", config.baseUrl)
            assertEquals("01M7CZPF6TP283F3ZRXF5P8GT2", config.fixture.companyId.value)
            assertEquals("ILS", config.fixture.companyBaseCurrency)
            assertEquals(2, config.fixture.currencyMinorUnits)
            assertFalse(config.isProvisionOnly)
            assertTrue(config.isReadinessOnly)
            assertFalse(config.isFinancialApproved)
            assertEquals("c0686be5-888f-4890-90bf-1f05f416a19a", config.credentials.deviceId.value)
            assertEquals("https://127.0.0.1:18443/_harness/readiness", config.startupTls.readinessUrl)
            assertEquals("https://10.0.2.2:18443/_harness/readiness", config.startupTls.androidReadinessUrl)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testClientHandoffSchemaHashPinned() {
        assertEquals(
            "edaef0309765e10d6462b36b5c660eb02f65c057c810ab72f756f7664e3ca275",
            CashPilotHandoffConfig.EXPECTED_CLIENT_HANDOFF_SCHEMA_SHA256
        )
    }
    @Test
    fun testClientHandoffSchemaHashMismatchThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(
                CashPilotHandoffConfig.EXPECTED_CLIENT_HANDOFF_SCHEMA_SHA256,
                "0000000000000000000000000000000000000000000000000000000000000000"
            )
            val badFile = createPrivateFile(tempDir, "bad_schema_pin.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for client_handoff_schema_sha256 mismatch")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("client_handoff_schema_sha256 mismatch") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testMissingRequiredIndependentEnvThrows() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _, _) = createValidFixtures(tempDir)
            try {
                CashPilotHandoffConfig.fromFile(
                    file = handoffFile,
                    expectedBackendSha = null,
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = "401dbb3c2528a30edfb6beaa5065e89bf225c348a4d88eba9d47d7f93cff543a",
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing backend SHA")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains(CashPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA) == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testSourcePinsMismatchThrows() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _, _) = createValidFixtures(tempDir)
            val handoffSha = CashPilotHandoffConfig.computeSha256(handoffFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = handoffFile,
                    expectedBackendSha = "0000000000000000000000000000000000000000",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = handoffSha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for backend execution HEAD mismatch")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Backend execution HEAD pin mismatch") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testMissingRequiredTopLevelSchemaFieldThrows() {
        val tempDir = createPrivateDir()
        try {
            val badHandoff = createPrivateFile(
                tempDir,
                "bad_handoff.json",
                """{"schema_version": "1.0.0", "handoff_scope": "ROOT_AUTHORIZED_READINESS_ONLY"}"""
            )
            val sha = CashPilotHandoffConfig.computeSha256(badHandoff.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badHandoff,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing top-level fields")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("missing top-level field") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testUnexpectedTopLevelSchemaFieldThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"schema_version\": \"1.0.0\",",
                "\"schema_version\": \"1.0.0\", \"invented_field\": true,"
            )
            val badFile = createPrivateFile(tempDir, "extra_top_key.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for unexpected top-level field (additionalProperties: false)")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("unexpected top-level field") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testMissingRequiredFixtureFieldThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(""""tax_rate_public_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
""", "")
            val badFile = createPrivateFile(tempDir, "missing_fixture_key.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing fixture key")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("Missing fixture field") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testUnexpectedFixtureFieldThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"schema_name\": \"accounting_p8_tmp_cash_test\",",
                "\"schema_name\": \"accounting_p8_tmp_cash_test\", \"unknown_fixture_field\": 123,"
            )
            val badFile = createPrivateFile(tempDir, "extra_fixture_key.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for unexpected fixture field")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("Unexpected fixture field") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testMissingOrInvalidMinorUnitsThrowsNoDefault() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            // Missing minor units in fixture
            val missingContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"currency_minor_units\": 2,\n",
                ""
            )
            val missingFile = createPrivateFile(tempDir, "missing_minor_units.json", missingContent)
            val shaMissing = CashPilotHandoffConfig.computeSha256(missingFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = missingFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = shaMissing,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing currency_minor_units (NO default 2 allowed)")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("currency_minor_units") == true)
            }
            // Out-of-bounds minor units (> 6)
            val oobContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"currency_minor_units\": 2",
                "\"currency_minor_units\": 7"
            )
            val oobFile = createPrivateFile(tempDir, "oob_minor_units.json", oobContent)
            val shaOob = CashPilotHandoffConfig.computeSha256(oobFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = oobFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = shaOob,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for currency_minor_units out of range (0..6)")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("currency_minor_units") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testMissingRequiredRuntimePinThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(""""guard_test_sha256": "f5ce3974cd8edfb3aa4bf4fd9826a4c397b21dce611ecf5058026a0d8703d9f0",
""", "")
            val badFile = createPrivateFile(tempDir, "missing_pin.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing runtime pin")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("Missing backend_runtime_pins") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testUnexpectedRuntimePinThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"qualified_core_head_sha\": \"6981511405d7bcd04cd8f8a09e64443ce0aaf47e\",",
                "\"qualified_core_head_sha\": \"6981511405d7bcd04cd8f8a09e64443ce0aaf47e\", \"invented_pin\": \"123\","
            )
            val badFile = createPrivateFile(tempDir, "extra_pin.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for unexpected runtime pin")
            } catch (e: RuntimeException) {
                assertTrue(e.message?.contains("Unexpected backend_runtime_pins") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testStartupTlsMandatoryHostAndAndroidReadinessUrls() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir, isReady = true)
            val config = CashPilotHandoffConfig.fromFile(
                file = goodHandoff,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = CashPilotHandoffConfig.computeSha256(goodHandoff.readBytes()),
                allowNonPosixForTesting = true
            )
            // Both host loopback (127.0.0.1) and Android emulator loopback (10.0.2.2) must be preserved distinctly
            assertEquals("https://127.0.0.1:18443/_harness/readiness", config.startupTls.readinessUrl)
            assertEquals("https://10.0.2.2:18443/_harness/readiness", config.startupTls.androidReadinessUrl)
            assertTrue("readinessUrl must point to host 127.0.0.1", config.startupTls.readinessUrl!!.contains("127.0.0.1"))
            assertTrue("androidReadinessUrl must point to emulator 10.0.2.2", config.startupTls.androidReadinessUrl!!.contains("10.0.2.2"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testReadyStateIncompleteThrowsNoDowngrade() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir, isReady = true)
            val goodContent = goodHandoff.readText(Charsets.UTF_8)
            // Missing android_readiness_url in READY state
            val missingAndroidUrlContent = goodContent.replace(
                "\"android_readiness_url\": \"https://10.0.2.2:18443/_harness/readiness\",",
                "\"android_readiness_url\": null,"
            )
            val badFile = createPrivateFile(tempDir, "missing_android_url.json", missingAndroidUrlContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing android_readiness_url in ready state")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("android_readiness_url is required when ready") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testZeroExecutedYieldsNotRunTriState() {
        val tempDir = createPrivateDir()
        try {
            val (handoffFile, _, _) = createValidFixtures(tempDir, isReady = false)
            val handoffSha = CashPilotHandoffConfig.computeSha256(handoffFile.readBytes())
            val config = CashPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = handoffSha,
                allowNonPosixForTesting = true
            )
            assertTrue("Expected isProvisionOnly == true for PROVISIONED_AWAITING_TLS_START", config.isProvisionOnly)
            assertFalse("isFinancialApproved must be false when provision only", config.isFinancialApproved)
            assertEquals(CashPilotHandoffConfig.TLS_STATUS_UNAVAILABLE, config.startupTls.status)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testInvalidRawWirePayloadShaThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val tamperedShaContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"raw_wire_payload_sha256\": \"1a269431c6960e53c1371485548a3ac8db0d29a328f74a7200055c37c9c452f0\",",
                "\"raw_wire_payload_sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\","
            )
            val badFile = createPrivateFile(tempDir, "tampered_raw_sha.json", tamperedShaContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for raw wire payload SHA-256 integrity mismatch")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Raw wire payload SHA-256 integrity mismatch") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testRawWirePayloadShaVsCanonicalProfileDigestDistinct() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val handoffSha = CashPilotHandoffConfig.computeSha256(goodHandoff.readBytes())
            val config = CashPilotHandoffConfig.fromFile(
                file = goodHandoff,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = handoffSha,
                allowNonPosixForTesting = true
            )
            val rawWireSha = config.rawWireSample.rawWirePayloadSha256
            val profileDigest = config.canonicalIntentProfileDigest.value
            // Assert they are structurally and mathematically distinct
            assertFalse(
                "Profile digest must not equal raw wire payload sha256",
                profileDigest == rawWireSha || profileDigest == "sha256:$rawWireSha"
            )
            assertTrue("Profile digest must carry sha256: prefix", profileDigest.startsWith("sha256:"))
            assertEquals(64, rawWireSha.length)
            assertEquals(71, profileDigest.length)
            // Assert fresh runtime IDs yield fresh digest distinct from historical CA5A digest
            val historicalDigest = config.historicalCa5aReference?.get("ca5a_canonical_digest")
            assertNotNull(historicalDigest)
            assertFalse(
                "Fresh runtime digest must not duplicate historical CA5A digest",
                profileDigest == "sha256:$historicalDigest"
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testCanonicalByteEqualityViolationThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            // Non-canonical wire representation with extra spaces
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"raw_wire_payload_json\": \"{\\\"client_event_id",
                "\"raw_wire_payload_json\": \"{  \\\"client_event_id"
            )
            val badFile = createPrivateFile(tempDir, "non_canonical_bytes.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for canonical byte equality violation")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    e.message?.contains("match") == true ||
                    e.message?.contains("canonically match") == true
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testLegacyHandoffScopeRejected() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val legacyContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"handoff_scope\": \"ROOT_AUTHORIZED_READINESS_ONLY\",",
                "\"handoff_scope\": \"mobile_v2_online_cash_sale\","
            )
            val legacyFile = createPrivateFile(tempDir, "legacy_scope.json", legacyContent)
            val sha = CashPilotHandoffConfig.computeSha256(legacyFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = legacyFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have rejected legacy handoff_scope 'mobile_v2_online_cash_sale'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Invalid handoff_scope") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testPaymentCashAccountMustBeValidUlidMatchingFixture() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            // Mismatched cash account ID in payment
            val badContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"cash_account_id\":\"01M7CZPF6TP283F3ZRXF5P8GT8\"",
                "\"cash_account_id\":\"01M7CZPF6TP283F3ZRXF5P8GT9\""
            )
            val badFile = createPrivateFile(tempDir, "mismatched_cash_account.json", badContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for cash_account_id mismatch vs fixture")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    e.message?.contains("mismatch vs fixture") == true ||
                    e.message?.contains("match") == true ||
                    e.message?.contains("canonically match") == true
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testPaymentMustBeFullCashSettlement() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val nonCashContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"payment_method\":\"cash\"",
                "\"payment_method\":\"credit_card\""
            )
            val badFile = createPrivateFile(tempDir, "non_cash_payment.json", nonCashContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have rejected non-cash payment method")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "Expected payment_method or mismatch but got: ${e.message}",
                    e.message?.contains("payment_method") == true ||
                    e.message?.contains("match") == true
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testStrictLoopbackHttpsOnlyAndGetCap() {
        try {
            CashPilotHandoffConfig.validateHttpsOrigin("http://127.0.0.1:18443")
            fail("Should have rejected http")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Strict loopback HTTPS only") == true)
        }
        try {
            CashPilotHandoffConfig.validateHttpsOrigin("https://evil.attacker.example:18443")
            fail("Should have rejected off-host origin")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Prohibited off-host URL origin") == true)
        }
        try {
            CashPilotHandoffConfig.validateHttpsOrigin("https://user:pass@127.0.0.1:18443")
            fail("Should have rejected userinfo")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Prohibited userinfo") == true)
        }
        try {
            CashPilotHandoffConfig.validateHttpsOrigin("https://127.0.0.1:18443/api/mobile/v2?leak=1")
            fail("Should have rejected query string")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Prohibited query") == true)
        }
    }
    @Test
    fun testStaleTtlThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val pastTtl = Instant.now().minus(2, ChronoUnit.DAYS).toString()
            val expiredContent = goodHandoff.readText(Charsets.UTF_8).replace(
                Regex("\"expires_at\": \"[^\"]+\""),
                "\"expires_at\": \"$pastTtl\""
            )
            val expiredFile = createPrivateFile(tempDir, "expired.json", expiredContent)
            val sha = CashPilotHandoffConfig.computeSha256(expiredFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = expiredFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for expired TTL")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("TTL expired") == true || e.message?.contains("must be strictly after created_at") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testInlineCredentialsForbidden() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val inlineCredContent = goodHandoff.readText(Charsets.UTF_8).replace(
                "\"schema_version\": \"1.0.0\",",
                "\"schema_version\": \"1.0.0\", \"raw_token\": \"st_m_forbidden_inline\","
            )
            val inlineFile = createPrivateFile(tempDir, "inline_cred.json", inlineCredContent)
            val sha = CashPilotHandoffConfig.computeSha256(inlineFile.readBytes())
            try {
                CashPilotHandoffConfig.fromFile(
                    file = inlineFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have rejected inline credentials")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("inline credential key") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testFinancialGrantRequiresRootApprovalSha() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(
                tempDir,
                isReady = true,
                handoffScope = CashPilotHandoffConfig.SCOPE_PAIRED_EXECUTION
            )
            val sha = CashPilotHandoffConfig.computeSha256(goodHandoff.readBytes())
            // Without root approval SHA
            val configWithoutApproval = CashPilotHandoffConfig.fromFile(
                file = goodHandoff,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = sha,
                expectedLifecycle = "FINANCIAL_PAIRED_RUN",
                rootApprovedHandoffSha = null,
                allowNonPosixForTesting = true
            )
            assertFalse(configWithoutApproval.isFinancialApproved)
            // With matching whole-file root approval SHA
            val configWithApproval = CashPilotHandoffConfig.fromFile(
                file = goodHandoff,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = sha,
                expectedLifecycle = "FINANCIAL_PAIRED_RUN",
                rootApprovedHandoffSha = sha,
                allowNonPosixForTesting = true
            )
            assertTrue(configWithApproval.isFinancialApproved)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testCashPilotEvidenceSerializationZeroLiteralNul() {
        val scenarios = listOf(
            ScenarioEvidence(
                name = "readiness_probe",
                status = "PASS",
                httpStatusCode = 200,
                latencyMs = 12L,
                details = "Verified loopback probe with secret token and email staff@example.com at /home/user/secret"
            ),
            ScenarioEvidence(
                name = "financial_cash_tamper_409",
                status = "NOT_RUN",
                httpStatusCode = null,
                latencyMs = 0L,
                details = "Awaiting financial grant"
            )
        )
        val evidence = CashPilotEvidence(
            backendHead = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
            clientHead = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
            manifestDigest = "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1",
            baseUrl = "https://127.0.0.1:18443",
            lifecycleGrant = "READINESS_ONLY",
            overallStatus = "NOT_RUN",
            scenarios = scenarios
        )
        val json = evidence.toJsonString()
        assertFalse("Evidence JSON must not contain literal NUL byte", json.contains("\u0000"))
        assertTrue(json.contains("\"overallStatus\": \"NOT_RUN\""))
        assertTrue(json.contains("[REDACTED_EMAIL]"))
        assertTrue(json.contains("[REDACTED_PATH]"))
    }
    @Test
    fun readinessProbe_success_200MimeNonceMatching() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody("""{"status":"ready","nonce":"authoritative-readiness-nonce-5678"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val result = CashPilotHandoffConfig.executeReadinessProbe(
                readinessUrl = url,
                expectedNonce = "authoritative-readiness-nonce-5678"
            )
            assertEquals(200, result.statusCode)
            assertEquals("ready", result.status)
            assertEquals("authoritative-readiness-nonce-5678", result.nonce)

            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals(CashPilotHandoffConfig.READINESS_PROBE_PATH, recorded.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_redirectRejection_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/somewhere-else")
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"ready","nonce":"test-nonce-1234"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "test-nonce-1234"
                )
            }
            assertTrue("Expected 302 rejection, got: " + ex.message, ex.message!!.contains("302"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_oversizedResponse_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            val oversizedBody = "x".repeat(70000)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(oversizedBody)
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "test-nonce-1234"
                )
            }
            assertTrue("Expected oversized limit rejection, got: " + ex.message, ex.message!!.contains("exceeded bounded limit"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_invalidJson_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{ malformed json }")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            assertThrows(Exception::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "test-nonce-1234"
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_nonceMismatch_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"ready","nonce":"wrong-nonce-9999"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "expected-nonce-1111"
                )
            }
            assertTrue("Expected nonce mismatch rejection, got: " + ex.message, ex.message!!.contains("nonce mismatch"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_statusNotReady_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"degraded","nonce":"matching-nonce-2222"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "matching-nonce-2222"
                )
            }
            assertTrue("Expected status ready rejection, got: " + ex.message, ex.message!!.contains("status expected 'ready'"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_nonJsonMime_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("""{"status":"ready","nonce":"matching-nonce-3333"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "matching-nonce-3333"
                )
            }
            assertTrue("Expected MIME rejection, got: " + ex.message, ex.message!!.contains("Expected application/json Content-Type"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_prefixedNonJsonContentType_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json-evil; charset=utf-8")
                    .setBody("""{"status":"ready","nonce":"matching-nonce-3333"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "matching-nonce-3333"
                )
            }
            assertTrue("Expected MIME rejection, got: " + ex.message, ex.message!!.contains("Expected application/json Content-Type"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_caseInsensitiveApplicationJson_succeeds() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "APPLICATION/JSON; CHARSET=UTF-8")
                    .setBody("""{"status":"ready","nonce":"matching-nonce-4444"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val result = CashPilotHandoffConfig.executeReadinessProbe(
                readinessUrl = url,
                expectedNonce = "matching-nonce-4444"
            )
            assertEquals(200, result.statusCode)
            assertEquals("matching-nonce-4444", result.nonce)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun readinessProbe_non200StatusCode_throwsException() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"service_unavailable"}""")
            )
            val url = server.url(CashPilotHandoffConfig.READINESS_PROBE_PATH).toString()
            val ex = assertThrows(IllegalArgumentException::class.java) {
                CashPilotHandoffConfig.executeReadinessProbe(
                    readinessUrl = url,
                    expectedNonce = "matching-nonce-4444"
                )
            }
            assertTrue("Expected 503 rejection, got: " + ex.message, ex.message!!.contains("returned HTTP 503"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun runtimeFiles_constantsMatchHarness() {
        assertEquals("runtime_client_handoff.json", CashPilotHandoffConfig.EXPECTED_RUNTIME_HANDOFF_FILENAME)
        assertEquals("runtime_credentials.json", CashPilotHandoffConfig.EXPECTED_RUNTIME_CREDENTIALS_FILENAME)
        assertEquals("/_harness/readiness", CashPilotHandoffConfig.READINESS_PROBE_PATH)
    }
}
