# Stage 1 decision and implementation contract

**PROPOSED; untested merchant/label assumptions.** No merchant sessions, native pixel review or performance measurements. Independent source/POS audits remain lead inputs; this recommendation is provisional until reconciliation. No implementation authorized in this handoff.

## Decision: A, merchant workspace

A puts Home, Sales, Inventory and People around a central action trigger. Checkout becomes a focused child, with search beside scan and one sticky basket action. Purchases has a named Home launcher for authorized purchasing staff, a first-level Inventory launcher and the second + row. B is procurement-led: five destinations including Purchases, a contextual FAB, and Home prioritizing pending deliveries alongside sale. It trades persistent purchase visibility for narrower Arabic labels and FAB/IME interference risk. B catalog uses an open order-ticket composition rather than A's collapsed basket dock. Neither is just a palette variant.

Recommend A for the brief's sales-heavy shop hypothesis. Choose B if uncoached daily purchasing users miss A's purchase entries. Do not implement both apps. People label `الجهات` is a placeholder with mandatory comparison against `الحسابات` and `الزبائن`; none validated. Destination must immediately show `الزبائن / الموردون`; hide supplier segment for cashier. Reject: extra permanent module strip, giant action grids, instant-post exact cash, fake scanner/session, cosmetic multi-SKU purchase entry, and returns presented as supported commands.

### Predicted paths/counts (not observed)

Count explicit taps to task entry from Home, exclude text entry/scrolling. Search/party selection adds steps. Both concepts use identical permission-filtered + groups.

| Task | A path / taps | B path / taps |
|---|---|---|
| Sale/resume | dominant Home action → catalog / 1 | Home sell → catalog / 1 |
| Purchase entry | Home شراء جديد / 1; + → شراء جديد / 2 | Purchases tab → شراء جديد / 2; + → شراء جديد / 2 |
| Purchase history | Inventory → المشتريات / 2 | Purchases tab / 1 |
| Product creation | + → المزيد → صنف جديد / 3 if secondary group collapsed; 2 when visible | same |
| Collection chooser | + → قبض من زبون / 2 | same |
| Supplier payment chooser | + → دفع لمورد / 2 | same |
| People detail | People → segment → party / 2–3 | same |
| One-item cash result | sell → add → review → payment → confirm / 5, exact-tender fill +1 if needed | same; open ticket changes visibility, not posting count |

Keep secondary + group visible when room allows; scroll it at large font, rather than nested submenu. Five novice users should attempt sell/buy/product/collect/pay uncoached; record wrong taps, hesitation, backtracks, keyboard burden, elapsed time and completion. Counts above predict reachability, not ease or financial completion. Purchase form currently needs supplier, summary, total and paid-now, not structured lines. No claim of merchant validation.

## State/navigation architecture: preserve manual routing

Keep TraderScreen/Crossfade/StateFlow and repository boundaries. Add small explicit root/child/overlay policy instead of introducing Navigation 3/NavHost. UI event definitions should be agreed first; no two writers to shared state or shell.

Proposed roots: HOME, INVOICES_HISTORY (Sales), PRODUCTS_CATALOG (Inventory), PEOPLE (new thin hub). Existing CUSTOMERS/SUPPLIERS content reused under hub; add PURCHASES child list/status, not an invented structured purchasing engine. NEW_SALE is a child of remembered originating root. Preserve legacy detail/report/settings routes through explicit parent mapping. Record selected root separately from child screen so opening receipt from history returns to history while completed checkout returns Home. Plus is overlay only, never route/selected root/history entry.

| Event | State transition / invariant |
|---|---|
| SelectRoot(r) | Dismiss task-free overlays; set root, clear child stack; preserve active sale, query, selected customer, discount and tender. No clearBasket callback. Reselect root does not duplicate history. |
| OpenQuickAdd | Retain root/task and draft; one overlay. Back/outside dismiss restores exact parent. Filter unauthorized actions before composition. |
| ChooseAction(task) | Close + atomically then enter direct form/chooser, capture origin. Never show picker behind sheet; unsupported command explanatory disabled state. |
| StartOrResumeSale | If basket exists resume; never call startFreshSale implicitly. Empty sale enters NEW_SALE; preserve query. Separate explicit discard confirmation. |
| Review → payment | Validate nonempty basket; retain selected customer/discount/note. Existing default tender is only an input assumption; final confirm mandatory. |
| ExactTender | Prefill due, no repository command. |
| ConfirmCash/Credit | Existing completeCashSale guards; disable while submitting; repository failure leaves basket + recoverable message; success receipt, clear sale once. No partial control. |
| Back | IME consumes first when shown; topmost local picker/dialog/sheet next; child pops next. At non-Home root return Home, then system exit. Active in-session basket remains. Checkout Back never posts or discards. Test predictive Back and cancel gesture on Android. |
| Scanner | Capture origin/purpose (sale add, inventory lookup, barcode field), cancellation restores parent/query. Keep simulation explicit until separately qualified; no global scanner that always adds to sale. |
| Leave purchase/create form | Proposed in-session form state held above Crossfade by stable task key. Preserve inputs across Back/sheet/root change; explicit discard only clears. Existing forms do NOT guarantee this. No durable/process-death promise. |
| Change demo role | Close restricted overlays, recheck destination and submit permissions, clear restricted selection/projection or return allowed root; do not render hidden costs/totals into semantics. Preserve safe sale content; restricted drafts cannot be submitted. |
| Historical receipt → next sale | Resume existing basket; distinguish from newly completed receipt so history navigation never clears active draft. |

