package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.PurchaseBill
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

private enum class SupplierBalanceFilter {
    ALL,
    PAYABLES,
    ADVANCES,
    SETTLED
}

@Composable
fun SuppliersScreen(
    state: TraderUiState,
    onSupplierClick: (Supplier) -> Unit,
    onOpenReceivePurchaseDialog: () -> Unit,
    onDismissFeedback: (() -> Unit)? = null,
    onSaveSupplier: ((supplierId: String?, name: String, company: String, phone: String) -> Unit)? = null
) {
    val lang = state.language
    val canManageSuppliers = state.currentSession.canManageSuppliers()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(SupplierBalanceFilter.ALL) }
    var isAddSupplierOpen by remember { mutableStateOf(false) }

    val payablesCount = remember(state.suppliers) { state.suppliers.count { it.balanceOwedIls > 0.005 } }
    val advancesCount = remember(state.suppliers) { state.suppliers.count { it.balanceOwedIls < -0.005 } }
    val settledCount = remember(state.suppliers) { state.suppliers.count { abs(it.balanceOwedIls) <= 0.005 } }

    val filteredSuppliers = remember(state.suppliers, searchQuery, selectedFilter) {
        val q = TraderUiState.normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
        state.suppliers.filter { s ->
            val matchesFilter = when (selectedFilter) {
                SupplierBalanceFilter.ALL -> true
                SupplierBalanceFilter.PAYABLES -> s.balanceOwedIls > 0.005
                SupplierBalanceFilter.ADVANCES -> s.balanceOwedIls < -0.005
                SupplierBalanceFilter.SETTLED -> abs(s.balanceOwedIls) <= 0.005
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true

            TraderUiState.normalizeForSearch(s.nameAr).contains(q) ||
                TraderUiState.normalizeForSearch(s.nameEn).contains(q) ||
                TraderUiState.normalizeForSearch(s.companyAr).contains(q) ||
                TraderUiState.normalizeForSearch(s.companyEn).contains(q) ||
                s.phone.replace("-", "").contains(q.replace("-", "")) ||
                s.phone.contains(q)
        }
    }

    val feedbackMessage = lang.tr(
        state.lastSupplierActionMessageAr ?: "",
        state.lastSupplierActionMessageEn ?: ""
    ).takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("suppliers_screen")
    ) {
        // Sticky Summary, Search & Balance Filter Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Payables vs Prepayments Summary Strip + New Financial Bill & New Supplier CTAs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = lang.tr("علينا للموردين (ذمم)", "We Owe Suppliers"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.totalSupplierPayablesIls),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TraderTheme.financial.debit,
                                modifier = Modifier.testTag("total_supplier_payables_value")
                            )
                        }

                        Column {
                            Text(
                                text = lang.tr("لنا عند الموردين (مقدم)", "Supplier Advances"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.totalSupplierAdvancesIls),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (state.totalSupplierAdvancesIls > 0.005) {
                                    TraderTheme.financial.income
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.testTag("total_supplier_advances_value")
                            )
                        }
                    }

                    if (canManageSuppliers) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (onSaveSupplier != null) {
                                OutlinedButton(
                                    onClick = { isAddSupplierOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.testTag("add_supplier_button")
                                ) {
                                    Text(
                                        text = lang.tr("+ مورد", "+ Vendor"),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Button(
                                onClick = onOpenReceivePurchaseDialog,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("record_purchase_bill_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = lang.tr("فاتورة شراء", "New Bill"),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                if (feedbackMessage != null) {
                    val isError = state.isSupplierActionError
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            TraderTheme.financial.incomeContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            else TraderTheme.financial.income.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier_action_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else TraderTheme.financial.income,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else TraderTheme.financial.income
                                )
                            }
                            if (onDismissFeedback != null) {
                                IconButton(
                                    onClick = onDismissFeedback,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = lang.tr("إغلاق", "Dismiss"),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Instant Search by Supplier Name, Company, or Phone
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_search_input"),
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "ابحث باسم المورد، الشركة أو رقم الهاتف...",
                                "Search supplier name, company, or phone..."
                            ),
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
                                    contentDescription = lang.tr("مسح", "Clear")
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                // Balance Filter Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == SupplierBalanceFilter.ALL,
                            onClick = { selectedFilter = SupplierBalanceFilter.ALL },
                            label = {
                                Text(
                                    lang.tr(
                                        "الكل (${WesternFormatters.formatInt(state.suppliers.size)})",
                                        "All (${WesternFormatters.formatInt(state.suppliers.size)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("supplier_filter_ALL")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == SupplierBalanceFilter.PAYABLES,
                            onClick = { selectedFilter = SupplierBalanceFilter.PAYABLES },
                            label = {
                                Text(
                                    lang.tr(
                                        "لهم ذمة علينا (${WesternFormatters.formatInt(payablesCount)})",
                                        "We Owe (${WesternFormatters.formatInt(payablesCount)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("supplier_filter_PAYABLES")
                        )
                    }
                    if (advancesCount > 0) {
                        item {
                            FilterChip(
                                selected = selectedFilter == SupplierBalanceFilter.ADVANCES,
                                onClick = { selectedFilter = SupplierBalanceFilter.ADVANCES },
                                label = {
                                    Text(
                                        lang.tr(
                                            "لنا دفعة مقدمة (${WesternFormatters.formatInt(advancesCount)})",
                                            "Store Advance (${WesternFormatters.formatInt(advancesCount)})"
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("supplier_filter_ADVANCES")
                            )
                        }
                    }
                    if (settledCount > 0) {
                        item {
                            FilterChip(
                                selected = selectedFilter == SupplierBalanceFilter.SETTLED,
                                onClick = { selectedFilter = SupplierBalanceFilter.SETTLED },
                                label = {
                                    Text(
                                        lang.tr(
                                            "خالص (${WesternFormatters.formatInt(settledCount)})",
                                            "Settled (${WesternFormatters.formatInt(settledCount)})"
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("supplier_filter_SETTLED")
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        if (filteredSuppliers.isEmpty()) {
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
                    Icon(
                        imageVector = Icons.Default.Store,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = lang.tr("لا يوجد موردون مطابقون للبحث", "No matching suppliers found"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSuppliers, key = { it.id }) { supplier ->
                    SupplierDirectoryRow(
                        supplier = supplier,
                        lang = lang,
                        onClick = { onSupplierClick(supplier) }
                    )
                }
            }
        }
    }

    if (isAddSupplierOpen && onSaveSupplier != null) {
        SupplierEditorDialog(
            initialSupplier = null,
            lang = lang,
            onDismiss = { isAddSupplierOpen = false },
            onConfirmSave = { sid, name, company, phone ->
                onSaveSupplier(sid, name, company, phone)
                isAddSupplierOpen = false
            }
        )
    }
}

@Composable
private fun SupplierDirectoryRow(
    supplier: Supplier,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val isPayableOwed = supplier.balanceOwedIls > 0.005
    val isStoreAdvance = supplier.balanceOwedIls < -0.005

    val amountColor = when {
        isPayableOwed || isStoreAdvance -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusTextColor = when {
        isPayableOwed -> TraderTheme.financial.onDebitContainer
        isStoreAdvance -> TraderTheme.financial.onIncomeContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val badgeBg = when {
        isPayableOwed -> TraderTheme.financial.debitContainer
        isStoreAdvance -> TraderTheme.financial.incomeContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val statusLabel = when {
        isPayableOwed -> lang.tr("مستحق له", "We Owe")
        isStoreAdvance -> lang.tr("دفعة مقدمة لنا", "Store Advance")
        else -> lang.tr("خالص", "Settled")
    }

    val displayedAmount = when {
        isPayableOwed -> WesternFormatters.formatIls(supplier.balanceOwedIls)
        isStoreAdvance -> WesternFormatters.formatIls(-supplier.balanceOwedIls)
        else -> WesternFormatters.formatIls(0.0)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supplier_row_${supplier.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = supplier.name(lang),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = supplier.company(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = supplier.phone,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = displayedAmount,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = amountColor
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusTextColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SupplierDetailScreen(
    state: TraderUiState,
    onBack: () -> Unit,
    onPaySupplier: (Supplier, Double) -> Unit,
    onOpenReceivePurchaseDialog: () -> Unit,
    onDismissFeedback: (() -> Unit)? = null,
    onSaveSupplier: ((supplierId: String?, name: String, company: String, phone: String) -> Unit)? = null,
    onConfirmGoodsReceipt: ((billId: String, productId: String, receivedQty: Int, batchNote: String) -> Unit)? = null
) {
    val lang = state.language
    val supplier = state.selectedSupplierForDetail ?: return
    val canManageSuppliers = state.currentSession.canManageSuppliers()

    val bills = remember(state.purchaseBills, supplier.id) {
        state.purchaseBills.filter { it.supplierId == supplier.id }
    }
    val payments = remember(state.supplierPayments, supplier.id) {
        state.supplierPayments.filter { it.supplierId == supplier.id }
    }

    var isPayDialogOpen by remember(supplier.id) { mutableStateOf(false) }
    var isEditSupplierOpen by remember(supplier.id) { mutableStateOf(false) }
    var isPurchaseReturnPreviewOpen by remember(supplier.id) { mutableStateOf(false) }
    var activeBillForGrn by remember(supplier.id) { mutableStateOf<PurchaseBill?>(null) }

    val isPayableOwed = supplier.balanceOwedIls > 0.005
    val isStoreAdvance = supplier.balanceOwedIls < -0.005

    val feedbackMessage = lang.tr(
        state.lastSupplierActionMessageAr ?: "",
        state.lastSupplierActionMessageEn ?: ""
    ).takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("supplier_detail_screen")
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (feedbackMessage != null) {
                item {
                    val isError = state.isSupplierActionError
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            TraderTheme.financial.incomeContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            else TraderTheme.financial.income.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier_detail_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else TraderTheme.financial.income,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else TraderTheme.financial.income
                                )
                            }
                            if (onDismissFeedback != null) {
                                IconButton(
                                    onClick = onDismissFeedback,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = lang.tr("إغلاق", "Dismiss"),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Supplier Identity, Payable/Advance Status & Actions Card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = supplier.name(lang),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = supplier.company(lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = supplier.phone,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Clear Balance Status Banner (We Owe vs Store Advance vs Settled)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isPayableOwed -> TraderTheme.financial.debitContainer
                                isStoreAdvance -> TraderTheme.financial.incomeContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isPayableOwed -> lang.tr("علينا للمورد (التزام مستحق)", "We Owe Supplier (Outstanding Payable)")
                                            isStoreAdvance -> lang.tr("لنا عند المورد (دفعة مقدمة)", "Store Advance Credit")
                                            else -> lang.tr("حالة حساب المورد", "Supplier Account Status")
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = when {
                                            isPayableOwed -> TraderTheme.financial.debit
                                            isStoreAdvance -> TraderTheme.financial.income
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        text = when {
                                            isPayableOwed -> lang.tr(
                                                "المبلغ المتبقي للمورد من فواتير المشتريات المسجلة",
                                                "Remaining payable from recorded purchase bills"
                                            )
                                            isStoreAdvance -> lang.tr(
                                                "رصيد مدفوع مقدماً لصالح المحل يخصم من الفواتير القادمة",
                                                "Prepaid balance in store's favor for upcoming bills"
                                            )
                                            else -> lang.tr(
                                                "لا توجد التزامات متبقية للمورد حالياً",
                                                "No outstanding payable owed to this supplier"
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = when {
                                            isPayableOwed -> WesternFormatters.formatIls(supplier.balanceOwedIls)
                                            isStoreAdvance -> WesternFormatters.formatIls(-supplier.balanceOwedIls)
                                            else -> WesternFormatters.formatIls(0.0)
                                        },
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = when {
                                            isPayableOwed -> TraderTheme.financial.debit
                                            isStoreAdvance -> TraderTheme.financial.income
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.testTag("supplier_detail_balance_value")
                                    )
                                    Text(
                                        text = when {
                                            isPayableOwed -> lang.tr("مستحق له", "We Owe")
                                            isStoreAdvance -> lang.tr("رصيد لنا", "Advance")
                                            else -> lang.tr("خالص", "Settled")
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = when {
                                            isPayableOwed -> TraderTheme.financial.debit
                                            isStoreAdvance -> TraderTheme.financial.income
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }

                        if (canManageSuppliers) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { isPayDialogOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_pay_supplier_dialog_button")
                                ) {
                                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = lang.tr("سداد دفعة للمورد", "Pay Supplier"),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                OutlinedButton(
                                    onClick = onOpenReceivePurchaseDialog,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("supplier_new_bill_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = lang.tr("فاتورة شراء جديدة", "New Bill"),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (onSaveSupplier != null) {
                                    OutlinedButton(
                                        onClick = { isEditSupplierOpen = true },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("edit_supplier_button")
                                    ) {
                                        Text(
                                            text = lang.tr("تعديل بيانات المورد", "Edit Supplier"),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { isPurchaseReturnPreviewOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("open_purchase_return_preview_button")
                                ) {
                                    Text(
                                        text = lang.tr("معاينة مرتجع مشتريات", "Purchase Return Preview"),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = lang.tr(
                                        "دور الكاشير: عرض حساب المورد فقط. قيد فواتير الشراء وسداد الموردين مخصص للمالك والمدير.",
                                        "Cashier role: View-only. Recording purchase bills and supplier payments requires Owner or Manager."
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

            // 2. Recorded Supplier Cash Disbursements (if any recorded in session)
            if (payments.isNotEmpty()) {
                item {
                    Text(
                        text = lang.tr("سندات الصرف النقدي للمورد", "Supplier Cash Disbursements"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                items(payments, key = { it.id }) { pay ->
                    SupplierPaymentRow(payment = pay, lang = lang)
                }
            }

            // 3. Purchase Bills Section (Clear distinction between Financial Payable vs Stock Receiving)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("فواتير المشتريات المالية مقابل استلام المخزن", "Financial Purchase Bills vs. Physical GRN"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lang.tr(
                                "${WesternFormatters.formatInt(bills.size)} فواتير",
                                "${WesternFormatters.formatInt(bills.size)} bills"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = lang.tr(
                            "الفاتورة المالية تثبت التزام المورد والدفعة النقدية فقط؛ بينما (سند استلام بضاعة مخزني - GRN) يضيف الكميات فعلياً لبطاقة الصنف بعد الفحص.",
                            "Financial bills record supplier payables and cash paid; Physical Goods Receipt (GRN) adds inspected quantities to product stock."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (bills.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr("لا توجد فواتير شراء مسجلة لهذا المورد.", "No purchase bills recorded for this supplier."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(bills, key = { it.id }) { bill ->
                    PurchaseBillRow(
                        bill = bill,
                        lang = lang,
                        canReceiveStock = canManageSuppliers && onConfirmGoodsReceipt != null,
                        onOpenReceiveGoodsDialog = { activeBillForGrn = bill }
                    )
                }
            }
        }
    }

    if (isEditSupplierOpen && onSaveSupplier != null) {
        SupplierEditorDialog(
            initialSupplier = supplier,
            lang = lang,
            onDismiss = { isEditSupplierOpen = false },
            onConfirmSave = { sid, name, company, phone ->
                onSaveSupplier(sid, name, company, phone)
                isEditSupplierOpen = false
            }
        )
    }

    if (isPurchaseReturnPreviewOpen) {
        PurchaseReturnPreviewDialog(
            supplier = supplier,
            bills = bills,
            lang = lang,
            onDismiss = { isPurchaseReturnPreviewOpen = false }
        )
    }

    if (activeBillForGrn != null && onConfirmGoodsReceipt != null) {
        val bill = activeBillForGrn!!
        PhysicalGoodsReceiptDialog(
            bill = bill,
            products = state.products,
            lang = lang,
            onDismiss = { activeBillForGrn = null },
            onConfirmGrn = { productId, receivedQty, batchNote ->
                onConfirmGoodsReceipt(bill.id, productId, receivedQty, batchNote)
                activeBillForGrn = null
            }
        )
    }

    // Supplier Disbursement Dialog
    if (isPayDialogOpen && canManageSuppliers) {
        val initialPayStr = if (supplier.balanceOwedIls > 0.005) {
            WesternFormatters.formatNumber(minOf(200.0, supplier.balanceOwedIls)).removeSuffix(".00")
        } else {
            "200"
        }
        var payAmountText by remember(supplier.id) { mutableStateOf(initialPayStr) }
        val enteredAmount = payAmountText.toDoubleOrNull() ?: 0.0
        val projectedOwed = remember(supplier.balanceOwedIls, enteredAmount) {
            BigDecimal(supplier.balanceOwedIls.toString())
                .subtract(BigDecimal(enteredAmount.toString()))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
        }

        AlertDialog(
            onDismissRequest = { isPayDialogOpen = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = lang.tr("سداد دفعة نقدية للمورد", "Pay Supplier from Cash Drawer"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${supplier.name(lang)} • ${
                            if (supplier.balanceOwedIls > 0.005) {
                                lang.tr("المستحق له حالياً: ${WesternFormatters.formatIls(supplier.balanceOwedIls)}", "Currently owed: ${WesternFormatters.formatIls(supplier.balanceOwedIls)}")
                            } else if (supplier.balanceOwedIls < -0.005) {
                                lang.tr("دفعة مقدمة لنا حالياً: ${WesternFormatters.formatIls(-supplier.balanceOwedIls)}", "Current advance credit: ${WesternFormatters.formatIls(-supplier.balanceOwedIls)}")
                            } else {
                                lang.tr("الرصيد الحالي: خالص (0.00 ₪)", "Current balance: Settled (0.00 ₪)")
                            }
                        }",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (supplier.balanceOwedIls > 0.005) {
                            val fullStr = WesternFormatters.formatNumber(supplier.balanceOwedIls).removeSuffix(".00")
                            item {
                                FilterChip(
                                    selected = payAmountText == fullStr || abs(enteredAmount - supplier.balanceOwedIls) < 0.005,
                                    onClick = { payAmountText = fullStr },
                                    label = {
                                        Text(
                                            lang.tr(
                                                "كامل المستحق (${WesternFormatters.formatIls(supplier.balanceOwedIls)})",
                                                "Full Owed (${WesternFormatters.formatIls(supplier.balanceOwedIls)})"
                                            )
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                        items(listOf(200, 500, 1000)) { preset ->
                            FilterChip(
                                selected = payAmountText == preset.toString(),
                                onClick = { payAmountText = preset.toString() },
                                label = { Text("$preset ₪") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = payAmountText,
                        onValueChange = { payAmountText = WesternFormatters.sanitizeWesternDigits(it) },
                        label = { Text(lang.tr("المبلغ المصروف من الصندوق (₪)", "Disbursement Amount (₪)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier_payment_amount_input")
                    )

                    if (enteredAmount > 0.0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (projectedOwed > 0.005) {
                                TraderTheme.financial.warningContainer
                            } else {
                                TraderTheme.financial.incomeContainer
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = when {
                                    projectedOwed > 0.005 -> lang.tr(
                                        "بعد الصرف: يتبقى للمورد علينا ${WesternFormatters.formatIls(projectedOwed)}",
                                        "After payment: Remaining payable ${WesternFormatters.formatIls(projectedOwed)}"
                                    )
                                    projectedOwed < -0.005 -> lang.tr(
                                        "بعد الصرف: يصبح لنا رصيد دفعة مقدمة عند المورد ${WesternFormatters.formatIls(-projectedOwed)}",
                                        "After payment: Store advance credit of ${WesternFormatters.formatIls(-projectedOwed)}"
                                    )
                                    else -> lang.tr(
                                        "بعد الصرف: يصبح حساب المورد خالصاً (0.00 ₪)",
                                        "After payment: Supplier account becomes settled (0.00 ₪)"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (projectedOwed > 0.005) MaterialTheme.colorScheme.onSurface else TraderTheme.financial.income,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredAmount > 0.0) {
                            onPaySupplier(supplier, enteredAmount)
                            isPayDialogOpen = false
                        }
                    },
                    enabled = enteredAmount > 0.0,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_supplier_payment_button")
                ) {
                    Text(lang.tr("تأكيد الصرف", "Confirm Payment"))
                }
            },
            dismissButton = {
                TextButton(onClick = { isPayDialogOpen = false }) {
                    Text(lang.tr("إلغاء", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun SupplierPaymentRow(
    payment: SupplierPayment,
    lang: AppLanguage
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supplier_payment_${payment.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = payment.note,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = payment.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "- ${WesternFormatters.formatIls(payment.amountIls)}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = TraderTheme.financial.income
                )
                Text(
                    text = lang.tr("سند صرف نقدي", "Cash Disbursed"),
                    style = MaterialTheme.typography.labelSmall,
                    color = TraderTheme.financial.income
                )
            }
        }
    }
}

@Composable
private fun PurchaseBillRow(
    bill: PurchaseBill,
    lang: AppLanguage,
    canReceiveStock: Boolean = false,
    onOpenReceiveGoodsDialog: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("purchase_bill_${bill.billNumber}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = WesternFormatters.formatBillCode(bill.billNumber),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    val hasExpectedSku = !bill.expectedProductId.isNullOrBlank() && bill.expectedQuantity > 0
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (bill.isReceived) {
                            TraderTheme.financial.incomeContainer
                        } else {
                            TraderTheme.financial.warningContainer
                        }
                    ) {
                        Text(
                            text = when {
                                bill.isReceived -> lang.tr("تم استلام البضاعة بالمخزن (GRN)", "Goods Received in Stock (GRN)")
                                hasExpectedSku -> lang.tr("بانتظار استلام مخزني (${WesternFormatters.formatInt(bill.expectedQuantity)} وحدة)", "Pending GRN (${WesternFormatters.formatInt(bill.expectedQuantity)} units)")
                                else -> lang.tr("قيد مالي نصي فقط • بدون بنود أصناف", "Financial summary only • No SKU lines")
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (bill.isReceived) {
                                TraderTheme.financial.income
                            } else {
                                TraderTheme.financial.warning
                            },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = bill.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = bill.itemsSummary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${lang.tr("قيمة الفاتورة:", "Bill Total:")} ${WesternFormatters.formatIls(bill.totalIls)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("دُفع نقداً:", "Paid Cash:")} ${WesternFormatters.formatIls(bill.paidIls)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bill.paidIls > 0.005) TraderTheme.financial.income else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("ذمة للمورد:", "Payable:")} ${WesternFormatters.formatIls(bill.remainingPayableIls)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (bill.remainingPayableIls > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income
                )
            }

            val canConfirmExpectedSku = !bill.isReceived &&
                !bill.expectedProductId.isNullOrBlank() &&
                bill.expectedQuantity > 0 &&
                canReceiveStock &&
                onOpenReceiveGoodsDialog != null

            if (canConfirmExpectedSku) {
                OutlinedButton(
                    onClick = onOpenReceiveGoodsDialog!!,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("confirm_grn_${bill.id}")
                ) {
                    Text(
                        text = lang.tr(
                            "تأكيد استلام بضاعة مخزني وإضافة الكمية للمخزون (GRN)",
                            "Confirm Physical Goods Receipt into Stock (GRN)"
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun SupplierEditorDialog(
    initialSupplier: Supplier?,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmSave: (supplierId: String?, name: String, company: String, phone: String) -> Unit
) {
    val isEditing = initialSupplier != null
    var nameText by remember(initialSupplier) { mutableStateOf(initialSupplier?.nameAr ?: "") }
    var companyText by remember(initialSupplier) { mutableStateOf(initialSupplier?.companyAr ?: "") }
    var phoneText by remember(initialSupplier) { mutableStateOf(initialSupplier?.phone ?: "") }

    val canSave = nameText.trim().length >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = if (isEditing) {
                    lang.tr("تعديل بيانات المورد", "Edit Supplier Info")
                } else {
                    lang.tr("إضافة مورد جديد", "Add New Supplier")
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text(lang.tr("اسم المورد / المندوب *", "Supplier / Agent Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_editor_name_input")
                )
                OutlinedTextField(
                    value = companyText,
                    onValueChange = { companyText = it },
                    label = { Text(lang.tr("الشركة أو المستودع", "Company / Distributor")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_editor_company_input")
                )
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("رقم الجوال / الهاتف", "Phone Number")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_editor_phone_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        onConfirmSave(initialSupplier?.id, nameText.trim(), companyText.trim(), phoneText.trim())
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_save_supplier_button")
            ) {
                Text(lang.tr("حفظ المورد", "Save Supplier"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
private fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<com.example.model.Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrn: (productId: String, receivedQty: Int, batchNote: String) -> Unit
) {
    val expectedProduct = remember(products, bill.expectedProductId) {
        products.firstOrNull { it.id == bill.expectedProductId && it.isStockTracked }
    }
    val expectedQty = bill.expectedQuantity.coerceAtLeast(0)
    var batchNote by remember(bill.id) {
        mutableStateOf("استلام مخزني مطابق للفاتورة ${WesternFormatters.formatBillCode(bill.billNumber)}")
    }
    val canConfirm = !bill.isReceived && expectedProduct != null && expectedQty > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr(
                    "سند استلام بضاعة مخزني (GRN) • ${WesternFormatters.formatBillCode(bill.billNumber)}",
                    "Physical Goods Receipt (GRN) • ${WesternFormatters.formatBillCode(bill.billNumber)}"
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TraderTheme.financial.incomeContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "عرض تجريبي محدد لبند واحد: يطابق الصنف والكمية المسجلين في الفاتورة لمرة واحدة فقط دون تغيير ذمة المورد المالية.",
                            "Bounded single-SKU demo: Verifies the bill's expected item and quantity once without altering supplier payable."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                if (expectedProduct != null && expectedQty > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_product_chip_${expectedProduct.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = lang.tr("الصنف المحدد في الفاتورة:", "Expected Product on Bill:"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = expectedProduct.name(lang),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr(
                                    "الكمية المتوقعة للاستلام: ${WesternFormatters.formatInt(expectedQty)} (${expectedProduct.unit(lang)})",
                                    "Expected quantity to receive: ${WesternFormatters.formatInt(expectedQty)} (${expectedProduct.unit(lang)})"
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("grn_qty_input")
                            )
                        }
                    }

                    OutlinedTextField(
                        value = batchNote,
                        onValueChange = { batchNote = it },
                        label = { Text(lang.tr("ملاحظة الفحص / رقم التشغيلة", "Inspection / Batch Note")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_note_input")
                    )

                    Text(
                        text = lang.tr(
                            "رصيد (${expectedProduct.name(lang)}) سيرتفع من ${WesternFormatters.formatInt(expectedProduct.stockCount)} إلى ${WesternFormatters.formatInt(expectedProduct.stockCount + expectedQty)} ${expectedProduct.unit(lang)}",
                            "${expectedProduct.name(lang)} stock will increase from ${WesternFormatters.formatInt(expectedProduct.stockCount)} to ${WesternFormatters.formatInt(expectedProduct.stockCount + expectedQty)}"
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = TraderTheme.financial.income
                    )
                } else {
                    Text(
                        text = lang.tr(
                            "هذه الفاتورة قيد مالي نصي فقط ولا تحتوي على صنف مخزني محدد للاستلام الآلي.",
                            "This bill is a financial summary only and has no linked catalog SKU for automated GRN."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TraderTheme.financial.warning
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val prod = expectedProduct
                    if (!bill.isReceived && prod != null && expectedQty > 0) {
                        onConfirmGrn(prod.id, expectedQty, batchNote.trim())
                    }
                },
                enabled = canConfirm,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_grn_submit_button")
            ) {
                Text(lang.tr("تثبيت الاستلام المخزني", "Confirm Goods Receipt"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
private fun PurchaseReturnPreviewDialog(
    supplier: Supplier,
    bills: List<PurchaseBill>,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val latestBill = bills.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr("معاينة مرتجع مشتريات للمورد (Purchase Return)", "Supplier Purchase Return Preview"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TraderTheme.financial.warningContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "معاينة نموذجية (Shallow Preview): إرجاع بضاعة تالفة أو قريبة الانتهاء للمورد (${supplier.name(lang)}) يخصم الكمية من المستودع ويصدر إشعار مدين يخفض التزام المورد عبر Laravel /api/v1.",
                            "Shallow Preview: Returning damaged/expiring goods to (${supplier.name(lang)}) deducts stock and issues a supplier debit note via Laravel /api/v1."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                if (latestBill != null) {
                    Text(
                        text = lang.tr(
                            "مثال تطبيقي على الفاتورة ${WesternFormatters.formatBillCode(latestBill.billNumber)} (${latestBill.itemsSummary}):\n• إرجاع 2 كرتونة تالفة بتكلفة 120.00 ₪\n• الأثر المالي: خفض ذمة المورد من ${WesternFormatters.formatIls(supplier.balanceOwedIls)} إلى ${WesternFormatters.formatIls((supplier.balanceOwedIls - 120.0).coerceAtLeast(0.0))}",
                            "Sample return against ${WesternFormatters.formatBillCode(latestBill.billNumber)}:\n• Return 2 damaged cartons (120.00 ₪)\n• Financial impact: Reduces payable owed from ${WesternFormatters.formatIls(supplier.balanceOwedIls)} to ${WesternFormatters.formatIls((supplier.balanceOwedIls - 120.0).coerceAtLeast(0.0))}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("close_purchase_return_preview_button")
            ) {
                Text(lang.tr("إغلاق المعاينة", "Close Preview"))
            }
        }
    )
}

@Composable
fun ReceivePurchaseBillDialog(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onConfirmBill: (supplierId: String, itemsSummary: String, totalIls: Double, paidNowIls: Double) -> Unit
) {
    val lang = state.language
    val suppliers = state.suppliers
    if (suppliers.isEmpty()) return

    var selectedSupplierId by remember(state.selectedSupplierForDetail?.id) {
        mutableStateOf(state.selectedSupplierForDetail?.id ?: suppliers.first().id)
    }
    var purchaseDesc by remember { mutableStateOf("") }
    var purchaseTotal by remember { mutableStateOf("") }
    var purchasePaid by remember { mutableStateOf("0") }

    val totalVal = purchaseTotal.toDoubleOrNull() ?: 0.0
    val paidVal = if (purchasePaid.isBlank()) 0.0 else (purchasePaid.toDoubleOrNull() ?: -1.0)
    val isPaidValid = paidVal >= 0.0 && paidVal <= totalVal + 0.001
    val isFormValid = purchaseDesc.isNotBlank() && totalVal > 0.0 && isPaidValid

    val payableAdded = if (totalVal > 0.0 && isPaidValid) {
        BigDecimal(totalVal.toString())
            .subtract(BigDecimal(paidVal.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)
    } else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr("قيد فاتورة مشتريات مالية", "Record Financial Purchase Bill"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Concise explanation of financial bill vs stock receiving
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "يسجل هذا السند التزام المورد والدفعة النقدية. (تزويد كميات الأصناف يتم من شاشة المخزون).",
                            "Records supplier payable and cash paid. (Itemized stock quantities are updated in Inventory)."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                // Supplier Selector Chips (if more than one supplier)
                if (state.selectedSupplierForDetail == null && suppliers.size > 1) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(suppliers, key = { it.id }) { sup ->
                            FilterChip(
                                selected = selectedSupplierId == sup.id,
                                onClick = { selectedSupplierId = sup.id },
                                label = { Text(sup.name(lang), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                } else {
                    val activeSup = suppliers.firstOrNull { it.id == selectedSupplierId } ?: suppliers.first()
                    Text(
                        text = "${lang.tr("المورد:", "Supplier:")} ${activeSup.name(lang)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                OutlinedTextField(
                    value = purchaseDesc,
                    onValueChange = { purchaseDesc = it },
                    label = { Text(lang.tr("بيان البضاعة أو رقم فاتورة المورد", "Bill Summary / Supplier Ref")) },
                    placeholder = { Text(lang.tr("مثال: 20 كرتونة زيت وحلاوة", "e.g. 20 cartons oil & halva")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_summary_input")
                )

                OutlinedTextField(
                    value = purchaseTotal,
                    onValueChange = { purchaseTotal = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("إجمالي قيمة الفاتورة (₪)", "Total Bill Amount (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_total_input")
                )

                OutlinedTextField(
                    value = purchasePaid,
                    onValueChange = { purchasePaid = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("المسدد نقداً الآن من الصندوق (₪)", "Cash Paid Now from Drawer (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_paid_input")
                )

                if (totalVal > 0.0 && !isPaidValid) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "المبلغ المدفوع فوراً يجب أن يكون بين 0.00 ₪ وإجمالي الفاتورة (${WesternFormatters.formatIls(totalVal)})",
                                "Paid-now amount must be between 0.00 ₪ and bill total (${WesternFormatters.formatIls(totalVal)})"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else if (isFormValid) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TraderTheme.financial.warningContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "المضاف لذمة المورد: ${WesternFormatters.formatIls(payableAdded)} • المخصوم من نقد الصندوق اليوم: ${WesternFormatters.formatIls(paidVal)}",
                                "Added to supplier payable: ${WesternFormatters.formatIls(payableAdded)} • Deducted from today's cash drawer: ${WesternFormatters.formatIls(paidVal)}"
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmBill(selectedSupplierId, purchaseDesc.trim(), totalVal, paidVal.coerceAtLeast(0.0))
                },
                enabled = isFormValid,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_purchase_bill_button")
            ) {
                Text(lang.tr("تثبيت الفاتورة المالية", "Record Financial Bill"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}
