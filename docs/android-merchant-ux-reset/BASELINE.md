# Stage 0 baseline — Huda Codex — 2026-11-07

## Checkout and provenance

- Prepared worktree ONLY: `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`.
- Initial branch: `t3/fba3c97b`; initial HEAD exactly `f8629644617d147b1d0920634b3eaed4e410cfb5`.
- Initial `git status --short`: empty; no tracked or untracked changes. Baseline diff empty.
- Renamed current branch using `git branch -m ux/merchant-experience-reset`; HEAD unchanged. No new worktree, commit, push or PR.
- Stacked candidate base: `ux/native-phase1`. Preserve draft PR #1: https://github.com/Bassamalsaqqa/small-trader-android/pull/1 . Its open/draft state is owner-brief evidence, not a fresh remote check in this assignment. Local phase1 branch retained untouched.
- Owner brief main snapshot: `ce2bb6ef8c480048036187892850fa6fb8853f6f`; Laravel snapshot `f8a0cf7012c4875bec7320d42e0f8dde58593b52`. Neither remote freshness nor backend source was independently verified here; independent audits belong to lead reconciliation. No backend checkout accessed.
- `MASTER_BRIEF_V2.md` is a byte-for-byte copy of the attachment; its broad execution instructions are bounded by the latest Stage 0/1-only assignment.

## Evidence vocabulary

**SOURCE-VERIFIED** = checked-in source/contracts or evidence-file contents inspected. **PRIOR QA** = stored results, not newly executed. **PROPOSED** = design hypothesis. **UNTESTED** = no execution or integration proof. **DEVICE-OBSERVED** = none in this assignment. HTML is proposed wireframe evidence only.

## Source contracts and diagnosis (SOURCE-VERIFIED)

Paths below are relative to `app/src/main/java/com/example/`.

1. `MainActivity.kt:62` collects one `TraderUiState`, wraps manual `Crossfade` in one `Scaffold`, applies safeDrawing and IME padding, and wires all screens/dialogs. No navigation-library migration needed. Root bar currently includes NEW_SALE; proposed catalog is a task child instead.
2. `viewmodel/TraderViewModel.kt:399` defines roots HOME/NEW_SALE/INVOICES_HISTORY/CUSTOMERS. `navigateTo` at 518 resets root history to Home, truncates history to eight, and prefills tender on payment entry. `navigateBack` at 559 dismisses scanner, printer, custom item, collection, customer, expense, purchase overlays before popping. Root/child classification must change together with shell, not just bottom labels.
3. `HomeScreen.kt` combines register, owner/cashier pulse, low-stock actions, operation tiles and recent invoices. `MainActivity.kt:135` wires restock to `restockProduct(id,10)`. Replace the Home inventory mutation with navigation to a stock filter; do not relabel it as purchasing.
4. `NewSaleScreen.kt:110` uses shared query/category, stable product keys, quantity operations, zero-result recovery and a local basket-expansion flag. Catalog and inventory currently share query/category; splitting contexts is an explicit future integration task. Wide catalog has no persistent cart pane.
5. `InvoiceReviewScreen.kt:122` has editable lines, customer picker, discount, draft and quotation actions. Picker/quotation flags are local `rememberSaveable`; root-level ViewModel Back does not enumerate these local surfaces. Overlay Back arbitration needs explicit tests, including IME first. Current review dock adds navigation-bar padding inside shell safe-drawing content (potential duplicate spacing, not device-confirmed).
6. `CashPaymentScreen.kt` supplies tender field/keypad/denominations and separate cash/credit callbacks. `completeCashSale` at 1053 refuses empty/double submit, requires sufficient cash or named customer for full zero-cash credit; success clears active basket and routes RECEIPT with Home history. Failure retains basket. `بالضبط` must only fill tender, never post. Seeded PARTIAL status in models does not mean new partial checkout is available.
7. `ProductsCatalogScreen.kt` keeps stock filter/create/transfer dialog state locally; `SuppliersScreen.kt` keeps search/filter/create state locally; purchase and payment forms likewise have local input. Crossfade disposal can lose local entry state. No existing durable purchase-draft contract should be promised.
8. `TraderRepository.kt:627` records financial text-only purchase bills without stock mutation. At 679 GRN requires existing unreceived bill, exact linked SKU/quantity and tracked product. Generic new bill receiving is unavailable. At 306 sale ID replay returns the stored invoice before full payload comparison: limited replay handling, not backend-grade idempotency.
9. `TraderModels.kt` uses Int quantities and Double money with BigDecimal rounding at selected boundaries. Weighted metadata is display-only. `UserSession` grants inventory/supplier/expense/report permissions to owner/manager; discount allowed for all demo roles. UI role switching is a mock, not authorization. Supplier list aggregates are present in Compose independent of its create-action gate: audit direct navigation and semantics for cashier leakage before release.
10. `CommonComponents.kt` owns top/bottom bars plus simulated scanner/printer and quick item UI. `PhaseOneComponents.kt` is the second shared primitive family. The old handoff's `ModalSheetsAndDialogs.kt` path is stale: actual modal implementations are in CommonComponents. Avoid another competing component system.

