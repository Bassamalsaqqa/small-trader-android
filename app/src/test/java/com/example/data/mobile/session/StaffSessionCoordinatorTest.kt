package com.example.data.mobile.session

import android.content.Context
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.readclient.ActorProfile
import com.example.data.mobile.readclient.CompanyMembership
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class StaffSessionCoordinatorTest {

    private lateinit var tempDir: File
    private lateinit var sessionFile: File
    private lateinit var identityFile: File
    private lateinit var installationStore: InstallationIdentityStore
    private lateinit var cryptoEngine: JvmAesGcmCryptoEngine
    private lateinit var sessionStore: DefaultSecureStaffSessionStore
    private lateinit var transport: RoutingMockTransport
    private lateinit var readClient: SmallTraderReadClient
    private lateinit var testClock: TestCoordinatorClock
    private lateinit var denialCapture: AuthDenialCapture
    private lateinit var coordinator: DefaultStaffSessionCoordinator

    private val baseOrigin = "https://api.smalltrader.example"
    private val appVersion = "1.0.0-test"
    private val testPackageName = "com.aistudio.smalltrader.psdukx"

    private val testDeviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10")
    private val testChallengeId = ClientUuid.parse("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d")
    private val testNonce = "9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef"
    private val testToken = "sanctum_1092837465_mobile_token_982347109238471092384710"
    private val testActorUlid = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890")
    private val testCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val testSecondCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    private val fixturesDir by lazy { locateFixturesDir() }

    private class TestCoordinatorClock(
        var epochMs: Long = 1794500000000L, // approx Nov 2026
        var isoUtc: String = "2026-11-12T12:00:00Z"
    ) : CoordinatorClock {
        override fun nowEpochMs(): Long = epochMs
        override fun nowIsoUtc(): String = isoUtc
    }

    private class AuthDenialCapture : AuthDenialListener {
        val scopes = mutableListOf<AuthDenialScope>()
        override fun onAuthDenied(scope: AuthDenialScope) {
            scopes.add(scope)
        }
    }

    private class RoutingMockTransport : ReadClientTransport {
        val handlers = mutableMapOf<Pair<HttpMethod, String>, (TransportRequest) -> TransportResponse>()
        var globalInterceptor: ((TransportRequest) -> TransportResponse?)? = null
        val requestsReceived = mutableListOf<TransportRequest>()

        override fun execute(request: TransportRequest): TransportResponse {
            requestsReceived.add(request)
            globalInterceptor?.invoke(request)?.let { return it }

            val handler = handlers[Pair(request.method, request.path)]
                ?: return TransportResponse(statusCode = 404, body = """{"error":{"code":"NOT_FOUND","message":"Path not mapped in mock"}}""")
            return handler(request)
        }
    }

    @Before
    fun setUp() {
        tempDir = File.createTempFile("coordinator_test_", "_dir")
        tempDir.delete()
        tempDir.mkdirs()

        sessionFile = File(tempDir, SecureStaffSessionStore.SESSION_FILE_NAME)
        identityFile = File(tempDir, InstallationIdentityStore.IDENTITY_FILE_NAME)

        installationStore = InstallationIdentityStore(identityFile)
        cryptoEngine = JvmAesGcmCryptoEngine()
        sessionStore = DefaultSecureStaffSessionStore(
            sessionFile = sessionFile,
            installationStore = installationStore,
            cryptoEngine = cryptoEngine,
            packageName = testPackageName
        )

        transport = RoutingMockTransport()
        readClient = SmallTraderReadClient(transport, baseOrigin)
        testClock = TestCoordinatorClock()
        denialCapture = AuthDenialCapture()

        coordinator = DefaultStaffSessionCoordinator(
            readClient = readClient,
            sessionStore = sessionStore,
            authDenialListener = denialCapture,
            clock = testClock
        )

        setupDefaultGoldenRoutes()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun setupDefaultGoldenRoutes() {
        // Challenge route
        val challengeJson = File(fixturesDir, "golden-auth-challenge.json").readText(Charsets.UTF_8)
        val challengeRes = StrictJsonParser.parse(challengeJson).asObject().require("response")
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/challenge")] = {
            TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(challengeRes))
        }

        // Login route
        val loginJson = File(fixturesDir, "golden-auth-login.json").readText(Charsets.UTF_8)
        val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
            TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
        }

        // Logout route
        val logoutJson = File(fixturesDir, "golden-auth-logout.json").readText(Charsets.UTF_8)
        val logoutRes = StrictJsonParser.parse(logoutJson).asObject().require("response")
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/logout")] = {
            TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(logoutRes))
        }

        // Bootstrap route
        val bootstrapJson = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 200, body = bootstrapJson)
        }

        // Product catalog route
        val productJson = File(fixturesDir, "golden-product-catalog.json").readText(Charsets.UTF_8)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/products")] = {
            TransportResponse(statusCode = 200, body = productJson)
        }

        // Customer directory route
        val customerJson = File(fixturesDir, "golden-customer-directory.json").readText(Charsets.UTF_8)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/customers")] = {
            TransportResponse(statusCode = 200, body = customerJson)
        }
    }

    @Test
    fun testHappyLoginChallengeAndAutoBootstrapSingleCompany() {
        // Initial state is LoggedOut
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)

        // 1. Request challenge
        val challengeRes = coordinator.requestChallenge(appVersion)
        assertTrue(challengeRes is CoordinatorResult.Success)
        val challenge = (challengeRes as CoordinatorResult.Success).value
        assertEquals(testChallengeId, challenge.challengeId)
        assertTrue(coordinator.authState is StaffAuthState.ChallengeActive)

        // 2. Submit credentials (golden-auth-login has exactly 1 company)
        val loginRes = coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(loginRes is CoordinatorResult.Success)

        // Single company auto-bootstrapped into ReadReady
        val authState = coordinator.authState
        assertTrue(authState is StaffAuthState.ReadReady)
        val readReady = authState as StaffAuthState.ReadReady
        assertEquals(testActorUlid, readReady.session.actorUlid)
        assertEquals(testCompanyUlid, readReady.session.selectedCompanyId)
        assertEquals("cashier", readReady.session.roleLabel)

        // Proves encrypted session file was saved to disk
        assertTrue(sessionStore.hasSessionFile())
        val readDisk = sessionStore.readSession(SessionReadScope(expectedDeviceUuid = coordinator.installationId))
        assertTrue(readDisk is SessionReadResult.Success)
        val diskRecord = (readDisk as SessionReadResult.Success).session
        assertEquals(testActorUlid, diskRecord.actorUlid)
        assertEquals(testCompanyUlid, diskRecord.selectedCompanyId)
    }

    @Test
    fun testLoginChallengeAndFreshMfaSequence() {
        // Mock login returning MFA_REQUIRED on first attempt
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = { req ->
            val obj = StrictJsonParser.parse(req.body!!).asObject()
            val mfa = obj["mfa_code"]
            if (mfa is com.example.data.mobile.contract.StrictJsonNull || mfa == null) {
                TransportResponse(statusCode = 200, body = """{"status":"MFA_REQUIRED","retry":"fresh_challenge"}""")
            } else {
                val loginJson = File(fixturesDir, "golden-auth-login.json").readText(Charsets.UTF_8)
                val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
            }
        }

        coordinator.requestChallenge(appVersion)
        val initialLogin = coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(initialLogin is CoordinatorResult.Success)
        assertTrue(coordinator.authState is StaffAuthState.MfaRequired)

        // Frozen rev2: Initial challenge was consumed; attempting to login again without fresh challenge fails
        val retryWithoutChallenge = coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8",
            mfaCode = "123456"
        )
        assertTrue(retryWithoutChallenge is CoordinatorResult.Error)
        assertEquals("No active challenge found. Request challenge first.", (retryWithoutChallenge as CoordinatorResult.Error).message)

        // Retry via retryLoginWithMfa (which acquires fresh challenge automatically)
        val mfaRetry = coordinator.retryLoginWithMfa(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8",
            mfaCode = "123456"
        )
        assertTrue(mfaRetry is CoordinatorResult.Success)
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)
    }

    @Test
    fun testLoginRejectsMutualExclusionOfMfaAndRecoveryCode() {
        coordinator.requestChallenge(appVersion)
        try {
            coordinator.login(
                email = "cashier@smalltrader.example",
                password = "CorrectHorseBatteryStaple123!",
                deviceName = "Samsung Galaxy Tab A8",
                mfaCode = "123456",
                recoveryCode = "ABCDEFGH-12345678"
            )
            fail("Expected IllegalArgumentException for mutually exclusive MFA and recovery code")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mutually exclusive"))
        }

        // retryLoginWithMfa also enforces mutual exclusion
        try {
            coordinator.retryLoginWithMfa(
                email = "cashier@smalltrader.example",
                password = "CorrectHorseBatteryStaple123!",
                deviceName = "Samsung Galaxy Tab A8",
                mfaCode = "123456",
                recoveryCode = "ABCDEFGH-12345678"
            )
            fail("Expected IllegalArgumentException for mutually exclusive MFA and recovery code")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mutually exclusive"))
        }
    }

    @Test
    fun testMultipleCompanySelectionWorkflow() {
        // Return login response with TWO companies
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
            val res = mapOf(
                "token" to testToken,
                "token_type" to "Bearer",
                "expires_at" to "2026-11-17T12:00:00Z",
                "actor" to mapOf(
                    "id" to testActorUlid.value,
                    "name" to "Ahmad Cashier",
                    "email" to "cashier@smalltrader.example",
                    "role" to "cashier",
                    "password_epoch" to "epoch-20261101-v1"
                ),
                "companies" to listOf(
                    mapOf(
                        "id" to testCompanyUlid.value,
                        "name" to "Al-Amal Retail Company",
                        "base_currency" to "ILS",
                        "is_default" to true
                    ),
                    mapOf(
                        "id" to testSecondCompanyUlid.value,
                        "name" to "Al-Amal Wholesale Company",
                        "base_currency" to "ILS",
                        "is_default" to false
                    )
                )
            )
            TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(res))
        }

        coordinator.requestChallenge(appVersion)
        val loginRes = coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(loginRes is CoordinatorResult.Success)

        // Multiple companies -> CompanySelectionRequired
        assertTrue(coordinator.authState is StaffAuthState.CompanySelectionRequired)
        val selectionState = coordinator.authState as StaffAuthState.CompanySelectionRequired
        assertEquals(2, selectionState.availableCompanies.size)

        // Session is NOT yet saved to disk before company selection
        assertFalse(sessionStore.hasSessionFile())

        // Selecting unlisted company ID is rejected
        val foreignCompanyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789Z")
        val invalidSelect = coordinator.selectCompany(foreignCompanyId)
        assertTrue(invalidSelect is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.CompanySelectionRequired)

        // Select second company
        val secondBootstrapJson = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
            .replace(testCompanyUlid.value, testSecondCompanyUlid.value)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testSecondCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 200, body = secondBootstrapJson)
        }

        val selectRes = coordinator.selectCompany(testSecondCompanyUlid)
        assertTrue(selectRes is CoordinatorResult.Success)
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)
        val readReady = coordinator.authState as StaffAuthState.ReadReady
        assertEquals(testSecondCompanyUlid, readReady.session.selectedCompanyId)

        // Verified persisted on disk
        assertTrue(sessionStore.hasSessionFile())
    }

    @Test
    fun testBootstrapActorUlidMismatchFailsClosedWithLockedScopeMismatch() {
        // Return bootstrap with different staff actor ID
        val foreignActorUlid = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67899")
        val mismatchedBootstrap = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
            .replace(testActorUlid.value, foreignActorUlid.value)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 200, body = mismatchedBootstrap)
        }

        coordinator.requestChallenge(appVersion)
        val loginRes = coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(loginRes is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.ScopeMismatch)
        assertFalse(sessionStore.hasSessionFile())
    }

    @Test
    fun testBootstrapCompanyMismatchFailsClosedWithLockedScopeMismatch() {
        // Return bootstrap with different company ID
        val foreignCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789Z")
        val mismatchedBootstrap = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
            .replace(testCompanyUlid.value, foreignCompanyUlid.value)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 200, body = mismatchedBootstrap)
        }

        coordinator.requestChallenge(appVersion)
        val loginRes = coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(loginRes is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        assertFalse(sessionStore.hasSessionFile())
    }

    @Test
    fun testRestoreSessionRevalidatesWithServerBeforeGrantingReadReadiness() {
        // 1. Save valid session to disk
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        // 2. Fresh coordinator restores session
        val newCoordinator = DefaultStaffSessionCoordinator(
            readClient = readClient,
            sessionStore = sessionStore,
            authDenialListener = denialCapture,
            clock = testClock
        )
        assertEquals(StaffAuthState.LoggedOut, newCoordinator.authState)

        val restoreRes = newCoordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Success)
        assertTrue(newCoordinator.authState is StaffAuthState.ReadReady)
        val ready = newCoordinator.authState as StaffAuthState.ReadReady
        assertEquals(testCompanyUlid, ready.session.selectedCompanyId)
        assertEquals(testActorUlid, ready.session.actorUlid)
    }

    @Test
    fun testRestoreSessionWithServer401LocksLifecycleAndEmitsAuthDenialHook() {
        // Save valid session to disk
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        // Server returns 401 on bootstrap revalidation
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 401, body = """{"error":{"code":"REVOKED_TOKEN","message":"Token has been revoked"}}""")
        }

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.AuthDenied)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.AuthDenied)

        // Verifies typed AuthDenialScope was emitted for outbox locking
        assertEquals(1, denialCapture.scopes.size)
        val denial = denialCapture.scopes[0]
        assertEquals(testCompanyUlid, denial.companyId)
        assertEquals(testActorUlid, denial.actorId)
        assertEquals(coordinator.installationId, denial.deviceId)
    }

    @Test
    fun testRestoreSessionWithServer403LocksLifecycleWithMembershipDenied() {
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 403, body = """{"error":{"code":"TENANT_FORBIDDEN","message":"Staff membership is inactive"}}""")
        }

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Forbidden)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.MembershipDenied)
    }

    @Test
    fun testRestoreSessionWithTransientNetworkErrorNeverFallsBackToDemo() {
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            throw java.io.IOException("Connection timeout")
        }

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.TransientError)
        // Proves NO silent read readiness or demo fallback
        assertFalse(coordinator.authState is StaffAuthState.ReadReady)
    }

    @Test
    fun testRestoreSessionScopeMismatchOnDeviceUuidFailsClosed() {
        val otherDeviceUuid = ClientUuid.parse("99999999-9999-4999-8999-999999999999")
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = otherDeviceUuid,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.ScopeMismatch)
    }

    @Test
    fun testRestoreExpiredSessionDeniesReadReadinessAndSetsLoggedOut() {
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-01T12:00:00Z", // Past date relative to testClock
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = "2026-11-01T00:00:00Z"
        )
        sessionStore.saveSession(record)

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Error)
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)
    }

    @Test
    fun testCorruptInstallationIdentityFailsClosedWithStorageFailed() {
        identityFile.writeBytes("corrupted bytes".toByteArray(Charsets.UTF_8))

        try {
            coordinator.installationId
            fail("Expected SessionStorageException on corrupt identity")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.Corrupt)
        }
        assertTrue(coordinator.authState is StaffAuthState.StorageFailed)
    }

    @Test
    fun testKeyStoreUnavailableSurfacesTypedLockedKeyUnavailable() {
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        // Simulate missing key in keystore
        cryptoEngine.secretKey = null

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.KeyUnavailable)
    }

    @Test
    fun testKeyStoreLockedSurfacesTypedLockedKeyLocked() {
        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = coordinator.installationId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)

        // Simulate key locked or invalidated
        cryptoEngine.isLocked = true

        val restoreRes = coordinator.restoreSession()
        assertTrue(restoreRes is CoordinatorResult.Error)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.KeyLocked)
    }

    @Test
    fun testGetProductsAndCustomersSucceedInReadReady() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)

        val productsRes = coordinator.getProducts(PaginationQuery(limit = 10))
        assertTrue(productsRes is CoordinatorResult.Success)
        val catalog = (productsRes as CoordinatorResult.Success).value
        assertEquals(testCompanyUlid, catalog.companyId)
        assertTrue(catalog.products.isNotEmpty())

        val customersRes = coordinator.getCustomers(PaginationQuery(limit = 10))
        assertTrue(customersRes is CoordinatorResult.Success)
        val directory = (customersRes as CoordinatorResult.Success).value
        assertEquals(testCompanyUlid, directory.companyId)
        assertTrue(directory.customers.isNotEmpty())
    }

    @Test
    fun testGetProductsOnHttp401LocksLifecycleAndEmitsAuthDenialHook() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)

        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/products")] = {
            TransportResponse(statusCode = 401, body = """{"error":{"code":"REVOKED_TOKEN","message":"Token expired or revoked"}}""")
        }

        val res = coordinator.getProducts()
        assertTrue(res is CoordinatorResult.AuthDenied)
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.AuthDenied)

        assertEquals(1, denialCapture.scopes.size)
        val denial = denialCapture.scopes[0]
        assertEquals(testCompanyUlid, denial.companyId)
        assertEquals(testActorUlid, denial.actorId)
    }

    @Test
    fun testGetProductsOnHttp403LocksLifecycleWithMembershipDeniedWithoutRetry() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)

        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/products")] = {
            callCount.incrementAndGet()
            TransportResponse(statusCode = 403, body = """{"error":{"code":"FORBIDDEN","message":"Tenant access forbidden"}}""")
        }

        val res = coordinator.getProducts()
        assertTrue(res is CoordinatorResult.Forbidden)
        assertEquals(1, callCount.get()) // Proves zero busy retry loops
        assertTrue(coordinator.authState is StaffAuthState.Locked)
        val locked = coordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.MembershipDenied)
    }

    @Test
    fun testGetProductsWhenSessionExpiredDeniesReadAndSetsLoggedOut() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)

        // Advance clock past session expiration (golden expiry is 2026-11-17T12:00:00Z)
        testClock.epochMs = 1795500000000L // past 2026-11-17

        val res = coordinator.getProducts()
        assertTrue(res is CoordinatorResult.Error)
        assertTrue((res as CoordinatorResult.Error).message.contains("expired"))
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)
    }

    @Test
    fun testGetProductsWhenNotReadReadyReturnsError() {
        val res = coordinator.getProducts()
        assertTrue(res is CoordinatorResult.Error)
        assertEquals("Not in ReadReady state", (res as CoordinatorResult.Error).message)
    }

    @Test
    fun testSwitchCompanyAdvancesGenerationAndSavesUpdatedSession() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)
        val genBefore = coordinator.currentGeneration

        // Map second company bootstrap
        val secondBootstrapJson = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
            .replace(testCompanyUlid.value, testSecondCompanyUlid.value)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testSecondCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 200, body = secondBootstrapJson)
        }

        val switchRes = coordinator.switchCompany(testSecondCompanyUlid)
        assertTrue(switchRes is CoordinatorResult.Success)
        assertTrue(coordinator.currentGeneration > genBefore)

        val ready = coordinator.authState as StaffAuthState.ReadReady
        assertEquals(testSecondCompanyUlid, ready.session.selectedCompanyId)

        // Check disk record updated
        val diskRead = sessionStore.readSession(SessionReadScope(expectedDeviceUuid = coordinator.installationId))
        assertTrue(diskRead is SessionReadResult.Success)
        assertEquals(testSecondCompanyUlid, (diskRead as SessionReadResult.Success).session.selectedCompanyId)
    }

    @Test
    fun testLogoutImmediatelyDeniesLocalReadsRevokesRemoteTokenAndClearsCredentials() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(sessionStore.hasSessionFile())
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)

        val logoutResult = coordinator.logout()
        assertTrue(logoutResult is LogoutResult.Success)
        assertEquals(RemoteRevocationStatus.Revoked, (logoutResult as LogoutResult.Success).remoteRevocation)
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)

        // Credentials cleared from disk
        assertFalse(sessionStore.hasSessionFile())

        // Subsequent read attempts immediately denied
        val readAttempt = coordinator.getProducts()
        assertTrue(readAttempt is CoordinatorResult.Error)
        assertEquals("Not in ReadReady state", (readAttempt as CoordinatorResult.Error).message)
    }

    @Test
    fun testLogoutWithRemoteTimeoutReportsRemoteRevocationUnknownWithoutClaimingServerLogout() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )

        // Remote logout endpoint times out
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/logout")] = {
            throw java.net.SocketTimeoutException("Remote logout call timed out")
        }

        val logoutResult = coordinator.logout()
        assertTrue(logoutResult is LogoutResult.Success)
        // Reports Unknown revocation; does NOT falsely claim server logout
        assertEquals(RemoteRevocationStatus.Unknown, (logoutResult as LogoutResult.Success).remoteRevocation)
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)
        // Local credentials were still cleared
        assertFalse(sessionStore.hasSessionFile())
    }

    @Test
    fun testLogoutWithServer401ReportsAlreadyRevokedOrExpired() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )

        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/logout")] = {
            TransportResponse(statusCode = 401, body = """{"error":{"code":"UNAUTHORIZED","message":"Token already revoked"}}""")
        }

        val logoutResult = coordinator.logout()
        assertTrue(logoutResult is LogoutResult.Success)
        assertEquals(RemoteRevocationStatus.AlreadyRevokedOrExpired, (logoutResult as LogoutResult.Success).remoteRevocation)
    }

    @Test
    fun testLogoutStorageClearFailureLeavesStateLockedAndNeverReportsSuccess() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(sessionStore.hasSessionFile())

        // Injected proxy store where clearSession throws
        val failingStore = object : SecureStaffSessionStore by sessionStore {
            override fun clearSession() {
                throw SessionStorageException(SessionStorageError.IoError("Simulated file deletion refusal"))
            }
        }
        val faultCoordinator = DefaultStaffSessionCoordinator(
            readClient = readClient,
            sessionStore = failingStore,
            clock = testClock
        )
        faultCoordinator.requestChallenge(appVersion)
        faultCoordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )

        val logoutResult = faultCoordinator.logout()
        assertTrue(logoutResult is LogoutResult.StorageClearFailed)
        val failResult = logoutResult as LogoutResult.StorageClearFailed
        assertEquals(RemoteRevocationStatus.Revoked, failResult.remoteRevocation)

        // Storage clear failure MUST remain locked, never return signedout success while token usable
        assertTrue(faultCoordinator.authState is StaffAuthState.Locked)
        val locked = faultCoordinator.authState as StaffAuthState.Locked
        assertTrue(locked.reason is LockReason.StorageClearFailed)
    }

    @Test
    fun testLogoutPreservesStableInstallationIdentity() {
        val initialIdentity = coordinator.installationId
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        coordinator.logout()

        // Installation ID strictly preserved
        assertEquals(initialIdentity, coordinator.installationId)
        assertTrue(identityFile.exists())
    }

    @Test
    fun testGenerationBarrierLateLoginAfterLogoutDiscardsTokenAndPreventsResurrection() {
        val transportEntered = CountDownLatch(1)
        val allowTransportFinish = CountDownLatch(1)

        val loginJson = File(fixturesDir, "golden-auth-login.json").readText(Charsets.UTF_8)
        val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")

        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
            transportEntered.countDown()
            allowTransportFinish.await(5, TimeUnit.SECONDS)
            TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
        }

        coordinator.requestChallenge(appVersion)

        val loginThreadResult = AtomicReference<CoordinatorResult<StaffAuthState>>()
        val loginThread = Thread {
            val res = coordinator.login(
                email = "cashier@smalltrader.example",
                password = "CorrectHorseBatteryStaple123!",
                deviceName = "Samsung Galaxy Tab A8"
            )
            loginThreadResult.set(res)
        }
        loginThread.start()

        // Wait until login reaches transport (before response returns)
        assertTrue(transportEntered.await(5, TimeUnit.SECONDS))

        // Concurrent logout occurs in main thread!
        val logoutRes = coordinator.logout()
        assertTrue(logoutRes is LogoutResult.Success)
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)

        // Allow late login response to complete
        allowTransportFinish.countDown()
        loginThread.join(5000)

        // Late login was discarded!
        val threadRes = loginThreadResult.get()
        assertTrue(threadRes is CoordinatorResult.StaleOperation)

        // Token was NOT saved to disk and session was NOT resurrected
        assertFalse(sessionStore.hasSessionFile())
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)
    }

    @Test
    fun testGenerationBarrierConcurrentCompanySwitchInvalidatesOlderInFlightBootstrap() {
        val firstBootstrapEntered = CountDownLatch(1)
        val allowFirstBootstrapFinish = CountDownLatch(1)

        val bootstrapJson = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/bootstrap")] = {
            firstBootstrapEntered.countDown()
            allowFirstBootstrapFinish.await(5, TimeUnit.SECONDS)
            TransportResponse(statusCode = 200, body = bootstrapJson)
        }

        val secondBootstrapJson = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
            .replace(testCompanyUlid.value, testSecondCompanyUlid.value)
        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testSecondCompanyUlid/bootstrap")] = {
            TransportResponse(statusCode = 200, body = secondBootstrapJson)
        }

        // Return multiple companies on login
        transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
            val res = mapOf(
                "token" to testToken,
                "token_type" to "Bearer",
                "expires_at" to "2026-11-17T12:00:00Z",
                "actor" to mapOf(
                    "id" to testActorUlid.value,
                    "name" to "Ahmad Cashier",
                    "email" to "cashier@smalltrader.example",
                    "role" to "cashier",
                    "password_epoch" to "epoch-20261101-v1"
                ),
                "companies" to listOf(
                    mapOf("id" to testCompanyUlid.value, "name" to "Co A", "base_currency" to "ILS", "is_default" to true),
                    mapOf("id" to testSecondCompanyUlid.value, "name" to "Co B", "base_currency" to "ILS", "is_default" to false)
                )
            )
            TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(res))
        }

        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.CompanySelectionRequired)

        val selectCompany1Result = AtomicReference<CoordinatorResult<StaffAuthState>>()
        val thread1 = Thread {
            val res = coordinator.selectCompany(testCompanyUlid)
            selectCompany1Result.set(res)
        }
        thread1.start()

        // Wait until company 1 bootstrap starts
        assertTrue(firstBootstrapEntered.await(5, TimeUnit.SECONDS))

        // Main thread selects company 2 or switches, advancing generation
        val selectCompany2 = coordinator.selectCompany(testSecondCompanyUlid)
        assertTrue(selectCompany2 is CoordinatorResult.Success)
        assertEquals(testSecondCompanyUlid, (coordinator.authState as StaffAuthState.ReadReady).session.selectedCompanyId)

        // Now allow company 1 bootstrap to finish
        allowFirstBootstrapFinish.countDown()
        thread1.join(5000)

        // Company 1 selection was discarded as stale!
        assertTrue(selectCompany1Result.get() is CoordinatorResult.StaleOperation)

        // Current state remains company 2; no overwrite from stale thread 1
        assertEquals(testSecondCompanyUlid, (coordinator.authState as StaffAuthState.ReadReady).session.selectedCompanyId)
    }

    @Test
    fun testGenerationBarrierStaleReadResultAfterLogoutIsDiscarded() {
        coordinator.requestChallenge(appVersion)
        coordinator.login(
            email = "cashier@smalltrader.example",
            password = "CorrectHorseBatteryStaple123!",
            deviceName = "Samsung Galaxy Tab A8"
        )
        assertTrue(coordinator.authState is StaffAuthState.ReadReady)

        val readEntered = CountDownLatch(1)
        val allowReadFinish = CountDownLatch(1)
        val productJson = File(fixturesDir, "golden-product-catalog.json").readText(Charsets.UTF_8)

        transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/$testCompanyUlid/products")] = {
            readEntered.countDown()
            allowReadFinish.await(5, TimeUnit.SECONDS)
            TransportResponse(statusCode = 200, body = productJson)
        }

        val readResult = AtomicReference<CoordinatorResult<ProductCatalogResponse>>()
        val readThread = Thread {
            val res = coordinator.getProducts()
            readResult.set(res)
        }
        readThread.start()

        // Wait until read enters transport
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))

        // Logout in main thread
        coordinator.logout()
        assertEquals(StaffAuthState.LoggedOut, coordinator.authState)

        // Unblock read transport
        allowReadFinish.countDown()
        readThread.join(5000)

        // Result is discarded as StaleOperation, never leaking DTO after logout
        assertTrue(readResult.get() is CoordinatorResult.StaleOperation)
    }

    @Test
    fun testCancellationExceptionIsPreservedAndRethrown() {
        val cancellingTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                throw CancellationException("Simulated coroutine cancellation")
            }
        }
        val cancellingClient = SmallTraderReadClient(cancellingTransport, baseOrigin)
        val cancellingCoordinator = DefaultStaffSessionCoordinator(
            readClient = cancellingClient,
            sessionStore = sessionStore,
            clock = testClock
        )

        try {
            cancellingCoordinator.requestChallenge(appVersion)
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Simulated coroutine cancellation", e.message)
        }
    }

    @Test
    fun testFatalJvmErrorIsPreservedAndRethrown() {
        val fatalTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                throw VirtualMachineErrorStub("Simulated JVM OOM")
            }
        }
        val fatalClient = SmallTraderReadClient(fatalTransport, baseOrigin)
        val fatalCoordinator = DefaultStaffSessionCoordinator(
            readClient = fatalClient,
            sessionStore = sessionStore,
            clock = testClock
        )

        try {
            fatalCoordinator.requestChallenge(appVersion)
            fail("Expected VirtualMachineError to be rethrown")
        } catch (e: VirtualMachineError) {
            assertEquals("Simulated JVM OOM", e.message)
        }
    }

    private class VirtualMachineErrorStub(message: String) : VirtualMachineError(message)

    @Test
    fun testDiagnosticToStringRedactsSecretsAcrossAllStatesAndResults() {
        val loggedOut = StaffAuthState.LoggedOut.toString()
        assertEquals("StaffAuthState.LoggedOut", loggedOut)

        val challenge = StaffAuthState.ChallengeActive(testChallengeId, "2026-11-12T12:00:00Z").toString()
        assertTrue(challenge.contains(testChallengeId.toString()))

        val mfa = StaffAuthState.MfaRequired("fresh_challenge").toString()
        assertTrue(mfa.contains("fresh_challenge"))

        val actor = ActorProfile(
            id = testActorUlid,
            name = "Secret Name",
            email = "secret@example.com",
            role = "cashier",
            passwordEpoch = "secret_epoch"
        )
        val company = CompanyMembership(
            id = testCompanyUlid,
            name = "Test Co",
            baseCurrency = "ILS",
            isDefault = true
        )
        val selection = StaffAuthState.CompanySelectionRequired(actor, listOf(company)).toString()
        assertTrue(selection.contains(testActorUlid.value))
        assertFalse(selection.contains("secret@example.com"))
        assertFalse(selection.contains("secret_epoch"))

        val record = StaffSessionRecord(
            token = testToken,
            actorUlid = testActorUlid,
            deviceUuid = testDeviceId,
            passwordEpoch = "secret_epoch",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = testCompanyUlid,
            roleLabel = "cashier",
            actorName = "Secret Name",
            actorEmail = "secret@example.com",
            createdAt = testClock.nowIsoUtc()
        )
        val bootstrapJson = File(fixturesDir, "golden-bootstrap.json").readText(Charsets.UTF_8)
        val bootstrap = com.example.data.mobile.readclient.BootstrapResponse.fromStrictJson(
            StrictJsonParser.parse(bootstrapJson).asObject()
        )
        val ready = StaffAuthState.ReadReady(record, bootstrap).toString()
        assertTrue(ready.contains(testActorUlid.value))
        assertTrue(ready.contains(testCompanyUlid.value))
        assertFalse(ready.contains(testToken))

        val locked = StaffAuthState.Locked(LockReason.AuthDenied()).toString()
        assertTrue(locked.contains("AuthDenied"))

        val storage = StaffAuthState.StorageFailed("Disk full").toString()
        assertTrue(storage.contains("Disk full"))

        val denialScope = AuthDenialScope(testCompanyUlid, testActorUlid, testDeviceId, testClock.nowIsoUtc()).toString()
        assertTrue(denialScope.contains(testCompanyUlid.value))
        assertTrue(denialScope.contains(testActorUlid.value))
    }

    @Test
    fun testProductionCompositionFactoryCreatesCoordinator() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = tempDir
            override fun getPackageName(): String = testPackageName
        }

        val createdCoordinator = StaffSessionCoordinator.create(
            context = mockContext,
            baseOrigin = baseOrigin,
            allowHttpLoopback = false
        )
        assertNotNull(createdCoordinator)
        assertEquals(StaffAuthState.LoggedOut, createdCoordinator.authState)
        assertNotNull(createdCoordinator.installationId)
    }
}
