# Home Screen Implementation & Lead Review Revision Report

**Target Workspace Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Branch:** `ux/merchant-experience-reset`  
**Owned Files:**
1. `app/src/main/java/com/example/ui/screens/HomeScreen.kt`
2. `app/src/test/java/com/example/MerchantHomeTest.kt`
3. `docs/android-merchant-ux-reset/HOME_IMPLEMENTATION.md`

---

## 1. Executive Summary & Lead Review Response

Following lead review of the initial 1248-line Home screen implementation, a substantive native mobile interaction rewrite was executed to replace the lingering dashboard silhouette with a calm, task-first mobile experience aligned with merchant reality:

| Lead Finding / Objection | Architectural Resolution in Revision |
|---|---|
| **Active cart card stacked with 4 competing actions in one row** | Replaced with **one dominant full-width action**: when cart is active, single prominent `home_resume_review_button` labeled `متابعة البيع` (`Resume Sale`) with concise item count and total, without explanatory resume paragraphs. When cart is empty, single prominent `hero_new_sale_button` labeled `بيع جديد` (`New Sale`). |
| **Secondary actions crowded into primary action** | 48dp tactile secondary actions (`hero_scan_barcode_button` and `home_quick_custom_item_button`) now sit in a **dedicated secondary row** directly below the primary hero action. When an active cart exists, a quiet, non-crowding text affordance allows adding more items. |
| **Line ~559 conflated financial bill with physical stock receipt** (`فاتورة شراء وتوريد بضاعة` / `Record vendor bill and receive stock`) | **Permanently eliminated** the misleading subtitle and any goods receipt implication. Authorized inventory managers now see a single concise, full-width button: `شراء جديد` (`New Purchase`, `home_purchase_button`). Physical receiving remains separate and bounded in Inventory/Purchases. |
| **Owner 2-KPI bordered pulse with drawer cash and float** | Replaced with **ONLY ONE Today's Sales figure** with date/invoice scope (`مبيعات اليوم • X فاتورة`) and quiet Reports drill-down (`home_reports_button`). Removed drawer cash and opening float from Home (drawer cash belongs in financial reports). Cashiers strictly see **no owner totals**. |
| **Bordered low stock card (3 rows) AND 3 invoice cards** | Replaced with **one short activity/attention list with a strict MAX 3 rows TOTAL**: exactly 1 navigation-only stock alert summary row (`home_catalog_button`) + up to 2 recent invoices (or up to 3 invoices if no stock alerts exist). |
| **Red stock card, pills jungle, border slabs, duplicated chips** | Eliminated border slabs, status pill clutter, and duplicated secondary operation chips (`home_expenses_button`, `home_suppliers_button`, `home_customers_button`). All operational features remain reachable via the shell bottom bar (`Home \| Sales \| + \| Inventory \| People`). |
| **Bloated 1248-line implementation with dead code** | Refactored into **345 lines of clean, modular, idiomatic Compose code**, favoring small composables, semantic theme tokens, and removing unused imports and dead layouts. |

---

## 2. Callback Contract & Signature Compatibility

The `HomeScreen` function signature maintains 100% backward compatibility with existing callers in `MainActivity.kt` and test suites:

```kotlin
@Composable
fun HomeScreen(
    state: TraderUiState,
    onStartNewSale: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPrinter: () -> Unit,
    onOpenCustomItem: () -> Unit,
    onReviewBasket: () -> Unit,
    onOpenInvoice: (InvoiceRecord) -> Unit,
    onViewAllInvoices: () -> Unit,
    onRestockProduct: (Product) -> Unit,
    onAddProductToSale: (Product) -> Unit,
    onNavigateToCatalog: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToSuppliers: () -> Unit,
    onNavigateToExpenses: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPurchase: () -> Unit = onNavigateToSuppliers
)
```

### Callbacks Detailed Accounting:
- **`onOpenPurchase: () -> Unit = onNavigateToSuppliers` (OPTIONAL AT END):** Wired to `home_purchase_button` for authorized managers.
- **`onStartNewSale: () -> Unit`:** Invoked by dominant `hero_new_sale_button` when cart is empty, and by quiet add-items affordance when cart is active.
- **`onReviewBasket: () -> Unit`:** Invoked by dominant `home_resume_review_button` when active sale exists. All basket drafts are fully preserved.
- **`onOpenScanner: () -> Unit`:** Invoked by 48dp `hero_scan_barcode_button`.
- **`onOpenCustomItem: () -> Unit`:** Invoked by 48dp `home_quick_custom_item_button`.
- **`onNavigateToCatalog: () -> Unit`:** Invoked by the single stock alert summary row (`home_catalog_button`).
- **`onRestockProduct: (Product) -> Unit`:** Retained for signature compatibility; **never invoked** from Home screen. Home strictly avoids stock mutations.
- **`onAddProductToSale: (Product) -> Unit`:** Retained for signature compatibility.
- **`onOpenInvoice: (InvoiceRecord) -> Unit`:** Invoked by recent invoice row items.
- **`onViewAllInvoices: () -> Unit`:** Invoked by `view_all_invoices_button` in the activity header.
- **`onNavigateToReports: () -> Unit`:** Invoked by quiet `home_reports_button` on the owner sales figure.
- **`onOpenPrinter`, `onNavigateToSettings`, `onNavigateToCustomers`, `onNavigateToSuppliers`, `onNavigateToExpenses`:** Retained in signature; features are accessed through the shell top bar, avatar menu, and navigation bar.

