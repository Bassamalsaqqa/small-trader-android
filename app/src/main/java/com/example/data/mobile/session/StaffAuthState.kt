package com.example.data.mobile.session

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.ActorProfile
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.CompanyMembership
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Explicit auth and session lifecycle states for the Small Trader Android client.
 *
 * Diagnostic [toString] representations strictly redact secrets (tokens, passwords, customer
 * data, raw server error text) to prevent secret leakage in crash dumps, logs, and telemetry.
 */
sealed interface StaffAuthState {
    /**
     * Unauthenticated, clean client state (e.g. initial launch or successful logout).
     */
    object LoggedOut : StaffAuthState {
        override fun toString(): String = "StaffAuthState.LoggedOut"
    }

    /**
     * Login challenge has been acquired from the server and is active for credential submission.
     */
    data class ChallengeActive(
        val challengeId: ClientUuid,
        val expiresAt: String
    ) : StaffAuthState {
        override fun toString(): String =
            "StaffAuthState.ChallengeActive(challengeId=$challengeId, expiresAt='$expiresAt')"
    }

    /**
     * Server indicated MFA is required. The initial challenge is consumed; a fresh challenge
     * must be acquired before retrying credentials with MFA code or recovery code.
     */
    data class MfaRequired(
        val retry: String = "fresh_challenge"
    ) : StaffAuthState {
        override fun toString(): String = "StaffAuthState.MfaRequired(retry='$retry')"
    }

    /**
     * Staff authenticated successfully with multiple company memberships.
     * The staff member must explicitly select one of the returned company memberships before
     * tenant bootstrap can be executed and read readiness granted.
     */
    data class CompanySelectionRequired(
        val actor: ActorProfile,
        val availableCompanies: List<CompanyMembership>
    ) : StaffAuthState {
        override fun toString(): String =
            "StaffAuthState.CompanySelectionRequired(actorId=${actor.id}, companiesCount=${availableCompanies.size})"
    }

    /**
     * Fully authenticated, tenant-scoped, and bootstrap-verified session ready for read operations.
     *
     * Roles are informative presentation strings only; client holds zero financial posting authority.
     */
    data class ReadReady(
        val session: StaffSessionRecord,
        val bootstrap: BootstrapResponse
    ) : StaffAuthState {
        override fun toString(): String =
            "StaffAuthState.ReadReady(actorId=${session.actorUlid}, companyId=${session.selectedCompanyId}, expiresAt='${session.expiresAt}')"
    }

    /**
     * Fail-closed locked state.
     * Entered on HTTP 401 AuthDenied, HTTP 403 Forbidden, cryptographic key invalidation,
     * or session scope mismatch.
     */
    data class Locked(val reason: LockReason) : StaffAuthState {
        override fun toString(): String = "StaffAuthState.Locked(reason=$reason)"
    }

    /**
     * Persistent storage failure (corrupt installation identity, disk write failure, etc.).
     */
    data class StorageFailed(val message: String) : StaffAuthState {
        override fun toString(): String = "StaffAuthState.StorageFailed(message='$message')"
    }

    /**
     * Transient network or transport error.
     * Never falls back to offline demo data or simulated staff state.
     */
    data class TransientError(val category: String) : StaffAuthState {
        override fun toString(): String = "StaffAuthState.TransientError(category='$category')"
    }
}

/**
 * Typed reasons for entering the [StaffAuthState.Locked] state.
 */
sealed interface LockReason {
    /** HTTP 401: Token revoked or invalid on server. Triggers queue locking hook. */
    data class AuthDenied(val message: String = "Authentication revoked or invalid (HTTP 401)") : LockReason

    /** HTTP 403: Tenant membership revoked or forbidden. */
    data class MembershipDenied(val message: String = "Tenant access forbidden (HTTP 403)") : LockReason

    /** AndroidKeyStore key alias missing or uninitialized. */
    data class KeyUnavailable(val message: String = "Cryptographic key unavailable") : LockReason

    /** AndroidKeyStore key locked or permanently invalidated (e.g. lock screen disabled). */
    data class KeyLocked(val message: String = "Cryptographic key locked or invalidated") : LockReason

    /** Local session credential deletion failed on logout. */
    data class StorageClearFailed(val message: String = "Local session clear failed on logout") : LockReason

    /** Restored or response metadata did not match requested scope. */
    data class ScopeMismatch(val message: String = "Session scope mismatch detected") : LockReason

    /** Stored session payload or identity file was corrupted or tampered. */
    data class CorruptSession(val message: String = "Corrupted session record") : LockReason
}

/**
 * Typed notification payload emitted when an HTTP 401 AuthDenied response is encountered.
 * Callers (such as offline outbox dispatchers) consume this hook to lock offline queues
 * (QUEUE_LOCKED_AUTH_DENIED) without deleting pending records.
 */
data class AuthDenialScope(
    val companyId: ServerUlid?,
    val actorId: ServerUlid?,
    val deviceId: ClientUuid,
    val deniedAt: String
) {
    override fun toString(): String =
        "AuthDenialScope(companyId=$companyId, actorId=$actorId, deviceId=$deviceId, deniedAt='$deniedAt')"
}

/**
 * Listener interface for observing typed auth denial events.
 */
fun interface AuthDenialListener {
    fun onAuthDenied(scope: AuthDenialScope)
}

/**
 * Status of remote token revocation attempt during logout.
 */
enum class RemoteRevocationStatus {
    /** Server acknowledged revocation with 200 OK (status: logged_out). */
    Revoked,

    /** Network timeout, transport error, or 5xx server error. Revocation unconfirmed. */
    Unknown,

    /** Server returned 401: token was already revoked or expired. */
    AlreadyRevokedOrExpired,

    /** No active token existed to revoke. */
    NotAttempted
}

/**
 * Result of a [StaffSessionCoordinator.logout] invocation.
 */
sealed interface LogoutResult {
    data class Success(val remoteRevocation: RemoteRevocationStatus) : LogoutResult
    data class StorageClearFailed(
        val remoteRevocation: RemoteRevocationStatus,
        val message: String
    ) : LogoutResult
}

/**
 * Typed outcome for coordinator operations.
 */
sealed interface CoordinatorResult<out T> {
    data class Success<out T>(val value: T) : CoordinatorResult<T>
    data class Error(
        val message: String,
        val category: String = "COORDINATOR_ERROR"
    ) : CoordinatorResult<Nothing>
    data class AuthDenied(val code: String = "UNAUTHORIZED") : CoordinatorResult<Nothing>
    data class Forbidden(val code: String = "FORBIDDEN") : CoordinatorResult<Nothing>
    data class StaleOperation(
        val message: String = "Operation invalidated by newer lifecycle generation"
    ) : CoordinatorResult<Nothing>
}

/**
 * Abstraction for system clock to support deterministic testing.
 */
interface CoordinatorClock {
    fun nowEpochMs(): Long
    fun nowIsoUtc(): String
}

object SystemCoordinatorClock : CoordinatorClock {
    private val rfc3339Format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf
        }
    }

    override fun nowEpochMs(): Long = System.currentTimeMillis()
    override fun nowIsoUtc(): String = rfc3339Format.get()!!.format(Date())
}
