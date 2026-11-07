package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.WesternFormatters
import com.example.ui.components.PhaseOneCard
import com.example.ui.components.PhaseOneStatusBadge
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

/**
 * Rebuilt Native New Sale (POS) Catalog Screen for Small Trader (التاجر الصغير).
 *
 * Implements Phase 1 UX Architecture:
 * 1. Calm Top Instrument Bar: Combines Arabic-normalized product/barcode search,
 *    clear query shortcut, and camera scanner trigger into a single 48dp tactile capsule,
 *    paired with borderless category pills and a pinned "+ Custom" item trigger.
 * 2. Exception-Based Catalog Ledger: Healthy items display only title, unit, and tabular price
 *    with a quiet circular "+" affordance. Redundant "In stock" labels are stripped so that
 *    low-stock and out-of-stock items stand out immediately by exception.
 * 3. Single-Axis Selection Geometry: Selected items tint the row calmly, display the live
 *    line calculation (`38.00 ₪ × 2 = 76.00 ₪`), and replace the price with a tactile stepper
 *    (`- qty +`) featuring 48dp minimum accessible touch targets.
 * 4. State-Responsive Checkout Dock: Remains a quiet 48dp status bar when the basket is empty,
 *    and elevates into a high-contrast checkout bar with an expandable line-item drawer as soon
 *    as products are added.
 * 5. Adaptive POS Support: Scales fluidly on Compact phones and Wide tablet viewports while
 *    preserving 100% of test tags and financial guards.
 */
