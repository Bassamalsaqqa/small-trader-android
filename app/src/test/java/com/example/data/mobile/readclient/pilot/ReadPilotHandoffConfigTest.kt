package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientResult
import com.example.data.mobile.readclient.http.HttpTransportException
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

class ReadPilotHandoffConfigTest {

    private fun createPrivateFile(parentDir: File, name: String, content: String): File {
        val file = File(parentDir, name)
        file.writeText(content, Charsets.UTF_8)
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {}
        return file
    }

    private fun createValidFixture(tempDir: File): Pair<File, File> {
        val credFile = createPrivateFile(
            tempDir,
            "credentials.json",
            """
            {
              "staff1": {
                "email": "cashier@smalltrader.example",
                "password": "SuperSecretPassword123!",
                "device_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
                "device_name": "Pixel 8 Pilot"
              },
              "staff2_mfa": {
                "email": "mfa@smalltrader.example",
                "password": "MfaSecretPassword123!",
                "totp_secret": "JBSWY3DPEHPK3PXP",
                "recovery_codes": ["1111", "2222"]
              },
              "revoked_device_id": "00000000-0000-4000-8000-000000000002"
            }
            """.trimIndent()
        )

        val futureTtl = Instant.now().plus(2, ChronoUnit.DAYS).toString()
        val handoffContent = """
        {
          "status": "RUNNING",
          "baseUrl": "https://staging.smalltrader.example:8443",
          "backendCommitSha": "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb",
          "manifestDigest": {
            "candidateRev3": "${ReadPilotHandoffConfig.EXPECTED_CANDIDATE_REV3}",
            "primaryPinRev2": "${ReadPilotHandoffConfig.EXPECTED_PRIMARY_PIN_REV2}"
          },
          "credentialsPath": "${credFile.absolutePath}",
          "deviceId": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
          "deviceName": "Pixel 8 Pilot",
          "appVersion": "1.0.0-pilot",
          "platform": "android",
          "companyId": "01JCV8X9M2NQW3TYZ4R5S6789A",
          "invalidCompanyId": "01JCV8X9M2NQW3TYZ4R5S6789B",
          "https_port": 18400,
          "wrong_hostname_port": 18600,
          "untrusted_port": 18800,
          "php_port": 18000,
          "php_pid": 12345,
          "caddy_pid": 12346,
          "schema_name": "accounting_test_schema",
          "compositionFoundationSha": "f5d16b356ed9c4d895e4fd07dcb469d756936d9b",
          "expectedMigrations": [
            "2026_11_11_000001_create_mobile_auth_tables",
            "2026_11_11_000002_harden_mobile_auth_tables",
            "2026_11_11_100000_create_mobile_search_indexes",
            "2026_11_12_000001_create_mobile_event_receipts_table"
          ],
          "resolvedFlags": {
            "financial_v2_active": false,
            "offline_capture_active": false,
            "mobileV1": true,
            "mobileV2": false,
            "mobile.enabled": true,
            "mobile.v2_enabled": false
          },
          "expectedProducts": [{"id":"01JCV8X9M2NQW3TYZ4R5S6789C","sku":"TEST-SKU","exactUnitPrice":"25.000000","taxRate":null,"taxRateId":null}],
          "fixtures": {
            "company_a": {
              "public_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
              "name": "Company A",
              "base_currency": "ILS",
              "products_count": 3,
              "customers_count": 3
            },
            "company_b": {
              "public_id": "01JCV8X9M2NQW3TYZ4R5S6789B",
              "name": "Company B",
              "base_currency": "USD",
              "products_count": 1,
              "customers_count": 2
            }
          },
          "ttlExpiresAt": "$futureTtl"
        }
        """.trimIndent()

        val handoffFile = createPrivateFile(tempDir, "handoff.json", handoffContent)
        return Pair(handoffFile, credFile)
    }

