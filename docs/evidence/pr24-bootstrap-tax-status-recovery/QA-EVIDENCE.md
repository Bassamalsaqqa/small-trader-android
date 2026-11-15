# PR #24 Focused Android Compile & QA Evidence Report

**Branch**: `t3/android-bootstrap-tax-status-recovery`  
**Base Pinned Commit**: `a29a10d610616d2c09f11017fc101672f5b2fc1c`  
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

Per lease terms, a maximum of two correction iterations were authorized strictly for trivial compile/API/fixture defects preserving all guards:

### Attempt 1: Production Compile Error
- **Commit**: `a29a10d610616d2c09f11017fc101672f5b2fc1c`
- **Elapsed**: 56.23s | **Exit Code**: 1
- **Defects Detected**:
  - `OnlineCashSaleOrchestrator.kt:407`: Unresolved reference `baseOrigin`.
  - `StaffWorkspaceViewModel.kt:914`: Missing import for `DurableOnlineSaleRecoveryRecord`.
- **Correction Applied (Iteration 1)**:
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
- **Correction Applied (Iteration 2 - Final Allowed Iteration)**:
  - Formatted `PayloadDigest("sha256:" + "0".repeat(64))`.
  - Mapped catch to `SessionStorageError.Corrupt(...)`.
  - Switched manual draft line calls to `StaffOnlineSaleDraftLine.fromProduct(..., explicitNoTax = true)`.
  - Imported `OnlineSaleContext`.
  - Pinned and committed in [`2c462ca`](https://github.com/Bassamalsaqqa/small-trader-android/commit/2c462ca6256d79a9586dfba8f6b05eb1daf3fd80).

### Attempt 3: Focused Test Execution
- **Commit**: `2c462ca6256d79a9586dfba8f6b05eb1daf3fd80`
- **Elapsed**: 72.45s | **Exit Code**: 1
- **Total Tests Completed**: 114
- **Passed**: 85 | **Failed**: 29 | **Errors**: 0 | **Skipped**: 0

---

## 3. Detailed Test Suite Results

| Test Class | Tests | Passed | Failed | Errors | Status |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `AndroidV2OnlineCashClientMockWebServerTest` | 38 | 38 | 0 | 0 | **PASS** (100%) |
| `StaffWorkspaceComposeTest` | 14 | 14 | 0 | 0 | **PASS** (100%) |
| `StaffOnlineSaleViewModelTest` | 36 | 29 | 7 | 0 | **PARTIAL** |
| `DurableOnlineSaleRecoveryStoreTest` | 26 | 4 | 22 | 0 | **FAIL** |
| **Total** | **114** | **85** | **29** | **0** | **BUILD FAILED** |

### Verified Working Capabilities
1. **Full MockWebServer Contract Parity**: All 38 tests in `AndroidV2OnlineCashClientMockWebServerTest` pass with exact RFC 8785 wire payload serialization, strict non-null server digests, and status recovery endpoints.
2. **End-to-End Compose UI Verification**: All 14 tests in `StaffWorkspaceComposeTest` pass cleanly, including:
   - Verification of the new `staff_sale_retry_recovery_button` on the Hold Card with `>= 48dp` touch bounds.
   - Successful trigger of `onRetryRecovery` callback without mutating or deleting ciphertext records.
   - Tax choice group chips and explicit no-tax toggles.

### Nature of Failures in Attempt 3
- `DurableOnlineSaleRecoveryStoreTest` (22 failures): Failed at `IntentProfileValidator.validate(profile)` because `createSampleRecord` constructs raw wire JSON that fails schema validation on fields (such as settlement mode or product structure).
- `StaffOnlineSaleViewModelTest` (7 failures): Assertions expecting specific recovery/isolation behavior transitions across mock scopes.
- **Rule Enforced**: Under the bounded QA grant ("if realbehavior/security/durability/resourcefailure STOP immediately release noautoretry"), execution was terminated immediately. No assertions were deleted and no production integrity guards were weakened.

---

## 4. Teardown & Lease Release

1. **Gradle Daemon Process Teardown**:
   - Commanded `./gradlew --stop`.
   - Verified process table with `pgrep -fl gradle`: confirmed **0 lingering Gradle daemons** (clean state).
2. **QA Lease Status**: **EXPLICITLY RELEASED**.
   - No further Gradle, build, or test executions will be attempted.
   - Slot freed for backend queue.
3. **Release Qualification Honesty**:
   - Focused unit test passes on Compose and Client mock suites do **NOT** constitute a qualified full UI/app release.
   - Full qualification awaits complete recovery fixture alignment and physical device verification.

---

## 5. Provenance & Preserved Logs

All raw logs and contemporaneous attestation JSON documents are archived in this directory:
- [`attempt-1/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-1/): Pre-invocation attestation, raw execution log (compile error), and teardown confirmation.
- [`attempt-2/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-2/): Pre-invocation attestation, raw execution log (test compile error), and teardown confirmation.
- [`attempt-3/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-3/): Pre-invocation attestation, raw execution log, post-invocation attestation with test results, XML test reports, and XML SHA-256 sums.
- [`SOURCE_RELEASE_MANIFEST.json`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/SOURCE_RELEASE_MANIFEST.json): Attested source file hashes and execution summary.
