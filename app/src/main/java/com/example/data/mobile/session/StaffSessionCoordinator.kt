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
     * Verifies whether the specified lifecycle generation and company ID are still active.
     *
     * Callers (such as UI/ViewModel coroutines) must validate that a received query outcome
     * is still current before updating UI presentation state, as a concurrent company switch
     * or logout could have completed after query return.
     */
    fun isScopeActive(generation: Long, companyId: ServerUlid): Boolean

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
 * 2. Atomic monotonic generation counter guards against race conditions, ensuring obsolete responses
 *    from late challenge, login, bootstrap, or read calls cannot overwrite newer state, resurrect
 *    sessions after logout, or save obsolete tokens.
 * 3. Stable installation identity is preserved across logouts; never rotated silently or fabricated on failure.
 * 4. CancellationException and fatal JVM errors are strictly preserved and rethrown.
 * 5. On HTTP 401 on session token, fails closed into Locked state and notifies [AuthDenialListener] for outbox locking;
 *    wrong-password credential failures on login do NOT emit outbox denial hooks.
 * 6. Local credential purge on logout occurs atomically BEFORE remote revocation network calls.
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

    // Retain private lifecycle credential context across transient errors
    private var activeTokenContext: String? = null
    private var activeChallenge: AuthChallengeResponse? = null
    private var pendingLoginSuccess: PendingLoginContext? = null

    private data class PendingLoginContext(
        val loginResponse: AuthLoginSuccessResponse,
        val loginGeneration: Long
    )

    override val authState: StaffAuthState
        get() = _authState

    override val currentGeneration: Long
        get() = generation.get()

    override val installationId: ClientUuid
        get() = try {
            sessionStore.installationId
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: SessionStorageException) {
            throw e
        } catch (_: Exception) {
            throw SessionStorageException(SessionStorageError.IoError("Installation identity unreadable"))
        }

    override fun isScopeActive(generation: Long, companyId: ServerUlid): Boolean {
        synchronized(stateLock) {
            if (generation != this.generation.get()) return false
            val ready = _authState as? StaffAuthState.ReadReady ?: return false
            return ready.session.selectedCompanyId == companyId
        }
    }

    private fun compareAndPersist(
        opGen: Long,
        record: StaffSessionRecord,
        bootstrap: BootstrapResponse,
        persist: Boolean = true
    ): CoordinatorResult<StaffAuthState> {
        synchronized(stateLock) {
            if (opGen != generation.get()) {
                return CoordinatorResult.StaleOperation()
            }

            // Recheck token expiry under lock before persist/read-ready
            val expiresEpoch = try {
                SessionValidation.parseRfc3339ToEpochMs(record.expiresAt, "expires_at")
            } catch (_: Exception) {
                0L
            }
            if (clock.nowEpochMs() >= expiresEpoch) {
                _authState = StaffAuthState.LoggedOut
                return CoordinatorResult.Error("Session token expired during bootstrap")
            }

            try {
                if (persist) sessionStore.saveSession(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (_: Exception) {
                _authState = StaffAuthState.StorageFailed("Failed to persist session to secure storage")
                return CoordinatorResult.Error("Storage save failure")
            }

            pendingLoginSuccess = null
            activeTokenContext = record.token
            _authState = StaffAuthState.ReadReady(session = record, bootstrap = bootstrap)
            return CoordinatorResult.Success(
                value = _authState,
                generation = opGen,
                companyId = record.selectedCompanyId,
                actorId = record.actorUlid
            )
        }
    }

    private fun handleBootstrapResult(
        opGen: Long,
        bootstrapResult: ReadClientResult<BootstrapResponse>,
        token: String,
        actorUlid: ServerUlid,
        companyId: ServerUlid,
        passwordEpoch: String,
        roleLabel: String?,
        actorName: String?,
        actorEmail: String?,
        expiresAt: String,
        restoredRecord: StaffSessionRecord? = null
    ): CoordinatorResult<StaffAuthState> {
        synchronized(stateLock) {
            if (opGen != generation.get()) {
                return CoordinatorResult.StaleOperation()
            }
        }

        return when (bootstrapResult) {
            is ReadClientResult.Success -> {
                val bootstrap = bootstrapResult.value
                if (bootstrap.companyId != companyId || bootstrap.staff.id != actorUlid) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.Locked(
                                LockReason.ScopeMismatch("Bootstrap actor or company mismatch")
                            )
                        }
                    }
                    return CoordinatorResult.Error("Bootstrap consistency check failed")
                }

                val devId = synchronized(stateLock) {
                    if (opGen != generation.get()) return CoordinatorResult.StaleOperation()
                    try {
                        installationId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: VirtualMachineError) {
                        throw e
                    } catch (_: Exception) {
                        _authState = StaffAuthState.StorageFailed("Installation identity unreadable")
                        return CoordinatorResult.Error("Installation identity unreadable: fail closed")
                    }
                }

                val record = restoredRecord ?: StaffSessionRecord(
                    token = token,
                    actorUlid = actorUlid,
                    deviceUuid = devId,
                    passwordEpoch = passwordEpoch,
                    expiresAt = expiresAt,
                    selectedCompanyId = companyId,
                    roleLabel = roleLabel,
                    actorName = actorName,
                    actorEmail = actorEmail,
                    createdAt = clock.nowIsoUtc()
                )

                compareAndPersist(opGen, record, bootstrap, persist = restoredRecord == null)
            }

            is ReadClientResult.Auth401 -> handleTokenDenied(opGen, companyId, actorUlid)

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                }
                CoordinatorResult.Forbidden()
            }

            is ReadClientResult.ParseFailure -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.Locked(
                        LockReason.ScopeMismatch("Bootstrap response parse or scope validation failure")
                    )
                }
                CoordinatorResult.Error("Bootstrap validation failed: PARSE_ERROR")
            }

            else -> {
                val cat = (bootstrapResult as? ReadClientResult.NetworkUnknown)?.category
                    ?: "BOOTSTRAP_ERROR"
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.TransientError(cat)
                }
                CoordinatorResult.Error("Bootstrap failed")
            }
        }
    }

    private fun handleTokenDenied(
        opGen: Long, companyId: ServerUlid, actorId: ServerUlid
    ): CoordinatorResult<Nothing> {
        val scope = synchronized(stateLock) {
            if (opGen != generation.get()) return CoordinatorResult.StaleOperation()
            _authState = StaffAuthState.Locked(LockReason.AuthDenied())
            val deviceId = try {
                sessionStore.installationId
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _authState = StaffAuthState.StorageFailed("Installation identity unreadable")
                return CoordinatorResult.Error("Installation identity unreadable")
            }
            AuthDenialScope(companyId, actorId, deviceId, clock.nowIsoUtc())
        }
        // Deliver the immutable scope of the accepted denial outside the state monitor.
        // A later login cannot change this event's actor/company/device identity.
        try {
            authDenialListener?.onAuthDenied(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return CoordinatorResult.Error("Auth denial notification failed")
        }
        return CoordinatorResult.AuthDenied()
    }

    override fun restoreSession(): CoordinatorResult<StaffAuthState> {
        val opGen: Long
        val deviceId: ClientUuid

        synchronized(stateLock) {
            if (_authState is StaffAuthState.Locked && (_authState as StaffAuthState.Locked).reason is LockReason.StorageClearFailed) {
                return CoordinatorResult.Error("Cannot restore session: storage clear failed on prior logout")
            }

            opGen = generation.incrementAndGet()
            activeChallenge = null
            pendingLoginSuccess = null
            activeTokenContext = null
            _authState = StaffAuthState.LoggedOut

            deviceId = try {
                installationId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is VirtualMachineError) throw e
                _authState = StaffAuthState.StorageFailed("Installation identity unreadable")
                return CoordinatorResult.Error("Installation identity unreadable: fail closed")
            }
        }

        val readResult = try {
            sessionStore.readSession(
                scope = SessionReadScope(expectedDeviceUuid = deviceId),
                nowEpochMs = clock.nowEpochMs()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (_: Exception) {
            synchronized(stateLock) {
                if (opGen == generation.get()) {
                    _authState = StaffAuthState.StorageFailed("Session read failed")
                }
            }
            return CoordinatorResult.Error("Storage read failure")
        }

        return when (readResult) {
            is SessionReadResult.NoSession -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.LoggedOut
                }
                CoordinatorResult.Success(StaffAuthState.LoggedOut, generation = opGen)
            }

            is SessionReadResult.Expired -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.LoggedOut
                }
                CoordinatorResult.Error("Session expired at ${readResult.error.expiresAt}")
            }

            is SessionReadResult.Locked -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.Locked(LockReason.KeyLocked())
                }
                CoordinatorResult.Error("Key locked or permanently invalidated")
            }

            is SessionReadResult.Unavailable -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.Locked(LockReason.KeyUnavailable())
                }
                CoordinatorResult.Error("Key unavailable")
            }

            is SessionReadResult.ScopeMismatch -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.Locked(LockReason.ScopeMismatch())
                }
                CoordinatorResult.Error("Scope mismatch")
            }

            is SessionReadResult.Corrupt -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.StorageFailed("Corrupt session storage")
                }
                CoordinatorResult.Error("Corrupt session storage")
            }

            is SessionReadResult.Oversized, is SessionReadResult.IoError -> {
                synchronized(stateLock) {
                    if (opGen != generation.get()) {
                        return CoordinatorResult.StaleOperation()
                    }
                    _authState = StaffAuthState.StorageFailed("Storage I/O failure")
                }
                CoordinatorResult.Error("Storage I/O failure")
            }

            is SessionReadResult.Success -> {
                val record = readResult.session
                synchronized(stateLock) {
                    if (opGen != generation.get()) return CoordinatorResult.StaleOperation()
                    activeTokenContext = record.token
                }
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

                // Check initial expiry before making network call
                val expiresMs = try {
                    SessionValidation.parseRfc3339ToEpochMs(record.expiresAt, "expires_at")
                } catch (_: Exception) {
                    0L
                }
                if (clock.nowEpochMs() >= expiresMs) {
                    synchronized(stateLock) {
                        if (opGen == generation.get()) {
                            _authState = StaffAuthState.LoggedOut
                        }
                    }
                    return CoordinatorResult.Error("Session expired at ${record.expiresAt}")
                }

                // Revalidate with server before granting read readiness
                val bootstrapResult = try {
                    readClient.getBootstrap(token = record.token, companyId = companyId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VirtualMachineError) {
                    throw e
                } catch (_: Exception) {
                    ReadClientResult.NetworkUnknown(category = "BOOTSTRAP_NETWORK_ERROR")
                }

                handleBootstrapResult(
                    opGen = opGen,
                    bootstrapResult = bootstrapResult,
                    token = record.token,
                    actorUlid = record.actorUlid,
                    companyId = companyId,
                    passwordEpoch = record.passwordEpoch,
                    roleLabel = record.roleLabel,
                    actorName = record.actorName,
                    actorEmail = record.actorEmail,
                    expiresAt = record.expiresAt,
                    restoredRecord = record
                )
            }
        }
    }

    override fun requestChallenge(appVersion: String): CoordinatorResult<AuthChallengeResponse> {
        val opGen: Long
        val deviceId: ClientUuid

        synchronized(stateLock) {
            if (_authState is StaffAuthState.ReadReady) {
                return CoordinatorResult.Error("Cannot request challenge: active session is ReadReady")
            }
            if (_authState is StaffAuthState.Locked && (_authState as StaffAuthState.Locked).reason is LockReason.StorageClearFailed) {
                return CoordinatorResult.Error("Cannot request challenge: storage clear failed on prior logout")
            }

            opGen = generation.incrementAndGet()
            activeChallenge = null

            deviceId = try {
                installationId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is VirtualMachineError) throw e
                _authState = StaffAuthState.StorageFailed("Installation identity unreadable")
                return CoordinatorResult.Error("Installation identity unreadable: fail closed")
            }
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
        } catch (_: Exception) {
            ReadClientResult.NetworkUnknown(category = "CHALLENGE_NETWORK_ERROR")
        }

        synchronized(stateLock) {
            if (opGen != generation.get()) {
                return CoordinatorResult.StaleOperation()
            }
            if (_authState is StaffAuthState.ReadReady) {
                return CoordinatorResult.StaleOperation("Active session is ReadReady")
            }

            return when (result) {
                is ReadClientResult.Success -> {
                    activeChallenge = result.value
                    _authState = StaffAuthState.ChallengeActive(
                        challengeId = result.value.challengeId,
                        expiresAt = result.value.expiresAt
                    )
                    CoordinatorResult.Success(result.value, generation = opGen)
                }

                else -> {
                    val cat = (result as? ReadClientResult.NetworkUnknown)?.category ?: "CHALLENGE_ERROR"
                    _authState = StaffAuthState.TransientError(cat)
                    CoordinatorResult.Error("Failed to acquire authentication challenge")
                }
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
            if (_authState is StaffAuthState.Locked && (_authState as StaffAuthState.Locked).reason is LockReason.StorageClearFailed) {
                return CoordinatorResult.Error("Cannot login: storage clear failed on prior logout")
            }

            challenge = activeChallenge
                ?: return CoordinatorResult.Error("No active challenge found. Request challenge first.")

            // Single-use challenge: consumed immediately to prevent reuse
            activeChallenge = null

            // One-time challenge TTL validation BEFORE login wire
            val challengeExpiresEpoch = try {
                SessionValidation.parseRfc3339ToEpochMs(challenge.expiresAt, "expires_at")
            } catch (_: Exception) {
                0L
            }
            if (clock.nowEpochMs() >= challengeExpiresEpoch) {
                _authState = StaffAuthState.LoggedOut
                return CoordinatorResult.Error("Authentication challenge expired")
            }

            opGen = generation.incrementAndGet()
            deviceId = try {
                installationId
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is VirtualMachineError) throw e
                _authState = StaffAuthState.StorageFailed("Installation identity unreadable")
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
        } catch (_: Exception) {
            ReadClientResult.NetworkUnknown(category = "LOGIN_NETWORK_ERROR")
        }

        synchronized(stateLock) {
            if (opGen != generation.get()) {
                return CoordinatorResult.StaleOperation()
            }
        }

        return when (result) {
            is ReadClientResult.Success -> {
                when (val outcome = result.value) {
                    is AuthLoginResult.MfaRequired -> {
                        synchronized(stateLock) {
                            if (opGen == generation.get()) {
                                _authState = StaffAuthState.MfaRequired(retry = outcome.response.retry)
                                CoordinatorResult.Success(_authState, generation = opGen)
                            } else {
                                CoordinatorResult.StaleOperation()
                            }
                        }
                    }

                    is AuthLoginResult.Success -> {
                        val loginSuccess = outcome.response

                        // Check token expiry immediately upon login return
                        val tokenExpires = try {
                            SessionValidation.parseRfc3339ToEpochMs(loginSuccess.expiresAt, "expires_at")
                        } catch (_: Exception) {
                            0L
                        }
                        if (clock.nowEpochMs() >= tokenExpires) {
                            synchronized(stateLock) {
                                if (opGen == generation.get()) {
                                    _authState = StaffAuthState.LoggedOut
                                }
                            }
                            return CoordinatorResult.Error("Received expired token on login")
                        }

                        synchronized(stateLock) {
                            if (opGen != generation.get()) return CoordinatorResult.StaleOperation()
                            activeTokenContext = loginSuccess.token
                        }
                        if (loginSuccess.companies.size > 1) {
                            synchronized(stateLock) {
                                if (opGen == generation.get()) {
                                    activeTokenContext = loginSuccess.token
                                    pendingLoginSuccess = PendingLoginContext(loginSuccess, opGen)
                                    _authState = StaffAuthState.CompanySelectionRequired(
                                        actor = loginSuccess.actor,
                                        availableCompanies = loginSuccess.companies
                                    )
                                    CoordinatorResult.Success(_authState, generation = opGen)
                                } else {
                                    CoordinatorResult.StaleOperation()
                                }
                            }
                        } else {
                            val singleCompany = loginSuccess.companies[0]
                            finalizeCompanyLogin(loginSuccess, singleCompany.id, opGen)
                        }
                    }
                }
            }

            is ReadClientResult.Auth401 -> {
                // Wrong credentials on initial login: do NOT emit outbox denial!
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.LoggedOut
                    }
                }
                CoordinatorResult.AuthDenied()
            }

            is ReadClientResult.Forbidden403 -> {
                synchronized(stateLock) {
                    if (opGen == generation.get()) {
                        _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    }
                }
                CoordinatorResult.Forbidden()
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

            opGen = generation.incrementAndGet()
        }

        return finalizeCompanyLogin(pending.loginResponse, companyId, opGen)
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
        } catch (_: Exception) {
            ReadClientResult.NetworkUnknown(category = "BOOTSTRAP_NETWORK_ERROR")
        }

        return handleBootstrapResult(
            opGen = opGen,
            bootstrapResult = bootstrapResult,
            token = loginSuccess.token,
            actorUlid = loginSuccess.actor.id,
            companyId = companyId,
            passwordEpoch = loginSuccess.actor.passwordEpoch,
            roleLabel = loginSuccess.actor.role,
            actorName = loginSuccess.actor.name,
            actorEmail = loginSuccess.actor.email,
            expiresAt = loginSuccess.expiresAt
        )
    }

    override fun switchCompany(newCompanyId: ServerUlid): CoordinatorResult<StaffAuthState> {
        val opGen: Long
        val currentSession: StaffSessionRecord

        synchronized(stateLock) {
            val ready = _authState as? StaffAuthState.ReadReady
                ?: return CoordinatorResult.Error("Cannot switch company: no active read-ready session")

            currentSession = ready.session
            opGen = generation.incrementAndGet()
        }

        val bootstrapResult = try {
            readClient.getBootstrap(token = currentSession.token, companyId = newCompanyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (_: Exception) {
            ReadClientResult.NetworkUnknown(category = "SWITCH_COMPANY_NETWORK_ERROR")
        }

        return handleBootstrapResult(
            opGen = opGen,
            bootstrapResult = bootstrapResult,
            token = currentSession.token,
            actorUlid = currentSession.actorUlid,
            companyId = newCompanyId,
            passwordEpoch = currentSession.passwordEpoch,
            roleLabel = currentSession.roleLabel,
            actorName = currentSession.actorName,
            actorEmail = currentSession.actorEmail,
            expiresAt = currentSession.expiresAt
        )
    }

    override fun logout(): LogoutResult {
        val opGen: Long
        val tokenToRevoke: String?
        val deviceId: ClientUuid?
        val clearFailed: Boolean
        val clearErrorReason = "Local session clear failed on logout"

        synchronized(stateLock) {
            opGen = generation.incrementAndGet()

            tokenToRevoke = activeTokenContext ?: when (val s = _authState) {
                is StaffAuthState.ReadReady -> s.session.token
                is StaffAuthState.CompanySelectionRequired -> pendingLoginSuccess?.loginResponse?.token
                else -> null
            }

            activeTokenContext = null
            activeChallenge = null
            pendingLoginSuccess = null

            // Deny reads even if the local purge itself is cancelled or fails.
            _authState = StaffAuthState.Locked(LockReason.StorageClearFailed(clearErrorReason))
            var failed = false
            try {
                sessionStore.clearSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (_: Exception) {
                failed = true
            }

            clearFailed = failed

            if (clearFailed) {
                _authState = StaffAuthState.Locked(LockReason.StorageClearFailed(clearErrorReason))
            } else {
                _authState = StaffAuthState.LoggedOut
            }

            deviceId = try {
                sessionStore.installationId
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (deviceId == null && !clearFailed) {
                _authState = StaffAuthState.StorageFailed("Installation identity unreadable")
            }


        }

        var remoteStatus = RemoteRevocationStatus.NotAttempted
        if (tokenToRevoke != null && deviceId != null) {
            remoteStatus = try {
                val res = readClient.authLogout(tokenToRevoke, AuthLogoutRequest(deviceId = deviceId))
                when (res) {
                    is ReadClientResult.Success -> RemoteRevocationStatus.Revoked
                    is ReadClientResult.Auth401 -> RemoteRevocationStatus.AlreadyRevokedOrExpired
                    else -> RemoteRevocationStatus.Unknown
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (_: Exception) {
                RemoteRevocationStatus.Unknown
            }
        }

        if (clearFailed) {
            return LogoutResult.StorageClearFailed(
                remoteRevocation = remoteStatus,
                message = clearErrorReason
            )
        }

        return LogoutResult.Success(remoteRevocation = remoteStatus)
    }

    private fun <T> executeScopedRead(
        queryName: String,
        networkCategory: String,
        block: (token: String, companyId: ServerUlid) -> ReadClientResult<T>
    ): CoordinatorResult<T> {
        val opGen: Long
        val token: String
        val companyId: ServerUlid
        val actorUlid: ServerUlid
        val expiresEpoch: Long

        synchronized(stateLock) {
            val ready = _authState as? StaffAuthState.ReadReady
                ?: return CoordinatorResult.Error("Not in ReadReady state")

            expiresEpoch = try {
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
            block(token, companyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (_: Exception) {
            ReadClientResult.NetworkUnknown(category = networkCategory)
        }

        if (result is ReadClientResult.Auth401) {
            return handleTokenDenied(opGen, companyId, actorUlid)
        }

        synchronized(stateLock) {
            if (opGen != generation.get()) {
                return CoordinatorResult.StaleOperation()
            }

            // Query completion expiry recheck
            if (clock.nowEpochMs() >= expiresEpoch) {
                _authState = StaffAuthState.LoggedOut
                return CoordinatorResult.Error("Session expired at ${clock.nowIsoUtc()}")
            }

            return when (result) {
                is ReadClientResult.Success -> {
                    CoordinatorResult.Success(
                        value = result.value,
                        generation = opGen,
                        companyId = companyId,
                        actorId = actorUlid
                    )
                }

                is ReadClientResult.Auth401 -> {
                    _authState = StaffAuthState.Locked(LockReason.AuthDenied())
                    CoordinatorResult.AuthDenied()
                }

                is ReadClientResult.Forbidden403 -> {
                    _authState = StaffAuthState.Locked(LockReason.MembershipDenied())
                    CoordinatorResult.Forbidden()
                }

                else -> CoordinatorResult.Error("Failed to fetch $queryName")
            }
        }
    }

    override fun getProducts(query: PaginationQuery): CoordinatorResult<ProductCatalogResponse> =
        executeScopedRead("product catalog", "PRODUCTS_NETWORK_ERROR") { token, companyId ->
            readClient.getProducts(token, companyId, query)
        }

    override fun getCustomers(query: PaginationQuery): CoordinatorResult<CustomerDirectoryResponse> =
        executeScopedRead("customer directory", "CUSTOMERS_NETWORK_ERROR") { token, companyId ->
            readClient.getCustomers(token, companyId, query)
        }
}
