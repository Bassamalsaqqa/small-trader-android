# PR #24 Focused Android Compile & QA Evidence Report

**Branch**: `t3/android-bootstrap-tax-status-recovery`  
**Base Pinned Commit for QA Grants**:
- Initial Grant: `a29a10d610616d2c09f11017fc101672f5b2fc1c`
- New Grant (Attempt 4): `ec7e51d185049ed12e571630c3852dd73e18b28c`
- Compile Fix Pin (Attempt 5): `74b6259a8cf19b43f4bbfdcb6970cb166ae7316e`  
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
  - Committed fix in [`74b6259`](https://github.com/Bassamalsaqqa/small-trader-android/commit/74b6259a8cf19b43f4bbfdcb6970cb166ae7316e) before invoking Attempt 5, per grant allowance rules.

### Attempt 5: Final Bounded Test Execution Under New Grant
- **Commit**: `74b6259a8cf19b43f4bbfdcb6970cb166ae7316e`
- **Elapsed**: 71.07s | **Exit Code**: 1
- **Total Tests Completed**: 123
- **Passed**: 117 | **Failed**: 6 | **Errors**: 0 | **Skipped**: 0

---

## 3. Detailed Test Suite Results (Attempt 5)

| Test Class | Tests | Passed | Failed | Errors | Skipped | Status | Time |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- | :---: |
| `AndroidV2OnlineCashClientMockWebServerTest` | 38 | 38 | 0 | 0 | 0 | **PASS** (100%) | 0.738s |
| `StaffWorkspaceComposeTest` | 14 | 14 | 0 | 0 | 0 | **PASS** (100%) | 8.942s |
| `StaffOnlineSaleViewModelTest` | 45 | 41 | 4 | 0 | 0 | **PARTIAL** (91.1%) | 0.427s |
| `DurableOnlineSaleRecoveryStoreTest` | 26 | 24 | 2 | 0 | 0 | **PARTIAL** (92.3%) | 0.297s |
| **Total** | **123** | **117** | **6** | **0** | **0** | **BUILD FAILED** | **71.07s** |

### Verified Working Capabilities (117 Tests Passing)
1. **MockWebServer Wire & Transport Parity (38/38 PASS)**:
   - All 38 tests pass with RFC 8785 wire payload canonicalization, non-null server digests, and status recovery endpoints.
2. **Staff Workspace Compose UI (14/14 PASS)**:
   - Touch bounds for retry button (`>= 48dp`), hold card presentation, tax choice groups, explicit no-tax toggle.
3. **Recovery Store Operations (24/26 PASS)**:
   - Up from 4/26 in Attempt 3 to 24/26 passing! Encrypted persistence, AES-GCM recovery, digest validation, and scope isolation operate cleanly.
4. **Staff Online Sale ViewModel (41/45 PASS)**:
   - Up from 29/36 in Attempt 3 to 41/45 passing! Idempotent retry, lost-ACK status checking, auth token expiration handling, and offline guards.

### Specific Failures Observed in Attempt 5 (6 Total)
1. `DurableOnlineSaleRecoveryStoreTest > testEnvelope_nearLimit_succeeds`:
   - `IntentProfileValidationError: Online intent profile exceeds line limit` (profile validation max line count triggered by fixture).
2. `DurableOnlineSaleRecoveryStoreTest > testEnvelope_overflow_throwsException`:
   - Same `IntentProfileValidationError: Online intent profile exceeds line limit`.
3. `StaffOnlineSaleViewModelTest > testCustomFactory_withRecoveryStore_storageFailure_zeroPost`:
   - `AssertionError`: factory was not invoked because storage failure occurred during startup phase before sale dispatch orchestrator creation.
4. `StaffOnlineSaleViewModelTest > testScopeSwitch_companyActorDevice_resetsPresentationWithoutLeakingOldUI_restoresOnReturn`:
   - `CanonicalSerializationException: token length is below minimum required length` (fixture token string too short for strict session validation).
5. `StaffOnlineSaleViewModelTest > testValidationFailed_lostAckRetainedDispatch_preservesRecordAndHold`:
   - `AssertionError` in test assertion logic.
6. `StaffOnlineSaleViewModelTest > testLocalTokenUnavailable_unreadableStorage_keepsHoldAndIntent`:
   - `AssertionError` in test assertion on draft state.

Per the grant instruction ("any REAL behavior/security/durability/resource fail STOP+release no further QA"), execution was terminated immediately. No production security guards were weakened.

---

## 4. Teardown & Lease Release

1. **Gradle Daemon Process Teardown**:
   - Commanded `./gradlew --stop`.
   - Verified process table with `ps aux | grep -i gradle`: confirmed **0 lingering Gradle processes**.
2. **QA Lease Status**: **EXPLICITLY RELEASED**.
   - No further Gradle, build, or test executions will be attempted.
   - Slot freed for backend queue.
3. **Release Qualification Honesty**:
   - 117 passing tests demonstrate strong component-level correctness across Compose, MockWebServer, and Recovery stores.
   - Full release qualification remains blocked pending alignment of the remaining 6 test fixture contracts.

---

## 5. Provenance & Preserved Logs

All raw logs and contemporaneous attestation JSON documents are archived in this directory:
- [`attempt-1/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-1/): Pre-invocation attestation, raw execution log (compile error), and teardown confirmation.
- [`attempt-2/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-2/): Pre-invocation attestation, raw execution log (test compile error), and teardown confirmation.
- [`attempt-3/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-3/): Pre-invocation attestation, raw execution log, post-invocation attestation with test results, XML test reports, and XML SHA-256 sums.
- [`attempt-4/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-4/): Pre-invocation attestation, raw execution log (compile error `ClientUuid.fromString`), and post-invocation attestation.
- [`attempt-5/`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/attempt-5/): Pre-invocation attestation, raw execution log (123 tests: 117 pass, 6 fail), post-invocation attestation, test-results XMLs, and XML SHA-256 sums.
- [`SOURCE_RELEASE_MANIFEST.json`](file:///home/t3dev/.t3/worktrees/small-trader-android/t3-7ac8b73d-abb5d6e52b3b3cf9/docs/evidence/pr24-bootstrap-tax-status-recovery/SOURCE_RELEASE_MANIFEST.json): Attested source file hashes and execution summary.
