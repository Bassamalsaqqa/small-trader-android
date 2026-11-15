# PR #24 Focused Android Compile & QA Evidence Report

**Branch**: `t3/android-bootstrap-tax-status-recovery`  
**Base Pinned Commits for QA Grants (Machine Full SHAs)**:
- Initial Grant: `a29a10d610616d2c09f11017fc101672f5b2fc1c`
- New Grant (Attempt 4): `ec7e51d185049ed12e571630c3852dd73e18b28c`
- Compile Fix Pin (Attempt 5): `74b6259a8cf19b43f4bbfdcb6970cb166ae7316e`
- Evidence Documentation: `7824772bf1c277c0e8a6d5705f01f2d8273221e7`
- Source Fix Pin (Attempt 6): `0eff096ca080aa44e424388e28438e105d967252`

> [!NOTE]
> **Errata on Prior Full Commit SHA Notation**:
> Prior turn text inadvertently referenced `78247728e833...` and `0eff096b797...`. The actual mechanical git SHAs verified via `git rev-parse HEAD` are `7824772bf1c277c0e8a6d5705f01f2d8273221e7` and `0eff096ca080aa44e424388e28438e105d967252`. All commit SHAs in this report are retrieved directly from `git rev-parse HEAD`.

**Target Environment**: Isolated Android Worktree `t3-7ac8b73d-abb5d6e52b3b3cf9`  
**Toolchain**:
- JDK: OpenJDK 21.0.13+9 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`)
- Android SDK: API 36 (compileSdk 36, targetSdk 36, minSdk 24; `/home/t3dev/.local/share/android-toolchain/sdk`)

---

## 1. QA Lease Claim & Bounded Execution Scope

The exclusive short Android QA lease was claimed following backend release to execute focused compile and test evaluation on four targeted test classes under bounded resource flags:
```bash
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-build-cache \
  --tests "com.example.data.mobile.onlinecash.DurableOnlineSaleRecoveryStoreTest" \
  --tests "com.example.ui.staff.sale.StaffOnlineSaleViewModelTest" \
  --tests "com.example.ui.staff.StaffWorkspaceComposeTest" \
  --tests "com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest" \
  --max-workers=1 \
  -Dorg.gradle.workers.max=1 \
  -Dorg.gradle.jvmargs="-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xmx1536m" \
  -Pkotlin.compiler.execution.strategy=in-process \
  --no-daemon
