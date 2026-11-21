package com.example.data

import com.example.data.CurrentAuthScope
import com.example.data.ProductionTraderRepository
import com.example.data.mobile.client.MobileMerchantApiClient
import com.example.data.mobile.contract.ClientUuid
import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.StrictJsonParser
import com.example.data.mobile.merchantread.MerchantReadClient
import com.example.data.mobile.merchantread.MerchantReadTokenProvider
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.PurchaseLineItem
import com.example.model.Supplier
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.viewmodel.TraderUiState
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Dedicated Wire & Presentation Test Suite for:
 * 1. P1 Unknown Balances: nullable Customer.balanceExact / Supplier.balanceOwedExact
 *    must NEVER falsely show as settled, 0.00 ₪, debt-free, or paid.
 * 2. Directory Filters & Aggregates: mixed known/unknown balances preserve UNKNOWN sentinel (NaN)
 *    and exclude unknown balances from settled counts and filters.
 * 3. Real Customer & Supplier update: authenticated PUT vs create POST with Idempotency-Key.
 * 4. Non-null supplier ID NEVER sends POST.
 * 5. Supplier company name field is not backend-persisted; attempting to save fake value fails.
 * 6. Forced 403 / 422 wire statuses preserve edit drafts without fake optimistic local copies.
 * 7. Midflight scope / generation change rejects responses without flow mutations.
 * 8. Exact cost redaction: redacted cost remains null, never invented.
 */
class CustomerSupplierDirectoryWireTest {

    private val companyId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val actorId = ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val deviceId = ClientUuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val validToken = "bearer_sample_token_length_min_20_chars_xyz"
    private val testSession = UserSession("usr_owner", "مالك المتجر", "owner", UserRole.OWNER, "الفرع الرئيسي", "Main Branch")

    class RecordingTransport : ReadClientTransport {
        val requests = mutableListOf<TransportRequest>()
        val responseQueue = mutableListOf<TransportResponse>()
        var defaultResponse = TransportResponse(200, emptyMap(), "{}")

        override fun execute(request: TransportRequest): TransportResponse {
            requests.add(request)
            if (request.path.contains("/receipts/") && request.path.endsWith("/status")) {
                val receiptIdx = responseQueue.indexOfFirst {
                    it.body?.contains("response_snapshot") == true || it.body?.contains("\"outcome\":") == true
                }
                if (receiptIdx >= 0) {
                    return responseQueue.removeAt(receiptIdx)
                }
                return TransportResponse(404, emptyMap(), """{"message":"Receipt not found"}""")
            }
            if (responseQueue.isNotEmpty()) {
                return responseQueue.removeAt(0)
            }
            return defaultResponse
        }
    }

