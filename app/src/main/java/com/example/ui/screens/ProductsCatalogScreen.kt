package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.ProductCategory
import com.example.model.StockMovement
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

private enum class CatalogStockFilter {
    ALL,
    LOW_OR_OUT,
    OUT_OF_STOCK,
    NON_STOCK
}

@Composable
fun ProductsCatalogScreen(
    state: TraderUiState,
    onSearchChange: (String) -> Unit,
    onCategorySelect: (ProductCategory) -> Unit,
    onProductClick: (Product) -> Unit,
    onAddProductToSale: (Product) -> Unit = {},
    onQuickRestock: (Product) -> Unit = {}
) {
    val lang = state.language
    var stockFilter by remember { mutableStateOf(CatalogStockFilter.ALL) }

    val lowOrOutCount = remember(state.products) {
        state.products.count { it.isStockTracked && it.isLowStock }
    }
    val outOfStockCount = remember(state.products) {
        state.products.count { it.isStockTracked && it.stockCount <= 0 }
    }
    val nonStockCount = remember(state.products) {
        state.products.count { !it.isStockTracked }
    }

    val displayedProducts = remember(state.filteredProducts, stockFilter) {
        state.filteredProducts.filter { product ->
            when (stockFilter) {
                CatalogStockFilter.ALL -> true
                CatalogStockFilter.LOW_OR_OUT -> product.isStockTracked && product.isLowStock
                CatalogStockFilter.OUT_OF_STOCK -> product.isStockTracked && product.stockCount <= 0
                CatalogStockFilter.NON_STOCK -> !product.isStockTracked
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("products_catalog_screen")
    ) {
        // Sticky Search, Category Browsing (all categories) & Stock Status Filter Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search by product name, unit, or barcode
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("catalog_search_input"),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = lang.tr(
                                "ابحث باسم الصنف، العبوة أو الباركود...",
                                "Search by product name, unit, or barcode..."
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
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = lang.tr("مسح البحث", "Clear search")
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )

                // All Product Categories (Horizontally scrollable so no category is cut off)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ProductCategory.entries, key = { it.name }) { cat ->
                        FilterChip(
                            selected = state.selectedCategory == cat,
                            onClick = { onCategorySelect(cat) },
                            label = { Text(cat.label(lang)) },
                            modifier = Modifier.testTag("catalog_category_${cat.name}")
                        )
                    }
                }

                // Inventory Availability Filter Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = stockFilter == CatalogStockFilter.ALL,
                            onClick = { stockFilter = CatalogStockFilter.ALL },
                            label = {
                                Text(
                                    lang.tr(
                                        "كل الأصناف (${WesternFormatters.formatInt(state.products.size)})",
                                        "All (${WesternFormatters.formatInt(state.products.size)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("catalog_stock_filter_ALL")
                        )
                    }
                    item {
                        FilterChip(
                            selected = stockFilter == CatalogStockFilter.LOW_OR_OUT,
                            onClick = { stockFilter = CatalogStockFilter.LOW_OR_OUT },
                            label = {
                                Text(
                                    lang.tr(
                                        "بحاجة لتزويد (${WesternFormatters.formatInt(lowOrOutCount)})",
                                        "Needs Restock (${WesternFormatters.formatInt(lowOrOutCount)})"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("catalog_stock_filter_LOW")
                        )
                    }
                    if (outOfStockCount > 0) {
                        item {
                            FilterChip(
                                selected = stockFilter == CatalogStockFilter.OUT_OF_STOCK,
                                onClick = { stockFilter = CatalogStockFilter.OUT_OF_STOCK },
                                label = {
                                    Text(
                                        lang.tr(
                                            "نفد المخزون (${WesternFormatters.formatInt(outOfStockCount)})",
                                            "Out of Stock (${WesternFormatters.formatInt(outOfStockCount)})"
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("catalog_stock_filter_OUT")
                            )
                        }
                    }
                    if (nonStockCount > 0) {
                        item {
                            FilterChip(
                                selected = stockFilter == CatalogStockFilter.NON_STOCK,
                                onClick = { stockFilter = CatalogStockFilter.NON_STOCK },
                                label = {
                                    Text(
                                        lang.tr(
                                            "بدون جرد (${WesternFormatters.formatInt(nonStockCount)})",
                                            "Non-Stock (${WesternFormatters.formatInt(nonStockCount)})"
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("catalog_stock_filter_NON_STOCK")
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        // Scannable Product Directory List
        if (displayedProducts.isEmpty()) {
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
                    Text(
                        text = lang.tr(
                            "لا توجد أصناف مطابقة للبحث أو التصنيف المحدد",
                            "No products match the current search or filter"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.searchQuery.isNotBlank() ||
                        state.selectedCategory != ProductCategory.ALL ||
                        stockFilter != CatalogStockFilter.ALL
                    ) {
                        TextButton(
                            onClick = {
                                onSearchChange("")
                                onCategorySelect(ProductCategory.ALL)
                                stockFilter = CatalogStockFilter.ALL
                            }
                        ) {
                            Text(lang.tr("إعادة ضبط الفلاتر وعرض الكل", "Reset Filters & Show All"))
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
                items(displayedProducts, key = { it.id }) { product ->
                    CatalogDirectoryRow(
                        product = product,
                        lang = lang,
                        onClick = { onProductClick(product) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogDirectoryRow(
    product: Product,
    lang: AppLanguage,
    onClick: () -> Unit
) {
    val isOutOfStock = product.isStockTracked && product.stockCount <= 0
    val isLowStock = product.isStockTracked && product.stockCount > 0 && product.isLowStock

    val stockBadgeText = when {
        !product.isStockTracked -> lang.tr("بدون جرد", "Non-stock")
        isOutOfStock -> lang.tr("نفد (0)", "Out (0)")
        isLowStock -> lang.tr(
            "منخفض: ${WesternFormatters.formatInt(product.stockCount)}",
            "Low: ${WesternFormatters.formatInt(product.stockCount)}"
        )
        else -> lang.tr(
            "متوفر: ${WesternFormatters.formatInt(product.stockCount)}",
            "In stock: ${WesternFormatters.formatInt(product.stockCount)}"
        )
    }

    val stockBadgeBg = when {
        !product.isStockTracked -> MaterialTheme.colorScheme.surfaceVariant
        isOutOfStock -> TraderTheme.financial.debitContainer
        isLowStock -> TraderTheme.financial.warningContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }

    val stockBadgeTextColor = when {
        !product.isStockTracked -> MaterialTheme.colorScheme.onSurfaceVariant
        isOutOfStock -> TraderTheme.financial.onDebitContainer
        isLowStock -> TraderTheme.financial.onWarningContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("catalog_item_${product.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left/Start: Product Name + Unit + Category + Barcode (no cost or cart buttons here)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = product.name(lang),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = product.unit(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = product.category.label(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (product.barcode.isNotBlank()) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = product.barcode,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right/End: Selling Price + Restrained Availability Pill
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = WesternFormatters.formatIls(product.priceIls),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = stockBadgeBg
                ) {
                    Text(
                        text = stockBadgeText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = stockBadgeTextColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProductDetailScreen(
    state: TraderUiState,
    onBack: () -> Unit,
    onUpdatePrice: (productId: String, newPrice: Double) -> Unit,
    onAdjustStock: (productId: String, delta: Int, reason: String) -> Unit,
    onAddToBasket: (Product) -> Unit = {},
    onDismissFeedback: (() -> Unit)? = null
) {
    val lang = state.language
    val product = state.selectedProductForDetail ?: return
    val canManageInventory = state.currentSession.canManageInventory()
    val movements = remember(state.stockMovements, product.id) {
        state.stockMovements.filter { it.productId == product.id }
    }

    var isEditPriceOpen by remember(product.id) { mutableStateOf(false) }
    var isAdjustStockOpen by remember(product.id) { mutableStateOf(false) }

    val feedbackMessage = lang.tr(
        state.lastInventoryActionMessageAr ?: "",
        state.lastInventoryActionMessageEn ?: ""
    ).takeIf { it.isNotBlank() }

    val unitMarginIls = remember(product.priceIls, product.costIls) {
        BigDecimal(product.priceIls.toString())
            .subtract(BigDecimal(product.costIls.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("product_detail_screen")
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Feedback Banner after Price or Stock Update
            if (feedbackMessage != null) {
                item {
                    val isError = state.isInventoryActionError
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            TraderTheme.financial.incomeContainer
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isError) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            } else {
                                TraderTheme.financial.income.copy(alpha = 0.4f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("product_detail_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        TraderTheme.financial.income
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = feedbackMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        TraderTheme.financial.income
                                    }
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

            // 1. Product Identity, Pricing & Stock Status Card
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
                        // Category & Stock-Tracking Mode Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = product.category.label(lang),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (product.isStockTracked) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            ) {
                                Text(
                                    text = if (product.isStockTracked) {
                                        lang.tr("صنف مخزني متتبع", "Stock-Tracked Good")
                                    } else {
                                        lang.tr("مادة / خدمة بدون جرد", "Non-Stock Item")
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (product.isStockTracked) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // Full Product Name
                        Text(
                            text = product.name(lang),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Unit & Barcode Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${lang.tr("الوحدة / العبوة:", "Unit / Pack:")} ${product.unit(lang)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = product.barcode,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Pricing & Availability Figures (Cost & Margin visible ONLY to Owner/Manager)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            // Selling Price (Always visible)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("سعر البيع", "Selling Price"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = WesternFormatters.formatIls(product.priceIls),
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("product_detail_price_value")
                                )
                            }

                            // Cost Price & Margin (Strictly Owner / Manager only — hidden from Cashier)
                            if (canManageInventory) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("product_detail_cost_section")
                                ) {
                                    Text(
                                        text = lang.tr("تكلفة الشراء", "Cost Price"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    if (product.isStockTracked && product.costIls > 0.0) {
                                        Text(
                                            text = WesternFormatters.formatIls(product.costIls),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${lang.tr("الهامش:", "Margin:")} ${WesternFormatters.formatIls(unitMarginIls)}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = if (unitMarginIls >= 0) {
                                                TraderTheme.financial.income
                                            } else {
                                                TraderTheme.financial.debit
                                            }
                                        )
                                    } else {
                                        Text(
                                            text = lang.tr("غير مقيد بتكلفة", "No tracked cost"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Stock Availability
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = lang.tr("حالة المخزون", "Availability"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                if (product.isStockTracked) {
                                    val isOut = product.stockCount <= 0
                                    val isLow = product.stockCount > 0 && product.isLowStock
                                    Text(
                                        text = "${WesternFormatters.formatInt(product.stockCount)} ${product.unit(lang)}",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = when {
                                            isOut -> TraderTheme.financial.debit
                                            isLow -> TraderTheme.financial.warning
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.testTag("product_detail_stock_value")
                                    )
                                    Text(
                                        text = when {
                                            isOut -> lang.tr("نفد من المستودع (حد التنبيه: ${product.minStockThreshold})", "Out of stock (Min: ${product.minStockThreshold})")
                                            isLow -> lang.tr("مخزون منخفض (الحد الأدنى: ${product.minStockThreshold})", "Low stock (Min: ${product.minStockThreshold})")
                                            else -> lang.tr("متوفر (حد التنبيه: ${product.minStockThreshold})", "Available (Min: ${product.minStockThreshold})")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            isOut -> TraderTheme.financial.debit
                                            isLow -> TraderTheme.financial.warning
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                } else {
                                    Text(
                                        text = lang.tr("بدون جرد كميات", "Non-stock item"),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("product_detail_stock_value")
                                    )
                                    Text(
                                        text = lang.tr("يباع مباشرة", "Direct sale line"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Non-Stock Item Clarification Banner
                        if (!product.isStockTracked) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("non_stock_item_explanation")
                            ) {
                                Text(
                                    text = lang.tr(
                                        "هذا البند مخصص للخدمات أو المواد السريعة غير المقيدة بجرد مستودعي — يباع في نقطة البيع دون خصم رصيد مخزني.",
                                        "This line is a non-stock service or quick item — it can be sold at POS without deducting physical inventory."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        // Authorized Management Controls (Strictly hidden from Cashiers!)
                        if (canManageInventory) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { isEditPriceOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("edit_product_price_button")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(lang.tr("تعديل السعر", "Edit Price"))
                                }

                                if (product.isStockTracked) {
                                    Button(
                                        onClick = { isAdjustStockOpen = true },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .weight(1.15f)
                                            .height(48.dp)
                                            .testTag("adjust_product_stock_button")
                                    ) {
                                        Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(lang.tr("تسوية وتعديل المخزون", "Adjust Stock"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Stock Movement History Section (For stock-tracked products)
            if (product.isStockTracked) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("سجل حركات المخزون", "Stock Movement History"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lang.tr(
                                "${WesternFormatters.formatInt(movements.size)} حركة",
                                "${WesternFormatters.formatInt(movements.size)} entries"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (movements.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lang.tr(
                                    "لا توجد حركات مخزنية مسجلة لهذا الصنف بعد.",
                                    "No stock movements recorded for this item yet."
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(movements, key = { it.id }) { sm ->
                        StockMovementRow(sm = sm, lang = lang)
                    }
                }
            }
        }
    }

    // Authorized Price Edit Dialog
    if (isEditPriceOpen && canManageInventory) {
        var newPriceText by remember(product.id, product.priceIls) {
            mutableStateOf(WesternFormatters.formatNumber(product.priceIls))
        }
        val parsedPrice = newPriceText.toDoubleOrNull() ?: 0.0
        val isBelowCost = product.isStockTracked && product.costIls > 0.0 && parsedPrice > 0.0 && parsedPrice < product.costIls

        AlertDialog(
            onDismissRequest = { isEditPriceOpen = false },
            title = {
                Text(
                    text = lang.tr("تعديل سعر البيع", "Edit Selling Price"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = product.name(lang),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (product.isStockTracked && product.costIls > 0.0) {
                        Text(
                            text = "${lang.tr("تكلفة الشراء المسجلة:", "Recorded Cost:")} ${WesternFormatters.formatIls(product.costIls)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = newPriceText,
                        onValueChange = { newPriceText = WesternFormatters.sanitizeWesternDigits(it) },
                        label = { Text(lang.tr("سعر البيع الجديد (₪)", "New Selling Price (₪)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("price_input_field")
                    )
                    if (isBelowCost) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = TraderTheme.financial.warningContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lang.tr(
                                    "تنبيه: السعر المدخل أقل من تكلفة الشراء (${WesternFormatters.formatIls(product.costIls)})",
                                    "Warning: Entered price is below cost (${WesternFormatters.formatIls(product.costIls)})"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = TraderTheme.financial.warning,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (parsedPrice > 0.0) {
                            onUpdatePrice(product.id, parsedPrice)
                            isEditPriceOpen = false
                        }
                    },
                    enabled = parsedPrice > 0.0,
                    modifier = Modifier.testTag("confirm_price_update_button")
                ) {
                    Text(lang.tr("حفظ السعر", "Save Price"))
                }
            },
            dismissButton = {
                TextButton(onClick = { isEditPriceOpen = false }) {
                    Text(lang.tr("إلغاء", "Cancel"))
                }
            }
        )
    }

    // Authorized Stock Adjustment Dialog with Direction Toggle, Reason Presets & Safe Outcome Preview
    if (isAdjustStockOpen && canManageInventory && product.isStockTracked) {
        var isAddingStock by remember { mutableStateOf(true) }
        var qtyInputText by remember { mutableStateOf("10") }
        var stockReasonText by remember {
            mutableStateOf(lang.tr("استلام بضاعة جديدة للمستودع", "New stock received"))
        }

        val rawQty = abs(qtyInputText.toIntOrNull() ?: 0)
        val signedDelta = if (isAddingStock) rawQty else -rawQty
        val resultingStock = product.stockCount + signedDelta
        val isResultValid = rawQty > 0 && resultingStock >= 0 && stockReasonText.trim().isNotEmpty()

        val reasonPresets = if (isAddingStock) {
            listOf(
                lang.tr("استلام بضاعة جديدة للمستودع", "New stock received"),
                lang.tr("تصحيح جرد فعلي (زيادة)", "Audit count correction (add)"),
                lang.tr("مرتجع سليم للرفوف", "Returned intact to shelf")
            )
        } else {
            listOf(
                lang.tr("تالف أو منتهي الصلاحية", "Damaged or expired"),
                lang.tr("تصحيح جرد فعلي (عجز)", "Audit count correction (shortage)"),
                lang.tr("استهلاك ضيافة ومحل", "Store internal use")
            )
        }

        AlertDialog(
            onDismissRequest = { isAdjustStockOpen = false },
            title = {
                Text(
                    text = lang.tr("تسوية وتعديل رصيد المخزون", "Inventory Stock Adjustment"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${product.name(lang)} • ${lang.tr("المخزون الحالي:", "Current Stock:")} ${WesternFormatters.formatInt(product.stockCount)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Add vs Deduct Direction Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = isAddingStock,
                            onClick = {
                                isAddingStock = true
                                stockReasonText = lang.tr("استلام بضاعة جديدة للمستودع", "New stock received")
                            },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(lang.tr("إضافة وتوريد (+)", "Add Stock (+)")) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = !isAddingStock,
                            onClick = {
                                isAddingStock = false
                                stockReasonText = lang.tr("تالف أو منتهي الصلاحية", "Damaged or expired")
                            },
                            leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(lang.tr("خصم / تالف (−)", "Deduct (−)")) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = qtyInputText,
                        onValueChange = {
                            qtyInputText = WesternFormatters.sanitizeWesternDigits(it).filter { ch -> ch.isDigit() }
                        },
                        label = {
                            Text(
                                if (isAddingStock) lang.tr("الكمية المضافة", "Quantity to Add")
                                else lang.tr("الكمية المخصومة", "Quantity to Deduct")
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stock_delta_input")
                    )

                    // Reason Presets
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(reasonPresets) { preset ->
                            FilterChip(
                                selected = stockReasonText == preset,
                                onClick = { stockReasonText = preset },
                                label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = stockReasonText,
                        onValueChange = { stockReasonText = it },
                        label = { Text(lang.tr("سبب التعديل (مطلوب للسجل)", "Reason for Adjustment")) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stock_reason_input")
                    )

                    // Safe Outcome Preview Banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (resultingStock < 0) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (resultingStock < 0) {
                                lang.tr(
                                    "غير مسموح: لا يمكن خصم ${WesternFormatters.formatInt(rawQty)} والمخزون المتوفر ${WesternFormatters.formatInt(product.stockCount)} فقط.",
                                    "Invalid: Cannot deduct ${WesternFormatters.formatInt(rawQty)} when current stock is ${WesternFormatters.formatInt(product.stockCount)}."
                                )
                            } else {
                                lang.tr(
                                    "الرصيد الناتج بعد التعديل: ${WesternFormatters.formatInt(resultingStock)} ${product.unit(lang)}",
                                    "Resulting stock after adjustment: ${WesternFormatters.formatInt(resultingStock)} ${product.unit(lang)}"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (resultingStock < 0) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isResultValid) {
                            onAdjustStock(product.id, signedDelta, stockReasonText.trim())
                            isAdjustStockOpen = false
                        }
                    },
                    enabled = isResultValid,
                    modifier = Modifier.testTag("confirm_stock_adjust_button")
                ) {
                    Text(lang.tr("تأكيد وحفظ الحركة", "Confirm Adjustment"))
                }
            },
            dismissButton = {
                TextButton(onClick = { isAdjustStockOpen = false }) {
                    Text(lang.tr("إلغاء", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun StockMovementRow(
    sm: StockMovement,
    lang: AppLanguage
) {
    val isPositive = sm.delta > 0
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stock_movement_${sm.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = sm.reason(lang),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = sm.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (isPositive) "+" else ""}${WesternFormatters.formatInt(sm.delta)}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isPositive) TraderTheme.financial.income else TraderTheme.financial.debit
                )
                Text(
                    text = "${lang.tr("الرصيد:", "Stock:")} ${WesternFormatters.formatInt(sm.resultingStock)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
