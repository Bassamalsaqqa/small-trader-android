package com.example.ui.staff.sale

import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.NonnegativeDecimalQuantity
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.readclient.ApprovedCashAccount
import com.example.data.mobile.readclient.BootstrapResponse
import com.example.data.mobile.readclient.BootstrapStaff
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.DefaultWarehouse
import com.example.data.mobile.readclient.OfflinePolicyDisabled
import com.example.data.mobile.readclient.ProductBaseUnit
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.data.mobile.readclient.ProductDefaultUnit
import com.example.data.mobile.session.StaffSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class StaffOnlineSaleDraftTest {

    private val companyUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV")
    private val actorUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA1")
    private val deviceUuid = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val warehouseUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA2")
    private val cashAccountUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA3")
    private val walkInUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA4")
    private val productUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA5")
    private val unitUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA6")
    private val taxRateUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA7")

    private fun createSampleProduct(
        unitPrice: String = "25.000000",
        taxRateId: ServerUlid? = null,
        taxRate: TaxPercentage? = null
    ): ProductCatalogItem {
        return ProductCatalogItem(
            id = productUlid,
            sku = "SKU-001",
            barcode = "1234567890123",
            name = "Test Product",
            unitPrice = MoneyAmount.fromNonnegativeString(unitPrice),
            taxRate = taxRate,
            taxRateId = taxRateId,
            isTracked = true,
            availableQuantity = NonnegativeDecimalQuantity.fromNonnegativeString("100.000000"),
            baseUnit = ProductBaseUnit(unitUlid, "PCS", "Pieces"),
            defaultUnit = ProductDefaultUnit(
                unitId = unitUlid,
                productUnitId = unitUlid,
                conversionToBase = PositiveDecimalQuantity.of(1),
                salePrice = MoneyAmount.fromNonnegativeString(unitPrice)
            ),
            units = emptyList()
        )
    }

    private fun createSampleBootstrap(): BootstrapResponse {
        return BootstrapResponse(
            companyId = companyUlid,
            companyName = "Acme Trading",
            baseCurrency = "USD",
            walkInCustomerId = walkInUlid,
            staff = BootstrapStaff(actorUlid, "Cashier Staff", "staff@example.com", listOf("cashier")),
            deviceLease = null,
            defaultWarehouse = DefaultWarehouse(warehouseUlid, "WH-MAIN", "Main Warehouse"),
            approvedCashAccount = ApprovedCashAccount(cashAccountUlid, "CASH-01", "Main Drawer", "USD"),
            units = emptyList(),
            taxes = emptyList(),
            offlinePolicy = OfflinePolicyDisabled(),
            catalogSnapshotVersion = "v1.0"
        )
    }

    private fun createSampleSession(): StaffSessionRecord {
        return StaffSessionRecord(
            token = "sample_session_bearer_token",
            actorUlid = actorUlid,
            deviceUuid = deviceUuid,
            passwordEpoch = "epoch-1",
            expiresAt = "2026-11-15T23:59:59Z",
            selectedCompanyId = companyUlid,
            roleLabel = "Cashier",
            actorName = "Cashier Staff",
            actorEmail = "staff@example.com",
            createdAt = "2026-11-15T00:00:00Z"
        )
    }

    @Test
    fun testDraftLineCalculationsExactDecimal() {
        val product = createSampleProduct(unitPrice = "12.500000")
        val line = StaffOnlineSaleDraftLine.fromProduct(
            product = product,
            quantity = PositiveDecimalQuantity.fromPositiveString("3.000000")
        )

        assertEquals("37.500000", line.estimatedSubtotal.toPlainString())
        assertEquals("0.000000", line.estimatedTax.toPlainString())
        assertFalse(line.hasUnresolvedTaxContract)
        assertFalse(line.taxInclusive == true)
        assertNull(line.taxRateId)
    }

    @Test
    fun testDraftTotalsExactDecimalWithTax() {
        val prodNoTax = createSampleProduct(unitPrice = "10.000000")
        val line1 = StaffOnlineSaleDraftLine.fromProduct(
            product = prodNoTax,
            quantity = PositiveDecimalQuantity.of(2)
        )

        val prodTaxed = createSampleProduct(
            unitPrice = "50.000000",
            taxRateId = taxRateUlid,
            taxRate = TaxPercentage.fromString("16.000000")
        )
        val line2 = StaffOnlineSaleDraftLine.fromProduct(
            product = prodTaxed,
            quantity = PositiveDecimalQuantity.of(1),
            taxInclusiveOverride = false
        )

        val draft = StaffOnlineSaleDraft(lines = listOf(line1, line2))

        assertEquals("70.000000", draft.estimatedSubtotal.toPlainString())
        assertEquals("8.000000", draft.estimatedTax.toPlainString())
        assertEquals("78.000000", draft.estimatedTotal.toPlainString())
        assertFalse(draft.hasUnresolvedTaxContract)
    }

    @Test
    fun testAuthorityResolutionMissingContractsFailsClosed() {
        val bootstrap = createSampleBootstrap()
        val session = createSampleSession()

        // Without minor units override: fails closed because V1 Bootstrap lacks currency_minor_units
        val resolution = StaffOnlineSaleAuthorityResolution.resolve(bootstrap, session, currencyMinorUnitsOverride = null)

        assertFalse(resolution.isDispatchPermitted)
        assertNull(resolution.currencyMinorUnits)
        assertTrue(resolution.missingContracts.any { it.contains("currency_minor_units") })
    }

    @Test
    fun testAuthorityResolutionSucceedsWhenContractsResolved() {
        val bootstrap = createSampleBootstrap()
        val session = createSampleSession()

        val resolution = StaffOnlineSaleAuthorityResolution.resolve(bootstrap, session, currencyMinorUnitsOverride = 2)

        assertTrue(resolution.isDispatchPermitted)
        assertEquals(2, resolution.currencyMinorUnits)
        assertEquals(companyUlid, resolution.companyId)
        assertEquals(actorUlid, resolution.actorId)
        assertEquals(deviceUuid, resolution.deviceId)
        assertEquals(warehouseUlid, resolution.warehouseId)
        assertEquals(cashAccountUlid, resolution.cashAccountId)
        assertEquals(walkInUlid, resolution.walkInCustomerId)
        assertEquals("USD", resolution.currency)
        assertTrue(resolution.missingContracts.isEmpty())
    }

    @Test
    fun testDraftUnresolvedTaxContractFailsClosed() {
        val bootstrap = createSampleBootstrap()
        val session = createSampleSession()
        val authority = StaffOnlineSaleAuthorityResolution.resolve(bootstrap, session, currencyMinorUnitsOverride = 2)

        val prodTaxed = createSampleProduct(
            unitPrice = "100.000000",
            taxRateId = taxRateUlid,
            taxRate = TaxPercentage.fromString("15.000000")
        )

        // taxInclusiveOverride = null because V1 ProductCatalogItem lacks tax_inclusive!
        val line = StaffOnlineSaleDraftLine.fromProduct(
            product = prodTaxed,
            quantity = PositiveDecimalQuantity.of(1),
            taxInclusiveOverride = null
        )

        val draft = StaffOnlineSaleDraft(lines = listOf(line))

        assertTrue(line.hasUnresolvedTaxContract)
        assertTrue(draft.hasUnresolvedTaxContract)
        assertFalse(draft.canPrepareIntent(authority))

        try {
            StaffOnlineSaleRequestBuilder.buildRawWireMap(draft, authority)
            fail("Expected exception when building wire payload with unresolved tax contract")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("unresolved tax contract") == true)
        }
    }

    @Test
    fun testDraftPreparesExact7KeyWireJsonAndNormativeProfile() {
        val bootstrap = createSampleBootstrap()
        val session = createSampleSession()
        val authority = StaffOnlineSaleAuthorityResolution.resolve(bootstrap, session, currencyMinorUnitsOverride = 2)

        val product = createSampleProduct(unitPrice = "20.000000")
        val line = StaffOnlineSaleDraftLine.fromProduct(
            product = product,
            quantity = PositiveDecimalQuantity.of(1)
        )

        val draft = StaffOnlineSaleDraft(lines = listOf(line))
        assertTrue(draft.canPrepareIntent(authority))

        val eventId = ClientUuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val wireMap = StaffOnlineSaleRequestBuilder.buildRawWireMap(draft, authority, eventId)

        // Strict 7 root keys
        val expectedRootKeys = setOf("client_event_id", "device_id", "customer_id", "warehouse_id", "currency", "lines", "payment")
        assertEquals(expectedRootKeys, wireMap.keys)

        assertEquals(eventId.value, wireMap["client_event_id"])
        assertEquals(deviceUuid.value, wireMap["device_id"])
        assertEquals(walkInUlid.value, wireMap["customer_id"])
        assertEquals(warehouseUlid.value, wireMap["warehouse_id"])
        assertEquals("USD", wireMap["currency"])

        // Line keys (8)
        @Suppress("UNCHECKED_CAST")
        val linesList = wireMap["lines"] as List<Map<String, Any?>>
        assertEquals(1, linesList.size)
        val line0 = linesList[0]
        val expectedLineKeys = setOf("product_id", "product_unit_id", "conversion_to_base", "quantity", "unit_price", "tax_rate_id", "tax_rate", "tax_inclusive")
        assertEquals(expectedLineKeys, line0.keys)
        assertEquals(productUlid.value, line0["product_id"])
        assertEquals(unitUlid.value, line0["product_unit_id"])
        assertEquals("1", line0["conversion_to_base"])
        assertEquals("1", line0["quantity"])
        assertEquals("20.000000", line0["unit_price"])
        assertNull(line0["tax_rate_id"])
        assertEquals("0.000000", line0["tax_rate"])
        assertEquals(false, line0["tax_inclusive"])

        // Payment keys (4)
        @Suppress("UNCHECKED_CAST")
        val paymentMap = wireMap["payment"] as Map<String, Any?>
        val expectedPaymentKeys = setOf("settlement_mode", "payment_method", "cash_account_id", "amount")
        assertEquals(expectedPaymentKeys, paymentMap.keys)
        assertEquals("full", paymentMap["settlement_mode"])
        assertEquals("cash", paymentMap["payment_method"])
        assertEquals(cashAccountUlid.value, paymentMap["cash_account_id"])
        assertEquals("20.00", paymentMap["amount"])

        // PreparedOnlineSaleIntent creation
        val intent = StaffOnlineSaleRequestBuilder.prepareIntent(
            draft = draft,
            authority = authority,
            scopeGeneration = 42L,
            clientEventId = eventId
        )

        assertNotNull(intent)
        assertEquals(42L, intent.scopeGeneration)
        assertEquals(eventId, intent.clientEventId)
        assertEquals(deviceUuid, intent.deviceId)
        assertEquals("USD", intent.currency)
        assertNotNull(intent.expectedNormativeProfileDigest)
        assertTrue(intent.expectedNormativeProfileDigest.value.startsWith("sha256:"))

        // Verify that rawWireBytes is strictly canonical UTF-8 JSON without Doubles
        val jsonStr = String(intent.rawWireBytes, Charsets.UTF_8)
        assertFalse(jsonStr.contains("Double"))
        assertFalse(jsonStr.contains("E+"))
        assertFalse(jsonStr.contains("E-"))
        val parsed = StrictJsonParser.parse(jsonStr)
        assertNotNull(parsed.asObject())
    }

    @Test
    fun testSelectedCustomerOverridesWalkIn() {
        val bootstrap = createSampleBootstrap()
        val session = createSampleSession()
        val authority = StaffOnlineSaleAuthorityResolution.resolve(bootstrap, session, currencyMinorUnitsOverride = 2)

        val product = createSampleProduct(unitPrice = "10.000000")
        val line = StaffOnlineSaleDraftLine.fromProduct(product = product, quantity = PositiveDecimalQuantity.of(1))

        val customCustomerUlid = ServerUlid.parse("01ARZ3NDEKTSV4RRFFQ69G5CUST")
        val customer = CustomerDirectoryItem(
            id = customCustomerUlid,
            code = "CUST-007",
            name = "Special Customer",
            phone = null,
            isWalkIn = false
        )

        val draft = StaffOnlineSaleDraft(
            lines = listOf(line),
            selectedCustomer = customer
        )

        val wireMap = StaffOnlineSaleRequestBuilder.buildRawWireMap(draft, authority)
        assertEquals(customCustomerUlid.value, wireMap["customer_id"])
    }
}
