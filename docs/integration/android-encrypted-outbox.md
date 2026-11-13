# Economic payload encryption and backup policy

Base: PR20 8d05f759ec96999891d54f182b578352638c23a6, fetched and verified against origin/t3/android-v1-read-pilot. Revision 3 contract files are unchanged.

The repository persists AES-256-GCM envelopes in the existing payload_bytes BLOB. Header STO/1, 12-byte random platform nonce, ciphertext and 128-bit tag; maximum plaintext 1 MiB. Length-prefixed UTF-8 AAD binds purpose/format, company, actor, device, event UUID, lease, capture timestamp, digest and event version. No credential key sharing: the outbox alias is small_trader_economic_outbox_v1. Key creation is permitted only for an empty database or an explicitly validated all-plaintext conversion. Encryption itself cannot create a missing key. Session behavior retains its existing default.

All repository event outputs decrypt stored bytes and verify the stored digest. No decrypted payload serialization, repricing, ID regeneration, rekey or event deletion occurs. Foreign actor/device reads and collision responses resolve scope before decryption. Claims decrypt within the CAS transaction, so failure rolls back leases. Confirmation authenticates original bytes before any status mutation. Crypto errors carry fixed categories without causes; cancellation and fatal errors propagate. Logout does not touch the outbox key or database.

## Coverage boundary

This is **payload encryption, not full database encryption**. Identity, timestamps, lease IDs, digests, status, server mapping IDs, error/reason strings and SQLite structure remain plaintext. Those fields can be identifying; caller-provided diagnostic strings must not contain secrets. Room materializes BLOBs before the cipher checks their size, so the envelope bound does not prevent allocation by Room of a maliciously enlarged database cell. An attacker able to replace the whole database can roll back an older valid snapshot; GCM is not an anti-rollback ledger. No multiprocess coordination or hardware backing guarantee is claimed.

Room version 1 and identity 6eddfecc8dabbb7ec370b858ec36714a are preserved. This is a payload-format conversion, not a fake schema upgrade. Unknown physical schemas still fail; no destructive migration fallback exists.

## Explicit legacy conversion and format detection

Normal reads reject plaintext with LEGACY_CONVERSION_REQUIRED. No startup migration, worker activation or capture UI is added. An owner-controlled recovery/integration flow must explicitly invoke convertLegacyPayloads before using a legacy queue.

Legacy payload conversion enforces strict fail-closed format detection and metadata parity:
- Any payload bearing an STO envelope prefix (`STO`) that is not valid STO/1 (including future envelope versions like STO/2+ or truncated envelopes) is rejected immediately before write; it is never admitted as plaintext.
- Candidate plaintext is strictly validated as a canonical bare JSON event for supported versions (v1: `OfflineSaleEvent`, v2: `OfflineSaleEventV2`) matching the row's `event_version`.
- The parsed event is validated against ALL immutable row metadata: `company_id`, `actor_id`, `device_id`, `client_event_id`, `device_lease_id`, `captured_at`, `event_version`, and `payload_digest`.
- Canonical byte-exact parity is enforced (`bytes.contentEquals(canonicalSerializerBytes)`); formatted whitespace, non-canonical key order, digest-consistent `{}` junk, cross-scope events, and format downgrades are strictly rejected. Zero parse-and-normalize rewriting occurs.
- Size is bounded to 1 MiB (`ContractConstants.MAX_PAYLOAD_BYTES`) with strict UTF-8 decoding under `CodingErrorAction.REPORT`.
- Model validation uses pure v1/v2 contracts and does not invent financial policy beyond capture existing currency qualified metadata; no guessing currency scale.
- Conversion is fully atomic: all events are pre-validated, and all existing encrypted rows are pre-authenticated via test decryption before ANY database writes occur.
- If existing encrypted rows are present and the key is missing or invalid, conversion fails closed (`KEY_UNAVAILABLE`) without generating a replacement key or incrementing the key generation counter, preserving the entire queue.
- Any mid-conversion fault (e.g. cancellation) rolls back all rows (not just the first), preserving original plaintext bytes, delivery states, and empty-queue auth locks.

