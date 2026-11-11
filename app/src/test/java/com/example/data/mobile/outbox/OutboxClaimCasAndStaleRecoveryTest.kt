package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ServerUlid
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * Lease-Bounded Claim CAS, Concurrent Claims & Stale Recovery Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxClaimCasAndStaleRecoveryTest {

    private lateinit var context: Context
    private lateinit var database: OutboxDatabase
    private var testClockTime = 100_000L
    private lateinit var repository: OutboxRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = OutboxDatabase.buildInMemory(context)
        testClockTime = 100_000L
        repository = OutboxRepositoryImpl(database, clock = { testClockTime })
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

        // Worker 1 claims at testClockTime (100_000L) with 30s lease (expires at 130_000L)
        val batch1 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )
        assertEquals(1, batch1.records.size)

        // Deterministically advance clock past lease expiration (no Thread.sleep race!)
        testClockTime += 35_000L // 135_000L

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

        val updatedRecord = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(updatedRecord)
        assertEquals("Status must transition to UNKNOWN_ACK, NEVER CONFIRMED", OutboxStatus.UNKNOWN_ACK, updatedRecord!!.status)
        assertNull("Claim token must be cleared", updatedRecord.deliveryState.claimToken)
        assertNull("Claim expiry must be cleared", updatedRecord.deliveryState.claimExpiresAt)
        assertNull("Server ULID must NOT be set on timeout", updatedRecord.deliveryState.serverUlid)
        assertEquals("HTTP 504 Gateway Timeout", updatedRecord.deliveryState.errorMessage)

        // Advance clock to simulate retry eligibility
        testClockTime += 5_000L

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

    @Test
    fun testLateConfirmationWithStaleClaimTokenOrExpiredLeaseIsRejected() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        // Worker 1 claims at T0 with 30s lease
        val batch1 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

        // Advance clock past expiration
        testClockTime += 35_000L

        // Worker 2 reclaims the record
        val batch2 = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )
        assertEquals(1, batch2.records.size)

        // Worker 1 returns late and tries to confirm with expired lease
        assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                clientEventId = event.clientEventId,
                payloadDigest = event.computePayloadDigest(),
                invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
                serverAcknowledgedAt = "2026-11-10T12:01:00Z",
                claimToken = batch1.claimToken // stale token!
            )
        }

        // Worker 2 confirms with valid claim token
        val confirmed = repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
                paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-10T12:01:00Z",
            claimToken = batch2.claimToken
        )
        assertTrue("Valid worker 2 confirmation must succeed", confirmed)
    }

    @Test
    fun testBatchAndLeaseDurationBoundariesAndOverflowSafety() {
        val event = OutboxTestFixtures.createSampleEvent()

        // Batch size out of bounds [1..100]
        assertThrows(IllegalArgumentException::class.java) {
            repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, batchSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, batchSize = 101)
        }

        // Lease duration out of bounds [1000..300000 ms]
        assertThrows(IllegalArgumentException::class.java) {
            repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, leaseDurationMillis = 999L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, leaseDurationMillis = 300_001L)
        }

        // Clock near Long.MAX_VALUE does not overflow with ArithmeticException
        testClockTime = Long.MAX_VALUE - 5_000L
        repository.captureSale(event)
        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, batchSize = 1, leaseDurationMillis = 10_000L)
        assertEquals(1, batch.records.size)
        assertEquals(Long.MAX_VALUE, batch.records.first().deliveryState.claimExpiresAt)
    }
}
