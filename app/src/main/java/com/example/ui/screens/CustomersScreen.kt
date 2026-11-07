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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.CustomerTransaction
import com.example.model.InvoiceRecord
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

private enum class CustomerBalanceFilter {
    ALL,
    DEBTORS,
    ADVANCE,
    SETTLED
}

/**
 * Native Material3 Customer Directory & Accounts Receivable Ledger Screen (دفتر الديون).
 *
 * Design Invariants Enforced:
 * 1. Strict Un-Netted Accounting: Separates Total Debts Owed to Store (Receivables)
 *    from Customer Advance Deposits (Store Liabilities), never netting them together.
 * 2. Instant Search & Multi-Axis Filtering: Live normalization across Arabic/English names,
 *    LTR phone numbers, and addresses, with counts on all filter chips.
 * 3. Rapid Debt Collection: One-tap quick collection button on debtor rows with 48dp hit targets.
 * 4. Responsive & Accessible: Strict 48dp touch targets on chips, buttons, and inputs.
 * 5. Bidi Polish: Western digits (0-9) and LTR monospace phone formatting inside Arabic RTL layout.
 */
@Composable
fun CustomersScreen(
    state: TraderUiState,
    onCustomerClick: (Customer) -> Unit,
    onOpenAddCustomerDialog: () -> Unit,
    onStartSaleForCustomer: (Customer) -> Unit,
    onQuickCollectPayment: ((Customer) -> Unit)? = null,
    onDismissFeedback: (() -> Unit)? = null
) {
    val lang = state.language
    val financial = TraderTheme.financial
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(CustomerBalanceFilter.ALL) }

    val debtorsCount = remember(state.customers) { state.customers.count { it.balanceIls > 0.005 } }
    val advanceCount = remember(state.customers) { state.customers.count { it.balanceIls < -0.005 } }
    val settledCount = remember(state.customers) { state.customers.count { abs(it.balanceIls) <= 0.005 } }

    val filteredCustomers = remember(state.customers, searchQuery, selectedFilter) {
        val q = TraderUiState.normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
        state.customers.filter { c ->
            val matchesFilter = when (selectedFilter) {
                CustomerBalanceFilter.ALL -> true
                CustomerBalanceFilter.DEBTORS -> c.balanceIls > 0.005
                CustomerBalanceFilter.ADVANCE -> c.balanceIls < -0.005
                CustomerBalanceFilter.SETTLED -> abs(c.balanceIls) <= 0.005
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true

            TraderUiState.normalizeForSearch(c.nameAr).contains(q) ||
                TraderUiState.normalizeForSearch(c.nameEn).contains(q) ||
                TraderUiState.normalizeForSearch(c.addressAr).contains(q) ||
                TraderUiState.normalizeForSearch(c.addressEn).contains(q) ||
                c.phone.replace("-", "").contains(q.replace("-", "")) ||
                c.phone.contains(q)
        }
    }

    val feedbackMessage = lang.tr(
        state.lastCustomerPaymentMessageAr ?: "",
        state.lastCustomerPaymentMessageEn ?: ""
    ).takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("customers_screen")
    ) {
        // Sticky Header: Daftar Al-Deyoun Summary + Search + Balance Filters
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Summary Strip: Owed to Store (Debts) vs Customer Advances + New Customer CTA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.currentSession.canViewReports()) Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = lang.tr("لنا عند الزبائن (ديون)", "Owed to Store"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.totalCustomerDebtsIls),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = financial.debit,
                                modifier = Modifier.testTag("total_customer_debts_value")
                            )
                        }

                        Column {
                            Text(
                                text = lang.tr("للزبائن عندنا (مقدم)", "Customer Advances"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.totalCustomerAdvancesIls),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (state.totalCustomerAdvancesIls > 0.005) {
                                    financial.income
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.testTag("total_customer_advances_value")
                            )
                        }
                    }

                    if (!state.currentSession.canViewReports()) Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onOpenAddCustomerDialog,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("add_customer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("زبون جديد", "New Customer"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                if (feedbackMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = financial.incomeContainer,
                        border = BorderStroke(1.dp, financial.income.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_action_feedback_banner")
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
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = financial.income,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = financial.income
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
                                        tint = financial.income,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Instant Search by Name, Phone, or Neighborhood
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_search_input"),
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "ابحث باسم الزبون، رقم الجوال أو المنطقة...",
                                "Search by customer name, phone, or area..."
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
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background
                    )
                )

                // Balance Filter Chips (All / Debtors / In Credit / Settled)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == CustomerBalanceFilter.ALL,
                                onClick = { selectedFilter = CustomerBalanceFilter.ALL },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "الكل (${WesternFormatters.formatInt(state.customers.size)})",
                                            "All (${WesternFormatters.formatInt(state.customers.size)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == CustomerBalanceFilter.ALL) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("customer_filter_ALL")
                            )
                        }
                    }
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == CustomerBalanceFilter.DEBTORS,
                                onClick = { selectedFilter = CustomerBalanceFilter.DEBTORS },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "عليهم دين (${WesternFormatters.formatInt(debtorsCount)})",
                                            "Owes Store (${WesternFormatters.formatInt(debtorsCount)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == CustomerBalanceFilter.DEBTORS) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = financial.debit,
                                    selectedLabelColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.testTag("customer_filter_DEBTORS")
                            )
                        }
                    }
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == CustomerBalanceFilter.ADVANCE,
                                onClick = { selectedFilter = CustomerBalanceFilter.ADVANCE },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "لهم رصيد مقدم (${WesternFormatters.formatInt(advanceCount)})",
                                            "In Credit (${WesternFormatters.formatInt(advanceCount)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == CustomerBalanceFilter.ADVANCE) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = financial.income,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("customer_filter_ADVANCE")
                            )
                        }
                    }
                    item {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = selectedFilter == CustomerBalanceFilter.SETTLED,
                                onClick = { selectedFilter = CustomerBalanceFilter.SETTLED },
                                label = {
                                    Text(
                                        text = lang.tr(
                                            "خالص (${WesternFormatters.formatInt(settledCount)})",
                                            "Settled (${WesternFormatters.formatInt(settledCount)})"
                                        ),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selectedFilter == CustomerBalanceFilter.SETTLED) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                modifier = Modifier.testTag("customer_filter_SETTLED")
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        if (filteredCustomers.isEmpty()) {
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
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedFilter != CustomerBalanceFilter.ALL) {
                            lang.tr("لا يوجد زبائن مطابقين للبحث أو التصفية", "No matching customers found")
                        } else {
                            lang.tr("لا يوجد زبائن مسجلين حالياً", "No registered customers yet")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (searchQuery.isNotBlank() || selectedFilter != CustomerBalanceFilter.ALL) {
                        TextButton(
                            onClick = {
                                searchQuery = ""
                                selectedFilter = CustomerBalanceFilter.ALL
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = lang.tr("عرض كل الزبائن", "Show All Customers"),
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
                    .testTag("customers_directory_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredCustomers, key = { it.id }) { customer ->
                    CustomerDirectoryRow(
                        customer = customer,
                        lang = lang,
                        onClick = { onCustomerClick(customer) },
                        onQuickCollect = if (onQuickCollectPayment != null && customer.balanceIls > 0.005) {
                            { onQuickCollectPayment(customer) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerDirectoryRow(
    customer: Customer,
    lang: AppLanguage,
    onClick: () -> Unit,
    onQuickCollect: (() -> Unit)?
) {
    val financial = TraderTheme.financial
    val isDebtor = customer.balanceIls > 0.005
    val isAdvance = customer.balanceIls < -0.005

    val amountColor = when {
        isDebtor -> financial.debit
        isAdvance -> financial.income
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusTextColor = when {
        isDebtor -> financial.onDebitContainer
        isAdvance -> financial.onIncomeContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusBadgeBg = when {
        isDebtor -> financial.debitContainer
        isAdvance -> financial.incomeContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val statusLabel = when {
        isDebtor -> lang.tr("عليه دين", "Owes Store")
        isAdvance -> lang.tr("له رصيد مقدم", "In Credit")
        else -> lang.tr("خالص", "Settled")
    }

    val displayedAmount = when {
        isDebtor -> WesternFormatters.formatIls(customer.balanceIls)
        isAdvance -> WesternFormatters.formatIls(-customer.balanceIls)
        else -> WesternFormatters.formatIls(0.0)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("customer_row_${customer.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left/Start: Name + LTR Phone + Neighborhood
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = customer.name(lang),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = customer.phone,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        text = customer.address(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Right/End: Balance Figure + Status Badge + Optional Quick Collect
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        color = statusBadgeBg
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = statusTextColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                if (onQuickCollect != null) {
                    FilledTonalButton(
                        onClick = onQuickCollect,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("quick_collect_${customer.id}")
                    ) {
                        Text(
                            text = lang.tr("قبض", "Collect"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Customer Detailed Profile & Statement Ledger Screen (كشف حساب الزبون).
 */
@Composable
fun CustomerDetailScreen(
    state: TraderUiState,
    onBack: () -> Unit,
    onOpenPaymentDialog: () -> Unit,
    onStartSaleForCustomer: (Customer) -> Unit,
    onOpenInvoice: ((InvoiceRecord) -> Unit)? = null,
    onDismissFeedback: (() -> Unit)? = null,
    onUpdateCustomer: ((customerId: String, name: String, phone: String, address: String, creditLimitIls: Double) -> Unit)? = null,
    onFormatStatementText: ((String) -> String)? = null
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val customer = state.selectedCustomerForDetail ?: return
    val transactions = remember(state.customerTransactions, customer.id) {
        state.customerTransactions.filter { it.customerId == customer.id }
    }
    var isEditCustomerOpen by rememberSaveable(customer.id) { mutableStateOf(false) }
    var isStatementPreviewOpen by rememberSaveable(customer.id) { mutableStateOf(false) }

    val isDebtor = customer.balanceIls > 0.005
    val isAdvance = customer.balanceIls < -0.005
    val availableCreditIls = max(0.0, customer.creditLimitIls - max(0.0, customer.balanceIls))

    val feedbackMessage = lang.tr(
        state.lastCustomerPaymentMessageAr ?: "",
        state.lastCustomerPaymentMessageEn ?: ""
    ).takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("customer_detail_screen")
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (feedbackMessage != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = financial.incomeContainer,
                        border = BorderStroke(1.dp, financial.income.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_payment_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = financial.income,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = financial.income
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
                                        tint = financial.income,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Customer Profile, Balance Status & Actions Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Customer Name + LTR Phone + Address
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = customer.name(lang),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Text(
                                            text = customer.phone,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = customer.address(lang),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        // Clear Balance Status Banner (Debtor vs Advance Credit vs Settled)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isDebtor -> financial.debitContainer
                                isAdvance -> financial.incomeContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isDebtor -> financial.debit.copy(alpha = 0.35f)
                                    isAdvance -> financial.income.copy(alpha = 0.35f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
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
                                            isDebtor -> lang.tr("عليه للمحل (دين مستحق)", "Owes Store (Outstanding Debt)")
                                            isAdvance -> lang.tr("له عند المحل (رصيد مقدم / أمانة)", "Customer Advance (In Credit)")
                                            else -> lang.tr("حالة الحساب", "Account Status")
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = when {
                                            isDebtor -> financial.debit
                                            isAdvance -> financial.income
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        text = when {
                                            isDebtor -> lang.tr(
                                                "سقف الدين: ${WesternFormatters.formatIls(customer.creditLimitIls)} • متاح للشراء الآجل: ${WesternFormatters.formatIls(availableCreditIls)}",
                                                "Limit: ${WesternFormatters.formatIls(customer.creditLimitIls)} • Available credit: ${WesternFormatters.formatIls(availableCreditIls)}"
                                            )
                                            isAdvance -> lang.tr(
                                                "يُخصم تلقائياً من مشترياته القادمة • السقف: ${WesternFormatters.formatIls(customer.creditLimitIls)}",
                                                "Applied to future purchases • Credit limit: ${WesternFormatters.formatIls(customer.creditLimitIls)}"
                                            )
                                            else -> lang.tr(
                                                "لا يوجد دين عليه ولا رصيد مقدم • سقف الدين المتاح: ${WesternFormatters.formatIls(customer.creditLimitIls)}",
                                                "Account is settled • Available credit limit: ${WesternFormatters.formatIls(customer.creditLimitIls)}"
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
                                            isDebtor -> WesternFormatters.formatIls(customer.balanceIls)
                                            isAdvance -> WesternFormatters.formatIls(-customer.balanceIls)
                                            else -> WesternFormatters.formatIls(0.0)
                                        },
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = when {
                                            isDebtor -> financial.debit
                                            isAdvance -> financial.income
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.testTag("customer_detail_balance_value")
                                    )
                                    Text(
                                        text = when {
                                            isDebtor -> lang.tr("مطلوب منه", "Owes")
                                            isAdvance -> lang.tr("رصيد له", "In Credit")
                                            else -> lang.tr("خالص", "Settled")
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = when {
                                            isDebtor -> financial.debit
                                            isAdvance -> financial.income
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }

                        // Secondary Metric Row: Total Historical Purchases
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr("إجمالي المشتريات المسجلة:", "Total Recorded Purchases:"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(customer.totalPurchasesIls),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Primary Actions: Collect Payment & Start Sale for Customer (min 48dp height)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenPaymentDialog,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1.15f)
                                    .height(48.dp)
                                    .testTag("collect_payment_button")
                            ) {
                                Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isDebtor) {
                                        lang.tr("قبض دفعة من الحساب", "Collect Payment")
                                    } else {
                                        lang.tr("إيداع دفعة بالحساب", "Record Deposit")
                                    },
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            OutlinedButton(
                                onClick = { onStartSaleForCustomer(customer) },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("customer_new_sale_button")
                            ) {
                                Icon(Icons.Default.PointOfSale, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = lang.tr("بيع جديد للزبون", "New Sale"),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }

                        // Secondary Actions: Edit Customer & Share/Print Statement (min 48dp height)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (onUpdateCustomer != null) {
                                OutlinedButton(
                                    onClick = { isEditCustomerOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("edit_customer_button")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = lang.tr("تعديل البيانات والسقف", "Edit Info & Limit"),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { isStatementPreviewOpen = true },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("share_customer_statement_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = lang.tr("مشاركة / كشف الحساب", "Share / Statement"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Customer Statement Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr("دفتر حركات الزبون", "Account Statement"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lang.tr(
                            "${WesternFormatters.formatInt(transactions.size)} حركة",
                            "${WesternFormatters.formatInt(transactions.size)} entries"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (transactions.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "لا توجد حركات سابقة مسجلة لهذا الزبون بعد.",
                                "No transactions recorded for this customer yet."
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(transactions, key = { it.id }) { tx ->
                    val linkedInvoice = remember(tx.invoiceNumber, state.invoices) {
                        tx.invoiceNumber?.let { num -> state.invoices.firstOrNull { it.invoiceNumber == num } }
                    }
                    CustomerStatementRow(
                        tx = tx,
                        lang = lang,
                        onOpenInvoice = if (linkedInvoice != null && onOpenInvoice != null) {
                            { onOpenInvoice(linkedInvoice) }
                        } else null
                    )
                }
            }
        }
    }

    if (isEditCustomerOpen && onUpdateCustomer != null) {
        EditCustomerDialog(
            customer = customer,
            lang = lang,
            onDismiss = { isEditCustomerOpen = false },
            onConfirmUpdate = { name, phone, address, creditLimitIls ->
                onUpdateCustomer(customer.id, name, phone, address, creditLimitIls)
                isEditCustomerOpen = false
            }
        )
    }

    if (isStatementPreviewOpen) {
        val statementText = onFormatStatementText?.invoke(customer.id) ?: buildString {
            appendLine("${lang.tr("كشف حساب الزبون:", "Customer Statement:")} ${customer.name(lang)}")
            appendLine("${lang.tr("الرصيد الحالي:", "Current Balance:")} ${WesternFormatters.formatIls(customer.balanceIls)}")
        }
        CustomerStatementShareDialog(
            customer = customer,
            statementText = statementText,
            lang = lang,
            onDismiss = { isStatementPreviewOpen = false }
        )
    }
}

@Composable
private fun EditCustomerDialog(
    customer: Customer,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmUpdate: (name: String, phone: String, address: String, creditLimitIls: Double) -> Unit
) {
    var nameText by rememberSaveable(customer.id) { mutableStateOf(customer.nameAr) }
    var phoneText by rememberSaveable(customer.id) { mutableStateOf(customer.phone) }
    var addressText by rememberSaveable(customer.id) { mutableStateOf(customer.addressAr) }
    var limitText by remember(customer.id) {
        mutableStateOf(WesternFormatters.formatNumber(customer.creditLimitIls).removeSuffix(".00"))
    }

    val parsedLimit = limitText.toDoubleOrNull() ?: customer.creditLimitIls
    val canSave = nameText.trim().length >= 2 && parsedLimit >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr("تعديل بيانات الزبون وسقف الائتمان", "Edit Customer & Credit Limit"),
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
                    label = { Text(lang.tr("اسم الزبون أو المنشأة *", "Customer Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_customer_name_input")
                )
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("رقم الجوال", "Phone Number")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFontFamily,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_customer_phone_input")
                )
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    label = { Text(lang.tr("العنوان / المنطقة", "Neighborhood / Address")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_customer_address_input")
                )
                OutlinedTextField(
                    value = limitText,
                    onValueChange = {
                        limitText = WesternFormatters.sanitizeWesternDigits(it)
                            .filter { ch -> ch.isDigit() || ch == '.' }
                    },
                    label = { Text(lang.tr("سقف الدين المسموح (₪)", "Credit Limit (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_customer_limit_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        onConfirmUpdate(nameText.trim(), phoneText.trim(), addressText.trim(), parsedLimit)
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
                    .testTag("confirm_edit_customer_button")
            ) {
                Text(
                    text = lang.tr("حفظ التعديلات", "Save Changes"),
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
private fun CustomerStatementShareDialog(
    customer: Customer,
    statementText: String,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr("مشاركة وطباعة كشف حساب الزبون", "Share & Print Customer Statement"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = lang.tr(
                        "نص كشف الحساب جاهز للنسخ والمشاركة عبر واتساب أو الطباعة الحرارية المحاكاة:",
                        "Statement text ready to copy/share via WhatsApp or simulated thermal print:"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statementText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("customer_statement_preview_text")
                    )
                }
                if (copied) {
                    Text(
                        text = lang.tr("تم نسخ كشف حساب ${customer.name(lang)} للحافظة", "Statement copied to clipboard"),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TraderTheme.financial.income
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(statementText))
                    copied = true
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("copy_customer_statement_button")
            ) {
                Text(
                    text = lang.tr("نسخ الكشف للمشاركة", "Copy Statement"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إغلاق", "Close"))
            }
        }
    )
}

@Composable
private fun CustomerStatementRow(
    tx: CustomerTransaction,
    lang: AppLanguage,
    onOpenInvoice: (() -> Unit)?
) {
    val financial = TraderTheme.financial
    val isCreditPurchase = tx.debitIls > 0.005 && tx.creditIls <= 0.005
    val isPaidInvoice = tx.debitIls > 0.005 && tx.creditIls >= tx.debitIls - 0.005
    val isPaymentCollection = tx.creditIls > 0.005 && tx.debitIls <= 0.005

    val runningBal = tx.runningBalanceIls
    val runningBalLabel = when {
        runningBal > 0.005 -> lang.tr(
            "الرصيد بعدها: عليه ${WesternFormatters.formatIls(runningBal)}",
            "Balance after: Owes ${WesternFormatters.formatIls(runningBal)}"
        )
        runningBal < -0.005 -> lang.tr(
            "الرصيد بعدها: له ${WesternFormatters.formatIls(-runningBal)}",
            "Balance after: In credit ${WesternFormatters.formatIls(-runningBal)}"
        )
        else -> lang.tr("الرصيد بعدها: خالص (0.00 ₪)", "Balance after: Settled (0.00 ₪)")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("customer_tx_${tx.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = tx.type(lang),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = tx.date,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tx.note.isNotBlank()) {
                        Text(
                            text = tx.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when {
                        isCreditPurchase -> {
                            Text(
                                text = "+ ${WesternFormatters.formatIls(tx.debitIls)}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = financial.debit
                            )
                            Text(
                                text = lang.tr("دين مضاف", "Added to Debt"),
                                style = MaterialTheme.typography.labelSmall,
                                color = financial.debit
                            )
                        }
                        isPaymentCollection -> {
                            Text(
                                text = "- ${WesternFormatters.formatIls(tx.creditIls)}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = financial.income
                            )
                            Text(
                                text = lang.tr("دفعة مقبوضة", "Payment Received"),
                                style = MaterialTheme.typography.labelSmall,
                                color = financial.income
                            )
                        }
                        isPaidInvoice -> {
                            Text(
                                text = WesternFormatters.formatIls(tx.debitIls),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr("مسددة نقداً", "Paid Cash"),
                                style = MaterialTheme.typography.labelSmall,
                                color = financial.income
                            )
                        }
                        else -> {
                            if (tx.debitIls > 0) {
                                Text(
                                    text = "+ ${WesternFormatters.formatIls(tx.debitIls)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.debit
                                )
                            }
                            if (tx.creditIls > 0) {
                                Text(
                                    text = "- ${WesternFormatters.formatIls(tx.creditIls)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.income
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = runningBalLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (onOpenInvoice != null && tx.invoiceNumber != null) {
                    TextButton(
                        onClick = onOpenInvoice,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr(
                                "عرض الفاتورة ${WesternFormatters.formatInvoiceCode(tx.invoiceNumber)}",
                                "View ${WesternFormatters.formatInvoiceCode(tx.invoiceNumber)}"
                            ),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerPaymentCollectionDialog(
    customer: Customer,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmPayment: (customerId: String, amountIls: Double, note: String, paymentId: String) -> Unit
) {
    val financial = TraderTheme.financial
    val initialAmt = if (customer.balanceIls > 0.005) {
        WesternFormatters.formatNumber(customer.balanceIls).removeSuffix(".00")
    } else {
        ""
    }
    var payAmountText by rememberSaveable(customer.id) { mutableStateOf(initialAmt) }
    var payNoteText by rememberSaveable(customer.id) { mutableStateOf("") }
    // Deterministic operation ID per dialog open session to prevent accidental double-tap replay
    val operationId = rememberSaveable(customer.id) { "cpay_${customer.id}_${System.currentTimeMillis()}" }

    val enteredAmount = payAmountText.toDoubleOrNull() ?: 0.0
    val projectedBalance = remember(customer.balanceIls, enteredAmount) {
        BigDecimal(customer.balanceIls.toString())
            .subtract(BigDecimal(enteredAmount.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = if (customer.balanceIls > 0.005) {
                    lang.tr("قبض دفعة نقدية من الزبون", "Collect Cash Payment")
                } else {
                    lang.tr("إيداع دفعة مقدمة للزبون", "Record Customer Advance")
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Customer Identity & Current Balance Summary
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = customer.name(lang),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = when {
                                customer.balanceIls > 0.005 -> lang.tr(
                                    "الدين الحالي عليه: ${WesternFormatters.formatIls(customer.balanceIls)}",
                                    "Current debt owed: ${WesternFormatters.formatIls(customer.balanceIls)}"
                                )
                                customer.balanceIls < -0.005 -> lang.tr(
                                    "له رصيد مقدم حالياً: ${WesternFormatters.formatIls(-customer.balanceIls)}",
                                    "Current advance credit: ${WesternFormatters.formatIls(-customer.balanceIls)}"
                                )
                                else -> lang.tr(
                                    "رصيد الحساب الحالي: خالص (0.00 ₪)",
                                    "Current balance: Settled (0.00 ₪)"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                customer.balanceIls > 0.005 -> financial.debit
                                customer.balanceIls < -0.005 -> financial.income
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Quick Preset Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (customer.balanceIls > 0.005) {
                        val fullStr = WesternFormatters.formatNumber(customer.balanceIls).removeSuffix(".00")
                        item {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                FilterChip(
                                    selected = payAmountText == fullStr || abs(enteredAmount - customer.balanceIls) < 0.005,
                                    onClick = { payAmountText = fullStr },
                                    label = {
                                        Text(
                                            text = lang.tr(
                                                "كامل الدين (${WesternFormatters.formatIls(customer.balanceIls)})",
                                                "Full Debt (${WesternFormatters.formatIls(customer.balanceIls)})"
                                            ),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.testTag("collect_preset_full")
                                )
                            }
                        }
                    }
                    items(listOf(50, 100, 200)) { preset ->
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

                OutlinedTextField(
                    value = payAmountText,
                    onValueChange = { payAmountText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("المبلغ المقبوض نقداً (₪)", "Cash Collected (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_amount_input")
                )

                // Live Balance Consequence Preview
                if (enteredAmount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (projectedBalance > 0.005) {
                            financial.warningContainer
                        } else {
                            financial.incomeContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (projectedBalance > 0.005) financial.warning.copy(alpha = 0.35f) else financial.income.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("payment_projected_balance_banner")
                    ) {
                        Text(
                            text = when {
                                projectedBalance > 0.005 -> lang.tr(
                                    "بعد قبض ${WesternFormatters.formatIls(enteredAmount)}: يتبقى عليه دين ${WesternFormatters.formatIls(projectedBalance)}",
                                    "After collecting ${WesternFormatters.formatIls(enteredAmount)}: Remaining debt ${WesternFormatters.formatIls(projectedBalance)}"
                                )
                                projectedBalance < -0.005 -> lang.tr(
                                    "بعد قبض ${WesternFormatters.formatIls(enteredAmount)}: يصبح له رصيد مقدم (أمانة) ${WesternFormatters.formatIls(-projectedBalance)}",
                                    "After collecting ${WesternFormatters.formatIls(enteredAmount)}: Customer advance credit of ${WesternFormatters.formatIls(-projectedBalance)}"
                                )
                                else -> lang.tr(
                                    "بعد قبض ${WesternFormatters.formatIls(enteredAmount)}: يصبح حساب الزبون خالصاً تماماً (0.00 ₪)",
                                    "After collecting ${WesternFormatters.formatIls(enteredAmount)}: Account becomes completely settled (0.00 ₪)"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (projectedBalance > 0.005) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                financial.income
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = payNoteText,
                    onValueChange = { payNoteText = it },
                    label = { Text(lang.tr("ملاحظة (اختياري)", "Note (Optional)")) },
                    placeholder = { Text(lang.tr("مثال: دفعة نقدية بالمحل", "e.g. Cash paid at counter")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_note_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmPayment(customer.id, enteredAmount, payNoteText, operationId)
                },
                enabled = enteredAmount > 0.0,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_customer_payment_button")
            ) {
                Text(
                    text = lang.tr("تأكيد القبض بالصندوق", "Confirm Collection"),
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
fun AddCustomerDialog(
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, address: String) -> Unit
) {
    var newCustName by rememberSaveable { mutableStateOf("") }
    var newCustPhone by rememberSaveable { mutableStateOf("") }
    var newCustAddr by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr("إضافة زبون جديد", "New Customer"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = newCustName,
                    onValueChange = { newCustName = it },
                    label = { Text(lang.tr("اسم الزبون أو المنشأة *", "Customer / Business Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_customer_name_input")
                )
                OutlinedTextField(
                    value = newCustPhone,
                    onValueChange = { newCustPhone = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("رقم الجوال (059...)", "Phone Number (059...)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = MonoFontFamily,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_customer_phone_input")
                )
                OutlinedTextField(
                    value = newCustAddr,
                    onValueChange = { newCustAddr = it },
                    label = { Text(lang.tr("المنطقة / العنوان", "Neighborhood / Address")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_customer_address_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newCustName.trim(), newCustPhone.trim(), newCustAddr.trim()) },
                enabled = newCustName.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_add_customer_button")
            ) {
                Text(
                    text = lang.tr("حفظ الزبون", "Save Customer"),
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
