# ADR-0001: Mobile V1 API Contract, Security Boundary, and Offline Intake Engine

## Status
Proposed (Contract Only — Supervised Implementation Baseline)

## Context
Small Trader Android (`small-trader-android`) requires an authoritative, transactionally sound integration contract with the Laravel backend (`account`). 
- **Laravel Sole Authority**: Laravel is the sole posting authority for all double-entry ledgers, official sequential invoice numbers, tax snapshots, and inventory balances.
- **Android Role**: Android acts as a client presentation and offline-first capture layer. It never posts directly to accounting ledgers or commits central inventory movements.
- **Baseline Commits**:
  - Laravel backend baseline: `f8a0cf7012c4875bec7320d42e0f8dde58593b52`
  - Android client baseline: `79c9bb348f7ad4840842aa077f1820f75745d2a0`
- **Scope**: Contract specifications, JSON schemas, golden fixtures, regression test vectors, and machine-executable validator. No runtime code changes, UI alterations, Gradle builds, or production deployments.

---

## 1. Architectural Boundaries and Roles

```
+-----------------------------------+        HTTPS / TLS 1.3
|       Small Trader Android        | ----------------------------> +-----------------------------------+
|   (Local-First Capture & POS)     | <---------------------------- |          Laravel Platform         |
|                                   |    REST / JSON (OpenAPI 3.1)  |      (Sole Posting Authority)     |
| - Android Keystore Wrapped Tokens |                               | - Double-Entry Accounting Ledger  |
| - Room Database (Outbox & Queue)  |                               | - Exact Money (DECIMAL 20,6)      |
| - Restricted Canonical JSON Hashing|                               | - Idempotency & Unique Row Locks  |
+-----------------------------------+                               +-----------------------------------+
```

---

## 2. Decision Summary

1. **Exact Arbitrary-Precision Decimal Strings**: All monetary amounts, line computations, tax rates, and quantities must be communicated as exact decimal strings (matching Kotlin `BigDecimal` and PHP `Brick\Math\BigDecimal`). IEEE 754 floating-point numbers (`Double`/`Float`) are strictly forbidden and rejected.
2. **Rounding Mode Policy (HALF_UP)**: Financial math adheres to Laravel platform's verified rounding mode: `RoundingMode::HALF_UP` (arithmetic rounding away from zero: 0.5 rounds up), **not Banker's rounding (`ROUND_HALF_EVEN`)**. Cites Laravel platform sources:
   - `app/Domain/Sales/Calculators/SalesLineCalculator.php`
   - `app/Domain/Sales/Calculators/SalesDocumentTotalsCalculator.php`
   - `docs/adr/0002-money-and-accounting-primitives.md`
   Internal financial scale is 6 decimal places (`DECIMAL(20,6)` for currency, `DECIMAL(20,10)` for FX rates). Display and intermediate line rounding respects minor units (ILS/USD: 2 decimals, JOD: 3 decimals).
3. **Small Trader Restricted Canonical JSON Profile v1 (RFC 8785 Subset)**: Canonical payload hashing client-side and server-side follows an explicit restricted profile:
   - All decimals and quantities MUST be string-encoded (floats rejected immediately).
   - Object keys are deterministically sorted by UTF-16 code units (`char.encode('utf-16-be')`).
   - Duplicate JSON keys, unpaired Unicode surrogates, and unknown non-schema keys are strictly rejected.
   - Escapes characters `< 0x20` as `\u00xx` or standard JSON escapes (`\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`). All non-ASCII Unicode characters (including Arabic text) emitted as unescaped UTF-8.
   - Cross-platform parity test suites across Kotlin (`small-trader-android`) and PHP (`account`) remain pending before production freeze.
4. **Immutable Offline Event Identity & Server Derivation**:
   - The outer request envelope (`client_event_id`, `device_id`, `captured_at`) is equality-validated against the inner `event` payload.
   - The inner `event` payload immutably binds `company_id`, `actor_id`, `device_id`, `device_lease_id`, `client_event_id`, `event_version`, `captured_at`, `customer_id`, `currency`, `warehouse_id`, `cash_account_id`, `catalog_snapshot_version`, `local_receipt_number`, `lines` (with `product_id`, `unit_id`, and bounded decimals), and `payment` (`mode: "cash"`, `cash_account_id`, and bounded decimals).
   - The SHA-256 digest (`sha256:<hex>`) binds this entire tuple.
   - **Server-Side Derivation**: The server derives authenticated `actor_id` from the Bearer token and `company_id` from the URL path. It compares them against the signed event. They are never blindly accepted from the client.
5. **Bootstrap Mapping & Arbitrary Routing Prevention**:
   - `GET /companies/{companyId}/bootstrap` returns the authoritative company context, base currency, walk-in customer UUID, staff profile, `device_lease` (lease UUID, device UUID, actor UUID, expiry), `default_warehouse` (`id`, `code`, `name`), `approved_cash_account` (`id`, `code`, `name`, `currency`), allowed `units` (with scale and fraction flags), and `offline_policy` (default: `enabled: false`).
   - Offline transactions must map exclusively to the assigned warehouse and approved cash account, preventing arbitrary inventory or cash routing.
