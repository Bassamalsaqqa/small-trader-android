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

    /** Queue is locked due to authentication denial (HTTP 401). */
    AUTH_BLOCKED,

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
    val canRetry: Boolean = false,
    val isScopeLocked: Boolean = false
) {
    val isOperational: Boolean
        get() = !isScopeLocked && status != OutboxHealthStatus.AUTH_BLOCKED &&
                ((status == OutboxHealthStatus.HEALTHY && !isSampleProbe) || status == OutboxHealthStatus.NEW_EMPTY)

    val isQueueProcessingReady: Boolean
        get() = isOperational

    val isLegacyBlocked: Boolean
        get() = status == OutboxHealthStatus.LEGACY_PLAINTEXT

    val hasPreservedData: Boolean
        get() = totalEvents > 0

    override fun toString(): String =
        "OutboxHealthResult(status=$status, isOperational=$isOperational, isScopeLocked=$isScopeLocked, canRetry=$canRetry)"
}

interface OutboxHealthInspector {
    suspend fun inspectHealth(
        companyId: String,
        actorId: String,
        deviceId: String,
        maxProbeCount: Int = ALL_EVENTS
    ): OutboxHealthResult

    companion object {
        const val ALL_EVENTS = 0
        const val DEFAULT_PAGE_SIZE = 10
        const val DEFAULT_PROBE_LIMIT = ALL_EVENTS
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onSnapshotReadHook: (() -> Unit)? = null,
    private val onPageReadHook: ((pageIndex: Int) -> Unit)? = null
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
                isScopeLocked = false,
                messageEn = "Scope identifier cannot be blank for outbox health inspection.",
                messageAr = "لا يمكن أن تكون معرّفات النطاق فارغة لفحص حالة طابور الترحيل.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = false
            )
        }

        val pageSize = OutboxHealthInspector.DEFAULT_PAGE_SIZE
        val maxEnvelopeBytes = ContractConstants.MAX_PAYLOAD_BYTES + 32
        val maxEnvelopeBytesPlusOne = maxEnvelopeBytes + 1

        // Gather scoped count, classifications, auth lock, and bounded initial page
        val snapshot = try {
            database.outboxDao().getScopedHealthSnapshot(
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                maxEnvelopeBytesPlusOne = maxEnvelopeBytesPlusOne,
                limit = pageSize,
                onBetweenCountAndSample = onSnapshotReadHook
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                isScopeLocked = false,
                messageEn = "Failed to inspect local outbox database storage.",
                messageAr = "تعذر فحص وحدة تخزين السجلات المحلية.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        val scopedTotal = snapshot.totalScopedEvents
        val isScopeLocked = snapshot.isScopeLocked
        val nonEncryptedCount = snapshot.nonEncryptedCount
        val globalTotal = snapshot.totalGlobalEvents

        if (isScopeLocked && scopedTotal == 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.AUTH_BLOCKED,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                isScopeLocked = true,
                messageEn = "Offline sync is temporarily paused. Please sign in again or contact support.",
                messageAr = "المزامنة متوقفة مؤقتاً. يرجى تسجيل الدخول مجدداً أو التواصل مع الدعم الفني.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        if (scopedTotal == 0) {
            // New empty queue for this scope.
            // If the database has global events (> 0), repository capture requires a retained key: payloadCipher.prepare(true).
            // Same-DB Company A legacy/no key + Company B empty must NOT claim capture ready.
            if (globalTotal > 0) {
                try {
                    payloadCipher.prepare(true)
                } catch (e: OutboxStorageException) {
                    return@withContext when (e.error) {
                        OutboxStorageError.KEY_LOCKED -> OutboxHealthResult(
                            status = OutboxHealthStatus.ENCRYPTED_LOCKED,
                            totalEvents = 0,
                            probedCount = 0,
                            isSampleProbe = false,
                            isScopeLocked = isScopeLocked,
                            messageEn = "The secure encryption key is locked or invalidated. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "مفتاح التشفير الآمن مقفل أو تم إبطاله. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                        OutboxStorageError.KEY_UNAVAILABLE -> OutboxHealthResult(
                            status = OutboxHealthStatus.KEY_UNAVAILABLE,
                            totalEvents = 0,
                            probedCount = 0,
                            isSampleProbe = false,
                            isScopeLocked = isScopeLocked,
                            messageEn = "The secure encryption key is unavailable on this device. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                        else -> OutboxHealthResult(
                            status = OutboxHealthStatus.CORRUPT,
                            totalEvents = 0,
                            probedCount = 0,
                            isSampleProbe = false,
                            isScopeLocked = isScopeLocked,
                            messageEn = "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withContext OutboxHealthResult(
                        status = OutboxHealthStatus.KEY_UNAVAILABLE,
                        totalEvents = 0,
                        probedCount = 0,
                        isSampleProbe = false,
                        isScopeLocked = isScopeLocked,
                        messageEn = "The secure encryption key is unavailable on this device. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }
            }

            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.NEW_EMPTY,
                totalEvents = 0,
                probedCount = 0,
                isSampleProbe = false,
                isScopeLocked = isScopeLocked,
                messageEn = "Encrypted offline queue is ready.",
                messageAr = "طابور الترحيل المشفر جاهز للاستخدام.",
                canRetry = false
            )
        }

        // Fast-path SQL unencrypted classification check
        if (nonEncryptedCount == scopedTotal) {
            // All scoped events are unencrypted legacy plaintext
            for (row in snapshot.sampleRows) {
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
                } catch (_: Exception) {
                    return@withContext OutboxHealthResult(
                        status = OutboxHealthStatus.CORRUPT,
                        totalEvents = scopedTotal,
                        probedCount = snapshot.sampleRows.size,
                        isSampleProbe = scopedTotal > snapshot.sampleRows.size,
                        isScopeLocked = isScopeLocked,
                        messageEn = "Pre-existing offline records failed integrity validation. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "فشل التحقق من سلامة السجلات السابقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }
            }
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.LEGACY_PLAINTEXT,
                totalEvents = scopedTotal,
                probedCount = snapshot.sampleRows.size,
                isSampleProbe = scopedTotal > snapshot.sampleRows.size,
                isScopeLocked = isScopeLocked,
                messageEn = "Pre-existing unencrypted sales records detected. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تم اكتشاف سجلات مبيعات سابقة غير مشفرة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        if (nonEncryptedCount > 0) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.MIXED_QUEUE,
                totalEvents = scopedTotal,
                probedCount = snapshot.sampleRows.size,
                isSampleProbe = scopedTotal > snapshot.sampleRows.size,
                isScopeLocked = isScopeLocked,
                messageEn = "Outbox storage contains conflicting record formats. We have not changed stored sales. Some records need help before syncing.",
                messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        // Bounded, cancellable, paged verification across scoped rows
        val targetProbeLimit = if (maxProbeCount > 0) minOf(maxProbeCount, scopedTotal) else scopedTotal
        var verifiedCount = 0
        var currentPage = snapshot.sampleRows
        var pageIndex = 0
        var lastCapturedAt: String? = null
        var lastClientEventId: String? = null

        while (currentPage.isNotEmpty() && verifiedCount < targetProbeLimit) {
            currentCoroutineContext().ensureActive()
            onPageReadHook?.invoke(pageIndex)

            for (row in currentPage) {
                currentCoroutineContext().ensureActive()
                if (verifiedCount >= targetProbeLimit) break

                if (row.payloadLength > maxEnvelopeBytes) {
                    return@withContext OutboxHealthResult(
                        status = OutboxHealthStatus.CORRUPT,
                        totalEvents = scopedTotal,
                        probedCount = verifiedCount + 1,
                        isSampleProbe = scopedTotal > (verifiedCount + 1),
                        isScopeLocked = isScopeLocked,
                        messageEn = "Storage records exceeded maximum allowed envelope size. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "تجاوزت بعض السجلات الحد الأقصى لحجم التخزين. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }

                val bytes = row.boundedPayloadBytes
                if (!payloadCipher.isEncrypted(bytes)) {
                    val status = if (payloadCipher.hasStoEnvelope(bytes)) {
                        OutboxHealthStatus.CORRUPT
                    } else {
                        OutboxHealthStatus.MIXED_QUEUE
                    }
                    return@withContext OutboxHealthResult(
                        status = status,
                        totalEvents = scopedTotal,
                        probedCount = verifiedCount + 1,
                        isSampleProbe = scopedTotal > (verifiedCount + 1),
                        isScopeLocked = isScopeLocked,
                        messageEn = if (status == OutboxHealthStatus.CORRUPT)
                            "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing."
                        else
                            "Outbox storage contains conflicting record formats. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = if (status == OutboxHealthStatus.CORRUPT)
                            "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة."
                        else
                            "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }

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
                            probedCount = verifiedCount + 1,
                            isSampleProbe = scopedTotal > (verifiedCount + 1),
                            isScopeLocked = isScopeLocked,
                            messageEn = "The secure encryption key is locked or invalidated. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "مفتاح التشفير الآمن مقفل أو تم إبطاله. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                        OutboxStorageError.KEY_UNAVAILABLE -> OutboxHealthResult(
                            status = OutboxHealthStatus.KEY_UNAVAILABLE,
                            totalEvents = scopedTotal,
                            probedCount = verifiedCount + 1,
                            isSampleProbe = scopedTotal > (verifiedCount + 1),
                            isScopeLocked = isScopeLocked,
                            messageEn = "The secure encryption key is unavailable on this device. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                        OutboxStorageError.CORRUPT, OutboxStorageError.OVERSIZED -> OutboxHealthResult(
                            status = OutboxHealthStatus.CORRUPT,
                            totalEvents = scopedTotal,
                            probedCount = verifiedCount + 1,
                            isSampleProbe = scopedTotal > (verifiedCount + 1),
                            isScopeLocked = isScopeLocked,
                            messageEn = "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                        OutboxStorageError.LEGACY_CONVERSION_REQUIRED -> OutboxHealthResult(
                            status = OutboxHealthStatus.MIXED_QUEUE,
                            totalEvents = scopedTotal,
                            probedCount = verifiedCount + 1,
                            isSampleProbe = scopedTotal > (verifiedCount + 1),
                            isScopeLocked = isScopeLocked,
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
                        probedCount = verifiedCount + 1,
                        isSampleProbe = scopedTotal > (verifiedCount + 1),
                        isScopeLocked = isScopeLocked,
                        messageEn = "Storage records could not be cryptographically authenticated. We have not changed stored sales. Some records need help before syncing.",
                        messageAr = "تعذر التحقق من التشفير الأمني للسجلات. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }

                lastCapturedAt = row.capturedAt
                lastClientEventId = row.clientEventId
                verifiedCount++
            }

            if (verifiedCount < targetProbeLimit) {
                pageIndex++
                val limitForNext = minOf(pageSize, targetProbeLimit - verifiedCount)
                currentPage = database.outboxDao().scopedEventsPageForHealthProbe(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    afterCapturedAt = lastCapturedAt,
                    afterClientEventId = lastClientEventId,
                    maxEnvelopeBytesPlusOne = maxEnvelopeBytesPlusOne,
                    limit = limitForNext
                )
            }
        }

        // Check persisted scope lock
        val isCurrentlyLocked = isScopeLocked || database.outboxDao().isScopeLocked(companyId, actorId, deviceId)
        if (isCurrentlyLocked) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.AUTH_BLOCKED,
                totalEvents = scopedTotal,
                probedCount = verifiedCount,
                isSampleProbe = scopedTotal > verifiedCount,
                isScopeLocked = true,
                messageEn = "Offline sync is temporarily paused. Please sign in again or contact support.",
                messageAr = "المزامنة متوقفة مؤقتاً. يرجى تسجيل الدخول مجدداً أو التواصل مع الدعم الفني.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        // Durable generation / snapshot check: verify that count has not mutated during the scan
        val currentScopedCount = database.outboxDao().scopedEventCount(companyId, actorId, deviceId)
        val isSampleProbe = (maxProbeCount > 0 && maxProbeCount < currentScopedCount) || (verifiedCount < currentScopedCount)

        if (isSampleProbe) {
            return@withContext OutboxHealthResult(
                status = OutboxHealthStatus.PARTIAL_UNCHECKED,
                totalEvents = currentScopedCount,
                probedCount = verifiedCount,
                isSampleProbe = true,
                isScopeLocked = false,
                messageEn = "Queue contains unverified offline records beyond probe limit. Queue processing cannot be granted from a partial sample.",
                messageAr = "يحتوي طابور الترحيل على سجلات غير مفحوصة تتجاوز حد الفحص. لا يمكن منح صلاحية المعالجة بناءً على عينة جزئية.",
                detailMessageEn = "Contact support",
                detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                canRetry = true
            )
        }

        return@withContext OutboxHealthResult(
            status = OutboxHealthStatus.HEALTHY,
            totalEvents = currentScopedCount,
            probedCount = verifiedCount,
            isSampleProbe = false,
            isScopeLocked = false,
            messageEn = "Encrypted offline queue is healthy.",
            messageAr = "طابور الترحيل المشفر يعمل بشكل سليم.",
            canRetry = false
        )
    }
}
