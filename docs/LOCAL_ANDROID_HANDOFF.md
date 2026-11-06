# Small Trader (التاجر الصغير) — Local Android Studio Handoff Guide

This document provides the engineering handoff for continuing development of **Small Trader / التاجر الصغير** in local **Android Studio** and connecting it to the **`Bassamalsaqqa/account`** Laravel `/api/v1` backend.

---

## 1. Verified Build Environment & Reproducible Handoff Status

### Exact Gradle & Android Configuration
Verified directly against `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `gradle/wrapper/gradle-wrapper.properties`:

| Setting | Value in Project | Notes |
| :--- | :--- | :--- |
| **Gradle Wrapper** | `Gradle 9.3.1` | `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` included in root. |
| **Android Gradle Plugin (AGP)** | `9.1.1` | Configured via `libs.plugins.android.application`. |
| **Kotlin Version** | `2.2.10` | `kotlin-compose` + `kotlin-serialization` plugins enabled. |
| **`namespace`** | `"com.example"` | Matches package structure under `app/src/main/java/com/example/`. |
| **`applicationId`** | `"com.aistudio.smalltrader.psdukx"` | Configured in `defaultConfig`. |
| **`compileSdk`** | `release(36) { minorApiLevel = 1 }` | Requires Android SDK Platform 36.1 in local Android Studio. |
| **`targetSdk` / `minSdk`** | `36` / `24` | Supports Android 7.0 (API 24) through Android 16 (API 36). |
| **Java Target Compatibility** | `JavaVersion.VERSION_11` | Verified executing on Eclipse Temurin JDK `21.0.13`. |

### Environment & Git Disclosure
- **Gradle Wrapper**: Standard `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` (for Gradle 9.3.1) are present in the workspace root.
- **Git Repository Status**: The cloud build workspace (`/app/applet`) is exported as a flat source tree without a `.git` directory (`git status` is not initialized in the container). When importing into local Android Studio, run `git init && git add . && git commit -m "Initial Small Trader Android prototype handoff"` if version control is desired locally.
- **Verification Scope**:
  - **Executed & Verified in Container**: Full Android debug compilation (`compile_applet` / `:app:assembleDebug`) and all **38 JVM unit & regression tests** (`gradle :app:testDebugUnitTest`).
  - **Source-Inspected Only (Requires Local Emulator/Device)**: Interactive Compose gesture flows, system font scaling, soft-keyboard IME insets, CameraX hardware preview, and Bluetooth RFCOMM thermal socket printing.

---

## 2. Current Architecture & Module Structure

The application follows a single-activity MVVM architecture with a repository boundary (`TraderRepository`) isolating all UI screens from data persistence:

1. **UI Layer (`com.example.ui.*`)**:
   - `MainActivity.kt`: Hosts `SmallTraderApp`, `Scaffold`, edge-to-edge window insets, deterministic `BackHandler`, `SmallTraderTopBar`, `SmallTraderBottomBar`, and modal sheets/dialogs.
   - `components/`:
     - `TraderAppBars.kt`: Top bar, 4-tab root navigation bar (`الرئيسية`, `بيع جديد`, `الفواتير`, `الزبائن`), lateral Store Operations switcher (`الأصناف`, `الموردون`, `المصاريف`, `التقارير`), and Account/Settings switcher.
     - `ModalSheetsAndDialogs.kt`: `CustomItemDialog`, `SimulatedBarcodeScannerSheet`, and `BluetoothPrinterModalSheet`.
   - `screens/`:
     - `HomeScreen.kt`: Shift cash/sales pulse (Owner/Manager), unfinished basket resume card, quick POS entry bar, urgent low-stock restock list, recent invoices, and 6-tile store operations grid.
     - `NewSaleScreen.kt`: Fast POS product selection, Arabic-normalized search, category filter chips, stock badges, and sticky checkout summary bar.
     - `InvoiceReviewScreen.kt`: Line-item quantity editing, discount presets (`5%`, `10%`, `Round Down`, `Custom ILS`), customer assignment with credit-limit projection, **Sale Drafts (`حفظ/استئناف مسودة`)**, and **Quotation Preview (`عرض سعر`)**.
     - `CashPaymentScreen.kt`: Exact tender default, ILS quick denominations (`20`, `50`, `100`, `200`), numeric keypad, change calculation, and full credit (`تسجيل آجل على الذمة`) posting.
     - `ReceiptScreen.kt`: Thermal receipt preview (`58mm`/`80mm`), **3-state payment status (`Paid Cash`, `Partial`, `On Credit`)**, **Sales Return / Credit Note Preview (`مرتجع مبيعات / إشعار دائن`)**, and `InvoicesHistoryScreen` with status filters.
     - `CustomersScreen.kt`: Customer directory (`دفتر الدين`), `CustomerDetailScreen`, idempotent debt collection (`CustomerPaymentCollectionDialog`), customer creation/editing (`AddCustomerDialog`, `EditCustomerDialog`), and statement sharing (`CustomerStatementShareDialog`).
     - `ProductsCatalogScreen.kt`: Catalog search/filter, `ProductDetailScreen` with warehouse/lot/expiry/weighted metadata, audited stock adjustment controls, `ProductEditorDialog` (create/edit product), and `StockCountTransferPreviewDialog`.
     - `SuppliersScreen.kt`: Supplier directory, `SupplierDetailScreen`, supplier creation/editing (`SupplierEditorDialog`), financial purchase bill entry (`ReceivePurchaseBillDialog`), **bounded single-SKU Physical Goods Receipt Note (`PhysicalGoodsReceiptDialog` - GRN)**, and `PurchaseReturnPreviewDialog`.
     - `BusinessExtraScreens.kt`: `ExpensesScreen`, `FinancialReportsScreen` (with **Shift Opening Float, Expected Book Cash vs Physical Counted Cash Variance, and Shift Close Preview**, plus **Treasury/Banks/Cheques shallow preview**), `UserProfileScreen` (with **Session/Security preview** and clearly separated **Demo & Testing Controls**), and `BusinessSettingsScreen`.
2. **State Holder (`com.example.viewmodel.TraderViewModel`)**:
   - Exposes `uiState: StateFlow<TraderUiState>`.
   - Coordinates navigation (`navigateTo`, `navigateBack`), basket mutations, draft saving/resumption, quotation/statement text formatting, and shift cash variance calculations.
   - Delegates all authoritative domain mutations to `TraderRepository`.
3. **Domain & Data Layer (`com.example.data.TraderRepository`, `OfflineDemoTraderRepository`, `SampleCatalog`)**:
   - Enforces financial, inventory, idempotency, GRN verification, and role-permission invariants synchronously in memory.

---

## 3. Preserved Accounting & Inventory Safeguards

1. **Idempotency on Replay**:
   - `recordSale(invoice)` checks `invoices.find { it.id == invoice.id }` first and returns the existing record without double-deducting stock or double-charging the customer.
   - `recordCustomerPayment(..., paymentId)` checks `customerTransactions.find { it.id == paymentId }` so retries never double-deduct customer balances.
2. **Aggregate Stock Validation at Final Posting**:
   - `recordSale` sums requested quantities per `product.id` across all invoice lines and rejects the sale if total demand exceeds current `stockCount` (no silent clamping to zero or negative stock).
3. **Canonical Pricing & Exact Money Math**:
   - All monetary calculations use `BigDecimal` with `RoundingMode.HALF_UP` (scale 2). Caller-tampered invoice totals that do not match canonical item prices are rejected.
   - Seeded invoices (`#INV-1042`, `#INV-1041`, `#INV-1040`, `#INV-1038`, `#INV-1036`, `#INV-1034`), line-item sums, customer balances (`cust_01` through `cust_05`), and customer statement running balances (`initialCustomerTransactions`, including partial-payment `#INV-1036` for `cust_04`) are 100% reconciled.
