package com.example.data.mobile.merchantread

import com.example.data.mobile.contract.ServerUlid
import com.example.data.mobile.readclient.HttpMethod
import com.example.data.mobile.readclient.ReadClientTransport
import com.example.data.mobile.readclient.TransportRequest
import com.example.data.mobile.readclient.TransportResponse
import java.io.IOException
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure Wire DTO Contract and Serialization Tests for Small Trader Merchant Read Client.
 * UNEXECUTED: Designed as executable reference fixtures without requiring live Android/Gradle daemon execution.
 */
class MerchantReadClientWireTest {

    private val companyId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789A")
    private val productId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789B")
    private val customerId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789C")
    private val invoiceId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789D")
    private val supplierId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789E")
    private val validToken = "sample_test_bearer_token_string_min_20_chars"

    class MockTransport : ReadClientTransport {
        var lastRequest: TransportRequest? = null
        var nextResponse: TransportResponse = TransportResponse(200, emptyMap(), "{}")

        override fun execute(request: TransportRequest): TransportResponse {
            lastRequest = request
            return nextResponse
        }
    }

    // ------------------------------------------------------------------------
    // 1. Inventory / Product Movement History Wire Test
    // ------------------------------------------------------------------------
    @Test
    fun testProductHistory_SuccessAndRequestVerification() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "product_id": "${productId.value}",
                "movements": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789F",
                        "movement_type": "manual_adjustment",
                        "delta": "-5.0000",
                        "date": "2026-11-18",
                        "reason": "Damage during stocking",
                        "resulting_stock": "45.0000"
                    }
                ]
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(
            transport = mock,
            baseOrigin = "https://api.example.com"
        )

        val result = client.getProductHistory(companyId, productId, limit = 50, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val success = (result as MerchantReadResult.Success).value
        assertEquals(productId, success.productId)
        assertEquals(1, success.movements.size)
        val movement = success.movements[0]
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789F"), movement.id)
        assertEquals("manual_adjustment", movement.movementType)
        assertEquals(BigDecimal("-5.0000"), movement.delta)
        assertEquals("2026-11-18", movement.date)
        assertEquals("Damage during stocking", movement.reason)
        assertEquals(BigDecimal("45.0000"), movement.resultingStock)

        // Verify request dispatch
        assertNotNull(mock.lastRequest)
        assertEquals(HttpMethod.GET, mock.lastRequest!!.method)
        assertEquals("/api/mobile/v1/companies/${companyId.value}/products/${productId.value}/movements", mock.lastRequest!!.path)
        assertEquals("50", mock.lastRequest!!.queryParams["limit"])
        assertEquals("Bearer $validToken", mock.lastRequest!!.headers["Authorization"])
    }

    // ------------------------------------------------------------------------
    // 2. Home Dashboard Shell Wire Test (With Null Unauthorized Projections)
    // ------------------------------------------------------------------------
    @Test
    fun testHome_SuccessAndNullableMetricsWhenUnauthorized() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${companyId.value}",
                "today": "2026-11-18",
                "sales_today_count": null,
                "sales_today_total": null,
                "cash_in_drawer": null,
                "low_stock_count": 3,
                "total_receivables": null,
                "total_payables": null,
                "recent_invoices": [
                    {
                        "id": "${invoiceId.value}",
                        "invoice_number": "INV-2026-00042",
                        "customer_name": "Walk-in Cash Customer",
                        "date": "2026-11-18",
                        "total": "125.500",
                        "paid": "125.500",
                        "remaining": "0.000",
                        "currency": "JOD",
                        "payment_status": "PAID_CASH"
                    }
                ],
                "business_info": {
                    "name_ar": "محل التجارة الحديثة",
                    "name_en": "Modern Trade Store",
                    "city_ar": "عمان",
                    "city_en": "Amman",
                    "phone": "0791234567",
                    "tax_number": "123456789",
                    "default_currency": "JOD",
                    "default_currency_minor_units": 3
                }
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getHome(companyId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val home = (result as MerchantReadResult.Success).value
        assertEquals(companyId, home.companyId)
        assertEquals("2026-11-18", home.today)
        assertNull(home.salesTodayCount)
        assertNull(home.salesTodayTotal) // Preserved null, NOT fabricated 0
        assertNull(home.cashInDrawer)   // Preserved null, NOT fabricated 0
        assertEquals(3, home.lowStockCount)
        assertNull(home.totalReceivables)
        assertNull(home.totalPayables)

        assertEquals(1, home.recentInvoices.size)
        val inv = home.recentInvoices[0]
        assertEquals("INV-2026-00042", inv.invoiceNumber) // Preserved exact string
        assertEquals(BigDecimal("125.500"), inv.total)
        assertEquals(BigDecimal("125.500"), inv.paid)
        assertEquals(BigDecimal("0.000"), inv.remaining)
        assertEquals("JOD", inv.currency)
        assertTrue(inv.isPaidInFull)
        assertFalse(inv.isCreditSale)

        assertEquals("JOD", home.businessInfo.defaultCurrency)
        assertEquals(3, home.businessInfo.defaultCurrencyMinorUnits)
    }

    // ------------------------------------------------------------------------
    // 3. Sales Invoices List Wire Test
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoicesList_SuccessAndFilterVerification() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${companyId.value}",
                "count": 1,
                "has_more": false,
                "invoices": [
                    {
                        "id": "${invoiceId.value}",
                        "invoice_number": "INV-1001",
                        "status": "posted",
                        "customer_id": "${customerId.value}",
                        "customer_name": "Acme General Trading",
                        "date": "2026-11-15",
                        "subtotal": "200.00",
                        "discount": "10.00",
                        "tax": "30.40",
                        "total": "220.40",
                        "paid": "100.00",
                        "remaining": "120.40",
                        "currency": "USD",
                        "currency_minor_units": 2,
                        "payment_status": "PARTIAL",
                        "created_at": "2026-11-15T10:30:00Z"
                    }
                ]
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val query = SalesInvoicesQuery(
            limit = 30,
            status = "POSTED",
            search = "Acme",
            customerId = customerId,
            dateFrom = "2026-11-01",
            dateTo = "2026-11-18"
        )
        val result = client.getSalesInvoices(companyId, query = query, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val list = (result as MerchantReadResult.Success).value
        assertEquals(1, list.count)
        assertFalse(list.hasMore)
        val item = list.invoices[0]
        assertEquals("INV-1001", item.invoiceNumber)
        assertEquals(BigDecimal("220.40"), item.total)
        assertEquals(BigDecimal("100.00"), item.paid)
        assertEquals(BigDecimal("120.40"), item.remaining)
        assertEquals(2, item.currencyMinorUnits)
        assertFalse(item.isCreditSale)
        assertFalse(item.isPaidInFull)

        // Verify query parameters
        assertEquals("POSTED", mock.lastRequest!!.queryParams["status"])
        assertEquals("Acme", mock.lastRequest!!.queryParams["search"])
        assertEquals(customerId.value, mock.lastRequest!!.queryParams["customer_id"])
        assertEquals("2026-11-01", mock.lastRequest!!.queryParams["date_from"])
        assertEquals("2026-11-18", mock.lastRequest!!.queryParams["date_to"])
    }

    // ------------------------------------------------------------------------
    // 4. Sales Invoice Detail Wire Test (Unpaid Credit Receipt, No Fake Payment ID)
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoiceDetail_CreditReceiptWithoutPayment() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-CREDIT-99",
                "status": "posted",
                "payment_status": "ON_CREDIT",
                "customer_id": "${customerId.value}",
                "customer_name": "Future Supply Co",
                "date": "2026-11-18",
                "currency": "USD",
                "currency_minor_units": 2,
                "subtotal": "500.00",
                "discount": "0.00",
                "tax": "80.00",
                "total": "580.00",
                "paid": "0.00",
                "remaining": "580.00",
                "lines": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "product_id": "${productId.value}",
                        "product_name": "Premium Industrial Valve",
                        "unit": "Piece",
                        "quantity": "2.0000",
                        "unit_price": "250.00",
                        "line_total": "500.00",
                        "tax_rate": "16.00",
                        "discount": "0.00"
                    }
                ],
                "thermal_receipt": {
                    "store_name_ar": "الشركة النموذجية",
                    "store_name_en": "Model Co",
                    "city_ar": "الرياض",
                    "city_en": "Riyadh",
                    "phone": "0501234567",
                    "tax_number": "300012345600003",
                    "invoice_number": "INV-CREDIT-99",
                    "date_time": "2026-11-18 14:20",
                    "cashier": "Ahmed",
                    "customer_name": "Future Supply Co",
                    "subtotal": "500.00",
                    "discount": "0.00",
                    "tax": "80.00",
                    "grand_total": "580.00",
                    "paid_amount": "0.00",
                    "remaining_balance": "580.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                            "product_id": "${productId.value}",
                            "product_name": "Premium Industrial Valve",
                            "unit": "Piece",
                            "quantity": "2.0000",
                            "unit_price": "250.00",
                            "line_total": "500.00",
                            "tax_rate": "16.00",
                            "discount": "0.00"
                        }
                    ]
                }
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val detail = (result as MerchantReadResult.Success).value
        assertEquals("INV-CREDIT-99", detail.invoiceNumber)
        assertEquals("ON_CREDIT", detail.paymentStatus)
        assertEquals(2, detail.currencyMinorUnits)
        assertTrue(detail.isCreditSale)
        assertFalse(detail.isPaidInFull)

        // Line check: id is valid ServerUlid
        assertEquals(1, detail.lines.size)
        val line = detail.lines[0]
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), line.id)
        assertEquals(productId, line.productId)
        assertEquals(BigDecimal("2.0000"), line.quantity)
        assertEquals(BigDecimal("250.00"), line.unitPrice)

        // Thermal receipt check: isCreditReceipt == true, payment nullable/zero without fake payment ID
        assertNotNull(detail.thermalReceipt)
        val thermal = detail.thermalReceipt!!
        assertTrue(thermal.isCreditReceipt)
        assertFalse(thermal.isPaidInFull)
        assertFalse(thermal.isPartialReceipt)
        assertEquals(BigDecimal("0.00"), thermal.paidAmount)
    }

    // ------------------------------------------------------------------------
    // 5. Customer Statement Wire Test (Multi-Currency & Aging)
    // ------------------------------------------------------------------------
    @Test
    fun testCustomerStatement_CurrenciesAndAgingBreakdown() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "customer_id": "${customerId.value}",
                "customer_name": "Apex Contracting",
                "from_date": "2026-01-01",
                "to_date": "2026-11-18",
                "currencies": {
                    "USD": {
                        "currency": "USD",
                        "opening_balance": "100.00",
                        "closing_balance": "450.00",
                        "total_debits": "500.00",
                        "total_credits": "150.00",
                        "entries": [
                            {
                                "date": "2026-11-10",
                                "type": "invoice",
                                "number": "INV-201",
                                "reference": null,
                                "description": "فاتورة مبيعات #INV-201",
                                "debit": "500.00",
                                "credit": "0.00",
                                "balance": "600.00"
                            },
                            {
                                "date": "2026-11-12",
                                "type": "payment",
                                "number": "PAY-101",
                                "reference": "TRX-7788",
                                "description": "سند قبض #PAY-101",
                                "debit": "0.00",
                                "credit": "150.00",
                                "balance": "450.00"
                            }
                        ],
                        "aging": {
                            "current": "0.00",
                            "days_1_30": "450.00",
                            "days_31_60": "0.00",
                            "days_61_90": "0.00",
                            "days_90_plus": "0.00",
                            "total": "450.00"
                        }
                    }
                }
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getCustomerStatement(companyId, customerId, fromDate = "2026-01-01", toDate = "2026-11-18", token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val statement = (result as MerchantReadResult.Success).value
        assertEquals(customerId, statement.customerId)
        assertEquals("2026-01-01", statement.fromDate)
        assertEquals("2026-11-18", statement.toDate)
        assertTrue(statement.currencies.containsKey("USD"))
        val usd = statement.currencies["USD"]!!
        assertEquals(BigDecimal("100.00"), usd.openingBalance)
        assertEquals(BigDecimal("450.00"), usd.closingBalance)
        assertEquals(2, usd.entries.size)
        assertEquals(BigDecimal("450.00"), usd.aging.days1To30)
    }

    // ------------------------------------------------------------------------
    // 6. Suppliers List & Detail Wire Test
    // ------------------------------------------------------------------------
    @Test
    fun testSuppliersListAndDetail_BalancesAndAdvances() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${companyId.value}",
                "count": 1,
                "has_more": false,
                "suppliers": [
                    {
                        "id": "${supplierId.value}",
                        "code": "SUP-01",
                        "name": "Global Parts Ltd",
                        "name_ar": "قطع الغيار العالمية",
                        "name_en": "Global Parts Ltd",
                        "phone": "0559988776",
                        "currency": "BHD",
                        "currency_minor_units": 3,
                        "balance_owed": "350.750",
                        "advances": null
                    }
                ]
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getSuppliers(companyId, limit = 30, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val list = (result as MerchantReadResult.Success).value
        val supplier = list.suppliers[0]
        assertEquals(supplierId, supplier.id)
        assertEquals("BHD", supplier.currency)
        assertEquals(3, supplier.currencyMinorUnits)
        assertEquals(BigDecimal("350.750"), supplier.balanceOwed)
        assertNull(supplier.advances)
    }

    // ------------------------------------------------------------------------
    // 7. Shift Summary Report Wire Test
    // ------------------------------------------------------------------------
    @Test
    fun testShiftSummaryReport_OpeningFloatAndExpectedCash() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${companyId.value}",
                "shift_date": "2026-11-18",
                "currency": "KWD",
                "currency_minor_units": 3,
                "opening_float": "150.000",
                "cash_sales": "420.500",
                "cash_collections": "80.000",
                "cash_disbursements": "50.000",
                "operating_expenses": "25.000",
                "expected_cash": "575.500",
                "sales_count": 12
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getShiftSummary(companyId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val report = (result as MerchantReadResult.Success).value
        assertEquals("KWD", report.currency)
        assertEquals(3, report.currencyMinorUnits)
        assertEquals(BigDecimal("150.000"), report.openingFloat)
        assertEquals(BigDecimal("420.500"), report.cashSales)
        assertEquals(BigDecimal("80.000"), report.cashCollections)
        assertEquals(BigDecimal("50.000"), report.cashDisbursements)
        assertEquals(BigDecimal("25.000"), report.operatingExpenses)
        assertEquals(BigDecimal("575.500"), report.expectedCash)
        assertEquals(12, report.salesCount)
    }

    // ------------------------------------------------------------------------
    // 8. Treasury Report Wire Test
    // ------------------------------------------------------------------------
    @Test
    fun testTreasuryReport_CashAndBankLedgerBalances() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${companyId.value}",
                "cash_accounts": [
                    {
                        "public_id": "01JCV8X9M2NQW3TYZ4R5S6789G",
                        "name": "Main Cash Drawer",
                        "account_type": "cash",
                        "currency_code": "USD",
                        "base_currency_code": "USD",
                        "balance_base": "1250.000000",
                        "balance_currency": "1250.000000",
                        "currency_balance_available": true,
                        "is_active": true,
                        "bank_name": null
                    }
                ],
                "bank_accounts": [
                    {
                        "public_id": "01JCV8X9M2NQW3TYZ4R5S6789H",
                        "name": "Commercial Bank Operating",
                        "account_type": "bank",
                        "currency_code": "EUR",
                        "base_currency_code": "USD",
                        "balance_base": "5400.000000",
                        "balance_currency": "5000.000000",
                        "currency_balance_available": true,
                        "is_active": true,
                        "bank_name": "Chase Bank"
                    }
                ]
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getTreasury(companyId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val treasury = (result as MerchantReadResult.Success).value
        assertEquals(1, treasury.cashAccounts.size)
        assertEquals(1, treasury.bankAccounts.size)
        val bank = treasury.bankAccounts[0]
        assertEquals("EUR", bank.currencyCode)
        assertEquals("USD", bank.baseCurrencyCode)
        assertEquals(BigDecimal("5000.000000"), bank.balanceCurrency)
        assertEquals("Chase Bank", bank.bankName)
    }

    // ------------------------------------------------------------------------
    // 9. Business Settings Wire Test
    // ------------------------------------------------------------------------
    @Test
    fun testBusinessSettings_CurrenciesMinorUnitsAndWarehouses() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${companyId.value}",
                "name_ar": "مؤسسة الرواد التجارية",
                "name_en": "Al-Rowad Commercial Est",
                "city_ar": "دبي",
                "city_en": "Dubai",
                "phone": "+971501122334",
                "tax_number": "100200300400003",
                "default_currency": "AED",
                "default_currency_minor_units": 2,
                "timezone": "Asia/Dubai",
                "default_tax_percent": "5.000000",
                "default_warehouse": {
                    "id": "01JCV8X9M2NQW3TYZ4R5S6789J",
                    "code": "WH-MAIN",
                    "name": "Main Depot"
                },
                "approved_cash_account": {
                    "id": "01JCV8X9M2NQW3TYZ4R5S6789K",
                    "code": "1010",
                    "name": "POS Cash Drawer 1",
                    "currency": "AED",
                    "currency_minor_units": 2
                },
                "walk_in_customer": {
                    "id": "${customerId.value}",
                    "code": "CUST-WALKIN",
                    "name": "Walk-in Retail Customer"
                },
                "currencies": [
                    {
                        "code": "AED",
                        "name": "UAE Dirham",
                        "symbol": "AED",
                        "minor_units": 2,
                        "is_base": true
                    }
                ],
                "taxes": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789M",
                        "code": "VAT-5",
                        "name": "Standard VAT 5%",
                        "rate": "5.000000",
                        "calculation": "exclusive",
                        "is_default": true
                    }
                ],
                "laravel_sync_status": "جاهز للمزامنة مع نظام الحسابات (Laravel API v1/v2)"
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getBusinessSettings(companyId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val settings = (result as MerchantReadResult.Success).value
        assertEquals("AED", settings.defaultCurrency)
        assertEquals(2, settings.defaultCurrencyMinorUnits)
        assertEquals(BigDecimal("5.000000"), settings.defaultTaxPercent)
        assertNotNull(settings.defaultWarehouse)
        assertEquals("WH-MAIN", settings.defaultWarehouse!!.code)
        assertNotNull(settings.approvedCashAccount)
        assertEquals(2, settings.approvedCashAccount!!.currencyMinorUnits)
    }

    // ------------------------------------------------------------------------
    // 10. Privacy and Redaction Safety Tests
    // ------------------------------------------------------------------------
    @Test
    fun testPrivacyRedaction_SensitiveDataNeverLogged() {
        val invoice = HomeRecentInvoice(
            id = invoiceId,
            invoiceNumber = "INV-001",
            customerName = "Secret Customer Name",
            date = "2026-11-18",
            total = BigDecimal("100.00"),
            paid = BigDecimal("100.00"),
            remaining = BigDecimal("0.00"),
            currency = "USD",
            paymentStatus = "PAID"
        )
        assertFalse(invoice.toString().contains("Secret Customer Name"))
        assertTrue(invoice.toString().contains("[REDACTED]"))

        val error = MerchantReadErrorResponse(
            code = "UNAUTHORIZED",
            message = "Confidential server stack trace or user details"
        )
        assertFalse(error.toString().contains("Confidential server stack trace"))
        assertTrue(error.toString().contains("[REDACTED]"))

        val request = TransportRequest(
            method = HttpMethod.GET,
            path = "/api/mobile/v1/companies/${companyId.value}/home",
            baseOrigin = "https://api.example.com",
            headers = mapOf("Authorization" to "Bearer $validToken")
        )
        assertFalse(request.toString().contains(validToken))
        assertTrue(request.toString().contains("Bearer [REDACTED]"))
    }

    // ------------------------------------------------------------------------
    // 11. Schema Errors: Malformed Decimal Strings & Tenant Mismatches
    // ------------------------------------------------------------------------
    @Test
    fun testSchemaError_MalformedInvalidDecimalString() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "product_id": "${productId.value}",
                "movements": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789F",
                        "movement_type": "manual_adjustment",
                        "delta": "12.34.56.bad_decimal",
                        "date": "2026-11-18",
                        "reason": "Test",
                        "resulting_stock": "45.0000"
                    }
                ]
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getProductHistory(companyId, productId, token = validToken)

        assertTrue(result is MerchantReadResult.SchemaError)
        val schemaError = result as MerchantReadResult.SchemaError
        assertEquals("PARSE_ERROR", schemaError.category)
    }

    @Test
    fun testSchemaError_TenantCompanyIdMismatch() {
        val mock = MockTransport()
        val foreignCompanyId = ServerUlid("01JCV8X9M2NQW3TYZ4R5S6789Z")
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "company_id": "${foreignCompanyId.value}",
                "today": "2026-11-18",
                "sales_today_count": null,
                "sales_today_total": null,
                "cash_in_drawer": null,
                "low_stock_count": null,
                "total_receivables": null,
                "total_payables": null,
                "recent_invoices": [],
                "business_info": {
                    "name_ar": "محل",
                    "name_en": "Store",
                    "city_ar": "دبي",
                    "city_en": "Dubai",
                    "phone": "050",
                    "tax_number": "123",
                    "default_currency": "AED",
                    "default_currency_minor_units": 2
                }
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getHome(companyId, token = validToken)

        assertTrue(result is MerchantReadResult.SchemaError)
    }

    // ------------------------------------------------------------------------
    // 12. HTTP Error Mapping: 401 AuthDenial, 403 Forbidden, 404 NotFound
    // ------------------------------------------------------------------------
    @Test
    fun testErrorStatuses_PreserveCategoriesTruthfully() {
        val mock = MockTransport()
        val client = MerchantReadClient(mock, "https://api.example.com")

        mock.nextResponse = TransportResponse(401, emptyMap(), """{"error":{"code":"UNAUTHENTICATED","message":"Token expired"}}""")
        val res401 = client.getHome(companyId, token = validToken)
        assertTrue(res401 is MerchantReadResult.AuthDenial)
        assertEquals(401, (res401 as MerchantReadResult.AuthDenial).statusCode)
        assertEquals("UNAUTHENTICATED", res401.error.code)

        mock.nextResponse = TransportResponse(403, emptyMap(), """{"error":{"code":"FORBIDDEN","message":"Missing permissions"}}""")
        val res403 = client.getHome(companyId, token = validToken)
        assertTrue(res403 is MerchantReadResult.Forbidden)
        assertEquals("FORBIDDEN", (res403 as MerchantReadResult.Forbidden).error.code)

        mock.nextResponse = TransportResponse(404, emptyMap(), """{"error":{"code":"NOT_FOUND","message":"Invoice not found"}}""")
        val res404 = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue(res404 is MerchantReadResult.NotFound)
        assertEquals("NOT_FOUND", (res404 as MerchantReadResult.NotFound).error.code)

        mock.nextResponse = TransportResponse(500, emptyMap(), """{"error":{"code":"INTERNAL_SERVER_ERROR","message":"Server error"}}""")
        val res500 = client.getHome(companyId, token = validToken)
        assertTrue(res500 is MerchantReadResult.HttpError)
        assertEquals(500, (res500 as MerchantReadResult.HttpError).statusCode)
    }

    // ------------------------------------------------------------------------
    // 13. Network Transport Errors
    // ------------------------------------------------------------------------
    @Test
    fun testNetworkError_TransportExceptionPreserved() {
        val failingTransport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                throw IOException("Connection reset by peer")
            }
        }
        val client = MerchantReadClient(failingTransport, "https://api.example.com")
        val result = client.getHome(companyId, token = validToken)

        assertTrue(result is MerchantReadResult.NetworkError)
        val netErr = result as MerchantReadResult.NetworkError
        assertEquals("NETWORK_ERROR", netErr.category)
    }

    // ------------------------------------------------------------------------
    // 14. Scoped Token Provider and Generation Guards
    // ------------------------------------------------------------------------
    @Test
    fun testTokenProvider_MissingOrStaleTokenYieldsAuthDenial() {
        val mock = MockTransport()
        var providerCallCount = 0
        val tokenProvider = MerchantReadTokenProvider { expectedCompany, generation ->
            providerCallCount++
            if (generation == 5L && expectedCompany == companyId) {
                validToken
            } else {
                null // Stale generation or mismatched company
            }
        }

        val client = MerchantReadClient(mock, "https://api.example.com", tokenProvider = tokenProvider)

        // Call with stale generation (e.g. 4L)
        val staleResult = client.getHome(companyId, retainedGeneration = 4L)
        assertTrue(staleResult is MerchantReadResult.AuthDenial)
        assertEquals("UNAUTHENTICATED", (staleResult as MerchantReadResult.AuthDenial).error.code)

        // Call with active generation (5L)
        mock.nextResponse = TransportResponse(200, emptyMap(), """
        {
            "company_id": "${companyId.value}",
            "today": "2026-11-18",
            "sales_today_count": null,
            "sales_today_total": null,
            "cash_in_drawer": null,
            "low_stock_count": null,
            "total_receivables": null,
            "total_payables": null,
            "recent_invoices": [],
            "business_info": {
                "name_ar": "محل",
                "name_en": "Store",
                "city_ar": "عمان",
                "city_en": "Amman",
                "phone": "079",
                "tax_number": "123",
                "default_currency": "JOD",
                "default_currency_minor_units": 3
            }
        }
        """.trimIndent())

        val validResult = client.getHome(companyId, retainedGeneration = 5L)
        assertTrue(validResult.toString(), validResult is MerchantReadResult.Success)
    }

    // ------------------------------------------------------------------------
    // 15. Token Provider Authoritative & Retained Generation Checks
    // ------------------------------------------------------------------------
    @Test
    fun testTokenProvider_Authoritative_NoExplicitTokenBypass() {
        val mock = MockTransport()
        val tokenProvider = MerchantReadTokenProvider { _, _ -> null }
        val client = MerchantReadClient(mock, "https://api.example.com", tokenProvider = tokenProvider)

        val result = client.getHome(companyId, token = validToken, retainedGeneration = 1L)
        assertTrue("Expected AuthDenial when provider returns null", result is MerchantReadResult.AuthDenial)
        assertEquals(401, (result as MerchantReadResult.AuthDenial).statusCode)
        assertEquals("UNAUTHENTICATED", result.error.code)
    }

    @Test
    fun testRetainedGeneration_WithoutProvider_Rejected() {
        val mock = MockTransport()
        val client = MerchantReadClient(mock, "https://api.example.com", tokenProvider = null)

        val result = client.getHome(companyId, token = validToken, retainedGeneration = 1L)
        assertTrue("Expected AuthDenial when retainedGeneration has no provider", result is MerchantReadResult.AuthDenial)
        assertEquals(401, (result as MerchantReadResult.AuthDenial).statusCode)
        assertEquals("UNAUTHENTICATED", result.error.code)
    }

    @Test
    fun testTokenProvider_StaleGenerationWhileGetDenial_NoResponseAccepted() {
        var currentGen = 10L
        val tokenProvider = MerchantReadTokenProvider { expectedCompany, gen ->
            if (expectedCompany == companyId && gen == currentGen) validToken else null
        }

        val transport = object : ReadClientTransport {
            override fun execute(request: TransportRequest): TransportResponse {
                // Generation invalidated while request is in flight
                currentGen = 11L
                return TransportResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """
                    {
                        "company_id": "${companyId.value}",
                        "today": "2026-11-18",
                        "sales_today_count": null,
                        "sales_today_total": null,
                        "cash_in_drawer": null,
                        "low_stock_count": 0,
                        "total_receivables": null,
                        "total_payables": null,
                        "recent_invoices": [],
                        "business_info": {
                            "name_ar": "محل",
                            "name_en": "Store",
                            "city_ar": "عمان",
                            "city_en": "Amman",
                            "phone": "079",
                            "tax_number": "123",
                            "default_currency": "JOD",
                            "default_currency_minor_units": 3
                        }
                    }
                    """.trimIndent()
                )
            }
        }

        val client = MerchantReadClient(transport, "https://api.example.com", tokenProvider = tokenProvider)
        val result = client.getHome(companyId, retainedGeneration = 10L)

        assertTrue("Expected AuthDenial when generation becomes stale during GET", result is MerchantReadResult.AuthDenial)
        val denial = result as MerchantReadResult.AuthDenial
        assertEquals(401, denial.statusCode)
        assertEquals("UNAUTHENTICATED", denial.error.code)
        assertEquals("Active scoped token generation changed during read", denial.error.message)
    }

    // ------------------------------------------------------------------------
    // 16. Contract Parity: Nullable Historical Fields in Invoice & Thermal Receipt
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoiceDetail_NullableHistoricalFields() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-HIST-01",
                "status": "posted",
                "payment_status": "ON_CREDIT",
                "customer_id": "${customerId.value}",
                "customer_name": null,
                "date": "2026-11-18",
                "currency": "USD",
                "currency_minor_units": 2,
                "subtotal": "100.00",
                "discount": "0.00",
                "tax": "15.00",
                "total": "115.00",
                "paid": "0.00",
                "remaining": "115.00",
                "lines": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "product_id": null,
                        "product_name": "Custom Service Item",
                        "unit": "Hours",
                        "quantity": "1.0000",
                        "unit_price": "100.00",
                        "line_total": "100.00",
                        "tax_rate": "15.00",
                        "discount": "0.00"
                    }
                ],
                "thermal_receipt": {
                    "store_name_ar": null,
                    "store_name_en": null,
                    "city_ar": null,
                    "city_en": null,
                    "phone": null,
                    "tax_number": null,
                    "invoice_number": "INV-HIST-01",
                    "date_time": "2026-11-18 10:00",
                    "cashier": null,
                    "customer_name": null,
                    "subtotal": "100.00",
                    "discount": "0.00",
                    "tax": "15.00",
                    "grand_total": "115.00",
                    "paid_amount": "0.00",
                    "remaining_balance": "115.00",
                    "currency": "USD",
                    "lines": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                            "product_id": null,
                            "product_name": "Custom Service Item",
                            "unit": "Hours",
                            "quantity": "1.0000",
                            "unit_price": "100.00",
                            "line_total": "100.00",
                            "tax_rate": "15.00",
                            "discount": "0.00"
                        }
                    ]
                }
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val detail = (result as MerchantReadResult.Success).value
        assertNull(detail.customerName)
        assertEquals(2, detail.currencyMinorUnits)
        assertEquals(1, detail.lines.size)
        assertNull(detail.lines[0].productId)
        assertEquals(ServerUlid.parse("01JCV8X9M2NQW3TYZ4R5S6789A"), detail.lines[0].id)

        assertNotNull(detail.thermalReceipt)
        val thermal = detail.thermalReceipt!!
        assertNull(thermal.cashier)
        assertNull(thermal.customerName)
        assertNull(thermal.storeNameAr)
        assertNull(thermal.storeNameEn)
        assertNull(thermal.cityAr)
        assertNull(thermal.cityEn)
        assertNull(thermal.phone)
        assertNull(thermal.taxNumber)
        assertFalse(thermal.isPaidInFull)
    }

    // ------------------------------------------------------------------------
    // 17. Contract Parity: 0 and 3 Minor Units Support
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoice_ZeroAndThreeMinorUnits() {
        val mock = MockTransport()
        val client = MerchantReadClient(mock, "https://api.example.com")

        // 0-minor-unit currency (JPY)
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-JPY-01",
                "status": "posted",
                "payment_status": "PAID_CASH",
                "customer_id": "${customerId.value}",
                "customer_name": "Tokyo Trade",
                "date": "2026-11-18",
                "currency": "JPY",
                "currency_minor_units": 0,
                "subtotal": "5000",
                "discount": "0",
                "tax": "500",
                "total": "5500",
                "paid": "5500",
                "remaining": "0",
                "lines": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "product_id": "${productId.value}",
                        "product_name": "Widget JPY",
                        "unit": "Piece",
                        "quantity": "5",
                        "unit_price": "1000",
                        "line_total": "5000",
                        "tax_rate": "10.00",
                        "discount": "0"
                    }
                ],
                "thermal_receipt": {
                    "store_name_ar": "طوكيو",
                    "store_name_en": "Tokyo",
                    "city_ar": "طوكيو",
                    "city_en": "Tokyo",
                    "phone": "0312345678",
                    "tax_number": "999888777",
                    "invoice_number": "INV-JPY-01",
                    "date_time": "2026-11-18 12:00",
                    "cashier": "Kenji",
                    "customer_name": "Tokyo Trade",
                    "subtotal": "5000",
                    "discount": "0",
                    "tax": "500",
                    "grand_total": "5500",
                    "paid_amount": "5500",
                    "remaining_balance": "0",
                    "currency": "JPY",
                    "lines": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                            "product_id": "${productId.value}",
                            "product_name": "Widget JPY",
                            "unit": "Piece",
                            "quantity": "5",
                            "unit_price": "1000",
                            "line_total": "5000",
                            "tax_rate": "10.00",
                            "discount": "0"
                        }
                    ]
                }
            }
            """.trimIndent()
        )

        val jpyResult = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue(jpyResult.toString(), jpyResult is MerchantReadResult.Success)
        val jpyDetail = (jpyResult as MerchantReadResult.Success).value
        assertEquals("JPY", jpyDetail.currency)
        assertEquals(0, jpyDetail.currencyMinorUnits)
        assertEquals(BigDecimal("5500"), jpyDetail.total)
        assertEquals(BigDecimal("0"), jpyDetail.remaining)
        assertTrue(jpyDetail.isFullCashSale)
        assertTrue(jpyDetail.isPaidInFull)

        // 3-minor-unit currency (JOD)
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-JOD-01",
                "status": "posted",
                "payment_status": "PAID_CASH",
                "customer_id": "${customerId.value}",
                "customer_name": "Amman Supply",
                "date": "2026-11-18",
                "currency": "JOD",
                "currency_minor_units": 3,
                "subtotal": "120.250",
                "discount": "0.000",
                "tax": "19.240",
                "total": "139.490",
                "paid": "139.490",
                "remaining": "0.000",
                "lines": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "product_id": "${productId.value}",
                        "product_name": "Widget JOD",
                        "unit": "Piece",
                        "quantity": "1.000",
                        "unit_price": "120.250",
                        "line_total": "120.250",
                        "tax_rate": "16.00",
                        "discount": "0.000"
                    }
                ],
                "thermal_receipt": {
                    "store_name_ar": "عمان",
                    "store_name_en": "Amman",
                    "city_ar": "عمان",
                    "city_en": "Amman",
                    "phone": "0791112233",
                    "tax_number": "111222333",
                    "invoice_number": "INV-JOD-01",
                    "date_time": "2026-11-18 12:00",
                    "cashier": "Sami",
                    "customer_name": "Amman Supply",
                    "subtotal": "120.250",
                    "discount": "0.000",
                    "tax": "19.240",
                    "grand_total": "139.490",
                    "paid_amount": "139.490",
                    "remaining_balance": "0.000",
                    "currency": "JOD",
                    "lines": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                            "product_id": "${productId.value}",
                            "product_name": "Widget JOD",
                            "unit": "Piece",
                            "quantity": "1.000",
                            "unit_price": "120.250",
                            "line_total": "120.250",
                            "tax_rate": "16.00",
                            "discount": "0.000"
                        }
                    ]
                }
            }
            """.trimIndent()
        )

        val jodResult = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue(jodResult.toString(), jodResult is MerchantReadResult.Success)
        val jodDetail = (jodResult as MerchantReadResult.Success).value
        assertEquals("JOD", jodDetail.currency)
        assertEquals(3, jodDetail.currencyMinorUnits)
        assertEquals(BigDecimal("139.490"), jodDetail.total)
        assertEquals(BigDecimal("0.000"), jodDetail.remaining)
        assertTrue(jodDetail.isFullCashSale)
        assertTrue(jodDetail.isPaidInFull)
    }

    // ------------------------------------------------------------------------
    // 18. Contract Parity: Missing or Malformed Currency Minor Units Fails
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoice_MissingOrMalformedCmu_Fails() {
        val mock = MockTransport()
        val client = MerchantReadClient(mock, "https://api.example.com")

        // 1. Missing currency_minor_units in detail response
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-FAIL-01",
                "status": "posted",
                "payment_status": "PAID_CASH",
                "customer_id": "${customerId.value}",
                "customer_name": "Test Customer",
                "date": "2026-11-18",
                "currency": "USD",
                "subtotal": "100.00",
                "discount": "0.00",
                "tax": "15.00",
                "total": "115.00",
                "paid": "115.00",
                "remaining": "0.00",
                "lines": [],
                "thermal_receipt": null
            }
            """.trimIndent()
        )
        val missingResult = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue("Expected SchemaError on missing currency_minor_units", missingResult is MerchantReadResult.SchemaError)

        // 2. Out of range currency_minor_units (7)
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-FAIL-02",
                "status": "posted",
                "payment_status": "PAID_CASH",
                "customer_id": "${customerId.value}",
                "customer_name": "Test Customer",
                "date": "2026-11-18",
                "currency": "USD",
                "currency_minor_units": 7,
                "subtotal": "100.00",
                "discount": "0.00",
                "tax": "15.00",
                "total": "115.00",
                "paid": "115.00",
                "remaining": "0.00",
                "lines": [],
                "thermal_receipt": null
            }
            """.trimIndent()
        )
        val outOfRangeResult = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue("Expected SchemaError on cmu > 6", outOfRangeResult is MerchantReadResult.SchemaError)

        // 3. String value instead of int
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-FAIL-03",
                "status": "posted",
                "payment_status": "PAID_CASH",
                "customer_id": "${customerId.value}",
                "customer_name": "Test Customer",
                "date": "2026-11-18",
                "currency": "USD",
                "currency_minor_units": "two",
                "subtotal": "100.00",
                "discount": "0.00",
                "tax": "15.00",
                "total": "115.00",
                "paid": "115.00",
                "remaining": "0.00",
                "lines": [],
                "thermal_receipt": null
            }
            """.trimIndent()
        )
        val stringCmuResult = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue("Expected SchemaError on string cmu", stringCmuResult is MerchantReadResult.SchemaError)
    }

    // ------------------------------------------------------------------------
    // 19. Contract Parity: PAID Non-Cash Invoice is NOT Guessed as Full Cash
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoice_PaidNonCash_IsNotFullCashSale() {
        val mock = MockTransport()
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-BANK-100",
                "status": "posted",
                "payment_status": "PAID",
                "customer_id": "${customerId.value}",
                "customer_name": "Bank Transfer Client",
                "date": "2026-11-18",
                "currency": "ILS",
                "currency_minor_units": 2,
                "subtotal": "1000.00",
                "discount": "0.00",
                "tax": "170.00",
                "total": "1170.00",
                "paid": "1170.00",
                "remaining": "0.00",
                "lines": [
                    {
                        "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                        "product_id": "${productId.value}",
                        "product_name": "Commercial Hardware",
                        "unit": "Set",
                        "quantity": "1.0000",
                        "unit_price": "1000.00",
                        "line_total": "1000.00",
                        "tax_rate": "17.00",
                        "discount": "0.00"
                    }
                ],
                "thermal_receipt": {
                    "store_name_ar": "المركز التجاري",
                    "store_name_en": "Commercial Center",
                    "city_ar": "القدس",
                    "city_en": "Jerusalem",
                    "phone": "021234567",
                    "tax_number": "555444333",
                    "invoice_number": "INV-BANK-100",
                    "date_time": "2026-11-18 15:30",
                    "cashier": "Yousef",
                    "customer_name": "Bank Transfer Client",
                    "subtotal": "1000.00",
                    "discount": "0.00",
                    "tax": "170.00",
                    "grand_total": "1170.00",
                    "paid_amount": "1170.00",
                    "remaining_balance": "0.00",
                    "currency": "ILS",
                    "lines": [
                        {
                            "id": "01JCV8X9M2NQW3TYZ4R5S6789A",
                            "product_id": "${productId.value}",
                            "product_name": "Commercial Hardware",
                            "unit": "Set",
                            "quantity": "1.0000",
                            "unit_price": "1000.00",
                            "line_total": "1000.00",
                            "tax_rate": "17.00",
                            "discount": "0.00"
                        }
                    ]
                }
            }
            """.trimIndent()
        )

        val client = MerchantReadClient(mock, "https://api.example.com")
        val result = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)

        assertTrue(result.toString(), result is MerchantReadResult.Success)
        val detail = (result as MerchantReadResult.Success).value
        assertEquals("PAID", detail.paymentStatus)
        assertTrue("Remaining is 0 so invoice is paid in full", detail.isPaidInFull)
        assertFalse("Invoice paid via non-cash bank transfer must NOT be full cash sale", detail.isFullCashSale)
        assertFalse("Invoice is paid, not credit", detail.isCreditSale)

        val thermal = detail.thermalReceipt!!
        assertTrue("Thermal receipt with zero remaining balance is paid in full", thermal.isPaidInFull)
        assertTrue(thermal.isFullyPaidReceipt)
        assertFalse(thermal.isCreditReceipt)
        assertFalse(thermal.isPartialReceipt)
    }

    // ------------------------------------------------------------------------
    // 20. Contract Parity: Invalid ULID Line IDs Rejected
    // ------------------------------------------------------------------------
    @Test
    fun testSalesInvoiceLine_InvalidULID_Fails() {
        val mock = MockTransport()
        val client = MerchantReadClient(mock, "https://api.example.com")

        // Non-ULID string "1042" as line ID
        mock.nextResponse = TransportResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = """
            {
                "id": "${invoiceId.value}",
                "company_id": "${companyId.value}",
                "invoice_number": "INV-BADLINE-01",
                "status": "posted",
                "payment_status": "PAID_CASH",
                "customer_id": "${customerId.value}",
                "customer_name": "Test",
                "date": "2026-11-18",
                "currency": "USD",
                "currency_minor_units": 2,
                "subtotal": "100.00",
                "discount": "0.00",
                "tax": "15.00",
                "total": "115.00",
                "paid": "115.00",
                "remaining": "0.00",
                "lines": [
                    {
                        "id": "1042",
                        "product_id": "${productId.value}",
                        "product_name": "Item",
                        "unit": "Piece",
                        "quantity": "1.0000",
                        "unit_price": "100.00",
                        "line_total": "100.00",
                        "tax_rate": "15.00",
                        "discount": "0.00"
                    }
                ],
                "thermal_receipt": null
            }
            """.trimIndent()
        )

        val result = client.getSalesInvoiceDetail(companyId, invoiceId, token = validToken)
        assertTrue("Expected SchemaError when line id is not a valid ULID", result is MerchantReadResult.SchemaError)
    }
}
