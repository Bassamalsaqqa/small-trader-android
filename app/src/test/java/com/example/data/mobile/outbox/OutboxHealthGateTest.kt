package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.session.InstallationIdentityStore
import com.example.data.mobile.session.SessionStorageError
import com.example.data.mobile.session.SessionStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxHealthGateTest {

    private fun createLegacyDatabaseFixture(dbFile: File): OutboxDatabase {
        val schemaJson = File("schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json").readText()
        val schema = org.json.JSONObject(schemaJson).getJSONObject("database")

        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val table = entities.getJSONObject(i)
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                val indices = table.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", table.getString("tableName")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) {
                old.execSQL(setup.getString(i))
            }
            old.version = 1
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        return OutboxDatabase.buildDatabase(context, dbFile.path, true)
    }

    @Test
    fun newEmptyInstallationHealth_returnsNewEmpty_noKeyGenerated_noLegacyFalsePositive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "empty-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val initialKeyGenerations = crypto.keyGenerationCount
        val cipher = OutboxPayloadCipher(crypto)
        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.NEW_EMPTY, result.status)
        assertEquals(0, result.totalEvents)
        assertEquals(0, result.probedCount)
        assertFalse(result.isSampleProbe)
        assertTrue(result.isOperational)
        assertFalse(result.isLegacyBlocked)
        assertFalse(result.canRetry)

        // Verify zero key generation was triggered just to observe an empty queue
        assertEquals(initialKeyGenerations, crypto.keyGenerationCount)

        db.close()
    }

    @Test
    fun realExistingRoomV1PopulatedPlaintext_preservesAllBytesClaimsLocks_andBlocksAsLegacyPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "legacy-fixture-${UUID.randomUUID()}.db")

        // 1. Create historical v1 schema database fixture
        var db = createLegacyDatabaseFixture(dbFile)

        // 2. Populate with real unencrypted legacy sales event
        val legacyEvent = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "LEGACY-REC-1001")
        val legacyPlaintextBytes = CanonicalJsonSerializer.serialize(legacyEvent).toByteArray(Charsets.UTF_8)
        val eventEntity = OutboxEventEntity(
            clientEventId = legacyEvent.clientEventId.value,
            companyId = legacyEvent.companyId.value,
            actorId = legacyEvent.actorId.value,
            deviceId = legacyEvent.deviceId.value,
            deviceLeaseId = legacyEvent.deviceLeaseId.value,
            capturedAt = legacyEvent.capturedAt,
            payloadDigest = legacyEvent.computePayloadDigest().value,
            payloadBytes = legacyPlaintextBytes,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(eventEntity)

        // Populate delivery claim state (in_flight lease)
        val claimState = OutboxDeliveryStateEntity(
            clientEventId = legacyEvent.clientEventId.value,
            companyId = legacyEvent.companyId.value,
            actorId = legacyEvent.actorId.value,
            deviceId = legacyEvent.deviceId.value,
            status = "in_flight",
            attemptCount = 1,
            lastAttemptAt = 1700000000000L,
            claimToken = "claim-token-xyz-123",
            claimExpiresAt = 1700000300000L,
            invoiceUlid = null,
            paymentUlid = null,
            serverUlid = null,
            serverAcknowledgedAt = null,
            errorMessage = null,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        db.outboxDao().insertDeliveryState(claimState)

        // Populate scope lock
        val scopeLock = OutboxScopeLockEntity(
            companyId = legacyEvent.companyId.value,
            actorId = legacyEvent.actorId.value,
            deviceId = legacyEvent.deviceId.value,
            lockedAt = 1700000000000L,
            reason = "AUTH_DENIED"
        )
        db.outboxDao().insertScopeLock(scopeLock)

        // Setup installation identity store
        val identityFile = File(context.noBackupFilesDir, "test_install_id_${UUID.randomUUID()}.json")
        val identityStore = InstallationIdentityStore(identityFile)
        val originalInstallId = identityStore.getOrCreateInstallationId()

        db.close()

        // 3. Reopen database across restart
        db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val initialKeyGenerations = crypto.keyGenerationCount
        val cipher = OutboxPayloadCipher(crypto)
        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        // 4. Run health gate inspection
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.isLegacyBlocked)
        assertTrue(result.hasPreservedData)
        assertTrue(result.canRetry)
        assertEquals(1, result.totalEvents)

        // 5. Verify all bytes, claims, locks, and installation ID are PRESERVED exactly
        val reopenedEvent = db.outboxDao().getEvent(legacyEvent.companyId.value, legacyEvent.clientEventId.value)
        assertNotNull(reopenedEvent)
        assertArrayEquals(legacyPlaintextBytes, reopenedEvent!!.payloadBytes)
        // Confirm it was NOT auto-converted to STO envelope
        assertFalse(cipher.isEncrypted(reopenedEvent.payloadBytes))
        assertFalse(cipher.hasStoEnvelope(reopenedEvent.payloadBytes))

        val reopenedState = db.outboxDao().getDeliveryState(legacyEvent.companyId.value, legacyEvent.clientEventId.value)
        assertNotNull(reopenedState)
        assertEquals("in_flight", reopenedState!!.status)
        assertEquals("claim-token-xyz-123", reopenedState.claimToken)
        assertEquals(1700000300000L, reopenedState.claimExpiresAt)

        val isLocked = db.outboxDao().isScopeLocked(legacyEvent.companyId.value, legacyEvent.actorId.value, legacyEvent.deviceId.value)
        assertTrue(isLocked)

        val reloadedInstallId = identityStore.getOrCreateInstallationId()
        assertEquals(originalInstallId, reloadedInstallId)

        // 6. Verify retries NEVER regenerate key, reset database, or auto-convert
        val retryResult = inspector.inspectHealth()
        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, retryResult.status)
        assertEquals(initialKeyGenerations, crypto.keyGenerationCount)

        val postRetryEvent = db.outboxDao().getEvent(legacyEvent.companyId.value, legacyEvent.clientEventId.value)
        assertArrayEquals(legacyPlaintextBytes, postRetryEvent!!.payloadBytes)

        db.close()
    }

    @Test
    fun encryptedHealthy_sampleProbeSucceeds_continuesOperational() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "healthy-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val sampleEvent = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "HEALTHY-01")
        val captureResult = repo.captureSale(sampleEvent)
        assertTrue(captureResult is CaptureResult.Created)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.HEALTHY, result.status)
        assertTrue(result.isOperational)
        assertFalse(result.isLegacyBlocked)
        assertEquals(1, result.totalEvents)
        assertEquals(1, result.probedCount)
        assertFalse(result.isSampleProbe)

        db.close()
    }

    @Test
    fun encryptedLostKey_returnsKeyUnavailable_preservesRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "lost-key-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val sampleEvent = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "LOSTKEY-01")
        repo.captureSale(sampleEvent)
        val originalCiphertext = db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)!!.payloadBytes

        // Simulate key lost / missing on device
        crypto.missing = true
        val generationsBeforeProbe = crypto.keyGenerationCount

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.KEY_UNAVAILABLE, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

        // Verify key was NOT regenerated over existing records
        assertEquals(generationsBeforeProbe, crypto.keyGenerationCount)

        // Verify record bytes preserved intact
        val stored = db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)
        assertNotNull(stored)
        assertArrayEquals(originalCiphertext, stored!!.payloadBytes)

        db.close()
    }

    @Test
    fun encryptedLockedKey_returnsEncryptedLocked_preservesRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "locked-key-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val sampleEvent = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "LOCKED-01")
        repo.captureSale(sampleEvent)
        val originalCiphertext = db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)!!.payloadBytes

        // Simulate permanently invalidated or locked key
        crypto.failure = SessionStorageException(SessionStorageError.Locked())

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.ENCRYPTED_LOCKED, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

        // Verify record bytes preserved intact
        val stored = db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)
        assertNotNull(stored)
        assertArrayEquals(originalCiphertext, stored!!.payloadBytes)

        db.close()
    }

    @Test
    fun corruptCiphertextOrEnvelope_returnsCorrupt_preservesRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "corrupt-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val sampleEvent = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "CORRUPT-01")
        repo.captureSale(sampleEvent)

        // Tamper with the envelope: replace with an invalid STO/2 envelope prefix
        val invalidEnvelope = byteArrayOf(0x53, 0x54, 0x4f, 0x02, 0x00, 0x01, 0x02)
        db.outboxDao().replacePayloadForConversion(
            sampleEvent.companyId.value,
            sampleEvent.clientEventId.value,
            invalidEnvelope
        )

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.CORRUPT, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

        val stored = db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)
        assertNotNull(stored)
        assertArrayEquals(invalidEnvelope, stored!!.payloadBytes)

        db.close()
    }

    @Test
    fun mixedQueue_returnsMixedQueue_preservesRecords() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "mixed-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        // Insert one encrypted event
        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "MIX-01")
        repo.captureSale(e1)

        // Insert one legacy plaintext event
        val e2 = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            localReceiptNumber = "MIX-02"
        )
        val plainBytes = CanonicalJsonSerializer.serialize(e2).toByteArray(Charsets.UTF_8)
        val legacyEntity = OutboxEventEntity(
            clientEventId = e2.clientEventId.value,
            companyId = e2.companyId.value,
            actorId = e2.actorId.value,
            deviceId = e2.deviceId.value,
            deviceLeaseId = e2.deviceLeaseId.value,
            capturedAt = e2.capturedAt,
            payloadDigest = e2.computePayloadDigest().value,
            payloadBytes = plainBytes,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(legacyEntity)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.MIXED_QUEUE, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)
        assertEquals(2, result.totalEvents)

        // Both records preserved
        assertNotNull(db.outboxDao().getEvent(e1.companyId.value, e1.clientEventId.value))
        assertNotNull(db.outboxDao().getEvent(e2.companyId.value, e2.clientEventId.value))

        db.close()
    }

    @Test
    fun boundedProbe_largeQueue_honestIntegrityLimitReported_noSilentUpgrade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "bounded-probe-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        // Capture 15 encrypted events
        for (i in 1..15) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                localReceiptNumber = "BOUNDED-$i"
            )
            repo.captureSale(event)
        }

        assertEquals(15, db.outboxDao().eventCount())

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        // Probe with a limit of 5 records
        val result = inspector.inspectHealth(maxProbeCount = 5)

        assertEquals(OutboxHealthStatus.HEALTHY, result.status)
        assertEquals(15, result.totalEvents)
        assertEquals(5, result.probedCount)
        assertTrue(result.isSampleProbe)
        // Honest message check: probe statement reported, does not falsely claim full unprobed queue integrity
        assertTrue(result.messageEn.contains("probe verified 5 records; full unprobed queue integrity is not claimed"))

        db.close()
    }

    @Test
    fun scopePrivacy_foreignCompanyMetadataAndEventIdsHidden() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "privacy-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        crypto.failure = SessionStorageException(SessionStorageError.Locked())
        val cipher = OutboxPayloadCipher(crypto)

        val foreignCompanyUlid = "01JCV8X9MBV4W5X6Y7Z8999999"
        val foreignEventUuid = "99999999-8888-7777-6666-555555555555"

        val event = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.parse(foreignEventUuid),
            companyId = ServerUlid.parse(foreignCompanyUlid)
        )
        // Store directly as encrypted-envelope format with locked crypto
        val entity = OutboxEventEntity(
            clientEventId = event.clientEventId.value,
            companyId = event.companyId.value,
            actorId = event.actorId.value,
            deviceId = event.deviceId.value,
            deviceLeaseId = event.deviceLeaseId.value,
            capturedAt = event.capturedAt,
            payloadDigest = event.computePayloadDigest().value,
            payloadBytes = byteArrayOf(0x53, 0x54, 0x4f, 0x01) + ByteArray(30),
            eventVersion = 1
        )
        db.outboxDao().insertEvent(entity)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth()

        assertEquals(OutboxHealthStatus.ENCRYPTED_LOCKED, result.status)

        // Strict privacy check: foreign company ID and client event UUID must NEVER appear in presentation strings
        assertFalse("Must not expose foreign company ID", result.messageEn.contains(foreignCompanyUlid))
        assertFalse("Must not expose foreign company ID", result.messageAr.contains(foreignCompanyUlid))
        assertFalse("Must not expose foreign event UUID", result.messageEn.contains(foreignEventUuid))
        assertFalse("Must not expose foreign event UUID", result.messageAr.contains(foreignEventUuid))

        db.close()
    }

    @Test
    fun cancellation_propagatesWithoutSwallowing() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "cancel-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        var caughtCancellation = false
        val job = launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.currentCoroutineContext().cancel()
                inspector.inspectHealth()
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }
        job.join()
        assertTrue("CancellationException must propagate cleanly", caughtCancellation)

        db.close()
    }
}
