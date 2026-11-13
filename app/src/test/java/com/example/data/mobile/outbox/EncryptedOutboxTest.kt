package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.OfflineSaleEvent
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.session.AndroidKeyStoreCryptoEngine
import com.example.data.mobile.session.KeyStoreBackend
import com.example.data.mobile.session.SessionProcessLocks
import com.example.data.mobile.session.SessionStorageError
import com.example.data.mobile.session.SessionStorageException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedOutboxTest {

    private data class DatabaseSnapshot(
        val events: List<OutboxEventEntity>,
        val deliveryStates: List<OutboxDeliveryStateEntity>,
        val scopeLocks: List<OutboxScopeLockEntity>
    )

    private fun takeSnapshot(db: OutboxDatabase): DatabaseSnapshot {
        val events = db.outboxDao().allEventsForConversion().sortedBy { it.clientEventId }
        val states = mutableListOf<OutboxDeliveryStateEntity>()
        val cursorStates = db.openHelper.readableDatabase.query(
            "SELECT * FROM outbox_delivery_states ORDER BY client_event_id ASC"
        )
        cursorStates.use { c ->
            val clientEventIdIdx = c.getColumnIndexOrThrow("client_event_id")
            val companyIdIdx = c.getColumnIndexOrThrow("company_id")
            val actorIdIdx = c.getColumnIndexOrThrow("actor_id")
            val deviceIdIdx = c.getColumnIndexOrThrow("device_id")
            val statusIdx = c.getColumnIndexOrThrow("status")
            val attemptCountIdx = c.getColumnIndexOrThrow("attempt_count")
            val lastAttemptAtIdx = c.getColumnIndexOrThrow("last_attempt_at")
            val claimTokenIdx = c.getColumnIndexOrThrow("claim_token")
            val claimExpiresAtIdx = c.getColumnIndexOrThrow("claim_expires_at")
            val invoiceUlidIdx = c.getColumnIndexOrThrow("invoice_ulid")
            val paymentUlidIdx = c.getColumnIndexOrThrow("payment_ulid")
            val serverUlidIdx = c.getColumnIndexOrThrow("server_ulid")
            val serverAcknowledgedAtIdx = c.getColumnIndexOrThrow("server_acknowledged_at")
            val errorMessageIdx = c.getColumnIndexOrThrow("error_message")
            val createdAtIdx = c.getColumnIndexOrThrow("created_at")
            val updatedAtIdx = c.getColumnIndexOrThrow("updated_at")
            while (c.moveToNext()) {
                states.add(
                    OutboxDeliveryStateEntity(
                        clientEventId = c.getString(clientEventIdIdx),
                        companyId = c.getString(companyIdIdx),
                        actorId = c.getString(actorIdIdx),
                        deviceId = c.getString(deviceIdIdx),
                        status = c.getString(statusIdx),
                        attemptCount = c.getInt(attemptCountIdx),
                        lastAttemptAt = if (c.isNull(lastAttemptAtIdx)) null else c.getLong(lastAttemptAtIdx),
                        claimToken = if (c.isNull(claimTokenIdx)) null else c.getString(claimTokenIdx),
                        claimExpiresAt = if (c.isNull(claimExpiresAtIdx)) null else c.getLong(claimExpiresAtIdx),
                        invoiceUlid = if (c.isNull(invoiceUlidIdx)) null else c.getString(invoiceUlidIdx),
                        paymentUlid = if (c.isNull(paymentUlidIdx)) null else c.getString(paymentUlidIdx),
                        serverUlid = if (c.isNull(serverUlidIdx)) null else c.getString(serverUlidIdx),
                        serverAcknowledgedAt = if (c.isNull(serverAcknowledgedAtIdx)) null else c.getString(serverAcknowledgedAtIdx),
                        errorMessage = if (c.isNull(errorMessageIdx)) null else c.getString(errorMessageIdx),
                        createdAt = c.getLong(createdAtIdx),
                        updatedAt = c.getLong(updatedAtIdx)
                    )
                )
            }
        }
        val locks = mutableListOf<OutboxScopeLockEntity>()
        val cursorLocks = db.openHelper.readableDatabase.query(
            "SELECT * FROM outbox_scope_locks ORDER BY company_id, actor_id, device_id ASC"
        )
        cursorLocks.use { c ->
            val companyIdIdx = c.getColumnIndexOrThrow("company_id")
            val actorIdIdx = c.getColumnIndexOrThrow("actor_id")
            val deviceIdIdx = c.getColumnIndexOrThrow("device_id")
            val lockedAtIdx = c.getColumnIndexOrThrow("locked_at")
            val reasonIdx = c.getColumnIndexOrThrow("reason")
            while (c.moveToNext()) {
                locks.add(
                    OutboxScopeLockEntity(
                        companyId = c.getString(companyIdIdx),
                        actorId = c.getString(actorIdIdx),
                        deviceId = c.getString(deviceIdIdx),
                        lockedAt = c.getLong(lockedAtIdx),
                        reason = c.getString(reasonIdx)
                    )
                )
            }
        }
        return DatabaseSnapshot(events, states, locks)
    }

    private fun assertSnapshotsEqual(expected: DatabaseSnapshot, actual: DatabaseSnapshot) {
        assertEquals("Events count mismatch", expected.events.size, actual.events.size)
        for (i in expected.events.indices) {
            val e1 = expected.events[i]
            val e2 = actual.events[i]
            assertEquals(e1.clientEventId, e2.clientEventId)
            assertEquals(e1.companyId, e2.companyId)
            assertEquals(e1.actorId, e2.actorId)
            assertEquals(e1.deviceId, e2.deviceId)
            assertEquals(e1.deviceLeaseId, e2.deviceLeaseId)
            assertEquals(e1.capturedAt, e2.capturedAt)
            assertEquals(e1.payloadDigest, e2.payloadDigest)
            assertEquals(e1.eventVersion, e2.eventVersion)
            assertArrayEquals("Payload bytes mismatch for ${e1.clientEventId}", e1.payloadBytes, e2.payloadBytes)
        }
        assertEquals("Delivery states mismatch", expected.deliveryStates, actual.deliveryStates)
        assertEquals("Scope locks mismatch", expected.scopeLocks, actual.scopeLocks)
    }

    private fun entity(): OutboxEventEntity {
        val e = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "SECRET-RECEIPT-83917")
        val bytes = CanonicalJsonSerializer.serialize(e).toByteArray()
        return OutboxEventEntity(e.clientEventId.value, e.companyId.value, e.actorId.value,
            e.deviceId.value, e.deviceLeaseId.value, e.capturedAt, e.computePayloadDigest().value, bytes, 1)
    }

    @Test
    fun exactBytesNonceTamperScopeAndBounds() {
        val cipher = OutboxPayloadCipher(JvmOutboxCrypto())
        val event = entity()
        val encrypted = cipher.encrypt(event)
        assertArrayEquals(event.payloadBytes, cipher.decrypt(encrypted).payloadBytes)
        assertFalse(encrypted.payloadBytes.contentEquals(cipher.encrypt(event).payloadBytes))
        for (index in listOf(4, 16, encrypted.payloadBytes.lastIndex)) {
            val changed = encrypted.payloadBytes.apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertThrows(OutboxStorageException::class.java) { cipher.decrypt(encrypted.withPayload(changed)) }
        }
        val moved = OutboxEventEntity(encrypted.clientEventId, encrypted.companyId, "different-actor",
            encrypted.deviceId, encrypted.deviceLeaseId, encrypted.capturedAt, encrypted.payloadDigest,
            encrypted.payloadBytes, 2)
        assertThrows(OutboxStorageException::class.java) { cipher.decrypt(moved) }
        assertThrows(OutboxStorageException::class.java) { cipher.decrypt(event) }
        assertThrows(OutboxStorageException::class.java) { cipher.encrypt(event.withPayload(ByteArray(1048577))) }
    }

    @Test
    fun missingInvalidCancellationAndFatalPreserveCiphertext() {
        val engine = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(engine)
        val event = cipher.encrypt(entity())
        val original = event.payloadBytes
        engine.missing = true
        assertThrows(OutboxStorageException::class.java) { cipher.decrypt(event) }
        engine.missing = false
        engine.failure = com.example.data.mobile.session.SessionStorageException(
            com.example.data.mobile.session.SessionStorageError.Locked())
        assertThrows(OutboxStorageException::class.java) { cipher.decrypt(event) }
        engine.failure = CancellationException()
        assertThrows(CancellationException::class.java) { cipher.decrypt(event) }
        engine.failure = OutOfMemoryError()
        assertThrows(OutOfMemoryError::class.java) { cipher.decrypt(event) }
        assertArrayEquals(original, event.payloadBytes)
    }

    @Test
    fun diskReopenAndSidecarsContainNoPayloadSecret() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "encrypted-${UUID.randomUUID()}.db")
        val cipher = OutboxPayloadCipher(JvmOutboxCrypto())
        val event = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "SECRET-RECEIPT-83917")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)
        val created = (repo.captureSale(event) as CaptureResult.Created).record
        fun checkFiles() {
            listOf("", "-wal", "-shm", "-journal").map { File(path.path + it) }.filter { it.exists() }.forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("SECRET-RECEIPT-83917"))
            }
        }
        checkFiles()
        db.close()
        checkFiles()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        assertArrayEquals(created.payloadBytes, OutboxRepositoryImpl(db, payloadCipher = cipher)
            .getRecord(event.companyId, event.actorId, event.deviceId, event.clientEventId)!!.payloadBytes)
        db.close()
    }

    @Test
    fun explicitLegacyConversionRollsBackThenReopensWithoutSchemaChange() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "legacy-${UUID.randomUUID()}.db")
        // Construct the historical physical v1 fixture independently of the current Room builder.
        val schema = org.json.JSONObject(File("schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json").readText())
            .getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val table = entities.getJSONObject(i)
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
                val indices = table.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", table.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.version = 1
        }
        var db = OutboxDatabase.buildDatabase(context, path.path, true)
        val a = entity()
        val eB = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "SECRET-RECEIPT-99999")
        val bytesB = CanonicalJsonSerializer.serialize(eB).toByteArray()
        val b = OutboxEventEntity(eB.clientEventId.value, eB.companyId.value, eB.actorId.value,
            eB.deviceId.value, eB.deviceLeaseId.value, eB.capturedAt, eB.computePayloadDigest().value, bytesB, 1)
        db.outboxDao().insertEvent(a)
        db.outboxDao().insertEvent(b)
        val engine = JvmOutboxCrypto()
        val interrupted = object : com.example.data.mobile.session.SessionCryptoEngine by engine {
            var calls = 0
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
                if (++calls == 2) throw CancellationException()
                return engine.encrypt(plaintext, aad)
            }
        }
        assertThrows(CancellationException::class.java) {
            OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(interrupted)).convertLegacyPayloads()
        }
        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        assertArrayEquals(a.payloadBytes, db.outboxDao().getEvent(a.companyId, a.clientEventId)!!.payloadBytes)
        val cipher = OutboxPayloadCipher(engine)
        OutboxRepositoryImpl(db, payloadCipher = cipher).convertLegacyPayloads()
        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        assertArrayEquals(a.payloadBytes, cipher.decrypt(db.outboxDao().getEvent(a.companyId, a.clientEventId)!!).payloadBytes)
        assertEquals(1, db.openHelper.readableDatabase.version)
        db.close()
    }

    // =========================================================================
    // Review 2 P2: Meaningful REAL disk SQLite persistent release gates
    // =========================================================================

    // Case 1: claimbatch multiple records first valid later auth failure -> ENTIRE claim transaction rollback no leases stranded
    @Test
    fun claimBatchMultipleRecordsFirstValidLaterAuthFailureRollsBackAllLeasesOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "claim-batch-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)

        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "RECEIPT-001")
        val e2 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "RECEIPT-002")
        val e3 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "RECEIPT-003")

        val engine = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(engine)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        // Capture all 3 events
        repo.captureSale(e1)
        repo.captureSale(e2)
        repo.captureSale(e3)

        val snapshotBefore = takeSnapshot(db)
        assertEquals(3, snapshotBefore.events.size)
        assertEquals(3, snapshotBefore.deliveryStates.size)
        assertTrue(snapshotBefore.deliveryStates.all { it.status == "pending" && it.claimToken == null && it.claimExpiresAt == null })

        // Intercept decrypt: e1 succeeds, e2 throws auth failure (Locked)
        var decryptCalls = 0
        val failingEngine = object : com.example.data.mobile.session.SessionCryptoEngine by engine {
            override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
                decryptCalls++
                if (decryptCalls == 2) {
                    throw com.example.data.mobile.session.SessionStorageException(
                        com.example.data.mobile.session.SessionStorageError.Locked("Simulated key locked during batch claim")
                    )
                }
                return engine.decrypt(iv, ciphertextWithTag, aad)
            }
        }
        val failingRepo = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(failingEngine))

        val ex = assertThrows(OutboxStorageException::class.java) {
            failingRepo.claimNextBatch(e1.companyId, e1.actorId, e1.deviceId, batchSize = 3)
        }
        assertEquals(OutboxStorageError.KEY_LOCKED, ex.error)

        // Verify entire transaction rolled back on disk immediately: no leases stranded
        val snapshotAfter = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotAfter)
        assertTrue("All delivery states must remain pending", snapshotAfter.deliveryStates.all { it.status == "pending" })
        assertTrue("No claim tokens may be stranded", snapshotAfter.deliveryStates.all { it.claimToken == null })
        assertTrue("No claim expirations may be stranded", snapshotAfter.deliveryStates.all { it.claimExpiresAt == null })

        // Close and reopen database from disk to verify disk durability
        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopen = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotReopen)
        db.close()
    }

    // Case 2: confirmDelivery AND reconcileDeliveryConfirmation corrupted cipher/missing key leaves original claims/status/eventbytes/servermap/authlocks unchanged
    @Test
    fun confirmDeliveryAndReconcileCorruptedCipherOrMissingKeyLeavesAllStateUnchangedOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "confirm-reconcile-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)

        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "CONFIRM-001")
        val engine = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(engine)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        repo.captureSale(e1)
        val batch = repo.claimNextBatch(e1.companyId, e1.actorId, e1.deviceId, batchSize = 1)
        assertEquals(1, batch.records.size)
        val claimToken = batch.claimToken

        // Insert an auth lock into outbox_scope_locks to verify locks are present and preserved
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = e1.companyId.value,
                actorId = e1.actorId.value,
                deviceId = e1.deviceId.value,
                lockedAt = 1000L,
                reason = "Preserved scope auth lock"
            )
        )

        // Subcase 2a: confirmDelivery with corrupted ciphertext
        val rawEvent = db.outboxDao().getEvent(e1.companyId.value, e1.clientEventId.value)!!
        val corruptedPayload = rawEvent.payloadBytes.clone().apply { this[20] = (this[20].toInt() xor 0xFF).toByte() }
        db.outboxDao().replacePayloadForConversion(e1.companyId.value, e1.clientEventId.value, corruptedPayload)

        val snapshotBeforeConfirm = takeSnapshot(db)

        val invoiceUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
        val paymentUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW")

        val exConfirm = assertThrows(OutboxStorageException::class.java) {
            repo.confirmDelivery(
                companyId = e1.companyId,
                actorId = e1.actorId,
                deviceId = e1.deviceId,
                clientEventId = e1.clientEventId,
                payloadDigest = e1.computePayloadDigest(),
                invoiceUlid = invoiceUlid,
                paymentUlid = paymentUlid,
                serverAcknowledgedAt = "2026-11-13T12:00:00Z",
                claimToken = claimToken
            )
        }
        assertEquals(OutboxStorageError.CORRUPT, exConfirm.error)

        val snapshotAfterConfirm = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBeforeConfirm, snapshotAfterConfirm)

        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopenConfirm = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBeforeConfirm, snapshotReopenConfirm)

        // Subcase 2b: reconcileDeliveryConfirmation with missing key
        db.outboxDao().replacePayloadForConversion(e1.companyId.value, e1.clientEventId.value, rawEvent.payloadBytes)
        // Transition record to unknown_ack for reconciliation
        val timeoutSuccess = repo.recordTimeout(
            companyId = e1.companyId,
            actorId = e1.actorId,
            deviceId = e1.deviceId,
            clientEventId = e1.clientEventId,
            claimToken = claimToken,
            errorMessage = "Network timeout"
        )
        assertTrue(timeoutSuccess)
        val snapshotBeforeReconcile = takeSnapshot(db)
        assertEquals(OutboxStatus.UNKNOWN_ACK.wireValue, snapshotBeforeReconcile.deliveryStates[0].status)

        engine.missing = true
        val repoMissingKey = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(engine))

        val exReconcile = assertThrows(OutboxStorageException::class.java) {
            repoMissingKey.reconcileDeliveryConfirmation(
                companyId = e1.companyId,
                actorId = e1.actorId,
                deviceId = e1.deviceId,
                clientEventId = e1.clientEventId,
                payloadDigest = e1.computePayloadDigest(),
                invoiceUlid = invoiceUlid,
                paymentUlid = paymentUlid,
                serverAcknowledgedAt = "2026-11-13T12:00:00Z"
            )
        }
        assertEquals(OutboxStorageError.KEY_UNAVAILABLE, exReconcile.error)

        val snapshotAfterReconcile = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBeforeReconcile, snapshotAfterReconcile)

        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopenReconcile = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBeforeReconcile, snapshotReopenReconcile)
        db.close()
    }

    private fun populateMixedQueuePreservationSentinels(db: OutboxDatabase) {
        val sql = db.openHelper.writableDatabase
        // Deliberately non-null persistence sentinels: accidental clearing must be observable.
        sql.execSQL("""UPDATE outbox_delivery_states SET status = 'in_flight', attempt_count = 3,
            last_attempt_at = 4000, claim_token = 'preserved-claim', claim_expires_at = 9000,
            invoice_ulid = '01ARZ3NDEKTSV4RRFFQ69G5FAV', payment_ulid = '01ARZ3NDEKTSV4RRFFQ69G5FAW',
            server_ulid = '01ARZ3NDEKTSV4RRFFQ69G5FAX', server_acknowledged_at = '2026-11-13T12:00:00Z',
            error_message = 'fixed-preservation-sentinel'""")
        sql.execSQL("""INSERT OR IGNORE INTO outbox_scope_locks
            (company_id, actor_id, device_id, locked_at, reason)
            SELECT company_id, actor_id, device_id, 3000, 'fixed-auth-lock-sentinel' FROM outbox_events""")
        val snapshot = takeSnapshot(db)
        assertTrue(snapshot.scopeLocks.isNotEmpty())
        assertTrue(snapshot.deliveryStates.all { it.claimToken == "preserved-claim" && it.invoiceUlid != null && it.paymentUlid != null && it.serverUlid != null && it.serverAcknowledgedAt != null })
    }

    // Case 3: mixed plaintext + cipher conversion missing/invalid key refuses and preserves entire queue/keygenerationcounter
    @Test
    fun mixedPlaintextAndCipherConversionMissingKeyRefusesAndPreservesEntireQueueAndKeyGenCounterOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "mixed-queue-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)

        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "PLAIN-001")
        val bytes1 = CanonicalJsonSerializer.serialize(e1).toByteArray()
        val plainEntity1 = OutboxEventEntity(e1.clientEventId.value, e1.companyId.value, e1.actorId.value,
            e1.deviceId.value, e1.deviceLeaseId.value, e1.capturedAt, e1.computePayloadDigest().value, bytes1, 1)

        val e2 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "CIPHER-002")
        val bytes2 = CanonicalJsonSerializer.serialize(e2).toByteArray()
        val plainEntity2 = OutboxEventEntity(e2.clientEventId.value, e2.companyId.value, e2.actorId.value,
            e2.deviceId.value, e2.deviceLeaseId.value, e2.capturedAt, e2.computePayloadDigest().value, bytes2, 1)

        val initialEngine = JvmOutboxCrypto()
        val initialCipher = OutboxPayloadCipher(initialEngine)
        val encryptedEntity2 = initialCipher.encrypt(plainEntity2)

        db.outboxDao().insertEvent(plainEntity1)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = plainEntity1.clientEventId,
                companyId = plainEntity1.companyId,
                actorId = plainEntity1.actorId,
                deviceId = plainEntity1.deviceId,
                status = OutboxStatus.PENDING.wireValue,
                attemptCount = 0, lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                invoiceUlid = null, paymentUlid = null, serverUlid = null, serverAcknowledgedAt = null,
                errorMessage = null, createdAt = 1000L, updatedAt = 1000L
            )
        )

        db.outboxDao().insertEvent(encryptedEntity2)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = encryptedEntity2.clientEventId,
                companyId = encryptedEntity2.companyId,
                actorId = encryptedEntity2.actorId,
                deviceId = encryptedEntity2.deviceId,
                status = OutboxStatus.PENDING.wireValue,
                attemptCount = 0, lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                invoiceUlid = null, paymentUlid = null, serverUlid = null, serverAcknowledgedAt = null,
                errorMessage = null, createdAt = 2000L, updatedAt = 2000L
            )
        )

        populateMixedQueuePreservationSentinels(db)
        val snapshotBefore = takeSnapshot(db)
        assertEquals(2, snapshotBefore.events.size)
        val snapEvent1 = snapshotBefore.events.first { it.clientEventId == plainEntity1.clientEventId }
        val snapEvent2 = snapshotBefore.events.first { it.clientEventId == encryptedEntity2.clientEventId }
        assertFalse(initialCipher.isEncrypted(snapEvent1.payloadBytes))
        assertTrue(initialCipher.isEncrypted(snapEvent2.payloadBytes))

        // Create engine with missing = true and check keyGenerationCount
        val missingEngine = JvmOutboxCrypto().apply { missing = true }
        val initialKeyGenCount = missingEngine.keyGenerationCount
        val repoMissingKey = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(missingEngine))

        val ex = assertThrows(OutboxStorageException::class.java) {
            repoMissingKey.convertLegacyPayloads()
        }
        assertEquals(OutboxStorageError.KEY_UNAVAILABLE, ex.error)
        assertEquals("Key generation counter must not increment on missing key with existing encrypted rows",
            initialKeyGenCount, missingEngine.keyGenerationCount)

        // Snapshot immediately after failure: entire queue preserved
        val snapshotAfter = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotAfter)

        // Reopen disk database to verify durability
        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopen = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotReopen)
        db.close()
    }

    // Case 3b: mixed plaintext + cipher conversion invalidated key (KEY_LOCKED) refuses and preserves entire queue and counter
    @Test
    fun mixedPlaintextAndCipherConversionInvalidatedKeyRefusesAndPreservesEntireQueueAndKeyGenCounterOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "mixed-invalidated-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)

        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "PLAIN-INV-001")
        val bytes1 = CanonicalJsonSerializer.serialize(e1).toByteArray()
        val plainEntity1 = OutboxEventEntity(e1.clientEventId.value, e1.companyId.value, e1.actorId.value,
            e1.deviceId.value, e1.deviceLeaseId.value, e1.capturedAt, e1.computePayloadDigest().value, bytes1, 1)

        val e2 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "CIPHER-INV-002")
        val bytes2 = CanonicalJsonSerializer.serialize(e2).toByteArray()
        val plainEntity2 = OutboxEventEntity(e2.clientEventId.value, e2.companyId.value, e2.actorId.value,
            e2.deviceId.value, e2.deviceLeaseId.value, e2.capturedAt, e2.computePayloadDigest().value, bytes2, 1)

        val initialEngine = JvmOutboxCrypto()
        val initialCipher = OutboxPayloadCipher(initialEngine)
        val encryptedEntity2 = initialCipher.encrypt(plainEntity2)

        db.outboxDao().insertEvent(plainEntity1)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = plainEntity1.clientEventId, companyId = plainEntity1.companyId,
                actorId = plainEntity1.actorId, deviceId = plainEntity1.deviceId,
                status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                invoiceUlid = null, paymentUlid = null, serverUlid = null,
                serverAcknowledgedAt = null, errorMessage = null,
                createdAt = 1000L, updatedAt = 1000L
            )
        )

        db.outboxDao().insertEvent(encryptedEntity2)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = encryptedEntity2.clientEventId, companyId = encryptedEntity2.companyId,
                actorId = encryptedEntity2.actorId, deviceId = encryptedEntity2.deviceId,
                status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                invoiceUlid = null, paymentUlid = null, serverUlid = null,
                serverAcknowledgedAt = null, errorMessage = null,
                createdAt = 2000L, updatedAt = 2000L
            )
        )

        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = plainEntity1.companyId, actorId = plainEntity1.actorId,
                deviceId = plainEntity1.deviceId, lockedAt = 3000L,
                reason = "Scope lock for invalidated key test"
            )
        )

        populateMixedQueuePreservationSentinels(db)
        val snapshotBefore = takeSnapshot(db)
        assertEquals(2, snapshotBefore.events.size)
        assertEquals(2, snapshotBefore.deliveryStates.size)
        assertEquals(1, snapshotBefore.scopeLocks.size)

        // Engine with permanently invalidated key (throws SessionStorageError.Locked)
        val lockedEngine = JvmOutboxCrypto().apply {
            failure = SessionStorageException(SessionStorageError.Locked("Key permanently invalidated in Keystore"))
        }
        val initialKeyGenCount = lockedEngine.keyGenerationCount
        val repoLockedKey = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(lockedEngine))

        val ex = assertThrows(OutboxStorageException::class.java) {
            repoLockedKey.convertLegacyPayloads()
        }
        assertEquals(OutboxStorageError.KEY_LOCKED, ex.error)
        assertEquals("Key generation counter must not increment on invalidated key with existing encrypted rows",
            initialKeyGenCount, lockedEngine.keyGenerationCount)

        val snapshotAfter = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotAfter)

        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopen = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotReopen)
        db.close()
    }

    // Case 3c: mixed plaintext + cipher conversion present wrong key (auth CORRUPT) refuses and preserves entire queue and counter
    @Test
    fun mixedPlaintextAndCipherConversionWrongKeyRefusesAndPreservesEntireQueueAndKeyGenCounterOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "mixed-wrong-key-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)

        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "PLAIN-WRONG-001")
        val bytes1 = CanonicalJsonSerializer.serialize(e1).toByteArray()
        val plainEntity1 = OutboxEventEntity(e1.clientEventId.value, e1.companyId.value, e1.actorId.value,
            e1.deviceId.value, e1.deviceLeaseId.value, e1.capturedAt, e1.computePayloadDigest().value, bytes1, 1)

        val e2 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "CIPHER-WRONG-002")
        val bytes2 = CanonicalJsonSerializer.serialize(e2).toByteArray()
        val plainEntity2 = OutboxEventEntity(e2.clientEventId.value, e2.companyId.value, e2.actorId.value,
            e2.deviceId.value, e2.deviceLeaseId.value, e2.capturedAt, e2.computePayloadDigest().value, bytes2, 1)

        val initialEngine = JvmOutboxCrypto()
        val initialCipher = OutboxPayloadCipher(initialEngine)
        val encryptedEntity2 = initialCipher.encrypt(plainEntity2)

        db.outboxDao().insertEvent(plainEntity1)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = plainEntity1.clientEventId, companyId = plainEntity1.companyId,
                actorId = plainEntity1.actorId, deviceId = plainEntity1.deviceId,
                status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                invoiceUlid = null, paymentUlid = null, serverUlid = null,
                serverAcknowledgedAt = null, errorMessage = null,
                createdAt = 1000L, updatedAt = 1000L
            )
        )

        db.outboxDao().insertEvent(encryptedEntity2)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = encryptedEntity2.clientEventId, companyId = encryptedEntity2.companyId,
                actorId = encryptedEntity2.actorId, deviceId = encryptedEntity2.deviceId,
                status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                invoiceUlid = null, paymentUlid = null, serverUlid = null,
                serverAcknowledgedAt = null, errorMessage = null,
                createdAt = 2000L, updatedAt = 2000L
            )
        )

        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = plainEntity1.companyId, actorId = plainEntity1.actorId,
                deviceId = plainEntity1.deviceId, lockedAt = 3000L,
                reason = "Scope lock for wrong key test"
            )
        )

        populateMixedQueuePreservationSentinels(db)
        val snapshotBefore = takeSnapshot(db)
        assertEquals(2, snapshotBefore.events.size)
        assertEquals(2, snapshotBefore.deliveryStates.size)
        assertEquals(1, snapshotBefore.scopeLocks.size)

        // Engine with a different (wrong) key: pre-authentication of encryptedEntity2 fails AEAD verification
        val wrongKeyEngine = JvmOutboxCrypto()
        val initialKeyGenCount = wrongKeyEngine.keyGenerationCount
        val repoWrongKey = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(wrongKeyEngine))

        val ex = assertThrows(OutboxStorageException::class.java) {
            repoWrongKey.convertLegacyPayloads()
        }
        assertEquals(OutboxStorageError.CORRUPT, ex.error)
        assertEquals("Key generation counter must not increment on wrong key pre-authentication failure",
            initialKeyGenCount, wrongKeyEngine.keyGenerationCount)

        val snapshotAfter = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotAfter)

        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopen = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotReopen)
        db.close()
    }

    // Case 4: midconversion fault rollback ALL rows not just first incl states + populated & empty queue locks
    @Test
    fun midConversionFaultRollbackAllRowsNotJustFirstIncludingStatesAndEmptyQueueLockOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "mid-conversion-fault-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)

        val e1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "ROW-1")
        val b1 = CanonicalJsonSerializer.serialize(e1).toByteArray()
        val p1 = OutboxEventEntity(e1.clientEventId.value, e1.companyId.value, e1.actorId.value,
            e1.deviceId.value, e1.deviceLeaseId.value, e1.capturedAt, e1.computePayloadDigest().value, b1, 1)

        val e2 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "ROW-2")
        val b2 = CanonicalJsonSerializer.serialize(e2).toByteArray()
        val p2 = OutboxEventEntity(e2.clientEventId.value, e2.companyId.value, e2.actorId.value,
            e2.deviceId.value, e2.deviceLeaseId.value, e2.capturedAt, e2.computePayloadDigest().value, b2, 1)

        db.outboxDao().insertEvent(p1)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = p1.clientEventId, companyId = p1.companyId, actorId = p1.actorId,
                deviceId = p1.deviceId, status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                lastAttemptAt = null, claimToken = null, claimExpiresAt = null, invoiceUlid = null,
                paymentUlid = null, serverUlid = null, serverAcknowledgedAt = null, errorMessage = null,
                createdAt = 1000L, updatedAt = 1000L
            )
        )

        db.outboxDao().insertEvent(p2)
        db.outboxDao().insertDeliveryState(
            OutboxDeliveryStateEntity(
                clientEventId = p2.clientEventId, companyId = p2.companyId, actorId = p2.actorId,
                deviceId = p2.deviceId, status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                lastAttemptAt = null, claimToken = null, claimExpiresAt = null, invoiceUlid = null,
                paymentUlid = null, serverUlid = null, serverAcknowledgedAt = null, errorMessage = null,
                createdAt = 2000L, updatedAt = 2000L
            )
        )

        // Insert scope lock belonging to the populated scope (p1/p2)
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = p1.companyId, actorId = p1.actorId, deviceId = p1.deviceId,
                lockedAt = 3000L, reason = "Populated queue persistent lock"
            )
        )

        // Insert separate company/actor/device scope with ZERO events
        val emptyScopeCompany = "01JCV8X9MBV4W5X6Y7Z8EMPTYC"
        val emptyScopeActor = "01JCV8X9MBV4W5X6Y7Z8EMPTYA"
        val emptyScopeDevice = "3fa85f64-5717-4562-b3fc-2c963f66afaa"
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = emptyScopeCompany, actorId = emptyScopeActor, deviceId = emptyScopeDevice,
                lockedAt = 3500L, reason = "Separate empty queue persistent lock"
            )
        )

        val snapshotBefore = takeSnapshot(db)
        assertEquals(2, snapshotBefore.events.size)
        assertEquals(2, snapshotBefore.deliveryStates.size)
        assertEquals(2, snapshotBefore.scopeLocks.size)
        assertTrue("Empty scope must have zero events before fault",
            snapshotBefore.events.none { it.companyId == emptyScopeCompany && it.actorId == emptyScopeActor && it.deviceId == emptyScopeDevice })
        assertTrue("Populated scope lock must be present",
            snapshotBefore.scopeLocks.any { it.companyId == p1.companyId && it.actorId == p1.actorId && it.deviceId == p1.deviceId })
        assertTrue("Separate empty scope lock must be present",
            snapshotBefore.scopeLocks.any { it.companyId == emptyScopeCompany && it.actorId == emptyScopeActor && it.deviceId == emptyScopeDevice })

        val engine = JvmOutboxCrypto()
        val faultEngine = object : com.example.data.mobile.session.SessionCryptoEngine by engine {
            var encryptCalls = 0
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
                encryptCalls++
                if (encryptCalls == 2) {
                    throw CancellationException("Mid-conversion cancellation on row 2")
                }
                return engine.encrypt(plaintext, aad)
            }
        }

        val repoFault = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(faultEngine))
        assertThrows(CancellationException::class.java) {
            repoFault.convertLegacyPayloads()
        }

        // Entire transaction rolls back: row 1 is NOT left encrypted, states and both scope locks intact
        val snapshotAfter = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotAfter)
        assertTrue("Empty scope must still have zero events after rollback",
            snapshotAfter.events.none { it.companyId == emptyScopeCompany && it.actorId == emptyScopeActor && it.deviceId == emptyScopeDevice })
        assertTrue("Populated scope lock must be intact after rollback",
            snapshotAfter.scopeLocks.any { it.companyId == p1.companyId && it.actorId == p1.actorId && it.deviceId == p1.deviceId })
        assertTrue("Separate empty scope lock must be intact after rollback",
            snapshotAfter.scopeLocks.any { it.companyId == emptyScopeCompany && it.actorId == emptyScopeActor && it.deviceId == emptyScopeDevice })
        assertFalse(OutboxPayloadCipher(engine).isEncrypted(snapshotAfter.events[0].payloadBytes))
        assertFalse(OutboxPayloadCipher(engine).isEncrypted(snapshotAfter.events[1].payloadBytes))

        // Reopen disk database to verify durability
        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopen = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotReopen)
        assertTrue("Empty scope must have zero events after reopen",
            snapshotReopen.events.none { it.companyId == emptyScopeCompany && it.actorId == emptyScopeActor && it.deviceId == emptyScopeDevice })
        assertTrue("Populated scope lock must be intact after reopen",
            snapshotReopen.scopeLocks.any { it.companyId == p1.companyId && it.actorId == p1.actorId && it.deviceId == p1.deviceId })
        assertTrue("Separate empty scope lock must be intact after reopen",
            snapshotReopen.scopeLocks.any { it.companyId == emptyScopeCompany && it.actorId == emptyScopeActor && it.deviceId == emptyScopeDevice })
        db.close()
    }

    // Helper for isolated format rejection tests on fresh disk databases
    private fun assertFormatRejectionLeavesDatabaseUntouched(
        description: String,
        expectedError: OutboxStorageError = OutboxStorageError.CORRUPT,
        verifyPrecondition: (OutboxEventEntity, OfflineSaleEvent) -> Unit = { _, _ -> },
        entityBuilder: (context: Context, validV1: OfflineSaleEvent) -> OutboxEventEntity
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "format-reject-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)
        try {
            val engine = JvmOutboxCrypto()
            val cipher = OutboxPayloadCipher(engine)
            val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

            val validSample = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "V1-FMT-${UUID.randomUUID().toString().take(6)}")
            val entity = entityBuilder(context, validSample)
            verifyPrecondition(entity, validSample)

            db.outboxDao().insertEvent(entity)
            db.outboxDao().insertDeliveryState(
                OutboxDeliveryStateEntity(
                    clientEventId = entity.clientEventId, companyId = entity.companyId,
                    actorId = entity.actorId, deviceId = entity.deviceId,
                    status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                    lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                    invoiceUlid = null, paymentUlid = null, serverUlid = null,
                    serverAcknowledgedAt = null, errorMessage = null,
                    createdAt = 1000L, updatedAt = 1000L
                )
            )
            db.outboxDao().insertScopeLock(
                OutboxScopeLockEntity(
                    companyId = entity.companyId, actorId = entity.actorId, deviceId = entity.deviceId,
                    lockedAt = 2000L, reason = "Pre-conversion lock for $description"
                )
            )

            val snapshotBefore = takeSnapshot(db)
            assertEquals("Exactly one event must be present before conversion: $description", 1, snapshotBefore.events.size)
            assertEquals("Exactly one delivery state must be present: $description", 1, snapshotBefore.deliveryStates.size)
            assertEquals("Exactly one scope lock must be present: $description", 1, snapshotBefore.scopeLocks.size)

            val ex = assertThrows("Expected $expectedError for $description", OutboxStorageException::class.java) {
                repo.convertLegacyPayloads()
            }
            assertEquals("Violation must match expected error for $description", expectedError, ex.error)

            val snapshotAfter = takeSnapshot(db)
            assertSnapshotsEqual(snapshotBefore, snapshotAfter)
            assertFalse("Payload must remain unencrypted after rejection: $description", cipher.isEncrypted(snapshotAfter.events[0].payloadBytes))

            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            val snapshotReopen = takeSnapshot(db)
            assertSnapshotsEqual(snapshotBefore, snapshotReopen)
            assertFalse("Payload must remain unencrypted after reopen: $description", cipher.isEncrypted(snapshotReopen.events[0].payloadBytes))
        } finally {
            db.close()
            path.delete()
        }
    }

    // Positive Control: Valid canonical v1 conversion succeeds on isolated fresh disk DB
    @Test
    fun validCanonicalV1PositiveConversionControlOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "valid-v1-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)
        try {
            val engine = JvmOutboxCrypto()
            val cipher = OutboxPayloadCipher(engine)
            val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

            val validV1 = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "V1-POS-001")
            val b1 = CanonicalJsonSerializer.serialize(validV1).toByteArray(StandardCharsets.UTF_8)
            val digest = validV1.computePayloadDigest().value
            val entity = OutboxEventEntity(
                clientEventId = validV1.clientEventId.value, companyId = validV1.companyId.value,
                actorId = validV1.actorId.value, deviceId = validV1.deviceId.value,
                deviceLeaseId = validV1.deviceLeaseId.value, capturedAt = validV1.capturedAt,
                payloadDigest = digest, payloadBytes = b1, eventVersion = 1
            )

            db.outboxDao().insertEvent(entity)
            db.outboxDao().insertDeliveryState(
                OutboxDeliveryStateEntity(
                    clientEventId = entity.clientEventId, companyId = entity.companyId,
                    actorId = entity.actorId, deviceId = entity.deviceId,
                    status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                    lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                    invoiceUlid = null, paymentUlid = null, serverUlid = null,
                    serverAcknowledgedAt = null, errorMessage = null,
                    createdAt = 1000L, updatedAt = 1000L
                )
            )
            db.outboxDao().insertScopeLock(
                OutboxScopeLockEntity(
                    companyId = entity.companyId, actorId = entity.actorId, deviceId = entity.deviceId,
                    lockedAt = 2000L, reason = "Positive control lock v1"
                )
            )

            repo.convertLegacyPayloads()

            val snapshotAfter = takeSnapshot(db)
            assertEquals(1, snapshotAfter.events.size)
            assertTrue("V1 payload must be encrypted after successful conversion", cipher.isEncrypted(snapshotAfter.events[0].payloadBytes))
            assertEquals(1, snapshotAfter.deliveryStates.size)
            assertEquals(1, snapshotAfter.scopeLocks.size)

            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            val reopenedSnapshot = takeSnapshot(db)
            assertEquals(1, reopenedSnapshot.events.size)
            assertTrue("V1 payload must remain encrypted after reopen", cipher.isEncrypted(reopenedSnapshot.events[0].payloadBytes))
            val decryptedEntity = cipher.decrypt(reopenedSnapshot.events[0])
            assertArrayEquals("Decrypted v1 payload must match exact original canonical bytes", b1, decryptedEntity.payloadBytes)
            assertEquals(digest, reopenedSnapshot.events[0].payloadDigest)
        } finally {
            db.close()
            path.delete()
        }
    }

    // Positive Control: Valid canonical v2 conversion succeeds on isolated fresh disk DB
    @Test
    fun validCanonicalV2PositiveConversionControlOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "valid-v2-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)
        try {
            val engine = JvmOutboxCrypto()
            val cipher = OutboxPayloadCipher(engine)
            val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

            val validV2 = OutboxTestFixtures.createSampleEventV2(localReceiptNumber = "V2-POS-001")
            val b2 = CanonicalJsonSerializer.serialize(validV2).toByteArray(StandardCharsets.UTF_8)
            val digest = validV2.computePayloadDigest().value
            val entity = OutboxEventEntity(
                clientEventId = validV2.clientEventId.value, companyId = validV2.companyId.value,
                actorId = validV2.actorId.value, deviceId = validV2.deviceId.value,
                deviceLeaseId = validV2.deviceLeaseId.value, capturedAt = validV2.capturedAt,
                payloadDigest = digest, payloadBytes = b2, eventVersion = 2
            )

            db.outboxDao().insertEvent(entity)
            db.outboxDao().insertDeliveryState(
                OutboxDeliveryStateEntity(
                    clientEventId = entity.clientEventId, companyId = entity.companyId,
                    actorId = entity.actorId, deviceId = entity.deviceId,
                    status = OutboxStatus.PENDING.wireValue, attemptCount = 0,
                    lastAttemptAt = null, claimToken = null, claimExpiresAt = null,
                    invoiceUlid = null, paymentUlid = null, serverUlid = null,
                    serverAcknowledgedAt = null, errorMessage = null,
                    createdAt = 1000L, updatedAt = 1000L
                )
            )
            db.outboxDao().insertScopeLock(
                OutboxScopeLockEntity(
                    companyId = entity.companyId, actorId = entity.actorId, deviceId = entity.deviceId,
                    lockedAt = 2000L, reason = "Positive control lock v2"
                )
            )

            repo.convertLegacyPayloads()

            val snapshotAfter = takeSnapshot(db)
            assertEquals(1, snapshotAfter.events.size)
            assertTrue("V2 payload must be encrypted after successful conversion", cipher.isEncrypted(snapshotAfter.events[0].payloadBytes))
            assertEquals(1, snapshotAfter.deliveryStates.size)
            assertEquals(1, snapshotAfter.scopeLocks.size)

            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            val reopenedSnapshot = takeSnapshot(db)
            assertEquals(1, reopenedSnapshot.events.size)
            assertTrue("V2 payload must remain encrypted after reopen", cipher.isEncrypted(reopenedSnapshot.events[0].payloadBytes))
            val decryptedEntity = cipher.decrypt(reopenedSnapshot.events[0])
            assertArrayEquals("Decrypted v2 payload must match exact original canonical bytes", b2, decryptedEntity.payloadBytes)
            assertEquals(digest, reopenedSnapshot.events[0].payloadDigest)
        } finally {
            db.close()
            path.delete()
        }
    }

    // Case 5.1: Row metadata mismatch: clientEventId differs from JSON
    @Test
    fun formatRejectionClientEventIdMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "clientEventId mismatch between row and canonical payload",
            verifyPrecondition = { entity, sample ->
                assertNotEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.companyId, sample.companyId.value)
                assertEquals(entity.actorId, sample.actorId.value)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = UUID.randomUUID().toString(), // differs from validSample.clientEventId
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.2: Row metadata mismatch: companyId differs from JSON
    @Test
    fun formatRejectionCompanyIdMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "companyId mismatch between row and canonical payload",
            verifyPrecondition = { entity, sample ->
                assertNotEquals(entity.companyId, sample.companyId.value)
                assertEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.actorId, sample.actorId.value)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = "01JCV8X9MBV4W5X6Y7Z8DIFF01", // differs from validSample.companyId
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.3: Row metadata mismatch: actorId differs from JSON
    @Test
    fun formatRejectionActorIdMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "actorId mismatch between row and canonical payload",
            verifyPrecondition = { entity, sample ->
                assertNotEquals(entity.actorId, sample.actorId.value)
                assertEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.companyId, sample.companyId.value)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = "01JCV8X9MBV4W5X6Y7Z8DIFF02", // differs from validSample.actorId
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.4: Row metadata mismatch: deviceId differs from JSON
    @Test
    fun formatRejectionDeviceIdMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "deviceId mismatch between row and canonical payload",
            verifyPrecondition = { entity, sample ->
                assertNotEquals(entity.deviceId, sample.deviceId.value)
                assertEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = "00000000-0000-0000-0000-000000000001", // differs from validSample.deviceId
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.5: Row metadata mismatch: deviceLeaseId differs from JSON
    @Test
    fun formatRejectionDeviceLeaseIdMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "deviceLeaseId mismatch between row and canonical payload",
            verifyPrecondition = { entity, sample ->
                assertNotEquals(entity.deviceLeaseId, sample.deviceLeaseId.value)
                assertEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = "00000000-0000-0000-0000-000000000002", // differs from validSample.deviceLeaseId
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.6: Row metadata mismatch: capturedAt differs from JSON
    @Test
    fun formatRejectionCapturedAtMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "capturedAt mismatch between row and canonical payload",
            verifyPrecondition = { entity, sample ->
                assertNotEquals(entity.capturedAt, sample.capturedAt)
                assertEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = "2026-11-10T12:01:00Z", // differs from validSample.capturedAt ("2026-11-10T12:00:00Z")
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.7: Row metadata mismatch: payloadDigest differs from byte digest
    @Test
    fun formatRejectionPayloadDigestMismatchOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "payloadDigest mismatch between row and actual payload bytes",
            verifyPrecondition = { entity, _ ->
                assertNotEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes("corrupt-bytes".toByteArray(StandardCharsets.UTF_8)).value,
                    payloadBytes = bytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.8: Row metadata mismatch: row eventVersion 2 but payload is canonical v1
    @Test
    fun formatRejectionEventVersionMismatchV1PayloadWithRowVersion2OnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "row eventVersion 2 with v1 canonical payload",
            verifyPrecondition = { entity, sample ->
                assertEquals(2, entity.eventVersion)
                assertEquals(1, sample.eventVersion)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 2 // Row claims version 2 for v1 event
                )
            }
        )
    }

    // Case 5.9: Row metadata mismatch: row eventVersion 1 but payload is canonical v2
    @Test
    fun formatRejectionEventVersionMismatchV2PayloadWithRowVersion1OnDisk() {
        val validV2 = OutboxTestFixtures.createSampleEventV2(localReceiptNumber = "V2-MISMATCH-001")
        val bytesV2 = CanonicalJsonSerializer.serialize(validV2).toByteArray(StandardCharsets.UTF_8)
        val digestV2 = PayloadDigest.fromBytes(bytesV2).value

        assertFormatRejectionLeavesDatabaseUntouched(
            description = "row eventVersion 1 with v2 canonical payload",
            verifyPrecondition = { entity, _ ->
                assertEquals(1, entity.eventVersion)
                assertEquals(2, validV2.eventVersion)
                assertEquals(entity.payloadDigest, digestV2)
            },
            entityBuilder = { _, _ ->
                OutboxEventEntity(
                    clientEventId = validV2.clientEventId.value,
                    companyId = validV2.companyId.value,
                    actorId = validV2.actorId.value,
                    deviceId = validV2.deviceId.value,
                    deviceLeaseId = validV2.deviceLeaseId.value,
                    capturedAt = validV2.capturedAt,
                    payloadDigest = digestV2,
                    payloadBytes = bytesV2,
                    eventVersion = 1 // Row claims version 1 for v2 event
                )
            }
        )
    }

    // Case 5.10: Unsupported event version 3
    @Test
    fun formatRejectionUnsupportedEventVersion3OnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "unsupported eventVersion 3",
            verifyPrecondition = { entity, _ ->
                assertEquals(3, entity.eventVersion)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 3 // Unsupported event version
                )
            }
        )
    }

    // Case 5.11: Unsupported event version 0
    @Test
    fun formatRejectionUnsupportedEventVersion0OnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "unsupported eventVersion 0",
            verifyPrecondition = { entity, _ ->
                assertEquals(0, entity.eventVersion)
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
            },
            entityBuilder = { _, validSample ->
                val bytes = CanonicalJsonSerializer.serialize(validSample).toByteArray(StandardCharsets.UTF_8)
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = PayloadDigest.fromBytes(bytes).value,
                    payloadBytes = bytes,
                    eventVersion = 0 // Unsupported event version
                )
            }
        )
    }

    // Case 5.12: Malformed UTF-8 payload
    @Test
    fun formatRejectionMalformedUtf8OnDisk() {
        val badUtf8 = byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0xFF.toByte(), 0xFE.toByte())
        val digest = PayloadDigest.fromBytes(badUtf8).value

        assertFormatRejectionLeavesDatabaseUntouched(
            description = "malformed UTF-8 payload bytes",
            verifyPrecondition = { entity, _ ->
                assertEquals(digest, entity.payloadDigest)
                assertThrows(java.nio.charset.CharacterCodingException::class.java) {
                    val dec = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    dec.decode(java.nio.ByteBuffer.wrap(badUtf8))
                }
            },
            entityBuilder = { _, validSample ->
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = digest,
                    payloadBytes = badUtf8,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.13: Unknown STO/2 envelope matching digest
    @Test
    fun formatRejectionUnknownSto2EnvelopeOnDisk() {
        val sto2Envelope = byteArrayOf(0x53, 0x54, 0x4f, 0x02) + ByteArray(40) { 0x42 }
        val sto2Digest = PayloadDigest.fromBytes(sto2Envelope).value

        assertFormatRejectionLeavesDatabaseUntouched(
            description = "unknown STO/2 envelope with matching digest",
            verifyPrecondition = { entity, _ ->
                assertEquals(sto2Digest, entity.payloadDigest)
                assertEquals(0x53.toByte(), entity.payloadBytes[0])
                assertEquals(0x54.toByte(), entity.payloadBytes[1])
                assertEquals(0x4f.toByte(), entity.payloadBytes[2])
                assertEquals(0x02.toByte(), entity.payloadBytes[3])
            },
            entityBuilder = { _, validSample ->
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = sto2Digest,
                    payloadBytes = sto2Envelope,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.14: Truncated STO envelope
    @Test
    fun formatRejectionTruncatedStoEnvelopeOnDisk() {
        val truncatedEnvelope = byteArrayOf(0x53, 0x54, 0x4f) // STO prefix but length < 4
        val digest = PayloadDigest.fromBytes(truncatedEnvelope).value

        assertFormatRejectionLeavesDatabaseUntouched(
            description = "truncated STO envelope header",
            verifyPrecondition = { entity, _ ->
                assertEquals(digest, entity.payloadDigest)
                assertTrue(entity.payloadBytes.size < 4)
            },
            entityBuilder = { _, validSample ->
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = digest,
                    payloadBytes = truncatedEnvelope,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.15: Arbitrary JSON junk / {} matching digest
    @Test
    fun formatRejectionJunkEmptyObjectOnDisk() {
        val junkBytes = "{}".toByteArray(StandardCharsets.UTF_8)
        val junkDigest = PayloadDigest.fromBytes(junkBytes).value

        assertFormatRejectionLeavesDatabaseUntouched(
            description = "arbitrary empty JSON object {} matching digest",
            verifyPrecondition = { entity, _ ->
                assertEquals(junkDigest, entity.payloadDigest)
                assertEquals("{}", String(entity.payloadBytes, StandardCharsets.UTF_8))
            },
            entityBuilder = { _, validSample ->
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = junkDigest,
                    payloadBytes = junkBytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.16: Genuinely noncanonical whitespace with SAME valid identity
    @Test
    fun formatRejectionGenuinelyNonCanonicalWhitespaceWithSameValidIdentityOnDisk() {
        assertFormatRejectionLeavesDatabaseUntouched(
            description = "genuinely noncanonical whitespace with 100% identical and valid row metadata",
            verifyPrecondition = { entity, sample ->
                // Prove all row metadata matches the sample event exactly
                assertEquals(entity.clientEventId, sample.clientEventId.value)
                assertEquals(entity.companyId, sample.companyId.value)
                assertEquals(entity.actorId, sample.actorId.value)
                assertEquals(entity.deviceId, sample.deviceId.value)
                assertEquals(entity.deviceLeaseId, sample.deviceLeaseId.value)
                assertEquals(entity.capturedAt, sample.capturedAt)
                assertEquals(entity.eventVersion, sample.eventVersion)
                // Prove row digest matches the non-canonical bytes
                assertEquals(entity.payloadDigest, PayloadDigest.fromBytes(entity.payloadBytes).value)
                // Prove the bytes are NOT identical to canonical serialization
                val canonicalBytes = CanonicalJsonSerializer.serialize(sample).toByteArray(StandardCharsets.UTF_8)
                assertFalse("Non-canonical bytes must differ from canonical serialization",
                    entity.payloadBytes.contentEquals(canonicalBytes))
            },
            entityBuilder = { _, validSample ->
                val canonicalJson = CanonicalJsonSerializer.serialize(validSample)
                val nonCanonicalJson = canonicalJson.replace("{\"", "{\n  \"")
                assertNotEquals("Whitespace must be introduced into non-canonical JSON", canonicalJson, nonCanonicalJson)
                val nonCanonicalBytes = nonCanonicalJson.toByteArray(StandardCharsets.UTF_8)
                val digest = PayloadDigest.fromBytes(nonCanonicalBytes).value

                // The row entity carries the EXACT SAME identity as the JSON payload
                OutboxEventEntity(
                    clientEventId = validSample.clientEventId.value,
                    companyId = validSample.companyId.value,
                    actorId = validSample.actorId.value,
                    deviceId = validSample.deviceId.value,
                    deviceLeaseId = validSample.deviceLeaseId.value,
                    capturedAt = validSample.capturedAt,
                    payloadDigest = digest,
                    payloadBytes = nonCanonicalBytes,
                    eventVersion = 1
                )
            }
        )
    }

    // Case 5.17: Logical probe proving validator components catch corruption and would fail if removed
    @Test
    fun legacyValidationLogicalProbesProveRemovedValidatorsFail() {
        val sample = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "PROBE-001")
        val canonicalJson = CanonicalJsonSerializer.serialize(sample)
        val nonCanonicalJson = canonicalJson.replace("{\"", "{\n  \"")
        assertNotEquals("Whitespace must be introduced into non-canonical JSON", canonicalJson, nonCanonicalJson)
        val nonCanonicalBytes = nonCanonicalJson.toByteArray(StandardCharsets.UTF_8)

        // Probe 1: Strict JSON parser alone parses non-canonical whitespace into a valid model without error.
        // This proves that model deserialization alone does NOT catch non-canonical whitespace!
        val parsedJson = StrictJsonParser.parse(nonCanonicalJson).asObject()
        val parsedModel = OfflineSaleEvent.fromStrictJson(parsedJson)
        assertEquals(sample.clientEventId, parsedModel.clientEventId)

        // Probe 2: CanonicalJsonSerializer produces canonical bytes that differ from the non-canonical bytes.
        // This proves the byte-exact parity check `bytes.contentEquals(canonicalBytes)` is strictly active and necessary.
        val canonicalBytes = CanonicalJsonSerializer.serialize(parsedModel).toByteArray(StandardCharsets.UTF_8)
        assertFalse("Parity probe: canonical serializer must detect non-canonical whitespace divergence",
            nonCanonicalBytes.contentEquals(canonicalBytes))

        // Probe 3: Changing any immutable metadata field causes either digest mismatch or equality failure
        val mutatedActor = sample.copy(actorId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8D1FF99"))
        val mutatedBytes = CanonicalJsonSerializer.serialize(mutatedActor).toByteArray(StandardCharsets.UTF_8)
        val mutatedDigest = PayloadDigest.fromBytes(mutatedBytes).value
        assertNotEquals("Digest must change when actorId changes", sample.computePayloadDigest().value, mutatedDigest)
    }

    // Case 6: concurrent initial key creation two instances alias locking test with barriers exercises production key coordination seam
    @Test
    fun concurrentInitialKeyCreationTwoInstancesExercisesProductionKeyCoordinationSeamWithBarriers() {
        val keyAlias = "small_trader_economic_outbox_v1"
        SessionProcessLocks.clearForTesting()

        // Verify production lock seam: both instances must retrieve the exact same lock object
        val lock1 = SessionProcessLocks.getKeyLock(keyAlias)
        val lock2 = SessionProcessLocks.getKeyLock(keyAlias)
        assertSame("Production key coordination seam must share the exact lock object for alias", lock1, lock2)

        // Fake backend storage that emulates keystore storage without internal synchronization coordination
        class FakeKeyStoreBackend : KeyStoreBackend {
            val storage = ConcurrentHashMap<String, SecretKey>()
            val generationAttempts = AtomicInteger(0)
            val generationStartedLatch = CountDownLatch(1)
            val releaseGenerationLatch = CountDownLatch(1)

            override fun containsAlias(alias: String): Boolean = storage.containsKey(alias)

            override fun getKey(alias: String): SecretKey? = storage[alias]

            override fun generateKey(alias: String): SecretKey {
                generationAttempts.incrementAndGet()
                generationStartedLatch.countDown()
                val released = releaseGenerationLatch.await(5, TimeUnit.SECONDS)
                if (!released) throw TimeoutException("Key generation wait timed out")
                val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                storage[alias] = key
                return key
            }
        }

        val fakeBackend = FakeKeyStoreBackend()

        // Instantiate two real production AndroidKeyStoreCryptoEngine instances sharing the same alias and backend
        val engine1 = AndroidKeyStoreCryptoEngine(keyAlias = keyAlias, allowCreationOnEncrypt = true, backend = fakeBackend)
        val engine2 = AndroidKeyStoreCryptoEngine(keyAlias = keyAlias, allowCreationOnEncrypt = true, backend = fakeBackend)

        val errors = CopyOnWriteArrayList<Throwable>()
        val thread2FinishedBeforeRelease = AtomicBoolean(false)
        val thread2Calling = CountDownLatch(1)

        val t1 = Thread {
            try {
                // Thread 1 calls production ensureKeyUsable(false), entering getOrCreateKey() and acquiring production keyLock
                engine1.ensureKeyUsable(false)
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        val t2 = Thread {
            try {
                // Ensure thread 1 has started key generation and holds production keyLock
                assertTrue("Thread 1 must enter key generation and hold lock",
                    fakeBackend.generationStartedLatch.await(5, TimeUnit.SECONDS))
                // Thread 2 calls production ensureKeyUsable(false); must block on production keyLock held by Thread 1
                thread2Calling.countDown()
                engine2.ensureKeyUsable(false)
                if (fakeBackend.releaseGenerationLatch.count > 0) {
                    thread2FinishedBeforeRelease.set(true)
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        t1.start()
        t2.start()

        // Verify thread 1 entered generation
        assertTrue("Thread 1 must enter key generation within timeout",
            fakeBackend.generationStartedLatch.await(5, TimeUnit.SECONDS))

        try {
            assertTrue("Second production invocation must start", thread2Calling.await(5, TimeUnit.SECONDS))
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var observedProductionLockWait = false
            while (System.nanoTime() < deadline && !observedProductionLockWait) {
                val info = bean.getThreadInfo(t2.id)
                observedProductionLockWait = info != null && info.threadState == Thread.State.BLOCKED &&
                    info.lockOwnerId == t1.id && info.lockInfo?.identityHashCode == System.identityHashCode(lock1)
                if (!observedProductionLockWait) Thread.yield()
            }
            assertTrue("Second engine must actually wait on the production alias lock owned by first engine", observedProductionLockWait)
            assertFalse("Second invocation cannot finish before generation release", thread2FinishedBeforeRelease.get())
            assertEquals("Only the lock owner may generate", 1, fakeBackend.generationAttempts.get())
        } finally {
            fakeBackend.releaseGenerationLatch.countDown()
        }

        t1.join(5000)
        t2.join(5000)
        assertFalse("Thread 1 must terminate within timeout", t1.isAlive)
        assertFalse("Thread 2 must terminate within timeout", t2.isAlive)
        assertTrue("No thread execution errors: $errors", errors.isEmpty())

        assertEquals("Production coordination seam must generate key exactly once across both instances",
            1, fakeBackend.generationAttempts.get())

        // Verify cross-instance encrypt/decrypt compatibility with the coordinated key
        val plaintext = "coordinated-production-key-verification".toByteArray(StandardCharsets.UTF_8)
        val aad = "aad-binding-metadata".toByteArray(StandardCharsets.UTF_8)
        val (iv, ciphertext) = engine1.encrypt(plaintext, aad)
        val decrypted = engine2.decrypt(iv, ciphertext, aad)
        assertArrayEquals("Engine 2 must successfully decrypt payload encrypted by Engine 1 using coordinated key",
            plaintext, decrypted)

        // Verify concurrent encrypt calls across both instances also succeed with single key
        val concurrentPlaintext = "concurrent-encrypt-payload".toByteArray(StandardCharsets.UTF_8)
        val (iv2, ciphertext2) = engine2.encrypt(concurrentPlaintext, aad)
        val decrypted2 = engine1.decrypt(iv2, ciphertext2, aad)
        assertArrayEquals("Engine 1 must decrypt payload encrypted by Engine 2", concurrentPlaintext, decrypted2)
        assertEquals("Total generation count must remain 1 after additional encrypt calls",
            1, fakeBackend.generationAttempts.get())

        // Explicit test boundary documentation:
        // In-process JVM coordination only; does NOT claim native Keystore / multiprocess proof from JVM.
    }
}

