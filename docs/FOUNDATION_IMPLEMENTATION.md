> Historical delegated implementation report. Claims are subject to lead reconciliation; see STITCH_NATIVE_HANDOFF.md and final validation evidence. This report alone does not establish builds, runtime behavior or owner approval.

# Native Foundation & Stitch Prototype Implementation Report
**Document Version:** 1.0.0  
**Date:** 2026-11-08  
**Worktree:** `/home/t3dev/.t3/worktrees/small-trader-android/t3-fba3c97b`  
**Branch:** `ux/stitch-native-prototype` starting from `f4713c0`  
**Target Stitch Reference:** `ST-01-AR-LIGHT-OWNER-NORMAL-v02` (`0e1f63884b964b639b7fb0203a831da8` / Project `15641450350510420845` / Canonical Asset `assets/51afd92dffb8461ab336e6d3d380e71b`)  

---

## 1. Executive Summary & Ownership Boundary

In accordance with `native/ACTIVE_OWNERSHIP.md` and direct orchestration guidelines, this implementation executes the Foundation Native Wave directly inside the assigned isolated worktree branch `ux/stitch-native-prototype`.

### 1.1 Strict Disjoint File Ownership
All file modifications and additions strictly adhere to the owned foundation boundary:
1. `app/src/main/java/com/example/ui/theme/Color.kt` (modified: codified canonical Stitch palette)
2. `app/src/main/java/com/example/ui/theme/Type.kt` (modified: configured IBM Plex Sans Arabic scale, preserved Cairo/Mono aliases)
3. `app/src/main/java/com/example/ui/theme/Theme.kt` (modified: 12dp card / 10dp button geometry, color schemes)
4. `app/src/main/java/com/example/ui/screens/HomeScreen.kt` (modified: faithfully adapted Stitch Home v02)
5. `app/src/main/java/com/example/ui/components/StitchComponents.kt` (NEW: shared Stitch cards, badges, buttons, bidi helpers)
6. `app/src/main/res/font/ibm_plex_sans_arabic_*.ttf` (NEW: 4 official TTF font distribution files)
7. `docs/fontlicense/` (NEW: OFL.txt, LICENSE.txt, README.md with verification checksums)
8. `MerchantHomeTest.kt` (inspected: all existing semantic test tags, role privacy checks, and assertions fully preserved)

Zero modifications were made to non-owned files (`CommonComponents.kt`, `MainActivity.kt`, ViewModels, Repositories, or Gradle scripts).

---

## 2. IBM Plex Sans Arabic Font Provenance & Integrity

The official font distribution bundle was fetched directly from the upstream authoritative Google Fonts Open Font License repository (`google/fonts/ofl/ibmplexsansarabic`).

### 2.1 File Characteristics & Verification
- **Digital Signatures & Tables:** Verified via native Linux `file` utility as TrueType Font data (19 tables, Microsoft language 0x409, copyright 2017/2019 IBM Corp., SIL OFL 1.1).
- **Embedded Weights:**
  - Regular (400): `ibm_plex_sans_arabic_regular.ttf` (Weight 400)
  - Medium (500): `ibm_plex_sans_arabic_medium.ttf` (Weight 500)
  - SemiBold (600): `ibm_plex_sans_arabic_semibold.ttf` (Weight 600)
  - Bold (700): `ibm_plex_sans_arabic_bold.ttf` (Weight 700)
- **SHA-256 Checksums:**
  - `ibm_plex_sans_arabic_regular.ttf`: `6f611412270a132bbac838da9259d4c68569b4175f3b3b8fa3fa36a30b56dab9`
  - `ibm_plex_sans_arabic_medium.ttf`: `b8363ab9f733dfa4f8e96b8b2102c24b5cf4110fb96d1d3d9a9412f6fb49cf74`
  - `ibm_plex_sans_arabic_semibold.ttf`: `597bd5502e5997be4414e4c9c88834b30ff3784250c84f20bce2b20e53ebd467`
  - `ibm_plex_sans_arabic_bold.ttf`: `691e0c891a38637ae6bbdb69700f8042cb0724a137bee615068ffdb92244f61f`
- **License Documentation:** Archived at `docs/fontlicense/OFL.txt` and `docs/fontlicense/LICENSE.txt`.

### 2.2 Typography Scale & Backward Compatibility
- Canonical app typography scale updated in `ui/theme/Type.kt` using `IbmpPlexSansArabicFontFamily`.
- All styles configured with `fontFeatureSettings = "tnum"` for tabular lining numerals.
- Arabic microtype guard enforced: minimum size is 12sp (`labelSmall` = 12sp, superseding prior 11sp proposal).
- `CairoFontFamily` and `MonoFontFamily` preserved as public exports for existing feature callers and tests.

