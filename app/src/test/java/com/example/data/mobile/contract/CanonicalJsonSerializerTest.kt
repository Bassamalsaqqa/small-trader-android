package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanonicalJsonSerializerTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenCanonicalVectorsParity() {
        val fixturesDir = locateFixturesDir()
        val vectorsFile = File(fixturesDir, "golden-canonical-vectors.json")
        assertTrue("Vectors file must exist", vectorsFile.exists())

        val content = vectorsFile.readText(Charsets.UTF_8)
        val jsonArray = StrictJsonParser.parse(content).asArray()
        assertTrue("Vectors array must not be empty", jsonArray.size > 0)

        for (i in 0 until jsonArray.size) {
            val vec = jsonArray[i].asObject()
            val name = vec.require("name").asString()
            val inputElem = vec.require("input")
            val expectedCanonical = vec.require("canonical_json").asString()
            val expectedDigest = vec.require("digest").asString()

            // 1. Verify canonical serialization string
            val actualCanonical = CanonicalJsonSerializer.serialize(inputElem)
            assertEquals("Vector '$name' canonical JSON mismatch", expectedCanonical, actualCanonical)

            // 2. Verify UTF-8 byte serialization exact match
            val actualBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(inputElem)
            val expectedBytes = expectedCanonical.toByteArray(Charsets.UTF_8)
            assertEquals("Vector '$name' byte length mismatch", expectedBytes.size, actualBytes.size)
            for (b in expectedBytes.indices) {
                assertEquals("Vector '$name' byte at $b mismatch", expectedBytes[b], actualBytes[b])
            }

            // 3. Verify SHA-256 digest
            val actualDigest = CanonicalJsonSerializer.computeDigest(inputElem)
            assertEquals("Vector '$name' digest mismatch", expectedDigest, actualDigest.value)
        }
    }

    @Test
    fun testUtf16SurrogateKeyOrderingSupplementaryVsHighBmp() {
        // High BMP U+FFFF vs Supplementary plane emoji U+1F600 (\uD83D\uDE00)
        // In UTF-16 code units, 0xD83D (55357) < 0xFFFF (65535)
        val map = mapOf(
            "\uffff" to "bmp_last",
            "\uD83D\uDE00" to "supplementary_plane"
        )
        val canonical = CanonicalJsonSerializer.serialize(map)
        assertEquals("{\"😀\":\"supplementary_plane\",\"\uffff\":\"bmp_last\"}", canonical)
    }

    @Test
    fun testControlCharacterEscaping() {
        val map = mapOf(
            "tab" to "a\tb",
            "newline" to "line1\nline2",
            "null_char" to "prefix\u0000suffix"
        )
        val canonical = CanonicalJsonSerializer.serialize(map)
        assertEquals("{\"newline\":\"line1\\nline2\",\"null_char\":\"prefix\\u0000suffix\",\"tab\":\"a\\tb\"}", canonical)

        val digest = CanonicalJsonSerializer.computeDigest(map)
        assertEquals("sha256:0468796dc39483b190fca87ef8328eec7fea550efcdd53ac9aaaf117fd041244", digest.value)
    }

    @Test
    fun testArabicUtf8TextPreserved() {
        val map = mapOf(
            "arabic" to "عميل نقدي / Walk-in",
            "english" to "Cash Customer"
        )
        val canonical = CanonicalJsonSerializer.serialize(map)
        assertEquals("{\"arabic\":\"عميل نقدي / Walk-in\",\"english\":\"Cash Customer\"}", canonical)
    }

    @Test
    fun testRejectUnpairedSurrogates() {
        // Lone high surrogate
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("bad" to "prefix\uD800suffix"))
        }

        // Lone low surrogate
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("bad" to "prefix\uDC00suffix"))
        }

        // Reversed surrogate pair (low surrogate before high surrogate)
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("bad" to "prefix\uDC00\uD800suffix"))
        }
    }

    @Test
    fun testRejectFloatsAndDoubles() {
        // Float
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("price" to 10.5f))
        }

        // Double
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("price" to 10.5))
        }

        // Double.NaN
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("price" to Double.NaN))
        }

        // Double.POSITIVE_INFINITY
        assertThrows(CanonicalSerializationException::class.java) {
            CanonicalJsonSerializer.serialize(mapOf("price" to Double.POSITIVE_INFINITY))
        }
    }

    @Test
    fun testStrictJsonParserRejectsDuplicateKeys() {
        val duplicateKeyJson = "{\"a\": 1, \"a\": 2}"
        val ex = assertThrows(CanonicalSerializationException::class.java) {
            StrictJsonParser.parse(duplicateKeyJson)
        }
        assertTrue("Should report duplicate key", ex.message!!.contains("Duplicate JSON key detected: 'a'"))
    }

    @Test
    fun testDistinguishesEmptyObjectAndArray() {
        val emptyObj = CanonicalJsonSerializer.serialize(emptyMap<String, Any?>())
        val emptyList = CanonicalJsonSerializer.serialize(emptyList<Any?>())

        assertEquals("{}", emptyObj)
        assertEquals("[]", emptyList)
        assertTrue(emptyObj != emptyList)
    }
}
