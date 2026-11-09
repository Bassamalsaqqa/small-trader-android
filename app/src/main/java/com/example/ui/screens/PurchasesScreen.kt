package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.PurchaseBill
import com.example.model.Supplier
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
import com.example.ui.components.StitchPrimaryButton
import com.example.ui.components.StitchTonalButton
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

/**
 * Filter categories for Supplier Purchases Ledger (ST-19).
 * All filters genuinely affect the displayed list (no inert chips).
 */
enum class PurchasesFilter {
    ALL,
    AWAITING_RECEIPT,
    OPEN_PAYABLES,
    UNPAID,
    PARTIALLY_PAID,
    RECEIVED,
    TEXT_ONLY
}

/**
 * Native Purchases Screen (ST-19 & ST-23).
 *
 * Implements Google Stitch Canonical Direction (Project 15641450350510420845):
 * - ST-19: Dedicated merchant bill ledger, search, multi-axis filtering, dual-state status
 *          (strictly separated financial paid/partial/credit vs physical receipt status).
 * - ST-23: Bill Detail view dynamically derived from current state.purchaseBills (no stale snapshot),
 *          concise supplier identity, 3-column financial ledger, SKU goods receipt tracking,
 *          and direct PhysicalGoodsReceiptDialog reuse.
 */
