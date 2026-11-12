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

### Crash-Safe Atomic Replace
- Writes are performed using a temporary file (`small_trader_secure_staff_session.bin.tmp`).
- Flushes data and synchronizes file descriptor to physical disk (`fos.fd.sync()`).
- Atomically replaces the target file via atomic rename (`tempFile.renameTo(sessionFile)`).
- If write fails, the temporary file is deleted in `finally`, leaving any prior valid record completely intact.

### Bounded Memory & Serialization Safety
- **Finite 1 MiB Upper Bound**: Files exceeding 1 MiB (`1,048,576 bytes`) are rejected immediately before reading into memory or allocating buffers.
- **Zero Java Serialization**: Deserialization uses `StrictJsonParser` with canonical JSON. Java standard serialization (`ObjectInputStream`) is completely avoided to prevent remote code execution and deserialization exploits.

### Single-Process Concurrency & Multi-Process Boundary
- **Single-Process Safe**: Multi-threaded read, write, and clear operations within the same process are synchronized via `ReentrantLock`.
- **Multi-Process Limitation**: Multi-process concurrency across independent Android application/service processes sharing storage is **NOT CLAIMED** without OS-level `FileLock` coordination.

---

## 8. Test Verification & Coverage

Hermetic test suites prove all architectural invariants in `com.example.data.mobile.session.SecureStaffSessionStoreTest`:
1. **Encrypt/Read Roundtrip**: Verifies exact preservation of token, ULID, device UUID, password epoch, expiry, and contextual metadata.
2. **Plaintext Absence**: Proves no raw secret tokens, epochs, emails, or names appear on disk.
3. **Tamper Resilience**: Proves ciphertext tampering, nonce mutation, AAD manipulation, magic mismatch, and length alteration fail closed with `Corrupt` and preserve the disk file.
4. **Key Invalidation**: Proves missing keys return `Unavailable` and locked keys return `Locked` without file deletion or key recreation.
5. **Scope Isolation**: Proves device, actor, or company mismatch conceals tokens.
6. **Expiry Enforcement**: Proves expired sessions return `Expired` and conceal tokens while preserving file records.
7. **Oversized / Truncated Files**: Proves >1 MiB files fail with `Oversized` without memory allocation, and truncated envelopes fail with `Corrupt`.
8. **Atomic Fault Recovery**: Proves write failures leave previous valid records undamaged.
9. **Single-Process Concurrency**: Proves multi-threaded concurrent reads and writes maintain integrity.
10. **Logout Isolation**: Proves `clearSession()` preserves the installation UUID and simulated outbox queue sentinels intact.
11. **Redaction Fidelity**: Proves `toString()` and error messages never expose secret credentials.
12. **Storage Path**: Proves factory resolves strictly within `context.noBackupFilesDir`.
