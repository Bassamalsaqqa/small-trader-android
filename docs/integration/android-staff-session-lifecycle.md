# Android Staff Session & Application Coordinator Lifecycle

## 1. Architectural Scope & Context

This document details the minimal Android API 24+ staff authentication and read-session lifecycle coordinator under `com.example.data.mobile.session.StaffSessionCoordinator`. It bridges authenticated credential persistence (`SecureStaffSessionStore`), dedicated HTTP transport (`OkHttpTransport`), and the bounded read client (`SmallTraderReadClient`) into a cohesive, testable application lifecycle without touching UI, Compose, ViewModel, Room, or WorkManager components.

### Core Architectural Principles
- **Existing Foundations Reused**: Integrates `SmallTraderReadClient` and `SecureStaffSessionStore` without duplicating wire clients or inventing synthetic protocol layers.
- **Composition Entry Point**: Exposes a clean, documented factory (`StaffSessionCoordinator.create(context, baseOrigin, ...)`) that composes `SecureStaffSessionStore.create(context)`, dedicated `OkHttpTransport`, and `SmallTraderReadClient`.
- **Sole Posting Authority Preserved**: Adheres strictly to AGENTS.md § 1; client role labels (`"cashier"`, `"sales_staff"`) are strictly informative presentation strings, conveying zero financial posting or ledger authority.
- **Fail-Closed by Design**: Corrupted storage, tampered identities, mismatched tenants, expired tokens, or uninitialized Keystore keys immediately fail closed into explicit typed states (`Locked` or `StorageFailed`).
- **No Production Network Execution**: Production networks, credentials, and live endpoints are not run; hermetic unit tests execute against pure Kotlin loopback and mock transport seams.

---

## 2. Explicit Auth State Machine

The coordinator models lifecycle transitions through exhaustive, sealed state variants (`StaffAuthState`):

```
       +-------------------------------------------------------------+
       |                                                             |
       v                                                             |
[ LoggedOut ] --( requestChallenge )--> [ ChallengeActive ]          |
       |                                        |                    |
       |                                    ( login )                |
       |                                        |                    |
       |                   +--------------------+--------------------+
       |                   | (MFA_REQUIRED)     | (1 company)        | (multiple companies)
       |                   v                    v                    v
       |            [ MfaRequired ]        [ ReadReady ]   [ CompanySelectionRequired ]
       |                   |                    ^                    |
       |          ( retryLoginWithMfa )         |             ( selectCompany )
       |                   +--------------------+--------------------+
       |                                        |
       |                                    ( logout )
       +----------------------------------------+
```

### State Definitions
1. **`StaffAuthState.LoggedOut`**: Clean, unauthenticated baseline. Active credentials absent from memory; local session file absent from disk.
2. **`StaffAuthState.ChallengeActive(challengeId, expiresAt)`**: Single-use challenge acquired from server; waiting for credential submission.
3. **`StaffAuthState.MfaRequired(retry)`**: Credentials validated on server, but secondary authentication required. Initial challenge is consumed; fresh challenge required before retry.
4. **`StaffAuthState.CompanySelectionRequired(actor, availableCompanies)`**: Staff credentials authenticated across multiple company memberships; staff must select active tenant before bootstrap and persistence.
5. **`StaffAuthState.ReadReady(session, bootstrap)`**: Fully authenticated, tenant-scoped, bootstrap-verified session ready for product and customer reads.
6. **`StaffAuthState.Locked(reason)`**: Permanent or revocable lock state (HTTP 401 AuthDenied, HTTP 403 Forbidden, Keystore key locked/invalidated, scope mismatch).
7. **`StaffAuthState.StorageFailed(message)`**: Unrecoverable disk I/O or storage corruption error (corrupted installation identity, disk full).
8. **`StaffAuthState.TransientError(category)`**: Non-fatal transport or network drop. Never triggers offline demo fallback.

### Diagnostic Redaction & Secret Privacy
All states and coordinator models override `toString()` to strictly conceal secrets:
- Bearer tokens are redacted as `"Bearer [REDACTED]"`.
- Passwords, MFA codes, and recovery codes are never stored on coordinator state fields.
- Actor email and password epoch are redacted.
- Server error messages and Problem Details are sanitized to avoid leaking sensitive stack traces into bug telemetry.

---

## 3. Concurrency Architecture & Generation Guard

