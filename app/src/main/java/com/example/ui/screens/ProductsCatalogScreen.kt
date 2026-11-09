package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
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
    onQuickRestock: (Product) -> Unit = {},
    launchCreate: Boolean = false,
    onLaunchConsumed: () -> Unit = {},
    onOpenPurchases: () -> Unit = {},
    launchLowStock: Boolean = false,
    onLowStockConsumed: () -> Unit = {},
    onSaveProduct: ((productId: String?, nameAr: String, category: ProductCategory, unitAr: String, barcode: String, priceIls: Double, costIls: Double, minStockThreshold: Int, initialStock: Int, warehouseAr: String, lotBatchCode: String, expiryDate: String?, isWeightedDisplayOnly: Boolean) -> Unit)? = null
) {
    val createDraft = rememberSaveableStateHolder()
    val lang = state.language
    val canManageInventory = state.currentSession.canManageInventory()
    var stockFilter by rememberSaveable { mutableStateOf(CatalogStockFilter.ALL) }
    var isCreateProductOpen by rememberSaveable { mutableStateOf(false) }
    var isStockTransferPreviewOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(canManageInventory) {
        if (!canManageInventory) {
            isCreateProductOpen = false
            isStockTransferPreviewOpen = false
        }
    }
    LaunchedEffect(launchCreate) {
        if (launchCreate && canManageInventory) { isCreateProductOpen = true; onLaunchConsumed() }
    }
    LaunchedEffect(launchLowStock) {
        if (launchLowStock) {
            stockFilter = CatalogStockFilter.LOW_OR_OUT
            onLowStockConsumed()
        }
    }
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
        // Contained Hero & Filter Surface Card (R2-INVENTORY Stitch Specification)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Title, Live Pulse & Quick Add Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = lang.tr("إدارة المخزون والمنتجات", "Inventory & Products"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (lowOrOutCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(TraderTheme.financial.warning, shape = RoundedCornerShape(4.dp))
                                )
                            }
                            Text(
                                text = if (lowOrOutCount > 0) {
                                    lang.tr(
                                        "${WesternFormatters.formatInt(state.products.size)} صنفاً مسجلاً — ${WesternFormatters.formatInt(lowOrOutCount)} بحاجة لإعادة طلب",
                                        "${WesternFormatters.formatInt(state.products.size)} registered — ${WesternFormatters.formatInt(lowOrOutCount)} need reorder"
                                    )
                                } else {
                                    lang.tr(
                                        "${WesternFormatters.formatInt(state.products.size)} صنفاً مسجلاً — المخزون مكتمل",
                                        "${WesternFormatters.formatInt(state.products.size)} registered — stock full"
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (canManageInventory && onSaveProduct != null) {
                        Button(
                            onClick = { isCreateProductOpen = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("add_product_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = lang.tr("إضافة صنف", "Add Product"),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // Secondary Utility: Warehouses, Transfer & Expiry Preview
                OutlinedButton(
                    onClick = { isStockTransferPreviewOpen = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag("open_stock_transfer_preview_button")
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = lang.tr("المستودعات والجرد والصلاحية", "Warehouses, Counts & Expiry"),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Inward Goods / Purchases Banner (Strictly guarded for Supplier Managers)
                if (state.currentSession.canManageSuppliers()) {
                    val pendingReceiptBillsCount = remember(state.purchaseBills) {
                        state.purchaseBills.count { !it.isReceived && it.expectedProductId != null && it.expectedQuantity > 0 }
                    }
                    Surface(
                        onClick = onOpenPurchases,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("open_purchases_banner")
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Inventory2,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = lang.tr("فواتير المشتريات والتوريد", "Purchases & Inward Goods"),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = lang.tr("سجل فواتير الموردين ومتابعة استلام البضائع", "Supplier bills & goods receipt"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (pendingReceiptBillsCount > 0) {
                                StitchBadge(
                                    text = lang.tr(
                                        "${WesternFormatters.formatInt(pendingReceiptBillsCount)} بانتظار الاستلام",
                                        "$pendingReceiptBillsCount awaiting receipt"
                                    ),
                                    tone = StitchBadgeTone.WARNING
                                )
                            }
                        }
                    }
                }

                // Integrated Search Field
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
                            IconButton(
                                onClick = { onSearchChange("") },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = lang.tr("مسح البحث", "Clear search")
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )

                // Category Browsing Filter Chips
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
                                        "نواقص (${WesternFormatters.formatInt(lowOrOutCount)})",
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

        // Section Title & Items Count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.tr("قائمة الأصناف المعروضة", "Catalog Products"),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = lang.tr(
                    "${WesternFormatters.formatInt(displayedProducts.size)} صنف",
                    "${WesternFormatters.formatInt(displayedProducts.size)} items"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // Scannable Product Directory List (Lower area preserved as approved)
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedProducts, key = { it.id }) { product ->
                    CatalogDirectoryRow(
                        product = product,
                        lang = lang,
                        canManageInventory = canManageInventory,
                        onClick = { onProductClick(product) }
                    )
                }
            }
        }
    }

    if (canManageInventory && isCreateProductOpen && onSaveProduct != null) {
        createDraft.SaveableStateProvider("product_${state.products.size}") {
            ProductEditorDialog(
                initialProduct = null,
                lang = lang,
                onDismiss = { isCreateProductOpen = false },
                onConfirmSave = { pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted ->
                    onSaveProduct(pid, nameAr, cat, unitAr, barcode, price, cost, minThreshold, initialStock, warehouse, lot, expiry, isWeighted)
                    isCreateProductOpen = false
                }
            )
        }
    }

    if (isStockTransferPreviewOpen) {
        StockCountTransferPreviewDialog(
            products = state.products,
            lang = lang,
            onDismiss = { isStockTransferPreviewOpen = false }
        )
    }
}

@Composable
private fun CatalogDirectoryRow(
    product: Product,
    lang: AppLanguage,
    canManageInventory: Boolean = false,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left/Start: Product Name + Unit + Category + Barcode (no cost or cart buttons here)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
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
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = WesternFormatters.formatIls(product.priceIls),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (canManageInventory && product.isStockTracked && product.costIls >= 0.0) {
                    Text(
                        text = "${lang.tr("التكلفة:", "Cost:")} ${WesternFormatters.formatIls(product.costIls)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
    onDismissFeedback: (() -> Unit)? = null,
    onSaveProduct: ((productId: String?, nameAr: String, category: ProductCategory, unitAr: String, barcode: String, priceIls: Double, costIls: Double, minStockThreshold: Int, initialStock: Int, warehouseAr: String, lotBatchCode: String, expiryDate: String?, isWeightedDisplayOnly: Boolean) -> Unit)? = null
) {
    val lang = state.language
    val product = state.selectedProductForDetail ?: return
    val canManageInventory = state.currentSession.canManageInventory()
    val movements = remember(state.stockMovements, product.id) {
        state.stockMovements.filter { it.productId == product.id }
    }

    var isEditPriceOpen by rememberSaveable(product.id) { mutableStateOf(false) }
    var isAdjustStockOpen by rememberSaveable(product.id) { mutableStateOf(false) }
    var isEditProductOpen by rememberSaveable(product.id) { mutableStateOf(false) }

    LaunchedEffect(canManageInventory) {
        if (!canManageInventory) {
            isEditPriceOpen = false
            isAdjustStockOpen = false
            isEditProductOpen = false
        }
    }
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
    val marginPercent = remember(product.priceIls, unitMarginIls) {
        if (product.priceIls > 0.0) {
            (unitMarginIls / product.priceIls) * 100.0
        } else {
            0.0
        }
    }

    val isOutOfStock = product.isStockTracked && product.stockCount <= 0
    val isLowStock = product.isStockTracked && product.stockCount > 0 && product.isLowStock

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("product_detail_screen")
    ) {

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("product_detail_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = lang.tr("إغلاق", "Dismiss"),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Card 1: Product Concise Identity Card
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Category Chip & Unit Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                ) {
                                    Text(
                                        text = product.category.label(lang),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = product.unit(lang),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (product.isWeightedDisplayOnly) {
                                    StitchBadge(
                                        text = lang.tr("صنف وزني", "Weighted Item"),
                                        tone = StitchBadgeTone.PRIMARY
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (product.isStockTracked) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    }
                                ) {
                                    Text(
                                        text = if (product.isStockTracked) {
                                            lang.tr("صنف مخزني", "Stock Item")
                                        } else {
                                            lang.tr("بدون جرد", "Non-Stock")
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (product.isStockTracked) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Full Product Name
                        Text(
                            text = product.name(lang),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Barcode Row
                        if (product.barcode.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
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
                        }
                    }
                }
            }

            // Card 2: Pricing & Profit Margins (Tonal Card, Guarded for Manager/Owner)
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payments,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = lang.tr("التسعير وهامش الربح", "Pricing & Margin"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Price and Margin Figures
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            // Selling Price (Always visible to all roles)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("سعر البيع", "Selling Price"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = WesternFormatters.formatIls(product.priceIls),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("product_detail_price_value")
                                )
                            }

                            // Cost Price & Calculated Margin (Strictly Owner/Manager guarded)
                            if (canManageInventory) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("product_detail_cost_section")
                                ) {
                                    Text(
                                        text = lang.tr("تكلفة الشراء", "Cost Price"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = WesternFormatters.formatIls(product.costIls),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = buildString {
                                            append(lang.tr("الهامش:", "Margin:"))
                                            append(" +${WesternFormatters.formatIls(unitMarginIls)}")
                                            append(" (${WesternFormatters.formatNumber(marginPercent)}%)")
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (unitMarginIls >= 0.0) TraderTheme.financial.income else TraderTheme.financial.debit,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }

                        // Price Edit Action (Authorized Manager/Owner only)
                        if (canManageInventory) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            OutlinedButton(
                                onClick = { isEditPriceOpen = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("edit_product_price_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = lang.tr("تعديل السعر وتحديث الهامش", "Edit Price & Margin"),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }
            }

            // Card 3: Stock Status & Warehouse Location (Restrained Tonal Alert Card)
            item {
                val containerColor = when {
                    isOutOfStock -> TraderTheme.financial.debitContainer.copy(alpha = 0.25f)
                    isLowStock -> TraderTheme.financial.warningContainer.copy(alpha = 0.25f)
                    else -> MaterialTheme.colorScheme.surface
                }
                val borderColor = when {
                    isOutOfStock -> TraderTheme.financial.debit.copy(alpha = 0.35f)
                    isLowStock -> TraderTheme.financial.warning.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = containerColor,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section Header with Status Badge
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Inventory2,
                                    contentDescription = null,
                                    tint = when {
                                        isOutOfStock -> TraderTheme.financial.debit
                                        isLowStock -> TraderTheme.financial.warning
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = lang.tr("حالة المخزون والمستودع", "Stock & Warehouse"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (product.isStockTracked) {
                                when {
                                    isOutOfStock -> StitchBadge(lang.tr("نفد المخزون", "Out of Stock"), tone = StitchBadgeTone.DANGER)
                                    isLowStock -> StitchBadge(lang.tr("نواقص المخزون", "Low Stock"), tone = StitchBadgeTone.WARNING)
                                    else -> StitchBadge(lang.tr("متوفر", "In Stock"), tone = StitchBadgeTone.SUCCESS)
                                }
                            }
                        }

                        if (product.isStockTracked) {
                            // Stock Count & Threshold
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = lang.tr("المخزون المتوفر حالياً", "Current Stock"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${WesternFormatters.formatInt(product.stockCount)} ${product.unit(lang)}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = when {
                                            isOutOfStock -> TraderTheme.financial.debit
                                            isLowStock -> TraderTheme.financial.warning
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.testTag("product_detail_stock_value")
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = lang.tr("حد إعادة الطلب (الإنذار)", "Reorder Threshold"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${WesternFormatters.formatInt(product.minStockThreshold)} ${product.unit(lang)}",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Storage Location
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("الموقع والمكان:", "Storage Location:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = product.warehouse(lang),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Stock Adjustment Action (Strictly Owner/Manager guarded)
                            if (canManageInventory) {
                                HorizontalDivider(color = borderColor.copy(alpha = 0.5f))
                                Button(
                                    onClick = { isAdjustStockOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("adjust_product_stock_button")
                                ) {
                                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = lang.tr("تسوية وتعديل المخزون", "Adjust Stock"),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        } else {
                            // Non-stock Item Content
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = lang.tr("بدون جرد كميات", "Non-stock item"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("product_detail_stock_value")
                                )
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
                        }
                    }
                }
            }

            // Card 4: Logistics & Batch Details (Progressive Disclosure)
            if (product.isStockTracked || !product.expiryDate.isNullOrBlank() || product.warehouse(lang).isNotBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Section Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalShipping,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = lang.tr("بيانات التوريد والدفعة", "Logistics & Batch Details"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Batch / Lot Code
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("رقم الدفعة (Batch):", "Lot / Batch Code:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = product.lotBatchCode.ifBlank { "—" },
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Expiry Date
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("تاريخ انتهاء الصلاحية:", "Expiry Date:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = product.expiryDate ?: lang.tr("غير محدد", "Not specified"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Warehouse
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr("المستودع الرئيسي:", "Primary Warehouse:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = product.warehouse(lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Edit Product Dialog Button (Manager/Owner only)
                            if (canManageInventory && onSaveProduct != null) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                OutlinedButton(
                                    onClick = { isEditProductOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("edit_product_button")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = lang.tr(
                                            "تعديل بيانات الصنف، الباركود والمستودع",
                                            "Edit Product Details, Barcode & Warehouse"
                                        ),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Stock Movement History Section (For stock-tracked products)
            if (product.isStockTracked) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.tr("سجل حركات المخزون", "Stock Movement History"),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lang.tr(
                                "${WesternFormatters.formatInt(movements.size)} حركة",
                                "${WesternFormatters.formatInt(movements.size)} entries"
                            ),
                            style = MaterialTheme.typography.labelSmall,
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
                                modifier = Modifier.padding(14.dp)
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

        // Sticky Bottom Action Bar (R2-PRODUCT-DETAIL 52dp Primary Action)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onAddToBasket(product) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("product_detail_add_to_basket_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = lang.tr("إضافة إلى سلة البيع", "Add to Basket"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ) {
                        Text(
                            text = WesternFormatters.formatIls(product.priceIls),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
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
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = lang.tr("تعديل سعر البيع", "Edit Selling Price"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                    shape = RoundedCornerShape(10.dp),
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
        var isAddingStock by rememberSaveable { mutableStateOf(true) }
        var qtyInputText by rememberSaveable { mutableStateOf("10") }
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
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = lang.tr("تسوية وتعديل رصيد المخزون", "Inventory Stock Adjustment"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        )
                        FilterChip(
                            selected = !isAddingStock,
                            onClick = {
                                isAddingStock = false
                                stockReasonText = lang.tr("تالف أو منتهي الصلاحية", "Damaged or expired")
                            },
                            leadingIcon = { Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(lang.tr("خصم / تالف (−)", "Deduct (−)")) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
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
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                                label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = stockReasonText,
                        onValueChange = { stockReasonText = it },
                        label = { Text(lang.tr("سبب التعديل (مطلوب للسجل)", "Reason for Adjustment")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
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
                            modifier = Modifier.padding(8.dp)
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
                    shape = RoundedCornerShape(10.dp),
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

    if (canManageInventory && isEditProductOpen && onSaveProduct != null) {
        ProductEditorDialog(
            initialProduct = product,
            lang = lang,
            onDismiss = { isEditProductOpen = false },
            onConfirmSave = { pid, nameAr, cat, unitAr, barcode, price, cost, minStock, stock, warehouseAr, lotBatch, expiry, weighted ->
                onSaveProduct(pid, nameAr, cat, unitAr, barcode, price, cost, minStock, stock, warehouseAr, lotBatch, expiry, weighted)
                isEditProductOpen = false
            }
        )
    }
}

@Composable
internal fun ProductEditorDialog(
    initialProduct: Product?,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmSave: (
        productId: String?,
        nameAr: String,
        category: ProductCategory,
        unitAr: String,
        barcode: String,
        priceIls: Double,
        costIls: Double,
        minStockThreshold: Int,
        initialStock: Int,
        warehouseAr: String,
        lotBatchCode: String,
        expiryDate: String?,
        isWeightedDisplayOnly: Boolean
    ) -> Unit
) {
    val isEditing = initialProduct != null
    var nameAr by rememberSaveable(initialProduct) { mutableStateOf(initialProduct?.nameAr ?: "") }
    var selectedCategory by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.category ?: ProductCategory.PANTRY)
    }
    var unitAr by rememberSaveable(initialProduct) { mutableStateOf(initialProduct?.unitAr ?: "حبة") }
    var priceText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.priceIls?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "")
    }
    var costText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.costIls?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "")
    }
    var stockText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.stockCount?.toString() ?: "10")
    }
    var minStockText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.minStockThreshold?.toString() ?: "4")
    }
    var barcodeText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.barcode ?: "7290009988")
    }
    var warehouseAr by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.warehouseAr ?: "رف المعرض الأمامي - A1")
    }
    var lotBatchText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.lotBatchCode ?: "")
    }
    var expiryText by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.expiryDate ?: "")
    }
    var isWeightedDisplayOnly by rememberSaveable(initialProduct) {
        mutableStateOf(initialProduct?.isWeightedDisplayOnly ?: false)
    }

    val parsedPrice = priceText.toDoubleOrNull() ?: 0.0
    val parsedCost = costText.toDoubleOrNull() ?: Double.NaN
    val parsedStock = stockText.toIntOrNull() ?: 0
    val parsedMinStock = minStockText.toIntOrNull() ?: 3
    val canSubmit = nameAr.trim().length >= 2 && parsedPrice.isFinite() && parsedPrice > 0.0 && parsedCost.isFinite() && parsedCost >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) {
                    lang.tr("تعديل بطاقة الصنف والمستودع", "Edit Product & Warehouse Card")
                } else {
                    lang.tr("تعريف صنف جديد بالمخزون", "Create New Catalog Product")
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it },
                    label = { Text(lang.tr("اسم الصنف بالعربية *", "Arabic Product Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("product_editor_name_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = {
                            priceText = WesternFormatters.sanitizeWesternDigits(it)
                                .filter { ch -> ch.isDigit() || ch == '.' }
                        },
                        label = { Text(lang.tr("سعر البيع (₪) *", "Sale Price (₪) *")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_price_input")
                    )
                    OutlinedTextField(
                        value = costText,
                        onValueChange = {
                            costText = WesternFormatters.sanitizeWesternDigits(it)
                                .filter { ch -> ch.isDigit() || ch == '.' }
                        },
                        label = { Text(lang.tr("التكلفة (₪) *", "Unit cost (₪) *")) },
                        isError = costText.isNotBlank() && (!parsedCost.isFinite() || parsedCost < 0.0),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_cost_input")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = unitAr,
                        onValueChange = { unitAr = it },
                        label = { Text(lang.tr("الوحدة (كغم / كيس / علبة)", "Unit (Ar)")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_unit_input")
                    )
                    if (!isEditing) {
                        OutlinedTextField(
                            value = stockText,
                            onValueChange = {
                                stockText = WesternFormatters.sanitizeWesternDigits(it).filter { ch -> ch.isDigit() }
                            },
                            label = { Text(lang.tr("رصيد افتتاحي", "Opening Stock")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("product_editor_stock_input")
                        )
                    } else {
                        OutlinedTextField(
                            value = minStockText,
                            onValueChange = {
                                minStockText = WesternFormatters.sanitizeWesternDigits(it).filter { ch -> ch.isDigit() }
                            },
                            label = { Text(lang.tr("حد التنبيه الأدنى", "Min Alert Stock")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("product_editor_min_stock_input")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = barcodeText,
                        onValueChange = { barcodeText = WesternFormatters.sanitizeWesternDigits(it) },
                        label = { Text(lang.tr("الباركود / SKU", "Barcode / SKU")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_barcode_input")
                    )
                    OutlinedTextField(
                        value = warehouseAr,
                        onValueChange = { warehouseAr = it },
                        label = { Text(lang.tr("المستودع / الرف", "Warehouse / Shelf")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_warehouse_input")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = lotBatchText,
                        onValueChange = { lotBatchText = it },
                        label = { Text(lang.tr("رقم التشغيلة (Lot)", "Lot / Batch")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_lot_input")
                    )
                    OutlinedTextField(
                        value = expiryText,
                        onValueChange = { expiryText = WesternFormatters.sanitizeWesternDigits(it) },
                        label = { Text(lang.tr("تاريخ الصلاحية (YYYY-MM)", "Expiry Date")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_editor_expiry_input")
                    )
                }

                // Weighted / Fractional goods toggle (display-only honesty)
                FilterChip(
                    selected = isWeightedDisplayOnly,
                    onClick = { isWeightedDisplayOnly = !isWeightedDisplayOnly },
                    label = {
                        Text(
                            lang.tr(
                                "صنف موزون بالجرام / كسور الكيلو (معاينة عرض فقط بدون ترحيل كسري وهمي)",
                                "Weighted / Fractional Item (Display preview only — integer stock ledger)"
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("product_editor_weighted_chip")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSubmit) {
                        onConfirmSave(
                            initialProduct?.id,
                            nameAr.trim(),
                            selectedCategory,
                            unitAr.trim().ifBlank { "حبة" },
                            barcodeText.trim(),
                            parsedPrice,
                            parsedCost,
                            parsedMinStock,
                            parsedStock,
                            warehouseAr.trim(),
                            lotBatchText.trim(),
                            expiryText.trim().ifBlank { null },
                            isWeightedDisplayOnly
                        )
                    }
                },
                enabled = canSubmit,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_save_product_button")
            ) {
                Text(lang.tr("حفظ بطاقة الصنف", "Save Product"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
private fun StockCountTransferPreviewDialog(
    products: List<Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val expiryTracked = remember(products) { products.filter { !it.expiryDate.isNullOrBlank() } }
    val weightedItems = remember(products) { products.filter { it.isWeightedDisplayOnly } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = lang.tr(
                    "معاينة المستودعات، التحويل المخزني، والتشغيلات (Lot/Expiry)",
                    "Warehouses, Stock Transfers & Lot/Expiry Preview"
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TraderTheme.financial.warningContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, TraderTheme.financial.warning.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "معاينة نموذجية (Shallow Preview): التعديل الفردي لحركة الصنف يعمل فعلياً من شاشة الصنف، بينما جلسات الجرد الجماعي والتحويل بين المستودعات مرتبطة مستقبلاً بنواة Laravel /api/v1.",
                            "Shallow Preview: Single-item stock adjustment is live in Product Detail, whereas bulk count sessions and inter-warehouse transfers connect to Laravel /api/v1 in backend integration."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Text(
                    text = lang.tr("المستودعات ومواقع التخزين المعرّفة:", "Configured Storage Locations:"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = lang.tr(
                        "• المستودع الرئيسي الخلفي (جملة وكراتين)\n• رف المعرض الأمامي - A1 / B2 (بيع مباشر)\n• ثلاجة الألبان والمبردات - C1\n• ركن المحمصة والبهارات (أصناف موزونة)",
                        "• Main Backroom Warehouse (Bulk)\n• Front Aisle - A1 / B2 (Direct POS)\n• Dairy Chiller - C1\n• Roastery & Spices Corner (Weighted goods)"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Text(
                    text = lang.tr(
                        "أصناف بتشغيلة وتاريخ صلاحية (${WesternFormatters.formatInt(expiryTracked.size)} صنف):",
                        "Lot & Expiry Tracked Items (${WesternFormatters.formatInt(expiryTracked.size)}):"
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
                expiryTracked.take(4).forEach { item ->
                    Text(
                        text = "• ${item.name(lang)} — Lot: ${item.lotBatchCode} • ${lang.tr("صلاحية:", "Exp:")} ${item.expiryDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (weightedItems.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = lang.tr(
                            "أصناف موزونة بالجرام/الكيلو (${WesternFormatters.formatInt(weightedItems.size)} صنف — عرض مرجعي):",
                            "Weighted Goods (${WesternFormatters.formatInt(weightedItems.size)} — display reference):"
                        ),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    weightedItems.forEach { item ->
                        Text(
                            text = "• ${item.name(lang)} (${WesternFormatters.formatIls(item.priceIls)} / ${item.unit(lang)}) — 0.250 kg = ${WesternFormatters.formatIls(item.priceIls * 0.25)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("close_stock_transfer_preview_button")
            ) {
                Text(lang.tr("إغلاق المعاينة", "Close Preview"))
            }
        }
    )
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isPositive) TraderTheme.financial.income else TraderTheme.financial.debit
                )
                Text(
                    text = "${lang.tr("الرصيد:", "Stock:")} ${WesternFormatters.formatInt(sm.resultingStock)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

