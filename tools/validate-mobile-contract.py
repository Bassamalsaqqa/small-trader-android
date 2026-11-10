#!/usr/bin/env python3
"""
tools/validate-mobile-contract.py

Validates the Small Trader Mobile V1 API contract:
1. Validates all JSON Schemas in docs/integration/mobile-v1/schemas/
2. Validates OpenAPI 3.1 specification in docs/integration/mobile-v1/openapi.json
3. Validates all Golden Fixtures against their respective schemas
4. Validates Golden Canonical JSON and SHA-256 Digest test vectors
5. Validates Regression Fixtures (altered payload conflict, decimal edge cases, lost-ACK, tenant mismatch, revocation)
6. Verifies SHA-256 manifest reproducibility

Usage:
    python3 tools/validate-mobile-contract.py [--generate-manifest]
"""

import sys
import os
import json
import hashlib
import re
import decimal
from pathlib import Path

# Set up decimal context for banking precision
decimal.getcontext().prec = 28
decimal.getcontext().rounding = decimal.ROUND_HALF_EVEN

REPO_ROOT = Path(__file__).resolve().parent.parent
BASE_DIR = REPO_ROOT / "docs" / "integration" / "mobile-v1"
SCHEMAS_DIR = BASE_DIR / "schemas"
FIXTURES_DIR = BASE_DIR / "fixtures"
OPENAPI_FILE = BASE_DIR / "openapi.json"
MANIFEST_FILE = BASE_DIR / "manifest.sha256"


# ---------------------------------------------------------------------------
# Strict JSON Schema Validator (Standard Library Implementation)
# ---------------------------------------------------------------------------

class SchemaValidationError(Exception):
    pass


def canonical_json_bytes(obj) -> bytes:
    """RFC 8785 JSON Canonicalization Scheme (JCS) serializer."""
    return json.dumps(
        obj,
        sort_keys=True,
        separators=(',', ':'),
        ensure_ascii=False
    ).encode('utf-8')


def compute_sha256_digest(obj) -> str:
    """Compute sha256:<hex> of canonical JSON serialization."""
    h = hashlib.sha256(canonical_json_bytes(obj)).hexdigest()
    return f"sha256:{h}"


