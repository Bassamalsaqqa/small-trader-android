# Small Trader (التاجر الصغير) — Surface Coverage Matrix

This document classifies every screen, workflow, and dialog in the **Small Trader / التاجر الصغير** prototype so developers working in Android Studio know exactly what is implemented with local domain logic, what is an interactive simulation, what is a shallow preview, and what is deferred to the **`Bassamalsaqqa/account`** Laravel `/api/v1` backend.

---

## Classification Legend

| Status | Meaning |
| :--- | :--- |
| **Working Demo (Repository-Backed)** | Connected navigation, real state mutations in `OfflineDemoTraderRepository`, enforced accounting/stock/role invariants, and unit-test coverage in `ExampleUnitTest.kt`. |
| **Interactive Simulation** | Interactive UI flow with realistic state transitions (e.g., simulated barcode camera sheet, simulated Bluetooth printer pairing, local draft/quote/GRN workflows) without live hardware or server persistence. |
| **Shallow Preview** | Accessible, discoverable UI surface showing truthful domain structure and sample context (e.g., sales/purchase return impact preview, warehouse transfer preview, multi-currency/cheques summary) without a fake posting engine. |
| **Deferred to Backend / Hardware** | Requires live `Bassamalsaqqa/account` Laravel `/api/v1` endpoints, Room offline sync queue, CameraX/ML Kit, or physical Bluetooth ESC/POS socket integration. |

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

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **Catalog Grid, Arabic Normalized Search & Category Filters** | **Working Demo** | `NewSaleScreen.kt`, `TraderViewModel.kt` | Normalizes Arabic hamza/alef/taa marbuta; shows cross-category match counts when a category filter hides results. |
| **Unfinished Basket Preservation Across Screens** | **Working Demo** | `TraderViewModel.kt`, `HomeScreen.kt` | Leaving `NEW_SALE` to inspect customers, invoices, or inventory preserves the active basket and surfaces a resume card on `HOME`. |
| **Custom Non-Stock Quick Item (`بند سريع مخصص`)** | **Working Demo** | `ModalSheetsAndDialogs.kt`, `TraderRepository.kt` | Modeled with `isStockTracked = false`, `costIls = 0.0`, `stockCount = 0` (no invented cost or fake stock). |
| **Sale Drafts (`حفظ واستئناف مسودة بيع`)** | **Interactive Simulation** | `InvoiceReviewScreen.kt`, `TraderViewModel.kt` | Saves active basket, customer, discount, and note into `savedDrafts` (seeded with `draft_101`), and resumes or deletes drafts without touching stock or cash drawer. |
| **Price Quotation (`عرض سعر ومشاركة`)** | **Interactive Simulation** | `InvoiceReviewScreen.kt`, `TraderViewModel.kt` | Formats an itemized quotation with explicit non-posting notice (`وثيقة غير مرحلة محاسبياً`) and clipboard copy. |
| **Invoice Review, Discounts (`5%`, `10%`, `تقريب`, `مخصص`) & Customer Assignment** | **Working Demo** | `InvoiceReviewScreen.kt` | `BigDecimal` `HALF_UP` rounding; projects customer post-sale balance and warns before credit ceiling excess. |
| **Cash Tender Entry, Quick Denominations & Credit Posting** | **Working Demo** | `CashPaymentScreen.kt`, `TraderRepository.kt` | Enforces aggregate per-SKU stock validation, canonical pricing checks, idempotency on replay, and customer credit limit gates. |
| **Simulated Barcode Scanner Sheet** | **Interactive Simulation** | `ModalSheetsAndDialogs.kt` | Supports quick product scan, random scan, flashlight toggle, and manual 4-digit suffix lookup. Live CameraX/ML Kit is deferred. |

---

