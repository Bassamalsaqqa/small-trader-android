package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = NavyPrimaryLight,
    onPrimary = OnNavyPrimaryLight,
    primaryContainer = NavyPrimaryContainerLight,
    onPrimaryContainer = OnNavyPrimaryContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = OnSlateSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight,
    onSecondaryContainer = OnSlateSecondaryContainerLight,
    tertiary = SageTertiaryLight,
    onTertiary = OnSageTertiaryLight,
    tertiaryContainer = SageTertiaryContainerLight,
    onTertiaryContainer = OnSageTertiaryContainerLight,
    background = NeutralBackgroundLight,
    onBackground = OnNeutralBackgroundLight,
    surface = NeutralSurfaceLight,
    onSurface = OnNeutralSurfaceLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = OnNeutralSurfaceVariantLight,
    outline = NeutralOutlineLight,
    outlineVariant = NeutralOutlineVariantLight,
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFDE8E8),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF601410)
)

private val DarkColorScheme = darkColorScheme(
    primary = NavyPrimaryDark,
    onPrimary = OnNavyPrimaryDark,
    primaryContainer = NavyPrimaryContainerDark,
    onPrimaryContainer = OnNavyPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = OnSlateSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = OnSlateSecondaryContainerDark,
    tertiary = SageTertiaryDark,
    onTertiary = OnSageTertiaryDark,
    tertiaryContainer = SageTertiaryContainerDark,
    onTertiaryContainer = OnSageTertiaryContainerDark,
    background = NeutralBackgroundDark,
    onBackground = OnNeutralBackgroundDark,
    surface = NeutralSurfaceDark,
    onSurface = OnNeutralSurfaceDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = OnNeutralSurfaceVariantDark,
    outline = NeutralOutlineDark,
    outlineVariant = NeutralOutlineVariantDark,
    error = Color(0xFFF87171),            // Readable coral red (never pink)
    errorContainer = Color(0xFF451919),
    onError = Color(0xFF410002),
    onErrorContainer = Color(0xFFFCA5A5)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Accessor for app-wide financial status colors that adapt cleanly between light and dark modes.
 */
object TraderTheme {
    val financial: FinancialColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFinancialColors.current
}

@Composable
fun SmallTraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isRtl: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val financialColors = if (darkTheme) DarkFinancialColors else LightFinancialColors
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalFinancialColors provides financialColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    SmallTraderTheme(darkTheme = darkTheme, isRtl = true, content = content)
}
