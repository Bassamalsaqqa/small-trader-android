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
     * if no valid identity currently exists on disk.
     */
    fun getOrCreateInstallationId(): ClientUuid {
        SessionProcessLocks.getCachedInstallationId(identityFile)?.let { return it }

        val lock = SessionProcessLocks.getFileLock(identityFile)
        return lock.withLock {
            SessionProcessLocks.getCachedInstallationId(identityFile)?.let { return it }

            if (identityFile.exists()) {
                val loaded = loadFromFile()
                if (loaded != null) {
                    SessionProcessLocks.cacheInstallationId(identityFile, loaded)
                    return loaded
                }
            }

            val newId = ClientUuid.random()
            persistToFile(newId)
            SessionProcessLocks.cacheInstallationId(identityFile, newId)
            newId
        }
    }

    private fun loadFromFile(): ClientUuid? {
        if (!identityFile.exists()) return null

        return try {
            val bytes = BoundedStreamReader.readBounded(identityFile, MAX_FILE_BYTES.toInt())
            val jsonStr = String(bytes, Charsets.UTF_8)
            val obj = StrictJsonParser.parse(jsonStr) as? StrictJsonObject ?: return null
            val deviceIdProp = obj["device_id"] as? StrictJsonString ?: return null
            ClientUuid.parse(deviceIdProp.value)
        } catch (e: Exception) {
            null
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
