package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Discount
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.Customer
import com.example.model.DiscountPreset
import com.example.model.Product
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InvoiceReviewScreen(
    state: TraderUiState,
    onAddMoreItems: () -> Unit,
    onIncrementProduct: (Product) -> Unit,
    onDecrementProduct: (String) -> Unit,
    onRemoveProduct: (String) -> Unit,
    onSelectDiscountPreset: (DiscountPreset) -> Unit,
    onUpdateCustomDiscount: (String) -> Unit,
    onUpdateCustomerNote: (String) -> Unit,
    onAssignCustomer: (Customer?) -> Unit,
    onProceedToCashPayment: () -> Unit,
    onSaveDraft: (() -> Unit)? = null,
    onResumeDraft: ((String) -> Unit)? = null,
    onDeleteDraft: ((String) -> Unit)? = null,
    onFormatQuotation: (() -> String)? = null
) {
    val lang = state.language
    var isCustomerPickerOpen by rememberSaveable { mutableStateOf(false) }
    var isQuotationDialogOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("invoice_review_screen")
    ) {
        if (state.basket.isEmpty()) {
            EmptyReviewBasketState(
                lang = lang,
                savedDrafts = state.savedDrafts,
                onReturnToCatalog = onAddMoreItems,
                onResumeDraft = onResumeDraft,
                onDeleteDraft = onDeleteDraft,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 0. Saved Sale Drafts (Hold / Resume) + Save Draft & Quotation Bar
                item {
                    DraftsAndQuotationBar(
                        savedDrafts = state.savedDrafts,
                        lang = lang,
                        onSaveDraft = onSaveDraft,
                        onOpenQuotation = { isQuotationDialogOpen = true },
                        onResumeDraft = onResumeDraft,
                        onDeleteDraft = onDeleteDraft
                    )
                }

                // 1. Editable Invoice Line Items Ledger
                item {
                    EditableInvoiceLinesSection(
                        basket = state.basket,
                        totalUnits = state.basketItemCount,
                        subtotalIls = state.basketSubtotalIls,
                        lang = lang,
                        onAddMoreItems = onAddMoreItems,
                        onIncrementProduct = onIncrementProduct,
                        onDecrementProduct = onDecrementProduct,
                        onRemoveProduct = onRemoveProduct
                    )
                }

                // 2. Customer Account & Consequence Section (Scales to hundreds of customers via Searchable Sheet)
                item {
                    CustomerAndAccountImpactCard(
                        selectedCustomer = state.selectedCustomer,
                        projectedBalanceIfCreditIls = state.projectedCustomerBalanceIfCreditIls,
                        wouldCreditExceedLimit = state.wouldCreditExceedCustomerLimit,
                        customerNote = state.customerNote,
                        totalCustomersCount = state.customers.size,
                        lang = lang,
                        onOpenCustomerPicker = { isCustomerPickerOpen = true },
                        onClearCustomer = { onAssignCustomer(null) },
                        onUpdateCustomerNote = onUpdateCustomerNote
                    )
                }

                // 3. Permitted Discount & Rounding Section
                if (state.currentSession.canApplyDiscounts()) {
                    item {
                        DiscountAndRoundingCard(
                            state = state,
                            lang = lang,
                            onSelectDiscountPreset = onSelectDiscountPreset,
                            onUpdateCustomDiscount = onUpdateCustomDiscount
                        )
                    }
                }
            }
        }

        // 4. Single Authoritative Summary, Consequence Strip & Checkout Dock
        ReviewCheckoutDock(
            state = state,
            lang = lang,
            onProceedToCashPayment = onProceedToCashPayment
        )
    }

    if (isCustomerPickerOpen) {
        CustomerPickerModalSheet(
            customers = state.customers,
            selectedCustomerId = state.selectedCustomerId,
            basketNetTotalIls = state.basketNetTotalIls,
            lang = lang,
            onDismiss = { isCustomerPickerOpen = false },
            onSelectCustomer = { chosen ->
                onAssignCustomer(chosen)
                isCustomerPickerOpen = false
            }
        )
    }

    if (isQuotationDialogOpen) {
        QuotationPreviewDialog(
            state = state,
            quotationText = onFormatQuotation?.invoke() ?: "",
            lang = lang,
            onDismiss = { isQuotationDialogOpen = false }
        )
    }
}

