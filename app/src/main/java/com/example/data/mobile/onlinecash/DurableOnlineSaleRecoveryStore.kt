package com.example.data.mobile.onlinecash

import android.content.Context
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.session.AndroidKeyStoreCryptoEngine
import com.example.data.mobile.session.BoundedStreamReader
import com.example.data.mobile.session.SessionCryptoEngine
import com.example.data.mobile.session.SessionProcessLocks
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * Exception thrown when durable online sale recovery record persistence or retrieval fails.
 * Guarantees fail-closed behavior before any network dispatch.
 */
class OnlineSaleRecoveryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Injectable platform I/O seam for directory durability fsync.
 */
fun interface DirectorySyncEngine {
    @Throws(Exception::class)
    fun syncDirectory(dir: File)
}

/**
 * Standard Android POSIX directory fsync implementation using android.system.Os (minSdk 24).
 * Opens directory with O_RDONLY, calls fsync(fd), and guarantees fd closure.
 */
object AndroidPlatformDirectorySyncEngine : DirectorySyncEngine {
    override fun syncDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(fd)
        } finally {
            android.system.Os.close(fd)
        }
    }
}

/**
 * Immutable durable online sale recovery record.
 *
 * Persisted atomically to encrypted private storage BEFORE any network POST is dispatched.
 * Contains sufficient validated intent data to recover via strict GET-only queries
 * after process death, session timeout, auth lock, or company re-selection across generation restarts.
 *
 * INVARIANTS:
 * - NEVER contains bearer tokens, session secrets, or passwords.
 * - Stores authoritative normative digest, exact economics (payment amount), and identity scope (company, actor, device, origin).
 * - Stores raw canonical RFC 8785 request bytes for deterministic intent re-verification.
 * - [toPreparedOnlineSaleIntent] rebinds intent to the CURRENT authenticated session generation and
 *   validates decoded record totals/currency/identity against the reconstructed profile.
 */
