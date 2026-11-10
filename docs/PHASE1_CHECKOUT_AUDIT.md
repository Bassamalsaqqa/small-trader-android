# Phase 1 checkout and customer ledger review

Baseline `ce2bb6ef8c480048036187892850fa6fb8853f6f`; branch `ux/native-phase1`.

## Existing foundation

Invoice review already supported line adjustment, customer assignment, discounts, held drafts and non-posting quotations. Cash payment already supported tender entry, denominations, exact-amount recovery, cash/credit guards and projected customer debt. Customers already separated debts from advances, offered balance filters, detail/history, editing, statements and receipt collection. These safeguards were not newly invented by the redesign.

## Changes

- Invoice review refines ledger row spacing, borders and quantity controls, draft/quotation presentation and the bottom review action.
- Payment adds an on-screen numeric keypad to the existing tender field and denominations. Digits keep LTR positions in both languages. A backspace key invokes the existing `BACK` command; the existing clear control remains available.
- Customer screens refine typography, financial status treatments and action targets. Phone runs have explicit LTR layout. Existing collection presets and projected debt/advance feedback remain.
- All screens use the established Cobalt/Slate Material color scheme and `TraderTheme.financial` tokens (`income`, `debit`, `warning`). No new dependency, backend or posting logic is introduced.

## Integration corrections

The initial agent handoff contained trailing backslashes on 2,125 Kotlin lines across InvoiceReviewScreen and CustomersScreen. Lead integration removed these invalid source characters before accepting compilation. The initial keypad mirrored digits in RTL and omitted backspace; integration corrected both. This is why static agent assurances are not treated as executable evidence.

## Contracts preserved for validation

Full cash completion requires sufficient tender. The existing customer-account checkout path records full zero-cash credit, subject to named customer, credit-limit and repository guards. Creating a new partially paid sale is not supported by this ViewModel; seeded partial invoices can be reviewed. That remains a workflow limitation, not a completed payment capability. Cash received, invoice total, change and debt remain separate. Collections run through the existing idempotent repository operation and update statement and expected cash; receivables and advances remain separate aggregate figures.

No ViewModel, model or repository change is intended in this UX slice. Back navigation and basket preservation remain with the existing app shell. Data remains deterministic and in memory; hardware and sharing capabilities retain their documented simulation/clipboard boundaries.

## Validation limits

The original implementation agent did not compile or render these changes. Claims of universal 48dp compliance, perfect bidi behavior or complete test-tag preservation are not accepted without checks. See `PHASE1_VALIDATION.md` for actual build/test outcomes. Compact phones, large fonts, keyboard visibility, dark/light and AR/EN layouts require rendered review. The app shell already consumes safe drawing insets; additional screen-level inset padding should be reviewed for duplicate spacing on devices.