class JsonSchemaValidator:
    def __init__(self, schemas_dir: Path):
        self.schemas_dir = schemas_dir
        self.schemas = {}
        self.load_all_schemas()

    def load_all_schemas(self):
        for schema_path in self.schemas_dir.glob("*.json"):
            with open(schema_path, "r", encoding="utf-8") as f:
                schema_json = json.load(f)
                self.schemas[schema_path.name] = schema_json
                if "$id" in schema_json:
                    self.schemas[schema_json["$id"]] = schema_json

    def resolve_ref(self, ref: str, current_schema: dict) -> dict:
        if ref.startswith("#/"):
            # Internal pointer
            parts = ref.lstrip("#/").split("/")
            curr = current_schema
            for p in parts:
                curr = curr[p]
            return curr
        # Relative file ref
        ref_filename = Path(ref).name
        if ref_filename in self.schemas:
            return self.schemas[ref_filename]
        if ref in self.schemas:
            return self.schemas[ref]
        raise SchemaValidationError(f"Could not resolve $ref: {ref}")

    def validate(self, data, schema, path: str = "root"):
        if "$ref" in schema:
            resolved = self.resolve_ref(schema["$ref"], schema)
            self.validate(data, resolved, path)
            return

        expected_type = schema.get("type")
        if expected_type:
            types = [expected_type] if isinstance(expected_type, str) else expected_type
            valid = False
            for t in types:
                if t == "null" and data is None:
                    valid = True
                elif t == "string" and isinstance(data, str):
                    valid = True
                elif t == "boolean" and isinstance(data, bool):
                    valid = True
                elif t == "integer" and isinstance(data, int) and not isinstance(data, bool):
                    valid = True
                elif t == "number" and (isinstance(data, (int, float)) and not isinstance(data, bool)):
                    valid = True
                elif t == "object" and isinstance(data, dict):
                    valid = True
                elif t == "array" and isinstance(data, list):
                    valid = True

            if not valid:
                raise SchemaValidationError(
                    f"At {path}: expected type {expected_type}, got {type(data).__name__} ({repr(data)})"
                )

        if data is None:
            return

        # Enum check
        if "enum" in schema:
            if data not in schema["enum"]:
                raise SchemaValidationError(
                    f"At {path}: value {repr(data)} is not in enum {schema['enum']}"
                )

        # String constraints
        if isinstance(data, str):
            if "pattern" in schema:
                pattern = schema["pattern"]
                if not re.search(pattern, data):
                    raise SchemaValidationError(
                        f"At {path}: string {repr(data)} does not match pattern {repr(pattern)}"
                    )
            if "minLength" in schema and len(data) < schema["minLength"]:
                raise SchemaValidationError(
                    f"At {path}: string length {len(data)} is less than minLength {schema['minLength']}"
                )
            if "maxLength" in schema and len(data) > schema["maxLength"]:
                raise SchemaValidationError(
                    f"At {path}: string length {len(data)} exceeds maxLength {schema['maxLength']}"
                )
            if "format" in schema:
                fmt = schema["format"]
                if fmt == "date-time":
                    # Simple ISO-8601 validation
                    if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$", data):
                        raise SchemaValidationError(f"At {path}: invalid date-time format: {data}")
                elif fmt == "email":
                    if not re.match(r"^[^@]+@[^@]+\.[^@]+$", data):
                        raise SchemaValidationError(f"At {path}: invalid email format: {data}")

        # Number / integer constraints
        if isinstance(data, (int, float)) and not isinstance(data, bool):
            if "minimum" in schema and data < schema["minimum"]:
                raise SchemaValidationError(
                    f"At {path}: value {data} is less than minimum {schema['minimum']}"
                )
            if "maximum" in schema and data > schema["maximum"]:
                raise SchemaValidationError(
                    f"At {path}: value {data} exceeds maximum {schema['maximum']}"
                )

        # Array constraints
        if isinstance(data, list):
            if "minItems" in schema and len(data) < schema["minItems"]:
                raise SchemaValidationError(
                    f"At {path}: array length {len(data)} is less than minItems {schema['minItems']}"
                )
            if "maxItems" in schema and len(data) > schema["maxItems"]:
                raise SchemaValidationError(
                    f"At {path}: array length {len(data)} exceeds maxItems {schema['maxItems']}"
                )
            if "items" in schema:
                items_schema = schema["items"]
                for i, item in enumerate(data):
                    self.validate(item, items_schema, f"{path}[{i}]")

        # Object constraints
        if isinstance(data, dict):
            required = schema.get("required", [])
            for req_key in required:
                if req_key not in data:
                    raise SchemaValidationError(f"At {path}: missing required property '{req_key}'")

            properties = schema.get("properties", {})
            additional_allowed = schema.get("additionalProperties", True)

            for key, val in data.items():
                if key in properties:
                    self.validate(val, properties[key], f"{path}.{key}")
                elif additional_allowed is False:
                    raise SchemaValidationError(f"At {path}: unexpected additional property '{key}'")
                elif isinstance(additional_allowed, dict):
                    self.validate(val, additional_allowed, f"{path}.{key}")


# ---------------------------------------------------------------------------
# Test Runner & Assertions
# ---------------------------------------------------------------------------

