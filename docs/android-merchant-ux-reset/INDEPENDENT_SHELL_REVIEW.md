# Independent Shell & Navigation Review

**Target Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Branch:** `ux/merchant-experience-reset`  
**Baseline:** `f862964`  
**Review Type:** Independent Read-Only Final Review  
**Date:** 2026-11-07  
**Scope:** Shell (`MainActivity.kt`, `TraderNavigationPolicy.kt`, `QuickActionSheet.kt`), Home (`HomeScreen.kt`), Navigation/State Management (`TraderViewModel.kt`), and Minimal Module Wiring (`ProductsCatalogScreen.kt`, `CustomersScreen.kt`, `SuppliersScreen.kt`, `BusinessExtraScreens.kt`, `PeopleHubScreen.kt`, `PurchasesScreen.kt`). Excludes sell-stream screens (`NewSaleScreen.kt`, `InvoiceReviewScreen.kt`, `CashPaymentScreen.kt`, `ReceiptScreen.kt`).  
**Operational Constraints:** Zero production source or test modifications (temporarily owned by QA agent running full Gradle); no test/build execution; no git/system/agent mutations.

---

## 1. Executive Summary

This independent review inspects the integrated shell, navigation architecture, Home screen refactoring, and minimal domain screen wiring implemented across branch `ux/merchant-experience-reset` against baseline `f862964` and the reconciled master brief (`RESEARCH_RECONCILIATION.md`).

Overall architectural health is solid:
- Domain models and repository calculations remain **100% untouched** (zero financial calculation drift, zero model signature breakage).
- Prior reviewer corrections have been faithfully implemented and verified directly from the source code.
- Insets ownership is unified under `Scaffold` with clean `consumeWindowInsets(innerPadding).imePadding()` propagation.
- Role-based permissions are enforced both at the navigation router layer (`TraderNavigationPolicy`) and within individual composables with active `LaunchedEffect` reset guards upon session downgrade.

One actionable source defect in quick-action routing was identified (`MerchantAction.PRODUCT` and `MerchantAction.SUPPLIER` do not navigate to their target modules), alongside a few low-risk visual/edge cases. No unhandled crashes or repository exceptions were found.

---

## 2. Verification of Prior Native Reviewer Corrections

Each of the five prior lead corrections was rechecked line-by-line in the actual source code:

### 1. Global Collection Routes to Updated Customer Account Retaining Success
- **Source Location:** `app/src/main/java/com/example/viewmodel/TraderViewModel.kt` (lines 1345–1348, 1351–1385) and `app/src/main/java/com/example/ui/screens/CustomersScreen.kt` (lines 712–735).
- **Verification:** 
  - `TraderViewModel.confirmCustomerCollection(...)` invokes `recordCustomerPayment(...)`, synchronizes from repository, updates `selectedCustomerForDetail` with `updatedCust`, and formats bilingual feedback (`lastCustomerPaymentMessageAr` / `En`).
  - Upon successful payment, it executes `navigateTo(TraderScreen.CUSTOMER_DETAIL)`.
  - `CustomerDetailScreen` reads `state.selectedCustomerForDetail` and displays the updated balance immediately alongside the green feedback banner (`testTag("customer_payment_feedback_banner")`).
  - `navigateTo(...)` preserves `lastCustomerPaymentMessageAr` / `En` (does not clear it).
- **Test Coverage:** Verified in `MerchantNavigationTest.kt` (lines 120–130: `quickCollectionShowsUpdatedAccountAndKeepsSuccessFeedback`).
- **Status:** **COMPLETE & VERIFIED**.

### 2. Product Cost Editor Role Render & Reset Guards
- **Source Location:** `app/src/main/java/com/example/ui/screens/ProductsCatalogScreen.kt` (lines 106–111, 570–575, 809–820, 925–935, 1032, 1119, 1298).
- **Verification:**
  - `ProductsCatalogScreen` and `ProductDetailScreen` both register `LaunchedEffect(canManageInventory)` that immediately resets `isCreateProductOpen = false`, `isStockTransferPreviewOpen = false`, `isEditPriceOpen = false`, `isAdjustStockOpen = false`, and `isEditProductOpen = false` if permissions are revoked.
  - Rendering of `product_detail_cost_section` (lines 809–820) and administrative control rows (lines 925–935) is strictly enclosed in `if (canManageInventory)`.
  - Stale dialog inputs cannot survive role downgrades or resurrect upon returning to Owner.
