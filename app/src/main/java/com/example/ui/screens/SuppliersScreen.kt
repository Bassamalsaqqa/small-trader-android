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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.PurchaseBill
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
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

/**
 * Native Supplier Directory & Accounts Payable Ledger Screen (سجل الموردين والذمم).
 *
 * Implements Stitch Design System (ST-33, ST-37, ST-38, ST-40 / DESIGN_LOCK.md):
 * 1. Role Protection: Only Owner/Manager can view or interact with suppliers. Cashier is
 *    immediately gated out.
 * 2. Truthful Directional Balances:
 *    - Supplier Payable (علينا للمورد): Warning pill `#A15C00` / `#FFF5DF`
 *    - Supplier Advance (لنا رصيد مقدم): Success pill `#14804A` / `#E8F5E9`
 *    - Settled: Success checkmark pill
 * 3. Separation of Concerns: Clear boundary between Financial Bills (debts & cash payments)
 *    and Physical Goods Receipts (GRN - stock addition).
 * 4. Microtype Safety & Spacing: 12sp floor, 12dp card radii, 10dp buttons, 48dp touch bounds.
 */
@Composable
fun SuppliersScreen(
    state: TraderUiState,
    onSupplierClick: (Supplier) -> Unit,
    onOpenReceivePurchaseDialog: () -> Unit,
    onDismissFeedback: (() -> Unit)? = null,
    onSaveSupplier: ((supplierId: String?, name: String, company: String, phone: String) -> Unit)? = null,
    launchCreate: Boolean = false,
    onLaunchConsumed: () -> Unit = {}
) {
    val createDraft = rememberSaveableStateHolder()
    val lang = state.language
    val canManageSuppliers = state.currentSession.canManageSuppliers()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(SupplierBalanceFilter.ALL) }
    var isAddSupplierOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(launchCreate) {
        if (launchCreate && canManageSuppliers) {
            isAddSupplierOpen = true
            onLaunchConsumed()
        }
    }
    if (!canManageSuppliers) return

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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Payables vs Prepayments Summary Strip + New Bill & New Supplier CTAs
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
                                text = StitchBidiUtils.formatIls(state.totalSupplierPayablesIls),
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
                                text = StitchBidiUtils.formatIls(state.totalSupplierAdvancesIls),
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
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("add_supplier_button")
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
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("record_purchase_bill_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = lang.tr("فاتورة شراء", "New Bill"),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
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
                                        tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else TraderTheme.financial.income,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Instant Search by Supplier Name, Representative or Phone
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("supplier_search_input"),
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "ابحث باسم المورد، الشركة، أو رقم الهاتف...",
                                "Search by supplier name, company, or phone..."
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
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = lang.tr("مسح", "Clear"),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Balance Filter Chips (All / Payables / Advances / Settled)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == SupplierBalanceFilter.ALL,
                                onClick = { selectedFilter = SupplierBalanceFilter.ALL },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "الكل (${WesternFormatters.formatInt(state.suppliers.size)})",
                                            "All (${WesternFormatters.formatInt(state.suppliers.size)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == SupplierBalanceFilter.ALL) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("supplier_filter_ALL")
                            )
                        }
                    }
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == SupplierBalanceFilter.PAYABLES,
                                onClick = { selectedFilter = SupplierBalanceFilter.PAYABLES },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "علينا ذمة (${WesternFormatters.formatInt(payablesCount)})",
                                            "Payable (${WesternFormatters.formatInt(payablesCount)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == SupplierBalanceFilter.PAYABLES) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TraderTheme.financial.debit,
                                    selectedLabelColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.testTag("supplier_filter_PAYABLES")
                            )
                        }
                    }
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == SupplierBalanceFilter.ADVANCES,
                                onClick = { selectedFilter = SupplierBalanceFilter.ADVANCES },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "لنا رصيد مقدم (${WesternFormatters.formatInt(advancesCount)})",
                                            "Advance (${WesternFormatters.formatInt(advancesCount)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == SupplierBalanceFilter.ADVANCES) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TraderTheme.financial.income,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("supplier_filter_ADVANCES")
                            )
                        }
                    }
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == SupplierBalanceFilter.SETTLED,
                                onClick = { selectedFilter = SupplierBalanceFilter.SETTLED },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "خالص (${WesternFormatters.formatInt(settledCount)})",
                                            "Settled (${WesternFormatters.formatInt(settledCount)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == SupplierBalanceFilter.SETTLED) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                ),
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Store,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedFilter != SupplierBalanceFilter.ALL) {
                            lang.tr("لا يوجد موردين مطابقين للبحث أو التصفية", "No matching suppliers found")
                        } else {
                            lang.tr("لا يوجد موردين مسجلين حالياً", "No registered suppliers yet")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (searchQuery.isNotBlank() || selectedFilter != SupplierBalanceFilter.ALL) {
                        TextButton(
                            onClick = {
                                searchQuery = ""
                                selectedFilter = SupplierBalanceFilter.ALL
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = lang.tr("عرض كل الموردين", "Show All Suppliers"),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("suppliers_directory_list"),
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
        createDraft.SaveableStateProvider("new_supplier_form") {
            SupplierEditorDialog(
                initialSupplier = null,
                lang = lang,
                onDismiss = { isAddSupplierOpen = false },
                onSave = { name, company, phone ->
                    onSaveSupplier(null, name, company, phone)
                    isAddSupplierOpen = false
                }
            )
        }
    }
}

/**
 * Calm compact supplier list row (ST-33 Stitch Specification).
 * Displays trade name, representative phone, directional balance pill, and detail click.
 */
@Composable
private fun SupplierDirectoryRow(
    supplier: Supplier,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val isPayableOwed = supplier.balanceOwedIls > 0.005
    val isStoreAdvance = supplier.balanceOwedIls < -0.005

    val statusBadgeTone = when {
        isPayableOwed -> StitchBadgeTone.WARNING
        isStoreAdvance -> StitchBadgeTone.SUCCESS
        else -> StitchBadgeTone.NEUTRAL
    }

    val statusLabel = when {
        isPayableOwed -> "${lang.tr("مستحق للمورد", "Owed to Supplier")} ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}"
        isStoreAdvance -> "${lang.tr("لنا رصيد مقدم", "Store Advance")} ${StitchBidiUtils.formatIls(-supplier.balanceOwedIls)}"
        else -> lang.tr("خالص", "Settled")
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supplier_row_${supplier.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left/Start: Trade Name + LTR Phone + Company
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = supplier.name(lang),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                    if (supplier.company(lang).isNotBlank() && supplier.company(lang) != supplier.name(lang)) {
                        Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = supplier.company(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Balance Directional Badge
                StitchBadge(
                    text = statusLabel,
                    tone = statusBadgeTone,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Right/End: Action button indicator
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lang.tr("التفاصيل", "Details"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Supplier Detail Screen (ST-37 Specification).
 *
 * Implements Stitch ST-37 (Outstanding Payable 850.00 ₪) and ST-38 (Disburse 200.00 ₪):
 * - Outstanding payable display with clear "علينا للمورد" semantic
 * - Cash disbursement trigger from drawer funds (850 - 200 = 650 ₪)
 * - Clear separation between Financial Purchase Bills and Physical Goods Receipts (GRN)
 * - Dynamic SKU-linked GRN inspection without hardcoded values
 */
@Composable
fun SupplierDetailScreen(
    state: TraderUiState,
    onBack: () -> Unit,
    onPaySupplier: (Supplier, Double) -> Unit,
    onOpenReceivePurchaseDialog: () -> Unit,
    onDismissFeedback: (() -> Unit)? = null,
    onSaveSupplier: ((supplierId: String?, name: String, company: String, phone: String) -> Unit)? = null,
    launchPayment: Boolean = false,
    onLaunchConsumed: () -> Unit = {},
    onConfirmGoodsReceipt: ((billId: String, productId: String, receivedQty: Int, batchNote: String) -> Unit)? = null
) {
    val paymentDraft = rememberSaveableStateHolder()
    val lang = state.language
    val supplier = state.selectedSupplierForDetail ?: return
    val canManageSuppliers = state.currentSession.canManageSuppliers()

    val bills = remember(state.purchaseBills, supplier.id) {
        state.purchaseBills.filter { it.supplierId == supplier.id }
    }
    val payments = remember(state.supplierPayments, supplier.id) {
        state.supplierPayments.filter { it.supplierId == supplier.id }
    }

    var isPayDialogOpen by rememberSaveable(supplier.id) { mutableStateOf(false) }
    LaunchedEffect(launchPayment, supplier.id) {
        if (launchPayment && canManageSuppliers) {
            isPayDialogOpen = true
            onLaunchConsumed()
        }
    }
    if (!canManageSuppliers) return

    var isEditSupplierOpen by rememberSaveable(supplier.id) { mutableStateOf(false) }
    var isPurchaseReturnPreviewOpen by rememberSaveable(supplier.id) { mutableStateOf(false) }
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
        // Sticky Header with Back Button
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = lang.tr("رجوع", "Back"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = supplier.name(lang),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                        if (supplier.company(lang).isNotBlank() && supplier.company(lang) != supplier.name(lang)) {
                            Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = supplier.company(lang),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (feedbackMessage != null) {
                item {
                    val isError = state.isSupplierActionError
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else TraderTheme.financial.incomeContainer,
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
                                    modifier = Modifier.size(20.dp)
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
                                        tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else TraderTheme.financial.income,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Outstanding Payable Summary Card (ST-37 Specification)
            item {
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr(
                                    "إجمالي المستحق للمورد (علينا)",
                                    "Total Payable Owed to Supplier"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StitchBadge(
                                text = when {
                                    isPayableOwed -> lang.tr("قيد الاستحقاق", "Payable Due")
                                    isStoreAdvance -> lang.tr("دفعة مقدمة لنا", "Store Advance")
                                    else -> lang.tr("خالص", "Settled")
                                },
                                tone = when {
                                    isPayableOwed -> StitchBadgeTone.WARNING
                                    isStoreAdvance -> StitchBadgeTone.SUCCESS
                                    else -> StitchBadgeTone.NEUTRAL
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        // Big Dominant Amount (e.g. 850.00 ₪)
                        Text(
                            text = StitchBidiUtils.formatIls(supplier.balanceOwedIls),
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = when {
                                isPayableOwed -> TraderTheme.financial.warning
                                isStoreAdvance -> TraderTheme.financial.income
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.testTag("supplier_detail_balance_value")
                        )

                        Text(
                            text = lang.tr(
                                "ذمم دائنة واجبة السداد من صندوق المحل لصالح المورد.",
                                "Monetary obligation payable from shop cash drawer."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. Primary Fast Actions (>= 48dp Touch Targets)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { isPayDialogOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("open_pay_supplier_dialog_button")
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("صرف ودفع دفعة للمورد", "Pay Supplier"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    OutlinedButton(
                        onClick = onOpenReceivePurchaseDialog,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("supplier_new_bill_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("فاتورة شراء جديدة", "New Bill"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            // 3. Secondary Utility Actions (Edit & Return Preview)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onSaveSupplier != null) {
                        OutlinedButton(
                            onClick = { isEditSupplierOpen = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("edit_supplier_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = lang.tr("تعديل بيانات المورد", "Edit Info"),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { isPurchaseReturnPreviewOpen = true },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("open_purchase_return_preview_button")
                    ) {
                        Text(
                            text = lang.tr("معاينة إرجاع بضاعة", "Return Preview"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            // 4. Financial Purchase Bills Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("فواتير الشراء المسجلة", "Purchase Bills"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StitchBadge(
                        text = "${WesternFormatters.formatInt(bills.size)} ${lang.tr("فاتورة", "bills")}",
                        tone = StitchBadgeTone.NEUTRAL,
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }

            if (bills.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "لا توجد فواتير شراء سابقة مسجلة لهذا المورد.",
                                "No purchase bills recorded for this supplier yet."
                            ),
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

            // 5. Cash Disbursements History Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("سندات الصرف والدفعات النقدية", "Disbursement Payments"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StitchBadge(
                        text = "${WesternFormatters.formatInt(payments.size)} ${lang.tr("سند", "receipts")}",
                        tone = StitchBadgeTone.NEUTRAL,
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }

            if (payments.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "لا توجد سندات صرف نقدية مسجلة لهذا المورد بعد.",
                                "No cash disbursements recorded for this supplier yet."
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(payments, key = { it.id }) { pay ->
                    SupplierPaymentRow(payment = pay, lang = lang)
                }
            }
        }
    }

    if (isEditSupplierOpen && onSaveSupplier != null) {
        SupplierEditorDialog(
            initialSupplier = supplier,
            lang = lang,
            onDismiss = { isEditSupplierOpen = false },
            onSave = { name, company, phone ->
                onSaveSupplier(supplier.id, name, company, phone)
                isEditSupplierOpen = false
            }
        )
    }

    if (isPurchaseReturnPreviewOpen) {
        PurchaseReturnPreviewDialog(
            supplier = supplier,
            lang = lang,
            onDismiss = { isPurchaseReturnPreviewOpen = false }
        )
    }

    // Physical Goods Receipt (GRN) Dialog
    if (activeBillForGrn != null && onConfirmGoodsReceipt != null) {
        PhysicalGoodsReceiptDialog(
            bill = activeBillForGrn!!,
            products = state.products,
            lang = lang,
            onDismiss = { activeBillForGrn = null },
            onConfirmGrn = { productId, receivedQty, batchNote ->
                val bill = activeBillForGrn ?: return@PhysicalGoodsReceiptDialog
                onConfirmGoodsReceipt(bill.id, productId, receivedQty, batchNote)
                activeBillForGrn = null
            }
        )
    }

    // Supplier Cash Disbursement Dialog (ST-38 Specification: 850 - 200 = 650 ₪)
    if (isPayDialogOpen && canManageSuppliers) {
        paymentDraft.SaveableStateProvider("payment_${supplier.id}_${state.supplierPayments.size}") {
            val initialPayStr = if (supplier.balanceOwedIls > 0.005) {
                WesternFormatters.formatNumber(supplier.balanceOwedIls).removeSuffix(".00")
            } else {
                ""
            }
            var payAmountText by rememberSaveable(supplier.id) { mutableStateOf(initialPayStr) }
            val enteredAmount = payAmountText.toDoubleOrNull() ?: 0.0
            val projectedOwed = remember(supplier.balanceOwedIls, enteredAmount) {
                BigDecimal(supplier.balanceOwedIls.toString())
                    .subtract(BigDecimal(enteredAmount.toString()))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toDouble()
            }

            AlertDialog(
                onDismissRequest = { isPayDialogOpen = false },
                shape = RoundedCornerShape(12.dp),
                title = {
                    Text(
                        text = lang.tr("صرف دفعة نقدية للمورد", "Pay Supplier from Cash Drawer"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Current Payable Status Card
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = supplier.name(lang),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (supplier.balanceOwedIls > 0.005) {
                                        lang.tr(
                                            "الرصيد المستحق الحالي للمورد (علينا): ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}",
                                            "Currently owed to supplier: ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}"
                                        )
                                    } else if (supplier.balanceOwedIls < -0.005) {
                                        lang.tr(
                                            "دفعة مقدمة لنا عند المورد: ${StitchBidiUtils.formatIls(-supplier.balanceOwedIls)}",
                                            "Current advance credit: ${StitchBidiUtils.formatIls(-supplier.balanceOwedIls)}"
                                        )
                                    } else {
                                        lang.tr("الرصيد الحالي: خالص (0.00 ₪)", "Current balance: Settled (0.00 ₪)")
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (supplier.balanceOwedIls > 0.005) TraderTheme.financial.warning else TraderTheme.financial.income
                                )
                            }
                        }

                        // Preset Chips (200, 500, Full)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (supplier.balanceOwedIls > 0.005) {
                                val fullStr = WesternFormatters.formatNumber(supplier.balanceOwedIls).removeSuffix(".00")
                                item {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                        FilterChip(
                                            selected = payAmountText == fullStr || abs(enteredAmount - supplier.balanceOwedIls) < 0.005,
                                            onClick = { payAmountText = fullStr },
                                            label = {
                                                Text(
                                                    lang.tr(
                                                        "كامل (${StitchBidiUtils.formatIls(supplier.balanceOwedIls)})",
                                                        "Full (${StitchBidiUtils.formatIls(supplier.balanceOwedIls)})"
                                                    ),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        )
                                    }
                                }
                            }
                            items(listOf(200, 500)) { preset ->
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                    FilterChip(
                                        selected = payAmountText == preset.toString(),
                                        onClick = { payAmountText = preset.toString() },
                                        label = { Text("$preset ₪") },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }
                        }

                        // Cash Amount Input
                        OutlinedTextField(
                            value = payAmountText,
                            onValueChange = { payAmountText = WesternFormatters.sanitizeWesternDigits(it) },
                            label = { Text(lang.tr("المبلغ المراد صرفه ودفعه نقداً (₪) *", "Cash Disbursement (₪) *")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                textDirection = TextDirection.Ltr
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("supplier_payment_amount_input")
                        )

                        // Live Financial Impact Banner (ST-38: 850 - 200 = 650 ₪)
                        if (enteredAmount > 0.0) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = when {
                                            projectedOwed > 0.005 -> lang.tr(
                                                "الرصيد المتبقي للمورد بعد الدفع: ${StitchBidiUtils.formatIls(projectedOwed)}",
                                                "Remaining payable after payment: ${StitchBidiUtils.formatIls(projectedOwed)}"
                                            )
                                            projectedOwed < -0.005 -> lang.tr(
                                                "بعد الصرف: يصبح لنا رصيد دفعة مقدمة ${StitchBidiUtils.formatIls(-projectedOwed)}",
                                                "After payment: Store advance credit of ${StitchBidiUtils.formatIls(-projectedOwed)}"
                                            )
                                            else -> lang.tr(
                                                "بعد الصرف: يصبح حساب المورد خالصاً تماماً (0.00 ₪)",
                                                "After payment: Supplier account is settled (0.00 ₪)"
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Payments,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = lang.tr(
                                                "جهة الخصم: الصندوق النقدي الحالي (الدرج)",
                                                "Source: Current cash drawer funds"
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("confirm_supplier_payment_button")
                    ) {
                        Text(
                            text = if (enteredAmount > 0.0) {
                                lang.tr(
                                    "تأكيد صرف ${StitchBidiUtils.formatIls(enteredAmount)} ودفعها للمورد",
                                    "Confirm ${StitchBidiUtils.formatIls(enteredAmount)} Disbursement"
                                )
                            } else {
                                lang.tr("تأكيد الصرف", "Confirm Payment")
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { isPayDialogOpen = false },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(lang.tr("إلغاء", "Cancel"))
                    }
                }
            )
        }
    }
}

@Composable
private fun SupplierPaymentRow(
    payment: SupplierPayment,
    lang: AppLanguage
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supplier_payment_${payment.id}")
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
                    text = "- ${StitchBidiUtils.formatIls(payment.amountIls)}",
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("purchase_bill_${bill.billNumber}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                        text = StitchBidiUtils.formatBillCode(bill.billNumber),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    val hasExpectedSku = !bill.expectedProductId.isNullOrBlank() && bill.expectedQuantity > 0
                    StitchBadge(
                        text = when {
                            bill.isReceived -> lang.tr("تم استلام البضاعة (GRN)", "Goods Received (GRN)")
                            hasExpectedSku -> lang.tr("بانتظار استلام (${WesternFormatters.formatInt(bill.expectedQuantity)} وحدة)", "Pending GRN (${WesternFormatters.formatInt(bill.expectedQuantity)} units)")
                            else -> lang.tr("قيد مالي فقط", "Financial only")
                        },
                        tone = if (bill.isReceived) StitchBadgeTone.SUCCESS else StitchBadgeTone.WARNING,
                        shape = RoundedCornerShape(6.dp)
                    )
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
                    text = "${lang.tr("القيمة:", "Total:")} ${StitchBidiUtils.formatIls(bill.totalIls)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("دُفع نقداً:", "Paid Cash:")} ${StitchBidiUtils.formatIls(bill.paidIls)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bill.paidIls > 0.005) TraderTheme.financial.income else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("ذمة للمورد:", "Payable:")} ${StitchBidiUtils.formatIls(bill.remainingPayableIls)}",
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
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
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
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
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
    onSave: (name: String, company: String, phone: String) -> Unit
) {
    var nameText by rememberSaveable(initialSupplier?.id) { mutableStateOf(initialSupplier?.nameAr ?: "") }
    var companyText by rememberSaveable(initialSupplier?.id) { mutableStateOf(initialSupplier?.companyAr ?: "") }
    var phoneText by rememberSaveable(initialSupplier?.id) { mutableStateOf(initialSupplier?.phone ?: "") }

    val canSave = nameText.trim().length >= 2 && phoneText.trim().length >= 4

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = if (initialSupplier == null) {
                    lang.tr("إضافة مورد جديد", "New Supplier")
                } else {
                    lang.tr("تعديل بيانات المورد", "Edit Supplier")
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text(lang.tr("اسم المورد أو الشركة *", "Supplier Name *")) },
                    placeholder = { Text(lang.tr("مثال: شركة القدس للتوزيع", "e.g. Al-Quds Distribution")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_editor_name_input")
                )
                OutlinedTextField(
                    value = companyText,
                    onValueChange = { companyText = it },
                    label = { Text(lang.tr("اسم الشركة التجاري / النشاط", "Company / Trade Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_editor_company_input")
                )
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("هاتف المندوب أو الشركة *", "Representative Phone *")) },
                    placeholder = { Text("059X-XXXXXX") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFontFamily,
                        textDirection = TextDirection.Ltr
                    ),
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
                        onSave(nameText.trim(), companyText.trim(), phoneText.trim())
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_save_supplier_button")
            ) {
                Text(
                    text = lang.tr("حفظ بيانات المورد", "Save Supplier"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

/**
 * Single-SKU physical receiving confirmation dialog (GRN).
 * Bounded inspection contract dynamically linked to bill.expectedProductId and bill.expectedQuantity.
 */
@Composable
internal fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<com.example.model.Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrn: (productId: String, receivedQty: Int, batchNote: String) -> Unit,
    errorMessage: String? = null
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
        shape = RoundedCornerShape(12.dp),
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
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("goods_receipt_error"))
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TraderTheme.financial.incomeContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "يطابق هذا السند الصنف والكمية المسجلين في الفاتورة لمرة واحدة فقط لزيادة رصيد المخزن الفعلي.",
                            "Verifies expected item and quantity to increase physical inventory without changing supplier debt."
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
                        label = { Text(lang.tr("ملاحظة الاستلام المخزني", "GRN Batch Note")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_note_input")
                    )
                } else {
                    Text(
                        text = lang.tr(
                            "هذه الفاتورة لا تحتوي على صنف محدد قابل للتتبع المخزني.",
                            "This bill does not have a linked stock-tracked product."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canConfirm && expectedProduct != null) {
                        onConfirmGrn(expectedProduct.id, expectedQty, batchNote)
                    }
                },
                enabled = canConfirm,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_grn_submit_button")
            ) {
                Text(
                    text = lang.tr("تثبيت الاستلام المخزني", "Confirm Goods Receipt"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
private fun PurchaseReturnPreviewDialog(
    supplier: Supplier,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("إرجاع بضاعة للمورد (معاينة)", "Purchase Return Preview"),
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
                        lang.tr("رصيد الذمة الحالي: ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}", "Current balance: ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}")
                    }",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = lang.tr(
                        "تتيح ميزة إرجاع المشتريات إعادة البضاعة التالفة أو غير المطابقة وخصم قيمتها من ذمة المورد تلقائياً.",
                        "Purchase returns allow returning damaged items and deducting value from supplier balance."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("close_purchase_return_preview_button")
            ) {
                Text(lang.tr("إغلاق", "Close"))
            }
        }
    )
}

/**
 * Public composable dialog for recording a new financial purchase bill.
 */
@Composable
fun ReceivePurchaseBillDialog(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onConfirmBill: (supplierId: String, itemsSummary: String, totalIls: Double, paidNowIls: Double) -> Unit
) {
    val lang = state.language
    val suppliers = state.suppliers
    if (suppliers.isEmpty()) return

    var selectedSupplierId by rememberSaveable(state.selectedSupplierForDetail?.id) {
        mutableStateOf(state.selectedSupplierForDetail?.id ?: suppliers.first().id)
    }
    var purchaseDesc by rememberSaveable { mutableStateOf("") }
    var purchaseTotal by rememberSaveable { mutableStateOf("") }
    var purchasePaid by rememberSaveable { mutableStateOf("0") }

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
        shape = RoundedCornerShape(12.dp),
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

                // Supplier Picker Chips
                Text(
                    text = lang.tr("اختر المورد:", "Select Supplier:"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(suppliers, key = { it.id }) { s ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = s.id == selectedSupplierId,
                                onClick = { selectedSupplierId = s.id },
                                label = { Text(s.name(lang)) },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = purchaseDesc,
                    onValueChange = { purchaseDesc = it },
                    label = { Text(lang.tr("بيان الفاتورة / الأصناف *", "Bill Summary / Items *")) },
                    placeholder = { Text(lang.tr("مثال: فاتورة بقوليات وأرز 25 كغم", "e.g. Rice & Grains 25kg")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_summary_input")
                )

                OutlinedTextField(
                    value = purchaseTotal,
                    onValueChange = { purchaseTotal = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("إجمالي الفاتورة (₪) *", "Bill Total (₪) *")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_total_input")
                )

                OutlinedTextField(
                    value = purchasePaid,
                    onValueChange = { purchasePaid = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("المدفوع نقداً الآن من الصندوق (₪)", "Paid Cash Now (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_paid_input")
                )

                if (totalVal > 0.0 && isPaidValid) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "يُقيد ذمة للمورد: ${StitchBidiUtils.formatIls(payableAdded)} • يُصرف من الصندوق: ${StitchBidiUtils.formatIls(paidVal)}",
                                "Payable added: ${StitchBidiUtils.formatIls(payableAdded)} • Disbursed: ${StitchBidiUtils.formatIls(paidVal)}"
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (payableAdded > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isFormValid) {
                        onConfirmBill(selectedSupplierId, purchaseDesc.trim(), totalVal, paidVal)
                    }
                },
                enabled = isFormValid,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_purchase_bill_button")
            ) {
                Text(
                    text = lang.tr("قيد الفاتورة", "Record Bill"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}
