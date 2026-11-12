# Paired Read Pilot: Android V1 HTTPS Staging & Gate Matrix

This document pins the integration candidate gate matrix, defines the hand-off contract between the Android client and the disposable Laravel staging backend, records backend migration dependencies and file overlaps, and establishes the standalone execution and verification harness for the paired read pilot.

---

## 1. Executive Summary & Provenance

- **Android Base**: Candidate PR #19 at exact commit [`d849e7903e5f87c31bebb1bd79b2e741fd1a8398`](https://github.com/Bassamalsaqqa/small-trader-android/commit/d849e7903e5f87c31bebb1bd79b2e741fd1a8398) (`t3/android-integration-candidate`), with verified zero remote drift.
- **Backend Verified Head (Bassamalsaqqa/account)**:
  - Composed backend commit SHA: `e111f83bc740e9590142869b30428eaa220eab6f` (clean composed foundation: `f5d16b356ed9c4d895e4fd07dcb469d756936d9b`).
  - PR #94: `7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb` (cross-version receipt identity, atomic rollback, financial authority)
  - PR #87: `912aa71f557b314837e70138df22615902fc9d7b` (OPEN: invoice UX, search indexes, document rules)
  - PR #88: `9f73ca8952da6bbfc0eabadbb01e51892ace8bc6` (catalog snapshot hash, O(n) scan)
  - PR #89: `cb97c9a7b864b3a40e5dc4fe03c9f168fd5c59e8` (Revision 2 contract mirror; not receipt idempotency)
  - PR #91: `f3836ef7aa8a2437e95fedaec3166399a44158b2` (Revision 3 financial contract mirror)
  - PR #92: `7b8531bfb932c73c26efee7f1ace6c5ae61fceaf` (dual-OFF boundary, safe default-deny)
  - PR #93: `1b8a696feda350bbc2df86a26d05b69fd615ba57` (currency precision authority, line decimal enforcement)
