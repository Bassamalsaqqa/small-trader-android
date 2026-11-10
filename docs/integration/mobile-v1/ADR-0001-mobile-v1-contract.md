# ADR-0001: Mobile V1 Native Contract, Security Boundary, and Offline Reconciliation

- **Status**: PROPOSED NOT LIVE
- **Date**: 2026-11-10
- **Backend Baseline**: `f8a0cf7012c4875bec7320d42e0f8dde58593b52` (`https://github.com/Bassamalsaqqa/account`)
- **Android Baseline**: `79c9bb348f7ad4840842aa077f1820f75745d2a0` (`https://github.com/Bassamalsaqqa/small-trader-android`)
- **Supervision**: Scoped worktrees under Codex supervision; direct user / owner authorization overriding previous reference gates.

---

## 1. Context and Problem Statement

Small Trader Android requires a production-grade, secure, and resilient integration with the Laravel accounting platform (`account`). Previous iterations operated with simulated local demo data (`OfflineDemoTraderRepository`, `SampleCatalog`) and browser-bound session routes (`/api/v1/session`, `Phase11ApiGuard`).

To support real merchant mobile workflows:
1. Android requires a dedicated native REST API (`/api/mobile/v1`) with native token authentication, device binding, and transactional revocation checks separate from browser cookie/CSRF boundaries.
2. The system must support online immediate sales with atomic server-side preview and commit, reusing Laravel's canonical `ImmediateSaleCommands` and `AccountingPostingService`.
3. The system must provide a disciplined, auditable local-first capture mechanism for offline cash sales while clearly acknowledging physical and mathematical invariants: disconnected devices cannot guarantee global inventory availability or immediate operator revocations.
4. All economic numbers must be arbitrary-precision decimal strings (Kotlin `BigDecimal` / PHP `Brick\Math\BigDecimal`); IEEE 754 floating point numbers (`Double`/`Float`) are prohibited in economic paths.
5. The offline capability must be **OFF by default** and requires explicit business sign-off on six specific risk parameters before live cash collection is enabled.

---

## 2. Core Architectural Principles & Invariants

### 2.1 Sole Posting Authority
The Laravel backend is the **sole and exclusive posting authority** for accounting journal entries, immutable inventory movements, official fiscal sequence numbering, and tax reporting. The Android application is an intake, caching, and local-capture interface; it never writes or declares central financial ledger truth independently.

### 2.2 Truthful Status & Document States
Four distinct document and event states exist:
1. **Local Basket / Draft**: Editable client-side state. No cash taken, no inventory moved, no canonical record.
2. **Offline Sale Captured (Pending Sync / غير مرحّلة بعد)**: Merchant collected physical cash while disconnected. A durable, immutable local event exists in SQLite/Room. A provisional slip may be printed/shown bearing the explicit label **"Pending synchronization / غير مرحّلة بعد"**, a local receipt reference, and no official server invoice number.
3. **Server Accepted & Posted**: Committed atomically on the server via `ImmediateSaleCommands`. Official invoice number, payment receipt, tax balance, and ledger journal IDs are returned.
4. **Reconciliation Conflict / Exception**: Submission was refused (e.g. revoked operator, depleted stock, altered payload). Cash-collected events are **never** silently discarded, auto-deleted, or silently repriced. They are parked into a durable manager-resolution queue with immutable proof.

### 2.3 Explicit Tenant Context
All mobile operations are strictly scoped to a single active tenant (`company_id`). The system reuses existing backend architecture:
```php
CompanyContext::setCompany($company, $verifiedUser);
```
No novel multi-tenant context architecture is required or claimed. Cross-tenant reads, writes, or key replays are rejected with HTTP 403 Forbidden.

### 2.4 Currency & Decimal Precision
- **No Hardcoded Currency**: The tenant's base currency is explicitly returned in `GET /api/mobile/v1/companies/{companyId}/bootstrap` as an ISO-4217 code (e.g. `ILS`, `USD`, `JOD`). Schemas and Android domain models must not hardcode `ILS` or any other currency.
- **Exact Decimal Strings**: Quantities and monetary amounts must be transmitted as exact string representations matching regex `^-?[0-9]+(\.[0-9]+)?$`. Android maps these to Kotlin `BigDecimal`, and Laravel maps them to `Brick\Math\BigDecimal`. Floating-point arithmetic is forbidden.

---

## 3. Dedicated Mobile Security Boundary

### 3.1 Separate Auth Proof
Browser session cookies, CSRF tokens, and `Phase11ApiGuard` remain exclusively for web/PWA clients. Mobile clients communicate over HTTPS with Bearer tokens issued via Sanctum native token infrastructure under `/api/mobile/v1/auth/`.