4. **Receivables vs Advances Separation (No Netting)**:
   - Customer receivables (`totalCustomerDebtsIls`: sum of `balanceIls > 0` = `735.00 ₪`) are never netted against customer advance deposits (`totalCustomerAdvancesIls`: sum of `abs(balanceIls)` where `balanceIls < 0` = `50.00 ₪`).
   - Supplier payables (`totalSupplierPayablesIls`) are similarly kept separate from supplier advances (`totalSupplierAdvancesIls`).
5. **Financial Purchase Bill vs Bounded Single-SKU Physical Goods Receipt (GRN)**:
   - `recordPurchaseBill` records the financial payable and cash paid (`paidNowIls`) only; it **never** auto-increments product shelf stock.
   - `confirmPhysicalGoodsReceipt` enforces strict guards:
     1. Rejects unknown `billId` (`NoSuchElementException`).
     2. Rejects already-received bills (`bill.isReceived == true` → `IllegalStateException`) so stock can never be increased repeatedly for the same bill.
     3. Rejects text-only financial bills that lack linked SKU metadata (`expectedProductId == null || expectedQuantity <= 0` → `IllegalStateException`).
     4. Rejects unrelated `productId != bill.expectedProductId` (`IllegalArgumentException`).
     5. Rejects mismatched/partial `receivedQty != bill.expectedQuantity` (`IllegalArgumentException`).
