# Stitch UI/UX Review Plan & Technical Design Specification (PR #3)

**Document Authority:** Design Sub-Agent Specification for PR #3 (Candidate v3 Review Revision)  
**Pull Request:** [PR #3: Consolidate candidate v3 prototype and validation handoff](https://github.com/Bassamalsaqqa/small-trader-android/pull/3)  
**Branch & Target:** `ux/stitch-native-prototype` -> `main` (commit `9336da67784b15bee12346b9c3c255943c9d2ccc`)  
**Design Authority:** Google Stitch Project `15641450350510420845` | Design System `assets/51afd92dffb8461ab336e6d3d380e71b`  
**Parent Brand Reference:** `Bassamalsaqqa/account` (`icon-maskable.svg` storefront vector mark & `#255FD6` Royal Blue)  
**Integration Status:** Uncommitted review design plan prepared exclusively for Integrator execution  
**Date:** 2026-11-09  

---

## 1. Executive Summary & Review Context

On 2026-11-09, the repository owner (Bassamalsaqqa) completed an independent engineering review of Candidate v3 (`9336da6`). While recognizing significant progress in native Jetpack Compose architecture, parent-brand visual alignment, in-memory sale preservation, role simulation checks, Arabic RTL layout, and automated unit test baseline (104 pass / 1 skipped), the owner determined that **revisions are required before merge**.

This document outlines the authoritative design system decisions, Stitch-generated screen blueprints, exact source seams for implementation, and a comprehensive acceptance matrix to resolve all 10 review findings without regressions.

### Invariant & Boundary Commitments
1. **Native Jetpack Compose Architecture:** Strict Material 3 component adherence; no external UI framework rewrites or unnecessary DI/multi-module dependencies.
2. **Authentic Parent Branding:** Storefront vector mark and `#255FD6` Royal Blue container palette derived directly from `Bassamalsaqqa/account`.
3. **Bilingual RTL/LTR & Theme:** First-class Arabic (RTL) and English (LTR) typography using bundled `IBM Plex Sans Arabic`; active MaterialTheme derivation supporting manual `state.isDarkTheme` without system theme mismatches.
4. **Accounting & Business Invariants:** Exact-decimal math (`BigDecimal`), strict separation of financial supplier payables from physical stock receiving (GRN), cash/credit distinction, advance balance preservation, role-based capability simulation, draft cart persistence, and deterministic replay invariants.
5. **Simulated Hardware Truth:** Clear prototype honesty for barcode camera scanning, Bluetooth thermal printing, and operator role switching.

---

## 2. Review Findings & Concrete Design Resolutions

| Priority | Scope / File | Review Finding | Design Resolution & Implementation Seam |
| :--- | :--- | :--- | :--- |
| **[P0]** | `SuppliersScreen.kt:1351`<br>`SuppliersScreen.kt:1257` | **Financial Action Failure UX & Input Preservation:**<br>Confirming supplier payment unconditionally invokes callback and closes dialog, discarding user input even if the domain action fails. Supplier goods-receipt confirm suffers identical issue. | **Domain-Aware Feedback Dialogs:**<br>Refactor `PaySupplierDialog` and `ReceiveGoodsDialog` to observe action status (`Idle`, `Submitting`, `Success`, `Error`). Dialog remains open on failure, preserving entered amounts, displaying inline error banners, disabling buttons against double-submission (`isSubmitting`), and closing only upon confirmed success. Add unit tests in `MerchantWorkflowTest`. |
| **[P1]** | `HomeScreen.kt:111` | **Theme Mismatch:**<br>`HomeScreen.kt:111` queries `isSystemInDarkTheme()`, conflicting when merchant sets manual `state.isDarkTheme`. | **Active MaterialTheme Derivation:**<br>Eliminate `isSystemInDarkTheme()`. Derive all semantic tokens (`surface`, `onSurface`, `primary`, `outlineVariant`) directly from `MaterialTheme.colorScheme` and active app state. Add Compose theme regression test. |
| **[P1]** | `CommonComponents.kt:726-899` | **Admitted 360dp / 2.0x Font Bottom-Nav Clipping:**<br>Frozen `SmallTraderBottomBar` clips Arabic and English labels under 360dp narrow viewport at 2.0x accessibility font scaling. | **Accessible Adaptive Bottom Navigation:**<br>Unfreeze `SmallTraderBottomBar`. Introduce adaptive height container (80-88dp), 48x48dp minimum touch targets, icon-first priority layout with responsive auto-wrapping typography, and full TalkBack `contentDescription` attributes in Arabic and English. |
| **[P1]** | `PurchasesScreen.kt:320` | **Purchases Summary Filter Mismatch:**<br>The clickable 'Open Payables' metric sums all outstanding balances, but clicking it applies `PurchaseFilter.UNPAID`, hiding partially-paid bills with remaining balances. | **Semantic Filter Alignment:**<br>Adjust filter logic or introduce `HAS_BALANCE` predicate: filter all bills where `remainingBalance > 0` (covering both `UNPAID` and `PARTIALLY_PAID`), ensuring the filtered list matches the displayed aggregate sum. |
| **[P1]** | `HomeScreen.kt` | **Home Priorities & Visual De-noising:**<br>Selling is buried under dense 200-line KPI dashboard. Too much duplicate card, badge, and subtitle noise across Home, Quick Add, Inventory, and Purchases. | **Primary Sale First + Collapsible Summary:**<br>Elevate `بيع جديد / متابعة البيع` to top primary position immediately below store header. Replace dense nested KPI cards with a calm, single collapsible `ملخص اليوم` (Today Summary) with shift balance and link to Reports. Streamline quick action row to 3 clean tiles without explanatory subtitles. |
| **[P1]** | `SuppliersScreen.kt`<br>`TraderViewModel.kt` | **Structured Purchasing & Stock Separation:**<br>`ReceivePurchaseBillDialog` only accepts free text + total + paid amount, creating a scope gap that cannot generate itemized stock receipts. | **Structured Purchase Bill + Partial GRN Workflow:**<br>Split into two formal documents:<br>1) *Financial Purchase Bill:* Supplier, Reference, itemized line items (SKU, Qty, explicit Unit Cost), Subtotal, Cash Paid, and Supplier Payable.<br>2) *Goods Receiving Note (GRN):* Linked to Purchase Bill; displays itemized Expected vs Received quantities, supports Partial Receiving, updates physical stock by verified received units, and maintains supplier payable intact. |
| **[P2]** | `SettingsScreen.kt`<br>`ReceiptScreen.kt` | **Prototype Honesty:**<br>Simulated printer, barcode scanner, and operator roles must be clearly identified as simulations. | **Explicit Simulation Badges:**<br>Display `(محاكاة برمجية)` and `(وضع تجريبي)` tags. Centralize printer configuration in Settings; remove duplicate configuration sheet triggers elsewhere. |
| **[P2]** | `TraderViewModel.kt`<br>`TraderUiState.kt` | **Repeated Work & Filtering Overhead:**<br>`filteredProducts` repeatedly normalizes strings and re-filters on every read; `syncFromRepository()` copies full collections on narrow mutations. | **Cached Search & Narrow State Updates:**<br>Pre-compute and cache normalized search tokens on products. Emit fine-grained state updates for targeted mutations rather than full-collection clones. |
| **[P2]** | `app/build.gradle.kts` | **Optimization & Verification:**<br>Release build has `isMinifyEnabled = false`. Benchmarks and speedups claimed without physical/AVD proof. | **Safe R8 Shrinking & Transparent Acceptance Matrix:**<br>Enable R8 shrinking with verified ProGuard keep rules for serialization/data models. Maintain honest verification matrix distinguishing verified local tests from pending owner device acceptance. |

