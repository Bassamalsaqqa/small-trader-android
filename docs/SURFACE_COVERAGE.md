# Small Trader Android — Surface Coverage Matrix

Truthful classification of Android surfaces distinguishing **Live Backend-Wired** (gated behind default-OFF flags), **Debug-Demo Only** (`OfflineDemoTraderRepository`), **Interactive Simulation / Shallow Preview**, and **Blocked / Future Proposal**.

## 1. Shell, Auth & Offline Gate Surfaces

| Surface | Classification | Notes & Authority |
|---|---|---|
| `StaffSignInScreen`, `StaffMfaScreen`, `StaffCompanySelectScreen` | **Live-Wired (Flag-Gated)** | Uses `/api/mobile/v1/auth/*` + `SecureStaffSessionStore` (`noBackupFilesDir`). Defaults OFF via `AppLaunchTargetResolver`. |
| `StaffWorkspaceScreen` & `StaffOutboxRecoveryScreen` | **Live-Wired (Flag-Gated)** | Read-only catalog/customer workspace (`SmallTraderReadClient`) + scoped outbox health gate (`OutboxHealthInspector`). |
| `RestrictedOfflineScreen` | **Live-Wired (Pilot Flag OFF)** | Active only with `OFFLINE_OWNER_PILOT=true` + acquired V2 server lease (`BOOT_COUNT` bound). Labels pending sales provisional. |
| `Demo Role Switcher` (`UserProfileScreen`) | **Debug-Demo Only** | Isolated to debug demo sessions; unavailable in production/staging launch targets. |

## 2. Core Merchant Commerce Surfaces (`AuthenticatedTraderApp` / `screens/`)

| Surface | Production (`ProductionTraderRepository`) | Debug Demo (`OfflineDemoTraderRepository`) | Notes |
|---|---|---|---|
| **Home (`HomeScreen`)** | **Live-Wired** (`GET .../home`) | Working In-Memory Demo | Role-gated pulse, resume basket, low-stock navigation. |
| **Catalog & Search (`NewSaleScreen`, `ProductsCatalogScreen`)** | **Live-Wired** (`GET/POST/PUT .../products`, `/inventory/adjustments`) | Working In-Memory Demo | Arabic-normalized search (`ProductSearch`); stock transfer is shallow preview (`transfers/preview`). |
| **Sale Review & Discounts (`InvoiceReviewScreen`)** | **Live-Wired (Discounts Blocked)** | Working In-Memory Demo | Nonzero discounts hidden/rejected in production; restored nonzero-discount drafts show `RestoredUnsupportedDiscountCard`. **Mobile document discount is a FUTURE PROPOSAL (not implemented).** |
| **Cash / Credit Checkout (`CashPaymentScreen`, `ReceiptScreen`)** | **Live-Wired** (V2 `/sales/online-cash`, V1 `/sales/invoices*`) | Working In-Memory Demo | Production supports V2 cash / V1 invoice paths; offline capture held OFF. Provisional offline receipts carry `LOCAL-n` until server ACK. |
| **Customers & Debt Ledger (`CustomersScreen`, `PeopleHubScreen`)** | **Live-Wired** (V1 `/customers*`, `/settlement-targets`, `/payments`) | Working In-Memory Demo | Separates AR receivables vs advances; explicit allocation targets; statement share is clipboard/text export. |
| **Suppliers, Purchases & GRN (`SuppliersScreen`, `PurchasesScreen`)** | **Partial / Deferred Parity** | Working Demo + Bounded Single-SKU GRN | V1 `/suppliers*`, `/settlement-targets`, `/payments`, `/purchases*` routes exist; demo separate bill-vs-GRN flow differs from backend ADR-0013 atomic purchasing (full native alignment deferred). |
| **Expenses (`BusinessExtraScreens`)** | **Live-Wired** (V1 `/expenses*`) | Working In-Memory Demo | Role-gated (`money.expense.manage` / `canRecordExpenses`). |
| **Shift Cash Close (`ShiftCloseDialog`)** | **BLOCKED (Held)** | Interactive Simulation | Do not release against current observation contract; see [Shift Count Blocker](SHIFT_COUNT_CONTRACT_BLOCKER.md). |
| **Barcode Scanner & 58/80mm Printer** | **Interactive Simulation** | Interactive Simulation | CameraX/ML Kit & Bluetooth `BluetoothSocket` ESC/POS deferred pending physical device hardware qualification (KVM blocked). |
| **Sales/Purchase Returns & Multi-Currency Treasury** | **Shallow Preview / Partial** | Shallow Preview | Return preview/store routes exist on V1 backend; full Android merchant workflows & physical device checks remain deferred. |
