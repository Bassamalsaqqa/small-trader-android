package com.example.data.mobile.readclient

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class ReadClientGoldenParityTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenAuthChallengeParity() {
        val file = File(locateFixturesDir(), "golden-auth-challenge.json")
        assertTrue("golden-auth-challenge.json must exist", file.exists())
        val root = StrictJsonParser.parse(file.readText(Charsets.UTF_8)).asObject()

        val reqObj = root.require("request").asObject()
        val deviceId = ClientUuid.parse(reqObj.require("device_id").asString())
        val appVersion = reqObj.require("app_version").asString()
        val platform = reqObj.require("platform").asString()
        val req = AuthChallengeRequest(deviceId, appVersion, platform)
        assertEquals("8e2b8bc6-f80c-4034-8c88-59368d1f2e10", req.deviceId.value)
        assertEquals("1.0.0-rc1", req.appVersion)
        assertEquals("android", req.platform)

        val resObj = root.require("response").asObject()
        val res = AuthChallengeResponse.fromStrictJson(resObj)
        assertEquals("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d", res.challengeId.value)
        assertEquals("9f834928abceef761234509876abcdef1234567890abcdef1234567890abcdef", res.nonce)
        assertEquals("2026-11-10T12:05:00Z", res.expiresAt)
    }

    @Test
    fun testGoldenAuthLoginParity() {
        val file = File(locateFixturesDir(), "golden-auth-login.json")
        assertTrue("golden-auth-login.json must exist", file.exists())
        val root = StrictJsonParser.parse(file.readText(Charsets.UTF_8)).asObject()

        val reqObj = root.require("request").asObject()
        val req = AuthLoginRequest(
            challengeId = ClientUuid.parse(reqObj.require("challenge_id").asString()),
            nonce = reqObj.require("nonce").asString(),
            deviceId = ClientUuid.parse(reqObj.require("device_id").asString()),
            deviceName = reqObj.require("device_name").asString(),
            email = reqObj.require("email").asString(),
            password = reqObj.require("password").asString(),
            mfaCode = null,
            recoveryCode = null
        )
        assertEquals("cashier@smalltrader.example", req.email)
        assertEquals("Samsung Galaxy Tab A8", req.deviceName)

        val resObj = root.require("response").asObject()
        val loginResult = AuthLoginResult.fromStrictJson(resObj)
        assertTrue("Golden auth login must be Success variant", loginResult is AuthLoginResult.Success)

        val success = (loginResult as AuthLoginResult.Success).response
        assertEquals("sanctum_1092837465_mobile_token_982347109238471092384710", success.token)
        assertEquals("Bearer", success.tokenType)
        assertEquals("2026-11-17T12:00:00Z", success.expiresAt)

        // Actor profile assertions
        assertEquals(ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890"), success.actor.id)
        assertEquals("Ahmad Cashier", success.actor.name)
        assertEquals("cashier@smalltrader.example", success.actor.email)
        assertEquals("cashier", success.actor.role)
        assertEquals("epoch-20261101-v1", success.actor.passwordEpoch)

        // Company membership assertions
        assertEquals(1, success.companies.size)
        val company = success.companies.first()
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), company.id)
        assertEquals("Al-Amal Retail Company", company.name)
        assertEquals("ILS", company.baseCurrency)
        assertTrue(company.isDefault)
    }

    @Test
    fun testGoldenAuthLogoutParity() {
        val file = File(locateFixturesDir(), "golden-auth-logout.json")
        assertTrue("golden-auth-logout.json must exist", file.exists())
        val root = StrictJsonParser.parse(file.readText(Charsets.UTF_8)).asObject()

        val reqObj = root.require("request").asObject()
        val req = AuthLogoutRequest(ClientUuid.parse(reqObj.require("device_id").asString()))
        assertEquals("8e2b8bc6-f80c-4034-8c88-59368d1f2e10", req.deviceId.value)

        val resObj = root.require("response").asObject()
        val res = AuthLogoutResponse.fromStrictJson(resObj)
        assertEquals("logged_out", res.status)
        assertEquals("2026-11-10T12:30:00Z", res.revokedAt)
    }

    @Test
    fun testGoldenBootstrapParity() {
        val file = File(locateFixturesDir(), "golden-bootstrap.json")
        assertTrue("golden-bootstrap.json must exist", file.exists())
        val root = StrictJsonParser.parse(file.readText(Charsets.UTF_8)).asObject()

        val bootstrap = BootstrapResponse.fromStrictJson(root)
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), bootstrap.companyId)
        assertEquals("Al-Amal Retail Company", bootstrap.companyName)
        assertEquals("ILS", bootstrap.baseCurrency)
        assertEquals(ServerUlid.parse("01JCV8X9M4K1P2Q3R4S5T6V7W8"), bootstrap.walkInCustomerId)
        assertEquals("snap-20261110-v1", bootstrap.catalogSnapshotVersion)

        // Staff profile
        assertEquals(ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890"), bootstrap.staff.id)
        assertEquals("Ahmad Cashier", bootstrap.staff.name)
        assertEquals("cashier", bootstrap.staff.role)
        assertEquals(4, bootstrap.staff.permissions.size)

        // Offline policy disabled & device_lease null
        assertFalse("Offline policy must be disabled in golden bootstrap", bootstrap.offlinePolicy.enabled)
        assertEquals("unavailable", bootstrap.offlinePolicy.availability)
        assertNull("device_lease must be null when offline policy is disabled", bootstrap.deviceLease)

        // Default warehouse & approved cash account
        assertEquals(ServerUlid.parse("01JCV8X9M9A2B3C4D5E6F7G8H9"), bootstrap.defaultWarehouse.id)
        assertEquals("WH-MAIN", bootstrap.defaultWarehouse.code)
        assertEquals("Main Store Warehouse", bootstrap.defaultWarehouse.name)

        assertEquals(ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"), bootstrap.approvedCashAccount.id)
        assertEquals("CASH-REG-01", bootstrap.approvedCashAccount.code)
        assertEquals("ILS", bootstrap.approvedCashAccount.currency)

        // Units catalog
        assertEquals(2, bootstrap.units.size)
        val pcsUnit = bootstrap.units[0]
        assertEquals("PCS", pcsUnit.code)
        assertEquals("Piece", pcsUnit.name)
        assertEquals("pc", pcsUnit.symbol)
        assertFalse(pcsUnit.allowsFraction)
        assertEquals(0, pcsUnit.decimalPlaces)

        val kgUnit = bootstrap.units[1]
        assertEquals("KG", kgUnit.code)
        assertTrue(kgUnit.allowsFraction)
        assertEquals(3, kgUnit.decimalPlaces)

        // Taxes definitions
        assertEquals(2, bootstrap.taxes.size)
        val vat16 = bootstrap.taxes[0]
        assertEquals("VAT16", vat16.code)
        assertEquals("16.000000", vat16.rate.toPlainString())
        assertEquals("exclusive", vat16.calculation)
        assertTrue(vat16.isDefault)

        val zeroTax = bootstrap.taxes[1]
        assertEquals("ZERO", zeroTax.code)
        assertEquals("0.000000", zeroTax.rate.toPlainString())
        assertFalse(zeroTax.isDefault)
    }

    @Test
    fun testGoldenProductCatalogParity() {
        val file = File(locateFixturesDir(), "golden-product-catalog.json")
        assertTrue("golden-product-catalog.json must exist", file.exists())
        val root = StrictJsonParser.parse(file.readText(Charsets.UTF_8)).asObject()

        val catalog = ProductCatalogResponse.fromStrictJson(root)
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), catalog.companyId)
        assertEquals("snap-20261110-v1", catalog.catalogSnapshotVersion)
        assertEquals("2026-11-10T12:00:00Z", catalog.generatedAt)
        assertNull(catalog.nextCursor)
        assertFalse(catalog.hasMore)
        assertEquals(2, catalog.products.size)

        // Product 1: Olive oil with null tax pair
        val p1 = catalog.products[0]
        assertEquals(ServerUlid.parse("01JCV8X9M6A1B2C3D4E5F6G7H8"), p1.id)
        assertEquals("SKU-OLIVE-OIL-1L", p1.sku)
        assertEquals("6291041001234", p1.barcode)
        assertEquals("Extra Virgin Olive Oil 1L", p1.name)
        assertEquals("25.000000", p1.unitPrice.toPlainString())
        assertNull("tax_rate must be null in golden catalog (no product default tax association)", p1.taxRate)
        assertNull("tax_rate_id must be null in golden catalog", p1.taxRateId)
        assertTrue(p1.isTracked)
        assertEquals("142.000000", p1.availableQuantity.toPlainString())

        // Packaging unit mapping: defaultUnit productUnitId is unit public ULID bound to product
        assertEquals(ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"), p1.defaultUnit.unitId)
        assertEquals(p1.defaultUnit.unitId, p1.defaultUnit.productUnitId)
        assertEquals("1.000000", p1.defaultUnit.conversionToBase.toPlainString())
        assertNotNull(p1.defaultUnit.salePrice)
        assertEquals("25.000000", p1.defaultUnit.salePrice?.toPlainString())

        // Units list
        assertEquals(1, p1.units.size)
        val p1Unit = p1.units[0]
        assertEquals("BTL", p1Unit.code)
        assertEquals("Bottle", p1Unit.name)
        assertTrue(p1Unit.isBase)
        assertTrue(p1Unit.isDefaultSale)

        // Product 2: Flour bulk with null tax pair
        val p2 = catalog.products[1]
        assertEquals("SKU-FLOUR-BULK", p2.sku)
        assertNull(p2.barcode)
        assertEquals("4.500000", p2.unitPrice.toPlainString())
        assertNull(p2.taxRate)
        assertNull(p2.taxRateId)
        assertEquals("520.500000", p2.availableQuantity.toPlainString())
    }

    @Test
    fun testGoldenCustomerDirectoryParity() {
        val file = File(locateFixturesDir(), "golden-customer-directory.json")
        assertTrue("golden-customer-directory.json must exist", file.exists())
        val root = StrictJsonParser.parse(file.readText(Charsets.UTF_8)).asObject()

        val directory = CustomerDirectoryResponse.fromStrictJson(root)
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), directory.companyId)
        assertEquals("2026-11-10T12:00:00Z", directory.generatedAt)
        assertNull(directory.nextCursor)
        assertFalse(directory.hasMore)
        assertEquals(2, directory.customers.size)

        // Customer 1: Walk-in with Arabic text
        val c1 = directory.customers[0]
        assertEquals(ServerUlid.parse("01JCV8X9M4K1P2Q3R4S5T6V7W8"), c1.id)
        assertEquals("CUST-WALK-IN", c1.code)
        assertEquals("Walk-in Customer / عميل نقدي", c1.name)
        assertNull(c1.phone)
        assertTrue(c1.isWalkIn)

        // Customer 2: Supermarket
        val c2 = directory.customers[1]
        assertEquals(ServerUlid.parse("01JCV8X9M5T2V3V4W5X6Y7Z890"), c2.id)
        assertEquals("CUST-00102", c2.code)
        assertEquals("Tariq Supermarket", c2.name)
        assertEquals("+970599123456", c2.phone)
        assertFalse(c2.isWalkIn)
    }
}
