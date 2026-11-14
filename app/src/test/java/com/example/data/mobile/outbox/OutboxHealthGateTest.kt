package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ContractConstants
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.session.InstallationIdentityStore
import com.example.data.mobile.session.SessionStorageError
import com.example.data.mobile.session.SessionStorageException
import kotlinx.coroutines.CancellationException
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

    private val defaultCompanyId = "01JCV8X9M2NQW3TYZ4R5S6789A"
    private val defaultActorId = "01JCV8X9M2NQW3TYZ4R5S6789C"
    private val defaultDeviceId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    private fun createLegacyDatabaseFixture(dbFile: File): OutboxDatabase {
        val schemaFile = if (File("schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json").exists()) {
            File("schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json")
        } else {
            File("app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json")
        }
        val schemaJson = schemaFile.readText()
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

        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

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

        // 4. Run scoped health gate inspection
        val result = inspector.inspectHealth(legacyEvent.companyId.value, legacyEvent.actorId.value, legacyEvent.deviceId.value)

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
        val retryResult = inspector.inspectHealth(legacyEvent.companyId.value, legacyEvent.actorId.value, legacyEvent.deviceId.value)
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
        val result = inspector.inspectHealth(sampleEvent.companyId.value, sampleEvent.actorId.value, sampleEvent.deviceId.value)

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
        val result = inspector.inspectHealth(sampleEvent.companyId.value, sampleEvent.actorId.value, sampleEvent.deviceId.value)

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
        val result = inspector.inspectHealth(sampleEvent.companyId.value, sampleEvent.actorId.value, sampleEvent.deviceId.value)

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
        val result = inspector.inspectHealth(sampleEvent.companyId.value, sampleEvent.actorId.value, sampleEvent.deviceId.value)

        assertEquals(OutboxHealthStatus.CORRUPT, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

        val stored = db.outboxDao().getEvent(sampleEvent.companyId.value, sampleEvent.clientEventId.value)
        assertNotNull(stored)
        assertArrayEquals(invalidEnvelope, stored!!.payloadBytes)

        db.close()
    }

    @Test
    fun exact2recordcorruption_firstValidSecondCorruptCipher_mustReturnCorrupt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "exact2corrupt-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        // Row 1: Valid encrypted record
        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "REC-VALID-1")
        repo.captureSale(e1)

        // Row 2: Encrypted with valid STO/1 header but corrupted ciphertext (fails AES-GCM MAC check)
        val e2 = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            localReceiptNumber = "REC-CORRUPT-2"
        )
        repo.captureSale(e2)

        // Corrupt row 2 ciphertext while leaving STO/1 header intact
        val row2OriginalBytes = db.outboxDao().getEvent(e2.companyId.value, e2.clientEventId.value)!!.payloadBytes
        val tamperedBytes = row2OriginalBytes.clone()
        tamperedBytes[tamperedBytes.size - 1] = (tamperedBytes[tamperedBytes.size - 1].toInt() xor 0xFF).toByte()
        db.outboxDao().replacePayloadForConversion(e2.companyId.value, e2.clientEventId.value, tamperedBytes)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(e1.companyId.value, e1.actorId.value, e1.deviceId.value)

        // Authentication of EVERY row is enforced: second row failure MUST trigger CORRUPT status!
        assertEquals(OutboxHealthStatus.CORRUPT, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

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

        // Insert one legacy plaintext event in same scope
        val e2 = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            companyId = e1.companyId,
            actorId = e1.actorId,
            deviceId = e1.deviceId,
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
        val result = inspector.inspectHealth(e1.companyId.value, e1.actorId.value, e1.deviceId.value)

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
    fun boundedProbe_largeQueue_returnsPartialUnchecked_explicitlyNotOperational() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "bounded-probe-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
        val actId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
        val devId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6")

        // Capture 15 encrypted events in scope
        for (i in 1..15) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                localReceiptNumber = "BOUNDED-$i"
            )
            repo.captureSale(event)
        }

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        // Probe with a limit of 5 records
        val result = inspector.inspectHealth(compId.value, actId.value, devId.value, maxProbeCount = 5)

        // A sample probe of a larger queue CANNOT grant full queue operational readiness!
        assertEquals(OutboxHealthStatus.PARTIAL_UNCHECKED, result.status)
        assertEquals(15, result.totalEvents)
        assertEquals(5, result.probedCount)
        assertTrue(result.isSampleProbe)
        assertFalse("Sample probe MUST NOT grant queue processing readiness", result.isOperational)
        assertTrue(result.canRetry)

        db.close()
    }

    @Test
    fun scopeIsolation_companyALegacy_companyBHealthyOrEmpty_neverBlocksCompanyB() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "scope-iso-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)

        val companyA = "01JCV8X9M2NQW3TYZ4R5S6789A"
        val companyB = "01JCV8X9M2NQW3TYZ4R5S6789B"
        val actor = "01JCV8X9M2NQW3TYZ4R5S6789C"
        val device = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

        // Insert legacy plaintext event for Company A
        val eventA = OutboxTestFixtures.createSampleEvent(
            companyId = ServerUlid.parse(companyA),
            actorId = ServerUlid.parse(actor),
            deviceId = ClientUuid.parse(device),
            localReceiptNumber = "COMPA-LEGACY"
        )
        val plainBytesA = CanonicalJsonSerializer.serialize(eventA).toByteArray(Charsets.UTF_8)
        val entityA = OutboxEventEntity(
            clientEventId = eventA.clientEventId.value,
            companyId = companyA,
            actorId = actor,
            deviceId = device,
            deviceLeaseId = eventA.deviceLeaseId.value,
            capturedAt = eventA.capturedAt,
            payloadDigest = eventA.computePayloadDigest().value,
            payloadBytes = plainBytesA,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(entityA)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        // Inspect Company A: blocked as LEGACY_PLAINTEXT
        val resultA = inspector.inspectHealth(companyA, actor, device)
        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, resultA.status)
        assertFalse(resultA.isOperational)

        // Inspect Company B: completely empty, operational NEW_EMPTY! Never blocked by Company A!
        val resultB = inspector.inspectHealth(companyB, actor, device)
        assertEquals(OutboxHealthStatus.NEW_EMPTY, resultB.status)
        assertTrue(resultB.isOperational)
        assertEquals(0, resultB.totalEvents)

        db.close()
    }

    @Test
    fun unauthorizedInspection_blankScope_failsClosed_noQueries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "unauth-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val cipher = OutboxPayloadCipher(JvmOutboxCrypto())
        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        val resultBlankCompany = inspector.inspectHealth("", defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.STORAGE_UNAVAILABLE, resultBlankCompany.status)
        assertFalse(resultBlankCompany.isOperational)

        val resultBlankActor = inspector.inspectHealth(defaultCompanyId, "", defaultDeviceId)
        assertEquals(OutboxHealthStatus.STORAGE_UNAVAILABLE, resultBlankActor.status)

        val resultBlankDevice = inspector.inspectHealth(defaultCompanyId, defaultActorId, "")
        assertEquals(OutboxHealthStatus.STORAGE_UNAVAILABLE, resultBlankDevice.status)

        db.close()
    }

    @Test
    fun oversizedPayloadCell_boundedProjection_returnsCorruptWithoutAllocatingWhole() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "oversized-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val cipher = OutboxPayloadCipher(JvmOutboxCrypto())

        // Insert event whose payload size exceeds max envelope (1 MiB + 32 bytes)
        val oversizedLen = ContractConstants.MAX_PAYLOAD_BYTES + 100
        val oversizedBytes = ByteArray(oversizedLen)
        oversizedBytes[0] = 0x53
        oversizedBytes[1] = 0x54
        oversizedBytes[2] = 0x4f
        oversizedBytes[3] = 0x01

        val entity = OutboxEventEntity(
            clientEventId = UUID.randomUUID().toString(),
            companyId = defaultCompanyId,
            actorId = defaultActorId,
            deviceId = defaultDeviceId,
            deviceLeaseId = UUID.randomUUID().toString(),
            capturedAt = "2026-11-13T20:00:00.000000Z",
            payloadDigest = "0000000000000000000000000000000000000000000000000000000000000000",
            payloadBytes = oversizedBytes,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(entity)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(OutboxHealthStatus.CORRUPT, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

        db.close()
    }

    @Test
    fun negativeOrExplicitProbeCount_verifiesAcrossPagesOrClampsToRequestedLimit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "probe-limits-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..15) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                localReceiptNumber = "PROBE-$i"
            )
            repo.captureSale(event)
        }

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        // Passing negative/zero limit falls back to full verification across all scoped rows via pages
        val resultNeg = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = -5)
        assertEquals(15, resultNeg.probedCount)
        assertEquals(15, resultNeg.totalEvents)
        assertEquals(OutboxHealthStatus.HEALTHY, resultNeg.status)
        assertTrue(resultNeg.isOperational)

        // Explicit bounded probe limit (e.g. 5) restricts probing to requested limit
        val resultBounded = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 5)
        assertEquals(5, resultBounded.probedCount)
        assertEquals(15, resultBounded.totalEvents)
        assertTrue(resultBounded.isSampleProbe)
        assertEquals(OutboxHealthStatus.PARTIAL_UNCHECKED, resultBounded.status)
        assertFalse(resultBounded.isOperational)

        // Passing large limit (e.g. 500) verifies up to min(limit, total) = 15 rows across pages
        val resultHuge = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 500)
        assertEquals(15, resultHuge.probedCount)
        assertEquals(OutboxHealthStatus.HEALTHY, resultHuge.status)
        assertTrue(resultHuge.isOperational)

        db.close()
    }

    @Test
    fun zeroKeygen_and_zeroDatabaseWrites_duringHealthInspection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "zero-writes-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val initialKeyGenerations = crypto.keyGenerationCount
        val cipher = OutboxPayloadCipher(crypto)
        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        val initialEventCount = db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(0, initialEventCount)

        // Run inspection multiple times
        for (i in 1..3) {
            inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        }

        // Verify zero key generations occurred
        assertEquals(initialKeyGenerations, crypto.keyGenerationCount)

        // Verify zero database modifications occurred
        val finalEventCount = db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(0, finalEventCount)

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
                inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }
        job.join()
        assertTrue("CancellationException must propagate cleanly", caughtCancellation)

        db.close()
    }

    @Test
    fun raceTwoConnectionsDeterministicBarrier_cannotFalseHealthyFromInconsistentSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "race-barrier-${UUID.randomUUID()}.db")
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo1 = OutboxRepositoryImpl(db1, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        // Insert exactly 10 valid encrypted events
        for (i in 1..10) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T20:00:%02d.000000Z".format(i),
                localReceiptNumber = "RACE-$i"
            )
            repo1.captureSale(event)
        }

        // Open Connection 2 to the same database file
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        // Under single Room read transaction:
        // Inside Connection 1's transaction, snapshot consistency guarantees it evaluates count=10, sample=10
        // atomically as a coherent snapshot, producing HEALTHY (operational).
        val inspector = DefaultOutboxHealthInspector(
            database = db1,
            payloadCipher = cipher,
            ioDispatcher = Dispatchers.IO
        )
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 10)
        assertEquals(10, result.totalEvents)
        assertEquals(10, result.probedCount)
        assertEquals(OutboxHealthStatus.HEALTHY, result.status)
        assertTrue(result.isOperational)

        // SQLite writers serialize appropriately: Connection 2 inserts an 11th corrupt event
        val corruptBytes = "STO\u0001CORRUPT-MAC-OR-CIPHERTEXT-INSUFFICIENT-LENGTH".toByteArray(Charsets.UTF_8)
        val entity = OutboxEventEntity(
            clientEventId = UUID.randomUUID().toString(),
            companyId = defaultCompanyId,
            actorId = defaultActorId,
            deviceId = defaultDeviceId,
            deviceLeaseId = UUID.randomUUID().toString(),
            capturedAt = "2026-11-13T20:00:59.000000Z",
            payloadDigest = "0000000000000000000000000000000000000000000000000000000000000000",
            payloadBytes = corruptBytes,
            eventVersion = 1
        )
        db2.outboxDao().insertEvent(entity)

        // Inspecting again with maxProbeCount = 10 observes the 11th record and reports PARTIAL_UNCHECKED (never false healthy)
        val subsequentResult = DefaultOutboxHealthInspector(db1, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 10)
        assertEquals(11, subsequentResult.totalEvents)
        assertEquals(10, subsequentResult.probedCount)
        assertTrue(subsequentResult.isSampleProbe)
        assertEquals(OutboxHealthStatus.PARTIAL_UNCHECKED, subsequentResult.status)
        assertFalse(subsequentResult.isOperational)
        assertFalse(subsequentResult.isQueueProcessingReady)

        // Inspecting without probe limit across all 11 records detects the 11th corrupt event and reports CORRUPT
        val fullProbeResult = DefaultOutboxHealthInspector(db1, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = -1)
        assertEquals(11, fullProbeResult.totalEvents)
        assertEquals(OutboxHealthStatus.CORRUPT, fullProbeResult.status)
        assertFalse(fullProbeResult.isOperational)

        db1.close()
        db2.close()
    }

    @Test
    fun fatalRoomTransactionRollback_healthInspectionFailsClosed_leavesDatabaseDataUnchanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "fatal-rollback-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..3) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                localReceiptNumber = "ROLLBACK-$i"
            )
            repo.captureSale(event)
        }

        assertEquals(3, db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId))

        // Hook throws runtime exception simulating unexpected failure inside Room transaction
        val throwingInspector = DefaultOutboxHealthInspector(
            database = db,
            payloadCipher = cipher,
            ioDispatcher = Dispatchers.IO,
            onSnapshotReadHook = {
                throw IllegalStateException("Simulated disk error during transaction read")
            }
        )

        val result = throwingInspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.STORAGE_UNAVAILABLE, result.status)
        assertFalse(result.isOperational)
        assertTrue(result.canRetry)

        // Verify database data remains 100% unchanged after transaction rollback
        assertEquals(3, db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId))

        db.close()
    }

    @Test
    fun emptyQueueWithPersistedAuthLock_failsOperational_returnsAuthBlocked_noKeystoreCreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "empty-locked-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val initialKeyGenerations = crypto.keyGenerationCount
        val cipher = OutboxPayloadCipher(crypto)

        // Queue has 0 events, but is explicitly locked due to HTTP 401 auth denial
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = defaultCompanyId,
                actorId = defaultActorId,
                deviceId = defaultDeviceId,
                lockedAt = System.currentTimeMillis(),
                reason = "HTTP 401: Authentication denied"
            )
        )
        assertTrue(db.outboxDao().isScopeLocked(defaultCompanyId, defaultActorId, defaultDeviceId))

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(OutboxHealthStatus.AUTH_BLOCKED, result.status)
        assertEquals(0, result.totalEvents)
        assertEquals(0, result.probedCount)
        assertFalse(result.isSampleProbe)
        assertTrue(result.isScopeLocked)
        assertFalse("Locked empty queue must NEVER be operational", result.isOperational)
        assertFalse("Locked empty queue must NEVER be queue processing ready", result.isQueueProcessingReady)
        assertTrue(result.canRetry)
        assertEquals(initialKeyGenerations, crypto.keyGenerationCount)

        db.close()
    }

    @Test
    fun nonEmptyEncryptedQueueWithPersistedAuthLock_failsOperational_returnsAuthBlocked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "nonempty-locked-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..3) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                localReceiptNumber = "LOCKED-$i"
            )
            repo.captureSale(event)
        }

        // All 3 events are valid encrypted records on disk
        // Lock queue on HTTP 401
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = defaultCompanyId,
                actorId = defaultActorId,
                deviceId = defaultDeviceId,
                lockedAt = System.currentTimeMillis(),
                reason = "HTTP 401: Session revoked"
            )
        )

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(OutboxHealthStatus.AUTH_BLOCKED, result.status)
        assertEquals(3, result.totalEvents)
        assertEquals(3, result.probedCount)
        assertTrue(result.isScopeLocked)
        assertFalse("Locked populated queue must not be operational", result.isOperational)
        assertFalse("Locked populated queue must not be queue processing ready", result.isQueueProcessingReady)
        assertTrue(result.canRetry)

        db.close()
    }

    @Test
    fun otherScopeAuthLockIsolation_doesNotLockUnrelatedTenantScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "lock-isolation-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)

        val foreignCompanyId = "01JCV8X9M2NQW3TYZ4R5S6789B"

        // Lock defaultCompanyId only
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = defaultCompanyId,
                actorId = defaultActorId,
                deviceId = defaultDeviceId,
                lockedAt = System.currentTimeMillis(),
                reason = "HTTP 401: Tenant A revoked"
            )
        )

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        // defaultCompanyId is locked
        val resultA = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.AUTH_BLOCKED, resultA.status)
        assertTrue(resultA.isScopeLocked)
        assertFalse(resultA.isOperational)

        // foreignCompanyId is NOT locked: reports NEW_EMPTY and is operational
        val resultB = inspector.inspectHealth(foreignCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.NEW_EMPTY, resultB.status)
        assertFalse(resultB.isScopeLocked)
        assertTrue(resultB.isOperational)
        assertTrue(resultB.isQueueProcessingReady)

        db.close()
    }

    @Test
    fun persistedAuthLockPersistsAcrossCloseAndReopen_healthInspectionStillAuthBlocked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "reopen-lock-${UUID.randomUUID()}.db")
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        db1.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = defaultCompanyId,
                actorId = defaultActorId,
                deviceId = defaultDeviceId,
                lockedAt = System.currentTimeMillis(),
                reason = "HTTP 401: Persisted across reopen"
            )
        )
        db1.close()

        // Reopen database from disk
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val inspector = DefaultOutboxHealthInspector(db2, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.AUTH_BLOCKED, result.status)
        assertTrue(result.isScopeLocked)
        assertFalse(result.isOperational)
        assertFalse(result.isQueueProcessingReady)

        db2.close()
    }

    @Test
    fun pagedVerification_11CleanRows_verifiesAll_returnsHealthyAndOperational() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "paged-11-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..11) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T21:00:%02d.000000Z".format(i),
                localReceiptNumber = "PAGE11-$i"
            )
            repo.captureSale(event)
        }

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(11, result.totalEvents)
        assertEquals(11, result.probedCount)
        assertFalse(result.isSampleProbe)
        assertEquals(OutboxHealthStatus.HEALTHY, result.status)
        assertTrue(result.isOperational)
        assertTrue(result.isQueueProcessingReady)
        assertFalse(result.canRetry)

        db.close()
    }

    @Test
    fun pagedVerification_100CleanRows_verifiesAll_returnsHealthyAndOperational() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "paged-100-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..100) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T22:%02d:%02d.000000Z".format(i / 60, i % 60),
                localReceiptNumber = "PAGE100-$i"
            )
            repo.captureSale(event)
        }

        var pagesSeen = 0
        val inspector = DefaultOutboxHealthInspector(
            database = db,
            payloadCipher = cipher,
            ioDispatcher = Dispatchers.IO,
            onPageReadHook = { pagesSeen++ }
        )
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(100, result.totalEvents)
        assertEquals(100, result.probedCount)
        assertFalse(result.isSampleProbe)
        assertEquals(OutboxHealthStatus.HEALTHY, result.status)
        assertTrue(result.isOperational)
        assertTrue(result.isQueueProcessingReady)
        assertTrue("Must page through in chunks of 10", pagesSeen >= 10)

        db.close()
    }

    @Test
    fun pagedVerification_lateCorruptRow_failsClosed_returnsCorrupt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "late-corrupt-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        // 10 valid encrypted events
        for (i in 1..10) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T23:00:%02d.000000Z".format(i),
                localReceiptNumber = "CLEAN-$i"
            )
            repo.captureSale(event)
        }

        // 11th row is corrupt on page 2
        val corruptBytes = "STO\u0001CORRUPT-MAC-OR-CIPHERTEXT-INSUFFICIENT-LENGTH".toByteArray(Charsets.UTF_8)
        val corruptEntity = OutboxEventEntity(
            clientEventId = UUID.randomUUID().toString(),
            companyId = defaultCompanyId,
            actorId = defaultActorId,
            deviceId = defaultDeviceId,
            deviceLeaseId = UUID.randomUUID().toString(),
            capturedAt = "2026-11-13T23:00:59.000000Z",
            payloadDigest = "0000000000000000000000000000000000000000000000000000000000000000",
            payloadBytes = corruptBytes,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(corruptEntity)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(11, result.totalEvents)
        assertEquals(OutboxHealthStatus.CORRUPT, result.status)
        assertFalse(result.isOperational)
        assertFalse(result.isQueueProcessingReady)
        assertTrue(result.canRetry)

        db.close()
    }

    @Test
    fun pagedVerification_lateLegacyRow_failsClosed_returnsMixedQueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "late-legacy-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..10) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T23:10:%02d.000000Z".format(i),
                localReceiptNumber = "CLEAN-$i"
            )
            repo.captureSale(event)
        }

        // 11th row is legacy plaintext
        val legacyEvent = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            companyId = compId,
            actorId = actId,
            deviceId = devId,
            capturedAt = "2026-11-13T23:10:59.000000Z",
            localReceiptNumber = "LEGACY-11"
        )
        val plainBytes = CanonicalJsonSerializer.serialize(legacyEvent).toByteArray(Charsets.UTF_8)
        val legacyEntity = OutboxEventEntity(
            clientEventId = legacyEvent.clientEventId.value,
            companyId = defaultCompanyId,
            actorId = defaultActorId,
            deviceId = defaultDeviceId,
            deviceLeaseId = legacyEvent.deviceLeaseId.value,
            capturedAt = legacyEvent.capturedAt,
            payloadDigest = legacyEvent.computePayloadDigest().value,
            payloadBytes = plainBytes,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(legacyEntity)

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(11, result.totalEvents)
        assertEquals(OutboxHealthStatus.MIXED_QUEUE, result.status)
        assertFalse(result.isOperational)
        assertFalse(result.isQueueProcessingReady)

        db.close()
    }

    @Test
    fun pagedVerification_scanMutation_concurrentInsertion_failsClosedAsPartialUnchecked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "scan-mutation-${UUID.randomUUID()}.db")
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo1 = OutboxRepositoryImpl(db1, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..10) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T23:20:%02d.000000Z".format(i),
                localReceiptNumber = "MUT-$i"
            )
            repo1.captureSale(event)
        }

        val db2 = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val repo2 = OutboxRepositoryImpl(db2, payloadCipher = cipher)

        // Initial snapshot with 10 records verifies completely
        val initialResult = DefaultOutboxHealthInspector(db1, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 10)
        assertEquals(10, initialResult.totalEvents)
        assertEquals(10, initialResult.probedCount)
        assertEquals(OutboxHealthStatus.HEALTHY, initialResult.status)
        assertTrue(initialResult.isOperational)

        // SQLite writers serialize: Connection 2 captures an 11th event
        val event11 = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            companyId = compId,
            actorId = actId,
            deviceId = devId,
            capturedAt = "2026-11-13T23:20:59.000000Z",
            localReceiptNumber = "MUT-11"
        )
        repo2.captureSale(event11)

        // Subsequent inspection with bounded probe limit (10) fails closed as PARTIAL_UNCHECKED
        val boundedResult = DefaultOutboxHealthInspector(db1, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 10)
        assertEquals(11, boundedResult.totalEvents)
        assertEquals(10, boundedResult.probedCount)
        assertTrue(boundedResult.isSampleProbe)
        assertEquals(OutboxHealthStatus.PARTIAL_UNCHECKED, boundedResult.status)
        assertFalse(boundedResult.isOperational)
        assertFalse(boundedResult.isQueueProcessingReady)
        assertTrue(boundedResult.canRetry)

        // Full paged inspection with no probe limit verifies all 11 clean events across pages
        val retryResult = DefaultOutboxHealthInspector(db1, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(11, retryResult.totalEvents)
        assertEquals(11, retryResult.probedCount)
        assertFalse(retryResult.isSampleProbe)
        assertEquals(OutboxHealthStatus.HEALTHY, retryResult.status)
        assertTrue(retryResult.isOperational)
        assertTrue(retryResult.isQueueProcessingReady)

        db1.close()
        db2.close()
    }

    @Test
    fun pagedVerification_cancellation_abortsImmediatelyCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "cancel-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        for (i in 1..20) {
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-13T23:30:%02d.000000Z".format(i),
                localReceiptNumber = "CANCEL-$i"
            )
            repo.captureSale(event)
        }

        var cancelledCaught = false
        val job = launch(Dispatchers.IO) {
            val inspector = DefaultOutboxHealthInspector(
                database = db,
                payloadCipher = cipher,
                ioDispatcher = Dispatchers.IO,
                onPageReadHook = { pageIndex ->
                    if (pageIndex == 0) {
                        cancel()
                    }
                }
            )
            try {
                inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
            } catch (e: CancellationException) {
                cancelledCaught = true
                throw e
            }
        }
        job.join()
        assertTrue("CancellationException must be thrown and caught", cancelledCaught)
        assertTrue("Job must be cancelled", job.isCancelled)

        // Database remains intact and fully verifiable after cancellation
        val postCancelResult = DefaultOutboxHealthInspector(db, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(20, postCancelResult.totalEvents)
        assertEquals(20, postCancelResult.probedCount)
        assertEquals(OutboxHealthStatus.HEALTHY, postCancelResult.status)
        assertTrue(postCancelResult.isOperational)

        db.close()
    }

    @Test
    fun sameDb_companyALegacyNoKey_companyBEmpty_refusesCaptureReady_actualRepositoryCaptureFailsWithoutMintingKey() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "samedb-missingkey-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val companyA = "01JCV8X9M2NQW3TYZ4R5S6789A"
        val companyB = "01JCV8X9M2NQW3TYZ4R5S6789B"
        val actor = "01JCV8X9M2NQW3TYZ4R5S6789C"
        val device = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

        // Insert legacy plaintext event for Company A
        val eventA = OutboxTestFixtures.createSampleEvent(
            companyId = ServerUlid.parse(companyA),
            actorId = ServerUlid.parse(actor),
            deviceId = ClientUuid.parse(device),
            localReceiptNumber = "COMPA-LEGACY"
        )
        val plainBytesA = CanonicalJsonSerializer.serialize(eventA).toByteArray(Charsets.UTF_8)
        val entityA = OutboxEventEntity(
            clientEventId = eventA.clientEventId.value,
            companyId = companyA,
            actorId = actor,
            deviceId = device,
            deviceLeaseId = eventA.deviceLeaseId.value,
            capturedAt = eventA.capturedAt,
            payloadDigest = eventA.computePayloadDigest().value,
            payloadBytes = plainBytesA,
            eventVersion = 1
        )
        db.outboxDao().insertEvent(entityA)
        assertEquals(1, db.outboxDao().eventCount())

        // Simulate missing encryption key on this legacy install (e.g. no key in KeyStore)
        crypto.missing = true
        val initialKeyGenerations = crypto.keyGenerationCount

        val inspector = DefaultOutboxHealthInspector(db, payloadCipher = cipher, ioDispatcher = Dispatchers.IO)

        // Company A: blocked as LEGACY_PLAINTEXT
        val resultA = inspector.inspectHealth(companyA, actor, device)
        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, resultA.status)
        assertFalse(resultA.isOperational)

        // Company B has 0 scoped events, but same DB has global eventCount > 0 and key is missing:
        // Must NOT claim capture ready (NEW_EMPTY / isOperational=true)!
        val resultB = inspector.inspectHealth(companyB, actor, device)
        assertEquals(
            "Company B must not report NEW_EMPTY when DB has global records and no encryption key exists",
            OutboxHealthStatus.KEY_UNAVAILABLE,
            resultB.status
        )
        assertFalse("Company B is not operational without a retained encryption key", resultB.isOperational)
        assertFalse(resultB.isQueueProcessingReady)
        assertEquals(0, resultB.totalEvents)

        // Test ACTUAL repository capture for Company B:
        // Because global eventCount() > 0 and key is missing, captureSale MUST fail closed with KEY_UNAVAILABLE.
        val eventB = OutboxTestFixtures.createSampleEvent(
            companyId = ServerUlid.parse(companyB),
            actorId = ServerUlid.parse(actor),
            deviceId = ClientUuid.parse(device),
            localReceiptNumber = "COMPB-FIRST"
        )

        var captureExceptionCaught = false
        try {
            repo.captureSale(eventB)
        } catch (e: OutboxStorageException) {
            captureExceptionCaught = true
            assertEquals(OutboxStorageError.KEY_UNAVAILABLE, e.error)
        }
        assertTrue("Actual repository capture must fail closed with KEY_UNAVAILABLE", captureExceptionCaught)

        // Invariant: Missing-key refusal preserved, NO replacement key minted
        assertEquals(
            "Must NEVER mint a replacement key over existing records on disk",
            initialKeyGenerations,
            crypto.keyGenerationCount
        )

        // Invariant: Company A legacy event in DB is strictly unchanged
        val storedA = db.outboxDao().getEvent(companyA, eventA.clientEventId.value)
        assertNotNull(storedA)
        assertTrue(storedA!!.payloadBytes.contentEquals(plainBytesA))

        // When retained key is available: Company B is NEW_EMPTY and CAN capture
        crypto.missing = false
        val resultBWithKey = inspector.inspectHealth(companyB, actor, device)
        assertEquals(OutboxHealthStatus.NEW_EMPTY, resultBWithKey.status)
        assertTrue(resultBWithKey.isOperational)
        assertTrue(resultBWithKey.isQueueProcessingReady)

        val captureResultB = repo.captureSale(eventB)
        assertTrue(captureResultB is CaptureResult.Created)

        db.close()
    }

    @Test
    fun pagedVerification_sameCountCorruptPayloadReplacement_failsClosedAsCorrupt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "same-count-corrupt-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val compId = ServerUlid.parse(defaultCompanyId)
        val actId = ServerUlid.parse(defaultActorId)
        val devId = ClientUuid.parse(defaultDeviceId)

        var replaceTargetId: String? = null
        for (i in 1..10) {
            val eventId = ClientUuid.random()
            if (i == 5) replaceTargetId = eventId.value
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = eventId,
                companyId = compId,
                actorId = actId,
                deviceId = devId,
                capturedAt = "2026-11-14T01:00:%02d.000000Z".format(i),
                localReceiptNumber = "SAMECOUNT-$i"
            )
            repo.captureSale(event)
        }

        // Verify count is 10 initially
        assertEquals(10, db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId))

        // Replace row 5 with corrupt payload (same count = 10!)
        val corruptPayload = "STO\u0001CORRUPT-SAME-COUNT-REPLACEMENT-MAC-FAIL".toByteArray(Charsets.UTF_8)
        db.outboxDao().replacePayloadForConversion(defaultCompanyId, replaceTargetId!!, corruptPayload)

        // Event count remains strictly 10
        assertEquals(10, db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId))

        // Health inspection must non-vacuously detect corruption despite count remaining unchanged
        val inspector = DefaultOutboxHealthInspector(db, cipher, Dispatchers.IO)
        val result = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(10, result.totalEvents)
        assertEquals(OutboxHealthStatus.CORRUPT, result.status)
        assertFalse("Same-count corrupt payload replacement must NOT be operational", result.isOperational)
        assertFalse(result.isQueueProcessingReady)
        assertTrue(result.canRetry)

        db.close()
    }

    @Test
    fun emptyScopeWithAuthLockAndCorruptRow_returnsAuthBlocked_neverNewEmptyReady() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "empty-authlock-${UUID.randomUUID()}.db")
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)

        // Scope starts with 0 events
        assertEquals(0, db.outboxDao().scopedEventCount(defaultCompanyId, defaultActorId, defaultDeviceId))

        // Insert persisted auth lock for this scope
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = defaultCompanyId,
                actorId = defaultActorId,
                deviceId = defaultDeviceId,
                lockedAt = System.currentTimeMillis(),
                reason = "HTTP 401 unauthorized test denial"
            )
        )

        // Inspect health: must return AUTH_BLOCKED, never NEW_EMPTY
        val inspector = DefaultOutboxHealthInspector(db, cipher, Dispatchers.IO)
        val resultLockedEmpty = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)

        assertEquals(OutboxHealthStatus.AUTH_BLOCKED, resultLockedEmpty.status)
        assertTrue(resultLockedEmpty.isScopeLocked)
        assertFalse("Auth-locked empty queue must never be operational", resultLockedEmpty.isOperational)
        assertFalse(resultLockedEmpty.isQueueProcessingReady)
        assertNotEquals(OutboxHealthStatus.NEW_EMPTY, resultLockedEmpty.status)

        // Now insert a corrupt row into this auth-locked queue
        val corruptBytes = "STO\u0001CORRUPT-PAYLOAD-INSUFFICIENT-LENGTH".toByteArray(Charsets.UTF_8)
        db.outboxDao().insertEvent(
            OutboxEventEntity(
                clientEventId = UUID.randomUUID().toString(),
                companyId = defaultCompanyId,
                actorId = defaultActorId,
                deviceId = defaultDeviceId,
                deviceLeaseId = UUID.randomUUID().toString(),
                capturedAt = "2026-11-14T01:30:00.000000Z",
                payloadDigest = "0000000000000000000000000000000000000000000000000000000000000000",
                payloadBytes = corruptBytes,
                eventVersion = 1
            )
        )

        // Re-inspect: queue is non-empty, scope is locked and row is corrupt: must fail operational readiness
        val resultLockedCorrupt = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertTrue(resultLockedCorrupt.isScopeLocked)
        assertFalse(resultLockedCorrupt.isOperational)
        assertFalse(resultLockedCorrupt.isQueueProcessingReady)
        assertNotEquals(OutboxHealthStatus.NEW_EMPTY, resultLockedCorrupt.status)

        db.close()
    }
}
