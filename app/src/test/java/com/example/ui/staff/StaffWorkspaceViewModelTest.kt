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
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.CoordinatorClock
import com.example.data.mobile.session.CoordinatorResult
import com.example.data.mobile.session.DefaultSecureStaffSessionStore
import com.example.data.mobile.session.DefaultStaffSessionCoordinator
import com.example.data.mobile.session.InstallationIdentityStore
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.data.mobile.session.LockReason
import com.example.data.mobile.session.LogoutResult
import com.example.data.mobile.session.RemoteRevocationStatus
import com.example.data.mobile.session.SecureStaffSessionStore
import com.example.data.mobile.session.StaffAuthState
import com.example.data.mobile.session.StaffSessionCoordinator
import com.example.data.mobile.session.StaffSessionRecord
import java.io.File
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

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    private data class RealCoordinatorEnv(
        val coordinator: DefaultStaffSessionCoordinator,
        val transport: RoutingMockTransport,
        val sessionStore: DefaultSecureStaffSessionStore,
        val tempDir: File
    )

    private fun setupRealCoordinator(): RealCoordinatorEnv {
        val tempDir = File.createTempFile("vm_test_", "_dir").apply {
            delete()
            mkdirs()
        }
        val sessionFile = File(tempDir, SecureStaffSessionStore.SESSION_FILE_NAME)
        val identityFile = File(tempDir, InstallationIdentityStore.IDENTITY_FILE_NAME)
        val installationStore = InstallationIdentityStore(identityFile)
        val cryptoEngine = JvmAesGcmCryptoEngine()
        val sessionStore = DefaultSecureStaffSessionStore(
            sessionFile = sessionFile,
            installationStore = installationStore,
            cryptoEngine = cryptoEngine,
            packageName = "com.aistudio.smalltrader.psdukx"
        )
        val transport = RoutingMockTransport()
        val readClient = SmallTraderReadClient(transport, "https://api.smalltrader.example")
        val coordinator = DefaultStaffSessionCoordinator(
            readClient = readClient,
            sessionStore = sessionStore,
            clock = TestCoordinatorClock()
        )
        return RealCoordinatorEnv(coordinator, transport, sessionStore, tempDir)
    }

    @Test
    fun realCoordinator_signIn_wireChallengeToLoginToBootstrapToProductsCustomers_andPersistsSession() = runTest(testDispatcher) {
        val env = setupRealCoordinator()
        try {
            val fixtures = locateFixturesDir()
            val challengeJson = File(fixtures, "golden-auth-challenge.json").readText(Charsets.UTF_8)
            val challengeRes = StrictJsonParser.parse(challengeJson).asObject().require("response")
            val loginJson = File(fixtures, "golden-auth-login.json").readText(Charsets.UTF_8)
            val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")
            val bootstrapJson = File(fixtures, "golden-bootstrap.json").readText(Charsets.UTF_8)
            val productJson = File(fixtures, "golden-product-catalog.json").readText(Charsets.UTF_8)
            val customerJson = File(fixtures, "golden-customer-directory.json").readText(Charsets.UTF_8)

            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/challenge")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(challengeRes))
            }
            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")] = {
                TransportResponse(statusCode = 200, body = bootstrapJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")] = {
                TransportResponse(statusCode = 200, body = productJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/customers")] = {
                TransportResponse(statusCode = 200, body = customerJson)
            }

            val vm = StaffWorkspaceViewModel(
                coordinator = env.coordinator,
                configuredOrigin = "https://api.smalltrader.example",
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            // 1. Initial phase is SIGN_IN
            assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)

            // 2. Perform sign in: triggers wire challenge -> login -> bootstrap -> products & customers
            vm.signIn("cashier@smalltrader.example", passwordOverride = "CorrectHorseBatteryStaple123!")
            advanceUntilIdle()

            // Verify sequential requests over wire
            assertTrue("Expected at least 5 requests on wire", env.transport.requestsReceived.size >= 5)
            assertEquals("/api/mobile/v1/auth/challenge", env.transport.requestsReceived[0].path)
            assertEquals("/api/mobile/v1/auth/login", env.transport.requestsReceived[1].path)
            assertTrue(
                "Login request body must contain challenge nonce",
                env.transport.requestsReceived[1].body?.contains("9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef") == true
            )
            assertEquals("/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap", env.transport.requestsReceived[2].path)

            // Verify secure storage persistence
            assertTrue("Session must be securely persisted to disk", env.sessionStore.hasSessionFile())

            // Verify UI state reaches READ_READY with products, customers, and truthful base currency
            val state = vm.uiState.value
            assertEquals(StaffWorkspacePhase.READ_READY, state.phase)
            assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", state.activeCompany?.companyId)
            assertEquals("Al-Amal Retail Company", state.activeCompany?.companyName)
            assertEquals("ILS", state.activeCompany?.currency)
            assertEquals("Ahmad Cashier", state.staffActor?.actorName)
            assertEquals("cashier", state.staffActor?.roleLabel)
            assertEquals(2, state.products.size)
            assertEquals("SKU-OLIVE-OIL-1L", state.products[0].sku)
            assertEquals(2, state.customers.size)
        } finally {
            env.tempDir.deleteRecursively()
        }
    }

    @Test
    fun realCoordinator_mfaRetry_freshChallengeAcquiredWithExactNonce_badCodeFailsAndCorrectRetrySucceeds() = runTest(testDispatcher) {
        val env = setupRealCoordinator()
        try {
            val fixtures = locateFixturesDir()
            val loginJson = File(fixtures, "golden-auth-login.json").readText(Charsets.UTF_8)
            val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")
            val bootstrapJson = File(fixtures, "golden-bootstrap.json").readText(Charsets.UTF_8)
            val productJson = File(fixtures, "golden-product-catalog.json").readText(Charsets.UTF_8)
            val customerJson = File(fixtures, "golden-customer-directory.json").readText(Charsets.UTF_8)

            var challengeCount = 0
            val nonces = listOf(
                "nonce_11111111111111111111111111111111",
                "nonce_22222222222222222222222222222222",
                "nonce_33333333333333333333333333333333"
            )
            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/challenge")] = {
                val nonce = nonces[challengeCount % nonces.size]
                challengeCount++
                TransportResponse(
                    statusCode = 200,
                    body = """{"challenge_id":"a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d","nonce":"$nonce","expires_at":"2026-11-10T12:05:00Z"}"""
                )
            }

            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = { req ->
                val body = req.body ?: ""
                when {
                    !body.contains("mfa_code") || body.contains("\"mfa_code\":null") -> {
                        assertTrue("Initial login must use challenge nonce 1", body.contains(nonces[0]))
                        TransportResponse(statusCode = 200, body = """{"status":"MFA_REQUIRED","retry":"fresh_challenge"}""")
                    }
                    body.contains("000000") -> {
                        assertTrue("First MFA retry must use fresh challenge nonce 2", body.contains(nonces[1]))
                        TransportResponse(statusCode = 401, body = """{"error":{"code":"AUTH_DENIED","message":"Invalid MFA code"}}""")
                    }
                    body.contains("123456") -> {
                        assertTrue("Second MFA retry must use fresh challenge nonce 3", body.contains(nonces[2]))
                        TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
                    }
                    else -> TransportResponse(statusCode = 400, body = """{"error":{"code":"BAD_REQUEST","message":"Unexpected code"}}""")
                }
            }

            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")] = {
                TransportResponse(statusCode = 200, body = bootstrapJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")] = {
                TransportResponse(statusCode = 200, body = productJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/customers")] = {
                TransportResponse(statusCode = 200, body = customerJson)
            }

            val vm = StaffWorkspaceViewModel(
                coordinator = env.coordinator,
                configuredOrigin = "https://api.smalltrader.example",
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            // 1. Initial sign-in with password -> triggers MFA_REQUIRED
            vm.signIn("cashier@smalltrader.example", passwordOverride = "CorrectHorseBatteryStaple123!")
            advanceUntilIdle()

            assertEquals(StaffWorkspacePhase.MFA_REQUIRED, vm.uiState.value.phase)
            assertEquals(1, challengeCount)

            // 2. Submit bad MFA code -> retryLoginWithMfa acquires fresh challenge 2, fails with 401
            vm.setTransientMfaCode("000000")
            vm.retryWithMfa()
            advanceUntilIdle()

            assertEquals(StaffWorkspacePhase.MFA_REQUIRED, vm.uiState.value.phase)
            assertNotNull(vm.uiState.value.errorMessage)
            assertEquals(2, challengeCount)

            // 3. Submit correct MFA code -> retryLoginWithMfa acquires fresh challenge 3, succeeds
            vm.setTransientMfaCode("123456")
            vm.retryWithMfa()
            advanceUntilIdle()

            assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
            assertEquals("Al-Amal Retail Company", vm.uiState.value.activeCompany?.companyName)
            assertEquals(3, challengeCount)
        } finally {
            env.tempDir.deleteRecursively()
        }
    }

    @Test
    fun realCoordinator_http403Forbidden_locksCoordinator_wipesScopedListsAndResetsSpinners() = runTest(testDispatcher) {
        val env = setupRealCoordinator()
        try {
            val fixtures = locateFixturesDir()
            val challengeJson = File(fixtures, "golden-auth-challenge.json").readText(Charsets.UTF_8)
            val challengeRes = StrictJsonParser.parse(challengeJson).asObject().require("response")
            val loginJson = File(fixtures, "golden-auth-login.json").readText(Charsets.UTF_8)
            val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")
            val bootstrapJson = File(fixtures, "golden-bootstrap.json").readText(Charsets.UTF_8)
            val productJson = File(fixtures, "golden-product-catalog.json").readText(Charsets.UTF_8)
            val customerJson = File(fixtures, "golden-customer-directory.json").readText(Charsets.UTF_8)

            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/challenge")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(challengeRes))
            }
            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")] = {
                TransportResponse(statusCode = 200, body = bootstrapJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")] = {
                TransportResponse(statusCode = 200, body = productJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/customers")] = {
                TransportResponse(statusCode = 200, body = customerJson)
            }

            val vm = StaffWorkspaceViewModel(
                coordinator = env.coordinator,
                configuredOrigin = "https://api.smalltrader.example",
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            vm.signIn("cashier@smalltrader.example", passwordOverride = "CorrectHorseBatteryStaple123!")
            advanceUntilIdle()
            assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
            assertEquals(2, vm.uiState.value.products.size)

            // Now product endpoint returns 403 Forbidden
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")] = {
                TransportResponse(statusCode = 403, body = """{"error":{"code":"FORBIDDEN","message":"Membership revoked"}}""")
            }

            vm.loadProducts(refresh = true)
            advanceUntilIdle()

            val lockedState = vm.uiState.value
            assertEquals(StaffWorkspacePhase.LOCKED, lockedState.phase)
            assertTrue("Products must be cleared on 403", lockedState.products.isEmpty())
            assertTrue("Customers must be cleared on 403", lockedState.customers.isEmpty())
            assertFalse("Products loading must be reset", lockedState.isProductsLoading)
            assertFalse("Products loading more must be reset", lockedState.isProductsLoadingMore)
            assertNotNull(lockedState.lockedReason)
        } finally {
            env.tempDir.deleteRecursively()
        }
    }

    @Test
    fun realCoordinator_trackedLogout_purgesDiskAndDeniesUIReads() = runTest(testDispatcher) {
        val env = setupRealCoordinator()
        try {
            val fixtures = locateFixturesDir()
            val challengeJson = File(fixtures, "golden-auth-challenge.json").readText(Charsets.UTF_8)
            val challengeRes = StrictJsonParser.parse(challengeJson).asObject().require("response")
            val loginJson = File(fixtures, "golden-auth-login.json").readText(Charsets.UTF_8)
            val loginRes = StrictJsonParser.parse(loginJson).asObject().require("response")
            val logoutJson = File(fixtures, "golden-auth-logout.json").readText(Charsets.UTF_8)
            val logoutRes = StrictJsonParser.parse(logoutJson).asObject().require("response")
            val bootstrapJson = File(fixtures, "golden-bootstrap.json").readText(Charsets.UTF_8)
            val productJson = File(fixtures, "golden-product-catalog.json").readText(Charsets.UTF_8)
            val customerJson = File(fixtures, "golden-customer-directory.json").readText(Charsets.UTF_8)

            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/challenge")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(challengeRes))
            }
            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/login")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(loginRes))
            }
            env.transport.handlers[Pair(HttpMethod.POST, "/api/mobile/v1/auth/logout")] = {
                TransportResponse(statusCode = 200, body = CanonicalJsonSerializer.serialize(logoutRes))
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/bootstrap")] = {
                TransportResponse(statusCode = 200, body = bootstrapJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/products")] = {
                TransportResponse(statusCode = 200, body = productJson)
            }
            env.transport.handlers[Pair(HttpMethod.GET, "/api/mobile/v1/companies/01JCV8X9M2NQW3TYZ4R5S6789A/customers")] = {
                TransportResponse(statusCode = 200, body = customerJson)
            }

            val vm = StaffWorkspaceViewModel(
                coordinator = env.coordinator,
                configuredOrigin = "https://api.smalltrader.example",
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            vm.signIn("cashier@smalltrader.example", passwordOverride = "CorrectHorseBatteryStaple123!")
            advanceUntilIdle()
            assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
            assertTrue(env.sessionStore.hasSessionFile())

            vm.logout()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(StaffWorkspacePhase.SIGN_IN, state.phase)
            assertFalse("Session file must be deleted on logout", env.sessionStore.hasSessionFile())
            assertTrue(state.products.isEmpty())
            assertTrue(state.customers.isEmpty())
            assertNull(state.activeCompany)
            assertNull(state.staffActor)
        } finally {
            env.tempDir.deleteRecursively()
        }
    }

    @Test
    fun productList_deduplicatesItemsWithSameId() = runTest(testDispatcher) {
        val item1 = fakeCoordinator.createProductItem("01JCV8X9M2NQW3TYZ4R5S67891", "P-1", "Product One")
        val itemDuplicate = fakeCoordinator.createProductItem("01JCV8X9M2NQW3TYZ4R5S67891", "P-1", "Product One Duplicate")

        fakeCoordinator.getProductsHandler = {
            ProductCatalogResponse(
                companyId = testCompanyId1,
                catalogSnapshotVersion = "snap_1",
                generatedAt = "2026-11-10T12:00:00Z",
                nextCursor = null,
                hasMore = false,
                products = listOf(item1, itemDuplicate)
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

        // Verify deduped to 1 item
        assertEquals(1, vm.uiState.value.products.size)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S67891", vm.uiState.value.products[0].id.value)
    }

    @Test
    fun restoredSession_companySwitch_showsHonestReAuth_andCancelReturnsToReadReady() = runTest(testDispatcher) {
        // Case 1: Restored session with empty availableCompanies -> honest re-auth prompt on SIGN_IN
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
        fakeCoordinator.restoreResult = CoordinatorResult.Success(fakeCoordinator.authState, generation = 1L, companyId = testCompanyId1)

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)

        // Opening company selection on restored session (where availableCompanies is empty)
        vm.openCompanySelection()
        advanceUntilIdle()

        // Restored session honest re-auth flow
        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("تسجيل الدخول مجدداً"))

        // Case 2: Session with populated availableCompanies -> transitions to COMPANY_SELECTION, cancel returns to READ_READY
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyId1, "Al-Zaytouna")
        val vm2 = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://small-trader.example.com",
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        // Simulate login result with multiple companies
        fakeCoordinator.loginResultProvider = { _, _, _, _ ->
            fakeCoordinator.authState = StaffAuthState.CompanySelectionRequired(
                actor = ActorProfile(testActorId, "Bassam", "staff@example.com", "Cashier", "epoch_1"),
                availableCompanies = listOf(
                    CompanyMembership(testCompanyId1, "Al-Zaytouna Branch 1", "ILS", true),
                    CompanyMembership(testCompanyId2, "Al-Zaytouna Branch 2", "ILS", false)
                )
            )
            CoordinatorResult.Success(fakeCoordinator.authState, generation = 2L)
        }
        vm2.signIn("staff@example.com", passwordOverride = "pass")
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.COMPANY_SELECTION, vm2.uiState.value.phase)
        val pendingSecret = StaffWorkspaceViewModel::class.java.getDeclaredField("pendingPassword")
        pendingSecret.isAccessible = true
        assertEquals("", pendingSecret.get(vm2))

        // Selecting company 1 reaches ReadReady
        vm2.selectCompany(testCompanyId1.value)
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.READ_READY, vm2.uiState.value.phase)
        assertEquals(2, vm2.uiState.value.availableCompanies.size)

        // Now openCompanySelection moves to COMPANY_SELECTION
        vm2.openCompanySelection()
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.COMPANY_SELECTION, vm2.uiState.value.phase)

        // Cancel returns back to READ_READY
        vm2.cancelCompanySelection()
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.READ_READY, vm2.uiState.value.phase)
        assertEquals(testCompanyId1.value, vm2.uiState.value.activeCompany?.companyId)
    }
    @Test
    fun uncertainLogoutLocksInsteadOfClaimingSignedOut() = runTest(testDispatcher) {
        val throwing = object : StaffSessionCoordinator by fakeCoordinator {
            override fun logout(): LogoutResult { throw IllegalStateException("private-logout-token") }
        }
        val vm = StaffWorkspaceViewModel(throwing, "https://example.test", ioDispatcher = testDispatcher)
        advanceUntilIdle()
        vm.logout()
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.LOCKED, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.isSubmitting)
        assertFalse(vm.uiState.value.toString().contains("private-logout-token"))
    }

    @Test
    fun cancelledLogoutClearsBusyFlagAndRemainsLocked() = runTest(testDispatcher) {
        val cancelling = object : StaffSessionCoordinator by fakeCoordinator {
            override fun logout(): LogoutResult { throw kotlinx.coroutines.CancellationException("cancelled") }
        }
        val vm = StaffWorkspaceViewModel(cancelling, "https://example.test", ioDispatcher = testDispatcher)
        advanceUntilIdle()
        vm.logout()
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.LOCKED, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test
    fun storageCompositionCanBeRetriedWithoutChangingConfiguration() = runTest(testDispatcher) {
        var attempts = 0
        val vm = StaffWorkspaceViewModel(null, "https://example.test", ioDispatcher = testDispatcher,
            initialPhase = StaffWorkspacePhase.STORAGE_FAILED,
            coordinatorFactory = {
                attempts++
                if (attempts == 1) throw IllegalStateException("private-provider-message")
                fakeCoordinator
            })
        vm.restoreSession()
        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.STORAGE_FAILED, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.isSubmitting)
        assertFalse(vm.uiState.value.toString().contains("private-provider-message"))
        vm.restoreSession()
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
    }

    @Test
    fun unexpectedLoginFailureDoesNotExposeExceptionInEnglishUi() = runTest(testDispatcher) {
        fakeCoordinator.loginResultProvider = { _, _, _, _ -> throw IllegalStateException("private-password-secret") }
        val vm = StaffWorkspaceViewModel(fakeCoordinator, "https://example.test", ioDispatcher = testDispatcher)
        advanceUntilIdle()
        if (vm.uiState.value.language != com.example.model.AppLanguage.EN) vm.toggleLanguage()
        vm.signIn("staff@example.com", passwordOverride = "password")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSubmitting)
        assertFalse(vm.uiState.value.errorMessage.orEmpty().contains("private-password-secret"))
        assertNotNull(vm.uiState.value.errorMessage)
    }

}

private class RoutingMockTransport : ReadClientTransport {
    val handlers = mutableMapOf<Pair<HttpMethod, String>, (TransportRequest) -> TransportResponse>()
    val requestsReceived = mutableListOf<TransportRequest>()

    override fun execute(request: TransportRequest): TransportResponse {
        requestsReceived.add(request)
        val handler = handlers[Pair(request.method, request.path)]
            ?: return TransportResponse(statusCode = 404, body = """{"error":{"code":"NOT_FOUND","message":"Path not mapped: ${request.path}"}}""")
        return handler(request)
    }
}

private class TestCoordinatorClock(
    var epochMs: Long = 1794312000000L, // 2026-11-10T12:00:00Z
    var isoUtc: String = "2026-11-10T12:00:00Z"
) : CoordinatorClock {
    override fun nowEpochMs(): Long = epochMs
    override fun nowIsoUtc(): String = isoUtc
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
