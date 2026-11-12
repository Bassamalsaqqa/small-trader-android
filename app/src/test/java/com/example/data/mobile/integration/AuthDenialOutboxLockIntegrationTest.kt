package com.example.data.mobile.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.outbox.CaptureResult
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.OutboxRepository
import com.example.data.mobile.outbox.OutboxRepositoryImpl
import com.example.data.mobile.outbox.OutboxStatus
import com.example.data.mobile.outbox.OutboxTestFixtures
import com.example.data.mobile.outbox.QueueLockedException
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.AuthDenialScope
import com.example.data.mobile.session.CoordinatorClock
import com.example.data.mobile.session.CoordinatorResult
import com.example.data.mobile.session.DefaultSecureStaffSessionStore
import com.example.data.mobile.session.DefaultStaffSessionCoordinator
import com.example.data.mobile.session.InstallationIdentityStore
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.data.mobile.session.LockReason
import com.example.data.mobile.session.SecureStaffSessionStore
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * End-to-end integration tests verifying the runtime bridge between
 * [DefaultStaffSessionCoordinator] [com.example.data.mobile.session.AuthDenialListener]
 * and [OutboxRepository.lockQueueOnAuthDenied].
 *
 * Invariants verified:
 * 1. Accepted 401 from real readclient/coordinator invokes listener to lock persistent Room database
 *    even on an initially empty queue.
 * 2. Subsequent v2 capture into the locked scope atomically produces AUTH_BLOCKED without UI activation.
 * 3. Scope-mismatched foreign records remain untouched and PENDING.
 * 4. Logout preserves queue records and persisted scope locks intact without deletion.
 * 5. Subsequent successful login or read does NOT automatically unlock the outbox queue.
 * 6. Bridge callback storage failures surface typed locked UI state without token leakage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthDenialOutboxLockIntegrationTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var dbFile: File
    private lateinit var database: OutboxDatabase
    private lateinit var outboxRepository: OutboxRepository
    private lateinit var bridge: OutboxAuthDenialBridge

    private lateinit var sessionFile: File
    private lateinit var identityFile: File
    private lateinit var installationStore: InstallationIdentityStore
    private lateinit var cryptoEngine: JvmAesGcmCryptoEngine
    private lateinit var sessionStore: DefaultSecureStaffSessionStore
    private lateinit var transport: MockIntegrationTransport
    private lateinit var readClient: SmallTraderReadClient
    private lateinit var testClock: TestIntegrationClock

    private val baseOrigin = "https://api.smalltrader.example"
    private val testPackageName = "com.aistudio.smalltrader.integration"
    private val testDeviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10")
    private val testActorUlid = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890")
    private val testCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")

    private val foreignDeviceId = ClientUuid.parse("a0000000-0000-0000-0000-000000000099")
    private val foreignActorUlid = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67899")
    private val foreignCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")

    private class TestIntegrationClock(
        var epochMs: Long = 1794312000000L,
        var isoUtc: String = "2026-11-10T12:00:00Z"
    ) : CoordinatorClock {
        override fun nowEpochMs(): Long = epochMs
        override fun nowIsoUtc(): String = isoUtc
    }

    private class MockIntegrationTransport : ReadClientTransport {
        var return401OnBootstrap: Boolean = false
        var return401OnProducts: Boolean = false

        override fun execute(request: TransportRequest): TransportResponse {
            val path = request.path
            if (path.contains("/bootstrap") && return401OnBootstrap) {
                return TransportResponse(
                    statusCode = 401,
                    headers = mapOf("content-type" to "application/json"),
                    body = """{"error":{"code":"AUTH_INVALID_TOKEN","message":"Token expired or revoked"}}"""
                )
            }
            if (path.contains("/products") && return401OnProducts) {
                return TransportResponse(
                    statusCode = 401,
                    headers = mapOf("content-type" to "application/json"),
                    body = """{"error":{"code":"AUTH_REVOKED","message":"Session revoked by administrator"}}"""
                )
            }
            if (path.contains("/bootstrap")) {
                val golden = File(OutboxTestFixtures.locateFixturesDir(), "golden-bootstrap.json").readText(Charsets.UTF_8)
                return TransportResponse(
                    statusCode = 200,
                    headers = mapOf("content-type" to "application/json"),
                    body = golden
                )
            }
            if (path.contains("/products")) {
                val golden = File(OutboxTestFixtures.locateFixturesDir(), "golden-product-catalog.json").readText(Charsets.UTF_8)
                return TransportResponse(
                    statusCode = 200,
                    headers = mapOf("content-type" to "application/json"),
                    body = golden
                )
            }
            return TransportResponse(
                statusCode = 404,
                headers = mapOf("content-type" to "application/json"),
                body = """{"error":{"code":"NOT_FOUND","message":"Not Found"}}"""
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "integration_test_${System.currentTimeMillis()}").apply { mkdirs() }
        dbFile = File(tempDir, "small_trader_outbox_integration.db")
        if (dbFile.exists()) dbFile.delete()

        // Real Room database built on disk file
        database = OutboxDatabase.buildDatabase(context, name = dbFile.absolutePath, allowMainThreadQueries = true)
        outboxRepository = OutboxRepositoryImpl(database)
        bridge = OutboxAuthDenialBridge(outboxRepository)

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

        transport = MockIntegrationTransport()
        readClient = SmallTraderReadClient(transport = transport, baseOrigin = baseOrigin)
        testClock = TestIntegrationClock()
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private fun createCoordinator(listener: com.example.data.mobile.session.AuthDenialListener? = bridge): DefaultStaffSessionCoordinator {
        return DefaultStaffSessionCoordinator(
            readClient = readClient,
            sessionStore = sessionStore,
            authDenialListener = listener,
            clock = testClock
        )
    }

    private fun persistValidSession(
        token: String = "sanctum_token_integration_001",
        actorUlid: ServerUlid = testActorUlid,
        companyUlid: ServerUlid = testCompanyUlid,
        deviceId: ClientUuid = sessionStore.installationId
    ) {
        val record = StaffSessionRecord(
            token = token,
            actorUlid = actorUlid,
            deviceUuid = deviceId,
            passwordEpoch = "epoch-20261101-v1",
            expiresAt = "2026-11-20T12:00:00Z",
            selectedCompanyId = companyUlid,
            roleLabel = "cashier",
            actorName = "Ahmad Cashier",
            actorEmail = "ahmad@example.com",
            createdAt = testClock.nowIsoUtc()
        )
        sessionStore.saveSession(record)
    }

    @Test
    fun testAccepted401CompositionThroughListenerLocksEmptyRoomQueueThenV2CaptureProducesAuthBlocked() = runBlocking(Dispatchers.IO) {
        val coordinator = createCoordinator(bridge)
        val deviceId = coordinator.installationId

        // 1. Initial State: Queue is EMPTY on disk
        val initialBatch = database.outboxDao().findClaimCandidateIds(
            companyId = testCompanyUlid.value,
            actorId = testActorUlid.value,
            deviceId = deviceId.value,
            nowMillis = testClock.epochMs,
            limit = 10
        )
        assertTrue("Queue must start completely empty", initialBatch.isEmpty())
        assertFalse("Scope must not be locked initially", outboxRepository.isQueueLocked(testCompanyUlid, testActorUlid, deviceId))

        // Also insert a foreign scope record into the database to verify tenant isolation
        val foreignEvent = OutboxTestFixtures.createSampleEventV2(
            companyId = foreignCompanyUlid,
            actorId = foreignActorUlid,
            deviceId = foreignDeviceId,
            localReceiptNumber = "FOREIGN-REC-001"
        )
        val foreignResult = outboxRepository.captureSaleV2(
            companyId = foreignCompanyUlid,
            actorId = foreignActorUlid,
            deviceId = foreignDeviceId,
            currencyMinorUnits = 2,
            event = foreignEvent
        )
        assertTrue("Foreign capture must succeed", foreignResult is CaptureResult.Created)
        val foreignRecordBefore = outboxRepository.getRecord(foreignCompanyUlid, foreignActorUlid, foreignDeviceId, foreignEvent.clientEventId)
        assertNotNull("Foreign record must exist", foreignRecordBefore)
        assertEquals("Foreign record must be PENDING", OutboxStatus.PENDING.wireValue, foreignRecordBefore!!.deliveryState.status)

        // 2. Setup active session and trigger accepted 401 via real coordinator
        persistValidSession(deviceId = deviceId)
        transport.return401OnBootstrap = true

        // restoreSession encounters 401 on bootstrap token revalidation
        val restoreResult = coordinator.restoreSession()

        // Verify coordinator transitioned into Locked state
        val authState = coordinator.authState
        assertTrue("Coordinator must transition to Locked", authState is StaffAuthState.Locked)
        val lockReason = (authState as StaffAuthState.Locked).reason
        assertTrue("Lock reason must be AuthDenied", lockReason is LockReason.AuthDenied)

        // 3. Verify Room disk scope lock persisted even on EMPTY queue
        val isLocked = outboxRepository.isQueueLocked(testCompanyUlid, testActorUlid, deviceId)
        assertTrue("Queue scope must be locked in Room disk storage even with 0 prior events", isLocked)

        // 4. Scope-mismatched foreign records must remain untouched
        val foreignRecordAfter = outboxRepository.getRecord(foreignCompanyUlid, foreignActorUlid, foreignDeviceId, foreignEvent.clientEventId)
        assertNotNull(foreignRecordAfter)
        assertEquals("Foreign record must remain PENDING", OutboxStatus.PENDING.wireValue, foreignRecordAfter!!.deliveryState.status)
        assertFalse("Foreign scope must NOT be locked", outboxRepository.isQueueLocked(foreignCompanyUlid, foreignActorUlid, foreignDeviceId))

        // 5. Subsequent v2 capture into this locked scope produces AUTH_BLOCKED
        val v2Event = OutboxTestFixtures.createSampleEventV2(
            companyId = testCompanyUlid,
            actorId = testActorUlid,
            deviceId = deviceId,
            localReceiptNumber = "LOCKED-SCOPE-REC-001"
        )
        val captureResult = outboxRepository.captureSaleV2(
            companyId = testCompanyUlid,
            actorId = testActorUlid,
            deviceId = deviceId,
            currencyMinorUnits = 2,
            event = v2Event
        )
        assertTrue("Capture result must be Created", captureResult is CaptureResult.Created)
        val createdRecord = (captureResult as CaptureResult.Created).record
        assertEquals(
            "Event captured in locked scope must immediately be AUTH_BLOCKED",
            OutboxStatus.AUTH_BLOCKED.wireValue,
            createdRecord.deliveryState.status
        )
        assertEquals(
            "Payload bytes must be preserved intact",
            v2Event.computePayloadDigest().value,
            createdRecord.event.payloadDigest
        )

        // Attempting to claim batch throws QueueLockedException
        assertThrows(QueueLockedException::class.java) {
            outboxRepository.claimNextBatch(
                companyId = testCompanyUlid,
                actorId = testActorUlid,
                deviceId = deviceId,
                batchSize = 10,
                leaseDurationMillis = 60_000L
            )
        }

        // 6. Logout preserves queues and scope locks
        coordinator.logout()
        assertTrue("Queue scope must REMAIN locked after logout", outboxRepository.isQueueLocked(testCompanyUlid, testActorUlid, deviceId))
        val persistedEvent = outboxRepository.getRecord(testCompanyUlid, testActorUlid, deviceId, v2Event.clientEventId)
        assertNotNull("Outbox event must NOT be deleted by logout", persistedEvent)
        assertEquals(OutboxStatus.AUTH_BLOCKED.wireValue, persistedEvent!!.deliveryState.status)

        // 7. Subsequent successful read/bootstrap does NOT automatically unlock the queue
        transport.return401OnBootstrap = false
        transport.return401OnProducts = false
        persistValidSession(token = "new_sanctum_token_after_relogin", deviceId = deviceId)
        val newCoordinator = createCoordinator(bridge)
        val newRestoreResult = newCoordinator.restoreSession()
        assertTrue("New session restore succeeds", newRestoreResult is CoordinatorResult.Success)

        // Invariant: NEVER autounlock on login/read success
        assertTrue(
            "Subsequent login/read success MUST NOT unlock the outbox queue; remains locked until reviewed reauth resolution",
            outboxRepository.isQueueLocked(testCompanyUlid, testActorUlid, deviceId)
        )
    }

    @Test
    fun testCallbackFailureSurfacesTypedLockedBehaviorWithoutLeakingTokens() = runBlocking(Dispatchers.IO) {
        val initialCoord = createCoordinator(bridge)
        val deviceId = initialCoord.installationId
        persistValidSession(deviceId = deviceId)
        transport.return401OnProducts = true

        val failingBridge = OutboxAuthDenialBridge {
            throw android.database.sqlite.SQLiteDiskIOException("private-provider-secret")
        }

        val failingCoordinator = DefaultStaffSessionCoordinator(
            readClient = readClient,
            sessionStore = sessionStore,
            authDenialListener = failingBridge,
            clock = testClock
        )

        // First restore valid session
        transport.return401OnBootstrap = false
        val restoreResult = failingCoordinator.restoreSession()
        assertTrue("Initial restore succeeds", restoreResult is CoordinatorResult.Success)

        // Now trigger product fetch returning 401
        val productsResult = failingCoordinator.getProducts()

        assertTrue(productsResult is CoordinatorResult.Error)
        assertFalse(productsResult.toString().contains("private-provider-secret"))

        // Coordinator catches listener exception and transitions authState to Locked
        assertTrue("Coordinator must be Locked", failingCoordinator.authState is StaffAuthState.Locked)
        val reason = (failingCoordinator.authState as StaffAuthState.Locked).reason
        assertTrue("Lock reason must be AuthDenied", reason is LockReason.AuthDenied)

        // Verification: No plaintext credentials or secrets leaked
        assertFalse(
            "Session file must not contain raw plaintext token",
            sessionFile.readText(Charsets.ISO_8859_1).contains("sanctum_token_integration_001")
        )
    }
    @Test
    fun testMainThreadMisuseRejectsBeforeRepositoryInitialization() {
        assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
        var opened = false
        val lazyBridge = OutboxAuthDenialBridge {
            opened = true
            outboxRepository
        }
        assertThrows(IllegalStateException::class.java) {
            lazyBridge.onAuthDenied(AuthDenialScope(testCompanyUlid, testActorUlid, testDeviceId, testClock.isoUtc))
        }
        assertFalse(opened)
        assertFalse(outboxRepository.isQueueLocked(testCompanyUlid, testActorUlid, testDeviceId))
    }

}
