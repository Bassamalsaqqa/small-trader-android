package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnlineSaleIntentProfileGoldenParityTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenOnlineSaleIntentProfileParityAndDigest() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-online-sale-intent-profile.json")
        assertTrue("Golden online sale intent profile file must exist", goldenFile.exists())

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val contextJson = rootJson.require("context").asObject()
        val requestJson = rootJson.require("request").asObject()
        val expectedDigest = PayloadDigest.parse(rootJson.require("payload_digest").asString())
        val expectedCanonicalJson = rootJson.require("canonical_json").asString()

        // 1. Context parsing
        val context = OnlineSaleContext.fromStrictJson(contextJson)
        assertEquals(ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890"), context.actorId)
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), context.companyId)
        assertEquals(2, context.currencyMinorUnits)

        // 2. Project request to intent profile
        val profile = OnlineSaleIntentProfile.fromContextAndRequest(context, requestJson)

        // 3. Verify canonical serialization string matches exact golden fixture bytes
        val canonicalJson = CanonicalJsonSerializer.serialize(profile)
        assertEquals(expectedCanonicalJson, canonicalJson)

        // 4. Verify SHA-256 payload digest matches golden fixture
        val computedDigest = profile.computePayloadDigest()
        assertEquals(expectedDigest, computedDigest)
        assertEquals(
            "sha256:93b9aa677275388defe694390ab756d4b08f122087ec084b8dbd8b421ffc45fd",
            computedDigest.value
        )

        // 5. Verify using helper
        assertTrue(OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(profile, expectedDigest))
    }

    @Test
    fun testUnpaidModeNormalizesCashFieldsToNullAndPreservesZeroAmountLexeme() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-online-sale-intent-profile.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val context = OnlineSaleContext.fromStrictJson(rootJson.require("context").asObject())
        val requestJson = rootJson.require("request").asObject()

        // Construct request with unpaid mode and zero lexeme "0.000"
        val reqProps = requestJson.properties.toMutableMap()
        val paymentProps = mutableMapOf<String, com.example.data.mobile.contract.StrictJsonElement>(
            "settlement_mode" to com.example.data.mobile.contract.StrictJsonString("unpaid"),
            "amount" to com.example.data.mobile.contract.StrictJsonString("0.000")
        )
        reqProps["payment"] = StrictJsonObject(paymentProps)
        val unpaidReqObj = StrictJsonObject(reqProps)

        val profile = OnlineSaleIntentProfile.fromContextAndRequest(context, unpaidReqObj)

        assertEquals("unpaid", profile.payment.settlementMode)
        assertNull(profile.payment.paymentMethod)
        assertNull(profile.payment.cashAccountId)
        assertEquals("0.000", profile.payment.amount.toPlainString())

        // Verify canonical serialization includes explicit nulls for payment_method and cash_account_id
        val canonical = CanonicalJsonSerializer.serialize(profile)
        assertTrue(canonical.contains("\"payment_method\":null"))
        assertTrue(canonical.contains("\"cash_account_id\":null"))
        assertTrue(canonical.contains("\"amount\":\"0.000\""))

        // Reconstruct with explicit nulls in request
        paymentProps["payment_method"] = com.example.data.mobile.contract.StrictJsonNull
        paymentProps["cash_account_id"] = com.example.data.mobile.contract.StrictJsonNull
        val unpaidWithNullsReqObj = StrictJsonObject(reqProps)
        val profile2 = OnlineSaleIntentProfile.fromContextAndRequest(context, unpaidWithNullsReqObj)
        assertEquals(profile, profile2)
    }

    @Test
    fun testPartialModeWithValidCashFields() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-online-sale-intent-profile.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val context = OnlineSaleContext.fromStrictJson(rootJson.require("context").asObject())
        val requestJson = rootJson.require("request").asObject()

        val reqProps = requestJson.properties.toMutableMap()
        val paymentProps = mutableMapOf<String, com.example.data.mobile.contract.StrictJsonElement>(
            "settlement_mode" to com.example.data.mobile.contract.StrictJsonString("partial"),
            "payment_method" to com.example.data.mobile.contract.StrictJsonString("cash"),
            "cash_account_id" to com.example.data.mobile.contract.StrictJsonString("01JCV8X9MAJ3K4M5N6P7Q8R9S0"),
            "amount" to com.example.data.mobile.contract.StrictJsonString("20.000000")
        )
        reqProps["payment"] = StrictJsonObject(paymentProps)
        val partialReqObj = StrictJsonObject(reqProps)

        val profile = OnlineSaleIntentProfile.fromContextAndRequest(context, partialReqObj)
        assertEquals("partial", profile.payment.settlementMode)
        assertEquals("cash", profile.payment.paymentMethod)
        assertEquals(ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"), profile.payment.cashAccountId)
        assertEquals("20.000000", profile.payment.amount.toPlainString())
    }

    @Test
    fun testDefensiveCopyOfLinesInIntentProfile() {
        val line = OnlineSaleIntentLine(
            productId = ServerUlid.parse("01JCV8X9M6A1B2C3D4E5F6G7H8"),
            productUnitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("25.000000"),
            taxRateId = ServerUlid.parse("01J9Z5K3MN0000000000000030"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxInclusive = true
        )
        val mutableList = mutableListOf(line)
        val payment = OnlineSaleIntentPayment(
            settlementMode = "full",
            paymentMethod = "cash",
            cashAccountId = ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"),
            amount = MoneyAmount.fromPositiveString("50.000000")
        )
        val profile = OnlineSaleIntentProfile(
            actorId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890"),
            clientEventId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"),
            currency = "ILS",
            currencyMinorUnits = 2,
            customerId = ServerUlid.parse("01JCV8X9M4K1P2Q3R4S5T6V7W8"),
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            lines = mutableList,
            payment = payment,
            warehouseId = ServerUlid.parse("01JCV8X9M9A2B3C4D5E6F7G8H9")
        )

        mutableList.add(line)
        assertEquals(1, profile.lines.size)

        assertThrows(UnsupportedOperationException::class.java) {
            (profile.lines as MutableList).add(line)
        }
    }

    @Test
    fun testRootValidatedIntegrationManifestAuthoritativeSyntheticTemplateParity() {
        val rawWirePayloadJson = """{
            "client_event_id": "c0686be5-888f-4890-90bf-1f05f416a19a",
            "device_id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
            "customer_id": "01M7CZPF6TP283F3ZRXF5P8GT3",
            "warehouse_id": "01M7CZPF6TP283F3ZRXF5P8GT4",
            "currency": "ILS",
            "lines": [
                {
                    "product_id": "01M7CZPF6TP283F3ZRXF5P8GT5",
                    "product_unit_id": "01M7CZPF6TP283F3ZRXF5P8GT6",
                    "conversion_to_base": "1.000000",
                    "quantity": "1.000000",
                    "unit_price": "50.000000",
                    "tax_rate_id": "01M7CZPF6TP283F3ZRXF5P8GT7",
                    "tax_rate": "16.000000",
                    "tax_inclusive": false
                }
            ],
            "payment": {
                "settlement_mode": "full",
                "payment_method": "cash",
                "cash_account_id": "01M7CZPF6TP283F3ZRXF5P8GT8",
                "amount": "58.000000"
            }
        }"""

        val serverSynthesizedContextJson = """{
            "company_id": "01M7CZPF6TP283F3ZRXF5P8GT2",
            "actor_id": "01M7CZPF6TP283F3ZRXF5P8GT1",
            "currency_minor_units": 2
        }"""

        val expectedDigest = PayloadDigest.parse("sha256:0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630")
        val expectedCanonicalJson = "{\"actor_id\":\"01M7CZPF6TP283F3ZRXF5P8GT1\",\"client_event_id\":\"c0686be5-888f-4890-90bf-1f05f416a19a\",\"company_id\":\"01M7CZPF6TP283F3ZRXF5P8GT2\",\"currency\":\"ILS\",\"currency_minor_units\":2,\"customer_id\":\"01M7CZPF6TP283F3ZRXF5P8GT3\",\"device_id\":\"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\",\"lines\":[{\"conversion_to_base\":\"1.000000\",\"product_id\":\"01M7CZPF6TP283F3ZRXF5P8GT5\",\"product_unit_id\":\"01M7CZPF6TP283F3ZRXF5P8GT6\",\"quantity\":\"1.000000\",\"tax_inclusive\":false,\"tax_rate\":\"16.000000\",\"tax_rate_id\":\"01M7CZPF6TP283F3ZRXF5P8GT7\",\"unit_price\":\"50.000000\"}],\"payment\":{\"amount\":\"58.000000\",\"cash_account_id\":\"01M7CZPF6TP283F3ZRXF5P8GT8\",\"payment_method\":\"cash\",\"settlement_mode\":\"full\"},\"warehouse_id\":\"01M7CZPF6TP283F3ZRXF5P8GT4\"}"

        val context = OnlineSaleContext.fromStrictJson(StrictJsonParser.parse(serverSynthesizedContextJson).asObject())
        val requestObj = StrictJsonParser.parse(rawWirePayloadJson).asObject()

        val profile = OnlineSaleIntentProfile.fromContextAndRequest(context, requestObj)

        val canonicalJson = CanonicalJsonSerializer.serialize(profile)
        assertEquals(expectedCanonicalJson, canonicalJson)

        val computedDigest = profile.computePayloadDigest()
        assertEquals(expectedDigest, computedDigest)
        assertEquals("sha256:0847e10d89777e103252ebfa2d27f89a95832a5d70e01af57afe6423f703c630", computedDigest.value)

        assertTrue(OnlineSaleIntentProfile.verifyOnlineSaleIntentProfile(profile, expectedDigest))
    }
}
