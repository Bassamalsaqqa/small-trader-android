# Phase 1 native Android design direction

Baseline: `ce2bb6ef8c480048036187892850fa6fb8853f6f`. Branch: `ux/native-phase1`.

## Diagnosis

The baseline is already a substantial native Compose demo, rather than an empty AI Studio mockup. It has a repository, a single StateFlow ViewModel, stock and role guards, draft sales, three invoice payment states, customer collections, suppliers, financial bills, bounded physical goods receipts, expenses and shift reconciliation. Preserve those behaviors. Repository state resets after process restart; it is not a production ledger.

Home already had a sale launcher, role-aware pulse, stock alerts, operations and recent invoices. The catalog already used searchable rows, quantity controls, category filters, search recovery and a basket drawer. Phase 1 refines this foundation; it does not introduce all these capabilities for the first time. The principal design issues are competing dashboard actions, crowded horizontal controls, repeated card decoration and inconsistent space allocated to primary tasks. Payment and customer surfaces are part of the same integration slice.

## Two directions considered

| Direction | Character | Tradeoff |
| --- | --- | --- |
| Cobalt and slate | Quiet blue actions, neutral surfaces, green cash, red debt and amber exceptions | Clear distinction between an action and a financial status; preserves established Android tokens |
| Market emerald and warm stone | Green actions, warmer neutral surfaces, terracotta warnings | A credible warmer alternative, but needs extra care separating brand actions from settled payment states |

Select cobalt and slate for continuity and clear action/status separation. This is a design judgment, not a measured claim that green branding causes errors or fatigue. Material 3 Expressive is useful where component feedback helps a task; no additional expressive dependency or decorative motion is needed here.

## Tokens retained and refined

| Role | Light | Dark |
| --- | --- | --- |
| Primary / on primary | `#1D4E89` / white | `#7EB3F7` / `#0B2545` |
| Canvas | `#F4F6F9` | `#161B22` |
| Surface | white | `#1E2530` |
| Primary text | `#111827` | `#F0F4F8` |
| Secondary text | `#475569` | `#A6B4C6` |
| Cash/income | `#156D49` | `#5CC894` |
| Debt/error | `#B42318` | `#F0755E` |
| Stock/partial warning | `#9A5208` | `#F2B249` |

Use matching financial container/on-container pairs from `Color.kt`. Status must have a text label, not color alone. These token values are implementation facts; this document does not certify every rendered text or control contrast pair.

Cairo remains the primary Arabic/English typeface, JetBrains Mono is available for codes. The existing 14–16sp body/title range remains; `labelSmall` increases from 11.5sp to 12sp with 17sp line height. WesternFormatters remains responsible for Western digits and ILS. Compose start/end placement handles mirroring; mixed Arabic/code/amount runs still need rendered verification. Browser CSS and iOS input zoom rules do not apply to this native app.

Keep the existing Material shape scale (6/10/12/16/22dp), use 12–16dp content gaps, restrained borders and low elevation. Prefer minimum heights over fixed text-bearing heights. Target 48dp interactive areas and allow text wrapping; touch and large-font coverage require validation rather than blanket compliance claims.

## Implemented Home and catalog changes

Home prioritizes the active basket or sale launcher, role-aware financial pulse, stock exceptions, store operations and recent invoices. At 700dp and above the content below the register header uses two columns. Below that it uses one scrolling column. Store-wide financial details remain role gated. Stock additions remain explicit repository-backed demo actions; they must not imply supplier purchases or goods receiving.

The catalog retains searchable product rows and exception-based stock labels, category navigation, custom items, inline quantity adjustment, a sticky review action and expandable basket. A permanent tablet order-ticket pane is **not implemented**. Search recovery offers clearing, looking across categories and adding a custom item. Payment/customer changes are documented in `PHASE1_CHECKOUT_AUDIT.md` when available.

`PhaseOneComponents.kt` supplies shared card, heading, metric, badge, stepper and operation-tile primitives. Prefer actual reuse and existing components over parallel visual systems. Further consolidation should follow evidence from the integrated screens.

## State and workflow contracts

- Search with no results must offer a way to recover; stock limits disable further additions and retain feedback.
- Empty baskets cannot proceed to payment; drafts and quotations must remain available without financial posting.
- Invoice total, cash received, change and remaining customer debt are different figures. Partial or credit payment requires the existing customer and credit-limit guards.
- Customer debt and advances remain separate; collections affect the demo cash drawer and statement through existing repository operations.
- Financial purchase bills remain separate from physical stock receipts. Expected book cash remains separate from physically counted cash.
- No new loading/network states are invented for synchronous in-memory actions. Existing errors, disabled controls and completion feedback remain meaningful.
- AR/RTL, EN/LTR, dark/light, keyboard, compact/large widths and font scaling require actual build/test/render evidence. See `PHASE1_VALIDATION.md` for results and unresolved coverage.

## Research and authority

The Android repository has no separate checked-in frontend playbook, UI profile, PRODUCT.md or DESIGN.md at baseline. The supplied AGENTS instructions and `ui-ux-design` router govern this work. The accounting repository is read-only domain reference, not an Android framework or frozen API contract.

Sources read or used for reference:

- [Android mobile design](https://developer.android.com/design/ui/mobile)
- [Compose Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Adaptive Compose apps](https://developer.android.com/develop/ui/compose/build-adaptive-apps)
- [Official Android skills](https://developer.android.com/tools/agents/android-skills): adaptive layout guidance is relevant; XML migration, AGP migration and Navigation 3 migration are unnecessary scope here. No global skill installation or configuration changes.
- [Accounting product](https://github.com/Bassamalsaqqa/account/blob/main/PRODUCT.md)
- [Accounting design reference](https://github.com/Bassamalsaqqa/account/blob/main/DESIGN.md)
- [Accounting workflow rules](https://github.com/Bassamalsaqqa/account/blob/main/docs/UI_WORKFLOW_RULES.md)
- [Accounting master specification](https://github.com/Bassamalsaqqa/account/blob/main/docs/SMALL_TRADER_ACCOUNTING_MASTER_SPEC_v1.0.md), especially inventory traceability/negative stock, sales invoices, partial payments and customer payments.

No named competitor product was independently evaluated. Do not present this work as a completed competitive field study. Hardware scanning/printing, live authentication, backend integration, durable storage and production posting remain outside Phase 1.
