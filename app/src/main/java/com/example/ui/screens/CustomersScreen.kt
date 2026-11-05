package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
    var searchQuery by remember { mutableStateOf("") }
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
            .testTag("customers_screen")
    ) {
        // Sticky Header: Daftar Al-Deyoun Summary + Search + Balance Filters
        Surface(
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Summary Strip: Owed to Store (Debts) vs Customer Advances + New Customer CTA
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
                                text = lang.tr("لنا عند الزبائن (ديون)", "Owed to Store"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.totalCustomerDebtsIls),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TraderTheme.financial.debit,
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
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (state.totalCustomerAdvancesIls > 0.005) {
                                    TraderTheme.financial.income
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.testTag("total_customer_advances_value")
                            )
                        }
                    }

                    Button(
                        onClick = onOpenAddCustomerDialog,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("add_customer_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("زبون جديد", "New Customer"),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                if (feedbackMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = TraderTheme.financial.incomeContainer,
                        border = BorderStroke(1.dp, TraderTheme.financial.income.copy(alpha = 0.35f)),
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
                                    tint = TraderTheme.financial.income,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = TraderTheme.financial.income
                                )
                            }
                            if (onDismissFeedback != null) {
                                IconButton(
                                    onClick = onDismissFeedback,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = lang.tr("إغلاق", "Dismiss"),
                                        modifier = Modifier.size(16.dp)
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

                // Balance Filter Chips (All / Debtors / In Credit / Settled)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFilter == CustomerBalanceFilter.ALL,
                            onClick = { selectedFilter = CustomerBalanceFilter.ALL },
                            label = {
                                Text(
                                    lang.tr(
                                        "الكل (${WesternFormatters.formatInt(state.customers.size)})",
                                        "All (${WesternFormatters.formatInt(state.customers.size)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("customer_filter_ALL")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == CustomerBalanceFilter.DEBTORS,
                            onClick = { selectedFilter = CustomerBalanceFilter.DEBTORS },
                            label = {
                                Text(
                                    lang.tr(
                                        "عليهم دين (${WesternFormatters.formatInt(debtorsCount)})",
                                        "Owes Store (${WesternFormatters.formatInt(debtorsCount)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("customer_filter_DEBTORS")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == CustomerBalanceFilter.ADVANCE,
                            onClick = { selectedFilter = CustomerBalanceFilter.ADVANCE },
                            label = {
                                Text(
                                    lang.tr(
                                        "لهم رصيد مقدم (${WesternFormatters.formatInt(advanceCount)})",
                                        "In Credit (${WesternFormatters.formatInt(advanceCount)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("customer_filter_ADVANCE")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedFilter == CustomerBalanceFilter.SETTLED,
                            onClick = { selectedFilter = CustomerBalanceFilter.SETTLED },
                            label = {
                                Text(
                                    lang.tr(
                                        "خالص (${WesternFormatters.formatInt(settledCount)})",
                                        "Settled (${WesternFormatters.formatInt(settledCount)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("customer_filter_SETTLED")
                        )
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedFilter != CustomerBalanceFilter.ALL) {
                            lang.tr("لا يوجد زبائن مطابقين للبحث أو التصفية", "No matching customers found")
                        } else {
                            lang.tr("لا يوجد زبائن مسجلين حالياً", "No registered customers yet")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (searchQuery.isNotBlank() || selectedFilter != CustomerBalanceFilter.ALL) {
                        TextButton(
                            onClick = {
                                searchQuery = ""
                                selectedFilter = CustomerBalanceFilter.ALL
                            }
                        ) {
                            Text(lang.tr("عرض كل الزبائن", "Show All Customers"))
                        }
                    }
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
    val isDebtor = customer.balanceIls > 0.005
    val isAdvance = customer.balanceIls < -0.005

    val balanceColor = when {
        isDebtor -> TraderTheme.financial.debit
        isAdvance -> TraderTheme.financial.income
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusBadgeBg = when {
        isDebtor -> TraderTheme.financial.debitContainer
        isAdvance -> TraderTheme.financial.incomeContainer
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
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("customer_row_${customer.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left/Start: Name + LTR Phone + Neighborhood
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = customer.name(lang),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
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
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = displayedAmount,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = balanceColor
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusBadgeBg
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = balanceColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                if (onQuickCollect != null) {
                    FilledTonalButton(
                        onClick = onQuickCollect,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("quick_collect_${customer.id}")
                    ) {
                        Text(
                            text = lang.tr("قبض", "Collect"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerDetailScreen(
    state: TraderUiState,
    onBack: () -> Unit,
    onOpenPaymentDialog: () -> Unit,
    onStartSaleForCustomer: (Customer) -> Unit,
    onOpenInvoice: ((InvoiceRecord) -> Unit)? = null,
    onDismissFeedback: (() -> Unit)? = null
) {
    val lang = state.language
    val customer = state.selectedCustomerForDetail ?: return
    val transactions = remember(state.customerTransactions, customer.id) {
        state.customerTransactions.filter { it.customerId == customer.id }
    }

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
            .testTag("customer_detail_screen")
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
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TraderTheme.financial.incomeContainer,
                        border = BorderStroke(1.dp, TraderTheme.financial.income.copy(alpha = 0.35f)),
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
                                    tint = TraderTheme.financial.income,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = TraderTheme.financial.income
                                )
                            }
                            if (onDismissFeedback != null) {
                                IconButton(
                                    onClick = onDismissFeedback,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = lang.tr("إغلاق", "Dismiss"),
                                        modifier = Modifier.size(16.dp)
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
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
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
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = customer.address(lang),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Clear Balance Status Banner (Debtor vs Advance Credit vs Settled)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isDebtor -> TraderTheme.financial.debitContainer
                                isAdvance -> TraderTheme.financial.incomeContainer
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
                                            isDebtor -> lang.tr("عليه للمحل (دين مستحق)", "Owes Store (Outstanding Debt)")
                                            isAdvance -> lang.tr("له عند المحل (رصيد مقدم / أمانة)", "Customer Advance (In Credit)")
                                            else -> lang.tr("حالة الحساب", "Account Status")
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = when {
                                            isDebtor -> TraderTheme.financial.debit
                                            isAdvance -> TraderTheme.financial.income
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
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = when {
                                            isDebtor -> TraderTheme.financial.debit
                                            isAdvance -> TraderTheme.financial.income
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
                                            isDebtor -> TraderTheme.financial.debit
                                            isAdvance -> TraderTheme.financial.income
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }

                        // Secondary Metric Row: Total Historical Purchases & Transaction Count
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
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Primary Actions: Collect Payment & Start Sale for Customer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenPaymentDialog,
                                shape = RoundedCornerShape(10.dp),
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
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            OutlinedButton(
                                onClick = { onStartSaleForCustomer(customer) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("customer_new_sale_button")
                            ) {
                                Icon(Icons.Default.PointOfSale, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = lang.tr("بيع جديد للزبون", "New Sale"),
                                    style = MaterialTheme.typography.labelLarge
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
                        shape = RoundedCornerShape(10.dp),
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
}

@Composable
private fun CustomerStatementRow(
    tx: CustomerTransaction,
    lang: AppLanguage,
    onOpenInvoice: (() -> Unit)?
) {
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
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("customer_tx_${tx.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TraderTheme.financial.debit
                            )
                            Text(
                                text = lang.tr("دين مضاف", "Added to Debt"),
                                style = MaterialTheme.typography.labelSmall,
                                color = TraderTheme.financial.debit
                            )
                        }
                        isPaymentCollection -> {
                            Text(
                                text = "- ${WesternFormatters.formatIls(tx.creditIls)}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = TraderTheme.financial.income
                            )
                            Text(
                                text = lang.tr("دفعة مقبوضة", "Payment Received"),
                                style = MaterialTheme.typography.labelSmall,
                                color = TraderTheme.financial.income
                            )
                        }
                        isPaidInvoice -> {
                            Text(
                                text = WesternFormatters.formatIls(tx.debitIls),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr("فاتورة مسددة نقداً", "Paid Cash at Sale"),
                                style = MaterialTheme.typography.labelSmall,
                                color = TraderTheme.financial.income
                            )
                        }
                        else -> {
                            if (tx.debitIls > 0) {
                                Text(
                                    text = "+ ${WesternFormatters.formatIls(tx.debitIls)}",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TraderTheme.financial.debit
                                )
                            }
                            if (tx.creditIls > 0) {
                                Text(
                                    text = "- ${WesternFormatters.formatIls(tx.creditIls)}",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = TraderTheme.financial.income
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = lang.tr(
                                "عرض الفاتورة ${WesternFormatters.formatInvoiceCode(tx.invoiceNumber)}",
                                "View ${WesternFormatters.formatInvoiceCode(tx.invoiceNumber)}"
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
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
    val initialAmt = if (customer.balanceIls > 0.005) {
        WesternFormatters.formatNumber(customer.balanceIls).removeSuffix(".00")
    } else {
        ""
    }
    var payAmountText by remember(customer.id) { mutableStateOf(initialAmt) }
    var payNoteText by remember(customer.id) { mutableStateOf("") }
    // Deterministic operation ID per dialog open session to prevent accidental double-tap replay
    val operationId = remember(customer.id) { "cpay_${customer.id}_${System.currentTimeMillis()}" }

    val enteredAmount = payAmountText.toDoubleOrNull() ?: 0.0
    val projectedBalance = remember(customer.balanceIls, enteredAmount) {
        BigDecimal(customer.balanceIls.toString())
            .subtract(BigDecimal(enteredAmount.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Customer Identity & Current Balance Summary
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = customer.name(lang),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
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
                                customer.balanceIls > 0.005 -> TraderTheme.financial.debit
                                customer.balanceIls < -0.005 -> TraderTheme.financial.income
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Quick Preset Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (customer.balanceIls > 0.005) {
                        val fullStr = WesternFormatters.formatNumber(customer.balanceIls).removeSuffix(".00")
                        item {
                            FilterChip(
                                selected = payAmountText == fullStr || abs(enteredAmount - customer.balanceIls) < 0.005,
                                onClick = { payAmountText = fullStr },
                                label = {
                                    Text(
                                        lang.tr(
                                            "كامل الدين (${WesternFormatters.formatIls(customer.balanceIls)})",
                                            "Full Debt (${WesternFormatters.formatIls(customer.balanceIls)})"
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("collect_preset_full")
                            )
                        }
                    }
                    items(listOf(50, 100, 200)) { preset ->
                        FilterChip(
                            selected = payAmountText == preset.toString(),
                            onClick = { payAmountText = preset.toString() },
                            label = { Text("$preset ₪") }
                        )
                    }
                }

                OutlinedTextField(
                    value = payAmountText,
                    onValueChange = { payAmountText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("المبلغ المقبوض نقداً (₪)", "Cash Collected (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_amount_input")
                )

                // Live Balance Consequence Preview
                if (enteredAmount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (projectedBalance > 0.005) {
                            TraderTheme.financial.warningContainer
                        } else {
                            TraderTheme.financial.incomeContainer
                        },
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
                                TraderTheme.financial.income
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = payNoteText,
                    onValueChange = { payNoteText = it },
                    label = { Text(lang.tr("ملاحظة (اختياري)", "Note (Optional)")) },
                    placeholder = { Text(lang.tr("مثال: دفعة نقدية بالمحل", "e.g. Cash paid at counter")) },
                    singleLine = true,
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
                modifier = Modifier.testTag("confirm_customer_payment_button")
            ) {
                Text(lang.tr("تأكيد القبض بالصندوق", "Confirm Collection"))
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
fun AddCustomerDialog(
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, address: String) -> Unit
) {
    var newCustName by remember { mutableStateOf("") }
    var newCustPhone by remember { mutableStateOf("") }
    var newCustAddr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = lang.tr("إضافة زبون جديد لدفتر الحسابات", "Add Customer to Ledger"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newCustName,
                    onValueChange = { newCustName = it },
                    label = { Text(lang.tr("اسم الزبون أو المنشأة", "Customer / Business Name")) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_customer_name_input")
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = newCustPhone,
                        onValueChange = { newCustPhone = WesternFormatters.sanitizeWesternDigits(it) },
                        label = { Text(lang.tr("رقم الجوال (059...)", "Phone Number (059...)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_customer_phone_input")
                    )
                }
                OutlinedTextField(
                    value = newCustAddr,
                    onValueChange = { newCustAddr = it },
                    label = { Text(lang.tr("المنطقة / العنوان", "Neighborhood / Address")) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_customer_address_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newCustName, newCustPhone, newCustAddr) },
                enabled = newCustName.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_customer_button")
            ) {
                Text(lang.tr("حفظ الزبون", "Save Customer"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}
