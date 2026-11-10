# PR #3 Minimal Design Addendum: Deliberate Hierarchy & Noise Reduction

**Document Classification:** Design Sub-Agent Addendum (Uncommitted Working Document)  
**Target Pull Request:** PR #3 (`ux/stitch-native-prototype`)  
**Parent Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Quality Benchmark:** `ST-01-AR-LIGHT-OWNER-NORMAL-v02` & Locked Parent Brand Authorities  
**Binding User Direction:** Simple purpose, simple architecture, simple design, simple implementation. Zero AI slop, zero explanatory filler, zero redundant secondary headers/labels. If a screen requires explanatory prose to make sense, fix hierarchy and layout.

---

## 1. Executive Summary & Direction Alignment

Following direct visual inspection by Owner and Parent of the initial Round 3 generated screens (`R3-*-v01`), concrete quality regressions and unneeded layout chrome were identified:
1. **Home Screen (`R3-HOME-SIMPLIFIED-v01`):** Lost authentic merchant identity and owner avatar in favor of a generic app title and toolbar; wrapped the primary sale action in a giant outlined nested card with verbose headers and duplicated basket chatter; added dashboard nesting and excessive quick-action prose.
2. **Accessible Bottom Navigation (`R3-BOTTOMNAV-360DP-2X-v01`):** Fundamentally inappropriate deliverable type—it was rendered as an artificial engineering test slide / accessibility spec with Compose code and WCAG claim banners inside the mockup rather than an authentic merchant screen.
3. **Structured Purchase Creation (`R3-PURCHASE-STRUCTURED-v01`):** Contained redundant section headers ("بنود الأصناف المشتراة", "التسوية المالية وطرق الدفع"), SKU chips, clear-all actions, lengthy accounting-vs-stock explanation paragraphs, and an oversized CTA.
4. **Goods Receipt Note (`R3-GOODS-RECEIPT-PARTIAL-v01`):** Overburdened with redundant audit headers, "مسودة قيد المطابقة", fake shelf codes ("رف A1"), and multi-line audit lectures.

### Core Remediation Philosophy
- **Minimal means deliberate and polished, not bare or generic.** We returned to the high craft of the benchmark design (`ST-01-AR-LIGHT-OWNER-NORMAL-v02`).
- **Layout and hierarchy carry meaning, not explanatory text.** All instructional paragraphs, secondary taglines, and decorative badges were eliminated.
- **Safeguards remain 100% intact.** Strict financial vs. physical stock separation, required unit costs, role boundaries, touch targets (min 48x48dp), and RTL accessibility tokens are preserved without compromise.

---

## 2. Screen-by-Screen Remediation & Concrete Changes

