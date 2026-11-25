# Shared Product Picker & Search Workflow Contract

Defines the unified product search, normalization, ranking, and line-entry contract shared across Android (Compose) and Web (Laravel/Livewire).

## 1. Interaction Flow & Field Decisions

**Flow:** `Search` $\rightarrow$ `Results` $\rightarrow$ `Unit + Qty Selection` $\rightarrow$ `Line Entry` $\rightarrow$ `Review`

- **Search & Scanner:** Accessible search input with clear button and inline 48dp camera scanner trigger inside the input group (simulated on Android until CameraX device qualification).
- **Candidate Rows:** Product name (AR/EN, 2-line wrap), barcode, selling price (or cost for authorized Owner/Manager purchase flows; strictly hidden from Cashiers), and exception-only stock badges (silent when healthy; badge only at low/out-of-stock).
- **Selection & Lines:** Inline 48dp `- / qty / +` stepper, multi-unit selector, duplicate product lines preserved independently (different costs/lots), explicit cost required for purchases (including deliberate `0.00`). No explanatory prose walls or one-click wipe actions.
- **Purchasing Domain Note:** Backend `PostPurchaseAction` (ADR 0013) atomically creates payable + inventory stock receipt; local Android demo purchasing separates financial bill vs single-SKU GRN only in debug demo mode and must never promise deferred backend stock.

## 2. Search Normalization & Ranking (`ProductSearch`)

- **Symmetric Normalization:** Trim whitespace, lowercase ASCII, normalize Arabic Alef (`أ إ آ ٱ` $\rightarrow$ `ا`), Taa Marbuta (`ة` $\rightarrow$ `ه`), Alef Maqsura (`ى` $\rightarrow$ `ي`), strip diacritics (`\u064B-\u065F`, `\u0670`) and tatweel (`\u0640`), and map Eastern Arabic/Persian digits to ASCII `0-9`.
- **Tier Precedence (Deterministic Index Tie-Break):**
  1. Exact barcode match.
  2. Barcode prefix match.
  3. Exact or prefix AR/EN name match.
  4. Token match in name, unit, or category (no internal ID treated as SKU).
- **Bounds:** Empty query shows top `30` items (`عرض 30 من أصل N`); search results clamp to max `30` (`limit <= 0` returns empty safely).
