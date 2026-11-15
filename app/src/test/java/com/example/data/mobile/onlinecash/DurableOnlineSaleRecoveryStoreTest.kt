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
            packageName = "com.example.smalltrader",
            directorySyncEngine = DirectorySyncEngine { /* no-op in test */ }
        )
    }

    private fun createSampleRecord(
        eventId: ClientUuid = clientEventId,
        company: ServerUlid = companyId,
        actor: ServerUlid = actorId,
        device: ClientUuid = deviceId,
        origin: String = baseOrigin,
        currency: String = "USD",
        currencyMinorUnits: Int = 2,
        unitPrice: String = "20.000000",
        lineCount: Int = 1
    ): DurableOnlineSaleRecoveryRecord {
        val totalAmount = if (lineCount == 1) {
            unitPrice.substringBefore(".") + "." + unitPrice.substringAfter(".").take(2).padEnd(2, '0')
        } else {
            java.math.BigDecimal(unitPrice).multiply(java.math.BigDecimal(lineCount)).setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()
        }

        val linesJson = (0 until lineCount).joinToString(",") {
            """{"conversion_to_base":"1","product_id":"01ARZ3NDEKTSV4RRFFQ69G5FA5","product_unit_id":"01ARZ3NDEKTSV4RRFFQ69G5FA6","quantity":"1","tax_inclusive":true,"tax_rate":"0","tax_rate_id":null,"unit_price":"$unitPrice"}"""
        }

        val rawWireJson = """{"client_event_id":"${eventId.value}","currency":"$currency","customer_id":"01ARZ3NDEKTSV4RRFFQ69G5FA4","device_id":"${device.value}","lines":[$linesJson],"payment":{"amount":"$totalAmount","cash_account_id":"01ARZ3NDEKTSV4RRFFQ69G5FA3","payment_method":"cash","settlement_mode":"full"},"warehouse_id":"01ARZ3NDEKTSV4RRFFQ69G5FA2"}"""
        val rawWireBytes = rawWireJson.toByteArray(Charsets.UTF_8)

        val context = OnlineSaleContext(
            actorId = actor,
            companyId = company,
            currencyMinorUnits = currencyMinorUnits
        )
        val preparedIntent = PreparedOnlineSaleIntent.prepare(
            context = context,
            rawWireBytes = rawWireBytes,
            scopeGeneration = 1L
        )

        return DurableOnlineSaleRecoveryRecord(
            clientEventId = eventId,
            expectedNormativeProfileDigest = preparedIntent.expectedNormativeProfileDigest,
            companyId = company,
            actorId = actor,
            deviceId = device,
            baseOrigin = origin,
            currency = currency,
            currencyMinorUnits = currencyMinorUnits,
            amount = MoneyAmount(totalAmount),
            rawWireBytes = rawWireBytes,
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
        val record1 = createSampleRecord(eventId = eventId, unitPrice = "20.000000")
        recoveryStore.savePendingSale(record1)

        val record2 = createSampleRecord(eventId = eventId, unitPrice = "30.000000")
        try {
            recoveryStore.savePendingSale(record2)
            fail("Expected OnlineSaleRecoveryException when modifying payload bytes of existing pending event")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Cannot overwrite pending event", ignoreCase = true) ||
                    e.message!!.contains("different payload", ignoreCase = true))
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

    @Test
    fun testCanonicalizeOrigin_strictValidation_failsClosedOnInvalid() {
        try {
            DurableOnlineSaleRecoveryStore.canonicalizeOrigin("ftp://small-trader.example.com")
            fail("Expected IllegalArgumentException for non-http/https scheme")
        } catch (_: IllegalArgumentException) {}

        try {
            DurableOnlineSaleRecoveryStore.canonicalizeOrigin("javascript:alert(1)")
            fail("Expected IllegalArgumentException for javascript URI")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun testCanonicalizeOrigin_normalizesDefaultPorts() {
        val httpsDefault = DurableOnlineSaleRecoveryStore.canonicalizeOrigin("https://small-trader.example.com:443")
        assertEquals("https://small-trader.example.com", httpsDefault)

        val httpDefault = DurableOnlineSaleRecoveryStore.canonicalizeOrigin("http://127.0.0.1:80", allowHttpLoopback = true)
        assertEquals("http://127.0.0.1", httpDefault)

        val nonDefault = DurableOnlineSaleRecoveryStore.canonicalizeOrigin("https://small-trader.example.com:8443")
        assertEquals("https://small-trader.example.com:8443", nonDefault)
    }

    @Test
    fun testDurabilitySyncFailure_propagatesRecoveryExceptionBeforePost() {
        recoveryStore.directorySyncEngine = DirectorySyncEngine {
            throw java.io.IOException("Simulated POSIX fsync failure")
        }
        val record = createSampleRecord()
        try {
            recoveryStore.savePendingSale(record)
            fail("Expected OnlineSaleRecoveryException when directory sync fails")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Directory durability fsync failed", ignoreCase = true))
        }
    }

    @Test
    fun testEnvelope_nearLimit_succeeds() {
        // Generate a ~110 KB valid canonical payload with 500 lines (well within 1 MiB limit)
        val record = createSampleRecord(lineCount = 500)
        recoveryStore.savePendingSale(record)

        val retrieved = recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin)
        assertNotNull(retrieved)
        assertEquals(record.clientEventId, retrieved!!.clientEventId)
        assertEquals(record.rawWireBytes.size, retrieved.rawWireBytes.size)
        assertTrue(record.rawWireBytes.contentEquals(retrieved.rawWireBytes))
    }

    @Test
    fun testEnvelope_overflow_throwsException() {
        // Generate an oversized valid canonical payload with 5000 lines (~1.1 MB, exceeding 1 MiB MAX_FILE_BYTES)
        val record = createSampleRecord(lineCount = 5000)
        try {
            recoveryStore.savePendingSale(record)
            fail("Expected OnlineSaleRecoveryException on oversized envelope")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(
                e.message!!.contains("exceeds maximum storage bound", ignoreCase = true) ||
                        e.message!!.contains("exceeds maximum allowable", ignoreCase = true)
            )
        }
    }

    @Test
    fun testConsistency_inconsistentEventId_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(clientEventId = ClientUuid.random())
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent eventId")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_inconsistentDeviceId_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(deviceId = ClientUuid.random())
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent deviceId")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_inconsistentCompanyId_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(companyId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999"))
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent companyId")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_inconsistentActorId_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(actorId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5888"))
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent actorId")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_inconsistentCurrency_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(currency = "EUR")
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent currency")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_inconsistentAmount_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(amount = MoneyAmount("999.00"))
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent amount")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_inconsistentDigest_rejectedOnSave() {
        val valid = createSampleRecord()
        val inconsistent = valid.copy(expectedNormativeProfileDigest = PayloadDigest("sha256:" + "0".repeat(64)))
        try {
            recoveryStore.savePendingSale(inconsistent)
            fail("Expected OnlineSaleRecoveryException for inconsistent digest")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("Inconsistent", ignoreCase = true) || e.message!!.contains("mismatch", ignoreCase = true) || e.message!!.contains("digest", ignoreCase = true))
        }
    }

    @Test
    fun testConsistency_corruptedDuplicateOnDisk_readFailsClosed() {
        val recordA = createSampleRecord(eventId = ClientUuid.random(), company = companyId)
        val recordsWithDuplicate = listOf(recordA, recordA)
        val plaintext = DurableOnlineSaleRecoveryWireFormat.serialize(recordsWithDuplicate)
        val aad = "purpose=online_sale_recovery;pkg=com.example.smalltrader;v=1".toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = fakeCryptoEngine.encrypt(plaintext, aad)
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        dos.write(byteArrayOf(0x4F, 0x53, 0x52, 0x01))
        dos.writeByte(iv.size)
        dos.write(iv)
        dos.writeInt(ciphertext.size)
        dos.write(ciphertext)
        dos.flush()
        storageFile.writeBytes(baos.toByteArray())

        try {
            recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin)
            fail("Expected OnlineSaleRecoveryException when reading file with corrupted duplicate records")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("duplicate", ignoreCase = true) || e.message!!.contains("Corrupted", ignoreCase = true))
        }
    }

    @Test
    fun testGlobalEventUuidCollision_acrossScopes_rejected() {
        val sharedEventId = ClientUuid.random()
        val recordA = createSampleRecord(eventId = sharedEventId, company = companyId)
        recoveryStore.savePendingSale(recordA)

        val otherCompany = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999")
        val recordB = createSampleRecord(eventId = sharedEventId, company = otherCompany)
        try {
            recoveryStore.savePendingSale(recordB)
            fail("Expected OnlineSaleRecoveryException on global event UUID collision across different companies")
        } catch (e: OnlineSaleRecoveryException) {
            assertTrue(e.message!!.contains("collision across identity scopes", ignoreCase = true))
        }
    }

    @Test
    fun testScopedClearPendingSale_onlyClearsMatchingScope() {
        val recordA = createSampleRecord(eventId = ClientUuid.random(), company = companyId)
        recoveryStore.savePendingSale(recordA)

        val nonMatchingCompany = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999")
        // Calling scoped clear with non-matching company must NOT delete the record
        recoveryStore.clearPendingSale(
            clientEventId = recordA.clientEventId,
            companyId = nonMatchingCompany,
            actorId = actorId,
            deviceId = deviceId,
            baseOrigin = baseOrigin
        )
        assertNotNull(recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin))

        // Calling scoped clear with exact matching parameters succeeds and deletes the record
        recoveryStore.clearPendingSale(
            clientEventId = recordA.clientEventId,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            baseOrigin = baseOrigin
        )
        assertNull(recoveryStore.getPendingSale(companyId, actorId, deviceId, baseOrigin))
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
                    com.example.data.mobile.session.SessionStorageError.Corrupt(e.message ?: "Decryption error")
                )
            }
        }

        override fun ensureKeyUsable(forExistingRecord: Boolean) {
            // Always usable in fake
        }
    }
}
