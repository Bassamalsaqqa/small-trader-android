# MERCHANT FINANCIAL PRIVACY & VALUATION REGRESSION TEST HANDOFF

**Target Branch:** `ux/merchant-experience-reset`  
**Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Role:** Test Sub-Agent (QA / Test Authoring)  
**Date:** 2026-11-07  
**Owned Files:**  
- `app/src/test/java/com/example/MerchantPrivacyTest.kt` (NEW)  
- `docs/android-merchant-ux-reset/PRIVACY_TEST_HANDOFF.md` (NEW)  

---

## 1. Executive Summary & Boundaries

This handoff documents the newly authored Robolectric Compose regression test suite covering merchant financial privacy, permission boundaries, and inventory valuation integrity.

### Strict Governance Compliance:
- **Zero Production Edits:** Only test code and this documentation were authored.
- **Zero Git Mutation:** No git commit, stash, or push operations performed.
- **No Agent/Device/System Modifications:** No background daemons, emulator installations, or external dependencies modified.
- **Gradle Deferral:** Gradle was NOT executed, strictly preserving environment stability while concurrent writers complete invoice review, checkout, and receipt workflows. Full test suite execution will be conducted by the lead integrator once all writers settle.

---

## 2. Tested Regressions & Source Reconciliation

Prior independent source review identified three critical merchant-privacy and data-integrity risks in the legacy code:
1. **Customer Aggregate Disclosure to Cashier:** Total store customer debts (receivables) and customer advances were rendered indiscriminately, exposing overall store business volume to cashiers.
2. **Stale Saveable Dialog Disclosure on Role Downgrade:** An open product create or edit dialog retained sensitive cost inputs across live role downgrades, allowing an unauthorized cashier session to view and potentially save modified costs.
3. **Fabricated Product Cost (75% Rule):** When unit cost was omitted, the editor silently fabricated an estimated cost equal to 75% of the selling price (`price * 0.75`), corrupting merchant valuation, accounting margins, and reporting honesty.

The lead integrator implemented source fixes in `CustomersScreen.kt` and `ProductsCatalogScreen.kt`:
- Aggregate debt and advance counters are wrapped in `if (state.currentSession.canViewReports())`.
- Product create/edit dialogs are gated on `canManageInventory`, and `LaunchedEffect(canManageInventory)` resets dialog state flags on permission loss so stale dialogs do not persist or reopen upon role restoration.
- Missing cost parses to `Double.NaN` instead of a fabricated 75% estimate; `canSubmit` requires explicit `parsedCost.isFinite() && parsedCost >= 0.0`, with explicit zero (`0.0`) permitted.

---

## 3. Test Suite Implementation: 

The new test suite (`app/src/test/java/com/example/MerchantPrivacyTest.kt`) uses Robolectric with SDK 35 and `createComposeRule()` to test the actual Compose UI trees.

### Test Matrix

| Test Method | Category | Verified Invariant |
|---|---|---|
| `customerDirectory_ownerRendersDebtAndAdvanceAggregates_downgradeToCashierRemovesBoth_whileIndividualBalancesAndActionsRemain` | Customer Privacy | Owner displays `total_customer_debts_value` & `total_customer_advances_value`. Live downgrade to Cashier immediately removes both tags from composition (`assertDoesNotExist()`), while individual customer directory rows (`customer_row_*`), quick collections (`quick_collect_*`), and customer creation remain accessible. |
| `productCatalog_launchCreateEditor_downgradeToCashierDismissesEditorAndCostInput_restoreOwnerDoesNotReopenStaleDialog` | Role Downgrade Eviction | Creating a product via `launchCreate = true` displays `product_editor_cost_input` for Owner. Live downgrade to Cashier immediately removes cost input, save button, and `add_product_button`. Restoring Owner role does **not** reopen the stale dialog automatically. |
| `productDetail_editDialog_downgradeToCashierDismissesEditorAndCostSection_restoreOwnerDoesNotReopenStaleDialog` | Edit Counterpart Eviction | Opening product edit dialog on `ProductDetailScreen` displays cost input. Downgrading to Cashier evicts dialog, cost input, and `product_detail_cost_section`. Restoring Owner restores detail cost section without resurrecting stale edit dialog. |
| `productEditor_blankCostPreventsSubmit_explicitZeroCostEnablesAndCapturesZeroCost_neverFabricatesSeventyFivePercent` | Valuation Integrity | Blank cost prevents saving: filling name and price leaves save button disabled; exactly 0 save callbacks posted. Explicitly entering `0` enables save; clicking save invokes callback with `cost = 0.0`, confirming rejection of fabricated `price * 0.75` (15.00 vs 0.0). |
| `productEditor_explicitNonZeroCost_enablesSubmitAndCapturesExactCost` | Valuation Fidelity | Confirms user-entered non-zero cost (e.g. 28.50 on 40.00 price) is captured faithfully rather than overwritten or estimated. |

---

## 4. Key Design Patterns & Engineering Details

1. **Callback Arity and Typing:**
   - `onSaveProduct` adheres to the exact 13-parameter signature established in `ProductsCatalogScreen.kt` and wired in `MainActivity.kt`:
     `(productId: String?, nameAr: String, category: ProductCategory, unitAr: String, barcode: String, priceIls: Double, costIls: Double, minStockThreshold: Int, initialStock: Int, warehouseAr: String, lotBatchCode: String, expiryDate: String?, isWeightedDisplayOnly: Boolean) -> Unit`.
2. **Deterministic Callback Counting:**
   - Tests enforce exact 0 callback invocations before user interaction, exact 0 invocations when cost is blank, and exactly 1 invocation upon clicking an enabled submit button.
3. **Viewport & Scroll Robustness:**
   - `performScrollTo()` is applied to all form fields inside scrollable dialog containers (`product_editor_name_input`, `product_editor_price_input`, `product_editor_cost_input`) and detail action buttons.
4. **Stable Semantic Test Tags:**
   - Uses verified tags from production source:
     - `total_customer_debts_value`
     - `total_customer_advances_value`
     - `customer_row_{id}`
     - `quick_collect_{id}`
     - `add_customer_button`
     - `product_editor_name_input`
     - `product_editor_price_input`
     - `product_editor_cost_input`
     - `confirm_save_product_button`
     - `product_detail_cost_section`
     - `edit_product_button`
5. **No Speculative Implementation Assumptions:**
   - Tests do not rely on fragile localized string matching for logic assertions; semantics tags and callback payloads are verified directly.

---

## 5. Outstanding Test Assumptions & Next Steps for Lead Integrator

1. **Test Runner Environment:**
   - Configured for Robolectric with Android SDK 35 (`@Config(sdk = [35])`), matching `MerchantHomeTest.kt` and `MerchantCheckoutTest.kt`.
   - Local unit test execution requires Android offline test resources enabled (`isIncludeAndroidResources = true`, already configured in `app/build.gradle.kts`).
2. **Concurrent Writers Convergence:**
   - AGY writers on `InvoiceReviewScreen`, `CashPaymentScreen`, and `ReceiptScreen` have not been touched or disrupted.
3. **Full QA Execution:**
   - When the lead integrator initiates the consolidated integration build, run:
     ```bash
     ./gradlew testDebugUnitTest --tests "com.example.MerchantPrivacyTest"
     ```
   - Combined test run command:
     ```bash
     ./gradlew testDebugUnitTest
     ```