Conversion does **not** securely erase plaintext from historical free pages, WAL, journals, filesystem snapshots or prior backups. No automatic VACUUM/checkpoint/deletion is performed. A pre-existing plaintext installation therefore does not acquire a claim of forensic plaintext absence through conversion. The disk absence test applies to newly encrypted captures. Tests use disk SQLite close/reopen and a schema-1 fixture built from the frozen exported schema; they do not prove power-loss durability.

## Persisted release gates and sync poison handling

The test suite enforces persisted release gates using real disk SQLite databases with before/after and close/reopen snapshots of all involved event rows, delivery claims, and auth locks:
1. **Batch claim failure rollback**: when claiming multiple records where an earlier record succeeds but a subsequent record encounters an authentication failure / key lock, the entire claim transaction rolls back on disk, leaving zero stranded leases (`status = 'pending'`, null claim tokens and expirations).
2. **Confirmation and reconciliation preservation**: corrupted ciphertexts or missing keys during `confirmDelivery` or `reconcileDeliveryConfirmation` fail closed immediately, leaving original claims, delivery status, event bytes, server mapping IDs, and auth locks intact across database reopen.
3. **Mixed queue preservation**: mixed plaintext and ciphertext conversion with a missing key refuses conversion and preserves the entire queue and key generation counter intact without generating replacement keys.
4. **Mid-conversion fault rollback**: mid-conversion faults roll back all event rows (not just the first row), preserving delivery states and empty-queue scope locks across close and reopen.
5. **Format rejection**: future STO envelopes (e.g. STO/2), row metadata mismatches, non-canonical byte formatting, and arbitrary `{}` junk matching the digest are rejected before write.
6. **Production key coordination seam**: concurrent key initialization across instances is coordinated via `SessionProcessLocks.getKeyLock(alias)` with mutual exclusion verified via barriers. In-process JVM coordination only; does NOT claim multiprocess Keystore proof from JVM.

**Sync poison record behavior clarification**: In commit 2d81b21 and subsequent hardening, stored corrupt payload bytes fail closed during in-transaction decryption inside `claimNextBatch`, rolling back the claim transaction to keep the record in `pending` without stranding a worker lease. This replaces the earlier unencrypted behavior where the sync coordinator claimed the corrupt record and parked it in `conflict`. No false historical test coverage is claimed.

The explicit conversion API is intentionally unwired until an owner-controlled recovery flow is qualified; no automatic startup migration exists.

## Backup and device blockers

allowBackup=false plus exhaustive domain exclusions in legacy backup rules and both API31 cloud-backup and device-transfer sections excludes all database files (including WAL/SHM/journal), ordinary and device-protected files/preferences, root and external domains. Session and installation identity already live in noBackupFilesDir. Restoring an encrypted queue without its installation Keystore key fails closed without deletion or key replacement. Backup transport/OEM behavior is not device verified.

Real Android Keystore, device process kill, instrumentation and actual transfer/restore: **BLOCKED / UNVERIFIED**, no approved device host. No emulator, KVM, Studio, host changes, real network pilot, financial activation or private credential reads were used. Hermetic JVM AES-GCM and Robolectric SQLite are not device proof.

Independent review is required before acceptance. No merge or self-approval.

Encrypted payloads are not readable by older binaries. Deployment must prevent downgrade to a plaintext-only outbox implementation; no downgrade compatibility or automatic rollout is claimed. The explicit conversion API is intentionally unwired until an owner-controlled recovery flow is qualified.

An all-plaintext legacy queue has no cryptographic installation provenance. Explicit conversion cannot distinguish an original legacy database from a copied legacy database; it must not be treated as authorization to restore an economic queue. Qualification of that operator/recovery boundary is still required. The automatic missing-key restore rejection applies to encrypted queues.

Final local validation: 409 tests across 39 suites: 408 passed, 1 retired pilot skipped, zero failures/errors; lintDebug succeeded ({'Warning': 63}). Python normal and optimized each passed 303 checks. Exact XML counts, lint and logs are in encrypted-outbox-evidence/. APK assembly is blocked by the missing checkout debug.keystore; application and test compilation passed.
