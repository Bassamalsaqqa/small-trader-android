package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ReadPilotHandoffConfigTest {

    private val sampleValidJson = """
    {
      "baseUrl": "https://staging.smalltrader.example:8443",
      "backendCommitSha": "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb",
      "manifestDigest": "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7",
      "staffEmail": "cashier@smalltrader.example",
      "staffPassword": "SuperSecretPassword123!",
      "deviceId": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
      "deviceName": "Pixel 8 Pilot",
      "appVersion": "1.0.0-pilot",
      "platform": "android",
      "companyId": "01JCV8X9M2NQW3TYZ4R5S6789A",
      "ttlExpiresAt": "2026-11-13T12:00:00Z"
    }
    """.trimIndent()

    @Test
    fun testValidHandoffParsing() {
        val root = StrictJsonParser.parse(sampleValidJson).asObject()
        val config = ReadPilotHandoffConfig.fromJsonObject(root)

        assertEquals("https://staging.smalltrader.example:8443", config.baseUrl)
        assertEquals("7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb", config.backendCommitSha)
        assertEquals(ReadPilotHandoffConfig.FROZEN_MANIFEST_DIGEST, config.manifestDigest)
        assertEquals("cashier@smalltrader.example", config.staffEmail)
        assertEquals("SuperSecretPassword123!", config.staffPassword)
        assertEquals("8e2b8bc6-f80c-4034-8c88-59368d1f2e10", config.deviceId.value)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", config.companyId.value)
        assertEquals("2026-11-13T12:00:00Z", config.ttlExpiresAt)
    }

    @Test
    fun testManifestMismatchFailsClosed() {
        val tamperedJson = sampleValidJson.replace(
            ReadPilotHandoffConfig.FROZEN_MANIFEST_DIGEST,
            "0000000000000000000000000000000000000000000000000000000000000000"
        )
        val root = StrictJsonParser.parse(tamperedJson).asObject()
        try {
            ReadPilotHandoffConfig.fromJsonObject(root)
            fail("Expected IllegalArgumentException on manifest mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected mismatch error message, got: ${e.message}", e.message?.contains("Manifest digest mismatch") == true)
        }
    }

    @Test
    fun testInsecureHttpRejectedWithoutLoopback() {
        val insecureJson = sampleValidJson.replace("https://", "http://")
        val root = StrictJsonParser.parse(insecureJson).asObject()
        try {
            ReadPilotHandoffConfig.fromJsonObject(root)
            fail("Expected IllegalArgumentException on HTTP non-loopback")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected HTTPS requirement error, got: ${e.message}", e.message?.contains("must use HTTPS") == true)
        }
    }

    @Test
    fun testMissingRequiredFieldsThrows() {
        val incompleteJson = """
        {
          "baseUrl": "https://staging.smalltrader.example:8443"
        }
        """.trimIndent()
        val root = StrictJsonParser.parse(incompleteJson).asObject()
        try {
            ReadPilotHandoffConfig.fromJsonObject(root)
            fail("Expected IllegalArgumentException on missing fields")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Missing required") == true)
        }
    }

    @Test
    fun testSanitizedSummaryRedactsSecrets() {
        val root = StrictJsonParser.parse(sampleValidJson).asObject()
        val config = ReadPilotHandoffConfig.fromJsonObject(root)
        val summary = config.toSanitizedSummary()

        assertFalse("Summary must never contain staffPassword", summary.containsKey("staffPassword"))
        assertFalse("Summary must never contain raw password values", summary.values.contains("SuperSecretPassword123!"))
        assertEquals("https://staging.smalltrader.example:8443", summary["baseUrl"])
        assertEquals("7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb", summary["backendCommitSha"])
    }

    @Test
    fun testEvidenceFormattingAndSanitization() {
        val scenarios = listOf(
            ScenarioEvidence(
                name = "auth_challenge",
                status = "PASS",
                httpStatusCode = 200,
                latencyMs = 45L,
                details = "Challenge acquired nonce"
            ),
            ScenarioEvidence(
                name = "auth_login",
                status = "PASS",
                httpStatusCode = 200,
                latencyMs = 120L,
                details = "Authenticated cashier"
            ),
            ScenarioEvidence(
                name = "revoked_token_401",
                status = "PASS",
                httpStatusCode = 401,
                latencyMs = 30L,
                details = "Revoked token rejected"
            )
        )

        val evidence = ReadPilotEvidence(
            backendHead = "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb",
            manifestDigest = ReadPilotHandoffConfig.FROZEN_MANIFEST_DIGEST,
            baseUrl = "https://staging.smalltrader.example:8443",
            overallStatus = "PASS",
            scenarios = scenarios
        )

        val json = evidence.toJsonString()
        assertTrue("JSON must contain backendHead", json.contains("7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb"))
        assertTrue("JSON must contain PASS", json.contains("\"overallStatus\": \"PASS\""))
        assertTrue("JSON must contain scenario name", json.contains("\"auth_challenge\""))

        // Verify valid JSON parse
        val parsed = StrictJsonParser.parse(json).asObject()
        assertEquals("PASS", (parsed["overallStatus"] as com.example.data.mobile.contract.StrictJsonString).value)
    }

    @Test
    fun testSeparateCredentialsSubFileLoading() {
        val tempDir = File.createTempFile("pilot-test", "dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val credFile = File(tempDir, "credentials.json")
            credFile.writeText(
                """
                {
                  "staffEmail": "ephemeral-staff@smalltrader.test",
                  "staffPassword": "DisposableEphemeralSecret!"
                }
                """.trimIndent()
            )

            val handoffJson = """
            {
              "baseUrl": "https://staging.smalltrader.example:8443",
              "backendCommitSha": "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb",
              "manifestDigest": "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7",
              "credentialsPath": "${credFile.absolutePath}",
              "deviceId": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
              "companyId": "01JCV8X9M2NQW3TYZ4R5S6789A"
            }
            """.trimIndent()

            val root = StrictJsonParser.parse(handoffJson).asObject()
            val config = ReadPilotHandoffConfig.fromJsonObject(root, baseDirectory = tempDir)

            assertEquals("ephemeral-staff@smalltrader.test", config.staffEmail)
            assertEquals("DisposableEphemeralSecret!", config.staffPassword)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
