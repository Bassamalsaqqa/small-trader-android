package com.example.data.mobile.outbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxMigrationTest {
    @Test fun upgradesPopulatedVersionOneWithoutLosingPayloadOrDeliveryState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schemaFile = listOf(File("schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json"), File("app/schemas/com.example.data.mobile.outbox.OutboxDatabase/1.json")).first { it.exists() }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val original = byteArrayOf(1, 3, 0, 7, 9)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.setForeignKeyConstraintsEnabled(true)
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            legacy.execSQL("INSERT INTO outbox_events (client_event_id,company_id,actor_id,device_id,device_lease_id,captured_at,payload_digest,event_version,payload_bytes) VALUES ('event','company','actor','device','lease','time','digest',2,?)", arrayOf(original))
            legacy.execSQL("INSERT INTO outbox_delivery_states (client_event_id,company_id,actor_id,device_id,status,attempt_count,created_at,updated_at) VALUES ('event','company','actor','device','unknown_ack',3,1,2)")
            legacy.execSQL("INSERT INTO outbox_scope_locks VALUES ('company','actor','device',1,'auth_denied')")
            legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            legacy.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(schema.getString("identityHash")))
            legacy.version = 1
        }
        val migrated = OutboxDatabase.buildDatabase(context, name, allowMainThreadQueries = true)
        try {
            val db = migrated.openHelper.writableDatabase
            db.query("SELECT payload_bytes,device_lease_id FROM outbox_events").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertArrayEquals(original, cursor.getBlob(0)); assertEquals("lease", cursor.getString(1))
            }
            db.query("SELECT status,attempt_count FROM outbox_delivery_states").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("unknown_ack", cursor.getString(0)); assertEquals(3, cursor.getInt(1))
            }
            db.query("SELECT reason FROM outbox_scope_locks").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("auth_denied", cursor.getString(0)) }
            db.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        } finally {
            migrated.close(); context.deleteDatabase(name)
        }
    }
}
