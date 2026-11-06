package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

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
    onProceedToInvoiceReview: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    var isBasketExpanded by remember { mutableStateOf(false) }
    val showBarcodeInRows = state.searchQuery.any { it.isDigit() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("new_sale_screen")
    ) {
        // Unified Search, Single Barcode Entry & Category Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_search_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = {
                            Text(
                                text = lang.tr(
                                    "ابحث بالاسم أو الباركود...",
                                    "Search name or barcode..."
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("clear_search_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = lang.tr("مسح البحث", "Clear search"),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                val candidate = state.filteredProducts.firstOrNull()
                                if (candidate != null && state.filteredProducts.size == 1) {
                                    onAddProduct(candidate)
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    // Dedicated 48dp Barcode Scanner button with clear spacing
                    FilledTonalIconButton(
                        onClick = onOpenScanner,
                        shape = RoundedCornerShape(10.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("sale_scan_barcode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = lang.tr("إدخال أو مسح باركود", "Enter or Scan Barcode"),
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Always-visible custom item entry
                    FilledTonalButton(
                        onClick = onOpenCustomItem,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("sale_custom_item_chip")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lang.tr("مادة حرة", "Custom"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Category Filter Strip
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(ProductCategory.entries, key = { it.name }) { category ->
                        val isSelected = state.selectedCategory == category
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectCategory(category) },
                            label = {
                                Text(
                                    text = category.label(lang),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.background,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("category_chip_${category.name}")
                        )
                    }
                }

                // Truthful Scan / Stock Limit Feedback Banner
                val bannerMsg = if (lang == AppLanguage.AR) state.lastScannedBannerAr else state.lastScannedBannerEn
                AnimatedVisibility(visible = bannerMsg != null) {
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
                                .testTag("sale_feedback_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
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

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )

        // Products List or Actionable Zero-Result State
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
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(state.filteredProducts, key = { it.id }) { product ->
                    val qtyInBasket = state.quantityInBasket(product.id)
                    ProductSaleCatalogRow(
                        product = product,
                        lang = lang,
                        quantityInBasket = qtyInBasket,
                        showBarcode = showBarcodeInRows,
                        onAdd = { onAddProduct(product) },
                        onDecrement = { onDecrementProduct(product.id) }
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        // Sticky Bottom Basket Bar & Expandable Basket Drawer
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
            onProceedToReview = onProceedToInvoiceReview
        )
    }
}

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
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("zero_result_search_state"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (searchQuery.isNotBlank()) {
                    lang.tr(
                        "لا توجد نتائج مطابقة لـ «${searchQuery.trim()}»",
                        "No products matching \"${searchQuery.trim()}\""
                    )
                } else {
                    lang.tr("لا توجد أصناف في هذا القسم", "No products in this category")
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedCategory != ProductCategory.ALL && allCategoriesMatchCount > 0) {
                    Button(
                        onClick = onShowAllCategories,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("search_all_categories_button")
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
                        modifier = Modifier.testTag("zero_result_clear_button")
                    ) {
                        Text(lang.tr("مسح البحث", "Clear Search"))
                    }
                }

                FilledTonalButton(
                    onClick = onOpenCustomItem,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("zero_result_custom_item_button")
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
 * High-speed POS catalog row:
 * - Eliminates heavy card borders and loud colored "Add" pill buttons.
 * - Gives Arabic/English product names generous horizontal room (`maxLines = 2`).
 * - Clearly shows unit/variant (`زجاجة 1 لتر`) and truthful stock state.
 * - Entire row is tappable to add (`product_card_${product.id}`), with an understated `+` button
 *   when unselected and a fast inline stepper (`- qty +`) with live line total when in basket.
 */
@Composable
private fun ProductSaleCatalogRow(
    product: Product,
    lang: AppLanguage,
    quantityInBasket: Int,
    showBarcode: Boolean,
    onAdd: () -> Unit,
    onDecrement: () -> Unit
) {
    val financial = TraderTheme.financial
    val isSelected = quantityInBasket > 0
    val isOutOfStock = product.isStockTracked && product.stockCount <= 0
    val isMaxStockReached = product.isStockTracked && product.stockCount > 0 && quantityInBasket >= product.stockCount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(enabled = !isOutOfStock) {
                onAdd()
            }
            .testTag("product_card_${product.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Subtle 4dp edge indicator when item is in the active basket
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left/Start: Full Product Name + Unit/Variant + Stock Status
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = product.name(lang),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutOfStock) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = product.unit(lang),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (showBarcode && product.barcode.isNotBlank()) {
                        Text(
                            text = "• ${product.barcode}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = when {
                            !product.isStockTracked -> lang.tr("• مادة حرة", "• Custom")
                            isOutOfStock -> lang.tr("• نفد المخزون", "• Out of stock")
                            isMaxStockReached -> lang.tr(
                                "• الحد الأقصى (${WesternFormatters.formatInt(product.stockCount)})",
                                "• Max (${WesternFormatters.formatInt(product.stockCount)})"
                            )
                            product.isLowStock -> lang.tr(
                                "• متبقي ${WesternFormatters.formatInt(product.stockCount)}",
                                "• ${WesternFormatters.formatInt(product.stockCount)} left"
                            )
                            else -> lang.tr(
                                "• متوفر ${WesternFormatters.formatInt(product.stockCount)}",
                                "• ${WesternFormatters.formatInt(product.stockCount)} in stock"
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isOutOfStock || isMaxStockReached || product.isLowStock) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = when {
                            isOutOfStock -> financial.debit
                            isMaxStockReached || product.isLowStock -> financial.warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        }
                    )
                }
            }

            // Right/End: Tabular Price + 48dp Action / Stepper
            if (!isSelected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = WesternFormatters.formatIls(product.priceIls),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isOutOfStock) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (isOutOfStock) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = financial.debitContainer
                        ) {
                            Text(
                                text = lang.tr("نفد", "Out"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = financial.debit,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    } else {
                        Surface(
                            onClick = onAdd,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("add_product_${product.id}")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = lang.tr("إضافة للسلة", "Add to basket"),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (quantityInBasket > 1) {
                            "${WesternFormatters.formatInt(quantityInBasket)} × ${WesternFormatters.formatNumber(product.priceIls)} = ${WesternFormatters.formatIls(product.priceIls * quantityInBasket)}"
                        } else {
                            WesternFormatters.formatIls(product.priceIls)
                        },
                        style = if (quantityInBasket > 1) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = onDecrement,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("dec_product_${product.id}"),
                            shape = RoundedCornerShape(10.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = lang.tr("إنقاص", "Decrease"),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = WesternFormatters.formatInt(quantityInBasket),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .testTag("qty_product_${product.id}")
                        )

                        FilledIconButton(
                            onClick = onAdd,
                            enabled = !isMaxStockReached,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("inc_product_${product.id}"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = lang.tr("زيادة", "Increase"),
                                modifier = Modifier.size(18.dp)
                            )
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
    onProceedToReview: () -> Unit
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val hasItems = state.basket.isNotEmpty()
    val hasDiscount = state.calculatedDiscountIls > 0.005
    val linkedCustomer = state.selectedCustomer

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Expandable editable basket drawer
            AnimatedVisibility(visible = isExpanded && hasItems) {
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
                        Column {
                            Text(
                                text = lang.tr(
                                    "سلة البيع (${WesternFormatters.formatInt(state.basket.size)} أصناف • ${WesternFormatters.formatInt(state.basketItemCount)} قطع)",
                                    "Active Basket (${WesternFormatters.formatInt(state.basket.size)} lines • ${WesternFormatters.formatInt(state.basketItemCount)} units)"
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (linkedCustomer != null) {
                                Text(
                                    text = lang.tr(
                                        "مرتبطة بحساب: ${linkedCustomer.name(lang)}",
                                        "Linked to: ${linkedCustomer.name(lang)}"
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
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.basket, key = { "basket_${it.product.id}" }) { item ->
                            val atMaxStock = item.product.isStockTracked && item.quantity >= item.product.stockCount
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.product.name(lang),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
                                    Text(
                                        text = WesternFormatters.formatIls(item.lineTotal),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = buildString {
                                            append("${WesternFormatters.formatIls(item.product.priceIls)} × ${WesternFormatters.formatInt(item.quantity)}")
                                            append(" (${item.product.unit(lang)})")
                                            if (atMaxStock) {
                                                append(
                                                    if (lang == AppLanguage.AR) " • الحد الأقصى"
                                                    else " • Max stock"
                                                )
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (atMaxStock) financial.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
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

                                        FilledTonalIconButton(
                                            onClick = { onDecrement(item.product.id) },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("basket_dec_${item.product.id}"),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Remove,
                                                contentDescription = lang.tr("إنقاص", "Decrease"),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Text(
                                            text = WesternFormatters.formatInt(item.quantity),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )

                                        FilledIconButton(
                                            onClick = { onIncrement(item.product) },
                                            enabled = !atMaxStock,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("basket_inc_${item.product.id}"),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = lang.tr("زيادة", "Increase"),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (hasDiscount) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
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
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Summary Bar + Checkout Button (consistent figures with Review screen)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { if (hasItems) onToggleExpand() },
                    color = Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_basket_drawer_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (hasItems) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = WesternFormatters.formatInt(state.basketItemCount),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (hasItems) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = when {
                                        hasDiscount -> lang.tr("الصافي بعد الخصم", "Net after discount")
                                        linkedCustomer != null -> linkedCustomer.name(lang)
                                        else -> lang.tr("مجموع السلة", "Basket Total")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (hasDiscount) financial.income else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (hasItems) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("basket_subtotal_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onProceedToReview,
                    enabled = hasItems,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("review_invoice_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingCartCheckout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = lang.tr("مراجعة الفاتورة", "Review Invoice"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
