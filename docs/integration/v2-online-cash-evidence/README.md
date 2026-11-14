# V2 Online Cash Client Evidence Artifacts

This directory contains hermetic unit test evidence, execution logs, and cryptographic manifests for Android V2 Online Cash and Status Client under PR #23.

## Status: FINANCIAL_PILOT_HOLD
- **Execution Source Commit**: `9f7de1478c27b68055e56dc169e90394486af5a7`
- **Scope**: Client implementation and unit QA qualification.
- **Financial Status**: Online cash pilot execution remains on **HOLD** pending coordinated disposal pairing with backend Laravel PR #102.
- **Resource Bounds**: `--max-workers=1`, `ActiveProcessorCount=2`, `SerialGC`, `1536m` in-process.

## Summary of Executed Test Suites (Commit 9f7de14)
- **Total Test Suites**: 18
- **Total Tests**: 285
- **Passed**: 283
- **Skipped**: 2 (intentional opt-in paired smoke tests via `Assume.assumeTrue`)
- **Failures**: 0
- **Errors**: 0
- **Duration**: 21s

### Suite Breakdown
1. `com.example.data.mobile.contract.v2.ContractV2WireValidationTest` (12 tests - PASS)
2. `com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest` (6 tests - PASS)
3. `com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest` (5 tests - PASS)
4. `com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest` (11 tests - PASS)
5. `com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest` (15 tests - PASS)
6. `com.example.data.mobile.contract.v2.SaleCommitResponseTest` (6 tests - PASS)
7. `com.example.data.mobile.contract.v2.SaleEventStatusResponseTest` (5 tests - PASS)
8. `com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest` (38 tests - PASS)
   - Includes regressions for wrong total status recovery, null payment status recovery, commit over-tender mismatch, and null payment_receipt_number.
9. `com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest` (37 tests - PASS)
   - Includes exact case-insensitive base MIME matching regressions.
10. `com.example.data.mobile.readclient.pilot.ReadPilotHandoffConfigTest` (19 tests - PASS)
11. `com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest` (2 tests: 1 PASS, 1 SKIPPED)
    - `testCashPilotFinancialAcceptanceEnforcement`: PASS (proves PostedWithoutPaymentReference and unconfirmed states fail acceptance).
    - `executePairedOnlineCashPilotSmoke`: SKIPPED (requires active pairing handoff).
12. `com.example.data.mobile.readclient.pilot.SmallTraderReadPilotSmokeTest` (1 test - SKIPPED)
13. `com.example.data.mobile.session.SecureStaffSessionStoreTest` (37 tests - PASS)
14. `com.example.data.mobile.session.StaffSessionCoordinatorTest` (51 tests - PASS)
15. `com.example.data.mobile.readclient.http.OkHttpMockWebServerTest` (14 tests - PASS)
16. `com.example.data.mobile.readclient.http.OkHttpTransportLoopbackServerTest` (12 tests - PASS)
17. `com.example.data.mobile.readclient.http.OkHttpTransportSecurityTest` (12 tests - PASS)
18. `com.example.data.mobile.readclient.http.PlatformHttpUrlConnectionAmbientEvidenceTest` (2 tests - PASS)

## Historical Evidence Separation
- `historical-76/`: 8 suites (76 tests) from initial baseline `f697451`.
- `historical-171-ddfc/`: 12 suites (171 tests) from model foundation `ddfc75536e647c65a6a7e8868c9336cee93769ec`.
- `historical-238-ad10/`: 14 suites (238 tests) from readiness helper refactor `ad10a4ceaa301f7d272cdf4702414f63a69a20c7`.
- `test-results/`: 18 suites (285 tests) current head `9f7de1478c27b68055e56dc169e90394486af5a7`.

## Execution Logs
- `9f7d-test-execution.log`: Current 18-suite hermetic execution log (285 tests).
- `ad10-test-execution.log`: Historical 14-suite rerun log (238 tests).
- `first-0c0-test-failure.log`: Contemporaneous failure log from commit `0c0b1bb` showing string interpolation defect.
- `manifest.json`: Cryptographic SHA256 hashes of all XML artifacts and logs.
- `summary.json`: Structured machine-readable qualification and audit record.