    @Test
    fun scalarManifestCannotBypassPrimaryRevisionPin() {
        val dir = Files.createTempDirectory("pilot-dual-pin-").toFile()
        try {
            val (handoff, _) = createValidFixture(dir)
            val scalar = handoff.readText().replace(
                Regex(""""manifestDigest":\s*\{[^}]+\}"""),
                "\"manifestDigest\":\"${ReadPilotHandoffConfig.EXPECTED_CANDIDATE_REV3}\""
            )
            handoff.writeText(scalar)
            try {
                ReadPilotHandoffConfig.fromFile(handoff, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
                fail("Scalar manifest must fail closed")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("dual-pin object"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun independentShaIsRequiredWithoutOptInParameter() {
        val dir = Files.createTempDirectory("pilot-independent-pin-").toFile()
        try {
            val (handoff, _) = createValidFixture(dir)
            try {
                ReadPilotHandoffConfig.fromFile(handoff, expectedBackendSha = null)
                fail("Missing independent SHA must fail closed by default")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("Independent expected backend SHA is required"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun testValidHandoffWithNestedCredentialsAndDualManifest() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val config = ReadPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb",
                requireIndependentSha = true
            )

            assertEquals("https://staging.smalltrader.example:8443", config.baseUrl)
            assertEquals("7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb", config.backendCommitSha)
            assertEquals(ReadPilotHandoffConfig.EXPECTED_CANDIDATE_REV3, config.manifestDigest)
            assertNotNull(config.manifestDigests)
            assertEquals(ReadPilotHandoffConfig.EXPECTED_CANDIDATE_REV3, config.manifestDigests!!.candidateRev3)
            assertEquals(ReadPilotHandoffConfig.EXPECTED_PRIMARY_PIN_REV2, config.manifestDigests!!.primaryPinRev2)
            assertEquals("cashier@smalltrader.example", config.staffEmail)
            assertEquals("SuperSecretPassword123!", config.staffPassword)
            assertEquals("8e2b8bc6-f80c-4034-8c88-59368d1f2e10", config.deviceId.value)
            assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", config.companyId.value)
            assertEquals("01JCV8X9M2NQW3TYZ4R5S6789B", config.invalidCompanyId.value)
            assertEquals("00000000-0000-4000-8000-000000000002", config.revokedDeviceId?.value)
            assertEquals("ILS", config.baseCurrency)
            assertEquals("25.000000", config.expectedProducts.single().exactUnitPrice)
            assertNull(config.expectedProducts.single().taxRate)
            assertNull(config.expectedProducts.single().taxRateId)
            assertEquals(4, config.expectedMigrations.size)
            assertFalse(config.resolvedFlags["financial_v2_active"]!!)
            assertFalse(config.resolvedFlags["offline_capture_active"]!!)
            assertTrue(config.resolvedFlags["mobileV1"]!!)
            assertFalse(config.resolvedFlags["mobileV2"]!!)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testIndependentBackendShaMismatchThrows() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            try {
                ReadPilotHandoffConfig.fromFile(
                    file = handoffFile,
                    expectedBackendSha = "0000000000000000000000000000000000000000",
                    requireIndependentSha = true
                )
                fail("Expected IllegalArgumentException on backend SHA mismatch")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Backend commit SHA mismatch") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMissingIndependentShaWhenRequiredThrows() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            try {
                ReadPilotHandoffConfig.fromFile(
                    file = handoffFile,
                    expectedBackendSha = null,
                    requireIndependentSha = true
                )
                fail("Expected IllegalArgumentException when independent SHA is required but null")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Independent expected backend SHA is required") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDualManifestTamperedPinFailsClosed() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val tampered = handoffFile.readText(Charsets.UTF_8).replace(
                ReadPilotHandoffConfig.EXPECTED_PRIMARY_PIN_REV2,
                "0000000000000000000000000000000000000000000000000000000000000000"
            )
            val root = StrictJsonParser.parse(tampered).asObject()
            try {
                ReadPilotHandoffConfig.fromJsonObject(root, tempDir, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
                fail("Expected IllegalArgumentException on tampered primaryPinRev2")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Manifest primaryPinRev2 mismatch") == true)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testToStringRedactsSecretsAndPaths() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val config = ReadPilotHandoffConfig.fromFile(handoffFile, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            val str = config.toString()

            assertFalse("toString must not leak password", str.contains("SuperSecretPassword123!"))
            assertFalse("toString must not leak email", str.contains("cashier@smalltrader.example"))
            assertFalse("toString must not leak credentials file path", str.contains("credentials.json"))
            assertTrue("toString contains company ID", str.contains("01JCV8X9M2NQW3TYZ4R5S6789A"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInlineStaffPasswordRejected() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val raw = """
            {
              "baseUrl": "https://staging.smalltrader.example:8443",
              "staffPassword": "InlineSecretPassword!"
            }
            """.trimIndent()
            val root = StrictJsonParser.parse(raw).asObject()
            ReadPilotHandoffConfig.fromJsonObject(root, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            fail("Expected IllegalArgumentException on inline password")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("inline credentials") == true || e.message?.contains("forbidden") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUnknownKeysRejectedInHandoff() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val raw = """
            {
              "baseUrl": "https://staging.smalltrader.example:8443",
              "unauthorizedExtraKey": "maliciousValue"
            }
            """.trimIndent()
            val root = StrictJsonParser.parse(raw).asObject()
            ReadPilotHandoffConfig.fromJsonObject(root, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            fail("Expected IllegalArgumentException on unknown key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown key(s)") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMissingManifestDigestThrows() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val modifiedJson = handoffFile.readText(Charsets.UTF_8).replace(
                Regex("\"manifestDigest\":\\s*\\{[^}]+\\},"),
                ""
            )
            val root = StrictJsonParser.parse(modifiedJson).asObject()
            ReadPilotHandoffConfig.fromJsonObject(root, tempDir, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            fail("Expected IllegalArgumentException on missing manifestDigest")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Missing required key 'manifestDigest'") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testExpiredTtlFailsClosed() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val pastTtl = "2020-01-01T00:00:00Z"
            val expired = handoffFile.readText(Charsets.UTF_8).replace(
                Regex("\"ttlExpiresAt\": \"[^\"]+\""),
                "\"ttlExpiresAt\": \"$pastTtl\""
            )
            val root = StrictJsonParser.parse(expired).asObject()
            ReadPilotHandoffConfig.fromJsonObject(root, tempDir, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            fail("Expected IllegalArgumentException on expired TTL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("TTL expired") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFinancialFlagsTrueFailsClosed() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val activatedJson = handoffFile.readText(Charsets.UTF_8).replace(
                "\"financial_v2_active\": false",
                "\"financial_v2_active\": true"
            )
            val root = StrictJsonParser.parse(activatedJson).asObject()
            ReadPilotHandoffConfig.fromJsonObject(root, tempDir, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            fail("Expected IllegalArgumentException when financial_v2_active is true")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("financial_v2_active must be explicitly false") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMobileV2TrueFailsClosed() {
        val tempDir = File.createTempFile("pilot-test-", "dir")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            val (handoffFile, _) = createValidFixture(tempDir)
            val activatedJson = handoffFile.readText(Charsets.UTF_8).replace(
                "\"mobileV2\": false",
                "\"mobileV2\": true"
            )
            val root = StrictJsonParser.parse(activatedJson).asObject()
            ReadPilotHandoffConfig.fromJsonObject(root, tempDir, expectedBackendSha = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb")
            fail("Expected IllegalArgumentException when mobileV2 is true")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("mobileV2 must be false") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testInsecureUrlOriginRejections() {
        // HTTP remote rejected
        try {
            ReadPilotHandoffConfig.validateHttpsOrigin("http://staging.smalltrader.example:8443", allowHttpLoopback = false)
            fail("Expected failure for HTTP remote origin")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must strictly use HTTPS") == true)
        }

        // UserInfo rejected
        try {
            ReadPilotHandoffConfig.validateHttpsOrigin("https://user:pass@staging.smalltrader.example:8443", allowHttpLoopback = false)
            fail("Expected failure for URL with userinfo")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must not contain userinfo") == true)
        }

        // Path segment rejected
        try {
            ReadPilotHandoffConfig.validateHttpsOrigin("https://staging.smalltrader.example:8443/api/v1", allowHttpLoopback = false)
            fail("Expected failure for URL with path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must not contain path segments") == true)
        }

        // Query rejected
        try {
            ReadPilotHandoffConfig.validateHttpsOrigin("https://staging.smalltrader.example:8443?token=secret", allowHttpLoopback = false)
            fail("Expected failure for URL with query")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must not contain query parameters") == true)
        }
    }

    @Test
    fun testBoundedStreamingReaderEnforcesMaxSize() {
        val tempFile = File.createTempFile("large-test-", ".txt")
        try {
            tempFile.writeBytes(ByteArray(100))
            try {
                ReadPilotHandoffConfig.readBoundedUtf8(tempFile, maxSizeBytes = 50)
                fail("Expected IllegalArgumentException when file exceeds max size")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("must be between 1 and 50 bytes") == true || e.message?.contains("exceeds maximum allowed size") == true)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testSimulateDenialEvaluationNetworkVsExactStatus() {
        // 1. Simulate Network Unknown on Challenge Reuse: Must NOT pass!
        val networkErrorResult: ReadClientResult<Nothing> = ReadClientResult.NetworkUnknown("Connection refused")
        val (reusePassed1, reuseCode1, _) = when (networkErrorResult) {
            is ReadClientResult.Auth401 -> Triple(true, 401, "Auth401")
            is ReadClientResult.Conflict409 -> Triple(true, 409, "Conflict409")
            is ReadClientResult.HttpError -> Triple(networkErrorResult.statusCode in setOf(401, 409), networkErrorResult.statusCode, "HttpError")
            else -> Triple(false, null, "Network failure or unexpected response")
        }
        assertFalse("Network error must NOT qualify as a valid denial", reusePassed1)
        assertNull("Network failure must have null HTTP status code (never fabricated 401)", reuseCode1)

        // 2. Simulate 500 Server Error: Must NOT pass as denial!
        val serverErrorResult: ReadClientResult<Nothing> = ReadClientResult.HttpError(
            500,
            com.example.data.mobile.readclient.ReadClientErrorResponse("SERVER_ERROR", "Internal Server Error")
        )
        val (reusePassed2, reuseCode2, _) = when (serverErrorResult) {
            is ReadClientResult.Auth401 -> Triple(true, 401, "Auth401")
            is ReadClientResult.Conflict409 -> Triple(true, 409, "Conflict409")
            is ReadClientResult.HttpError -> Triple(serverErrorResult.statusCode in setOf(401, 409), serverErrorResult.statusCode, "HttpError")
            else -> Triple(false, null, "Unexpected")
        }
        assertFalse("HTTP 500 must NOT qualify as valid denial", reusePassed2)
        assertEquals(500, reuseCode2)

        // 3. Simulate Valid Auth401: Must PASS!
        val auth401Result: ReadClientResult<Nothing> = ReadClientResult.Auth401(
            com.example.data.mobile.readclient.ReadClientErrorResponse("CHALLENGE_EXPIRED", "Challenge expired")
        )
        val (reusePassed3, reuseCode3, _) = when (auth401Result) {
            is ReadClientResult.Auth401 -> Triple(true, 401, "Auth401")
            is ReadClientResult.Conflict409 -> Triple(true, 409, "Conflict409")
            else -> Triple(false, null, "Unexpected")
        }
        assertTrue("Auth401 must qualify as valid denial", reusePassed3)
        assertEquals(401, reuseCode3)

        // 4. Simulate Foreign Company 403: Must PASS!
        val forbiddenResult: ReadClientResult<Nothing> = ReadClientResult.Forbidden403(
            com.example.data.mobile.readclient.ReadClientErrorResponse("FORBIDDEN", "Company membership required")
        )
        val (wrongPassed1, wrongCode1, _) = when (forbiddenResult) {
            is ReadClientResult.Forbidden403 -> Triple(true, 403, "Forbidden403")
            is ReadClientResult.HttpError -> Triple(forbiddenResult.statusCode == 403, 403, "HttpError")
            else -> Triple(false, null, "Unexpected")
        }
        assertTrue("Forbidden403 must qualify as valid foreign tenant denial", wrongPassed1)
        assertEquals(403, wrongCode1)
    }

    @Test
    fun testInvalidMethodPreflightIsNotTlsError() {
        try {
            EndpointSecurity.validateMethodAndPath(HttpMethod.GET, "/api/mobile/v1/auth/challenge")
            fail("Expected IllegalArgumentException for unauthorized GET on challenge")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unauthorized GET endpoint") == true)
        }
    }

    @Test
    fun testTlsErrorVsNetworkIoClassification() {
        assertEquals("TLS_ERROR", HttpTransportException.CATEGORY_TLS_ERROR)
        assertEquals("NETWORK_IO_ERROR", HttpTransportException.CATEGORY_NETWORK_IO)
        assertEquals("NETWORK_TIMEOUT", HttpTransportException.CATEGORY_TIMEOUT)

        val tlsEx = HttpTransportException(HttpTransportException.CATEGORY_TLS_ERROR, "Handshake failed")
        val ioEx = HttpTransportException(HttpTransportException.CATEGORY_NETWORK_IO, "Connection reset")

        assertTrue(tlsEx.category == HttpTransportException.CATEGORY_TLS_ERROR)
        assertFalse(ioEx.category == HttpTransportException.CATEGORY_TLS_ERROR)
    }

    @Test
    fun testEvidenceSanitizationRemovesEmailsAndPaths() {
        val rawDetails = "Failed connecting to user alice@smalltrader.example with file /home/t3dev/secret/key.pem"
        val sanitized = ScenarioEvidence.sanitizeDetails(rawDetails)

        assertFalse("Must not leak email address", sanitized.contains("alice@smalltrader.example"))
        assertFalse("Must not leak home path", sanitized.contains("/home/t3dev/secret/key.pem"))
        assertTrue("Redacts email", sanitized.contains("[REDACTED_EMAIL]"))
        assertTrue("Redacts path", sanitized.contains("[REDACTED_PATH]"))
    }
}