@Composable
fun NewSaleScreen(
    state: TraderUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectCategory: (ProductCategory) -> Unit,
    onAddProduct: (Product) -> Unit,
    onDecrementProduct: (String) -> Unit,
    onRemoveProduct: (String) -> Unit,
    onClearBasket: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenCustomItem: () -> Unit,
    onProceedToInvoiceReview: () -> Unit,
    onOpenSaleOptions: () -> Unit = onProceedToInvoiceReview
) {
    val lang = state.language
    var isBasketExpanded by remember { mutableStateOf(false) }
    val showBarcodeInRows = state.searchQuery.any { it.isDigit() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("new_sale_screen")
    ) {
        val isWidePos = maxWidth >= 840.dp

        if (isWidePos) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                ) {
                    PosSearchAndCategoryHeader(
                        state = state,
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectCategory = onSelectCategory,
                        onAddSingleFilteredProduct = onAddProduct,
                        onOpenScanner = onOpenScanner,
                        onOpenCustomItem = onOpenCustomItem
                    )

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
                    )

                    if (state.filteredProducts.isEmpty()) {
                        ZeroResultCatalogState(
                            searchQuery = state.searchQuery,
                            selectedCategory = state.selectedCategory,
                            allCategoriesMatchCount = state.allCategoriesMatchCount,
                            lang = lang,
                            onShowAllCategories = { onSelectCategory(ProductCategory.ALL) },
                            onClearSearch = {
                                onSearchQueryChange("")
                                if (state.selectedCategory != ProductCategory.ALL) {
                                    onSelectCategory(ProductCategory.ALL)
                                }
                            },
                            onOpenCustomItem = onOpenCustomItem,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("product_catalog_list"),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(state.filteredProducts, key = { it.id }) { product ->
                                val qtyInBasket = state.quantityInBasket(product.id)
                                ProductSaleCatalogRow(
                                    product = product,
                                    lang = lang,
                                    isDarkTheme = state.isDarkTheme,
                                    quantityInBasket = qtyInBasket,
                                    showBarcode = showBarcodeInRows,
                                    onAdd = { onAddProduct(product) },
                                    onDecrement = { onDecrementProduct(product.id) }
                                )
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }

                VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
                )

                Column(
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    WidePersistentCartPane(
                        state = state,
                        onIncrement = onAddProduct,
                        onDecrement = onDecrementProduct,
                        onRemove = onRemoveProduct,
                        onClearBasket = onClearBasket,
                        onProceedToPayment = onProceedToInvoiceReview,
                        onOpenSaleOptions = onOpenSaleOptions
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PosSearchAndCategoryHeader(
                    state = state,
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectCategory = onSelectCategory,
                    onAddSingleFilteredProduct = onAddProduct,
                    onOpenScanner = onOpenScanner,
                    onOpenCustomItem = onOpenCustomItem
                )

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
                )

                if (state.filteredProducts.isEmpty()) {
                    ZeroResultCatalogState(
                        searchQuery = state.searchQuery,
                        selectedCategory = state.selectedCategory,
                        allCategoriesMatchCount = state.allCategoriesMatchCount,
                        lang = lang,
                        onShowAllCategories = { onSelectCategory(ProductCategory.ALL) },
                        onClearSearch = {
                            onSearchQueryChange("")
                            if (state.selectedCategory != ProductCategory.ALL) {
                                onSelectCategory(ProductCategory.ALL)
                            }
                        },
                        onOpenCustomItem = onOpenCustomItem,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("product_catalog_list"),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(state.filteredProducts, key = { it.id }) { product ->
                            val qtyInBasket = state.quantityInBasket(product.id)
                            ProductSaleCatalogRow(
                                product = product,
                                lang = lang,
                                isDarkTheme = state.isDarkTheme,
                                quantityInBasket = qtyInBasket,
                                showBarcode = showBarcodeInRows,
                                onAdd = { onAddProduct(product) },
                                onDecrement = { onDecrementProduct(product.id) }
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                BasketStickyBottomSheet(
                    state = state,
                    isExpanded = isBasketExpanded,
                    onToggleExpand = { isBasketExpanded = !isBasketExpanded },
                    onIncrement = onAddProduct,
                    onDecrement = onDecrementProduct,
                    onRemove = onRemoveProduct,
                    onClearBasket = {
                        onClearBasket()
                        isBasketExpanded = false
                    },
                    onProceedToPayment = onProceedToInvoiceReview,
                    onOpenSaleOptions = onOpenSaleOptions
                )
            }
        }
    }
}

@Composable
private fun PosSearchAndCategoryHeader(
    state: TraderUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectCategory: (ProductCategory) -> Unit,
    onAddSingleFilteredProduct: (Product) -> Unit,
    onOpenScanner: () -> Unit,
    onOpenCustomItem: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val focusManager = LocalFocusManager.current
    val searchInteractionSource = remember { MutableInteractionSource() }
    val isSearchFocused by searchInteractionSource.collectIsFocusedAsState()
    val hasActiveQuery = state.searchQuery.isNotEmpty()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Unified Search & Barcode Capsule (48dp height, 48dp scanner button)
            val searchContainerColor = if (isSearchFocused || hasActiveQuery) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            }
            val searchBorderColor = when {
                isSearchFocused -> MaterialTheme.colorScheme.primary
                hasActiveQuery -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else -> Color.Transparent
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(searchContainerColor)
                    .border(
                        width = if (isSearchFocused) 1.5.dp else 1.dp,
                        color = searchBorderColor,
                        shape = RoundedCornerShape(12.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(14.dp))
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = if (isSearchFocused || hasActiveQuery) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (state.searchQuery.isEmpty()) {
                        Text(
                            text = lang.tr(
                                "ابحث باسم الصنف أو الباركود...",
                                "Search product or barcode..."
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = searchInteractionSource,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                val candidate = state.filteredProducts.firstOrNull()
                                if (candidate != null && state.filteredProducts.size == 1) {
                                    onAddSingleFilteredProduct(candidate)
                                }
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product_search_input")
                    )
                }

                if (hasActiveQuery) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("clear_search_button")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = lang.tr("مسح البحث", "Clear search"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Subtle vertical divider separating text input from barcode scanner trigger
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                IconButton(
                    onClick = onOpenScanner,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("sale_scan_barcode_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = lang.tr("إدخال أو مسح باركود", "Enter or Scan Barcode"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Borderless Category Strip + Pinned Custom Quick Item Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(ProductCategory.entries, key = { it.name }) { category ->
                        val isSelected = state.selectedCategory == category
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(
                                    role = Role.Tab,
                                    onClick = { onSelectCategory(category) }
                                )
                                .testTag("category_chip_${category.name}")
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                modifier = Modifier.height(34.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = category.label(lang),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Pinned Custom Item Trigger (48dp touch height, 34dp visual capsule)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(
                            role = Role.Button,
                            onClick = onOpenCustomItem
                        )
                        .testTag("sale_custom_item_chip")
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 11.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = lang.tr("مادة حرة", "Custom"),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Truthful Scan / Stock Limit Feedback Banner
            val bannerMsg = if (lang == AppLanguage.AR) state.lastScannedBannerAr else state.lastScannedBannerEn
            AnimatedVisibility(
                visible = bannerMsg != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (bannerMsg != null) {
                    val isError = state.isBannerError
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isError) financial.debitContainer else financial.incomeContainer,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isError) financial.debit.copy(alpha = 0.35f) else financial.income.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .testTag("sale_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isError) financial.debit else financial.income)
                            )
                            Text(
                                text = bannerMsg,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isError) financial.debit else financial.income,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZeroResultCatalogState(
    searchQuery: String,
    selectedCategory: ProductCategory,
    allCategoriesMatchCount: Int,
    lang: AppLanguage,
    onShowAllCategories: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenCustomItem: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .testTag("zero_result_search_state"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (searchQuery.isNotBlank()) {
                    lang.tr(
                        "لا توجد أصناف مطابقة لـ «${searchQuery.trim()}»",
                        "No products matching \"${searchQuery.trim()}\""
                    )
                } else {
                    lang.tr("لا توجد أصناف في هذا القسم", "No products in this category")
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            if (selectedCategory != ProductCategory.ALL && allCategoriesMatchCount > 0) {
                Text(
                    text = lang.tr(
                        "يوجد ${WesternFormatters.formatInt(allCategoriesMatchCount)} صنف مطابق في الأقسام الأخرى",
                        "Found ${WesternFormatters.formatInt(allCategoriesMatchCount)} matching item(s) in other categories"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedCategory != ProductCategory.ALL && allCategoriesMatchCount > 0) {
                    Button(
                        onClick = onShowAllCategories,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("search_all_categories_button")
                    ) {
                        Text(
                            text = lang.tr(
                                "عرض في كل الأقسام (${WesternFormatters.formatInt(allCategoriesMatchCount)})",
                                "Show in All (${WesternFormatters.formatInt(allCategoriesMatchCount)})"
                            )
                        )
                    }
                }

                if (searchQuery.isNotEmpty() || selectedCategory != ProductCategory.ALL) {
                    OutlinedButton(
                        onClick = onClearSearch,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("zero_result_clear_button")
                    ) {
                        Text(lang.tr("مسح البحث", "Clear Search"))
                    }
                }

                FilledTonalButton(
                    onClick = onOpenCustomItem,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("zero_result_custom_item_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(lang.tr("مادة حرة بالسعر", "Custom Item"))
                }
            }
        }
    }
}

/**
 * Exception-Based Retail POS Catalog Row:
 * - Unselected items display only product title, packaging unit, and tabular price with a
 *   quiet circular "+" affordance. Low stock and out of stock appear strictly by exception.
 * - Selected items tint the row calmly, display live line calculation (`38.00 ₪ × 2 = 76.00 ₪`),
 *   and replace the price with a single unified `- qty +` stepper pill with 48dp hit targets.
 */
@Composable
private fun ProductSaleCatalogRow(
    product: Product,
    lang: AppLanguage,
    isDarkTheme: Boolean,
    quantityInBasket: Int,
    showBarcode: Boolean,
    onAdd: () -> Unit,
    onDecrement: () -> Unit
) {
    val financial = TraderTheme.financial
    val isSelected = quantityInBasket > 0
    val isOutOfStock = product.isStockTracked && product.stockCount <= 0
    val isMaxStockReached = product.isStockTracked && product.stockCount > 0 && quantityInBasket >= product.stockCount

    val rowBackground = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.30f else 0.42f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(rowBackground)
            .clickable(enabled = !isOutOfStock) {
                onAdd()
            }
            .testTag("product_card_${product.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading 3.5dp selection indicator bar
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .fillMaxHeight()
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.5.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start Column: Product Name + Unit / Contextual Line Total / Exception Stock State
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = product.name(lang),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutOfStock) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                if (isSelected) {
                    // Active in basket: Show unit price × qty = line total & stock limit if reached
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (quantityInBasket > 1) {
                                "${WesternFormatters.formatIls(product.priceIls)} × ${WesternFormatters.formatInt(quantityInBasket)} = ${WesternFormatters.formatIls(product.priceIls * quantityInBasket)}"
                            } else {
                                "${WesternFormatters.formatIls(product.priceIls)} • ${product.unit(lang)}"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (isMaxStockReached) {
                            Text(
                                text = lang.tr(
                                    "• الحد الأقصى (${WesternFormatters.formatInt(product.stockCount)})",
                                    "• Max (${WesternFormatters.formatInt(product.stockCount)})"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = financial.warning
                            )
                        }
                    }
                } else {
                    // Unselected: Show packaging unit + exception-only stock status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = product.unit(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (isOutOfStock) 0.6f else 0.9f
                            )
                        )

                        if (showBarcode && product.barcode.isNotBlank()) {
                            Text(
                                text = "• ${product.barcode}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        when {
                            isOutOfStock -> {
                                Text(
                                    text = lang.tr("• نفد المخزون", "• Out of stock"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = financial.debit
                                )
                            }
                            product.isStockTracked && product.isLowStock -> {
                                Text(
                                    text = lang.tr(
                                        "• متبقي ${WesternFormatters.formatInt(product.stockCount)}",
                                        "• ${WesternFormatters.formatInt(product.stockCount)} left"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = financial.warning
                                )
                            }
                            !product.isStockTracked -> {
                                Text(
                                    text = lang.tr("• خدمة", "• Service"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // End Column: Price + Quiet Add Trigger OR Unified Tactile Stepper Capsule
            if (!isSelected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = WesternFormatters.formatIls(product.priceIls),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isOutOfStock) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (isOutOfStock) {
                        PhaseOneStatusBadge(
                            text = lang.tr("نفد", "Out"),
                            textColor = financial.debit,
                            containerColor = financial.debitContainer,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    } else {
                        // 48dp accessible hit area with a subtle 30dp circular "+" affordance inside
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClick = onAdd
                                )
                                .testTag("add_product_${product.id}")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = lang.tr("إضافة للسلة", "Add to basket"),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Unified Single-Axis Stepper Pill (- qty +) with 48dp hit targets
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    role = Role.Button,
                                    onClick = onDecrement
                                )
                                .testTag("dec_product_${product.id}")
                        ) {
                            Icon(
                                imageVector = if (quantityInBasket == 1) {
                                    Icons.Default.DeleteOutline
                                } else {
                                    Icons.Default.Remove
                                },
                                contentDescription = lang.tr("إنقاص", "Decrease"),
                                tint = if (quantityInBasket == 1) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = WesternFormatters.formatInt(quantityInBasket),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .widthIn(min = 24.dp)
                                .padding(horizontal = 2.dp)
                                .testTag("qty_product_${product.id}")
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    enabled = !isMaxStockReached,
                                    role = Role.Button,
                                    onClick = onAdd
                                )
                                .testTag("inc_product_${product.id}")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isMaxStockReached) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = lang.tr("زيادة", "Increase"),
                                        tint = if (isMaxStockReached) {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.onPrimary
                                        },
                                        modifier = Modifier.size(17.dp)
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

@Composable
private fun BasketStickyBottomSheet(
    state: TraderUiState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onIncrement: (Product) -> Unit,
    onDecrement: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearBasket: () -> Unit,
    onProceedToPayment: () -> Unit,
    onOpenSaleOptions: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val hasItems = state.basket.isNotEmpty()
    val hasDiscount = state.calculatedDiscountIls > 0.005
    val linkedCustomer = state.selectedCustomer

    if (!hasItems) {
        // Quiet, flat 48dp status bar when basket is empty
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("toggle_basket_drawer_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (linkedCustomer != null) {
                            lang.tr(
                                "حساب: ${linkedCustomer.name(lang)} • السلة فارغة",
                                "Account: ${linkedCustomer.name(lang)} • Basket empty"
                            )
                        } else {
                            lang.tr(
                                "السلة فارغة",
                                "Basket empty"
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = WesternFormatters.formatIls(0.0),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("basket_subtotal_text")
                        )

                        Text(
                            text = lang.tr("متابعة الدفع", "Continue"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.testTag("review_invoice_button")
                        )
                    }
                }
                if (state.savedDrafts.isNotEmpty()) {
                    TextButton(
                        onClick = onOpenSaleOptions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("open_saved_drafts_button")
                    ) { Text(lang.tr("المسودات", "Saved drafts")) }
                }
            }
        }
        return
    }

    // Active Basket & Checkout Dock
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Expandable editable basket drawer
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.tr(
                                    "سلة البيع (${WesternFormatters.formatInt(state.basket.size)} أصناف • ${WesternFormatters.formatInt(state.basketItemCount)} وحدات)",
                                    "Active Basket (${WesternFormatters.formatInt(state.basket.size)} items • ${WesternFormatters.formatInt(state.basketItemCount)} units)"
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (linkedCustomer != null) {
                                Text(
                                    text = lang.tr(
                                        "الزبون: ${linkedCustomer.name(lang)}",
                                        "Customer: ${linkedCustomer.name(lang)}"
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        TextButton(
                            onClick = onClearBasket,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("clear_basket_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = lang.tr("إفراغ", "Clear"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.basket, key = { "basket_${it.product.id}" }) { item ->
                            val atMaxStock = item.product.isStockTracked && item.quantity >= item.product.stockCount
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = item.product.name(lang),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = buildString {
                                            append("${WesternFormatters.formatIls(item.product.priceIls)} × ${WesternFormatters.formatInt(item.quantity)}")
                                            append(" = ${WesternFormatters.formatIls(item.lineTotal)}")
                                            if (atMaxStock) {
                                                append(
                                                    if (lang == AppLanguage.AR) " • الحد الأقصى"
                                                    else " • Max stock"
                                                )
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = if (atMaxStock) financial.warning else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    IconButton(
                                        onClick = { onRemove(item.product.id) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("basket_remove_${item.product.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = lang.tr("حذف الصنف", "Remove item"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDecrement(item.product.id) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("basket_dec_${item.product.id}")
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Remove,
                                                    contentDescription = lang.tr("إنقاص", "Decrease"),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = WesternFormatters.formatInt(item.quantity),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(min = 20.dp)
                                    )

                                    IconButton(
                                        onClick = { onIncrement(item.product) },
                                        enabled = !atMaxStock,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("basket_inc_${item.product.id}")
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (atMaxStock) {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = lang.tr("زيادة", "Increase"),
                                                    tint = if (atMaxStock) {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    } else {
                                                        MaterialTheme.colorScheme.onPrimary
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (hasDiscount) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = lang.tr(
                                "المجموع الفرعي ${WesternFormatters.formatIls(state.basketSubtotalIls)} • خصم -${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                                "Subtotal ${WesternFormatters.formatIls(state.basketSubtotalIls)} • Discount -${WesternFormatters.formatIls(state.calculatedDiscountIls)}"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = financial.income,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Primary Checkout Dock Row (Minimum 48dp hit area)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            role = Role.Button,
                            onClick = onToggleExpand
                        )
                        .padding(end = 8.dp)
                        .testTag("toggle_basket_drawer_button")
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = WesternFormatters.formatInt(state.basketItemCount),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = when {
                                    hasDiscount -> lang.tr("الصافي بعد الخصم", "Net after discount")
                                    linkedCustomer != null -> linkedCustomer.name(lang)
                                    else -> lang.tr(
                                        "${WesternFormatters.formatInt(state.basket.size)} أصناف في السلة",
                                        "${WesternFormatters.formatInt(state.basket.size)} items in basket"
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasDiscount) financial.income else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = lang.tr("عرض أو إخفاء السلة", "Toggle basket details"),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = WesternFormatters.formatIls(state.basketNetTotalIls),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("basket_subtotal_text")
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        onClick = onOpenSaleOptions,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("open_sale_options_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("خيارات", "Options"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = onProceedToPayment,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("review_invoice_button")
                    ) {
                        Text(
                            text = lang.tr("متابعة الدفع", "Continue to Payment"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun WidePersistentCartPane(
    state: TraderUiState,
    onIncrement: (Product) -> Unit,
    onDecrement: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearBasket: () -> Unit,
    onProceedToPayment: () -> Unit,
    onOpenSaleOptions: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val hasItems = state.basket.isNotEmpty()
    val hasDiscount = state.calculatedDiscountIls > 0.005
    val linkedCustomer = state.selectedCustomer

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lang.tr(
                        "سلة البيع (${WesternFormatters.formatInt(state.basket.size)} أصناف • ${WesternFormatters.formatInt(state.basketItemCount)} وحدات)",
                        "Active Cart (${WesternFormatters.formatInt(state.basket.size)} items • ${WesternFormatters.formatInt(state.basketItemCount)} units)"
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (linkedCustomer != null) {
                    Text(
                        text = lang.tr(
                            "الزبون: ${linkedCustomer.name(lang)}",
                            "Customer: ${linkedCustomer.name(lang)}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (hasItems) {
                TextButton(
                    onClick = onClearBasket,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("clear_basket_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lang.tr("إفراغ", "Clear"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        if (!hasItems) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lang.tr(
                        "السلة فارغة • اختر أصنافاً من القائمة",
                        "Cart is empty • Select items from catalog"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.basket, key = { it.product.id }) { item ->
                    val atMaxStock = item.product.isStockTracked &&
                        item.product.stockCount > 0 &&
                        item.quantity >= item.product.stockCount

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.product.name(lang),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${WesternFormatters.formatIls(item.product.priceIls)} × ${WesternFormatters.formatInt(item.quantity)} = ${WesternFormatters.formatIls(item.lineTotal)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = { onRemove(item.product.id) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("basket_remove_${item.product.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = lang.tr("حذف", "Remove"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDecrement(item.product.id) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("basket_dec_${item.product.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Remove,
                                        contentDescription = lang.tr("تقليل", "Decrease"),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = WesternFormatters.formatInt(item.quantity),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                IconButton(
                                    onClick = { onIncrement(item.product) },
                                    enabled = !atMaxStock,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("basket_inc_${item.product.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = lang.tr("زيادة", "Increase"),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (hasDiscount) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = lang.tr("المجموع الفرعي", "Subtotal"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = WesternFormatters.formatIls(state.basketSubtotalIls),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = lang.tr("الخصم", "Discount"),
                        style = MaterialTheme.typography.bodySmall,
                        color = financial.income
                    )
                    Text(
                        text = "-${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = financial.income,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr("المبلغ المطلوب", "Total Due"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = WesternFormatters.formatIls(state.basketNetTotalIls),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("basket_subtotal_text")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOpenSaleOptions,
                shape = RoundedCornerShape(12.dp),
                enabled = hasItems,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("open_sale_options_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = lang.tr("خيارات", "Options"),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Button(
                onClick = onProceedToPayment,
                shape = RoundedCornerShape(12.dp),
                enabled = hasItems,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .weight(1.5f)
                    .height(48.dp)
                    .testTag("review_invoice_button")
            ) {
                Text(
                    text = lang.tr("متابعة الدفع", "Continue to Payment"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