data class DurableOnlineSaleRecoveryRecord(
    val clientEventId: ClientUuid,
    val expectedNormativeProfileDigest: PayloadDigest,
    val companyId: ServerUlid,
    val actorId: ServerUlid,
    val deviceId: ClientUuid,
    val baseOrigin: String,
    val currency: String,
    val currencyMinorUnits: Int,
    val amount: MoneyAmount,
    val rawWireBytes: ByteArray,
    val createdAt: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DurableOnlineSaleRecoveryRecord) return false
        return clientEventId == other.clientEventId &&
                expectedNormativeProfileDigest == other.expectedNormativeProfileDigest &&
                companyId == other.companyId &&
                actorId == other.actorId &&
                deviceId == other.deviceId &&
                baseOrigin == other.baseOrigin &&
                currency == other.currency &&
                currencyMinorUnits == other.currencyMinorUnits &&
                amount == other.amount &&
                createdAt == other.createdAt &&
                rawWireBytes.contentEquals(other.rawWireBytes)
    }

    override fun hashCode(): Int {
        var result = clientEventId.hashCode()
        result = 31 * result + expectedNormativeProfileDigest.hashCode()
        result = 31 * result + companyId.hashCode()
        result = 31 * result + actorId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + baseOrigin.hashCode()
        result = 31 * result + currency.hashCode()
        result = 31 * result + currencyMinorUnits.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + rawWireBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "DurableOnlineSaleRecoveryRecord(eventId=$clientEventId, companyId=$companyId, " +
                "actorId=$actorId, deviceId=$deviceId, currency='$currency', amount=$amount)"

    /**
     * Reconstitutes and rebinds the prepared online sale intent to [reboundGeneration].
     * Re-validates canonical 7-key request bytes and profile digest strictly, and asserts
     * that decoded recovery economics match the reconstructed normative profile.
     */
    fun toPreparedOnlineSaleIntent(reboundGeneration: Long): PreparedOnlineSaleIntent {
        val context = OnlineSaleContext(
            actorId = actorId,
            companyId = companyId,
            currencyMinorUnits = currencyMinorUnits
        )
        val reboundIntent = PreparedOnlineSaleIntent.prepare(
            context = context,
            rawWireBytes = rawWireBytes,
            scopeGeneration = reboundGeneration,
            expectedDigest = expectedNormativeProfileDigest
        )

        // Strict cross-validation of decoded record against reconstructed normative profile
        require(reboundIntent.profile.payment.amount == amount) {
            "Decoded recovery record amount ($amount) does not match reconstructed profile payment amount (${reboundIntent.profile.payment.amount})"
        }
        require(reboundIntent.currency == currency) {
            "Decoded recovery record currency ($currency) does not match reconstructed profile currency (${reboundIntent.currency})"
        }
        require(reboundIntent.clientEventId == clientEventId) {
            "Decoded recovery record event ID ($clientEventId) does not match reconstructed profile event ID (${reboundIntent.clientEventId})"
        }
        require(reboundIntent.deviceId == deviceId) {
            "Decoded recovery record device ID ($deviceId) does not match reconstructed profile device ID (${reboundIntent.deviceId})"
        }
        require(reboundIntent.context.companyId == companyId) {
            "Decoded recovery record company ID ($companyId) does not match reconstructed profile company ID (${reboundIntent.context.companyId})"
        }
        require(reboundIntent.context.actorId == actorId) {
            "Decoded recovery record actor ID ($actorId) does not match reconstructed profile actor ID (${reboundIntent.context.actorId})"
        }

        return reboundIntent
    }

    /**
     * Validates that all record metadata strictly matches the canonical RFC 8785 raw wire payload,
     * reconstructed normative profile, tenant identity, and financial economics.
     * Fails closed with [OnlineSaleRecoveryException] on any discrepancy.
     */
    fun validateConsistency() {
        val rebound = try {
            toPreparedOnlineSaleIntent(reboundGeneration = 1L)
        } catch (e: OnlineSaleRecoveryException) {
            throw e
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException(
                "Inconsistent recovery record for event $clientEventId: ${e.message}",
                e
            )
        }
        if (rebound.clientEventId != clientEventId) {
            throw OnlineSaleRecoveryException("Inconsistent record: event ID mismatch ($clientEventId vs ${rebound.clientEventId})")
        }
        if (rebound.deviceId != deviceId) {
            throw OnlineSaleRecoveryException("Inconsistent record: device ID mismatch ($deviceId vs ${rebound.deviceId})")
        }
        if (rebound.context.companyId != companyId) {
            throw OnlineSaleRecoveryException("Inconsistent record: company ID mismatch ($companyId vs ${rebound.context.companyId})")
        }
        if (rebound.context.actorId != actorId) {
            throw OnlineSaleRecoveryException("Inconsistent record: actor ID mismatch ($actorId vs ${rebound.context.actorId})")
        }
        if (rebound.currency != currency) {
            throw OnlineSaleRecoveryException("Inconsistent record: currency mismatch ($currency vs ${rebound.currency})")
        }
        if (rebound.profile.payment.amount != amount) {
            throw OnlineSaleRecoveryException("Inconsistent record: amount mismatch ($amount vs ${rebound.profile.payment.amount})")
        }
        if (rebound.expectedNormativeProfileDigest != expectedNormativeProfileDigest) {
            throw OnlineSaleRecoveryException("Inconsistent record: digest mismatch ($expectedNormativeProfileDigest vs ${rebound.expectedNormativeProfileDigest})")
        }
    }
}

/**
 * Public interface for durable encrypted online sale recovery storage.
 */
interface DurableOnlineSaleRecoveryStore {
    /**
     * Atomically encrypts and persists [record] to disk BEFORE network dispatch.
     * Enforces compare-and-set reservation and global event UUID uniqueness:
     * - Rejects attempts to save an event ID already present under a different identity scope.
     * - Rejects attempts to overwrite with a different event while an unverified sale is pending for this scope.
     * - Rejects attempts to modify payload bytes or digest of an existing event ID.
     * Throws [OnlineSaleRecoveryException] on conflict, write, crypto, or durability sync failure.
     */
    fun savePendingSale(record: DurableOnlineSaleRecoveryRecord)

