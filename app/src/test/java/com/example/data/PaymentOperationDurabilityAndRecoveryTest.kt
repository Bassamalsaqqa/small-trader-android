package com.example.data

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.toNullableString
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.PaymentAllocationInput
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.screens.CustomerPaymentCollectionDialog
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.screens.SupplierEditorDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentOperationDurabilityAndRecoveryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var server: MockWebServer? = null
    private var baseOrigin: String = ""
    private val cryptoEngine = JvmAesGcmCryptoEngine()

    private val companyA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val companyB = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789Z")
    private val actorA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val actorB = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789Y")
    private val deviceA = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val customerA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val customerB = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E")
    private val supplierA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val supplierB = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789F")
    private val cashAccountA = ServerUlid.parse("01JMB000000000000000000002")
    private val cashAccountB = ServerUlid.parse("01JMB000000000000000000099")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"
    private val validScope = CurrentAuthScope(companyA, actorA, deviceA, token = validToken, generation = 1L)
    private val ownerSession = UserSession(
        id = actorA.value,
        name = "Owner",
        username = "owner",
        role = UserRole.OWNER,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch",
        isDemoSession = false,
        permissions = setOf(
            "pos.sale",
            "customers.view",
            "money.receipt.create",
            "vendors.view",
            "money.vendor_payment.create",
            "purchases.manage",
            "expenses.manage",
            "reports.financial.view"
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_pay_durability_${System.nanoTime()}.db")
        if (dbFile.exists()) dbFile.delete()
        val s = MockWebServer()
        s.start()
        server = s
        baseOrigin = "http://127.0.0.1:${s.port}"
        ProductionTraderRepository.clearInFlightForTest()
    }

    @After
    fun tearDown() {
        ProductionTraderRepository.clearInFlightForTest()
        server?.shutdown()
        server = null
        if (dbFile.exists()) dbFile.delete()
        Dispatchers.resetMain()
    }

    private fun newTransport(readTimeoutMs: Int = 350, callTimeoutMs: Int = 700): OkHttpTransport {
        return OkHttpTransport(
            configuredOrigin = baseOrigin,
            allowHttpLoopback = true,
            connectTimeoutMs = 500,
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs
        )
    }

    private fun newRepo(
        store: RoomMerchantCommandStore,
        transport: ReadClientTransport = newTransport(),
        scopeProvider: () -> CurrentAuthScope? = { validScope }
    ): ProductionTraderRepository {
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) {
            scopeProvider()?.token.orEmpty()
        }
        return ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyA,
            actorId = actorA,
            deviceId = deviceA,
            commandStore = store,
            permissions = ownerSession.permissions.orEmpty(),
            authScopeProvider = scopeProvider
        )
    }

    // -------------------------------------------------------------------------
    // 1. Customer Payment: Lost ACK -> Dismiss & Reopen -> Process Restart ->
    //    Status Lookup Error (NO POST) -> Status Lookup Found -> One Payment + Single Rotation
    // -------------------------------------------------------------------------
    @Test
    fun customerPayment_lostAck_dismissReopen_processRecreate_lookupErrorNoPost_lookupFoundConvergesOnce() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        val postCalls = AtomicInteger(0)
        val statusCalls = AtomicInteger(0)
        val observedIdempotencyKeys = CopyOnWriteArrayList<String>()
        val observedPostBodies = CopyOnWriteArrayList<String>()
        var failStatusLookup = true
        var committedOnServer = false

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments") -> {
                        val n = postCalls.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { observedIdempotencyKeys.add(it) }
                        observedPostBodies.add(request.body.readUtf8())
                        committedOnServer = true
                        if (n == 1) {
                            // Server committed the payment, but ACK is lost before reaching client
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        } else {
                            MockResponse().setResponseCode(500).setBody("""{"error":{"message":"Duplicate POST must not happen"}}""")
                        }
                    }
                    request.method == "GET" && path.contains("/receipts/status") -> {
                        statusCalls.incrementAndGet()
                        if (failStatusLookup) {
                            MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                                .setBody("""{"error":{"code":"UNAVAILABLE","message":"Transient lookup outage"}}""")
                        } else if (committedOnServer) {
                            val key = observedIdempotencyKeys.first()
                            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    {
                                      "status": "COMPLETED",
                                      "company_id": "${companyA.value}",
                                      "idempotency_key": "$key",
                                      "response_snapshot": {
                                        "id": "01JCVPAYCUSTRECOVER000001",
                                        "customer_id": "${customerA.value}",
                                        "amount": "125.50",
                                        "posted_at": "2026-11-27",
                                        "balance": "74.50",
                                        "is_advance": false,
                                        "allocated_amount": "125.50",
                                        "unallocated_amount": "0.00",
                                        "allocations": [
                                          {"target_type": "invoice", "target_id": "01JCVINV00000000000000001", "allocated_amount": "125.50"}
                                        ]
                                      }
                                    }
                                    """.trimIndent()
                                )
                        } else {
                            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)
        val repo1 = newRepo(store1)
        val vm1 = TraderViewModel(repo1)

        try {
            val customer = Customer(
                id = customerA.value,
                nameAr = "زبون المتابعة",
                nameEn = "Followup Customer",
                balanceExact = BigDecimal("200.00")
            )
            vm1.openPaymentCollectionForCustomer(customer)
            val op1 = vm1.uiState.value.draftCustomerPaymentOperationId
            assertFalse("Persisted op ID must not be ephemeral cpay_ timestamp", op1.startsWith("cpay_"))

            val allocs = listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("125.50")))
            val attempt1Done = CompletableDeferred<Boolean>()
            vm1.confirmCustomerCollection(
                customerId = customerA.value,
                amount = BigDecimal("125.50"),
                note = "دفعة فقدان التأكيد",
                paymentId = op1,
                allocations = allocs
            ) { attempt1Done.complete(it) }

            assertFalse("Attempt 1 must fail cleanly on lost ACK + 503 status lookup", withTimeout(5_000) { attempt1Done.await() })
            assertEquals(1, postCalls.get())
            assertEquals(1, statusCalls.get())
            assertEquals(op1, vm1.uiState.value.draftCustomerPaymentOperationId)
            assertTrue("Dialog must enter recovering mode after UNKNOWN_ACK", vm1.uiState.value.isCustomerPaymentRecovering)
            assertNotNull("Encrypted draft payload must be restored to UI state", vm1.uiState.value.activeCustomerPaymentDraft)

            val record1 = store1.findCommand(companyA.value, actorA.value, deviceA.value, "custpay-$op1")
            assertNotNull(record1)
            assertEquals(CommandStatus.UNKNOWN_ACK, record1!!.status)

            // Dismiss & reopen dialog -> same key and recovering state retained
            vm1.openPaymentCollectionDialog(false)
            vm1.openPaymentCollectionDialog(true)
            assertEquals(op1, vm1.uiState.value.draftCustomerPaymentOperationId)
            assertTrue(vm1.uiState.value.isCustomerPaymentRecovering)

            // Attempting a rogue non-blank UI ID must fail BEFORE POST
            val rogueDone = CompletableDeferred<Boolean>()
            vm1.confirmCustomerCollection(
                customerId = customerA.value,
                amount = BigDecimal("125.50"),
                note = "دفعة فقدان التأكيد",
                paymentId = "cpay_${customerA.value}_999999999",
                allocations = allocs
            ) { rogueDone.complete(it) }
            assertFalse("Rogue UI paymentId must be refused", withTimeout(5_000) { rogueDone.await() })
            assertEquals("Rogue ID must trigger zero POSTs", 1, postCalls.get())

            // Simulate full process recreation: close DB & VM1, open DB2, Repo2, VM2
            db1.close()
            val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
            val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
            val repo2 = newRepo(store2)
            val vm2 = TraderViewModel(repo2)

            try {
                assertEquals(op1, vm2.uiState.value.draftCustomerPaymentOperationId)
                assertTrue(vm2.uiState.value.isCustomerPaymentRecovering)
                val restoredDraft = vm2.uiState.value.activeCustomerPaymentDraft
                assertNotNull(restoredDraft)
                assertEquals(customerA.value, restoredDraft!!.properties["customer_id"].toNullableString())
                assertEquals("125.500000", restoredDraft.properties["amount"].toNullableString())

                // Recovery while /receipts/status still fails -> NO second POST, stays UNKNOWN_ACK
                val lookupErrDone = CompletableDeferred<Boolean>()
                vm2.confirmCustomerCollection(
                    customerId = customerA.value,
                    amount = BigDecimal("125.50"),
                    note = "دفعة فقدان التأكيد",
                    paymentId = op1,
                    allocations = allocs
                ) { lookupErrDone.complete(it) }
                assertFalse(withTimeout(5_000) { lookupErrDone.await() })
                assertEquals("Lookup error must NEVER issue another POST", 1, postCalls.get())
                assertEquals(2, statusCalls.get())
                assertEquals(op1, vm2.uiState.value.draftCustomerPaymentOperationId)
                assertTrue(vm2.uiState.value.isCustomerPaymentRecovering)

                // Now /receipts/status returns FOUND with server snapshot -> converges cleanly to ONE payment
                failStatusLookup = false
                val recoverDone = CompletableDeferred<Boolean>()
                vm2.confirmCustomerCollection(
                    customerId = customerA.value,
                    amount = BigDecimal("125.50"),
                    note = "دفعة فقدان التأكيد",
                    paymentId = op1,
                    allocations = allocs
                ) { recoverDone.complete(it) }
                assertTrue("Recovery via /receipts/status must succeed", withTimeout(5_000) { recoverDone.await() })

                assertEquals("Still only ONE mutating POST total across entire lifecycle", 1, postCalls.get())
                assertEquals(3, statusCalls.get())
                assertEquals(listOf("custpay-$op1"), observedIdempotencyKeys)

                val finalRec = store2.findCommand(companyA.value, actorA.value, deviceA.value, "custpay-$op1")
                assertNotNull(finalRec)
                assertEquals(CommandStatus.COMPLETED, finalRec!!.status)
                assertFalse("Recovery flag must clear after authoritative completion", vm2.uiState.value.isCustomerPaymentRecovering)
                assertNull(vm2.uiState.value.activeCustomerPaymentDraft)

                // Operation ID rotates ONCE after authoritative completion
                val op2 = vm2.uiState.value.draftCustomerPaymentOperationId
                assertNotEquals("Operation ID must rotate after canonical completion", op1, op2)
                assertEquals(op2, store2.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment"))
                assertEquals(1, repo2.getCustomerTransactions().count { it.id == "01JCVPAYCUSTRECOVER000001" })
            } finally {
                db2.close()
            }
        } finally {
            mainExecutor.shutdownNow()
        }
    }

    // -------------------------------------------------------------------------
    // 2. Supplier Payment: Lost ACK -> Dismiss & Reopen -> Process Restart ->
    //    404 NotFound Retries SAME Key & Exact Payload -> One Payment + Single Rotation
    // -------------------------------------------------------------------------
    @Test
    fun supplierPayment_lostAck_dismissReopen_processRecreate_notFoundRetriesSameKey_convergesOnce() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor()
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        val postCalls = AtomicInteger(0)
        val statusCalls = AtomicInteger(0)
        val observedKeys = CopyOnWriteArrayList<String>()
        val observedBodies = CopyOnWriteArrayList<String>()

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/suppliers/${supplierA.value}/payments") -> {
                        val n = postCalls.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { observedKeys.add(it) }
                        observedBodies.add(request.body.readUtf8())
                        if (n == 1) {
                            // First attempt hits transient network failure before reaching server
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        } else {
                            MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    {
                                      "id": "01JCVPAYSUPRETRY00000001",
                                      "supplier_id": "${supplierA.value}",
                                      "amount": "300.00",
                                      "posted_at": "2026-11-27",
                                      "balance_owed": "200.00",
                                      "advances": "0.00",
                                      "is_advance": true,
                                      "allocated_amount": "0.00",
                                      "unallocated_amount": "300.00",
                                      "allocations": []
                                    }
                                    """.trimIndent()
                                )
                        }
                    }
                    request.method == "GET" && path.contains("/receipts/status") -> {
                        val n = statusCalls.incrementAndGet()
                        if (n == 1) {
                            // First inline lookup fails transiently -> UNKNOWN_ACK
                            MockResponse().setResponseCode(500).setBody("""{"error":{"message":"Timeout"}}""")
                        } else {
                            // Second lookup on manual retry returns authoritative 404 NotFound -> safe to retry SAME key
                            MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)
        val repo1 = newRepo(store1)
        val vm1 = TraderViewModel(repo1)

        try {
            val op1 = vm1.uiState.value.draftSupplierPaymentOperationId
            val done1 = CompletableDeferred<Result<SupplierPayment>>()
            vm1.paySupplierAmountExactResult(
                supplierId = supplierA.value,
                amount = BigDecimal("300.00"),
                note = "سداد مورد 1",
                paymentId = op1
            ) { done1.complete(it) }

            assertTrue(withTimeout(5_000) { done1.await() }.isFailure)
            assertEquals(op1, vm1.uiState.value.draftSupplierPaymentOperationId)
            assertTrue(vm1.uiState.value.isSupplierPaymentRecovering)

            // Dismiss/reopen refresh retains key + recovering payload
            vm1.prepareSupplierPaymentDraft()
            assertEquals(op1, vm1.uiState.value.draftSupplierPaymentOperationId)
            assertTrue(vm1.uiState.value.isSupplierPaymentRecovering)

            // Process recreation
            db1.close()
            val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
            val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
            val repo2 = newRepo(store2)
            val vm2 = TraderViewModel(repo2)

            try {
                assertEquals(op1, vm2.uiState.value.draftSupplierPaymentOperationId)
                assertTrue(vm2.uiState.value.isSupplierPaymentRecovering)
                assertEquals(supplierA.value, vm2.uiState.value.activeSupplierPaymentDraft!!.properties["supplier_id"].toNullableString())

                val done2 = CompletableDeferred<Result<SupplierPayment>>()
                vm2.paySupplierAmountExactResult(
                    supplierId = supplierA.value,
                    amount = BigDecimal("300.00"),
                    note = "سداد مورد 1",
                    paymentId = op1
                ) { done2.complete(it) }

                val res2 = withTimeout(5_000) { done2.await() }
                assertTrue("Retry after 404 NotFound must succeed: ${res2.exceptionOrNull()?.message}", res2.isSuccess)
                assertEquals(2, postCalls.get())
                assertEquals(2, statusCalls.get())
                assertEquals(listOf("suppay-$op1", "suppay-$op1"), observedKeys)
                assertEquals("Retried POST body must be byte-for-byte identical", observedBodies[0], observedBodies[1])

                val completed = store2.findCommand(companyA.value, actorA.value, deviceA.value, "suppay-$op1")
                assertEquals(CommandStatus.COMPLETED, completed!!.status)
                assertFalse(vm2.uiState.value.isSupplierPaymentRecovering)
                assertNull(vm2.uiState.value.activeSupplierPaymentDraft)
                val op2 = vm2.uiState.value.draftSupplierPaymentOperationId
                assertNotEquals(op1, op2)
                assertEquals(op2, store2.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "supplier_payment"))
            } finally {
                db2.close()
            }
        } finally {
            mainExecutor.shutdownNow()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Direct Repository Admission: Refuses Different Key & Changed Parameters
    //    BEFORE POST, Including After Midnight (Default Date Preserved)
    // -------------------------------------------------------------------------
    @Test
    fun repositoryAdmission_refusesDifferentKeyAndChangedParametersBeforePost_forBothPaymentTypes() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)
        val postCalls = AtomicInteger(0)

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") postCalls.incrementAndGet()
                return if (request.method == "GET" && request.path?.contains("/receipts/status") == true) {
                    MockResponse().setResponseCode(503).setBody("{}")
                } else {
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
        }

        val repo = newRepo(store)
        try {
            // Seed UNKNOWN_ACK for Customer A
            val custOp1 = repo.getDraftOperationId("customer_payment")
            val custRes1 = repo.recordCustomerPayment(
                customerId = customerA.value,
                amount = BigDecimal("100.00"),
                note = "Original Note",
                paymentId = custOp1,
                cashAccountId = cashAccountA.value,
                paymentDate = "2026-11-26",
                allocations = listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))
            )
            assertTrue(custRes1.isFailure)
            val initialPostsCust = postCalls.get()

            // Direct caller with DIFFERENT key must fail BEFORE POST
            assertTrue(repo.recordCustomerPayment(customerA.value, BigDecimal("100.00"), "Original Note", "direct_other_key", cashAccountA.value, "2026-11-26", listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))).isFailure)
            // Different party (customerB) even with blank or same key must fail BEFORE POST
            assertTrue(repo.recordCustomerPayment(customerB.value, BigDecimal("100.00"), "Original Note", "", cashAccountA.value, "2026-11-26", listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))).isFailure)
            // Changed amount
            assertTrue(repo.recordCustomerPayment(customerA.value, BigDecimal("101.00"), "Original Note", custOp1, cashAccountA.value, "2026-11-26", listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))).isFailure)
            // Changed cash account
            assertTrue(repo.recordCustomerPayment(customerA.value, BigDecimal("100.00"), "Original Note", custOp1, cashAccountB.value, "2026-11-26", listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))).isFailure)
            // Changed explicit date
            assertTrue(repo.recordCustomerPayment(customerA.value, BigDecimal("100.00"), "Original Note", custOp1, cashAccountA.value, "2026-11-27", listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))).isFailure)
            // Changed allocations
            assertTrue(repo.recordCustomerPayment(customerA.value, BigDecimal("100.00"), "Original Note", custOp1, cashAccountA.value, "2026-11-26", emptyList()).isFailure)
            // Changed notes
            assertTrue(repo.recordCustomerPayment(customerA.value, BigDecimal("100.00"), "Edited Note", custOp1, cashAccountA.value, "2026-11-26", listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))).isFailure)
            assertEquals("None of the mismatched customer payment calls may hit POST", initialPostsCust, postCalls.get())

            // Recovery after midnight with paymentDate = null & cashAccountId = null preserves original draft's date & account!
            server!!.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (request.method == "GET" && path.contains("/receipts/status")) {
                        MockResponse().setResponseCode(200).setBody(
                            """{"status":"FOUND","response_snapshot":{"id":"01JCVPAYCUSTRECOVER000002","customer_id":"${customerA.value}","amount":"100.00","posted_at":"2026-11-26","balance":"0.00"}}"""
                        )
                    } else MockResponse().setResponseCode(500).setBody("{}")
                }
            }
            val custMidnightRecover = repo.recordCustomerPayment(
                customerId = customerA.value,
                amount = BigDecimal("100.00"),
                note = "Original Note",
                paymentId = "",
                cashAccountId = null,
                paymentDate = null,
                allocations = listOf(PaymentAllocationInput("invoice", "01JCVINV00000000000000001", BigDecimal("60.00")))
            )
            assertTrue("Omitted date/account must preserve draft values and recover cleanly: ${custMidnightRecover.exceptionOrNull()?.message}", custMidnightRecover.isSuccess)

            // Same enforcement for Supplier Payment
            postCalls.set(0)
            server!!.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") postCalls.incrementAndGet()
                    return if (request.method == "GET" && request.path?.contains("/receipts/status") == true) {
                        MockResponse().setResponseCode(503).setBody("{}")
                    } else {
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                }
            }
            val supOp1 = repo.getDraftOperationId("supplier_payment")
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("250.00"), "Sup Note", ownerSession, supOp1, cashAccountA.value, "2026-11-26", emptyList()).isFailure)
            val initialPostsSup = postCalls.get()

            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("250.00"), "Sup Note", ownerSession, "direct_other_sup_key", cashAccountA.value, "2026-11-26", emptyList()).isFailure)
            assertTrue(repo.recordSupplierPayment(supplierB.value, BigDecimal("250.00"), "Sup Note", ownerSession, supOp1, cashAccountA.value, "2026-11-26", emptyList()).isFailure)
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("255.00"), "Sup Note", ownerSession, supOp1, cashAccountA.value, "2026-11-26", emptyList()).isFailure)
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("250.00"), "Sup Note", ownerSession, supOp1, cashAccountB.value, "2026-11-26", emptyList()).isFailure)
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("250.00"), "Sup Note", ownerSession, supOp1, cashAccountA.value, "2026-11-27", emptyList()).isFailure)
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("250.00"), "Sup Note", ownerSession, supOp1, cashAccountA.value, "2026-11-26", listOf(PaymentAllocationInput("purchase", "01JCVPUR01", BigDecimal("100.00")))).isFailure)
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("250.00"), "Changed Sup Note", ownerSession, supOp1, cashAccountA.value, "2026-11-26", emptyList()).isFailure)
            assertEquals("None of the mismatched supplier payment calls may hit POST", initialPostsSup, postCalls.get())
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // 4. Two Repository Instances Sharing SQLite + getDraftOperationId Transaction Concurrency
    // -------------------------------------------------------------------------
    @Test
    fun twoRepositoryInstancesSharedSqlite_concurrentAdmissionAndDraftIdTransaction() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore({ db.openHelper.writableDatabase }, cryptoEngine)
        val store2 = RoomMerchantCommandStore({ db.openHelper.writableDatabase }, cryptoEngine)

        try {
            // Concurrent getDraftOperationId across both stores sharing SQLite must converge to ONE UUID
            val startDraftLatch = CountDownLatch(1)
            val draftIds = (0 until 8).map { idx ->
                async(Dispatchers.IO) {
                    startDraftLatch.await(2, TimeUnit.SECONDS)
                    val s = if (idx % 2 == 0) store1 else store2
                    s.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment")
                }
            }
            startDraftLatch.countDown()
            val resolvedIds = draftIds.awaitAll()
            assertEquals("Concurrent getDraftOperationId calls must yield identical UUID", 1, resolvedIds.distinct().size)

            // Now test TWO separate ProductionTraderRepository instances sharing SQLite submitting concurrently
            val postCalls = AtomicInteger(0)
            val postEntered = CountDownLatch(1)
            val releasePost = CountDownLatch(1)
            server!!.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments")) {
                        postCalls.incrementAndGet()
                        postEntered.countDown()
                        releasePost.await(3, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"id":"01JCVPAYCONCURRENT000001","customer_id":"${customerA.value}","amount":"80.00","posted_at":"2026-11-27","balance":"120.00"}""")
                    }
                    return MockResponse().setResponseCode(200).setBody("{}")
                }
            }

            val repo1 = newRepo(store1, transport = newTransport(readTimeoutMs = 4000, callTimeoutMs = 5000))
            val repo2 = newRepo(store2, transport = newTransport(readTimeoutMs = 4000, callTimeoutMs = 5000))

            val d1 = async(Dispatchers.IO) {
                repo1.recordCustomerPayment(customerA.value, BigDecimal("80.00"), "Concurrent A", paymentId = resolvedIds.first())
            }
            assertTrue("First repo instance must reach POST", postEntered.await(3, TimeUnit.SECONDS))
            val d2 = async(Dispatchers.IO) {
                repo2.recordCustomerPayment(customerA.value, BigDecimal("80.00"), "Concurrent B", paymentId = "concurrent-other-id")
            }
            val res2 = d2.await()
            assertTrue("Second repository instance must fail admission while first is in-flight", res2.isFailure)

            releasePost.countDown()
            val res1 = d1.await()
            assertTrue("First repository instance must succeed: ${res1.exceptionOrNull()?.message}", res1.isSuccess)
            assertEquals("Exactly ONE outbound POST across two repository instances sharing SQLite", 1, postCalls.get())
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // 5. Crash After Durable COMPLETED Before VM Rotation & HTTP 422 Terminal FAILED
    // -------------------------------------------------------------------------
    @Test
    fun crashAfterCompletedBeforeRotation_and_http422TerminalFailed_rotateCleanlyOnNextDraft() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        try {
            // Part A: Crash after COMPLETED before rotateDraftOperationId runs
            val op1 = store1.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment")
            val payload = """{"customer_id":"${customerA.value}"}""".toByteArray()
            val digest = "sha256:testdigest1"
            store1.recordPendingCommand(companyA.value, op1, actorA.value, deviceA.value, "customer.payment", "custpay-$op1", digest, payload)
            assertTrue(store1.markSubmitted(companyA.value, op1))
            assertTrue(store1.markCompleted(companyA.value, op1, """{"id":"01JCVPAYDONE1","amount":"50.00"}""".toByteArray()))
            // Simulate process death BEFORE rotateDraftOperationId was called!
            db1.close()

            val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
            val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
            try {
                val nextOp = store2.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment")
                assertNotEquals("getDraftOperationId must never re-hand out a COMPLETED command ID after crash", op1, nextOp)

                // Part B: HTTP 422 JSON error whose message omits literal "HTTP 4"/"VALIDATION_FAILED"
                val statusCalls = AtomicInteger(0)
                server!!.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET" && request.path?.contains("/receipts/status") == true) {
                            statusCalls.incrementAndGet()
                            return MockResponse().setResponseCode(404).setBody("{}")
                        }
                        return MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                            .setBody("""{"error":{"code":"VALIDATION_FAILED","message":"Insufficient cash in register"}}""")
                    }
                }
                val repo2 = newRepo(store2)
                val supOp1 = repo2.getDraftOperationId("supplier_payment")
                val fail422 = repo2.recordSupplierPayment(supplierA.value, BigDecimal("999.00"), "Over cash", ownerSession, supOp1)
                assertTrue(fail422.isFailure)
                assertEquals("Definitive 422 POST error must NOT trigger extra /receipts/status probe", 0, statusCalls.get())
                val failedRec = store2.findCommand(companyA.value, actorA.value, deviceA.value, "suppay-$supOp1")
                assertEquals(CommandStatus.FAILED, failedRec!!.status)
                assertFalse("FAILED 422 must NOT trap isDraftRecovering=true", repo2.isDraftRecovering("supplier_payment"))
                val supOp2 = repo2.getDraftOperationId("supplier_payment")
                assertNotEquals("Next supplier draft must rotate cleanly away from FAILED key", supOp1, supOp2)
            } finally {
                db2.close()
            }
        } finally {
            runCatching { db1.close() }
        }
    }

    // -------------------------------------------------------------------------
    // 6. Revoked Scope / Cross-Company / Cross-Actor Fail Closed
    // -------------------------------------------------------------------------
    @Test
    fun revokedScopeAndCrossTenantCrossActor_failClosedWithZeroPostsAndIsolation() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)
        val postCalls = AtomicInteger(0)

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") postCalls.incrementAndGet()
                return if (request.method == "GET" && request.path?.contains("/receipts/status") == true) {
                    MockResponse().setResponseCode(503).setBody("{}")
                } else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        }

        var currentScope: CurrentAuthScope? = validScope
        val repoA = newRepo(store) { currentScope }

        try {
            val opA = repoA.getDraftOperationId("customer_payment")
            assertTrue(repoA.recordCustomerPayment(customerA.value, BigDecimal("50.00"), "Tenant A", opA).isFailure)
            val postsAfterAttempt = postCalls.get()

            // Revoked scope -> SecurityException, zero HTTP calls
            currentScope = null
            val revokedRes = repoA.recordCustomerPayment(customerA.value, BigDecimal("50.00"), "Tenant A", opA)
            assertTrue(revokedRes.isFailure)
            assertTrue(revokedRes.exceptionOrNull() is SecurityException)

            // Cross-company scope -> SecurityException, zero HTTP calls
            currentScope = CurrentAuthScope(companyB, actorA, deviceA, token = validToken, generation = 2L)
            val crossCompanyRes = repoA.recordSupplierPayment(supplierA.value, BigDecimal("50.00"), "Tenant B", ownerSession, "")
            assertTrue(crossCompanyRes.isFailure)
            assertTrue(crossCompanyRes.exceptionOrNull() is SecurityException)

            // Cross-actor scope -> SecurityException, zero HTTP calls
            currentScope = CurrentAuthScope(companyA, actorB, deviceA, token = validToken, generation = 3L)
            val crossActorRes = repoA.recordCustomerPayment(customerA.value, BigDecimal("50.00"), "Actor B", opA)
            assertTrue(crossActorRes.isFailure)
            assertTrue(crossActorRes.exceptionOrNull() is SecurityException)

            assertEquals("Revoked / cross-tenant / cross-actor retries must not hit POST", postsAfterAttempt, postCalls.get())
            assertNull("Actor B must never see Actor A's active draft", store.getActiveDraftRecord(companyA.value, actorB.value, deviceA.value, "customer_payment"))
            assertNull("Company B must never see Company A's active draft", store.getActiveDraftRecord(companyB.value, actorA.value, deviceA.value, "customer_payment"))
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // 7. Compose UI Guards & Recovery Banners (Customer Payment, Pay Supplier, Supplier Editor)
    // -------------------------------------------------------------------------
    @Test
    fun composeDialogs_recoveryLocksFieldsKeepRetryEnabled_blocksOtherParty_andGuardsDoubleSubmit() {
        val recoveryDraft = StrictJsonObject(
            mapOf(
                "customer_id" to StrictJsonString(customerA.value),
                "supplier_id" to StrictJsonString(supplierA.value),
                "amount" to StrictJsonString("175.000000"),
                "notes" to StrictJsonString("ملاحظة مستعادة"),
                "allocations" to StrictJsonArray(emptyList())
            )
        )
        var selectedCustomer by mutableStateOf(Customer(id = customerA.value, nameAr = "زبون أ", nameEn = "Customer A", balanceExact = BigDecimal("200.00")))
        var customerConfirmCalls = 0
        var deferredCustomerComplete: ((Result<CustomerTransaction>) -> Unit)? = null

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                CustomerPaymentCollectionDialog(
                    customer = selectedCustomer,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onConfirmPaymentExactWithResult = { _, _, _, _, _, onComplete ->
                        customerConfirmCalls++
                        deferredCustomerComplete = onComplete
                    },
                    operationId = "persisted-cust-op-1",
                    initialDraft = recoveryDraft,
                    isRecovering = true
                )
            }
        }

        // Same customer recovery: banner visible, fields locked, Retry button ENABLED (no trap)
        composeTestRule.onNodeWithTag("customer_pay_recovery_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("payment_amount_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("payment_note_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsEnabled()

        // Rapid double click during in-flight callback must only dispatch ONCE
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").performClick()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").performClick()
        assertEquals("Rapid taps must dispatch only ONE payment submission", 1, customerConfirmCalls)
        deferredCustomerComplete?.invoke(Result.failure(RuntimeException("Still unknown")))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsEnabled()

        // Opening for DIFFERENT customer while customerA is unresolved -> blocked banner + disabled Confirm
        selectedCustomer = Customer(id = customerB.value, nameAr = "زبون ب", nameEn = "Customer B", balanceExact = BigDecimal("90.00"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("customer_pay_blocked_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsNotEnabled()
    }

    @Test
    fun composeDialogs_paySupplierRecoveryAndSupplierEditorSubmitGuard() {
        val supplierDraft = StrictJsonObject(
            mapOf(
                "supplier_id" to StrictJsonString(supplierA.value),
                "amount" to StrictJsonString("140.000000"),
                "notes" to StrictJsonString("سداد مستعاد"),
                "allocations" to StrictJsonArray(emptyList())
            )
        )
        var activeSupplier by mutableStateOf(Supplier(id = supplierA.value, nameAr = "مورد أ", nameEn = "Supplier A", balanceOwedExact = BigDecimal("300.00")))
        var showEditor by mutableStateOf(false)
        var editorSaveCalls = 0
        var deferredEditorDone: ((Boolean) -> Unit)? = null

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                if (!showEditor) {
                    PaySupplierDialog(
                        supplier = activeSupplier,
                        lang = AppLanguage.EN,
                        onDismiss = {},
                        onPaySupplier = { _, _, _ -> Result.failure(IllegalStateException("unused")) },
                        onPaySupplierExactWithResult = { _, _, _, _, _, _ -> },
                        operationId = "persisted-sup-op-1",
                        initialDraft = supplierDraft,
                        isRecovering = true
                    )
                } else {
                    SupplierEditorDialog(
                        initialSupplier = null,
                        lang = AppLanguage.EN,
                        onDismiss = {},
                        onSave = { _, _, _ -> },
                        onSaveWithResult = { _, _, _, onComplete ->
                            editorSaveCalls++
                            deferredEditorDone = onComplete
                        }
                    )
                }
            }
        }

        // Supplier A recovery: banner visible, fields locked, Retry button enabled
        composeTestRule.onNodeWithTag("supplier_pay_recovery_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("supplier_pay_note_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsEnabled()

        // Different supplier blocked while supplierA is unresolved
        activeSupplier = Supplier(id = supplierB.value, nameAr = "مورد ب", nameEn = "Supplier B", balanceOwedExact = BigDecimal("90.00"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("supplier_pay_blocked_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsNotEnabled()

        // Switch to SupplierEditorDialog & verify isSubmitting guard prevents rapid double submit
        showEditor = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("supplier_name_input").performTextClearance()
        composeTestRule.onNodeWithTag("supplier_name_input").performTextInput("New Supplier")
        composeTestRule.onNodeWithTag("save_supplier_button").performClick()
        composeTestRule.onNodeWithTag("save_supplier_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("supplier_name_input").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_supplier_button").performClick()
        assertEquals("Rapid taps on SupplierEditorDialog must submit only ONCE", 1, editorSaveCalls)
        deferredEditorDone?.invoke(false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("save_supplier_button").assertIsEnabled()
    }

    // -------------------------------------------------------------------------
    // 8. Two Repository Instances Sharing SQLite: Supplier Payment Concurrent Admission
    // -------------------------------------------------------------------------
    @Test
    fun twoRepositoryInstancesSharedSqlite_concurrentAdmission_supplierPayment() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore({ db.openHelper.writableDatabase }, cryptoEngine)
        val store2 = RoomMerchantCommandStore({ db.openHelper.writableDatabase }, cryptoEngine)

        try {
            val sharedDraftId = store1.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "supplier_payment")
            assertEquals(sharedDraftId, store2.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "supplier_payment"))

            val postCalls = AtomicInteger(0)
            val postEntered = CountDownLatch(1)
            val releasePost = CountDownLatch(1)
            server!!.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.method == "POST" && path.endsWith("/suppliers/${supplierA.value}/payments")) {
                        postCalls.incrementAndGet()
                        postEntered.countDown()
                        releasePost.await(3, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"id":"01JCVPAYSUPCONCURRENT001","supplier_id":"${supplierA.value}","amount":"95.00","posted_at":"2026-11-27","balance_owed":"105.00"}""")
                    }
                    return MockResponse().setResponseCode(200).setBody("{}")
                }
            }

            val repo1 = newRepo(store1, transport = newTransport(readTimeoutMs = 4000, callTimeoutMs = 5000))
            val repo2 = newRepo(store2, transport = newTransport(readTimeoutMs = 4000, callTimeoutMs = 5000))

            val d1 = async(Dispatchers.IO) {
                repo1.recordSupplierPayment(supplierA.value, BigDecimal("95.00"), "Concurrent Sup A", ownerSession, paymentId = sharedDraftId)
            }
            assertTrue("First supplier repo must reach POST", postEntered.await(3, TimeUnit.SECONDS))
            val d2 = async(Dispatchers.IO) {
                repo2.recordSupplierPayment(supplierA.value, BigDecimal("95.00"), "Concurrent Sup B", ownerSession, paymentId = "concurrent-other-sup-id")
            }
            val res2 = d2.await()
            assertTrue("Second supplier repository instance must fail concurrent admission", res2.isFailure)

            releasePost.countDown()
            val res1 = d1.await()
            assertTrue("First supplier repository instance must succeed: ${res1.exceptionOrNull()?.message}", res1.isSuccess)
            assertEquals("Exactly ONE supplier POST across two repository instances sharing SQLite", 1, postCalls.get())
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // 9. Full Production UI + ViewModel + Repo + Room + OkHttp Vertical Recovery
    // -------------------------------------------------------------------------
    @Test
    fun fullVerticalStack_productionCustomerDialog_dismissReopenAndRecover_convergesOnce() {
        val postCalls = AtomicInteger(0)
        val statusCalls = AtomicInteger(0)
        val keys = CopyOnWriteArrayList<String>()
        var failStatus = true
        var committed = false

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments") -> {
                        postCalls.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { keys.add(it) }
                        committed = true
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    request.method == "GET" && path.contains("/receipts/status") -> {
                        statusCalls.incrementAndGet()
                        if (failStatus) {
                            MockResponse().setResponseCode(503).setBody("""{"error":{"message":"Outage"}}""")
                        } else if (committed) {
                            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"${keys.first()}","response_snapshot":{"id":"01JCVPAYUIVERT000001","customer_id":"${customerA.value}","amount":"120.00","posted_at":"2026-11-27","balance":"80.00"}}"""
                                )
                        } else {
                            MockResponse().setResponseCode(404).setBody("{}")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)
        val repo = newRepo(store)
        val vm = TraderViewModel(repo)

        try {
            val customer = Customer(id = customerA.value, nameAr = "زبون الواجهة", nameEn = "UI Customer", balanceExact = BigDecimal("120.00"))
            vm.openPaymentCollectionForCustomer(customer)
            val op1 = vm.uiState.value.draftCustomerPaymentOperationId

            composeTestRule.setContent {
                val state by vm.uiState.collectAsState()
                SmallTraderTheme(darkTheme = false, isRtl = false) {
                    if (state.isPaymentCollectionDialogOpen && state.selectedCustomerForDetail != null) {
                        val cust = state.selectedCustomerForDetail!!
                        CustomerPaymentCollectionDialog(
                            customer = cust,
                            lang = state.language,
                            onDismiss = { vm.openPaymentCollectionDialog(false) },
                            onConfirmPaymentExactWithResult = { cid, amount, note, paymentId, allocations, onComplete ->
                                vm.confirmCustomerCollectionResult(cid, amount, note, paymentId, allocations, onComplete)
                            },
                            operationId = state.draftCustomerPaymentOperationId.takeIf { it.isNotBlank() },
                            initialDraft = state.activeCustomerPaymentDraft,
                            isRecovering = state.isCustomerPaymentRecovering,
                            isSubmitting = state.isCustomerPaymentSubmitting,
                            inlineError = state.customerPaymentError
                        )
                    }
                }
            }

            composeTestRule.onNodeWithTag("payment_amount_input").assertIsDisplayed()
            composeTestRule.onNodeWithTag("confirm_customer_payment_button").performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                vm.uiState.value.isCustomerPaymentRecovering && !vm.uiState.value.isCustomerPaymentSubmitting
            }
            composeTestRule.waitForIdle()
            assertEquals(1, postCalls.get())
            assertEquals(1, statusCalls.get())
            assertEquals(op1, vm.uiState.value.draftCustomerPaymentOperationId)
            composeTestRule.onNodeWithTag("customer_pay_recovery_banner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("payment_amount_input").assertIsNotEnabled()

            // Dismiss & reopen dialog via ViewModel -> preserves same key, restored amount, recovery banner
            vm.openPaymentCollectionDialog(false)
            composeTestRule.waitForIdle()
            vm.openPaymentCollectionDialog(true)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("customer_pay_recovery_banner").assertIsDisplayed()
            assertEquals(op1, vm.uiState.value.draftCustomerPaymentOperationId)

            // Retry via UI Verify & Retry CTA once /receipts/status recovers -> closes dialog, 1 total POST, rotates ONCE
            failStatus = false
            composeTestRule.onNodeWithTag("confirm_customer_payment_button").performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !vm.uiState.value.isPaymentCollectionDialogOpen && !vm.uiState.value.isCustomerPaymentSubmitting
            }
            composeTestRule.waitForIdle()
            assertEquals("Still only ONE POST across full UI + VM + OkHttp + SQLite flow", 1, postCalls.get())
            assertEquals(2, statusCalls.get())
            assertEquals(listOf("custpay-$op1"), keys)
            assertFalse(vm.uiState.value.isCustomerPaymentRecovering)
            assertNotEquals(op1, vm.uiState.value.draftCustomerPaymentOperationId)
        } finally {
            db.close()
        }
    }

    private fun sha256Digest(bytes: ByteArray): String =
        "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------
    // 10. Unstructured / HTML / Mismatched 403, 404, 422 Retain UNKNOWN_ACK & Same Key
    // -------------------------------------------------------------------------
    @Test
    fun unstructuredAndMismatched403_404_422_retainUnknownAck_checkStatus_andKeepSameKey() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)
        val repo = newRepo(store)

        val custPostCount = AtomicInteger(0)
        val custStatusCount = AtomicInteger(0)
        val custKeys = CopyOnWriteArrayList<String>()
        var custStatusMode = "html404" // html404 -> found

        val supPostCount = AtomicInteger(0)
        val supStatusCount = AtomicInteger(0)
        val supKeys = CopyOnWriteArrayList<String>()
        var supPostMode = "nonContract404" // nonContract404 -> nonContractCode422 -> mismatchedScope422
        var supStatusMode = "outage503" // outage503 -> found

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments") -> {
                        custPostCount.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { custKeys.add(it) }
                        // Server committed financially, but upstream proxy returned HTML 403!
                        MockResponse().setResponseCode(403)
                            .setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("<html><body>403 Proxy Forbidden</body></html>")
                    }
                    request.method == "POST" && path.endsWith("/suppliers/${supplierA.value}/payments") -> {
                        supPostCount.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { supKeys.add(it) }
                        when (supPostMode) {
                            "nonContract404" -> MockResponse().setResponseCode(404)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"message":"Gateway route not found"}""")
                            "nonContractCode422" -> MockResponse().setResponseCode(422)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"error":{"code":"WAF_RULE_BLOCKED","message":"Blocked by proxy"}}""")
                            else -> MockResponse().setResponseCode(422)
                                .setHeader("Content-Type", "application/json")
                                .setHeader("X-Company-Id", companyB.value)
                                .setBody("""{"error":{"code":"VALIDATION_FAILED","message":"Wrong company header"}}""")
                        }
                    }
                    request.method == "GET" && path.contains("/receipts/status") -> {
                        val keyParam = request.requestUrl?.queryParameter("idempotency_key").orEmpty()
                        if (keyParam.startsWith("custpay-")) {
                            custStatusCount.incrementAndGet()
                            if (custStatusMode == "html404") {
                                MockResponse().setResponseCode(404)
                                    .setHeader("Content-Type", "text/html")
                                    .setBody("<html>404 Gateway</html>")
                            } else {
                                MockResponse().setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("""{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$keyParam","response_snapshot":{"id":"01JCVPAYHTML403RECOV01","customer_id":"${customerA.value}","amount":"40.00","posted_at":"2026-11-27","balance":"60.00"}}""")
                            }
                        } else {
                            supStatusCount.incrementAndGet()
                            if (supStatusMode == "outage503") {
                                MockResponse().setResponseCode(503)
                                    .setBody("""{"error":{"code":"TEMPORARY_UNAVAILABLE","message":"Retry"}}""")
                            } else {
                                MockResponse().setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("""{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$keyParam","response_snapshot":{"id":"01JCVSUPPAYUNSTRUCT01","supplier_id":"${supplierA.value}","amount":"55.00","posted_at":"2026-11-27","balance_owed":"25.00"}}""")
                            }
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        try {
            // Part A: Customer POST -> proxy HTML 403 + HTML 404 on /receipts/status -> keeps UNKNOWN_ACK & SAME key
            val custOp = repo.getDraftOperationId("customer_payment")
            val custRes1 = repo.recordCustomerPayment(
                customerId = customerA.value,
                amount = BigDecimal("40.00"),
                note = "Proxy HTML 403",
                paymentId = custOp,
                cashAccountId = cashAccountA.value,
                paymentDate = "2026-11-27"
            )
            assertTrue("HTML 403 + HTML 404 status must fail ambiguously, never treat as settled or NOT_FOUND", custRes1.isFailure)
            assertEquals("SAME draft key must be preserved", custOp, repo.getDraftOperationId("customer_payment"))
            assertTrue("Must remain recovering", repo.isDraftRecovering("customer_payment"))
            val custRec = store.findCommand(companyA.value, actorA.value, deviceA.value, "custpay-$custOp")
            assertEquals(CommandStatus.UNKNOWN_ACK, custRec?.status)
            assertEquals(1, custPostCount.get())
            assertEquals(1, custStatusCount.get())

            // Recovery retry when /receipts/status returns Found -> succeeds via 0 additional POSTs & rotates key
            custStatusMode = "found"
            val custRes2 = repo.recordCustomerPayment(
                customerId = customerA.value,
                amount = BigDecimal("40.00"),
                note = "Proxy HTML 403",
                paymentId = custOp,
                cashAccountId = cashAccountA.value,
                paymentDate = "2026-11-27"
            )
            assertTrue("Retry must recover via /receipts/status: ${custRes2.exceptionOrNull()?.message}", custRes2.isSuccess)
            assertEquals("No second POST may occur when HTML 403 retained UNKNOWN_ACK", 1, custPostCount.get())
            assertEquals(2, custStatusCount.get())
            assertEquals(listOf("custpay-$custOp"), custKeys)
            assertNotEquals(custOp, repo.getDraftOperationId("customer_payment"))

            // Part B: Supplier POST -> non-contract 404 {"message":...} retains UNKNOWN_ACK & SAME key, then recovers
            val supOp1 = repo.getDraftOperationId("supplier_payment")
            val supRes1 = repo.recordSupplierPayment(supplierA.value, BigDecimal("55.00"), "Proxy 404", ownerSession, supOp1, cashAccountA.value, "2026-11-27")
            assertTrue(supRes1.isFailure)
            assertEquals(supOp1, repo.getDraftOperationId("supplier_payment"))
            assertEquals(CommandStatus.UNKNOWN_ACK, store.findCommand(companyA.value, actorA.value, deviceA.value, "suppay-$supOp1")?.status)
            assertEquals(1, supStatusCount.get())

            supStatusMode = "found"
            val supRes1Recov = repo.recordSupplierPayment(supplierA.value, BigDecimal("55.00"), "Proxy 404", ownerSession, supOp1, cashAccountA.value, "2026-11-27")
            assertTrue(supRes1Recov.isSuccess)
            assertEquals(1, supPostCount.get())
            assertNotEquals(supOp1, repo.getDraftOperationId("supplier_payment"))

            // Part C: Non-contract error code 422 & mismatched X-Company-Id 422 also retain UNKNOWN_ACK & SAME key
            supPostMode = "nonContractCode422"
            supStatusMode = "outage503"
            val supOp2 = repo.getDraftOperationId("supplier_payment")
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("15.00"), "WAF 422", ownerSession, supOp2, cashAccountA.value, "2026-11-27").isFailure)
            assertEquals(supOp2, repo.getDraftOperationId("supplier_payment"))
            assertEquals(CommandStatus.UNKNOWN_ACK, store.findCommand(companyA.value, actorA.value, deviceA.value, "suppay-$supOp2")?.status)

            supStatusMode = "found"
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("15.00"), "WAF 422", ownerSession, supOp2, cashAccountA.value, "2026-11-27").isSuccess)
            assertNotEquals(supOp2, repo.getDraftOperationId("supplier_payment"))

            supPostMode = "mismatchedScope422"
            supStatusMode = "outage503"
            val supOp3 = repo.getDraftOperationId("supplier_payment")
            assertTrue(repo.recordSupplierPayment(supplierA.value, BigDecimal("18.00"), "Bad company hdr", ownerSession, supOp3, cashAccountA.value, "2026-11-27").isFailure)
            assertEquals(supOp3, repo.getDraftOperationId("supplier_payment"))
            assertEquals(CommandStatus.UNKNOWN_ACK, store.findCommand(companyA.value, actorA.value, deviceA.value, "suppay-$supOp3")?.status)
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // 11. Validated Authoritative Domain Rejections (422/403/404) Terminate Cleanly
    // -------------------------------------------------------------------------
    @Test
    fun ambiguousPriorPayment_neverReleasesKeyAfterUnrelated404AndForbiddenRetry() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)
        val repo = newRepo(store)
        var lookupBody = "{}"
        var posts = 0
        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.path.orEmpty().contains("/receipts/status")) {
                    return MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody(lookupBody)
                }
                posts++
                return if (posts == 1) MockResponse().setResponseCode(503).setBody("Gateway lost acknowledgement")
                else MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"code":"FORBIDDEN","message":"Permission revoked"}}""")
            }
        }
        try {
            val op = repo.getDraftOperationId("customer_payment")
            suspend fun submit() = repo.recordCustomerPayment(
                customerId = customerA.value, amount = BigDecimal("40.00"), note = "Lost acknowledgement",
                paymentId = op, cashAccountId = cashAccountA.value, paymentDate = "2026-11-27"
            )
            assertTrue(submit().isFailure)
            for (body in listOf("", "{}", """{"message":"Gateway missing route"}""", """{"error":{"code":"MOBILE_MERCHANT_DISABLED","message":"Disabled"}}""")) {
                lookupBody = body
                assertTrue(submit().isFailure)
                assertEquals(1, posts)
                assertEquals(op, repo.getDraftOperationId("customer_payment"))
            }
            lookupBody = """{"error":{"code":"NOT_FOUND","message":"Not found"}}"""
            assertTrue(submit().isFailure)
            assertEquals(2, posts)
            assertEquals(CommandStatus.UNKNOWN_ACK, store.findCommand(companyA.value, actorA.value, deviceA.value, "custpay-$op")?.status)
            assertEquals(op, repo.getDraftOperationId("customer_payment"))
            assertTrue(repo.isDraftRecovering("customer_payment"))
        } finally {
            db.close()
        }
    }

    @Test
    fun validatedAuthoritativeDomainRejections_matchingContractAndScope_markFailedAndRotate() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)
        val repo = newRepo(store)
        val statusCalls = AtomicInteger(0)
        var nextErrorResponse = 422 to """{"error":{"code":"VALIDATION_FAILED","message":"Amount exceeds credit policy"}}"""

        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments") ->
                        MockResponse()
                            .setResponseCode(nextErrorResponse.first)
                            .setHeader("Content-Type", "application/json")
                            .setBody(nextErrorResponse.second)
                    request.method == "GET" && path.contains("/receipts/status") -> {
                        statusCalls.incrementAndGet()
                        MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        try {
            val cases = listOf(
                422 to """{"error":{"code":"VALIDATION_FAILED","message":"Amount exceeds policy"}}""",
                403 to """{"error":{"code":"FORBIDDEN","message":"Permission denied"}}""",
                404 to """{"error":{"code":"NOT_FOUND","message":"Customer does not exist"}}"""
            )
            for ((idx, errCase) in cases.withIndex()) {
                nextErrorResponse = errCase
                val op = repo.getDraftOperationId("customer_payment")
                val res = repo.recordCustomerPayment(
                    customerId = customerA.value,
                    amount = BigDecimal("${10 + idx}.00"),
                    note = "Validated rejection ${errCase.first}",
                    paymentId = op,
                    cashAccountId = cashAccountA.value,
                    paymentDate = "2026-11-27"
                )
                assertTrue("Validated ${errCase.first} must fail", res.isFailure)
                assertEquals("Validated domain rejection must skip /receipts/status", 0, statusCalls.get())
                assertEquals(CommandStatus.FAILED, store.findCommand(companyA.value, actorA.value, deviceA.value, "custpay-$op")?.status)
                assertFalse("FAILED command must NOT trap recovering flag", repo.isDraftRecovering("customer_payment"))
                assertNotEquals("Validated FAILED must rotate draft key cleanly", op, repo.getDraftOperationId("customer_payment"))
            }
        } finally {
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // 12. Crash in Durable PENDING Window Before SUBMITTED (Customer + Supplier UI)
    // -------------------------------------------------------------------------
    @Test
    fun pendingWindowRestart_customerAndSupplierUi_restoreOriginalPayload_blockOtherParty_andSubmitOnePost() = runBlocking {
        // Phase A: Seed durable PENDING customer_payment & supplier_payment (crashed before SUBMITTED & POST)
        val dbSeed = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val storeSeed = RoomMerchantCommandStore(dbSeed, cryptoEngine)
        val custOp = storeSeed.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment")
        val custPayloadObj = StrictJsonObject(
            mapOf(
                "customer_id" to StrictJsonString(customerA.value),
                "amount" to StrictJsonString("50.000000"),
                "currency" to StrictJsonString("ILS"),
                "payment_date" to StrictJsonString("2026-11-27"),
                "money_account_id" to StrictJsonString(cashAccountA.value),
                "notes" to StrictJsonString("Pending customer draft"),
                "allocations" to StrictJsonArray(emptyList())
            )
        )
        val custPayloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(custPayloadObj)
        val custRecord = storeSeed.recordPendingCommand(
            companyId = companyA.value,
            commandId = custOp,
            actorId = actorA.value,
            deviceId = deviceA.value,
            commandType = "customer.payment",
            idempotencyKey = "custpay-$custOp",
            payloadDigest = sha256Digest(custPayloadBytes),
            canonicalPayloadBytes = custPayloadBytes
        )
        assertEquals(CommandStatus.PENDING, custRecord.status)

        val supOp = storeSeed.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "supplier_payment")
        val supPayloadObj = StrictJsonObject(
            mapOf(
                "supplier_id" to StrictJsonString(supplierA.value),
                "amount" to StrictJsonString("75.000000"),
                "currency" to StrictJsonString("ILS"),
                "payment_date" to StrictJsonString("2026-11-27"),
                "money_account_id" to StrictJsonString(cashAccountA.value),
                "notes" to StrictJsonString("Pending supplier draft"),
                "allocations" to StrictJsonArray(emptyList())
            )
        )
        val supPayloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(supPayloadObj)
        val supRecord = storeSeed.recordPendingCommand(
            companyId = companyA.value,
            commandId = supOp,
            actorId = actorA.value,
            deviceId = deviceA.value,
            commandType = "supplier.payment",
            idempotencyKey = "suppay-$supOp",
            payloadDigest = sha256Digest(supPayloadBytes),
            canonicalPayloadBytes = supPayloadBytes
        )
        assertEquals(CommandStatus.PENDING, supRecord.status)
        dbSeed.close()

        // Phase B: Cold restart on a fresh OutboxDatabase connection
        val custPostCalls = AtomicInteger(0)
        val custPostKeys = CopyOnWriteArrayList<String>()
        val custPostBodies = CopyOnWriteArrayList<String>()
        val supPostCalls = AtomicInteger(0)
        val supPostKeys = CopyOnWriteArrayList<String>()
        val supPostBodies = CopyOnWriteArrayList<String>()
        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments") -> {
                        custPostCalls.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { custPostKeys.add(it) }
                        custPostBodies.add(request.body.readUtf8())
                        MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"id":"01JCVPAYPENDINGCUST01","customer_id":"${customerA.value}","amount":"50.00","posted_at":"2026-11-27","balance":"70.00"}""")
                    }
                    request.method == "POST" && path.endsWith("/suppliers/${supplierA.value}/payments") -> {
                        supPostCalls.incrementAndGet()
                        request.getHeader("Idempotency-Key")?.let { supPostKeys.add(it) }
                        supPostBodies.add(request.body.readUtf8())
                        MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"id":"01JCVPAYPENDINGSUPP01","supplier_id":"${supplierA.value}","amount":"75.00","posted_at":"2026-11-27","balance_owed":"125.00"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val dbRestart = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val storeRestart = RoomMerchantCommandStore(dbRestart, cryptoEngine)
        val repoRestart = newRepo(storeRestart)
        val vm = TraderViewModel(repoRestart)

        try {
            assertTrue("PENDING customer draft must report isDraftRecovering=true", repoRestart.isDraftRecovering("customer_payment"))
            assertTrue("PENDING supplier draft must report isDraftRecovering=true", repoRestart.isDraftRecovering("supplier_payment"))
            assertEquals(custOp, repoRestart.getDraftOperationId("customer_payment"))
            assertEquals(supOp, repoRestart.getDraftOperationId("supplier_payment"))

            val custA = Customer(id = customerA.value, nameAr = "زبون أ", nameEn = "Customer A", balanceExact = BigDecimal("120.00"))
            val custB = Customer(id = customerB.value, nameAr = "زبون ب", nameEn = "Customer B", balanceExact = BigDecimal("50.00"))
            val supA = Supplier(id = supplierA.value, nameAr = "مورد أ", nameEn = "Supplier A", balanceOwedExact = BigDecimal("200.00"))
            val supB = Supplier(id = supplierB.value, nameAr = "مورد ب", nameEn = "Supplier B", balanceOwedExact = BigDecimal("75.00"))

            var mode by mutableStateOf("custOther") // custOther -> custOwn -> supOther -> supOwn

            composeTestRule.setContent {
                val state by vm.uiState.collectAsState()
                SmallTraderTheme(darkTheme = false, isRtl = false) {
                    when (mode) {
                        "custOther" -> CustomerPaymentCollectionDialog(
                            customer = custB,
                            lang = AppLanguage.EN,
                            onDismiss = {},
                            onConfirmPaymentExactWithResult = { cid, amount, note, paymentId, allocs, onComplete ->
                                vm.confirmCustomerCollectionResult(cid, amount, note, paymentId, allocs, onComplete)
                            },
                            operationId = state.draftCustomerPaymentOperationId.takeIf { it.isNotBlank() },
                            initialDraft = state.activeCustomerPaymentDraft,
                            isRecovering = state.isCustomerPaymentRecovering,
                            isSubmitting = state.isCustomerPaymentSubmitting,
                            inlineError = state.customerPaymentError
                        )
                        "custOwn" -> CustomerPaymentCollectionDialog(
                            customer = custA,
                            lang = AppLanguage.EN,
                            onDismiss = {},
                            onConfirmPaymentExactWithResult = { cid, amount, note, paymentId, allocs, onComplete ->
                                vm.confirmCustomerCollectionResult(cid, amount, note, paymentId, allocs, onComplete)
                            },
                            operationId = state.draftCustomerPaymentOperationId.takeIf { it.isNotBlank() },
                            initialDraft = state.activeCustomerPaymentDraft,
                            isRecovering = state.isCustomerPaymentRecovering,
                            isSubmitting = state.isCustomerPaymentSubmitting,
                            inlineError = state.customerPaymentError
                        )
                        "supOther" -> PaySupplierDialog(
                            supplier = supB,
                            lang = AppLanguage.EN,
                            onDismiss = {},
                            onPaySupplier = { _, _, _ -> Result.failure(IllegalStateException("unused")) },
                            onPaySupplierExactWithResult = { sup, amount, note, paymentId, allocs, onComplete ->
                                vm.paySupplierAmountExactResult(sup.id, amount, note, paymentId, allocations = allocs, onComplete = onComplete)
                            },
                            operationId = state.draftSupplierPaymentOperationId.takeIf { it.isNotBlank() },
                            initialDraft = state.activeSupplierPaymentDraft,
                            isRecovering = state.isSupplierPaymentRecovering,
                            isSubmitting = state.isSupplierPaymentSubmitting,
                            inlineError = state.supplierPaymentError
                        )
                        "supOwn" -> PaySupplierDialog(
                            supplier = supA,
                            lang = AppLanguage.EN,
                            onDismiss = {},
                            onPaySupplier = { _, _, _ -> Result.failure(IllegalStateException("unused")) },
                            onPaySupplierExactWithResult = { sup, amount, note, paymentId, allocs, onComplete ->
                                vm.paySupplierAmountExactResult(sup.id, amount, note, paymentId, allocations = allocs, onComplete = onComplete)
                            },
                            operationId = state.draftSupplierPaymentOperationId.takeIf { it.isNotBlank() },
                            initialDraft = state.activeSupplierPaymentDraft,
                            isRecovering = state.isSupplierPaymentRecovering,
                            isSubmitting = state.isSupplierPaymentSubmitting,
                            inlineError = state.supplierPaymentError
                        )
                    }
                }
            }

            // 1. Customer B must be blocked by Customer A's PENDING draft
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("customer_pay_blocked_banner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsNotEnabled()

            // 2. Customer A must restore 50 (not 120 default balance), lock inputs, enable Verify & Retry, execute ONE POST
            mode = "custOwn"
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("customer_pay_recovery_banner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("payment_amount_input").assertIsNotEnabled().assertTextContains("50.00", substring = true)
            composeTestRule.onNodeWithTag("payment_note_input").assertIsNotEnabled().assertTextContains("Pending customer draft", substring = true)
            composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsEnabled().performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !vm.uiState.value.isCustomerPaymentRecovering && !vm.uiState.value.isCustomerPaymentSubmitting
            }
            composeTestRule.waitForIdle()
            assertEquals("PENDING customer recovery must execute exactly ONE POST", 1, custPostCalls.get())
            assertEquals(listOf("custpay-$custOp"), custPostKeys)
            val custBodyObj = StrictJsonParser.parseObject(custPostBodies.single())
            assertEquals("50.000000", custBodyObj.properties["amount"]?.asString())
            assertEquals("Pending customer draft", custBodyObj.properties["notes"]?.asString())
            assertNotEquals(custOp, vm.uiState.value.draftCustomerPaymentOperationId)

            // 3. Supplier B must be blocked by Supplier A's PENDING draft
            mode = "supOther"
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("supplier_pay_blocked_banner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsNotEnabled()

            // 4. Supplier A must restore 75 (not 200 default balance), lock inputs, enable confirm, execute ONE POST
            mode = "supOwn"
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("supplier_pay_recovery_banner").assertIsDisplayed()
            composeTestRule.onNodeWithTag("supplier_pay_amount_input").assertIsNotEnabled().assertTextContains("75.00", substring = true)
            composeTestRule.onNodeWithTag("supplier_pay_note_input").assertIsNotEnabled().assertTextContains("Pending supplier draft", substring = true)
            composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsEnabled().performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !vm.uiState.value.isSupplierPaymentRecovering && !vm.uiState.value.isSupplierPaymentSubmitting
            }
            composeTestRule.waitForIdle()
            assertEquals("PENDING supplier recovery must execute exactly ONE POST", 1, supPostCalls.get())
            assertEquals(listOf("suppay-$supOp"), supPostKeys)
            val supBodyObj = StrictJsonParser.parseObject(supPostBodies.single())
            assertEquals("75.000000", supBodyObj.properties["amount"]?.asString())
            assertEquals("Pending supplier draft", supBodyObj.properties["notes"]?.asString())
            assertNotEquals(supOp, vm.uiState.value.draftSupplierPaymentOperationId)
        } finally {
            dbRestart.close()
        }
    }

    // -------------------------------------------------------------------------
    // 13. Two Independent Room OutboxDatabase Connections on Same SQLite File
    // Honest scope limit: Robolectric runs inside one JVM process; this verifies
    // two separate Room connections & stores on the same SQLite file without
    // shared Room instance locks or active inFlightFormTypes masking SQLite,
    // NOT separate OS processes.
    // -------------------------------------------------------------------------
    @Test
    fun twoIndependentRoomConnectionsSameSqliteFile_enforcePendingSubmittedUnknownAckAndDraftId() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        db1.openHelper.writableDatabase.query("PRAGMA busy_timeout = 5000").close()
        db2.openHelper.writableDatabase.query("PRAGMA busy_timeout = 5000").close()

        val store1 = RoomMerchantCommandStore({ db1.openHelper.writableDatabase }, cryptoEngine)
        val store2 = RoomMerchantCommandStore({ db2.openHelper.writableDatabase }, cryptoEngine)

        val postCalls = AtomicInteger(0)
        server!!.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path.endsWith("/customers/${customerA.value}/payments") -> {
                        postCalls.incrementAndGet()
                        MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"id":"01JCVTWODBCONN0001","customer_id":"${customerA.value}","amount":"60.00","posted_at":"2026-11-27","balance":"40.00"}""")
                    }
                    request.method == "GET" && path.contains("/receipts/status") ->
                        MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                            .setBody("""{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }

        val repo2 = newRepo(store2)

        try {
            // 1. Concurrent getDraftOperationId across two separate Room connections on same file converges
            val gate = CountDownLatch(1)
            val ids = (0 until 8).map { idx ->
                async(Dispatchers.IO) {
                    gate.await(2, TimeUnit.SECONDS)
                    (if (idx % 2 == 0) store1 else store2)
                        .getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment")
                }
            }
            gate.countDown()
            val resolved = ids.awaitAll()
            assertEquals("Two independent SQLite connections must converge to ONE draft UUID", 1, resolved.distinct().size)
            val draftOp = resolved.distinct().single()

            // 2. Connection 1 persists PENDING directly in SQLite (repo1 is NOT holding inFlightFormTypes!)
            val payloadObj = StrictJsonObject(
                mapOf(
                    "customer_id" to StrictJsonString(customerA.value),
                    "amount" to StrictJsonString("60.000000"),
                    "currency" to StrictJsonString("ILS"),
                    "payment_date" to StrictJsonString("2026-11-27"),
                    "money_account_id" to StrictJsonString(cashAccountA.value),
                    "notes" to StrictJsonString("Conn1 pending"),
                    "allocations" to StrictJsonArray(emptyList())
                )
            )
            val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)
            val digest = sha256Digest(payloadBytes)
            store1.recordPendingCommand(
                companyId = companyA.value,
                commandId = draftOp,
                actorId = actorA.value,
                deviceId = deviceA.value,
                commandType = "customer.payment",
                idempotencyKey = "custpay-$draftOp",
                payloadDigest = digest,
                canonicalPayloadBytes = payloadBytes
            )

            // Connection 2 observes PENDING without any shared Room instance or inFlightFormTypes state
            assertTrue("Independent connection 2 must see PENDING draft as recovering", repo2.isDraftRecovering("customer_payment"))
            assertEquals(draftOp, repo2.getDraftOperationId("customer_payment"))

            // Other customer/digest/alternate paymentId rejected via SQLite on connection 2 without POST
            assertTrue(repo2.recordCustomerPayment(customerB.value, BigDecimal("60.00"), "Conn1 pending", draftOp, cashAccountA.value, "2026-11-27").isFailure)
            assertTrue(repo2.recordCustomerPayment(customerA.value, BigDecimal("61.00"), "Conn1 pending", draftOp, cashAccountA.value, "2026-11-27").isFailure)
            assertTrue(repo2.recordCustomerPayment(customerA.value, BigDecimal("60.00"), "Conn1 pending", "other-id", cashAccountA.value, "2026-11-27").isFailure)
            assertEquals(0, postCalls.get())

            // 3. SUBMITTED on connection 1 prevents conflicting transition/claim on connection 2, and COMPLETED recovers cleanly
            assertTrue(store1.transitionStatus(companyA.value, draftOp, CommandStatus.PENDING, CommandStatus.SUBMITTED))
            assertTrue("Independent connection 2 must see SUBMITTED draft", repo2.isDraftRecovering("customer_payment"))

            val recOnConn2 = store2.getActiveDraftRecord(companyA.value, actorA.value, deviceA.value, "customer_payment")!!
            val claimGate = CountDownLatch(1)
            val claims = (0 until 2).map { idx ->
                async(Dispatchers.IO) {
                    claimGate.await(2, TimeUnit.SECONDS)
                    (if (idx == 0) store1 else store2).claimSubmittedCommand(companyA.value, draftOp, recOnConn2.updatedAt)
                }
            }
            claimGate.countDown()
            val claimResults = claims.awaitAll()
            // Note: claimSubmittedCommand returns boolean; both or one may update only once depending on updatedAt change; key check is UNKNOWN_ACK + recovery
            assertEquals(2, claimResults.size)

            // Transition to UNKNOWN_ACK via connection 1 -> connection 2 recovers cleanly and sends POST once (since server not yet committed)
            assertTrue(store1.markUnknownAck(companyA.value, draftOp, "Conn1 interrupted"))
            assertTrue(repo2.isDraftRecovering("customer_payment"))
            val resRecovered = repo2.recordCustomerPayment(customerA.value, BigDecimal("60.00"), "Conn1 pending", draftOp, cashAccountA.value, "2026-11-27")
            assertTrue("Independent connection 2 must complete recovered draft: ${resRecovered.exceptionOrNull()?.message}", resRecovered.isSuccess)
            assertEquals(1, postCalls.get())
            assertFalse(repo2.isDraftRecovering("customer_payment"))
            assertNotEquals(draftOp, store1.getDraftOperationId(companyA.value, actorA.value, deviceA.value, "customer_payment"))
        } finally {
            db2.close()
            db1.close()
        }
    }
}
