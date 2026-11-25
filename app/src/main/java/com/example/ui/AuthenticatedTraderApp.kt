package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.SmallTraderApp
import com.example.data.mobile.offline.authorizesCapture
import com.example.data.CurrentAuthScope
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.integration.OutboxAuthDenialBridge
import com.example.data.mobile.session.AndroidKeyStoreCryptoEngine
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.OutboxRepositoryImpl
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.session.AuthDenialListener
import com.example.data.mobile.session.StaffAuthState
import com.example.ui.staff.StaffCompanySelectScreen
import com.example.ui.staff.StaffLockedScreen
import com.example.ui.staff.StaffMfaScreen
import com.example.ui.staff.StaffOfflineScreen
import com.example.ui.staff.StaffOutboxRecoveryScreen
import com.example.ui.staff.StaffSignInScreen
import com.example.ui.staff.StaffStorageFailedScreen
import com.example.ui.staff.TraderRepositoryLoadFailedScreen
import com.example.ui.staff.StaffUnconfiguredScreen
import com.example.ui.staff.StaffWorkspacePhase
import com.example.ui.staff.StaffWorkspaceViewModel
import com.example.ui.theme.SmallTraderTheme
import com.example.viewmodel.TraderViewModel
import com.example.viewmodel.TraderViewModelFactory
import kotlinx.coroutines.Dispatchers

/**
 * Root Composable for the Full Authenticated Small Trader App Shell.
 *
 * Drives the secure staff authentication flow (initializing, sign-in, MFA, company selection,
 * outbox health check, locked, storage recovery) and, upon reaching READ_READY, instantiates
 * the authoritative ProductionTraderRepository and renders the full SmallTraderApp with all
 * approved icon-focused merchant screens.
 */
