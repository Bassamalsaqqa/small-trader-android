package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.contract.v2.OnlineSaleIntentProfile
import com.example.data.mobile.onlinecash.AndroidV2OnlineCashClient
import com.example.data.mobile.onlinecash.OnlineCashStatusResult
import com.example.data.mobile.onlinecash.OnlineCashSubmitResult
import com.example.data.mobile.onlinecash.PreparedOnlineSaleIntent
import com.example.data.mobile.readclient.AuthChallengeRequest
import com.example.data.mobile.readclient.AuthLoginRequest
import com.example.data.mobile.readclient.AuthLoginResult
import com.example.data.mobile.readclient.ReadClientResult
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.http.OkHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in paired HTTPS smoke test harness for Android V2 Online Cash and Status Client.
 *
 * Operational rules:
 * - If SMALL_TRADER_CASH_PILOT_HANDOFF is absent, the test is explicitly SKIPPED via
 *   Assume.assumeTrue (NOT RUN, not silently passing).
 * - Requires independent backend SHA (SMALL_TRADER_EXPECTED_BACKEND_SHA); never trusts handoff alone.
 * - Distinct lifecycle grants: READINESS_ONLY vs FINANCIAL_PAIRED_RUN.
 *   FINANCIAL_PAIRED_RUN strictly requires runtime private approval root PIN (SMALL_TRADER_ROOT_FINANCIAL_APPROVAL_PIN).
 *   Handoff files cannot self-authorize financial execution.
 * - Auth via REAL SmallTraderReadClient / OkHttpTransport / private handoff credentials (no dummy bearer).
 * - Parse failure, transient error, or 401 MUST FAIL expected happy path (not assertNotNull).
 * - Unknown event status query confirms fresh auth without mutating financial state.
 * - Under FINANCIAL_PAIRED_RUN: fresh submit (201), idempotent replay (200), status parity, and tampered wire 409.
 * - Coordinated fault and stock probes remain explicitly NOT_RUN until backend contract is present (no pretend pass).
 * - Credentials and raw handshake bodies are never printed, copied, or committed.
 */
class SmallTraderOnlineCashPilotSmokeTest {