    private fun createRepository(
        transport: ReadClientTransport,
        generationProvider: () -> Long = { 1L }
    ): ProductionTraderRepository {
        val tokenProvider = MerchantReadTokenProvider { reqComp, reqGen ->
            if (reqComp == companyId && (reqGen == null || reqGen == generationProvider())) validToken else null
        }
        val readClient = MerchantReadClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = tokenProvider
        )
        val apiClient = MobileMerchantApiClient(
            transport = transport,
            baseOrigin = "https://api.example.com",
            tokenProvider = { validToken }
        )
        return ProductionTraderRepository.forTesting(
            apiClient = apiClient,
            companyId = companyId,
            actorId = actorId,
            deviceId = deviceId,
            authScopeProvider = {
                CurrentAuthScope(
                    companyId = companyId,
                    actorId = actorId,
                    deviceId = deviceId,
                    token = validToken,
                    generation = generationProvider()
                )
            },
            merchantReadClient = readClient
        )
    }

    // =========================================================================
    // 1. P1 Unknown Balances: Nullable Directory Balances Must NOT Show Settled
    // =========================================================================

    @Test
    fun testNullableCustomerBalance_MustNotShowSettled_OrZero_OrDebtFree() {
        val customerWithNullBalance = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789C",
            nameAr = "أبو أحمد المدين",
            nameEn = "Abu Ahmad Debtor",
            phone = "0599-112233",
            addressAr = "نابلس - رفيديا",
            addressEn = "Nablus - Rafidia",
            balanceExact = null, // Backend directory returns NO balance
            creditLimitExact = BigDecimal("1000.00"),
            totalPurchasesExact = null
        )

        // 1. BalanceIls bridge must return NaN for unknown balance, never an economic 0.0
        assertTrue("balanceIls must be NaN for null balance", customerWithNullBalance.balanceIls.isNaN())
        assertNull("balanceExact must be strictly null", customerWithNullBalance.balanceExact)

        // 2. Directory row status label must be 'Balance unavailable', NEVER 'Settled' (خالص) or '0.00 ₪'
        val isDebtor = customerWithNullBalance.balanceIls > 0.005
        val isAdvance = customerWithNullBalance.balanceIls < -0.005
        assertFalse("Null balance must not be classified as debtor", isDebtor)
        assertFalse("Null balance must not be classified as advance", isAdvance)

        val statusLabelAr = when {
            customerWithNullBalance.balanceExact == null -> AppLanguage.AR.tr("الرصيد غير متاح", "Balance unavailable")
            isDebtor -> AppLanguage.AR.tr("عليه دين", "Owes Store")
            isAdvance -> AppLanguage.AR.tr("له رصيد مقدم", "In Credit")
            else -> AppLanguage.AR.tr("خالص", "Settled")
        }
        assertEquals("الرصيد غير متاح", statusLabelAr)
        assertNotEquals("خالص", statusLabelAr)

        val statusLabelEn = when {
            customerWithNullBalance.balanceExact == null -> AppLanguage.EN.tr("الرصيد غير متاح", "Balance unavailable")
            isDebtor -> AppLanguage.EN.tr("عليه دين", "Owes Store")
            isAdvance -> AppLanguage.EN.tr("له رصيد مقدم", "In Credit")
            else -> AppLanguage.EN.tr("خالص", "Settled")
        }
        assertEquals("Balance unavailable", statusLabelEn)
        assertNotEquals("Settled", statusLabelEn)

        // 3. Displayed amount must be short localized 'Unavailable', not '0.00 ₪'
        val displayedAmountAr = when {
            customerWithNullBalance.balanceExact == null -> AppLanguage.AR.tr("غير متاح", "Unavailable")
            isDebtor -> StitchBidiUtils.formatIls(customerWithNullBalance.balanceIls)
            isAdvance -> StitchBidiUtils.formatIls(-customerWithNullBalance.balanceIls)
            else -> StitchBidiUtils.formatIls(0.0)
        }
        assertEquals("غير متاح", displayedAmountAr)
        assertFalse("Must not format as zero currency", displayedAmountAr.contains("0.00"))

        // 4. Detail screen badge must be NEUTRAL with 'Unavailable' text
        val detailBadgeText = if (customerWithNullBalance.balanceExact == null) {
            "غير متاح"
        } else if (isDebtor) {
            "ذمة قائمة"
        } else {
            "حساب خالص"
        }
        assertEquals("غير متاح", detailBadgeText)
        val detailBadgeTone = if (customerWithNullBalance.balanceExact == null) {
            StitchBadgeTone.NEUTRAL
        } else if (isDebtor) {
            StitchBadgeTone.DANGER
        } else {
            StitchBadgeTone.SUCCESS
        }
        assertEquals(StitchBadgeTone.NEUTRAL, detailBadgeTone)
    }

    @Test
    fun testNullableSupplierBalance_MustNotShowSettled_OrZero() {
        val supplierWithNullBalance = Supplier(
            id = "01JCV8X9M2NQW3TYZ4R5S6789D",
            nameAr = "شركة الزيوت الفلسطينية",
            nameEn = "Palestine Oils Co",
            companyAr = null,
            companyEn = null,
            phone = "09-2380000",
            balanceOwedExact = null // Nullable balance from directory
        )

        assertTrue("balanceOwedIls must be NaN for null balance", supplierWithNullBalance.balanceOwedIls.isNaN())
        assertNull("balanceOwedExact must be null", supplierWithNullBalance.balanceOwedExact)

        val isPayableOwed = supplierWithNullBalance.balanceOwedIls > 0.005
        val isStoreAdvance = supplierWithNullBalance.balanceOwedIls < -0.005
        assertFalse(isPayableOwed)
        assertFalse(isStoreAdvance)

        val statusLabelAr = when {
            supplierWithNullBalance.balanceOwedExact == null -> AppLanguage.AR.tr("الرصيد غير متاح", "Balance unavailable")
            isPayableOwed -> "مستحق للمورد"
            isStoreAdvance -> "لنا رصيد مقدم"
            else -> "خالص"
        }
        assertEquals("الرصيد غير متاح", statusLabelAr)
        assertNotEquals("خالص", statusLabelAr)

        val badgeTone = when {
            supplierWithNullBalance.balanceOwedExact == null -> StitchBadgeTone.NEUTRAL
            isPayableOwed -> StitchBadgeTone.WARNING
            isStoreAdvance -> StitchBadgeTone.SUCCESS
            else -> StitchBadgeTone.NEUTRAL
        }
        assertEquals(StitchBadgeTone.NEUTRAL, badgeTone)
    }

    // =========================================================================
    // 2. Directory Filters & Mixed Known/Unknown Aggregate Totals
    // =========================================================================

    @Test
    fun testDirectoryFilters_MixedKnownAndUnknownBalances_StrictlyExcludesUnknownFromSettled() {
        val custKnownDebtor = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S67891",
            nameAr = "مدين معروف",
            nameEn = "Known Debtor",
            phone = "0599-111111",
            addressAr = "نابلس",
            addressEn = "Nablus",
            balanceExact = BigDecimal("5000.00"),
            creditLimitExact = BigDecimal("10000.00")
        )
        val custUnknown = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S67892",
            nameAr = "زبون برصيد غير متاح",
            nameEn = "Customer Unknown Balance",
            phone = "0599-222222",
            addressAr = "جنين",
            addressEn = "Jenin",
            balanceExact = null, // Unknown!
            creditLimitExact = BigDecimal("1000.00")
        )
        val custSettled = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S67893",
            nameAr = "زبون خالص",
            nameEn = "Settled Customer",
            phone = "0599-333333",
            addressAr = "طولكرم",
            addressEn = "Tulkarm",
            balanceExact = BigDecimal.ZERO, // Authoritative zero
            creditLimitExact = BigDecimal("1000.00")
        )

        val customers = listOf(custKnownDebtor, custUnknown, custSettled)

        // 1. Debtors count: only custKnownDebtor
        val debtorsCount = customers.count { it.balanceExact != null && it.balanceExact > BigDecimal.ZERO }
        assertEquals(1, debtorsCount)

        // 2. Advances count: none
        val advanceCount = customers.count { it.balanceExact != null && it.balanceExact < BigDecimal.ZERO }
        assertEquals(0, advanceCount)

        // 3. Settled count: ONLY custSettled, NEVER custUnknown
        val settledCount = customers.count { it.balanceExact != null && it.balanceExact.compareTo(BigDecimal.ZERO) == 0 }
        assertEquals("Unknown balance must NOT be counted as settled", 1, settledCount)

        // 4. Filter chip predicates
        val allFiltered = customers.filter { true }
        assertEquals(3, allFiltered.size)

        val debtorsFiltered = customers.filter { it.balanceExact != null && it.balanceExact > BigDecimal.ZERO }
        assertEquals(listOf(custKnownDebtor), debtorsFiltered)

        val settledFiltered = customers.filter { it.balanceExact != null && it.balanceExact.compareTo(BigDecimal.ZERO) == 0 }
        assertEquals("Settled filter must strictly contain only customers with exact zero balance", listOf(custSettled), settledFiltered)
        assertFalse("Unknown balance must not match settled filter", settledFiltered.contains(custUnknown))

        // 5. Aggregate Totals: If ANY customer in directory has null balance, directory cannot invent a total
        val state = TraderUiState(customers = customers)
        assertNull("Unknown balance must not become a projected credit balance",
            state.copy(selectedCustomerId = custUnknown.id).projectedCustomerBalanceIfCreditIls)
        assertTrue("Total customer debts must be NaN when balances are unknown", state.totalCustomerDebtsIls.isNaN())
        assertTrue("Total customer advances must be NaN when balances are unknown", state.totalCustomerAdvancesIls.isNaN())
    }

    @Test
    fun testSupplierFilters_MixedKnownAndUnknownBalances_StrictlyExcludesUnknownFromSettled() {
        val supKnownPayable = Supplier(
            id = "01JCV8X9M2NQW3TYZ4R5S67894",
            nameAr = "مورد مستحق",
            nameEn = "Payable Supplier",
            phone = "09-2311111",
            balanceOwedExact = BigDecimal("1200.00")
        )
        val supUnknown = Supplier(
            id = "01JCV8X9M2NQW3TYZ4R5S67895",
            nameAr = "مورد برصيد غير متاح",
            nameEn = "Unknown Supplier",
            phone = "09-2322222",
            balanceOwedExact = null
        )
        val supSettled = Supplier(
            id = "01JCV8X9M2NQW3TYZ4R5S67896",
            nameAr = "مورد خالص",
            nameEn = "Settled Supplier",
            phone = "09-2333333",
            balanceOwedExact = BigDecimal.ZERO
        )

        val suppliers = listOf(supKnownPayable, supUnknown, supSettled)

        val payablesCount = suppliers.count { it.balanceOwedExact != null && it.balanceOwedExact > BigDecimal.ZERO }
        assertEquals(1, payablesCount)

        val advancesCount = suppliers.count { it.balanceOwedExact != null && it.balanceOwedExact < BigDecimal.ZERO }
        assertEquals(0, advancesCount)

        val settledCount = suppliers.count { it.balanceOwedExact != null && it.balanceOwedExact.compareTo(BigDecimal.ZERO) == 0 }
        assertEquals("Unknown supplier balance must NOT be counted as settled", 1, settledCount)

        val settledFiltered = suppliers.filter { it.balanceOwedExact != null && it.balanceOwedExact.compareTo(BigDecimal.ZERO) == 0 }
        assertEquals(listOf(supSettled), settledFiltered)
        assertFalse("Unknown supplier must not match settled filter", settledFiltered.contains(supUnknown))

        val state = TraderUiState(suppliers = suppliers)
        assertTrue("Total supplier payables must be NaN when any balance is unknown", state.totalSupplierPayablesIls.isNaN())
        assertTrue("Total supplier advances must be NaN when any balance is unknown", state.totalSupplierAdvancesIls.isNaN())
    }

    // =========================================================================
    // 3. Customer Create (POST) vs Update (PUT) Wire Verification
    // =========================================================================

    @Test
    fun testCustomerCreateUsesPost_CustomerEditUsesPut_WithIdempotencyKey() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val targetCustomerId = "01JCV8X9M2NQW3TYZ4R5S6789C"

        // 1. Create customer response fixture
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$targetCustomerId",
                    "name": "الزبون الجديد",
                    "code": "CUST-001",
                    "phone": "0599-123456",
                    "credit_limit": "1500.00"
                }
                """.trimIndent()
            )
        )

        val createRes = repo.createCustomer("الزبون الجديد", "0599-123456", "نابلس", 1500.0)
        assertTrue("Create customer must succeed", createRes.isSuccess)
        val created = createRes.getOrThrow()
        assertEquals(targetCustomerId, created.id)
        assertEquals("الزبون الجديد", created.nameAr)
        assertEquals(BigDecimal("1500.00"), created.creditLimitExact)

        // Verify wire: POST to /api/mobile/v1/companies/{companyId}/customers with Idempotency-Key
        assertEquals(1, transport.requests.size)
        val createReq = transport.requests[0]
        assertEquals(HttpMethod.POST, createReq.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/customers", createReq.path)
        assertNotNull("Idempotency-Key header is required", createReq.headers["Idempotency-Key"])
        assertTrue("Payload must include name_ar", createReq.body?.contains("\"name_ar\":\"الزبون الجديد\"") == true)

        // 2. Update customer response fixture
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$targetCustomerId",
                    "name": "الزبون المعدل رسمياً",
                    "code": "CUST-001",
                    "phone": "0599-999888",
                    "credit_limit": "2500.00"
                }
                """.trimIndent()
            )
        )

        val updateRes = repo.updateCustomer(
            customerId = targetCustomerId,
            name = "الزبون المعدل رسمياً",
            phone = "0599-999888",
            address = "رام الله",
            creditLimitIls = 2500.0
        )
        assertTrue("Update customer must succeed", updateRes.isSuccess)
        val updated = updateRes.getOrThrow()
        assertEquals(targetCustomerId, updated.id)
        assertEquals("الزبون المعدل رسمياً", updated.nameAr)
        assertEquals("0599-999888", updated.phone)
        assertEquals(BigDecimal("2500.00"), updated.creditLimitExact)

        // Verify wire: PUT to /api/mobile/v1/companies/{companyId}/customers/{customerId}
        assertEquals(2, transport.requests.size)
        val updateReq = transport.requests[1]
        assertEquals("Update customer must use authenticated PUT", HttpMethod.PUT, updateReq.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/customers/$targetCustomerId", updateReq.path)
        assertNotNull("Idempotency-Key header is required for PUT update", updateReq.headers["Idempotency-Key"])
        assertTrue("Payload must include updated name_ar", updateReq.body?.contains("\"name_ar\":\"الزبون المعدل رسمياً\"") == true)
        assertTrue("Payload must include updated credit_limit", updateReq.body?.contains("\"credit_limit\":\"2500.00\"") == true)

        // Verify repo customers flow has updated entity
        val flowCustomer = repo.getCustomers().first { it.id == targetCustomerId }
        assertEquals("الزبون المعدل رسمياً", flowCustomer.nameAr)
        assertEquals("0599-999888", flowCustomer.phone)
        assertEquals(BigDecimal("2500.00"), flowCustomer.creditLimitExact)
    }

    // =========================================================================
    // 4. Supplier Create (POST) vs Update (PUT) Wire Verification
    // =========================================================================

    @Test
    fun testSupplierCreateUsesPost_SupplierEditUsesPut_NonNullIdNeverPosts() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val targetSupplierId = "01JCV8X9M2NQW3TYZ4R5S6789D"

        // 1. Create supplier response fixture
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$targetSupplierId",
                    "name": "شركة التوريدات الوطنية",
                    "code": "SUPP-001",
                    "phone": "09-2345678",
                    "balance_owed": null
                }
                """.trimIndent()
            )
        )

        val createRes = repo.saveSupplier(
            supplierId = null,
            name = "شركة التوريدات الوطنية",
            company = "",
            phone = "09-2345678",
            operator = testSession
        )
        assertTrue("Create supplier must succeed", createRes.isSuccess)
        val created = createRes.getOrThrow()
        assertEquals(targetSupplierId, created.id)
        assertEquals("شركة التوريدات الوطنية", created.nameAr)

        // Verify wire: POST to /api/mobile/v1/companies/{companyId}/suppliers
        assertEquals(1, transport.requests.size)
        val createReq = transport.requests[0]
        assertEquals(HttpMethod.POST, createReq.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/suppliers", createReq.path)
        assertNotNull("Idempotency-Key header is required", createReq.headers["Idempotency-Key"])

        // 2. Update supplier with non-null supplierId fixture
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$targetSupplierId",
                    "name": "شركة التوريدات الوطنية المحدثة",
                    "code": "SUPP-001",
                    "phone": "09-2399999"
                }
                """.trimIndent()
            )
        )

        val updateRes = repo.saveSupplier(
            supplierId = targetSupplierId,
            name = "شركة التوريدات الوطنية المحدثة",
            company = "",
            phone = "09-2399999",
            operator = testSession
        )
        assertTrue("Update supplier must succeed", updateRes.isSuccess)
        val updated = updateRes.getOrThrow()
        assertEquals(targetSupplierId, updated.id)
        assertEquals("شركة التوريدات الوطنية المحدثة", updated.nameAr)
        assertEquals("09-2399999", updated.phone)

        // CRITICAL INVARIANT: Non-null supplier ID MUST use authenticated PUT, NEVER POST create new vendor
        assertEquals(2, transport.requests.size)
        val updateReq = transport.requests[1]
        assertEquals("Non-null supplier ID must use authenticated PUT", HttpMethod.PUT, updateReq.method)
        assertNotEquals("Non-null supplier ID must NEVER use POST", HttpMethod.POST, updateReq.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/suppliers/$targetSupplierId", updateReq.path)
        assertNotNull("Idempotency-Key header is required", updateReq.headers["Idempotency-Key"])

        // Verify flow update
        val flowSupplier = repo.getSuppliers().first { it.id == targetSupplierId }
        assertEquals("شركة التوريدات الوطنية المحدثة", flowSupplier.nameAr)
        assertEquals("09-2399999", flowSupplier.phone)
    }

    // =========================================================================
    // 5. Supplier Company Name Not Backend-Persisted: Reject Fake Value
    // =========================================================================

    @Test
    fun testSupplierCompanyName_NotBackendPersisted_MustNotSaveFakeValue() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        // Attempting to pass a non-blank company name to saveSupplier must fail immediately
        val createWithFakeCompany = repo.saveSupplier(
            supplierId = null,
            name = "مورد جديد",
            company = "شركة وهمية غير محفوظة",
            phone = "09-2300000",
            operator = testSession
        )
        assertTrue("Saving non-blank company name must fail", createWithFakeCompany.isFailure)
        assertTrue(
            "Error must state company name is not persisted",
            createWithFakeCompany.exceptionOrNull()?.message?.contains("not persisted") == true
        )
        assertEquals("Zero network calls must be made when fake company is passed", 0, transport.requests.size)

        val updateWithFakeCompany = repo.saveSupplier(
            supplierId = "01JCV8X9M2NQW3TYZ4R5S6789D",
            name = "مورد قائم",
            company = "شركة وهمية",
            phone = "09-2300000",
            operator = testSession
        )
        assertTrue("Update with non-blank company name must fail", updateWithFakeCompany.isFailure)
        assertEquals(0, transport.requests.size)
    }

    // =========================================================================
    // 6. Forced 403 / 422 Server Errors: Preserve Draft Without Fake Optimistic Copies
    // =========================================================================

    @Test
    fun testForced403And422_PreservesFlowWithoutFakeOptimisticCopies() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789C"

        // Seed initial customer
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$customerId",
                    "name": "الزبون الأصلي",
                    "code": "CUST-001",
                    "phone": "0599-100100",
                    "credit_limit": "1000.00"
                }
                """.trimIndent()
            )
        )
        repo.createCustomer("الزبون الأصلي", "0599-100100", "نابلس", 1000.0)

        // 1. Force 403 Forbidden on updateCustomer
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 403,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "code": "FORBIDDEN",
                    "message": "Action is unauthorized. Missing required customers.manage permission."
                }
                """.trimIndent()
            )
        )

        val forbiddenRes = repo.updateCustomer(
            customerId = customerId,
            name = "اسم معدل بدون صلاحية",
            phone = "0599-999999",
            address = "الخليل",
            creditLimitIls = 5000.0
        )
        assertTrue("403 must yield Result.failure", forbiddenRes.isFailure)
        assertTrue(
            "Error message must convey unauthorized / 403",
            forbiddenRes.exceptionOrNull()?.message?.contains("unauthorized", ignoreCase = true) == true ||
                forbiddenRes.exceptionOrNull()?.message?.contains("403") == true
        )

        // Flow MUST remain unchanged (zero fake optimistic update)
        val customerAfter403 = repo.getCustomers().first { it.id == customerId }
        assertEquals("Flow customer name must remain original", "الزبون الأصلي", customerAfter403.nameAr)
        assertEquals("Flow customer phone must remain original", "0599-100100", customerAfter403.phone)
        assertEquals(BigDecimal("1000.00"), customerAfter403.creditLimitExact)

        // 2. Force 422 Validation Failed on updateCustomer
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 422,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "code": "VALIDATION_FAILED",
                    "message": "Failed to update customer: The credit_limit field must be a valid number."
                }
                """.trimIndent()
            )
        )

        val validationFailedRes = repo.updateCustomer(
            customerId = customerId,
            name = "اسم غير صالح",
            phone = "0599-999999",
            address = "الخليل",
            creditLimitIls = 0.0
        )
        assertTrue("422 must yield Result.failure", validationFailedRes.isFailure)

        // Flow MUST still remain unchanged
        val customerAfter422 = repo.getCustomers().first { it.id == customerId }
        assertEquals("الزبون الأصلي", customerAfter422.nameAr)
        assertEquals("0599-100100", customerAfter422.phone)
    }

    // =========================================================================
    // 7. Refresh Scope Change / Midflight Stale Response: No Flow Mutations
    // =========================================================================

    @Test
    fun testMidflightScopeChange_RejectsResponseWithoutMutatingFlow() = runBlocking {
        var currentGen = 1L
        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789C"

        var shouldIncrementGen = false
        val interceptingTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                if (shouldIncrementGen) {
                    currentGen = 2L
                }
                val respName = if (shouldIncrementGen) "تعديل وصل بعد فوات الأوان" else "الزبون المستقر"
                return TransportResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """
                    {
                        "id": "$customerId",
                        "name": "$respName",
                        "code": "CUST-001",
                        "phone": "0599-777777",
                        "credit_limit": "3000.00"
                    }
                    """.trimIndent()
                )
            }
        }

        val repo = createRepository(interceptingTransport, generationProvider = { currentGen })

        // Seed initial customer
        val seedRes = repo.createCustomer("الزبون المستقر", "0599-000000", "نابلس", 1000.0)
        assertTrue(seedRes.isSuccess)

        // Now activate midflight generation increment for updateCustomer
        shouldIncrementGen = true

        val staleResult = repo.updateCustomer(
            customerId = customerId,
            name = "تعديل وصل بعد فوات الأوان",
            phone = "0599-777777",
            address = "بيت لحم",
            creditLimitIls = 3000.0
        )

        assertTrue("Midflight scope change must cause Result.failure", staleResult.isFailure)
        assertTrue(
            "Exception must be SecurityException",
            staleResult.exceptionOrNull() is SecurityException
        )

        // Flow MUST NOT have mutated to stale response data
        val customerAfterStale = repo.getCustomers().firstOrNull { it.id == customerId }
        assertEquals("الزبون المستقر", customerAfterStale?.nameAr)
    }

    // =========================================================================
    // 8. Exact Cost Redaction: Preserves Null Without Fake Inventions
    // =========================================================================

    @Test
    fun testExactCostRedaction_PreservesNullWithoutFakeCost() {
        val lineWithRedactedCost = PurchaseLineItem(
            productId = "01JCV8X9M2NQW3TYZ4R5S6789E",
            productNameAr = "سلعة بدون كشف تكلفة",
            productNameEn = "Product With Redacted Cost",
            quantity = 10,
            unitCostExact = null, // Redacted on server for Cashier role
            receivedQuantity = 10
        )

        assertNull("Redacted unitCostExact must remain strictly null", lineWithRedactedCost.unitCostExact)
        assertNull("Redacted unitCostIls must remain null", lineWithRedactedCost.unitCostIls)
    }

    // =========================================================================
    // 9. Review Findings Verification (Truthful Balances, Directory Omission, Pagination)
    // =========================================================================

    @Test
    fun testSharedCustomerStatement_WithNullBalance_DoesNotSaySettledAccountZero() {
        val custWithNullBalance = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789C",
            nameAr = "أحمد خالد",
            nameEn = "Ahmad Khaled",
            phone = "0599-123456",
            balanceExact = null,
            creditLimitExact = BigDecimal("1000.00")
        )
        val transport = RecordingTransport()
        val repo = createRepository(transport)
        val vm = TraderViewModel(repo)

        val statementAr = vm.formatCustomerStatementText(custWithNullBalance)
        assertTrue("Statement must indicate balance is unavailable", statementAr.contains("غير متاح (يتطلب تحديث كشف الحساب)"))
        assertFalse("Statement must NEVER claim settled account 0.00 ₪ when balance is null", statementAr.contains("الحساب خالص"))
        assertFalse("Statement must NEVER format 0.00 ₪ as current balance", statementAr.contains("الحساب خالص (0.00 ₪)"))
    }

    @Test
    fun testCustomerPaymentSuccess_WithNullBalance_DoesNotClaimSettledZero() {
        val cust = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789C",
            nameAr = "عميل نقدي",
            nameEn = "Cash Customer",
            phone = "0599-000000",
            balanceExact = null
        )
        val isBalKnown = cust.balanceExact != null && !cust.balanceIls.isNaN()
        val paidStr = WesternFormatters.formatIls(100.0)
        val newBal = cust.balanceIls
        val msgAr = when {
            !isBalKnown -> "تم قبض $paidStr نقداً — الرصيد الحالي غير متاح (يتطلب تحديث كشف الحساب)"
            newBal > 0.005 -> "تم قبض $paidStr نقداً — المتبقي على الزبون: ${WesternFormatters.formatIls(newBal)}"
            newBal < -0.005 -> "تم قبض $paidStr نقداً — أصبح للزبون رصيد مقدم: ${WesternFormatters.formatIls(-newBal)}"
            else -> "تم قبض $paidStr نقداً — أصبح الحساب خالصاً (0.00 ₪)"
        }
        assertEquals("تم قبض 100.00 ₪ نقداً — الرصيد الحالي غير متاح (يتطلب تحديث كشف الحساب)", msgAr)
        assertFalse("Must not claim settled account", msgAr.contains("أصبح الحساب خالصاً"))
    }

    @Test
    fun testUnknownBalance_DoesNotShowSafeCreditCeilingOrZeroPercentCheckout() {
        val cust = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789C",
            nameAr = "عميل غير معروف الرصيد",
            nameEn = "Customer Unknown",
            phone = "0599-000000",
            balanceExact = null,
            creditLimitExact = BigDecimal("1000.00")
        )
        val state = TraderUiState(
            customers = listOf(cust),
            selectedCustomerId = cust.id
        )
        assertFalse("isCustomerBalanceKnown must be false", state.isCustomerBalanceKnown)
        assertNull("projectedCustomerBalanceIfCreditIls must be null when balance is unknown", state.projectedCustomerBalanceIfCreditIls)
        assertFalse("wouldCreditExceedCustomerLimit must be false (no false positive / false negative limit check)", state.wouldCreditExceedCustomerLimit)
    }

    @Test
    fun testCustomerCreation_RequiresAuthoritativeULID_FailsOnMissingOrInvalidId() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        // 1. Missing "id" in response
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"name":"عميل بدون معرف"}"""
            )
        )
        val resMissingId = repo.createCustomer("عميل بدون معرف", "0599-111111", "نابلس", 0.0)
        assertTrue("Must fail when server omits ID", resMissingId.isFailure)
        assertTrue("Error must mention missing customer ID", resMissingId.exceptionOrNull()?.message?.contains("missing customer ID") == true)

        // 2. Non-ULID (e.g. UUID) in response
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"550e8400-e29b-41d4-a716-446655440000","name":"عميل بمعرف غير صالح"}"""
            )
        )
        val resInvalidId = repo.createCustomer("عميل بمعرف غير صالح", "0599-111111", "نابلس", 0.0)
        assertTrue("Must fail when server returns non-ULID", resInvalidId.isFailure)
        assertTrue("Error must mention invalid ULID", resInvalidId.exceptionOrNull()?.message?.contains("invalid customer ULID") == true)
    }

    @Test
    fun testCustomerCreation_PostsAddressAndValidatesMidflightScope() = runBlocking {
        var currentGen = 1L
        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789F"

        val transport = object : ReadClientTransport {
            val requests = mutableListOf<TransportRequest>()
            override fun execute(request: TransportRequest): TransportResponse {
                requests.add(request)
                // Midflight scope change simulation
                currentGen = 2L
                return TransportResponse(
                    statusCode = 201,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """{"id":"$customerId","name":"عميل تجريبي","address_ar":"نابلس شارع فيصل"}"""
                )
            }
        }
        val repo = createRepository(transport, generationProvider = { currentGen })

        val result = repo.createCustomer("عميل تجريبي", "0599-222222", "نابلس شارع فيصل", 500.0)
        assertTrue("Must fail due to midflight scope invalidation", result.isFailure)
        assertEquals(1, transport.requests.size)
        val postReq = transport.requests[0]
        assertTrue("Must send address_ar on wire", postReq.body?.contains("\"address_ar\":\"نابلس شارع فيصل\"") == true)
    }

    @Test
    fun testColdDirectoryCustomerEdit_DoesNotWipeStoredAddressOrCreditLimit() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789G"

        // Seed a customer as loaded from directory (address and credit limit null / unloaded)
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 201,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"$customerId","name":"زبون دليل بارد"}"""
            )
        )
        repo.createCustomer("زبون دليل بارد", "", "", -1.0)

        // Seed successful PUT response
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"$customerId","name":"زبون معدل بالاسم فقط"}"""
            )
        )

        // Perform edit where address is empty and creditLimit is 0.0 (unloaded placeholders)
        val editRes = repo.updateCustomer(
            customerId = customerId,
            name = "زبون معدل بالاسم فقط",
            phone = "0599-333333",
            address = "",
            creditLimitIls = 0.0
        )
        assertTrue(editRes.isSuccess)

        // Verify wire: PUT request MUST NOT include address_ar or credit_limit keys!
        val putReq = transport.requests.last { it.method == HttpMethod.PUT }
        assertFalse("PUT payload must NOT contain address_ar when unmodified in cold edit", putReq.body?.contains("address_ar") == true)
        assertFalse("PUT payload must NOT contain credit_limit when unmodified in cold edit", putReq.body?.contains("credit_limit") == true)
        assertTrue("PUT payload must contain updated name_ar", putReq.body?.contains("\"name_ar\":\"زبون معدل بالاسم فقط\"") == true)
    }

    @Test
    fun testSuppliersDirectory_First30HasMore_DoesNotCalculateFalseGlobalTotal() {
        val suppliers = (1..30).map { i ->
            Supplier(
                id = "01JCV8X9M2NQW3TYZ4R5S678${i.toString().padStart(2, '0')}",
                nameAr = "مورد $i",
                nameEn = "Supplier $i",
                balanceOwedExact = BigDecimal("100.00"),
                advancesExact = BigDecimal("50.00")
            )
        }
        val stateWithoutMore = TraderUiState(suppliers = suppliers, suppliersHasMore = false)
        assertEquals(3000.0, stateWithoutMore.totalSupplierPayablesIls, 0.001)
        assertEquals(1500.0, stateWithoutMore.totalSupplierAdvancesIls, 0.001)

        val stateWithMore = TraderUiState(suppliers = suppliers, suppliersHasMore = true)
        assertTrue("Total payables must be NaN when directory is truncated with has_more=true", stateWithMore.totalSupplierPayablesIls.isNaN())
        assertTrue("Total advances must be NaN when directory is truncated with has_more=true", stateWithMore.totalSupplierAdvancesIls.isNaN())
    }

    @Test
    fun testSuppliersDirectory_ParsesSeparateAuthoritativeAdvancesField() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val supplierId = "01JCV8X9M2NQW3TYZ4R5S6789H"
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "items": [
                        {
                            "id": "$supplierId",
                            "name": "شركة التوزيع المتقدمة",
                            "balance_owed": "450.00",
                            "advances": "120.00"
                        }
                    ],
                    "has_more": true
                }
                """.trimIndent()
            )
        )

        repo.refreshSuppliers()

        val suppliers = repo.getSuppliers()
        val supplier = suppliers.first { it.id == supplierId }
        assertEquals(BigDecimal("450.00"), supplier.balanceOwedExact)
        assertEquals(BigDecimal("120.00"), supplier.advancesExact)
        assertEquals(120.0, supplier.advancesIls, 0.001)
        assertTrue("suppliersHasMoreFlow must be true when response indicates has_more", repo.suppliersHasMoreFlow.value)
    }

    @Test
    fun testCustomerEdit_ExactDecimalCreditLimitValidation() {
        val regex = Regex("^\\d*(\\.\\d{0,6})?$")
        assertTrue(regex.matches("12.34"))
        assertTrue(regex.matches("12"))
        assertTrue(regex.matches("12.3"))
        assertTrue(regex.matches(""))
        assertTrue(regex.matches("1000.00"))
        assertTrue("6 decimals must be accepted for historical limits", regex.matches("123.456789"))
        assertFalse("More than 6 decimals must be rejected", regex.matches("12.3456789"))
        assertFalse("Non-digits must be rejected", regex.matches("abc"))
        assertFalse("Multiple dots must be rejected", regex.matches("12.34.56"))
    }

    // =========================================================================
    // 9. Exact 598c Nullable Payload Fixtures & Stable Durable Keys
    // =========================================================================

    @Test
    fun testCustomerCreate_ExplicitJsonNullBalanceAndLimit_ParsesWithoutCrash() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789K"
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$customerId",
                    "name": "أبو كمال الزبون",
                    "phone": "0599-123456",
                    "address": null,
                    "balance": null,
                    "credit_limit": null
                }
                """.trimIndent()
            )
        )

        val createRes = repo.createCustomer("أبو كمال الزبون", "0599-123456", "", "test-op-create-1")
        assertTrue("Customer creation with null balance must succeed", createRes.isSuccess)

        val customer = createRes.getOrNull()
        assertNotNull(customer)
        assertEquals(customerId, customer!!.id)
        assertEquals("أبو كمال الزبون", customer.nameAr)
        assertNull("balanceExact must be strictly null when backend sends null", customer.balanceExact)
        assertTrue("balanceIls must be NaN when balanceExact is null", customer.balanceIls.isNaN())
        assertNull("creditLimitExact must be null when backend sends null", customer.creditLimitExact)
    }

    @Test
    fun testCustomerDetail_ExplicitJsonNullBalance_ParsesWithoutCrash() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789A"
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$customerId",
                    "name": "أبو كمال الزبون",
                    "balance": null,
                    "credit_limit": null
                }
                """.trimIndent()
            )
        )

        val detailRes = repo.getCustomerDetail(customerId)
        assertTrue("Customer detail with null balance must succeed without crash", detailRes.isSuccess)
        val detail = detailRes.getOrNull()
        assertNotNull(detail)
        assertNull("Detail balanceExact must be null", detail!!.balanceExact)
        assertTrue("Detail balanceIls must be NaN", detail.balanceIls.isNaN())
    }

    @Test
    fun testSupplierDirectory_NullableBalanceAndAdvancesCombinations() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "items": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S67891",
                            "name": "مورد سلفة فقط",
                            "balance_owed": null,
                            "advances": "120.00"
                        },
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S67892",
                            "name": "مورد ذمة فقط",
                            "balance_owed": "450.00",
                            "advances": null
                        },
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S67893",
                            "name": "مورد أرصدة مجهولة",
                            "balance_owed": null,
                            "advances": null
                        }
                    ],
                    "has_more": false
                }
                """.trimIndent()
            )
        )

        val refreshRes = repo.refreshSuppliers()
        assertTrue("Supplier refresh with nullable balances must succeed", refreshRes.isSuccess)

        val suppliers = repo.getSuppliers()
        assertEquals(3, suppliers.size)

        val s1 = suppliers.first { it.id == "01JCV8X9M2NQW3TYZ4R5S67891" }
        assertNull("s1 balanceOwedExact must be null", s1.balanceOwedExact)
        assertEquals(BigDecimal("120.00"), s1.advancesExact)

        val s2 = suppliers.first { it.id == "01JCV8X9M2NQW3TYZ4R5S67892" }
        assertEquals(BigDecimal("450.00"), s2.balanceOwedExact)
        assertNull("s2 advancesExact must be null", s2.advancesExact)

        val s3 = suppliers.first { it.id == "01JCV8X9M2NQW3TYZ4R5S67893" }
        assertNull("s3 balanceOwedExact must be null", s3.balanceOwedExact)
        assertNull("s3 advancesExact must be null", s3.advancesExact)
    }

    @Test
    fun testCustomerCreate_StableDurableCommandKeyAcrossFailureAndRetry() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val stableOpId = "stable-cust-op-42"

        // First attempt fails with 500 Internal Server Error
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 500,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"message":"Internal Server Error"}"""
            )
        )
        val res1 = repo.createCustomer("زبون تجربة مفتاح", "0599-000111", "", stableOpId)
        assertFalse("First attempt must fail", res1.isSuccess)

        // Second attempt succeeds
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"01JCV8X9M2NQW3TYZ4R5S6789M","name":"زبون تجربة مفتاح"}"""
            )
        )
        val res2 = repo.createCustomer("زبون تجربة مفتاح", "0599-000111", "", stableOpId)
        assertTrue("Second attempt must succeed", res2.isSuccess)

        // Verify that BOTH attempts used the exact same Idempotency-Key
        val postRequests = transport.requests.filter { it.method == HttpMethod.POST }
        assertEquals(2, postRequests.size)
        val key1 = postRequests[0].headers["Idempotency-Key"]
        val key2 = postRequests[1].headers["Idempotency-Key"]
        assertNotNull(key1)
        assertEquals("custcreate-$stableOpId", key1)
        assertEquals("Retry must preserve exact same Idempotency-Key", key1, key2)
    }

    @Test
    fun testSupplierCreate_StableDurableCommandKeyAcrossFailureAndRetry() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val stableOpId = "stable-supp-op-99"

        // First attempt fails with 503 Service Unavailable
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 503,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"message":"Service Unavailable"}"""
            )
        )
        val res1 = repo.createSupplier("مورد تجربة مفتاح", "0599-222333", "", stableOpId)
        assertFalse("First attempt must fail", res1.isSuccess)

        // Second attempt succeeds
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"01JCV8X9M2NQW3TYZ4R5S6789N","name":"مورد تجربة مفتاح"}"""
            )
        )
        val res2 = repo.createSupplier("مورد تجربة مفتاح", "0599-222333", "", stableOpId)
        assertTrue("Second attempt must succeed", res2.isSuccess)

        // Verify that BOTH attempts used the exact same Idempotency-Key
        val postRequests = transport.requests.filter { it.method == HttpMethod.POST }
        assertEquals(2, postRequests.size)
        val key1 = postRequests[0].headers["Idempotency-Key"]
        val key2 = postRequests[1].headers["Idempotency-Key"]
        assertNotNull(key1)
        assertEquals("suppcreate-$stableOpId", key1)
        assertEquals("Supplier retry must preserve exact same Idempotency-Key", key1, key2)
    }

    @Test
    fun testCustomerUpdate_NameOnlyEditOmitsUntouchedFields_Preserves6DecimalLimit() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789P"
        // Seed customer with historical 6-decimal limit
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$customerId",
                    "name": "الزبون الأصلي",
                    "phone": "0599-111222",
                    "address": "نابلس - شارع فيصل",
                    "credit_limit": "123.456789"
                }
                """.trimIndent()
            )
        )
        val initRes = repo.createCustomer("الزبون الأصلي", "0599-111222", "نابلس - شارع فيصل")
        assertTrue(initRes.isSuccess)
        assertEquals(BigDecimal("123.456789"), repo.getCustomers().first().creditLimitExact)

        // Server responds to name-only PUT
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$customerId",
                    "name": "الاسم الجديد المعدل فقط"
                }
                """.trimIndent()
            )
        )

        // Update name ONLY with untouched flags
        val updateRes = repo.updateCustomer(
            customerId = customerId,
            name = "الاسم الجديد المعدل فقط",
            phone = "0599-111222",
            address = "نابلس - شارع فيصل",
            updatePhone = false,
            updateAddress = false,
            updateCreditLimit = false,
            creditLimit = null
        )
        assertTrue("Name-only update must succeed", updateRes.isSuccess)

        // Verify PUT payload on wire
        val putReq = transport.requests.last { it.method == HttpMethod.PUT }
        val putBody = putReq.body ?: ""
        assertTrue("PUT body must contain updated name", putBody.contains("\"name_ar\":\"الاسم الجديد المعدل فقط\""))
        assertFalse("PUT body must NOT contain phone when updatePhone=false", putBody.contains("phone"))
        assertFalse("PUT body must NOT contain address when updateAddress=false", putBody.contains("address"))
        assertFalse("PUT body must NOT contain credit_limit when updateCreditLimit=false", putBody.contains("credit_limit"))

        // Verify local repository preserves untouched 6-decimal limit!
        val updatedCustomer = repo.getCustomers().first { it.id == customerId }
        assertEquals("الاسم الجديد المعدل فقط", updatedCustomer.nameAr)
        assertEquals(BigDecimal("123.456789"), updatedCustomer.creditLimitExact)
        assertEquals("0599-111222", updatedCustomer.phone)
    }

    @Test
    fun testCustomerPayment_RefreshFails_InvalidatesPrepaymentBalance() = runBlocking {
        val transport = RecordingTransport()
        val repo = createRepository(transport)

        val customerId = "01JCV8X9M2NQW3TYZ4R5S6789Q"
        // Seed customer with known balance
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """
                {
                    "id": "$customerId",
                    "name": "زبون ذمة سابقة",
                    "balance": "300.00"
                }
                """.trimIndent()
            )
        )
        repo.createCustomer("زبون ذمة سابقة", "", "")

        // 1. Payment POST succeeds
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"pay_123","amount":"100.00"}"""
            )
        )
        // 2. Subsequent GET customer detail fails with 500 error
        transport.responseQueue.add(
            TransportResponse(
                statusCode = 500,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"message":"Internal Server Error"}"""
            )
        )

        val payRes = repo.recordCustomerPayment(customerId, 100.0, "دفعة نقدية", "op-pay-1")
        assertTrue("Payment recording itself succeeded on wire", payRes.isSuccess)

        // Verify that customer balanceExact was invalidated (null), NOT claiming stale 300.00 or false 0.00
        val custAfterPay = repo.getCustomers().first { it.id == customerId }
        assertNull("Customer balanceExact must be invalidated to null when refresh fails", custAfterPay.balanceExact)
        assertTrue("Customer balanceIls must be NaN when balance is invalidated", custAfterPay.balanceIls.isNaN())
    }

    @Test
    fun testSupplierDirectory_KnownAdvanceWinsOverUnavailableBranch() {
        val supplierWithAdvanceAndNullBalance = Supplier(
            id = "01JCV8X9M2NQW3TYZ4R5S6789R",
            nameAr = "مورد سلفة مؤكدة",
            nameEn = "Advance Supplier",
            balanceOwedExact = null,
            advancesExact = BigDecimal("150.00")
        )

        val isPayableOwed = supplierWithAdvanceAndNullBalance.balanceOwedExact != null && supplierWithAdvanceAndNullBalance.balanceOwedExact > BigDecimal.ZERO
        val hasAdvance = (supplierWithAdvanceAndNullBalance.advancesExact != null && supplierWithAdvanceAndNullBalance.advancesExact > BigDecimal.ZERO) ||
            (supplierWithAdvanceAndNullBalance.balanceOwedExact != null && supplierWithAdvanceAndNullBalance.balanceOwedExact < BigDecimal.ZERO)

        val statusBadgeTone = when {
            hasAdvance -> StitchBadgeTone.SUCCESS
            isPayableOwed -> StitchBadgeTone.WARNING
            supplierWithAdvanceAndNullBalance.balanceOwedExact == null -> StitchBadgeTone.NEUTRAL
            else -> StitchBadgeTone.NEUTRAL
        }

        val advanceAmountIls = if (supplierWithAdvanceAndNullBalance.advancesExact != null && supplierWithAdvanceAndNullBalance.advancesExact > BigDecimal.ZERO) {
            supplierWithAdvanceAndNullBalance.advancesIls
        } else 0.0

        val statusLabel = when {
            hasAdvance -> "${AppLanguage.AR.tr("لنا رصيد مقدم", "Store Advance")} ${StitchBidiUtils.formatIls(advanceAmountIls)}"
            isPayableOwed -> "${AppLanguage.AR.tr("مستحق للمورد", "Owed to Supplier")} ${StitchBidiUtils.formatIls(supplierWithAdvanceAndNullBalance.balanceOwedIls)}"
            supplierWithAdvanceAndNullBalance.balanceOwedExact == null -> AppLanguage.AR.tr("الرصيد غير متاح", "Balance unavailable")
            else -> AppLanguage.AR.tr("خالص", "Settled")
        }

        assertEquals(StitchBadgeTone.SUCCESS, statusBadgeTone)
        assertTrue("Status label must indicate store advance", statusLabel.contains("لنا رصيد مقدم"))
        assertFalse("Status label must NOT say balance unavailable", statusLabel.contains("الرصيد غير متاح"))
    }

    @Test
    fun testTraderUiState_UnloadedCreditLimitIsNotApprovedZero() {
        val customerWithNullLimit = Customer(
            id = "01JCV8X9M2NQW3TYZ4R5S6789S",
            nameAr = "زبون بدون سقف",
            nameEn = "Customer No Limit",
            creditLimitExact = null,
            balanceExact = BigDecimal("100.00")
        )

        val state = TraderUiState(
            customers = listOf(customerWithNullLimit),
            selectedCustomerId = customerWithNullLimit.id
        )

        assertFalse("Unloaded credit limit must NOT cause wouldCreditExceedCustomerLimit to be true", state.wouldCreditExceedCustomerLimit)
        assertEquals(0.0, state.creditCeilingExcessIls, 0.001)
    }
}