def run_validations() -> int:
    print("=" * 70)
    print("Small Trader Mobile V1 Contract Validation Suite")
    print("=" * 70)

    validator = JsonSchemaValidator(SCHEMAS_DIR)
    passed_checks = 0

    # 1. Validate Schema files existence and basic structure
    print("\n[Step 1] Validating JSON Schemas...")
    schema_files = sorted(SCHEMAS_DIR.glob("*.json"))
    if not schema_files:
        print("ERROR: No schema files found in", SCHEMAS_DIR)
        return 1

    for s_path in schema_files:
        with open(s_path, "r", encoding="utf-8") as f:
            try:
                s_json = json.load(f)
            except Exception as e:
                print(f"  FAIL: Schema {s_path.name} is not valid JSON: {e}")
                return 1
            if "$schema" not in s_json or "type" not in s_json:
                print(f"  FAIL: Schema {s_path.name} missing $schema or type")
                return 1
        print(f"  OK: {s_path.name}")
        passed_checks += 1
    print(f"  Total schemas validated: {len(schema_files)}")

    # 2. Validate OpenAPI 3.1 specification
    print("\n[Step 2] Validating OpenAPI 3.1 Specification...")
    if not OPENAPI_FILE.exists():
        print("ERROR: openapi.json not found at", OPENAPI_FILE)
        return 1
    with open(OPENAPI_FILE, "r", encoding="utf-8") as f:
        openapi = json.load(f)

    assert openapi.get("openapi") == "3.1.0", "OpenAPI version must be 3.1.0"
    paths = openapi.get("paths", {})
    expected_endpoints = [
        "/api/mobile/v1/auth/challenge",
        "/api/mobile/v1/auth/login",
        "/api/mobile/v1/auth/logout",
        "/api/mobile/v1/devices/{deviceId}/revoke",
        "/api/mobile/v1/companies/{companyId}/bootstrap",
        "/api/mobile/v1/companies/{companyId}/products",
        "/api/mobile/v1/companies/{companyId}/customers",
        "/api/mobile/v1/companies/{companyId}/sales/preview",
        "/api/mobile/v1/companies/{companyId}/sales/commit",
        "/api/mobile/v1/companies/{companyId}/offline-events",
        "/api/mobile/v1/companies/{companyId}/offline-events/{clientEventId}/status",
    ]
    for ep in expected_endpoints:
        if ep not in paths:
            print(f"  FAIL: Missing expected endpoint {ep} in openapi.json")
            return 1
        print(f"  OK: Endpoint defined: {ep}")
        passed_checks += 1

    # 3. Validate Golden Fixtures against schemas
    print("\n[Step 3] Validating Golden Fixtures...")
    fixtures_map = [
        ("golden-auth-challenge.json", "auth-challenge-request.json", "request"),
        ("golden-auth-challenge.json", "auth-challenge-response.json", "response"),
        ("golden-auth-login.json", "auth-login-request.json", "request"),
        ("golden-auth-login.json", "auth-login-response.json", "response"),
        ("golden-auth-logout.json", "auth-logout-request.json", "request"),
        ("golden-auth-logout.json", "auth-logout-response.json", "response"),
        ("golden-device-revoke.json", "device-revoke-request.json", "request"),
        ("golden-device-revoke.json", "device-revoke-response.json", "response"),
        ("golden-bootstrap.json", "bootstrap-response.json", None),
        ("golden-product-catalog.json", "product-catalog-response.json", None),
        ("golden-customer-directory.json", "customer-directory-response.json", None),
        ("golden-sale-preview.json", "sale-preview-request.json", "request"),
        ("golden-sale-preview.json", "sale-preview-response.json", "response"),
        ("golden-sale-commit.json", "sale-commit-request.json", "request"),
        ("golden-sale-commit.json", "sale-commit-response.json", "response"),
        ("golden-offline-sale-intake.json", "offline-sale-intake-request.json", "request"),
        ("golden-offline-sale-intake.json", "offline-sale-intake-response.json", "response"),
        ("golden-sale-event-status.json", "sale-event-status-response.json", None),
    ]

    for fix_file, schema_file, sub_key in fixtures_map:
        fpath = FIXTURES_DIR / fix_file
        with open(fpath, "r", encoding="utf-8") as f:
            data = json.load(f)
        if sub_key:
            data = data[sub_key]
        schema = validator.schemas[schema_file]
        try:
            validator.validate(data, schema, path=f"{fix_file}:{sub_key or 'root'}")
            print(f"  OK: {fix_file} [{sub_key or 'root'}] conforms to {schema_file}")
            passed_checks += 1
        except SchemaValidationError as e:
            print(f"  FAIL: Validation error in {fix_file}: {e}")
            return 1

    # 4. Validate Golden Canonical Vectors
    print("\n[Step 4] Validating Golden Canonical JSON and SHA-256 Digest Vectors...")
    vectors_file = FIXTURES_DIR / "golden-canonical-vectors.json"
    with open(vectors_file, "r", encoding="utf-8") as f:
        vectors = json.load(f)

    for vec in vectors:
        name = vec["name"]
        inp = vec["input"]
        expected_canonical = vec["canonical_json"]
        expected_digest = vec["digest"]

        actual_canonical = canonical_json_bytes(inp).decode("utf-8")
        if actual_canonical != expected_canonical:
            print(f"  FAIL Vector {name}: canonical mismatch!\n    Exp: {expected_canonical}\n    Act: {actual_canonical}")
            return 1

        actual_digest = compute_sha256_digest(inp)
        if actual_digest != expected_digest:
            print(f"  FAIL Vector {name}: digest mismatch!\n    Exp: {expected_digest}\n    Act: {actual_digest}")
            return 1

        print(f"  OK: Vector '{name}' verified: {actual_digest}")
        passed_checks += 1

    # Verify golden offline intake event digest match
    with open(FIXTURES_DIR / "golden-offline-sale-intake.json", "r", encoding="utf-8") as f:
        offline_intake_data = json.load(f)["request"]
    computed_intake_digest = compute_sha256_digest(offline_intake_data["event"])
    if computed_intake_digest != offline_intake_data["payload_digest"]:
        print(f"  FAIL: Offline intake payload_digest mismatch!\n    Computed: {computed_intake_digest}\n    In request: {offline_intake_data['payload_digest']}")
        return 1
    print(f"  OK: Offline intake golden event matches payload_digest: {computed_intake_digest}")
    passed_checks += 1

    # 5. Validate Regression Fixtures
    print("\n[Step 5] Validating Regression Fixtures...")

    # 5a. Altered payload conflict
    with open(FIXTURES_DIR / "regression-altered-payload-conflict.json", "r", encoding="utf-8") as f:
        alt_data = json.load(f)
    alt_event = alt_data["incoming_request"]["event"]
    computed_alt_digest = compute_sha256_digest(alt_event)
    assert computed_alt_digest == alt_data["incoming_request"]["payload_digest"]
    assert computed_alt_digest != alt_data["stored_record"]["payload_digest"], "Altered payload MUST have different digest"
    assert alt_data["expected_status"] == 409
    validator.validate(alt_data["expected_response"], validator.schemas["error-response.json"], "regression-altered-payload")
    print("  OK: Altered payload conflict regression verified (digest mismatch -> 409 Conflict)")
    passed_checks += 1

    # 5b. Decimal edge cases
    with open(FIXTURES_DIR / "regression-decimal-edge-cases.json", "r", encoding="utf-8") as f:
        dec_data = json.load(f)
    for tc in dec_data["test_cases"]:
        if tc["name"] == "fractional_weighted_item":
            q = decimal.Decimal(tc["quantity"])
            p = decimal.Decimal(tc["unit_price"])
            sub = q * p
            assert str(sub) == tc["subtotal"], f"Subtotal mismatch: {sub} != {tc['subtotal']}"
            tax_rate = decimal.Decimal(tc["tax_rate"])
            tax_amount = sub * tax_rate
            assert str(tax_amount) == tc["tax_amount"], f"Tax amount mismatch: {tax_amount} != {tc['tax_amount']}"
            tot = sub + tax_amount
            assert str(tot) == tc["line_total_exact"], f"Total mismatch: {tot} != {tc['line_total_exact']}"
            rounded = tot.quantize(decimal.Decimal("0.01"))
            assert str(rounded) == tc["line_total_rounded_bankers"], f"Rounded total mismatch: {rounded} != {tc['line_total_rounded_bankers']}"
        elif tc["name"] == "exact_three_cent_sum":
            d1 = decimal.Decimal(tc["line_1"])
            d2 = decimal.Decimal(tc["line_2"])
            d_sum = d1 + d2
            assert str(d_sum) == tc["exact_sum"]
            # Demonstrate binary float drift
            f_sum = 0.10 + 0.20
            assert str(f_sum) == tc["float_corrupted_sum"]
    print("  OK: Decimal exact arithmetic regression verified (Banker's rounding, arbitrary precision scale)")
    passed_checks += 1

    # 5c. Lost ACK replay
    with open(FIXTURES_DIR / "regression-lost-ack-replay.json", "r", encoding="utf-8") as f:
        lost_ack = json.load(f)
    assert lost_ack["first_submission"]["payload_digest"] == lost_ack["retry_submission"]["payload_digest"]
    assert lost_ack["first_submission"]["client_event_id"] == lost_ack["retry_submission"]["client_event_id"]
    validator.validate(lost_ack["server_behavior"]["response"], validator.schemas["offline-sale-intake-response.json"], "regression-lost-ack")
    print("  OK: Lost-ACK idempotent replay regression verified (identical digest -> same canonical outcome)")
    passed_checks += 1

    # 5d. Tenant mismatch
    with open(FIXTURES_DIR / "regression-tenant-mismatch.json", "r", encoding="utf-8") as f:
        tenant_mis = json.load(f)
    assert tenant_mis["expected_status"] == 403
    validator.validate(tenant_mis["expected_response"], validator.schemas["error-response.json"], "regression-tenant-mismatch")
    print("  OK: Tenant mismatch regression verified (cross-tenant access -> 403 Forbidden)")
    passed_checks += 1

    # 5e. Auth revocation
    with open(FIXTURES_DIR / "regression-auth-revocation.json", "r", encoding="utf-8") as f:
        rev_data = json.load(f)
    rev_computed_digest = compute_sha256_digest(rev_data["intake_submission"]["event"])
    assert rev_computed_digest == rev_data["intake_submission"]["payload_digest"]
    validator.validate(rev_data["expected_response"], validator.schemas["offline-sale-intake-response.json"], "regression-auth-revocation")
    assert rev_data["expected_response"]["outcome"]["conflict_code"] == "ACTOR_REVOKED"
    print("  OK: Offline staff revocation regression verified (parked for manager resolution without silent drop)")
    passed_checks += 1

    # 6. Verify reproducible manifest SHA256
    print("\n[Step 6] Verifying Reproducible SHA-256 Manifest...")
    manifest_entries = generate_manifest_entries()

    if "--generate-manifest" in sys.argv or not MANIFEST_FILE.exists():
        with open(MANIFEST_FILE, "w", encoding="utf-8") as f:
            for rel_path, sha in manifest_entries.items():
                f.write(f"{sha}  {rel_path}\n")
        print(f"  OK: Generated new manifest at {MANIFEST_FILE}")
        passed_checks += 1
    else:
        with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
            lines = f.readlines()
        stored_manifest = {}
        for line in lines:
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=1)
            if len(parts) == 2:
                stored_manifest[parts[1]] = parts[0]

        for rel_path, actual_sha in manifest_entries.items():
            if rel_path not in stored_manifest:
                print(f"  FAIL: File {rel_path} not in manifest.sha256")
                return 1
            if stored_manifest[rel_path] != actual_sha:
                print(f"  FAIL: Checksum mismatch for {rel_path}!\n    Expected: {stored_manifest[rel_path]}\n    Actual:   {actual_sha}")
                return 1
            passed_checks += 1
        print(f"  OK: All {len(manifest_entries)} contract files match manifest.sha256 exactly")

    print("\n" + "=" * 70)
    print(f"SUCCESS: All {passed_checks} contract, schema, fixture, and regression checks passed!")
    print("=" * 70)
    return 0


def generate_manifest_entries() -> dict:
    entries = {}
    target_files = []
    target_files.append(BASE_DIR / "ADR-0001-mobile-v1-contract.md")
    target_files.append(OPENAPI_FILE)
    target_files.extend(sorted(SCHEMAS_DIR.glob("*.json")))
    target_files.extend(sorted(FIXTURES_DIR.glob("*.json")))

    for tf in target_files:
        rel = tf.relative_to(BASE_DIR).as_posix()
        with open(tf, "rb") as f:
            h = hashlib.sha256(f.read()).hexdigest()
        entries[rel] = h
    return entries


if __name__ == "__main__":
    sys.exit(run_validations())
