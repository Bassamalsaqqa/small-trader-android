package com.example.data

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.PayloadDigest
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.TaxPercentage
import com.example.data.mobile.contract.v2.PaymentDecimalConsistency
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
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
import com.example.model.TraderScreen
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionSaleRecoveryTest {
    private val company = id('A')
    private val actor = id('B')
    private val invoice = id('C')
    private val payment = id('D')
    private val product = id('E')
    private val unit = id('F')
    private val productUnit = id('G')
    private val inclusive = BootstrapTax(id('H'), "IN", "Included tax", TaxPercentage("17.500000"), "inclusive", false)
    private val exclusive = BootstrapTax(id('J'), "EX", "Added tax", TaxPercentage("17.500000"), "exclusive", false)
    private val device = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val token = "1|sanctum_local_fixture_sale_recovery_token"
    private val auth = CurrentAuthScope(company, actor, device, token, 1L)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val crypto = JvmAesGcmCryptoEngine()

    @Test
    fun lostAcknowledgementRestoresSameSaleAndRequiresVerifiedGet() = withFixture { server, file ->
        val (eventId, digest, statusPath) = withDatabase(file) { db ->
            val outbox = outbox(db)
            val repo = repository(server, outbox)
            val vm = TraderViewModel(repo)
            try {
                await(vm) { !it.isPaymentUnverifiedHold }
                server.enqueue(json(catalog(null, "10.00")))
                runBlocking { repo.refreshProducts().getOrThrow() }
                assertEquals("GET", take(server).method)
                val item = repo.getProducts().single()
                vm.addProductToBasket(item, 1)
                assertTrue(vm.uiState.value.hasUnresolvedSaleTax)
                vm.selectSaleLineTax(item.id, null)
                vm.updateCashInputText("20.00")
                val eventId = vm.uiState.value.currentSaleClientEventId
                val statusPath = "/api/mobile/v2/companies/${company.value}/offline-events/$eventId/status"
                server.enqueue(json(status(eventId)))
                // The server receives the entire POST and drops the reply.
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                vm.completeCashSale()
                val held = await(vm) { it.isPaymentUnverifiedHold && !it.isSubmittingSale }
                assertEquals(eventId, held.activeReceipt?.clientEventId)
                assertEquals("unknown_ack", held.activeReceipt?.outboxStatus)
                val record = outbox.getRecord(company, actor, device, ClientUuid.parse(eventId))!!
                val digest = record.payloadDigest
                assertEquals(digest, held.activeReceipt?.salePayloadDigest)
                assertEquals(digest, PayloadDigest.fromBytes(record.payloadBytes).value)
                val storedBytes = db.outboxDao().getEvent(company.value, eventId)!!.payloadBytes
                assertTrue(OutboxPayloadCipher(crypto).isEncrypted(storedBytes))
                assertFalse(storedBytes.contentEquals(record.payloadBytes))
                assertEquals(statusPath, take(server).path)
                assertEquals("POST", take(server).method)
                assertEquals(1, db.outboxDao().eventCount())
                Triple(eventId, digest, statusPath)
            } finally {
                vm.viewModelScope.cancel()
                repo.close()
            }
        }

        withDatabase(file) { db ->
            val outbox = outbox(db)
            // Reopening under another actor or device neither restores nor discloses the hold.
            for (other in listOf(auth.copy(actorId = id('K')), auth.copy(deviceId = ClientUuid.random()), auth.copy(companyId = id('K')))) {
                val repo = repository(server, outbox, scope = other)
                try {
                    assertNull(runBlocking { repo.restoreHeldSale().getOrThrow() })
                } finally {
                    repo.close()
                }
            }
            val repo = repository(server, outbox)
            val vm = TraderViewModel(repo)
            try {
                val restored = await(vm) { it.isPaymentUnverifiedHold && it.activeReceipt != null }
                assertEquals(TraderScreen.CASH_PAYMENT, restored.currentScreen)
                assertEquals(eventId, restored.currentSaleClientEventId)
                assertEquals(digest, restored.activeReceipt?.salePayloadDigest)
                assertEquals(0, BigDecimal("10.00").compareTo(restored.basketNetTotalExact))
                val heldReceipt = requireNotNull(restored.activeReceipt)
                assertFalse(heldReceipt.hasLocalTenderDetails)
                assertEquals(eventId, repo.invoicesFlow.value.single().clientEventId)
                vm.clearBasket()
                vm.addProductToBasket(restored.basket.single().product, 2)
                vm.selectSaleLineTax(product.value, inclusive.id.value)
                vm.startFreshSale()
                vm.completeCashSale()
                assertEquals(restored.basket, vm.uiState.value.basket)
                assertEquals(TraderScreen.CASH_PAYMENT, vm.uiState.value.currentScreen)
                val blocked = heldReceipt.copy(clientEventId = ClientUuid.random().value)
                assertTrue(runBlocking { repo.recordSale(blocked) }.isFailure)
                assertEquals(3, server.requestCount)

                val posted = status(eventId, digest)
                val replies = listOf(
                    json(status(eventId)),
                    json(status(eventId).replace("\"unknown\"", "\"pending\"")),
                    MockResponse().setResponseCode(403),
                    MockResponse().setResponseCode(409),
                    json(posted.replace(company.value, id('K').value)),
                    json(posted.replace(actor.value, id('K').value)),
                    json(posted.replace(device.value, ClientUuid.random().value)),
                    json(posted.replace(eventId, ClientUuid.random().value)),
                    json(posted.replace(digest, "sha256:" + "0".repeat(64))),
                    json(posted.replace("\"${payment.value}\"", "null"))
                )
                for (reply in replies) {
                    server.enqueue(reply)
                    vm.checkHeldSaleStatus()
                    assertLookup(server, statusPath)
                    val afterCheck = await(vm) { !it.isSubmittingSale }
                    assertTrue(afterCheck.isPaymentUnverifiedHold)
                    assertEquals(eventId, afterCheck.currentSaleClientEventId)
                    assertNull(afterCheck.activeReceipt?.serverInvoiceUlid)
                    assertEquals(OutboxStatus.UNKNOWN_ACK, outbox.getRecord(company, actor, device, ClientUuid.parse(eventId))!!.status)
                }

                server.enqueue(json(posted))
                vm.checkHeldSaleStatus()
                assertLookup(server, statusPath)
                val receipt = await(vm) { it.currentScreen == TraderScreen.RECEIPT }.activeReceipt!!
                assertEquals(invoice.value, receipt.id)
                assertEquals(invoice.value, receipt.serverInvoiceUlid)
                assertEquals(payment.value, receipt.serverPaymentUlid)
                assertEquals(eventId, receipt.clientEventId)
                assertEquals(digest, receipt.salePayloadDigest)
                assertEquals("INV-100", receipt.serverInvoiceNumber)
                assertEquals("REC-100", receipt.serverPaymentReceiptNumber)
                assertFalse(vm.uiState.value.isPaymentUnverifiedHold)
                assertTrue(vm.uiState.value.basket.isEmpty())
                val confirmed = outbox.getRecord(company, actor, device, ClientUuid.parse(eventId))!!
                assertEquals(OutboxStatus.CONFIRMED, confirmed.status)
                assertEquals(1, confirmed.deliveryState.attemptCount)
                assertEquals(1, db.outboxDao().eventCount())
                assertEquals(4 + replies.size, server.requestCount)
            } finally {
                vm.viewModelScope.cancel()
                repo.close()
            }
        }

        withDatabase(file) { db ->
            val repo = repository(server, outbox(db))
            try {
                assertNull(runBlocking { repo.restoreHeldSale().getOrThrow() })
                server.enqueue(json(history()))
                val fromHistory = runBlocking { repo.refreshInvoices().getOrThrow().single() }
                assertEquals("GET", take(server).method)
                assertEquals(invoice.value, fromHistory.id)
                assertEquals(payment.value, fromHistory.serverPaymentUlid)
                assertEquals(eventId, fromHistory.clientEventId)
                assertEquals(digest, fromHistory.salePayloadDigest)
                assertFalse(fromHistory.hasLocalTenderDetails)
            } finally {
                repo.close()
            }
        }
    }

    @Test
    fun catalogTaxAndCashUseAuthoritativePrecision() {
        val subtotals = mapOf(0 to "1", 2 to "1.23", 3 to "1.235", 6 to "1.234567")
        val withTax = mapOf(0 to "1", 2 to "1.45", 3 to "1.451", 6 to "1.450616")
        for ((precision, subtotal) in subtotals) withFixture { server, file ->
            withDatabase(file) { db ->
                val outbox = outbox(db)
                val repo = repository(server, outbox, precision)
                try {
                    fun load(tax: BootstrapTax?): BasketItem {
                        server.enqueue(json(catalog(tax)))
                        val result = runBlocking { repo.refreshProducts() }
                        result.getOrThrow()
                        assertEquals("GET", take(server).method)
                        return BasketItem(repo.getProducts().single(), 1)
                    }
                    val included = load(inclusive)
                    val added = load(exclusive)
                    val unassigned = load(null)
                    assertTrue(included.isTaxInclusive)
                    assertFalse(added.isTaxInclusive)
                    assertFalse(unassigned.hasResolvedTax)
                    assertEquals(0, included.lineTotalExact.compareTo(BigDecimal(subtotal)))
                    assertEquals(0, added.lineTotalExact.compareTo(BigDecimal(withTax.getValue(precision))))
                    assertTrue(included.calculateLine(precision).taxAmount.value >= BigDecimal.ZERO)

                    val noTax = unassigned.copy(taxRateExact = BigDecimal.ZERO)
                    assertEquals(0, noTax.lineTotalExact.compareTo(BigDecimal(subtotal)))
                    val eventId = ClientUuid.random()
                    val total = noTax.lineTotalExact
                    val sale = InvoiceRecord(
                        id = eventId.value, invoiceNumber = 1, timestamp = "2026-11-25T08:00:00Z",
                        items = listOf(unassigned), subtotalExact = total, discountExact = BigDecimal.ZERO, netTotalExact = total,
                        cashReceivedExact = BigDecimal.ZERO, changeReturnedExact = BigDecimal.ZERO, customerNote = "",
                        cashierNameAr = "", cashierNameEn = "", isPaidInFull = false, remainingDebitExact = total,
                        clientEventId = eventId.value
                    )
                    assertTrue(runBlocking { repo.recordSale(sale) }.isFailure)
                    val invalidChange = if (precision == 0) BigDecimal("0.01") else BigDecimal("0.001")
                    val cashSale = sale.copy(
                        items = listOf(noTax), isPaidInFull = true, remainingDebitExact = BigDecimal.ZERO,
                        cashReceivedExact = total + invalidChange, changeReturnedExact = invalidChange
                    )
                    assertTrue(runBlocking { repo.recordSale(cashSale) }.isFailure)
                    val state = TraderUiState(
                        basket = listOf(noTax), businessInfo = repo.getBusinessInfo(), cashInputText = cashSale.cashReceivedExact.toPlainString()
                    )
                    assertTrue(state.isCashCollectionUnrepresentable)
                    assertEquals(precision >= 2, PaymentDecimalConsistency.fitsCashEntry(BigDecimal("0.01"), precision))
                    assertFalse(PaymentDecimalConsistency.fitsCashEntry(BigDecimal("0.001"), precision))
                    assertEquals(0, db.outboxDao().eventCount())
                    assertEquals(3, server.requestCount)

                    val selectedLines = listOf(included, added, noTax)
                    val grandTotal = selectedLines.fold(BigDecimal.ZERO) { sum, line -> sum + line.lineTotalExact }
                    server.enqueue(json(status(eventId.value)))
                    server.enqueue(MockResponse().setResponseCode(409))
                    val held = runBlocking {
                        repo.recordSale(sale.copy(
                            items = selectedLines, subtotalExact = grandTotal, netTotalExact = grandTotal, remainingDebitExact = grandTotal
                        )).getOrThrow()
                    }
                    assertEquals("conflict", held.outboxStatus)
                    assertEquals("GET", take(server).method)
                    val post = take(server)
                    assertEquals("POST", post.method)
                    val record = outbox.getRecord(company, actor, device, eventId)!!
                    val json = StrictJsonParser.parseObject(String(record.payloadBytes, Charsets.UTF_8))
                    val lines = json.require("lines").asArray()
                    assertTrue(lines[0].asObject().require("tax_inclusive").asBoolean())
                    assertFalse(lines[1].asObject().require("tax_inclusive").asBoolean())
                    assertEquals("0.000000", lines[2].asObject().require("tax_rate").asString())
                    assertEquals(precision, json.require("currency_minor_units").asInt())
                    assertEquals(held.salePayloadDigest, runBlocking { repo.restoreHeldSale().getOrThrow()?.salePayloadDigest })
                } finally {
                    repo.close()
                }
            }
        }
    }

    @Test
    fun onlineHeldJodSale_survivesRecreateAndChangedCurrentBusinessCurrency_withoutTenderFabrication() {
        withFixture { server, file ->
            val eventId = ClientUuid.random()
            withDatabase(file) { db1 ->
                val outbox1 = outbox(db1)
                val repo1 = repository(server, outbox1, precision = 3, baseCurrency = "JOD")
                try {
                    server.enqueue(json(catalog(inclusive, "9.830")))
                    runBlocking { repo1.refreshProducts() }.getOrThrow()
                    take(server)

                    val basketItem = BasketItem(repo1.getProducts().single(), 1)
                    val calc = basketItem.calculateLine(3)
                    val total = calc.lineTotal.value
                    assertEquals(0, BigDecimal("9.83").compareTo(total))
                    val heldSale = InvoiceRecord(
                        id = eventId.value,
                        invoiceNumber = 1,
                        timestamp = "2026-11-25T09:00:00Z",
                        items = listOf(basketItem),
                        subtotalExact = total,
                        discountExact = BigDecimal.ZERO,
                        netTotalExact = total,
                        cashReceivedExact = BigDecimal("10.00"),
                        changeReturnedExact = BigDecimal("10.00").subtract(total),
                        customerNote = "",
                        cashierNameAr = "Owner",
                        cashierNameEn = "Owner",
                        isPaidInFull = true,
                        remainingDebitExact = BigDecimal.ZERO,
                        clientEventId = eventId.value,
                        currency = "JOD",
                        currencyMinorUnits = 3,
                        taxExact = calc.taxAmount.value
                    )

                    server.enqueue(json(status(eventId.value)))
                    server.enqueue(MockResponse().setResponseCode(500))
                    val held = runBlocking { repo1.recordSale(heldSale) }.getOrThrow()
                    assertEquals("JOD", held.currency)
                    assertEquals(3, held.currencyMinorUnits)
                    assertEquals(0, total.compareTo(held.netTotalExact))
                } finally {
                    repo1.close()
                }
            }

            // Reopen durable Room DB on a changed current business currency (USD, 2 minor units)
            withDatabase(file) { db2 ->
                val outbox2 = outbox(db2)
                val repo2 = repository(server, outbox2, precision = 2, baseCurrency = "USD")
                try {
                    val restored = checkNotNull(runBlocking { repo2.restoreHeldSale() }.getOrThrow())
                    assertEquals("JOD", restored.currency)
                    assertEquals(3, restored.currencyMinorUnits)
                    assertEquals(false, restored.hasLocalTenderDetails)
                    assertTrue(restored.taxExact != null && restored.taxExact!!.signum() > 0)
                    // Current business info changed to USD, while historic held receipt remains frozen in JOD
                    assertEquals("USD", repo2.getBusinessInfo().defaultCurrency)
                } finally {
                    repo2.close()
                }
            }
        }
    }

    @Test
    fun onlineHeldJodSale_tamperedMinorUnitsColumn_usesAuthenticatedProfileWithoutCrash() {
        withFixture { server, file ->
            val eventId = ClientUuid.random()
            val (origBytes, origDigest) = withDatabase(file) { db1 ->
                val outbox1 = outbox(db1)
                val repo1 = repository(server, outbox1, precision = 3, baseCurrency = "JOD")
                try {
                    server.enqueue(json(catalog(inclusive, "9.833")))
                    runBlocking { repo1.refreshProducts() }.getOrThrow()
                    take(server)

                    val basketItem = BasketItem(repo1.getProducts().single(), 1)
                    val calc = basketItem.calculateLine(3)
                    val total = calc.lineTotal.value
                    assertEquals(0, BigDecimal("9.833").compareTo(total))
                    val heldSale = InvoiceRecord(
                        id = eventId.value,
                        invoiceNumber = 1,
                        timestamp = "2026-11-25T09:00:00Z",
                        items = listOf(basketItem),
                        subtotalExact = total,
                        discountExact = BigDecimal.ZERO,
                        netTotalExact = total,
                        cashReceivedExact = BigDecimal.ZERO,
                        changeReturnedExact = BigDecimal.ZERO,
                        customerNote = "",
                        cashierNameAr = "Owner",
                        cashierNameEn = "Owner",
                        isPaidInFull = false,
                        remainingDebitExact = total,
                        clientEventId = eventId.value,
                        currency = "JOD",
                        currencyMinorUnits = 3,
                        taxExact = calc.taxAmount.value
                    )

                    server.enqueue(json(status(eventId.value)))
                    server.enqueue(MockResponse().setResponseCode(500))
                    runBlocking { repo1.recordSale(heldSale) }.getOrThrow()
                    val rec = checkNotNull(outbox1.getRecord(company, actor, device, eventId))
                    rec.payloadBytes to rec.payloadDigest
                } finally {
                    repo1.close()
                }
            }

            withDatabase(file) { db2 ->
                db2.openHelper.writableDatabase.execSQL(
                    "UPDATE outbox_events SET currency_minor_units = 99 WHERE company_id = ? AND client_event_id = ?",
                    arrayOf(company.value, eventId.value)
                )
                val outbox2 = outbox(db2)
                val repo2 = repository(server, outbox2, precision = 2, baseCurrency = "USD")
                try {
                    val restored = checkNotNull(runBlocking { repo2.restoreHeldSale() }.getOrThrow())
                    assertEquals("JOD", restored.currency)
                    assertEquals(3, restored.currencyMinorUnits)
                    assertEquals(0, BigDecimal("9.833").compareTo(restored.netTotalExact))
                    assertEquals(0, BigDecimal("9.833").compareTo(restored.items.single().lineTotalExact))
                    val persisted = checkNotNull(outbox2.getRecord(company, actor, device, eventId))
                    org.junit.Assert.assertArrayEquals(origBytes, persisted.payloadBytes)
                    assertEquals(origDigest, persisted.payloadDigest)
                } finally {
                    repo2.close()
                }
            }
        }
    }

    private fun repository(
        server: MockWebServer,
        outbox: OutboxRepositoryImpl,
        precision: Int = 2,
        scope: CurrentAuthScope = auth,
        baseCurrency: String = "JOD"
    ): ProductionTraderRepository {
        val origin = "http://127.0.0.1:${server.port}"
        val transport = OkHttpTransport(origin, allowHttpLoopback = true, readTimeoutMs = 1500, callTimeoutMs = 3000)
        return ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(transport, origin) { scope.token },
            companyId = scope.companyId, actorId = scope.actorId, deviceId = scope.deviceId,
            authScopeProvider = { scope },
            merchantReadClient = MerchantReadClient(transport, origin, allowHttpLoopback = true,
                tokenProvider = MerchantReadTokenProvider { _, _ -> scope.token }),
            baseCurrency = baseCurrency, currencyMinorUnits = precision, outboxRepository = outbox,
            saleSyncGateway = HttpSaleSyncGateway(transport, origin) { scope.token },
            bootstrapTaxes = listOf(inclusive, exclusive)
        )
    }

    private fun withFixture(body: (MockWebServer, File) -> Unit) {
        val dbFile = File(context.filesDir, "sale-recovery-${ClientUuid.random().value}.db")
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

    private fun assertLookup(server: MockWebServer, statusPath: String) {
        val request = take(server)
        assertEquals("GET", request.method)
        assertEquals(statusPath, request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    private fun await(vm: TraderViewModel, ready: (TraderUiState) -> Boolean): TraderUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            ShadowLooper.idleMainLooper()
            val state = vm.uiState.value
            if (ready(state)) return state
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        error("Checkout state did not settle")
    }

    private fun status(eventId: String, digest: String? = null): String {
        val official = if (digest == null) "null" else """
            {"invoice_id":"${invoice.value}","payment_id":"${payment.value}","grand_total":"10.000000",
             "invoice_number":"INV-100","payment_receipt_number":"REC-100","posted_at":"2026-11-25T08:00:00Z"}
        """.trimIndent()
        return """
            {"company_id":"${company.value}","actor_id":"${actor.value}","device_id":"${device.value}",
             "client_event_id":"$eventId","status":"${if (digest == null) "unknown" else "posted"}",
             "payload_digest":${digest?.let { "\"$it\"" } ?: "null"},"official_invoice":$official}
        """.trimIndent()
    }

    private fun catalog(tax: BootstrapTax?, price: String = "1.234567") = """
        {"company_id":"${company.value}","catalog_snapshot_version":"1","generated_at":"2026-11-25T08:00:00Z",
         "has_more":false,"next_cursor":null,"products":[{
           "id":"${product.value}","sku":"A","barcode":null,"name":"Coffee","unit_price":"$price",
           "tax_rate_id":${tax?.let { "\"${it.id.value}\"" } ?: "null"},"tax_rate":${tax?.let { "\"${it.rate.value}\"" } ?: "null"},
           "is_tracked":true,"available_quantity":"100.000000","base_unit":{"id":"${unit.value}","code":"kg","name":"kg"},
           "default_unit":{"product_unit_id":"${productUnit.value}","unit_id":"${unit.value}",
                           "conversion_to_base":"1.000000","sale_price":"$price"},"units":[]
         }]}
    """.trimIndent()

    private fun history() = """
        {"company_id":"${company.value}","count":1,"has_more":false,"next_cursor":null,"invoices":[{
          "id":"${invoice.value}","invoice_number":"INV-100","status":"posted",
          "customer_id":"01JMB000000000000000000005","customer_name":"Walk-in","date":"2026-11-25",
          "subtotal":"10.00","discount":"0.00","tax":"0.00","total":"10.00","paid":"10.00","remaining":"0.00",
          "currency":"JOD","currency_minor_units":2,"payment_status":"PAID_CASH","created_at":"2026-11-25T08:00:00Z"
        }]}
    """.trimIndent()
}
