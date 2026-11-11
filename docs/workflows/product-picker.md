# Shared Product Picker & Creation Workflow Contract

**Document Version:** 1.0.1 (Design & Architecture Addendum)  
**Target Pull Request:** UX Product Picker Improvements (`t3/android-product-picker-ux`)  
**Context:** Multi-platform Commercial UX Alignment (Android Jetpack Compose + Laravel Web)  
**Parent Threads:**  
- Android Parent Thread: `83e6a721-b800-424f-8117-1b95dbcbf33a`  
- Backend Codex Supervisor Thread: [`<t3-thread://v2/32fc517e-67b4-4fec-863a-ec803da43c1a/mcp:launch:b115bd8718ab7f51886265350a7b0857916a47e0a36980a0007bee341c2edd6f>`](t3-thread://v2/32fc517e-67b4-4fec-863a-ec803da43c1a/mcp:launch:b115bd8718ab7f51886265350a7b0857916a47e0a36980a0007bee341c2edd6f)  

---

## 1. Unified Interaction Vocabulary & Task Flow

Both native Android (Jetpack Compose) and Web (Laravel Blade / Livewire / Alpine) follow the identical commercial task flow:

$$\text{Search} \longrightarrow \text{Results} \longrightarrow \text{Unit + Qty Selection} \longrightarrow \text{Line Entry} \longrightarrow \text{Review}$$

### Universal Steps:
1. **Search (`search`):** Tactile search input with clear accessible name, clear button (`Clear`), and inline camera scanner button (when hardware/browser media API is available).
2. **Results (`results`):** High-density, ranked candidate list. Shows product name (Arabic/English), barcode, selling price (or cost for purchase), and stock status strictly by exception.
3. **Unit + Qty Selection (`unit+qty`):** Accessible quantity stepper (`- [qty] +`), selectable unit if multi-unit, and explicit unit cost input for purchases.
4. **Line Entry (`line`):** Immediate local addition to active draft without unnecessary round-trip network/DB transactions.
5. **Review (`review`):** Bounded review dock displaying line items, edit/delete actions, and calculated totals.

---

## 2. Field Keep / Remove Matrix

| Surface | Field / Element | Decision | Rationale |
| :--- | :--- | :--- | :--- |
| **Sales & Purchase** | Product Search Input | **KEEP (Primary)** | Essential entry point; supports barcode, AR/EN names. Placed primary above candidate results. |
| **Sales & Purchase** | Camera Scan Action | **KEEP (Inside Input)** | Inline 48dp trigger; avoids separate detached scanner modals. |
| **Sales & Purchase** | Product Name (AR / EN) | **KEEP (Prominent)** | Two-line soft wrap; primary visual identifier for merchants. |
| **Sales POS** | Selling Price (₪) | **KEEP** | Formatted with Western Arabic digits; explicit currency symbol. |
| **Sales POS** | Stock Badges | **SIMPLIFY (Exception-only)** | Show badge ONLY when low-stock (<= threshold) or out-of-stock. Healthy stock is silent. |
| **Sales POS** | Quantity Stepper | **KEEP** | Inline 48dp tactile buttons (`-`, `+`); immediate line total computation. |
| **Sales POS** | Customer Selector | **PROGRESSIVE DISCLOSURE** | Optional named customer assignment; no invented client-side walk-in without verified canonical company mapping. |
| **Sales POS** | Explanatory Prose | **REMOVE** | Eliminates instructional paragraphs and duplicate basket summaries. |
| **Purchase Creation** | Supplier Selector | **KEEP (Essential)** | Mandatory commercial counterparty for payables and purchases. |
| **Purchase Creation** | Bill Mode Toggle | **KEEP (Essential)** | Itemized Stock Bill vs. Financial-Only Bill distinction required for truthful demo accounting (purchases remain local demo in Release 1). |
| **Purchase Creation** | Product Picker in Bill | **UPGRADE (Add Search)** | Adds fast bounded search placed primary above candidate chips (bounded to top 30 ranked items) with zero-result feedback and 48dp touch targets. |
| **Purchase Creation** | Explicit Unit Cost (₪) | **KEEP (Mandatory)** | Intentional zero (`0.00`) supported; required for accurate FIFO/inventory valuation. |
| **Purchase Creation** | Line Duplicate Items | **KEEP (Invariant)** | Multiple lines with identical product ID permitted (different costs / lots). |
| **Purchase Creation** | Cash Paid Now | **KEEP** | Cash paid from drawer reduces cash balance; remainder posted to supplier debt. |
| **Purchase Creation** | Prototype Timing Note | **TRUTHFUL DEMO NOTICE** | Clarified as local prototype demo notice to avoid promising deferred backend stock pending separate GRN. Account PostPurchaseAction in backend atomically creates payable and inventory receipt (ADR0013). Native purchase-domain alignment is deferred to a separate reviewed slice; current Android purchase flow is explicitly unwired demo. |
| **Purchase Creation** | Redundant Section Titles | **REMOVE** | Eliminates verbose headers like *"بنود الأصناف المشتراة"* and *"التسوية المالية وطرق الدفع"*. |
| **Purchase Creation** | Clear-All Clutter | **REMOVE** | Removes dangerous wipe actions and noisy clutter from line rows. |

---

## 3. Search Contract & Ranking Specifications

### 3.1 Input Normalization
1. **Case & Whitespace:** Trim leading/trailing whitespace; lowercase English strings.
2. **Arabic Character Normalization:**
   - Alef variants: `['أ', 'إ', 'آ', 'ٱ']` $\longrightarrow$ `'ا'`
   - Taa Marbuta: `'ة'` $\longrightarrow$ `'ه'`
   - Alef Maqsura: `'ى'` $\longrightarrow$ `'ي'`
   - Diacritics / Harakat: Remove range `[\u064B-\u065F]`, `\u0670`
   - Tatweel: Remove `\u0640`
3. **Digit Normalization:**
   - Eastern Arabic digits (`٠١٢٣٤٥٦٧٨٩`) and Persian digits (`۰۱۲۳۴۵۶۷۸۹`) mapped to ASCII digits (`0-9`).
4. **Symmetry:** Identical normalization pipeline applies symmetrically to stored index fields and incoming search queries.

### 3.2 Multi-Tier Relevance Hierarchy
Queries evaluate candidates in strict tiered precedence:
1. **Tier 1 (Exact Barcode):** Full exact string match on barcode. Highest priority candidate.
2. **Tier 2 (Prefix Barcode):** Barcode starts with query digits/text.
3. **Tier 3 (Prefix / Exact Name):** Arabic or English product name starts with or exactly matches the normalized query.
4. **Tier 4 (Token Match):** Normalized query is contained within name, unit, or category label.
5. **Deterministic Tie-Breaking:** Ties between candidates with equal relevance scores are broken deterministically by stable collection index.
6. **Metadata Authority Notice:** The Product model exposes barcode, Arabic/English names, unit labels, and category; it contains NO real SKU field. Internal entity IDs are never treated as SKUs.

### 3.3 Boundedness & Safe Execution
- **Zero-Query Behavior:** When search query is empty, catalog display is bounded to the first 30 items with an explicit explanation badge (`عرض 30 من أصل N`) to avoid presenting a silent truncated catalog.
- **Result Clamping:** Results clamped to maximum **30** candidates. Bounded handling for non-positive limits (`limit <= 0`) safely returns an empty list without crashing.
- **Execution Architecture:** Local in-memory search over cached state operates synchronously without artificial delays. Query job cancellation and debouncing pipelines are reserved for future indexed/remote network queries.
- **Allocation Profile:** In-memory collection processing allocates candidate scoring pairs and result lists; bounded to at most 30 elements to minimize GC pressure.
- **Provisional Status:** Shared search contract remains provisional subject to server-side indexed query evidence.

---

## 4. Google Stitch Reference Manifest

All UI components align directly with accepted Google Stitch designs from project `projects/15641450350510420845`:

| Screen Name | Stitch Screen ID | Local Artifact Path | SHA-256 Hash |
| :--- | :--- | :--- | :--- |
| **Home (Merchant Workspace)** | `4d0d36bd3dbf4f3d8528808a89b97321` | `docs/PR3_MINIMAL_DESIGN_ADDENDUM.md` / `round3-review/R3-HOME-SIMPLIFIED-v02.html` | `37aa36b9da4f3fbf45d7b5e08b1f548ea2acfb48fe3f1b1fef38a2a645fb5e5b` |
| **Structured Purchase Bill** | `2e83981a2a8c42dc94b3124e1b814158` | `docs/PR3_MINIMAL_DESIGN_ADDENDUM.md` / `round3-review/R3-PURCHASE-STRUCTURED-v02.html` | `c2869c7333ecfe773571d410696aa1ee345755d0cfe2c5a6295274c15fb78fae` |
| **Goods Receipt Note (GRN)** | `77dde46b35de42da9a850d454d333757` | `docs/PR3_MINIMAL_DESIGN_ADDENDUM.md` / `round3-review/R3-GOODS-RECEIPT-PARTIAL-v02.html` | `d1a31ff70f28f8bdb9e88372653fb8fc7a46de5c9072ba84dcc5a350ffea168c` |
| **Accessible Navigation** | `8ea59e518331415c984e80c1b484e235` | `docs/PR3_MINIMAL_DESIGN_ADDENDUM.md` / `round3-review/R3-BOTTOMNAV-360DP-2X-v02.html` | `7314859cc88c80f390eacb1c2af2dd8d0b13ba8be255da999ab95632050efd48` |

---

## 5. Domain Invariants & Role Security
1. **Cashier Privacy:** Cashiers can never view unit cost or gross profit margins on products or purchases in the shipped UI.
2. **Explicit Cost:** Product cost must never be guessed or silently omitted; explicit entry (including intentional `0.00`) is mandatory. No fake `0.00` fallback on lookup failures.
3. **Demo Boundaries:** Android repository remains an explicit in-memory demo until native API endpoints are wired in a future release. First release backend scope is limited to sales/receipts; purchase integration remains deferred. No labels suggest backend effects until verified with backend supervisor.
4. **Backend Canonical Behavior vs. Local Demo (ADR0013):** In the canonical Laravel backend (`Account` PostPurchaseAction), purchase confirmation atomically creates both the payable AND the inventory stock receipt (ADR0013). The existing Android prototype assumption of deferred stock pending separate GRN is local demo-only. The UX and specs MUST NOT promise deferred backend stock pending separate GRN. Current Android purchase flow is explicitly unwired demo; native purchase-domain alignment will be executed as a separate reviewed slice before live integration.
5. **Duplicate Product Lines Invariant:** Multiple lines referencing the same product ID must be preserved with independent line IDs and line costs.
6. **No Fake Walk-in:** Named customer assignment remains optional; no invented walk-in client defaults without canonical company mapping from backend.
7. **Stock-Tracked Filter Predicate:** Stock-tracked predicate is consistently enforced across all search tiers (including exact barcode) for purchase operations. The Product model contains no active flag in the current cache model; active filtering is not claimed or invented.
8. **Measured Performance Benchmark:** 10,000 synthetic product search latency is measured strictly in-memory on the JVM using the shared `ProductSearch` primitive with fixed seed `Random(42)`. No claims are made regarding backend query or physical device latency, and no ungrounded baseline comparisons are made.
