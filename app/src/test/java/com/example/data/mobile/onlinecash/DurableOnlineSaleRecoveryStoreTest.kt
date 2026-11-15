package com.example.data.mobile.onlinecash

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.v2.OnlineSaleContext
import com.example.data.mobile.session.SessionCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DurableOnlineSaleRecoveryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageFile: File
    private lateinit var fakeCryptoEngine: FakeSessionCryptoEngine
    private lateinit var recoveryStore: DefaultDurableOnlineSaleRecoveryStore

    private val companyId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val actorId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val baseOrigin = "https://small-trader.example.com"
    private val clientEventId = ClientUuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    // Valid canonical RFC 8785 JSON wire payload matching raw 7 keys
    private val sampleRawWireJson = """{"client_event_id":"6ba7b810-9dad-11d1-80b4-00c04fd430c8","currency":"USD","customer_id":"01ARZ3NDEKTSV4RRFFQ69G5FA4","device_id":"550e8400-e29b-41d4-a716-446655440000","lines":[{"conversion_to_base":"1","product_id":"01ARZ3NDEKTSV4RRFFQ69G5FA5","product_unit_id":"01ARZ3NDEKTSV4RRFFQ69G5FA6","quantity":"1","tax_inclusive":true,"tax_rate":"0","tax_rate_id":null,"unit_price":"20.000000"}],"payment":{"amount":"20.00","cash_account_id":"01ARZ3NDEKTSV4RRFFQ69G5FA3","payment_method":"cash","settlement_mode":"full"},"warehouse_id":"01ARZ3NDEKTSV4RRFFQ69G5FA2"}"""
    private val sampleRawWireBytes = sampleRawWireJson.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        storageFile = File(tempFolder.newFolder(), "online_sale_recovery.bin")
        fakeCryptoEngine = FakeSessionCryptoEngine()
        recoveryStore = DefaultDurableOnlineSaleRecoveryStore(
            storageFile = storageFile,
            cryptoEngine = fakeCryptoEngine,
            packageName = "com.example.smalltrader"
        )
    }

    private fun createSampleRecord(
        eventId: ClientUuid = clientEventId,
        company: ServerUlid = companyId,
        actor: ServerUlid = actorId,
        device: ClientUuid = deviceId,
        origin: String = baseOrigin
    ): DurableOnlineSaleRecoveryRecord {
        val context = OnlineSaleContext(
            actorId = actor,
            companyId = company,
            currencyMinorUnits = 2
        )
        val preparedIntent = PreparedOnlineSaleIntent.prepare(
            context = context,
            rawWireBytes = sampleRawWireBytes,
            scopeGeneration = 1L
        )

        return DurableOnlineSaleRecoveryRecord(
            clientEventId = eventId,
            expectedNormativeProfileDigest = preparedIntent.expectedNormativeProfileDigest,
            companyId = company,
            actorId = actor,
            deviceId = device,
            baseOrigin = origin,
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("20.00"),
            rawWireBytes = sampleRawWireBytes,
            createdAt = "2026-11-15T12:00:00Z"
        )
    }

    @Test
    fun testSaveAndGet_roundTrip_exactParity() {
        val record = createSampleRecord()
        recoveryStore.savePendingSale(record)

        assertTrue(storageFile.exists())
        val retrieved = recoveryStore.getPendingSale(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            baseOrigin = baseOrigin
        )

        assertNotNull(retrieved)
        assertEquals(record.clientEventId, retrieved!!.clientEventId)
        assertEquals(record.expectedNormativeProfileDigest, retrieved.expectedNormativeProfileDigest)
        assertEquals(record.companyId, retrieved.companyId)
        assertEquals(record.actorId, retrieved.actorId)
        assertEquals(record.deviceId, retrieved.deviceId)
        assertEquals(record.baseOrigin, retrieved.baseOrigin)
        assertEquals(record.currency, retrieved.currency)
        assertEquals(record.currencyMinorUnits, retrieved.currencyMinorUnits)
        assertEquals(record.amount, retrieved.amount)
        assertEquals(record.createdAt, retrieved.createdAt)
        assertTrue(record.rawWireBytes.contentEquals(retrieved.rawWireBytes))
    }

    @Test
    fun testTenantIsolation_matchesExactScopeOnly() {
        val record = createSampleRecord()
        recoveryStore.savePendingSale(record)

        val wrongCompany = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999")
        val wrongActor = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5888")
        val wrongDevice = ClientUuid.random()
        val wrongOrigin = "https://other.example.com"

        // Scope mismatch queries MUST return null
        assertNull(recoveryStore.getPendingSale(wrongCompany, actorId, deviceId, baseOrigin))
        assertNull(recoveryStore.getPendingSale(companyId, wrongActor, deviceId, baseOrigin))
        assertNull(recoveryStore.getPendingSale(companyId, actorId, wrongDevice, baseOrigin))
        assertNull(recoveryStore.getPendingSale(companyId, actorId, deviceId, wrongOrigin))

        // Exact match succeeds
        assertNotNull(recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin))
    }

    @Test
    fun testClearPendingSale_onlyClearsMatchingEventId() {
        val recordA = createSampleRecord(eventId = ClientUuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        val recordB = createSampleRecord(
            eventId = ClientUuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c9"),
            company = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999")
        )

        recoveryStore.savePendingSale(recordA)
        recoveryStore.savePendingSale(recordB)

        assertEquals(2, recoveryStore.listAllRecords().size)

        // Clear record A
        recoveryStore.clearPendingSale(recordA.clientEventId)

        val remaining = recoveryStore.listAllRecords()
        assertEquals(1, remaining.size)
        assertEquals(recordB.clientEventId, remaining[0].clientEventId)

        // Clear record B -> file deleted when empty
        recoveryStore.clearPendingSale(recordB.clientEventId)
        assertTrue(recoveryStore.listAllRecords().isEmpty())
    }

    @Test
    fun testClearPendingSale_nonMatchingEventId_isNoOp() {
        val record = createSampleRecord()
        recoveryStore.savePendingSale(record)

        val nonExistentEventId = ClientUuid.random()
        recoveryStore.clearPendingSale(nonExistentEventId)

        // Original record remains completely intact
        val retrieved = recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin)
        assertNotNull(retrieved)
        assertEquals(record.clientEventId, retrieved!!.clientEventId)
    }

    @Test
    fun testCorruptedCiphertext_throwsRecoveryException() {
        val record = createSampleRecord()
        recoveryStore.savePendingSale(record)

        // Tamper with storage file bytes (flip byte in ciphertext payload)
        val fileBytes = storageFile.readBytes()
        fileBytes[fileBytes.size - 1] = (fileBytes[fileBytes.size - 1].toInt() xor 0xFF).toByte()
        storageFile.writeBytes(fileBytes)

        try {
            recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin)
            fail("Expected OnlineSaleRecoveryException on corrupted ciphertext")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("decrypt", ignoreCase = true) || e.message!!.contains("corrupted", ignoreCase = true))
        }
    }

    @Test
    fun testTruncatedEnvelope_throwsRecoveryException() {
        storageFile.writeBytes(byteArrayOf(0x4F, 0x53, 0x52, 0x01, 0x0C)) // Truncated header

        try {
            recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin)
            fail("Expected OnlineSaleRecoveryException on truncated envelope")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("corrupted", ignoreCase = true) || e.message!!.contains("envelope", ignoreCase = true))
        }
    }

    @Test
    fun testReboundIntent_reconstitutesValidPreparedOnlineSaleIntent() {
        val record = createSampleRecord()
        val reboundIntent = record.toPreparedOnlineSaleIntent(reboundGeneration = 42L)

        assertEquals(42L, reboundIntent.scopeGeneration)
        assertEquals(record.clientEventId, reboundIntent.clientEventId)
        assertEquals(record.companyId, reboundIntent.context.companyId)
        assertEquals(record.actorId, reboundIntent.context.actorId)
        assertEquals(record.deviceId, reboundIntent.deviceId)
        assertEquals(record.currency, reboundIntent.currency)
        assertEquals(record.expectedNormativeProfileDigest, reboundIntent.expectedNormativeProfileDigest)
        assertEquals(record.amount, reboundIntent.profile.payment.amount)
    }

    @Test
    fun testCompareAndSet_blocksSecondEventWhilePriorPending() {
        val record1 = createSampleRecord(eventId = ClientUuid.random())
        recoveryStore.savePendingSale(record1)

        val record2 = createSampleRecord(eventId = ClientUuid.random())
        try {
            recoveryStore.savePendingSale(record2)
            fail("Expected OnlineSaleRecoveryException when saving second event while unverified event pending")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("already exists", ignoreCase = true))
        }
    }

    @Test
    fun testCompareAndSet_blocksMutationOfExistingEventWithDifferentPayload() {
        val eventId = ClientUuid.random()
        val record1 = createSampleRecord(eventId = eventId)
        recoveryStore.savePendingSale(record1)

        val tamperedBytes = record1.rawWireBytes.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tamperedRecord = record1.copy(rawWireBytes = tamperedBytes)
        try {
            recoveryStore.savePendingSale(tamperedRecord)
            fail("Expected OnlineSaleRecoveryException when modifying payload bytes of existing pending event")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("different payload", ignoreCase = true))
        }
    }

    @Test
    fun testCompareAndSet_allowsIdempotentResaveOfExactSameRecord() {
        val record = createSampleRecord()
        recoveryStore.savePendingSale(record)
        // Idempotent re-save of exact same record must succeed without exception
        recoveryStore.savePendingSale(record)

        val retrieved = recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin)
        assertNotNull(retrieved)
        assertEquals(record.clientEventId, retrieved!!.clientEventId)
    }

    @Test
    fun testCanonicalizeOrigin_normalizesTrailingSlashAndCase() {
        val canonical = DurableOnlineSaleRecoveryStore.canonicalizeOrigin("HTTPS://SMALL-TRADER.EXAMPLE.COM///")
        assertEquals("https://small-trader.example.com", canonical)
    }

    /**
     * Hermetic software AES-256-GCM crypto engine for unit tests.
     */
    private class FakeSessionCryptoEngine : SessionCryptoEngine {
        private val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private val secretKey = SecretKeySpec(rawKey, "AES")

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            return Pair(iv, ciphertext)
        }

        override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad)
                cipher.doFinal(ciphertextWithTag)
            } catch (e: Exception) {
                throw com.example.data.mobile.session.SessionStorageException(
                    com.example.data.mobile.session.SessionStorageError.DecryptionFailed(e.message ?: "Decryption error")
                )
            }
        }

        override fun ensureKeyUsable(forExistingRecord: Boolean) {
            // Always usable in fake
        }
    }
}
