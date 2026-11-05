package com.example.data

import com.example.model.AppLanguage
import com.example.model.BusinessInfo
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.ExpenseCategory
import com.example.model.ExpenseEntry
import com.example.model.InvoiceRecord
import com.example.model.Product
import com.example.model.PurchaseBill
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.UserSession
import com.example.model.WesternFormatters
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Bounded domain repository contract for Small Trader (التاجر الصغير).
 *
 * Architecture Boundary Notice:
 * The existing Laravel backend (/api/v1) is a session-authenticated API and
 * represents the future single source of truth for all production commercial data.
 *
 * This contract establishes the explicit domain boundary between the Compose UI/ViewModel
 * and persistence. [OfflineDemoTraderRepository] provides truthful, strictly bounded in-memory
 * financial and stock logic for demonstration without inventing live endpoints or modifying
 * production systems.
 */
interface TraderRepository {
    fun getCurrentSession(): UserSession
    fun getBusinessInfo(): BusinessInfo

    // Product & Inventory
    fun getProducts(): List<Product>
    fun findProductById(id: String): Product?
    fun updateProductPrice(productId: String, newPriceIls: Double, operator: UserSession): Result<Unit>
    fun adjustProductStock(productId: String, adjustment: Int, reason: String, operator: UserSession): Result<StockMovement>
    fun getStockMovements(): List<StockMovement>
    fun addCustomProduct(product: Product): Result<Product>

    // Sales & Invoices
    fun getInvoices(): List<InvoiceRecord>
    fun recordSale(invoice: InvoiceRecord): Result<InvoiceRecord>

    // Customers & Collections
    fun getCustomers(): List<Customer>
    fun getCustomerTransactions(): List<CustomerTransaction>
    fun recordCustomerPayment(customerId: String, amountIls: Double, note: String, paymentId: String = ""): Result<CustomerTransaction>
    fun createCustomer(name: String, phone: String, address: String): Result<Customer>

    // Suppliers & Disbursements
    fun getSuppliers(): List<Supplier>
    fun getPurchaseBills(): List<PurchaseBill>
    fun getSupplierPayments(): List<SupplierPayment>
    fun recordPurchaseBill(supplierId: String, itemsSummary: String, totalIls: Double, paidNowIls: Double, operator: UserSession): Result<PurchaseBill>
    fun recordSupplierPayment(supplierId: String, amountIls: Double, note: String, operator: UserSession): Result<SupplierPayment>

    // Expenses
    fun getExpenses(): List<ExpenseEntry>
    fun recordExpense(title: String, category: ExpenseCategory, amountIls: Double, operator: UserSession): Result<ExpenseEntry>
}

/**
 * In-memory reference implementation strictly adhering to accounting, stock, and role integrity.
 * This class is the single authoritative domain engine for local POS demo operations.
 */
class OfflineDemoTraderRepository : TraderRepository {

    private var currentSession: UserSession = SampleCatalog.demoStaff.first()
    private val businessInfo: BusinessInfo = SampleCatalog.defaultBusiness

    private val products = SampleCatalog.initialProducts.toMutableList()
    private val stockMovements = SampleCatalog.initialStockMovements.toMutableList()
    private val invoices = SampleCatalog.initialInvoices(SampleCatalog.initialProducts).toMutableList()
    private val customers = SampleCatalog.initialCustomers.toMutableList()
    private val customerTransactions = SampleCatalog.initialCustomerTransactions.toMutableList()
    private val suppliers = SampleCatalog.initialSuppliers.toMutableList()
    private val purchaseBills = SampleCatalog.initialPurchaseBills.toMutableList()
    private val supplierPayments = mutableListOf<SupplierPayment>()
    private val expenses = SampleCatalog.initialExpenses.toMutableList()

    override fun getCurrentSession(): UserSession = currentSession
    override fun getBusinessInfo(): BusinessInfo = businessInfo

    override fun getProducts(): List<Product> = products.toList()
    override fun findProductById(id: String): Product? = products.firstOrNull { it.id == id }