---

## 3. Semantic Test Tag Accounting

### Preserved Test Tags (Semantically Meaningful):
- `home_screen_list`: Main scrollable `LazyColumn`.
- `hero_new_sale_button`: Dominant full-width New Sale button (visible only when cart is empty).
- `home_resume_review_button`: Dominant full-width Resume Sale button (visible only when cart is active).
- `hero_scan_barcode_button`: Dedicated 48dp barcode scanner action button.
- `home_quick_custom_item_button`: Dedicated 48dp quick custom item action button.
- `home_purchase_button`: Concise full-width `شراء جديد` button (authorized roles only).
- `home_reports_button`: Quiet reports action text button on the owner sales figure.
- `home_catalog_button`: Single navigation-only stock alert summary row.
- `view_all_invoices_button`: "All Invoices" link in recent activity header.
- `recent_invoice_${invoice.invoiceNumber}`: Recent invoice row items (max 2-3).

### Explicitly Removed Test Tags (Documented Reductions):
- `home_expenses_button`: Removed from Home. Expenses are accessible via shell People/Management and the central `+` action.
- `home_suppliers_button`: Removed from Home. Accessible via shell People tab.
- `home_customers_button`: Removed from Home. Accessible via shell People tab.
- `restock_${product.id}`: Permanently eliminated. Prevents accidental stock adjustments from the register.
- `low_stock_item_${product.id}`: Replaced by single concise summary row `home_catalog_button`.
- `home_printer_setup_button`: Moved to shell top bar.
- `home_settings_button`: Moved to shell avatar menu.

---

## 4. Automated Tests: `MerchantHomeTest.kt`

The Robolectric Compose semantics test suite was rewritten to enforce the calm interaction contract:

1. **`homeScreen_emptyBasket_showsNewSalePrimaryAction_andNoResumeButton`:**
   - Verifies `hero_new_sale_button` is displayed and enabled.
   - Verifies `home_resume_review_button` does NOT exist.
   - Verifies 48dp tactile scanner and custom item actions are accessible.
   - Verifies clicking `hero_new_sale_button` navigates to `TraderScreen.NEW_SALE`.
2. **`homeScreen_activeBasket_showsResumeButtonAsDominantAction_andNavigatesToReview`:**
   - Seeds cart with items.
   - Verifies `home_resume_review_button` is displayed and enabled.
   - Asserts `hero_new_sale_button` does NOT exist (eliminating competing hero actions).
   - Verifies clicking `home_resume_review_button` navigates to `TraderScreen.INVOICE_REVIEW`.
3. **`homeScreen_stockSummaryRow_navigatesToCatalog_andNeverMutatesRepositoryStock`:**
   - Snapshots repository stock counts.
   - Uses `performScrollTo()` to locate `home_catalog_button`.
   - Verifies clicking stock summary navigates to `TraderScreen.PRODUCTS_CATALOG`.
   - Asserts `onRestockProduct` callback was NEVER invoked.
   - Asserts repository stock counts are strictly unmodified.
   - Verifies `restock_${product.id}` does NOT exist anywhere.
4. **`homeScreen_roleGating_ownerVsCashier_purchaseAffordanceAndFinancialPrivacy`:**
   - Owner session: verifies `home_purchase_button` and `home_reports_button` are displayed (using `performScrollTo()`).
   - Asserts obsolete `home_expenses_button` expectation is removed.
   - Cashier session: verifies `home_purchase_button` and `home_reports_button` do NOT exist. Cashier sees no owner totals.
   - Verifies `view_all_invoices_button` remains accessible for shift invoice review.
5. **`homeScreen_assertsNoMisleadingReceiveStockOrDemoBanners`:**
   - Asserts text containing `توريد بضاعة` / `receive stock` does NOT exist anywhere in the semantics tree.
   - Asserts persistent demo banners (`نسخة تجريبية — البيانات على الجهاز فقط`) do NOT exist on the merchant screen.

*Test Execution Notice: In strict accordance with task instructions ("Tests written not executed. No builds while concurrent writers; lead owns final build/test/lint"), Gradle was not executed.*

---

## 5. Remaining Device Checks & Next Steps

1. **RTL Arabic Display Verification:**
   - Verify layout direction and numeral formatting (`WesternFormatters.formatIls`) render correctly on Arabic locale devices.
2. **Dynamic Type / Font Scaling (1.5x - 2.0x):**
   - Verify that button heights expand naturally with text scaling without clipping labels.
3. **Lead Integration:**
   - Ready for lead integration with shell and sell screens on branch `ux/merchant-experience-reset`.

## Lead integration follow-up

Home resume now opens NEW_SALE via app wiring, preserving the active selection/basket rather than entering optional refinements. The active button's label and amount use stacked content for compact/large-font resilience, and the amount is net after discount. The stock alert now requests a one-shot LOW_OR_OUT inventory filter and clears only Inventory search/category, preserving Sale context. Added a navigation regression; not executed yet. Native width/font checks remain pending; source changes do not guarantee visual behavior.
