# V2 Online Cash Client Evidence Artifacts

This directory contains hermetic unit test evidence, Android lint reports, execution logs, and cryptographic manifests for Android V2 Online Cash and Status Client under PR #23.

## Status: FINANCIAL_PILOT_HOLD
- **Execution Source Commit**: `78ac4437e4010b68408278788dc82eb826fe8014` (`app/src/main/` 100% untouched vs qualified `753fb5133e28acb1284370dd7a84e3929e7b1a7d`)
- **Previous Execution Source Commit**: `753fb5133e28acb1284370dd7a84e3929e7b1a7d`
- **Scope**: Client implementation and unit QA qualification.
  - **basic_smoke**: PASS (hermetic MockWebServer unit fixtures).
  - **full_pilot**: NOT_RUN (lost-ACK fault injection, stock rollback, and paired live backend integration pending).
  - **E2E Claim**: Full E2E qualification is NOT claimed; status remains on **HOLD** pending coordinated disposal pairing with backend Laravel PR #102.
- **Resource Bounds**: `--max-workers=1`, `ActiveProcessorCount=2`, `SerialGC`, `1536m` in-process, `--no-daemon`.

## Attested Verification Summary (Commit 78ac443)

### 1. Unit Test Pass (`cleanTestDebugUnitTest testDebugUnitTest --no-build-cache`)
- **Total Test Suites**: 18
- **Total Tests**: 291
- **Passed**: 289
- **Skipped**: 2 (intentional opt-in paired smoke tests via `Assume.assumeTrue(isExternalPairingEnabled())`)
- **Failures / Errors**: 0 / 0
- **Duration**: 66.37s
- **Exit Code**: 0

### 2. Lint Pass (Baseline Qualified on 753fb51, Main Untouched)
- **Task**: `:app:lintDebug` (established on commit `753fb5133e28acb1284370dd7a84e3929e7b1a7d`)
- **Main Source State**: `app/src/main/` is 100% byte-for-byte identical between `753fb51` and `78ac443`.
- **Exit Code**: 0
- **Reported Issues**: **0 errors, 63 warnings observed**
- **Issue Category**: `IconLauncherShape` (baseline historical 63 known launcher icon silhouette warnings on legacy `res/mipmap-*/ic_launcher.png` resources).

### 3. Source Attestation & Pre/Post Git Pins
- `source-attestation-78ac.json` records actual git HEAD (`78ac4437e4010b68408278788dc82eb826fe8014`), working tree status, and cryptographic SHA256 hashes of all 30 relevant source and test files both **before** and **after** test execution. Pre-test and post-test hashes match identically.

## Disclosures & Audit Notes

### First Coordinated TLS Window Client Admission Failure Preservation
During the initial coordinated TLS window, client execution failed closed before any HTTP request or credential access because `peer_android_approved_head_sha` (`824cb7d...`) and `peer_android_executable_head_sha` (`753fb51...`) were compared against a single publication pin variable.
- **Retained Evidence**: Preserved historical record from `/tmp/root-readiness-bar7pq1o` (XML SHA `dc2340af6bfabddcef4fd1793a334de2ca5d47686e26b62d9fed1beaaa702e9d`, 2 tests: 1 PASS, 1 FAIL, 0 skip).
- **Audit Findings**: Verified safe `GET /_readiness_forbidden_probe 403` with NO financial route accessed; wrong expected nonce was a local comparison failure, not client HTTP negative.
- **Remediation**: Separated client publication HEAD pin (`SMALL_TRADER_EXPECTED_CLIENT_SHA`) and executable HEAD pin (`SMALL_TRADER_EXPECTED_CLIENT_EXECUTABLE_SHA`) with 6 new hermetic regressions in `CashPilotHandoffConfigTest`.

### Disclosure of Missing Intermediate Pins
Contemporaneous git commit pins from intermediate conversational iterations were not automatically captured by the environment harness at execution time. In accordance with strict reporting integrity, these intermediate pins are **not retroconstructed**. Complete cryptographic attestation is established on clean commits `753fb51` (`source-attestation-753f.json`) and `78ac443` (`source-attestation-78ac.json`).

