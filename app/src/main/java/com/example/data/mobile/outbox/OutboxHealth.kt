package com.example.data.mobile.outbox

import android.os.Looper
import com.example.data.mobile.contract.ContractConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * High-level cryptographic and structural health state of the local outbox for an active session scope.
 */
enum class OutboxHealthStatus {
    /** Encrypted outbox is functional, accessible, and all scoped records verified. */
    HEALTHY,

    /** Database is empty for this scope (no offline events). Ready for encrypted captures; no key generation needed. */
    NEW_EMPTY,

    /** Records are encrypted, but Keystore key is locked or permanently invalidated. */
    ENCRYPTED_LOCKED,

    /** Records are encrypted, but Keystore key is missing or unavailable on this device. */
    KEY_UNAVAILABLE,

    /** Outbox records or envelope headers are corrupted, truncated, oversized, or fail MAC authentication. */
    CORRUPT,

    /** Pre-existing unencrypted Room v1 data detected. Requires owner-reviewed recovery before upgrade. */
    LEGACY_PLAINTEXT,

    /** Queue contains a conflicting mix of plaintext and encrypted records. */
    MIXED_QUEUE,

    /** Queue contains unverified offline records beyond probe limit. Readiness cannot be granted from sample. */
    PARTIAL_UNCHECKED,

    /** Local database or storage could not be accessed. */
    STORAGE_UNAVAILABLE
}

/**
 * Result of a bounded outbox health inspection for an active tenant/actor/device scope.
 *
 * Privacy Invariants:
 * - NEVER leaks foreign company IDs or tenant names.
 * - NEVER leaks clientEventId, UUIDs, or payload byte content.
 * - NEVER leaks foreign record counts.
 */
data class OutboxHealthResult(
    val status: OutboxHealthStatus,
    val totalEvents: Int,
    val probedCount: Int,
    val isSampleProbe: Boolean,
    val messageEn: String,
    val messageAr: String,
    val detailMessageEn: String? = null,
    val detailMessageAr: String? = null,
    val canRetry: Boolean = false
) {
    val isOperational: Boolean
        get() = (status == OutboxHealthStatus.HEALTHY && !isSampleProbe) || status == OutboxHealthStatus.NEW_EMPTY

    val isLegacyBlocked: Boolean
        get() = status == OutboxHealthStatus.LEGACY_PLAINTEXT

    val hasPreservedData: Boolean
        get() = totalEvents > 0

    override fun toString(): String =
        "OutboxHealthResult(status=$status, isOperational=$isOperational, canRetry=$canRetry)"
}

interface OutboxHealthInspector {
    suspend fun inspectHealth(
        companyId: String,
        actorId: String,
        deviceId: String,
        maxProbeCount: Int = DEFAULT_PROBE_LIMIT
    ): OutboxHealthResult

    companion object {
        const val DEFAULT_PROBE_LIMIT = 10
        const val MAX_PROBE_LIMIT = 10
    }
}

class UnavailableOutboxHealthInspector(
    private val failureReasonEn: String = "Outbox health inspector service is unavailable.",
    private val failureReasonAr: String = "خدمة فحص طابور الترحيل غير متوفرة."
) : OutboxHealthInspector {
    override suspend fun inspectHealth(
        companyId: String,
        actorId: String,
        deviceId: String,
        maxProbeCount: Int
    ): OutboxHealthResult = OutboxHealthResult(
        status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
        totalEvents = 0,
        probedCount = 0,
        isSampleProbe = false,
        messageEn = failureReasonEn,
        messageAr = failureReasonAr,
        detailMessageEn = "Contact support",
        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
        canRetry = true
    )
}

