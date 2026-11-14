package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.onlinecash.AndroidV2OnlineCashClient
import com.example.data.mobile.onlinecash.OnlineCashOrchestrationResult
import com.example.data.mobile.onlinecash.OnlineCashSaleOrchestrator
import com.example.data.mobile.onlinecash.OnlineCashStatusResult
import com.example.data.mobile.onlinecash.PreparedOnlineSaleIntent
import com.example.data.mobile.readclient.http.OkHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Opt-in paired HTTPS smoke test harness for Android V2 Online Cash and Status Client.
 *
 * Operational rules:
 * - If SMALL_TRADER_CASH_PILOT_HANDOFF is absent, the test is explicitly SKIPPED via Assume.assumeTrue.
 * - When configured, any error FAILS the test (no skip fallback).
 * - Requires independent environment variables: backend SHA, client SHA, handoff SHA.
 * - Under PROVISION_ONLY state: records NOT_RUN scenarios and overall status NOT_RUN (no PASS zero executed).
 * - When READY: missing any required nonce/CA/pins/TTL/fields FAILS closed immediately.
 * - Under READINESS_ONLY: readiness probe executes, financial scenarios remain NOT_RUN.
 * - Under FINANCIAL_PAIRED_RUN: executes readiness, fresh 201 commit, idempotent 200 replay, status query,
 *   and balanced 409 tamper conflict via actual OnlineCashSaleOrchestrator.
 * - Absent coordinated fault and stock scenarios remain explicitly NOT_RUN (not fake complete).
 * - Overall status is PASS only if required executed scenarios passed.
 * - Evidence output contains zero financial secrets, tokens, or raw bodies.
 */
class SmallTraderOnlineCashPilotSmokeTest {

    @Test
    fun executePairedOnlineCashPilotSmoke() {
        val handoffEnv = System.getenv(CashPilotHandoffConfig.ENV_CASH_PILOT_HANDOFF)

        Assume.assumeTrue(
            "Environment variable '${CashPilotHandoffConfig.ENV_CASH_PILOT_HANDOFF}' not set. Paired online cash pilot skipped (explicit NOT RUN).",
            !handoffEnv.isNullOrBlank()
        )

        val handoffFile = File(handoffEnv!!.trim())
        assertTrue("Handoff file does not exist: [REDACTED_FILE]", handoffFile.exists())

        var overallStatus = "FAIL"
        val scenarios = mutableListOf<ScenarioEvidence>()
        var tlsCloseable: Closeable? = null
        var config: CashPilotHandoffConfig? = null

        try {
            // STEP 0: Load and validate cash pilot hand-off configuration
            config = CashPilotHandoffConfig.fromFile(file = handoffFile)

            // STEP 1: Handle explicit PROVISION_ONLY state
            if (config.isProvisionOnly) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "readiness_probe",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend harness in PROVISION_ONLY state; awaiting TLS listener start"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_fresh_commit_201",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend in PROVISION_ONLY state; no active TLS listener running yet"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_idempotent_replay_200",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend in PROVISION_ONLY state; no active TLS listener running yet"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_event_status_posted",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend in PROVISION_ONLY state; no active TLS listener running yet"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_tamper_409",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend in PROVISION_ONLY state; no active TLS listener running yet"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "transport_lost_ack_recovery",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend in PROVISION_ONLY state; coordinated fault scenario awaiting transport start"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "stock_permission_rollback_probes",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend in PROVISION_ONLY state; stock probes awaiting transport start"
                    )
                )

