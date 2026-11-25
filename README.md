# التاجر الصغير · Small Trader (Android)

Arabic-first (RTL) / English (LTR) Kotlin + Jetpack Compose (Material 3) POS, merchant ledger, inventory, and encrypted offline-outbox client integrated with **`Bassamalsaqqa/account`** (`/api/mobile/v1` and `/api/mobile/v2`), plus an isolated debug-only demo mode.

## Current Release & Verification Reality

- **Release Identity:** **`v1.4` (`versionCode = 6`)**, `applicationId = "com.aistudio.smalltrader.psdukx"`, origin `https://account.palsync.net`. The signed APK artifact is stored in **private userdata (NOT published on GitHub)**.
- **Critical Signing & Outbox Safety:** The release signing certificate is **incompatible** with debug builds (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). **Preserve pending offline outbox records; NEVER uninstall an installation that has pending outbox data.**
- **Default-OFF Flags (`gradle.properties` / `app/build.gradle.kts`):** `smalltrader.staffReadWorkspace=false`, `smalltrader.staffAllowHttpLoopback=false`, `smalltrader.offlineOwnerPilot=false`, `smalltrader.allowInsecureDebugSigningForRelease=false` (backend mobile & offline APIs also default **OFF**).
- **Automated Verification:**
  - Full standard JVM/Robolectric suite: **`945` passed / `6` skipped** (`951` total: skips 1 `@Ignore` `ExampleRobolectricTest`, 1 `SmallTraderReadPilotSmokeTest`, 4 `OutboxScaleBenchmarkHarnessTest`), plus **`5` targeted tests** (`4` `OutboxScaleBenchmarkHarnessTest` + online/offline UUID collision) passed separately.
  - Paired real-HTTP harness ([`tools/paired/README.md`](tools/paired/README.md) + `PairedProductionSaleTest`): **`12` real sale vectors** across 3 companies + **`6` backend domain reconciliations** + foreign-tenant zero-leakage passed against the Account `d096` runtime baseline (backend runtime business source unchanged since).
  - Contract validator (`tools/validate-mobile-contract.py`): validates schemas, OpenAPI 3.1, fixtures, JCS vectors, and `manifest.sha256` parity in normal and `-O` modes.
- **Blocked / Unverified / Proposals:**
  - **Physical device & KVM emulator:** **BLOCKED / UNVERIFIED** (no physical device/emulator for hardware `AndroidKeyStore`, `BOOT_COUNT`, TalkBack, IME, CameraX, or Bluetooth ESC/POS printing).
  - **Deployment & Restore:** **No production deployment occurred**; database **restore is NOT VERIFIED**.
  - **Document Discounts & Shift Close:** Mobile document discounts are a **FUTURE PROPOSAL (NOT IMPLEMENTED)** — production checkout rejects nonzero discounts. Shift close UI remains **BLOCKED** pending backend contract guarantees ([Shift Count Blocker](docs/SHIFT_COUNT_CONTRACT_BLOCKER.md)).

## Toolchain & Commands

Configured in `app/build.gradle.kts` & `gradle/libs.versions.toml`: Gradle `9.3.1`, AGP `9.1.1`, Kotlin `2.2.10`, Compose BOM `2024.09.00`, Room `2.7.0`, OkHttp `4.10.0`, `compileSdk 36.1`, `minSdk 24`, `targetSdk 36`, Java 11 target (tested on JDK 21).

```bash
# Build debug APK & run standard JVM/Robolectric tests + lint
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug

# Contract & schema validation (Python 3.10+)
python3 tools/validate-mobile-contract.py && python3 -O tools/validate-mobile-contract.py

# Opt-in outbox scale benchmark (1,000 encrypted records; skipped in normal runs)
OUTBOX_BENCHMARK_ENABLED=true ./gradlew :app:testDebugUnitTest \
  --tests "com.example.data.mobile.outbox.OutboxScaleBenchmarkHarnessTest"
```

See [Release Playbook](docs/RELEASING.md) for signed release builds and [Paired Harness](tools/paired/README.md) for live loopback Laravel integration.

## Source Architecture (`app/src/main/java/com/example/`)

