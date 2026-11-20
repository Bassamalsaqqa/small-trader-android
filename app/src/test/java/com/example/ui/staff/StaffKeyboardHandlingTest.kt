package com.example.ui.staff

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.example.model.AppLanguage
import com.example.ui.theme.SmallTraderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaffKeyboardHandlingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun signInScreen_imeActionsAndTransientMemoryClearing_verified() {
        var submittedEmail = ""
        var submittedPassword = ""

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.SIGN_IN,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffSignInScreen(
                    uiState = uiState,
                    onSignIn = { email, pwd ->
                        submittedEmail = email
                        submittedPassword = pwd
                    },
                    onToggleLanguage = {},
                    onToggleTheme = {}
                )
            }
        }

        // Email field accepts input and performs Next action
        composeTestRule.onNodeWithTag("staff_signin_email_input").performTextInput("cashier@al-zaytouna.ps")
        composeTestRule.onNodeWithTag("staff_signin_email_input").performImeAction()

        composeTestRule.onNodeWithTag("staff_signin_password_input").assertIsFocused()

        // Password field accepts input and performs Done action triggering submission
        composeTestRule.onNodeWithTag("staff_signin_password_input").performTextInput("StrongPass123!")
        composeTestRule.onNodeWithTag("staff_signin_password_input").performImeAction()
        composeTestRule.waitForIdle()

        assertEquals("cashier@al-zaytouna.ps", submittedEmail)
        assertEquals("StrongPass123!", submittedPassword)

        // Password input field in Compose state was cleared after submission
        assertEquals("", composeTestRule.onNodeWithTag("staff_signin_password_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun mfaScreen_imeDoneAction_submitsCodeSafely() {
        var submittedCode = ""
        var submittedIsRecovery = false

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.MFA_REQUIRED,
            mfaMode = MfaMode.TOTP,
            language = AppLanguage.AR
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = true) {
                StaffMfaScreen(
                    uiState = uiState,
                    onRetryWithMfa = { code, isRec ->
                        submittedCode = code
                        submittedIsRecovery = isRec
                    },
                    onSetMfaMode = {},
                    onBackToSignIn = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("staff_mfa_code_input").performTextInput("847291")
        composeTestRule.onNodeWithTag("staff_mfa_code_input").performImeAction()
        composeTestRule.waitForIdle()

        assertEquals("847291", submittedCode)
        assertFalse(submittedIsRecovery)

        // Code input in Compose state was cleared after submission
        assertEquals("", composeTestRule.onNodeWithTag("staff_mfa_code_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun mfaScreen_recoveryMode_imeDoneAction_submitsRecoveryCodeSafely() {
        var submittedCode = ""
        var submittedIsRecovery = false

        val uiState = StaffWorkspaceUiState(
            phase = StaffWorkspacePhase.MFA_REQUIRED,
            mfaMode = MfaMode.RECOVERY_CODE,
            language = AppLanguage.EN
        )

        composeTestRule.setContent {
            SmallTraderTheme(darkTheme = false, isRtl = false) {
                StaffMfaScreen(
                    uiState = uiState,
                    onRetryWithMfa = { code, isRec ->
                        submittedCode = code
                        submittedIsRecovery = isRec
                    },
                    onSetMfaMode = {},
                    onBackToSignIn = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("staff_recovery_code_input").performTextInput("abcde-12345-fghij")
        composeTestRule.onNodeWithTag("staff_recovery_code_input").performImeAction()
        composeTestRule.waitForIdle()

        assertEquals("abcde-12345-fghij", submittedCode)
        assertTrue(submittedIsRecovery)

        // Input was cleared after submission
        assertEquals("", composeTestRule.onNodeWithTag("staff_recovery_code_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}
