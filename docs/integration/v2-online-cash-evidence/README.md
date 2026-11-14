# V2 Online Cash Client & Orchestrator Evidence (PR #23)

## Qualification Scope
> **QUALIFIED for Android Client Implementation ONLY**
> The qualification status and test verification recorded herein apply strictly to the Android mobile client implementation and its local hermetic unit/mock tests under PR #23. It does NOT denote qualification across all financial backend systems or complete end-to-end financial qualification.

## Architectural Boundaries and Review Remediation

This directory records verified execution evidence, schemas, and architectural boundaries for the Mobile V2 Online Cash client implementation under PR #23.

### Review Remediation and Hardening
1. **Compilation Errors Fixed**:
   Prior commit f697451 introduced syntax errors from unescaped double quotes in Kotlin string templates in `PreparedOnlineSaleIntent.kt` and `OnlineCashSaleReceipt.toString()`. These were fixed and verified. The initial failure log is preserved in `first-compiler-failure.log`.
2. **Post-Network Scope Guarding Across ALL Result Branches**:
   Previously, only pre-dispatch and 401 branches checked session generation. A 201 Created or 200 replay response returning after account switch or logout could return a foreign stale receipt. `OnlineCashSaleOrchestrator` now guards ALL result branches after network I/O. If session generation or active scope (`actorId`, `deviceId`, `companyId`, `generation`) has changed, it returns `ScopeMismatch` (or `StaleAuthDenied` for 401) without leaking receipts or locking a new session scope.
3. **Strict Canonical Wire Byte Equality**:
   `PreparedOnlineSaleIntent.prepare` strictly enforces that raw wire bytes match RFC 8785 canonical JSON bytes of the RAW 7 request keys (`client_event_id`, `device_id`, `customer_id`, `warehouse_id`, `currency`, `lines`, `payment`) via `CanonicalJsonSerializer.serializeToUtf8Bytes(rootObj)`. Any whitespace, formatting discrepancy, or non-canonical key order fails closed before dispatch. The 10-key context profile digest is independently computed and verified.
4. **Economic Dispatch Restriction**:
   Low-level `submitSale` is marked `internal` to prevent bypassing verified intent preparation. Public economic dispatch is strictly bound to `executeOnlineCashSale(token, intent: PreparedOnlineSaleIntent)`.
5. **Sanitized Error Categories**:
   Raw exception messages (`e.message`), causes, and untrusted string IDs have been removed from errors and `toString()`. Standard fixed sanitized categories are returned (`SCOPE_VALIDATION_FAILED`, `FINANCIAL_CONSISTENCY_MISMATCH`, `EVENT_PARITY_MISMATCH`, etc.).
6. **Authoritative Full Cash Consistency**:
   `ConfirmedFinancialSuccess` requires:
   - Non-null `paymentId` and `paymentReceiptNumber`.
   - `amountPaid == profile.payment.amount`.
   - `amountPaid - changeReturned == grandTotal`.
   If `paymentId` is null, returns schema-honest `PostedWithoutPaymentReference` without fabricating synthetic IDs. Financial amount discrepancies fail closed with `FINANCIAL_CONSISTENCY_MISMATCH`.
7. **Source-Pinned Fail-Closed Smoke Harness**:
   `SmallTraderOnlineCashPilotSmokeTest` requires `SMALL_TRADER_CASH_PILOT_HANDOFF` and independent `SMALL_TRADER_EXPECTED_BACKEND_SHA`. When configured, missing required pins (`manifestDigests`) strictly fails closed (assertion failure, not skipped).

## Provenance Corrections and Evidence Structure

### 1. Backend Commit Prefix vs Port Clarification
Reference `9935` in review documentation refers to a backend commit SHA prefix (in backend repository history), NOT a network or TCP port number. No mock, dummy, or fabricated network endpoints have been added.

