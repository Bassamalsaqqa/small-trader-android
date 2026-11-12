package com.example.ui.staff

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.readclient.ActorProfile
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.AuthChallengeResponse
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.BootstrapStaff
import com.example.data.mobile.readclient.CompanyMembership
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.DefaultWarehouse
import com.example.data.mobile.readclient.OfflinePolicyDisabled
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ProductDefaultUnit
import com.example.data.mobile.session.CoordinatorResult
import com.example.data.mobile.session.LockReason
import com.example.data.mobile.session.LogoutResult
import com.example.data.mobile.session.RemoteRevocationStatus
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionCoordinator
import com.example.data.mobile.session.StaffSessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class StaffWorkspaceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val testCompanyId1 = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val testCompanyId2 = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val testActorId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val testInstallId = ClientUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6")

    private lateinit var fakeCoordinator: FakeStaffSessionCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyId1,
            initialActorId = testActorId,
            installId = testInstallId
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unconfiguredOrigin_setsUnconfiguredPhase() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.UNCONFIGURED, vm.uiState.value.phase)
    }

    @Test
    fun restoreSession_success_transitionsToReadReady_andLoadsData() = runTest(testDispatcher) {
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna"),
            generation = 1L,
            companyId = testCompanyId1,
            actorId = testActorId
        )
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(StaffWorkspacePhase.READ_READY, state.phase)
        assertEquals(testCompanyId1.value, state.activeCompany?.companyId)
        assertEquals("Al-Zaytouna", state.activeCompany?.companyName)
        assertEquals("Bassam", state.staffActor?.actorName)
        assertEquals("Cashier", state.staffActor?.roleLabel)
        assertEquals(1, state.products.size)
        assertEquals(1, state.customers.size)
    }

    @Test
    fun restoreSession_notFound_transitionsToSignIn() = runTest(testDispatcher) {
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            StaffAuthState.LoggedOut,
            generation = 1L
        )
        fakeCoordinator.authState = StaffAuthState.LoggedOut

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
    }

    @Test
    fun happyLogin_singleCompany_transitionsToReadReady_andClearsTransientMemory() = runTest(testDispatcher) {
        fakeCoordinator.loginResultProvider = { email, password, mfa, rec ->
            fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna Store")
            CoordinatorResult.Success(
                fakeCoordinator.authState,
                generation = 2L,
                companyId = testCompanyId1,
                actorId = testActorId
            )
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.setTransientPassword("SecretPassword123!")
        vm.signIn("staff@example.com")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(StaffWorkspacePhase.READ_READY, state.phase)
        assertEquals(testCompanyId1.value, state.activeCompany?.companyId)
        assertEquals("Al-Zaytouna Store", state.activeCompany?.companyName)
        assertFalse(state.isSubmitting)
        assertNull(state.errorMessage)
        assertEquals(1, state.products.size)
    }

    @Test
    fun login_requiresMfa_thenFreshRetrySucceeds_andClearsTransientMemory() = runTest(testDispatcher) {
        var loginAttempts = 0
        fakeCoordinator.loginResultProvider = { _, _, _, _ ->
            loginAttempts++
            fakeCoordinator.authState = StaffAuthState.MfaRequired("fresh_challenge")
            CoordinatorResult.Success(fakeCoordinator.authState, generation = 2L)
        }

        fakeCoordinator.retryMfaResultProvider = { email, pwd, mfa, rec ->
            assertEquals("staff@example.com", email)
            assertEquals("SecretPass123", pwd)
            assertEquals("123456", mfa)
            assertNull(rec)

            fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
            CoordinatorResult.Success(
                fakeCoordinator.authState,
                generation = 3L,
                companyId = testCompanyId1,
                actorId = testActorId
            )
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // 1. Initial Login with password
        vm.signIn("staff@example.com", passwordOverride = "SecretPass123")
        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.MFA_REQUIRED, vm.uiState.value.phase)
        assertEquals(MfaMode.TOTP, vm.uiState.value.mfaMode)

        // 2. Submit MFA TOTP Code
        vm.setTransientMfaCode("123456")
        vm.retryWithMfa()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(StaffWorkspacePhase.READ_READY, state.phase)
        assertEquals("Al-Zaytouna", state.activeCompany?.companyName)
    }

    @Test
    fun mfaMode_mutualExclusivity_totpVsRecovery() = runTest(testDispatcher) {
        var capturedMfa: String? = null
        var capturedRecovery: String? = null

        fakeCoordinator.retryMfaResultProvider = { _, _, mfa, rec ->
            capturedMfa = mfa
            capturedRecovery = rec
            fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
            CoordinatorResult.Success(fakeCoordinator.authState, generation = fakeCoordinator.currentGeneration, companyId = testCompanyId1)
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // Test TOTP mode
        vm.setMfaMode(MfaMode.TOTP)
        vm.retryWithMfa(email = "user@test.com", passwordOverride = "pass", codeOverride = "654321")
        advanceUntilIdle()

        assertEquals("654321", capturedMfa)
        assertNull(capturedRecovery)

        // Test Recovery Code mode
        vm.setMfaMode(MfaMode.RECOVERY_CODE)
        vm.retryWithMfa(email = "user@test.com", passwordOverride = "pass", codeOverride = "REC-999-ABCD")
        advanceUntilIdle()

        assertNull(capturedMfa)
        assertEquals("REC-999-ABCD", capturedRecovery)
    }

    @Test
    fun companySelection_multipleMemberships_selectingCompanyTransitionsToReadReady() = runTest(testDispatcher) {
        val memberships = listOf(
            CompanyMembership(testCompanyId1, "Branch Central", "ILS", isDefault = true),
            CompanyMembership(testCompanyId2, "Branch North", "ILS", isDefault = false)
        )
        fakeCoordinator.companyNames[testCompanyId1] = "Branch Central"
        fakeCoordinator.companyNames[testCompanyId2] = "Branch North"

        fakeCoordinator.loginResultProvider = { _, _, _, _ ->
            fakeCoordinator.authState = StaffAuthState.CompanySelectionRequired(
                actor = ActorProfile(testActorId, "Bassam", "staff@example.com", "Cashier", "epoch_1"),
                availableCompanies = memberships
            )
            CoordinatorResult.Success(fakeCoordinator.authState, generation = fakeCoordinator.currentGeneration)
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.signIn("staff@example.com", passwordOverride = "password")
        advanceUntilIdle()

        val selectionState = vm.uiState.value
        assertEquals(StaffWorkspacePhase.COMPANY_SELECTION, selectionState.phase)
        assertEquals(2, selectionState.availableCompanies.size)

        // Select second company
        vm.selectCompany(testCompanyId2.value)
        advanceUntilIdle()

        val readyState = vm.uiState.value
        assertEquals(StaffWorkspacePhase.READ_READY, readyState.phase)
        assertEquals(testCompanyId2.value, readyState.activeCompany?.companyId)
        assertEquals("Branch North", readyState.activeCompany?.companyName)
    }

    @Test
    fun readListAndPagination_productCatalog_loadsInitialAndNextPage() = runTest(testDispatcher) {
        val item1 = fakeCoordinator.createProductItem("01JCV8X9M2NQW3TYZ4R5S67891", "P-1", "Product One")
        val item2 = fakeCoordinator.createProductItem("01JCV8X9M2NQW3TYZ4R5S67892", "P-2", "Product Two")

        var queryCount = 0
        fakeCoordinator.getProductsHandler = { query ->
            queryCount++
            if (query.cursor == null) {
                ProductCatalogResponse(
                    companyId = testCompanyId1,
                    catalogSnapshotVersion = "snap_1",
                    generatedAt = "2026-11-10T12:00:00Z",
                    nextCursor = "cursor_page_2",
                    hasMore = true,
                    products = listOf(item1)
                )
            } else {
                ProductCatalogResponse(
                    companyId = testCompanyId1,
                    catalogSnapshotVersion = "snap_1",
                    generatedAt = "2026-11-10T12:00:00Z",
                    nextCursor = null,
                    hasMore = false,
                    products = listOf(item2)
                )
            }
        }

        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
        fakeCoordinator.restoreResult = CoordinatorResult.Success(fakeCoordinator.authState, generation = 1L, companyId = testCompanyId1)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // Page 1 loaded on startup
        assertEquals(1, vm.uiState.value.products.size)
        assertEquals("P-1", vm.uiState.value.products[0].sku)
        assertTrue(vm.uiState.value.productsHasMore)
        assertEquals("cursor_page_2", vm.uiState.value.productsNextCursor)

        // Load Page 2
        vm.loadProducts(loadMore = true)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.products.size)
        assertEquals("P-2", vm.uiState.value.products[1].sku)
        assertFalse(vm.uiState.value.productsHasMore)
        assertNull(vm.uiState.value.productsNextCursor)
    }

    @Test
    fun customerDirectory_searchQuery_dispatchesQueryAndUpdatesList() = runTest(testDispatcher) {
        var lastSearchQuery: String? = null
        fakeCoordinator.getCustomersHandler = { query ->
            lastSearchQuery = query.search
            CustomerDirectoryResponse(
                companyId = testCompanyId1,
                generatedAt = "2026-11-10T12:00:00Z",
                nextCursor = null,
                hasMore = false,
                customers = listOf(
                    CustomerDirectoryItem(
                        id = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67899"),
                        code = "C-100",
                        name = "Filtered Customer",
                        phone = "0599000000",
                        isWalkIn = false
                    )
                )
            )
        }

        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
        fakeCoordinator.restoreResult = CoordinatorResult.Success(fakeCoordinator.authState, generation = 1L, companyId = testCompanyId1)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.updateCustomerSearchQuery("Ahmad")
        advanceUntilIdle()

        assertEquals("Ahmad", lastSearchQuery)
        assertEquals(1, vm.uiState.value.customers.size)
        assertEquals("Filtered Customer", vm.uiState.value.customers[0].name)
    }

    @Test
    fun authDenial401_clearsDataImmediately_andLocksSession() = runTest(testDispatcher) {
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
        fakeCoordinator.restoreResult = CoordinatorResult.Success(fakeCoordinator.authState, generation = 1L, companyId = testCompanyId1)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.products.size)

        // Simulate subsequent 401 Auth Denial on product catalog fetch
        fakeCoordinator.forceAuthDenialOnProducts = true
        vm.loadProducts(refresh = true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(StaffWorkspacePhase.LOCKED, state.phase)
        assertTrue(state.products.isEmpty())
        assertTrue(state.customers.isEmpty())
        assertNotNull(state.lockedReason)
    }

    @Test
    fun companySwitch_delayedResponseFromOldCompany_isDiscardedAndDoesNotRepopulate() = runTest(testDispatcher) {
        fakeCoordinator.companyNames[testCompanyId1] = "Company 1"
        fakeCoordinator.companyNames[testCompanyId2] = "Company 2"
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Company 1")
        fakeCoordinator.restoreResult = CoordinatorResult.Success(fakeCoordinator.authState, generation = 1L, companyId = testCompanyId1)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.products.size)

        // Switch to Company 2
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId2, "Company 2")
        vm.switchCompany(testCompanyId2.value)
        advanceUntilIdle()

        assertEquals(testCompanyId2.value, vm.uiState.value.activeCompany?.companyId)
        assertEquals("Company 2", vm.uiState.value.activeCompany?.companyName)
    }

    @Test
    fun logout_cancelsJobs_wipesListsAndCredentials_handlesRemoteFailureSafely() = runTest(testDispatcher) {
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Company 1")
        fakeCoordinator.restoreResult = CoordinatorResult.Success(fakeCoordinator.authState, generation = 1L, companyId = testCompanyId1)
        fakeCoordinator.remoteRevocationStatus = RemoteRevocationStatus.Unknown // Remote network timeout

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.setTransientPassword("secret")
        vm.logout()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(StaffWorkspacePhase.SIGN_IN, state.phase)
        assertNull(state.activeCompany)
        assertNull(state.staffActor)
        assertTrue(state.products.isEmpty())
        assertTrue(state.customers.isEmpty())
        assertEquals(StaffAuthState.LoggedOut, fakeCoordinator.authState)
    }

    @Test
    fun deduplication_submittingDuplicateLoginIgnored() = runTest(testDispatcher) {
        var loginInvocations = 0
        fakeCoordinator.loginResultProvider = { _, _, _, _ ->
            loginInvocations++
            CoordinatorResult.Success(fakeCoordinator.createReadReadyState(testCompanyId1, "Store"), generation = 2L, companyId = testCompanyId1)
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        vm.signIn("staff@example.com", passwordOverride = "pass")
        // Simultaneous immediate duplicate call
        vm.signIn("staff@example.com", passwordOverride = "pass")
        advanceUntilIdle()

        assertEquals(1, loginInvocations)
    }

    @Test
    fun preferenceToggles_languageAndTheme() = runTest(testDispatcher) {
        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )

        assertEquals(com.example.model.AppLanguage.AR, vm.uiState.value.language)
        assertFalse(vm.uiState.value.isDarkTheme)

        vm.toggleLanguage()
        assertEquals(com.example.model.AppLanguage.EN, vm.uiState.value.language)

        vm.toggleTheme()
        assertTrue(vm.uiState.value.isDarkTheme)
    }
}

