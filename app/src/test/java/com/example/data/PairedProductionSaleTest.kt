package com.example.data

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.*
import com.example.data.mobile.contract.v2.*
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.offline.RestrictedOfflineBootstrapStore
import com.example.data.mobile.offline.RestrictedOfflineWorkspace
import com.example.data.mobile.offline.SqliteOfflineLeaseStore
import com.example.data.mobile.offline.authorizesCapture
import com.example.data.mobile.outbox.*
import com.example.data.mobile.readclient.*
import com.example.data.mobile.readclient.http.HttpTransportException
import com.example.data.mobile.readclient.http.OkHttpTransport
import com.example.data.mobile.session.*
import com.example.data.mobile.sync.HttpSaleSyncGateway
import com.example.data.mobile.sync.SyncDiagnostics
import com.example.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.SecretKey

/** Real loopback Laravel only. No MockWebServer, canned ACKs, or synthetic bearer sessions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairedProductionSaleTest {
    private data class RecordedHttpExchange(
        val method: HttpMethod,
        val path: String,
        val statusCode: Int,
        val body: String
    )

    private fun privateJson(path: String): StrictJsonObject {
        val file = File(path)
        require(!Files.isSymbolicLink(file.toPath()))
        require(Files.getPosixFilePermissions(file.toPath()).all {
            it == PosixFilePermission.OWNER_READ || it == PosixFilePermission.OWNER_WRITE
        }) { "Handoff must be owner private" }
        return StrictJsonParser.parseObject(file.readText())
    }

    private fun StrictJsonObject.optionalString(key: String): String? {
        val elem = properties[key] ?: return null
        return if (elem is StrictJsonNull) null else elem.asString()
    }

    private fun <T> requireSuccess(result: ReadClientResult<T>): T {
        check(result is ReadClientResult.Success) { "Authoritative HTTP operation failed: ${result::class.simpleName}" }
        return result.value
    }

    private fun loginRealStaff(
        client: SmallTraderReadClient,
        device: ClientUuid,
        email: String,
        password: String
    ): AuthLoginSuccessResponse {
        val challenge = requireSuccess(client.authChallenge(AuthChallengeRequest(device, "paired-test", "android")))
        val login = requireSuccess(
            client.authLogin(
                AuthLoginRequest(
                    challengeId = challenge.challengeId,
                    nonce = challenge.nonce,
                    deviceId = device,
                    deviceName = "Synthetic paired client",
                    email = email,
                    password = password
                )
            )
        )
        check(login is AuthLoginResult.Success) { "Fixture requires ordinary named staff login" }
        return login.response
    }

    private fun invoiceFor(
        items: List<BasketItem>,
        minorUnits: Int,
        timestamp: String = Instant.now().toString()
    ): InvoiceRecord {
        val total = items.fold(BigDecimal.ZERO) { sum, item ->
            sum.add(item.calculateLine(minorUnits).lineTotal.value)
        }
        val event = ClientUuid.random().value
        return InvoiceRecord(
            id = event,
            clientEventId = event,
            invoiceNumber = 0,
            timestamp = timestamp,
            items = items,
            subtotalExact = total,
            discountExact = BigDecimal.ZERO,
            netTotalExact = total,
            cashReceivedExact = total,
            changeReturnedExact = BigDecimal.ZERO,
            customerNote = "",
            cashierNameAr = "",
            cashierNameEn = "",
            isPaidInFull = true,
            remainingDebitExact = BigDecimal.ZERO
        )
    }

    @Test
    fun realLoginOnlineOfflineTaxPrecisionRestrictedAndNegativePaths() = runBlocking {
        val path = System.getenv("SMALL_TRADER_FINANCIAL_PILOT_HANDOFF")
        assumeTrue("NOT RUN: owned paired financial slot has not been assigned", !path.isNullOrBlank())
        val handoff = privateJson(requireNotNull(path))
        val rawExpectedBackend = System.getenv("PAIRED_BACKEND_SHA")
        val expectedBackend = rawExpectedBackend ?: "220f5c78fcedcbd2b045d0e9100317a11da07504"
        require(Regex("^[0-9a-f]{40}$").matches(expectedBackend)) {
            "PAIRED_BACKEND_SHA must be an explicit 40-character lowercase git commit SHA"
        }
        require(handoff.require("backend").asString() == expectedBackend)
        require(handoff.require("expires").asInt().toLong() > System.currentTimeMillis() / 1000)
        val origin = handoff.require("origin").asString()
        require(origin == "http://127.0.0.1:18400")
        val credentials = privateJson(handoff.require("credentials").asString())
        val device = ClientUuid.parse(handoff.require("device").asString())
        val secondDevice = ClientUuid.parse(handoff.require("second_device").asString())
        val otherDevice = ClientUuid.parse(handoff.require("other_device").asString())
        val revokedDevice = ClientUuid.parse(handoff.require("revoked_device").asString())
        val foreignCompany = ServerUlid.parse(handoff.require("foreign_company").asString())

        val upstream = OkHttpTransport(origin, allowHttpLoopback = true)
        var dropOnlineCommitReply = false
        var dropOfflineCommitReply = false
        var retainedOnlineCommit: TransportRequest? = null
        var retainedOfflineCommit: TransportRequest? = null
        val observedExchanges = CopyOnWriteArrayList<RecordedHttpExchange>()

        fun assertDecimal6(expected6dp: String, actual: String) {
            assertEquals(0, BigDecimal(expected6dp).compareTo(BigDecimal(actual)))
            assertEquals(expected6dp, BigDecimal(actual).setScale(6, RoundingMode.UNNECESSARY).toPlainString())
        }

        fun assertOnlineWireTotals(clientEventId: String, expectedTotal6dp: String) {
            val match = observedExchanges.last { ex ->
                ex.method == HttpMethod.POST &&
                    ex.path.endsWith("/sales/online-cash") &&
                    ex.statusCode in 200..201 &&
                    runCatching { StrictJsonParser.parseObject(ex.body).optionalString("client_event_id") }.getOrNull() == clientEventId
            }
            val obj = StrictJsonParser.parseObject(match.body)
            assertEquals("posted", obj.require("status").asString())
            assertDecimal6(expectedTotal6dp, obj.require("grand_total").asString())
            assertDecimal6(expectedTotal6dp, obj.require("amount_paid").asString())
            assertDecimal6("0.000000", obj.require("change_returned").asString())
        }

        fun assertOfflineWireTotal(clientEventId: String, expectedTotal6dp: String) {
            val match = observedExchanges.last { ex ->
                ex.method == HttpMethod.POST &&
                    ex.path.endsWith("/offline-events") &&
                    ex.statusCode == 200 &&
                    runCatching { StrictJsonParser.parseObject(ex.body).optionalString("client_event_id") }.getOrNull() == clientEventId
            }
            val obj = StrictJsonParser.parseObject(match.body)
            assertEquals("posted", obj.require("status").asString())
            assertDecimal6(expectedTotal6dp, obj.require("outcome").asObject().require("grand_total").asString())
        }

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                val response = upstream.execute(request)
                observedExchanges.add(RecordedHttpExchange(request.method, request.path, response.statusCode, response.body))
                if (request.method == HttpMethod.POST && request.path.endsWith("/sales/online-cash")) {
                    retainedOnlineCommit = request
                    if (dropOnlineCommitReply && response.statusCode in 200..201) {
                        dropOnlineCommitReply = false
                        // Qualifier fault: Laravel has replied after commit, but the client loses its reply.
                        throw HttpTransportException(HttpTransportException.CATEGORY_NETWORK_IO, "Qualifier dropped online committed reply")
                    }
                }
                if (request.method == HttpMethod.POST && request.path.endsWith("/offline-events")) {
                    if (response.statusCode in 200..201) {
                        retainedOfflineCommit = request
                    }
                    if (dropOfflineCommitReply && response.statusCode in 200..201) {
                        dropOfflineCommitReply = false
                        // Qualifier fault: Laravel committed offline intake, client drops reply before recording ACK.
                        throw HttpTransportException(HttpTransportException.CATEGORY_NETWORK_IO, "Qualifier dropped offline committed reply")
                    }
                }
                return response
            }
        }

        val client = SmallTraderReadClient(transport, origin, allowHttpLoopback = true)
        val primaryLogin = loginRealStaff(
            client,
            device,
            credentials.require("email").asString(),
            credentials.require("password").asString()
        )
        val primaryToken = primaryLogin.token
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = JvmAesGcmCryptoEngine()

        // Production restricted entry must remain OFF unless explicitly enabled at build time.
        assertFalse(BuildConfig.OFFLINE_OWNER_PILOT)
        assertNull(RestrictedOfflineWorkspace.open(context, origin))

        var ilsCompany: ServerUlid? = null
        var ilsOnlineEventId: ClientUuid? = null
        var ilsLeaseId: ClientUuid? = null
        var ilsProductId: ServerUlid? = null
        var ilsProductUnitId: ServerUlid? = null
        var ilsWarehouseId: ServerUlid? = null
        var ilsCashAccountId: ServerUlid? = null
        var ilsWalkInCustomerId: ServerUlid? = null
        var ilsCatalogVersion: String? = null
        var ilsExclusiveTaxId: ServerUlid? = null
        var ilsMinorUnits: Int = 2
        var ilsOffline1Id: String? = null

        try {
            val fixtures = handoff.require("fixtures").asArray()
            assertEquals(3, fixtures.size)

            for (index in 0 until fixtures.size) {
                val fixture = fixtures[index].asObject()
                val key = fixture.require("key").asString()
                val company = ServerUlid.parse(fixture.require("company").asString())
                val bootstrap = requireSuccess(client.getBootstrap(primaryToken, company))
                assertEquals(fixture.require("currency").asString(), bootstrap.baseCurrency)

                val auth = CurrentAuthScope(company, primaryLogin.actor.id, device, primaryToken, 1L)
                val readyState = StaffAuthState.ReadReady(
                    session = StaffSessionRecord(
                        token = primaryToken,
                        actorUlid = primaryLogin.actor.id,
                        deviceUuid = device,
                        passwordEpoch = primaryLogin.actor.passwordEpoch,
                        expiresAt = primaryLogin.expiresAt,
                        selectedCompanyId = company,
                        roleLabel = bootstrap.staff.role,
                        actorName = bootstrap.staff.name,
                        actorEmail = primaryLogin.actor.email,
                        createdAt = Instant.now().toString()
                    ),
                    bootstrap = bootstrap
                )
                val readClient = MerchantReadClient(
                    transport = transport,
                    baseOrigin = origin,
                    allowHttpLoopback = true,
                    tokenProvider = MerchantReadTokenProvider { reqCompany, _ ->
                        if (reqCompany == company) primaryToken else null
                    }
                )

                val file = File(context.filesDir, "paired-$key-${ClientUuid.random().value}.db")
                fun open() = OutboxDatabase.buildDatabase(context, file.absolutePath, allowMainThreadQueries = true)
                var db = open()

                suspend fun buildRepository(requireLeaseAuth: Boolean = true): ProductionTraderRepository {
                    val lease = SqliteOfflineLeaseStore(crypto, bootIdentity = { "paired-jvm-boot" }) {
                        db.openHelper.writableDatabase
                    }
                    val outbox = OutboxRepositoryImpl(
                        database = db,
                        payloadCipher = OutboxPayloadCipher(crypto),
                        requireLeaseAuthorization = requireLeaseAuth,
                        leaseAuthority = { l, e -> lease.authorizesCapture(l, e) }
                    )
                    return ProductionTraderRepository.fromReadReady(
                        readReady = readyState,
                        outboxRepository = outbox,
                        transport = transport,
                        baseOrigin = origin,
                        commandStore = InMemoryMerchantCommandStore(),
                        authScopeProvider = { auth },
                        merchantReadClient = readClient,
                        offlineLeaseStore = lease
                    ).also { it.isOfflineSalesCapabilityEnabled = true }
                }

                var repo = buildRepository()
                try {
                    val cmu = repo.currencyMinorUnits
                    if (bootstrap.baseCurrency == "JOD") assertEquals(3, cmu) else assertEquals(2, cmu)

                    repo.prepareOfflineCatalog(2, 30) {}.getOrThrow()
                    val products = repo.getProducts()
                    val product = products.single { it.id == fixture.require("product").asString() }
                    val taxMode = fixture.require("offline_tax_mode").asString()

                    when (key) {
                        "ils_exclusive" -> {
                            ilsCompany = company
                            ilsMinorUnits = cmu
                            ilsProductId = ServerUlid.parse(product.id)
                            ilsProductUnitId = ServerUlid.parse(requireNotNull(product.productUnitId))
                            ilsWarehouseId = bootstrap.defaultWarehouse.id
                            ilsCashAccountId = bootstrap.approvedCashAccount.id
                            ilsWalkInCustomerId = bootstrap.walkInCustomerId
                            ilsCatalogVersion = bootstrap.catalogSnapshotVersion

                            val exclTaxId = ServerUlid.parse(requireNotNull(fixture.optionalString("tax_exclusive")))
                            val inclTaxId = ServerUlid.parse(requireNotNull(fixture.optionalString("tax_inclusive")))
                            ilsExclusiveTaxId = exclTaxId
                            val exclTax = bootstrap.taxes.single { it.id == exclTaxId }
                            val inclTax = bootstrap.taxes.single { it.id == inclTaxId }
                            assertEquals("exclusive", exclTax.calculation)
                            assertEquals("inclusive", inclTax.calculation)

                            // 1) Online ILS exclusive tax + fractional qty + post-commit reply drop + status recovery + replay + 409
                            val onlineExclItem = BasketItem(
                                product = product,
                                quantity = BigDecimal("1.5"),
                                unitPriceExact = BigDecimal("10.00"),
                                taxRateId = exclTax.id.value,
                                taxRateExact = exclTax.rate.value,
                                isTaxInclusive = false
                            )
                            dropOnlineCommitReply = true
                            val held = repo.recordSale(invoiceFor(listOf(onlineExclItem), cmu)).getOrThrow()
                            assertEquals("unknown_ack", held.outboxStatus)
                            assertNull(held.serverInvoiceUlid)
                            val online1 = repo.checkHeldSaleStatus(requireNotNull(held.clientEventId)).getOrThrow()
                            val invUlid = requireNotNull(online1.serverInvoiceUlid)
                            val payUlid = requireNotNull(online1.serverPaymentUlid)
                            assertNotEquals(invUlid, payUlid)
                            ilsOnlineEventId = ClientUuid.parse(requireNotNull(online1.clientEventId))

                            // Idempotent status check yields identical server IDs
                            val recheck = repo.checkHeldSaleStatus(requireNotNull(online1.clientEventId)).getOrThrow()
                            assertEquals(invUlid, recheck.serverInvoiceUlid)
                            assertEquals(payUlid, recheck.serverPaymentUlid)

                            // Same-event HTTP replay returns 200 with identical IDs; changed payload returns 409
                            val origOnline = requireNotNull(retainedOnlineCommit)
                            val replayResp = upstream.execute(origOnline)
                            assertEquals(200, replayResp.statusCode)
                            val replayObj = StrictJsonParser.parseObject(replayResp.body)
                            assertEquals(invUlid, replayObj.require("invoice_id").asString())
                            assertEquals(payUlid, replayObj.require("payment_id").asString())
                            assertDecimal6("17.400000", replayObj.require("grand_total").asString())
                            assertDecimal6("17.400000", replayObj.require("amount_paid").asString())
                            assertDecimal6("0.000000", replayObj.require("change_returned").asString())
                            assertOnlineWireTotals(ilsOnlineEventId.value, "17.400000")
                            val origOnlineBody = requireNotNull(origOnline.body)
                            val changedOnlineBody = origOnlineBody.replace("10.000000", "11.000000")
                            check(changedOnlineBody != origOnlineBody) { "Expected retained online price lexeme" }
                            assertEquals(409, upstream.execute(origOnline.copy(body = changedOnlineBody)).statusCode)

                            // 2) Online ILS inclusive tax + fractional qty
                            val onlineInclItem = BasketItem(
                                product = product,
                                quantity = BigDecimal("1.25"),
                                unitPriceExact = BigDecimal("10.00"),
                                taxRateId = inclTax.id.value,
                                taxRateExact = inclTax.rate.value,
                                isTaxInclusive = true
                            )
                            val online2 = repo.recordSale(invoiceFor(listOf(onlineInclItem), cmu)).getOrThrow()
                            assertEquals("CONFIRMED", online2.outboxStatus)
                            assertNotNull(online2.serverInvoiceUlid)
                            assertNotNull(online2.serverPaymentUlid)
                            assertOnlineWireTotals(requireNotNull(online2.clientEventId), "12.500000")

                            // 3) Online ILS noTax + fractional qty
                            val onlineNoTaxItem = BasketItem(
                                product = product,
                                quantity = BigDecimal("0.75"),
                                unitPriceExact = BigDecimal("10.00"),
                                taxRateId = null,
                                taxRateExact = BigDecimal.ZERO,
                                isTaxInclusive = false
                            )
                            val online3 = repo.recordSale(invoiceFor(listOf(onlineNoTaxItem), cmu)).getOrThrow()
                            assertEquals("CONFIRMED", online3.outboxStatus)
                            assertNotNull(online3.serverInvoiceUlid)
                            assertOnlineWireTotals(requireNotNull(online3.clientEventId), "7.500000")

                            // 4) Offline lease + RestrictedOfflineWorkspace test-injected Keystore/boot/device gate (no fake auth!)
                            val lease = repo.acquireOfflineSaleLease().getOrThrow()
                            ilsLeaseId = lease.leaseId
                            assertEquals(exclTax.id, lease.taxRateId)
                            assertEquals("exclusive", lease.taxCalculation)

                            val restrictedDir = File(context.filesDir, "paired-restricted-${ClientUuid.random().value}").apply { mkdirs() }
                            try {
                                val backendKey: SecretKey = requireNotNull(crypto.secretKey)
                                val testKeystoreCrypto = AndroidKeyStoreCryptoEngine(
                                    keyAlias = "paired_restricted_test_key",
                                    backend = object : KeyStoreBackend {
                                        override fun getKey(alias: String): SecretKey = backendKey
                                        override fun generateKey(alias: String): SecretKey = backendKey
                                    }
                                )
                                val identityFile = File(restrictedDir, "identity.json").apply {
                                    writeText("""{"device_id":"${device.value}"}""")
                                }
                                val sessionFile = File(restrictedDir, "session.bin")
                                val bootstrapFile = File(restrictedDir, "bootstrap.bin")
                                val sessionStore = DefaultSecureStaffSessionStore(
                                    sessionFile = sessionFile,
                                    installationStore = InstallationIdentityStore(identityFile),
                                    cryptoEngine = testKeystoreCrypto,
                                    packageName = context.packageName
                                )
                                sessionStore.saveSession(readyState.session)
                                val leaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "paired-jvm-boot" }) {
                                    db.openHelper.writableDatabase
                                }
                                val bootstrapStore = RestrictedOfflineBootstrapStore(
                                    file = AtomicFile(bootstrapFile),
                                    crypto = testKeystoreCrypto,
                                    sessions = sessionStore
                                )
                                bootstrapStore.save(repo, lease, leaseStore)
                                repo.close(); db.close()

                                db = open()
                                val restrictedLeases = SqliteOfflineLeaseStore(crypto, bootIdentity = { "paired-jvm-boot" }) {
                                    db.openHelper.writableDatabase
                                }
                                val workspace = RestrictedOfflineWorkspace.compose(
                                    db = db,
                                    leases = restrictedLeases,
                                    bootstraps = bootstrapStore,
                                    baseOrigin = origin,
                                    ownerPilotEnabled = true,
                                    payloadCipher = OutboxPayloadCipher(crypto),
                                    commandStore = RoomMerchantCommandStore(db, testKeystoreCrypto)
                                )!!
                                try {
                                    withTimeout(5000) {
                                        while (workspace.products.value.isEmpty()) delay(10)
                                    }
                                    val restrictedProduct = workspace.products.value.single { it.id == product.id }
                                    val restrictedLine = BasketItem(
                                        product = restrictedProduct,
                                        quantity = BigDecimal("1.5"),
                                        unitPriceExact = requireNotNull(restrictedProduct.priceExact),
                                        taxRateId = lease.taxRateId?.value,
                                        taxRateExact = lease.taxRate?.value ?: BigDecimal.ZERO,
                                        isTaxInclusive = lease.taxCalculation == "inclusive"
                                    )
                                    val restrictedCaptured = workspace.capture(
                                        invoiceFor(listOf(restrictedLine), cmu, lease.issuedAt)
                                    ).getOrThrow()
                                    assertEquals("pending", restrictedCaptured.outboxStatus)
                                    assertNotNull(restrictedCaptured.localReceiptNumber)
                                    assertEquals(1, workspace.pending().count)
                                    ilsOffline1Id = requireNotNull(restrictedCaptured.clientEventId)
                                } finally {
                                    workspace.close()
                                }
                            } finally {
                                restrictedDir.deleteRecursively()
                            }

                            // 5) Reopen repository offline, capture 2nd offline sale, recreate DB/repo, reconnect & sync (with offline reply drop)
                            db = open()
                            repo = buildRepository()
                            repo.isNetworkOffline = true
                            val offlineLine2 = BasketItem(
                                product = product,
                                quantity = BigDecimal("2.25"),
                                unitPriceExact = BigDecimal("10.00"),
                                taxRateId = exclTax.id.value,
                                taxRateExact = exclTax.rate.value,
                                isTaxInclusive = false
                            )
                            val offline2 = repo.recordSale(invoiceFor(listOf(offlineLine2), cmu)).getOrThrow()
                            assertEquals("pending", offline2.outboxStatus)
                            assertEquals(2, repo.getPendingOfflineSalesCount())

                            // Repository recreation across restart
                            repo.close(); db.close()
                            db = open()
                            repo = buildRepository()
                            assertEquals(2, repo.getPendingOfflineSalesCount())

                            // Reconnect: drop first POST /offline-events reply -> timeout -> second sync recovers via status & confirms both
                            repo.isNetworkOffline = false
                            dropOfflineCommitReply = true
                            val sync1 = repo.syncOfflineSales().getOrThrow()
                            assertEquals(0, sync1.confirmedCount)
                            assertEquals(1, sync1.timeoutCount)
                            assertTrue(sync1.stoppedEarly)

                            val sync2 = repo.syncOfflineSales().getOrThrow()
                            assertEquals(2, sync2.confirmedCount)
                            assertEquals(0, repo.getPendingOfflineSalesCount())
                            assertOfflineWireTotal(requireNotNull(ilsOffline1Id), "17.400000")
                            assertOfflineWireTotal(requireNotNull(offline2.clientEventId), "26.100000")

                            // Offline same-event replay (200 same IDs) and changed-payload 409 (zero extra)
                            val origOffline = requireNotNull(retainedOfflineCommit)
                            val offReplay = upstream.execute(origOffline)
                            assertEquals(200, offReplay.statusCode)
                            val offReplayObj = StrictJsonParser.parseObject(offReplay.body)
                            val offOutcome = offReplayObj.require("outcome").asObject()
                            val offRootObj = StrictJsonParser.parseObject(requireNotNull(origOffline.body))
                            val origEventObj = offRootObj.require("event").asObject()
                            val origFirstLine = origEventObj.require("lines").asArray()[0].asObject()
                            val mutatedPrice = MoneyAmount("11.000000")
                            val mutatedCalc = SalesLineCalculator.calculate(
                                quantity = PositiveDecimalQuantity.fromPositiveString(origFirstLine.require("quantity").asString()),
                                unitPrice = mutatedPrice,
                                taxPercentage = TaxPercentage.fromString(origFirstLine.require("tax_rate").asString()),
                                taxInclusive = (origFirstLine.require("tax_inclusive") as StrictJsonBoolean).value,
                                minorUnits = cmu
                            )
                            val mutatedLine = StrictJsonObject(
                                origFirstLine.properties.toMutableMap().apply {
                                    put("unit_price", StrictJsonString(mutatedPrice.toPlainString()))
                                    put("tax_amount", StrictJsonString(mutatedCalc.taxAmount.toPlainString()))
                                    put("line_total", StrictJsonString(mutatedCalc.lineTotal.toPlainString()))
                                }
                            )
                            val origPaymentObj = origEventObj.require("payment").asObject()
                            val mutatedPayment = StrictJsonObject(
                                origPaymentObj.properties.toMutableMap().apply {
                                    put("amount_tendered", StrictJsonString(mutatedCalc.lineTotal.toPlainString()))
                                    put("amount_settled", StrictJsonString(mutatedCalc.lineTotal.toPlainString()))
                                    put("change_returned", StrictJsonString("0.000000"))
                                }
                            )
                            val mutatedEvent = StrictJsonObject(
                                origEventObj.properties.toMutableMap().apply {
                                    put("lines", StrictJsonArray(listOf(mutatedLine)))
                                    put("payment", mutatedPayment)
                                }
                            )
                            val mutatedDigest = PayloadDigest.fromBytes(
                                CanonicalJsonSerializer.serialize(mutatedEvent).toByteArray(StandardCharsets.UTF_8)
                            )
                            val changedOffReq = StrictJsonObject(
                                offRootObj.properties.toMutableMap().apply {
                                    put("event", mutatedEvent)
                                    put("payload_digest", StrictJsonString(mutatedDigest.value))
                                }
                            )
                            val offConflict = upstream.execute(
                                origOffline.copy(body = CanonicalJsonSerializer.serialize(changedOffReq))
                            )
                            assertEquals(409, offConflict.statusCode)
                            assertEquals(
                                offOutcome.require("invoice_id").asString(),
                                StrictJsonParser.parseObject(upstream.execute(origOffline).body)
                                    .require("outcome").asObject().require("invoice_id").asString()
                            )

                            // 6) Stock conflict (STOCK_UNAVAILABLE): capture offline qty 2.5 > 1.0 server stock -> sync -> held with original bytes & reason
                            val lowStockId = requireNotNull(fixture.optionalString("low_stock_product"))
                            val lowStockProduct = products.single { it.id == lowStockId }
                            repo.isNetworkOffline = true
                            val stockConflictLine = BasketItem(
                                product = lowStockProduct,
                                quantity = BigDecimal("2.5"),
                                unitPriceExact = BigDecimal("10.00"),
                                taxRateId = exclTax.id.value,
                                taxRateExact = exclTax.rate.value,
                                isTaxInclusive = false
                            )
                            val stockCaptured = repo.recordSale(invoiceFor(listOf(stockConflictLine), cmu)).getOrThrow()
                            val stockEventId = ClientUuid.parse(requireNotNull(stockCaptured.clientEventId))
                            val outbox = OutboxRepositoryImpl(db, payloadCipher = OutboxPayloadCipher(crypto))
                            val stockOriginalRecord = requireNotNull(outbox.getRecord(company, auth.actorId, device, stockEventId))

                            repo.isNetworkOffline = false
                            observedExchanges.clear()
                            val stockSync = repo.syncOfflineSales().getOrThrow()
                            assertEquals(0, stockSync.confirmedCount)
                            assertEquals(1, stockSync.conflictCount)
                            val stockAfter = requireNotNull(outbox.getRecord(company, auth.actorId, device, stockEventId))
                            assertEquals(OutboxStatus.CONFLICT, stockAfter.status)
                            assertEquals(SyncDiagnostics.BUSINESS_CONFLICT, stockAfter.deliveryState.errorMessage)
                            assertEquals(stockOriginalRecord.payloadDigest, stockAfter.payloadDigest)
                            assertArrayEquals(stockOriginalRecord.payloadBytes, stockAfter.payloadBytes)
                            assertTrue(repo.getRejectedCashSales().any { it.clientEventId == stockEventId.value })
                            assertTrue(
                                observedExchanges.any {
                                    it.method == HttpMethod.POST &&
                                        it.path.endsWith("/offline-events") &&
                                        it.statusCode == 409 &&
                                        it.body.contains("STOCK_UNAVAILABLE")
                                }
                            )

                            // 7) Stale tax conflict (STALE_TAX): offline event with non-lease tax_rate_id -> sync -> held with original bytes & reason
                            val staleTaxLineCalc = SalesLineCalculator.calculate(
                                quantity = PositiveDecimalQuantity("1.500000"),
                                unitPrice = MoneyAmount("10.000000"),
                                taxPercentage = inclTax.rate,
                                taxInclusive = true,
                                minorUnits = cmu
                            )
                            val staleTaxEventId = ClientUuid.random()
                            val staleTaxEvent = OfflineSaleEventV2(
                                clientEventId = staleTaxEventId,
                                eventVersion = 2,
                                companyId = company,
                                actorId = auth.actorId,
                                deviceId = device,
                                deviceLeaseId = lease.leaseId,
                                capturedAt = lease.issuedAt,
                                customerId = bootstrap.walkInCustomerId,
                                currency = lease.currency,
                                warehouseId = lease.warehouseId,
                                cashAccountId = lease.cashAccountId,
                                catalogSnapshotVersion = lease.catalogSnapshotVersion,
                                localReceiptNumber = "LOCAL-STALE-TAX",
                                lines = listOf(
                                    OfflineSaleLineV2(
                                        productId = ServerUlid.parse(product.id),
                                        productUnitId = ServerUlid.parse(requireNotNull(product.productUnitId)),
                                        unitId = ServerUlid.parse(requireNotNull(product.productUnitId)),
                                        conversionToBase = PositiveDecimalQuantity("1.000000"),
                                        quantity = PositiveDecimalQuantity("1.500000"),
                                        unitPrice = MoneyAmount("10.000000"),
                                        taxRateId = inclTax.id,
                                        taxRate = inclTax.rate,
                                        taxInclusive = true,
                                        taxAmount = staleTaxLineCalc.taxAmount,
                                        lineTotal = staleTaxLineCalc.lineTotal
                                    )
                                ),
                                payment = OfflineSalePaymentV2(
                                    mode = "full",
                                    paymentMethod = "cash",
                                    cashAccountId = lease.cashAccountId,
                                    amountTendered = staleTaxLineCalc.lineTotal,
                                    amountSettled = staleTaxLineCalc.lineTotal,
                                    changeReturned = MoneyAmount("0.000000")
                                )
                            )
                            val staleOriginalBytes = CanonicalJsonSerializer.serialize(staleTaxEvent).toByteArray(StandardCharsets.UTF_8)
                            val staleOriginalDigest = staleTaxEvent.computePayloadDigest().value
                            val unguardedOutbox = OutboxRepositoryImpl(
                                database = db,
                                payloadCipher = OutboxPayloadCipher(crypto),
                                requireLeaseAuthorization = false
                            )
                            assertTrue(unguardedOutbox.captureLeaseSale(lease, staleTaxEvent) {} is CaptureResult.Created)

                            observedExchanges.clear()
                            val staleSync = repo.syncOfflineSales().getOrThrow()
                            assertEquals(0, staleSync.confirmedCount)
                            assertEquals(1, staleSync.conflictCount)
                            val staleAfter = requireNotNull(outbox.getRecord(company, auth.actorId, device, staleTaxEventId))
                            assertEquals(OutboxStatus.CONFLICT, staleAfter.status)
                            assertEquals(SyncDiagnostics.BUSINESS_CONFLICT, staleAfter.deliveryState.errorMessage)
                            assertEquals(staleOriginalDigest, staleAfter.payloadDigest)
                            assertArrayEquals(staleOriginalBytes, staleAfter.payloadBytes)
                            assertEquals(2, repo.getRejectedCashSales().size)
                            assertTrue(
                                observedExchanges.any {
                                    it.method == HttpMethod.POST &&
                                        it.path.endsWith("/offline-events") &&
                                        it.statusCode == 409 &&
                                        it.body.contains("STALE_TAX")
                                }
                            )
                        }

                        "jod_inclusive" -> {
                            val exclTax = bootstrap.taxes.single {
                                it.id == ServerUlid.parse(requireNotNull(fixture.optionalString("tax_exclusive")))
                            }
                            val inclTax = bootstrap.taxes.single {
                                it.id == ServerUlid.parse(requireNotNull(fixture.optionalString("tax_inclusive")))
                            }
                            // Online JOD: inclusive (1.375), exclusive (1.125), noTax (0.375)
                            val jodOnline1 = repo.recordSale(
                                invoiceFor(
                                    listOf(BasketItem(product, BigDecimal("1.375"), BigDecimal("10.00"), inclTax.id.value, inclTax.rate.value, true)),
                                    cmu
                                )
                            ).getOrThrow()
                            val jodOnline2 = repo.recordSale(
                                invoiceFor(
                                    listOf(BasketItem(product, BigDecimal("1.125"), BigDecimal("10.00"), exclTax.id.value, exclTax.rate.value, false)),
                                    cmu
                                )
                            ).getOrThrow()
                            val jodOnline3 = repo.recordSale(
                                invoiceFor(
                                    listOf(BasketItem(product, BigDecimal("0.375"), BigDecimal("10.00"), null, BigDecimal.ZERO, false)),
                                    cmu
                                )
                            ).getOrThrow()
                            assertEquals("CONFIRMED", jodOnline1.outboxStatus)
                            assertEquals("CONFIRMED", jodOnline2.outboxStatus)
                            assertEquals("CONFIRMED", jodOnline3.outboxStatus)
                            assertOnlineWireTotals(requireNotNull(jodOnline1.clientEventId), "13.750000")
                            assertOnlineWireTotals(requireNotNull(jodOnline2.clientEventId), "13.050000")
                            assertOnlineWireTotals(requireNotNull(jodOnline3.clientEventId), "3.750000")

                            // Offline JOD inclusive + fractional quantities (multiple + repo recreation + reconnect)
                            val lease = repo.acquireOfflineSaleLease().getOrThrow()
                            assertEquals(3, lease.currencyMinorUnits)
                            assertEquals(inclTax.id, lease.taxRateId)
                            assertEquals("inclusive", lease.taxCalculation)

                            repo.isNetworkOffline = true
                            val jodOff1 = repo.recordSale(
                                invoiceFor(
                                    listOf(BasketItem(product, BigDecimal("1.375"), BigDecimal("10.00"), inclTax.id.value, inclTax.rate.value, true)),
                                    cmu
                                )
                            ).getOrThrow()
                            val jodOff2 = repo.recordSale(
                                invoiceFor(
                                    listOf(BasketItem(product, BigDecimal("2.125"), BigDecimal("10.00"), inclTax.id.value, inclTax.rate.value, true)),
                                    cmu
                                )
                            ).getOrThrow()
                            assertEquals("pending", jodOff1.outboxStatus)
                            assertEquals("pending", jodOff2.outboxStatus)
                            assertEquals(2, repo.getPendingOfflineSalesCount())

                            repo.close(); db.close()
                            db = open(); repo = buildRepository()
                            assertEquals(2, repo.getPendingOfflineSalesCount())
                            repo.isNetworkOffline = false
                            assertEquals(2, repo.syncOfflineSales().getOrThrow().confirmedCount)
                            assertEquals(0, repo.getPendingOfflineSalesCount())
                            assertOfflineWireTotal(requireNotNull(jodOff1.clientEventId), "13.750000")
                            assertOfflineWireTotal(requireNotNull(jodOff2.clientEventId), "21.250000")
                        }

                        "ils_notax" -> {
                            assertEquals("notax", taxMode)
                            val lease = repo.acquireOfflineSaleLease().getOrThrow()
                            assertNull(lease.taxRateId)

                            repo.isNetworkOffline = true
                            val noTaxIds = ArrayList<String>(2)
                            repeat(2) { idx ->
                                val qty = if (idx == 0) BigDecimal("1.5") else BigDecimal("2.5")
                                val local = repo.recordSale(
                                    invoiceFor(
                                        listOf(BasketItem(product, qty, BigDecimal("10.00"), null, BigDecimal.ZERO, false)),
                                        cmu
                                    )
                                ).getOrThrow()
                                assertEquals("pending", local.outboxStatus)
                                assertNull(local.serverInvoiceUlid)
                                assertNotNull(local.localReceiptNumber)
                                noTaxIds.add(requireNotNull(local.clientEventId))
                            }
                            assertEquals(2, repo.getPendingOfflineSalesCount())
                            repo.close(); db.close()
                            db = open(); repo = buildRepository()
                            assertEquals(2, repo.getPendingOfflineSalesCount())
                            repo.isNetworkOffline = false
                            assertEquals(2, repo.syncOfflineSales().getOrThrow().confirmedCount)
                            assertEquals(0, repo.getPendingOfflineSalesCount())
                            assertOfflineWireTotal(noTaxIds[0], "15.000000")
                            assertOfflineWireTotal(noTaxIds[1], "25.000000")
                        }

                        else -> error("Unexpected fixture key: $key")
                    }
                } finally {
                    repo.close()
                    db.close()
                    context.deleteDatabase(file.absolutePath)
                }
            }

            // -----------------------------------------------------------------
            // Cross-company, Cross-actor, Cross-device, Token/Device Revocation
            // -----------------------------------------------------------------
            val targetCompany = requireNotNull(ilsCompany)
            val targetOnlineEvent = requireNotNull(ilsOnlineEventId)
            val targetLeaseId = requireNotNull(ilsLeaseId)
            val targetProductId = requireNotNull(ilsProductId)
            val targetProductUnitId = requireNotNull(ilsProductUnitId)
            val targetWarehouseId = requireNotNull(ilsWarehouseId)
            val targetCashAccountId = requireNotNull(ilsCashAccountId)
            val targetCustomerId = requireNotNull(ilsWalkInCustomerId)
            val targetCatalogVersion = requireNotNull(ilsCatalogVersion)
            val targetTaxId = requireNotNull(ilsExclusiveTaxId)

            // A. Cross-company denial against FOREIGN company (zero membership)
            assertTrue(client.getBootstrap(primaryToken, foreignCompany) is ReadClientResult.Forbidden403)
            val foreignOnlineResp = upstream.execute(
                TransportRequest(
                    method = HttpMethod.POST,
                    path = "/api/mobile/v2/companies/${foreignCompany.value}/sales/online-cash",
                    baseOrigin = origin,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                        "Authorization" to "Bearer $primaryToken"
                    ),
                    body = OnlineSaleIntentProfile(
                        actorId = primaryLogin.actor.id,
                        clientEventId = ClientUuid.random(),
                        companyId = foreignCompany,
                        currency = "ILS",
                        currencyMinorUnits = ilsMinorUnits,
                        customerId = targetCustomerId,
                        deviceId = device,
                        lines = listOf(
                            OnlineSaleIntentLine(
                                productId = targetProductId,
                                productUnitId = targetProductUnitId,
                                conversionToBase = PositiveDecimalQuantity("1.000000"),
                                quantity = PositiveDecimalQuantity("1.000000"),
                                unitPrice = MoneyAmount("10.000000"),
                                taxRateId = null,
                                taxRate = TaxPercentage("0.000000"),
                                taxInclusive = false
                            )
                        ),
                        payment = OnlineSaleIntentPayment("full", "cash", targetCashAccountId, MoneyAmount("10.000000")),
                        warehouseId = targetWarehouseId
                    ).toWireRequestJson()
                )
            )
            assertEquals(403, foreignOnlineResp.statusCode)
            val foreignLeaseResp = upstream.execute(
                TransportRequest(
                    method = HttpMethod.POST,
                    path = "/api/mobile/v2/companies/${foreignCompany.value}/offline-sale-lease",
                    baseOrigin = origin,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                        "Authorization" to "Bearer $primaryToken"
                    ),
                    body = """{"catalog_snapshot_version":"$targetCatalogVersion"}"""
                )
            )
            assertEquals(403, foreignLeaseResp.statusCode)
            val foreignStatusResp = upstream.execute(
                TransportRequest(
                    method = HttpMethod.GET,
                    path = "/api/mobile/v2/companies/${foreignCompany.value}/offline-events/${targetOnlineEvent.value}/status",
                    baseOrigin = origin,
                    headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $primaryToken")
                )
            )
            assertEquals(403, foreignStatusResp.statusCode)

            // Helper to build a signed OfflineSaleEventV2 wire intake body for cross-actor / cross-device probes
            fun offlineIntakeWire(actor: ServerUlid, dev: ClientUuid, leaseId: ClientUuid): String {
                val evId = ClientUuid.random()
                val calc = SalesLineCalculator.calculate(
                    PositiveDecimalQuantity("1.000000"),
                    MoneyAmount("10.000000"),
                    TaxPercentage("16.000000"),
                    taxInclusive = false,
                    minorUnits = ilsMinorUnits
                )
                val ev = OfflineSaleEventV2(
                    clientEventId = evId,
                    eventVersion = 2,
                    companyId = targetCompany,
                    actorId = actor,
                    deviceId = dev,
                    deviceLeaseId = leaseId,
                    capturedAt = Instant.now().toString(),
                    customerId = targetCustomerId,
                    currency = "ILS",
                    warehouseId = targetWarehouseId,
                    cashAccountId = targetCashAccountId,
                    catalogSnapshotVersion = targetCatalogVersion,
                    localReceiptNumber = "LOCAL-CROSS",
                    lines = listOf(
                        OfflineSaleLineV2(
                            productId = targetProductId,
                            productUnitId = targetProductUnitId,
                            unitId = targetProductUnitId,
                            conversionToBase = PositiveDecimalQuantity("1.000000"),
                            quantity = PositiveDecimalQuantity("1.000000"),
                            unitPrice = MoneyAmount("10.000000"),
                            taxRateId = targetTaxId,
                            taxRate = TaxPercentage("16.000000"),
                            taxInclusive = false,
                            taxAmount = calc.taxAmount,
                            lineTotal = calc.lineTotal
                        )
                    ),
                    payment = OfflineSalePaymentV2(
                        mode = "full",
                        paymentMethod = "cash",
                        cashAccountId = targetCashAccountId,
                        amountTendered = calc.lineTotal,
                        amountSettled = calc.lineTotal,
                        changeReturned = MoneyAmount("0.000000")
                    )
                )
                return CanonicalJsonSerializer.serialize(
                    OfflineSaleIntakeRequestV2(
                        clientEventId = evId,
                        deviceId = dev,
                        capturedAt = ev.capturedAt,
                        payloadDigest = ev.computePayloadDigest(),
                        event = ev
                    )
                )
            }

            // B. Cross-actor denial (otherStaff on same ILS company & otherDevice)
            val otherLogin = loginRealStaff(
                client,
                otherDevice,
                credentials.require("other_email").asString(),
                credentials.require("other_password").asString()
            )
            try {
                val crossActorStatus = upstream.execute(
                    TransportRequest(
                        method = HttpMethod.GET,
                        path = "/api/mobile/v2/companies/${targetCompany.value}/offline-events/${targetOnlineEvent.value}/status",
                        baseOrigin = origin,
                        headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer ${otherLogin.token}")
                    )
                )
                assertEquals(403, crossActorStatus.statusCode)

                val crossActorOnlineRetry = upstream.execute(
                    TransportRequest(
                        method = HttpMethod.POST,
                        path = "/api/mobile/v2/companies/${targetCompany.value}/sales/online-cash",
                        baseOrigin = origin,
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Content-Type" to "application/json; charset=utf-8",
                            "Authorization" to "Bearer ${otherLogin.token}"
                        ),
                        body = OnlineSaleIntentProfile(
                            actorId = otherLogin.actor.id,
                            clientEventId = targetOnlineEvent,
                            companyId = targetCompany,
                            currency = "ILS",
                            currencyMinorUnits = ilsMinorUnits,
                            customerId = targetCustomerId,
                            deviceId = otherDevice,
                            lines = listOf(
                                OnlineSaleIntentLine(
                                    productId = targetProductId,
                                    productUnitId = targetProductUnitId,
                                    conversionToBase = PositiveDecimalQuantity("1.000000"),
                                    quantity = PositiveDecimalQuantity("1.000000"),
                                    unitPrice = MoneyAmount("10.000000"),
                                    taxRateId = null,
                                    taxRate = TaxPercentage("0.000000"),
                                    taxInclusive = false
                                )
                            ),
                            payment = OnlineSaleIntentPayment("full", "cash", targetCashAccountId, MoneyAmount("10.000000")),
                            warehouseId = targetWarehouseId
                        ).toWireRequestJson()
                    )
                )
                assertEquals(403, crossActorOnlineRetry.statusCode)

                val crossActorOffline = upstream.execute(
                    TransportRequest(
                        method = HttpMethod.POST,
                        path = "/api/mobile/v2/companies/${targetCompany.value}/offline-events",
                        baseOrigin = origin,
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Content-Type" to "application/json; charset=utf-8",
                            "Authorization" to "Bearer ${otherLogin.token}"
                        ),
                        body = offlineIntakeWire(otherLogin.actor.id, otherDevice, targetLeaseId)
                    )
                )
                assertTrue("Expected 403/409 for cross-actor lease, got ${crossActorOffline.statusCode}",
                    crossActorOffline.statusCode in setOf(403, 409))
            } finally {
                requireSuccess(client.authLogout(otherLogin.token, AuthLogoutRequest(otherDevice)))
            }

            // C. Cross-device denial + Token Revocation (logout) with local cash retained
            val secondLogin = loginRealStaff(
                client,
                secondDevice,
                credentials.require("email").asString(),
                credentials.require("password").asString()
            )
            val crossDeviceStatus = upstream.execute(
                TransportRequest(
                    method = HttpMethod.GET,
                    path = "/api/mobile/v2/companies/${targetCompany.value}/offline-events/${targetOnlineEvent.value}/status",
                    baseOrigin = origin,
                    headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer ${secondLogin.token}")
                )
            )
            assertEquals(403, crossDeviceStatus.statusCode)

            val crossDeviceOffline = upstream.execute(
                TransportRequest(
                    method = HttpMethod.POST,
                    path = "/api/mobile/v2/companies/${targetCompany.value}/offline-events",
                    baseOrigin = origin,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json; charset=utf-8",
                        "Authorization" to "Bearer ${secondLogin.token}"
                    ),
                    body = offlineIntakeWire(secondLogin.actor.id, secondDevice, targetLeaseId)
                )
            )
            assertTrue("Expected 403/409 for cross-device lease, got ${crossDeviceOffline.statusCode}",
                crossDeviceOffline.statusCode in setOf(403, 409))

            // Token revoke: capture offline cash on secondDevice, revoke bearer via authLogout, sync fails with authBlocked & retains local cash
            val secondBootstrap = requireSuccess(client.getBootstrap(secondLogin.token, targetCompany))
            val secondDbFile = File(context.filesDir, "paired-tok-revoke-${ClientUuid.random().value}.db")
            val secondDb = OutboxDatabase.buildDatabase(context, secondDbFile.absolutePath, allowMainThreadQueries = true)
            val secondAuth = CurrentAuthScope(targetCompany, secondLogin.actor.id, secondDevice, secondLogin.token, 1L)
            val secondLeaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "paired-jvm-boot" }) {
                secondDb.openHelper.writableDatabase
            }
            val secondOutbox = OutboxRepositoryImpl(
                database = secondDb,
                payloadCipher = OutboxPayloadCipher(crypto),
                requireLeaseAuthorization = true,
                leaseAuthority = { l, e -> secondLeaseStore.authorizesCapture(l, e) }
            )
            val secondRepo = ProductionTraderRepository.fromReadReady(
                readReady = StaffAuthState.ReadReady(
                    session = StaffSessionRecord(
                        token = secondLogin.token,
                        actorUlid = secondLogin.actor.id,
                        deviceUuid = secondDevice,
                        passwordEpoch = secondLogin.actor.passwordEpoch,
                        expiresAt = secondLogin.expiresAt,
                        selectedCompanyId = targetCompany,
                        roleLabel = secondBootstrap.staff.role,
                        actorName = secondBootstrap.staff.name,
                        actorEmail = secondLogin.actor.email,
                        createdAt = Instant.now().toString()
                    ),
                    bootstrap = secondBootstrap
                ),
                outboxRepository = secondOutbox,
                transport = transport,
                baseOrigin = origin,
                commandStore = InMemoryMerchantCommandStore(),
                authScopeProvider = { secondAuth },
                merchantReadClient = MerchantReadClient(
                    transport = transport,
                    baseOrigin = origin,
                    allowHttpLoopback = true,
                    tokenProvider = MerchantReadTokenProvider { reqCompany, _ ->
                        if (reqCompany == targetCompany) secondLogin.token else null
                    }
                ),
                offlineLeaseStore = secondLeaseStore
            ).also { it.isOfflineSalesCapabilityEnabled = true }
            try {
                secondRepo.prepareOfflineCatalog(2, 30) {}.getOrThrow()
                val secondLease = secondRepo.acquireOfflineSaleLease().getOrThrow()
                val exclTax = secondBootstrap.taxes.single { it.id == requireNotNull(secondLease.taxRateId) }
                val prod = secondRepo.getProducts().single { it.id == targetProductId.value }
                secondRepo.isNetworkOffline = true
                val retainedLocal = secondRepo.recordSale(
                    invoiceFor(
                        listOf(BasketItem(prod, BigDecimal("1.5"), BigDecimal("10.00"), exclTax.id.value, exclTax.rate.value, false)),
                        secondRepo.currencyMinorUnits
                    )
                ).getOrThrow()
                val evId = ClientUuid.parse(requireNotNull(retainedLocal.clientEventId))
                val beforeRevoke = requireNotNull(secondOutbox.getRecord(targetCompany, secondLogin.actor.id, secondDevice, evId))

                requireSuccess(client.authLogout(secondLogin.token, AuthLogoutRequest(secondDevice)))
                secondRepo.isNetworkOffline = false
                val revokedSync = secondRepo.syncOfflineSales().getOrThrow()
                assertTrue(revokedSync.authBlocked)
                assertEquals(0, revokedSync.confirmedCount)
                assertTrue(secondOutbox.isQueueLocked(targetCompany, secondLogin.actor.id, secondDevice))
                val afterRevoke = requireNotNull(secondOutbox.getRecord(targetCompany, secondLogin.actor.id, secondDevice, evId))
                assertEquals(OutboxStatus.AUTH_BLOCKED, afterRevoke.status)
                assertEquals(beforeRevoke.payloadDigest, afterRevoke.payloadDigest)
                assertArrayEquals(beforeRevoke.payloadBytes, afterRevoke.payloadBytes)
            } finally {
                secondRepo.close()
                secondDb.close()
                context.deleteDatabase(secondDbFile.absolutePath)
            }

            // D. Device Revocation (DELETE /api/mobile/v1/sessions/{deviceId}) zero effects + local cash retained + re-login denied
            val devLogin = loginRealStaff(
                client,
                revokedDevice,
                credentials.require("device_email").asString(),
                credentials.require("device_password").asString()
            )
            val devBootstrap = requireSuccess(client.getBootstrap(devLogin.token, targetCompany))
            val devDbFile = File(context.filesDir, "paired-dev-revoke-${ClientUuid.random().value}.db")
            val devDb = OutboxDatabase.buildDatabase(context, devDbFile.absolutePath, allowMainThreadQueries = true)
            val devAuth = CurrentAuthScope(targetCompany, devLogin.actor.id, revokedDevice, devLogin.token, 1L)
            val devLeaseStore = SqliteOfflineLeaseStore(crypto, bootIdentity = { "paired-jvm-boot" }) {
                devDb.openHelper.writableDatabase
            }
            val devOutbox = OutboxRepositoryImpl(
                database = devDb,
                payloadCipher = OutboxPayloadCipher(crypto),
                requireLeaseAuthorization = true,
                leaseAuthority = { l, e -> devLeaseStore.authorizesCapture(l, e) }
            )
            val devRepo = ProductionTraderRepository.fromReadReady(
                readReady = StaffAuthState.ReadReady(
                    session = StaffSessionRecord(
                        token = devLogin.token,
                        actorUlid = devLogin.actor.id,
                        deviceUuid = revokedDevice,
                        passwordEpoch = devLogin.actor.passwordEpoch,
                        expiresAt = devLogin.expiresAt,
                        selectedCompanyId = targetCompany,
                        roleLabel = devBootstrap.staff.role,
                        actorName = devBootstrap.staff.name,
                        actorEmail = devLogin.actor.email,
                        createdAt = Instant.now().toString()
                    ),
                    bootstrap = devBootstrap
                ),
                outboxRepository = devOutbox,
                transport = transport,
                baseOrigin = origin,
                commandStore = InMemoryMerchantCommandStore(),
                authScopeProvider = { devAuth },
                merchantReadClient = MerchantReadClient(
                    transport = transport,
                    baseOrigin = origin,
                    allowHttpLoopback = true,
                    tokenProvider = MerchantReadTokenProvider { reqCompany, _ ->
                        if (reqCompany == targetCompany) devLogin.token else null
                    }
                ),
                offlineLeaseStore = devLeaseStore
            ).also { it.isOfflineSalesCapabilityEnabled = true }
            try {
                devRepo.prepareOfflineCatalog(2, 30) {}.getOrThrow()
                val devLease = devRepo.acquireOfflineSaleLease().getOrThrow()
                val exclTax = devBootstrap.taxes.single { it.id == requireNotNull(devLease.taxRateId) }
                val prod = devRepo.getProducts().single { it.id == targetProductId.value }
                devRepo.isNetworkOffline = true
                val devCaptured = devRepo.recordSale(
                    invoiceFor(
                        listOf(BasketItem(prod, BigDecimal("1.5"), BigDecimal("10.00"), exclTax.id.value, exclTax.rate.value, false)),
                        devRepo.currencyMinorUnits
                    )
                ).getOrThrow()
                val devEvId = ClientUuid.parse(requireNotNull(devCaptured.clientEventId))
                val beforeDevRevoke = requireNotNull(devOutbox.getRecord(targetCompany, devLogin.actor.id, revokedDevice, devEvId))

                val revokeDeviceResp = upstream.execute(
                    TransportRequest(
                        method = HttpMethod.DELETE,
                        path = "/api/mobile/v1/sessions/${revokedDevice.value}",
                        baseOrigin = origin,
                        headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer ${devLogin.token}")
                    )
                )
                assertEquals(200, revokeDeviceResp.statusCode)

                devRepo.isNetworkOffline = false
                val devSync = devRepo.syncOfflineSales().getOrThrow()
                assertTrue(devSync.authBlocked)
                assertEquals(0, devSync.confirmedCount)
                assertTrue(devOutbox.isQueueLocked(targetCompany, devLogin.actor.id, revokedDevice))
                val afterDevRevoke = requireNotNull(devOutbox.getRecord(targetCompany, devLogin.actor.id, revokedDevice, devEvId))
                assertEquals(OutboxStatus.AUTH_BLOCKED, afterDevRevoke.status)
                assertEquals(beforeDevRevoke.payloadDigest, afterDevRevoke.payloadDigest)
                assertArrayEquals(beforeDevRevoke.payloadBytes, afterDevRevoke.payloadBytes)

                // Revoked device login is rejected with 401
                val revChal = requireSuccess(client.authChallenge(AuthChallengeRequest(revokedDevice, "paired-test", "android")))
                val revReLogin = client.authLogin(
                    AuthLoginRequest(
                        challengeId = revChal.challengeId,
                        nonce = revChal.nonce,
                        deviceId = revokedDevice,
                        deviceName = "Synthetic paired client",
                        email = credentials.require("device_email").asString(),
                        password = credentials.require("device_password").asString()
                    )
                )
                assertTrue("Expected Auth401 for revoked device login, got $revReLogin", revReLogin is ReadClientResult.Auth401)
            } finally {
                devRepo.close()
                devDb.close()
                context.deleteDatabase(devDbFile.absolutePath)
            }
        } finally {
            requireSuccess(client.authLogout(primaryToken, AuthLogoutRequest(device)))
        }
    }
}
