package com.example.data.mobile.sync

import com.example.data.mobile.contract.*
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
    private fun body(payment: String = "\"01M3WMTQQZE7P30Z3KC93N7YJB\"", hash: String = digest.value): String =
        """{"company_id":"${company.value}","client_event_id":"${event.value}","status":"posted","payload_digest":"$hash","official_invoice":{"invoice_id":"01M3WMTQQZE7P30Z3KC93N7YJC","payment_id":$payment,"posted_at":"2026-11-19T00:00:00Z"}}"""
    private fun gateway(body: String) = HttpSaleSyncGateway(object : ReadClientTransport {
        override fun execute(request: TransportRequest) = TransportResponse(200, body = body)
    }, "https://example.com") { "token" }

    @Test fun acceptsOnlyMatchingPersistedReceipt() {
        assertTrue(gateway(body()).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.Posted)
    }
    @Test fun missingPaymentNeverBecomesInvoiceId() {
        assertTrue(gateway(body(payment = "null")).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.TransientUnknown)
    }
    @Test fun mismatchedDigestNeverConfirms() {
        assertTrue(gateway(body(hash = "sha256:" + "0".repeat(64))).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.TransientUnknown)
    }
    @Test fun missingDigestNeverConfirms() {
        assertTrue(gateway(body().replace("\"payload_digest\":\"${digest.value}\",", "")).checkStatus(company, actor, device, event, digest) is SyncStatusOutcome.TransientUnknown)
    }
    @Test fun commitIsConfirmedUsingScopedGet() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir")!!)) { it.parentFile }.first { java.io.File(it, "docs/integration/mobile-v1/fixtures").exists() }
        val fixture = StrictJsonParser.parseObject(java.io.File(root, "docs/integration/mobile-v1/fixtures/golden-online-sale-intent-profile.json").readText())
        val profile = com.example.data.mobile.contract.v2.OnlineSaleIntentProfile.fromContextAndRequest(com.example.data.mobile.contract.v2.OnlineSaleContext.fromStrictJson(fixture.require("context").asObject()), fixture.require("request").asObject())
        val bytes = CanonicalJsonSerializer.serialize(profile).toByteArray()
        val digest = profile.computePayloadDigest()
        val serverBody = body().replace(company.value, profile.companyId.value).replace(event.value, profile.clientEventId.value).replace(this.digest.value, digest.value)
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