- **Test Coverage:** Verified in `MerchantPrivacyTest.kt` (`productCatalog_launchCreateEditor_downgradeToCashierDismissesEditorAndCostInput_restoreOwnerDoesNotReopenStaleDialog` and `productDetail_editDialog_downgradeToCashierDismissesEditorAndCostSection_restoreOwnerDoesNotReopenStaleDialog`).
- **Status:** **COMPLETE & VERIFIED**.

### 3. New Customer Action Uses CUSTOMERS Route to Avoid Saved Supplier Segment
- **Source Location:** `app/src/main/java/com/example/viewmodel/TraderViewModel.kt` (line 544) and `app/src/main/java/com/example/MainActivity.kt` (lines 331–345).
- **Verification:**
  - `openMerchantAction(MerchantAction.CUSTOMER)` explicitly executes `navigateTo(TraderScreen.CUSTOMERS)` followed by `openAddCustomerDialog(true)`.
  - `TraderScreen.CUSTOMERS` mounts `CustomersScreen` directly in `MainActivity.kt` line 331, completely bypassing `PeopleHubScreen` and its `rememberSaveable` `supplierSegment` state.
  - Tapping New Customer will never accidentally open over the Supplier list.
- **Test Coverage:** Verified in `MerchantNavigationTest.kt` (lines 132–138: `newCustomerQuickActionUsesCustomerDirectory`).
- **Status:** **COMPLETE & VERIFIED**.

### 4. Customer Directory Aggregates Are Report-Only
- **Source Location:** `app/src/main/java/com/example/ui/screens/CustomersScreen.kt` (lines 177–220).
- **Verification:**
  - The aggregate metrics strip displaying `total_customer_debts_value` (receivables owed to store) and `total_customer_advances_value` (customer credits) is wrapped in `if (state.currentSession.canViewReports())`.
  - When a cashier is active, the metrics strip is omitted entirely and replaced with `Spacer(Modifier.weight(1f))` to preserve alignment of the New Customer button.
- **Test Coverage:** Verified in `MerchantPrivacyTest.kt` (`customerDirectory_ownerRendersDebtAndAdvanceAggregates_downgradeToCashierRemovesBoth_whileIndividualBalancesAndActionsRemain`).
- **Status:** **COMPLETE & VERIFIED**.

### 5. Product Cost Requires Explicit Finite Value >= 0 (Rejection of 75% Estimation)
- **Source Location:** `app/src/main/java/com/example/ui/screens/ProductsCatalogScreen.kt` (lines 1367, 1370, 1426–1434, 1561–1563).
- **Verification:**
  - `parsedCost` is computed as `costText.toDoubleOrNull() ?: Double.NaN`.
  - `canSubmit` requires `nameAr.trim().length >= 2 && parsedPrice.isFinite() && parsedPrice > 0.0 && parsedCost.isFinite() && parsedCost >= 0.0`.
  - Blank cost sets `Double.NaN`, preventing submission. Explicit zero (`0.0`) is accepted. Negative numbers or invalid formats trigger `isError = true` on the field.
  - The legacy `price * 0.75` estimation is eliminated; zero valuation fabrication occurs.
- **Test Coverage:** Verified in `MerchantPrivacyTest.kt` (`productEditor_blankCostPreventsSubmit_explicitZeroCostEnablesAndCapturesZeroCost_neverFabricatesSeventyFivePercent`).
- **Status:** **COMPLETE & VERIFIED**.

---

## 3. Structural & Architectural Audit

### A. Root Bar Accessibility & Selection
- **Implementation:** `SmallTraderBottomBar` in `app/src/main/java/com/example/ui/components/CommonComponents.kt` (lines 461–493).
- **Structure:** Contains 4 `NavigationBarItem`s (`HOME`, `INVOICES_HISTORY`, `PRODUCTS_CATALOG`, `PEOPLE`) and 1 central `IconButton` (`size(56.dp)`) for `nav_quick_add`.
- **Selection State:** Evaluated via `state.currentScreen == screen`. Because `SmallTraderBottomBar` is rendered only when `state.isRootDestination` is true (`MainActivity.kt` line 106), `state.currentScreen` is guaranteed to be one of the four roots when composed.
- **Accessibility:** 
  - `IconButton` provides `contentDescription = lang.tr("إضافة عملية", "New action")` with a generous 56dp touch target exceeding WCAG 48dp minimums.
  - Navigation tabs feature explicit bilingual text labels and semantic test tags (`nav_home_tab`, `nav_invoices_tab`, `nav_inventory_tab`, `nav_people_tab`).

