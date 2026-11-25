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
    fun trustedTime(lease: OfflineSaleLease): Long? = null
    fun getLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): OfflineSaleLease?
    fun saveLease(lease: OfflineSaleLease, isCatalogComplete: Boolean, requestElapsed: Long? = null)
    fun getVerifiedCatalogSnapshotVersion(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): String?
    fun isCatalogComplete(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): Boolean
    fun saveVerifiedCatalogSnapshot(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid, snapshotVersion: String, isComplete: Boolean)
    fun saveCatalogPages(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid, version: String, pages: List<ByteArray>)
    fun getCatalogPages(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): List<ByteArray>
    fun clearLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid)
}

internal fun androidBootIdentity(context: android.content.Context): String? = try {
    android.provider.Settings.Global.getInt(context.applicationContext.contentResolver,
        android.provider.Settings.Global.BOOT_COUNT).takeIf { it >= 0 }?.toString()
} catch (_: Exception) { null }

class SqliteOfflineLeaseStore internal constructor(
    private val crypto: com.example.data.mobile.session.SessionCryptoEngine,
    private val bootIdentity: () -> String? = { null },
    private val dbProvider: () -> SupportSQLiteDatabase
) : OfflineLeaseStore {
    constructor(context: android.content.Context, dbProvider: () -> SupportSQLiteDatabase) : this(
        com.example.data.mobile.session.AndroidKeyStoreCryptoEngine("small_trader_offline_lease_v1"),
        { androidBootIdentity(context) }, dbProvider)

    private fun aad(c: String, a: String, d: String, lease: String, version: String, complete: Boolean): ByteArray =
        CanonicalJsonSerializer.serialize(listOf("offline-lease:1", c, a, d, lease, version, complete)).toByteArray(Charsets.UTF_8)

    private fun seal(bytes: ByteArray, aad: ByteArray): ByteArray {
        require(bytes.size <= com.example.data.mobile.contract.ContractConstants.MAX_PAYLOAD_BYTES)
        crypto.ensureKeyUsable(false)
        val (iv, ciphertext) = crypto.encrypt(bytes, aad)
        require(iv.size == 12)
        return iv + ciphertext
    }

    private fun open(bytes: ByteArray, aad: ByteArray): ByteArray {
        require(bytes.size in 28..(com.example.data.mobile.contract.ContractConstants.MAX_PAYLOAD_BYTES + 28))
        crypto.ensureKeyUsable(true)
        return crypto.decrypt(bytes.copyOfRange(0, 12), bytes.copyOfRange(12, bytes.size), aad)
    }

    private val trustedTimes = ConcurrentHashMap<String, Long>()
    private fun timeKey(lease: OfflineSaleLease) = "${lease.companyId.value}:${lease.actorId.value}:${lease.deviceId.value}:${lease.leaseId.value}"
    override fun trustedTime(lease: OfflineSaleLease): Long? {
        getLease(lease.companyId, lease.actorId, lease.deviceId) ?: return null
        return trustedTimes[timeKey(lease)]
    }
    init {
        ensureTable()
    }

    private fun ensureTable() {
        val db = dbProvider()
        db.execSQL("CREATE TABLE IF NOT EXISTS offline_lease_baselines (company_id TEXT NOT NULL, actor_id TEXT NOT NULL, device_id TEXT NOT NULL, lease_id TEXT NOT NULL, version TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(company_id,actor_id,device_id,lease_id))")
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
            "SELECT lease_payload_bytes, lease_id, catalog_snapshot_version, is_catalog_complete FROM offline_sale_leases WHERE company_id = ? AND actor_id = ? AND device_id = ?",
            arrayOf(companyId.value, actorId.value, deviceId.value)
        )
        return cursor.use { c ->
            if (c.moveToFirst()) {
                val blob = c.getBlob(0)
                if (blob == null || blob.isEmpty()) null
                else {
                    try {
                        val bytes = open(blob, aad(companyId.value, actorId.value, deviceId.value, c.getString(1), c.getString(2), c.getInt(3) == 1))
                        val envelope = StrictJsonParser.parseObject(String(bytes, StandardCharsets.UTF_8))
                        require(envelope.require("continuity").asString() == requireNotNull(bootIdentity()))
                        val elapsed = envelope.require("elapsed").asString().toLong()
                        val delta = android.os.SystemClock.elapsedRealtime() - elapsed
                        require(delta >= 0)
                        val lease = OfflineSaleLease.fromStrictJson(envelope.require("lease").asObject())
                        require(delta < Math.multiplyExact(lease.maxOfflineHours, 3_600_000L))
                        val issued = requireNotNull(OfflineSaleLease.parseRfc3339Millis(lease.issuedAt))
                        val trusted = Math.addExact(issued, delta)
                        require(lease.isTimeValid(trusted))
                        trustedTimes[timeKey(lease)] = trusted
                        require(lease.companyId == companyId && lease.actorId == actorId && lease.deviceId == deviceId && lease.leaseId.value == c.getString(1))
                        require(lease.catalogSnapshotVersion == c.getString(2))
                        lease
                    } catch (_: Exception) {
                        null
                    }
                }
            } else null
        }
    }

    override fun saveLease(lease: OfflineSaleLease, isCatalogComplete: Boolean, requestElapsed: Long?) {
        val db = dbProvider()
        ensureTable()
        db.beginTransaction()
        try {
        val retained = getLease(lease.companyId, lease.actorId, lease.deviceId)
        if (retained?.leaseId == lease.leaseId) {
            require(retained.copy(postedSalesCount = lease.postedSalesCount, postedTotalAmount = lease.postedTotalAmount) == lease) {
                "Same lease policy changed; a new server lease is required"
            }
            db.setTransactionSuccessful()
            return // preserve original lifetime baseline and clock anchor
        }
        val anchor = requestElapsed ?: android.os.SystemClock.elapsedRealtime()
        require(anchor in 0..android.os.SystemClock.elapsedRealtime())
        // Reacquisition preserves the first authenticated boot, clock anchor and budget.
        // Local immutable history includes confirmations.
        var originalAnchor: Long? = null
        val baseline = db.query(
            "SELECT payload, version FROM offline_lease_baselines WHERE company_id=? AND actor_id=? AND device_id=? AND lease_id=?",
            arrayOf(lease.companyId.value, lease.actorId.value, lease.deviceId.value, lease.leaseId.value)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val envelope = StrictJsonParser.parseObject(String(open(cursor.getBlob(0), aad(lease.companyId.value,
                    lease.actorId.value, lease.deviceId.value, lease.leaseId.value, cursor.getString(1), true)), StandardCharsets.UTF_8))
                require(envelope.require("continuity").asString() == requireNotNull(bootIdentity())) {
                    "Boot changed; a new server lease ID is required"
                }
                originalAnchor = envelope.require("elapsed").asString().toLong()
                OfflineSaleLease.fromStrictJson(envelope.require("lease").asObject())
            }
        }
        val original = baseline ?: db.query(
            "SELECT lease_payload_bytes, catalog_snapshot_version, is_catalog_complete FROM offline_sale_leases WHERE company_id=? AND actor_id=? AND device_id=? AND lease_id=?",
            arrayOf(lease.companyId.value, lease.actorId.value, lease.deviceId.value, lease.leaseId.value)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val bytes = open(cursor.getBlob(0), aad(lease.companyId.value, lease.actorId.value,
                    lease.deviceId.value, lease.leaseId.value, cursor.getString(1), cursor.getInt(2) == 1))
                val envelope = StrictJsonParser.parseObject(String(bytes, StandardCharsets.UTF_8))
                require(envelope.require("continuity").asString() == requireNotNull(bootIdentity())) { "Boot changed; a new server lease ID is required" }
                originalAnchor = envelope.require("elapsed").asString().toLong()
                OfflineSaleLease.fromStrictJson(envelope.require("lease").asObject())
            }
        }
        val effectiveLease = if (original == null) lease else {
            require(original.companyId == lease.companyId && original.actorId == lease.actorId && original.deviceId == lease.deviceId)
            require(original.copy(postedSalesCount = lease.postedSalesCount, postedTotalAmount = lease.postedTotalAmount) == lease) {
                "Same lease policy changed; a new server lease is required"
            }
            lease.copy(postedSalesCount = original.postedSalesCount, postedTotalAmount = original.postedTotalAmount)
        }
        val canonicalBytes = CanonicalJsonSerializer.serialize(mapOf(
            "lease" to effectiveLease, "continuity" to requireNotNull(bootIdentity()) { "Boot continuity unavailable" },
            "elapsed" to (originalAnchor ?: anchor).toString()
        )).toByteArray(StandardCharsets.UTF_8)
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
                seal(canonicalBytes, aad(lease.companyId.value, lease.actorId.value, lease.deviceId.value, lease.leaseId.value, lease.catalogSnapshotVersion, isCatalogComplete)),
                if (isCatalogComplete) 1 else 0,
                now,
                now
            )
        )
        db.execSQL("INSERT OR IGNORE INTO offline_lease_baselines VALUES (?,?,?,?,?,?)", arrayOf(
            lease.companyId.value, lease.actorId.value, lease.deviceId.value, lease.leaseId.value,
            effectiveLease.catalogSnapshotVersion, seal(canonicalBytes,
                aad(lease.companyId.value, lease.actorId.value, lease.deviceId.value, lease.leaseId.value, effectiveLease.catalogSnapshotVersion, true))))
        db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun getVerifiedCatalogSnapshotVersion(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): String? {
        val db = dbProvider()
        ensureTable()
        val cursor = db.query(
            "SELECT catalog_snapshot_version, lease_payload_bytes, lease_id, is_catalog_complete FROM offline_sale_leases WHERE company_id = ? AND actor_id = ? AND device_id = ?",
            arrayOf(companyId.value, actorId.value, deviceId.value)
        )
        return cursor.use { c ->
            if (!c.moveToFirst()) null else try {
                open(c.getBlob(1), aad(companyId.value, actorId.value, deviceId.value, c.getString(2), c.getString(0), c.getInt(3) == 1))
                c.getString(0)
            } catch (_: Exception) { null }
        }
    }

    override fun isCatalogComplete(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): Boolean {
        if (getVerifiedCatalogSnapshotVersion(companyId, actorId, deviceId) == null || getCatalogPages(companyId, actorId, deviceId).isEmpty()) return false
        return dbProvider().query("SELECT is_catalog_complete FROM offline_sale_leases WHERE company_id=? AND actor_id=? AND device_id=?",
            arrayOf(companyId.value, actorId.value, deviceId.value)).use { it.moveToFirst() && it.getInt(0) == 1 }
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
        if (existingLease != null && existingLease.catalogSnapshotVersion == snapshotVersion) {
            require(isCatalogComplete(companyId, actorId, deviceId) == isComplete) { "Lease catalog state cannot be changed after acquisition" }
        } else {
            val bytes = seal("{}".toByteArray(), aad(companyId.value, actorId.value, deviceId.value, "", snapshotVersion, isComplete))
            db.execSQL("INSERT OR REPLACE INTO offline_sale_leases (company_id,actor_id,device_id,lease_id,catalog_snapshot_version,lease_payload_bytes,is_catalog_complete,created_at,updated_at) VALUES (?,?,?,'',?,?,?,0,0)",
                arrayOf(companyId.value, actorId.value, deviceId.value, snapshotVersion, bytes, if (isComplete) 1 else 0))
        }
    }

    override fun saveCatalogPages(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid, version: String, pages: List<ByteArray>) {
        require(pages.isNotEmpty())
        val bytes = CanonicalJsonSerializer.serialize(pages.map { String(it, Charsets.UTF_8) }).toByteArray(Charsets.UTF_8)
        val sealed = seal(bytes, aad(companyId.value, actorId.value, deviceId.value, "catalog", version, true))
        val db = dbProvider()
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS offline_catalog (company_id TEXT, actor_id TEXT, device_id TEXT, version TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(company_id,actor_id,device_id))")
            db.execSQL("INSERT OR REPLACE INTO offline_catalog VALUES (?,?,?,?,?)", arrayOf(companyId.value, actorId.value, deviceId.value, version, sealed))
            saveVerifiedCatalogSnapshot(companyId, actorId, deviceId, version, true)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun getCatalogPages(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): List<ByteArray> {
        val version = getVerifiedCatalogSnapshotVersion(companyId, actorId, deviceId) ?: return emptyList()
        return try {
            dbProvider().query("SELECT payload FROM offline_catalog WHERE company_id=? AND actor_id=? AND device_id=? AND version=?",
                arrayOf(companyId.value, actorId.value, deviceId.value, version)).use {
                if (!it.moveToFirst()) emptyList() else {
                    val bytes = open(it.getBlob(0), aad(companyId.value, actorId.value, deviceId.value, "catalog", version, true))
                    val pages = StrictJsonParser.parse(String(bytes, Charsets.UTF_8)).asArray().elements.map { page -> page.asString().toByteArray(Charsets.UTF_8) }
                    require(pages.isNotEmpty())
                    val cursors = HashSet<String>()
                    val productIds = HashSet<String>()
                    pages.forEachIndexed { index, pageBytes ->
                        val page = com.example.data.mobile.readclient.ProductCatalogResponse.fromStrictJson(
                            StrictJsonParser.parseObject(String(pageBytes, Charsets.UTF_8)))
                        require(page.companyId == companyId && page.catalogSnapshotVersion == version)
                        require(page.products.all { productIds.add(it.id.value) })
                        require(page.hasMore == (index < pages.lastIndex))
                        if (page.hasMore) require(cursors.add(requireNotNull(page.nextCursor)))
                        else require(page.nextCursor == null)
                    }
                    pages
                }
            }
        } catch (_: Exception) { emptyList() }
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

    override fun saveLease(lease: OfflineSaleLease, isCatalogComplete: Boolean, requestElapsed: Long?) {
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

    private val catalogs = ConcurrentHashMap<String, List<ByteArray>>()
    override fun saveCatalogPages(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid, version: String, pages: List<ByteArray>) {
        catalogs[key(companyId, actorId, deviceId)] = pages.map { it.copyOf() }
        saveVerifiedCatalogSnapshot(companyId, actorId, deviceId, version, true)
    }
    override fun getCatalogPages(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid): List<ByteArray> = catalogs[key(companyId, actorId, deviceId)].orEmpty().map { it.copyOf() }
    override fun clearLease(companyId: ServerUlid, actorId: ServerUlid, deviceId: ClientUuid) {
        map.remove(key(companyId, actorId, deviceId))
    }
}