    /**
     * Retrieves the pending recovery record matching the exact active identity scope.
     * Returns null if no record exists for this company, actor, device, and baseOrigin.
     * Records for other tenants/actors/origins are strictly isolated and never returned.
     */
    fun getPendingSale(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        baseOrigin: String
    ): DurableOnlineSaleRecoveryRecord?

    /**
     * Crash-safely clears the recovery record matching [clientEventId] AND the exact authenticated identity scope.
     * Prevents cross-scope erasure: if the stored record does not match all scope parameters, it is preserved.
     */
    fun clearPendingSale(
        clientEventId: ClientUuid,
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        baseOrigin: String
    )

    /**
     * Convenience unscoped clear. Fails if multiple records match across scopes.
     * Retained for test backward compatibility; production callers must use scoped [clearPendingSale].
     */
    @Deprecated("Test-only; production callers must use scoped clearPendingSale")
    fun clearPendingSale(clientEventId: ClientUuid)

    /**
     * Returns all records currently held in storage (for testing and diagnostics).
     */
    fun listAllRecords(): List<DurableOnlineSaleRecoveryRecord>

    companion object {
        const val FILE_NAME = "small_trader_online_sale_recovery.bin"
        const val KEY_ALIAS = "small_trader_online_sale_recovery_v1"

        /**
         * Normalizes base origin identically across persistence and retrieval paths.
         * Validates origin policy strictly with NO fail-open fallback:
         * normalizes scheme, host, and default ports (:443/:80) while preserving distinct ports.
         */
        fun canonicalizeOrigin(origin: String, allowHttpLoopback: Boolean = false): String {
            val trimmed = origin.trim().trimEnd('/')
            val validated = EndpointSecurity.validateBaseOrigin(trimmed, allowHttpLoopback)
            val uri = URI(validated)
            val scheme = uri.scheme.lowercase(Locale.ROOT)
            val host = uri.host.lowercase(Locale.ROOT)
            val port = uri.port
            val isDefaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80) || port == -1
            return if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
        }

        fun create(
            context: Context,
            directorySyncEngine: DirectorySyncEngine = AndroidPlatformDirectorySyncEngine
        ): DurableOnlineSaleRecoveryStore {
            val noBackupDir = context.noBackupFilesDir
            val file = File(noBackupDir, FILE_NAME)
            val cryptoEngine = AndroidKeyStoreCryptoEngine(KEY_ALIAS)
            return DefaultDurableOnlineSaleRecoveryStore(
                storageFile = file,
                cryptoEngine = cryptoEngine,
                packageName = context.packageName,
                directorySyncEngine = directorySyncEngine
            )
        }
    }
}

/**
 * Standard implementation of [DurableOnlineSaleRecoveryStore].
 *
 * Uses AES-256-GCM authenticated encryption backed by AndroidKeyStore / [SessionCryptoEngine].
 * Enforces atomic replacement via temporary file and fd.sync() before rename, and directory fsync.
 *
 * Process Concurrency and Atomic Reservation Architecture:
 * SmallTrader operates strictly within a single Android OS process (standard android:process architecture).
 * Thread-safety and atomic compare-and-set reservation boundaries are coordinated via canonical path
 * ReentrantLocks ([SessionProcessLocks.getFileLock]). Across atomic disk writes, updates are written to a temp
 * file, flushed and synced (`fd.sync()`), atomically replaced via rename, and parent directory fsynced via POSIX.
 * Any corrupted or duplicate entries detected on disk fail closed immediately with [OnlineSaleRecoveryException]
 * rather than silently hiding or discarding records.
 */
