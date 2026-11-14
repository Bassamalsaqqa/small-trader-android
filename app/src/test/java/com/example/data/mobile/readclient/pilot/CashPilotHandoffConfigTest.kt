package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * - Matches exact authoritative backend provision schema (mobile-v2-client-handoff-schema.json).
 * - Distinct lifecycle grant: READINESS_ONLY vs FINANCIAL_PAIRED_RUN.
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

    private fun createValidFixtures(tempDir: File, isReady: Boolean = true): Triple<File, File, File> {
        val credFile = createPrivateFile(
            tempDir,
            "runtime_credentials.json",
            """
            {
              "raw_token": "st_m_harness_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "token_hash": "2ff1c7a884d8e578fafe616782ffceb0d4f107f9c16e7fcbe735e58988636ba5",
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
        val sampleReqSha = CashPilotHandoffConfig.computeSha256(sampleReqJson.toByteArray(Charsets.UTF_8))

        val lifecyclePhase = if (isReady) "ACTIVE_TLS_READY" else "PROVISIONED_AWAITING_TLS_START"
        val tlsStatus = if (isReady) "ACTIVE_TLS_READY" else "UNAVAILABLE_AWAITING_TLS_START"
        val caPathStr = if (isReady) "\"${caCertFile.absolutePath}\"" else "null"
        val caShaStr = if (isReady) "\"$caSha\"" else "null"
        val readinessUrlStr = if (isReady) "\"https://127.0.0.1:18443/_harness/readiness\"" else "null"
        val readinessNonceStr = if (isReady) "\"harness-nonce-0123456789abcdef\"" else "null"

        val handoffFile = createPrivateFile(
            tempDir,
            "runtime_client_handoff.json",
            """
            {
              "schema_version": "1.0.0",
              "handoff_scope": "ROOT_AUTHORIZED_READINESS_ONLY",
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
                "pairing_manifest_sha256": "5bfb4b06ae33ddf0084a3d2d99a41e714f0c64d97fa810b4ecdddf6f8e5f16c1"
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
                "raw_wire_payload_json": "$sampleReqJson",
                "raw_wire_payload_sha256": "$sampleReqSha",
                "parsed_request": $sampleReqJson
              },
              "server_synthesized_context": {
                "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
                "actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
                "currency_minor_units": 2
              },
              "canonical_intent_profile_digest": "sha256:7c7526dd284b3e414c22fb0971b569bf17f0aa86d4e130a1130dcf2b9cb0c2ee",
              "historical_ca5a_reference": {
                "ca5a_manifest_sha256": "3248b9721c3e2a09e84ae478ddb0171c3ea576fd0cbdd70b3606e4cdda4ff3e7",
                "ca5a_canonical_digest": "0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630"
              },
              "startup_tls": {
                "status": "$tlsStatus",
                "ca_cert_path": $caPathStr,
                "ca_cert_sha256": $caShaStr,
                "readiness_url": $readinessUrlStr,
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
    fun testActiveTlsReadyMissingNonceOrCaThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir, isReady = true)
            val goodContent = goodHandoff.readText(Charsets.UTF_8)
            val missingNonceContent = goodContent.replace(
                ""readiness_nonce": "harness-nonce-0123456789abcdef"",
                ""readiness_nonce": null"
            )
            val badFile = createPrivateFile(tempDir, "missing_nonce.json", missingNonceContent)
            val sha = CashPilotHandoffConfig.computeSha256(badFile.readBytes())

            try {
                CashPilotHandoffConfig.fromFile(
                    file = badFile,
                    expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                    expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                    expectedHandoffSha = sha,
                    allowNonPosixForTesting = true
                )
                fail("Should have thrown for missing readiness nonce in READY state")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("readiness_nonce is required when ready") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testStaleTtlThrows() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val pastTtl = Instant.now().minus(2, ChronoUnit.DAYS).toString()
            val expiredContent = goodHandoff.readText(Charsets.UTF_8).replace(
                Regex(""expires_at": "[^"]+""),
                ""expires_at": "$pastTtl""
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
                assertTrue(e.message?.contains("TTL expired") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testStrictLoopbackHttpsOnly() {
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
    fun testInlineCredentialsForbidden() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir)
            val inlineCredContent = goodHandoff.readText(Charsets.UTF_8).replace(
                ""schema_version": "1.0.0",",
                ""schema_version": "1.0.0", "raw_token": "st_m_forbidden_inline","
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
    fun testProvisionOnlyStateProperties() {
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

            assertTrue("Expected isProvisionOnly == true", config.isProvisionOnly)
            assertEquals("UNAVAILABLE_AWAITING_TLS_START", config.startupTls.status)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFinancialGrantRequiresRootApprovalSha() {
        val tempDir = createPrivateDir()
        try {
            val (goodHandoff, _, _) = createValidFixtures(tempDir, isReady = true)
            val financialContent = goodHandoff.readText(Charsets.UTF_8).replace(
                ""handoff_scope": "ROOT_AUTHORIZED_READINESS_ONLY"",
                ""handoff_scope": "ROOT_AUTHORIZED_PAIRED_EXECUTION""
            )
            val finFile = createPrivateFile(tempDir, "fin_handoff.json", financialContent)
            val sha = CashPilotHandoffConfig.computeSha256(finFile.readBytes())

            // Without root approval SHA
            val configWithoutApproval = CashPilotHandoffConfig.fromFile(
                file = finFile,
                expectedBackendSha = "4a2e0b28f8b4a4bced4a4aed3e7f69abaf7c3ac1",
                expectedClientSha = "ddfc75536e647c65a6a7e8868c9336cee93769ec",
                expectedHandoffSha = sha,
                expectedLifecycle = "FINANCIAL_PAIRED_RUN",
                rootApprovedHandoffSha = null,
                allowNonPosixForTesting = true
            )
            assertFalse(configWithoutApproval.isFinancialApproved)

            // With matching root approval SHA
            val configWithApproval = CashPilotHandoffConfig.fromFile(
                file = finFile,
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
        assertTrue(json.contains(""overallStatus": "NOT_RUN""))
        assertTrue(json.contains("[REDACTED_EMAIL]"))
        assertTrue(json.contains("[REDACTED_PATH]"))
    }
}
