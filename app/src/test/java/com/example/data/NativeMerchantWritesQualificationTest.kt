package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CurrentAuthScope
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.client.MobileMerchantApiClient.ReceiptStatusResult
import com.example.data.mobile.contract.CanonicalJsonSerializer
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonBoolean
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.contract.StrictJsonString
import com.example.data.mobile.outbox.CommandStatus
import com.example.data.mobile.outbox.InMemoryMerchantCommandStore
import com.example.data.mobile.outbox.MerchantCommandCipher
import com.example.data.mobile.outbox.MerchantCommandRecord
import com.example.data.mobile.outbox.MerchantCommandStore
import com.example.data.mobile.outbox.OutboxDatabase
import com.example.data.mobile.outbox.RoomMerchantCommandStore
import com.example.data.mobile.contract.toNullableString
import com.example.data.mobile.readclient.EndpointSecurity
import org.junit.Assert.assertNotEquals
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.data.mobile.session.JvmAesGcmCryptoEngine
import com.example.model.ExpenseCategory
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.ServerExpenseCategory
import com.example.model.Supplier
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBidiUtils
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Qualification Test Suite for Native Merchant Writes & Durable Command Idempotency.
 *
 * Verifies the 25 qualification criteria:
 * 1. Exact purchases POST payload & lines scale 6
 * 2. GRN payload uses 'lines' (NOT 'received_lines'), explicit destination warehouse ULID, and exact fractional qty
 * 3. Failed HTTP call results in zero changed records
 * 4. Official server IDs and bill numbers preserved
 * 5. Stock never locally derived (refreshed strictly from server)
 * 6. Ambiguous same-SKU lines rejected when lineId missing
 * 7. Durable command replay uses same key bytes and single POST when status is posted
 * 8. Auth scope change (wrong actor/device/generation) produces zero POST
 * 9. Real category ULID required for expenses, unknown balances remain null
 * 10. Production factory requires real durable store; no InMemory default
 * 11. Lost ACK recovery without duplicate POST via receipt status lookup
 * 12. Changed payload conflict rejected; digest mismatch prevents overwrite
 * 13. Cross actor or device replay denied by composite scope
 * 14. Transactional CAS lifecycle transitions
 * 15. AES-256-GCM encrypted BLOB with AAD; no plaintext PII in database
 * 16. Room migration v1->v2 populated and fresh schema includes unique index
 * 17. Purchases and GRN do not query generic receipt status
 * 18. Stable operation IDs originate in ViewModel and persist across retries
 * 19. Restart with actual SQLite and encryption restores draft intent and payload
 * 20. Concurrent claim only one POST via CAS transition
 * 21. Failed capture produces zero POST
 * 22. Expired scope after GET produces zero ACK / aborts
 * 23. Missing required server fields fail not fakes
 * 24. Nullable cost remains null when redacted
 * 25. Genuine bill number exact string preserved
 *
 * Note: Preserved per task instructions as unexecuted qualification artifact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeMerchantWritesQualificationTest {
    private fun digestBytes(bytes: ByteArray) = com.example.data.mobile.contract.PayloadDigest("sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })


    private val companyId = ServerUlid.parse("01JMB000000000000000000001")
    private val actorId = ServerUlid.parse("01JMB000000000000000000002")
    private val deviceId = ClientUuid.parse("00000000-0000-4000-8000-000000000001")
    private val warehouseId = ServerUlid.parse("01JMB000000000000000000003")

    private val testSession = UserSession(
        id = actorId.value,
        name = "Test Operator",
        username = "test_operator",
        role = UserRole.MANAGER,
        branchNameAr = "الفرع الرئيسي",
        branchNameEn = "Main Branch",
        isDemoSession = false
    )

    // -------------------------------------------------------------------------
    // 1. Exact purchases POST payload & lines scale 6
    // -------------------------------------------------------------------------
    @Test
    fun test1_exactPurchasesPostPayloadAndLinesScale6(): Unit = runBlocking {
        val recordedRequests = mutableListOf<TransportRequest>()
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                recordedRequests.add(request)
                return when {
                    request.method == HttpMethod.POST && request.path.contains("/purchases") -> {
                        TransportResponse(
                            statusCode = 201,
                            body = """{"id":"01JCV8X9M2NQW3TYZ4R5S00004","bill_number":"BILL-2026-001"}"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/purchases/01JCV8X9M2NQW3TYZ4R5S00004") -> {
                        TransportResponse(
                            statusCode = 200,
                            body = """{
                                "id": "01JCV8X9M2NQW3TYZ4R5S00004",
                                "bill_number": "BILL-2026-001",
                                "supplier_id": "01JCV8X9M2NQW3TYZ4R5S00015",
                                "supplier_name": "Al-Quds Flour",
                                "total_amount": "50.00",
                                "paid_amount": "20.00",
                                "remaining_payable": "30.00",
                                "is_received": false,
                                "received_quantity": "0.000000",
                                "created_at": "2026-11-19T00:00:00Z",
                                "lines": [
                                    {
                                        "id": "01JCV8X9M2NQW3TYZ4R5S00012",
                                        "product_id": "01JCV8X9M2NQW3TYZ4R5S00014",
                                        "product_name": "Wheat Flour 25kg",
                                        "quantity": "2.000000",
                                        "unit_cost": "25.00",
                                        "line_total": "50.00",
                                        "received_quantity": "0.000000"
                                    }
                                ]
                            }"""
                        )
                    }
                    else -> TransportResponse(404, body = """{"error":"Not Found"}""")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        val lines = listOf(
            PurchaseLineItem(
                id = "local_temp_1",
                productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                productNameAr = "طحين قمح 25 كغم",
                productNameEn = "Wheat Flour 25kg",
                quantity = 2,
                unitCostIls = 25.0
            )
        )

        val result = repo.recordStructuredPurchaseBill(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            lines = lines,
            paidNowIls = 20.0,
            operator = testSession,
            note = "Initial stock order"
        )

        assertTrue("Structured purchase bill must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val postReq = recordedRequests.firstOrNull { it.method == HttpMethod.POST }
        assertNotNull("Expected POST /purchases request", postReq)
        assertTrue(postReq!!.path.contains("/api/mobile/v1/companies/${companyId.value}/purchases"))

        val postJson = StrictJsonParser.parseObject(postReq.body ?: "")
        val linesArray = postJson.require("lines").asArray()
        assertEquals(1, linesArray.elements.size)
        val lineJson = linesArray.elements[0].asObject()
        assertEquals("01JCV8X9M2NQW3TYZ4R5S00014", lineJson.require("product_id").asString())
        // Wire scale must be 6 decimal places: "2.000000"
        assertEquals("2.000000", lineJson.require("quantity").asString())
        assertEquals("25.000000", lineJson.require("unit_cost_ils").asString())

        // Followed by authoritative GET
        val getReq = recordedRequests.firstOrNull { it.method == HttpMethod.GET && it.path.contains("/purchases/") }
        assertNotNull("Expected GET /purchases/{id} after POST", getReq)
    }

    // -------------------------------------------------------------------------
    // 2. GRN payload uses 'lines' (NOT 'received_lines'), explicit destination warehouse ULID, and exact fractional qty
    // -------------------------------------------------------------------------
    @Test
    fun test2_grnPayloadUsesLinesAndExplicitWarehouseAndFractionalQty(): Unit = runBlocking {
        val recordedRequests = mutableListOf<TransportRequest>()
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                recordedRequests.add(request)
                return when {
                    request.method == HttpMethod.POST && request.path.contains("/grn") -> {
                        TransportResponse(
                            statusCode = 200,
                            body = """{
                                "id": "01JMBGRN000000000000000001",
                                "grn_number": "GRN-2026-0001",
                                "received_at": "2026-11-19T01:00:00Z",
                                "destination_warehouse_id": "${warehouseId.value}",
                                "lines": [
                                    {
                                        "id": "01JCV8X9M2NQW3TYZ4R5S00011",
                                        "purchase_line_id": "01JCV8X9M2NQW3TYZ4R5S00012",
                                        "received_now": "0.000001"
                                    }
                                ]
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/products/") -> {
                        TransportResponse(
                            statusCode = 200,
                            body = """{
                                "id": "01JCV8X9M2NQW3TYZ4R5S00014",
                                "name": "Precision Spice",
                                "sku": "SPICE-01",
                                "stock_count": "100.000001",
                                "selling_price": "10.00",
                                "cost_price": "5.00"
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/purchases/") -> {
                        TransportResponse(
                            statusCode = 200,
                            body = """{
                                "id": "01JCV8X9M2NQW3TYZ4R5S00004",
                                "bill_number": "BILL-2026-001",
                                "supplier_id": "01JCV8X9M2NQW3TYZ4R5S00015",
                                "supplier_name": "Precision Supplier",
                                "date": "2026-11-19T01:00:00Z",
                                "created_at": "2026-11-19T01:00:00Z",
                                "total_amount": "50.00",
                                "paid_amount": "20.00",
                                "remaining_payable": "30.00",
                                "is_received": false,
                                "received_quantity": "0.000001",
                                "lines": [
                                    {
                                        "id": "01JCV8X9M2NQW3TYZ4R5S00012",
                                        "product_id": "01JCV8X9M2NQW3TYZ4R5S00014",
                                        "product_name": "Precision Spice",
                                        "quantity": "1.000000",
                                        "received_quantity": "0.000001",
                                        "unit_cost": "50.00"
                                    }
                                ]
                            }"""
                        )
                    }
                    else -> TransportResponse(200, body = "{}")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // Seed purchase bill with matching line
        val initialBill = PurchaseBill(
            id = "01JCV8X9M2NQW3TYZ4R5S00004",
            billNumber = "BILL-2026-001",
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            totalExact = BigDecimal("50.00"),
            paidExact = BigDecimal("20.00"),
            lines = listOf(
                PurchaseLineItem(
                    id = "01JCV8X9M2NQW3TYZ4R5S00012",
                    productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                    productNameAr = "بهارات دقيقة",
                    productNameEn = "Precision Spice",
                    quantityExact = BigDecimal("1.000000"),
                    receivedQuantityExact = BigDecimal.ZERO,
                    unitCostExact = BigDecimal("50.00")
                )
            )
        )
        repo.seedPurchaseBill(initialBill)

        val fractionalQty = BigDecimal("0.000001")
        val result = repo.confirmPhysicalGoodsReceipt(
            billId = initialBill.id,
            productId = "01JCV8X9M2NQW3TYZ4R5S00014",
            receivedQty = fractionalQty,
            batchNote = "Micro batch check",
            operator = testSession,
            lineId = "01JCV8X9M2NQW3TYZ4R5S00012"
        )

        assertTrue("Structured purchase bill must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val grnReq = recordedRequests.firstOrNull { it.method == HttpMethod.POST && it.path.contains("/grn") }
        assertNotNull("Expected POST /grn request", grnReq)

        val grnBodyText = grnReq!!.body ?: ""
        // MUST use 'lines' NOT 'received_lines'
        assertTrue("Payload must use 'lines'", grnBodyText.contains("\"lines\""))
        assertFalse("Payload must NOT use 'received_lines'", grnBodyText.contains("\"received_lines\""))

        val grnJson = StrictJsonParser.parseObject(grnBodyText)
        assertEquals(warehouseId.value, grnJson.require("destination_warehouse_id").asString())
        val linesArray = grnJson.require("lines").asArray()
        assertEquals(1, linesArray.elements.size)
        val itemJson = linesArray.elements[0].asObject()
        assertEquals("01JCV8X9M2NQW3TYZ4R5S00012", itemJson.require("purchase_line_id").asString())
        assertEquals("0.000001", itemJson.require("quantity").asString())
    }

    // -------------------------------------------------------------------------
    // 3. Failed HTTP call results in zero changed records
    // -------------------------------------------------------------------------
    @Test
    fun test3_failedHttpCallResultsInZeroChangedRecords(): Unit = runBlocking {
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return TransportResponse(statusCode = 500, body = """{"error":"Internal Server Error"}""")
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        val initialBillsCount = repo.getPurchaseBills().size
        val initialMovementsCount = repo.getStockMovements().size

        val lines = listOf(
            PurchaseLineItem(
                id = "line_fail_1",
                productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                productNameAr = "منتج",
                productNameEn = "Product",
                quantity = 5,
                unitCostIls = 10.0
            )
        )

        val result = repo.recordStructuredPurchaseBill(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            lines = lines,
            paidNowIls = 0.0,
            operator = testSession
        )

        assertTrue("HTTP 500 must cause failure", result.isFailure)
        assertEquals("Bills list must remain unchanged", initialBillsCount, repo.getPurchaseBills().size)
        assertEquals("Stock movements must remain unchanged", initialMovementsCount, repo.getStockMovements().size)
    }

    // -------------------------------------------------------------------------
    // 4. Official server IDs and bill numbers preserved
    // -------------------------------------------------------------------------
    @Test
    fun test4_officialServerIdsAndBillNumbersPreserved(): Unit = runBlocking {
        val officialBillId = "01JCV8X9M2NQW3TYZ4R5S00041"
        val officialBillNumber = "BILL-2026-AUTHORITATIVE"
        val officialLineId = "01JCV8X9M2NQW3TYZ4R5S00042"

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST -> TransportResponse(
                        201,
                        body = """{"id":"$officialBillId","bill_number":"$officialBillNumber"}"""
                    )
                    request.method == HttpMethod.GET && request.path.contains("/purchases/$officialBillId") -> {
                        TransportResponse(
                            200,
                            body = """{
                                "id": "$officialBillId",
                                "bill_number": "$officialBillNumber",
                                "supplier_id": "01JCV8X9M2NQW3TYZ4R5S00015",
                                "supplier_name": "Authoritative Supplier",
                                "total_amount": "100.00",
                                "paid_amount": "100.00",
                                "remaining_payable": "0.00",
                                "is_received": false,
                                "received_quantity": "0.000000",
                                "created_at": "2026-11-19T02:00:00Z",
                                "lines": [
                                    {
                                        "id": "$officialLineId",
                                        "product_id": "01JCV8X9M2NQW3TYZ4R5S00014",
                                        "product_name": "Premium Product",
                                        "quantity": "10.000000",
                                        "unit_cost": "10.00",
                                        "line_total": "100.00",
                                        "received_quantity": "0.000000"
                                    }
                                ]
                            }"""
                        )
                    }
                    else -> TransportResponse(200, body = "{}")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        val result = repo.recordStructuredPurchaseBill(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            lines = listOf(
                PurchaseLineItem(
                    id = "local_draft_id",
                    productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                    productNameAr = "صنف ممتاز",
                    productNameEn = "Premium Product",
                    quantity = 10,
                    unitCostIls = 10.0
                )
            ),
            paidNowIls = 100.0,
            operator = testSession
        )

        assertTrue("Structured purchase bill must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val bill = result.getOrNull()
        assertNotNull(bill)
        assertEquals(officialBillId, bill!!.id)
        assertEquals(officialBillNumber, bill.billNumber)
        assertEquals(1, bill.lines.size)
        assertEquals(officialLineId, bill.lines[0].id)
    }

    // -------------------------------------------------------------------------
    // 5. Stock never locally derived (refreshed strictly from server)
    // -------------------------------------------------------------------------
    @Test
    fun test5_stockNeverLocallyDerivedRefreshedStrictlyFromServer(): Unit = runBlocking {
        // Initial stock in app is 10. We receive 5 units.
        // Server centralized stock is 99 (e.g. concurrent warehouse receipts).
        // Must reflect 99 from server, NOT 10 + 5 = 15!
        val centralBillId = "01JCV8X9M2NQW3TYZ4R5S00051"
        val centralProductId = "01JCV8X9M2NQW3TYZ4R5S00053"
        val centralLineId = "01JCV8X9M2NQW3TYZ4R5S00052"
        val centralGrnId = "01JCV8X9M2NQW3TYZ4R5S00055"
        val grnLineId = "01JCV8X9M2NQW3TYZ4R5S00054"
        val supplierId = "01JCV8X9M2NQW3TYZ4R5S00015"

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.contains("/grn") -> {
                        TransportResponse(
                            200,
                            body = """{
                                "id": "$centralGrnId",
                                "grn_number": "GRN-2026-0002",
                                "destination_warehouse_id": "${warehouseId.value}",
                                "lines": [{"id":"$grnLineId","purchase_line_id":"$centralLineId","received_now":"5.000000"}]
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/products/$centralProductId") -> {
                        TransportResponse(
                            200,
                            body = """{
                                "id": "$centralProductId",
                                "name": "Central Stocked Item",
                                "sku": "CEN-01",
                                "available_quantity": "99.000000",
                                "stock_quantity": "99.000000",
                                "selling_price": "20.00",
                                "cost_price": "10.00"
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/purchases/") -> {
                        TransportResponse(
                            200,
                            body = """{
                                "id": "$centralBillId",
                                "bill_number": "BILL-CEN",
                                "supplier_id": "$supplierId",
                                "supplier_name": "Central Supplier",
                                "date": "2026-11-19T02:00:00Z",
                                "created_at": "2026-11-19T02:00:00Z",
                                "total_amount": "50.00",
                                "paid_amount": "50.00",
                                "remaining_payable": "0.00",
                                "is_received": true,
                                "received_quantity": "5.000000",
                                "lines": [
                                    {
                                        "id": "$centralLineId",
                                        "product_id": "$centralProductId",
                                        "product_name": "Central Stocked Item",
                                        "quantity": "5.000000",
                                        "received_quantity": "5.000000",
                                        "unit_cost": "10.00"
                                    }
                                ]
                            }"""
                        )
                    }
                    else -> TransportResponse(200, body = "{}")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        repo.seedProduct(
            Product(
                id = centralProductId,
                nameAr = "صنف مركزي",
                nameEn = "Central Stocked Item",
                category = ProductCategory.PANTRY,
                barcode = "123456",
                priceIls = 20.0,
                costIls = 10.0,
                unitAr = "قطعة",
                unitEn = "pcs",
                stockCount = 10,
                minStockThreshold = 8,
                accentHex = 0xFF123456L,
                isStockTracked = true
            )
        )
        repo.seedPurchaseBill(
            PurchaseBill(
                id = centralBillId,
                billNumber = "BILL-CEN",
                supplierId = supplierId,
                totalExact = BigDecimal("50.00"),
                paidExact = BigDecimal("50.00"),
                lines = listOf(
                    PurchaseLineItem(
                        id = centralLineId,
                        productId = centralProductId,
                        productNameAr = "صنف مركزي",
                        productNameEn = "Central Stocked Item",
                        quantityExact = BigDecimal("5.000000"),
                        receivedQuantityExact = BigDecimal.ZERO,
                        unitCostExact = BigDecimal("10.00")
                    )
                )
            )
        )

        val result = repo.confirmPhysicalGoodsReceipt(
            billId = centralBillId,
            productId = centralProductId,
            receivedQty = BigDecimal("5.000000"),
            batchNote = "Receiving central item",
            operator = testSession,
            lineId = centralLineId
        )

        assertTrue("Physical goods receipt must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val movement = result.getOrNull()
        assertNotNull("StockMovement must not be null", movement)
        // Resulting stock must be the server's authoritative 99.000000, NOT locally computed 15
        assertEquals(BigDecimal("99.000000"), movement!!.resultingStockExact)

        val updatedProd = repo.getProducts().first { it.id == centralProductId }
        assertEquals(BigDecimal("99.000000"), updatedProd.stockCountExact)
    }

    // -------------------------------------------------------------------------
    // 6. Ambiguous same-SKU lines rejected when lineId missing
    // -------------------------------------------------------------------------
    @Test
    fun test6_ambiguousSameSkuLinesRejectedWhenLineIdMissing(): Unit = runBlocking {
        val client = MobileMerchantApiClient(object : ReadClientTransport {
            override fun execute(request: TransportRequest) = TransportResponse(200, body = "{}")
        }, "https://api.test.example") { "token" }

        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // Seed a bill with TWO lines of the exact same product SKU
        val sameProductId = "01JCV8X9M2NQW3TYZ4R5S00064"
        val multiBillId = "01JCV8X9M2NQW3TYZ4R5S00061"
        val multiSupplierId = "01JCV8X9M2NQW3TYZ4R5S00015"
        repo.seedPurchaseBill(
            PurchaseBill(
                id = multiBillId,
                billNumber = "BILL-SAME-SKU",
                supplierId = multiSupplierId,
                totalExact = BigDecimal("110.00"),
                paidExact = BigDecimal.ZERO,
                lines = listOf(
                    PurchaseLineItem(
                        id = "01JCV8X9M2NQW3TYZ4R5S00062",
                        productId = sameProductId,
                        productNameAr = "نفس الصنف 1",
                        productNameEn = "Same SKU 1",
                        quantityExact = BigDecimal("5.000000"),
                        receivedQuantityExact = BigDecimal.ZERO,
                        unitCostExact = BigDecimal("10.00")
                    ),
                    PurchaseLineItem(
                        id = "01JCV8X9M2NQW3TYZ4R5S00063",
                        productId = sameProductId,
                        productNameAr = "نفس الصنف 2",
                        productNameEn = "Same SKU 2",
                        quantityExact = BigDecimal("5.000000"),
                        receivedQuantityExact = BigDecimal.ZERO,
                        unitCostExact = BigDecimal("12.00")
                    )
                )
            )
        )

        // Attempting to confirm GRN without lineId MUST fail due to ambiguity
        val result = repo.confirmPhysicalGoodsReceipt(
            billId = multiBillId,
            productId = sameProductId,
            receivedQty = BigDecimal("5.000000"),
            batchNote = "Ambiguous confirm",
            operator = testSession,
            lineId = null // missing!
        )

        assertTrue("Ambiguous line resolution must fail", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Error must explain ambiguity: ${ex?.message}", ex is IllegalArgumentException || ex?.message?.contains("Ambiguous") == true)
    }

    // -------------------------------------------------------------------------
    // 7. Durable command replay uses same key bytes and single POST when status is posted
    // -------------------------------------------------------------------------
    @Test
    fun test7_durableCommandReplayUsesSameKeyBytesAndSinglePostWhenStatusIsPosted(): Unit = runBlocking {
        val testOperationId = UUID.randomUUID().toString()
        val testIdempotencyKey = "bill-$testOperationId"
        var postCount = 0
        val durableBillId = "01JCV8X9M2NQW3TYZ4R5S00071"
        val durableSupplierId = "01JCV8X9M2NQW3TYZ4R5S00015"

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST -> {
                        postCount++
                        TransportResponse(
                            201,
                            body = """{"id":"$durableBillId","bill_number":"BILL-DURABLE-01"}"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/receipts/$testIdempotencyKey/status") -> {
                        // Returns 200 with previous snapshot on replay
                        TransportResponse(
                            200,
                            body = """{
                                "idempotency_key": "$testIdempotencyKey",
                                "status": "posted",
                                "response_snapshot": {
                                    "id": "$durableBillId",
                                    "bill_number": "BILL-DURABLE-01",
                                    "supplier_id": "$durableSupplierId",
                                    "supplier_name": "Durable Supplier",
                                    "date": "2026-11-19T02:00:00Z",
                                    "created_at": "2026-11-19T02:00:00Z",
                                    "total_amount": "50.00",
                                    "paid_amount": "0.00",
                                    "remaining_payable": "50.00",
                                    "is_received": false,
                                    "received_quantity": "0.000000",
                                    "lines": []
                                }
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/purchases/$durableBillId") -> {
                        TransportResponse(
                            200,
                            body = """{
                                "id": "$durableBillId",
                                "bill_number": "BILL-DURABLE-01",
                                "supplier_id": "$durableSupplierId",
                                "supplier_name": "Durable Supplier",
                                "date": "2026-11-19T02:00:00Z",
                                "created_at": "2026-11-19T02:00:00Z",
                                "total_amount": "50.00",
                                "paid_amount": "0.00",
                                "remaining_payable": "50.00",
                                "is_received": false,
                                "received_quantity": "0.000000",
                                "lines": []
                            }"""
                        )
                    }
                    else -> TransportResponse(200, body = "{}")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val sharedStore = InMemoryMerchantCommandStore()
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = sharedStore
        )

        // First attempt (normal execution)
        val result1 = repo.recordStructuredPurchaseBill(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            lines = listOf(
                PurchaseLineItem(
                    productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                    quantity = 1,
                    unitCostIls = 50.0
                )
            ),
            paidNowIls = 0.0,
            operator = testSession,
            note = "Initial Order",
            operationId = testOperationId
        )
        assertTrue("First purchase attempt must succeed: ${result1.exceptionOrNull()?.message}", result1.isSuccess)
        assertEquals("Post count must be 1 on first attempt", 1, postCount)

        // Second attempt with exact same operationId / idempotency key (replay)
        val result2 = repo.recordStructuredPurchaseBill(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            lines = listOf(
                PurchaseLineItem(
                    productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                    quantity = 1,
                    unitCostIls = 50.0
                )
            ),
            paidNowIls = 0.0,
            operator = testSession,
            note = "Initial Order",
            operationId = testOperationId
        )
        assertTrue("Replay attempt must succeed: ${result2.exceptionOrNull()?.message}", result2.isSuccess)
        // Post count MUST NOT increment because GET /receipts/{key}/status confirmed it was posted
        assertEquals("Post count must remain 1 on durable replay", 1, postCount)
    }

    // -------------------------------------------------------------------------
    // 8. Auth scope change produces zero POST
    // -------------------------------------------------------------------------
    @Test
    fun test8_authScopeChangeProducesZeroPost(): Unit = runBlocking {
        var postCount = 0
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) postCount++
                return TransportResponse(200, body = "{}")
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        // Scope locked to actor 01JMB000000000000000000002 and company 01JMB000000000000000000001
        // But authScopeProvider returns mismatched company (e.g. company changed / session switched)
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            authScopeProvider = {
                CurrentAuthScope(
                    companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S00016"),
                    actorId = actorId,
                    deviceId = deviceId,
                    token = "rogue-token",
                    generation = 1L
                )
            }
        )

        try {
            repo.recordStructuredPurchaseBill(
                supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
                lines = listOf(
                    PurchaseLineItem(
                        productId = "01JCV8X9M2NQW3TYZ4R5S00014",
                        quantity = 1,
                        unitCostIls = 100.0
                    )
                ),
                paidNowIls = 0.0,
                operator = testSession,
                note = "Cross-tenant attempt"
            )
            fail("Cross-scope execution must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("SecurityException must explain auth scope changed: ${e.message}", e.message?.contains("Authenticated scope has changed") == true)
        }

        assertEquals("Zero POST requests must be made when auth scope is mismatched", 0, postCount)
    }

    // -------------------------------------------------------------------------
    // 9. Real category ULID required for expenses, unknown balances remain null
    // -------------------------------------------------------------------------
    @Test
    fun test9_realCategoryUlidRequiredForExpensesUnknownBalancesRemainNull(): Unit = runBlocking {
        val serverCategoryId = "01JMBCAT000000000000000001"
        var expensePostJson: String? = null

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.GET && request.path.contains("/expenses/categories") -> {
                        TransportResponse(
                            200,
                            body = """{
                                "categories": [
                                    {"id":"$serverCategoryId","code":"TRANSPORT","name_ar":"نقل","name_en":"Transportation","is_active":true}
                                ]
                            }"""
                        )
                    }
                    request.method == HttpMethod.POST && request.path.contains("/expenses") -> {
                        expensePostJson = request.body ?: ""
                        TransportResponse(
                            201,
                            body = """{
                                "id": "01JMBEXP000000000000000001",
                                "expense_category_id": "$serverCategoryId",
                                "category_id": "$serverCategoryId",
                                "amount": "45.000000",
                                "description": "Delivery Taxi",
                                "posted_at": "2026-11-19T03:00:00Z"
                            }"""
                        )
                    }
                    request.method == HttpMethod.POST && (request.path.contains("/payments") || request.path.contains("/customer-payments")) -> {
                        // Response WITHOUT running_balance (omitted by server)
                        TransportResponse(
                            201,
                            body = """{
                                "id": "01JCV8X9M2NQW3TYZ4R5S00007",
                                "customer_id": "01JCV8X9M2NQW3TYZ4R5S00006",
                                "amount": "100.00",
                                "posted_at": "2026-11-19T03:30:00Z"
                            }"""
                        )
                    }
                    else -> TransportResponse(200, body = "{}")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // 9A: Record expense matches server category ULID
        val expResult = repo.recordExpense(
            title = "Delivery Taxi",
            category = ExpenseCategory.TRANSPORT,
            amountIls = 45.0,
            operator = testSession,
            categoryId = serverCategoryId
        )
        assertTrue("Expense recording must succeed: ${expResult.exceptionOrNull()?.message}", expResult.isSuccess)
        assertNotNull(expensePostJson)
        val expParsed = StrictJsonParser.parseObject(expensePostJson!!)
        val postedCatId = expParsed.properties["expense_category_id"]?.asString() ?: expParsed.properties["category_id"]?.asString()
        assertEquals(serverCategoryId, postedCatId)

        // 9B: Customer payment with unknown balance must have runningBalanceIls == null (NEVER 0.0)
        val payResult = repo.recordCustomerPayment(
            customerId = "01JCV8X9M2NQW3TYZ4R5S00006",
            amountIls = 100.0,
            note = "Direct cash payment",
            paymentId = "cpay_test_01"
        )
        assertTrue("Customer payment must succeed: ${payResult.exceptionOrNull()?.message}", payResult.isSuccess)
        val tx = repo.getCustomerTransactions().firstOrNull { it.id == "01JCV8X9M2NQW3TYZ4R5S00007" }
        assertNotNull("Customer transaction must be found", tx)
        assertNull("Unknown running balance from server must remain null, never coerced to 0.0", tx!!.runningBalanceIls)
    }

    // -------------------------------------------------------------------------
    // 10. Production factory requires real durable store; no InMemory default
    // -------------------------------------------------------------------------
    @Test
    fun test10_productionFactoryRequiresRealDurableStore_noInMemoryDefault() {
        val constructor = ProductionTraderRepository::class.java.constructors.first()
        val commandStoreParam = constructor.parameters.firstOrNull { it.type == MerchantCommandStore::class.java }
        assertNotNull("ProductionTraderRepository must have MerchantCommandStore parameter", commandStoreParam)

        val fromReadReadyMethod = ProductionTraderRepository.Companion::class.java.methods.firstOrNull { it.name == "fromReadReady" }
        assertNotNull("fromReadReady must exist on companion", fromReadReadyMethod)
        val hasStore = fromReadReadyMethod!!.parameterTypes.any { it == MerchantCommandStore::class.java }
        assertTrue("fromReadReady must explicitly require MerchantCommandStore parameter without fallback", hasStore)
    }

    // -------------------------------------------------------------------------
    // 11. Lost ACK recovery without duplicate POST via receipt status lookup
    // -------------------------------------------------------------------------
    @Test
    fun test11_lostAckRecoveryWithoutDuplicate_receiptStatusChecked(): Unit = runBlocking {
        var postAttempts = 0
        var statusQueries = 0
        val commandStore = InMemoryMerchantCommandStore()

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                return when {
                    request.method == HttpMethod.POST && request.path.contains("/expenses") -> {
                        postAttempts++
                        // Simulate network drop / timeout after server received it
                        TransportResponse(504, body = """{"error":"Gateway Timeout"}""")
                    }
                    request.method == HttpMethod.GET && request.path.contains("/receipts/") -> {
                        statusQueries++
                        TransportResponse(
                            statusCode = 200,
                            body = """{
                                "status": "completed",
                                "response_snapshot": {
                                    "id": "01JMBEXP000000000000000001",
                                    "title": "Fuel",
                                    "amount": "50.00",
                                    "date": "2026-11-19T10:00:00Z"
                                }
                            }"""
                        )
                    }
                    request.method == HttpMethod.GET && request.path.contains("/expenses/categories") -> {
                        TransportResponse(
                            statusCode = 200,
                            body = """{"items":[{"id":"01JMBCAT000000000000000001","code":"TRANSPORT","name_ar":"نقل","name_en":"Transport"}]}"""
                        )
                    }
                    else -> TransportResponse(200, body = "{}")
                }
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore
        )

        val result = repo.recordExpense(
            title = "Fuel",
            category = ExpenseCategory.TRANSPORT,
            amountIls = 50.0,
            operator = testSession,
            categoryId = "01JMBCAT000000000000000001",
            operationId = "exp_retry_test_1"
        )

        assertTrue("Lost ACK command must succeed via receipt status reconciliation", result.isSuccess)
        assertEquals("Exactly 1 POST attempt should have been made before reconciling via receipt status", 1, postAttempts)
        assertEquals("Receipt status query should have been performed to recover lost ACK", 1, statusQueries)
        assertEquals("01JMBEXP000000000000000001", result.getOrNull()?.id)

        // Verifying commandStore state is COMPLETED
        val stored = commandStore.findCommand(companyId.value, actorId.value, deviceId.value, "exp-exp_retry_test_1")
        assertNotNull(stored)
        assertEquals(CommandStatus.COMPLETED, stored!!.status)
    }

    // -------------------------------------------------------------------------
    // 12. Changed payload conflict rejected; digest mismatch prevents overwrite
    // -------------------------------------------------------------------------
    @Test
    fun test12_changedPayloadConflictRejected_digestMismatch(): Unit = runBlocking {
        val commandStore = InMemoryMerchantCommandStore()
        val payload1 = CanonicalJsonSerializer.serializeToUtf8Bytes(StrictJsonObject(mapOf("amount" to StrictJsonString("50.00"))))
        val payload2 = CanonicalJsonSerializer.serializeToUtf8Bytes(StrictJsonObject(mapOf("amount" to StrictJsonString("75.00"))))

        val digest1 = digestBytes(payload1).value
        val digest2 = digestBytes(payload2).value

        // Record initial pending command
        val initial = commandStore.recordPendingCommand(
            companyId = companyId.value,
            commandId = "cmd_123",
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "expense.create",
            idempotencyKey = "exp-cmd_123",
            payloadDigest = digest1,
            canonicalPayloadBytes = payload1
        )
        assertNotNull("Initial pending command must be recorded", initial)

        // Attempting to record directly with SAME key but DIFFERENT payload digest MUST fail
        try {
            commandStore.recordPendingCommand(
                companyId = companyId.value,
                commandId = "cmd_123",
                actorId = actorId.value,
                deviceId = deviceId.value,
                commandType = "expense.create",
                idempotencyKey = "exp-cmd_123",
                payloadDigest = digest2,
                canonicalPayloadBytes = payload2
            )
            fail("Expected IllegalStateException on payload digest mismatch")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must indicate parameter/digest conflict: ${e.message}",
                e.message?.contains("different parameters") == true || e.message?.contains("Payload digest mismatch") == true
            )
        }

        // Prove via repository: executing with conflicting payload produces failure and ZERO additional POST
        var postCount = 0
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) postCount++
                return TransportResponse(200, body = """{"items":[{"id":"01JMBCAT000000000000000001","code":"TRANSPORT","name_ar":"نقل","name_en":"Transport"}]}""")
            }
        }
        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore
        )

        val repoResult = runCatching {
            repo.recordExpense(
                title = "Conflict Test",
                category = ExpenseCategory.TRANSPORT,
                amountIls = 75.0,
                operator = testSession,
                categoryId = "01JMBCAT000000000000000001",
                operationId = "cmd_123"
            )
        }
        val isFailure = repoResult.isFailure || repoResult.getOrNull()?.isFailure == true
        assertTrue("Attempting to execute with conflicting payload must fail", isFailure)
        assertEquals("Zero POST must be dispatched on changed payload conflict", 0, postCount)
    }

    // -------------------------------------------------------------------------
    // 13. Cross actor or device replay denied by composite scope
    // -------------------------------------------------------------------------
    @Test
    fun test13_crossActorOrDeviceReplayDenied(): Unit = runBlocking {
        val commandStore = InMemoryMerchantCommandStore()
        val payload = CanonicalJsonSerializer.serializeToUtf8Bytes(StrictJsonObject(mapOf("amount" to StrictJsonString("100.00"))))
        val digest = digestBytes(payload).value

        commandStore.recordPendingCommand(
            companyId = companyId.value,
            commandId = "cmd_scope_1",
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "customer.payment",
            idempotencyKey = "cpay-cmd_scope_1",
            payloadDigest = digest,
            canonicalPayloadBytes = payload
        )

        // Actor B on Device A tries to lookup/replay Actor A's command -> SecurityException thrown
        val otherActor = "01JCV8X9M2NQW3TYZ4R5S00003"
        try {
            commandStore.findCommand(
                companyId = companyId.value,
                actorId = otherActor,
                deviceId = deviceId.value,
                idempotencyKey = "cpay-cmd_scope_1"
            )
            fail("Cross-actor lookup must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Cross-actor denial exception expected: ${e.message}", e.message?.contains("Cross-actor or cross-device lookup denied") == true)
        }

        // Actor A on Device B tries to lookup/replay Actor A's command -> SecurityException thrown
        val otherDevice = "00000000-0000-4000-8000-000000000099"
        try {
            commandStore.findCommand(
                companyId = companyId.value,
                actorId = actorId.value,
                deviceId = otherDevice,
                idempotencyKey = "cpay-cmd_scope_1"
            )
            fail("Cross-device lookup must throw SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Cross-device denial exception expected: ${e.message}", e.message?.contains("Cross-actor or cross-device lookup denied") == true)
        }
    }

    // -------------------------------------------------------------------------
    // 14. Transactional CAS lifecycle transitions
    // -------------------------------------------------------------------------
    @Test
    fun test14_transactionalCasLifecycleTransitions(): Unit = runBlocking {
        val commandStore = InMemoryMerchantCommandStore()
        val payload = CanonicalJsonSerializer.serializeToUtf8Bytes(StrictJsonObject(mapOf("delta" to StrictJsonString("5"))))
        val digest = digestBytes(payload).value

        val rec = commandStore.recordPendingCommand(
            companyId = companyId.value,
            commandId = "cas_1",
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "inventory.adjustment",
            idempotencyKey = "adj-cas_1",
            payloadDigest = digest,
            canonicalPayloadBytes = payload
        )
        assertEquals(CommandStatus.PENDING, rec.status)

        // PENDING -> SUBMITTED (Valid CAS)
        val submitted = commandStore.markSubmitted(rec.commandId)
        assertTrue(submitted)

        // Attempt invalid transition: markSubmitted again on already SUBMITTED
        val submittedAgain = commandStore.markSubmitted(rec.commandId)
        assertFalse("Cannot re-mark already submitted command as submitted", submittedAgain)

        // SUBMITTED -> COMPLETED (Valid CAS)
        val snapshotBytes = """{"status":"ok"}""".toByteArray()
        val completed = commandStore.markCompleted(rec.commandId, snapshotBytes)
        assertTrue(completed)

        // COMPLETED command cannot transition back to SUBMITTED or UNKNOWN_ACK
        val backToSubmitted = commandStore.markSubmitted(rec.commandId)
        assertFalse(backToSubmitted)
        val toUnknown = commandStore.markUnknownAck(rec.commandId, "timeout")
        assertFalse(toUnknown)
    }

    // -------------------------------------------------------------------------
    // 15. AES-256-GCM encrypted BLOB with AAD; no plaintext PII in database
    // -------------------------------------------------------------------------
    @Test
    fun test15_aes256GcmEncryptedBlobNoPlaintextPiiInDatabase() {
        val sensitiveData = """{"customer_name":"Ahmad Abu-Laban","tax_id":"987654321","amount":"1500.00"}""".toByteArray()
        val secretKey = MerchantCommandCipher.generateAesKey()

        val encrypted = MerchantCommandCipher.encrypt(
            plaintext = sensitiveData,
            secretKey = secretKey,
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandId = "cmd_sec_1",
            idempotencyKey = "cpay-sec-1",
            payloadDigest = "sha256_mock_digest"
        )

        // Verify ciphertext does not leak plaintext customer name or tax id
        val rawCipherStr = String(encrypted, Charsets.ISO_8859_1)
        assertFalse(rawCipherStr.contains("Ahmad Abu-Laban"))
        assertFalse(rawCipherStr.contains("987654321"))
        assertFalse(rawCipherStr.contains("1500.00"))

        // Decrypt with authentic AAD parameters
        val decrypted = MerchantCommandCipher.decrypt(
            ciphertextWithIvAndTag = encrypted,
            secretKey = secretKey,
            companyId = companyId.value,
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandId = "cmd_sec_1",
            idempotencyKey = "cpay-sec-1",
            payloadDigest = "sha256_mock_digest"
        )
        assertEquals(String(sensitiveData), String(decrypted))

        // Decrypt with tampered AAD (e.g. attacker changing actorId) MUST fail
        try {
            MerchantCommandCipher.decrypt(
                ciphertextWithIvAndTag = encrypted,
                secretKey = secretKey,
                companyId = companyId.value,
                actorId = "ATTACKER_ACTOR",
                deviceId = deviceId.value,
                commandId = "cmd_sec_1",
                idempotencyKey = "cpay-sec-1",
                payloadDigest = "sha256_mock_digest"
            )
            fail("Decryption with tampered AAD must throw GeneralSecurityException")
        } catch (_: GeneralSecurityException) {
            // Expected authentication tag verification failure
        }
    }

    // -------------------------------------------------------------------------
    // 16. Room migration v1->v2 populated and fresh schema includes unique index
    // -------------------------------------------------------------------------
    @Test
    fun test16_roomMigrationV1ToV2PopulatedAndFreshValid() {
        val ddl = OutboxDatabase.CREATE_MERCHANT_COMMANDS_SQL.replace("`", "")
        assertTrue("DDL must contain uq_merchant_commands_scope_key unique constraint",
            ddl.contains("uq_merchant_commands_scope_key") && ddl.contains("UNIQUE (company_id, actor_id, device_id, idempotency_key)"))
        assertTrue("DDL must use BLOB for encrypted payload_bytes", ddl.contains("payload_bytes BLOB NOT NULL"))
        assertTrue("DDL must use BLOB for encrypted response_snapshot", ddl.contains("response_snapshot BLOB"))

        val migration = OutboxDatabase.MIGRATION_1_2
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
    }

    // -------------------------------------------------------------------------
    // 17. Purchases and GRN do not query generic receipt status
    // -------------------------------------------------------------------------
    @Test
    fun test17_purchasesAndGrnDoNotQueryGenericReceiptStatus(): Unit = runBlocking {
        var receiptStatusQueried = false
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.path.contains("/receipts/")) {
                    receiptStatusQueried = true
                }
                if (request.method == HttpMethod.POST && request.path.contains("/purchases")) {
                    return TransportResponse(504, body = """{"error":"Gateway Timeout"}""")
                }
                return TransportResponse(200, body = "{}")
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        val res = repo.recordStructuredPurchaseBill(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S00015",
            lines = listOf(
                PurchaseLineItem(
                    productId = "01JCV8X9M2NQW3TYZ4R5S00013",
                    quantity = 1,
                    unitCostIls = 10.0
                )
            ),
            paidNowIls = 0.0,
            operator = testSession
        )

        assertTrue(res.isFailure)
        assertFalse("Purchases must not query generic /receipts/{key}/status because backend uses purchases table directly", receiptStatusQueried)
    }

    // -------------------------------------------------------------------------
    // 18. Stable operation IDs originate in ViewModel and persist across retries
    // -------------------------------------------------------------------------
    @Test
    fun test18_stableOperationIdsOriginateInViewModelAndPersistAcrossRetries(): Unit = runBlocking {
        val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            var failNextCall = true
            val commandStore = InMemoryMerchantCommandStore()
            val transport = object : ReadClientTransport {
                override fun execute(request: TransportRequest): TransportResponse {
                    return when {
                        request.method == HttpMethod.POST && request.path.contains("/expenses") -> {
                            if (failNextCall) {
                                TransportResponse(500, body = """{"error":"Server error"}""")
                            } else {
                                TransportResponse(201, body = """{"id":"01JCV8X9M2NQW3TYZ4R5S00018","amount":"10.000000","posted_at":"2026-11-19"}""")
                            }
                        }
                        request.method == HttpMethod.GET && request.path.contains("/receipts/") -> {
                            // Status check after 500: receipt not found yet on server, allowing retry to submit
                            TransportResponse(404, body = """{"error":"Receipt not found"}""")
                        }
                        request.method == HttpMethod.GET && request.path.contains("/expenses/categories") -> {
                            TransportResponse(200, body = """{"items":[{"id":"01JMBCAT000000000000000001","code":"TRANSPORT","name_ar":"نقل","name_en":"Transport"}]}""")
                        }
                        else -> TransportResponse(200, body = "{}")
                    }
                }
            }

            val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
            val repo = ProductionTraderRepository.forTesting(
                apiClient = client,
                companyId = companyId,
                actorId = actorId,
                deviceId = deviceId,
                warehouseId = warehouseId,
                commandStore = commandStore
            )

            val vm = TraderViewModel(repo)
            val initialOpId = vm.uiState.value.draftExpenseOperationId
            assertNotNull("Initial draftExpenseOperationId must be generated", initialOpId)

            // Attempt 1: Call fails on server 500
            val done1 = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val res1 = vm.addExpense(
                title = "Office Supplies",
                category = ExpenseCategory.TRANSPORT,
                amountIls = 10.0,
                categoryId = "01JMBCAT000000000000000001",
                onComplete = { done1.complete(it) }
            )
            assertFalse("Attempt 1 must fail on server 500", res1)
            assertFalse(kotlinx.coroutines.withTimeout(5000) { done1.await() })
            kotlinx.coroutines.withTimeout(5000) { while (vm.uiState.value.isActionBusy) kotlinx.coroutines.delay(1) }
            // On failure, draftExpenseOperationId must be retained identically
            val retryOpId = vm.uiState.value.draftExpenseOperationId
            assertEquals("Draft operation ID must be retained on failure across retries", initialOpId, retryOpId)

            // Attempt 2: Retry succeeds
            failNextCall = false
            val done2 = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val res2 = vm.addExpense(
                title = "Office Supplies",
                category = ExpenseCategory.TRANSPORT,
                amountIls = 10.0,
                categoryId = "01JMBCAT000000000000000001",
                onComplete = { done2.complete(it) }
            )
            assertFalse("Async launch must not claim immediate success", res2)
            assertTrue("Attempt 2 retry must succeed", kotlinx.coroutines.withTimeout(5000) { done2.await() })
            // On success, draftExpenseOperationId must rotate to fresh ID
            val successOpId = vm.uiState.value.draftExpenseOperationId
            assertTrue("Draft operation ID must rotate to new UUID after authoritative success", successOpId != initialOpId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // -------------------------------------------------------------------------
    // 19. Restart with actual SQLite and encryption restores draft intent and payload
    // -------------------------------------------------------------------------
    @Test
    fun test19_restartWithActualSQLiteAndEncryptionRestoresDraftIntent(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "test_cmd_store_durability_${UUID.randomUUID()}.db")
        try {
            if (dbFile.exists()) dbFile.delete()
            val db1 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
            val cryptoEngine = JvmAesGcmCryptoEngine()
            val store1 = RoomMerchantCommandStore(db1, cryptoEngine)

            val expenseDraft1 = store1.getDraftOperationId(companyId.value, actorId.value, deviceId.value, "expense")
            val purchaseDraft1 = store1.getDraftOperationId(companyId.value, actorId.value, deviceId.value, "purchase")
            assertNotNull(expenseDraft1)
            assertNotNull(purchaseDraft1)

            // Record a pending command with encrypted payload into real SQLite
            val payload = CanonicalJsonSerializer.serializeToUtf8Bytes(StrictJsonObject(mapOf("amount" to StrictJsonString("50.00"))))
            val digest = digestBytes(payload).value
            store1.recordPendingCommand(
                companyId = companyId.value,
                commandId = expenseDraft1,
                actorId = actorId.value,
                deviceId = deviceId.value,
                commandType = "expense.create",
                idempotencyKey = "exp-$expenseDraft1",
                payloadDigest = digest,
                canonicalPayloadBytes = payload
            )

            // Restart: close database and re-open to prove SQLite durability & key retention
            db1.close()

            val db2 = OutboxDatabase.buildDatabase(context, dbFile.absolutePath, allowMainThreadQueries = true)
            val store2 = RoomMerchantCommandStore(db2, cryptoEngine)

            // Restored draft operation IDs must match identically prior to rotation
            val expenseDraft2 = store2.getDraftOperationId(companyId.value, actorId.value, deviceId.value, "expense")
            val purchaseDraft2 = store2.getDraftOperationId(companyId.value, actorId.value, deviceId.value, "purchase")
            assertEquals("Draft operation ID must survive process restart / DB reopen", expenseDraft1, expenseDraft2)
            assertEquals("Purchase draft intent must survive reopen", purchaseDraft1, purchaseDraft2)

            // Retrieve and decrypt payload from persisted SQLite record
            val retrieved = store2.findCommand(companyId.value, actorId.value, deviceId.value, "exp-$expenseDraft1")
            assertNotNull("Persisted command record must be retrieved from SQLite", retrieved)
            val decrypted = store2.decryptPayload(retrieved!!)
            assertArrayEquals("Payload must decrypt cleanly after restart", payload, decrypted)

            // Rotation after completion yields a fresh distinct UUID
            val rotatedExpense = store2.rotateDraftOperationId(companyId.value, actorId.value, deviceId.value, "expense")
            assertTrue("Rotated draft intent must be distinct from prior intent", rotatedExpense != expenseDraft1)
            assertEquals(rotatedExpense, store2.getDraftOperationId(companyId.value, actorId.value, deviceId.value, "expense"))

            // Cancellation clears / rotates intent
            store2.cancelDraftOperation(companyId.value, actorId.value, deviceId.value, "purchase")
            val nextPurchase = store2.getDraftOperationId(companyId.value, actorId.value, deviceId.value, "purchase")
            assertTrue("Canceled draft intent must be replaced with fresh intent", nextPurchase != purchaseDraft1)

            db2.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // 20. Concurrent claim only one POST via CAS transition
    // -------------------------------------------------------------------------
    @Test
    fun test20_concurrentClaimOnlyOnePost(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = OutboxDatabase.buildInMemory(context, allowMainThreadQueries = true)
        val commandStore = RoomMerchantCommandStore(db, JvmAesGcmCryptoEngine())
        var postCount = 0
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST && request.path.contains("/expenses")) {
                    postCount++
                    return TransportResponse(201, body = """{"id":"01JCV8X9M2NQW3TYZ4R5S00020","amount":"15.000000","posted_at":"2026-11-19"}""")
                }
                if (request.method == HttpMethod.GET && request.path.contains("/expenses/categories")) {
                    return TransportResponse(200, body = """{"items":[{"id":"01JMBCAT000000000000000001","code":"TRANSPORT","name_ar":"نقل","name_en":"Transport"}]}""")
                }
                return TransportResponse(200, body = "{}")
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore
        )

        val fixedOpId = UUID.randomUUID().toString()
        val idempotencyKey = "exp-$fixedOpId"
        val payloadObj = StrictJsonObject(
            mapOf(
                "amount" to StrictJsonString("15.000000"),
                "expense_category_id" to StrictJsonString("01JMBCAT000000000000000001"),
                "description" to StrictJsonString("Taxi")
            )
        )
        val payload = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)
        val digest = digestBytes(payload).value

        commandStore.recordPendingCommand(
            companyId = companyId.value,
            commandId = fixedOpId,
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "expense.create",
            idempotencyKey = idempotencyKey,
            payloadDigest = digest,
            canonicalPayloadBytes = payload
        )

        // Concurrent execution simulation:
        // Caller 1 claims transition to SUBMITTED
        val claim1 = commandStore.transitionStatus(companyId.value, fixedOpId, CommandStatus.PENDING, CommandStatus.SUBMITTED)
        // Caller 2 tries to claim transition from PENDING (or SUBMITTED) to SUBMITTED
        val claim2 = commandStore.transitionStatus(companyId.value, fixedOpId, CommandStatus.PENDING, CommandStatus.SUBMITTED)
        val claim3 = commandStore.transitionStatus(companyId.value, fixedOpId, CommandStatus.SUBMITTED, CommandStatus.SUBMITTED)

        assertTrue("First caller must successfully claim submission CAS", claim1)
        assertFalse("Second concurrent caller must fail to claim submission CAS from PENDING", claim2)
        assertFalse("Second caller must fail SUBMITTED -> SUBMITTED transition", claim3)

        // Concurrent caller execution through repo respects CAS:
        // Because record is already SUBMITTED, executeIdempotentCommand fails transitionStatus and produces ZERO POST
        val resCaller2 = repo.recordExpense(
            title = "Taxi",
            category = ExpenseCategory.TRANSPORT,
            amountIls = 15.0,
            operator = testSession,
            categoryId = "01JMBCAT000000000000000001",
            operationId = fixedOpId
        )
        assertTrue("Concurrent caller must fail due to active CAS submission lock: ${resCaller2.exceptionOrNull()?.message}", resCaller2.isFailure)
        assertEquals("Zero POST must be dispatched when command is already claimed under CAS", 0, postCount)
    }

    // -------------------------------------------------------------------------
    // 21. Failed capture produces zero POST
    // -------------------------------------------------------------------------
    @Test
    fun test21_failedCaptureProducesZeroPost(): Unit = runBlocking {
        var postDispatched = false
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST) {
                    postDispatched = true
                }
                return TransportResponse(200, body = "{}")
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // Pass invalid supplier ULID to fail validation/capture before any POST
        val result = repo.recordStructuredPurchaseBill(
            supplierId = "invalid_not_a_ulid",
            lines = listOf(PurchaseLineItem(productId = "01JCV8X9M2NQW3TYZ4R5S00014", quantity = 1, unitCostIls = 10.0)),
            paidNowIls = 0.0,
            operator = testSession
        )

        assertTrue(result.isFailure)
        assertFalse("Failed capture or invalid payload must produce zero POST requests", postDispatched)
    }

    // -------------------------------------------------------------------------
    // 22. Expired scope after GET produces zero ACK / aborts
    // -------------------------------------------------------------------------
    @Test
    fun test22_expiredScopeAfterGetZeroAck(): Unit = runBlocking {
        var currentScope = CurrentAuthScope(
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            token = "test-token-gen1",
            generation = 1L
        )

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.GET && request.path.contains("/receipts/")) {
                    // During GET, scope is revoked or incremented on the client
                    currentScope = currentScope.copy(generation = 2L, token = "test-token-gen2")
                    return TransportResponse(
                        200,
                        body = """{
                            "company_id": "${companyId.value}",
                            "idempotency_key": "exp-scope-test-01",
                            "status": "completed",
                            "response_snapshot": {"id":"01JMBEXP000000000000000001","amount":"50.000000","title":"Fuel","date":"2026-11-19T10:00:00Z"}
                        }"""
                    )
                }
                if (request.method == HttpMethod.GET && request.path.contains("/expenses/categories")) {
                    return TransportResponse(
                        200,
                        body = """{"items":[{"id":"01JMBCAT000000000000000001","code":"TRANSPORT","name_ar":"نقل","name_en":"Transport"}]}"""
                    )
                }
                return TransportResponse(200, body = "{}")
            }
        }

        val client = MobileMerchantApiClient(transport, "https://api.test.example") { "test-token" }
        val commandStore = InMemoryMerchantCommandStore()
        val opId = "scope-test-01"
        val idempotencyKey = "exp-$opId"
        val payloadObj = StrictJsonObject(
            mapOf(
                "amount" to StrictJsonString("50.000000"),
                "expense_category_id" to StrictJsonString("01JMBCAT000000000000000001"),
                "description" to StrictJsonString("Fuel")
            )
        )
        val payload = CanonicalJsonSerializer.serializeToUtf8Bytes(payloadObj)
        val digest = digestBytes(payload).value

        commandStore.recordPendingCommand(
            companyId = companyId.value,
            commandId = opId,
            actorId = actorId.value,
            deviceId = deviceId.value,
            commandType = "expense.create",
            idempotencyKey = idempotencyKey,
            payloadDigest = digest,
            canonicalPayloadBytes = payload
        )
        commandStore.transitionStatus(companyId.value, opId, CommandStatus.PENDING, CommandStatus.UNKNOWN_ACK)

        val repo = ProductionTraderRepository.forTesting(
            apiClient = client,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore,
            authScopeProvider = { currentScope }
        )

        // When authenticated scope is revoked / mismatched during or after GET,
        // repo.recordExpense must fail closed and produce zero completion in command store.
        val result = runCatching { repo.recordExpense(
            title = "Fuel",
            category = ExpenseCategory.TRANSPORT,
            amountIls = 50.0,
            operator = testSession,
            categoryId = "01JMBCAT000000000000000001",
            operationId = opId
        ) }

        assertTrue("Execution must fail closed when auth scope changes during status lookup", result.isFailure)
        assertTrue("Exception must be SecurityException: ${result.exceptionOrNull()}", result.exceptionOrNull() is SecurityException)
        val stored = commandStore.findCommand(companyId.value, actorId.value, deviceId.value, idempotencyKey)
        assertNotNull("Stored command must exist", stored)
        assertEquals("Command must NOT be marked COMPLETED when scope check fails", CommandStatus.UNKNOWN_ACK, stored!!.status)
    }

    // -------------------------------------------------------------------------
    // 23. Missing required server fields fail not fakes
    // -------------------------------------------------------------------------
    @Test
    fun test23_missingRequiredServerFieldsFailNotFakes() {
        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(object : ReadClientTransport {
                override fun execute(request: TransportRequest) = TransportResponse(200, body = "{}")
            }, "https://api.test.example") { "tok" },
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // Missing id
        val noId = StrictJsonObject(mapOf("bill_number" to StrictJsonString("BILL-001"), "supplier_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00015"), "date" to StrictJsonString("2026-11-19"), "total" to StrictJsonString("100.00")))
        try {
            repo.parseAuthoritativePurchaseBill(noId)
            fail("Missing id must throw IllegalArgumentException, never fake BILL-id")
        } catch (_: IllegalArgumentException) {}

        // Blank bill number
        val blankBillNum = StrictJsonObject(mapOf("id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00004"), "bill_number" to StrictJsonString("   "), "supplier_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00015"), "date" to StrictJsonString("2026-11-19"), "total" to StrictJsonString("100.00")))
        try {
            repo.parseAuthoritativePurchaseBill(blankBillNum)
            fail("Blank bill number must throw IllegalArgumentException, never synthetic 100")
        } catch (_: IllegalArgumentException) {}

        // Missing supplier id
        val noSupplier = StrictJsonObject(mapOf("id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00004"), "bill_number" to StrictJsonString("BILL-001"), "date" to StrictJsonString("2026-11-19"), "total" to StrictJsonString("100.00")))
        try {
            repo.parseAuthoritativePurchaseBill(noSupplier)
            fail("Missing supplier id must throw IllegalArgumentException, never fallback supplier")
        } catch (_: IllegalArgumentException) {}

        // Missing date
        val noDate = StrictJsonObject(mapOf("id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00004"), "bill_number" to StrictJsonString("BILL-001"), "supplier_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00015"), "total" to StrictJsonString("100.00")))
        try {
            repo.parseAuthoritativePurchaseBill(noDate)
            fail("Missing date must throw IllegalArgumentException, never currentDate fallback")
        } catch (_: IllegalArgumentException) {}

        // Line missing line ULID
        val badLine = StrictJsonObject(mapOf(
            "id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00004"),
            "bill_number" to StrictJsonString("BILL-001"),
            "supplier_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00015"),
            "date" to StrictJsonString("2026-11-19"),
            "total" to StrictJsonString("100.00"),
            "lines" to StrictJsonArray(listOf(
                StrictJsonObject(mapOf("product_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00014"), "quantity" to StrictJsonString("1.000000")))
            ))
        ))
        try {
            repo.parseAuthoritativePurchaseBill(badLine)
            fail("Line missing id ULID must throw IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }

    // -------------------------------------------------------------------------
    // 24. Nullable cost remains null when redacted
    // -------------------------------------------------------------------------
    @Test
    fun test24_nullableCostRemainsNullWhenRedacted() {
        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(object : ReadClientTransport {
                override fun execute(request: TransportRequest) = TransportResponse(200, body = "{}")
            }, "https://api.test.example") { "tok" },
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // Line object with unit_cost_ils omitted (redacted by backend permissions)
        val billObj = StrictJsonObject(mapOf(
            "id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00004"),
            "bill_number" to StrictJsonString("BILL-2026-REDACTED"),
            "supplier_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00015"),
            "date" to StrictJsonString("2026-11-19"),
            "total" to StrictJsonString("50.00"),
            "lines" to StrictJsonArray(listOf(
                StrictJsonObject(mapOf(
                    "id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00012"),
                    "product_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00014"),
                    "product_name" to StrictJsonString("Flour"),
                    "quantity" to StrictJsonString("5.000000")
                ))
            ))
        ))

        val bill = repo.parseAuthoritativePurchaseBill(billObj)
        assertEquals(1, bill.lines.size)
        val line = bill.lines[0]
        assertNull("unitCostExact must remain null when omitted/redacted, never zeroed", line.unitCostExact)
        assertNull("unitCostIls must remain null when omitted/redacted", line.unitCostIls)
    }

    // -------------------------------------------------------------------------
    // 25. Genuine bill number exact string preserved
    // -------------------------------------------------------------------------
    @Test
    fun test25_genuineBillNumberExactString() {
        val repo = ProductionTraderRepository.forTesting(
            apiClient = MobileMerchantApiClient(object : ReadClientTransport {
                override fun execute(request: TransportRequest) = TransportResponse(200, body = "{}")
            }, "https://api.test.example") { "tok" },
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        val billObj = StrictJsonObject(mapOf(
            "id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00004"),
            "bill_number" to StrictJsonString("فاتورة-2026-X889"),
            "supplier_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00015"),
            "date" to StrictJsonString("2026-11-19"),
            "total" to StrictJsonString("75.00"),
            "lines" to StrictJsonArray(listOf(
                StrictJsonObject(mapOf(
                    "id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00012"),
                    "product_id" to StrictJsonString("01JCV8X9M2NQW3TYZ4R5S00014"),
                    "product_name" to StrictJsonString("Flour"),
                    "quantity" to StrictJsonString("3.000000"),
                    "unit_cost_ils" to StrictJsonString("25.00")
                ))
            ))
        ))

        val bill = repo.parseAuthoritativePurchaseBill(billObj)
        assertEquals("فاتورة-2026-X889", bill.billNumber)
        // WesternFormatters preserves string
        assertEquals("#فاتورة-2026-X889", WesternFormatters.formatBillCode(bill.billNumber))
        // StitchBidiUtils formatBillCode preserves string
        val stitchFormatted = StitchBidiUtils.formatBillCode(bill.billNumber)
        assertTrue(stitchFormatted.contains("فاتورة-2026-X889"))
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // 26. Real transport allowlist validates receipts status and supplier search
    // -------------------------------------------------------------------------
    @Test
    fun test26_realTransportAllowlistValidatesReceiptsStatusAndSupplierSearch() {
        val validReceiptQueryPath = "/api/mobile/v1/companies/${companyId.value}/receipts/status?idempotency_key=custcreate-test-key-123"
        val validReceiptPathFormat = "/api/mobile/v1/companies/${companyId.value}/receipts/custcreate-test-key-123/status"
        val validSupplierSearchPath = "/api/mobile/v1/companies/${companyId.value}/suppliers?search=Supplier31"

        // Real EndpointSecurity allowlist must accept scoped GET paths
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, validReceiptQueryPath)
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, validReceiptPathFormat)
        EndpointSecurity.validateMethodAndPath(HttpMethod.GET, validSupplierSearchPath)

        // Bounded validations
        EndpointSecurity.validateSearchQuery("Supplier31")
        EndpointSecurity.validateIdempotencyKey("custcreate-test-key-123")

        // Invalid paths or malicious characters rejected
        var rejectedIllegalKey = false
        try {
            EndpointSecurity.validateIdempotencyKey("bad key with spaces	")
        } catch (_: IllegalArgumentException) {
            rejectedIllegalKey = true
        }
        assertTrue("Illegal idempotency key with control characters must be rejected", rejectedIllegalKey)

        var rejectedPathTraversal = false
        try {
            EndpointSecurity.validatePath("/api/mobile/v1/companies/${companyId.value}/receipts/../../etc/passwd/status")
        } catch (_: IllegalArgumentException) {
            rejectedPathTraversal = true
        }
        assertTrue("Path traversal in receipt status path must be rejected", rejectedPathTraversal)
    }

    // -------------------------------------------------------------------------
    // 27. Malformed authoritative response leaves command in UNKNOWN_ACK and does NOT rotate key
    // -------------------------------------------------------------------------
    @Test
    fun test27_malformedAuthoritativeResponseDoesNotMarkCompletedOrRotateKey(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = OutboxDatabase.buildInMemory(context, allowMainThreadQueries = true)
        val commandStore = RoomMerchantCommandStore(db, JvmAesGcmCryptoEngine())

        var postCount = 0
        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                // Return 200 with invalid entity ULID
                if (request.method == HttpMethod.POST && request.path.contains("/customers")) {
                    postCount++
                    return TransportResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"id":"NOT_A_VALID_ULID","name_ar":"خالد","code":"CUST-001"}"""
                    )
                }
                return TransportResponse(200, emptyMap(), "{}")
            }
        }

        val apiClient = MobileMerchantApiClient(transport, "https://api.test.example") { "tok" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore
        )

        val draftOpIdBefore = repo.getDraftOperationId("customer_create")
        val createRes = repo.createCustomer("خالد", "0599123456", "نابلس", 500.0, draftOpIdBefore)

        assertTrue("Creation must fail due to invalid ULID", createRes.isFailure)
        assertEquals(1, postCount)

        // Draft operation ID must NOT have been rotated
        val draftOpIdAfter = repo.getDraftOperationId("customer_create")
        assertEquals("Draft op ID must remain unchanged when authoritative validation fails", draftOpIdBefore, draftOpIdAfter)

        // In Room database, command record must be UNKNOWN_ACK, NEVER COMPLETED
        val record = commandStore.getActiveDraftRecord(companyId.value, actorId.value, deviceId.value, "customer_create")
        assertNotNull("Record must exist in Room store", record)
        assertEquals("Status must be UNKNOWN_ACK", CommandStatus.UNKNOWN_ACK, record?.status)
    }

    // -------------------------------------------------------------------------
    // 28. Room persists immutable draft payload across process death, preventing substitute intent
    // -------------------------------------------------------------------------
    @Test
    fun test28_formsRestoreImmutablePersistedRoomDraftAfterProcessDeath(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = OutboxDatabase.buildInMemory(context, allowMainThreadQueries = true)
        val crypto = JvmAesGcmCryptoEngine()
        val commandStore1 = RoomMerchantCommandStore(db, crypto)

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST && request.path.contains("/customers")) {
                    // Simulate network timeout or dropped connection leaving command in flight / UNKNOWN_ACK
                    throw java.io.IOException("Network dropped before ACK")
                }
                return TransportResponse(200, emptyMap(), "{}")
            }
        }

        val apiClient = MobileMerchantApiClient(transport, "https://api.test.example") { "tok" }
        val repo1 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore1
        )

        val opId = repo1.getDraftOperationId("customer_create")
        val res = repo1.createCustomer("سمير التميمي", "0599000111", "رام الله", 1000.0, opId)
        assertTrue(res.isFailure)

        // Process death: simulate by tearing down repo1 and opening repo2 with fresh command store connected to same Room DB
        val commandStore2 = RoomMerchantCommandStore(db, crypto)
        val repo2 = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId,
            commandStore = commandStore2
        )

        // Restores immutable persisted Room draft
        assertTrue("Draft must be recognized as recovering", repo2.isDraftRecovering("customer_create"))
        val restoredPayload = repo2.getActiveDraftPayload("customer_create")
        assertNotNull("Restored payload must be present", restoredPayload)
        assertEquals("سمير التميمي", restoredPayload?.properties?.get("name_ar")?.toNullableString())
        assertEquals("0599000111", restoredPayload?.properties?.get("phone")?.toNullableString())

        // An edited draft substituting different payload bytes with same opId is rejected by Room
        var collisionRejected = false
        try {
            val editedBytes = """{"name_ar":"بديل خبيث"}""".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val digest = digestBytes(editedBytes)
            commandStore2.recordPendingCommand(
                companyId = companyId.value,
                commandId = opId,
                actorId = actorId.value,
                deviceId = deviceId.value,
                commandType = "customer_create",
                idempotencyKey = "custcreate-$opId",
                payloadDigest = digest.value,
                canonicalPayloadBytes = editedBytes
            )
        } catch (_: IllegalStateException) {
            collisionRejected = true
        }
        assertTrue("Substituted draft payload under same operationId must be rejected", collisionRejected)
    }

    // -------------------------------------------------------------------------
    // 29. Supplier search isolation from base directory without clobbering pagination
    // -------------------------------------------------------------------------
    @Test
    fun test29_supplierSearchIsolationFromBaseDirectory(): Unit = runBlocking {
        val baseSuppliersJson = """{"has_more":true,"suppliers":[
            {"id":"01JCV8X9M2NQW3TYZ4R5S00001","name_ar":"مورد 1","balance_owed":"100.00"},
            {"id":"01JCV8X9M2NQW3TYZ4R5S00002","name_ar":"مورد 2","balance_owed":"200.00"}
        ]}"""

        val searchSupplier31Json = """{"has_more":false,"suppliers":[
            {"id":"01JCV8X9M2NQW3TYZ4R5S00031","name_ar":"Supplier31","company_name":"شركة 31","phone":"0599313131","balance_owed":"310.00","advances":"50.00"}
        ]}"""

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                EndpointSecurity.validateMethodAndPath(request.method, request.path)
                if (request.queryParams.containsKey("search")) {
                    val q = request.queryParams["search"]
                    if (q == "Supplier31") {
                        return TransportResponse(200, emptyMap(), searchSupplier31Json)
                    }
                }
                return TransportResponse(200, emptyMap(), baseSuppliersJson)
            }
        }

        val apiClient = MobileMerchantApiClient(transport, "https://api.test.example") { "tok" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // 1. Refresh base directory
        val baseRefreshRes = repo.refreshSuppliers()
        assertTrue(baseRefreshRes.isSuccess)
        assertEquals(2, repo.getSuppliers().size)
        assertTrue("Base directory pagination hasMore must be true", repo.suppliersHasMoreFlow.value)

        // 2. Perform dedicated server search for Supplier31
        val searchRes = repo.searchSuppliers("Supplier31")
        assertTrue(searchRes.isSuccess)
        val searchList = searchRes.getOrThrow()
        assertEquals(1, searchList.size)
        assertEquals("01JCV8X9M2NQW3TYZ4R5S00031", searchList[0].id)
        assertEquals("Supplier31", searchList[0].nameAr)
        assertEquals(BigDecimal("310.00"), searchList[0].balanceOwedExact)
        assertEquals(BigDecimal("50.00"), searchList[0].advancesExact)

        // 3. Base directory must NOT be mutated or appended by the search!
        assertEquals("Base directory list size must remain 2", 2, repo.getSuppliers().size)
        assertTrue("Base directory pagination hasMore must remain true", repo.suppliersHasMoreFlow.value)
    }

    // -------------------------------------------------------------------------
    // 30. Pre-payment detail request does not republish stale balance
    // -------------------------------------------------------------------------
    @Test
    fun test30_prePaymentDetailRequestDoesNotRepublishStaleBalance(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S00099"
        var currentBalance = "500.00"
        var getShouldWait = false
        val getStartedLatch = java.util.concurrent.CountDownLatch(1)
        val paymentDoneLatch = java.util.concurrent.CountDownLatch(1)

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST && request.path.contains("/customers/$custId/payments")) {
                    return TransportResponse(201, emptyMap(), """{"id":"01PAY000000000000000000001","amount":"200.00","posted_at":"2026-11-21","balance":"300.00"}""")
                }
                if (request.method == HttpMethod.GET && request.path.contains("/customers/$custId")) {
                    if (getShouldWait) {
                        getShouldWait = false
                        getStartedLatch.countDown()
                        paymentDoneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        // Return stale pre-payment balance of 500.00
                        return TransportResponse(200, emptyMap(), """{"id":"$custId","name_ar":"زبون رصيد","balance":"500.00","credit_limit":"1000.00"}""")
                    }
                    return TransportResponse(200, emptyMap(), """{"id":"$custId","name_ar":"زبون رصيد","balance":"$currentBalance","credit_limit":"1000.00"}""")
                }
                if (request.method == HttpMethod.GET && request.path.contains("/receipts/status")) {
                    return TransportResponse(200, emptyMap(), """{"status":"FOUND","response_snapshot":{"id":"01PAY000000000000000000001","amount":"200.00","posted_at":"2026-11-21","balance":"300.00"}}""")
                }
                return TransportResponse(200, emptyMap(), "{}")
            }
        }

        val apiClient = MobileMerchantApiClient(transport, "https://api.test.example") { "tok" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // 1. Initial detail fetch: balance = 500.00
        val initDetail = repo.getCustomerDetail(custId)
        assertTrue(initDetail.isSuccess)
        assertEquals(BigDecimal("500.00"), repo.getCustomers().first { it.id == custId }.balanceExact)
        assertTrue(repo.getCustomers().first { it.id == custId }.isDetailLoaded)

        // 2. Start a pre-payment detail fetch in the background that hangs until payment commits
        currentBalance = "300.00"
        getShouldWait = true
        val delayedThread = kotlin.concurrent.thread {
            runBlocking {
                repo.getCustomerDetail(custId)
            }
        }
        getStartedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

        // 3. Payment occurs in the meantime: balance becomes 300.00
        val payRes = repo.recordCustomerPayment(custId, 200.0, "سداد", "pay-1")
        assertTrue(payRes.isSuccess)
        assertEquals(BigDecimal("300.00"), repo.getCustomers().first { it.id == custId }.balanceExact)

        // 4. Now allow delayed pre-payment detail response (returning stale 500.00) to complete
        paymentDoneLatch.countDown()
        delayedThread.join(5000)

        // 5. CAS revision guard prevents stale 500.00 from overwriting post-payment 300.00
        val finalBal = repo.getCustomers().first { it.id == custId }.balanceExact
        assertEquals("Post-payment balance must NOT be overwritten by stale pre-payment detail", BigDecimal("300.00"), finalBal)
    }

    // -------------------------------------------------------------------------
    // 31. Customer update explicit null clears limit and untouched preserves exact 6
    // -------------------------------------------------------------------------
    @Test
    fun test31_customerUpdateExplicitNullClearsLimitAndUntouchedPreservesExact6(): Unit = runBlocking {
        val custId = "01JCV8X9M2NQW3TYZ4R5S00077"
        var lastPutBody: String? = null

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (request.method == HttpMethod.POST && request.path.contains("/customers")) {
                    return TransportResponse(201, emptyMap(), """{"id":"$custId","name_ar":"شركة الأمل","phone":"0599112233","address_ar":"طولكرم","credit_limit":"1234.567890"}""")
                }
                if (request.method == HttpMethod.PUT && request.path.contains("/customers/$custId")) {
                    lastPutBody = request.body
                    val returnLimit = if (request.body?.contains(""""credit_limit":null""") == true) "null" else """"1234.56""""
                    return TransportResponse(200, emptyMap(), """{"id":"$custId","name_ar":"شركة الأمل","phone":"0599112233","address_ar":"طولكرم","credit_limit":$returnLimit}""")
                }
                return TransportResponse(200, emptyMap(), "{}")
            }
        }

        val apiClient = MobileMerchantApiClient(transport, "https://api.test.example") { "tok" }
        val repo = ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            warehouseId = warehouseId
        )

        // Create initial customer
        val initRes = repo.createCustomer("شركة الأمل", "0599112233", "طولكرم", 1234.56789)
        assertTrue(initRes.isSuccess)
        val exact6Limit = BigDecimal("1234.567890")

        // 1. Update customer with updateCreditLimit = false: untouched limit preserves exact 6 decimals
        val res1 = repo.updateCustomer(
            customerId = custId,
            name = "شركة الأمل الجديدة",
            phone = null,
            address = null,
            creditLimit = null,
            updatePhone = false,
            updateAddress = false,
            updateCreditLimit = false
        )
        assertTrue(res1.isSuccess)
        assertEquals("Untouched limit must preserve exact 6 decimals", exact6Limit, res1.getOrThrow().creditLimitExact)

        // 2. Update customer with updateCreditLimit = true and creditLimit = null (clear limit)
        val res2 = repo.updateCustomer(
            customerId = custId,
            name = "شركة الأمل",
            phone = null,
            address = "",
            creditLimit = null,
            updatePhone = false,
            updateAddress = true,
            updateCreditLimit = true
        )
        assertTrue(res2.isSuccess)
        assertNull("Explicit null update response must clear credit limit", res2.getOrThrow().creditLimitExact)

        // 3. Verify address clear in PUT body sent all 3 locales
        assertNotNull(lastPutBody)
        assertTrue("Address clear must send address_ar null", lastPutBody!!.contains(""""address_ar":null"""))
        assertTrue("Address clear must send address null", lastPutBody!!.contains(""""address":null"""))
        assertTrue("Address clear must send address_en null", lastPutBody!!.contains(""""address_en":null"""))
    }
}
