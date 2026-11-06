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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.ExpenseCategory
import com.example.model.TraderScreen
import com.example.model.UserSession
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

// ============================================================================
// 1. OPERATING EXPENSES & EXPENSE HISTORY (Prompt 14)
// ============================================================================

@Composable
fun ExpensesScreen(
    state: TraderUiState,
    onOpenAddExpenseDialog: () -> Unit,
    onDismissFeedback: () -> Unit = {}
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val canManageExpenses = state.currentSession.canManageSuppliers() || state.currentSession.canViewReports()

    var searchQuery by remember { mutableStateOf("") }
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
        // Summary & Primary Action Strip
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
                                text = lang.tr("مصاريف وردية اليوم", "Today's Shift Expenses"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.shiftExpensesIls),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = financial.debit
                            )
                        }
                        Column {
                            Text(
                                text = lang.tr("إجمالي كل السجلات", "All-Time Recorded"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.totalExpensesIls),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (canManageExpenses) {
                        Button(
                            onClick = onOpenAddExpenseDialog,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("add_expense_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = lang.tr("مصروف جديد", "New Expense"),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Cashier Role Notice
                if (!canManageExpenses) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cashier_expense_restriction_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
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

                // Search & Category Filter Row
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
                            IconButton(onClick = { searchQuery = "" }) {
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
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                )

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
                        }
                    )
                    ExpenseCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                            label = { Text(cat.label(lang)) }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedCategory != null || searchQuery.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lang.tr(
                                "نتائج التصفية (${WesternFormatters.formatInt(filteredExpenses.size)})",
                                "Filtered entries (${WesternFormatters.formatInt(filteredExpenses.size)})"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = WesternFormatters.formatIls(filteredTotalIls),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = financial.debit
                        )
                    }
                }
            }

            if (filteredExpenses.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = lang.tr("لا توجد مصاريف مطابقة للبحث", "No matching expenses found"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            if (searchQuery.isNotBlank() || selectedCategory != null) {
                                TextButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedCategory = null
                                    }
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
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
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
                                        Text(
                                            text = lang.tr("• وردية اليوم", "• Today's shift"),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "- ${WesternFormatters.formatIls(expense.amountIls)}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Truthful Audit Note (How mistakes are handled without fake reversal/edit buttons)
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                        Text(
                            text = lang.tr(
                                "ملاحظة ضبط الصندوق: كل مصروف يُسجل هنا يُخصم نقداً من صندوق الوردية الحالية (${state.todayDate}). لا يدعم الوضع التجريبي تعديل أو حذف مصروف بعد ترحيله؛ في حال وجود خطأ إدخال يتم توثيقه عند جرد الوردية.",
                                "Drawer audit note: Expenses recorded here are deducted in cash from today's shift drawer (${state.todayDate}). Editing or reversing posted expenses is not enabled in this demo; entry discrepancies are noted at shift close."
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

@Composable
fun AddExpenseDialog(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onConfirmExpense: (String, ExpenseCategory, Double) -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    var titleText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ExpenseCategory.ELECTRICITY_WATER) }
    var amountText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val parsedAmount = amountText.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = lang.tr("تسجيل مصروف نقدي من الصندوق", "Record Cash Drawer Expense"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = lang.tr(
                        "التاريخ: ${state.todayDate} • المصدر: صندوق الكاش الرئيسي",
                        "Date: ${state.todayDate} • Source: Main Cash Drawer"
                    ),
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
                            label = { Text(cat.label(lang)) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = titleText,
                    onValueChange = {
                        titleText = it
                        localError = null
                    },
                    label = { Text(lang.tr("بيان المصروف (مثال: أكياس تغليف، فاتورة مياه)", "Description (e.g. Packaging bags, water bill)")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("expense_title_input")
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = WesternFormatters.sanitizeWesternDigits(it)
                        localError = null
                    },
                    label = { Text(lang.tr("المبلغ المدفوع نقداً (₪)", "Cash Amount Paid (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("expense_amount_input")
                )

                if (parsedAmount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr("النقد المتوقع بالصندوق بعد الصرف:", "Expected drawer cash after expense:"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.drawerTotalCashIls - parsedAmount),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
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
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_add_expense_button")
            ) {
                Text(lang.tr("تأكيد الصرف من الصندوق", "Record Cash Expense"))
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

// ============================================================================
// 2. REPORTS, CASH DRAWER & SHIFT ACCOUNTING EXPLANATIONS (Prompt 15)
// ============================================================================

@Composable
fun FinancialReportsScreen(
    state: TraderUiState
) {
    val lang = state.language
    val session = state.currentSession
    val financial = TraderTheme.financial

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
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = lang.tr("غير مصرح بالدخول", "Access Restricted"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = lang.tr(
                            "هذا القسم مخصص للمالك والإدارة فقط. المستخدم الحالي (${session.role.label(lang)}) لا يملك صلاحية الاطلاع على تقارير الصندوق والذمم.",
                            "This section is restricted to Owner and Management. Current role (${session.role.label(lang)}) cannot view cash drawer and financial reports."
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
        // Period & Scope Header Banner
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
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
                            text = lang.tr(
                                "تقرير وردية اليوم (${state.todayDate})",
                                "Today's Shift Report (${state.todayDate})"
                            ),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lang.tr(
                                "الأرقام في جدول الصندوق أدناه تمثل الحركة النقدية الفعلية لليوم فقط ولا تخلط مصاريف الأيام السابقة.",
                                "The cash drawer table below reconciles today's shift cash inflows and outflows only, separated from prior days."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 1. Expected Cash in Drawer for Today's Shift (100% Reconciled Formula)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
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
                                text = lang.tr("النقد المتوقع في الصندوق (وردية اليوم)", "Expected Cash in Drawer (Today's Shift)"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = lang.tr(
                                    "تاريخ الوردية: ${state.todayDate} • رصيد دفتري قبل الجرد الفعلي",
                                    "Shift date: ${state.todayDate} • Book balance prior to physical count"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    ReportMetricRow(
                        label = lang.tr("رصيد افتتاح الصندوق صباحاً", "Opening Drawer Float"),
                        value = WesternFormatters.formatIls(state.startingDrawerCashIls),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )

                    ReportMetricRow(
                        label = lang.tr("مبيعات اليوم النقدية الصافية (كاش)", "Today's Net Cash Sales"),
                        value = "+ ${WesternFormatters.formatIls(state.shiftCashSalesIls)}",
                        valueColor = financial.income
                    )

                    ReportMetricRow(
                        label = lang.tr("تحصيل ديون زبائن نقداً اليوم", "Today's Customer Debt Collections"),
                        value = "+ ${WesternFormatters.formatIls(state.shiftCustomerCollectionsIls)}",
                        valueColor = if (state.shiftCustomerCollectionsIls > 0.0) {
                            financial.income
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    ReportMetricRow(
                        label = lang.tr("دفعات نقدية للموردين من الصندوق اليوم", "Today's Supplier Cash Payments"),
                        value = "- ${WesternFormatters.formatIls(state.shiftSupplierDisbursementsIls)}",
                        valueColor = if (state.shiftSupplierDisbursementsIls > 0.0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    ReportMetricRow(
                        label = lang.tr("مصاريف ونثريات مدفوعة من الصندوق اليوم", "Today's Cash Expenses Paid"),
                        value = "- ${WesternFormatters.formatIls(state.shiftExpensesIls)}",
                        valueColor = if (state.shiftExpensesIls > 0.0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("النقد المتوقع بالصندوق الآن", "Expected Cash in Drawer Now"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr(
                                    "لم يتم تسجيل جرد فعلي للأوراق النقدية بعد",
                                    "Physical bill count has not been recorded yet"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = WesternFormatters.formatIls(state.drawerTotalCashIls),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("expected_drawer_cash_value")
                        )
                    }
                }
            }
        }

        // 2. Today's Sales & Operating Expenses Breakdown (Separating Shift vs Prior Days)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr("ملخص مبيعات ومصاريف المتجر", "Sales & Operating Expenses Summary"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    ReportMetricRow(
                        label = lang.tr(
                            "إجمالي فواتير بيع اليوم (${WesternFormatters.formatInt(state.todayInvoiceCount)} فاتورة)",
                            "Today's Total Invoices (${WesternFormatters.formatInt(state.todayInvoiceCount)} invoices)"
                        ),
                        value = WesternFormatters.formatIls(state.todaySalesTotalIls),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        boldValue = true
                    )

                    ReportMetricRow(
                        label = lang.tr("• منها مبيعات نقدية دخلت الصندوق", "• Paid in cash (in drawer)"),
                        value = WesternFormatters.formatIls(state.shiftCashSalesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ReportMetricRow(
                        label = lang.tr("• منها مبيعات آجلة على الذمة (لم تدخل الصندوق)", "• Sold on credit (receivable, not in drawer)"),
                        value = WesternFormatters.formatIls(state.shiftCreditSalesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    ReportMetricRow(
                        label = lang.tr("مصاريف اليوم (${state.todayDate}) المخصومة من الصندوق", "Today's Shift Expenses (${state.todayDate})"),
                        value = WesternFormatters.formatIls(state.shiftExpensesIls),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )

                    ReportMetricRow(
                        label = lang.tr("مصاريف الأيام السابقة (مخصومة في ورديات سابقة)", "Prior Days' Expenses (previous shifts)"),
                        value = WesternFormatters.formatIls(state.historicalExpensesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ReportMetricRow(
                        label = lang.tr("إجمالي المصاريف المسجلة في السجل", "Cumulative Recorded Expenses"),
                        value = WesternFormatters.formatIls(state.totalExpensesIls),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        boldValue = true
                    )
                }
            }
        }

        // 3. Receivables & Payables Outside Cash Drawer
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = lang.tr("الديون والالتزامات التراكمية (خارج الصندوق النقدي)", "Receivables & Payables (Outside Cash Drawer)"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = lang.tr(
                                "هذه الأرصدة تمثل ذمماً للزبائن والموردين وليست مبالغ نقدية موجودة داخل الدرج حالياً.",
                                "These balances represent customer and supplier accounts, not physical cash inside the drawer."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    ReportMetricRow(
                        label = lang.tr("ديون الزبائن للمحل (لنا عند الزبائن)", "Customer Debts Owed to Store"),
                        value = WesternFormatters.formatIls(state.totalCustomerDebtsIls),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        boldValue = true
                    )

                    ReportMetricRow(
                        label = lang.tr("أرصدة مقدمة للزبائن (لهم عند المحل)", "Customer Advance Balances"),
                        value = WesternFormatters.formatIls(state.totalCustomerAdvancesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    ReportMetricRow(
                        label = lang.tr("مستحقات الموردين على المحل (ذمم دائنة)", "Supplier Payables Owed by Store"),
                        value = WesternFormatters.formatIls(state.totalSupplierPayablesIls),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        boldValue = true
                    )

                    ReportMetricRow(
                        label = lang.tr("دفعات مقدمة للموردين (لنا عند الموردين)", "Store Advances with Suppliers"),
                        value = WesternFormatters.formatIls(state.totalSupplierAdvancesIls),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant
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
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (boldValue) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = valueColor
        )
    }
}

// ============================================================================
// 3. USER PROFILE / MY ACCOUNT EXPERIENCE (Prompt 16)
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
        // 1. Identity & Business Context Card
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.name,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = session.role.label(lang),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = "@${session.username}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("المنشأة الحالية", "Active Business"),
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
                                text = lang.tr("الفرع / نقطة البيع", "Branch / POS"),
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

        // 2. Role Permissions & Scope Card
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
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
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("صلاحيات الدور الحالي (${session.role.label(lang)})", "Current Role Capabilities (${session.role.label(lang)})"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PermissionRow(
                        label = lang.tr("البيع النقدي والآجل، إصدار الفواتير، وتحصيل دفعات الزبائن", "POS sales, receipts, and collecting customer payments"),
                        allowed = true,
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("تعديل أسعار البيع، الاطلاع على التكلفة، وضبط كميات المخزون", "Edit selling prices, view product costs, and adjust inventory"),
                        allowed = session.canManageInventory(),
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("تسجيل فواتير الموردين، صرف دفعات الموردين، وتسجيل المصاريف", "Record supplier bills, supplier payments, and operating expenses"),
                        allowed = session.canManageSuppliers(),
                        lang = lang
                    )
                    PermissionRow(
                        label = lang.tr("الاطلاع على تقارير مطابقة الصندوق والمركز المالي", "Access cash drawer reconciliation and financial reports"),
                        allowed = session.canViewReports(),
                        lang = lang
                    )
                }
            }
        }

        // 3. Personal Preferences & Quick Link to Settings
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr("تفضيلات العرض السريعة", "Quick Display Preferences"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onToggleLanguage,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("profile_toggle_language_button")
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(lang.tr("اللغة: العربية (EN)", "Lang: English (عربي)"))
                        }
                        OutlinedButton(
                            onClick = onToggleTheme,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("profile_toggle_theme_button")
                        ) {
                            Icon(
                                imageVector = if (state.isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (state.isDarkTheme) lang.tr("المظهر: داكن", "Theme: Dark")
                                else lang.tr("المظهر: فاتح", "Theme: Light")
                            )
                        }
                    }

                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("profile_open_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(lang.tr("الانتقال إلى إعدادات التطبيق والمتجر", "Go to Store & App Settings"))
                    }
                }
            }
        }

        // 4. Isolated Developer / Demo Role Switcher (Clearly Separated from Real Auth)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lang.tr(
                            "أداة معاينة الأدوار (للاختبار والعرض التوضيحي فقط)",
                            "Demo Role Switcher (For Testing & Evaluation Only)"
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lang.tr(
                            "لا يتضمن هذا العرض التجريبي المحلي تسجيل دخول حقيقي أو تغيير كلمة مرور أو إدارة جلسات عن بُعد. يمكنك تبديل الدور هنا لمعاينة الفرق بين صلاحيات المالك والكاشير.",
                            "Real authentication, password change, and remote session management are not connected in this offline demo. Switch roles below to inspect Owner vs. Cashier permissions."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
    val financial = TraderTheme.financial
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (allowed) financial.incomeContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = if (allowed) lang.tr("مسموح", "Allowed") else lang.tr("مقيد", "Restricted"),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (allowed) financial.income else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// ============================================================================
// 4. SETTINGS & APP PREFERENCES (Prompt 17)
// ============================================================================

@Composable
fun BusinessSettingsScreen(
    state: TraderUiState,
    onSwitchUser: (UserSession) -> Unit,
    onToggleLanguage: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
    onSelectPaperWidth: (Int) -> Unit = {},
    onOpenPrinterSheet: () -> Unit = {},
    onOpenProfile: () -> Unit = {}
) {
    val lang = state.language
    val b = state.businessInfo
    val session = state.currentSession
    val isOwnerOrManager = session.canViewReports()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("business_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Display Language & Appearance Preferences (Working Controls)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = lang.tr("اللغة والمظهر", "Language & Appearance"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("لغة الواجهة", "Display Language"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = lang.tr("الأرقام والعملة تُعرض دائماً بالأرقام الغربية (0-9)", "Numbers and currency always use Western digits (0-9)"),
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("مظهر الشاشة", "Screen Theme"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = if (state.isDarkTheme) {
                                    lang.tr("الوضع الداكن مفعل حالياً", "Dark theme is currently active")
                                } else {
                                    lang.tr("الوضع الفاتح مفعل حالياً", "Light theme is currently active")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = state.isDarkTheme,
                            onClick = onToggleTheme,
                            label = {
                                Text(
                                    if (state.isDarkTheme) lang.tr("داكن", "Dark")
                                    else lang.tr("فاتح", "Light")
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

        // 2. Receipt & Simulated Printer Preferences (Working Controls)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            text = lang.tr("إعدادات الإيصال والطابعة الحرارية", "Receipt & Thermal Printer Preferences"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("عرض ورق الإيصال الحراري", "Thermal Paper Width"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = lang.tr("يضبط تنسيق الإيصال عند المعاينة والطباعة", "Adjusts receipt layout for preview and printing"),
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr("طابعة البلوتوث (وضع المحاكاة)", "Simulated Bluetooth Printer"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = state.connectedPrinter?.let {
                                    lang.tr("متصل محاكاةً بـ: ${it.name}", "Simulated connection: ${it.name}")
                                } ?: lang.tr("غير متصل بطابعة حالياً", "No printer currently selected"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenPrinterSheet,
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

        // 3. Store & Business Identity (Permission-aware presentation)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
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
                            imageVector = Icons.Default.Store,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("بيانات المتجر المطبوعة على الفواتير", "Store Identity on Receipts"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "${lang.tr("العملة المعتمدة:", "Store Currency:")} ${b.defaultCurrency} (₪)",
                        style = MaterialTheme.typography.labelMedium,
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

        // 4. Active Profile Shortcut & Truthful App Mode
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("settings_open_profile_button")
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(lang.tr("الملف الشخصي", "My Profile"))
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(top = 2.dp)
                        )
                        Text(
                            text = lang.tr(
                                "حالة التطبيق: نسخة عرض محلية تعمل بدون إنترنت. جميع الفواتير والدفعات تُحفظ في ذاكرة الجلسة الحالية، وتتوفر أداة تبديل الدور التوضيحية داخل صفحة الملف الشخصي.",
                                "App status: Offline local demo. Sales, ledgers, and expenses are stored in the active session memory. The demo role switcher is available inside My Profile."
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