class DefaultDurableOnlineSaleRecoveryStore internal constructor(
    private val storageFile: File,
    private val cryptoEngine: SessionCryptoEngine,
    private val packageName: String,
    internal var directorySyncEngine: DirectorySyncEngine = AndroidPlatformDirectorySyncEngine
) : DurableOnlineSaleRecoveryStore {

    internal var renameOperation: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) }

    override fun savePendingSale(record: DurableOnlineSaleRecoveryRecord) {
        // Enforce strict record self-consistency against raw wire bytes, canonical profile, identity, and economics BEFORE lock/save
        record.validateConsistency()

        val canonicalOrigin = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(record.baseOrigin)
        val normalizedRecord = if (record.baseOrigin == canonicalOrigin) record else record.copy(baseOrigin = canonicalOrigin)

        val lock = SessionProcessLocks.getFileLock(storageFile)
        lock.withLock {
            val existingRecords = readRecordsInternal()

            // 1. Global event UUID uniqueness across all identity scopes
            val existingSameEvent = existingRecords.firstOrNull { it.clientEventId == normalizedRecord.clientEventId }
            if (existingSameEvent != null) {
                val isSameScope = existingSameEvent.companyId == normalizedRecord.companyId &&
                        existingSameEvent.actorId == normalizedRecord.actorId &&
                        existingSameEvent.deviceId == normalizedRecord.deviceId &&
                        existingSameEvent.baseOrigin == normalizedRecord.baseOrigin

                if (!isSameScope) {
                    throw OnlineSaleRecoveryException(
                        "Global event UUID collision across identity scopes for event ${normalizedRecord.clientEventId}: " +
                                "existing scope (${existingSameEvent.companyId}, ${existingSameEvent.actorId}) vs " +
                                "new scope (${normalizedRecord.companyId}, ${normalizedRecord.actorId})"
                    )
                }

                // Same scope: enforce byte and digest immutability
                if (existingSameEvent.expectedNormativeProfileDigest != normalizedRecord.expectedNormativeProfileDigest ||
                    !existingSameEvent.rawWireBytes.contentEquals(normalizedRecord.rawWireBytes)
                ) {
                    throw OnlineSaleRecoveryException(
                        "Cannot overwrite pending event ${normalizedRecord.clientEventId} with different payload bytes or digest"
                    )
                }
                // Idempotent re-save of exact same event
                return@withLock
            }

            // 2. Identity scope reservation: block new event while prior unverified event is pending
            val existingForIdentity = existingRecords.firstOrNull {
                it.companyId == normalizedRecord.companyId &&
                        it.actorId == normalizedRecord.actorId &&
                        it.deviceId == normalizedRecord.deviceId &&
                        it.baseOrigin == normalizedRecord.baseOrigin
            }
            if (existingForIdentity != null) {
                throw OnlineSaleRecoveryException(
                    "Cannot save new event ${normalizedRecord.clientEventId}: pending unverified event " +
                            "${existingForIdentity.clientEventId} already exists for this scope"
                )
            }

            val updated = existingRecords + normalizedRecord
            writeRecordsInternal(updated)
        }
    }

    override fun getPendingSale(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        baseOrigin: String
    ): DurableOnlineSaleRecoveryRecord? {
        val canonicalOrigin = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(baseOrigin)
        val lock = SessionProcessLocks.getFileLock(storageFile)
        return lock.withLock {
            if (!storageFile.exists()) return@withLock null
            val records = readRecordsInternal()
            records.firstOrNull { r ->
                r.companyId == companyId &&
                        r.actorId == actorId &&
                        r.deviceId == deviceId &&
                        r.baseOrigin == canonicalOrigin
            }
        }
    }

    override fun clearPendingSale(
        clientEventId: ClientUuid,
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        baseOrigin: String
    ) {
        val canonicalOrigin = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(baseOrigin)
        val lock = SessionProcessLocks.getFileLock(storageFile)
        lock.withLock {
            if (!storageFile.exists()) return@withLock
            val records = readRecordsInternal()
            val filtered = records.filterNot {
                it.clientEventId == clientEventId &&
                        it.companyId == companyId &&
                        it.actorId == actorId &&
                        it.deviceId == deviceId &&
                        it.baseOrigin == canonicalOrigin
            }
            if (filtered.size == records.size) {
                // No matching record found for this exact identity scope; idempotent no-op (never cross-scope erase!)
                return@withLock
            }
            if (filtered.isEmpty()) {
                if (!storageFile.delete()) {
                    throw OnlineSaleRecoveryException("Failed to delete empty recovery file")
                }
                syncDirectory(storageFile.parentFile)
            } else {
                writeRecordsInternal(filtered)
            }
        }
    }

    @Deprecated("Test-only; production callers must use scoped clearPendingSale")
    override fun clearPendingSale(clientEventId: ClientUuid) {
        val lock = SessionProcessLocks.getFileLock(storageFile)
        lock.withLock {
            if (!storageFile.exists()) return@withLock
            val records = readRecordsInternal()
            val matching = records.filter { it.clientEventId == clientEventId }
            if (matching.isEmpty()) return@withLock
            if (matching.size > 1) {
                throw OnlineSaleRecoveryException(
                    "Ambiguous unscoped clear: multiple records found with event ID $clientEventId. Scoped clear required."
                )
            }
            val target = matching.first()
            val filtered = records.filterNot {
                it.clientEventId == target.clientEventId &&
                        it.companyId == target.companyId &&
                        it.actorId == target.actorId &&
                        it.deviceId == target.deviceId &&
                        it.baseOrigin == target.baseOrigin
            }
            if (filtered.isEmpty()) {
                if (!storageFile.delete()) {
                    throw OnlineSaleRecoveryException("Failed to delete empty recovery file")
                }
                syncDirectory(storageFile.parentFile)
            } else {
                writeRecordsInternal(filtered)
            }
        }
    }

    override fun listAllRecords(): List<DurableOnlineSaleRecoveryRecord> {
        val lock = SessionProcessLocks.getFileLock(storageFile)
        return lock.withLock {
            if (!storageFile.exists()) return@withLock emptyList()
            readRecordsInternal()
        }
    }

    private fun writeRecordsInternal(records: List<DurableOnlineSaleRecoveryRecord>) {
        val existingFileExists = storageFile.exists()
        try {
            cryptoEngine.ensureKeyUsable(forExistingRecord = existingFileExists)
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException("Cryptographic key is unusable for recovery store: ${e.message}", e)
        }

        val aad = buildAad(packageName)
        val plaintext = DurableOnlineSaleRecoveryWireFormat.serialize(records)
        if (plaintext.size > MAX_FILE_BYTES) {
            throw OnlineSaleRecoveryException(
                "Recovery envelope exceeds maximum storage bound ($MAX_FILE_BYTES bytes): actual=${plaintext.size}"
            )
        }

        val (iv, ciphertext) = try {
            cryptoEngine.encrypt(plaintext, aad)
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException("Failed to encrypt recovery records: ${e.message}", e)
        }

        val envelopeBytes = buildEnvelope(iv, ciphertext)
        if (envelopeBytes.size > MAX_FILE_BYTES) {
            throw OnlineSaleRecoveryException(
                "Recovery envelope exceeds maximum storage bound ($MAX_FILE_BYTES bytes): actual=${envelopeBytes.size}"
            )
        }

        val parent = storageFile.parentFile ?: File(".")
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(parent, "${storageFile.name}.${UUID.randomUUID()}.tmp")
        var writeSuccess = false
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(envelopeBytes)
                fos.flush()
                fos.fd.sync()
            }
            if (tempFile.length() > MAX_FILE_BYTES) {
                throw OnlineSaleRecoveryException("Temp file length exceeds maximum allowable $MAX_FILE_BYTES bytes")
            }
            if (!renameOperation(tempFile, storageFile)) {
                throw OnlineSaleRecoveryException("Failed to atomically replace recovery file")
            }
            syncDirectory(parent)
            writeSuccess = true
        } catch (e: Exception) {
            if (e is OnlineSaleRecoveryException) throw e
            throw OnlineSaleRecoveryException("Failed to atomically persist recovery file: ${e.message}", e)
        } finally {
            if (!writeSuccess && tempFile.exists()) {
                tempFile.delete()
                try {
                    syncDirectory(parent)
                } catch (_: Exception) {
                    // Suppress secondary cleanup sync failure
                }
            }
        }
    }

    private fun readRecordsInternal(): List<DurableOnlineSaleRecoveryRecord> {
        if (!storageFile.exists()) return emptyList()

        val envelopeBytes = try {
            BoundedStreamReader.readBounded(storageFile, MAX_FILE_BYTES.toInt())
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException("Failed to read bounded recovery file from disk", e)
        }

        if (envelopeBytes.size < MIN_ENVELOPE_BYTES) {
            throw OnlineSaleRecoveryException("Recovery file corrupted: truncated envelope")
        }

        val (iv, ciphertext) = parseEnvelope(envelopeBytes)
            ?: throw OnlineSaleRecoveryException("Recovery file corrupted: invalid envelope header or magic")

        val aad = buildAad(packageName)
        val plaintext = try {
            cryptoEngine.decrypt(iv, ciphertext, aad)
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException("Failed to decrypt recovery file: ${e.message}", e)
        }

        val records = try {
            DurableOnlineSaleRecoveryWireFormat.deserialize(plaintext)
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException("Failed to deserialize recovery records: ${e.message}", e)
        }

        // Validate each record's self-consistency and detect corrupted duplicate entries
        val seenEventIds = mutableSetOf<ClientUuid>()
        val seenScopes = mutableSetOf<String>()
        for (record in records) {
            record.validateConsistency()
            if (!seenEventIds.add(record.clientEventId)) {
                throw OnlineSaleRecoveryException("Corrupted recovery file: duplicate record with eventId ${record.clientEventId} detected")
            }
            val scopeKey = "${record.companyId}:${record.actorId}:${record.deviceId}:${record.baseOrigin}"
            if (!seenScopes.add(scopeKey)) {
                throw OnlineSaleRecoveryException("Corrupted recovery file: multiple unverified records detected for scope $scopeKey")
            }
        }
        return records
    }

    private fun syncDirectory(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        try {
            directorySyncEngine.syncDirectory(dir)
        } catch (e: Exception) {
            throw OnlineSaleRecoveryException("Directory durability fsync failed for ${dir.absolutePath}", e)
        }
    }

    private fun buildAad(pkgName: String): ByteArray {
        val aadString = "purpose=$RECORD_PURPOSE;pkg=$pkgName;v=$FORMAT_VERSION"
        return aadString.toByteArray(Charsets.UTF_8)
    }

    private fun buildEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(MAGIC)
        dos.writeByte(iv.size)
        dos.write(iv)
        dos.writeInt(ciphertext.size)
        dos.write(ciphertext)
        dos.flush()
        return baos.toByteArray()
    }

    private fun parseEnvelope(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        if (bytes.size < MIN_ENVELOPE_BYTES) return null

        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return null
        }

        val ivLen = bytes[4].toInt() and 0xFF
        if (ivLen != EXPECTED_IV_LENGTH) return null

        val ivStart = 5
        val ivEnd = ivStart + ivLen
        if (bytes.size < ivEnd + 4) return null

        val iv = bytes.copyOfRange(ivStart, ivEnd)

        val payloadLen = ((bytes[ivEnd].toInt() and 0xFF) shl 24) or
                ((bytes[ivEnd + 1].toInt() and 0xFF) shl 16) or
                ((bytes[ivEnd + 2].toInt() and 0xFF) shl 8) or
                (bytes[ivEnd + 3].toInt() and 0xFF)

        if (payloadLen <= 0 || payloadLen > MAX_FILE_BYTES) return null

        val ciphertextStart = ivEnd + 4
        if (bytes.size != ciphertextStart + payloadLen) return null

        val ciphertext = bytes.copyOfRange(ciphertextStart, bytes.size)
        return Pair(iv, ciphertext)
    }

    companion object {
        private const val RECORD_PURPOSE = "online_sale_recovery"
        private const val FORMAT_VERSION = 1
        private const val MAX_FILE_BYTES = 1024 * 1024L // 1 MiB hard bound
        private const val EXPECTED_IV_LENGTH = 12
        // Magic: 'O', 'S', 'R', 0x01
        private val MAGIC = byteArrayOf(0x4F, 0x53, 0x52, 0x01)
        private const val MIN_ENVELOPE_BYTES = 4 + 1 + EXPECTED_IV_LENGTH + 4 + 16 // 37 bytes
    }
}

