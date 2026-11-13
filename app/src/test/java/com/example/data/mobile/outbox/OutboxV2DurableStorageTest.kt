package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.contract.v2.OfflineSaleIntakeRequestV2
import com.example.data.mobile.contract.v2.OfflineSaleLineV2
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.v2.OfflineSalePaymentV2
import java.nio.charset.StandardCharsets
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
import java.math.BigDecimal

/**
 * Android Outbox Foundation V2 Durable Storage Test Suite.
 *
 * Enforces ACID transaction guarantees, disk reopen byte preservation, cross-version conflict
 * semantics, scope isolation, and money decimal validation conforming to Contract Revision 3 (API v2).
 *
 * NOTE ON TESTING BOUNDARIES:
 * These tests provide local SQLite file-backing and ACID transaction proof in user-space.
 * They do NOT constitute hardware power-loss proof, kernel-level process termination proof,
 * or backend ledger settlement proof (which belongs authoritatively to the central Laravel backend).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxV2DurableStorageTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var database: OutboxDatabase
    private lateinit var repository: OutboxRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_outbox_v2_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()
        database = OutboxDatabase.buildInMemory(context)
        repository = testOutboxRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    // =========================================================================
    // 1. Disk Close / Reopen Exact V2 Bytes, Version, and Digest Verification
    // =========================================================================

    @Test
    fun testDiskCloseReopenExactV2BytesVersionAndDigest() {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo1 = testOutboxRepository(db1)

        val goldenReq = OutboxTestFixtures.loadGoldenIntakeRequestV2()
        val event = goldenReq.event
        val expectedDigest = goldenReq.payloadDigest.value

        val captureResult = repo1.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = goldenReq
        )

        assertTrue("Initial v2 capture must be Created", captureResult is CaptureResult.Created)
        val initialRecord = (captureResult as CaptureResult.Created).record
        assertEquals(event.clientEventId.value, initialRecord.clientEventId)
        assertEquals(2, initialRecord.eventVersion)
        assertEquals(2, initialRecord.event.eventVersion)
        assertEquals(expectedDigest, initialRecord.payloadDigest)
        assertEquals(OutboxStatus.PENDING, initialRecord.status)
        assertEquals(0, initialRecord.deliveryState.attemptCount)

        // Close connection to simulate app shutdown
        db1.close()

        assertTrue("SQLite file must exist on physical disk", dbFile.exists())
        assertTrue("SQLite file size must be non-zero", dbFile.length() > 0)

        // Reopen database from exact same disk file
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo2 = testOutboxRepository(db2)

        val recoveredRecord = repo2.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull("Record must persist across database connection close and reopen", recoveredRecord)
        assertEquals(event.clientEventId.value, recoveredRecord!!.clientEventId)
        assertEquals(event.companyId.value, recoveredRecord.companyId)
        assertEquals(event.actorId.value, recoveredRecord.actorId)
        assertEquals(event.deviceId.value, recoveredRecord.deviceId)
        assertEquals(2, recoveredRecord.eventVersion)
        assertEquals(2, recoveredRecord.event.eventVersion)
        assertEquals(expectedDigest, recoveredRecord.payloadDigest)
        assertEquals(OutboxStatus.PENDING, recoveredRecord.status)
        assertEquals(0, recoveredRecord.deliveryState.attemptCount)

        // Verify exact byte preservation of immutable payload
        assertArrayEquals(
            "V2 payload bytes must be byte-for-byte identical after disk reopen",
            initialRecord.payloadBytes,
            recoveredRecord.payloadBytes
        )

        db2.close()
    }

    // =========================================================================
    // 2. Duplicate V2 Capture Replay Idempotency
    // =========================================================================

    @Test
    fun testDuplicateV2CaptureReturnsDuplicateWithoutMutation() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)

        // Capture 1: Initial creation
        val res1 = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = intakeReq
        )
        assertTrue("First capture must be Created", res1 is CaptureResult.Created)
        val initialRecord = (res1 as CaptureResult.Created).record

        // Capture 2: Duplicate lost-ACK retry with identical intake request
        val res2 = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = intakeReq
        )
        assertTrue("Second capture must be Duplicate", res2 is CaptureResult.Duplicate)
        val duplicateRecord = (res2 as CaptureResult.Duplicate).record

        assertEquals(initialRecord.clientEventId, duplicateRecord.clientEventId)
        assertEquals(initialRecord.payloadDigest, duplicateRecord.payloadDigest)
        assertEquals(2, duplicateRecord.eventVersion)
        assertEquals(OutboxStatus.PENDING, duplicateRecord.status)
        assertArrayEquals(initialRecord.payloadBytes, duplicateRecord.payloadBytes)

        // Verify only 1 row in database
        val allRecords = repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId)
        assertEquals(1, allRecords.size)
    }

    // =========================================================================
    // 3. Cross-Version Same-ID Conflict Preservation: V1 vs V2
    // =========================================================================

    @Test
    fun testCrossVersionSameIdV1V2ConflictPreservesOriginalV1() {
        val sharedEventId = ClientUuid.random()
        val companyId = ServerUlid.parse("01J9Z5K3MN0000000000000001")
        val actorId = ServerUlid.parse("01J9Z5K3MN0000000000000002")
        val deviceId = ClientUuid.parse("a0000000-0000-0000-0000-000000000001")

        // Capture 1: v1 event
        val v1Event = OutboxTestFixtures.createSampleEvent(
            clientEventId = sharedEventId,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId
        )
        val res1 = repository.captureSale(v1Event)
        assertTrue(res1 is CaptureResult.Created)
        val v1Record = (res1 as CaptureResult.Created).record
        val v1Digest = v1Record.payloadDigest
        val v1Bytes = v1Record.payloadBytes

        // Capture 2: v2 event with the exact same client_event_id under same company scope
        val v2Event = OutboxTestFixtures.createSampleEventV2(
            clientEventId = sharedEventId,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId
        )
        val v2IntakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(v2Event)

        val res2 = repository.captureSaleV2(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = 2,
            intakeRequest = v2IntakeReq
        )
        assertTrue("Cross-version same-ID capture must result in Conflict", res2 is CaptureResult.Conflict)
        val conflict = res2 as CaptureResult.Conflict
        assertEquals(sharedEventId.value, conflict.clientEventId)
        assertEquals(v1Digest, conflict.existingDigest)
        assertEquals(v2Event.computePayloadDigest().value, conflict.incomingDigest)

        // Verify stored event payload in DB remains the original v1 event (NO replacement or overwrite)
        val storedRecord = repository.getRecord(companyId, actorId, deviceId, sharedEventId)
        assertNotNull(storedRecord)
        assertEquals(1, storedRecord!!.eventVersion)
        assertEquals(v1Digest, storedRecord.payloadDigest)
        assertArrayEquals(v1Bytes, storedRecord.payloadBytes)
        assertEquals(OutboxStatus.CONFLICT, storedRecord.status)
    }

    @Test
    fun testCrossVersionSameIdV2V1ConflictPreservesOriginalV2() {
        val sharedEventId = ClientUuid.random()
        val companyId = ServerUlid.parse("01J9Z5K3MN0000000000000001")
        val actorId = ServerUlid.parse("01J9Z5K3MN0000000000000002")
        val deviceId = ClientUuid.parse("a0000000-0000-0000-0000-000000000001")

        // Capture 1: v2 event
        val v2Event = OutboxTestFixtures.createSampleEventV2(
            clientEventId = sharedEventId,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId
        )
        val v2IntakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(v2Event)
        val res1 = repository.captureSaleV2(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = 2,
            intakeRequest = v2IntakeReq
        )
        assertTrue(res1 is CaptureResult.Created)
        val v2Record = (res1 as CaptureResult.Created).record
        val v2Digest = v2Record.payloadDigest
        val v2Bytes = v2Record.payloadBytes

        // Capture 2: v1 event with the same client_event_id
        val v1Event = OutboxTestFixtures.createSampleEvent(
            clientEventId = sharedEventId,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId
        )
        val res2 = repository.captureSale(v1Event)
        assertTrue("Attempt to overwrite v2 with v1 must result in Conflict", res2 is CaptureResult.Conflict)
        val conflict = res2 as CaptureResult.Conflict
        assertEquals(sharedEventId.value, conflict.clientEventId)
        assertEquals(v2Digest, conflict.existingDigest)
        assertEquals(v1Event.computePayloadDigest().value, conflict.incomingDigest)

        // Verify stored event payload in DB remains original v2 event
        val storedRecord = repository.getRecord(companyId, actorId, deviceId, sharedEventId)
        assertNotNull(storedRecord)
        assertEquals(2, storedRecord!!.eventVersion)
        assertEquals(v2Digest, storedRecord.payloadDigest)
        assertArrayEquals(v2Bytes, storedRecord.payloadBytes)
        assertEquals(OutboxStatus.CONFLICT, storedRecord.status)
    }

    // =========================================================================
    // 4. Terminal Confirmed Immutability: Altered Capture Cannot Demote
    // =========================================================================

    @Test
    fun testTerminalConfirmedRecordCannotBeDemotedByAlteredV2Capture() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)

        repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = intakeReq
        )

        // Claim batch
        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId)
        assertEquals(1, batch.records.size)

        // Confirm delivery
        val invoiceUlid = ServerUlid.parse("01J9Z5K3MN0000000000000088")
        val paymentUlid = ServerUlid.parse("01J9Z5K3MN0000000000000099")
        val confirmed = repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = intakeReq.payloadDigest,
            invoiceUlid = invoiceUlid,
            paymentUlid = paymentUlid,
            serverAcknowledgedAt = "2026-11-11T14:32:00Z",
            claimToken = batch.claimToken
        )
        assertTrue(confirmed)

        val recordBeforeAlteredCapture = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.CONFIRMED, recordBeforeAlteredCapture!!.status)

        // Attempt altered capture with same UUID but modified unit price
        val packagingUlid = ServerUlid.parse("01J9Z5K3MN0000000000000020")
        val alteredLine = OfflineSaleLineV2(
            productId = ServerUlid.parse("01J9Z5K3MN0000000000000010"),
            productUnitId = packagingUlid,
            unitId = packagingUlid,
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("50.000000"),
            taxRateId = ServerUlid.parse("01J9Z5K3MN0000000000000030"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxInclusive = true,
            taxAmount = MoneyAmount.fromNonnegativeString("13.800000"),
            lineTotal = MoneyAmount.fromPositiveString("100.000000")
        )
        val alteredPayment = OfflineSalePaymentV2(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = event.cashAccountId,
            amountTendered = MoneyAmount.fromNonnegativeString("100.000000"),
            amountSettled = MoneyAmount.fromPositiveString("100.000000"),
            changeReturned = MoneyAmount.fromNonnegativeString("0.000000")
        )
        val alteredEvent = event.copy(lines = listOf(alteredLine), payment = alteredPayment)
        val alteredReq = OutboxTestFixtures.createSampleIntakeRequestV2(alteredEvent)

        val alteredResult = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = alteredReq
        )

        assertTrue(alteredResult is CaptureResult.Conflict)
        val recordAfterAlteredCapture = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(recordAfterAlteredCapture)
        // CRITICAL INVARIANT: status remains CONFIRMED (never demoted to CONFLICT)
        assertEquals("CONFIRMED record must NEVER be demoted to CONFLICT", OutboxStatus.CONFIRMED, recordAfterAlteredCapture!!.status)
        assertEquals(invoiceUlid.value, recordAfterAlteredCapture.deliveryState.invoiceUlid)
        assertEquals(paymentUlid.value, recordAfterAlteredCapture.deliveryState.paymentUlid)
    }

    // =========================================================================
    // 5. Cross-Scope Zero Data Leakage Generic Rejection
    // =========================================================================

    @Test
    fun testCrossScopeZeroLeakGenericRejection() {
        val sharedEventId = ClientUuid.random()
        val companyId = ServerUlid.parse("01J9Z5K3MN0000000000000001")
        val actor1 = ServerUlid.parse("01J9Z5K3MN0000000000000002")
        val device1 = ClientUuid.parse("a0000000-0000-0000-0000-000000000001")

        val actor2 = ServerUlid.parse("01J9Z5K3MN0000000000000099")
        val device2 = ClientUuid.parse("a0000000-0000-0000-0000-000000000099")

        // Scope 1 captures event
        val event1 = OutboxTestFixtures.createSampleEventV2(
            clientEventId = sharedEventId,
            companyId = companyId,
            actorId = actor1,
            deviceId = device1
        )
        val res1 = repository.captureSaleV2(
            companyId = companyId,
            actorId = actor1,
            deviceId = device1,
            currencyMinorUnits = 2,
            intakeRequest = OutboxTestFixtures.createSampleIntakeRequestV2(event1)
        )
        assertTrue(res1 is CaptureResult.Created)

        // Scope 2 attempts to capture same clientEventId
        val event2 = OutboxTestFixtures.createSampleEventV2(
            clientEventId = sharedEventId,
            companyId = companyId,
            actorId = actor2,
            deviceId = device2
        )
        val res2 = repository.captureSaleV2(
            companyId = companyId,
            actorId = actor2,
            deviceId = device2,
            currencyMinorUnits = 2,
            intakeRequest = OutboxTestFixtures.createSampleIntakeRequestV2(event2)
        )

        assertTrue("Foreign scope collision must result in ScopeRejected", res2 is CaptureResult.ScopeRejected)
        val scopeRejected = res2 as CaptureResult.ScopeRejected
        assertEquals(sharedEventId.value, scopeRejected.clientEventId)
        assertTrue("Generic message with zero leakage", scopeRejected.message.contains("already exists under another actor or device"))
        assertFalse("Must not leak actor 1 ULID in generic error", scopeRejected.message.contains(actor1.value))
        assertFalse("Must not leak device 1 UUID in generic error", scopeRejected.message.contains(device1.value))

        // Querying from Scope 2 returns null (zero state leakage)
        val queryResult = repository.getRecord(companyId, actor2, device2, sharedEventId)
        assertNull("Query from foreign scope must return null", queryResult)
    }

    // =========================================================================
    // 6. Cross-Tenant Top-Level UUID Independence
    // =========================================================================

    @Test
    fun testTopLevelSameUuidDifferentCompanyIndependent() {
        val sharedUuid = ClientUuid.random()
        val companyA = ServerUlid.parse("01J9Z5K3MN0000000000000001")
        val companyB = ServerUlid.parse("01J9Z5K3MN0000000000000002")

        val eventA = OutboxTestFixtures.createSampleEventV2(
            clientEventId = sharedUuid,
            companyId = companyA
        )
        val eventB = OutboxTestFixtures.createSampleEventV2(
            clientEventId = sharedUuid,
            companyId = companyB
        )

        val resA = repository.captureSaleV2(
            companyId = companyA,
            actorId = eventA.actorId,
            deviceId = eventA.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = OutboxTestFixtures.createSampleIntakeRequestV2(eventA)
        )
        val resB = repository.captureSaleV2(
            companyId = companyB,
            actorId = eventB.actorId,
            deviceId = eventB.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = OutboxTestFixtures.createSampleIntakeRequestV2(eventB)
        )

        assertTrue("Tenant A capture must succeed", resA is CaptureResult.Created)
        assertTrue("Tenant B capture must succeed independently with same clientEventId", resB is CaptureResult.Created)

        val recordA = repository.getRecord(companyA, eventA.actorId, eventA.deviceId, sharedUuid)
        val recordB = repository.getRecord(companyB, eventB.actorId, eventB.deviceId, sharedUuid)

        assertNotNull(recordA)
        assertNotNull(recordB)
        assertEquals(companyA.value, recordA!!.companyId)
        assertEquals(companyB.value, recordB!!.companyId)
    }

    // =========================================================================
    // 7. Auth Denial Persistent Lock on Empty Queue & Atomically Blocked V2 Capture
    // =========================================================================

    @Test
    fun testAuthLockEmptyQueueAtomicallyBlocksNewV2Capture() {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo1 = testOutboxRepository(db1)

        val companyId = ServerUlid.parse("01J9Z5K3MN0000000000000001")
        val actorId = ServerUlid.parse("01J9Z5K3MN0000000000000002")
        val deviceId = ClientUuid.parse("a0000000-0000-0000-0000-000000000001")

        // 1. Lock scope when queue is empty (0 records)
        repo1.lockQueueOnAuthDenied(companyId, actorId, deviceId, "HTTP 401 Unauthorized")
        assertTrue(repo1.isQueueLocked(companyId, actorId, deviceId))

        // 2. Reopen database: persistent lock must survive restart
        db1.close()
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo2 = testOutboxRepository(db2)
        assertTrue("Scope lock must persist across disk reopen", repo2.isQueueLocked(companyId, actorId, deviceId))

        // 3. Capture v2 event while queue is locked
        val event = OutboxTestFixtures.createSampleEventV2(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId
        )
        val captureResult = repo2.captureSaleV2(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            currencyMinorUnits = 2,
            intakeRequest = OutboxTestFixtures.createSampleIntakeRequestV2(event)
        )

        assertTrue(captureResult is CaptureResult.Created)
        val record = (captureResult as CaptureResult.Created).record
        // Initial status must be AUTH_BLOCKED
        assertEquals(OutboxStatus.AUTH_BLOCKED, record.status)
        // CRITICAL: capture must NOT unlock auth
        assertTrue("Capture must NOT unlock the queue", repo2.isQueueLocked(companyId, actorId, deviceId))

        // Cannot claim batch while locked
        assertThrows(QueueLockedException::class.java) {
            repo2.claimNextBatch(companyId, actorId, deviceId)
        }

        // Manager re-authenticates and unlocks queue
        val unlockedCount = repo2.unlockQueueAfterReauth(companyId, actorId, deviceId)
        assertEquals(1, unlockedCount)
        assertFalse(repo2.isQueueLocked(companyId, actorId, deviceId))

        val unlockedRecord = repo2.getRecord(companyId, actorId, deviceId, event.clientEventId)
        assertEquals(OutboxStatus.PENDING, unlockedRecord!!.status)

        db2.close()
    }

    // =========================================================================
    // 8. Injected Transaction Failure Full Rollback: No Orphan State
    // =========================================================================

    @Test
    fun testInjectedTransactionFailureFullRollbackNoOrphanState() {
        val event = OutboxTestFixtures.createSampleEventV2()

        // Test atomic rollback via database.runInTransaction
        assertThrows(RuntimeException::class.java) {
            database.runInTransaction {
                val eventEntity = OutboxEventEntity(
                    clientEventId = event.clientEventId.value,
                    companyId = event.companyId.value,
                    actorId = event.actorId.value,
                    deviceId = event.deviceId.value,
                    deviceLeaseId = event.deviceLeaseId.value,
                    capturedAt = event.capturedAt,
                    payloadDigest = event.computePayloadDigest().value,
                    payloadBytes = CanonicalJsonSerializer.serialize(event).toByteArray(Charsets.UTF_8),
                    eventVersion = 2
                )
                database.outboxDao().insertEvent(eventEntity)
                // Injected failure before delivery state insertion
                throw RuntimeException("Injected transaction failure!")
            }
        }

        // Verify zero orphan rows in outbox_events or outbox_delivery_states
        val storedEvent = database.outboxDao().getEvent(event.companyId.value, event.clientEventId.value)
        assertNull("Event row must be fully rolled back", storedEvent)

        val storedState = database.outboxDao().getDeliveryState(event.companyId.value, event.clientEventId.value)
        assertNull("State row must be null", storedState)
    }

    // =========================================================================
    // 9. Currency Overprecision, Zero Total, and Missing Fields Rejection
    // =========================================================================

    @Test
    fun testCurrencyOverprecisionRejectedBeforeWrite() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)

        // 1. Invalid currencyMinorUnits (< 0 or > 6)
        assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(event.companyId, event.actorId, event.deviceId, currencyMinorUnits = -1, intakeRequest = intakeReq)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(event.companyId, event.actorId, event.deviceId, currencyMinorUnits = 7, intakeRequest = intakeReq)
        }

        // 2. Overprecision in lineTotal: cmu=2 but lineTotal has 3 decimal places ("50.005000")
        val packagingUlid = ServerUlid.parse("01J9Z5K3MN0000000000000020")
        val overprecisionLine = OfflineSaleLineV2(
            productId = ServerUlid.parse("01J9Z5K3MN0000000000000010"),
            productUnitId = packagingUlid,
            unitId = packagingUlid,
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("25.002500"),
            taxRateId = ServerUlid.parse("01J9Z5K3MN0000000000000030"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxInclusive = true,
            taxAmount = MoneyAmount.fromNonnegativeString("6.900000"),
            lineTotal = MoneyAmount.fromPositiveString("50.005000")
        )
        val overprecisionPayment = OfflineSalePaymentV2(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = event.cashAccountId,
            amountTendered = MoneyAmount.fromNonnegativeString("50.005000"),
            amountSettled = MoneyAmount.fromPositiveString("50.005000"),
            changeReturned = MoneyAmount.fromNonnegativeString("0.000000")
        )
        val overprecisionEvent = event.copy(lines = listOf(overprecisionLine), payment = overprecisionPayment)
        val overprecisionReq = OutboxTestFixtures.createSampleIntakeRequestV2(overprecisionEvent)

        val exLine = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(event.companyId, event.actorId, event.deviceId, currencyMinorUnits = 2, intakeRequest = overprecisionReq)
        }
        assertTrue(exLine.message!!.contains("invalid scale for currencyMinorUnits=2"))

        // 3. Overprecision in taxAmount ("6.900001" at cmu 2)
        val overprecisionTaxLine = OfflineSaleLineV2(
            productId = ServerUlid.parse("01J9Z5K3MN0000000000000010"),
            productUnitId = packagingUlid,
            unitId = packagingUlid,
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("25.000000"),
            taxRateId = ServerUlid.parse("01J9Z5K3MN0000000000000030"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxInclusive = true,
            taxAmount = MoneyAmount.fromNonnegativeString("6.900001"),
            lineTotal = MoneyAmount.fromPositiveString("50.000000")
        )
        val overprecisionTaxEvent = event.copy(lines = listOf(overprecisionTaxLine))
        val overprecisionTaxReq = OutboxTestFixtures.createSampleIntakeRequestV2(overprecisionTaxEvent)

        val exTax = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(event.companyId, event.actorId, event.deviceId, currencyMinorUnits = 2, intakeRequest = overprecisionTaxReq)
        }
        assertTrue(exTax.message!!.contains("invalid scale for currencyMinorUnits=2"))

        // Verify zero rows written to database
        val allRecords = repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId)
        assertEquals(0, allRecords.size)
    }

    @Test
    fun testScopeParameterMismatchRejectedBeforeDbAccess() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)

        val foreignCompany = ServerUlid.parse("01J9Z5K3MN0000000000000099")
        val foreignActor = ServerUlid.parse("01J9Z5K3MN0000000000000098")
        val foreignDevice = ClientUuid.parse("a0000000-0000-0000-0000-000000000097")

        // 1. Company mismatch
        assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(foreignCompany, event.actorId, event.deviceId, 2, intakeReq)
        }

        // 2. Actor mismatch
        assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(event.companyId, foreignActor, event.deviceId, 2, intakeReq)
        }

        // 3. Device mismatch
        assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(event.companyId, event.actorId, foreignDevice, 2, intakeReq)
        }

        // DB remains untouched
        assertEquals(0, repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId).size)
    }

    @Test
    fun testStrictUtf8RejectsMalformedBytesWithoutSilentReplacementV2() {
        val event = OutboxTestFixtures.createSampleEventV2()

        // Invalid overlong UTF-8 byte sequence
        val invalidUtf8 = byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0x30.toByte())

        val ex = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                rawCanonicalEventUtf8 = invalidUtf8
            )
        }
        assertTrue("Exception must report malformed UTF-8", ex.message!!.contains("Malformed UTF-8"))
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))
    }

    // =========================================================================
    // 10. Timeout & Reconciliation: Exact UUID and Bytes Without Reserialization
    // =========================================================================

    @Test
    fun testTimeoutAndReconciliationPreservesExactBytesAndUuidNoReserialization() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)

        val captureResult = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = intakeReq
        )
        val initialRecord = (captureResult as CaptureResult.Created).record
        val initialBytes = initialRecord.payloadBytes
        val initialDigest = initialRecord.payloadDigest

        // Worker claims batch -> IN_FLIGHT
        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId)
        assertEquals(1, batch.records.size)

        // Worker times out -> UNKNOWN_ACK
        val timedOut = repository.recordTimeout(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            claimToken = batch.claimToken,
            errorMessage = "HTTP 504 Gateway Timeout"
        )
        assertTrue(timedOut)

        val recordAfterTimeout = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(recordAfterTimeout)
        assertEquals(OutboxStatus.UNKNOWN_ACK, recordAfterTimeout!!.status)
        assertEquals(event.clientEventId.value, recordAfterTimeout.clientEventId)
        assertEquals(2, recordAfterTimeout.eventVersion)
        assertEquals(initialDigest, recordAfterTimeout.payloadDigest)
        assertArrayEquals("Payload bytes must not be reserialized after timeout", initialBytes, recordAfterTimeout.payloadBytes)

        // Reconcile delivery confirmation without worker claim
        val invoiceUlid = ServerUlid.parse("01J9Z5K3MN0000000000000088")
        val paymentUlid = ServerUlid.parse("01J9Z5K3MN0000000000000099")
        val reconciled = repository.reconcileDeliveryConfirmation(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = intakeReq.payloadDigest,
            invoiceUlid = invoiceUlid,
            paymentUlid = paymentUlid,
            serverAcknowledgedAt = "2026-11-11T14:35:00Z"
        )
        assertTrue(reconciled)

        val confirmedRecord = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(confirmedRecord)
        assertEquals(OutboxStatus.CONFIRMED, confirmedRecord!!.status)
        assertEquals(event.clientEventId.value, confirmedRecord.clientEventId)
        assertEquals(2, confirmedRecord.eventVersion)
        assertEquals(initialDigest, confirmedRecord.payloadDigest)
        assertArrayEquals("Payload bytes must not be altered after reconciliation", initialBytes, confirmedRecord.payloadBytes)
        assertEquals(invoiceUlid.value, confirmedRecord.deliveryState.invoiceUlid)
        assertEquals(paymentUlid.value, confirmedRecord.deliveryState.paymentUlid)
    }

    // =========================================================================
    // 11. Typed Valid Model + Raw {} Arbitrary Root Rejected Before Write
    // =========================================================================

    @Test
    fun testTypedValidModelWithRawEmptyObjectRejectedBeforeWrite() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)
        val rawEmptyObj = "{}".toByteArray(StandardCharsets.UTF_8)

        assertThrows(Exception::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                intakeRequest = intakeReq,
                rawCanonicalEventUtf8 = rawEmptyObj
            )
        }

        // DB state check: zero records, zero delivery states, zero scope locks
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))
        assertEquals(0, repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId).size)
        assertFalse(repository.isQueueLocked(event.companyId, event.actorId, event.deviceId))
    }

    // =========================================================================
    // 12. Raw Envelope & Extra Wrapper Keys Rejected Before Write
    // =========================================================================

    @Test
    fun testRawEnvelopeAndExtraWrapperKeysRejectedBeforeWrite() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)

        // a) Intake envelope passed to raw-only captureSaleV2
        val rawEnvelope = CanonicalJsonSerializer.serialize(intakeReq).toByteArray(StandardCharsets.UTF_8)
        assertThrows(Exception::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                rawCanonicalEventUtf8 = rawEnvelope
            )
        }
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))

        // b) Fixture wrapper {"request": ...} passed to raw-only captureSaleV2
        val goldenFile = File(OutboxTestFixtures.locateFixturesDir(), "golden-offline-sale-intake-v2.json")
        val rawFixtureWrapper = goldenFile.readBytes()
        assertThrows(Exception::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                rawCanonicalEventUtf8 = rawFixtureWrapper
            )
        }
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))

        // c) Raw bare event with unexpected wrapper/unknown key passed to raw-only captureSaleV2
        val bareJson = CanonicalJsonSerializer.serialize(event)
        val jsonWithUnknownKey = bareJson.replaceFirst("}", ",\"unexpected_wrapper_key\":\"invalid\"}")
            .toByteArray(StandardCharsets.UTF_8)
        assertThrows(Exception::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                rawCanonicalEventUtf8 = jsonWithUnknownKey
            )
        }
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))

        // d) Typed intake overload with raw intake envelope rejected
        assertThrows(Exception::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                intakeRequest = intakeReq,
                rawCanonicalEventUtf8 = rawEnvelope
            )
        }
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))
        assertEquals(0, repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId).size)
    }

    // =========================================================================
    // 13. Noncanonical Whitespace & Raw Key Order Rejected Without Rewrite
    // =========================================================================

    @Test
    fun testNoncanonicalWhitespaceAndKeyOrderRejectedNoRewrite() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)
        val canonicalBareJson = CanonicalJsonSerializer.serialize(event)

        // Noncanonical formatted whitespace (trailing newline or spaces)
        val whitespaceRaw = (canonicalBareJson + "\n ").toByteArray(StandardCharsets.UTF_8)
        val exWhitespace = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                intakeRequest = intakeReq,
                rawCanonicalEventUtf8 = whitespaceRaw
            )
        }
        assertTrue("Exception must cite non-canonical bytes", exWhitespace.message!!.contains("not canonical"))
        assertNull("DB must be clean: no record written", repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))

        // Noncanonical internal key ordering (valid JSON for same model, but non-canonical key order)
        val actorPattern = "\"actor_id\":\"${event.actorId.value}\","
        val companyPattern = "\"company_id\":\"${event.companyId.value}\","
        val reorderedStr = canonicalBareJson.replaceFirst(actorPattern, "").replaceFirst(companyPattern, "$companyPattern$actorPattern")

        val parsedReordered = com.example.data.mobile.contract.StrictJsonParser.parse(reorderedStr).asObject()
        val parsedEventReordered = OfflineSaleEventV2.fromStrictJson(parsedReordered)
        assertEquals("Must parse to same model", event, parsedEventReordered)

        val reorderedRaw = reorderedStr.toByteArray(StandardCharsets.UTF_8)
        val exReordered = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                intakeRequest = intakeReq,
                rawCanonicalEventUtf8 = reorderedRaw
            )
        }
        assertTrue("Exception must cite non-canonical bytes", exReordered.message!!.contains("not canonical"))
        assertNull("DB must be clean: no rewrite or fallback serialization allowed", repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))
        assertEquals(0, repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId).size)
    }

    // =========================================================================
    // 14. Typed Model + Raw Different Lexeme or Event Rejected
    // =========================================================================

    @Test
    fun testTypedModelAndRawDifferentLexemeOrEventRejectedWithoutAffectingExisting() {
        val existingEvent = OutboxTestFixtures.createSampleEventV2()
        val existingIntake = OutboxTestFixtures.createSampleIntakeRequestV2(existingEvent)

        // Seed an existing valid record into DB
        val seedResult = repository.captureSaleV2(
            companyId = existingEvent.companyId,
            actorId = existingEvent.actorId,
            deviceId = existingEvent.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = existingIntake
        )
        assertTrue(seedResult is CaptureResult.Created)
        val originalRecord = repository.getRecord(existingEvent.companyId, existingEvent.actorId, existingEvent.deviceId, existingEvent.clientEventId)!!

        // Attempt capture with typed model but raw for a DIFFERENT event
        val otherEvent = OutboxTestFixtures.createSampleEventV2(localReceiptNumber = "REC-OTHER-0099")
        val otherRawCanonical = CanonicalJsonSerializer.serialize(otherEvent).toByteArray(StandardCharsets.UTF_8)

        val newEvent = OutboxTestFixtures.createSampleEventV2()
        val newIntake = OutboxTestFixtures.createSampleIntakeRequestV2(newEvent)

        assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(
                companyId = newEvent.companyId,
                actorId = newEvent.actorId,
                deviceId = newEvent.deviceId,
                currencyMinorUnits = 2,
                intakeRequest = newIntake,
                rawCanonicalEventUtf8 = otherRawCanonical
            )
        }

        // Verify new event was NOT written
        assertNull(repository.getRecord(newEvent.companyId, newEvent.actorId, newEvent.deviceId, newEvent.clientEventId))

        // Verify existing record is 100% untouched
        val afterRecord = repository.getRecord(existingEvent.companyId, existingEvent.actorId, existingEvent.deviceId, existingEvent.clientEventId)!!
        assertEquals(originalRecord.payloadDigest, afterRecord.payloadDigest)
        assertArrayEquals(originalRecord.payloadBytes, afterRecord.payloadBytes)
        assertEquals(originalRecord.status, afterRecord.status)
        assertEquals(1, repository.getRecordsByScope(existingEvent.companyId, existingEvent.actorId, existingEvent.deviceId).size)
    }

    // =========================================================================
    // 15. Canonical Bare Event Bytes Capture + Disk Reopen Exact
    // =========================================================================

    @Test
    fun testCanonicalBareEventBytesCaptureAndDiskReopenExact() {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo1 = testOutboxRepository(db1)

        val event = OutboxTestFixtures.createSampleEventV2()
        val rawCanonicalBareBytes = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)

        val res = repo1.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            rawCanonicalEventUtf8 = rawCanonicalBareBytes
        )
        assertTrue(res is CaptureResult.Created)
        val created = (res as CaptureResult.Created).record
        assertArrayEquals(rawCanonicalBareBytes, created.payloadBytes)
        assertEquals(event.computePayloadDigest().value, created.payloadDigest)

        db1.close()

        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo2 = testOutboxRepository(db2)

        val reopened = repo2.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(reopened)
        assertEquals(2, reopened!!.eventVersion)
        assertEquals(event.computePayloadDigest().value, reopened.payloadDigest)
        assertArrayEquals(rawCanonicalBareBytes, reopened.payloadBytes)
        assertEquals(OutboxStatus.PENDING, reopened.status)

        db2.close()
    }

    // =========================================================================
    // 16. Raw-Only Canonical Event Accepted
    // =========================================================================

    @Test
    fun testRawOnlyCanonicalBareEventAccepted() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val rawCanonicalBytes = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)

        val res = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            rawCanonicalEventUtf8 = rawCanonicalBytes
        )

        assertTrue(res is CaptureResult.Created)
        val record = (res as CaptureResult.Created).record
        assertEquals(event.clientEventId.value, record.clientEventId)
        assertEquals(2, record.eventVersion)
        assertEquals(event.computePayloadDigest().value, record.payloadDigest)
        assertArrayEquals(rawCanonicalBytes, record.payloadBytes)
        assertEquals(OutboxStatus.PENDING, record.status)
    }

    // =========================================================================
    // 17. Intake Without Raw Serializes Initial Canonical Event Byte Parity
    // =========================================================================

    @Test
    fun testIntakeWithoutRawSerializesInitialCanonicalEventByteParity() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val intakeReq = OutboxTestFixtures.createSampleIntakeRequestV2(event)
        val expectedCanonicalBytes = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)

        // Capture with null rawCanonicalEventUtf8
        val res = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = intakeReq,
            rawCanonicalEventUtf8 = null
        )

        assertTrue(res is CaptureResult.Created)
        val record = (res as CaptureResult.Created).record
        assertArrayEquals("Stored bytes must match canonical serializer bytes", expectedCanonicalBytes, record.payloadBytes)
        assertEquals(event.computePayloadDigest().value, record.payloadDigest)

        // Idempotent duplicate replay without raw
        val dupRes = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            intakeRequest = intakeReq,
            rawCanonicalEventUtf8 = null
        )
        assertTrue(dupRes is CaptureResult.Duplicate)
        val dupRecord = (dupRes as CaptureResult.Duplicate).record
        assertArrayEquals(expectedCanonicalBytes, dupRecord.payloadBytes)
        assertEquals(event.computePayloadDigest().value, dupRecord.payloadDigest)
    }

    // =========================================================================
    // 18. Oversize Payload (> 1 MiB) Rejected Before Decode & Write
    // =========================================================================

    @Test
    fun testOversizePayloadRejectedBeforeDecodeAndWrite() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val oversizeBytes = ByteArray(ContractConstants.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() }

        val ex = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSaleV2(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                currencyMinorUnits = 2,
                rawCanonicalEventUtf8 = oversizeBytes
            )
        }
        assertTrue("Exception must report oversize payload", ex.message!!.contains("exceeds maximum allowed 1 MiB"))
        assertNull(repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId))
        assertEquals(0, repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId).size)
    }

    // =========================================================================
    // 19. Input & Output Alias Defense
    // =========================================================================

    @Test
    fun testInputAndOutputAliasDefenseV2() {
        val event = OutboxTestFixtures.createSampleEventV2()
        val mutableRaw = CanonicalJsonSerializer.serialize(event).toByteArray(StandardCharsets.UTF_8)
        val originalFirstByte = mutableRaw[0]

        val res = repository.captureSaleV2(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            currencyMinorUnits = 2,
            rawCanonicalEventUtf8 = mutableRaw
        )
        assertTrue(res is CaptureResult.Created)

        // 1. Mutate incoming array post-capture (input alias attempt)
        mutableRaw[0] = 0x7F.toByte()

        val record1 = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)!!
        assertEquals("Stored byte must remain uncorrupted by caller mutation", originalFirstByte, record1.payloadBytes[0])

        // 2. Mutate returned byte array (output alias attempt)
        record1.payloadBytes[0] = 0x7F.toByte()
        record1.event.payloadBytes.fill(0)

        val record2 = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)!!
        assertEquals("Subsequent read must remain uncorrupted by output mutation", originalFirstByte, record2.payloadBytes[0])
        assertEquals(event.computePayloadDigest().value, PayloadDigest.fromBytes(record2.payloadBytes).value)
    }

    // =========================================================================
    // 20. Cross Version Lost ACK Raw Identity Preserved
    // =========================================================================

    @Test
    fun testCrossVersionLostAckRawIdentityPreserved() {
        val v1Event = OutboxTestFixtures.createSampleEvent(clientEventId = ClientUuid.random())
        val v2Event = OutboxTestFixtures.createSampleEventV2(
            clientEventId = ClientUuid.random(),
            companyId = v1Event.companyId,
            actorId = v1Event.actorId,
            deviceId = v1Event.deviceId
        )

        val v1Raw = CanonicalJsonSerializer.serialize(v1Event).toByteArray(StandardCharsets.UTF_8)
        val v2Raw = CanonicalJsonSerializer.serialize(v2Event).toByteArray(StandardCharsets.UTF_8)

        val v1Res = repository.captureSale(v1Event, v1Raw)
        val v2Res = repository.captureSaleV2(v2Event.companyId, v2Event.actorId, v2Event.deviceId, 2, v2Raw)
        assertTrue(v1Res is CaptureResult.Created)
        assertTrue(v2Res is CaptureResult.Created)

        // Worker claims both in batch
        val batch = repository.claimNextBatch(v1Event.companyId, v1Event.actorId, v1Event.deviceId, batchSize = 10)
        assertEquals(2, batch.records.size)

        // Lost ACK: worker times out on both -> UNKNOWN_ACK
        assertTrue(repository.recordTimeout(v1Event.companyId, v1Event.actorId, v1Event.deviceId, v1Event.clientEventId, batch.claimToken, "504 Timeout"))
        assertTrue(repository.recordTimeout(v2Event.companyId, v2Event.actorId, v2Event.deviceId, v2Event.clientEventId, batch.claimToken, "504 Timeout"))

        // Reconcile confirmations via status poll (without worker claim token)
        val v1Inv = ServerUlid.parse("01J9Z5K3MN0000000000000071")
        val v1Pay = ServerUlid.parse("01J9Z5K3MN0000000000000072")
        val v2Inv = ServerUlid.parse("01J9Z5K3MN0000000000000073")
        val v2Pay = ServerUlid.parse("01J9Z5K3MN0000000000000074")

        assertTrue(repository.reconcileDeliveryConfirmation(v1Event.companyId, v1Event.actorId, v1Event.deviceId, v1Event.clientEventId, v1Event.computePayloadDigest(), v1Inv, v1Pay, "2026-11-11T15:00:00Z"))
        assertTrue(repository.reconcileDeliveryConfirmation(v2Event.companyId, v2Event.actorId, v2Event.deviceId, v2Event.clientEventId, v2Event.computePayloadDigest(), v2Inv, v2Pay, "2026-11-11T15:00:00Z"))

        // Verify status is CONFIRMED and byte identity is preserved for both versions
        val confirmedV1 = repository.getRecord(v1Event.companyId, v1Event.actorId, v1Event.deviceId, v1Event.clientEventId)!!
        val confirmedV2 = repository.getRecord(v2Event.companyId, v2Event.actorId, v2Event.deviceId, v2Event.clientEventId)!!

        assertEquals(OutboxStatus.CONFIRMED, confirmedV1.status)
        assertEquals(1, confirmedV1.eventVersion)
        assertEquals(v1Event.computePayloadDigest().value, confirmedV1.payloadDigest)
        assertArrayEquals(v1Raw, confirmedV1.payloadBytes)

        assertEquals(OutboxStatus.CONFIRMED, confirmedV2.status)
        assertEquals(2, confirmedV2.eventVersion)
        assertEquals(v2Event.computePayloadDigest().value, confirmedV2.payloadDigest)
        assertArrayEquals(v2Raw, confirmedV2.payloadBytes)
    }
}