6. **Expected Book Cash vs Physically Counted Cash**:
   - `expectedBookCashIls` (`drawerTotalCashIls`) = `startingDrawerCashIls + shiftCashSalesIls + shiftCustomerCollectionsIls - shiftSupplierDisbursementsIls - shiftExpensesIls`.
   - `countedPhysicalCashIls` is entered independently during shift reconciliation, yielding `shiftCashVarianceIls = countedPhysicalCashIls - expectedBookCashIls`.
7. **Development-Only vs Production Capabilities**:
   - `UserProfileScreen` isolates the one-tap role switcher (`Abu Kareem` Owner vs `Tareq` Cashier) inside a dedicated `Demo & Testing Controls` card (`demo_role_switcher_card`).
   - Local theme/language/paper-width toggles in `BusinessSettingsScreen` are client UI preferences, clearly separated from store identity fields.

---

## 4. Backend Integration Contract (`Bassamalsaqqa/account` — `routes/api-v1.php`)

### Important Architectural Boundaries
Inspection of `Bassamalsaqqa/account/routes/api-v1.php` establishes the following facts that **must** govern Android backend integration:
1. **Session-Authenticated Pipeline (Not Sanctum Bearer Tokens)**:
   - All `/api/v1` routes in `Bassamalsaqqa/account` run behind `middleware(['web', 'auth', 'auth.session', Phase11ApiGuard::class])` with outer `Phase11ApiBoundary` enforcement (TLS, JSON envelope, payload limits, feature gate).
   - Native mobile **Laravel Sanctum token authentication has NOT been approved or implemented** in `routes/api-v1.php`. Do not assume `/api/v1/auth/login` or bearer tokens exist until a backend authentication ADR approves either cookie+CSRF session binding or a dedicated mobile auth guard.
2. **Command-Based Server Execution (Offline Financial Posting Not Approved)**:
   - Sales invoices in `Bassamalsaqqa/account` follow an authoritative server-side command lifecycle (`draft` → `update` → `preview` → `execute`).
   - **Autonomous offline financial posting from the mobile client is NOT approved.** Local drafts in Android (`SaleDraft`) are non-posting convenience state and must be submitted through the server's `draft` → `preview` → `execute` workflow when online.

### A. Existing Verified Endpoints in `Bassamalsaqqa/account/routes/api-v1.php`