## 3. Invoices, 3-State Payment Status, Receipts & Returns

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **3-State Invoice Payment Status (`نقدي مدفوع`, `مدفوع جزئياً`, `آجل / ذمم`)** | **Working Demo** | `TraderModels.kt`, `ReceiptScreen.kt` | `InvoicePaymentStatus` distinguishes `PAID_CASH` (`#INV-1042`), `PARTIAL` (`#INV-1036`: `58.00 ₪` paid / `100.00 ₪` remaining), and `ON_CREDIT` (`#INV-1034`). |
| **Invoice History Search & Status Filtering** | **Working Demo** | `ReceiptScreen.kt` (`InvoicesHistoryScreen`) | Filter chips for `الكل`, `نقدي مدفوع`, `مدفوع جزئياً`, and `آجل (ذمم)` with immediate status summary strip. |
| **Thermal Receipt View (58mm / 80mm) & Bluetooth Printer Sheet** | **Interactive Simulation** | `ReceiptScreen.kt`, `ModalSheetsAndDialogs.kt` | Previews paper width and simulates ready/offline/disconnected Bluetooth thermal printers. Live `BluetoothSocket` ESC/POS is deferred. |
| **Sales Return / Credit Note Preview (`مرتجع مبيعات / إشعار دائن`)** | **Shallow Preview** | `ReceiptScreen.kt` (`SalesReturnPreviewDialog`) | Shows truthful line-by-line stock restock impact (`+qty`) and cash refund vs customer debt reduction (`إشعار دائن`) without mutating ledger balances locally. |

---

## 4. Customers (`دفتر الدين`) & Statement Sharing

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **Customer Directory (`الكل`, `عليهم ذمم`, `لهم رصيد مقدم`)** | **Working Demo** | `CustomersScreen.kt` | Strictly separates total receivables (`totalCustomerDebtsIls = 735.00 ₪`) from customer advance deposits (`totalCustomerAdvancesIls = 50.00 ₪`). |
| **Customer Creation & Profile/Credit-Limit Editing** | **Working Demo** | `CustomersScreen.kt`, `TraderRepository.kt` | Create new customer (`AddCustomerDialog`) or edit name, phone, address, and credit ceiling (`EditCustomerDialog`) while preserving ledger balance. |
| **Customer Debt Collection (`سند قبض دفعة`)** | **Working Demo** | `CustomersScreen.kt`, `TraderRepository.kt` | Idempotent receipt voucher by `paymentId`; updates customer balance, payment ledger, and shift cash drawer collections. |
| **Customer Account Statement Share/Copy (`مشاركة كشف الحساب`)** | **Interactive Simulation** | `CustomersScreen.kt` (`CustomerStatementShareDialog`) | Generates formatted Arabic/English statement text with current balance, credit limit, and recent vouchers/invoices for clipboard/WhatsApp sharing. |

---

## 5. Products Catalog, Inventory, Batches & Weighted Goods

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **Product Creation & Catalog Definition Editing** | **Working Demo** | `ProductsCatalogScreen.kt`, `TraderRepository.kt` | Create or edit product name (AR/EN), category, barcode, selling price, cost, unit, minimum threshold, warehouse location, lot/expiry, and weighted flag (Manager/Owner only). |
| **Tracked vs Non-Stock & Weighted Goods (`صنف موزون`) Display** | **Working Demo** | `ProductsCatalogScreen.kt`, `SampleCatalog.kt` | Displays warehouse location, lot/batch code, expiry date, and weighted/fractional unit badge (`كغم موزون`) without inventing fractional posting math. |
| **Audited Stock Adjustments (`+1`, `+5`, `+10`, `-1`, `-5`, Custom)** | **Working Demo** | `ProductsCatalogScreen.kt`, `TraderRepository.kt` | Logs every adjustment with operator name, delta, and reason in `StockMovement`; blocks negative stock and non-stock service items. |
| **Multi-Warehouse Stock Count & Transfer (`جرد وتحويل مخزني`)** | **Shallow Preview** | `ProductsCatalogScreen.kt` (`StockCountTransferPreviewDialog`) | Previews main showroom vs back-room storage distribution, batch/expiry tracking, and transfer workflow deferred to `/api/v1/stock-transfers`. |