---

## 3. Concrete Google Stitch Screen Specifications

All visual proposals were generated inside the authentic project workspace using the locked design tokens.

- **Stitch Project:** `projects/15641450350510420845`
- **Design System Asset:** `assets/51afd92dffb8461ab336e6d3d380e71b`
- **Color Palette:** Primary `#255FD6`, Canvas `#F7F9FC`, Surface `#FFFFFF`, Border `#E4E9F1`, Text `#172033`, Muted `#526174`, Success `#14804A`, Danger `#B42318`, Warning `#A15C00`.
- **Typography:** `IBM Plex Sans Arabic` (Bold, SemiBold, Medium, Regular).
- **Physical Output Directory:** `/home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/`

### Screen 1: Simplified Home Screen (`R3-HOME-SIMPLIFIED-v01`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/c7da680fc61c4a02847f4648e5f20573`
- **Dimensions:** 780x1918 px (Mobile 393dp @ 2x)
- **HTML Artifact:** `round3-review/R3-HOME-SIMPLIFIED-v01.html` (SHA-256: `591a881561dbf1a8342449f132be9398182d1cf50114520d367696ab59ba96a7`)
- **PNG Artifact:** `round3-review/R3-HOME-SIMPLIFIED-v01.png` (SHA-256: `72f819175be6b746851c0476f8a8bc7eb3b23919035beb17e017ecf9c5e7d18f`)
- **Design Decisions for Kotlin Compose:**
  1. *Top App Bar:* Single card containing storefront icon in `#255FD6` container, store name "التاجر الصغير", operator role badge "المالك (وضع تجريبي)" with prototype tag, and clean AR/EN language switch.
  2. *Top Operational Action (Sale First):* Positioned at index 1 directly below the store header. Displays active basket count ("السلة الحالية: 3 أصناف — 85.00 ₪") with primary 52dp button "متابعة السلة والبيع" (#255FD6) and secondary outline action "بدء بيع جديد".
  3. *Restrained Summary Card:* Replaces 200 lines of nested charts with a single clean card showing "مبيعات اليوم: 1,450.00 ₪" and "نقد الصندوق: 820.00 ₪", an expand/collapse toggle for shift breakdown, and a direct button "عرض التقارير الكاملة".
  4. *Tactile Action Shortcuts:* 3 equal tiles (48dp+) for "جرد المخزون", "فاتورة شراء", and "تسجيل مصروف", completely removing verbose subtitles and redundant badges.

### Screen 2: Accessible Bottom Navigation Spec (`R3-BOTTOMNAV-360DP-2X-v01`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/44ab3b17f4944ef4a9d67256b7ae08d6`
- **Dimensions:** 2560x2048 px (Design Spec & Comparison Canvas)
- **HTML Artifact:** `round3-review/R3-BOTTOMNAV-360DP-2X-v01.html` (SHA-256: `6d64406842661148a8a117f3180f769a67f7782dc17daf09326117daadc75315`)
- **PNG Artifact:** `round3-review/R3-BOTTOMNAV-360DP-2X-v01.png` (SHA-256: `c9cc2ae7be935eb3ec75a0d4f1e1615470b8ea5e70f6d5c149ba2e7d25ce0363`)
- **Design Decisions for Kotlin Compose:**
  1. *Resolved SmallTraderBottomBar:* Flexible navigation container that scales height to 84dp when `LocalDensity.current.fontScale >= 1.5f`, preventing label clipping.
  2. *5 Accessible Slots:* الرئيسية (Home), المبيعات (Sales), [+] Center FAB (48x48dp #255FD6), المخزون (Inventory), الحسابات (People).
  3. *Typography Protection:* Under extreme 2.0x scaling, labels gracefully auto-wrap to two tight lines or switch to icon-focused presentation with full TalkBack content descriptions. No truncation ellipsis (`...`).
  4. *Theme Token Compliance:* All slot indicators and labels use `MaterialTheme.colorScheme` tokens (`primary`, `onSurfaceVariant`, `primaryContainer`), eliminating system dark mode hardcoding.

### Screen 3: Structured Purchase Bill Creation (`R3-PURCHASE-STRUCTURED-v01`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/3fe102815d2a4ff788627c0c842d0b52`
- **Dimensions:** 780x2570 px (Mobile 393dp @ 2x)
- **HTML Artifact:** `round3-review/R3-PURCHASE-STRUCTURED-v01.html` (SHA-256: `a0ba08905882a06573a2b13afe660b941ce9f39dced9a6a83279b7d90bcc68cd`)
- **PNG Artifact:** `round3-review/R3-PURCHASE-STRUCTURED-v01.png` (SHA-256: `54a54f86724bf8a07840ea5466c271c717d0e57dbda869c336b1352fcc9a1f7b`)
- **Design Decisions for Kotlin Compose:**
  1. *Supplier Header Card:* Dropdown / chip selector for verified suppliers, displaying supplier name, contact, and outstanding balance ("ذمة سابقة: 1,200.00 ₪").
  2. *Structured Itemized Table:* Replaces text summary. Each row includes SKU selector, Item Name, Quantity, Unit Cost (explicit numeric entry with zero-cost validation), and computed Line Total.
  3. *Financial Settlement Section:* Clear financial ledger breakdown:
     - إجمالي الفاتورة (Total): `900.00 ₪`
     - المدفوع نقداً الآن (Cash Paid): `300.00 ₪`
     - ذمة المورد المضافة (Payable Added): `600.00 ₪`
  4. *Scope Separation Banner:* Tonal info banner: "سند مالي دفتري: يقيد ذمة المورد فور الاعتماد. استلام البضاعة الفعلي وتعديل كميات المخزون يتم بشكل مستقل عبر سند الاستلام المخزني (GRN)."

### Screen 4: Goods Receiving Note & Partial Receiving (`R3-GOODS-RECEIPT-PARTIAL-v01`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/5e72ca7495ef42098e2d050f1489cc93`
- **Dimensions:** 780x2160 px (Mobile 393dp @ 2x)
- **HTML Artifact:** `round3-review/R3-GOODS-RECEIPT-PARTIAL-v01.html` (SHA-256: `3e6ea54d80d376970c4eb093f902e5d15e905341725292fb6bebfa1f3a8693cd`)
- **PNG Artifact:** `round3-review/R3-GOODS-RECEIPT-PARTIAL-v01.png` (SHA-256: `9af1945db1af6e0439da1e8ddee602961feec36f375bbbf0ebd593e9997d4c33`)
- **Design Decisions for Kotlin Compose:**
  1. *Purchase Bill Linkage:* Document header clearly references parent bill: "مرتبط بفاتورة الشراء: BILL-2026-089 — مطاحن ومحامص نابلس المركزية".
  2. *Partial Receiving Table:* Each item displays:
     - الكمية المطلوبة (Ordered): e.g. 20 كيس
     - المستلم سابقاً (Previously Received): 0 كيس
     - المستلم الفعلي الآن (Receiving Now): Interactive stepper / numeric input [ 12 ]
     - Status Badge: Warning Orange `#A15C00` "استلام جزئي (المتبقي: 8)" or Success Green `#14804A` "تم الاستلام بالكامل".
  3. *Physical Audit Guarantee:* Explicit guarantee text: "ستتم زيادة رصيد المخزون الفعلي بمقدار الكميات المستلمة الآن فقط. لا تتأثر الذمة المالية للمورد."
  4. *Duplicate Tap Guard:* Action button enters loading state upon tap to guarantee idempotent execution.

---

## 4. Repository & Artifacts Audit

### 4.1 Git Worktrees & Branch Topology
1. **Primary Workspace (`/home/t3dev/projects/small-trader-android`):**
   - Head: `f862964` (`ux/native-phase1`)
   - Untracked Historical Assets:
     - `crop_14.png`
     - `crop_16.png`
     - `slice_16_0.png`
     - `slice_16_1.png`
     - `slice_16_2.png`
     - `slice_16_3.png`
   - **Recommendation:** **MUST PRESERVE PERMANENTLY.** These are historical PSD/Figma visual slice authorities extracted during Phase 1. Do NOT delete, clean, or git-stash.
2. **PR #3 Worktree (`/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`):**
   - Head: `9336da6` (`ux/stitch-native-prototype`)
   - Working Tree: Clean; dedicated worktree for PR #3.
   - **Recommendation:** Maintain as active integration worktree for PR #3 fixes.

### 4.2 Prior Artifacts Inventory & Canonical Strategy

| Path | Description | Classification | Recommendation |
| :--- | :--- | :--- | :--- |
| `round2/validation/small-trader-round2-v3-review.apk` | Canonical Candidate v3 Review APK (19,628,852 bytes, SHA-256 `3c2d23f14a...`) | **Canonical Deliverable** | **Preserve.** Retain as verified baseline until Candidate v4 is assembled. |
| `round2/validation/native-renders-v03/` | 16 verified Robolectric Native Graphics render captures | **Validation Evidence** | **Preserve.** Essential visual regression baseline for LTR/RTL and font scale. |
| `round2/validation/small-trader-round2-review.apk` | Historical Round 2 interim APK | **Historical Baseline** | **Preserve.** Historical audit trail. |
| `.t3/artifacts/small-trader-stitch-2026-11-08/` | Master Stitch design project artifacts, prompts, and registry | **Core Design Authority** | **Preserve.** Contains master `SCREEN_REGISTRY.json` and Stitch scripts. |
| `.t3/artifacts/small-trader-review-round2/` | Round 2 review notes, frozen bottom-nav checksums, and renders | **Audit Record** | **Preserve.** Audit trail for prior freezing decision now superseded. |
| `.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/` | Round 3 Stitch-generated HTML & PNG designs (Home, BottomNav, Purchase, GRN) | **New Canonical Authority** | **Canonical Visual Spec.** Active authority for Candidate v4 implementation. |

---

## 5. Precise Source Seams for Lean Feature Refactoring

The following refactor plan isolates concerns into feature-sized units without risky global rewrites:

### Step 1: Financial Confirmation Dialogs (`SuppliersScreen.kt`)
- **Seam:** In `PaySupplierDialog` (lines ~1320–1390) and `ReceiveGoodsDialog` (lines ~1240–1280):
  - Introduce `var isSubmitting by rememberSaveable { mutableStateOf(false) }` and `var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }`.
  - Guard confirm button `onClick`: if `isSubmitting`, return early.
  - On confirm, execute action and observe domain result: on error, set `errorMessage` and leave dialog open; on success, invoke `onDismiss()`.
  - Add unit tests in `app/src/test/java/com/example/MerchantWorkflowTest.kt`.

### Step 2: Home Semantic Theme Derivation (`HomeScreen.kt`)
- **Seam:** Replace line 111 `val isDark = isSystemInDarkTheme()` with:
  ```kotlin
  val isDark = state.isDarkTheme
  ```
  Derive card borders, tonal backgrounds, and text colors directly from `MaterialTheme.colorScheme` tokens, ensuring total consistency with the user-selected theme.

### Step 3: Accessible Bottom Navigation (`CommonComponents.kt`)
- **Seam:** In `SmallTraderBottomBar` (lines 726–899):
  - Replace rigid `NavigationBar` default item wrapping with responsive text styling:
  ```kotlin
  val fontScale = LocalDensity.current.fontScale
  val labelStyle = if (fontScale >= 1.5f) {
      MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
  } else {
      MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
  }
  ```
  - Ensure every `NavigationBarItem` and the center FAB maintains a minimum 48x48dp touch target.
  - Provide complete bilingual `contentDescription` for every tab.

### Step 4: Purchase Filter Alignment (`PurchasesScreen.kt`)
- **Seam:** Line ~320: Update click handler on `Open Payables` card:
  - Either filter by predicate `{ it.remainingBalance > 0.0 }`, or expand `PurchaseFilter` to include `HAS_OUTSTANDING_BALANCE`.
  - Ensure bills that are partially paid but still have an outstanding balance remain visible when filtering payables.

### Step 5: Home Screen Layout Restructuring (`HomeScreen.kt`)
- **Seam:** Reorder items in `LazyColumn`:
  1. `item { StoreIdentityHeader(...) }`
  2. `item { PrimarySaleActionCard(hasActiveBasket, onReviewBasket, onNewSale) }` (Elevated to top)
  3. `item { CollapsibleTodaySummary(state.todayMetrics, onOpenReports) }` (Collapsed summary)
  4. `item { StreamlinedQuickActions(onInventory, onPurchase, onExpense) }` (3 tactile tiles)
  5. `item { RecentTransactionsList(...) }`

### Step 6: Structured Purchasing & Partial Receiving (`TraderModel.kt` & `SuppliersScreen.kt`)
- **Seam:** In domain models:
  - Introduce `PurchaseBillItem(productId: String, sku: String, title: String, orderedQty: Double, unitCostIls: Double)`.
  - In `TraderUiState`, maintain `PurchaseBill(id: String, supplierId: String, items: List<PurchaseBillItem>, totalIls: Double, paidNowIls: Double, receivedStatus: ReceivingStatus)`.
  - Add `GoodsReceiptNote(id: String, billId: String, receivedItems: Map<String, Double>, timestamp: Long)`.
  - Separate `receiveStock(grn: GoodsReceiptNote)` from `recordPurchaseBill(bill: PurchaseBill)`.

### Step 7: Performance Caching & Release Optimizations
- **Seam:** In `TraderViewModel.kt`:
  - Cache normalized search strings on catalog products.
  - Safely validate R8 configuration in `app/build.gradle.kts` (`isMinifyEnabled = true`, `getDefaultProguardFile("proguard-android-optimize.txt")`, `proguard-rules.pro`).

---

## 6. Benchmark & Verification Acceptance Matrix

To ensure absolute reporting integrity, all verification criteria are categorized by verification level.

| Verification Level | Metric / Check | Baseline (v3) | Acceptance Gate (v4) | Verification Method |
| :--- | :--- | :--- | :--- | :--- |
| **Local Unit Suite** | Automated Tests | 104 passed, 1 skipped, 0 failed | >= 108 passed, 0 failed, 1 skipped | `./gradlew :app:testDebugUnitTest` |
| **Static Analysis** | Android Lint | 0 errors, 61 deferred warnings | 0 errors, <= 61 warnings (no new warnings) | `./gradlew :app:lintDebug` |
| **Robolectric Graphics** | RNG Native Renders | 16 captures (`native-renders-v03`) | 16+ captures including 360dp 2.0x BottomNav | `NativeVisualEvidenceTest` |
| **APK Binary Footprint** | Release/Debug Size | Debug: 19,628,852 bytes | Measured after R8 audit; report exact delta | `stat -c %s app-release.apk` |
| **Screen Scaling** | 360dp Viewport @ 2.0x | Admitted label clipping | Clean layout, no text clipping, 48dp touch targets | Robolectric RNG + Device Verification |
| **Bilingual RTL/LTR** | Arabic & English | Tested in Arabic & English | Full RTL mirroring, no bidi layout distortion | Automated tests + Visual captures |
| **Accounting Invariants** | Stock, Balances, Role | Verified in 13 test suites | 100% preservation; regression tests added | Domain test execution |
| **Hardware Simulation** | Prototype Honesty | Simulated scanner & printer | Clear "(محاكاة)" badging, isolated demo state | Visual inspection |
| **Device Acceptance** | Windows Pixel_8a_T3 | **PENDING OWNER VERIFICATION** | **PENDING OWNER VERIFICATION** | Platform platform-tools / `device_open` |

*(Note on Verification Integrity: R8 minification, startup latency, scroll jank, and Windows AVD acceptance must be measured on real hardware/emulators with exact numbers reported; no invented or estimated percentage gains are permitted.)*

---

## 7. Actionable Hand-off to Integrator

The design assets, Stitch screen IDs, source seams, and acceptance criteria documented herein provide a complete, non-destructive path to address all review findings.

**Integrator Checklist:**
1. Leave this plan in `docs/PR3_STITCH_REVIEW_PLAN.md` uncommitted for owner/integrator review.
2. Implement fixes strictly in the worktree `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`.
3. Preserve root checkout untracked PNGs.
4. Execute test suites and report genuine, unembellished verification metrics.
