package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class OfflineSaleContractGoldenParityTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenOfflineSaleIntakeParityAndDigest() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake.json")
        assertTrue("Golden offline sale intake file must exist", goldenFile.exists())

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()

        // Parse strongly-typed domain model from JSON
        val intakeReq = OfflineSaleIntakeRequest.fromStrictJson(reqJson)

        // 1. Envelope equality checks
        assertEquals(intakeReq.clientEventId, intakeReq.event.clientEventId)
        assertEquals(intakeReq.deviceId, intakeReq.event.deviceId)
        assertEquals(intakeReq.capturedAt, intakeReq.event.capturedAt)

        // 2. Packaging unit mapping check: product_unit_id is unit public ULID bound to product
        val line = intakeReq.event.lines.first()
        assertEquals(ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"), line.productUnitId)
        assertEquals(line.productUnitId, line.unitId)

        // 3. Compute canonical JSON digest over event
        val computedDigest = intakeReq.event.computePayloadDigest()
        val expectedDigest = PayloadDigest.parse("sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9")

        assertEquals("Golden offline intake payload_digest mismatch", expectedDigest, computedDigest)
        assertEquals("Envelope payload_digest must match computed digest", intakeReq.payloadDigest, computedDigest)

        // 4. Validate envelope bindings helper succeeds on valid payload
        intakeReq.validateEnvelopeBindings()
    }

    @Test
    fun testEnvelopeTamperDetection() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-offline-sale-intake.json")
        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val reqJson = rootJson.require("request").asObject()
        val validReq = OfflineSaleIntakeRequest.fromStrictJson(reqJson)

        // Envelope client_event_id mismatch caught at construction
        assertThrows(IllegalArgumentException::class.java) {
            validReq.copy(clientEventId = ClientUuid.random())
        }

        // Envelope device_id mismatch caught at construction
        assertThrows(IllegalArgumentException::class.java) {
            validReq.copy(deviceId = ClientUuid.random())
        }

        // Envelope captured_at mismatch caught at construction
        assertThrows(IllegalArgumentException::class.java) {
            validReq.copy(capturedAt = "2026-11-10T13:00:00Z")
        }

        // Tampered payload digest caught at construction
        assertThrows(IllegalArgumentException::class.java) {
            validReq.copy(
                payloadDigest = PayloadDigest.parse("sha256:0000000000000000000000000000000000000000000000000000000000000000")
            )
        }
    }

    @Test
    fun testAlteredPayloadConflictRegression() {
        val fixturesDir = locateFixturesDir()
        val altFile = File(fixturesDir, "regression-altered-payload-conflict.json")
        assertTrue("Altered payload regression file must exist", altFile.exists())

        val altRoot = StrictJsonParser.parse(altFile.readText(Charsets.UTF_8)).asObject()
        val incomingReqJson = altRoot.require("incoming_request").asObject()
        val storedRecordJson = altRoot.require("stored_record").asObject()

        val incomingReq = OfflineSaleIntakeRequest.fromStrictJson(incomingReqJson)
        val storedDigest = PayloadDigest.parse(storedRecordJson.require("payload_digest").asString())

        val computedAltDigest = incomingReq.event.computePayloadDigest()

        // Computed digest matches incoming request declared digest
        assertEquals(incomingReq.payloadDigest, computedAltDigest)

        // Altered payload MUST differ from stored record digest
        assertNotEquals("Altered payload digest must differ from stored record digest", storedDigest, computedAltDigest)
    }

    @Test
    fun testLostAckReplayParity() {
        val fixturesDir = locateFixturesDir()
        val lostAckFile = File(fixturesDir, "regression-lost-ack-replay.json")
        assertTrue("Lost ACK fixture file must exist", lostAckFile.exists())

        val root = StrictJsonParser.parse(lostAckFile.readText(Charsets.UTF_8)).asObject()
        val firstSub = root.require("first_submission").asObject()
        val retrySub = root.require("retry_submission").asObject()

        assertEquals(
            "Lost-ACK client_event_id must match between submissions",
            firstSub.require("client_event_id").asString(),
            retrySub.require("client_event_id").asString()
        )
        assertEquals(
            "Lost-ACK payload_digest must match between submissions",
            firstSub.require("payload_digest").asString(),
            retrySub.require("payload_digest").asString()
        )
    }

    @Test
    fun testExactDecimalLexemePreservationAndModelRoundtrip() {
        val jsonPayload = """
        {
          "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "device_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
          "captured_at": "2026-11-10T12:00:00Z",
          "payload_digest": "",
          "event": {
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "event_version": 1,
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "actor_id": "01JCV8X9M8T1V2W3X4Y5Z67890",
            "device_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
            "device_lease_id": "7c1e5a2b-3d4f-5678-90ab-cdef12345678",
            "captured_at": "2026-11-10T12:00:00Z",
            "customer_id": "01JCV8X9M4K1P2Q3R4S5T6V7W8",
            "currency": "ILS",
            "warehouse_id": "01JCV8X9M9A2B3C4D5E6F7G8H9",
            "cash_account_id": "01JCV8X9MAJ3K4M5N6P7Q8R9S0",
            "catalog_snapshot_version": "snap-20261110-v1",
            "local_receipt_number": "REC-OFF-00042",
            "lines": [
              {
                "product_id": "01JCV8X9M6A1B2C3D4E5F6G7H8",
                "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                "quantity": "1",
                "unit_price": "50",
                "tax_rate": "16.000000",
                "tax_amount": "8.000000",
                "line_total": "58.000000"
              }
            ],
            "payment": {
              "mode": "full",
              "payment_method": "cash",
              "cash_account_id": "01JCV8X9MAJ3K4M5N6P7Q8R9S0",
              "amount_tendered": "100.000000",
              "amount_settled": "58.000000",
              "change_returned": "42.000000"
            }
          }
        }
        """.trimIndent()

        val parsedInit = StrictJsonParser.parse(jsonPayload).asObject()
        val rawEventObj = parsedInit.require("event").asObject()
        val originalDigest = CanonicalJsonSerializer.computeDigest(rawEventObj)

        val fullJsonWithDigest = jsonPayload.replace(
            "\"payload_digest\": \"\"",
            "\"payload_digest\": \"${originalDigest.value}\""
        )
        val parsedFull = StrictJsonParser.parse(fullJsonWithDigest).asObject()

        // Decode into strongly-typed model
        val requestModel = OfflineSaleIntakeRequest.fromStrictJson(parsedFull)

        // Verify preserved lexemes
        val line = requestModel.event.lines.first()
        assertEquals("Exact quantity lexeme '1' must be preserved", "1", line.quantity.lexeme)
        assertEquals("Exact quantity toPlainString() must be '1'", "1", line.quantity.toPlainString())
        assertEquals("Exact unit_price lexeme '50' must be preserved", "50", line.unitPrice.lexeme)
        assertEquals("Exact unit_price toPlainString() must be '50'", "50", line.unitPrice.toPlainString())

        // Arithmetic view matches
        assertEquals(BigDecimal("1"), line.quantity.value)
        assertEquals(BigDecimal("50"), line.unitPrice.value)

        // Reserialize from domain model to canonical JSON
        val canonicalEventString = CanonicalJsonSerializer.serialize(requestModel.event)
        val roundtripDigest = CanonicalJsonSerializer.computeDigest(requestModel.event)

        // Confirm model roundtrip produces identical bytes and identical digest
        val rawCanonicalString = CanonicalJsonSerializer.serialize(rawEventObj)
        assertEquals(rawCanonicalString, canonicalEventString)
        assertEquals("Model roundtrip must produce identical digest", originalDigest, roundtripDigest)

        // Check canonical output contains exact uncoerced strings "1" and "50"
        assertTrue(canonicalEventString.contains("\"quantity\":\"1\""))
        assertTrue(canonicalEventString.contains("\"unit_price\":\"50\""))
    }

    @Test
    fun testUnknownFieldsRejectedAtAllLevels() {
        val validIntakeJson = """
        {
          "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "device_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
          "captured_at": "2026-11-10T12:00:00Z",
          "payload_digest": "sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9",
          "event": {
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "event_version": 1,
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "actor_id": "01JCV8X9M8T1V2W3X4Y5Z67890",
            "device_id": "8e2b8bc6-f80c-4034-8c88-59368d1f2e10",
            "device_lease_id": "7c1e5a2b-3d4f-5678-90ab-cdef12345678",
            "captured_at": "2026-11-10T12:00:00Z",
            "customer_id": "01JCV8X9M4K1P2Q3R4S5T6V7W8",
            "currency": "ILS",
            "warehouse_id": "01JCV8X9M9A2B3C4D5E6F7G8H9",
            "cash_account_id": "01JCV8X9MAJ3K4M5N6P7Q8R9S0",
            "catalog_snapshot_version": "snap-20261110-v1",
            "local_receipt_number": "REC-OFF-00042",
            "lines": [
              {
                "product_id": "01JCV8X9M6A1B2C3D4E5F6G7H8",
                "product_unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                "unit_id": "01JCV8X9MBV4W5X6Y7Z8012345",
                "quantity": "2.000000",
                "unit_price": "25.000000",
                "tax_rate": "16.000000",
                "tax_amount": "8.000000",
                "line_total": "58.000000"
              }
            ],
            "payment": {
              "mode": "full",
              "payment_method": "cash",
              "cash_account_id": "01JCV8X9MAJ3K4M5N6P7Q8R9S0",
              "amount_tendered": "100.000000",
              "amount_settled": "58.000000",
              "change_returned": "42.000000"
            }
          }
        }
        """.trimIndent()

        // 1. Root level unexpected field
        val rootUnknown = validIntakeJson.replace("\"client_event_id\":", "\"unknown_root\": 1, \"client_event_id\":")
        assertThrows(CanonicalSerializationException::class.java) {
            OfflineSaleIntakeRequest.fromStrictJson(StrictJsonParser.parse(rootUnknown).asObject())
        }

        // 2. Event level unexpected field
        val eventUnknown = validIntakeJson.replace("\"event_version\": 1,", "\"event_version\": 1, \"extra_event_field\": \"bad\",")
        assertThrows(CanonicalSerializationException::class.java) {
            OfflineSaleIntakeRequest.fromStrictJson(StrictJsonParser.parse(eventUnknown).asObject())
        }

        // 3. Line level unexpected field
        val lineUnknown = validIntakeJson.replace("\"product_id\":", "\"unknown_line_prop\": true, \"product_id\":")
        assertThrows(CanonicalSerializationException::class.java) {
            OfflineSaleIntakeRequest.fromStrictJson(StrictJsonParser.parse(lineUnknown).asObject())
        }

        // 4. Payment level unexpected field
        val paymentUnknown = validIntakeJson.replace("\"mode\": \"full\",", "\"mode\": \"full\", \"tip_amount\": \"5.000000\",")
        assertThrows(CanonicalSerializationException::class.java) {
            OfflineSaleIntakeRequest.fromStrictJson(StrictJsonParser.parse(paymentUnknown).asObject())
        }
    }

    @Test
    fun testDeterministicBoundaryChecks() {
        val validLine = OfflineSaleLine(
            productId = ServerUlid.parse("01JCV8X9M6A1B2C3D4E5F6G7H8"),
            productUnitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
            unitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
            quantity = DecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("25.000000"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxAmount = MoneyAmount.fromNonnegativeString("8.000000"),
            lineTotal = MoneyAmount.fromPositiveString("58.000000")
        )

        val validPayment = OfflineSalePayment(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"),
            amountTendered = MoneyAmount.fromNonnegativeString("100.000000"),
            amountSettled = MoneyAmount.fromPositiveString("58.000000"),
            changeReturned = MoneyAmount.fromNonnegativeString("42.000000")
        )

        val validEvent = OfflineSaleEvent(
            clientEventId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"),
            actorId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890"),
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceLeaseId = ClientUuid.parse("7c1e5a2b-3d4f-5678-90ab-cdef12345678"),
            capturedAt = "2026-11-10T12:00:00Z",
            customerId = ServerUlid.parse("01JCV8X9M4K1P2Q3R4S5T6V7W8"),
            currency = "ILS",
            warehouseId = ServerUlid.parse("01JCV8X9M9A2B3C4D5E6F7G8H9"),
            cashAccountId = ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"),
            catalogSnapshotVersion = "snap-20261110-v1",
            localReceiptNumber = "REC-OFF-00042",
            lines = listOf(validLine),
            payment = validPayment
        )

        // 1. unitId vs productUnitId equality
        assertThrows(IllegalArgumentException::class.java) {
            validLine.copy(unitId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"))
        }

        // 2. Reject zero line total
        assertThrows(IllegalArgumentException::class.java) {
            validLine.copy(lineTotal = MoneyAmount("0.000000"))
        }

        // 3. Cash conservation: tendered (100) - settled (58) != change (40)
        assertThrows(IllegalArgumentException::class.java) {
            validPayment.copy(changeReturned = MoneyAmount("40.000000"))
        }

        // 4. Cash account equality between event and payment
        assertThrows(IllegalArgumentException::class.java) {
            validEvent.copy(cashAccountId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"))
        }

        // 5. Line sum vs settled conservation: lineTotal (58) != settled (50)
        assertThrows(IllegalArgumentException::class.java) {
            validEvent.copy(payment = validPayment.copy(amountSettled = MoneyAmount("50.000000"), changeReturned = MoneyAmount("50.000000")))
        }

        // 6. Invalid timestamp format
        assertThrows(IllegalArgumentException::class.java) {
            validEvent.copy(capturedAt = "invalid-date-time")
        }

        // 7. Timestamp year out of bounds [2020..2100]
        assertThrows(IllegalArgumentException::class.java) {
            validEvent.copy(capturedAt = "2015-01-01T00:00:00Z")
        }

        // 8. String caps (> 64 characters)
        assertThrows(IllegalArgumentException::class.java) {
            validEvent.copy(catalogSnapshotVersion = "A".repeat(65))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validEvent.copy(localReceiptNumber = "R".repeat(65))
        }
    }

    @Test
    fun testDefensiveCopyPreventsMutableSourceAlias() {
        val validLine = OfflineSaleLine(
            productId = ServerUlid.parse("01JCV8X9M6A1B2C3D4E5F6G7H8"),
            productUnitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
            unitId = ServerUlid.parse("01JCV8X9MBV4W5X6Y7Z8012345"),
            quantity = DecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("25.000000"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxAmount = MoneyAmount.fromNonnegativeString("8.000000"),
            lineTotal = MoneyAmount.fromPositiveString("58.000000")
        )
        val mutableLines = ArrayList<OfflineSaleLine>()
        mutableLines.add(validLine)

        val payment = OfflineSalePayment(
            mode = "full",
            paymentMethod = "cash",
            cashAccountId = ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"),
            amountTendered = MoneyAmount.fromNonnegativeString("100.000000"),
            amountSettled = MoneyAmount.fromPositiveString("58.000000"),
            changeReturned = MoneyAmount.fromNonnegativeString("42.000000")
        )

        val event = OfflineSaleEvent(
            clientEventId = ClientUuid.parse("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"),
            actorId = ServerUlid.parse("01JCV8X9M8T1V2W3X4Y5Z67890"),
            deviceId = ClientUuid.parse("8e2b8bc6-f80c-4034-8c88-59368d1f2e10"),
            deviceLeaseId = ClientUuid.parse("7c1e5a2b-3d4f-5678-90ab-cdef12345678"),
            capturedAt = "2026-11-10T12:00:00Z",
            customerId = ServerUlid.parse("01JCV8X9M4K1P2Q3R4S5T6V7W8"),
            currency = "ILS",
            warehouseId = ServerUlid.parse("01JCV8X9M9A2B3C4D5E6F7G8H9"),
            cashAccountId = ServerUlid.parse("01JCV8X9MAJ3K4M5N6P7Q8R9S0"),
            catalogSnapshotVersion = "snap-20261110-v1",
            localReceiptNumber = "REC-OFF-00042",
            lines = mutableLines,
            payment = payment
        )

        val initialDigest = event.computePayloadDigest()

        // Mutate original list
        mutableLines.clear()

        // Event internal lines must remain intact
        assertEquals("Event lines size must remain 1 after mutating external list", 1, event.lines.size)
        assertEquals("Event payload digest must remain unchanged after external mutation", initialDigest, event.computePayloadDigest())

        // Attempting to modify event.lines directly must throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException::class.java) {
            (event.lines as MutableList<OfflineSaleLine>).clear()
        }
    }
}
