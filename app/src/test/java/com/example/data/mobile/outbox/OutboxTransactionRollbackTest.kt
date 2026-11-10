package com.example.data.mobile.outbox

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Transaction Rollback & Failure Atomicity Tests.
 *
 * Verifies that failure during atomic capture transaction propagates without partial
 * write leakage or orphaned state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTransactionRollbackTest {

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
    fun testTransactionAbortsAndRollsBackOnDeliveryStateFailure() {
        val event = OutboxTestFixtures.createSampleEvent()

        // Manually insert an orphan delivery state with this ID to force an SQLite primary key collision on state insert
        val conflictingState = OutboxDeliveryStateEntity(
            clientEventId = event.clientEventId.value,
            companyId = event.companyId.value,
            actorId = event.actorId.value,
            deviceId = event.deviceId.value,
            status = OutboxStatus.PENDING.wireValue,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        // First insert event so FK is satisfied
        val eventEntity = OutboxEventEntity(
            clientEventId = event.clientEventId.value,
            companyId = event.companyId.value,
            actorId = event.actorId.value,
            deviceId = event.deviceId.value,
            deviceLeaseId = event.deviceLeaseId.value,
            capturedAt = event.capturedAt,
            payloadDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            payloadBytes = byteArrayOf(1, 2, 3),
            eventVersion = 1
        )
        database.outboxDao().insertEvent(eventEntity)
        database.outboxDao().insertDeliveryState(conflictingState)

        // Now test that another event with differing ID cannot have state insert if constrained
        val newEvent = OutboxTestFixtures.createSampleEvent()

        // We simulate a transaction failure during runInTransaction:
        try {
            database.runInTransaction {
                val newEventEntity = OutboxEventEntity(
                    clientEventId = newEvent.clientEventId.value,
                    companyId = newEvent.companyId.value,
                    actorId = newEvent.actorId.value,
                    deviceId = newEvent.deviceId.value,
                    deviceLeaseId = newEvent.deviceLeaseId.value,
                    capturedAt = newEvent.capturedAt,
                    payloadDigest = newEvent.computePayloadDigest().value,
                    payloadBytes = byteArrayOf(4, 5, 6),
                    eventVersion = 1
                )
                database.outboxDao().insertEvent(newEventEntity)

                // Deliberately throw an exception inside the transaction before state insert completes
                throw java.io.IOException("Simulated disk I/O write failure")
            }
            fail("Expected IOException to propagate")
        } catch (e: java.io.IOException) {
            assertEquals("Simulated disk I/O write failure", e.message)
        }

        // Verify rollback: newEvent must NOT exist in outbox_events table!
        val rolledBackEvent = database.outboxDao().getEvent(newEvent.clientEventId.value)
        assertNull("Event must not exist after transaction rollback", rolledBackEvent)
        val rolledBackState = database.outboxDao().getDeliveryState(newEvent.clientEventId.value)
        assertNull("Delivery state must not exist after transaction rollback", rolledBackState)
    }

    @Test
    fun testForeignKeyConstraintEnforcedOnDeliveryState() {
        val nonExistentId = "00000000-0000-0000-0000-000000000000"
        val orphanState = OutboxDeliveryStateEntity(
            clientEventId = nonExistentId,
            companyId = "01JCV8X9MBV4W5X6Y7Z8012345",
            actorId = "01JCV8X9MBV4W5X6Y7Z8012346",
            deviceId = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            status = OutboxStatus.PENDING.wireValue,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        assertThrows(SQLiteConstraintException::class.java) {
            database.outboxDao().insertDeliveryState(orphanState)
        }
    }
}
