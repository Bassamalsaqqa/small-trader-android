# SMALL TRADER / التاجر الصغير — NATIVE ANDROID PRODUCT EXPERIENCE RESET
## Consolidated master execution brief for T3 / Codex / Antigravity

**Document version:** 2.0 — 2026-11-07  
**Purpose:** Replace three competing AI recommendations with ONE evidence-based product brief and safe implementation sequence.  
**Product:** A simple, fast, trustworthy Android companion for ordinary Palestinian merchants, cashiers, and shop owners.  
**Android implementation:** https://github.com/Bassamalsaqqa/small-trader-android  
**Authoritative business/accounting reference (READ ONLY):** https://github.com/Bassamalsaqqa/account  
**Execution environment:** T3 on HP-worker, under the existing `t3dev` sandbox; owner can inspect a Windows `Pixel_8a_T3` emulator.  
**Status:** A proposed, independent product/engineering direction. NOT an approved merger, deployment, integration, camera certification, or verified visual acceptance.

> **AGENT INSTRUCTION — READ THIS FIRST:** You are the accountable principal Android product designer, merchant-workflow architect, Kotlin/Jetpack Compose engineer, and integrator. The owner has **rejected the current UX**, despite the useful technical groundwork. Your job is not to repaint the old screens or add more components. Rebuild the experience around merchant intentions while preserving financial and permission correctness. Work autonomously on safe read/design/branch/prototype/QA tasks, and stop at the explicit owner acceptance gate before merge, production connectivity or irreversible operations. Report observed facts, decisions, evidence and limitations, not aspirational marketing.

---

## 1. Ground truth and source hierarchy

### GitHub snapshot verified 2026-11-07

| Source | Verified state | Implication |
|---|---|---|
| Android `main` | `ce2bb6ef8c480048036187892850fa6fb8853f6f` | Current public baseline at review time |
| Android draft PR #1 | https://github.com/Bassamalsaqqa/small-trader-android/pull/1 | **Open, draft, UNMERGED** |
| PR #1 branch/head | `ux/native-phase1` / `f8629644617d147b1d0920634b3eaed4e410cfb5` | Useful technical foundation, **design rejected**; recheck for updates |
| Laravel `account` main | `f8a0cf7012c4875bec7320d42e0f8dde58593b52` | Read-only domain and UX reference; recheck for updates |
| PR #1 reported validation | 41 tests passed, 1 existing ignored; lint 0 errors / 62 warnings | Based on committed QA evidence; **not freshly rerun by this reviewer** |
| Visual acceptance | No real Pixel screenshot certification in PR evidence | Owner's negative UX review overrides any implied design signoff |

The PR and source show a **local, in-memory Compose demo**, NOT a live accounting client. Product records, sale/collection/purchase flows and stock mutations are demo repository behaviors. Scanner and Bluetooth printer are simulations; receipt/statement export may be clipboard only; full cash and full credit checkout are supported, while partial invoice examples exist but new partial checkout is not. Demo data disappears on restart. No real production authentication or authoritative server accounting writes are wired.

**Exact-source rule:** At the start of the job, re-read current repo, PR, branches, tree, relevant docs and `git status`. Any SHA above is a snapshot, not authority over newer commits or local uncommitted work. Never overwrite an active T3/agent working tree.

### Decision precedence

1. This owner's explicit intent and any later explicit decisions.
2. Financial integrity, tenancy/authorization, platform safety and verified current source behavior.
3. The Laravel authoritative domain (`PRODUCT.md`, `DESIGN.md`, `docs/UI_WORKFLOW_RULES.md`, master spec and actual API routes), **read only**.
4. The existing Android demo's verified contracts/tests and preserved core data.
5. This consolidated product design contract.
6. The three older proposals as **idea sources only**, not execution authority.
7. Competitor screenshots, Figma Make, shadcn/BoardUI, material examples as inspiration, NEVER direct source-code replacements or permission to skip validation.

Three source plans furnished by owner:
- `SMALL_TRADER_ANDROID_UX_REBUILD_MASTER_BRIEF.md` — strong safe product guardrails and staged UX reset.
- `SMALL_TRADER_ANDROID_UX_RESET_REVIEW_AND_T3_PROMPTS_2026-11-07(1).md` — strong diagnosis, two navigation alternatives and task-specific follow-ups.
- `agent_master_execution_plan.md` — concrete design ideas and workflow detail, **but several prescriptions are dangerous, premature or inconsistent with verified repository state**. See §2.

### Source-oriented engineering starting points

