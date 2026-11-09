> **Candidate v3 (Consolidated Working Prototype):** Native Arabic-first merchant workspace with Home, POS Sales, Invoices Ledger, Products Catalog, and People Hub. See **[Candidate v3 Handoff (`docs/CANDIDATE_V3_HANDOFF.md`)](docs/CANDIDATE_V3_HANDOFF.md)** for canonical APK artifacts, verified test totals (105 tests, 104 passed), lint audit, limitations, and owner Windows installation instructions. The prototype description below provides project background and architecture.

# التاجر الصغير · Small Trader (Android POS & Merchant Ledger)

**التاجر الصغير (Small Trader)** is an Arabic-first (RTL) / English (LTR) native Android point-of-sale, customer debt ledger (`دفتر الدين`), inventory, supplier payables, and shift cash-drawer prototype built in **Kotlin** and **Jetpack Compose** (Material 3) for Palestinian shopkeepers, roasteries, and grocery merchants.

This repository is prepared for **local Android Studio development** and integration with the **`Bassamalsaqqa/account`** Laravel `/api/v1` accounting backend.

---

## Quick Links

- **[Candidate v3 Handoff (`docs/CANDIDATE_V3_HANDOFF.md`)](docs/CANDIDATE_V3_HANDOFF.md)** — Canonical APK digest, verified test totals (105 tests), lint report, bottom bar freeze status, and Windows owner installation steps.
- **[Surface Coverage Matrix (`docs/SURFACE_COVERAGE.md`)](docs/SURFACE_COVERAGE.md)** — Exact classification of every screen, flow, and dialog: *Working Demo*, *Interactive Simulation*, *Shallow Preview*, or *Deferred to Backend / Hardware*.
- **[Local Android Handoff Guide (`docs/LOCAL_ANDROID_HANDOFF.md`)](docs/LOCAL_ANDROID_HANDOFF.md)** — Architecture overview, financial invariants, hardware boundaries (CameraX/ML Kit & Bluetooth ESC/POS), verified `Bassamalsaqqa/account` (`routes/api-v1.php`) contracts vs future backend requirements, test evidence, and local cleanup priorities.

---

## Actual Gradle & SDK Configuration (`app/build.gradle.kts` & `gradle/libs.versions.toml`)

1. **Gradle & Android Gradle Plugin (AGP)**:
   - **Gradle Wrapper**: `Gradle 9.3.1` (`gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`)
   - **AGP**: `9.1.1` (`com.android.application`)
   - **Kotlin**: `2.2.10` (`org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`)
2. **Android SDK & Java Target**:
   - `namespace = "com.example"`
   - `applicationId = "com.aistudio.smalltrader.psdukx"`
   - `compileSdk = release(36) { minorApiLevel = 1 }`
   - `minSdk = 24`
   - `targetSdk = 36`
   - `sourceCompatibility = JavaVersion.VERSION_11` / `targetCompatibility = JavaVersion.VERSION_11` (runs under JDK 17/21 in Android Studio or CLI; verified on Temurin JDK 21.0.13)
3. **Locale & Numerals**:
   - All monetary amounts (`₪` / ILS), quantities, invoice numbers, and timestamps are formatted with **Western Arabic digits (`0-9`)** via `WesternFormatters` regardless of whether the device locale is Arabic (`ar`) or English (`en`).

---

## Building & Running Locally

### 1. Open in Android Studio
1. Open the project root folder in **Android Studio** (with Android SDK API 36.1 installed).
2. Let Gradle sync finish using the included Gradle 9.3.1 wrapper (`app` module, `com.example` namespace, `com.aistudio.smalltrader.psdukx` application ID).
3. Select the `app` run configuration and run on any Android Emulator or physical device (API 24+).

### 2. Command-Line Build & Unit Tests
Using the included Gradle wrapper (`./gradlew` on macOS/Linux or `gradlew.bat` on Windows):

```bash
# Assemble the debug APK
./gradlew :app:assembleDebug

# Run the JVM unit & domain invariant test suite (38 tests)
./gradlew :app:testDebugUnitTest
```

*(Note: Inside the cloud build container, `gradle :app:assembleDebug` and `gradle :app:testDebugUnitTest` can also be invoked directly.)*

---

