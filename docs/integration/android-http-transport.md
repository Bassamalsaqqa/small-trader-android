# Android Read Client HTTP Transport Adapter (Phase 2B)

## 1. Architectural Scope & Context

This document details the minimal Android API 24+ compatible HTTP transport adapter for the Small Trader Mobile V1 Read Client Foundation. It implements `ReadClientTransport` via a dedicated, private, non-injected `OkHttpClient` instance under `com.example.data.mobile.readclient.http.OkHttpTransport`.

### Design Philosophy & Dedicated Client Security Boundary
- **Isolated Client Dependency**: Activates the existing `okhttp = "4.10.0"` catalog dependency in `gradle/libs.versions.toml` without introducing Retrofit, logging-interceptors, Moshi, or broad framework overhauls.
- **Strict Separation of Concerns**: Bridges pure Kotlin request/response models (`TransportRequest`, `TransportResponse`) to HTTP socket streaming while enforcing origin equality, endpoint allowlisting, bounded payloads, and credential protection.
- **True Process-Ambient Isolation**: By utilizing a dedicated, private `OkHttpClient` instance with explicit `CookieJar.NO_COOKIES`, `Authenticator.NONE`, `proxyAuthenticator(Authenticator.NONE)`, and `cache(null)`, the transport guarantees complete isolation from JVM ambient state (`CookieHandler.getDefault()`, `java.net.Authenticator.getDefault()`, and `ResponseCache.getDefault()`).
- **Zero Global State Mutation**: Eliminates process-wide static mutations (`HttpURLConnection.setFollowRedirects`, `defaultUseCaches`) and eliminates dangerous multi-threaded race conditions caused by attempting to temporarily clear JVM static defaults.
- **Honest Platform Tradeoff Documentation**: Accurately documents platform-level architectural limits regarding ambient VM state (cookies, authenticators) and provides empirical test fixtures demonstrating why platform `HttpURLConnection` fails per-client isolation.

---

## 2. Unwired Gates, Timeouts & System Requirements

> [!IMPORTANT]
> **Unwired Gate Status**: The transport adapter is implemented and verified hermetically. The following platform requirements are documented as unwired gates and are deliberately preserved without modifying `AndroidManifest.xml` or application runtime entrypoints in this PR:
> 1. **Android Internet Permission**: Real device/emulator communication requires:
>    ```xml
>    <uses-permission android:name="android.permission.INTERNET" />
>    ```
>    This manifest change is deferred until supervisor application wiring.
> 2. **Main Thread Policy (`NetworkOnMainThreadException`)**:
>    HTTP calls perform synchronous blocking network I/O. In an Android runtime, invoking `execute` on the main (UI) thread triggers an immediate `android.os.NetworkOnMainThreadException`. Callers must dispatch `SmallTraderReadClient` / transport calls on a background worker thread or coroutine dispatcher (e.g. `Dispatchers.IO`).
> 3. **Socket Timeout vs. Total Deadline & Cancellation**:
>    - `connectTimeout` and `readTimeout` bound socket idle duration (time to establish connection and maximum pause between incoming bytes).
>    - In addition to connect and read timeouts, `OkHttpTransport` configures a finite, bounded `callTimeout` (default: 25s; bounded within 100ms..60s) providing an authoritative total request-response deadline.
>    - When calls fail or time out, `call.cancel()` is deterministically invoked in `finally`, immediately closing underlying sockets and cancelling ongoing I/O.
>    - This avoids the platform defect where standard coroutine `withTimeout` alone around blocking stream I/O cannot preempt a thread blocked in native socket read without tearing down the underlying socket connection.
>    - No new coroutine worker wiring is introduced in this PR; the transport adapter remains a clean, synchronous `ReadClientTransport` implementation.

---

## 3. Ambient Global State & Platform Isolation Analysis