### 3.2 Transactional Revalidation
To guard against stale authorizations or concurrent administrator revocations, every write transaction (both online sale commit and offline event intake) revalidates inside the canonical database transaction:
1. Device authorization and active status (`device.is_active = 1`).
2. Token validity and revocation status.
3. Tenant company membership (`company_user.status = active`).
4. Staff password epoch / credential revision (`user.password_epoch == token.password_epoch`).
5. Role and write permissions (`sales.create`).

### 3.3 Anti-Impersonation & MFA Policy
- **No Support Impersonation**: Administrative support-view masquerade or session impersonation is strictly denied on mobile API endpoints.
- **Optional MFA Support**: The auth flow honors existing Fortify/tenant two-factor authentication policies when enabled, returning an MFA challenge. Blanket compulsory MFA is not imposed on tenants where it is not configured.

---

## 4. API Endpoints Specification (`/api/mobile/v1`)

```
POST   /api/mobile/v1/auth/challenge
POST   /api/mobile/v1/auth/login
POST   /api/mobile/v1/auth/logout
POST   /api/mobile/v1/devices/{deviceId}/revoke

GET    /api/mobile/v1/companies/{companyId}/bootstrap
GET    /api/mobile/v1/companies/{companyId}/products
GET    /api/mobile/v1/companies/{companyId}/customers

POST   /api/mobile/v1/companies/{companyId}/sales/preview
POST   /api/mobile/v1/companies/{companyId}/sales/commit

POST   /api/mobile/v1/companies/{companyId}/offline-events
GET    /api/mobile/v1/companies/{companyId}/offline-events/{clientEventId}/status
```

### 4.1 Authentication & Device Lifecycle
- `POST /auth/challenge`: Issues an ephemeral cryptographic nonce and challenge ID bound to `device_id` and client metadata to defend against replay and credential stuffing.
- `POST /auth/login`: Authenticates credentials and optional `mfa_code` against challenge nonce. Returns a scoped Bearer token, actor profile (with server-derived UUID and password epoch), and authorized companies.
- `POST /auth/logout`: Revokes the calling device token. Does NOT delete the Android local Room queue or conflict records.
- `POST /devices/{deviceId}/revoke`: Administrative or operator-initiated device revocation marking the device inactive and terminating all active tokens.

### 4.2 Scoped Master Data Lookups
- `GET /companies/{companyId}/bootstrap`: Returns company settings, base currency ISO code, tax rules, user permissions, walk-in customer public ID, and `offline_policy` configuration (default: `enabled: false`).
- `GET /companies/{companyId}/products`: Returns active sellable products with public UUIDs, SKU, barcode, unit prices, tax rates, inventory tracking flags, and `catalog_snapshot_version`.
- `GET /companies/{companyId}/customers`: Returns searchable customer directory with public UUIDs, code, name, phone, and walk-in indicator.

### 4.3 Online Immediate Sales (Two-Phase Atomic)
1. **Preview (`POST .../sales/preview`)**: Calculates exact totals, line taxes, subtotal, and change server-side. Returns a short-lived encrypted `preview_token` binding prices, customer, and actor.
2. **Commit (`POST .../sales/commit`)**: Accepts `client_event_id`, `preview_token`, and `payload_digest`. Executes canonical `ImmediateSaleCommands::execute` inside a database transaction, creating the official sales invoice, inventory ledger deduction, customer payment, and accounting entries. Returns the official invoice number and receipt.

---

## 5. Offline Cash Event Intake & Idempotency Engine

### 5.1 Canonical Serialization and Payload Digest
Every offline sale event is hashed client-side before transmission. The digest algorithm is:
1. Normalize payload into canonical JSON following RFC 8785 (keys sorted lexicographically in ASCII codepoint order, no unquoted whitespace, UTF-8 encoded).
2. Calculate SHA-256 hash of the canonical bytes.
3. Express digest as `sha256:<64-char-lower-case-hex>`.

### 5.2 Concurrency, Storage & Replay Semantics
- **Database Unique Constraint**: A dedicated backend intake table (`mobile_event_receipts`) enforces a database unique index on `(company_id, client_event_id)`:
  ```sql
  UNIQUE KEY uq_mobile_event (company_id, client_event_id)
  ```
  Concurrency is resolved via atomic database constraint plus row locking inside a transaction; application-level `SELECT` checks without row locks are strictly insufficient.
- **Idempotent Replay**: If an incoming request matches an existing `(company_id, client_event_id)`:
  - If `request.payload_digest == existing.payload_digest`: return the stored canonical outcome (official invoice ID, receipt, numbers) immediately. **Do NOT regenerate drafts, tokens, or new invoice sequences on replay.**
  - If `request.payload_digest != existing.payload_digest`: reject with **HTTP 409 Conflict** and conflict payload containing immutable evidence.
