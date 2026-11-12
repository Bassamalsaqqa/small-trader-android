# Android Secure Staff Session Persistence Foundation

## 1. Architectural Scope & Context

This document details the minimal Android API 24+ secure staff session storage foundation for the Small Trader Android client. It provides authenticated, encrypted credential persistence under `com.example.data.mobile.session.SecureStaffSessionStore`.

### Design Principles & Minimal Crypto Boundary
- **AndroidKeyStore Runtime AES-256-GCM**: Session credentials are encrypted using 256-bit AES keys generated lazily within `AndroidKeyStore` using `AES/GCM/NoPadding`.
- **Private Non-Backup Storage**: The encrypted session file is located strictly under `context.noBackupFilesDir` (`small_trader_secure_staff_session.bin`), preventing automatic backup or cloud exfiltration via Android Auto Backup.
- **Envelope Wire Format**: Binary envelope structure:
  - 4 bytes magic header: `0x53, 0x53, 0x53, 0x01` (`"SSS\x01"`)
  - 1 byte IV length: `12` (`0x0C`)
  - 12 bytes unique IV (nonce)
  - 4 bytes payload length (big-endian Int)
  - Variable-length ciphertext with 128-bit (16-byte) standard GCM authentication tag.
- **Additional Authenticated Data (AAD)**: Every cryptographic operation updates AAD with contextual metadata:
  ```
  purpose=staff_session;pkg=<applicationId>;v=1
  ```
  Tampering with the record purpose, package identity, or format version invalidates the GCM authentication tag and fails closed.
- **Standard Platform Crypto**: Employs standard JDK / Android security providers without deprecated `EncryptedSharedPreferences`, passphrase derivation, or debug plaintext fallbacks.
- **Lazy Key Creation**: Keys are generated within `AndroidKeyStore` only when the application explicitly invokes session storage APIs; no external `keytool` invocations or user-provided secrets are used.

---

## 2. Hardware Backing & Device Validation Constraints

### Hardware Keystore Support
- **Hardware Preferred**: When available on the device, `AndroidKeyStore` automatically places keys within hardware-backed security modules (Trusted Execution Environment / TEE).
- **No Unconditional StrongBox**: To maintain compatibility across devices without dedicated StrongBox security chips, `setIsStrongBoxBacked(true)` is NOT unconditionally forced (which throws `StrongBoxUnavailableException` on unsupported hardware).
- **Honest Hardware Verification Claim**: The system does NOT claim that keys are unconditionally hardware-backed across all Android devices.

### Device Validation Holds
> [!IMPORTANT]
> **On-Device Hardware Verification Status**: On-device Android Keystore hardware operations were **NOT VERIFIED** in this phase because no emulator or physical device was available via `device_list`, and direct `adb` driving is prohibited. Hermetic test suites execute on JVM using an isolated test seam (`JvmAesGcmCryptoEngine`). JVM cryptography and Robolectric do not constitute proof of hardware Keystore execution on physical devices.

---

## 3. Threat Boundary & Fail-Closed Invariants

### Fail-Closed Typed Error Modeling
The session store translates all failures into fixed, fail-closed typed results (`SessionReadResult` / `SessionStorageError`):
- `Locked`: Cryptographic key is locked, requires user re-authentication, or has been permanently invalidated.
- `Unavailable`: Cryptographic key alias is missing from keystore or provider is uninitialized.
- `Corrupt`: GCM authentication tag verification failed, ciphertext tampered, magic header invalid, or payload corrupted.
- `Oversized`: File size exceeds the 1 MiB hard upper bound before memory allocation.
- `Expired`: RFC 3339 expiration timestamp has elapsed.
- `ScopeMismatch`: Requested read scope (device, actor, company) does not match stored metadata.
- `IoError`: Disk I/O read or atomic replacement failure.

### Preservation of Corrupted Records (No Silent Deletion)
If a record cannot be decrypted due to key unavailability, invalidation, ciphertext tampering, format mismatch, or expiration:
- The store **NEVER** silently deletes the existing file on disk.
- The store **NEVER** recreates the key and overwrites the existing file.
- **Rationale**: Silently deleting corrupted session records or rotating keys on decrypt failure could discard uncommitted offline cash ledger records, pending sync tokens, or audit forensic evidence.

---

## 4. No Auth Posting Authority

> [!WARNING]
> **Presentation Label Only**: The stored `roleLabel` (e.g. `"sales_staff"`) is strictly an informative display label for UI rendering. It conveys **ZERO** authorization grants on the client. Server-side authorization is always authoritative. Storing a staff session locally does not grant authority to post sales, modify ledger entries, or bypass backend policy.

---

## 5. Scope Protection & Credential Privacy

