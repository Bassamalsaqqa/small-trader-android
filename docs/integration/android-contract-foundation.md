# Android Mobile Contract Foundation (Phase 1)

## 1. Architectural Scope & Bounded Context

This integration foundation implements the pure Kotlin contract primitives, exact-decimal financial types, and restricted canonical event JSON serializer/digest baseline for Android (`small-trader-android`).

### Scope Boundaries
- **In-Scope**:
  - Typesafe identifier primitives (`ServerUlid`, `ClientUuid`, `PayloadDigest`).
  - Arbitrary-precision exact-decimal value objects (`DecimalQuantity`, `MoneyAmount`, `TaxPercentage`) backed by `BigDecimal` at normalized 6-decimal scale (`DECIMAL(20,6)`).
  - Deterministic line arithmetic sequence conforming to Laravel `SalesLineCalculator.php` and `TaxPercentage.php` parity (commit `f8a0cf7012c4875bec7320d42e0f8dde58593b52`) with `RoundingMode.HALF_UP`.
  - Pure Kotlin RFC 8785 subset serializer (`CanonicalJsonSerializer`) implementing Small Trader Restricted Canonical JSON Profile v1 with UTF-16 code unit key sorting and lone surrogate detection.
  - Pure Kotlin strict JSON parser (`StrictJsonParser`) rejecting duplicate object keys and floating-point numeric tokens.
  - Offline sale intake domain structures (`OfflineSaleEvent`, `OfflineSaleLine`, `OfflineSalePayment`, `OfflineSaleIntakeRequest`) with cryptographic envelope validation.
  - Frozen JVM regression and golden test suite verifying exact byte-level digest parity against `manifest.sha256` (`a8d04a130bdb0bd909a7cfabdde93fb6f90c96cc07dbedfbd01971bdb07c6733`).
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

## 5. Exact-Decimal Primitives & Laravel Half-Up Rounding

Binary floating-point types (`Double`, `Float`, `NaN`, `Infinity`) are strictly prohibited in all financial paths. All quantities, prices, taxes, and totals are normalized to 6 decimal places (`DECIMAL(20,6)`):

1. **`DecimalQuantity`**:
   - Strictly positive quantities (`fromPositiveString`) accept trailing zeros (e.g. `"0.100000"`), while rejecting zero (`"0.000000"`), negative values, and overscale decimals (>6 places).
   - Magnitude capped at `99999999999999.999999` (14 integer digits, 6 decimal places).
2. **`MoneyAmount`**:
   - Nonnegative amounts (`fromNonnegativeString`) and positive amounts (`fromPositiveString`).
   - Scale normalized to 6 places (`toPlainString()`).
3. **`TaxPercentage`**:
   - Value bounded to `0.000000..100.000000`. Rejects values > 100% (e.g. `"100.000001"`) and negative values.
   - Provides conversion to fractional rate `toRate() = value / 100`.
4. **`SalesLineCalculator`**:
   - Matches Laravel `SalesLineCalculator.php` sequence (commit `f8a0cf7012c4875bec7320d42e0f8dde58593b52`):
     - `rawSubtotal = quantity * unitPrice` -> `subtotal = rawSubtotal.setScale(minorUnits, RoundingMode.HALF_UP)`
     - Inclusive Tax: `actualNet = subtotal / (1 + rate)` with `HALF_UP`; `tax = subtotal - actualNet`; `total = subtotal`.
     - Exclusive Tax: `tax = (subtotal * rate)` with `HALF_UP`; `total = subtotal + tax`.
     - Output normalized to 6 decimal places (`DECIMAL(20,6)`).

---

## 6. Small Trader Restricted Canonical JSON Profile v1

Serializer and digest generation conform to RFC 8785 subset rules:
- **UTF-16 Code Unit Key Sorting**: Object keys are sorted lexicographically by UTF-16 code units (`String.compareTo`), ensuring surrogate pairs in supplementary planes (`\uD83D\uDE00`) precede high BMP characters (`\uFFFF`).
- **No Floating-Point Numbers**: `Float` and `Double` throw `CanonicalSerializationException`. Decimals must be formatted strings.
- **Surrogate Pair Integrity**: Unpaired high or low surrogates and reversed surrogate pairs are detected and rejected.
- **Escape Rules**: Characters `< 0x20` are escaped via `\u00xx` or standard short escapes (`\b`, `\f`, `\n`, `\r`, `\t`, `\"`, `\\`). Unicode text (such as Arabic `عميل نقدي / Walk-in`) is emitted unescaped in UTF-8.
- **Duplicate Key Prevention**: `StrictJsonParser` rejects duplicate object keys immediately.

---

## 7. Verification & Parity Test Suite

The pure JVM Gradle test suite runs under JDK 21 (`/home/t3dev/.local/share/android-toolchain/jdk-21.0.13+9`) without Android framework stubs:

| Test Class | Tests | Checks Covered |
| :--- | :--- | :--- |
| `CanonicalJsonSerializerTest` | 8 | All 5 golden vectors (`primitive_sorting`, `currency_total`, `arabic_fractions`, `utf16_surrogate_key_ordering`, `control_char_escaping`), UTF-16 key sorting, control escapes, Arabic text, lone surrogate rejection, float rejection, duplicate key rejection. |
| `DecimalPrimitivesAndRoundingTest` | 5 | Exclusive/inclusive sales line calculations, trailing zeros, `HALF_UP` vs `HALF_EVEN` divergence, float drift immunity, magnitude boundaries, over-scale rejection, tax percentage boundaries. |
| `IdentifierPrimitivesTest` | 4 | Valid ULID acceptance, Crockford exclusion rules (`I`, `L`, `O`, `U`, lowercase, leading > 7, length), UUID validation and rejection as ULID, payload digest validation and lowercase hex format. |
| `OfflineSaleContractGoldenParityTest` | 4 | Golden offline intake event digest (`sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9`), envelope equality checks, packaging unit mapping, envelope tamper detection, altered payload conflict regression, lost-ACK replay parity. |

Total: **21 focused unit tests**, **100% passing**, 0 failures, 0 skipped.
Contract manifest hash verified: `a8d04a130bdb0bd909a7cfabdde93fb6f90c96cc07dbedfbd01971bdb07c6733`.
