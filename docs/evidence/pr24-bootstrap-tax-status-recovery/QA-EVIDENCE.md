# PR #24 Focused Android Compile & QA Evidence Report

**Branch**: `t3/android-bootstrap-tax-status-recovery`  
**Base Pinned Commits for QA Grants (Machine Full SHAs)**:
- Initial Grant: `a29a10d610616d2c09f11017fc101672f5b2fc1c`
- New Grant (Attempt 4): `ec7e51d185049ed12e571630c3852dd73e18b28c`
- Compile Fix Pin (Attempt 5): `74b6259a8cf19b43f4bbfdcb6970cb166ae7316e`
- Evidence Documentation: `7824772bf1c277c0e8a6d5705f01f2d8273221e7`
- Source Fix Pin (Attempt 6): `0eff096ca080aa44e424388e28438e105d967252`
- Evidence Documentation 6: `7e442523dfbef122b0b0d2fd0973c658b401c178`
- Source Fix Pin (Attempt 7): `5f382ba77d5fa9dd87606c8f0bb9be31a4861f36`

> [!NOTE]
> **Errata on Prior Full Commit SHA Notation**:
> Prior turn text inadvertently referenced `78247728e833...` and `0eff096b797...`. The actual mechanical git SHAs verified via `git rev-parse HEAD` are `7824772bf1c277c0e8a6d5705f01f2d8273221e7` and `0eff096ca080aa44e424388e28438e105d967252`. All commit SHAs in this report are retrieved directly from `git rev-parse HEAD` without synthetic prefix extension.

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

### Attempt 6: Focused QA Run Under Explicit Grant
- **Commit**: `0eff096ca080aa44e424388e28438e105d967252`
- **Elapsed**: 143.33s | **Exit Code**: 1
- **Total Tests Completed**: 124
- **Passed**: 121 | **Failed**: 3 | **Errors**: 0 | **Skipped**: 0
- **Failures Identified**:
  1. `StaffOnlineSaleViewModelTest > testValidationFailed_lostAckRetainedDispatch_preservesRecordAndHold`: State transition left `isIndeterminate = true` after placing on hold.
  2. `StaffOnlineSaleViewModelTest > testCustomFactory_withRecoveryStore_storageFailure_zeroPost`: Storage failure reported error but did not place draft into blocking hold (`isPaymentUnverifiedHold = true`).
  3. `StaffOnlineSaleViewModelTest > testScopeSwitch_companyActorDevice_resetsPresentationWithoutLeakingOldUI_restoresOnReturn`: Secondary token fixture `"token_scope_1"` fell below 20-character session validation minimum.
