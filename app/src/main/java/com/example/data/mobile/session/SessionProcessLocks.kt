package com.example.data.mobile.session

import com.example.data.mobile.contract.ClientUuid
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Process-wide lock and cache coordination for session files, installation identities, and keys.
 *
 * Coordinates concurrent instances within the same JVM process by canonical file path
 * and key alias. Note: Concurrency safety across independent Android OS processes
 * sharing storage is NOT claimed without OS-level file locking.
 */
internal object SessionProcessLocks {
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val identityCache = ConcurrentHashMap<String, ClientUuid>()

    fun getFileLock(file: File): ReentrantLock {
        val path = getCanonicalPath(file)
        return fileLocks.computeIfAbsent(path) { ReentrantLock() }
    }

    fun getKeyLock(keyAlias: String): Any {
        return keyLocks.computeIfAbsent(keyAlias) { Any() }
    }

    fun getCachedInstallationId(identityFile: File): ClientUuid? {
        val path = getCanonicalPath(identityFile)
        return identityCache[path]
    }

    fun cacheInstallationId(identityFile: File, uuid: ClientUuid) {
        val path = getCanonicalPath(identityFile)
        identityCache[path] = uuid
    }

    internal fun clearForTesting() {
        identityCache.clear()
        fileLocks.clear()
        keyLocks.clear()
    }

    private fun getCanonicalPath(file: File): String {
        return try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }
    }
}
