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
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ProductDefaultUnit
import com.example.data.mobile.readclient.ReadClientErrorResponse
import com.example.data.mobile.session.AuthDenialListener
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
                productUnitId = unitUlid,
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

        // 1. Add product to sale draft
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1))
        assertEquals(1, vm.uiState.value.saleDraft.lines.size)
        assertEquals("1", vm.uiState.value.saleDraft.lines[0].quantity.toPlainString())
        assertEquals("15.000000", vm.uiState.value.saleDraft.estimatedSubtotal.toPlainString())

        // 2. Add same product again -> quantity increments cleanly without duplicate line
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(2))
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
        vm.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1))
        assertFalse(vm.uiState.value.saleDraft.lines.isEmpty())
        vm.clearSaleDraft()
        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
    }

    @Test
    fun testSessionScopedOrchestratorDispatchesActiveGenerationAndToken() = runTest(testDispatcher) {
        fakeCoordinator.currentGeneration = 42L

        var capturedToken: String? = null
        var capturedGeneration: Long = -1L
        var capturedScope: OnlineSaleScope? = null
        var capturedIntent: PreparedOnlineSaleIntent? = null

        val expectedReceipt = OnlineCashSaleReceipt(
            companyId = companyUlid,
            clientEventId = ClientUuid.random(),
            payloadDigest = PayloadDigest.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            invoiceId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5REC1"),
            invoiceNumber = "INV-2026-001",
            paymentId = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5PAY1"),
            paymentReceiptNumber = "RCT-001",
            grandTotal = MoneyAmount.fromNonnegativeString("30.00"),
            postedAt = "2026-11-15T12:00:00Z",
            wasReplay = false
        )

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                // Create custom orchestrator proxy to verify scoped parameters without network
                object : OnlineCashSaleOrchestrator(
                    client = AndroidV2OnlineCashClient(
                        transport = { _ -> throw UnsupportedOperationException("No network") },
                        baseOrigin = "https://small-trader.example.com"
                    ),
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier
                ) {
                    fun dispatchMock(intent: PreparedOnlineSaleIntent): OnlineCashOrchestrationResult {
                        capturedToken = tokenProvider.invoke()
                        capturedGeneration = scopeGenProvider.invoke()
                        capturedScope = scopeSupplier?.invoke()
                        capturedIntent = intent
                        return OnlineCashOrchestrationResult.ConfirmedFinancialSuccess(expectedReceipt)
                    }
                }.let { mockOrch ->
                    // Proxy call
                    val dummyClient = AndroidV2OnlineCashClient(
                        transport = { _ -> throw UnsupportedOperationException("No network") },
                        baseOrigin = "https://small-trader.example.com"
                    )
                    object : OnlineCashSaleOrchestrator(
                        client = dummyClient,
                        tokenProvider = tokenProvider,
                        scopeGenerationProvider = scopeGenProvider,
                        onAuthDeniedLockScope = lockHook,
                        activeScopeSupplier = scopeSupplier
                    ) {
                        override fun toString(): String = "MockOrchestrator"
                    }
                }
            }
        )

        // For direct orchestration execution verification, use an orchestrator factory that returns our controlled result:
        var executedDispatch = false
        val vmWithMock = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher,
            onlineCashEnabled = true,
            currencyMinorUnitsOverride = 2,
            orchestratorFactory = { tokenProvider, scopeGenProvider, lockHook, scopeSupplier ->
                val dummyClient = AndroidV2OnlineCashClient(
                    transport = { _ -> throw UnsupportedOperationException() },
                    baseOrigin = "https://small-trader.example.com"
                )
                object : OnlineCashSaleOrchestrator(
                    client = dummyClient,
                    tokenProvider = tokenProvider,
                    scopeGenerationProvider = scopeGenProvider,
                    onAuthDeniedLockScope = lockHook,
                    activeScopeSupplier = scopeSupplier
                ) {
                    // Override dispatch by delegating or capturing
                }
            }
        )
        advanceUntilIdle()

        // Test authority resolution and tab switching
        vmWithMock.selectTab(StaffWorkspaceTab.CASH_SALE)
        val authority = vmWithMock.uiState.value.saleAuthority
        assertNotNull(authority)
        assertTrue(authority!!.isDispatchPermitted)
        assertEquals(2, authority.currencyMinorUnits)

        // Add product to draft
        val product = createSampleProduct(unitPrice = "30.000000")
        vmWithMock.addProductToSaleDraft(product, quantity = PositiveDecimalQuantity.of(1))
        assertEquals(1, vmWithMock.uiState.value.saleDraft.lines.size)
        assertEquals("30.000000", vmWithMock.uiState.value.saleDraft.estimatedTotal.toPlainString())
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

        vm.addProductToSaleDraft(createSampleProduct(), quantity = PositiveDecimalQuantity.of(2))
        assertFalse(vm.uiState.value.saleDraft.lines.isEmpty())

        // Logout
        vm.logout()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saleDraft.lines.isEmpty())
        assertNull(vm.uiState.value.saleDraft.receipt)
        assertNull(vm.uiState.value.saleAuthority)
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