### 2.1 Screen 1: Simplified Home Screen (`R3-HOME-SIMPLIFIED-v02`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/4d0d36bd3dbf4f3d8528808a89b97321` (Refined from `c7da680fc61c4a02847f4648e5f20573`)
- **Artifacts:**
  - HTML: [`R3-HOME-SIMPLIFIED-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-HOME-SIMPLIFIED-v02.html) (12,853 bytes)
  - PNG: [`R3-HOME-SIMPLIFIED-v02.png`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-HOME-SIMPLIFIED-v02.png) (780x1768 px, SHA-256: `ff75a71bcecf...`)
- **Concrete Removals:**
  - REMOVED: Giant nested outlined card wrapping the primary sale button.
  - REMOVED: Verbose heading "نقطة البيع واستئناف الفاتورة" and redundant status pills ("معلقة").
  - REMOVED: Duplicated basket description ("السلة الحالية غير المكتملة 3 أصناف مسجلة").
  - REMOVED: Floating language/theme switch toolbar from top header.
  - REMOVED: Accordion drawer for shift breakdown and verbose shift commentary.
  - REMOVED: Redundant section header "إجراءات سريعة" and 3 nested dashboard boxes.
- **Layout Simplifications & Restorations:**
  - RESTORED: Authentic top merchant header with storefront container, store name ("سوبرماركت ومحمصة الزيتونة"), merchant subtitle ("أبو كريم (باسم السقا)"), and circular Owner Avatar button ("أك", 48x48dp hit area).
  - RESTORED: Direct, bold full-width "بيع جديد" primary button (52dp height, solid royal blue `#255FD6`, shopping_cart icon, white text). Immediate and unmistakable.
  - RESTORED: Single calm metric card for today's sales ("مبيعات اليوم (9 نوفمبر 2026)" with pill "28 فاتورة", large numeral "3,840.00 ₪", and cash/credit split "المبيعات النقدية: 3,220.00 ₪ • آجل: 620.00 ₪").
  - RESTORED: Clean 48dp tactile operational rows: "المشتريات" with chevron, and "نواقص المخزون: 3 أصناف" with amber warning container.
  - RESTORED: Direct recent operations list ("العمليات الأخيرة" with "عرض الكل" and invoice rows).
  - PRESERVED: Authentic 5-slot bottom navigation bar.

---

### 2.2 Screen 2: Accessible Bottom Navigation Screen (`R3-BOTTOMNAV-360DP-2X-v02`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/8ea59e518331415c984e80c1b484e235` (Refined from `44ab3b17f4944ef4a9d67256b7ae08d6`)
- **Artifacts:**
  - HTML: [`R3-BOTTOMNAV-360DP-2X-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-BOTTOMNAV-360DP-2X-v02.html) (13,549 bytes)
  - PNG: [`R3-BOTTOMNAV-360DP-2X-v02.png`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-BOTTOMNAV-360DP-2X-v02.png) (780x1768 px, SHA-256: `25aef1d38ddd...`)
- **Concrete Removals:**
  - REMOVED: Artificial 2560px presentation canvas.
  - REMOVED: In-mockup engineering lecture banners ("اختبار إمكانية الوصول والتكبير", "محاكاة حية لسلوك واجهة أندرويد الأصلية").
  - REMOVED: WCAG AAA compliance claims, Jetpack Compose code snippets, and TalkBack Ready badges rendered inside the UI.
- **Layout Simplifications & Restorations:**
  - REPLACED WITH: Authentic merchant Sales screen (`المبيعات`) on a 360dp mobile viewport.
  - TOP APP BAR: Screen title "المبيعات" with search, filter_list, and clean filter chips ("الكل (28)", "نقدية (24)", "آجل (4)").
  - MERCHANT CONTENT: Clean list of sales invoices (`INV-1042`, `INV-1041`, `INV-1040`) showing real customer names, timestamps, and status pills.
  - ACCESSIBLE BOTTOM NAVIGATION (Primary Subject of Evaluation):
    - 84dp container height specification providing headroom for scaled text.
    - 5 authentic slots in RTL order: الرئيسية, المبيعات (Active in `#255FD6`), [+] Center Action FAB (48x48dp), المخزون, الحسابات.
    - Guaranteed minimum 48x48dp touch targets for every item.
    - CRITICAL VERIFICATION NOTE: The HTML mockup's `max-w: 360px` and meta viewport do NOT constitute proof of Android 2.0x system font scaling. Actual runtime text reflow, multi-line soft wrap, and layout integrity are validated strictly by Jetpack Compose Robolectric and device tests.

---

### 2.3 Screen 3: Structured Purchase Creation (`R3-PURCHASE-STRUCTURED-v02`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/2e83981a2a8c42dc94b3124e1b814158` (Refined from `3fe102815d2a4ff788627c0c842d0b52`)
- **Artifacts:**
  - HTML: [`R3-PURCHASE-STRUCTURED-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-PURCHASE-STRUCTURED-v02.html) (14,527 bytes)
  - PNG: [`R3-PURCHASE-STRUCTURED-v02.png`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-PURCHASE-STRUCTURED-v02.png) (780x1768 px, SHA-256: `acbfcd8a23e9...`)
- **Concrete Removals:**
  - REMOVED: Redundant section header "بنود الأصناف المشتراة" and "تفريغ السلة".
  - REMOVED: Redundant section header "التسوية المالية وطرق الدفع".
  - REMOVED: SKU chips and "الرمز المصدري" clutter from item cards.
  - REMOVED: Lengthy multi-step accounting explanations and warning lecture paragraphs.
  - REMOVED: Giant verbose CTA button text.
- **Layout Simplifications & Restorations:**
  - TOP APP BAR: Back arrow, "فاتورة شراء جديدة", ref "BILL-2026-089".
  - SUPPLIER CARD: Compact single card displaying supplier name ("مطاحن ومحامص نابلس المركزية") and outstanding balance ("ذمة سابقة: 1,200.00 ₪").
  - STRUCTURED LINE ITEMS: Clean, compact item rows:
    - Item 1: "قهوة برازيلي مطحونة 1 كغم" | Qty stepper [ 20 كيس ] | Unit Cost [ 25.00 ₪ ] (explicit 'تكلفة الوحدة' field label retained; currency symbol alone is ambiguous) | Total `500.00 ₪`.
    - Item 2: "هيل حب أمريكي 500 غم" | Qty stepper [ 10 علب ] | Unit Cost [ 40.00 ₪ ] | Total `400.00 ₪`.
    - Single compact "صنف آخر" button (duplicate plus icon/text removed).
  - CONCISE FINANCIAL SETTLEMENT: Clean 3-line ledger breakdown:
    - إجمالي الفاتورة: `900.00 ₪`
    - المدفوع نقداً: `300.00 ₪`
    - المتبقي لذمة المورد: `600.00 ₪`
  - HONEST NOTICE: Exactly ONE calm, single-line note:
    *"قيد مالي: استلام البضاعة الفعلي للمخزون يتم عبر سند استلام مخزني (GRN)."*
  - PRIMARY ACTION: Solid 50dp button in royal blue `#255FD6`: "اعتماد الفاتورة".

---

### 2.4 Screen 4: Goods Receipt Note (GRN) & Partial Receiving (`R3-GOODS-RECEIPT-PARTIAL-v02`)
- **Stitch Screen ID:** `projects/15641450350510420845/screens/77dde46b35de42da9a850d454d333757` (Refined from `5e72ca7495ef42098e2d050f1489cc93`)
- **Artifacts:**
  - HTML: [`R3-GOODS-RECEIPT-PARTIAL-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-GOODS-RECEIPT-PARTIAL-v02.html) (17,806 bytes)
  - PNG: [`R3-GOODS-RECEIPT-PARTIAL-v02.png`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-GOODS-RECEIPT-PARTIAL-v02.png) (780x1768 px, SHA-256: `cca9183b1847...`)
- **Concrete Removals:**
  - REMOVED: "مسودة قيد المطابقة" subtitle and fake status badges from app bar.
  - REMOVED: Fake warehouse shelf location codes (no "رف A1").
  - REMOVED: Verbose section header "بنود التوريد الفعلي والاستلام" and barcode chatter.
  - REMOVED: Lengthy multi-line audit lectures and redundant warning boxes.
- **Layout Simplifications & Restorations:**
  - TOP APP BAR: Back arrow, "استلام بضاعة مخزني (GRN)", Ref "GRN-0412".
  - LINKED BILL REF: Compact 1-line card: "مرتبط بفاتورة: BILL-2026-089 (مطاحن ومحامص نابلس)".
  - RECEIVING TABLE: Clean, focused item rows:
    - Item 1: "قهوة برازيلي مطحونة 1 كغم" • المطلوب: 20 كيس • المستلم الآن: [ 12 ] • "استلام جزئي (المتبقي: 8)" (calm amber warning container).
    - Item 2: "هيل حب أمريكي 500 غم" • المطلوب: 10 علب • المستلم الآن: [ 10 ] • Pre-submit status: "استلام كامل للكمية المتبقية" (calm neutral/primary indicator; green "تم الاستلام بالكامل" is never displayed pre-submit, as success is only claimed post-commit).
    - Breakdown explicitly distinguishes: (1) Ordered quantity, (2) Previously received quantity, (3) Receiving now, (4) Remaining quantity after this receipt.
  - CONCISE PHYSICAL AUDIT NOTICE: Single honest 1-line note:
    *"أثر المخزون: تضاف الكميات المستلمة الآن للمخزون الفعلي دون تعديل الذمة المالية."*
  - PRIMARY ACTION: Solid 50dp idempotent button: "تأكيد الاستلام وتحديث المخزون" (`#14804A` / `#255FD6`).

---

## 3. Stitch Asset Inventory & Traceability Matrix

All artifact baselines (`-v01`) remain intact. Refined designs are persisted as `-v02` files:

| Screen Code | Iteration | Stitch Screen ID | Dimensions | HTML Artifact | PNG SHA-256 | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `R3-HOME-SIMPLIFIED` | v01 (Baseline) | `c7da680fc61c4a02847f4648e5f20573` | 780x1918 px | `round3-review/R3-HOME-SIMPLIFIED-v01.html` | `72f819175be6...` | Superseded |
| `R3-HOME-SIMPLIFIED` | **v02 (Refined)** | `4d0d36bd3dbf4f3d8528808a89b97321` | **780x1768 px** | [`R3-HOME-SIMPLIFIED-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-HOME-SIMPLIFIED-v02.html) | `ff75a71bcecf...` | **Canonical Authority** |
| `R3-BOTTOMNAV-360DP` | v01 (Baseline) | `44ab3b17f4944ef4a9d67256b7ae08d6` | 2560x2048 px | `round3-review/R3-BOTTOMNAV-360DP-2X-v01.html` | `c9cc2ae7be93...` | Superseded |
| `R3-BOTTOMNAV-360DP` | **v02 (Refined)** | `8ea59e518331415c984e80c1b484e235` | **780x1768 px** | [`R3-BOTTOMNAV-360DP-2X-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-BOTTOMNAV-360DP-2X-v02.html) | `25aef1d38ddd...` | **Canonical Authority** |
| `R3-PURCHASE-STRUCTURED` | v01 (Baseline) | `3fe102815d2a4ff788627c0c842d0b52` | 780x2570 px | `round3-review/R3-PURCHASE-STRUCTURED-v01.html` | `54a54f86724b...` | Superseded |
| `R3-PURCHASE-STRUCTURED` | **v02 (Refined)** | `2e83981a2a8c42dc94b3124e1b814158` | **780x1768 px** | [`R3-PURCHASE-STRUCTURED-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-PURCHASE-STRUCTURED-v02.html) | `acbfcd8a23e9...` | **Canonical Authority** |
| `R3-GOODS-RECEIPT-PARTIAL` | v01 (Baseline) | `5e72ca7495ef42098e2d050f1489cc93` | 780x2160 px | `round3-review/R3-GOODS-RECEIPT-PARTIAL-v01.html` | `9af1945db1af...` | Superseded |
| `R3-GOODS-RECEIPT-PARTIAL` | **v02 (Refined)** | `77dde46b35de42da9a850d454d333757` | **780x1768 px** | [`R3-GOODS-RECEIPT-PARTIAL-v02.html`](file:///home/t3dev/.t3/artifacts/small-trader-stitch-2026-11-08/design/round3-review/R3-GOODS-RECEIPT-PARTIAL-v02.html) | `cca9183b1847...` | **Canonical Authority** |

---

## 4. Verification Integrity & Boundary Notice

Per project reporting integrity guidelines:
1. **Mockups Are Visual Authorities, Not Proof of Runtime Verification:** Generating HTML and PNG in Stitch confirms visual design intent and layout token hierarchy. It does **not** constitute proof of device execution, 2.0x font scaling compliance, or unit test passage. The HTML mockups with `max-w: 360px` and `maximum-scale` meta cannot simulate Android system-wide font scaling; runtime accessibility and reflow are proven strictly by Jetpack Compose Robolectric tests (`MerchantRound3EngineeringTest`, `MerchantHomeTest`) and physical device audit.
2. **Honest Status Representation:** Pre-submit dialogs must never display completed green checks or claim actions are finished before the transaction is committed to the local ledger. Partial receiving is fully bounded by remaining pending quantities.
2. **Untracked Working Document:** This file is left intentionally uncommitted in `docs/PR3_MINIMAL_DESIGN_ADDENDUM.md` to avoid conflicts with active implementation in the worktree.
3. **Prior Shared Plan Untouched:** `docs/PR3_STITCH_REVIEW_PLAN.md` has been left strictly unedited to preserve integrator workflow stability.
