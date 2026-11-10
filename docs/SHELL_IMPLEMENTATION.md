> Historical delegated implementation report. Claims are subject to lead reconciliation; see STITCH_NATIVE_HANDOFF.md and final validation evidence. This report alone does not establish builds, runtime behavior or owner approval.

# Native Shell, Navigation & Quick Action Implementation Report
**Document Version:** 1.0.0  
**Date:** 2026-11-08  
**Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Branch:** `ux/stitch-native-prototype` starting from `f4713c0`  
**Target Stitch Reference:** 
- ST-01 Header & Root Navigation (`ST-01-AR-LIGHT-OWNER-NORMAL-v02`, Source `0e1f63884b964b639b7fb0203a831da8`)
- ST-03 Global Quick Action Bottom Sheet (`ST-03-AR-LIGHT-OWNER-QUICKSHEET-v01`, Source `6db76a215ca949beb69409dcf31ba1e8`)
- Canonical Design System Asset: `assets/51afd92dffb8461ab336e6d3d380e71b` (Project `15641450350510420845`)

---

## 1. Executive Summary & Ownership Boundaries

In strict compliance with `native/ACTIVE_OWNERSHIP.md` and the implementation mandate, this report documents the Shell Native Wave implementation inside the assigned isolated worktree branch `ux/stitch-native-prototype`.

### 1.1 Strict Disjoint File Ownership
The Shell Agent modified ONLY the following assigned files:
1. `app/src/main/AndroidManifest.xml` (Lint hygiene: eliminated redundant activity label)
2. `app/src/main/java/com/example/MainActivity.kt` (Wired catalog -> review -> payment, verified Scaffold WindowInsets/IME ownership)
3. `app/src/main/java/com/example/ui/shell/QuickActionSheet.kt` (Full native Compose implementation of ST-03)
4. `app/src/main/java/com/example/ui/components/CommonComponents.kt` (Implemented ST-01 top bar header, ST-01 5-slot bottom bar, preserved simulation sheets and public APIs)
5. `app/src/main/java/com/example/ui/navigation/TraderNavigationPolicy.kt` (Verified: exact roots, roles, and action permissions preserved)

**Strictly Avoided Files (No Touches):**
- Zero edits to ViewModels (`TraderViewModel.kt`) or Repositories (`TraderRepository.kt`, `SampleCatalog.kt`).
- Zero edits to Foundation files (`HomeScreen.kt`, `Theme.kt`, `Color.kt`, `Type.kt`, `StitchComponents.kt`, font TTF files).
- Zero edits to Sales screens (`NewSaleScreen.kt`, `InvoiceReviewScreen.kt`, `CashPaymentScreen.kt`, `ReceiptScreen.kt`).
- Zero edits to test files or build configurations (`build.gradle.kts`, `settings.gradle.kts`).
- No Git branch, commit, push, Gradle run, backend changes, or global system alterations.

---

## 2. Shell Top App Bar Header (Stitch ST-01)

### 2.1 Store Identity & User Header
- **Storefront Identity:** Located at the start of the TopAppBar on `TraderScreen.HOME`, rendering the store icon (`Icons.Default.Store`, tint `#255FD6`) inside a 38dp circular surface (`surfaceVariant.copy(alpha = 0.55f)`), accompanied by the business name (`state.businessInfo.name(lang)`) in SemiBold 15sp.
- **Active Merchant Subtitle:** Displays the active session merchant name (`state.currentSession.name`, e.g. `أبو كريم (باسم السقا)`) in 12sp onSurfaceVariant, avoiding duplicate identity cards inside the Home screen body.
- **Elimination of Dead Bell:** The non-functional notification bell icon (`notifications`) was completely removed, adhering to Section 1 of `DESIGN_LOCK.md` and `CORE_FAMILY_REVIEW.md`.

### 2.2 Profile / Account Button & Sheet
- **Avatar Button Hit Area:** Rendered at the end of the TopAppBar within an explicit `sizeIn(minWidth = 48.dp, minHeight = 48.dp)` container with testTag `top_bar_account_button`.
- **Dynamic User Initials:** Computes real initials from `state.currentSession.name` (e.g. `أك` for Abu Karim), styled with bold text `#255FD6` inside a `#EEF4FF` container with 1dp `#D6DEEA` border.
- **Account & Identity Sheet (`ModalBottomSheet`):**
  - Displays user profile card with user name, role badge (`state.currentSession.role.label(lang)`), and business name.
  - Direct navigation to Profile & Role (`TraderScreen.USER_PROFILE`) and Store Settings (`TraderScreen.BUSINESS_SETTINGS`).
  - Role-guarded links to Reports & Cash Drawer (`TraderScreen.FINANCIAL_REPORTS`) and Expenses (`TraderScreen.EXPENSES`).
  - Fast toggles for Language (`onToggleLanguage`) and Dark/Light Appearance (`onToggleTheme`).
  - Truthful receipt printer simulation point-of-use (`onOpenPrinter`).
  - About & Diagnostics expander showing session ID and in-memory status.

### 2.3 Non-Root Screen Header
- Non-root destinations automatically display a 48dp hit-area back button (`Icons.AutoMirrored.Filled.ArrowBack`, testTag `top_bar_back_button`) and concise Arabic/English screen titles (e.g. `مراجعة البيع` / `Sale Review`, `الدفع النقدي` / `Cash Payment`).

---

## 3. Root Bottom Navigation Bar (Stitch ST-01)

