# Android Read Client Foundation (Phase 2)

## 1. Architectural Scope & Bounded Context

This foundation implements the bounded, pure Kotlin read and authentication client boundary for Android (`small-trader-android`), built directly on top of the exact-decimal contract foundation (PR5, commit `810c491af4ac9df2918c67072f57a395106eba64`) and authoritative Coordinated Draft Revision 2 contract specification (`manifest.sha256` hash `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`).

### Bounded Endpoints (Only)
The client boundary strictly supports exactly 6 authentication, bootstrap, and read catalog endpoints:
1. `POST /api/mobile/v1/auth/challenge`: Fetch ephemeral single-use challenge nonce and TTL for device.
2. `POST /api/mobile/v1/auth/login`: Authenticate staff credentials bound to device and challenge nonce.
3. `POST /api/mobile/v1/auth/logout`: Revoke active mobile session token.
4. `GET /api/mobile/v1/companies/{companyId}/bootstrap`: Retrieve authoritative company context, catalog snapshot version, tax definitions, units, and offline policy status.
5. `GET /api/mobile/v1/companies/{companyId}/products`: Retrieve bounded, paginated product catalog.
6. `GET /api/mobile/v1/companies/{companyId}/customers`: Retrieve bounded, paginated customer directory.

### Explicit Non-Claims & Exclusions
- **No Network Engine**: No OkHttp, Retrofit, or live HTTP client runtime is wired or bundled. All transport is injected via `ReadClientTransport`.
- **No UI / ViewModel Wiring**: No Jetpack Compose, ViewModel, or repository wiring. Existing sample UI and offline repositories remain untouched.
- **No Token Persistence / Keystore**: Bearer tokens and hardware master keys are not persisted, cached, or read from Android Keystore in this foundation layer.
- **No Room Database Edits**: No Room entities, DAOs, or SQLite schema changes.
- **No Sales Endpoints**: Offline event intake, sales preview, sales commit, and event status endpoints are out of scope for this read client layer.
- **No Demo Fallback**: No silent fallback to mock data, hardcoded catalogs, or demo staff when network or authentication fails.

---

## 2. Authority & Security Boundaries

> [!IMPORTANT]
> **Central Laravel Sole Authority**: The Laravel backend (`account`) remains the sole and authoritative source of tenant permissions, company memberships, and authentication validity. The client never infers authorization grants from presentation strings (e.g. `actor.role` or `staff.role`).

### 2.1 Endpoint Allowlisting & Origin Security
- All requests dispatched through `SmallTraderReadClient` validate that the target endpoint strictly matches an allowlisted Mobile V1 URL path template (`EndpointSecurity.validatePath`).
- Base origin is validated (`EndpointSecurity.validateBaseOrigin`): requires valid `http://` or `https://` URI without path segments, query parameters, fragments, or userinfo credentials.
- External URLs and arbitrary path traversal in cursor parameters are rejected (`EndpointSecurity.validateCursor`). The client never follows arbitrary external URLs.

### 2.2 Token & Credential Placement
- **Tokens in Authorization Header Only**: Authenticated requests (`logout`, `bootstrap`, `products`, `customers`) supply the token exclusively through the HTTP header `Authorization: Bearer <token>`. Tokens are never placed in URL paths, query parameters, request bodies, or log representations.
- **Login Credentials in Body Only**: Staff email, password, TOTP `mfa_code`, and Fortify `recovery_code` are transmitted exclusively in the JSON request body of `POST /api/mobile/v1/auth/login`.

### 2.3 Secret Redaction & Log Safety
- `AuthLoginRequest.toString()` strictly redacts passwords, MFA codes, and recovery codes with `[REDACTED]`.
- `AuthLoginSuccessResponse.toString()` redacts bearer tokens as `Bearer [REDACTED]`.
- `TransportRequest.toString()` redacts `Authorization` headers and masks login request bodies as `[LOGIN_CREDENTIALS_REDACTED]`.
- Error serialization (`ReadClientErrorResponse`) never reflects sensitive credentials.

---

## 3. Coordinated Draft Revision 2 Contract Invariants

### 3.1 MFA Fresh Challenge Retry
- When two-factor authentication is required, `POST /auth/login` returns:
  ```json
  {
    "status": "MFA_REQUIRED",
    "retry": "fresh_challenge"
  }
  ```
- Because the initial challenge nonce is consumed upon login evaluation, the client is signaled with `retry: "fresh_challenge"`. Nonce-less continuation or reusing consumed challenges is strictly rejected.
- Request validation strictly enforces mutual exclusion between `mfa_code` (6-digit TOTP) and `recovery_code` (Fortify recovery code); submitting both simultaneously is rejected.

### 3.2 Disabled Offline Policy & Device Lease Invariants
- Default offline policy is strictly disabled:
  ```json
  {
    "enabled": false,
    "availability": "unavailable"
  }
  ```
