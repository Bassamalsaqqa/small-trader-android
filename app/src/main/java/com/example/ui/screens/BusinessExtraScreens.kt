package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.ExpenseCategory
import com.example.model.Product
import com.example.model.TraderScreen
import com.example.model.UserRole
import com.example.model.UserSession
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
import kotlin.math.abs
import kotlin.math.max

/**
 * Returns an appropriate Material Icon for each ExpenseCategory.
 */
private fun expenseCategoryIcon(category: ExpenseCategory): ImageVector = when (category) {
    ExpenseCategory.RENT -> Icons.Default.Store
    ExpenseCategory.ELECTRICITY_WATER -> Icons.Default.Receipt
    ExpenseCategory.TRANSPORT -> Icons.Default.LocalShipping
    ExpenseCategory.SALARIES -> Icons.Default.Payments
    ExpenseCategory.MAINTENANCE -> Icons.Default.ShoppingBag
    ExpenseCategory.OTHER -> Icons.Default.AccountBalanceWallet
}

// ============================================================================
// 1. OPERATING EXPENSES & EXPENSE HISTORY (ST-44 v02 Canonical)
// ============================================================================

@Composable
fun ExpensesScreen(
    state: TraderUiState,
    onOpenAddExpenseDialog: () -> Unit,
    onDismissFeedback: () -> Unit = {}
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val canManageExpenses = state.currentSession.canManageExpenses()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ExpenseCategory?>(null) }

    val filteredExpenses = remember(state.expenses, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        state.expenses.filter { exp ->
            val matchesCat = selectedCategory == null || exp.category == selectedCategory
            if (!matchesCat) return@filter false
            if (q.isEmpty()) return@filter true
            exp.titleAr.lowercase().contains(q) ||
                exp.titleEn.lowercase().contains(q) ||
                exp.category.labelAr.lowercase().contains(q) ||
                exp.category.labelEn.lowercase().contains(q) ||
                exp.date.contains(q)
        }
    }

    val filteredTotalIls = remember(filteredExpenses) {
        filteredExpenses.sumOf { it.amountIls }
    }

    val todayExpenses = remember(state.expenses, state.todayDate) {
        state.expenses.filter { it.date.startsWith(state.todayDate) }
    }

    val feedbackMessage = if (lang == AppLanguage.AR) {
        state.lastExpenseActionMessageAr
    } else {
        state.lastExpenseActionMessageEn
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("expenses_screen")
    ) {
        // Sticky Header / Summary Section adhering to Stitch ST-44 v02
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Screen Title & Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.businessInfo.name(lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = lang.tr("المصاريف التشغيلية", "Operating Expenses"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (canManageExpenses) {
                        Button(
                            onClick = onOpenAddExpenseDialog,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("add_expense_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = lang.tr("تسجيل مصروف", "Add Expense"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // Cashier Role Notice
                if (!canManageExpenses) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cashier_expense_restriction_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = lang.tr(
                                    "صرف النثريات والمصاريف من الصندوق متاح للمالك أو المدير فقط.",
                                    "Recording cash drawer expenses is restricted to Owner or Manager."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Feedback Banner
                if (feedbackMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (state.isExpenseActionError) financial.debitContainer else financial.incomeContainer,
                        border = BorderStroke(
                            1.dp,
                            if (state.isExpenseActionError) financial.debit.copy(alpha = 0.35f)
                            else financial.income.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("expense_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = feedbackMessage,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (state.isExpenseActionError) financial.debit else financial.income,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissFeedback,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = lang.tr("إغلاق", "Dismiss"),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // ST-44 Hero Summary Card (Shift Cash vs Total)
                StitchCard(
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PointOfSale,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = lang.tr(
                                        "فترة الصندوق: الوردية الحالية (${state.todayDate})",
                                        "Cash Period: Current Shift (${state.todayDate})"
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StitchBadge(
                                text = lang.tr("خصم نقدي مباشر", "Cash Drawer Deduction"),
                                tone = StitchBadgeTone.WARNING
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("إجمالي مصاريف الوردية", "Today's Shift Expenses"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(state.shiftExpensesIls),
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = financial.debit
                                )
                                Text(
                                    text = lang.tr(
                                        "${todayExpenses.size} سندات مقيدة بالوردية",
                                        "${todayExpenses.size} shift vouchers"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = lang.tr("إجمالي كل السجلات", "All-Time Recorded"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(state.totalExpensesIls),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = lang.tr(
                                        "${state.expenses.size} سند إجمالي",
                                        "${state.expenses.size} total entries"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Search & Filter Row
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("expenses_search_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = lang.tr("مسح", "Clear"),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    placeholder = {
                        Text(
                            text = lang.tr("ابحث في البيان أو التاريخ...", "Search description or date..."),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                )

                // Category Chips Row (48dp min touch targets)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = {
                            Text(
                                lang.tr(
                                    "الكل (${WesternFormatters.formatInt(state.expenses.size)})",
                                    "All (${WesternFormatters.formatInt(state.expenses.size)})"
                                )
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    ExpenseCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            leadingIcon = {
                                Icon(
                                    imageVector = expenseCategoryIcon(cat),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text(cat.label(lang)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Expenses Ledger List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedCategory != null || searchQuery.isNotBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr(
                                    "نتائج التصفية (${filteredExpenses.size} سند)",
                                    "Filtered results (${filteredExpenses.size} entries)"
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = StitchBidiUtils.formatIls(filteredTotalIls),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = financial.debit
                            )
                        }
                    }
                }
            }

            if (filteredExpenses.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Receipt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = lang.tr("لا توجد مصاريف مطابقة للبحث", "No matching expenses found"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            if (searchQuery.isNotBlank() || selectedCategory != null) {
                                OutlinedButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedCategory = null
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(lang.tr("إعادة ضبط البحث", "Reset filters"))
                                }
                            }
                        }
                    }
                }
            } else {
                items(filteredExpenses, key = { it.id }) { expense ->
                    val isToday = expense.date.startsWith(state.todayDate)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("expense_row_${expense.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = expenseCategoryIcon(expense.category),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = expense.title(lang),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = expense.category.label(lang),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = "• ${expense.date}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (isToday) {
                                            StitchBadge(
                                                text = lang.tr("وردية اليوم", "Today"),
                                                tone = StitchBadgeTone.PRIMARY
                                            )
                                        }
                                    }
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "- " + StitchBidiUtils.formatIls(expense.amountIls),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = financial.debit
                                )
                                Text(
                                    text = lang.tr("سند #${expense.id.takeLast(4)}", "Voucher #${expense.id.takeLast(4)}"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ST-44 v02 Truthful Accounting Note
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = financial.income,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Text(
                            text = lang.tr(
                                "تُخصم المصاريف النقدية فوراً من رصيد صندوق الوردية الدفتري لضمان مطابقة الجرد الفعلي عند الإغلاق وتسليم العهدة.",
                                "Cash expenses are deducted immediately from today's shift book cash to ensure exact physical reconciliation at shift close."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 1B. ADD OPERATING EXPENSE MODAL (ST-45 v02 Canonical)
// ============================================================================

@Composable
fun AddExpenseDialog(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onConfirmExpense: (String, ExpenseCategory, Double) -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    var titleText by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(ExpenseCategory.TRANSPORT) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val parsedAmount = amountText.toDoubleOrNull() ?: 0.0
    val quickPresets = listOf(20.0, 50.0, 60.0, 100.0, 200.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = lang.tr("تسجيل مصروف تشغيلي", "Record Operating Expense"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = lang.tr("سند صرف نقدي فوري مباشر", "Direct cash drawer disbursement"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Shift Context
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                                "الوردية: ${state.todayDate} • المسؤول: ${state.currentSession.name}",
                                "Shift: ${state.todayDate} • By: ${state.currentSession.name}"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Amount Field
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = {
                            amountText = WesternFormatters.sanitizeWesternDigits(it)
                            localError = null
                        },
                        label = { Text(lang.tr("المبلغ المصروف نقداً (₪) *", "Cash Amount (₪) *")) },
                        placeholder = { Text("0.00") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("expense_amount_input")
                    )

                    // Quick Amount Presets (ST-45 v02)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickPresets.forEach { preset ->
                            val isSelected = abs(parsedAmount - preset) < 0.01
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    amountText = WesternFormatters.formatNumber(preset).removeSuffix(".00")
                                    localError = null
                                },
                                label = { Text("${preset.toInt()} ₪") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }

                // Description Field
                OutlinedTextField(
                    value = titleText,
                    onValueChange = {
                        titleText = it
                        localError = null
                    },
                    label = { Text(lang.tr("بيان المصروف (السبب) *", "Description (Reason) *")) },
                    placeholder = {
                        Text(
                            lang.tr(
                                "مثال: شحن بضاعة، أكياس تعبئة، ضيافة المحل...",
                                "e.g. Shipping goods, packaging, hospitality..."
                            )
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("expense_title_input")
                )

                // Category Selection
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = lang.tr("تصنيف المصروف *", "Expense Category *"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExpenseCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                leadingIcon = {
                                    Icon(
                                        imageVector = expenseCategoryIcon(cat),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text(cat.label(lang)) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }

                // Live Drawer Deduction Preview Card (ST-45 v02)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr("الرصيد الدفتري الحالي بالصندوق:", "Current drawer book cash:"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = StitchBidiUtils.formatIls(state.expectedBookCashIls),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }

                        if (parsedAmount > 0.0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("المبلغ المخصوم الآن:", "Amount deducted:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = financial.debit
                                )
                                Text(
                                    text = "- " + StitchBidiUtils.formatIls(parsedAmount),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = financial.debit
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("الرصيد الدفتري بعد الصرف:", "Drawer cash after deduction:"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(max(0.0, state.expectedBookCashIls - parsedAmount)),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (localError != null) {
                    Text(
                        text = localError!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = financial.debit
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        titleText.trim().isEmpty() -> {
                            localError = lang.tr("يرجى كتابة بيان المصروف", "Please enter an expense description")
                        }
                        parsedAmount <= 0.0 -> {
                            localError = lang.tr("يرجى إدخال مبلغ صحيح أكبر من صفر", "Please enter a valid amount greater than zero")
                        }
                        else -> {
                            onConfirmExpense(titleText.trim(), selectedCategory, parsedAmount)
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
                    .testTag("confirm_add_expense_button")
            ) {
                Text(
                    text = if (parsedAmount > 0.0) {
                        lang.tr(
                            "تأكيد صرف ${StitchBidiUtils.formatIls(parsedAmount)}",
                            "Confirm ${StitchBidiUtils.formatIls(parsedAmount)}"
                        )
                    } else {
                        lang.tr("تأكيد الصرف من الصندوق", "Confirm Drawer Expense")
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = lang.tr("إلغاء والعودة", "Cancel"),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    )
}

// ============================================================================
// 2. FINANCIAL REPORTS & RECONCILIATION HUB (ST-41, ST-42, ST-43 v02)
// ============================================================================

@Composable
fun FinancialReportsScreen(
    state: TraderUiState,
    onUpdateOpeningCash: (Double) -> Unit = {},
    onUpdateCountedCashText: (String) -> Unit = {},
    onToggleShiftClose: (Boolean) -> Unit = {}
) {
    val lang = state.language
    val session = state.currentSession
    val financial = TraderTheme.financial

    // Cashier Role Access Guard
    if (!session.canViewReports()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag("financial_reports_screen"),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = lang.tr("غير مصرح بالدخول", "Access Restricted"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = lang.tr(
                            "هذا القسم مخصص للمالك والإدارة فقط. المستخدم الحالي (${session.role.label(lang)}) لا يملك صلاحية الاطلاع على تقارير الصندوق الشاملة والذمم التراكمية.",
                            "This section is restricted to Owner and Management. Current role (${session.role.label(lang)}) cannot view cash drawer totals and cumulative debt ledgers."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }


    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("financial_reports_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ST-41 v02 Header & Period Selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = lang.tr("التقارير وسجلات النشاط", "Reports & Activity Hub"),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${state.businessInfo.name(lang)} • ${session.role.label(lang)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StitchBadge(
                        text = lang.tr("اليوم", "Today"),
                        tone = StitchBadgeTone.PRIMARY
                    )
                }

                Text(lang.tr("اليوم", "Today"), style = MaterialTheme.typography.labelLarge)

            }
        }

        // ST-41 High Signal Operational Summary Metrics
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                            text = lang.tr("حركة الأعمال اللحظية", "Live Business Operations"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        StitchBadge(
                            text = lang.tr("وردية اليوم", "Today's Shift"),
                            tone = StitchBadgeTone.NEUTRAL
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Total Sales Card
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = lang.tr("مبيعات مسجلة", "Recorded Sales"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(state.todaySalesTotalIls),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = lang.tr(
                                        "${state.todayInvoiceCount} فواتير",
                                        "${state.todayInvoiceCount} invoices"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Book Cash in Drawer Card
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = financial.incomeContainer.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = lang.tr("النقد بالدرج (دفتري)", "Book Cash in Drawer"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = StitchBidiUtils.formatIls(state.expectedBookCashIls),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = financial.income
                                )
                                Text(
                                    text = if (state.shiftCashVarianceIls != null && abs(state.shiftCashVarianceIls!!) < 0.005) {
                                        lang.tr("مطابق للجرد الفعلي", "Matches Count")
                                    } else {
                                        lang.tr("رصيد دفتري مسجل", "Recorded Book Cash")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = financial.income
                                )
                            }
                        }
                    }

                    // Receivables & Payables Quick Glance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("ذمم الزبائن", "Customer Receivables"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = StitchBidiUtils.formatIls(state.totalCustomerDebtsIls),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("مستحقات الموردين", "Supplier Payables"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = StitchBidiUtils.formatIls(state.totalSupplierPayablesIls),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = financial.warning
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            val netReceivable = state.totalCustomerDebtsIls - state.totalSupplierPayablesIls
                            Text(
                                text = lang.tr("صافي الذمم", "Net Position"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = (if (netReceivable >= 0) "+ " else "") + StitchBidiUtils.formatIls(netReceivable),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (netReceivable >= 0) financial.income else financial.debit
                            )
                        }
                    }
                }
            }
        }

        // ST-42: Expected Cash in Drawer & Shift Breakdown (Reconciliation Card)
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cash_drawer_reconciliation_card")
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("الصندوق والوردية الحالية", "Cash Drawer & Shift Ledger"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = lang.tr(
                                    "تاريخ الوردية: ${state.todayDate} • رصيد دفتري من واقع القيود",
                                    "Shift: ${state.todayDate} • Book balance from ledger"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StitchBadge(
                            text = if (state.isShiftClosedPreview) {
                                lang.tr("معاينة إغلاق", "Preview Closed")
                            } else {
                                lang.tr("مفتوحة الآن", "Open Now")
                            },
                            tone = if (state.isShiftClosedPreview) StitchBadgeTone.WARNING else StitchBadgeTone.SUCCESS
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Inflows & Outflows Ledger
                    ReportMetricRow(
                        label = lang.tr("رصيد بداية الوردية (العهدة الافتتاحية)", "Opening Drawer Cash (Float)"),
                        value = StitchBidiUtils.formatIls(state.startingDrawerCashIls),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )

                    ReportMetricRow(
                        label = lang.tr("مبيعات نقدية مقبوضة بالدرج (كاش)", "Cash Sales Received in Drawer"),
                        value = "+ " + StitchBidiUtils.formatIls(state.shiftCashSalesIls),
                        valueColor = financial.income
                    )

                    ReportMetricRow(
                        label = lang.tr("سداد ديون زبائن مقبوض نقداً اليوم", "Customer Debt Collected (Cash)"),
                        value = "+ " + StitchBidiUtils.formatIls(state.shiftCustomerCollectionsIls),
                        valueColor = if (state.shiftCustomerCollectionsIls > 0.0) financial.income else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ReportMetricRow(
                        label = lang.tr("دفعات نقدية للموردين من الدرج", "Supplier Payments from Drawer"),
                        value = "- " + StitchBidiUtils.formatIls(state.shiftSupplierDisbursementsIls),
                        valueColor = if (state.shiftSupplierDisbursementsIls > 0.0) financial.debit else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ReportMetricRow(
                        label = lang.tr("مصاريف ونثريات مدفوعة نقداً", "Shift Cash Expenses Paid"),
                        value = "- " + StitchBidiUtils.formatIls(state.shiftExpensesIls),
                        valueColor = if (state.shiftExpensesIls > 0.0) financial.debit else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Grand Book Total
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("النقد المتوقع دفترياً بالدرج", "Expected Book Cash in Drawer"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr(
                                    "المعادلة: العهدة + المقبوضات - المدفوعات",
                                    "Formula: Float + Inflows - Outflows"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = StitchBidiUtils.formatIls(state.expectedBookCashIls),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("expected_drawer_cash_value")
                        )
                    }
                }
            }
        }

        // ST-43: Physical Count, Variance & Shift Closing Card
        item {
            val variance = state.shiftCashVarianceIls
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shift_physical_count_variance_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr(
                            "جرد الصندوق الفعلي ومطابقة الوردية",
                            "Physical Cash Count & Shift Reconciliation"
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = lang.tr(
                            "أدخل النقد الموجود فعلياً بالدرج للتحقق من تطابق الرصيد الدفتري مع الأوراق النقدية قبل تسليم الوردية.",
                            "Enter the actual physically counted cash in the drawer to verify against book cash before shift handover."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Opening Float Preset Chips (ST-42)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("عهدة افتتاح الوردية:", "Opening Float:"),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(250.0, 350.0, 500.0).forEach { floatAmt ->
                                val isSel = abs(state.startingDrawerCashIls - floatAmt) < 0.01
                                FilterChip(
                                    selected = isSel,
                                    onClick = { onUpdateOpeningCash(floatAmt) },
                                    label = { Text("${floatAmt.toInt()} ₪") },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("shift_opening_preset_${floatAmt.toInt()}")
                                )
                            }
                        }
                    }

                    // Physical Cash Count Input Field (ST-43)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.countedPhysicalCashText,
                            onValueChange = onUpdateCountedCashText,
                            label = {
                                Text(
                                    lang.tr(
                                        "الجرد الفعلي النقدي بالدرج (₪)",
                                        "Actual Counted Cash in Drawer (₪)"
                                    )
                                )
                            },
                            placeholder = { Text(WesternFormatters.formatNumber(state.expectedBookCashIls)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("shift_counted_cash_input")
                        )
                        OutlinedButton(
                            onClick = {
                                onUpdateCountedCashText(
                                    WesternFormatters.formatNumber(state.expectedBookCashIls).removeSuffix(".00")
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("match_expected_cash_button")
                        ) {
                            Text(
                                text = lang.tr("مطابق للدفتري", "Match Book"),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Quick Cash Addition Chips (ST-43)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(20, 50, 100, 200).forEach { bill ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val current = state.countedPhysicalCashIls ?: 0.0
                                    val next = current + bill
                                    onUpdateCountedCashText(WesternFormatters.formatNumber(next).removeSuffix(".00"))
                                },
                                label = { Text("+$bill ₪") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    // Variance Banner (ST-43 v02: مطابق تماماً / عجز / فائض)
                    if (variance != null) {
                        val isExact = abs(variance) < 0.005
                        val isSurplus = variance > 0.005
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isExact -> financial.incomeContainer
                                isSurplus -> financial.warningContainer
                                else -> financial.debitContainer
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isExact -> financial.income.copy(alpha = 0.35f)
                                    isSurplus -> financial.warning.copy(alpha = 0.35f)
                                    else -> financial.debit.copy(alpha = 0.35f)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isExact) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = when {
                                            isExact -> financial.income
                                            isSurplus -> financial.warning
                                            else -> financial.debit
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = lang.tr(
                                            "الفارق (الفعلي - الدفتري):",
                                            "Variance (Counted - Book):"
                                        ),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Text(
                                    text = when {
                                        isExact -> lang.tr("مطابق تماماً (0.00 ₪)", "Exact Match (0.00 ₪)")
                                        isSurplus -> "+ " + StitchBidiUtils.formatIls(variance)
                                        else -> StitchBidiUtils.formatIls(variance)
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = when {
                                        isExact -> financial.income
                                        isSurplus -> financial.warning
                                        else -> financial.debit
                                    },
                                    modifier = Modifier.testTag("shift_cash_variance_text")
                                )
                            }
                        }
                    }

                    // Shift Closing Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.isShiftClosedPreview) {
                                    lang.tr(
                                        "حالة الوردية: مغلقة مؤقتاً (${state.shiftClosedTimestamp ?: ""})",
                                        "Shift Status: Closed (${state.shiftClosedTimestamp ?: ""})"
                                    )
                                } else {
                                    lang.tr(
                                        "حالة الوردية: مفتوحة وتستقبل العمليات",
                                        "Shift Status: Open and active"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (state.isShiftClosedPreview) financial.warning else financial.income,
                                modifier = Modifier.testTag("shift_close_status_text")
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onToggleShiftClose(!state.isShiftClosedPreview) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("toggle_shift_close_button")
                        ) {
                            Text(
                                text = if (state.isShiftClosedPreview) {
                                    lang.tr("إعادة فتح المعاينة", "Reopen Shift")
                                } else {
                                    lang.tr("معاينة إغلاق الوردية", "Preview Shift Close")
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }

        // ST-41 Detailed Reports & Insights (Sales, Receivables, Payables, Stock)
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr("سجلات التقارير الميدانية", "Operational Ledger Reports"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 1. Sales & Invoices
                    ReportMetricRow(
                        label = lang.tr("إجمالي مبيعات اليوم", "Today's Gross Sales"),
                        value = StitchBidiUtils.formatIls(state.todaySalesTotalIls),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        boldValue = true
                    )
                    ReportMetricRow(
                        label = lang.tr("عدد الفواتير المنفذة", "Invoices Issued"),
                        value = "${state.todayInvoiceCount} " + lang.tr("فاتورة", "invoices"),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReportMetricRow(
                        label = lang.tr("متوسط قيمة السلة (الفاتورة)", "Average Basket Value"),
                        value = StitchBidiUtils.formatIls(state.averageBasketIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 2. Receivables & Payables
                    ReportMetricRow(
                        label = lang.tr("ديون الزبائن المستحقة للمحل (لنا)", "Customer Debts (Receivables)"),
                        value = StitchBidiUtils.formatIls(state.totalCustomerDebtsIls),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        boldValue = true
                    )
                    ReportMetricRow(
                        label = lang.tr("أرصدة أمانات الزبائن (لهم)", "Customer Advance Balances"),
                        value = StitchBidiUtils.formatIls(state.totalCustomerAdvancesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReportMetricRow(
                        label = lang.tr("مستحقات الموردين القائمة (علينا)", "Supplier Payables"),
                        value = StitchBidiUtils.formatIls(state.totalSupplierPayablesIls),
                        valueColor = financial.warning,
                        boldValue = true
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 3. Operating Expenses Breakdown
                    ReportMetricRow(
                        label = lang.tr("مصاريف وردية اليوم", "Today's Shift Expenses"),
                        value = StitchBidiUtils.formatIls(state.shiftExpensesIls),
                        valueColor = financial.debit,
                        boldValue = true
                    )
                    ReportMetricRow(
                        label = lang.tr("مصاريف الأيام السابقة (المثبتة)", "Prior Days' Expenses"),
                        value = StitchBidiUtils.formatIls(state.historicalExpensesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReportMetricRow(
                        label = lang.tr("إجمالي المصاريف التراكمية", "Total Cumulative Expenses"),
                        value = StitchBidiUtils.formatIls(state.totalExpensesIls),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 4. Critical Inventory Alerts
                    val lowStock = state.lowStockProducts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("نواقص المخزون والكميات الحرجة", "Low Stock & Reorder Alerts"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        StitchBadge(
                            text = if (lowStock.isEmpty()) {
                                lang.tr("المخزون كافٍ", "Stock OK")
                            } else {
                                "${lowStock.size} " + lang.tr("أصناف حرجة", "critical")
                            },
                            tone = if (lowStock.isEmpty()) StitchBadgeTone.SUCCESS else StitchBadgeTone.WARNING
                        )
                    }
                }
            }
        }

        // Treasury & Extended Reports Shallow Preview Card (ST-41)
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shallow_treasury_reports_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lang.tr(
                            "معاينة الصناديق والبنوك والشيكات والتقارير التفصيلية",
                            "Cash/Bank Accounts, Cheques & Extended Reports"
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = lang.tr(
                            "• الصناديق والبنوك: صندوق الكاشير الرئيسي (${StitchBidiUtils.formatIls(state.drawerTotalCashIls)}) • الخزنة الرئيسية الخلفية (4,200.00 ₪ مرجعي) • حساب بنك فلسطين التجاري (12,500.00 ₪ مرجعي)\n• التحويلات والشيكات: توريد نقدي من الدرج للخزنة • حافظة شيكات زبائن آجلة برسم التحصيل (2 شيكات بقيمة 1,450.00 ₪)\n• تقارير إضافية (ترتبط بـ Laravel /api/v1): أعمار ديون الزبائن (Aging) • حركة الأصناف الأكثر مبيعاً • كشف الضريبة المجمّع",
                            "• Cash & Bank Accounts: Main POS Drawer (${StitchBidiUtils.formatIls(state.drawerTotalCashIls)}) • Back-office Safe (4,200.00 ₪ sample) • Bank of Palestine Current Account (12,500.00 ₪ sample)\n• Transfers & Cheques: Drawer-to-Safe cash drop • Post-dated customer cheques portfolio (2 cheques totaling 1,450.00 ₪)\n• Extended Reports (Laravel /api/v1): Receivables Aging • Top-Moving SKUs • Tax Summary"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportMetricRow(
    label: String,
    value: String,
    valueColor: Color,
    boldValue: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (boldValue) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = valueColor
        )
    }
}

// ============================================================================
// 3. USER PROFILE / MY ACCOUNT EXPERIENCE (ST-46 v02 Canonical)
// ============================================================================

@Composable
fun UserProfileScreen(
    state: TraderUiState,
    onSwitchUser: (UserSession) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val lang = state.language
    val session = state.currentSession
    val business = state.businessInfo
    val financial = TraderTheme.financial

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("user_profile_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ST-46 User Profile Identity Header Card
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // User Initials Avatar
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val initials = session.name.split(" ")
                                    .take(2)
                                    .mapNotNull { it.firstOrNull()?.toString() }
                                    .joinToString("")
                                    .ifEmpty { "أك" }
                                Text(
                                    text = initials,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StitchBadge(
                                    text = session.role.label(lang),
                                    tone = if (session.role == UserRole.OWNER) StitchBadgeTone.PRIMARY else StitchBadgeTone.NEUTRAL
                                )
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = "@${session.username}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("المتجر النشط", "Active Store"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = business.name(lang),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = lang.tr("الفرع الحالي", "Current Branch"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = session.branch(lang),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }

        // ST-46 Role Permissions & Access Scopes Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lang.tr("نطاق الصلاحيات المعتمدة", "Approved Permission Scopes"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    PermissionRow(
                        label = lang.tr("نقطة البيع وإصدار الفواتير", "POS & Invoice Issuance"),
                        allowed = true,
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("جرد الصندوق ومطابقة الوردية", "Drawer Count & Shift Close"),
                        allowed = session.canViewReports(),
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("تحصيل ديون الزبائن والسندات", "Customer Debt Collection"),
                        allowed = true,
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("سداد مستحقات الموردين وفواتير الشراء", "Supplier Settlements & Bills"),
                        allowed = session.canManageSuppliers(),
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("صرف المصاريف والنثريات من الصندوق", "Cash Drawer Expenses"),
                        allowed = session.canManageExpenses(),
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("التقارير المالية الشاملة وهوامش الربح", "Financial Reports & Margins"),
                        allowed = session.canViewReports(),
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("إدارة بيانات المتجر والإعدادات العامة", "Store Identity & Settings"),
                        allowed = session.canViewReports(),
                        lang = lang
                    )
                }
            }
        }

        // ST-46 Quick Display Preferences & Shortcuts
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr("تفضيلات الواجهة السريعة", "Quick Interface Preferences"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onToggleLanguage,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("profile_toggle_language_button")
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(lang.tr("EN / عربي", "عربي / EN"))
                        }

                        OutlinedButton(
                            onClick = onToggleTheme,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("profile_toggle_theme_button")
                        ) {
                            Icon(
                                imageVector = if (state.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (state.isDarkTheme) lang.tr("وضع فاتح", "Light")
                                else lang.tr("وضع داكن", "Dark")
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("profile_open_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(lang.tr("الانتقال للإعدادات العامة", "Open Business Settings"))
                    }
                }
            }
        }

        // ST-46 System & Local Runtime Diagnostics
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_security_session_preview_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lang.tr("معلومات النظام وبيئة التشغيل", "System & Runtime Environment"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    ReportMetricRow(
                        label = lang.tr("حالة التطبيق", "App Status"),
                        value = lang.tr("نسخة تشغيلية تجريبية محلية (In-Memory)", "In-Memory Demo Build"),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    ReportMetricRow(
                        label = lang.tr("بيئة التخزين", "Data Storage"),
                        value = lang.tr("ذاكرة التطبيق النشطة (RAM)", "Active Session RAM"),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReportMetricRow(
                        label = lang.tr("إصدار الواجهة", "UI Version"),
                        value = com.example.BuildConfig.VERSION_NAME,
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReportMetricRow(
                        label = lang.tr("العملة المعتمدة", "Currency"),
                        value = "${state.businessInfo.defaultCurrency} (₪) • أرقام قياسية 0-9",
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ST-46 Isolated Demo Role Switcher
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("demo_role_switcher_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr(
                            "أداة تبديل الدور التجريبية (Demo Role Switcher)",
                            "Demo Role Switcher (Diagnostics)"
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lang.tr(
                            "أداة مخصصة لمعاينة الفرق الميداني بين صلاحيات المالك والكاشير داخل هذا النموذج التجريبي المحلي.",
                            "Use this diagnostic switcher to inspect Owner vs. Cashier permissions in this local prototype."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.availableUsers.forEach { user ->
                            FilterChip(
                                selected = state.currentSession.id == user.id,
                                onClick = { onSwitchUser(user) },
                                label = {
                                    Text("${user.name} (${user.role.label(lang)})")
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("switch_role_${user.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    allowed: Boolean,
    lang: AppLanguage
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        StitchBadge(
            text = if (allowed) lang.tr("مسموح", "Allowed") else lang.tr("مقيد", "Restricted"),
            tone = if (allowed) StitchBadgeTone.SUCCESS else StitchBadgeTone.NEUTRAL
        )
    }
}

// ============================================================================
// 4. BUSINESS SETTINGS & SYSTEM LIMITATIONS (ST-47, ST-48, ST-51 v02)
// ============================================================================

@Composable
fun BusinessSettingsScreen(
    state: TraderUiState,
    onSwitchUser: (UserSession) -> Unit,
    onToggleLanguage: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
    onSelectPaperWidth: (Int) -> Unit = {},
    onOpenPrinterSheet: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onResetDemoData: (() -> Unit)? = null
) {
    val lang = state.language
    val b = state.businessInfo
    val session = state.currentSession
    val isOwnerOrManager = session.canViewReports()
    var showResetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("business_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ST-47 Store Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = lang.tr("الإعدادات العامة", "General Settings"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "${b.name(lang)} • ${b.city(lang)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. Language & Appearance (ST-48 v02: Strictly Binary Light/Dark, NO System Mode)
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
                    Text(
                        text = lang.tr("تفضيلات الواجهة والتشغيل", "Interface & Display Preferences"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Language Toggle (ST-48)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("لغة التطبيق", "Display Language"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = lang.tr("العربية (RTL الافتراضي) • English (LTR)", "Arabic (RTL default) • English (LTR)"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = true,
                            onClick = onToggleLanguage,
                            label = {
                                Text(lang.tr("العربية (تبديل EN)", "English (Switch عربي)"))
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_language_chip")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Theme Toggle (ST-48: Strictly Light / Dark, No unsupported "System" automatic)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("مظهر الواجهة", "Screen Theme"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = if (state.isDarkTheme) {
                                    lang.tr("وضع داكن (Dark Mode) مفعل", "Dark Mode is currently active")
                                } else {
                                    lang.tr("وضع فاتح (Light Mode) مفعل", "Light Mode is currently active")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = state.isDarkTheme,
                            onClick = onToggleTheme,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (state.isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    if (state.isDarkTheme) lang.tr("داكن (Dark)", "Dark")
                                    else lang.tr("فاتح (Light)", "Light")
                                )
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_theme_chip")
                        )
                    }
                }
            }
        }

        // 2. Receipt & Simulated Thermal Printer Preferences (ST-49 v02)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("إعدادات الطابعة الحرارية (وضع المحاكاة)", "Thermal Printer (Simulation Mode)"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Paper Width (58mm / 80mm)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("عرض ورق الإيصال", "Receipt Paper Width"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = lang.tr("يضبط قياس التذكرة للمعاينة والطباعة", "Sets ticket width for preview and printing"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(58, 80).forEach { widthMm ->
                                FilterChip(
                                    selected = state.receiptPaperWidthMm == widthMm,
                                    onClick = { onSelectPaperWidth(widthMm) },
                                    label = { Text("${widthMm}mm") },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("settings_paper_width_${widthMm}")
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Printer Setup Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("طابعة البلوتوث الافتراضية", "Simulated Bluetooth Printer"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = state.connectedPrinter?.let {
                                    lang.tr("محاكاة: ${it.name}", "Simulated: ${it.name}")
                                } ?: lang.tr("غير متصل بطابعة", "No printer connected"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenPrinterSheet,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_open_printer_button")
                        ) {
                            Icon(
                                imageVector = if (state.connectedPrinter != null) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(lang.tr("إعداد الطابعة", "Printer Setup"))
                        }
                    }
                }
            }
        }

        // 3. Store Identity & Legal Information (ST-47 v02)
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("بيانات المتجر المطبوعة على الإيصالات", "Store Identity on Receipts"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        text = b.name(lang),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = b.city(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = b.phone,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isOwnerOrManager) {
                        Text(
                            text = "${lang.tr("الرقم الضريبي للمنشأة:", "Business Tax ID:")} ${b.taxNumber}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "${lang.tr("العملة المعتمدة:", "Store Currency:")} ${b.defaultCurrency} (₪)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = if (isOwnerOrManager) {
                            lang.tr(
                                "بيانات المنشأة والعملة ثابتة في نسخة العرض الحالية لضمان تطابق الفواتير المسجلة.",
                                "Store identity and currency are fixed in this demo dataset to keep historical receipts consistent."
                            )
                        } else {
                            lang.tr(
                                "عرض فقط: تعديل بيانات المنشأة والضريبة مخصص للمالك.",
                                "Read-only: Store registration and tax settings are managed by the store owner."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 4. Active Profile & Permissions Shortcut
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("المستخدم النشط والصلاحيات", "Active Profile & Permissions"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${session.name} • ${session.role.label(lang)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenProfile,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_open_profile_button")
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(lang.tr("الملف الشخصي", "My Profile"))
                        }
                    }
                }
            }
        }

        // 5. ST-51 v02: About App & In-Memory System Boundaries
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("حول التطبيق وحدود النظام التجريبي", "About & In-Memory System Boundaries"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        text = lang.tr(
                            "يعمل هذا النموذج الأولي في الذاكرة الحية (In-Memory Demo). المعاملات النقدية وفواتير البيع وسندات المصاريف تُسجل في ذاكرة الجلسة الحالية وتتم إعادة ضبطها عند تصفير الذاكرة أو إعادة تشغيل التطبيق. لا يتطلب النظام مزامنة سحابية خلفية ويعمل بشكل كامل دون إنترنت.",
                            "This application operates in active session memory (In-Memory Demo). Sales transactions, shift cash drawer adjustments, and expenses are maintained in RAM and reset upon memory restart. The system functions fully offline with simulated peripherals."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Hardware Boundaries (ST-51 v02)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = lang.tr("حدود الملحقات والعتاد:", "Hardware & Peripheral Limits:"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr(
                                    "• الطابعة: محاكاة برمجية لتوليد ومعاينة إيصالات 58/80 ملم دون اقتران فيزيائي.\n• المسح الضوئي: إدخال باركود تجريبي عبر الواجهة.\n• درج النقدية: احتساب دفتري وتدقيق جرد برمجي دون منفذ فيزيائي.",
                                    "• Printer: Simulated 58/80mm preview without physical pairing.\n• Barcode: Software simulated barcode input.\n• Cash Drawer: Theoretical ledger calculation without physical port."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // In-Memory Reset Button (ST-51 v02)
                    if (onResetDemoData != null) Button(
                        onClick = { showResetDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr("إعادة ضبط البيانات التجريبية (In-Memory Reset)", "Reset Demo Session Data"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Text(
                        text = lang.tr(
                            "التاجر الصغير — ${com.example.BuildConfig.VERSION_NAME}",
                            "Small Trader — ${com.example.BuildConfig.VERSION_NAME}"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Confirmation Dialog for In-Memory Reset (ST-51 v02)
    if (showResetDialog && onResetDemoData != null) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = lang.tr("تأكيد إعادة ضبط البيانات التجريبية", "Confirm In-Memory Reset"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = lang.tr(
                        "سيؤدي هذا الإجراء إلى حذف العمليات المسجلة في الذاكرة للجلسة الحالية واستعادة البيانات الافتراضية للنموذج.",
                        "This action will reset the current session's in-memory transactions and restore default sample data."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetDemoData?.invoke()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(lang.tr("تأكيد التصفير", "Confirm Reset"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(lang.tr("إلغاء", "Cancel"))
                }
            }
        )
    }
}
