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

/**
 * Default inspector evaluating outbox health off the main thread.
 *
 * Durability and Snapshot Coherence Guarantees:
 * 1. Single Coherent SQLite Transaction:
 *    Executes a single Room transaction ([database.runInTransaction]) encompassing the initial
 *    scope evaluation, global key admission, auth lock status, bounded keyset pagination across all
 *    records, cryptographic payload authentication, and final health determination.
 * 2. Scope, Time, and Lock Limitations:
 *    - Scope: Provides snapshot isolation guarantees across all queries in the scope.
 *    - Writer Serialization: In SQLite, transactions serialize write operations. Room's
 *      [runInTransaction] holds a database lock during inspection; external SQLite writers
 *      serialize appropriately and will wait until the inspection transaction completes.
 *    - Bounded Resource Usage: Inspection uses keyset pagination bounded to [OutboxHealthInspector.DEFAULT_PAGE_SIZE]
 *      (10 records per page), preventing whole-queue memory materialization even for large backlogs.
 *    - Strict Off-Main-Thread Execution: Guaranteed execution on [ioDispatcher] with non-main-thread assertions.
 * 3. Cancellation Propagation:
 *    - Active cancellation checks ([inspectionContext.ensureActive()]) execute at each page
 *      and row. Cancellation immediately rolls back the transaction and releases all locks without swallowing
 *      [CancellationException].
 * 4. Zero False Positives / Zero Destructive Actions:
 *    - Empty queues ([NEW_EMPTY]) generate zero encryption keys.
 *    - Unauthenticated/legacy/corrupt records are preserved on disk with zero writes, zero destructive drops,
 *      and zero key minting.
 */
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

        val inspectionContext = currentCoroutineContext()
        inspectionContext.ensureActive()

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

        val result = try {
            database.runInTransaction<OutboxHealthResult> {
                // Gather scoped count, classifications, auth lock, and bounded initial page under ONE coherent transaction
                val snapshot = database.outboxDao().getScopedHealthSnapshot(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    maxEnvelopeBytesPlusOne = maxEnvelopeBytesPlusOne,
                    limit = pageSize,
                    onBetweenCountAndSample = onSnapshotReadHook
                )

                val scopedTotal = snapshot.totalScopedEvents
                val isScopeLocked = snapshot.isScopeLocked
                val nonEncryptedCount = snapshot.nonEncryptedCount
                val globalTotal = snapshot.totalGlobalEvents

                if (isScopeLocked && scopedTotal == 0) {
                    return@runInTransaction OutboxHealthResult(
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
                            return@runInTransaction when (e.error) {
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
                                    status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                                    totalEvents = 0,
                                    probedCount = 0,
                                    isSampleProbe = false,
                                    isScopeLocked = isScopeLocked,
                                    messageEn = "Secure storage is unavailable. We have not changed stored sales. Some records need help before syncing.",
                                    messageAr = "وحدة التخزين الآمنة غير متوفرة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                                    detailMessageEn = "Contact support",
                                    detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                                    canRetry = true
                                )
                            }
                        }
                    }

                    return@runInTransaction OutboxHealthResult(
                        status = OutboxHealthStatus.NEW_EMPTY,
                        totalEvents = 0,
                        probedCount = 0,
                        isSampleProbe = false,
                        isScopeLocked = false,
                        messageEn = "New empty outbox queue ready for operations.",
                        messageAr = "طابور الترحيل فارغ وجاهز للعمليات.",
                        canRetry = false
                    )
                }

                // If non-encrypted rows exist in this scope
                if (nonEncryptedCount > 0) {
                    // A non-STO/1 prefix is not proof of valid legacy data. Validate
                    // every plaintext candidate without modifying or blessing its provenance.
                    var legacyPage = snapshot.sampleRows
                    while (legacyPage.isNotEmpty()) {
                        for (row in legacyPage) {
                            inspectionContext.ensureActive()
                            if (!payloadCipher.isEncrypted(row.boundedPayloadBytes)) {
                                try {
                                    payloadCipher.verifyLegacy(OutboxEventEntity(
                                        clientEventId = row.clientEventId, companyId = row.companyId,
                                        actorId = row.actorId, deviceId = row.deviceId,
                                        deviceLeaseId = row.deviceLeaseId, capturedAt = row.capturedAt,
                                        payloadDigest = row.payloadDigest, payloadBytes = row.boundedPayloadBytes,
                                        eventVersion = row.eventVersion
                                    ))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: OutboxStorageException) {
                                    return@runInTransaction OutboxHealthResult(
                                        status = OutboxHealthStatus.CORRUPT, totalEvents = scopedTotal,
                                        probedCount = 0, isSampleProbe = false, isScopeLocked = isScopeLocked,
                                        messageEn = "Some stored sales need help before syncing.",
                                        messageAr = "بعض المبيعات المحفوظة تحتاج إلى مساعدة قبل المزامنة.",
                                        canRetry = true
                                    )
                                }
                            }
                        }
                        val last = legacyPage.last()
                        legacyPage = database.outboxDao().scopedEventsPageForHealthProbe(
                            companyId, actorId, deviceId, last.capturedAt, last.clientEventId,
                            maxEnvelopeBytesPlusOne, pageSize
                        )
                    }
                    val status = if (nonEncryptedCount == scopedTotal) {
                        OutboxHealthStatus.LEGACY_PLAINTEXT
                    } else {
                        OutboxHealthStatus.MIXED_QUEUE
                    }
                    val isMixed = status == OutboxHealthStatus.MIXED_QUEUE
                    val messageEn = if (isMixed) {
                        "Outbox storage contains conflicting record formats. We have not changed stored sales. Some records need help before syncing."
                    } else {
                        "Storage records require format upgrade before syncing. We have not changed stored sales. Some records need help before syncing."
                    }
                    val messageAr = if (isMixed) {
                        "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة."
                    } else {
                        "تتطلب السجلات ترقية التنسيق قبل المزامنة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة."
                    }
                    return@runInTransaction OutboxHealthResult(
                        status = status,
                        totalEvents = scopedTotal,
                        probedCount = nonEncryptedCount,
                        isSampleProbe = scopedTotal > nonEncryptedCount,
                        isScopeLocked = isScopeLocked,
                        messageEn = messageEn,
                        messageAr = messageAr,
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }

                // Check scope lock for non-empty queue
                if (isScopeLocked) {
                    return@runInTransaction OutboxHealthResult(
                        status = OutboxHealthStatus.AUTH_BLOCKED,
                        totalEvents = scopedTotal,
                        probedCount = 0,
                        isSampleProbe = scopedTotal > 0,
                        isScopeLocked = true,
                        messageEn = "Offline sync is temporarily paused. Please sign in again or contact support.",
                        messageAr = "المزامنة متوقفة مؤقتاً. يرجى تسجيل الدخول مجدداً أو التواصل مع الدعم الفني.",
                        detailMessageEn = "Contact support",
                        detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                        canRetry = true
                    )
                }

                // Prepare cipher for reading encrypted payloads
                try {
                    payloadCipher.prepare(true)
                } catch (e: OutboxStorageException) {
                    return@runInTransaction when (e.error) {
                        OutboxStorageError.KEY_LOCKED -> OutboxHealthResult(
                            status = OutboxHealthStatus.ENCRYPTED_LOCKED,
                            totalEvents = scopedTotal,
                            probedCount = 0,
                            isSampleProbe = scopedTotal > 0,
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
                            probedCount = 0,
                            isSampleProbe = scopedTotal > 0,
                            isScopeLocked = isScopeLocked,
                            messageEn = "The secure encryption key is unavailable on this device. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                        else -> OutboxHealthResult(
                            status = OutboxHealthStatus.STORAGE_UNAVAILABLE,
                            totalEvents = scopedTotal,
                            probedCount = 0,
                            isSampleProbe = scopedTotal > 0,
                            isScopeLocked = isScopeLocked,
                            messageEn = "Secure storage is unavailable. We have not changed stored sales. Some records need help before syncing.",
                            messageAr = "وحدة التخزين الآمنة غير متوفرة. لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                            detailMessageEn = "Contact support",
                            detailMessageAr = "يرجى التواصل مع الدعم الفني.",
                            canRetry = true
                        )
                    }
                }

                // Bounded, cancellable, keyset-paged verification across scoped rows
                val targetProbeLimit = if (maxProbeCount > 0) minOf(maxProbeCount, scopedTotal) else scopedTotal
                var verifiedCount = 0
                var currentPage = snapshot.sampleRows
                var pageIndex = 0
                var lastCapturedAt: String? = null
                var lastClientEventId: String? = null

                while (currentPage.isNotEmpty() && verifiedCount < targetProbeLimit) {
                    inspectionContext.ensureActive()
                    onPageReadHook?.invoke(pageIndex)

                    for (row in currentPage) {
                        inspectionContext.ensureActive()
                        if (verifiedCount >= targetProbeLimit) break

                        if (row.payloadLength > maxEnvelopeBytes) {
                            return@runInTransaction OutboxHealthResult(
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
                            return@runInTransaction OutboxHealthResult(
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
                            return@runInTransaction when (e.error) {
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
                            return@runInTransaction OutboxHealthResult(
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

                val isSampleProbe = (maxProbeCount > 0 && maxProbeCount < scopedTotal) || (verifiedCount < scopedTotal)

                if (isSampleProbe) {
                    return@runInTransaction OutboxHealthResult(
                        status = OutboxHealthStatus.PARTIAL_UNCHECKED,
                        totalEvents = scopedTotal,
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

                return@runInTransaction OutboxHealthResult(
                    status = OutboxHealthStatus.HEALTHY,
                    totalEvents = scopedTotal,
                    probedCount = verifiedCount,
                    isSampleProbe = false,
                    isScopeLocked = false,
                    messageEn = "Encrypted offline queue is healthy.",
                    messageAr = "طابور الترحيل المشفر يعمل بشكل سليم.",
                    canRetry = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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

        return@withContext result
    }
}