- **Outer Mobile Event Mapping**: The outer mobile intake layer maintains independent mapping from `client_event_id` to canonical records, preserving existing `ImmediateSaleCommands` idempotency keys without collision.

### 5.3 Offline Reconciliation Handling
When an offline batch syncs:
- If `offline_policy.enabled == false`: Request is rejected or parked as `parked_conflict` (`OFFLINE_POLICY_DISABLED`).
- If price, tax snapshot, or lot version has drifted: The event is parked as `parked_conflict` (`PRICE_SNAPSHOT_MISMATCH` or `STOCK_EXHAUSTED`). The server **never silently reprices** or drops the sale.
- If staff was revoked while offline: The event is parked for authorized manager resolution (`ACTOR_REVOKED`). Physical cash remains accounted for in the exception queue.

---

## 6. Android Client Architecture & Persistence

### 6.1 Durable Outbox & Room Invariants
- **Local Capture Before Success**: The UI must never display "Sale Captured" until the immutable event record is committed to the local Room database outbox.
- **No Destructive Migrations**: `fallbackToDestructiveMigration()` is **strictly prohibited** in the Android Room database builder for any table containing outbox events, conflict logs, or cash records. All schema changes must use explicit Room migrations.
- **Unknown-ACK State Recovery**: If network drops after sending POST or during transport timeout, the client enters an `UNKNOWN_ACK` state. WorkManager retries using `GET .../offline-events/{clientEventId}/status`. It never generates a new client UUID for the same transaction.

### 6.2 Session, Queue & Keystore Isolation
- **Preserved Queue on Logout**: Operator logout locks the interface and clears in-memory credentials, but does **not** erase unsynced Room records or conflict items. Queues remain bound to `actor_id` and `company_id`.
- **No Cross-Actor Sync**: WorkManager sync only drains queue entries belonging to the currently authenticated and verified actor.
- **Keystore & Auto Backup Exclusions**:
  - Tokens and sensitive master keys reside in Android Keystore (`MasterKey` / `EncryptedSharedPreferences`).
  - Room database files (`*.db`, `*.db-wal`, `*.db-shm`) and shared preferences are explicitly excluded from Android Auto Backup and device-to-device transfers (`backup_rules.xml` and `data_extraction_rules.xml`).

---

## 7. Explicit Owner Decisions Pending Before Live Cash Pilot

The following six decisions remain pending business sign-off before enabling live offline cash collection (M6/M7):

1. **Cash Only While Disconnected**: Proposed **YES**. (Offline credit creates untracked receivable exposure and multi-device limit breaches).
2. **Offline Stock Policy**: Proposed **bounded provisional cash sales with strict on-device eligibility and transparent exception queue**. (If zero oversell risk is required, pre-reserved offline inventory quotas must be implemented).
3. **Disconnected Limits & Thresholds**: Maximum offline duration (e.g. 12 hours), max transaction count (e.g. 50 sales), max monetary volume per shift (e.g. 5,000 base currency), and whitelist of eligible devices/operators.
4. **Provisional Customer Document**: Local slip clearly labeled **"Pending synchronization / غير مرحّلة بعد"** with local serial, no official fiscal invoice number. Merchant must confirm local regulatory acceptance.
5. **Revocation Resolution Policy**: If an operator was terminated/revoked during an outage, park transactions in manager-review queue with immutable cash audit.
6. **Outage Completion Guarantee**: Absolute guarantee is mathematically impossible in distributed networks. Feasible default is risk-managed local capture with transparent server exception handling.

---

## 8. Verification Strategy & Regression Matrices

All schemas, fixtures, and canonical vectors are verified with machine-executable tooling (`tools/validate-mobile-contract.py`):
1. **Schema Compliance**: Every fixture strictly validates against its OpenAPI 3.1 / JSON Schema definition.
2. **Exact Decimal Parsing**: Fractional quantities (e.g. `0.333`), small tax percentages (`0.1600`), and large values are validated without floating point coercion.
3. **Digest Stability**: SHA-256 canonical vectors guarantee byte-for-byte reproducibility across Android (Kotlin) and Laravel (PHP).
4. **Altered Payload Conflict**: Regression fixture proves that mutating an economic line with the same `client_event_id` produces a different digest and triggers HTTP 409.
5. **Tenant Isolation**: Regression fixture proves cross-company submission returns HTTP 403.
6. **Lost-ACK Replay**: Regression fixture proves identical replay produces identical canonical invoice mapping.
7. **Manifest Reproducibility**: SHA-256 checksums of all schemas and fixtures are recorded in `manifest.sha256` for cross-repository mirroring.