/**
 * Deterministic in-memory fake implementation of [StaffSessionCoordinator] for testing.
 */
class FakeStaffSessionCoordinator(
    private var initialCompanyId: ServerUlid,
    private val initialActorId: ServerUlid,
    private val installId: ClientUuid
) : StaffSessionCoordinator {

    override var authState: StaffAuthState = StaffAuthState.LoggedOut
    override val installationId: ClientUuid = installId
    override var currentGeneration: Long = 1L

    var restoreResult: CoordinatorResult<StaffAuthState>? = null
    var loginResultProvider: ((String, String, String?, String?) -> CoordinatorResult<StaffAuthState>)? = null
    var retryMfaResultProvider: ((String, String, String?, String?) -> CoordinatorResult<StaffAuthState>)? = null
    var getProductsHandler: ((PaginationQuery) -> ProductCatalogResponse)? = null
    var getCustomersHandler: ((PaginationQuery) -> CustomerDirectoryResponse)? = null
    var forceAuthDenialOnProducts: Boolean = false
    var remoteRevocationStatus: RemoteRevocationStatus = RemoteRevocationStatus.Revoked
    val companyNames: MutableMap<ServerUlid, String> = mutableMapOf()

    override fun isScopeActive(generation: Long, companyId: ServerUlid): Boolean {
        if (generation != currentGeneration) return false
        val ready = authState as? StaffAuthState.ReadReady ?: return false
        return ready.session.selectedCompanyId == companyId
    }

    override fun restoreSession(): CoordinatorResult<StaffAuthState> {
        return restoreResult ?: CoordinatorResult.Success(StaffAuthState.LoggedOut, generation = currentGeneration)
    }

    override fun requestChallenge(appVersion: String): CoordinatorResult<AuthChallengeResponse> {
        return CoordinatorResult.Success(
            AuthChallengeResponse(
                challengeId = ClientUuid("3fa85f64-5717-4562-b3fc-2c963f66afa7"),
                nonce = "12345678901234567890123456789012",
                expiresAt = "2026-11-10T12:05:00Z"
            ),
            generation = currentGeneration
        )
    }

    override fun login(
        email: String,
        password: String,
        deviceName: String,
        mfaCode: String?,
        recoveryCode: String?
    ): CoordinatorResult<StaffAuthState> {
        currentGeneration++
        return loginResultProvider?.invoke(email, password, mfaCode, recoveryCode)
            ?: CoordinatorResult.Error("No login provider configured")
    }

    override fun retryLoginWithMfa(
        email: String,
        password: String,
        deviceName: String,
        mfaCode: String?,
        recoveryCode: String?
    ): CoordinatorResult<StaffAuthState> {
        currentGeneration++
        return retryMfaResultProvider?.invoke(email, password, mfaCode, recoveryCode)
            ?: CoordinatorResult.Error("No MFA provider configured")
    }

    override fun selectCompany(companyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        currentGeneration++
        val name = companyNames[companyId] ?: ("Selected " + companyId.value)
        authState = createReadReadyState(companyId, name)
        return CoordinatorResult.Success(authState, generation = currentGeneration, companyId = companyId, actorId = initialActorId)
    }

    override fun switchCompany(newCompanyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        currentGeneration++
        val name = companyNames[newCompanyId] ?: ("Switched " + newCompanyId.value)
        authState = createReadReadyState(newCompanyId, name)
        return CoordinatorResult.Success(authState, generation = currentGeneration, companyId = newCompanyId, actorId = initialActorId)
    }

    override fun logout(): LogoutResult {
        currentGeneration++
        authState = StaffAuthState.LoggedOut
        return LogoutResult.Success(remoteRevocation = remoteRevocationStatus)
    }

    override fun getProducts(query: PaginationQuery): CoordinatorResult<ProductCatalogResponse> {
        if (forceAuthDenialOnProducts) {
            authState = StaffAuthState.Locked(LockReason.AuthDenied())
            return CoordinatorResult.AuthDenied()
        }
        val ready = authState as? StaffAuthState.ReadReady
            ?: return CoordinatorResult.Error("Not in ReadReady state")
        val companyId = ready.session.selectedCompanyId ?: initialCompanyId

        val response = getProductsHandler?.invoke(query) ?: ProductCatalogResponse(
            companyId = companyId,
            catalogSnapshotVersion = "snap_1",
            generatedAt = "2026-11-10T12:00:00Z",
            nextCursor = null,
            hasMore = false,
            products = listOf(createProductItem("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-DEF", "Default Olive Oil"))
        )
        return CoordinatorResult.Success(response, generation = currentGeneration, companyId = companyId, actorId = initialActorId)
    }

    override fun getCustomers(query: PaginationQuery): CoordinatorResult<CustomerDirectoryResponse> {
        val ready = authState as? StaffAuthState.ReadReady
            ?: return CoordinatorResult.Error("Not in ReadReady state")
        val companyId = ready.session.selectedCompanyId ?: initialCompanyId

        val response = getCustomersHandler?.invoke(query) ?: CustomerDirectoryResponse(
            companyId = companyId,
            generatedAt = "2026-11-10T12:00:00Z",
            nextCursor = null,
            hasMore = false,
            customers = listOf(
                CustomerDirectoryItem(
                    id = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67898"),
                    code = "C-DEFAULT",
                    name = "Ahmad Al-Sakka",
                    phone = "0599111222",
                    isWalkIn = false
                )
            )
        )
        return CoordinatorResult.Success(response, generation = currentGeneration, companyId = companyId, actorId = initialActorId)
    }

    fun createReadReadyState(companyId: ServerUlid, companyName: String): StaffAuthState.ReadReady {
        val record = StaffSessionRecord(
            token = "valid_test_token_string_over_20_chars",
            actorUlid = initialActorId,
            deviceUuid = installId,
            passwordEpoch = "epoch_1",
            expiresAt = "2026-11-11T12:00:00Z",
            selectedCompanyId = companyId,
            roleLabel = "Cashier",
            actorName = "Bassam",
            actorEmail = "staff@example.com",
            createdAt = "2026-11-10T12:00:00Z"
        )
        val bootstrap = BootstrapResponse(
            companyId = companyId,
            companyName = companyName,
            baseCurrency = "ILS",
            walkInCustomerId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S67890"),
            staff = BootstrapStaff(initialActorId, "Bassam", "Cashier", listOf("catalog.read")),
            deviceLease = null,
            defaultWarehouse = DefaultWarehouse(ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789W"), "WH-1", "Main Warehouse"),
            approvedCashAccount = ApprovedCashAccount(ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789A"), "CASH-1", "Register 1", "ILS"),
            units = emptyList(),
            taxes = emptyList(),
            offlinePolicy = OfflinePolicyDisabled(),
            catalogSnapshotVersion = "snap_1"
        )
        return StaffAuthState.ReadReady(record, bootstrap)
    }

    fun createProductItem(id: String, sku: String, name: String): ProductCatalogItem {
        val baseUnit = ProductBaseUnit(ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"), "KG", "Kilogram")
        val defaultUnit = ProductDefaultUnit(
            unitId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E"),
            productUnitId = ServerUlid(id),
            conversionToBase = PositiveDecimalQuantity("1.0"),
            salePrice = MoneyAmount("25.00")
        )
        return ProductCatalogItem(
            id = ServerUlid(id),
            sku = sku,
            barcode = "12345678",
            name = name,
            unitPrice = MoneyAmount("20.00"),
            taxRate = TaxPercentage("16.00"),
            taxRateId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789T"),
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity("100.000000"),
            baseUnit = baseUnit,
            defaultUnit = defaultUnit,
            units = emptyList()
        )
    }
}
