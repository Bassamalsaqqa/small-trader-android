package com.example.data.mobile.session

import android.content.Context
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.AuthChallengeRequest
import com.example.data.mobile.readclient.AuthChallengeResponse
import com.example.data.mobile.readclient.AuthLoginRequest
import com.example.data.mobile.readclient.AuthLoginResult
import com.example.data.mobile.readclient.AuthLoginSuccessResponse
import com.example.data.mobile.readclient.AuthLogoutRequest
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.CustomerDirectoryResponse
import com.example.data.mobile.readclient.PaginationQuery
import com.example.data.mobile.readclient.ProductCatalogResponse
import com.example.data.mobile.readclient.ReadClientResult
import com.example.data.mobile.readclient.SmallTraderReadClient
import com.example.data.mobile.readclient.http.OkHttpTransport
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Public interface for staff authentication and read session coordination.
 *
 * Coordinates authentication challenges, logins, company selection, bootstrap validation,
 * encrypted session persistence, scoped read queries, and logout with generation guards.
 */
interface StaffSessionCoordinator {
    /** Current observable lifecycle state. */
    val authState: StaffAuthState

    /** Stable client device installation identity. */
    val installationId: ClientUuid

    /** Current lifecycle generation counter. */
    val currentGeneration: Long

    /**
     * Restores persisted session from secure storage, verifies scope and expiry,
     * and revalidates tenant membership with the server before granting read readiness.
     */
    fun restoreSession(): CoordinatorResult<StaffAuthState>

    /**
     * Requests an authentication challenge nonce and TTL from the server.
     */
    fun requestChallenge(appVersion: String = "1.0"): CoordinatorResult<AuthChallengeResponse>

    /**
     * Authenticates staff with credentials and active challenge nonce.
     * Consumes the challenge immediately to prevent reuse.
     *
     * If MFA_REQUIRED is returned, clears the challenge and transitions to [StaffAuthState.MfaRequired].
     * If multiple companies are returned, transitions to [StaffAuthState.CompanySelectionRequired].
     * If exactly one company is returned, bootstraps and saves the session.
     */
    fun login(
        email: String,
        password: String,
        deviceName: String,
        mfaCode: String? = null,
        recoveryCode: String? = null
    ): CoordinatorResult<StaffAuthState>

    /**
     * Executes the MFA retry sequence per frozen rev2: acquires a FRESH challenge
     * and submits credentials with the supplied [mfaCode] or [recoveryCode].
     */
    fun retryLoginWithMfa(
        email: String,
        password: String,
        deviceName: String,
        mfaCode: String? = null,
        recoveryCode: String? = null
    ): CoordinatorResult<StaffAuthState>

    /**
     * Selects an active company from the returned membership list, executes bootstrap,
     * verifies consistency, persists the session record, and grants read readiness.
     */
    fun selectCompany(companyId: ServerUlid): CoordinatorResult<StaffAuthState>

    /**
     * Switches company for an active session, invalidates in-flight generation,
     * re-bootstraps for the new company, and persists the updated session.
     */
    fun switchCompany(newCompanyId: ServerUlid): CoordinatorResult<StaffAuthState>

    /**
     * Logs out active staff: immediately invalidates generation, denies local reads,
     * attempts remote token revocation, and deletes session credentials from disk.
     */
    fun logout(): LogoutResult

    /**
     * Fetches bounded product catalog for the selected company. Requires [StaffAuthState.ReadReady].
     */
    fun getProducts(query: PaginationQuery = PaginationQuery()): CoordinatorResult<ProductCatalogResponse>

    /**
     * Fetches bounded customer directory for the selected company. Requires [StaffAuthState.ReadReady].
     */
    fun getCustomers(query: PaginationQuery = PaginationQuery()): CoordinatorResult<CustomerDirectoryResponse>

