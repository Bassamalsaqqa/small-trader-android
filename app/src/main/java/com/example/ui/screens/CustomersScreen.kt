package com.example.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.example.data.mobile.contract.StrictJsonArray
import com.example.data.mobile.contract.StrictJsonObject
import com.example.data.mobile.contract.toNullableBigDecimal
import com.example.data.mobile.contract.toNullableString

import java.math.BigDecimal
import androidx.compose.material.icons.filled.Warning
import com.example.viewmodel.ReadStatus
import com.example.model.TraderScreen
import androidx.compose.material3.CircularProgressIndicator
import com.example.data.mobile.merchantread.LiveCustomerStatement
import com.example.data.mobile.merchantread.CustomerStatementCurrencySummary
import com.example.data.mobile.merchantread.CustomerStatementEntry
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Checkbox
import com.example.model.PaymentAllocationInput
import com.example.model.SettlementTarget
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.ui.components.SelectAllNumericOutlinedTextField
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
import com.example.ui.navigation.MerchantAction
import com.example.ui.navigation.allowed
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.RoundingMode
import com.example.data.mobile.contract.v2.PaymentDecimalConsistency
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
    onDismissFeedback: (() -> Unit)? = null,
    onRetryRefresh: (() -> Unit)? = null,
    onSearchCustomers: ((String) -> Unit)? = null
) {
    val readStatus = state.readStatusFor(TraderScreen.CUSTOMERS)
    val lang = state.language
    val financial = TraderTheme.financial
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(CustomerBalanceFilter.ALL) }

    val debtorsCount = remember(state.customers) { state.customers.count { it.balanceExact != null && it.balanceExact > BigDecimal.ZERO } }
    val advanceCount = remember(state.customers) { state.customers.count { it.balanceExact != null && it.balanceExact < BigDecimal.ZERO } }
    val settledCount = remember(state.customers) { state.customers.count { it.balanceExact != null && it.balanceExact.compareTo(BigDecimal.ZERO) == 0 } }

    val filteredCustomers = remember(state.customers, state.customerSearchResults, state.customerSearchQuery, searchQuery, selectedFilter) {
        val q = TraderUiState.normalizeForSearch(WesternFormatters.sanitizeWesternDigits(searchQuery.trim()))
        val sourceList = if (searchQuery.isNotBlank() &&
            state.customerSearchQuery.trim().equals(searchQuery.trim(), ignoreCase = true) &&
            state.customerSearchResults != null
        ) {
            state.customerSearchResults!!
        } else {
            state.customers
        }
        sourceList.filter { c ->
            val matchesFilter = when (selectedFilter) {
                CustomerBalanceFilter.ALL -> true
                CustomerBalanceFilter.DEBTORS -> c.balanceExact != null && c.balanceExact > BigDecimal.ZERO
                CustomerBalanceFilter.ADVANCE -> c.balanceExact != null && c.balanceExact < BigDecimal.ZERO
                CustomerBalanceFilter.SETTLED -> c.balanceExact != null && c.balanceExact.compareTo(BigDecimal.ZERO) == 0
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true

            TraderUiState.normalizeForSearch(c.nameAr).contains(q) ||
                TraderUiState.normalizeForSearch(c.nameEn).contains(q) ||
                (c.addressAr?.let { TraderUiState.normalizeForSearch(it).contains(q) } ?: false) ||
                (c.addressEn?.let { TraderUiState.normalizeForSearch(it).contains(q) } ?: false) ||
                (c.code ?: "").lowercase().contains(q) ||
                (c.phone?.replace("-", "")?.contains(q.replace("-", "")) ?: false) ||
                (c.phone?.contains(q) ?: false)
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
                                val dirCurrency = state.businessInfo.defaultCurrency
                                val debtsExact = state.totalCustomerDebtsExact
                                Text(
                                    text = when {
                                        state.customersHasMore -> lang.tr("غير متاح (دليل جزئي)", "Unavailable (partial)")
                                        debtsExact == null -> lang.tr("غير متاح", "Unavailable")
                                        else -> StitchBidiUtils.formatHumanMoney2dp(debtsExact, dirCurrency, lang)
                                    },
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
                                val dirCurrency = state.businessInfo.defaultCurrency
                                val advExact = state.totalCustomerAdvancesExact
                                Text(
                                    text = when {
                                        state.customersHasMore -> lang.tr("غير متاح (دليل جزئي)", "Unavailable (partial)")
                                        advExact == null -> lang.tr("غير متاح", "Unavailable")
                                        else -> StitchBidiUtils.formatHumanMoney2dp(advExact, dirCurrency, lang)
                                    },
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (advExact != null && advExact > BigDecimal.ZERO) {
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
                    onValueChange = {
                        searchQuery = it
                        onSearchCustomers?.invoke(it)
                    },
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
                                onClick = {
                                    searchQuery = ""
                                    onSearchCustomers?.invoke("")
                                },
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
                                    val countSuffix = if (state.customersHasMore) "+" else ""
                                    Text(
                                        text = lang.tr(
                                            "الكل (${WesternFormatters.formatInt(state.customers.size)}$countSuffix)",
                                            "All (${WesternFormatters.formatInt(state.customers.size)}$countSuffix)"
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

        val isDebouncingOrSearching = searchQuery.isNotBlank() && (
            state.isSearchingCustomers || !state.customerSearchQuery.trim().equals(searchQuery.trim(), ignoreCase = true)
        )

        if (isDebouncingOrSearching) {
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
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("customer_search_progress"),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = lang.tr("جاري البحث في الزبائن...", "Searching customers..."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (searchQuery.isNotBlank() && state.customerSearchError != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = lang.tr(
                            "فشل البحث في الزبائن: ${state.customerSearchError}",
                            "Customer search failed: ${state.customerSearchError}"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { onSearchCustomers?.invoke(searchQuery) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("customer_search_retry_button")
                    ) {
                        Text(lang.tr("إعادة المحاولة", "Retry"))
                    }
                }
            }
        } else if (filteredCustomers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .testTag("customer_zero_results"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (readStatus == ReadStatus.LOADING) {
                        CircularProgressIndicator()
                    } else if (readStatus == ReadStatus.FAILED && state.customers.isEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            text = lang.tr("تعذر تحديث الزبائن من الخادم", "Could not refresh customers from server"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (onRetryRefresh != null) {
                            TextButton(
                                onClick = onRetryRefresh,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(
                                    text = lang.tr("إعادة المحاولة", "Retry"),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    } else {
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
            }
        } else {
            val canCollectPayment = MerchantAction.COLLECT.allowed(state.currentSession)
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
                        currency = state.businessInfo.defaultCurrency,
                        onClick = { onCustomerClick(customer) },
                        onQuickCollect = if (onQuickCollectPayment != null && canCollectPayment && customer.balanceExact != null && customer.balanceExact > BigDecimal.ZERO) {
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
    currency: String = "ILS",
    onClick: () -> Unit,
    onQuickCollect: (() -> Unit)?
) {
    val financial = TraderTheme.financial
    val balExact = customer.balanceExact
    val isDebtor = balExact != null && balExact > BigDecimal.ZERO
    val isAdvance = balExact != null && balExact < BigDecimal.ZERO

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
        balExact == null -> lang.tr("الرصيد غير متاح", "Balance unavailable")
        isDebtor -> lang.tr("عليه دين", "Owes Store")
        isAdvance -> lang.tr("له رصيد مقدم", "In Credit")
        else -> lang.tr("خالص", "Settled")
    }

    val displayedAmount = when {
        balExact == null -> lang.tr("غير متاح", "Unavailable")
        isDebtor -> StitchBidiUtils.formatHumanMoney2dp(balExact, currency, lang)
        isAdvance -> StitchBidiUtils.formatHumanMoney2dp(balExact.abs(), currency, lang)
        else -> StitchBidiUtils.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)
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
                            text = customer.phone ?: "",
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
    onUpdateCustomerWithResult: ((customerId: String, name: String, phone: String, address: String, creditLimitIls: Double, onComplete: (Boolean) -> Unit) -> Unit)? = null,
    onUpdateCustomerWithDetails: ((customerId: String, name: String, phone: String, address: String, updatePhone: Boolean, updateAddress: Boolean, updateCreditLimit: Boolean, creditLimit: BigDecimal?, onComplete: (Boolean) -> Unit) -> Unit)? = null,
    onFormatStatementText: ((String) -> String)? = null,
    onRetryStatement: ((String) -> Unit)? = null
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val customerId = state.selectedCustomerForDetail?.id ?: return
    val customer = state.customers.firstOrNull { it.id == customerId } ?: state.selectedCustomerForDetail ?: return
    val transactions = remember(state.customerTransactions, customer.id) {
        state.customerTransactions.filter { it.customerId == customer.id }
    }
    var isEditCustomerOpen by rememberSaveable(customer.id) { mutableStateOf(false) }
    var isStatementPreviewOpen by rememberSaveable(customer.id) { mutableStateOf(false) }

    val isDemo = state.currentSession.isDemoSession
    val liveStatement = if (!isDemo && state.selectedCustomerStatement?.customerId == customer.id) {
        state.selectedCustomerStatement
    } else null
    val statementError = if (!isDemo && liveStatement == null) state.customerStatementError else null
    val isStatementLoading = !isDemo && liveStatement == null && (state.isLoadingCustomerStatement || statementError == null)

    val currency = state.businessInfo.defaultCurrency
    val balExact = customer.balanceExact
    val isDebtor = balExact != null && balExact > BigDecimal.ZERO
    val isAdvance = balExact != null && balExact < BigDecimal.ZERO
    val limitExact = customer.creditLimitExact
    val availableCreditExact: BigDecimal? = if (limitExact != null && balExact != null) {
        limitExact.subtract(balExact.coerceAtLeast(BigDecimal.ZERO)).coerceAtLeast(BigDecimal.ZERO)
    } else {
        null
    }
    val creditUsagePercent = if (limitExact != null && limitExact > BigDecimal.ZERO && balExact != null) {
        balExact.coerceAtLeast(BigDecimal.ZERO)
            .divide(limitExact, 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
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
                                text = customer.phone ?: "",
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

            // 1. Balance Status Card (ST-34 Outstanding Debt vs ST-36 Customer Advance vs Live Multi-Currency)
            item {
                if (isStatementLoading) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_statement_loading")
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = lang.tr("جارٍ تحميل كشف الحساب من الخادم...", "Loading statement from server..."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (!isDemo && statementError != null) {
                    val localizedStatementError = if (statementError.contains(" | ")) {
                        lang.tr(statementError.substringBefore(" | "), statementError.substringAfter(" | "))
                    } else {
                        lang.tr(
                            "تعذر تحميل كشف حساب الزبون. يرجى المحاولة مرة أخرى.",
                            "Could not load customer statement. Please try again."
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_statement_error")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = localizedStatementError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            if (onRetryStatement != null) {
                                OutlinedButton(
                                    onClick = { onRetryStatement(customer.id) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("customer_statement_retry_button")
                                ) {
                                    Text(
                                        text = lang.tr("إعادة المحاولة", "Retry"),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                } else if (liveStatement != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        liveStatement.currencies.forEach { (currCode, currSummary) ->
                            val isDebtorLive = currSummary.closingBalance.compareTo(BigDecimal.ZERO) > 0
                            val isAdvanceLive = currSummary.closingBalance.compareTo(BigDecimal.ZERO) < 0
                            val formattedBalance = StitchBidiUtils.formatHumanMoney2dp(currSummary.closingBalance.abs(), currCode, lang)

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("customer_currency_statement_$currCode")
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
                                            text = when {
                                                isDebtorLive -> lang.tr("رصيد مستحق ($currCode)", "Outstanding Debt ($currCode)")
                                                isAdvanceLive -> lang.tr("رصيد دائن / أمانة ($currCode)", "Credit Balance ($currCode)")
                                                else -> lang.tr("رصيد الحساب ($currCode)", "Account Balance ($currCode)")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        StitchBadge(
                                            text = when {
                                                isDebtorLive -> lang.tr("ذمة قائمة", "Debt")
                                                isAdvanceLive -> lang.tr("رصيد دائن", "Credit")
                                                else -> lang.tr("خالص", "Settled")
                                            },
                                            tone = when {
                                                isDebtorLive -> StitchBadgeTone.DANGER
                                                isAdvanceLive -> StitchBadgeTone.SUCCESS
                                                else -> StitchBadgeTone.NEUTRAL
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }

                                    Text(
                                        text = formattedBalance,
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = MonoFontFamily
                                        ),
                                        color = when {
                                            isDebtorLive -> financial.debit
                                            isAdvanceLive -> financial.income
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.testTag("customer_detail_balance_value")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${lang.tr("إجمالي المدين:", "Total Debits:")} ${StitchBidiUtils.formatHumanMoney2dp(currSummary.totalDebits, currCode, lang)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${lang.tr("إجمالي الدائن:", "Total Credits:")} ${StitchBidiUtils.formatHumanMoney2dp(currSummary.totalCredits, currCode, lang)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = financial.income
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (isAdvance) {
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
                                    val advanceStr = StitchBidiUtils.formatHumanMoney2dp(balExact!!.abs(), currency, lang)
                                    Text(
                                        text = advanceStr,
                                        style = MaterialTheme.typography.displayLarge.copy(
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = financial.income,
                                        modifier = Modifier.testTag("customer_detail_balance_value")
                                    )
                                    Text(
                                        text = lang.tr(
                                            "له بذمتنا $advanceStr",
                                            "Store holds $advanceStr (prepaid credit balance)"
                                        ),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ST-34: Outstanding Debt or Settled
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
                                    text = if (balExact == null) lang.tr("غير متاح", "Unavailable") else if (isDebtor) lang.tr("ذمة قائمة", "Outstanding Debt") else lang.tr("حساب خالص", "Settled"),
                                    tone = if (balExact == null) StitchBadgeTone.NEUTRAL else if (isDebtor) StitchBadgeTone.DANGER else StitchBadgeTone.SUCCESS,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            // Big Outstanding Debt Amount
                            Text(
                                text = if (balExact == null) lang.tr("غير متاح", "Unavailable") else StitchBidiUtils.formatHumanMoney2dp(balExact, currency, lang),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (balExact == null) MaterialTheme.colorScheme.onSurfaceVariant else if (isDebtor) financial.debit else MaterialTheme.colorScheme.onSurface,
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
                                        text = "${lang.tr("سقف الائتمان:", "Credit Limit:")} ${if (limitExact != null) StitchBidiUtils.formatHumanMoney2dp(limitExact, currency, lang) else lang.tr("غير محدد", "Not set")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${lang.tr("المتبقي المسموح:", "Available:")} ${
                                            when {
                                                limitExact == null -> lang.tr("غير محدد", "Not set")
                                                availableCreditExact == null -> lang.tr("غير متاح", "Unavailable")
                                                else -> StitchBidiUtils.formatHumanMoney2dp(availableCreditExact, currency, lang)
                                            }
                                        }",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (availableCreditExact != null) financial.income else MaterialTheme.colorScheme.onSurfaceVariant
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
                    if (MerchantAction.COLLECT.allowed(state.currentSession)) {
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
                    if (onUpdateCustomerWithDetails != null || onUpdateCustomerWithResult != null || onUpdateCustomer != null) {
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
                        val entryCount = if (!isDemo && liveStatement != null) {
                            liveStatement.currencies.values.sumOf { it.entries.size }
                        } else if (isDemo) {
                            transactions.size
                        } else {
                            0
                        }
                        StitchBadge(
                            text = "${WesternFormatters.formatInt(entryCount)} ${lang.tr("حركة", "entries")}",
                            tone = StitchBadgeTone.NEUTRAL,
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }
            }

            if (!isDemo && isStatementLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (!isDemo && statementError != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr(
                                    "تعذر تحميل سجل الحركات من الخادم",
                                    "Could not load transaction history"
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            if (onRetryStatement != null) {
                                TextButton(
                                    onClick = { onRetryStatement(customer.id) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("customer_statement_history_retry_button")
                                ) {
                                    Text(
                                        text = lang.tr("إعادة المحاولة", "Retry"),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (!isDemo && liveStatement != null) {
                val liveEntries = liveStatement.currencies.flatMap { (code, statement) -> statement.entries.map { code to it } }
                if (liveEntries.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lang.tr(
                                    "لا توجد حركات مسجلة لهذا الزبون في كشف الحساب.",
                                    "No transactions recorded for this customer in statement."
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(liveEntries) { (entryCurrency, entry) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${entry.type} • ${entry.date}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!entry.number.isNullOrBlank()) {
                                        Text(
                                            text = "${lang.tr("مستند:", "Doc:")} ${entry.number}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (entry.debit.compareTo(BigDecimal.ZERO) > 0) {
                                        Text(
                                            text = "+${StitchBidiUtils.formatHumanMoney2dp(entry.debit, entryCurrency, lang)}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = financial.debit
                                        )
                                    }
                                    if (entry.credit.compareTo(BigDecimal.ZERO) > 0) {
                                        Text(
                                            text = "-${StitchBidiUtils.formatHumanMoney2dp(entry.credit, entryCurrency, lang)}",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = financial.income
                                        )
                                    }
                                    Text(
                                        text = "${lang.tr("الرصيد:", "Bal:")} ${StitchBidiUtils.formatHumanMoney2dp(entry.balance, entryCurrency, lang)}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (transactions.isEmpty()) {
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

    if (isEditCustomerOpen && (onUpdateCustomerWithDetails != null || onUpdateCustomerWithResult != null || onUpdateCustomer != null)) {
        EditCustomerDialog(
            customer = customer,
            currency = currency,
            lang = lang,
            onDismiss = { isEditCustomerOpen = false },
            onConfirmUpdate = { name, phone, address, upPhone, upAddr, upLimit, limitExact, limitIls ->
                if (onUpdateCustomerWithDetails != null) {
                    onUpdateCustomerWithDetails(customer.id, name, phone, address, upPhone, upAddr, upLimit, limitExact) { success ->
                        if (success) isEditCustomerOpen = false
                    }
                } else if (onUpdateCustomerWithResult != null) {
                    onUpdateCustomerWithResult(customer.id, name, phone, address, limitIls) { success ->
                        if (success) isEditCustomerOpen = false
                    }
                } else {
                    onUpdateCustomer?.invoke(customer.id, name, phone, address, limitIls)
                    isEditCustomerOpen = false
                }
            }
        )
    }

    if (isStatementPreviewOpen) {
        val statementText = onFormatStatementText?.invoke(customer.id) ?: buildString {
            appendLine("${lang.tr("كشف حساب الزبون:", "Customer Statement:")} ${customer.name(lang)}")
            val balStr = if (balExact != null) {
                StitchBidiUtils.formatHumanMoney2dp(balExact, currency, lang)
            } else {
                lang.tr("غير متاح (يرجى تحديث الكشف)", "Unavailable (refresh statement)")
            }
            appendLine("${lang.tr("الرصيد الحالي:", "Current Balance:")} $balStr")
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
    currency: String = "ILS",
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmUpdate: (
        name: String,
        phone: String,
        address: String,
        updatePhone: Boolean,
        updateAddress: Boolean,
        updateCreditLimit: Boolean,
        creditLimit: BigDecimal?,
        creditLimitIls: Double
    ) -> Unit
) {
    var nameText by rememberSaveable(customer.id) { mutableStateOf(customer.nameAr) }
    var phoneText by rememberSaveable(customer.id) { mutableStateOf(customer.phone ?: "") }
    var addressText by rememberSaveable(customer.id) { mutableStateOf(customer.addressAr ?: "") }
    var limitText by rememberSaveable(customer.id) {
        mutableStateOf(customer.creditLimitExact?.toPlainString() ?: "")
    }

    var nameModified by rememberSaveable(customer.id) { mutableStateOf(false) }
    var phoneModified by rememberSaveable(customer.id) { mutableStateOf(false) }
    var addressModified by rememberSaveable(customer.id) { mutableStateOf(false) }
    var limitModified by rememberSaveable(customer.id) { mutableStateOf(false) }

    LaunchedEffect(customer) {
        if (!nameModified) nameText = customer.nameAr
        if (!phoneModified) phoneText = customer.phone ?: ""
        if (!addressModified) addressText = customer.addressAr ?: ""
        if (!limitModified) limitText = customer.creditLimitExact?.toPlainString() ?: ""
    }

    val isLimitValid = remember(limitText) {
        val trimmed = limitText.trim()
        if (trimmed.isEmpty()) true
        else {
            try {
                val bd = BigDecimal(trimmed)
                val parts = trimmed.split(".")
                val decPlaces = if (parts.size > 1) parts[1].length else 0
                bd >= BigDecimal.ZERO && decPlaces <= 6
            } catch (_: Exception) {
                false
            }
        }
    }
    val limitErrorMsg = if (!isLimitValid) {
        lang.tr("يرجى إدخال مبلغ صحيح (بحد أقصى 6 خانات عشرية)", "Enter a valid amount (up to 6 decimal places)")
    } else null

    val parsedLimitExact = if (limitText.isBlank()) null else try { BigDecimal(limitText.trim()) } catch (_: Exception) { null }
    val parsedLimitDouble = parsedLimitExact?.toDouble() ?: if (limitText.isBlank()) 0.0 else customer.creditLimitIls
    val canSave = nameText.trim().length >= 2 && isLimitValid

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
                    onValueChange = {
                        nameText = it
                        nameModified = true
                    },
                    label = { Text(lang.tr("اسم الزبون أو المنشأة *", "Customer Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_customer_name_input")
                )
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = {
                        phoneText = WesternFormatters.sanitizeWesternDigits(it)
                        phoneModified = true
                    },
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
                    onValueChange = {
                        addressText = it
                        addressModified = true
                    },
                    label = { Text(lang.tr("العنوان / المنطقة", "Neighborhood / Address")) },
                    placeholder = {
                        if (customer.addressAr == null) {
                            Text(lang.tr("غير محمل — اتركه فارغاً لعدم التعديل", "Unloaded — leave blank to keep unchanged"))
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_customer_address_input")
                )
                SelectAllNumericOutlinedTextField(
                    value = limitText,
                    onValueChange = {
                        limitText = WesternFormatters.sanitizeWesternDigits(it)
                            .filter { ch -> ch.isDigit() || ch == '.' }
                        limitModified = true
                    },
                    label = {
                        val currencyTag = WesternFormatters.formatReceiptCurrencyTag(currency, lang)
                        Text(lang.tr("سقف الدين المسموح ($currencyTag)", "Credit Limit ($currencyTag)"))
                    },
                    placeholder = {
                        if (customer.creditLimitExact == null) {
                            Text(lang.tr("غير محدد — اتركه فارغاً للإبقاء عليه", "Not set — leave blank to keep unchanged"))
                        }
                    },
                    isError = !isLimitValid,
                    supportingText = limitErrorMsg?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } },
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
                        onConfirmUpdate(
                            nameText.trim(),
                            phoneText.trim(),
                            addressText.trim(),
                            phoneModified,
                            addressModified,
                            limitModified,
                            parsedLimitExact,
                            parsedLimitDouble
                        )
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
    val isCreditPurchase = tx.debitExact > BigDecimal.ZERO && tx.creditExact <= BigDecimal.ZERO
    val isPaidInvoice = tx.debitExact > BigDecimal.ZERO && tx.creditExact >= tx.debitExact
    val isPaymentCollection = tx.creditExact > BigDecimal.ZERO && tx.debitExact <= BigDecimal.ZERO

    val runningBal = tx.runningBalanceExact
    val runningBalLabel = when {
        runningBal == null -> lang.tr("الرصيد بعدها: غير متاح", "Balance after: Unavailable")
        runningBal > BigDecimal.ZERO -> lang.tr(
            "الرصيد بعدها: عليه ${StitchBidiUtils.formatHumanMoney2dp(runningBal, tx.currency, lang)}",
            "Balance after: Owes ${StitchBidiUtils.formatHumanMoney2dp(runningBal, tx.currency, lang)}"
        )
        runningBal < BigDecimal.ZERO -> lang.tr(
            "الرصيد بعدها: له ${StitchBidiUtils.formatHumanMoney2dp(runningBal.abs(), tx.currency, lang)}",
            "Balance after: In credit ${StitchBidiUtils.formatHumanMoney2dp(runningBal.abs(), tx.currency, lang)}"
        )
        else -> lang.tr(
            "الرصيد بعدها: خالص (${StitchBidiUtils.formatHumanMoney2dp(BigDecimal.ZERO, tx.currency, lang)})",
            "Balance after: Settled (${StitchBidiUtils.formatHumanMoney2dp(BigDecimal.ZERO, tx.currency, lang)})"
        )
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
                                text = "+ ${StitchBidiUtils.formatHumanMoney2dp(tx.debitExact, tx.currency, lang)}",
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
                                text = "- ${StitchBidiUtils.formatHumanMoney2dp(tx.creditExact, tx.currency, lang)}",
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
                                text = StitchBidiUtils.formatHumanMoney2dp(tx.debitExact, tx.currency, lang),
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
                            if (tx.debitExact > BigDecimal.ZERO) {
                                Text(
                                    text = "+ ${StitchBidiUtils.formatHumanMoney2dp(tx.debitExact, tx.currency, lang)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.debit
                                )
                            }
                            if (tx.creditExact > BigDecimal.ZERO) {
                                Text(
                                    text = "- ${StitchBidiUtils.formatHumanMoney2dp(tx.creditExact, tx.currency, lang)}",
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
internal fun extractDraftPaymentValues(
    initialDraft: StrictJsonObject?,
    minorUnits: Int
): Triple<String, String, Map<String, String>>? {
    if (initialDraft == null) return null
    val rawAmt = initialDraft.properties["amount"].toNullableBigDecimal(minorUnits)
    val amtText = rawAmt?.let {
        PaymentDecimalConsistency.cashEntryText(it, minorUnits) ?: it.stripTrailingZeros().toPlainString()
    } ?: ""
    val noteText = initialDraft.properties["notes"].toNullableString()
        ?: initialDraft.properties["note"].toNullableString()
        ?: ""
    val allocMap = linkedMapOf<String, String>()
    val allocArr = initialDraft.properties["allocations"] as? StrictJsonArray
    if (allocArr != null) {
        for (el in allocArr.elements) {
            val obj = el as? StrictJsonObject ?: continue
            val tType = obj.properties["target_type"].toNullableString() ?: continue
            val tId = obj.properties["target_id"].toNullableString() ?: continue
            val aBd = obj.properties["amount"].toNullableBigDecimal(6) ?: continue
            val aText = PaymentDecimalConsistency.cashEntryText(aBd, minorUnits)
                ?: aBd.stripTrailingZeros().toPlainString()
            allocMap["$tType:$tId"] = aText
        }
    }
    return Triple(amtText, noteText, allocMap)
}

@Composable
fun CustomerPaymentCollectionDialog(
    customer: Customer,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmPayment: ((customerId: String, amountIls: Double, note: String, paymentId: String) -> Unit)? = null,
    onConfirmPaymentExact: ((customerId: String, amount: BigDecimal, note: String, paymentId: String, allocations: List<PaymentAllocationInput>) -> Unit)? = null,
    onConfirmPaymentExactWithResult: ((customerId: String, amount: BigDecimal, note: String, paymentId: String, allocations: List<PaymentAllocationInput>, onComplete: (Result<CustomerTransaction>) -> Unit) -> Unit)? = null,
    settlementTargets: List<SettlementTarget> = emptyList(),
    isLoadingTargets: Boolean = false,
    targetsError: String? = null,
    targetsHasMore: Boolean = false,
    onLoadMoreTargets: (() -> Unit)? = null,
    onRetryTargets: (() -> Unit)? = null,
    currency: String = "ILS",
    minorUnits: Int = 2,
    operationId: String? = null,
    initialDraft: StrictJsonObject? = null,
    isRecovering: Boolean = false,
    isSubmitting: Boolean = false,
    inlineError: String? = null
) {
    val financial = TraderTheme.financial
    val initialAmt = if ((customer.balanceExact ?: BigDecimal.ZERO) > BigDecimal.ZERO) {
        PaymentDecimalConsistency.cashEntryText(customer.balanceExact!!, minorUnits).orEmpty()
    } else {
        ""
    }
    val draftCustomerId = initialDraft?.properties?.get("customer_id").toNullableString()
    val isBlockedByOtherDraft = isRecovering && !draftCustomerId.isNullOrBlank() && draftCustomerId != customer.id
    val isRecoveringCurrentCustomer = isRecovering && (draftCustomerId.isNullOrBlank() || draftCustomerId == customer.id)
    val restoredDraft = remember(initialDraft, isRecoveringCurrentCustomer, minorUnits) {
        if (isRecoveringCurrentCustomer) extractDraftPaymentValues(initialDraft, minorUnits) else null
    }

    var payAmountText by rememberSaveable(customer.id) { mutableStateOf(restoredDraft?.first ?: initialAmt) }
    var payNoteText by rememberSaveable(customer.id) { mutableStateOf(restoredDraft?.second ?: "") }
    var localSubmitting by rememberSaveable(customer.id) { mutableStateOf(false) }
    var localInlineError by rememberSaveable(customer.id) { mutableStateOf<String?>(null) }
    val localFallbackOperationId = rememberSaveable(customer.id) { "cpay_${customer.id}_${System.currentTimeMillis()}" }
    val effectiveOperationId = if (!operationId.isNullOrBlank()) operationId else localFallbackOperationId
    val effectiveSubmitting = isSubmitting || localSubmitting

    LaunchedEffect(Unit) {
        if (inlineError == null) {
            localInlineError = null
        }
    }

    LaunchedEffect(inlineError) {
        if (inlineError != null) {
            localInlineError = inlineError
        }
    }
    val displayedInlineError = localInlineError ?: inlineError

    // Map of targetKey ("${targetType}:${targetId}") -> allocation amount text
    val selectedAllocations = remember {
        mutableStateMapOf<String, String>().apply {
            restoredDraft?.third?.let { putAll(it) }
        }
    }

    LaunchedEffect(isRecoveringCurrentCustomer, restoredDraft) {
        if (isRecoveringCurrentCustomer && restoredDraft != null) {
            payAmountText = restoredDraft.first
            payNoteText = restoredDraft.second
            selectedAllocations.clear()
            selectedAllocations.putAll(restoredDraft.third)
        }
    }

    val totalAllocatedExact = remember(selectedAllocations.toMap()) {
        selectedAllocations.values.fold(BigDecimal.ZERO) { acc, s ->
            val bd = s.toBigDecimalOrNull() ?: BigDecimal.ZERO
            acc.add(bd)
        }
    }

    val enteredAmountExact = remember(payAmountText) {
        payAmountText.toBigDecimalOrNull()
    }
    val validEnteredAmountExact = enteredAmountExact ?: BigDecimal.ZERO
    val isAmountPrecisionValid = PaymentDecimalConsistency.fitsCashEntry(validEnteredAmountExact, minorUnits)
    val areAllocationsValid = selectedAllocations.values.all {
        val amount = it.toBigDecimalOrNull()
        amount != null && amount > BigDecimal.ZERO && PaymentDecimalConsistency.fitsCashEntry(amount, minorUnits)
    }
    val currencySymbolOrCode = if (currency == "ILS") "₪" else currency

    val projectedBalance: BigDecimal? = remember(customer.balanceExact, enteredAmountExact) {
        if (customer.balanceExact == null) null
        else customer.balanceExact.subtract(validEnteredAmountExact)
    }

    val hasAllocations = selectedAllocations.isNotEmpty()
    val isOverAllocated = enteredAmountExact != null && totalAllocatedExact > enteredAmountExact
    val unallocatedSurplus = if (enteredAmountExact != null && enteredAmountExact > totalAllocatedExact) {
        enteredAmountExact.subtract(totalAllocatedExact)
    } else {
        BigDecimal.ZERO
    }

    LaunchedEffect(customer.id) {
        if (settlementTargets.isEmpty() && !isLoadingTargets && targetsError == null) {
            onRetryTargets?.invoke()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!effectiveSubmitting) {
                onDismiss()
            }
        },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = if (customer.balanceExact == null) {
                    lang.tr("دفعة من الزبون", "Customer payment")
                } else if (customer.balanceExact > BigDecimal.ZERO) {
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
                if (isRecoveringCurrentCustomer) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_pay_recovery_banner")
                    ) {
                        Text(
                            text = lang.tr(
                                "توجد عملية قبض غير مؤكدة بنفس المعرّف. تم استعادة البيانات الأصلية للتحقق من حالة الخادم دون تكرار القيد.",
                                "Recovering unverified collection with same operation key. Confirm to verify receipt status without duplicate posting."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else if (isBlockedByOtherDraft) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_pay_blocked_banner")
                    ) {
                        Text(
                            text = lang.tr(
                                "توجد عملية قبض غير محسومة لزبون آخر. يجب إتمامها أو التحقق من حالتها أولاً قبل بدء دفعة جديدة.",
                                "Another customer payment has an unresolved status. Resolve or verify that payment before starting a new one."
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                if (displayedInlineError != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("customer_pay_inline_error")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = displayedInlineError,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                // Notice: Unapplied advance payment banner or Allocated settlement summary
                if (!hasAllocations) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().testTag("customer_unapplied_advance_notice")
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = lang.tr(
                                    "تنبيه: تسجل الدفعة النقدية كدفعة مقدمة غير مخصصة (Unapplied Advance) ولا تسوي فواتير محددة مباشرة.",
                                    "Notice: Payments are recorded as Unapplied Advance and do not settle specific invoices directly."
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOverAllocated) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (isOverAllocated) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().testTag("customer_allocation_summary_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isOverAllocated) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isOverAllocated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = lang.tr(
                                        "إجمالي المخصص للفواتير والذمم: ${StitchBidiUtils.formatHumanMoney2dp(totalAllocatedExact, currency, lang)}",
                                        "Total allocated to targets: ${StitchBidiUtils.formatHumanMoney2dp(totalAllocatedExact, currency, lang)}"
                                    ),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isOverAllocated) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (unallocatedSurplus > BigDecimal.ZERO) {
                                Text(
                                    text = lang.tr(
                                        "المتبقي كدفعة مقدمة غير مخصصة: ${StitchBidiUtils.formatHumanMoney2dp(unallocatedSurplus, currency, lang)}",
                                        "Unallocated advance remainder: ${StitchBidiUtils.formatHumanMoney2dp(unallocatedSurplus, currency, lang)}"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (isOverAllocated) {
                                Text(
                                    text = lang.tr(
                                        "خطأ: مجموع المخصصات يتجاوز المبلغ المقبوض نقداً!",
                                        "Error: Total allocations exceed cash collected!"
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

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
                                customer.balanceExact == null -> lang.tr("الرصيد غير متاح", "Balance unavailable")
                                customer.balanceExact > BigDecimal.ZERO -> lang.tr(
                                    "الدين الحالي عليه (ذمة قائمة): ${StitchBidiUtils.formatHumanMoney2dp(customer.balanceExact, currency, lang)}",
                                    "Current outstanding debt: ${StitchBidiUtils.formatHumanMoney2dp(customer.balanceExact, currency, lang)}"
                                )
                                customer.balanceExact < BigDecimal.ZERO -> lang.tr(
                                    "له رصيد مقدم حالياً: ${StitchBidiUtils.formatHumanMoney2dp(customer.balanceExact.negate(), currency, lang)}",
                                    "Current advance credit: ${StitchBidiUtils.formatHumanMoney2dp(customer.balanceExact.negate(), currency, lang)}"
                                )
                                else -> {
                                    val zeroStr = StitchBidiUtils.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)
                                    lang.tr(
                                        "رصيد الحساب الحالي: خالص ($zeroStr)",
                                        "Current balance: Settled ($zeroStr)"
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                customer.balanceExact == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                customer.balanceExact > BigDecimal.ZERO -> financial.debit
                                customer.balanceExact < BigDecimal.ZERO -> financial.income
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Open Settlement Targets Selection Section (displayed when targets exist, loading, error, or has more)
                val showCustomerTargetsSection = settlementTargets.isNotEmpty() || targetsHasMore || isLoadingTargets || targetsError != null
                if (showCustomerTargetsSection) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("customer_settlement_targets_section")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("الذمم والفواتير المفتوحة (اختياري)", "Open Settlement Targets (Optional)"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                if (hasAllocations) {
                                    TextButton(
                                        onClick = { selectedAllocations.clear() },
                                        enabled = !effectiveSubmitting && !isRecovering,
                                        modifier = Modifier.heightIn(min = 32.dp).testTag("clear_customer_allocations_button")
                                    ) {
                                        Text(
                                            text = lang.tr("إلغاء التخصيص", "Clear"),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }

                            if (isLoadingTargets) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = lang.tr("جاري تحميل الذمم المفتوحة...", "Loading open targets..."),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (targetsError != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = targetsError,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { onRetryTargets?.invoke() },
                                        modifier = Modifier.heightIn(min = 32.dp)
                                    ) {
                                        Text(lang.tr("إعادة المحاولة", "Retry"), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            if (settlementTargets.isEmpty() && !isLoadingTargets && targetsError == null) {
                                if (targetsHasMore) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                        modifier = Modifier.fillMaxWidth().testTag("customer_targets_continuation_banner")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = lang.tr(
                                                    "توجد سجلات إضافية في السجل التاريخي — اضغط لمتابعة فحص الذمم المفتوحة",
                                                    "Historical records scan continuing — tap Load More to continue searching open targets"
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            TextButton(
                                                onClick = { onLoadMoreTargets?.invoke() },
                                                modifier = Modifier.heightIn(min = 36.dp).testTag("load_more_targets_button")
                                            ) {
                                                Text(
                                                    lang.tr("تحميل المزيد من الذمم", "Load More Targets"),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = lang.tr(
                                            "لا توجد فواتير أو أرصدة افتتاحية مفتوحة — تسجل الدفعة كدفعة مقدمة على الحساب.",
                                            "No open invoices or opening trade targets found — payment will be recorded as unapplied advance."
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            settlementTargets.forEach { target ->
                                val key = "${target.targetType}:${target.targetId}"
                                val isChecked = selectedAllocations.containsKey(key)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth().testTag("target_card_$key")
                                ) {
                                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    enabled = !effectiveSubmitting && !isRecovering,
                                                    onCheckedChange = { checked ->
                                                        localInlineError = null
                                                        if (checked) {
                                                            val targetStr = PaymentDecimalConsistency.cashEntryText(target.remainingAmountExact, minorUnits)
                                                            selectedAllocations[key] = targetStr.orEmpty()
                                                            if (targetStr != null && validEnteredAmountExact < target.remainingAmountExact) {
                                                                payAmountText = targetStr
                                                            }
                                                        } else {
                                                            selectedAllocations.remove(key)
                                                        }
                                                    },
                                                    modifier = Modifier.testTag("target_checkbox_${target.targetType}_${target.targetId}")
                                                )
                                                Column {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        if (target.targetType == "invoice") {
                                                            StitchBadge(
                                                                text = lang.tr("فاتورة #${target.targetNumber}", "Inv #${target.targetNumber}"),
                                                                tone = StitchBadgeTone.PRIMARY
                                                            )
                                                        } else if (target.targetType == "opening_trade_item") {
                                                            StitchBadge(
                                                                text = lang.tr("رصيد افتتاحي #${target.targetNumber}", "Opening #${target.targetNumber}"),
                                                                tone = StitchBadgeTone.WARNING
                                                            )
                                                        } else {
                                                            StitchBadge(
                                                                text = "${target.targetType} #${target.targetNumber}",
                                                                tone = StitchBadgeTone.NEUTRAL
                                                            )
                                                        }
                                                        Text(
                                                            text = target.businessDate,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        text = lang.tr(
                                                            "المتبقي: ${StitchBidiUtils.formatHumanMoney2dp(target.remainingAmountExact, target.currency, lang)}",
                                                            "Remaining: ${StitchBidiUtils.formatHumanMoney2dp(target.remainingAmountExact, target.currency, lang)}"
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                                    )
                                                }
                                            }

                                            if (isChecked) {
                                                SelectAllNumericOutlinedTextField(
                                                    value = selectedAllocations[key] ?: "",
                                                    onValueChange = { newVal ->
                                                        localInlineError = null
                                                        val sanitized = WesternFormatters.sanitizeWesternDigits(newVal)
                                                        selectedAllocations[key] = sanitized
                                                    },
                                                    enabled = !effectiveSubmitting && !isRecovering,
                                                    label = { Text(lang.tr("المخصص $currencySymbolOrCode", "Alloc $currencySymbolOrCode"), style = MaterialTheme.typography.labelSmall) },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    singleLine = true,
                                                    shape = RoundedCornerShape(8.dp),
                                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Ltr),
                                                    modifier = Modifier.width(115.dp).testTag("target_allocation_input_${target.targetType}_${target.targetId}")
                                                )
                                            }
                                        }
                                        if (isChecked) {
                                            val allocText = selectedAllocations[key].orEmpty()
                                            val requiresManual2dp = PaymentDecimalConsistency.cashEntryText(target.remainingAmountExact, minorUnits) == null
                                            val parsedAlloc = allocText.toBigDecimalOrNull()
                                            val isAllocValid = parsedAlloc != null &&
                                                parsedAlloc > BigDecimal.ZERO &&
                                                PaymentDecimalConsistency.fitsCashEntry(parsedAlloc, minorUnits)
                                            if (requiresManual2dp && !isAllocValid) {
                                                Text(
                                                    text = lang.tr(
                                                        "أدخل مبلغ التخصيص بخانتين عشريتين (المتبقي الدقيق لا يُعبَّأ تلقائياً)",
                                                        "Enter allocation at 2 decimals (exact remaining cannot auto-fill)"
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = financial.warning,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 12.dp)
                                                        .testTag("target_2dp_hint_${target.targetType}_${target.targetId}")
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (targetsHasMore && settlementTargets.isNotEmpty()) {
                                TextButton(
                                    onClick = { onLoadMoreTargets?.invoke() },
                                    modifier = Modifier.fillMaxWidth().testTag("load_more_targets_button")
                                ) {
                                    Text(
                                        lang.tr("تحميل المزيد من الذمم المفتوحة", "Load More Open Targets"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }

                // Cash Amount Input (Large readable field with Western numbers)
                SelectAllNumericOutlinedTextField(
                    value = payAmountText,
                    onValueChange = {
                        payAmountText = WesternFormatters.sanitizeWesternDigits(it)
                        localInlineError = null
                    },
                    enabled = !effectiveSubmitting && !isRecovering,
                    label = { Text(lang.tr("المبلغ المقبوض نقداً ($currencySymbolOrCode) *", "Cash Collected ($currencySymbolOrCode) *")) },
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
                if (validEnteredAmountExact > BigDecimal.ZERO && !isAmountPrecisionValid) {
                    Text(
                        text = lang.tr(
                            "دقة المبلغ تتجاوز الحد المسموح (${minOf(2, minorUnits)} خانات)",
                            "Amount precision exceeds allowed limit (${minOf(2, minorUnits)} decimals)"
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("payment_amount_precision_error")
                    )
                }

                // Quick Preset Chips (Full Debt, 50, 100, 200, Total Allocated)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (totalAllocatedExact > BigDecimal.ZERO) {
                        val totalAllocatedStr = PaymentDecimalConsistency.cashEntryText(totalAllocatedExact, minorUnits)
                        item {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                FilterChip(
                                    selected = payAmountText == totalAllocatedStr,
                                    enabled = !effectiveSubmitting && !isRecovering && totalAllocatedStr != null,
                                    onClick = {
                                        localInlineError = null
                                        totalAllocatedStr?.let { payAmountText = it }
                                    },
                                    label = {
                                        Text(
                                            text = lang.tr(
                                                "مجموع المخصص (${StitchBidiUtils.formatHumanMoney2dp(totalAllocatedExact, currency, lang)})",
                                                "Total Alloc (${StitchBidiUtils.formatHumanMoney2dp(totalAllocatedExact, currency, lang)})"
                                            ),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    modifier = Modifier.testTag("collect_preset_total_allocated")
                                )
                            }
                        }
                    }
                    if ((customer.balanceExact ?: BigDecimal.ZERO) > BigDecimal.ZERO) {
                        val fullStr = PaymentDecimalConsistency.cashEntryText(customer.balanceExact!!, minorUnits)
                        item {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                FilterChip(
                                    selected = (fullStr != null && payAmountText == fullStr) || (enteredAmountExact != null && enteredAmountExact == customer.balanceExact),
                                    enabled = !effectiveSubmitting && !isRecovering && fullStr != null,
                                    onClick = {
                                        localInlineError = null
                                        fullStr?.let { payAmountText = it }
                                    },
                                    label = {
                                        Text(
                                            text = lang.tr(
                                                "كامل الدين (${StitchBidiUtils.formatHumanMoney2dp(customer.balanceExact!!, currency, lang)})",
                                                "Full Debt (${StitchBidiUtils.formatHumanMoney2dp(customer.balanceExact!!, currency, lang)})"
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
                                enabled = !effectiveSubmitting && !isRecovering,
                                onClick = {
                                    localInlineError = null
                                    payAmountText = preset.toString()
                                },
                                label = { Text("$preset $currencySymbolOrCode") },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                val debtBalanceExact = customer.balanceExact
                if (debtBalanceExact != null && debtBalanceExact > BigDecimal.ZERO &&
                    PaymentDecimalConsistency.cashEntryText(debtBalanceExact, minorUnits) == null
                ) {
                    Text(
                        text = lang.tr(
                            "خيار «كامل الدين» غير متاح لأن الرصيد الدقيق (${StitchBidiUtils.formatHumanMoney2dp(debtBalanceExact, currency, lang)}) لا يُمثَّل بخانتين عشريتين للإدخال النقدي.",
                            "Full Debt shortcut is unavailable because exact debt (${StitchBidiUtils.formatHumanMoney2dp(debtBalanceExact, currency, lang)}) cannot be entered at 2 decimals."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_full_debt_unrepresentable_note")
                    )
                }

                // Projected Balance & Cash Drawer Status Banner (ST-35 Breakdown)
                if (validEnteredAmountExact > BigDecimal.ZERO) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            customer.balanceExact == null || projectedBalance == null -> MaterialTheme.colorScheme.surfaceVariant
                            projectedBalance > BigDecimal.ZERO -> MaterialTheme.colorScheme.surfaceVariant
                            else -> financial.incomeContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (customer.balanceExact == null || projectedBalance == null || projectedBalance > BigDecimal.ZERO) {
                                MaterialTheme.colorScheme.outlineVariant
                            } else {
                                financial.income.copy(alpha = 0.4f)
                            }
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
                                    customer.balanceExact == null || projectedBalance == null -> lang.tr("الرصيد غير متاح", "Balance unavailable")
                                    projectedBalance > BigDecimal.ZERO -> if (hasAllocations) {
                                        lang.tr(
                                            "الرصيد الدفتري المتبقي بعد القبض: ${StitchBidiUtils.formatHumanMoney2dp(projectedBalance, currency, lang)}",
                                            "Remaining ledger balance after payment: ${StitchBidiUtils.formatHumanMoney2dp(projectedBalance, currency, lang)}"
                                        )
                                    } else {
                                        lang.tr(
                                            "الرصيد الدفتري المتبقي بعد القبض: ${StitchBidiUtils.formatHumanMoney2dp(projectedBalance, currency, lang)} (دفعة غير مخصصة)",
                                            "Remaining ledger balance after payment: ${StitchBidiUtils.formatHumanMoney2dp(projectedBalance, currency, lang)} (Unapplied advance)"
                                        )
                                    }
                                    projectedBalance < BigDecimal.ZERO -> lang.tr(
                                        "الرصيد الدفتري بعد القبض: يصبح له رصيد مقدم (أمانة) ${StitchBidiUtils.formatHumanMoney2dp(projectedBalance.negate(), currency, lang)}",
                                        "After collection: Customer advance credit of ${StitchBidiUtils.formatHumanMoney2dp(projectedBalance.negate(), currency, lang)}"
                                    )
                                    else -> {
                                        val zeroStr = StitchBidiUtils.formatHumanMoney2dp(BigDecimal.ZERO, currency, lang)
                                        if (hasAllocations) {
                                            lang.tr(
                                                "الرصيد الدفتري بعد القبض: رصيد خالص ($zeroStr)",
                                                "After collection: Settled balance ($zeroStr)"
                                            )
                                        } else {
                                            lang.tr(
                                                "الرصيد الدفتري بعد القبض: رصيد متوازن دفترياً ($zeroStr — دفعة مقدمة غير مخصصة)",
                                                "After collection: Balanced ledger ($zeroStr — unapplied advance, does not settle invoices directly)"
                                            )
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = when {
                                    customer.balanceExact == null || projectedBalance == null || projectedBalance > BigDecimal.ZERO -> {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                    else -> financial.income
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
                    enabled = !effectiveSubmitting && !isRecovering,
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
                    if (!effectiveSubmitting && !isBlockedByOtherDraft) {
                        val allocInputs = selectedAllocations.mapNotNull { (key, text) ->
                            val amt = text.toBigDecimalOrNull() ?: return@mapNotNull null
                            if (amt <= BigDecimal.ZERO) return@mapNotNull null
                            val parts = key.split(":", limit = 2)
                            if (parts.size == 2) {
                                val scaledAmt = if (amt.scale() < minorUnits) {
                                    amt.setScale(minorUnits, RoundingMode.UNNECESSARY)
                                } else {
                                    amt
                                }
                                PaymentAllocationInput(parts[0], parts[1], scaledAmt)
                            } else null
                        }
                        val finalAmount = if (validEnteredAmountExact.scale() < minorUnits) {
                            validEnteredAmountExact.setScale(minorUnits, RoundingMode.UNNECESSARY)
                        } else {
                            validEnteredAmountExact
                        }
                        if (onConfirmPaymentExactWithResult != null) {
                            localSubmitting = true
                            localInlineError = null
                            onConfirmPaymentExactWithResult(customer.id, finalAmount, payNoteText, effectiveOperationId, allocInputs) { res ->
                                localSubmitting = false
                                if (res.isFailure) {
                                    localInlineError = res.exceptionOrNull()?.message
                                }
                            }
                        } else if (onConfirmPaymentExact != null) {
                            onConfirmPaymentExact(customer.id, finalAmount, payNoteText, effectiveOperationId, allocInputs)
                        } else {
                            onConfirmPayment?.invoke(customer.id, finalAmount.toDouble(), payNoteText, effectiveOperationId)
                        }
                    }
                },
                enabled = !effectiveSubmitting && !isBlockedByOtherDraft &&
                    validEnteredAmountExact > BigDecimal.ZERO && !isOverAllocated && isAmountPrecisionValid && areAllocationsValid &&
                    (onConfirmPaymentExactWithResult != null || onConfirmPaymentExact != null || onConfirmPayment != null),
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
                    text = when {
                        isRecoveringCurrentCustomer -> lang.tr("التحقق من حالة السند", "Verify Status & Retry")
                        validEnteredAmountExact > BigDecimal.ZERO -> if (hasAllocations) {
                            lang.tr(
                                "تأكيد قبض ${StitchBidiUtils.formatHumanMoney2dp(validEnteredAmountExact, currency, lang)} (مخصص ${StitchBidiUtils.formatHumanMoney2dp(totalAllocatedExact, currency, lang)})",
                                "Confirm ${StitchBidiUtils.formatHumanMoney2dp(validEnteredAmountExact, currency, lang)} (${StitchBidiUtils.formatHumanMoney2dp(totalAllocatedExact, currency, lang)} allocated)"
                            )
                        } else {
                            lang.tr(
                                "تأكيد قبض ${StitchBidiUtils.formatHumanMoney2dp(validEnteredAmountExact, currency, lang)} وإيداعها بالصندوق",
                                "Confirm ${StitchBidiUtils.formatHumanMoney2dp(validEnteredAmountExact, currency, lang)} Collection"
                            )
                        }
                        else -> lang.tr("تأكيد القبض بالصندوق", "Confirm Collection")
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!effectiveSubmitting) onDismiss() },
                enabled = !effectiveSubmitting,
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
    onConfirm: (name: String, phone: String, address: String) -> Unit,
    onConfirmWithResult: ((name: String, phone: String, address: String, onComplete: (Boolean) -> Unit) -> Unit)? = null,
    initialDraft: com.example.data.mobile.contract.StrictJsonObject? = null,
    isRecovering: Boolean = false
) {
    var newCustName by rememberSaveable {
        mutableStateOf(initialDraft?.properties?.get("name_ar").toNullableString() ?: "")
    }
    var newCustPhone by rememberSaveable {
        mutableStateOf(initialDraft?.properties?.get("phone").toNullableString() ?: "")
    }
    var newCustAddr by rememberSaveable {
        mutableStateOf(initialDraft?.properties?.get("address_ar").toNullableString() ?: "")
    }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
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
                if (isRecovering) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr("جاري استعادة العملية السابقة والتحقق من حالتها...", "Recovering in-flight operation from local store..."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = newCustName,
                    onValueChange = { newCustName = it },
                    enabled = !isSubmitting && !isRecovering,
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
                    enabled = !isSubmitting && !isRecovering,
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
                    enabled = !isSubmitting && !isRecovering,
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
                enabled = !isSubmitting && (isRecovering || newCustName.isNotBlank()),
                onClick = {
                    if (!isSubmitting && (isRecovering || newCustName.isNotBlank())) {
                        val trimmedName = newCustName.trim()
                        val trimmedPhone = newCustPhone.trim()
                        val trimmedAddr = newCustAddr.trim()
                        if (onConfirmWithResult != null) {
                            isSubmitting = true
                            onConfirmWithResult(trimmedName, trimmedPhone, trimmedAddr) { success ->
                                isSubmitting = false
                                if (success) {
                                    onDismiss()
                                }
                            }
                        } else {
                            onConfirm(trimmedName, trimmedPhone, trimmedAddr)
                        }
                    }
                },
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
                onClick = { if (!isSubmitting) onDismiss() },
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}