- **`MainActivity.kt` & `ui/navigation/`:** `AppLaunchTargetResolver` gates `FULL_SHELL` (`AuthenticatedTraderApp`), `STAFF_WORKSPACE` (`StaffWorkspaceApp`), `UNCONFIGURED`, or debug-only `DEMO_TRADER` (never silent demo fallback in release).
- **`data/mobile/`:** Contract primitives & JCS (`contract/`, `contract/v2/`), `client/` (`MobileMerchantApiClient`), `merchantread/`, `readclient/` (`SmallTraderReadClient`, isolated `OkHttpTransport`), `session/` (`SecureStaffSessionStore` in `noBackupFilesDir`, `StaffSessionCoordinator`), `outbox/` (Room v1 `OutboxDatabase`, `STO/1` AES-256-GCM encryption, `AUTH_BLOCKED` scope locks), `offline/` (`OfflineLeaseStore`, `RestrictedOfflineWorkspace`, `BOOT_COUNT`), `sync/` (`SaleSyncCoordinator`, `HttpSaleSyncGateway`), `integration/` (`OutboxAuthDenialBridge`).
- **`data/` & `viewmodel/`:** `TraderRepository`, `ProductionTraderRepository`, debug-only `OfflineDemoTraderRepository`, `SampleCatalog`, `ProductSearch`, `TraderViewModel`, `TraderUiState`.
- **`ui/`:** `AuthenticatedTraderApp`, `RestrictedOfflineScreen`, `OfflinePilotControls`, `staff/`, `screens/` (Home, NewSale, InvoiceReview, CashPayment, Receipt, Customers, Suppliers, Purchases, ProductsCatalog, PeopleHub, BusinessExtraScreens), `components/`, `shell/`, `theme/` (`IBM Plex Sans Arabic`, Western digits via `WesternFormatters`).

## Documentation Index (Current vs Historical)

### Current & Authoritative

| Document | Scope |
|---|---|
| [AGENTS.md](AGENTS.md) | Posting authority, decimal/JCS/idempotency/Keystore invariants |
| [Release Playbook](docs/RELEASING.md) | **Single authority** for `v1.4` (`versionCode 6`) signing, upgrades & outbox safety |
| [Paired Financial Harness](tools/paired/README.md) | Real-HTTP Android <-> Laravel `tools/paired/` verification |
| [Local Android Handoff](docs/LOCAL_ANDROID_HANDOFF.md) | Developer setup, build flags, and integration status |
| [Surface Coverage Matrix](docs/SURFACE_COVERAGE.md) | Live-wired vs debug-demo vs simulated vs blocked surfaces |
| [Product Picker & Search](docs/workflows/product-picker.md) | Arabic normalization, tiered ranking, and bounded search rules |
| [Frozen Mobile Contract ADR-0001](docs/integration/mobile-v1/ADR-0001-mobile-v1-contract.md) | SHA-256 pinned contract baseline (also see [Revision 3](docs/integration/mobile-v1-revision-3-decisions.md)) |
| [Font Licenses](docs/fontlicense/README.md) | IBM Plex Sans Arabic SIL OFL 1.1 provenance & hashes |

### Historical Milestones, Proposals & Evidence

- **Blockers & Proposals (Historical / Not Live):** [Shift Count Blocker & Discount Gate](docs/SHIFT_COUNT_CONTRACT_BLOCKER.md), [Financial Tax Protocol Proposal](docs/integration/financial-tax-protocol-proposal.md).
- **Historical Integration & Milestone Reports:** [Integration Candidate](docs/integration/android-integration-candidate.md), [Paired Read Pilot](docs/integration/paired-read-pilot.md), [Offline Persistence Handoff](docs/OFFLINE_PERSISTENCE_CORRECTION_HANDOFF.md), [Offline Review Correction](docs/integration/offline-review-correction-075d5bc.md), [Remember Session Handoff](docs/REMEMBER_SESSION_HANDOFF.md), [Revision 2](docs/integration/mobile-v1-revision-2-decisions.md), [Revision 3](docs/integration/mobile-v1-revision-3-decisions.md), and module foundation notes (`docs/integration/android-*.md`).
- **Historical Prototype / UX Archives:** [Candidate v3](docs/CANDIDATE_V3_HANDOFF.md), [Stitch Native](docs/STITCH_NATIVE_HANDOFF.md), `docs/PHASE1_*.md`, `docs/PR3_*.md`, `docs/*_IMPLEMENTATION.md`, [Brand Provenance](docs/PARENT_BRAND_PROVENANCE.md), `docs/android-merchant-ux-reset/`, and evidence directories (`docs/integration/*-evidence/`, `docs/candidate-v3/`, `docs/phase1-evidence/`).