@Composable
private fun EmptyReviewBasketState(
    lang: AppLanguage,
    savedDrafts: List<com.example.model.SaleDraft>,
    onReturnToCatalog: () -> Unit,
    onResumeDraft: ((String) -> Unit)? = null,
    onDeleteDraft: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ShoppingBag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Text(
                text = lang.tr("لم يتبقَّ أي صنف في الفاتورة", "No items left in the invoice"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = lang.tr(
                    "أضف أصنافاً من القائمة لمتابعة مراجعة الفاتورة والدفع.",
                    "Add items from the catalog to review and complete the sale."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onReturnToCatalog,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("invoice_add_more_items_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(lang.tr("العودة لاختيار الأصناف", "Return to Products"))
            }

            if (savedDrafts.isNotEmpty() && onResumeDraft != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SavedSaleDraftsCard(
                    savedDrafts = savedDrafts,
                    lang = lang,
                    onResumeDraft = onResumeDraft,
                    onDeleteDraft = onDeleteDraft
                )
            }
        }
    }
}

@Composable
private fun DraftsAndQuotationBar(
    savedDrafts: List<com.example.model.SaleDraft>,
    lang: AppLanguage,
    onSaveDraft: (() -> Unit)?,
    onOpenQuotation: () -> Unit,
    onResumeDraft: ((String) -> Unit)?,
    onDeleteDraft: ((String) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onSaveDraft != null) {
                OutlinedButton(
                    onClick = onSaveDraft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("save_draft_button")
                ) {
                    Text(
                        text = lang.tr("حفظ كمسودة بيع", "Save as Draft"),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenQuotation,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("open_quotation_preview_button")
            ) {
                Text(
                    text = lang.tr("عرض سعر (Quotation)", "Price Quotation"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }

        if (savedDrafts.isNotEmpty() && onResumeDraft != null) {
            SavedSaleDraftsCard(
                savedDrafts = savedDrafts,
                lang = lang,
                onResumeDraft = onResumeDraft,
                onDeleteDraft = onDeleteDraft
            )
        }
    }
}

@Composable
private fun SavedSaleDraftsCard(
    savedDrafts: List<com.example.model.SaleDraft>,
    lang: AppLanguage,
    onResumeDraft: (String) -> Unit,
    onDeleteDraft: ((String) -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_sale_drafts_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = lang.tr(
                    "مسودات بيع معلقة (${WesternFormatters.formatInt(savedDrafts.size)}) — لا تخصم من المخزون حتى اعتمادها",
                    "Held Sale Drafts (${WesternFormatters.formatInt(savedDrafts.size)}) — Not posted to stock/cash yet"
                ),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            savedDrafts.forEach { draft ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
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
                                text = draft.title(lang),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${WesternFormatters.formatInt(draft.totalQuantity)} ${lang.tr("وحدة", "units")} • ${WesternFormatters.formatIls(draft.netTotalIls)} • ${draft.timestamp}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = { onResumeDraft(draft.id) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .testTag("resume_draft_${draft.id}")
                            ) {
                                Text(
                                    text = lang.tr("استئناف", "Resume"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            if (onDeleteDraft != null) {
                                IconButton(
                                    onClick = { onDeleteDraft(draft.id) },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("delete_draft_${draft.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = lang.tr("حذف المسودة", "Delete draft"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
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
private fun QuotationPreviewDialog(
    state: TraderUiState,
    quotationText: String,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copiedFeedback by rememberSaveable { mutableStateOf(false) }
    val displayDoc = quotationText.ifBlank {
        buildString {
            appendLine(lang.tr("عرض سعر مرجعي (غير مرحّل)", "Price Quotation (Non-Posting)"))
            appendLine("${lang.tr("الصافي التقديري:", "Estimated Net Total:")} ${WesternFormatters.formatIls(state.basketNetTotalIls)}")
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = lang.tr("معاينة عرض سعر للزبون (Quotation)", "Customer Price Quotation Preview"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TraderTheme.financial.warningContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "وثيقة تسعير فقط: لا تخصم من المخزون ولا تسجل نقداً أو ذمة على الزبون حتى يتم اعتماد الفاتورة.",
                            "Non-posting quotation: Does not deduct inventory or record cash/debt until confirmed as a sale."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = displayDoc,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier
                            .padding(12.dp)
                            .testTag("quotation_preview_text")
                    )
                }
                if (copiedFeedback) {
                    Text(
                        text = lang.tr("تم نسخ نص عرض السعر للحافظة", "Quotation copied to clipboard"),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TraderTheme.financial.income
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(displayDoc))
                    copiedFeedback = true
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("copy_quotation_button")
            ) {
                Text(lang.tr("نسخ عرض السعر", "Copy Quotation"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(lang.tr("إغلاق", "Close"))
            }
        }
    )
}

/**
 * Unified Invoice Ledger Card:
 * Groups all basket lines inside one clean ledger surface with hairline dividers.
 * Each line has two uncluttered rows:
 * - Row 1: Index + Full Arabic/English product name (up to 2 lines) | Bold tabular Line Total
 * - Row 2: Unit price × quantity (unit) + stock impact | Remove icon + [- qty +] stepper
 */
@Composable
private fun EditableInvoiceLinesSection(
    basket: List<BasketItem>,
    totalUnits: Int,
    subtotalIls: Double,
    lang: AppLanguage,
    onAddMoreItems: () -> Unit,
    onIncrementProduct: (Product) -> Unit,
    onDecrementProduct: (String) -> Unit,
    onRemoveProduct: (String) -> Unit
) {
    val financial = TraderTheme.financial

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Card Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lang.tr("الأصناف والكميات", "Invoice Items"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lang.tr(
                            "${WesternFormatters.formatInt(basket.size)} أصناف • ${WesternFormatters.formatInt(totalUnits)} وحدات • المجموع الفرعي ${WesternFormatters.formatIls(subtotalIls)}",
                            "${WesternFormatters.formatInt(basket.size)} lines • ${WesternFormatters.formatInt(totalUnits)} units • Subtotal ${WesternFormatters.formatIls(subtotalIls)}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = onAddMoreItems,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("invoice_add_more_items_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lang.tr("إضافة أصناف", "Add Items"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            // Line items
            basket.forEachIndexed { index, item ->
                val product = item.product
                val isMaxStock = product.isStockTracked && item.quantity >= product.stockCount
                val stockRemainingAfterSale = (product.stockCount - item.quantity).coerceAtLeast(0)

                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("invoice_line_${product.id}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Top Row: Index + Full Product Name | Line Total
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${WesternFormatters.formatInt(index + 1)}.",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                            Text(
                                text = product.name(lang),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = WesternFormatters.formatIls(item.lineTotal),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Bottom Row: Two-line Unit price × quantity & stock impact | Remove + [- qty +] 48dp stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "${WesternFormatters.formatIls(product.priceIls)} × ${WesternFormatters.formatInt(item.quantity)} (${product.unit(lang)})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    !product.isStockTracked -> lang.tr("مادة حرة بدون جرد مخزني", "Custom item (no stock deduction)")
                                    isMaxStock -> lang.tr(
                                        "الحد الأقصى بالمخزون (${WesternFormatters.formatInt(product.stockCount)})",
                                        "Max available stock (${WesternFormatters.formatInt(product.stockCount)})"
                                    )
                                    else -> lang.tr(
                                        "يتبقى بالمخزون بعد البيع: ${WesternFormatters.formatInt(stockRemainingAfterSale)}",
                                        "Stock remaining after sale: ${WesternFormatters.formatInt(stockRemainingAfterSale)}"
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isMaxStock) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isMaxStock) financial.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { onRemoveProduct(product.id) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("remove_line_${product.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = lang.tr("حذف الصنف", "Remove item"),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            FilledTonalIconButton(
                                onClick = { onDecrementProduct(product.id) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("dec_line_${product.id}"),
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
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .testTag("qty_line_${product.id}")
                            )

                            FilledIconButton(
                                onClick = { onIncrementProduct(product) },
                                enabled = !isMaxStock,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("inc_line_${product.id}"),
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
}

/**
 * Customer Selection & Account Consequence Card:
 * Replaces unbounded customer chips with a scalable Walk-in vs. Known Customer selector
 * that opens a searchable customer picker sheet and shows exact debt/credit-limit consequences.
 */
@Composable
private fun CustomerAndAccountImpactCard(
    selectedCustomer: Customer?,
    projectedBalanceIfCreditIls: Double?,
    wouldCreditExceedLimit: Boolean,
    customerNote: String,
    totalCustomersCount: Int,
    lang: AppLanguage,
    onOpenCustomerPicker: () -> Unit,
    onClearCustomer: () -> Unit,
    onUpdateCustomerNote: (String) -> Unit
) {
    val financial = TraderTheme.financial

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (selectedCustomer != null) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (selectedCustomer != null) Icons.Default.Person else Icons.Default.PersonOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = lang.tr("الزبون وطريقة القيد", "Customer & Account"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (selectedCustomer == null) {
                                lang.tr(
                                    "مبيعات نقدية عامة (لا تُسجّل ذمة على الحساب)",
                                    "Walk-in cash sale (cannot record account debt)"
                                )
                            } else {
                                lang.tr(
                                    "حساب مسجل — يقبل الدفع النقدي أو القيد بالذمة",
                                    "Registered account — supports cash or credit debt"
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onOpenCustomerPicker,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("select_customer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (selectedCustomer == null) {
                            lang.tr(
                                "ربط بزبون (${WesternFormatters.formatInt(totalCustomersCount)})",
                                "Select Customer (${WesternFormatters.formatInt(totalCustomersCount)})"
                            )
                        } else {
                            lang.tr("تغيير الزبون", "Change")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (selectedCustomer != null) {
                // Known Customer Account & Debt Consequence Summary
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_customer_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedCustomer.name(lang),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${selectedCustomer.phone} • ${selectedCustomer.address(lang)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(
                                onClick = onClearCustomer,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("clear_selected_customer_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = lang.tr("نقدي عام", "Walk-in"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 3-column Debt Consequence Preview: Current Debt | If Posted on Credit | Credit Limit
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("الرصيد الحالي", "Current Debt"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(selectedCustomer.balanceIls),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedCustomer.balanceIls > 0.005) {
                                        financial.debit
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            Column(modifier = Modifier.weight(1.2f)) {
                                Text(
                                    text = lang.tr("لو سُجّلت بالذمة", "If Posted on Credit"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(
                                        projectedBalanceIfCreditIls ?: selectedCustomer.balanceIls
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (wouldCreditExceedLimit) financial.debit else financial.warning
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("سقف الائتمان", "Credit Limit"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(selectedCustomer.creditLimitIls),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (wouldCreditExceedLimit) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = financial.debitContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = financial.debit,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = lang.tr(
                                            "تسجيل كامل الفاتورة بالذمة يتجاوز سقف الائتمان (${WesternFormatters.formatIls(selectedCustomer.creditLimitIls)})؛ يلزم الدفع النقدي الكامل أو تقليل الأصناف.",
                                            "Full credit exceeds limit (${WesternFormatters.formatIls(selectedCustomer.creditLimitIls)}); full cash payment or reducing items required."
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = financial.debit
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Optional Invoice / Delivery Note Input
            OutlinedTextField(
                value = customerNote,
                onValueChange = onUpdateCustomerNote,
                placeholder = {
                    Text(
                        text = if (selectedCustomer != null) {
                            lang.tr(
                                "ملاحظة إضافية على الفاتورة (مثال: استلام ابنه / توصيل)...",
                                "Optional invoice note (e.g. delivery / recipient)..."
                            )
                        } else {
                            lang.tr(
                                "اسم المستلم أو ملاحظة اختيارية على الإيصال...",
                                "Recipient name or optional receipt note..."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingIcon = {
                    if (customerNote.isNotEmpty()) {
                        IconButton(onClick = { onUpdateCustomerNote("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = lang.tr("مسح الملاحظة", "Clear note"),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("customer_note_input")
            )
        }
    }
}

/**
 * Searchable Customer Picker Modal Sheet:
 * Scales cleanly to hundreds of customers with instant search by Arabic/English name,
 * phone number, or address, and shows current balance & credit limit before selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerPickerModalSheet(
    customers: List<Customer>,
    selectedCustomerId: String?,
    basketNetTotalIls: Double,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onSelectCustomer: (Customer?) -> Unit
) {
    val financial = TraderTheme.financial
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val normalizedQuery = TraderUiState.normalizeForSearch(
        WesternFormatters.sanitizeWesternDigits(searchQuery.trim())
    )
    val filteredCustomers = if (normalizedQuery.isEmpty()) {
        customers
    } else {
        customers.filter { cust ->
            TraderUiState.normalizeForSearch(cust.nameAr).contains(normalizedQuery) ||
                TraderUiState.normalizeForSearch(cust.nameEn).contains(normalizedQuery) ||
                cust.phone.contains(normalizedQuery) ||
                TraderUiState.normalizeForSearch(cust.addressAr).contains(normalizedQuery) ||
                TraderUiState.normalizeForSearch(cust.addressEn).contains(normalizedQuery)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("customer_picker_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = lang.tr("اختيار الزبون للفاتورة", "Select Customer for Invoice"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = lang.tr(
                            "ابحث بالاسم أو رقم الهاتف أو المنطقة (${WesternFormatters.formatInt(customers.size)} زبون مسجل)",
                            "Search by name, phone, or area (${WesternFormatters.formatInt(customers.size)} registered)"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = lang.tr("إغلاق", "Close")
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text(
                        text = lang.tr(
                            "ابحث عن اسم الزبون أو رقم الجوال (مثال: أبو طارق / 0599)...",
                            "Search customer name or phone (e.g. Abu Tareq / 0599)..."
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = lang.tr("مسح", "Clear")
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("customer_picker_search_input")
            )

            // Walk-in Customer Option (Always available at top)
            Surface(
                onClick = { onSelectCustomer(null) },
                shape = RoundedCornerShape(12.dp),
                color = if (selectedCustomerId == null) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selectedCustomerId == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("picker_walk_in_option")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lang.tr("زبون نقدي مباشر (مبيعات عامة)", "Walk-in Customer (Cash Sale)"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = lang.tr(
                                "دفع نقدي كامل عند الكاشير بدون تسجيل ذمة",
                                "Full cash payment at counter without account debt"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedCustomerId == null) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp)
            ) {
                if (filteredCustomers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.tr("لا يوجد زبون مطابق للبحث", "No matching customer found"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filteredCustomers, key = { it.id }) { cust ->
                            val isSelected = selectedCustomerId == cust.id
                            val projectedDebt = cust.balanceIls + basketNetTotalIls
                            val exceedsLimit = projectedDebt > cust.creditLimitIls + 0.001

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable { onSelectCustomer(cust) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("customer_option_${cust.id}"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = cust.name(lang),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${cust.phone} • ${cust.address(lang)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = if (cust.balanceIls > 0.005) {
                                                lang.tr(
                                                    "ذمة: ${WesternFormatters.formatIls(cust.balanceIls)}",
                                                    "Debt: ${WesternFormatters.formatIls(cust.balanceIls)}"
                                                )
                                            } else {
                                                lang.tr("لا يوجد دين", "No debt")
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (cust.balanceIls > 0.005) financial.debit else financial.income
                                        )
                                        Text(
                                            text = if (exceedsLimit) {
                                                lang.tr(
                                                    "يتجاوز السقف (${WesternFormatters.formatIls(cust.creditLimitIls)})",
                                                    "Over limit (${WesternFormatters.formatIls(cust.creditLimitIls)})"
                                                )
                                            } else {
                                                lang.tr(
                                                    "السقف ${WesternFormatters.formatIls(cust.creditLimitIls)}",
                                                    "Limit ${WesternFormatters.formatIls(cust.creditLimitIls)}"
                                                )
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (exceedsLimit) financial.warning else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Permitted Discount & Rounding Card:
 * Displays preset options and live shekel deduction right in the header without clutter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscountAndRoundingCard(
    state: TraderUiState,
    lang: AppLanguage,
    onSelectDiscountPreset: (DiscountPreset) -> Unit,
    onUpdateCustomDiscount: (String) -> Unit
) {
    val financial = TraderTheme.financial
    val hasDiscount = state.calculatedDiscountIls > 0.005
    val rawCustomTyped = WesternFormatters.sanitizeWesternDigits(state.customDiscountText).toDoubleOrNull() ?: 0.0
    val isCustomCapped = state.discountPreset == DiscountPreset.CUSTOM_ILS &&
        rawCustomTyped > state.basketSubtotalIls + 0.005

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (hasDiscount) financial.income.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        imageVector = Icons.Default.Discount,
                        contentDescription = null,
                        tint = if (hasDiscount) financial.income else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = lang.tr("الخصم وجبر الكسر", "Discount & Rounding"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (hasDiscount) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = financial.incomeContainer
                    ) {
                        Text(
                            text = "- ${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = financial.income,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiscountPreset.entries.forEach { preset ->
                    val selected = state.discountPreset == preset
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectDiscountPreset(preset) },
                        label = {
                            Text(
                                text = lang.tr(preset.labelAr, preset.labelEn),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("discount_chip_${preset.name}")
                    )
                }
            }

            AnimatedVisibility(visible = state.discountPreset == DiscountPreset.CUSTOM_ILS) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = state.customDiscountText,
                        onValueChange = onUpdateCustomDiscount,
                        label = {
                            Text(lang.tr("قيمة الخصم الثابت بالشيكل (₪)", "Fixed Discount Amount in ILS (₪)"))
                        },
                        placeholder = { Text("5.00") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_discount_input")
                    )
                    if (isCustomCapped) {
                        Text(
                            text = lang.tr(
                                "تم تقييد الخصم بحد أقصى يساوي قيمة المجموع الفرعي (${WesternFormatters.formatIls(state.basketSubtotalIls)})",
                                "Discount is capped at the basket subtotal (${WesternFormatters.formatIls(state.basketSubtotalIls)})"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = financial.warning
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single Authoritative Summary & Consequence Dock:
 * Displays the final math (Subtotal, Discount, Net Total Due) and transaction impact
 * (inventory deduction & customer account target) ONCE at the bottom of the screen.
 */
@Composable
private fun ReviewCheckoutDock(
    state: TraderUiState,
    lang: AppLanguage,
    onProceedToCashPayment: () -> Unit
) {
    val financial = TraderTheme.financial
    val hasDiscount = state.calculatedDiscountIls > 0.005
    val linkedCustomer = state.selectedCustomer

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Subtotal & Discount breakdown row when discount is applied
            if (hasDiscount) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lang.tr(
                            "المجموع الفرعي: ${WesternFormatters.formatIls(state.basketSubtotalIls)}",
                            "Subtotal: ${WesternFormatters.formatIls(state.basketSubtotalIls)}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = lang.tr(
                            "الخصم المعتمد: - ${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                            "Discount: - ${WesternFormatters.formatIls(state.calculatedDiscountIls)}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = financial.income
                    )
                }
            }

            // Transaction Consequence Strip (Stock & Account Debt Impact)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = buildString {
                            append(
                                lang.tr(
                                    "خصم مخزون: ${WesternFormatters.formatInt(state.stockUnitsToDeduct)} قطع",
                                    "Stock: -${WesternFormatters.formatInt(state.stockUnitsToDeduct)} units"
                                )
                            )
                            if (state.customLinesCount > 0) {
                                append(
                                    lang.tr(
                                        " (+${WesternFormatters.formatInt(state.customLinesCount)} حرة)",
                                        " (+${WesternFormatters.formatInt(state.customLinesCount)} custom)"
                                    )
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = if (linkedCustomer != null) {
                        lang.tr(
                            "الحساب: ${linkedCustomer.name(lang)}",
                            "Account: ${linkedCustomer.name(lang)}"
                        )
                    } else {
                        lang.tr("القيد: زبون نقدي مباشر", "Account: Walk-in (Cash only)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (linkedCustomer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            // Single Authoritative Net Total Due + Primary Continue Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lang.tr("الصافي المطلوب للدفع", "Net Total Due"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = WesternFormatters.formatIls(state.basketNetTotalIls),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("invoice_net_total_text")
                    )
                }

                Button(
                    onClick = onProceedToCashPayment,
                    enabled = state.basket.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("proceed_to_cash_payment_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (linkedCustomer != null) {
                            lang.tr("متابعة للدفع أو الذمة", "Payment / Credit")
                        } else {
                            lang.tr("متابعة للدفع النقدي", "Cash Payment")
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
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
}