@Composable
fun PurchasesScreen(
    state: TraderUiState,
    onNewPurchase: () -> Unit,
    onSupplier: (Supplier) -> Unit,
    onConfirmPhysicalGoodsReceipt: ((String, String, Int, String) -> Boolean)? = null
) {
    if (!state.currentSession.canManageSuppliers()) return
    val lang = state.language

    // Selected bill ID is preserved across orientation/navigation; the bill itself is derived
    // dynamically from state.purchaseBills so receipts immediately reflect without stale snapshots.
    var selectedBillId by rememberSaveable(state.currentSession.id) { mutableStateOf<String?>(null) }
    val currentBill = remember(state.purchaseBills, selectedBillId) {
        state.purchaseBills.firstOrNull { it.id == selectedBillId }
    }

    BackHandler(enabled = selectedBillId != null && currentBill != null) { selectedBillId = null }
    if (selectedBillId != null && currentBill != null) {
        val linkedSupplier = remember(state.suppliers, currentBill.supplierId) {
            state.suppliers.firstOrNull { it.id == currentBill.supplierId }
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { selectedBillId = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
        PurchaseBillDetailView(
            bill = currentBill,
            supplier = linkedSupplier,
            state = state,
            lang = lang,
            onBack = { selectedBillId = null },
            onSupplier = { supplier -> selectedBillId = null; onSupplier(supplier) },
            onConfirmPhysicalGoodsReceipt = onConfirmPhysicalGoodsReceipt
        )
        }
    } else {
        PurchasesListView(
            state = state,
            lang = lang,
            onNewPurchase = onNewPurchase,
            onSelectBill = { bill -> selectedBillId = bill.id }
        )
    }
}

/**
 * ST-19: Purchases Ledger List View.
 */
@Composable
private fun PurchasesListView(
    state: TraderUiState,
    lang: AppLanguage,
    onNewPurchase: () -> Unit,
    onSelectBill: (PurchaseBill) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(PurchasesFilter.ALL) }

    // Dynamic counts reflecting actual data
    val countAll = state.purchaseBills.size
    val countAwaitingReceipt = remember(state.purchaseBills) {
        state.purchaseBills.count { !it.isReceived && it.expectedProductId != null && it.expectedQuantity > 0 }
    }
    val countUnpaid = remember(state.purchaseBills) {
        state.purchaseBills.count { it.paidIls <= 0.005 }
    }
    val countPartiallyPaid = remember(state.purchaseBills) {
        state.purchaseBills.count { it.paidIls > 0.005 && it.remainingPayableIls > 0.005 }
    }
    val countReceived = remember(state.purchaseBills) {
        state.purchaseBills.count { it.isReceived }
    }
    val countTextOnly = remember(state.purchaseBills) {
        state.purchaseBills.count { it.expectedProductId == null || it.expectedQuantity <= 0 }
    }

    val totalOpenPayables = remember(state.purchaseBills) {
        state.purchaseBills.sumOf { it.remainingPayableIls }
    }
    val countOpenPayables = remember(state.purchaseBills) {
        state.purchaseBills.count { it.remainingPayableIls > 0.005 }
    }

    // Active filtering & search logic
    val displayedBills = remember(state.purchaseBills, state.suppliers, searchQuery, selectedFilter) {
        val query = searchQuery.trim()
        state.purchaseBills.filter { bill ->
            val matchesFilter = when (selectedFilter) {
                PurchasesFilter.ALL -> true
                PurchasesFilter.AWAITING_RECEIPT -> !bill.isReceived && bill.expectedProductId != null && bill.expectedQuantity > 0
                PurchasesFilter.OPEN_PAYABLES -> bill.remainingPayableIls > 0.005
                PurchasesFilter.UNPAID -> bill.paidIls <= 0.005
                PurchasesFilter.PARTIALLY_PAID -> bill.paidIls > 0.005 && bill.remainingPayableIls > 0.005
                PurchasesFilter.RECEIVED -> bill.isReceived
                PurchasesFilter.TEXT_ONLY -> bill.expectedProductId == null || bill.expectedQuantity <= 0
            }
            if (!matchesFilter) return@filter false
            if (query.isEmpty()) return@filter true

            val supplier = state.suppliers.firstOrNull { it.id == bill.supplierId }
            bill.itemsSummary.contains(query, ignoreCase = true) ||
                bill.billNumber.toString().contains(query) ||
                bill.date.contains(query) ||
                (supplier?.nameAr?.contains(query, ignoreCase = true) == true) ||
                (supplier?.nameEn?.contains(query, ignoreCase = true) == true) ||
                (supplier?.companyAr?.contains(query, ignoreCase = true) == true) ||
                (supplier?.companyEn?.contains(query, ignoreCase = true) == true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("purchases_screen")
    ) {
        // Sticky Header with Title, Action & Metric Strip
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Row: Title & New Purchase Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lang.tr("فواتير المشتريات والتوريد", "Purchases & Inward Bills"),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lang.tr("سجل فواتير الموردين ومتابعة استلام البضائع", "Supplier bills and physical stock receiving"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = onNewPurchase,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("new_purchase_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("فاتورة جديدة", "New Bill"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                // Metric Summary Strip (Operational bottlenecks & open debt)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Card 1: Awaiting goods receipt bottleneck
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (countAwaitingReceipt > 0) TraderTheme.financial.warningContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (countAwaitingReceipt > 0) TraderTheme.financial.warning.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedFilter = PurchasesFilter.AWAITING_RECEIPT }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = lang.tr("بانتظار الاستلام", "Awaiting Receipt"),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (countAwaitingReceipt > 0) TraderTheme.financial.warning else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = lang.tr(
                                    "${WesternFormatters.formatInt(countAwaitingReceipt)} فاتورة",
                                    "${WesternFormatters.formatInt(countAwaitingReceipt)} bills"
                                ),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (countAwaitingReceipt > 0) TraderTheme.financial.warning else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Card 2: Open supplier payables
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedFilter == PurchasesFilter.OPEN_PAYABLES) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (selectedFilter == PurchasesFilter.OPEN_PAYABLES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedFilter = PurchasesFilter.OPEN_PAYABLES }
                            .testTag("purchases_metric_open_payables")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = lang.tr("الذمم المعلقة", "Open Payables"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = StitchBidiUtils.formatIls(totalOpenPayables),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (totalOpenPayables > 0.005) TraderTheme.financial.debit else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchases_search_input"),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = lang.tr("ابحث برقم الفاتورة، اسم المورد أو بيان البضاعة...", "Search by bill code, supplier or items..."),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = lang.tr("مسح البحث", "Clear search")
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )

                // Filter Chips (Every filter genuinely affects data)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == PurchasesFilter.ALL,
                            onClick = { selectedFilter = PurchasesFilter.ALL },
                            label = { Text(lang.tr("الكل (${WesternFormatters.formatInt(countAll)})", "All (${WesternFormatters.formatInt(countAll)})")) },
                            modifier = Modifier.testTag("purchases_filter_ALL")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == PurchasesFilter.AWAITING_RECEIPT,
                            onClick = { selectedFilter = PurchasesFilter.AWAITING_RECEIPT },
                            label = { Text(lang.tr("بانتظار الاستلام (${WesternFormatters.formatInt(countAwaitingReceipt)})", "Awaiting Receipt (${WesternFormatters.formatInt(countAwaitingReceipt)})")) },
                            modifier = Modifier.testTag("purchases_filter_AWAITING_RECEIPT")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == PurchasesFilter.OPEN_PAYABLES,
                            onClick = { selectedFilter = PurchasesFilter.OPEN_PAYABLES },
                            label = { Text(lang.tr("ذمم معلقة (${WesternFormatters.formatInt(countOpenPayables)})", "Open Payables (${WesternFormatters.formatInt(countOpenPayables)})")) },
                            modifier = Modifier.testTag("purchases_filter_OPEN_PAYABLES")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == PurchasesFilter.UNPAID,
                            onClick = { selectedFilter = PurchasesFilter.UNPAID },
                            label = { Text(lang.tr("غير مدفوعة (${WesternFormatters.formatInt(countUnpaid)})", "Unpaid (${WesternFormatters.formatInt(countUnpaid)})")) },
                            modifier = Modifier.testTag("purchases_filter_UNPAID")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == PurchasesFilter.PARTIALLY_PAID,
                            onClick = { selectedFilter = PurchasesFilter.PARTIALLY_PAID },
                            label = { Text(lang.tr("سداد جزئي (${WesternFormatters.formatInt(countPartiallyPaid)})", "Partially Paid (${WesternFormatters.formatInt(countPartiallyPaid)})")) },
                            modifier = Modifier.testTag("purchases_filter_PARTIALLY_PAID")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == PurchasesFilter.RECEIVED,
                            onClick = { selectedFilter = PurchasesFilter.RECEIVED },
                            label = { Text(lang.tr("مستلمة بالكامل (${WesternFormatters.formatInt(countReceived)})", "Received (${WesternFormatters.formatInt(countReceived)})")) },
                            modifier = Modifier.testTag("purchases_filter_RECEIVED")
                        )
                    }
                    if (countTextOnly > 0) {
                        item {
                            FilterChip(
                                selected = selectedFilter == PurchasesFilter.TEXT_ONLY,
                                onClick = { selectedFilter = PurchasesFilter.TEXT_ONLY },
                                label = { Text(lang.tr("فواتير نصية (${WesternFormatters.formatInt(countTextOnly)})", "Text-Only (${WesternFormatters.formatInt(countTextOnly)})")) },
                                modifier = Modifier.testTag("purchases_filter_TEXT_ONLY")
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        // Scannable Bill Ledger List
        if (displayedBills.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lang.tr(
                            "لا توجد فواتير مطابقة للبحث أو التصفية المحددة",
                            "No purchase bills match the selected filter or search"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (searchQuery.isNotBlank() || selectedFilter != PurchasesFilter.ALL) {
                        TextButton(
                            onClick = {
                                searchQuery = ""
                                selectedFilter = PurchasesFilter.ALL
                            }
                        ) {
                            Text(lang.tr("عرض جميع الفواتير", "Show All Bills"))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedBills, key = { it.id }) { bill ->
                    val supplier = remember(state.suppliers, bill.supplierId) {
                        state.suppliers.firstOrNull { it.id == bill.supplierId }
                    }
                    PurchaseBillLedgerCard(
                        bill = bill,
                        supplier = supplier,
                        lang = lang,
                        onClick = { onSelectBill(bill) }
                    )
                }
            }
        }
    }
}

/**
 * Bill Row Card within Purchases Ledger (ST-19).
 * Features strictly separated physical receipt badge vs financial payment badge.
 */
@Composable
private fun PurchaseBillLedgerCard(
    bill: PurchaseBill,
    supplier: Supplier?,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val supplierName = supplier?.name(lang) ?: lang.tr("مورد تجاري", "Supplier")
    val supplierCompany = supplier?.company(lang) ?: ""

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("purchase_bill_${bill.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Supplier Identity & Total Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Storefront,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = supplierName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (supplierCompany.isNotBlank()) {
                            Text(
                                text = supplierCompany,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = StitchBidiUtils.formatIls(bill.totalIls),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = bill.date,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Alphanumeric Code & Items Summary
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("رقم الفاتورة:", "Bill Number:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = StitchBidiUtils.formatBillCode(bill.billNumber),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = MonoFontFamily
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = bill.itemsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Dual-State Separated Badges
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 1. Physical Goods Receipt Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (physicalText, physicalTone) = when {
                        bill.isReceived -> lang.tr(
                            "تم استلام البضاعة بالكامل${if (bill.expectedQuantity > 0) " (${WesternFormatters.formatInt(bill.expectedQuantity)})" else ""}",
                            "Goods fully received${if (bill.expectedQuantity > 0) " (${WesternFormatters.formatInt(bill.expectedQuantity)})" else ""}"
                        ) to StitchBadgeTone.SUCCESS
                        bill.expectedProductId != null && bill.expectedQuantity > 0 -> lang.tr(
                            "بانتظار استلام البضاعة (${WesternFormatters.formatInt(bill.expectedQuantity)})",
                            "Awaiting stock receipt (${WesternFormatters.formatInt(bill.expectedQuantity)})"
                        ) to StitchBadgeTone.WARNING
                        else -> lang.tr(
                            "فاتورة نصية • استلام المخزون غير متاح",
                            "Text bill • stock receiving unavailable"
                        ) to StitchBadgeTone.NEUTRAL
                    }
                    StitchBadge(
                        text = physicalText,
                        tone = physicalTone,
                        shape = RoundedCornerShape(6.dp)
                    )
                }

                // 2. Financial Payable Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (financialText, financialTone) = when {
                        bill.remainingPayableIls <= 0.005 -> lang.tr("مدفوعة بالكامل", "Fully Paid") to StitchBadgeTone.SUCCESS
                        bill.paidIls > 0.005 -> lang.tr(
                            "سداد جزئي (مدفوع ${StitchBidiUtils.formatIls(bill.paidIls)} • متبقي ${StitchBidiUtils.formatIls(bill.remainingPayableIls)})",
                            "Partial (Paid ${StitchBidiUtils.formatIls(bill.paidIls)} • Rem: ${StitchBidiUtils.formatIls(bill.remainingPayableIls)})"
                        ) to StitchBadgeTone.PRIMARY
                        else -> lang.tr(
                            "غير مدفوعة (ذمة: ${StitchBidiUtils.formatIls(bill.remainingPayableIls)})",
                            "Unpaid (Due: ${StitchBidiUtils.formatIls(bill.remainingPayableIls)})"
                        ) to StitchBadgeTone.DANGER
                    }
                    StitchBadge(
                        text = financialText,
                        tone = financialTone,
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Footer navigation hint
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr("اضغط لعرض السند وحركة الاستلام", "Tap to view bill & stock receiving"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = lang.tr("تفاصيل الفاتورة ←", "Bill Details →"),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * ST-23: Purchase Bill Detail Screen.
 *
 * Provides deep operational inspection for a specific bill:
 * - Dynamic derivation from current state.purchaseBills (no stale snapshot).
 * - Supplier contact and balance context.
 * - 3-column financial ledger grid.
 * - Single-SKU physical receipt status (or concise financial badge for text-only bills).
 * - Direct PhysicalGoodsReceiptDialog reuse.
 */
@Composable
private fun PurchaseBillDetailView(
    bill: PurchaseBill,
    supplier: Supplier?,
    state: TraderUiState,
    lang: AppLanguage,
    onBack: () -> Unit,
    onSupplier: (Supplier) -> Unit,
    onConfirmPhysicalGoodsReceipt: ((String, String, Int, String) -> Boolean)?
) {
    var isGrnDialogOpen by rememberSaveable { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val linkedProduct = remember(state.products, bill.expectedProductId) {
        state.products.firstOrNull { it.id == bill.expectedProductId }
    }
    val hasLinkedSku = bill.expectedProductId != null && bill.expectedQuantity > 0
    val canReceiveGoods = !bill.isReceived && hasLinkedSku && state.currentSession.canManageInventory() && onConfirmPhysicalGoodsReceipt != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("purchase_bill_detail_${bill.id}")
    ) {
        // Sticky Top App Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("back_from_bill_detail_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = lang.tr("رجوع", "Back")
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = StitchBidiUtils.formatBillCode(bill.billNumber, lang.tr("فاتورة شراء", "Purchase Bill")),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = bill.date,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        // Scrollable Detail Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val feedback = lang.tr(state.lastSupplierActionMessageAr.orEmpty(), state.lastSupplierActionMessageEn.orEmpty())
            if (feedback.isNotBlank()) {
                Text(
                    text = feedback,
                    color = if (state.isSupplierActionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("purchase_receipt_feedback")
                )
            }
            // 1. Supplier Profile Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("بيانات المورد التجاري", "Supplier Information"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = supplier?.name(lang) ?: lang.tr("مورد تجاري غير مقيد", "Unlinked Supplier"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!supplier?.company(lang).isNullOrBlank()) {
                                Text(
                                    text = supplier?.company(lang) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!supplier?.phone.isNullOrBlank()) {
                            Surface(
                                onClick = {
                                    val phone = supplier?.phone.orEmpty()
                                    if (phone.isNotBlank()) context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.fromParts("tel", phone, null)))
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = lang.tr("اتصال بالمورد", "Call supplier"),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("رصيد حساب المورد الإجمالي:", "Supplier Total Account Balance:"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = StitchBidiUtils.formatIls(supplier?.balanceOwedIls ?: bill.remainingPayableIls),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if ((supplier?.balanceOwedIls ?: 0.0) > 0.005) TraderTheme.financial.debit else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 2. Financial Payable Section & 3-Column Bento Grid
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("الذمة المالية للفاتورة", "Financial Payable Breakdown"),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val (pillText, pillTone) = when {
                            bill.remainingPayableIls <= 0.005 -> lang.tr("مدفوعة بالكامل", "Paid") to StitchBadgeTone.SUCCESS
                            bill.paidIls > 0.005 -> lang.tr("سداد جزئي", "Partial") to StitchBadgeTone.PRIMARY
                            else -> lang.tr("غير مدفوعة", "Unpaid") to StitchBadgeTone.DANGER
                        }
                        StitchBadge(text = pillText, tone = pillTone)
                    }

                    // 3-Column Bento Grid
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Total
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = lang.tr("قيمة الفاتورة", "Total Bill"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(bill.totalIls),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Paid
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = lang.tr("المدفوع حالياً", "Paid Now"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(bill.paidIls),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (bill.paidIls > 0.005) TraderTheme.financial.income else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Remaining
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (bill.remainingPayableIls > 0.005) TraderTheme.financial.debitContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (bill.remainingPayableIls > 0.005) TraderTheme.financial.debit.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = lang.tr("ذمة معلقة", "Remaining Due"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (bill.remainingPayableIls > 0.005) TraderTheme.financial.debit else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(bill.remainingPayableIls),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (bill.remainingPayableIls > 0.005) TraderTheme.financial.debit else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Account type footer
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr("نوع التعامل المحاسبي", "Accounting Category"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (bill.remainingPayableIls > 0.005) {
                                    lang.tr("أجل (ذمم دائنة للمورد)", "Credit (Supplier Payable)")
                                } else {
                                    lang.tr("نقدي مسدد بالكامل", "Cash (Fully Settled)")
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // 3. Physical Goods Receipt (GRN) Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("حركة الاستلام المستودعي", "Physical Stock Receipt"),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val (receiptBadgeText, receiptBadgeTone) = when {
                            bill.isReceived -> lang.tr("تم الاستلام بالكامل", "Received") to StitchBadgeTone.SUCCESS
                            hasLinkedSku -> lang.tr("بانتظار الاستلام", "Awaiting") to StitchBadgeTone.WARNING
                            else -> lang.tr("فاتورة مالية نصية", "Text-Only") to StitchBadgeTone.NEUTRAL
                        }
                        StitchBadge(text = receiptBadgeText, tone = receiptBadgeTone)
                    }

                    if (hasLinkedSku) {
                        // Linked Product Identity
                        val productName = linkedProduct?.name(lang) ?: bill.itemsSummary
                        val productUnit = linkedProduct?.unit(lang) ?: ""
                        val productBarcode = linkedProduct?.barcode ?: ""

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = lang.tr("الصنف المرتبط المؤهل للاستلام بالمستودع:", "Linked Product Eligible for Receiving:"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = productName,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (productBarcode.isNotBlank()) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = "${lang.tr("الباركود:", "Barcode:")} $productBarcode",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Quantity & Cost Box
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = lang.tr("الكمية المتوقعة للإدخال:", "Expected Receiving Qty:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${WesternFormatters.formatInt(bill.expectedQuantity)} $productUnit",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = lang.tr("إجمالي تكلفة الشحنة:", "Total Bill Cost:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = StitchBidiUtils.formatIls(bill.totalIls),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Warehouse Delivery State Highlight
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (bill.isReceived) TraderTheme.financial.incomeContainer.copy(alpha = 0.5f) else TraderTheme.financial.warningContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (bill.isReceived) TraderTheme.financial.income.copy(alpha = 0.4f) else TraderTheme.financial.warning.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (bill.isReceived) Icons.Default.CheckCircle else Icons.Default.PendingActions,
                                    contentDescription = null,
                                    tint = if (bill.isReceived) TraderTheme.financial.income else TraderTheme.financial.warning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = if (bill.isReceived) {
                                            lang.tr("تم الاستلام المخزني الفعلي وتحديث الرصيد", "Physically received into inventory")
                                        } else {
                                            lang.tr("بانتظار الاستلام المخزني الفعلي", "Awaiting physical warehouse receipt")
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (bill.isReceived) TraderTheme.financial.income else TraderTheme.financial.warning
                                    )
                                }
                            }
                        }
                    } else {
                        // Concise note for financial-only bills (no lecture, no fake receive button)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lang.tr(
                                    "قيد مالي عام غير خاضع للاستلام المخزني.",
                                    "General financial record, no physical stock receiving."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Fixed Mobile Action Dock
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary Action: Receive Goods (Only if eligible, linked, unreceived, and user authorized)
                if (canReceiveGoods) {
                    val unitLabel = linkedProduct?.unit(lang) ?: ""
                    Button(
                        onClick = { isGrnDialogOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("receive_goods_button")
                    ) {
                        Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr(
                                "استلام البضاعة في المخزون (${WesternFormatters.formatInt(bill.expectedQuantity)} $unitLabel)",
                                "Receive Stock in Warehouse (${WesternFormatters.formatInt(bill.expectedQuantity)} $unitLabel)"
                            ),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                // Secondary Action: Pay Supplier / Open Supplier Account
                if (supplier != null) {
                    OutlinedButton(
                        onClick = { onSupplier(supplier) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("pay_supplier_button")
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr("حساب المورد / تسجيل دفعة", "Supplier Account / Record Payment"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }

    // Direct PhysicalGoodsReceiptDialog reuse via internal visibility in SuppliersScreen
    if (isGrnDialogOpen && hasLinkedSku) {
        PhysicalGoodsReceiptDialog(
            bill = bill,
            products = state.products,
            lang = lang,
            onDismiss = { isGrnDialogOpen = false },
            errorMessage = if (state.isSupplierActionError) lang.tr(state.lastSupplierActionMessageAr.orEmpty(), state.lastSupplierActionMessageEn.orEmpty()).takeIf { it.isNotBlank() } else null,
            onConfirmGrn = { productId, receivedQty, batchNote ->
                val success = onConfirmPhysicalGoodsReceipt?.invoke(bill.id, productId, receivedQty, batchNote) == true
                if (success) {
                    isGrnDialogOpen = false
                }
                success
            }
        )
    }
}
