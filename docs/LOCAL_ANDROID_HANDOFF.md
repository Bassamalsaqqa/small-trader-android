# Small Trader (التاجر الصغير) — Local Android Studio Handoff Guide

This document provides the engineering handoff for continuing development of **Small Trader / التاجر الصغير** in local **Android Studio** and connecting it to the **`Bassamalsaqqa/account`** Laravel `/api/v1` backend.

---

## 1. Current Architecture & Module Structure

The application follows a clean, single-activity MVVM architecture with a repository boundary designed to swap the in-memory demo store for Retrofit/Ktor + Room without rewriting Jetpack Compose screens:

1. **UI Layer (`com.example.ui.*`)**:
   - `MainActivity.kt`: Hosts `SmallTraderApp`, `Scaffold`, edge-to-edge window insets, deterministic `BackHandler`, `SmallTraderTopBar`, `SmallTraderBottomBar`, and modal sheets/dialogs.
   - `screens/`:
     - `HomeScreen.kt`: Shift cash/sales pulse (Owner/Manager), unfinished basket resume card, quick POS entry bar, urgent low-stock restock list, recent invoices, and 6-tile store operations grid.
     - `NewSaleScreen.kt`: Fast POS product selection, Arabic-normalized search, category filter chips, stock badges, and sticky checkout summary bar.
     - `InvoiceReviewScreen.kt`: Line-item quantity editing, discount presets (`5%`, `10%`, `Round Down`, `Custom ILS`), customer assignment with credit-limit projection, **Sale Drafts (`حفظ/استئناف مسودة`)**, and **Quotation Preview (`عرض سعر`)**.
     - `CashPaymentScreen.kt`: Exact tender default, ILS quick denominations (`20`, `50`, `100`, `200`), numeric keypad, change calculation, and full credit (`تسجيل آجل على الذمة`) posting.
     - `ReceiptScreen.kt`: Thermal receipt preview (`58mm`/`80mm`), **3-state payment status (`Paid Cash`, `Partial`, `On Credit`)**, **Sales Return / Credit Note Preview (`مرتجع مبيعات / إشعار دائن`)**, and `InvoicesHistoryScreen` with status filters.
     - `CustomersScreen.kt`: Customer directory (`دفتر الدين`), `CustomerDetailScreen`, idempotent debt collection (`CustomerPaymentCollectionDialog`), customer creation/editing (`AddCustomerDialog`, `EditCustomerDialog`), and statement sharing (`CustomerStatementShareDialog`).
     - `ProductsCatalogScreen.kt`: Catalog search/filter, `ProductDetailScreen` with warehouse/lot/expiry/weighted metadata, audited stock adjustment controls, `ProductEditorDialog` (create/edit product), and `StockCountTransferPreviewDialog`.
     - `SuppliersScreen.kt`: Supplier directory, `SupplierDetailScreen`, supplier creation/editing (`SupplierEditorDialog`), financial purchase bill entry (`ReceivePurchaseBillDialog`), **Physical Goods Receipt Note (`PhysicalGoodsReceiptDialog` - GRN)**, and `PurchaseReturnPreviewDialog`.
     - `BusinessExtraScreens.kt`: `ExpensesScreen`, `FinancialReportsScreen` (with **Shift Opening Float, Expected Book Cash vs Physical Counted Cash Variance, and Shift Close Preview**, plus **Treasury/Banks/Cheques shallow preview**), `UserProfileScreen` (with **Security/MFA preview** and clearly separated **Demo & Testing Controls**), and `BusinessSettingsScreen`.
2. **State Holder (`com.example.viewmodel.TraderViewModel`)**:
   - Exposes `uiState: StateFlow<TraderUiState>`.
   - Coordinates navigation (`navigateTo`, `navigateBack`), basket mutations, draft saving/resumption, quotation/statement text formatting, and shift cash variance calculations.
   - Delegates all authoritative domain mutations to `TraderRepository`.
3. **Domain & Data Layer (`com.example.data.TraderRepository`, `OfflineDemoTraderRepository`, `SampleCatalog`)**:
   - Enforces financial, inventory, idempotency, and role-permission invariants synchronously in memory.

---

## 2. Preserved Accounting & Financial Safeguards

