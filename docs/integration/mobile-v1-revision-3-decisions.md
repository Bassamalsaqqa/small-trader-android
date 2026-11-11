# Coordinated DRAFT contract revision 3

Previous Android Git head: `ec8220934839ade0c4fe5408398ab1a313feb09c` (PR4 rev2).
Previous manifest SHA-256: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659` (preserved unchanged as parent rev2 reference).
Revision 3 manifest SHA-256: `f5c5bdcd125b95511cce866283d534696c1b4480127c506adc60f543aaae6283`.
API prefix remains `/api/mobile/v1`; this is an explicit draft contract revision 3, not a silent mirror rewrite. Status: **PROPOSED NOT LIVE**.

Read the revision 3 section of ADR-0001 for normative contract specifications. Proposal evidence and source anchors were independently verified against PR9 remote `4a249ca7416f8c45478aa2f163df1369d02172ea` (`docs/integration/financial-tax-protocol-proposal.md`) and Account backend commit `f8a0cf7012c4875bec7320d42e0f8dde58593b52`.

### Scope and Boundaries
This task is bounded strictly to schemas, contract documentation, validator, and test fixtures within `docs/integration/mobile-v1/**`, this decision note, and `tools/validate-mobile-contract.py`. No Kotlin, Room, UI, network, backend, runtime, or AGENTS edits are made.

### Distinct Concepts: Contract Revision vs Event Version
Contract revision (Revision 3) and offline event version (`event_version: 2`) are strictly distinct. Revision 3 specifies the overall contract updates (schemas, OpenAPI 3.1, ADR-0001, fixtures, currency minor units). Event version 2 specifies the wire representation of offline sales events. Offline event version 1 is archived for backwards compatibility tests; new offline capture accepts strictly `event_version: 2`.

### Financial Line Schema Amendment
Both online sale preview (`POST /sales/preview`) and offline intake (`POST /offline-events`) lines now require:
1. `tax_rate_id`: Public nullable 26-character Crockford ULID (`^[0-7][0-9A-HJKMNP-TV-Z]{25}$`).
2. `tax_rate`: Exact percentage snapshot string (`0..100`, up to 6 decimal places).
3. `tax_inclusive`: Boolean flag (`true` for gross inclusive, `false` for net exclusive).
4. `conversion_to_base`: Positive exact decimal snapshot string (`^(?:0\.(?=[0-9]{1,6}$)[0-9]*[1-9][0-9]*|[1-9][0-9]{0,13}(?:\.[0-9]{1,6})?)$`) matching the catalog packaging unit conversion multiplier.

### Explicit No-Tax vs. Catalog Null Tax Pair
- Catalog `GET /products` returns `tax_rate: null, tax_rate_id: null` to signify *absence of a product default tax association*, NEVER zero tax or tax exemption. Mature products have no tax columns.
- Explicit No-Tax selection in client UI transmits strictly: `tax_rate_id: null`, `tax_rate: "0.000000"`, `tax_inclusive: false`. Any other combination with `tax_rate_id: null` is rejected by the proposed schema; corresponding server validation remains to be implemented.
- Master Resolution: Non-null `tax_rate_id` resolves to an active tax rate master belonging to the authenticated company, comparing exact rate percentage and inclusive mode inside the posting transaction. Fails closed on mismatch; never guesses a tax master from a percentage alone.

### Currency Minor Units in Bootstrap
Bootstrap response includes `base_currency_minor_units` (integer `0..6`).
- **Source Anchor**: Backend `SalesDocumentRules::price` (`app/Services/Sales/SalesDocumentRules.php`) hardcodes `$currency === 'JOD' ? 3 : 2`. Dynamic currency minor unit parity requires server financial writers and calculators to be qualified against `currencies.minor_units`. No client guessing of currency metadata.

### Deterministic Tax Calculation Parity
Calculations match `SalesLineCalculator.php` (ROUND_HALF_UP at minor units):
- ILS gross 50.000000, 16% inclusive, 2 minor units:
  - `subtotal = 50.000000`
  - `net_before_tax = round(50.00 / 1.16, 2) = 43.100000`
  - `tax_amount = 50.00 - 43.10 = 6.900000` (NOT unrounded floating `6.896552`)
  - `line_total = 43.10 + 6.90 = 50.000000`

### Canonical Stale-Preview Behavior
`SalesInvoiceCommands::computePreviewRevision` fingerprints current product and unit default prices. Price modifications invalidate the preview token even for users holding `sales.invoice.change_price`. The proposed mobile adapter must expose stale-preview conflicts without treating existing internal blocker names as established HTTP guarantees; the client must request a fresh preview. No relaxed stale token rules, no silent refresh.

### Proposed Mobile Authority Boundary
The write transaction revalidates the bearer token, device installation UUID binding, tenant company membership, password epoch, and required permissions. No fabricated browser session proofs.

### Immutable Durable Receipt & Idempotent Replay
- Dedicated mobile receipt table scoped `UNIQUE(company_id, client_event_id)` bound to original `(actor_id, device_id)`.
- Replay with identical payload bytes/digest returns original canonical outcome (`invoice_id`, `payment_id`, `invoice_number`, `grand_total`).
- Altered payload bytes/digest returns generic HTTP 409 Conflict.
- High-concurrency unique insert and row locking require MariaDB qualification tests; schema fixtures assert contract shape only.

### Preserved Historical Material & Legacy v1 Unsupported Mapping
- Legacy v1 financial goldens (`golden-offline-sale-intake.json`, `golden-sale-preview.json`, `golden-canonical-vectors.json`) and schemas (`archived-v1-*.json`) are preserved verbatim as compatibility material without byte or digest mutation.
- New v2 intake accepts only `event_version: 2`. Legacy v1 events encountering v2 intake return HTTP 409 with reason `unsupported_event_version_1`, mapping to client Room outbox `CONFLICT` state without mutating stored bytes, UUID, or digest.
- Offline policy remains disabled (`enabled: false, availability: "unavailable"`).

### Blocking Representation Rollout Decision
Revision 2 and revision 3 currently share `/api/mobile/v1` paths but are not
bidirectionally wire compatible. Revision 2 strict clients reject new response
properties; revision 3 requires fields absent from revision 2 requests/responses.
Separate draft isolation does not resolve deployment compatibility. Before any
consumer repin, backend mirror update or activation, specify and test an explicit
endpoint version or representation negotiation mechanism for both requests and
responses. Do not select the representation by guessing financial intent.
Missing tax identity, calculation mode, conversion snapshot or currency scale
must fail closed; no default or legacy fallback may invent those economic terms.
Existing revision 2 consumers stay pinned until that decision is reviewed.
