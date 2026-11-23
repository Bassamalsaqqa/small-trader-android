package com.example.data.mobile.outbox

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.mobile.session.SessionCryptoEngine
import com.example.data.mobile.session.SessionStorageError
import com.example.data.mobile.session.SessionStorageException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CommandStatus {
    PENDING,
    SUBMITTED,
    COMPLETED,
    UNKNOWN_ACK,
    FAILED
}

data class MerchantCommandRecord(
    val companyId: String,
    val commandId: String,
    val actorId: String,
    val deviceId: String,
    val commandType: String,
    val idempotencyKey: String,
    val payloadDigest: String,
    val payloadBytes: ByteArray,
    val status: CommandStatus,
    val responseSnapshot: ByteArray?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MerchantCommandRecord
        return companyId == other.companyId &&
            commandId == other.commandId &&
            actorId == other.actorId &&
            deviceId == other.deviceId &&
            commandType == other.commandType &&
            idempotencyKey == other.idempotencyKey &&
            payloadDigest == other.payloadDigest &&
            status == other.status &&
            payloadBytes.contentEquals(other.payloadBytes) &&
            (responseSnapshot == null && other.responseSnapshot == null ||
                (responseSnapshot != null && other.responseSnapshot != null && responseSnapshot.contentEquals(other.responseSnapshot)))
    }

    override fun hashCode(): Int {
        var result = companyId.hashCode()
        result = 31 * result + commandId.hashCode()
        result = 31 * result + idempotencyKey.hashCode()
        result = 31 * result + payloadDigest.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

interface MerchantCommandStore {
    fun recordPendingCommand(
        companyId: String,
        commandId: String,
        actorId: String,
        deviceId: String,
        commandType: String,
        idempotencyKey: String,
        payloadDigest: String,
        canonicalPayloadBytes: ByteArray
    ): MerchantCommandRecord

    fun findCommand(
        companyId: String,
        actorId: String,
        deviceId: String,
        idempotencyKey: String
    ): MerchantCommandRecord?

    fun transitionStatus(
        companyId: String,
        commandId: String,
        expectedStatus: CommandStatus,
        newStatus: CommandStatus,
        errorMessage: String? = null
    ): Boolean

    fun markSubmitted(companyId: String, commandId: String): Boolean
    fun markSubmitted(commandId: String): Boolean
    fun claimSubmittedCommand(companyId: String, commandId: String, expectedUpdatedAt: Long): Boolean

    fun markCompleted(
        companyId: String,
        commandId: String,
        responseSnapshotBytes: ByteArray
    ): Boolean
    fun markCompleted(commandId: String, responseSnapshotBytes: ByteArray): Boolean

    fun markUnknownAck(
        companyId: String,
        commandId: String,
        error: String
    ): Boolean
    fun markUnknownAck(commandId: String, error: String): Boolean

    fun markFailed(
        companyId: String,
        commandId: String,
        error: String
    ): Boolean
    fun markFailed(commandId: String, error: String): Boolean

    fun getDraftOperationId(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): String

    fun rotateDraftOperationId(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): String

    fun cancelDraftOperation(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    )

    fun getActiveDraftRecord(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): MerchantCommandRecord?

    fun getActiveDraftPayload(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): com.example.data.mobile.contract.StrictJsonObject?

    fun getLatestCompletedCommand(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandType: String
    ): MerchantCommandRecord?

    fun decryptPayload(record: MerchantCommandRecord): ByteArray
    fun decryptSnapshot(record: MerchantCommandRecord): ByteArray?
}

/**
 * Robust AES-256-GCM cipher binding scope and idempotency metadata into AAD.
 * Follows strict outbox envelope: magic (STO\x01) + nonce (12 bytes) + ciphertext+tag.
 */
internal class MerchantCommandCipher internal constructor(private val engine: SessionCryptoEngine) {
    private val magic = byteArrayOf(0x53, 0x54, 0x4f, 0x01)

    private fun aad(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandId: String,
        idempotencyKey: String,
        payloadDigest: String
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            listOf(
                "small_trader:merchant_command:1",
                companyId,
                actorId,
                deviceId,
                commandId,
                idempotencyKey,
                payloadDigest
            ).forEach { field ->
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                stream.writeInt(bytes.size)
                stream.write(bytes)
            }
        }
        return output.toByteArray()
    }

    fun encryptPayload(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandId: String,
        idempotencyKey: String,
        payloadDigest: String,
        plaintext: ByteArray
    ): ByteArray {
        engine.ensureKeyUsable(forExistingRecord = false)
        val authData = aad(companyId, actorId, deviceId, commandId, idempotencyKey, payloadDigest)
        val (nonce, encrypted) = engine.encrypt(plaintext, authData)
        return magic + nonce + encrypted
    }

    fun decryptPayload(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandId: String,
        idempotencyKey: String,
        payloadDigest: String,
        blob: ByteArray
    ): ByteArray {
        if (blob.size < 32 || !blob.copyOfRange(0, 4).contentEquals(magic)) {
            throw SessionStorageException(SessionStorageError.Corrupt())
        }
        engine.ensureKeyUsable(forExistingRecord = true)
        val authData = aad(companyId, actorId, deviceId, commandId, idempotencyKey, payloadDigest)
        val nonce = blob.copyOfRange(4, 16)
        val ciphertext = blob.copyOfRange(16, blob.size)
        return engine.decrypt(nonce, ciphertext, authData)
    }

    fun encryptSnapshot(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandId: String,
        idempotencyKey: String,
        payloadDigest: String,
        plaintext: ByteArray
    ): ByteArray {
        engine.ensureKeyUsable(forExistingRecord = false)
        val authData = aad(companyId, actorId, deviceId, commandId, "$idempotencyKey:snap", payloadDigest)
        val (nonce, encrypted) = engine.encrypt(plaintext, authData)
        return magic + nonce + encrypted
    }

    fun decryptSnapshot(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandId: String,
        idempotencyKey: String,
        payloadDigest: String,
        blob: ByteArray
    ): ByteArray {
        if (blob.size < 32 || !blob.copyOfRange(0, 4).contentEquals(magic)) {
            throw SessionStorageException(SessionStorageError.Corrupt())
        }
        engine.ensureKeyUsable(forExistingRecord = true)
        val authData = aad(companyId, actorId, deviceId, commandId, "$idempotencyKey:snap", payloadDigest)
        val nonce = blob.copyOfRange(4, 16)
        val ciphertext = blob.copyOfRange(16, blob.size)
        return engine.decrypt(nonce, ciphertext, authData)
    }

    companion object {
        fun generateAesKey(): SecretKey {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            return keyGen.generateKey()
        }

        private fun computeAad(
            companyId: String,
            actorId: String,
            deviceId: String,
            commandId: String,
            idempotencyKey: String,
            payloadDigest: String
        ): ByteArray {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { stream ->
                listOf(
                    "small_trader:merchant_command:1",
                    companyId,
                    actorId,
                    deviceId,
                    commandId,
                    idempotencyKey,
                    payloadDigest
                ).forEach { field ->
                    val bytes = field.toByteArray(StandardCharsets.UTF_8)
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                }
            }
            return output.toByteArray()
        }

        fun encrypt(
            plaintext: ByteArray,
            secretKey: SecretKey,
            companyId: String,
            actorId: String,
            deviceId: String,
            commandId: String,
            idempotencyKey: String,
            payloadDigest: String
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            cipher.updateAAD(computeAad(companyId, actorId, deviceId, commandId, idempotencyKey, payloadDigest))
            val ciphertext = cipher.doFinal(plaintext)
            return byteArrayOf(0x53, 0x54, 0x4f, 0x01) + iv + ciphertext
        }

        fun decrypt(
            ciphertextWithIvAndTag: ByteArray,
            secretKey: SecretKey,
            companyId: String,
            actorId: String,
            deviceId: String,
            commandId: String,
            idempotencyKey: String,
            payloadDigest: String
        ): ByteArray {
            val magic = byteArrayOf(0x53, 0x54, 0x4f, 0x01)
            if (ciphertextWithIvAndTag.size < 32 || !ciphertextWithIvAndTag.copyOfRange(0, 4).contentEquals(magic)) {
                throw SessionStorageException(SessionStorageError.Corrupt())
            }
            val iv = ciphertextWithIvAndTag.copyOfRange(4, 16)
            val encrypted = ciphertextWithIvAndTag.copyOfRange(16, ciphertextWithIvAndTag.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.updateAAD(computeAad(companyId, actorId, deviceId, commandId, idempotencyKey, payloadDigest))
            return cipher.doFinal(encrypted)
        }
    }
}

internal fun candidateCommandTypes(formType: String): List<String> {
    return when (formType) {
        "shift_close", "shift.close" -> listOf("shift.close", "shift_close")
        "expense", "expense.create" -> listOf("expense.create", "expense")
        "supplier_payment", "supplier.payment" -> listOf("supplier.payment", "supplier_payment")
        "customer_payment", "customer.payment" -> listOf("customer.payment", "customer_payment")
        "purchase", "purchase.bill" -> listOf("purchase.bill", "purchase")
        "goods_receipt_note", "purchase.grn" -> listOf("purchase.grn", "goods_receipt_note")
        "customer_create", "customer.create" -> listOf("customer_create", "customer.create")
        "supplier_create", "supplier.create" -> listOf("supplier_create", "supplier.create")
        "inventory.adjustment" -> listOf("inventory.adjustment")
        else -> listOf(formType)
    }
}

class RoomMerchantCommandStore internal constructor(
    private val dbProvider: () -> SupportSQLiteDatabase,
    private val cipher: MerchantCommandCipher
) : MerchantCommandStore {

    internal constructor(
        database: OutboxDatabase,
        cryptoEngine: SessionCryptoEngine
    ) : this(
        dbProvider = { database.openHelper.writableDatabase },
        cipher = MerchantCommandCipher(cryptoEngine)
    )

    internal constructor(
        dbProvider: () -> SupportSQLiteDatabase,
        cryptoEngine: SessionCryptoEngine
    ) : this(
        dbProvider = dbProvider,
        cipher = MerchantCommandCipher(cryptoEngine)
    )

    override fun recordPendingCommand(
        companyId: String,
        commandId: String,
        actorId: String,
        deviceId: String,
        commandType: String,
        idempotencyKey: String,
        payloadDigest: String,
        canonicalPayloadBytes: ByteArray
    ): MerchantCommandRecord {
        val db = dbProvider()
        db.beginTransaction()
        try {
            // Find existing by company_id and idempotency_key
            val cursor = db.query(
                "SELECT company_id, command_id, actor_id, device_id, command_type, idempotency_key, " +
                    "payload_digest, payload_bytes, status, response_snapshot, error_message, created_at, updated_at " +
                    "FROM merchant_commands WHERE company_id = ? AND idempotency_key = ?",
                arrayOf(companyId, idempotencyKey)
            )

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val existingActorId = c.getString(2)
                    val existingDeviceId = c.getString(3)
                    val existingDigest = c.getString(6)

                    // Strict cross-scope denial
                    if (existingActorId != actorId || existingDeviceId != deviceId) {
                        throw SecurityException("Cross-actor or cross-device idempotency key replay denied")
                    }

                    // Strict payload equality check
                    if (existingDigest != payloadDigest) {
                        throw IllegalStateException("Idempotency key [$idempotencyKey] was previously committed with different parameters")
                    }

                    val existingStatus = CommandStatus.valueOf(c.getString(8))
                    return MerchantCommandRecord(
                        companyId = c.getString(0),
                        commandId = c.getString(1),
                        actorId = existingActorId,
                        deviceId = existingDeviceId,
                        commandType = c.getString(4),
                        idempotencyKey = c.getString(5),
                        payloadDigest = existingDigest,
                        payloadBytes = c.getBlob(7),
                        status = existingStatus,
                        responseSnapshot = if (c.isNull(9)) null else c.getBlob(9),
                        errorMessage = if (c.isNull(10)) null else c.getString(10),
                        createdAt = c.getLong(11),
                        updatedAt = c.getLong(12)
                    )
                }
            }

            // Encrypt payload before storing - NO PLAINTEXT PII
            val encryptedBytes = cipher.encryptPayload(
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                commandId = commandId,
                idempotencyKey = idempotencyKey,
                payloadDigest = payloadDigest,
                plaintext = canonicalPayloadBytes
            )

            val now = System.currentTimeMillis()
            val stmt = db.compileStatement(
                "INSERT INTO merchant_commands (company_id, command_id, actor_id, device_id, command_type, " +
                    "idempotency_key, payload_digest, payload_bytes, status, response_snapshot, error_message, " +
                    "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)"
            )
            stmt.bindString(1, companyId)
            stmt.bindString(2, commandId)
            stmt.bindString(3, actorId)
            stmt.bindString(4, deviceId)
            stmt.bindString(5, commandType)
            stmt.bindString(6, idempotencyKey)
            stmt.bindString(7, payloadDigest)
            stmt.bindBlob(8, encryptedBytes)
            stmt.bindString(9, CommandStatus.PENDING.name)
            stmt.bindLong(10, now)
            stmt.bindLong(11, now)
            stmt.executeInsert()

            db.setTransactionSuccessful()

            return MerchantCommandRecord(
                companyId = companyId,
                commandId = commandId,
                actorId = actorId,
                deviceId = deviceId,
                commandType = commandType,
                idempotencyKey = idempotencyKey,
                payloadDigest = payloadDigest,
                payloadBytes = encryptedBytes,
                status = CommandStatus.PENDING,
                responseSnapshot = null,
                errorMessage = null,
                createdAt = now,
                updatedAt = now
            )
        } finally {
            db.endTransaction()
        }
    }

    override fun findCommand(
        companyId: String,
        actorId: String,
        deviceId: String,
        idempotencyKey: String
    ): MerchantCommandRecord? {
        val db = dbProvider()
        val cursor = db.query(
            "SELECT company_id, command_id, actor_id, device_id, command_type, idempotency_key, " +
                "payload_digest, payload_bytes, status, response_snapshot, error_message, created_at, updated_at " +
                "FROM merchant_commands WHERE company_id = ? AND actor_id = ? AND device_id = ? AND idempotency_key = ?",
            arrayOf(companyId, actorId, deviceId, idempotencyKey)
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                return MerchantCommandRecord(
                    companyId = c.getString(0),
                    commandId = c.getString(1),
                    actorId = c.getString(2),
                    deviceId = c.getString(3),
                    commandType = c.getString(4),
                    idempotencyKey = c.getString(5),
                    payloadDigest = c.getString(6),
                    payloadBytes = c.getBlob(7),
                    status = CommandStatus.valueOf(c.getString(8)),
                    responseSnapshot = if (c.isNull(9)) null else c.getBlob(9),
                    errorMessage = if (c.isNull(10)) null else c.getString(10),
                    createdAt = c.getLong(11),
                    updatedAt = c.getLong(12)
                )
            }
        }
        return null
    }

    override fun transitionStatus(
        companyId: String,
        commandId: String,
        expectedStatus: CommandStatus,
        newStatus: CommandStatus,
        errorMessage: String?
    ): Boolean {
        if (expectedStatus == newStatus) return false
        if (expectedStatus == CommandStatus.COMPLETED) return false
        val db = dbProvider()
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "UPDATE merchant_commands SET status = ?, error_message = ?, updated_at = ? " +
                "WHERE company_id = ? AND command_id = ? AND status = ?"
        )
        stmt.bindString(1, newStatus.name)
        if (errorMessage != null) {
            stmt.bindString(2, errorMessage)
        } else {
            stmt.bindNull(2)
        }
        stmt.bindLong(3, now)
        stmt.bindString(4, companyId)
        stmt.bindString(5, commandId)
        stmt.bindString(6, expectedStatus.name)
        val affected = stmt.executeUpdateDelete()
        return affected > 0
    }

    override fun markSubmitted(companyId: String, commandId: String): Boolean {
        val db = dbProvider()
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "UPDATE merchant_commands SET status = ?, updated_at = ? " +
                "WHERE company_id = ? AND command_id = ? AND status IN ('PENDING', 'UNKNOWN_ACK')"
        )
        stmt.bindString(1, CommandStatus.SUBMITTED.name)
        stmt.bindLong(2, now)
        stmt.bindString(3, companyId)
        stmt.bindString(4, commandId)
        return stmt.executeUpdateDelete() > 0
    }

    override fun markSubmitted(commandId: String): Boolean {
        val db = dbProvider()
        val cursor = db.query("SELECT company_id FROM merchant_commands WHERE command_id = ? LIMIT 1", arrayOf(commandId))
        val companyId = cursor.use { if (it.moveToFirst()) it.getString(0) else return false }
        return markSubmitted(companyId, commandId)
    }

    override fun claimSubmittedCommand(companyId: String, commandId: String, expectedUpdatedAt: Long): Boolean {
        val db = dbProvider()
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "UPDATE merchant_commands SET updated_at = ? " +
                "WHERE company_id = ? AND command_id = ? AND status = 'SUBMITTED' AND updated_at = ?"
        )
        stmt.bindLong(1, now)
        stmt.bindString(2, companyId)
        stmt.bindString(3, commandId)
        stmt.bindLong(4, expectedUpdatedAt)
        return stmt.executeUpdateDelete() > 0
    }

    override fun markCompleted(
        companyId: String,
        commandId: String,
        responseSnapshotBytes: ByteArray
    ): Boolean {
        val db = dbProvider()
        // Read record identity to compute exact AAD for snapshot encryption
        val cursor = db.query(
            "SELECT actor_id, device_id, idempotency_key, payload_digest, status FROM merchant_commands " +
                "WHERE company_id = ? AND command_id = ?",
            arrayOf(companyId, commandId)
        )
        val (actorId, deviceId, idempotencyKey, payloadDigest, currentStatus) = cursor.use { c ->
            if (c.moveToFirst()) {
                listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            } else {
                return false
            }
        }

        if (currentStatus == CommandStatus.COMPLETED.name) {
            return true
        }
        if (currentStatus != CommandStatus.SUBMITTED.name && currentStatus != CommandStatus.UNKNOWN_ACK.name) {
            return false
        }

        val encryptedSnapshot = cipher.encryptSnapshot(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            payloadDigest = payloadDigest,
            plaintext = responseSnapshotBytes
        )

        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "UPDATE merchant_commands SET status = ?, response_snapshot = ?, error_message = NULL, updated_at = ? " +
                "WHERE company_id = ? AND command_id = ? AND status IN ('SUBMITTED', 'UNKNOWN_ACK')"
        )
        stmt.bindString(1, CommandStatus.COMPLETED.name)
        stmt.bindBlob(2, encryptedSnapshot)
        stmt.bindLong(3, now)
        stmt.bindString(4, companyId)
        stmt.bindString(5, commandId)
        return stmt.executeUpdateDelete() > 0
    }

    override fun markCompleted(commandId: String, responseSnapshotBytes: ByteArray): Boolean {
        val db = dbProvider()
        val cursor = db.query("SELECT company_id FROM merchant_commands WHERE command_id = ? LIMIT 1", arrayOf(commandId))
        val companyId = cursor.use { if (it.moveToFirst()) it.getString(0) else return false }
        return markCompleted(companyId, commandId, responseSnapshotBytes)
    }

    override fun markUnknownAck(companyId: String, commandId: String, error: String): Boolean {
        val db = dbProvider()
        val cursor = db.query("SELECT status FROM merchant_commands WHERE company_id = ? AND command_id = ?", arrayOf(companyId, commandId))
        val currentStatus = cursor.use { if (it.moveToFirst()) it.getString(0) else return false }
        if (currentStatus == CommandStatus.COMPLETED.name) return false
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "UPDATE merchant_commands SET status = ?, error_message = ?, updated_at = ? " +
                "WHERE company_id = ? AND command_id = ? AND status IN ('SUBMITTED', 'PENDING')"
        )
        stmt.bindString(1, CommandStatus.UNKNOWN_ACK.name)
        stmt.bindString(2, error)
        stmt.bindLong(3, now)
        stmt.bindString(4, companyId)
        stmt.bindString(5, commandId)
        return stmt.executeUpdateDelete() > 0
    }

    override fun markUnknownAck(commandId: String, error: String): Boolean {
        val db = dbProvider()
        val cursor = db.query("SELECT company_id FROM merchant_commands WHERE command_id = ? LIMIT 1", arrayOf(commandId))
        val companyId = cursor.use { if (it.moveToFirst()) it.getString(0) else return false }
        return markUnknownAck(companyId, commandId, error)
    }

    override fun markFailed(companyId: String, commandId: String, error: String): Boolean {
        val db = dbProvider()
        val cursor = db.query("SELECT status FROM merchant_commands WHERE company_id = ? AND command_id = ?", arrayOf(companyId, commandId))
        val currentStatus = cursor.use { if (it.moveToFirst()) it.getString(0) else return false }
        if (currentStatus == CommandStatus.COMPLETED.name) return false
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "UPDATE merchant_commands SET status = ?, error_message = ?, updated_at = ? " +
                "WHERE company_id = ? AND command_id = ? AND status != 'COMPLETED'"
        )
        stmt.bindString(1, CommandStatus.FAILED.name)
        stmt.bindString(2, error)
        stmt.bindLong(3, now)
        stmt.bindString(4, companyId)
        stmt.bindString(5, commandId)
        return stmt.executeUpdateDelete() > 0
    }

    override fun markFailed(commandId: String, error: String): Boolean {
        val db = dbProvider()
        val cursor = db.query("SELECT company_id FROM merchant_commands WHERE command_id = ? LIMIT 1", arrayOf(commandId))
        val companyId = cursor.use { if (it.moveToFirst()) it.getString(0) else return false }
        return markFailed(companyId, commandId, error)
    }

    override fun getDraftOperationId(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): String {
        val db = dbProvider()
        // 1. Check if an active uncompleted command exists for this form type
        val cmdTypes = candidateCommandTypes(formType)
        val placeholders = cmdTypes.joinToString(",") { "?" }
        val queryArgs = arrayOf(companyId, actorId, deviceId) + cmdTypes.toTypedArray()
        val c = db.query(
            "SELECT command_id FROM merchant_commands " +
                "WHERE company_id = ? AND actor_id = ? AND device_id = ? AND command_type IN ($placeholders) " +
                "AND status IN ('PENDING', 'SUBMITTED', 'UNKNOWN_ACK') " +
                "ORDER BY updated_at DESC LIMIT 1",
            queryArgs
        )
        c.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }

        // 2. Check if a draft intent is saved in merchant_form_intents
        val cDraft = db.query(
            "SELECT operation_id FROM merchant_form_intents " +
                "WHERE company_id = ? AND actor_id = ? AND device_id = ? AND form_type = ?",
            arrayOf(companyId, actorId, deviceId, formType)
        )
        cDraft.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }

        // 3. Generate a fresh UUID and save it durably
        val newOpId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO merchant_form_intents " +
                "(company_id, actor_id, device_id, form_type, operation_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.bindString(1, companyId)
        stmt.bindString(2, actorId)
        stmt.bindString(3, deviceId)
        stmt.bindString(4, formType)
        stmt.bindString(5, newOpId)
        stmt.bindLong(6, now)
        stmt.executeInsert()
        return newOpId
    }

    override fun rotateDraftOperationId(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): String {
        val db = dbProvider()
        val newOpId = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO merchant_form_intents " +
                "(company_id, actor_id, device_id, form_type, operation_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt.bindString(1, companyId)
        stmt.bindString(2, actorId)
        stmt.bindString(3, deviceId)
        stmt.bindString(4, formType)
        stmt.bindString(5, newOpId)
        stmt.bindLong(6, now)
        stmt.executeInsert()
        return newOpId
    }

    override fun cancelDraftOperation(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ) {
        rotateDraftOperationId(companyId, actorId, deviceId, formType)
    }

    override fun getActiveDraftRecord(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): MerchantCommandRecord? {
        val db = dbProvider()
        val cmdTypes = candidateCommandTypes(formType)
        val placeholders = cmdTypes.joinToString(",") { "?" }
        val queryArgs = arrayOf(companyId, actorId, deviceId) + cmdTypes.toTypedArray()
        val c = db.query(
            "SELECT company_id, command_id, actor_id, device_id, command_type, idempotency_key, " +
                "payload_digest, payload_bytes, status, response_snapshot, error_message, created_at, updated_at " +
                "FROM merchant_commands WHERE company_id = ? AND actor_id = ? AND device_id = ? AND command_type IN ($placeholders) " +
                "AND status IN ('PENDING', 'SUBMITTED', 'UNKNOWN_ACK') ORDER BY updated_at DESC LIMIT 1",
            queryArgs
        )
        return c.use {
            if (it.moveToFirst()) {
                MerchantCommandRecord(
                    companyId = it.getString(0),
                    commandId = it.getString(1),
                    actorId = it.getString(2),
                    deviceId = it.getString(3),
                    commandType = it.getString(4),
                    idempotencyKey = it.getString(5),
                    payloadDigest = it.getString(6),
                    payloadBytes = it.getBlob(7),
                    status = CommandStatus.valueOf(it.getString(8)),
                    responseSnapshot = if (it.isNull(9)) null else it.getBlob(9),
                    errorMessage = if (it.isNull(10)) null else it.getString(10),
                    createdAt = it.getLong(11),
                    updatedAt = it.getLong(12)
                )
            } else null
        }
    }

    override fun getActiveDraftPayload(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): com.example.data.mobile.contract.StrictJsonObject? {
        val record = getActiveDraftRecord(companyId, actorId, deviceId, formType) ?: return null
        return try {
            val decrypted = decryptPayload(record)
            val jsonStr = String(decrypted, StandardCharsets.UTF_8)
            com.example.data.mobile.contract.StrictJsonParser.parse(jsonStr).asObject()
        } catch (_: Exception) {
            null
        }
    }

    override fun getLatestCompletedCommand(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandType: String
    ): MerchantCommandRecord? {
        val db = dbProvider()
        val cmdTypes = candidateCommandTypes(commandType)
        val placeholders = cmdTypes.joinToString(",") { "?" }
        val queryArgs = arrayOf(companyId, actorId, deviceId) + cmdTypes.toTypedArray()
        val c = db.query(
            "SELECT company_id, command_id, actor_id, device_id, command_type, idempotency_key, " +
                "payload_digest, payload_bytes, status, response_snapshot, error_message, created_at, updated_at " +
                "FROM merchant_commands WHERE company_id = ? AND actor_id = ? AND device_id = ? AND command_type IN ($placeholders) " +
                "AND status = 'COMPLETED' AND response_snapshot IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
            queryArgs
        )
        return c.use {
            if (it.moveToFirst()) {
                MerchantCommandRecord(
                    companyId = it.getString(0),
                    commandId = it.getString(1),
                    actorId = it.getString(2),
                    deviceId = it.getString(3),
                    commandType = it.getString(4),
                    idempotencyKey = it.getString(5),
                    payloadDigest = it.getString(6),
                    payloadBytes = it.getBlob(7),
                    status = CommandStatus.valueOf(it.getString(8)),
                    responseSnapshot = if (it.isNull(9)) null else it.getBlob(9),
                    errorMessage = if (it.isNull(10)) null else it.getString(10),
                    createdAt = it.getLong(11),
                    updatedAt = it.getLong(12)
                )
            } else null
        }
    }

    override fun decryptPayload(record: MerchantCommandRecord): ByteArray {
        return cipher.decryptPayload(
            companyId = record.companyId,
            actorId = record.actorId,
            deviceId = record.deviceId,
            commandId = record.commandId,
            idempotencyKey = record.idempotencyKey,
            payloadDigest = record.payloadDigest,
            blob = record.payloadBytes
        )
    }

    override fun decryptSnapshot(record: MerchantCommandRecord): ByteArray? {
        val snapBlob = record.responseSnapshot ?: return null
        return cipher.decryptSnapshot(
            companyId = record.companyId,
            actorId = record.actorId,
            deviceId = record.deviceId,
            commandId = record.commandId,
            idempotencyKey = record.idempotencyKey,
            payloadDigest = record.payloadDigest,
            blob = snapBlob
        )
    }
}

