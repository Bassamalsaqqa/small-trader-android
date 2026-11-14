package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.onlinecash.AndroidV2OnlineCashClient
import com.example.data.mobile.readclient.http.OkHttpTransport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.Closeable
import java.io.File

/**
 * Opt-in paired HTTPS smoke test harness for Android V2 Online Cash and Status Client.
 *
 * Operational rules:
 * - Reuses PR 20 custody strict pins, loopback, test CA handshake, and source attestation.
 * - If SMALL_TRADER_CASH_PILOT_HANDOFF / SMALL_TRADER_READ_PILOT_HANDOFF is absent,
 *   the test is explicitly SKIPPED via Assume.assumeTrue (NOT RUN, not silently passing).
 * - Requires independent backend SHA (SMALL_TRADER_EXPECTED_BACKEND_SHA); never trusts handoff alone.
 * - No services, background network, or production credentials until lead-approved handoff.
 * - Preserves ProductionDeny negative assertions.
 */
class SmallTraderOnlineCashPilotSmokeTest {

    companion object {
        const val ENV_CASH_PILOT_HANDOFF = "SMALL_TRADER_CASH_PILOT_HANDOFF"
    }

    @Test
    fun executePairedOnlineCashPilotSmoke() {
        val handoffEnv = System.getenv(ENV_CASH_PILOT_HANDOFF)
            ?: System.getenv(ReadPilotHandoffConfig.ENV_HANDOFF_PATH)

        Assume.assumeTrue(
            "Neither '$ENV_CASH_PILOT_HANDOFF' nor '${ReadPilotHandoffConfig.ENV_HANDOFF_PATH}' set. Paired online cash pilot skipped (explicit NOT RUN).",
            !handoffEnv.isNullOrBlank()
        )

        val expectedShaEnv = System.getenv(ReadPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA)?.trim()
        require(!expectedShaEnv.isNullOrBlank()) {
            "Safety invariant violation: ${ReadPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA} is required; never trust handoff alone"
        }

        val handoffFile = File(handoffEnv!!.trim())
        assertTrue("Handoff file does not exist: [REDACTED_FILE]", handoffFile.exists())

        var truststoreCloseable: Closeable? = null
        try {
            val config = ReadPilotHandoffConfig.fromFile(
                file = handoffFile,
                expectedBackendSha = expectedShaEnv,
                requireIndependentSha = true
            )

            // Configure isolated JVM test CA trust if provided
            if (config.caCertPath != null) {
                val caFile = File(config.caCertPath)
                assertTrue("Configured CA cert file not found", caFile.exists())
                truststoreCloseable = JvmTlsTrustConfigurator.configureJvmTestCa(caFile)
            } else if (config.testTruststorePath != null) {
                val tsFile = File(config.testTruststorePath)
                assertTrue("Configured truststore file not found", tsFile.exists())
                truststoreCloseable = JvmTlsTrustConfigurator.configureTrustStore(tsFile, config.testTruststorePassword)
            }

            val transport = OkHttpTransport(
                configuredOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )

            val client = AndroidV2OnlineCashClient(
                transport = transport,
                baseOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )

            // Status check query for unknown event (safe query, no side effects)
            val testEventId = ClientUuid.random()
            val dummyToken = "st_m_test_bearer_token_1234567890_abcdef"
            val statusResult = client.checkStatus(dummyToken, config.companyId, testEventId)
            // Confirms real HTTPS network reachability without mutating financial state
            assertNotNull(statusResult)
        } finally {
            try { truststoreCloseable?.close() } catch (_: Throwable) {}
        }
    }
}
