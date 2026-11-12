package com.example.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.PrimaryBlueLight

/**
 * Screen for secondary authentication (MFA / Recovery Code).
 *
 * Invariants:
 * 1. Mutual exclusivity: Either [MfaMode.TOTP] or [MfaMode.RECOVERY_CODE] is submitted, never both.
 * 2. Transient in-memory inputs: Codes exist only in transient local state and are cleared on submission or back navigation.
 * 3. Fresh challenge retry: Submitting triggers the coordinator's fresh challenge retry sequence.
 */
@Composable
fun StaffMfaScreen(
    uiState: StaffWorkspaceUiState,
    onRetryWithMfa: (code: String, isRecovery: Boolean) -> Unit,
    onSetMfaMode: (MfaMode) -> Unit,
    onBackToSignIn: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lang = uiState.language
    val focusManager = LocalFocusManager.current

    // Transient in-memory code inputs
    var codeInput by remember(uiState.mfaMode) { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("staff_mfa_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = lang.tr("التحقق بخطوتين", "Two-Factor Authentication"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = lang.tr(
                            "أدخل الرمز لتأكيد هويتك والوصول لمساحة الاستعلام",
                            "Enter verification code to access staff read workspace"
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Mutually exclusive mode chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        FilterChip(
                            selected = uiState.mfaMode == MfaMode.TOTP,
                            onClick = { onSetMfaMode(MfaMode.TOTP) },
                            label = { Text(lang.tr("رمز تطبيق المصادقة", "Authenticator Code")) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("staff_mfa_chip_totp"),
                            colors = FilterChipDefaults.filterChipColors()
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        FilterChip(
                            selected = uiState.mfaMode == MfaMode.RECOVERY_CODE,
                            onClick = { onSetMfaMode(MfaMode.RECOVERY_CODE) },
                            label = { Text(lang.tr("رمز الاسترداد", "Recovery Code")) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("staff_mfa_chip_recovery"),
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Inline Error Banner
                    if (uiState.errorMessage != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("staff_mfa_error_banner"),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = uiState.errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Mutually Exclusive Code Field
                    if (uiState.mfaMode == MfaMode.TOTP) {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { if (it.length <= 8) codeInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("staff_mfa_code_input"),
                            label = { Text(lang.tr("رمز التحقق (6 أرقام)", "6-digit Authenticator Code")) },
                            leadingIcon = {
                                Icon(Icons.Default.Key, contentDescription = null)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (codeInput.isNotBlank()) {
                                        val codeToSubmit = codeInput
                                        codeInput = ""
                                        onRetryWithMfa(codeToSubmit, false)
                                    }
                                }
                            ),
                            enabled = !uiState.isSubmitting
                        )
                    } else {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { if (it.length <= 32) codeInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("staff_recovery_code_input"),
                            label = { Text(lang.tr("رمز استرداد الحساب", "Fortify Recovery Code")) },
                            leadingIcon = {
                                Icon(Icons.Default.Key, contentDescription = null)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (codeInput.isNotBlank()) {
                                        val codeToSubmit = codeInput
                                        codeInput = ""
                                        onRetryWithMfa(codeToSubmit, true)
                                    }
                                }
                            ),
                            enabled = !uiState.isSubmitting
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Submit Button (min 48dp)
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            val codeToSubmit = codeInput
                            codeInput = ""
                            onRetryWithMfa(codeToSubmit, uiState.mfaMode == MfaMode.RECOVERY_CODE)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("staff_mfa_submit_button"),
                        enabled = !uiState.isSubmitting && codeInput.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlueLight)
                    ) {
                        if (uiState.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = lang.tr("تأكيد ومتابعة", "Verify and Continue"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Back to Sign-in Button (min 48dp)
                    OutlinedButton(
                        onClick = {
                            codeInput = ""
                            onBackToSignIn()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("staff_mfa_back_button"),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !uiState.isSubmitting
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = lang.tr("العودة لتسجيل الدخول", "Back to Sign In"),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
