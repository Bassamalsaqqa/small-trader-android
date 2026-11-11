package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Regression test asserting that [ContractConstants.MANIFEST_SHA256] matches the actual
 * file hash of `docs/integration/mobile-v1/manifest.sha256`, preventing contract drift.
 */
class ContractManifestDriftTest {

    private fun locateManifestFile(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/manifest.sha256").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/manifest.sha256 from $base")
        return File(root, "docs/integration/mobile-v1/manifest.sha256")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testManifestFileHashMatchesContractConstant() {
        val manifestFile = locateManifestFile()
        assertTrue("manifest.sha256 must exist", manifestFile.isFile)

        val computedHash = sha256Hex(manifestFile.readBytes())
        assertEquals(
            "ContractConstants.MANIFEST_SHA256 must match the actual SHA-256 hash of manifest.sha256 to prevent drift",
            ContractConstants.MANIFEST_SHA256,
            computedHash
        )
        assertEquals(
            "ContractConstants.MANIFEST_SHA256 must be draft revision 2 hash ba753523...",
            "ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659",
            ContractConstants.MANIFEST_SHA256
        )
        assertEquals(
            "ContractConstants.CONTRACT_REVISION must be 2",
            2,
            ContractConstants.CONTRACT_REVISION
        )
    }

    @Test
    fun testManifestEntriesIntegrity() {
        val manifestFile = locateManifestFile()
        val contractDir = manifestFile.parentFile ?: error("No parent dir for manifest")
        val lines = manifestFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        assertTrue("Manifest must declare entries", lines.isNotEmpty())

        for (line in lines) {
            val parts = line.split(Regex("\\s+"), limit = 2)
            assertEquals("Manifest line must contain hash and relative path: $line", 2, parts.size)
            val expectedEntryHash = parts[0]
            val relativePath = parts[1]

            val targetFile = File(contractDir, relativePath)
            assertTrue("File referenced in manifest must exist: $relativePath", targetFile.isFile)
            val actualFileHash = sha256Hex(targetFile.readBytes())
            assertEquals("File content hash must match manifest entry: $relativePath", expectedEntryHash, actualFileHash)
        }
    }
}
