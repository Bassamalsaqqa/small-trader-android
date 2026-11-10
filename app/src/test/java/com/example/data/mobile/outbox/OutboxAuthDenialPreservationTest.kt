package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

/**
 * Auth Denial Queue Lock & Preservation Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxAuthDenialPreservationTest {

    private lateinit var context: Context
    private lateinit var database: OutboxDatabase
    private lateinit var repository: OutboxRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = OutboxDatabase.buildInMemory(context)
        repository = OutboxRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
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
        assertEquals(OutboxStatus.PENDING, repository.getRecord(eventPending.clientEventId)!!.status)
        assertEquals(OutboxStatus.CONFLICT, repository.getRecord(eventConflict.clientEventId)!!.status)

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
        val pendingRecord = repository.getRecord(eventPending.clientEventId)
        assertNotNull(pendingRecord)
        assertEquals(OutboxStatus.AUTH_BLOCKED, pendingRecord!!.status)
        assertEquals("HTTP 401: Token revoked by manager", pendingRecord.deliveryState.errorMessage)

        val conflictRecord = repository.getRecord(eventConflict.clientEventId)
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
        val restoredRecord = repository.getRecord(eventPending.clientEventId)
        assertEquals(OutboxStatus.PENDING, restoredRecord!!.status)

        // Conflict record remains conflict
        val restoredConflict = repository.getRecord(eventConflict.clientEventId)
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
}