- **Frozen Contract Manifest Digests**:
  - `candidateRev3`: `e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7` (50 synchronized contract files).
  - `primaryPinRev2`: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`.
- **Room V1 Hash**: `6eddfecc8dabbb7ec370b858ec36714a` (schema unchanged).
- **Policy Invariant**: The repository owner has approved the **offline store-and-forward architecture** (Room durable outbox, strict scope-bound locks, canonical ACK verification, and offline sales data model), but **live financial capture, background sync activation, and live offline financial posting remain strictly OFF**. No broad policy relaxation is granted.

---

## 2. Gate Matrix

| Priority | Gate Scope | Description | Status | Owner | Required Evidence | Approval |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **P0** | **Freeze Compose + HTTPS V1 Auth/Read** | Freeze Compose UI to Staff Read Workspace (`STAFF_READ_WORKSPACE=true`); execute real HTTPS auth challenge → login → bootstrap → deterministic 2+1 product/customer pagination → empty nonce search → foreign tenant 403 → revoked device 401 → logout → revoked token 401 → negative TLS hostname/untrusted probes via `SmallTraderReadClient` + unchanged `OkHttpTransport` against disposable Laravel staging. | **VERIFIED PASS IN PAIRED PILOT** | Android Sub-agent / Lead | Filtered Gradle JUnit execution; sanitized JSON evidence at `evidence/paired-read-pilot-evidence.json` (`overallStatus: PASS`, 13 PASS, 1 NOT_RUN). | Lead / Owner |
| **P0** | **Android Outbox Encryption & Backup Exclusions** | Protect durable sale payloads at rest in Room. Plain SQLite `OutboxEventEntity.payload_bytes` is currently unencrypted BLOB; `AndroidManifest.xml` has `android:allowBackup="true"`. Must add explicit cloud/device-transfer exclusions (`backup_rules.xml`, `data_extraction_rules.xml`) and encrypt economic payloads at rest before live capture activation. | **HELD / PROPOSED** (Follow-on PR 2) | Android Security | Key loss, reinstall, reboot, upgrade tests; no clear-app-data workarounds. | Owner |
| **P0** | **V2 Production Adapter Authorization & Writers** | Implement real authenticated V2 HTTP gateway in `SaleSyncCoordinator`; canonical writers, atomic cross-version receipt identity, status-before-resubmit, and rollback on absence-row races. | **HELD / PROPOSED** (Follow-on PR 1) | Backend & Android | Integration tests with concurrent requests, rollback verification, absence-row race handling. | Owner |
| **P1** | **Receipt Canonical Retention & Backup Policy** | Raw canonical byte retention for offline/online sales receipts and backup access policy. No legal guesses; owner must define retention timeline and audit access. | **PENDING OWNER DECISION** | Business Owner / Legal | Written policy document signed off by owner. | Owner |
| **P1** | **Authoritative Currency Policy** | Exact decimal precision authority across Revision 2/3 transitions and historical invoices (2 vs 3 decimal places for JOD/ILS/USD). | **QUALIFIED IN CONTRACT** (PR #93 backend, Check 33 Android) | Financial Owner | Automated contract validation suite (`tools/validate-mobile-contract.py`). | Financial Lead |
| **P1** | **Cross-Tenant & Device Revocation** | Strict tenant isolation and device revocation enforcement. Revoked token, revoked device login, or foreign tenant access strictly denied. | **VERIFIED PASS IN PAIRED PILOT** (Step 8 foreign company 403, Step 8b revoked device login 401, Step 10 revoked token 401) | Android / Backend | Negative 401/403 test cases in read pilot smoke suite. | Lead |
| **P1** | **AUTH_BLOCKED Queue Resolution Policy** | Clear specification for resolving persisted `AUTH_BLOCKED` outbox queues while preserving physical cash taken during offline shifts. | **HELD / PROPOSED** (Follow-on PR 5) | Android / Security | Re-auth and manager reconciliation workflow documentation. | Owner |
| **P1** | **Idempotency & Lost ACK Recovery** | Handling network loss during sale submission: status probe before re-submitting payload to prevent duplicate financial invoices. | **DESIGNED IN CONTRACT** (Follow-on PR 3) | Android / Backend | Idempotency test suite across network failure points. | Lead |
| **P1** | **Stock & FEFO Conflict Policy** | Resolving stock shortages and FEFO batch conflicts when offline sales are synced after competing online sales. | **DEFERRED TO PILOT V2** | Operations Owner | Business rules specification for offline negative inventory. | Owner |
| **P1** | **Provisional Receipt Truthfulness** | UI must label offline receipts strictly as "Provisional / Unposted" until canonical server ACK is received with official invoice number. | **VERIFIED IN CONTRACT** (No fake POSTED status displayed) | Android UI | UI review against `SURFACE_COVERAGE.md`. | Product Owner |
| **P1** | **Device Acceptance & PR #88 Scan Performance** | Physical Pixel hardware validation (Arabic RTL, TalkBack, IME, barcode scanner, Bluetooth printer) + resolving PR #88's O(n) catalog hash snapshot full scan. | **BLOCKED UNVERIFIED** (No device infra / KVM in harness) | Hardware QA | Physical device run and catalog snapshot query benchmark. | Owner |

---

## 3. Backend Hand-off Contract

The backend integration thread provisions an isolated disposable MariaDB + HTTPS Laravel staging environment and exports a single JSON hand-off configuration accompanied by a separate private credentials file (`chmod 0600`).

### Environment Variables
- `SMALL_TRADER_READ_PILOT_HANDOFF`: Absolute file path to the hand-off JSON.
- `SMALL_TRADER_EXPECTED_BACKEND_SHA`: Required independent 40-character hex SHA pinning the expected composed backend commit (`e111f83bc740e9590142869b30428eaa220eab6f`).

### JSON Schema & Exact Keys
```json
{
  "status": "RUNNING",
  "baseUrl": "https://127.0.0.1:18400",
  "backendCommitSha": "e111f83bc740e9590142869b30428eaa220eab6f",
  "manifestDigest": {
    "candidateRev3": "e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7",
    "primaryPinRev2": "ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659"
  },
  "credentialsPath": "/path/to/private/credentials.json",
  "deviceId": "00000000-0000-4000-8000-000000000001",
  "deviceName": "Pixel 8 Pilot",
  "appVersion": "1.0.0",
  "platform": "android",
  "companyId": "01M78KCP1K8R0HZ33PCA7YK1NF",
  "invalidCompanyId": "01M78KCP5EGVM4QVRHMT8WYF81",
  "caCertPath": "/path/to/certs/ca.crt",
  "wrongHostnameUrl": "https://127.0.0.1:18600",
  "untrustedCertUrl": "https://127.0.0.1:18800",
  "expectedMigrations": [ ... 83 migrations ... ],
  "resolvedFlags": {
    "mobileV1": true,
    "mobileV2": false,
    "financial_v2_active": false,
    "offline_capture_active": false,
    "mobile.enabled": true,
    "mobile.v2_enabled": false
  },
  "ttlExpiresAt": "2026-11-12T21:22:45+00:00"
}
```

### Credentials File Schema (`credentialsPath`, chmod 0600)
```json
{
  "staff1": {
    "email": "[REDACTED]",
    "password": "[REDACTED]",
    "device_id": "00000000-0000-4000-8000-000000000001",
    "device_name": "Pixel 8 Pilot"
  },
  "staff2_mfa": {
    "email": "[REDACTED]",
    "password": "[REDACTED]",
    "device_id": "00000000-0000-4000-8000-000000000003",
    "device_name": "Pixel 8 Pilot",
    "totp_secret": "[REDACTED]",
    "recovery_codes": ["[REDACTED]", "[REDACTED]", "[REDACTED]"]
  },
  "revoked_device_id": "00000000-0000-4000-8000-000000000002"
}
```

---

## 4. Standalone Filtered Gradle JUnit Command

The read pilot smoke test is isolated from standard build suites:
- When `SMALL_TRADER_READ_PILOT_HANDOFF` is **unset**, the test reports explicit `SKIPPED (NOT RUN)`. It does not fail normal builds and does not silently pass.
- When `SMALL_TRADER_READ_PILOT_HANDOFF` is **set**, `SMALL_TRADER_EXPECTED_BACKEND_SHA` is **required**. The test executes all validation steps against the live disposable backend. Any failure fails closed and produces a sanitized FAIL evidence artifact.

### Execution Command
```bash
JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9 \
ANDROID_HOME=/home/t3dev/Android/Sdk \
PATH="/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9/bin:$PATH" \
SMALL_TRADER_READ_PILOT_HANDOFF="/home/t3dev/.t3/userdata/antigravity-tmp/pilot-harness-owned/handoff.json" \
SMALL_TRADER_EXPECTED_BACKEND_SHA="e111f83bc740e9590142869b30428eaa220eab6f" \
./gradlew :app:testDebugUnitTest \
  --tests "com.example.data.mobile.readclient.pilot.SmallTraderReadPilotSmokeTest"
