package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifierPrimitivesTest {

    @Test
    fun testValidServerUlidsAccepted() {
        val validUlids = listOf(
            "01JCV8X9M2NQW3TYZ4R5S6789A",
            "01JCV8X9M8T1V2W3X4Y5Z67890",
            "01JCV8X9M4K1P2Q3R4S5T6V7W8",
            "01JCV8X9M9A2B3C4D5E6F7G8H9",
            "01JCV8X9MAJ3K4M5N6P7Q8R9S0",
            "01JCV8X9MBV4W5X6Y7Z8012345",
            "01JCV8X9ME7890123456789ABC",
            "01JCV8X9MFGHJKMNPQRSTVWXYZ",
            "7ZZZZZZZZZZZZZZZZZZZZZZZZZ", // Maximum valid 128-bit ULID
            "00000000000000000000000000"  // Minimum valid ULID
        )

        for (ulidStr in validUlids) {
            assertTrue("ULID '$ulidStr' should be valid", ServerUlid.isValid(ulidStr))
            val ulid = ServerUlid.parse(ulidStr)
            assertEquals(ulidStr, ulid.value)
            assertEquals(ulidStr, ulid.toString())
        }
    }

    @Test
    fun testInvalidServerUlidsRejected() {
        // Excluded Crockford characters: I, L, O, U
        val badChars = listOf(
            "01JCV8X9M2NQW3TYZ4R5S6789I", // contains 'I'
            "01JCV8X9M2NQW3TYZ4R5S6789L", // contains 'L'
            "01JCV8X9M2NQW3TYZ4R5S6789O", // contains 'O'
            "01JCV8X9M2NQW3TYZ4R5S6789U"  // contains 'U'
        )
        for (bad in badChars) {
            assertFalse("ULID with forbidden character '$bad' should be invalid", ServerUlid.isValid(bad))
            assertThrows(IllegalArgumentException::class.java) { ServerUlid.parse(bad) }
        }

        // Lowercase characters rejected
        val lowercase = "01jcv8x9m2nqw3tyz4r5s6789a"
        assertFalse("Lowercase ULID should be invalid", ServerUlid.isValid(lowercase))
        assertThrows(IllegalArgumentException::class.java) { ServerUlid.parse(lowercase) }

        // Leading character > 7 rejected (exceeds 128-bit space)
        val overflowFirstChar = "81JCV8X9M2NQW3TYZ4R5S6789A"
        assertFalse("ULID starting with 8 should be invalid", ServerUlid.isValid(overflowFirstChar))
        assertThrows(IllegalArgumentException::class.java) { ServerUlid.parse(overflowFirstChar) }

        // Wrong length rejected (25 chars and 27 chars)
        assertFalse(ServerUlid.isValid("01JCV8X9M2NQW3TYZ4R5S6789"))
        assertFalse(ServerUlid.isValid("01JCV8X9M2NQW3TYZ4R5S6789AA"))

        // UUID string rejected for ServerUlid
        val uuid = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        assertFalse("UUID must be rejected as ServerUlid", ServerUlid.isValid(uuid))
        assertThrows(IllegalArgumentException::class.java) { ServerUlid.parse(uuid) }

        // Empty string rejected
        assertFalse(ServerUlid.isValid(""))
        assertThrows(IllegalArgumentException::class.java) { ServerUlid.parse("") }
    }

    @Test
    fun testClientUuidValidation() {
        val validUuids = listOf(
            "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
            "7c1e5a2b-3d4f-5678-90ab-cdef12345678",
            "00000000-0000-0000-0000-000000000000",
            "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"
        )
        for (u in validUuids) {
            assertTrue("UUID '$u' should be valid", ClientUuid.isValid(u))
            val parsed = ClientUuid.parse(u)
            assertEquals(u, parsed.value)
        }

        // Random generation produces valid UUID
        val random = ClientUuid.random()
        assertTrue(ClientUuid.isValid(random.value))

        // ULID rejected for ClientUuid
        val ulid = "01JCV8X9M2NQW3TYZ4R5S6789A"
        assertFalse("ULID must be rejected as ClientUuid", ClientUuid.isValid(ulid))
        assertThrows(IllegalArgumentException::class.java) { ClientUuid.parse(ulid) }

        // Malformed UUIDs rejected
        assertFalse(ClientUuid.isValid("not-a-uuid"))
        assertFalse(ClientUuid.isValid("3fa85f64-5717-4562-b3fc-2c963f66afa")) // 35 chars
        assertFalse(ClientUuid.isValid("3fa85f64-5717-4562-b3fc-2c963f66afa66")) // 37 chars
    }

    @Test
    fun testPayloadDigestValidation() {
        val validDigest = "sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9"
        assertTrue(PayloadDigest.isValid(validDigest))
        val digest = PayloadDigest.parse(validDigest)
        assertEquals(validDigest, digest.value)
        assertEquals("d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9", digest.hexString)

        // Computation from string and bytes
        val computed = PayloadDigest.fromUtf8String("hello world")
        assertTrue(computed.value.startsWith("sha256:"))
        assertEquals(71, computed.value.length) // "sha256:" (7) + 64 hex = 71

        // Uppercase hex rejected (contract requires lowercase)
        val uppercaseHex = "sha256:D442CE49A7609A9A42BB5165990D481D1C7C563CB8030403487099A9919728D9"
        assertFalse(PayloadDigest.isValid(uppercaseHex))
        assertThrows(IllegalArgumentException::class.java) { PayloadDigest.parse(uppercaseHex) }

        // Missing sha256: prefix rejected
        val bareHex = "d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9"
        assertFalse(PayloadDigest.isValid(bareHex))
        assertThrows(IllegalArgumentException::class.java) { PayloadDigest.parse(bareHex) }

        // Truncated hash rejected
        val shortHex = "sha256:d442ce49a760"
        assertFalse(PayloadDigest.isValid(shortHex))
        assertThrows(IllegalArgumentException::class.java) { PayloadDigest.parse(shortHex) }
    }
}
