# Paired Read Pilot: Android V1 HTTPS Staging & Gate Matrix

This document pins the integration candidate gate matrix, defines the hand-off contract between the Android client and the disposable Laravel staging backend, records backend migration dependencies and file overlaps, and establishes the standalone execution and verification harness for the paired read pilot.

---

## 1. Executive Summary & Provenance

- **Android Base**: Candidate PR #19 at exact commit [`d849e7903e5f87c31bebb1bd79b2e741fd1a8398`](https://github.com/Bassamalsaqqa/small-trader-android/commit/d849e7903e5f87c31bebb1bd79b2e741fd1a8398) (`t3/android-integration-candidate`), with verified zero remote drift.
- **Backend Heads (Bassamalsaqqa/account)**:
  - PR #94: `7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb` (cross-version receipt identity, atomic rollback, financial authority)
  - PR #87: `912aa71f557b314837e70138df22615902fc9d7b` (OPEN: invoice UX, search indexes, document rules)
  - PR #88: `9f73ca8952da6bbfc0eabadbb01e51892ace8bc6` (catalog snapshot hash, O(n) scan)
  - PR #89: `cb97c9a7b864b3a40e5dc4fe03c9f168fd5c59e8` (idempotency, lock ordering)
  - PR #91: `f3836ef7aa8a2437e95fedaec3166399a44158b2` (Revision 3 financial contract mirror)
  - PR #92: `7b8531bfb932c73c26efee7f1ace6c5ae61fceaf` (dual-OFF boundary, safe default-deny)
  - PR #93: `1b8a696feda350bbc2df86a26d05b69fd615ba57` (currency precision authority, line decimal enforcement)
  - Backend main: `f8a0cf7012c4875bec7320d42e0f8dde58593b52`
  - Android main: `79c9bb348f7ad4840842aa077f1820f75745d2a0`
- **Frozen Contract Manifest**: `e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7` (50 synchronized contract files).
- **Room V1 Hash**: `6eddfecc8dabbb7ec370b858ec36714a` (schema unchanged).
- **Policy Invariant**: The repository owner has approved the **offline store-and-forward architecture** (Room durable outbox, strict scope-bound locks, canonical ACK verification, and offline sales data model), but **live financial capture, background sync activation, and live offline financial posting remain strictly OFF**. No broad policy relaxation is granted.

---

## 2. Gate Matrix

