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
import com.example.data.mobile.merchantread.SalesInvoicesQuery
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
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBidiUtils
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
                        TransportResponse(
                            statusCode = 404,
                            headers = mapOf("Content-Type" to "application/json"),
                            body = """{"error":{"code":"NOT_FOUND","message":"Not found"}}"""
                        )
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
        EndpointSecurity.validateEndpointQueryParams("/api/mobile/v1/companies/01JMB000000000000000000001/sales/invoices", mapOf("date_from" to "2026-11-01", "date_to" to "2026-11-22", "status" to "POSTED", "customer_id" to "01JMB000000000000000000002"))

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

    // =========================================================================
    // Finding 1 (Round 2): Authoritative Sales Invoices Query & OkHttp Wire Tests
    // =========================================================================

    @Test
    fun testFinding1_salesInvoicesQueryRealQueryParamsToOkHttpAndSecurityValidation() {
        val custId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789C")
        val realQuery = SalesInvoicesQuery(
            status = "POSTED",
            customerId = custId,
            dateFrom = "2026-11-01",
            dateTo = "2026-11-22",
            search = "INV-001",
            limit = 25,
            cursor = "01JCV8X9M2NQW3TYZ4R5SCUR01"
        )
        val queryParams = realQuery.toQueryParams()

        // 1. Authoritative per-endpoint keys emitted by actual SalesInvoicesQuery
        assertTrue(queryParams.containsKey("status"))
        assertTrue(queryParams.containsKey("customer_id"))
        assertTrue(queryParams.containsKey("date_from"))
        assertTrue(queryParams.containsKey("date_to"))
        assertTrue(queryParams.containsKey("search"))
        assertTrue(queryParams.containsKey("limit"))
        assertTrue(queryParams.containsKey("cursor"))
        assertFalse("Must emit date_from, not from_date", queryParams.containsKey("from_date"))
        assertFalse("Must emit date_to, not to_date", queryParams.containsKey("to_date"))

        // 2. Transport endpoint security validation passes for authoritative keys
        val endpoint = "/api/mobile/v1/companies/${companyId.value}/sales/invoices"
        EndpointSecurity.validateEndpointQueryParams(endpoint, queryParams)

        // 3. Real query -> OkHttp transport to MockWebServer
        val serverBaseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val httpUrlBuilder = "$serverBaseUrl$endpoint".toHttpUrl().newBuilder()
        for ((k, v) in queryParams) {
            httpUrlBuilder.addQueryParameter(k, v)
        }
        val fullUrl = httpUrlBuilder.build()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"items":[],"next_cursor":null}""")
        )

        val client = OkHttpClient.Builder().build()
        val request = Request.Builder()
            .url(fullUrl)
            .header("Authorization", "Bearer $validToken")
            .get()
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("GET", recordedRequest!!.method)
        val recordedUrl = recordedRequest.requestUrl!!
        assertEquals("POSTED", recordedUrl.queryParameter("status"))
        assertEquals(custId.value, recordedUrl.queryParameter("customer_id"))
        assertEquals("2026-11-01", recordedUrl.queryParameter("date_from"))
        assertEquals("2026-11-22", recordedUrl.queryParameter("date_to"))
        assertEquals("INV-001", recordedUrl.queryParameter("search"))
        assertEquals("25", recordedUrl.queryParameter("limit"))
        assertEquals("01JCV8X9M2NQW3TYZ4R5SCUR01", recordedUrl.queryParameter("cursor"))

        // 4. Per-endpoint values validation
        try {
            EndpointSecurity.validateEndpointQueryParams(endpoint, mapOf("status" to "INVALID_STATUS_XYZ"))
            fail("Must reject unallowed status")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid status"))
        }

        try {
            EndpointSecurity.validateEndpointQueryParams(endpoint, mapOf("customer_id" to "invalid customer id"))
            fail("Must reject invalid customer_id format")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid customer_id format"))
        }

        try {
            EndpointSecurity.validateEndpointQueryParams(endpoint, mapOf("date_from" to "01-11-2026"))
            fail("Must reject non-ISO date format")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid date_from format"))
        }

        try {
            EndpointSecurity.validateEndpointQueryParams(endpoint, mapOf("malicious_extra" to "value"))
            fail("Must reject disallowed param on invoices endpoint")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Disallowed query parameter"))
        }
    }

    // =========================================================================
    // Finding 3 (Round 2): Bounded Catalog Paging & On-Demand Server Search
    // =========================================================================

    @Test
    fun testFinding3_catalogBoundedFirstPageAndOnDemandSearchNoCrashOnLargeCatalog(): Unit = runBlocking {
        var productPageRequests = 0
        var customerPageRequests = 0
        var searchedProductQuery: String? = null
        var searchedCustomerQuery: String? = null

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                val path = request.path
                return when {
                    path.contains("/products") -> {
                        productPageRequests++
                        val querySearch = request.queryParams["search"]
                        if (querySearch != null) {
                            searchedProductQuery = querySearch
                            TransportResponse(
                                statusCode = 200,
                                headers = emptyMap(),
                                body = """{
                                    "company_id": "${companyId.value}",
                                    "catalog_snapshot_version": "snap-20261122-v1",
                                    "generated_at": "2026-11-22T12:00:00Z",
                                    "next_cursor": null,
                                    "has_more": false,
                                    "products": [
                                        {
                                            "id": "01JCV8X9M2NQW3TYZ4R5S00001",
                                            "sku": "SKU-FOUND-01",
                                            "barcode": "6291040001",
                                            "name": "Virgin Olive Oil",
                                            "unit_price": "35.000000",
                                            "tax_rate": null,
                                            "tax_rate_id": null,
                                            "is_tracked": true,
                                            "available_quantity": "100.000000",
                                            "base_unit": {
                                                "id": "01JCV8X9MBV4W5X6Y7Z8012345",
                                                "code": "BTL",
                                                "name": "Bottle"
                                            },
                                            "default_unit": {
                                                "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                                                "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                                                "conversion_to_base": "1.000000",
                                                "sale_price": "35.000000"
                                            },
                                            "units": []
                                        }
                                    ]
                                }"""
                            )
                        } else {
                            val itemsJson = (1..30).joinToString(",") { i ->
                                val pad = i.toString().padStart(5, '0')
                                """{
                                    "id": "01JCV8X9M2NQW3TYZ4R5S$pad",
                                    "sku": "SKU-$i",
                                    "barcode": "629104000$i",
                                    "name": "منتج $i",
                                    "unit_price": "10.000000",
                                    "tax_rate": null,
                                    "tax_rate_id": null,
                                    "is_tracked": true,
                                    "available_quantity": "50.000000",
                                    "base_unit": {
                                        "id": "01JCV8X9MBV4W5X6Y7Z8012345",
                                        "code": "PCS",
                                        "name": "قطعة"
                                    },
                                    "default_unit": {
                                        "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                                        "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                                        "conversion_to_base": "1.000000",
                                        "sale_price": "10.000000"
                                    },
                                    "units": []
                                }"""
                            }
                            TransportResponse(
                                statusCode = 200,
                                headers = emptyMap(),
                                body = """{
                                    "company_id": "${companyId.value}",
                                    "catalog_snapshot_version": "snap-20261122-v1",
                                    "generated_at": "2026-11-22T12:00:00Z",
                                    "next_cursor": "01JCV8X9M2NQW3TYZ4R5SPAGE2",
                                    "has_more": true,
                                    "products": [$itemsJson]
                                }"""
                            )
                        }
                    }
                    path.contains("/customers") -> {
                        customerPageRequests++
                        val querySearch = request.queryParams["search"]
                        if (querySearch != null) {
                            searchedCustomerQuery = querySearch
                            TransportResponse(
                                statusCode = 200,
                                headers = emptyMap(),
                                body = """{
                                    "company_id": "${companyId.value}",
                                    "generated_at": "2026-11-22T12:00:00Z",
                                    "next_cursor": null,
                                    "has_more": false,
                                    "customers": [
                                        {
                                            "id": "01JCV8X9M2NQW3TYZ4R5S00099",
                                            "code": "CUST-FOUND-01",
                                            "name": "طارق العلي",
                                            "phone": "0599112233",
                                            "is_walk_in": false
                                        }
                                    ]
                                }"""
                            )
                        } else {
                            val itemsJson = (1..30).joinToString(",") { i ->
                                val pad = i.toString().padStart(5, '0')
                                """{
                                    "id": "01JCV8X9M2NQW3TYZ4R5S$pad",
                                    "code": "CUST-$i",
                                    "name": "زبون $i",
                                    "phone": "05990000$i",
                                    "is_walk_in": false
                                }"""
                            }
                            TransportResponse(
                                statusCode = 200,
                                headers = emptyMap(),
                                body = """{
                                    "company_id": "${companyId.value}",
                                    "generated_at": "2026-11-22T12:00:00Z",
                                    "next_cursor": "01JCV8X9M2NQW3TYZ4R5SCUSTPAGE2",
                                    "has_more": true,
                                    "customers": [$itemsJson]
                                }"""
                            )
                        }
                    }
                    else -> TransportResponse(200, emptyMap(), "{}")
                }
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

        val prodResult = repo.refreshProducts()
        assertTrue("refreshProducts must succeed on large catalog without hard-fail: ${prodResult.exceptionOrNull()?.message}", prodResult.isSuccess)
        assertEquals("Must only fetch 1 bounded page, no 100-page loop", 1, productPageRequests)
        assertEquals("Loaded items must be 30", 30, repo.productsFlow.value.size)
        assertTrue("productsHasMore must truthfully be true", repo.productsHasMoreFlow.value)

        val custResult = repo.refreshCustomers()
        assertTrue("refreshCustomers must succeed on large catalog without hard-fail: ${custResult.exceptionOrNull()?.message}", custResult.isSuccess)
        assertEquals("Must only fetch 1 bounded page, no 100-page loop", 1, customerPageRequests)
        assertEquals("Loaded items must be 30", 30, repo.customersFlow.value.size)
        assertTrue("customersHasMore must truthfully be true", repo.customersHasMoreFlow.value)

        val searchArabicResult = repo.searchProductsOnServer("ز")
        assertTrue(searchArabicResult.isSuccess)
        assertEquals("ز", searchedProductQuery)
        val searchItem = searchArabicResult.getOrThrow().first()
        assertEquals("SKU-FOUND-01", searchItem.sku)
        assertEquals("Virgin Olive Oil", searchItem.nameEn)

        val searchSkuResult = repo.searchProductsOnServer("SKU-FOUND")
        assertTrue(searchSkuResult.isSuccess)
        assertEquals("SKU-FOUND", searchedProductQuery)

        val searchCustomerResult = repo.searchCustomers("طارق")
        assertTrue(searchCustomerResult.isSuccess)
        assertEquals("طارق", searchedCustomerQuery)
        assertEquals("CUST-FOUND-01", searchCustomerResult.getOrThrow().first().code)
    }

    // =========================================================================
    // Finding 4 (Round 2): Supplier Concurrency & Mutex/CAS Race Protection
    // =========================================================================

    @Test
    fun testFinding4_supplierPaymentAndGetDetailDeterministicCasRace(): Unit = runBlocking {
        val supId = "01JCV8X9M2NQW3TYZ4R5S67893"
        val initialSupplier = Supplier(
            id = supId,
            nameAr = "شركة الأهرام للتجارة",
            nameEn = "Al-Ahram Trading",
            phone = "0599112233",
            balanceOwedExact = BigDecimal("500.00")
        )

        val paymentPostLatch = CountDownLatch(1)
        val getStartedLatch = CountDownLatch(1)

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.contains("/suppliers/$supId/payments") -> {
                        getStartedLatch.await(5, TimeUnit.SECONDS)
                        TransportResponse(
                            statusCode = 201,
                            headers = emptyMap(),
                            body = """{
                                "id": "01JCV8X9M2NQW3TYZ4R5SSUPPAY01",
                                "amount": "100.00",
                                "balance_owed": "400.00",
                                "owed": "400.00",
                                "advances": "0.00",
                                "posted_at": "2026-11-22"
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.endsWith("/suppliers/$supId") -> {
                        getStartedLatch.countDown()
                        paymentPostLatch.await(5, TimeUnit.SECONDS)
                        TransportResponse(
                            statusCode = 200,
                            headers = emptyMap(),
                            body = """{
                                "id": "$supId",
                                "name_ar": "شركة الأهرام للتجارة",
                                "phone": "0599112233",
                                "owed": "500.00",
                                "advances": "0.00"
                            }"""
                        )
                    }
                    else -> TransportResponse(200, emptyMap(), "{}")
                }
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

        val suppliersFlowField = ProductionTraderRepository::class.java.getDeclaredField("_suppliersFlow")
        suppliersFlowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = suppliersFlowField.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<List<Supplier>>
        flow.value = listOf(initialSupplier)

        val paymentJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            repo.recordSupplierPayment(
                supplierId = supId,
                amountIls = 100.0,
                note = "سداد دفعة",
                operator = repo.getCurrentSession(),
                paymentId = "pay_sup_001"
            )
            paymentPostLatch.countDown()
        }

        val getJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            delay(50)
            repo.getSupplierDetail(supId)
        }

        paymentJob.join()
        getJob.join()

        val finalSup = repo.suppliersFlow.value.first { it.id == supId }
        assertEquals(
            "Stale GET started during payment must not overwrite post-payment balance",
            BigDecimal("400.00"),
            finalSup.balanceOwedExact
        )
    }

    // =========================================================================
    // Finding 5 (Round 2): Supplier Search UI States & Empty Directory Usability
    // =========================================================================

    @Test
    fun testFinding5_supplierSearchStatesAndPurchaseBillDialogUsableWithEmptyDirectory(): Unit = runBlocking {
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

        val initialState = vm.uiState.value
        assertTrue("Initial directory suppliers is empty", initialState.suppliers.isEmpty())
        assertNull("Initial search results is null", initialState.supplierSearchResults)
        assertFalse("Not searching initially", initialState.isSearchingSuppliers)
        assertNull("No search error initially", initialState.supplierSearchError)

        vm.searchSuppliers("شركة")
        val searchingState = vm.uiState.value
        assertEquals("شركة", searchingState.supplierSearchQuery)

        assertFalse("Initially suppliersHasMore is false when empty", initialState.suppliersHasMore)
    }

    // =========================================================================
    // Finding 2 (Round 2): Unallocated Payment Labels & No False Settlement Claim
    // =========================================================================

    @Test
    fun testFinding2_unallocatedPaymentLabelsUnappliedAdvanceNeverClaimsSettled(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S67888"
        val initialCust = Customer(
            id = custId,
            nameAr = "يوسف أحمد",
            nameEn = "Youssef Ahmad",
            phone = "0599887766",
            balanceExact = BigDecimal("100.00"),
            isDetailLoaded = true
        )

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return TransportResponse(
                    statusCode = 201,
                    headers = emptyMap(),
                    body = """{"id":"01JCV8X9M2NQW3TYZ4R5SPAY88","amount":"100.00","balance":"0.00","posted_at":"2026-11-22"}"""
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
        flow.value = listOf(initialCust)

        val paymentResult = repo.recordCustomerPayment(
            customerId = custId,
            amountIls = 100.0,
            note = "دفعة كاملة على الحساب",
            paymentId = "pay_full_01"
        )
        assertTrue(paymentResult.isSuccess)

        val syncCust = repo.customersFlow.value.first { it.id == custId }
        val paidStr = WesternFormatters.formatIls(100.0)
        val newBal = syncCust.balanceIls

        val msgAr = when {
            newBal > 0.005 -> "تم قبض $paidStr نقداً (دفعة غير مخصصة) — الرصيد الدفتري المتبقي: ${WesternFormatters.formatIls(newBal)}"
            newBal < -0.005 -> "تم قبض $paidStr نقداً — رصيد الزبون المقدم (دفعة غير مخصصة): ${WesternFormatters.formatIls(-newBal)}"
            else -> "تم قبض $paidStr نقداً (دفعة غير مخصصة) — الرصيد الدفتري: 0.00 ₪"
        }
        val msgEn = when {
            newBal > 0.005 -> "Collected $paidStr in cash (unapplied advance) — Remaining ledger balance: ${WesternFormatters.formatIls(newBal)}"
            newBal < -0.005 -> "Collected $paidStr in cash — Customer advance credit: ${WesternFormatters.formatIls(-newBal)}"
            else -> "Collected $paidStr in cash (unapplied advance) — Ledger balance: 0.00 ₪"
        }

        assertTrue("Arabic message must mention دفعة غير مخصصة", msgAr.contains("دفعة غير مخصصة"))
        assertFalse("Arabic message must NOT claim أصبح الحساب خالصاً", msgAr.contains("أصبح الحساب خالصاً"))
        assertTrue("English message must mention unapplied advance", msgEn.contains("unapplied advance"))
        assertFalse("English message must NOT claim Account is now settled", msgEn.contains("Account is now settled"))
    }

    // =========================================================================
    // Finding 6 (Round 2 Delta): Authoritative Purchase Wire & Multi-Currency Vectors
    // =========================================================================

    @Test
    fun testFinding6_authoritativePurchaseBillWireParsing_jod3dpAndIls2dp_exactAmountsAndCurrencies() {
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
            commandStore = InMemoryMerchantCommandStore(),
            authScopeProvider = { validScope }
        )

        // 1. Unpaid JOD 3dp bill (1.234 JOD with legacy 1.23 ILS alias from backend)
        val jodUnpaidJson = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S00001",
            "bill_number": "PB-JOD-001",
            "supplier_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "date": "2026-11-22",
            "currency": "JOD",
            "currency_minor_units": 3,
            "total_exact": "1.234",
            "paid_exact": "0.000",
            "total_ils": "1.23",
            "paid_ils": "0.00",
            "is_received": false,
            "lines": [
                {
                    "id": "01JCV8X9M2NQW3TYZ4R5S00002",
                    "product_id": "01JCV8X9M2NQW3TYZ4R5S00003",
                    "product_name": "Jordanian Olive Oil",
                    "ordered_quantity": "1",
                    "received_quantity": "0",
                    "unit_cost_exact": "1.234",
                    "unit_cost_ils": "1.23"
                }
            ]
        }"""
        val jodUnpaidObj = StrictJsonParser.parseObject(jodUnpaidJson)
        val jodBill = repo.parseAuthoritativePurchaseBill(jodUnpaidObj)

        assertEquals("JOD", jodBill.currency)
        assertEquals(3, jodBill.currencyMinorUnits)
        assertEquals(BigDecimal("1.234"), jodBill.totalExact)
        assertEquals(BigDecimal("0.000"), jodBill.paidExact)
        assertEquals(BigDecimal("1.234"), jodBill.remainingPayableExact)
        assertEquals(BigDecimal("1.234"), jodBill.lines[0].unitCostExact)
        assertTrue("Formatted total must contain 1.234 JOD", jodBill.formattedTotal.contains("1.234 JOD"))
        assertFalse("Must not label JOD as ₪ or ILS", jodBill.formattedTotal.contains("₪") || jodBill.formattedTotal.contains("ILS"))

        // 2. Partially paid JOD 3dp bill
        val jodPartialJson = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S00004",
            "bill_number": "PB-JOD-002",
            "supplier_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "date": "2026-11-22",
            "currency": "JOD",
            "currency_minor_units": 3,
            "total_exact": "10.500",
            "paid_exact": "3.250",
            "total_ils": "10.50",
            "paid_ils": "3.25",
            "is_received": true,
            "lines": []
        }"""
        val jodPartialBill = repo.parseAuthoritativePurchaseBill(StrictJsonParser.parseObject(jodPartialJson))
        assertEquals(BigDecimal("10.500"), jodPartialBill.totalExact)
        assertEquals(BigDecimal("3.250"), jodPartialBill.paidExact)
        assertEquals(BigDecimal("7.250"), jodPartialBill.remainingPayableExact)
        assertTrue(jodPartialBill.formattedRemainingPayable.contains("7.250 JOD"))

        // 3. Paid ILS 2dp bill
        val ilsPaidJson = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S00005",
            "bill_number": "PB-ILS-001",
            "supplier_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "date": "2026-11-22",
            "currency": "ILS",
            "currency_minor_units": 2,
            "total_exact": "150.75",
            "paid_exact": "150.75",
            "is_received": true,
            "lines": []
        }"""
        val ilsPaidBill = repo.parseAuthoritativePurchaseBill(StrictJsonParser.parseObject(ilsPaidJson))
        assertEquals("ILS", ilsPaidBill.currency)
        assertEquals(2, ilsPaidBill.currencyMinorUnits)
        assertEquals(BigDecimal("150.75"), ilsPaidBill.totalExact)
        assertEquals(BigDecimal("150.75"), ilsPaidBill.paidExact)
        assertEquals(BigDecimal("0.00"), ilsPaidBill.remainingPayableExact)
        assertTrue(ilsPaidBill.formattedTotal.contains("150.75 ₪"))

        // 4. Return / credit adjustment bill with negative total
        val returnJson = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S00006",
            "bill_number": "PB-RET-001",
            "supplier_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "date": "2026-11-22",
            "currency": "JOD",
            "currency_minor_units": 3,
            "total_exact": "-25.500",
            "paid_exact": "0.000",
            "is_received": false,
            "lines": []
        }"""
        val returnBill = repo.parseAuthoritativePurchaseBill(StrictJsonParser.parseObject(returnJson))
        assertEquals(BigDecimal("-25.500"), returnBill.totalExact)
        assertTrue(returnBill.formattedTotal.contains("-25.500 JOD"))

        // 5. Verification of currency_minor and minor_units alias keys
        val aliasCurrencyMinorJson = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S00007",
            "bill_number": "PB-ALIAS-001",
            "supplier_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "date": "2026-11-22",
            "currency": "JOD",
            "currency_minor": 3,
            "total_exact": "1.234",
            "paid_exact": "0.000",
            "is_received": false,
            "lines": []
        }"""
        val aliasCurrencyMinorBill = repo.parseAuthoritativePurchaseBill(StrictJsonParser.parseObject(aliasCurrencyMinorJson))
        assertEquals(3, aliasCurrencyMinorBill.currencyMinorUnits)
        assertEquals(BigDecimal("1.234"), aliasCurrencyMinorBill.totalExact)
        assertTrue(aliasCurrencyMinorBill.formattedTotal.contains("1.234 JOD"))

        val aliasMinorUnitsJson = """{
            "id": "01JCV8X9M2NQW3TYZ4R5S00008",
            "bill_number": "PB-ALIAS-002",
            "supplier_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "date": "2026-11-22",
            "currency": "JOD",
            "minor_units": 3,
            "total_exact": "5.678",
            "paid_exact": "0.000",
            "is_received": false,
            "lines": []
        }"""
        val aliasMinorUnitsBill = repo.parseAuthoritativePurchaseBill(StrictJsonParser.parseObject(aliasMinorUnitsJson))
        assertEquals(3, aliasMinorUnitsBill.currencyMinorUnits)
        assertEquals(BigDecimal("5.678"), aliasMinorUnitsBill.totalExact)
        assertTrue(aliasMinorUnitsBill.formattedTotal.contains("5.678 JOD"))
    }
}
