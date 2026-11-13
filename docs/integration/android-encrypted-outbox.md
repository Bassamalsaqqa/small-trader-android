# Economic payload encryption and backup policy

Base: PR20 8d05f759ec96999891d54f182b578352638c23a6, fetched and verified against origin/t3/android-v1-read-pilot. Revision 3 contract files are unchanged.

The repository persists AES-256-GCM envelopes in the existing payload_bytes BLOB. Header STO/1, 12-byte random platform nonce, ciphertext and 128-bit tag; maximum plaintext 1 MiB. Length-prefixed UTF-8 AAD binds purpose/format, company, actor, device, event UUID, lease, capture timestamp, digest and event version. No credential key sharing: the outbox alias is small_trader_economic_outbox_v1. Key creation is permitted only for an empty database or an explicitly validated all-plaintext conversion. Encryption itself cannot create a missing key. Session behavior retains its existing default.

All repository event outputs decrypt stored bytes and verify the stored digest. No decrypted payload serialization, repricing, ID regeneration, rekey or event deletion occurs. Foreign actor/device reads and collision responses resolve scope before decryption. Claims decrypt within the CAS transaction, so failure rolls back leases. Confirmation authenticates original bytes before any status mutation. Crypto errors carry fixed categories without causes; cancellation and fatal errors propagate. Logout does not touch the outbox key or database.

## Coverage boundary

This is **payload encryption, not full database encryption**. Identity, timestamps, lease IDs, digests, status, server mapping IDs, error/reason strings and SQLite structure remain plaintext. Those fields can be identifying; caller-provided diagnostic strings must not contain secrets. Room materializes BLOBs before the cipher checks their size, so the envelope bound does not prevent allocation by Room of a maliciously enlarged database cell. An attacker able to replace the whole database can roll back an older valid snapshot; GCM is not an anti-rollback ledger. No multiprocess coordination or hardware backing guarantee is claimed.

Room version 1 and identity 6eddfecc8dabbb7ec370b858ec36714a are preserved. This is a payload-format conversion, not a fake schema upgrade. Unknown physical schemas still fail; no destructive migration fallback exists.

## Explicit legacy conversion

Normal reads reject plaintext with LEGACY_CONVERSION_REQUIRED. No startup migration, worker activation or capture UI is added. An owner-controlled recovery/integration flow must explicitly invoke convertLegacyPayloads before using a legacy queue. The transaction first verifies legacy digests, requires the existing key if any encrypted row exists, then authenticates encrypted rows and replaces only plaintext BLOBs. Any exception rolls back all row changes. Existing identities, delivery states and empty-queue auth locks are untouched. Retry after a rolled-back conversion is supported; an already-created key may remain after rollback, which is harmless and must not be deleted.

Conversion does **not** securely erase plaintext from historical free pages, WAL, journals, filesystem snapshots or prior backups. No automatic VACUUM/checkpoint/deletion is performed. A pre-existing plaintext installation therefore does not acquire a claim of forensic plaintext absence through conversion. The disk absence test applies to newly encrypted captures. Tests use disk SQLite close/reopen and a schema-1 fixture built from the frozen exported schema; they do not prove power-loss durability.

## Backup and device blockers

allowBackup=false plus exhaustive domain exclusions in legacy backup rules and both API31 cloud-backup and device-transfer sections excludes all database files (including WAL/SHM/journal), ordinary and device-protected files/preferences, root and external domains. Session and installation identity already live in noBackupFilesDir. Restoring an encrypted queue without its installation Keystore key fails closed without deletion or key replacement. Backup transport/OEM behavior is not device verified.

Real Android Keystore, device process kill, instrumentation and actual transfer/restore: **BLOCKED / UNVERIFIED**, no approved device host. No emulator, KVM, Studio, host changes, real network pilot, financial activation or private credential reads were used. Hermetic JVM AES-GCM and Robolectric SQLite are not device proof.

Independent review is required before acceptance. No merge or self-approval.

Encrypted payloads are not readable by older binaries. Deployment must prevent downgrade to a plaintext-only outbox implementation; no downgrade compatibility or automatic rollout is claimed. The explicit conversion API is intentionally unwired until an owner-controlled recovery flow is qualified.

An all-plaintext legacy queue has no cryptographic installation provenance. Explicit conversion cannot distinguish an original legacy database from a copied legacy database; it must not be treated as authorization to restore an economic queue. Qualification of that operator/recovery boundary is still required. The automatic missing-key restore rejection applies to encrypted queues.

Final local validation: 402 passed, 1 retired pilot skipped, zero failures/errors across 39 suites; lintDebug succeeded. Python normal and optimized each passed 303 checks. Exact XML counts, lint and logs are in encrypted-outbox-evidence/. APK assembly is blocked by the missing checkout debug.keystore; application and test compilation passed.