class DefaultOutboxHealthInspector(
    private val database: OutboxDatabase,
    private val payloadCipher: OutboxPayloadCipher = OutboxPayloadCipher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : OutboxHealthInspector {

    override suspend fun inspectHealth(
        companyId: String,
        actorId: String,
        deviceId: String,
        maxProbeCount: Int
    ): OutboxHealthResult = withContext(ioDispatcher) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Outbox health inspection must run off the main thread"
        }

        currentCoroutineContext().ensureActive()

        // Privacy and isolation: scope cannot be blank. Unauthorized/blank scopes fail closed.
        if (companyId.isBlank() || actorId.isBlank() || deviceId.isBlank()) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                messageEn = "Scope identifier cannot be blank for outbox health inspection.",
                messageAr = "لا يمكن أن تكون معرّفات النطاق فارغة لفحص حالة طابور الترحيل.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = false
            )
        }

        val scopedTotal = try {
            database.outboxDao().scopedEventCount(companyId, actorId, deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                messageEn = "Failed to inspect local outbox database storage.",
                messageAr = "تعذر فحص وحدة تخزين السجلات المحلية.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        if (scopedTotal == 0) {
            // New empty queue for this scope: no records exist, zero key generation needed
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.NEW_EMPTY,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                messageEn = "Encrypted offline queue is ready.",
                messageAr = "طابور الترحيل المشفر جاهز للاستخدام.",
                canRetry = false
            )
        }

        val sampleLimit = if (maxProbeCount <= 0 || maxProbeCount > OutboxHealthInspector.MAX_PROBE_LIMIT) {
            OutboxHealthInspector.DEFAULT_PROBE_LIMIT
        } else {
            maxProbeCount
        }

        val maxEnvelopeBytes = ContractConstants.MAX_PAYLOAD_BYTES + 32
        val maxEnvelopeBytesPlusOne = maxEnvelopeBytes + 1

        val probeRows = try {
            database.outboxDao().scopedSampleEventsForHealthProbe(
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                maxEnvelopeBytesPlusOne = maxEnvelopeBytesPlusOne,
                limit = sampleLimit
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                totalEvents = scopedTotal,
                probedCount = 0,
                isSampleProbe = scopedTotal > 0,
                messageEn = "Storage records could not be read. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تعذر قراءة سجلات التخزين. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        val probedCount = probeRows.size
        val isSampleProbe = scopedTotal > probedCount

        var encryptedCount = 0
        var plaintextCount = 0
        var corruptEnvelopeCount = 0
        var oversizedCount = 0

        for (row in probeRows) {
            currentCoroutineContext().ensureActive()
            if (row.payloadLength > maxEnvelopeBytes) {
                oversizedCount++
                continue
            }
            val bytes = row.boundedPayloadBytes
            if (payloadCipher.isEncrypted(bytes)) {
                encryptedCount++
            } else if (payloadCipher.hasStoEnvelope(bytes)) {
                // Future envelope (e.g. STO/2+) or truncated/corrupt envelope
                corruptEnvelopeCount++
            } else {
                plaintextCount++
            }
        }

        if (oversizedCount > 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = scopedTotal,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Storage records exceeded maximum allowed envelope size. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تجاوزت بعض السجلات الحد الأقصى لحجم التخزين. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        if (corruptEnvelopeCount > 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = scopedTotal,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        // Format conflict check: mixed encrypted and plaintext
        if (encryptedCount > 0 && plaintextCount > 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.MIXED_QUEUE,
                totalEvents = scopedTotal,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Outbox storage contains conflicting record formats. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        // If sample probe was taken and all probed rows are encrypted, check scoped non-encrypted aggregate
        if (isSampleProbe && encryptedCount > 0) {
            val nonEncryptedCount = try {
                database.outboxDao().scopedNonEncryptedEventCount(companyId, actorId, deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            if (nonEncryptedCount > 0) {
                return@withContext OutboxHealthResult(
                    status = OutboxHealthStatus.MIXED_QUEUE,
                    totalEvents = scopedTotal,
                    probedCount = probedCount,
                    isSampleProbe = true,
                    messageEn = "Outbox storage contains conflicting record formats. We have not changed stored sales. Some records need help before syncing.",
                    messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                    detailMessageEn = "Contact support",
                    detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                    canRetry = true
                )
            }
        }

        if (plaintextCount > 0 && encryptedCount == 0) {
            // Validate EVERY legacy plaintext record in the probed rows
            var legacyValid = true
            for (row in probeRows) {
                currentCoroutineContext().ensureActive()
                val eventEntity = OutboxEventEntity(
                    clientEventId = row.clientEventId,
                    companyId = row.companyId,
                    actorId = row.actorId,
                    deviceId = row.deviceId,
                    deviceLeaseId = row.deviceLeaseId,
                    capturedAt = row.capturedAt,
                    payloadDigest = row.payloadDigest,
                    payloadBytes = row.boundedPayloadBytes,
                    eventVersion = row.eventVersion
                )
                try {
                    payloadCipher.verifyLegacy(eventEntity)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    legacyValid = false
                    break
                }
            }

            if (!legacyValid) {
                return@withContext OutboxHealthResult(
                    status = OutboxHealthStatus.CORRUPT,
                    totalEvents = scopedTotal,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "Pre-existing offline records failed integrity validation. We have not changed stored sales. Some records need help before syncing.",
                    messageAr = "فشل التحقق من سلامة السجلات السابقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                    detailMessageEn = "Contact support",
                    detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                    canRetry = true
                )
            }

            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.LEGACY_PLAINTEXT,
                totalEvents = scopedTotal,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Pre-existing unencrypted sales records detected. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تم اكتشاف سجلات مبيعات سابقة غير مشفرة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        // All probed records are encrypted: authenticate key and ciphertext on EVERY returned row!
        for (row in probeRows) {
            currentCoroutineContext().ensureActive()
            val eventEntity = OutboxEventEntity(
                clientEventId = row.clientEventId,
                companyId = row.companyId,
                actorId = row.actorId,
                deviceId = row.deviceId,
                deviceLeaseId = row.deviceLeaseId,
                capturedAt = row.capturedAt,
                payloadDigest = row.payloadDigest,
                payloadBytes = row.boundedPayloadBytes,
                eventVersion = row.eventVersion
            )
            try {
                payloadCipher.decrypt(eventEntity)
            } catch (e: OutboxStorageException) {
                return@withContext when (e.error) {
                    OutboxStorageError.KEY_LOCKED -> OutboxHealthResult(
                        status = OutboxHealthStatus.ENCRYPTED_LOCKED,
                        totalEvents = scopedTotal,
                        probedCount = probedCount,
                        isSampleProbe = isSampleProbe,
                        messageEn = "The secure encryption key is locked or invalidated. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "مفتاح التشفير الآمن مقفل أو تم إبطاله. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                    OutboxStorageError.KEY_UNAVAILABLE -> OutboxHealthResult(
                        status = OutboxHealthStatus.KEY_UNAVAILABLE,
                        totalEvents = scopedTotal,
                        probedCount = probedCount,
                        isSampleProbe = isSampleProbe,
                        messageEn = "The secure encryption key is unavailable on this device. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                    OutboxStorageError.CORRUPT, OutboxStorageError.OVERSIZED -> OutboxHealthResult(
                        status = OutboxHealthStatus.CORRUPT,
                        totalEvents = scopedTotal,
                        probedCount = probedCount,
                        isSampleProbe = isSampleProbe,
                        messageEn = "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                    OutboxStorageError.LEGACY_CONVERSION_REQUIRED -> OutboxHealthResult(
                        status = OutboxHealthStatus.MIXED_QUEUE,
                        totalEvents = scopedTotal,
                        probedCount = probedCount,
                        isSampleProbe = isSampleProbe,
                        messageEn = "Outbox storage contains conflicting record formats. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext OutboxHealthResult(
                    status = OutboxHealthStatus.CORRUPT,
                    totalEvents = scopedTotal,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing.",
                    messageAr = "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                    detailMessageEn = "Contact support",
                    detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                    canRetry = true
                )
            }
        }

        // Encryption and decryption probe succeeded for all probed records!
        if (isSampleProbe) {
            // A sample of a larger queue CANNOT grant queue processing readiness!
            OutboxHealthResult(
                status = OutboxHealthStatus.PARTIAL_UNCHECKED,
                totalEvents = scopedTotal,
                probedCount = probedCount,
                isSampleProbe = true,
                messageEn = "Queue contains unverified offline records beyond probe limit. Queue processing cannot be granted from a partial sample.",
                messageAr = "يحتوي طابور الترحيل على سجلات غير مفحوصة تتجاوز حد الفحص. لا يمكن منح صلاحية المعالجة بناءً على عينة جزئية.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        } else {
            // Entire scoped queue was probed and authenticated
            OutboxHealthResult(
                status = OutboxHealthStatus.HEALTHY,
                totalEvents = scopedTotal,
                probedCount = probedCount,
                isSampleProbe = false,
                messageEn = "Encrypted offline queue is healthy.",
                messageAr = "طابور الترحيل المشفر يعمل بشكل سليم.",
                canRetry = false
            )
        }
    }
}