6. **Authentication, Fortify Parity & Single-Use MFA Challenge**:
   - `POST /auth/challenge` issues an ephemeral `challenge_id`, cryptographic `nonce`, and `expires_at` (5-minute TTL).
   - `POST /auth/login` binds `challenge_id` AND `nonce` together with `device_id`, `device_name`, `email`, `password`, optional `mfa_code` (6-digit TOTP), and optional `recovery_code` (single-use backup recovery code matching Laravel Fortify policy).
   - Rate limited to 5 attempts per minute per IP/username, matching `FortifyServiceProvider`.
   - Nonce provides freshness and replay protection, but does not prevent credential stuffing by itself; stuffing defense relies on rate limits, WAF, and optional MFA.
   - Password changes or credential updates increment the user's password epoch, revoking all active mobile tokens.
7. **Auth Revocation & Queue Lock Separation**:
   - A revoked Bearer token cannot post intake events or receive official receipts. An HTTP request with a revoked token is rejected at the authentication gateway with **HTTP 401 Unauthorized**. The server never returns parked receipts to an unauthenticated/revoked caller.
   - On the Android client, an HTTP 401 error transitions the outbox queue to `QUEUE_LOCKED_AUTH_DENIED`. The captured sales remain safely preserved and immutable in local Room storage to maintain physical cash drawer audit integrity.
   - The locked queue requires **authenticated manager resolution** (manager authentication or re-authenticated session) to inspect or reconcile.
   - Cross-actor payloads or sync statuses are never exposed to unauthorized operators.
8. **Android Keystore Hardware Key Custody**:
   - Deprecated `EncryptedSharedPreferences` is explicitly removed.
   - Hardware-backed AES-256-GCM master keys reside inside Android Keystore (`AndroidKeyStore`).
   - The Bearer token itself is NOT stored inside Android Keystore (which is a cryptographic key repository, not a secret string store). Instead, the Keystore key encrypts/wraps the token, and the ciphertext resides in private application storage (e.g. encrypted Proto DataStore or private files/Tink).
   - Room database files and private preferences are excluded from Android Auto Backup and device-to-device transfers (`backup_rules.xml` and `data_extraction_rules.xml`).
9. **Offline Policy Default**:
   - Default: `enabled: false`. Offline cash capture remains disabled pending business owner sign-off on risk limits and reconciliation procedures.

---

## 3. OpenAPI 3.1 Endpoint Architecture

The contract defines 11 REST endpoints:
- **7 POST Endpoints with Request & Response Payloads**:
  1. `POST /api/mobile/v1/auth/challenge`
  2. `POST /api/mobile/v1/auth/login`
  3. `POST /api/mobile/v1/auth/logout`
  4. `POST /api/mobile/v1/devices/{deviceId}/revoke`
  5. `POST /api/mobile/v1/companies/{companyId}/sales/preview`
  6. `POST /api/mobile/v1/companies/{companyId}/sales/commit`
  7. `POST /api/mobile/v1/companies/{companyId}/offline-events`
- **4 GET Endpoints (Query & Path Parameters Only, No Request Body)**:
  8. `GET /api/mobile/v1/companies/{companyId}/bootstrap`
  9. `GET /api/mobile/v1/companies/{companyId}/products`
  10. `GET /api/mobile/v1/companies/{companyId}/customers`
  11. `GET /api/mobile/v1/companies/{companyId}/offline-events/{clientEventId}/status`

All error responses across all endpoints standardize on `schemas/error-response.json` (RFC 7807/9457 Problem Details shape).

---

## 4. Idempotency, Concurrency & Database Constraints

- **Intake Table Unique Constraint**:
  ```sql
  UNIQUE KEY uq_mobile_event (company_id, client_event_id)
  ```
  Concurrency is resolved via atomic database constraint plus row locking (`lockForUpdate`) inside a database transaction.
- **Idempotent Replay Handling**:
  - If `(company_id, client_event_id)` matches an existing record and `request.payload_digest == existing.payload_digest`: return stored canonical outcome immediately. Do NOT regenerate invoices, numbers, or accounting lines.
  - If `request.payload_digest != existing.payload_digest`: reject immediately with **HTTP 409 Conflict** and structured conflict evidence.

---

## 5. Explicit Owner Decisions Pending Before Live Cash Pilot

1. **Cash Only While Disconnected**: Default: `cash_only: true`.
2. **Offline Stock Policy**: Bounded provisional cash sales with transparent exception queue.
3. **Disconnected Limits & Thresholds**: Max duration (12 hours), max transaction count (50 sales), max monetary volume (5,000 base currency).
4. **Provisional Customer Document**: Local slip clearly labeled **"Pending synchronization / غير مرحّلة بعد"** with local serial, no official fiscal invoice number.
5. **Revocation Resolution Policy**: If an operator is revoked while disconnected, the device locks the outbox queue on HTTP 401 until an authorized manager resolves it.
6. **Cross-Platform Parity Testing**: Complete automated test suites verifying exact digest and rounding parity across Kotlin (`small-trader-android`) and PHP (`account`) must pass prior to production rollout.
