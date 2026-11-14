# V2 Online Cash Client Evidence Artifacts

This directory contains hermetic unit test evidence, live coordinated readiness probe artifacts, Android lint reports, execution logs, and cryptographic manifests for Android V2 Online Cash and Status Client under PR #23.

## Status: READINESS_QUALIFIED / FINANCIAL_PILOT_HOLD
- **Readiness Verdict**: **PASS** (Live coordinated readiness probe verified HTTP 200 via CA-trusted TLS loopback in 13.34s)
- **Financial Pilot Status**: **HOLD / NOT_RUN** (Financial scenarios NOT_RUN; safe non-readiness GET returned 403; no authentication or financial HTTP dispatch; parser loaded private runtime credential file locally)
- **Client Publication Commit**: `58e8478bfdf02a1a681e6ff63bbbc06694b9f746` (documentation and evidence publication)
- **Client Executable Source Commit**: `78ac4437e4010b68408278788dc82eb826fe8014` (`app/src/main/` 100% untouched vs qualified `753fb5133e28acb1284370dd7a84e3929e7b1a7d`)
- **Backend Runtime Execution HEAD**: `d7c7324535b1e6586140ab1e3561966342d625d6` (source `a8540ff83513b49988d90c77cf284831cfd5b2de` frozen)
- **Scope Distinction**: Live readiness qualification is **STRICTLY SEPARATE** from the 291-test hermetic unit suite. This qualifies transport readiness ONLY, not overall financial transactions, stock rollback, or signed devices.

---

## 1. Live Coordinated Readiness Verification (PASS)

Coordinated readiness execution against live disposable backend harness (`1 JVM invocation`, `duration 13.3375s`, `exit 0`):

### Results
- **Test Suite**: `com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest`
- **Tests**: **2 total, 2 PASS, 0 SKIP, 0 FAIL, 0 ERROR**
- **Scenarios Evaluated**:
  1. `readiness_probe`: **PASS** (HTTP 200, latency 306ms, exact matching nonce verified via CA-trusted TLS loopback on `https://127.0.0.1:18443/_harness/readiness`)
  2. `testCashPilotFinancialAcceptanceEnforcement`: **PASS** (0.002s, unconfirmed states throw)
  3. `financial_cash_fresh_commit_201`: **NOT_RUN**
  4. `financial_cash_idempotent_replay_200`: **NOT_RUN**
  5. `financial_cash_event_status_posted`: **NOT_RUN**
  6. `financial_cash_tamper_409`: **NOT_RUN**
  7. `transport_lost_ack_recovery`: **NOT_RUN**
  8. `stock_permission_rollback_probes`: **NOT_RUN**

### Cryptographic Artifact Hashes (`coordinated-readiness/`)
- `TEST-com.example.data.mobile.readclient.pilot.SmallTraderOnlineCashPilotSmokeTest.xml`: `51e834a7f5d358e0e6544eb03b355e40130e0455d0d2c8de9b7099390b07c6eb`
- `paired-online-cash-pilot-evidence.json`: `0bfaba51e8ab55ae49ab5bf05f374fe4091d48c2a694dd4b810e120049246c5d`
- `root_outcome.json`: `dd267bc4f3a505d1558020758545ac1e40159114eca0b9538fb9f37839aeffe7`
- `android_result.json`: `8f9da64aa1645666d9ecba248a216e1774879017bfcac0d1b6b3333cccebf623`
- `android.log`: `be9aa06a8259901b324f0e7d90a3999ef0e6f52272fbcbc4b0f44e326068c283`
- `backend.log`: `fd7eedd59cd0b334a4d9305b8c65ec0697d8cff0c9d792602466f462202c5ebc`
- `live_verified.json`: `42528b2ead0e60c3bb1e35b727044e6e0ca9b7ad19dab180012527057ab76704`
- `backend_manifest.json`: `88c8c2e2389f7486e73c74f4f660aa0621bf3c8486a85590b6c4bbb44bb83f5f`
- `android_pre.json`: `3f948f6cfcc23bef892c96430326447a094f3a272b6ee4c605406b726415a1aa`
- `android_post.json`: `1fee9e7b4278b0904013d8f7eba38d7097b72bcaa3d93740ecf1261530e4d657`

