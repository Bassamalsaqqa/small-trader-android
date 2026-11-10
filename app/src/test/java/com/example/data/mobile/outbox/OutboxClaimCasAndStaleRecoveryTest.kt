package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lease-Bounded Claim CAS, Concurrent Claims & Stale Recovery Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxClaimCasAndStaleRecoveryTest {

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
    fun testClaimCasPreventsParallelDrainsAcrossConcurrentWorkers() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        // Worker 1 claims with 30s lease
        val batch1 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

        assertEquals("Worker 1 must claim 1 record", 1, batch1.records.size)
        val claimed1 = batch1.records.first()
        assertEquals(OutboxStatus.IN_FLIGHT, claimed1.status)
        assertEquals(1, claimed1.deliveryState.attemptCount)
        assertEquals(batch1.claimToken, claimed1.deliveryState.claimToken)
        assertNotNull(claimed1.deliveryState.claimExpiresAt)

        // Worker 2 attempts to claim concurrently while Worker 1's lease is active
        val batch2 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

        assertEquals("Worker 2 must claim 0 records (parallel drain prevented by CAS)", 0, batch2.records.size)
    }

    @Test
    fun testStaleClaimRecoveryReclaimsAbandonedInFlightRecord() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        // Worker 1 claims with a very short lease (1 millisecond)
        val batch1 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 1L
        )
        assertEquals(1, batch1.records.size)

        // Sleep to let lease expire
        Thread.sleep(10)

        // Worker 2 claims: must recover stale in-flight record
        val batch2 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

        assertEquals("Worker 2 must recover abandoned in-flight record", 1, batch2.records.size)
        val recovered = batch2.records.first()
        assertEquals(event.clientEventId.value, recovered.clientEventId)
        assertNotEquals("New claim token must be issued", batch1.claimToken, recovered.deliveryState.claimToken)
        assertEquals("Attempt count must increment to 2", 2, recovered.deliveryState.attemptCount)
        assertEquals(OutboxStatus.IN_FLIGHT, recovered.status)
    }

    @Test
    fun testTimeoutTransitionsToUnknownAckWithoutConfirming() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )
        val claimed = batch.records.first()

        // Simulate network timeout during delivery
        val timedOut = repository.recordTimeout(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            claimToken = batch.claimToken,
            errorMessage = "HTTP 504 Gateway Timeout"
        )
        assertTrue(timedOut)

        val updatedRecord = repository.getRecord(event.clientEventId)
        assertNotNull(updatedRecord)
        assertEquals("Status must transition to UNKNOWN_ACK, NEVER CONFIRMED", OutboxStatus.UNKNOWN_ACK, updatedRecord!!.status)
        assertNull("Claim token must be cleared", updatedRecord.deliveryState.claimToken)
        assertNull("Claim expiry must be cleared", updatedRecord.deliveryState.claimExpiresAt)
        assertNull("Server ULID must NOT be set on timeout", updatedRecord.deliveryState.serverUlid)
        assertEquals("HTTP 504 Gateway Timeout", updatedRecord.deliveryState.errorMessage)

        // Verify that after timeout, record is eligible for reclaim
        val retryBatch = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )
        assertEquals("Unknown ack record must be re-claimable", 1, retryBatch.records.size)
        assertEquals(2, retryBatch.records.first().deliveryState.attemptCount)
    }
}
