package com.example.ui.staff

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.OutboxHealthInspector
import com.example.data.mobile.outbox.OutboxHealthResult
import com.example.data.mobile.outbox.OutboxHealthStatus
import com.example.data.mobile.session.CoordinatorResult
import com.example.model.AppLanguage
import com.example.ui.theme.SmallTraderTheme
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StaffOutboxRecoveryComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private val testCompanyIdA = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val testCompanyIdB = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val testActorId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val testInstallId = ClientUuid("3fa85f64-5717-4562-b3fc-2c963f66afa6")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun legacyPlaintext_rendersFriendlyBilingualMessages_preservesBanner_noConvertButton() {
        var retried = false
        val legacyResult = OutboxHealthResult(
            status = OutboxHealthStatus.LEGACY_PLAINTEXT,
            totalEvents = 3,
            probedCount = 3,
            isSampleProbe = false,
            messageEn = "Pre-existing unencrypted sales records detected. Records are preserved safely on device, but require owner-reviewed recovery before processing.",
            messageAr = "تم اكتشاف سجلات مبيعات سابقة غير مشفرة. السجلات محفوظة بأمان على الجهاز وتتطلب مراجعة واعتماد الاسترداد من المالك قبل المعالجة.",
            canRetry = true
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffOutboxRecoveryScreen(
                    healthResult = legacyResult,
                    lang = AppLanguage.AR,
                    onRetry = { retried = true },
                    isRetrying = false
                )
            }
        }

        // Screen is displayed
        composeTestRule.onNodeWithTag("outbox_recovery_gate_screen").assertIsDisplayed()

        // Arabic title
        composeTestRule.onNodeWithText("مراجعة استرداد البيانات السابقة مطلوبة").assertIsDisplayed()

        // Truthful preserved data banner
        composeTestRule.onNodeWithText("لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.").assertIsDisplayed()

        // Contact support guidance
        composeTestRule.onNodeWithText("يرجى التواصل مع الدعم الفني.").assertIsDisplayed()

        // Explicit authority restriction notice
        composeTestRule.onNodeWithText("تسجيل الدخول للموظفين لا يمنح صلاحية استرداد البيانات. يظل التحويل مغلقاً لحين اعتماد المالك.").assertIsDisplayed()

        // CRITICAL TRUST: No "Convert" button exists
        composeTestRule.onNodeWithText("Convert").assertDoesNotExist()
        composeTestRule.onNodeWithText("تحويل").assertDoesNotExist()

        // Safe retry / check status button
        val retryBtn = composeTestRule.onNodeWithTag("outbox_health_retry_button")
        retryBtn.assertIsDisplayed()
        retryBtn.assertIsEnabled()
        retryBtn.performClick()
        assertTrue(retried)
    }

    @Test
    fun encryptedLocked_rendersFriendlyMessage_showsRetry() {
        var retried = false
        val lockedResult = OutboxHealthResult(
            status = OutboxHealthStatus.ENCRYPTED_LOCKED,
            totalEvents = 1,
            probedCount = 1,
            isSampleProbe = false,
            messageEn = "The secure encryption key is locked or invalidated. Unsynced offline sales records are preserved safely on this device.",
            messageAr = "مفتاح التشفير الآمن مقفل أو تم إبطاله. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
            canRetry = true
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffOutboxRecoveryScreen(
                    healthResult = lockedResult,
                    lang = AppLanguage.EN,
                    onRetry = { retried = true },
                    isRetrying = false
                )
            }
        }

        composeTestRule.onNodeWithText("Encryption Key Locked").assertIsDisplayed()
        composeTestRule.onNodeWithText("We have not changed stored sales. Some records need help before syncing.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contact support").assertIsDisplayed()

        val retryBtn = composeTestRule.onNodeWithTag("outbox_health_retry_button")
        retryBtn.assertIsDisplayed()
        retryBtn.performClick()
        assertTrue(retried)
    }

    @Test
    fun keyUnavailable_rendersFriendlyMessage_showsRetry() {
        val unavailableResult = OutboxHealthResult(
            status = OutboxHealthStatus.KEY_UNAVAILABLE,
            totalEvents = 2,
            probedCount = 2,
            isSampleProbe = false,
            messageEn = "The secure encryption key is unavailable on this device. Unsynced offline sales records are preserved safely on this device.",
            messageAr = "مفتاح التشفير الآمن غير متوفر على هذا الجهاز. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
            canRetry = true
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffOutboxRecoveryScreen(
                    healthResult = unavailableResult,
                    lang = AppLanguage.EN,
                    onRetry = {},
                    isRetrying = false
                )
            }
        }

        composeTestRule.onNodeWithText("Encryption Key Unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("We have not changed stored sales. Some records need help before syncing.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contact support").assertIsDisplayed()
    }

    @Test
    fun corrupt_rendersFriendlyMessage_showsRetry() {
        val corruptResult = OutboxHealthResult(
            status = OutboxHealthStatus.CORRUPT,
            totalEvents = 1,
            probedCount = 1,
            isSampleProbe = false,
            messageEn = "Storage records could not be cryptographically authenticated. Unsynced offline sales records are preserved safely on this device.",
            messageAr = "تعذر التحقق من التشفير الأمني للسجلات. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
            canRetry = true
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffOutboxRecoveryScreen(
                    healthResult = corruptResult,
                    lang = AppLanguage.EN,
                    onRetry = {},
                    isRetrying = false
                )
            }
        }

        composeTestRule.onNodeWithText("Cryptographic Authentication Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("We have not changed stored sales. Some records need help before syncing.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contact support").assertIsDisplayed()
    }

    @Test
    fun mixedQueue_rendersFriendlyMessage_showsRetry() {
        val mixedResult = OutboxHealthResult(
            status = OutboxHealthStatus.MIXED_QUEUE,
            totalEvents = 2,
            probedCount = 2,
            isSampleProbe = false,
            messageEn = "Outbox storage contains conflicting record formats. Unsynced offline sales records are preserved safely on this device.",
            messageAr = "تحتوي وحدة التخزين على تنسيقات سجلات غير متوافقة. سجلات المبيعات غير المرحّلة محفوظة بأمان على هذا الجهاز.",
            canRetry = true
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffOutboxRecoveryScreen(
                    healthResult = mixedResult,
                    lang = AppLanguage.EN,
                    onRetry = {},
                    isRetrying = false
                )
            }
        }

        composeTestRule.onNodeWithText("Conflicting Storage Formats").assertIsDisplayed()
        composeTestRule.onNodeWithText("We have not changed stored sales. Some records need help before syncing.").assertIsDisplayed()
    }

    @Test
    fun isRetrying_disablesRetryButton() {
        val legacyResult = OutboxHealthResult(
            status = OutboxHealthStatus.LEGACY_PLAINTEXT,
            totalEvents = 1,
            probedCount = 1,
            isSampleProbe = false,
            messageEn = "Test",
            messageAr = "اختبار",
            canRetry = true
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffOutboxRecoveryScreen(
                    healthResult = legacyResult,
                    lang = AppLanguage.EN,
                    onRetry = {},
                    isRetrying = true
                )
            }
        }

        composeTestRule.onNodeWithTag("outbox_health_retry_button").assertIsNotEnabled()
    }

    @Test
    fun workspaceScreen_withOutboxRecoveryBanner_preservesReadCatalog() {
        var retried = false
        val legacyResult = OutboxHealthResult(
            status = OutboxHealthStatus.LEGACY_PLAINTEXT,
            totalEvents = 2,
            probedCount = 2,
            isSampleProbe = false,
            messageEn = "Legacy outbox detected",
            messageAr = "تم اكتشاف سجلات سابقة",
            canRetry = true
        )

        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyIdA,
            initialActorId = testActorId,
            installId = testInstallId
        )
        val sampleProduct = fakeCoordinator.createProductItem("01JCV8X9M2NQW3TYZ4R5S67891", "SKU-OIL", "Extra Virgin Olive Oil")

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.READ_READY,
            activeCompany = SafeCompanyViewData(testCompanyIdA.value, "Al-Zaytouna"),
            staffActor = SafeStaffActorViewData(testActorId.value, "staff@example.com", "Bassam", "Cashier"),
            products = listOf(sampleProduct),
            outboxHealthResult = legacyResult,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffWorkspaceScreen(
                    uiState = uiState,
                    onSelectTab = {},
                    onLoadMoreProducts = {},
                    onRefreshProducts = {},
                    onSearchProducts = {},
                    onLoadMoreCustomers = {},
                    onRefreshCustomers = {},
                    onSearchCustomers = {},
                    onSwitchCompany = {},
                    onLogout = {},
                    onToggleLanguage = {},
                    onToggleTheme = {},
                    onRetryOutboxHealth = { retried = true }
                )
            }
        }

        // Recovery banner is displayed within read workspace
        composeTestRule.onNodeWithTag("outbox_recovery_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local Outbox Storage Notice").assertIsDisplayed()
        composeTestRule.onNodeWithText("We have not changed stored sales. Some records need help before syncing.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contact support").assertIsDisplayed()

        // Catalog is PRESERVED and accessible underneath!
        composeTestRule.onNodeWithText("Extra Virgin Olive Oil").assertExists()
        composeTestRule.onNodeWithText("SKU-OIL").assertExists()

        // Retry in banner works
        val retryBtn = composeTestRule.onNodeWithTag("outbox_health_retry_button")
        retryBtn.assertIsDisplayed()
        retryBtn.performClick()
        assertTrue(retried)
    }

    @Test
    fun staffWorkspaceViewModel_withHealthyOutbox_transitionsToReadReady_andSetsQueueProcessingReadyTrue() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyIdA,
            initialActorId = testActorId,
            installId = testInstallId
        )
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            fakeCoordinator.createReadReadyState(testCompanyIdA, "Al-Zaytouna"),
            generation = 1L,
            companyId = testCompanyIdA,
            actorId = testActorId
        )
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyIdA, "Al-Zaytouna")

        var probedScope: Triple<String, String, String>? = null
        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(
                companyId: String,
                actorId: String,
                deviceId: String,
                maxProbeCount: Int
            ): OutboxHealthResult {
                probedScope = Triple(companyId, actorId, deviceId)
                return OutboxHealthResult(
                    status = OutboxHealthStatus.HEALTHY,
                    totalEvents = 1,
                    probedCount = 1,
                    isSampleProbe = false,
                    messageEn = "Healthy",
                    messageAr = "سليم",
                    canRetry = false
                )
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://pilot.example.com",
            ioDispatcher = testDispatcher,
            outboxHealthInspector = fakeInspector
        )

        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertEquals(Triple(testCompanyIdA.value, testActorId.value, testInstallId.value), probedScope)
        assertNotNull(vm.uiState.value.outboxHealthResult)
        assertEquals(OutboxHealthStatus.HEALTHY, vm.uiState.value.outboxHealthResult!!.status)
        assertTrue(vm.uiState.value.isQueueProcessingReady)
    }

    @Test
    fun staffWorkspaceViewModel_withLegacyOutbox_retainsReadReady_setsQueueProcessingReadyFalse_preservesCatalog() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyIdA,
            initialActorId = testActorId,
            installId = testInstallId
        )
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            fakeCoordinator.createReadReadyState(testCompanyIdA, "Al-Zaytouna"),
            generation = 1L,
            companyId = testCompanyIdA,
            actorId = testActorId
        )
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyIdA, "Al-Zaytouna")

        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(
                companyId: String,
                actorId: String,
                deviceId: String,
                maxProbeCount: Int
            ): OutboxHealthResult {
                return OutboxHealthResult(
                    status = OutboxHealthStatus.LEGACY_PLAINTEXT,
                    totalEvents = 5,
                    probedCount = 5,
                    isSampleProbe = false,
                    messageEn = "Legacy",
                    messageAr = "سابق",
                    canRetry = true
                )
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://pilot.example.com",
            ioDispatcher = testDispatcher,
            outboxHealthInspector = fakeInspector
        )

        advanceUntilIdle()

        // Architectural separation: Honest read access works! Phase is READ_READY, not error screen.
        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertEquals(1, vm.uiState.value.products.size)
        assertEquals(1, vm.uiState.value.customers.size)

        // Queue processing is blocked
        assertFalse(vm.uiState.value.isQueueProcessingReady)
        assertNotNull(vm.uiState.value.outboxHealthResult)
        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, vm.uiState.value.outboxHealthResult!!.status)
    }

    @Test
    fun staffWorkspaceViewModel_companyALegacy_doesNotBlockCompanyBLoginAndReadReady() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyIdA,
            initialActorId = testActorId,
            installId = testInstallId
        )
        // Session restores into Company A with legacy outbox
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            fakeCoordinator.createReadReadyState(testCompanyIdA, "Company A"),
            generation = 1L,
            companyId = testCompanyIdA,
            actorId = testActorId
        )
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyIdA, "Company A")

        val scopedHealthMap = mapOf(
            testCompanyIdA.value to OutboxHealthStatus.LEGACY_PLAINTEXT,
            testCompanyIdB.value to OutboxHealthStatus.HEALTHY
        )

        val probedScopes = mutableListOf<String>()
        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(
                companyId: String,
                actorId: String,
                deviceId: String,
                maxProbeCount: Int
            ): OutboxHealthResult {
                probedScopes.add(companyId)
                val status = scopedHealthMap[companyId] ?: OutboxHealthStatus.HEALTHY
                return OutboxHealthResult(
                    status = status,
                    totalEvents = 2,
                    probedCount = 2,
                    isSampleProbe = false,
                    messageEn = "Status $status",
                    messageAr = "الحالة $status",
                    canRetry = status != OutboxHealthStatus.HEALTHY
                )
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://pilot.example.com",
            ioDispatcher = testDispatcher,
            outboxHealthInspector = fakeInspector
        )

        advanceUntilIdle()

        // Company A has legacy outbox -> read-ready is active, but queue processing is not ready
        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertEquals(testCompanyIdA.value, vm.uiState.value.activeCompany?.companyId)
        assertFalse(vm.uiState.value.isQueueProcessingReady)
        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, vm.uiState.value.outboxHealthResult!!.status)

        // Switch to Company B
        fakeCoordinator.companyNames[testCompanyIdB] = "Company B"
        vm.switchCompany(testCompanyIdB.value)
        advanceUntilIdle()

        // Company B is healthy -> Company A's legacy outbox did NOT block Company B!
        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertEquals(testCompanyIdB.value, vm.uiState.value.activeCompany?.companyId)
        assertTrue(vm.uiState.value.isQueueProcessingReady)
        assertEquals(OutboxHealthStatus.HEALTHY, vm.uiState.value.outboxHealthResult!!.status)
        assertTrue(probedScopes.contains(testCompanyIdB.value))
    }

    @Test
    fun staffWorkspaceViewModel_generationBarrier_cancelsAndIgnoresStaleHealthResultOnLogout() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyIdA,
            initialActorId = testActorId,
            installId = testInstallId
        )
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            fakeCoordinator.createReadReadyState(testCompanyIdA, "Company A"),
            generation = 1L,
            companyId = testCompanyIdA,
            actorId = testActorId
        )
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyIdA, "Company A")

        val healthCheckSignal = CompletableDeferred<OutboxHealthResult>()
        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(
                companyId: String,
                actorId: String,
                deviceId: String,
                maxProbeCount: Int
            ): OutboxHealthResult {
                return healthCheckSignal.await()
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://pilot.example.com",
            ioDispatcher = testDispatcher,
            outboxHealthInspector = fakeInspector
        )

        // Advance to let restoreSession complete and health check start
        advanceUntilIdle()

        // Now user logs out while health check is still waiting!
        vm.logout()
        advanceUntilIdle()

        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
        assertNull(vm.uiState.value.outboxHealthResult)

        // Complete the stale health check with CORRUPT
        healthCheckSignal.complete(
            OutboxHealthResult(
                status = OutboxHealthStatus.CORRUPT,
                totalEvents = 1,
                probedCount = 1,
                isSampleProbe = false,
                messageEn = "Stale Corrupt",
                messageAr = "فاسد قديم",
                canRetry = true
            )
        )
        advanceUntilIdle()

        // Generation barrier: State must NOT be overwritten by stale health result
        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
        assertNull(vm.uiState.value.outboxHealthResult)
        assertFalse(vm.uiState.value.isOutboxRetrying)
    }

    @Test
    fun staffWorkspaceViewModel_retryHealth_updatesReadReadyStateWhenResolved() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyIdA,
            initialActorId = testActorId,
            installId = testInstallId
        )
        fakeCoordinator.restoreResult = CoordinatorResult.Success(
            fakeCoordinator.createReadReadyState(testCompanyIdA, "Al-Zaytouna"),
            generation = 1L,
            companyId = testCompanyIdA,
            actorId = testActorId
        )
        fakeCoordinator.authState = fakeCoordinator.createReadReadyState(testCompanyIdA, "Al-Zaytouna")

        var isResolved = false
        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(
                companyId: String,
                actorId: String,
                deviceId: String,
                maxProbeCount: Int
            ): OutboxHealthResult {
                return if (!isResolved) {
                    OutboxHealthResult(
                        status = OutboxHealthStatus.ENCRYPTED_LOCKED,
                        totalEvents = 1,
                        probedCount = 1,
                        isSampleProbe = false,
                        messageEn = "Locked",
                        messageAr = "مقفل",
                        canRetry = true
                    )
                } else {
                    OutboxHealthResult(
                        status = OutboxHealthStatus.HEALTHY,
                        totalEvents = 1,
                        probedCount = 1,
                        isSampleProbe = false,
                        messageEn = "Healthy",
                        messageAr = "سليم",
                        canRetry = false
                    )
                }
            }
        }

        val vm = StaffWorkspaceViewModel(
            coordinator = fakeCoordinator,
            configuredOrigin = "https://pilot.example.com",
            ioDispatcher = testDispatcher,
            outboxHealthInspector = fakeInspector
        )

        advanceUntilIdle()
        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.isQueueProcessingReady)

        // Resolve the issue (e.g. device key unlocked)
        isResolved = true
        vm.retryOutboxHealth()
        advanceUntilIdle()

        // After successful retry, queue is healthy and processing is ready
        assertEquals(StaffWorkspacePhase.READ_READY, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.isQueueProcessingReady)
        assertEquals(OutboxHealthStatus.HEALTHY, vm.uiState.value.outboxHealthResult!!.status)
    }
}