- `MainActivity.kt`: `TraderScreen` + manual `Crossfade` routing; global `Scaffold`; home quick stock adjustment via `restockProduct(id,10)`.
- `viewmodel/TraderViewModel.kt`: large `TraderUiState` owns navigation, demo finance, basket, customers, suppliers, scanner, printer and overlays; `completeCashSale()` accepts full cash or full credit.
- `data/TraderRepository.kt`: demo `OfflineDemoTraderRepository`, stock/credit/customer collection and purchase/GRN guards. `recordPurchaseBill()` can create a **text-only** bill. `confirmPhysicalGoodsReceipt()` requires a linked expected product and quantity, refuses duplicate receiving; do not imply all purchases can be received through a generic click.
- `ui/components/CommonComponents.kt`: current top bar and bottom bar, explanatory subtitles and multiple controls; `PhaseOneComponents.kt` adds another shared component family.
- Large UI screen files: `HomeScreen.kt`, `NewSaleScreen.kt`, `InvoiceReviewScreen.kt`, `CashPaymentScreen.kt`, `CustomersScreen.kt`, `SuppliersScreen.kt`, `BusinessExtraScreens.kt`, `ProductsCatalogScreen.kt`, `ReceiptScreen.kt`.
- `docs/PHASE1_DESIGN.md`, `PHASE1_VALIDATION.md`, `PHASE1_WINDOWS_ACCEPTANCE.md`, `SURFACE_COVERAGE.md`, `LOCAL_ANDROID_HANDOFF.md`.
- `account/routes/api-v1.php` currently defines **session-authenticated, guard-protected** session APIs, sales invoice draft/update/show/preview/execute and read-only lookups. This is **not** a complete Android authentication, purchasing, inventory, payments, sync or offline command API. Do not invent a mobile token flow or bypass session/CSRF/tenant guards.

### Evidence classification is mandatory

For every claim distinguish **source-verified**, **reported by prior QA**, **observed in real screenshots/device**, **proposed design**, and **untested/integration pending**. A test passing does not certify a pleasant screen. A Figma prototype does not certify a working form. A mock online badge does not certify connectivity.

---

## 2. Synthesis and rejection of bad advice

**Keep from the three plans:** intent-first navigation; central + action; short actions; simplified Home; separate sales/purchase/stock/people/money concerns; progressive disclosure; meaningful Arabic-first copy; role-aware information; truthful demo vs real states; scanner UX; Compose Material 3; sound financial invariants; real device visual review; bounded stages.

**Reject or defer these items, even if a previous prompt called them “mandatory”:**

1. **Do not simultaneously install CameraX, ML Kit, Google Code Scanner, Bluetooth printer libraries, Ktor, JitPack, icon packs, server adapters and DataStore.** Each needs an actual task, evidence, dependency/security review and its own tests. The repo already has CameraX entries in the version catalog but they're commented out of the app. Reuse, don't stack competing scanners.
2. **Do not introduce a “one-tap exact cash” action that instantly posts the invoice merely because an item is in the basket.** Shop-floor speed matters, but a merchant must see the final amount and deliberately confirm actual money received. Optimize to ONE clear final confirmation, not two accidental confirmations or an unguarded shortcut.
3. **Do not show a selectable “Partial” payment mode in new checkout unless the repository genuinely supports and tests it.** Seeded partial invoices are not evidence that a new partially paid invoice can be created correctly. Design future state separately.
4. **Do not fabricate “Connected • Active session” with a green dot.** The app has no real server session. Merchant release experience must show truthful, concise demo status when relevant, such as `نسخة تجريبية — البيانات على الجهاز فقط`; developer details may move to debug-only view.
5. **Do not simply delete every warning that explains a genuine money or stock consequence.** Delete redundant technical prose, but preserve short decision-critical warnings and clear disabled/unavailable state.
6. **Do not place everything back on Home.** The third plan's cash hero + three KPIs + four operations + low-stock + five invoices is still an overloaded dashboard. Reduce, prioritize, test the first viewport.
7. **Do not freeze aesthetics to exact color hexes, 64dp product rows, 46dp keypad buttons, or a dozen pill badges before real screenshots.** Visual tokens are hypotheses; Arabic glyphs, long names, 1.5–2.0x text and touch sizes decide final dimensions. 48dp interactive touch regions are the Android baseline.
8. **Do not force a small inline 180dp live camera view as the only scanner UX.** It steals catalog space, complicates lifecycle/keyboard handling and may scan poorly. Test a dedicated full-screen, continuous scan mode; an inline mode is optional only with evidence.
9. **Do not call a financial purchase bill a physical receipt.** The current demo's text-only bills cannot generically auto-receive; the bounded linked-SKU demo GRN has strict exact-SKU/quantity constraints.
10. **Do not implement real Bluetooth ESC/POS and a remote Laravel adapter during the UX reset.** These are separate integration/hardware qualifications after the core shell/checkout has been accepted.
11. **Do not clone the Laravel desktop BoardUI/shadcn design into the phone.** Borrow its semantic financial discipline and concise wording; Compose's platform navigation/accessibility conventions win.
12. **Do not trust the third plan's version declarations.** Actual checked-in Compose BOM on the reviewed branch is `2024.09.00`, while that plan calls it `2026.02.01`; reconcile source before dependencies. Do not upgrade the build toolchain to chase a design goal.
13. **Do not treat record ID replay as full proof of immutable, exact-payload idempotency.** The current demo repository returns an invoice that matches the supplied invoice ID before comparing a complete replay payload. Preserve existing behavior, document this test/security limitation, and don't claim canonical backend-grade duplicate protection from it alone.
14. **Do not manufacture “perfect” Figma previews.** Earlier Figma Make direction was liked, but visually polished previews were reported to contain inert actions and undersized text. Design quality includes fully usable state transitions and legible controls.