---

## 3. Stitch Design System Tokens & Hierarchy Mapping

### 3.1 Color Tokens (`ui/theme/Color.kt`)
- **Primary Brand:** `#255FD6`, pressed `#1F52BD`, pale container `#EEF4FF`
- **Canvas Ground:** `#F7F9FC`
- **Surface Elevation:** Pure White `#FFFFFF` with 1dp subtle border `#E4E9F1`
- **Soft Wells / Input Background:** `#F5F7FB`
- **Text Ink Hierarchy:** Primary `#172033`, Secondary `#475467`, Muted `#526174`
- **Financial Status Accents:**
  - Paid / Success: `#14804A` (container `#E8F5E9`)
  - Partial / Warning: `#A15C00` (container `#FFF5DF`)
  - Debt / Danger: `#B42318` (container `#FFF0EE`)
- **Dark Mode Palette:**
  - Sky cobalt `#7EB3F7` paired with `#0B2545` dark navy ink (guaranteeing high contrast; no white on `#7EB3F7`).
  - Three-tier architectural slate surfaces: Canvas `#161B22`, Cards `#1E2530`, Containers `#283140`.

### 3.2 Component Geometry (`ui/theme/Theme.kt` & `StitchComponents.kt`)
- **Cards & Sheets:** 12dp corner radii (`RoundedCornerShape(12.dp)`), 1dp border (`#E4E9F1`).
- **Buttons & Inputs:** 10dp corner radii (`RoundedCornerShape(10.dp)`), minimum 48dp touch bounds.
- **Gutters & Spacing:** 16dp horizontal screen gutters, 8dp/14dp spatial rhythm.
- **BiDi Utilities (`StitchBidiUtils`):**
  - Isolates alphanumeric identifiers to prevent RTL punctuation inversion: `StitchBidiUtils.formatInvoiceCode(1042)` renders `فاتورة INV-1042` rather than `#INV-1042`.

---

## 4. Stitch Home Screen Adaptation (`ST-01-AR-LIGHT-OWNER-NORMAL-v02`)

The implementation in `ui/screens/HomeScreen.kt` faithfully mirrors the approved Stitch Home layout:

1. **Quiet Store Header:** Respects the shell-owned top app bar (`TraderTopBar`), eliminating redundant headers.
2. **Dominant Register Hero CTA:**
   - Empty basket: Full-width primary blue button (`hero_new_sale_button`) labeled `بيع جديد` (min 52dp height, 10dp radius).
   - Active basket: Full-width primary blue button (`home_resume_review_button`) labeled `متابعة البيع` with item count and live basket total.
3. **Tactile 48dp Secondary POS Actions:**
   - Two equal-width 48dp buttons in a row: `hero_scan_barcode_button` (`مسح باركود`) and `home_quick_custom_item_button` (`صنف سريع`).
4. **Authorized Purchases Action:**
   - Concise utilitarian card (`home_purchase_button`) labeled `المشتريات` with transport icon and chevron.
   - Gated by role (`canManageSuppliers`); strictly hidden for Cashier.
5. **Single Dated Sales Metric (Owner Pulse):**
   - Clickable card (`home_reports_button`) showing `مبيعات اليوم (<date>)` and neutral badge `<N> فاتورة`.
   - Large prominent sales total (`todaySalesTotalIls`).
   - Gated by role (`canViewReports`); strictly hidden for Cashier.
   - **Lead Reconciliation Honored:** Strictly NO physical or book cash footers (the unverified 3,220 / 620 split is excluded).
6. **Compact Low Stock Alert:**
   - Bordered card (`home_catalog_button`) with warning amber container, item count, and item name preview.
   - Tapping navigates to catalog.
   - **Lead Reconciliation Honored:** Strictly NO inline restock mutation controls (+10 button eliminated).
7. **Recent Invoices Section:**
   - Header with `عرض الكل` link (`view_all_invoices_button`).
   - Bordered 12dp card hosting up to 2 recent invoices with row dividers.
   - Each row (`recent_invoice_<number>`) displays customer name/walk-in, bidi-safe invoice code (`فاتورة INV-1042`), net total, and status badge:
     - Fully paid: `مدفوع` (Success green)
     - Partial payment: `سداد جزئي` (Warning amber) with explicit paid and remaining breakdown
     - Unpaid credit: `ذمة` (Danger red)

---

## 5. Verification Boundary & Unverified Checks

In strict adherence to instructions:
- `git diff --check` executed and verified: **CLEAN (0 errors, no whitespace issues, no conflicts)**.
- **No Gradle build was run.**
- **No commits or pushes were performed.**
- **No test completions or runtime verification are claimed at this stage.**