    override fun getStockMovements(): List<StockMovement> = stockMovements.toList()

    override fun addCustomProduct(product: Product): Result<Product> {
        if (product.nameAr.isBlank() && product.nameEn.isBlank()) {
            return Result.failure(IllegalArgumentException("Product name cannot be blank."))
        }
        if (product.priceIls < 0.0) {
            return Result.failure(IllegalArgumentException("Product price cannot be negative."))
        }
        if (product.costIls < 0.0) {
            return Result.failure(IllegalArgumentException("Product cost cannot be negative."))
        }
        if (product.stockCount < 0) {
            return Result.failure(IllegalArgumentException("Product stock count cannot be negative."))
        }
        products.add(0, product)
        return Result.success(product)
    }

    override fun updateProductPrice(productId: String, newPriceIls: Double, operator: UserSession): Result<Unit> {
        if (!operator.canManageInventory()) {
            return Result.failure(SecurityException("غير مصرح: تعديل الأسعار متاح للمالك والمدير فقط | Permission denied: Price update requires Owner or Manager"))
        }
        if (newPriceIls <= 0.0) {
            return Result.failure(IllegalArgumentException("Price must be greater than zero."))
        }
        val idx = products.indexOfFirst { it.id == productId }
        if (idx == -1) return Result.failure(NoSuchElementException("Product not found: $productId"))
        products[idx] = products[idx].copy(priceIls = BigDecimal(newPriceIls.toString()).setScale(2, RoundingMode.HALF_UP).toDouble())
        return Result.success(Unit)
    }

    override fun adjustProductStock(productId: String, adjustment: Int, reason: String, operator: UserSession): Result<StockMovement> {
        if (!operator.canManageInventory()) {
            return Result.failure(SecurityException("غير مصرح: تعديل المخزون متاح للمالك والمدير فقط | Permission denied: Stock adjustment requires Owner or Manager"))
        }
        if (adjustment == 0) {
            return Result.failure(IllegalArgumentException("Stock adjustment delta cannot be zero."))
        }
        val idx = products.indexOfFirst { it.id == productId }
        if (idx == -1) return Result.failure(NoSuchElementException("Product not found: $productId"))

        val currentProd = products[idx]
        if (!currentProd.isStockTracked) {
            return Result.failure(IllegalStateException("هذا الصنف غير مقيد بجرد مخزني ولا يمكن تعديل كميته | Non-stock item does not track physical inventory quantities"))
        }
        val candidate = currentProd.stockCount + adjustment
        if (candidate < 0) {
            return Result.failure(IllegalStateException("لا يمكن تقليل المخزون إلى ما دون الصفر | Cannot reduce stock below zero. Current: ${currentProd.stockCount}, Delta: $adjustment"))
        }

        products[idx] = currentProd.copy(stockCount = candidate)
        val movement = StockMovement(
            id = "sm_${System.currentTimeMillis()}",
            productId = productId,
            date = WesternFormatters.currentTimestamp(),
            delta = adjustment,
            reasonAr = reason.ifEmpty { "تعديل جرد يدوي" },
            reasonEn = reason.ifEmpty { "Manual inventory adjustment" },
            resultingStock = candidate
        )
        stockMovements.add(0, movement)
        return Result.success(movement)
    }

    override fun getInvoices(): List<InvoiceRecord> = invoices.toList()

