package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tenant, Actor & Device Isolation Tests.
 *
 * Verifies strict boundary scoping to prevent cross-tenant or cross-user logout leakage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTenantIsolationTest {

    private lateinit var context: Context
    private lateinit var database: OutboxDatabase
    private lateinit var repository: OutboxRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = OutboxDatabase.buildInMemory(context)
        repository = testOutboxRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testCrossTenantIsolationPreventsForeignCompanyReadsAndClaims() {
        val companyA = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000001")
        val companyB = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000002")
        val actor = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8111111")
        val device = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")

        val eventA = OutboxTestFixtures.createSampleEvent(companyId = companyA, actorId = actor, deviceId = device)
        repository.captureSale(eventA)

        // Query scope for Company B: must return empty list
        val recordsB = repository.getRecordsByScope(companyB, actor, device)
        assertTrue("Company B must not see Company A's records", recordsB.isEmpty())

        // Query getRecord for Company B: must return null
        val recordB = repository.getRecord(companyB, actor, device, eventA.clientEventId)
        assertNull("Company B must not read Company A's record", recordB)

        // Claim batch for Company B: must claim 0 records
        val claimB = repository.claimNextBatch(companyB, actor, device, batchSize = 10)
        assertEquals("Company B worker must not claim Company A's records", 0, claimB.records.size)

        // Query scope for Company A: returns record
        val recordsA = repository.getRecordsByScope(companyA, actor, device)
        assertEquals(1, recordsA.size)
    }

    @Test
    fun testCrossActorIsolationPreventsUserLogoutLeakage() {
        val company = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000001")
        val actor1 = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8111111")
        val actor2 = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8222222")
        val sharedDevice = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")

        // Actor 1 captures an offline sale
        val eventActor1 = OutboxTestFixtures.createSampleEvent(
            companyId = company,
            actorId = actor1,
            deviceId = sharedDevice
        )
        repository.captureSale(eventActor1)

        // Actor 1 logs out. Actor 2 logs in on the exact same device.
        val recordsActor2 = repository.getRecordsByScope(company, actor2, sharedDevice)
        assertTrue("Actor 2 must not see Actor 1's offline queue records", recordsActor2.isEmpty())

        val recordActor2 = repository.getRecord(company, actor2, sharedDevice, eventActor1.clientEventId)
        assertNull("Actor 2 must not read Actor 1's record", recordActor2)

        // Actor 2 tries to drain the queue: must claim 0 records
        val claimActor2 = repository.claimNextBatch(company, actor2, sharedDevice, batchSize = 10)
        assertEquals("Actor 2 must not drain Actor 1's offline records", 0, claimActor2.records.size)

        // When Actor 1 logs back in, their records are intact and claimable
        val recordsActor1 = repository.getRecordsByScope(company, actor1, sharedDevice)
        assertEquals(1, recordsActor1.size)
    }

    @Test
    fun testDeviceIsolationPreventsForeignDeviceClaims() {
        val company = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000001")
        val actor = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8111111")
        val device1 = ClientUuid.parse("11111111-1111-1111-1111-111111111111")
        val device2 = ClientUuid.parse("22222222-2222-2222-2222-222222222222")

        val event = OutboxTestFixtures.createSampleEvent(
            companyId = company,
            actorId = actor,
            deviceId = device1
        )
        repository.captureSale(event)

        val claimDevice2 = repository.claimNextBatch(company, actor, device2, batchSize = 10)
        assertEquals("Device 2 cannot claim Device 1's records", 0, claimDevice2.records.size)
    }

    @Test
    fun testCrossTenantSameUuidCollisionSucceedsIndependentlyWithoutInterference() {
        val sharedEventId = ClientUuid.parse("99999999-9999-9999-9999-999999999999")
        val companyA = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000001")
        val companyB = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000002")
        val actorA = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8111111")
        val actorB = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8222222")
        val deviceA = ClientUuid.parse("11111111-1111-1111-1111-111111111111")
        val deviceB = ClientUuid.parse("22222222-2222-2222-2222-222222222222")

        val eventA = OutboxTestFixtures.createSampleEvent(
            clientEventId = sharedEventId,
            companyId = companyA,
            actorId = actorA,
            deviceId = deviceA,
            localReceiptNumber = "COMPANY-A-RECEIPT"
        )
        val eventB = OutboxTestFixtures.createSampleEvent(
            clientEventId = sharedEventId,
            companyId = companyB,
            actorId = actorB,
            deviceId = deviceB,
            localReceiptNumber = "COMPANY-B-RECEIPT"
        )

        val resA = repository.captureSale(eventA)
        assertTrue("Company A capture must succeed as Created", resA is CaptureResult.Created)

        // Capture with identical UUID for Company B: must succeed as Created due to composite PK (company_id, client_event_id)
        val resB = repository.captureSale(eventB)
        assertTrue("Company B capture with same UUID must succeed as Created", resB is CaptureResult.Created)

        val recordA = repository.getRecord(companyA, actorA, deviceA, sharedEventId)
        val recordB = repository.getRecord(companyB, actorB, deviceB, sharedEventId)

        assertNotNull(recordA)
        assertNotNull(recordB)
        assertEquals(companyA.value, recordA!!.companyId)
        assertEquals(companyB.value, recordB!!.companyId)
        assertEquals(actorA.value, recordA.actorId)
        assertEquals(actorB.value, recordB.actorId)
    }

    @Test
    fun testSameTenantOtherActorOrDeviceRejectionWithZeroDataLeakage() {
        val company = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8000001")
        val actor1 = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8111111")
        val actor2 = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8222222")
        val device1 = ClientUuid.parse("11111111-1111-1111-1111-111111111111")
        val device2 = ClientUuid.parse("22222222-2222-2222-2222-222222222222")

        val eventActor1 = OutboxTestFixtures.createSampleEvent(
            companyId = company,
            actorId = actor1,
            deviceId = device1
        )
        val res1 = repository.captureSale(eventActor1)
        assertTrue(res1 is CaptureResult.Created)

        // Actor 2 on Device 2 in same company attempts to capture using Actor 1's clientEventId
        val eventActor2 = OutboxTestFixtures.createSampleEvent(
            clientEventId = eventActor1.clientEventId,
            companyId = company,
            actorId = actor2,
            deviceId = device2
        )
        val res2 = repository.captureSale(eventActor2)

        // Must be rejected with ScopeRejected; no cross-scope record leaked
        assertTrue("Cross-scope capture must return ScopeRejected", res2 is CaptureResult.ScopeRejected)
        val scopeRejected = res2 as CaptureResult.ScopeRejected
        assertEquals(eventActor1.clientEventId.value, scopeRejected.clientEventId)

        // Actor 1's record must NOT be demoted to CONFLICT; remains PENDING
        val actor1Record = repository.getRecord(company, actor1, device1, eventActor1.clientEventId)
        assertNotNull(actor1Record)
        assertEquals(OutboxStatus.PENDING, actor1Record!!.status)

        // Actor 2 cannot retrieve Actor 1's record
        val actor2Query = repository.getRecord(company, actor2, device2, eventActor1.clientEventId)
        assertNull("Actor 2 must receive null when querying Actor 1's clientEventId", actor2Query)
    }
}
