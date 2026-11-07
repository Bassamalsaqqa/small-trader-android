package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.model.Customer
import com.example.model.WesternFormatters
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme
import com.example.viewmodel.TraderUiState
import kotlin.math.abs

enum class PaymentMode {
    CASH,
    CREDIT
}

/**
 * Native Material3 Cash Payment & Settlement Screen (Step 3 of Checkout).
 *
 * Implements Stage 3 Architecture:
 * 1. Dominant Net Due Amount isolated prominently at top.
 * 2. Segmented Cash / Credit Mode selection eliminating visual clutter.
 * 3. Cash Mode: Exact tender prefill, standard bill chips, change/shortfall status,
 *    and optional toggleable on-screen keypad to avoid soft keyboard collision.
 * 4. Credit Mode: Named customer requirement, 3-column debt projection, credit limit ceiling.
 * 5. Exactly ONE primary final confirmation button at bottom dock with submitting guards.
 * 6. Clean, concise merchant Arabic / English without explanatory clutter or AI filler.
 */
@Composable
fun CashPaymentScreen(
    state: TraderUiState,
    onSetExactAmount: () -> Unit,
    onSelectDenomination: (Int) -> Unit,
    onAddCashIncrement: (Int) -> Unit = {},
    onKeypadPress: (String) -> Unit = {},
    onUpdateCashText: (String) -> Unit = {},
    onAssignCustomer: (Customer?) -> Unit = {},
    onDismissCheckoutError: () -> Unit = {},
    onEditInvoice: () -> Unit = {},
    onCompleteSale: () -> Unit,
    onCompleteCreditSale: () -> Unit = {}
) {
    val lang = state.language
    val financial = TraderTheme.financial
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var isCustomerPickerOpen by rememberSaveable(state.saleSessionId) { mutableStateOf(false) }

    var paymentMode by rememberSaveable(state.saleSessionId) { mutableStateOf(PaymentMode.CASH) }
    var isKeypadVisible by rememberSaveable(state.saleSessionId) { mutableStateOf(false) }

    val selectedCustomer = state.selectedCustomer
    val sufficient = state.isCashSufficient
    val isExact = state.isExactCashTendered
    val errorMsg = state.checkoutErrorMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("cash_payment_screen")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Recoverable Checkout Error Banner
            if (errorMsg != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = financial.debitContainer,
                    border = BorderStroke(1.dp, financial.debit.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_error_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = financial.debit,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = financial.debit,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissCheckoutError,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = lang.tr("إغلاق التنبيه", "Dismiss error"),
                                tint = financial.debit,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Tactile Segmented Payment Mode Selector (Min 48dp touch targets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    onClick = {
                        paymentMode = PaymentMode.CASH
                        focusManager.clearFocus()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (paymentMode == PaymentMode.CASH) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Transparent
                    },
                    shadowElevation = if (paymentMode == PaymentMode.CASH) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics {
                            selected = paymentMode == PaymentMode.CASH
                            role = Role.Tab
                        }
                        .testTag("payment_mode_cash_tab")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payments,
                            contentDescription = null,
                            tint = if (paymentMode == PaymentMode.CASH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("نقداً", "Cash"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (paymentMode == PaymentMode.CASH) FontWeight.Bold else FontWeight.Medium,
                            color = if (paymentMode == PaymentMode.CASH) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    onClick = {
                        paymentMode = PaymentMode.CREDIT
                        focusManager.clearFocus()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = if (paymentMode == PaymentMode.CREDIT) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Transparent
                    },
                    shadowElevation = if (paymentMode == PaymentMode.CREDIT) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics {
                            selected = paymentMode == PaymentMode.CREDIT
                            role = Role.Tab
                        }
                        .testTag("payment_mode_credit_tab")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = if (paymentMode == PaymentMode.CREDIT) financial.debit else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = lang.tr("آجل", "Credit"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (paymentMode == PaymentMode.CREDIT) FontWeight.Bold else FontWeight.Medium,
                            color = if (paymentMode == PaymentMode.CREDIT) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // =========================================================================
            // 1. CASH MODE: Settlement Board, Tender Input, Denominations & Change
            // =========================================================================
            if (paymentMode == PaymentMode.CASH) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Dominant Amount Due & Quick Edit Invoice
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("المبلغ المطلوب للدفع", "Amount Due"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("payment_total_due_text")
                                )
                            }

                            TextButton(
                                onClick = onEditInvoice,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("payment_edit_invoice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = lang.tr("تعديل", "Edit"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = lang.tr("المقبوض نقداً", "Cash Received"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(state.cashTenderedIls),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("payment_cash_received_text")
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Amount Received Input Field
                        OutlinedTextField(
                            value = state.cashInputText,
                            onValueChange = { input ->
                                val cleaned = input.filter { it.isDigit() || it == '.' }
                                onUpdateCashText(cleaned)
                            },
                            singleLine = true,
                            label = {
                                Text(
                                    lang.tr("المبلغ المستلم من الزبون (شيكل)", "Cash Tendered (ILS)")
                                )
                            },
                            placeholder = {
                                Text(WesternFormatters.formatNumber(state.basketNetTotalIls))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Payments,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 6.dp)
                                ) {
                                    if (!isExact) {
                                        TextButton(
                                            onClick = {
                                                onSetExactAmount()
                                                focusManager.clearFocus()
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("cash_exact_button")
                                        ) {
                                            Text(
                                                text = lang.tr("بالضبط", "Exact"),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (state.cashInputText.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                onKeypadPress("C")
                                                onUpdateCashText("")
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("keypad_btn_C")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = lang.tr("مسح المبلغ", "Clear amount"),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cash_tender_input")
                        )

                        // Quick Shekel Bill Chips: 10, 20, 50, 100, 200
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bills = listOf(10, 20, 50, 100, 200)
                            bills.forEach { bill ->
                                val isSelectedBill = abs(state.cashTenderedIls - bill.toDouble()) < 0.005 && !isExact
                                val coversDue = bill.toDouble() + 0.005 >= state.basketNetTotalIls
                                Surface(
                                    onClick = {
                                        onSelectDenomination(bill)
                                        focusManager.clearFocus()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = when {
                                        isSelectedBill -> MaterialTheme.colorScheme.primaryContainer
                                        coversDue -> MaterialTheme.colorScheme.surface
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = when {
                                            isSelectedBill -> MaterialTheme.colorScheme.primary
                                            coversDue -> MaterialTheme.colorScheme.outlineVariant
                                            else -> Color.Transparent
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("cash_bill_$bill")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            text = "$bill",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (isSelectedBill || coversDue) FontWeight.Bold else FontWeight.Medium,
                                            color = when {
                                                isSelectedBill -> MaterialTheme.colorScheme.onPrimaryContainer
                                                coversDue -> MaterialTheme.colorScheme.onSurface
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Change Due or Shortfall Status Surface
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (sufficient) {
                                financial.incomeContainer.copy(alpha = 0.55f)
                            } else {
                                financial.warningContainer.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (sufficient) {
                                    financial.income.copy(alpha = 0.35f)
                                } else {
                                    financial.warning.copy(alpha = 0.35f)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (sufficient) {
                                                lang.tr("الباقي للزبون", "Change to Return")
                                            } else {
                                                lang.tr("المبلغ المتبقي", "Shortfall Due")
                                            },
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (sufficient) financial.income else financial.warning
                                        )
                                        Text(
                                            text = when {
                                                isExact -> lang.tr(
                                                    "المبلغ المستلم مطابق تماماً — لا يوجد باقي",
                                                    "Exact cash received — no change"
                                                )
                                                sufficient -> lang.tr(
                                                    "سلّم الزبون هذا الباقي نقداً",
                                                    "Hand this change to customer"
                                                )
                                                else -> lang.tr(
                                                    "استلم كامل المبلغ لإتمام البيع النقدي",
                                                    "Collect full cash to complete"
                                                )
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (sufficient) {
                                                financial.income.copy(alpha = 0.9f)
                                            } else {
                                                financial.warning.copy(alpha = 0.95f)
                                            }
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Text(
                                        text = if (sufficient) {
                                            WesternFormatters.formatIls(state.changeDueIls)
                                        } else {
                                            WesternFormatters.formatIls(state.remainingUnpaidIls)
                                        },
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (sufficient) financial.income else financial.warning,
                                        modifier = Modifier.testTag("payment_change_due_text")
                                    )
                                }

                                if (!sufficient && state.basket.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                onSetExactAmount()
                                                focusManager.clearFocus()
                                            },
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("recover_exact_cash_button")
                                        ) {
                                            Text(
                                                text = lang.tr(
                                                    "تعيين المبلغ الكامل (${WesternFormatters.formatIls(state.basketNetTotalIls)})",
                                                    "Set Exact Due (${WesternFormatters.formatIls(state.basketNetTotalIls)})"
                                                ),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Optional Keypad Toggle (avoids fighting soft keyboard simultaneously)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                onClick = {
                                    isKeypadVisible = !isKeypadVisible
                                    focusManager.clearFocus()
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("toggle_keypad_button")
                            ) {
                                Icon(
                                    imageVector = if (isKeypadVisible) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isKeypadVisible) {
                                        lang.tr("إخفاء لوحة الأرقام", "Hide Keypad")
                                    } else {
                                        lang.tr("لوحة أرقام الصندوق", "Show Numpad")
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = isKeypadVisible,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            CashierNumpadGrid(
                                lang = lang,
                                onKeypadPress = onKeypadPress,
                                onClearFocus = { focusManager.clearFocus() }
                            )
                        }
                    }
                }
            } else {
                // =========================================================================
                // 2. CREDIT MODE: Customer Assignment, Debt Projection & Credit Ceiling
                // =========================================================================
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (state.wouldCreditExceedCustomerLimit) {
                            financial.debit.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Dominant Amount Due & Notice
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lang.tr("المبلغ المطلوب تقييده ديناً", "Credit Amount Due"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.debit,
                                    modifier = Modifier.testTag("payment_total_due_text")
                                )
                            }

                            TextButton(
                                onClick = onEditInvoice,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("payment_edit_invoice_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = lang.tr("تعديل", "Edit"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        // Explicit zero cash disclosure
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lang.tr(
                                    "المبلغ المقبوض بالصندوق: 0.00 ₪ (تسجيل آجل بالكامل على الذمة)",
                                    "Cash Drawer Impact: 0.00 ₪ (100% posted to customer debit)"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Customer Selection & Account Status
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
                                    imageVector = if (selectedCustomer != null) {
                                        Icons.Default.Person
                                    } else {
                                        Icons.Default.PersonAddAlt1
                                    },
                                    contentDescription = null,
                                    tint = if (selectedCustomer != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = lang.tr("حساب الزبون", "Customer Account"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (selectedCustomer != null) {
                                            selectedCustomer.name(lang)
                                        } else {
                                            lang.tr("لم يُحدد زبون مسجل", "No customer linked")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selectedCustomer != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            financial.debit
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { isCustomerPickerOpen = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("payment_select_customer_button")
                            ) {
                                Icon(
                                    imageVector = if (selectedCustomer != null) {
                                        Icons.Default.SwapHoriz
                                    } else {
                                        Icons.Default.PersonAddAlt1
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (selectedCustomer != null) {
                                        lang.tr("تغيير الزبون", "Change")
                                    } else {
                                        lang.tr("اختيار زبون", "Select Customer")
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (selectedCustomer == null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = financial.debitContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, financial.debit.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = lang.tr(
                                        "البيع الآجل يتطلب تحديد حساب زبون مسجل في النظام. اضغط على 'اختيار زبون' للربط قبل المتابعة.",
                                        "Credit sales require a registered customer account. Tap 'Select Customer' to link an account."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = financial.debit,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        } else {
                            // 3-Column Debt Projection Ledger
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
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
                                            text = lang.tr("الرصيد السابق", "Prev Debt"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(selectedCustomer.balanceIls),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = lang.tr("قيمة الفاتورة", "Invoice +"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = financial.debit
                                        )
                                        Text(
                                            text = "+${WesternFormatters.formatIls(state.basketNetTotalIls)}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = financial.debit
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        Text(
                                            text = lang.tr("الرصيد بعد القيد", "Projected Total"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = WesternFormatters.formatIls(state.projectedCustomerBalanceIfCreditIls ?: 0.0),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (state.wouldCreditExceedCustomerLimit) {
                                                financial.debit
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }

                            // Credit Limit Status / Warning
                            val overLimit = state.wouldCreditExceedCustomerLimit
                            if (overLimit) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("payment_credit_limit_warning")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = financial.debit,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = lang.tr(
                                            "يتجاوز سقف الدين المسموح بمقدار ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}. استلم الفاتورة نقداً، أو غيّر الزبون.",
                                            "Exceeds credit ceiling by ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}. Collect cash or change customer."
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = financial.debit,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                Text(
                                    text = lang.tr(
                                        "ضمن سقف الدين المسموح به (${WesternFormatters.formatIls(selectedCustomer.creditLimitIls)}).",
                                        "Within allowed credit ceiling (${WesternFormatters.formatIls(selectedCustomer.creditLimitIls)})."
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. Authoritative Single Final Confirmation Dock (Consequence + Primary Action)
        // =========================================================================
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (paymentMode == PaymentMode.CASH) {
                    // Consequence line for cash sale
                    Text(
                        text = when {
                            !sufficient -> lang.tr(
                                "المتبقي: ${WesternFormatters.formatIls(state.remainingUnpaidIls)}",
                                "Shortfall: ${WesternFormatters.formatIls(state.remainingUnpaidIls)}"
                            )
                            state.changeDueIls > 0.005 -> lang.tr(
                                "المقبوض: ${WesternFormatters.formatIls(state.cashTenderedIls)} • الباقي: ${WesternFormatters.formatIls(state.changeDueIls)}",
                                "Received: ${WesternFormatters.formatIls(state.cashTenderedIls)} • Change: ${WesternFormatters.formatIls(state.changeDueIls)}"
                            )
                            else -> lang.tr(
                                "المقبوض: ${WesternFormatters.formatIls(state.cashTenderedIls)} • بدون باقي",
                                "Received: ${WesternFormatters.formatIls(state.cashTenderedIls)} • Exact"
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sufficient) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            financial.warning
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Button(
                        onClick = onCompleteSale,
                        enabled = state.basket.isNotEmpty() && sufficient && !state.isSubmittingSale,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("complete_sale_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr("تأكيد البيع", "Confirm Sale"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else {
                    // Consequence line for credit sale
                    val overLimit = state.wouldCreditExceedCustomerLimit
                    Text(
                        text = when {
                            selectedCustomer == null -> lang.tr(
                                "يلزم اختيار زبون مسجل للبيع الآجل",
                                "Select a customer for credit sale"
                            )
                            overLimit -> lang.tr(
                                "يتجاوز سقف الدين بمقدار ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}",
                                "Exceeds credit limit by ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}"
                            )
                            else -> lang.tr(
                                "قيد على الحساب: ${WesternFormatters.formatIls(state.basketNetTotalIls)} • الصندوق: 0.00 ₪",
                                "Account Debit: ${WesternFormatters.formatIls(state.basketNetTotalIls)} • Cash: 0.00 ₪"
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selectedCustomer != null && !overLimit) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            financial.debit
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Button(
                        onClick = onCompleteCreditSale,
                        enabled = selectedCustomer != null && !overLimit && state.basket.isNotEmpty() && !state.isSubmittingSale,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCustomer != null && !overLimit) financial.debit else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedCustomer != null && !overLimit) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("complete_credit_sale_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                selectedCustomer == null -> lang.tr(
                                    "البيع الآجل غير متاح بدون زبون",
                                    "Credit Disabled: No Customer"
                                )
                                overLimit -> lang.tr(
                                    "متوقف لتجاوز سقف الدين",
                                    "Credit Blocked: Over Limit"
                                )
                                else -> lang.tr(
                                    "تأكيد البيع الآجل",
                                    "Confirm Credit Sale"
                                )
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }

    if (isCustomerPickerOpen) {
        CustomerPickerModalSheet(
            customers = state.customers,
            selectedCustomerId = state.selectedCustomerId,
            basketNetTotalIls = state.basketNetTotalIls,
            lang = lang,
            onDismiss = { isCustomerPickerOpen = false },
            onSelectCustomer = { customer ->
                onAssignCustomer(customer)
                isCustomerPickerOpen = false
            }
        )
    }
}

/** Numeric keys retain their familiar LTR positions in either app language. */
@Composable
private fun CashierNumpadGrid(
    lang: com.example.model.AppLanguage,
    onKeypadPress: (String) -> Unit,
    onClearFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "BACK")
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rows.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowKeys.forEach { key ->
                        Surface(
                            onClick = {
                                onClearFocus()
                                onKeypadPress(key)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = when (key) {
                                "BACK" -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("cash_key_$key")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (key == "BACK") {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                                        contentDescription = lang.tr("حذف خانة", "Backspace"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
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
