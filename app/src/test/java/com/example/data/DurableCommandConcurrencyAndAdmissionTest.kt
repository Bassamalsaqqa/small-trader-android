package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DurableCommandConcurrencyAndAdmissionTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val cryptoEngine = JvmAesGcmCryptoEngine()

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"
    private val validScope = CurrentAuthScope(companyId, actorId, deviceId, token = validToken, generation = 1L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_durable_concurrency_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()
        ProductionTraderRepository.clearInFlightForTest()
    }

    @After
    fun tearDown() {
        ProductionTraderRepository.clearInFlightForTest()
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @Test
    fun testPersistedSubmittedCommand_reopenRealRoom_concurrentDelayedPost404_exactlyOneOutboundPost() = runBlocking {
        // Step 1: Open real disk-backed Room DB
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        val commandId = "01JCV8X9M2NQW3TYZ4R5S67891"
        val idempotencyKey = "custcreate-$commandId"
        val payloadJson = """{"name_ar":"شركة الأقصى التجارية","phone":"0599112233","address_ar":"القدس"}"""
        val payloadBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)
        val payloadDigest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(payloadBytes).joinToString("") { "%02x".format(it) }

        // Record pending command and transition to SUBMITTED
        val rec1 = store1.recordPendingCommand(
            companyId = companyId.value,
            commandId = commandId,
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "customer_create",
            idempotencyKey = idempotencyKey,
            payloadDigest = payloadDigest,
            canonicalPayloadBytes = payloadBytes
        )
        assertEquals(CommandStatus.PENDING, rec1.status)
        assertTrue(store1.transitionStatus(companyId.value, commandId, CommandStatus.PENDING, CommandStatus.SUBMITTED))

        // Step 2: Simulate process death by closing DB connection
        db1.close()

        // Step 3: Reopen real disk-backed DB from same file (new process recovery)
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)

        val restoredRecord = store2.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertNotNull(restoredRecord)
        assertEquals(CommandStatus.SUBMITTED, restoredRecord!!.status)
        assertEquals(idempotencyKey, restoredRecord.idempotencyKey)
        val decryptedBytes = store2.decryptPayload(restoredRecord)
        assertEquals(payloadJson, String(decryptedBytes, StandardCharsets.UTF_8))

        // Step 4: Setup transport where receipt status check is delayed (50ms) and returns 404 (Not Found)
        val outboundPostCount = AtomicInteger(0)
        val mockTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.GET && request.path.contains("/receipts/status") -> {
                        // Simulate network latency on receipt status lookup
                        Thread.sleep(60)
                        TransportResponse(
                            statusCode = 404,
                            headers = mapOf("Content-Type" to "application/json"),
                            body = """{"error":{"code":"NOT_FOUND","message":"Not found"}}"""
                        )
                    }
                    request.method == HttpMethod.POST && request.path.contains("/customers") -> {
                        outboundPostCount.incrementAndGet()
                        assertEquals(idempotencyKey, request.headers["Idempotency-Key"])
                        TransportResponse(
                            statusCode = 201,
                            headers = emptyMap(),
                            body = """{"id":"01JCV8X9M2NQW3TYZ4R5S67891","name_ar":"شركة الأقصى التجارية","phone":"0599112233","address_ar":"القدس"}"""
                        )
                    }
                    else -> TransportResponse(statusCode = 200, emptyMap(), """{}""")
                }
            }
        }

        val apiClient = MobileMerchantApiClient(
            transport = mockTransport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        // Multiple repository instances sharing the same underlying store/scope
        val repo1 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope }
        )
        val repo2 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope }
        )

        // Step 5: Launch concurrent coroutines attempting to submit the same command
        val results = coroutineScope {
            val d1 = async {
                repo1.createCustomer(
                    name = "شركة الأقصى التجارية",
                    phone = "0599112233",
                    address = "القدس",
                    operationId = commandId
                )
            }
            val d2 = async {
                // Short delay to ensure d1 enters first while receipt check is in flight
                delay(15)
                repo2.createCustomer(
                    name = "شركة الأقصى التجارية",
                    phone = "0599112233",
                    address = "القدس",
                    operationId = commandId
                )
            }
            listOf(d1.await(), d2.await())
        }

        // Step 6: Exactly 1 mutating outbound request must have executed!
        assertEquals("Exactly 1 mutating outbound POST request must be made across concurrent delayed 404 calls", 1, outboundPostCount.get())

        // One call must have succeeded
        val successCount = results.count { it.isSuccess }
        assertTrue("At least one coroutine must succeed with the authoritative result", successCount >= 1)

        // The other coroutine either failed admission or succeeded without a second network POST
        val failureCount = results.count { it.isFailure }
        if (failureCount > 0) {
            val failure = results.first { it.isFailure }.exceptionOrNull()
            assertTrue(
                "Admission failure must indicate command is already being submitted",
                failure?.message?.contains("Command is already being submitted") == true
            )
        }

        // Room command store must now reflect COMPLETED
        val finalRecord = store2.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertNotNull(finalRecord)
        assertEquals(CommandStatus.COMPLETED, finalRecord!!.status)
        db2.close()
    }

    @Test
    fun testFailureOrCancellation_releasesInFlightLock_noPermanentLock() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val commandId = "01JCV8X9M2NQW3TYZ4R5S67892"
        var shouldFailNetwork = true

        val mockTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.GET && request.path.contains("/receipts/status") -> {
                        TransportResponse(
                            statusCode = 404,
                            headers = mapOf("Content-Type" to "application/json"),
                            body = """{"error":{"code":"NOT_FOUND","message":"Not found"}}"""
                        )
                    }
                    request.method == HttpMethod.POST && request.path.contains("/customers") -> {
                        if (shouldFailNetwork) {
                            throw RuntimeException("Simulated transient socket timeout")
                        } else {
                            TransportResponse(
                                statusCode = 201,
                                headers = emptyMap(),
                                body = """{"id":"01JCV8X9M2NQW3TYZ4R5S67892","name_ar":"تاجر النصر","phone":"0599000000"}"""
                            )
                        }
                    }
                    else -> TransportResponse(statusCode = 200, emptyMap(), """{}""")
                }
            }
        }

        val apiClient = MobileMerchantApiClient(
            transport = mockTransport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope }
        )

        // Attempt 1 fails with transient socket timeout
        val res1 = repo.createCustomer(
            name = "تاجر النصر",
            phone = "0599000000",
            address = "نابلس - رفيديا",
            creditLimitIls = 1000.0,
            operationId = commandId
        )
        assertTrue("Attempt 1 must fail due to network error", res1.isFailure)

        // Verify in-flight admission was released in finally (no permanent lock)
        val admissionKey = "${companyId.value}:${actorId.value}:${deviceId.value}:$commandId"
        assertEquals("In-flight lock must be released on failure", false, ProductionTraderRepository.isInFlight(companyId.value, actorId.value, deviceId.value, commandId))

        // Attempt 2 succeeds now that network is back
        shouldFailNetwork = false
        val res2 = repo.createCustomer(
            name = "تاجر النصر",
            phone = "0599000000",
            address = "نابلس - رفيديا",
            creditLimitIls = 1000.0,
            operationId = commandId
        )
        assertTrue("Attempt 2 must succeed without permanent lock: ${res2.exceptionOrNull()?.message}", res2.isSuccess)

        val completed = store.findCommand(companyId.value, actorId.value, deviceId.value, "custcreate-$commandId")
        assertNotNull(completed)
        assertEquals(CommandStatus.COMPLETED, completed!!.status)
        db.close()
    }
}
