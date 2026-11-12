package com.example.data.mobile.session

import android.content.Context
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.concurrent.withLock

/**
 * Public interface for secure staff session persistence on Android API 24+.
 */
interface SecureStaffSessionStore {
    /**
     * Stable installation UUID uniquely identifying this client device instance.
     * Persisted independently across staff logouts.
     */
    val installationId: ClientUuid

    /**
     * Atomically encrypts and replaces the active staff session on disk under [Context.getNoBackupFilesDir].
     *
     * In the event of a write failure or crash, any previously valid session record is strictly preserved.
     */
    fun saveSession(record: StaffSessionRecord)

    /**
     * Reads, authenticates, and scope-verifies the active staff session.
     *
     * Fails closed with typed results when expired, locked, corrupted, or when scope mismatch occurs.
     * Never silently deletes or replaces stored records on read failure.
     */
    fun readSession(scope: SessionReadScope, nowEpochMs: Long = System.currentTimeMillis()): SessionReadResult

    /**
     * Clears ONLY the encrypted staff session credential record from disk.
     *
     * Preserves the stable [installationId] and any offline/outbox queues untouched.
     * Does NOT claim server-side token revocation or auth queue unlock.
     */
    fun clearSession()

    /**
     * Returns true if an encrypted session file currently exists on disk.
     */
    fun hasSessionFile(): Boolean

    companion object {
        const val SESSION_FILE_NAME = "small_trader_secure_staff_session.bin"

        /**
         * Factory method constructing a production [SecureStaffSessionStore] backed by AndroidKeyStore
         * and the application's private [Context.getNoBackupFilesDir].
         */
        fun create(context: Context): SecureStaffSessionStore {
            val noBackupDir = context.noBackupFilesDir
            val sessionFile = File(noBackupDir, SESSION_FILE_NAME)
            val installationStore = InstallationIdentityStore.create(noBackupDir)
            val cryptoEngine = AndroidKeyStoreCryptoEngine()
            return DefaultSecureStaffSessionStore(
                sessionFile = sessionFile,
                installationStore = installationStore,
                cryptoEngine = cryptoEngine,
                packageName = context.packageName
            )
        }
    }
}

/**
 * Standard implementation of [SecureStaffSessionStore].
 *
 * Concurrency is coordinated across instances within the same JVM process by canonical file path.
 * Multi-process file access across isolated Android processes is NOT claimed without OS-level FileLock.
 *
 * Crash durability: Writes synchronize file descriptor data to storage before atomic rename.
 * POSIX directory metadata durability across power-loss crash requires parent directory fsync which is
 * not portably exposed in Java/Android userland without native directory file descriptor opening.
 * On atomic replacement failure, operations fail closed and preserve prior records intact without
 * destructive restore or key recreation.
 */
