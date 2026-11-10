package com.example.data.mobile.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

        // Envelope client_event_id mismatch caught
        val badClientEventId = validReq.copy(clientEventId = ClientUuid.random())
        assertThrows(IllegalArgumentException::class.java) {
            badClientEventId.validateEnvelopeBindings()
        }

        // Envelope device_id mismatch caught
        val badDeviceId = validReq.copy(deviceId = ClientUuid.random())
        assertThrows(IllegalArgumentException::class.java) {
            badDeviceId.validateEnvelopeBindings()
        }

        // Envelope captured_at mismatch caught
        val badCapturedAt = validReq.copy(capturedAt = "2026-11-10T13:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            badCapturedAt.validateEnvelopeBindings()
        }

        // Tampered payload digest caught
        val tamperedDigest = validReq.copy(
            payloadDigest = PayloadDigest.parse("sha256:0000000000000000000000000000000000000000000000000000000000000000")
        )
        assertThrows(IllegalArgumentException::class.java) {
            tamperedDigest.validateEnvelopeBindings()
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
}
