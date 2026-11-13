package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
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

/**
 * Auth Denial Queue Lock & Preservation Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxAuthDenialPreservationTest {

    private lateinit var context: Context
    private lateinit var database: OutboxDatabase
    private lateinit var repository: OutboxRepository
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = OutboxDatabase.buildInMemory(context)
        repository = testOutboxRepository(database)
        dbFile = File(context.filesDir, "test_outbox_auth_lock_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        database.close()
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun testAuthDeniedLocksQueueAndPreservesPendingAndConflictRecords() {
        val eventPending = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "PENDING-001")
        repository.captureSale(eventPending)

        val eventConflict = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "CONFLICT-001")
        repository.captureSale(eventConflict)
        // Trigger conflict on eventConflict
        val alteredConflictEvent = eventConflict.copy(localReceiptNumber = "ALTERED-001")
        repository.captureSale(alteredConflictEvent)

        // Verify initial states
        val recordPending = repository.getRecord(eventPending.companyId, eventPending.actorId, eventPending.deviceId, eventPending.clientEventId)
        val recordConflict = repository.getRecord(eventConflict.companyId, eventConflict.actorId, eventConflict.deviceId, eventConflict.clientEventId)
        assertEquals(OutboxStatus.PENDING, recordPending!!.status)
        assertEquals(OutboxStatus.CONFLICT, recordConflict!!.status)

        // Simulate HTTP 401 Unauthorized / Token Revocation
        val lockedCount = repository.lockQueueOnAuthDenied(
            companyId = eventPending.companyId,
            actorId = eventPending.actorId,
            deviceId = eventPending.deviceId,
            reason = "HTTP 401: Token revoked by manager"
        )
        assertEquals("Only active pending record must transition to auth_blocked", 1, lockedCount)

        // Queue must report locked
        assertTrue(repository.isQueueLocked(eventPending.companyId, eventPending.actorId, eventPending.deviceId))

        // Records must be preserved:
        val pendingRecord = repository.getRecord(eventPending.companyId, eventPending.actorId, eventPending.deviceId, eventPending.clientEventId)
        assertNotNull(pendingRecord)
        assertEquals(OutboxStatus.AUTH_BLOCKED, pendingRecord!!.status)
        assertEquals("HTTP 401: Token revoked by manager", pendingRecord.deliveryState.errorMessage)

        val conflictRecord = repository.getRecord(eventConflict.companyId, eventConflict.actorId, eventConflict.deviceId, eventConflict.clientEventId)
        assertNotNull(conflictRecord)
        assertEquals("Conflict record must remain CONFLICT (preserved without deletion)", OutboxStatus.CONFLICT, conflictRecord!!.status)

        // While locked, claiming must be rejected
        assertThrows(QueueLockedException::class.java) {
            repository.claimNextBatch(
                companyId = eventPending.companyId,
                actorId = eventPending.actorId,
                deviceId = eventPending.deviceId,
                batchSize = 10
            )
        }

        // Manager re-authenticates the session
        val unlockedCount = repository.unlockQueueAfterReauth(
            companyId = eventPending.companyId,
            actorId = eventPending.actorId,
            deviceId = eventPending.deviceId
        )
        assertEquals(1, unlockedCount)
        assertFalse(repository.isQueueLocked(eventPending.companyId, eventPending.actorId, eventPending.deviceId))

        // Formerly auth_blocked record is now pending again and claimable
        val restoredRecord = repository.getRecord(eventPending.companyId, eventPending.actorId, eventPending.deviceId, eventPending.clientEventId)
        assertEquals(OutboxStatus.PENDING, restoredRecord!!.status)

        // Conflict record remains conflict
        val restoredConflict = repository.getRecord(eventConflict.companyId, eventConflict.actorId, eventConflict.deviceId, eventConflict.clientEventId)
        assertEquals(OutboxStatus.CONFLICT, restoredConflict!!.status)

        // Now claim succeeds for the restored pending record
        val batch = repository.claimNextBatch(
            companyId = eventPending.companyId,
            actorId = eventPending.actorId,
            deviceId = eventPending.deviceId,
            batchSize = 10
        )
        assertEquals(1, batch.records.size)
        assertEquals(eventPending.clientEventId.value, batch.records.first().clientEventId)
    }

    @Test
    fun testPersistedEmptyQueueAuthLockAcrossReopenAndSubsequentCapture() {
        val company = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000001")
        val actor = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8111111")
        val device = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")

        // Step 1: Open disk-backed database with zero events in the queue
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo1 = testOutboxRepository(db1)

        assertEquals("Queue should start empty", 0, repo1.getRecordsByScope(company, actor, device).size)
        assertFalse("Queue should not be locked initially", repo1.isQueueLocked(company, actor, device))

        // Lock empty queue on HTTP 401
        val lockedCount = repo1.lockQueueOnAuthDenied(company, actor, device, "HTTP 401: Session revoked on empty queue")
        assertEquals(0, lockedCount) // 0 delivery states transitioned
        assertTrue("Empty queue must still be locked via scope lock table", repo1.isQueueLocked(company, actor, device))

        // Step 2: Close and reopen from disk file
        db1.close()

        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val repo2 = testOutboxRepository(db2)

        // Lock must persist across reopen
        assertTrue("Scope lock must persist across disk reopen", repo2.isQueueLocked(company, actor, device))

        // Attempting to claim must immediately throw QueueLockedException
        assertThrows(QueueLockedException::class.java) {
            repo2.claimNextBatch(company, actor, device, batchSize = 10)
        }

        // Step 3: Capture new sale while queue is locked
        val newEvent = OutboxTestFixtures.createSampleEvent(companyId = company, actorId = actor, deviceId = device)
        val captureResult = repo2.captureSale(newEvent)
        assertTrue(captureResult is CaptureResult.Created)

        // New record must be created directly in AUTH_BLOCKED state to prevent worker drain
        val capturedRecord = (captureResult as CaptureResult.Created).record
        assertEquals("Capture during auth denial must have AUTH_BLOCKED state", OutboxStatus.AUTH_BLOCKED, capturedRecord.status)
        assertTrue(capturedRecord.deliveryState.errorMessage!!.contains("Captured while queue locked"))

        // Drain still blocked
        assertThrows(QueueLockedException::class.java) {
            repo2.claimNextBatch(company, actor, device, batchSize = 10)
        }

        // Step 4: Manager re-authenticates
        repo2.unlockQueueAfterReauth(company, actor, device)
        assertFalse(repo2.isQueueLocked(company, actor, device))

        // Record is now PENDING and claimable
        val pendingRecord = repo2.getRecord(company, actor, device, newEvent.clientEventId)
        assertNotNull(pendingRecord)
        assertEquals(OutboxStatus.PENDING, pendingRecord!!.status)

        val batch = repo2.claimNextBatch(company, actor, device, batchSize = 10)
        assertEquals(1, batch.records.size)
        assertEquals(newEvent.clientEventId.value, batch.records.first().clientEventId)

        db2.close()
    }
}
