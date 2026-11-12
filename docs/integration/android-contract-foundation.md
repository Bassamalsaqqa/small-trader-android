# Android Mobile Contract Foundation (Phase 1)

## 1. Architectural Scope & Bounded Context

This integration foundation implements the pure Kotlin contract primitives, exact-decimal financial types, and restricted canonical event JSON serializer/digest baseline for Android (`small-trader-android`).

### Scope Boundaries
- **In-Scope**:
  - Typesafe identifier primitives (`ServerUlid`, `ClientUuid`, `PayloadDigest`).
  - Arbitrary-precision exact-decimal value objects (`PositiveDecimalQuantity`, `NonnegativeDecimalQuantity`, `MoneyAmount`, `TaxPercentage`) preserving exact wire string lexemes and exposing `BigDecimal` views for arithmetic calculations.
  - Deterministic line arithmetic sequence conforming to Laravel `SalesLineCalculator.php` and `TaxPercentage.php` parity (commit `f8a0cf7012c4875bec7320d42e0f8dde58593b52`) with `RoundingMode.HALF_UP`, explicitly bounded to no-discount base currency preview.
  - Pure Kotlin RFC 8785 subset serializer (`CanonicalJsonSerializer`) implementing Small Trader Restricted Canonical JSON Profile v1 with UTF-16 code unit key sorting, lone surrogate detection, safe integer domain bounds [-(2^53-1), 2^53-1], recursion depth limit (32), and strict negative zero (`-0`) rejection.
  - Pure Kotlin strict JSON parser (`StrictJsonParser`) rejecting duplicate object keys, negative zero, unbounded recursion, oversized strings (>64 KB), and oversized payloads (>1 MB).
  - Offline sale intake domain structures (`OfflineSaleEvent`, `OfflineSaleLine`, `OfflineSalePayment`, `OfflineSaleIntakeRequest`) with strict unexpected property rejection, deterministic boundary checks (timestamps, string caps, cash conservation, line sum equality), defensive list copying, and pre-coercion envelope digest verification.
  - Frozen JVM regression and golden test suite verifying exact byte-level digest parity against `manifest.sha256` (`ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659`, Draft Revision 2).
- **Explicitly Excluded**:
  - No UI / Jetpack Compose modifications.
  - No ViewModel, existing repository (`TraderRepository`, `SampleCatalog`), or runtime demo wiring edits.
  - No Room database entities, DAO, or local SQLite schema changes.
  - No Retrofit/OkHttp network wiring, bearer token handling, or Keystore operations in this layer.
  - Backend API is NOT live; offline policies default to disabled (`enabled: false`) pending business sign-off.

---

## 2. Authority & Parity Clarification

> [!IMPORTANT]
> **Laravel Sole Posting Authority**: The central Laravel backend (`account`) is the sole and authoritative posting authority for the general ledger, inventory lot movements, and fiscal tax/invoice transactions.
>
> The Android calculation and golden test fixtures provide **client-side parity only** for local draft calculation and intake envelope verification. They do **not** constitute independent financial authority or process-death proofing.

---

## 3. Identifiers: Server Crockford ULID vs Client RFC 4122 UUID

The foundation establishes strict separation between server-managed domain entity public IDs and client-generated event artifacts:

| ID Type | Class | Pattern Regex | Semantics & Scope |
| :--- | :--- | :--- | :--- |
| **Server ULID** | `ServerUlid` | `^[0-7][0-9A-HJKMNP-TV-Z]{25}$` | 26-char Crockford Base32. Used for `company_id`, `actor_id`, `product_id`, `customer_id`, `warehouse_id`, `cash_account_id`, `unit_id`, `product_unit_id`, `invoice_id`, `payment_id`. Excludes `I`, `L`, `O`, `U`, lowercase, and UUIDs. |
| **Client UUID** | `ClientUuid` | `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$` | RFC 4122 UUID. Used for `client_event_id`, `device_id`, `device_lease_id`, `challenge_id`. |
| **Payload Digest** | `PayloadDigest` | `^sha256:[0-9a-f]{64}$` | Cryptographic SHA-256 hash over canonical JSON UTF-8 byte stream. |

---

## 4. Product Packaging Unit Mapping

- **Schema Parity**: The database `units` table has an authoritative public ULID (`char(26)`), whereas `product_units` is an internal join entity with a surrogate primary key.
- **External Contract Mapping**: In client-facing contracts (`SalesDraftIntent`, `OfflineSaleLine`), `product_unit_id` is mapped to the unit public ULID bound to the product (matching `unit_id` ULID), NOT an internal database auto-increment ID.

---

## 5. Exact-Decimal Primitives & Invariant Enforcement

Binary floating-point types (`Double`, `Float`, `NaN`, `Infinity`) are strictly prohibited in all financial paths. All decimal types preserve their exact wire string lexemes (`lexeme`) to prevent altering immutable canonical event digests during serialization:

