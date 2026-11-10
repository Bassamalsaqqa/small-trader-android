# NATIVE ANDROID MERCHANT UX RESET — QA & VALIDATION REPORT

**Document version:** 1.0 — 2026-11-07  
**Execution Environment:** HP-worker (Linux x86_64, headless, no emulator/Pixel device)  
**Target Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Branch:** `ux/merchant-experience-reset`  
**Baseline Commit:** `f8629644617d147b1d0920634b3eaed4e410cfb5`  
**Status:** Automated checks passed; owner Android visual acceptance PENDING.

---

## 1. Toolchain & Execution Commands

### Toolchain Configuration
- **JDK:** Eclipse Adoptium OpenJDK 21.0.13+9 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`)
- **Android SDK:** `/home/t3dev/.local/share/android-toolchain/sdk`
- **Gradle Version:** Gradle 9.3.1; app Kotlin Compose plugin 2.2.10 (Gradle embedded Kotlin is a separate version)
- **JVM Owner Flags:** `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`
- **Gradle Execution Flags:** `--max-workers=2 -Dorg.gradle.jvmargs='-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'`

### Executed Tasks & Exit Codes

| Step | Command | Exit Code | Duration | Outcome |
|---|---|:---:|:---:|---|
| 1 | `./gradlew :app:assembleDebug` | 0 | 1m 03s | BUILD SUCCESSFUL — Initial Debug APK assembled |
| 2 | `./gradlew :app:testDebugUnitTest` | 0 | 1m 03s | BUILD SUCCESSFUL — 71 tests (70 passed, 0 failed, 1 skipped) |
| 3 | `./gradlew :app:lintDebug` | 0 | 1m 38s | BUILD SUCCESSFUL — 0 errors, 62 warnings (baseline preserved) |
| 4 | `./gradlew :app:assembleDebug` (final) | 0 | 6s | BUILD SUCCESSFUL — Final verified installable debug APK |

---

## 2. Test Suite Execution & XML Breakdown

All unit and Robolectric Compose semantics tests completed with zero errors and zero failures:

| Test Class | Total | Passed | Failed | Skipped | Status |
|---|:---:|:---:|:---:|:---:|:---:|
| `com.example.ExampleRobolectricTest` | 1 | 0 | 0 | 1 | SKIPPED (existing placeholder `@Ignore`) |
| `com.example.ExampleUnitTest` | 39 | 39 | 0 | 0 | PASSED |
| `com.example.MerchantCheckoutTest` | 6 | 6 | 0 | 0 | PASSED |
| `com.example.MerchantHomeTest` | 5 | 5 | 0 | 0 | PASSED |
| `com.example.MerchantNavigationTest` | 11 | 11 | 0 | 0 | PASSED |
| `com.example.MerchantPrivacyTest` | 5 | 5 | 0 | 0 | PASSED |
| `com.example.MerchantRoleTest` | 2 | 2 | 0 | 0 | PASSED |
| `com.example.NewSaleScreenDesignTest` | 1 | 1 | 0 | 0 | PASSED |
| `com.example.PhaseOneWorkflowTest` | 1 | 1 | 0 | 0 | PASSED |
| **TOTAL** | **71** | **70** | **0** | **1** | **ALL PASSED** |

### Verified Invariants in Mandatory Suites:
- **`MerchantHomeTest`:** Verified empty basket hero action, active basket resume button, zero repo stock mutations on stock summary row navigation, owner vs cashier role gating, and absence of fake receive-stock / persistent demo banners.
- **`MerchantNavigationTest`:** Verified quick add cancellation origin retention, roots reselect & draft preservation, purchase entry dialog Back dismissal, collect chooser safe start without posting, workspace search/category isolation, origin preservation on money chooser cancellation, low-stock filter reset without sale/stock mutation, customer directory quick collection feedback, and new regression test `openMerchantActionConsumesPreviousPendingAndRoutesProductAndSupplierFromHome`.
- **`MerchantRoleTest`:** Cashier direct restricted destination/action denial, role-switch restricted destination/action eviction.
- **`MerchantPrivacyTest`:** Store debt & advance totals hidden on live cashier downgrade while individual customer rows remain accessible; product create/edit dialog evicted on cashier downgrade without stale reopen on owner restoration; explicit 0.0 cost allowed without 75% fabrication.
- **`MerchantCheckoutTest`:** Exact cash tender fills input without drawer mutation; credit sale blocks over-limit and unassigned customer; quotation preview generates non-posting text; progressive disclosure expands customer & discount sections; compact receipt exposes thermal paper toggle.

---

## 3. Lint Results Breakdown

Executed `:app:lintDebug` with `--max-workers=2`. Result: **0 errors, 62 warnings** (matching prior known baseline report, 0 new warnings).

| Issue ID | Count | Severity | Category | Description |
|---|:---:|:---:|---|---|
| `GradleDependency` | 22 | Warning | Correctness | Obsolete dependency versions available in repositories |
| `UnusedResources` | 21 | Warning | Performance | Unused drawables, strings, and theme assets from legacy baseline |
| `NewerVersionAvailable` | 9 | Warning | Correctness | Newer library patch versions available |
| `IconLauncherShape` | 5 | Warning | Usability:Icons | Launcher icon adaptive shape warnings |
| `AndroidGradlePluginVersion` | 2 | Warning | Correctness | AGP version upgrade advisory |
| `UnusedAttribute` | 1 | Warning | Correctness | Attribute unused on API level |
| `RedundantLabel` | 1 | Warning | Correctness | Redundant activity label in manifest |
| `VectorRaster` | 1 | Warning | Performance | Vector rasterization warning |
| **Total Issues** | **62** | **Warning** | — | **0 Errors, 62 Warnings** |

Full lint report saved at `evidence/lint-results-debug.xml`.

---

## 4. Fixes Applied During QA Ownership

In strict compliance with QA governance (exclusive ownership during QA to fix compile errors and demonstrably incorrect UI wiring without touching financial logic or weakening assertions):

1. **`CommonComponents.kt` NavigationBar Extension Functions:**
   - *Problem:* Unresolved reference `NavigationBarItem` and `@Composable` invocations can only happen from context of `@Composable` function. In Material 3, `NavigationBarItem` is a `RowScope` extension function and cannot be called with package prefix `androidx.compose.material3.NavigationBarItem(...)`.
   - *Fix:* Added `import androidx.compose.material3.NavigationBar` and `import androidx.compose.material3.NavigationBarItem`. Replaced package-qualified calls with standard composables inside `NavigationBar` scope.

2. **`CashPaymentScreen.kt` Nullable Projected Balance:**
   - *Problem:* `formatIls(state.projectedCustomerBalanceIfCreditIls)` failed compilation with argument type mismatch (`Double?` provided, `Double` expected).
   - *Fix:* Applied safe Elvis operator fallback: `formatIls(state.projectedCustomerBalanceIfCreditIls ?: 0.0)`.

3. **`HomeScreen.kt` / `ReceiptScreen.kt` Recent Invoice Component Compatibility:**
   - *Problem:* `ReceiptScreen.kt:1589` (in `InvoicesHistoryScreen`) referenced `RecentInvoiceCard`, which was refactored into private `RecentInvoiceRow` in `HomeScreen.kt`.
   - *Fix:* Made `RecentInvoiceRow` public and added a clean `RecentInvoiceCard` delegation wrapper in `HomeScreen.kt`, ensuring full binary and signature compatibility without modifying legacy history logic.

4. **Test Compilation & Method Mappings:**
   - *Problem:* `MerchantHomeTest.kt` and `MerchantPrivacyTest.kt` imported non-existent `androidx.compose.ui.test.assertDoesNotExist` (it is a member of `SemanticsNodeInteraction`). `MerchantHomeTest.kt` called `repo.observeProducts().value` which does not exist on `OfflineDemoTraderRepository`. `MerchantCheckoutTest.kt` referenced non-existent method names `resumeDraft`, `deleteDraft`, `formatCurrentQuotation`, and `simulatePrintReceipt`.
   - *Fix:* Removed invalid static imports; mapped repository access to `repo.getProducts()`; mapped ViewModel methods to `resumeSaleDraft`, `deleteSaleDraft`, `formatQuotationShareText`, and `simulateThermalPrint`.

5. **Robolectric Offscreen `LazyColumn` / Scroll Handling in Tests:**
   - *Problem:* Offscreen Compose elements in `LazyColumn` are not composed until scrolled to. In `MerchantCheckoutTest.kt`, `open_quotation_preview_button`, `payment_credit_limit_warning`, `customer_note_input`, and `discount_chip_PERCENT_5` failed `assertIsDisplayed()` because they were offscreen. In `MerchantPrivacyTest.kt`, `customer_row_cust_05` was offscreen.
   - *Fix:* Applied `.performScrollTo()` on `verticalScroll` column in `CashPaymentScreen`. Added `testTag("customers_directory_list")` on `CustomersScreen.kt` `LazyColumn`. Applied `.performScrollToNode(hasTestTag(...))` on the respective scrollable containers before asserting visibility.

6. **MainActivity Party Chooser for Zero Parties:**
   - *Problem:* When `state.customers` or `state.suppliers` was empty, the account chooser modal rendered completely blank with no feedback or recovery path.
   - *Fix:* Added concise empty labels (`"لا يوجد زبائن مسجلون"`, `"لا يوجد موردون مسجلون"`) and direct create recovery text buttons (with 48dp minimum touch target: `Modifier.heightIn(min = 48.dp)`) gated by `MerchantAction.CUSTOMER.allowed` and `canManageSuppliers`. Preserved cancellation origin via `traderViewModel::consumeAction`.

7. **`openMerchantAction` Pending Action Consumption:**
   - *Problem:* Reselecting an action or invoking a new action while on the same screen could leave previous pending actions dangling because `navigateTo` on the current screen is a no-op.
   - *Fix:* Ensured `openMerchantAction` clears `pendingAction = null` and closes quick action sheet at entry after permission verification, prior to dispatching new action. Added regression test `openMerchantActionConsumesPreviousPendingAndRoutesProductAndSupplierFromHome` to `MerchantNavigationTest.kt`.

8. **Review Finding P1 False:**
   - Verified that the independent review claim of lost PRODUCT/SUPPLIER routing was FALSE: `openMerchantAction` properly navigated to `PRODUCTS_CATALOG` and `SUPPLIERS` then set pending. Verified with new automated regression test.

---

## 5. Artifacts, Signatures & Diff Hashes

### Installable APK Artifact
- **File:** `app/build/outputs/apk/debug/app-debug.apk`
- **File Size:** `18,958,811 bytes` (~18.08 MB)
- **SHA-256:** `43afadfb4f52be5abdaa535435d2b597555133924d75b9751dc69bbc60686b3c`

### Source Difference Hash
- **`git diff` Byte Count:** `378,882 bytes`
- **`git diff` SHA-256:** `7b09e2d01f2b33b4d265c161ee91a1ee4e21a64029f1316ae9292eb6129374fe`

### Untracked Source Files (SHA-256 Hashes)

```text
b325775b1bd51f3ea9785eb9648f149214eb169a10658c1ecff41e7e5e6cb619  app/src/main/java/com/example/ui/navigation/TraderNavigationPolicy.kt
7a7cc258e6b2d133d161d7058274c0d50655ad4d9e125149c8c1a09d45829dc9  app/src/main/java/com/example/ui/screens/PeopleHubScreen.kt
bd0d98c5ac3353e37a31dafc48a047c396765cc9a81b84a78ac1251ede186127  app/src/main/java/com/example/ui/screens/PurchasesScreen.kt
b21eef45ac01a075543308de419000ee857a339e55becb144b16e4a405fff910  app/src/main/java/com/example/ui/shell/QuickActionSheet.kt
34e15546135358b739ec4be0bcf6ffee2ca91a50274285b5d6636d79f603ab3e  app/src/test/java/com/example/MerchantCheckoutTest.kt
7574134a20a93e57eeff14199f22dd294666180fa34f9bc12b7e7df3d7bb8547  app/src/test/java/com/example/MerchantHomeTest.kt
0cbcbcc850ff08f5cdbcf1f606ff85e206c2a7eb028e50486932f2a87963356b  app/src/test/java/com/example/MerchantNavigationTest.kt
b538522ed2943a9866a98486ae1d46dcd0971cd07081207bb5d6d7eb751681fe  app/src/test/java/com/example/MerchantPrivacyTest.kt
cc3d1c46c7561a4ed816b0d2d882f71d096b9276a3a357dfe69b62cbc20b8730  app/src/test/java/com/example/MerchantRoleTest.kt
3755fedb96d7f7daf49f36b68fb7a99403902ee6e9b858ec1a35a26dcd8281e5  docs/android-merchant-ux-reset/BASELINE.md
8b9cab9424810e5baa7f5b426c3f53eb2cf132573af353924ff4f1ae273c4d01  docs/android-merchant-ux-reset/HOME_IMPLEMENTATION.md
2487dbc91f4b2a493df7d661d03fea100f9d67c708e6d2c8ccf3e19f0e851f5e  docs/android-merchant-ux-reset/IMPLEMENTATION_PLAN.md
f37cca05d4095588e8ea9127e3cc5ce7139cc61d50ae70d1662dc9ac7dd86fca  docs/android-merchant-ux-reset/INDEPENDENT_SHELL_REVIEW.md
e2dd1842fdbecd253485cd041f00aa84583ec0a33a9289363bb2ec0f735a3317  docs/android-merchant-ux-reset/MASTER_BRIEF_V2.md
97fbfca2e991415197bafd0ebea25c02592931f5748df6959f5f0a25dc593a10  docs/android-merchant-ux-reset/PATTERN_RESEARCH_RAW.md
e25f7fdb46b526b2682d23dbc5944acf6fa1f3828deda45aff6d3ca1f68c55db  docs/android-merchant-ux-reset/PRIVACY_TEST_HANDOFF.md
f46cd9df2c477e12f0b6ceedb57fd6c6be9aa4c72f913382b741a78962d9f011  docs/android-merchant-ux-reset/RESEARCH_RECONCILIATION.md
9ca97adcb85f5f922a78682f74a1eb8166a88594d858f6b4d72b6d11a45767f7  docs/android-merchant-ux-reset/SELL_IMPLEMENTATION.md
ce7bebd525ee55da2ee047b356c2d8f800cdb0fa5cc956e1b8f9d62dac9182d6  docs/android-merchant-ux-reset/SHELL_IMPLEMENTATION.md
acc388646249bce2b89c143d4822d6432c8155566a05084034911ab63e4691a7  docs/android-merchant-ux-reset/SOURCE_AUDIT_RAW.md
df121b35569465467e20075457e2dca066ef7c6d73585464270cf430c0949d7b  docs/android-merchant-ux-reset/WINDOWS_ACCEPTANCE.md
6ed1ed255970c3f4d62f95e9a981fc7b9d3cb6e323e0f6113fdc0272002ed7fb  docs/android-merchant-ux-reset/concepts.html
da97a3b76bc3db0f29bb8f3a89a582943c35572741556f7712d459125e79b6c2  evidence/lint-results-debug.xml
a4996ec4aea52477681123be1f4a748734a5f6343010e3067e296d94e52c0929  evidence/testDebugUnitTest/TEST-com.example.ExampleRobolectricTest.xml
cddb0f034938adbcbf1d4641530d98774d640007b2f763addbf61b71bf95a467  evidence/testDebugUnitTest/TEST-com.example.ExampleUnitTest.xml
0b93339a349d5131ad8269b72d62667d2746deacac33d0c670cbec7b307e990b  evidence/testDebugUnitTest/TEST-com.example.MerchantCheckoutTest.xml
ac577318beda209a6e95f52378cde1d58de1be9b80f4503e6ee713d066dea9c1  evidence/testDebugUnitTest/TEST-com.example.MerchantHomeTest.xml
63d306f034b0f3350777bedb55a9ac46e3b20576fc0772b1a67a8e9feeecc56d  evidence/testDebugUnitTest/TEST-com.example.MerchantNavigationTest.xml
f937bf8e1c5bff1fbc402fdec5b3edee7f90162482f2c97c17764ec02a81ed5d  evidence/testDebugUnitTest/TEST-com.example.MerchantPrivacyTest.xml
9044fb44b3e9f429b30cf891c95830e6a3d5d6584de1ed14502cc2c1b02c2993  evidence/testDebugUnitTest/TEST-com.example.MerchantRoleTest.xml
60dc714a015cf47dafc10f17f2659bacf8001e1ed9ed07aa0f56fcb65a84b4cb  evidence/testDebugUnitTest/TEST-com.example.NewSaleScreenDesignTest.xml
9c9f62825fb6d442f9810649f3121c1b4dae415fdf39a65f06641e81f2552c24  evidence/testDebugUnitTest/TEST-com.example.PhaseOneWorkflowTest.xml
```

---

## 6. Honest Limitations & Acceptance Boundaries

1. **No Physical Device / Pixel Emulator on HP-Worker:**
   - Testing was conducted in a headless Linux environment using Robolectric 4.16.1 with Android SDK 35 and Compose semantics testing.
   - Physical Pixel touch responsiveness, real hardware camera barcode scanning, Bluetooth thermal printing, and native IME window insets were not visually tested on device.
   - The owner has access to a Windows `Pixel_8a_T3` emulator where `app-debug.apk` should be installed and visually evaluated before any production merge.

2. **No Persistent Backend Storage:**
   - All models, transactions, receipts, and products operate strictly in memory using demo repository structures. Data resets on process death.

3. **In-Memory Tenancy & Permission Simulation:**
   - Role permissions (`OWNER`, `MANAGER`, `CASHIER`) are enforced in Compose UI navigation and dialog presentation. They represent client-side demo gates, not cryptographic server-side authorization.