    override fun recordSale(invoice: InvoiceRecord): Result<InvoiceRecord> {
        // 1. Line item validation
        if (invoice.items.isEmpty()) {
            return Result.failure(IllegalArgumentException("Invoice must contain at least one item."))
        }
        for (item in invoice.items) {
            if (item.quantity <= 0) {
                return Result.failure(IllegalArgumentException("Item quantity must be greater than zero. Found: ${item.quantity} for product ${item.product.nameEn}"))
            }
            if (item.product.priceIls < 0.0) {
                return Result.failure(IllegalArgumentException("Item price cannot be negative."))
            }
        }

        // 2. Exact replay vs Identifier collision
        val existingById = invoices.firstOrNull { it.id == invoice.id }
        if (existingById != null) {
            // Exact idempotent replay: return existing invoice without duplicate inventory deductions or double debt posting
            return Result.success(existingById)
        }

        val collisionByNumber = invoices.firstOrNull { it.invoiceNumber == invoice.invoiceNumber }
        if (collisionByNumber != null) {
            return Result.failure(IllegalStateException("Invoice number collision: #INV-${invoice.invoiceNumber} already exists for a different transaction."))
        }

        // 3. Party ID verification
        val customer = if (invoice.customerId != null) {
            customers.firstOrNull { it.id == invoice.customerId }
                ?: return Result.failure(NoSuchElementException("Customer not found: ${invoice.customerId}"))
        } else null

        // 4. Stale operations & Aggregate inventory validation across all lines per SKU
        val aggregateDemand = invoice.items
            .filter { it.product.isStockTracked }
            .groupBy { it.product.id }

        for ((productId, lines) in aggregateDemand) {
            val totalDemanded = lines.sumOf { it.quantity }
            val catalogProd = products.firstOrNull { it.id == productId }
                ?: return Result.failure(NoSuchElementException("Product not in catalog: $productId"))
            if (catalogProd.stockCount < totalDemanded) {
                return Result.failure(
                    IllegalStateException("المخزون غير كافٍ للصنف ${catalogProd.nameAr}. المتوفر: ${catalogProd.stockCount}، المطلوب إجمالاً: $totalDemanded | Insufficient stock for ${catalogProd.nameEn}. Available: ${catalogProd.stockCount}, Demanded: $totalDemanded")
                )
            }
        }

        // 5. Monetary invariant derivation & validation (prevent caller-tampered invoice totals)
        var canonicalSubtotal = BigDecimal.ZERO
        for (item in invoice.items) {
            val catalogProd = products.firstOrNull { it.id == item.product.id }
            val unitPrice = if (catalogProd != null) {
                BigDecimal(catalogProd.priceIls.toString()).setScale(2, RoundingMode.HALF_UP)
            } else {
                BigDecimal(item.product.priceIls.toString()).setScale(2, RoundingMode.HALF_UP)
            }
            val lineTotal = unitPrice.multiply(BigDecimal(item.quantity.toString())).setScale(2, RoundingMode.HALF_UP)
            canonicalSubtotal = canonicalSubtotal.add(lineTotal)
        }
        canonicalSubtotal = canonicalSubtotal.setScale(2, RoundingMode.HALF_UP)

        val declaredSubtotal = BigDecimal(invoice.subtotalIls.toString()).setScale(2, RoundingMode.HALF_UP)
        val declaredDiscount = BigDecimal(invoice.discountIls.toString()).setScale(2, RoundingMode.HALF_UP)
        val declaredNet = BigDecimal(invoice.netTotalIls.toString()).setScale(2, RoundingMode.HALF_UP)

        if (declaredSubtotal.subtract(canonicalSubtotal).abs() > BigDecimal("0.02")) {
            return Result.failure(IllegalArgumentException("Invoice subtotal mismatch. Expected: $canonicalSubtotal ₪, declared: $declaredSubtotal ₪"))
        }
        if (declaredDiscount < BigDecimal.ZERO || declaredDiscount > declaredSubtotal) {
            return Result.failure(IllegalArgumentException("Invalid invoice discount: $declaredDiscount ₪"))
        }
        val canonicalNet = canonicalSubtotal.subtract(declaredDiscount).setScale(2, RoundingMode.HALF_UP)
        if (declaredNet.subtract(canonicalNet).abs() > BigDecimal("0.02")) {
            return Result.failure(IllegalArgumentException("Invoice net total mismatch. Expected: $canonicalNet ₪, declared: $declaredNet ₪"))
        }

        // 6. Payment & Credit limit validation
        val tendered = BigDecimal(invoice.cashReceivedIls.toString()).setScale(2, RoundingMode.HALF_UP)
        val remainingDebit = BigDecimal(invoice.remainingDebitIls.toString()).setScale(2, RoundingMode.HALF_UP)

        if (tendered < BigDecimal.ZERO) {
            return Result.failure(IllegalArgumentException("Cash tendered cannot be negative."))
        }
        if (remainingDebit < BigDecimal.ZERO) {
            return Result.failure(IllegalArgumentException("Remaining debit cannot be negative."))
        }

        if (invoice.customerId == null) {
            // Anonymous walk-in cash sale: must be paid in full, no debt allowed
            if (remainingDebit > BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("Cannot issue credit to anonymous walk-in customer."))
            }
            if (tendered < declaredNet) {
                return Result.failure(IllegalArgumentException("المبلغ النقدي المسلم غير كافٍ لإتمام البيع | Insufficient cash tendered for anonymous cash sale."))
            }
        } else {
            // Registered customer sale: verify credit limit if debt is added
            if (remainingDebit > BigDecimal.ZERO) {
                val cust = customer!!
                val newBalance = BigDecimal(cust.balanceIls.toString())
                    .add(remainingDebit)
                    .setScale(2, RoundingMode.HALF_UP)
                    .toDouble()
                if (newBalance > cust.creditLimitIls) {
                    return Result.failure(
                        IllegalStateException("تجاوز سقف الدين المحدد (${cust.creditLimitIls} ₪) للزبون ${cust.nameAr}. الرصيد الناتج: $newBalance ₪ | Sale exceeds credit limit")
                    )
                }
            }
        }