```

### Combined Verification Command (Candidate + Pilot Harness)
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

### Migration Ordering Constraints
The backend migrations on the disposable Laravel instance run in strict sequence (83 migrations total, including mobile auth, indexes, and event receipts).

### Source Overlaps
1. **PR #87 and PR #93**:
   - Both modify `app/Livewire/Pages/Sales/InvoiceForm.php`
   - Both touch `app/Services/Sales/SalesDocumentRules.php`
   - Both touch `app/Services/Sales/SalesProductSelection.php`
   - *Resolution*: Normal merge candidate reconciles PR #93's line decimal authority with PR #87's search indexes and UX rules.
2. **PR #93 and PR #94**:
   - Both modify `app/Providers/AppServiceProvider.php`
   - *Resolution*: Merge receipt identity service provider registrations cleanly after financial authority bindings.

---

## 6. Authoritative Follow-on PR Ordering

1. **Step 1: Read Pilot Independent Proof (COMPLETED & VERIFIED)**
   - Paired HTTPS read pilot executed against disposable Laravel staging with zero leaks, verified 401/403 denials, and sanitized evidence at `evidence/paired-read-pilot-evidence.json`.
2. **Step 2: Canonical V2 Online Cash & Receipt Atomicity (Next)**
   - Implement `online_cash_full` exact payment allocation (`tender == total`), authoritative grants, real writers, server status verification, and receipt atomicity.
3. **Step 3: Outbox Payload Encryption & Backup Exclusions (Before Capture)**
   - Encrypt economic payloads at rest in Room (`OutboxEventEntity.payload_bytes`).
   - Add explicit cloud/device-transfer exclusions (`backup_rules.xml`, `data_extraction_rules.xml`).
   - Add key-loss, reboot, and app-upgrade migration tests.
4. **Step 4: Authenticated V2 Sync & Lost ACK Recovery**
   - Implement real authenticated V2 HTTP gateway in `SaleSyncCoordinator`.
   - Support status-before-resubmit, unknown-ACK recovery, and provisional receipt truthfulness.
5. **Step 5: WorkManager Sync Retry Scheduler (LAST after gateway/retry semantics)**
   - Implement idempotent `SaleSyncWorker` using AndroidX WorkManager with network constraint gating and exponential backoff.
6. **Step 6: Safe AUTH_BLOCKED Resolution Policy**
   - Define and implement same-scope staff re-authentication and manager reconciliation workflow.
   - Ensure physical cash taken during locked shifts is never abandoned or silently deleted.

---

## 7. Scope Limits & Explicit Boundary Disclaimers

> [!IMPORTANT]
> The following explicit scope boundaries are recorded to prevent overclaiming:
> 1. **Revocation Scope Limit**: Verifies `revoked_device_id` on login rejects with HTTP 401 (login refusal for revoked device), and verifies logout invalidates the active session token; it does NOT claim server-side issued token revocation via password epoch or global token blacklisting across devices without logout.
> 2. **Tenant Authorization Boundary**: Foreign company ID (`01M78KCP5EGVM4QVRHMT8WYF81`) returns HTTP 403 Forbidden; this is a real existing company fixture, proving tenant authorization denial. Nonexistent-company status and existence-oracle behavior were not tested.
> 3. **V2 Financial Boundary (Backend Supervisor Owned)**: Android V1 read client strictly stays within V1 routes (`/api/mobile/v1/...`). The V2 financial route is outside the V1 allowlist. Android makes NO wire claims for V2. The backend supervisor owns the actual HTTPS default-OFF 404 and container DenyFinancialExecutionAdapter evidence.
> 4. **Device & Hardware Scope Limits**: NO device hardware, Keystore hardware-backed keys, biometric authentication, physical barcode scanner, or Bluetooth printer claims are made (verified in JVM unit environment without emulator).


## Supervisor qualification delta

The original `18639cf7` wire run establishes HTTPS authentication/read lifecycle, pagination counts, and TLS denial. Its decimal regex did not prove fixture price equality; the follow-up requires exact product IDs/order/SKUs, six-decimal price strings, nullable tax pairs, and customer IDs/order from independent expectations. A null/null catalog tax pair is unassigned, not an explicit NoTax choice.

Independent expectations were supplied from a read-only query against the owned disposable MariaDB fixture schema, cross-checked with tracked seed literals, never from captured HTTP responses. Artifact SHA-256: `d02e666653937ec9dff6dc3edb1af8e25fcc47bbe0a9f2028bb7645b2e1ad3c7`. Backend pin: `e111f83bc740e9590142869b30428eaa220eab6f`. Both revision pins are mandatory; scalar manifest fallback is rejected, and file/parser entry points require an independent backend SHA by default. The original private handoff remains intact; a separate private derived runner input adds the independently supplied expectations.

This is JVM execution of the production client and transport against disposable HTTPS Laravel. It does not establish Compose device acceptance, hardware Keystore behavior, financial posting, or durable offline synchronization readiness.


Supervisor execution completed at `2026-11-12T20:11:26Z`: 19 parser tests plus the configured HTTPS smoke test passed (20 total, zero failures/errors/skips). All 13 V1 scenarios passed; V2 remains explicitly NOT_RUN in this harness. Product fixtures matched `25.000000`, `40.000000`, and `5.500000` without numeric conversion, alongside exact IDs/order/SKUs and null/null tax associations. Customer IDs/order matched independent expectations. The earlier pre-network explicit-null parser failure is preserved separately in `evidence/paired-read-pilot-supervisor-parser-failure.json`; the successful rerun is `evidence/paired-read-pilot-evidence.json`. Backend supervisor received completion acknowledgment before owned staging cleanup. No further network execution is required for source review.


Backend completion acknowledgment was honored: owned PHP/Caddy processes were stopped with PID/start-time checks, and only the nonce-verified disposable schema was dropped. The original handoff, independent fixture expectations, derived input, and private credentials were retained under existing permissions. That retired instance is not a target for later reruns; any future paired execution needs a newly verified handoff.


## Minimal follow-on PR proposals (activation remains held)

| Slice | Smallest implementation | Required acceptance before activation |
| --- | --- | --- |
| Android encrypted outbox + backup exclusions | Encrypt economic event payloads with authenticated scope/event/version binding; explicitly exclude database/session/installation artifacts from cloud and device-transfer backup. Preserve existing queued bytes and identity during conversion and key failure. | Tamper, missing/invalid key, interrupted conversion, reopen, upgrade, and backup-rule checks; no plaintext fallback or silent queue deletion. Hardware Keystore and device upgrade proof remain blocked pending an approved device host. |
| Laravel canonical V2 online cash | Close PR #94 lock-order, scope/error, typed-value, context, and test-analysis findings, then add a disabled-by-default adapter/route using real membership/financial grants and existing financial/inventory actions. | Atomic root receipt/posting/status transaction, cross-version company/event uniqueness, real MariaDB absence-row races/rollback and lost-ACK reconciliation, exact authoritative currency and payment allocation. Bearer identity alone grants no financial authority. |
| Android authenticated V2 gateway | Connect the existing status-before-submit coordinator to the independently qualified canonical backend, retaining original encrypted event identity and bytes. | Paired lost-ACK/replay, revoked membership/device, stock/FEFO conflict, and receipt matching tests. Offline stock conflicts require explicit reconciliation; provisional receipts must remain visibly provisional until server confirmation. |
| Auth-blocked resolution and scheduling | Define reviewed same-scope reauthentication/manager reconciliation, then add bounded WorkManager execution after gateway semantics are proven. | Cash already taken and queued events survive denial; login alone never unlocks queues; no blind retry of conflicts or duplicate financial writer. |

Owner approval is still required for financial activation/deployment/main merge, offline business and stock-conflict policy, and canonical receipt retention policy. These proposals authorize no live posting. The existing Android UX must be preserved through implementation; on-device geometry, Arabic/RTL interaction, instrumentation, and hardware-backed Keystore acceptance remain BLOCKED/UNVERIFIED under the current host constraint. PR #88's O(n) catalog hash snapshot scan also remains an explicit performance gate.

Offline supervisor regression: 413 tests across 39 classes, 412 passed, zero failures/errors, one explicit unconfigured-pilot skip. Both normal and optimized contract validators passed 303 checks each. `lintDebug` completed with 0 errors and 63 baseline warnings. Counts are recorded separately from the configured 20-test live/parser run.


## GitHub follow-up reviews: safety, source custody and dependency corrections

Reviews `account#94/5882502845` and `small-trader-android#20/5882510757` identify a new P0 in the backend test harness. `MobileEventReceiptIdentityTest` and `MobileReceiptRollbackAtomicityTest` removed RefreshDatabase but retained ownership checks in its uninvoked hook while teardown still performs broad deletes. **Do not execute either class against an existing database.** The backend supervisor owns an explicit setup guard, zero-mutation refusal proof, teardown revalidation and subsequent testing only in a freshly owned disposable MariaDB schema. Prior component test results do not clear this new test-safety gate. Financial activation and full-stack merge remain held.