    companion object {
        /**
         * Factory creating a production [StaffSessionCoordinator] using [SecureStaffSessionStore.create],
         * [OkHttpTransport], and [SmallTraderReadClient].
         */
        fun create(
            context: Context,
            baseOrigin: String,
            allowHttpLoopback: Boolean = false,
            authDenialListener: AuthDenialListener? = null,
            clock: CoordinatorClock = SystemCoordinatorClock
        ): StaffSessionCoordinator {
            val sessionStore = SecureStaffSessionStore.create(context)
            val transport = OkHttpTransport(
                configuredOrigin = baseOrigin,
                allowHttpLoopback = allowHttpLoopback
            )
            val readClient = SmallTraderReadClient(
                transport = transport,
                baseOrigin = baseOrigin,
                allowHttpLoopback = allowHttpLoopback
            )
            return DefaultStaffSessionCoordinator(
                readClient = readClient,
                sessionStore = sessionStore,
                authDenialListener = authDenialListener,
                clock = clock
            )
        }
    }
}

/**
 * Standard thread-safe implementation of [StaffSessionCoordinator].
 *
 * Invariants:
 * 1. Global state mutex is NEVER held across blocking network calls.
 * 2. Higher-level generation counter guards against race conditions, ensuring obsolete responses
 *    from late challenge, login, bootstrap, or read calls cannot overwrite newer state, resurrect
 *    sessions after logout, or save obsolete tokens.
 * 3. Stable installation identity is preserved across logouts; never rotated silently on failure.
 * 4. CancellationException and fatal JVM errors are strictly preserved and rethrown.
 * 5. On HTTP 401, fails closed into Locked state and notifies [AuthDenialListener] for outbox locking.
 */
