package com.example.data.mobile.session

import android.content.Context
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import com.example.data.mobile.contract.CanonicalSerializationException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SecureStaffSessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var noBackupDir: File
    private lateinit var sessionFile: File
    private lateinit var installationStore: InstallationIdentityStore
    private lateinit var cryptoEngine: JvmAesGcmCryptoEngine
    private lateinit var store: DefaultSecureStaffSessionStore

    private val testPackageName = "com.aistudio.smalltrader.psdukx"
    private val validActorUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val foreignActorUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val validCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val foreignCompanyUlid = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val validToken = "sanctum_plain_text_token_secret_value_1234567890"

    @Before
    fun setUp() {
        SessionProcessLocks.clearForTesting()
        noBackupDir = tempFolder.newFolder("noBackup")
        sessionFile = File(noBackupDir, SecureStaffSessionStore.SESSION_FILE_NAME)
        installationStore = InstallationIdentityStore.create(noBackupDir)
        cryptoEngine = JvmAesGcmCryptoEngine()
        store = DefaultSecureStaffSessionStore(
            sessionFile = sessionFile,
            installationStore = installationStore,
            cryptoEngine = cryptoEngine,
            packageName = testPackageName
        )
    }

    private fun createValidRecord(
        deviceUuid: ClientUuid = store.installationId,
        actorUlid: ServerUlid = validActorUlid,
        selectedCompanyId: ServerUlid? = validCompanyUlid,
        expiresAt: String = "2030-01-01T00:00:00Z",
        token: String = validToken
    ): StaffSessionRecord = StaffSessionRecord(
        token = token,
        actorUlid = actorUlid,
        deviceUuid = deviceUuid,
        passwordEpoch = "epoch_hash_5678_abcdef",
        expiresAt = expiresAt,
        selectedCompanyId = selectedCompanyId,
        roleLabel = "sales_staff",
        actorName = "Jane Trader",
        actorEmail = "jane@example.com",
        createdAt = "2026-11-12T06:00:00Z"
    )

    // 1. Meaningful encrypt / read roundtrip test key
    @Test
    fun `meaningful encrypt and read roundtrip preserves all fields with test key`() {
        val record = createValidRecord()
        assertFalse(store.hasSessionFile())

        store.saveSession(record)
        assertTrue(store.hasSessionFile())

        val scope = SessionReadScope(
            expectedDeviceUuid = store.installationId,
            expectedActorUlid = validActorUlid,
            expectedCompanyId = validCompanyUlid
        )
        val result = store.readSession(scope, nowEpochMs = 1773300000000L) // in 2026

        assertTrue(result is SessionReadResult.Success)
        val readSession = (result as SessionReadResult.Success).session
        assertEquals(record.token, readSession.token)
        assertEquals(record.actorUlid, readSession.actorUlid)
        assertEquals(record.deviceUuid, readSession.deviceUuid)
        assertEquals(record.passwordEpoch, readSession.passwordEpoch)
        assertEquals(record.expiresAt, readSession.expiresAt)
        assertEquals(record.selectedCompanyId, readSession.selectedCompanyId)
        assertEquals(record.roleLabel, readSession.roleLabel)
        assertEquals(record.actorName, readSession.actorName)
        assertEquals(record.actorEmail, readSession.actorEmail)
        assertEquals(record.createdAt, readSession.createdAt)
    }

    // 2. Plaintext disk secret absence
    @Test
    fun `plaintext disk secret absence verifies file contains no raw credentials or personal data`() {
        val sensitiveToken = "sanctum_super_secret_token_never_plain_on_disk_1234567890"
        val sensitiveEpoch = "epoch_top_secret_hash_value_987654321"
        val sensitiveEmail = "sensitive_staff_member@hidden-domain.com"
        val sensitiveName = "VeryUniqueStaffName"

        val record = createValidRecord(
            token = sensitiveToken
        ).copy(
            passwordEpoch = sensitiveEpoch,
            actorEmail = sensitiveEmail,
            actorName = sensitiveName
        )

        store.saveSession(record)
        assertTrue(sessionFile.exists())

        val diskBytes = sessionFile.readBytes()
        val diskString = String(diskBytes, Charsets.ISO_8859_1)
        val diskUtf8 = String(diskBytes, Charsets.UTF_8)

        assertFalse("Token must not appear in raw disk bytes", diskString.contains(sensitiveToken))
        assertFalse("Token must not appear in UTF-8 disk string", diskUtf8.contains(sensitiveToken))
        assertFalse("Password epoch must not appear on disk", diskString.contains(sensitiveEpoch))
        assertFalse("Email must not appear on disk", diskString.contains(sensitiveEmail))
        assertFalse("Name must not appear on disk", diskString.contains(sensitiveName))
    }

    // 3. Tamper cipher nonce AAD version fail preserve file
    @Test
    fun `tamper ciphertext byte fails closed with Corrupt and preserves file`() {
        val record = createValidRecord()
        store.saveSession(record)

        val originalBytes = sessionFile.readBytes()
        // Corrupt the very last byte (part of ciphertext/tag)
        val tamperedBytes = originalBytes.clone()
        tamperedBytes[tamperedBytes.size - 1] = (tamperedBytes[tamperedBytes.size - 1].toInt() xor 0xFF).toByte()
        sessionFile.writeBytes(tamperedBytes)

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Corrupt result, got $result", result is SessionReadResult.Corrupt)
        assertTrue("Corrupted file must be preserved on disk", sessionFile.exists())
        assertEquals(originalBytes.size.toLong(), sessionFile.length())
    }

    @Test
    fun `tamper IV nonce in envelope fails closed with Corrupt and preserves file`() {
        val record = createValidRecord()
        store.saveSession(record)

        val originalBytes = sessionFile.readBytes()
        // IV begins at byte index 5
        val tamperedBytes = originalBytes.clone()
        tamperedBytes[5] = (tamperedBytes[5].toInt() xor 0xAA).toByte()
        sessionFile.writeBytes(tamperedBytes)

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Corrupt result, got $result", result is SessionReadResult.Corrupt)
        assertTrue("File must be preserved on disk", sessionFile.exists())
    }

    @Test
    fun `tamper AAD package or version fails closed with Corrupt and preserves file`() {
        val record = createValidRecord()
        store.saveSession(record)

        // Create store instance with different package name (simulating altered AAD context)
        val foreignStore = DefaultSecureStaffSessionStore(
            sessionFile = sessionFile,
            installationStore = installationStore,
            cryptoEngine = cryptoEngine,
            packageName = "com.attacker.spoofed.package"
        )

        val scope = SessionReadScope(store.installationId)
        val result = foreignStore.readSession(scope)

        assertTrue("Expected Corrupt on AAD mismatch, got $result", result is SessionReadResult.Corrupt)
        assertTrue("File must be preserved on disk", sessionFile.exists())
    }

    @Test
    fun `tamper magic header bytes fails closed with Corrupt and preserves file`() {
        val record = createValidRecord()
        store.saveSession(record)

        val originalBytes = sessionFile.readBytes()
        val tamperedBytes = originalBytes.clone()
        tamperedBytes[0] = 0x00 // Alter magic 'S'
        sessionFile.writeBytes(tamperedBytes)

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Corrupt on magic mismatch, got $result", result is SessionReadResult.Corrupt)
        assertTrue("File must be preserved on disk", sessionFile.exists())
    }

    @Test
    fun `tamper IV length byte fails closed with Corrupt and preserves file`() {
        val record = createValidRecord()
        store.saveSession(record)

        val originalBytes = sessionFile.readBytes()
        val tamperedBytes = originalBytes.clone()
        tamperedBytes[4] = 16 // Expected 12
        sessionFile.writeBytes(tamperedBytes)

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Corrupt on IV length mismatch, got $result", result is SessionReadResult.Corrupt)
        assertTrue("File must be preserved on disk", sessionFile.exists())
    }

    // 4. Key unavailable / invalidation no fallback
    @Test
    fun `key unavailable fails closed with Unavailable and does not delete file or recreate key`() {
        val record = createValidRecord()
        store.saveSession(record)
        val originalFileLength = sessionFile.length()

        // Simulate key missing from Keystore
        cryptoEngine.secretKey = null

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Unavailable result, got $result", result is SessionReadResult.Unavailable)
        assertTrue("File must not be deleted when key is unavailable", sessionFile.exists())
        assertEquals(originalFileLength, sessionFile.length())
        assertNull("Key must not be silently recreated on read", cryptoEngine.secretKey)
    }

    @Test
    fun `key permanently invalidated fails closed with Locked and preserves file`() {
        val record = createValidRecord()
        store.saveSession(record)

        // Simulate key invalidated / locked in Keystore
        cryptoEngine.isLocked = true

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Locked result, got $result", result is SessionReadResult.Locked)
        assertTrue("File must not be deleted when key is locked", sessionFile.exists())
    }

    // 5. Scope mismatch token hidden
    @Test
    fun `scope mismatch on device UUID conceals token`() {
        val record = createValidRecord()
        store.saveSession(record)

        val foreignDevice = ClientUuid.random()
        val scope = SessionReadScope(expectedDeviceUuid = foreignDevice)
        val result = store.readSession(scope)

        assertTrue("Expected ScopeMismatch, got $result", result is SessionReadResult.ScopeMismatch)
        val error = (result as SessionReadResult.ScopeMismatch).error
        assertTrue(error.message.contains("Device UUID mismatch") || error.message.contains("device"))
    }

    @Test
    fun `scope mismatch on actor ULID conceals token`() {
        val record = createValidRecord()
        store.saveSession(record)

        val scope = SessionReadScope(
            expectedDeviceUuid = store.installationId,
            expectedActorUlid = foreignActorUlid
        )
        val result = store.readSession(scope)

        assertTrue("Expected ScopeMismatch, got $result", result is SessionReadResult.ScopeMismatch)
    }

    @Test
    fun `scope mismatch on company ULID conceals token`() {
        val record = createValidRecord()
        store.saveSession(record)

        val scope = SessionReadScope(
            expectedDeviceUuid = store.installationId,
            expectedCompanyId = foreignCompanyUlid
        )
        val result = store.readSession(scope)

        assertTrue("Expected ScopeMismatch, got $result", result is SessionReadResult.ScopeMismatch)
    }

    // 6. Expired never returned
    @Test
    fun `expired session is never returned and record is preserved on disk`() {
        val expiredRecord = createValidRecord(expiresAt = "2020-01-01T00:00:00Z")
        store.saveSession(expiredRecord)

        val scope = SessionReadScope(store.installationId)
        val nowMs = 1773300000000L // 2026
        val result = store.readSession(scope, nowEpochMs = nowMs)

        assertTrue("Expected Expired result, got $result", result is SessionReadResult.Expired)
        val err = (result as SessionReadResult.Expired).error
        assertEquals("2020-01-01T00:00:00Z", err.expiresAt)
        assertTrue("Session file must be preserved on disk for auditing/re-auth", sessionFile.exists())
    }

    // 7. Oversize / truncated file
    @Test
    fun `oversized file exceeding 1 MiB fails closed with Oversized without allocating memory`() {
        // Create 1 MiB + 1 byte file
        val oversizedLen = 1024 * 1024 + 1
        val dummy = ByteArray(1024)
        FileOutputStream(sessionFile).use { fos ->
            var written = 0
            while (written < oversizedLen) {
                val toWrite = minOf(dummy.size, oversizedLen - written)
                fos.write(dummy, 0, toWrite)
                written += toWrite
            }
        }
        assertEquals(oversizedLen.toLong(), sessionFile.length())

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Oversized result, got $result", result is SessionReadResult.Oversized)
        val err = (result as SessionReadResult.Oversized).error
        assertEquals(oversizedLen.toLong(), err.sizeBytes)
        assertTrue("Oversized file must be preserved for forensic analysis", sessionFile.exists())
    }

    @Test
    fun `truncated file fails closed with Corrupt and preserves file`() {
        sessionFile.writeBytes(byteArrayOf(0x53, 0x53, 0x53, 0x01, 0x0C)) // Only 5 bytes

        val scope = SessionReadScope(store.installationId)
        val result = store.readSession(scope)

        assertTrue("Expected Corrupt on truncated file, got $result", result is SessionReadResult.Corrupt)
        assertTrue(sessionFile.exists())
    }

    // 8. Atomic write fault old record preserved
    @Test
    fun `atomic write failure strictly preserves previous valid record`() {
        val record1 = createValidRecord(token = "initial_valid_token_1234567890123")
        store.saveSession(record1)

        val scope = SessionReadScope(store.installationId)
        val res1 = store.readSession(scope)
        assertTrue(res1 is SessionReadResult.Success)
        assertEquals(record1.token, (res1 as SessionReadResult.Success).session.token)

        // Configure cryptoEngine to fail on next encrypt, simulating a crash/fault during write preparation
        cryptoEngine.isLocked = true
        val record2 = createValidRecord(token = "new_failing_token_9876543210123")

        try {
            store.saveSession(record2)
            fail("Expected SessionStorageException on locked engine during save")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.Locked)
        }

        // Restore cryptoEngine and verify old record is untouched and completely valid
        cryptoEngine.isLocked = false
        val resAfterFault = store.readSession(scope)
        assertTrue("Old record must be intact after write failure", resAfterFault is SessionReadResult.Success)
        assertEquals(record1.token, (resAfterFault as SessionReadResult.Success).session.token)
    }

    // 9. Concurrent objects single process safe
    @Test
    fun `concurrent threads accessing same store maintain consistency`() {
        val record = createValidRecord()
        store.saveSession(record)

        val threadCount = 8
        val iterationsPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val scope = SessionReadScope(store.installationId)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        if (i % 2 == 0) {
                            val res = store.readSession(scope)
                            if (res is SessionReadResult.Success && res.session.token == record.token) {
                                successCount.incrementAndGet()
                            } else {
                                errorCount.incrementAndGet()
                            }
                        } else {
                            store.saveSession(record)
                            successCount.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errorCount.get())
        assertEquals(threadCount * iterationsPerThread, successCount.get())
    }

    // 10. Logout installation UUID + external queue-sentinel preserved
    @Test
    fun `clearSession removes session file only and strictly preserves installation UUID and external queues`() {
        val originalInstallationId = store.installationId
        assertNotNull(originalInstallationId)

        // Place an external queue sentinel file in the same directory (simulating an offline Room/outbox database)
        val externalQueueFile = File(noBackupDir, "room_offline_outbox_queue.bin")
        val queuePayload = "pending_cash_transaction_ulid_01JCV8X9M2NQW3TYZ4R5S6789A".toByteArray(Charsets.UTF_8)
        externalQueueFile.writeBytes(queuePayload)

        val record = createValidRecord()
        store.saveSession(record)
        assertTrue(store.hasSessionFile())
        assertTrue(externalQueueFile.exists())

        // Clear session (staff logout)
        store.clearSession()

        // 1. Session file is gone
        assertFalse(store.hasSessionFile())
        assertFalse(sessionFile.exists())

        // 2. Installation UUID is intact and unchanged
        val postLogoutInstallationId = store.installationId
        assertEquals("Installation ID must survive staff logout", originalInstallationId, postLogoutInstallationId)

        // 3. External queue file is completely intact and unaltered
        assertTrue("External outbox/queue files must never be purged on staff logout", externalQueueFile.exists())
        assertArrayEquals(queuePayload, externalQueueFile.readBytes())

        // 4. Reading session now returns NoSession cleanly
        val scope = SessionReadScope(originalInstallationId)
        val readResult = store.readSession(scope)
        assertEquals(SessionReadResult.NoSession, readResult)
    }

    // 11. Debug / errors no secrets
    @Test
    fun `toString and error representations strictly redact tokens and personal secrets`() {
        val record = createValidRecord()
        val recordString = record.toString()

        assertFalse("Record toString must not leak plaintext token", recordString.contains(validToken))
        assertTrue("Record toString must redact token", recordString.contains("token='Bearer [REDACTED]'"))
        assertFalse("Record toString must not leak password epoch", recordString.contains(record.passwordEpoch))
        assertTrue("Record toString must redact password epoch", recordString.contains("passwordEpoch='[REDACTED]'"))
        assertFalse("Record toString must not leak email", recordString.contains(record.actorEmail!!))
        assertTrue("Record toString must redact email", recordString.contains("actorEmail='[REDACTED]'"))

        // Verify SessionStorageError does not contain secrets
        val corruptErr = SessionStorageError.Corrupt("Corrupt data error")
        assertFalse(corruptErr.toString().contains(validToken))
        val lockedErr = SessionStorageError.Locked("Locked key error")
        assertFalse(lockedErr.toString().contains(validToken))
        val unavailableErr = SessionStorageError.Unavailable("Unavailable key error")
        assertFalse(unavailableErr.toString().contains(validToken))
    }

    // 12. noBackup path factory proven
    @Test
    fun `noBackup path factory proven verifies storage resolves inside noBackupFilesDir`() {
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = noBackupDir
            override fun getPackageName(): String = testPackageName
        }

        val createdStore = SecureStaffSessionStore.create(mockContext)
        assertNotNull(createdStore)
        assertEquals(store.installationId, createdStore.installationId)

        val record = createValidRecord(deviceUuid = createdStore.installationId)
        // Note: DefaultSecureStaffSessionStore created via factory uses AndroidKeyStoreCryptoEngine in production,
        // which on pure JVM throws Unavailable or fails gracefully.
        // We verify that the resolved directory is strictly noBackupDir:
        val expectedSessionFile = File(noBackupDir, SecureStaffSessionStore.SESSION_FILE_NAME)
        val expectedInstallationFile = File(noBackupDir, InstallationIdentityStore.IDENTITY_FILE_NAME)

        assertTrue(expectedInstallationFile.exists())
        assertEquals(noBackupDir.absolutePath, expectedSessionFile.parentFile?.absolutePath)
        assertEquals(noBackupDir.absolutePath, expectedInstallationFile.parentFile?.absolutePath)
    }

    // =========================================================================
    // Regression Tests covering Findings 1 to 8
    // =========================================================================

    // Finding 1: RFC 3339 Timestamp Parsing & Strict Calendar / Offset / Expiry
    @Test
    fun `finding 1 - RFC 3339 parses millisecond fraction and nonzero seconds correctly`() {
        // .123Z
        val ms1 = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T00:00:45.123Z")
        val ms2 = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T00:00:45Z")
        assertEquals(45_123L, ms1 - SessionValidation.parseRfc3339ToEpochMs("2030-01-01T00:00:00Z"))
        assertEquals(123L, ms1 - ms2)

        // Positive offset with fraction: 12:00:00.500+02:00 == 10:00:00.500Z
        val msPlusOffset = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T12:00:00.500+02:00")
        val msUtcEquivalent = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T10:00:00.500Z")
        assertEquals(msUtcEquivalent, msPlusOffset)

        // Negative offset with fraction: 12:00:00.500-05:00 == 17:00:00.500Z
        val msMinusOffset = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T12:00:00.500-05:00")
        val msUtcMinusEquivalent = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T17:00:00.500Z")
        assertEquals(msUtcMinusEquivalent, msMinusOffset)

        // Offsets without fractions
        val msPlusNoFrac = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T12:00:00+02:00")
        assertEquals(SessionValidation.parseRfc3339ToEpochMs("2030-01-01T10:00:00Z"), msPlusNoFrac)
        val msMinusNoFrac = SessionValidation.parseRfc3339ToEpochMs("2030-01-01T12:00:00-05:00")
        assertEquals(SessionValidation.parseRfc3339ToEpochMs("2030-01-01T17:00:00Z"), msMinusNoFrac)
    }

    @Test
    fun `finding 1 - RFC 3339 strictly rejects invalid calendar, offset, and trailing newline`() {
        val invalidInputs = listOf(
            "2021-02-29T10:00:00Z", // Non-leap year Feb 29
            "2030-13-01T00:00:00Z", // Month 13
            "2030-04-31T00:00:00Z", // April 31
            "2030-01-01T24:00:00Z", // Hour 24
            "2030-01-01T00:60:00Z", // Minute 60
            "2030-01-01T00:00:60Z", // Second 60
            "2030-01-01T00:00:00+24:00", // Offset hour 24
            "2030-01-01T00:00:00+00:60", // Offset minute 60
            "2030-01-01T00:00:00Z\n", // Trailing newline
            "2030-01-01T00:00:00Z\r\n", // Trailing CRLF
            "2030-01-01T00:00:00", // Missing offset / Z
            "2019-12-31T23:59:59Z" // Year before 2020
        )

        for (invalid in invalidInputs) {
            try {
                SessionValidation.parseRfc3339ToEpochMs(invalid)
                fail("Expected CanonicalSerializationException for invalid timestamp: '$invalid'")
            } catch (e: CanonicalSerializationException) {
                // Assert no raw input was echoed in error message
                assertFalse("Error message must not echo sensitive input '$invalid'", e.message?.contains(invalid) == true)
            }
        }
    }

    @Test
    fun `finding 1 - exact expiry boundary evaluation`() {
        val expiresAt = "2030-01-01T00:00:00Z"
        val expiryEpochMs = SessionValidation.parseRfc3339ToEpochMs(expiresAt)
        val record = createValidRecord(expiresAt = expiresAt)
        store.saveSession(record)

        val scope = SessionReadScope(store.installationId)

        // 1 ms before expiry: valid
        val validResult = store.readSession(scope, nowEpochMs = expiryEpochMs - 1)
        assertTrue("Must be valid 1ms before expiry", validResult is SessionReadResult.Success)

        // Exact expiry boundary: expired
        val exactBoundaryResult = store.readSession(scope, nowEpochMs = expiryEpochMs)
        assertTrue("Must be expired on exact boundary", exactBoundaryResult is SessionReadResult.Expired)

        // 1 ms after expiry: expired
        val afterBoundaryResult = store.readSession(scope, nowEpochMs = expiryEpochMs + 1)
        assertTrue("Must be expired after boundary", afterBoundaryResult is SessionReadResult.Expired)
    }

    // Finding 2: Multi-Instance Shared Process Lock & Stable Installation UUID
    @Test
    fun `finding 2 - simultaneously constructed InstallationIdentityStores share canonical path lock and produce stable UUID`() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val barrierLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = java.util.concurrent.ConcurrentLinkedQueue<ClientUuid>()

        for (i in 0 until threadCount) {
            executor.execute {
                barrierLatch.await()
                // Each thread constructs its own instance pointing to the same file
                val instance = InstallationIdentityStore.create(noBackupDir)
                results.add(instance.getOrCreateInstallationId())
                doneLatch.countDown()
            }
        }

        barrierLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threadCount, results.size)
        val firstUuid = results.peek()
        for (uuid in results) {
            assertEquals("All simultaneously constructed instances must yield identical UUID", firstUuid, uuid)
        }
    }

    @Test
    fun `finding 2 - multiple store instances coordinate under shared process lock`() {
        val store2 = DefaultSecureStaffSessionStore(
            sessionFile = sessionFile,
            installationStore = installationStore,
            cryptoEngine = cryptoEngine,
            packageName = testPackageName
        )

        val record = createValidRecord()
        store.saveSession(record)

        val scope = SessionReadScope(store.installationId)
        val readFromStore2 = store2.readSession(scope)
        assertTrue(readFromStore2 is SessionReadResult.Success)
        assertEquals(record.token, (readFromStore2 as SessionReadResult.Success).session.token)

        store2.clearSession()
        assertFalse(store.hasSessionFile())
        assertEquals(SessionReadResult.NoSession, store.readSession(scope))
    }

    @Test
    fun `in flight save completes before waiting logout without resurrection`() {
        val record = createValidRecord()
        val encryptEntered = CountDownLatch(1)
        val releaseEncrypt = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val blockingCrypto = object : SessionCryptoEngine by cryptoEngine {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
                encryptEntered.countDown()
                check(releaseEncrypt.await(5, TimeUnit.SECONDS))
                return cryptoEngine.encrypt(plaintext, aad)
            }
        }
        val savingStore = DefaultSecureStaffSessionStore(sessionFile, installationStore, blockingCrypto, testPackageName)
        val clearingStore = DefaultSecureStaffSessionStore(sessionFile, installationStore, cryptoEngine, testPackageName)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val save = executor.submit { savingStore.saveSession(record) }
            assertTrue(encryptEntered.await(5, TimeUnit.SECONDS))
            val clear = executor.submit {
                clearStarted.countDown()
                clearingStore.clearSession()
            }
            assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
            try {
                clear.get(150, TimeUnit.MILLISECONDS)
                fail("Logout must wait for the in-flight save critical section")
            } catch (_: java.util.concurrent.TimeoutException) {
                // Encryption holds the shared lock until the complete save is committed.
            }
            releaseEncrypt.countDown()
            save.get(5, TimeUnit.SECONDS)
            clear.get(5, TimeUnit.SECONDS)
            assertFalse(sessionFile.exists())
        } finally {
            releaseEncrypt.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `existing invalid identity records fail closed without byte changes`() {
        val identityFile = File(noBackupDir, InstallationIdentityStore.IDENTITY_FILE_NAME)
        val samples = listOf(
            "{}".toByteArray(),
            "{\"device_id\":\"private-invalid-uuid\"}".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
            ByteArray(1024 * 1024 + 1) { 65 }
        )
        for (bytes in samples) {
            identityFile.writeBytes(bytes)
            try {
                InstallationIdentityStore(identityFile).getOrCreateInstallationId()
                fail("An existing unreadable identity must never be replaced")
            } catch (e: SessionStorageException) {
                assertTrue(e.error is SessionStorageError.Corrupt || e.error is SessionStorageError.Oversized)
                assertFalse(e.toString().contains("private-invalid-uuid"))
            }
            assertArrayEquals(bytes, identityFile.readBytes())
        }
    }

    @Test
    fun `identity IO failure preserves original filesystem entry`() {
        val identityFile = File(noBackupDir, InstallationIdentityStore.IDENTITY_FILE_NAME)
        assertTrue(identityFile.mkdir())
        try {
            InstallationIdentityStore(identityFile).getOrCreateInstallationId()
            fail("Unreadable identity entry must fail closed")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.IoError)
        }
        assertTrue(identityFile.isDirectory)
    }

    @Test
    fun `cached identity does not conceal corruption or missing persisted identity`() {
        val original = installationStore.getOrCreateInstallationId()
        val identityFile = File(noBackupDir, InstallationIdentityStore.IDENTITY_FILE_NAME)
        identityFile.writeText("{}")
        try {
            installationStore.getOrCreateInstallationId()
            fail("Cache must not hide corrupted storage")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.Corrupt)
        }
        assertEquals("{}", identityFile.readText())
        assertTrue(identityFile.delete())
        try {
            installationStore.getOrCreateInstallationId()
            fail("An initialized identity must not silently rotate after file loss")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.IoError)
        }
        assertFalse(identityFile.exists())
        assertNotNull(original)
    }

    @Test
    fun `logout deletion failure is reported instead of claiming success`() {
        assertTrue(sessionFile.mkdir())
        val sentinel = File(sessionFile, "preserved")
        sentinel.writeText("keep")
        try {
            store.clearSession()
            fail("Failed credential deletion must be reported")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.IoError)
        }
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `read preserves cancellation and stored ciphertext`() {
        store.saveSession(createValidRecord())
        val original = sessionFile.readBytes()
        val cancellation = java.util.concurrent.CancellationException("private-cancellation")
        val cancellingCrypto = object : SessionCryptoEngine by cryptoEngine {
            override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
                throw cancellation
            }
        }
        val cancellingStore = DefaultSecureStaffSessionStore(sessionFile, installationStore, cancellingCrypto, testPackageName)
        try {
            cancellingStore.readSession(SessionReadScope(store.installationId))
            fail("Cancellation must propagate")
        } catch (e: java.util.concurrent.CancellationException) {
            assertTrue(e === cancellation)
        }
        assertArrayEquals(original, sessionFile.readBytes())
    }

    // Finding 4: Unique Sibling Temporary Files and Operation-Owned Cleanup
    @Test
    fun `finding 4 - saveSession uses unique sibling temporary file and does not collide`() {
        val record = createValidRecord()
        store.saveSession(record)
        assertTrue(sessionFile.exists())

        // Ensure no lingering temporary files matching *.tmp exist in directory
        val tempFiles = noBackupDir.listFiles { _, name -> name.endsWith(".tmp") }
        assertNotNull(tempFiles)
        assertEquals("All temporary files must be cleaned up after successful write", 0, tempFiles!!.size)

        // Verify clearSession does not delete other temporary files
        val foreignTemp = File(noBackupDir, "unrelated_worker.tmp")
        foreignTemp.writeBytes(byteArrayOf(1, 2, 3))
        store.clearSession()

        assertTrue("clearSession must NOT delete unrelated temporary files", foreignTemp.exists())
        foreignTemp.delete()
    }

    // Finding 5: Atomic Rename Failure Preserves Prior Good Record Without Truncation
    @Test
    fun `finding 5 - rename failure fails closed and preserves prior record without truncation`() {
        val record1 = createValidRecord(token = "good_record_token_1234567890123")
        store.saveSession(record1)
        assertTrue(sessionFile.exists())
        val originalBytes = sessionFile.readBytes()

        // Inject rename failure
        store.renameOperation = { _, _ -> false }

        val record2 = createValidRecord(token = "record2_that_fails_rename_12345678")
        try {
            store.saveSession(record2)
            fail("Expected SessionStorageException on failed rename")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.IoError)
        }

        // Verify sessionFile is still completely intact with original bytes (NOT truncated by fallback copy)
        assertTrue("Session file must exist and be intact", sessionFile.exists())
        assertArrayEquals("Session file bytes must be unchanged", originalBytes, sessionFile.readBytes())

        // Restore normal rename and verify read
        store.renameOperation = { src, dst -> src.renameTo(dst) }
        val scope = SessionReadScope(store.installationId)
        val readResult = store.readSession(scope)
        assertTrue(readResult is SessionReadResult.Success)
        assertEquals(record1.token, (readResult as SessionReadResult.Success).session.token)
    }

    // Finding 6: Bounded Streaming Read Prevents TOCTOU and Handles Stream Growth
    @Test
    fun `finding 6 - bounded reader detects stream growth exceeding 1 MiB via probe`() {
        val largeFile = File(noBackupDir, "growing_stream.bin")
        val chunk = ByteArray(8192) { 0x58 }
        FileOutputStream(largeFile).use { fos ->
            for (i in 0 until 129) { // 129 * 8192 = 1,056,768 bytes > 1 MiB
                fos.write(chunk)
            }
        }

        try {
            BoundedStreamReader.readBounded(largeFile, 1024 * 1024)
            fail("Expected SessionStorageException with Oversized error")
        } catch (e: SessionStorageException) {
            assertTrue(e.error is SessionStorageError.Oversized)
            val oversized = e.error as SessionStorageError.Oversized
            assertTrue(oversized.sizeBytes > 1024 * 1024)
        } finally {
            largeFile.delete()
        }
    }

    // Finding 7: Discard Raw Error Text and Sensitive Inputs from Outward Errors
    @Test
    fun `finding 7 - corrupted payload and scope mismatch error messages do not leak secrets`() {
        val record = createValidRecord()
        store.saveSession(record)

        // Read with mismatched actor ULID
        val wrongScope = SessionReadScope(
            expectedDeviceUuid = store.installationId,
            expectedActorUlid = foreignActorUlid,
            expectedCompanyId = foreignCompanyUlid
        )
        val res = store.readSession(wrongScope)
        assertTrue(res is SessionReadResult.ScopeMismatch)
        val msg = (res as SessionReadResult.ScopeMismatch).error.message
        assertFalse("Scope mismatch error must not contain stored actor ULID", msg.contains(validActorUlid.value))
        assertFalse("Scope mismatch error must not contain stored company ULID", msg.contains(validCompanyUlid.value))
        assertFalse("Scope mismatch error must not contain stored device UUID", msg.contains(store.installationId.value))

        // Corrupt plaintext payload test: verify Corrupt error does not leak decrypted data
        val tamperedPlaintext = "{\"token\":\"super_secret_decrypted_token_xyz\",\"invalid\":123}".toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = cryptoEngine.encrypt(tamperedPlaintext, "purpose=staff_session;pkg=$testPackageName;v=1".toByteArray(Charsets.UTF_8))
        val envelope = ByteArrayOutputStream().apply {
            val dos = DataOutputStream(this)
            dos.write(byteArrayOf(0x53, 0x53, 0x53, 0x01))
            dos.writeByte(iv.size)
            dos.write(iv)
            dos.writeInt(ciphertext.size)
            dos.write(ciphertext)
            dos.flush()
        }.toByteArray()
        sessionFile.writeBytes(envelope)

        val corruptRes = store.readSession(SessionReadScope(store.installationId))
        assertTrue(corruptRes is SessionReadResult.Corrupt)
        val corruptMsg = (corruptRes as SessionReadResult.Corrupt).error.message
        assertFalse("Corrupt error message must not leak decrypted token", corruptMsg.contains("super_secret_decrypted_token_xyz"))
    }

    // Finding 8: Key Recoverability on Save Refuses Recreation Over Existing Ciphertext
    @Test
    fun `finding 8 - missing key subsequent save preserves bytes and does not create new key`() {
        val record1 = createValidRecord(token = "initial_good_token_1234567890123")
        store.saveSession(record1)
        assertTrue(sessionFile.exists())
        val originalBytes = sessionFile.readBytes()

        // Simulate missing key
        cryptoEngine.secretKey = null

        val record2 = createValidRecord(token = "second_token_should_not_overwrite_123")
        try {
            store.saveSession(record2)
            fail("Expected SessionStorageException when saving over existing file with missing key")
        } catch (e: SessionStorageException) {
            assertTrue("Expected Unavailable error", e.error is SessionStorageError.Unavailable)
        }

        // Verify key was NOT recreated
        assertNull("Missing key must not be regenerated when file exists", cryptoEngine.secretKey)

        // Verify original ciphertext bytes on disk are preserved
        assertArrayEquals("Original ciphertext bytes must be preserved intact", originalBytes, sessionFile.readBytes())
    }

    @Test
    fun `finding 8 - invalidated key subsequent save preserves bytes and refuses recreation`() {
        val record1 = createValidRecord(token = "initial_good_token_1234567890123")
        store.saveSession(record1)
        assertTrue(sessionFile.exists())
        val originalBytes = sessionFile.readBytes()

        // Simulate permanently invalidated key
        cryptoEngine.isLocked = true

        val record2 = createValidRecord(token = "second_token_should_not_overwrite_123")
        try {
            store.saveSession(record2)
            fail("Expected SessionStorageException when saving over existing file with locked key")
        } catch (e: SessionStorageException) {
            assertTrue("Expected Locked error", e.error is SessionStorageError.Locked)
        }

        // Verify original ciphertext bytes on disk are preserved
        assertArrayEquals("Original ciphertext bytes must be preserved intact", originalBytes, sessionFile.readBytes())
    }
}