- **Correction Applied**:
  - Pinned and committed in [`5f382ba77d5fa9dd87606c8f0bb9be31a4861f36`](https://github.com/Bassamalsaqqa/small-trader-android/commit/5f382ba77d5fa9dd87606c8f0bb9be31a4861f36).

### Attempt 7: Full Green Single-Invocation Test Execution
- **Commit**: `5f382ba77d5fa9dd87606c8f0bb9be31a4861f36`
- **Elapsed**: 138.92s | **Exit Code**: 0 (BUILD SUCCESSFUL)
- **Total Tests Completed**: 124
- **Passed**: 124 | **Failed**: 0 | **Errors**: 0 | **Skipped**: 0 (100% PASS)

---

## 3. Detailed Test Suite Results (Attempt 7)

| Test Class | Tests | Passed | Failed | Errors | Skipped | Status | Time | XML Report SHA-256 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| `AndroidV2OnlineCashClientMockWebServerTest` | 38 | 38 | 0 | 0 | 0 | **PASS** | 0.784s | `288fbf85dc0342fda8eeafb37481ab8d070d1354c85dbbc463b5fb94cea041b5` |
| `DurableOnlineSaleRecoveryStoreTest` | 26 | 26 | 0 | 0 | 0 | **PASS** | 0.141s | `0ad4ac50f2115a39ffb7603dcc36d4af7643995d1a3b7a88a02819e96d13ee51` |
| `StaffWorkspaceComposeTest` | 14 | 14 | 0 | 0 | 0 | **PASS** | 10.907s | `4db2d8c4a5493590b85853d3ca58454be8bc47e814a8e38ac8f4f5d5d30aa2bc` |
| `StaffOnlineSaleViewModelTest` | 46 | 46 | 0 | 0 | 0 | **PASS** | 0.530s | `73b0c0329484ed4b4dbc3ce1d65742560292dd27ff413512ef46ebef2b3f9155` |
| **Total** | **124** | **124** | **0** | **0** | **0** | **100% PASS** | **138.92s** | *All XML hashes attested in `xml-sha256.txt`* |

---

## 4. Qualification Boundary & Engineering Attestation

> [!IMPORTANT]
> **QUALIFICATION SCOPE & HARDWARE BOUNDARIES**:
> - **Execution Scope**: Unit, Robolectric Compose, and OkHttp MockWebServer tests ONLY.
> - **Filesystem & Durability**: Durability error handling and recovery storage atomic replace mechanics are validated exclusively through the injected `DirectorySyncEngine` seam. These tests **DO NOT** prove real Android OS `fsync` semantics, sudden power-loss survival, physical flash wear, or actual hardware-backed AndroidKeyStore StrongBox custody.
> - **Production Durability**: Real device OS durability and flash retention remain **UNVERIFIED**.
> - **Acceptance & Release**: This unit verification does **NOT** constitute a financial service release grant, paired hardware qualification, or physical UI device acceptance.

---

## 5. Attestation Record Keys & Execution Provenance

- **Pre-invocation Attestation**: Uses `git_status_porcelain` as generated contemporaneously by `runner_attempt7.py` ([`pre_invocation_attestation.json`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-7/pre_invocation_attestation.json)).
- **Post-invocation Attestation**: Uses `git_status` as generated contemporaneously ([`post_invocation_attestation.json`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-7/post_invocation_attestation.json)).
- Both files are preserved strictly as emitted at execution time with no retroactive key normalization.
- **Actual Gradle Argv Recorded**:
  `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-build-cache --tests com.example.data.mobile.onlinecash.DurableOnlineSaleRecoveryStoreTest --tests com.example.ui.staff.sale.StaffOnlineSaleViewModelTest --tests com.example.ui.staff.StaffWorkspaceComposeTest --tests com.example.data.mobile.onlinecash.AndroidV2OnlineCashClientMockWebServerTest --max-workers=1 -Dorg.gradle.workers.max=1 -Dorg.gradle.jvmargs=-XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xmx1536m -Pkotlin.compiler.execution.strategy=in-process --no-daemon`

---

## 6. Teardown & Lease Release

1. **Gradle Daemon Process Teardown**:
   - Commanded `./gradlew --stop`.
   - Verified process table with `ps aux | grep -i gradle`: confirmed **0 lingering Gradle processes**.
2. **QA Lease Status**: **EXPLICITLY RELEASED**.
   - No further Gradle, build, or test executions will be attempted.
   - Slot freed for backend queue.

---

## 7. Preserved Execution Logs & Raw XMLs

All raw logs and contemporaneous attestation JSON documents are archived in this directory:
- [`attempt-1/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-1/): Pre-invocation attestation, raw execution log (compile error), and teardown confirmation.
- [`attempt-2/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-2/): Pre-invocation attestation, raw execution log (test compile error), and teardown confirmation.
- [`attempt-3/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-3/): Pre-invocation attestation, raw execution log, post-invocation attestation with test results, XML test reports, and XML SHA-256 sums.
- [`attempt-4/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-4/): Pre-invocation attestation, raw execution log (compile error `ClientUuid.fromString`), and post-invocation attestation.
- [`attempt-5/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-5/): Pre-invocation attestation, raw execution log (123 tests: 117 pass, 6 fail), post-invocation attestation, test-results XMLs, and XML SHA-256 sums.
- [`attempt-6/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-6/): Pre-invocation attestation, raw execution log (124 tests: 121 pass, 3 fail), post-invocation attestation, test-results XMLs, and XML SHA-256 sums.
- [`attempt-7/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-7/): Pre-invocation attestation, raw execution log (124 tests: 124 pass, 0 fail), post-invocation attestation, test-results XMLs, and XML SHA-256 sums.
- [`SOURCE_RELEASE_MANIFEST.json`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/SOURCE_RELEASE_MANIFEST.json): Attested source file hashes and execution summary.
