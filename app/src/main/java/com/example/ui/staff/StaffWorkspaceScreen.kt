package com.example.ui.staff

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import com.example.data.mobile.outbox.OutboxHealthResult
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.mobile.readclient.CustomerDirectoryItem
import com.example.data.mobile.readclient.ProductCatalogItem
import com.example.model.AppLanguage
import com.example.ui.theme.PrimaryBlueLight

/**
 * Main ReadReady Workspace Screen for authenticated staff.
 *
 * Hosts dedicated tabs for scoped Product Catalog and Customer Directory reads.
 * Displays truthful exact wire strings without Double conversion or fake financial metrics.
 */
@Composable
fun StaffWorkspaceScreen(
    uiState: StaffWorkspaceUiState,
    onSelectTab: (StaffWorkspaceTab) -> Unit,
    onLoadMoreProducts: () -> Unit,
    onRefreshProducts: () -> Unit,
    onSearchProducts: (String) -> Unit,
    onLoadMoreCustomers: () -> Unit,
    onRefreshCustomers: () -> Unit,
    onSearchCustomers: (String) -> Unit,
    onSwitchCompany: () -> Unit,
    onLogout: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit,
    onRetryOutboxHealth: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang = uiState.language

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("staff_workspace_screen")
    ) {
        // Workspace Header
        StaffWorkspaceHeader(
            activeCompany = uiState.activeCompany,
            staffActor = uiState.staffActor,
            canSwitchCompany = uiState.activeCompany != null,
            isDarkTheme = uiState.isDarkTheme,
            lang = lang,
            onSwitchCompany = onSwitchCompany,
            onLogout = onLogout,
            onToggleLanguage = onToggleLanguage,
            onToggleTheme = onToggleTheme
        )

        // Outbox recovery warning banner (integrated within workspace preserving catalog)
        if (uiState.outboxHealthResult != null && !uiState.outboxHealthResult.isOperational) {
            StaffOutboxRecoveryBanner(
                healthResult = uiState.outboxHealthResult,
                lang = lang,
                onRetry = onRetryOutboxHealth,
                isRetrying = uiState.isOutboxRetrying
            )
        }

        // Navigation Tabs: Products / Customers
        TabRow(
            selectedTabIndex = if (uiState.selectedTab == StaffWorkspaceTab.PRODUCTS) 0 else 1,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = uiState.selectedTab == StaffWorkspaceTab.PRODUCTS,
                onClick = { onSelectTab(StaffWorkspaceTab.PRODUCTS) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("staff_tab_products"),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("سجل الأصناف", "Product Catalog"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
            Tab(
                selected = uiState.selectedTab == StaffWorkspaceTab.CUSTOMERS,
                onClick = { onSelectTab(StaffWorkspaceTab.CUSTOMERS) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("staff_tab_customers"),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.People,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("دليل العملاء", "Customer Directory"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            )
        }

        // Active Tab Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (uiState.selectedTab) {
                StaffWorkspaceTab.PRODUCTS -> {
                    StaffProductCatalogView(
                        products = uiState.products,
                        isLoading = uiState.isProductsLoading,
                        isLoadingMore = uiState.isProductsLoadingMore,
                        hasMore = uiState.productsHasMore,
                        searchQuery = uiState.productSearchQuery,
                        errorMessage = uiState.productsError,
                        currencyCode = uiState.activeCompany?.currency ?: "USD",
                        lang = lang,
                        onSearchChange = onSearchProducts,
                        onRefresh = onRefreshProducts,
                        onLoadMore = onLoadMoreProducts
                    )
                }
                StaffWorkspaceTab.CUSTOMERS -> {
                    StaffCustomerDirectoryView(
                        customers = uiState.customers,
                        isLoading = uiState.isCustomersLoading,
                        isLoadingMore = uiState.isCustomersLoadingMore,
                        hasMore = uiState.customersHasMore,
                        searchQuery = uiState.customerSearchQuery,
                        errorMessage = uiState.customersError,
                        lang = lang,
                        onSearchChange = onSearchCustomers,
                        onRefresh = onRefreshCustomers,
                        onLoadMore = onLoadMoreCustomers
                    )
                }
            }
        }
    }
}

/**
 * Top app bar and actor identity card for Staff Read Workspace.
 */
@Composable
private fun StaffWorkspaceHeader(
    activeCompany: SafeCompanyViewData?,
    staffActor: SafeStaffActorViewData?,
    canSwitchCompany: Boolean,
    isDarkTheme: Boolean,
    lang: AppLanguage,
    onSwitchCompany: () -> Unit,
    onLogout: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staff_workspace_header"),
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Company details
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Business,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = activeCompany?.companyName ?: lang.tr("منشأة نشطة", "Active Company"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (activeCompany != null) {
                        Text(
                            text = activeCompany.companyId,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Header actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleLanguage,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("staff_workspace_lang_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = lang.tr("تبديل اللغة", "Toggle Language"),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("staff_workspace_theme_toggle")
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = lang.tr("تبديل المظهر", "Toggle Theme"),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (canSwitchCompany) {
                        IconButton(
                            onClick = onSwitchCompany,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("staff_switch_company_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = lang.tr("تبديل المنشأة", "Switch Company"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = onLogout,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("staff_logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = lang.tr("تسجيل الخروج", "Logout"),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Staff identity & role row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr("الموظف: ", "Staff: ") + (staffActor?.actorName ?: "—"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = staffActor?.roleLabel ?: "Staff",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Truthful non-authority notice
            Text(
                text = lang.tr(
                    "عرض فقط",
                    "View only"
                ),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Product catalog view with search, exact wire prices, packaging units, and bounded pagination.
 */
private fun formatCurrencyAmount(amount: String, currencyCode: String): String {
    val code = currencyCode.trim()
    return if (code.isNotEmpty()) "$amount $code" else amount
}

@Composable
private fun StaffProductCatalogView(
    products: List<ProductCatalogItem>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    searchQuery: String,
    errorMessage: String?,
    currencyCode: String = "USD",
    lang: AppLanguage,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("staff_product_catalog_view")
    ) {
        // Search and refresh bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("staff_product_search_input"),
                placeholder = { Text(lang.tr("بحث بالاسم أو الباركود...", "Search by name or barcode..."), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchChange("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = lang.tr("مسح", "Clear"))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("staff_product_refresh_button"),
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = lang.tr("تحديث", "Refresh"),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Error message banner
        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("staff_product_error_banner"),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("staff_product_retry_button")
                    ) {
                        Text(lang.tr("إعادة المحاولة", "Retry"), fontSize = 12.sp)
                    }
                }
            }
        }

        // Loading indicator
        if (isLoading && products.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (products.isEmpty()) {
            // Empty view
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("staff_product_empty_view"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lang.tr("لا توجد أصناف في هذا السجل", "No products found in catalog"),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Product list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("staff_product_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(products, key = { it.id.value }) { product ->
                    ProductCatalogItemCard(product = product, currencyCode = currencyCode, lang = lang)
                }

                // Pagination footer
                if (hasMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoadingMore) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
                            } else {
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("staff_product_load_more_button"),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = lang.tr("تحميل المزيد من الأصناف", "Load More Products"),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual product card displaying exact snapshot wire fields truthfully.
 */
@Composable
private fun ProductCatalogItemCard(
    product: ProductCatalogItem,
    currencyCode: String = "USD",
    lang: AppLanguage
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staff_product_card_${product.id.value}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Name & SKU header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = product.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Card(
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = product.sku,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (product.barcode != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lang.tr("باركود: ", "Barcode: ") + product.barcode,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // Truthful pricing and stock row (exact strings, no Double conversion)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = lang.tr("سعر الوحدة (أساسي)", "Unit Price (Base)"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatCurrencyAmount(product.unitPrice.toPlainString(), currencyCode),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = lang.tr("الكمية المتوفرة", "Available Qty"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${product.availableQuantity.value.toPlainString()} ${product.baseUnit.name}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Default sales unit price
            Spacer(modifier = Modifier.height(6.dp))
            val salePriceText = if (product.defaultUnit.salePrice != null) {
                formatCurrencyAmount(product.defaultUnit.salePrice.toPlainString(), currencyCode)
            } else {
                lang.tr("غير محدد", "Unavailable")
            }
            Text(
                text = lang.tr("سعر البيع الافتراضي: ", "Default Sale Price: ") + salePriceText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Paired tax association (truthful, no inferred zero)
            Spacer(modifier = Modifier.height(4.dp))
            val taxText = if (product.taxRateId != null && product.taxRate != null) {
                "${product.taxRate.value.toPlainString()}%"
            } else {
                lang.tr("غير محددة", "Unassigned")
            }
            Text(
                text = lang.tr("الضريبة: ", "Tax: ") + taxText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Packaging units breakdown (if additional units exist)
            if (product.units.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = lang.tr("الوحدات المتاحة:", "Packaging Units:"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                product.units.forEach { unit ->
                    val unitPriceLabel = if (unit.salePrice != null) {
                        formatCurrencyAmount(unit.salePrice.toPlainString(), currencyCode)
                    } else {
                        lang.tr("غير محدد", "Unavailable")
                    }
                    Text(
                        text = "• ${unit.name} (${unit.code}): 1 = ${unit.conversionToBase.value.toPlainString()} ${product.baseUnit.name} | $unitPriceLabel",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Customer directory view with search and bounded cursor pagination.
 */
@Composable
private fun StaffCustomerDirectoryView(
    customers: List<CustomerDirectoryItem>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    searchQuery: String,
    errorMessage: String?,
    lang: AppLanguage,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("staff_customer_directory_view")
    ) {
        // Search & Refresh bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("staff_customer_search_input"),
                placeholder = { Text(lang.tr("بحث بالاسم أو الكود...", "Search by name or code..."), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchChange("") },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = lang.tr("مسح", "Clear"))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("staff_customer_refresh_button"),
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = lang.tr("تحديث", "Refresh"),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Error message banner
        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("staff_customer_error_banner"),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("staff_customer_retry_button")
                    ) {
                        Text(lang.tr("إعادة المحاولة", "Retry"), fontSize = 12.sp)
                    }
                }
            }
        }

        // Loading indicator
        if (isLoading && customers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (customers.isEmpty()) {
            // Empty view
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("staff_customer_empty_view"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lang.tr("لا توجد نتائج مطابقة في دليل العملاء", "No matching customers found"),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Customer directory list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("staff_customer_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(customers, key = { it.id.value }) { customer ->
                    CustomerDirectoryItemCard(customer = customer, lang = lang)
                }

                // Pagination footer
                if (hasMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoadingMore) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))
                            } else {
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("staff_customer_load_more_button"),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = lang.tr("تحميل المزيد من العملاء", "Load More Customers"),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual customer card in directory list.
 */
@Composable
private fun CustomerDirectoryItemCard(
    customer: CustomerDirectoryItem,
    lang: AppLanguage
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staff_customer_card_${customer.id.value}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (customer.isWalkIn) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = lang.tr("زبون نقدي", "Walk-in"),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lang.tr("الكود: ", "Code: ") + customer.code,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (customer.phone != null) {
                    Text(
                        text = lang.tr("الهاتف: ", "Phone: ") + customer.phone,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Minimal visible recovery warning banner integrated within the read workspace.
 * Preserves the full product catalog and customer directory while surfacing
 * truthful outbox recovery status and safe non-destructive retry.
 */
@Composable
fun StaffOutboxRecoveryBanner(
    healthResult: OutboxHealthResult,
    lang: AppLanguage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("outbox_recovery_banner"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = lang.tr(
                        "تنبيه وحدة التخزين المحلية",
                        "Local Outbox Storage Notice"
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = lang.tr(
                    "لم نقم بتعديل المبيعات المحفوظة. بعض السجلات تحتاج إلى مساعدة قبل المزامنة.",
                    "We have not changed stored sales. Some records need help before syncing."
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = lang.tr("يرجى التواصل مع الدعم الفني.", "Contact support"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRetry,
                enabled = !isRetrying,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .testTag("outbox_health_retry_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (isRetrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = lang.tr("إعادة المحاولة", "Retry"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