Form drafts are UI inputs, not SaleDraft postings: keep purchase financial entry separate from pending physical goods. No command on tab/Back. Saved sale draft refresh currently clamps stock: show a short changed-items summary before resumed checkout, subject to focused follow-up tests. Collection/supplier payment must show party, before balance, entered amount and projected after with direction; overpayment follows current advance behavior, never silent netting.

## Disjoint ownership for later stages

Names describe future tasks, not agents launched. Lead can run them sequentially; if delegating later, enforce exact file locks. Prefix production paths below with `app/src/main/java/com/example/`.

| Task / stage | Exclusive write set | Interface / handoff |
|---|---|---|
| INTEGRATOR / 2 then 3 | `MainActivity.kt`, `model/TraderModels.kt`, `viewmodel/TraderViewModel.kt`, `ui/components/CommonComponents.kt`, `PhaseOneComponents.kt`, `ui/theme/*`; new `ui/navigation/TraderNavigationPolicy.kt`, `ui/shell/QuickActionSheet.kt`, `ui/screens/PeopleHubScreen.kt`, `PurchaseEntryScreen.kt`; navigation tests | Sole shared-state, callback, permission, Back and integration writer. Stage 2 purchase entry can wrap current text-only dialog; full domain redesign waits stage 4. Establish contracts before feature work. No other task edits these files. |
| HOME / 2 | `ui/screens/HomeScreen.kt`; dedicated new Home semantics test | Render provided state/actions only: sale/resume, authorized today amount, ≤3 exceptions, purchase launcher. Low-stock navigates; no +10 mutation. Submit required callback changes to integrator as notes. |
| SELL / 3 | `ui/screens/NewSaleScreen.kt`, `InvoiceReviewScreen.kt`, `CashPaymentScreen.kt`, `ReceiptScreen.kt`; `NewSaleScreenDesignTest.kt`, `PhaseOneWorkflowTest.kt` | Render search/basket/review/payment/result with existing callbacks; UI-local state only until agreed lifted-state contract. No writes to MainActivity/ViewModel/theme/shared components. Requests land through integrator sequentially. |
| DOMAIN SAFETY / lead verification | Read-only repository/models/tests review initially; any needed `TraderRepository.kt` or `ExampleUnitTest.kt` fix exclusively assigned after findings | Preserve canonical stock, credit, draft, cash, duplicate behavior. Do not mix financial fixes into visual edits. Payload replay limitation needs separate decision. |
| DOMAIN UI / 4+ | `SuppliersScreen.kt`, `ProductsCatalogScreen.kt`, `CustomersScreen.kt`, `BusinessExtraScreens.kt` later by one file owner each | Frozen during 2/3 except integrator-approved minimal launcher wiring. No two writers on BusinessExtraScreens. |

Existing shared components may be reused by Sell; new primitive requests go to integrator rather than duplicate families. Land state/event changes before consumer changes; integrate Home before Sell; rebase no concurrent shared-file cherry-picks. This is intentionally less parallel than splitting the monolithic ViewModel between writers.

## Concept artifact and verification plan

`concepts.html` is self-contained, no remote assets, static wireframes with real HTML review controls only (concept/theme/width selectors). Drawn merchant action labels are not functioning POS controls. Includes A/B Home/catalog/+ / purchase at 360 and 393 CSS pixels light/dark; compact 360 low-height and 440, tablet sketches. CSS px are layout hypotheses, not Compose dp certification. At narrow host widths board can scroll horizontally to retain specimen width. No fake transactions or camera feed.

Before stage 2: lead reconcile source audit and three-app public-pattern audit; settle People wording and purchase frequency with owner. Before stage 3 acceptance: meaningful navigation tests (overlay priority, root reselect, receipt history, basket and form retention, role switch), existing financial regression suite, build/unit/lint with exact commit. Compare AR/EN, both themes, 360/393/440, landscape/tablet, text 1/1.5/2x, IME/system bars, TalkBack, long names and mixed LTR numbers. Check no duplicate insets. Focus rows have ≥48dp intended targets, flexible text heights and no fixed tiny labels. HTML alone verifies none of those Android properties.

After stage 3: exact APK SHA-256 + source commit, installable owner package, authentic before/after Pixel captures and recorded tasks; **owner accept/revise before stages 4/5**. Current scanner REAL SCANNER UNVERIFIED. No API/auth/storage/printer changes without separate scope. No merge/deploy by implied acceptance.
