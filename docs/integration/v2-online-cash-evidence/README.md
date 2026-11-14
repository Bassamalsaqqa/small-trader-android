# V2 Online Cash Client Evidence Artifacts

This directory contains hermetic unit test evidence, Android lint reports, execution logs, and cryptographic manifests for Android V2 Online Cash and Status Client under PR #23.

## Status: FINANCIAL_PILOT_HOLD
- **Execution Source Commit**: `753fb5133e28acb1284370dd7a84e3929e7b1a7d` (source files unchanged from `9f7de1478c27b68055e56dc169e90394486af5a7`)
- **Scope**: Client implementation and unit QA qualification.
  - **basic_smoke**: PASS (hermetic MockWebServer unit fixtures).
  - **full_pilot**: NOT_RUN (lost-ACK fault injection, stock rollback, and paired live backend integration pending).
  - **E2E Claim**: Full E2E qualification is NOT claimed; status remains on **HOLD** pending coordinated disposal pairing with backend Laravel PR #102.
- **Resource Bounds**: `--max-workers=1`, `ActiveProcessorCount=2`, `SerialGC`, `1536m` in-process, `--no-daemon`.

## Attested Verification Summary (Commit 753fb51)

### 1. Unit Test Pass (`cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`)
- **Total Test Suites**: 18
- **Total Tests**: 285
- **Passed**: 283
- **Skipped**: 2 (intentional opt-in paired smoke tests via `Assume.assumeTrue(isExternalPairingEnabled())`)
- **Failures / Errors**: 0 / 0
- **Duration**: 14.47s
- **Exit Code**: 0

### 2. Lint Pass (`:app:lintDebug`)
- **Task**: `:app:lintDebug`
- **Duration**: 68.17s
- **Exit Code**: 0
- **Reported Issues**: **0 errors, 63 warnings observed**
- **Issue Category**: `IconLauncherShape` (baseline historical 63 known launcher icon silhouette warnings on legacy `res/mipmap-*/ic_launcher.png` resources).

### 3. Source Attestation & Pre/Post Git Pins
- `source-attestation-753f.json` records actual git HEAD (`753fb5133e28acb1284370dd7a84e3929e7b1a7d`), working tree status, and cryptographic SHA256 hashes of all 30 relevant source and test files both **before** and **after** test execution and lint execution.

## Disclosures & Audit Notes

### Disclosure of Missing Intermediate Pins
Contemporaneous git commit pins from intermediate conversational iterations were not automatically captured by the environment harness at execution time. In accordance with strict reporting integrity, these intermediate pins are **not retroconstructed**. Complete cryptographic attestation is established on the clean source commit `753fb51` via `source-attestation-753f.json` and `manifest.json`.

### Timestamp Clarification
The timestamp in the prior manifest was recorded as `2026-11-14T19:46:00Z` due to the local system clock (UTC+2 / EET) being formatted with a trailing 'Z' instead of the '+02:00' timezone offset. The actual UTC time was `17:46Z`, consistent with git commit timestamps at `17:48Z` and XML generation at `17:45Z`. The current manifest records the authoritative UTC timestamp `2026-11-14T17:58:37Z` (local `19:58:37+02:00`).

### Mobile Contract Endpoint Clarification
A reviewer note referenced an example path `GET /v2/sales/events`. In the authoritative Mobile Sales Contract v2, event status queries are handled via `/api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status` (as implemented in `AndroidV2OnlineCashClient` and `Transport`). No non-contract `/v2/sales/events` path has been added, and no client TLS certificate / mTLS requirements have been invented.

## Suite Breakdown (18 Suites, 285 Tests)
1. `com.example.data.mobile.contract.v2.ContractV2WireValidationTest` (12 tests - PASS)
2. `com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest` (6 tests - PASS)
3. `com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest` (5 tests - PASS)
4. `com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest` (11 tests - PASS)
5. `com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest` (15 tests - PASS)
6. `com.example.data.mobile.contract.v2.SaleCommitResponseTest` (6 tests - PASS)
7. `com.example.data.mobile.contract.v2.SaleEventStatusResponseTest` (5 tests - PASS)
8. `com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest` (38 tests - PASS)
   - Regressions for wrong total status recovery, null payment status recovery, commit over-tender mismatch, and null payment_receipt_number.
9. `com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest` (37 tests - PASS)
   - Exact case-insensitive base MIME matching regressions (`application/json` before semicolon).
10. `com.example.data.mobile.readclient.pilot.ReadPilotHandoffConfigTest` (19 tests - PASS)
11. `com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest` (2 tests: 1 PASS, 1 SKIPPED)
    - `testCashPilotFinancialAcceptanceEnforcement`: PASS (asserts `PostedWithoutPaymentReference` and unconfirmed states fail acceptance).
    - `executePairedOnlineCashPilotSmoke`: SKIPPED (paired external execution requires live disposable backend).
12. `com.example.data.mobile.readclient.pilot.SmallTraderReadPilotSmokeTest` (1 test - SKIPPED)
13. `com.example.data.mobile.session.SecureStaffSessionStoreTest` (37 tests - PASS)
14. `com.example.data.mobile.session.StaffSessionCoordinatorTest` (51 tests - PASS)
15. `com.example.data.mobile.readclient.http.OkHttpMockWebServerTest` (14 tests - PASS)
16. `com.example.data.mobile.readclient.http.OkHttpTransportLoopbackServerTest` (12 tests - PASS)
17. `com.example.data.mobile.readclient.http.OkHttpTransportSecurityTest` (12 tests - PASS)
18. `com.example.data.mobile.readclient.http.PlatformHttpUrlConnectionAmbientEvidenceTest` (2 tests - PASS)

## Execution Logs and Evidence Files
- `753f-test-execution.log`: Current 18-suite unit test execution log (285 tests).
- `753f-lint-execution.log`: Current `:app:lintDebug` execution log.
- `lint-results-debug.txt`: Raw Android lint results text output (0 errors, 63 baseline warnings).
- `source-attestation-753f.json`: Complete pre/post git HEAD, status, and 30-file source hash attestation.
- `9f7d-test-execution.log`: Historical 18-suite hermetic execution log from commit `9f7de14`.
- `ad10-test-execution.log`: Historical 14-suite execution log from commit `ad10a4c`.
- `first-0c0-test-failure.log`: Contemporaneous failure log from commit `0c0b1bb`.
- `manifest.json`: Cryptographic SHA256 hashes of all source files, XML artifacts, and execution logs.
- `summary.json`: Structured machine-readable qualification and audit record.

## Historical Evidence Separation
- `historical-76/`: 8 suites (76 tests) from initial baseline `f697451`.
- `historical-171-ddfc/`: 12 suites (171 tests) from model foundation `ddfc75536e647c65a6a7e8868c9336cee93769ec`.
- `historical-238-ad10/`: 14 suites (238 tests) from readiness helper refactor `ad10a4ceaa301f7d272cdf4702414f63a69a20c7`.
- `test-results/`: 18 suites (285 tests) from execution head `753fb5133e28acb1284370dd7a84e3929e7b1a7d` (provenance commit `fed3bdf048a4ec9ed942a23905f39f9e18ec823f`).
