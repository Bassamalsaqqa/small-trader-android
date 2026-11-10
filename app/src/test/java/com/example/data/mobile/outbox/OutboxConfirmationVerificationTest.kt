package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Server Acknowledgement & Confirmation Verification Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxConfirmationVerificationTest {

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
    fun testValidServerAcknowledgementSuccessfullyConfirmsRecord() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

        val serverUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8999999")
        val serverAckTimestamp = "2026-11-10T12:01:00Z"

        val confirmed = repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            serverUlid = serverUlid,
            serverAcknowledgedAt = serverAckTimestamp,
            claimToken = batch.claimToken
        )
        assertTrue("Valid server acknowledgement must return true", confirmed)

        val record = repository.getRecord(event.clientEventId)
        assertNotNull(record)
        assertEquals(OutboxStatus.CONFIRMED, record!!.status)
        assertEquals(serverUlid.value, record.deliveryState.serverUlid)
        assertEquals(serverAckTimestamp, record.deliveryState.serverAcknowledgedAt)
        assertNull("Claim token must be cleared on confirmation", record.deliveryState.claimToken)
        assertNull("Claim expiry must be cleared on confirmation", record.deliveryState.claimExpiresAt)
    }

    @Test
    fun testConfirmationRejectsMismatchedCompanyId() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val foreignCompanyId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8888888")
        val serverUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8999999")

        assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = foreignCompanyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                clientEventId = event.clientEventId,
                payloadDigest = event.computePayloadDigest(),
                serverUlid = serverUlid,
                serverAcknowledgedAt = "2026-11-10T12:01:00Z"
            )
        }

        // Verify status remains PENDING
        val record = repository.getRecord(event.clientEventId)
        assertEquals(OutboxStatus.PENDING, record!!.status)
    }

    @Test
    fun testConfirmationRejectsTamperedPayloadDigest() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val tamperedDigest = PayloadDigest.parse("sha256:0000000000000000000000000000000000000000000000000000000000000000")
        val serverUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8999999")

        assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                clientEventId = event.clientEventId,
                payloadDigest = tamperedDigest,
                serverUlid = serverUlid,
                serverAcknowledgedAt = "2026-11-10T12:01:00Z"
            )
        }

        val record = repository.getRecord(event.clientEventId)
        assertEquals(OutboxStatus.PENDING, record!!.status)
    }

    @Test
    fun testConfirmationRejectsInvalidServerUlid() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        // Attempting to construct or confirm with UUID or non-ULID
        assertThrows(IllegalArgumentException::class.java) {
            ServerUlid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerUlid.parse("01jcv8x9mbv4w5x6y7z8999999") // lowercase rejected
        }
    }

    @Test
    fun testConfirmationRejectsEventInConflictState() {
        val originalEvent = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(originalEvent)

        // Force conflict via altered payload
        val alteredEvent = originalEvent.copy(
            localReceiptNumber = "ALTERED-RECEIPT"
        )
        repository.captureSale(alteredEvent)

        val serverUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8999999")
        assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = originalEvent.companyId,
                actorId = originalEvent.actorId,
                deviceId = originalEvent.deviceId,
                clientEventId = originalEvent.clientEventId,
                payloadDigest = originalEvent.computePayloadDigest(),
                serverUlid = serverUlid,
                serverAcknowledgedAt = "2026-11-10T12:01:00Z"
            )
        }

        val record = repository.getRecord(originalEvent.clientEventId)
        assertEquals(OutboxStatus.CONFLICT, record!!.status)
    }
}
