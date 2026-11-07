# Lead reconciliation of stage 1 research

Status: provisional A decision; source/task audit still pending. No production implementation or native visual acceptance in this artifact.

## Authority and useful patterns

Retain the master's Direction A exactly: Home / Sales / central + action / Inventory / People. New sale is a task, Sales is invoice/draft access. People immediately exposes Customers/Suppliers with role-appropriate content. + offers New sale, New purchase, Receive customer payment, Pay supplier; secondary creations remain visible/scrollable rather than another menu. Purchase entry is named on authorized Home and first-level Inventory. Labels remain hypotheses; use short plain Arabic rather than claiming native-merchant testing.

Useful candidate patterns from the AGY report: direct product add with stock checks, one sticky basket summary/action, exact cash as input only followed by deliberate confirmation, unfinished basket retained across navigation, and accessible native controls. Camera-in-cart continuity is a later hardware design goal, not a real camera implementation for this slice. Do not duplicate customer/discount controls in catalog AND review/payment merely to copy a competitor.

## Corrections to raw report

The raw research is retained as provenance, **not accepted implementation authority**. Specifically reject or qualify:

1. Its A/B tab definitions drift from the master and omit Inventory or conflate Sales/New Sale. Use master definitions.
2. Claims of measured 360/393/440 or large-font Pass/Fail are unsupported. No native screenshots/measurements were produced. A's four destinations plus action also consumes five physical positions, so it is not automatically more spacious than B.
3. Official Android navigation guidance explicitly permits **three to five destinations**, contrary to the claimed 3–4 limit: https://developer.android.com/develop/ui/compose/components/navigation-bar . B remains a credible alternative until task/pixel evidence says otherwise.
4. Claimed sales/delivery frequency (50–300 sales/day, 1–3 deliveries/week) has no evidence here. Do not use it as merchant research.
5. `شراء / وارد بضاعة` conflates finance with stock. New purchase records a financial bill; receiving remains separate and bounded by actual linked-SKU/quantity guards.
6. Credit-limit recovery by owner override or partial payment is unsupported. Offer changing customer/basket, returning to cash after receiving sufficient money, or cancelling safely. Do not fabricate an override or partial mode.
7. The suggested permanent “data saved on this device” notice implies persistence. Use concise in-memory demo wording that states reset on restart; no fake server/session status.
8. Catalog already has a scanner affordance. Do not present adding it as a new discovery. Simulation must not request camera permission or show real-camera success states.
9. Published documentation links are candidate sources; report statements about haptics/continuous scanning and product limitations are not independently certified by link presence. Lead navigation/inset checks used official Android docs. The Loyverse page failed access via the lead's web tool; do not claim a successful lead fetch.
10. Insets are a source risk, not an observed double-padding bug. Scaffold/bar consumption differs by placement; choose one explicit ownership policy and test actual device/IME behavior. https://developer.android.com/develop/ui/compose/system/insets-ui .
11. Predicted task counts omit listed taps in places. Count entry actions separately from completed financial tasks; never report these as measured speed.
12. This app's absence of partial creation or hardware payment support says nothing about what the independent Laravel product supports. Keep backend read-only and out of scope.

## Native acceptance and implementation constraints

Keep repository/model financial values and posting commands unchanged during shell/sales restructuring unless a verified bug warrants a separately documented fix. One shared navigation/state writer; disjoint Home and sell-screen writers. New form/chooser launch events must be explicit and consumed once, not open an unrelated dashboard. Existing feature access stays available; no new component family or dependency stack.

No real camera/Bluetooth/backend/storage integration, emulator installation, security changes, merge, or approved UX claim. After shell/sales: exact source commit, new build/unit/lint evidence, APK hash, owner Windows Pixel acceptance package and honest device-access limitations. Broader domains wait for owner acceptance.


## Source audit reconciliation and stage 2/3 decision

Both AGY reports and the Huda architecture/concepts are now collected. Lead selects A provisionally for implementation, preserving the exact master roots and central quick-action grouping. Purchase findability and People wording remain owner-test questions rather than proven findings.