## Actual Source Tree

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt                  # App shell, root vs secondary navigation, BackHandler, modal sheets
│       │   │   ├── data/
│       │   │   │   ├── SampleCatalog.kt             # Seeded Palestinian merchant catalog, reconciled customers/invoices/statements, drafts
│       │   │   │   └── TraderRepository.kt          # Repository interface + OfflineDemoTraderRepository with accounting & GRN guards
│       │   │   ├── model/
│       │   │   │   └── TraderModels.kt              # Domain models, InvoicePaymentStatus (PAID_CASH/PARTIAL/ON_CREDIT), SaleDraft, WesternFormatters
│       │   │   ├── ui/
│       │   │   │   ├── components/
│       │   │   │   │   ├── ModalSheetsAndDialogs.kt # Custom quick item dialog, simulated Barcode & Bluetooth Printer sheets
│       │   │   │   │   └── TraderAppBars.kt         # TopBar, 4-tab BottomBar, Store Operations & Account lateral switchers
│       │   │   │   ├── screens/
│       │   │   │   │   ├── BusinessExtraScreens.kt  # ExpensesScreen, FinancialReportsScreen, UserProfileScreen, BusinessSettingsScreen
│       │   │   │   │   ├── CashPaymentScreen.kt     # Tender entry, quick denominations, change & credit posting
│       │   │   │   │   ├── CustomersScreen.kt       # Customers directory, CustomerDetailScreen, receipt vouchers, statement share
│       │   │   │   │   ├── HomeScreen.kt            # Shift pulse, unfinished basket resume card, urgent low-stock list, operations grid
│       │   │   │   │   ├── InvoiceReviewScreen.kt   # Basket review, discounts, customer assignment, drafts & quotation preview
│       │   │   │   │   ├── NewSaleScreen.kt         # Catalog grid, Arabic-normalized search, category chips, sticky checkout bar
│       │   │   │   │   ├── ProductsCatalogScreen.kt # Catalog list, ProductDetailScreen, create/edit product, warehouse transfer preview
│       │   │   │   │   ├── ReceiptScreen.kt         # Thermal receipt (58mm/80mm), 3-state payment status, sales return preview, InvoicesHistoryScreen
│       │   │   │   │   └── SuppliersScreen.kt       # Suppliers list, SupplierDetailScreen, financial bills, bounded single-SKU GRN, return preview
│       │   │   │   └── theme/
│       │   │   │       ├── Color.kt                 # Light & Dark financial color tokens
│       │   │   │       ├── Theme.kt                 # SmallTraderTheme & TraderTheme financial composition locals
│       │   │   │       └── Type.kt                  # Cairo & JetBrains Mono typography scale
│       │   │   └── viewmodel/
│       │   │       └── TraderViewModel.kt           # Single-source-of-truth StateFlow (TraderUiState) & POS/ledger operations
│       │   └── res/                                 # Adaptive launcher icons, bundled Cairo/JetBrains Mono fonts, strings.xml
│       └── test/java/com/example/
│           └── ExampleUnitTest.kt                   # 38 JVM unit & regression tests
├── docs/
│   ├── LOCAL_ANDROID_HANDOFF.md                     # Architecture, verified Bassamalsaqqa/account API routes, and handoff guide
│   └── SURFACE_COVERAGE.md                          # Screen-by-screen classification matrix
├── gradle/
│   ├── libs.versions.toml                           # Version catalog (AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2026.02.01)
│   └── wrapper/                                     # Gradle 9.3.1 wrapper jar & properties
├── build.gradle.kts
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

---

## Truthfulness & Prototype Boundaries

- **In-Memory Demo Repository (`OfflineDemoTraderRepository`)**: All operations execute against deterministic in-memory state seeded from `SampleCatalog`. Data resets on full app process restart.
- **Development-Only vs Production Capabilities**:
  - **Demo Role Switching (`UserProfileScreen` → `demo_role_switcher_card`)**: Clearly isolated under *"Demo & Testing Controls"* for inspecting Owner/Manager vs Cashier permission gates locally; not a production authentication system.
  - **Authentication & Offline Posting Policy**: The `Bassamalsaqqa/account` backend (`routes/api-v1.php`) uses web-session authentication (`web`, `auth`, `auth.session`, `Phase11ApiGuard`) and a server-side `draft` → `preview` → `execute` sales invoice pipeline. Native Sanctum token auth and offline financial posting have **not** been approved.
- **Financial & Inventory Separation**:
  - Recording a **Financial Purchase Bill (`فاتورة مشتريات مالية`)** updates supplier payables and cash disbursements only; it **never** auto-increments product shelf stock.
  - Recording a **Physical Goods Receipt (`سند استلام بضاعة مخزني - GRN`)** is a bounded single-SKU demonstration that verifies the purchase bill exists, has not already been received, and matches the bill's expected product SKU and expected quantity before incrementing stock once.
  - **Expected Book Cash (`النقد المتوقع دفترياً`)** is computed strictly from shift opening float + cash sales + customer collections − supplier cash payments − shift expenses, and is never conflated with **Actual Physically Counted Cash (`الجرد الفعلي النقدي`)** or the resulting **Cash Variance (`الفرق`)**.
- **Simulated Hardware**: Barcode scanning and 58mm/80mm Bluetooth thermal printing are interactive UI simulations ready to be replaced by CameraX/ML Kit and `BluetoothSocket` ESC/POS adapters during local hardware integration.
