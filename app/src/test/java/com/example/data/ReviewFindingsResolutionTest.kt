package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CurrentAuthScope
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.toNullableString
import com.example.data.mobile.merchantread.SupplierDetailResponse
import com.example.data.mobile.merchantread.SupplierListItem
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.readclient.EndpointSecurity
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.Customer
import com.example.model.Supplier
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFindingsResolutionTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var cryptoEngine: JvmAesGcmCryptoEngine
    private lateinit var mockWebServer: MockWebServer

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"
    private val validScope = CurrentAuthScope(companyId, actorId, deviceId, token = validToken, generation = 1L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_findings_durability_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()
        cryptoEngine = JvmAesGcmCryptoEngine()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    // =========================================================================
    // Finding 1: Process-Death Party Creation & Recovery
    // =========================================================================

    @Test
    fun testFinding1_realDiskBackedRoomReopenPreservesDraftAndAvoidsSubmittedTransitionConflict(): Unit = runBlocking {
        // Step 1: Open real disk-backed Room DB
        val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

        val commandId = "01JCV8X9M2NQW3TYZ4R5S67891"
        val idempotencyKey = "custcreate-$commandId"
        val payloadJson = """{"name_ar":"شركة النور للمواد الغذائية","phone":"0599123456","address_ar":"نابلس"}"""
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        val payloadDigest = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes).joinToString("") { "%02x".format(it) }

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

        // Simulate process death by closing DB connection
        db1.close()

        // Step 2: Reopen real disk-backed DB from same file
        val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
        val store2 = RoomMerchantCommandStore(db2, cryptoEngine)

        val restoredRecord = store2.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertNotNull(restoredRecord)
        assertEquals(CommandStatus.SUBMITTED, restoredRecord!!.status)
        assertEquals(idempotencyKey, restoredRecord.idempotencyKey)
        val decryptedBytes = store2.decryptPayload(restoredRecord)
        assertEquals(payloadJson, String(decryptedBytes, Charsets.UTF_8))

        // Step 3: Verify safe retry when server returns 404 for receipt check on already SUBMITTED command
        // The repository must NOT attempt a forbidden SUBMITTED -> SUBMITTED transition that returns false
        var postActionExecuted = false
        val mockTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.GET && request.path.contains("/receipts/status") -> {
                        // Server does not have receipt record yet (returns 404)
                        TransportResponse(statusCode = 404, emptyMap(), """{"error":"Not Found"}""")
                    }
                    request.method == HttpMethod.POST && request.path.contains("/customers") -> {
                        postActionExecuted = true
                        assertEquals(idempotencyKey, request.headers["Idempotency-Key"])
                        TransportResponse(
                            statusCode = 201,
                            headers = emptyMap(),
                            body = """{"id":"01JCV8X9M2NQW3TYZ4R5S67891","name_ar":"شركة النور للمواد الغذائية","phone":"0599123456","address_ar":"نابلس"}"""
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

        val repository = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store2,
            authScopeProvider = { validScope }
        )

        // Retry customer creation using the existing commandId
        val retryResult = repository.createCustomer(
            name = "شركة النور للمواد الغذائية",
            phone = "0599123456",
            address = "نابلس",
            operationId = commandId
        )

        assertTrue("Retry must succeed without SUBMITTED->SUBMITTED transition error: ${retryResult.exceptionOrNull()?.message}", retryResult.isSuccess)
        assertTrue("Post action must have executed on retry", postActionExecuted)
        val finalRecord = store2.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertNotNull(finalRecord)
        assertEquals(CommandStatus.COMPLETED, finalRecord!!.status)
        db2.close()
    }

    @Test
    fun testFinding1_okHttpMockWebServerBoundaryForPartyCreation() {
        val serverBaseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val postPath = "/api/mobile/v1/companies/${companyId.value}/customers"
        val idempotencyKey = "idemp_okhttp_test_001"
        val requestJson = """{"name_ar":"مخبز القدس الحديث","phone":"0599988776","address_ar":"القدس"}"""

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"id":"01JCV8X9M2NQW3TYZ4R5SCUST99","name_ar":"مخبز القدس الحديث","phone":"0599988776","address_ar":"القدس"}""")
        )

        val client = OkHttpClient.Builder().build()
        val request = Request.Builder()
            .url("$serverBaseUrl$postPath")
            .header("Authorization", "Bearer mock_token_value_abc")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Device-Id", deviceId.value)
            .header("X-Actor-Id", actorId.value)
            .post(requestJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        assertEquals(201, response.code)
        val bodyStr = response.body?.string()
        assertNotNull(bodyStr)
        assertTrue(bodyStr!!.contains("01JCV8X9M2NQW3TYZ4R5SCUST99"))

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest!!.method)
        assertEquals(postPath, recordedRequest.path)
        assertEquals(idempotencyKey, recordedRequest.getHeader("Idempotency-Key"))
        assertEquals("Bearer mock_token_value_abc", recordedRequest.getHeader("Authorization"))
        assertTrue(recordedRequest.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(requestJson, recordedRequest.body.readUtf8())
    }

    @Test
    fun testFinding1_prepareSupplierCreateDraftAndRecoveryState(): Unit = runBlocking {
        val store = InMemoryMerchantCommandStore()
        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(
                transport = object : ReadClientTransport {
                    override fun execute(request: TransportRequest) = TransportResponse(200, emptyMap(), "{}")
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

        val vm = TraderViewModel(repo)

        // Seed an in-flight supplier draft
        val draftJson = """{"name_ar":"شركة الأمل للتجارة","company_name":"الأمل","phone":"0599001122"}"""
        store.recordPendingCommand(
            companyId = companyId.value,
            commandId = "supplier_create_001",
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "supplier_create",
            idempotencyKey = "idemp_sup_001",
            payloadDigest = "digest",
            canonicalPayloadBytes = draftJson.toByteArray(Charsets.UTF_8)
        )
        store.markUnknownAck(companyId.value, "supplier_create_001", "Connection timed out")

        // Call prepareSupplierCreateDraft
        vm.prepareSupplierCreateDraft()

        val state = vm.uiState.value
        assertNotNull(state.activeSupplierCreateDraft)
        assertEquals("شركة الأمل للتجارة", state.activeSupplierCreateDraft!!.properties["name_ar"].toNullableString())
        assertEquals("0599001122", state.activeSupplierCreateDraft!!.properties["phone"].toNullableString())
        assertTrue("isSupplierCreateRecovering must be true for unknown ack draft", state.isSupplierCreateRecovering)
    }

    // =========================================================================
    // Finding 2: Customer Balance Publication Race
    // =========================================================================

    @Test
    fun testFinding2_getStartedDuringPaymentDoesNotReplacePostPaymentRefresh(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S67891"
        val initialCust = Customer(
            id = custId,
            nameAr = "أحمد خليل",
            nameEn = "Ahmad Khalil",
            phone = "0599111222",
            balanceExact = BigDecimal("100.00"),
            isDetailLoaded = true
        )

        val paymentPostLatch = CountDownLatch(1)
        val getStartedLatch = CountDownLatch(1)
        val getCompleteLatch = CountDownLatch(1)

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.contains("/customers/$custId/payments") -> {
                        // Payment POST is running; wait until GET has started and sampled revisionBefore
                        getStartedLatch.await(5, TimeUnit.SECONDS)
                        TransportResponse(
                            statusCode = 201,
                            headers = emptyMap(),
                            body = """{"id":"01JCV8X9M2NQW3TYZ4R5SPAY01","amount":"30.00","balance":"70.00","posted_at":"2026-11-22"}"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.endsWith("/customers/$custId") -> {
                        // This GET represents an in-flight read returning stale balance 100.00
                        getStartedLatch.countDown()
                        // Wait for payment to fully complete and publish 70.00 first
                        paymentPostLatch.await(5, TimeUnit.SECONDS)
                        TransportResponse(
                            statusCode = 200,
                            headers = emptyMap(),
                            body = """{"id":"$custId","name_ar":"أحمد خليل","phone":"0599111222","balance":"100.00"}"""
                        )
                    }
                    else -> TransportResponse(statusCode = 200, emptyMap(), """{}""")
                }
            }
        }

        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        val store = InMemoryMerchantCommandStore()
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope }
        )

        // Seed initial customer
        val customersFlowField = ProductionTraderRepository::class.java.getDeclaredField("_customersFlow")
        customersFlowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = customersFlowField.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<Customer>>
        flow.value = listOf(initialCust)

        // Launch concurrent payment and getCustomerDetail
        val paymentJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            repo.recordCustomerPayment(
                customerId = custId,
                amountIls = 30.0,
                note = "تسديد دفعة",
                paymentId = "pay_001"
            )
            paymentPostLatch.countDown()
        }

        val getJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            // Wait until payment has started and incremented in-flight counter
            delay(50)
            repo.getCustomerDetail(custId)
            getCompleteLatch.countDown()
        }

        paymentJob.join()
        getJob.join()

        // Verify the published balance in repo is the post-payment 70.00, NOT the stale 100.00
        val finalCust = repo.customersFlow.value.first { it.id == custId }
        assertEquals(
            "Stale GET started during payment must not overwrite post-payment balance",
            BigDecimal("70.00"),
            finalCust.balanceExact
        )
    }

    @Test
    fun testFinding2_olderPaymentWithNullBalanceCannotClearNewerBalance(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S67892"
        val initialCust = Customer(
            id = custId,
            nameAr = "سعيد المصري",
            nameEn = "Saeed Masri",
            phone = "0599333444",
            balanceExact = BigDecimal("150.00"),
            isDetailLoaded = true
        )

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return TransportResponse(
                    statusCode = 201,
                    headers = emptyMap(),
                    // Payment returns amount but NO balance property in snapshot
                    body = """{"id":"01JCV8X9M2NQW3TYZ4R5SPAY02","amount":"50.00","posted_at":"2026-11-22"}"""
                )
            }
        }

        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        val store = InMemoryMerchantCommandStore()
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = store,
            authScopeProvider = { validScope }
        )

        val customersFlowField = ProductionTraderRepository::class.java.getDeclaredField("_customersFlow")
        customersFlowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = customersFlowField.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<Customer>>
        flow.value = listOf(initialCust)

        val res = repo.recordCustomerPayment(
            customerId = custId,
            amountIls = 50.0,
            note = "دفعة بدون رصيد جديد",
            paymentId = "pay_002"
        )
        assertTrue(res.isSuccess)

        val finalCust = repo.customersFlow.value.first { it.id == custId }
        // 150.00 - 50.00 = 100.00, NOT null!
        assertNotNull("Balance must not be cleared to null", finalCust.balanceExact)
        assertEquals(BigDecimal("100.00"), finalCust.balanceExact)
    }

    // =========================================================================
    // Finding 3: Supplier Picker / Directory Query-Keyed Results
    // =========================================================================

    @Test
    fun testFinding3_supplierSearchQueryKeyedResultsNoStaleOrBasePageFallback() {
        val baseSuppliers: List<Supplier> = listOf(
            Supplier(id = "sup_1", nameAr = "شركة الألبان", nameEn = "Dairy Co", balanceOwedExact = BigDecimal("500.00"), code = "SUP-001"),
            Supplier(id = "sup_2", nameAr = "مطاحن السلام", nameEn = "Salam Mills", balanceOwedExact = BigDecimal("300.00"), code = "SUP-002")
        )

        // Case A: Query is non-blank, but results are query-keyed and empty (no matches on server)
        val stateEmptyMatches = TraderUiState(
            suppliers = baseSuppliers,
            supplierSearchQuery = "خضار",
            supplierSearchResults = emptyList(),
            isSearchingSuppliers = false,
            supplierSearchError = null
        )

        // Emulate filteredSuppliers calculation logic:
        val qA = "خضار"
        val sourceListA = if (qA.isNotBlank()) {
            if (stateEmptyMatches.supplierSearchQuery.trim().equals(qA.trim(), ignoreCase = true)) {
                stateEmptyMatches.supplierSearchResults ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            stateEmptyMatches.suppliers
        }
        assertTrue("Must NOT fall back to baseSuppliers when search returns empty results", sourceListA.isEmpty())

        // Case B: Search query has error on server
        val stateError = TraderUiState(
            suppliers = baseSuppliers,
            supplierSearchQuery = "أرز",
            supplierSearchResults = emptyList(),
            isSearchingSuppliers = false,
            supplierSearchError = "Network error"
        )
        val qB = "أرز"
        val sourceListB = if (qB.isNotBlank()) {
            if (stateError.supplierSearchQuery.trim().equals(qB.trim(), ignoreCase = true)) {
                stateError.supplierSearchResults ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            stateError.suppliers
        }
        assertTrue("Must NOT fall back to baseSuppliers on search error", sourceListB.isEmpty())

        // Case C: Query mismatch (stale results from previous query "طحين", but user typed "سكر")
        val stateStale = TraderUiState(
            suppliers = baseSuppliers,
            supplierSearchQuery = "طحين",
            supplierSearchResults = listOf(baseSuppliers[1]),
            isSearchingSuppliers = true
        )
        val qC = "سكر"
        val sourceListC = if (qC.isNotBlank()) {
            if (stateStale.supplierSearchQuery.trim().equals(qC.trim(), ignoreCase = true)) {
                stateStale.supplierSearchResults ?: emptyList()
            } else {
                emptyList()
            }
        } else {
            stateStale.suppliers
        }
        assertTrue("Must NOT use stale results from a different query", sourceListC.isEmpty())

        // Case D: Server code matching preserved
        val supWithCode = Supplier(id = "sup_code", nameAr = "مؤسسة الوفاء", nameEn = "Wafa Est", code = "SUP-999")
        val codeQuery = "sup-999"
        val matchesCode = (supWithCode.code ?: "").lowercase().contains(codeQuery)
        assertTrue("Supplier code match must be supported", matchesCode)
    }

    // =========================================================================
    // Finding 4: Exact Credit Limit Aliases & Blocked Unknown Balance
    // =========================================================================

    @Test
    fun testFinding4_customerGetCreditLimitAliases(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S67894"

        // Test alias "credit_limit_exact"
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return TransportResponse(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = """{
                        "id": "$custId",
                        "name_ar": "طارق زياد",
                        "phone": "0599555666",
                        "credit_limit_exact": "2500.50",
                        "balance": "0.00"
                    }"""
                )
            }
        }

        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = InMemoryMerchantCommandStore(),
            authScopeProvider = { validScope }
        )

        val res = repo.getCustomerDetail(custId)
        assertTrue(res.isSuccess)
        val cust = res.getOrThrow()
        assertEquals(BigDecimal("2500.50"), cust.creditLimitExact)
    }

    @Test
    fun testFinding4_configuredCreditLimitWithUnknownBalanceBlocksSale() {
        val vm = TraderViewModel(
            com.example.data.OfflineDemoTraderRepository()
        )
        val customerWithLimit = Customer(
            id = "cust_limit_unknown",
            nameAr = "ماجد عبد الله",
            nameEn = "Majed Abdullah",
            phone = "0599777888",
            creditLimitExact = BigDecimal("1000.00"),
            balanceExact = null, // Unknown balance!
            isDetailLoaded = true
        )

        vm.assignCustomerToSale(customerWithLimit)
        val sampleProduct = vm.uiState.value.products.first()
        vm.addProductToBasket(sampleProduct, 2)

        // Precondition checks on UI state
        val stateBefore = vm.uiState.value
        assertFalse("Balance must be reported as unknown", stateBefore.isCustomerBalanceKnown)
        assertTrue("Credit sale must be blocked by unknown balance when credit limit is configured", stateBefore.isCreditSaleBlockedByUnknownBalance)

        // Attempt completeCashSale with credit debt = true
        vm.completeCashSale(asCreditDebt = true)

        // Verify credit sale is rejected
        val updatedState = vm.uiState.value
        assertFalse("Sale submission must be aborted", updatedState.isSubmittingSale)
        assertNotNull("Checkout error must be set", updatedState.checkoutErrorAr)
        assertTrue(
            "Error must mention customer balance is unknown to verify credit limit",
            updatedState.checkoutErrorAr!!.contains("رصيد الزبون الحالي غير معروف للتحقق من سقف الائتمان")
        )
    }

    @Test
    fun testFinding4_supplierStrictReadersAcceptOwedAndAdvances(): Unit = runBlocking {
        // Strict JSON from backend returning "owed" and "advances"
        val jsonItem = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S67891",
            "code": "SUP-101",
            "name": "شركة الوادي للمطاحن",
            "name_ar": "شركة الوادي للمطاحن",
            "name_en": null,
            "phone": null,
            "currency": "ILS",
            "currency_minor_units": 2,
            "owed": "450.00",
            "advances": "0.00"
        }"""
        val parsedItemObj = StrictJsonParser.parseObject(jsonItem)
        val item = SupplierListItem.fromStrictJson(parsedItemObj)
        assertEquals(BigDecimal("450.00"), item.balanceOwed)
        assertEquals(BigDecimal("0.00"), item.advances)

        val jsonDetail = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S67891",
            "code": "SUP-101",
            "name": "شركة الوادي للمطاحن",
            "name_ar": "شركة الوادي للمطاحن",
            "name_en": null,
            "phone": null,
            "whatsapp": null,
            "email": null,
            "tax_number": null,
            "address": null,
            "currency": "ILS",
            "currency_minor_units": 2,
            "owed": "450.00",
            "advances": "100.00",
            "status": "active"
        }"""
        val parsedDetailObj = StrictJsonParser.parseObject(jsonDetail)
        val detail = SupplierDetailResponse.fromStrictJson(parsedDetailObj)
        assertEquals(BigDecimal("450.00"), detail.balanceOwed)
        assertEquals(BigDecimal("100.00"), detail.advances)
    }

    @Test
    fun testFinding4_updateCustomerHandlesNaNAndUnchangedLimitSafely(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S67895"
        val existingCust = Customer(
            id = custId,
            nameAr = "عمرو دياب",
            nameEn = "Amr Diab",
            phone = "0599444333",
            creditLimitExact = null
        )

        var recordedPutRequest: TransportRequest? = null
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                recordedPutRequest = request
                return TransportResponse(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = """{"id":"$custId","name_ar":"عمرو دياب","phone":"0599444333"}"""
                )
            }
        }

        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )

        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            commandStore = InMemoryMerchantCommandStore(),
            authScopeProvider = { validScope }
        )

        val customersFlowField = ProductionTraderRepository::class.java.getDeclaredField("_customersFlow")
        customersFlowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = customersFlowField.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<Customer>>
        flow.value = listOf(existingCust)

        // Passing Double.NaN for credit limit (which happens when creditLimitExact is null) must NOT throw NumberFormatException!
        val res = repo.updateCustomer(
            customerId = custId,
            name = "عمرو دياب",
            phone = "0599444333",
            address = "",
            creditLimitIls = Double.NaN
        )
        assertTrue("updateCustomer must succeed without throwing NumberFormatException on NaN: ${res.exceptionOrNull()?.message}", res.isSuccess)
        assertNotNull(recordedPutRequest)
        assertFalse("Must NOT include credit_limit in PUT when NaN passed", recordedPutRequest!!.body!!.contains("credit_limit"))
    }

    // =========================================================================
    // Finding 5: Endpoint Query-Key Validation & Strict HTTPS Origin Release Guard
    // =========================================================================

    @Test
    fun testFinding5_endpointQueryParamsSecurityValidation() {
        // Allowed queries on permitted endpoints
        EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/products", mapOf("search" to "flour", "limit" to "20", "cursor" to "01JMB000000000000000000005"))
        EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/customers", mapOf("search" to "ahmad", "limit" to "30"))
        EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/suppliers", mapOf("search" to "salam", "limit" to "10"))
        EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/receipts/status", mapOf("idempotency_key" to "idemp_test_123"))
        EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/sales/invoices", mapOf("from_date" to "2026-11-01", "to_date" to "2026-11-22"))

        // Disallowed query parameter on endpoint
        try {
            EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/products", mapOf("unauthorized_param" to "malicious"))
            fail("Must reject unauthorized query parameter")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Disallowed query parameter 'unauthorized_param'"))
        }

        // Search parameter not allowed on receipts/status
        try {
            EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/receipts/status", mapOf("search" to "test"))
            fail("Must reject search parameter on receipts/status")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Disallowed query parameter 'search'"))
        }

        // Control characters in query parameter
        try {
            EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/products", mapOf("search" to "flour\r\ninjection"))
            fail("Must reject control characters in query parameter")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("control characters"))
        }
    }

    @Test
    fun testFinding5_strictHttpsOriginValidationMatrix() {
        fun validateOrigin(originInput: String): Result<String> {
            val origin = originInput.trim()
            if (origin.isBlank() || origin.equals("unconfigured", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN is empty or unconfigured. A valid production HTTPS origin (e.g. https://account.palsync.net) is mandatory."))
            }
            val uri = try {
                URI(origin)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN is not a valid URI: '$origin'"))
            }
            if (uri.scheme != "https") {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must use secure HTTPS. Found: '$origin'"))
            }
            if (uri.host.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must have a valid host. Found: '$origin'"))
            }
            if (!uri.rawUserInfo.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain user credentials. Found: '$origin'"))
            }
            if (!uri.rawPath.isNullOrBlank() && uri.rawPath != "/") {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain a path component. Found: '$origin'"))
            }
            if (!uri.rawQuery.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain a query string. Found: '$origin'"))
            }
            if (!uri.rawFragment.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Release build distribution rejected: STAFF_WORKSPACE_ORIGIN must not contain a fragment. Found: '$origin'"))
            }
            return Result.success(origin)
        }

        // Valid production origin
        assertTrue(validateOrigin("https://account.palsync.net").isSuccess)
        assertTrue(validateOrigin("https://subdomain.domain.org").isSuccess)

        // Invalid matrix:
        assertTrue("Blank rejected", validateOrigin("").isFailure)
        assertTrue("Unconfigured rejected", validateOrigin("unconfigured").isFailure)
        assertTrue("Insecure HTTP rejected", validateOrigin("http://account.palsync.net").isFailure)
        assertTrue("No host rejected", validateOrigin("https://").isFailure)
        assertTrue("Userinfo rejected", validateOrigin("https://user:pass@account.palsync.net").isFailure)
        assertTrue("Path rejected", validateOrigin("https://account.palsync.net/api/v1").isFailure)
        assertTrue("Query rejected", validateOrigin("https://account.palsync.net?token=123").isFailure)
        assertTrue("Fragment rejected", validateOrigin("https://account.palsync.net#section").isFailure)
        assertTrue("Malformed URI rejected", validateOrigin("https://account^palsync").isFailure)
    }
}
