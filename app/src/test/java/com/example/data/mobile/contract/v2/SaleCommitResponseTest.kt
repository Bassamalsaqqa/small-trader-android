package com.example.data.mobile.contract.v2

import com.example.data.mobile.contract.CanonicalSerializationException
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.MoneyAmount
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

class SaleCommitResponseTest {

    private fun locateFixturesDir(): File {
        val base = System.getProperty("user.dir") ?: "."
        val root = generateSequence(File(base)) { it.parentFile }
            .firstOrNull { File(it, "docs/integration/mobile-v1/fixtures").exists() }
            ?: error("Could not locate docs/integration/mobile-v1/fixtures from $base")
        return File(root, "docs/integration/mobile-v1/fixtures")
    }

    @Test
    fun testGoldenSaleCommitResponseParity() {
        val fixturesDir = locateFixturesDir()
        val goldenFile = File(fixturesDir, "golden-sale-commit.json")
        assertTrue("Golden sale commit file must exist", goldenFile.exists())

        val rootJson = StrictJsonParser.parse(goldenFile.readText(Charsets.UTF_8)).asObject()
        val responseJson = rootJson.require("response").asObject()

        val commit = SaleCommitResponse.fromStrictJson(responseJson)

        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", commit.clientEventId.value)
        assertEquals("posted", commit.status)
        assertEquals("01JCV8X9ME7890123456789ABC", commit.invoiceId.value)
        assertEquals("INV-2026-00042", commit.invoiceNumber)
        assertEquals("01JCV8X9MFGHJKMNPQRSTVWXYZ", commit.paymentId?.value)
        assertEquals("RCT-2026-00042", commit.paymentReceiptNumber)
        assertEquals("58.000000", commit.grandTotal.lexeme)
        assertEquals("58.000000", commit.amountPaid.lexeme)
        assertEquals("0.000000", commit.changeReturned.lexeme)
        assertEquals("2026-11-10T12:01:05Z", commit.postedAt)
    }

    @Test
    fun testNullablePaymentFieldsAllowed() {
        val json = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "status": "posted",
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "invoice_number": "INV-2026-00042",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": "100.000000",
            "amount_paid": "0.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-10T12:01:05Z"
        }"""

        val obj = StrictJsonParser.parse(json).asObject()
        val commit = SaleCommitResponse.fromStrictJson(obj)

        assertNull(commit.paymentId)
        assertNull(commit.paymentReceiptNumber)
        assertEquals("100.000000", commit.grandTotal.lexeme)
        assertEquals("0.000000", commit.amountPaid.lexeme)
    }

    @Test
    fun testRejectExtraKeys() {
        val jsonWithInternalId = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "status": "posted",
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "invoice_number": "INV-2026-00042",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": "58.000000",
            "amount_paid": "58.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-10T12:01:05Z",
            "sales_invoice_id": 42
        }"""

        val obj = StrictJsonParser.parse(jsonWithInternalId).asObject()
        val ex = assertThrows(CanonicalSerializationException::class.java) {
            SaleCommitResponse.fromStrictJson(obj)
        }
        assertTrue(ex.message!!.contains("Unexpected fields"))
    }

    @Test
    fun testRejectMissingRequiredKeys() {
        val missingInvoiceNumber = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "status": "posted",
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": "58.000000",
            "amount_paid": "58.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-10T12:01:05Z"
        }"""

        val obj = StrictJsonParser.parse(missingInvoiceNumber).asObject()
        val ex = assertThrows(CanonicalSerializationException::class.java) {
            SaleCommitResponse.fromStrictJson(obj)
        }
        assertTrue(ex.message!!.contains("Missing required keys"))
    }

    @Test
    fun testRejectInvalidStatusEnum() {
        val json = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "status": "completed",
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "invoice_number": "INV-2026-00042",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": "58.000000",
            "amount_paid": "58.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-10T12:01:05Z"
        }"""

        val obj = StrictJsonParser.parse(json).asObject()
        assertThrows(CanonicalSerializationException::class.java) {
            SaleCommitResponse.fromStrictJson(obj)
        }
    }

    @Test
    fun testRejectInvalidDecimalLexeme() {
        val invalidGrandTotal = """{
            "client_event_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "status": "posted",
            "invoice_id": "01JCV8X9ME7890123456789ABC",
            "invoice_number": "INV-2026-00042",
            "payment_id": null,
            "payment_receipt_number": null,
            "grand_total": "0.000000",
            "amount_paid": "0.000000",
            "change_returned": "0.000000",
            "posted_at": "2026-11-10T12:01:05Z"
        }"""

        val obj = StrictJsonParser.parse(invalidGrandTotal).asObject()
        assertThrows(CanonicalSerializationException::class.java) {
            SaleCommitResponse.fromStrictJson(obj)
        }
    }
}