The third plan is useful as an *idea bank*, not an instruction list. The first two are safer foundations, but they need consolidation into a single owner-facing execution path rather than 9 independently drifting prompts.

---

## 3. North-star product contract

**North star:** A shopkeeper unfamiliar with accounting software can identify the correct action, finish it without getting lost, and understand the financial result. The mobile app is not the entire web back office squeezed onto 390dp.

Primary roles: **Owner/Manager** (sell, buy, manage inventory/parties, permitted money and reports) and **Cashier** (sell/scan/collect only as authorized; no leaked costs, owner totals or unauthorized supplier actions).

Core jobs, in merchant language:

| Job / purpose | Direct entry | Successful destination | Core recovery |
|---|---|---|---|
| **Sell** / بيع | Home main CTA; Sales; + | Receipt with number, total, payment status and next sale | Resume unfinished basket; clear item validation |
| **Buy** / شراء | +; visible Purchases entry; supplier detail | Supplier bill status, clearly separated **goods not yet received** if applicable | Edit eligible draft; review unreceived status; no fake GRN |
| **Products & stock** / الأصناف والمخزون | Inventory; + Add Product; Scan | Product saved in demo / detail; truthful stock movement | Search empty state, invalid/zero stock, missing barcode |
| **Customers** / الزبائن | People; + Add Customer; sale customer chooser | Detail/statement; selected customer retained as appropriate | No-result create/search; distinguish owes us vs advance |
| **Suppliers** / الموردون | People; + Add Supplier; Purchase | Supplier detail, payables and transactions | No-result create/search; distinguish payable vs advance |
| **Receive money** / قبض | Customer detail; + | Clearly identified collection result + adjusted customer debt and cash | Invalid/overpayment, double submission |
| **Pay supplier** / دفع لمورد | Supplier detail; + | Payment result + payable/advance behavior | Prevent unauthorized/wrong supplier/payment |
| **Business health** / ملخص اليوم | Home; Reports/Cash | Answer a short specific question with correct time scope | Empty data, role restriction, separate cash vs debt |
| **Expenses / shifts** / مصاريف وصندوق | Management/Reports; + where appropriate | Correct expense or shift count/reconciliation in demo | One relevant confirmation; distinguish book vs counted cash |

These are jobs, not nine permanent bottom tabs. A task may require more than one screen when it prevents a costly financial mistake.

### Metric of simplicity

Measure **findability, task completion, wrong taps, backtracks, screen changes, user hesitation, keyboard burden and time**, in addition to click count. Proposed expectations (NOT measured results): a new sale starts from Home with one clear tap; a known scanned item adds without an unrelated menu; common creations start with + then one choice; Cash requires just one deliberate final confirmation after the user sees due/tender/change.

---

## 4. Navigation: design two genuine alternatives, choose with evidence

**Default recommendation for a sales-heavy small shop: Direction A**, pending an actual pixel/merchant task comparison. This is a **4-destination navigation surface plus a CENTRAL ACTION**, not 5 destinations.

### A — Merchant workspace (recommended starting hypothesis)

**RTL physical order from right to left:** `الرئيسية | المبيعات | [+] | المخزون | [People]`.  
English: `Home | Sales | [+] | Inventory | People`.

- **Home:** store name, today, active sale, 1–3 meaningful exceptions/recent tasks. NOT an accounting module index.
- **Sales:** invoices/drafts/returns (only actually supported); obvious sale action; actionable search/history.
- **+** is a modal quick-action trigger, NOT a route or selected tab.
- **Inventory:** products/stock, with a **first-level, clearly named Purchases section** or conspicuous Purchases launcher; never buried in stock adjustments.
- **People:** visibly distinct `الزبائن` and `الموردون` segments and their separate balances/transactions.
- **Purchases** must also appear prominently in the global + sheet and a clearly discoverable Home entry for purchasing staff; measure whether users can find it without coaching.
- **Reports/Cash/Expenses/Account/Settings** are purpose-specific screens reached through contextual Home/management/account actions, not extra global tab strips.

**CRITICAL wording test:** A bottom label such as `الجهات` may not be natural to nontechnical customers, while `الزبائن والموردون` may be too long. Test `الحسابات`, `الزبائن`, `الجهات` with native Arabic users alongside an icon and immediate destination contents. Do not impose the English word “People” or accountancy jargon on merchants without validation.

### B — Procurement-led shop

**Five permanent destinations** `الرئيسية | المبيعات | المشتريات | المخزون | [People]`, with a single contextual + FAB (not a sixth tab). Purchases becomes undeniably first-class. Risk: small Arabic devices may become cramped; the FAB must not obscure a checkout, keyboard or bottom sheet.

**Run both through the same tasks on 360/393/440dp Arabic, light/dark.** If purchasing is a daily co-primary activity and users struggle to find it in A, select B. Record the choice and why; do not build two entire production apps. No stacked top-level navigation bars. Use a navigation rail on suitably wide tablets if warranted by device evidence.

