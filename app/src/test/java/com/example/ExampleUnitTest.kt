package com.example

import com.example.data.OfflineDemoTraderRepository
import com.example.data.SampleCatalog
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.DiscountPreset
import com.example.model.ExpenseCategory
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.viewmodel.TraderViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class ExampleUnitTest {

    // =========================================================================
    // 1. Idempotency & Replay Handling at Repository and UI Boundary
    // =========================================================================

    @Test
    fun repositoryRecordSale_isIdempotent_doesNotDoubleDeductStockOrDuplicateInvoices() {
        val repo = OfflineDemoTraderRepository()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" }
        val initialStock = oliveOil.stockCount
        val initialInvoicesCount = repo.getInvoices().size

        val invoice = InvoiceRecord(
            id = "inv_idempotent_test",
            invoiceNumber = 7701,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(BasketItem(product = oliveOil, quantity = 2)),
            subtotalIls = 76.00,
            discountIls = 0.0,
            netTotalIls = 76.00,
            cashReceivedIls = 100.00,
            changeReturnedIls = 24.00,
            customerId = null,
            customerNote = "زبون نقدي",
            cashierNameAr = "أبو كريم",
            cashierNameEn = "Abu Kareem"
        )

        // First execution: deducts 2 from stock (e.g. 20 -> 18)
        val firstResult = repo.recordSale(invoice)
        assertTrue(firstResult.isSuccess)
        val stockAfterFirst = repo.findProductById(oliveOil.id)!!.stockCount
        assertEquals(initialStock - 2, stockAfterFirst)
        assertEquals(initialInvoicesCount + 1, repo.getInvoices().size)

        // Second execution (replayed network call / retry / duplicate submission of same invoice):
        val replayResult = repo.recordSale(invoice)
        assertTrue("Replay of existing invoice must return success idempotently", replayResult.isSuccess)
        assertEquals(invoice.id, replayResult.getOrThrow().id)

        // Critical accounting assertion: stock must NOT be deducted again (must remain 18, NOT 16!)
        val stockAfterReplay = repo.findProductById(oliveOil.id)!!.stockCount
        assertEquals("Stock must remain exactly unchanged on idempotent replay", stockAfterFirst, stockAfterReplay)

        // Invoice list must NOT contain duplicate entries
        assertEquals("Invoice list must not duplicate replayed invoice", initialInvoicesCount + 1, repo.getInvoices().size)
    }

    @Test
    fun viewModelReplay_doubleClickOnSaleCompletion_isIdempotent() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        val initialStock = oliveOil.stockCount

        viewModel.addProductToBasket(oliveOil, 2)
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)
        viewModel.setCashQuickDenomination(100)

        // First click
        viewModel.completeCashSale(asCreditDebt = false)
        val invoicesCount = viewModel.uiState.value.invoices.size
        val stockAfterSale = viewModel.uiState.value.products.first { it.id == oliveOil.id }.stockCount
        assertEquals(initialStock - 2, stockAfterSale)
        assertEquals(0, viewModel.uiState.value.basketItemCount)

        // Immediate second click (duplicate action on empty basket)
        viewModel.completeCashSale(asCreditDebt = false)
        assertEquals(invoicesCount, viewModel.uiState.value.invoices.size)
        assertEquals(stockAfterSale, viewModel.uiState.value.products.first { it.id == oliveOil.id }.stockCount)
    }

    // =========================================================================
    // 2. Stale Operations & Stock Revalidation at Final Posting
    // =========================================================================

    @Test
    fun staleOperation_revalidatesStockAtFinalPosting_rejectsWithoutSilentClamping() {
        val repo = OfflineDemoTraderRepository()
        val manager = repo.getCurrentSession()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" }

        // Initial stock is 20. Stale basket was prepared with 5 items
        val basketItems = listOf(BasketItem(product = oliveOil, quantity = 5))
        val invoice = InvoiceRecord(
            id = "inv_stale_test",
            invoiceNumber = 7702,
            timestamp = WesternFormatters.currentTimestamp(),
            items = basketItems,
            subtotalIls = 190.00,
            discountIls = 0.0,
            netTotalIls = 190.00,
            cashReceivedIls = 200.00,
            changeReturnedIls = 10.00,
            customerId = null,
            customerNote = "زبون نقدي",
            cashierNameAr = "مدير",
            cashierNameEn = "Manager"
        )

        // Meanwhile, another cashier or inventory adjustment reduces stock to 2 items
        repo.adjustProductStock(oliveOil.id, -(oliveOil.stockCount - 2), "تعديل خارجي", manager)
        assertEquals(2, repo.findProductById(oliveOil.id)!!.stockCount)

        // Now final sale posting is attempted with the stale 5 items
        val saleResult = repo.recordSale(invoice)

        // Must be rejected with failure, NOT silently clamped to 0 or negative
        assertTrue("Sale must fail when current stock is insufficient", saleResult.isFailure)
        assertTrue(saleResult.exceptionOrNull() is IllegalStateException)
        assertTrue(saleResult.exceptionOrNull()!!.message!!.contains("Insufficient stock"))

        // Catalog stock must remain 2 (never clamped to 0 or negative)
        assertEquals(2, repo.findProductById(oliveOil.id)!!.stockCount)
    }

    // =========================================================================
    // 3. Purchase Bills: Reject Invalid Payments & Party IDs, Maintain Consistency
    // =========================================================================

    @Test
    fun purchaseBill_rejectsNegativePaidNow_andInvalidSupplier() {
        val repo = OfflineDemoTraderRepository()
        val manager = repo.getCurrentSession()
        val supplier = repo.getSuppliers().first()
        val initialBillsCount = repo.getPurchaseBills().size
        val initialOwed = supplier.balanceOwedIls

        // Case 1: Total 100, Paid-now -50 (negative paid now) -> Must reject!
        val negativePaidResult = repo.recordPurchaseBill(
            supplierId = supplier.id,
            itemsSummary = "فاتورة بقيمة سالبة",
            totalIls = 100.0,
            paidNowIls = -50.0,
            operator = manager
        )
        assertTrue("Negative paid-now must be rejected", negativePaidResult.isFailure)
        assertTrue(negativePaidResult.exceptionOrNull() is IllegalArgumentException)

        // Case 2: Paid-now exceeding total (e.g. Total 100, Paid-now 150) -> Must reject on bill
        val overpaidBillResult = repo.recordPurchaseBill(
            supplierId = supplier.id,
            itemsSummary = "فاتورة دفعة زائدة",
            totalIls = 100.0,
            paidNowIls = 150.0,
            operator = manager
        )
        assertTrue("Paid-now exceeding total must be rejected on bill creation", overpaidBillResult.isFailure)

        // Case 3: Invalid supplier party ID -> Must reject!
        val invalidSupplierResult = repo.recordPurchaseBill(
            supplierId = "non_existent_supplier_id",
            itemsSummary = "فاتورة لمورد غير موجود",
            totalIls = 100.0,
            paidNowIls = 50.0,
            operator = manager
        )
        assertTrue("Non-existent supplier ID must be rejected", invalidSupplierResult.isFailure)

        // Supplier balance and bills list must remain strictly consistent and unchanged
        assertEquals(initialBillsCount, repo.getPurchaseBills().size)
        val currentSup = repo.getSuppliers().first { it.id == supplier.id }
        assertEquals(initialOwed, currentSup.balanceOwedIls, 0.001)
    }

    // =========================================================================
    // 4. WesternFormatters: Amount Entry, Sanitizer & Large-Value Scenarios
    // =========================================================================

    @Test
    fun westernFormatters_sanitizesFormattedLargeNumbersAndArabicSeparators() {
        // Standard US formatted amount from formatNumber
        val formatted = WesternFormatters.formatNumber(1234.50)
        assertEquals("1,234.50", formatted)

        // Sanitizer must strip thousands comma and preserve decimal period
        val sanitized = WesternFormatters.sanitizeWesternDigits(formatted)
        assertEquals("1234.50", sanitized)
        assertEquals(1234.50, sanitized.toDoubleOrNull() ?: 0.0, 0.001)

        // Millions with multiple thousands commas
        val millionFormatted = WesternFormatters.formatNumber(1000000.00)
        assertEquals("1,000,000.00", millionFormatted)
        val millionSanitized = WesternFormatters.sanitizeWesternDigits(millionFormatted)
        assertEquals("1000000.00", millionSanitized)
        assertEquals(1000000.00, millionSanitized.toDoubleOrNull() ?: 0.0, 0.001)

        // Arabic-Indic digits with Arabic decimal separator (U+066B) and thousands separator (U+066C)
        val arabicInput = "١٬٢٣٤٫٥٠"
        val arabicSanitized = WesternFormatters.sanitizeWesternDigits(arabicInput)
        assertEquals("1234.50", arabicSanitized)
        assertEquals(1234.50, arabicSanitized.toDoubleOrNull() ?: 0.0, 0.001)

        // User typed comma as decimal on custom amount (e.g. "12,50")
        assertEquals("12.50", WesternFormatters.sanitizeWesternDigits("12,50"))
    }

    // =========================================================================
    // 5. Exact Money Operations: No Unrounded Floats in Discounts or Totals
    // =========================================================================

    @Test
    fun discountRounding_exactMoneyValues_5PercentOn6Point50Yields33AgorotDiscountAnd6Point17Net() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Product with price 6.50 ILS (e.g. Soap or small item)
        val testProd = Product(
            id = "p_test_650",
            nameAr = "صنف بسعر 6.50",
            nameEn = "Item 6.50",
            category = ProductCategory.PANTRY,
            barcode = "625000000650",
            priceIls = 6.50,
            costIls = 4.00,
            unitAr = "قطعة",
            unitEn = "Item",
            stockCount = 10,
            accentHex = 0xFF000000
        )
        repo.addCustomProduct(testProd)
        viewModel.addProductToBasket(testProd, 1)

        assertEquals(6.50, viewModel.uiState.value.basketSubtotalIls, 0.001)

        // Apply 5% discount
        viewModel.selectDiscountPreset(DiscountPreset.PERCENT_5)

        // 6.50 * 0.05 = 0.325 -> explicitly rounded HALF_UP to 0.33 ILS (NOT 0.325!)
        assertEquals(0.33, viewModel.uiState.value.calculatedDiscountIls, 0.0001)

        // Net total: 6.50 - 0.33 = 6.17 ILS (NOT 6.175!)
        assertEquals(6.17, viewModel.uiState.value.basketNetTotalIls, 0.0001)

        // Apply 10% discount: 6.50 * 0.10 = 0.65 ILS, net 5.85 ILS
        viewModel.selectDiscountPreset(DiscountPreset.PERCENT_10)
        assertEquals(0.65, viewModel.uiState.value.calculatedDiscountIls, 0.0001)
        assertEquals(5.85, viewModel.uiState.value.basketNetTotalIls, 0.0001)
    }

    // =========================================================================
    // 6. Custom Quick Items: Permissible Non-Stock Lines with Zero Invented Cost
    // =========================================================================

    @Test
    fun customQuickItem_modelsAsPermissibleNonStockLine_withZeroInventedCostAndZeroStock() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Add custom quick item priced 25.00 ILS
        viewModel.addCustomQuickItem("مشروب ساخن مخصص", "25.00")

        val customItem = viewModel.uiState.value.basket.firstOrNull { it.product.priceIls == 25.0 }
        assertNotNull(customItem)
        val prod = customItem!!.product

        // Must NOT have invented 75% cost or 99 fake stock
        assertFalse("Custom quick line must not be tracked in physical inventory", prod.isStockTracked)
        assertEquals("Invented cost must be 0.0", 0.0, prod.costIls, 0.001)
        assertEquals("Stock count for non-stock item must be 0", 0, prod.stockCount)

        // Sale of non-stock line succeeds and does not fail on stock checks
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)
        viewModel.setCashQuickDenomination(50)
        viewModel.completeCashSale(asCreditDebt = false)

        val receipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(25.00, receipt.netTotalIls, 0.001)
        assertEquals(25.00, receipt.changeReturnedIls, 0.001)
    }

    // =========================================================================
    // 7. Customer Credit Limits & Authoritative Debt Allocation
    // =========================================================================

    @Test
    fun customerCreditLimit_isEnforcedAuthoritatively() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val customer = repo.getCustomers().first { it.id == "cust_02" } // Um Samer: balance 90, limit 800

        // Attempt a credit sale that would push customer balance past their credit limit (e.g. 750 ILS debt added -> 840 > 800)
        val expensiveItem = Product(
            id = "p_expensive",
            nameAr = "سلعة عالية القيمة",
            nameEn = "High value item",
            category = ProductCategory.PANTRY,
            barcode = "625999999",
            priceIls = 750.00,
            costIls = 600.00,
            unitAr = "طرد",
            unitEn = "Package",
            stockCount = 10,
            accentHex = 0xFF123456
        )
        repo.addCustomProduct(expensiveItem)

        viewModel.addProductToBasket(expensiveItem, 1)
        viewModel.assignCustomerToSale(customer)
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)

        val initialInvoicesCount = viewModel.uiState.value.invoices.size

        // Attempting to post credit sale exceeding limit
        viewModel.completeCashSale(asCreditDebt = true)

        // Must be rejected by domain engine
        assertEquals("Invoice must not be posted when credit limit is exceeded", initialInvoicesCount, viewModel.uiState.value.invoices.size)
        val updatedCustomer = viewModel.uiState.value.customers.first { it.id == customer.id }
        assertEquals(customer.balanceIls, updatedCustomer.balanceIls, 0.001)
        assertNotNull(viewModel.uiState.value.lastScannedBannerAr)
        assertTrue(viewModel.uiState.value.lastScannedBannerAr!!.contains("سقف الدين"))
    }

    // =========================================================================
    // 8. Preservation of Advances vs Outstanding Debts Without Conflation
    // =========================================================================

    @Test
    fun advancesAndDebts_areSeparatedWithoutConflation() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Customer 1 owes 185.00 ILS (debt)
        val cust1 = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        assertEquals(185.00, cust1.balanceIls, 0.001)

        // Customer 3 owes 0.00 ILS initially; customer 3 pays an advance deposit of 100.00 ILS
        val cust3 = viewModel.uiState.value.customers.first { it.id == "cust_03" }
        viewModel.recordCustomerPayment(cust3.id, 100.00, "دفعة مقدمة أمانة")

        val updatedCust3 = viewModel.uiState.value.customers.first { it.id == cust3.id }
        assertEquals(-100.00, updatedCust3.balanceIls, 0.001)

        // Assert: Customer Debts (Receivables) must be strictly positive balances only
        // Customer 1 (185) + Customer 2 (90) + Customer 4 (460) = 735.00 ILS
        val expectedDebts = 185.00 + 90.00 + 460.00
        assertEquals(expectedDebts, viewModel.uiState.value.totalCustomerDebtsIls, 0.001)

        // Customer Deposits (Advances) must be strictly 150.00 ILS (cust_05 initial 50.00 + cust_03 100.00, NOT netted against debts!)
        assertEquals(150.00, viewModel.uiState.value.totalCustomerAdvancesIls, 0.001)

        // Supplier 1 owed 1200.00 ILS initially; store pays advance of 200.00 ILS to Supplier 2 (owed 450 -> pays 650 -> advance 200)
        val sup2 = viewModel.uiState.value.suppliers.first { it.id == "sup_02" }
        viewModel.paySupplierAmount(sup2.id, sup2.balanceOwedIls + 200.00)

        val updatedSup2 = viewModel.uiState.value.suppliers.first { it.id == sup2.id }
        assertEquals(-200.00, updatedSup2.balanceOwedIls, 0.001)

        // Assert: Supplier Payables must NOT conflate the 200.00 advance
        val expectedPayables = 1200.00 + 850.00 // sup_01 (1200) + sup_03 (850)
        assertEquals(expectedPayables, viewModel.uiState.value.totalSupplierPayablesIls, 0.001)
        assertEquals(200.00, viewModel.uiState.value.totalSupplierAdvancesIls, 0.001)
    }

    // =========================================================================
    // 9. Defined Dates & Shift Boundaries
    // =========================================================================

    @Test
    fun todayAndShiftFigures_reflectDefinedDateBoundaries() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val today = WesternFormatters.currentDate()

        // Today's invoices must match today's date prefix
        val todayInvoices = viewModel.uiState.value.todayInvoices
        assertTrue(todayInvoices.isNotEmpty())
        assertTrue(todayInvoices.all { it.timestamp.startsWith(today) })

        val calculatedSum = todayInvoices.sumOf { it.netTotalIls }
        assertEquals(calculatedSum, viewModel.uiState.value.todaySalesTotalIls, 0.001)

        // Physical cash drawer correctly reconciles shift float
        val starting = 350.00
        val shiftCashSales = viewModel.uiState.value.shiftCashSalesIls
        val shiftCollections = viewModel.uiState.value.shiftCustomerCollectionsIls
        val shiftDisbursements = viewModel.uiState.value.shiftSupplierDisbursementsIls
        val shiftExpenses = viewModel.uiState.value.shiftExpensesIls

        val expectedDrawer = BigDecimal(starting.toString())
            .add(BigDecimal(shiftCashSales.toString()))
            .add(BigDecimal(shiftCollections.toString()))
            .subtract(BigDecimal(shiftDisbursements.toString()))
            .subtract(BigDecimal(shiftExpenses.toString()))
            .setScale(2, RoundingMode.HALF_UP).toDouble()

        assertEquals(expectedDrawer, viewModel.uiState.value.drawerTotalCashIls, 0.001)
    }

    // =========================================================================
    // 10. Cashier Role Security: Privileged Controls & Owner Financials Hidden
    // =========================================================================

    @Test
    fun cashierRole_cannotAccessPrivilegedControlsOrExposeOwnerFinancials() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val cashier = SampleCatalog.demoStaff.first { it.role == UserRole.CASHIER }
        viewModel.switchUserSession(cashier)

        // Cashier permissions
        assertFalse(cashier.canManageInventory())
        assertFalse(cashier.canManageSuppliers())
        assertFalse(cashier.canViewReports())

        // Demo session is clearly marked as demo, not real authentication/MFA
        assertTrue(cashier.isDemoSession)

        // Privileged operations must be rejected
        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        assertFalse("Cashier cannot update prices", viewModel.updateProductPrice(oliveOil.id, 99.00))
        assertFalse("Cashier cannot adjust inventory", viewModel.adjustProductStock(oliveOil.id, 10, "تعديل"))
        assertFalse("Cashier cannot record purchase bills", viewModel.recordPurchaseBill("sup_01", "فاتورة", 200.0, 50.0))
        assertFalse("Cashier cannot disburse supplier cash", viewModel.paySupplierAmount("sup_01", 100.0))
    }

    // =========================================================================
    // 11. Single Authoritative Domain Path: ViewModel Synchronized With Repository
    // =========================================================================

    @Test
    fun singleAuthoritativeDomainPath_viewModelMutationsDelegateToRepository() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        val initialStockInRepo = repo.findProductById(oliveOil.id)!!.stockCount

        viewModel.addProductToBasket(oliveOil, 2)
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)
        viewModel.setCashQuickDenomination(100)

        // Complete sale via ViewModel
        viewModel.completeCashSale(asCreditDebt = false)

        // Verification: The repository itself was authoritatively updated
        val repoInvoices = repo.getInvoices()
        val repoStock = repo.findProductById(oliveOil.id)!!.stockCount
        assertEquals(initialStockInRepo - 2, repoStock)
        assertTrue(repoInvoices.any { it.invoiceNumber == 1043 })

        // ViewModel state is in complete lockstep with repository
        assertEquals(repoStock, viewModel.uiState.value.products.first { it.id == oliveOil.id }.stockCount)
        assertEquals(repoInvoices.size, viewModel.uiState.value.invoices.size)
    }

    // =========================================================================
    // 12. End-to-End POS Flow & Receipt Generation
    // =========================================================================

    @Test
    fun endToEndSaleFlow_basketInvoicePaymentAndReceipt_isCorrect() {
        val viewModel = TraderViewModel()

        assertEquals(AppLanguage.AR, viewModel.uiState.value.language)
        assertTrue(viewModel.uiState.value.language.isRtl)

        // Toggle language
        viewModel.toggleLanguage()
        assertEquals(AppLanguage.EN, viewModel.uiState.value.language)
        viewModel.toggleLanguage()
        assertEquals(AppLanguage.AR, viewModel.uiState.value.language)

        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        val zaatar = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" }
        viewModel.simulateBarcodeScan(oliveOil)
        viewModel.addProductToBasket(zaatar, 2)

        assertEquals(3, viewModel.uiState.value.basketItemCount)
        assertEquals(71.00, viewModel.uiState.value.basketSubtotalIls, 0.001)

        viewModel.navigateTo(TraderScreen.INVOICE_REVIEW)
        viewModel.selectDiscountPreset(DiscountPreset.ROUND_DOWN)
        assertEquals(1.00, viewModel.uiState.value.calculatedDiscountIls, 0.001)
        assertEquals(70.00, viewModel.uiState.value.basketNetTotalIls, 0.001)

        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)
        viewModel.setCashQuickDenomination(100)
        assertEquals(100.00, viewModel.uiState.value.cashTenderedIls, 0.001)
        assertEquals(30.00, viewModel.uiState.value.changeDueIls, 0.001)

        viewModel.completeCashSale()
        assertEquals(TraderScreen.RECEIPT, viewModel.uiState.value.currentScreen)
        val receipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(70.00, receipt.netTotalIls, 0.001)
        assertEquals(30.00, receipt.changeReturnedIls, 0.001)
        assertEquals(0, viewModel.uiState.value.basketItemCount)
    }

    // =========================================================================
    // 13. Correctness Gate 1: Aggregate Stock per SKU across multiple lines
    // =========================================================================

    @Test
    fun aggregateStock_multipleLinesOfSameProduct_rejectsWhenCombinedDemandExceedsStock() {
        val repo = OfflineDemoTraderRepository()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" } // initial stock = 20
        // Set stock to exactly 18
        val manager = repo.getCurrentSession()
        repo.adjustProductStock(oliveOil.id, -(oliveOil.stockCount - 18), "ضبط للاختبار", manager)
        assertEquals(18, repo.findProductById(oliveOil.id)!!.stockCount)

        // Invoice has TWO lines of olive oil: 12 + 12 = 24 items demanded. Available = 18.
        val invoice = InvoiceRecord(
            id = "inv_aggregate_stock_test",
            invoiceNumber = 7705,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(
                BasketItem(product = oliveOil, quantity = 12),
                BasketItem(product = oliveOil, quantity = 12)
            ),
            subtotalIls = 24 * oliveOil.priceIls,
            discountIls = 0.0,
            netTotalIls = 24 * oliveOil.priceIls,
            cashReceivedIls = 24 * oliveOil.priceIls,
            changeReturnedIls = 0.0,
            customerId = null,
            customerNote = "زبون نقدي",
            cashierNameAr = "مدير",
            cashierNameEn = "Manager"
        )

        val result = repo.recordSale(invoice)

        // Must reject because combined demand 24 > stock 18
        assertTrue("Combined SKU demand exceeding stock must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("المخزون غير كافٍ") || result.exceptionOrNull()!!.message!!.contains("Insufficient stock"))

        // Stock must remain untouched at 18 (NEVER -6!)
        assertEquals(18, repo.findProductById(oliveOil.id)!!.stockCount)
    }

    // =========================================================================
    // 14. Correctness Gate 2: Negative or Zero Line Quantities Rejected
    // =========================================================================

    @Test
    fun lineQuantity_negativeOrZero_isRejectedAndDoesNotMutateStock() {
        val repo = OfflineDemoTraderRepository()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" }
        val initialStock = oliveOil.stockCount

        val negativeInvoice = InvoiceRecord(
            id = "inv_negative_qty_test",
            invoiceNumber = 7706,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(BasketItem(product = oliveOil, quantity = -2)),
            subtotalIls = -76.00,
            discountIls = 0.0,
            netTotalIls = -76.00,
            cashReceivedIls = 0.0,
            changeReturnedIls = 0.0,
            customerId = null,
            customerNote = "زبون نقدي",
            cashierNameAr = "مدير",
            cashierNameEn = "Manager"
        )

        val result = repo.recordSale(negativeInvoice)
        assertTrue("Negative quantity must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)

        // Stock must NOT have increased from 20 to 22!
        assertEquals("Stock must remain unchanged", initialStock, repo.findProductById(oliveOil.id)!!.stockCount)
    }

    // =========================================================================
    // 15. Correctness Gate 3: Tampered Invoice Totals Rejected by Canonical Pricing
    // =========================================================================

    @Test
    fun invoiceTotals_callerTamperedAmount_isRejectedByCanonicalPricing() {
        val repo = OfflineDemoTraderRepository()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" } // price is 38.00 ₪

        // Caller attempts to submit an invoice for 1 olive oil (₪38.00) but claims subtotal ₪0.01 and net total ₪0.01
        val tamperedInvoice = InvoiceRecord(
            id = "inv_tampered_test",
            invoiceNumber = 7707,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(BasketItem(product = oliveOil, quantity = 1)),
            subtotalIls = 0.01,
            discountIls = 0.0,
            netTotalIls = 0.01,
            cashReceivedIls = 0.01,
            changeReturnedIls = 0.0,
            customerId = null,
            customerNote = "زبون نقدي",
            cashierNameAr = "كاشير",
            cashierNameEn = "Cashier"
        )

        val result = repo.recordSale(tamperedInvoice)
        assertTrue("Tampered invoice totals must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("mismatch"))
    }

    // =========================================================================
    // 16. Correctness Gate 4: Invoice Number Collision with Different ID Fails
    // =========================================================================

    @Test
    fun invoiceNumberCollision_differentIds_failsRatherThanSilentLoss() {
        val repo = OfflineDemoTraderRepository()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" }

        val invoice1 = InvoiceRecord(
            id = "inv_legit_first",
            invoiceNumber = 8801,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(BasketItem(product = oliveOil, quantity = 1)),
            subtotalIls = 38.00,
            discountIls = 0.0,
            netTotalIls = 38.00,
            cashReceivedIls = 38.00,
            changeReturnedIls = 0.0,
            customerId = null,
            customerNote = "فاتورة أولى",
            cashierNameAr = "مدير",
            cashierNameEn = "Manager"
        )

        val result1 = repo.recordSale(invoice1)
        assertTrue(result1.isSuccess)

        // Now an unrelated invoice with DIFFERENT ID tries to claim invoiceNumber 8801
        val invoiceCollision = InvoiceRecord(
            id = "inv_collision_second",
            invoiceNumber = 8801,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(BasketItem(product = oliveOil, quantity = 1)),
            subtotalIls = 38.00,
            discountIls = 0.0,
            netTotalIls = 38.00,
            cashReceivedIls = 38.00,
            changeReturnedIls = 0.0,
            customerId = null,
            customerNote = "فاتورة ثانية مختلفة برقم مكرر",
            cashierNameAr = "مدير",
            cashierNameEn = "Manager"
        )

        val resultCollision = repo.recordSale(invoiceCollision)
        assertTrue("Different ID with duplicate invoice number must fail", resultCollision.isFailure)
        assertTrue(resultCollision.exceptionOrNull() is IllegalStateException)
        assertTrue(resultCollision.exceptionOrNull()!!.message!!.contains("collision"))
    }

    // =========================================================================
    // 17. Correctness Gate 5: Customer Payment Replay Idempotency
    // =========================================================================

    @Test
    fun customerPayment_idempotentReplayWithSameId_doesNotDoubleDeduct() {
        val repo = OfflineDemoTraderRepository()
        val cust1 = repo.getCustomers().first { it.id == "cust_01" } // balance 185.00
        val initialBalance = cust1.balanceIls
        assertEquals(185.00, initialBalance, 0.001)

        val paymentId = "cpay_test_replay_001"

        // First receipt voucher execution: pays 10.00 ILS (185 -> 175)
        val firstResult = repo.recordCustomerPayment(
            customerId = cust1.id,
            amountIls = 10.00,
            note = "سداد نقدي تجريبي",
            paymentId = paymentId
        )
        assertTrue(firstResult.isSuccess)
        val balanceAfterFirst = repo.getCustomers().first { it.id == cust1.id }.balanceIls
        assertEquals(175.00, balanceAfterFirst, 0.001)

        // Second receipt voucher execution with SAME operation/payment ID:
        val replayResult = repo.recordCustomerPayment(
            customerId = cust1.id,
            amountIls = 10.00,
            note = "إعادة إرسال نفس السند",
            paymentId = paymentId
        )
        assertTrue("Replayed payment with same ID must return success idempotently", replayResult.isSuccess)

        // Critical accounting assertion: customer balance must remain 175.00 (NOT 165.00!)
        val balanceAfterReplay = repo.getCustomers().first { it.id == cust1.id }.balanceIls
        assertEquals("Customer balance must NOT be double-deducted on replay", 175.00, balanceAfterReplay, 0.001)
    }

    // =========================================================================
    // 18. Correctness Gate 6: Custom Product Validation (No Negative Price/Stock)
    // =========================================================================

    @Test
    fun customProduct_negativePriceOrStock_isRejected() {
        val repo = OfflineDemoTraderRepository()

        val negativePriceProduct = Product(
            id = "p_custom_neg_price",
            nameAr = "صنف بسعر سالب",
            nameEn = "Negative price item",
            category = ProductCategory.PANTRY,
            barcode = "",
            priceIls = -15.00,
            costIls = 0.0,
            unitAr = "قطعة",
            unitEn = "Item",
            stockCount = 10,
            accentHex = 0xFF000000
        )
        val priceResult = repo.addCustomProduct(negativePriceProduct)
        assertTrue("Negative price must be rejected", priceResult.isFailure)
        assertTrue(priceResult.exceptionOrNull() is IllegalArgumentException)

        val negativeStockProduct = Product(
            id = "p_custom_neg_stock",
            nameAr = "صنف بمخزون سالب",
            nameEn = "Negative stock item",
            category = ProductCategory.PANTRY,
            barcode = "",
            priceIls = 15.00,
            costIls = 0.0,
            unitAr = "قطعة",
            unitEn = "Item",
            stockCount = -5,
            accentHex = 0xFF000000
        )
        val stockResult = repo.addCustomProduct(negativeStockProduct)
        assertTrue("Negative stock count must be rejected", stockResult.isFailure)
        assertTrue(stockResult.exceptionOrNull() is IllegalArgumentException)
    }

    // =========================================================================
    // 19. Correctness Gate 7: Expense Role Permission Boundary
    // =========================================================================

    @Test
    fun recordExpense_cashierRole_isRejectedByPermissionBoundary() {
        val repo = OfflineDemoTraderRepository()
        val cashier = SampleCatalog.demoStaff.first { it.role == UserRole.CASHIER }
        val owner = SampleCatalog.demoStaff.first { it.role == UserRole.OWNER }

        // Cashier tries to record expense: must fail with SecurityException
        val cashierResult = repo.recordExpense(
            title = "فاتورة صيانة غير مصرحة",
            category = ExpenseCategory.MAINTENANCE,
            amountIls = 120.00,
            operator = cashier
        )
        assertTrue("Cashier must be blocked from recording expenses", cashierResult.isFailure)
        assertTrue(cashierResult.exceptionOrNull() is SecurityException)

        // Owner tries to record expense: succeeds
        val ownerResult = repo.recordExpense(
            title = "فاتورة صيانة معتمدة",
            category = ExpenseCategory.MAINTENANCE,
            amountIls = 120.00,
            operator = owner
        )
        assertTrue("Owner is authorized to record expenses", ownerResult.isSuccess)
    }

    // =========================================================================
    // 20. Correctness Gate 8: Anonymous Walk-in Credit Sale Rejected
    // =========================================================================

    @Test
    fun anonymousWalkIn_creditSale_isRejected() {
        val repo = OfflineDemoTraderRepository()
        val oliveOil = repo.getProducts().first { it.id == "p_olive_oil" }

        val walkInCreditInvoice = InvoiceRecord(
            id = "inv_walkin_credit",
            invoiceNumber = 7708,
            timestamp = WesternFormatters.currentTimestamp(),
            items = listOf(BasketItem(product = oliveOil, quantity = 1)),
            subtotalIls = 38.00,
            discountIls = 0.0,
            netTotalIls = 38.00,
            cashReceivedIls = 0.0,
            changeReturnedIls = 0.0,
            customerId = null, // Anonymous walk-in
            customerNote = "زبون نقدي عام",
            cashierNameAr = "مدير",
            cashierNameEn = "Manager",
            remainingDebitIls = 38.00 // Attempting to leave debt without registered customer
        )

        val result = repo.recordSale(walkInCreditInvoice)
        assertTrue("Anonymous walk-in cannot have credit/debt", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    // =========================================================================
    // 21. Navigation, Root vs Deep Hierarchy, Back Behavior & Unfinished Basket
    // =========================================================================

    @Test
    fun navigationAndBackBehavior_acrossAllModules_preservesUnfinishedBasketAndRootHierarchy() {
        val viewModel = TraderViewModel()

        // 1. Start at HOME (root destination)
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.uiState.value.isRootDestination)
        assertFalse("Back at root HOME returns false", viewModel.navigateBack())

        // 2. Navigate Home -> New Sale and build an unfinished basket
        viewModel.navigateTo(TraderScreen.NEW_SALE)
        assertEquals(TraderScreen.NEW_SALE, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.uiState.value.isRootDestination)

        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" } // 38.00
        val zaatar = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" } // 16.50
        viewModel.addProductToBasket(oliveOil, 1)
        viewModel.addProductToBasket(zaatar, 1)
        assertEquals(2, viewModel.uiState.value.basketItemCount)
        assertEquals(54.50, viewModel.uiState.value.basketSubtotalIls, 0.001)

        // 3. Switch across root tabs (New Sale -> Invoices -> Customers):
        // Root tab switches must not pile up a deep chain; Back from Customers returns directly to HOME
        viewModel.navigateTo(TraderScreen.INVOICES_HISTORY)
        assertEquals(TraderScreen.INVOICES_HISTORY, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.uiState.value.isRootDestination)

        viewModel.navigateTo(TraderScreen.CUSTOMERS)
        assertEquals(TraderScreen.CUSTOMERS, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.uiState.value.isRootDestination)
        assertEquals(listOf(TraderScreen.HOME), viewModel.uiState.value.screenHistory)

        // Drill into Customer Detail (deep screen, not root)
        val firstCustomer = viewModel.uiState.value.customers.first()
        viewModel.selectCustomerForDetail(firstCustomer)
        assertEquals(TraderScreen.CUSTOMER_DETAIL, viewModel.uiState.value.currentScreen)
        assertFalse(viewModel.uiState.value.isRootDestination)

        // Back from Customer Detail -> Customers -> Home
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.CUSTOMERS, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)

        // 4. Navigate Home -> Inventory (Products Catalog) -> Product Detail -> Back -> Back to Home
        viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        assertEquals(TraderScreen.PRODUCTS_CATALOG, viewModel.uiState.value.currentScreen)
        assertFalse("Inventory catalog is a secondary screen without root bottom bar", viewModel.uiState.value.isRootDestination)

        viewModel.selectProductForDetail(oliveOil)
        assertEquals(TraderScreen.PRODUCT_DETAIL, viewModel.uiState.value.currentScreen)
        assertFalse(viewModel.uiState.value.isRootDestination)

        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.PRODUCTS_CATALOG, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)

        // 5. Lateral Store Operations switching (Catalog -> Suppliers -> Expenses -> Reports)
        // does not build a deep back-stack loop; Back returns directly to HOME
        viewModel.navigateTo(TraderScreen.PRODUCTS_CATALOG)
        assertTrue(viewModel.uiState.value.isStoreOperationDestination)
        viewModel.navigateTo(TraderScreen.SUPPLIERS)
        assertTrue(viewModel.uiState.value.isStoreOperationDestination)
        viewModel.navigateTo(TraderScreen.EXPENSES)
        assertTrue(viewModel.uiState.value.isStoreOperationDestination)
        viewModel.navigateTo(TraderScreen.FINANCIAL_REPORTS)
        assertEquals(TraderScreen.FINANCIAL_REPORTS, viewModel.uiState.value.currentScreen)
        assertFalse(viewModel.uiState.value.isRootDestination)
        assertTrue(viewModel.uiState.value.isStoreOperationDestination)
        assertEquals(listOf(TraderScreen.HOME), viewModel.uiState.value.screenHistory)
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)

        // Lateral Profile <-> Settings switching also avoids back-stack loops and returns to HOME on Back
        viewModel.navigateTo(TraderScreen.USER_PROFILE)
        assertTrue(viewModel.uiState.value.isAccountOrSettingsDestination)
        viewModel.navigateTo(TraderScreen.BUSINESS_SETTINGS)
        assertEquals(TraderScreen.BUSINESS_SETTINGS, viewModel.uiState.value.currentScreen)
        assertFalse(viewModel.uiState.value.isRootDestination)
        assertTrue(viewModel.uiState.value.isAccountOrSettingsDestination)
        assertEquals(listOf(TraderScreen.HOME), viewModel.uiState.value.screenHistory)
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)

        // 6. Unfinished basket is still preserved; return to New Sale -> Invoice Review -> Add More Items (New Sale)
        assertEquals(2, viewModel.uiState.value.basketItemCount)
        assertEquals(54.50, viewModel.uiState.value.basketSubtotalIls, 0.001)

        viewModel.navigateTo(TraderScreen.NEW_SALE)
        viewModel.navigateTo(TraderScreen.INVOICE_REVIEW)
        assertEquals(TraderScreen.INVOICE_REVIEW, viewModel.uiState.value.currentScreen)
        assertFalse(viewModel.uiState.value.isRootDestination)

        // Tapping "Add More Items" navigates back to NEW_SALE and pops INVOICE_REVIEW rather than creating a loop
        viewModel.navigateTo(TraderScreen.NEW_SALE)
        assertEquals(TraderScreen.NEW_SALE, viewModel.uiState.value.currentScreen)
        assertEquals(listOf(TraderScreen.HOME), viewModel.uiState.value.screenHistory)

        // 7. Open Scanner sheet on NEW_SALE: Back dismisses sheet first before leaving NEW_SALE
        viewModel.setScannerSheetOpen(true)
        assertTrue(viewModel.uiState.value.isScannerSheetOpen)
        assertTrue(viewModel.navigateBack())
        assertFalse(viewModel.uiState.value.isScannerSheetOpen)
        assertEquals(TraderScreen.NEW_SALE, viewModel.uiState.value.currentScreen)

        // Next Back returns from NEW_SALE to HOME with unfinished basket intact
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)
        assertEquals(2, viewModel.uiState.value.basketItemCount)
        assertEquals(54.50, viewModel.uiState.value.basketSubtotalIls, 0.001)
    }

    // =========================================================================
    // 22. Home Screen States: Initial Home, Unfinished Basket, Owner vs Cashier
    // =========================================================================

    @Test
    fun homeScreenStates_initialUnfinishedBasketAndOwnerVsCashier_behaveTruthfully() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Initial Home state (Manager/Owner default session, empty basket)
        val initialHome = viewModel.uiState.value
        assertEquals(TraderScreen.HOME, initialHome.currentScreen)
        assertEquals(0, initialHome.basketItemCount)
        assertEquals(0.0, initialHome.basketSubtotalIls, 0.001)
        assertTrue("Initial manager session can view financial pulse", initialHome.currentSession.canViewReports())
        assertTrue("Initial manager session can restock low-stock items", initialHome.currentSession.canManageInventory())
        assertTrue("Low stock products are surfaced and sorted by urgency", initialHome.lowStockProducts.isNotEmpty())
        assertTrue(
            "Most urgent low-stock item comes first",
            initialHome.lowStockProducts.first().stockCount <= initialHome.lowStockProducts.last().stockCount
        )

        // 2. Home with an Unfinished Basket
        val oliveOil = initialHome.products.first { it.id == "p_olive_oil" } // 38.00
        viewModel.addProductToBasket(oliveOil, 3)
        val basketHome = viewModel.uiState.value
        assertEquals(TraderScreen.HOME, basketHome.currentScreen)
        assertEquals(3, basketHome.basketItemCount)
        assertEquals(3 * oliveOil.priceIls, basketHome.basketSubtotalIls, 0.001)

        // Primary action on unfinished basket Home opens Invoice Review directly
        viewModel.navigateTo(TraderScreen.INVOICE_REVIEW)
        assertEquals(TraderScreen.INVOICE_REVIEW, viewModel.uiState.value.currentScreen)
        assertTrue(viewModel.navigateBack())
        assertEquals(TraderScreen.HOME, viewModel.uiState.value.currentScreen)

        // 3. Owner vs Cashier Home visibility & restock boundaries
        val lowStockTarget = viewModel.uiState.value.lowStockProducts.first()
        val stockBeforeOwnerRestock = lowStockTarget.stockCount
        viewModel.restockProduct(lowStockTarget.id, 10)
        val stockAfterOwnerRestock = viewModel.uiState.value.products.first { it.id == lowStockTarget.id }.stockCount
        assertEquals(stockBeforeOwnerRestock + 10, stockAfterOwnerRestock)

        // Switch to Cashier on Home
        val cashier = SampleCatalog.demoStaff.first { it.role == UserRole.CASHIER }
        viewModel.switchUserSession(cashier)
        val cashierHome = viewModel.uiState.value
        assertFalse("Cashier Home hides owner financial reports pulse", cashierHome.currentSession.canViewReports())
        assertFalse("Cashier Home hides +10 inventory restock action", cashierHome.currentSession.canManageInventory())

        // Cashier attempting restock is blocked, while unfinished basket remains intact
        val target2 = cashierHome.lowStockProducts.first()
        val stockBeforeCashierAttempt = target2.stockCount
        viewModel.restockProduct(target2.id, 10)
        val stockAfterCashierAttempt = viewModel.uiState.value.products.first { it.id == target2.id }.stockCount
        assertEquals("Cashier cannot mutate stock from Home", stockBeforeCashierAttempt, stockAfterCashierAttempt)
        assertEquals(3, viewModel.uiState.value.basketItemCount)
    }

    // =========================================================================
    // 23. POS Product Selection, Search, Category Filters & Simulated Barcode
    // =========================================================================

    @Test
    fun productSelectionAndSimulatedBarcode_fastAdditionsSearchAndStockLimits_behaveTruthfully() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Arabic-normalized search ("ارز" without hamza matches "أرز حبة متوسطة فاخر") & unit search ("500 غم")
        viewModel.updateSearchQuery("ارز")
        assertTrue(
            "Arabic search without hamza finds rice",
            viewModel.uiState.value.filteredProducts.any { it.id == "p_rice_medium" }
        )

        viewModel.updateSearchQuery("500 غم")
        assertTrue(
            "Unit/variant search finds 500g items",
            viewModel.uiState.value.filteredProducts.any { it.id == "p_zaatar_jenin" }
        )

        // 2. Category filter + zero-result search with cross-category match count
        viewModel.selectCategory(ProductCategory.BEVERAGES)
        viewModel.updateSearchQuery("زيت")
        assertTrue("Olive oil is not in Beverages", viewModel.uiState.value.filteredProducts.isEmpty())
        assertTrue(
            "Cross-category match count knows olive oil exists in All Categories",
            viewModel.uiState.value.allCategoriesMatchCount > 0
        )

        // Reset filter to ALL and clear search
        viewModel.selectCategory(ProductCategory.ALL)
        viewModel.updateSearchQuery("")
        assertEquals(viewModel.uiState.value.products.size, viewModel.uiState.value.filteredProducts.size)

        // 3. Unavailable stock (stockCount == 0) cannot be added by tap or barcode scan
        val outOfStockGhee = viewModel.uiState.value.products.first { it.id == "p_baladi_ghee" }
        assertEquals(0, outOfStockGhee.stockCount)
        viewModel.simulateBarcodeScan(outOfStockGhee)
        assertEquals("Out-of-stock scan must not add to basket", 0, viewModel.uiState.value.basketItemCount)
        assertTrue("Out-of-stock scan sets error banner", viewModel.uiState.value.isBannerError)

        // 4. Multiple successive scans & fast repeated additions with exact stock limit
        val rice = viewModel.uiState.value.products.first { it.id == "p_rice_medium" } // stockCount = 3
        assertEquals(3, rice.stockCount)

        // Scan 1 -> qty 1
        viewModel.simulateBarcodeScan(rice)
        assertEquals(1, viewModel.uiState.value.quantityInBasket(rice.id))
        assertFalse(viewModel.uiState.value.isBannerError)

        // Scan 2 -> qty 2 (banner shows ×2)
        viewModel.simulateBarcodeScan(rice)
        assertEquals(2, viewModel.uiState.value.quantityInBasket(rice.id))
        assertTrue(viewModel.uiState.value.lastScannedBannerAr!!.contains("×2"))
        assertFalse(viewModel.uiState.value.isBannerError)

        // Manual barcode suffix entry for 3rd unit ("1137") -> qty 3
        viewModel.updateManualBarcodeInput("1137")
        assertTrue(viewModel.submitManualBarcode())
        assertEquals(3, viewModel.uiState.value.quantityInBasket(rice.id))
        assertEquals("", viewModel.uiState.value.manualBarcodeInput)

        // 4th scan attempt exceeds available stock (3) -> rejected with error banner
        viewModel.simulateBarcodeScan(rice)
        assertEquals("Quantity must remain capped at 3", 3, viewModel.uiState.value.quantityInBasket(rice.id))
        assertTrue("Exceeding stock via scan sets error banner", viewModel.uiState.value.isBannerError)

        // 5. Unknown barcode entry is rejected with error banner and does not alter basket
        viewModel.updateManualBarcodeInput("9999")
        assertFalse(viewModel.submitManualBarcode())
        assertTrue("Unknown barcode sets error banner", viewModel.uiState.value.isBannerError)
        assertEquals(3, viewModel.uiState.value.basketItemCount)
    }

    // =========================================================================
    // 24. Basket & Review-Before-Posting: Walk-in, Known Customer, Edits & Discounts
    // =========================================================================

    @Test
    fun basketAndReviewExperience_walkInKnownCustomerEditsAndDiscounts_preserveExactFiguresAndConsequences() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // ---------------------------------------------------------------------
        // Situation 1: Simple Walk-in Sale
        // ---------------------------------------------------------------------
        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" } // 38.00, stock 18
        val zaatar = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" } // 16.50, stock 24

        viewModel.addProductToBasket(oliveOil, 1)
        viewModel.addProductToBasket(zaatar, 2) // 38.00 + 33.00 = 71.00

        val walkInState = viewModel.uiState.value
        assertEquals(null, walkInState.selectedCustomer)
        assertEquals(3, walkInState.basketItemCount)
        assertEquals("All 3 units are stock-tracked", 3, walkInState.stockUnitsToDeduct)
        assertEquals(0, walkInState.customLinesCount)
        assertEquals(71.00, walkInState.basketSubtotalIls, 0.001)
        assertEquals(0.0, walkInState.calculatedDiscountIls, 0.001)
        assertEquals(71.00, walkInState.basketNetTotalIls, 0.001)
        assertEquals(null, walkInState.projectedCustomerBalanceIfCreditIls)
        assertFalse(walkInState.wouldCreditExceedCustomerLimit)

        // ---------------------------------------------------------------------
        // Situation 3: Cashier edits/removes a product, adds custom item, applies permitted discounts
        // ---------------------------------------------------------------------
        // Add a custom non-stock item (12.50 ILS) and increment its quantity to 2 on Review
        viewModel.addCustomQuickItem("خبز طابون طازج", "12.50")
        val customProduct = viewModel.uiState.value.basket.last().product
        assertFalse("Custom item is non-stock-tracked", customProduct.isStockTracked)
        viewModel.addProductToBasket(customProduct, 1) // 1 -> 2 (+25.00 total for custom line)

        // Remove zaatar (was 2 * 16.50 = 33.00) -> remaining: 1 olive oil (38.00) + 2 custom bread (25.00) = 63.00
        viewModel.removeProductFromBasket(zaatar.id)

        val editedState = viewModel.uiState.value
        assertEquals(3, editedState.basketItemCount) // 1 olive oil + 2 custom bread
        assertEquals("Only tracked olive oil deducts from inventory", 1, editedState.stockUnitsToDeduct)
        assertEquals("1 custom non-stock line in basket", 1, editedState.customLinesCount)
        assertEquals(63.00, editedState.basketSubtotalIls, 0.001)
        assertEquals(63.00, editedState.basketNetTotalIls, 0.001)

        // Apply ROUND_DOWN discount on 63.00 (63 % 5 = 3.00 -> rounds to 60.00)
        viewModel.selectDiscountPreset(DiscountPreset.ROUND_DOWN)
        assertEquals(3.00, viewModel.uiState.value.calculatedDiscountIls, 0.001)
        assertEquals(60.00, viewModel.uiState.value.basketNetTotalIls, 0.001)

        // Apply PERCENT_10 discount on 63.00 -> 6.30 discount, 56.70 net total
        viewModel.selectDiscountPreset(DiscountPreset.PERCENT_10)
        assertEquals(6.30, viewModel.uiState.value.calculatedDiscountIls, 0.001)
        assertEquals(56.70, viewModel.uiState.value.basketNetTotalIls, 0.001)

        // Apply CUSTOM_ILS discount of 8.00 -> 55.00 net total
        viewModel.updateCustomDiscountText("8.00")
        assertEquals(DiscountPreset.CUSTOM_ILS, viewModel.uiState.value.discountPreset)
        assertEquals(8.00, viewModel.uiState.value.calculatedDiscountIls, 0.001)
        assertEquals(55.00, viewModel.uiState.value.basketNetTotalIls, 0.001)

        // Custom discount exceeding subtotal (e.g. 999.00) is safely capped at subtotal (63.00) -> 0.00 net
        viewModel.updateCustomDiscountText("999.00")
        assertEquals(63.00, viewModel.uiState.value.calculatedDiscountIls, 0.001)
        assertEquals(0.00, viewModel.uiState.value.basketNetTotalIls, 0.001)

        // Set reasonable 3.00 discount -> net total 60.00
        viewModel.updateCustomDiscountText("3.00")
        assertEquals(60.00, viewModel.uiState.value.basketNetTotalIls, 0.001)

        // ---------------------------------------------------------------------
        // Situation 2: Known Customer Selection, Separate Delivery Note & Debt Consequence
        // ---------------------------------------------------------------------
        val customer = viewModel.uiState.value.customers.first { it.id == "cust_01" } // balance 185.00, limit 500.00
        viewModel.updateCustomerNote("توصيل لباب المحل عصراً")
        viewModel.assignCustomerToSale(customer)

        val knownCustomerState = viewModel.uiState.value
        assertEquals(customer.id, knownCustomerState.selectedCustomer?.id)
        assertEquals(
            "Assigning a customer must NOT overwrite the separate invoice/delivery note",
            "توصيل لباب المحل عصراً",
            knownCustomerState.customerNote
        )
        assertEquals(
            "Projected credit balance = 185.00 + 60.00 = 245.00",
            245.00,
            knownCustomerState.projectedCustomerBalanceIfCreditIls ?: 0.0,
            0.001
        )
        assertFalse(
            "245.00 is within the 1500.00 credit limit",
            knownCustomerState.wouldCreditExceedCustomerLimit
        )

        // If basket grows so projected debt exceeds 1500.00 limit (+120 custom bread = +1500.00 -> net 1560.00 + 185.00 = 1745.00)
        viewModel.addProductToBasket(customProduct, 120)
        assertTrue(
            "1745.00 exceeds the 1500.00 credit limit and triggers warning state",
            viewModel.uiState.value.wouldCreditExceedCustomerLimit
        )

        // Decrement custom bread quantity back to 2 (net total 60.00) and post on credit
        repeat(120) { viewModel.decrementProductInBasket(customProduct.id) }
        assertEquals(60.00, viewModel.uiState.value.basketNetTotalIls, 0.001)

        viewModel.onCashKeypadPress("C")
        viewModel.completeCashSale(asCreditDebt = true)

        val completedInvoice = viewModel.uiState.value.activeReceipt
        assertNotNull(completedInvoice)
        assertEquals(63.00, completedInvoice!!.subtotalIls, 0.001)
        assertEquals(3.00, completedInvoice.discountIls, 0.001)
        assertEquals(60.00, completedInvoice.netTotalIls, 0.001)
        assertEquals(60.00, completedInvoice.remainingDebitIls, 0.001)
        assertEquals(customer.id, completedInvoice.customerId)
        assertTrue(
            "Posted invoice note includes both customer name and delivery note",
            completedInvoice.customerNote.contains(customer.nameAr) &&
                completedInvoice.customerNote.contains("توصيل لباب المحل عصراً")
        )

        // Verify inventory deducted by 1 (18 -> 17) and customer debt increased to 245.00
        val updatedOliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }
        assertEquals(17, updatedOliveOil.stockCount)
        val updatedCustomer = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        assertEquals(245.00, updatedCustomer.balanceIls, 0.001)
    }

    // =========================================================================
    // 25. Checkout, Tender Entry & Completion Decision: Exact, Change, Shortfall, Credit & Retry
    // =========================================================================

    @Test
    fun checkoutTenderAndCompletion_exactLargerShortfallCreditAndRetry_behaveTruthfully() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" } // 38.00
        val zaatar = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" } // 16.50
        val initialDrawerCash = viewModel.uiState.value.drawerTotalCashIls

        // ---------------------------------------------------------------------
        // 1. Exact Tender (Default on navigating to CASH_PAYMENT)
        // ---------------------------------------------------------------------
        viewModel.addProductToBasket(oliveOil, 1) // 38.00
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)
        assertEquals("38.00", viewModel.uiState.value.cashInputText)
        assertTrue(viewModel.uiState.value.isCashSufficient)
        assertTrue(viewModel.uiState.value.isExactCashTendered)
        assertEquals(0.0, viewModel.uiState.value.changeDueIls, 0.001)

        viewModel.completeCashSale(asCreditDebt = false)
        val exactReceipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(38.00, exactReceipt.netTotalIls, 0.001)
        assertEquals(38.00, exactReceipt.cashReceivedIls, 0.001)
        assertEquals(0.0, exactReceipt.changeReturnedIls, 0.001)
        assertEquals(0.0, exactReceipt.remainingDebitIls, 0.001)
        assertEquals(initialDrawerCash + 38.00, viewModel.uiState.value.drawerTotalCashIls, 0.001)

        // ---------------------------------------------------------------------
        // 2. Insufficient Tender -> Specific Error -> Recovery -> Larger Tender & Change
        // ---------------------------------------------------------------------
        viewModel.startFreshSale()
        viewModel.addProductToBasket(oliveOil, 1) // 38.00
        viewModel.addProductToBasket(zaatar, 2)   // 33.00 -> Total 71.00
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)

        // Enter insufficient cash (50.00 ILS for 71.00 ILS due -> 21.00 shortfall)
        viewModel.updateCashInputText("50")
        assertFalse(viewModel.uiState.value.isCashSufficient)
        assertEquals(21.00, viewModel.uiState.value.remainingUnpaidIls, 0.001)
        assertEquals(0.0, viewModel.uiState.value.changeDueIls, 0.001)

        // Attempting cash completion with insufficient tender is rejected with specific shortfall error
        val invoicesBeforeShortfall = viewModel.uiState.value.invoices.size
        viewModel.completeCashSale(asCreditDebt = false)
        assertEquals(invoicesBeforeShortfall, viewModel.uiState.value.invoices.size)
        assertNotNull(viewModel.uiState.value.checkoutErrorMessage)
        assertTrue(viewModel.uiState.value.checkoutErrorMessage!!.contains("21.00"))

        // One-tap recovery to exact amount clears error and makes cash sufficient
        viewModel.setCashExactAmount()
        assertEquals(null, viewModel.uiState.value.checkoutErrorMessage)
        assertTrue(viewModel.uiState.value.isCashSufficient)
        assertTrue(viewModel.uiState.value.isExactCashTendered)

        // Customer hands 200 ILS bill -> 129.00 ILS change to return, net +71.00 ILS to drawer
        viewModel.setCashQuickDenomination(200)
        assertTrue(viewModel.uiState.value.isCashSufficient)
        assertFalse(viewModel.uiState.value.isExactCashTendered)
        assertEquals(129.00, viewModel.uiState.value.changeDueIls, 0.001)

        val drawerBeforeLargerTender = viewModel.uiState.value.drawerTotalCashIls
        viewModel.completeCashSale(asCreditDebt = false)
        val changeReceipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(71.00, changeReceipt.netTotalIls, 0.001)
        assertEquals(200.00, changeReceipt.cashReceivedIls, 0.001)
        assertEquals(129.00, changeReceipt.changeReturnedIls, 0.001)
        assertEquals(drawerBeforeLargerTender + 71.00, viewModel.uiState.value.drawerTotalCashIls, 0.001)

        // Double-tap after completion is a safe no-op
        val invoicesAfterLargerTender = viewModel.uiState.value.invoices.size
        viewModel.completeCashSale(asCreditDebt = false)
        assertEquals(invoicesAfterLargerTender, viewModel.uiState.value.invoices.size)

        // ---------------------------------------------------------------------
        // 3. Credit Without Customer -> Credit Over Limit -> Recovery & Truthful Full Credit Retry
        // ---------------------------------------------------------------------
        viewModel.startFreshSale()
        viewModel.addCustomQuickItem("طلبية تموين جملة", "750.00")
        viewModel.navigateTo(TraderScreen.CASH_PAYMENT)

        // 3a. Attempting credit sale without an assigned customer fails recoverably
        viewModel.completeCashSale(asCreditDebt = true)
        assertNotNull(viewModel.uiState.value.checkoutErrorMessage)
        assertEquals(TraderScreen.CASH_PAYMENT, viewModel.uiState.value.currentScreen)

        // 3b. Assigning Um Samer (balance 90.00, limit 800.00) clears previous error,
        // but 90 + 750 = 840 > 800 -> creditCeilingExcessIls = 40.00
        val umSamer = viewModel.uiState.value.customers.first { it.id == "cust_02" }
        viewModel.assignCustomerToSale(umSamer)
        assertEquals(null, viewModel.uiState.value.checkoutErrorMessage)
        assertTrue(viewModel.uiState.value.wouldCreditExceedCustomerLimit)
        assertEquals(40.00, viewModel.uiState.value.creditCeilingExcessIls, 0.001)

        // Attempting credit sale over limit is rejected and surfaces specific ceiling error
        viewModel.completeCashSale(asCreditDebt = true)
        assertNotNull(viewModel.uiState.value.checkoutErrorMessage)
        assertEquals(TraderScreen.CASH_PAYMENT, viewModel.uiState.value.currentScreen)

        // 3c. Recover by assigning Abu Tareq (balance 245.00, limit 1500.00) -> 245 + 750 = 995 <= 1500
        // Even if cashInputText had "50" typed earlier, posting on credit is 100% credit (0.00 drawer cash)
        viewModel.updateCashInputText("50")
        val abuTareq = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        viewModel.assignCustomerToSale(abuTareq)
        assertFalse(viewModel.uiState.value.wouldCreditExceedCustomerLimit)
        assertEquals(0.0, viewModel.uiState.value.creditCeilingExcessIls, 0.001)

        val drawerBeforeCredit = viewModel.uiState.value.drawerTotalCashIls
        val balanceBeforeCredit = abuTareq.balanceIls

        viewModel.completeCashSale(asCreditDebt = true)
        val creditReceipt = checkNotNull(viewModel.uiState.value.activeReceipt)
        assertEquals(TraderScreen.RECEIPT, viewModel.uiState.value.currentScreen)
        assertEquals(750.00, creditReceipt.netTotalIls, 0.001)
        assertEquals("Credit sale must record 0.00 cash received", 0.0, creditReceipt.cashReceivedIls, 0.001)
        assertEquals(0.0, creditReceipt.changeReturnedIls, 0.001)
        assertEquals("Credit sale must record full 750.00 as customer debt", 750.00, creditReceipt.remainingDebitIls, 0.001)
        assertFalse(creditReceipt.isPaidInFull)
        assertEquals("Physical cash drawer must not change on credit sale", drawerBeforeCredit, viewModel.uiState.value.drawerTotalCashIls, 0.001)

        val updatedAbuTareq = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        assertEquals(balanceBeforeCredit + 750.00, updatedAbuTareq.balanceIls, 0.001)
    }

    // =========================================================================
    // 26. Invoice History, Long Receipts, Credit vs Cash & Simulated Printing
    // =========================================================================

    @Test
    fun invoiceHistoryAndReceipts_longReceiptCreditDistinctionAndSimulatedPrinter_behaveTruthfully() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Long multi-item credit invoice (#INV-1034) is present and distinguishable from paid cash receipts
        val longCreditInvoice = viewModel.uiState.value.invoices.first { it.invoiceNumber == 1034 }
        assertFalse("Invoice #1034 is an unpaid credit invoice", longCreditInvoice.isPaidInFull)
        assertEquals(185.00, longCreditInvoice.remainingDebitIls, 0.001)
        assertTrue("Long receipt has 6+ distinct line items", longCreditInvoice.items.size >= 6)

        val paidCashInvoice = viewModel.uiState.value.invoices.first { it.invoiceNumber == 1042 }
        assertTrue("Invoice #1042 is paid in full in cash", paidCashInvoice.isPaidInFull)
        assertEquals(0.0, paidCashInvoice.remainingDebitIls, 0.001)

        // 2. Opening an existing invoice from history sets activeReceipt and navigates to RECEIPT
        viewModel.openInvoiceReceipt(longCreditInvoice)
        assertEquals(TraderScreen.RECEIPT, viewModel.uiState.value.currentScreen)
        assertEquals(longCreditInvoice.id, viewModel.uiState.value.activeReceipt?.id)

        // 3. Simulated Bluetooth printer states: connected, offline error, disconnect, and reconnect
        assertNotNull("Default simulated printer is pre-selected", viewModel.uiState.value.connectedPrinter)
        viewModel.simulateThermalPrint()

        // Connect to simulated offline/unpaired printer ("bt_star_l200") -> surfaces ERROR status
        val offlinePrinter = viewModel.uiState.value.availablePrinters.first { it.id == "bt_star_l200" }
        viewModel.connectBluetoothPrinter(offlinePrinter)
        assertEquals(com.example.model.PrinterStatus.ERROR, viewModel.uiState.value.printerStatus)

        // Disconnect printer -> status becomes DISCONNECTED and connectedPrinter is null
        viewModel.disconnectBluetoothPrinter()
        assertEquals(null, viewModel.uiState.value.connectedPrinter)
        assertEquals(com.example.model.PrinterStatus.DISCONNECTED, viewModel.uiState.value.printerStatus)

        // Reconnect working printer ("bt_alquds_58") -> recovers to READY
        val readyPrinter = viewModel.uiState.value.availablePrinters.first { it.id == "bt_alquds_58" }
        viewModel.connectBluetoothPrinter(readyPrinter)
        assertEquals(com.example.model.PrinterStatus.READY, viewModel.uiState.value.printerStatus)
    }

    // =========================================================================
    // 27. Customers (دفتر الدين): Debtor, Advance, Settled & Payment Collection
    // =========================================================================

    @Test
    fun customersLedger_debtorAdvanceSettledAndCollection_preserveBalancesAndRefreshDetail() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Verify initial directory has all three customer states:
        // Debtor (cust_01 > 0), Settled (cust_03 == 0), and Advance (cust_05 < 0)
        val debtor = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        val settled = viewModel.uiState.value.customers.first { it.id == "cust_03" }
        val advance = viewModel.uiState.value.customers.first { it.id == "cust_05" }

        assertEquals(185.00, debtor.balanceIls, 0.001)
        assertEquals(0.0, settled.balanceIls, 0.001)
        assertEquals(-50.00, advance.balanceIls, 0.001)

        // Select debtor for detail and collect 85.00 ILS cash payment
        viewModel.selectCustomerForDetail(debtor)
        assertEquals(TraderScreen.CUSTOMER_DETAIL, viewModel.uiState.value.currentScreen)
        val drawerBefore = viewModel.uiState.value.drawerTotalCashIls

        viewModel.recordCustomerPayment(
            customerId = debtor.id,
            paymentAmountIls = 85.00,
            note = "دفعة نقدية من الحساب",
            paymentId = "cpay_verify_debtor_85"
        )

        // Selected customer detail refreshes immediately to 100.00 ILS remaining debt
        assertEquals(100.00, viewModel.uiState.value.selectedCustomerForDetail!!.balanceIls, 0.001)
        assertEquals(drawerBefore + 85.00, viewModel.uiState.value.drawerTotalCashIls, 0.001)
        assertNotNull(viewModel.uiState.value.lastCustomerPaymentMessageAr)
    }

    // =========================================================================
    // 28. Catalog & Inventory: Tracked vs Non-Stock, Zero Stock & Role Boundaries
    // =========================================================================

    @Test
    fun catalogAndInventory_trackedVsNonStockZeroStockAndPermissions_behaveTruthfully() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Verify catalog includes tracked goods, zero-stock item (p_baladi_ghee), and non-stock service (p_roasting_service)
        val zeroStockGhee = viewModel.uiState.value.products.first { it.id == "p_baladi_ghee" }
        assertTrue(zeroStockGhee.isStockTracked)
        assertEquals(0, zeroStockGhee.stockCount)

        val nonStockService = viewModel.uiState.value.products.first { it.id == "p_roasting_service" }
        assertFalse("Roasting service is a non-stock item", nonStockService.isStockTracked)

        // 2. Attempting to adjust stock on a non-stock item is rejected
        viewModel.selectProductForDetail(nonStockService)
        assertFalse(
            "Non-stock item must reject stock adjustment",
            viewModel.adjustProductStock(nonStockService.id, 10, "محاولة جرد صنف خدمي")
        )
        assertTrue(viewModel.uiState.value.isInventoryActionError)

        // 3. Authorized Manager/Owner can restock zero-stock tracked item and selectedProductForDetail refreshes
        viewModel.selectProductForDetail(zeroStockGhee)
        assertTrue(viewModel.adjustProductStock(zeroStockGhee.id, 6, "توريد سمنة بلدية جديدة"))
        assertEquals(6, viewModel.uiState.value.selectedProductForDetail!!.stockCount)
        assertFalse(viewModel.uiState.value.isInventoryActionError)
    }

    // =========================================================================
    // 29. Suppliers & Operating Expenses: Financial Bills vs Stock, Advances & Guards
    // =========================================================================

    @Test
    fun suppliersAndExpenses_financialBillDoesNotMutateStock_andValidationsHold() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val supplier = viewModel.uiState.value.suppliers.first { it.id == "sup_01" } // owed 1200.00
        val totalStockBeforeBill = viewModel.uiState.value.products.sumOf { it.stockCount }
        val drawerBeforeBill = viewModel.uiState.value.drawerTotalCashIls

        // 1. Enter a financial purchase bill (Total 500.00, Paid now 200.00 -> +300.00 payable, -200.00 cash drawer)
        viewModel.selectSupplierForDetail(supplier)
        assertTrue(viewModel.recordPurchaseBill(supplier.id, "فاتورة بقوليات وحبوب", 500.0, 200.0))

        // Shelf stock must NOT change automatically from a text-only financial purchase bill
        val totalStockAfterBill = viewModel.uiState.value.products.sumOf { it.stockCount }
        assertEquals("Financial purchase bill must not auto-increment product stock", totalStockBeforeBill, totalStockAfterBill)
        assertEquals(1500.00, viewModel.uiState.value.selectedSupplierForDetail!!.balanceOwedIls, 0.001)
        assertEquals(drawerBeforeBill - 200.00, viewModel.uiState.value.drawerTotalCashIls, 0.001)

        // 2. Reject invalid supplier payment (<= 0)
        assertFalse(viewModel.paySupplierAmount(supplier.id, 0.0))
        assertTrue(viewModel.uiState.value.isSupplierActionError)

        // 3. Operating expenses: valid expense deducts from shift cash drawer, invalid amount rejected
        val shiftExpensesBefore = viewModel.uiState.value.shiftExpensesIls
        val drawerBeforeExpense = viewModel.uiState.value.drawerTotalCashIls
        viewModel.addExpense(
            title = "شراء رولات ورق حراري وأكياس تعبئة وتغليف للمحمصة",
            category = ExpenseCategory.OTHER,
            amountIls = 40.00
        )
        assertEquals(shiftExpensesBefore + 40.00, viewModel.uiState.value.shiftExpensesIls, 0.001)
        assertEquals(drawerBeforeExpense - 40.00, viewModel.uiState.value.drawerTotalCashIls, 0.001)
        assertFalse(viewModel.uiState.value.isExpenseActionError)
    }

    // =========================================================================
    // 30. Reports Cash Drawer Reconciliation, Profile Role Switch & Settings
    // =========================================================================

    @Test
    fun reportsCashDrawerReconciliation_profileAndSettings_reconcile100Percent() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Verify initial cash drawer math reconciles 100%:
        // Opening 350.00 + Today's Cash Sales 196.00 + Collections 0.00 - Supplier Paid 0.00 - Today's Shift Expenses 60.00 = 486.00 ILS
        val s0 = viewModel.uiState.value
        assertEquals(350.00, s0.startingDrawerCashIls, 0.001)
        assertEquals(196.00, s0.shiftCashSalesIls, 0.001)
        assertEquals(60.00, s0.shiftExpensesIls, 0.001)
        assertEquals(225.00, s0.historicalExpensesIls, 0.001)
        assertEquals(285.00, s0.totalExpensesIls, 0.001)
        assertEquals(486.00, s0.drawerTotalCashIls, 0.001)

        // Record a customer collection (+50.00) and a shift expense (-16.00) -> 486 + 50 - 16 = 520.00 ILS
        viewModel.recordCustomerPayment("cust_01", 50.00, "تحصيل")
        viewModel.addExpense("ضيافة", ExpenseCategory.OTHER, 16.00)

        val s1 = viewModel.uiState.value
        val manualSum = s1.startingDrawerCashIls +
            s1.shiftCashSalesIls +
            s1.shiftCustomerCollectionsIls -
            s1.shiftSupplierDisbursementsIls -
            s1.shiftExpensesIls
        assertEquals(520.00, manualSum, 0.001)
        assertEquals(manualSum, s1.drawerTotalCashIls, 0.001)

        // 2. Navigate to USER_PROFILE and switch between Owner/Manager and Cashier
        viewModel.navigateTo(TraderScreen.USER_PROFILE)
        assertEquals(TraderScreen.USER_PROFILE, viewModel.uiState.value.currentScreen)
        val cashier = viewModel.uiState.value.availableUsers.first { it.role == UserRole.CASHIER }
        viewModel.switchUserSession(cashier)
        assertEquals(UserRole.CASHIER, viewModel.uiState.value.currentSession.role)

        // 3. Settings preferences: paper width (58mm / 80mm) and theme toggle
        viewModel.selectPaperWidth(80)
        assertEquals(80, viewModel.uiState.value.receiptPaperWidthMm)
        viewModel.selectPaperWidth(58)
        assertEquals(58, viewModel.uiState.value.receiptPaperWidthMm)
    }

    // =========================================================================
    // 31. Visual Identity & Light/Dark Theme Hierarchy Verification
    // =========================================================================

    @Test
    fun visualIdentity_lightAndDarkThemeTokens_haveGradedSurfacesAndNoPinkDebit() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Default theme is Light (isDarkTheme = false)
        assertFalse("App starts in crisp Light theme by default", viewModel.uiState.value.isDarkTheme)

        // 2. Dark surfaces are properly graded (Background < Surface < SurfaceVariant) and not pitch black
        val darkBgR = com.example.ui.theme.CanvasBackgroundDark.red
        val darkSurfaceR = com.example.ui.theme.CardSurfaceDark.red
        val darkVariantR = com.example.ui.theme.SurfaceVariantDark.red
        assertTrue("Dark canvas is comfortable slate, not near-black", darkBgR > 0.08f)
        assertTrue("Dark card surface is elevated above dark canvas", darkSurfaceR > darkBgR)
        assertTrue("Dark surfaceVariant is elevated above dark card surface", darkVariantR > darkSurfaceR)

        // 3. Dark debit color is warm vermilion (green > blue), never pastel pink
        val darkDebit = com.example.ui.theme.DarkFinancialColors.debit
        assertTrue("Dark debit has warm vermilion balance (green > blue, not pink)", darkDebit.green > darkDebit.blue)

        // 4. Theme toggle works across Home, New Sale, Customers, and Financial Reports
        for (screen in listOf(
            TraderScreen.HOME,
            TraderScreen.NEW_SALE,
            TraderScreen.CUSTOMERS,
            TraderScreen.FINANCIAL_REPORTS
        )) {
            viewModel.navigateTo(screen)
            assertEquals(screen, viewModel.uiState.value.currentScreen)
            viewModel.toggleTheme()
            assertTrue(viewModel.uiState.value.isDarkTheme)
            viewModel.toggleTheme()
            assertFalse(viewModel.uiState.value.isDarkTheme)
        }
    }

    // =========================================================================
    // 32. Interactive Demo: Product Creation, Editing & Warehouse/Batch/Weighted Metadata
    // =========================================================================

    @Test
    fun interactiveProductCreationAndEditing_persistsWarehouseLotExpiryAndWeightedDisplay_respectsRoleGate() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Seeded weighted item (za'atar / frekeh) carries warehouse, lot/expiry, and isWeightedDisplayOnly
        val zaatar = viewModel.uiState.value.products.first { it.id == "p_zaatar_jenin" }
        assertTrue(zaatar.isWeightedDisplayOnly)
        assertTrue(zaatar.warehouseAr.isNotEmpty())
        assertTrue(zaatar.lotBatchCode.isNotEmpty())
        assertFalse(zaatar.expiryDate.isNullOrEmpty())

        // 2. Create a new catalog product with warehouse, lot/batch, expiry, and weighted display flag
        val initialCount = viewModel.uiState.value.products.size
        val created = viewModel.saveProduct(
            productId = null,
            nameAr = "حلاوة طحينية نابلسية فاخرة",
            nameEn = "Premium Nabulsi Halva",
            category = ProductCategory.PANTRY,
            barcode = "6251090009911",
            priceIls = 26.00,
            costIls = 19.50,
            unitAr = "علبة 700 غم",
            unitEn = "700g Tub",
            initialStock = 14,
            minStockThreshold = 4,
            warehouseAr = "رف الحلويات والمكسرات - صالة البيع",
            lotBatchCode = "LOT-HLV-2611",
            expiryDate = "2027-09-30",
            isWeightedDisplayOnly = false
        )
        assertTrue(created)
        assertEquals(initialCount + 1, viewModel.uiState.value.products.size)
        val halva = viewModel.uiState.value.products.first { it.barcode == "6251090009911" }
        assertEquals(26.00, halva.priceIls, 0.001)
        assertEquals("LOT-HLV-2611", halva.lotBatchCode)
        assertEquals("2027-09-30", halva.expiryDate)

        // 3. Edit the existing product's price, unit, warehouse, and weighted display flag
        viewModel.selectProductForDetail(halva)
        val updated = viewModel.saveProduct(
            productId = halva.id,
            nameAr = "حلاوة طحينية نابلسية بالمكسرات",
            nameEn = "Nabulsi Halva with Pistachio",
            category = ProductCategory.PANTRY,
            barcode = halva.barcode,
            priceIls = 28.50,
            costIls = 21.00,
            unitAr = "كغم موزون",
            unitEn = "Weighted kg",
            initialStock = halva.stockCount, // Stock count preserved; adjusted via stock movements
            minStockThreshold = 5,
            warehouseAr = "ركن الأجبان والحلاوة الموزونة",
            lotBatchCode = "LOT-HLV-2612",
            expiryDate = "2027-10-15",
            isWeightedDisplayOnly = true
        )
        assertTrue(updated)
        val refreshedDetail = checkNotNull(viewModel.uiState.value.selectedProductForDetail)
        assertEquals(28.50, refreshedDetail.priceIls, 0.001)
        assertEquals("LOT-HLV-2612", refreshedDetail.lotBatchCode)
        assertTrue(refreshedDetail.isWeightedDisplayOnly)

        // 4. Cashier role cannot create or edit catalog definitions
        val cashier = SampleCatalog.demoStaff.first { it.role == UserRole.CASHIER }
        viewModel.switchUserSession(cashier)
        assertFalse(
            "Cashier must be blocked from editing catalog definitions",
            viewModel.saveProduct(
                productId = halva.id,
                nameAr = "تعديل غير مصرح",
                nameEn = "Unauthorized Edit",
                category = ProductCategory.PANTRY,
                barcode = halva.barcode,
                priceIls = 5.00,
                costIls = 1.00,
                unitAr = "قطعة",
                unitEn = "Item",
                initialStock = 99,
                minStockThreshold = 1,
                warehouseAr = "",
                lotBatchCode = "",
                expiryDate = null,
                isWeightedDisplayOnly = false
            )
        )
    }

    // =========================================================================
    // 33. Interactive Demo: Sales Drafts & Quotation Without Mutating Stock or Drawer
    // =========================================================================

    @Test
    fun salesDraftsAndQuotations_saveResumeDeleteAndFormat_neverMutateInventoryOrDrawerCash() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val oliveOil = viewModel.uiState.value.products.first { it.id == "p_olive_oil" } // 38.00
        val initialOliveOilStock = oliveOil.stockCount
        val initialDrawerCash = viewModel.uiState.value.drawerTotalCashIls
        val initialDraftsCount = viewModel.uiState.value.savedDrafts.size
        assertTrue("Seeded draft_101 is available out of the box", initialDraftsCount >= 1)

        // 1. Build a basket with customer & discount, format a quotation, and save as draft
        val customer = viewModel.uiState.value.customers.first { it.id == "cust_02" }
        viewModel.addProductToBasket(oliveOil, 2) // 76.00
        viewModel.assignCustomerToSale(customer)
        viewModel.selectDiscountPreset(DiscountPreset.ROUND_DOWN) // 76 -> 75.00 (discount 1.00)
        viewModel.updateCustomerNote("عرض سعر ومذكرة تسليم صباحاً")

        val quoteText = viewModel.formatQuotationShareText()
        assertTrue("Quotation contains non-posting notice", quoteText.contains("عرض سعر") || quoteText.contains("Quotation"))
        assertTrue("Quotation contains net amount 75.00", quoteText.contains("75.00"))

        // Save current basket as draft: clears active basket and stores draft
        assertTrue(viewModel.saveCurrentBasketAsDraft("طلبية أم سامر الصباحية"))
        assertEquals(0, viewModel.uiState.value.basketItemCount)
        assertEquals(initialDraftsCount + 1, viewModel.uiState.value.savedDrafts.size)

        // Critical invariant: saving a draft or generating a quote NEVER deducts stock or changes drawer cash
        assertEquals(initialOliveOilStock, viewModel.uiState.value.products.first { it.id == oliveOil.id }.stockCount)
        assertEquals(initialDrawerCash, viewModel.uiState.value.drawerTotalCashIls, 0.001)

        // 2. Resume the saved draft back into the active basket
        val newlySavedDraft = viewModel.uiState.value.savedDrafts.first()
        assertTrue(viewModel.resumeSaleDraft(newlySavedDraft.id))
        assertEquals(2, viewModel.uiState.value.basketItemCount)
        assertEquals(75.00, viewModel.uiState.value.basketNetTotalIls, 0.001)
        assertEquals(customer.id, viewModel.uiState.value.selectedCustomer?.id)
        assertEquals(initialDraftsCount, viewModel.uiState.value.savedDrafts.size)
    }

    // =========================================================================
    // 34. Interactive Demo: Three-State Invoice Payment Status (Paid Cash, Partial, On Credit)
    // =========================================================================

    @Test
    fun invoicePaymentStatus_distinguishesPaidCashPartialAndFullCredit() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        val invoices = viewModel.uiState.value.invoices
        val paidCash = invoices.first { it.invoiceNumber == 1042 }
        val partial = invoices.first { it.invoiceNumber == 1036 }
        val fullCredit = invoices.first { it.invoiceNumber == 1034 }

        // Paid Cash (#1042): remainingDebitIls == 0
        assertEquals(com.example.model.InvoicePaymentStatus.PAID_CASH, paidCash.paymentStatus)
        assertTrue(paidCash.isPaidInFull)
        assertEquals(0.0, paidCash.remainingDebitIls, 0.001)

        // Partial (#1036): cashReceivedIls = 58.00, remainingDebitIls = 100.00, netTotalIls = 158.00
        assertEquals(com.example.model.InvoicePaymentStatus.PARTIAL, partial.paymentStatus)
        assertFalse(partial.isPaidInFull)
        assertEquals(58.00, partial.cashReceivedIls, 0.001)
        assertEquals(100.00, partial.remainingDebitIls, 0.001)

        // On Credit (#1034): cashReceivedIls = 0.00, remainingDebitIls = 185.00
        assertEquals(com.example.model.InvoicePaymentStatus.ON_CREDIT, fullCredit.paymentStatus)
        assertFalse(fullCredit.isPaidInFull)
        assertEquals(0.0, fullCredit.cashReceivedIls, 0.001)
        assertEquals(185.00, fullCredit.remainingDebitIls, 0.001)
    }

    // =========================================================================
    // 35. Interactive Demo: Customer/Supplier Editing & Physical GRN vs Financial Bill
    // =========================================================================

    @Test
    fun customerAndSupplierEditing_andPhysicalGoodsReceiptGrn_preserveFinancialAndStockSeparation() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // 1. Edit customer profile and credit limit without mutating customer debt balance
        val cust1 = viewModel.uiState.value.customers.first { it.id == "cust_01" }
        val initialDebt = cust1.balanceIls
        viewModel.selectCustomerForDetail(cust1)
        assertTrue(
            viewModel.updateCustomer(
                customerId = cust1.id,
                name = "أبو طارق الكرمي - سوبرماركت الهدى",
                phone = "0599-112299",
                address = "طولكرم - دوار شويكة",
                creditLimitIls = 1800.00
            )
        )
        val updatedCust1 = checkNotNull(viewModel.uiState.value.selectedCustomerForDetail)
        assertEquals("أبو طارق الكرمي - سوبرماركت الهدى", updatedCust1.nameAr)
        assertEquals(1800.00, updatedCust1.creditLimitIls, 0.001)
        assertEquals("Editing customer profile must preserve exact debt balance", initialDebt, updatedCust1.balanceIls, 0.001)

        val statementText = viewModel.formatCustomerStatementText(updatedCust1)
        assertTrue(statementText.contains("185.00"))

        // 2. Create/edit supplier
        val initialSupCount = viewModel.uiState.value.suppliers.size
        assertTrue(
            viewModel.saveSupplier(
                supplierId = null,
                name = "شركة سنابل الجنوب للحبوب",
                company = "سنابل الجنوب للتجارة والتوزيع",
                phone = "0598-445566"
            )
        )
        assertEquals(initialSupCount + 1, viewModel.uiState.value.suppliers.size)

        // 3. Confirm physical Goods Receipt Note (GRN) on pending bill pb_03 (#BILL-303)
        // Verifies that physical GRN increases product stock and records a StockMovement,
        // without changing the supplier's financial payable balance!
        val pendingBill = viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }
        assertFalse("Bill #303 starts pending warehouse receipt", pendingBill.isReceived)

        val sup3 = viewModel.uiState.value.suppliers.first { it.id == pendingBill.supplierId }
        val supplierPayableBeforeGrn = sup3.balanceOwedIls
        val rice = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }
        val riceStockBeforeGrn = rice.stockCount
        val movementsBeforeGrn = viewModel.uiState.value.stockMovements.size

        assertTrue(
            viewModel.confirmPhysicalGoodsReceipt(
                billId = pendingBill.id,
                productId = rice.id,
                receivedQty = 12,
                batchNote = "LOT-RICE-GRN303"
            )
        )

        val billAfterGrn = viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }
        assertTrue("Bill #303 is now marked physically received", billAfterGrn.isReceived)
        assertEquals(riceStockBeforeGrn + 12, viewModel.uiState.value.products.first { it.id == rice.id }.stockCount)
        assertEquals(movementsBeforeGrn + 1, viewModel.uiState.value.stockMovements.size)
        assertEquals(
            "Physical GRN must NOT alter supplier financial payable balance",
            supplierPayableBeforeGrn,
            viewModel.uiState.value.suppliers.first { it.id == sup3.id }.balanceOwedIls,
            0.001
        )
    }

    // =========================================================================
    // 36. Interactive Demo: Shift Opening Float, Expected Book Cash vs Physical Count Variance
    // =========================================================================

    @Test
    fun shiftReconciliation_separatesExpectedBookCashFromCountedPhysicalCashAndCalculatesVariance() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)

        // Initial expected book cash: 350.00 opening + 196.00 sales - 60.00 expenses = 486.00 ILS
        assertEquals(350.00, viewModel.uiState.value.startingDrawerCashIls, 0.001)
        assertEquals(486.00, viewModel.uiState.value.expectedBookCashIls, 0.001)
        assertEquals(null, viewModel.uiState.value.countedPhysicalCashIls)
        assertEquals(null, viewModel.uiState.value.shiftCashVarianceIls)

        // 1. Update shift opening float to 400.00 -> Expected book cash becomes 536.00 ILS
        assertTrue(viewModel.updateStartingDrawerCash(400.00))
        assertEquals(400.00, viewModel.uiState.value.startingDrawerCashIls, 0.001)
        assertEquals(536.00, viewModel.uiState.value.expectedBookCashIls, 0.001)

        // 2. Merchant physically counts 526.00 ILS in the drawer -> -10.00 ILS shortage variance
        // Expected book cash remains 536.00 ILS (never conflated with counted physical cash!)
        viewModel.updateCountedPhysicalCashText("526.00")
        assertEquals(536.00, viewModel.uiState.value.expectedBookCashIls, 0.001)
        assertEquals(526.00, checkNotNull(viewModel.uiState.value.countedPhysicalCashIls), 0.001)
        assertEquals(-10.00, checkNotNull(viewModel.uiState.value.shiftCashVarianceIls), 0.001)

        // 3. Merchant finds a 20 ILS note (546.00 ILS counted) -> +10.00 ILS overage variance
        viewModel.updateCountedPhysicalCashText("546.00")
        assertEquals(536.00, viewModel.uiState.value.expectedBookCashIls, 0.001)
        assertEquals(546.00, checkNotNull(viewModel.uiState.value.countedPhysicalCashIls), 0.001)
        assertEquals(10.00, checkNotNull(viewModel.uiState.value.shiftCashVarianceIls), 0.001)

        // 4. Toggle shift closing preview and reopen
        assertFalse(viewModel.uiState.value.isShiftClosedPreview)
        viewModel.toggleShiftClosePreview(true)
        assertTrue(viewModel.uiState.value.isShiftClosedPreview)
        assertNotNull(viewModel.uiState.value.shiftClosedTimestamp)
        viewModel.toggleShiftClosePreview(false)
        assertFalse(viewModel.uiState.value.isShiftClosedPreview)
    }

    // =========================================================================
    // 37. Regression: Physical Goods Receiving (GRN) Safety & Idempotency Guards
    // =========================================================================

    @Test
    fun physicalGoodsReceiptGrn_rejectsUnknownBill_unrelatedProduct_mismatchedQuantity_textOnlyBill_andDuplicateReceipt() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val manager = repo.getCurrentSession()

        val pendingBill = viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }
        assertEquals("p_rice_medium", pendingBill.expectedProductId)
        assertEquals(12, pendingBill.expectedQuantity)
        assertFalse(pendingBill.isReceived)

        val riceBefore = viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        val oliveOilBefore = viewModel.uiState.value.products.first { it.id == "p_olive_oil" }.stockCount

        // 1. Unknown bill ID -> rejected with NoSuchElementException, zero stock mutation
        val unknownBillResult = repo.confirmPhysicalGoodsReceipt(
            billId = "pb_unknown_999",
            productId = "p_rice_medium",
            receivedQty = 12,
            batchNote = "Invalid bill",
            operator = manager
        )
        assertTrue("Unknown bill ID must be rejected", unknownBillResult.isFailure)
        assertTrue(unknownBillResult.exceptionOrNull() is NoSuchElementException)
        assertEquals(riceBefore, repo.findProductById("p_rice_medium")!!.stockCount)

        // 2. Unrelated product ID -> rejected with IllegalArgumentException, bill remains unreceived
        val unrelatedProductResult = repo.confirmPhysicalGoodsReceipt(
            billId = "pb_03",
            productId = "p_olive_oil",
            receivedQty = 12,
            batchNote = "Wrong SKU",
            operator = manager
        )
        assertTrue("Unrelated product must be rejected for GRN", unrelatedProductResult.isFailure)
        assertTrue(unrelatedProductResult.exceptionOrNull() is IllegalArgumentException)
        assertEquals(oliveOilBefore, repo.findProductById("p_olive_oil")!!.stockCount)
        assertFalse(repo.getPurchaseBills().first { it.id == "pb_03" }.isReceived)

        // 3. Mismatched / partial quantity -> rejected with IllegalArgumentException, bill remains unreceived
        val mismatchedQtyResult = repo.confirmPhysicalGoodsReceipt(
            billId = "pb_03",
            productId = "p_rice_medium",
            receivedQty = 5,
            batchNote = "Partial mismatch",
            operator = manager
        )
        assertTrue("Mismatched quantity must be rejected in bounded single-SKU GRN", mismatchedQtyResult.isFailure)
        assertTrue(mismatchedQtyResult.exceptionOrNull() is IllegalArgumentException)
        assertEquals(riceBefore, repo.findProductById("p_rice_medium")!!.stockCount)
        assertFalse(repo.getPurchaseBills().first { it.id == "pb_03" }.isReceived)

        // 4. Text-only financial purchase bill (no linked SKU) -> rejected with IllegalStateException
        val textOnlyBill = repo.recordPurchaseBill(
            supplierId = "sup_01",
            itemsSummary = "فاتورة مشتريات مالية ملخصة بدون بنود أصناف",
            totalIls = 300.0,
            paidNowIls = 100.0,
            operator = manager
        ).getOrThrow()
        assertEquals(null, textOnlyBill.expectedProductId)
        val textOnlyGrnResult = repo.confirmPhysicalGoodsReceipt(
            billId = textOnlyBill.id,
            productId = "p_olive_oil",
            receivedQty = 10,
            batchNote = "Attempt GRN on text-only financial bill",
            operator = manager
        )
        assertTrue("Text-only financial bill without expected SKU must reject automated GRN", textOnlyGrnResult.isFailure)
        assertTrue(textOnlyGrnResult.exceptionOrNull() is IllegalStateException)
        assertEquals(oliveOilBefore, repo.findProductById("p_olive_oil")!!.stockCount)

        // 5. Valid GRN matching expected SKU and expected quantity succeeds once
        assertTrue(
            viewModel.confirmPhysicalGoodsReceipt(
                billId = "pb_03",
                productId = "p_rice_medium",
                receivedQty = 12,
                batchNote = "LOT-RICE-GRN303"
            )
        )
        assertEquals(riceBefore + 12, viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount)
        assertTrue(viewModel.uiState.value.purchaseBills.first { it.id == "pb_03" }.isReceived)

        // 6. Repeated GRN call on already-received bill (#BILL-0303 or #BILL-0301) is rejected -> never double-increments stock
        assertFalse(
            "Repeated GRN on already-received bill must fail",
            viewModel.confirmPhysicalGoodsReceipt(
                billId = "pb_03",
                productId = "p_rice_medium",
                receivedQty = 12,
                batchNote = "Duplicate GRN attempt"
            )
        )
        assertEquals(
            "Stock must not increase a second time for the same bill",
            riceBefore + 12,
            viewModel.uiState.value.products.first { it.id == "p_rice_medium" }.stockCount
        )
    }

    // =========================================================================
    // 38. Regression: Sample Catalog Financial Consistency (#INV-1036, #INV-1038 & Statements)
    // =========================================================================

    @Test
    fun sampleCatalogFinancialConsistency_invoicesLineItemsReceiptsCustomerBalancesAndStatements_reconcile100Percent() {
        val repo = OfflineDemoTraderRepository()
        val viewModel = TraderViewModel(repo)
        val state = viewModel.uiState.value

        // 1. Every seeded invoice's subtotalIls and netTotalIls match its canonical line items
        for (invoice in state.invoices) {
            val lineSum = invoice.items.fold(BigDecimal.ZERO) { acc, item ->
                val line = BigDecimal(item.product.priceIls.toString())
                    .multiply(BigDecimal(item.quantity))
                    .setScale(2, RoundingMode.HALF_UP)
                acc.add(line)
            }.setScale(2, RoundingMode.HALF_UP).toDouble()

            assertEquals(
                "Invoice #INV-${invoice.invoiceNumber} subtotal must equal canonical sum of line items",
                lineSum,
                invoice.subtotalIls,
                0.001
            )
            val expectedNet = BigDecimal(invoice.subtotalIls.toString())
                .subtract(BigDecimal(invoice.discountIls.toString()))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
            assertEquals(
                "Invoice #INV-${invoice.invoiceNumber} netTotal must equal subtotal - discount",
                expectedNet,
                invoice.netTotalIls,
                0.001
            )

            // Every customer-linked invoice has a matching transaction entry in customerTransactions
            if (invoice.customerId != null) {
                val matchingTx = state.customerTransactions.firstOrNull {
                    it.customerId == invoice.customerId && it.invoiceNumber == invoice.invoiceNumber
                }
                assertNotNull(
                    "Customer invoice #INV-${invoice.invoiceNumber} must exist in customerTransactions",
                    matchingTx
                )
            }
        }

        // 2. Partial-payment invoice #INV-1036 reconciles with cust_04 balance (460.00 ILS) and statement history
        val cust04 = state.customers.first { it.id == "cust_04" }
        assertEquals(460.00, cust04.balanceIls, 0.001)

        val cust04Txs = state.customerTransactions.filter { it.customerId == "cust_04" }
        assertEquals(2, cust04Txs.size)

        val latestTx = cust04Txs[0] // 2026-11-02 18:20 (#INV-1036)
        val priorTx = cust04Txs[1]  // 2026-11-01 10:00 (Payment 500.00 -> running balance 360.00)
        assertEquals(Integer.valueOf(1036), latestTx.invoiceNumber)
        assertEquals(158.00, latestTx.debitIls, 0.001)
        assertEquals(58.00, latestTx.creditIls, 0.001)
        assertEquals(460.00, latestTx.runningBalanceIls, 0.001)
        assertEquals(360.00, priorTx.runningBalanceIls, 0.001)
        assertEquals(
            "Running balance after #INV-1036 must equal prior balance (360) + unpaid remainder (158 - 58 = 100)",
            priorTx.runningBalanceIls + (latestTx.debitIls - latestTx.creditIls),
            latestTx.runningBalanceIls,
            0.001
        )
        assertEquals(cust04.balanceIls, latestTx.runningBalanceIls, 0.001)

        // Statement share text for cust_04 includes #INV-1036, 158.00 total, 58.00 paid, +100.00 remaining, and 460.00 balance
        val cust04Statement = viewModel.formatCustomerStatementText(cust04)
        assertTrue(cust04Statement.contains("1036"))
        assertTrue(cust04Statement.contains("158.00"))
        assertTrue(cust04Statement.contains("58.00"))
        assertTrue(cust04Statement.contains("100.00"))
        assertTrue(cust04Statement.contains("460.00"))

        // 3. Every customer with transactions has latest runningBalanceIls equal to customer.balanceIls
        for (customer in state.customers) {
            val txs = state.customerTransactions.filter { it.customerId == customer.id }
            if (txs.isNotEmpty()) {
                assertEquals(
                    "Latest transaction running balance for ${customer.id} must match customer.balanceIls",
                    customer.balanceIls,
                    txs.first().runningBalanceIls,
                    0.001
                )
            }
        }
    }
}