@Composable
fun AuthenticatedTraderApp(
    configuredOrigin: String,
    allowHttpLoopback: Boolean = false,
    authDenialListener: AuthDenialListener? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val effectiveAuthListener = remember(context) {
        authDenialListener ?: OutboxAuthDenialBridge.create(context.applicationContext)
    }

    val staffViewModel: StaffWorkspaceViewModel = viewModel(
        factory = StaffWorkspaceViewModel.Factory(
            context = context,
            configuredOrigin = configuredOrigin,
            allowHttpLoopback = allowHttpLoopback,
            ioDispatcher = Dispatchers.IO,
            authDenialListener = effectiveAuthListener
        )
    )

    val uiState by staffViewModel.uiState.collectAsStateWithLifecycle()
    var enterRestrictedOffline by remember(uiState.phase) { androidx.compose.runtime.mutableStateOf(false) }

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
                StaffWorkspacePhase.OUTBOX_RECOVERY_REQUIRED -> {
                    StaffOutboxRecoveryScreen(
                        healthResult = uiState.outboxHealthResult,
                        lang = uiState.language,
                        onRetry = { staffViewModel.retryOutboxHealth() },
                        isRetrying = uiState.isOutboxRetrying
                    )
                }
                StaffWorkspacePhase.SIGN_IN -> {
                    if (enterRestrictedOffline) RestrictedOfflineScreen(configuredOrigin,
                        onRetry = { enterRestrictedOffline = false; staffViewModel.restoreSession() },
                        onLogout = { enterRestrictedOffline = false; staffViewModel.logout() })
                    else androidx.compose.foundation.layout.Column {
                        if (com.example.BuildConfig.OFFLINE_OWNER_PILOT) androidx.compose.material3.TextButton(
                            onClick = { enterRestrictedOffline = true }) {
                            androidx.compose.material3.Text("Restricted offline capture / البيع دون اتصال بصلاحية محدودة")
                        }
                    StaffSignInScreen(
                        uiState = uiState,
                        onSignIn = { email, password, rememberSession ->
                            staffViewModel.signIn(email, password, rememberSession = rememberSession)
                        },
                        onToggleLanguage = { staffViewModel.toggleLanguage() },
                        onToggleTheme = { staffViewModel.toggleTheme() }
                    )
                    }
                }
                StaffWorkspacePhase.MFA_REQUIRED -> {
                    StaffMfaScreen(
                        uiState = uiState,
                        onRetryWithMfa = { code, isRecovery ->
                            staffViewModel.retryWithMfa(codeOverride = code, isRecovery = isRecovery)
                        },
                        onSetMfaMode = { staffViewModel.setMfaMode(it) },
                        onBackToSignIn = { staffViewModel.logout() }
                    )
                }
                StaffWorkspacePhase.COMPANY_SELECTION -> {
                    StaffCompanySelectScreen(
                        uiState = uiState,
                        onSelectCompany = { companyId ->
                            staffViewModel.selectCompany(companyId)
                        },
                        onCancel = { staffViewModel.cancelCompanySelection() }
                    )
                }
                StaffWorkspacePhase.READ_READY -> {
                    val coord = staffViewModel.getCoordinator()
                    val readyState = coord?.authState as? StaffAuthState.ReadReady
                    val transport = coord?.transport
                    if (readyState != null && transport != null) {
                        val companyId = readyState.bootstrap.companyId.value
                        val actorId = readyState.session.actorUlid.value
                        val deviceId = readyState.session.deviceUuid.value
                        val generation = coord.currentGeneration

                        var loadAttempt by remember(companyId, actorId, deviceId, generation) { androidx.compose.runtime.mutableIntStateOf(0) }
                        val repositoryLoad by androidx.compose.runtime.key(companyId, actorId, deviceId, generation, loadAttempt) {
                            androidx.compose.runtime.produceState<Result<ProductionTraderRepository>?>(
                                initialValue = null
                            ) {
                                var created: ProductionTraderRepository? = null
                                try {
                                    val loaded = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        val db = OutboxDatabase.buildDatabase(context.applicationContext)
                                        val leaseStore = com.example.data.mobile.offline.SqliteOfflineLeaseStore(context.applicationContext) { db.openHelper.writableDatabase }
                                        val cryptoEngine = AndroidKeyStoreCryptoEngine()
                                        val commandStore = RoomMerchantCommandStore(db, cryptoEngine)
                                        val authScopeProvider: () -> CurrentAuthScope? = {
                                            val currentCoord = staffViewModel.getCoordinator()
                                            val currentReady = currentCoord?.authState as? StaffAuthState.ReadReady
                                            if (currentReady != null && currentCoord.isScopeActive(currentCoord.currentGeneration, currentReady.bootstrap.companyId)) {
                                                CurrentAuthScope(
                                                    companyId = currentReady.bootstrap.companyId,
                                                    actorId = currentReady.session.actorUlid,
                                                    deviceId = currentReady.session.deviceUuid,
                                                    generation = currentCoord.currentGeneration,
                                                    token = currentReady.session.token
                                                )
                                            } else {
                                                null
                                            }
                                        }
                                        val captureScope = requireNotNull(authScopeProvider())
                                        val outboxRepo = OutboxRepositoryImpl(database = db, requireLeaseAuthorization = true,
                                            leaseAuthority = { lease, event ->
                                                authScopeProvider() == captureScope && captureScope.companyId == lease.companyId &&
                                                    captureScope.actorId == lease.actorId && captureScope.deviceId == lease.deviceId &&
                                                leaseStore.authorizesCapture(lease, event)
                                            })
                                        try { ProductionTraderRepository.fromReadReady(
                                            readReady = readyState,
                                            outboxRepository = outboxRepo,
                                            transport = transport,
                                            baseOrigin = configuredOrigin,
                                            commandStore = commandStore,
                                            offlineLeaseStore = leaseStore,
                                            authScopeProvider = authScopeProvider,
                                            onClose = { db.close() }
                                        ).also { created = it } } catch (error: Exception) { db.close(); throw error }
                                    }
                                    created = loaded
                                    value = Result.success(loaded)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    created?.close()
                                    throw cancelled
                                } catch (error: Exception) {
                                    created?.close()
                                    value = Result.failure(error)
                                }
                            }
                        }
                        val repository = repositoryLoad?.getOrNull()
                        if (repository == null) {
                            if (repositoryLoad == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                TraderRepositoryLoadFailedScreen(
                                    error = repositoryLoad?.exceptionOrNull(),
                                    lang = uiState.language,
                                    onRetry = { loadAttempt++ },
                                    onSignOut = { staffViewModel.logout() }
                                )
                            }
                            return@Box
                        }

                        androidx.compose.runtime.DisposableEffect(repository) {
                            onDispose {
                                repository.close()
                            }
                        }

                        val traderViewModel: TraderViewModel = viewModel(
                            key = "trader_${companyId}_${actorId}_${deviceId}_gen_${generation}",
                            factory = TraderViewModelFactory(repository)
                        )
                        androidx.compose.foundation.layout.Column {
                            if (com.example.BuildConfig.OFFLINE_OWNER_PILOT) {
                                OfflinePilotControls(repository, traderViewModel)
                            }
                            SmallTraderApp(traderViewModel = traderViewModel)
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                StaffWorkspacePhase.LOCKED -> {
                    StaffLockedScreen(
                        reason = uiState.lockedReason,
                        lang = uiState.language,
                        onReAuthenticate = { staffViewModel.logout() }
                    )
                }
                StaffWorkspacePhase.STORAGE_FAILED -> {
                    StaffStorageFailedScreen(
                        message = uiState.storageErrorMessage,
                        lang = uiState.language,
                        onRetry = { staffViewModel.restoreSession() },
                        onReAuthenticate = { staffViewModel.logout() }
                    )
                }
                StaffWorkspacePhase.OFFLINE_ERROR -> {
                    if (com.example.BuildConfig.OFFLINE_OWNER_PILOT) {
                        RestrictedOfflineScreen(configuredOrigin,
                            onRetry = { staffViewModel.restoreSession() }, onLogout = { staffViewModel.logout() })
                    } else StaffOfflineScreen(
                        category = uiState.offlineErrorMessage,
                        lang = uiState.language,
                        onRetry = { staffViewModel.restoreSession() }
                    )
                }
            }
        }
    }
}
