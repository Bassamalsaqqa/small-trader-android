package com.example.data.mobile.offline

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.v2.OfflineSaleLease
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable store for active offline sale leases and verified catalog snapshot versions.
 *
 * Invariants:
 * - Scoped strictly to (companyId, actorId, deviceId).
 * - Partial catalog responses (hasMore == true) must NEVER be treated as complete.
 * - Leases survive app restart and process termination.
 */
interface OfflineLeaseStore {
    fun getLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): OfflineSaleLease?
    fun saveLease(lease: OfflineSaleLease, isCatalogComplete: Boolean)
    fun getVerifiedCatalogSnapshotVersion(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): String?
    fun isCatalogComplete(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): Boolean
    fun saveVerifiedCatalogSnapshot(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid, snapshotVersion: String, isComplete: Boolean)
    fun clearLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid)
}

class SqliteOfflineLeaseStore(
    private val dbProvider: () -> SupportSQLiteDatabase
) : OfflineLeaseStore {

    init {
        ensureTable()
    }

    private fun ensureTable() {
        val db = dbProvider()
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `offline_sale_leases` (
                `company_id` TEXT NOT NULL,
                `actor_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `lease_id` TEXT NOT NULL,
                `catalog_snapshot_version` TEXT NOT NULL,
                `lease_payload_bytes` BLOB NOT NULL,
                `is_catalog_complete` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`company_id`, `actor_id`, `device_id`)
            )
        """.trimIndent())
    }

    override fun getLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): OfflineSaleLease? {
        val db = dbProvider()
        ensureTable()
        val cursor = db.query(
            "SELECT lease_payload_bytes FROM offline_sale_leases WHERE company_id = ? AND actor_id = ? AND device_id = ?",
            arrayOf(companyId.value, actorId.value, deviceId.value)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) {
                val blob = c.getBlob(0)
                if (blob == null || blob.isEmpty()) null
                else {
                    try {
                        val json = StrictJsonParser.parseObject(String(blob, StandardCharsets.UTF_8))
                        OfflineSaleLease.fromStrictJson(json)
                    } catch (_: Exception) {
                        null
                    }
                }
            } else null
        }
    }

    override fun saveLease(lease: OfflineSaleLease, isCatalogComplete: Boolean) {
        val db = dbProvider()
        ensureTable()
        val canonicalBytes = CanonicalJsonSerializer.serialize(lease).toByteArray(StandardCharsets.UTF_8)
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR REPLACE INTO offline_sale_leases 
            (company_id, actor_id, device_id, lease_id, catalog_snapshot_version, lease_payload_bytes, is_catalog_complete, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                lease.companyId.value,
                lease.actorId.value,
                lease.deviceId.value,
                lease.leaseId.value,
                lease.catalogSnapshotVersion,
                canonicalBytes,
                if (isCatalogComplete) 1 else 0,
                now,
                now
            )
        )
    }

    override fun getVerifiedCatalogSnapshotVersion(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): String? {
        val db = dbProvider()
        ensureTable()
        val cursor = db.query(
            "SELECT catalog_snapshot_version FROM offline_sale_leases WHERE company_id = ? AND actor_id = ? AND device_id = ?",
            arrayOf(companyId.value, actorId.value, deviceId.value)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    override fun isCatalogComplete(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): Boolean {
        val db = dbProvider()
        ensureTable()
        val cursor = db.query(
            "SELECT is_catalog_complete FROM offline_sale_leases WHERE company_id = ? AND actor_id = ? AND device_id = ?",
            arrayOf(companyId.value, actorId.value, deviceId.value)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) c.getInt(0) == 1 else false
        }
    }

    override fun saveVerifiedCatalogSnapshot(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        snapshotVersion: String,
        isComplete: Boolean
    ) {
        val db = dbProvider()
        ensureTable()
        val existingLease = getLease(companyId, actorId, deviceId)
        val now = System.currentTimeMillis()
        if (existingLease != null) {
            db.execSQL(
                "UPDATE offline_sale_leases SET catalog_snapshot_version = ?, is_catalog_complete = ?, updated_at = ? WHERE company_id = ? AND actor_id = ? AND device_id = ?",
                arrayOf(snapshotVersion, if (isComplete) 1 else 0, now, companyId.value, actorId.value, deviceId.value)
            )
        } else {
            db.execSQL(
                """
                INSERT OR REPLACE INTO offline_sale_leases
                (company_id, actor_id, device_id, lease_id, catalog_snapshot_version, lease_payload_bytes, is_catalog_complete, created_at, updated_at)
                VALUES (?, ?, ?, '', ?, X'', ?, ?, ?)
                """.trimIndent(),
                arrayOf(companyId.value, actorId.value, deviceId.value, snapshotVersion, if (isComplete) 1 else 0, now, now)
            )
        }
    }

    override fun clearLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid) {
        val db = dbProvider()
        ensureTable()
        db.execSQL(
            "DELETE FROM offline_sale_leases WHERE company_id = ? AND actor_id = ? AND device_id = ?",
            arrayOf(companyId.value, actorId.value, deviceId.value)
        )
    }
}

class InMemoryOfflineLeaseStore : OfflineLeaseStore {
    private data class Entry(val lease: OfflineSaleLease?, val snapshotVersion: String, val isComplete: Boolean)
    private val map = ConcurrentHashMap<String, Entry>()

    private fun key(c: ServerUlid, a: ServerUlid, d: ClientUuid) = "${c.value}:${a.value}:${d.value}"

    override fun getLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): OfflineSaleLease? =
        map[key(companyId, actorId, deviceId)]?.lease

    override fun saveLease(lease: OfflineSaleLease, isCatalogComplete: Boolean) {
        map[key(lease.companyId, lease.actorId, lease.deviceId)] =
            Entry(lease, lease.catalogSnapshotVersion, isCatalogComplete)
    }

    override fun getVerifiedCatalogSnapshotVersion(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): String? =
        map[key(companyId, actorId, deviceId)]?.snapshotVersion

    override fun isCatalogComplete(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): Boolean =
        map[key(companyId, actorId, deviceId)]?.isComplete ?: false

    override fun saveVerifiedCatalogSnapshot(
        companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid, snapshotVersion: String, isComplete: Boolean
    ) {
        val k = key(companyId, actorId, deviceId)
        val existing = map[k]?.lease
        map[k] = Entry(existing, snapshotVersion, isComplete)
    }

    override fun clearLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid) {
        map.remove(key(companyId, actorId, deviceId))
    }
}
