package com.example.data.mobile.readclient.pilot

import com.example.data.mobile.readclient.AuthChallengeRequest
import com.example.data.mobile.readclient.AuthLoginRequest
import com.example.data.mobile.readclient.AuthLoginResult
import com.example.data.mobile.readclient.AuthLogoutRequest
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ReadClientResult
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.http.HttpTransportException
import com.example.data.mobile.readclient.http.OkHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.Closeable
import java.io.File

/**
 * Opt-in paired executable smoke test using REAL SmallTraderReadClient and REAL,
 * UNCHANGED OkHttpTransport against the disposable HTTPS staging environment.
 *
 * Operational rules:
 * - If SMALL_TRADER_READ_PILOT_HANDOFF is absent, the test is explicitly SKIPPED (NOT RUN).
 *   It does NOT silently pass.
 * - If configured, SMALL_TRADER_EXPECTED_BACKEND_SHA must be explicitly set to independently
 *   verify backend commit SHA; never trust handoff alone.
 * - If configured, it performs real HTTPS requests against the handoff URL.
 * - Strict aggregate fail in JUnit and artifact-consistent FAIL on setup or scenario failure.
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

        val expectedShaEnv = System.getenv(ReadPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA)?.trim()
        require(!expectedShaEnv.isNullOrBlank()) {
            "Safety invariant violation: ${ReadPilotHandoffConfig.ENV_EXPECTED_BACKEND_SHA} is required; never trust handoff alone"
        }

        val handoffFile = File(handoffEnv!!.trim())
        var overallSuccess = true
        val scenarios = mutableListOf<ScenarioEvidence>()
        var truststoreCloseable: Closeable? = null
        var config: ReadPilotHandoffConfig? = null

        try {
            // STEP 0: Load and validate hand-off configuration with required independent backend SHA
            assertTrue("Handoff file does not exist: [REDACTED_FILE]", handoffFile.exists())
            config = ReadPilotHandoffConfig.fromFile(
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

            // Instantiate REAL OkHttpTransport and REAL SmallTraderReadClient
            val transport = OkHttpTransport(
                configuredOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )
            val client = SmallTraderReadClient(
                transport = transport,
                baseOrigin = config.baseUrl,
                allowHttpLoopback = config.allowHttpLoopback
            )

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

            // STEP 2: Auth login with staff1
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

            val token: String
            if (authOutcome is AuthLoginResult.MfaRequired) {
                scenarios.add(
                    ScenarioEvidence(
                        name = "auth_login_mfa",
                        status = "NOT_RUN",
                        httpStatusCode = 200,
                        latencyMs = latency1,
                        details = "Server requested MFA; staff1 runner configured without MFA requirement"
                    )
                )
                overallSuccess = false
                throw IllegalStateException("MFA challenge received unexpectedly for staff1")
            } else if (authOutcome is AuthLoginResult.Success) {
                val loginSuccess = authOutcome.response
                token = loginSuccess.token
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
            } else {
                throw IllegalStateException("Unexpected AuthLoginResult type")
            }

            // STEP 3: Reused challenge nonce rejection (Strict HTTP 401 or 409 required; no fabricated fallback)
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
            val (reusePassed, reuseCode, reuseDetails) = when (reuseResult) {
                is ReadClientResult.Auth401 -> Triple(true, 401, "Reused challenge strictly rejected with Auth401")
                is ReadClientResult.Conflict409 -> Triple(true, 409, "Reused challenge strictly rejected with Conflict409")
                is ReadClientResult.HttpError -> {
                    if (reuseResult.statusCode == 401 || reuseResult.statusCode == 409) {
                        Triple(true, reuseResult.statusCode, "Reused challenge rejected with HTTP ${reuseResult.statusCode}")
                    } else {
                        Triple(false, reuseResult.statusCode, "Unexpected HTTP status ${reuseResult.statusCode} on reuse denial (expected 401 or 409)")
                    }
                }
                is ReadClientResult.NetworkUnknown -> Triple(false, null, "Network error during reuse attempt (not an authoritative HTTP denial)")
                is ReadClientResult.ParseFailure -> Triple(false, null, "Parse failure during reuse attempt")
                is ReadClientResult.Success -> Triple(false, 200, "FATAL: Reused challenge succeeded unexpectedly")
                else -> Triple(false, null, "Unexpected result type: ${reuseResult.javaClass.simpleName}")
            }
            scenarios.add(
                ScenarioEvidence(
                    name = "reused_challenge_denial",
                    status = if (reusePassed) "PASS" else "FAIL",
                    httpStatusCode = reuseCode,
                    latencyMs = latency2,
                    details = reuseDetails
                )
            )
            if (!reusePassed) {
                overallSuccess = false
                throw AssertionError("Reused challenge denial failed: $reuseDetails")
            }

            // STEP 4: Tenant bootstrap
            val t3 = System.currentTimeMillis()
            val bootstrapResult = client.getBootstrap(token = token, companyId = config.companyId)
            val latency3 = System.currentTimeMillis() - t3
            assertTrue("Bootstrap failed: $bootstrapResult", bootstrapResult is ReadClientResult.Success)
            val bootstrap = (bootstrapResult as ReadClientResult.Success).value
            assertEquals(config.companyId, bootstrap.companyId)
            assertTrue("Base currency must not be blank", bootstrap.baseCurrency.isNotBlank())
            if (config.baseCurrency != null) {
                assertEquals("Base currency mismatch with handoff metadata", config.baseCurrency, bootstrap.baseCurrency)
            }
            scenarios.add(
                ScenarioEvidence(
                    name = "bootstrap",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency3,
                    details = "Bootstrap loaded: currency=${bootstrap.baseCurrency}, snapshot=${bootstrap.catalogSnapshotVersion}"
                )
            )

            // STEP 5: Deterministic product catalog pagination (Page 1: 2 items, Page 2: 1 item, total = 3 unique items)
            val t4Start = System.currentTimeMillis()
            val prodPage1Result = client.getProducts(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(limit = 2, cursor = null)
            )
            assertTrue("Product page 1 fetch failed: $prodPage1Result", prodPage1Result is ReadClientResult.Success)
            val prodPage1 = (prodPage1Result as ReadClientResult.Success).value
            assertEquals("Product page 1 must return exactly 2 items", 2, prodPage1.products.size)
            assertTrue("Product page 1 must provide nextCursor", !prodPage1.nextCursor.isNullOrBlank())

            val prodPage2Result = client.getProducts(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(limit = 2, cursor = prodPage1.nextCursor)
            )
            assertTrue("Product page 2 fetch failed: $prodPage2Result", prodPage2Result is ReadClientResult.Success)
            val prodPage2 = (prodPage2Result as ReadClientResult.Success).value
            assertEquals("Product page 2 must return exactly 1 item", 1, prodPage2.products.size)
            assertTrue("Product page 2 nextCursor must be empty", prodPage2.nextCursor.isNullOrBlank())

            assertEquals("Catalog snapshot version must remain consistent across pages", prodPage1.catalogSnapshotVersion, prodPage2.catalogSnapshotVersion)
            val allProducts = prodPage1.products + prodPage2.products
            val collectedProductIds = allProducts.map { it.id.value }.toSet()
            assertEquals("Total unique products across pages 2+1 must be 3", 3, collectedProductIds.size)

            for (p in allProducts) {
                assertTrue("Malformed unitPrice decimal string: ${p.unitPrice.lexeme}", p.unitPrice.lexeme.matches(Regex("^[0-9]+(\\.[0-9]+)?$")))
                if (p.taxRate != null) {
                    assertTrue("Malformed taxRate decimal string: ${p.taxRate.lexeme}", p.taxRate.lexeme.matches(Regex("^[0-9]+(\\.[0-9]+)?$")))
                }
            }
            assertEquals("Exactly three independently supplied product fixtures are required", 3, config.expectedProducts.size)
            assertEquals("Product page ordering must match independent fixture IDs", config.expectedProducts.map { it.id }, allProducts.map { it.id.value })
            val expectedProductsById = config.expectedProducts.associateBy { it.id }
            assertEquals("Expected product fixture IDs must be unique", 3, expectedProductsById.size)
            assertEquals("Product IDs must match independently supplied fixtures", expectedProductsById.keys, collectedProductIds)
            for (product in allProducts) {
                val expected = expectedProductsById.getValue(product.id.value)
                assertEquals("Exact unit-price lexeme must match fixture", expected.exactUnitPrice, product.unitPrice.lexeme)
                assertEquals("Exact nullable tax-rate lexeme must match fixture", expected.taxRate, product.taxRate?.lexeme)
                assertEquals("Nullable tax association must match fixture; unassigned is not NoTax", expected.taxRateId, product.taxRateId?.value)
                assertEquals("SKU must match independent fixture", expected.sku, product.sku)
            }
            val latency4 = System.currentTimeMillis() - t4Start
            scenarios.add(
                ScenarioEvidence(
                    name = "product_pagination",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency4,
                    details = "Deterministic 2+1 pagination verified: 2 pages, 3 unique products, snapshot consistent, exact fixture IDs and price/tax lexemes matched"
                )
            )

            // STEP 6: Deterministic customer directory pagination (Page 1: 2 items, Page 2: 1 item, total = 3 unique items)
            val t5Start = System.currentTimeMillis()
            val custPage1Result = client.getCustomers(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(limit = 2, cursor = null)
            )
            assertTrue("Customer page 1 fetch failed: $custPage1Result", custPage1Result is ReadClientResult.Success)
            val custPage1 = (custPage1Result as ReadClientResult.Success).value
            assertEquals("Customer page 1 must return exactly 2 items", 2, custPage1.customers.size)
            assertTrue("Customer page 1 must provide nextCursor", !custPage1.nextCursor.isNullOrBlank())

            val custPage2Result = client.getCustomers(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(limit = 2, cursor = custPage1.nextCursor)
            )
            assertTrue("Customer page 2 fetch failed: $custPage2Result", custPage2Result is ReadClientResult.Success)
            val custPage2 = (custPage2Result as ReadClientResult.Success).value
            assertEquals("Customer page 2 must return exactly 1 item", 1, custPage2.customers.size)
            assertTrue("Customer page 2 nextCursor must be empty", custPage2.nextCursor.isNullOrBlank())

            val allCustomers = custPage1.customers + custPage2.customers
            val collectedCustomerIds = allCustomers.map { it.id.value }.toSet()
            assertEquals("Total unique customers across pages 2+1 must be 3", 3, collectedCustomerIds.size)
            assertEquals("Exactly three independently supplied customer fixtures are required", 3, config.expectedCustomers.size)
            assertEquals("Customer page ordering must match independent fixture IDs", config.expectedCustomers, allCustomers.map { it.id.value })
            assertEquals("Expected customer fixture IDs must be unique", 3, config.expectedCustomers.toSet().size)
            assertEquals("Customer IDs must match independently supplied fixtures", config.expectedCustomers.toSet(), collectedCustomerIds)
            val latency5 = System.currentTimeMillis() - t5Start
            scenarios.add(
                ScenarioEvidence(
                    name = "customer_pagination",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency5,
                    details = "Deterministic 2+1 pagination verified: 2 pages, 3 unique customers matching independent fixture IDs"
                )
            )

            // STEP 7: Empty results search query with NONEXISTENT_NONCE_7392
            val searchNonce = "NONEXISTENT_NONCE_7392"
            val t6 = System.currentTimeMillis()
            val emptyResult = client.getProducts(
                token = token,
                companyId = config.companyId,
                query = PaginationQuery(search = searchNonce)
            )
            val latency6 = System.currentTimeMillis() - t6
            assertTrue("Empty search query failed: $emptyResult", emptyResult is ReadClientResult.Success)
            val emptyProducts = (emptyResult as ReadClientResult.Success).value
            assertTrue("Expected empty items list for search nonce", emptyProducts.products.isEmpty())
            scenarios.add(
                ScenarioEvidence(
                    name = "empty_results_search",
                    status = "PASS",
                    httpStatusCode = 200,
                    latencyMs = latency6,
                    details = "Empty search with NONEXISTENT_NONCE_7392 correctly returned 0 items"
                )
            )

            // STEP 8: Wrong company / tenant membership denial (Strict HTTP 403 on real foreign existing company)
            val wrongCompany = config.invalidCompanyId
            val t7 = System.currentTimeMillis()
            val wrongResult = client.getBootstrap(token = token, companyId = wrongCompany)
            val latency7 = System.currentTimeMillis() - t7
            val (wrongPassed, wrongCode, wrongDetails) = when (wrongResult) {
                is ReadClientResult.Forbidden403 -> Triple(true, 403, "Foreign tenant access strictly denied with Forbidden403 (real foreign company fixture, tenant boundary verified; nonexistent-company oracle behavior not tested)")
                is ReadClientResult.HttpError -> {
                    if (wrongResult.statusCode == 403) {
                        Triple(true, 403, "Foreign tenant access denied with HTTP 403 (tenant boundary verified; nonexistent-company oracle behavior not tested)")
                    } else {
                        Triple(false, wrongResult.statusCode, "Unexpected HTTP status ${wrongResult.statusCode} on foreign tenant access (expected 403)")
                    }
                }
                is ReadClientResult.NetworkUnknown -> Triple(false, null, "Network error during foreign tenant query (not an authoritative HTTP denial)")
                is ReadClientResult.ParseFailure -> Triple(false, null, "Parse failure during foreign tenant query")
                is ReadClientResult.Success -> Triple(false, 200, "FATAL: Foreign tenant access succeeded unexpectedly")
                else -> Triple(false, null, "Unexpected result type: ${wrongResult.javaClass.simpleName}")
            }
            scenarios.add(
                ScenarioEvidence(
                    name = "wrong_company_denial",
                    status = if (wrongPassed) "PASS" else "FAIL",
                    httpStatusCode = wrongCode,
                    latencyMs = latency7,
                    details = wrongDetails
                )
            )
            if (!wrongPassed) {
                overallSuccess = false
                throw AssertionError("Foreign tenant denial failed: $wrongDetails")
            }

            // STEP 8b: Revoked device ID login denial (SCN-07) if provided
            if (config.revokedDeviceId != null) {
                val tRev = System.currentTimeMillis()
                val chalRevResult = client.authChallenge(
                    AuthChallengeRequest(
                        deviceId = config.revokedDeviceId,
                        appVersion = config.appVersion,
                        platform = config.platform
                    )
                )
                assertTrue("Challenge for revoked device failed: $chalRevResult", chalRevResult is ReadClientResult.Success)
                val chalRev = (chalRevResult as ReadClientResult.Success).value

                val revLoginResult = client.authLogin(
                    AuthLoginRequest(
                        challengeId = chalRev.challengeId,
                        nonce = chalRev.nonce,
                        deviceId = config.revokedDeviceId,
                        deviceName = config.deviceName,
                        email = config.staffEmail,
                        password = config.staffPassword
                    )
                )
                val latencyRev = System.currentTimeMillis() - tRev
                val (revPassed, revCode, revDetails) = when (revLoginResult) {
                    is ReadClientResult.Auth401 -> Triple(true, 401, "Revoked device login rejected with Auth401 (scope limit: login refusal, not issued token revocation without logout)")
                    is ReadClientResult.HttpError -> {
                        if (revLoginResult.statusCode == 401) {
                            Triple(true, 401, "Revoked device login rejected with HTTP 401")
                        } else {
                            Triple(false, revLoginResult.statusCode, "Unexpected HTTP status ${revLoginResult.statusCode} on revoked device login")
                        }
                    }
                    else -> Triple(false, null, "Unexpected response for revoked device login: ${revLoginResult.javaClass.simpleName}")
                }
                scenarios.add(
                    ScenarioEvidence(
                        name = "revoked_device_login_denial",
                        status = if (revPassed) "PASS" else "FAIL",
                        httpStatusCode = revCode,
                        latencyMs = latencyRev,
                        details = revDetails
                    )
                )
                if (!revPassed) {
                    overallSuccess = false
                    throw AssertionError("Revoked device login denial failed: $revDetails")
                }
            }

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

            // STEP 11: Negative TLS hostname verification using allowlisted POST challenge
            if (config.wrongHostnameUrl != null) {
                val tTls = System.currentTimeMillis()
                try {
                    val wrongTransport = OkHttpTransport(
                        configuredOrigin = config.wrongHostnameUrl,
                        allowHttpLoopback = config.allowHttpLoopback
                    )
                    // Send VALID allowlisted challenge request
                    wrongTransport.execute(
                        TransportRequest(
                            method = HttpMethod.POST,
                            path = "/api/mobile/v1/auth/challenge",
                            baseOrigin = wrongTransport.validatedOrigin,
                            body = "{\"device_id\":\"${config.deviceId.value}\",\"app_version\":\"${config.appVersion}\",\"platform\":\"${config.platform}\"}"
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
                    val isTlsError = e.category == HttpTransportException.CATEGORY_TLS_ERROR
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_hostname_verification",
                            status = if (isTlsError) "PASS" else "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tTls,
                            details = if (isTlsError) "Wrong hostname rejected by transport TLS validator (CATEGORY_TLS_ERROR)" else "Rejected with non-TLS category: ${e.category}"
                        )
                    )
                    if (!isTlsError) overallSuccess = false
                } catch (e: Throwable) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_hostname_verification",
                            status = "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tTls,
                            details = "Unexpected exception: ${e.javaClass.simpleName}"
                        )
                    )
                    overallSuccess = false
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

            // STEP 12: Negative TLS untrusted certificate verification
            if (config.untrustedCertUrl != null) {
                val tUntrusted = System.currentTimeMillis()
                try {
                    val untrustedTransport = OkHttpTransport(
                        configuredOrigin = config.untrustedCertUrl,
                        allowHttpLoopback = config.allowHttpLoopback
                    )
                    untrustedTransport.execute(
                        TransportRequest(
                            method = HttpMethod.POST,
                            path = "/api/mobile/v1/auth/challenge",
                            baseOrigin = untrustedTransport.validatedOrigin,
                            body = "{\"device_id\":\"${config.deviceId.value}\",\"app_version\":\"${config.appVersion}\",\"platform\":\"${config.platform}\"}"
                        )
                    )
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_untrusted_cert_verification",
                            status = "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tUntrusted,
                            details = "Connection with untrusted certificate succeeded unexpectedly"
                        )
                    )
                    overallSuccess = false
                } catch (e: HttpTransportException) {
                    val isTlsError = e.category == HttpTransportException.CATEGORY_TLS_ERROR
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_untrusted_cert_verification",
                            status = if (isTlsError) "PASS" else "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tUntrusted,
                            details = if (isTlsError) "Untrusted certificate rejected by transport TLS validator (CATEGORY_TLS_ERROR)" else "Rejected with non-TLS category: ${e.category}"
                        )
                    )
                    if (!isTlsError) overallSuccess = false
                } catch (e: Throwable) {
                    scenarios.add(
                        ScenarioEvidence(
                            name = "tls_untrusted_cert_verification",
                            status = "FAIL",
                            httpStatusCode = null,
                            latencyMs = System.currentTimeMillis() - tUntrusted,
                            details = "Unexpected exception: ${e.javaClass.simpleName}"
                        )
                    )
                    overallSuccess = false
                }
            } else {
                scenarios.add(
                    ScenarioEvidence(
                        name = "tls_untrusted_cert_verification",
                        status = "UNVERIFIED",
                        httpStatusCode = null,
                        latencyMs = 0L,
                        details = "No untrustedCertUrl configured in handoff"
                    )
                )
            }

            // STEP 13: V2 Financial Boundary Reference (Backend-owned proof reference; wire probe removed)
            scenarios.add(
                ScenarioEvidence(
                    name = "v2_financial_boundary",
                    status = "NOT_RUN",
                    httpStatusCode = null,
                    latencyMs = 0L,
                    details = "V2 route (/api/mobile/v2/...) remains outside V1 allowlist; backend supervisor owns actual HTTPS default-OFF 404 and container DenyFinancialExecutionAdapter evidence"
                )
            )

        } catch (t: Throwable) {
            overallSuccess = false
            scenarios.add(
                ScenarioEvidence(
                    name = "pilot_execution_error",
                    status = "FAIL",
                    httpStatusCode = null,
                    latencyMs = 0L,
                    details = "Execution failed: ${t.javaClass.simpleName} - ${t.message ?: "no message"}"
                )
            )
            throw t
        } finally {
            try {
                truststoreCloseable?.close()
            } catch (_: Throwable) {}

            val finalOverallStatus = if (overallSuccess && scenarios.all { it.status == "PASS" || it.status == "NOT_RUN" || it.status == "SKIPPED" }) {
                "PASS"
            } else {
                "FAIL"
            }

            val evidence = ReadPilotEvidence(
                backendHead = config?.backendCommitSha ?: "UNKNOWN",
                manifestDigest = config?.manifestDigest ?: ReadPilotHandoffConfig.FROZEN_MANIFEST_DIGEST,
                baseUrl = config?.baseUrl ?: "https://unknown.staging",
                ttlExpiresAt = config?.ttlExpiresAt,
                overallStatus = finalOverallStatus,
                scenarios = scenarios
            )
            val rootDir = generateSequence(File(".").canonicalFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() }
                ?: File(".")
            val evidenceDir = File(rootDir, "evidence")
            evidenceDir.mkdirs()
            val evidenceFile = File(evidenceDir, "paired-read-pilot-evidence.json")
            evidence.writeTo(evidenceFile)

            assertTrue(
                "Read pilot smoke execution failed: overallSuccess=$overallSuccess, finalOverallStatus=$finalOverallStatus",
                finalOverallStatus == "PASS"
            )
        }
    }
}