### Disclosure of Evidence Serializer Metadata Omission
`CashPilotEvidence.toJson()` in `app/src/test/.../CashPilotEvidence.kt` serializes only `clientHead` (mapped to executable HEAD `78ac4437e4010b68408278788dc82eb826fe8014`) and omits separate `clientPublicationHead` and `clientExecutableHead` keys in `paired-online-cash-pilot-evidence.json` (SHA `0bfaba51e8ab55ae49ab5bf05f374fe4091d48c2a694dd4b810e120049246c5d`). Both publication HEAD (`653327243b2f5a4541c5fcf8cbe9520769e6be50`) and executable HEAD (`78ac4437e4010b68408278788dc82eb826fe8014`) were independently verified by the root harness environment pins (`SMALL_TRADER_EXPECTED_CLIENT_SHA` and `SMALL_TRADER_EXPECTED_CLIENT_EXECUTABLE_SHA`).

### Backend Cleanup & Port Closure
- Child harness exited with SIGTERM exit 1 as designed.
- Disposable schema (`accounting_p8_tmp_84fa14fb4cb2`) and temporary runtime directories were cleanly deleted.
- Ports 18080 and 18443 are verified closed.

---

## 2. Hermetic Unit Test Pass (291 Tests, Commit 78ac443)

- **Total Test Suites**: 18
- **Total Tests**: 291
- **Passed**: 289
- **Skipped**: 2 (intentional opt-in paired smoke tests via `Assume.assumeTrue(isExternalPairingEnabled())`)
- **Failures / Errors**: 0 / 0
- **Duration**: 66.37s
- **Resource Bounds**: `--max-workers=1`, `ActiveProcessorCount=2`, `SerialGC`, `1536m` in-process, `--no-daemon`.
- **Lint Invariant**: `:app:lintDebug` 0 errors, 63 baseline historical mipmap warnings on qualified source; `app/src/main/` 100% untouched.

---

## 3. Retained Failed Windows & Historical Failures

### Historical Unit Failures (Compiler & Mock)
- `first-compiler-failure.log`: Initial compilation failure on test refactor before parameter fixes.
- `first-0c0-test-failure.log`: First MockWebServer unit test failure before wire format corrections.

### Real Failed Coordinated Windows
1. **Window 1 (Zero JVM - Strict Root CA Validation Failure)**:
   - Location: `/tmp/root-readiness-eb_1btu5`
   - `root_outcome.json` SHA-256: `0eb6a27a628cea8e35cd9526ebdc470502cec33496d9954090ab7e595fe50a53`
   - Outcome: `ROOT_TLS_CA_VALIDATION_FAILED_BEFORE_JVM` ("CA cert does not include key usage extension")
   - Invocations: Zero Android JVM invocations occurred (`android_invocations: 0`). Failed during pre-flight root CA validation before JVM startup; zero private keys read; backend schema `accounting_p8_tmp_a5057b7ce693` cleanly purged.
2. **Window 2 (1 JVM - Client Admission Pin Comparison Failure)**:
   - Location: `/tmp/root-readiness-bar7pq1o`
   - XML SHA-256: `dc2340af6bfabddcef4fd1793a334de2ca5d47686e26b62d9fed1beaaa702e9d` (2 tests: 1 PASS, 1 FAIL, 0 SKIP)
   - Outcome: `CLIENT_PIN_MISMATCH_BEFORE_HTTP`
   - Details: Failed closed in `CashPilotHandoffConfig.fromFile` comparing single client pin against executable HEAD before HTTP dispatch. Safe non-readiness probe `GET /_readiness_forbidden_probe` returned HTTP 403; NO financial route / POST tested.

---

## Evidence Directory Organization
- `coordinated-readiness/`: 10 safe artifacts from the live coordinated readiness verification (XML, JSON, logs, root outcome, and attestations).
- `test-results/`: 18 hermetic XML test suites (291 tests) from commit `78ac443`.
- `historical-76/`, `historical-171-ddfc/`, `historical-238-ad10/`: Historical test milestones.
- `manifest.json`: Full cryptographic SHA256 manifest of all source files, XML artifacts, execution logs, and readiness evidence.
- `summary.json`: Structured machine-readable qualification and audit summary.
