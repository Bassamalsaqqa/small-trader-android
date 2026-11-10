# Small Trader Android — UX & Navigation Reset Design Report
**Project:** `small-trader-android` (commit f862964)  
**Target:** Palestinian Compact Retail Merchant Point of Sale (Offline-First In-Memory Demo)  
**Role:** Design Sub-Agent (Read-Only Architectural Audit & Competitive Formulation)  
**Evidence Standard:** Strictly distinguishes **source-verified**, **primary documentation**, **inferred design hypothesis**, and **predicted (untested)** estimates.

---

## 1. Executive Summary & Design Charter

The objective of this design audit is to establish a calm, error-resistant, merchant-first mobile interaction architecture for **Small Trader (التاجر الصغير)**. The current codebase exhibits high technical quality and strict accounting guards, but suffers from an overloaded accounting dashboard model on compact screens (360dp–393dp), duplicated window insets, custom non-tokenized navigation bars, and competing actions that force a shopkeeper to navigate like a desktop accountant.

### Evidence & Method Boundaries
- **Codebase Inspected:** Git repository `/home/t3dev/projects/small-trader-android` at commit `f862964` (clean working tree).
- **Modification Policy:** Strictly **read-only**. No source modifications, no branch manipulations, no dependency installations, and no device execution were performed.
- **External Benchmarks:** Derived strictly from official primary documentation (Android Material 3 guidelines, Shopify POS manuals, Square POS guides, Loyverse POS knowledge base). No third-party blog posts or marketing renders were used as primary sources.
- **Verification Rule:** Tap counts, transition speeds, and cognitive burdens reported herein are **predicted design models**, not empirically measured merchant field tests.

---

## 2. Competitive & Platform Research: Adopted & Rejected Patterns

### A. Primary Documentation Sources Consulted
1. **Android Developers — Compose Navigation Bar:** https://developer.android.com/develop/ui/compose/components/navigation-bar
2. **Android Developers — Build Adaptive Navigation:** https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation
3. **Android Developers — Touch Target & Accessibility:** https://developer.android.com/develop/ui/compose/accessibility/api-defaults
4. **Android Developers — Insets & Keyboard in Compose:** https://developer.android.com/develop/ui/compose/system/insets-ui
5. **Shopify POS — Cart & Checkout Workflow:** https://help.shopify.com/en/manual/sell-in-person/shopify-pos
6. **Shopify POS — Barcode Scanner Behavior:** https://help.shopify.com/en/manual/sell-in-person/hardware/barcode-scanners
7. **Square POS — Point of Sale Payment Acceptance:** https://squareup.com/help/us/en/article/5115-accept-payments-with-square-point-of-sale
8. **Loyverse POS — How to Make Sales:** https://help.loyverse.com/help/how-make-sales

---

### B. Adopted Patterns (<= 5)