/**
 * Thread-safe memory store for unit testing.
 * Enforces the identical scoping, payload digest equality, and CAS transitions.
 */
class InMemoryMerchantCommandStore : MerchantCommandStore {
    private val records = ConcurrentHashMap<String, MerchantCommandRecord>()
    private val draftIntents = ConcurrentHashMap<String, String>()
    private val lock = Any()

    override fun recordPendingCommand(
        companyId: String,
        commandId: String,
        actorId: String,
        deviceId: String,
        commandType: String,
        idempotencyKey: String,
        payloadDigest: String,
        canonicalPayloadBytes: ByteArray
    ): MerchantCommandRecord = synchronized(lock) {
        val key = "$companyId:$idempotencyKey"
        val existing = records[key]
        if (existing != null) {
            if (existing.actorId != actorId || existing.deviceId != deviceId) {
                throw SecurityException("Cross-actor or cross-device idempotency key replay denied")
            }
            if (existing.payloadDigest != payloadDigest) {
                throw IllegalStateException("Idempotency key [$idempotencyKey] was previously committed with different parameters")
            }
            return existing
        }
        val now = System.currentTimeMillis()
        val record = MerchantCommandRecord(
            companyId = companyId,
            commandId = commandId,
            actorId = actorId,
            deviceId = deviceId,
            commandType = commandType,
            idempotencyKey = idempotencyKey,
            payloadDigest = payloadDigest,
            payloadBytes = canonicalPayloadBytes,
            status = CommandStatus.PENDING,
            responseSnapshot = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now
        )
        records[key] = record
        records["id:$companyId:$commandId"] = record
        return record
    }

