# Android Read Client HTTP Transport Adapter (Phase 2B)

## 1. Architectural Scope & Context

This document details the minimal Android API 24+ compatible HTTP transport adapter for the Small Trader Mobile V1 Read Client Foundation. It implements `ReadClientTransport` via standard platform `java.net.HttpURLConnection` / `javax.net.ssl.HttpsURLConnection` under `com.example.data.mobile.readclient.http.*`.

### Design Philosophy & Platform Boundaries
- **Platform Native**: Utilizes built-in `HttpURLConnection` and `HttpsURLConnection` available on Android API 24+ and JDK 21.
- **Strict Separation of Concerns**: Bridges pure Kotlin request/response models (`TransportRequest`, `TransportResponse`) to HTTP socket streaming while enforcing origin equality, endpoint allowlisting, bounded payloads, and credential protection.
- **Zero Global State Mutation**: Eliminates process-wide static mutations (`HttpURLConnection.setFollowRedirects`, `defaultUseCaches`) in favor of strict per-connection configuration (`instanceFollowRedirects = false`, `useCaches = false`).
- **Honest Platform Tradeoff Documentation**: Accurately documents platform-level architectural limits regarding ambient VM state (cookies, authenticators) without making false isolation claims.

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
>    Platform `HttpURLConnection` performs synchronous blocking I/O. In an Android runtime, invoking `execute` on the main (UI) thread will trigger an immediate `android.os.NetworkOnMainThreadException`. Callers must dispatch `SmallTraderReadClient` / transport calls on a background worker thread or coroutine dispatcher (e.g. `Dispatchers.IO`).
> 3. **Socket Timeout vs. Total Deadline & Cancellation**:
>    - `connectTimeout` and `readTimeout` bound socket idle duration (time to establish connection and maximum pause between incoming bytes). They do **not** bound the total request-response lifespan (a slow stream trickling bytes within `readTimeout` can prolong execution).
>    - Higher-level caller workflows require an end-to-end deadline with explicit connection teardown (invoking `connection.disconnect()` upon cancellation or deadline expiration).
>    - Standard coroutine `withTimeout` alone around blocking stream I/O cannot preempt a thread blocked in native socket read without tearing down the underlying socket connection.
>    - No new coroutine worker wiring is introduced in this PR; the transport adapter remains a clean, synchronous `ReadClientTransport` implementation.

---

## 3. Ambient Global State & Platform Isolation Analysis

### 3.1 Platform `HttpURLConnection` Architectural Boundaries
In standard Java / Android runtime implementations (`com.android.okhttp.internal.huc.HttpURLConnectionImpl` on Android API 24+ and `sun.net.www.protocol.http.HttpURLConnection` on OpenJDK):
1. **Cookie Handling (`java.net.CookieHandler`)**:
   - `HttpURLConnection` unconditionally queries the process-wide static `CookieHandler.getDefault()`.
   - If an ambient `CookieHandler` is configured anywhere in the process (e.g. by WebView or an embedded SDK), `HttpURLConnection` appends ambient `Cookie` headers to outgoing requests and dispatches received `Set-Cookie` headers to `CookieHandler.put()`.
   - There is no per-instance API on `HttpURLConnection` to disable or bypass `CookieHandler`. Mutating `CookieHandler.setDefault(null)` during `execute()` would mutate process globals and create thread-safety race conditions across the host application.
2. **Authentication Handling (`java.net.Authenticator`)**:
   - When a 401 Unauthorized or 407 Proxy Authentication Required response is encountered, `HttpURLConnection` delegates to the process-global static `java.net.Authenticator.getDefault()`.
   - If configured, the ambient authenticator prompts for credentials and retries the request automatically.
   - `HttpURLConnection` on Android API 24 has no per-instance API to disable `Authenticator`.
3. **Response Caching (`java.net.ResponseCache`)**:
   - Per-connection caching is safely suppressed by setting `connection.useCaches = false`. The global `defaultUseCaches` is left untouched.

