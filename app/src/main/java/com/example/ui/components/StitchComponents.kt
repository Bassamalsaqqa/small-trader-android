package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.WesternFormatters
import com.example.ui.theme.TraderTheme
import java.util.Locale

/**
 * Stitch Design System UI Components & BiDi Helpers for Small Trader (التاجر الصغير).
 *
 * Implements Google Stitch Canonical Direction (Project 15641450350510420845 / DESIGN_LOCK.md):
 * 1. Bidi-Safe Formatting: Isolates Western European numerals and alphanumeric codes (INV-1042)
 *    so punctuation and currency symbols (₪) do not hop or flip in RTL Android renderers.
 * 2. 48dp Touch Targets: All interactive components enforce minimum 48x48dp bounds.
 * 3. Consistent Geometry: Cards have 12dp corner radii with subtle 1dp borders (#E4E9F1);
 *    Buttons have 10dp corner radii; Status badges have 6-8dp corner radii.
 * 4. Microtype Safety: Arabic microtype is never rendered below 12sp.
 * 5. Expandable Containers: No fixed-height clipping on text containers.
 */

object StitchBidiUtils {
    /**
     * Formats an alphanumeric invoice code safely without leading `#` which flips in RTL.
     * Example: "فاتورة INV-1042" instead of "#INV-1042"
     */
    fun formatInvoiceCode(number: Int, prefix: String = "فاتورة"): String {
        return "$prefix \u2066INV-${String.format(Locale.US, "%04d", number)}\u2069"
    }

    /**
     * Formats an alphanumeric bill code safely in RTL.
     */
    fun formatBillCode(number: Int, prefix: String = "شراء"): String {
        return "$prefix \u2066BILL-${String.format(Locale.US, "%04d", number)}\u2069"
    }

    /**
     * Formats an ILS monetary amount with Western numerals and directional isolation.
     */
    fun formatIls(amount: Double): String {
        return "\u2066${WesternFormatters.formatIls(amount)}\u2069"
    }
}

enum class StitchBadgeTone {
    SUCCESS,
    WARNING,
    DANGER,
    NEUTRAL,
    PRIMARY
}

/**
 * High-contrast semantic status pill for Stitch lists and sheets.
 * Enforces >= 12sp typography and expandable width.
 */
@Composable
fun StitchBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: StitchBadgeTone = StitchBadgeTone.NEUTRAL,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    val financial = TraderTheme.financial

    val (bgColor, textColor) = when (tone) {
        StitchBadgeTone.SUCCESS -> financial.incomeContainer to financial.income
        StitchBadgeTone.WARNING -> financial.warningContainer to financial.warning
        StitchBadgeTone.DANGER -> financial.debitContainer to financial.debit
        StitchBadgeTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        StitchBadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = shape,
        color = bgColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Standard Stitch elevated/bordered card container (12dp radius, 1dp subtle border).
 */
@Composable
fun StitchCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = border
        ) {
            Box(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = border
        ) {
            Box(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

/**
 * Primary action button adhering to Stitch guidelines (10dp radius, min 48dp height).
 */
@Composable
fun StitchPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    minHeight: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        ),
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * Secondary tactile tonal button with border (10dp radius, min 48dp height).
 */
@Composable
fun StitchTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    minHeight: Dp = 48.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = minHeight),
        enabled = enabled,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