### 3.1 Why One Client Dependency is a Necessary Security Boundary vs. Global State Workarounds
Standard Java / Android platform implementations (`com.android.okhttp.internal.huc.HttpURLConnectionImpl` on Android API 24+ and `sun.net.www.protocol.http.HttpURLConnection` on OpenJDK) exhibit architectural coupling to JVM process-global singletons:
1. **Cookie Handling (`java.net.CookieHandler`)**:
   - `HttpURLConnection` unconditionally queries the process-wide static `CookieHandler.getDefault()`.
   - If an ambient `CookieHandler` is configured anywhere in the process (e.g. by WebView, third-party analytics, or an embedded SDK), `HttpURLConnection` appends ambient `Cookie` headers to outgoing requests and dispatches received `Set-Cookie` headers to `CookieHandler.put()`.
   - There is no per-instance API on `HttpURLConnection` to disable or bypass `CookieHandler`. Mutating `CookieHandler.setDefault(null)` during `execute()` mutates process globals, breaking other components and creating fatal multi-threaded race conditions.
2. **Authentication Handling (`java.net.Authenticator`)**:
   - When a 401 Unauthorized or 407 Proxy Authentication Required response is encountered, `HttpURLConnection` delegates to the process-global static `java.net.Authenticator.getDefault()`.
   - If configured, the ambient authenticator prompts for credentials and retries the request automatically.
   - `HttpURLConnection` on Android API 24 has no per-instance API to disable `Authenticator`.
3. **Response Caching (`java.net.ResponseCache`)**:
   - `HttpURLConnection` may interact with ambient response caches if not explicitly managed.

### 3.2 The Dedicated OkHttpClient Solution
By activating the existing `okhttp = "4.10.0"` dependency and constructing a private, non-injected `OkHttpClient`:
```kotlin
OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .addInterceptor(NoRetryApplicationInterceptor())
    .addNetworkInterceptor(NoRetryNetworkInterceptor())
    .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .callTimeout(callTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
    .build()
```
- **Guaranteed Isolation**: `CookieJar.NO_COOKIES` and `Authenticator.NONE` ensure OkHttp never queries `CookieHandler.getDefault()` or `Authenticator.getDefault()`.
- **Zero Process-Global Mutations**: No static setters are ever invoked.
- **Dedicated Private Instance**: Arbitrary caller client injection is strictly disallowed, preventing external code from injecting clients configured with cookie persistence or arbitrary interceptors.
- **No Automatic Retries Guarantee**: In addition to `retryOnConnectionFailure(false)`, explicit cooperating application and network interceptors (`NoRetryApplicationInterceptor`, `NoRetryNetworkInterceptor`) together with one-shot POST request bodies (`isOneShot() = true`) guarantee zero additional wire requests on `503 Retry-After: 0`, `408`, `421`, `401`, `407`, and `3xx`, while preserving the server's initial status code, body, and response headers intact.

---

## 4. Security, TLS & Transport Invariants

### 4.1 Origin Equality & HTTPS Default
- **Strict HTTPS Default**: Origins are validated through `EndpointSecurity.validateBaseOrigin(configuredOrigin, allowHttpLoopback)`. Remote endpoints require `https://`.
- **Loopback HTTP Opt-In**: Plaintext HTTP (`http://`) is strictly restricted to verified loopback addresses (`127.0.0.1`, `localhost`, `10.0.2.2`, `::1`, `[::1]`) and requires explicit `allowHttpLoopback = true` configuration. Broad remote HTTP is strictly rejected.
- **Origin Equality Check**: `OkHttpTransport.execute(request)` verifies that `request.baseOrigin` equals the transport instance's `validatedOrigin` before opening any connection. Cross-origin dispatch is rejected immediately.
- **No DNS Prevalidation / Custom Resolver**: Relies on standard platform name resolution at connection time without inventing custom DNS resolvers or pre-resolving addresses.

