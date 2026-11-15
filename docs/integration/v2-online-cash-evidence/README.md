# V2 Online Cash Client Evidence Artifacts

This directory contains hermetic unit test evidence, live coordinated readiness probe artifacts, Android lint reports, execution logs, and cryptographic manifests for Android V2 Online Cash and Status Client under PR #23.

## Status: READINESS_QUALIFIED / FINANCIAL_PILOT_HOLD
- **Readiness Verdict**: **PASS** (Live coordinated readiness probe verified HTTP 200 via CA-trusted TLS loopback in 13.34s)
- **Financial Pilot Status**: **HOLD / NOT_RUN** (Financial scenarios NOT_RUN; safe non-readiness GET returned 403; no authentication or financial HTTP dispatch; parser loaded private runtime credential file locally)
- **Client Executable Source Commit**: `b7e61eb4ed0aed2f5040d9fc44f9764b310be530` (`app/src/main/` 100% untouched vs qualified `753fb5133e28acb1284370dd7a84e3929e7b1a7d`)
- **Backend Runtime Execution HEAD**: `d7c7324535b1e6586140ab1e3561966342d625d6` (source `a8540ff83513b49988d90c77cf284831cfd5b2de` frozen)
- **Scope Distinction**: Live readiness qualification is **STRICTLY SEPARATE** from the 291-test hermetic unit suite and the focused serializer test pass. This qualifies transport readiness ONLY, not overall financial transactions, stock rollback, or signed devices.

---

## 1. Synthetic Financial Golden Fixture Parity (Commit 88d4066)

