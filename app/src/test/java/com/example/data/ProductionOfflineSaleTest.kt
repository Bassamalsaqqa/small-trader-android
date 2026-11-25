package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.contract.v2.OfflineSaleEventV2
import com.example.data.mobile.contract.v2.OfflineSaleLease
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.offline.authorizesCapture
import com.example.data.mobile.offline.SqliteOfflineLeaseStore
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.OutboxPayloadCipher
import com.example.data.mobile.outbox.OutboxRepositoryImpl
import com.example.data.mobile.outbox.OutboxStatus
import com.example.data.mobile.readclient.BootstrapTax
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.data.mobile.sync.HttpSaleSyncGateway
import com.example.model.BasketItem
import com.example.model.InvoiceRecord
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionOfflineSaleTest {
    private val companyA = id('A')
    private val companyB = id('B')
    private val actor = id('C')
    private val invoiceUlid = id('D')
    private val paymentUlid = id('E')
    private val product = id('F')
    private val unit = id('G')
    private val productUnit = id('H')
    private val inclusiveTax = BootstrapTax(id('J'), "IN", "Included tax", TaxPercentage("16.000000"), "inclusive", false)
    private val device = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val leaseId = ClientUuid.parse("11111111-2222-4333-8444-555555555555")
    private val token = "1|sanctum_local_fixture_offline_sale_token"
    private val authA = CurrentAuthScope(companyA, actor, device, token, 1L)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val crypto = JvmAesGcmCryptoEngine()

    // Base time: 2026-11-25T10:00:00Z (within lease 08:00 to 16:00)
    private val fixedClockMillis = 1795600800000L

    @Test
    fun test1_airplaneEquivalentNetworkOffCapture() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }
            val vm = TraderViewModel(repo)

            try {
                await(vm) { !it.isPaymentUnverifiedHold }
                // 1. Initial online catalog refresh
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                assertEquals(1, server.requestCount)
                take(server) // consume catalog request

                // 2. Online explicit server lease acquisition
                server.enqueue(json(leaseResponse()))
                repo.isOfflineSalesCapabilityEnabled = true
                val lease = runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                assertNotNull(lease)
                assertEquals(2, server.requestCount)
                val leaseReq = take(server)
                assertEquals("POST", leaseReq.method)
                assertEquals("/api/mobile/v2/companies/${companyA.value}/offline-sale-lease", leaseReq.path)
                assertEquals("""{"catalog_snapshot_version":"1"}""", leaseReq.body.readUtf8())

                // 3. Airplane mode: disconnect network
                repo.isNetworkOffline = true
                vm.setNetworkOffline(true)
                vm.refreshOfflineLeaseState()

                // 4. Perform cash sale in airplane mode
                val item = repo.getProducts().single()
                vm.addProductToBasket(item, 1)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("20.00")
                vm.completeCashSale()

                val state = await(vm) { it.activeReceipt != null && !it.isSubmittingSale }
                val receipt = state.activeReceipt!!

                // Assert zero additional network calls made in airplane mode!
                assertEquals(2, server.requestCount)

                // Assert offline local pending receipt semantics
                assertEquals("LOCAL-${leaseId.value}-1", receipt.localReceiptNumber)
                assertNull(receipt.serverInvoiceNumber)
                assertNull(receipt.serverInvoiceUlid)
                assertEquals("pending", receipt.outboxStatus)
                assertTrue(receipt.hasLocalTenderDetails)
                assertFalse(state.isPaymentUnverifiedHold) // Does NOT freeze POS
                assertTrue(state.basket.isEmpty())

                // Assert outbox durability
                assertEquals(1, repo.getPendingOfflineSalesCount())
                assertEquals(BigDecimal("10.00"), repo.getPendingOfflineSalesTotal())
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test2_severalQueuedOfflineSales() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }
            val vm = TraderViewModel(repo)

            try {
                await(vm) { !it.isPaymentUnverifiedHold }
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)

                server.enqueue(json(leaseResponse()))
                repo.isOfflineSalesCapabilityEnabled = true
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                repo.isNetworkOffline = true
                vm.setNetworkOffline(true)

                val item = repo.getProducts().single()

                // Sale 1
                vm.addProductToBasket(item, 1)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("10.00")
                vm.completeCashSale()
                val receipt1 = await(vm) { it.activeReceipt?.localReceiptNumber == "LOCAL-${leaseId.value}-1" }.activeReceipt!!
                assertEquals("LOCAL-${leaseId.value}-1", receipt1.localReceiptNumber)

                // Sale 2
                vm.startFreshSale()
                vm.addProductToBasket(item, 1)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("15.00")
                vm.completeCashSale()
                val receipt2 = await(vm) { it.activeReceipt?.localReceiptNumber == "LOCAL-${leaseId.value}-2" }.activeReceipt!!
                assertEquals("LOCAL-${leaseId.value}-2", receipt2.localReceiptNumber)

                // Sale 3
                vm.startFreshSale()
                vm.addProductToBasket(item, 1)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("20.00")
                vm.completeCashSale()
                val receipt3 = await(vm) { it.activeReceipt?.localReceiptNumber == "LOCAL-${leaseId.value}-3" }.activeReceipt!!
                assertEquals("LOCAL-${leaseId.value}-3", receipt3.localReceiptNumber)

                // Assert zero network requests for the 3 offline sales
                assertEquals(2, server.requestCount)

                // Assert exactly 3 queued sales in outbox
                assertEquals(3, repo.getPendingOfflineSalesCount())
                assertEquals(BigDecimal("30.00"), repo.getPendingOfflineSalesTotal())
                assertEquals(3, db.outboxDao().scopedEventCount(companyA.value, actor.value, device.value))
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test3_restoreAndProcessKillSurvival() = withFixture { server, file ->
        // Step 1: Capture 2 sales in process 1
        withDatabase(file) { db1 ->
            val outbox1 = outbox(db1)
            val leaseStore1 = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db1.openHelper.writableDatabase }
            val repo1 = repository(server, outbox1, leaseStore = leaseStore1)
            repo1.clock = { fixedClockMillis }

            server.enqueue(json(catalog(null, "10.00")))
            runBlocking { repo1.refreshProducts().getOrThrow() }
            take(server)

            server.enqueue(json(leaseResponse()))
            repo1.isOfflineSalesCapabilityEnabled = true
            runBlocking { repo1.acquireOfflineSaleLease().getOrThrow() }
            take(server)

            repo1.isNetworkOffline = true
            val item = repo1.getProducts().single()
            val basketItem = testBasketItem(item)

            val inv1 = InvoiceRecord(
                id = "dummy1", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
            )
            runBlocking { repo1.recordSale(inv1).getOrThrow() }

            val inv2 = InvoiceRecord(
                id = "dummy2", invoiceNumber = 2, timestamp = "2026-11-25T10:01:00Z",
                items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("15.00"), changeReturnedExact = BigDecimal("5.00"),
                customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
            )
            runBlocking { repo1.recordSale(inv2).getOrThrow() }

            assertEquals(2, repo1.getPendingOfflineSalesCount())
            assertEquals(BigDecimal("20.00"), repo1.getPendingOfflineSalesTotal())
            repo1.close()
        } // db1 closed (simulating device process termination)

        // Step 2: Open fresh database and fresh repository instance from the same disk file
        withDatabase(file) { db2 ->
            val outbox2 = outbox(db2)
            val leaseStore2 = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db2.openHelper.writableDatabase }
            val repo2 = repository(server, outbox2, leaseStore = leaseStore2)
            repo2.clock = { fixedClockMillis }
            repo2.isOfflineSalesCapabilityEnabled = true

            try {
                // Lease survived process restart
                val activeLease = repo2.getActiveOfflineSaleLease()
                assertNotNull(activeLease)
                assertEquals(leaseId, activeLease!!.leaseId)

                // Budget and pending sales survived process restart
                assertEquals(2, repo2.getPendingOfflineSalesCount())
                assertEquals(BigDecimal("20.00"), repo2.getPendingOfflineSalesTotal())

                val pending = repo2.getPendingOfflineSales()
                assertEquals(2, pending.size)
                assertEquals("LOCAL-${leaseId.value}-1", pending[0].localReceiptNumber)
                assertEquals("LOCAL-${leaseId.value}-2", pending[1].localReceiptNumber)
            } finally {
                repo2.close()
            }
        }
    }

    @Test
    fun test4_statusLostAckRecovery() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }

            try {
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)

                server.enqueue(json(leaseResponse()))
                repo.isOfflineSalesCapabilityEnabled = true
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                // Capture offline
                repo.isNetworkOffline = true
                val item = repo.getProducts().single()
                val basketItem = testBasketItem(item)
                val inv = InvoiceRecord(
                    id = "dummy1", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                val captured = runBlocking { repo.recordSale(inv).getOrThrow() }
                val eventId = captured.clientEventId!!
                val recordBeforeSync = outbox.getRecord(companyA, actor, device, ClientUuid.parse(eventId))!!
                val digest = recordBeforeSync.payloadDigest

                // Network restored: sync begins
                repo.isNetworkOffline = false

                // 1. First sync checkStatus (NotFound) -> submitSale POST /offline-events.
                // Server status returns 404/NotFound on initial check
                server.enqueue(MockResponse().setResponseCode(404))
                // Server receives POST /offline-events and drops connection (lost ACK)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

                val syncResult1 = runBlocking { repo.syncOfflineSales() }.getOrThrow()
                assertTrue(syncResult1.timeoutCount > 0)

                // Assert record transitioned to unknown_ack (NEVER confirmed on timeout!)
                val recordAfterTimeout = outbox.getRecord(companyA, actor, device, ClientUuid.parse(eventId))!!
                assertEquals(OutboxStatus.UNKNOWN_ACK, recordAfterTimeout.status)

                // 2. Second sync attempt: checkStatus GET /offline-events/{eventId}/status finds POSTED on server!
                server.enqueue(json(status(eventId, digest)))
                val syncResult2 = runBlocking { repo.syncOfflineSales() }.getOrThrow()
                assertEquals(1, syncResult2.confirmedCount)

                // Assert CAS confirmed delivery!
                val recordConfirmed = outbox.getRecord(companyA, actor, device, ClientUuid.parse(eventId))!!
                assertEquals(OutboxStatus.CONFIRMED, recordConfirmed.status)
                assertEquals(invoiceUlid.value, recordConfirmed.deliveryState.invoiceUlid)
                assertEquals(paymentUlid.value, recordConfirmed.deliveryState.paymentUlid)
                assertEquals(0, repo.getPendingOfflineSalesCount())
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test5_leaseExpiryFailClosedClockUncertainty() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }

            try {
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)

                // Lease valid 08:00 to 16:00 (1795593600000L to 1795622400000L)
                server.enqueue(json(leaseResponse(
                    issuedAt = "2026-11-25T08:00:00Z",
                    expiresAt = "2026-11-25T16:00:00Z"
                )))
                repo.isOfflineSalesCapabilityEnabled = true
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                repo.isNetworkOffline = true
                val item = repo.getProducts().single()
                val basketItem = testBasketItem(item)
                val inv = InvoiceRecord(
                    id = "dummy", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )

                // Wall time cannot expire or extend the authenticated monotonic anchor.
                repo.clock = { 1795626000000L }
                assertNotNull(repo.getActiveOfflineSaleLease())
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(9))
                assertNull(repo.getActiveOfflineSaleLease())
                val resultExpired = runBlocking { repo.recordSale(inv) }
                assertTrue(resultExpired.isFailure)
                assertTrue(resultExpired.exceptionOrNull()!!.message!!.contains("No active valid offline sale lease"))

                // 2. Clock moved backwards before issuedAt (clock uncertainty / backwards skew, e.g. 07:00 UTC)
                val backwardsMillis = fixedClockMillis // Returning inside the original validity window must not revive expiry
                repo.clock = { backwardsMillis }
                assertNull(repo.getActiveOfflineSaleLease())
                val resultSkew = runBlocking { repo.recordSale(inv) }
                assertTrue(resultSkew.isFailure)
                assertTrue(resultSkew.exceptionOrNull()!!.message!!.contains("No active valid offline sale lease"))
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test6_leaseRevocationConflict() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }

            try {
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)

                server.enqueue(json(leaseResponse()))
                repo.isOfflineSalesCapabilityEnabled = true
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                repo.isNetworkOffline = true
                val item = repo.getProducts().single()
                val basketItem = testBasketItem(item)
                val inv = InvoiceRecord(
                    id = "dummy", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                val captured = runBlocking { repo.recordSale(inv).getOrThrow() }
                val eventId = ClientUuid.parse(captured.clientEventId!!)

                // Restore network and sync
                repo.isNetworkOffline = false
                server.enqueue(MockResponse().setResponseCode(404)) // status check not found
                // Server returns 409 LEASE_POLICY_CHANGED
                server.enqueue(MockResponse().setResponseCode(409).setBody("""
                    {
                      "error": {
                        "code": "CONFLICT",
                        "message": "The offline lease policy changed on the server.",
                        "conflict_evidence": {
                          "reason": "LEASE_POLICY_CHANGED",
                          "resolution": "Owner re-auth required"
                        }
                      }
                    }
                """.trimIndent()))

                val syncResult = runBlocking { repo.syncOfflineSales() }.getOrThrow()
                assertEquals(1, syncResult.conflictCount)

                // Record moves to CONFLICT
                val record = outbox.getRecord(companyA, actor, device, eventId)!!
                assertEquals(OutboxStatus.CONFLICT, record.status)

                // Rejected cash is preserved in UI query for owner supervision
                val rejected = repo.getRejectedCashSales()
                assertEquals(1, rejected.size)
                assertEquals("LOCAL-${leaseId.value}-1", rejected[0].localReceiptNumber)
                assertEquals(BigDecimal("10.00"), rejected[0].netTotalExact)
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test7_stockConflict() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }

            try {
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)

                server.enqueue(json(leaseResponse()))
                repo.isOfflineSalesCapabilityEnabled = true
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                repo.isNetworkOffline = true
                val item = repo.getProducts().single()
                val basketItem = testBasketItem(item)
                val inv = InvoiceRecord(
                    id = "dummy", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                val captured = runBlocking { repo.recordSale(inv).getOrThrow() }
                val eventId = ClientUuid.parse(captured.clientEventId!!)

                repo.isNetworkOffline = false
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(409).setBody("""
                    {
                      "error": {
                        "code": "CONFLICT",
                        "message": "Current stock cannot fulfill the saved sale.",
                        "conflict_evidence": {
                          "reason": "STOCK_UNAVAILABLE",
                          "resolution": "Stock depleted by another writer"
                        }
                      }
                    }
                """.trimIndent()))

                val syncResult = runBlocking { repo.syncOfflineSales() }.getOrThrow()
                assertEquals(1, syncResult.conflictCount)

                val record = outbox.getRecord(companyA, actor, device, eventId)!!
                assertEquals(OutboxStatus.CONFLICT, record.status)
                assertEquals(1, repo.getRejectedCashSales().size)
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test8_budgetLimitsEnforcedFailClosed() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox, leaseStore = leaseStore)
            repo.clock = { fixedClockMillis }

            try {
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)

                // Lease configured with max count = 2, max amount = 25.00
                server.enqueue(json(leaseResponse(maxCount = 2, maxAmount = "25.000000")))
                repo.isOfflineSalesCapabilityEnabled = true
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                repo.isNetworkOffline = true
                val item = repo.getProducts().single()
                val basketItem = testBasketItem(item)

                // Sale 1: 10.00 (Count = 1, Total = 10.00) -> SUCCESS
                val inv1 = InvoiceRecord(
                    id = "d1", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                val first = runBlocking { repo.recordSale(inv1).getOrThrow() }

                // Sale 2: 10.00 (Count = 2, Total = 20.00) -> SUCCESS
                val inv2 = InvoiceRecord(
                    id = "d2", invoiceNumber = 2, timestamp = "2026-11-25T10:01:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                val firstRecord = outbox.getRecord(companyA, actor, device, ClientUuid.parse(first.clientEventId!!))!!
                val template = OfflineSaleEventV2.fromStrictJson(StrictJsonParser.parseObject(String(firstRecord.payloadBytes, Charsets.UTF_8)))
                val activeLease = leaseStore.getLease(companyA, actor, device)!!
                assertTrue(runCatching {
                    outbox.captureSaleV2(companyA, actor, device, 2, template.copy(clientEventId = ClientUuid.random()))
                }.isFailure)
                assertTrue(runCatching { outbox.captureLeaseSale(activeLease,
                    template.copy(clientEventId = ClientUuid.random(), warehouseId = id('K'))) {} }.isFailure)
                assertTrue(runCatching { outbox.captureLeaseSale(activeLease,
                    template.copy(clientEventId = ClientUuid.random(), lines = listOf(template.lines.single().copy(
                        taxAmount = com.example.data.mobile.contract.MoneyAmount("1.000000"))))) {} }.isFailure)
                val replay = outbox.captureLeaseSale(activeLease, template.copy(capturedAt = "2026-11-25T08:00:02Z")) {}
                assertTrue(replay is com.example.data.mobile.outbox.CaptureResult.Duplicate)
                var scopeChecks = 0
                assertTrue(runCatching {
                    outbox.captureLeaseSale(activeLease, template.copy(clientEventId = ClientUuid.random())) {
                        scopeChecks++
                        if (scopeChecks > 1) throw SecurityException("Simulated in-flight logout/company switch")
                    }
                }.isFailure)
                assertEquals(1, outbox.getPendingOfflineSalesBudget(companyA, actor, device, leaseId).first)
                withDatabase(file) { secondDb ->
                    val secondOutbox = outbox(secondDb)
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
                    try {
                        val tasks = listOf(outbox, secondOutbox).map { storage ->
                            executor.submit<Boolean> {
                                runCatching { storage.captureLeaseSale(activeLease, template.copy(clientEventId = ClientUuid.random())) {} }.isSuccess
                            }
                        }
                        assertEquals(1, tasks.count { it.get(10, TimeUnit.SECONDS) })
                    } finally { executor.shutdownNow() }
                }
                // Delivery acknowledgement must never replenish the two consumed slots.
                assertEquals(2, runBlocking { repo.offlineQueueSummary() }.count)
                var payloadDecryptions = 0
                val countingCrypto = object : com.example.data.mobile.session.SessionCryptoEngine by crypto {
                    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
                        payloadDecryptions++
                        return crypto.decrypt(iv, ciphertextWithTag, aad)
                    }
                }
                val queueReader = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(countingCrypto))
                assertEquals(2, queueReader.getOfflineQueueRecords(companyA, actor, device).size)
                assertEquals(2, payloadDecryptions)
                assertEquals(2, queueReader.getOfflineQueueRecords(companyA, actor, device).size)
                assertTrue(queueReader.getOfflineQueueRecords(companyB, actor, device).isEmpty())
                assertEquals(2, payloadDecryptions)
                db.openHelper.writableDatabase.execSQL("UPDATE outbox_delivery_states SET status='confirmed'")
                assertTrue(queueReader.getOfflineQueueRecords(companyA, actor, device).isEmpty())
                assertEquals(2, payloadDecryptions)
                assertEquals(0, runBlocking { repo.offlineQueueSummary() }.count)

                // Sale 3: Count would become 3 > 2 -> FAILS CLOSED
                val inv3 = InvoiceRecord(
                    id = "d3", invoiceNumber = 3, timestamp = "2026-11-25T10:02:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                val resultCountLimit = runBlocking { repo.recordSale(inv3) }
                assertTrue(resultCountLimit.isFailure)
                assertTrue(resultCountLimit.exceptionOrNull()!!.message!!.contains("Exceeded max offline sales count limit"))

                // Verify outbox remains at 2 sales
                assertEquals(0, repo.getPendingOfflineSalesCount())
                val lifetime = outbox.getPendingOfflineSalesBudget(companyA, actor, device, leaseId)
                assertEquals(2, lifetime.first)
                assertEquals(0, BigDecimal("20.00").compareTo(lifetime.second))
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test9_tenantSwitchIsolation() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }

            // Company A setup and offline sale capture
            val repoA = repository(server, outbox, scope = authA, leaseStore = leaseStore)
            repoA.clock = { fixedClockMillis }

            server.enqueue(json(catalog(null, "10.00")))
            runBlocking { repoA.refreshProducts().getOrThrow() }
            take(server)

            server.enqueue(json(leaseResponse()))
            repoA.isOfflineSalesCapabilityEnabled = true
            runBlocking { repoA.acquireOfflineSaleLease().getOrThrow() }
            take(server)

            repoA.isNetworkOffline = true
            val item = repoA.getProducts().single()
            val basketItem = testBasketItem(item)
            val inv = InvoiceRecord(
                id = "dummyA", invoiceNumber = 1, timestamp = "2026-11-25T10:00:00Z",
                items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
            )
            runBlocking { repoA.recordSale(inv).getOrThrow() }
            assertEquals(1, repoA.getPendingOfflineSalesCount())

            // Switch to Company B
            val authB = CurrentAuthScope(companyB, actor, device, "tokenB", 1L)
            val repoB = repository(server, outbox, scope = authB, leaseStore = leaseStore)
            repoB.clock = { fixedClockMillis }
            repoB.isOfflineSalesCapabilityEnabled = true

            try {
                // Company B must NOT see Company A's lease
                assertNull(repoB.getActiveOfflineSaleLease())

                // Company B must NOT see Company A's pending sales
                assertEquals(0, repoB.getPendingOfflineSalesCount())
                assertEquals(BigDecimal.ZERO, repoB.getPendingOfflineSalesTotal())
                assertTrue(repoB.getPendingOfflineSales().isEmpty())
            } finally {
                repoA.close()
                repoB.close()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun repository(
        server: MockWebServer,
        outbox: OutboxRepositoryImpl,
        precision: Int = 2,
        currency: String = "JOD",
        scope: CurrentAuthScope = authA,
        leaseStore: SqliteOfflineLeaseStore
    ): ProductionTraderRepository {
        val origin = "http://127.0.0.1:${server.port}"
        val transport = OkHttpTransport(origin, allowHttpLoopback = true, readTimeoutMs = 1500, callTimeoutMs = 3000)
        return ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(transport, origin) { scope.token },
            companyId = scope.companyId,
            actorId = scope.actorId,
            deviceId = scope.deviceId,
            authScopeProvider = { scope },
            merchantReadClient = MerchantReadClient(transport, origin, allowHttpLoopback = true,
                tokenProvider = MerchantReadTokenProvider { _, _ -> scope.token }),
            baseCurrency = currency,
            currencyMinorUnits = precision,
            outboxRepository = outbox,
            saleSyncGateway = HttpSaleSyncGateway(transport, origin) { scope.token },
            bootstrapTaxes = listOf(inclusiveTax),
            offlineLeaseStore = leaseStore
        )
    }

    @Test
    fun encryptedLeaseRejectsMetadataAndScopeTampering() = withFixture { _, file ->
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val lease = OfflineSaleLease.fromStrictJson(StrictJsonParser.parseObject(leaseResponse()))
            store.saveLease(lease, true)
            assertNotNull(store.getLease(companyA, actor, device))
            db.openHelper.writableDatabase.execSQL("UPDATE offline_sale_leases SET is_catalog_complete=0")
            assertNull(store.getLease(companyA, actor, device))
            assertNull(store.getVerifiedCatalogSnapshotVersion(companyA, actor, device))
            store.saveLease(lease, true)
            db.openHelper.writableDatabase.execSQL("UPDATE offline_sale_leases SET company_id=?", arrayOf(companyB.value))
            assertNull(store.getLease(companyB, actor, device))
            assertNull(store.getLease(companyA, actor, device))
        }
    }

    @Test
    fun durableBootAnchorSurvivesRecreationAndRebootFailsClosed() = withFixture { _, file ->
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        fun currentBoot() = com.example.data.mobile.offline.androidBootIdentity(context)
        assertEquals("7", currentBoot())
        val lease = OfflineSaleLease.fromStrictJson(StrictJsonParser.parseObject(leaseResponse()))
        withDatabase(file) { db ->
            SqliteOfflineLeaseStore(crypto, bootIdentity = { currentBoot() }) { db.openHelper.writableDatabase }
                .saveLease(lease, true)
        }
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { currentBoot() }) { db.openHelper.writableDatabase }
            assertEquals(lease, store.getLease(companyA, actor, device))
            assertNotNull(store.trustedTime(lease))
            // Same-boot online reacquisition keeps the first authenticated budget baseline.
            store.saveLease(lease.copy(postedSalesCount = lease.postedSalesCount + 1), true)
            assertEquals(lease.postedSalesCount, store.getLease(companyA, actor, device)?.postedSalesCount)
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
            assertNull(store.getLease(companyA, actor, device))
            assertTrue(runCatching { store.saveLease(lease, true) }.isFailure)
            android.provider.Settings.Global.putString(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, null)
            assertNull(store.getLease(companyA, actor, device))
        }
    }

    @Test
    fun completeLastPageAndCorruptionSurviveDiskRecreation() = withFixture { server, file ->
        val one = catalog(null)
        val productJson = one.substringAfter("\"products\":[").substringBeforeLast("]}")
        val first = one.substringBefore("\"products\":[") + "\"products\":[" +
            (1..50).joinToString(",") { index -> productJson.replace(product.value,
                product.value.dropLast(4) + index.toString().padStart(4, '0')) } + "]}"
        val pageOne = first.replace("\"has_more\":false,\"next_cursor\":null", "\"has_more\":true,\"next_cursor\":\"last-page\"")
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox(db), leaseStore = store)
            try {
                repo.isOfflineSalesCapabilityEnabled = true
                server.enqueue(json(pageOne))
                server.enqueue(json(one))
                runBlocking { repo.prepareOfflineCatalog(2, 51) {}.getOrThrow() }
                assertEquals(51, repo.getProducts().size)
                take(server)
                assertTrue(take(server).path!!.contains("last-page"))
                server.enqueue(json(one.replace(productJson, "$productJson,$productJson")))
                assertTrue(runBlocking { repo.prepareOfflineCatalog(1, 2) {} }.isFailure)
                take(server)
                assertEquals(2, store.getCatalogPages(companyA, actor, device).size)
                assertEquals(51, repo.getProducts().size)
            } finally { repo.close() }
        }
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            assertTrue(store.isCatalogComplete(companyA, actor, device))
            assertEquals(2, store.getCatalogPages(companyA, actor, device).size)
            val repo = repository(server, outbox(db), leaseStore = store)
            try {
                runBlocking { kotlinx.coroutines.withTimeout(5000) {
                    while (repo.getProducts().size != 51) kotlinx.coroutines.delay(10)
                } }
                assertTrue(repo.getProducts().any { it.id == product.value })
                db.openHelper.writableDatabase.execSQL("UPDATE offline_catalog SET payload=zeroblob(1048605)")
                assertFalse(store.isCatalogComplete(companyA, actor, device))
                assertTrue(store.getCatalogPages(companyA, actor, device).isEmpty())
            } finally { repo.close() }
        }
    }

    @Test
    fun maxOneBudgetRemainsConsumedAfterConfirmationAndSameLeaseReacquisition() = withFixture { server, file ->
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val storage = outbox(db)
            val repo = repository(server, storage, leaseStore = store)
            try {
                repo.isOfflineSalesCapabilityEnabled = true
                server.enqueue(json(catalog(null)))
                runBlocking { repo.prepareOfflineCatalog(1, 1) {}.getOrThrow() }
                take(server)
                server.enqueue(json(leaseResponse(maxCount = 1)))
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)
                repo.isNetworkOffline = true
                val line = testBasketItem(repo.getProducts().single())
                val invoice = InvoiceRecord(id = "one", invoiceNumber = 0, timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(line), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User")
                runBlocking { repo.recordOfflineSale(invoice).getOrThrow() }
                // A server-only acknowledgement cannot be invented locally during acquisition.
                server.enqueue(json(leaseResponse(maxCount = 1, postedCount = 1, postedAmount = "10.000000")))
                assertTrue(runBlocking { repo.acquireOfflineSaleLease() }.isFailure)
                take(server)
                assertEquals(0, store.getLease(companyA, actor, device)!!.postedSalesCount)
                assertEquals(1, storage.getRecordsByScope(companyA, actor, device).size)
                db.openHelper.writableDatabase.execSQL("UPDATE outbox_delivery_states SET status='confirmed'")
                server.enqueue(json(leaseResponse(maxCount = 1, postedCount = 1, postedAmount = "10.000000")))
                runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)
                assertEquals(0, store.getLease(companyA, actor, device)!!.postedSalesCount)
                assertTrue(runBlocking { repo.recordOfflineSale(invoice) }.isFailure)
                val replacement = OfflineSaleLease.fromStrictJson(StrictJsonParser.parseObject(
                    leaseResponse(lId = ClientUuid.random(), maxCount = 1, postedCount = 1, postedAmount = "10.000000")))
                store.saveLease(replacement, true)
                assertTrue(runBlocking { repo.recordOfflineSale(invoice) }.isFailure)
                assertEquals(1, storage.getRecordsByScope(companyA, actor, device).size)
            } finally { repo.close() }
        }
    }

    @Test
    fun fractionalQuantityInclusiveTaxPersistsExactIlsAndJod() {
        for ((currency, precision) in listOf("ILS" to 2, "JOD" to 3)) withFixture { server, file ->
            withDatabase(file) { db ->
                val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
                val storage = outbox(db)
                val repo = repository(server, storage, precision = precision, currency = currency, leaseStore = store)
                try {
                    repo.isOfflineSalesCapabilityEnabled = true
                    server.enqueue(json(catalog(inclusiveTax, "1.16")))
                    runBlocking { repo.prepareOfflineCatalog(1, 1) {}.getOrThrow() }
                    take(server)
                    server.enqueue(json(leaseResponse(currency = currency, precision = precision,
                        taxId = inclusiveTax.id.value, taxRate = "16.000000", taxCalc = "inclusive")))
                    runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                    take(server)
                    val line = BasketItem(product = repo.getProducts().single(), quantity = BigDecimal("0.125"),
                        unitPriceExact = BigDecimal("1.16"), taxRateId = inclusiveTax.id.value,
                        taxRateExact = BigDecimal("16"), isTaxInclusive = true)
                    val total = line.calculateLine(precision).lineTotal.value
                    val invoice = InvoiceRecord(id = "fraction", invoiceNumber = 0, timestamp = "2026-11-25T10:00:00Z",
                        items = listOf(line), subtotalExact = total, discountExact = BigDecimal.ZERO,
                        netTotalExact = total, cashReceivedExact = BigDecimal("0.15"), changeReturnedExact = BigDecimal("0.15").subtract(total),
                        customerNote = "", cashierNameAr = "User", cashierNameEn = "User")
                    assertTrue(runBlocking { repo.recordOfflineSale(invoice.copy(items = listOf(line.copy(taxRateExact = BigDecimal("15"))))) }.isFailure)
                    assertEquals(0, storage.getRecordsByScope(companyA, actor, device).size)
                    runBlocking { repo.recordOfflineSale(invoice).getOrThrow() }
                    val record = storage.getRecordsByScope(companyA, actor, device).single()
                    val event = OfflineSaleEventV2.fromStrictJson(StrictJsonParser.parseObject(String(record.payloadBytes, Charsets.UTF_8)))
                    assertEquals(BigDecimal("0.125000"), event.lines.single().quantity.value)
                    assertEquals(BigDecimal(if (precision == 2) "0.150000" else "0.145000"), event.lines.single().lineTotal.value)
                    assertEquals(BigDecimal("0.020000"), event.lines.single().taxAmount.value)
                } finally { repo.close() }
            }
        }
    }

    @Test
    fun restrictedProductionCompositionRestartsWithoutOnlineAuthAndFailsClosed() = withFixture { server, file ->
        var backendKey = requireNotNull(crypto.secretKey)
        var availableKey: javax.crypto.SecretKey? = backendKey
        var generatedKeys = 0
        val diskCrypto = com.example.data.mobile.session.AndroidKeyStoreCryptoEngine("restricted-composition", backend =
            object : com.example.data.mobile.session.KeyStoreBackend {
                override fun getKey(alias: String) = availableKey
                override fun generateKey(alias: String): javax.crypto.SecretKey { generatedKeys++; return backendKey.also { availableKey = it } }
            })
        val directory = File(context.filesDir, "restricted-${ClientUuid.random().value}").apply { mkdirs() }
        val identity = File(directory, "identity.json").apply { writeText("{\"device_id\":\"${device.value}\"}") }
        val sessionFile = File(directory, "session.bin")
        val bootstrapFile = File(directory, "bootstrap.bin")
        var boot = "same-boot"
        fun sessions() = com.example.data.mobile.session.DefaultSecureStaffSessionStore(sessionFile,
            com.example.data.mobile.session.InstallationIdentityStore(identity), diskCrypto, context.packageName)
        fun bootstrap() = com.example.data.mobile.offline.RestrictedOfflineBootstrapStore(android.util.AtomicFile(bootstrapFile), diskCrypto, sessions())
        val record = com.example.data.mobile.session.StaffSessionRecord(token, actor, device, "epoch", "2026-11-26T00:00:00Z",
            companyA, createdAt = "2026-11-25T08:00:00Z")
        try {
            sessions().saveSession(record)
            withDatabase(file) { db ->
                val leases = SqliteOfflineLeaseStore(diskCrypto, bootIdentity = { boot }) { db.openHelper.writableDatabase }
                val repo = repository(server, outbox(db), leaseStore = leases)
                try {
                    repo.isOfflineSalesCapabilityEnabled = true
                    server.enqueue(json(catalog(null)))
                    runBlocking { repo.prepareOfflineCatalog(1, 1) {}.getOrThrow() }
                    take(server)
                    server.enqueue(json(leaseResponse()))
                    val lease = runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                    take(server)
                    bootstrap().save(repo, lease, leases)
                    assertFalse(String(bootstrapFile.readBytes(), Charsets.UTF_8).contains(token))
                } finally { repo.close() }
            }
            withDatabase(file) { db ->
                val leases = SqliteOfflineLeaseStore(diskCrypto, bootIdentity = { boot }) { db.openHelper.writableDatabase }
                val cache = bootstrap()
                val workspace = runBlocking { com.example.data.mobile.offline.RestrictedOfflineWorkspace.compose(
                    db, leases, cache, "https://account.example.test", true, OutboxPayloadCipher(diskCrypto),
                    com.example.data.mobile.outbox.RoomMerchantCommandStore(db, diskCrypto)) }!!
                try {
                    runBlocking { kotlinx.coroutines.withTimeout(5000) {
                        while (workspace.products.value.isEmpty()) kotlinx.coroutines.delay(10)
                    } }
                    val line = testBasketItem(workspace.products.value.single())
                    val invoice = InvoiceRecord(id = "restricted", invoiceNumber = 0, timestamp = record.createdAt,
                        items = listOf(line), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                        netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                        customerNote = "", cashierNameAr = "", cashierNameEn = "")
                    runBlocking { workspace.capture(invoice).getOrThrow() }
                    assertEquals(1, runBlocking { workspace.pending() }.count)
                    assertEquals(2, server.requestCount)
                    sessions().clearSession()
                    assertTrue(runBlocking { workspace.capture(invoice) }.isFailure)
                    assertNull(cache.read(leases))
                    sessions().saveSession(record.copy(selectedCompanyId = companyB))
                    assertNull(cache.read(leases))
                    sessions().saveSession(record)
                    assertNotNull(cache.read(leases))
                    boot = "different-boot"
                    assertNull(cache.read(leases))
                    boot = "same-boot"
                    val wrongKey = com.example.data.mobile.offline.RestrictedOfflineBootstrapStore(android.util.AtomicFile(bootstrapFile), JvmAesGcmCryptoEngine(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 88.toByte() }, "AES")), sessions())
                    assertNull(wrongKey.read(leases))
                    availableKey = null
                    assertNull(cache.read(leases))
                    assertEquals(0, generatedKeys)
                    availableKey = backendKey
                    assertNotNull(cache.read(leases))
                    bootstrapFile.writeBytes(ByteArray(com.example.data.mobile.contract.ContractConstants.MAX_PAYLOAD_BYTES + 29))
                    assertNull(cache.read(leases))
                    assertEquals(1, db.outboxDao().scopedEventCount(companyA.value, actor.value, device.value))
                    cache.invalidate()
                    assertNull(cache.read(leases))
                } finally { workspace.close() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun productionRestrictedEntryDefaultsOffWithoutOpeningNativeStorage() {
        assertFalse(com.example.BuildConfig.OFFLINE_OWNER_PILOT)
        assertNull(runBlocking { com.example.data.mobile.offline.RestrictedOfflineWorkspace.open(context, "https://account.example.test") })
    }

    @Test
    fun offlineJodSale_survivesRecreateClearedLeaseAndChangedBusinessCurrency() = withFixture { server, file ->
        withDatabase(file) { db1 ->
            val store1 = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db1.openHelper.writableDatabase }
            val outbox1 = outbox(db1)
            val repo1 = repository(server, outbox1, precision = 3, currency = "JOD", leaseStore = store1)
            repo1.clock = { fixedClockMillis }
            try {
                repo1.isOfflineSalesCapabilityEnabled = true
                server.enqueue(json(catalog(null, "9.83")))
                runBlocking { repo1.prepareOfflineCatalog(1, 1) {}.getOrThrow() }
                take(server)

                server.enqueue(json(leaseResponse(currency = "JOD", precision = 3)))
                runBlocking { repo1.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                repo1.isNetworkOffline = true
                val prod = repo1.getProducts().single()
                val line = testBasketItem(prod, quantity = BigDecimal("1.00"), unitPrice = BigDecimal("9.83"))
                val sale = InvoiceRecord(
                    id = "offline-jod-1",
                    invoiceNumber = 0,
                    timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(line),
                    subtotalExact = BigDecimal("9.83"),
                    discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("9.83"),
                    cashReceivedExact = BigDecimal("9.84"),
                    changeReturnedExact = BigDecimal("0.01"),
                    customerNote = "",
                    cashierNameAr = "User",
                    cashierNameEn = "User"
                )
                val captured = runBlocking { repo1.recordSale(sale).getOrThrow() }
                assertEquals("JOD", captured.currency)
                assertEquals(3, captured.currencyMinorUnits)
                assertEquals(0, BigDecimal("0.01").compareTo(captured.changeReturnedExact))
            } finally {
                repo1.close()
            }
        }

        // Reopen database with cleared lease and switched company base currency (USD, precision = 2).
        withDatabase(file) { db2 ->
            val store2 = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db2.openHelper.writableDatabase }
            store2.clearLease(companyA, actor, device)
            val outbox2 = outbox(db2)
            val repo2 = repository(server, outbox2, precision = 2, currency = "USD", leaseStore = store2)
            repo2.clock = { fixedClockMillis }
            try {
                val pending = repo2.getPendingOfflineSales()
                assertEquals(1, pending.size)
                val recovered = pending.single()
                assertEquals("JOD", recovered.currency)
                assertEquals(3, recovered.currencyMinorUnits)
                assertEquals(0, BigDecimal("9.83").compareTo(recovered.netTotalExact))
                assertEquals(0, BigDecimal("9.84").compareTo(recovered.cashReceivedExact))
                assertEquals(0, BigDecimal("0.01").compareTo(recovered.changeReturnedExact))
                assertTrue(recovered.hasLocalTenderDetails)
                assertNotNull(recovered.taxExact)
                assertEquals("USD", repo2.getBusinessInfo().defaultCurrency)
            } finally {
                repo2.close()
            }
        }
    }

    private fun withFixture(body: (MockWebServer, File) -> Unit) {
        val dbFile = File(context.filesDir, "offline-sale-${ClientUuid.random().value}.db")
        MockWebServer().use { server ->
            server.start()
            try {
                body(server, dbFile)
            } finally {
                context.deleteDatabase(dbFile.absolutePath)
            }
        }
    }

    private fun <T> withDatabase(file: File, body: (OutboxDatabase) -> T): T {
        val db = OutboxDatabase.buildDatabase(context, file.absolutePath)
        return try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val result = executor.submit<T> { body(db) }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                    while (!result.isDone && System.nanoTime() < deadline) {
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) ShadowLooper.idleMainLooper()
                        Thread.sleep(5)
                    }
                    result.get(1, TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            } else runBlocking(kotlinx.coroutines.Dispatchers.IO) { body(db) }
        } finally {
            db.close()
        }
    }

    @Test
    fun reviewPackagingPriceAndBrowsePreserveLeasedCapture() = withFixture { server, file ->
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val storage = outbox(db)
            val repo = repository(server, storage, leaseStore = store)
            repo.clock = { fixedClockMillis }
            try {
                repo.isOfflineSalesCapabilityEnabled = true
                val packaged = catalog(null).replace("\"conversion_to_base\":\"1.000000\",\"sale_price\":\"10.00\"",
                    "\"conversion_to_base\":\"6.000000\",\"sale_price\":\"60.00\"")
                server.enqueue(json(packaged))
                runBlocking { repo.prepareOfflineCatalog(1, 1) {}.getOrThrow() }
                take(server)
                assertEquals(0, repo.getProducts().single().priceExact!!.compareTo(BigDecimal("60")))
                val productV1 = repo.getProducts().single()
                server.enqueue(json(leaseResponse()))
                val lease = runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)
                server.enqueue(json(catalog(null, "20.00").replace("\"catalog_snapshot_version\":\"1\"", "\"catalog_snapshot_version\":\"2\"")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                take(server)
                assertEquals(lease, store.getLease(companyA, actor, device))
                assertEquals("1", store.getVerifiedCatalogSnapshotVersion(companyA, actor, device))
                repo.isNetworkOffline = true
                fun sale(price: String) = InvoiceRecord(id = ClientUuid.random().value, invoiceNumber = 0,
                    timestamp = "2026-11-25T10:00:00Z", items = listOf(testBasketItem(productV1, unitPrice = BigDecimal(price))),
                    subtotalExact = BigDecimal(price), discountExact = BigDecimal.ZERO, netTotalExact = BigDecimal(price),
                    cashReceivedExact = BigDecimal(price), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User")
                assertTrue(runBlocking { repo.recordOfflineSale(sale("61.00")) }.isFailure)
                assertTrue(storage.getOfflineQueueRecords(companyA, actor, device).isEmpty())
                val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val reads = (1..8).map { workers.submit<List<com.example.data.mobile.outbox.OutboxRecord>> {
                        storage.getOfflineQueueRecords(companyA, actor, device)
                    } }
                    reads.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).isEmpty()) }
                } finally { workers.shutdownNow() }
                val captured = runBlocking { repo.recordOfflineSale(sale("60.00")).getOrThrow() }
                assertEquals(1, storage.getOfflineQueueRecords(companyA, actor, device).size)
                server.enqueue(json(status(requireNotNull(captured.clientEventId))))
                server.enqueue(json("""{"error":{"code":"FORBIDDEN_TENANT_MISMATCH","message":"Membership revoked"}}""").setResponseCode(403))
                repo.isNetworkOffline = false
                runBlocking { repo.syncOfflineSales().getOrThrow() }
                repo.isNetworkOffline = true
                val reopened = outbox(db)
                assertTrue(reopened.isQueueLocked(companyA, actor, device))
                assertEquals(1, reopened.getOfflineQueueRecords(companyA, actor, device).size)
                assertTrue(runBlocking { repo.recordOfflineSale(sale("60.00")) }.isFailure)
            } finally { repo.close() }
        }
        withDatabase(file) { reopenedDb ->
            val reopened = outbox(reopenedDb)
            assertTrue(reopened.isQueueLocked(companyA, actor, device))
            assertEquals(1, reopened.getOfflineQueueRecords(companyA, actor, device).size)
        }
    }

    @Test
    fun reviewRegisteredCustomerIsRetainedAndGatedBeforeCash() = withFixture { server, file ->
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val repo = repository(server, outbox(db), leaseStore = store)
            val vm = TraderViewModel(repo)
            try {
                await(vm) { !it.isPaymentUnverifiedHold }
                vm.setNetworkOffline(true)
                vm.assignCustomerToSale(com.example.model.Customer(id = companyB.value, nameAr = "زبون", nameEn = "Customer", isDetailLoaded = true))
                vm.navigateTo(com.example.model.TraderScreen.INVOICE_REVIEW)
                vm.navigateTo(com.example.model.TraderScreen.CASH_PAYMENT)
                assertEquals(companyB.value, vm.uiState.value.selectedCustomerId)
                assertTrue(vm.uiState.value.currentScreen != com.example.model.TraderScreen.CASH_PAYMENT)
            } finally { repo.close() }
        }
    }

    @Test
    fun onlineAndOfflineProfileUuidCollisionPreservesCanonicalPayloadAndLeaseBudget() = withFixture { server, file ->
        withDatabase(file) { db ->
            val store = SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
            val storage = outbox(db)
            val repo = repository(server, storage, leaseStore = store)
            repo.clock = { fixedClockMillis }
            try {
                repo.isOfflineSalesCapabilityEnabled = true
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.prepareOfflineCatalog(1, 1) {}.getOrThrow() }
                take(server)

                server.enqueue(json(leaseResponse(maxCount = 5, maxAmount = "100.000000")))
                val lease = runBlocking { repo.acquireOfflineSaleLease().getOrThrow() }
                take(server)

                val item = repo.getProducts().single()
                val line = testBasketItem(item)
                val sharedOnlineUuid = ClientUuid.random()
                val onlineInvoice = InvoiceRecord(
                    id = sharedOnlineUuid.value,
                    clientEventId = sharedOnlineUuid.value,
                    invoiceNumber = 0,
                    timestamp = "2026-11-25T10:00:00Z",
                    items = listOf(line),
                    subtotalExact = BigDecimal("10.00"),
                    discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"),
                    cashReceivedExact = BigDecimal("10.00"),
                    changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "",
                    cashierNameAr = "User",
                    cashierNameEn = "User"
                )

                // 1. Capture an online sale profile (held as UNKNOWN_ACK after lost ACK).
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                runBlocking { repo.recordSale(onlineInvoice).getOrThrow() }
                take(server)
                take(server)

                val onlineBefore = checkNotNull(storage.getRecord(companyA, actor, device, sharedOnlineUuid))
                assertNull(onlineBefore.event.deviceLeaseId)
                assertEquals(OutboxStatus.UNKNOWN_ACK, onlineBefore.status)
                val onlineBytes = onlineBefore.payloadBytes
                val onlineDigest = onlineBefore.payloadDigest
                val onlineDelivery = onlineBefore.deliveryState

                // 2. Switch offline and attempt capture with the exact same event UUID via both repo and captureLeaseSale.
                repo.isNetworkOffline = true
                val forwardRepoCollision = runBlocking { repo.recordSale(onlineInvoice) }
                assertTrue(forwardRepoCollision.isFailure)
                assertTrue(forwardRepoCollision.exceptionOrNull() is IllegalArgumentException)
                assertTrue(forwardRepoCollision.exceptionOrNull()!!.message!!.contains("conflicts with an online sale profile"))

                val collidingOfflineEvent = OfflineSaleEventV2(
                    clientEventId = sharedOnlineUuid,
                    eventVersion = 2,
                    companyId = companyA,
                    actorId = actor,
                    deviceId = device,
                    deviceLeaseId = lease.leaseId,
                    capturedAt = "2026-11-25T10:00:00Z",
                    customerId = lease.walkInCustomerId,
                    currency = lease.currency,
                    warehouseId = lease.warehouseId,
                    cashAccountId = lease.cashAccountId,
                    catalogSnapshotVersion = lease.catalogSnapshotVersion,
                    localReceiptNumber = "LOCAL-${lease.leaseId.value}-1",
                    lines = listOf(
                        com.example.data.mobile.contract.v2.OfflineSaleLineV2(
                            productId = product,
                            productUnitId = productUnit,
                            unitId = productUnit,
                            conversionToBase = com.example.data.mobile.contract.PositiveDecimalQuantity.fromPositiveString("1.000000"),
                            quantity = com.example.data.mobile.contract.PositiveDecimalQuantity.fromPositiveString("1.000000"),
                            unitPrice = com.example.data.mobile.contract.MoneyAmount.fromNonnegativeString("10.000000"),
                            taxRateId = null,
                            taxRate = TaxPercentage("0.000000"),
                            taxInclusive = false,
                            taxAmount = com.example.data.mobile.contract.MoneyAmount.fromNonnegativeString("0.000000"),
                            lineTotal = com.example.data.mobile.contract.MoneyAmount.fromPositiveString("10.000000")
                        )
                    ),
                    payment = com.example.data.mobile.contract.v2.OfflineSalePaymentV2(
                        mode = "full",
                        paymentMethod = "cash",
                        cashAccountId = lease.cashAccountId,
                        amountTendered = com.example.data.mobile.contract.MoneyAmount.fromNonnegativeString("10.000000"),
                        amountSettled = com.example.data.mobile.contract.MoneyAmount.fromPositiveString("10.000000"),
                        changeReturned = com.example.data.mobile.contract.MoneyAmount.fromNonnegativeString("0.000000")
                    )
                )
                val forwardDirectError = runCatching { storage.captureLeaseSale(lease, collidingOfflineEvent) {} }.exceptionOrNull()
                assertTrue(forwardDirectError is IllegalArgumentException)
                assertTrue(forwardDirectError!!.message!!.contains("conflicts with an online sale profile"))

                // Assert old canonical bytes, digest, and delivery state unchanged, zero lease budget charged, no new receipt.
                val onlineAfter = checkNotNull(storage.getRecord(companyA, actor, device, sharedOnlineUuid))
                assertNull(onlineAfter.event.deviceLeaseId)
                org.junit.Assert.assertArrayEquals(onlineBytes, onlineAfter.payloadBytes)
                assertEquals(onlineDigest, onlineAfter.payloadDigest)
                assertEquals(onlineDelivery, onlineAfter.deliveryState)
                val budgetAfterForward = storage.getPendingOfflineSalesBudget(companyA, actor, device, lease.leaseId)
                assertEquals(0, budgetAfterForward.first)
                assertEquals(0, BigDecimal.ZERO.compareTo(budgetAfterForward.second))
                assertEquals(0, repo.getPendingOfflineSalesCount())
                assertTrue(repo.getPendingOfflineSales().isEmpty())
                assertEquals(1, storage.getRecordsByScope(companyA, actor, device).size)
                assertEquals(4, server.requestCount)

                // 3. Confirm the online profile, then re-verify offline collision preserves CONFIRMED state and zero budget charge.
                repo.isNetworkOffline = false
                server.enqueue(json(status(sharedOnlineUuid.value, onlineDigest)))
                assertEquals(1, runBlocking { repo.syncOfflineSales().getOrThrow() }.confirmedCount)
                take(server)
                val confirmedBefore = checkNotNull(storage.getRecord(companyA, actor, device, sharedOnlineUuid))
                assertEquals(OutboxStatus.CONFIRMED, confirmedBefore.status)

                repo.isNetworkOffline = true
                val confirmedCollision = runBlocking { repo.recordSale(onlineInvoice) }
                assertTrue(confirmedCollision.isFailure)
                assertTrue(confirmedCollision.exceptionOrNull()!!.message!!.contains("conflicts with an online sale profile"))
                val confirmedAfter = checkNotNull(storage.getRecord(companyA, actor, device, sharedOnlineUuid))
                org.junit.Assert.assertArrayEquals(onlineBytes, confirmedAfter.payloadBytes)
                assertEquals(onlineDigest, confirmedAfter.payloadDigest)
                assertEquals(confirmedBefore.deliveryState, confirmedAfter.deliveryState)
                assertEquals(0, storage.getPendingOfflineSalesBudget(companyA, actor, device, lease.leaseId).first)

                // 4. Reverse profile collision: capture a genuine offline lease sale, then attempt online capture with the same UUID.
                val offlineUuid = ClientUuid.random()
                val offlineInvoice = onlineInvoice.copy(id = offlineUuid.value, clientEventId = offlineUuid.value)
                val capturedOffline = runBlocking { repo.recordSale(offlineInvoice).getOrThrow() }
                assertEquals("LOCAL-${lease.leaseId.value}-1", capturedOffline.localReceiptNumber)

                val offlineBefore = checkNotNull(storage.getRecord(companyA, actor, device, offlineUuid))
                assertEquals(lease.leaseId.value, offlineBefore.event.deviceLeaseId)
                assertEquals(OutboxStatus.PENDING, offlineBefore.status)
                val offlineBytes = offlineBefore.payloadBytes
                val offlineDigest = offlineBefore.payloadDigest
                val offlineDelivery = offlineBefore.deliveryState

                // 4a. Public ProductionTraderRepository online path rejects mismatched payload before network dispatch.
                repo.isNetworkOffline = false
                val reverseRepoCollision = runBlocking { repo.recordSale(offlineInvoice) }
                assertTrue(reverseRepoCollision.isFailure)
                assertTrue(reverseRepoCollision.exceptionOrNull() is IllegalStateException)
                assertTrue(reverseRepoCollision.exceptionOrNull()!!.message!!.contains("Sale payload differs from the retained event"))
                assertEquals(5, server.requestCount)

                val offlineAfterRepo = checkNotNull(storage.getRecord(companyA, actor, device, offlineUuid))
                org.junit.Assert.assertArrayEquals(offlineBytes, offlineAfterRepo.payloadBytes)
                assertEquals(offlineDigest, offlineAfterRepo.payloadDigest)
                assertEquals(offlineDelivery, offlineAfterRepo.deliveryState)
                assertEquals(lease.leaseId.value, offlineAfterRepo.event.deviceLeaseId)
                val budgetAfterReverseRepo = storage.getPendingOfflineSalesBudget(companyA, actor, device, lease.leaseId)
                assertEquals(1, budgetAfterReverseRepo.first)
                assertEquals(0, BigDecimal("10.00").compareTo(budgetAfterReverseRepo.second))

                // 4b. Direct public OutboxRepository.captureOnlineSaleIntent collision returns Conflict and preserves offline bytes/digest/lease.
                val reverseProfile = com.example.data.mobile.contract.v2.OnlineSaleIntentProfile(
                    actorId = actor,
                    clientEventId = offlineUuid,
                    companyId = companyA,
                    currency = "JOD",
                    currencyMinorUnits = 2,
                    customerId = lease.walkInCustomerId,
                    deviceId = device,
                    lines = listOf(
                        com.example.data.mobile.contract.v2.OnlineSaleIntentLine(
                            productId = product,
                            productUnitId = productUnit,
                            conversionToBase = com.example.data.mobile.contract.PositiveDecimalQuantity.fromPositiveString("1.000000"),
                            quantity = com.example.data.mobile.contract.PositiveDecimalQuantity.fromPositiveString("1.000000"),
                            unitPrice = com.example.data.mobile.contract.MoneyAmount.fromNonnegativeString("10.000000"),
                            taxRateId = null,
                            taxRate = TaxPercentage("0.000000"),
                            taxInclusive = false
                        )
                    ),
                    payment = com.example.data.mobile.contract.v2.OnlineSaleIntentPayment(
                        settlementMode = "full",
                        paymentMethod = "cash",
                        cashAccountId = lease.cashAccountId,
                        amount = com.example.data.mobile.contract.MoneyAmount.fromNonnegativeString("10.000000")
                    ),
                    warehouseId = lease.warehouseId
                )
                val directReverse = storage.captureOnlineSaleIntent(
                    companyId = companyA,
                    actorId = actor,
                    deviceId = device,
                    currencyMinorUnits = 2,
                    profile = reverseProfile,
                    requireNoOtherHeldSale = true
                )
                assertTrue(directReverse is com.example.data.mobile.outbox.CaptureResult.Conflict)
                val directConflict = directReverse as com.example.data.mobile.outbox.CaptureResult.Conflict
                assertEquals(offlineDigest, directConflict.existingDigest)
                assertEquals(reverseProfile.computePayloadDigest().value, directConflict.incomingDigest)

                val offlineAfterDirect = checkNotNull(storage.getRecord(companyA, actor, device, offlineUuid))
                assertEquals(lease.leaseId.value, offlineAfterDirect.event.deviceLeaseId)
                org.junit.Assert.assertArrayEquals(offlineBytes, offlineAfterDirect.payloadBytes)
                assertEquals(offlineDigest, offlineAfterDirect.payloadDigest)
                assertEquals(OutboxStatus.CONFLICT, offlineAfterDirect.status)
                assertEquals(2, storage.getRecordsByScope(companyA, actor, device).size)
                assertEquals(1, storage.getPendingOfflineSalesBudget(companyA, actor, device, lease.leaseId).first)
            } finally { repo.close() }
        }
    }

    private fun outbox(db: OutboxDatabase) = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(crypto),
        requireLeaseAuthorization = true, leaseAuthority = { lease, event ->
            SqliteOfflineLeaseStore(crypto, bootIdentity = { "test-boot" }) { db.openHelper.writableDatabase }
                .authorizesCapture(lease, event)
        })
    private fun id(suffix: Char) = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789$suffix")
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun take(server: MockWebServer) = checkNotNull(server.takeRequest(4, TimeUnit.SECONDS))

    private fun testBasketItem(
        product: com.example.model.Product,
        quantity: BigDecimal = BigDecimal("1.00"),
        unitPrice: BigDecimal = BigDecimal("10.00")
    ) = BasketItem(
        product = product,
        quantity = quantity,
        unitPriceExact = unitPrice,
        taxRateId = null,
        taxRateExact = BigDecimal.ZERO,
        isTaxInclusive = false
    )

    private fun await(vm: TraderViewModel, ready: (TraderUiState) -> Boolean): TraderUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) ShadowLooper.idleMainLooper()
            val state = vm.uiState.value
            if (ready(state)) return state
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        val s = vm.uiState.value
        error("Checkout state did not settle: isSubmittingSale=${s.isSubmittingSale}, isActionBusy=${s.isActionBusy}, activeReceipt=${s.activeReceipt}, errorAr=${s.checkoutErrorAr}, errorEn=${s.checkoutErrorEn}, basketSize=${s.basket.size}, hasUnresolvedSaleTax=${s.hasUnresolvedSaleTax}, isCashSufficient=${s.isCashSufficient}")
    }

    private fun status(eventId: String, digest: String? = null): String {
        val official = if (digest == null) "null" else """
            {"invoice_id":"${invoiceUlid.value}","payment_id":"${paymentUlid.value}","grand_total":"10.000000",
             "invoice_number":"INV-100","payment_receipt_number":"REC-100","posted_at":"2026-11-25T08:00:00Z"}
        """.trimIndent()
        return """
            {"company_id":"${companyA.value}","actor_id":"${actor.value}","device_id":"${device.value}",
             "client_event_id":"$eventId","status":"${if (digest == null) "unknown" else "posted"}",
             "payload_digest":${digest?.let { "\"$it\"" } ?: "null"},"official_invoice":$official}
        """.trimIndent()
    }

    private fun catalog(tax: BootstrapTax?, price: String = "10.00") = """
        {"company_id":"${companyA.value}","catalog_snapshot_version":"1","generated_at":"2026-11-25T08:00:00Z",
         "has_more":false,"next_cursor":null,"products":[{
           "id":"${product.value}","sku":"A","barcode":null,"name":"Coffee","unit_price":"$price",
           "tax_rate_id":${tax?.let { "\"${it.id.value}\"" } ?: "null"},"tax_rate":${tax?.let { "\"${it.rate.value}\"" } ?: "null"},
           "is_tracked":true,"available_quantity":"100.000000","base_unit":{"id":"${unit.value}","code":"kg","name":"kg"},
           "default_unit":{"product_unit_id":"${productUnit.value}","unit_id":"${unit.value}",
                           "conversion_to_base":"1.000000","sale_price":"$price"},"units":[]
         }]}
    """.trimIndent()

    private fun leaseResponse(
        lId: ClientUuid = leaseId,
        snapshotVersion: String = "1",
        maxHours: Long = 8,
        maxCount: Long = 10,
        maxAmount: String = "500.000000",
        postedCount: Int = 0,
        postedAmount: String = "0.000000",
        issuedAt: String = "2026-11-25T08:00:00Z",
        expiresAt: String = "2026-11-25T16:00:00Z",
        taxId: String? = null,
        taxRate: String? = null,
        taxCalc: String? = null,
        currency: String = "JOD",
        precision: Int = 2
    ): String = """
        {
          "lease_id": "${lId.value}",
          "company_id": "${companyA.value}",
          "actor_id": "${actor.value}",
          "device_id": "${device.value}",
          "catalog_snapshot_version": "$snapshotVersion",
          "issued_at": "$issuedAt",
          "expires_at": "$expiresAt",
          "posted_sales_count": $postedCount,
          "posted_total_amount": "$postedAmount",
          "currency": "$currency",
          "currency_minor_units": $precision,
          "warehouse_id": "01JMB000000000000000000003",
          "cash_account_id": "01JMB000000000000000000004",
          "walk_in_customer_id": "01JMB000000000000000000005",
          "tax_rate_id": ${taxId?.let { "\"$it\"" } ?: "null"},
          "tax_rate": ${taxRate?.let { "\"$it\"" } ?: "null"},
          "tax_calculation": ${taxCalc?.let { "\"$it\"" } ?: "null"},
          "max_offline_hours": $maxHours,
          "max_offline_sales_count": $maxCount,
          "max_offline_total_amount": "$maxAmount"
        }
    """.trimIndent()
}