1. **`PositiveDecimalQuantity` (`typealias DecimalQuantity`)**:
   - Strictly positive quantities (`value > BigDecimal.ZERO`) matching `ContractConstants.POSITIVE_QUANTITY_REGEX`.
   - Invariant enforced on EVERY construction path (primary constructor, secondary constructor, `copy()`).
   - Accepts valid trailing zeros (e.g. `"0.100000"`, `"1"`), while rejecting zero (`"0.000000"`, `"0"`), negative amounts, overscale decimals (>6 places), and magnitude > `99999999999999.999999`.
   - Exposes `value: BigDecimal` view for arithmetic.
2. **`NonnegativeDecimalQuantity`**:
   - Explicitly decoupled from sales quantities for inventory stock (`available_quantity`).
   - Accepts zero (`"0.000000"`, `"0"`), strictly rejects negative amounts on every path.
3. **`MoneyAmount`**:
   - Nonnegative monetary amounts (`value >= BigDecimal.ZERO`) matching `ContractConstants.NONNEGATIVE_DECIMAL_REGEX`.
   - Rejects negative amounts on every path including constructor and `copy()`.
   - No silent rounding: `fromScaledBigDecimal` removed to prevent silent distortion of economic input.
4. **`TaxPercentage`**:
   - Bounded to `0.000000..100.000000`. Rejects values > 100% and negative values.
   - Provides conversion to fractional rate `toRate() = value / 100` (scale 8, `HALF_UP`).
5. **`SalesLineCalculator`**:
   - Narrowed scope: supported no-discount base currency line calculator preview.
   - Requires explicit `minorUnits` bounded to `0..6` (no default; use server currency metadata); callers for 3-minor currencies (e.g. KWD, JOD) or 0-minor (JPY) must pass explicit currency scale.
   - Sequence:
     - `rawSubtotal = quantity * unitPrice` -> `subtotal = rawSubtotal.setScale(minorUnits, RoundingMode.HALF_UP)`
     - Inclusive Tax: `net = subtotal / (1 + rate)` with `HALF_UP`; `tax = subtotal - net`; `total = subtotal`.
     - Exclusive Tax: `tax = (subtotal * rate)` with `HALF_UP`; `total = subtotal + tax`.
     - Normalization to 6 decimal places (`DECIMAL(20,6)`) via `RoundingMode.UNNECESSARY` (no silent rounding).

---

## 6. Small Trader Restricted Canonical JSON Profile v1

Serializer and digest generation conform to RFC 8785 subset rules:
- **UTF-16 Code Unit Key Sorting**: Object keys are sorted lexicographically by UTF-16 code units (`String.compareTo`), ensuring surrogate pairs in supplementary planes (`\uD83D\uDE00`) precede high BMP characters (`\uFFFF`).
- **No Floating-Point Numbers**: `Float` and `Double` throw `CanonicalSerializationException`. Decimals must be string-encoded.
- **Surrogate Pair Integrity**: Unpaired high or low surrogates and reversed surrogate pairs are detected and rejected.
- **Escape Rules**: Characters `< 0x20` are escaped via `\u00xx` or standard short escapes (`\b`, `\f`, `\n`, `\r`, `\t`, `\"`, `\\`). Unicode text (such as Arabic `عميل نقدي / Walk-in`) is emitted unescaped in UTF-8.
- **Canonical Integer Profile Domain**: Non-economic small integers strictly bounded to RFC 8785 IEEE 754 safe integer range `[-(2^53-1), 2^53-1]` (`[-9007199254740991, 9007199254740991]`).
- **Negative Zero Rejection**: Canonical integer negative zero (`"-0"`, `"-00"`) is strictly forbidden and rejected by both parser and serializer.
- **Resource Bounds**: Recursion depth limited to 32, payload size limited to 1 MB, string literals limited to 64 KB.
- **Duplicate Key Prevention**: `StrictJsonParser` rejects duplicate object keys immediately.

---

## 7. Offline Sale Domain Models & Envelope Integrity

- **Pre-Coercion Hash Verification**: `OfflineSaleIntakeRequest.fromStrictJson` computes and validates the canonical payload digest over the raw parsed JSON element tree *before* coercion into typed models, preventing silent normalization divergence.
- **Envelope Bindings Enforcement**: `init { validateEnvelopeBindings() }` enforces equality between outer envelope (`client_event_id`, `device_id`, `captured_at`, `payload_digest`) and inner event properties on construction and copy paths.
- **Strict Unknown Field Rejection**: Decoders at all levels (`OfflineSaleIntakeRequest`, `OfflineSaleEvent`, `OfflineSaleLine`, `OfflineSalePayment`) strictly reject unexpected properties per frozen JSON schemas (`additionalProperties: false`).
- **Deterministic Boundary Checks**:
  - `capturedAt`: Validated as RFC 3339 / ISO-8601 date-time bounded to years [2020..2100].
  - String Caps: `catalog_snapshot_version` and `local_receipt_number` capped at 1..64 characters.
  - Cash Account Equality: Top-level `event.cashAccountId` must match `event.payment.cashAccountId`.
  - Packaging Unit Mapping: `unitId` must match `productUnitId`.
  - Cash Conservation: `amountTendered - amountSettled == changeReturned`.
  - Full Settlement Conservation: Sum of line totals must equal `payment.amountSettled`.
  - Line Total: Zero and negative line totals are rejected per schema.