        // 7. Truthfully deduct physical inventory by aggregate SKU demand
        val timestamp = invoice.timestamp.ifEmpty { WesternFormatters.currentTimestamp() }
        for ((productId, lines) in aggregateDemand) {
            val totalDemanded = lines.sumOf { it.quantity }
            val idx = products.indexOfFirst { it.id == productId }
            if (idx >= 0) {
                val currentStock = products[idx].stockCount
                val resultingStock = currentStock - totalDemanded
                products[idx] = products[idx].copy(stockCount = resultingStock)
                stockMovements.add(
                    0,
                    StockMovement(
                        id = "sm_${System.currentTimeMillis()}_${products[idx].id}",
                        productId = products[idx].id,
                        date = timestamp,
                        delta = -totalDemanded,
                        reasonAr = "مبيعات فاتورة #INV-${invoice.invoiceNumber}",
                        reasonEn = "Sales Invoice #INV-${invoice.invoiceNumber}",
                        resultingStock = resultingStock
                    )
                )
            }
        }

        // 8. Update customer ledger and transactions if linked
        if (customer != null) {
            val custIdx = customers.indexOfFirst { it.id == customer.id }
            val newDebtAdded = remainingDebit
            val newTotalPurchases = BigDecimal(customer.totalPurchasesIls.toString())
                .add(declaredNet)
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
            val newBalance = BigDecimal(customer.balanceIls.toString())
                .add(newDebtAdded)
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()

            customers[custIdx] = customer.copy(
                balanceIls = newBalance,
                totalPurchasesIls = newTotalPurchases
            )

            customerTransactions.add(
                0,
                CustomerTransaction(
                    id = "ctx_${System.currentTimeMillis()}_${invoice.invoiceNumber}",
                    customerId = customer.id,
                    date = timestamp,
                    typeAr = if (invoice.remainingDebitIls > 0.0) "مبيعات على الحساب #INV-${invoice.invoiceNumber}" else "فاتورة مبيعات مسددة #INV-${invoice.invoiceNumber}",
                    typeEn = if (invoice.remainingDebitIls > 0.0) "Credit Sale #INV-${invoice.invoiceNumber}" else "Paid Invoice #INV-${invoice.invoiceNumber}",
                    invoiceNumber = invoice.invoiceNumber,
                    debitIls = invoice.netTotalIls,
                    creditIls = invoice.cashReceivedIls,
                    runningBalanceIls = newBalance,
                    note = invoice.customerNote
                )
            )
        }

