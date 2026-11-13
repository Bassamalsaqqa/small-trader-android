package com.example.ui.staff

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.outbox.OutboxHealthInspector
import com.example.data.mobile.outbox.OutboxHealthResult
import com.example.data.mobile.outbox.OutboxHealthStatus
import com.example.model.AppLanguage
import com.example.ui.theme.SmallTraderTheme
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
    private val testCompanyId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789A")
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

        // Arabic title and description
        composeTestRule.onNodeWithText("مراجعة استرداد البيانات السابقة مطلوبة").assertIsDisplayed()

        // Preserved data banner
        composeTestRule.onNodeWithText("كافة السجلات غير المرحّلة محفوظة بأمان على هذا الجهاز").assertIsDisplayed()

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
        composeTestRule.onNodeWithText("All unsynced offline records are preserved safely on this device").assertIsDisplayed()

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
        composeTestRule.onNodeWithText("All unsynced offline records are preserved safely on this device").assertIsDisplayed()
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
        composeTestRule.onNodeWithText("All unsynced offline records are preserved safely on this device").assertIsDisplayed()
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
        composeTestRule.onNodeWithText("All unsynced offline records are preserved safely on this device").assertIsDisplayed()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staffWorkspaceViewModel_withLegacyOutbox_routesToOutboxRecoveryRequired() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyId,
            initialActorId = testActorId,
            installId = testInstallId
        )

        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(maxProbeCount: Int): OutboxHealthResult {
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

        assertEquals(StaffWorkspacePhase.OUTBOX_RECOVERY_REQUIRED, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.outboxHealthResult)
        assertEquals(OutboxHealthStatus.LEGACY_PLAINTEXT, vm.uiState.value.outboxHealthResult!!.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staffWorkspaceViewModel_retryHealth_transitionsToSignInWhenResolved() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyId,
            initialActorId = testActorId,
            installId = testInstallId
        )

        var isResolved = false
        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(maxProbeCount: Int): OutboxHealthResult {
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
        assertEquals(StaffWorkspacePhase.OUTBOX_RECOVERY_REQUIRED, vm.uiState.value.phase)

        // Resolve the issue (e.g. device unlocked or key recovered)
        isResolved = true
        vm.retryOutboxHealth()
        advanceUntilIdle()

        // After successful retry, outbox is healthy and normal session restoration proceeds!
        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
        assertEquals(OutboxHealthStatus.HEALTHY, vm.uiState.value.outboxHealthResult!!.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staffWorkspaceViewModel_healthyOutbox_proceedsToNormalPhase() = runTest(testDispatcher) {
        val fakeCoordinator = FakeStaffSessionCoordinator(
            initialCompanyId = testCompanyId,
            initialActorId = testActorId,
            installId = testInstallId
        )

        val fakeInspector = object : OutboxHealthInspector {
            override suspend fun inspectHealth(maxProbeCount: Int): OutboxHealthResult {
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

        // Normal session restoration proceeds directly to SIGN_IN
        assertEquals(StaffWorkspacePhase.SIGN_IN, vm.uiState.value.phase)
    }
}
