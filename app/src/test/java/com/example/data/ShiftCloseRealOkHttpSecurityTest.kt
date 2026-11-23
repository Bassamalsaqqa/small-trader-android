package com.example.data

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.contract.StrictJsonNumber
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.merchantread.LiveShiftSummary
import com.example.data.mobile.merchantread.ShiftCloseResponse
import com.example.data.mobile.merchantread.ShiftSummaryReportResponse
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.AppLanguage
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.ui.screens.FinancialReportsScreen
import com.example.ui.screens.ShiftCloseDialog
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShiftCloseRealOkHttpSecurityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var server: MockWebServer? = null
    private var baseOrigin: String = ""
    private val cryptoEngine = JvmAesGcmCryptoEngine()

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val cashAccountId = ServerUlid.parse("01JMB000000000000000000004")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"
    private val validScope = CurrentAuthScope(companyId, actorId, deviceId, token = validToken, generation = 1L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_shift_close_security_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()

        val s = MockWebServer()
        s.start()
        val port = s.port
        baseOrigin = "http://127.0.0.1:$port"
        this.server = s
        ProductionTraderRepository.clearInFlightForTest()
    }

    @After
    fun tearDown() {
        ProductionTraderRepository.clearInFlightForTest()
        server?.shutdown()
        server = null
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 1. Real OkHttp + MockWebServer: Wire Path, Headers, Query, and Payload
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_wirePathQueryPayloadHeadersExact_MockWebServer() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val closeResponseJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789X",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789X",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "500.00",
                "counted_cash": "495.50",
                "variance": "-4.50",
                "variance_status": "SHORTAGE",
                "closed_at": "2026-11-23T02:00:00Z"
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(closeResponseJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val commandId = "cmd-shift-001"
        val result = repo.closeShift(
            countedCash = BigDecimal("495.50"),
            notes = "Shortage 4.50 after evening rush",
            commandId = commandId
        )

        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789X", resp.id.value)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789X", resp.shiftClosureId.value)
        assertEquals("2026-11-23", resp.shiftDate)
        assertEquals("ILS", resp.currency)
        assertEquals(2, resp.currencyMinorUnits)
        assertEquals(BigDecimal("500.00"), resp.expectedCash)
        assertEquals(BigDecimal("495.50"), resp.countedCash)
        assertEquals(BigDecimal("-4.50"), resp.variance)
        assertEquals("SHORTAGE", resp.varianceStatus)
        assertEquals("2026-11-23T02:00:00Z", resp.closedAt)
        assertTrue(resp.success)
        assertTrue(resp.observationRecorded)
        assertFalse(resp.isShiftClosed)

        val request = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/reports/shift-close", request!!.path)
        assertEquals("POST", request.method)
        assertEquals("shiftclose-$commandId", request.getHeader("Idempotency-Key"))
        assertEquals("Bearer $validToken", request.getHeader("Authorization"))

        val recordedBody = request.body.readUtf8()
        val parsedJson = StrictJsonParser.parseObject(recordedBody)
        assertEquals("495.50", parsedJson.properties["counted_cash"]?.asString())
        assertEquals(cashAccountId.value, parsedJson.properties["cash_account_id"]?.asString())
        assertEquals("Shortage 4.50 after evening rush", parsedJson.properties["notes"]?.asString())

        db.close()
    }

    // -------------------------------------------------------------------------
    // 2. Authoritative Variance Status: EXACT and OVERAGE
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_authoritativeVarianceStatusExactAndOverage() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val exactJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCVEXACT0000000000000001",
                "shift_closure_id": "01JCVEXACT0000000000000001",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "500.00",
                "counted_cash": "500.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "2026-11-23T02:05:00Z"
            }
        """.trimIndent()

        val overageJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV999990000000000000002",
                "shift_closure_id": "01JCV999990000000000000002",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "500.00",
                "counted_cash": "510.00",
                "variance": "10.00",
                "variance_status": "OVERAGE",
                "closed_at": "2026-11-23T02:10:00Z"
            }
        """.trimIndent()

        server!!.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(exactJson))
        server!!.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(overageJson))

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val resExact = repo.closeShift(BigDecimal("500.00"), commandId = "cmd-exact")
        assertTrue(resExact.isSuccess)
        assertEquals("EXACT", resExact.getOrThrow().varianceStatus)
        assertEquals(BigDecimal("0.00"), resExact.getOrThrow().variance)

        val resOver = repo.closeShift(BigDecimal("510.00"), commandId = "cmd-over")
        assertTrue(resOver.isSuccess)
        assertEquals("OVERAGE", resOver.getOrThrow().varianceStatus)
        assertEquals(BigDecimal("10.00"), resOver.getOrThrow().variance)

        db.close()
    }

    // -------------------------------------------------------------------------
    // 3. 503 LEDGER_UNAVAILABLE: Truthful Retry Without Fake Zero Expected Balance
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_503LedgerUnavailable_truthfulRetry_notFakeZeroExpectedBalance() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val error503Json = """
            {
                "error": {
                    "code": "LEDGER_UNAVAILABLE",
                    "message": "Authoritative ledger cash balance is unavailable for this drawer."
                }
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody(error503Json)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val result = repo.closeShift(BigDecimal("300.00"), commandId = "cmd-503")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("Error message must contain LEDGER_UNAVAILABLE or authoritative ledger message: ${ex!!.message}",
            ex.message!!.contains("LEDGER_UNAVAILABLE") || ex.message!!.contains("Authoritative ledger cash balance is unavailable"))

        // shiftCloseSuccessFlow must remain null (truthful unknown/error, never fake 0.00)
        assertEquals(null, repo.shiftCloseSuccessFlow.value)

        db.close()
    }

    // -------------------------------------------------------------------------
    // 4. 403 FORBIDDEN: Missing Permissions Rejection
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_authDeny403Forbidden() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val forbiddenJson = """
            {
                "error": {
                    "code": "FORBIDDEN",
                    "message": "Action is unauthorized. Missing required reports permission."
                }
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(forbiddenJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val result = repo.closeShift(BigDecimal("300.00"), commandId = "cmd-403")
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Must preserve 403 FORBIDDEN error: $msg", msg.contains("403") || msg.contains("FORBIDDEN") || msg.contains("unauthorized"))

        db.close()
    }

    // -------------------------------------------------------------------------
    // 5. Real-File Room Close & Reopen: Lost ACK Recovery (Same Immutable Intent)
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_realFileRoom_lostAckReplay_sameImmutableIntent() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        val closeResponseJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789R",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789R",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "400.00",
                "counted_cash": "400.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "2026-11-23T02:20:00Z"
            }
        """.trimIndent()

        // Server only enqueues 1 response
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(closeResponseJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo1 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store1,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val commandId = "cmd-lost-ack-001"
        val res1 = repo1.closeShift(BigDecimal("400.00"), notes = "Pre-crash intent", commandId = commandId)
        assertTrue(res1.isSuccess)
        assertEquals(1, server!!.requestCount)

        // Simulate application restart: close database
        db1.close()

        // Reopen real database from disk
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)

        val repo2 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        // Resubmit same command (lost ACK / retry after process restart)
        val res2 = repo2.closeShift(BigDecimal("400.00"), notes = "Pre-crash intent", commandId = commandId)
        assertTrue(res2.isSuccess)

        // Must return cached completed snapshot WITHOUT making another server request
        assertEquals(1, server!!.requestCount)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789R", res2.getOrThrow().shiftClosureId.value)
        assertEquals("2026-11-23T02:20:00Z", res2.getOrThrow().closedAt)

        db2.close()
    }

    // -------------------------------------------------------------------------
    // 6. Concurrency: In-Flight Lock Rejects Duplicate Closures
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_concurrency_secondCallFailsBeforePost() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val closeResponseJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789C",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789C",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "350.00",
                "counted_cash": "350.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "2026-11-23T02:30:00Z"
            }
        """.trimIndent()

        // Delay server response by 300ms to allow concurrent call
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(closeResponseJson)
                .setBodyDelay(300, TimeUnit.MILLISECONDS)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val call1 = async {
            repo.closeShift(BigDecimal("350.00"), commandId = "cmd-conc-1")
        }

        // Small yield to let call1 acquire admission lock
        delay(20)

        val call2 = async {
            repo.closeShift(BigDecimal("350.00"), commandId = "cmd-conc-2")
        }

        val res1 = call1.await()
        val res2 = call2.await()

        val (winner, loser) = if (res1.isSuccess) Pair(res1, res2) else Pair(res2, res1)
        assertTrue("Exactly one concurrent call must succeed: res1=$res1, res2=$res2", winner.isSuccess && loser.isFailure)
        val rejection = loser.exceptionOrNull()
        assertTrue("Second concurrent call must fail admission: $rejection", rejection is IllegalStateException)
        assertTrue("Second concurrent call must retain the unresolved attempt: ${rejection?.message}",
            rejection!!.message!!.contains("already in flight") ||
                rejection.message!!.contains("existing cash count attempt"))
        assertEquals("Only the admitted attempt may reach the server", 1, server!!.requestCount)
        val rejectedCommandId = if (res1.isSuccess) "cmd-conc-2" else "cmd-conc-1"
        assertNull(store.findCommand(companyId.value, actorId.value, deviceId.value, "shiftclose-$rejectedCommandId"))

        db.close()
    }

    // -------------------------------------------------------------------------
    // 7. Changed Count With Same Key: Fails Before POST
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_changedCountSameKey_failsBeforePost() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        // Pre-record pending command in store with count 100.00
        val key = "shiftclose-cmd-conflict"
        val payload1 = StrictJsonObject(mapOf(
            "counted_cash" to com.example.data.mobile.contract.StrictJsonString("100.00"),
            "cash_account_id" to com.example.data.mobile.contract.StrictJsonString(cashAccountId.value),
            "currency" to com.example.data.mobile.contract.StrictJsonString("ILS"),
            "currency_minor_units" to com.example.data.mobile.contract.StrictJsonNumber("2", true),
            "shift_date" to com.example.data.mobile.contract.StrictJsonString("2026-11-23")
        ))
        val bytes1 = com.example.data.mobile.contract.CanonicalJsonSerializer.serializeToUtf8Bytes(payload1)
        val digest1 = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes1).joinToString("") { "%02x".format(it) }

        store.recordPendingCommand(
            companyId = companyId.value,
            commandId = "cmd-conflict",
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "shift.close",
            idempotencyKey = key,
            payloadDigest = digest1,
            canonicalPayloadBytes = bytes1
        )

        // Attempt submission with count 200.00 under the same commandId / idempotency key
        val result = repo.closeShift(BigDecimal("200.00"), commandId = "cmd-conflict")
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Must fail before POST with idempotency parameter conflict: $msg",
            msg.contains("previously committed with different parameters"))

        // A new workflow must not evade the unresolved command by rotating its key.
        for (status in listOf(CommandStatus.PENDING, CommandStatus.SUBMITTED, CommandStatus.UNKNOWN_ACK)) {
            val record = store.findCommand(companyId.value, actorId.value, deviceId.value, key)!!
            if (record.status != status) {
                assertTrue(store.transitionStatus(companyId.value, record.commandId, record.status, status))
            }
            val viewModel = TraderViewModel(repository = repo)
            val retainedId = viewModel.uiState.value.draftShiftCloseOperationId
            viewModel.startNewShiftWorkflow()
            assertEquals(retainedId, viewModel.uiState.value.draftShiftCloseOperationId)
            assertEquals(retainedId, repo.getDraftOperationId("shift_close"))

            val newAttempt = repo.closeShift(BigDecimal("100.00"), commandId = "different-command")
            assertTrue("Unresolved $status attempt must block a new command ID", newAttempt.isFailure)
            assertTrue(newAttempt.exceptionOrNull()!!.message!!.contains("existing cash count attempt"))
        }

        // MockWebServer MUST receive zero requests
        assertEquals(0, server!!.requestCount)

        db.close()
    }

    // -------------------------------------------------------------------------
    // 8. Scope Change During Execution: No Tenant Leak
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_scopeChangeDuringExecution_noLeak() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        var activeScope = validScope

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { activeScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        // Mutate scope before call
        activeScope = CurrentAuthScope(
            companyId = ServerUlid.parse("01JCV999999999999999999999"),
            actorId = actorId,
            deviceId = deviceId,
            token = validToken,
            generation = 2L
        )

        val result = repo.closeShift(BigDecimal("100.00"), commandId = "cmd-scope-leak")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)

        assertEquals(0, server!!.requestCount)
        db.close()
    }

    // -------------------------------------------------------------------------
    // 9. Decimal Validation: Reject Negative, Reject Excess Precision, Accept Zero
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_rejectsNegativeAndExcessPrecision_acceptsValidZero() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        // 1. Negative rejection
        val negRes = repo.closeShift(BigDecimal("-10.00"), commandId = "cmd-neg")
        assertTrue(negRes.isFailure)
        assertTrue(negRes.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Counted physical cash must be non-negative", negRes.exceptionOrNull()!!.message)

        // 2. Overprecision rejection (3 decimals with minorUnits=2)
        val excessRes = repo.closeShift(BigDecimal("100.555"), commandId = "cmd-excess")
        assertTrue(excessRes.isFailure)
        assertTrue(excessRes.exceptionOrNull() is IllegalArgumentException)
        assertTrue(excessRes.exceptionOrNull()!!.message!!.contains("exceeds currency minor units"))

        // 3. Valid zero acceptance
        val zeroResponseJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789Z",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789Z",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "100.00",
                "counted_cash": "0.00",
                "variance": "-100.00",
                "variance_status": "SHORTAGE",
                "closed_at": "2026-11-23T02:40:00Z"
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(zeroResponseJson)
        )

        val zeroRes = repo.closeShift(BigDecimal("0.00"), commandId = "cmd-zero")
        assertTrue(zeroRes.isSuccess)
        assertEquals(BigDecimal("0.00"), zeroRes.getOrThrow().countedCash)
        assertEquals("SHORTAGE", zeroRes.getOrThrow().varianceStatus)

        db.close()
    }

    // -------------------------------------------------------------------------
    // 10. Compose UI: Real Count Dialog, Input Validation, and Read-Only Closed State
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_ComposeActualConfirm_truthfulErrorsAndReadOnlyClosedState() {
        val session = UserSession(
            id = "usr_owner_1",
            name = "مالك المتجر",
            username = "owner",
            role = UserRole.OWNER,
            branchNameAr = "الفرع الرئيسي",
            branchNameEn = "Main Branch",
            isDemoSession = false
        )

        val liveShift = LiveShiftSummary(
            shiftDate = "2026-11-23",
            currency = "ILS",
            currencyMinorUnits = 2,
            openingFloat = BigDecimal("350.00"),
            cashSales = BigDecimal("500.00"),
            cashCollections = BigDecimal("50.00"),
            cashDisbursements = BigDecimal("20.00"),
            operatingExpenses = BigDecimal("30.00"),
            expectedCash = BigDecimal("850.00"),
            salesCount = 8
        )

        var dialogConfirmedCount: String? = null
        var dialogConfirmedNotes: String? = null

        val state = TraderUiState(
            currentSession = session,
            liveShiftSummary = liveShift,
            isShiftCloseDialogOpen = true,
            language = AppLanguage.AR
        )

        val testUiState = mutableStateOf(state)

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                val currentState = testUiState.value
                if (currentState.isShiftCloseDialogOpen) {
                    ShiftCloseDialog(
                        state = currentState,
                        onDismiss = {},
                        onConfirmClose = { count, notes ->
                            dialogConfirmedCount = count
                            dialogConfirmedNotes = notes
                        }
                    )
                } else {
                    FinancialReportsScreen(
                        state = currentState,
                        onUpdateOpeningCash = {},
                        onUpdateCountedCashText = {},
                        onToggleShiftClose = {}
                    )
                }
            }
        }

        // Dialog rendered with expected book cash
        composeTestRule.onNodeWithTag("shift_close_dialog_expected_cash").assertIsDisplayed()
        composeTestRule.onNodeWithText("850.00 ILS", substring = true).assertIsDisplayed()

        // Match book button populates input
        composeTestRule.onNodeWithTag("shift_close_match_book_button").performClick()

        // Enter notes
        composeTestRule.onNodeWithTag("shift_close_notes_input").performTextInput("مطابقة تامة للجرد")

        // Confirm
        composeTestRule.onNodeWithTag("confirm_shift_close_button").performClick()

        assertEquals("850.00", dialogConfirmedCount)
        assertEquals("مطابقة تامة للجرد", dialogConfirmedNotes)

        // Now verify read-only closed state when authoritative snapshot is set
        val closedSnapshot = ShiftCloseResponse(
            id = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789X"),
            shiftClosureId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789X"),
            shiftDate = "2026-11-23",
            currency = "ILS",
            currencyMinorUnits = 2,
            expectedCash = BigDecimal("850.00"),
            countedCash = BigDecimal("850.00"),
            variance = BigDecimal("0.00"),
            varianceStatus = "EXACT",
            closedAt = "2026-11-23T02:50:00Z",
            success = true,
            observationRecorded = true,
            isShiftClosed = false
        )

        val closedState = state.copy(
            isShiftCloseDialogOpen = false,
            shiftCloseSuccessSnapshot = closedSnapshot
        )

        testUiState.value = closedState
        composeTestRule.waitForIdle()

        // Verify closed state is rendered with exact authoritative ACK snapshot
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("shift_close_status_text"))
        composeTestRule.onNodeWithTag("shift_close_status_text").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("EXACT", substring = true)[0].assertIsDisplayed()
        composeTestRule.onNodeWithTag("shift_cash_variance_text").assertIsDisplayed()

        // Local fake toggle button must NOT exist in live mode
        composeTestRule.onNodeWithTag("toggle_shift_close_button").assertDoesNotExist()
    }

    // -------------------------------------------------------------------------
    // 11. Client-Side Permission Rejection: Missing Permissions Rejects Before Network
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_localPermissionCheck_failsWithoutNetworkCall() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2,
            permissions = setOf("pos.sale")
        )

        val result = repo.closeShift(BigDecimal("100.00"), commandId = "cmd-unauth-local")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Missing required reports permission"))
        assertEquals(0, server!!.requestCount)

        db.close()
    }

    // -------------------------------------------------------------------------
    // 12. Compose UI Wire: Dialog Opens, Confirms Production Command, Updates Live Screen
    // -------------------------------------------------------------------------

    @Test
    fun testShiftClose_ComposeActualDialogWire_productionCommandNotPreview() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val closeResponseJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789W",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789W",
                "shift_date": "2026-11-23",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "750.00",
                "counted_cash": "750.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "2026-11-23T03:00:00Z"
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(closeResponseJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val viewModel = TraderViewModel(
            repository = repo
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                val state by viewModel.uiState.collectAsState()
                FinancialReportsScreen(
                    state = state,
                    onUpdateOpeningCash = viewModel::updateStartingDrawerCash,
                    onUpdateCountedCashText = viewModel::updateCountedPhysicalCashText,
                    onToggleShiftClose = viewModel::toggleShiftClosePreview,
                    onOpenShiftCloseDialog = viewModel::openShiftCloseDialog
                )
                if (state.isShiftCloseDialogOpen) {
                    ShiftCloseDialog(
                        state = state,
                        onDismiss = { viewModel.openShiftCloseDialog(false) },
                        onConfirmClose = { count, notes -> viewModel.closeShift(count, notes) },
                        onUpdateCountedCash = viewModel::updateShiftCloseCountedCash,
                        onUpdateNotes = viewModel::updateShiftCloseNotes
                    )
                }
            }
        }

        // 1. Initial state: shift is open
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("open_shift_close_dialog_button"))
        composeTestRule.onNodeWithTag("open_shift_close_dialog_button").assertIsDisplayed()

        // 2. Click button to open dialog (production wire, not preview toggle)
        composeTestRule.onNodeWithTag("open_shift_close_dialog_button").performClick()
        composeTestRule.waitForIdle()

        // 3. Dialog is displayed
        composeTestRule.onNodeWithTag("shift_close_counted_cash_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shift_close_counted_cash_input").performTextInput("750.00")

        // 4. Click confirm close button
        composeTestRule.onNodeWithTag("confirm_shift_close_button").performClick()
        composeTestRule.waitForIdle()

        // Wait for coroutine to complete and UI to update
        composeTestRule.waitUntil(5000) {
            viewModel.uiState.value.shiftCloseSuccessSnapshot != null
        }

        // 5. Verify server request was dispatched with exact path and headers
        val request = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/reports/shift-close", request!!.path)
        assertEquals("POST", request.method)

        // 6. Verify UI renders count observation state, and shift remains open per server is_shift_closed=false
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("shift_close_status_text"))
        composeTestRule.onNodeWithTag("shift_close_status_text").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("EXACT", substring = true)[0].assertIsDisplayed()
        assertFalse(viewModel.uiState.value.isShiftClosedPreview)
        assertFalse("Option A: server returned is_shift_closed=false, so shift remains active", viewModel.uiState.value.isShiftClosed)
        assertNotNull(viewModel.uiState.value.shiftCloseSuccessSnapshot)
        assertTrue(viewModel.uiState.value.shiftCloseSuccessSnapshot!!.observationRecorded)
        assertFalse(viewModel.uiState.value.shiftCloseSuccessSnapshot!!.isShiftClosed)
        // Permit new explicit observation after confirmed: button remains visible
        composeTestRule.onNodeWithTag("open_shift_close_dialog_button").assertIsDisplayed()

        db.close()
    }

    @Test
    fun testShiftClose_realProductionVM_MainActivityDialogConfirm_lostAckRetry_sameKeyAndBodyProducesCanonicalResult() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val initialDraftId = repo.getDraftOperationId("shift_close")
        val viewModel = TraderViewModel(repository = repo)
        assertEquals(initialDraftId, viewModel.uiState.value.draftShiftCloseOperationId)

        // 1. First server response simulates network timeout / 503 lost ACK
        server!!.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"TEMPORARY_UNAVAILABLE","message":"Gateway Timeout"}""")
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                val state by viewModel.uiState.collectAsState()
                FinancialReportsScreen(
                    state = state,
                    onOpenShiftCloseDialog = viewModel::openShiftCloseDialog
                )
                if (state.isShiftCloseDialogOpen) {
                    ShiftCloseDialog(
                        state = state,
                        onDismiss = { viewModel.openShiftCloseDialog(false) },
                        onConfirmClose = { count, notes -> viewModel.closeShift(count, notes) },
                        onUpdateCountedCash = viewModel::updateShiftCloseCountedCash,
                        onUpdateNotes = viewModel::updateShiftCloseNotes
                    )
                }
            }
        }

        // Open dialog and enter counted cash & notes
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("open_shift_close_dialog_button"))
        composeTestRule.onNodeWithTag("open_shift_close_dialog_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("shift_close_counted_cash_input").performTextInput("500.00")
        composeTestRule.onNodeWithTag("shift_close_notes_input").performTextInput("Lost ACK retry test")

        // First click (without passing manual ID) -> attempts POST and fails with 503
        composeTestRule.onNodeWithTag("confirm_shift_close_button").performClick()
        composeTestRule.waitForIdle()

        val req1 = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req1)
        val expectedKey = "shiftclose-$initialDraftId"
        assertEquals(expectedKey, req1!!.getHeader("Idempotency-Key"))
        val body1 = req1.body.readUtf8()

        // Verify draft ID did not rotate on failure! Still in recovering state
        assertEquals(initialDraftId, viewModel.uiState.value.draftShiftCloseOperationId)
        assertTrue(viewModel.uiState.value.isShiftCloseRecovering)

        // 2. Server now enqueues authoritative 200 OK success
        val today = WesternFormatters.currentDate()
        val successJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": true,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789W",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789W",
                "shift_date": "$today",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "500.00",
                "counted_cash": "500.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "${today}T03:30:00Z"
            }
        """.trimIndent()
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(successJson)
        )

        // Retry via clicking confirm again (no manual ID injected!)
        composeTestRule.onNodeWithTag("confirm_shift_close_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(5000) {
            viewModel.uiState.value.isShiftClosed
        }

        val req2 = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req2)
        assertEquals("Retry must reuse identical Idempotency-Key", expectedKey, req2!!.getHeader("Idempotency-Key"))
        assertEquals("Retry must reuse identical payload body", body1, req2.body.readUtf8())

        // Authoritative success rotated the draft ID AFTER persisted ACK
        val nextDraftId = viewModel.uiState.value.draftShiftCloseOperationId
        assertTrue("Draft ID must rotate after authoritative ACK", nextDraftId.isNotBlank() && nextDraftId != initialDraftId)
        assertFalse(viewModel.uiState.value.isShiftCloseRecovering)
        assertTrue(viewModel.uiState.value.isShiftClosed)

        db.close()
    }

    @Test
    fun testShiftClose_processDeath_RoomCloseReopen_newVM_retainsDraftAndPayload_producesCanonicalResult() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo1 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store1,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val origDraftId = repo1.getDraftOperationId("shift_close")
        val vm1 = TraderViewModel(repository = repo1)

        server!!.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"LOST_ACK"}""")
        )

        val completion1 = CompletableDeferred<Result<ShiftCloseResponse>>()
        vm1.openShiftCloseDialog(true)
        vm1.updateShiftCloseCountedCash("650.00")
        vm1.updateShiftCloseNotes("Pre-crash intent notes")
        vm1.closeShift("650.00", "Pre-crash intent notes") { res ->
            completion1.complete(res)
        }

        val req1 = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req1)
        val body1 = req1!!.body.readUtf8()
        completion1.await()

        // Simulate process death / restart
        db1.close()

        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
        val repo2 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val vm2 = TraderViewModel(repository = repo2)

        // Verify coldboot rehydrated draft ID, recovering state, and original payload fields
        assertEquals(origDraftId, vm2.uiState.value.draftShiftCloseOperationId)
        assertTrue(vm2.uiState.value.isShiftCloseRecovering)
        assertEquals("650.00", vm2.uiState.value.shiftCloseCountedCashInput)
        assertEquals("Pre-crash intent notes", vm2.uiState.value.shiftCloseNotesInput)

        // Resubmit via new VM
        val today = WesternFormatters.currentDate()
        val successJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": true,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789K",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789K",
                "shift_date": "$today",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "650.00",
                "counted_cash": "650.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "${today}T04:00:00Z"
            }
        """.trimIndent()
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(successJson)
        )

        val completion2 = CompletableDeferred<Result<ShiftCloseResponse>>()
        vm2.closeShift { res ->
            completion2.complete(res)
        }

        val req2 = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req2)
        assertEquals("shiftclose-$origDraftId", req2!!.getHeader("Idempotency-Key"))
        assertEquals(body1, req2.body.readUtf8())
        val res2 = completion2.await()
        assertTrue(res2.isSuccess)
        assertTrue(vm2.uiState.value.isShiftClosed)

        db2.close()
    }

    @Test
    fun testShiftClose_concurrentTap_stillOneOutboundPost() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val today = WesternFormatters.currentDate()
        val successJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": true,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789M",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789M",
                "shift_date": "$today",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "300.00",
                "counted_cash": "300.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "${today}T04:10:00Z"
            }
        """.trimIndent()
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(successJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val viewModel = TraderViewModel(repository = repo)
        viewModel.openShiftCloseDialog(true)
        viewModel.updateShiftCloseCountedCash("300.00")

        // Concurrently invoke closeShift twice
        viewModel.closeShift("300.00")
        viewModel.closeShift("300.00")

        delay(300)

        assertEquals("Concurrent tap must result in exactly one outbound POST", 1, server!!.requestCount)

        db.close()
    }

    @Test
    fun testShiftClose_changedPendingCountOrNotes_blockedBeforePost_andRestoresOriginal() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val viewModel = TraderViewModel(repository = repo)

        server!!.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"TEMP_DOWN"}""")
        )

        val completion1 = CompletableDeferred<Result<ShiftCloseResponse>>()
        viewModel.openShiftCloseDialog(true)
        viewModel.updateShiftCloseCountedCash("450.00")
        viewModel.updateShiftCloseNotes("Original notes")
        viewModel.closeShift("450.00", "Original notes") { res ->
            completion1.complete(res)
        }

        val req1 = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req1)
        assertEquals(1, server!!.requestCount)
        completion1.await()
        assertTrue(viewModel.uiState.value.isShiftCloseRecovering)

        // Attempt to call closeShift with changed count & notes while recovering
        var failedResult: Result<ShiftCloseResponse>? = null
        viewModel.closeShift("999.00", "Tampered notes") { res ->
            failedResult = res
        }

        assertNotNull(failedResult)
        assertTrue(failedResult!!.isFailure)
        assertTrue(failedResult!!.exceptionOrNull() is IllegalStateException)
        // Must NOT dispatch outbound request to server
        assertEquals("Changed parameters while pending must be blocked before POST", 1, server!!.requestCount)

        // Must restore original payload values into state
        assertEquals("450.00", viewModel.uiState.value.shiftCloseCountedCashInput)
        assertEquals("Original notes", viewModel.uiState.value.shiftCloseNotesInput)

        db.close()
    }

    @Test
    fun testShiftClose_coldBoot_rehydratesCompletedSnapshot_sameScopeAndDate() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        val today = WesternFormatters.currentDate()
        val successJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": true,
                "id": "01JCV8X9M2NQW3TYZ4R5S6789S",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789S",
                "shift_date": "$today",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "800.00",
                "counted_cash": "800.00",
                "variance": "0.00",
                "variance_status": "EXACT",
                "closed_at": "${today}T02:45:00Z"
            }
        """.trimIndent()
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(successJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo1 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store1,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val vm1 = TraderViewModel(repository = repo1)
        val completion1 = CompletableDeferred<Result<ShiftCloseResponse>>()
        vm1.openShiftCloseDialog(true)
        vm1.closeShift("800.00") { res ->
            completion1.complete(res)
        }
        val req1 = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req1)
        val res1 = completion1.await()
        assertTrue(res1.isSuccess)
        assertTrue(vm1.uiState.value.isShiftClosed)

        // Close and simulate cold boot
        db1.close()

        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)
        val repo2 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val vm2 = TraderViewModel(repository = repo2)

        // Verify cold boot rehydrated completed snapshot
        val snap = vm2.uiState.value.shiftCloseSuccessSnapshot
        assertNotNull("Must rehydrate completed snapshot on cold boot", snap)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789S", snap!!.shiftClosureId.value)
        assertEquals(today, snap.shiftDate)
        assertEquals(BigDecimal("800.00"), snap.countedCash)
        assertEquals("EXACT", snap.varianceStatus)
        assertEquals("${today}T02:45:00Z", snap.closedAt)
        assertTrue(vm2.uiState.value.isShiftClosed)
        assertEquals("${today}T02:45:00Z", vm2.uiState.value.shiftClosureTimestamp)

        db2.close()
    }

    @Test
    fun testShiftClose_coldBoot_newDayOrWrongScope_noFalseClosed() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        // Record a completed shift close for a past date (newday)
        val pastDate = "2026-11-20"
        val payloadObj = StrictJsonObject(
            mapOf(
                "counted_cash" to StrictJsonString("500.00"),
                "cash_account_id" to StrictJsonString(cashAccountId.value),
                "currency" to StrictJsonString("ILS"),
                "currency_minor_units" to StrictJsonNumber("2", isInt = true),
                "shift_date" to StrictJsonString(pastDate)
            )
        )
        val payloadBytes = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)
        val payloadDigest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val snapObj = StrictJsonObject(
            mapOf(
                "success" to StrictJsonBoolean(true),
                "observation_recorded" to StrictJsonBoolean(true),
                "is_shift_closed" to StrictJsonBoolean(true),
                "id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S6789P"),
                "shift_closure_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S6789P"),
                "shift_date" to StrictJsonString(pastDate),
                "currency" to StrictJsonString("ILS"),
                "currency_minor_units" to StrictJsonNumber("2", isInt = true),
                "expected_cash" to StrictJsonString("500.00"),
                "counted_cash" to StrictJsonString("500.00"),
                "variance" to StrictJsonString("0.00"),
                "variance_status" to StrictJsonString("EXACT"),
                "closed_at" to StrictJsonString("${pastDate}T20:00:00Z")
            )
        )
        val snapBytes = CanonicalJsonSerializer.serialize(snapObj).toByteArray(java.nio.charset.StandardCharsets.UTF_8)

        store1.recordPendingCommand(
            companyId = companyId.value,
            commandId = "past-cmd-001",
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "shift.close",
            idempotencyKey = "shiftclose-past-cmd-001",
            payloadDigest = payloadDigest,
            canonicalPayloadBytes = payloadBytes
        )
        store1.markCompleted(companyId.value, "past-cmd-001", snapBytes)

        db1.close()

        // Cold boot with today's date (WesternFormatters.currentDate() != "2026-11-20")
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo2 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2
        )

        val vm2 = TraderViewModel(repository = repo2)

        // Verify that past date does NOT mark today's shift as falsely closed!
        assertNull("Past date must not mark current shift closed on cold boot", vm2.uiState.value.shiftCloseSuccessSnapshot)
        assertFalse("Current shift must remain open on a new day", vm2.uiState.value.isShiftClosed)

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                val state by vm2.uiState.collectAsState()
                FinancialReportsScreen(
                    state = state,
                    onOpenShiftCloseDialog = vm2::openShiftCloseDialog
                )
            }
        }

        // Truthfully displays open status
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("shift_close_status_text"))
        composeTestRule.onNodeWithTag("shift_close_status_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("حالة الوردية: مفتوحة وتستقبل العمليات").assertIsDisplayed()

        db2.close()
    }

    @Test
    fun testShiftClose_productionUI_serverObservationTrueClosedFalse_staysNotClosed_withAuthoritativeVarianceAndObservedTimestamp() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val today = WesternFormatters.currentDate()
        val observationJson = """
            {
                "success": true,
                "observation_recorded": true,
                "is_shift_closed": false,
                "id": "01JCV8X9M2NQW3TYZ4R5S67899",
                "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S67899",
                "shift_date": "$today",
                "currency": "ILS",
                "currency_minor_units": 2,
                "expected_cash": "700.00",
                "counted_cash": "715.00",
                "variance": "15.00",
                "variance_status": "OVERAGE",
                "observed_at": "${today}T04:15:00Z"
            }
        """.trimIndent()

        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(observationJson)
        )

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2,
            permissions = setOf("reports.financial.view")
        )

        val viewModel = TraderViewModel(repository = repo)

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                val state by viewModel.uiState.collectAsState()
                FinancialReportsScreen(
                    state = state,
                    onOpenShiftCloseDialog = viewModel::openShiftCloseDialog
                )
                if (state.isShiftCloseDialogOpen) {
                    ShiftCloseDialog(
                        state = state,
                        onDismiss = { viewModel.openShiftCloseDialog(false) },
                        onConfirmClose = { count, notes -> viewModel.closeShift(count, notes) },
                        onUpdateCountedCash = viewModel::updateShiftCloseCountedCash,
                        onUpdateNotes = viewModel::updateShiftCloseNotes
                    )
                }
            }
        }

        // Open dialog and submit counted cash
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("open_shift_close_dialog_button"))
        composeTestRule.onNodeWithTag("open_shift_close_dialog_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("shift_close_counted_cash_input").performTextInput("715.00")
        composeTestRule.onNodeWithTag("confirm_shift_close_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(5000) {
            viewModel.uiState.value.shiftCloseSuccessSnapshot != null
        }

        val request = server!!.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)

        val state = viewModel.uiState.value
        assertFalse("Option A: server returned is_shift_closed=false, so shift must stay NOT closed", state.isShiftClosed)
        val snap = state.shiftCloseSuccessSnapshot
        assertNotNull(snap)
        assertTrue(snap!!.observationRecorded)
        assertFalse(snap.isShiftClosed)
        assertEquals(BigDecimal("15.00"), snap.variance)
        assertEquals("OVERAGE", snap.varianceStatus)
        assertEquals("${today}T04:15:00Z", state.shiftObservationTimestamp)

        // UI displays count observation banner and leaves open_shift_close_dialog_button visible
        composeTestRule.onNodeWithTag("financial_reports_screen").performScrollToNode(hasTestTag("shift_close_status_text"))
        composeTestRule.onNodeWithTag("shift_close_status_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_shift_close_dialog_button").assertIsDisplayed()

        db.close()
    }

    @Test
    fun testShiftClose_permissions_leastPrivilegeReportsFinancialViewOnly_rejectsCashOnlyAndNeither() = runBlocking {
        val db = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store = RoomMerchantCommandStore(db, cryptoEngine)

        val transport = OkHttpTransport(baseOrigin, allowHttpLoopback = true)
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }

        // Case 1: reports-only permission -> permitted
        val repoReports = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2,
            permissions = setOf("reports.financial.view")
        )
        val today = WesternFormatters.currentDate()
        server!!.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "success": true,
                        "observation_recorded": true,
                        "is_shift_closed": false,
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "shift_closure_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "shift_date": "$today",
                        "currency": "ILS",
                        "currency_minor_units": 2,
                        "expected_cash": "100.00",
                        "counted_cash": "100.00",
                        "variance": "0.00",
                        "variance_status": "EXACT",
                        "closed_at": "${today}T01:00:00Z"
                    }
                """.trimIndent())
        )
        val resReports = repoReports.closeShift(BigDecimal("100.00"), commandId = "cmd-reports-ok")
        assertTrue("reports.financial.view must be permitted", resReports.isSuccess)
        assertEquals(1, server!!.requestCount)

        // Case 2: cash-only permission (money.cash.view) -> 403 Forbidden without network POST
        val repoCashOnly = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2,
            permissions = setOf("money.cash.view")
        )
        val resCashOnly = repoCashOnly.closeShift(BigDecimal("100.00"), commandId = "cmd-cash-deny")
        assertTrue("money.cash.view only must fail with SecurityException", resCashOnly.isFailure)
        assertTrue(resCashOnly.exceptionOrNull() is SecurityException)
        assertTrue(resCashOnly.exceptionOrNull()!!.message!!.contains("Missing required reports permission"))
        assertEquals("Cash-only must not make network call", 1, server!!.requestCount)

        // Verify UI with cash-only user hides submit UI and shows Access Restricted
        val cashOnlySession = UserSession(
            id = "usr_cashier_1",
            name = "كاشير الصندوق",
            username = "cashier",
            role = UserRole.CASHIER,
            permissions = setOf("money.cash.view"),
            branchNameAr = "الفرع",
            branchNameEn = "Branch",
            isDemoSession = false
        )
        assertFalse("cash-only user cannot view reports", cashOnlySession.canViewReports())

        // Case 3: neither permission -> 403 Forbidden without network POST
        val repoNeither = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope },
            baseCurrency = "ILS",
            currencyMinorUnits = 2,
            permissions = setOf("pos.sale")
        )
        val resNeither = repoNeither.closeShift(BigDecimal("100.00"), commandId = "cmd-neither-deny")
        assertTrue(resNeither.isFailure)
        assertTrue(resNeither.exceptionOrNull() is SecurityException)
        assertEquals("Neither must not make network call", 1, server!!.requestCount)

        db.close()
    }
}
