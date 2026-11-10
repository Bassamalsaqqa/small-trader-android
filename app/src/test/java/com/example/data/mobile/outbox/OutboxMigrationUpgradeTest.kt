package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema Upgrade & Migration Strategy Tests.
 *
 * Verifies real schema upgrade migration from Version 1 to Version 2 preserving
 * queued records intact (no fake identity migrations).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxMigrationUpgradeTest {

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
    fun testRealMigrationV1ToV2PreservesQueuedRecordsIntact() {
        // Step 1: Capture records in schema Version 1
        val event = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "MIGRATE-001")
        val captureResult = repository.captureSale(event)
        assertTrue(captureResult is CaptureResult.Created)

        val originalRecord = repository.getRecord(event.clientEventId)
        assertNotNull(originalRecord)
        assertEquals(event.clientEventId.value, originalRecord!!.clientEventId)
        assertEquals(OutboxStatus.PENDING, originalRecord.status)

        // Step 2: Apply real MIGRATION_1_2 to SQLite database
        val sqliteDb = database.openHelper.writableDatabase
        OutboxMigrations.MIGRATION_1_2.migrate(sqliteDb)

        // Step 3: Verify schema change (new column routing_tag added)
        val cursor = sqliteDb.query("PRAGMA table_info(outbox_delivery_states)")
        var foundRoutingTagColumn = false
        while (cursor.moveToNext()) {
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex >= 0 && cursor.getString(nameIndex) == "routing_tag") {
                foundRoutingTagColumn = true
                break
            }
        }
        cursor.close()
        assertTrue("MIGRATION_1_2 must add routing_tag column", foundRoutingTagColumn)

        // Step 4: Verify queued record in outbox_events and outbox_delivery_states preserved with ZERO data loss
        val preservedRecord = repository.getRecord(event.clientEventId)
        assertNotNull("Record must be preserved after schema upgrade", preservedRecord)
        assertEquals(originalRecord.clientEventId, preservedRecord!!.clientEventId)
        assertEquals(originalRecord.companyId, preservedRecord.companyId)
        assertEquals(originalRecord.actorId, preservedRecord.actorId)
        assertEquals(originalRecord.deviceId, preservedRecord.deviceId)
        assertEquals(originalRecord.payloadDigest, preservedRecord.payloadDigest)
        assertEquals(OutboxStatus.PENDING, preservedRecord.status)
        assertEquals(originalRecord.deliveryState.createdAt, preservedRecord.deliveryState.createdAt)
    }
}