| Priority | Gate Scope | Description | Status | Owner | Required Evidence | Approval |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **P0** | **Freeze Compose + HTTPS V1 Auth/Read** | Freeze Compose UI to Staff Read Workspace (`STAFF_READ_WORKSPACE=true`); execute real HTTPS auth challenge → login → bootstrap → bounded pagination → logout → old token 401 via `SmallTraderReadClient` + unchanged `OkHttpTransport` against disposable Laravel staging. | **READY FOR HANDOFF** (Harness implemented; reports `NOT RUN` until handoff available) | Android Sub-agent / Lead | Filtered Gradle JUnit execution; sanitized JSON evidence at `evidence/paired-read-pilot-evidence.json`. | Lead / Owner |
| **P0** | **Android Outbox Encryption & Backup Exclusions** | Protect durable sale payloads at rest in Room. Plain SQLite `OutboxEventEntity.payload_bytes` is currently unencrypted BLOB; `AndroidManifest.xml` has `android:allowBackup="true"`. Must add explicit cloud/device-transfer exclusions (`backup_rules.xml`, `data_extraction_rules.xml`) and encrypt economic payloads at rest before live capture activation. | **HELD / PROPOSED** (Follow-on PR-A) | Android Security | Key loss, reinstall, reboot, upgrade tests; no clear-app-data workarounds. | Owner |
| **P0** | **V2 Production Adapter Authorization & Writers** | Implement real authenticated V2 HTTP gateway in `SaleSyncCoordinator`; canonical writers, atomic cross-version receipt identity, status-before-resubmit, and rollback on absence-row races. | **HELD / PROPOSED** (Follow-on PR-C) | Backend & Android | Integration tests with concurrent requests, rollback verification, absence-row race handling. | Owner |
| **P1** | **Receipt Canonical Retention & Backup Policy** | Raw canonical byte retention for offline/online sales receipts and backup access policy. No legal guesses; owner must define retention timeline and audit access. | **PENDING OWNER DECISION** | Business Owner / Legal | Written policy document signed off by owner. | Owner |
| **P1** | **Authoritative Currency Policy** | Exact decimal precision authority across Revision 2/3 transitions and historical invoices (2 vs 3 decimal places for JOD/ILS/USD). | **QUALIFIED IN CONTRACT** (PR #93 backend, Check 33 Android) | Financial Owner | Automated contract validation suite (`tools/validate-mobile-contract.py`). | Financial Lead |
| **P1** | **Cross-Tenant & Device Revocation** | Strict tenant isolation and device revocation enforcement. Revoked token or foreign tenant access immediately locks queue scope. | **VERIFIED IN HARNESS** (Step 8 & 10 in pilot smoke test) | Android / Backend | Negative 401/403 test cases in read pilot smoke suite. | Lead |
| **P1** | **AUTH_BLOCKED Queue Resolution Policy** | Clear specification for resolving persisted `AUTH_BLOCKED` outbox queues while preserving physical cash taken during offline shifts. | **HELD / PROPOSED** (Follow-on PR-D) | Android / Security | Re-auth and manager reconciliation workflow documentation. | Owner |
| **P1** | **Idempotency & Lost ACK Recovery** | Handling network loss during sale submission: status probe before re-submitting payload to prevent duplicate financial invoices. | **DESIGNED IN CONTRACT** (Follow-on PR-C) | Android / Backend | Idempotency test suite across network failure points. | Lead |
| **P1** | **Stock & FEFO Conflict Policy** | Resolving stock shortages and FEFO batch conflicts when offline sales are synced after competing online sales. | **DEFERRED TO PILOT V2** | Operations Owner | Business rules specification for offline negative inventory. | Owner |
| **P1** | **Provisional Receipt Truthfulness** | UI must label offline receipts strictly as "Provisional / Unposted" until canonical server ACK is received with official invoice number. | **VERIFIED IN CONTRACT** (No fake POSTED status displayed) | Android UI | UI review against `SURFACE_COVERAGE.md`. | Product Owner |
| **P1** | **Device Acceptance & PR #88 Scan Performance** | Physical Pixel hardware validation (Arabic RTL, TalkBack, IME, barcode scanner, Bluetooth printer) + resolving PR #88's O(n) catalog hash snapshot full scan. | **BLOCKED UNVERIFIED** (No device infra / KVM in harness) | Hardware QA | Physical device run and catalog snapshot query benchmark. | Owner |

---

## 3. Backend Hand-off Contract

The backend integration thread (Codex) provisions an isolated disposable MariaDB + HTTPS Laravel staging environment and exports a single JSON hand-off configuration.

### Environment Variable
The pilot test runner expects the absolute file path to the hand-off JSON in:
```bash
SMALL_TRADER_READ_PILOT_HANDOFF="/path/to/private/handoff.json"
```

### JSON Schema & Exact Keys
```json
{
  "baseUrl": "https://disposable-staging.smalltrader.test:8443",
  "backendCommitSha": "7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb",
  "manifestDigest": "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7",
  "staffEmail": "cashier@smalltrader.example",
  "staffPassword": "EphemeralStaffPassword123!",
  "deviceId": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
  "deviceName": "ReadPilotHarness",
  "appVersion": "1.0.0-pilot",
  "platform": "android",
  "companyId": "01JCV8X9M2NQW3TYZ4R5S6789A",
  "caCertPath": "/path/to/test-ca.crt",
  "credentialsPath": "/path/to/private/chmod600_credentials.json",
  "invalidCompanyId": "01JCV8X9M2NQW3TYZ4R5S6789Z",
  "wrongHostnameUrl": "https://wrong-host.smalltrader.test:8443",
  "untrustedCertUrl": "https://untrusted-ca.smalltrader.test:8443",
  "v2FinancialProbeUrl": "/api/mobile/v2/companies/01JCV8X9M2NQW3TYZ4R5S6789A/sales",
  "expectedMigrations": [
    "2026_11_10_000001_create_mobile_auth_tables",
    "2026_11_10_000002_create_mobile_devices_table",
    "2026_11_10_000003_create_mobile_event_receipts_table"
  ],
  "resolvedFlags": {
    "financial_v2_active": false,
    "offline_capture_active": false
  },
  "ttlExpiresAt": "2026-11-13T12:00:00Z",
  "allowHttpLoopback": false
}
```

### Key Reference
- `baseUrl`: HTTPS URL of disposable Laravel instance. Plain HTTP is rejected unless `allowHttpLoopback=true`.
- `backendCommitSha`: Expected git commit SHA of backend candidate (fails closed on mismatch).
- `manifestDigest`: Expected contract manifest digest (must match `e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7`).
- `staffEmail` / `staffPassword`: Ephemeral test credentials (may be provided inline or in a separate file referenced by `credentialsPath`).
- `deviceId`: Client UUID for the test runner.
- `companyId`: Server ULID of the target tenant company.
- `caCertPath`: Optional path to public test CA certificate in PEM format for isolated JVM trust.
- `wrongHostnameUrl` / `untrustedCertUrl`: Optional endpoints for negative TLS verification.
- `v2FinancialProbeUrl`: Optional probe endpoint to verify that unapproved V2 financial routes return 404/403.
- `ttlExpiresAt`: Disposable staging instance expiration timestamp.

---

## 4. Standalone Filtered Gradle JUnit Command

The read pilot smoke test is isolated from standard build suites:
- When `SMALL_TRADER_READ_PILOT_HANDOFF` is **unset**, the test reports explicit `SKIPPED (NOT RUN)`. It does not fail normal builds and does not silently pass.
- When `SMALL_TRADER_READ_PILOT_HANDOFF` is **set**, the test executes all 12 validation steps against the live disposable backend. Any failure (network unreachable, handshake failure, wrong status code, or schema mismatch) fails closed.

### Execution Command
```bash
JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9 \
ANDROID_HOME=/home/t3dev/Android/Sdk \
PATH="/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9/bin:$PATH" \
SMALL_TRADER_READ_PILOT_HANDOFF="/path/to/private/handoff.json" \
./gradlew :app:testDebugUnitTest \
  --tests "com.example.data.mobile.readclient.pilot.SmallTraderReadPilotSmokeTest"
```

### Combined Verification Command (Harness + 393 Focused Tests)
```bash
JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9 \
ANDROID_HOME=/home/t3dev/Android/Sdk \
PATH="/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9/bin:$PATH" \
./gradlew :app:testDebugUnitTest \
  --tests "com.example.data.mobile.*" \
  --tests "com.example.ui.staff.*" \
  --tests "com.example.MerchantPurchaseAccountingRegressionTest" \
  --tests "com.example.ProductPickerWorkflowRegressionTest" \
  --tests "com.example.ProductSearchPerformanceTest"
```

---

## 5. Backend Migration Order & Source Overlap Notes

### Source Overlaps
1. **PR #87 and PR #93**:
   - Both modify `app/Filament/Admin/Resources/InvoiceResource/Pages/InvoiceForm.php`
   - Both touch `app/Rules/SalesDocumentRules.php`
   - Both touch `app/Filament/Admin/Resources/SalesProductSelection.php`
   - *Resolution*: Normal merge candidate must reconcile PR #93's line decimal authority with PR #87's search indexes and UX rules.
2. **PR #93 and PR #94**:
   - Both modify `app/Providers/AppServiceProvider.php`
   - *Resolution*: Merge receipt identity service provider registrations cleanly after financial authority bindings.

### Migration Ordering Constraints
The backend migrations on the disposable Laravel instance must be run in this exact order:
1. `mobile_auth` v1 schema migrations (tokens, challenges, staff memberships).
2. `mobile_devices` table creation.
3. `mobile_event_receipts` table creation (receipt foreign keys **must** be declared after `mobile_devices` to prevent FK constraint failure).
4. Security hardening migrations.
5. Search index migrations from PR #87.

---

## 6. Minimal Follow-on PR Proposals (Post-Pilot Execution)

The following minimal, single-responsibility PRs are scheduled after read pilot verification:

1. **PR-A: Outbox Payload Encryption & Backup Exclusions (P0 before capture)**
   - Add SQLCipher / AES-GCM encrypted payload serializer for `OutboxEventEntity.payload_bytes`.
   - Update `AndroidManifest.xml`, `backup_rules.xml`, and `data_extraction_rules.xml` to explicitly exclude Room databases from cloud backup and device transfer.
   - Add key-loss, reboot, and app-upgrade migration tests.

2. **PR-B: WorkManager Sync Retry Scheduler (P0 completeness)**
   - Implement idempotent `SaleSyncWorker` using AndroidX WorkManager.
   - Enforce network constraint gating (CONNECTED only), exponential backoff, and execution window limits.

3. **PR-C: Authenticated V2 Financial Gateway & Canonical Receipt Reconciliation (P0 before writes)**
   - Implement `SaleSyncCoordinator` real V2 HTTP gateway.
   - Support status-before-resubmit, unknown-ACK recovery, and atomic rollback on absence-row races.

4. **PR-D: AUTH_BLOCKED Resolution & Manager Audit Workflow (P1)**
   - Define and implement same-scope staff re-authentication and manager reconciliation workflow.
   - Ensure physical cash taken during locked shifts is never abandoned or silently deleted.
