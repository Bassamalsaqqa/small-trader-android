# Android Staff Read Workspace UI Integration

## 1. Architectural Scope & Context

This document details the minimal Android API 24+ staff read UI workspace integration under `com.example.ui.staff`. It layers an explicit, off-main-thread ViewModel (`StaffWorkspaceViewModel`) and Jetpack Compose screens directly atop the qualified session lifecycle coordinator (`StaffSessionCoordinator.create`), authenticated credential persistence (`SecureStaffSessionStore`), dedicated HTTP transport (`OkHttpTransport`), and the bounded read client (`SmallTraderReadClient`).

### Core Architectural Principles
- **No Production Default or Silent Fallback**: Governed by explicit compile-time / build-time opt-in flags:
  - `smalltrader.staffReadWorkspace` / `STAFF_READ_WORKSPACE` (boolean, default `false`)
  - `smalltrader.staffWorkspaceOrigin` / `STAFF_WORKSPACE_ORIGIN` (string, default `""`)
  - `smalltrader.staffAllowHttpLoopback` / `STAFF_ALLOW_HTTP_LOOPBACK` (boolean, default `false`)
- **Strict Isolation from Demo**: When opted in, `MainActivity` routes exclusively to `StaffWorkspaceApp`. Any network, authentication, session lock, storage failure, or configuration error stays within the explicit staff status screens (`StaffStatusScreens.kt`) and **never** falls back to demo merchant screens or synthetic catalog data.
- **Sole Posting Authority Preserved**: In strict compliance with `AGENTS.md` § 1, staff role badges (e.g., `"Cashier"`, `"Sales Staff"`) are purely descriptive presentation labels. The UI prominently displays an explicit disclaimer: `"Local role is strictly informational; zero financial posting authority."`
- **Transient Memory for Secrets**: Passwords, TOTP codes, and recovery codes are held strictly in transient UI memory (`StaffWorkspaceViewModel` in-flight coroutine parameters and Compose local form state). They are **never** persisted to `SavedStateHandle`, disk, logs, or long-lived fields, and are immediately purged in `finally` blocks upon completion or failure.
- **Bearer Tokens Concealed**: Bearer tokens are kept within `StaffSessionRecord` inside the coordinator and store. View data (`SafeStaffActorViewData`, `SafeCompanyViewData`, `StaffWorkspaceUiState`) completely omits auth tokens.
- **Immediate Cache Invalidation on Tenant Switch / Logout / Lock**: Switching companies, logging out, or receiving an HTTP 401 AuthDenied lock immediately clears customer and product catalogs in memory. Stale async network responses with mismatched generation counters or company IDs are discarded.

---

## 2. Real API Dependency: Backend PR #88

The Android Staff Read Workspace communicates with the backend services implemented in backend PR #88 (`feat(auth): staff session authentication and bounded read API`):
- `POST /api/v1/staff/auth/challenge`: Acquires single-use challenge ULID and expiration timestamp.
- `POST /api/v1/staff/auth/login`: Submits credentials (`email`, `password`, `challenge_id`, optional `mfa_code` / `recovery_code`, optional `company_id`). Returns `StaffAuthResponse` or `MfaRequiredResponse`.
- `POST /api/v1/staff/auth/logout`: Revokes the session token server-side.
- `GET /api/v1/bootstrap`: Verifies company membership and active tenant scope.
- `GET /api/v1/products`: Bounded cursor pagination of tenant inventory items.
- `GET /api/v1/customers`: Bounded cursor pagination of tenant customer records.

---

## 3. Configuration & Build Opt-In Flags

The workspace requires explicit opt-in in `local.properties` or via Gradle command line arguments:

```properties
# local.properties (DEVELOPMENT ONLY)
smalltrader.staffReadWorkspace=true
smalltrader.staffWorkspaceOrigin=https://api.smalltrader.internal
smalltrader.staffAllowHttpLoopback=false
```

Or via Gradle flags:
```bash
./gradlew assembleDebug \
  -Psmalltrader.staffReadWorkspace=true \
  -Psmalltrader.staffWorkspaceOrigin="https://api.smalltrader.internal" \
  -Psmalltrader.staffAllowHttpLoopback=false
```