### Central + sheet

One familiar native sheet, short row labels and semantic icons. **First group (four):**
- `بيع جديد` — New sale
- `شراء جديد` — New purchase
- `قبض من زبون` — Receive customer payment
- `دفع لمورد` — Pay supplier

**Secondary group labeled `المزيد`:** `صنف جديد`, `زبون جديد`, `مورد جديد`, `مصروف جديد` (only if supported/permitted). Do not turn a sheet into a full-screen tile gallery or make every option equally prominent. One tap to open, one selection to land **directly in the task**. Keep placement stable; filter actions by current permission and show meaningful restricted states rather than fake success.

### Header/profile

- Root header: one-line active business identity + avatar, with a simple contextual title when appropriate. Language/theme belong in Settings.
- Avatar opens **Account / Settings / Shift / Help / Diagnostics where real**. No invented login/logout or connected-state green dot. Real logout only once real authentication exists.
- Secondary pages: short title, Back, at most one/two relevant controls. Scan button belongs beside the relevant product search entry as the standard pattern, rather than a competing global permanent header control on every page.
- Full-screen checkout: contextual Back and preservation; hide the root bottom bar when necessary for useful working space. On exit, do not silently discard an active basket.

---

## 5. Core workflow screen contracts

### 5.1 Home: one calm, high-signal viewport

Above the fold on a normal phone: **active store**; **resume sale OR new sale** (one dominant action); for authorized role **sales today** with time scope; 1–3 concise important exceptions/recent operations. Owner may drill into payables, cash, receivables, stock and reports. Cashier must not see unauthorized owner totals. Remove printer/settings duplicates, mini textbooks, repeating action grids and the red multi-item +10 stock card. A low-stock row may navigate to the right stock filter; it must not silently change inventory. The expected cash drawer total is **book cash**, not a physical count.

### 5.2 Sell: scan/search → basket → payment → receipt

**Product picking:** Product search immediately usable and camera affordance beside it. Compact but not fixed-height rows: name, unit, price, stock exception, easy +/quantity. Stable query/categories and selection when navigating. Known barcode adds or opens quantity choice without unrelated screens. Empty search offers clear/reset/create where allowed. Weighted/fractional products are **display-only where the repo does not post fractional quantity**; do not simulate fractional stock accounting.

**Basket:** One sticky total + `مراجعة` CTA, with count and price. Edit quantities/delete. Optional customer, discount, note, draft and quote appear when requested, not as compulsory banners. Preserve draft/quotation nonposting semantics.

**Payment:** Amount **due** is dominant. Full cash by default; show amount received, `بالضبط` shortcut, optional useful denominations, calculated change or insufficiency and **one final `تأكيد البيع`**. For full credit choose a valid customer, show the consequence, enforce credit/role rules. Do not fabricate a third new-partial checkout mode. Never treat entered cash as the amount the merchant physically received unless deliberately confirmed. Do not force a giant bespoke keypad if Android's decimal IME is clearer; test both on real device. Block duplicate submits and recover from errors without losing basket.

**Receipt:** One quiet success state: document number, amount, paid/debt status; next sale and share/print when genuinely supported. A sample rendering is a preview; disconnected Bluetooth is not a successful print. Do not show an enormous paper mock as the success page's sole focus.

**Speed shortcut, safely:** `بالضبط` may prefill received = due, but must NOT post until the cashier taps the single explicit final confirmation. Do not chase “1 tap” by removing financial intent verification.

### 5.3 Purchases: visible independent job

Purchasing deserves its own **purchase list/status and new purchase flow**, even under an Inventory hub. Sequence: choose supplier → add items where demo supports structured lines → quantities/cost → payment terms/paid-now → review and record bill → separately handle **physical receipt** when eligible. Present `لم تُستلم البضاعة`/`تم الاستلام` accurately. Do not automatically credit stock merely because a supplier bill was recorded, or add stock twice.

**Current demo limitation:** `recordPurchaseBill()` accepts `itemsSummary` and financial amounts, typically creating a text-only bill with no `expectedProductId`/`expectedQuantity`; `confirmPhysicalGoodsReceipt()` refuses that bill. Existing seeded linked-SKU bills can be received after matching exact product/quantity and checking duplicate receipt. Agent should **map which cases are genuinely supported before designing affordances**. An unavailable receiving action needs an honest disabled/helper explanation or a navigable applicable manual stock-adjustment path; never pretend that an inventory adjustment is an official bill receipt. Full itemized multi-SKU purchase/GRN is future domain work, not a painted demo trick.

### 5.4 Products & inventory

Search/scan first; product row name + unit + sale price + meaningful stock, not ten metadata chips. Product details expose edit, price, stock status and movement history as permitted. Product creation shows the **minimum fields the current repository can actually accept without manufacturing costs, units, warehouse or expiry**. Optional barcodes/advanced attributes can expand; do not silently invent economic values for missing required fields. Distinguish purchasing, receiving, adjustment and transfer; role-gate cost/margin, warehouse and destructive actions.

