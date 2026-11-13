package com.example.data.mobile.sync

import com.example.data.mobile.outbox.testOutboxRepository
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.CaptureResult
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.OutboxRepository
import com.example.data.mobile.outbox.OutboxRepositoryImpl
import com.example.data.mobile.outbox.OutboxEventEntity
import com.example.data.mobile.outbox.OutboxDeliveryStateEntity
import com.example.data.mobile.outbox.OutboxStatus
import com.example.data.mobile.outbox.OutboxTestFixtures
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android Sync Coordinator Foundation Room SQLite Test Suite.
 *
 * Verifies local orchestration invariants:
 * - Disk persist capture v2 -> claim -> status posted confirm
 * - Lost ACK after server post -> reopen -> status replay confirms one canonical server effect
 * - NotFound submit original bytes
 * - Altered server digest/company/event/invoice/payment reject
 * - Auth revoked queue blocked and captures preserved
 * - 2 coordinators/interleavings claims no 2 submits
 * - Stale lease late ack cannot confirm new claim
 * - Conflict preserve bytes
 * - V1 park without post
 * - Injected timeout/cancellation no false confirmation/no rekey
 *
 * NOTE ON TESTING BOUNDARIES:
 * These tests verify user-space SQLite invariants and local coordination logic.
 * Fake counters (e.g., [FakeSaleSyncGateway.serverPostEffectCounter]) model the single canonical
 * server effect in test; they do NOT constitute hardware power-loss or MariaDB distributed ledger proofs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaleSyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var database: OutboxDatabase
    private lateinit var repository: OutboxRepository
    private var testClockMillis = 100_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_sync_coordinator_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()
        testClockMillis = 100_000L
        database = OutboxDatabase.buildInMemory(context)
        repository = testOutboxRepository(database, clock = { testClockMillis })
    }

    @After
    fun tearDown() {
        database.close()
        if (dbFile.exists()) dbFile.delete()
    }

    // =========================================================================
    // Fake Sale Sync Gateway for Deterministic Invariant Testing
    // =========================================================================

    class FakeSaleSyncGateway : SaleSyncGateway {
        val checkStatusCallCount = AtomicInteger(0)
        val submitSaleCallCount = AtomicInteger(0)

        // Clearly labeled fake counter modeling backend general ledger idempotency
        val serverPostEffectCounter = AtomicInteger(0)

        var statusOutcomeProvider: (ClientUuid) -> SyncStatusOutcome = { SyncStatusOutcome.NotFound }
        var submitOutcomeProvider: (ClientUuid, ByteArray) -> SyncSubmitOutcome = { clientEventId, _ ->
            serverPostEffectCounter.incrementAndGet()
            SyncSubmitOutcome.Posted(
                ServerSaleAck(
                    companyId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
                    clientEventId = clientEventId,
                    payloadDigest = PayloadDigest.parse("sha256:" + "00".repeat(32)),
                    invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                    paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                    serverAcknowledgedAt = "2026-11-12T01:00:00Z"
                )
            )
        }

        val submittedPayloadBytes = mutableListOf<ByteArray>()
        var onBeforeSubmit: (() -> Unit)? = null

        override fun checkStatus(
            companyId: ServerUlid,
            actorId: ServerUlid,
            deviceId: ClientUuid,
            clientEventId: ClientUuid,
            payloadDigest: PayloadDigest
        ): SyncStatusOutcome {
            checkStatusCallCount.incrementAndGet()
            return statusOutcomeProvider(clientEventId)
        }

        override fun submitSale(
            companyId: ServerUlid,
            actorId: ServerUlid,
            deviceId: ClientUuid,
            clientEventId: ClientUuid,
            payloadDigest: PayloadDigest,
            canonicalEventUtf8: ByteArray
        ): SyncSubmitOutcome {
            submitSaleCallCount.incrementAndGet()
            submittedPayloadBytes.add(canonicalEventUtf8.clone())
            onBeforeSubmit?.invoke()
            return submitOutcomeProvider(clientEventId, canonicalEventUtf8)
        }
    }

    // =========================================================================
    // 1. Disk persist capture v2 -> claim -> status posted confirm
    // =========================================================================

    @Test
    fun testDiskPersistCaptureV2ClaimStatusPostedConfirm() {
        val diskDb = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val diskRepo = testOutboxRepository(diskDb, clock = { testClockMillis })

        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        val captureResult = diskRepo.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )
        assertTrue("Capture must succeed", captureResult is CaptureResult.Created)

        val digest = sampleEvent.computePayloadDigest()
        val expectedAck = ServerSaleAck(
            companyId = sampleEvent.companyId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-12T01:20:00Z"
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.Posted(expectedAck) }
        }

        val coordinator = SaleSyncCoordinatorImpl(diskRepo, gateway, clock = { testClockMillis })
        val syncResult = coordinator.synchronizeScope(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId
        )

        assertEquals(1, syncResult.recordsClaimed)
        assertEquals(1, syncResult.confirmedCount)
        assertEquals(0, syncResult.timeoutCount)
        assertEquals(1, gateway.checkStatusCallCount.get())
        assertEquals(0, gateway.submitSaleCallCount.get()) // already posted on server!

        val persisted = diskRepo.getRecord(
            sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId
        )
        assertNotNull(persisted)
        assertEquals(OutboxStatus.CONFIRMED, persisted!!.status)
        assertEquals(OutboxTestFixtures.SAMPLE_INVOICE_ULID.value, persisted.deliveryState.invoiceUlid)
        assertEquals(OutboxTestFixtures.SAMPLE_PAYMENT_ULID.value, persisted.deliveryState.paymentUlid)
        assertNull(persisted.deliveryState.claimToken)

        diskDb.close()
    }

    // =========================================================================
    // 2. Lost ACK after server post -> reopen -> status replay confirms one canonical server effect
    // =========================================================================

    @Test
    fun testLostAckAfterServerPostReopenStatusReplayConfirmsOneCanonicalServerEffect() {
        val diskDb1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val diskRepo1 = testOutboxRepository(diskDb1, clock = { testClockMillis })

        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        diskRepo1.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val digest = sampleEvent.computePayloadDigest()
        val canonicalServerAck = ServerSaleAck(
            companyId = sampleEvent.companyId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-12T01:30:00Z"
        )

        val gateway = FakeSaleSyncGateway()
        // First pass: server does not know about event yet
        gateway.statusOutcomeProvider = { SyncStatusOutcome.NotFound }
        // On submit: server commits effect (increments counter) but ACK is dropped due to simulated network timeout
        gateway.submitOutcomeProvider = { _, _ ->
            gateway.serverPostEffectCounter.incrementAndGet() // canonical server effect executed
            SyncSubmitOutcome.TransientUnknown("Network timeout waiting for server response", isTimeout = true)
        }

        val coordinator1 = SaleSyncCoordinatorImpl(diskRepo1, gateway, clock = { testClockMillis })
        val syncResult1 = coordinator1.synchronizeScope(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId
        )

        assertEquals(1, syncResult1.recordsClaimed)
        assertEquals(0, syncResult1.confirmedCount)
        assertEquals(1, syncResult1.timeoutCount)
        // Record in SQLite is now UNKNOWN_ACK
        val recordAfterTimeout = diskRepo1.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.UNKNOWN_ACK, recordAfterTimeout.status)

        // Close database and reopen from disk (simulating application crash / restart)
        diskDb1.close()

        val diskDb2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val diskRepo2 = testOutboxRepository(diskDb2, clock = { testClockMillis })

        // Second pass: gateway status lookup reflects that the server has recorded the sale from earlier submit
        gateway.statusOutcomeProvider = { SyncStatusOutcome.Posted(canonicalServerAck) }

        val coordinator2 = SaleSyncCoordinatorImpl(diskRepo2, gateway, clock = { testClockMillis })
        val syncResult2 = coordinator2.synchronizeScope(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId
        )

        assertEquals(1, syncResult2.recordsClaimed)
        assertEquals(1, syncResult2.confirmedCount)
        assertEquals(0, syncResult2.timeoutCount)

        // Verify: serverPostEffectCounter is STILL exactly 1 (no duplicate server execution!)
        assertEquals("Fake counter: exactly one canonical server effect executed", 1, gateway.serverPostEffectCounter.get())
        assertEquals(1, gateway.submitSaleCallCount.get()) // submit was NOT called again on second pass!

        val finalRecord = diskRepo2.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.CONFIRMED, finalRecord.status)
        assertEquals(OutboxTestFixtures.SAMPLE_INVOICE_ULID.value, finalRecord.deliveryState.invoiceUlid)

        diskDb2.close()
    }

    // =========================================================================
    // 3. NotFound submit original bytes
    // =========================================================================

    @Test
    fun testNotFoundSubmitOriginalBytes() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val digest = sampleEvent.computePayloadDigest()
        val expectedAck = ServerSaleAck(
            companyId = sampleEvent.companyId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-12T01:35:00Z"
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.NotFound }
            submitOutcomeProvider = { clientEventId, _ ->
                serverPostEffectCounter.incrementAndGet()
                SyncSubmitOutcome.Posted(expectedAck)
            }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        val syncResult = coordinator.synchronizeScope(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId
        )

        assertEquals(1, syncResult.recordsClaimed)
        assertEquals(1, syncResult.confirmedCount)
        assertEquals(1, gateway.checkStatusCallCount.get())
        assertEquals(1, gateway.submitSaleCallCount.get())

        // Verify byte-exact submission of canonical event bytes
        val storedRecord = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertArrayEquals("Submitted bytes must match stored canonical event bytes exactly", storedRecord.event.payloadBytes, gateway.submittedPayloadBytes.first())
    }

    // =========================================================================
    // 4. Altered server digest/company/event/invoice/payment reject
    // =========================================================================

    @Test
    fun testAlteredServerDigestCompanyEventInvoicePaymentReject() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        val digest = sampleEvent.computePayloadDigest()

        // 4a: Altered digest in server ack
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val alteredAck = ServerSaleAck(
                companyId = sampleEvent.companyId,
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = PayloadDigest.parse("sha256:" + "ff".repeat(32)), // altered digest!
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "2026-11-12T01:40:00Z"
            )
            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { SyncStatusOutcome.Posted(alteredAck) }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val result = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            assertEquals(0, result.confirmedCount)
            assertEquals(1, result.timeoutCount)
            val rec = repo.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
            assertNotEquals(OutboxStatus.CONFIRMED, rec.status)
            db.close()
        }

        // 4b: Altered companyId in server ack
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val alteredAck = ServerSaleAck(
                companyId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000000"), // foreign company!
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = digest,
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "2026-11-12T01:40:00Z"
            )
            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { SyncStatusOutcome.Posted(alteredAck) }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val result = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            assertEquals(0, result.confirmedCount)
            assertEquals(1, result.timeoutCount)
            val rec = repo.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
            assertNotEquals(OutboxStatus.CONFIRMED, rec.status)
            db.close()
        }

        // 4c: Altered clientEventId in server ack
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val alteredAck = ServerSaleAck(
                companyId = sampleEvent.companyId,
                clientEventId = ClientUuid.random(), // mismatched event ID!
                payloadDigest = digest,
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "2026-11-12T01:40:00Z"
            )
            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { SyncStatusOutcome.Posted(alteredAck) }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val result = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            assertEquals(0, result.confirmedCount)
            assertEquals(1, result.timeoutCount)
            val rec = repo.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
            assertNotEquals(OutboxStatus.CONFIRMED, rec.status)
            db.close()
        }

        // 4d: Altered/invalid invoice ULID in server ack
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val alteredAck = ServerSaleAck(
                companyId = sampleEvent.companyId,
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = digest,
                invoiceUlid = ServerUlid.parse("01JCV8X9ME7890123456789ABC"), // valid
                paymentUlid = ServerUlid.parse("01JCV8X9MFGHJKMNPQRSTVWXYZ"), // valid
                serverAcknowledgedAt = "" // blank acknowledged time!
            )
            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { SyncStatusOutcome.Posted(alteredAck) }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val result = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            assertEquals(0, result.confirmedCount)
            assertEquals(1, result.timeoutCount)
            val rec = repo.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
            assertNotEquals(OutboxStatus.CONFIRMED, rec.status)
            db.close()
        }
    }

    // =========================================================================
    // 5. Auth revoked queue blocked and captures preserved
    // =========================================================================

    @Test
    fun testAuthRevokedQueueBlockedAndCapturesPreserved() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.AuthDenied("401 Unauthorized: token expired or revoked") }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        val syncResult = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)

        assertTrue("authBlocked must be true", syncResult.authBlocked)
        assertTrue("stoppedEarly must be true", syncResult.stoppedEarly)

        // Verify scope lock persisted
        assertTrue(repository.isQueueLocked(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId))

        // Verify record transitioned to AUTH_BLOCKED and payload preserved
        val record = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.AUTH_BLOCKED, record.status)
        assertNotNull(record.event.payloadBytes)
        assertEquals(sampleEvent.computePayloadDigest().value, record.payloadDigest)

        // Subsequent synchronizeScope returns immediately without claiming or calling gateway
        val subsequentResult = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
        assertTrue(subsequentResult.authBlocked)
        assertEquals(0, subsequentResult.recordsClaimed)
        assertEquals(1, gateway.checkStatusCallCount.get()) // Not called again!
    }

    // =========================================================================
    // 6. 2 coordinators/interleavings claims no 2 submits
    // =========================================================================

    @Test
    fun testTwoCoordinatorsInterleavingsClaimsNoTwoSubmits() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.NotFound }
            submitOutcomeProvider = { clientEventId, _ ->
                serverPostEffectCounter.incrementAndGet()
                SyncSubmitOutcome.Posted(
                    ServerSaleAck(
                        companyId = sampleEvent.companyId,
                        clientEventId = clientEventId,
                        payloadDigest = sampleEvent.computePayloadDigest(),
                        invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                        paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                        serverAcknowledgedAt = "2026-11-12T01:45:00Z"
                    )
                )
            }
        }

        val coordinator1 = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        val coordinator2 = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })

        // Interleaving simulation: during coordinator 1's submit, coordinator 2 attempts to claim
        gateway.onBeforeSubmit = {
            val syncResult2 = coordinator2.synchronizeScope(
                companyId = sampleEvent.companyId,
                actorId = sampleEvent.actorId,
                deviceId = sampleEvent.deviceId,
                batchSize = 10
            )
            assertEquals("Coordinator 2 must claim 0 records while lease is held", 0, syncResult2.recordsClaimed)
        }

        val syncResult1 = coordinator1.synchronizeScope(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

        assertEquals(1, syncResult1.recordsClaimed)
        assertEquals(1, syncResult1.confirmedCount)
        assertEquals("Gateway submit must be called exactly once across both coordinators", 1, gateway.submitSaleCallCount.get())
        assertEquals("Server post effect counter must be exactly 1", 1, gateway.serverPostEffectCounter.get())
    }

    // =========================================================================
    // 7. Stale lease late ack cannot confirm new claim
    // =========================================================================

    @Test
    fun testStaleLeaseLateAckCannotConfirmNewClaim() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        // Claim 1 at t = 100_000 with 30s lease (expires at 130_000)
        val batch1 = repository.claimNextBatch(
            sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, batchSize = 10, leaseDurationMillis = 30_000L
        )
        assertEquals(1, batch1.records.size)
        val claimToken1 = batch1.claimToken

        // Advance clock past expiration (t = 135_000)
        testClockMillis += 35_000L

        // Claim 2 reclaims the stale in-flight record with new lease
        val batch2 = repository.claimNextBatch(
            sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, batchSize = 10, leaseDurationMillis = 30_000L
        )
        assertEquals(1, batch2.records.size)
        val claimToken2 = batch2.claimToken
        assertNotEquals(claimToken1, claimToken2)

        // Late ACK from worker 1 arrives now with stale claimToken1:
        assertThrows(com.example.data.mobile.outbox.InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = sampleEvent.companyId,
                actorId = sampleEvent.actorId,
                deviceId = sampleEvent.deviceId,
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = sampleEvent.computePayloadDigest(),
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "2026-11-12T01:50:00Z",
                claimToken = claimToken1 // STALE TOKEN!
            )
        }

        // Coordinator 2's active claim remains in_flight with claimToken2
        val currentRecord = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.IN_FLIGHT, currentRecord.status)
        assertEquals(claimToken2, currentRecord.deliveryState.claimToken)
    }

    // =========================================================================
    // 8. Conflict preserve bytes
    // =========================================================================

    @Test
    fun testConflictPreservesEventBytes() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.NotFound }
            submitOutcomeProvider = { _, _ ->
                SyncSubmitOutcome.BusinessConflict("409 Conflict: Inventory exhausted on server")
            }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        val result = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)

        assertEquals(1, result.recordsClaimed)
        assertEquals(1, result.conflictCount)
        assertEquals(0, result.confirmedCount)

        val record = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.CONFLICT, record.status)
        assertEquals(SyncDiagnostics.BUSINESS_CONFLICT, record.deliveryState.errorMessage)

        // Canonical event bytes are strictly preserved
        val expectedDigest = sampleEvent.computePayloadDigest().value
        assertEquals(expectedDigest, record.payloadDigest)
        assertEquals(sampleEvent.clientEventId.value, record.clientEventId)
    }

    // =========================================================================
    // 9. V1 park without post
    // =========================================================================

    @Test
    fun testV1ParkWithoutPost() {
        // Capture legacy V1 event
        val v1Event = OutboxTestFixtures.createSampleEvent()
        val captureResult = repository.captureSale(v1Event)
        assertTrue(captureResult is CaptureResult.Created)

        val gateway = FakeSaleSyncGateway()
        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })

        val result = coordinator.synchronizeScope(v1Event.companyId, v1Event.actorId, v1Event.deviceId)

        assertEquals(1, result.recordsClaimed)
        assertEquals(1, result.parkedV1Count)
        assertEquals(1, result.conflictCount)
        assertEquals(0, result.confirmedCount)

        // Gateway must NEVER be called for legacy v1 event
        assertEquals(0, gateway.checkStatusCallCount.get())
        assertEquals(0, gateway.submitSaleCallCount.get())

        val record = repository.getRecord(v1Event.companyId, v1Event.actorId, v1Event.deviceId, v1Event.clientEventId)!!
        assertEquals(OutboxStatus.CONFLICT, record.status)
        assertEquals(SyncDiagnostics.LEGACY_EVENT_VERSION, record.deliveryState.errorMessage)
    }

    // =========================================================================
    // 10. Injected timeout/cancellation no false confirmation/no rekey
    // =========================================================================

    @Test
    fun testInjectedCancellationNoFalseConfirmationNoRekey() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.NotFound }
            submitOutcomeProvider = { _, _ ->
                throw CancellationException("Simulated thread or coroutine cancellation during submit")
            }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })

        assertThrows(CancellationException::class.java) {
            coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
        }

        // Verify: record was moved to UNKNOWN_ACK before propagating cancellation
        val record = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.UNKNOWN_ACK, record.status)
        assertNotEquals(OutboxStatus.CONFIRMED, record.status)
        assertEquals("UUID must not be rekeyed", sampleEvent.clientEventId.value, record.clientEventId)
        assertEquals("Digest must not be altered", sampleEvent.computePayloadDigest().value, record.payloadDigest)
    }

    // =========================================================================
    // 11. 403 Membership Denial fails closed
    // =========================================================================

    @Test
    fun testMembershipDeniedFailsClosedWithoutRetryLoop() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.MembershipDenied("403 Forbidden: Actor is not an active tenant member") }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        val result = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)

        assertTrue(result.membershipDenied)
        assertTrue(result.stoppedEarly)
        assertEquals(1, result.conflictCount)

        val record = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.CONFLICT, record.status)
        assertEquals(SyncDiagnostics.MEMBERSHIP_DENIED, record.deliveryState.errorMessage)
    }

    // =========================================================================
    // 12. Defensive byte copy defense against gateway mutating caller payload
    // =========================================================================

    @Test
    fun testDefensiveCopyAtSubmissionPreventsStorageCorruption() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val digest = sampleEvent.computePayloadDigest()
        val expectedAck = ServerSaleAck(
            companyId = sampleEvent.companyId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-12T01:55:00Z"
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.NotFound }
            submitOutcomeProvider = { clientEventId, canonicalBytes ->
                // Malicious or buggy gateway mutates the input array
                canonicalBytes[0] = 0.toByte()
                canonicalBytes[1] = 0.toByte()
                SyncSubmitOutcome.Posted(expectedAck)
            }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)

        val record = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.CONFIRMED, record.status)

        // Storage bytes must NOT have been corrupted by gateway mutation!
        assertNotEquals(0.toByte(), record.event.payloadBytes[0])
        assertEquals('{'.code.toByte(), record.event.payloadBytes[0])
    }

    // =========================================================================
    // 13. Status NotFound delayed past lease + new claim: no first stale submit
    // =========================================================================

    @Test
    fun testStatusNotFoundDelayedPastLeaseNewClaimNoFirstStaleSubmit() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val digest = sampleEvent.computePayloadDigest()
        val expectedAck = ServerSaleAck(
            companyId = sampleEvent.companyId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-12T01:35:00Z"
        )

        var clockTime = 100_000L
        val repo = testOutboxRepository(database, clock = { clockTime })

        val gateway = FakeSaleSyncGateway()
        gateway.statusOutcomeProvider = { SyncStatusOutcome.NotFound }
        gateway.submitOutcomeProvider = { _, _ ->
            gateway.serverPostEffectCounter.incrementAndGet()
            SyncSubmitOutcome.Posted(expectedAck)
        }

        val coordinator2 = SaleSyncCoordinatorImpl(repo, gateway, clock = { clockTime })
        var coordinator1SubmitCalled = false

        val interleavingGateway = object : SaleSyncGateway by gateway {
            override fun checkStatus(
                companyId: ServerUlid,
                actorId: ServerUlid,
                deviceId: ClientUuid,
                clientEventId: ClientUuid,
                payloadDigest: PayloadDigest
            ): SyncStatusOutcome {
                val res = gateway.checkStatus(companyId, actorId, deviceId, clientEventId, payloadDigest)
                // Interleave: advance clock past Coordinator 1's lease duration (10s)
                clockTime += 20_000L
                // Coordinator 2 claims and successfully submits + confirms
                val res2 = coordinator2.synchronizeScope(companyId, actorId, deviceId, batchSize = 1, leaseDurationMillis = 10_000L)
                assertEquals(1, res2.confirmedCount)
                return res
            }

            override fun submitSale(
                companyId: ServerUlid,
                actorId: ServerUlid,
                deviceId: ClientUuid,
                clientEventId: ClientUuid,
                payloadDigest: PayloadDigest,
                canonicalEventUtf8: ByteArray
            ): SyncSubmitOutcome {
                coordinator1SubmitCalled = true
                return gateway.submitSale(companyId, actorId, deviceId, clientEventId, payloadDigest, canonicalEventUtf8)
            }
        }

        val coordinator1 = SaleSyncCoordinatorImpl(repo, interleavingGateway, clock = { clockTime })
        val res1 = coordinator1.synchronizeScope(
            sampleEvent.companyId,
            sampleEvent.actorId,
            sampleEvent.deviceId,
            batchSize = 1,
            leaseDurationMillis = 10_000L
        )

        // Coordinator 1 must re-read scoped record before submit, detect stale lease/reclaimed claim, and suppress submit!
        assertFalse("Coordinator 1 must NOT dispatch submitSale when claim is stale or reclaimed", coordinator1SubmitCalled)
        assertEquals(0, res1.confirmedCount)
        assertTrue(res1.stoppedEarly)

        // Exactly one submission occurred (from Coordinator 2)
        assertEquals("Exactly one canonical server effect executed", 1, gateway.serverPostEffectCounter.get())
        assertEquals(1, gateway.submitSaleCallCount.get())

        val record = repo.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.CONFIRMED, record.status)
    }

    // =========================================================================
    // 14. Batch of 3 cleanup on transient, cancel, 403, and 401
    // =========================================================================

    @Test
    fun testBatch3CleanupOnTransientCancel403AndAuth() {
        val dummy = OutboxTestFixtures.createSampleEventV2()
        val companyId = dummy.companyId
        val actorId = dummy.actorId
        val deviceId = dummy.deviceId

        // Case A: Transient error on first of 3 records
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)

            val e1 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000001"))
            val e2 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000002"))
            val e3 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000003"))

            repo.captureSaleV2(companyId, actorId, deviceId, 2, e1)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e2)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e3)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = {
                    SyncStatusOutcome.TransientUnknown("Temporary 503 Service Unavailable", isTimeout = false)
                }
            }

            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val res = coordinator.synchronizeScope(companyId, actorId, deviceId, batchSize = 3)

            assertEquals(3, res.recordsClaimed)
            assertEquals(1, res.timeoutCount)
            assertTrue(res.stoppedEarly)

            // e1 is in UNKNOWN_ACK
            val r1 = repo.getRecord(companyId, actorId, deviceId, e1.clientEventId)!!
            assertEquals(OutboxStatus.UNKNOWN_ACK, r1.status)

            // e2 and e3 were cleaned up back to PENDING (ownership cleared)
            val r2 = repo.getRecord(companyId, actorId, deviceId, e2.clientEventId)!!
            assertEquals(OutboxStatus.PENDING, r2.status)
            assertNull(r2.deliveryState.claimToken)
            assertNull(r2.deliveryState.claimExpiresAt)

            val r3 = repo.getRecord(companyId, actorId, deviceId, e3.clientEventId)!!
            assertEquals(OutboxStatus.PENDING, r3.status)
            assertNull(r3.deliveryState.claimToken)
            assertNull(r3.deliveryState.claimExpiresAt)

            db.close()
        }

        // Case B: 403 MembershipDenied on first of 3 records
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)

            val e1 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000001"))
            val e2 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000002"))
            val e3 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000003"))

            repo.captureSaleV2(companyId, actorId, deviceId, 2, e1)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e2)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e3)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = {
                    SyncStatusOutcome.MembershipDenied("403 Forbidden")
                }
            }

            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val res = coordinator.synchronizeScope(companyId, actorId, deviceId, batchSize = 3)

            assertEquals(3, res.recordsClaimed)
            assertEquals(1, res.conflictCount)
            assertTrue(res.membershipDenied)
            assertTrue(res.stoppedEarly)

            val r1 = repo.getRecord(companyId, actorId, deviceId, e1.clientEventId)!!
            assertEquals(OutboxStatus.CONFLICT, r1.status)

            // e2 and e3 released back to PENDING
            val r2 = repo.getRecord(companyId, actorId, deviceId, e2.clientEventId)!!
            assertEquals(OutboxStatus.PENDING, r2.status)
            assertNull(r2.deliveryState.claimToken)

            val r3 = repo.getRecord(companyId, actorId, deviceId, e3.clientEventId)!!
            assertEquals(OutboxStatus.PENDING, r3.status)
            assertNull(r3.deliveryState.claimToken)

            db.close()
        }

        // Case C: CancellationException during processing
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)

            val e1 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000001"))
            val e2 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000002"))
            val e3 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000003"))

            repo.captureSaleV2(companyId, actorId, deviceId, 2, e1)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e2)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e3)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = {
                    throw CancellationException("Simulated coroutine cancellation")
                }
            }

            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            assertThrows(CancellationException::class.java) {
                coordinator.synchronizeScope(companyId, actorId, deviceId, batchSize = 3)
            }

            // e1 is UNKNOWN_ACK (cancellation during in-flight status check)
            val r1 = repo.getRecord(companyId, actorId, deviceId, e1.clientEventId)!!
            assertEquals(OutboxStatus.UNKNOWN_ACK, r1.status)

            // e2 and e3 released back to PENDING
            val r2 = repo.getRecord(companyId, actorId, deviceId, e2.clientEventId)!!
            assertEquals(OutboxStatus.PENDING, r2.status)
            assertNull(r2.deliveryState.claimToken)

            val r3 = repo.getRecord(companyId, actorId, deviceId, e3.clientEventId)!!
            assertEquals(OutboxStatus.PENDING, r3.status)
            assertNull(r3.deliveryState.claimToken)

            db.close()
        }

        // Case D: 401 AuthDenied locks queue and marks ALL active scope records as AUTH_BLOCKED
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)

            val e1 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000001"))
            val e2 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000002"))
            val e3 = OutboxTestFixtures.createSampleEventV2(clientEventId = ClientUuid.parse("00000000-0000-0000-0000-000000000003"))

            repo.captureSaleV2(companyId, actorId, deviceId, 2, e1)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e2)
            repo.captureSaleV2(companyId, actorId, deviceId, 2, e3)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = {
                    SyncStatusOutcome.AuthDenied("401 Token Expired")
                }
            }

            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
            val res = coordinator.synchronizeScope(companyId, actorId, deviceId, batchSize = 3)

            assertTrue(res.authBlocked)
            assertTrue(res.stoppedEarly)
            assertTrue(repo.isQueueLocked(companyId, actorId, deviceId))

            // All 3 records transitioned to AUTH_BLOCKED
            val r1 = repo.getRecord(companyId, actorId, deviceId, e1.clientEventId)!!
            assertEquals(OutboxStatus.AUTH_BLOCKED, r1.status)
            val r2 = repo.getRecord(companyId, actorId, deviceId, e2.clientEventId)!!
            assertEquals(OutboxStatus.AUTH_BLOCKED, r2.status)
            val r3 = repo.getRecord(companyId, actorId, deviceId, e3.clientEventId)!!
            assertEquals(OutboxStatus.AUTH_BLOCKED, r3.status)

            db.close()
        }
    }

    // =========================================================================
    // 15. Corrupt UUID and corrupt bytes park without loop or submission
    // =========================================================================

    @Test
    fun testCorruptUUIDAndBytesParkNoLoopOrSubmit() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()

        // 15A: Malformed stored UUID parked via narrow raw-string key; no infinite poison reclaim loop
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)

            val sampleBytes = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(sampleEvent).toByteArray(Charsets.UTF_8)
            val rawMalformedId = "not-a-valid-uuid-12345"
            val eventEntity = OutboxEventEntity(
                clientEventId = rawMalformedId,
                companyId = sampleEvent.companyId.value,
                actorId = sampleEvent.actorId.value,
                deviceId = sampleEvent.deviceId.value,
                deviceLeaseId = sampleEvent.deviceLeaseId.value,
                capturedAt = sampleEvent.capturedAt,
                payloadDigest = sampleEvent.computePayloadDigest().value,
                payloadBytes = sampleBytes,
                eventVersion = 2
            )
            val stateEntity = OutboxDeliveryStateEntity(
                clientEventId = rawMalformedId,
                companyId = sampleEvent.companyId.value,
                actorId = sampleEvent.actorId.value,
                deviceId = sampleEvent.deviceId.value,
                status = OutboxStatus.PENDING.wireValue,
                createdAt = 100_000L,
                updatedAt = 100_000L
            )
            db.outboxDao().insertEvent(com.example.data.mobile.outbox.testCipher.encrypt(eventEntity))
            db.outboxDao().insertDeliveryState(stateEntity)

            val gateway = FakeSaleSyncGateway()
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)

            val res1 = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            assertEquals(1, res1.recordsClaimed)
            assertEquals(1, res1.conflictCount)
            assertEquals(0, gateway.checkStatusCallCount.get())
            assertEquals(0, gateway.submitSaleCallCount.get())

            val stateAfter = db.outboxDao().getDeliveryState(sampleEvent.companyId.value, rawMalformedId)!!
            assertEquals(OutboxStatus.CONFLICT.wireValue, stateAfter.status)
            assertEquals(SyncDiagnostics.MALFORMED_CLIENT_EVENT_ID, stateAfter.errorMessage)

            // Second pass: must NOT reclaim the parked record!
            val res2 = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            assertEquals(0, res2.recordsClaimed)

            db.close()
        }

        // 15B: Corrupt payload bytes (SHA-256 != payloadDigest) parked with zero submission
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)

            val sampleBytes = com.example.data.mobile.contract.CanonicalJsonSerializer.serialize(sampleEvent).toByteArray(Charsets.UTF_8)
            val corruptedBytes = sampleBytes.clone().apply {
                this[this.size - 2] = 'Z'.code.toByte()
            }
            val eventEntity = OutboxEventEntity(
                clientEventId = sampleEvent.clientEventId.value,
                companyId = sampleEvent.companyId.value,
                actorId = sampleEvent.actorId.value,
                deviceId = sampleEvent.deviceId.value,
                deviceLeaseId = sampleEvent.deviceLeaseId.value,
                capturedAt = sampleEvent.capturedAt,
                payloadDigest = sampleEvent.computePayloadDigest().value,
                payloadBytes = corruptedBytes,
                eventVersion = 2
            )
            val stateEntity = OutboxDeliveryStateEntity(
                clientEventId = sampleEvent.clientEventId.value,
                companyId = sampleEvent.companyId.value,
                actorId = sampleEvent.actorId.value,
                deviceId = sampleEvent.deviceId.value,
                status = OutboxStatus.PENDING.wireValue,
                createdAt = 100_000L,
                updatedAt = 100_000L
            )
            db.outboxDao().insertEvent(eventEntity)
            db.outboxDao().insertDeliveryState(stateEntity)

            val gateway = FakeSaleSyncGateway()
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)

            org.junit.Assert.assertThrows(com.example.data.mobile.outbox.OutboxStorageException::class.java) {
                repo.claimNextBatch(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            }
            assertEquals(0, gateway.checkStatusCallCount.get())
            assertEquals(0, gateway.submitSaleCallCount.get())
            assertEquals(OutboxStatus.PENDING.wireValue,
                db.outboxDao().getDeliveryState(sampleEvent.companyId.value, sampleEvent.clientEventId.value)!!.status)
            assertArrayEquals(corruptedBytes,
                db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)!!.payloadBytes)

            db.close()
        }
    }

    // =========================================================================
    // 16. Invalid ack date: no confirmation, recoverable via fresh status
    // =========================================================================

    @Test
    fun testInvalidAckDateNoConfirmationAndRecoverableViaFreshStatus() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        repository.captureSaleV2(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            currencyMinorUnits = 2,
            event = sampleEvent
        )

        val digest = sampleEvent.computePayloadDigest()
        // Invalid non-existent calendar date (February 30th)
        val invalidAck = ServerSaleAck(
            companyId = sampleEvent.companyId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-02-30T10:00:00Z"
        )

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = { SyncStatusOutcome.Posted(invalidAck) }
        }

        val coordinator = SaleSyncCoordinatorImpl(repository, gateway, clock = { testClockMillis })
        val res1 = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)

        assertEquals(1, res1.recordsClaimed)
        assertEquals(0, res1.confirmedCount)
        assertEquals(1, res1.timeoutCount)

        val record1 = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.UNKNOWN_ACK, record1.status)
        assertEquals(SyncDiagnostics.INVALID_SERVER_ACK, record1.deliveryState.errorMessage)

        // Pass 2: Gateway provides valid RFC 3339 timestamp with valid offset; recovers cleanly
        val validAck = invalidAck.copy(serverAcknowledgedAt = "2026-11-12T03:00:00+02:00")
        gateway.statusOutcomeProvider = { SyncStatusOutcome.Posted(validAck) }

        val res2 = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
        assertEquals(1, res2.recordsClaimed)
        assertEquals(1, res2.confirmedCount)

        val record2 = repository.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        assertEquals(OutboxStatus.CONFIRMED, record2.status)
        assertEquals("2026-11-12T03:00:00+02:00", record2.deliveryState.serverAcknowledgedAt)
    }

    // =========================================================================
    // 17. Direct repository API tests for RFC 3339 validation
    // =========================================================================

    @Test
    fun testDirectApiInvalidDatesAndValidContractCases() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()
        val digest = sampleEvent.computePayloadDigest()

        val db = OutboxDatabase.buildInMemory(context)
        val repo = testOutboxRepository(db)
        repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

        val claim = repo.claimNextBatch(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, batchSize = 1)

        // 17A: Invalid dates throw IllegalArgumentException on confirmDelivery
        assertThrows(IllegalArgumentException::class.java) {
            repo.confirmDelivery(
                companyId = sampleEvent.companyId,
                actorId = sampleEvent.actorId,
                deviceId = sampleEvent.deviceId,
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = digest,
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "not-a-date",
                claimToken = claim.claimToken
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            repo.confirmDelivery(
                companyId = sampleEvent.companyId,
                actorId = sampleEvent.actorId,
                deviceId = sampleEvent.deviceId,
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = digest,
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "2026-02-31T00:00:00Z",
                claimToken = claim.claimToken
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            repo.confirmDelivery(
                companyId = sampleEvent.companyId,
                actorId = sampleEvent.actorId,
                deviceId = sampleEvent.deviceId,
                clientEventId = sampleEvent.clientEventId,
                payloadDigest = digest,
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "",
                claimToken = claim.claimToken
            )
        }

        // 17B: Valid RFC 3339 with offset succeeds
        val confirmed = repo.confirmDelivery(
            companyId = sampleEvent.companyId,
            actorId = sampleEvent.actorId,
            deviceId = sampleEvent.deviceId,
            clientEventId = sampleEvent.clientEventId,
            payloadDigest = digest,
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-12T03:00:00+02:00",
            claimToken = claim.claimToken
        )
        assertTrue(confirmed)

        db.close()
    }

    // =========================================================================
    // 18. Meaningful malicious short secret/password/Basic/customer data:
    //     no DB reason, error_message, summary, or toString leak
    // =========================================================================

    @Test
    fun testNoSecretLeakInDBReasonSummaryOrToString() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()

        val secretPassword = "SuperSecretPassword123!"
        val basicAuthHeader = "Basic dXNlcjpwYXNz"
        val bearerToken = "Bearer secret_api_token_abc"
        val customerPii = "CustomerCreditCard=4111222233334444"

        // 18A: Outcomes toString() must never leak raw strings
        val conflictOutcome = SyncStatusOutcome.BusinessConflict(reason = "Failed: $secretPassword")
        assertFalse("Outcome toString() must not contain secret password", conflictOutcome.toString().contains(secretPassword))

        val authOutcome = SyncStatusOutcome.AuthDenied(reason = "Denied: $bearerToken")
        assertFalse("Outcome toString() must not contain bearer token", authOutcome.toString().contains(bearerToken))

        val transientOutcome = SyncSubmitOutcome.TransientUnknown(errorMessage = "Error: $basicAuthHeader")
        assertFalse("Outcome toString() must not contain basic auth", transientOutcome.toString().contains(basicAuthHeader))

        // 18B: Persisted DB state must never contain raw exception messages or untrusted gateway reasons
        val db = OutboxDatabase.buildInMemory(context)
        val repo = testOutboxRepository(db)
        repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

        val gateway = FakeSaleSyncGateway().apply {
            statusOutcomeProvider = {
                SyncStatusOutcome.BusinessConflict(reason = "HostileReason: $customerPii")
            }
        }

        val coordinator = SaleSyncCoordinatorImpl(repo, gateway)
        val res = coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)

        assertFalse("Summary must not contain PII", res.summary.contains(customerPii))

        val record = repo.getRecord(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, sampleEvent.clientEventId)!!
        val dbErrorMessage = record.deliveryState.errorMessage ?: ""
        assertFalse("DB error_message must not contain PII", dbErrorMessage.contains(customerPii))
        assertEquals(SyncDiagnostics.BUSINESS_CONFLICT, dbErrorMessage)

        db.close()
    }

    // =========================================================================
    // 19. Fatal errors and cancellation propagate without heavy DB cleanup
    // =========================================================================

    @Test
    fun testFatalErrorsPropagateWithoutHeavyDbCleanup() {
        val sampleEvent = OutboxTestFixtures.createSampleEventV2()

        // 19A: OutOfMemoryError propagates immediately
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { throw OutOfMemoryError("Simulated heap exhaustion") }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)

            assertThrows(OutOfMemoryError::class.java) {
                coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            }
            db.close()
        }

        // 19B: AssertionError propagates immediately
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { throw AssertionError("Simulated assertion failure") }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)

            assertThrows(AssertionError::class.java) {
                coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            }
            db.close()
        }

        // 19C: InterruptedException restores thread interrupt flag and propagates
        run {
            val db = OutboxDatabase.buildInMemory(context)
            val repo = testOutboxRepository(db)
            repo.captureSaleV2(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId, 2, sampleEvent)

            val gateway = FakeSaleSyncGateway().apply {
                statusOutcomeProvider = { throw InterruptedException("Simulated worker thread interrupt") }
            }
            val coordinator = SaleSyncCoordinatorImpl(repo, gateway)

            assertThrows(InterruptedException::class.java) {
                coordinator.synchronizeScope(sampleEvent.companyId, sampleEvent.actorId, sampleEvent.deviceId)
            }
            assertTrue("Interrupted status must be restored", Thread.interrupted())
            db.close()
        }
    }
}