/**
 * Strict canonical serialization for durable online recovery records.
 */
internal object DurableOnlineSaleRecoveryWireFormat {
    private val RECORD_ALLOWED_KEYS = setOf(
        "client_event_id",
        "expected_normative_profile_digest",
        "company_id",
        "actor_id",
        "device_id",
        "base_origin",
        "currency",
        "currency_minor_units",
        "amount",
        "raw_wire_payload",
        "created_at"
    )

    private val ROOT_ALLOWED_KEYS = setOf(
        "format_version",
        "records"
    )

    fun serialize(records: List<DurableOnlineSaleRecoveryRecord>): ByteArray {
        val recordsWire = records.map { r ->
            linkedMapOf<String, Any?>(
                "client_event_id" to r.clientEventId.value,
                "expected_normative_profile_digest" to r.expectedNormativeProfileDigest.value,
                "company_id" to r.companyId.value,
                "actor_id" to r.actorId.value,
                "device_id" to r.deviceId.value,
                "base_origin" to r.baseOrigin,
                "currency" to r.currency,
                "currency_minor_units" to r.currencyMinorUnits.toLong(),
                "amount" to r.amount.toPlainString(),
                "raw_wire_payload" to String(r.rawWireBytes, Charsets.UTF_8),
                "created_at" to r.createdAt
            )
        }
        val rootMap = linkedMapOf<String, Any?>(
            "format_version" to 1L,
            "records" to recordsWire
        )
        val jsonStr = CanonicalJsonSerializer.serialize(rootMap)
        return jsonStr.toByteArray(Charsets.UTF_8)
    }