Most harmful redesign targets (PROPOSED priority from source): Home competing tasks and +10 mutation; global header/root routing; review/payment repetition and compact financial rows. No screenshot diagnosis is claimed.

## Task readiness / current routes

| Task | Entry → result | Recovery / permissions | Readiness |
|---|---|---|---|
| Sell | Home/NEW_SALE → review → cash → receipt | Back retains active basket; stale stock/customer credit guards; no partial creation | Working local demo |
| Hold/quote | Review → saved draft / clipboard quotation | Resume refreshes stock and may clamp/drop unavailable quantities; no posting | Working demo / text export |
| Product | Home/catalog → product detail/create/edit | Search reset, positive price, no negative stock; owner/manager writes | Working local demo |
| Customer / collect | CUSTOMERS → detail or collection → updated statement/cash | Positive amount; named party; advances distinct; replay ID limitation | Working local demo |
| Supplier / pay | SUPPLIERS → detail/payment → payable or advance | Owner/manager commands; positive amount; review read-data disclosure | Working local demo |
| Buy | Supplier list/dialog → financial bill | supplier, total >0, 0≤paid≤total; local form inputs | Working local demo; text-only |
| Receive goods | Supplier detail → bounded GRN → movement | exact seeded linked SKU/quantity; no duplicates | Bounded interactive simulation |
| Expenses | Home → expense dialog/list | owner/manager; no edit/reverse after posting | Working local demo |
| Cash/shift | Reports → count/variance/close preview | owner/manager; book vs counted distinct | Interactive simulation |
| Camera / printer | contextual sheets → simulated feedback | cancellation returns to task; real hardware absent | Interactive simulation; real unavailable |
| Returns/transfers/treasury/session | contextual preview surfaces | no real posting/auth/sync | Preview only / real unavailable |

## Versions / prior evidence

SOURCE-VERIFIED configuration: AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2024.09.00, Gradle wrapper 9.3.1, compile SDK 36 minor 1, target 36, min 24, versionName 1.0/versionCode 1, applicationId `com.aistudio.smalltrader.psdukx`; Cairo and JetBrains Mono baseline. Navigation Compose 2.8.9 and CameraX 1.5.0 catalog entries are not proof of active use (camera app dependencies commented). No toolchain upgrade proposed. No separate frontend playbook, UI profile, PRODUCT.md or DESIGN.md found in Android tracked file inventory; PHASE1_DESIGN and supplied router/AGENTS govern.

PRIOR QA: PHASE1_VALIDATION and preserved XML report 41 passed, 1 ignored, 0 failures/errors (XML attributes independently read here). Lint reported 0 errors/62 warnings. Local handoff's 38-test count is stale. Prior toolchain reported Temurin 21.0.13+9 and SDK android-36.1, not freshly executed. Prior artifact JSON records 17,924,393 bytes, SHA-256 `2fc1efe1ee0f4f75c1c395ec2b34de392bf7a5af1a60923099181e637e309159`; it names the earlier baseline, not exact final commit mapping. No new APK exists from this assignment; prior hash is not a hash of these planning artifacts or proof of source/APK identity. No build/test/lint/device run performed as instructed.

## Guardrails and rollout

No production Kotlin, dependency, backend, network/security/system/global-skill or emulator changes. No hooks, new agents, installs, credentials, remote writes, resets, commits, push, PR or merge. Keep in-memory demo honest; no fake connectivity, auth, camera, print or partial checkout. Retain stock/cash/credit/role/GRN guards, nonposting drafts/quotes, Western digits and EN/AR RTL behavior.

Stages: 0 source/task audit + 1 concepts (this handoff only); lead reconciles independent audits first. 2 shell/Home/+; 3 sell-to-receipt with regression checks and exact commit/APK/device evidence. **Owner accept/revise gate after 3 and before 4/5.** 4 purchases/products/people/collections only after accepted direction. 5 real camera requires separate approval and real frames; remaining quality/settings/reports scoped separately. 6 independent acceptance and explicit owner merge/deploy authorization. Production API/auth/decimal integration and Bluetooth are separate projects. Current status: **READY FOR CONCEPT REVIEW; NOT READY FOR NATIVE VISUAL ACCEPTANCE**.

## Planning artifact checks

`cmp` confirmed exact brief copy; SHA-256 `e2dd1842fdbecd253485cd041f00aa84583ec0a33a9289363bb2ec0f735a3317`. Standalone HTML preview rendered at 728 CSS px with no console messages; native/pixel/merchant evidence remains absent. Board deliberately scrolls horizontally to preserve specimen widths; selectors expose concept/theme/360/393 variants. Final HEAD remains baseline, tracked and staged diffs empty, only this documentation folder untracked. Planning files are intentionally uncommitted for lead handoff.
