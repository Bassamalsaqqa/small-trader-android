package com.example.data

import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.merchantread.CustomerStatementAging
import com.example.data.mobile.merchantread.CustomerStatementCurrencySummary
import com.example.data.mobile.merchantread.CustomerStatementEntry
import com.example.data.mobile.merchantread.LiveCustomerStatement
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.PaymentAllocationInput
import com.example.model.Supplier
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.viewmodel.TraderViewModel
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPrecisionAndRefreshContractTest {

    private val companyA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceA = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val customerA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val customerB = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789E")
    private val supplierA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val cashAccountA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789F")
    private val targetInvA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S00001")
    private val targetPurA = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S00002")
    private val validToken = "1|sanctum_valid_token_test_1234567890_abcdef"

    private val validScope = CurrentAuthScope(
        companyId = companyA,
        actorId = actorA,
        deviceId = deviceA,
        token = validToken,
        generation = 1L
    )

    private val fullPermissions = setOf(
        "pos.sale",
        "purchases.manage",
        "expenses.manage",
        "reports.financial.view",
        "customers.view",
        "customers.manage",
        "money.receipt.create",
        "vendors.view",
        "vendors.manage",
        "money.payment.create"
    )

    private val ownerSession = UserSession(
        id = actorA.value,
        name = "Owner",
        username = "owner",
        role = UserRole.OWNER,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch",
        isDemoSession = false,
        permissions = fullPermissions
    )

    private fun seedCustomer(repo: ProductionTraderRepository, customer: Customer) {
        val field = ProductionTraderRepository::class.java.getDeclaredField("_customersFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<Customer>>
        flow.value = listOf(customer)
    }

    private fun seedSupplier(repo: ProductionTraderRepository, supplier: Supplier) {
        val field = ProductionTraderRepository::class.java.getDeclaredField("_suppliersFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<Supplier>>
        flow.value = listOf(supplier)
    }

    private fun newRepo(
        transport: ReadClientTransport,
        baseCurrency: String = "ILS",
        minorUnits: Int = 2,
        commandStore: InMemoryMerchantCommandStore = InMemoryMerchantCommandStore()
    ): ProductionTraderRepository {
        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )
        return ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyA,
            actorId = actorA,
            deviceId = deviceA,
            commandStore = commandStore,
            authScopeProvider = { validScope },
            baseCurrency = baseCurrency,
            currencyMinorUnits = minorUnits,
            permissions = fullPermissions
        )
    }

    private fun exactPaymentSnapshotJson(
        id: String,
        partyField: String,
        partyId: String,
        balanceField: String,
        targetType: String,
        targetId: String
    ): String = """
        {
          "id": "$id",
          "$partyField": "$partyId",
          "amount": "1.23",
          "amount_exact": "1.234567",
          "allocated_amount": "0.23",
          "allocated_amount_exact": "0.234567",
          "unallocated_amount": "1.00",
          "unallocated_amount_exact": "1.000000",
          "is_advance": false,
          "posted_at": "2026-11-28",
          "currency": "USD",
          "$balanceField": "8.765433",
          "allocations": [
            {
              "target_type": "$targetType",
              "target_id": "$targetId",
              "amount": "0.23",
              "amount_exact": "0.234567",
              "allocated_amount": "0.23",
              "allocated_amount_exact": "0.234567"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun malformedPresentExactFields_rejectPostAndRecovery_withoutRotatingEitherPaymentDraft() = runBlocking {
        for (supplier in listOf(false, true)) {
            for (field in listOf("amount_exact", "allocated_amount_exact", "unallocated_amount_exact", "allocation")) {
                val valid = exactPaymentSnapshotJson(
                    "01JCVPAYMALFORMED00000001", if (supplier) "supplier_id" else "customer_id",
                    if (supplier) supplierA.value else customerA.value,
                    if (supplier) "balance_owed" else "balance", if (supplier) "purchase" else "invoice",
                    if (supplier) targetPurA.value else targetInvA.value
                )
                val malformed = if (field == "allocation") valid.replace("\"amount_exact\": \"0.234567\"", "\"amount_exact\": \"invalid\"")
                    else valid.replace(Regex("\"$field\": \"[^\"]*\""), "\"$field\": \"invalid\"")
                val store = InMemoryMerchantCommandStore()
                var posts = 0
                val repo = newRepo(object : ReadClientTransport {
                    override fun execute(request: TransportRequest): TransportResponse {
                        val body = if (request.method == HttpMethod.POST) {
                            posts++
                            malformed
                        } else {
                            val key = request.queryParams["idempotency_key"]
                            """{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$key","response_snapshot":$malformed}"""
                        }
                        return TransportResponse(200, mapOf("Content-Type" to "application/json"), body)
                    }
                }, commandStore = store)
                val form = if (supplier) "supplier_payment" else "customer_payment"
                val op = repo.getDraftOperationId(form)
                repeat(2) {
                    val result = if (supplier) repo.recordSupplierPayment(
                        supplierA.value, BigDecimal("1.23"), "Malformed exact", ownerSession, op, cashAccountA.value, "2026-11-28"
                    ) else repo.recordCustomerPayment(
                        customerA.value, BigDecimal("1.23"), "Malformed exact", op, cashAccountA.value, "2026-11-28"
                    )
                    assertTrue("$form/$field must fail before completion", result.isFailure)
                    assertEquals(op, repo.getDraftOperationId(form))
                    assertTrue(repo.isDraftRecovering(form))
                }
                assertEquals("Recovery must validate without a second POST", 1, posts)
            }
        }
    }

    private fun buildPaymentSnapshotWithExactRawTokens(
        id: String,
        supplier: Boolean,
        amountExactToken: String = "\"1.234567\"",
        allocatedExactToken: String = "\"0.234567\"",
        unallocatedExactToken: String = "\"1.000000\"",
        allocAmountExactToken: String = "\"0.234567\"",
        allocAllocatedExactToken: String = "\"0.234567\""
    ): String {
        val partyField = if (supplier) "supplier_id" else "customer_id"
        val partyId = if (supplier) supplierA.value else customerA.value
        val balanceField = if (supplier) "balance_owed" else "balance"
        val targetType = if (supplier) "purchase" else "invoice"
        val targetId = if (supplier) targetPurA.value else targetInvA.value
        return """
            {
              "id": "$id",
              "$partyField": "$partyId",
              "amount": "1.23",
              "amount_exact": $amountExactToken,
              "allocated_amount": "0.23",
              "allocated_amount_exact": $allocatedExactToken,
              "unallocated_amount": "1.00",
              "unallocated_amount_exact": $unallocatedExactToken,
              "is_advance": false,
              "posted_at": "2026-11-28",
              "currency": "USD",
              "$balanceField": "8.765433",
              "allocations": [
                {
                  "target_type": "$targetType",
                  "target_id": "$targetId",
                  "amount": "0.23",
                  "amount_exact": $allocAmountExactToken,
                  "allocated_amount": "0.23",
                  "allocated_amount_exact": $allocAllocatedExactToken
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun malformedNullEmptyAndWrongTypeExactFields_rejectPostAndRecovery_thenHealSameKeyOnce() = runBlocking {
        val malformedTokens = listOf(
            "null" to "null",
            "empty" to "\"\"",
            "blank" to "\"   \"",
            "json_number" to "1.234567",
            "json_boolean" to "true",
            "json_object" to "{}",
            "json_array" to "[]"
        )
        val targetFields = listOf(
            "snapshot_amount_exact",
            "snapshot_allocated_amount_exact",
            "snapshot_unallocated_amount_exact",
            "alloc_amount_exact",
            "alloc_allocated_amount_exact"
        )

        for (supplier in listOf(false, true)) {
            for (field in targetFields) {
                for ((label, token) in malformedTokens) {
                    val malformed = buildPaymentSnapshotWithExactRawTokens(
                        id = "01JCVPAYMALFORMED00000002",
                        supplier = supplier,
                        amountExactToken = if (field == "snapshot_amount_exact") token else "\"1.234567\"",
                        allocatedExactToken = if (field == "snapshot_allocated_amount_exact") token else "\"0.234567\"",
                        unallocatedExactToken = if (field == "snapshot_unallocated_amount_exact") token else "\"1.000000\"",
                        allocAmountExactToken = if (field == "alloc_amount_exact") token else "\"0.234567\"",
                        allocAllocatedExactToken = if (field == "alloc_allocated_amount_exact") token else "\"0.234567\""
                    )
                    val valid = buildPaymentSnapshotWithExactRawTokens(
                        id = "01JCVPAYMALFORMED00000002",
                        supplier = supplier
                    )
                    var healStatus = false
                    var posts = 0
                    var statusLookups = 0
                    val store = InMemoryMerchantCommandStore()
                    val repo = newRepo(object : ReadClientTransport {
                        override fun execute(request: TransportRequest): TransportResponse {
                            return when {
                                request.method == HttpMethod.POST -> {
                                    posts++
                                    TransportResponse(201, mapOf("Content-Type" to "application/json"), malformed)
                                }
                                request.method == HttpMethod.GET && request.path.endsWith("/receipts/status") -> {
                                    statusLookups++
                                    val key = request.queryParams["idempotency_key"].orEmpty()
                                    val snap = if (healStatus) valid else malformed
                                    TransportResponse(
                                        200,
                                        mapOf("Content-Type" to "application/json"),
                                        """{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$key","response_snapshot":$snap}"""
                                    )
                                }
                                else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                            }
                        }
                    }, commandStore = store)

                    if (supplier) {
                        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد أ", nameEn = "Sup A", balanceOwedExact = BigDecimal("10.00")))
                    } else {
                        seedCustomer(repo, Customer(id = customerA.value, nameAr = "زبون أ", nameEn = "Cust A", balanceExact = BigDecimal("10.00")))
                    }

                    val form = if (supplier) "supplier_payment" else "customer_payment"
                    val keyPrefix = if (supplier) "suppay" else "custpay"
                    val op = repo.getDraftOperationId(form)
                    val allocs = listOf(
                        PaymentAllocationInput(
                            if (supplier) "purchase" else "invoice",
                            if (supplier) targetPurA.value else targetInvA.value,
                            BigDecimal("0.23")
                        )
                    )

                    suspend fun submit(): Result<*> = if (supplier) {
                        repo.recordSupplierPayment(
                            supplierA.value, BigDecimal("1.23"), "Malformed $field=$label", ownerSession, op, cashAccountA.value, "2026-11-28", allocs
                        )
                    } else {
                        repo.recordCustomerPayment(
                            customerA.value, BigDecimal("1.23"), "Malformed $field=$label", op, cashAccountA.value, "2026-11-28", allocs
                        )
                    }

                    // 1. Direct POST with malformed exact field fails before COMPLETED/rotation and retains UNKNOWN_ACK + key
                    val postRes = submit()
                    assertTrue("$form/$field/$label POST must fail", postRes.isFailure)
                    assertEquals(1, posts)
                    assertEquals(CommandStatus.UNKNOWN_ACK, store.findCommand(companyA.value, actorA.value, deviceA.value, "$keyPrefix-$op")?.status)
                    assertEquals(op, repo.getDraftOperationId(form))
                    assertTrue(repo.isDraftRecovering(form))

                    // 2. FOUND recovery with malformed exact field still fails without a second POST or rotation
                    val foundBadRes = submit()
                    assertTrue("$form/$field/$label FOUND must fail", foundBadRes.isFailure)
                    assertEquals(1, posts)
                    assertEquals(1, statusLookups)
                    assertEquals(CommandStatus.UNKNOWN_ACK, store.findCommand(companyA.value, actorA.value, deviceA.value, "$keyPrefix-$op")?.status)
                    assertEquals(op, repo.getDraftOperationId(form))
                    assertTrue(repo.isDraftRecovering(form))
                    if (supplier) {
                        assertTrue(repo.getSupplierPayments().isEmpty())
                        assertEquals(BigDecimal("10.00"), repo.getSuppliers().single().balanceOwedExact)
                    } else {
                        assertTrue(repo.getCustomerTransactions().isEmpty())
                        assertEquals(BigDecimal("10.00"), repo.getCustomers().single().balanceExact)
                    }

                    // 3. Once /receipts/status returns a valid canonical exact payload, the preserved key heals with still 1 POST and rotates once
                    healStatus = true
                    val healedRes = submit()
                    assertTrue("$form/$field/$label healed recovery must succeed: ${healedRes.exceptionOrNull()?.message}", healedRes.isSuccess)
                    assertEquals(1, posts)
                    assertEquals(2, statusLookups)
                    assertEquals(CommandStatus.COMPLETED, store.findCommand(companyA.value, actorA.value, deviceA.value, "$keyPrefix-$op")?.status)
                    assertFalse(repo.isDraftRecovering(form))
                    assertNotEquals(op, repo.getDraftOperationId(form))
                    if (supplier) {
                        val pay = repo.getSupplierPayments().single()
                        assertEquals(BigDecimal("1.234567"), pay.amountExact)
                        assertEquals(BigDecimal("0.234567"), pay.allocatedAmountExact)
                        assertEquals(BigDecimal("1.000000"), pay.unallocatedAmountExact)
                        assertEquals(BigDecimal("0.234567"), pay.allocations.single().allocatedAmountExact)
                    } else {
                        val tx = repo.getCustomerTransactions().single()
                        assertEquals(BigDecimal("1.234567"), tx.creditExact)
                        assertEquals(BigDecimal("0.234567"), tx.allocatedAmountExact)
                        assertEquals(BigDecimal("1.000000"), tx.unallocatedAmountExact)
                        assertEquals(BigDecimal("0.234567"), tx.allocations.single().allocatedAmountExact)
                    }
                }
            }
        }
    }

    @Test
    fun cachedCompletedReplay_withMalformedPresentExactFields_rejectsWithZeroNetworkCalls() = runBlocking {
        val malformedVariants = listOf(
            buildPaymentSnapshotWithExactRawTokens("01JCVPAYCACHEBAD00000001", supplier = false, amountExactToken = "null"),
            buildPaymentSnapshotWithExactRawTokens("01JCVPAYCACHEBAD00000002", supplier = false, allocatedExactToken = "\"\""),
            buildPaymentSnapshotWithExactRawTokens("01JCVPAYCACHEBAD00000003", supplier = false, unallocatedExactToken = "1.000000"),
            buildPaymentSnapshotWithExactRawTokens("01JCVPAYCACHEBAD00000004", supplier = false, allocAllocatedExactToken = "\"invalid\"")
        )
        for (supplier in listOf(false, true)) {
            for ((idx, rawSnapshot) in malformedVariants.withIndex()) {
                val malformed = if (supplier) {
                    rawSnapshot
                        .replace("\"customer_id\": \"${customerA.value}\"", "\"supplier_id\": \"${supplierA.value}\"")
                        .replace("\"balance\": \"8.765433\"", "\"balance_owed\": \"8.765433\"")
                        .replace("\"target_type\": \"invoice\"", "\"target_type\": \"purchase\"")
                        .replace("\"target_id\": \"${targetInvA.value}\"", "\"target_id\": \"${targetPurA.value}\"")
                } else rawSnapshot

                val posts = AtomicInteger(0)
                val statusCalls = AtomicInteger(0)
                var failInitially = true
                val store = InMemoryMerchantCommandStore()
                val repo = newRepo(object : ReadClientTransport {
                    override fun execute(request: TransportRequest): TransportResponse {
                        return if (request.method == HttpMethod.POST) {
                            posts.incrementAndGet()
                            if (failInitially) TransportResponse(500, emptyMap(), """{"message":"timeout"}""")
                            else TransportResponse(500, emptyMap(), """{"message":"unexpected replay POST"}""")
                        } else {
                            statusCalls.incrementAndGet()
                            TransportResponse(503, emptyMap(), """{"message":"unavailable"}""")
                        }
                    }
                }, commandStore = store)

                if (supplier) {
                    seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد أ", nameEn = "Sup A", balanceOwedExact = BigDecimal("10.00")))
                } else {
                    seedCustomer(repo, Customer(id = customerA.value, nameAr = "زبون أ", nameEn = "Cust A", balanceExact = BigDecimal("10.00")))
                }

                val form = if (supplier) "supplier_payment" else "customer_payment"
                val op = repo.getDraftOperationId(form)
                val allocs = listOf(
                    PaymentAllocationInput(
                        if (supplier) "purchase" else "invoice",
                        if (supplier) targetPurA.value else targetInvA.value,
                        BigDecimal("0.23")
                    )
                )

                suspend fun invokePayment(): Result<*> = if (supplier) {
                    repo.recordSupplierPayment(
                        supplierA.value, BigDecimal("1.23"), "Cached replay $idx", ownerSession, op, cashAccountA.value, "2026-11-28", allocs
                    )
                } else {
                    repo.recordCustomerPayment(
                        customerA.value, BigDecimal("1.23"), "Cached replay $idx", op, cashAccountA.value, "2026-11-28", allocs
                    )
                }

                // Record real canonical command payload via repository admission (UNKNOWN_ACK), then simulate a pre-fix cached COMPLETED snapshot
                assertTrue(invokePayment().isFailure)
                assertTrue(store.markCompleted(companyA.value, op, malformed.toByteArray(StandardCharsets.UTF_8)))
                failInitially = false
                posts.set(0)
                statusCalls.set(0)

                val replayRes = invokePayment()
                assertTrue("Cached COMPLETED replay with malformed exact field must fail ($form #$idx)", replayRes.isFailure)
                assertTrue(
                    "Failure must come from canonical payment field validation, got: ${replayRes.exceptionOrNull()?.message}",
                    replayRes.exceptionOrNull()?.message?.contains("Invalid canonical payment field") == true
                )
                assertEquals("Cached replay validation failure must not issue POST", 0, posts.get())
                assertEquals("Cached replay validation failure must not issue receipt status probe", 0, statusCalls.get())
                if (supplier) {
                    assertTrue(repo.getSupplierPayments().isEmpty())
                    assertEquals(BigDecimal("10.00"), repo.getSuppliers().single().balanceOwedExact)
                } else {
                    assertTrue(repo.getCustomerTransactions().isEmpty())
                    assertEquals(BigDecimal("10.00"), repo.getCustomers().single().balanceExact)
                }
            }

            // Valid cached COMPLETED replay with exact fields continues to succeed with 0 network calls
            val validPosts = AtomicInteger(0)
            val validStatusCalls = AtomicInteger(0)
            val validSnapshot = buildPaymentSnapshotWithExactRawTokens("01JCVPAYCACHEOK000000001", supplier = supplier)
            val validStore = InMemoryMerchantCommandStore()
            val validRepo = newRepo(object : ReadClientTransport {
                override fun execute(request: TransportRequest): TransportResponse {
                    return if (request.method == HttpMethod.POST) {
                        validPosts.incrementAndGet()
                        TransportResponse(201, mapOf("Content-Type" to "application/json"), validSnapshot)
                    } else {
                        validStatusCalls.incrementAndGet()
                        TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                    }
                }
            }, commandStore = validStore)
            val validForm = if (supplier) "supplier_payment" else "customer_payment"
            val validOp = validRepo.getDraftOperationId(validForm)
            val validAllocs = listOf(
                PaymentAllocationInput(
                    if (supplier) "purchase" else "invoice",
                    if (supplier) targetPurA.value else targetInvA.value,
                    BigDecimal("0.23")
                )
            )
            suspend fun invokeValid(): Result<*> = if (supplier) {
                validRepo.recordSupplierPayment(
                    supplierA.value, BigDecimal("1.23"), "Valid cached replay", ownerSession, validOp, cashAccountA.value, "2026-11-28", validAllocs
                )
            } else {
                validRepo.recordCustomerPayment(
                    customerA.value, BigDecimal("1.23"), "Valid cached replay", validOp, cashAccountA.value, "2026-11-28", validAllocs
                )
            }
            assertTrue(invokeValid().isSuccess)
            assertEquals(1, validPosts.get())
            assertTrue(invokeValid().isSuccess)
            assertEquals("Valid cached COMPLETED replay must execute 0 extra POSTs", 1, validPosts.get())
            assertEquals(0, validStatusCalls.get())
        }
    }

    @Test
    fun supplierPayment_omittedExactCompatFallback_acceptsDisplayFieldsBothDirectAndRecovery() = runBlocking {
        val compatPayload = """{"id":"01JCVSUPCOMPAT001","supplier_id":"${supplierA.value}","amount":"1.23","allocated_amount":"0.23","unallocated_amount":"1.00","balance_owed":"8.77","is_advance":false,"allocations":[{"target_type":"purchase","target_id":"${targetPurA.value}","allocated_amount":"0.23"}]}"""
        var directPosts = 0
        val directRepo = newRepo(object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return if (request.method == HttpMethod.POST) {
                    directPosts++
                    TransportResponse(201, mapOf("Content-Type" to "application/json"), compatPayload)
                } else {
                    TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        })
        val allocs = listOf(PaymentAllocationInput("purchase", targetPurA.value, BigDecimal("0.23")))
        val directPay = directRepo.recordSupplierPayment(
            supplierA.value, BigDecimal("1.23"), "sup compat direct", ownerSession, directRepo.getDraftOperationId("supplier_payment"), cashAccountA.value, "2026-11-28", allocs
        ).getOrThrow()
        assertEquals(1, directPosts)
        assertEquals(BigDecimal("1.23"), directPay.amountExact)
        assertEquals(BigDecimal("0.23"), directPay.allocatedAmountExact)
        assertEquals(BigDecimal("1.00"), directPay.unallocatedAmountExact)
        assertEquals(BigDecimal("0.23"), directPay.allocations.single().allocatedAmountExact)

        var recPosts = 0
        var recStatusCalls = 0
        val recRepo = newRepo(object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return if (request.method == HttpMethod.POST) {
                    recPosts++
                    TransportResponse(500, emptyMap(), """{"message":"timeout"}""")
                } else {
                    recStatusCalls++
                    if (recStatusCalls == 1) {
                        TransportResponse(503, emptyMap(), """{"message":"unavailable"}""")
                    } else {
                        val key = request.queryParams["idempotency_key"].orEmpty()
                        TransportResponse(200, mapOf("Content-Type" to "application/json"), """{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$key","response_snapshot":$compatPayload}""")
                    }
                }
            }
        })
        val op = recRepo.getDraftOperationId("supplier_payment")
        assertTrue(recRepo.recordSupplierPayment(supplierA.value, BigDecimal("1.23"), "sup compat rec", ownerSession, op, cashAccountA.value, "2026-11-28", allocs).isFailure)
        val recPay = recRepo.recordSupplierPayment(supplierA.value, BigDecimal("1.23"), "sup compat rec", ownerSession, op, cashAccountA.value, "2026-11-28", allocs).getOrThrow()
        assertEquals(1, recPosts)
        assertEquals(2, recStatusCalls)
        assertEquals(BigDecimal("1.23"), recPay.amountExact)
        assertEquals(BigDecimal("0.23"), recPay.allocatedAmountExact)
        assertEquals(BigDecimal("1.00"), recPay.unallocatedAmountExact)
        assertEquals(BigDecimal("0.23"), recPay.allocations.single().allocatedAmountExact)
    }

    @Test
    fun customerAndSupplierPayment_preferExactFieldsUnscaled_overDisplayFields_bothDirectAndDurableReplay_withCompatFallback() = runBlocking {
        val customerSnapshot = exactPaymentSnapshotJson(
            id = "01JCVPAYCUSTEXACT00000001",
            partyField = "customer_id",
            partyId = customerA.value,
            balanceField = "balance",
            targetType = "invoice",
            targetId = targetInvA.value
        )
        val supplierSnapshot = exactPaymentSnapshotJson(
            id = "01JCVPAYSUPEXACT000000001",
            partyField = "supplier_id",
            partyId = supplierA.value,
            balanceField = "balance_owed",
            targetType = "purchase",
            targetId = targetPurA.value
        )

        // 1A. Direct POST responses with display 1.23 + exact 1.234567 (even with currencyMinorUnits = 2)
        val directTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") ->
                        TransportResponse(201, mapOf("Content-Type" to "application/json"), customerSnapshot)
                    request.method == HttpMethod.POST && request.path.endsWith("/suppliers/${supplierA.value}/payments") ->
                        TransportResponse(201, mapOf("Content-Type" to "application/json"), supplierSnapshot)
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val directRepo = newRepo(directTransport, baseCurrency = "ILS", minorUnits = 2)
        seedCustomer(directRepo, Customer(id = customerA.value, nameAr = "زبون أ", nameEn = "Cust A", balanceExact = BigDecimal("10.00")))
        seedSupplier(directRepo, Supplier(id = supplierA.value, nameAr = "مورد أ", nameEn = "Sup A", balanceOwedExact = BigDecimal("10.00")))

        val custAllocInput = listOf(PaymentAllocationInput("invoice", targetInvA.value, BigDecimal("0.23")))
        val custRes = directRepo.recordCustomerPayment(
            customerId = customerA.value,
            amount = BigDecimal("1.23"),
            note = "exact witness",
            paymentId = "op-cust-direct-exact",
            cashAccountId = cashAccountA.value,
            paymentDate = "2026-11-28",
            allocations = custAllocInput
        )
        assertTrue(custRes.isSuccess)
        val custTx = custRes.getOrThrow()
        assertEquals(BigDecimal("1.234567"), custTx.creditExact)
        assertEquals(BigDecimal("0.234567"), custTx.allocatedAmountExact)
        assertEquals(BigDecimal("1.000000"), custTx.unallocatedAmountExact)
        assertEquals(1, custTx.allocations.size)
        assertEquals(BigDecimal("0.234567"), custTx.allocations[0].amountExact)
        assertEquals(BigDecimal("0.234567"), custTx.allocations[0].allocatedAmountExact)

        val supAllocInput = listOf(PaymentAllocationInput("purchase", targetPurA.value, BigDecimal("0.23")))
        val supRes = directRepo.recordSupplierPayment(
            supplierId = supplierA.value,
            amount = BigDecimal("1.23"),
            note = "exact witness",
            operator = ownerSession,
            paymentId = "op-sup-direct-exact",
            cashAccountId = cashAccountA.value,
            paymentDate = "2026-11-28",
            allocations = supAllocInput
        )
        assertTrue(supRes.isSuccess)
        val supPay = supRes.getOrThrow()
        assertEquals(BigDecimal("1.234567"), supPay.amountExact)
        assertEquals(BigDecimal("0.234567"), supPay.allocatedAmountExact)
        assertEquals(BigDecimal("1.000000"), supPay.unallocatedAmountExact)
        assertEquals(1, supPay.allocations.size)
        assertEquals(BigDecimal("0.234567"), supPay.allocations[0].amountExact)
        assertEquals(BigDecimal("0.234567"), supPay.allocations[0].allocatedAmountExact)

        // 1B. Durable replay via GET /receipts/status after UNKNOWN_ACK on initial POST
        val custPostCalls = AtomicInteger(0)
        val custStatusCalls = AtomicInteger(0)
        val replayTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") -> {
                        custPostCalls.incrementAndGet()
                        TransportResponse(500, emptyMap(), """{"message":"timeout"}""")
                    }
                    request.method == HttpMethod.GET && request.path.endsWith("/receipts/status") -> {
                        if (custStatusCalls.incrementAndGet() == 1) {
                            TransportResponse(503, emptyMap(), """{"message":"gateway unavailable"}""")
                        } else {
                            val key = request.queryParams["idempotency_key"].orEmpty()
                            TransportResponse(
                                200,
                                mapOf("Content-Type" to "application/json"),
                                """{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$key","response_snapshot":$customerSnapshot}"""
                            )
                        }
                    }
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val replayRepo = newRepo(replayTransport, baseCurrency = "ILS", minorUnits = 2)
        seedCustomer(replayRepo, Customer(id = customerA.value, nameAr = "زبون أ", nameEn = "Cust A", balanceExact = BigDecimal("10.00")))

        assertTrue(
            replayRepo.recordCustomerPayment(
                customerA.value, BigDecimal("1.23"), "replay", "op-cust-replay-1", cashAccountA.value, "2026-11-28", custAllocInput
            ).isFailure
        )
        val custReplayed = replayRepo.recordCustomerPayment(
            customerA.value, BigDecimal("1.23"), "replay", "op-cust-replay-1", cashAccountA.value, "2026-11-28", custAllocInput
        )
        assertTrue(custReplayed.isSuccess)
        assertEquals(1, custPostCalls.get())
        val replayedCustTx = custReplayed.getOrThrow()
        assertEquals(BigDecimal("1.234567"), replayedCustTx.creditExact)
        assertEquals(BigDecimal("0.234567"), replayedCustTx.allocatedAmountExact)
        assertEquals(BigDecimal("1.000000"), replayedCustTx.unallocatedAmountExact)
        assertEquals(BigDecimal("0.234567"), replayedCustTx.allocations.single().allocatedAmountExact)

        val supPostCalls = AtomicInteger(0)
        val supStatusCalls = AtomicInteger(0)
        val supReplayTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/suppliers/${supplierA.value}/payments") -> {
                        supPostCalls.incrementAndGet()
                        TransportResponse(500, emptyMap(), """{"message":"timeout"}""")
                    }
                    request.method == HttpMethod.GET && request.path.endsWith("/receipts/status") -> {
                        if (supStatusCalls.incrementAndGet() == 1) {
                            TransportResponse(503, emptyMap(), """{"message":"gateway unavailable"}""")
                        } else {
                            val key = request.queryParams["idempotency_key"].orEmpty()
                            TransportResponse(
                                200,
                                mapOf("Content-Type" to "application/json"),
                                """{"status":"FOUND","company_id":"${companyA.value}","idempotency_key":"$key","response_snapshot":$supplierSnapshot}"""
                            )
                        }
                    }
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val supReplayRepo = newRepo(supReplayTransport, baseCurrency = "ILS", minorUnits = 2)
        seedSupplier(supReplayRepo, Supplier(id = supplierA.value, nameAr = "مورد أ", nameEn = "Sup A", balanceOwedExact = BigDecimal("10.00")))
        assertTrue(
            supReplayRepo.recordSupplierPayment(
                supplierA.value, BigDecimal("1.23"), "replay", ownerSession, "op-sup-replay-1", cashAccountA.value, "2026-11-28", supAllocInput
            ).isFailure
        )
        val supReplayed = supReplayRepo.recordSupplierPayment(
            supplierA.value, BigDecimal("1.23"), "replay", ownerSession, "op-sup-replay-1", cashAccountA.value, "2026-11-28", supAllocInput
        )
        assertTrue(supReplayed.isSuccess)
        assertEquals(1, supPostCalls.get())
        val replayedSup = supReplayed.getOrThrow()
        assertEquals(BigDecimal("1.234567"), replayedSup.amountExact)
        assertEquals(BigDecimal("0.234567"), replayedSup.allocatedAmountExact)
        assertEquals(BigDecimal("1.000000"), replayedSup.unallocatedAmountExact)
        assertEquals(BigDecimal("0.234567"), replayedSup.allocations.single().allocatedAmountExact)

        // 1C. Display-only compat fallback when *_exact fields are omitted
        val compatTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") ->
                        TransportResponse(
                            201,
                            mapOf("Content-Type" to "application/json"),
                            """{"id":"01JCVPAYCOMPAT001","amount":"1.23","allocated_amount":"0.23","unallocated_amount":"1.00","balance":"8.77","allocations":[{"target_type":"invoice","target_id":"${targetInvA.value}","allocated_amount":"0.23"}]}"""
                        )
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val compatRepo = newRepo(compatTransport, baseCurrency = "ILS", minorUnits = 2)
        seedCustomer(compatRepo, Customer(id = customerA.value, nameAr = "زبون", nameEn = "Cust", balanceExact = BigDecimal("10.00")))
        val compatTx = compatRepo.recordCustomerPayment(
            customerA.value, BigDecimal("1.23"), "compat", "op-compat", cashAccountA.value, "2026-11-28", custAllocInput
        ).getOrThrow()
        assertEquals(BigDecimal("1.23"), compatTx.creditExact)
        assertEquals(BigDecimal("0.23"), compatTx.allocatedAmountExact)
        assertEquals(BigDecimal("1.00"), compatTx.unallocatedAmountExact)
        assertEquals(BigDecimal("0.23"), compatTx.allocations.single().allocatedAmountExact)
    }

    @Test
    fun customerRefresh_balanceNullOnLatestRevision_publishesNullNeverCachedMinusPayment_andPreservesOutOfOrderGuard() = runBlocking {
        // Case A: cached 100.00, pay 20.00, POST omits balance and follow-up GET returns 200 {"balance": null} -> MUST publish null (NEVER 80.00!)
        val nullRefreshTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") ->
                        TransportResponse(
                            201,
                            mapOf("Content-Type" to "application/json"),
                            """{"id":"01JCVCUSTNULL01","amount":"20.00","balance":null,"posted_at":"2026-11-28"}"""
                        )
                    request.method == HttpMethod.GET && request.path.endsWith("/customers/${customerA.value}") ->
                        TransportResponse(
                            200,
                            mapOf("Content-Type" to "application/json"),
                            """{"id":"${customerA.value}","name_ar":"زبون","name_en":"Customer","balance":null,"credit_limit":"500.00"}"""
                        )
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val repoNullAfterPay = newRepo(nullRefreshTransport)
        seedCustomer(repoNullAfterPay, Customer(id = customerA.value, nameAr = "زبون", nameEn = "Customer", balanceExact = BigDecimal("100.00")))
        val tx = repoNullAfterPay.recordCustomerPayment(
            customerA.value, BigDecimal("20.00"), "pay 20", "op-null-after-pay", cashAccountA.value, "2026-11-28"
        ).getOrThrow()
        assertNull("Successful customer refresh returning balance:null must publish null runningBalanceExact, never 100-20=80", tx.runningBalanceExact)
        assertNull(
            "Cached 100 - 20 = 80 must NEVER be published when server refresh returned balance:null",
            repoNullAfterPay.getCustomers().first { it.id == customerA.value }.balanceExact
        )

        // Case B: getCustomerDetail directly returns 200 {"balance": null} on LATEST revision -> clears cached 100.00 to null
        seedCustomer(repoNullAfterPay, Customer(id = customerA.value, nameAr = "زبون", nameEn = "Customer", balanceExact = BigDecimal("100.00")))
        val detail = repoNullAfterPay.getCustomerDetail(customerA.value).getOrThrow()
        assertNull("getCustomerDetail returning balance:null must publish null", detail.balanceExact)
        assertNull("Flow cache must be null after getCustomerDetail balance:null", repoNullAfterPay.getCustomers().first { it.id == customerA.value }.balanceExact)

        // Case C: follow-up GET failure (500) clears cached 100.00 to null
        val failRefreshTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") ->
                        TransportResponse(201, emptyMap(), """{"id":"01JCVCUSTFAIL01","amount":"20.00","posted_at":"2026-11-28"}""")
                    request.method == HttpMethod.GET && request.path.endsWith("/customers/${customerA.value}") ->
                        TransportResponse(500, emptyMap(), """{"message":"Server error"}""")
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val repoFail = newRepo(failRefreshTransport)
        seedCustomer(repoFail, Customer(id = customerA.value, nameAr = "زبون", nameEn = "Customer", balanceExact = BigDecimal("100.00")))
        val failTx = repoFail.recordCustomerPayment(
            customerA.value, BigDecimal("20.00"), "pay fail refresh", "op-cust-fail-refresh", cashAccountA.value, "2026-11-28"
        ).getOrThrow()
        assertNull(failTx.runningBalanceExact)
        assertNull(repoFail.getCustomers().first { it.id == customerA.value }.balanceExact)

        // Case D: Out-of-order guard: slow getCustomerDetail returning balance:null after newer payment published 70.00 MUST NOT clear 70.00
        val getStarted = CountDownLatch(1)
        val payCommitted = CountDownLatch(1)
        val oooTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.GET && request.path.endsWith("/customers/${customerA.value}") -> {
                        getStarted.countDown()
                        payCommitted.await(5, TimeUnit.SECONDS)
                        TransportResponse(200, mapOf("Content-Type" to "application/json"), """{"id":"${customerA.value}","name_ar":"زبون","balance":null}""")
                    }
                    request.method == HttpMethod.POST && request.path.endsWith("/customers/${customerA.value}/payments") -> {
                        getStarted.await(5, TimeUnit.SECONDS)
                        TransportResponse(201, mapOf("Content-Type" to "application/json"), """{"id":"01JCVOOOCUST01","amount":"30.00","balance":"70.00","posted_at":"2026-11-28"}""")
                    }
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val oooRepo = newRepo(oooTransport)
        seedCustomer(oooRepo, Customer(id = customerA.value, nameAr = "زبون", nameEn = "Customer", balanceExact = BigDecimal("100.00")))
        val getJob = GlobalScope.launch(Dispatchers.IO) { oooRepo.getCustomerDetail(customerA.value) }
        assertTrue(getStarted.await(5, TimeUnit.SECONDS))
        val payResult = oooRepo.recordCustomerPayment(
            customerA.value, BigDecimal("30.00"), "newer pay", "op-ooo-pay", cashAccountA.value, "2026-11-28"
        )
        assertTrue(payResult.isSuccess)
        payCommitted.countDown()
        getJob.join()
        assertEquals(
            "Out-of-order stale GET with balance:null must NOT overwrite newer committed balance 70.00",
            BigDecimal("70.00"),
            oooRepo.getCustomers().first { it.id == customerA.value }.balanceExact
        )
    }

    @Test
    fun supplierRefresh_bothNullAndPartialNullClearCounterpart_failureAndOutOfOrderRespected() = runBlocking {
        // Test via getSupplierDetail AND recordSupplierPayment for:
        // 1. Both null -> publishes both null
        // 2. Partial known owed + unknown advances -> clears advancesExact to null
        // 3. Partial unknown owed + known advances -> clears balanceOwedExact to null
        // 4. Refresh failure -> clears both to null
        // 5. Out-of-order delayed GET returning (null, null) after newer payment committed (400.00, 10.00) -> keeps (400.00, 10.00)

        var nextOwedJson = "null"
        var nextAdvJson = "null"
        var getStatus = 200
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.endsWith("/suppliers/${supplierA.value}/payments") ->
                        TransportResponse(
                            201,
                            mapOf("Content-Type" to "application/json"),
                            """{"id":"01JCVSUPREFRESH${System.nanoTime()}","amount":"25.00","posted_at":"2026-11-28"}"""
                        )
                    request.method == HttpMethod.GET && request.path.endsWith("/suppliers/${supplierA.value}") ->
                        if (getStatus != 200) {
                            TransportResponse(getStatus, emptyMap(), """{"message":"Server error"}""")
                        } else {
                            TransportResponse(
                                200,
                                mapOf("Content-Type" to "application/json"),
                                """{"id":"${supplierA.value}","name_ar":"مورد","balance_owed":$nextOwedJson,"owed":$nextOwedJson,"advances":$nextAdvJson}"""
                            )
                        }
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val repo = newRepo(transport)

        // 1. Both null on getSupplierDetail
        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("200.00"), advancesExact = BigDecimal("50.00")))
        nextOwedJson = "null"
        nextAdvJson = "null"
        val detBothNull = repo.getSupplierDetail(supplierA.value).getOrThrow()
        assertNull(detBothNull.balanceOwedExact)
        assertNull(detBothNull.advancesExact)

        // 2. Partial known owed + null advances -> must CLEAR cached advancesExact
        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("200.00"), advancesExact = BigDecimal("50.00")))
        nextOwedJson = """"120.00""""
        nextAdvJson = "null"
        val detPartialOwed = repo.getSupplierDetail(supplierA.value).getOrThrow()
        assertEquals(BigDecimal("120.00"), detPartialOwed.balanceOwedExact)
        assertNull("Unknown advances must clear cached 50.00", detPartialOwed.advancesExact)

        // 3. Partial null owed + known advances -> must CLEAR cached balanceOwedExact
        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("200.00"), advancesExact = BigDecimal("50.00")))
        nextOwedJson = "null"
        nextAdvJson = """"75.00""""
        val detPartialAdv = repo.getSupplierDetail(supplierA.value).getOrThrow()
        assertNull("Unknown owed must clear cached 200.00", detPartialAdv.balanceOwedExact)
        assertEquals(BigDecimal("75.00"), detPartialAdv.advancesExact)

        // 4. recordSupplierPayment follow-up: both null
        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("200.00"), advancesExact = BigDecimal("50.00")))
        nextOwedJson = "null"
        nextAdvJson = "null"
        repo.recordSupplierPayment(
            supplierA.value, BigDecimal("25.00"), "both null", ownerSession, "op-sup-both-null", cashAccountA.value, "2026-11-28"
        ).getOrThrow()
        val supAfterBothNull = repo.getSuppliers().first { it.id == supplierA.value }
        assertNull(supAfterBothNull.balanceOwedExact)
        assertNull(supAfterBothNull.advancesExact)

        // 5. recordSupplierPayment follow-up: partial known owed + null advances -> clears advancesExact
        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("200.00"), advancesExact = BigDecimal("50.00")))
        nextOwedJson = """"175.00""""
        nextAdvJson = "null"
        repo.recordSupplierPayment(
            supplierA.value, BigDecimal("25.00"), "partial", ownerSession, "op-sup-partial-null", cashAccountA.value, "2026-11-28"
        ).getOrThrow()
        val supAfterPartial = repo.getSuppliers().first { it.id == supplierA.value }
        assertEquals(BigDecimal("175.00"), supAfterPartial.balanceOwedExact)
        assertNull(supAfterPartial.advancesExact)

        // 6. recordSupplierPayment follow-up: failure (500) -> clears both to null
        seedSupplier(repo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("200.00"), advancesExact = BigDecimal("50.00")))
        getStatus = 500
        repo.recordSupplierPayment(
            supplierA.value, BigDecimal("25.00"), "fail refresh", ownerSession, "op-sup-fail-refresh", cashAccountA.value, "2026-11-28"
        ).getOrThrow()
        val supAfterFail = repo.getSuppliers().first { it.id == supplierA.value }
        assertNull(supAfterFail.balanceOwedExact)
        assertNull(supAfterFail.advancesExact)

        // 7. Out-of-order delayed getSupplierDetail must not overwrite newer committed payment state
        val getStarted = CountDownLatch(1)
        val payCommitted = CountDownLatch(1)
        val oooTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.GET && request.path.endsWith("/suppliers/${supplierA.value}") -> {
                        getStarted.countDown()
                        payCommitted.await(5, TimeUnit.SECONDS)
                        TransportResponse(200, mapOf("Content-Type" to "application/json"), """{"id":"${supplierA.value}","name_ar":"مورد","balance_owed":null,"advances":null}""")
                    }
                    request.method == HttpMethod.POST && request.path.endsWith("/suppliers/${supplierA.value}/payments") -> {
                        getStarted.await(5, TimeUnit.SECONDS)
                        TransportResponse(201, mapOf("Content-Type" to "application/json"), """{"id":"01JCVOOOSUP01","amount":"100.00","balance_owed":"400.00","advances":"10.00","posted_at":"2026-11-28"}""")
                    }
                    else -> TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
                }
            }
        }
        val oooRepo = newRepo(oooTransport)
        seedSupplier(oooRepo, Supplier(id = supplierA.value, nameAr = "مورد", nameEn = "Sup", balanceOwedExact = BigDecimal("500.00"), advancesExact = BigDecimal("0.00")))
        val getJob = GlobalScope.launch(Dispatchers.IO) { oooRepo.getSupplierDetail(supplierA.value) }
        assertTrue(getStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            oooRepo.recordSupplierPayment(
                supplierA.value, BigDecimal("100.00"), "newer sup pay", ownerSession, "op-ooo-sup", cashAccountA.value, "2026-11-28"
            ).isSuccess
        )
        payCommitted.countDown()
        getJob.join()
        val finalSup = oooRepo.getSuppliers().first { it.id == supplierA.value }
        assertEquals(BigDecimal("400.00"), finalSup.balanceOwedExact)
        assertEquals(BigDecimal("10.00"), finalSup.advancesExact)
    }

    @Test
    fun formatCustomerStatementText_sharesAllCurrenciesAllEntriesOverTen_2dpDisplay_andHonestFallbackWhenMismatched() = runBlocking {
        val repo = newRepo(object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse =
                TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Not found"}}""")
        }, baseCurrency = "JOD", minorUnits = 3)
        val vm = TraderViewModel(repo)
        val stateField = TraderViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.viewmodel.TraderUiState>

        val jodEntries = (1..12).map { i ->
            CustomerStatementEntry(
                date = "2026-11-${String.format(java.util.Locale.US, "%02d", i)}",
                type = "sale",
                number = "INV-JOD-${1000 + i}",
                reference = null,
                description = "Item $i",
                debit = BigDecimal("10.000"),
                credit = BigDecimal.ZERO,
                balance = BigDecimal("${10 * i}.000")
            )
        }
        val usdEntries = (1..3).map { i ->
            CustomerStatementEntry(
                date = "2026-11-${String.format(java.util.Locale.US, "%02d", 12 + i)}",
                type = "sale",
                number = "INV-USD-${2000 + i}",
                reference = null,
                description = "USD Entry $i",
                debit = if (i == 1) BigDecimal("0.001") else BigDecimal("50.00"),
                credit = BigDecimal.ZERO,
                balance = BigDecimal("${50 * i}.001")
            )
        }
        val multiCurrencyStatement = LiveCustomerStatement(
            customerId = customerA.value,
            customerName = "Multi Currency Customer",
            fromDate = "2026-11-01",
            toDate = "2026-11-28",
            currencies = mapOf(
                "JOD" to CustomerStatementCurrencySummary(
                    currency = "JOD",
                    openingBalance = BigDecimal("0.000"),
                    closingBalance = BigDecimal("1234.567"),
                    totalDebits = BigDecimal("1234.567"),
                    totalCredits = BigDecimal.ZERO,
                    entries = jodEntries,
                    aging = CustomerStatementAging(BigDecimal("1234.567"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("1234.567"))
                ),
                "USD" to CustomerStatementCurrencySummary(
                    currency = "USD",
                    openingBalance = BigDecimal.ZERO,
                    closingBalance = BigDecimal("0.001"),
                    totalDebits = BigDecimal("100.001"),
                    totalCredits = BigDecimal("100.000"),
                    entries = usdEntries,
                    aging = CustomerStatementAging(BigDecimal("0.001"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("0.001"))
                )
            )
        )

        val custA = Customer(id = customerA.value, nameAr = "زبون متعدد العملات", nameEn = "Multi Currency", phone = "0790000111", balanceExact = null, creditLimitExact = BigDecimal("500.00"))
        val custB = Customer(id = customerB.value, nameAr = "زبون آخر", nameEn = "Other Cust", phone = "0790000222", balanceExact = null, creditLimitExact = null)

        stateFlow.value = stateFlow.value.copy(
            language = AppLanguage.EN,
            currentSession = ownerSession,
            selectedCustomerStatement = multiCurrencyStatement
        )

        val sharedForA = vm.formatCustomerStatementText(custA)
        // Both currencies present with 2dp human formatting + sub-cent < 0.01 USD (no raw 1234.567 / 0.001 / ₪)
        assertTrue("Expected JOD section with 2dp 1,234.57 JOD:\n$sharedForA", sharedForA.contains("1,234.57 JOD"))
        assertTrue("Expected USD section with < 0.01 USD:\n$sharedForA", sharedForA.contains("< 0.01 USD"))
        assertFalse("Must NOT leak raw >2dp 1234.567 in human statement:\n$sharedForA", sharedForA.contains("1234.567"))
        assertFalse("Must NOT hardcode ILS symbol ₪:\n$sharedForA", sharedForA.contains("₪"))
        // All 12 JOD rows AND all 3 USD rows present (not capped at 10!)
        assertTrue("Expected first JOD entry #1001:\n$sharedForA", sharedForA.contains("INV-JOD-1001"))
        assertTrue("Expected entry #11 (#1011) past 10-row limit:\n$sharedForA", sharedForA.contains("INV-JOD-1011"))
        assertTrue("Expected entry #12 (#1012) past 10-row limit:\n$sharedForA", sharedForA.contains("INV-JOD-1012"))
        assertTrue("Expected all USD entries including INV-USD-2003:\n$sharedForA", sharedForA.contains("INV-USD-2003"))

        // Mismatched statement (selectedCustomerStatement belongs to customerA while formatting customerB)
        val sharedForB = vm.formatCustomerStatementText(custB)
        assertFalse("Mismatched statement for A must NOT leak into B's shared text:\n$sharedForB", sharedForB.contains("INV-JOD-1001"))
        assertFalse("Mismatched statement balances for A must NOT leak into B:\n$sharedForB", sharedForB.contains("1,234.57 JOD"))
        assertTrue("Expected honest unavailable / summary fallback note for B:\n$sharedForB", sharedForB.contains("Unavailable") && sharedForB.contains("Full live statement is not loaded"))
    }
}
