package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.WesternFormatters
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TraderTheme

/**
 * Phase 1 Shared Compose Components for Small Trader (التاجر الصغير).
 *
 * Implements BoardUI-inspired and Material 3 Adaptive design patterns:
 * - 48dp minimum accessible touch targets across all interactive controls.
 * - Arabic RTL-safe layouts and isolated LTR numeric runs.
 * - Restrained semantic color tokens for financial certainty.
 * - Graded slate and calm alabaster surface hierarchy.
 */

/**
 * High-craft elevated card surface with standardized 14dp corner radius,
 * hairline border, and zero artificial drop-shadow clutter.
 */
@Composable
fun PhaseOneCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    content: @Composable () -> Unit
) {
    val tagModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = tagModifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(borderWidth, borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    } else {
        Card(
            modifier = tagModifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(borderWidth, borderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

/**
 * Standard section heading with optional trailing action button (minimum 48dp touch target).
 */
@Composable
fun PhaseOneSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionTitle: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionTag: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (actionTitle != null && onActionClick != null) {
            val buttonModifier = if (actionTag != null) {
                Modifier
                    .heightIn(min = 48.dp)
                    .testTag(actionTag)
            } else {
                Modifier.heightIn(min = 48.dp)
            }
            TextButton(
                onClick = onActionClick,
                modifier = buttonModifier,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = actionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * BoardUI-inspired financial metric tile with subtle tonal tinting,
 * bold tabular numerals, and role-safe navigation affordance.
 */
@Composable
fun PhaseOneMetricTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: (() -> Unit)? = null,
    testTag: String? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    } else {
        Modifier
    }

    val tagModifier = if (testTag != null) clickModifier.testTag(testTag) else clickModifier

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.then(tagModifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Three-state semantic badge for financial status (income/debit/warning/neutral).
 */
@Composable
fun PhaseOneStatusBadge(
    text: String,
    textColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = if (isMonospace) MonoFontFamily else CairoFontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1
        )
    }
}

/**
 * Tactile single-axis stepper pill (`- qty +`) with 48dp minimum hit targets.
 */
@Composable
fun PhaseOneStepperPill(
    quantity: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    isMaxReached: Boolean = false,
    incTag: String? = null,
    decTag: String? = null,
    qtyTag: String? = null
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decrement button with 48dp touch target
            val decModifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDecrement)
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (decTag != null) decModifier.testTag(decTag) else decModifier
            ) {
                Icon(
                    imageVector = if (quantity == 1) Icons.Default.DeleteOutline else Icons.Default.Remove,
                    contentDescription = null,
                    tint = if (quantity == 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Quantity display
            val qtyModifier = Modifier
                .widthIn(min = 24.dp)
                .padding(horizontal = 2.dp)
            Text(
                text = WesternFormatters.formatInt(quantity),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = if (qtyTag != null) qtyModifier.testTag(qtyTag) else qtyModifier
            )

            // Increment button with 48dp touch target
            val incModifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = !isMaxReached, role = Role.Button, onClick = onIncrement)
            Box(
                contentAlignment = Alignment.Center,
                modifier = if (incTag != null) incModifier.testTag(incTag) else incModifier
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isMaxReached) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = if (isMaxReached) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Store Operation Tile for Bento grid navigation (48dp+ touch target).
 */
@Composable
fun PhaseOneBentoTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    accentTint: Color? = null
) {
    val tagModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    Surface(
        onClick = onClick,
        modifier = tagModifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = accentTint ?: MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
