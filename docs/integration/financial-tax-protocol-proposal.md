# Source-Backed Financial Tax Protocol Proposal

**Document Path**: `docs/integration/financial-tax-protocol-proposal.md`  
**Document Status**: SOURCE-BACKED DESIGN PROPOSAL ONLY  
**Authority Boundary**: Design specification only; NO implementation now, NO financial activation, NO frozen contract/schema edits, NO Room migrations.

---

## 1. Executive Summary & Provenance Baseline (Facts)

### 1.1 Verified Repository State
- **Backend Current Main**: Commit `f8a0cf7012c4875bec7320d42e0f8dde58593b52` (`Bassamalsaqqa/account` at `/home/t3dev/projects/account`, clean read-only HEAD).
- **Android READ Contract Rev 2 (PR 4)**: Head `ec8220934839ade0c4fe5408398ab1a313feb09c` (`manifest.sha256`: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`).
- **Android Contract Primitives (PR 5)**: Head `810c491af4ac9df2918c67072f57a395106eba64` (drift prevention, exact decimal primitives).
- **Android Room Outbox Foundation (PR 6)**: Head `456bfd39a362f9d3de745f1cf12d918263202dc9` (Room outbox, composite `(company_id, client_event_id)` PK, 6-state status machine, immutable BLOB payload).

### 1.2 The Financial Protocol Blocker
Mobile contract revision 2 lacks line tax identity (`tax_rate_id`), calculation mode (`tax_inclusive`), and packaging conversion snapshot, blocking deterministic financial reconciliation between Android offline/online sales and canonical Laravel accounting.

---

## 2. Source Facts & Existing Invariants (Facts)

1. **Frozen Endpoint & Wire Schemas**:
   - The frozen status query route in OpenAPI is `GET /api/mobile/v1/companies/{companyId}/offline-events/{clientEventId}/status`.
   - Intake schema `schemas/offline-sale-intake-request.json` enforces `event_version: 1` with strict line keys (`product_id`, `product_unit_id`, `unit_id`, `quantity`, `unit_price`, `tax_rate`, `tax_amount`, `line_total`) and `additionalProperties: false`. It rejects v2 payloads and extra keys until a coordinated contract revision.
   - Frozen reconciliation outcome field names in `schemas/sale-event-status-response.json` and `schemas/offline-sale-intake-response.json` are `invoice_id`, `payment_id`, `invoice_number`, and `grand_total`.
2. **Backend Guard Reality**:
   - `SalesActorGuard::lockAndAuthorize(int $companyId, User $actor, string $permission)` performs a singular permission check under company lock.
   - `CommandSessionGuard::authorize(User $actor)` exclusively owns database session locking, session age, idle timeout, and freshly queried password verification (`hash_equals`).
3. **Draft Revisions & Tax Snapshot Checks**:
   - `SalesInvoiceCommands::computeDraftRevision` binds lines into a SHA-256 draft revision containing `tax_rate_id`, `tax_rate_snapshot`, `tax_inclusive`, and `unit_conversion_ratio`.
   - `tax_snapshot_changed` is currently an internal blocker code returned inside `SalesInvoiceCommands::buildPreviewData` `$blockers[]`, NOT an implemented mobile HTTP response or contract guarantee.
4. **Currency Minor Units Discrepancy**:
   - Database table `currencies` contains integer `minor_units`, but `SalesDocumentRules::price` currently hardcodes `$currency === 'JOD' ? 3 : 2`.
5. **Packaging Conversion Gap**:
   - Backend `product_units` stores `conversion_to_base`, but frozen offline event v1 lines omit conversion snapshots. A catalog digest alone cannot reconstruct historical ratios upon sync without explicit line snapshots or retained server catalog snapshots.
6. **Room Database & Parser Reality**:
   - Room table `outbox_events` stores generic BLOB `payload_bytes` and integer `event_version`. Generic BLOB storage physically accommodates v2 without SQLite migration or Room schema bump.
   - However, current typed Kotlin v1 capture models and parsers (`OfflineSaleIntakeRequest`, `StrictJsonParser`) reject v2 payloads until separately reviewed.
   - Room `OutboxStatus` defines exactly 6 states (`PENDING`, `IN_FLIGHT`, `UNKNOWN_ACK`, `CONFIRMED`, `CONFLICT`, `AUTH_BLOCKED`). There is no persisted `unsupported_version` status; unsupported v1 events map to `CONFLICT` with a safe reason string.

---

## 3. Proposed Financial & Offline Event Protocol (Proposal)

### 3.1 Line Tax Identity & Explicit Selection
- **Proposed Line Tax Fields**:
  - `tax_rate_id`: nullable ULID `char(26)` (`^[0-7][0-9A-HJKMNP-TV-Z]{25}$`). Resolves server-side to active `tax_rates.id` scoped strictly by `company_id`.
  - `tax_rate`: exact snapshot percentage string (`0.000000` to `100.000000`).
  - `tax_inclusive`: boolean calculation mode (`true` = inclusive, `false` = exclusive).
- **Tax Association Semantics**:
  - Catalog `tax_rate: null, tax_rate_id: null` denotes *absence of default tax association*, NEVER zero tax.
  - **New Proposed Rule**: Explicit "No Tax" UI selection transmits `tax_rate_id: null`, `tax_rate: "0.000000"`, `tax_inclusive: false`.
- **Permissions & Stock Guards**:
  - Price override permission `sales.invoice.change_price` is preserved; actors with this permission do not trigger conflict on authorized custom prices.
  - Stock availability relies on canonical `StockAvailabilityQuery` at `$issueDate`, avoiding blanket catalog conflicts for non-stock items.

### 3.2 Packaging Unit Conversion Snapshot
To detect conversion changes between offline capture and sync:
- **Proposal**: Introduce an immutable line field `conversion_to_base` (matching catalog regex `^(?:0\.(?=[0-9]{1,6}$)[0-9]*[1-9][0-9]*|[1-9][0-9]{0,13}(?:\.[0-9]{1,6})?)$`) in event v2, OR establish server retention of historical catalog snapshot versions.
- **Boundary Rule**: These semantic wire additions must be incorporated into the canonical digest, event v2 schema, and a coordinated contract revision 3. No frozen revision 2 contracts are modified now.

### 3.3 Verified Event Sample & Decimal Parity
Deterministic calculation vector (`SalesLineCalculator.php` parity, ILS gross 50, inclusive 16%, minor units 2):
- `net = 50.00 / 1.16 = 43.10` (`HALF_UP`), `tax = 50.00 - 43.10 = 6.90` -> `tax_amount = "6.900000"`, `line_total = "50.000000"`.

```json
{
  "client_event_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "event_version": 2,
  "company_id": "01J9Z5K3MN0000000000000001",
  "actor_id": "01J9Z5K3MN0000000000000002",
  "device_id": "a0000000-0000-0000-0000-000000000001",
  "device_lease_id": "b0000000-0000-0000-0000-000000000001",
  "captured_at": "2026-11-11T14:30:00Z",
  "customer_id": "01J9Z5K3MN0000000000000003",
  "currency": "ILS",
  "warehouse_id": "01J9Z5K3MN0000000000000004",
  "cash_account_id": "01J9Z5K3MN0000000000000005",
  "catalog_snapshot_version": "v2026.11.11.01",
  "local_receipt_number": "REC-20261111-0001",
  "lines": [
    {
      "product_id": "01J9Z5K3MN0000000000000010",
      "product_unit_id": "01J9Z5K3MN0000000000000020",
      "unit_id": "01J9Z5K3MN0000000000000020",
      "conversion_to_base": "1.000000",
      "quantity": "2.000000",
      "unit_price": "25.000000",
      "tax_rate_id": "01J9Z5K3MN0000000000000030",
      "tax_rate": "16.000000",
      "tax_inclusive": true,
      "tax_amount": "6.900000",
      "line_total": "50.000000"
    }
  ],
  "payment": {
    "mode": "full",
    "payment_method": "cash",
    "cash_account_id": "01J9Z5K3MN0000000000000005",
    "amount_tendered": "50.000000",
    "amount_settled": "50.000000",
    "change_returned": "0.000000"
  }
}
```

### 3.4 Server Receipt & Idempotent Replay Architecture
- **Dedicated Proposed Receipt**: Scoped with `UNIQUE(company_id, client_event_id)` and bound to the original `(actor_id, device_id)` on lookup and replay.
- **Fresh Authentication**: Must revalidate actor credentials before disclosing status or payload digests.
- **Single Transaction Execution**: The same transaction inserts/locks the unique receipt and invokes canonical writers (`SalesInvoiceCommands` and payment actions). On any failure, the entire transaction rolls back cleanly; no dangling committed "processing" rows are stored.
- **Deterministic Replay**: Matching replayed `client_event_id` and digest returns the original durable outcome (`invoice_id`, `payment_id`, `invoice_number`, `grand_total`) without creating new drafts or confirmation tokens.

### 3.5 Outbox Delivery States & Security Boundaries
- **Unsupported Legacy v1 Intake**: Pre-existing v1 events map to Room `CONFLICT` with `error_message = "unsupported_event_version_1"`. The exact `payload_bytes`, `client_event_id`, and `payload_digest` are preserved without mutation.
- **Authentication Denial (HTTP 401)**: Triggers `AUTH_BLOCKED` and sets `OutboxScopeLockEntity`. Device lease expiration is an unapproved operational concept and is kept strictly separate from HTTP 401 authentication rejection.

---

## 4. Qualification Gates & Source Anchors (Qualification Gates)

### 4.1 Implementation Qualification Gates
1. **MariaDB Concurrent Receipt Qualification**: Validate the unique insert and row-locking concurrency algorithm against concurrent worker races under real MariaDB isolation.
2. **Currency Minor Units Writer Parity**: Qualify all server financial writers (`SalesDocumentRules::price`, calculators) to read `currencies.minor_units` rather than hardcoding JOD 3 / else 2 before client metadata parity. Reject unsupported currencies explicitly.
3. **Conversion Ratio Snapshot Decision**: Authorize either explicit line `conversion_to_base` in event v2 or server-side historical catalog snapshot retention.
4. **Unapproved Operational Policies**: Device lease timeouts, negative stock overselling, and offline tax invoice legality remain strictly UNAPPROVED (default OFF/NULL).

### 4.2 Source Anchor Table

| Component | Repository | Source Path | Key Symbol & Verified Anchor |
| :--- | :--- | :--- | :--- |
| **Sales Draft Intent** | Backend | `app/Application/Commands/SalesDraftIntent.php` | `ALLOWED_LINE_KEYS`: `tax_rate_id` must be `int > 0 \| null`; `MAX_MONEY_SCALE = 6`. |
| **Document Rules** | Backend | `app/Services/Sales/SalesDocumentRules.php` | `price()`: enforces `sales.invoice.change_price`; hardcodes `$currency === 'JOD' ? 3 : 2`. |
| **Actor Guard** | Backend | `app/Services/Sales/SalesActorGuard.php` | `lockAndAuthorize(int, User, string)`: singular permission check under company lock. |
| **Session Guard** | Backend | `app/Services/Security/CommandSessionGuard.php` | `authorize(User)`: verifies DB session lock, age, idle timeout, and user password hash. |
| **Invoice Commands** | Backend | `app/Application/Commands/SalesInvoiceCommands.php` | `computeDraftRevision`: hashes tax & conversion; `buildPreviewData`: `tax_snapshot_changed` blocker. |
| **Tax Rate Model** | Backend | `app/Models/TaxRate.php` | `CALC_EXCLUSIVE = 'exclusive'`, `CALC_INCLUSIVE = 'inclusive'`; `rate` scale 6 string. |
| **Product Model** | Backend | `app/Models/Product.php` | Zero tax columns; catalog DTO sends `tax_rate: null, tax_rate_id: null`. |
| **Currency Model** | Backend | `app/Models/Currency.php` | Has `minor_units`; missing in mobile `bootstrap-response.json`. |
| **Room Outbox** | Android | `app/src/main/java/com/example/data/mobile/outbox/OutboxEntities.kt` | `OutboxEventEntity`: generic `payload_bytes: BLOB`, `event_version: Int`; composite PK. |
| **Room Delivery State** | Android | `app/src/main/java/com/example/data/mobile/outbox/OutboxStatus.kt` | 6 states: `PENDING`, `IN_FLIGHT`, `UNKNOWN_ACK`, `CONFIRMED`, `CONFLICT`, `AUTH_BLOCKED`. |