| Route Name | HTTP Method & Path | Controller Action | How Android Maps to This Contract |
| :--- | :--- | :--- | :--- |
| `api.v1.session.show` | `GET /api/v1/session` | `SessionApiController@show` | Inspect active authenticated user/session context (`UserProfileScreen`). |
| `api.v1.sessions.index` | `GET /api/v1/sessions` | `SessionApiController@index` | List active device sessions (`UserProfileScreen` security preview). |
| `api.v1.sessions.destroy` | `DELETE /api/v1/sessions/{opaqueHandle}` | `SessionApiController@destroy` | Revoke a device session by its `opaqueHandle`. |
| `api.v1.lookups.customers` | `GET /api/v1/companies/{companyId}/lookups/customers` | `LookupController@customers` | Read-only customer lookup for POS customer assignment and directory hydration. |
| `api.v1.lookups.products` | `GET /api/v1/companies/{companyId}/lookups/products` | `LookupController@products` | Read-only product catalog lookup for POS grid and barcode resolution. |
| `api.v1.lookups.warehouses` | `GET /api/v1/companies/{companyId}/lookups/warehouses` | `LookupController@warehouses` | Read-only warehouse lookup for product storage location metadata. |
| `api.v1.lookups.currencies` | `GET /api/v1/companies/{companyId}/lookups/currencies` | `LookupController@currencies` | Read-only currency lookup (ILS default in prototype). |
| `api.v1.lookups.taxes` | `GET /api/v1/companies/{companyId}/lookups/taxes` | `LookupController@taxes` | Read-only tax configuration lookup. |
| `api.v1.sales.invoices.draft` | `POST /api/v1/companies/{companyId}/sales/invoices/draft` | `SalesInvoiceApiController@storeDraft` | Create server-side sales invoice draft from `TraderViewModel` basket / `SaleDraft`. |
| `api.v1.sales.invoices.update` | `PUT /api/v1/companies/{companyId}/sales/invoices/{publicId}` | `SalesInvoiceApiController@updateDraft` | Update line items, customer, or discount on an existing server draft (`InvoiceReviewScreen`). |
| `api.v1.sales.invoices.show` | `GET /api/v1/companies/{companyId}/sales/invoices/{publicId}` | `SalesInvoiceApiController@show` | Fetch authoritative invoice details (`ReceiptScreen`). |
| `api.v1.sales.invoices.preview` | `POST /api/v1/companies/{companyId}/sales/invoices/{publicId}/preview` | `SalesInvoiceApiController@preview` | Server-side financial/tax/stock validation preview prior to final execution. |
| `api.v1.sales.invoices.execute` | `POST /api/v1/companies/{companyId}/sales/invoices/{publicId}/execute` | `SalesInvoiceApiController@execute` | Execute and post the validated sales invoice (`CashPaymentScreen` completion). |
| `api.v1.assistant.turn` | `POST /api/v1/companies/{companyId}/assistant/turn` | `AssistantTurnController` | Exists in backend (`routes/api-v1.php`), **intentionally unused** by the Small Trader Android POS app. |

### B. Future Backend Contracts Required (Not Yet in `routes/api-v1.php`)

The following prototype capabilities work against `OfflineDemoTraderRepository` today and require **future backend API endpoints** before they can be wired to `Bassamalsaqqa/account`:

| Prototype Feature in Android | Current Android Status | Future Backend Requirement |
| :--- | :--- | :--- |
| **Mobile Login / Session Bootstrap** | Demo role switcher (`SampleCatalog.demoStaff`) | Decide between web-session login + CSRF cookie jar vs dedicated mobile auth under `Phase11ApiGuard`. |
| **Sales Quotations & Sales Returns** | Local simulation (`InvoiceReviewScreen`) & shallow preview (`ReceiptScreen`) | Requires future `/companies/{companyId}/sales/quotations` and `/sales/returns` command endpoints. |
| **Customer Creation, Editing & Receipt Vouchers (`دفتر الدين`)** | Working local demo (`recordCustomerPayment`, `addCustomer`, `updateCustomer`) | Currently only `GET .../lookups/customers` exists; requires customer write endpoints and receipt voucher command endpoints (`draft`/`preview`/`execute`). |
| **Product Creation, Editing & Stock Adjustments** | Working local demo (`saveProduct`, `adjustProductStock`) & transfer preview | Currently only `GET .../lookups/products` and `GET .../lookups/warehouses` exist; requires catalog mutation and inventory adjustment/transfer endpoints. |
| **Suppliers, Purchase Bills, GRN & Disbursements** | Working local demo (`recordPurchaseBill`, `paySupplier`, bounded `confirmPhysicalGoodsReceipt`) | Requires supplier lookup/write endpoints, purchase bill commands, warehouse GRN commands, and supplier payment voucher endpoints. |
| **Operating Expenses & Shift Z-Report / Treasury** | Working local demo (`recordExpense`) & interactive shift variance simulation | Requires expense voucher endpoints and shift open/close reconciliation endpoints. |

