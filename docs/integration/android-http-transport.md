# Android Read Client HTTP Transport Adapter (Phase 2B)

## 1. Architectural Scope & Context

This document details the minimal Android API 24+ compatible HTTP transport adapter for the Small Trader Mobile V1 Read Client Foundation. It implements `ReadClientTransport` via standard platform `java.net.HttpURLConnection` / `javax.net.ssl.HttpsURLConnection` under `com.example.data.mobile.readclient.http.*`.

### Design Philosophy & Zero Extra Dependencies
- **Platform Native**: Utilizes built-in `HttpURLConnection` and `HttpsURLConnection` available on Android API 24+ and JDK 21.
- **Zero Gradle / Dependency Bloat**: Avoids adding OkHttp, Retrofit, Ktor, or custom HTTP client libraries.
- **Strict Separation of Concerns**: Bridges pure Kotlin request/response models (`TransportRequest`, `TransportResponse`) to HTTP socket streaming while enforcing origin equality, endpoint allowlisting, bounded payloads, and credential protection.

---

## 2. Unwired Gates & System Requirements

> [!IMPORTANT]
> **Unwired Gate Status**: The transport adapter is implemented and verified hermetically. The following platform requirements are documented as unwired gates and are deliberately preserved without modifying `AndroidManifest.xml` or application runtime entrypoints in this PR:
> 1. **Android Internet Permission**: Real device/emulator communication requires:
>    ```xml
>    <uses-permission android:name="android.permission.INTERNET" />
>    ```
>    This manifest change is deferred until supervisor application wiring.
> 2. **Main Thread Policy (`NetworkOnMainThreadException`)**:
>    Platform `HttpURLConnection` performs synchronous blocking I/O. In an Android runtime, invoking `execute` on the main (UI) thread will trigger an immediate `android.os.NetworkOnMainThreadException`. Callers must dispatch `SmallTraderReadClient` / transport calls on a background worker thread or coroutine dispatcher (e.g. `Dispatchers.IO`).
> 3. **Blocking I/O Cancellation Honesty**:
>    Standard Java `HttpURLConnection` relies on blocking stream operations (`InputStream.read()`, `OutputStream.write()`). While coroutine `CancellationException` is strictly propagated and never swallowed, blocking socket I/O on standard Java sockets does not guarantee instantaneous thread interruption without closing the underlying socket connection or waiting for socket timeouts. The implementation does not claim immediate bound blocking I/O cancellation without socket teardown.

---

## 3. Security, TLS & Transport Invariants

### 3.1 Origin Equality & HTTPS Default
- **Strict HTTPS Default**: Origins are validated through `EndpointSecurity.validateBaseOrigin(configuredOrigin, allowHttpLoopback)`. Remote endpoints require `https://`.
- **Loopback HTTP Opt-In**: Plaintext HTTP (`http://`) is strictly restricted to verified loopback addresses (`127.0.0.1`, `localhost`, `10.0.2.2`, `::1`, `[::1]`) and requires explicit `allowHttpLoopback = true` configuration. Broad remote HTTP is strictly rejected.
- **Origin Equality Check**: `HttpUrlConnectionTransport.execute(request)` verifies that `request.baseOrigin` equals the transport instance's `validatedOrigin` before opening any connection. Cross-origin dispatch is rejected immediately.
- **No DNS Prevalidation / Custom Resolver**: Relies on standard platform name resolution at connection time without inventing custom DNS resolvers or pre-resolving addresses.

### 3.2 Method & Path Allowlist Enforcement
- Before opening any connection or invoking test seams, the transport checks `request.method` and `request.path` using `EndpointSecurity.validateMethodAndPath(request.method, request.path)`.
- Disallowed paths (e.g. unallowlisted endpoints, admin paths) and disallowed method-path combinations (e.g. POST to `/products`, GET to `/auth/login`) are rejected before connection establishment.

### 3.3 Controlled Headers & Credential Placement
- **Controlled Request Headers**: Only `Accept`, `Content-Type`, and `Authorization` are permitted. Any unexpected or arbitrary headers (e.g. `Cookie`, `X-Custom-Auth`) are rejected with `CATEGORY_SECURITY_VIOLATION`.
- **Authorization Header Validation**: If present, `Authorization` must begin with `Bearer ` and the token portion must pass strict token validation (`ValidationUtils.validateToken`), rejecting CRLF injection, control characters, and whitespace.
- **No URL Credentials**: URLs must not contain userinfo credentials (`user:pass@host`).

### 3.4 Opaque Query Parameter Percent-Encoding
- All query parameters (`limit`, `cursor`, `search`) are UTF-8 URL-encoded (`URLEncoder.encode(..., "UTF-8")`).
- Cursors are validated as bounded opaque tokens (`EndpointSecurity.validateCursor`) up to 255 characters. Valid contract characters (`/`, `=`, `+`, `-`, `_`, `.`) are safely percent-encoded (e.g. `/` -> `%2F`, `=` -> `%3D`) and never parsed or followed as URLs.