class DefaultStaffSessionCoordinator(
    private val readClient: SmallTraderReadClient,
    private val sessionStore: SecureStaffSessionStore,
    private val authDenialListener: AuthDenialListener? = null,
    private val clock: CoordinatorClock = SystemCoordinatorClock
) : StaffSessionCoordinator {

    private val generation = AtomicLong(1L)
    private val stateLock = Any()

    @Volatile
    private var _authState: StaffAuthState = StaffAuthState.LoggedOut

    override val authState: StaffAuthState
        get() = _authState

    override val currentGeneration: Long
        get() = generation.get()

    override val installationId: ClientUuid
        get() = try {
            sessionStore.installationId
        } catch (e: SessionStorageException) {
            synchronized(stateLock) {
                _authState = StaffAuthState.StorageFailed(e.error.message)
            }
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                _authState = StaffAuthState.StorageFailed("Failed to resolve installation identity")
            }
            throw SessionStorageException(
                SessionStorageError.Corrupt("Corrupt or inaccessible installation identity")
            )
        }

    private var activeChallenge: AuthChallengeResponse? = null
    private var pendingLoginSuccess: PendingLoginContext? = null

    private data class PendingLoginContext(
        val loginResponse: AuthLoginSuccessResponse,
        val loginGeneration: Long
    )

    override fun restoreSession(): CoordinatorResult<StaffAuthState> {
        val opGen = generation.get()
        val deviceId = try {
            installationId
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is VirtualMachineError) throw e
            return CoordinatorResult.Error("Installation identity unreadable: fail closed")
        }

        val readResult = sessionStore.readSession(
            scope = SessionReadScope(expectedDeviceUuid = deviceId),
            nowEpochMs = clock.nowEpochMs()
        )

        return when (readResult) {
            is SessionReadResult.NoSession -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.LoggedOut
                    }
                }
                CoordinatorResult.Success(StaffAuthState.LoggedOut)
            }

            is SessionReadResult.Expired -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.LoggedOut
                    }
                }
                CoordinatorResult.Error("Session expired at ${readResult.error.expiresAt}")
            }

            is SessionReadResult.Locked -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.KeyLocked(readResult.error.message))
                    }
                }
                CoordinatorResult.Error("Key locked or permanently invalidated")
            }

            is SessionReadResult.Unavailable -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.KeyUnavailable(readResult.error.message))
                    }
                }
                CoordinatorResult.Error("Key unavailable")
            }

            is SessionReadResult.ScopeMismatch -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.ScopeMismatch(readResult.error.message))
                    }
                }
                CoordinatorResult.Error("Scope mismatch")
            }

            is SessionReadResult.Corrupt -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.StorageFailed(readResult.error.message)
                    }
                }
                CoordinatorResult.Error("Corrupt session storage")
            }

            is SessionReadResult.Oversized, is SessionReadResult.IoError -> {
                val msg = (readResult as? SessionReadResult.Oversized)?.error?.message
                    ?: (readResult as? SessionReadResult.IoError)?.error?.message
                    ?: "Storage I/O failure"
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.StorageFailed(msg)
                    }
                }
                CoordinatorResult.Error(msg)
            }

            is SessionReadResult.Success -> {
                val record = readResult.session
                val companyId = record.selectedCompanyId
                if (companyId == null) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.Locked(
                                LockReason.ScopeMismatch("Restored session lacks selected company ID")
                            )
                        }
                    }
                    return CoordinatorResult.Error("Restored session has no selected company")
                }

                // Revalidate with server before granting read readiness
                val bootstrapResult = try {
                    readClient.getBootstrap(token = record.token, companyId = companyId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Exception) {
                    ReadClientResult.NetworkUnknown(category = "BOOTSTRAP_NETWORK_ERROR")
                }

                if (opGen != generation.get()) {
                    return CoordinatorResult.StaleOperation()
                }

                when (bootstrapResult) {
                    is ReadClientResult.Success -> {
                        val bootstrap = bootstrapResult.value
                        if (bootstrap.companyId != companyId || bootstrap.staff.id != record.actorUlid) {
                            synchronized(stateLock) {
                                if (opGen == generation.get()) {
                                    _authState = StaffAuthState.Locked(
                                        LockReason.ScopeMismatch("Bootstrap actor or company mismatch")
                                    )
                                }
                            }
                            return CoordinatorResult.Error("Bootstrap consistency check failed")
                        }

                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                _authState = StaffAuthState.ReadReady(session = record, bootstrap = bootstrap)
                                CoordinatorResult.Success(_authState)
                            } else {
                                CoordinatorResult.StaleOperation()
                            }
                        }
                    }

                    is ReadClientResult.Auth401 -> {
                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                            }
                        }
                        emitAuthDenial(companyId = companyId, actorId = record.actorUlid)
                        CoordinatorResult.AuthDenied(bootstrapResult.error.code)
                    }

                    is ReadClientResult.Forbidden403 -> {
                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                            }
                        }
                        CoordinatorResult.Forbidden(bootstrapResult.error.code)
                    }

                    is ReadClientResult.ParseFailure -> {
                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                _authState = StaffAuthState.Locked(
                                    LockReason.ScopeMismatch("Bootstrap response parse or scope validation failure")
                                )
                            }
                        }
                        CoordinatorResult.Error("Bootstrap validation failed: PARSE_ERROR")
                    }

                    else -> {
                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                val cat = (bootstrapResult as? ReadClientResult.NetworkUnknown)?.category
                                    ?: "HTTP_ERROR"
                                _authState = StaffAuthState.TransientError(cat)
                            }
                        }
                        CoordinatorResult.Error("Transient error during bootstrap revalidation")
                    }
                }
            }
        }
    }

    override fun requestChallenge(appVersion: String): CoordinatorResult<AuthChallengeResponse> {
        val opGen = generation.get()
        val deviceId = try {
            installationId
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is VirtualMachineError) throw e
            return CoordinatorResult.Error("Installation identity unreadable: fail closed")
        }

        val request = AuthChallengeRequest(
            deviceId = deviceId,
            appVersion = appVersion,
            platform = "android"
        )

        val result = try {
            readClient.authChallenge(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            ReadClientResult.NetworkUnknown(category = "CHALLENGE_NETWORK_ERROR")
        }

        if (opGen != generation.get()) {
            return CoordinatorResult.StaleOperation()
        }

        return when (result) {
            is ReadClientResult.Success -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        activeChallenge = result.value
                        _authState = StaffAuthState.ChallengeActive(
                            challengeId = result.value.challengeId,
                            expiresAt = result.value.expiresAt
                        )
                        CoordinatorResult.Success(result.value)
                    } else {
                        CoordinatorResult.StaleOperation()
                    }
                }
            }

            else -> {
                val cat = (result as? ReadClientResult.NetworkUnknown)?.category ?: "CHALLENGE_ERROR"
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.TransientError(cat)
                    }
                }
                CoordinatorResult.Error("Failed to acquire authentication challenge")
            }
        }
    }

    override fun login(
        email: String,
        password: String,
        deviceName: String,
        mfaCode: String?,
        recoveryCode: String?
    ): CoordinatorResult<StaffAuthState> {
        require(mfaCode == null || recoveryCode == null) {
            "mfa_code and recovery_code are mutually exclusive; submitting both simultaneously is rejected"
        }

        val opGen: Long
        val challenge: AuthChallengeResponse
        val deviceId: ClientUuid

        synchronized(stateLock) {
            challenge = activeChallenge
                ?: return CoordinatorResult.Error("No active challenge found. Request challenge first.")
            // Single-use challenge: consumed immediately to prevent reuse
            activeChallenge = null
            // New login invalidates prior operations
            opGen = generation.incrementAndGet()
            deviceId = try {
                installationId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is VirtualMachineError) throw e
                return CoordinatorResult.Error("Installation identity unreadable: fail closed")
            }
        }

        val request = AuthLoginRequest(
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            deviceId = deviceId,
            deviceName = deviceName,
            email = email,
            password = password,
            mfaCode = mfaCode,
            recoveryCode = recoveryCode
        )

        val result = try {
            readClient.authLogin(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            ReadClientResult.NetworkUnknown(category = "LOGIN_NETWORK_ERROR")
        }

        if (opGen != generation.get()) {
            return CoordinatorResult.StaleOperation()
        }

        return when (result) {
            is ReadClientResult.Success -> {
                when (val outcome = result.value) {
                    is AuthLoginResult.MfaRequired -> {
                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                _authState = StaffAuthState.MfaRequired(retry = outcome.response.retry)
                                CoordinatorResult.Success(_authState)
                            } else {
                                CoordinatorResult.StaleOperation()
                            }
                        }
                    }

                    is AuthLoginResult.Success -> {
                        val loginSuccess = outcome.response
                        if (loginSuccess.companies.size > 1) {
                            synchronized(stateLock) {
                                if (opGen == generation.get()) {
                                    pendingLoginSuccess = PendingLoginContext(loginSuccess, opGen)
                                    _authState = StaffAuthState.CompanySelectionRequired(
                                        actor = loginSuccess.actor,
                                        availableCompanies = loginSuccess.companies
                                    )
                                    CoordinatorResult.Success(_authState)
                                } else {
                                    CoordinatorResult.StaleOperation()
                                }
                            }
                        } else {
                            // Exactly one company: bootstrap immediately
                            val singleCompany = loginSuccess.companies[0]
                            finalizeCompanyLogin(loginSuccess, singleCompany.id, opGen)
                        }
                    }
                }
            }

            is ReadClientResult.Auth401 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                    }
                }
                emitAuthDenial(companyId = null, actorId = null)
                CoordinatorResult.AuthDenied(result.error.code)
            }

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    }
                }
                CoordinatorResult.Forbidden(result.error.code)
            }

            else -> {
                val cat = (result as? ReadClientResult.NetworkUnknown)?.category ?: "LOGIN_ERROR"
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.TransientError(cat)
                    }
                }
                CoordinatorResult.Error("Login failed")
            }
        }
    }

    override fun retryLoginWithMfa(
        email: String,
        password: String,
        deviceName: String,
        mfaCode: String?,
        recoveryCode: String?
    ): CoordinatorResult<StaffAuthState> {
        require(mfaCode == null || recoveryCode == null) {
            "mfa_code and recovery_code are mutually exclusive; submitting both simultaneously is rejected"
        }

        // Frozen rev2: MFA retry requires acquiring a FRESH challenge first
        val freshChallengeResult = requestChallenge()
        if (freshChallengeResult !is CoordinatorResult.Success) {
            return CoordinatorResult.Error("Failed to acquire fresh challenge for MFA retry")
        }

        return login(
            email = email,
            password = password,
            deviceName = deviceName,
            mfaCode = mfaCode,
            recoveryCode = recoveryCode
        )
    }

    override fun selectCompany(companyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        val opGen: Long
        val pending: PendingLoginContext

        synchronized(stateLock) {
            val state = _authState as? StaffAuthState.CompanySelectionRequired
                ?: return CoordinatorResult.Error("Not in CompanySelectionRequired state")

            pending = pendingLoginSuccess
                ?: return CoordinatorResult.Error("No pending login session to bind")

            if (state.availableCompanies.none { it.id == companyId }) {
                return CoordinatorResult.Error("Company $companyId is not in returned membership list")
            }
            // Company selection advances generation and invalidates earlier selections
            opGen = generation.incrementAndGet()
        }

        return finalizeCompanyLogin(pending.loginResponse, companyId, opGen)
    }

    override fun switchCompany(newCompanyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        val opGen: Long
        val currentSession: StaffSessionRecord

        synchronized(stateLock) {
            val ready = _authState as? StaffAuthState.ReadReady
                ?: return CoordinatorResult.Error("Cannot switch company: no active read-ready session")

            currentSession = ready.session
            // Company switch invalidates older in-flight operations
            opGen = generation.incrementAndGet()
        }

        val bootstrapResult = try {
            readClient.getBootstrap(token = currentSession.token, companyId = newCompanyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            ReadClientResult.NetworkUnknown(category = "SWITCH_COMPANY_NETWORK_ERROR")
        }

        if (opGen != generation.get()) {
            return CoordinatorResult.StaleOperation()
        }

        return when (bootstrapResult) {
            is ReadClientResult.Success -> {
                val bootstrap = bootstrapResult.value
                if (bootstrap.companyId != newCompanyId || bootstrap.staff.id != currentSession.actorUlid) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.Locked(
                                LockReason.ScopeMismatch("Bootstrap actor or company mismatch")
                            )
                        }
                    }
                    return CoordinatorResult.Error("Bootstrap consistency check failed")
                }

                val newRecord = currentSession.copy(selectedCompanyId = newCompanyId)
                try {
                    sessionStore.saveSession(newRecord)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Exception) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.StorageFailed(
                                "Failed to persist switched session: ${e.message}"
                            )
                        }
                    }
                    return CoordinatorResult.Error("Storage save failure on company switch")
                }

                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.ReadReady(session = newRecord, bootstrap = bootstrap)
                        CoordinatorResult.Success(_authState)
                    } else {
                        CoordinatorResult.StaleOperation()
                    }
                }
            }

            is ReadClientResult.Auth401 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                    }
                }
                emitAuthDenial(companyId = newCompanyId, actorId = currentSession.actorUlid)
                CoordinatorResult.AuthDenied(bootstrapResult.error.code)
            }

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    }
                }
                CoordinatorResult.Forbidden(bootstrapResult.error.code)
            }

            is ReadClientResult.ParseFailure -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(
                            LockReason.ScopeMismatch("Bootstrap response parse or scope validation failure")
                        )
                    }
                }
                CoordinatorResult.Error("Bootstrap validation failed: PARSE_ERROR")
            }

            else -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        val cat = (bootstrapResult as? ReadClientResult.NetworkUnknown)?.category
                            ?: "HTTP_ERROR"
                        _authState = StaffAuthState.TransientError(cat)
                    }
                }
                CoordinatorResult.Error("Failed to switch company")
            }
        }
    }

    private fun finalizeCompanyLogin(
        loginSuccess: AuthLoginSuccessResponse,
        companyId: ServerUlid,
        opGen: Long
    ): CoordinatorResult<StaffAuthState> {
        val bootstrapResult = try {
            readClient.getBootstrap(token = loginSuccess.token, companyId = companyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            ReadClientResult.NetworkUnknown(category = "BOOTSTRAP_NETWORK_ERROR")
        }

        if (opGen != generation.get()) {
            return CoordinatorResult.StaleOperation()
        }

        return when (bootstrapResult) {
            is ReadClientResult.Success -> {
                val bootstrap = bootstrapResult.value
                if (bootstrap.companyId != companyId || bootstrap.staff.id != loginSuccess.actor.id) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.Locked(
                                LockReason.ScopeMismatch("Bootstrap actor or company mismatch")
                            )
                        }
                    }
                    return CoordinatorResult.Error("Bootstrap consistency check failed")
                }

                val record = StaffSessionRecord(
                    token = loginSuccess.token,
                    actorUlid = loginSuccess.actor.id,
                    deviceUuid = installationId,
                    passwordEpoch = loginSuccess.actor.passwordEpoch,
                    expiresAt = loginSuccess.expiresAt,
                    selectedCompanyId = companyId,
                    roleLabel = loginSuccess.actor.role,
                    actorName = loginSuccess.actor.name,
                    actorEmail = loginSuccess.actor.email,
                    createdAt = clock.nowIsoUtc()
                )

                try {
                    sessionStore.saveSession(record)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (e: Exception) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.StorageFailed(
                                "Failed to persist session: ${e.message}"
                            )
                        }
                    }
                    return CoordinatorResult.Error("Storage save failure")
                }

                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        pendingLoginSuccess = null
                        _authState = StaffAuthState.ReadReady(session = record, bootstrap = bootstrap)
                        CoordinatorResult.Success(_authState)
                    } else {
                        CoordinatorResult.StaleOperation()
                    }
                }
            }

            is ReadClientResult.Auth401 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                    }
                }
                emitAuthDenial(companyId = companyId, actorId = loginSuccess.actor.id)
                CoordinatorResult.AuthDenied(bootstrapResult.error.code)
            }

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    }
                }
                CoordinatorResult.Forbidden(bootstrapResult.error.code)
            }

            is ReadClientResult.ParseFailure -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(
                            LockReason.ScopeMismatch("Bootstrap response parse or scope validation failure")
                        )
                    }
                }
                CoordinatorResult.Error("Bootstrap validation failed: PARSE_ERROR")
            }

            else -> {
                val cat = (bootstrapResult as? ReadClientResult.NetworkUnknown)?.category
                    ?: "BOOTSTRAP_ERROR"
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.TransientError(cat)
                    }
                }
                CoordinatorResult.Error("Bootstrap failed")
            }
        }
    }

    override fun logout(): LogoutResult {
        val opGen: Long
        val activeToken: String?
        val deviceId: ClientUuid

        synchronized(stateLock) {
            // Immediately invalidates outstanding generation and denies local reads
            opGen = generation.incrementAndGet()
            activeToken = when (val s = _authState) {
                is StaffAuthState.ReadReady -> s.session.token
                is StaffAuthState.CompanySelectionRequired -> pendingLoginSuccess?.loginResponse?.token
                else -> null
            }
            activeChallenge = null
            pendingLoginSuccess = null
            _authState = StaffAuthState.LoggedOut
            deviceId = try {
                sessionStore.installationId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is VirtualMachineError) throw e
                ClientUuid.random()
            }
        }

        // Attempt remote token revocation if an active token was held
        var remoteStatus = RemoteRevocationStatus.NotAttempted
        if (activeToken != null) {
            remoteStatus = try {
                val res = readClient.authLogout(activeToken, AuthLogoutRequest(deviceId = deviceId))
                when (res) {
                    is ReadClientResult.Success -> RemoteRevocationStatus.Revoked
                    is ReadClientResult.Auth401 -> RemoteRevocationStatus.AlreadyRevokedOrExpired
                    else -> RemoteRevocationStatus.Unknown
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Exception) {
                RemoteRevocationStatus.Unknown
            }
        }

        // Clear local credentials from disk without deleting identity store or outbox queues
        var clearFailed = false
        var clearErrorMsg = ""
        try {
            sessionStore.clearSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            clearFailed = true
            clearErrorMsg = e.message ?: "Failed to clear session from disk"
        }

        if (clearFailed) {
            synchronized(stateLock) {
                _authState = StaffAuthState.Locked(LockReason.StorageClearFailed(clearErrorMsg))
            }
            return LogoutResult.StorageClearFailed(
                remoteRevocation = remoteStatus,
                message = clearErrorMsg
            )
        }

        synchronized(stateLock) {
            if (opGen == generation.get()) {
                _authState = StaffAuthState.LoggedOut
            }
        }
        return LogoutResult.Success(remoteRevocation = remoteStatus)
    }

    override fun getProducts(query: PaginationQuery): CoordinatorResult<ProductCatalogResponse> {
        val opGen: Long
        val token: String
        val companyId: ServerUlid
        val actorUlid: ServerUlid

        synchronized(stateLock) {
            val ready = _authState as? StaffAuthState.ReadReady
                ?: return CoordinatorResult.Error("Not in ReadReady state")

            val expiresEpoch = try {
                SessionValidation.parseRfc3339ToEpochMs(ready.session.expiresAt, "expires_at")
            } catch (_: Exception) {
                0L
            }
            if (clock.nowEpochMs() >= expiresEpoch) {
                _authState = StaffAuthState.LoggedOut
                return CoordinatorResult.Error("Session expired at ${ready.session.expiresAt}")
            }

            token = ready.session.token
            companyId = ready.session.selectedCompanyId
                ?: return CoordinatorResult.Error("Session lacks selected company ID")
            actorUlid = ready.session.actorUlid
            opGen = generation.get()
        }

        val result = try {
            readClient.getProducts(token, companyId, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            ReadClientResult.NetworkUnknown(category = "PRODUCTS_NETWORK_ERROR")
        }

        if (opGen != generation.get()) {
            return CoordinatorResult.StaleOperation()
        }

        return when (result) {
            is ReadClientResult.Success -> CoordinatorResult.Success(result.value)

            is ReadClientResult.Auth401 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                    }
                }
                emitAuthDenial(companyId = companyId, actorId = actorUlid)
                CoordinatorResult.AuthDenied(result.error.code)
            }

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    }
                }
                CoordinatorResult.Forbidden(result.error.code)
            }

            else -> CoordinatorResult.Error("Failed to fetch product catalog")
        }
    }

    override fun getCustomers(query: PaginationQuery): CoordinatorResult<CustomerDirectoryResponse> {
        val opGen: Long
        val token: String
        val companyId: ServerUlid
        val actorUlid: ServerUlid

        synchronized(stateLock) {
            val ready = _authState as? StaffAuthState.ReadReady
                ?: return CoordinatorResult.Error("Not in ReadReady state")

            val expiresEpoch = try {
                SessionValidation.parseRfc3339ToEpochMs(ready.session.expiresAt, "expires_at")
            } catch (_: Exception) {
                0L
            }
            if (clock.nowEpochMs() >= expiresEpoch) {
                _authState = StaffAuthState.LoggedOut
                return CoordinatorResult.Error("Session expired at ${ready.session.expiresAt}")
            }

            token = ready.session.token
            companyId = ready.session.selectedCompanyId
                ?: return CoordinatorResult.Error("Session lacks selected company ID")
            actorUlid = ready.session.actorUlid
            opGen = generation.get()
        }

        val result = try {
            readClient.getCustomers(token, companyId, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Exception) {
            ReadClientResult.NetworkUnknown(category = "CUSTOMERS_NETWORK_ERROR")
        }

        if (opGen != generation.get()) {
            return CoordinatorResult.StaleOperation()
        }

        return when (result) {
            is ReadClientResult.Success -> CoordinatorResult.Success(result.value)

            is ReadClientResult.Auth401 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                    }
                }
                emitAuthDenial(companyId = companyId, actorId = actorUlid)
                CoordinatorResult.AuthDenied(result.error.code)
            }

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    }
                }
                CoordinatorResult.Forbidden(result.error.code)
            }

            else -> CoordinatorResult.Error("Failed to fetch customer directory")
        }
    }

    private fun emitAuthDenial(companyId: ServerUlid?, actorId: ServerUlid?) {
        val devId = try {
            installationId
        } catch (_: Exception) {
            ClientUuid.random()
        }
        val scope = AuthDenialScope(
            companyId = companyId,
            actorId = actorId,
            deviceId = devId,
            deniedAt = clock.nowIsoUtc()
        )
        authDenialListener?.onAuthDenied(scope)
    }
}
