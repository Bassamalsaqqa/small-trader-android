package com.example.data.mobile.merchantread

import com.example.data.mobile.contract.ServerUlid
import com.example.model.CustomerTransaction
import com.example.model.InvoiceRecord
import com.example.model.StockMovement
import java.math.BigDecimal
import java.util.Collections
import java.util.Locale

/**
 * Minimal typed projections and read model adapters for Mobile V1 Merchant Reads.
 *
 * Design Invariants:
 * 1. Zero Double bridges: All currency and quantity numbers are preserved as exact [BigDecimal].
 * 2. Truthful nulls: Missing permissions or uncomputed metrics remain null; zeroes are NEVER invented.
 * 3. No synthetic data: Basket lines, fake customer names, synthetic payment IDs, or hardcoded currencies
 *    are never fabricated when server index payloads lack them.
 * 4. Merging integrity: Preserves local outbox basket items and client event IDs while binding
 *    authoritative server ULIDs, invoice numbers, and posted settlement states.
 */

data class LiveInvoiceRecord(
    val id: String,
    val invoiceNumber: String,
    val status: String,
    val paymentStatus: String,
    val customerId: String,
    val customerName: String?,
    val date: String,
    val createdAt: String?,
    val subtotalExact: BigDecimal,
    val discountExact: BigDecimal,
    val taxExact: BigDecimal,
    val totalExact: BigDecimal,
    val paidExact: BigDecimal,
    val remainingDebitExact: BigDecimal,
    val currency: String,
    val currencyMinorUnits: Int,
    val lines: List<SalesInvoiceDetailLine> = emptyList(),
    val thermalReceipt: ThermalReceiptData? = null,
    val clientEventId: String? = null,
    val serverInvoiceUlid: String? = null,
    val outboxStatus: String? = null
) {
    val isPaidInFull: Boolean get() = remainingDebitExact.compareTo(BigDecimal.ZERO) <= 0
    val isCreditSale: Boolean get() = paymentStatus == "ON_CREDIT" || paidExact.compareTo(BigDecimal.ZERO) <= 0
    val isFullCashSale: Boolean get() = paymentStatus == "PAID_CASH"

    override fun toString(): String =
        "LiveInvoiceRecord(id='$id', invoiceNumber='$invoiceNumber', status='$status', paymentStatus='$paymentStatus', customerId='$customerId', customerName='[REDACTED]', date='$date', totalExact=$totalExact, paidExact=$paidExact, remainingDebitExact=$remainingDebitExact, currency='$currency', outboxStatus=$outboxStatus)"
}

data class LiveHomeMetrics(
    val today: String,
    val salesTodayCount: Int?,
    val salesTodayTotal: BigDecimal?,
    val cashInDrawer: BigDecimal?,
    val lowStockCount: Int?,
    val totalReceivables: BigDecimal?,
    val totalPayables: BigDecimal?,
    val recentInvoices: List<HomeRecentInvoice>,
    val businessInfo: HomeBusinessInfo
) {
    val isFinancialsAuthorized: Boolean
        get() = salesTodayTotal != null || cashInDrawer != null || totalReceivables != null || totalPayables != null
}

data class LiveShiftSummary(
    val shiftDate: String,
    val currency: String,
    val currencyMinorUnits: Int,
    val openingFloat: BigDecimal?,
    val cashSales: BigDecimal,
    val cashCollections: BigDecimal,
    val cashDisbursements: BigDecimal,
    val operatingExpenses: BigDecimal,
    val expectedCash: BigDecimal?,
    val salesCount: Int
)

data class LiveTreasurySummary(
    val cashAccounts: List<TreasuryAccountItem>,
    val bankAccounts: List<TreasuryAccountItem>,
    val totalCashBase: BigDecimal,
    val totalBankBase: BigDecimal
)

data class LiveCustomerStatement(
    val customerId: String,
    val customerName: String,
    val fromDate: String?,
    val toDate: String?,
    val currencies: Map<String, CustomerStatementCurrencySummary>
) {
    fun defaultCurrencySummary(preferredCurrency: String): CustomerStatementCurrencySummary? =
        currencies[preferredCurrency] ?: currencies.values.firstOrNull()
}

data class LiveStockMovementHistory(
    val productId: String,
    val movements: List<StockMovementItem>
)

