package com.example.data.mobile.session

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.concurrent.withLock

/**
 * Manages an installation-unique, independently persisted [ClientUuid] stored under [noBackupFilesDir].
 *
 * This identity is generated once per app installation and is strictly preserved across staff logouts,
 * application restarts, and credential purges.
 *
 * Concurrency is coordinated across instances within the same JVM process by canonical file path.
 * Multi-process file access across isolated Android processes is NOT claimed.
 */
class InstallationIdentityStore(
    private val identityFile: File
) {

    /**
     * Retrieves the stable installation UUID, generating and persisting a new [ClientUuid]
     * only when no identity exists on disk. Existing unreadable records are preserved and rejected.
     */
    fun getOrCreateInstallationId(): ClientUuid {
        val lock = SessionProcessLocks.getFileLock(identityFile)
        return lock.withLock {
            if (identityFile.exists()) {
                val loaded = loadFromFile()
                SessionProcessLocks.cacheInstallationId(identityFile, loaded)
                return@withLock loaded
            }
            // A disappeared initialized identity is a storage failure, not a new installation.
            if (SessionProcessLocks.getCachedInstallationId(identityFile) != null) {
                throw SessionStorageException(SessionStorageError.IoError("Installation identity file is missing"))
            }
            val newId = ClientUuid.random()
            persistToFile(newId)
            SessionProcessLocks.cacheInstallationId(identityFile, newId)
            newId
        }
    }

    private fun loadFromFile(): ClientUuid {
        val bytes = try {
            BoundedStreamReader.readBounded(identityFile, MAX_FILE_BYTES.toInt())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionStorageException) {
            throw e
        } catch (e: Exception) {
            throw SessionStorageException(SessionStorageError.IoError("Failed to read installation identity"))
        }
        return try {
            val jsonStr = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val obj = StrictJsonParser.parse(jsonStr) as? StrictJsonObject
                ?: throw IllegalArgumentException()
            val deviceIdProp = obj["device_id"] as? StrictJsonString
                ?: throw IllegalArgumentException()
            ClientUuid.parse(deviceIdProp.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionStorageException(SessionStorageError.Corrupt("Invalid installation identity record"))
        }
    }

    private fun persistToFile(uuid: ClientUuid) {
        val parent = identityFile.parentFile ?: File(".")
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(parent, "${identityFile.name}.${UUID.randomUUID()}.tmp")
        val timestamp = formatIsoUtc(Date())
        val map = linkedMapOf<String, Any?>(
            "device_id" to uuid.value,
            "created_at" to timestamp
        )
        val jsonBytes = CanonicalJsonSerializer.serialize(map).toByteArray(Charsets.UTF_8)

        var success = false
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonBytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tempFile.renameTo(identityFile)) {
                throw SessionStorageException(
                    SessionStorageError.IoError("Failed to atomically replace installation identity file")
                )
            }
            success = true
        } finally {
            if (!success && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun formatIsoUtc(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    companion object {
        const val IDENTITY_FILE_NAME = "small_trader_device_installation.json"
        private const val MAX_FILE_BYTES = 1024 * 1024L // 1 MiB

        fun create(noBackupFilesDir: File): InstallationIdentityStore {
            return InstallationIdentityStore(File(noBackupFilesDir, IDENTITY_FILE_NAME))
        }
    }
}