When migrating `OfflineDemoTraderRepository` to Room + Retrofit (`Bassamalsaqqa/account`), preserve these domain invariants verified in `ExampleUnitTest.kt`:

1. **Idempotency on Replay**:
   - `recordSale(invoice)` checks `invoices.find { it.id == invoice.id }` first and returns the existing record without double-deducting stock or double-charging the customer.
   - `recordCustomerPayment(..., paymentId)` checks `customerPayments.find { it.id == paymentId }` so network retries never double-deduct customer balances.
2. **Aggregate Stock Validation at Final Posting**:
   - `recordSale` sums requested quantities per `product.id` across all invoice lines and rejects the sale if total demand exceeds current `stockCount` (no silent clamping to zero or negative stock).
3. **Canonical Pricing & Exact Money Math**:
   - All monetary calculations use `BigDecimal` with `RoundingMode.HALF_UP` (scale 2). Caller-tampered invoice totals that do not match canonical item prices are rejected.
4. **Receivables vs Advances Separation (No Netting)**:
   - Customer receivables (`totalCustomerDebtsIls`: sum of `balanceIls > 0`) are never netted against customer advance deposits (`totalCustomerAdvancesIls`: sum of `abs(balanceIls)` where `balanceIls < 0`).
   - Supplier payables (`totalSupplierPayablesIls`) are similarly kept separate from supplier advances (`totalSupplierAdvancesIls`).
5. **Financial Purchase Bill vs Physical Goods Receipt (GRN)**:
   - `recordPurchaseBill` records the financial payable and cash paid (`paidNowIls`) only; it **never** auto-increments product shelf stock.
   - `confirmPhysicalGoodsReceipt` marks a purchase bill received and records an explicit `StockMovement` to increment shelf stock without altering supplier financial payables.
6. **Expected Book Cash vs Physically Counted Cash**:
   - `expectedBookCashIls` (`drawerTotalCashIls`) = `startingDrawerCashIls + shiftCashSalesIls + shiftCustomerCollectionsIls - shiftSupplierDisbursementsIls - shiftExpensesIls`.
   - `countedPhysicalCashIls` is entered independently by the cashier/manager during shift reconciliation, yielding `shiftCashVarianceIls = countedPhysicalCashIls - expectedBookCashIls`.
7. **Role Permission Gates**:
   - `UserRole.CASHIER` can operate POS, drafts, quotations, customer debt collection, and customer creation/editing, but is blocked at both UI and repository levels from mutating catalog prices/stock, recording expenses, managing suppliers, or viewing owner financial reports.

---

## 3. Backend Integration Boundaries (`Bassamalsaqqa/account` Laravel `/api/v1`)

To connect the app to the `Bassamalsaqqa/account` Laravel backend:

| App Repository Method / Flow | Target Laravel `/api/v1` Endpoint | Integration Notes |
| :--- | :--- | :--- |
| Session Auth & Role Context | `POST /api/v1/auth/login`, `GET /api/v1/auth/me` | Replace `SampleCatalog.demoStaff` switcher in `UserProfileScreen` with Sanctum bearer token + EncryptedSharedPreferences/DataStore. |
| Product Catalog & Batches | `GET /api/v1/products`, `POST /api/v1/products`, `PUT /api/v1/products/{id}` | Map `warehouseAr`/`warehouseEn`, `lotBatchCode`, `expiryDate`, and `isWeightedDisplayOnly` to product SKU attributes. |
| Stock Adjustments & GRN | `POST /api/v1/inventory/adjustments`, `POST /api/v1/purchases/{id}/receive` | Map `adjustProductStock` and `confirmPhysicalGoodsReceipt` with `idempotency_key`. |
| POS Sales, Drafts & Quotes | `POST /api/v1/sales/invoices`, `POST /api/v1/sales/quotations` | Pass `invoice.id` as client UUID `idempotency_key`; map `InvoicePaymentStatus` (`PAID_CASH`, `PARTIAL`, `ON_CREDIT`). |
| Sales & Purchase Returns | `POST /api/v1/sales/returns`, `POST /api/v1/purchases/returns` | Wire `SalesReturnPreviewDialog` and `PurchaseReturnPreviewDialog` to post credit/debit notes. |
| Customers & Receipt Vouchers | `GET/POST/PUT /api/v1/customers`, `POST /api/v1/vouchers/receipts` | Pass `paymentId` as `idempotency_key` on receipt vouchers. |
| Suppliers, Bills & Disbursements | `GET/POST/PUT /api/v1/suppliers`, `POST /api/v1/purchases/bills`, `POST /api/v1/vouchers/payments` | Keep financial bill posting (`bills`) separate from warehouse receiving (`receive`). |
| Shift Z-Report & Treasury | `POST /api/v1/shifts/open`, `POST /api/v1/shifts/close`, `GET /api/v1/reports/treasury` | Persist `startingDrawerCashIls`, `expectedBookCashIls`, `countedPhysicalCashIls`, and `shiftCashVarianceIls`. |