    @Test
    fun executePairedOnlineCashPilotSmoke() {
        val handoffEnv = System.getenv(CashPilotHandoffConfig.ENV_CASH_PILOT_HANDOFF)

        Assume.assumeTrue(
            "Environment variable '${CashPilotHandoffConfig.ENV_CASH_PILOT_HANDOFF}' not set. Paired online cash pilot skipped (explicit NOT RUN).",
            !handoffEnv.isNullOrBlank()
        )

        val expectedShaEnv = System.getenv(CashPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA)?.trim()
        require(!expectedShaEnv.isNullOrBlank()) {
            "Safety invariant violation: ${CashPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA} is required; never trust handoff alone"
        }

        val handoffFile = File(handoffEnv!!.trim())
        assertTrue("Handoff file does not exist: [REDACTED_FILE]", handoffFile.exists())

        var overallSuccess = true
        val scenarios = mutableListOf<ScenarioEvidence>()
        var truststoreCloseable: Closeable? = null
        var config: CashPilotHandoffConfig? = null

        try {
            // STEP 0: Load and validate cash pilot hand-off configuration
            config = CashPilotHandoffConfig.fromFile(
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

            // STEP 1: Readiness probe check against /_harness/readiness
            val tReadinessStart = System.currentTimeMillis()
            try {
                val readinessUrl = URL("${config.baseUrl}/_harness/readiness")
                val conn = readinessUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                val readinessCode = conn.responseCode
                val readinessLatency = System.currentTimeMillis() - tReadinessStart

                if (readinessCode != 200) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "readiness_probe",
                            status = "FAIL",
                            httpStatusCode = readinessCode,
                            latencyMs = readinessLatency,
                            details = "Harness readiness probe returned HTTP $readinessCode"
                        )
                    )
                    overallSuccess = false
                    throw AssertionError("Harness readiness probe failed with HTTP $readinessCode")
                }

                val readinessBody = conn.inputStream.bufferedReader().use { it.readText() }
                val readinessJson = StrictJsonParser.parse(readinessBody).asObject()
                val readinessStatus = readinessJson["status"]?.asString()
                val readinessNonce = readinessJson["nonce"]?.asString()

                if (readinessStatus != "ready" || readinessNonce != config.harnessNonce) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "readiness_probe",
                            status = "FAIL",
                            httpStatusCode = 200,
                            latencyMs = readinessLatency,
                            details = "Harness readiness nonce mismatch: expected '${config.harnessNonce}', got '$readinessNonce'"
                        )
                    )
                    overallSuccess = false
                    throw AssertionError("Harness readiness nonce mismatch")
                }

                scenarios.add(
                    ScenarioEvidence(
                        name = "readiness_probe",
                        status = "PASS",
                        httpStatusCode = 200,
                        latencyMs = readinessLatency,
                        details = "Readiness probe verified with matching harness nonce"
                    )
                )
            } catch (e: Throwable) {
                if (scenarios.none { it.name == "readiness_probe" }) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "readiness_probe",
                            status = "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tReadinessStart,
                            details = "Readiness probe threw exception: ${e.javaClass.simpleName}"
                        )
                    )
                }
                overallSuccess = false
                throw e
            }

            // STEP 2: Initialize real transport and clients
            val transport = OkHttpTransport(
                configuredOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )
            val readClient = SmallTraderReadClient(
                transport = transport,
                baseOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )
            val cashClient = AndroidV2OnlineCashClient(
                transport = transport,
                baseOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )

            // STEP 3: Real Client Session Authentication & Bootstrap
            val token: String
            val tAuthStart = System.currentTimeMillis()
            if (!config.credentials.staffEmail.isNullOrBlank() && !config.credentials.staffPassword.isNullOrBlank()) {
                val challengeResult = readClient.authChallenge(
                    AuthChallengeRequest(
                        deviceId = config.fixture.deviceId,
                        appVersion = config.credentials.appVersion ?: "2.0.0",
                        platform = config.credentials.platform ?: "android"
                    )
                )
                if (challengeResult !is ReadClientResult.Success) {
                    val code = (challengeResult as? ReadClientResult.HttpError)?.statusCode
                    scenarios.add(
                        ScenarioEvidence(
                            name = "auth_session",
                            status = "FAIL",
                            httpStatusCode = code,
                            latencyMs = System.currentTimeMillis() - tAuthStart,
                            details = "Auth challenge failed: $challengeResult"
                        )
                    )
                    overallSuccess = false
                    throw AssertionError("Auth challenge failed: $challengeResult")
                }
                val challenge = challengeResult.value

                val loginResult = readClient.authLogin(
                    AuthLoginRequest(
                        challengeId = challenge.challengeId,
                        nonce = challenge.nonce,
                        deviceId = config.fixture.deviceId,
                        deviceName = config.credentials.deviceName ?: "Android Integration Device",
                        email = config.credentials.staffEmail!!,
                        password = config.credentials.staffPassword!!
                    )
                )
                if (loginResult !is ReadClientResult.Success) {
                    val code = (loginResult as? ReadClientResult.HttpError)?.statusCode
                    scenarios.add(
                        ScenarioEvidence(
                            name = "auth_session",
                            status = "FAIL",
                            httpStatusCode = code,
                            latencyMs = System.currentTimeMillis() - tAuthStart,
                            details = "Auth login failed: $loginResult"
                        )
                    )
                    overallSuccess = false
                    throw AssertionError("Auth login failed: $loginResult")
                }
                val loginOutcome = loginResult.value
                if (loginOutcome !is AuthLoginResult.Success) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "auth_session",
                            status = "FAIL",
                            httpStatusCode = 200,
                            latencyMs = System.currentTimeMillis() - tAuthStart,
                            details = "Login outcome was not Success (e.g. MFA required)"
                        )
                    )
                    overallSuccess = false
                    throw AssertionError("Login outcome was not Success: $loginOutcome")
                }
                token = loginOutcome.response.token
            } else if (!config.credentials.rawToken.isNullOrBlank()) {
                token = config.credentials.rawToken!!
            } else {
                throw IllegalStateException("Credentials file must provide either staff credentials or raw_token")
            }

            // Verify session token via real bootstrap query
            val bootstrapResult = readClient.getBootstrap(token, config.fixture.companyId)
            val authLatency = System.currentTimeMillis() - tAuthStart
            if (bootstrapResult !is ReadClientResult.Success) {
                val statusCode = when (bootstrapResult) {
                    is ReadClientResult.Auth401 -> 401
                    is ReadClientResult.Forbidden403 -> 403
                    is ReadClientResult.HttpError -> bootstrapResult.statusCode
                    else -> null
                }
                scenarios.add(
                    ScenarioEvidence(
                        name = "auth_session",
                        status = "FAIL",
                        httpStatusCode = statusCode,
                        latencyMs = authLatency,
                        details = "Bootstrap call failed with active token: $bootstrapResult"
                    )
                )
                overallSuccess = false
                throw AssertionError("Bootstrap verification failed: $bootstrapResult")
            }
            val bootstrap = bootstrapResult.value
            assertEquals(config.fixture.companyId, bootstrap.companyId)
            assertTrue("Base currency must not be blank", bootstrap.baseCurrency.isNotBlank())
            scenarios.add(
                ScenarioEvidence(
                    name = "auth_session",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = authLatency,
                    details = "Auth session verified: company=${bootstrap.companyId.value}, currency=${bootstrap.baseCurrency}"
                )
            )

            // STEP 4: Status query for unknown event (safe query, no financial mutation)
            val tStatusStart = System.currentTimeMillis()
            val unknownEventId = ClientUuid.random()
            val statusResult = cashClient.checkStatus(token, config.fixture.companyId, unknownEventId)
            val statusLatency = System.currentTimeMillis() - tStatusStart

            if (statusResult !is OnlineCashStatusResult.Unknown) {
                val statusCode = when (statusResult) {
                    is OnlineCashStatusResult.AuthDenied401 -> 401
                    is OnlineCashStatusResult.Forbidden403 -> 403
                    is OnlineCashStatusResult.HttpError -> statusResult.statusCode
                    else -> null
                }
                scenarios.add(
                    ScenarioEvidence(
                        name = "status_unknown_event",
                        status = "FAIL",
                        httpStatusCode = statusCode,
                        latencyMs = statusLatency,
                        details = "Unknown event status check did not return OnlineCashStatusResult.Unknown: $statusResult"
                    )
                )
                overallSuccess = false
                throw AssertionError("Unknown event status check failed: $statusResult")
            }

            val unknownResp = statusResult.response
            assertEquals("unknown", unknownResp.status)
            assertNull("officialInvoice must be null for unknown event", unknownResp.officialInvoice)
            assertNull("conflict must be null for unknown event", unknownResp.conflict)
            scenarios.add(
                ScenarioEvidence(
                    name = "status_unknown_event",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = statusLatency,
                    details = "Status query for unknown event correctly returned status=unknown with null invoice"
                )
            )

            // STEP 5: Check Lifecycle Grant before any mutating financial HTTP POST
            if (config.lifecycleGrant == CashPilotHandoffConfig.GRANT_READINESS_ONLY) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_create_201",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Lifecycle grant is READINESS_ONLY. Awaiting Root financial approval PIN grant before mutating HTTP POST"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_replay_200",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Lifecycle grant is READINESS_ONLY"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_status_parity",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Lifecycle grant is READINESS_ONLY"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_tamper_409",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Lifecycle grant is READINESS_ONLY"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "transport_lost_ack_recovery",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Lifecycle grant is READINESS_ONLY"
                    )
                )
                scenarios.add(
                    ScenarioEvidence(
                        name = "stock_permission_rollback_probes",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Lifecycle grant is READINESS_ONLY"
                    )
                )
                return
            }

            // FINANCIAL_PAIRED_RUN: full financial execution
            val clientEventId = ClientUuid.random()
            val context = OnlineSaleContext(
                companyId = config.fixture.companyId,
                actorId = config.fixture.staffActorId,
                currencyMinorUnits = config.fixture.currencyMinorUnits
            )

            val rawWireObj = buildStrict7KeyWireJson(config.fixture, clientEventId)
            val rawWireBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(rawWireObj)
            val preparedIntent = PreparedOnlineSaleIntent.prepare(context, rawWireBytes, scopeGeneration = 0L)

            // STEP 5a: Fresh Submit (POST /sales/online-cash) -> 201 Created
            val tSubmitStart = System.currentTimeMillis()
            val submitResult = cashClient.submitSale(
                token = token,
                companyId = config.fixture.companyId,
                clientEventId = clientEventId,
                expectedPayloadDigest = preparedIntent.expectedNormativeProfileDigest,
                canonicalBytes = preparedIntent.rawWireBytes
            )
            val submitLatency = System.currentTimeMillis() - tSubmitStart

            if (submitResult !is OnlineCashSubmitResult.Created) {
                val statusCode = when (submitResult) {
                    is OnlineCashSubmitResult.AuthDenied401 -> 401
                    is OnlineCashSubmitResult.Forbidden403 -> 403
                    is OnlineCashSubmitResult.Conflict409 -> 409
                    is OnlineCashSubmitResult.HttpError -> submitResult.statusCode
                    else -> null
                }
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_create_201",
                        status = "FAIL",
                        httpStatusCode = statusCode,
                        latencyMs = submitLatency,
                        details = "Fresh cash sale submission failed: $submitResult"
                    )
                )
                overallSuccess = false
                throw AssertionError("Fresh cash sale submission failed: $submitResult")
            }

            val commit = submitResult.response
            assertEquals(clientEventId, commit.clientEventId)
            assertNotNull(commit.officialInvoice)
            assertNotNull(commit.officialInvoice.invoiceId)
            assertNotNull(commit.officialInvoice.invoiceNumber)
            scenarios.add(
                ScenarioEvidence(
                    name = "financial_cash_create_201",
                    status = "PASS",
                    httpStatusCode = 201,
                    latencyMs = submitLatency,
                    details = "Fresh sale created: invoiceId=${commit.officialInvoice.invoiceId.value}, number=${commit.officialInvoice.invoiceNumber}"
                )
            )

            // STEP 5b: Idempotent Exact Replay -> 200 OK
            val tReplayStart = System.currentTimeMillis()
            val replayResult = cashClient.submitSale(
                token = token,
                companyId = config.fixture.companyId,
                clientEventId = clientEventId,
                expectedPayloadDigest = preparedIntent.expectedNormativeProfileDigest,
                canonicalBytes = preparedIntent.rawWireBytes
            )
            val replayLatency = System.currentTimeMillis() - tReplayStart

            if (replayResult !is OnlineCashSubmitResult.Replayed) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_replay_200",
                        status = "FAIL",
                        httpStatusCode = (replayResult as? OnlineCashSubmitResult.HttpError)?.statusCode,
                        latencyMs = replayLatency,
                        details = "Idempotent replay failed: $replayResult"
                    )
                )
                overallSuccess = false
                throw AssertionError("Idempotent replay failed: $replayResult")
            }

            val replayCommit = replayResult.response
            assertEquals(clientEventId, replayCommit.clientEventId)
            assertEquals(commit.officialInvoice.invoiceId, replayCommit.officialInvoice.invoiceId)
            assertEquals(commit.officialInvoice.invoiceNumber, replayCommit.officialInvoice.invoiceNumber)
            scenarios.add(
                ScenarioEvidence(
                    name = "financial_cash_replay_200",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = replayLatency,
                    details = "Idempotent replay verified with identical invoice ID and number"
                )
            )

            // STEP 5c: Status query on posted event
            val tPostedStatus = System.currentTimeMillis()
            val postedStatusResult = cashClient.checkStatus(token, config.fixture.companyId, clientEventId)
            val postedStatusLatency = System.currentTimeMillis() - tPostedStatus

            if (postedStatusResult !is OnlineCashStatusResult.Posted) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_status_parity",
                        status = "FAIL",
                        httpStatusCode = (postedStatusResult as? OnlineCashStatusResult.HttpError)?.statusCode,
                        latencyMs = postedStatusLatency,
                        details = "Status query on posted event did not return Posted: $postedStatusResult"
                    )
                )
                overallSuccess = false
                throw AssertionError("Posted status query failed: $postedStatusResult")
            }

            val postedResp = postedStatusResult.response
            assertEquals("posted", postedResp.status)
            assertEquals(commit.officialInvoice.invoiceId, postedResp.officialInvoice?.invoiceId)
            scenarios.add(
                ScenarioEvidence(
                    name = "financial_cash_status_parity",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = postedStatusLatency,
                    details = "Status query matched committed invoice ID: ${postedResp.officialInvoice?.invoiceId?.value}"
                )
            )

            // STEP 5d: Tampered wire submission with SAME clientEventId -> 409 Conflict
            val tTamperStart = System.currentTimeMillis()
            val tamperedObj = buildStrict7KeyWireJson(config.fixture, clientEventId, tamperedAmount = "99.000000")
            val tamperedBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(tamperedObj)
            val tamperedProfile = OnlineSaleIntentProfile.fromContextAndRequest(context, tamperedObj)
            val tamperedDigest = tamperedProfile.computePayloadDigest()

            val tamperResult = cashClient.submitSale(
                token = token,
                companyId = config.fixture.companyId,
                clientEventId = clientEventId,
                expectedPayloadDigest = tamperedDigest,
                canonicalBytes = tamperedBytes
            )
            val tamperLatency = System.currentTimeMillis() - tTamperStart

            if (tamperResult !is OnlineCashSubmitResult.Conflict409) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "financial_cash_tamper_409",
                        status = "FAIL",
                        httpStatusCode = (tamperResult as? OnlineCashSubmitResult.HttpError)?.statusCode,
                        latencyMs = tamperLatency,
                        details = "Tampered submission with duplicate event ID did not return Conflict409: $tamperResult"
                    )
                )
                overallSuccess = false
                throw AssertionError("Tampered submission did not return 409 Conflict: $tamperResult")
            }

            scenarios.add(
                ScenarioEvidence(
                    name = "financial_cash_tamper_409",
                    status = "PASS",
                    httpStatusCode = 409,
                    latencyMs = tamperLatency,
                    details = "Duplicate clientEventId with tampered payload strictly rejected with HTTP 409 Conflict"
                )
            )

            // STEP 5e: Coordinated fault lost ACK recovery
            if (config.coordinatedFaultAvailable) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "transport_lost_ack_recovery",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Backend coordinated fault injection not yet enabled in current harness version"
                    )
                )
            } else {
                scenarios.add(
                    ScenarioEvidence(
                        name = "transport_lost_ack_recovery",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Harness coordinated fault scenario not provided by backend; not pretending PASS"
                    )
                )
            }

            // STEP 5f: Stock/permission/rollback probes
            if (config.stockProbesAvailable) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "stock_permission_rollback_probes",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Stock probe fixtures not yet enabled in current harness version"
                    )
                )
            } else {
                scenarios.add(
                    ScenarioEvidence(
                        name = "stock_permission_rollback_probes",
                        status = "NOT_RUN",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "Stock/permission/rollback probes need provisioned fixtures; keeping separate scenarios NOT_RUN"
                    )
                )
            }
        } finally {
            try { truststoreCloseable?.close() } catch (_: Throwable) {}

            // Write evidence file
            if (config != null) {
                val evidence = CashPilotEvidence(
                    backendHead = config.backendCommitSha,
                    clientHead = config.clientCommitSha,
                    manifestDigest = config.manifestDigest,
                    baseUrl = config.baseUrl,
                    lifecycleGrant = config.lifecycleGrant,
                    ttlExpiresAt = config.ttlExpiresAt,
                    overallStatus = if (overallSuccess) "PASS" else "FAIL",
                    scenarios = scenarios
                )
                try {
                    val evidenceDir = File("docs/integration/v2-online-cash-evidence")
                    evidence.writeTo(File(evidenceDir, "paired-online-cash-pilot-evidence.json"))
                } catch (_: Throwable) {}
            }
        }
    }

    private fun buildStrict7KeyWireJson(
        fixture: CashPilotFixture,
        clientEventId: ClientUuid,
        tamperedAmount: String? = null
    ): StrictJsonObject {
        val lines = StrictJsonArray(
            listOf(
                StrictJsonObject(
                    linkedMapOf(
                        "product_id" to StrictJsonString(fixture.productId.value),
                        "product_unit_id" to StrictJsonString(fixture.productUnitId.value),
                        "conversion_to_base" to StrictJsonString("1.000000"),
                        "quantity" to StrictJsonString("1.000000"),
                        "unit_price" to StrictJsonString(fixture.unitPrice),
                        "tax_rate_id" to StrictJsonString(fixture.taxRateId.value),
                        "tax_rate" to StrictJsonString(fixture.taxRate),
                        "tax_inclusive" to StrictJsonBoolean(false)
                    )
                )
            )
        )

        val paymentAmount = tamperedAmount ?: "58.000000"
        val payment = StrictJsonObject(
            linkedMapOf(
                "settlement_mode" to StrictJsonString("full"),
                "payment_method" to StrictJsonString("cash"),
                "cash_account_id" to StrictJsonString(fixture.cashAccountId.value),
                "amount" to StrictJsonString(paymentAmount)
            )
        )

        return StrictJsonObject(
            linkedMapOf(
                "client_event_id" to StrictJsonString(clientEventId.value),
                "device_id" to StrictJsonString(fixture.deviceId.value),
                "customer_id" to StrictJsonString(fixture.customerId.value),
                "warehouse_id" to StrictJsonString(fixture.warehouseId.value),
                "currency" to StrictJsonString(fixture.companyBaseCurrency),
                "lines" to lines,
                "payment" to payment
            )
        )
    }
}
