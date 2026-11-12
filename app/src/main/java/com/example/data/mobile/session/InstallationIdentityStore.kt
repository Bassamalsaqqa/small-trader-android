package com.example.data.mobile.session

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages an installation-unique, independently persisted [ClientUuid] stored under [noBackupFilesDir].
 *
 * This identity is generated once per app installation and is strictly preserved across staff logouts,
 * application restarts, and credential purges.
 */
class InstallationIdentityStore(
    private val identityFile: File
) {
    private val lock = ReentrantLock()

    @Volatile
    private var cachedId: ClientUuid? = null

    /**
     * Retrieves the stable installation UUID, generating and persisting a new [ClientUuid]
     * if no valid identity currently exists on disk.
     */
    fun getOrCreateInstallationId(): ClientUuid {
        cachedId?.let { return it }

        return lock.withLock {
            cachedId?.let { return it }

            if (identityFile.exists()) {
                val loaded = loadFromFile()
                if (loaded != null) {
                    cachedId = loaded
                    return loaded
                }
            }

            val newId = ClientUuid.random()
            persistToFile(newId)
            cachedId = newId
            newId
        }
    }

    private fun loadFromFile(): ClientUuid? {
        if (!identityFile.exists()) return null
        if (identityFile.length() > MAX_FILE_BYTES) return null

        return try {
            val bytes = FileInputStream(identityFile).use { it.readBytes() }
            val jsonStr = String(bytes, Charsets.UTF_8)
            val obj = StrictJsonParser.parse(jsonStr) as? StrictJsonObject ?: return null
            val deviceIdProp = obj["device_id"] as? StrictJsonString ?: return null
            ClientUuid.parse(deviceIdProp.value)
        } catch (e: Exception) {
            null
        }
    }

    private fun persistToFile(uuid: ClientUuid) {
        val parent = identityFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(identityFile.parentFile, "${identityFile.name}.tmp")
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
                tempFile.copyTo(identityFile, overwrite = true)
                tempFile.delete()
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
