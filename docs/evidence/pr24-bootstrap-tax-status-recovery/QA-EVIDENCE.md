# PR #24 Focused Android Compile & QA Evidence Report

**Branch**: `t3/android-bootstrap-tax-status-recovery`  
**Base Commit**: `e6b178c8056236f62078e699a6901f58fc449284`  
**Target Environment**: Isolated Android Worktree `t3-7ac8b73d-abb5d6e52b3b3cf9`  
**Toolchain**:
- JDK: OpenJDK 21.0.13+9 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`)
- Android SDK: API 35 (`/home/t3dev/Android/Sdk`)
- Gradle Flags: `-Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseSerialGC -XX:MaxMetaspaceSize=512m" -Dorg.gradle.workers.max=2 -Pkotlin.compiler.execution.strategy=in-process --no-daemon --no-build-cache`

---

## 1. Focused Unit Test Execution Summary

Command:
```bash
./gradlew testDebugUnitTest --no-daemon --no-build-cache \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseSerialGC -XX:MaxMetaspaceSize=512m" \
  -Dorg.gradle.workers.max=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*ReadClientRevision2BoundariesTest" \
  --tests "*SmallTraderReadClientWorkflowTest" \
  --tests "*StaffSessionCoordinatorTest" \
  --tests "*SecureStaffSessionStoreTest" \
  --tests "*StaffOnlineSaleDraftTest" \
  --tests "*StaffOnlineSaleViewModelTest" \
  --tests "*StaffWorkspaceComposeTest" \
  --tests "*StaffWorkspaceViewModelTest" \
  --tests "*AppLaunchTargetResolverTest"
```
**Exit Code**: 0  
**Outcome**: `BUILD SUCCESSFUL`

### Test Counts by Suite

| Test Class | Tests | Failures | Errors | Skipped | Status |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `ReadClientRevision2BoundariesTest` | 15 | 0 | 0 | 0 | **PASS** |
| `SmallTraderReadClientWorkflowTest` | 22 | 0 | 0 | 0 | **PASS** |
| `SecureStaffSessionStoreTest` | 37 | 0 | 0 | 0 | **PASS** |
| `StaffSessionCoordinatorTest` | 53 | 0 | 0 | 0 | **PASS** |
| `AppLaunchTargetResolverTest` | 14 | 0 | 0 | 0 | **PASS** |
| `StaffOnlineSaleDraftTest` | 14 | 0 | 0 | 0 | **PASS** |
| `StaffOnlineSaleViewModelTest` | 18 | 0 | 0 | 0 | **PASS** |
| `StaffWorkspaceComposeTest` | 14 | 0 | 0 | 0 | **PASS** |
| `StaffWorkspaceViewModelTest` | 24 | 0 | 0 | 0 | **PASS** |
| **Total** | **211** | **0** | **0** | **0** | **100% PASS** |

---

## 2. Android Lint Execution Summary

Command:
```bash
./gradlew lintDebug --no-daemon --no-build-cache \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:+UseSerialGC -XX:MaxMetaspaceSize=512m" \
  -Dorg.gradle.workers.max=2 \
  -Pkotlin.compiler.execution.strategy=in-process
```
**Exit Code**: 0  
**Outcome**: `BUILD SUCCESSFUL`  
**Report**: 0 errors, 63 warnings (all existing baseline / deprecation warnings).

---

## 3. Root Cause Analysis & Defect Resolutions

1. **Initial Compilation Errors (preserved in `first-compile-failure.log`)**:
   - `MainActivity.kt`: Unresolved `MerchantAction` references in preview/action handlers resolved.
   - `OnlineCashSaleOrchestrator.kt`: Fixed parameter name mismatch in `ReadClientErrorResponse` instantiation.
   - `SmallTraderReadClient.kt`: Fixed unresolved `fromStrictJson` call.
   - `StaffWorkspaceViewModel.kt`: Corrected invalid backing field reference in custom setter/property.

2. **Status Recovery Digest Matching (`OnlineCashSaleOrchestrator.kt`)**:
   - **Defect**: On status GET recovery query (`/api/mobile/v2/companies/{companyId}/offline-events/{clientEventId}/status`), the Revision 3 wire response schema permits `payload_digest: null`. The orchestrator was checking `if (status.payloadDigest == null || status.payloadDigest != intent.expectedNormativeProfileDigest)`, causing a `StatusDigestMismatch` when `payload_digest` was null on status response.
   - **Resolution**: Updated validation to require exact match if `status.payloadDigest` is present, and gracefully fall back to `intent.expectedNormativeProfileDigest` in receipt construction when `null`.

3. **Robolectric Viewport Resolution (`StaffWorkspaceComposeTest.kt`)**:
   - **Defect**: By default Robolectric uses an unconstrained or small viewport (320x470dp), causing the full `StaffWorkspaceScreen` layout with Header, Outbox banner, TabRow, and Estimate card to clip the action buttons and line tax chips below the window bounds. This caused `assertIsDisplayed()` to fail and prevented `performClick()` center-hit dispatching.
   - **Resolution**: Added `@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")`, matching the project's standard modern phone test configuration (`NativeVisualEvidenceTest.kt`), enabling all 14 Compose tests to pass with full UI interaction.

4. **MinSdk 24 UTC Date-Time Compatibility (`StaffWorkspaceViewModel.kt`)**:
   - **Defect**: `deniedAt = java.time.Instant.now().toString()` failed `lintDebug` with `[NewApi]` since `java.time.Instant` requires API 26 (project minSdk is 24).
   - **Resolution**: Replaced with thread-safe `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)` with UTC timezone, consistent with the rest of the application codebase.

---

## 4. XML Test Result SHA256 Fingerprints

```
ce068d8b3ca3d7aa4e2d9f37d658389c18891ed3b3964f0b46d6ba7f686dc675  test-results/TEST-com.example.data.mobile.readclient.ReadClientRevision2BoundariesTest.xml
7414413c0f7c167cc14e7197cad4de36c86a3bd5ed0a940077f512d10d036e12  test-results/TEST-com.example.data.mobile.readclient.SmallTraderReadClientWorkflowTest.xml
3bd07e36c34bdc75c126d1b2d2608d1e098842dad79bb9605102fa053811d364  test-results/TEST-com.example.data.mobile.session.SecureStaffSessionStoreTest.xml
e1a080be3d0790e38d2b690d5bf86a07ca0c804018f1e00f08eb4e8f547f8086  test-results/TEST-com.example.data.mobile.session.StaffSessionCoordinatorTest.xml
c828db725cee531ae55f6249781932a35fed8ba04afebb795bdd60dacb2873f0  test-results/TEST-com.example.ui.navigation.AppLaunchTargetResolverTest.xml
4805738c24e583861d9dce9a1c09a89cbbf965112aef8b660fdcb04f727a521c  test-results/TEST-com.example.ui.staff.sale.StaffOnlineSaleDraftTest.xml
fcc3d44bdac817c5578701aae10d7fa72e25ac775ab98aa51480f68a29ca939c  test-results/TEST-com.example.ui.staff.sale.StaffOnlineSaleViewModelTest.xml
3d7a0a0184995d98b783b798104faca00a377a0f55b4cceb6e367c6be5baa7ae  test-results/TEST-com.example.ui.staff.StaffWorkspaceComposeTest.xml
7cd8d2c8825b5ff90b9ec57b9ed2329a79e60525f2735f1f9f5bf779118bf4c3  test-results/TEST-com.example.ui.staff.StaffWorkspaceViewModelTest.xml
```
