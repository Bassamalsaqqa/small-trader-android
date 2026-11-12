package com.example.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.mobile.session.AuthDenialListener
import com.example.ui.theme.SmallTraderTheme
import kotlinx.coroutines.Dispatchers

/**
 * Root Composable for the Staff Read Workspace.
 *
 * Exclusively routes to staff authentication, company selection, read catalog,
 * customer directory, or explicit error/locked states. Never falls back to demo merchant screens.
 */
@Composable
fun StaffWorkspaceApp(
    configuredOrigin: String,
    allowHttpLoopback: Boolean = false,
    authDenialListener: AuthDenialListener? = null,
    viewModel: StaffWorkspaceViewModel = viewModel(
        factory = StaffWorkspaceViewModel.Factory(
            context = LocalContext.current,
            configuredOrigin = configuredOrigin,
            allowHttpLoopback = allowHttpLoopback,
            ioDispatcher = Dispatchers.IO,
            authDenialListener = authDenialListener
        )
    ),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SmallTraderTheme(
        darkTheme = uiState.isDarkTheme,
        isRtl = uiState.language.isRtl
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (uiState.phase) {
                StaffWorkspacePhase.UNCONFIGURED -> {
                    StaffUnconfiguredScreen(lang = uiState.language)
                }
                StaffWorkspacePhase.INITIALIZING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                StaffWorkspacePhase.SIGN_IN -> {
                    StaffSignInScreen(
                        uiState = uiState,
                        onSignIn = { email, password ->
                            viewModel.signIn(email, password)
                        },
                        onToggleLanguage = { viewModel.toggleLanguage() },
                        onToggleTheme = { viewModel.toggleTheme() }
                    )
                }
                StaffWorkspacePhase.MFA_REQUIRED -> {
                    StaffMfaScreen(
                        uiState = uiState,
                        onRetryWithMfa = { code, isRecovery ->
                            viewModel.retryWithMfa(codeOverride = code, isRecovery = isRecovery)
                        },
                        onSetMfaMode = { viewModel.setMfaMode(it) },
                        onBackToSignIn = { viewModel.logout() }
                    )
                }
                StaffWorkspacePhase.COMPANY_SELECTION -> {
                    StaffCompanySelectScreen(
                        uiState = uiState,
                        onSelectCompany = { companyId ->
                            viewModel.selectCompany(companyId)
                        },
                        onCancel = { viewModel.cancelCompanySelection() }
                    )
                }
                StaffWorkspacePhase.READ_READY -> {
                    StaffWorkspaceScreen(
                        uiState = uiState,
                        onSelectTab = { viewModel.selectTab(it) },
                        onLoadMoreProducts = { viewModel.loadProducts(loadMore = true) },
                        onRefreshProducts = { viewModel.loadProducts(refresh = true) },
                        onSearchProducts = { viewModel.updateProductSearchQuery(it) },
                        onLoadMoreCustomers = { viewModel.loadCustomers(loadMore = true) },
                        onRefreshCustomers = { viewModel.loadCustomers(refresh = true) },
                        onSearchCustomers = { viewModel.updateCustomerSearchQuery(it) },
                        onSwitchCompany = { viewModel.openCompanySelection() },
                        onLogout = { viewModel.logout() },
                        onToggleLanguage = { viewModel.toggleLanguage() },
                        onToggleTheme = { viewModel.toggleTheme() }
                    )
                }
                StaffWorkspacePhase.LOCKED -> {
                    StaffLockedScreen(
                        reason = uiState.lockedReason,
                        lang = uiState.language,
                        onReAuthenticate = { viewModel.logout() }
                    )
                }
                StaffWorkspacePhase.STORAGE_FAILED -> {
                    StaffStorageFailedScreen(
                        message = uiState.storageErrorMessage,
                        lang = uiState.language,
                        onRetry = { viewModel.restoreSession() },
                        onReAuthenticate = { viewModel.logout() }
                    )
                }
                StaffWorkspacePhase.OFFLINE_ERROR -> {
                    StaffOfflineScreen(
                        category = uiState.offlineErrorMessage,
                        lang = uiState.language,
                        onRetry = { viewModel.restoreSession() }
                    )
                }
            }
        }
    }
}
