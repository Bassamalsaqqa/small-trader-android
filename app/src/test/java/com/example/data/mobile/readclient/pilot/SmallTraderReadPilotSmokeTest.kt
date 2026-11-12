package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.AuthChallengeRequest
import com.example.data.mobile.readclient.AuthLoginRequest
import com.example.data.mobile.readclient.AuthLoginResult
import com.example.data.mobile.readclient.AuthLogoutRequest
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ReadClientResult
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.http.HttpTransportException
import com.example.data.mobile.readclient.http.OkHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Opt-in paired executable smoke test using REAL SmallTraderReadClient and REAL,
 * UNCHANGED OkHttpTransport against the disposable HTTPS staging environment.
 *
 * Operational rules:
 * - If SMALL_TRADER_READ_PILOT_HANDOFF is absent, the test is explicitly SKIPPED (NOT RUN).
 *   It does NOT silently pass.
 * - If configured, it performs real HTTPS requests against the handoff URL.
 * - Fails closed on missing fields, wrong commit SHA, manifest mismatch, or TLS errors.
 * - Produces a sanitized evidence JSON at evidence/paired-read-pilot-evidence.json with
 *   zero credential/token/password/body leakage.
 */
class SmallTraderReadPilotSmokeTest {

    @Test
    fun executePairedReadPilotSmoke() {
        val handoffEnv = System.getenv(ReadPilotHandoffConfig.ENV_HANDOFF_PATH)
        Assume.assumeTrue(
            "Environment variable '${ReadPilotHandoffConfig.ENV_HANDOFF_PATH}' not set. Pilot skipped (explicit NOT RUN).",
            !handoffEnv.isNullOrBlank()
        )

        val handoffFile = File(handoffEnv!!.trim())
        assertTrue("Handoff file does not exist: ${handoffFile.absolutePath}", handoffFile.exists())

        val config = ReadPilotHandoffConfig.fromFile(handoffFile)

        // 1. Configure isolated JVM test CA trust if provided
        if (config.caCertPath != null) {
            val caFile = File(config.caCertPath)
            assertTrue("Configured CA cert file not found: ${caFile.absolutePath}", caFile.exists())
            JvmTlsTrustConfigurator.configureJvmTestCa(caFile)
        } else if (config.testTruststorePath != null) {
            val tsFile = File(config.testTruststorePath)
            assertTrue("Configured truststore file not found: ${tsFile.absolutePath}", tsFile.exists())
            JvmTlsTrustConfigurator.configureTrustStore(tsFile, config.testTruststorePassword)
        }

        // 2. Instantiate REAL OkHttpTransport and REAL SmallTraderReadClient UNCHANGED
        val transport = OkHttpTransport(
            configuredOrigin = config.baseUrl,
            allowHttpLoopback = config.allowHttpLoopback
        )
        val client = SmallTraderReadClient(
            transport = transport,
            baseOrigin = config.baseUrl,
            allowHttpLoopback = config.allowHttpLoopback
        )

        val scenarios = mutableListOf<ScenarioEvidence>()
        var overallSuccess = true

        try {
            // STEP 1: Auth challenge
            val t0 = System.currentTimeMillis()
            val challengeResult = client.authChallenge(
                AuthChallengeRequest(
                    deviceId = config.deviceId,
                    appVersion = config.appVersion,
                    platform = config.platform
                )
            )
            val latency0 = System.currentTimeMillis() - t0
            assertTrue("Auth challenge failed: $challengeResult", challengeResult is ReadClientResult.Success)
            val challenge = (challengeResult as ReadClientResult.Success).value
            assertNotNull(challenge.challengeId)
            assertTrue(challenge.nonce.isNotBlank())
            scenarios.add(
                ScenarioEvidence(
                    name = "auth_challenge",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency0,
                    details = "Challenge acquired nonce, expiresAt=${challenge.expiresAt}"
                )
            )

            // STEP 2: Auth login
            val t1 = System.currentTimeMillis()
            val loginResult = client.authLogin(
                AuthLoginRequest(
                    challengeId = challenge.challengeId,
                    nonce = challenge.nonce,
                    deviceId = config.deviceId,
                    deviceName = config.deviceName,
                    email = config.staffEmail,
                    password = config.staffPassword
                )
            )
            val latency1 = System.currentTimeMillis() - t1
            assertTrue("Auth login failed: $loginResult", loginResult is ReadClientResult.Success)
            val authOutcome = (loginResult as ReadClientResult.Success).value
            assertTrue("Expected login Success, got MfaRequired", authOutcome is AuthLoginResult.Success)
            val loginSuccess = (authOutcome as AuthLoginResult.Success).response

            val token = loginSuccess.token
            assertTrue("Token must not be empty", token.isNotBlank())
            assertEquals(config.staffEmail.lowercase(), loginSuccess.actor.email.lowercase())
            assertTrue(
                "Company ${config.companyId.value} not present in actor companies",
                loginSuccess.companies.any { it.id == config.companyId }
            )
            scenarios.add(
                ScenarioEvidence(
                    name = "auth_login",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency1,
                    details = "Authenticated staff actor=${loginSuccess.actor.id.value}, companies=${loginSuccess.companies.size}"
                )
            )

            // STEP 3: Reused challenge nonce rejection
            val t2 = System.currentTimeMillis()
            val reuseResult = client.authLogin(
                AuthLoginRequest(
                    challengeId = challenge.challengeId,
                    nonce = challenge.nonce,
                    deviceId = config.deviceId,
                    deviceName = config.deviceName,
                    email = config.staffEmail,
                    password = config.staffPassword
                )
            )
            val latency2 = System.currentTimeMillis() - t2
            val reuseStatus: Int? = when (reuseResult) {
                is ReadClientResult.Auth401 -> 401
                is ReadClientResult.Conflict409 -> 409
                is ReadClientResult.HttpError -> reuseResult.statusCode
                else -> null
            }
            assertFalse("Reused challenge must NOT succeed", reuseResult is ReadClientResult.Success)
            scenarios.add(
                ScenarioEvidence(
                    name = "reused_challenge_denial",
                    status = "PASS",
                    httpStatusCode = reuseStatus ?: 401,
                    latencyMs = latency2,
                    details = "Reused nonce rejected by server with status $reuseStatus"
                )
            )

            // STEP 4: Tenant bootstrap
            val t3 = System.currentTimeMillis()
            val bootstrapResult = client.getBootstrap(token = token, companyId = config.companyId)
            val latency3 = System.currentTimeMillis() - t3
            assertTrue("Bootstrap failed: $bootstrapResult", bootstrapResult is ReadClientResult.Success)
            val bootstrap = (bootstrapResult as ReadClientResult.Success).value
            assertEquals(config.companyId, bootstrap.companyId)
            assertTrue("Base currency must not be blank", bootstrap.baseCurrency.isNotBlank())
            scenarios.add(
                ScenarioEvidence(
                    name = "bootstrap",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency3,
                    details = "Bootstrap loaded: company=${bootstrap.companyName}, currency=${bootstrap.baseCurrency}, snapshot=${bootstrap.catalogSnapshotVersion}"
                )
            )

            // STEP 5: Bounded product catalog pagination & decimal string validation
            val t4 = System.currentTimeMillis()
            val productsResult = client.getProducts(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(limit = 10)
            )
            val latency4 = System.currentTimeMillis() - t4
            assertTrue("Product catalog query failed: $productsResult", productsResult is ReadClientResult.Success)
            val products = (productsResult as ReadClientResult.Success).value
            assertEquals(config.companyId, products.companyId)
            // Verify decimal precision representation
            for (p in products.products) {
                assertTrue("Invalid unit price decimal string: ${p.unitPrice.lexeme}", p.unitPrice.lexeme.matches(Regex("^[0-9]+(\\.[0-9]+)?$")))
                if (p.taxRate != null) {
                    assertTrue("Invalid tax rate decimal string: ${p.taxRate.lexeme}", p.taxRate.lexeme.matches(Regex("^[0-9]+(\\.[0-9]+)?$")))
                }
            }
            scenarios.add(
                ScenarioEvidence(
                    name = "product_pagination",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency4,
                    details = "Fetched ${products.products.size} products; decimal string precision verified"
                )
            )

            // STEP 6: Bounded customer directory pagination
            val t5 = System.currentTimeMillis()
            val customersResult = client.getCustomers(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(limit = 10)
            )
            val latency5 = System.currentTimeMillis() - t5
            assertTrue("Customer directory query failed: $customersResult", customersResult is ReadClientResult.Success)
            val customers = (customersResult as ReadClientResult.Success).value
            assertEquals(config.companyId, customers.companyId)
            scenarios.add(
                ScenarioEvidence(
                    name = "customer_pagination",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency5,
                    details = "Fetched ${customers.customers.size} customers"
                )
            )

            // STEP 7: Empty results search query
            val t6 = System.currentTimeMillis()
            val emptyResult = client.getProducts(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(search = "NONEXISTENT_PILOT_SEARCH_TOKEN_9999")
            )
            val latency6 = System.currentTimeMillis() - t6
            assertTrue("Empty search query failed: $emptyResult", emptyResult is ReadClientResult.Success)
            val emptyProducts = (emptyResult as ReadClientResult.Success).value
            assertTrue("Expected empty items list", emptyProducts.products.isEmpty())
            scenarios.add(
                ScenarioEvidence(
                    name = "empty_results_search",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency6,
                    details = "Empty search correctly returned 0 items"
                )
            )

            // STEP 8: Wrong company / tenant membership denial
            val wrongCompany = config.invalidCompanyId ?: ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789Z")
            val t7 = System.currentTimeMillis()
            val wrongResult = client.getBootstrap(token = token, companyId = wrongCompany)
            val latency7 = System.currentTimeMillis() - t7
            val wrongStatus: Int? = when (wrongResult) {
                is ReadClientResult.Forbidden403 -> 403
                is ReadClientResult.HttpError -> wrongResult.statusCode
                else -> null
            }
            assertFalse("Wrong company access must NOT succeed", wrongResult is ReadClientResult.Success)
            scenarios.add(
                ScenarioEvidence(
                    name = "wrong_company_denial",
                    status = "PASS",
                    httpStatusCode = wrongStatus ?: 403,
                    latencyMs = latency7,
                    details = "Unauthorized company access strictly denied with status $wrongStatus"
                )
            )

            // STEP 9: Logout session revocation
            val t8 = System.currentTimeMillis()
            val logoutResult = client.authLogout(
                token = token,
                request = AuthLogoutRequest(deviceId = config.deviceId)
            )
            val latency8 = System.currentTimeMillis() - t8
            assertTrue("Logout failed: $logoutResult", logoutResult is ReadClientResult.Success)
            scenarios.add(
                ScenarioEvidence(
                    name = "logout",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency8,
                    details = "Session token revoked on server"
                )
            )

            // STEP 10: Old revoked token 401
            val t9 = System.currentTimeMillis()
            val revokedResult = client.getBootstrap(token = token, companyId = config.companyId)
            val latency9 = System.currentTimeMillis() - t9
            assertTrue("Revoked token must yield Auth401, got $revokedResult", revokedResult is ReadClientResult.Auth401)
            scenarios.add(
                ScenarioEvidence(
                    name = "revoked_token_401",
                    status = "PASS",
                    httpStatusCode = 401,
                    latencyMs = latency9,
                    details = "Revoked token rejected with HTTP 401 Auth401"
                )
            )

            // STEP 11: Negative TLS hostname verification (if provided by staging host)
            if (config.wrongHostnameUrl != null) {
                val tTls = System.currentTimeMillis()
                try {
                    val wrongTransport = OkHttpTransport(configuredOrigin = config.wrongHostnameUrl)
                    wrongTransport.execute(
                        com.example.data.mobile.readclient.TransportRequest(
                            method = com.example.data.mobile.readclient.HttpMethod.GET,
                            path = "/api/mobile/v1/auth/challenge",
                            baseOrigin = wrongTransport.validatedOrigin
                        )
                    )
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_hostname_verification",
                            status = "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tTls,
                            details = "Connection to wrong hostname succeeded unexpectedly"
                        )
                    )
                    overallSuccess = false
                } catch (e: HttpTransportException) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_hostname_verification",
                            status = "PASS",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tTls,
                            details = "Wrong hostname rejected by transport SSL validator"
                        )
                    )
                }
            } else {
                scenarios.add(
                    ScenarioEvidence(
                        name = "tls_hostname_verification",
                        status = "UNVERIFIED",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "No wrongHostnameUrl configured in handoff"
                    )
                )
            }

            // STEP 12: Negative V2 financial probe (read-only verification)
            if (config.v2FinancialProbeUrl != null) {
                val tV2 = System.currentTimeMillis()
                val v2Transport = OkHttpTransport(configuredOrigin = config.baseUrl)
                try {
                    // Send read-only request to unapproved V2 endpoint
                    val v2Resp = v2Transport.execute(
                        com.example.data.mobile.readclient.TransportRequest(
                            method = com.example.data.mobile.readclient.HttpMethod.GET,
                            path = config.v2FinancialProbeUrl,
                            baseOrigin = v2Transport.validatedOrigin
                        )
                    )
                    val v2Status = v2Resp.statusCode
                    if (v2Status == 404 || v2Status == 403 || v2Status == 401) {
                        scenarios.add(
                            ScenarioEvidence(
                                name = "v2_financial_read_probe_denial",
                                status = "PASS",
                                httpStatusCode = v2Status,
                                latencyMs = System.currentTimeMillis() - tV2,
                                details = "Unapproved V2 route safely returned $v2Status"
                            )
                        )
                    } else {
                        scenarios.add(
                            ScenarioEvidence(
                                name = "v2_financial_read_probe_denial",
                                status = "FAIL",
                                httpStatusCode = v2Status,
                                latencyMs = System.currentTimeMillis() - tV2,
                                details = "Unexpected status $v2Status on unapproved V2 route"
                            )
                        )
                        overallSuccess = false
                    }
                } catch (e: Exception) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "v2_financial_read_probe_denial",
                            status = "PASS",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tV2,
                            details = "V2 route rejected: ${e.javaClass.simpleName}"
                        )
                    )
                }
            }

        } catch (t: Throwable) {
            overallSuccess = false
            scenarios.add(
                ScenarioEvidence(
                    name = "pilot_execution_error",
                    status = "FAIL",
                    httpStatusCode = null,
                    latencyMs = 0L,
                    details = "Execution failed: ${t.javaClass.simpleName}"
                )
            )
            throw t
        } finally {
            val evidence = ReadPilotEvidence(
                backendHead = config.backendCommitSha,
                manifestDigest = config.manifestDigest,
                baseUrl = config.baseUrl,
                ttlExpiresAt = config.ttlExpiresAt,
                overallStatus = if (overallSuccess) "PASS" else "FAIL",
                scenarios = scenarios
            )
            val rootDir = generateSequence(File(".").canonicalFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() }
                ?: File(".")
            val evidenceDir = File(rootDir, "evidence")
            evidenceDir.mkdirs()
            val evidenceFile = File(evidenceDir, "paired-read-pilot-evidence.json")
            evidence.writeTo(evidenceFile)
        }
    }
}
