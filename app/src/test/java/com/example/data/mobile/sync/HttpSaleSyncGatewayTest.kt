package com.example.data.mobile.sync

import com.example.data.mobile.contract.*
import com.example.data.mobile.contract.v2.OnlineSaleIntentLine
import com.example.data.mobile.contract.v2.OnlineSaleIntentPayment
import com.example.data.mobile.contract.v2.OnlineSaleIntentProfile
import com.example.data.mobile.readclient.*
import org.junit.Assert.*
import org.junit.Test

class HttpSaleSyncGatewayTest {
    private val company = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJ9")
    private val actor = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJA")
    private val event = ClientUuid.parse("00000000-0000-4000-8000-000000000001")
    private val device = ClientUuid.parse("00000000-0000-4000-8000-000000000002")
    private val bytes = "{}".toByteArray()
    private val digest = PayloadDigest.fromBytes(bytes)

    private fun body(
        payment: String = "\"01M3WMTQQZE7P30Z3KC93N7YJB\"",
        hash: String = digest.value,
        invoiceId: String = "01M3WMTQQZE7P30Z3KC93N7YJC",
        invoiceNumber: String? = "\"INV-2026-001\"",
        paymentReceiptNumber: String? = if (payment == "null") "null" else "\"RCT-2026-001\"",
        grandTotal: String? = "\"58.000000\""
    ): String {
        val invNumPart = if (invoiceNumber == null) "" else ""","invoice_number":$invoiceNumber"""
        val rctNumPart = if (paymentReceiptNumber == null) "" else ""","payment_receipt_number":$paymentReceiptNumber"""
        val totalPart = if (grandTotal == null) "" else ""","grand_total":$grandTotal"""
        return """{"company_id":"${company.value}","client_event_id":"${event.value}","status":"posted","payload_digest":"$hash","official_invoice":{"invoice_id":"$invoiceId","payment_id":$payment$invNumPart$rctNumPart$totalPart,"posted_at":"2026-11-19T00:00:00Z"}}"""
    }

    private fun gateway(body: String) = HttpSaleSyncGateway(object : ReadClientTransport {
        override fun execute(request: TransportRequest) = TransportResponse(200, body = body)
    }, "https://example.com") { "token" }

    private fun createProfile(
        settlementMode: String,
        amount: String
    ): OnlineSaleIntentProfile {
        val line = OnlineSaleIntentLine(
            productId = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJD"),
            productUnitId = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJE"),
            conversionToBase = PositiveDecimalQuantity.fromPositiveString("1.000000"),
            quantity = PositiveDecimalQuantity.fromPositiveString("2.000000"),
            unitPrice = MoneyAmount.fromNonnegativeString("25.000000"),
            taxRateId = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJF"),
            taxRate = TaxPercentage.fromString("16.000000"),
            taxInclusive = false
        )
        val payment = OnlineSaleIntentPayment(
            settlementMode = settlementMode,
            paymentMethod = if (settlementMode == "unpaid") null else "cash",
            cashAccountId = if (settlementMode == "unpaid") null else ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJG"),
            amount = MoneyAmount.fromNonnegativeString(amount)
        )
        return OnlineSaleIntentProfile(
            actorId = actor,
            clientEventId = event,
            companyId = company,
            currency = "ILS",
            currencyMinorUnits = 2,
            customerId = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJH"),
            deviceId = device,
            lines = listOf(line),
            payment = payment,
            warehouseId = ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJJ")
        )
    }

    @Test
    fun acceptsOnlyMatchingPersistedReceipt() {
        assertTrue(gateway(body()).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.Posted)
    }

