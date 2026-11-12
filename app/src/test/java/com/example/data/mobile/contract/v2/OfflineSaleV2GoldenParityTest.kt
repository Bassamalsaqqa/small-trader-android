package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.PositiveDecimalQuantity
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineSaleV2GoldenParityTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenOfflineSaleV2IntakeParityAndDigest() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake-v2.json")
        assertTrue("Golden v2 offline intake file must exist", goldenFile.exists())

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()

        // 1. Parse strongly-typed domain model from JSON
        val intakeReq = OfflineSaleIntakeRequestV2.fromStrictJson(reqJson)

        // 2. Envelope bindings equality
        assertEquals(intakeReq.clientEventId, intakeReq.event.clientEventId)
        assertEquals(intakeReq.deviceId, intakeReq.event.deviceId)
        assertEquals(intakeReq.capturedAt, intakeReq.event.capturedAt)

        // 3. Line items and revision 3 fields
        val line = intakeReq.event.lines.first()
        assertEquals(ServerUlid.parse("01J9Z5K3MN0000000000000010"), line.productId)
        assertEquals(ServerUlid.parse("01J9Z5K3MN0000000000000020"), line.productUnitId)
        assertEquals(line.productUnitId, line.unitId)
        assertEquals("1.000000", line.conversionToBase.toPlainString())
        assertEquals("2.000000", line.quantity.toPlainString())
        assertEquals("25.000000", line.unitPrice.toPlainString())
        assertEquals(ServerUlid.parse("01J9Z5K3MN0000000000000030"), line.taxRateId)
        assertEquals("16.000000", line.taxRate.toPlainString())
        assertTrue(line.taxInclusive)
        assertEquals("6.900000", line.taxAmount.toPlainString())
        assertEquals("50.000000", line.lineTotal.toPlainString())

        // 4. Payment fields
        val payment = intakeReq.event.payment
        assertEquals("full", payment.mode)
        assertEquals("cash", payment.paymentMethod)
        assertEquals(ServerUlid.parse("01J9Z5K3MN0000000000000005"), payment.cashAccountId)
        assertEquals("50.000000", payment.amountTendered.toPlainString())
        assertEquals("50.000000", payment.amountSettled.toPlainString())
        assertEquals("0.000000", payment.changeReturned.toPlainString())

        // 5. Canonical JSON serialization and digest
        val computedDigest = intakeReq.event.computePayloadDigest()
        val expectedDigest = PayloadDigest.parse("sha256:18f0403bbda6e05eab2805eb20c9c9f9e22d86fb922eda1482db85d24dba30a3")

        assertEquals("Golden v2 offline intake payload_digest mismatch", expectedDigest, computedDigest)
        assertEquals("Envelope payload_digest must match computed digest", intakeReq.payloadDigest, computedDigest)

        // 6. Round-trip serialization equality
        val serializedJson = CanonicalJsonSerializer.serialize(intakeReq)
        val reparsedReq = OfflineSaleIntakeRequestV2.fromStrictJson(StrictJsonParser.parse(serializedJson).asObject())
        assertEquals(intakeReq, reparsedReq)
    }

    @Test
    fun testRejectEventVersion1InV2() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake-v2.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val rawReq = rootJson.require("request").asObject()

        // Mutate event_version to 1
        val eventObj = rawReq.require("event").asObject()
        val mutatedEventProps = eventObj.properties.toMutableMap()
        mutatedEventProps["event_version"] = com.example.data.mobile.contract.StrictJsonNumber("1", isInt = true)
        val mutatedEventObj = StrictJsonObject(mutatedEventProps)

        assertThrows(IllegalArgumentException::class.java) {
            OfflineSaleEventV2.fromStrictJson(mutatedEventObj)
        }
    }

    @Test
    fun testTamperedEnvelopeDigestDetected() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake-v2.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()

        val tamperedProps = reqJson.properties.toMutableMap()
        tamperedProps["payload_digest"] = com.example.data.mobile.contract.StrictJsonString("sha256:0000000000000000000000000000000000000000000000000000000000000000")
        val tamperedReqObj = StrictJsonObject(tamperedProps)

        assertThrows(CanonicalSerializationException::class.java) {
            OfflineSaleIntakeRequestV2.fromStrictJson(tamperedReqObj)
        }
    }

    @Test
    fun testDefensiveCopyPreventsExternalListMutation() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake-v2.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()
        val intakeReq = OfflineSaleIntakeRequestV2.fromStrictJson(reqJson)

        val mutableList = ArrayList(intakeReq.event.lines)
        val event = OfflineSaleEventV2(
            clientEventId = intakeReq.event.clientEventId,
            eventVersion = 2,
            companyId = intakeReq.event.companyId,
            actorId = intakeReq.event.actorId,
            deviceId = intakeReq.event.deviceId,
            deviceLeaseId = intakeReq.event.deviceLeaseId,
            capturedAt = intakeReq.event.capturedAt,
            customerId = intakeReq.event.customerId,
            currency = intakeReq.event.currency,
            warehouseId = intakeReq.event.warehouseId,
            cashAccountId = intakeReq.event.cashAccountId,
            catalogSnapshotVersion = intakeReq.event.catalogSnapshotVersion,
            localReceiptNumber = intakeReq.event.localReceiptNumber,
            lines = mutableList,
            payment = intakeReq.event.payment
        )

        // Attempt to modify the original mutable list passed into constructor
        val extraLine = event.lines.first()
        mutableList.add(extraLine)

        // Event's lines list must remain unchanged at size 1
        assertEquals(1, event.lines.size)

        // Event's internal lines list must throw UnsupportedOperationException on add
        assertThrows(UnsupportedOperationException::class.java) {
            (event.lines as MutableList).add(extraLine)
        }
    }

    @Test
    fun testCopyMethodPreservesInvariants() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake-v2.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()
        val intakeReq = OfflineSaleIntakeRequestV2.fromStrictJson(reqJson)

        val originalEvent = intakeReq.event
        val copiedEvent = originalEvent.copy()

        assertEquals(originalEvent, copiedEvent)
        assertEquals(originalEvent.hashCode(), copiedEvent.hashCode())
        assertEquals(originalEvent.computePayloadDigest(), copiedEvent.computePayloadDigest())
    }

    @Test
    fun testExplicitNoTaxInLineItem() {
        val noTaxLine = OfflineSaleLineV2(
            productId = ServerUlid.parse("01J9Z5K3MN0000000000000010"),
            productUnitId = ServerUlid.parse("01J9Z5K3MN0000000000000020"),
            unitId = ServerUlid.parse("01J9Z5K3MN0000000000000020"),
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("10.000000"),
            taxRateId = null,
            taxRate = TaxPercentage.fromString("0.000000"),
            taxInclusive = false,
            taxAmount = MoneyAmount.fromNonnegativeString("0.000000"),
            lineTotal = MoneyAmount.fromPositiveString("10.000000")
        )
        assertNull(noTaxLine.taxRateId)
        assertEquals("0.000000", noTaxLine.taxRate.toPlainString())

        // Violating ExplicitNoTax: null taxRateId with non-zero taxRate
        assertThrows(IllegalArgumentException::class.java) {
            noTaxLine.copy(taxRate = TaxPercentage.fromString("16.000000"))
        }

        // Violating ExplicitNoTax: null taxRateId with taxInclusive true
        assertThrows(IllegalArgumentException::class.java) {
            noTaxLine.copy(taxInclusive = true)
        }
    }
}
