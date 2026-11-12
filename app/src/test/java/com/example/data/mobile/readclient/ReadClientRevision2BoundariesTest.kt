package com.example.data.mobile.readclient

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadClientRevision2BoundariesTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testFreshMfaChallengeAcceptedAndParsed() {
        val json = """
            {
              "status": "MFA_REQUIRED",
              "retry": "fresh_challenge"
            }
        """.trimIndent()
        val parsed = AuthLoginResult.fromStrictJson(StrictJsonParser.parse(json).asObject())
        assertTrue("Must parse as MfaRequired", parsed is AuthLoginResult.MfaRequired)
        val mfa = (parsed as AuthLoginResult.MfaRequired).response
        assertEquals("MFA_REQUIRED", mfa.status)
        assertEquals("fresh_challenge", mfa.retry)
    }

    @Test
    fun testOldNonceLessMfaRejected() {
        // Revision 2 negative case 2: old nonce-less continuation
        val oldMfaJson = """
            {
              "status": "MFA_REQUIRED",
              "continuation_nonce": "9f834928abceef761234509876abcdef"
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            AuthLoginResult.fromStrictJson(StrictJsonParser.parse(oldMfaJson).asObject())
        }
    }

    @Test
    fun testDisabledOfflinePolicyWithLeaseRejected() {
        // Revision 2 case 4: disabled offline policy with a lease must be rejected
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-bootstrap.json")
        val rootObj = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()

        // Inject lease into disabled bootstrap
        val tamperedProps = LinkedHashMap(rootObj.properties)
        val leaseJson = """
            {
              "lease_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
              "device_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
              "actor_id": "01JCV8X9M8T1V2W3X4Y5Z67890",
              "expires_at": "2026-11-10T14:00:00Z"
            }
        """.trimIndent()
        tamperedProps["device_lease"] = StrictJsonParser.parse(leaseJson)
        val tamperedObj = com.example.data.mobile.contract.StrictJsonObject(tamperedProps)

        val ex = assertThrows(CanonicalSerializationException::class.java) {
            BootstrapResponse.fromStrictJson(tamperedObj)
        }
        assertTrue(ex.message.orEmpty().contains("device_lease must be null"))
    }

    @Test
    fun testEmptyMatureMastersAcceptedInBootstrap() {
        // Revision 2 case 5: empty mature masters (units: [], taxes: []) are completely honest and accepted
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-bootstrap.json")
        val rootObj = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()

        val props = LinkedHashMap(rootObj.properties)
        props["units"] = com.example.data.mobile.contract.StrictJsonArray(emptyList())
        props["taxes"] = com.example.data.mobile.contract.StrictJsonArray(emptyList())
        val emptyMastersObj = com.example.data.mobile.contract.StrictJsonObject(props)

        val bootstrap = BootstrapResponse.fromStrictJson(emptyMastersObj)
        assertTrue(bootstrap.units.isEmpty())
        assertTrue(bootstrap.taxes.isEmpty())
    }

    @Test
    fun testTruthfulNullablePairedProductTax() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-product-catalog.json")
        val rootObj = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()

        // 1. Both null is accepted (case 6: no product tax association)
        val catalog = ProductCatalogResponse.fromStrictJson(rootObj)
        val p = catalog.products.first()
        assertNull(p.taxRate)
        assertNull(p.taxRateId)

        // 2. Both non-null is accepted
        val pObj = (rootObj.require("products") as com.example.data.mobile.contract.StrictJsonArray).elements[0].asObject()
        val validPairProps = LinkedHashMap(pObj.properties)
        validPairProps["tax_rate_id"] = com.example.data.mobile.contract.StrictJsonString("01JCV8X9MDPTRSVWXYZ0123456")
        validPairProps["tax_rate"] = com.example.data.mobile.contract.StrictJsonString("16.000000")
        val validPairItem = ProductCatalogItem.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(validPairProps))
        assertEquals("16.000000", validPairItem.taxRate?.toPlainString())
        assertEquals("01JCV8X9MDPTRSVWXYZ0123456", validPairItem.taxRateId?.value)

        // 3. Mismatched: tax_rate present with tax_rate_id null -> rejected (case 7)
        val mismatchRateProps = LinkedHashMap(pObj.properties)
        mismatchRateProps["tax_rate_id"] = com.example.data.mobile.contract.StrictJsonNull
        mismatchRateProps["tax_rate"] = com.example.data.mobile.contract.StrictJsonString("16.000000")
        assertThrows(CanonicalSerializationException::class.java) {
            ProductCatalogItem.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(mismatchRateProps))
        }

        // 4. Mismatched: tax_rate_id present with tax_rate null -> rejected (case 8)
        val mismatchIdProps = LinkedHashMap(pObj.properties)
        mismatchIdProps["tax_rate_id"] = com.example.data.mobile.contract.StrictJsonString("01JCV8X9MDPTRSVWXYZ0123456")
        mismatchIdProps["tax_rate"] = com.example.data.mobile.contract.StrictJsonNull
        assertThrows(CanonicalSerializationException::class.java) {
            ProductCatalogItem.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(mismatchIdProps))
        }
    }

    @Test
    fun testMaxWidthBoundariesAcceptanceAndRejection() {
        val fixturesDir = locateFixturesDir()
        val goldenBootstrap = File(fixturesDir, "golden-bootstrap.json")
        val bootObj = StrictJsonParser.parse(goldenBootstrap.readText(Charsets.UTF_8)).asObject()

        // Company name: 255 chars valid, 256 invalid
        val props255 = LinkedHashMap(bootObj.properties)
        props255["company_name"] = com.example.data.mobile.contract.StrictJsonString("A".repeat(255))
        val validBoot = BootstrapResponse.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(props255))
        assertEquals(255, validBoot.companyName.length)

        val props256 = LinkedHashMap(bootObj.properties)
        props256["company_name"] = com.example.data.mobile.contract.StrictJsonString("A".repeat(256))
        assertThrows(CanonicalSerializationException::class.java) {
            BootstrapResponse.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(props256))
        }

        // Warehouse code: 64 chars valid, 65 invalid
        val whObj = bootObj.require("default_warehouse").asObject()
        val wh64Props = LinkedHashMap(whObj.properties)
        wh64Props["code"] = com.example.data.mobile.contract.StrictJsonString("W".repeat(64))
        assertEquals(64, DefaultWarehouse.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(wh64Props)).code.length)

        val wh65Props = LinkedHashMap(whObj.properties)
        wh65Props["code"] = com.example.data.mobile.contract.StrictJsonString("W".repeat(65))
        assertThrows(CanonicalSerializationException::class.java) {
            DefaultWarehouse.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(wh65Props))
        }

        // Cash account code: 32 chars valid, 33 invalid
        val cashObj = bootObj.require("approved_cash_account").asObject()
        val cash32Props = LinkedHashMap(cashObj.properties)
        cash32Props["code"] = com.example.data.mobile.contract.StrictJsonString("C".repeat(32))
        assertEquals(32, ApprovedCashAccount.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(cash32Props)).code.length)

        val cash33Props = LinkedHashMap(cashObj.properties)
        cash33Props["code"] = com.example.data.mobile.contract.StrictJsonString("C".repeat(33))
        assertThrows(CanonicalSerializationException::class.java) {
            ApprovedCashAccount.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(cash33Props))
        }

        // Unit code 32 valid, 33 invalid; name 128 valid, 129 invalid
        val uObj = (bootObj.require("units") as com.example.data.mobile.contract.StrictJsonArray).elements[0].asObject()
        val u128Props = LinkedHashMap(uObj.properties)
        u128Props["name"] = com.example.data.mobile.contract.StrictJsonString("U".repeat(128))
        assertEquals(128, BootstrapUnit.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(u128Props)).name.length)

        val u129Props = LinkedHashMap(uObj.properties)
        u129Props["name"] = com.example.data.mobile.contract.StrictJsonString("U".repeat(129))
        assertThrows(CanonicalSerializationException::class.java) {
            BootstrapUnit.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(u129Props))
        }
    }

    @Test
    fun testUnexpectedPropertiesRejectedStrictly() {
        val fixturesDir = locateFixturesDir()
        val goldenBootstrap = File(fixturesDir, "golden-bootstrap.json")
        val bootObj = StrictJsonParser.parse(goldenBootstrap.readText(Charsets.UTF_8)).asObject()

        val extraProps = LinkedHashMap(bootObj.properties)
        extraProps["unauthorized_injected_field"] = com.example.data.mobile.contract.StrictJsonString("malicious")
        assertThrows(CanonicalSerializationException::class.java) {
            BootstrapResponse.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(extraProps))
        }
    }

    @Test
    fun testMalformedMoneyAndFloatRejected() {
        val json = """
            {
              "id": "01JCV8X9M6A1B2C3D4E5F6G7H8",
              "sku": "SKU-TEST",
              "name": "Test Item",
              "unit_price": 25.50,
              "tax_rate": null,
              "tax_rate_id": null,
              "is_tracked": true,
              "available_quantity": "10.000000",
              "base_unit": { "id": "01JCV8X9MBV4W5X6Y7Z8012345", "code": "PCS", "name": "Piece" },
              "default_unit": { "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345", "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345", "conversion_to_base": "1.000000", "sale_price": "25.500000" },
              "units": []
            }
        """.trimIndent()
        // Binary float for unit_price instead of string decimal must be rejected
        val obj = StrictJsonParser.parse(json).asObject()
        assertThrows(CanonicalSerializationException::class.java) {
            ProductCatalogItem.fromStrictJson(obj)
        }
    }

    @Test
    fun testForeignAndInvalidIdsRejected() {
        // UUID rejected as ServerUlid
        assertFalse(ServerUlid.isValid("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"))
        // Lowercase rejected
        assertFalse(ServerUlid.isValid("01jcv8x9m8t1v2w3x4y5z67890"))
        // Crockford forbidden letters 'I', 'L', 'O', 'U' rejected
        assertFalse(ServerUlid.isValid("01JCV8X9M8T1V2W3X4Y5Z6789I"))
        assertFalse(ServerUlid.isValid("01JCV8X9M8T1V2W3X4Y5Z6789L"))
        assertFalse(ServerUlid.isValid("01JCV8X9M8T1V2W3X4Y5Z6789O"))
        assertFalse(ServerUlid.isValid("01JCV8X9M8T1V2W3X4Y5Z6789U"))
        // Leading char > 7 rejected
        assertFalse(ServerUlid.isValid("81JCV8X9M8T1V2W3X4Y5Z67890"))

        // ULID rejected as ClientUuid
        assertFalse(ClientUuid.isValid("01JCV8X9M8T1V2W3X4Y5Z67890"))
    }

    @Test
    fun testMissingRequiredNullablePropertiesRejected() {
        // 1. Missing next_cursor in CustomerDirectoryResponse (missing != null)
        val missingCursorCustomerJson = """
            {
              "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
              "generated_at": "2026-11-10T12:00:00Z",
              "has_more": false,
              "customers": []
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            CustomerDirectoryResponse.fromStrictJson(StrictJsonParser.parse(missingCursorCustomerJson).asObject())
        }

        // 2. Missing next_cursor in ProductCatalogResponse
        val missingCursorProductJson = """
            {
              "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
              "catalog_snapshot_version": "snap-1",
              "generated_at": "2026-11-10T12:00:00Z",
              "has_more": false,
              "products": []
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            ProductCatalogResponse.fromStrictJson(StrictJsonParser.parse(missingCursorProductJson).asObject())
        }

        // 3. Missing tax_rate or tax_rate_id in ProductCatalogItem (missing != null)
        val missingTaxRateJson = """
            {
              "id": "01JCV8X9M6A1B2C3D4E5F6G7H8",
              "sku": "SKU-TEST",
              "name": "Test Item",
              "unit_price": "25.000000",
              "tax_rate_id": null,
              "is_tracked": true,
              "available_quantity": "10.000000",
              "base_unit": { "id": "01JCV8X9MBV4W5X6Y7Z8012345", "code": "PCS", "name": "Piece" },
              "default_unit": { "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345", "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345", "conversion_to_base": "1.000000", "sale_price": "25.000000" },
              "units": []
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            ProductCatalogItem.fromStrictJson(StrictJsonParser.parse(missingTaxRateJson).asObject())
        }

        // 4. Missing sale_price in ProductDefaultUnit
        val missingSalePriceUnitJson = """
            {
              "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
              "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
              "conversion_to_base": "1.000000"
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            ProductDefaultUnit.fromStrictJson(StrictJsonParser.parse(missingSalePriceUnitJson).asObject())
        }

        // 5. Missing device_lease in BootstrapResponse
        val fixturesDir = locateFixturesDir()
        val goldenBootstrap = File(fixturesDir, "golden-bootstrap.json")
        val bootObj = StrictJsonParser.parse(goldenBootstrap.readText(Charsets.UTF_8)).asObject()
        val missingLeaseProps = LinkedHashMap(bootObj.properties)
        missingLeaseProps.remove("device_lease")
        assertThrows(CanonicalSerializationException::class.java) {
            BootstrapResponse.fromStrictJson(com.example.data.mobile.contract.StrictJsonObject(missingLeaseProps))
        }
    }

    @Test
    fun testErrorResponseDetailsTypeConstraints() {
        // Invalid: number in details
        val numberDetailsJson = """
            {
              "error": {
                "code": "INVALID_STATE",
                "message": "Error occurred",
                "details": 42
              }
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            ReadClientErrorResponse.fromStrictJson(StrictJsonParser.parse(numberDetailsJson).asObject())
        }

        // Invalid: boolean in details
        val booleanDetailsJson = """
            {
              "error": {
                "code": "INVALID_STATE",
                "message": "Error occurred",
                "details": true
              }
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            ReadClientErrorResponse.fromStrictJson(StrictJsonParser.parse(booleanDetailsJson).asObject())
        }

        // Valid: string in details
        val stringDetailsJson = """
            {
              "error": {
                "code": "INVALID_STATE",
                "message": "Error occurred",
                "details": "Additional explanation"
              }
            }
        """.trimIndent()
        val err = ReadClientErrorResponse.fromStrictJson(StrictJsonParser.parse(stringDetailsJson).asObject())
        assertEquals("INVALID_STATE", err.code)
    }

    @Test
    fun testDirectConstructorAndCopyInvariants() {
        // ProductDefaultUnit invariants
        assertThrows(IllegalArgumentException::class.java) {
            ProductDefaultUnit(
                unitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
                productUnitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
                conversionToBase = com.example.data.mobile.contract.PositiveDecimalQuantity("1.000000"),
                salePrice = com.example.data.mobile.contract.MoneyAmount(java.math.BigDecimal("-1.00"))
            )
        }

        // ReadClientErrorResponse invariants
        assertThrows(IllegalArgumentException::class.java) {
            ReadClientErrorResponse(code = "", message = "Something happened")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadClientErrorResponse(code = "CODE", message = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadClientErrorResponse(
                code = "CODE",
                message = "Message",
                details = com.example.data.mobile.contract.StrictJsonNumber("123", true)
            )
        }
    }

    @Test
    fun testAuthLoginMfaOneOfMixedBranchesRejected() {
        // Mixed branches: status/retry mixed with token
        val mixedJson = """
            {
              "status": "MFA_REQUIRED",
              "retry": "fresh_challenge",
              "token": "sanctum_1092837465_mobile_token_982347109238471092384710"
            }
        """.trimIndent()
        assertThrows(CanonicalSerializationException::class.java) {
            AuthLoginResult.fromStrictJson(StrictJsonParser.parse(mixedJson).asObject())
        }
    }
}