    fun deserialize(bytes: ByteArray): List<DurableOnlineSaleRecoveryRecord> {
        val jsonStr = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            throw CanonicalSerializationException("Plaintext is not valid UTF-8")
        }

        val rootElement = StrictJsonParser.parse(jsonStr)
        val rootObj = rootElement as? StrictJsonObject
            ?: throw CanonicalSerializationException("Root must be a JSON object")

        for (k in rootObj.properties.keys) {
            if (k !in ROOT_ALLOWED_KEYS) {
                throw CanonicalSerializationException("Unexpected key in recovery root: $k")
            }
        }

        val recordsArray = rootObj.properties["records"] as? StrictJsonArray
            ?: throw CanonicalSerializationException("Missing or invalid 'records' array")

        val result = mutableListOf<DurableOnlineSaleRecoveryRecord>()
        for (item in recordsArray.elements) {
            val obj = item as? StrictJsonObject
                ?: throw CanonicalSerializationException("Record item must be an object")

            for (k in obj.properties.keys) {
                if (k !in RECORD_ALLOWED_KEYS) {
                    throw CanonicalSerializationException("Unexpected key in recovery record: $k")
                }
            }

            val clientEventId = ClientUuid.parse(requireString(obj, "client_event_id"))
            val expectedDigest = PayloadDigest.parse(requireString(obj, "expected_normative_profile_digest"))
            val companyId = ServerUlid.parse(requireString(obj, "company_id"))
            val actorId = ServerUlid.parse(requireString(obj, "actor_id"))
            val deviceId = ClientUuid.parse(requireString(obj, "device_id"))
            val baseOrigin = requireString(obj, "base_origin")
            val currency = requireString(obj, "currency")
            val cmuNum = (obj.require("currency_minor_units") as? StrictJsonNumber)?.rawString?.toIntOrNull()
                ?: throw CanonicalSerializationException("currency_minor_units must be a valid integer")
            val amount = MoneyAmount(requireString(obj, "amount"))
            val rawWirePayload = requireString(obj, "raw_wire_payload")
            val createdAt = requireString(obj, "created_at")

            result.add(
                DurableOnlineSaleRecoveryRecord(
                    clientEventId = clientEventId,
                    expectedNormativeProfileDigest = expectedDigest,
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    baseOrigin = baseOrigin,
                    currency = currency,
                    currencyMinorUnits = cmuNum,
                    amount = amount,
                    rawWireBytes = rawWirePayload.toByteArray(Charsets.UTF_8),
                    createdAt = createdAt
                )
            )
        }
        return result
    }

    private fun requireString(obj: StrictJsonObject, key: String): String {
        val prop = obj[key] ?: throw CanonicalSerializationException("Missing required property '$key'")
        return (prop as? StrictJsonString)?.value
            ?: throw CanonicalSerializationException("Property '$key' must be a string")
    }
}
