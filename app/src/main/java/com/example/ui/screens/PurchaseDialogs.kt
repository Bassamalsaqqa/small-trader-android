package com.example.ui.screens

import com.example.data.mobile.contract.toNullableString

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import com.example.data.ProductSearch
import com.example.model.ProductCategory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import com.example.model.PaymentAllocationInput
import com.example.model.SettlementTarget
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.Product
import com.example.model.PurchaseBill
import com.example.model.PurchaseLineItem
import com.example.model.StockMovement
import com.example.model.Supplier
import com.example.model.SupplierPayment
import com.example.model.WesternFormatters
import com.example.ui.components.StitchBadge
import com.example.ui.components.StitchBadgeTone
import com.example.ui.components.StitchBidiUtils
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Cohesive Purchasing Dialogs and Document Presentation Components.
 *
 * Implements strict domain validation, multi-line structured purchasing,
 * idempotent GRN operation replay guards, and immediate typed Result error surfacing.
 */

@Composable
fun PaySupplierDialog(
    supplier: Supplier,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onPaySupplier: (supplier: Supplier, amount: Double, paymentId: String) -> Result<SupplierPayment>,
    onPaySupplierWithResult: ((supplier: Supplier, amount: Double, paymentId: String, onComplete: (Result<SupplierPayment>) -> Unit) -> Unit)? = null,
    onPaySupplierExactWithResult: ((supplier: Supplier, amount: BigDecimal, note: String, paymentId: String, allocations: List<PaymentAllocationInput>, onComplete: (Result<SupplierPayment>) -> Unit) -> Unit)? = null,
    settlementTargets: List<SettlementTarget> = emptyList(),
    isLoadingTargets: Boolean = false,
    targetsError: String? = null,
    targetsHasMore: Boolean = false,
    onLoadMoreTargets: (() -> Unit)? = null,
    onRetryTargets: (() -> Unit)? = null
) {
    var submissionId by rememberSaveable(supplier.id) { mutableStateOf(UUID.randomUUID().toString()) }
    val initialPayStr = if (supplier.balanceOwedIls > 0.005) {
        WesternFormatters.formatNumber(supplier.balanceOwedIls).removeSuffix(".00")
    } else {
        ""
    }
    var payAmountText by rememberSaveable(supplier.id) { mutableStateOf(initialPayStr) }
    var payNoteText by rememberSaveable(supplier.id) { mutableStateOf("") }
    var isSubmittingPay by rememberSaveable(supplier.id) { mutableStateOf(false) }
    var payInlineError by rememberSaveable(supplier.id) { mutableStateOf<String?>(null) }

    // Map of targetKey ("${targetType}:${targetId}") -> allocation amount text
    val selectedAllocations = remember { mutableStateMapOf<String, String>() }

    val totalAllocatedExact = remember(selectedAllocations.toMap()) {
        selectedAllocations.values.fold(BigDecimal.ZERO) { acc, s ->
            val bd = s.toBigDecimalOrNull() ?: BigDecimal.ZERO
            acc.add(bd)
        }.setScale(2, RoundingMode.HALF_UP)
    }

    val enteredAmountExact = remember(payAmountText) {
        payAmountText.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO
    }
    val enteredAmount = enteredAmountExact.toDouble()

    val projectedOwed = remember(supplier.balanceOwedIls, enteredAmount) {
        supplier.balanceOwedExact?.subtract(enteredAmountExact)
            ?.setScale(2, RoundingMode.HALF_UP)?.toDouble() ?: Double.NaN
    }

    val hasAllocations = selectedAllocations.isNotEmpty()
    val isOverAllocated = totalAllocatedExact > enteredAmountExact
    val unallocatedSurplus = if (enteredAmountExact > totalAllocatedExact) {
        enteredAmountExact.subtract(totalAllocatedExact).setScale(2, RoundingMode.HALF_UP)
    } else {
        BigDecimal.ZERO
    }

    LaunchedEffect(supplier.id) {
        if (settlementTargets.isEmpty() && !isLoadingTargets && targetsError == null) {
            onRetryTargets?.invoke()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmittingPay) {
                onDismiss()
            }
        },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = if (hasAllocations) {
                    lang.tr("صرف دفعة نقدية مخصصة للمورد", "Pay Supplier (Allocated Settlement)")
                } else {
                    lang.tr("صرف دفعة نقدية للمورد (دفعة مقدمة غير مخصصة)", "Pay Supplier (Unapplied Advance)")
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!hasAllocations) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier_unapplied_advance_notice")
                    ) {
                        Text(
                            text = lang.tr(
                                "سند صرف نقدي غير مخصص (دفعة مقدمة على الحساب). لا يقوم هذا السند بتسوية فواتير مشتريات أو ذمم محددة آلياً.",
                                "Unapplied advance disbursement on account. This transaction does not automatically settle specific purchase bills or debts."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOverAllocated) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (isOverAllocated) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().testTag("supplier_allocation_summary_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isOverAllocated) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isOverAllocated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = lang.tr(
                                        "إجمالي المخصص للفواتير والذمم: ${StitchBidiUtils.formatIls(totalAllocatedExact.toDouble())}",
                                        "Total allocated to targets: ${StitchBidiUtils.formatIls(totalAllocatedExact.toDouble())}"
                                    ),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isOverAllocated) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (unallocatedSurplus > BigDecimal.ZERO) {
                                Text(
                                    text = lang.tr(
                                        "المتبقي كدفعة مقدمة غير مخصصة: ${StitchBidiUtils.formatIls(unallocatedSurplus.toDouble())}",
                                        "Unallocated advance remainder: ${StitchBidiUtils.formatIls(unallocatedSurplus.toDouble())}"
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            if (isOverAllocated) {
                                Text(
                                    text = lang.tr(
                                        "خطأ: مجموع المخصصات يتجاوز المبلغ المصروف نقداً!",
                                        "Error: Total allocations exceed cash paid!"
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (payInlineError != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("supplier_pay_inline_error")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = payInlineError!!,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Current Payable Status Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = supplier.name(lang),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${lang.tr("الرصيد المستحق:", "Balance Due:")} ${StitchBidiUtils.formatIls(supplier.balanceOwedIls)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (supplier.balanceOwedIls > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income
                        )
                    }
                }

                // Open Settlement Targets Selection Section (displayed when targets exist, loading, error, or has more)
                val showSupplierTargetsSection = settlementTargets.isNotEmpty() || targetsHasMore || isLoadingTargets || targetsError != null
                if (showSupplierTargetsSection) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("supplier_settlement_targets_section")
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
                                    text = lang.tr("فواتير المشتريات والذمم المفتوحة (اختياري)", "Open Purchase Targets (Optional)"),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                if (hasAllocations) {
                                    TextButton(
                                        onClick = { selectedAllocations.clear() },
                                        modifier = Modifier.heightIn(min = 32.dp).testTag("clear_supplier_allocations_button")
                                    ) {
                                        Text(
                                            text = lang.tr("إلغاء التخصيص", "Clear"),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }

                            if (isLoadingTargets) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = lang.tr("جاري تحميل الذمم المفتوحة...", "Loading open targets..."),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (targetsError != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = targetsError,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { onRetryTargets?.invoke() },
                                        modifier = Modifier.heightIn(min = 32.dp)
                                    ) {
                                        Text(lang.tr("إعادة المحاولة", "Retry"), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            if (settlementTargets.isEmpty() && !isLoadingTargets && targetsError == null) {
                                if (targetsHasMore) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                        modifier = Modifier.fillMaxWidth().testTag("supplier_targets_continuation_banner")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = lang.tr(
                                                    "توجد سجلات إضافية في السجل التاريخي — اضغط لمتابعة فحص الذمم المفتوحة",
                                                    "Historical records scan continuing — tap Load More to continue searching open targets"
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            TextButton(
                                                onClick = { onLoadMoreTargets?.invoke() },
                                                modifier = Modifier.heightIn(min = 36.dp).testTag("load_more_supplier_targets_button")
                                            ) {
                                                Text(
                                                    lang.tr("تحميل المزيد من الذمم", "Load More Targets"),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = lang.tr(
                                            "لا توجد فواتير مشتريات أو ذمم افتتاحية مفتوحة — تسجل الدفعة كدفعة مقدمة على الحساب.",
                                            "No open purchase bills or opening trade targets found — payment will be recorded as unapplied advance."
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            settlementTargets.forEach { target ->
                                val key = "${target.targetType}:${target.targetId}"
                                val isChecked = selectedAllocations.containsKey(key)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth().testTag("target_card_$key")
                                ) {
                                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = { checked ->
                                                        if (checked) {
                                                            selectedAllocations[key] = target.remainingAmountExact.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                                            if (enteredAmountExact < target.remainingAmountExact) {
                                                                payAmountText = target.remainingAmountExact.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                                            }
                                                        } else {
                                                            selectedAllocations.remove(key)
                                                        }
                                                    },
                                                    modifier = Modifier.testTag("target_checkbox_${target.targetType}_${target.targetId}")
                                                )
                                                Column {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        if (target.targetType == "purchase") {
                                                            StitchBadge(
                                                                text = lang.tr("شراء #${target.targetNumber}", "Purchase #${target.targetNumber}"),
                                                                tone = StitchBadgeTone.PRIMARY
                                                            )
                                                        } else if (target.targetType == "opening_trade_item") {
                                                            StitchBadge(
                                                                text = lang.tr("رصيد افتتاحي #${target.targetNumber}", "Opening #${target.targetNumber}"),
                                                                tone = StitchBadgeTone.WARNING
                                                            )
                                                        } else {
                                                            StitchBadge(
                                                                text = "${target.targetType} #${target.targetNumber}",
                                                                tone = StitchBadgeTone.NEUTRAL
                                                            )
                                                        }
                                                        Text(
                                                            text = target.businessDate,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        text = lang.tr(
                                                            "المتبقي: ${StitchBidiUtils.formatIls(target.remainingAmountExact.toDouble())}",
                                                            "Remaining: ${StitchBidiUtils.formatIls(target.remainingAmountExact.toDouble())}"
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                                    )
                                                }
                                            }

                                            if (isChecked) {
                                                OutlinedTextField(
                                                    value = selectedAllocations[key] ?: "",
                                                    onValueChange = { newVal ->
                                                        val sanitized = WesternFormatters.sanitizeWesternDigits(newVal)
                                                        selectedAllocations[key] = sanitized
                                                    },
                                                    label = { Text(lang.tr("المخصص ₪", "Alloc ₪"), style = MaterialTheme.typography.labelSmall) },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    singleLine = true,
                                                    shape = RoundedCornerShape(8.dp),
                                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Ltr),
                                                    modifier = Modifier.width(115.dp).testTag("target_allocation_input_${target.targetType}_${target.targetId}")
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (targetsHasMore && settlementTargets.isNotEmpty()) {
                                TextButton(
                                    onClick = { onLoadMoreTargets?.invoke() },
                                    modifier = Modifier.fillMaxWidth().testTag("load_more_supplier_targets_button")
                                ) {
                                    Text(
                                        lang.tr("تحميل المزيد من الذمم المفتوحة", "Load More Open Targets"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = payAmountText,
                    onValueChange = {
                        payAmountText = WesternFormatters.sanitizeWesternDigits(it)
                        payInlineError = null
                    },
                    label = { Text(lang.tr("المبلغ المصروف نقداً (₪) *", "Cash Amount (₪) *")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_pay_amount_input")
                )

                // Quick Presets
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (totalAllocatedExact > BigDecimal.ZERO) {
                        item {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                FilterChip(
                                    selected = payAmountText == totalAllocatedExact.toPlainString(),
                                    onClick = { payAmountText = totalAllocatedExact.toPlainString() },
                                    label = {
                                        Text(
                                            text = lang.tr(
                                                "مجموع المخصص (${StitchBidiUtils.formatIls(totalAllocatedExact.toDouble())})",
                                                "Total Alloc (${StitchBidiUtils.formatIls(totalAllocatedExact.toDouble())})"
                                            ),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    modifier = Modifier.testTag("pay_supplier_preset_total_allocated")
                                )
                            }
                        }
                    }
                    if (supplier.balanceOwedIls > 0.005) {
                        val fullStr = WesternFormatters.formatNumber(supplier.balanceOwedIls).removeSuffix(".00")
                        item {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                                FilterChip(
                                    selected = payAmountText == fullStr,
                                    onClick = { payAmountText = fullStr },
                                    label = {
                                        Text(
                                            text = lang.tr(
                                                "كامل الدين (${StitchBidiUtils.formatIls(supplier.balanceOwedIls)})",
                                                "Full Debt (${StitchBidiUtils.formatIls(supplier.balanceOwedIls)})"
                                            ),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.testTag("pay_supplier_preset_full")
                                )
                            }
                        }
                    }
                    items(listOf(50, 100, 200)) { preset ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 48.dp)) {
                            FilterChip(
                                selected = payAmountText == preset.toString(),
                                onClick = { payAmountText = preset.toString() },
                                label = { Text("$preset ₪") },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                // Live Settlement Projection Card
                if (enteredAmount > 0.0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().testTag("supplier_payment_projected_balance_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = when {
                                    supplier.balanceOwedExact == null -> lang.tr("الرصيد غير متاح", "Balance unavailable")
                                    projectedOwed > 0.005 -> if (hasAllocations) {
                                        lang.tr(
                                            "الرصيد الدفتري المتبقي: ${StitchBidiUtils.formatIls(projectedOwed)}",
                                            "Remaining book balance: ${StitchBidiUtils.formatIls(projectedOwed)}"
                                        )
                                    } else {
                                        lang.tr(
                                            "الرصيد الدفتري المتبقي: ${StitchBidiUtils.formatIls(projectedOwed)} (دفعة غير مخصصة)",
                                            "Remaining book balance: ${StitchBidiUtils.formatIls(projectedOwed)} (Unapplied advance)"
                                        )
                                    }
                                    projectedOwed < -0.005 -> lang.tr(
                                        "دفعة مقدمة للمتجر (أمانة): ${StitchBidiUtils.formatIls(-projectedOwed)}",
                                        "Store unapplied advance credit: ${StitchBidiUtils.formatIls(-projectedOwed)}"
                                    )
                                    else -> if (hasAllocations) {
                                        lang.tr(
                                            "الرصيد بعد الصرف: رصيد خالص (0.00 ₪)",
                                            "Balance after payment: Settled (0.00 ₪)"
                                        )
                                    } else {
                                        lang.tr(
                                            "الرصيد بعد السند: دفعة مقدمة غير مخصصة (لا تسوي الفواتير آلياً)",
                                            "Balance after payment: Unapplied advance (does not settle bills directly)"
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = when {
                                    projectedOwed > 0.005 -> TraderTheme.financial.debit
                                    projectedOwed < -0.005 -> TraderTheme.financial.warning
                                    else -> TraderTheme.financial.income
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = payNoteText,
                    onValueChange = { payNoteText = it },
                    label = { Text(lang.tr("البيان / ملاحظة السند (اختياري)", "Note (Optional)")) },
                    placeholder = { Text(lang.tr("مثال: صرف دفعة نقدية على الحساب", "e.g. Cash payment on account")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_pay_note_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSubmittingPay && enteredAmountExact > BigDecimal.ZERO && !isOverAllocated) {
                        isSubmittingPay = true
                        payInlineError = null

                        val allocInputs = selectedAllocations.mapNotNull { (key, text) ->
                            val amt = text.toBigDecimalOrNull() ?: return@mapNotNull null
                            if (amt <= BigDecimal.ZERO) return@mapNotNull null
                            val parts = key.split(":", limit = 2)
                            if (parts.size == 2) {
                                PaymentAllocationInput(parts[0], parts[1], amt.setScale(2, RoundingMode.HALF_UP))
                            } else null
                        }

                        if (onPaySupplierExactWithResult != null) {
                            onPaySupplierExactWithResult(supplier, enteredAmountExact, payNoteText, submissionId, allocInputs) { result ->
                                isSubmittingPay = false
                                if (result.isSuccess) {
                                    onDismiss()
                                } else {
                                    val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                        "فشلت عملية صرف الدفعة للمورد، يُرجى المحاولة مرة أخرى",
                                        "Supplier payment failed, please retry"
                                    )
                                    val parts = rawErr.split(" | ", limit = 2)
                                    payInlineError = if (lang == AppLanguage.AR) {
                                        parts.firstOrNull()?.trim() ?: rawErr
                                    } else {
                                        parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                                    }
                                }
                            }
                        } else if (onPaySupplierWithResult != null) {
                            onPaySupplierWithResult(supplier, enteredAmount, submissionId) { result ->
                                isSubmittingPay = false
                                if (result.isSuccess) {
                                    onDismiss()
                                } else {
                                    val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                        "فشلت عملية صرف الدفعة للمورد، يُرجى المحاولة مرة أخرى",
                                        "Supplier payment failed, please retry"
                                    )
                                    val parts = rawErr.split(" | ", limit = 2)
                                    payInlineError = if (lang == AppLanguage.AR) {
                                        parts.firstOrNull()?.trim() ?: rawErr
                                    } else {
                                        parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                                    }
                                }
                            }
                        } else {
                            val result = onPaySupplier(supplier, enteredAmount, submissionId)
                            isSubmittingPay = false
                            if (result.isSuccess) {
                                onDismiss()
                            } else {
                                val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                    "فشلت عملية صرف الدفعة للمورد، يُرجى المحاولة مرة أخرى",
                                    "Supplier payment failed, please retry"
                                )
                                val parts = rawErr.split(" | ", limit = 2)
                                payInlineError = if (lang == AppLanguage.AR) {
                                    parts.firstOrNull()?.trim() ?: rawErr
                                } else {
                                    parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                                }
                            }
                        }
                    }
                },
                enabled = !isSubmittingPay && enteredAmountExact > BigDecimal.ZERO && !isOverAllocated,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_supplier_payment_button")
            ) {
                Text(
                    text = if (enteredAmountExact > BigDecimal.ZERO) {
                        if (hasAllocations) {
                            lang.tr(
                                "تأكيد صرف ${StitchBidiUtils.formatIls(enteredAmount)} (مخصص ${StitchBidiUtils.formatIls(totalAllocatedExact.toDouble())})",
                                "Confirm ${StitchBidiUtils.formatIls(enteredAmount)} (${StitchBidiUtils.formatIls(totalAllocatedExact.toDouble())} allocated)"
                            )
                        } else {
                            lang.tr(
                                "تأكيد صرف ${StitchBidiUtils.formatIls(enteredAmount)} للمورد",
                                "Confirm ${StitchBidiUtils.formatIls(enteredAmount)} Payment"
                            )
                        }
                    } else {
                        lang.tr("تأكيد الصرف", "Confirm Payment")
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isSubmittingPay) {
                        onDismiss()
                    }
                },
                enabled = !isSubmittingPay,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrnResult: (operationId: String, productId: String, receivedQty: Int, batchNote: String, lineId: String?) -> Result<StockMovement>,
    errorMessage: String? = null,
    onConfirmGrnWithResult: ((operationId: String, productId: String, receivedQty: Int, batchNote: String, lineId: String?, onComplete: (Result<StockMovement>) -> Unit) -> Unit)? = null
) {
    var submissionId by rememberSaveable(bill.id) { mutableStateOf(UUID.randomUUID().toString()) }

    // Multi-line support: select active line
    val lines = bill.effectiveLines
    var selectedLineId by rememberSaveable(bill.id) {
        mutableStateOf(lines.firstOrNull { (it.quantity - it.receivedQuantity) > 0 }?.id ?: lines.firstOrNull()?.id ?: "")
    }

    val selectedLine = remember(lines, selectedLineId) {
        lines.firstOrNull { it.id == selectedLineId } ?: lines.firstOrNull()
    }

    val expectedProduct = remember(products, selectedLine) {
        products.firstOrNull { it.id == selectedLine?.productId && it.isStockTracked }
    }

    val pendingQty = if (selectedLine != null) {
        (selectedLine.quantity - selectedLine.receivedQuantity).toInt().coerceAtLeast(0)
    } else {
        bill.pendingQuantity.toInt().coerceAtLeast(0)
    }

    val maxReceivableQty = pendingQty.coerceAtLeast(0)
    var receivingQtyText by rememberSaveable(bill.id, selectedLineId) { mutableStateOf(maxReceivableQty.toString()) }
    val receivingQtyDouble = receivingQtyText.toDoubleOrNull() ?: 0.0
    val receivingQty = receivingQtyDouble.toInt()
    val isQtyValid = receivingQtyDouble > 0.0 && receivingQtyDouble <= maxReceivableQty.toDouble()

    var batchNote by rememberSaveable(bill.id) {
        mutableStateOf("استلام مخزني مطابق للفاتورة ${WesternFormatters.formatBillCode(bill.billNumber)}")
    }
    var isSubmittingGrn by rememberSaveable(bill.id) { mutableStateOf(false) }
    var grnInlineError by rememberSaveable(bill.id) { mutableStateOf(errorMessage) }
    val canConfirm = !bill.isReceived && expectedProduct != null && maxReceivableQty > 0

    val previouslyReceived = selectedLine?.receivedQuantity ?: bill.receivedQuantity
    val remainingAfterThis = (maxReceivableQty - receivingQty).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = {
            if (!isSubmittingGrn) {
                onDismiss()
            }
        },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr(
                    "سند استلام بضاعة للمستودع • ${WesternFormatters.formatBillCode(bill.billNumber)}",
                    "Goods Receipt • ${WesternFormatters.formatBillCode(bill.billNumber)}"
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentError = grnInlineError ?: errorMessage
                if (!currentError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("goods_receipt_inline_error")
                    ) {
                        Text(
                            text = currentError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("goods_receipt_error")
                        )
                    }
                }

                // If multiple lines, show line selector chips
                if (lines.size > 1) {
                    Text(
                        text = lang.tr("اختر الصنف المراد استلامه:", "Select line to receive:"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(lines, key = { it.id }) { line ->
                            val linePending = (line.quantity - line.receivedQuantity).toInt().coerceAtLeast(0)
                            FilterChip(
                                selected = line.id == selectedLineId,
                                onClick = {
                                    selectedLineId = line.id
                                    val newMax = linePending.coerceAtLeast(0)
                                    receivingQtyText = newMax.toString()
                                },
                                label = {
                                    Text("${line.name(lang)} ($linePending)")
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("grn_line_chip_${line.id}")
                            )
                        }
                    }
                }

                if (expectedProduct != null) {
                    val unitStr = expectedProduct.unit(lang)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("grn_product_chip_${expectedProduct.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = expectedProduct.name(lang),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.tr("الكمية المطلوبة بالفاتورة:", "Ordered Qty:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${WesternFormatters.formatInt(selectedLine?.quantity ?: bill.expectedQuantity)} $unitStr",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            if (previouslyReceived > 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = lang.tr("المستلم سابقاً:", "Previously Received:"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${WesternFormatters.formatInt(previouslyReceived)} $unitStr",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = lang.tr("المتبقي بعد هذا الاستلام:", "Remaining After:"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${WesternFormatters.formatInt(remainingAfterThis)} $unitStr",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (remainingAfterThis == 0) MaterialTheme.colorScheme.primary else TraderTheme.financial.warning
                                    ),
                                    modifier = Modifier.testTag("grn_qty_label")
                                )
                            }
                        }
                    }

                    if (maxReceivableQty > 0) {
                        OutlinedTextField(
                            value = receivingQtyText,
                            onValueChange = {
                                receivingQtyText = WesternFormatters.sanitizeWesternDigits(it)
                                grnInlineError = null
                            },
                            label = { Text(lang.tr("الكمية المستلمة الآن", "Quantity Received Now")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            isError = !isQtyValid && receivingQtyText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grn_qty_input")
                        )

                        OutlinedTextField(
                            value = batchNote,
                            onValueChange = { batchNote = it },
                            label = { Text(lang.tr("ملاحظة الاستلام المخزني", "Receipt Note")) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grn_note_input")
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("grn_line_fully_received_banner")
                        ) {
                            Text(
                                text = lang.tr(
                                    "تم استلام هذا البند بالكامل مخزنياً (${WesternFormatters.formatInt(previouslyReceived)} من ${WesternFormatters.formatInt(selectedLine?.quantity ?: bill.expectedQuantity)}).",
                                    "This line has been fully received (${WesternFormatters.formatInt(previouslyReceived)} of ${WesternFormatters.formatInt(selectedLine?.quantity ?: bill.expectedQuantity)})."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = lang.tr(
                            "هذه الفاتورة لا تحتوي على صنف محدد قابل للتتبع المخزني.",
                            "This bill does not have a linked stock-tracked product."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val prod = expectedProduct
                    if (!isSubmittingGrn && canConfirm && prod != null) {
                        isSubmittingGrn = true
                        grnInlineError = null
                        val lineIdToConfirm = selectedLine?.id?.takeIf { it.isNotBlank() } ?: bill.lines.firstOrNull()?.id
                        if (onConfirmGrnWithResult != null) {
                            onConfirmGrnWithResult(submissionId, prod.id, receivingQty, batchNote, lineIdToConfirm) { result ->
                                isSubmittingGrn = false
                                if (result.isSuccess) {
                                    onDismiss()
                                } else {
                                    val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                        "فشل تأكيد الاستلام المخزني، يُرجى التحقق وإعادة المحاولة",
                                        "Goods receipt confirmation failed, please verify and retry"
                                    )
                                    val parts = rawErr.split(" | ", limit = 2)
                                    grnInlineError = if (lang == AppLanguage.AR) {
                                        parts.firstOrNull()?.trim() ?: rawErr
                                    } else {
                                        parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                                    }
                                }
                            }
                        } else {
                            val result = onConfirmGrnResult(submissionId, prod.id, receivingQty, batchNote, lineIdToConfirm)
                            isSubmittingGrn = false
                            if (result.isSuccess) {
                                onDismiss()
                            } else {
                                val rawErr = result.exceptionOrNull()?.message ?: lang.tr(
                                    "فشل تأكيد الاستلام المخزني، يُرجى التحقق وإعادة المحاولة",
                                    "Goods receipt confirmation failed, please verify and retry"
                                )
                                val parts = rawErr.split(" | ", limit = 2)
                                grnInlineError = if (lang == AppLanguage.AR) {
                                    parts.firstOrNull()?.trim() ?: rawErr
                                } else {
                                    parts.getOrNull(1)?.trim() ?: parts.firstOrNull()?.trim() ?: rawErr
                                }
                            }
                        }
                    }
                },
                enabled = !isSubmittingGrn && canConfirm && isQtyValid,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_grn_submit_button")
            ) {
                Text(
                    text = lang.tr("تأكيد الاستلام المخزني", "Confirm Goods Receipt"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isSubmittingGrn) {
                        onDismiss()
                    }
                },
                enabled = !isSubmittingGrn,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrnResult: (operationId: String, productId: String, receivedQty: Int, batchNote: String) -> Result<StockMovement>,
    errorMessage: String? = null
) {
    PhysicalGoodsReceiptDialog(
        bill = bill,
        products = products,
        lang = lang,
        onDismiss = onDismiss,
        onConfirmGrnResult = { operationId, productId, receivedQty, batchNote, _ ->
            onConfirmGrnResult(operationId, productId, receivedQty, batchNote)
        },
        errorMessage = errorMessage
    )
}

@Composable
fun PhysicalGoodsReceiptDialog(
    bill: PurchaseBill,
    products: List<Product>,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirmGrn: (productId: String, receivedQty: Int, batchNote: String) -> Boolean,
    errorMessage: String? = null
) {
    PhysicalGoodsReceiptDialog(
        bill = bill,
        products = products,
        lang = lang,
        onDismiss = onDismiss,
        onConfirmGrnResult = { _, productId, receivedQty, batchNote ->
            val ok = onConfirmGrn(productId, receivedQty, batchNote)
            if (ok) {
                Result.success(
                    StockMovement(
                        id = "sm_legacy",
                        productId = productId,
                        date = "today",
                        delta = receivedQty,
                        reasonAr = batchNote,
                        reasonEn = batchNote,
                        resultingStock = 0
                    )
                )
            } else {
                Result.failure(Exception(errorMessage ?: "Goods receipt failed"))
            }
        },
        errorMessage = errorMessage
    )
}

/**
 * Data class representing a temporary line entered in the purchase dialog.
 */
data class EnteredPurchaseLine(
    val product: Product,
    var quantityText: String = "1",
    var unitCostText: String = ""
) {
    val quantity: Int get() = quantityText.toIntOrNull() ?: 0
    val unitCost: Double get() = unitCostText.toDoubleOrNull() ?: 0.0
    val lineTotal: BigDecimal get() = if (quantity > 0 && unitCost >= 0.0) {
        BigDecimal(unitCost.toString()).multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)
    } else BigDecimal.ZERO
}

enum class PurchaseBillMode {
    STRUCTURED,
    FINANCIAL_ONLY
}

val PurchaseLineListSaver: Saver<SnapshotStateList<PurchaseLineItem>, Any> = listSaver(
    save = { stateList ->
        stateList.map { line ->
            listOf(
                line.productId,
                line.productNameAr,
                line.productNameEn,
                line.quantity,
                line.unitCostIls,
                line.receivedQuantity,
                line.id
            )
        }
    },
    restore = { rawList ->
        val restored = mutableStateListOf<PurchaseLineItem>()
        @Suppress("UNCHECKED_CAST")
        (rawList as List<List<Any>>).forEach { items ->
            restored.add(
                PurchaseLineItem(
                    productId = items[0] as String,
                    productNameAr = items[1] as String,
                    productNameEn = items[2] as String,
                    quantity = (items[3] as Number).toInt(),
                    unitCostIls = (items[4] as Number).toDouble(),
                    receivedQuantity = (items[5] as Number).toInt(),
                    id = items[6] as String
                )
            )
        }
        restored
    }
)

@Composable
fun ReceivePurchaseBillDialog(
    state: TraderUiState,
    onDismiss: () -> Unit,
    onConfirmBill: ((supplierId: String, itemsSummary: String, totalIls: Double, paidNowIls: Double, expectedProductId: String?, expectedQuantity: Int) -> Boolean)? = null,
    onConfirmStructuredBill: ((supplierId: String, lines: List<PurchaseLineItem>, paidNowIls: Double, note: String) -> Result<PurchaseBill>)? = null,
    onConfirmBillWithResult: ((supplierId: String, itemsSummary: String, totalIls: Double, paidNowIls: Double, expectedProductId: String?, expectedQuantity: Int, onComplete: (Boolean) -> Unit) -> Unit)? = null,
    onConfirmStructuredBillWithResult: ((supplierId: String, lines: List<PurchaseLineItem>, paidNowIls: Double, note: String, onComplete: (Result<PurchaseBill>) -> Unit) -> Unit)? = null,
    onSearchSuppliers: ((String) -> Unit)? = null
) {
    val lang = state.language
    val suppliers = state.suppliers

    var billMode by rememberSaveable { mutableStateOf(PurchaseBillMode.STRUCTURED) }
    var submissionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var selectedSupplierId by rememberSaveable(state.selectedSupplierForDetail?.id) {
        mutableStateOf(state.selectedSupplierForDetail?.id ?: suppliers.firstOrNull()?.id ?: state.supplierSearchResults?.firstOrNull()?.id ?: "")
    }
    var purchaseDesc by rememberSaveable { mutableStateOf("") }
    var freeformTotal by rememberSaveable { mutableStateOf("") }
    var purchasePaid by rememberSaveable { mutableStateOf("0") }
    var linkedProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkedQuantityText by rememberSaveable { mutableStateOf("") }
    var unitCostText by rememberSaveable { mutableStateOf("") }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var inlineError by rememberSaveable { mutableStateOf<String?>(null) }

    val trackedProducts = remember(state.products) { state.products.filter { it.isStockTracked } }
    var productSearchQuery by rememberSaveable { mutableStateOf("") }
    val filteredTrackedProducts = remember(trackedProducts, productSearchQuery) {
        ProductSearch.search(
            products = trackedProducts,
            query = productSearchQuery,
            category = ProductCategory.ALL,
            limit = 30,
            stockTrackedOnly = true
        )
    }

    val structuredLines = rememberSaveable(saver = PurchaseLineListSaver) { mutableStateListOf<PurchaseLineItem>() }

    val derivedTotal: BigDecimal = structuredLines.fold(BigDecimal.ZERO) { acc, l ->
        acc.add(BigDecimal(l.unitCostIls.toString()).multiply(BigDecimal(l.quantity.toString())))
    }.setScale(2, RoundingMode.HALF_UP)

    val totalVal: Double = if (billMode == PurchaseBillMode.STRUCTURED) {
        derivedTotal.toDouble()
    } else {
        freeformTotal.toDoubleOrNull() ?: 0.0
    }

    val paidVal = if (purchasePaid.isBlank()) 0.0 else (purchasePaid.toDoubleOrNull() ?: -1.0)
    val isPaidValid = paidVal >= 0.0 && (totalVal == 0.0 || paidVal <= totalVal + 0.001)

    val effectiveDesc = if (billMode == PurchaseBillMode.STRUCTURED) {
        purchaseDesc.ifBlank {
            structuredLines.joinToString(" • ") { "${it.quantity}x ${it.name(lang)}" }
        }
    } else {
        purchaseDesc.trim()
    }

    val isFormValid = selectedSupplierId.isNotBlank() && (if (billMode == PurchaseBillMode.STRUCTURED) {
        structuredLines.isNotEmpty() && totalVal > 0.0 && isPaidValid && effectiveDesc.isNotBlank()
    } else {
        effectiveDesc.isNotBlank() && totalVal > 0.0 && isPaidValid
    })

    val payableAdded = if (totalVal > 0.0 && isPaidValid) {
        BigDecimal(totalVal.toString())
            .subtract(BigDecimal(paidVal.toString()))
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
            .coerceAtLeast(0.0)
    } else 0.0

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        shape = RoundedCornerShape(12.dp),
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = lang.tr("فاتورة مشتريات وتوريد", "Purchase & Inward Bill"),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                if (inlineError != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { }
                            .testTag("purchase_bill_inline_error")
                    ) {
                        Text(
                            text = inlineError!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // Bill Mode Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = billMode == PurchaseBillMode.STRUCTURED,
                        enabled = !isSubmitting,
                        onClick = {
                            billMode = PurchaseBillMode.STRUCTURED
                            inlineError = null
                        },
                        label = { Text(lang.tr("فاتورة أصناف مخزنية", "Itemized Stock Bill"), style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("purchase_bill_mode_structured")
                    )
                    FilterChip(
                        selected = billMode == PurchaseBillMode.FINANCIAL_ONLY,
                        enabled = !isSubmitting,
                        onClick = {
                            billMode = PurchaseBillMode.FINANCIAL_ONLY
                            inlineError = null
                        },
                        label = { Text(lang.tr("قيد مالي فقط", "Financial-Only Bill"), style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("purchase_bill_mode_financial")
                    )
                }

                // Supplier Picker with Search
                var supplierPickerSearch by rememberSaveable { mutableStateOf("") }
                val displayedSuppliers = remember(suppliers, state.supplierSearchResults, state.supplierSearchQuery, supplierPickerSearch) {
                    if (supplierPickerSearch.isNotBlank()) {
                        if (onSearchSuppliers != null) {
                            if (state.supplierSearchQuery.trim().equals(supplierPickerSearch.trim(), ignoreCase = true)) {
                                state.supplierSearchResults ?: emptyList()
                            } else {
                                emptyList()
                            }
                        } else {
                            val q = supplierPickerSearch.trim().lowercase()
                            suppliers.filter {
                                it.name(lang).lowercase().contains(q) ||
                                    it.company(lang).lowercase().contains(q) ||
                                    (it.code ?: "").lowercase().contains(q) ||
                                    (it.phone ?: "").contains(q)
                            }
                        }
                    } else suppliers
                }

                val allKnownSuppliers = remember(suppliers, state.supplierSearchResults) {
                    (suppliers + (state.supplierSearchResults ?: emptyList())).distinctBy { it.id }
                }
                val finalChips = remember(displayedSuppliers, selectedSupplierId, allKnownSuppliers) {
                    if (selectedSupplierId.isNotBlank() && displayedSuppliers.none { it.id == selectedSupplierId }) {
                        val selectedSup = allKnownSuppliers.find { it.id == selectedSupplierId }
                        if (selectedSup != null) listOf(selectedSup) + displayedSuppliers else displayedSuppliers
                    } else {
                        displayedSuppliers
                    }
                }

                if (onSearchSuppliers != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = lang.tr("المورد:", "Supplier:"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = supplierPickerSearch,
                            onValueChange = {
                                supplierPickerSearch = it
                                onSearchSuppliers.invoke(it)
                            },
                            placeholder = { Text(lang.tr("بحث في الموردين بالاسم أو الهاتف...", "Search suppliers by name or phone..."), style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("purchase_supplier_search_input")
                        )

                        val isDebouncingOrSearching = state.isSearchingSuppliers || (supplierPickerSearch.isNotBlank() && !state.supplierSearchQuery.trim().equals(supplierPickerSearch.trim(), ignoreCase = true))
                        if (isDebouncingOrSearching) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(lang.tr("جاري البحث في الموردين...", "Searching suppliers..."), style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (state.supplierSearchError != null && supplierPickerSearch.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.supplierSearchError ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { onSearchSuppliers.invoke(supplierPickerSearch) },
                                    modifier = Modifier.heightIn(min = 28.dp)
                                ) {
                                    Text(lang.tr("إعادة المحاولة", "Retry"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else if (supplierPickerSearch.isNotBlank() && displayedSuppliers.isEmpty()) {
                            Text(
                                text = lang.tr("لا توجد نتائج بحث للموردين", "No matching suppliers found"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else if (suppliers.isEmpty() && displayedSuppliers.isEmpty()) {
                            Text(
                                text = lang.tr("دليل الموردين فارغ، استخدم البحث أعلاه لاختيار مورد", "Supplier directory is empty, use search above to select a supplier"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        if (finalChips.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(finalChips, key = { it.id }) { s ->
                                    FilterChip(
                                        selected = s.id == selectedSupplierId,
                                        enabled = !isSubmitting,
                                        onClick = { selectedSupplierId = s.id },
                                        label = { Text(s.name(lang), style = MaterialTheme.typography.labelSmall) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        modifier = Modifier.testTag("purchase_supplier_chip_${s.id}")
                                    )
                                }
                                if (state.suppliersHasMore && supplierPickerSearch.isBlank()) {
                                    item {
                                        Text(
                                            text = lang.tr("+ المزيد في الخادم", "+ More on server"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (finalChips.isEmpty()) {
                        Text(
                            text = lang.tr("لا يوجد موردين متاحين حالياً", "No suppliers available currently"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr("المورد:", "Supplier:"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(finalChips, key = { it.id }) { s ->
                                    FilterChip(
                                        selected = s.id == selectedSupplierId,
                                        enabled = !isSubmitting,
                                        onClick = { selectedSupplierId = s.id },
                                        label = { Text(s.name(lang), style = MaterialTheme.typography.labelSmall) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        modifier = Modifier.testTag("purchase_supplier_chip_${s.id}")
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = purchaseDesc,
                    onValueChange = {
                        purchaseDesc = it
                        inlineError = null
                    },
                    enabled = !isSubmitting,
                    placeholder = {
                        Text(
                            if (billMode == PurchaseBillMode.STRUCTURED) {
                                lang.tr("بيان الفاتورة (اختياري)", "Bill Summary (Optional)")
                            } else {
                                lang.tr("بيان الفاتورة / الأصناف *", "Bill Summary / Items *")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_summary_input")
                )

                if (billMode == PurchaseBillMode.STRUCTURED && trackedProducts.isNotEmpty()) {
                    // Primary search input placed strictly ABOVE candidate results
                    OutlinedTextField(
                        value = productSearchQuery,
                        onValueChange = { productSearchQuery = it },
                        enabled = !isSubmitting,
                        placeholder = {
                            Text(
                                lang.tr("ابحث بالاسم أو الباركود...", "Search item by name or barcode..."),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (productSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { productSearchQuery = "" },
                                    enabled = !isSubmitting,
                                    modifier = Modifier.size(48.dp).testTag("clear_purchase_product_search")
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = lang.tr("مسح", "Clear"), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("purchase_product_search_input")
                    )

                    // Candidates or zero-result feedback strictly below search input
                    if (filteredTrackedProducts.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr(
                                    "لا توجد أصناف مخزنية مطابقة لـ «$productSearchQuery»",
                                    "No inventory items matching «$productSearchQuery»"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { productSearchQuery = "" },
                                enabled = !isSubmitting,
                                modifier = Modifier.testTag("clear_purchase_search_no_results")
                            ) {
                                Text(lang.tr("مسح البحث", "Clear"))
                            }
                        }
                    } else {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredTrackedProducts, key = { it.id }) { p ->
                                FilterChip(
                                    selected = linkedProductId == p.id,
                                    enabled = !isSubmitting,
                                    onClick = {
                                        linkedProductId = p.id
                                        if (purchaseDesc.isBlank()) {
                                            purchaseDesc = "توريد ${p.name(lang)}"
                                        }
                                        if (unitCostText.isBlank()) {
                                            unitCostText = WesternFormatters.formatNumber(p.costIls).removeSuffix(".00")
                                        }
                                        if (linkedQuantityText.isBlank()) {
                                            linkedQuantityText = "1"
                                        }
                                    },
                                    label = { Text("${p.name(lang)} (${p.unit(lang)})", style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("chip_product_${p.id}")
                                )
                            }
                        }
                    }

                    if (linkedProductId != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = linkedQuantityText,
                                onValueChange = {
                                    linkedQuantityText = WesternFormatters.sanitizeWesternDigits(it)
                                    inlineError = null
                                },
                                enabled = !isSubmitting,
                                label = { Text(lang.tr("الكمية", "Quantity")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("purchase_bill_linked_qty_input")
                            )
                            OutlinedTextField(
                                value = unitCostText,
                                onValueChange = {
                                    unitCostText = WesternFormatters.sanitizeWesternDigits(it)
                                    inlineError = null
                                },
                                enabled = !isSubmitting,
                                label = { Text(lang.tr("تكلفة الوحدة (₪)", "Unit Cost (₪)")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("purchase_bill_unit_cost_input")
                            )
                        }

                        val candidateProd = trackedProducts.firstOrNull { it.id == linkedProductId }
                        val candidateQty = linkedQuantityText.toIntOrNull() ?: 0
                        val candidateCostDouble = unitCostText.toDoubleOrNull()
                        val isCostEntered = unitCostText.isNotBlank() && candidateCostDouble != null && candidateCostDouble >= 0.0 && !candidateCostDouble.isNaN() && !candidateCostDouble.isInfinite()
                        val canAddCandidate = candidateProd != null && candidateQty > 0 && isCostEntered

                        OutlinedButton(
                            onClick = {
                                val p = candidateProd ?: return@OutlinedButton
                                val q = candidateQty
                                val c = candidateCostDouble ?: return@OutlinedButton
                                if (q > 0 && c >= 0.0) {
                                    // Preserve exact priced lines with unique line identity
                                    structuredLines.add(
                                        PurchaseLineItem(
                                            productId = p.id,
                                            productNameAr = p.nameAr,
                                            productNameEn = p.nameEn,
                                            quantity = q,
                                            unitCostIls = c,
                                            receivedQuantity = 0,
                                            id = UUID.randomUUID().toString()
                                        )
                                    )
                                    if (purchaseDesc.isBlank()) {
                                        purchaseDesc = structuredLines.joinToString(" • ") { "${it.quantity}x ${it.name(lang)}" }
                                    }
                                    linkedProductId = null
                                    linkedQuantityText = ""
                                    unitCostText = ""
                                    inlineError = null
                                }
                            },
                            enabled = !isSubmitting && canAddCandidate,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_purchase_bill_line_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(lang.tr("إضافة هذا الصنف للفاتورة", "Add This Item to Bill"))
                        }
                    }

                    if (structuredLines.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("structured_purchase_lines_container")
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = lang.tr("الأصناف المسجلة في الفاتورة (${structuredLines.size}):", "Recorded Bill Items (${structuredLines.size}):"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                structuredLines.forEachIndexed { idx, line ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().testTag("structured_purchase_line_${line.id}"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                line.name(lang),
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Text(
                                                "${line.quantity} × ${(line.unitCostExact?.toPlainString() ?: "—")} = ${(line.totalCostExact?.toPlainString() ?: "—")}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                structuredLines.removeAt(idx)
                                            },
                                            enabled = !isSubmitting,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("btn_remove_line_${line.productId}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = lang.tr("إزالة الصنف", "Remove item"),
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

                // Authoritative Total Field
                OutlinedTextField(
                    value = if (billMode == PurchaseBillMode.STRUCTURED) {
                        if (structuredLines.isNotEmpty()) derivedTotal.toPlainString() else ""
                    } else {
                        freeformTotal
                    },
                    onValueChange = {
                        if (billMode == PurchaseBillMode.FINANCIAL_ONLY) {
                            freeformTotal = WesternFormatters.sanitizeWesternDigits(it)
                            inlineError = null
                        }
                    },
                    readOnly = (billMode == PurchaseBillMode.STRUCTURED),
                    enabled = !isSubmitting,
                    label = {
                        Text(
                            if (billMode == PurchaseBillMode.STRUCTURED) {
                                lang.tr("إجمالي الفاتورة (₪) [محسوب تلقائياً من الأصناف]", "Bill Total (₪) [Calculated from Lines]")
                            } else {
                                lang.tr("إجمالي الفاتورة (₪) *", "Bill Total (₪) *")
                            }
                        )
                    },
                    supportingText = if (billMode == PurchaseBillMode.STRUCTURED) {
                        {
                            Text(
                                lang.tr(
                                    "القيمة محسوبة ومطابقة لبنود الأصناف لمنع أي تباين مالي",
                                    "Authoritative total derived strictly from line items"
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_total_input")
                )

                OutlinedTextField(
                    value = purchasePaid,
                    onValueChange = {
                        purchasePaid = WesternFormatters.sanitizeWesternDigits(it)
                        inlineError = null
                    },
                    enabled = !isSubmitting,
                    label = { Text(lang.tr("المدفوع نقداً الآن من الصندوق (₪)", "Paid Cash Now (₪)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_bill_paid_input")
                )

                if (totalVal > 0.0 && isPaidValid) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr(
                                "ذمة المورد: ${StitchBidiUtils.formatIls(payableAdded)} • مدفوع: ${StitchBidiUtils.formatIls(paidVal)}",
                                "Payable: ${StitchBidiUtils.formatIls(payableAdded)} • Paid: ${StitchBidiUtils.formatIls(paidVal)}"
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (payableAdded > 0.005) TraderTheme.financial.debit else TraderTheme.financial.income,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                // Prototype demo boundary notice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = lang.tr(
                            "يُسجّل استلام البضاعة وتأكيد الكميات بشكل منفصل.",
                            "Goods receipt and quantity confirmation are recorded separately."
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSubmitting && isFormValid) {
                        isSubmitting = true
                        inlineError = null

                        if (billMode == PurchaseBillMode.STRUCTURED) {
                            if (structuredLines.isEmpty()) {
                                isSubmitting = false
                                inlineError = lang.tr("يُرجى إضافة صنف واحد على الأقل", "Please add at least one line item")
                                return@Button
                            }
                            if (onConfirmStructuredBillWithResult != null) {
                                onConfirmStructuredBillWithResult(selectedSupplierId, structuredLines.toList(), paidVal, effectiveDesc) { res ->
                                    isSubmitting = false
                                    if (res.isSuccess) {
                                        onDismiss()
                                    } else {
                                        inlineError = res.exceptionOrNull()?.message ?: lang.tr("فشل قيد الفاتورة", "Failed to record bill")
                                    }
                                }
                            } else if (onConfirmStructuredBill != null) {
                                val res = onConfirmStructuredBill(selectedSupplierId, structuredLines.toList(), paidVal, effectiveDesc)
                                isSubmitting = false
                                if (res.isSuccess) {
                                    onDismiss()
                                } else {
                                    inlineError = res.exceptionOrNull()?.message ?: lang.tr("فشل قيد الفاتورة", "Failed to record bill")
                                }
                            } else {
                                isSubmitting = false
                                inlineError = "Structured bill handler not provided"
                            }
                        } else {
                            if (onConfirmBillWithResult != null) {
                                onConfirmBillWithResult(
                                    selectedSupplierId,
                                    effectiveDesc,
                                    totalVal,
                                    paidVal,
                                    null,
                                    0
                                ) { success ->
                                    isSubmitting = false
                                    if (success) {
                                        onDismiss()
                                    } else {
                                        inlineError = state.supplierActionMessage(lang).ifBlank {
                                            lang.tr("فشل قيد الفاتورة، يُرجى المحاولة مرة أخرى", "Failed to record bill, please retry")
                                        }
                                    }
                                }
                            } else if (onConfirmBill != null) {
                                val success = onConfirmBill(
                                    selectedSupplierId,
                                    effectiveDesc,
                                    totalVal,
                                    paidVal,
                                    null,
                                    0
                                )
                                isSubmitting = false
                                if (success) {
                                    onDismiss()
                                } else {
                                    inlineError = state.supplierActionMessage(lang).ifBlank {
                                        lang.tr("فشل قيد الفاتورة، يُرجى المحاولة مرة أخرى", "Failed to record bill, please retry")
                                    }
                                }
                            } else {
                                isSubmitting = false
                                inlineError = "Financial bill handler not provided"
                            }
                        }
                    }
                },
                enabled = !isSubmitting && isFormValid,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_purchase_bill_button")
            ) {
                Text(
                    text = lang.tr("قيد الفاتورة", "Record Bill"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!isSubmitting) onDismiss() },
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun SupplierEditorDialog(
    initialSupplier: Supplier?,
    companyEditable: Boolean = true,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onSave: (name: String, company: String, phone: String) -> Unit,
    onSaveWithResult: ((name: String, company: String, phone: String, onComplete: (Boolean) -> Unit) -> Unit)? = null,
    initialDraft: com.example.data.mobile.contract.StrictJsonObject? = null,
    isRecovering: Boolean = false
) {
    var name by rememberSaveable(initialSupplier?.id) {
        mutableStateOf(initialSupplier?.nameAr ?: initialDraft?.properties?.get("name_ar").toNullableString() ?: "")
    }
    var company by rememberSaveable(initialSupplier?.id) {
        mutableStateOf(initialSupplier?.companyAr ?: initialDraft?.properties?.get("company_name").toNullableString() ?: "")
    }
    var phone by rememberSaveable(initialSupplier?.id) {
        mutableStateOf(initialSupplier?.phone ?: initialDraft?.properties?.get("phone").toNullableString() ?: "")
    }
    val isValid = name.isNotBlank() || isRecovering

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = if (initialSupplier == null) lang.tr("إضافة مورد جديد", "Add Supplier") else lang.tr("تعديل بيانات المورد", "Edit Supplier"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRecovering) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = lang.tr("جاري استعادة العملية السابقة والتحقق من حالتها...", "Recovering in-flight operation from local store..."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !isRecovering,
                    label = { Text(lang.tr("اسم المورد *", "Supplier Name *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_name_input")
                )
                OutlinedTextField(
                    enabled = companyEditable && !isRecovering,
                    supportingText = { if (!companyEditable) Text(lang.tr("غير متاح (لا يُحفظ على الخادم)", "Unavailable (not saved on server)")) },
                    value = company,
                    onValueChange = { company = it },
                    label = { Text(lang.tr("اسم الشركة / التجارة", "Company / Business Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_company_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = WesternFormatters.sanitizeWesternDigits(it) },
                    enabled = !isRecovering,
                    label = { Text(lang.tr("رقم الهاتف", "Phone Number")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("supplier_phone_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        val trimmedName = name.trim()
                        val trimmedCompany = if (companyEditable) company.trim() else ""
                        val trimmedPhone = phone.trim()
                        if (onSaveWithResult != null) {
                            onSaveWithResult(trimmedName, trimmedCompany, trimmedPhone) { success ->
                                if (success) {
                                    onDismiss()
                                }
                            }
                        } else {
                            onSave(trimmedName, trimmedCompany, trimmedPhone)
                        }
                    }
                },
                enabled = isValid,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("save_supplier_button")
            ) {
                Text(lang.tr("حفظ المورد", "Save Supplier"))
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

@Composable
fun PurchaseReturnPreviewDialog(
    supplier: Supplier,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = lang.tr("سند مردودات مشتريات", "Purchase Return Note"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = lang.tr(
                        "مردودات المشتريات تخصم من ذمة المورد (${supplier.name(lang)}) وتعيد البضاعة من المخزون إلى المورد.",
                        "Purchase returns credit supplier balance (${supplier.name(lang)}) and remove inventory."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lang.tr(
                            "مردود المشتريات مرتبط بالفاتورة الأصلية ورقم التشغيلة المعتمد.",
                            "Purchase return is linked to original bill and verified batch number."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(lang.tr("إغلاق", "Close"))
            }
        }
    )
}

@Composable
fun PurchaseBillRow(
    bill: PurchaseBill,
    lang: AppLanguage,
    canReceiveStock: Boolean = false,
    onOpenReceiveGoodsDialog: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("purchase_bill_${bill.billNumber}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    Text(
                        text = StitchBidiUtils.formatBillCode(bill.billNumber),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    val hasExpectedSku = (!bill.expectedProductId.isNullOrBlank() && bill.expectedQuantity > 0) ||
                        bill.lines.any { it.quantity > 0 }
                    val totalExpected = if (bill.lines.isNotEmpty()) bill.lines.sumOf { l -> l.quantity } else bill.expectedQuantity
                    val totalReceived = if (bill.lines.isNotEmpty()) bill.lines.sumOf { l -> l.receivedQuantity } else bill.receivedQuantity

                    StitchBadge(
                        text = when {
                            bill.isReceived -> lang.tr("تم استلام البضاعة (GRN)", "Goods Received (GRN)")
                            bill.isPartiallyReceived -> lang.tr(
                                "استلام جزئي (${WesternFormatters.formatInt(totalReceived)}/${WesternFormatters.formatInt(totalExpected)})",
                                "Partial GRN (${WesternFormatters.formatInt(totalReceived)}/${WesternFormatters.formatInt(totalExpected)})"
                            )
                            hasExpectedSku -> lang.tr(
                                "بانتظار استلام (${WesternFormatters.formatInt(totalExpected - totalReceived)} وحدة)",
                                "Pending GRN (${WesternFormatters.formatInt(totalExpected - totalReceived)} units)"
                            )
                            else -> lang.tr("قيد مالي فقط", "Financial only")
                        },
                        tone = when {
                            bill.isReceived -> StitchBadgeTone.SUCCESS
                            bill.isPartiallyReceived -> StitchBadgeTone.WARNING
                            hasExpectedSku -> StitchBadgeTone.WARNING
                            else -> StitchBadgeTone.NEUTRAL
                        },
                        shape = RoundedCornerShape(6.dp)
                    )
                }

                Text(
                    text = bill.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = bill.itemsSummary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (bill.lines.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    bill.lines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "• ${line.name(lang)} (×${line.quantity})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(line.unitCostExact?.toPlainString() ?: "—")}/${lang.tr("وحدة", "unit")}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${lang.tr("القيمة:", "Total:")} ${StitchBidiUtils.formatCurrency(bill.totalExact, bill.currency, bill.currencyMinorUnits)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("دُفع نقداً:", "Paid Cash:")} ${StitchBidiUtils.formatCurrency(bill.paidExact, bill.currency, bill.currencyMinorUnits)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bill.paidExact > BigDecimal.ZERO) TraderTheme.financial.income else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${lang.tr("ذمة للمورد:", "Payable:")} ${StitchBidiUtils.formatCurrency(bill.remainingPayableExact, bill.currency, bill.currencyMinorUnits)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (bill.remainingPayableExact > BigDecimal.ZERO) TraderTheme.financial.debit else TraderTheme.financial.income
                )
            }

            val canConfirmExpectedSku = !bill.isReceived &&
                ((!bill.expectedProductId.isNullOrBlank() && bill.expectedQuantity > 0) || bill.lines.any { it.quantity > it.receivedQuantity }) &&
                canReceiveStock &&
                onOpenReceiveGoodsDialog != null

            if (canConfirmExpectedSku) {
                OutlinedButton(
                    onClick = onOpenReceiveGoodsDialog!!,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("open_grn_dialog_button_${bill.billNumber}")
                ) {
                    Text(
                        text = lang.tr(
                            "استلام بضاعة للمستودع (GRN)",
                            "Receive Goods to Stock (GRN)"
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun SupplierPaymentRow(
    payment: SupplierPayment,
    lang: AppLanguage
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("supplier_payment_${payment.id}")
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Payments,
                            contentDescription = null,
                            tint = TraderTheme.financial.income,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = payment.id,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = payment.note.ifBlank { lang.tr("سداد نقدي للمورد", "Cash Payment to Supplier") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = WesternFormatters.formatIls(payment.amountIls),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = TraderTheme.financial.income
                )
                Text(
                    text = payment.date,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
