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
- **No Live Cryptographic Proof**: Read DTOs carry wire representations; they do not construct or verify live cryptographic device-binding proofs or hardware attestation.

---

## 2. Authority & Security Boundaries

> [!IMPORTANT]
> **Central Laravel Sole Authority**: The Laravel backend (`account`) remains the sole and authoritative source of tenant permissions, company memberships, and authentication validity. The client never infers authorization grants from presentation strings (e.g. `actor.role` or `staff.role`), which are purely display metadata.

### 2.1 Endpoint Allowlisting & Origin Security
- **Strict HTTPS Default**: Base origin validation (`EndpointSecurity.validateBaseOrigin`) uses robust `java.net.URI` (Android API 24 compatible). By default, only secure `https://` origins are permitted.
- **Loopback Opt-In Only**: Plaintext HTTP (`http://`) is strictly rejected unless `allowHttpLoopback = true` is explicitly enabled AND the host is a verified loopback address (`127.0.0.1`, `::1`, `localhost`). Broad remote HTTP is never allowed.
- **URI Structure Rejection**: Base origins containing userinfo credentials, non-empty paths, query parameters, fragments, or invalid port numbers (> 65535 or < 0) are strictly rejected.
- **Validated Origin Binding**: Dispatched transport requests explicitly bind `baseOrigin` into `TransportRequest.baseOrigin`.
- **Method + Path Pair Validation**: Requests are validated against an allowlist of exact method and path templates (`EndpointSecurity.validateMethodAndPath`).
- **Opaque Cursor Validation**: Cursors are validated as opaque query values (max 255 characters). Contract-valid opaque characters (such as `/`, `=`, `+`, `-`, `_`, `.`) are permitted, without inventing a cursor character grammar. URL-looking text remains query data; the client never follows cursors as URLs. Future transport must percent-encode query values. Origin validation performs no DNS resolution and local HTTP opt-in uses an explicit address allowlist.

### 2.2 Token & Credential Placement
- **Tokens in Authorization Header Only**: Authenticated requests (`logout`, `bootstrap`, `products`, `customers`) supply the token exclusively through the HTTP header `Authorization: Bearer <token>`. Tokens are never placed in URL paths, query parameters, request bodies, or log representations.
- **Token Validation**: Bearer tokens from both login responses and caller headers are validated to be within 20..2048 characters, containing only printable ASCII characters (`!` to `~`, ASCII 33..126), rejecting CRLF, whitespace, and control characters, while supporting standard Laravel Sanctum tokens (e.g. `1|laravel_sanctum_token_...`).
- **Login Credentials in Body Only**: Staff email, password, TOTP `mfa_code`, and Fortify `recovery_code` are transmitted exclusively in the JSON request body of `POST /api/mobile/v1/auth/login`.

### 2.3 Secret Redaction, Log Safety & Safe Exceptions
- **Zero Raw Credential/PII Leaks**: All default debug and `toString()` methods across requests, responses, errors, results, and DTOs redact sensitive data:
  - `AuthLoginRequest.toString()` redacts password, MFA code, and recovery code.
  - `ActorProfile.toString()` redacts actor name, email, and password epoch.
  - `AuthLoginSuccessResponse.toString()` redacts bearer token as `Bearer [REDACTED]`.
  - `BootstrapStaff.toString()` redacts staff name.
  - `ProductCatalogItem.toString()` redacts product name.
  - `CustomerDirectoryItem.toString()` redacts customer name and phone.
  - `TransportRequest.toString()` redacts headers, query parameters, and raw bodies.
  - `TransportResponse.toString()` redacts raw bodies.
- **Fixed Safe Failure Categories**: `ReadClientResult.ParseFailure` and `ReadClientResult.NetworkUnknown` retain only safe fixed category codes (`PARSE_ERROR`, `NETWORK_ERROR`), without raw exception messages or accessible causes that could echo hostiles/secrets.
- **Error Response Code Masking**: `ReadClientErrorResponse.toString()` masks unverified/malformed error codes as `[UNTRUSTED_CODE]` and redacts raw server error messages and details.
- **Exception Safety**: Only ordinary `Exception`s are caught. `CancellationException` is immediately rethrown to preserve coroutine cancellation, and fatal VM errors (`VirtualMachineError`) are never swallowed.

### 2.4 Multi-Tenant Company Isolation & Actor Verification
- **Tenant Scope Enforcement**: For `bootstrap`, `products`, and `customers`, `SmallTraderReadClient` verifies that the response `companyId` matches the requested `companyId`. Any mismatch triggers a safe generic `PARSE_ERROR` before exposing foreign DTO data.
- **Login Actor Verification**: `POST /auth/login` verifies that `response.actor.email` matches the submitted `request.email` (using case-insensitive trimmed comparison). Any mismatch is rejected as a `PARSE_ERROR`.
- **No Invented Schema Fields**: The login success response matches the frozen schema exactly, with no invented device-response fields.

### 2.5 Future Transport Engine Requirements
When a concrete HTTP transport engine (e.g. OkHttp/Cronet) is wired in future phases, it must adhere to the following contracts:
1. **Same-Origin Guarantee**: Requests must never switch origin or downgrade protocol.
2. **Query Percent-Encoding**: Query parameters, especially opaque pagination cursors containing characters like `/` and `=`, must be properly percent-encoded in the HTTP query string.
3. **No Auth Forwarding on Redirect**: If HTTP redirects are encountered, the engine must not automatically forward `Authorization` headers across domain or origin boundaries.

---

## 3. Coordinated Draft Revision 2 Contract Invariants

### 3.1 MFA Fresh Challenge Retry & Exact oneOf Branches
- When two-factor authentication is required, `POST /auth/login` returns:
  ```json
  {
    "status": "MFA_REQUIRED",
    "retry": "fresh_challenge"
  }
  ```