    override fun findCommand(
        companyId: String,
        actorId: String,
        deviceId: String,
        idempotencyKey: String
    ): MerchantCommandRecord? = synchronized(lock) {
        val record = records["$companyId:$idempotencyKey"] ?: return null
        if (record.actorId != actorId || record.deviceId != deviceId) {
            throw SecurityException("Cross-actor or cross-device lookup denied")
        }
        return record
    }

    override fun transitionStatus(
        companyId: String,
        commandId: String,
        expectedStatus: CommandStatus,
        newStatus: CommandStatus,
        errorMessage: String?
    ): Boolean = synchronized(lock) {
        if (expectedStatus == newStatus) return false
        if (expectedStatus == CommandStatus.COMPLETED) return false
        val idKey = "id:$companyId:$commandId"
        val existing = records[idKey] ?: return false
        if (existing.status != expectedStatus) return false
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = newStatus,
            errorMessage = errorMessage,
            updatedAt = now
        )
        records[idKey] = updated
        records["$companyId:${existing.idempotencyKey}"] = updated
        return true
    }

    override fun markSubmitted(companyId: String, commandId: String): Boolean = synchronized(lock) {
        val idKey = "id:$companyId:$commandId"
        val existing = records[idKey] ?: return false
        if (existing.status != CommandStatus.PENDING && existing.status != CommandStatus.UNKNOWN_ACK) {
            return false
        }
        val now = System.currentTimeMillis()
        val updated = existing.copy(status = CommandStatus.SUBMITTED, updatedAt = now)
        records[idKey] = updated
        records["$companyId:${existing.idempotencyKey}"] = updated
        return true
    }

    override fun markSubmitted(commandId: String): Boolean = synchronized(lock) {
        val existing = records.values.firstOrNull { it.commandId == commandId } ?: return false
        return markSubmitted(existing.companyId, commandId)
    }

    override fun claimSubmittedCommand(companyId: String, commandId: String, expectedUpdatedAt: Long): Boolean = synchronized(lock) {
        val idKey = "id:$companyId:$commandId"
        val existing = records[idKey] ?: return false
        if (existing.status != CommandStatus.SUBMITTED || existing.updatedAt != expectedUpdatedAt) {
            return false
        }
        val now = System.currentTimeMillis()
        val updated = existing.copy(updatedAt = now)
        records[idKey] = updated
        records["$companyId:${existing.idempotencyKey}"] = updated
        return true
    }

    override fun markCompleted(
        companyId: String,
        commandId: String,
        responseSnapshotBytes: ByteArray
    ): Boolean = synchronized(lock) {
        val idKey = "id:$companyId:$commandId"
        val existing = records[idKey] ?: return false
        if (existing.status == CommandStatus.COMPLETED) return true
        if (existing.status != CommandStatus.SUBMITTED && existing.status != CommandStatus.UNKNOWN_ACK) return false
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = CommandStatus.COMPLETED,
            responseSnapshot = responseSnapshotBytes,
            errorMessage = null,
            updatedAt = now
        )
        records[idKey] = updated
        records["$companyId:${existing.idempotencyKey}"] = updated
        return true
    }

    override fun markCompleted(commandId: String, responseSnapshotBytes: ByteArray): Boolean = synchronized(lock) {
        val existing = records.values.firstOrNull { it.commandId == commandId } ?: return false
        return markCompleted(existing.companyId, commandId, responseSnapshotBytes)
    }

    override fun markUnknownAck(companyId: String, commandId: String, error: String): Boolean = synchronized(lock) {
        val idKey = "id:$companyId:$commandId"
        val existing = records[idKey] ?: return false
        if (existing.status == CommandStatus.COMPLETED) return false
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = CommandStatus.UNKNOWN_ACK,
            errorMessage = error,
            updatedAt = now
        )
        records[idKey] = updated
        records["$companyId:${existing.idempotencyKey}"] = updated
        return true
    }

    override fun markUnknownAck(commandId: String, error: String): Boolean = synchronized(lock) {
        val existing = records.values.firstOrNull { it.commandId == commandId } ?: return false
        return markUnknownAck(existing.companyId, commandId, error)
    }

    override fun markFailed(companyId: String, commandId: String, error: String): Boolean = synchronized(lock) {
        val idKey = "id:$companyId:$commandId"
        val existing = records[idKey] ?: return false
        if (existing.status == CommandStatus.COMPLETED) return false
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = CommandStatus.FAILED,
            errorMessage = error,
            updatedAt = now
        )
        records[idKey] = updated
        records["$companyId:${existing.idempotencyKey}"] = updated
        return true
    }

    override fun markFailed(commandId: String, error: String): Boolean = synchronized(lock) {
        val existing = records.values.firstOrNull { it.commandId == commandId } ?: return false
        return markFailed(existing.companyId, commandId, error)
    }

    override fun getDraftOperationId(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): String = synchronized(lock) {
        val cmdTypes = candidateCommandTypes(formType)
        val active = records.values.firstOrNull {
            it.companyId == companyId && it.actorId == actorId && it.deviceId == deviceId &&
                it.commandType in cmdTypes &&
                (it.status == CommandStatus.PENDING || it.status == CommandStatus.SUBMITTED || it.status == CommandStatus.UNKNOWN_ACK)
        }
        if (active != null) return active.commandId

        val key = "$companyId:$actorId:$deviceId:$formType"
        return draftIntents.computeIfAbsent(key) { java.util.UUID.randomUUID().toString() }
    }

    override fun rotateDraftOperationId(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): String = synchronized(lock) {
        val key = "$companyId:$actorId:$deviceId:$formType"
        val next = java.util.UUID.randomUUID().toString()
        draftIntents[key] = next
        return next
    }

    override fun cancelDraftOperation(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ) = synchronized(lock) {
        rotateDraftOperationId(companyId, actorId, deviceId, formType)
        Unit
    }

    override fun getActiveDraftRecord(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): MerchantCommandRecord? = synchronized(lock) {
        val cmdTypes = candidateCommandTypes(formType)
        return records.values.firstOrNull {
            it.companyId == companyId && it.actorId == actorId && it.deviceId == deviceId &&
                it.commandType in cmdTypes &&
                (it.status == CommandStatus.PENDING || it.status == CommandStatus.SUBMITTED || it.status == CommandStatus.UNKNOWN_ACK)
        }
    }

    override fun getActiveDraftPayload(
        companyId: String,
        actorId: String,
        deviceId: String,
        formType: String
    ): com.example.data.mobile.contract.StrictJsonObject? = synchronized(lock) {
        val record = getActiveDraftRecord(companyId, actorId, deviceId, formType) ?: return null
        return try {
            val decrypted = decryptPayload(record)
            val jsonStr = String(decrypted, StandardCharsets.UTF_8)
            com.example.data.mobile.contract.StrictJsonParser.parse(jsonStr).asObject()
        } catch (_: Exception) {
            null
        }
    }

    override fun getLatestCompletedCommand(
        companyId: String,
        actorId: String,
        deviceId: String,
        commandType: String
    ): MerchantCommandRecord? = synchronized(lock) {
        val cmdTypes = candidateCommandTypes(commandType)
        return records.values
            .filter {
                it.companyId == companyId && it.actorId == actorId && it.deviceId == deviceId &&
                    it.commandType in cmdTypes && it.status == CommandStatus.COMPLETED &&
                    it.responseSnapshot != null
            }
            .maxByOrNull { it.updatedAt }
    }

    override fun decryptPayload(record: MerchantCommandRecord): ByteArray = record.payloadBytes

    override fun decryptSnapshot(record: MerchantCommandRecord): ByteArray? = record.responseSnapshot
}
