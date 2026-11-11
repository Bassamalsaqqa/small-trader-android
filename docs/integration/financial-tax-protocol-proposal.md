# Source-Backed Financial Tax Protocol Proposal

**Document Path**: `docs/integration/financial-tax-protocol-proposal.md`  
**Document Status**: SOURCE-BACKED DESIGN PROPOSAL ONLY  
**Implementation Authority**: Design specification only; NO implementation now, NO financial activation, NO existing contract/schema edits, NO Room migrations, NO production actions.  
**Author**: Android Primary Design Writer (AGY `gemini-3.8-flash`, Thread: `t3-thread://v2/32fc517e-67b4-4fec-863a-ec803da43c1a/thread:delegated-task:command%3Amcp%3Ae0bf0fb1-5c3b-439f-9302-f4d67da01c4b%3Adelegate-task%3A5a338b45-ec30-47af-8865-dab0aef947e1`)  
**Parent Coordination**: [Integrate Android Sales with Laravel](t3-thread://v2/32fc517e-67b4-4fec-863a-ec803da43c1a/83e6a721-b800-424f-8117-1b95dbcbf33a)  
**Backend Coordination**: [Small Trader Laravel mobile integration — Codex supervision](t3-thread://v2/32fc517e-67b4-4fec-863a-ec803da43c1a/mcp:launch:b115bd8718ab7f51886265350a7b0857916a47e0a36980a0007bee341c2edd6f)

---

## 1. Executive Summary & Provenance Baseline

### 1.1 Verified Repository State
- **Backend Current Main**: Commit `f8a0cf7012c4875bec7320d42e0f8dde58593b52` (`Bassamalsaqqa/account`), verified clean HEAD.
- **Android READ Contract Revision 2 (PR 4)**: Head `ec8220934839ade0c4fe5408398ab1a313feb09c`, Manifest SHA-256 `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`.
- **Android Contract Primitives (PR 5)**: Head `810c491af4ac9df2918c67072f57a395106eba64` (repin to revision 2, drift prevention, exact decimal primitives).
- **Android Durable Outbox Foundation (PR 6)**: Head `456bfd39a362f9d3de745f1cf12d918263202dc9` (Room outbox, composite `(company_id, client_event_id)` PK, 6-state status machine, immutable BLOB payload).
- **Active Android Worktree**: Isolated branch `t3/financial-tax-protocol-proposal` at `79c9bb3` (`origin/main`).

### 1.2 The Financial Protocol Blocker
Current contract draft revision 2 defines `OfflineSaleLine` and `SalePreviewRequest` lines with a raw numeric string `tax_rate` (e.g. `"16.000000"`), but lacks:
1. **Tax Identity**: No `tax_rate_id` is transmitted on lines, yet backend canonical draft processor (`SalesDraftIntent::ALLOWED_LINE_KEYS`) strictly requires a nullable integer internal `tax_rate_id` (`int > 0 | null`) and forbids direct client-supplied numeric rates.
2. **Tax Inclusivity Flag**: No `tax_inclusive` boolean is present on line payloads, preventing deterministic calculation of gross vs net subtotal and tax amounts.
3. **No Product Tax Association**: The backend `Product` model has NO foreign key or column for tax. Products cannot supply a tax rate default; catalog reads return `tax_rate: null, tax_rate_id: null` to signify *no association*, which must NOT be misinterpreted as a zero tax exemption.
4. **Currency Minor Units Discrepancy**: Disconnected mobile calculation currently lacks authoritative currency scale metadata in `bootstrap-response.json`, while backend baseline `SalesDocumentRules::price` currently hardcodes JOD 3 / else 2 despite `currencies.minor_units` existing in database.

This proposal establishes the minimal, source-backed wire amendment and dual-version migration strategy required before activating online or offline sales.

---

## 2. Minimal Public Line Tax Identity & Explicit Line Selection

### 2.1 Wire Field Definition
In alignment with backend `App\Application\Commands\SalesDraftIntent` and `App\Models\TaxRate`:

| Field Name | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `tax_rate_id` | `string \| null` | ULID char(26): `^[0-7][0-9A-HJKMNP-TV-Z]{25}$` or `null` | Public ULID of the company-scoped tax rate master. Mapped server-side to internal `tax_rates.id`. |
| `tax_rate` | `string` | Regex: `^(?:100(?:\.0{1,6})?\|(?:0\|[1-9][0-9]?)(?:\.[0-9]{1,6})?)$` | Exact snapshot percentage (0.000000 to 100.000000, scale <= 6). |
| `tax_inclusive` | `boolean` | `true \| false` | Snapshot calculation mode: `true` = tax included in unit price; `false` = tax added to subtotal. |

### 2.2 Canonical "No Tax" vs Zero Tax & Explicit Tax Binding
- **Product Model Reality**: Backend `App\Models\Product` contains columns for SKU, names, units, stock tracking, and pricing, but **zero tax columns**.
- **Catalog Representation**: `ProductCatalogResponse` returns `"tax_rate": null` and `"tax_rate_id": null`. This pair strictly denotes *absence of default tax association*, NEVER zero tax or tax exemption.
- **Explicit User Selection**: UI line addition cannot silently default an unassociated product to 0% tax. The merchant UI must require explicit tax selection from the company's active `taxes` master (retrieved via `/bootstrap`) or explicit selection of "No Tax".
- **Canonical "No Tax" Wire Tuple**: When "No Tax" is explicitly selected:
  ```json
  "tax_rate_id": null,
  "tax_rate": "0.000000",
  "tax_inclusive": false
  ```
  This explicit 0% / false tuple is a new proposed validation rule; the catalog nullable pair is only a hint of no association.
- **Tax ULID Resolution Invariant**: When `tax_rate_id` is supplied, it MUST resolve to an active `tax_rates` row within the same authenticated `company_id`. The server must verify that the master rate and calculation mode match the line snapshot exactly. The system must **NEVER "guess" a tax master by matching percentage alone**, as multiple tax rates may share identical percentages with different ledger accounts or legal descriptions.

### 2.3 Server Binding & Revalidation in Online Preview
During online sales creation (`SalesInvoiceCommands::preview` and `SalesInvoiceCommands::execute`):
1. **Public-to-Internal Mapping**: Server resolves `tax_rate_id` ULID to `tax_rates.id` scoped strictly by `company_id`.
2. **Tax Master Integrity Check**:
   - `TaxRate::where('company_id', $inv->company_id)->whereKey($tax_rate_id)->first()`
   - Must be active (`$tr->active === true`).
   - Rate must match exactly: `BigDecimal::of($tr->rate)->isEqualTo($line->tax_rate_snapshot)`.
   - Calculation mode must match: `($tr->calculation === TaxRate::CALC_INCLUSIVE) === (bool)$line->tax_inclusive`.
   - Any divergence raises a generic HTTP 409 Conflict with detail code `tax_snapshot_changed` or `tax_rate_inactive` (noting that `tax_snapshot_changed` is a proposed generic 409 detail, not an existing machine HTTP guarantee).
3. **Cryptographic Binding**:
   - The verified line tax properties are incorporated into `draft_revision` (SHA-256) and `preview_revision` (SHA-256) inside `SalesInvoiceCommands::computeDraftRevision`.
   - An encrypted `confirmation_token` with canonical 300-second TTL is signed containing `draft_revision` and `preview_revision`.
4. **Execution Enforcement**: `SalesInvoiceCommands::execute` rechecks both revisions under database row locks (`lockForUpdate()`). If tax rates were edited in the admin console between preview and post, execution aborts with 409.

### 2.4 Mature Permission Policy
Backend `SalesDocumentRules::price()` and `SalesInvoiceCommands::preview()` enforce:
- **Price Override**: If a line unit price differs from the suggested catalog price (`default_sale_price_base / exchange_rate` rounded `HALF_UP` at currency minor units), the user MUST possess the `sales.invoice.change_price` permission. Price changes are **not universally conflicting** for actors who hold this permission; authorized actors may intentionally sell at custom prices.
- **Manual Line Discount**: Any discount > 0 requires `sales.invoice.change_discount`.
- **Non-Catalog Service**: Manual service lines without `product_id` require `sales.invoice.change_price`.

---

## 3. Offline Immutable Payload & Economic Terms Integrity

### 3.1 Event Payload Structure (Event Version 2)
An offline sale event captures immutable economic terms agreed at the physical counter. No recalculation or re-association occurs upon sync.

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
      "quantity": "2.000000",
      "unit_price": "25.000000",
      "tax_rate_id": "01J9Z5K3MN0000000000000030",
      "tax_rate": "16.000000",
      "tax_inclusive": true,
      "tax_amount": "6.896552",
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

### 3.2 Packaging Unit External Mapping
- **Database Reality**: `product_units` in Laravel has NO `public_id`. It is a relational join table with `id`, `company_id`, `product_id`, `unit_id`, `conversion_to_base`, etc.
- **Mapping Rule**: The mobile contract uses the packaging unit's public ULID (`units.public_id`). In the line payload, `unit_id` and `product_unit_id` both carry this ULID.
- **Server Resolution**: Server executes `ProductUnit::where('company_id', $companyId)->where('product_id', $productId)->where('unit_id', $resolvedInternalUnitId)->firstOrFail()`.
- **Conversion Ratio Verification**: Server asserts `line.unit_conversion_ratio == product_unit.conversion_to_base`. No conversion fallback (such as 1.0 or 120) is permitted.

### 3.3 Authoritative Currency Minor Units in Bootstrap
- **Baseline Source Finding**: Backend `currencies` table contains integer `minor_units`, but `SalesDocumentRules::price` currently hardcodes `$currency === 'JOD' ? 3 : 2`. Meanwhile, `bootstrap-response.json` only exposes `base_currency: "ILS"` without numeric scale metadata.
- **Qualification Required**: Because the baseline backend code does not yet universally read `currency.minor_units` during line validation, currency-minor metadata requires formal qualification across both services before claiming full runtime parity.
- **Proposal**: Expose explicit `minor_units: integer (0..6)` in bootstrap metadata:
  ```json
  "base_currency": "ILS",
  "base_currency_minor_units": 2
  ```
  This allows client calculators (`SalesLineCalculator.kt`) to avoid hardcoded scale assumptions.

### 3.4 Snapshot Divergence & Canonical Conflict Guard
If master data changes on the server between offline capture and sync:
- **Price changed**: Server DOES NOT silently reprice. If actor lacked `sales.invoice.change_price`, server flags conflict.
- **Tax rate changed or deactivated**: Server DOES NOT substitute another tax rate.
- **Unit conversion ratio changed**: Blocked.
- **Stock availability**: Relies on the actual canonical guard (`StockAvailabilityQuery` checking available stock at `$issueDate`), avoiding blanket global change conflicts for non-stock items.
- **Resolution**: Unreconcilable events are flagged in outbox delivery state as `status: "conflict"`, preserved locally without loss of money evidence, and queued for administrative review.

---

## 4. Version Boundary, Outbox Preservation & Dual-Language Parity

### 4.1 Boundary Distinctions
- **API Contract Revision**: Governs OpenAPI endpoint shapes, authentication envelopes, and HTTP semantics (e.g. Revision 2 -> Revision 3).
- **Offline Event Version**: Governs the inner cryptographic JSON payload of economic events (e.g. `event_version: 1` vs `event_version: 2`).

### 4.2 Immutable Outbox Preservation
- **Preservation Invariant**: In Room outbox (`outbox_events`), queued `payload_bytes`, `payload_digest`, and `client_event_id` are permanently immutable.
- **No In-Place Upgrade**: Older `event_version: 1` events stored in Room MUST NOT be deserialized, modified (e.g., injecting `tax_inclusive`), and re-serialized. Doing so would invalidate the cryptographic `payload_digest` (`sha256:hex`).
- **Fail-Closed Handling**: Because v1 offline events were never wired in live production environments, any pre-existing v1 event encountering a v2 intake endpoint must fail closed (`status: "conflict"` or `unsupported_version`) for manager review, rather than undergoing speculative auto-upgrade.

### 4.3 Lost ACK Replay & Reconciliation
- **Reconciliation Endpoint**: `/api/v1/mobile/sales/events/{client_event_id}` allows the Android client to check delivery status following a lost HTTP response or network drop.
- **Tenant Scope Isolation**: Queries are locked to `(company_id, client_event_id)` using the authenticated device token. No foreign company status can be inspected or leaked.

---

## 5. Server Idempotency, Concurrency & Atomic Execution Architecture

### 5.1 Database Uniqueness & Concurrency
- **Uniqueness Constraint**: MariaDB table `mobile_sale_events` enforces `UNIQUE(company_id, client_event_id)`.
- **Race Condition Handling on Absent Row**:
  When concurrent sync workers send the same `(company_id, client_event_id)`:
  ```sql
  INSERT INTO mobile_sale_events (company_id, client_event_id, request_hash, status, ...)
  VALUES (?, ?, ?, 'processing', ...)
  ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id);
  ```
  The transaction acquiring the row executes the canonical posting. Competing threads detect the existing receipt and await or replay the result.

### 5.2 Atomic Immediate Sale Execution & Durable Receipts
- **Contrast with Browser Receipts**: Existing `ImmediateSaleCommands` persists a `CommandReceipt` scoped to `company_id + user_id + action + idempotency_key` and hashes `invoice_id | encrypted_confirmation_token`. This is NOT a durable mobile event receipt and cannot serve mobile offline replays.
- **New Immutable Mobile Receipt**: A dedicated mobile receipt with `UNIQUE(company_id, client_event_id)` must atomically wrap canonical writers (`SalesInvoiceCommands` and `PostCustomerPaymentAction` under `TenantWriteAccess::transaction`).
- **Idempotent Replay**: Replaying with the same `client_event_id` and matching `payload_digest` returns the previously persisted outcome (`invoice_ulid`, `payment_ulid`, receipt number) without regenerating drafts or confirmation tokens. If replayed with a mismatched payload digest, it throws HTTP 409 Conflict.
- **Actor & Permission Lock**:
  `SalesActorGuard::lockAndAuthorize()` verifies `sales.invoice.view`, `sales.invoice.post`, `sales.invoice.create`, `money.receipt.create`, `money.receipt.allocate` under company lock. Revalidates staff membership, active state, and password revision.

### 5.3 Status Disambiguation
- **HTTP 401 Unauthorized (`auth_blocked`)**: Device token revoked, expired lease, or user credentials invalidated. Room sets `OutboxScopeLockEntity` and pauses all outbox dispatch across all events for that scope.
- **HTTP 409 Conflict (`conflict`)**: Business invariant broken (unauthorized price change, tax snapshot mismatch, duplicate UUID with divergent payload, insufficient stock). Specific event is parked without locking unrelated queue items.
- **HTTP 5xx / Network Error (`unknown_ack`)**: Dispatch status remains `unknown_ack` pending reconciliation.

---

## 6. Room Storage Schema & Kotlin Serialization Strategy

### 6.1 Existing Room Database Schema (Version 1)
In Android commit `456bfd39a362f9d3de745f1cf12d918263202dc9` (`OutboxDatabase.kt`), the `outbox_events` table is:
```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    client_event_id TEXT NOT NULL,
    company_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    device_lease_id TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    payload_digest TEXT NOT NULL,
    event_version INTEGER NOT NULL,
    payload_bytes BLOB NOT NULL,
    PRIMARY KEY(company_id, client_event_id)
);
```

### 6.2 Zero Physical DB Migration Required
- **Storage Reality**: The Room table stores `event_version` as an `INTEGER` and the payload as raw `BLOB` bytes.
- **Backward Compatibility**: Storing an event with `event_version = 2` requires **NO physical SQLite schema modification** and **NO Room database version bump**. Room database remains at version 1.

### 6.3 Typed Kotlin Serialization Plan
1. **Model Hierarchy**:
   ```kotlin
   sealed interface OfflineSaleEvent : CanonicalSerializable {
       val eventVersion: Int
       val clientEventId: ClientUuid
       val companyId: ServerUlid
       // ...
   }

   data class OfflineSaleEventV1(...) : OfflineSaleEvent { override val eventVersion: Int = 1 }
   data class OfflineSaleEventV2(...) : OfflineSaleEvent { override val eventVersion: Int = 2 }
   ```
2. **Strict V2 Line Model**:
   ```kotlin
   data class OfflineSaleLineV2(
       val productId: ServerUlid,
       val productUnitId: ServerUlid,
       val unitId: ServerUlid,
       val quantity: DecimalQuantity,
       val unitPrice: MoneyAmount,
       val taxRateId: ServerUlid?,       // Nullable public tax master ULID
       val taxRate: TaxPercentage,        // Exact percentage string (0..100)
       val taxInclusive: Boolean,         // Calculation mode
       val taxAmount: MoneyAmount,        // Computed tax amount
       val lineTotal: MoneyAmount         // Line total
   ) : CanonicalSerializable
   ```
3. **Dispatcher**: Retrofit intake client serializes V2 events directly from stored `payload_bytes` without re-encoding, preserving exact UTF-8 canonical bytes and digest parity.

---

## 7. Business Risk Gates & Explicitly Unapproved Policies

The following business policies remain strictly **UNAPPROVED** by executive ownership and must default to **OFF / NULL**:
1. **Offline Device Lease Duration & Value Limits**: No arbitrary timeout or maximum cash amount is hardcoded. Default lease is `NULL` (offline sales disabled).
2. **Negative Stock Oversell Policy**: Bounded offline sales without stock verification remain blocked until an explicit business policy (e.g. oversell allowance vs backorder) is signed off.
3. **Provisional Receipt Legal Standing**: Printing offline tax invoices without central fiscal registration numbers remains an open legal decision.
4. **Offline Token Revocation Window**: How quickly a compromised handheld terminal's offline authority expires without network access remains pending.

> [!IMPORTANT]
> These unapproved operational gates do NOT block the technical contract design or experimental verification on disposable staging tenants. They do, however, strictly forbid claiming production readiness or processing real cash.

---

## 8. Source Code Anchors & Evidence Table

| Component | Repository | Source File & Location | Verified Semantic Anchor |
| :--- | :--- | :--- | :--- |
| **Sales Draft Intent** | Backend | `app/Application/Commands/SalesDraftIntent.php` | `ALLOWED_LINE_KEYS = [..., 'tax_rate_id']`; `tax_rate_id` must be `int > 0 \| null`; `MAX_MONEY_SCALE = 6`. |
| **Document Rules** | Backend | `app/Services/Sales/SalesDocumentRules.php` | `price()` enforces `sales.invoice.change_price`; baseline hardcodes `$currency === 'JOD' ? 3 : 2`. |
| **Invoice Commands** | Backend | `app/Application/Commands/SalesInvoiceCommands.php` | `buildPreviewData()` checks `$tr->active`, `$tr->rate`, `$tr->calculation`; signs `confirmation_token` with 300s TTL. |
| **Immediate Sale** | Backend | `app/Application/Commands/ImmediateSaleCommands.php` | Atomic transaction composing `SalesInvoiceCommands::execute` and `PostCustomerPaymentAction::execute`. |
| **Tax Rate Model** | Backend | `app/Models/TaxRate.php` | `CALC_EXCLUSIVE = 'exclusive'`, `CALC_INCLUSIVE = 'inclusive'`; `rate` cast to string scale 6 via `TaxPercentage`. |
| **Product Model** | Backend | `app/Models/Product.php` | Zero tax associations or foreign keys; catalog DTO sends null pair. |
| **Currency Model** | Backend | `app/Models/Currency.php` | Has integer `minor_units`; missing in mobile `bootstrap-response.json`. |
| **Android Room Outbox** | Android | `app/src/main/java/com/example/data/mobile/outbox/OutboxEntities.kt` | `OutboxEventEntity` with `(company_id, client_event_id)` composite PK; immutable `payload_bytes: BLOB`, `event_version: Int`. |
| **Android Line Calc** | Android | `app/src/main/java/com/example/data/mobile/contract/SalesLineCalculator.kt` | Implements exact `HALF_UP` line rounding parity for inclusive and exclusive taxes given explicit `minorUnits: 0..6`. |
