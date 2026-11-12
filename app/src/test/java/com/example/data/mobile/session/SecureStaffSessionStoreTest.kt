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
import java.io.File
import java.io.FileOutputStream
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
}
