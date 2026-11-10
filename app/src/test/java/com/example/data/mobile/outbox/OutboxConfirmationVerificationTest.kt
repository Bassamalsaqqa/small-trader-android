package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID
        val paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID
        val serverAckTimestamp = "2026-11-10T12:01:00Z"

        val confirmed = repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            invoiceUlid = invoiceUlid,
            paymentUlid = paymentUlid,
            serverAcknowledgedAt = serverAckTimestamp,
            claimToken = batch.claimToken
        )
        assertTrue("Valid server acknowledgement must return true", confirmed)

        val record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(record)
        assertEquals(OutboxStatus.CONFIRMED, record!!.status)
        assertEquals(invoiceUlid.value, record.deliveryState.invoiceUlid)
        assertEquals(paymentUlid.value, record.deliveryState.paymentUlid)
        assertEquals(invoiceUlid.value, record.deliveryState.serverUlid)
        assertEquals(serverAckTimestamp, record.deliveryState.serverAcknowledgedAt)
        assertNull("Claim token must be cleared on confirmation", record.deliveryState.claimToken)
        assertNull("Claim expiry must be cleared on confirmation", record.deliveryState.claimExpiresAt)
    }

    @Test
    fun testConfirmationRejectsMismatchedCompanyId() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

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
                serverAcknowledgedAt = "2026-11-10T12:01:00Z",
                claimToken = batch.claimToken
            )
        }

        // Verify status remains in_flight
        val record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.IN_FLIGHT, record!!.status)
    }

    @Test
    fun testConfirmationRejectsTamperedPayloadDigest() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            batchSize = 10,
            leaseDurationMillis = 30_000L
        )

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
                serverAcknowledgedAt = "2026-11-10T12:01:00Z",
                claimToken = batch.claimToken
            )
        }

        val record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.IN_FLIGHT, record!!.status)
    }

    @Test
    fun testConfirmationRejectsInvalidServerUlid() {
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
                serverAcknowledgedAt = "2026-11-10T12:01:00Z",
                claimToken = "dummy-token"
            )
        }

        val record = repository.getRecord(originalEvent.companyId, originalEvent.actorId, originalEvent.deviceId, originalEvent.clientEventId)
        assertEquals(OutboxStatus.CONFLICT, record!!.status)
    }

    @Test
    fun testConfirmationRejectsUnclaimedPendingOrAuthBlockedState() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val serverUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8999999")

        // 1. Rejects PENDING record
        val ex1 = assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                clientEventId = event.clientEventId,
                payloadDigest = event.computePayloadDigest(),
                serverUlid = serverUlid,
                serverAcknowledgedAt = "2026-11-10T12:01:00Z",
                claimToken = "some-token"
            )
        }
        assertTrue(ex1.message!!.contains("PENDING"))

        // 2. Lock queue to move to AUTH_BLOCKED
        repository.lockQueueOnAuthDenied(event.companyId, event.actorId, event.deviceId, "HTTP 401")
        val ex2 = assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                clientEventId = event.clientEventId,
                payloadDigest = event.computePayloadDigest(),
                serverUlid = serverUlid,
                serverAcknowledgedAt = "2026-11-10T12:01:00Z",
                claimToken = "some-token"
            )
        }
        assertTrue(ex2.message!!.contains("AUTH_BLOCKED"))
    }

    @Test
    fun testConfirmedRecordExactMappingReplayPreservesOriginalWithoutMutation() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, 10)
        val invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID
        val paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID
        val initialTimestamp = "2026-11-10T12:01:00Z"

        // First confirmation
        val conf1 = repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            invoiceUlid = invoiceUlid,
            paymentUlid = paymentUlid,
            serverAcknowledgedAt = initialTimestamp,
            claimToken = batch.claimToken
        )
        assertTrue(conf1)

        val recordAfterConf1 = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        val initialUpdatedAt = recordAfterConf1!!.deliveryState.updatedAt

        // Exact mapping replay: calling confirm again with matching invoice and payment ULID
        val conf2 = repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            invoiceUlid = invoiceUlid,
            paymentUlid = paymentUlid,
            serverAcknowledgedAt = "2026-11-10T12:05:00Z", // later timestamp
            claimToken = "any-token"
        )
        assertTrue("Exact mapping replay must return true", conf2)

        // DB record must NOT be mutated:
        val recordAfterConf2 = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(initialTimestamp, recordAfterConf2!!.deliveryState.serverAcknowledgedAt)
        assertEquals(initialUpdatedAt, recordAfterConf2.deliveryState.updatedAt)
    }

    @Test
    fun testConfirmedRecordConflictingMappingRejectedAndPreservesOriginal() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, 10)
        val originalInvoice = OutboxTestFixtures.SAMPLE_INVOICE_ULID
        val originalPayment = OutboxTestFixtures.SAMPLE_PAYMENT_ULID

        repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            invoiceUlid = originalInvoice,
            paymentUlid = originalPayment,
            serverAcknowledgedAt = "2026-11-10T12:01:00Z",
            claimToken = batch.claimToken
        )

        // Re-confirming with conflicting invoice ULID
        val conflictingInvoice = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8777777")
        val ex = assertThrows(InvalidAcknowledgementException::class.java) {
            repository.confirmDelivery(
                companyId = event.companyId,
                actorId = event.actorId,
                deviceId = event.deviceId,
                clientEventId = event.clientEventId,
                payloadDigest = event.computePayloadDigest(),
                invoiceUlid = conflictingInvoice,
                paymentUlid = originalPayment,
                serverAcknowledgedAt = "2026-11-10T12:02:00Z",
                claimToken = "any-token"
            )
        }
        assertTrue(ex.message!!.contains("Conflicting server mapping"))

        // Original confirmation remains strictly preserved
        val record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(originalInvoice.value, record!!.deliveryState.invoiceUlid)
    }

    @Test
    fun testConfirmedRecordCannotBeDemotedByLateTimeoutConflictOrAlteredCapture() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, 10)
        repository.confirmDelivery(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            serverUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            serverAcknowledgedAt = "2026-11-10T12:01:00Z",
            claimToken = batch.claimToken
        )

        // 1. Late recordTimeout: must return false and NOT change CONFIRMED status
        val timeoutResult = repository.recordTimeout(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            claimToken = batch.claimToken,
            errorMessage = "Late timeout"
        )
        assertFalse(timeoutResult)
        var record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.CONFIRMED, record!!.status)

        // 2. Late captureSale with altered payload: must return Conflict and NOT change stored CONFIRMED status
        val alteredEvent = event.copy(localReceiptNumber = "ALTERED-LATE")
        val captureResult = repository.captureSale(alteredEvent)
        assertTrue(captureResult is CaptureResult.Conflict)

        record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals("Confirmed record must NOT be demoted to CONFLICT", OutboxStatus.CONFIRMED, record!!.status)

        // 3. Late auth denial: must NOT touch CONFIRMED record
        repository.lockQueueOnAuthDenied(event.companyId, event.actorId, event.deviceId, "Late 401")
        record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.CONFIRMED, record!!.status)
    }

    @Test
    fun testExplicitReconciliationPathConfirmsInFlightOrUnknownAckWithoutWorkerClaim() {
        val event = OutboxTestFixtures.createSampleEvent()
        repository.captureSale(event)

        val batch = repository.claimNextBatch(event.companyId, event.actorId, event.deviceId, 10)
        // Transition to UNKNOWN_ACK via timeout
        repository.recordTimeout(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            claimToken = batch.claimToken,
            errorMessage = "HTTP 504"
        )

        var record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.UNKNOWN_ACK, record!!.status)

        // Status reconciliation query (e.g., polling /sales/events/{client_event_id}/status)
        val reconciled = repository.reconcileDeliveryConfirmation(
            companyId = event.companyId,
            actorId = event.actorId,
            deviceId = event.deviceId,
            clientEventId = event.clientEventId,
            payloadDigest = event.computePayloadDigest(),
            invoiceUlid = OutboxTestFixtures.SAMPLE_INVOICE_ULID,
            paymentUlid = OutboxTestFixtures.SAMPLE_PAYMENT_ULID,
            serverAcknowledgedAt = "2026-11-10T12:05:00Z"
        )
        assertTrue(reconciled)

        record = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(OutboxStatus.CONFIRMED, record!!.status)
        assertEquals(OutboxTestFixtures.SAMPLE_INVOICE_ULID.value, record.deliveryState.invoiceUlid)
        assertEquals(OutboxTestFixtures.SAMPLE_PAYMENT_ULID.value, record.deliveryState.paymentUlid)
    }
}
