package com.example.data.mobile.session

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Bounded streaming reader preventing TOCTOU races and unbounded memory allocation.
 *
 * Reads in bounded chunks (up to 8 KiB) with a 1-byte probe past [maxAllowedBytes].
 * If a stream or file grows beyond [maxAllowedBytes] during reading, it fails closed immediately
 * with [SessionStorageError.Oversized] without reading unbounded bytes or allocating large buffers.
 */
internal object BoundedStreamReader {
    const val MAX_ALLOWED_BYTES = 1024 * 1024 // 1 MiB

    fun readBounded(file: File, maxAllowedBytes: Int = MAX_ALLOWED_BYTES): ByteArray {
        val fileLength = file.length()
        if (fileLength > maxAllowedBytes) {
            throw SessionStorageException(
                SessionStorageError.Oversized(fileLength, maxAllowedBytes.toLong())
            )
        }

        // Bounded initial capacity to avoid unbounded pre-allocation
        val initialCapacity = if (fileLength in 1..maxAllowedBytes) fileLength.toInt() else 1024
        val baos = ByteArrayOutputStream(initialCapacity)

        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var totalRead = 0
            while (true) {
                // Read at most enough to probe one byte past maxAllowedBytes
                val remainingAllowed = (maxAllowedBytes - totalRead) + 1
                val toRead = minOf(buffer.size, remainingAllowed)
                val readCount = fis.read(buffer, 0, toRead)
                if (readCount == -1) break

                if (totalRead + readCount > maxAllowedBytes) {
                    throw SessionStorageException(
                        SessionStorageError.Oversized((totalRead + readCount).toLong(), maxAllowedBytes.toLong())
                    )
                }
                baos.write(buffer, 0, readCount)
                totalRead += readCount
            }
        }
        return baos.toByteArray()
    }
}