- Because the initial challenge nonce is consumed upon login evaluation, the client is signaled with `retry: "fresh_challenge"`. Nonce-less continuation or reusing consumed challenges is strictly rejected.
- Strict `oneOf` discrimination rejects payloads combining keys from both the success branch (`token`, `actor`, `companies`) and the MFA branch (`status`, `retry`), enforcing `additionalProperties: false`.
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
- `device_lease` is a required nullable property: the key must be explicitly present in the JSON payload (`missing != null`).

### 3.3 Truthful Nullable Paired Product Tax
- `tax_rate` and `tax_rate_id` form a strict nullable pair in product catalog items:
  - Both null: No product default tax association exists (signifies unassociated/unknown tax, never an inferred zero tax rate or tax exemption). Both keys must be explicitly present in the JSON payload (`missing != null`).
  - Both non-null: Valid tax rate association.
  - Mismatched pair (`tax_rate` without `tax_rate_id`, or `tax_rate_id` without `tax_rate`): strictly rejected.

### 3.4 Schema Parity, Missing != Null, and Immutable Invariants
- **Missing != Null Enforcement**: Required nullable properties (`next_cursor`, `tax_rate`, `tax_rate_id`, `device_lease`, `sale_price`) must exist in the JSON map (`containsKey`). Missing keys are rejected even if null is a valid value.
- **Error Details Schema Bounds**: `ReadClientErrorResponse.details` strictly permits only JSON objects, arrays, strings, or null; numbers and booleans are rejected per contract schema.
- **Defensive Immutability**: All collections and maps in response DTOs (`companies`, `units`, `taxes`, `products`, `customers`, `permissions`, `headers`, `queryParams`) are defensively wrapped in unmodifiable collections (`Collections.unmodifiableList`, `Collections.unmodifiableMap`) to prevent mutation via external aliases.
- **Constructor & Copy Invariants**: Domain invariants are enforced in `init` blocks and validated copy methods (e.g. `ProductDefaultUnit` requires `conversionToBase > 0` and `salePrice >= 0`).

---

## 4. Typed Outcomes & Exact Status Codes

Every client call returns an explicit, exhaustive `ReadClientResult<T>`:
- **`Success<T>`**: Exactly HTTP status `200` OK per OpenAPI specification. Unexpected 201/202/204 status codes are treated as `HttpError`.
- **`Auth401`**: HTTP 401 Unauthorized carrying `ReadClientErrorResponse`. Callers use this explicit outcome to trigger outbox queue lock handling (`QUEUE_LOCKED_AUTH_DENIED`) without deleting local records.
- **`Forbidden403`**: HTTP 403 Forbidden carrying `ReadClientErrorResponse` (e.g. cross-tenant unauthorized access).
- **`Conflict409`**: HTTP 409 Conflict carrying `ReadClientErrorResponse` with structured `ConflictEvidence`.
- **`HttpError`**: Other HTTP error status codes (e.g. 201, 204, 404, 422, 500).
- **`ParseFailure`**: Wire shape violations, unexpected extra keys (`additionalProperties: false`), companyId mismatches, or missing required nullable keys. Retains safe category `PARSE_ERROR`.
- **`NetworkUnknown`**: Transport I/O failures, connection drops, or timeouts. Retains safe category `NETWORK_ERROR`.

---

## 5. Automated Verification & Test Evidence

The read client foundation unit tests execute under JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`) without Android framework stubs:

| Test Class | Tests | Checks Covered |
| :--- | :--- | :--- |
| `ReadClientGoldenParityTest` | 6 | All 6 golden fixtures (`golden-auth-challenge.json`, `golden-auth-login.json`, `golden-auth-logout.json`, `golden-bootstrap.json`, `golden-product-catalog.json`, `golden-customer-directory.json`), verifying exact wire parity, ULID/UUID representations, exact decimal amounts, and field bindings. |
| `ReadClientRevision2BoundariesTest` | 13 | Fresh MFA challenge acceptance, old nonce-less MFA rejection, strict MFA oneOf branch separation, disabled offline policy with lease rejection, empty mature masters acceptance, truthful nullable paired product tax (both null, both set, mismatched rejections), missing != null key enforcement on nullable fields (`next_cursor`, `tax_rate`, `device_lease`), error details type bounds (object/array/string/null only), max width boundary tests, unexpected property rejections, float money rejections, foreign ULID/UUID rejections, and constructor/copy invariants. |
| `ReadClientSecurityAndTransportTest` | 10 | Base origin validation (HTTPS required, loopback opt-in only, rejecting paths/queries/fragments/credentials/invalid ports), endpoint allowlist and method+path enforcement, secret redaction in `toString()` (passwords, MFA codes, bearer tokens, customer PII), cursor safety with opaque valid chars (`/`, `=`), bearer token validation with Laravel Sanctum pipe support, and defensive copy immutability. |
| `SmallTraderReadClientWorkflowTest` | 21 | End-to-end client dispatch across all 6 endpoints with mock transport, parameter placement, tenant company isolation checks (bootstrap, products, customers), login identity email verification (case-insensitive), exact 200 success enforcement (rejecting 201/204), safe failure categories and zero credential leak on hostile transport exceptions and malformed login secret bodies, and explicit outcome discrimination. |

Combined Mobile Suite XML counts:
- `com.example.data.mobile.contract.*`: **40 tests** (all 7 classes passing 100%)
- `com.example.data.mobile.readclient.*`: **50 tests** (all 4 classes passing 100%)
- **Total Mobile Foundation & Read Client Tests**: **90 tests** (0 failed, 0 skipped).

Python contract validation suite: **210 checks passing** (normal and `-O` optimized python), matching `manifest.sha256` (`ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`, Draft Revision 2).
