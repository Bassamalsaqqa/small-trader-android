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
    fun hardLimit_negativeOrHugeMaxProbeCount_boundedToHardCap10() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "probe-bounds-${UUID.randomUUID()}.db")
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

        // Passing negative limit should fall back to default (10)
        val resultNeg = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = -5)
        assertEquals(10, resultNeg.probedCount)

        // Passing huge limit (> 10) should be clamped to hard cap (10)
        val resultHuge = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId, maxProbeCount = 500)
        assertEquals(10, resultHuge.probedCount)

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
}
