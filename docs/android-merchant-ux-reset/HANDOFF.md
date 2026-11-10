# Merchant reset candidate handoff

Status: **READY FOR OWNER TESTING — UX ACCEPTANCE PENDING**.

## Scope and product decision

This candidate builds on unmerged Phase 1 `f8629644617d147b1d0920634b3eaed4e410cfb5` on a separate worktree/branch, `ux/merchant-experience-reset`. Its draft PR must target `ux/native-phase1`. PR #1 and main remain unchanged.

A is the implemented hypothesis: Home, Sales, central + action, Inventory, People. B remains a credible procurement-led alternative. No merchant study or Pixel evidence proves one direction superior. A/B proposed HTML compositions and predicted task paths are in concepts.html and IMPLEMENTATION_PLAN.md; independent research corrections are in RESEARCH_RECONCILIATION.md. Short Arabic label الحسابات remains an owner-testing question.

## What changed for merchant tasks

| Task | Previous candidate | Reset candidate |
| --- | --- | --- |
| Home | KPI/stock cards, operation grid and repeated controls | One sale/resume action, authorized purchase button, one owner sales figure, at most three activity/attention rows |
| Global tasks | Scattered top controls and secondary module strips | Four stable roots and central native quick-action sheet; contextual task forms/choosers |
| Routine sale | Catalog → review → payment always | Catalog/basket → payment; optional sale refinement remains available |
| Refinement | Customer/debt/discount/drafts expanded together | Expandable customer/discount sections and saved drafts; quotation/save actions retained |
| Payment | Large keypad and mixed cash/credit presentation | Cash/Credit mode, optional keypad, exact only prefills, one final confirmation |
| Receipt | Large paper document as main result | Compact outcome and optional invoice details; truthful print simulation/share/copy |
| Interrupt sale | Shared sale/inventory search and fragile form scopes | Separate query/category contexts, safe chooser cancellation, in-session form scopes |
| Cashier | Customer aggregate totals and stale editor risks | Restricted destinations/actions, hidden owner aggregates, cost editor eviction on downgrade |
| New product cost | Blank silently became 75% of sale price | Explicit finite nonnegative cost; deliberate zero allowed |
| Stock alert | Home +10 stock mutation shortcut | Navigation-only targeted low-stock filter |

These are source-verified transitions, not measured speed claims. No routine demo banner or filler instruction text is added; About carries reset information. Decision-critical amounts/errors/confirmations remain.

## Review and execution

Both Google AGY accounts performed actual child work: source/workflow audit, POS research, Home and sale implementations/revisions, privacy regression authoring, independent shell review and combined QA. Huda Codex prepared concepts/architecture and integrated the shell. Lead reconciled unsupported report claims, owned integration corrections, and verified original XML/artifact outputs. Separate read-only Codex reviews found actionable role/form/result and sale-lifecycle defects; corrections are documented in FINAL_REVIEW.md. No agent's prose alone establishes a pass.

Final review added actual-app-shell lifecycle regressions: saved drafts reachable from an empty catalog, and payment mode scoped to a sale generation. The first attempted lifecycle fix still restored Credit; the regression exposed it. The corrected composition/input scoping passed the focused run. Full checks and APK were rerun specifically because production source changed. See VALIDATION.md and evidence/ for final results. SOURCE_MANIFEST.json identifies built source contents; ARTIFACT.json records final binary bytes/hash. Commit attribution is recorded in the PR/lead delivery after committing the unchanged verified sources.

## Preserved functions and boundaries

Repository, seed data and posting calculations remain unchanged. Full cash/full credit, credit limits, stock/stale validation, customer collections, suppliers/payment/bills, bounded linked-SKU GRN, inventory editing/movements, drafts/quotes, invoice history, partial-status review, expenses, shifts/reports and settings/preview paths remain available according to role. No new partial checkout or multi-SKU receiving is fabricated. ID-only replay protection is a demo limitation; models still use Double values with existing decimal-rounding boundaries.

No backend, real authentication, production posting, persistent sync, Bluetooth integration, camera qualification, dependency migration, GitHub CI, emulator installation or host isolation/security change. Real scanning/printing remain unavailable simulations; real scanner requires separate hardware qualification. Data resets on process restart.

## Next milestone and known risks

Use WINDOWS_ACCEPTANCE.md and the NEW reset APK from this worktree, not the earlier Phase 1 binary. Authentic Windows Pixel_8a_T3 captures and merchant task walkthrough are still required. Arabic/English, light/dark, compact/landscape/tablet, 1.5/2x text, TalkBack, IME/insets, touch geometry and responsiveness are not certified by Robolectric semantics. Party choosers currently have no search. Explicit form discard and durable draft recovery remain future work. Legacy domain screens still retain longer copy and existing layouts until stage 4.

Owner must accept or request revisions to this shell/sales direction before the broad purchase/product/people redesign, real camera qualification, merge or deployment. No merge or UX approval is implied by a passing build.