The original paired run remains historical bounded V1 wire evidence. The backend SHA environment comparison checks metadata agreement; it alone does not attest which source a remote process executes. Local object verification found:

- Pilot commit: `e111f83bc740e9590142869b30428eaa220eab6f`.
- Tree: `f1ae3acebfab14d213254bfdfdc1588325f6febc`.
- Direct parent/composed foundation: `f5d16b356ed9c4d895e4fd07dcb469d756936d9b`.
- External retrieval was reported unavailable by the GitHub reviewer. A secret-inspected source-only review ref or verifiable source bundle, parent/merge inventory, migrations and environment-source hashes must be supplied by the backend supervisor before calling the pair independently reproducible. Do not resurrect the retired instance or alter the historical evidence to imply this gate has passed.

The dependency graph below was checked against GitHub PR base/head metadata and `git merge-base --is-ancestor` against the pilot commit. Each exact head is an ancestor of the pilot. This describes source inclusion, not authorization to merge or run unsafe tests.

| Backend PR | Base | Exact included head | Purpose |
| --- | --- | --- | --- |
| #86 | main | `614b1da44ef8715d64a2477e635246f760a3f84c` | Contract serializer foundation |
| #87 | main | `912aa71f557b314837e70138df22615902fc9d7b` | Web invoice/purchase workflow (open, not draft) |
| #88 | #86 branch | `9f73ca8952da6bbfc0eabadbb01e51892ace8bc6` | V1 auth/read API |
| #89 | #86 branch | `cb97c9a7b864b3a40e5dc4fe03c9f168fd5c59e8` | Revision 2 mirror |
| #91 | #89 branch | `f3836ef7aa8a2437e95fedaec3166399a44158b2` | Revision 3 mirror/pure helpers |
| #92 | #88 branch | `7b8531bfb932c73c26efee7f1ace6c5ae61fceaf` | Disabled V2 boundary |
| #93 | main | `1b8a696feda350bbc2df86a26d05b69fd615ba57` | Currency qualification |
| #94 | #92 branch | `7bb6d779e52ca2f284045d66f8f3f6aee7b4e6fb` | Receipt identity foundation; P0 test-safety hold |

The exact #87/#93 changed-file intersection is the three corrected Livewire/Services paths in section 5. At the pilot tree their Git blob IDs are respectively `903535dfbf04b5a0309feaaf4c51e9092bee3afe`, `f6bb7cd336d4a3911978bdfff7576c8482e6ccde`, and `fe027e83739f36f4c98439a059ad149eeef9353e`. The exact #93/#94 intersection is `app/Providers/AppServiceProvider.php`. These checks verify the recorded merge tree, not financial correctness of the resolutions.

Performance remains unqualified: the saved single run reports challenge 3441 ms, login 451 ms, bootstrap 296 ms, catalog 219 ms and customers 158 ms. It does not separate cold startup, TLS, JVM or Laravel costs and establishes neither warm latency nor p95. A future freshly owned staging run should record a bounded first-request/repeated-request trace with process/client lifecycle and source pins; no fast-launch claim is made from the retired run.

Next review gate: independently verify backend P0 refusal/teardown safety and published source custody, then review the smallest disabled-by-default V2 online cash-sale proposal using existing canonical financial/inventory actions and real grants. Preserve the working read pilot and UI; no financial posting is enabled by this documentation correction.
