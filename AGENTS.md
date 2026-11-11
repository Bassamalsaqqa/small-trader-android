# AGENTS.md — Small Trader Android & Integration Governance

## 1. Authority & Posting Boundary
- **Laravel Sole Posting Authority**: The Laravel backend (`account`) is the sole and authoritative posting authority for all accounting ledger, inventory movement, and official tax/invoice transactions. The Android client (`small-trader-android`) is a client presentation and local-first capture layer; it never creates or commits central financial or inventory records independently.
- **No Live Demo Fallback**: Production and staging builds must never silently fall back to `OfflineDemoTraderRepository`, sample catalogs, or simulated staff/cash states. Offline demo modes must be explicitly segregated by build variant or explicit test flags.

## 2. Worktree & Supervision Model
- **Scoped Supervised Worktrees**: Direct user explicitly authorizes supervised AGY worktrees under Codex supervision, overriding previous ChatGPT reference gates. All development must occur in isolated, scoped worktrees without cross-branch contamination or uncoordinated writes.
- **Strict Role & Isolation Boundaries**: One agent/writer per worktree. Code reviews and acceptance checks are performed independently against commit diffs and verifiable test output.
- **No Unauthorized Operations**: No merges to `main`, production deployments, destructive database migrations, or secret management without explicit owner authorization.

## 3. UI, Localization & Accessibility
- **Preserve Accepted UI**: Preserve reviewed and accepted UI architecture, visual hierarchy, compact tablet/mobile density (minimum 44px touch targets), and typography.
- **Bilingual & RTL Integrity**: Maintain complete parity between Arabic (RTL) and English (LTR) layouts and formatting.
- **Truthful Status Presentation**: Offline-captured sales must be clearly presented as provisional ("Pending synchronization / غير مرحّلة بعد") until canonical backend confirmation is received.

## 4. Contract & Data Integrity
- **Exact Decimal Strings**: All monetary amounts, tax rates, line calculations, and quantities must be handled as exact arbitrary-precision decimal strings (Kotlin `BigDecimal`), never IEEE 754 floating point numbers (`Double`/`Float`).
- **Rounding Mode Policy**: Calculations adhere to Laravel platform verified `RoundingMode::HALF_UP` (standard arithmetic rounding: 0.5 rounds away from zero to next unit), not Banker's rounding (`ROUND_HALF_EVEN`).
- **Restricted Canonical JSON Profile v1**: Cryptographic payload digests (`sha256:<hex>`) follow RFC 8785 subset rules with UTF-16 code unit key sorting, string-only decimals, and strict rejection of floats, duplicate keys, and unpaired surrogates.
- **Idempotency & Replays**: All financial and capture commands require client-generated UUID idempotency keys and cryptographic payload digests. Replays with identical digests must be idempotent; altered payloads must yield HTTP 409 Conflict.
- **Hardware-Backed Key Custody**: Sensitive tokens are encrypted using hardware-backed AES-256-GCM master keys in Android Keystore (`AndroidKeyStore`) and stored in private application storage; bearer tokens are never stored directly inside Android Keystore.
- **Auth Revocation & Queue Lock**: Revoked bearer tokens fail at the authentication gate (HTTP 401). Local queues lock safely (`QUEUE_LOCKED_AUTH_DENIED`) preserving records locally until resolved by an authenticated manager.