        invoices.add(0, invoice)
        return Result.success(invoice)
    }

    override fun getCustomers(): List<Customer> = customers.toList()
    override fun getCustomerTransactions(): List<CustomerTransaction> = customerTransactions.toList()

    override fun recordCustomerPayment(
        customerId: String,
        amountIls: Double,
        note: String,
        paymentId: String
    ): Result<CustomerTransaction> {
        if (customerId.isBlank()) {
            return Result.failure(IllegalArgumentException("Customer ID cannot be blank."))
        }
        val idx = customers.indexOfFirst { it.id == customerId }
        if (idx == -1) return Result.failure(NoSuchElementException("Customer not found: $customerId"))
        if (amountIls <= 0.0) {
            return Result.failure(IllegalArgumentException("Payment amount must be greater than zero."))
        }

        // True replay idempotency: if operation ID already recorded, return transaction without double deduction
        if (paymentId.isNotBlank()) {
            val existingTx = customerTransactions.firstOrNull { it.id == paymentId }
            if (existingTx != null) {
                return Result.success(existingTx)
            }
        }

        val currentCust = customers[idx]
        val amountExact = BigDecimal(amountIls.toString()).setScale(2, RoundingMode.HALF_UP)
        // Exact decimal math preserving overpayments (negative balance = customer credit deposit / advance)
        val newBalance = BigDecimal(currentCust.balanceIls.toString())
            .subtract(amountExact)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()

        customers[idx] = currentCust.copy(balanceIls = newBalance)

        val tx = CustomerTransaction(
            id = if (paymentId.isNotBlank()) paymentId else "ctx_${System.currentTimeMillis()}",
            customerId = customerId,
            date = WesternFormatters.currentTimestamp(),
            typeAr = "سند قبض نقدي (سداد حساب)",
            typeEn = "Receipt Voucher (Payment)",
            invoiceNumber = null,
            debitIls = 0.0,
            creditIls = amountExact.toDouble(),
            runningBalanceIls = newBalance,
            note = note.ifEmpty { "سند قبض نقدي بالصندوق" }
        )
        customerTransactions.add(0, tx)
        return Result.success(tx)
    }

    override fun createCustomer(name: String, phone: String, address: String): Result<Customer> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Customer name cannot be empty."))
        val newCust = Customer(
            id = "cust_${System.currentTimeMillis()}",
            nameAr = name.trim(),
            nameEn = name.trim(),
            phone = phone.trim().ifEmpty { "0599-000000" },
            addressAr = address.trim().ifEmpty { "نابلس" },
            addressEn = address.trim().ifEmpty { "Nablus" },
            balanceIls = 0.0,
            creditLimitIls = 1000.0,
            totalPurchasesIls = 0.0
        )
        customers.add(0, newCust)
        return Result.success(newCust)
    }

    override fun getSuppliers(): List<Supplier> = suppliers.toList()
    override fun getPurchaseBills(): List<PurchaseBill> = purchaseBills.toList()
    override fun getSupplierPayments(): List<SupplierPayment> = supplierPayments.toList()

    override fun recordPurchaseBill(
        supplierId: String,
        itemsSummary: String,
        totalIls: Double,
        paidNowIls: Double,
        operator: UserSession
    ): Result<PurchaseBill> {
        if (!operator.canManageSuppliers()) {
            return Result.failure(SecurityException("غير مصرح: تسجيل فواتير الشراء متاح للمالك والمدير فقط | Permission denied: Purchase bill requires Owner or Manager"))
        }
        if (supplierId.isBlank()) {
            return Result.failure(IllegalArgumentException("معرف المورد غير صالح | Supplier ID cannot be blank."))
        }
        val idx = suppliers.indexOfFirst { it.id == supplierId }
        if (idx == -1) return Result.failure(NoSuchElementException("المورد غير موجود | Supplier not found: $supplierId"))
        if (totalIls <= 0.0) {
            return Result.failure(IllegalArgumentException("إجمالي الفاتورة يجب أن يكون أكبر من صفر | Bill total must be greater than zero."))
        }
        if (paidNowIls < 0.0) {
            return Result.failure(IllegalArgumentException("المبلغ المدفوع فوراً لا يمكن أن يكون سالباً | Paid-now amount cannot be negative."))
        }
        if (paidNowIls > totalIls) {
            return Result.failure(IllegalArgumentException("المبلغ المدفوع فوراً لا يمكن أن يتجاوز إجمالي الفاتورة | Paid-now amount cannot exceed bill total."))
        }

        val totalExact = BigDecimal(totalIls.toString()).setScale(2, RoundingMode.HALF_UP)
        val paidExact = BigDecimal(paidNowIls.toString()).setScale(2, RoundingMode.HALF_UP)
        val payableAdded = totalExact.subtract(paidExact)

        val nextBillNum = (purchaseBills.maxOfOrNull { it.billNumber } ?: 300) + 1
        val bill = PurchaseBill(
            id = "pb_${System.currentTimeMillis()}_$nextBillNum",
            billNumber = nextBillNum,
            supplierId = supplierId,
            date = WesternFormatters.currentDate(),
            totalIls = totalExact.toDouble(),
            paidIls = paidExact.toDouble(),
            itemsSummary = itemsSummary,
            isReceived = false // Text-only bills require physical itemized audit before marking goods received
        )

        val currentSup = suppliers[idx]
        val newBalance = BigDecimal(currentSup.balanceOwedIls.toString())
            .add(payableAdded)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()

        suppliers[idx] = currentSup.copy(balanceOwedIls = newBalance)
        purchaseBills.add(0, bill)
        return Result.success(bill)
    }

    override fun recordSupplierPayment(
        supplierId: String,
        amountIls: Double,
        note: String,
        operator: UserSession
    ): Result<SupplierPayment> {
        if (!operator.canManageSuppliers()) {
            return Result.failure(SecurityException("غير مصرح: سداد دفعات الموردين متاح للمالك والمدير فقط | Permission denied: Supplier disbursement requires Owner or Manager"))
        }
        if (supplierId.isBlank()) {
            return Result.failure(IllegalArgumentException("معرف المورد غير صالح | Supplier ID cannot be blank."))
        }
        val idx = suppliers.indexOfFirst { it.id == supplierId }
        if (idx == -1) return Result.failure(NoSuchElementException("المورد غير موجود | Supplier not found: $supplierId"))
        if (amountIls <= 0.0) {
            return Result.failure(IllegalArgumentException("مبلغ السداد يجب أن يكون أكبر من صفر | Disbursement amount must be greater than zero."))
        }

        val amountExact = BigDecimal(amountIls.toString()).setScale(2, RoundingMode.HALF_UP)
        val currentSup = suppliers[idx]
        val newOwed = BigDecimal(currentSup.balanceOwedIls.toString())
            .subtract(amountExact)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()

        suppliers[idx] = currentSup.copy(balanceOwedIls = newOwed)

        val payment = SupplierPayment(
            id = "spay_${System.currentTimeMillis()}",
            supplierId = supplierId,
            date = WesternFormatters.currentTimestamp(),
            amountIls = amountExact.toDouble(),
            note = note.ifEmpty { "سداد نقدي من الصندوق" }
        )
        supplierPayments.add(0, payment)
        return Result.success(payment)
    }

    override fun getExpenses(): List<ExpenseEntry> = expenses.toList()

    override fun recordExpense(
        title: String,
        category: ExpenseCategory,
        amountIls: Double,
        operator: UserSession
    ): Result<ExpenseEntry> {
        if (!operator.canManageExpenses()) {
            return Result.failure(SecurityException("غير مصرح: تسجيل المصروفات متاح للمالك والمدير فقط | Permission denied: Expense recording requires Owner or Manager"))
        }
        if (amountIls <= 0.0 || title.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid expense entry details."))
        }
        val exp = ExpenseEntry(
            id = "exp_${System.currentTimeMillis()}",
            date = WesternFormatters.currentDate(),
            titleAr = title.trim(),
            titleEn = title.trim(),
            category = category,
            amountIls = BigDecimal(amountIls.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()
        )
        expenses.add(0, exp)
        return Result.success(exp)
    }
}