### 3.2 Security Posture & Tradeoff Analysis
- **Fail-Closed on Controlled Headers**: `HttpUrlConnectionTransport` strictly rejects explicit `Cookie` headers in `request.headers` with `CATEGORY_SECURITY_VIOLATION`.
- **Stateless Client Justification**: If genuine, guaranteed per-client isolation from process-ambient `CookieHandler` and `Authenticator` is required by future supervisor layers, adopting a dedicated `OkHttpClient` instance configured with:
  ```kotlin
  OkHttpClient.Builder()
      .cookieJar(CookieJar.NO_COOKIES)
      .authenticator(Authenticator.NONE)
      .proxyAuthenticator(Authenticator.NONE)
      .cache(null)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .build()
  ```
  would be technically justified. The existing Gradle catalog already declares `okhttp = "4.10.0"` in `gradle/libs.versions.toml`.
- **No False Claims**: This PR does **not** make unproven claims that `HttpURLConnection` achieves full process-ambient cookie/auth isolation. The platform behavior is demonstrated hermetically in `HttpUrlConnectionTransportLoopbackServerTest.testLoopbackAmbientCookieHandlerAndAuthenticatorPlatformEvidence`, and the bounded blocker is formally recorded.

---

## 4. Security, TLS & Transport Invariants

### 4.1 Origin Equality & HTTPS Default
- **Strict HTTPS Default**: Origins are validated through `EndpointSecurity.validateBaseOrigin(configuredOrigin, allowHttpLoopback)`. Remote endpoints require `https://`.
- **Loopback HTTP Opt-In**: Plaintext HTTP (`http://`) is strictly restricted to verified loopback addresses (`127.0.0.1`, `localhost`, `10.0.2.2`, `::1`, `[::1]`) and requires explicit `allowHttpLoopback = true` configuration. Broad remote HTTP is strictly rejected.
- **Origin Equality Check**: `HttpUrlConnectionTransport.execute(request)` verifies that `request.baseOrigin` equals the transport instance's `validatedOrigin` before opening any connection. Cross-origin dispatch is rejected immediately.
- **No DNS Prevalidation / Custom Resolver**: Relies on standard platform name resolution at connection time without inventing custom DNS resolvers or pre-resolving addresses.

### 4.2 Method & Path Allowlist Enforcement
- Before opening any connection, the transport checks `request.method` and `request.path` using `EndpointSecurity.validateMethodAndPath(request.method, request.path)`.
- Disallowed paths and disallowed method-path combinations (e.g. POST to `/products`, GET to `/auth/login`) are rejected before connection establishment.
- **GET Request Body Rejection**: GET requests containing a non-null request body are strictly rejected before opening a connection (`CATEGORY_SECURITY_VIOLATION`), preventing `HttpURLConnection` from performing an automatic method-flip to POST via `doOutput = true`.

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
- Cursors are validated as bounded opaque tokens (`EndpointSecurity.validateCursor`) up to 255 characters. Valid contract characters (`/`, `=`, `+`, `-`, `_`, `.`) are safely percent-encoded and never parsed or followed as URLs.

### 4.5 Per-Connection Redirect & Cache Suppression
- Automatic redirects are disabled per-connection via `connection.instanceFollowRedirects = false`. The global `HttpURLConnection.setFollowRedirects` default is never mutated.
- Caching is disabled per-connection via `connection.useCaches = false`. The global `defaultUseCaches` protocol default is never mutated.
- 3xx redirects (301, 302, 303, 307, 308) return their HTTP status code directly to the foundation. Credentials are never forwarded to redirected URLs.

### 4.6 Standard TLS Verification
- Uses the platform's default TLS chain and hostname verifier.
- Strictly avoids custom trust-all trust managers, certificate bypasses, or invented pinning implementations.

### 4.7 Finite Bounded Timeouts
- Connect and read timeouts are finite and bounded (default: 10s connect, 15s read; enforced bounds: 100ms minimum, 60s maximum).
- Socket timeouts are caught and mapped to `HttpTransportException(CATEGORY_TIMEOUT)`.