### 5.5 Customers, suppliers, debt and collections

People hub uses a clear `الزبائن / الموردون` segmented control. Rows show name and correct balance polarity, not mini financial reports. Customer detail primary action `قبض دفعة` only when appropriate, seller-specific history accessible. Supplier detail primary `دفع لمورد` when appropriate; purchase action contextual. Collection/payment: choose party → amount + `كامل المبلغ` → projected new balance → single confirmation → clear receipt/success. Show customer debt **owed to store** versus store-held **customer advance** distinctly; supplier **payable** versus store **advance to supplier** distinctly. Respect overpayment logic and authorization; don't compress polarity into color alone.

### 5.6 Reports, cash and account settings

Reports answer questions (`اليوم`, `الصندوق`, `ذمم الزبائن`, `الموردون`, `المخزون`) with dates and drill-down. **Invoice total ≠ cash received ≠ change ≠ net cash drawer ≠ customer receivable.** `expectedBookCash = opening float + cash sales net of change + customer cash collections − supplier cash disbursements − shift cash expenses` in this demo's scope. Actual physically counted cash and variance are separate. Confirm any other cash movement sources in current code before claiming this formula is exhaustive beyond the demo.

Settings: **App** (language/theme), **Store** (only authorized real/demo fields), **Sales & Receipts**, **Devices**, **Security/Session** (only actual auth capabilities), **About**. Raw `/api/v1` routes, role impersonation/testing, simulated device IDs and SDK jargon move to **DEBUG-ONLY tools / explicit demo information**, not merchant settings. Do not remove the user's ability to see that data is local/demo when it is relevant to trust.

---

## 6. Native Android design language, not an AI-dashboard skin

Design principles:

1. **Typography beats cards.** Use hierarchy, section separation, empty space and short labels instead of repeated bordered slabs, logos, taglines, excessive pills, colored metric cards or paragraph explanations.
2. **One primary next action per meaningful state.** Secondary actions can be quiet; critical destructive/financial decisions remain clearly distinct.
3. **AR/RTL first, EN/LTR equally functional.** Existing Cairo/JetBrains Mono are implementation baselines, not unquestionable design perfection. Render real Arabic forms and long product names, bidirectional ILS `₪`, Western 0–9 numerals, barcodes and document IDs correctly. Isolate numeric runs LTR without flipping whole rows.
4. **Material 3 + precise customization.** Use native Compose behavior, adaptive typography, coherent tonal surfaces, simple shape scale, one restrained brand accent and truly semantic financial statuses. Light and dark are separately designed. Do not hardcode a new theme palette on faith.
5. **Touch/accessibility:** >=48dp functional hit regions, large-type 1.5x/2x validation, TalkBack labels and traversal, keyboard/IME visibility, predictable Back/predictive Back, no clipped buttons, correct safe-drawing/inset consumption. Avoid overlapping 40dp stepper hitboxes or arbitrary 46dp financial keypad buttons.
6. **Native lists:** Product/party rows should be dense enough to scan but tall enough for real text and touch. Avoid fixed row heights that break at font scaling. Recycle with `LazyColumn`, stable item keys and scoped state; no premature performance assertions.
7. **Real feedback:** short confirmation after success; contextual inline errors with focus/recovery; meaningful empty/loading/offline/unavailable states, but never fake remote fetching in an in-memory demo. Respect reduced motion, provide haptic feedback sparingly.
8. **Phone-first, tablet-adaptive:** on wide screens consider a rail and sale/cart split-pane rather than 2 columns of oversize phone cards. Keep keyboard, bottom CTA and system bars correct at 360–440dp, landscape and large font.
9. **Copy:** write for Palestinian merchants, not accountants or developers. Prefer `بيع`, `شراء`, `قبض`, `دفع`, `صنف`, `زبون`, `مورد`, `الصندوق`; replace technical nouns only when the replacement stays precise. A real warning may deserve one sentence; an obvious icon does not need a subtitle.
10. **No fake visual acceptance.** The owner's real feedback, native Pixel captures and user tasks outweigh code-completion assertions and Figma screenshots.

### Two required product-level visual concepts

Produce A and B **as different navigation/workflow compositions**, not cobalt versus emerald on the same card skeleton. For each: Arabic-first light/dark **Home, Sale Catalog, Quick Add**, plus one Purchasing entry representation, annotated task transitions and 360/393dp layouts. Include **one compact low-end** target and a tablet sketch. Pick a strong recommended direction; list rejected design choices. Real rendered Compose/Pixel images must be identified separately from Figma/handdrawn concepts. A previously liked Figma Make `Small Trader Sales Flow` may inform the exploration, but never take inert prototype buttons as evidence of working interactions or shrink Arabic text to 8–12px merely for the screenshot.

---

## 7. Real camera scanner — mandatory product goal, separate verification gate

Scanner must ultimately be a **real device camera workflow**, not a product picker wearing camera graphics. Design entry beside sale/inventory/product barcode search; protect context so a scan returns to correct task.

