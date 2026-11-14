# V2 Online Cash Client & Readiness Verification Evidence (PR #23)

## Qualification Scope
> **QUALIFIED for Android Client Implementation ONLY**
> The qualification status and test verification recorded herein apply strictly to the Android mobile client implementation and its local hermetic unit/mock tests under PR #23. It does NOT denote qualification across all financial backend systems or complete end-to-end financial qualification.

## Architectural Boundaries and Readiness Refactoring

This directory records verified execution evidence, test suite outputs, logs, and architectural boundaries for the Mobile V2 Online Cash client implementation under PR #23 at commit `ad10a4ceaa301f7d272cdf4702414f63a69a20c7`.

### Review Remediation and Code Alignment
1. **Fake Backend Route Helper Removed**:
   Removed Kotlin `handleChildReadinessRoute` and `ReadinessRouteResult` from `CashPilotHandoffConfig.kt`. These previously mirrored PHP backend route handling (`DisposableMobileV2BackendHarness::handleChildReadinessRoute`). HTTP route constants 405 and 403 were likewise removed.
2. **Authoritative Client Readiness Probe Extracted**:
   Implemented `CashPilotHandoffConfig.executeReadinessProbe(...)` returning `ReadinessProbeResult(statusCode, latencyMs, body, status, nonce)`. It enforces:
   - Loopback GET request with redirect following strictly disabled (`instanceFollowRedirects = false`).
   - Response status HTTP 200 (rejecting redirects and error codes).
   - Content-Type `application/json` (permitting charset suffix).
   - Bounded payload consumption (<= 64 KiB, `maxSizeBytes = 65536`) with oversized payload rejection.
   - Strict JSON parsing and object validation.
   - Status field equals `"ready"`.
   - Nonce matching expected nonce verbatim.
3. **Single Real Code Path**:
   `SmallTraderOnlineCashPilotSmokeTest.kt` Step 3 was refactored to call `CashPilotHandoffConfig.executeReadinessProbe(...)` directly, ensuring the smoke harness and unit tests exercise the identical client implementation.
4. **Hermetic Client Response Validation Tests via MockWebServer**:
   In `CashPilotHandoffConfigTest.kt`, removed the 4 fake mirror cases and replaced them with 9 hermetic unit tests using OkHttp `MockWebServer`:
   - `readinessProbe_success_200MimeNonceMatching`
   - `readinessProbe_redirectRejection_throwsException` (verifies HTTP 302 rejection)
   - `readinessProbe_oversizedResponse_throwsException` (verifies >64 KiB rejection)
   - `readinessProbe_invalidJson_throwsException` (verifies malformed JSON rejection)
   - `readinessProbe_nonceMismatch_throwsException` (verifies nonce mismatch rejection)
   - `readinessProbe_statusNotReady_throwsException` (verifies non-ready status rejection)
   - `readinessProbe_nonJsonMime_throwsException` (verifies non-JSON MIME rejection)
   - `readinessProbe_non200StatusCode_throwsException` (verifies HTTP 503 rejection)
   - `runtimeFiles_constantsMatchHarness` (verifies runtime handoff file names and probe path)
5. **Raw Task Execution Logs**:
   - `first-0c0-test-failure.log`: Contemporaneous raw log from initial execution of commit `0c0b1bb`, where string template interpolation was momentarily missing in exception messages.
   - `ad10-test-execution.log`: Full raw log from fresh rerun with `--rerun-tasks` at commit `ad10a4c`, verifying 33 actionable tasks executed, BUILD SUCCESSFUL in 2m 19s.
   - `first-compiler-failure.log`: Historical syntax error log from early quote escaping fix.

### Network Isolation and Resource Bounds
- **Backend Readiness Worker**: Backend worker `6e8c` completed with exit code 0 (GET 200 nonce CA+SAN in 8.236s), verified schema 0 root cleanup, and closed ports 18080 and 18443. All runtime handoff and TLS files were deleted before Android QA execution.
- **Zero External HTTP Calls**: No external HTTP requests were made. Both paired smoke tests (`SmallTraderOnlineCashPilotSmokeTest` and `SmallTraderReadPilotSmokeTest`) safely skipped via `Assume.assumeTrue`.
- **Resource Constraints**:
  - Max workers: 1 (`--max-workers=1`, `-Dorg.gradle.workers.max=1`)
  - Active processor count: 2 (`-XX:ActiveProcessorCount=2`)
  - Garbage collector: SerialGC (`-XX:+UseSerialGC`)
  - Heap: 1536 MiB (`-Xmx1536m`)
  - Daemon: Disabled (`--no-daemon`)
  - Execution mode: Single-process in-line task execution.
- **Production Code Unchanged**: `app/src/main` is 100% untouched and verified identical to `ddfc75536e647c65a6a7e8868c9336cee93769ec`.

---

## Test Suite Structure & Historical Separation

### 1. Current Verified Execution: 14 Suites / 238 Tests (Commit `ad10a4c`)
Stored in `test-results/`:
- `TEST-com.example.data.mobile.contract.v2.ContractV2WireValidationTest.xml` (12 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.contract.v2.OfflineSaleV2GoldenParityTest.xml` (6 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileGoldenParityTest.xml` (5 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.contract.v2.OnlineSaleIntentProfileMutationTest.xml` (11 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.contract.v2.PaymentDecimalConsistencyTest.xml` (15 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.contract.v2.SaleCommitResponseTest.xml` (6 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.contract.v2.SaleEventStatusResponseTest.xml` (5 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest.xml` (34 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest.xml` (35 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.readclient.pilot.ReadPilotHandoffConfigTest.xml` (19 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest.xml` (1 test, 0 failures, 1 skipped)
- `TEST-com.example.data.mobile.readclient.pilot.SmallTraderReadPilotSmokeTest.xml` (1 test, 0 failures, 1 skipped)
- `TEST-com.example.data.mobile.session.SecureStaffSessionStoreTest.xml` (37 tests, 0 failures, 0 skipped)
- `TEST-com.example.data.mobile.session.StaffSessionCoordinatorTest.xml` (51 tests, 0 failures, 0 skipped)
- **Total**: 14 suites, 238 tests (236 passed, 2 skipped via `Assume.assumeTrue`, 0 failures, 0 errors).

### 2. Historical Baseline: 12 Suites / 171 Tests (Commit `ddfc755`)
Stored in `historical-171-ddfc/` for audit continuity:
- 12 suites, 171 tests (170 passed, 1 skipped, 0 failures, 0 errors).

### 3. Historical Baseline: 8 Suites / 76 Tests (Commit `f697451`)
Stored in `historical-76/` for provenance continuity:
- 8 suites, 76 tests (76 passed, 0 failures, 0 errors).

---

## Heavy QA Lease Release
The short exclusive hermetic QA lease granted to Android is **formally released**. Subsequent work operates in the documentation lane only.