### Security & Cleartext Policy
- **HTTPS Enforcement**: Release builds and default debug builds strictly enforce HTTPS origins (`https://`).
- **Loopback Cleartext Exception (Debug Only)**: If local emulator/device testing against an unencrypted loopback development server (e.g., `http://10.0.2.2:8000` or `http://127.0.0.1:8000`) is required during development, `smalltrader.staffAllowHttpLoopback=true` must be explicitly passed. Release builds ignore loopback overrides.
- **Permissions**: `android.permission.INTERNET` is declared in `AndroidManifest.xml` for network communication.

---

## 4. UI Components & Screen States

The staff workspace is structured into compact, accessible composables conforming to the existing Material 3 styling and 48dp minimum touch target requirement:

1. **`StaffSignInScreen`**:
   - Email and password input fields with keyboard actions and password visibility toggle.
   - Transparent orchestration of fresh challenge acquisition behind the scenes.
   - Deduplicated submit button with loading indicator.
2. **`StaffMfaScreen`**:
   - Mutually exclusive selection chips between TOTP Authenticator code and Fortify Recovery Code.
   - Automatically re-acquires a fresh challenge before invoking coordinator MFA login retry.
   - Option to cancel and return to sign-in.
3. **`StaffCompanySelectScreen`**:
   - Multi-tenant company cards displaying company name, slug, and role badge.
   - Selection triggers tenant-scoped bootstrap and transitions to `ReadReady`.
4. **`StaffWorkspaceScreen` (ReadReady)**:
   - Header with active company name, staff display name, email, and informative role badge.
   - Posting authority disclaimer: `"Local role is strictly informational; zero financial posting authority."`
   - Tab switch between **Product Catalog** and **Customer Directory**.
   - Search query filtering with instantaneous client-side filter and server cursor queries.
   - Formatted exact wire fields:
     - Products: SKU, unit price string, available inventory count, paired tax rate (or `"No tax rate associated"`), default sale price (or `"Price unavailable"`).
     - Customers: Name, phone, email, notes, active status.
   - Bounded cursor pagination with "Load More" controls.
   - Company switch and Logout actions.
5. **`StaffStatusScreens`**:
   - **Locked**: Indicates auth revocation (401), membership denial (403), or Keystore invalidation. Offers safe local reset to unauthenticated state.
   - **StorageFailed**: Indicates unrecoverable Keystore or file corruption.
   - **OfflineError**: Displays transport/connectivity error with manual retry button.
   - **Unconfigured**: Displayed when opted-in without an origin configured.

---

## 5. Verification & Proof Limits

### Automated Verification
- **Unit Tests**: Full unit test coverage for `StaffWorkspaceViewModel` (`StaffWorkspaceViewModelTest.kt`) verifying sign-in, MFA challenge renewal, multi-tenant selection, tab switching, search filtering, pagination, immediate list invalidation on switch/logout, and error handling.
- **Compose Tests**: Robolectric UI tests (`StaffWorkspaceComposeTest.kt`) validating screen composition, accessibility targets, form submission, and tab toggling.
- **Mobile Contract**: Hermetic contract suite (`tools/validate-mobile-contract.py`) passing 210/210 assertions in both normal and optimized (`-O`) Python modes.
- **Lint**: `lintDebug` passing with 0 errors and zero new warnings over baseline.

### Environmental Proof Limitations
- **Device Visual Verification**: **NOT VERIFIED**. `device_list` discovered 0 connected Android devices or emulators on the host system.
- **Hardware Keystore Integration**: **NOT VERIFIED**. Hardware-backed MasterKey generation requires a physical device or emulator running Google Play Services / hardware keystore daemon. Verified via hermetic mock and software-backed tests only.
- **No Production Activation**: This integration slice introduces no live server configuration changes, no hardcoded production endpoints, and no deployment actions.

### Follow-up error recovery

Unexpected logout exceptions and cancellation leave the workspace locked, with busy indicators reset; the UI never claims credentials were cleared without confirmation. Passwords are cleared as soon as company selection no longer requires them. Unexpected coordinator/provider error text is replaced with fixed messages, and status screens use localized fallback copy instead of displaying raw reasons.

A failed storage composition retains a retry factory for the already validated origin. Retry reconstructs the coordinator off the main thread and reruns session restoration; failure stays in the storage-error screen rather than being mislabeled as missing configuration. Clearing scoped lists invalidates pending query sequence numbers, and loading cleanup also checks coordinator generation.

Additional targeted regressions cover uncertain logout, cancelled logout, storage retry after an initial provider failure, safe English error text, and password clearing at company selection. Device-level validation remains outstanding.