### 4.8 Payload Bounding & Strict UTF-8 Stream Decoding
- **1 MiB Upper Bound**: Maximum allowable request and response payload size is strictly 1 MiB (1,048,576 bytes).
- **Request Body Pre-Check & Strict UTF-8**: The request body length is bounded before allocation, and characters are encoded using a strict UTF-8 encoder (`CodingErrorAction.REPORT`) to reject malformed UTF-16 surrogates without unbounded memory growth. Fixed-length streaming mode is applied.
- **Response Content-Length Pre-Check**: If the `Content-Length` header exceeds 1 MiB, the connection is aborted immediately without reading stream bytes (`CATEGORY_PAYLOAD_TOO_LARGE`).
- **Streaming Limit**: For chunked or unknown content length, byte reading is capped at 1 MiB during stream consumption (`CATEGORY_PAYLOAD_TOO_LARGE`).
- **Strict UTF-8 Decoding**: Response bytes are decoded using `StandardCharsets.UTF_8.newDecoder()` with `CodingErrorAction.REPORT`. Malformed or unmappable byte sequences are strictly rejected (`CATEGORY_MALFORMED_UTF8`), never masked with replacement character `\uFFFD`.

### 4.9 Resource Management & Error Streams
- **Deterministic Teardown**: `outputStream`, `inputStream`, `errorStream`, and `connection.disconnect()` are closed/disconnected in `finally` blocks on all paths.
- **Error Stream Handling**:
  - Responses with 4xx/5xx status codes read `connection.errorStream` directly without falling back to `connection.inputStream` (avoiding ordinary `FileNotFoundException` and preventing socket timeouts from being masked).
  - Empty 4xx/5xx responses (e.g. empty 401 Unauthorized with null `errorStream`) return the authoritative status code with an empty body `""`.
  - 3xx responses read `connection.inputStream` if a diagnostic body is present.
  - Any `SocketTimeoutException` encountered while reading streams is strictly propagated as `CATEGORY_TIMEOUT`, never swallowed into synthetic success or empty bodies.
- **Response Headers Null Safety**: Response header extraction defends against null status keys (HTTP status line) and null list items.
- **Cancellation & Fatal Error Propagation**: Catches `Throwable` to ensure `VirtualMachineError` and `CancellationException` are immediately re-thrown.
- **Safe Exceptions**: `HttpTransportException` carries only a fixed category code and sanitized message. No raw URLs, query tokens, request/response bodies, or low-level exception causes are attached.

---

## 5. Automated Verification Evidence

### 5.1 Unit Test Suite Execution
Tests executed with `./gradlew testDebugUnitTest --tests "com.example.data.mobile.*" --rerun`:

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
| `readclient.http` | `HttpUrlConnectionTransportExecutionTest` | 17 | PASSED |
| `readclient.http` | `HttpUrlConnectionTransportSecurityTest` | 12 | PASSED |
| `readclient.http` | `HttpUrlConnectionTransportLoopbackServerTest` | 8 | PASSED |
| **Total** | **14 test classes** | **127** | **100% PASSED** (0 failures, 0 skipped) |

### 5.2 Contract & Schema Verification
- Python contract validation (`python3 tools/validate-mobile-contract.py` and `python3 -O tools/validate-mobile-contract.py`):
  - **210 tests passed** in both normal and `-O` optimized mode.
  - Verifies exact `manifest.sha256` hash: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659` (Draft Revision 2).

### 5.3 Static Analysis
- Gradle static analysis (`./gradlew lintDebug`): PASSED clean with 0 errors.

### 5.4 Platform & Device Testing Limits
- All automated unit tests execute hermetically on JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`) using mock connections and an in-process local JDK `HttpServer` bound strictly to loopback `127.0.0.1`.
- Hermetic loopback tests do not claim to prove on-device TLS certificates or real device networking.
- Broad Android instrumentation visual test runs are not executed due to known native visual emulator evidence hangs; verification relies on focused mobile unit test suites and official contract parity validators.