### Explicit Read Scoping
Callers must supply an explicit `SessionReadScope` when reading sessions:
- `expectedDeviceUuid`: Required; verified against the session's bound device UUID.
- `expectedActorUlid`: Optional; if supplied, must match the authenticated staff actor ULID.
- `expectedCompanyId`: Optional; if supplied, must match the selected company ULID.

If any parameter mismatches, the store returns `SessionReadResult.ScopeMismatch` and **strictly conceals the Bearer token**. Cross-scope session fallback is prohibited.

### Credential Redaction & Sanitized Errors
- `StaffSessionRecord.toString()` redacts sensitive fields:
  ```kotlin
  "StaffSessionRecord(token='Bearer [REDACTED]', passwordEpoch='[REDACTED]', actorEmail='[REDACTED]', ...)"
  ```
- Bearer tokens, passwords, and decrypted bytes are never logged or propagated in exception causes.
- Cryptographic exceptions discard raw platform causes to eliminate secret leakage through bug telemetry.

---

## 6. Installation Identity & Logout Lifecycle

### Stable Installation UUID
- A persistent `ClientUuid` is generated once upon initial launch and stored under `context.noBackupFilesDir/small_trader_device_installation.json`.
- Managed independently by `InstallationIdentityStore`.

### Logout Credential Purge Boundary
When staff logs out (`clearSession()`):
- **Cleared**: ONLY `small_trader_secure_staff_session.bin` (and any associated temporary write files) are deleted.
- **Preserved**:
  - The stable `installationId` (`small_trader_device_installation.json`) is strictly preserved across logouts.
  - Offline Room database files and outbox event queues are **NEVER** purged on staff logout.
- **No Revocation Claim**: Local `clearSession()` makes NO claim of server-side revocation. Remote token invalidation is the sole responsibility of the network logout endpoint (`/api/mobile/v1/auth/logout`).
- **No Queue Unlock Claim**: Staff logout does NOT unlock offline outbox queues or bypass pending synchronization gates.
- **No Automatic Refresh**: Background token refresh is out of scope and not implemented.

---

## 7. Atomic Storage & Concurrency Guarantees

### Crash-Safe Atomic Replace & Durability Limits
- **Unique Sibling Temporary Files**: Writes are staged using uniquely named sibling temporary files (`small_trader_secure_staff_session.bin.<UUID>.tmp`) in the same directory, preventing filename collisions across concurrent operations.
- **Operation-Owned Cleanup**: Each write operation strictly tracks and cleans up its own temporary file in `finally` on failure. `clearSession()` deletes only the session file itself and never touches external temporary files.
- **Physical Media Flush**: Flushes data and synchronizes file descriptor to physical disk (`fos.fd.sync()`).
- **Atomic Replace Without Truncation Fallback**: Atomically replaces the target file via Android API 24-compatible atomic rename (`tempFile.renameTo(sessionFile)`). On rename failure, the operation fails closed with `SessionStorageError.IoError` and deletes the temp file. The dangerous `copyTo(overwrite = true)` fallback has been removed to prevent truncating or destroying prior valid records.
- **Crash Durability Limits (Honest Qualification)**: File descriptor sync (`fsync`) ensures data bytes reach persistent storage before rename. However, POSIX directory metadata durability across sudden power-loss crash requires parent directory `fsync` (which is not portably exposed in Java/Android userland without native file descriptors). Under crash-stop scenarios, atomic replacement guarantees that partial writes never corrupt prior valid records, and no destructive recovery or key recreation is ever performed.

### Bounded Memory & Streaming Safety (TOCTOU Elimination)
- **Bounded Streaming Read**: Reads are streamed via `BoundedStreamReader` up to 1 MiB with a 1-byte probe (`1,048,577 bytes`). If a file or stream grows beyond 1 MiB during reading, the operation immediately fails closed with `SessionStorageError.Oversized` without reading unbounded data.
- **Allocation Cap**: Initial buffer allocation is capped at `minOf(file.length(), 1024 * 1024)`, preventing unbounded pre-allocation attacks. Both `SecureStaffSessionStore` and `InstallationIdentityStore` enforce bounded reads.
- **Zero Java Serialization**: Deserialization uses `StrictJsonParser` with canonical JSON. Java standard serialization (`ObjectInputStream`) is completely avoided to prevent remote code execution and deserialization exploits.

### Process-Wide Concurrency & Multi-Instance Coordination
- **Canonical-Path Process Locks**: Synchronized via `SessionProcessLocks` keyed by canonical file path. Multiple store or identity instances targeting the same physical file share the same `ReentrantLock`.
- **Linearizable Full Save/Read/Clear**: `saveSession` holds the shared lock across key verification, serialization, encryption, and atomic rename. A logout waiting behind an in-flight save runs after that save and removes its record. Saves explicitly started after logout are separate operations; this store does not cancel higher-level pending login jobs.
- **Key Generation Coordination**: First-key generation is synchronized process-wide by key alias (`SessionProcessLocks.getKeyLock`).
- **Existing Record Key Recoverability**: Before replacing an existing session file on disk, `saveSession` checks key presence; encryption must also succeed before replacement. This does not authenticate old ciphertext during a new login. If the key is missing or permanently invalidated, replacement is refused, existing ciphertext is preserved, and key recreation is prohibited.
- **Multi-Process Limitation**: Concurrency across independent Android application/service processes sharing storage is **NOT CLAIMED** without OS-level `FileLock` coordination.

