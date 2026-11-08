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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Customer
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
import kotlin.math.abs

enum class PaymentMode {
    CASH,
    CREDIT
}

/**
 * Native Material3 Cash Payment & Settlement Screen (Stage 3 Checkout).
 *
 * Grounded in Stitch Project 15641450350510420845 / ST-10 (Cash) & ST-11 (Credit):
 * 1. Dominant Net Due Amount isolated prominently at top (28sp bold, Western digits + ₪).
 * 2. Compact Segmented Cash / Credit Mode selection (48dp min touch targets).
 * 3. Cash Mode (ST-10):
 *    - Large numeric tender input with clear button (testTag: cash_tender_input, keypad_btn_C).
 *    - Prominent exact match preset (cash_exact_button) and standard bill chips (10, 20, 50, 100, 200 ₪).
 *    - One clear high-contrast Change / Shortfall panel with formula and status pill.
 *    - Optional collapsible on-screen keypad to avoid fighting soft keyboard.
 *    - Quick switch to credit sale for verified customers.
 * 4. Credit Mode (ST-11):
 *    - Named customer requirement with verified customer badge and quick change affordance.
 *    - 2-column ledger breakdown: previous debt, credit limit, and capacity utilization bar.
 *    - Projected post-transaction balance box (previous + current = total).
 *    - Clear over-limit warning and submission guard when exceeding credit ceiling.
 *    - Invariant accounting notice card (0.00 ₪ cash received, full debt debited).
 * 5. Single Authoritative Bottom Confirmation Dock:
 *    - Exactly ONE primary button (min 52dp height, disabled when invalid or submitting).
 *    - Concise consequence line and truthful local persistence notice.
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
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
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

            // 1. Dominant Total Due Card (ST-10 / ST-11 Canonical Hero)
            StitchCard(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (paymentMode == PaymentMode.CASH) {
                                lang.tr("المبلغ المطلوب سداده", "Amount Due")
                            } else {
                                lang.tr("المبلغ المطلوب تقييده ديناً", "Credit Amount Due")
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("payment_total_due_text")
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${WesternFormatters.formatInt(state.basket.size)} ${lang.tr("أصناف في السلة", "items in basket")}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }

                    // Quick invoice edit affordance
                    Surface(
                        onClick = onEditInvoice,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("payment_edit_invoice_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = lang.tr("تعديل", "Edit"),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 2. Compact Segmented Cash / Credit Mode Selector (Min 48dp touch targets)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Cash tab
                    Surface(
                        onClick = {
                            paymentMode = PaymentMode.CASH
                            focusManager.clearFocus()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (paymentMode == PaymentMode.CASH) MaterialTheme.colorScheme.surface else Color.Transparent,
                        border = if (paymentMode == PaymentMode.CASH) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        shadowElevation = if (paymentMode == PaymentMode.CASH) 1.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics {
                                selected = paymentMode == PaymentMode.CASH
                                role = Role.Tab
                            }
                            .testTag("payment_mode_cash_tab")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payments,
                                contentDescription = null,
                                tint = if (paymentMode == PaymentMode.CASH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
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

                    // Credit tab
                    Surface(
                        onClick = {
                            paymentMode = PaymentMode.CREDIT
                            focusManager.clearFocus()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (paymentMode == PaymentMode.CREDIT) MaterialTheme.colorScheme.surface else Color.Transparent,
                        border = if (paymentMode == PaymentMode.CREDIT) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        shadowElevation = if (paymentMode == PaymentMode.CREDIT) 1.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics {
                                selected = paymentMode == PaymentMode.CREDIT
                                role = Role.Tab
                            }
                            .testTag("payment_mode_credit_tab")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = if (paymentMode == PaymentMode.CREDIT) financial.debit else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = lang.tr("على الحساب (آجل)", "Credit Sale"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (paymentMode == PaymentMode.CREDIT) FontWeight.Bold else FontWeight.Medium,
                                color = if (paymentMode == PaymentMode.CREDIT) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 3. CASH MODE: Tender Entry, Banknote Chips, Change/Shortfall Panel & Keypad
            // =========================================================================
            if (paymentMode == PaymentMode.CASH) {
                StitchCard(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.tr("المبلغ المستلم نقداً", "Cash Received"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = WesternFormatters.formatIls(state.cashTenderedIls),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("payment_cash_received_text")
                            )
                        }

                        // Large Numeric Input Container (ST-10)
                        OutlinedTextField(
                            value = state.cashInputText,
                            onValueChange = { input ->
                                val cleaned = input.filter { it.isDigit() || it == '.' }
                                onUpdateCashText(cleaned)
                            },
                            singleLine = true,
                            label = {
                                Text(lang.tr("المبلغ المستلم بالشيكل", "Tender Amount (ILS)"))
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
                                if (state.cashInputText.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            onUpdateCashText("")
                                            focusManager.clearFocus()
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("keypad_btn_C")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                                            contentDescription = lang.tr("مسح القيمة", "Clear amount"),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
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
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = MonoFontFamily
                            ),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cash_tender_input")
                        )

                        // Quick Denominations Section (ST-10)
                        Text(
                            text = lang.tr("فئات نقدية سريعة:", "Quick Cash Presets:"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Exact Match Preset Button (Prefills exact net due)
                        Surface(
                            onClick = {
                                onSetExactAmount()
                                focusManager.clearFocus()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isExact) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(
                                1.dp,
                                if (isExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("cash_exact_button")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (isExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = lang.tr("المبلغ تماماً (بالضبط)", "Exact Match"),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = WesternFormatters.formatIls(state.basketNetTotalIls),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = MonoFontFamily
                                    ),
                                    color = if (isExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Standard Bill Chips (10, 20, 50, 100, 200 ₪)
                        val bills = listOf(10, 20, 50, 100, 200)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            bills.forEach { bill ->
                                val isSelectedBill = (state.cashTenderedIls - bill.toDouble()).let { abs(it) < 0.005 }
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
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
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
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "$bill",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = if (isSelectedBill || coversDue) FontWeight.Bold else FontWeight.Medium,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = when {
                                                isSelectedBill -> MaterialTheme.colorScheme.onPrimaryContainer
                                                coversDue -> MaterialTheme.colorScheme.onSurface
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            }
                                        )
                                        Text(
                                            text = "₪",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                            color = when {
                                                isSelectedBill -> MaterialTheme.colorScheme.primary
                                                coversDue -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. One Clear High-Contrast Change / Shortfall Panel (ST-10 Section 3)
                if (sufficient) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = financial.incomeContainer,
                        border = BorderStroke(1.dp, financial.income.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PriceCheck,
                                        contentDescription = null,
                                        tint = financial.income,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = lang.tr("الباقي للزبون (الفكة المستحقة)", "Change to Customer"),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = financial.income
                                    )
                                }

                                Text(
                                    text = WesternFormatters.formatIls(state.changeDueIls),
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.income,
                                    modifier = Modifier.testTag("payment_change_due_text")
                                )
                            }

                            HorizontalDivider(color = financial.income.copy(alpha = 0.2f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                StitchBadge(
                                    text = if (isExact) lang.tr("حساب متطابق", "Exact") else lang.tr("مكتمل", "Covered"),
                                    tone = StitchBadgeTone.SUCCESS
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = financial.warningContainer,
                        border = BorderStroke(1.dp, financial.warning.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = financial.warning,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = lang.tr("المبلغ المستلم غير كافٍ (نقص نقدي)", "Cash Shortfall"),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = financial.warning
                                    )
                                }

                                Text(
                                    text = WesternFormatters.formatIls(state.remainingUnpaidIls),
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = financial.warning,
                                    modifier = Modifier.testTag("payment_change_due_text")
                                )
                            }

                            HorizontalDivider(color = financial.warning.copy(alpha = 0.2f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.tr(
                                        "استلم كامل المبلغ (${WesternFormatters.formatIls(state.basketNetTotalIls)}) لإتمام البيع النقدي",
                                        "Collect full amount (${WesternFormatters.formatIls(state.basketNetTotalIls)}) to complete cash sale"
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = financial.warning.copy(alpha = 0.95f),
                                    modifier = Modifier.weight(1f)
                                )

                                if (state.basket.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = {
                                            onSetExactAmount()
                                            focusManager.clearFocus()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("recover_exact_cash_button")
                                    ) {
                                        Text(
                                            text = lang.tr("تعيين بالكامل", "Set Exact"),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Optional Keypad Toggle (Collapsible to prevent soft keyboard collision)
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
                                lang.tr("إخفاء لوحة أرقام الصندوق", "Hide Numpad")
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

                // Quick secondary option: Convert to Credit Sale (ST-10 Section 4)
                Surface(
                    onClick = {
                        paymentMode = PaymentMode.CREDIT
                        focusManager.clearFocus()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = financial.debit,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))

                    }
                }
            } else {
                // =========================================================================
                // 4. CREDIT MODE: Customer Identity, Ledger, Limit Meter & Invariants (ST-11)
                // =========================================================================
                StitchCard(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (state.wouldCreditExceedCustomerLimit) {
                            financial.debit.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (selectedCustomer != null) {
                            // Customer Identity Header (ST-11)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = selectedCustomer.name(lang),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (selectedCustomer.phone.isNotBlank()) {
                                                Text(
                                                    text = selectedCustomer.phone,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 12.sp,
                                                        fontFamily = MonoFontFamily
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            StitchBadge(
                                                text = lang.tr("زبون معتمد", "Verified Customer"),
                                                tone = StitchBadgeTone.SUCCESS
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    onClick = { isCustomerPickerOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("payment_select_customer_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PersonSearch,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = lang.tr("تغيير", "Change"),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // 2-Column Ledger Financial Breakdown (ST-11)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = lang.tr("الرصيد السابق المستحق", "Previous Debt Due"),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = WesternFormatters.formatIls(selectedCustomer.balanceIls),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = if (selectedCustomer.balanceIls > 0.0) financial.debit else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (selectedCustomer.balanceIls > 0.0) {
                                            StitchBadge(
                                                text = lang.tr("ذمة سابقة", "Owed"),
                                                tone = StitchBadgeTone.WARNING
                                            )
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = lang.tr("سقف الائتمان المعتمد", "Approved Credit Limit"),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = WesternFormatters.formatIls(selectedCustomer.creditLimitIls),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = MonoFontFamily
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Credit Limit Meter & Projection
                            val projectedBalance = state.projectedCustomerBalanceIfCreditIls ?: (selectedCustomer.balanceIls + state.basketNetTotalIls)
                            val overLimit = state.wouldCreditExceedCustomerLimit
                            val consumptionRatio = if (selectedCustomer.creditLimitIls > 0.0) {
                                (projectedBalance / selectedCustomer.creditLimitIls).toFloat().coerceIn(0f, 1f)
                            } else 1f

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = lang.tr("نسبة استهلاك السقف بعد الفاتورة", "Limit Usage After Sale"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${(consumptionRatio * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = MonoFontFamily
                                        ),
                                        color = if (overLimit) financial.debit else MaterialTheme.colorScheme.primary
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = { consumptionRatio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = if (overLimit) financial.debit else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )

                                if (!overLimit) {
                                    val safeRemaining = (selectedCustomer.creditLimitIls - projectedBalance).coerceAtLeast(0.0)
                                    Text(
                                        text = lang.tr(
                                            "ضمن الحد الائتماني الآمن (متبقي ${WesternFormatters.formatIls(safeRemaining)} متاح)",
                                            "Within safe credit ceiling (${WesternFormatters.formatIls(safeRemaining)} available)"
                                        ),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = financial.income
                                    )
                                }
                            }

                            // Projected Post-Transaction Balance Summary Box (ST-11)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                                    Column {
                                        Text(
                                            text = lang.tr("الرصيد الإجمالي بعد هذه الفاتورة", "Projected Total Debt"),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${WesternFormatters.formatIls(selectedCustomer.balanceIls)} + ${WesternFormatters.formatIls(state.basketNetTotalIls)}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                fontFamily = MonoFontFamily
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        text = WesternFormatters.formatIls(projectedBalance),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = MonoFontFamily
                                        ),
                                        color = if (overLimit) financial.debit else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // Over-limit Warning Alert Banner
                            if (overLimit) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = financial.debitContainer,
                                    border = BorderStroke(1.dp, financial.debit.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("payment_credit_limit_warning")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
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
                                            text = lang.tr(
                                                "يتجاوز سقف الدين المسموح بمقدار ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}. استلم الفاتورة نقداً، أو غيّر الزبون.",
                                                "Exceeds credit ceiling by ${WesternFormatters.formatIls(state.creditCeilingExcessIls)}. Collect cash or change customer."
                                            ),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = financial.debit,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Unlinked Customer / Walk-in State
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = lang.tr(
                                        "يلزم تحديد زبون مسجل لتقييد الذمة الآجلة",
                                        "Select a registered customer for credit sale"
                                    ),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )

                                Surface(
                                    onClick = { isCustomerPickerOpen = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("payment_select_customer_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PersonSearch,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = lang.tr("اختيار زبون مسجل", "Select Registered Customer"),
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Accounting Notice Card (ST-11 Section 4)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = financial.debit,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = lang.tr("قيد الحساب والذمم", "Ledger & Credit Note"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = lang.tr(
                                    "سيسجل المبلغ كاملاً (${WesternFormatters.formatIls(state.basketNetTotalIls)}) كذمة مستحقة على حساب الزبون. المقبوض نقداً: 0.00 ₪.",
                                    "Full amount (${WesternFormatters.formatIls(state.basketNetTotalIls)}) will be debited to customer account. Cash received: 0.00 ₪."
                                ),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 5. Authoritative Single Final Confirmation Dock (Fixed at Bottom)
        // =========================================================================
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (paymentMode == PaymentMode.CASH) {
                    // Consequence line for cash sale
                    Text(
                        text = when {
                            !sufficient -> lang.tr(
                                "المتبقي لإتمام البيع: ${WesternFormatters.formatIls(state.remainingUnpaidIls)}",
                                "Shortfall: ${WesternFormatters.formatIls(state.remainingUnpaidIls)}"
                            )
                            state.changeDueIls > 0.005 -> lang.tr(
                                "المقبوض: ${WesternFormatters.formatIls(state.cashTenderedIls)} • الباقي: ${WesternFormatters.formatIls(state.changeDueIls)}",
                                "Received: ${WesternFormatters.formatIls(state.cashTenderedIls)} • Change: ${WesternFormatters.formatIls(state.changeDueIls)}"
                            )
                            else -> lang.tr(
                                "المقبوض: ${WesternFormatters.formatIls(state.cashTenderedIls)} • بدون باقي (مطابق)",
                                "Received: ${WesternFormatters.formatIls(state.cashTenderedIls)} • Exact"
                            )
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (sufficient) MaterialTheme.colorScheme.onSurfaceVariant else financial.warning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Primary confirmation button (ST-10)
                    Button(
                        onClick = onCompleteSale,
                        enabled = state.basket.isNotEmpty() && sufficient && !state.isSubmittingSale,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("complete_sale_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Payments,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = lang.tr(
                                "تأكيد واستلام النقد (${WesternFormatters.formatIls(state.basketNetTotalIls)})",
                                "Confirm Cash Sale (${WesternFormatters.formatIls(state.basketNetTotalIls)})"
                            ),
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
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (selectedCustomer != null && !overLimit) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            financial.debit
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Primary confirmation button (ST-11)
                    Button(
                        onClick = onCompleteCreditSale,
                        enabled = selectedCustomer != null && !overLimit && state.basket.isNotEmpty() && !state.isSubmittingSale,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCustomer != null && !overLimit) financial.debit else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedCustomer != null && !overLimit) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
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
                                    "تأكيد البيع على الحساب (${WesternFormatters.formatIls(state.basketNetTotalIls)})",
                                    "Confirm Credit Sale (${WesternFormatters.formatIls(state.basketNetTotalIls)})"
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