### B. Insets Ownership & WindowInsets Consumption
- **Implementation:** `MainActivity.kt` (lines 88–121) and `CommonComponents.kt` (line 467).
- **Audit Findings:**
  - `Scaffold` defines `contentWindowInsets = WindowInsets.safeDrawing`.
  - Inside the content lambda, `innerPadding` is applied via `Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()`.
  - `SmallTraderBottomBar` declares `windowInsets = WindowInsets.navigationBars`.
  - No duplicate `imePadding()` or `safeDrawingPadding()` calls exist inside child screens (verified across `HomeScreen`, `ProductsCatalogScreen`, `CustomersScreen`, `SuppliersScreen`, `BusinessExtraScreens`, `PeopleHubScreen`).
  - Insets ownership is unified and adheres to Compose insets guidelines.

### C. Idempotency & Duplicate Submission Protection
- **Implementation:** `CustomersScreen.kt` (lines 1522, 1720–1730) and `TraderRepository.kt` (lines 485–503).
- **Audit Findings:**
  - `CustomerPaymentCollectionDialog` binds a stable `operationId = rememberSaveable(customer.id) { "cpay_${customer.id}_${System.currentTimeMillis()}" }`.
  - Multiple taps or recompositions pass the identical ID.
  - `TraderRepository.recordCustomerPayment` checks `customerTransactions.firstOrNull { it.id == paymentId }` and returns the existing transaction without double deducting balance.
  - `SupplierDetailScreen` uses `paymentDraft.SaveableStateProvider("payment_${supplier.id}_${state.supplierPayments.size}")` to advance the form state key only upon successful payment recording.

### D. Stale Role Flags & Session Switching
- **Implementation:** `TraderViewModel.kt` (lines 525–534).
- **Audit Findings:**
  - `switchUserSession` evicts `pendingAction = null`, `isQuickActionOpen = false`, `isReceivePurchaseDialogOpen = false`, `isAddExpenseDialogOpen = false`, and `selectedSupplierForDetail = null`.
  - Disallowed child routes in `screenHistory` are filtered through `TraderNavigationPolicy.allows(...)`.
  - If current screen is disallowed under the new session, navigation falls back safely to `TraderScreen.HOME`.

### E. API Compatibility & Data Models
- **Audit Findings:**
  - `TraderModels.kt` diff against baseline `f862964` contains only the addition of `PEOPLE` and `PURCHASES` to `enum class TraderScreen`.
  - `TraderRepository.kt` has **0 modified lines** vs `f862964`. Domain math, decimal scaling, and repository contracts remain identical.

---

## 4. Priority Findings & Recommended Fixes

### Finding 1: Lost Navigation on Quick Actions "New Product" and "New Supplier" (Source Defect)
- **Severity:** Source Defect (Priority 1)
- **File & Lines:** `app/src/main/java/com/example/viewmodel/TraderViewModel.kt` (lines 538–557) & `app/src/main/java/com/example/MainActivity.kt` (lines 271, 376).
- **Description:**
  When a user opens `QuickActionSheet` from `HOME` (or `PEOPLE`) and selects either `MerchantAction.PRODUCT` or `MerchantAction.SUPPLIER`:
  ```kotlin
  fun openMerchantAction(action: MerchantAction) {
      if (!action.allowed(_uiState.value.currentSession)) return
      setQuickActionOpen(false)
      when (action) {
          MerchantAction.SALE -> navigateTo(TraderScreen.NEW_SALE)
          MerchantAction.PURCHASE -> { navigateTo(TraderScreen.PURCHASES); openReceivePurchaseDialog(true) }
          MerchantAction.CUSTOMER -> { navigateTo(TraderScreen.CUSTOMERS); openAddCustomerDialog(true) }
          MerchantAction.EXPENSE -> { navigateTo(TraderScreen.EXPENSES); openAddExpenseDialog(true) }
          MerchantAction.COLLECT, MerchantAction.PAY -> {
              _uiState.update { it.copy(pendingAction = action) }
          }
          else -> {
              _uiState.update { it.copy(pendingAction = action) }
          }
      }
  }
  ```
  Both `PRODUCT` and `SUPPLIER` fall into the `else` branch, setting `pendingAction = action` without navigating to `TraderScreen.PRODUCTS_CATALOG` or `TraderScreen.SUPPLIERS`.
  Because `launchCreate` is passed only when `screen == TraderScreen.PRODUCTS_CATALOG` or `TraderScreen.SUPPLIERS`, and because neither screen is composed while on `HOME`, the launch effect never executes. The user remains on the Home screen with an invisible `pendingAction` that merely traps the next Back press.