### Elimination of Global Mutex Across Network Calls
In multi-threaded Android runtimes, holding a synchronization lock across HTTP requests blocks cancellation, freezes UI threads, and prevents immediate logout. `DefaultStaffSessionCoordinator` adheres to a strict concurrency invariant:
> **No state mutex is EVER held across blocking network or I/O calls.**

### Monotonic Lifecycle Generation Guard
To eliminate race conditions, resurrecting sessions, and obsolete writes, the coordinator employs an atomic monotonic generation counter (`AtomicLong`):
1. **Invalidation on User Intent**: Every operation that changes user intent (`logout()`, starting a new `login()`, `selectCompany()`, or `switchCompany()`) increments the generation counter (`generation.incrementAndGet()`).
2. **Generation Capture**: At entry to any asynchronous or network operation, the worker thread captures the current generation (`val opGen = generation.get()`).
3. **Pre-Commit Verification**: After network execution completes, the coordinator checks `if (opGen != generation.get())`. If the generation has changed:
   - The late response is **strictly discarded**.
   - Obsolete tokens are **never written** to `SecureStaffSessionStore`.
   - Read readiness is **never restored**.
   - Foreign or obsolete DTOs are **never leaked** to callers (returns `CoordinatorResult.StaleOperation`).
   - Logout is **never resurrected**.

### Deterministic Barrier Verification
The test suite exercises three deterministic concurrency barrier scenarios:
- **Late Login After Logout**: A slow in-flight login that returns 200 OK after logout completes is safely discarded; no session file is written, and state remains `LoggedOut`.
- **Concurrent Company Switch**: A slow bootstrap for Company A returning after staff switched to Company B is discarded; Company B remains active.
- **Stale Read Results**: A slow catalog read returning after logout or company switch returns `CoordinatorResult.StaleOperation` rather than leaking obsolete tenant DTOs.

---

## 4. Authentication & MFA Lifecycle per Frozen Rev2

### Single-Use Challenge Lifecycle
- Login requires an unexpired challenge nonce and UUID obtained via `POST /api/mobile/v1/auth/challenge`.
- Upon submission in `login(...)`, the active challenge is **consumed immediately**; subsequent attempts with the same challenge fail closed.

### Fresh MFA Sequence
Per draft revision 2 decisions:
- If server returns `status: "MFA_REQUIRED", retry: "fresh_challenge"`, the coordinator invalidates the consumed challenge and enters `StaffAuthState.MfaRequired`.
- Retrying credentials with an MFA code (`mfa_code`) or Fortify recovery code (`recovery_code`) via `retryLoginWithMfa(...)` **must acquire a NEW challenge** before dispatching credentials to `/api/mobile/v1/auth/login`.
- Nonce reuse or continuation proofs are strictly rejected.

### Mutual Exclusion of MFA and Recovery Code
- Submitting both `mfa_code` and `recovery_code` simultaneously violates OpenAPI schema invariants and is rejected with `IllegalArgumentException` before touching network transport.

---

## 5. Scope Isolation & Persistent Session Lifecycle

### Installation Identity Custody
- The client installation identity (`ClientUuid`) is generated once and managed independently by `InstallationIdentityStore` under `context.noBackupFilesDir`.
- Preserved across staff logouts, credential purges, and app restarts.
- If the identity file is missing after creation, corrupted, or unreadable, the coordinator fails closed (`StaffAuthState.StorageFailed`) without silent UUID rotation.

### Session Persistence & Server Revalidation
- Successful login records are encrypted via `SecureStaffSessionStore` using runtime AES-256-GCM master keys with AAD binding `purpose=staff_session;pkg=<appId>;v=1`.
- **Restored Session Revalidation**: When restoring a session from disk on app launch (`restoreSession()`):
  1. Stored session is decrypted, validated for RFC 3339 expiration, and checked against device installation identity.
  2. The coordinator calls `GET /api/mobile/v1/companies/{companyId}/bootstrap` using the stored token.
  3. Consistency check: verified that `bootstrap.companyId == session.selectedCompanyId` AND `bootstrap.staff.id == session.actorUlid`.
  4. Only upon 200 OK and successful consistency check is `StaffAuthState.ReadReady` granted.

### HTTP 401 AuthDenial Lifecycle & Outbox Notification
When the server returns HTTP 401 (token revoked or expired):
- Coordinator transitions immediately to `StaffAuthState.Locked(LockReason.AuthDenied())`.
- Emits a typed `AuthDenialScope(companyId, actorId, deviceId, deniedAt)` via `AuthDenialListener`.
- Designed for downstream integration to lock local outbox queues (`QUEUE_LOCKED_AUTH_DENIED`) without deleting captured sales.
- Automatic unlocking or silent token retry is prohibited.