| # | Pattern Name | Primary Source & Link | Documented Mechanics vs Inferred Merchant Value | Application to Small Trader Android |
|---|---|---|---|---|
| **1** | **Direct-to-Cart Fast Catalog Tap with Sticky Dock** | [Loyverse POS: How to Make Sales](https://help.loyverse.com/help/how-make-sales) | **Documented:** Tapping an item immediately increments the current ticket; ticket status/total is pinned at the bottom-right charge button.<br>**Inferred Value:** Eliminates intermediate quantity dialogs for single-item retail sales while preserving rapid multi-tap additions. | In `NewSaleScreen`, tapping any product tile immediately adds unit quantity 1 to basket and exposes a persistent sticky bottom checkout dock with current unit count and net ILS total. |
| **2** | **Single Deliberate Final Settlement Confirmation** | [Square POS: Accept Payments](https://squareup.com/help/us/en/article/5115-accept-payments-with-square-point-of-sale) | **Documented:** Cash checkout presents quick tender buttons (Exact, Round Amounts) and requires tapping "Tender/Charge" once change is calculated.<br>**Inferred Value:** Prevents accidental double-posting while eliminating tedious manual numpad typing for exact cash transactions. | In `CashPaymentScreen`, one-tap quick denominations (Exact ₪, 20₪, 50₪, 100₪, 200₪) populate tender, display change due instantly, and require exactly ONE deliberate tap on `تأكيد استلام المبلغ وإصدار الإيصال`. |
| **3** | **Integrated Full-Screen Continuous Barcode Scanning** | [Shopify POS: Camera Barcodes](https://help.shopify.com/en/manual/sell-in-person/hardware/barcode-scanners) | **Documented:** Camera scanner operates directly in context of active cart with audio/visual feedback, without creating detached workflows.<br>**Inferred Value:** Merchant can scan consecutive items rapidly without losing basket context or having a tiny 180dp viewport cramped by on-screen keyboard. | Modal sheet with full viewfinder, torch toggle, scan vibration/beep feedback, duplicate frame suppression, and instant quantity incrementing directly in `NewSaleScreen`. |
| **4** | **Unfinished Basket Persistence Across Navigation** | [Shopify POS Workflows](https://help.shopify.com/en/manual/sell-in-person/shopify-pos) | **Documented:** Navigating between Home, Inventory, or Customer profiles preserves active draft cart lines in memory.<br>**Inferred Value:** Allows shopkeeper to check customer debt or restock count mid-sale without losing the customer's assembled items. | State preservation in `TraderViewModel.uiState`: switching bottom tabs or opening customer detail never clears `basket`; TopBar and Home expose a `متابعة البيع (السلة)` pill. |
| **5** | **Material 3 Strict 48dp Minimum Accessible Interactive Targets** | [Android M3 Accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | **Documented:** Standard touch target sizing must be >= 48dp x 48dp with safe padding, especially critical in dense bottom navigation bars and numpads.<br>**Inferred Value:** Prevents mis-taps by busy grocery/kiosk merchants with wet or dusty hands on compact 360dp screens. | All navigation items, numpad buttons, steppers, and filter chips adhere strictly to `Modifier.heightIn(min = 48.dp)` and `Modifier.sizeIn(min = 48.dp)`. |

---

### C. Rejected Patterns (<= 5)

| # | Pattern Name | Primary Source / Context | Why Rejected for Small Trader (Palestine / Compact Retail) | Documented vs Inferred Basis |
|---|---|---|---|---|
| **1** | **One-Tap Instant Cash Bypass ("Instant Sale")** | High-speed kiosk literature / Unofficial proposals | **Rejected:** Bypassing settlement review directly to receipt upon item tap leads to catastrophic accidental posting in retail when items are scanned erroneously or prices need verbal agreement. Cash must always display Due, Received, and Change before posting. | **Documented in Brief:** Reject unguarded one-tap posting shortcuts to protect accounting ledgers. |
| **2** | **Phantom Partial Payment Toggle** | US / Cloud POS Split Tenders | **Rejected:** The current in-memory domain and Laravel backend authority do not support verified, audited partial settlement creation in this phase. Showing a non-functional or half-baked "دفع جزئي" option compromises trust. | **Source-Verified in Repo:** `CashPaymentScreen` and `TraderRepository` only support full cash settlement or full customer credit posting. |
| **3** | **Card / Contactless Hardware Transplant** | [Square POS Readers](https://squareup.com/help/us/en/article/5115-accept-payments-with-square-point-of-sale) & [Shopify Tap to Pay](https://help.shopify.com/en/manual/sell-in-person/shopify-pos) | **Rejected:** Transplanting Stripe, Square Reader, Apple Pay, or Google Pay SDKs is impossible for Palestinian cash/credit merchant reality and violates the offline local in-memory boundary. | **Primary Scope Constraint:** Palestine cash drawer (ILS ₪) and store-credit account ledgers only. |
| **4** | **Fake "Connected • Active Session" Status Indicators** | SaaS Web Admin Dashboards | **Rejected:** Placing a green pulsing dot claiming "Connected" when the app is running in an offline in-memory demo mode is deceptive. | **Documented in Brief:** Status must truthfully state `نسخة تجريبية — البيانات على الجهاز فقط`. |
| **5** | **Permanent 5th "Purchases" Bottom Navigation Tab on Compact Phones** | Standard 5-tab desktop/tablet navigation models | **Rejected:** On a 360dp phone, 5 bottom tabs allocate only 72dp per tab. In Arabic with RTL typography (Cairo) and 1.5x font scaling, labels wrap or clip severely, violating M3 guidelines. Purchases is a low-frequency back-office task compared to Sales. | **Material 3 Navigation Spec:** Recommends 3–4 destinations on compact mobile; 5 destinations cause truncation in dense languages. |

---

## 3. Navigation Architecture: Direction A vs Direction B

### Detailed Model Definitions
- **Direction A (Recommended): 4 Top-Level Destinations + Prominent Central Quick-Action Modal (`+`)**
  - **Bottom Destinations (4):**
    1. **الرئيسية (Home):** Register status, financial pulse (cash drawer & sales), urgent low-stock alerts, quick actions.
    2. **البيع (New Sale):** Active catalog, direct barcode search, sticky basket dock with badge count.
    3. **الفواتير (Invoices):** History of completed sales, credit invoices, reprints, and customer receipt lookups.
    4. **الحسابات (Accounts / People):** Unified customer debt ledgers and supplier payables.
  - **Central Elevated Action (`+`):** An elevated action button opening a bottom sheet modal offering 4 prioritized actions:
    - `فاتورة شراء جديدة (وارد بضاعة)` — Direct entry to procurement / supplier bill.
    - `صنف جديد` — Add product to catalog.
    - `سند قبض من زبون` — Collect debt from customer.
    - `دفعة لمورد / مصاريف` — Pay vendor or record petty cash expense.

- **Direction B: 5 Static Bottom Navigation Tabs + Contextual Floating Action Button (FAB)**
  - **Bottom Destinations (5):**
    1. الرئيسية (Home)
    2. البيع (New Sale)
    3. المشتريات (Purchases) — Dedicated top-level tab
    4. الفواتير / المخزون (Invoices / Inventory)
    5. الزبائن (Customers)
  - **Action Affordance:** Screen-contextual FAB (e.g., `+` on Purchases adds purchase bill; `+` on Customers adds customer).

---

### Task Path & Interaction Comparison (5 Core Merchant Tasks)

*Note: All step counts and tap estimates are **predicted design hypotheses**, not measured stopwatch benchmarks.*

| Task | Direction A Flow (4 Tabs + Center `+`) | Direction A Predicted Taps | Direction B Flow (5 Tabs + Contextual FAB) | Direction B Predicted Taps | Evaluation & Discoverability Notes |
|---|---|:---:|---|:---:|---|
| **1. Sell (بيع صنف نقدي)** | Home -> Tap `بيع` tab (or Home `بدء بيع`) -> Tap Product -> Tap `دفع` -> Tap `المبلغ بالضبط` -> Tap `تأكيد البيع` | **4 taps** | Home -> Tap `بيع` tab -> Tap Product -> Tap `دفع` -> Tap `المبلغ بالضبط` -> Tap `تأكيد البيع` | **4 taps** | **Equal speed**. Both provide direct 1-tap entry to sales register. |
| **2. Buy / Purchase (شراء بضاعة)** | Any screen -> Tap Center `+` -> Tap `فاتورة شراء` (or Home -> `الموردون والمشتريات`) | **2 taps** | Home -> Tap `المشتريات` tab -> Tap `+` FAB | **2 taps** | Direction A exposes purchase from **any** screen via central `+`. Direction B requires navigating to tab 3 first. |
| **3. Add Product (إضافة صنف)** | Any screen -> Tap Center `+` -> Tap `صنف جديد` | **2 taps** | Home -> Tap `المخزون` tab -> Tap `+` FAB | **2 taps** | Direction A allows quick stock addition mid-operation without navigating away from current context. |
| **4. Collect Debt (قبض من زبون)** | Any screen -> Tap Center `+` -> Tap `قبض من زبون` -> Select Customer -> Confirm | **3 taps** | Home -> Tap `الزبائن` tab -> Find Customer -> Tap `قبض` | **3 taps** | Direction A centralizes money-movement tasks under the explicit action trigger. |
| **5. Pay Vendor (دفع لمورد)** | Any screen -> Tap Center `+` -> Tap `دفعة لمورد` -> Select Supplier -> Confirm | **3 taps** | Home -> Tap `المشتريات` tab -> Tap `الموردون` sub-tab -> Find Supplier -> Tap `دفع` | **4 taps** | Direction B buries supplier payouts inside nested procurement hierarchy. |

---

### Viewport, Responsive & Density Stress-Test Matrix

| Viewport & Mode | Direction A (4 Destinations + Center `+`) | Direction B (5 Destinations + Contextual FAB) | Material 3 / Ergonomic Finding |
|---|---|---|---|
| **360dp Width**<br>*(Small Compact e.g. Galaxy A04)* | **Pass:** 4 destinations allocate ~72dp per tab + dedicated 56dp central action. Arabic labels (`الرئيسية`, `البيع`, `الفواتير`, `الحسابات`) fit comfortably on 1 line with 12sp typography without truncation. | **Fail / Cramped:** 5 tabs allocate only 64dp–68dp per tab. In Arabic, `المشتريات` (10 chars) and `الفواتير` clip or force 2-line stacking, pushing icons above standard 48dp touch bounds. | Direction A prevents label truncation and touch-target crowding on entry-level Android devices. |
| **393dp Width**<br>*(Standard e.g. Pixel 7a/8)* | **Optimal:** Balanced spacing, clear icon-label pairing, generous touch targets (>= 48dp), clear elevation on central action button. | **Marginal:** Fits in 1.0x font size, but feels visually noisy and crowded with 5 icons and text labels spanning the base. | Direction A provides a calmer visual baseline for non-technical users. |
| **440dp Width**<br>*(Large e.g. Pixel Pro)* | **Spacious:** Clean alignment; can transition to centered grouped navigation bar without edge-to-edge stretching. | **Acceptable:** Fits comfortably across width, but FAB begins competing with bottom sticky dock on checkout screens. | Direction A scales smoothly without layout redesign. |
| **Large Text (1.5x / 2.0x)** | **Pass:** Labels wrap gracefully or leverage standard M3 label hiding on unselected tabs while maintaining icon clarity. | **Severe Failure:** 5 tabs break under 1.5x font scale; Arabic text wraps into 3 lines or clips adjacent items completely. | Accessibility standard requires resilient font scaling without text clipping. |
| **Dark Theme** | **Pass:** Central `+` uses `MaterialTheme.colorScheme.primary` container with high-contrast icon; tabs use standard surface tone. | **Acceptable:** Standard M3 tonal elevation applies, but 5 active containers create visual noise at low screen brightness. | Direction A provides a single unambiguous primary action beacon. |

---

### Procurement Discoverability Rationale for Direction A

Why Direction A does **not** hide purchasing:
1. **Central Action Trigger:** The elevated `+` button is visible across all root screens. Tapping `+` opens a sheet where the very first option is `فاتورة شراء جديدة (وارد بضاعة)` with a truck/box icon.
2. **Home Screen Operations Section:** In `HomeScreen.kt`, the prominent Bento card `الموردون والمشتريات` displays outstanding supplier payables and provides 1-tap entry to purchase bills.
3. **Inventory Catalog Hub:** On `ProductsCatalogScreen.kt`, low-stock items provide a direct `طلب توريد / شراء` shortcut.
4. **Merchant Reality:** Small grocery and kiosk merchants record 50–300 customer sales per day, but only 1–3 supplier deliveries per week. Elevating purchases to a permanent 20% slice of bottom screen estate misallocates mobile ergonomics.

---

## 4. Localized Palestinian Merchant Arabic Copy & Terminology

### A. Core Glossary: Authentic Palestinian Retail vs Clunky Accounting

| Concept / Action | Recommended Merchant Arabic | Clunky / Rejected Web Back-Office Term | Merchant Rationale & Local Context |
|---|---|---|---|
| **Sell** | **بيع** | إنشاء فاتورة مبيعات ضريبية | "بيع" is immediate, natural, and universal in Ramallah/Nablus/Hebron retail shops. |
| **Buy / Goods Receipt** | **شراء / وارد بضاعة** | فاتورة مشتريات وتوريد مخزني | Combines financial obligation with physical stock arrival in shopkeeper terms. |
| **Collect Money** | **قبض من زبون** | سند قبض مالي للمدينين | "قبض" is standard Palestinian shop speech for cash collection on store credit. |
| **Pay Supplier** | **دفعة لمورد** | سند صرف دفعات دائنة | "دفعة" clearly denotes partial or full cash handover to a distributor. |
| **Cash Drawer** | **الصندوق (الكاش)** | الخزينة النقدية والسيولة | Merchants refer to physical drawer cash as "الصندوق" or "الكاش". |
| **Customer Credit / Debt** | **ديون الزبائن (على الحساب)** | الذمم المدينة المستحقة | "على الحساب" is the exact colloquial term for store credit purchases. |
| **Exact Cash** | **المبلغ بالضبط** | سداد كامل القيمة نقداً | Direct, unambiguous button text on checkout keypad. |
| **Change Due** | **الباقي للزبون** | الفائض النقدي المرتجع | Clear instruction to the cashier on physical change to hand over. |

---

### B. "People" Bottom Tab Label Trade-Offs (Explicitly Untested)

*Note: None of these alternatives have been tested in formal user research with Palestinian merchants; each represents a design hypothesis.*

| Label Option | Literal Translation | Visual / Cognitive Advantage | Critical Disadvantage | Recommendation Status |
|---|---|---|---|---|
| **الحسابات** | Accounts | Compact (7 characters); fits easily on 360dp screens; common in Arab wholesale/commercial accounting. | Might be confused with app settings, bank accounts, or ledger configuration by small grocers. | **Candidate 1 (Shortest)** |
| **الزبائن** | Customers | 100% natural and instantly recognized by every shopkeeper. | Completely conceals **Suppliers (الموردون)** from the navigation surface, requiring users to guess that suppliers live inside. | **Candidate 2 (High clarity, partial scope)** |
| **الزبائن والموردون** | Customers & Suppliers | 100% accurate; leaves zero ambiguity about destination contents. | Too long (17 characters). Will wrap or clip on 360dp/393dp screens at >1.2x font scale. | **Rejected on compact mobile** |
| **جهات التعامل / الأطراف** | Business Parties | Formal, correct commercial terminology. | Stiff, bureaucratic government/ERP phrasing; alien to small shopkeepers. | **Rejected** |

**Design Proposal:** Adopt **`الحسابات`** with contextual subtitle or two internal segmented tabs: `الزبائن` (Customers) and `الموردون` (Suppliers).

---

### C. Truthful Demo Notice
*Replace all mock "Connected • Active Session" UI with an honest, permanent status line:*
> **`نسخة تجريبية — البيانات محفوظة على هذا الجهاز فقط`**  
> *(Demo Version — Data stored locally on this device only)*

On debug tap / info button:
> `وضع التجربة المحلية: جميع الفواتير والمخزون مخزنة في ذاكرة الهاتف المؤقتة ولا تتصل بسيرفر خارجي حالياً.`

---

### D. Progressive Checkout Architecture (Zero Feature Loss)

The checkout flow must allow a walk-in cash customer to finish in **2 fast steps** while keeping advanced features (Customer account, Discount, Note, Draft quote) immediately accessible via progressive disclosure:

```
[ Step 1: New Sale Screen ]
  ┌──────────────────────────────────────────────┐
  │ Products Catalog Grid / Barcode Search       │
  │ [Item 1: 15₪] [Item 2: 25₪] [Item 3: 10₪]    │
  └──────────────────────────────────────────────┘
  ┌──────────────────────────────────────────────┐
  │ 🛒 3 أصناف • المجموع: 50.00 ₪                │
  │ [ الزبون: نقدي عام ▾ ] [ خصم ▾ ]             │ <-- Optional Inline Pills
  │ [ زر رئيسي: متابعة الدفع (50.00 ₪) ➔ ]       │
  └──────────────────────────────────────────────┘
                         │ Tap "متابعة الدفع"
                         ▼
[ Step 2: Settlement & Cash Drawer ]
  ┌──────────────────────────────────────────────┐
  │ المطلوب: 50.00 ₪                             │
  │ الواصل: [ 50.00 ]  الباقي: 0.00 ₪            │
  │ Quick Cash: [50₪ بالضبط] [100₪] [200₪]       │
  │ ──────────────────────────────────────────   │
  │ 🔘 دفع نقدي (الصندوق)                         │
  │ ⚪ بيع آجل (على الحساب) -> يطلب اختيار زبون   │
  │ ──────────────────────────────────────────   │
  │ [ زر رئيسي: تأكيد استلام المبلغ وحفظ الفاتورة] │
  └──────────────────────────────────────────────┘
                         │ Tap "تأكيد"
                         ▼
[ Step 3: Receipt & Document Handover ]
  ┌──────────────────────────────────────────────┐
  │ ✅ تم حفظ البيع وإصدار الفاتورة رقم #0042      │
  │ [ 🖨️ طباعة إيصال ] [ 💬 مشاركة كشف ]          │
  │ [ 🔄 بيع جديد ]                              │
  └──────────────────────────────────────────────┘
```

**Key Safeguards:**
- **Customer assignment:** Defaults to "زبون نقدي عام" (General Cash Walk-in). Tapping the pill allows assigning a named customer.
- **Discounts:** Hidden under a neat `+ خصم` chip. Tapping opens presets (5%, 10%, 15% or fixed ILS amount). Max discount is capped at total bill.
- **Drafts / Quotes:** Placed in the top action overflow (`حفظ كمسودة` / `مشاركة كعرض سعر`), avoiding button sprawl on the primary path.

---

## 5. Inspection & Mapping to Current Compose Implementation

### A. `CommonComponents.kt` & Navigation Bar Architecture
1. **Current Code (`app/src/main/java/com/example/ui/components/CommonComponents.kt:752–860`):**
   - Implements `SmallTraderBottomBar` using a custom `Surface` + `Row` + `Box` hierarchy rather than official Material 3 `NavigationBar` and `NavigationBarItem`.
   - **Double Insets Bug:** Line 804 applies `.windowInsetsPadding(WindowInsets.navigationBars)`. However, `MainActivity.kt:85` applies `contentWindowInsets = WindowInsets.safeDrawing` to the `Scaffold`, and line 107 applies `.padding(scaffoldPadding)` to the inner `Box`. This causes double-padding on devices with 3-button navigation bars, pushing content unnecessarily high.
   - **Recommendation:** Replace custom row with native `NavigationBar` from `androidx.compose.material3`. Pass `windowInsets = WindowInsets(0, 0, 0, 0)` to `SmallTraderBottomBar` and let `Scaffold` control inset consumption cleanly.

2. **TopBar Overload (`CommonComponents.kt:124–380`):**
   - `SmallTraderTopBar` houses up to 5 distinct actions simultaneously (Back icon, Title + Subtitle, Resume Sale pill, Barcode Scanner, Bluetooth status, Language toggle, Theme toggle, and Account dropdown).
   - On 360dp phones, this forces text truncation and overlapping action icons.
   - **Recommendation:** Move Language toggle, Theme toggle, and App Mode into the Account menu or Settings sheet. Reserve TopBar purely for `Back`, `Screen Title`, and `Resume Sale` pill.

---

### B. `MainActivity.kt` & Back-Stack Routing
1. **Current Code (`MainActivity.kt:71–81`):**
   - `canNavigateBack` checks 8 discrete boolean dialog flags in a manual boolean disjunction.
   - Transitions rely on a single flat `TraderScreen` enum inside a `Crossfade`.
   - **Recommendation:** Retain `BackHandler`, but ensure that pressing Back from nested sheets (Scanner, Quick Add) dismisses the sheet before altering screen navigation.

---

### C. `HomeScreen.kt`
1. **Current Code (`app/src/main/java/com/example/ui/screens/HomeScreen.kt:120–350`):**
   - Implements `BoxWithConstraints` with responsive two-column layout for `maxWidth >= 700.dp`.
   - On compact screens (<700dp), stacks:
     1. Primary Register (Sale in progress or Start sale)
     2. Financial/Shift Pulse card
     3. Actionable Low Stock
     4. Store Operations Bento Grid (2x2)
     5. Recent Invoices
   - **Observation:** Very rich, but pushes the recent invoices below the first viewport fold on 360x800dp screens.
   - **Recommendation:** Keep Primary Register and Financial Pulse at the top. The central `+` button eliminates the need for 4 duplicate operational buttons in the Bento grid, allowing the Home screen to breathe.

---

### D. `NewSaleScreen.kt` & Sticky Dock
1. **Current Code (`app/src/main/java/com/example/ui/screens/NewSaleScreen.kt:940–1030`):**
   - `BasketStickyBottomSheet` provides a quiet 48dp bar when empty (`السلة فارغة • المس أي صنف للإضافة`) and expands when items are present.
   - **Recommendation:** Excellent pattern. Enhance the bar to display a direct camera scanner icon right next to the search bar so barcode entry is never hidden.

---

### E. `InvoiceReviewScreen.kt`
1. **Current Code (`app/src/main/java/com/example/ui/screens/InvoiceReviewScreen.kt`):**
   - Contains customer assignment card, discount chip row, note input, and action buttons.
   - **Recommendation:** Make this screen an optional expandable step rather than a mandatory roadblock for cash sales. If a user taps "Proceed to Pay" directly from `NewSaleScreen`, bypass directly to `CashPaymentScreen` unless a customer note or custom discount has been activated.

---

### F. `CashPaymentScreen.kt`
1. **Current Code (`app/src/main/java/com/example/ui/screens/CashPaymentScreen.kt:120–220`):**
   - Features Amount Due, Cash Received, Change Due, quick denomination pills, and numpad.
   - **Recommendation:** Ensure the numpad buttons use `CairoFontFamily` with `tnum` (tabular numerals) and minimum 48dp touch bounds. The single final submit button `تأكيد استلام المبلغ وإصدار الإيصال` must remain strictly locked until `cashReceived >= basketNetTotal` or `isCreditDebt == true`.

---

### G. `ReceiptScreen.kt`
1. **Current Code (`app/src/main/java/com/example/ui/screens/ReceiptScreen.kt:120–200`):**
   - Clear distinction between cash sale (`تم حفظ عملية البيع النقدي وإصدار الإيصال`) and credit sale (`تم تسجيل فاتورة البيع الآجل على حساب الزبون`).
   - Displays change due prominently.
   - **Recommendation:** The primary bottom CTA must be `بيع جديد (F2 / New Sale)` to quickly cycle to the next waiting customer.

---

## 6. Core Edge Cases & UX State Resilience

| State Category | Concrete Trigger Scenario | Expected Visual Presentation | Recovery Mechanism |
|---|---|---|---|
| **Error: Underpayment** | Cashier inputs 40₪ cash for a 50₪ bill and attempts to submit cash sale. | Submit button is disabled; warning banner shows: `المبلغ المستلم أقل من المطلوب بمقدار 10.00 ₪`. | Tap `المبلغ بالضبط` or enter correct cash; button re-enables instantly. |
| **Error: Credit without Customer** | Cashier selects "بيع آجل (على الحساب)" but customer is "زبون نقدي عام". | Action blocked; inline alert: `يجب تحديد حساب الزبون أولاً لتسجيل البيع الآجل`. | Tap `اختيار زبون` dialog opens directly; selecting customer clears error. |
| **Error: Credit Limit Exceeded** | Customer debt limit is 500₪, current balance is 450₪, new sale is 100₪. | Prominent debit warning: `تجاوز سقف الدين: الرصيد الجديد سيكون 550.00 ₪ (الحد المسموح: 500.00 ₪)`. | Requires Owner override confirmation or partial cash payment. |
| **Empty: First Launch / Catalog** | Fresh installation with zero products added. | Centered calm illustration with text: `لا توجد أصناف في المتجر بعد`<br>Primary CTA: `إضافة صنفك الأول (+)`. | Tapping CTA opens Quick Add Product sheet. |
| **Empty: Product Search** | Search query "جبنة بيضاء" returns 0 matches in catalog. | Clear empty state: `لا توجد نتائج تطابق "جبنة بيضاء"`<br>Secondary action: `إضافة "جبنة بيضاء" كصنف مخصص بالسلة`. | One-tap addition of custom item with entered name and price. |
| **Permission: Camera Denied** | User taps Barcode Scanner but denied camera permission. | Clean fallback card inside sheet: `الكاميرا غير مفعلة لمسح الباركود`<br>Subtext: `يمكنك كتابة رقم الباركود يدوياً أو تفعيل الإذن من إعدادات الهاتف`. | Input text field with manual numpad barcode entry + button to open App Settings. |
| **Keyboard: Inset Overlap** | On-screen IME pops up while typing customer note or custom discount. | Bottom CTA buttons and active text fields smoothly translate above IME using `Modifier.imePadding()`. | User can tap outside or press Back/Done on IME to collapse without lost input. |
| **Recovery: Accidental Back Tap** | Merchant assembling 5 items accidentally taps hardware Back or switches to Invoices tab. | Active basket items, quantities, and selected customer remain 100% intact in memory. A floating pill `السلة (5) • 120.00 ₪` remains visible. | Tapping pill or returning to `البيع` tab resumes exactly where merchant left off. |

---

## 7. Visual Gate Criteria & Acceptance Matrix

No implementation branch should be marked as accepted or presented for owner visual sign-off without satisfying the following verification criteria on real or emulated target viewports:

1. **Exact Hardware & Viewport Verification:**
   - Must be verified on compact dimensions: **360dp width** (e.g. 360 x 800 dp, density 2.0x), **393dp width** (e.g. Pixel 7a/8), and **440dp width** (e.g. Pixel 8 Pro).
   - Must verify both **Arabic RTL** and **English LTR**.
   - Must verify both **Light** and **Dark** themes.
2. **Typography & Layout Legibility:**
   - Cairo font must render with comfortable line heights; no clipped Arabic diacritics, ascenders, or descenders.
   - All monetary amounts must render using tabular numerals (`tnum`).
   - Dynamic font scaling at **1.5x** and **2.0x** must not cause horizontal text overlap or push critical buttons off-screen.
3. **Touch Targets & Inset Compliance:**
   - Every interactive control must measure at least **48dp x 48dp**.
   - Zero double-padding or bottom bar overlap against Android 3-button and gesture navigation bars.
4. **Deliberate Accounting Guards:**
   - Under no circumstances may cash sale submission be bypassed with an unguarded single tap.
   - Credit sales must strictly enforce named customer assignment.
   - Stock counts and prices must reflect exact decimal calculations.
5. **Gate Signoff States:**
   - **READY FOR VISUAL REVIEW:** Captured APK with SHA-256 and genuine emulator screenshots matching commit SHA attached.
   - **REVISIONS REQUIRED:** Inset glitches, text clipping, or missing edge-case recoveries detected.
   - **BLOCKED:** Unverified scanner hardware, broken accounting guards, or unauthorized backend modification.
