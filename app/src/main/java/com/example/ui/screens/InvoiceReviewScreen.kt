package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Discount
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.BasketItem
import com.example.model.Customer
import com.example.model.DiscountPreset
import com.example.model.Product
import com.example.model.SaleDraft
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

/**
 * Rebuilt Native Invoice Review Screen for Small Trader (التاجر الصغير).
 *
 * Implements Google Stitch Canonical Direction (ST-09 / DESIGN_LOCK.md):
 * 1. Line Items Prioritized First: Immediate view and tactile editing of products,
 *    quantities, unit prices, and line totals in a cohesive StitchCard container.
 * 2. Progressive Disclosure for Customer: Compact Walk-in vs. Linked Customer selector
 *    reveals accounting consequences, credit limit checks, and notes on demand.
 * 3. Transparent Financial Summary & Discounts: Clear breakdown of subtotal, discount presets,
 *    and bold net total due with zero VAT/tax distortion.
 * 4. Safe Non-Posting Quotations & Drafts: Enables held sales and quotation previews with
 *    clipboard copy feedback that strictly avoid posting financial transactions.
 * 5. Shell-Safe Dock: Docked checkout bar with net total due and primary 'متابعة للدفع' action,
 *    relying on Shell-managed window insets without duplicate navigation bar padding.
 */
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Customer Account Selector Card (ST-09 Section 2)
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

                // 2. Editable Invoice Line Items Ledger (ST-09 Section 3)
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

                // 3. Permitted Discount & Rounding Section (ST-09 Section 4)
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

                // 4. Drafts & Quotation Action Bar + Collapsed Saved Drafts Group (ST-09 Section 5)
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
            }

            // 5. Sticky Bottom Action Dock (ST-09 Section 6)
            ReviewCheckoutDock(
                state = state,
                lang = lang,
                onProceedToCashPayment = onProceedToCashPayment
            )
        }
    }

    // Modal Customer Picker Sheet
    if (isCustomerPickerOpen) {
        CustomerPickerModalSheet(
            customers = state.customers,
            selectedCustomerId = state.selectedCustomer?.id,
            basketNetTotalIls = state.basketNetTotalIls,
            lang = lang,
            onDismiss = { isCustomerPickerOpen = false },
            onSelectCustomer = { cust ->
                onAssignCustomer(cust)
                isCustomerPickerOpen = false
            }
        )
    }

    // Non-Posting Quotation Preview Dialog
    if (isQuotationDialogOpen) {
        val quotationText = onFormatQuotation?.invoke() ?: ""
        QuotationPreviewDialog(
            state = state,
            quotationText = quotationText,
            lang = lang,
            onDismiss = { isQuotationDialogOpen = false }
        )
    }
}

/**
 * Empty Review Basket State: Friendly recovery prompting merchant to return to catalog.
 */
