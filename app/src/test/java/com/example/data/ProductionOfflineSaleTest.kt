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
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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
                assertEquals("LOCAL-1", receipt.localReceiptNumber)
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
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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
                val receipt1 = await(vm) { it.activeReceipt?.localReceiptNumber == "LOCAL-1" }.activeReceipt!!
                assertEquals("LOCAL-1", receipt1.localReceiptNumber)

                // Sale 2
                vm.startFreshSale()
                vm.addProductToBasket(item, 1)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("15.00")
                vm.completeCashSale()
                val receipt2 = await(vm) { it.activeReceipt?.localReceiptNumber == "LOCAL-2" }.activeReceipt!!
                assertEquals("LOCAL-2", receipt2.localReceiptNumber)

                // Sale 3
                vm.startFreshSale()
                vm.addProductToBasket(item, 1)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("20.00")
                vm.completeCashSale()
                val receipt3 = await(vm) { it.activeReceipt?.localReceiptNumber == "LOCAL-3" }.activeReceipt!!
                assertEquals("LOCAL-3", receipt3.localReceiptNumber)

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
            val leaseStore1 = SqliteOfflineLeaseStore { db1.openHelper.writableDatabase }
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
            val leaseStore2 = SqliteOfflineLeaseStore { db2.openHelper.writableDatabase }
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
                assertEquals("LOCAL-1", pending[0].localReceiptNumber)
                assertEquals("LOCAL-2", pending[1].localReceiptNumber)
            } finally {
                repo2.close()
            }
        }
    }

    @Test
    fun test4_statusLostAckRecovery() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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

                // 1. Clock advanced past expiresAt (e.g. 17:00 UTC)
                val expiredMillis = 1795626000000L
                repo.clock = { expiredMillis }
                assertNull(repo.getActiveOfflineSaleLease())
                val resultExpired = runBlocking { repo.recordSale(inv) }
                assertTrue(resultExpired.isFailure)
                assertTrue(resultExpired.exceptionOrNull()!!.message!!.contains("No active valid offline sale lease"))

                // 2. Clock moved backwards before issuedAt (clock uncertainty / backwards skew, e.g. 07:00 UTC)
                val backwardsMillis = 1795590000000L
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
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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
                assertEquals("LOCAL-1", rejected[0].localReceiptNumber)
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
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }
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
                runBlocking { repo.recordSale(inv1).getOrThrow() }

                // Sale 2: 10.00 (Count = 2, Total = 20.00) -> SUCCESS
                val inv2 = InvoiceRecord(
                    id = "d2", invoiceNumber = 2, timestamp = "2026-11-25T10:01:00Z",
                    items = listOf(basketItem), subtotalExact = BigDecimal("10.00"), discountExact = BigDecimal.ZERO,
                    netTotalExact = BigDecimal("10.00"), cashReceivedExact = BigDecimal("10.00"), changeReturnedExact = BigDecimal.ZERO,
                    customerNote = "", cashierNameAr = "User", cashierNameEn = "User"
                )
                runBlocking { repo.recordSale(inv2).getOrThrow() }

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
                assertEquals(2, repo.getPendingOfflineSalesCount())
                assertEquals(BigDecimal("20.00"), repo.getPendingOfflineSalesTotal())
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun test9_tenantSwitchIsolation() = withFixture { server, file ->
        withDatabase(file) { db ->
            val outbox = outbox(db)
            val leaseStore = SqliteOfflineLeaseStore { db.openHelper.writableDatabase }

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
            baseCurrency = "JOD",
            currencyMinorUnits = precision,
            outboxRepository = outbox,
            saleSyncGateway = HttpSaleSyncGateway(transport, origin) { scope.token },
            bootstrapTaxes = listOf(inclusiveTax),
            offlineLeaseStore = leaseStore
        )
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
        val db = OutboxDatabase.buildDatabase(context, file.absolutePath, allowMainThreadQueries = true)
        return try {
            body(db)
        } finally {
            db.close()
        }
    }

    private fun outbox(db: OutboxDatabase) = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(crypto))
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
            ShadowLooper.idleMainLooper()
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
        taxCalc: String? = null
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
          "currency": "JOD",
          "currency_minor_units": 2,
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