---

## 6. Logout & Credential Purge Boundary

When staff logs out (`logout()`):
1. **Immediate Local Denial**: Advances generation counter and immediately transitions `_authState` to `StaffAuthState.LoggedOut`. In-flight and subsequent local reads are denied instantly.
2. **Explicit Remote Revocation**: Dispatches `POST /api/mobile/v1/auth/logout` with the active Bearer token and stable installation `device_id`.
3. **Local Credential Purge**: Calls `SecureStaffSessionStore.clearSession()`.
   - **Purged**: Only `small_trader_secure_staff_session.bin` (and write temp files).
   - **Preserved**: Stable `installationId` (`small_trader_device_installation.json`) and offline outbox event queues are preserved intact.
4. **Honest Status Reporting**:
   - `RemoteRevocationStatus.Revoked`: Server confirmed 200 OK.
   - `RemoteRevocationStatus.Unknown`: Remote call timed out or encountered network error; server logout is **not claimed**.
   - `RemoteRevocationStatus.AlreadyRevokedOrExpired`: Server returned 401.
5. **Storage Clear Failure Lock**: If deleting the local session file fails, the coordinator enters `StaffAuthState.Locked(LockReason.StorageClearFailed)` and returns `LogoutResult.StorageClearFailed`. It never returns signed-out success while credentials remain readable on disk.

---

## 7. How the Next UI Layer Composes Lifecycle

The next Android slice will wire UI / ViewModel components to this coordinator:

```kotlin
// Composition Root Example (Application or Dependency Container)
val coordinator = StaffSessionCoordinator.create(
    context = applicationContext,
    baseOrigin = "https://api.smalltrader.example",
    allowHttpLoopback = false,
    authDenialListener = { denialScope ->
        // Hook for future outbox coordinator: lock sales outbox on 401
        outboxRepository.lockQueue(denialScope.companyId, "QUEUE_LOCKED_AUTH_DENIED")
    }
)

// In ViewModel (Consuming observable lifecycle):
class AuthViewModel(private val coordinator: StaffSessionCoordinator) : ViewModel() {
    val state: StateFlow<StaffAuthState> = ...

    fun onLoginSubmitted(email: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            coordinator.requestChallenge()
            when (val res = coordinator.login(email, pass, Build.MODEL)) {
                is CoordinatorResult.Success -> { /* State updated to ReadReady or Selection */ }
                is CoordinatorResult.Error -> { /* Show error */ }
                is CoordinatorResult.AuthDenied -> { /* Show 401 */ }
                is CoordinatorResult.Forbidden -> { /* Show 403 */ }
                is CoordinatorResult.StaleOperation -> { /* Ignored */ }
            }
        }
    }
}
```

> [!NOTE]
> This slice does not mutate UI, Compose, ViewModel, Room, or AndroidManifest. It provides the pure, testable lifecycle layer ready for UI consumption.

---

## 8. Verification & Suite Summary

### Exact Test Counts
- **Targeted Mobile Unit Tests**: **200 unit tests** across **17 testsuites** (`com.example.data.mobile.*`), 0 failures, 0 errors, 0 skipped.
  - `StaffSessionCoordinatorTest`: 33 tests (concurrency barriers, fresh MFA, single/multi-company bootstrap, restore revalidation, 401/403 fail-closed, storage faults, cancellation, redaction).
  - `SecureStaffSessionStoreTest`: 37 tests (AES-256-GCM envelope, AAD, installation identity, atomic replacement, bounds).
  - `ReadClient*` and `OkHttpTransport*`: 91 tests (origin isolation, method allowlisting, DTO parsing, loopback, timeout).
  - Contract & Decimal Foundations: 39 tests (exact arithmetic, JCS serialization, Crockford ULID, UUID, boundaries).
- **Contract Verification Suite**: **210 tests passed** in both normal (`python3 tools/validate-mobile-contract.py`) and optimized (`python3 -O tools/validate-mobile-contract.py`) execution.
- **Lint Verification**: **64 baseline warnings** in `app/build/reports/lint-results-debug.xml`, **0 errors** (0 warnings in `com.example.data.mobile`).

### Hardware Keystore Verification Notice
On-device Android Keystore hardware operations were not executed on physical hardware as no active emulator or device session was available via `device_list`. Hermetic test suites execute on JVM using an isolated test cryptographic engine (`JvmAesGcmCryptoEngine`).
