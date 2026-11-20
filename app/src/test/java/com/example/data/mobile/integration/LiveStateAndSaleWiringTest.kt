package com.example.data.mobile.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.contract.*
import com.example.data.mobile.contract.v2.*
import com.example.data.mobile.outbox.*
import com.example.data.mobile.readclient.*
import com.example.data.mobile.sync.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveStateAndSaleWiringTest {
    private fun profile(): OnlineSaleIntentProfile {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }.first { File(it, "docs/integration/mobile-v1/fixtures").exists() }
        val fixture = StrictJsonParser.parseObject(File(root, "docs/integration/mobile-v1/fixtures/golden-online-sale-intent-profile.json").readText())
        return OnlineSaleIntentProfile.fromContextAndRequest(OnlineSaleContext.fromStrictJson(fixture.require("context").asObject()), fixture.require("request").asObject())
    }

    @Test fun wireProjectionPreservesCanonicalDigestAndRejectsUnknownProfileFields() {
        val p = profile()
        val canonical = CanonicalJsonSerializer.serialize(p)
        val wire = StrictJsonParser.parseObject(p.toWireRequestJson())
        assertEquals(setOf("client_event_id", "device_id", "customer_id", "warehouse_id", "currency", "lines", "payment"), wire.properties.keys)
        val reconstructed = OnlineSaleIntentProfile.fromContextAndRequest(OnlineSaleContext(p.actorId, p.companyId, p.currencyMinorUnits), wire)
        assertEquals(canonical, CanonicalJsonSerializer.serialize(reconstructed))
        val malformed = StrictJsonParser.parseObject(canonical).properties + ("extra" to StrictJsonString("invalid"))
        assertThrows(IntentProfileValidationError::class.java) { OnlineSaleIntentProfile.fromStrictJson(StrictJsonObject(malformed)) }
    }

    @Test fun realOutboxCommitsEncryptedIntentBeforeHttpAndConfirmsMatchingServerReceipt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = OutboxDatabase.buildInMemory(context)
        try {
            val repo = testOutboxRepository(db)
            val p = profile()
            val canonical = CanonicalJsonSerializer.serialize(p).toByteArray()
            val digest = p.computePayloadDigest()
            repo.captureOnlineSaleIntent(p.companyId, p.actorId, p.deviceId, p.currencyMinorUnits, p, canonical)
            val stored = db.outboxDao().allEventsForConversion().single()
            assertNull(stored.deviceLeaseId)
            assertFalse(stored.payloadBytes.contentEquals(canonical))
            val methods = mutableListOf<HttpMethod>()
            val transport = object : ReadClientTransport {
                override fun execute(request: TransportRequest): TransportResponse {
                    methods.add(request.method)
                    assertEquals(digest.value, db.outboxDao().allEventsForConversion().single().payloadDigest)
                    if (request.method == HttpMethod.POST) {
                        assertFalse(StrictJsonParser.parseObject(request.body!!).properties.containsKey("actor_id"))
                        return TransportResponse(201, body = "{}")
                    }
                    return TransportResponse(200, body = """{"company_id":"${p.companyId.value}","client_event_id":"${p.clientEventId.value}","status":"posted","payload_digest":"${digest.value}","official_invoice":{"invoice_id":"01M3WMTQQZE7P30Z3KC93N7YJC","payment_id":"01M3WMTQQZE7P30Z3KC93N7YJB","posted_at":"2026-11-19T00:00:00Z"}}""")
                }
            }
            val result = HttpSaleSyncGateway(transport, "https://example.com") { "test-token" }.submitSale(p.companyId, p.actorId, p.deviceId, p.clientEventId, digest, canonical)
            assertTrue(result is SyncSubmitOutcome.Posted)
            assertEquals(listOf(HttpMethod.POST, HttpMethod.GET), methods)
            assertArrayEquals(canonical, repo.getRecord(p.companyId, p.actorId, p.deviceId, p.clientEventId)!!.event.payloadBytes)
        } finally { db.close() }
    }

    @Test fun invalidProfileNeverFallsBackToRawPost() {
        var calls = 0
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse { calls++; return TransportResponse(201, body = "{}") }
        }
        val p = profile(); val bytes = "{}".toByteArray()
        val result = HttpSaleSyncGateway(transport, "https://example.com") { "test-token" }.submitSale(p.companyId, p.actorId, p.deviceId, p.clientEventId, PayloadDigest.fromBytes(bytes), bytes)
        assertTrue(result is SyncSubmitOutcome.BusinessConflict); assertEquals(0, calls)
    }
}
