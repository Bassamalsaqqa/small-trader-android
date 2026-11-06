# Small Trader (التاجر الصغير) — Surface Coverage Matrix

This document classifies every screen, workflow, and dialog in the **Small Trader / التاجر الصغير** prototype so developers working in Android Studio know exactly what is implemented with local domain logic, what is an interactive simulation, what is a shallow preview, and what connects to existing vs. future **`Bassamalsaqqa/account`** (`routes/api-v1.php`) backend contracts.

---

## Classification Legend

| Status | Meaning |
| :--- | :--- |
| **Working Demo (Repository-Backed)** | Connected navigation, real state mutations in `OfflineDemoTraderRepository`, enforced accounting/stock/role invariants, and unit-test coverage in `ExampleUnitTest.kt`. |
| **Interactive Simulation** | Bounded interactive UI flow with realistic state transitions (e.g., simulated barcode camera sheet, simulated Bluetooth printer pairing, local draft/quote/single-SKU GRN workflows) without live hardware or server persistence. |
| **Shallow Preview** | Accessible, discoverable UI surface showing truthful domain structure and sample context (e.g., sales/purchase return impact preview, warehouse transfer preview, multi-currency/cheques summary) without a fake posting engine. |
| **Existing `/api/v1` Contract vs Future Backend Requirement** | Distinguishes capabilities that map to routes currently defined in `Bassamalsaqqa/account/routes/api-v1.php` from capabilities that require future backend endpoints. |

---

## 1. App Shell, Navigation & Localization

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **4-Tab Root Navigation (`الرئيسية`, `بيع جديد`, `الفواتير`, `الزبائن`)** | **Working Demo** | `MainActivity.kt`, `TraderAppBars.kt` | Root tab switching avoids deep back-stack loops; Back from any secondary tab returns to `HOME`. |
| **Store Operations Switcher (`الأصناف`, `الموردون`, `المصاريف`, `التقارير`)** | **Working Demo** | `TraderAppBars.kt`, `HomeScreen.kt` | Lateral switching between store modules keeps `HOME` as the single parent on the back stack. |
| **Bilingual RTL Arabic / LTR English + Western Digits (`0-9`)** | **Working Demo** | `WesternFormatters`, `SmallTraderTheme` | Instant AR/EN toggle; all monetary figures, quantities, and dates strictly render Western digits (`0-9`). |
| **Light & Dark Financial Themes** | **Working Demo** | `Theme.kt`, `Color.kt`, `Type.kt` | Graded slate dark mode (`CanvasBackgroundDark` < `CardSurfaceDark` < `SurfaceVariantDark`); warm vermilion debit token. |

---

## 2. Point of Sale (POS), Drafts, Quotations & Checkout

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **Catalog Grid, Arabic Normalized Search & Category Filters** | **Working Demo** | `NewSaleScreen.kt`, `TraderViewModel.kt` | **Existing Lookup**: `GET /api/v1/companies/{companyId}/lookups/products`. |
| **Unfinished Basket Preservation Across Screens** | **Working Demo** | `TraderViewModel.kt`, `HomeScreen.kt` | Client UI state; preserved across tab/module switches. |
| **Custom Non-Stock Quick Item (`بند سريع مخصص`)** | **Working Demo** | `ModalSheetsAndDialogs.kt`, `TraderRepository.kt` | Modeled with `isStockTracked = false`, `costIls = 0.0`, `stockCount = 0` (no invented cost or fake stock). |
| **Sale Drafts (`حفظ واستئناف مسودة بيع`)** | **Interactive Simulation** | `InvoiceReviewScreen.kt`, `TraderViewModel.kt` | **Existing Draft Routes**: `POST .../sales/invoices/draft` (`storeDraft`) & `PUT .../sales/invoices/{publicId}` (`updateDraft`). Saves/resumes without touching stock or cash drawer. |
| **Price Quotation (`عرض سعر ومشاركة`)** | **Interactive Simulation** | `InvoiceReviewScreen.kt`, `TraderViewModel.kt` | Formats an itemized quotation with explicit non-posting notice (`وثيقة غير مرحلة محاسبياً`) and clipboard copy. **Future backend endpoint** if server-persisted quotes are needed. |
| **Invoice Review, Discounts (`5%`, `10%`, `تقريب`, `مخصص`) & Customer Assignment** | **Working Demo** | `InvoiceReviewScreen.kt` | **Existing Routes**: `GET .../lookups/customers` & `POST .../sales/invoices/{publicId}/preview`. |
| **Cash Tender Entry, Quick Denominations & Credit Posting** | **Working Demo** | `CashPaymentScreen.kt`, `TraderRepository.kt` | **Existing Execute Route**: `POST .../sales/invoices/{publicId}/execute`. Offline financial posting is **not** approved. |
| **Simulated Barcode Scanner Sheet** | **Interactive Simulation** | `ModalSheetsAndDialogs.kt` | Supports quick product scan, random scan, flashlight toggle, and manual 4-digit suffix lookup. Live CameraX/ML Kit is deferred to local hardware integration. |