### 4.2 Method & Path Allowlist Enforcement
- Before opening any connection, the transport checks `request.method` and `request.path` using `EndpointSecurity.validateMethodAndPath(request.method, request.path)`.
- Disallowed paths and disallowed method-path combinations (e.g. POST to `/products`, GET to `/auth/login`) are rejected before connection establishment.
- **GET Request Body Rejection**: GET requests containing a non-null request body are strictly rejected before opening a connection (`CATEGORY_SECURITY_VIOLATION`), preventing protocol ambiguity or method-flipping.

### 4.3 Controlled Headers & Sanitized Credentials
- **Controlled Request Headers**: Only `Accept`, `Content-Type`, and `Authorization` are permitted. Any unexpected or arbitrary headers (e.g. `Cookie`, `X-Custom-Auth`) are rejected with `CATEGORY_SECURITY_VIOLATION`.
- **Exact MIME Matching**:
  - `Accept` must equal `application/json` exactly (case-insensitive). Substring matching (`contains`) is strictly forbidden, rejecting inputs like `text/html; application/json` or `application/json-evil`.
  - `Content-Type` must equal `application/json` or `application/json; charset=utf-8` exactly.
- **Duplicate Header Prevention**: Case-insensitive duplicate headers (e.g. multiple `Authorization` or `Accept` entries) are rejected with `CATEGORY_SECURITY_VIOLATION`.
- **Authorization Header Validation**:
  - `Authorization` must begin with exact prefix `Bearer `.
  - The token is extracted without trimming (`value.substring(7)`) and validated directly against `ValidationUtils.validateToken`.
  - Tokens with leading/trailing whitespace, CRLF, control characters, or length outside 20..2048 are strictly rejected before connection opening.
  - Printable ASCII tokens including Sanctum pipe delimiters (`|`) are fully supported.
  - The exact raw value validated is dispatched over the wire without modification.
- **Safe Constant Error Messages**: Exceptions never interpolate raw header names, endpoint paths, or custom query tokens.
- **No URL Credentials**: URLs must not contain userinfo credentials (`user:pass@host`).

### 4.4 Opaque Query Parameter Percent-Encoding
- All query parameters (`limit`, `cursor`, `search`) are UTF-8 URL-encoded (`URLEncoder.encode(..., "UTF-8")`).
- Any CRLF characters remain safely percent-encoded query characters (`%0D%0A`), preventing header injection.
- Cursors are validated as bounded opaque tokens (`EndpointSecurity.validateCursor`) up to 255 characters. Valid contract characters (`/`, `=`, `+`, `-`, `_`, `.`) are safely percent-encoded and never parsed or followed as URLs.