```

---

## 2. Invocation History & Correction Iterations

### Attempt 1: Production Compile Error
- **Commit**: `a29a10d610616d2c09f11017fc101672f5b2fc1c`
- **Elapsed**: 56.23s | **Exit Code**: 1
- **Defects Detected**:
  - `OnlineCashSaleOrchestrator.kt:407`: Unresolved reference `baseOrigin`.
  - `StaffWorkspaceViewModel.kt:914`: Missing import for `DurableOnlineSaleRecoveryRecord`.
- **Correction Applied**:
  - Added `val baseOrigin = DurableOnlineSaleRecoveryStore.canonicalizeOrigin(client.validatedBaseOrigin)` before recovery clear.
  - Added `import com.example.data.mobile.onlinecash.DurableOnlineSaleRecoveryRecord`.
  - Pinned and committed in [`1de90b8`](https://github.com/Bassamalsaqqa/small-trader-android/commit/1de90b8c7c8fedc1298f7d5a196419d37ebb9881).

### Attempt 2: Test Fixture Compile Error
- **Commit**: `1de90b8c7c8fedc1298f7d5a196419d37ebb9881`
- **Elapsed**: 115.74s | **Exit Code**: 1
- **Production Code Status**: `:app:compileDebugKotlin` PASSED cleanly.
- **Defects Detected in `:app:compileDebugUnitTestKotlin`**:
  - `DurableOnlineSaleRecoveryStoreTest.kt:426`: Non-existent helper `PayloadDigest.fromSha256HexString`.
  - `DurableOnlineSaleRecoveryStoreTest.kt:527`: Unresolved `SessionStorageError.DecryptionFailed`.
  - `StaffOnlineSaleViewModelTest.kt:2111, 2184, 2252, 2300`: Manual `StaffOnlineSaleDraftLine` constructor call missing `unitCode` and passing `NonnegativeDecimalQuantity`.
  - `StaffOnlineSaleViewModelTest.kt:2487, 2556`: Missing import for `com.example.data.mobile.contract.v2.OnlineSaleContext`.
- **Correction Applied**:
  - Formatted `PayloadDigest("sha256:" + "0".repeat(64))`.
  - Mapped catch to `SessionStorageError.Corrupt(...)`.
  - Switched manual draft line calls to `StaffOnlineSaleDraftLine.fromProduct(..., explicitNoTax = true)`.
  - Imported `OnlineSaleContext`.
  - Pinned and committed in [`2c462ca`](https://github.com/Bassamalsaqqa/small-trader-android/commit/2c462ca6256d79a9586dfba8f6b05eb1daf3fd80).

### Attempt 3: Initial Focused Test Run
- **Commit**: `2c462ca6256d79a9586dfba8f6b05eb1daf3fd80`
- **Elapsed**: 72.45s | **Exit Code**: 1
- **Total Tests Completed**: 114
- **Passed**: 85 | **Failed**: 29 | **Errors**: 0 | **Skipped**: 0

### Attempt 4: Focused QA Run Under New Grant
- **Commit**: `ec7e51d185049ed12e571630c3852dd73e18b28c`
- **Elapsed**: 115.06s | **Exit Code**: 1
- **Defect Detected**:
  - `StaffOnlineSaleViewModelTest.kt:2826:46`: Unresolved reference `ClientUuid.fromString`.
- **Correction Applied**:
  - Replaced `ClientUuid.fromString(eventIdStr)` with `ClientUuid.parse(eventIdStr)`.
  - Committed fix in [`74b6259`](https://github.com/Bassamalsaqqa/small-trader-android/commit/74b6259a8cf19b43f4bbfdcb6970cb166ae7316e) before invoking Attempt 5.

### Attempt 5: Bounded Test Execution
- **Commit**: `74b6259a8cf19b43f4bbfdcb6970cb166ae7316e`
- **Elapsed**: 71.07s | **Exit Code**: 1
- **Total Tests Completed**: 123
- **Passed**: 117 | **Failed**: 6 | **Errors**: 0 | **Skipped**: 0

### Attempt 6: Final Single-Invocation Execution Under Explicit Grant
- **Commit**: `0eff096ca080aa44e424388e28438e105d967252`
- **Elapsed**: 143.33s | **Exit Code**: 1
- **Total Tests Completed**: 124
- **Passed**: 121 | **Failed**: 3 | **Errors**: 0 | **Skipped**: 0

---

## 3. Detailed Test Suite Results (Attempt 6)

| Test Class | Tests | Passed | Failed | Errors | Skipped | Status | Time |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- | :---: |
| `AndroidV2OnlineCashClientMockWebServerTest` | 38 | 38 | 0 | 0 | 0 | **PASS** (100%) | 0.811s |
| `DurableOnlineSaleRecoveryStoreTest` | 26 | 26 | 0 | 0 | 0 | **PASS** (100%) | 0.170s |
| `StaffWorkspaceComposeTest` | 14 | 14 | 0 | 0 | 0 | **PASS** (100%) | 14.666s |
| `StaffOnlineSaleViewModelTest` | 46 | 43 | 3 | 0 | 0 | **PARTIAL** (93.5%) | 0.589s |
| **Total** | **124** | **121** | **3** | **0** | **0** | **BUILD FAILED** | **143.33s** |

### Verified Working Capabilities (121 Tests Passing)
1. **MockWebServer Wire & Transport Parity (38/38 PASS - 100%)**:
   - RFC 8785 wire payload canonicalization, non-null server digests, and status recovery endpoints fully verified.
2. **Durable Online Recovery Store (26/26 PASS - 100%)**:
   - 100% passing across all encrypted persistence, AES-GCM recovery, digest validation, directory durability sync, and scope isolation mechanics.
   - Both `testEnvelope_nearLimit_succeeds` and `testEnvelope_overflow_throwsException` pass cleanly via exact encoded envelope overhead calculation and internal `maxFileBytes` seam.
3. **Staff Workspace Compose UI (14/14 PASS - 100%)**:
   - Touch bounds for retry button (`>= 48dp`), hold card presentation, tax choice groups, and explicit no-tax toggle confirmed.
4. **Staff Online Sale ViewModel (43/46 PASS - 93.5%)**:
   - Up from 41/45 in Attempt 5 and 29/36 in Attempt 3.
   - Idempotent retry, lost-ACK status checking, auth token expiration handling, offline guards, and unreadable storage sequencing verified.

### Specific Failures Observed in Attempt 6 (3 Total)
1. `StaffOnlineSaleViewModelTest > testValidationFailed_lostAckRetainedDispatch_preservesRecordAndHold`:
   - `AssertionError` at line 2978 in test assertions.
2. `StaffOnlineSaleViewModelTest > testScopeSwitch_companyActorDevice_resetsPresentationWithoutLeakingOldUI_restoresOnReturn`:
   - `CanonicalSerializationException: token length is below minimum required length` at line 3144 (secondary session token fixture in test scope transition).
3. `StaffOnlineSaleViewModelTest > testCustomFactory_withRecoveryStore_storageFailure_zeroPost`:
   - `AssertionError` at line 2754 in factory assertion.

Under the single-invocation rule ("ONLY 1 invocation no autofix/retry even fixture failure; stop report then release"), execution was strictly terminated immediately without retries.

---

## 4. Teardown & Lease Release

1. **Gradle Daemon Process Teardown**:
   - Commanded `./gradlew --stop`.
   - Verified process table with `ps aux | grep -i gradle`: confirmed **0 lingering Gradle processes**.
2. **QA Lease Status**: **EXPLICITLY RELEASED**.
   - No further Gradle, build, or test executions will be attempted.
   - Slot freed for backend queue.
3. **Release Qualification Honesty**:
   - 121 passing tests (out of 124) demonstrate robust component-level correctness across Compose, MockWebServer, and Recovery stores (100% on 3 suites).
   - Full release qualification remains blocked pending alignment of the remaining 3 test fixture assertions.

---

## 5. Provenance & Preserved Logs

All raw logs and contemporaneous attestation JSON documents are archived in this directory:
- [`attempt-1/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-1/): Pre-invocation attestation, raw execution log (compile error), and teardown confirmation.
- [`attempt-2/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-2/): Pre-invocation attestation, raw execution log (test compile error), and teardown confirmation.
- [`attempt-3/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-3/): Pre-invocation attestation, raw execution log, post-invocation attestation with test results, XML test reports, and XML SHA-256 sums.
- [`attempt-4/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-4/): Pre-invocation attestation, raw execution log (compile error `ClientUuid.fromString`), and post-invocation attestation.
- [`attempt-5/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-5/): Pre-invocation attestation, raw execution log (123 tests: 117 pass, 6 fail), post-invocation attestation, test-results XMLs, and XML SHA-256 sums.
- [`attempt-6/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-6/): Pre-invocation attestation, raw execution log (124 tests: 121 pass, 3 fail), post-invocation attestation, test-results XMLs, and XML SHA-256 sums.
- [`SOURCE_RELEASE_MANIFEST.json`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/SOURCE_RELEASE_MANIFEST.json): Attested source file hashes and execution summary.
