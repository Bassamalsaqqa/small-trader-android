package com.example.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ProductSearch
import com.example.model.Customer
import com.example.model.Supplier
import com.example.model.TraderScreen
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBidiUtils
import com.example.ui.navigation.MerchantAction
import com.example.ui.navigation.allowed
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.ReadStatus
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal

/**
 * Stitch ST-03 Global Quick Action Bottom Sheet (إجراء سريع).
 *
 * Implements the approved Stitch ST-03 specification:
 * 1. Clean Modal Bottom Sheet titled "إضافة" with clear dismiss affordance.
 * 2. Primary 4 tactical actions: بيع جديد (dominant), شراء جديد, قبض من زبون, دفع لمورد.
 * 3. Concise, non-verbose action labels without redundant paragraphs.
 * 4. Subdued, initially collapsed expander "المزيد من العمليات" for role-filtered secondary controls
 *    (صنف جديد, زبون جديد, مورد جديد, مصروف جديد).
 * 5. Full TalkBack bounds with >= 48dp minimum interactive touch geometry.
 * 6. Preserves existing MerchantAction callbacks and test tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionSheet(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onAction: (MerchantAction) -> Unit
) {
    val lang = state.language
    var expandedMore by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(width = 40.dp, height = 4.dp)
                ) {}
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Sheet Header: Title and Close Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lang.tr("إضافة", "Add"),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = lang.tr("إجراء سريع", "Quick action"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = lang.tr("إغلاق", "Close"),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 1. Primary Action: بيع جديد (Dominant Highlighted Row)
            Surface(
                onClick = { onAction(MerchantAction.SALE) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("quick_sale")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingCart,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = lang.tr("بيع جديد", "New sale"),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )

                            }
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 2. Primary Action: شراء جديد
            if (MerchantAction.PURCHASE.allowed(state.currentSession)) {
                QuickActionRow(
                    title = lang.tr("شراء جديد", "New purchase"),
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    iconBgColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    testTag = "quick_purchase",
                    onClick = { onAction(MerchantAction.PURCHASE) }
                )
            }

            // 3. Primary Action: قبض من زبون
            if (MerchantAction.COLLECT.allowed(state.currentSession)) {
                QuickActionRow(
                    title = lang.tr("قبض من زبون", "Receive customer payment"),
                    icon = Icons.Default.Payments,
                    iconBgColor = Color(0xFFE8F5E9),
                    iconTint = Color(0xFF14804A),
                    testTag = "quick_collect",
                    onClick = { onAction(MerchantAction.COLLECT) }
                )
            }

            // 4. Primary Action: دفع لمورد
            if (MerchantAction.PAY.allowed(state.currentSession)) {
                QuickActionRow(
                    title = lang.tr("دفع لمورد", "Pay supplier"),
                    icon = Icons.Default.AccountBalanceWallet,
                    iconBgColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    testTag = "quick_pay",
                    onClick = { onAction(MerchantAction.PAY) }
                )
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // 5. Subdued Expander: المزيد من العمليات
            Surface(
                onClick = { expandedMore = !expandedMore },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = lang.tr("المزيد من العمليات", "More actions"),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        )
                    }

                    Icon(
                        imageVector = if (expandedMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Secondary Creation Controls (Expanded, Role-Filtered)
            AnimatedVisibility(
                visible = expandedMore,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (MerchantAction.PRODUCT.allowed(state.currentSession)) {
                        QuickActionSubRow(
                            title = lang.tr("صنف جديد", "New product"),
                            icon = Icons.Default.Inventory2,
                            testTag = "quick_product",
                            onClick = { onAction(MerchantAction.PRODUCT) }
                        )
                    }

                    QuickActionSubRow(
                        title = lang.tr("زبون جديد", "New customer"),
                        icon = Icons.Default.PersonAdd,
                        testTag = "quick_customer",
                        onClick = { onAction(MerchantAction.CUSTOMER) }
                    )

                    if (MerchantAction.SUPPLIER.allowed(state.currentSession)) {
                        QuickActionSubRow(
                            title = lang.tr("مورد جديد", "New supplier"),
                            icon = Icons.Default.LocalShipping,
                            testTag = "quick_supplier",
                            onClick = { onAction(MerchantAction.SUPPLIER) }
                        )
                    }

                    if (MerchantAction.EXPENSE.allowed(state.currentSession)) {
                        QuickActionSubRow(
                            title = lang.tr("مصروف جديد", "New expense"),
                            icon = Icons.Default.Receipt,
                            testTag = "quick_expense",
                            onClick = { onAction(MerchantAction.EXPENSE) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionRow(
    title: String,
    icon: ImageVector,
    iconBgColor: Color,
    iconTint: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = iconBgColor,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun QuickActionSubRow(
    title: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun normalizePartyPhone(phone: String?): String =
    WesternFormatters.sanitizeWesternDigits(phone.orEmpty())
        .replace("-", "")
        .replace(" ", "")
        .lowercase()

private fun matchesQuickCustomer(c: Customer, normalizedQuery: String, normalizedPhoneQuery: String): Boolean {
    if (normalizedQuery.isEmpty()) return true
    if (ProductSearch.normalize(c.nameAr).contains(normalizedQuery) ||
        ProductSearch.normalize(c.nameEn).contains(normalizedQuery) ||
        ProductSearch.normalize(c.addressAr.orEmpty()).contains(normalizedQuery) ||
        ProductSearch.normalize(c.addressEn.orEmpty()).contains(normalizedQuery) ||
        ProductSearch.normalize(c.code.orEmpty()).contains(normalizedQuery)
    ) return true
    return normalizedPhoneQuery.isNotEmpty() && normalizePartyPhone(c.phone).contains(normalizedPhoneQuery)
}

private fun matchesQuickSupplier(s: Supplier, normalizedQuery: String, normalizedPhoneQuery: String): Boolean {
    if (normalizedQuery.isEmpty()) return true
    if (ProductSearch.normalize(s.nameAr).contains(normalizedQuery) ||
        ProductSearch.normalize(s.nameEn).contains(normalizedQuery) ||
        ProductSearch.normalize(s.companyAr.orEmpty()).contains(normalizedQuery) ||
        ProductSearch.normalize(s.companyEn.orEmpty()).contains(normalizedQuery) ||
        ProductSearch.normalize(s.code.orEmpty()).contains(normalizedQuery)
    ) return true
    return normalizedPhoneQuery.isNotEmpty() && normalizePartyPhone(s.phone).contains(normalizedPhoneQuery)
}

@Composable
fun QuickPartyPickerDialog(
    state: TraderUiState,
    action: MerchantAction,
    onDismiss: () -> Unit,
    onSearchCustomers: (String) -> Unit,
    onSearchSuppliers: (String) -> Unit,
    onSelectCustomer: (Customer) -> Unit,
    onSelectSupplier: (Supplier) -> Unit,
    onCreateCustomer: (() -> Unit)? = null,
    onCreateSupplier: (() -> Unit)? = null,
    onRetryDirectory: (() -> Unit)? = null
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val currency = state.businessInfo.defaultCurrency
    val isCollect = action == MerchantAction.COLLECT
    val isDemo = state.currentSession.isDemoSession
    var localQuery by rememberSaveable(action) {
        mutableStateOf(if (isCollect) state.customerSearchQuery else state.supplierSearchQuery)
    }

    val trimmedQuery = localQuery.trim()
    val normalizedQuery = ProductSearch.normalize(trimmedQuery)
    val normalizedPhoneQuery = normalizePartyPhone(trimmedQuery)
    val activeServerQuery = if (isCollect) state.customerSearchQuery else state.supplierSearchQuery
    val searchError = if (isCollect) state.customerSearchError else state.supplierSearchError
    val isSearching = !state.isScopeInvalidated && trimmedQuery.isNotBlank() && (
        (if (isCollect) state.isSearchingCustomers else state.isSearchingSuppliers) ||
            (!isDemo && !activeServerQuery.trim().equals(trimmedQuery, ignoreCase = true) && searchError == null)
        )
    val directoryStatus = state.readStatusFor(if (isCollect) TraderScreen.CUSTOMERS else TraderScreen.SUPPLIERS)
    val hasMoreDirectory = if (isCollect) state.customersHasMore else state.suppliersHasMore

    val customers = if (!isCollect || state.isScopeInvalidated) {
        emptyList()
    } else if (trimmedQuery.isBlank()) {
        state.customers
    } else if (!isDemo && !isSearching && activeServerQuery.trim().equals(trimmedQuery, ignoreCase = true) && state.customerSearchResults != null) {
        state.customerSearchResults
    } else {
        val localSource = if (isDemo && state.customerSearchResults != null && activeServerQuery.trim().equals(trimmedQuery, ignoreCase = true)) {
            state.customerSearchResults
        } else {
            state.customers
        }
        localSource.filter { matchesQuickCustomer(it, normalizedQuery, normalizedPhoneQuery) }
    }

    val suppliers = if (isCollect || state.isScopeInvalidated) {
        emptyList()
    } else if (trimmedQuery.isBlank()) {
        state.suppliers
    } else if (!isDemo && !isSearching && activeServerQuery.trim().equals(trimmedQuery, ignoreCase = true) && state.supplierSearchResults != null) {
        state.supplierSearchResults
    } else {
        val localSource = if (isDemo && state.supplierSearchResults != null && activeServerQuery.trim().equals(trimmedQuery, ignoreCase = true)) {
            state.supplierSearchResults
        } else {
            state.suppliers
        }
        localSource.filter { matchesQuickSupplier(it, normalizedQuery, normalizedPhoneQuery) }
    }

    val retrySearch: () -> Unit = {
        if (isCollect) onSearchCustomers(localQuery) else onSearchSuppliers(localQuery)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isCollect) {
                    lang.tr("قبض من زبون — اختر الحساب", "Receive Payment — Choose Customer")
                } else {
                    lang.tr("دفع لمورد — اختر الحساب", "Pay Supplier — Choose Account")
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = localQuery,
                    onValueChange = { next ->
                        localQuery = next
                        if (isCollect) onSearchCustomers(next) else onSearchSuppliers(next)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "ابحث بالاسم، رقم الهاتف، أو الكود...",
                                "Search by name, phone, or code..."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (isSearching || localQuery.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .size(18.dp)
                                            .testTag("quick_party_search_progress"),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (localQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            localQuery = ""
                                            if (isCollect) onSearchCustomers("") else onSearchSuppliers("")
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = lang.tr("مسح", "Clear"),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("quick_party_search_input")
                )

                if (trimmedQuery.isBlank() && hasMoreDirectory && !isDemo) {
                    Text(
                        text = lang.tr(
                            "يتم عرض أول 30 حساباً — ابحث بالاسم أو الهاتف أو الكود للوصول لبقية الحسابات",
                            "Showing first 30 accounts — search by name, phone, or code for more"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val isListEmpty = if (isCollect) customers.isEmpty() else suppliers.isEmpty()
                val baseListEmpty = if (isCollect) state.customers.isEmpty() else state.suppliers.isEmpty()

                if (state.isScopeInvalidated) {
                    Text(
                        text = lang.tr("انتهت صلاحية الجلسة أو تغير نطاق الشركة", "Session expired or company scope changed"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                } else if (trimmedQuery.isBlank() && baseListEmpty && !isDemo && directoryStatus == ReadStatus.LOADING) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = lang.tr("جارٍ تحميل الحسابات...", "Loading accounts..."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (trimmedQuery.isBlank() && baseListEmpty && !isDemo && directoryStatus == ReadStatus.FAILED) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = lang.tr("تعذر تحميل قائمة الحسابات من الخادم", "Could not load accounts from server"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        if (onRetryDirectory != null) {
                            OutlinedButton(
                                onClick = onRetryDirectory,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("quick_party_directory_retry")
                            ) {
                                Text(lang.tr("إعادة المحاولة", "Retry"))
                            }
                        }
                    }
                } else if (!isSearching && trimmedQuery.isNotBlank() && searchError != null && isListEmpty) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = lang.tr("تعذر البحث عن الحسابات", "Account search failed"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(
                            onClick = retrySearch,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("quick_party_search_retry")
                        ) {
                            Text(lang.tr("إعادة المحاولة", "Retry"))
                        }
                    }
                } else if (isSearching && isListEmpty) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("جاري البحث في الحسابات...", "Searching accounts..."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (isListEmpty) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = when {
                                trimmedQuery.isNotBlank() -> lang.tr("لا توجد حسابات مطابقة للبحث", "No accounts match this search")
                                isCollect -> lang.tr("لا يوجد زبائن مسجلون", "No registered customers")
                                else -> lang.tr("لا يوجد موردون مسجلون", "No registered suppliers")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (trimmedQuery.isBlank() && isCollect && onCreateCustomer != null) {
                            TextButton(
                                onClick = onCreateCustomer,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(lang.tr("إضافة زبون جديد", "Add new customer"))
                            }
                        } else if (trimmedQuery.isBlank() && !isCollect && onCreateSupplier != null) {
                            TextButton(
                                onClick = onCreateSupplier,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(lang.tr("إضافة مورد جديد", "Add new supplier"))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isCollect) {
                            items(customers, key = { it.id }) { customer ->
                                val bal = customer.balanceExact
                                val balText = StitchBidiUtils.formatHumanMoney2dp(bal, currency, lang)
                                val balColor = when {
                                    bal == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    bal > BigDecimal.ZERO -> financial.debit
                                    bal < BigDecimal.ZERO -> financial.income
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val subParts = buildList {
                                    customer.phone?.takeIf { it.isNotBlank() }?.let { add("\u2066$it\u2069") }
                                    customer.address(lang).takeIf { it.isNotBlank() }?.let { add(it) }
                                    if (customer.code.isNullOrBlank() && customer.phone.isNullOrBlank()) {
                                        add("#${customer.id.takeLast(6)}")
                                    }
                                }.joinToString(" • ")
                                QuickPartyRow(
                                    id = customer.id,
                                    name = customer.name(lang),
                                    code = customer.code,
                                    subtitle = subParts,
                                    balanceText = balText,
                                    balanceColor = balColor,
                                    onClick = { onSelectCustomer(customer) }
                                )
                            }
                        } else {
                            items(suppliers, key = { it.id }) { supplier ->
                                val advExact = supplier.advancesExact
                                val owedExact = supplier.balanceOwedExact
                                val effectiveBal = when {
                                    advExact != null && advExact > BigDecimal.ZERO -> advExact.negate()
                                    else -> owedExact
                                }
                                val balText = StitchBidiUtils.formatHumanMoney2dp(effectiveBal, currency, lang)
                                val balColor = when {
                                    effectiveBal == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    effectiveBal > BigDecimal.ZERO -> financial.warning
                                    effectiveBal < BigDecimal.ZERO -> financial.income
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val subParts = buildList {
                                    supplier.phone?.takeIf { it.isNotBlank() }?.let { add("\u2066$it\u2069") }
                                    supplier.company(lang).takeIf { it.isNotBlank() }?.let { add(it) }
                                    if (supplier.code.isNullOrBlank() && supplier.phone.isNullOrBlank()) {
                                        add("#${supplier.id.takeLast(6)}")
                                    }
                                }.joinToString(" • ")
                                QuickPartyRow(
                                    id = supplier.id,
                                    name = supplier.name(lang),
                                    code = supplier.code,
                                    subtitle = subParts,
                                    balanceText = balText,
                                    balanceColor = balColor,
                                    onClick = { onSelectSupplier(supplier) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
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
private fun QuickPartyRow(
    id: String,
    name: String,
    code: String?,
    subtitle: String,
    balanceText: String,
    balanceColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag("quick_party_option_$id")
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!code.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = balanceText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFontFamily
                ),
                color = balanceColor
            )
        }
    }
}