**Recommended engineering hypothesis for frequent retail scanning:** CameraX + **bundled ML Kit Barcode Scanning**, one continuous full-screen camera mode with lifecycle/pause handling, on-device decoding, torch, permission denied/retry, known/unknown/duplicate codes, quantity feedback and repeated-item suppression. Bundled model is available on first use without waiting for Google Play Services network model download, at the expense of APK size. **Alternative for infrequent single scans:** Google's permission-less Code Scanner (depends on Play Services/module availability). Evaluate once with real target devices. Do NOT install both approaches merely to claim capability.

Barcode normalization must not corrupt codes, remove leading zeros or confuse product/receipt scanners. Ask no irrelevant camera permission in simulated-only mode. Tests should include EAN-13, EAN-8, UPC-A, Code 128 and any actually required local symbologies; a QR feature must have a real product purpose. Handle scan while basket contains the same item, low/zero stock, unknown code, denied camera, phone call/background, rotation, duplicate frames, no network, screen-reader feedback and no camera. **Real Android camera frames on a real device are the success gate**; emulator simulation is design verification only. If unavailable, state `REAL SCANNER UNVERIFIED` and don't ship it as production-ready.

Do not add Bluetooth ESC/POS now. After product flow acceptance, prototype receipt/document share and printer integration behind an independently scoped, supported-hardware gate.

---

## 8. Critical integrity invariants and API boundaries

**ABSOLUTE:** Do not modify, deploy, push to or run real transactions against `Bassamalsaqqa/account` or its live tenant database. Keep current app in-memory. Backend auth, native permissions, future API contracts, idempotency and exact decimal accounting require **a separate approved integration project**. Laravel remains the future authority over company, user, price, stock, credit, supplier obligations, cash and posting.

Preserve and test:

- No silent negative stock/overselling, aggregate same-SKU basket checks, stale availability and exact item/quantity validation.
- Drafts/quotes do NOT mutate stock or financial ledgers.
- New cash sales require enough tender; credit sales require allowed named customer and credit guards; no phantom partial checkout.
- Change due must be correct; tender, book cash, receivable, total and physical count distinct.
- Collections, supplier bills/payments, advances and payables must have correct directions and idempotent behavior; don't silently convert a payment into a receipt.
- Cashier must not receive owner/manager capabilities or hidden finance through state, navigation or accessible semantics. The demo's UI-only role mock is not a substitute for server authorization.
- Financial bill vs goods receipt vs stock adjustment vs transfer remain distinct. One bill cannot be physically received twice.
- Money represented as `Double` in parts of the Android demo must **not** be extended into a production financial engine; don't opportunistically rewrite everything during visual restructuring. Future integration must use exact decimal string/BigDecimal domain boundaries and canonical server posting.
- Existing repository's invoice replay shortcut should be documented as a limitation rather than certified server-grade payload-level idempotency. Add relevant focused tests when touching checkout; no changes to actual backend.
- Human-readable Arabic error and success copy, never leaked raw `GET /api/v1` or DevTools descriptions on merchant surfaces.
- Returning from a sheet, child screen, scanner or root tab must preserve the parent sale/purchase draft. Process restart remains explicitly a known limitation unless a separate storage milestone is authorized.

---

## 9. Engineering execution in ONE lead T3 conversation

**No uncontrolled army of concurrent agents.** One integrator owns decisions; independent subagents may do **read-only research/QA** or disjoint feature code when actual T3 child execution and separate worktree/file ownership are verified. Do not claim parallelism because providers are configured. If AGY fails, capture actual evidence and continue with an available provider. Never modify HP-worker sandbox/service, `t3dev` identity, pasta/bwrap, KVM, credentials, networking, family media, or GitHub branch protection. Do not globally install agent skills.

### Branch/worktree integrity

- Inspect local working tree, latest `main` and PR #1 first. No reset, overwrite, force-push or auto-merge.
- Preserve PR #1 as a draft. If using its useful implementation as foundation, start a **distinct worktree and branch from its exact current head**, e.g. `ux/merchant-experience-reset`, and explicitly note it is a **stacked** candidate relative to `ux/native-phase1`. Open a draft PR against the correct base so earlier unmerged changes are not misrepresented as new. Never silently rewrite PR #1 or merge both automatically.
- Baseline `git diff`, new branch head, tests, screenshots and local APK hashes are mandatory evidence. Verify no private `.env`, keys, tenant data or SDK artifacts are committed.
- Existing JDK 21/Android SDK reported usable in the `t3dev` sandbox; no need to install a Linux emulator or expose KVM. Windows Pixel emulator is not controlled by T3's remote Linux sandbox.

### Stage 0 — source audit + merchant task contract [read-only]

Map each current task/entry/exit/recovery/permission and feature readiness: **working local demo / interactive simulation / preview only / real unavailable**. Report at least 5 concrete observations from actual Compose code and PR evidence. Reconcile any stale file path, release version or assumption. Identify 2–3 most harmful screens. Preserve baseline build/test evidence. No write beyond new untracked planning evidence on safe agent branch if necessary.

### Stage 1 — two genuinely different concepts + architecture choice [design]