Accepted source concerns: overloaded Home/header, Home stock-adjustment shortcut, expense read visibility for cashier, secondary inventory navigation, duplicated checkout presentation and developer-facing copy. Preserve all financial commands and existing role methods. Restricted data should not render in unauthorized destinations or semantics; do not claim in-memory mock roles provide server authorization.

Corrections to SOURCE_AUDIT_RAW.md (not accepted as literal fact):

- ProductsCatalogScreen already wraps product_detail_cost_section in `if (canManageInventory)` around line 775. Its claimed unconditional cost leak is false. Retain and test this guard.
- confirmPhysicalGoodsReceipt returns `Result.failure`; TraderViewModel handles that result and displays feedback. There is no demonstrated thrown/crashing GRN path. Text-only bills are deliberately ineligible; improve honest presentation, do not bypass the guard.
- Partial sample is #INV-1036, not #INV-1041. A partial payment-status model is valid for existing data, not “dead” or permission to invent new checkout.
- `canManageSuppliers() || canViewReports()` currently has the same role truth table as canManageExpenses(); inconsistent naming is maintenance debt, not proof of an authorization bypass. Read visibility and navigation still need gating.
- +10 is an existing role-guarded stock adjustment with a reason, not proven corruption or direct violation of an unrelated backend ADR. Remove it from Home because the new merchant workflow requires deliberate stock actions and precise terminology.
- recordSale is the actual replay method, not the audit's recordInvoice. Document ID-only payload replay limitation without changing the financial engine here.
- Final checkout must say confirm sale, never “confirm and print” when printing remains simulated.

Implementation writers: Huda sole shared navigation/state/shell integration; AGY account1 Home only; AGY account2 NewSale/InvoiceReview/CashPayment/Receipt only. Shared worktree ux/merchant-experience-reset; no overlapping source files, no builds until lead integrates. All preserve source signature compatibility except explicitly agreed optional callbacks. No commits/push from children. Lead owns final integration/review/build/artifact/stacked draft PR and owner gate.

## Latest owner steering — mobile interaction and concise copy

Owner reviewed concept directions and explicitly rejected persistent demo banners such as `نسخة تجريبية — البيانات على الجهاز فقط`, redundant instructions and webapp-style menu clutter. This supersedes the master's suggestion of routine visible demo prose. Normal screens should use clear touch actions, short labels and progressive disclosure. Move reset/demo details to accessible About information; do not imply live connectivity. Keep text only when it helps a real decision or recovery: due/tender/change/debt, validation, deliberate financial confirmation and truthful print/scan availability at point of use. Preserve accessibility labels and financial guards. Proceed through an installable APK for owner testing, without another intermediate micro-approval. All three implementation tasks received this steering; file ownership remains unchanged.

## Independent integrated shell review and corrections

A separate read-only reviewer identified concrete interactions beyond raw AGY audits: global collection success lacked visible feedback on its origin screen; saveable cost-editor flags could remain composed after role downgrade; new-customer action could open over the saved Supplier segment; Customer directory showed cashier aggregate totals; blank product cost fabricated 75% of sale price. Lead corrected each at UI/navigation boundaries: confirmCustomerCollection routes to updated account preserving success feedback, editor render guards and permission-loss flag reset, customer-specific creation route, report-permission aggregate composition, explicit finite nonnegative cost required (including deliberate zero). Posting repository/math remains untouched. MerchantNavigationTest covers result and creation routes; AGY authored MerchantPrivacyTest covering live roles and explicit cost. These are source fixes and written tests, not passed checks until new QA execution.

## Second independent AGY shell review reconciliation

The reviewer source-confirmed the five earlier role/form/result corrections and unchanged repository. Its priority-1 claim that PRODUCT/SUPPLIER quick actions never navigate is rejected: current openMerchantAction explicitly calls navigateTo inside the else branch before setting pendingAction. Lead inspected the exact code. Regression coverage is requested, not an unnecessary routing rewrite. Empty party choosers need concise recovery; unreachable legacy cashier expense notice is optional dead-code cleanup. QA sole source writer received the real recovery fix and pending-action replacement edge case, plus instruction not to apply unsupported review claims. No new device/pass evidence follows from source review.
