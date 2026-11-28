package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.MerchantCommandStore
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.PaymentAllocationInput
import com.example.model.UserRole
import com.example.model.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
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

    private suspend fun withDatabase(block: suspend (OutboxDatabase) -> Unit) {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun customerCachedCompletionRecoversBeforeFirstDraftLookupCanRotate() = runBlocking {
        verifyCachedPaymentRecovery(supplier = false)
    }

    @Test
    fun supplierCachedCompletionRecoversBeforeFirstDraftLookupCanRotate() = runBlocking {
        verifyCachedPaymentRecovery(supplier = true)
    }

    private suspend fun verifyCachedPaymentRecovery(supplier: Boolean) {
        val form = if (supplier) "supplier_payment" else "customer_payment"
        val keyPrefix = if (supplier) "suppay" else "custpay"
        val partyField = if (supplier) "supplier_id" else "customer_id"
        val party = "01JCV8X9M2NQW3TYZ4R5S6789C"
        val otherParty = "01JCV8X9M2NQW3TYZ4R5S6789D"
        val cash = "01JCV8X9M2NQW3TYZ4R5S6789E"
        val target = "01JCV8X9M2NQW3TYZ4R5S6789F"
        val targetType = if (supplier) "purchase" else "invoice"
        val balanceField = if (supplier) "balance_owed" else "balance"
        val allocations = listOf(PaymentAllocationInput(targetType, target, BigDecimal("0.23")))
        val operator = UserSession(actorId.value, "Owner", "owner", UserRole.OWNER, "فرع", "Branch", false)
        val snapshot = """
            {"id":"payment-1","$partyField":"$party","amount_exact":"1.234567",
             "allocated_amount_exact":"0.234567","unallocated_amount_exact":"1.000000",
             "$balanceField":"8.765433","is_advance":false,
             "allocations":[{"target_type":"$targetType","target_id":"$target",
                             "amount_exact":"0.234567","allocated_amount_exact":"0.234567"}]}
        """.trimIndent()
        val malformed = if (supplier) {
            snapshot.replace("\"amount_exact\":\"0.234567\"", "\"amount_exact\":false")
        } else {
            snapshot.replace("\"unallocated_amount_exact\":\"1.000000\"", "\"unallocated_amount_exact\":1.000000")
        }
        val posts = mutableListOf<String>()
        val lookups = mutableListOf<String>()
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    val key = request.headers.getValue("Idempotency-Key")
                    posts.add(key)
                    val body = if (posts.size == 1) malformed else snapshot.replace("payment-1", "payment-2")
                    return TransportResponse(201, emptyMap(), body)
                }
                assertTrue(request.path.endsWith("/receipts/status"))
                val key = request.queryParams.getValue("idempotency_key")
                lookups.add(key)
                return TransportResponse(
                    200,
                    emptyMap(),
                    """{"status":"completed","command_type":"$form","outcome":"replay","idempotency_key":"$key","result":$snapshot,"response_snapshot":$snapshot}"""
                )
            }
        }

        fun repository(store: MerchantCommandStore) = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(transport, "https://api.example.com", tokenProvider = { validToken }),
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope }
        )

        suspend fun submit(
            repo: ProductionTraderRepository,
            id: String,
            partyId: String = party,
            amount: BigDecimal = BigDecimal("1.23")
        ): Result<*> = if (supplier) {
            repo.recordSupplierPayment(partyId, amount, "Test", operator, id, cash, "2026-11-28", allocations)
        } else {
            repo.recordCustomerPayment(partyId, amount, "Test", id, cash, "2026-11-28", allocations)
        }

        lateinit var operation: String
        lateinit var originalBytes: ByteArray
        lateinit var originalDigest: String
        withDatabase { db ->
            val store = RoomMerchantCommandStore(db, cryptoEngine)
            val repo = repository(store)
            try {
                operation = repo.getDraftOperationId(form)
                assertTrue(submit(repo, operation).isFailure)
                val original = store.findCommand(companyId.value, actorId.value, deviceId.value, "$keyPrefix-$operation")!!
                assertEquals(CommandStatus.UNKNOWN_ACK, original.status)
                originalBytes = store.decryptPayload(original)
                originalDigest = original.payloadDigest
                assertTrue(store.markCompleted(companyId.value, operation, malformed.toByteArray()))
                if (supplier) {
                    // Simulate the previous client having already rotated an empty intent.
                    assertNotEquals(operation, store.rotateDraftOperationId(companyId.value, actorId.value, deviceId.value, form))
                }
            } finally {
                repo.close()
            }
        }
        val key = "$keyPrefix-$operation"
        withDatabase { db1 ->
            withDatabase { db2 ->
                val store1 = RoomMerchantCommandStore(db1, cryptoEngine)
                val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
                val repo1 = repository(store1)
                val repo2 = repository(store2)
                try {
                    val stale = store2.findCommand(companyId.value, actorId.value, deviceId.value, key)!!
                    assertEquals(CommandStatus.COMPLETED, stale.status)
                    coroutineScope {
                        val first = async(Dispatchers.IO) { repo1.getDraftOperationId(form) }
                        val second = async(Dispatchers.IO) { repo2.getDraftOperationId(form) }
                        assertEquals(operation, first.await())
                        assertEquals(operation, second.await())
                    }
                    val recovered = repo2.getActiveDraftRecord(form)!!
                    assertEquals(CommandStatus.UNKNOWN_ACK, recovered.status)
                    assertEquals(key, recovered.idempotencyKey)
                    assertEquals(originalDigest, recovered.payloadDigest)
                    assertArrayEquals(originalBytes, store2.decryptPayload(recovered))
                    assertNull(recovered.responseSnapshot)
                    assertEquals(operation, repo1.rotateDraftOperationId(form))
                    repo1.cancelDraftOperation(form)
                    assertEquals(operation, repo2.getDraftOperationId(form))

                    assertTrue(submit(repo2, java.util.UUID.randomUUID().toString()).isFailure)
                    assertTrue(submit(repo2, "", partyId = otherParty).isFailure)
                    assertTrue(submit(repo1, operation, amount = BigDecimal("1.24")).isFailure)
                    assertEquals(listOf(key), posts)
                    assertTrue(lookups.isEmpty())

                    val valid = submit(repo2, operation)
                    assertTrue(valid.exceptionOrNull()?.message, valid.isSuccess)
                    assertEquals(listOf(key), lookups)
                    assertEquals(listOf(key), posts)
                    assertFalse(store1.reopenInvalidCompletedCommand(form, stale, "Stale validator"))
                    assertFalse(repo1.isDraftRecovering(form))

                    val next = repo2.getDraftOperationId(form)
                    assertNotEquals(operation, next)
                    assertEquals(next, repo1.getDraftOperationId(form))
                    assertTrue(submit(repo2, operation).isSuccess)
                    assertEquals(next, repo2.getDraftOperationId(form))
                    assertEquals(listOf(key), lookups)
                    assertEquals(listOf(key), posts)
                    if (supplier) {
                        assertEquals(BigDecimal("1.234567"), repo2.getSupplierPayments().single().amountExact)
                    } else {
                        assertEquals(BigDecimal("1.234567"), repo2.getCustomerTransactions().single().creditExact)
                    }
                    assertTrue(submit(repo2, next).isSuccess)
                    assertEquals(listOf(key, "$keyPrefix-$next"), posts)
                } finally {
                    repo1.close()
                    repo2.close()
                }
            }
        }
    }

    @Test
    fun concurrentCompletionCasHasOneWinnerAndValidCompletionRotatesAfterRestart() = runBlocking {
        val form = "customer_payment"
        val repoCompany = companyId.value
        val repoActor = actorId.value
        val repoDevice = deviceId.value
        val payload = """{"amount":"1.00","customer_id":"01JCV8X9M2NQW3TYZ4R5S6789C","notes":""}""".toByteArray()
        val digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val valid = """{"id":"payment-crash","amount_exact":"1.000000"}""".toByteArray()

        lateinit var operation: String
        withDatabase { db1 ->
            withDatabase { db2 ->
                val store1 = RoomMerchantCommandStore(db1, cryptoEngine)
                val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
                operation = store1.getDraftOperationId(repoCompany, repoActor, repoDevice, form)
                store1.recordPendingCommand(repoCompany, operation, repoActor, repoDevice, "customer.payment", "custpay-$operation", digest, payload)
                assertTrue(store1.markSubmitted(repoCompany, operation))
                assertTrue(store1.markCompleted(repoCompany, operation, """{"amount_exact":null}""".toByteArray()))

                val stale1 = store1.peekCompletedCommandForDraft(repoCompany, repoActor, repoDevice, form)!!
                val stale2 = store2.peekCompletedCommandForDraft(repoCompany, repoActor, repoDevice, form)!!
                val results = coroutineScope {
                    val first = async(Dispatchers.IO) { store1.reopenInvalidCompletedCommand(form, stale1, "First") }
                    val second = async(Dispatchers.IO) { store2.reopenInvalidCompletedCommand(form, stale2, "Second") }
                    listOf(first.await(), second.await())
                }
                assertEquals(1, results.count { it })
                assertEquals(CommandStatus.UNKNOWN_ACK, store2.getActiveDraftRecord(repoCompany, repoActor, repoDevice, form)?.status)
                assertTrue(store2.markCompleted(repoCompany, operation, valid))
                assertFalse(store1.reopenInvalidCompletedCommand(form, stale1, "Stale validator"))
            }
        }
        withDatabase { db ->
            val store = RoomMerchantCommandStore(db, cryptoEngine)
            val repo = ProductionTraderRepository.forTesting(
                apiClient = MobileMerchantApiClient(
                    transport = object : ReadClientTransport {
                        override fun execute(request: TransportRequest): TransportResponse = error("Unexpected network request")
                    },
                    baseOrigin = "https://api.example.com",
                    tokenProvider = { validToken }
                ),
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                commandStore = store,
                authScopeProvider = { validScope }
            )
            try {
                val next = repo.getDraftOperationId(form)
                assertNotEquals(operation, next)
                assertEquals(next, repo.getDraftOperationId(form))
                val completed = store.findCommand(repoCompany, repoActor, repoDevice, "custpay-$operation")!!
                assertEquals(CommandStatus.COMPLETED, completed.status)
                assertArrayEquals(valid, store.decryptSnapshot(completed))
            } finally {
                repo.close()
            }
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