---

## 3. Invoices, 3-State Payment Status, Receipts & Returns

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **3-State Invoice Payment Status (`نقدي مدفوع`, `مدفوع جزئياً`, `آجل / ذمم`)** | **Working Demo** | `TraderModels.kt`, `ReceiptScreen.kt` | **Existing Show Route**: `GET /api/v1/companies/{companyId}/sales/invoices/{publicId}`. Distinguishes `PAID_CASH` (`#INV-1042`), `PARTIAL` (`#INV-1036`: `58.00 ₪` paid / `100.00 ₪` remaining), and `ON_CREDIT` (`#INV-1034`). |
| **Invoice History Search & Status Filtering** | **Working Demo** | `ReceiptScreen.kt` (`InvoicesHistoryScreen`) | Filter chips for `الكل`, `نقدي مدفوع`, `مدفوع جزئياً`, and `آجل (ذمم)`. |
| **Thermal Receipt View (58mm / 80mm) & Bluetooth Printer Sheet** | **Interactive Simulation** | `ReceiptScreen.kt`, `ModalSheetsAndDialogs.kt` | Previews paper width and simulates ready/offline/disconnected Bluetooth thermal printers. Live `BluetoothSocket` ESC/POS is deferred. |
| **Sales Return / Credit Note Preview (`مرتجع مبيعات / إشعار دائن`)** | **Shallow Preview** | `ReceiptScreen.kt` (`SalesReturnPreviewDialog`) | Shows line-by-line stock restock impact (`+qty`) and cash refund vs customer debt reduction (`إشعار دائن`) without mutating ledger balances locally. **Future backend endpoint required.** |

---

## 4. Customers (`دفتر الدين`) & Statement Sharing

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **Customer Directory (`الكل`, `عليهم ذمم`, `لهم رصيد مقدم`)** | **Working Demo** | `CustomersScreen.kt` | **Existing Read Lookup**: `GET /api/v1/companies/{companyId}/lookups/customers`. Strictly separates receivables (`735.00 ₪`) from customer advances (`50.00 ₪`). |
| **Customer Creation & Profile/Credit-Limit Editing** | **Working Demo** | `CustomersScreen.kt`, `TraderRepository.kt` | Local repository demo (`AddCustomerDialog`, `EditCustomerDialog`). **Future backend write endpoint required.** |
| **Customer Debt Collection (`سند قبض دفعة`)** | **Working Demo** | `CustomersScreen.kt`, `TraderRepository.kt` | Idempotent receipt voucher by `paymentId`; updates customer balance, transaction history, and shift cash drawer collections. **Future backend receipt voucher endpoint required.** |
| **Customer Account Statement Share/Copy (`مشاركة كشف الحساب`)** | **Interactive Simulation** | `CustomersScreen.kt` (`CustomerStatementShareDialog`) | Generates formatted Arabic/English statement text with reconciled running balances (including partial invoice `#INV-1036` for `cust_04`). |

---

## 5. Products Catalog, Inventory, Batches & Weighted Goods

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **Product Creation & Catalog Definition Editing** | **Working Demo** | `ProductsCatalogScreen.kt`, `TraderRepository.kt` | **Existing Read Lookups**: `GET .../lookups/products`, `GET .../lookups/warehouses`, `GET .../lookups/taxes`. **Future backend write endpoints required** for creating/updating SKUs. |
| **Tracked vs Non-Stock & Weighted Goods (`صنف موزون`) Display** | **Working Demo** | `ProductsCatalogScreen.kt`, `SampleCatalog.kt` | Displays warehouse location, lot/batch code, expiry date, and weighted/fractional unit badge (`كغم موزون`) without inventing fractional posting math. |
| **Audited Stock Adjustments (`+1`, `+5`, `+10`, `-1`, `-5`, Custom)** | **Working Demo** | `ProductsCatalogScreen.kt`, `TraderRepository.kt` | Logs every adjustment with operator name, delta, and reason in `StockMovement`; blocks negative stock and non-stock service items. **Future backend inventory adjustment endpoint required.** |
| **Multi-Warehouse Stock Count & Transfer (`جرد وتحويل مخزني`)** | **Shallow Preview** | `ProductsCatalogScreen.kt` (`StockCountTransferPreviewDialog`) | Previews main showroom vs back-room storage distribution and batch/expiry tracking. **Future backend stock-transfer endpoint required.** |