### Timestamp Clarification
The timestamp in the prior manifest was recorded as `2026-11-14T19:46:00Z` due to the local system clock (UTC+2 / EET) being formatted with a trailing 'Z' instead of the '+02:00' timezone offset. The actual UTC time was `17:46Z`, consistent with git commit timestamps at `17:48Z` and XML generation at `17:45Z`.

### Mobile Contract Endpoint Clarification
A reviewer note referenced an example path `GET /v2/sales/events`. In the authoritative Mobile Sales Contract v2, event status queries are handled via `/api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status` (as implemented in `AndroidV2OnlineCashClient` and `Transport`). No non-contract `/v2/sales/events` path has been added, and no client TLS certificate / mTLS requirements have been invented.

## Suite Breakdown (18 Suites, 291 Tests)
1. `com.example.data.mobile.contract.v2.ContractV2WireValidationTest` (12 tests - PASS)
2. `com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest` (6 tests - PASS)
3. `com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest` (5 tests - PASS)
4. `com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest` (11 tests - PASS)
5. `com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest` (15 tests - PASS)
6. `com.example.data.mobile.contract.v2.SaleCommitResponseTest` (6 tests - PASS)
7. `com.example.data.mobile.contract.v2.SaleEventStatusResponseTest` (5 tests - PASS)
8. `com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest` (38 tests - PASS)
   - Regressions for wrong total status recovery, null payment status recovery, commit over-tender mismatch, and null payment_receipt_number.
9. `com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest` (43 tests - PASS, +6 new regressions)
   - Distinct publication vs executable pin acceptance (`testDistinctGenuinePubExecSuccess`).
   - Refusal of missing executable pin before credentials/network (`testMissingExternalExecutableShaRefusesBeforeCredentialsOrNetwork`).
   - Refusal of wrong publication pin (`testWrongPublicationShaRefuses`).
   - Refusal of wrong executable pin (`testWrongExecutableShaRefuses`).
   - Refusal of swapped pins (`testSwappedPublicationAndExecutablePinsRefuse`).
   - Current regression with publication `824cb7d` and executable `753fb51` (`testCurrent824PublicationAnd753ExecutableRegression`).
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
- `78ac-test-execution.log`: Current 18-suite unit test execution log (291 tests).
- `source-attestation-78ac.json`: Complete pre/post git HEAD, status, and 30-file source hash attestation for commit `78ac443`.
- `753f-test-execution.log`: Prior 18-suite unit test execution log (285 tests).
- `753f-lint-execution.log`: Prior `:app:lintDebug` execution log.
- `lint-results-debug.txt`: Raw Android lint results text output (0 errors, 63 baseline warnings).
- `source-attestation-753f.json`: Pre/post git HEAD and 30-file source hash attestation for commit `753fb51`.
- `9f7d-test-execution.log`: Historical 18-suite hermetic execution log from commit `9f7de14`.
- `ad10-test-execution.log`: Historical 14-suite execution log from commit `ad10a4c`.
- `first-0c0-test-failure.log`: Contemporaneous failure log from commit `0c0b1bb`.
- `manifest.json`: Cryptographic SHA256 hashes of all source files, XML artifacts, and execution logs.
- `summary.json`: Structured machine-readable qualification and audit record.

## Historical Evidence Separation
- `historical-76/`: 8 suites (76 tests) from initial baseline `f697451`.
- `historical-171-ddfc/`: 12 suites (171 tests) from model foundation `ddfc75536e647c65a6a7e8868c9336cee93769ec`.
- `historical-238-ad10/`: 14 suites (238 tests) from readiness helper refactor `ad10a4ceaa301f7d272cdf4702414f63a69a20c7`.
- `test-results/`: 18 suites (291 tests) from execution head `78ac4437e4010b68408278788dc82eb826fe8014`.