### Error Sanitization & Data Concealment
- Outward error results strictly discard raw platform exception text, variable interpolation, and decrypted payloads.
- Scope mismatches and corruption errors use fixed safe category messages without reflecting decrypted fields or expected ULIDs/UUIDs.
- `CancellationException` and fatal JVM errors (`VirtualMachineError`) are preserved and rethrown.

---

## 8. Test Verification & Coverage

Hermetic tests exercise the following foundation boundaries in `com.example.data.mobile.session.SecureStaffSessionStoreTest` (37 unit tests):
1. **Encrypt/Read Roundtrip**: Verifies exact preservation of token, ULID, device UUID, password epoch, expiry, and contextual metadata.
2. **Plaintext Absence**: Proves no raw secret tokens, epochs, emails, or names appear on disk.
3. **Tamper Resilience**: Proves ciphertext tampering, nonce mutation, AAD manipulation, magic mismatch, and length alteration fail closed with `Corrupt` and preserve the disk file.
4. **Key Invalidation**: Proves missing keys return `Unavailable` and locked keys return `Locked` without file deletion or key recreation.
5. **Scope Isolation**: Proves device, actor, or company mismatch conceals tokens with sanitized messages.
6. **Expiry Enforcement**: Proves expired sessions return `Expired` and conceal tokens while preserving file records.
7. **Oversized / Truncated Files**: Proves >1 MiB files fail with `Oversized` without memory allocation, and truncated envelopes fail with `Corrupt`.
8. **Atomic Fault Recovery**: Proves write failures leave previous valid records undamaged.
9. **Single-Process Concurrency**: Proves multi-threaded concurrent reads and writes maintain integrity.
10. **Logout Isolation**: Proves `clearSession()` preserves the installation UUID and simulated outbox queue sentinels intact.
11. **Redaction Fidelity**: Proves `toString()` and error messages never expose secret credentials.
12. **Storage Path**: Proves factory resolves strictly within `context.noBackupFilesDir`.
13. **RFC 3339 Strict Parsing**: Proves millisecond fraction (`.123Z`), nonzero seconds, positive/negative offsets with/without fractions, and exact expiry boundaries (`now == expiry - 1` vs `now == expiry`).
14. **RFC 3339 Malformed Input**: Proves strict rejection of invalid calendar dates (non-leap year Feb 29, month 13, day 32), invalid timezone offsets, and trailing newlines (`\n`, `\r\n`).
15. **Simultaneous Identity Instances**: Proves simultaneously constructed `InstallationIdentityStore` instances coordinate under canonical-path lock and produce identical stable UUIDs.
16. **Multi-Instance Store Coordination**: Proves distinct `DefaultSecureStaffSessionStore` instances coordinate under canonical-path lock.
17. **Linearizable Save/Clear Barrier**: Blocks encryption in one store instance, verifies logout in another instance waits, then verifies no session remains after both complete, without a final cleanup call.
18. **Unique Sibling Temp Files**: Proves save uses unique sibling temporary files without collisions and operation-owned cleanup.
19. **Rename Failure No-Truncation**: Proves injected rename failure fails closed and preserves prior valid records without truncation.
20. **Streaming Read TOCTOU**: Proves bounded stream reader detects file growth exceeding 1 MiB via 1-byte probe.
21. **Key Recoverability Refusal**: Proves missing-key and locked-key saves over existing files fail closed, preserve disk bytes, and refuse key recreation.

### Suite Summary & Exact Counts
- **Targeted Mobile Unit Tests**: 167 unit tests across 16 testsuites (`com.example.data.mobile.*`), 0 failures, 0 errors, 0 skipped.
- **Contract Verification Suite**: 210 tests passed in both normal (`python3 tools/validate-mobile-contract.py`) and optimized (`python3 -O tools/validate-mobile-contract.py`) execution.
- **Lint Verification**: 63 baseline warnings in `app/build/reports/lint-results-debug.xml`, 0 errors.

Installation identity reads fail closed on malformed UTF-8/JSON, invalid UUIDs, oversized records, and I/O failure; existing files are never replaced after a read failure. Cached identities do not hide corrupt or missing files. Missing files after initialization require explicit recovery rather than silent UUID rotation. Logout reports a failed file deletion as `IoError`.
