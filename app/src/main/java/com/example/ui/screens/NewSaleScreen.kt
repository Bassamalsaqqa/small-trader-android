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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.components.StitchCard
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

/**
 * Rebuilt Native New Sale (POS) Catalog Screen for Small Trader (التاجر الصغير).
 *
 * Implements Google Stitch Canonical Direction (ST-06, ST-07, ST-53 / DESIGN_LOCK.md):
 * 1. Unified Search & Scanner: 48dp tactile search field with camera scanner trigger,
 *    clearing shortcut, and horizontal scrolling category pills.
 * 2. Dense Product Cards: Standard 12dp Stitch cards with 8dp spacing and 16dp gutters.
 *    Long Arabic titles wrap cleanly across 2 lines without clipping on narrow 360dp screens.
 * 3. Exception-Based Stock: Stock badges appear strictly by exception (out-of-stock, low-stock),
 *    eliminating visual noise for healthy items.
 * 4. Zero Add -> Tactile Stepper: Unselected products feature a clear 48dp (+) add button;
 *    selected products reveal an inline 48dp accessible stepper (- qty +) with clear math.
 * 5. Bounded Sticky Review Dock: Derived units & net total in a sticky bottom dock;
 *    expandable drawer is bounded in height and scrolls rather than covering the catalog.
 * 6. Full RTL & Theme Adaptability: Adheres to semantic color tokens for flawless Light and Dark theme,
 *    Western Arabic digits, and RTL directional isolation.
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
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
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
                                .testTag("product_catalog_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            }
                        }
                    }
                }

                VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
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
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
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
                            .testTag("product_catalog_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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

/**
 * ST-06 / ST-07 Unified POS Instrument Header:
 * - Search bar with Western digit normalization and clear query shortcut.
 * - Embedded 48dp camera barcode scanner trigger.
 * - Horizontal scrolling category pills with active pale blue highlights.
 * - Pinned '+ Custom' item button.
 */
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
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Hero Title & Context Row (Strictly Card-Contained, responsive to 2.0x font scaling)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PointOfSale,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = lang.tr("نقطة البيع (بيع جديد)", "New Sale (POS)"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${state.businessInfo.name(lang)} • ${lang.tr("الكاشير:", "Cashier:")} ${state.currentSession.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StitchBadge(
                    text = lang.tr(
                        "${WesternFormatters.formatInt(state.filteredProducts.size)} صنفاً",
                        "${WesternFormatters.formatInt(state.filteredProducts.size)} items"
                    ),
                    tone = StitchBadgeTone.PRIMARY
                )
            }

            // Unified Search & Barcode Capsule (48dp height, 10dp radius per DESIGN_LOCK)
            val searchContainerColor = if (isSearchFocused || hasActiveQuery) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
            val searchBorderColor = when {
                isSearchFocused -> MaterialTheme.colorScheme.primary
                hasActiveQuery -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(searchContainerColor)
                    .border(
                        width = if (isSearchFocused) 1.5.dp else 1.dp,
                        color = searchBorderColor,
                        shape = RoundedCornerShape(10.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = if (isSearchFocused || hasActiveQuery) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
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
                            .semantics {
                                contentDescription = lang.tr("البحث عن الأصناف بالاسم أو الباركود", "Search products by name or barcode")
                            }
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

                // 48dp tactile camera scanner button
                IconButton(
                    onClick = onOpenScanner,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("sale_scan_barcode_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = lang.tr("مسح الباركود بالكاميرا", "Scan barcode"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Category Filter Pills & Custom Item Trigger (ST-06 horizontal scroll)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pinned Quick Custom Item Chip
                item {
                    Surface(
                        onClick = onOpenCustomItem,
                        shape = RoundedCornerShape(9999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("sale_custom_item_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = lang.tr("صنف حر", "Custom"),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Category Filter Pills
                items(ProductCategory.entries.toTypedArray(), key = { it.name }) { category ->
                    val isSelected = state.selectedCategory == category
                    val pillBg = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                    val pillBorder = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                    val pillTextColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        onClick = { onSelectCategory(category) },
                        shape = RoundedCornerShape(9999.dp),
                        color = pillBg,
                        border = BorderStroke(1.dp, pillBorder),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("category_chip_${category.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (category == ProductCategory.ALL && isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Storefront,
                                    contentDescription = null,
                                    tint = pillTextColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = category.label(lang),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = pillTextColor
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
                            .padding(vertical = 2.dp)
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
                                style = MaterialTheme.typography.labelMedium,
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

/**
 * Zero-Result State with Clean Cross-Category Recovery (ST-06).
 */
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
                        "No products matching «${searchQuery.trim()}»"
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
 * Compact Dense Retail POS Product Row (ST-06 / ST-07 / ST-53):
 * - Standard StitchCard (12dp radius, 1dp subtle border) matching Google Stitch design.
 * - Arabic title wraps cleanly over 2 lines without clipping on narrow 360dp screens.
 * - Unselected: Tabular price + quiet 48dp (+) add button. Stock shown strictly by exception.
 * - Selected: Active pale tint + inline line calculation (e.g. 38.00 ₪ × 2 = 76.00 ₪) +
 *   tactile 48dp stepper capsule (- qty +).
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

    val cardBorder = if (isSelected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    val cardBg = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.22f else 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clickable(enabled = !isOutOfStock) { onAdd() }
            .testTag("product_card_${product.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Product Information Column (RTL Start)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = product.name(lang),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutOfStock) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                if (isSelected) {
                    // Active selection: Show line calculation & stock limits
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
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )

                        if (isMaxStockReached) {
                            StitchBadge(
                                text = lang.tr(
                                    "الحد الأقصى (${WesternFormatters.formatInt(product.stockCount)})",
                                    "Max (${WesternFormatters.formatInt(product.stockCount)})"
                                ),
                                tone = StitchBadgeTone.WARNING
                            )
                        }
                    }
                } else {
                    // Unselected: Packaging unit + exception-only stock status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = product.unit(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (isOutOfStock) 0.6f else 0.85f
                            )
                        )

                        if (showBarcode && product.barcode.isNotBlank()) {
                            Text(
                                text = "• ${product.barcode}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Stock Exceptions ONLY: Clean semantic badges for low/out of stock
                        when {
                            isOutOfStock -> {
                                StitchBadge(
                                    text = lang.tr("نفد", "Out"),
                                    tone = StitchBadgeTone.DANGER
                                )
                            }
                            product.isStockTracked && product.stockCount in 1..4 -> {
                                StitchBadge(
                                    text = lang.tr(
                                        "متبقي ${WesternFormatters.formatInt(product.stockCount)}",
                                        "Only ${WesternFormatters.formatInt(product.stockCount)} left"
                                    ),
                                    tone = StitchBadgeTone.WARNING
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

            // Action / Stepper Column (RTL End)
            if (!isSelected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = WesternFormatters.formatIls(product.priceIls),
                        style = MaterialTheme.typography.titleMedium.copy(
                            textDirection = TextDirection.Ltr
                        ),
                        fontWeight = FontWeight.Bold,
                        color = if (isOutOfStock) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false
                    )

                    if (isOutOfStock) {
                        StitchBadge(
                            text = lang.tr("نفدت الكمية", "Out of Stock"),
                            tone = StitchBadgeTone.DANGER
                        )
                    } else {
                        // 48dp tactile (+) Add trigger
                        Surface(
                            onClick = onAdd,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("add_product_${product.id}")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = lang.tr("إضافة للسلة", "Add to basket"),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // Tactile Stepper Capsule (- qty +) with min 48dp touch targets
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDecrement,
                            modifier = Modifier
                                .size(48.dp)
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
                                    MaterialTheme.colorScheme.error
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
                                .widthIn(min = 26.dp)
                                .padding(horizontal = 2.dp)
                                .testTag("qty_product_${product.id}"),
                            maxLines = 1,
                            softWrap = false
                        )

                        IconButton(
                            onClick = onAdd,
                            enabled = !isMaxStockReached,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("inc_product_${product.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = lang.tr("زيادة", "Increase"),
                                tint = if (isMaxStockReached) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sticky Bottom Basket Dock & Bounded Expandable Drawer (ST-07):
 * - Quiet 48dp bar when empty with draft access.
 * - Elevated floating dock when active with item count and subtotal.
 * - Expandable drawer bounded to 240dp to prevent full catalog occlusion.
 * - Primary button 'مراجعة السلة' invokes onProceedToPayment (navigates to Invoice Review).
 */
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
        // Quiet, flat 48dp status bar when basket is empty (ST-06)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                            lang.tr("السلة فارغة", "Basket empty")
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
                            style = MaterialTheme.typography.labelLarge.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("basket_subtotal_text")
                        )

                        Text(
                            text = lang.tr("مراجعة السلة", "Review Basket"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.testTag("review_invoice_button")
                        )
                    }
                }

                // Point-of-sale draft recovery button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onOpenSaleOptions,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("open_saved_drafts_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.savedDrafts.isNotEmpty()) {
                                lang.tr(
                                    "المسودات (${WesternFormatters.formatInt(state.savedDrafts.size)})",
                                    "Saved drafts (${WesternFormatters.formatInt(state.savedDrafts.size)})"
                                )
                            } else {
                                lang.tr("المسودات والخيارات", "Drafts & Options")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        return
    }

    // Active Basket & Checkout Dock (ST-07)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Expandable Basket Item Drawer (Bounded to 240dp max)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr(
                                "محتويات السلة (${WesternFormatters.formatInt(state.basket.size)} أصناف)",
                                "Basket Items (${WesternFormatters.formatInt(state.basket.size)} lines)"
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        TextButton(
                            onClick = onClearBasket,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
                                text = lang.tr("إفراغ السلة", "Clear"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = minOf(240.dp, (LocalConfiguration.current.screenHeightDp * 0.25f).dp)),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                        maxLines = 2,
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
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            textDirection = TextDirection.Ltr
                                        ),
                                        fontWeight = FontWeight.Medium,
                                        color = if (atMaxStock) financial.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
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
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDecrement(item.product.id) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("basket_dec_${item.product.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Remove,
                                            contentDescription = lang.tr("إنقاص", "Decrease"),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Text(
                                        text = WesternFormatters.formatInt(item.quantity),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.widthIn(min = 20.dp),
                                        maxLines = 1,
                                        softWrap = false
                                    )

                                    IconButton(
                                        onClick = { onIncrement(item.product) },
                                        enabled = !atMaxStock,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("basket_inc_${item.product.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = lang.tr("زيادة", "Increase"),
                                            tint = if (atMaxStock) {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (hasDiscount) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = lang.tr(
                                "المجموع الفرعي ${WesternFormatters.formatIls(state.basketSubtotalIls)} • خصم -${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                                "Subtotal ${WesternFormatters.formatIls(state.basketSubtotalIls)} • Discount -${WesternFormatters.formatIls(state.calculatedDiscountIls)}"
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            color = financial.income,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            // Primary Checkout Dock Container (ST-07)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fontScale = LocalDensity.current.fontScale
                val isCompactOrLargeFont = maxWidth < 380.dp || fontScale > 1.15f

                if (isCompactOrLargeFont) {
                    // Stacked 2-tier layout: Summary + Quiet Options on top, Full-width Review below
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Basket summary drawer trigger
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
                                    .padding(end = 4.dp)
                                    .testTag("toggle_basket_drawer_button")
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = WesternFormatters.formatInt(state.basketItemCount),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                hasDiscount -> lang.tr("الصافي بعد الخصم", "Net after discount")
                                                linkedCustomer != null -> linkedCustomer.name(lang)
                                                else -> lang.tr(
                                                    "${WesternFormatters.formatInt(state.basketItemCount)} قطع في السلة",
                                                    "${WesternFormatters.formatInt(state.basketItemCount)} units in basket"
                                                )
                                            },
                                            style = MaterialTheme.typography.labelSmall,
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
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            textDirection = TextDirection.Ltr
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("basket_subtotal_text")
                                    )
                                }
                            }

                            // Secondary Quiet Options button
                            TextButton(
                                onClick = onOpenSaleOptions,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // Full-width Primary Review CTA (min 48dp, content never clipped)
                        Button(
                            onClick = onProceedToPayment,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("review_invoice_button")
                        ) {
                            Text(
                                text = lang.tr("مراجعة السلة", "Review Basket"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    // Single-row layout for standard viewports
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
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = when {
                                            hasDiscount -> lang.tr("الصافي بعد الخصم", "Net after discount")
                                            linkedCustomer != null -> linkedCustomer.name(lang)
                                            else -> lang.tr(
                                                "${WesternFormatters.formatInt(state.basketItemCount)} قطع في السلة",
                                                "${WesternFormatters.formatInt(state.basketItemCount)} units in basket"
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
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        textDirection = TextDirection.Ltr
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Button(
                                onClick = onProceedToPayment,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("review_invoice_button")
                            ) {
                                Text(
                                    text = lang.tr("مراجعة السلة", "Review Basket"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
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
            }        }
    }
}

/**
 * Adaptive Wide Screen POS Cart Pane (840dp+):
 * - Persistent right-hand cart panel for tablet viewports.
 * - Standard StitchCard styling with 12dp radii and clear status badges.
 */
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
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    style = MaterialTheme.typography.titleLarge.copy(
                        textDirection = TextDirection.Ltr
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
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
                shape = RoundedCornerShape(10.dp),
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
                shape = RoundedCornerShape(10.dp),
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
                    text = lang.tr("مراجعة السلة", "Review Basket"),
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