Produce one-page A vs B navigation tradeoff; real screen wireframes for Home/Catalog/+ (AR light/dark) and Purchases discovery. Compare sale/buy/product/collect/pay findability with task clicks, labels, and accessibility constraints. Default to A unless purchasing findability is unacceptable, then choose B. Keep short owner-facing design rationale. **Do not stop at a color palette.**

### Stage 2 — coherent shell / Home / quick add [implementation]

Implement selected navigation and controlled Back; remove competing permanent nav bars; central plus action sheet; clean header/account; task-driven Home; role-aware visibility; honest demo status. Touch `MainActivity`, shared components and navigation code deliberately. Add navigation tests, AR/EN and light/dark screenshots. Avoid duplicated insets and preserve old workflows.

### Stage 3 — entire sell-to-receipt slice [implementation]

Rebuild catalog → basket → review/tender → result with true scan affordance, minimum copy, safe exact-cash shortcut, credit rules, unfinished basket, stock validation, error recovery and receipt actions. Remove repetitive stepper/subtitle/total controls. The financial domain engine is not a design sandbox; preserve and regression-test it. **Produce a usable debug APK from an exact commit and real Pixel acceptance captures.** This completes the first decisive owner comparison gate.

### OWNER VISUAL GATE — STOP BEFORE BROAD REDESIGN/MERGE

Show owner one concise report: before/after real device captures, recorded merchant task walkthrough, decision A/B, precise defects, tests, APK SHA-256, branch/PR. Ask for **accept/revise the product direction**, not a dozen micro-approvals. If rejected, revise shell/sales until understandable. No production backend, security change or merge. The owner must actually be able to try the APK; on HP-worker build/push then rebuild/install in Windows `Pixel_8a_T3` or clearly disclose device access limitation. Don't fabricate images or claim Pixel checks from Robolectric.

### Stage 4 — purchase, products, people, collections [after UX direction accepted]

Rebuild independently legible Purchase/Supplier, Inventory/Product and People/Customer/Supplier flows as described; preserve text-only bills and bounded GRN behavior. Add one focused regression path per change and perform real screenshot check per domain, not a massive late QA pile. Expose unavailable features truthfully.

### Stage 5 — real camera + remaining settings/reports + quality [separate qualification]

Implement and verify real CameraX/ML Kit scanner on actual Android camera hardware once approved; otherwise retain explicitly simulated demo. Simplify reports/settings/shift, role gates and large-screen adaptive navigation. Focused opportunistic code decomposition, not new monolith files or wholesale architecture migration. Add receipts/printer/network ONLY in individually approved later milestones.

### Stage 6 — independent acceptance and PR closeout [NO auto-merge]

Agent independent review plus recorded tests, true source/APK matching and exhaustive task checks. Report remaining blockers and obtain explicit owner authorization before merging or deploying. A high test count is not acceptance.

### Toolchain commands: reuse ONLY existing local toolchain when paths remain valid

