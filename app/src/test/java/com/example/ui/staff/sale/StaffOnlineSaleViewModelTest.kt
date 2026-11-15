package com.example.ui.staff.sale

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.onlinecash.AndroidV2OnlineCashClient
import com.example.data.mobile.onlinecash.DurableOnlineSaleRecoveryRecord
import com.example.data.mobile.onlinecash.DurableOnlineSaleRecoveryStore
import com.example.data.mobile.onlinecash.OnlineSaleRecoveryException
import com.example.data.mobile.onlinecash.OnlineCashOrchestrationResult
import com.example.data.mobile.onlinecash.OnlineCashSaleOrchestrator
import com.example.data.mobile.onlinecash.OnlineCashSaleReceipt
import com.example.data.mobile.onlinecash.OnlineSaleScope
import com.example.data.mobile.onlinecash.PreparedOnlineSaleIntent
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.AuthChallengeResponse
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.BootstrapStaff
import com.example.data.mobile.readclient.BootstrapTax
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.DefaultWarehouse
import com.example.data.mobile.readclient.OfflinePolicyDisabled
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ProductDefaultUnit
import com.example.data.mobile.readclient.ReadClientErrorResponse
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.AuthDenialListener
import java.io.IOException
import com.example.data.mobile.session.AuthDenialScope
import com.example.data.mobile.session.CoordinatorResult
import com.example.data.mobile.session.LogoutResult
import com.example.data.mobile.session.RemoteRevocationStatus
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionCoordinator
import com.example.data.mobile.session.StaffSessionRecord
import com.example.ui.staff.StaffWorkspacePhase
import com.example.ui.staff.StaffWorkspaceTab
import com.example.ui.staff.StaffWorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class StaffOnlineSaleViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val companyUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val actorUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val deviceUuid = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val warehouseUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val cashAccountUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA3")
    private val walkInUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA4")
    private val productUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA5")
    private val unitUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA6")

    private lateinit var fakeCoordinator: TestStaffCoordinator
    private lateinit var defaultRecoveryStore: InMemoryDurableOnlineSaleRecoveryStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        defaultRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore()
        fakeCoordinator = TestStaffCoordinator(
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            warehouseId = warehouseUlid,
            cashAccountId = cashAccountUlid,
            walkInId = walkInUlid
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleProduct(unitPrice: String = "20.000000"): ProductCatalogItem {
        return ProductCatalogItem(
            id = productUlid,
            sku = "SKU-001",
            barcode = "1234567890123",
            name = "Test Product",
            unitPrice = MoneyAmount.fromNonnegativeString(unitPrice),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("50.000000"),
            baseUnit = ProductBaseUnit(unitUlid, "PCS", "Pieces"),
            defaultUnit = ProductDefaultUnit(
                unitId = unitUlid,
                productUnitId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAP"),
                conversionToBase = PositiveDecimalQuantity.of(1),
                salePrice = MoneyAmount.fromNonnegativeString(unitPrice)
            ),
            units = emptyList()
        )
    }

    @Test
    fun testDraftLineAdditionAndQuantityModification() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "15.000000")

        // 1. Add product to sale draft (with explicitNoTax = true)
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        assertEquals("1", vm.uiState.value.saleDraft.lines[0].quantity.toPlainString())
        assertEquals("15.000000", vm.uiState.value.saleDraft.estimatedSubtotal.toPlainString())

        // 2. Add same product again -> quantity increments cleanly without duplicate line
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(2), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        assertEquals("3", vm.uiState.value.saleDraft.lines[0].quantity.toPlainString())
        assertEquals("45.000000", vm.uiState.value.saleDraft.estimatedSubtotal.toPlainString())

        // 3. Update quantity directly
        val lineId = vm.uiState.value.saleDraft.lines[0].lineId
        vm.updateDraftLineQuantity(lineId, PositiveDecimalQuantity.of(4))
        assertEquals("4", vm.uiState.value.saleDraft.lines[0].quantity.toPlainString())
        assertEquals("60.000000", vm.uiState.value.saleDraft.estimatedSubtotal.toPlainString())

        // 4. Remove line
        vm.removeDraftLine(lineId)
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
        assertEquals("0.000000", vm.uiState.value.saleDraft.estimatedSubtotal.toPlainString())

        // 5. Clear draft
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertFalse(vm.uiState.value.saleDraft.lines.isEmpty())
        vm.clearSaleDraft()
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
    }

    @Test
    fun testSessionScopedOrchestratorDispatchesActiveGenerationAndToken() = runTest(testDispatcher) {
        fakeCoordinator.currentGeneration = 42L

        val capturedRequests = mutableListOf<TransportRequest>()
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                capturedRequests.add(request)
                val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                val dummyCommitJson = """
                    {
                        "client_event_id": "$clientEventId",
                        "status": "posted",
                        "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                        "invoice_number": "INV-2026-001",
                        "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA1",
                        "payment_receipt_number": "RCT-001",
                        "grand_total": "30.00",
                        "amount_paid": "30.00",
                        "change_returned": "0.00",
                        "posted_at": "2026-11-15T12:00:00Z"
                    }
                """.trimIndent()
                return TransportResponse(201, emptyMap(), dummyCommitJson)
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.selectTab(StaffWorkspaceTab.CASH_SALE)
        val authority = vm.uiState.value.saleAuthority
        assertNotNull(authority)
        assertTrue(authority!!.isDispatchPermitted)
        assertEquals(2, authority.currencyMinorUnits)

        // Add product to draft
        val product = createSampleProduct(unitPrice = "30.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        assertEquals("30.000000", vm.uiState.value.saleDraft.estimatedTotal.toPlainString())

        // Actually submit the sale through ViewModel
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        // Assert real transport request captured with actual session bearer, scope path, and wire payload
        assertEquals(1, capturedRequests.size)
        val request = capturedRequests[0]
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("/api/mobile/v2/companies/${companyUlid.value}/sales/online-cash", request.path)
        assertEquals("Bearer test_bearer_token_abc123", request.headers["Authorization"])
        assertNotNull(request.body)
        assertTrue(request.body!!.contains(productUlid.value))
        assertTrue(request.body!!.contains("30.000000"))

        // Assert confirmed receipt recorded in ViewModel state and basket cleared
        val receipt = vm.uiState.value.saleDraft.receipt
        assertNotNull(receipt)
        assertEquals("INV-2026-001", receipt!!.invoiceNumber)
        assertEquals("RCT-001", receipt.paymentReceiptNumber)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
    }

    @Test
    fun testAuthDenialNotifiesListenerWithExactScope() = runTest(testDispatcher) {
        fakeCoordinator.currentGeneration = 99L
        val denialRef = AtomicReference<AuthDenialScope?>(null)

        val authDenialListener = AuthDenialListener { scope ->
            denialRef.set(scope)
        }

        var lockHookCaptured: ((Long, ReadClientErrorResponse) -> Unit)? = null

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            authDenialListener = authDenialListener,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                lockHookCaptured = lockHook
                val dummyClient = AndroidV2OnlineCashClient(
                    transport = object : ReadClientTransport {
                        override fun execute(request: TransportRequest): TransportResponse {
                            throw UnsupportedOperationException()
                        }
                    },
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = dummyClient,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()
        vm.getOrchestrator()

        // Trigger lock hook with current generation
        assertNotNull(lockHookCaptured)
        val dummyError = ReadClientErrorResponse(
            code = "AUTH_DENIED",
            message = "Bearer token revoked"
        )
        lockHookCaptured!!.invoke(99L, dummyError)

        val denial = denialRef.get()
        assertNotNull(denial)
        assertEquals(companyUlid, denial!!.companyId)
        assertEquals(actorUlid, denial.actorId)
        assertEquals(deviceUuid, denial.deviceId)
    }

    @Test
    fun testCoherentCancellationOnLogout() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(), quantity = PositiveDecimalQuantity.of(2), explicitNoTax = true)
        assertFalse(vm.uiState.value.saleDraft.lines.isEmpty())

        // Logout
        vm.logout()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
        assertNull(vm.uiState.value.saleDraft.receipt)
        assertNull(vm.uiState.value.saleAuthority)
    }

    @Test
    fun testIndeterminateTransientRetainsSamePreparedIntentAndEventIdOnRetry() = runTest(testDispatcher) {
        val capturedRequests = mutableListOf<TransportRequest>()

        // Real transport simulates socket timeout on POST and Lost-ACK GET status
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                capturedRequests.add(request)
                throw IOException("Simulated network timeout during socket transport")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "45.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)

        // First submit attempt: network timeout produces IndeterminateTransient
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isIndeterminate)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertTrue(vm.uiState.value.saleDraft.submissionError?.contains("Network timeout") == true)
        val firstIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(firstIntent)

        // Retry submit attempt: MUST reuse EXACT same PreparedOnlineSaleIntent (same clientEventId & wire bytes)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        val secondIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(secondIntent)

        assertEquals(firstIntent!!.clientEventId, secondIntent!!.clientEventId)
        assertTrue(firstIntent.rawWireBytes.contentEquals(secondIntent.rawWireBytes))
        assertEquals(firstIntent, secondIntent)

        val postRequests = capturedRequests.filter { it.method == HttpMethod.POST }
        assertTrue(postRequests.size >= 2)
        assertEquals(postRequests[0].body, postRequests[1].body)
    }

    @Test
    fun testDraftMutationsFrozenWhileIndeterminate() = runTest(testDispatcher) {
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                throw IOException("Simulated gateway timeout")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "50.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        val initialLine = vm.uiState.value.saleDraft.lines.first()

        // Submit and trigger indeterminate state via transport failure
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isIndeterminate)

        // Attempt mutations while indeterminate - all must be ignored (frozen)
        val otherProduct = ProductCatalogItem(
            id = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5PT1"),
            sku = "SKU-OTHER",
            barcode = null,
            name = "Other Product",
            unitPrice = MoneyAmount.fromNonnegativeString("10.000000"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("10.000000"),
            baseUnit = ProductBaseUnit(unitUlid, "PCS", "Pieces"),
            defaultUnit = ProductDefaultUnit(unitUlid, ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5PV1"), PositiveDecimalQuantity.of(1), MoneyAmount.fromNonnegativeString("10.000000")),
            units = emptyList()
        )
        vm.addProductToSaleDraft(otherProduct, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        vm.updateDraftLineQuantity(initialLine.lineId, PositiveDecimalQuantity.of(10))
        assertEquals("1", vm.uiState.value.saleDraft.lines.first().quantity.value.toPlainString())

        vm.removeDraftLine(initialLine.lineId)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        vm.selectCustomerForSale(CustomerDirectoryItem(ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5CS2"), "C2", "Customer 2", null, false))
        assertNull(vm.uiState.value.saleDraft.selectedCustomer)

        vm.updateCashTendered(MoneyAmount.fromNonnegativeString("100.00"))
        assertNull(vm.uiState.value.saleDraft.cashTendered)

        vm.clearSaleDraft()
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        assertTrue(vm.uiState.value.saleDraft.isIndeterminate)
    }

    @Test
    fun testAuthDeniedActiveScopeLocksPhaseAndClearsLists() = runTest(testDispatcher) {
        val denialRef = AtomicReference<AuthDenialScope?>(null)
        val authDenialListener = AuthDenialListener { scope ->
            denialRef.set(scope)
        }

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return TransportResponse(401, emptyMap(), """{"error":"TOKEN_EXPIRED","message":"Staff session token expired","status":401}""")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            authDenialListener = authDenialListener,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.LOCKED, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.lockedReason?.contains("HTTP 401") == true)
        assertTrue(vm.uiState.value.products.isEmpty())
        assertTrue(vm.uiState.value.customers.isEmpty())
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
        assertNull(vm.uiState.value.saleAuthority)

        val denial = denialRef.get()
        assertNotNull(denial)
        assertEquals(companyUlid, denial!!.companyId)
        assertEquals(actorUlid, denial.actorId)
        assertEquals(deviceUuid, denial.deviceId)
    }

    @Test
    fun testStaleAuthDeniedDoesNotLockCurrentScope() = runTest(testDispatcher) {
        val denialRef = AtomicReference<AuthDenialScope?>(null)
        val authDenialListener = AuthDenialListener { scope ->
            denialRef.set(scope)
        }

        // Real transport simulates generation advancement during network flight
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                fakeCoordinator.currentGeneration = 999L
                return TransportResponse(401, emptyMap(), """{"error":"STALE_TOKEN","message":"Stale token from old generation","status":401}""")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            authDenialListener = authDenialListener,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        // Stale 401 MUST NOT lock the current session!
        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertNull(vm.uiState.value.lockedReason)
        assertNotNull(vm.uiState.value.saleDraft.submissionError)
        assertTrue(
            vm.uiState.value.saleDraft.submissionError!!.contains("expired") ||
            vm.uiState.value.saleDraft.submissionError!!.contains("Stale")
        )
    }

    @Test
    fun testPostedWithoutPaymentReference_holdsBasketAndPreservesPreparedIntent() = runTest(testDispatcher) {
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                // Response with null payment reference (payment_id = null, payment_receipt_number = null)
                val unverifiedPaymentJson = """
                    {
                        "client_event_id": "$clientEventId",
                        "status": "posted",
                        "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                        "invoice_number": "INV-UNVERIFIED-01",
                        "payment_id": null,
                        "payment_receipt_number": null,
                        "grand_total": "25.00",
                        "amount_paid": "0.00",
                        "change_returned": "0.00",
                        "posted_at": "2026-11-15T12:00:00Z"
                    }
                """.trimIndent()
                return TransportResponse(201, emptyMap(), unverifiedPaymentJson)
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "25.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        vm.submitOnlineCashSale()
        advanceUntilIdle()

        // MUST be in explicit payment unverified hold state
        val draft = vm.uiState.value.saleDraft
        assertTrue("isPaymentUnverifiedHold must be true", draft.isPaymentUnverifiedHold)
        assertFalse("isSubmitting must be false", draft.isSubmitting)
        assertFalse("isIndeterminate must be false", draft.isIndeterminate)
        assertNull("receipt must be null (never treated as paid!)", draft.receipt)
        assertNotNull("unverifiedReceipt must be recorded", draft.unverifiedReceipt)
        assertEquals("INV-UNVERIFIED-01", draft.unverifiedReceipt?.invoiceNumber)

        // Basket lines and prepared intent MUST be preserved
        assertEquals(1, draft.lines.size)
        assertNotNull("preparedIntent must be preserved", draft.preparedIntent)
        assertTrue(draft.submissionError?.contains("unverified") == true || draft.submissionError?.contains("posted") == true)

        // Mutations and re-submit must be blocked
        val initialLine = draft.lines.first()
        vm.updateDraftLineQuantity(initialLine.lineId, PositiveDecimalQuantity.of(5))
        assertEquals("1", vm.uiState.value.saleDraft.lines.first().quantity.toPlainString())

        vm.removeDraftLine(initialLine.lineId)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        vm.clearSaleDraft()
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)

        vm.submitOnlineCashSale()
        // No change, still on hold
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
    }

    @Test
    fun testLocalTokenUnavailable_allowsEditing_invalidatesPreparedIntent() = runTest(testDispatcher) {
        var tokenAvailable = false

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                val commitJson = """
                    {
                        "client_event_id": "$clientEventId",
                        "status": "posted",
                        "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                        "invoice_number": "INV-2026-002",
                        "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA2",
                        "payment_receipt_number": "RCT-002",
                        "grand_total": "50.00",
                        "amount_paid": "50.00",
                        "change_returned": "0.00",
                        "posted_at": "2026-11-15T12:00:00Z"
                    }
                """.trimIndent()
                return TransportResponse(201, emptyMap(), commitJson)
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = { if (tokenAvailable) tokenProvider() else null },
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "25.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)

        // 1. First submit with token unavailable -> LocalTokenUnavailable
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertNull("preparedIntent must be invalidated / cleared on pre-dispatch failure", vm.uiState.value.saleDraft.preparedIntent)
        assertNotNull(vm.uiState.value.saleDraft.submissionError)

        // 2. Edit basket line quantity -> succeeds and keeps preparedIntent null
        val lineId = vm.uiState.value.saleDraft.lines.first().lineId
        vm.updateDraftLineQuantity(lineId, PositiveDecimalQuantity.of(2))
        assertEquals("2", vm.uiState.value.saleDraft.lines.first().quantity.toPlainString())
        assertNull("preparedIntent must remain null after mutation", vm.uiState.value.saleDraft.preparedIntent)

        // 3. Make token available and submit -> dispatches fresh intent with updated quantity
        tokenAvailable = true
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        val receipt = vm.uiState.value.saleDraft.receipt
        assertNotNull("Sale must succeed", receipt)
        assertEquals("INV-2026-002", receipt?.invoiceNumber)
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
    }

    @Test
    fun testConcurrentSubmit_executesSingleRequest() = runTest(testDispatcher) {
        var requestCount = 0

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                requestCount++
                val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                val commitJson = """
                    {
                        "client_event_id": "$clientEventId",
                        "status": "posted",
                        "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                        "invoice_number": "INV-2026-003",
                        "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA3",
                        "payment_receipt_number": "RCT-003",
                        "grand_total": "20.00",
                        "amount_paid": "20.00",
                        "change_returned": "0.00",
                        "posted_at": "2026-11-15T12:00:00Z"
                    }
                """.trimIndent()
                return TransportResponse(201, emptyMap(), commitJson)
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)

        // Trigger two submits concurrently without advancing dispatcher between them
        vm.submitOnlineCashSale()
        vm.submitOnlineCashSale()

        advanceUntilIdle()

        assertEquals("Only one network request should be dispatched", 1, requestCount)
        assertNotNull(vm.uiState.value.saleDraft.receipt)
        assertEquals("INV-2026-003", vm.uiState.value.saleDraft.receipt?.invoiceNumber)
    }

    @Test
    fun testSetLineTax_validatesCurrentBootstrapTaxes() = runTest(testDispatcher) {
        val taxId1 = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5TX1")
        val validTax = BootstrapTax(
            id = taxId1,
            code = "VAT15",
            name = "VAT 15%",
            rate = TaxPercentage("15.000000"),
            calculation = "exclusive",
            isDefault = true
        )
        val coordinatorWithTax = TestStaffCoordinator(
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            warehouseId = warehouseUlid,
            cashAccountId = cashAccountUlid,
            walkInId = walkInUlid,
            taxes = listOf(validTax)
        )
        val vm = StaffWorkspaceViewModel(
            coordinator = coordinatorWithTax,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore
        )
        advanceUntilIdle()

        // Verify available taxes populated in UI state
        assertEquals(1, vm.uiState.value.availableTaxes.size)
        assertEquals(validTax, vm.uiState.value.availableTaxes[0])

        // Add product without tax assignment (explicitNoTax = false)
        val product = createSampleProduct(unitPrice = "40.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = false)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        val line = vm.uiState.value.saleDraft.lines[0]
        assertNull("Unassigned tax must be null, never automatically zero", line.taxRate)
        assertNull(line.taxRateId)
        assertNull(line.taxInclusive)
        assertTrue("Unassigned line must have unresolved tax contract", line.hasUnresolvedTaxContract)

        // Set valid tax from current bootstrap
        vm.setLineTax(line.lineId, validTax)
        val updatedLine = vm.uiState.value.saleDraft.lines[0]
        assertEquals(validTax.id, updatedLine.taxRateId)
        assertEquals(validTax.rate, updatedLine.taxRate)
        assertEquals(false, updatedLine.taxInclusive)
        assertFalse(updatedLine.hasUnresolvedTaxContract)

        // Attempt setting foreign tax not in bootstrap -> must be rejected
        val foreignTax = BootstrapTax(
            id = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5TX9"),
            code = "FOREIGN",
            name = "Foreign Tax",
            rate = TaxPercentage("10.000000"),
            calculation = "exclusive",
            isDefault = false
        )
        vm.setLineTax(line.lineId, foreignTax)
        val lineAfterForeign = vm.uiState.value.saleDraft.lines[0]
        assertEquals(validTax.id, lineAfterForeign.taxRateId)
        assertEquals(validTax.rate, lineAfterForeign.taxRate)

        // Attempt setting tax with mismatched rate/calculation -> must be rejected
        val mismatchedTax = validTax.copy(rate = TaxPercentage("5.000000"))
        vm.setLineTax(line.lineId, mismatchedTax)
        val lineAfterMismatched = vm.uiState.value.saleDraft.lines[0]
        assertEquals(validTax.id, lineAfterMismatched.taxRateId)
        assertEquals(validTax.rate, lineAfterMismatched.taxRate)
    }

    @Test
    fun testSetLineExplicitNoTax_setsCanonicalZeroTax() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "20.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = false)
        val line = vm.uiState.value.saleDraft.lines[0]
        assertNull(line.taxRate)
        assertTrue(line.hasUnresolvedTaxContract)

        // Explicitly set No Tax control
        vm.setLineExplicitNoTax(line.lineId)
        val noTaxLine = vm.uiState.value.saleDraft.lines[0]
        assertNull(noTaxLine.taxRateId)
        assertEquals("0.000000", noTaxLine.taxRate?.value?.toPlainString())
        assertEquals(false, noTaxLine.taxInclusive)
        assertFalse(noTaxLine.hasUnresolvedTaxContract)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_recoversWhenPaymentConfirmed() = runTest(testDispatcher) {
        var statusCheckCount = 0
        val capturedRequests = mutableListOf<TransportRequest>()
        var statusResponseDigest: String? = null

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                capturedRequests.add(request)
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-01",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "25.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    statusCheckCount++
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    val digestValue = statusResponseDigest
                        ?: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": "$digestValue",
                            "official_invoice": {
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                                "invoice_number": "INV-HOLD-01",
                                "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA1",
                                "payment_receipt_number": "RCT-001",
                                "grand_total": "25.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            },
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        val product = createSampleProduct(unitPrice = "25.000000")
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)

        // 1. Submit -> enters PaymentUnverifiedHold
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNull(vm.uiState.value.saleDraft.receipt)
        assertNotNull(vm.uiState.value.saleDraft.unverifiedReceipt)
        val expectedDigest = vm.uiState.value.saleDraft.preparedIntent!!.expectedNormativeProfileDigest.value
        statusResponseDigest = expectedDigest

        // 2. Trigger checkOnlineCashSaleStatus() -> GET request confirms payment with matching non-null digest
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals(1, statusCheckCount)
        val draft = vm.uiState.value.saleDraft
        assertFalse("Hold must be released", draft.isPaymentUnverifiedHold)
        assertFalse(draft.isSubmitting)
        assertNull(draft.unverifiedReceipt)
        assertNotNull("Confirmed receipt must be set", draft.receipt)
        assertEquals("INV-HOLD-01", draft.receipt?.invoiceNumber)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5PA1", draft.receipt?.paymentId?.value)
        assertEquals("RCT-001", draft.receipt?.paymentReceiptNumber)
        assertTrue("Basket lines must be cleared on confirmed payment", draft.lines.isEmpty())
        assertNull(draft.preparedIntent)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_staysOnHoldWhenPaymentStillNull() = runTest(testDispatcher) {
        val capturedRequests = mutableListOf<TransportRequest>()
        var statusResponseDigest: String? = null

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                capturedRequests.add(request)
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-02",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "30.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    val digestValue = statusResponseDigest
                        ?: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": "$digestValue",
                            "official_invoice": {
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                                "invoice_number": "INV-HOLD-02",
                                "payment_id": null,
                                "payment_receipt_number": null,
                                "grand_total": "30.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            },
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "30.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val originalIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(originalIntent)
        statusResponseDigest = originalIntent!!.expectedNormativeProfileDigest.value

        // Status check -> still null payment
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        val draft = vm.uiState.value.saleDraft
        assertTrue("Must remain on hold when payment still unverified", draft.isPaymentUnverifiedHold)
        assertFalse(draft.isSubmitting)
        assertNull("Confirmed receipt must still be null", draft.receipt)
        assertNotNull(draft.unverifiedReceipt)
        assertEquals(1, draft.lines.size)
        assertEquals(originalIntent, draft.preparedIntent)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_neverPostsOrGeneratesNewUuid() = runTest(testDispatcher) {
        val capturedRequests = mutableListOf<TransportRequest>()

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                capturedRequests.add(request)
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-03",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "15.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "unknown",
                            "payload_digest": null,
                            "official_invoice": null,
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "15.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        val postRequestsBefore = capturedRequests.filter { it.method == HttpMethod.POST }.size
        assertEquals(1, postRequestsBefore)
        val originalClientEventId = vm.uiState.value.saleDraft.preparedIntent!!.clientEventId

        // Call checkOnlineCashSaleStatus multiple times
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        // Verify ZERO additional POSTs occurred
        val postRequestsAfter = capturedRequests.filter { it.method == HttpMethod.POST }.size
        assertEquals("checkOnlineCashSaleStatus must NEVER send POST requests", 1, postRequestsAfter)

        // Verify GET requests were sent for status lookup with exact retained clientEventId
        val getRequests = capturedRequests.filter { it.method == HttpMethod.GET }
        assertEquals(2, getRequests.size)
        getRequests.forEach { req ->
            assertTrue(
                "GET request path must contain original clientEventId ${originalClientEventId.value}",
                req.path.contains(originalClientEventId.value)
            )
        }

        // Verify clientEventId is identical
        assertEquals(originalClientEventId, vm.uiState.value.saleDraft.preparedIntent!!.clientEventId)
    }

    @Test
    fun testOnCleared_cancelsSaleJobAndClearsCachedOrchestrator() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore
        )
        advanceUntilIdle()

        // Populate orchestrator cache
        val orch = vm.getOrchestrator()
        assertNotNull(orch)
        assertTrue(vm.hasCachedOrchestratorForTesting())

        // Clear view model
        vm.performOnClearedForTesting()

        // Cached orchestrator must be null and sale job not active
        assertFalse(vm.hasCachedOrchestratorForTesting())
        assertFalse(vm.isSaleJobActiveForTesting())
    }

    @Test
    fun testAuthoritativeBootstrapCurrencyMinorUnitsTakesPrecedenceOverOverride() = runTest(testDispatcher) {
        val coordinatorWithCurrency = TestStaffCoordinator(
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            warehouseId = warehouseUlid,
            cashAccountId = cashAccountUlid,
            walkInId = walkInUlid,
            currencyMinorUnits = 3
        )
        val vm = StaffWorkspaceViewModel(
            coordinator = coordinatorWithCurrency,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore
        )
        advanceUntilIdle()

        val authority = vm.uiState.value.saleAuthority
        assertNotNull(authority)
        assertEquals("Authoritative bootstrap currency minor units must take precedence", 3, authority!!.currencyMinorUnits)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_postedNullDigest_neverConfirmedHoldKept() = runTest(testDispatcher) {
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-N1",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "20.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    // Server returns posted with official invoice and payment, but NULL payload_digest
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": null,
                            "official_invoice": {
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                                "invoice_number": "INV-HOLD-N1",
                                "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA1",
                                "payment_receipt_number": "RCT-001",
                                "grand_total": "20.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            },
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val originalIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(originalIntent)

        // GET status query with null digest MUST fail closed
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        val draft = vm.uiState.value.saleDraft
        assertTrue("Hold must be KEPT when posted digest is null", draft.isPaymentUnverifiedHold)
        assertFalse(draft.isSubmitting)
        assertNull("Confirmed receipt must NEVER be produced when server returns null digest", draft.receipt)
        assertNotNull("Unverified receipt preserved", draft.unverifiedReceipt)
        assertEquals(1, draft.lines.size)
        assertEquals(originalIntent, draft.preparedIntent)
        assertTrue(draft.submissionError?.contains("digest mismatch", ignoreCase = true) == true)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_postedWrongDigest_neverConfirmedHoldKept() = runTest(testDispatcher) {
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-W1",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "20.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    // Server returns posted with official invoice and payment, but WRONG payload_digest
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                            "official_invoice": {
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                                "invoice_number": "INV-HOLD-W1",
                                "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA1",
                                "payment_receipt_number": "RCT-001",
                                "grand_total": "20.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            },
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val originalIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(originalIntent)

        // GET status query with wrong digest MUST fail closed
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        val draft = vm.uiState.value.saleDraft
        assertTrue("Hold must be KEPT when posted digest is mismatched", draft.isPaymentUnverifiedHold)
        assertFalse(draft.isSubmitting)
        assertNull("Confirmed receipt must NEVER be produced on digest mismatch", draft.receipt)
        assertNotNull("Unverified receipt preserved", draft.unverifiedReceipt)
        assertEquals(1, draft.lines.size)
        assertEquals(originalIntent, draft.preparedIntent)
        assertTrue(draft.submissionError?.contains("digest mismatch", ignoreCase = true) == true)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_postedMatchingDigest_succeedsConfirmedFinancialSuccess() = runTest(testDispatcher) {
        var statusResponseDigest: String? = null
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-M1",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "20.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    val digestValue = statusResponseDigest
                        ?: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": "$digestValue",
                            "official_invoice": {
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                                "invoice_number": "INV-HOLD-M1",
                                "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA1",
                                "payment_receipt_number": "RCT-001",
                                "grand_total": "20.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            },
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val expectedDigest = vm.uiState.value.saleDraft.preparedIntent!!.expectedNormativeProfileDigest.value
        statusResponseDigest = expectedDigest

        // GET status query with MATCHING non-null digest succeeds
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        val draft = vm.uiState.value.saleDraft
        assertFalse("Hold must be released on matching digest and confirmed payment", draft.isPaymentUnverifiedHold)
        assertFalse(draft.isSubmitting)
        assertNull(draft.unverifiedReceipt)
        assertNotNull("Confirmed receipt must be present", draft.receipt)
        assertEquals("INV-HOLD-M1", draft.receipt?.invoiceNumber)
        assertEquals("RCT-001", draft.receipt?.paymentReceiptNumber)
        assertEquals(expectedDigest, draft.receipt?.payloadDigest?.value)
        assertTrue("Basket lines must be cleared", draft.lines.isEmpty())
        assertNull(draft.preparedIntent)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_unknownNullableDigest_remainsUnknownHold() = runTest(testDispatcher) {
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-U1",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "20.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    // Unknown status allows nullable payload_digest
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "unknown",
                            "payload_digest": null,
                            "official_invoice": null,
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val originalIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(originalIntent)

        // Status check -> unknown returns indeterminate transient, hold preserved
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        val draft = vm.uiState.value.saleDraft
        assertNull("Confirmed receipt must still be null on unknown", draft.receipt)
        assertEquals(1, draft.lines.size)
        assertEquals(originalIntent, draft.preparedIntent)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_recoversEvenWhenNewSaleAuthorityInvalid() = runTest(testDispatcher) {
        var postCount = 0
        var getCount = 0
        var statusResponseDigest: String? = null

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    postCount++
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-AUTHDISABLED-1",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "20.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    getCount++
                    val parts = request.path.split("/")
                    val eventId = parts[parts.size - 2]
                    val digestValue = statusResponseDigest
                        ?: "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": "$digestValue",
                            "official_invoice": {
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                                "invoice_number": "INV-HOLD-AUTHDISABLED-1",
                                "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5PA1",
                                "payment_receipt_number": "RCT-AUTH-RECOVERED-01",
                                "grand_total": "20.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            },
                            "conflict": null,
                            "queried_at": "2026-11-15T12:05:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        fakeCoordinator = TestStaffCoordinator(
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            warehouseId = warehouseUlid,
            cashAccountId = cashAccountUlid,
            walkInId = walkInUlid,
            currencyMinorUnits = 2
        )

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = null,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertEquals(1, postCount)
        assertEquals(0, getCount)

        val originalIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(originalIntent)
        val expectedDigest = originalIntent!!.expectedNormativeProfileDigest.value
        statusResponseDigest = expectedDigest

        // Mutate coordinator bootstrap: changed warehouse, changed cash account, and unavailable minor units
        val currentReady = fakeCoordinator.authState as StaffAuthState.ReadReady
        val changedWarehouse = DefaultWarehouse(ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999"), "WH-02", "Changed Warehouse")
        val changedCashAccount = ApprovedCashAccount(ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5888"), "CASH-02", "Changed Cash", "USD")
        val brokenBootstrap = currentReady.bootstrap.copy(
            defaultWarehouse = changedWarehouse,
            approvedCashAccount = changedCashAccount,
            currencyMinorUnits = null
        )
        fakeCoordinator.authState = currentReady.copy(bootstrap = brokenBootstrap)

        // Read-only GET status query must NOT be blocked by missing new-sale authority
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals(1, postCount) // ZERO new POSTs
        assertEquals(1, getCount) // Exactly 1 GET query

        val draft = vm.uiState.value.saleDraft
        assertFalse("Hold must be released on matching digest and confirmed payment", draft.isPaymentUnverifiedHold)
        assertFalse(draft.isSubmitting)
        assertNull(draft.unverifiedReceipt)
        assertNotNull("Confirmed receipt must be present", draft.receipt)
        assertEquals("INV-HOLD-AUTHDISABLED-1", draft.receipt?.invoiceNumber)
        assertEquals("RCT-AUTH-RECOVERED-01", draft.receipt?.paymentReceiptNumber)
        assertEquals(expectedDigest, draft.receipt?.payloadDigest?.value)
        assertTrue("Basket lines must be cleared", draft.lines.isEmpty())
        assertNull(draft.preparedIntent)
    }

    @Test
    fun testPaymentUnverifiedHold_checkPaymentStatus_wrongSessionScope_refusesZeroHttpPreservesHold() = runTest(testDispatcher) {
        var httpCallCount = 0

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                httpCallCount++
                if (request.method == HttpMethod.POST) {
                    val parsedBody = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val clientEventId = parsedBody.properties["client_event_id"]?.asString() ?: "00000000-0000-0000-0000-000000000000"
                    val unverifiedJson = """
                        {
                            "client_event_id": "$clientEventId",
                            "status": "posted",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5NV1",
                            "invoice_number": "INV-HOLD-WRONGSCOPE-1",
                            "payment_id": null,
                            "payment_receipt_number": null,
                            "grand_total": "20.00",
                            "amount_paid": "0.00",
                            "change_returned": "0.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    """.trimIndent()
                    return TransportResponse(201, emptyMap(), unverifiedJson)
                }
                throw IllegalStateException("Unexpected request: ${request.method} ${request.path}")
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = defaultRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = defaultRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val originalIntent = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(originalIntent)
        assertEquals(1, httpCallCount) // initial POST only

        val ready = fakeCoordinator.authState as StaffAuthState.ReadReady

        // 1. Wrong company ID -> zero HTTP, preserves hold
        val wrongCompany = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5999")
        fakeCoordinator.authState = ready.copy(session = ready.session.copy(selectedCompanyId = wrongCompany))
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals("No HTTP calls allowed on company mismatch", 1, httpCallCount)
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertEquals(originalIntent, vm.uiState.value.saleDraft.preparedIntent)
        assertTrue(vm.uiState.value.saleDraft.submissionError?.contains("Active session scope does not match retained sale intent scope") == true)

        // 2. Wrong actor ID -> zero HTTP, preserves hold
        val wrongActor = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5888")
        fakeCoordinator.authState = ready.copy(session = ready.session.copy(actorUlid = wrongActor))
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals("No HTTP calls allowed on actor mismatch", 1, httpCallCount)
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertEquals(originalIntent, vm.uiState.value.saleDraft.preparedIntent)

        // 3. Wrong device ID -> zero HTTP, preserves hold
        val wrongDevice = ClientUuid.random()
        fakeCoordinator.authState = ready.copy(session = ready.session.copy(deviceUuid = wrongDevice))
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals("No HTTP calls allowed on device mismatch", 1, httpCallCount)
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertEquals(originalIntent, vm.uiState.value.saleDraft.preparedIntent)

        // 4. Stale generation -> zero HTTP, preserves hold
        fakeCoordinator.authState = ready
        fakeCoordinator.currentGeneration = 99L
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals("No HTTP calls allowed on generation mismatch", 1, httpCallCount)
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertEquals(originalIntent, vm.uiState.value.saleDraft.preparedIntent)
    }

    @Test
    fun testDurableRecovery_writeFailure_zeroPost() = runTest(testDispatcher) {
        var postCount = 0
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    postCount++
                }
                return TransportResponse(201, emptyMap(), "{}")
            }
        }

        val failingRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore(throwOnSave = true)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = failingRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = failingRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm.submitOnlineCashSale()
        advanceUntilIdle()

        // Write failure MUST fail-closed with ZERO network POST
        assertEquals("Zero HTTP POST calls permitted when recovery persistence fails", 0, postCount)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertNotNull(vm.uiState.value.saleDraft.submissionError)
        assertTrue(vm.uiState.value.saleDraft.submissionError!!.contains("storage error", ignoreCase = true) ||
                vm.uiState.value.saleDraft.submissionError!!.contains("write failure", ignoreCase = true))
    }

    @Test
    fun testDurableRecovery_processRecreate_lostAck_recoversHoldViaGetOnly() = runTest(testDispatcher) {
        val sharedRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore()
        var postCount = 0
        var getStatusCount = 0
        val capturedEventId = AtomicReference<String>()
        val capturedDigest = AtomicReference<String>()

        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    postCount++
                    val obj = com.example.data.mobile.contract.StrictJsonParser.parse(request.body!!).asObject()
                    val eventId = obj.require("client_event_id").asString()
                    capturedEventId.set(eventId)
                    // Return unverified payment (posted without payment reference) to simulate lost-ack hold
                    val unverifiedJson = """
                        {
                            "client_event_id": "$eventId",
                            "status": "posted",
                            "official_invoice": {
                                "company_id": "${companyUlid.value}",
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5999",
                                "invoice_number": "INV-UNVERIFIED-1",
                                "payment_id": null,
                                "payment_receipt_number": null,
                                "grand_total": "20.00",
                                "posted_at": "2026-11-15T12:00:00Z"
                            }
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), unverifiedJson)
                } else if (request.method == HttpMethod.GET) {
                    getStatusCount++
                    val eventId = capturedEventId.get() ?: "00000000-0000-0000-0000-000000000000"
                    val digest = capturedDigest.get() ?: ""
                    val statusJson = """
                        {
                            "client_event_id": "$eventId",
                            "company_id": "${companyUlid.value}",
                            "status": "posted",
                            "payload_digest": "$digest",
                            "official_invoice": {
                                "company_id": "${companyUlid.value}",
                                "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5999",
                                "invoice_number": "INV-CONFIRMED-1",
                                "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5888",
                                "payment_receipt_number": "REC-9999",
                                "grand_total": "20.00",
                                "posted_at": "2026-11-15T12:05:00Z"
                            }
                        }
                    """.trimIndent()
                    return TransportResponse(200, emptyMap(), statusJson)
                }
                throw IllegalStateException("Unexpected request")
            }
        }

        fun createVm(): StaffWorkspaceViewModel {
            return StaffWorkspaceViewModel(
                coordinator = fakeCoordinator,
                configuredOrigin = "https://small-trader.example.com",
                ioDispatcher = testDispatcher,
                onlineCashEnabled = true,
                currencyMinorUnitsOverride = 2,
                recoveryStore = sharedRecoveryStore,
                orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                    val client = AndroidV2OnlineCashClient(
                        transport = testTransport,
                        baseOrigin = "https://small-trader.example.com"
                    )
                    OnlineCashSaleOrchestrator(
                        client = client,
                        tokenProvider = tokenProvider,
                        scopeGenerationProvider = scopeGenProvider,
                        onAuthDeniedLockScope = lockHook,
                        activeScopeSupplier = scopeSupplier,
                        recoveryStore = sharedRecoveryStore
                    )
                }
            )
        }

        // 1. Process 1: Submit sale, enters unverified hold
        val vm1 = createVm()
        advanceUntilIdle()

        vm1.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        vm1.submitOnlineCashSale()
        advanceUntilIdle()

        assertEquals(1, postCount)
        assertTrue(vm1.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNotNull(vm1.uiState.value.saleDraft.preparedIntent)
        capturedDigest.set(vm1.uiState.value.saleDraft.preparedIntent!!.expectedNormativeProfileDigest.value)

        // Durable store has pending record persisted BEFORE post
        val stored = sharedRecoveryStore.getPendingSale(companyUlid, actorUlid, deviceUuid, "https://small-trader.example.com")
        assertNotNull(stored)
        assertEquals(capturedEventId.get(), stored!!.clientEventId.value)

        // 2. Process Recreation: Simulate app killed / new VM instantiated
        fakeCoordinator.currentGeneration = 5L // session generation advanced across restart
        val vm2 = createVm()
        advanceUntilIdle()

        // Startup restores same-identity HOLD, rebinding to new generation 5L
        assertTrue(vm2.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNotNull(vm2.uiState.value.saleDraft.preparedIntent)
        assertEquals(5L, vm2.uiState.value.saleDraft.preparedIntent!!.scopeGeneration)
        assertEquals(stored.clientEventId, vm2.uiState.value.saleDraft.preparedIntent!!.clientEventId)

        // New sales are blocked while in HOLD
        val product = createSampleProduct()
        vm2.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        // Basket modifications are blocked
        assertTrue(vm2.uiState.value.saleDraft.lines.isEmpty())

        // 3. Status check recovers confirmed receipt via GET-ONLY (ZERO additional POSTs)
        vm2.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        assertEquals("No additional POST calls allowed during status check", 1, postCount)
        assertEquals("GET status check must be invoked", 1, getStatusCount)
        assertFalse(vm2.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNotNull(vm2.uiState.value.saleDraft.receipt)
        assertEquals("REC-9999", vm2.uiState.value.saleDraft.receipt!!.paymentReceiptNumber)

        // Durable recovery record cleared from store on ConfirmedFinancialSuccess
        assertNull(sharedRecoveryStore.getPendingSale(companyUlid, actorUlid, deviceUuid, "https://small-trader.example.com"))
    }

    @Test
    fun testDurableRecovery_companySwitch_isolation() = runTest(testDispatcher) {
        val sharedRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore()
        val companyA = companyUlid
        val companyB = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5888")

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = sharedRecoveryStore
        )
        advanceUntilIdle()

        // Setup pending record for Company A
        val eventA = ClientUuid.random()
        val draft = StaffOnlineSaleDraft(
            lines = listOf(
                StaffOnlineSaleDraftLine(
                    product = createSampleProduct(),
                    productUnitId = unitUlid,
                    conversionToBase = NonnegativeDecimalQuantity.of(1),
                    quantity = PositiveDecimalQuantity.of(1),
                    unitPrice = MoneyAmount("20.000000"),
                    taxRateId = null,
                    taxRate = TaxPercentage.ZERO,
                    taxInclusive = true
                )
            )
        )
        val authorityA = StaffOnlineSaleAuthorityResolution.resolve(
            (fakeCoordinator.authState as StaffAuthState.ReadReady).bootstrap,
            (fakeCoordinator.authState as StaffAuthState.ReadReady).session,
            currencyMinorUnitsOverride = 2
        )
        val intentA = StaffOnlineSaleRequestBuilder.prepareIntent(draft, authorityA, fakeCoordinator.currentGeneration, eventA)

        val recordA = DurableOnlineSaleRecoveryRecord(
            clientEventId = eventA,
            expectedNormativeProfileDigest = intentA.expectedNormativeProfileDigest,
            companyId = companyA,
            actorId = actorUlid,
            deviceId = deviceUuid,
            baseOrigin = "https://small-trader.example.com",
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("20.00"),
            rawWireBytes = intentA.rawWireBytes,
            createdAt = "2026-11-15T12:00:00Z"
        )
        sharedRecoveryStore.savePendingSale(recordA)

        // Trigger load for Company A -> restores HOLD
        val ready = fakeCoordinator.authState as StaffAuthState.ReadReady
        fakeCoordinator.authState = ready.copy(session = ready.session.copy(selectedCompanyId = companyA))
        vm.selectTab(StaffWorkspaceTab.CASH_SALE)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertEquals(eventA, vm.uiState.value.saleDraft.preparedIntent?.clientEventId)

        // Switch to Company B
        val readyB = ready.copy(
            session = ready.session.copy(selectedCompanyId = companyB),
            bootstrap = ready.bootstrap.copy(companyId = companyB)
        )
        fakeCoordinator.authState = readyB
        vm.switchCompany(companyB.value)
        advanceUntilIdle()

        // Company B MUST NOT see Company A's pending hold!
        assertFalse(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNull(vm.uiState.value.saleDraft.preparedIntent)

        // Switch back to Company A
        fakeCoordinator.authState = ready.copy(session = ready.session.copy(selectedCompanyId = companyA))
        vm.switchCompany(companyA.value)
        advanceUntilIdle()

        // Company A pending HOLD restored cleanly!
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertEquals(eventA, vm.uiState.value.saleDraft.preparedIntent?.clientEventId)
    }

    @Test
    fun testDurableRecovery_logoutAndAuthLock_preservesRecord() = runTest(testDispatcher) {
        val sharedRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore()
        val eventId = ClientUuid.random()

        val draft = StaffOnlineSaleDraft(
            lines = listOf(
                StaffOnlineSaleDraftLine(
                    product = createSampleProduct(),
                    productUnitId = unitUlid,
                    conversionToBase = NonnegativeDecimalQuantity.of(1),
                    quantity = PositiveDecimalQuantity.of(1),
                    unitPrice = MoneyAmount("20.000000"),
                    taxRateId = null,
                    taxRate = TaxPercentage.ZERO,
                    taxInclusive = true
                )
            )
        )
        val authority = StaffOnlineSaleAuthorityResolution.resolve(
            (fakeCoordinator.authState as StaffAuthState.ReadReady).bootstrap,
            (fakeCoordinator.authState as StaffAuthState.ReadReady).session,
            currencyMinorUnitsOverride = 2
        )
        val intent = StaffOnlineSaleRequestBuilder.prepareIntent(draft, authority, 1L, eventId)

        val record = DurableOnlineSaleRecoveryRecord(
            clientEventId = eventId,
            expectedNormativeProfileDigest = intent.expectedNormativeProfileDigest,
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            baseOrigin = "https://small-trader.example.com",
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("20.00"),
            rawWireBytes = intent.rawWireBytes,
            createdAt = "2026-11-15T12:00:00Z"
        )
        sharedRecoveryStore.savePendingSale(record)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = sharedRecoveryStore
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)

        // Logout
        vm.logout()
        advanceUntilIdle()

        // In-memory UI state is cleared
        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)

        // Record in durable storage remains preserved!
        val preserved = sharedRecoveryStore.getPendingSale(companyUlid, actorUlid, deviceUuid, "https://small-trader.example.com")
        assertNotNull(preserved)
        assertEquals(eventId, preserved!!.clientEventId)
    }

    @Test
    fun testDurableRecovery_clearConfirmed_doesNotEraseMismatchedEvent() {
        val sharedRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore()
        val eventA = ClientUuid.random()
        val eventB = ClientUuid.random()

        val recordA = DurableOnlineSaleRecoveryRecord(
            clientEventId = eventA,
            expectedNormativeProfileDigest = PayloadDigest.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            baseOrigin = "https://small-trader.example.com",
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("10.00"),
            rawWireBytes = byteArrayOf(1, 2, 3),
            createdAt = "2026-11-15T12:00:00Z"
        )
        sharedRecoveryStore.savePendingSale(recordA)

        // Clearing non-matching eventB does not erase recordA
        sharedRecoveryStore.clearPendingSale(eventB)

        assertNotNull(sharedRecoveryStore.getPendingSale(companyUlid, actorUlid, deviceUuid, "https://small-trader.example.com"))
        assertEquals(1, sharedRecoveryStore.listAllRecords().size)
    }

    @Test
    fun testDurableRecovery_restoredHold_postedWrongDigest_staysHold() = runTest(testDispatcher) {
        val sharedRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore()
        val eventId = ClientUuid.random()

        val draft = StaffOnlineSaleDraft(
            lines = listOf(
                StaffOnlineSaleDraftLine(
                    product = createSampleProduct(),
                    productUnitId = unitUlid,
                    conversionToBase = NonnegativeDecimalQuantity.of(1),
                    quantity = PositiveDecimalQuantity.of(1),
                    unitPrice = MoneyAmount("20.000000"),
                    taxRateId = null,
                    taxRate = TaxPercentage.ZERO,
                    taxInclusive = true
                )
            )
        )
        val authority = StaffOnlineSaleAuthorityResolution.resolve(
            (fakeCoordinator.authState as StaffAuthState.ReadReady).bootstrap,
            (fakeCoordinator.authState as StaffAuthState.ReadReady).session,
            currencyMinorUnitsOverride = 2
        )
        val intent = StaffOnlineSaleRequestBuilder.prepareIntent(draft, authority, 1L, eventId)

        val record = DurableOnlineSaleRecoveryRecord(
            clientEventId = eventId,
            expectedNormativeProfileDigest = intent.expectedNormativeProfileDigest,
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            baseOrigin = "https://small-trader.example.com",
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("20.00"),
            rawWireBytes = intent.rawWireBytes,
            createdAt = "2026-11-15T12:00:00Z"
        )
        sharedRecoveryStore.savePendingSale(record)

        val wrongDigest = PayloadDigest.parse("sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        val testTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                // Server returns official invoice with mismatched digest
                val responseJson = """
                    {
                        "client_event_id": "${eventId.value}",
                        "company_id": "${companyUlid.value}",
                        "status": "posted",
                        "payload_digest": "${wrongDigest.value}",
                        "official_invoice": {
                            "company_id": "${companyUlid.value}",
                            "invoice_id": "01ARZ3NDEKTSV4RRFFQ69G5999",
                            "invoice_number": "INV-WRONG-DIGEST",
                            "payment_id": "01ARZ3NDEKTSV4RRFFQ69G5888",
                            "payment_receipt_number": "REC-WRONG",
                            "grand_total": "20.00",
                            "posted_at": "2026-11-15T12:00:00Z"
                        }
                    }
                """.trimIndent()
                return TransportResponse(200, emptyMap(), responseJson)
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = sharedRecoveryStore,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val client = AndroidV2OnlineCashClient(
                    transport = testTransport,
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = client,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier,
                    recoveryStore = sharedRecoveryStore
                )
            }
        )
        advanceUntilIdle()

        // Check status with mismatched digest
        vm.checkOnlineCashSaleStatus()
        advanceUntilIdle()

        // Mismatched digest MUST preserve HOLD and keep record in recovery store
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertNotNull(vm.uiState.value.saleDraft.submissionError)
        assertTrue(vm.uiState.value.saleDraft.submissionError!!.contains("mismatch", ignoreCase = true))
        assertNotNull(sharedRecoveryStore.getPendingSale(companyUlid, actorUlid, deviceUuid, "https://small-trader.example.com"))
    }

    @Test
    fun testDurableRecovery_loadPendingFailure_locksUiWithBlockingHold() = runTest(testDispatcher) {
        val failingRecoveryStore = InMemoryDurableOnlineSaleRecoveryStore(throwOnGet = true)
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = failingRecoveryStore
        )
        advanceUntilIdle()

        // Read/load failure MUST lock the UI in blocking payment hold
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertFalse(vm.uiState.value.saleDraft.isSubmitting)
        assertNotNull(vm.uiState.value.saleDraft.submissionError)
        assertTrue(vm.uiState.value.saleDraft.submissionError!!.contains("blocked", ignoreCase = true) ||
                vm.uiState.value.saleDraft.submissionError!!.contains("failed", ignoreCase = true))

        // Adding product or submitting new sale MUST be rejected
        vm.addProductToSaleDraft(createSampleProduct(), quantity = PositiveDecimalQuantity.of(1))
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
    }

    @Test
    fun testDurableRecovery_missingStore_onlineCashActive_locksUiWithBlockingHold() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = null,
            allowRecoveryBypassForTesting = false
        )
        advanceUntilIdle()

        // Missing store with online cash enabled MUST lock UI in blocking hold
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNotNull(vm.uiState.value.saleDraft.submissionError)
        assertTrue(vm.uiState.value.saleDraft.submissionError!!.contains("unavailable", ignoreCase = true))

        // Product addition is blocked
        vm.addProductToSaleDraft(createSampleProduct(), quantity = PositiveDecimalQuantity.of(1))
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
    }

    @Test
    fun testDurableRecovery_missingStore_submitBlockedWithHold() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = null,
            allowRecoveryBypassForTesting = true // Allow startup without hold
        )
        advanceUntilIdle()

        // Add product succeeds when bypass was enabled at startup
        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        // When orchestrator requires recovery store and store is null, dispatch returns StorageFailure
        val draft = vm.uiState.value.saleDraft
        val ready = fakeCoordinator.authState as StaffAuthState.ReadReady
        val authority = StaffOnlineSaleAuthorityResolution.resolve(ready.bootstrap, ready.session, 2)
        val intent = StaffOnlineSaleRequestBuilder.prepareIntent(draft, authority, 1L, ClientUuid.random())

        // Explicit orchestrator dispatch with requireRecoveryStore = true fails closed
        val strictOrchestrator = OnlineCashSaleOrchestrator(
            client = AndroidV2OnlineCashClient(
                transport = object : ReadClientTransport {
                    override fun execute(request: TransportRequest): TransportResponse {
                        throw AssertionError("HTTP POST must not be called")
                    }
                },
                baseOrigin = "https://small-trader.example.com"
            ),
            tokenProvider = { "token" },
            recoveryStore = null,
            requireRecoveryStore = true
        )
        val result = strictOrchestrator.dispatchOnlineCashSale(intent, companyUlid, deviceUuid, "USD")
        assertTrue(result is OnlineCashOrchestrationResult.StorageFailure)
    }

    @Test
    fun testCurrentGenerationReauth_rebindsPreparedIntentToNewGeneration() = runTest(testDispatcher) {
        val sharedStore = InMemoryDurableOnlineSaleRecoveryStore()
        val eventId = ClientUuid.random()
        val rawWireJson = """{"client_event_id":"$eventId","currency":"USD","customer_id":"${walkInUlid.value}","device_id":"${deviceUuid.value}","lines":[{"conversion_to_base":"1","product_id":"${productUlid.value}","product_unit_id":"${unitUlid.value}","quantity":"1","tax_inclusive":true,"tax_rate":"0","tax_rate_id":null,"unit_price":"20.000000"}],"payment":{"amount":"20.00","cash_account_id":"${cashAccountUlid.value}","payment_method":"cash","settlement_mode":"full"},"warehouse_id":"${warehouseUlid.value}"}"""
        val rawWireBytes = rawWireJson.toByteArray(Charsets.UTF_8)
        val context = OnlineSaleContext(actorId = actorUlid, companyId = companyUlid, currencyMinorUnits = 2)
        val initialIntent = PreparedOnlineSaleIntent.prepare(context = context, rawWireBytes = rawWireBytes, scopeGeneration = 1L)
        val initialRecord = DurableOnlineSaleRecoveryRecord(
            clientEventId = eventId,
            expectedNormativeProfileDigest = initialIntent.expectedNormativeProfileDigest,
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            baseOrigin = "https://small-trader.example.com",
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("20.00"),
            rawWireBytes = rawWireBytes,
            createdAt = "2026-11-15T12:00:00Z"
        )
        sharedStore.savePendingSale(initialRecord)

        fakeCoordinator.currentGeneration = 10L
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = sharedStore
        )
        advanceUntilIdle()

        // Reconstituted prepared intent MUST be rebound to active generation 10L
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        val rebound = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(rebound)
        assertEquals(10L, rebound!!.scopeGeneration)
        assertEquals(eventId, rebound.clientEventId)

        // Session lock/reauth: generation advances to 11L, retryLoadDurableRecovery rebinds to 11L
        fakeCoordinator.currentGeneration = 11L
        vm.retryLoadDurableRecovery()
        advanceUntilIdle()

        val updated = vm.uiState.value.saleDraft.preparedIntent
        assertNotNull(updated)
        assertEquals(11L, updated!!.scopeGeneration)
    }

    @Test
    fun testRetryLoadDurableRecovery_unusableStorage_thenRecovers() = runTest(testDispatcher) {
        val failingStore = InMemoryDurableOnlineSaleRecoveryStore(throwOnGet = true)
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = failingStore
        )
        advanceUntilIdle()

        // Initially in blocking hold with error and null intent
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNull(vm.uiState.value.saleDraft.preparedIntent)
        assertTrue(vm.uiState.value.saleDraft.submissionError!!.contains("storage error", ignoreCase = true) ||
                vm.uiState.value.saleDraft.submissionError!!.contains("read failure", ignoreCase = true))

        // Storage recovers and now contains a pending sale
        failingStore.throwOnGet = false
        val eventId = ClientUuid.random()
        val rawWireJson = """{"client_event_id":"$eventId","currency":"USD","customer_id":"${walkInUlid.value}","device_id":"${deviceUuid.value}","lines":[{"conversion_to_base":"1","product_id":"${productUlid.value}","product_unit_id":"${unitUlid.value}","quantity":"1","tax_inclusive":true,"tax_rate":"0","tax_rate_id":null,"unit_price":"20.000000"}],"payment":{"amount":"20.00","cash_account_id":"${cashAccountUlid.value}","payment_method":"cash","settlement_mode":"full"},"warehouse_id":"${warehouseUlid.value}"}"""
        val rawWireBytes = rawWireJson.toByteArray(Charsets.UTF_8)
        val context = OnlineSaleContext(actorId = actorUlid, companyId = companyUlid, currencyMinorUnits = 2)
        val intent = PreparedOnlineSaleIntent.prepare(context = context, rawWireBytes = rawWireBytes, scopeGeneration = 1L)
        val record = DurableOnlineSaleRecoveryRecord(
            clientEventId = eventId,
            expectedNormativeProfileDigest = intent.expectedNormativeProfileDigest,
            companyId = companyUlid,
            actorId = actorUlid,
            deviceId = deviceUuid,
            baseOrigin = "https://small-trader.example.com",
            currency = "USD",
            currencyMinorUnits = 2,
            amount = MoneyAmount("20.00"),
            rawWireBytes = rawWireBytes,
            createdAt = "2026-11-15T12:00:00Z"
        )
        failingStore.savePendingSale(record)

        // Read-only retry
        vm.retryLoadDurableRecovery()
        advanceUntilIdle()

        // Restores prepared intent under active generation
        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNotNull(vm.uiState.value.saleDraft.preparedIntent)
        assertEquals(eventId, vm.uiState.value.saleDraft.preparedIntent!!.clientEventId)
    }

    @Test
    fun testRetryLoadDurableRecovery_cleanStore_clearsHold() = runTest(testDispatcher) {
        val failingStore = InMemoryDurableOnlineSaleRecoveryStore(throwOnGet = true)
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            recoveryStore = failingStore
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)

        // Storage recovers and is clean (zero pending records)
        failingStore.throwOnGet = false
        vm.retryLoadDurableRecovery()
        advanceUntilIdle()

        // Hold is cleared, error is reset, normal draft actions permitted
        assertFalse(vm.uiState.value.saleDraft.isPaymentUnverifiedHold)
        assertNull(vm.uiState.value.saleDraft.submissionError)
        vm.addProductToSaleDraft(createSampleProduct(unitPrice = "20.000000"), quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
    }
}

/**
 * Minimal hermetic fake coordinator for staff sale testing.
 */
private class TestStaffCoordinator(
    val companyId: ServerUlid,
    val actorId: ServerUlid,
    val deviceId: ClientUuid,
    val warehouseId: ServerUlid,
    val cashAccountId: ServerUlid,
    val walkInId: ServerUlid,
    val taxes: List<BootstrapTax> = emptyList(),
    val currencyMinorUnits: Int? = null
) : StaffSessionCoordinator {

    override var currentGeneration: Long = 1L
    override val installationId: ClientUuid = deviceId

    private val sessionRecord = StaffSessionRecord(
        token = "test_bearer_token_abc123",
        actorUlid = actorId,
        deviceUuid = deviceId,
        passwordEpoch = "epoch_1",
        expiresAt = "2026-11-15T23:59:59Z",
        selectedCompanyId = companyId,
        roleLabel = "Cashier",
        actorName = "Test Actor",
        actorEmail = "actor@example.com",
        createdAt = "2026-11-15T00:00:00Z"
    )

    private val bootstrapResponse = BootstrapResponse(
        companyId = companyId,
        companyName = "Test Company",
        baseCurrency = "USD",
        walkInCustomerId = walkInId,
        staff = BootstrapStaff(actorId, "Test Actor", "actor@example.com", listOf("cashier")),
        deviceLease = null,
        defaultWarehouse = DefaultWarehouse(warehouseId, "WH-01", "Default Warehouse"),
        approvedCashAccount = ApprovedCashAccount(cashAccountId, "CASH-01", "Main Cash", "USD"),
        units = emptyList(),
        taxes = taxes,
        currencyMinorUnits = currencyMinorUnits,
        offlinePolicy = OfflinePolicyDisabled(),
        catalogSnapshotVersion = "v1"
    )

    override var authState: StaffAuthState = StaffAuthState.ReadReady(
        session = sessionRecord,
        bootstrap = bootstrapResponse
    )

    override fun isScopeActive(generation: Long, companyId: ServerUlid): Boolean {
        return generation == currentGeneration && this.companyId == companyId
    }

    override fun restoreSession(): CoordinatorResult<StaffAuthState> {
        return CoordinatorResult.Success(authState, currentGeneration, companyId, actorId)
    }

    override fun requestChallenge(appVersion: String): CoordinatorResult<AuthChallengeResponse> {
        return CoordinatorResult.Success(
            AuthChallengeResponse(ClientUuid.random(), "nonce1234567890123456789012345678", "2026-11-15T12:00:00Z"),
            currentGeneration
        )
    }

    override fun login(email: String, password: String, deviceName: String, mfaCode: String?, recoveryCode: String?): CoordinatorResult<StaffAuthState> {
        return CoordinatorResult.Success(authState, currentGeneration, companyId, actorId)
    }

    override fun retryLoginWithMfa(email: String, password: String, deviceName: String, mfaCode: String?, recoveryCode: String?): CoordinatorResult<StaffAuthState> {
        return CoordinatorResult.Success(authState, currentGeneration, companyId, actorId)
    }

    override fun selectCompany(companyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        return CoordinatorResult.Success(authState, currentGeneration, companyId, actorId)
    }

    override fun switchCompany(newCompanyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        return CoordinatorResult.Success(authState, currentGeneration, newCompanyId, actorId)
    }

    override fun logout(): LogoutResult {
        currentGeneration++
        authState = StaffAuthState.LoggedOut
        return LogoutResult.Success(RemoteRevocationStatus.Revoked)
    }

    override fun getProducts(query: PaginationQuery): CoordinatorResult<ProductCatalogResponse> {
        return CoordinatorResult.Success(
            ProductCatalogResponse(
                companyId = companyId,
                catalogSnapshotVersion = "snap-1",
                generatedAt = "2026-11-15T12:00:00Z",
                nextCursor = null,
                hasMore = false,
                products = emptyList()
            ),
            currentGeneration
        )
    }

    override fun getCustomers(query: PaginationQuery): CoordinatorResult<CustomerDirectoryResponse> {
        return CoordinatorResult.Success(
            CustomerDirectoryResponse(
                companyId = companyId,
                generatedAt = "2026-11-15T12:00:00Z",
                nextCursor = null,
                hasMore = false,
                customers = emptyList()
            ),
            currentGeneration
        )
    }
}

/**
 * Hermetic in-memory implementation of DurableOnlineSaleRecoveryStore for testing.
 */
private class InMemoryDurableOnlineSaleRecoveryStore(
    var throwOnSave: Boolean = false,
    var throwOnGet: Boolean = false
) : DurableOnlineSaleRecoveryStore {
    private val records = mutableMapOf<ClientUuid, DurableOnlineSaleRecoveryRecord>()

    override fun savePendingSale(record: DurableOnlineSaleRecoveryRecord) {
        if (throwOnSave) {
            throw OnlineSaleRecoveryException("Simulated disk write failure")
        }
        val canonical = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(record.baseOrigin)
        val normalized = if (record.baseOrigin == canonical) record else record.copy(baseOrigin = canonical)
        val existing = records[normalized.clientEventId]
        if (existing != null) {
            val isSameScope = existing.companyId == normalized.companyId &&
                    existing.actorId == normalized.actorId &&
                    existing.deviceId == normalized.deviceId &&
                    existing.baseOrigin == normalized.baseOrigin
            if (!isSameScope) {
                throw OnlineSaleRecoveryException("Global event UUID collision across identity scopes")
            }
            if (existing.expectedNormativeProfileDigest != normalized.expectedNormativeProfileDigest ||
                !existing.rawWireBytes.contentEquals(normalized.rawWireBytes)
            ) {
                throw OnlineSaleRecoveryException("Cannot overwrite pending event with different payload bytes")
            }
            return
        }
        records[normalized.clientEventId] = normalized
    }

    override fun getPendingSale(
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        baseOrigin: String
    ): DurableOnlineSaleRecoveryRecord? {
        if (throwOnGet) {
            throw OnlineSaleRecoveryException("Simulated disk read failure")
        }
        val canonical = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(baseOrigin)
        return records.values.firstOrNull {
            it.companyId == companyId && it.actorId == actorId && it.deviceId == deviceId && it.baseOrigin == canonical
        }
    }

    override fun clearPendingSale(
        clientEventId: ClientUuid,
        companyId: ServerUlid,
        actorId: ServerUlid,
        deviceId: ClientUuid,
        baseOrigin: String
    ) {
        val canonical = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(baseOrigin)
        val existing = records[clientEventId]
        if (existing != null &&
            existing.companyId == companyId &&
            existing.actorId == actorId &&
            existing.deviceId == deviceId &&
            existing.baseOrigin == canonical
        ) {
            records.remove(clientEventId)
        }
    }

    override fun clearPendingSale(clientEventId: ClientUuid) {
        records.remove(clientEventId)
    }

    override fun listAllRecords(): List<DurableOnlineSaleRecoveryRecord> {
        return records.values.toList()
    }
}