---

## 6. Suppliers, Financial Purchase Bills vs Physical Goods Receiving (GRN)

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **Supplier Directory, Creation & Editing** | **Working Demo** | `SuppliersScreen.kt`, `TraderRepository.kt` | Separates supplier payables (`totalSupplierPayablesIls`) from supplier advances (`totalSupplierAdvancesIls`). **Future backend supplier endpoints required.** |
| **Financial Purchase Bill (`فاتورة مشتريات مالية`)** | **Working Demo** | `SuppliersScreen.kt`, `TraderRepository.kt` | Records financial liability and immediate cash paid (`paidNowIls`) without auto-mutating shelf stock. **Future backend purchase bill endpoint required.** |
| **Bounded Single-SKU Physical Goods Receipt Note (`سند استلام بضاعة مخزني - GRN`)** | **Interactive Simulation (Bounded Single-SKU)** | `SuppliersScreen.kt` (`PhysicalGoodsReceiptDialog`), `TraderRepository.kt` | Strictly validates that the purchase bill exists, is not already received, has a linked `expectedProductId` and `expectedQuantity` (e.g., `#BILL-0303`: 12 sacks of `p_rice_medium`), and increments stock **once** without altering supplier payables. Text-only financial bills without SKU metadata are blocked from automated GRN. **Future backend GRN endpoint required.** |
| **Supplier Cash Disbursement (`سند صرف للمورد`)** | **Working Demo** | `SuppliersScreen.kt`, `TraderRepository.kt` | Deducts from supplier payable balance and records shift cash disbursement. **Future backend payment voucher endpoint required.** |
| **Purchase Return / Debit Note (`مرتجع مشتريات / إشعار مدين`)** | **Shallow Preview** | `SuppliersScreen.kt` (`PurchaseReturnPreviewDialog`) | Previews how returning damaged/expired goods deducts from supplier payable and warehouse stock. **Future backend purchase-return endpoint required.** |

---

## 7. Operating Expenses, Shift Cash Reconciliation & Treasury Reports

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **Operating Expenses (`المصاريف التشغيلية`)** | **Working Demo** | `BusinessExtraScreens.kt`, `TraderRepository.kt` | Separates today's shift cash expenses (`60.00 ₪`) from historical archive expenses (`225.00 ₪`); gated by `canRecordExpenses()`. **Future backend expense endpoint required.** |
| **Shift Opening Float, Expected Book Cash vs Physical Count Variance & Shift Close** | **Interactive Simulation** | `BusinessExtraScreens.kt` (`FinancialReportsScreen`), `TraderViewModel.kt` | Adjusts shift opening float (`رصيد افتتاح الوردية`), computes **Expected Book Cash (`النقد المتوقع دفترياً`)**, accepts **Actual Physically Counted Cash (`الجرد الفعلي النقدي`)**, displays **Variance (`الفرق: عجز / زيادة / مطابق`)**, and previews shift closing. **Future backend shift Z-report endpoint required.** |
| **Treasury, Bank Accounts, Cheques & Statement Exports (`الصناديق والبنوك والشيكات`)** | **Shallow Preview** | `BusinessExtraScreens.kt` (`shallow_treasury_reports_card`) | Previews main ILS cash drawer, bank transfer account, post-dated cheques under collection, and report categories (`GET .../lookups/currencies` exists for currency lookup; treasury reporting requires future backend endpoints). |

---

## 8. User Profile, Session Security Context & Development-Only Role Controls

| Surface / Capability | Classification | Location | Backend Contract Status (`routes/api-v1.php`) |
| :--- | :--- | :--- | :--- |
| **Account, Branch & Active Device Sessions Preview** | **Shallow Preview** | `BusinessExtraScreens.kt` (`UserProfileScreen`, `BusinessSettingsScreen`) | **Existing Session Routes**: `GET /api/v1/session`, `GET /api/v1/sessions`, `DELETE /api/v1/sessions/{opaqueHandle}` (authenticated via `web`, `auth`, `auth.session`, `Phase11ApiGuard`). |
| **Demo & Testing Controls (`أدوات العرض والاختبار المحلي`)** | **Development-Only Demo Control** | `BusinessExtraScreens.kt` (`demo_role_switcher_card`) | Explicitly separated from production profile settings; allows switching between **Abu Kareem (Owner)**, **Omar (Manager)**, and **Tareq (Cashier)** to test role permission gates locally. |