object MerchantReadAdapter {

    /**
     * Maps a server [SalesInvoiceListItem] to an existing domain [InvoiceRecord],
     * optionally merging with a matching local outbox [localMatch] without synthesizing fake lines.
     */
    fun toDomainInvoiceRecord(
        serverItem: SalesInvoiceListItem,
        localMatch: InvoiceRecord? = null
    ): InvoiceRecord {
        val invoiceNumberInt = serverItem.invoiceNumber.filter { it.isDigit() }.toIntOrNull()
            ?: localMatch?.invoiceNumber
            ?: 0

        return InvoiceRecord(
            id = serverItem.id.value,
            invoiceNumber = invoiceNumberInt,
            timestamp = serverItem.createdAt ?: serverItem.date,
            items = localMatch?.items ?: emptyList(), // Never synthesize fake items
            subtotalExact = serverItem.subtotal,
            discountExact = serverItem.discount,
            netTotalExact = serverItem.total,
            cashReceivedExact = localMatch?.takeIf { it.hasLocalTenderDetails }?.cashReceivedExact ?: serverItem.paid,
            changeReturnedExact = localMatch?.changeReturnedExact ?: BigDecimal.ZERO,
            customerId = serverItem.customerId.value,
            customerNote = localMatch?.customerNote ?: "",
            cashierNameAr = localMatch?.cashierNameAr ?: "",
            cashierNameEn = localMatch?.cashierNameEn ?: "",
            isPaidInFull = serverItem.isPaidInFull,
            remainingDebitExact = serverItem.remaining,
            printCount = localMatch?.printCount ?: 0,
            clientEventId = localMatch?.clientEventId,
            serverInvoiceUlid = serverItem.id.value,
            serverInvoiceNumber = serverItem.invoiceNumber,
            serverPaymentUlid = localMatch?.serverPaymentUlid,
            serverPaymentReceiptNumber = localMatch?.serverPaymentReceiptNumber,
            serverPostedAt = serverItem.createdAt,
            outboxStatus = "CONFIRMED",
            hasLocalTenderDetails = localMatch?.hasLocalTenderDetails == true
        )
    }

    /**
     * Maps a server [SalesInvoiceListItem] to a typed [LiveInvoiceRecord].
     */
    fun toLiveInvoiceRecord(
        serverItem: SalesInvoiceListItem,
        localMatch: InvoiceRecord? = null
    ): LiveInvoiceRecord {
        return LiveInvoiceRecord(
            id = serverItem.id.value,
            invoiceNumber = serverItem.invoiceNumber,
            status = serverItem.status,
            paymentStatus = serverItem.paymentStatus,
            customerId = serverItem.customerId.value,
            customerName = serverItem.customerName,
            date = serverItem.date,
            createdAt = serverItem.createdAt,
            subtotalExact = serverItem.subtotal,
            discountExact = serverItem.discount,
            taxExact = serverItem.tax,
            totalExact = serverItem.total,
            paidExact = serverItem.paid,
            remainingDebitExact = serverItem.remaining,
            currency = serverItem.currency,
            currencyMinorUnits = serverItem.currencyMinorUnits,
            lines = emptyList(),
            thermalReceipt = null,
            clientEventId = localMatch?.clientEventId,
            serverInvoiceUlid = serverItem.id.value,
            outboxStatus = "CONFIRMED"
        )
    }

    /**
     * Maps a detailed server invoice [SalesInvoiceDetailResponse] to a typed [LiveInvoiceRecord].
     */
    fun toLiveInvoiceRecord(
        detail: SalesInvoiceDetailResponse,
        localMatch: InvoiceRecord? = null
    ): LiveInvoiceRecord {
        return LiveInvoiceRecord(
            id = detail.id.value,
            invoiceNumber = detail.invoiceNumber,
            status = detail.status,
            paymentStatus = detail.paymentStatus,
            customerId = detail.customerId.value,
            customerName = detail.customerName,
            date = detail.date,
            createdAt = null,
            subtotalExact = detail.subtotal,
            discountExact = detail.discount,
            taxExact = detail.tax,
            totalExact = detail.total,
            paidExact = detail.paid,
            remainingDebitExact = detail.remaining,
            currency = detail.currency,
            currencyMinorUnits = detail.currencyMinorUnits,
            lines = detail.lines,
            thermalReceipt = detail.thermalReceipt,
            clientEventId = localMatch?.clientEventId,
            serverInvoiceUlid = detail.id.value,
            outboxStatus = "CONFIRMED"
        )
    }