```bash
export JAVA_HOME=/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=2"
./gradlew :app:assembleDebug --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
./gradlew :app:testDebugUnitTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
./gradlew :app:lintDebug --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8"
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Run the exact commands actually suitable to checked-out project; report pass/fail, existing skipped tests and lint warnings honestly. A previous build passing does not prove a new branch works. No secret-bearing logs or APKs should be published publicly without inspection.

---

## 10. Quantifiable acceptance matrix (targets, NEVER claim measured unless run)

| Task / property | Minimum evidence / acceptance intention |
|---|---|
| Find Sale, Purchase, Product, Collect, Pay Vendor | 5 uncoached novice merchant tasks; count wrong taps/dead ends; each discoverable |
| Start new Sale | One obvious action from Home to actionable product list; no intermediate generic dashboard |
| Scan known barcode | Real camera result adds/identifies the correct product without navigation maze; camera hardware verified when claimed |
| Cash walk-in sale | Clear due, cash in, change, one final deliberate confirmation; no duplicate issuance |
| Credit sale | Correct customer required, permission/limit and debt impact visible; no unsupported partial mode |
| Basket recovery | Root switching, Back, sheet dismiss, cancel scanner preserve in-session sale |
| New product / customer / supplier | `+` then one named task; required fields only, with accurate constraints |
| Customer collect / supplier pay | Clear before/input/projected after, one confirmation, correct polarity and cash impact |
| Purchase bill & GRN | Financial bill and physical stock receipt are visibly separate; no double/add-wrong-SKU receipt |
| Role changes | Owner/cashier sections and action sheets disclose only permitted data; tests |
| First viewport | Calm hierarchy, key task obvious, no card jungle, at real compact device sizes |
| Accessibility | AR RTL/EN LTR, light/dark, 360/393/440dp, 1.0/1.5/2.0 text, keyboard, insets, system Back, 48dp touch, TalkBack where available |
| Performance | Screen transitions, search latency, scrolling and scan-to-cart sampled on real hardware if described as fast; show device and measured values |
| Financial safety | Stock, credit, reconciliation, idempotency/duplicate, drafts and receipt tests retain correct results |
| Visual signoff | Actual emulator photos/screenshots from EXACT app commit/APK, with dates/device/locale/theme; owner approval |

**Hard NO-GO:** real/demo deception, unsafe financial action, wrong stock change, role leakage, clipped CTA/overlapped keyboard, nonrecoverable sale state, no actual Pixel review, claimed scanner working without camera frames, or untraceable PR/evidence.

---

## 11. Reporting format and handoff artifacts

One accountable lead owns `docs/android-merchant-ux-reset/` (or similarly consistent evidence folder) and reports:

1. **Baseline:** Android branch/HEAD, PR #1 state, Laravel reference SHA, working tree status, exact build environment.
2. **Diagnosis:** 5–8 concise verified source findings and any genuine captured screenshot findings; don't imply source images not actually reviewed.
3. **Product decision:** A vs B, why one wins, labels tested, tab locations for Purchase and People, rejected interaction ideas.
4. **Before/after flows:** task, starting screen, tap path, error recovery, expected final state, whether measured.
5. **Design:** actual native AR/EN light/dark captures and any clearly marked Figma concepts, small design tokens/components, screenshot/artifact provenance.
6. **Code:** changed paths, accidental UX/financial regressions audited, who authored/reviewed, branch/PR links.
7. **QA:** exact `assemble`, unit, lint and device checks; clear errors/warnings; financial and authorization matrices; accessibility and responsive defects.
8. **Delivery:** APK local path and SHA-256, steps for Windows Pixel installation, detailed manual scenario guide, known limitations.
9. **Owner gate:** **READY FOR VISUAL REVIEW / REVISIONS REQUIRED / BLOCKED**. Never “deployed” or “accepted” merely because tests pass.

**First owner-facing deliverable:** a distinctly different and usable Android Home + + + Sales checkout experience, with authentic source-matched Pixel evidence and a staged release plan. Preserve all accounting guards. The owner should instantly see a merchant product rather than a reskinned accounting admin panel.

---

## 12. External research references and exact lessons (not license to clone)

- Android navigation bar: https://developer.android.com/develop/ui/compose/components/navigation-bar — stable compact top-level destinations; don't place 9 modules in navigation.
- Adaptive navigation: https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation — bottom bar on compact devices, rail as width grows.
- Material 3: https://developer.android.com/develop/ui/compose/designsystems/material3 — native components with deliberate tokens and accessible states.
- Accessibility touch: https://developer.android.com/develop/ui/compose/accessibility/api-defaults — minimum recommended interactive target 48dp.
- Insets and keyboard: https://developer.android.com/develop/ui/compose/system/insets-ui — consume insets properly; nested bars/IME can create overlaps.
- ML Kit bundled vs dynamic barcode: https://developers.google.com/ml-kit/vision/barcode-scanning/android — deliberate first-run offline vs APK-size tradeoff; real camera frames required.
- Google Code Scanner: https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner — permissionless single-scan alternative with Google Play Services module considerations.
- Shopify POS workflow: https://help.shopify.com/en/manual/sell-in-person/shopify-pos — cart, discount, customer, payment and receipt belong to the same checkout task.
- Shopify barcode behavior: https://help.shopify.com/en/manual/sell-in-person/hardware/barcode-scanners — camera/barcode should act on product context; no fake scanning screen.

**Competitive research assignment:** Compare actual current workflows (not brand shots) in at least three POS/invoicing apps such as Shopify POS, Square POS, and a merchant-focused lightweight regional POS where documentation is available; identify at most **5 adopted patterns and 5 rejected patterns**, noting which are directly documented vs inferred. Never transplant payment processors, online sync semantics or device support that are not available in Palestine/the current product.

---

## 13. Single-message T3 launch command (copy-paste)

> Read and execute the attached **SMALL_TRADER_ANDROID_MASTER_AGENT_BRIEF_V2_2026-11-07.md** as the single current product/UX reset brief for `Bassamalsaqqa/small-trader-android`. Confirm real current repo/PR/worktree state and inspect the authoritative `Bassamalsaqqa/account` repository READ ONLY. Preserve the unmerged Phase 1 work and all accounting/role invariants. Start with the task and navigation audit, genuinely compare Direction A vs B using Arabic-first small-phone visuals, then implement a clearly superior, coherent Shell + Home + Quick Add + Sell-to-Receipt vertical slice on a distinct safe draft review branch. Work autonomously through research, design, coding, tests and branch evidence, but never merge, deploy, touch live Laravel, alter HP-worker T3 security, pretend hardware is real, or claim visual acceptance without real device screenshots. When Windows Pixel device access is unavailable, produce an exact identifiable APK + manual acceptance package and state the limitation. Deliver a short candid before/after merchant-flow critique, AR/EN light/dark real captured screenshots when possible, true QA status, APK SHA-256 and the owner visual acceptance request. **Do not run the dangerous directives from older plans; this document supersedes them.**

**Final principle:** If your new UI is prettier but a nontechnical shopkeeper still cannot instantly find Buy, Sell, Collect, Products and Supplier, the redesign has failed.