- **Defensive Copying**: `OfflineSaleEvent.lines` defensively copies input lists into an unmodifiable list to eliminate mutable source aliasing and ensure immutability post-digest.

---

## 8. Verification & Parity Test Suite

The pure JVM Gradle test suite runs under JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`) without Android framework stubs:

| Test Class | Tests | Checks Covered |
| :--- | :--- | :--- |
| `CanonicalJsonSerializerTest` | 11 | All 5 golden vectors (`primitive_sorting`, `currency_total`, `arabic_fractions`, `utf16_surrogate_key_ordering`, `control_char_escaping`), UTF-16 key sorting, control escapes, Arabic text, lone surrogate rejection, float rejection, duplicate key rejection, negative zero `-0` rejection, safe integer domain bounds, recursion depth and string length bounds. |
| `DecimalPrimitivesAndRoundingTest` | 7 | Exclusive/inclusive sales line calculations, trailing zeros, `HALF_UP` vs `HALF_EVEN` divergence, float drift immunity, magnitude boundaries, over-scale rejection, tax percentage boundaries, invariant enforcement on all constructor/copy paths, nonnegative vs positive quantity distinction, minorUnits 0..6 validation. |
| `IdentifierPrimitivesTest` | 4 | Valid ULID acceptance, Crockford exclusion rules (`I`, `L`, `O`, `U`, lowercase, leading > 7, length), UUID validation and rejection as ULID, payload digest validation and lowercase hex format. |
| `OfflineSaleContractGoldenParityTest` | 8 | Golden offline intake event digest, envelope equality checks, packaging unit mapping, envelope tamper detection, altered payload conflict regression, lost-ACK replay parity, exact decimal lexeme preservation & roundtrip with quantity '1' / unit_price '50', unknown field rejection at all 4 levels, deterministic boundary checks (timestamps, string caps, cash conservation, line sum equality), defensive list copy against mutable alias. |
| `StringBoundaryParityTest` | 5 | Strict JSON string boundary parsing: unsigned ASCII hex escape digits, surrogate pairs with 64KB UTF-16 limit enforcement after pair append, raw unescaped ASCII control char rejection, 1 MiB UTF-8 byte boundary rejection, signed Unicode escape rejection. |
| `BoundaryCompatibilityTest` | 3 | API-24-compatible timestamp field checks without java.time, UTF-8 payload byte limits, and explicit three-decimal currency precision. |
| `ContractManifestDriftTest` | 2 | Verifies `ContractConstants.MANIFEST_SHA256` matches actual hash of `docs/integration/mobile-v1/manifest.sha256` preventing contract drift, `CONTRACT_REVISION == 2`, and validates cryptographic integrity of all manifest entries. |

The foundation has **40 contract unit tests** (all 7 test classes passing 100%), plus `MerchantCheckoutTest` (6 tests) and `MerchantSaleLifecycleTest` (2 tests), totaling **48 focused JVM unit tests** (0 failed, 0 skipped).

Python contract validation suite: 210 checks passing (both normal and `-O` optimized python), including all 19 JSON schemas (Draft 2020-12), OpenAPI 3.1 specification, golden fixtures, canonical vectors, regressions, 89 revision 2 positive/negative validation cases, and `manifest.sha256` matching all 39 contract files.

Contract manifest hash verified: `ba75352349e552f53b02ad7159570171f88943dc26e5bdf2a4b013386b0d0659` (Draft Revision 2).

---

## 9. Draft Revision 2 Coordination & Financial Readiness Boundaries

Coordinated Draft Revision 2 incorporates read/auth/bootstrap contract refinements from `docs/integration/mobile-v1-revision-2-decisions.md`:
1. **Read/Auth/Bootstrap Refinements**:
   - MFA fresh retry, immutable staff/device identity, fresh tenant authorization.
   - Real catalog field widths (companies/warehouses/customers names 255; units 128; ledger code 32; tax rate code 32; no truncation).
   - Product tax association: nullable tax rate ID pair indicates unknown product tax association, NOT zero tax. No inferred zero tax rate.
   - Disabled offline policy: offline capabilities remain disabled (`enabled: false`), with no lease allocated.
2. **Financial Readiness Gate (BLOCKED)**:
   - Current offline cash financial schemas still lack public `lineTaxRateId` in sale preview; financial readiness remains **BLOCKED**.
   - No new financial activation. All event versions remain `eventVersion = 1`; golden canonical digest fixtures and financial schemas are unchanged.
   - No pending event re-keying or repricing.
   - All API/model runtime remains strictly UNWIRED and offline mode OFF.