    /**
     * Maps a server [HomeResponse] to [LiveHomeMetrics], preserving exact nulls for unauthorized values.
     */
    fun toLiveHomeMetrics(response: HomeResponse): LiveHomeMetrics {
        return LiveHomeMetrics(
            today = response.today,
            salesTodayCount = response.salesTodayCount,
            salesTodayTotal = response.salesTodayTotal,
            cashInDrawer = response.cashInDrawer,
            lowStockCount = response.lowStockCount,
            totalReceivables = response.totalReceivables,
            totalPayables = response.totalPayables,
            recentInvoices = response.recentInvoices,
            businessInfo = response.businessInfo
        )
    }

    /**
     * Maps a server [ShiftSummaryReportResponse] to [LiveShiftSummary].
     */
    fun toLiveShiftSummary(response: ShiftSummaryReportResponse): LiveShiftSummary {
        return LiveShiftSummary(
            shiftDate = response.shiftDate,
            currency = response.currency,
            currencyMinorUnits = response.currencyMinorUnits,
            openingFloat = response.openingFloat,
            cashSales = response.cashSales,
            cashCollections = response.cashCollections,
            cashDisbursements = response.cashDisbursements,
            operatingExpenses = response.operatingExpenses,
            expectedCash = response.expectedCash,
            salesCount = response.salesCount
        )
    }

    /**
     * Maps a server [TreasuryReportResponse] to [LiveTreasurySummary] computing active base totals.
     */
    fun toLiveTreasurySummary(response: TreasuryReportResponse): LiveTreasurySummary {
        val totalCash = response.cashAccounts
            .filter { it.isActive }
            .fold(BigDecimal.ZERO) { acc, it -> acc.add(it.balanceBase) }
        val totalBank = response.bankAccounts
            .filter { it.isActive }
            .fold(BigDecimal.ZERO) { acc, it -> acc.add(it.balanceBase) }

        return LiveTreasurySummary(
            cashAccounts = response.cashAccounts,
            bankAccounts = response.bankAccounts,
            totalCashBase = totalCash,
            totalBankBase = totalBank
        )
    }

    /**
     * Maps a server [CustomerStatementEntry] to domain [CustomerTransaction].
     */
    fun toDomainCustomerTransaction(
        entry: CustomerStatementEntry,
        customerId: String
    ): CustomerTransaction {
        val typeLower = entry.type.lowercase(Locale.ROOT)
        val typeAr = when (typeLower) {
            "invoice" -> "فاتورة مبيعات"
            "payment" -> "سداد دفعة نقداً"
            "return" -> "مرتجع مبيعات"
            else -> entry.type
        }
        val invoiceNumberInt = entry.number.filter { it.isDigit() }.toIntOrNull()

        return CustomerTransaction(
            id = entry.reference ?: entry.number,
            customerId = customerId,
            date = entry.date,
            typeAr = typeAr,
            typeEn = entry.type,
            invoiceNumber = invoiceNumberInt,
            debitExact = entry.debit,
            creditExact = entry.credit,
            runningBalanceExact = entry.balance,
            note = entry.description
        )
    }

    /**
     * Maps a server [StockMovementItem] to domain [StockMovement].
     */
    fun toDomainStockMovement(
        item: StockMovementItem,
        productId: String
    ): StockMovement {
        val movementTypeLower = item.movementType.lowercase(Locale.ROOT)
        val reasonAr = when (movementTypeLower) {
            "sale" -> "فاتورة بيع"
            "manual_adjustment", "adjustment" -> "تسوية مخزنية"
            "purchase_receipt", "purchase" -> "استلام بضاعة (GRN)"
            "return" -> "مرتجع"
            else -> if (item.reason.isNotBlank()) item.reason else item.movementType
        }

        return StockMovement(
            id = item.id.value,
            productId = productId,
            date = item.date,
            deltaExact = item.delta,
            reasonAr = reasonAr,
            reasonEn = item.movementType,
            resultingStockExact = item.resultingStock
        )
    }
}