### 2. Compiler Failure Log Verification
The raw compiler failure log `first-compiler-failure.log` has verified SHA-256:
`3cb8da03f50248025170ffaa8b18f069d2ee10efc0cdb089965889ae2278490f`
An earlier review handoff erroneously cited an `ab62...` hash. The manifest accurately records `3cb8da...`, and the historical raw log is preserved unedited.

### 3. Backend Synthetic Fixture Pins
Authoritative synthetic fixture references in `manifest.json`:
- `backendQualifiedCommit`: `"6981511405d7bcd04cd8f8a09e64443ce0aaf47e"` (full 40-character string SHA)
- `exactSyntheticFixtureCommit`: `"ca5a58c207dd4594302b7e7cae1d7cd4fd733fc0"`
- `manifestSha256`: `"3248b9721c3e2a09e84ae478ddb0171c3ea576fd0cbdd70b3606e4cdda4ff3e7"`
- `normativeProfileDigest`: `"sha256:0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630"`

### 4. Execution Source Commit & Provenance Gap Disclosure
- `executionSourceCommit`: `ddfc75536e647c65a6a7e8868c9336cee93769ec`
- **Provenance Gap Disclosure**: In git history, the code fixes (compilation fix, post-network scope guarding) and the execution evidence (test XMLs) were committed together in `ddfc75536e647c65a6a7e8868c9336cee93769ec`. The preceding commit `f697451719d2940bde89a8aab5b6770d93c7b823` had compilation errors. Contemporaneous before/after pins were not separately captured; the 7 source file hashes in `manifest.json` are verified against `ddfc75536e647c65a6a7e8868c9336cee93769ec` source files.

### 5. Historical 76-Test Evidence (Restored from Git f697451)
`historical-76/` contains the 8 test suite XML files restored from git commit `f697451` (reflecting historical 39fea / recorded references):
- `TEST-com.example.data.mobile.contract.v2.ContractV2WireValidationTest.xml` (12 tests)
- `TEST-com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest.xml` (6 tests)
- `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest.xml` (5 tests)
- `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest.xml` (11 tests)
- `TEST-com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest.xml` (15 tests)
- `TEST-com.example.data.mobile.contract.v2.SaleCommitResponseTest.xml` (6 tests)
- `TEST-com.example.data.mobile.contract.v2.SaleEventStatusResponseTest.xml` (5 tests)
- `TEST-com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest.xml` (16 tests)
- **Total**: 8 suites, 76 tests (76 passed, 0 failures, 0 errors, 0 skipped).
*Note*: This historical suite is recorded for provenance and regression continuity; it represents the historical state prior to the 171-test expanded suite and is NOT the current pass.

## Current Execution Evidence (171 Tests)
All tests executed under authorized QA lease constraints (`-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xmx1536m --max-workers=1`):
- `test-results/`:
  - `TEST-com.example.data.mobile.contract.v2.ContractV2WireValidationTest.xml` (12 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest.xml` (6 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest.xml` (5 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest.xml` (11 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest.xml` (15 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.SaleCommitResponseTest.xml` (6 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.SaleEventStatusResponseTest.xml` (5 tests, 0 failures)
  - `TEST-com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest.xml` (34 tests, 0 failures)
  - `TEST-com.example.data.mobile.readclient.SharedTransportAllowlistRegressionTest.xml` (4 tests, 0 failures)
  - `TEST-com.example.data.mobile.readclient.SmallTraderReadClientWorkflowTest.xml` (21 tests, 0 failures)
  - `TEST-com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest.xml` (1 test, 1 skipped)
  - `TEST-com.example.data.mobile.session.StaffSessionCoordinatorTest.xml` (51 tests, 0 failures)
- Total: 12 suites, 171 tests (170 passed, 1 skipped, 0 failures, 0 errors).
- *Single Skipped Test*: `SmallTraderOnlineCashPilotSmokeTest` is a dormant pilot requiring opt-in custody handoff `SMALL_TRADER_CASH_PILOT_HANDOFF` and independent backend pin, NOT an E2E failure.
