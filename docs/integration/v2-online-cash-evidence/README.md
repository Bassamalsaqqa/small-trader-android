# V2 Online Cash Client & Orchestrator Evidence (PR #23)

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

## Execution Evidence (171 Tests)
All tests executed under authorized QA lease constraints (`-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xmx1536m --max-workers=1`):
- `test-results/`:
  - `TEST-com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest.xml` (34 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.ContractV2WireValidationTest.xml` (12 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest.xml` (6 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest.xml` (5 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest.xml` (11 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest.xml` (15 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.SaleCommitResponseTest.xml` (6 tests, 0 failures)
  - `TEST-com.example.data.mobile.contract.v2.SaleEventStatusResponseTest.xml` (5 tests, 0 failures)
  - `TEST-com.example.data.mobile.readclient.SharedTransportAllowlistRegressionTest.xml` (4 tests, 0 failures)
  - `TEST-com.example.data.mobile.readclient.SmallTraderReadClientWorkflowTest.xml` (21 tests, 0 failures)
  - `TEST-com.example.data.mobile.session.StaffSessionCoordinatorTest.xml` (51 tests, 0 failures)
  - `TEST-com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest.xml` (1 test, 1 skipped)
- Total: 171 tests (170 passed, 1 skipped, 0 failures, 0 errors).