### 4.5 Prohibition of Automatic Wire Retries & Follow-Ups (503 Retry-After: 0, 408, 421, 401, 407, 3xx)
The transport enforces an authoritative guarantee: **No automatic retries or follow-up requests are transmitted over the wire within a single `execute()` call**. This guarantee is crucial for authentication and challenge flows (preventing duplicate submission of nonce tokens or credentials) as well as operational determinism (surfacing the server's authoritative response rather than concealing transient load shed).

Primary bytecode inspection of OkHttp 4.10.0 (`RetryAndFollowUpInterceptor.kt`) reveals that while `retryOnConnectionFailure(false)` disables route failure retries and 408 Request Timeout retries, certain follow-up loops remain active by default:
1. **503 Service Unavailable with `Retry-After: 0`**:
   - OkHttp 4.10's `followUpRequest` explicitly checks `if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) return userResponse.request`. This triggers an automatic loop and second wire request regardless of `retryOnConnectionFailure(false)`.
   - **Mechanism**: `NoRetryNetworkInterceptor` intercepts the raw wire response immediately above `CallServerInterceptor`. If `Retry-After` is present, it caches the header in the per-call `CallExecutionState` and strips `Retry-After` from the response passed upward to `RetryAndFollowUpInterceptor`. Because the header is absent, OkHttp evaluates default delay (`Integer.MAX_VALUE > 0`) and returns `null` from `followUpRequest`, cleanly preventing any follow-up loop. The outer `NoRetryApplicationInterceptor` then restores the exact `Retry-After` header before returning to the caller. The initial 503 status code, body, and headers are fully surfaced.
2. **408 Request Timeout**:
   - In OkHttp 4.10, `followUpRequest` for 408 checks `if (!client.retryOnConnectionFailure) return null`. With `retryOnConnectionFailure(false)` configured, 408 returns `null` immediately and never retries. `Retry-After` neutralization provides additional defense-in-depth.
3. **HTTP/2 Connection Coalescing (421 Misdirected Request)**:
   - OkHttp 4.10's 421 handler retries only when `exchange.isCoalescedConnection` is `true`, which occurs when an existing connection to Host A is reused for Host B because both resolve to the same IP and share a SAN TLS certificate.
   - **Proof of Scope**: Because `OkHttpTransport` strictly verifies and pins all requests to `this.validatedOrigin`, no cross-host requests ever share this client's connection pool. Within a single origin, `isCoalescedConnection` is strictly `false`. Furthermore, one-shot POST request bodies (`RequestBody.isOneShot() = true`) cause 421 to return `null` immediately. The initial 421 response is returned directly without blanket HTTP/2 protocol degradation.
4. **401 Unauthorized & 407 Proxy Authentication Required**:
   - Delegated to `client.authenticator` and `client.proxyAuthenticator`. Because both are explicitly configured as `Authenticator.NONE`, `authenticate()` unconditionally returns `null`, surfacing the initial challenge response without attempting credentials or retry loops.
5. **3xx Redirects (301, 302, 303, 307, 308)**:
   - Configured with `followRedirects(false)` and `followSslRedirects(false)`. `buildRedirectRequest` returns `null`, preventing redirect follow-ups and credential leakage.
6. **One-Shot POST Payloads**:
   - For POST requests, `createOneShotRequestBody()` overrides `isOneShot(): Boolean = true`. In OkHttp's `RetryAndFollowUpInterceptor`, any follow-up request with a one-shot body aborts follow-up execution immediately and returns the original response.
7. **Wire Request Execution Guard**:
   - `CallExecutionState.wireRequestsAttempted` is tracked atomically in `NoRetryNetworkInterceptor`. If any additional wire request is ever initiated for the same call execution, it is rejected with `HttpTransportException(CATEGORY_SECURITY_VIOLATION)`.

### 4.6 Standard TLS Verification
- Uses the platform's default TLS chain and hostname verifier.
- Strictly avoids custom trust-all trust managers, certificate bypasses, or invented pinning implementations.

### 4.7 Finite Bounded Timeouts
- Connect, read, write, and call timeouts are finite and bounded:
  - Default: 10s connect, 15s read, 25s call.
  - Enforced bounds: 100ms minimum, 60s maximum.
- Socket timeouts and call deadlines are caught and mapped to safe `HttpTransportException(CATEGORY_TIMEOUT)`.

### 4.8 Payload Bounding & Strict UTF-8 Stream Decoding
- **1 MiB Upper Bound**: Maximum allowable request and response payload size is strictly 1 MiB (1,048,576 bytes).
- **Request Body Pre-Check & Strict UTF-8**: The request body length is bounded before allocation, and characters are encoded using a strict UTF-8 encoder (`CodingErrorAction.REPORT`) to reject malformed UTF-16 surrogates without unbounded memory growth.
- **Response Content-Length Pre-Check**: If the `Content-Length` header exceeds 1 MiB, the connection is aborted immediately without reading stream bytes (`CATEGORY_PAYLOAD_TOO_LARGE`).
- **Streaming Limit**: For chunked or unknown content length, byte reading is capped at 1 MiB during stream consumption (`CATEGORY_PAYLOAD_TOO_LARGE`).
- **Strict UTF-8 Decoding**: Response bytes are decoded using `StandardCharsets.UTF_8.newDecoder()` with `CodingErrorAction.REPORT`. Malformed or unmappable byte sequences are strictly rejected (`CATEGORY_MALFORMED_UTF8`), never masked with replacement character `\uFFFD`.

### 4.9 Resource Management & Error Streams
- **Deterministic Teardown**: `response` body streams and underlying calls (`call.cancel()`) are closed/canceled in `finally` blocks on all paths.
- **Authoritative Status Codes & No Swallowing**:
  - Responses with 401 Unauthorized return authoritative status code with empty body `""` when the body is empty.
  - Any IO error, network drop, or socket timeout encountered while reading response streams is strictly propagated as `CATEGORY_TIMEOUT` or `CATEGORY_NETWORK_IO`, never swallowed into synthetic empty bodies.
- **Cancellation & Fatal Error Propagation**: Catches `Throwable` to ensure `VirtualMachineError` and `CancellationException` are immediately re-thrown.
- **Safe Exceptions**: `HttpTransportException` carries only a fixed category code and sanitized message. No raw URLs, query tokens, request/response bodies, or low-level exception causes are attached.

---

## 5. Automated Verification Evidence

### 5.1 Unit Test Suite Execution
Tests executed with `./gradlew testDebugUnitTest`:

| Package | Test Class | Tests | Status |
| :--- | :--- | :--- | :--- |
| `contract` | `OfflineSaleContractGoldenParityTest` | 8 | PASSED |
| `contract` | `BoundaryCompatibilityTest` | 3 | PASSED |
| `contract` | `CanonicalJsonSerializerTest` | 11 | PASSED |
| `contract` | `IdentifierPrimitivesTest` | 4 | PASSED |
| `contract` | `ContractManifestDriftTest` | 2 | PASSED |
| `contract` | `DecimalPrimitivesAndRoundingTest` | 7 | PASSED |
| `contract` | `StringBoundaryParityTest` | 5 | PASSED |
| `readclient` | `ReadClientGoldenParityTest` | 6 | PASSED |
| `readclient` | `ReadClientSecurityAndTransportTest` | 10 | PASSED |
| `readclient` | `ReadClientRevision2BoundariesTest` | 13 | PASSED |
| `readclient` | `SmallTraderReadClientWorkflowTest` | 21 | PASSED |
| `readclient.http` | `OkHttpTransportSecurityTest` | 12 | PASSED |
| `readclient.http` | `OkHttpTransportLoopbackServerTest` | 12 | PASSED |
| `readclient.http` | `OkHttpMockWebServerTest` | 14 | PASSED |
| `readclient.http` | `PlatformHttpUrlConnectionAmbientEvidenceTest` | 2 | PASSED |
| **Total Core Suite** | **15 test classes** | **130** | **100% PASSED** (0 failures, 0 skipped) |

*Note on test scope*: Verification is reported on the focused mobile unit test suite (130 passing unit tests across 15 test classes). Unverified claims of 263 full repository test passes are explicitly removed, as broader repo test suites include out-of-scope visual Compose and Robolectric instrumentation tests.

### 5.2 Contract & Schema Verification
- Python contract validation (`python3 tools/validate-mobile-contract.py` and `python3 -O tools/validate-mobile-contract.py`):
  - **210 tests passed** in both normal and `-O` optimized mode.
  - Verifies exact `manifest.sha256` hash: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659` (Draft Revision 2).

### 5.3 Static Analysis
- Gradle static analysis (`./gradlew lintDebug`): PASSED clean with 0 errors.

### 5.4 Platform & Device Testing Limits
- All automated unit tests execute hermetically on JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`) using `MockWebServer` and an in-process local JDK `HttpServer` bound strictly to loopback `127.0.0.1`.
- Hermetic loopback tests do not claim to prove on-device TLS certificates or real device networking.
- Broad Android instrumentation visual test runs are not executed due to known native visual emulator evidence hangs; verification relies on focused mobile unit test suites and official contract parity validators.

### 5.5 Pull Request Governance & Review Status
- **Draft PR Status**: PR #12 is maintained as a Draft pull request against base PR #10 (`t3/android-auth-read-client`).
- **Owner Approval**: Repository owner merge approval has **not** been given. PR #12 must not be merged or deployed without explicit repository owner approval.

