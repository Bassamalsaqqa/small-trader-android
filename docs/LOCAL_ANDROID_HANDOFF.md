# Small Trader Android — Local Developer Handoff

Developer guide for Android Studio setup, launch modes, security/outbox architecture, and integration status with **`Bassamalsaqqa/account`** (`/api/mobile/v1` and `/api/mobile/v2`).

## 1. Gradle & SDK Configuration

Verified against `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `gradle/wrapper/gradle-wrapper.properties`:
- **Toolchain:** Gradle `9.3.1`, AGP `9.1.1`, Kotlin `2.2.10`, Compose BOM `2024.09.00`, Room `2.7.0`, OkHttp `4.10.0`, Java 11 target (runs under JDK 17/21).
- **Android App Config:** `namespace = "com.example"`, `applicationId = "com.aistudio.smalltrader.psdukx"`, `compileSdk 36.1`, `minSdk 24`, `targetSdk 36`, `versionCode = 6`, `versionName = "1.4"`.
- **Gradle Opt-In Flags (`gradle.properties`):**
  - `smalltrader.staffWorkspaceOrigin` (default `https://account.palsync.net`)
  - `smalltrader.staffReadWorkspace` (`false` by default)
  - `smalltrader.staffAllowHttpLoopback` (`false`; debug builds only)
  - `smalltrader.offlineOwnerPilot` (`false`; also requires `smalltrader.offlineCatalogMaxPages` & `smalltrader.offlineCatalogMaxProducts` for pilot UI)
  - `smalltrader.allowInsecureDebugSigningForRelease` (`false`; local debug-signed release testing only — never distribute)

## 2. Launch Routing & Security Architecture

- **No Silent Demo Fallback (`AppLaunchTargetResolver`):** Release/configured builds route strictly to `FULL_SHELL` (`AuthenticatedTraderApp`), `STAFF_WORKSPACE` (`StaffWorkspaceApp`), or fail-closed `UNCONFIGURED`. Demo mode (`OfflineDemoTraderRepository`) is segregated to debug builds with explicit opt-in.
- **Session & Keystore (`data/mobile/session/`):** `SecureStaffSessionStore` encrypts bearer sessions with `AndroidKeyStore` AES-256-GCM (`purpose=staff_session;pkg=<id>;v=1`) inside `noBackupFilesDir`. Plaintext passwords/MFA codes remain transient. Revoked tokens/devices (`401`) lock outbox scope (`AUTH_BLOCKED`).
- **Encrypted Durable Room Outbox (`data/mobile/outbox/`):** Room schema v1 (`6eddfecc8dabbb7ec370b858ec36714a`, `allowBackup="false"`, explicit cloud/device-transfer exclusions). Stores immutable canonical bare event bytes encrypted with `STO/1` AES-256-GCM envelopes, composite PK `(company_id, client_event_id)`, and scope locks (`OutboxScopeLockEntity`) that survive restarts.
- **Restricted Offline Workspace (`data/mobile/offline/`):** Activated only when `OFFLINE_OWNER_PILOT=true` and an explicit server lease is acquired; enforces `Settings.Global.BOOT_COUNT` continuity and monotonic elapsed-time checks.
- **Financial & Precision Rules:** All quantities, prices, and totals use Kotlin `BigDecimal` (`RoundingMode.HALF_UP`) and Western digits (`WesternFormatters`). JCS canonical serialization (`CanonicalJsonSerializer`) matches Laravel byte-for-byte.

## 3. Backend Integration Map (`Bassamalsaqqa/account`)

Backend mobile routes exist in `routes/mobile-v1.php` and `routes/mobile-v2.php` behind **default-OFF** server flags (`MOBILE_API_ENABLED=false`, `MOBILE_MERCHANT_API_ENABLED=false`, `MOBILE_V2_API_ENABLED=false`, `MOBILE_V2_CASH_SLICE_ENABLED=false`, `MOBILE_V2_OFFLINE_SALES_ENABLED=false`):

| Domain | Wired Client Surface | Backend Boundary & Status |
|---|---|---|
| **Auth & Sessions** | `SmallTraderReadClient`, `StaffSessionCoordinator` | `/api/mobile/v1/auth/{challenge,login,logout}`, `/devices/{id}/revoke`, `/sessions` |
| **Bootstrap & Settings** | `SmallTraderReadClient`, `ProductionTraderRepository` | `/companies/{id}/bootstrap`, `/settings/business` (minor units & company identity) |
| **Catalog, Customers, Suppliers** | `MerchantReadClient`, `MobileMerchantApiClient` | V1 bounded cursor reads & CRUD (`/products`, `/customers`, `/suppliers`) |
| **AR/AP Settlement & Payments** | `MobileMerchantApiClient`, `SettlementTargets` | V1 `/customers|suppliers/{id}/settlement-targets` & `/payments` (exact decimals, explicit allocations) |
| **Online Invoices & V2 Cash** | `MobileMerchantApiClient`, `HttpSaleSyncGateway` | V1 `/sales/invoices*`, V2 `/sales/online-cash` (nonzero discounts **rejected** in production; document discount extension is a **future proposal**) |
| **Offline Lease & Sync** | `OfflineLeaseStore`, `SaleSyncCoordinator` | V2 `/offline-sale-lease`, `/offline-events`, `/offline-events/{id}/status` (**OFF** by default) |
| **Blocked / Demo-Only / Hardware** | `ShiftCloseDialog`, `PurchasesScreen`, scanner/printer sheets | Shift close **blocked** ([Shift Count Blocker](SHIFT_COUNT_CONTRACT_BLOCKER.md)); local demo purchasing/GRN (`ADR-0013` atomic backend); CameraX & Bluetooth ESC/POS **simulated** |

## 4. Local Build, Test & Paired Verification

See [README.md](../README.md) for standard Gradle/contract test commands, [Release Playbook](RELEASING.md) for signed APKs and outbox preservation, and [Paired Harness](../tools/paired/README.md) for real-HTTP Laravel integration.