---

## 4. Hardware Integration Boundaries

1. **CameraX + ML Kit Barcode Scanning**:
   - Currently simulated in `SimulatedBarcodeScannerSheet` (`ui/components/ModalSheetsAndDialogs.kt`) via `TraderViewModel.simulateBarcodeScan` and `submitManualBarcode`.
   - **Local Next Step**: Add `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view`, and `com.google.mlkit:barcode-scanning`, request `Manifest.permission.CAMERA` at runtime, and feed detected EAN-13 / Code-128 strings directly into `updateManualBarcodeInput(rawValue)` + `submitManualBarcode()`.
2. **Bluetooth ESC/POS Thermal Printing (58mm / 80mm)**:
   - Currently simulated in `BluetoothPrinterModalSheet` (`ui/components/ModalSheetsAndDialogs.kt`) and `TraderViewModel.simulateThermalPrint`.
   - **Local Next Step**: Request `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (API 31+), open an RFCOMM `BluetoothSocket` (`00001101-0000-1000-8000-00805F9B34FB`), and rasterize the Arabic receipt layout (`ReceiptScreen.kt`) according to `receiptPaperWidthMm` (384 dots for 58mm, 576 dots for 80mm).

---

## 5. Verification & Test Evidence

All **36 JVM unit tests** in `app/src/test/java/com/example/ExampleUnitTest.kt` validate:
- Idempotent sale & customer payment replay handling (Tests 1, 2, 17)
- Stale basket & aggregate per-SKU stock revalidation at final posting (Tests 2, 13, 14)
- Canonical pricing, `BigDecimal` `HALF_UP` discount rounding, and Western numeral sanitization (Tests 4, 5, 15, 16)
- Customer credit limits, receivables vs advances separation, and walk-in credit rejection (Tests 7, 8, 20, 24, 25, 27)
- Cashier vs Owner/Manager permission boundaries across inventory, suppliers, expenses, and reports (Tests 10, 19, 22, 28, 32)
- Navigation back-stack determinism and unfinished basket preservation (Tests 21, 22)
- Interactive demo completion flows:
  - Product creation/editing & warehouse/lot/expiry/weighted metadata (`Test 32`)
  - Sale draft save/resume/delete & quotation formatting without stock/cash mutation (`Test 33`)
  - 3-state invoice payment status: `PAID_CASH`, `PARTIAL`, `ON_CREDIT` (`Test 34`)
  - Customer/supplier editing & physical GRN (`confirmPhysicalGoodsReceipt`) vs financial purchase bill separation (`Test 35`)
  - Shift opening float, expected book cash vs physically counted cash variance, and shift close preview (`Test 36`)

---

## 6. Recommended Local Cleanup & Next Priorities

1. **Introduce Room Offline Cache & Sync Queue**: Persist `Product`, `Customer`, `InvoiceRecord`, `SaleDraft`, and `StockMovement` locally so offline transactions survive app process restarts before syncing to `/api/v1`.
2. **Enable Partial Cash + Credit Split at POS Checkout**: `InvoiceRecord` and `ReceiptScreen` already support `InvoicePaymentStatus.PARTIAL` (`#INV-1036`). When backend rules are finalized, add a toggle on `CashPaymentScreen` allowing a registered customer to tender partial cash and post the remainder (`remainingUnpaidIls`) to their credit ledger in one step.
3. **Gate `Demo & Testing Controls` by `BuildConfig.DEBUG`**: Keep the one-tap Manager/Cashier role switcher in `UserProfileScreen` (`demo_role_switcher_card`) visible only in debug/staging builds once real Sanctum authentication is wired.