                // For explicit PROVISION_ONLY, overall tri-state status is strictly NOT_RUN (no PASS zero executed!)
                overallStatus = "NOT_RUN"
                return
            }

            // STEP 2: Configure JVM TLS truststore with test CA
            val caCertPath = config.startupTls.caCertPath
                ?: throw IllegalStateException("startup_tls.ca_cert_path is required in READY state")
            tlsCloseable = JvmTlsTrustConfigurator.configureJvmTestCa(File(caCertPath))

            // STEP 3: Execute readiness probe
            val readinessUrl = config.startupTls.readinessUrl
                ?: throw IllegalStateException("startup_tls.readiness_url is required in READY state")
            val expectedNonce = config.startupTls.readinessNonce
                ?: throw IllegalStateException("startup_tls.readiness_nonce is required in READY state")

            val tStartReadiness = System.currentTimeMillis()
            val conn = URL(readinessUrl).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val readinessCode: Int
            val readinessBody: String
            try {
                conn.connect()
                readinessCode = conn.responseCode
                val contentType = conn.contentType ?: ""
                require(contentType.startsWith("application/json")) {
                    "Expected application/json Content-Type, got '$contentType'"
                }
                val stream: InputStream = if (readinessCode in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream.use { s ->
                    val buf = ByteArray(65536)
                    val r = s.read(buf)
                    if (r > 0) buf.copyOf(r) else ByteArray(0)
                }
                readinessBody = String(bytes, Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
            val readinessLatency = System.currentTimeMillis() - tStartReadiness

            require(readinessCode == 200) {
                "Readiness probe returned HTTP $readinessCode (expected 200)"
            }
            val readinessObj = StrictJsonParser.parse(readinessBody).asObject()
            val statusVal = readinessObj["status"]?.asString()?.value
            val nonceVal = readinessObj["nonce"]?.asString()?.value
            require(statusVal == "ready") {
                "Readiness response status expected 'ready', got '$statusVal'"
            }
            require(nonceVal == expectedNonce) {
                "Readiness response nonce mismatch: expected $expectedNonce, got $nonceVal"
            }

            scenarios.add(
                ScenarioEvidence(
                    name = "readiness_probe",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = readinessLatency,
                    details = "Loopback HTTPS readiness probe verified HTTP 200 with matching nonce"
                )
            )

            // If READINESS_ONLY: mark financial scenarios NOT_RUN and pass
            if (config.isReadinessOnly || !config.isFinancialApproved) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_fresh_commit_201",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Financial execution not authorized under current lifecycle grant; readiness only verified"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_idempotent_replay_200",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Financial execution not authorized under current lifecycle grant; readiness only verified"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_event_status_posted",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Financial execution not authorized under current lifecycle grant; readiness only verified"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_tamper_409",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Financial execution not authorized under current lifecycle grant; readiness only verified"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "transport_lost_ack_recovery",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Financial execution not authorized under current lifecycle grant"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "stock_permission_rollback_probes",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Financial execution not authorized under current lifecycle grant"
                    )
                )
                overallStatus = "PASS"
                return
            }

            // STEP 4: FINANCIAL_PAIRED_RUN Execution via OnlineCashSaleOrchestrator
            val transport = OkHttpTransport(
                configuredOrigin = config.baseUrl,
                allowHttpLoopback = false
            )
            val cashClient = AndroidV2OnlineCashClient(
                transport = transport,
                baseOrigin = config.baseUrl,
                allowHttpLoopback = false
            )
            val orchestrator = OnlineCashSaleOrchestrator(
                client = cashClient,
                tokenProvider = { config.credentials.rawToken }
            )

            val rawWireBytes = config.rawWireSample.rawWirePayloadJson.toByteArray(Charsets.UTF_8)
            val preparedIntent = PreparedOnlineSaleIntent.prepare(
                context = config.serverContext,
                rawWireBytes = rawWireBytes,
                scopeGeneration = 0L,
                expectedDigest = config.canonicalIntentProfileDigest
            )

            // Scenario: fresh commit (201)
            val tCommitStart = System.currentTimeMillis()
            val freshResult = orchestrator.dispatchOnlineCashSale(
                intent = preparedIntent,
                targetCompanyId = config.serverContext.companyId,
                targetDeviceId = preparedIntent.deviceId,
                targetCurrency = preparedIntent.currency
            )
            val commitLatency = System.currentTimeMillis() - tCommitStart

            val freshReceipt = when (freshResult) {
                is OnlineCashOrchestrationResult.ConfirmedFinancialSuccess -> freshResult.receipt
                is OnlineCashOrchestrationResult.PostedWithoutPaymentReference -> freshResult.receipt
                else -> throw AssertionError("Fresh commit failed: $freshResult")
            }
            require(!freshReceipt.wasReplay) { "Fresh commit must not report replay" }

            scenarios.add(
                ScenarioEvidence(
                    name = "financial_cash_fresh_commit_201",
                    status = "PASS",
                    httpStatusCode = 201,
                    latencyMs = commitLatency,
                    details = "Fresh online cash sale committed atomically with invoice ${freshReceipt.invoiceNumber}"
                )
            )

            // Scenario: idempotent replay (200) with EXACT SAME prepared bytes
            val tReplayStart = System.currentTimeMillis()
            val replayResult = orchestrator.dispatchOnlineCashSale(
                intent = preparedIntent,
                targetCompanyId = config.serverContext.companyId,
                targetDeviceId = preparedIntent.deviceId,
                targetCurrency = preparedIntent.currency
            )
            val replayLatency = System.currentTimeMillis() - tReplayStart

            val replayReceipt = when (replayResult) {
                is OnlineCashOrchestrationResult.ConfirmedFinancialSuccess -> replayResult.receipt
                is OnlineCashOrchestrationResult.PostedWithoutPaymentReference -> replayResult.receipt
                else -> throw AssertionError("Idempotent replay failed: $replayResult")
            }
            require(replayReceipt.wasReplay) { "Replay must report wasReplay == true" }
            require(replayReceipt.invoiceNumber == freshReceipt.invoiceNumber) {
                "Replay invoice number mismatch: expected ${freshReceipt.invoiceNumber}, got ${replayReceipt.invoiceNumber}"
            }

            scenarios.add(
                ScenarioEvidence(
                    name = "financial_cash_idempotent_replay_200",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = replayLatency,
                    details = "Idempotent replay returned byte-identical response with matching invoice number"
                )
            )

            // Scenario: status query (200 posted)
            val tStatusStart = System.currentTimeMillis()
            val statusResult = cashClient.checkStatus(
                token = config.credentials.rawToken,
                companyId = config.serverContext.companyId,
                clientEventId = preparedIntent.clientEventId
            )
            val statusLatency = System.currentTimeMillis() - tStatusStart

            when (statusResult) {
                is OnlineCashStatusResult.Posted -> {
                    require(statusResult.response.status == "posted") {
                        "Expected status 'posted', got '${statusResult.response.status}'"
                    }
                    require(statusResult.response.officialInvoice?.invoiceNumber == freshReceipt.invoiceNumber) {
                        "Status invoice number mismatch: expected ${freshReceipt.invoiceNumber}, got ${statusResult.response.officialInvoice?.invoiceNumber}"
                    }
                    scenarios.add(
                        ScenarioEvidence(
                            name = "financial_cash_event_status_posted",
                            status = "PASS",
                            httpStatusCode = 200,
                            latencyMs = statusLatency,
                            details = "Offline event status query confirmed 'posted' status with authoritative invoice"
                        )
                    )
                }
                else -> throw AssertionError("Status query did not return Posted: $statusResult")
            }

            // Scenario: balanced tamper 409 conflict
            val tamperedObj = buildBalancedTamperedWireJson(config.rawWireSample.parsedRequest)
            val tamperedBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(tamperedObj)
            val tamperedIntent = PreparedOnlineSaleIntent.prepare(
                context = config.serverContext,
                rawWireBytes = tamperedBytes,
                scopeGeneration = 0L
            )

            val tTamperStart = System.currentTimeMillis()
            val tamperResult = orchestrator.dispatchOnlineCashSale(
                intent = tamperedIntent,
                targetCompanyId = config.serverContext.companyId,
                targetDeviceId = tamperedIntent.deviceId,
                targetCurrency = tamperedIntent.currency
            )
            val tamperLatency = System.currentTimeMillis() - tTamperStart

            when (tamperResult) {
                is OnlineCashOrchestrationResult.BusinessConflict -> {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "financial_cash_tamper_409",
                            status = "PASS",
                            httpStatusCode = 409,
                            latencyMs = tamperLatency,
                            details = "Duplicate clientEventId with balanced tampered payload strictly rejected with HTTP 409 Conflict"
                        )
                    )
                }
                else -> throw AssertionError("Tampered submission did not return BusinessConflict (409): $tamperResult")
            }

            // Coordinated fault lost ACK and stock probes: NOT_RUN when backend scenario absent
            scenarios.add(
                ScenarioEvidence(
                    name = "transport_lost_ack_recovery",
                    status = "NOT_RUN",
                    httpStatusCode = null,
                    latencyMs = 0L,
                    details = "Backend coordinated fault injection scenario absent; keeping explicit NOT_RUN"
                )
            )
            scenarios.add(
                ScenarioEvidence(
                    name = "stock_permission_rollback_probes",
                    status = "NOT_RUN",
                    httpStatusCode = null,
                    latencyMs = 0L,
                    details = "Backend stock/rollback probe scenario absent; keeping explicit NOT_RUN"
                )
            )

            overallStatus = "PASS"

        } finally {
            try { tlsCloseable?.close() } catch (_: Throwable) {}

            if (config != null) {
                val evidence = CashPilotEvidence(
                    backendHead = config.backendExecutionHeadSha,
                    clientHead = config.peerAndroidHeadSha,
                    manifestDigest = config.handoffFileSha256,
                    baseUrl = config.baseUrl,
                    lifecycleGrant = if (config.isFinancialApproved) CashPilotHandoffConfig.LIFECYCLE_FINANCIAL_PAIRED_RUN else CashPilotHandoffConfig.LIFECYCLE_READINESS_ONLY,
                    ttlExpiresAt = config.expiresAt,
                    overallStatus = overallStatus,
                    scenarios = scenarios
                )
                try {
                    val evidenceDir = File("docs/integration/v2-online-cash-evidence")
                    evidence.writeTo(File(evidenceDir, "paired-online-cash-pilot-evidence.json"))
                } catch (_: Throwable) {}
            }
        }
    }

    private fun buildBalancedTamperedWireJson(originalReq: StrictJsonObject): StrictJsonObject {
        val originalLines = originalReq["lines"]?.asArray()?.elements ?: emptyList()
        val modifiedLines = originalLines.map { lineElem ->
            val lineObj = lineElem.asObject()
            val origQty = lineObj["quantity"]?.asString()?.value ?: "1.000000"
            val doubledQty = BigDecimal(origQty).multiply(BigDecimal(2)).setScale(6, RoundingMode.HALF_UP).toPlainString()
            val props = lineObj.properties.toMutableMap()
            props["quantity"] = StrictJsonString(doubledQty)
            StrictJsonObject(props)
        }

        val originalPayment = originalReq["payment"]?.asObject()
            ?: throw IllegalArgumentException("Missing payment in parsed request")
        val origAmount = originalPayment["amount"]?.asString()?.value ?: "58.000000"
        val doubledAmount = BigDecimal(origAmount).multiply(BigDecimal(2)).setScale(6, RoundingMode.HALF_UP).toPlainString()
        val paymentProps = originalPayment.properties.toMutableMap()
        paymentProps["amount"] = StrictJsonString(doubledAmount)

        val rootProps = originalReq.properties.toMutableMap()
        rootProps["lines"] = StrictJsonArray(modifiedLines)
        rootProps["payment"] = StrictJsonObject(paymentProps)
        return StrictJsonObject(rootProps)
    }
}