- Invariant: When `offline_policy.enabled` is `false`, `device_lease` MUST be `null`. Any payload containing a non-null device lease while offline policy is disabled is rejected as invalid (`CanonicalSerializationException`).
- No invented numeric limits or caps are synthesized when offline mode is unavailable.

### 3.3 Truthful Nullable Paired Product Tax
- `tax_rate` and `tax_rate_id` form a strict nullable pair in product catalog items:
  - Both null: No product default tax association exists (e.g. mature products without direct tax foreign keys). This signifies unknown/unassociated tax, **never** an inferred zero tax rate or tax exemption.
  - Both non-null: Valid tax rate association.
  - Mismatched pair (`tax_rate` without `tax_rate_id`, or `tax_rate_id` without `tax_rate`): strictly rejected.

### 3.4 Bounded Field Widths & Schema Parity
- Maximum string widths reflect mature database schema migrations without truncation:
  - Names (companies, warehouses, cash accounts, taxes, customers, products, staff, actor): 1..255 UTF-16 code units.
  - Codes (cash account, units, taxes): 1..32 code units.
  - Codes (warehouse, customer): 1..64 code units.
  - SKUs and Barcodes: 1..128 code units.
  - Units of Measure (base unit name, packaging unit name): 1..128 code units.
  - Device Name: 1..100 code units.
  - Password: 1..256 code units.
  - App Version & Catalog Snapshot Version: 1..64 code units.
  - Challenge Nonce: 32..128 code units.
  - Pagination Limit: 1..100 (default 50).
  - Pagination Cursor: max 255 code units.
  - Search Query: max 100 code units.

---

## 4. Typed Outcomes & Lock Handling

Every client call returns an explicit, exhaustive `ReadClientResult<T>`:
- **`Success<T>`**: Strongly-typed payload parsed with exact decimal preservation and zero float coercion.
- **`Auth401`**: HTTP 401 Unauthorized carrying `ReadClientErrorResponse`. Callers use this explicit outcome to trigger outbox queue lock handling (`QUEUE_LOCKED_AUTH_DENIED`) without deleting local records.
- **`Forbidden403`**: HTTP 403 Forbidden carrying `ReadClientErrorResponse` (e.g. cross-tenant unauthorized access).
- **`Conflict409`**: HTTP 409 Conflict carrying `ReadClientErrorResponse` with structured `ConflictEvidence`.
- **`HttpError`**: Other HTTP error status codes (e.g. 404, 422, 500).
- **`ParseFailure`**: Wire shape violations, unexpected extra keys (`additionalProperties: false`), float money, or boundary failures.
- **`NetworkUnknown`**: Transport I/O failures, connection drops, or timeouts.

---

## 5. Automated Verification & Test Evidence

The read client foundation unit tests execute under JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`) without Android framework stubs:

| Test Class | Tests | Checks Covered |
| :--- | :--- | :--- |
| `ReadClientGoldenParityTest` | 6 | All 6 golden fixtures (`golden-auth-challenge.json`, `golden-auth-login.json`, `golden-auth-logout.json`, `golden-bootstrap.json`, `golden-product-catalog.json`, `golden-customer-directory.json`), verifying exact wire parity, ULID/UUID representations, exact decimal amounts, and field bindings. |
| `ReadClientRevision2BoundariesTest` | 9 | Fresh MFA challenge acceptance, old nonce-less MFA rejection, disabled offline policy with lease rejection, empty mature masters acceptance, truthful nullable paired product tax (both null, both set, mismatched rejections), max width boundary tests (255 vs 256, 128 vs 129, 64 vs 65, 32 vs 33), unexpected property rejections, float money rejections, foreign/invalid Crockford ULID and UUID rejections. |
| `ReadClientSecurityAndTransportTest` | 5 | Base origin validation (rejecting paths, userinfo, queries), endpoint allowlist enforcement, secret redaction in `toString()` (passwords, MFA codes, bearer tokens), cursor safety against external URL following, pagination limit bounds (1..100). |
| `SmallTraderReadClientWorkflowTest` | 12 | End-to-end client dispatch across all 6 endpoints with mock transport, parameter placement (tokens in headers, credentials in body), honest empty pagination results, and explicit outcome discrimination (`Auth401`, `Forbidden403`, `Conflict409`, `HttpError`, `ParseFailure`, `NetworkUnknown`). |

Combined Mobile Suite XML counts:
- `com.example.data.mobile.contract.*`: **40 tests** (all 7 classes passing 100%)
- `com.example.data.mobile.readclient.*`: **32 tests** (all 4 classes passing 100%)
- **Total Mobile Foundation & Read Client Tests**: **72 tests** (0 failed, 0 skipped).

Python contract validation suite: **210 checks passing** (normal and `-O` optimized python), matching `manifest.sha256` (`ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`, Draft Revision 2).