Cross-repo golden fixture generated via actual `CashPilotEvidence.toJsonString()` (`CashPilotEvidence.kt` blob SHA `edfba4bc03372131a245b1cf1826811fc9066d5a` unchanged from `b7e61eb`) for PHP outer validator parity ([GitHub Review 5906853631](https://github.com/Bassamalsaqqa/small-trader-android/pull/23#pullrequestreview-5906853631)):

### Key Fixture Metadata & Invariants
- **Generation Method**: Executed `CashPilotHandoffConfigTest.testCashPilotEvidenceDeterministicSyntheticFinancialGolden` (1 test executed, 1 passed, 0 failures, 0 errors, exit code 0 in 84.0s).
- **Qualification Scope**: **SYNTHETIC FIXTURE GENERATION ONLY** (Unit test PASS). This is **STRICTLY NOT** a financial run or financial pass; no live network, TLS, DB, or financial transaction was executed.
- **Wire JSON Invariant**: `synthetic-financial-golden-wire.json` contains pure wire JSON output from `CashPilotEvidence.toJsonString()` with **zero extra synthetic flags or fields**.
- **Separate Provenance Wrapper**: `synthetic-financial-golden-wrapper.json` wraps the wire evidence and carries `"synthetic": true`, purpose, and cryptographic provenance separate from the wire payload.
- **Deterministic Pins & Scenarios**:
  - `clientPublicationHead`: `c00aedb70ea06f28ae42282149c030df76dc300d`
  - `clientExecutableHead`: `b7e61eb4ed0aed2f5040d9fc44f9764b310be530`
  - `clientHead`: `b7e61eb4ed0aed2f5040d9fc44f9764b310be530` (legacy alias)
  - `backendHead`: `cfc28baaaaf52521ecbc88f1ba4c621afee17221` (synthetic backend pin)
  - `lifecycleGrant`: `FINANCIAL_PAIRED_RUN`
  - `overallStatus`: `PASS`
  - **All 5 Basic Scenarios (PASS)**:
    1. `readiness_probe`: HTTP 200
    2. `financial_cash_fresh_commit_201`: HTTP 201
    3. `financial_cash_idempotent_replay_200`: HTTP 200
    4. `financial_cash_event_status_posted`: HTTP 200 (exact lowercase 'posted')
    5. `financial_cash_tamper_409`: HTTP 409
  - **2 Advanced Fault Scenarios (NOT_RUN)**:
    6. `transport_lost_ack_recovery`: HTTP null
    7. `stock_permission_rollback_probes`: HTTP null

### Cryptographic Artifact Hashes (`synthetic-golden-fixture/`)
- `synthetic-financial-golden-wire.json`: `25ae020de247d03c5aa57538592cd82574d62d6e4041e845ad11ea835c7f6aa6` (2,367 bytes)
- `synthetic-financial-golden-wrapper.json`: `7458f05b57fe149beec9e26ebec77015b45f2acfcc428687f10c216f80f1592d` (2,961 bytes)
- `TEST-com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest.xml`: `3b679a13c9a332588a959568b7158706720d9444491a770470e2dc6dfffa5174` (531 bytes)
- `golden-test-execution.log`: `86c49050ea8328572b254db009a644cae3abdd5fa3b0307c471d458cc1df2223` (3,029 bytes)
- `source-attestation-88d4.json`: `8a40f6aee8de16869263ea2b6300c2507e33bae49d9537b44d61e359303def14` (37,506 bytes)
- `manifest.json`: `2135861348655859da9ef4f232cbb1b3ba87debce0b9806340df62bce0476f08` (2,395 bytes)

---

## 2. Focused Serializer & Parser Test Pass (Commit b7e61eb)

Focused test execution verifying the serialization of explicit publication and executable client pins in `CashPilotEvidence.toJsonString()` ([GitHub Review 5906747082](https://github.com/Bassamalsaqqa/small-trader-android/pull/23#pullrequestreview-5906747082)):

### Results
- **Task**: `cleanTestDebugUnitTest testDebugUnitTest --no-build-cache --tests com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest`
- **Resource Bounds**: `--max-workers=1`, `ActiveProcessorCount=2`, `SerialGC`, `1536m` in-process, `--no-daemon`
- **Total Tests**: **44 passed, 0 skipped, 0 failures, 0 errors** in 78.56s (test execution time 0.395s)
- **Key Assertions Verified**:
  1. `testCashPilotEvidenceDistinctPublicationAndExecutablePinsSerialization`: Evaluates actual `CashPilotEvidence.toJsonString()`, strictly parsed via `StrictJsonParser.parse(json).asObject()`. Confirms exact values for `clientPublicationHead` and `clientExecutableHead`, confirms legacy `clientHead` alias, confirms omission of `clientPublicationHead` when null, and verifies canonical escaping of quotes and whitespace in pins.
  2. `testCashPilotEvidenceSerializationZeroLiteralNul`: Evaluates zero literal NUL byte invariant, sensitive data redaction (`[REDACTED_EMAIL]`, `[REDACTED_PATH]`), and verifies all three pin fields (`clientHead`, `clientPublicationHead`, `clientExecutableHead`).

### Cryptographic Artifact Hashes (`focused-serializer-test/`)
- `TEST-com.example.data.mobile.readclient.pilot.CashPilotHandoffConfigTest.xml`: `2d2f5ddfc9467b291214d5dd996b540733c57649d40e90a9c11dd09a53c0ac7b`
- `b7e6-focused-test-execution.log`: `86c5c40c4b1c4948b25d61954489eeba209f98f4e3c3a29ea18634ead1c4e386`
- `source-attestation-b7e6.json`: `7508b63d112bcf0238a46658d6580dd8147d0f925e972e6bb7d7ec59df8c8c36` (pre-test and post-test source hashes match identically at commit `b7e61eb4ed0aed2f5040d9fc44f9764b310be530`)

---

## 3. Live Coordinated Readiness Verification (PASS)

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

### Backend Cleanup & Port Closure
- Child harness exited with SIGTERM exit 1 as designed.
- Disposable schema (`accounting_p8_tmp_84fa14fb4cb2`) and temporary runtime directories were cleanly deleted.
- Ports 18080 and 18443 are verified closed.

---

## 4. Hermetic Unit Test Pass (291 Tests, Commit 78ac443)

- **Total Test Suites**: 18
- **Total Tests**: 291
- **Passed**: 289
- **Skipped**: 2 (intentional opt-in paired smoke tests via `Assume.assumeTrue(isExternalPairingEnabled())`)
- **Failures / Errors**: 0 / 0
- **Duration**: 66.37s
- **Resource Bounds**: `--max-workers=1`, `ActiveProcessorCount=2`, `SerialGC`, `1536m` in-process, `--no-daemon`.
- **Lint Invariant**: `:app:lintDebug` 0 errors, 63 baseline historical mipmap warnings on qualified source; `app/src/main/` 100% untouched.

---

## 5. Retained Failed Windows & Historical Failures

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
- `synthetic-golden-fixture/`: 6 artifacts for PHP validator cross-repo parity (wire JSON, wrapper, XML, log, attestation, manifest).
- `focused-serializer-test/`: 3 artifacts from the focused CashPilotHandoffConfigTest pass (XML, log, and attestation).
- `coordinated-readiness/`: 10 safe artifacts from the live coordinated readiness verification (XML, JSON, logs, root outcome, and attestations).
- `test-results/`: 18 hermetic XML test suites (291 tests) from commit `78ac443`.
- `historical-76/`, `historical-171-ddfc/`, `historical-238-ad10/`: Historical test milestones.
- `manifest.json`: Full cryptographic SHA256 manifest of all source files, XML artifacts, execution logs, and readiness evidence.
- `summary.json`: Structured machine-readable qualification and audit summary.