---

## 5. Hardware Integration Boundaries

1. **CameraX + ML Kit Barcode Scanning**:
   - Currently simulated in `SimulatedBarcodeScannerSheet` (`ui/components/ModalSheetsAndDialogs.kt`) via `TraderViewModel.simulateBarcodeScan` and `submitManualBarcode`.
   - **Local Next Step**: Add `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view`, and `com.google.mlkit:barcode-scanning`, request `Manifest.permission.CAMERA` at runtime, and feed detected EAN-13 / Code-128 strings directly into `updateManualBarcodeInput(rawValue)` + `submitManualBarcode()`.
2. **Bluetooth ESC/POS Thermal Printing (58mm / 80mm)**:
   - Currently simulated in `BluetoothPrinterModalSheet` (`ui/components/ModalSheetsAndDialogs.kt`) and `TraderViewModel.simulateThermalPrint`.
   - **Local Next Step**: Request `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (API 31+), open an RFCOMM `BluetoothSocket` (`00001101-0000-1000-8000-00805F9B34FB`), and rasterize the Arabic receipt layout (`ReceiptScreen.kt`) according to `receiptPaperWidthMm` (384 dots for 58mm, 576 dots for 80mm).

---

## 6. Verification & Test Evidence

All **38 JVM unit & regression tests** in `app/src/test/java/com/example/ExampleUnitTest.kt` execute and pass via `./gradlew :app:testDebugUnitTest`:
- Idempotent sale & customer payment replay handling (Tests 1, 2, 17)
- Stale basket & aggregate per-SKU stock revalidation at final posting (Tests 2, 13, 14)
- Canonical pricing, `BigDecimal` `HALF_UP` discount rounding, and Western numeral sanitization (Tests 4, 5, 15, 16)
- Customer credit limits, receivables vs advances separation, and walk-in credit rejection (Tests 7, 8, 20, 24, 25, 27)
- Cashier vs Owner/Manager permission boundaries across inventory, suppliers, expenses, and reports (Tests 10, 19, 22, 28, 32)
- Navigation back-stack determinism and unfinished basket preservation (Tests 21, 22)
- Interactive demo completion & regression flows:
  - Product creation/editing & warehouse/lot/expiry/weighted metadata (`Test 32`)
  - Sale draft save/resume/delete & quotation formatting without stock/cash mutation (`Test 33`)
  - 3-state invoice payment status: `PAID_CASH`, `PARTIAL`, `ON_CREDIT` (`Test 34`)
  - Customer/supplier editing & bounded single-SKU physical GRN (`confirmPhysicalGoodsReceipt`) vs financial purchase bill separation (`Test 35`)
  - Shift opening float, expected book cash vs physically counted cash variance, and shift close preview (`Test 36`)
  - Physical GRN safety guards: rejecting unknown bill ID, unrelated product ID, mismatched quantity, text-only financial bills, and repeated GRN calls on an already-received bill (`Test 37`)
  - Sample catalog 100% financial consistency across invoice line items (`#INV-1038`, `#INV-1036`), customer balances (`cust_04 = 460.00 ₪`), and customer statement running balances (`Test 38`)
