package com.example.data.mobile.outbox

import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * High-level cryptographic and structural health state of the local outbox.
 */
enum class OutboxHealthStatus {
    /** Encrypted outbox is functional, accessible, and verified. */
    HEALTHY,

    /** Database is empty (no offline events). Ready for encrypted captures; no key generation needed. */
    NEW_EMPTY,

    /** Records are encrypted, but Keystore key is locked or permanently invalidated. */
    ENCRYPTED_LOCKED,

    /** Records are encrypted, but Keystore key is missing or unavailable on this device. */
    KEY_UNAVAILABLE,

    /** Outbox records or envelope headers are corrupted, truncated, or fail MAC authentication. */
    CORRUPT,

    /** Pre-existing unencrypted Room v1 data detected. Requires owner-reviewed recovery before upgrade. */
    LEGACY_PLAINTEXT,

    /** Queue contains a conflicting mix of plaintext and encrypted records. */
    MIXED_QUEUE
}

/**
 * Result of a bounded outbox health inspection.
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
        get() = status == OutboxHealthStatus.HEALTHY || status == OutboxHealthStatus.NEW_EMPTY

    val isLegacyBlocked: Boolean
        get() = status == OutboxHealthStatus.LEGACY_PLAINTEXT

    val hasPreservedData: Boolean
        get() = totalEvents > 0

    override fun toString(): String =
        "OutboxHealthResult(status=$status, totalEvents=$totalEvents, probedCount=$probedCount, isSampleProbe=$isSampleProbe)"
}

interface OutboxHealthInspector {
    suspend fun inspectHealth(maxProbeCount: Int = DEFAULT_PROBE_LIMIT): OutboxHealthResult

    companion object {
        const val DEFAULT_PROBE_LIMIT = 50
    }
}

class DefaultOutboxHealthInspector(
    private val database: OutboxDatabase,
    private val payloadCipher: OutboxPayloadCipher = OutboxPayloadCipher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : OutboxHealthInspector {

    override suspend fun inspectHealth(maxProbeCount: Int): OutboxHealthResult = withContext(ioDispatcher) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Outbox health inspection must run off the main thread"
        }

        currentCoroutineContext().ensureActive()

        val totalEvents = try {
            database.outboxDao().eventCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                messageEn = "Failed to inspect local outbox database storage.",
                messageAr = "تعذر فحص وحدة تخزين السجلات المحلية.",
                detailMessageEn = "Database read error occurred.",
                detailMessageAr = "حدث خطأ أثناء قراءة قاعدة البيانات.",
                canRetry = true
            )
        }

        if (totalEvents == 0) {
            // New empty queue: no records exist, zero key generation needed
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

        val sampleLimit = if (maxProbeCount <= 0) OutboxHealthInspector.DEFAULT_PROBE_LIMIT else maxProbeCount
        val sampleEvents = try {
            database.outboxDao().sampleEventsForHealthProbe(sampleLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = totalEvents,
                probedCount = 0,
                isSampleProbe = totalEvents > 0,
                messageEn = "Storage records could not be read. Unsynced offline sales records are preserved safely on this device.",
                messageAr = "تعذر قراءة سجلات التخزين. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                detailMessageEn = "Database probe failed.",
                detailMessageAr = "فشل فحص قاعدة البيانات.",
                canRetry = true
            )
        }

        val probedCount = sampleEvents.size
        val isSampleProbe = totalEvents > probedCount

        var encryptedCount = 0
        var plaintextCount = 0
        var corruptEnvelopeCount = 0

        for (event in sampleEvents) {
            currentCoroutineContext().ensureActive()
            val bytes = event.payloadBytes
            if (payloadCipher.isEncrypted(bytes)) {
                encryptedCount++
            } else if (payloadCipher.hasStoEnvelope(bytes)) {
                // Future envelope (e.g. STO/2+) or truncated envelope
                corruptEnvelopeCount++
            } else {
                plaintextCount++
            }
        }

        if (corruptEnvelopeCount > 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = totalEvents,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Storage records could not be cryptographically authenticated. Unsynced offline sales records are preserved safely on this device.",
                messageAr = "تعذر التحقق من التشفير الأمني للسجلات. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                detailMessageEn = "Corrupted envelope detected in offline records.",
                detailMessageAr = "تم اكتشاف تلف في بنية السجلات غير المرحّلة.",
                canRetry = true
            )
        }

        if (encryptedCount > 0 && plaintextCount > 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.MIXED_QUEUE,
                totalEvents = totalEvents,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Outbox storage contains conflicting record formats. Unsynced offline sales records are preserved safely on this device.",
                messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                detailMessageEn = "Mixed plaintext and encrypted records require owner recovery review.",
                detailMessageAr = "وجود سجلات متباينة يتطلب مراجعة الاسترداد من المالك.",
                canRetry = true
            )
        }

        if (plaintextCount > 0 && encryptedCount == 0) {
            // Validate that the legacy plaintext is genuine and intact
            var legacyValid = true
            for (event in sampleEvents) {
                currentCoroutineContext().ensureActive()
                try {
                    payloadCipher.verifyLegacy(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    legacyValid = false
                    break
                }
            }

            if (!legacyValid) {
                return@withContext OutboxHealthResult(
                    status = OutboxHealthStatus.CORRUPT,
                    totalEvents = totalEvents,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "Pre-existing offline records failed integrity validation. Records are preserved safely on this device.",
                    messageAr = "فشل التحقق من سلامة السجلات السابقة. السجلات محفوظة بأمان على هذا الجهاز.",
                    detailMessageEn = "Legacy records failed integrity verification.",
                    detailMessageAr = "فشل التحقق من سلامة السجلات السابقة.",
                    canRetry = true
                )
            }

            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.LEGACY_PLAINTEXT,
                totalEvents = totalEvents,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Pre-existing unencrypted sales records detected. Records are preserved safely on device, but require owner-reviewed recovery before processing.",
                messageAr = "تم اكتشاف سجلات مبيعات سابقة غير مشفرة. السجلات محفوظة بأمان على الجهاز وتتطلب مراجعة واعتماد الاسترداد من المالك قبل المعالجة.",
                detailMessageEn = "Pre-existing data has no cryptographic installation provenance. Upgrade is paused to protect economic records.",
                detailMessageAr = "البيانات السابقة لا تحمل إثبات تثبيت مشفر. تم إيقاف الترقية لحماية السجلات المالية.",
                canRetry = true
            )
        }

        // All probed records are encrypted; authenticate key and ciphertext on the first record
        val firstEncrypted = sampleEvents.first()
        try {
            payloadCipher.decrypt(firstEncrypted)
        } catch (e: OutboxStorageException) {
            return@withContext when (e.error) {
                OutboxStorageError.KEY_LOCKED -> OutboxHealthResult(
                    status = OutboxHealthStatus.ENCRYPTED_LOCKED,
                    totalEvents = totalEvents,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "The secure encryption key is locked or invalidated. Unsynced offline sales records are preserved safely on this device.",
                    messageAr = "مفتاح التشفير الآمن مقفل أو تم إبطاله. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                    detailMessageEn = "Android Keystore key is permanently invalidated or locked.",
                    detailMessageAr = "مفتاح Keystore مقفل أو تم إبطاله نهائياً.",
                    canRetry = true
                )
                OutboxStorageError.KEY_UNAVAILABLE -> OutboxHealthResult(
                    status = OutboxHealthStatus.KEY_UNAVAILABLE,
                    totalEvents = totalEvents,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "The secure encryption key is unavailable on this device. Unsynced offline sales records are preserved safely on this device.",
                    messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                    detailMessageEn = "Keystore key is missing for existing encrypted records.",
                    detailMessageAr = "مفتاح التشفير مفقود للسجلات المشفرة الحالية.",
                    canRetry = true
                )
                OutboxStorageError.CORRUPT, OutboxStorageError.OVERSIZED -> OutboxHealthResult(
                    status = OutboxHealthStatus.CORRUPT,
                    totalEvents = totalEvents,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "Storage records could not be cryptographically authenticated. Unsynced offline sales records are preserved safely on this device.",
                    messageAr = "تعذر التحقق من التشفير الأمني للسجلات. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                    detailMessageEn = "AEAD ciphertext authentication or digest mismatch.",
                    detailMessageAr = "فشل التحقق الأمني أو عدم تطابق البصمة المشفرة.",
                    canRetry = true
                )
                OutboxStorageError.LEGACY_CONVERSION_REQUIRED -> OutboxHealthResult(
                    status = OutboxHealthStatus.MIXED_QUEUE,
                    totalEvents = totalEvents,
                    probedCount = probedCount,
                    isSampleProbe = isSampleProbe,
                    messageEn = "Outbox storage contains conflicting record formats. Unsynced offline sales records are preserved safely on this device.",
                    messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                    detailMessageEn = "Legacy format conflict detected during decryption probe.",
                    detailMessageAr = "تعارض في صيغ السجلات أثناء الفحص.",
                    canRetry = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = totalEvents,
                probedCount = probedCount,
                isSampleProbe = isSampleProbe,
                messageEn = "Storage records could not be cryptographically authenticated. Unsynced offline sales records are preserved safely on this device.",
                messageAr = "تعذر التحقق من التشفير الأمني للسجلات. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
                detailMessageEn = "Unexpected decryption error.",
                detailMessageAr = "خطأ غير متوقع أثناء فك التشفير.",
                canRetry = true
            )
        }

        // Encryption and decryption probe succeeded!
        OutboxHealthResult(
            status = OutboxHealthStatus.HEALTHY,
            totalEvents = totalEvents,
            probedCount = probedCount,
            isSampleProbe = isSampleProbe,
            messageEn = if (isSampleProbe) {
                "Encrypted offline queue is healthy (probe verified $probedCount records; full unprobed queue integrity is not claimed)."
            } else {
                "Encrypted offline queue is healthy."
            },
            messageAr = if (isSampleProbe) {
                "طابور الترحيل المشفر يعمل بشكل سليم (تم التحقق من عينة $probedCount سجل، دون ادعاء سلامة كامل السجلات غير المفحوصة)."
            } else {
                "طابور الترحيل المشفر يعمل بشكل سليم."
            },
            canRetry = false
        )
    }
}
