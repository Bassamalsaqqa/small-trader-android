# التاجر الصغير · Small Trader (Android POS & Merchant Ledger)

**التاجر الصغير (Small Trader)** is an Arabic-first (RTL) / English (LTR) native Android point-of-sale, customer debt ledger (`دفتر الدين`), inventory, supplier payables, and shift cash-drawer prototype built in **Kotlin** and **Jetpack Compose** (Material 3) for Palestinian shopkeepers, roasteries, and grocery merchants.

This repository is prepared for **local Android Studio development** and integration with the **`Bassamalsaqqa/account`** Laravel `/api/v1` accounting backend.

---

## Quick Links

- **[Surface Coverage Matrix (`docs/SURFACE_COVERAGE.md`)](docs/SURFACE_COVERAGE.md)** — Exact classification of every screen, flow, and dialog: *Working Demo*, *Interactive Simulation*, *Shallow Preview*, or *Deferred to Backend / Hardware*.
- **[Local Android Handoff Guide (`docs/LOCAL_ANDROID_HANDOFF.md`)](docs/LOCAL_ANDROID_HANDOFF.md)** — Architecture overview, financial invariants, hardware boundaries (CameraX/ML Kit & Bluetooth ESC/POS), Laravel `/api/v1` integration contract, test evidence, and local cleanup priorities.

---

## Prerequisites for Local Android Studio Development

1. **Android Studio**: Ladybug (2024.2.1) or newer.
2. **JDK**: Java 17 (bundled JBR in Android Studio works out of the box).
3. **Android SDK**:
   - `compileSdk = 36` (or SDK 35+ matching your local AGP 8.x setup)
   - `minSdk = 24`
   - `targetSdk = 35`
4. **Locale & Numerals**:
   - All monetary amounts (`₪` / ILS), quantities, invoice numbers, and timestamps are formatted with **Western Arabic digits (`0-9`)** via `WesternFormatters` regardless of whether the device locale is Arabic (`ar`) or English (`en`).

---

## Building & Running Locally

### 1. Open in Android Studio
1. Clone or unzip this repository and open the root folder in **Android Studio**.
2. Let Gradle sync finish (`app` module, `com.example` namespace, `com.aistudio.smalltrader.palpos` application ID).
3. Select the `app` run configuration and run on any Android Emulator or physical device (API 24+).

### 2. Command-Line Build & Unit Tests
On a local developer machine with the Gradle wrapper (`./gradlew`) or a system `gradle` installation:

```bash
# Assemble the debug APK
./gradlew :app:assembleDebug

# Run the JVM unit & domain invariant test suite (36 tests)
./gradlew :app:testDebugUnitTest
```

*(Note: Inside the cloud build container where the Gradle wrapper is not used, run `gradle :app:assembleDebug` and `gradle :app:testDebugUnitTest` directly.)*

---

## Core Architecture at a Glance

```text
app/src/main/java/com/example/
├── MainActivity.kt                  # App shell, root vs secondary navigation, BackHandler, modal sheets
├── model/
│   └── TraderModels.kt              # Domain models, InvoicePaymentStatus (PAID_CASH/PARTIAL/ON_CREDIT), SaleDraft, WesternFormatters
├── data/
│   ├── TraderRepository.kt          # Repository interface + OfflineDemoTraderRepository with financial invariants
│   └── SampleCatalog.kt             # Seeded Palestinian merchant catalog, customers, suppliers, invoices, drafts
├── viewmodel/
│   └── TraderViewModel.kt           # Single-source-of-truth StateFlow (TraderUiState) & POS/ledger operations
└── ui/
    ├── components/                  # TopBar, 4-tab BottomBar, simulated Barcode & Bluetooth Printer sheets
    ├── screens/                     # Home, NewSale, InvoiceReview, CashPayment, Receipt, Customers, Catalog, Suppliers, Expenses, Reports, Profile/Settings
    └── theme/                       # SmallTraderTheme, Cairo/JetBrains Mono typography, Light & Dark financial color tokens
```

---

## Truthfulness & Prototype Boundaries

- **In-Memory Demo Repository (`OfflineDemoTraderRepository`)**: All operations execute against deterministic in-memory state seeded from `SampleCatalog`. Data resets on full app process restart until wired to Room / `Bassamalsaqqa/account` `/api/v1`.
- **Financial & Inventory Separation**:
  - Recording a **Financial Purchase Bill (`فاتورة مشتريات مالية`)** updates supplier payables and cash disbursements only; it **never** auto-increments product shelf stock.
  - Recording a **Physical Goods Receipt (`سند استلام بضاعة مخزني - GRN`)** on a purchase bill explicitly increments product shelf stock and logs a `StockMovement` without mutating supplier payables.
  - **Expected Book Cash (`النقد المتوقع دفترياً`)** is computed strictly from shift opening float + cash sales + customer collections − supplier cash payments − shift expenses, and is never conflated with **Actual Physically Counted Cash (`الجرد الفعلي النقدي`)** or the resulting **Cash Variance (`الفرق`)**.
- **Simulated Hardware**: Barcode scanning and 58mm/80mm Bluetooth thermal printing are interactive UI simulations ready to be replaced by CameraX/ML Kit and `BluetoothSocket` ESC/POS adapters during local hardware integration.
