package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SaleEventStatusResponseTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenSaleEventStatusParity() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-sale-event-status.json")
        assertTrue("Golden sale event status file must exist", goldenFile.exists())

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val status = SaleEventStatusResponse.fromStrictJson(rootJson)

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", status.clientEventId.value)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S6789A", status.companyId.value)
        assertEquals("posted", status.status)
        assertEquals("sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9", status.payloadDigest?.value)
        assertNull(status.conflict)
        assertEquals("2026-11-10T12:05:00Z", status.queriedAt)

        val invoice = status.officialInvoice
        assertNotNull(invoice)
        assertEquals("01JCV8X9ME7890123456789ABC", invoice!!.invoiceId.value)
        assertEquals("INV-2026-00042", invoice.invoiceNumber)
        assertEquals("01JCV8X9MFGHJKMNPQRSTVWXYZ", invoice.paymentId?.value)
        assertEquals("RCT-2026-00042", invoice.paymentReceiptNumber)
        assertEquals("58.000000", invoice.grandTotal.lexeme)
        assertEquals("2026-11-10T12:02:10Z", invoice.postedAt)
    }

    @Test
    fun testUnknownStatusPayload() {
        val json = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "status": "unknown",
            "payload_digest": null,
            "official_invoice": null,
            "conflict": null,
            "queried_at": "2026-11-10T12:05:00Z"
        }"""

        val obj = StrictJsonParser.parse(json).asObject()
        val status = SaleEventStatusResponse.fromStrictJson(obj)

        assertEquals("unknown", status.status)
        assertNull(status.payloadDigest)
        assertNull(status.officialInvoice)
        assertNull(status.conflict)
    }

    @Test
    fun testParkedConflictStatusPayload() {
        val json = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "status": "parked_conflict",
            "payload_digest": "sha256:d442ce49a7609a9a42bb5165990d481d1c7c563cb8030403487099a9919728d9",
            "official_invoice": null,
            "conflict": {
                "conflict_code": "STOCK_EXHAUSTED",
                "conflict_reason": "Available stock is insufficient for requested quantity",
                "parked_at": "2026-11-10T12:03:00Z",
                "resolution_status": "pending_manager_review"
            },
            "queried_at": "2026-11-10T12:05:00Z"
        }"""

        val obj = StrictJsonParser.parse(json).asObject()
        val status = SaleEventStatusResponse.fromStrictJson(obj)

        assertEquals("parked_conflict", status.status)
        assertNotNull(status.conflict)
        assertEquals("STOCK_EXHAUSTED", status.conflict!!.conflictCode)
        assertEquals("Available stock is insufficient for requested quantity", status.conflict!!.conflictReason)
        assertEquals("pending_manager_review", status.conflict!!.resolutionStatus)
    }

    @Test
    fun testRejectExtraKeys() {
        val jsonWithExtra = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "status": "unknown",
            "payload_digest": null,
            "official_invoice": null,
            "conflict": null,
            "queried_at": "2026-11-10T12:05:00Z",
            "unknown_flag": true
        }"""

        val obj = StrictJsonParser.parse(jsonWithExtra).asObject()
        assertThrows(CanonicalSerializationException::class.java) {
            SaleEventStatusResponse.fromStrictJson(obj)
        }
    }

    @Test
    fun testRejectInvalidStatusEnum() {
        val invalidStatusJson = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "company_id": "01JCV8X9M2NQW3TYZ4R5S6789A",
            "status": "pending",
            "payload_digest": null,
            "official_invoice": null,
            "conflict": null,
            "queried_at": "2026-11-10T12:05:00Z"
        }"""

        val obj = StrictJsonParser.parse(invalidStatusJson).asObject()
        assertThrows(CanonicalSerializationException::class.java) {
            SaleEventStatusResponse.fromStrictJson(obj)
        }
    }
}