@Composable
private fun EmptyReviewBasketState(
    lang: AppLanguage,
    savedDrafts: List<SaleDraft>,
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
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ShoppingBag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onReturnToCatalog,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("invoice_add_more_items_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lang.tr("العودة لاختيار الأصناف", "Return to Products"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (savedDrafts.isNotEmpty() && onResumeDraft != null) {
                Spacer(modifier = Modifier.height(10.dp))
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

/**
 * Customer Selection & Account Consequence Card (ST-09 Section 2):
 * - Displays walk-in default or linked customer details with balance & limit warning.
 * - Minimum 48dp touch targets and progressive disclosure for customer notes.
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
    var isExpanded by rememberSaveable(selectedCustomer?.id) {
        mutableStateOf(selectedCustomer != null || customerNote.isNotEmpty())
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selectedCustomer != null) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectedCustomer != null) {
                    Modifier.testTag("selected_customer_card")
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Customer Status & Quick Switch Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (selectedCustomer != null) Icons.Default.Person else Icons.Default.PersonOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (selectedCustomer == null) {
                                    lang.tr("زبون نقدي (افتراضي)", "Cash Customer (Default)")
                                } else {
                                    selectedCustomer.name(lang)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (selectedCustomer == null) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(financial.income)
                                )
                            }
                        }

                        Text(
                            text = if (selectedCustomer == null) {
                                lang.tr("بيع نقدي مباشر", "Direct cash sale")
                            } else {
                                "${WesternFormatters.formatIls(selectedCustomer.balanceIls)} • ${selectedCustomer.phone}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedCustomer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenCustomerPicker,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("select_customer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwitchAccount,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (selectedCustomer == null) {
                                lang.tr("ربط زبون", "Select")
                            } else {
                                lang.tr("تغيير", "Change")
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("toggle_customer_section_button")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) lang.tr("طي", "Collapse") else lang.tr("توسيع", "Expand"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedCustomer != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = lang.tr("الرصيد الحالي:", "Current balance:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = WesternFormatters.formatIls(selectedCustomer.balanceIls),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (selectedCustomer.balanceIls > 0) financial.debit else financial.income
                                    )
                                }

                                if (projectedBalanceIfCreditIls != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lang.tr("الرصيد التقديري في حال البيع الآجل:", "Projected if sold on credit:"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(projectedBalanceIfCreditIls),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (wouldCreditExceedLimit) financial.debit else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                if (wouldCreditExceedLimit) {
                                    StitchBadge(
                                        text = lang.tr(
                                            "تحذير: سيتجاوز سقف الائتمان (${WesternFormatters.formatIls(selectedCustomer.creditLimitIls)})",
                                            "Warning: Exceeds credit limit (${WesternFormatters.formatIls(selectedCustomer.creditLimitIls)})"
                                        ),
                                        tone = StitchBadgeTone.DANGER
                                    )
                                }

                                TextButton(
                                    onClick = onClearCustomer,
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .heightIn(min = 40.dp)
                                        .testTag("clear_selected_customer_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = lang.tr("إلغاء ربط الزبون (العودة للزبون العام)", "Unlink customer"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // Optional customer/sale note input (10dp radius per DESIGN_LOCK)
                    OutlinedTextField(
                        value = customerNote,
                        onValueChange = onUpdateCustomerNote,
                        placeholder = {
                            Text(
                                text = lang.tr(
                                    "اكتب ملاحظة مختصرة للزبون أو للأرشيف...",
                                    "Brief note for customer or invoice archive..."
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = false,
                        maxLines = 3,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_note_input")
                    )
                }
            }
        }
    }
}

/**
 * Itemized Basket List & Editable Line Items (ST-09 Section 3):
 * - Groups cart lines in a standard StitchCard with dividers.
 * - Line item displays product name, unit price, quantity, and line total.
 * - Tactile 48dp stepper buttons and remove action.
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

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: Summary & Add More Items CTA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lang.tr("الأصناف في السلة", "Basket Items"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lang.tr(
                            "${WesternFormatters.formatInt(basket.size)} أصناف • ${WesternFormatters.formatInt(totalUnits)} قطع • المجموع ${WesternFormatters.formatIls(subtotalIls)}",
                            "${WesternFormatters.formatInt(basket.size)} items • ${WesternFormatters.formatInt(totalUnits)} units • Total ${WesternFormatters.formatIls(subtotalIls)}"
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
                        .heightIn(min = 44.dp)
                        .testTag("invoice_add_more_items_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lang.tr("تعديل السلة", "Add Items"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Itemized Rows
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
                    // Top Row: Index + Title | Line Total
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
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

                    // Bottom Row: Unit Price & Stock Note | Stepper Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                text = lang.tr(
                                    "الكمية: ${WesternFormatters.formatInt(item.quantity)} • سعر القطعة: ${WesternFormatters.formatIls(product.priceIls)} (${product.unit(lang)})",
                                    "Qty: ${WesternFormatters.formatInt(item.quantity)} • Unit: ${WesternFormatters.formatIls(product.priceIls)} (${product.unit(lang)})"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (product.isStockTracked) {
                                Text(
                                    text = if (isMaxStock) {
                                        lang.tr(
                                            "الحد الأقصى بالمخزون (${WesternFormatters.formatInt(product.stockCount)})",
                                            "Max available stock (${WesternFormatters.formatInt(product.stockCount)})"
                                        )
                                    } else {
                                        lang.tr(
                                            "يتبقى بالمخزون بعد البيع: ${WesternFormatters.formatInt(stockRemainingAfterSale)}",
                                            "Remaining after sale: ${WesternFormatters.formatInt(stockRemainingAfterSale)}"
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isMaxStock) financial.warning else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Stepper Action Group (Min 48dp touch hit targets)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
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

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onDecrementProduct(product.id) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("dec_line_${product.id}")
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

                                    IconButton(
                                        onClick = { onIncrementProduct(product) },
                                        enabled = !isMaxStock,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("inc_line_${product.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = lang.tr("زيادة", "Increase"),
                                            tint = if (isMaxStock) {
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
        }
    }
}

/**
 * Financial Summary & Discount Card (ST-09 Section 4):
 * - Subtotal, expandable discount presets, and prominent grand net total due.
 * - Zero VAT or unauthorized tax additions per financial integrity mandates.
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
    var isExpanded by rememberSaveable(hasDiscount) { mutableStateOf(hasDiscount) }
    val isCustomCapped = state.discountPreset == DiscountPreset.CUSTOM_ILS &&
        state.calculatedDiscountIls >= state.basketSubtotalIls &&
        state.basketSubtotalIls > 0

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
            // Subtotal Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.tr("المجموع الفرعي", "Subtotal"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = WesternFormatters.formatIls(state.basketSubtotalIls),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Discount Row with Edit / Toggle Trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Discount,
                        contentDescription = null,
                        tint = if (hasDiscount) financial.income else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = lang.tr("خصم إضافي", "Additional Discount"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasDiscount) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = financial.incomeContainer
                        ) {
                            Text(
                                text = "- ${WesternFormatters.formatIls(state.calculatedDiscountIls)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = financial.income,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        Text(
                            text = lang.tr("بدون خصم", "None"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("toggle_discount_section_button")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) lang.tr("طي", "Collapse") else lang.tr("توسيع", "Expand"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DiscountPreset.entries.forEach { preset ->
                            val selected = state.discountPreset == preset
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectDiscountPreset(preset) },
                                    label = {
                                        Text(
                                            text = lang.tr(preset.labelAr, preset.labelEn),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .testTag("discount_chip_${preset.name}")
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = state.discountPreset == DiscountPreset.CUSTOM_ILS) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = state.customDiscountText,
                                onValueChange = onUpdateCustomDiscount,
                                label = {
                                    Text(lang.tr("قيمة الخصم بالشيكل (₪)", "Discount Amount (ILS ₪)"))
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
                                        "تم تقييد الخصم بحد أقصى يساوي المجموع الفرعي (${WesternFormatters.formatIls(state.basketSubtotalIls)})",
                                        "Discount capped at subtotal (${WesternFormatters.formatIls(state.basketSubtotalIls)})"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = financial.warning
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Grand Net Total Due Row (Large display typography)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = lang.tr("الإجمالي المطلوب", "Net Total Due"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lang.tr("صافي الفاتورة للدفع", "Final payable amount"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = WesternFormatters.formatIls(state.basketNetTotalIls),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("invoice_net_total_text")
                )
            }
        }
    }
}

/**
 * Drafts & Quotation Bar + Collapsed Saved Drafts (ST-09 Section 5):
 * - Non-posting quotation generation and draft parking.
 */
@Composable
private fun DraftsAndQuotationBar(
    savedDrafts: List<SaleDraft>,
    lang: AppLanguage,
    onSaveDraft: (() -> Unit)?,
    onOpenQuotation: () -> Unit,
    onResumeDraft: ((String) -> Unit)?,
    onDeleteDraft: ((String) -> Unit)?
) {
    var isDraftsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onSaveDraft != null) {
                OutlinedButton(
                    onClick = onSaveDraft,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("save_draft_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = lang.tr("حفظ كمسودة", "Save Draft"),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenQuotation,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("open_quotation_preview_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lang.tr("عرض سعر", "Price Quotation"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }

        if (savedDrafts.isNotEmpty() && onResumeDraft != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isDraftsExpanded = !isDraftsExpanded }
                    .testTag("toggle_saved_drafts_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = lang.tr(
                                "المسودات المعلقة (${WesternFormatters.formatInt(savedDrafts.size)})",
                                "Held Drafts (${WesternFormatters.formatInt(savedDrafts.size)})"
                            ),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = if (isDraftsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isDraftsExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
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

/**
 * Saved Sale Drafts Card: Lists held transactions with resume and delete triggers.
 */
@Composable
private fun SavedSaleDraftsCard(
    savedDrafts: List<SaleDraft>,
    lang: AppLanguage,
    onResumeDraft: (String) -> Unit,
    onDeleteDraft: ((String) -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                text = "${WesternFormatters.formatInt(draft.totalQuantity)} ${lang.tr("وحدات", "units")} • ${WesternFormatters.formatIls(draft.netTotalIls)} • ${draft.timestamp}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = { onResumeDraft(draft.id) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
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
                                        .size(48.dp)
                                        .testTag("delete_draft_${draft.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = lang.tr("حذف المسودة", "Delete draft"),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(19.dp)
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
 * Non-Posting Quotation Preview Dialog:
 * Shows a plain-text quotation document with copy action and clear non-posting guarantee.
 */
@Composable
private fun QuotationPreviewDialog(
    state: TraderUiState,
    quotationText: String,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copiedFeedback by rememberSaveable { mutableStateOf(false) }
    val displayDoc = quotationText.ifBlank {
        buildString {
            appendLine(lang.tr("عرض سعر مرجعي (غير مرحّل)", "Price Quotation (Non-Posting)"))
            appendLine("${lang.tr("الصافي التقديري:", "Estimated Net Total:")} ${WesternFormatters.formatIls(state.basketNetTotalIls)}")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("معاينة عرض سعر للزبون", "Customer Price Quotation Preview"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TraderTheme.financial.warningContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, TraderTheme.financial.warning.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = TraderTheme.financial.warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = lang.tr(
                                "وثيقة تسعير فقط: لا تخصم من المخزون ولا تسجل نقداً أو ذمة على الزبون حتى يتم اعتماد الفاتورة.",
                                "Non-posting quotation: Does not deduct inventory or record cash/debt until confirmed as a sale."
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                    clipboard.setText(AnnotatedString(displayDoc))
                    copiedFeedback = true
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("copy_quotation_button")
            ) {
                Text(
                    text = lang.tr("نسخ عرض السعر", "Copy Quotation"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إغلاق", "Close"))
            }
        }
    )
}

/**
 * Sticky Bottom Checkout Dock (ST-09 Section 6):
 * - Displays net total due and primary 'متابعة للدفع' action.
 * - Relies on parent Shell Scaffold for window insets (zero duplicate insets).
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
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
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
                        modifier = Modifier.size(15.dp)
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("proceed_to_cash_payment_button")
                ) {
                    Text(
                        text = lang.tr("متابعة للدفع", "Proceed to Payment"),
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

/**
 * CustomerPickerModalSheet:
 * Searchable customer picker sheet adhering to Stitch styling with min 48dp hit targets.
 * Shared across screens via internal visibility (preserved API contract).
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
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
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
                shape = RoundedCornerShape(10.dp),
                placeholder = {
                    Text(
                        text = lang.tr(
                            "ابحث عن اسم الزبون أو رقم الجوال...",
                            "Search customer name or phone..."
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
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(48.dp)
                        ) {
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

            // Direct Walk-In Option
            Surface(
                onClick = { onSelectCustomer(null) },
                shape = RoundedCornerShape(10.dp),
                color = if (selectedCustomerId == null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                border = BorderStroke(
                    1.dp,
                    if (selectedCustomerId == null) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("picker_walk_in_option")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOutline,
                            contentDescription = null,
                            tint = if (selectedCustomerId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = lang.tr("زبون نقدي عام (بدون تسجيل حساب)", "Walk-in Cash Customer (No account)"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedCustomerId == null) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (selectedCustomerId == null) {
                        StitchBadge(
                            text = lang.tr("الحالي", "Current"),
                            tone = StitchBadgeTone.PRIMARY
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Searchable Registered Customers List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredCustomers, key = { it.id }) { cust ->
                    val isCustSelected = cust.id == selectedCustomerId
                    val projected = cust.balanceIls + basketNetTotalIls
                    val wouldExceed = projected > cust.creditLimitIls

                    Surface(
                        onClick = { onSelectCustomer(cust) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCustSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isCustSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("customer_option_${cust.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cust.name(lang),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isCustSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${cust.phone} • ${cust.address(lang)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = WesternFormatters.formatIls(cust.balanceIls),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cust.balanceIls > 0) financial.debit else financial.income
                                )
                                if (wouldExceed) {
                                    Text(
                                        text = lang.tr("سيتجاوز الحد", "Exceeds limit"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = financial.debit,
                                        fontWeight = FontWeight.Bold
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
