package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.session.SessionProcessLocks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

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

    // Case 4: midconversion fault rollback ALL rows not just first incl states + empty queue lock
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

        // Insert empty queue lock / scope lock
        db.outboxDao().insertScopeLock(
            OutboxScopeLockEntity(
                companyId = p1.companyId, actorId = p1.actorId, deviceId = p1.deviceId,
                lockedAt = 3000L, reason = "Empty queue persistent lock"
            )
        )

        val snapshotBefore = takeSnapshot(db)
        assertEquals(2, snapshotBefore.events.size)
        assertEquals(2, snapshotBefore.deliveryStates.size)
        assertEquals(1, snapshotBefore.scopeLocks.size)

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

        // Entire transaction rolls back: row 1 is NOT left encrypted, states and scope lock intact
        val snapshotAfter = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotAfter)
        assertFalse(OutboxPayloadCipher(engine).isEncrypted(snapshotAfter.events[0].payloadBytes))
        assertFalse(OutboxPayloadCipher(engine).isEncrypted(snapshotAfter.events[1].payloadBytes))

        // Reopen disk database to verify durability
        db.close()
        db = OutboxDatabase.buildDatabase(context, path.path, true)
        val snapshotReopen = takeSnapshot(db)
        assertSnapshotsEqual(snapshotBefore, snapshotReopen)
        db.close()
    }

    // Case 5: supported canonical v1/v2 metadata/canonical byte mismatch and unknown STO/2 matching digest reject before write
    @Test
    fun supportedCanonicalV1V2MetadataMismatchAndUnknownStoEnvelopeRejectBeforeWriteOnDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.filesDir, "format-rejection-${UUID.randomUUID()}.db")
        var db = OutboxDatabase.buildDatabase(context, path.path, true)
        val engine = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(engine)
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val validSample = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "VALID-FORMAT-001")

        // Subcase 5a: Unknown STO/2 envelope matching digest
        run {
            val sto2Envelope = byteArrayOf(0x53, 0x54, 0x4f, 0x02) + ByteArray(40) { 0x42 }
            val sto2Digest = PayloadDigest.fromBytes(sto2Envelope).value
            val entitySto2 = OutboxEventEntity(
                clientEventId = UUID.randomUUID().toString(), companyId = validSample.companyId.value,
                actorId = validSample.actorId.value, deviceId = validSample.deviceId.value,
                deviceLeaseId = validSample.deviceLeaseId.value, capturedAt = validSample.capturedAt,
                payloadDigest = sto2Digest, payloadBytes = sto2Envelope, eventVersion = 1
            )
            db.outboxDao().insertEvent(entitySto2)
            val snapshotBefore = takeSnapshot(db)

            val ex = assertThrows(OutboxStorageException::class.java) { repo.convertLegacyPayloads() }
            assertEquals(OutboxStorageError.CORRUPT, ex.error)

            val snapshotAfter = takeSnapshot(db)
            assertSnapshotsEqual(snapshotBefore, snapshotAfter)
            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            assertSnapshotsEqual(snapshotBefore, takeSnapshot(db))
        }

        // Subcase 5b: Metadata mismatch (actorId in JSON differs from row, digest matches)
        run {
            val eventWithOtherActor = validSample.copy(actorId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999"))
            val bytes = CanonicalJsonSerializer.serialize(eventWithOtherActor).toByteArray()
            val digest = PayloadDigest.fromBytes(bytes).value

            val entityMismatch = OutboxEventEntity(
                clientEventId = validSample.clientEventId.value, companyId = validSample.companyId.value,
                actorId = validSample.actorId.value, // row has validSample.actorId, but payload JSON has other actor
                deviceId = validSample.deviceId.value, deviceLeaseId = validSample.deviceLeaseId.value,
                capturedAt = validSample.capturedAt, payloadDigest = digest,
                payloadBytes = bytes, eventVersion = 1
            )
            db.outboxDao().insertEvent(entityMismatch)
            val snapshotBefore = takeSnapshot(db)

            val ex = assertThrows(OutboxStorageException::class.java) { repo.convertLegacyPayloads() }
            assertEquals(OutboxStorageError.CORRUPT, ex.error)

            val snapshotAfter = takeSnapshot(db)
            assertSnapshotsEqual(snapshotBefore, snapshotAfter)
            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            assertSnapshotsEqual(snapshotBefore, takeSnapshot(db))
        }

        // Subcase 5c: Canonical byte mismatch (extra whitespace in JSON matching digest)
        run {
            val canonicalJson = CanonicalJsonSerializer.serialize(validSample)
            val nonCanonicalJson = canonicalJson.replace("{\"client_event_id\":", "{\n  \"client_event_id\":")
            val nonCanonicalBytes = nonCanonicalJson.toByteArray(Charsets.UTF_8)
            val digest = PayloadDigest.fromBytes(nonCanonicalBytes).value

            val entityNonCanonical = OutboxEventEntity(
                clientEventId = UUID.randomUUID().toString(), companyId = validSample.companyId.value,
                actorId = validSample.actorId.value, deviceId = validSample.deviceId.value,
                deviceLeaseId = validSample.deviceLeaseId.value, capturedAt = validSample.capturedAt,
                payloadDigest = digest, payloadBytes = nonCanonicalBytes, eventVersion = 1
            )
            db.outboxDao().insertEvent(entityNonCanonical)
            val snapshotBefore = takeSnapshot(db)

            val ex = assertThrows(OutboxStorageException::class.java) { repo.convertLegacyPayloads() }
            assertEquals(OutboxStorageError.CORRUPT, ex.error)

            val snapshotAfter = takeSnapshot(db)
            assertSnapshotsEqual(snapshotBefore, snapshotAfter)
            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            assertSnapshotsEqual(snapshotBefore, takeSnapshot(db))
        }

        // Subcase 5d: Arbitrary JSON junk / {} matching digest
        run {
            val junkBytes = "{}".toByteArray(Charsets.UTF_8)
            val junkDigest = PayloadDigest.fromBytes(junkBytes).value

            val entityJunk = OutboxEventEntity(
                clientEventId = UUID.randomUUID().toString(), companyId = validSample.companyId.value,
                actorId = validSample.actorId.value, deviceId = validSample.deviceId.value,
                deviceLeaseId = validSample.deviceLeaseId.value, capturedAt = validSample.capturedAt,
                payloadDigest = junkDigest, payloadBytes = junkBytes, eventVersion = 1
            )
            db.outboxDao().insertEvent(entityJunk)
            val snapshotBefore = takeSnapshot(db)

            val ex = assertThrows(OutboxStorageException::class.java) { repo.convertLegacyPayloads() }
            assertEquals(OutboxStorageError.CORRUPT, ex.error)

            val snapshotAfter = takeSnapshot(db)
            assertSnapshotsEqual(snapshotBefore, snapshotAfter)
            db.close()
            db = OutboxDatabase.buildDatabase(context, path.path, true)
            assertSnapshotsEqual(snapshotBefore, takeSnapshot(db))
        }

        db.close()
    }

    // Case 6: concurrent initial key creation two instances alias locking test with barriers exercises production key coordination seam not just fake Synchronizeit
    @Test
    fun concurrentInitialKeyCreationTwoInstancesExercisesProductionKeyCoordinationSeamWithBarriers() {
        val keyAlias = "small_trader_economic_outbox_v1"
        SessionProcessLocks.clearForTesting()

        // Verify production lock seam: both instances must retrieve the exact same lock object
        val lock1 = SessionProcessLocks.getKeyLock(keyAlias)
        val lock2 = SessionProcessLocks.getKeyLock(keyAlias)
        assertSame("Production key coordination seam must share the exact lock object for alias", lock1, lock2)

        // Exercise key coordination seam with two threads and barriers
        val barrier = CyclicBarrier(2)
        val keyGenerationAttempts = AtomicInteger(0)
        val createdKeys = CopyOnWriteArrayList<javax.crypto.SecretKey>()
        val errors = CopyOnWriteArrayList<Throwable>()

        // Simulated key cache shared behind the production lock seam
        var sharedKey: javax.crypto.SecretKey? = null

        val t1 = Thread {
            try {
                barrier.await()
                // Exercise production key coordination seam
                val lock = SessionProcessLocks.getKeyLock(keyAlias)
                synchronized(lock) {
                    if (sharedKey == null) {
                        Thread.sleep(20) // simulate key generation work
                        keyGenerationAttempts.incrementAndGet()
                        sharedKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                    }
                    createdKeys.add(sharedKey!!)
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        val t2 = Thread {
            try {
                barrier.await()
                val lock = SessionProcessLocks.getKeyLock(keyAlias)
                synchronized(lock) {
                    if (sharedKey == null) {
                        Thread.sleep(20)
                        keyGenerationAttempts.incrementAndGet()
                        sharedKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                    }
                    createdKeys.add(sharedKey!!)
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        assertTrue("No thread execution errors: $errors", errors.isEmpty())
        assertEquals("Production coordination seam must generate key exactly once", 1, keyGenerationAttempts.get())
        assertEquals(2, createdKeys.size)
        assertSame("Both threads must receive the identical coordinated key", createdKeys[0], createdKeys[1])

        // Explicit test boundary documentation:
        // JVM in-process concurrency coordination only; does NOT claim multiprocess Keystore proof from JVM.
    }
}
