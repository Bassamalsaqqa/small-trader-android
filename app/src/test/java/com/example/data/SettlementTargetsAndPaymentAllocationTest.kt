package com.example.data

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.PaymentAllocationInput
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.SettlementCurrencyTotal
import com.example.model.SettlementTarget
import com.example.model.SettlementTargetsResponse
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.ui.screens.CustomerPaymentCollectionDialog
import com.example.ui.screens.NewSaleScreen
import com.example.ui.screens.PaySupplierDialog
import com.example.ui.screens.ProductsCatalogScreen
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Mobile V1 Settlement Targets and Payment Allocation Comprehensive Qualification Test Suite.
 *
 * Verifies:
 * 1. Wire + API Client Contract for Customer and Supplier settlement targets.
 * 2. Exact decimal arithmetic and allocations (full, partial, mixed invoice/purchase + opening balance).
 * 3. Keyset continuation semantics: empty targets with has_more=true does not claim debt settled.
 * 4. Boundary rejections: over-allocation, duplicate targets, cross-party 422, permission denied 403.
 * 5. Local Room Outbox durability & real-file reopen: preserves encrypted command payload and idempotency key across restarts.
 * 6. ViewModel paginated loading, state reset, and truthful feedback messaging.
 * 7. Compose UI interaction for Customer and Supplier settlement dialogs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettlementTargetsAndPaymentAllocationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val cryptoEngine = JvmAesGcmCryptoEngine()

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"
    private val validScope = CurrentAuthScope(companyId, actorId, deviceId, token = validToken, generation = 1L)
    private val baseOrigin = "https://api.example.com"

    private val targetCustomerId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val targetSupplierId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")

    class RecordingTransport(var handler: (TransportRequest) -> TransportResponse) : ReadClientTransport {
        val requests = mutableListOf<TransportRequest>()
        override fun execute(request: TransportRequest): TransportResponse {
            requests.add(request)
            return handler(request)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_alloc_outbox_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 1. Wire & Client: Customer Settlement Targets & Allocation
    // -------------------------------------------------------------------------

    @Test
    fun testCustomerSettlementTargetsWireContract_parsesTargetsAndExactTotals() = runBlocking {
        val jsonResponse = """
            {
                "customer_id": "${targetCustomerId.value}",
                "customer_name": "سوبرماركت النصر",
                "currency": "ILS",
                "currency_minor_units": 2,
                "total_targets": 2,
                "targets": [
                    {
                        "target_type": "invoice",
                        "target_id": "inv_101",
                        "target_number": "INV-2026-001",
                        "business_date": "2026-11-20",
                        "currency": "ILS",
                        "currency_minor_units": 2,
                        "original_amount_exact": "500.00",
                        "allocated_amount_exact": "200.00",
                        "remaining_amount_exact": "300.00"
                    },
                    {
                        "target_type": "opening_trade_item",
                        "target_id": "op_99",
                        "target_number": "OP-CUST-99",
                        "business_date": "2026-01-01",
                        "currency": "ILS",
                        "currency_minor_units": 2,
                        "original_amount_exact": "150.50",
                        "allocated_amount_exact": "0.00",
                        "remaining_amount_exact": "150.50"
                    }
                ],
                "totals": {
                    "remaining_by_currency": {
                        "ILS": {
                            "amount_exact": "450.50",
                            "minor_units": 2
                        }
                    },
                    "original_by_currency": {
                        "ILS": {
                            "amount_exact": "650.50",
                            "minor_units": 2
                        }
                    }
                },
                "pagination": {
                    "has_more": false,
                    "next_cursor": null
                }
            }
        """.trimIndent()

        val transport = RecordingTransport { req ->
            assertTrue(req.path.contains("/settlement-targets"))
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = jsonResponse
            )
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val response = repo.getCustomerSettlementTargets(targetCustomerId.value, limit = 100).getOrThrow()

        assertEquals(targetCustomerId.value, response.partyId)
        assertEquals(2, response.targets.size)

        val target1 = response.targets[0]
        assertEquals("invoice", target1.targetType)
        assertEquals("inv_101", target1.targetId)
        assertEquals("INV-2026-001", target1.targetNumber)
        assertEquals(BigDecimal("300.00"), target1.remainingAmountExact)

        val target2 = response.targets[1]
        assertEquals("opening_trade_item", target2.targetType)
        assertEquals("op_99", target2.targetId)
        assertEquals(BigDecimal("150.50"), target2.remainingAmountExact)

        assertFalse(response.hasMore)
        assertEquals(BigDecimal("450.50"), response.totalsRemainingByCurrency["ILS"]?.amountExact)
    }

    @Test
    fun testCustomerPaymentRecord_withMixedAllocationsAndUnallocatedSurplus() = runBlocking {
        var recordedBody: String? = null
        val transport = RecordingTransport { req ->
            if (req.path.contains("/payments")) {
                recordedBody = req.body
                val ackResponse = """
                    {
                        "payment_id": "cpay_ack_001",
                        "receipt_id": "cpay_ack_001",
                        "id": "cpay_ack_001",
                        "customer_id": "${targetCustomerId.value}",
                        "amount_exact": "500.00",
                        "allocated_amount_exact": "450.50",
                        "unallocated_amount_exact": "49.50",
                        "is_advance": false,
                        "currency": "ILS",
                        "allocations": [
                            {
                                "target_type": "invoice",
                                "target_id": "inv_101",
                                "allocated_amount_exact": "300.00"
                            },
                            {
                                "target_type": "opening_trade_item",
                                "target_id": "op_99",
                                "allocated_amount_exact": "150.50"
                            }
                        ]
                    }
                """.trimIndent()
                return@RecordingTransport TransportResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = ackResponse
                )
            }
            TransportResponse(404, emptyMap(), "{}")
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val allocs = listOf(
            PaymentAllocationInput("invoice", "inv_101", BigDecimal("300.00")),
            PaymentAllocationInput("opening_trade_item", "op_99", BigDecimal("150.50"))
        )

        val result = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("500.00"),
            note = "Mixed settlement test",
            paymentId = "cpay_test_001",
            allocations = allocs
        ).getOrThrow()

        assertNotNull(recordedBody)
        assertTrue("Must contain allocations array", recordedBody!!.contains("\"allocations\":["))
        assertTrue("Must contain invoice target", recordedBody!!.contains("\"target_id\":\"inv_101\""))
        assertTrue("Must contain opening trade item target", recordedBody!!.contains("\"target_id\":\"op_99\""))

        assertEquals("cpay_ack_001", result.id)
        assertEquals(BigDecimal("500.00"), result.creditExact)
        assertEquals(BigDecimal("450.50"), result.allocatedAmountExact)
        assertEquals(BigDecimal("49.50"), result.unallocatedAmountExact)
        assertFalse(result.isAdvance)
        assertEquals(2, result.allocations.size)
    }

    // -------------------------------------------------------------------------
    // 2. Wire & Client: Supplier Settlement Targets & Allocation
    // -------------------------------------------------------------------------

    @Test
    fun testSupplierSettlementTargetsWireContract_parsesPurchaseAndOpeningTargets() = runBlocking {
        val jsonResponse = """
            {
                "supplier_id": "${targetSupplierId.value}",
                "supplier_name": "شركة القدس للتجارة",
                "currency": "ILS",
                "currency_minor_units": 2,
                "total_targets": 2,
                "targets": [
                    {
                        "target_type": "purchase",
                        "target_id": "pur_202",
                        "target_number": "BILL-202",
                        "business_date": "2026-11-21",
                        "currency": "ILS",
                        "currency_minor_units": 2,
                        "original_amount_exact": "800.00",
                        "allocated_amount_exact": "300.00",
                        "remaining_amount_exact": "500.00"
                    },
                    {
                        "target_type": "opening_trade_item",
                        "target_id": "op_sup_88",
                        "target_number": "OP-SUP-88",
                        "business_date": "2026-01-01",
                        "currency": "ILS",
                        "currency_minor_units": 2,
                        "original_amount_exact": "250.00",
                        "allocated_amount_exact": "0.00",
                        "remaining_amount_exact": "250.00"
                    }
                ],
                "totals": {
                    "remaining_by_currency": {
                        "ILS": {
                            "amount_exact": "750.00",
                            "minor_units": 2
                        }
                    },
                    "original_by_currency": {
                        "ILS": {
                            "amount_exact": "1050.00",
                            "minor_units": 2
                        }
                    }
                },
                "pagination": {
                    "has_more": false,
                    "next_cursor": null
                }
            }
        """.trimIndent()

        val transport = RecordingTransport { req ->
            assertTrue(req.path.contains("/settlement-targets"))
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = jsonResponse
            )
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val response = repo.getSupplierSettlementTargets(targetSupplierId.value, limit = 100).getOrThrow()

        assertEquals(targetSupplierId.value, response.partyId)
        assertEquals(2, response.targets.size)
        assertEquals("purchase", response.targets[0].targetType)
        assertEquals(BigDecimal("500.00"), response.targets[0].remainingAmountExact)
        assertEquals("opening_trade_item", response.targets[1].targetType)
        assertEquals(BigDecimal("250.00"), response.targets[1].remainingAmountExact)
        assertFalse(response.hasMore)
        assertEquals(BigDecimal("750.00"), response.totalsRemainingByCurrency["ILS"]?.amountExact)
    }

    @Test
    fun testSupplierPaymentRecord_withFullAllocation() = runBlocking {
        var recordedBody: String? = null
        val transport = RecordingTransport { req ->
            if (req.path.contains("/payments")) {
                recordedBody = req.body
                val ackResponse = """
                    {
                        "payment_id": "spay_ack_002",
                        "receipt_id": "spay_ack_002",
                        "id": "spay_ack_002",
                        "supplier_id": "${targetSupplierId.value}",
                        "amount_exact": "500.00",
                        "allocated_amount_exact": "500.00",
                        "unallocated_amount_exact": "0.00",
                        "is_advance": false,
                        "currency": "ILS",
                        "allocations": [
                            {
                                "target_type": "purchase",
                                "target_id": "pur_202",
                                "allocated_amount_exact": "500.00"
                            }
                        ]
                    }
                """.trimIndent()
                return@RecordingTransport TransportResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = ackResponse
                )
            }
            TransportResponse(404, emptyMap(), "{}")
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val allocs = listOf(
            PaymentAllocationInput("purchase", "pur_202", BigDecimal("500.00"))
        )
        val operator = UserSession("op1", "Cashier", "cashier1", UserRole.CASHIER, "الفرع الرئيسي", "Main Branch", false)
        val result = repo.recordSupplierPayment(
            supplierId = targetSupplierId.value,
            amount = BigDecimal("500.00"),
            note = "Full purchase payment",
            operator = operator,
            paymentId = "spay_test_001",
            allocations = allocs
        ).getOrThrow()

        assertNotNull(recordedBody)
        assertEquals("spay_ack_002", result.id)
        assertEquals(BigDecimal("500.00"), result.allocatedAmountExact)
        assertEquals(BigDecimal("0.00"), result.unallocatedAmountExact)
    }

    // -------------------------------------------------------------------------
    // 3. Keyset Continuation Semantics: Empty Targets with has_more=true
    // -------------------------------------------------------------------------

    @Test
    fun testContinuationSemantics_emptyTargetsWithHasMoreTrue_doesNotClaimDebtFree() = runBlocking {
        val continuationResponse = """
            {
                "customer_id": "${targetCustomerId.value}",
                "customer_name": "محل الاختبار",
                "currency": "ILS",
                "currency_minor_units": 2,
                "total_targets": 0,
                "targets": [],
                "totals": {
                    "remaining_by_currency": {},
                    "original_by_currency": {}
                },
                "pagination": {
                    "has_more": true,
                    "next_cursor": "eyJpZCI6IjIwMDAifQ=="
                }
            }
        """.trimIndent()

        val transport = RecordingTransport {
            TransportResponse(200, mapOf("Content-Type" to "application/json"), continuationResponse)
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val resp = repo.getCustomerSettlementTargets(targetCustomerId.value).getOrThrow()

        assertTrue("Targets list is empty for this chunk", resp.targets.isEmpty())
        assertTrue("has_more must be true indicating continued scan", resp.hasMore)
        assertNotNull("Next cursor must be present for continuation", resp.nextCursor)
    }

    // -------------------------------------------------------------------------
    // 4. Boundary Rejections: Over-Allocation, Duplicates, Cross-Party 422, 403
    // -------------------------------------------------------------------------

    @Test
    fun testOverAllocationRejection_failsLocallyWithoutNetwork() = runBlocking {
        val transport = RecordingTransport {
            fail("Network request should not be made when local over-allocation validation fails")
            TransportResponse(500, emptyMap(), "{}")
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val allocs = listOf(
            PaymentAllocationInput("invoice", "inv_1", BigDecimal("300.00")),
            PaymentAllocationInput("invoice", "inv_2", BigDecimal("300.00"))
        )

        val res = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("500.00"),
            note = "note",
            allocations = allocs
        )

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("exceed total payment amount"))
    }

    @Test
    fun testDuplicateTargetRejection_failsLocallyWithoutNetwork() = runBlocking {
        val transport = RecordingTransport {
            fail("Network request should not be made when duplicate target validation fails")
            TransportResponse(500, emptyMap(), "{}")
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val allocs = listOf(
            PaymentAllocationInput("invoice", "inv_1", BigDecimal("100.00")),
            PaymentAllocationInput("invoice", "inv_1", BigDecimal("200.00"))
        )

        val res = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("500.00"),
            note = "note",
            allocations = allocs
        )

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("Duplicate allocation target detected"))
    }

    @Test
    fun testCrossPartyOrOverTargetServer422_surfacesErrorTruthfully() = runBlocking {
        val error422Response = """
            {
                "message": "Target inv_999 does not belong to customer ${targetCustomerId.value} | الهدف المحدد لا يتبع لهذا الزبون",
                "errors": {
                    "allocations.0.target_id": ["Target does not belong to customer"]
                }
            }
        """.trimIndent()

        val transport = RecordingTransport {
            TransportResponse(422, mapOf("Content-Type" to "application/json"), error422Response)
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val res = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("200.00"),
            note = "note",
            allocations = listOf(PaymentAllocationInput("invoice", "inv_999", BigDecimal("200.00")))
        )

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("Target inv_999 does not belong"))
    }

    @Test
    fun testSupplierTargetsCostPermissionDenied403_failsTruthfully() = runBlocking {
        val error403Response = """
            {
                "message": "This action is unauthorized. Viewing supplier settlement targets requires purchasing.cost.view permission."
            }
        """.trimIndent()

        val transport = RecordingTransport {
            TransportResponse(403, mapOf("Content-Type" to "application/json"), error403Response)
        }

        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope }
        )

        val res = repo.getSupplierSettlementTargets(targetSupplierId.value)
        assertTrue(res.isFailure)
        val msg = res.exceptionOrNull()!!.message ?: ""
        assertTrue(msg.contains("403") || msg.contains("purchasing.cost.view") || msg.contains("FORBIDDEN"))
    }

    // -------------------------------------------------------------------------
    // 5. Room Outbox Durability & Reopen (Local Durability Qualification)
    // -------------------------------------------------------------------------

    @Test
    fun testRoomOutboxDurabilityReopen_preservesEncryptedAllocationsAndKey() = runBlocking {
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        val commandId = "01JCV8X9M2NQW3TYZ4R5S67899"
        val idempotencyKey = "cpay-test-$commandId"
        val payloadJson = """
            {
                "customer_id": "${targetCustomerId.value}",
                "payment_amount_ils": 500.0,
                "note": "Outbox reopen qualification",
                "allocations": [
                    {
                        "target_type": "invoice",
                        "target_id": "inv_101",
                        "amount_exact": "300.00"
                    },
                    {
                        "target_type": "opening_trade_item",
                        "target_id": "op_99",
                        "amount_exact": "200.00"
                    }
                ]
            }
        """.trimIndent()
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        val payloadDigest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes).joinToString("") { "%02x".format(it) }

        val rec1 = store1.recordPendingCommand(
            companyId = companyId.value,
            commandId = commandId,
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "customer_payment",
            idempotencyKey = idempotencyKey,
            payloadDigest = payloadDigest,
            canonicalPayloadBytes = payloadBytes
        )
        assertEquals(CommandStatus.PENDING, rec1.status)
        assertTrue(store1.transitionStatus(companyId.value, commandId, CommandStatus.PENDING, CommandStatus.SUBMITTED))

        db1.close()

        assertTrue("SQLite file must exist physically on disk", dbFile.exists())
        assertTrue("SQLite file size must be non-zero", dbFile.length() > 0)

        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)

        val recoveredRecord = store2.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertNotNull("Command record must persist across connection reopen", recoveredRecord)
        assertEquals(CommandStatus.SUBMITTED, recoveredRecord!!.status)
        assertEquals(idempotencyKey, recoveredRecord.idempotencyKey)

        val decryptedBytes = store2.decryptPayload(recoveredRecord)
        val decryptedJson = String(decryptedBytes, Charsets.UTF_8)
        assertEquals(payloadJson, decryptedJson)
        assertTrue("Decrypted payload contains invoice target", decryptedJson.contains("inv_101"))
        assertTrue("Decrypted payload contains opening_trade_item target", decryptedJson.contains("op_99"))

        val ackResponseJson = """{"data":{"payment_id":"cpay_ack_reopen_1"}}"""
        val updated = store2.markCompleted(companyId.value, commandId, ackResponseJson.toByteArray(Charsets.UTF_8))
        assertTrue("Mark completed after reopen must succeed", updated)

        val finalRec = store2.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertEquals(CommandStatus.COMPLETED, finalRec!!.status)

        db2.close()
    }

    // -------------------------------------------------------------------------
    // 6. ViewModel: State Management, Pagination, Generation Reset
    // -------------------------------------------------------------------------

    @Test
    fun testViewModel_loadAndResetCustomerSettlementTargets() = runBlocking {
        val repo = OfflineDemoTraderRepository()
        val vm = TraderViewModel(repo)

        val customer = repo.getCustomers().first()

        vm.loadCustomerSettlementTargets(customer.id, resetPagination = true)
        val state = vm.uiState.value

        assertFalse(state.isLoadingCustomerSettlementTargets)

        vm.resetCustomerSettlementTargets()
        val resetState = vm.uiState.value
        assertTrue("Targets must be empty after reset", resetState.customerSettlementTargets.isEmpty())
        assertFalse(resetState.isLoadingCustomerSettlementTargets)
    }

    // -------------------------------------------------------------------------
    // 7. Compose UI: Customer Payment Collection Dialog
    // -------------------------------------------------------------------------

    @Test
    fun testCustomerPaymentCollectionDialog_showsUnappliedNoticeWhenNoTargetsSelected() {
        val customer = Customer(
            id = "cust_dialog_1",
            nameAr = "أحمد محمد",
            nameEn = "Ahmed Mohamed",
            balanceExact = BigDecimal("500.00")
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPaymentCollectionDialog(
                    customer = customer,
                    lang = AppLanguage.AR,
                    onDismiss = {},
                    onConfirmPayment = { _, _, _, _ -> },
                    settlementTargets = emptyList(),
                    isLoadingTargets = false,
                    targetsHasMore = false
                )
            }
        }

        composeTestRule.onNodeWithTag("customer_unapplied_advance_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("payment_amount_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").assertIsDisplayed()
    }

    @Test
    fun testCustomerPaymentCollectionDialog_rendersTargetsAndSelectsAllocation() {
        val customer = Customer(
            id = "cust_dialog_2",
            nameAr = "سعيد علي",
            nameEn = "Saeed Ali",
            balanceExact = BigDecimal("300.00")
        )

        val target = SettlementTarget(
            targetType = "invoice",
            targetId = "inv_ui_1",
            targetNumber = "INV-99",
            businessDate = "2026-11-20",
            dueDate = null,
            daysOverdue = 0,
            currency = "ILS",
            currencyMinorUnits = 2,
            originalAmountExact = BigDecimal("300.00"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("300.00")
        )

        var confirmedAllocs: List<PaymentAllocationInput>? = null
        var confirmedAmount: BigDecimal? = null

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPaymentCollectionDialog(
                    customer = customer,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onConfirmPayment = { _, _, _, _ -> },
                    onConfirmPaymentExact = { _, amount, _, _, allocs ->
                        confirmedAmount = amount
                        confirmedAllocs = allocs
                    },
                    settlementTargets = listOf(target),
                    isLoadingTargets = false,
                    targetsHasMore = false
                )
            }
        }

        composeTestRule.onNodeWithTag("target_checkbox_invoice_inv_ui_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_checkbox_invoice_inv_ui_1").performClick()
        composeTestRule.onNodeWithTag("customer_allocation_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").performClick()

        assertNotNull(confirmedAllocs)
        assertEquals(1, confirmedAllocs!!.size)
        assertEquals("inv_ui_1", confirmedAllocs!![0].targetId)
        assertEquals(BigDecimal("300.00"), confirmedAllocs!![0].amountExact)
        assertEquals(BigDecimal("300.00"), confirmedAmount)
    }

    @Test
    fun testCustomerPaymentCollectionDialog_showsContinuationBannerWhenEmptyWithHasMore() {
        val customer = Customer(
            id = "cust_dialog_3",
            nameAr = "محمود حسن",
            nameEn = "Mahmoud Hassan",
            balanceExact = BigDecimal("200.00")
        )

        var loadMoreClicked = false

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPaymentCollectionDialog(
                    customer = customer,
                    lang = AppLanguage.AR,
                    onDismiss = {},
                    onConfirmPayment = { _, _, _, _ -> },
                    settlementTargets = emptyList(),
                    isLoadingTargets = false,
                    targetsHasMore = true,
                    onLoadMoreTargets = { loadMoreClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("customer_targets_continuation_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load_more_targets_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load_more_targets_button").performClick()
        assertTrue("Load more callback must be triggered", loadMoreClicked)
    }

    // -------------------------------------------------------------------------
    // 8. Compose UI: Pay Supplier Dialog
    // -------------------------------------------------------------------------

    @Test
    fun testPaySupplierDialog_showsUnappliedNoticeWhenNoTargetsSelected() {
        val supplier = Supplier(
            id = "sup_dialog_1",
            nameAr = "شركة الأمل",
            nameEn = "Al-Amal Co",
            balanceOwedExact = BigDecimal("650.00")
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                PaySupplierDialog(
                    supplier = supplier,
                    lang = AppLanguage.AR,
                    onDismiss = {},
                    onPaySupplier = { _, _, _ -> Result.success(SupplierPayment("sp_1", "sup_1", "today", 100.0, "")) },
                    settlementTargets = emptyList(),
                    isLoadingTargets = false,
                    targetsHasMore = false
                )
            }
        }

        composeTestRule.onNodeWithTag("supplier_unapplied_advance_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("supplier_pay_amount_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").assertIsDisplayed()
    }

    @Test
    fun testPaySupplierDialog_rendersPurchaseTargetAndConfirmsExact() {
        val supplier = Supplier(
            id = "sup_dialog_2",
            nameAr = "شركة القدس للتجارة",
            nameEn = "Jerusalem Trading Co",
            balanceOwedExact = BigDecimal("450.00")
        )

        val target = SettlementTarget(
            targetType = "purchase",
            targetId = "pur_ui_1",
            targetNumber = "BILL-88",
            businessDate = "2026-11-21",
            dueDate = null,
            daysOverdue = 0,
            currency = "ILS",
            currencyMinorUnits = 2,
            originalAmountExact = BigDecimal("450.00"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("450.00")
        )

        var confirmedAllocs: List<PaymentAllocationInput>? = null
        var confirmedAmount: BigDecimal? = null

        composeTestRule.setContent {
            SmallTraderTheme {
                PaySupplierDialog(
                    supplier = supplier,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onPaySupplier = { _, _, _ -> Result.success(SupplierPayment("sp_2", "sup_2", "today", 450.0, "")) },
                    onPaySupplierExactWithResult = { _, amount, _, _, allocs, onComplete ->
                        confirmedAmount = amount
                        confirmedAllocs = allocs
                        onComplete(Result.success(SupplierPayment("sp_ack", supplier.id, "today", amount.toDouble(), "")))
                    },
                    settlementTargets = listOf(target),
                    isLoadingTargets = false,
                    targetsHasMore = false
                )
            }
        }

        composeTestRule.onNodeWithTag("target_checkbox_purchase_pur_ui_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_checkbox_purchase_pur_ui_1").performClick()
        composeTestRule.onNodeWithTag("supplier_allocation_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").performClick()

        assertNotNull(confirmedAllocs)
        assertEquals(1, confirmedAllocs!!.size)
        assertEquals("pur_ui_1", confirmedAllocs!![0].targetId)
        assertEquals(BigDecimal("450.00"), confirmedAllocs!![0].amountExact)
        assertEquals(BigDecimal("450.00"), confirmedAmount)
    }

    @Test
    fun testPaySupplierDialog_showsContinuationBannerWhenEmptyWithHasMore() {
        val supplier = Supplier(
            id = "sup_dialog_3",
            nameAr = "مؤسسة بركة",
            nameEn = "Baraka Est",
            balanceOwedExact = BigDecimal("150.00")
        )

        var loadMoreClicked = false

        composeTestRule.setContent {
            SmallTraderTheme {
                PaySupplierDialog(
                    supplier = supplier,
                    lang = AppLanguage.AR,
                    onDismiss = {},
                    onPaySupplier = { _, _, _ -> Result.success(SupplierPayment("sp_3", "sup_3", "today", 150.0, "")) },
                    settlementTargets = emptyList(),
                    isLoadingTargets = false,
                    targetsHasMore = true,
                    onLoadMoreTargets = { loadMoreClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("supplier_targets_continuation_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load_more_supplier_targets_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load_more_supplier_targets_button").performClick()
        assertTrue("Supplier load more callback must be triggered", loadMoreClicked)
    }

    // -------------------------------------------------------------------------
    // 9. Contract Regressions: JOD 3-Digits, Strict Scale, Fractional GRN & Search Retry
    // -------------------------------------------------------------------------

    @Test
    fun testCustomerPaymentCollectionDialog_jodThreeDecimals_andNullableBalance() {
        val customer = Customer(
            id = "cust_jod_1",
            nameAr = "محل الهدى",
            nameEn = "Al-Huda Store",
            balanceExact = null
        )

        val target = SettlementTarget(
            targetType = "invoice",
            targetId = "inv_jod_1",
            targetNumber = "INV-JOD-001",
            businessDate = "2026-11-22",
            dueDate = null,
            daysOverdue = 0,
            currency = "JOD",
            currencyMinorUnits = 3,
            originalAmountExact = BigDecimal("150.500"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("150.500")
        )

        var confirmedAllocs: List<PaymentAllocationInput>? = null
        var confirmedAmount: BigDecimal? = null

        composeTestRule.setContent {
            SmallTraderTheme {
                CustomerPaymentCollectionDialog(
                    customer = customer,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onConfirmPayment = { _, _, _, _ -> },
                    onConfirmPaymentExact = { _, amount, _, _, allocs ->
                        confirmedAmount = amount
                        confirmedAllocs = allocs
                    },
                    settlementTargets = listOf(target),
                    isLoadingTargets = false,
                    targetsHasMore = false,
                    currency = "JOD",
                    minorUnits = 3
                )
            }
        }

        // Nullable unknown balance renders "Balance unavailable" without crash or Double conversion
        composeTestRule.onNodeWithText("Balance unavailable").assertIsDisplayed()

        // Click target checkbox
        composeTestRule.onNodeWithTag("target_checkbox_invoice_inv_jod_1").performClick()
        composeTestRule.onNodeWithTag("customer_allocation_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_customer_payment_button").performClick()

        assertNotNull(confirmedAllocs)
        assertEquals(1, confirmedAllocs!!.size)
        assertEquals("inv_jod_1", confirmedAllocs!![0].targetId)
        // Normalized exact scale 3 for JOD without loss or HALF_UP rounding
        assertEquals(BigDecimal("150.500"), confirmedAllocs!![0].amountExact)
        assertEquals(3, confirmedAllocs!![0].amountExact.scale())
        assertEquals(BigDecimal("150.500"), confirmedAmount)
        assertEquals(3, confirmedAmount!!.scale())
    }

    @Test
    fun testPaySupplierDialog_jodThreeDecimals_andNullableBalance() {
        val supplier = Supplier(
            id = "sup_jod_1",
            nameAr = "مؤسسة الأردن",
            nameEn = "Jordan Trading",
            balanceOwedExact = null
        )

        val target = SettlementTarget(
            targetType = "purchase",
            targetId = "pur_jod_1",
            targetNumber = "BILL-JOD-99",
            businessDate = "2026-11-22",
            dueDate = null,
            daysOverdue = 0,
            currency = "JOD",
            currencyMinorUnits = 3,
            originalAmountExact = BigDecimal("75.250"),
            allocatedAmountExact = BigDecimal.ZERO,
            remainingAmountExact = BigDecimal("75.250")
        )

        var confirmedAllocs: List<PaymentAllocationInput>? = null
        var confirmedAmount: BigDecimal? = null

        composeTestRule.setContent {
            SmallTraderTheme {
                PaySupplierDialog(
                    supplier = supplier,
                    lang = AppLanguage.EN,
                    onDismiss = {},
                    onPaySupplier = { _, _, _ -> Result.success(SupplierPayment("sp_jod", "sup_jod_1", "today", 75.25, "")) },
                    onPaySupplierExactWithResult = { _, amount, _, _, allocs, onComplete ->
                        confirmedAmount = amount
                        confirmedAllocs = allocs
                        onComplete(Result.success(SupplierPayment("sp_jod_ack", supplier.id, "today", amount.toDouble(), "")))
                    },
                    settlementTargets = listOf(target),
                    isLoadingTargets = false,
                    targetsHasMore = false,
                    currency = "JOD",
                    minorUnits = 3
                )
            }
        }

        // Nullable unknown balance handled
        composeTestRule.onNodeWithText("Balance unavailable").assertIsDisplayed()

        // Click target checkbox
        composeTestRule.onNodeWithTag("target_checkbox_purchase_pur_jod_1").performClick()
        composeTestRule.onNodeWithTag("supplier_allocation_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_supplier_payment_button").performClick()

        assertNotNull(confirmedAllocs)
        assertEquals(1, confirmedAllocs!!.size)
        assertEquals("pur_jod_1", confirmedAllocs!![0].targetId)
        assertEquals(BigDecimal("75.250"), confirmedAllocs!![0].amountExact)
        assertEquals(3, confirmedAllocs!![0].amountExact.scale())
        assertEquals(BigDecimal("75.250"), confirmedAmount)
        assertEquals(3, confirmedAmount!!.scale())
    }

    @Test
    fun testOfflineAndProductionRepository_fractionalGoodsReceipt_lossless2_5Units() = runBlocking {
        val offlineRepo = OfflineDemoTraderRepository()
        val operator = SampleCatalog.demoStaff.first()

        val product = offlineRepo.getProducts().first { it.isStockTracked }
        val initialStockExact = product.stockCountExact
        val billResult = offlineRepo.recordStructuredPurchaseBill(
            supplierId = offlineRepo.getSuppliers().first().id,
            lines = listOf(
                PurchaseLineItem(
                    productId = product.id,
                    productNameAr = product.nameAr,
                    productNameEn = product.nameEn,
                    quantityExact = BigDecimal("10.00"),
                    unitCostExact = BigDecimal("20.00"),
                    receivedQuantityExact = BigDecimal.ZERO
                )
            ),
            paidNowIls = 0.0,
            operator = operator,
            note = "Fractional Goods Receipt Test"
        )
        assertTrue(billResult.isSuccess)
        val bill = billResult.getOrThrow()
        val lineId = bill.lines.first().id

        // Confirm fractional 2.5 receipt
        val fractionalQty = BigDecimal("2.5")
        val grnResult = offlineRepo.confirmPhysicalGoodsReceipt(
            billId = bill.id,
            productId = product.id,
            receivedQty = fractionalQty,
            batchNote = "Batch 2.5kg",
            operator = operator,
            lineId = lineId
        )
        assertTrue(grnResult.isSuccess)
        val movement = grnResult.getOrThrow()

        // Verify exact decimal preservation
        assertEquals(fractionalQty, movement.deltaExact)
        assertEquals(initialStockExact + fractionalQty, movement.resultingStockExact)

        val updatedBill = offlineRepo.getPurchaseBills().first { it.id == bill.id }
        assertEquals(fractionalQty, updatedBill.receivedQuantityExact)
        assertEquals(fractionalQty, updatedBill.lines.first().receivedQuantityExact)
        assertFalse(updatedBill.isReceived)
    }

    @Test
    fun testProductionRepository_strictCurrencyScaleNormalization_andExcessPrecisionRejection() = runBlocking {
        val transport = RecordingTransport { _ ->
            TransportResponse(200, mapOf("Content-Type" to "application/json"), "{\"id\":\"ack_1\"}")
        }
        val apiClient = MobileMerchantApiClient(transport, baseOrigin) { validToken }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = { validScope },
            baseCurrency = "JOD",
            currencyMinorUnits = 3
        )

        // 1. Amount with scale <= 3 normalizes with UNNECESSARY
        val validAlloc = listOf(PaymentAllocationInput("invoice", "inv_1", BigDecimal("50.00")))
        val normResult = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("50.00"),
            note = "Valid JOD scale",
            allocations = validAlloc
        )
        assertTrue("Normalizing scale <= 3 with UNNECESSARY must succeed: ${normResult.exceptionOrNull()?.message}", normResult.isSuccess)

        // 2. Amount with excess fractional precision (e.g. 4 decimals for JOD) is rejected with precision error
        val excessResult = repo.recordCustomerPayment(
            customerId = targetCustomerId.value,
            amount = BigDecimal("50.1234"),
            note = "Excess precision"
        )
        assertTrue("Excessive precision must be rejected", excessResult.isFailure)
        assertTrue(excessResult.exceptionOrNull()?.message?.contains("exceeds allowed precision") == true)
    }

    @Test
    fun testProductCatalog_truthfulHasMore_rendersPartialNoticeAndBanner() {
        val base = SampleCatalog.initialProducts
        val sampleProducts = (0 until 30).map { i ->
            base[i % base.size].copy(id = "prod_test_$i")
        }

        val stateWithMore = TraderUiState(
            products = sampleProducts,
            productsHasMore = true,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                ProductsCatalogScreen(
                    state = stateWithMore,
                    onSearchChange = {},
                    onCategorySelect = {},
                    onProductClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("catalog_limited_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("30+ registered (partial catalog)", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-440dpi")
    fun testProductSearchErrorRetry_triggersCallback_inCatalog() {
        var catalogRetriedQuery: String? = null

        val catalogErrorState = TraderUiState(
            searchQuery = "biscuit",
            productSearchQuery = "biscuit",
            productSearchError = "Network connection timeout",
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                ProductsCatalogScreen(
                    state = catalogErrorState,
                    onSearchChange = { q -> catalogRetriedQuery = q },
                    onCategorySelect = {},
                    onProductClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("product_search_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_search_retry_button").performClick()
        assertEquals("biscuit", catalogRetriedQuery)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-440dpi")
    fun testProductSearchErrorRetry_triggersCallback_inNewSale() {
        var newSaleRetriedQuery: String? = null

        val newSaleErrorState = TraderUiState(
            searchQuery = "juice",
            productSearchQuery = "juice",
            productSearchError = "Server unavailable 503",
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme {
                NewSaleScreen(
                    state = newSaleErrorState,
                    onSearchQueryChange = { q -> newSaleRetriedQuery = q },
                    onSelectCategory = {},
                    onAddProduct = {},
                    onDecrementProduct = {},
                    onRemoveProduct = {},
                    onClearBasket = {},
                    onOpenScanner = {},
                    onOpenCustomItem = {},
                    onProceedToInvoiceReview = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("product_search_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_search_retry_button").performClick()
        assertEquals("juice", newSaleRetriedQuery)
    }
}
