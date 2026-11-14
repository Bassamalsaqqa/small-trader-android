package com.example.data.mobile.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.OfflineSaleEvent
import com.example.data.mobile.contract.ServerUlid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Focused opt-in JVM/Robolectric scale performance and correctness qualification harness.
 *
 * Requirements & Invariants:
 * 1. Opt-in Execution:
 *    - Guarded by [PROPERTY_BENCHMARK_ENABLED] or [ENV_BENCHMARK_ENABLED].
 *    - Defaults to skipped ([Assume.assumeTrue]) during standard unit test suites (168 QA tests).
 * 2. Realistic Scale & Invariants:
 *    - 1,000+ actual, canonical, encrypted receipt records in real Room database via real [OutboxRepositoryImpl].
 *    - Strict avoidance of fake counts: zero reused IDs, strictly distinct and valid ISO-8601 timestamps,
 *      canonical JSON serializations, unique receipt numbers, and AES-256-GCM / 128-bit authentication tags.
 * 3. Bounded Paged Verification:
 *    - Validates that [DefaultOutboxHealthInspector] traverses all records in bounded 10-item pages
 *      ([OutboxHealthInspector.DEFAULT_PAGE_SIZE]), avoiding whole-queue memory materialization.
 * 4. Latency Distribution & Measurements:
 *    - Measures warmups and sample distributions (random event IDs are hashed per run): reports min, p50 (median), p95, p99, max, mean.
 *    - Clearly qualifies latency as JVM database read / background worker execution, NOT physical UI frame hardware.
 * 5. Concurrent Capture Under Write Lock:
 *    - Measures background writer wait time when [repository.captureSale] executes concurrently during
 *      an active 1,000-row health transaction.
 *    - Asserts serialization, clean lock release upon inspection commit, and data integrity.
 * 6. Cooperative Cancellation:
 *    - Cancels mid-inspection after verifying partial pages.
 *    - Asserts bounded termination without draining the remainder of the 1,000 rows.
 *    - Asserts transaction rollback, prompt database lock release, and 100% preservation of all bytes and delivery claims.
 * 7. Verification Environment:
 *    - JVM (Robolectric / OpenJDK 21) only. Does NOT claim low-end hardware / native Android Keystore acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxScaleBenchmarkHarnessTest {

    companion object {
        const val PROPERTY_BENCHMARK_ENABLED = "outbox.benchmark.enabled"
        const val ENV_BENCHMARK_ENABLED = "OUTBOX_BENCHMARK_ENABLED"
        const val PROPERTY_ROW_COUNT = "outbox.benchmark.count"

        const val DEFAULT_ROW_COUNT = 1000
        const val DEFAULT_WARMUP_ROUNDS = 3
        const val DEFAULT_SAMPLE_ROUNDS = 10

        fun isBenchmarkEnabled(): Boolean {
            return System.getProperty(PROPERTY_BENCHMARK_ENABLED)?.toBoolean() == true ||
                System.getenv(ENV_BENCHMARK_ENABLED)?.toBoolean() == true
        }

        fun configuredRowCount(): Int {
            val custom = System.getProperty(PROPERTY_ROW_COUNT)?.toIntOrNull()
            return if (custom != null && custom >= 1000) custom else DEFAULT_ROW_COUNT
        }
    }

    private val defaultCompanyId = "01JCV8X9MBV4W5X6Y7Z8012345"
    private val defaultActorId = "01JCV8X9MBV4W5X6Y7Z8012346"
    private val defaultDeviceId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var database: OutboxDatabase? = null
    private var writerDatabase: OutboxDatabase? = null
    private val executor = Executors.newFixedThreadPool(2)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "outbox-scale-bench-${UUID.randomUUID()}.db")
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        assertTrue("Harness threads must terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        writerDatabase?.close()
        database?.close()
        if (dbFile.exists()) {
            dbFile.delete()
        }
        val walFile = File(dbFile.path + "-wal")
        if (walFile.exists()) walFile.delete()
        val shmFile = File(dbFile.path + "-shm")
        if (shmFile.exists()) shmFile.delete()
    }

    private fun computeScenarioDigest(events: List<OfflineSaleEvent>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (event in events) {
            md.update(event.clientEventId.value.toByteArray(StandardCharsets.UTF_8))
            md.update(event.capturedAt.toByteArray(StandardCharsets.UTF_8))
            md.update(event.computePayloadDigest().value.toByteArray(StandardCharsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun storedRecordsDigest(db: OutboxDatabase, events: List<OfflineSaleEvent>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (event in events) {
            val row = requireNotNull(db.outboxDao().getEvent(event.companyId.value, event.clientEventId.value))
            digest.update(row.payloadBytes)
            digest.update(row.payloadDigest.toByteArray(StandardCharsets.UTF_8))
            val state = requireNotNull(db.outboxDao().getDeliveryState(event.companyId.value, event.clientEventId.value))
            digest.update(state.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun seedCanonicalEncryptedQueue(
        repo: OutboxRepository,
        count: Int,
        companyId: ServerUlid = ServerUlid.parse(defaultCompanyId),
        actorId: ServerUlid = ServerUlid.parse(defaultActorId),
        deviceId: ClientUuid = ClientUuid.parse(defaultDeviceId)
    ): List<OfflineSaleEvent> {
        val baseEpoch = 1794312000000L // 2026-11-10T12:00:00Z
        val events = ArrayList<OfflineSaleEvent>(count)
        for (i in 0 until count) {
            val timestamp = Instant.ofEpochMilli(baseEpoch + (i * 1000L)).toString()
            val event = OutboxTestFixtures.createSampleEvent(
                clientEventId = ClientUuid.random(),
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                capturedAt = timestamp,
                localReceiptNumber = String.format(Locale.US, "REC-BENCH-%06d", i + 1),
                quantity = "1.000000",
                unitPrice = "100.000000",
                taxRate = "15.000000",
                taxAmount = "15.000000",
                lineTotal = "115.000000"
            )
            val captureResult = repo.captureSale(event)
            assertTrue("Seeding row $i must succeed", captureResult is CaptureResult.Created)
            events.add(event)
        }
        return events
    }

    @Test(timeout = 120_000)
    fun benchmark1k_pagedVerification_distribution_and_responsiveness() = runBlocking {
        Assume.assumeTrue(
            "Scale benchmark is opt-in. Set -D$PROPERTY_BENCHMARK_ENABLED=true or $ENV_BENCHMARK_ENABLED=true to run.",
            isBenchmarkEnabled()
        )

        val totalRecords = configuredRowCount()
        assertTrue("Harness requires 1,000+ actual records", totalRecords >= 1000)

        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        database = db
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        // Seed 1,000+ actual valid canonical encrypted receipt rows
        val seedStartNs = System.nanoTime()
        val events = seedCanonicalEncryptedQueue(repo, totalRecords)
        val seedElapsedMs = (System.nanoTime() - seedStartNs) / 1_000_000.0
        val scenarioHash = computeScenarioDigest(events)

        assertEquals(totalRecords, db.outboxDao().eventCount())

        // 1. Verify Bounded Paged Verification (page size 10, all rows verified, no whole-queue materialization)
        val pageCounter = AtomicInteger(0)
        val inspector = DefaultOutboxHealthInspector(
            database = db,
            payloadCipher = cipher,
            ioDispatcher = Dispatchers.IO,
            onPageReadHook = { _ -> pageCounter.incrementAndGet() }
        )

        val baselineResult = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.HEALTHY, baselineResult.status)
        assertEquals(totalRecords, baselineResult.totalEvents)
        assertEquals(totalRecords, baselineResult.probedCount)
        assertFalse(baselineResult.isSampleProbe)
        assertTrue(baselineResult.isOperational)

        val expectedPages = (totalRecords + OutboxHealthInspector.DEFAULT_PAGE_SIZE - 1) / OutboxHealthInspector.DEFAULT_PAGE_SIZE
        assertEquals("Must traverse in bounded pages of 10", expectedPages, pageCounter.get())

        // 2. Warm-up JIT and database caches
        for (w in 0 until DEFAULT_WARMUP_ROUNDS) {
            val warmRes = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
            assertEquals(OutboxHealthStatus.HEALTHY, warmRes.status)
        }

        // 3. Measure Sample Distribution
        val samplesMs = ArrayList<Double>(DEFAULT_SAMPLE_ROUNDS)
        for (s in 0 until DEFAULT_SAMPLE_ROUNDS) {
            val startNs = System.nanoTime()
            val sampleRes = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            assertEquals(OutboxHealthStatus.HEALTHY, sampleRes.status)
            assertEquals(totalRecords, sampleRes.probedCount)
            samplesMs.add(elapsedMs)
        }

        val sorted = samplesMs.sorted()
        val minMs = sorted.first()
        val p50Ms = sorted[sorted.size / 2]
        val p95Ms = sorted[(sorted.size * 95) / 100]
        val p99Ms = sorted[(sorted.size * 99) / 100]
        val maxMs = sorted.last()
        val meanMs = samplesMs.average()

        println("================================================================================")
        println("=== OUTBOX 1,000+ ENCRYPTED QUEUE SCALE QUALIFICATION REPORT ===")
        println("================================================================================")
        println("Scenario Digest (SHA-256): $scenarioHash")
        println("Database Path: ${dbFile.path}")
        println("Total Valid Encrypted Receipts: $totalRecords")
        println(String.format(Locale.US, "Seeding Time (1,000 captures): %.2f ms", seedElapsedMs))
        println("Page Size: ${OutboxHealthInspector.DEFAULT_PAGE_SIZE} records / page (bounded memory)")
        println("Total Keyset Pages Traversed: $expectedPages pages")
        println("Warm-up Iterations: $DEFAULT_WARMUP_ROUNDS")
        println("Measured Samples: $DEFAULT_SAMPLE_ROUNDS")
        println("Raw samples ms: $samplesMs")
        println("p95/p99 are empirical order statistics from 10 samples, not stable device-tail estimates.")
        println("--------------------------------------------------------------------------------")
        println(String.format(Locale.US, "Latency Distribution (DB Read & Decrypt):"))
        println(String.format(Locale.US, "  Min:    %8.2f ms", minMs))
        println(String.format(Locale.US, "  p50:    %8.2f ms", p50Ms))
        println(String.format(Locale.US, "  p95:    %8.2f ms", p95Ms))
        println(String.format(Locale.US, "  p99:    %8.2f ms", p99Ms))
        println(String.format(Locale.US, "  Max:    %8.2f ms", maxMs))
        println(String.format(Locale.US, "  Mean:   %8.2f ms", meanMs))
        println("--------------------------------------------------------------------------------")
        println("[QUALIFICATION DISCLAIMER]")
        println("Commands/runtime/device: JVM (Robolectric / OpenJDK 21) only.")
        println("Latency represents background SQLite transaction read & AES-GCM verification.")
        println("Does NOT represent physical device UI rendering or native Android Keystore performance.")
        println("Low-end physical hardware gate remains UNVERIFIED / HOLD.")
        println("================================================================================")

        // Measurements are observations, not an invented device/UI performance SLO.
    }

    @Test(timeout = 120_000)
    fun benchmark1k_concurrentCaptureSale_waitsDuringInspectionAndSucceeds() = runBlocking {
        Assume.assumeTrue(
            "Scale benchmark is opt-in. Set -D$PROPERTY_BENCHMARK_ENABLED=true or $ENV_BENCHMARK_ENABLED=true to run.",
            isBenchmarkEnabled()
        )

        val totalRecords = configuredRowCount()
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val readerDb = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        val writerDb = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        database = readerDb
        writerDatabase = writerDb

        val repoReader = OutboxRepositoryImpl(readerDb, payloadCipher = cipher)
        val repoWriter = OutboxRepositoryImpl(writerDb, payloadCipher = cipher)
        executor.submit<Int> { writerDb.outboxDao().eventCount() }.get(5, TimeUnit.SECONDS)

        seedCanonicalEncryptedQueue(repoReader, totalRecords)
        assertEquals(totalRecords, readerDb.outboxDao().eventCount())

        val concurrentEvent = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            companyId = ServerUlid.parse(defaultCompanyId),
            actorId = ServerUlid.parse(defaultActorId),
            deviceId = ClientUuid.parse(defaultDeviceId),
            capturedAt = "2026-11-10T13:00:00Z",
            localReceiptNumber = "REC-CONCURRENT-001"
        )

        val halfwayPage = (totalRecords / OutboxHealthInspector.DEFAULT_PAGE_SIZE) / 2
        val writeAttemptStarted = CountDownLatch(1)
        var pendingWriteFuture: Future<*>? = null
        var captureWaitStartNs = 0L
        var captureWaitDurationMs = -1.0

        val inspector = DefaultOutboxHealthInspector(
            database = readerDb,
            payloadCipher = cipher,
            ioDispatcher = Dispatchers.IO,
            onPageReadHook = { pageIndex ->
                if (pageIndex == halfwayPage) {
                    pendingWriteFuture = executor.submit {
                        captureWaitStartNs = System.nanoTime()
                        writeAttemptStarted.countDown()
                        val result = repoWriter.captureSale(concurrentEvent)
                        val finishNs = System.nanoTime()
                        captureWaitDurationMs = (finishNs - captureWaitStartNs) / 1_000_000.0
                        result
                    }
                    assertTrue("Write attempt must be triggered", writeAttemptStarted.await(5, TimeUnit.SECONDS))
                    // Verify the writer is blocked behind SQLite write lock while inspection is active
                    try {
                        pendingWriteFuture!!.get(100, TimeUnit.MILLISECONDS)
                        fail("Concurrent capture committed while health inspector held SQLite transaction lock!")
                    } catch (_: TimeoutException) {
                        // Expected: writer is blocked waiting for lock release
                    }
                }
            }
        )

        val healthResult = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.HEALTHY, healthResult.status)

        // After health inspection completes its transaction, the concurrent write finishes promptly
        assertNotNull("Write future must be present", pendingWriteFuture)
        val writeResult = pendingWriteFuture!!.get(5, TimeUnit.SECONDS)
        assertTrue("Concurrent capture must succeed as Created", writeResult is CaptureResult.Created)
        assertTrue("Capture wait time must be positive", captureWaitDurationMs >= 0.0)

        // Verify count updated to 1,001 and database is coherent
        assertEquals(totalRecords + 1, readerDb.outboxDao().eventCount())
        val afterHealth = DefaultOutboxHealthInspector(readerDb, cipher, Dispatchers.IO)
            .inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        assertEquals(OutboxHealthStatus.HEALTHY, afterHealth.status)
        assertEquals(totalRecords + 1, afterHealth.totalEvents)

        println(String.format(Locale.US, "[CONCURRENT CAPTURE] Write lock hold wait: %.2f ms", captureWaitDurationMs))
    }

    @Test(timeout = 120_000)
    fun benchmark1k_cancellation_boundedTermination_andDataPreservation() = runBlocking {
        Assume.assumeTrue(
            "Scale benchmark is opt-in. Set -D$PROPERTY_BENCHMARK_ENABLED=true or $ENV_BENCHMARK_ENABLED=true to run.",
            isBenchmarkEnabled()
        )

        val totalRecords = configuredRowCount()
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        database = db
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        val seededEvents = seedCanonicalEncryptedQueue(repo, totalRecords)
        val originalStoredDigest = storedRecordsDigest(db, seededEvents)
        assertEquals(totalRecords, db.outboxDao().eventCount())

        val cancelAtPage = 20 // 200 items in
        val pageReached = CountDownLatch(1)
        val pagesVisited = AtomicInteger(0)
        val cancellationRequested = CountDownLatch(1)

        val inspector = DefaultOutboxHealthInspector(
            database = db,
            payloadCipher = cipher,
            ioDispatcher = Dispatchers.IO,
            onPageReadHook = { pageIndex ->
                pagesVisited.incrementAndGet()
                if (pageIndex == cancelAtPage) {
                    pageReached.countDown()
                    assertTrue(cancellationRequested.await(5, TimeUnit.SECONDS))
                }
            }
        )

        var cancelStartNs = 0L
        var cancellationCaught = false
        val job = launch(Dispatchers.IO) {
            try {
                inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
            } catch (e: CancellationException) {
                cancellationCaught = true
                throw e
            }
        }

        try {
            assertTrue("Must reach cancellation page", pageReached.await(10, TimeUnit.SECONDS))
            cancelStartNs = System.nanoTime()
            job.cancel()
        } finally {
            cancellationRequested.countDown()
        }
        withTimeout(5_000) { job.join() }
        assertTrue("Inspection must observe cancellation", cancellationCaught)
        assertEquals("Stored ciphertext, identities and delivery state must remain byte-exact",
            originalStoredDigest, storedRecordsDigest(db, seededEvents))

        val cancelDurationMs = (System.nanoTime() - cancelStartNs) / 1_000_000.0

        // 1. Verify bounded termination (did not scan remaining pages)
        val visited = pagesVisited.get()
        assertTrue("Pages visited ($visited) must be <= cancelAtPage + 2", visited <= cancelAtPage + 2)
        assertTrue("Must have halted before scanning all 100 pages", visited < (totalRecords / 10))

        // 2. Verify immediate unlock: subsequent write on database succeeds immediately
        val postCancelEvent = OutboxTestFixtures.createSampleEvent(
            clientEventId = ClientUuid.random(),
            companyId = ServerUlid.parse(defaultCompanyId),
            actorId = ServerUlid.parse(defaultActorId),
            deviceId = ClientUuid.parse(defaultDeviceId),
            capturedAt = "2026-11-10T14:00:00Z",
            localReceiptNumber = "REC-POST-CANCEL-001"
        )
        val writeAfterCancel = repo.captureSale(postCancelEvent)
        assertTrue("Subsequent write must succeed immediately after cancellation rollback", writeAfterCancel is CaptureResult.Created)
        assertEquals(totalRecords + 1, db.outboxDao().eventCount())

        // 3. Verify all original records, digests, and claims are 100% preserved
        for (event in seededEvents) {
            val stored = db.outboxDao().getEvent(event.companyId.value, event.clientEventId.value)
            assertNotNull("Stored event must exist", stored)
            assertEquals("Digest must match exactly", event.computePayloadDigest().value, stored!!.payloadDigest)
            val state = db.outboxDao().getDeliveryState(event.companyId.value, event.clientEventId.value)
            assertNotNull("Delivery state must exist", state)
            assertEquals(OutboxStatus.PENDING.wireValue, state!!.status)
        }

        println(String.format(Locale.US, "[CANCELLATION] Prompt termination in %.2f ms at page %d (visited %d pages)",
            cancelDurationMs, cancelAtPage, visited))
    }

    @Test(timeout = 120_000)
    fun benchmark1k_readWorkspaceResponsiveness_qualifiedAsDbReadLatency() = runBlocking {
        Assume.assumeTrue(
            "Scale benchmark is opt-in. Set -D$PROPERTY_BENCHMARK_ENABLED=true or $ENV_BENCHMARK_ENABLED=true to run.",
            isBenchmarkEnabled()
        )

        val totalRecords = configuredRowCount()
        val crypto = JvmOutboxCrypto()
        val cipher = OutboxPayloadCipher(crypto)
        val db = OutboxDatabase.buildDatabase(context, dbFile.path, true)
        database = db
        val repo = OutboxRepositoryImpl(db, payloadCipher = cipher)

        seedCanonicalEncryptedQueue(repo, totalRecords)
        val inspector = DefaultOutboxHealthInspector(db, cipher, Dispatchers.IO)

        val startNs = System.nanoTime()
        val healthResult = inspector.inspectHealth(defaultCompanyId, defaultActorId, defaultDeviceId)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        assertTrue("Health inspection must be operational", healthResult.isOperational)
        assertEquals(OutboxHealthStatus.HEALTHY, healthResult.status)

        println(String.format(Locale.US,
            "[WORKSPACE RESPONSIVENESS] Background inspection roundtrip: %.2f ms across %d records",
            elapsedMs, totalRecords))
        println("[QUALIFICATION NOTE] This measurement represents JVM background I/O / SQLite read latency.")
        println("It does NOT measure Android Compose UI main-thread frame delivery or physical hardware display.")
    }
}
