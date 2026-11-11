package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.DecimalQuantity
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.OfflineSaleLine
import com.example.data.mobile.contract.OfflineSalePayment
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Idempotency, Lost-ACK Replay & Altered-Payload Conflict Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxIdempotencyAndConflictTest {

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
    fun testSameEventLostAckReplayReturnsDuplicateWithoutMutation() {
        val event = OutboxTestFixtures.createSampleEvent()

        // Capture 1: Initial capture
        val res1 = repository.captureSale(event)
        assertTrue("First capture must be Created", res1 is CaptureResult.Created)
        val initialRecord = (res1 as CaptureResult.Created).record

        // Capture 2: Lost-ACK retry with same event and identical digest
        val res2 = repository.captureSale(event)
        assertTrue("Second capture must be Duplicate", res2 is CaptureResult.Duplicate)
        val duplicateRecord = (res2 as CaptureResult.Duplicate).record

        assertEquals(initialRecord.clientEventId, duplicateRecord.clientEventId)
        assertEquals(initialRecord.payloadDigest, duplicateRecord.payloadDigest)
        assertEquals(OutboxStatus.PENDING, duplicateRecord.status)
        assertArrayEquals(initialRecord.payloadBytes, duplicateRecord.payloadBytes)

        // Verify total records in database is still 1 (no duplicate row inserted)
        val allRecords = repository.getRecordsByScope(event.companyId, event.actorId, event.deviceId)
        assertEquals(1, allRecords.size)
    }

    @Test
    fun testAlteredPayloadConflictRejectsReplacementAndPreservesOriginalPayload() {
        val originalEvent = OutboxTestFixtures.createSampleEvent(
            quantity = "1.000000",
            unitPrice = "100.000000",
            taxRate = "15.000000",
            taxAmount = "15.000000",
            lineTotal = "115.000000"
        )
        val res1 = repository.captureSale(originalEvent)
        assertTrue(res1 is CaptureResult.Created)
        val originalRecord = (res1 as CaptureResult.Created).record
        val originalDigest = originalRecord.payloadDigest
        val originalBytes = originalRecord.payloadBytes

        // Attempt to re-capture using same client_event_id but altered price and line total
        val packagingUlid = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012350")
        val alteredLine = OfflineSaleLine(
            productId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012351"),
            productUnitId = packagingUlid,
            unitId = packagingUlid,
            quantity = DecimalQuantity.fromPositiveString("1.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("200.000000"),
            taxRate = TaxPercentage.fromString("15.000000"),
            taxAmount = MoneyAmount.fromNonnegativeString("30.000000"),
            lineTotal = MoneyAmount.fromPositiveString("230.000000")
        )
        val alteredPayment = OfflineSalePayment(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = originalEvent.cashAccountId,
            amountTendered = MoneyAmount.fromNonnegativeString("230.000000"),
            amountSettled = MoneyAmount.fromPositiveString("230.000000"),
            changeReturned = MoneyAmount.fromNonnegativeString("0.000000")
        )
        val alteredEvent = originalEvent.copy(
            lines = listOf(alteredLine),
            payment = alteredPayment
        )

        assertNotEquals(
            "Altered event digest must differ from original digest",
            originalDigest,
            alteredEvent.computePayloadDigest().value
        )

        // Capture 2: must return Conflict and must NOT overwrite the original event
        val res2 = repository.captureSale(alteredEvent)
        assertTrue("Capture with altered digest must result in Conflict", res2 is CaptureResult.Conflict)
        val conflictResult = res2 as CaptureResult.Conflict

        assertEquals(originalEvent.clientEventId.value, conflictResult.clientEventId)
        assertEquals(originalDigest, conflictResult.existingDigest)
        assertEquals(alteredEvent.computePayloadDigest().value, conflictResult.incomingDigest)

        // Verify stored event payload in DB remains exactly the original (NO replacement)
        val storedRecord = repository.getRecord(originalEvent.companyId, originalEvent.actorId, originalEvent.deviceId, originalEvent.clientEventId)
        assertNotNull("Record must still exist", storedRecord)
        assertEquals("Payload digest must NOT have been changed", originalDigest, storedRecord!!.payloadDigest)
        assertArrayEquals("Payload bytes must NOT have been overwritten", originalBytes, storedRecord.payloadBytes)
        assertEquals("Delivery status must be marked CONFLICT", OutboxStatus.CONFLICT, storedRecord.status)
        assertTrue(storedRecord.deliveryState.errorMessage!!.contains("Local conflict"))
    }

    @Test
    fun testStrictUtf8RejectsMalformedBytesWithoutSilentReplacement() {
        val event = OutboxTestFixtures.createSampleEvent()

        // Construct invalid UTF-8 byte sequence: 0xC0 0xAF is an invalid overlong encoding
        val invalidUtf8 = byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0x30.toByte())

        val ex = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSale(event, rawUtf8Json = invalidUtf8)
        }
        assertTrue("Exception must report malformed UTF-8", ex.message!!.contains("Malformed UTF-8"))

        // Verify no record was inserted
        val storedRecord = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertEquals(null, storedRecord)
    }

    @Test
    fun testDefensiveByteArrayCopyPreventsExternalMutationFromAlteringStoredPayload() {
        val event = OutboxTestFixtures.createSampleEvent()
        val canonicalJson = CanonicalJsonSerializer.serialize(event)
        val mutableBytes = canonicalJson.toByteArray(Charsets.UTF_8)
        val originalFirstByte = mutableBytes[0]

        val result = repository.captureSale(event, rawUtf8Json = mutableBytes)
        assertTrue(result is CaptureResult.Created)

        // Caller maliciously or accidentally mutates the source ByteArray after capture
        mutableBytes[0] = 0x7F.toByte()

        // Verify stored entity payload was NOT affected
        val storedRecord = repository.getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)
        assertNotNull(storedRecord)
        assertEquals(originalFirstByte, storedRecord!!.payloadBytes[0])
        // Direct entity access must not expose the backing bytes either.
        storedRecord.event.payloadBytes.fill(0)
        storedRecord.payloadBytes.fill(0)
        assertEquals(originalFirstByte, storedRecord.payloadBytes[0])
        assertEquals(event.computePayloadDigest().value,
            com.example.data.mobile.contract.PayloadDigest.fromBytes(storedRecord.payloadBytes).value)

    }

    @Test
    fun testPayloadValidationDoesNotLogSensitiveDataOnFailure() {
        val event = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "SECRET-RECEIPT-999")
        val otherEvent = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "OTHER-RECEIPT")
        val otherBytes = CanonicalJsonSerializer.serialize(otherEvent).toByteArray(Charsets.UTF_8)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            repository.captureSale(event, rawUtf8Json = otherBytes)
        }
        // Verify exception does not log the entire JSON or sensitive business payload
        assertFalse(ex.message!!.contains("SECRET-RECEIPT-999"))
        assertFalse(ex.message!!.contains("OTHER-RECEIPT"))
        assertTrue(ex.message!!.contains(event.clientEventId.value))
    }
}
