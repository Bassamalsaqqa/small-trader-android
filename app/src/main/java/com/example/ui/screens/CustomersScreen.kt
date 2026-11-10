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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlin.math.max

private enum class CustomerBalanceFilter {
    ALL,
    DEBTORS,
    ADVANCE,
    SETTLED
}

/**
 * Native Customer Directory & Accounts Receivable Ledger Screen (دفتر الديون).
 *
 * Implements Stitch Design System (ST-32, ST-34, ST-35, ST-36, ST-39 / DESIGN_LOCK.md):
 * 1. Strict Financial Privacy: Store-wide aggregates (receivables & advances) are displayed
 *    ONLY for Owner sessions (canViewReports). Cashier downgrade immediately evicts both tags.
 * 2. Directional Balance Presentation:
 *    - Customer Debt (> 0): Red `#B42318` badge "عليه: 185.00 ₪"
 *    - Customer Advance (< 0): Green `#14804A` badge "له رصيد مقدم: 50.00+ ₪" (#EEF4FF container)
 *    - Settled: Neutral badge "خالص"
 * 3. Compact Calm Rows: Calm list rows with whole-card click to detail, and a dedicated
 *    48dp quick collect CTA for debtors.
 * 4. Microtype Safety & Spacing: Strict >= 12sp floor for Arabic typography, 12dp card corners,
 *    10dp button corners, 16dp outer gutters, and 8dp rhythm.
 * 5. BiDi & Numeric Alignment: Western European numerals (0-9) with LTR monospace phone numbers.
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
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    if (state.currentSession.canViewReports()) {
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
                                    text = StitchBidiUtils.formatIls(state.totalCustomerDebtsIls),
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
                                    text = StitchBidiUtils.formatIls(state.totalCustomerAdvancesIls),
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
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    Button(
                        onClick = onOpenAddCustomerDialog,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
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
                            text = lang.tr("+ زبون", "+ Customer"),
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
                        .heightIn(min = 48.dp)
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
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
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

/**
 * Calm compact list row (ST-32 Stitch Specification).
 * Displays name, LTR phone, neighborhood, directional balance, and quick collect CTA.
 */
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

    val statusBadgeTone = when {
        isDebtor -> StitchBadgeTone.DANGER
        isAdvance -> StitchBadgeTone.SUCCESS
        else -> StitchBadgeTone.NEUTRAL
    }

    val statusLabel = when {
        isDebtor -> lang.tr("عليه دين", "Owes Store")
        isAdvance -> lang.tr("له رصيد مقدم", "In Credit")
        else -> lang.tr("خالص", "Settled")
    }

    val displayedAmount = when {
        isDebtor -> StitchBidiUtils.formatIls(customer.balanceIls)
        isAdvance -> StitchBidiUtils.formatIls(-customer.balanceIls)
        else -> StitchBidiUtils.formatIls(0.0)
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
            // Left/Start: Customer Name + LTR Phone + Area
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = customer.name(lang),
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
                            text = customer.phone,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (customer.address(lang).isNotBlank()) {
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

                // Balance Directional Line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (isDebtor) {
                        Text(
                            text = lang.tr("عليه:", "Owes:"),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = financial.debit
                        )
                        Text(
                            text = displayedAmount,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = financial.debit
                        )
                    } else if (isAdvance) {
                        StitchBadge(
                            text = "$statusLabel $displayedAmount",
                            tone = StitchBadgeTone.SUCCESS,
                            shape = RoundedCornerShape(6.dp)
                        )
                    } else {
                        StitchBadge(
                            text = statusLabel,
                            tone = StitchBadgeTone.NEUTRAL,
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Right/End: Quick Collect CTA or Details Action
            if (onQuickCollect != null && isDebtor) {
                Button(
                    onClick = onQuickCollect,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("quick_collect_${customer.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lang.tr("قبض دفعة", "Collect"),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/**
 * Customer Detailed Profile & Statement Ledger Screen (كشف حساب الزبون).
 *
 * Implements Stitch ST-34 (Outstanding Debt 185.00 ₪) and ST-36 (Advance Deposit +50.00 ₪):
 * - Clear outstanding balance display with credit limit usage progress bar
 * - Advance credit rendered in success green #14804A with #EEF4FF container, NEVER styled as debt
 * - Direct actionable triggers: Collect payment, new credit sale, edit limit, share statement
 * - Real ledger entries with running balance calculation
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
    val customerId = state.selectedCustomerForDetail?.id ?: return
    val customer = state.customers.firstOrNull { it.id == customerId } ?: return
    val transactions = remember(state.customerTransactions, customer.id) {
        state.customerTransactions.filter { it.customerId == customer.id }
    }
    var isEditCustomerOpen by rememberSaveable(customer.id) { mutableStateOf(false) }
    var isStatementPreviewOpen by rememberSaveable(customer.id) { mutableStateOf(false) }

    val isDebtor = customer.balanceIls > 0.005
    val isAdvance = customer.balanceIls < -0.005
    val availableCreditIls = max(0.0, customer.creditLimitIls - max(0.0, customer.balanceIls))
    val creditUsagePercent = if (customer.creditLimitIls > 0.0) {
        (max(0.0, customer.balanceIls) / customer.creditLimitIls).coerceIn(0.0, 1.0).toFloat()
    } else 0f

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
        // Sticky Header with Back Button & Customer Context
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
                        text = customer.name(lang),
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
                                text = customer.phone,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (customer.address(lang).isNotBlank()) {
                            Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
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

            // 1. Balance Status Card (ST-34 Outstanding Debt vs ST-36 Customer Advance)
            item {
                if (isAdvance) {
                    // ST-36: Customer Advance Credit (+50.00 ₪)
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
                                    text = lang.tr("رصيد أمانة مقدم للزبون", "Customer Advance Deposit"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StitchBadge(
                                    text = lang.tr("رصيد مقدم", "Advance"),
                                    tone = StitchBadgeTone.SUCCESS,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            // Advance Highlight Box (#EEF4FF Container)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = lang.tr("له رصيد مقدم (دائن لصالح الزبون)", "Advance Credit Balance (Customer Credit)"),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = StitchBidiUtils.formatIls(-customer.balanceIls),
                                        style = MaterialTheme.typography.displayLarge.copy(
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = financial.income,
                                        modifier = Modifier.testTag("customer_detail_balance_value")
                                    )
                                    Text(
                                        text = lang.tr(
                                            "له بذمتنا ${StitchBidiUtils.formatIls(-customer.balanceIls)}",
                                            "Store holds ${StitchBidiUtils.formatIls(-customer.balanceIls)} (prepaid credit balance)"
                                        ),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ST-34: Outstanding Debt (185.00 ₪) or Settled
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
                                    text = if (isDebtor) {
                                        lang.tr("إجمالي الرصيد المستحق (لنا بذمته)", "Total Outstanding Debt (Owed to Store)")
                                    } else {
                                        lang.tr("حالة الحساب الحالي", "Current Account Status")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StitchBadge(
                                    text = if (isDebtor) lang.tr("ذمة قائمة", "Outstanding Debt") else lang.tr("حساب خالص", "Settled"),
                                    tone = if (isDebtor) StitchBadgeTone.DANGER else StitchBadgeTone.SUCCESS,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            // Big Outstanding Debt Amount
                            Text(
                                text = StitchBidiUtils.formatIls(customer.balanceIls),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isDebtor) financial.debit else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("customer_detail_balance_value")
                            )

                            // Credit Limit & Available Credit Indicator
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${lang.tr("سقف الائتمان:", "Credit Limit:")} ${StitchBidiUtils.formatIls(customer.creditLimitIls)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${lang.tr("المتبقي المسموح:", "Available:")} ${StitchBidiUtils.formatIls(availableCreditIls)}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = financial.income
                                    )
                                }
                                // Tactile Progress Bar
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(creditUsagePercent)
                                            .background(
                                                if (creditUsagePercent > 0.85f) financial.debit
                                                else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Primary Fast Actions Grid (>= 48dp Touch Targets)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenPaymentDialog,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("collect_payment_button")
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isDebtor) lang.tr("قبض دفعة", "Collect Payment")
                            else lang.tr("تسجيل دفعة أمانة", "Record Deposit"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    OutlinedButton(
                        onClick = { onStartSaleForCustomer(customer) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("customer_new_sale_button")
                    ) {
                        Icon(Icons.Default.PointOfSale, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isAdvance) lang.tr("بيع خصماً من الرصيد", "Credit Sale")
                            else lang.tr("بيع آجل جديد", "New Credit Sale"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            // 3. Secondary Utility Actions (Edit & Share Statement)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onUpdateCustomer != null) {
                        OutlinedButton(
                            onClick = { isEditCustomerOpen = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
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
                            .heightIn(min = 48.dp)
                            .testTag("share_customer_statement_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("مشاركة / كشف الحساب", "Share Statement"),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            // 4. Customer Statement Section Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = lang.tr("سجل الفواتير والحركات", "Transaction History"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        StitchBadge(
                            text = "${WesternFormatters.formatInt(transactions.size)} ${lang.tr("حركة", "entries")}",
                            tone = StitchBadgeTone.NEUTRAL,
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
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
            appendLine("${lang.tr("الرصيد الحالي:", "Current Balance:")} ${StitchBidiUtils.formatIls(customer.balanceIls)}")
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
        shape = RoundedCornerShape(12.dp),
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
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("مشاركة كشف حساب الزبون", "Share Customer Statement"),
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
                        "كشف الحساب",
                        "Statement text ready to copy and share:"
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
                        text = lang.tr("تم نسخ كشف حساب ${customer.name(lang)} للحافظة بنجاح", "Statement copied to clipboard"),
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
            "الرصيد بعدها: عليه ${StitchBidiUtils.formatIls(runningBal)}",
            "Balance after: Owes ${StitchBidiUtils.formatIls(runningBal)}"
        )
        runningBal < -0.005 -> lang.tr(
            "الرصيد بعدها: له ${StitchBidiUtils.formatIls(-runningBal)}",
            "Balance after: In credit ${StitchBidiUtils.formatIls(-runningBal)}"
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
                                text = "+ ${StitchBidiUtils.formatIls(tx.debitIls)}",
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
                                text = "- ${StitchBidiUtils.formatIls(tx.creditIls)}",
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
                                text = StitchBidiUtils.formatIls(tx.debitIls),
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
                                    text = "+ ${StitchBidiUtils.formatIls(tx.debitIls)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.debit
                                )
                            }
                            if (tx.creditIls > 0) {
                                Text(
                                    text = "- ${StitchBidiUtils.formatIls(tx.creditIls)}",
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
                            text = StitchBidiUtils.formatInvoiceCode(tx.invoiceNumber, lang.tr("فاتورة", "Invoice")),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Customer Cash Debt Collection & Advance Deposit Dialog (ST-35 Canonical Direction).
 *
 * Implements Stitch ST-35:
 * - Minimal customer amount presets (Full debt 185.00 ₪, 50 ₪, 100 ₪, 200 ₪)
 * - Source-derived arithmetic: 185.00 - 85.00 = 100.00 ₪ projected remaining debt
 * - Overpayment allowed: Collecting more than outstanding debt deposits the surplus as
 *   a customer advance (credit balance) per repository contract, NOT blocked by policy error.
 * - Single prominent confirmation CTA: "تأكيد قبض [X] ₪ وإيداعها بالصندوق"
 * - Idempotency preserved: Uses stable operationId to protect against double-tap submits.
 */
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
    // Deterministic operation ID per dialog session to prevent duplicate processing
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
        shape = RoundedCornerShape(12.dp),
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
                // Customer Identity & Current Debt Status Card
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
                            text = customer.name(lang),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = when {
                                customer.balanceIls > 0.005 -> lang.tr(
                                    "الدين الحالي عليه (ذمة قائمة): ${StitchBidiUtils.formatIls(customer.balanceIls)}",
                                    "Current outstanding debt: ${StitchBidiUtils.formatIls(customer.balanceIls)}"
                                )
                                customer.balanceIls < -0.005 -> lang.tr(
                                    "له رصيد مقدم حالياً: ${StitchBidiUtils.formatIls(-customer.balanceIls)}",
                                    "Current advance credit: ${StitchBidiUtils.formatIls(-customer.balanceIls)}"
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

                // Quick Preset Chips (Full Debt, 50, 100, 200)
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
                                                "كامل الدين (${StitchBidiUtils.formatIls(customer.balanceIls)})",
                                                "Full Debt (${StitchBidiUtils.formatIls(customer.balanceIls)})"
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

                // Cash Amount Input (Large readable field with Western numbers)
                OutlinedTextField(
                    value = payAmountText,
                    onValueChange = { payAmountText = WesternFormatters.sanitizeWesternDigits(it) },
                    label = { Text(lang.tr("المبلغ المقبوض نقداً (₪) *", "Cash Collected (₪) *")) },
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
                        .testTag("payment_amount_input")
                )

                // Projected Balance & Cash Drawer Status Banner (ST-35 Breakdown)
                if (enteredAmount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            projectedBalance > 0.005 -> MaterialTheme.colorScheme.surfaceVariant
                            projectedBalance < -0.005 -> financial.incomeContainer
                            else -> financial.incomeContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (projectedBalance > 0.005) MaterialTheme.colorScheme.outlineVariant
                            else financial.income.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("payment_projected_balance_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = when {
                                    projectedBalance > 0.005 -> lang.tr(
                                        "الرصيد المتبقي بعد القبض: ${StitchBidiUtils.formatIls(projectedBalance)} (دين متبقي)",
                                        "Remaining debt after payment: ${StitchBidiUtils.formatIls(projectedBalance)}"
                                    )
                                    projectedBalance < -0.005 -> lang.tr(
                                        "الرصيد بعد القبض: يصبح له رصيد مقدم (أمانة) ${StitchBidiUtils.formatIls(-projectedBalance)}",
                                        "After collection: Customer advance credit of ${StitchBidiUtils.formatIls(-projectedBalance)}"
                                    )
                                    else -> lang.tr(
                                        "الرصيد بعد القبض: يصبح حساب الزبون خالصاً تماماً (0.00 ₪)",
                                        "After collection: Account becomes completely settled (0.00 ₪)"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (projectedBalance > 0.005) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    financial.income
                                }
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PointOfSale,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = lang.tr(
                                        "قبض نقدي",
                                        "Deposit to active cash drawer • Ready"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = payNoteText,
                    onValueChange = { payNoteText = it },
                    label = { Text(lang.tr("البيان / ملاحظة السند (اختياري)", "Note (Optional)")) },
                    placeholder = { Text(lang.tr("مثال: دفعة نقدية على الحساب", "e.g. Cash payment on account")) },
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
                    text = if (enteredAmount > 0.0) {
                        lang.tr(
                            "تأكيد قبض ${StitchBidiUtils.formatIls(enteredAmount)} وإيداعها بالصندوق",
                            "Confirm ${StitchBidiUtils.formatIls(enteredAmount)} Collection"
                        )
                    } else {
                        lang.tr("تأكيد القبض بالصندوق", "Confirm Collection")
                    },
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
 * Minimal Add Customer Modal Sheet / Dialog (ST-39 Stitch Specification).
 * Fields: Name (required), Phone (required, 05X format), Address (optional).
 */
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
        shape = RoundedCornerShape(12.dp),
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
                    label = { Text(lang.tr("اسم الزبون أو المنشأة *", "Customer Name *")) },
                    placeholder = { Text(lang.tr("مثال: خليل شاهين", "e.g. Khalil Shaheen")) },
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
                    label = { Text(lang.tr("رقم الجوال (05X...) *", "Phone Number (05X...) *")) },
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
                        .testTag("new_customer_phone_input")
                )
                OutlinedTextField(
                    value = newCustAddr,
                    onValueChange = { newCustAddr = it },
                    label = { Text(lang.tr("العنوان أو الحي (اختياري)", "Address / Area (Optional)")) },
                    placeholder = { Text(lang.tr("نابلس — رفيديا", "Nablus - Rafidia")) },
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
