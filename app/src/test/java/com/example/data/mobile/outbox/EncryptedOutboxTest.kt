package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.CanonicalJsonSerializer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedOutboxTest {
    private fun entity(): OutboxEventEntity {
        val e = OutboxTestFixtures.createSampleEvent(localReceiptNumber = "SECRET-RECEIPT-83917")
        val bytes = CanonicalJsonSerializer.serialize(e).toByteArray()
        return OutboxEventEntity(e.clientEventId.value, e.companyId.value, e.actorId.value,
            e.deviceId.value, e.deviceLeaseId.value, e.capturedAt, e.computePayloadDigest().value, bytes)
    }

    @Test fun exactBytesNonceTamperScopeAndBounds() {
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

    @Test fun missingInvalidCancellationAndFatalPreserveCiphertext() {
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

    @Test fun diskReopenAndSidecarsContainNoPayloadSecret() {
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

    @Test fun explicitLegacyConversionRollsBackThenReopensWithoutSchemaChange() {
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
        val b = OutboxEventEntity(UUID.randomUUID().toString(), a.companyId, a.actorId, a.deviceId,
            a.deviceLeaseId, a.capturedAt, a.payloadDigest, a.payloadBytes)
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
}