    @Test
    fun missingPaymentNeverBecomesInvoiceId() {
        // Without expectedIntent, explicit null payment is rejected (only admitted for unpaid profile)
        assertTrue(gateway(body(payment = "null")).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun mismatchedDigestNeverConfirms() {
        assertTrue(gateway(body(hash = "sha256:" + "0".repeat(64))).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun missingDigestNeverConfirms() {
        assertTrue(gateway(body().replace("\"payload_digest\":\"${digest.value}\",", "")).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun unpaidAdmittedProfileAcceptsExplicitNullPayment() {
        val unpaidProfile = createProfile("unpaid", "0.000000")
        val unpaidDigest = unpaidProfile.computePayloadDigest()
        val serverBody = body(payment = "null", hash = unpaidDigest.value, paymentReceiptNumber = null)
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, unpaidDigest, expectedIntent = unpaidProfile)
        assertTrue(outcome is SyncStatusOutcome.Posted)
        val ack = (outcome as SyncStatusOutcome.Posted).ack
        assertNull(ack.paymentUlid)
        assertNull(ack.paymentReceiptNumber)
        assertEquals(ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJC"), ack.invoiceUlid)
    }

    @Test
    fun unpaidProfileRejectsNonNullPayment() {
        val unpaidProfile = createProfile("unpaid", "0.000000")
        val unpaidDigest = unpaidProfile.computePayloadDigest()
        // Server returns unexpected payment ULID for unpaid profile
        val serverBody = body(payment = "\"01M3WMTQQZE7P30Z3KC93N7YJB\"", hash = unpaidDigest.value)
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, unpaidDigest, expectedIntent = unpaidProfile)
        assertTrue(outcome is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun forgedNullPaymentRejectedForFullProfile() {
        val fullProfile = createProfile("full", "58.000000")
        val fullDigest = fullProfile.computePayloadDigest()
        // Server or forged wire returns null payment for full profile -> MUST be rejected
        val serverBody = body(payment = "null", hash = fullDigest.value)
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, fullDigest, expectedIntent = fullProfile)
        assertTrue(outcome is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun forgedNullPaymentRejectedForPartialProfile() {
        val partialProfile = createProfile("partial", "20.000000")
        val partialDigest = partialProfile.computePayloadDigest()
        // Server or forged wire returns null payment for partial profile -> MUST be rejected
        val serverBody = body(payment = "null", hash = partialDigest.value)
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, partialDigest, expectedIntent = partialProfile)
        assertTrue(outcome is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun paymentFallbackToInvoiceIdRejected() {
        val fullProfile = createProfile("full", "58.000000")
        val fullDigest = fullProfile.computePayloadDigest()
        val invoiceId = "01M3WMTQQZE7P30Z3KC93N7YJC"
        // Wire payment_id matches invoice_id -> fallback prohibited!
        val serverBody = body(payment = "\"$invoiceId\"", hash = fullDigest.value, invoiceId = invoiceId)
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, fullDigest, expectedIntent = fullProfile)
        assertTrue(outcome is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun partialProfileAcceptsDistinctPaymentAndValidatesAmount() {
        val partialProfile = createProfile("partial", "20.000000")
        val partialDigest = partialProfile.computePayloadDigest()
        val serverBody = body(
            payment = "\"01M3WMTQQZE7P30Z3KC93N7YJB\"",
            hash = partialDigest.value,
            grandTotal = "\"58.000000\""
        )
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, partialDigest, expectedIntent = partialProfile)
        assertTrue(outcome is SyncStatusOutcome.Posted)
        val ack = (outcome as SyncStatusOutcome.Posted).ack
        assertEquals(ServerUlid.parse("01M3WMTQQZE7P30Z3KC93N7YJB"), ack.paymentUlid)
        assertNotEquals(ack.invoiceUlid, ack.paymentUlid)
    }

    @Test
    fun partialProfileRejectsViolatedAmountSemantics() {
        val partialProfile = createProfile("partial", "58.000000") // illegal for partial (not strictly less than total)
        val partialDigest = partialProfile.computePayloadDigest()
        val serverBody = body(
            payment = "\"01M3WMTQQZE7P30Z3KC93N7YJB\"",
            hash = partialDigest.value,
            grandTotal = "\"58.000000\""
        )
        val outcome = gateway(serverBody).checkStatus(company, actor, device, event, partialDigest, expectedIntent = partialProfile)
        assertTrue(outcome is SyncStatusOutcome.TransientUnknown)
    }

    @Test
    fun commitIsConfirmedUsingScopedGet() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }.first { java.io.File(it, "docs/integration/mobile-v1/fixtures").exists() }
        val fixture = StrictJsonParser.parseObject(java.io.File(root, "docs/integration/mobile-v1/fixtures/golden-online-sale-intent-profile.json").readText())
        val profile = com.example.data.mobile.contract.v2.OnlineSaleIntentProfile.fromContextAndRequest(com.example.data.mobile.contract.v2.OnlineSaleContext.fromStrictJson(fixture.require("context").asObject()), fixture.require("request").asObject())
        val bytes = CanonicalJsonSerializer.serialize(profile).toByteArray()
        val digest = profile.computePayloadDigest()
        val serverBody = body(grandTotal = "\"${profile.payment.amount.value.toPlainString()}\"").replace(company.value, profile.companyId.value).replace(event.value, profile.clientEventId.value).replace(this.digest.value, digest.value)
        val calls = mutableListOf<TransportRequest>()
        val gateway = HttpSaleSyncGateway(object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                calls.add(request)
                return if (request.method == HttpMethod.POST) TransportResponse(201, body = "{}") else TransportResponse(200, body = serverBody)
            }
        }, "https://example.com") { "token" }
        assertTrue(gateway.submitSale(profile.companyId, profile.actorId, profile.deviceId, profile.clientEventId, digest, bytes) is SyncSubmitOutcome.Posted)
        assertEquals(listOf(HttpMethod.POST, HttpMethod.GET), calls.map { it.method })
    }
}