### 3.5 Global & Per-Connection Redirect Suppression
- Automatic redirects are disabled globally via `HttpURLConnection.setFollowRedirects(false)` and per-connection via `connection.instanceFollowRedirects = false`.
- 3xx redirects (301, 302, 303, 307, 308) return their HTTP status code directly to the foundation. Under no circumstances is the `Authorization` header forwarded to redirected URLs.

### 3.6 Standard TLS Verification
- Uses the platform's default TLS chain and hostname verifier.
- Strictly avoids custom trust-all trust managers, certificate bypasses, or invented pinning implementations.

### 3.7 Finite Bounded Timeouts
- Connect and read timeouts are finite and bounded (default: 10s connect, 15s read; enforced bounds: 100ms minimum, 60s maximum).
- Socket timeouts are caught and mapped to `HttpTransportException(CATEGORY_TIMEOUT)`.

### 3.8 Payload Bounding & Strict UTF-8 Stream Decoding
- **1 MiB Upper Bound**: Maximum allowable response payload size is 1 MiB (1,048,576 bytes).
- **Content-Length Pre-Check**: If the `Content-Length` header exceeds 1 MiB, the connection is aborted immediately without reading stream bytes (`CATEGORY_PAYLOAD_TOO_LARGE`).
- **Streaming Limit**: For chunked or unknown content length, byte reading is capped at 1 MiB during stream consumption (`CATEGORY_PAYLOAD_TOO_LARGE`).
- **Strict UTF-8 Decoding**: Response bytes are decoded using `StandardCharsets.UTF_8.newDecoder()` with `CodingErrorAction.REPORT`. Malformed or unmappable byte sequences are strictly rejected (`CATEGORY_MALFORMED_UTF8`), never masked with replacement character `\uFFFD`.

### 3.9 Resource Management & Safe Exceptions
- **Deterministic Teardown**: `outputStream`, `inputStream`, `errorStream`, and `connection.disconnect()` are closed/disconnected in `finally` blocks on all paths (success, client error, server error, timeout, and stream failure).
- **Safe Exceptions**: `HttpTransportException` carries only a fixed category code and sanitized message. No raw URLs with tokens, request/response bodies, or low-level exception causes are attached, preventing accidental leakage in loggers or crash reporters.
- **Exception Categories**:
  - `SECURITY_VIOLATION`
  - `CONNECT_TIMEOUT`
  - `READ_TIMEOUT`
  - `NETWORK_TIMEOUT`
  - `NETWORK_IO_ERROR`
  - `PAYLOAD_TOO_LARGE`
  - `MALFORMED_UTF8`
  - `UNSUPPORTED_PROTOCOL`

---

## 4. Test Seam & Hermetic Verification

### 4.1 Connection Factory Seam
```kotlin
fun interface HttpUrlConnectionFactory {
    @Throws(IOException::class)
    fun openConnection(url: URL): HttpURLConnection
}
```
In production, `DefaultHttpUrlConnectionFactory` delegates to `url.openConnection() as HttpURLConnection`. In hermetic tests, custom `MockHttpURLConnection` or local test server factories are injected.

> [!NOTE]
> The test seam cannot bypass security invariants. Origin validation, method/path allowlisting, header checking, timeout application, redirect suppression, 1 MiB bounding, and UTF-8 validation execute within `HttpUrlConnectionTransport` and cannot be circumvented by the factory.

### 4.2 Platform Verification Limits
- Hermetic test suites execute on JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`).
- Verification uses mock connections and an in-process local JDK `HttpServer` bound to loopback `127.0.0.1`.
- Live backend calls and fake claims of HTTPS against real devices are explicitly avoided.

---

## 5. Automated Verification Evidence

### 5.1 Unit Test Suite Execution
Tests executed with `./gradlew testDebugUnitTest --tests "com.example.data.mobile.*"`:

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
| `readclient.http` | `HttpUrlConnectionTransportExecutionTest` | 11 | PASSED |
| `readclient.http` | `HttpUrlConnectionTransportSecurityTest` | 10 | PASSED |
| `readclient.http` | `HttpUrlConnectionTransportLoopbackServerTest` | 6 | PASSED |
| **Total** | **14 test classes** | **117** | **100% PASSED** (0 failures, 0 skipped) |

### 5.2 Contract & Schema Verification
- Python contract validation (`python3 tools/validate-mobile-contract.py` and `python3 -O tools/validate-mobile-contract.py`):
  - **210 tests passed** in both normal and `-O` optimized mode.
  - Verifies exact `manifest.sha256` hash: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659` (Draft Revision 2).

### 5.3 Static Analysis
- Gradle static analysis (`./gradlew lintDebug`): PASSED clean with 0 errors.