---

## 6. Suppliers, Financial Purchase Bills vs Physical Goods Receiving (GRN)

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **Supplier Directory, Creation & Editing** | **Working Demo** | `SuppliersScreen.kt`, `TraderRepository.kt` | Separates supplier payables (`totalSupplierPayablesIls`) from supplier advances (`totalSupplierAdvancesIls`); supports creating and editing suppliers. |
| **Financial Purchase Bill (`فاتورة مشتريات مالية`)** | **Working Demo** | `SuppliersScreen.kt`, `TraderRepository.kt` | Records financial liability and immediate cash paid (`paidNowIls`) without auto-mutating shelf stock. |
| **Physical Goods Receipt Note (`سند استلام بضاعة مخزني - GRN`)** | **Interactive Simulation** | `SuppliersScreen.kt` (`PhysicalGoodsReceiptDialog`), `TraderRepository.kt` | Explicitly receives physical stock against a pending purchase bill (e.g., `#BILL-303`), increments product shelf stock, and records a `StockMovement` without altering supplier financial payables. |
| **Supplier Cash Disbursement (`سند صرف للمورد`)** | **Working Demo** | `SuppliersScreen.kt`, `TraderRepository.kt` | Deducts from supplier payable balance and records shift cash disbursement. |
| **Purchase Return / Debit Note (`مرتجع مشتريات / إشعار مدين`)** | **Shallow Preview** | `SuppliersScreen.kt` (`PurchaseReturnPreviewDialog`) | Previews how returning damaged/expired goods deducts from supplier payable and warehouse stock once connected to `/api/v1/purchase-returns`. |

---

## 7. Operating Expenses, Shift Cash Reconciliation & Treasury Reports

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **Operating Expenses (`المصاريف التشغيلية`)** | **Working Demo** | `BusinessExtraScreens.kt`, `TraderRepository.kt` | Separates today's shift cash expenses (`60.00 ₪`) from historical archive expenses (`225.00 ₪`); gated by `canRecordExpenses()`. |
| **Shift Opening Float, Expected Book Cash vs Physical Count Variance & Shift Close** | **Interactive Simulation** | `BusinessExtraScreens.kt` (`FinancialReportsScreen`), `TraderViewModel.kt` | Adjusts shift opening float (`رصيد افتتاح الوردية`), computes **Expected Book Cash (`النقد المتوقع دفترياً`)**, accepts **Actual Physically Counted Cash (`الجرد الفعلي النقدي`)**, displays **Variance (`الفرق: عجز / زيادة / مطابق`)**, and previews shift closing (`إغلاق الوردية`). |
| **Treasury, Bank Accounts, Cheques & Statement Exports (`الصناديق والبنوك والشيكات`)** | **Shallow Preview** | `BusinessExtraScreens.kt` (`shallow_treasury_reports_card`) | Previews main ILS cash drawer, bank transfer account, post-dated cheques under collection, and PDF/Excel report categories mapped to `Bassamalsaqqa/account`. |

---

## 8. User Profile, Security Context & Demo Role Controls

| Surface / Capability | Classification | Location | Notes & Invariants |
| :--- | :--- | :--- | :--- |
| **Account, Branch, Token Session & MFA Preview** | **Shallow Preview** | `BusinessExtraScreens.kt` (`UserProfileScreen`, `BusinessSettingsScreen`) | Displays active branch (`الفرع الرئيسي — نابلس`), Sanctum token/MFA status preview, and role permission matrix (`المالك / المدير` vs `الكاشير`). |
| **Demo & Testing Controls (`أدوات العرض والاختبار المحلي`)** | **Working Demo** | `BusinessExtraScreens.kt` (`demo_role_switcher_card`) | Clearly separated from real profile settings; allows instant switching between **Abu Kareem (Manager)** and **Samer (Cashier)** to test permission gates. |
