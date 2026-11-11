# Coordinated DRAFT contract revision 3

Previous Android Git head: `ec8220934839ade0c4fe5408398ab1a313feb09c` (PR4 rev2).
Previous manifest SHA-256: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659` (preserved unchanged as parent rev2 reference).
Revision 3 manifest SHA-256: `e8e3fe672a8f7aceb3152c3dd990b4fb137fa517b24038e468659e0fdf5a87a7`.
API paths preserve `/api/mobile/v1` for revision 2 read/auth representations and introduce explicit `/api/mobile/v2` paths for breaking financial endpoints; this is an explicit draft contract revision 3, not a silent mirror rewrite. Status: **PROPOSED NOT LIVE**.

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
- Backend source audit confirms `CommandReceipt` rows ARE durable database receipts written transactionally to MySQL (`command_receipts`), not merely ephemeral or session-bound artifacts.
- However, backend `CommandReceipt` idempotency is scoped by `UNIQUE(company_id, user_id, action, idempotency_key)` with `request_hash = hash('sha256', target_invoice_id . '|' . token)`. This invoice/token-hash scoped identity differs from the required mobile intake identity:
- Mobile intake requires a dedicated durable receipt table scoped `UNIQUE(company_id, client_event_id)` bound to original `(actor_id, device_id)` and spanning API versions.
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

### Proposed Explicit API v2 Boundary
Use explicit `/api/mobile/v2` paths for the breaking representation rather than
custom header negotiation. Keep v1 revision-2 read/auth representations unchanged.
Contract revision 3, API path v2 and offline event version 2 are distinct concepts.
This decision still requires coordinated schema and runtime implementation.

PR88's draft `MobileApiBoundary`, provider registration and exception hook currently
recognize only v1. Mounting that middleware on v2 routes alone would bypass its
prefix-specific checks. A separate reviewed v2 boundary must explicitly cover the
default-OFF switch, authentication, company context setup/reset, input/schema
validation, rate limits, redacted errors and exception handling. V1 staff/device
tokens confer no implicit financial authority; token reuse requires an explicit,
reviewed grant model and transaction-time checks.

The durable receipt uniqueness domain `(company_id, client_event_id)` must span
API versions. Adding an API version to that identity would allow duplicate posting
through different routes. Original actor/device scope and current authorization
must be checked before replay/status disclosure, including unsupported legacy
version responses. No v2 route, middleware or permission is activated here.

### Normative Online Canonical Intent Profile & Preview Token
Online preview (`POST /api/mobile/v2/sales/preview`) computes an explicit canonical intent profile rather than hashing raw request payload omitting context.
- **Field selection**: Authoritative context (`company_id`, `actor_id`, `currency_minor_units`) from route/token + request (`device_id`, `client_event_id`, `customer_id`, `warehouse_id`, `currency`, `lines`, `payment`).
- **Exact Decimal Lexemes**: All financial quantities (`price`, `subtotal`, `net_before_tax`, `tax_amount`, `line_total`, `tax_rate`, `conversion_to_base`, `grand_total`, `tendered_amount`, `settled_amount`, `change_amount`) are preserved as exact validated strings without floating point conversion or implicit rounding.
- **Restricted JCS UTF-16 code unit sorting**: Canonical JSON serializes object keys sorted by UTF-16 code units without whitespace.
- **Digest & Token**: SHA-256 digest is derived from canonical profile bytes (`sha256:93b9aa677275388defe694390ab756d4b08f122087ec084b8dbd8b421ffc45fd` for golden preview v2). Preview token is an illustrative opaque placeholder (`eyJ...sig_...`) representing server binding, without claiming client cryptographic proof.
- **Tampering Rejection**: Any alteration to actor, company, device, event, tax identity, tax inclusive flag, conversion multiplier, decimal lexeme, line ordering, or payment fields alters the canonical profile bytes and digest, causing validation and commit rejection.

### Arithmetic vs Schema Qualification
- JSON Schema enforces syntax, format, and regex patterns only (e.g. ULID regex, exact decimal regex `^[0-9]+(\\.[0-9]{1,6})?$`).
- True financial arithmetic (line subtotal, tax amount, line total, document grand total, tendered - settled = change) requires server decimal computation with ROUND_HALF_UP at qualified currency minor units.
- Contract fixtures and tests verify genuine arithmetic using an exact Decimal calculator helper; they do not assert universal server validation until backend implementation is qualified.

### Normative Online Intent Profile Invariants & Payment Parity
In response to secondary audit review of `construct_online_sale_intent_profile` and `verify_payment_decimal_consistency`, four contract invariants were corrected:
1. **Nullable Payment Normalization & Unpaid Support**:
   - Contract permits omission or explicit `null` for `payment_method` and `cash_account_id` ONLY when `settlement_mode: "unpaid"`. Both forms canonically normalize to `null` in the canonical intent profile.
   - For `full` and `partial` settlement modes, `payment_method` (`"cash"`) and `cash_account_id` (Crockford ULID) are mandatory economic fields and never default to null.
   - Missing required economic fields fail closed; no defaulting occurs.
2. **Mandatory Raw Request Validation & Zero Implicit NoTax**:
   - `construct_online_sale_intent_profile` mandates raw request validation against `sale-preview-request.json` before any projection. Unknown root, line, or payment properties are rejected.
   - Missing `tax_rate_id` on any line item throws `IntentProfileValidationError`, eliminating the previous `line.get("tax_rate_id")` bug that silently defaulted missing tax IDs to implicit NoTax. Explicit No-Tax requires an explicit `null` with `tax_rate: "0.000000"` and `tax_inclusive: false`.
   - Strict context validation enforces exact known keys `{"actor_id", "company_id", "currency_minor_units"}` and rejects boolean as integer (`currency_minor_units`).
3. **Genuine Payment Decimal Consistency & First-Release Online Policy**:
   - `verify_payment_decimal_consistency` enforces strict contract and backend invariants:
     - **First-Release Policy: Positive Financial Grand Total ONLY**: In accordance with orchestrator policy and backend receipt invariants (`PostCustomerPaymentAction::execute` line 209: receipt amount must be strictly positive), the contract requires `grand_total > 0`. All paid, unpaid, and offline sales reject non-positive totals (`grand_total <= 0`); no zero-invoice or zero-receipt workarounds are supported. Zero-total NoPayment sales may be qualified in future canonical revisions but are NOT claimed now.
     - **Exact Currency Minor Units Representation**: Received, settled, tender, and change amounts must represent integer currency minor units in `0..6` using exact `Decimal` arithmetic without rounding (`RoundingMode::UNNECESSARY` parity with `MoneyValues::amount` and `ReceiptRequestValues::decimal`). Trailing zeros (e.g. `50.000000` at minor units = 2) are accepted. Fractional amounts beyond currency minor units (e.g. `0.000001` at minor units = 2 for ILS) are strictly rejected without rounding. Quantities and packaging conversion to base preserve 6-decimal precision, and tax percentages preserve up to 6-decimal percent, independent of currency minor units.
     - **Fail-Closed Numeric Bounds**: Numeric finite/domain bounds are validated before helper execution; helpers fail closed on malformed lexemes, NaN, Infinity, floats, or scientific exponent notations with explicit `return False` invariants without crashing.
     - **Source Currency Qualification Gate**: Backend source inspection reveals divergent minor unit hardcoding: `PostCustomerPaymentAction` hardcodes `['JOD', 'KWD', 'BHD', 'OMR'] ? 3 : 2` while `SalesDocumentRules` hardcodes `$currency === 'JOD' ? 3 : 2`. The contract documents this qualification finding without modifying backend code or claiming master universality.
   - Online payment semantics strictly adhere to backend `ImmediateSaleCommands.php`:
     - `unpaid`: `Decimal(amount) == 0` with `grand_total > 0`. Exact string decimal lexemes (e.g. `'0.000'`) are preserved verbatim without monetary rounding or format normalization. Documented command choice adapter maps EXACTLY to `{"mode": "unpaid"}` only after strict wire validation and digest derivation, stripping wire payment fields from command input without altering wire profile/digest or applying a general null filter (line `tax_rate_id: null` remains explicit canonical NoTax). Unpaid requires neither cash account resolver nor payment date (`unpaidnoresolverneeded`).
     - `partial`: `0 < Decimal(amount) < grand_total` (positive and strictly below grand total). Proposed adapter maps wire amount to canonical `received_amount` AND `invoice_amount` allocation (FX 1, reviewed date, active cash account).
     - `full`: `Decimal(amount) == grand_total` (`grand_total > 0`). Backend source verification of `ImmediateSaleCommands::intent` (lines 185-242) and `ImmediateSaleCommands::paymentTerms` (lines 248-295) baseline `f8a0cf7` identifies that excess wire amount (`amount > total`) is posted as `unallocated_payment_amount` (unallocated customer credit), NOT returned change. Under the orchestrator-selected smallest first-release online policy, FULL wire amount MUST EQUAL grand total; overpayment and underpayment are strictly rejected.
   - **Company-Scoped Cash Account Resolver & Sourced Reviewed Date**: The illustrative canonical adapter requires an explicit company-scoped active cash account ULID -> internal positive integer resolver (`money_account_id: int`) and an explicit reviewed payment date with NO default (eliminating invented dates like `2026-11-11`). The resolver is called only after strict wire validation and scoped to company; missing/foreign/inactive accounts fail closed and never return wire ULIDs under `money_account_id`. Tests inject deterministic explicit resolvers and reviewed dates to prove boundary conditions without live database credentials.
   - **Offline Cash Capture & Audit vs Command Separation**: Conservation `amount_tendered == amount_settled + change_returned` and `amount_settled == grand_total` (`settled > 0`) is preserved. The offline helper result is an illustrative audit mapping, NOT a `PostCustomerPaymentActionDTO`. Offline audit bytes and digest retain `amount_tendered`, `amount_settled`, and `change_returned` separately in `audit`. The canonical receipt builder strictly maps the NET RETAINED settled amount (e.g. tender 60, total 50, change 10 -> net settled 50), never the tendered amount when change is returned. Wire audit fields are never passed unchecked into `PostCustomerPaymentAction`.
4. **Demonstration Scope & Remaining Qualification**:
   - All helper functions and adapters in `tools/validate-mobile-contract.py` (`construct_online_sale_intent_profile`, `verify_online_sale_intent_profile`, `verify_payment_decimal_consistency`, `adapt_offline_cash_to_canonical_receipt`, `adapt_online_payment_to_canonical_choice`, `calculate_canonical_line`) are proposal demonstrations and contract qualification test verifiers, NOT server implementations. Authoritative transactional validation, state transitions, and ledger posting remain exclusively in Laravel backend services.
   - **Candid Qualification Disclosure**: No blanket "financial ready" claim is made. Dynamic multi-currency minor units, MariaDB concurrency locking, live tenant cash account master mapping, and server-side payment DTO construction require dedicated backend implementation and end-to-end integration qualification before financial activation.

