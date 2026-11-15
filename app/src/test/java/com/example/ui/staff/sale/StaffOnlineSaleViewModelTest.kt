package com.example.ui.staff.sale

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.onlinecash.AndroidV2OnlineCashClient
import com.example.data.mobile.onlinecash.OnlineCashOrchestrationResult
import com.example.data.mobile.onlinecash.OnlineCashSaleOrchestrator
import com.example.data.mobile.onlinecash.OnlineCashSaleReceipt
import com.example.data.mobile.onlinecash.OnlineSaleScope
import com.example.data.mobile.onlinecash.PreparedOnlineSaleIntent
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.AuthChallengeResponse
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.BootstrapStaff
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
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
            currencyMinorUnitsOverride = 2
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
        val testTransport = ReadClientTransport { req ->
            capturedRequests.add(req)
            val dummyCommitJson = """
                {
                    "invoice_id": "01ARZ3NDEKTSV4RRFFQ69GINV1",
                    "invoice_number": "INV-2026-001",
                    "payment_id": "01ARZ3NDEKTSV4RRFFQ69GPAY1",
                    "payment_receipt_number": "RCT-001",
                    "grand_total": "30.00",
                    "posted_at": "2026-11-15T12:00:00Z"
                }
            """.trimIndent()
            TransportResponse(201, emptyMap(), dummyCommitJson)
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
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
                    activeScopeSupplier = scopeSupplier
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
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                lockHookCaptured = lockHook
                val dummyClient = AndroidV2OnlineCashClient(
                    transport = { _ -> throw UnsupportedOperationException() },
                    baseOrigin = "https://small-trader.example.com"
                )
                OnlineCashSaleOrchestrator(
                    client = dummyClient,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier
                )
            }
        )
        advanceUntilIdle()

        // Trigger lock hook with current generation
        assertNotNull(lockHookCaptured)
        val dummyError = ReadClientErrorResponse(
            error = "AUTH_DENIED",
            message = "Bearer token revoked",
            status = 401
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
            currencyMinorUnitsOverride = 2
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
        val testTransport = ReadClientTransport { req ->
            capturedRequests.add(req)
            throw IOException("Simulated network timeout during socket transport")
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
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
                    activeScopeSupplier = scopeSupplier
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
        val testTransport = ReadClientTransport { _ ->
            throw IOException("Simulated gateway timeout")
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
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
                    activeScopeSupplier = scopeSupplier
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
            id = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5OTH1"),
            sku = "SKU-OTHER",
            barcode = null,
            name = "Other Product",
            unitPrice = MoneyAmount.fromNonnegativeString("10.000000"),
            taxRate = null,
            taxRateId = null,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("10.000000"),
            baseUnit = ProductBaseUnit(unitUlid, "PCS", "Pieces"),
            defaultUnit = ProductDefaultUnit(unitUlid, ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5PIV1"), PositiveDecimalQuantity.of(1), MoneyAmount.fromNonnegativeString("10.000000")),
            units = emptyList()
        )
        vm.addProductToSaleDraft(otherProduct, quantity = PositiveDecimalQuantity.of(1), explicitNoTax = true)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        vm.updateDraftLineQuantity(initialLine.lineId, PositiveDecimalQuantity.of(10))
        assertEquals("1", vm.uiState.value.saleDraft.lines.first().quantity.value.toPlainString())

        vm.removeDraftLine(initialLine.lineId)
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)

        vm.selectCustomerForSale(CustomerDirectoryItem(ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5CST2"), "C2", "Customer 2", null, false))
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

        val testTransport = ReadClientTransport { _ ->
            TransportResponse(401, emptyMap(), """{"error":"TOKEN_EXPIRED","message":"Staff session token expired","status":401}""")
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            authDenialListener = authDenialListener,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
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
                    activeScopeSupplier = scopeSupplier
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
        val testTransport = ReadClientTransport { _ ->
            fakeCoordinator.currentGeneration = 999L
            TransportResponse(401, emptyMap(), """{"error":"STALE_TOKEN","message":"Stale token from old generation","status":401}""")
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            authDenialListener = authDenialListener,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
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
                    activeScopeSupplier = scopeSupplier
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
        assertNull(denialRef.get())
        assertTrue(vm.uiState.value.saleDraft.submissionError?.contains("Stale") == true)
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
    val walkInId: ServerUlid
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
        taxes = emptyList(),
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
        return CoordinatorResult.Success(ProductCatalogResponse(companyId, emptyList(), null, false), currentGeneration)
    }

    override fun getCustomers(query: PaginationQuery): CoordinatorResult<CustomerDirectoryResponse> {
        return CoordinatorResult.Success(CustomerDirectoryResponse(companyId, emptyList(), null, false), currentGeneration)
    }
}