### 3.1 Five-Slot Layout Architecture
The bottom navigation bar (`SmallTraderBottomBar`) arranges 5 slots in strict physical sequence:
1. **الرئيسية (Home):** `TraderScreen.HOME`, `Icons.Default.Home`, testTag `nav_home_tab`
2. **المبيعات (Sales Ledger):** `TraderScreen.INVOICES_HISTORY`, `Icons.AutoMirrored.Filled.ReceiptLong`, testTag `nav_invoices_tab`
3. **[+] Quick Action (Center Elevated Action):** Centered elevated circular trigger (`Icons.Default.Add`), size 48dp, elevation 4dp, `#255FD6` background, testTag `nav_quick_add`
4. **المخزون (Inventory):** `TraderScreen.PRODUCTS_CATALOG`, `Icons.Default.Inventory2`, testTag `nav_inventory_tab`
5. **الحسابات (Accounts / Debts):** `TraderScreen.PEOPLE`, `Icons.Default.People`, testTag `nav_people_tab`

### 3.2 Layout & BiDi Mechanics
- In RTL (Arabic), Compose automatically arranges child 0 at physical right, placing `الرئيسية` on the far right and `الحسابات` on the far left, with `[+]` in the exact visual center.
- In LTR (English), child 0 is at physical left, preserving standard directional navigation.
- Each item is assigned `Modifier.weight(1f)`, ensuring equal column distribution across 393dp viewports.

### 3.3 Accessibility & Typography Guards
- Minimum label font size is strictly 12sp (`fontSize = 12.sp`), conforming to the Arabic typography minimum locked in `DESIGN_LOCK.md`.
- `maxLines = 1` with `TextOverflow.Ellipsis` ensures resilient layout under 2x accessibility font scaling.
- Interactive bounds for all 5 items meet or exceed **48 × 48 dp**.
- Full TalkBack descriptions are provided for screen readers (`contentDescription = lang.tr("إجراء سريع", "Quick action")`).

---

## 4. Global Quick Action Sheet (Stitch ST-03)

### 4.1 Visual Structure (`QuickActionSheet.kt`)
- **Container:** Native `ModalBottomSheet` with 20dp top rounded corners, drag handle, system navigation insets, and clear dismiss button (Close `X`, 48dp hit area).
- **Header:** Titled `إضافة` (Add) with `إجراء سريع` (Quick action) pill badge.
- **Primary Tactical Actions (56dp min height each):**
  1. `بيع جديد` (New Sale): Dominant hero row with `#EEF4FF` container, `#255FD6` border, `ShoppingCart` icon, and `شائع` badge. TestTag: `quick_sale`.
  2. `شراء جديد` (New Purchase): Supplier intake row, role-filtered for supplier management. TestTag: `quick_purchase`.
  3. `قبض من زبون` (Customer Debt Collection): Payment receipt row with success-tinted container (`#E8F5E9`, `#14804A`), opening the customer chooser. TestTag: `quick_collect`.
  4. `دفع لمورد` (Supplier Pay-Out): Supplier payment row, role-filtered. TestTag: `quick_pay`.
- **Concise Copy Guarantee:** Removed pedagogical/tutorial paragraphs (`فتح سلة بيع فارغة وفحص الباركود`), replacing them with short operational tags (`سلة بيع جديدة وفحص باركود`).
- **Subdued Expander (`المزيد من العمليات`):**
  - Initially collapsed 48dp trigger with `Apps` icon and hint `(صنف، زبون، مورد، مصروف)`.
  - Expands with smooth animation (`expandVertically + fadeIn`) to reveal secondary role-filtered creation controls:
    - `صنف جديد` (New Product, `quick_product`)
    - `زبون جديد` (New Customer, `quick_customer`)
    - `مورد جديد` (New Supplier, `quick_supplier`)
    - `مصروف جديد` (New Expense, `quick_expense`)
- All 8 actions dispatch via existing `onAction(MerchantAction)` callback and preserve test tags.

---

## 5. Checkout & Flow Routing Contract

### 5.1 Catalog -> Review -> Payment Alignment
In `MainActivity.kt`:
- **NewSaleScreen:** Primary action `onProceedToInvoiceReview` now routes directly to `TraderScreen.INVOICE_REVIEW` (previously routed to CASH_PAYMENT).
- **InvoiceReviewScreen:** Primary action `onProceedToCashPayment` routes to `TraderScreen.CASH_PAYMENT`.
- **Back Navigation:** `onNavigateBack` from review calls `traderViewModel.navigateBack()`, safely returning to `TraderScreen.NEW_SALE` while preserving active basket items.
- **Drafts Preservation:** Callbacks `onSaveDraft`, `onResumeDraft`, and `onDeleteDraft` are fully retained.

### 5.2 Single WindowInsets & IME Ownership
- `Scaffold` maintains `contentWindowInsets = WindowInsets.safeDrawing`.
- Inner content `Box` applies:
  ```kotlin
  Modifier
      .fillMaxSize()
      .padding(innerPadding)
      .consumeWindowInsets(innerPadding)
      .imePadding()
  ```
- Consuming `innerPadding` prevents nested child screens (such as `InvoiceReviewScreen` navigation-bar padding) from double-padding the bottom safe area.

---

## 6. Android Lint Hygiene

- In `app/src/main/AndroidManifest.xml`, removed redundant `android:label="@string/app_name"` from the `.MainActivity` `<activity>` tag (since `<application>` already declares it).
- Resolves Android Lint `RedundantLabel` warning without suppressing or silencing genuine behaviors.

---

## 7. Verification & Limitations

1. **`git diff --check`:** Passed cleanly with 0 whitespace errors or formatting issues.
2. **Contract Preservation:** All existing public callback signatures for `SmallTraderTopBar`, `SmallTraderBottomBar`, `SimulatedBarcodeScannerSheet`, `BluetoothPrinterModalSheet`, `CustomQuickItemDialog`, and `QuickActionSheet` are preserved.
3. **No Build Claims:** This report makes no claims of running Gradle builds or generating APKs, respecting explicit orchestration boundaries.
