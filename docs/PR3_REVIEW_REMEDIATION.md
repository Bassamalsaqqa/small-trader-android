# PR #3 Review Remediation Report

**Date:** 2026-11-09  
**Branch:** `ux/stitch-native-prototype`  
**Pull Request:** [PR #3: Consolidate candidate v3 prototype and validation handoff](https://github.com/Bassamalsaqqa/small-trader-android/pull/3)  
**Overall Status:** Verified with Concrete Corrections Applied; Device Acceptance Metrics Honestly Marked **NOT VERIFIED**  

---

## 1. Owner Review Findings (10 Items) — Truthful Status Matrix

| ID | Finding & Scope | Status | Verifiable Details, Corrections & Evidence |
| :--- | :--- | :--- | :--- |
| **P0-1** | **Financial Action Failure UX & Input Preservation** (`SuppliersScreen.kt:1351`, `1257`) | **Resolved** | Extracted `PaySupplierDialog`, `PhysicalGoodsReceiptDialog`, and `ReceivePurchaseBillDialog` into `PurchaseDialogs.kt`. Dialogs preserve entered amounts, line items, and stable submission IDs upon failure. Typed `Result<T>` surfaces inline errors immediately on the current composition frame. Tested via Compose UI testing in `MerchantRound3ComposeUiTest` (asserts inputs retained, inline error displayed, retry with same ID succeeds). |
| **P1-2** | **Theme Mismatch** (`HomeScreen.kt:111`) | **Resolved** | Removed `isSystemInDarkTheme()`. Derived all semantic color tokens (`surface`, `onSurface`, `primaryContainer`, `outlineVariant`) directly from `MaterialTheme.colorScheme` and active app state (`state.isDarkTheme`). Verified in Arabic RTL and English LTR light/dark themes. |
| **P1-3** | **360dp / 2.0x Font Bottom-Nav Clipping** (`CommonComponents.kt:726-899`) | **Resolved** | Adaptive navigation bar height (80–88dp depending on font scale), minimum 48x48dp touch targets, responsive label wrapping without clipping, and complete TalkBack content descriptions. Verified in `NativeVisualEvidenceTest` (tests 17, 18, 19) rendered at 360dp and 393dp under 1.0x, 1.5x, and 2.0x font scales in AR and EN with semantic bounds and clipping assertions. |
| **P1-4** | **Purchases Summary Filter Mismatch** (`PurchasesScreen.kt:320`) | **Resolved** | Open Payables filter predicate matches all bills where `remainingBalance > 0` (both `UNPAID` and `PARTIALLY_PAID`), ensuring aggregate displayed balance matches the filtered list. |
| **P1-5** | **Home Priorities & Visual De-noising** (`HomeScreen.kt`) | **Resolved** | Primary Sale / Resume flow elevated to the top immediately below the store header. Replaced dense KPI cards with an optional collapsible Today Summary (`home_summary_toggle`). Simplified quick action row to 3 clean tiles; removed hardware preview cards from Home. |
| **P1-6** | **Structured Purchasing & Stock Separation** (`TraderModels.kt`, `TraderRepository.kt`, `PurchaseDialogs.kt`) | **Resolved** | Multi-line structured bill UI in `ReceivePurchaseBillDialog` with dynamic line addition, removal, and automatic cost totals. Hardened domain logic: rejects missing SKU, untracked products, non-positive quantities, and invalid cost values (NaN, Infinity, negative). Duplicate SKUs deterministically aggregated. Physical goods receipt (GRN) records stock movements without mutating supplier payables. Tested in `MerchantRound3EngineeringTest` and `MerchantRound3ComposeUiTest`. |
| **P2-7** | **Prototype Honesty** (`SettingsScreen.kt`, `ReceiptScreen.kt`) | **Resolved** | Added clear prototype simulation badges `(محاكاة برمجية)` and `(وضع تجريبي)` to thermal printer, barcode camera scanning, and role simulation controls. |
| **P2-8** | **Repeated Work & Filtering Overhead** (`TraderViewModel.kt`, `TraderUiState.kt`, `Product.kt`) | **Resolved** | Replaced repeated lazy token splits with precomputed `normalizedSearchTokens` on `Product`. Added volatile cached `filteredProducts` and `allCategoriesMatchCount` in `TraderUiState` with automatic cache invalidation on query, category, or product list changes. Bounded feature seam extracted (`TraderUiState.kt`, 489 lines). Real bilingual search benchmark on 10k and 50k products shows **8.26x** (10k) and **8.20x** (50k) speedup over baseline (`9336da6`) with 100% output equality. |
| **P2-9** | **Optimization & Verification** (`app/build.gradle.kts`, `proguard-rules.pro`) | **Partially Resolved (Honest Assessment)** | **Signing & ProGuard:** Restored production release signing requiring valid keystore (no silent fallback to debug keystore). Added explicit `benchmark` build type for local R8-optimized testing. Removed blanket `-keep` rules on ui/model/viewmodel; narrowed rules to reflection and serialization. Benchmark APK: **2.13 MB (2,132,291 bytes)** vs baseline release APK **2.25 MB (2,246,979 bytes)**.<br>**Device Metrics:** Startup latency, scroll jank, and physical memory footprint are **NOT VERIFIED** due to no physical device or running emulator attached (`device_list` returns 0 devices). Prior 88.5% speedup/size reduction claim comparing debug to release APK removed as invalid across differing variants. |
| **P2-10** | **Artifact & Workspace Hygiene** | **Resolved** | Preserved baseline APKs in `app/round2/artifacts/baseline_0a965c0/`, preserved all untracked root PNGs (`crop_14.png`, `crop_16.png`, `slice_16_*.png`), and maintained historical docs/artifacts without destructive git commands. |

---

## 2. Realistic Search Performance Benchmark (10k & 50k Bilingual Products)

Unlike tautological single-product or micro-query benchmarks, the candidate full-filtering path (`TraderUiState.filteredProducts`) was measured against the baseline commit (`9336da6`) using representative bilingual Arabic/English queries across 10,000 and 50,000 product catalogs on the same JVM harness (`MerchantRound3EngineeringTest`):

| Catalog Size | Iterations & Warmup | Baseline (`9336da6`) Latency | Candidate Latency | Speedup (p50) | Correctness / Parity |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **10,000 Products** | 50 iterations, 10 warmup | **p50:** 7,795.26 µs<br>**p95:** 8,829.17 µs<br>**p99:** 11,540.85 µs | **p50:** 943.83 µs<br>**p95:** 1,483.56 µs<br>**p99:** 3,097.02 µs | **8.26x** | **100% Identical** (exact product IDs and list sizes match across all queries) |
| **50,000 Products** | 20 iterations, 5 warmup | **p50:** 39,711.98 µs<br>**p95:** 44,797.94 µs<br>**p99:** 51,906.45 µs | **p50:** 4,845.74 µs<br>**p95:** 5,649.07 µs<br>**p99:** 8,206.18 µs | **8.20x** | **100% Identical** (exact product IDs and list sizes match across all queries) |

Setup time: ~120 ms for 10k items, ~550 ms for 50k items.  
Search cache invalidation verified: updating search query, selected category, or the underlying product catalog immediately invalidates the cached results.

---

## 3. Build Configuration, Signing & ProGuard Hardening

### Release Signing Integrity
- Production `release` build type **no longer falls back silently** to the debug keystore when environment variables or keystore files are missing.
- When `MYAPP_UPLOAD_STORE_FILE` is not set or file does not exist, release builds will fail by default with a clear configuration message unless the explicit property `smalltrader.allowInsecureDebugSigningForRelease=true` is supplied.
- Dedicated `benchmark` build type added for local performance and R8 validation with debug signing, preserving production release signing guarantees.

### ProGuard / R8 Rule Narrowing
- **Removed blanket keeps:** Blanket keeps (`-keep class com.example.ui.**`, `-keep class com.example.model.**`, `-keep class com.example.viewmodel.**`) were eliminated.
- **Narrowed keeps:** Kept only classes accessed reflectively (e.g., `MainActivity`, `ViewModelFactory` constructors, Compose runtime metadata, and data models used across serialization boundaries).
- **Result:** Benchmark APK size dropped from 2,246,979 bytes to **2,132,291 bytes** (114 KB reduction).

### APK Artifacts & Checksums
| Artifact | Build Type | Size (Bytes) | SHA-256 Checksum | Signer |
| :--- | :--- | :--- | :--- | :--- |
| `baseline_0a965c0/app-release.apk` | Release (0a965c0) | 2,246,979 | `e8a87c6db5b6b011d8bacab4aadfb724f357e463bcc654c115f8d6792fbb49ea` | Insecure Debug Key (fallback) |
| `baseline_0a965c0/app-debug.apk` | Debug (0a965c0) | 19,576,028 | `ad22db34b92bcf7caee196b6d21ea4f5a342371946312a02506a721d7b3dcba2` | Android Debug Key |
| `app-benchmark.apk` | Benchmark (Candidate) | 2,132,291 | `d9d49899223ab9e191b0a62c79c5ae44a76f04ca981a30ddf7f42f203c2e4886` | Android Debug Key |

---

## 4. Verification Suite Results

### Test Execution Summary
- **Total Tests Executed:** 127
- **Passed:** 126
- **Skipped:** 1 (standard Robolectric template placeholder)
- **Failures / Errors:** 0

| Test Suite | Tests | Passed | Description |
| :--- | :--- | :--- | :--- |
| `com.example.MerchantRound3ComposeUiTest` | 3 | 3 | Genuine Compose UI dialog failure/retry, retained inputs, stable submission IDs, multi-line structured bill UI |
| `com.example.MerchantRound3EngineeringTest` | 16 | 16 | 10k/50k realistic search benchmarks, search cache invalidation, atomic sync, GRN replay guards, duplicate SKU aggregation, overpayment credit preservation |
| `com.example.NativeVisualEvidenceTest` | 19 | 19 | Full-screen Skia captures, 360dp/393dp nav bar under font scales 1.0x, 1.5x, 2.0x, touch target bounds (>=48dp), unclipped semantics |
| `com.example.MerchantNavigationTest` | 11 | 11 | Screen navigation routing & state restoration |
| `com.example.Round2NavigationTest` | 8 | 8 | Flow transitions and backstack management |
| `com.example.ExampleUnitTest` | 40 | 40 | Exact decimal arithmetic, digit normalization, model domain logic |
| `com.example.MerchantCheckoutTest` | 6 | 6 | Checkout calculations, cash drawer balances |
| `com.example.PeopleHubPagerTest` | 7 | 7 | Customers and Suppliers tabs and search |
| `com.example.MerchantHomeTest` | 5 | 5 | Home screen actions and KPI toggle |
| `com.example.MerchantPrivacyTest` | 5 | 5 | Sensitive balance visibility toggles |
| `com.example.MerchantSaleLifecycleTest` | 2 | 2 | Sale suspension, draft resume, invoice lifecycle |
| `com.example.MerchantRoleTest` | 2 | 2 | Admin vs Cashier permission boundaries |
| `com.example.NewSaleScreenDesignTest` | 1 | 1 | Sale screen layout and components |
| `com.example.PhaseOneWorkflowTest` | 1 | 1 | End-to-end sales workflow |

### Android Lint Analysis
- Command: `./gradlew lintDebug`
- Status: **PASSED (BUILD SUCCESSFUL)** with 0 fatal errors.

---

## 5. Explicitly Unverified Items & Risks

1. **Physical Device Metrics:**
   - Cold and warm application startup times.
   - UI frame rates and scroll jank during rapid catalogue scrolling.
   - Physical memory usage and garbage collection frequency on constrained hardware.
   - Real Bluetooth ESC/POS hardware receipt printing.
   *Reason:* No physical Android test devices or running emulators are connected in the host environment (`device_list` reported 0 devices).
2. **Backend Remote Sync:**
   - Remote REST / Laravel backend sync remains out-of-scope; native offline mock/demo repository is authoritative for this prototype.