- **Recommended Fix (for QA Agent):**
  Update `openMerchantAction` in `TraderViewModel.kt`:
  ```kotlin
  MerchantAction.PRODUCT -> {
      navigateTo(TraderScreen.PRODUCTS_CATALOG)
      _uiState.update { it.copy(pendingAction = action) }
  }
  MerchantAction.SUPPLIER -> {
      navigateTo(TraderScreen.SUPPLIERS)
      _uiState.update { it.copy(pendingAction = action) }
  }
  ```

---

### Finding 2: Unreachable Restriction Banner in `ExpensesScreen` (Dead Code / Visual Risk)
- **Severity:** Low (Dead Code / Visual Risk)
- **File & Lines:** `app/src/main/java/com/example/ui/screens/BusinessExtraScreens.kt` (line 99 vs line 205).
- **Description:**
  Line 99 declares `val canManageExpenses = state.currentSession.canManageExpenses(); if (!canManageExpenses) return`.
  Consequently, lines 205–224 (`if (!canManageExpenses) Surface(modifier = Modifier.testTag("cashier_expense_restriction_banner"))`) are unreachable dead code.
- **Impact:** None in runtime because `TraderNavigationPolicy` blocks Cashier navigation to `TraderScreen.EXPENSES` before composition.
- **Recommended Fix:** Retain as defense-in-depth or remove the unreachable banner block during next cleanup.

---

### Finding 3: Empty Party Chooser State (Edge Case / Visual Risk)
- **Severity:** Low (Edge Case)
- **File & Lines:** `app/src/main/java/com/example/MainActivity.kt` (lines 439–460).
- **Description:**
  If `state.customers` or `state.suppliers` is empty when `MerchantAction.COLLECT` or `MerchantAction.PAY` is triggered, the `AlertDialog` renders an empty list with no placeholder text or explicit dismiss button (dismissible only via scrim tap or Back key).
- **Recommended Fix:** Add a fallback `Text(lang.tr("لا يوجد حسابات مسجلة", "No accounts recorded"))` when the list is empty.

---

## 5. Distinction: Source Blockers vs. Visual Risks

- **No Source Blockers / Crashes:**
  All repository operations (`recordSale`, `recordCustomerPayment`, `saveProduct`, `recordSupplierPayment`, `confirmPhysicalGoodsReceipt`) return `Result<T>` and are checked via `isSuccess` / `isFailure`. No unhandled exceptions or crash vectors were identified.
- **Visual Risks Identified:**
  1. `SmallTraderBottomBar` icon-only quick add button vertically centers without a label; requires visual verification with Arabic system font scaling.
  2. Dialog inputs in `ProductEditorDialog` require keyboard elevation testing to ensure `imePadding()` does not clip bottom save actions.

---

## 6. Concrete Remaining Device Checks

These verification steps must be executed on a physical Android device or emulator during final QA acceptance:
1. **RTL & Number Rendering:** Verify Arabic locale numeral formatting across `HomeScreen` and `CustomerDetailScreen` (`WesternFormatters.formatIls`).
2. **Dynamic Type Scaling:** Test with system font scaling set to 1.5x and 2.0x to verify that bottom bar labels, dominant New/Resume sale buttons, and dialog action bars do not truncate.
3. **IME Soft Keyboard Interaction:** Open `CustomerPaymentCollectionDialog` and `ProductEditorDialog` in landscape/portrait; confirm soft keyboard pushes fields upward without obscuring confirm buttons.
4. **Quick Action Routing Verification:** Validate that the fix for `MerchantAction.PRODUCT` and `MerchantAction.SUPPLIER` cleanly mounts their respective dialogs and allows Back dismissal to Home.