class DefaultSecureStaffSessionStore internal constructor(
    private val sessionFile: File,
    private val installationStore: InstallationIdentityStore,
    private val cryptoEngine: SessionCryptoEngine,
    private val packageName: String
) : SecureStaffSessionStore {

    internal var renameOperation: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) }

    override val installationId: ClientUuid
        get() = installationStore.getOrCreateInstallationId()

    override fun hasSessionFile(): Boolean =
        SessionProcessLocks.getFileLock(sessionFile).withLock { sessionFile.exists() }

    override fun saveSession(record: StaffSessionRecord) {
        val lock = SessionProcessLocks.getFileLock(sessionFile)
        lock.withLock {
            val existingFileExists = sessionFile.exists()
            cryptoEngine.ensureKeyUsable(forExistingRecord = existingFileExists)

            val aad = buildAad(packageName)
            val plaintext = SessionWireFormat.serialize(record)
            if (plaintext.size > MAX_FILE_BYTES) {
                throw SessionStorageException(
                    SessionStorageError.Oversized(plaintext.size.toLong(), MAX_FILE_BYTES)
                )
            }

            val (iv, ciphertext) = cryptoEngine.encrypt(plaintext, aad)
            val envelopeBytes = buildEnvelope(iv, ciphertext)
            if (envelopeBytes.size > MAX_FILE_BYTES) {
                throw SessionStorageException(
                    SessionStorageError.Oversized(envelopeBytes.size.toLong(), MAX_FILE_BYTES)
                )
            }

            val parent = sessionFile.parentFile ?: File(".")
            if (!parent.exists()) {
                parent.mkdirs()
            }

            val tempFile = File(parent, "${sessionFile.name}.${UUID.randomUUID()}.tmp")
            var writeSuccess = false
            try {
                FileOutputStream(tempFile).use { fos ->
                    fos.write(envelopeBytes)
                    fos.flush()
                    fos.fd.sync()
                }
                if (!renameOperation(tempFile, sessionFile)) {
                    throw SessionStorageException(
                        SessionStorageError.IoError("Failed to atomically replace session file")
                    )
                }
                writeSuccess = true
            } catch (e: SessionStorageException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SessionStorageException(
                    SessionStorageError.IoError("Failed to atomically persist session record")
                )
            } finally {
                if (!writeSuccess && tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    override fun readSession(scope: SessionReadScope, nowEpochMs: Long): SessionReadResult {
        val lock = SessionProcessLocks.getFileLock(sessionFile)
        return lock.withLock {
            if (!sessionFile.exists()) {
                return@withLock SessionReadResult.NoSession
            }

            val envelopeBytes = try {
                BoundedStreamReader.readBounded(sessionFile, MAX_FILE_BYTES.toInt())
            } catch (e: SessionStorageException) {
                return@withLock when (val err = e.error) {
                    is SessionStorageError.Oversized -> SessionReadResult.Oversized(err)
                    is SessionStorageError.IoError -> SessionReadResult.IoError(err)
                    else -> SessionReadResult.Corrupt(SessionStorageError.Corrupt("Failed to read session file"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock SessionReadResult.IoError(
                    SessionStorageError.IoError("Failed to read session file from disk")
                )
            }

            if (envelopeBytes.size < MIN_ENVELOPE_BYTES) {
                return@withLock SessionReadResult.Corrupt(
                    SessionStorageError.Corrupt("Session file is truncated or envelope header invalid")
                )
            }

            // Verify envelope header
            val (iv, ciphertext) = parseEnvelope(envelopeBytes)
                ?: return@withLock SessionReadResult.Corrupt(
                    SessionStorageError.Corrupt("Invalid session file magic or corrupted header envelope")
                )

            val aad = buildAad(packageName)
            val plaintext = try {
                cryptoEngine.decrypt(iv, ciphertext, aad)
            } catch (e: SessionStorageException) {
                return@withLock when (val err = e.error) {
                    is SessionStorageError.Locked -> SessionReadResult.Locked(err)
                    is SessionStorageError.Unavailable -> SessionReadResult.Unavailable(err)
                    is SessionStorageError.Corrupt -> SessionReadResult.Corrupt(err)
                    is SessionStorageError.Oversized -> SessionReadResult.Oversized(err)
                    is SessionStorageError.Expired -> SessionReadResult.Expired(err)
                    is SessionStorageError.ScopeMismatch -> SessionReadResult.ScopeMismatch(err)
                    is SessionStorageError.IoError -> SessionReadResult.IoError(err)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock SessionReadResult.Corrupt(
                    SessionStorageError.Corrupt("Unexpected decryption failure")
                )
            }

            val record = try {
                SessionWireFormat.deserialize(plaintext)
            } catch (e: CanonicalSerializationException) {
                return@withLock SessionReadResult.Corrupt(
                    SessionStorageError.Corrupt("Failed to parse decrypted session payload")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock SessionReadResult.Corrupt(
                    SessionStorageError.Corrupt("Malformed session payload")
                )
            }

            // Expiry verification
            val expiresAtEpochMs = try {
                SessionValidation.parseRfc3339ToEpochMs(record.expiresAt, "expires_at")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock SessionReadResult.Corrupt(
                    SessionStorageError.Corrupt("Invalid expires_at timestamp in session payload")
                )
            }

            if (nowEpochMs >= expiresAtEpochMs) {
                val nowIso = formatIsoUtc(Date(nowEpochMs))
                return@withLock SessionReadResult.Expired(
                    SessionStorageError.Expired(record.expiresAt, nowIso)
                )
            }

            // Scope verification
            if (scope.expectedDeviceUuid != record.deviceUuid) {
                return@withLock SessionReadResult.ScopeMismatch(
                    SessionStorageError.ScopeMismatch(
                        "Scope mismatch: device UUID does not match session"
                    )
                )
            }
            if (scope.expectedActorUlid != null && scope.expectedActorUlid != record.actorUlid) {
                return@withLock SessionReadResult.ScopeMismatch(
                    SessionStorageError.ScopeMismatch(
                        "Scope mismatch: actor ULID does not match session"
                    )
                )
            }
            if (scope.expectedCompanyId != null && scope.expectedCompanyId != record.selectedCompanyId) {
                return@withLock SessionReadResult.ScopeMismatch(
                    SessionStorageError.ScopeMismatch(
                        "Scope mismatch: company ID does not match session"
                    )
                )
            }

            SessionReadResult.Success(record)
        }
    }

    override fun clearSession() {
        val lock = SessionProcessLocks.getFileLock(sessionFile)
        lock.withLock {
            if (sessionFile.exists() && !sessionFile.delete()) {
                throw SessionStorageException(SessionStorageError.IoError("Failed to clear session record"))
            }
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

        // Check magic
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

    private fun formatIsoUtc(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    companion object {
        private const val RECORD_PURPOSE = "staff_session"
        private const val FORMAT_VERSION = 1
        private const val MAX_FILE_BYTES = 1024 * 1024L // 1 MiB hard bound
        private const val EXPECTED_IV_LENGTH = 12
        // Magic: 'S', 'S', 'S', 0x01
        private val MAGIC = byteArrayOf(0x53, 0x53, 0x53, 0x01)
        private const val MIN_ENVELOPE_BYTES = 4 + 1 + EXPECTED_IV_LENGTH + 4 + 16 // 37 bytes
    }
}
