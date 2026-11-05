package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Contemporary Retail Business Palette for Small Trader (التاجر الصغير).
 *
 * Design Direction:
 * Replaces murky olive, saturated mint, and muddy terracotta with a calm,
 * prestigious Deep Navy and Architectural Slate palette. Surfaces are graded
 * carefully in both light and dark modes to avoid visual fatigue, while financial
 * figures use dignified brick-crimson and emerald status tokens rather than
 * eye-straining pinks or saturated alerts.
 */

// =========================================================================
// Light Theme: Crisp, airy neutral canvas with authoritative Navy accent
// =========================================================================

val NavyPrimaryLight = Color(0xFF1B365D)              // Deep Merchant Navy
val OnNavyPrimaryLight = Color(0xFFFFFFFF)
val NavyPrimaryContainerLight = Color(0xFFE9EFF6)     // Soft ice-slate container
val OnNavyPrimaryContainerLight = Color(0xFF0F2542)

val SlateSecondaryLight = Color(0xFF475569)           // Neutral architectural slate
val OnSlateSecondaryLight = Color(0xFFFFFFFF)
val SlateSecondaryContainerLight = Color(0xFFF1F5F9)  // Soft mist
val OnSlateSecondaryContainerLight = Color(0xFF1E293B)

val SageTertiaryLight = Color(0xFF2C6E6B)             // Quiet merchant sage accent
val OnSageTertiaryLight = Color(0xFFFFFFFF)
val SageTertiaryContainerLight = Color(0xFFE4F0EE)
val OnSageTertiaryContainerLight = Color(0xFF133836)

val NeutralBackgroundLight = Color(0xFFF6F8FA)        // Soft crisp off-white canvas
val OnNeutralBackgroundLight = Color(0xFF14171A)      // High-contrast charcoal text
val NeutralSurfaceLight = Color(0xFFFFFFFF)           // Crisp white card surface
val OnNeutralSurfaceLight = Color(0xFF14171A)
val NeutralSurfaceVariantLight = Color(0xFFEDF1F5)    // Input fields, chips, headers
val OnNeutralSurfaceVariantLight = Color(0xFF55606E)  // Legible secondary text (> 5.5:1 contrast)
val NeutralOutlineLight = Color(0xFFD6DCE3)           // Fine delicate card dividers
val NeutralOutlineVariantLight = Color(0xFFE6EBF0)

// =========================================================================
// Dark Theme: Properly graded dark slate surfaces (no pitch-black or green swamp)
// =========================================================================

val NavyPrimaryDark = Color(0xFF8AB4F8)               // Clean, legible slate-blue
val OnNavyPrimaryDark = Color(0xFF0A2548)
val NavyPrimaryContainerDark = Color(0xFF183B68)      // Rich elevated navy
val OnNavyPrimaryContainerDark = Color(0xFFD4E3FC)

val SlateSecondaryDark = Color(0xFF94A3B8)            // Cool slate
val OnSlateSecondaryDark = Color(0xFF0F172A)
val SlateSecondaryContainerDark = Color(0xFF242E3D)   // Subtle elevated container
val OnSlateSecondaryContainerDark = Color(0xFFE2E8F0)

val SageTertiaryDark = Color(0xFF76BAAF)              // Muted sage
val OnSageTertiaryDark = Color(0xFF0E3530)
val SageTertiaryContainerDark = Color(0xFF1C4541)
val OnSageTertiaryContainerDark = Color(0xFFC7EBE5)

val NeutralBackgroundDark = Color(0xFF101317)         // Deep slate-charcoal (no swamp green or pitch black)
val OnNeutralBackgroundDark = Color(0xFFE8ECF0)       // Crisp, legible text
val NeutralSurfaceDark = Color(0xFF171B21)            // First elevation level (cards)
val OnNeutralSurfaceDark = Color(0xFFE8ECF0)
val NeutralSurfaceVariantDark = Color(0xFF202630)     // Second elevation level (chips, inputs)
val OnNeutralSurfaceVariantDark = Color(0xFF98A4B3)   // Accessible secondary text
val NeutralOutlineDark = Color(0xFF323B47)            // Clean structural border
val NeutralOutlineVariantDark = Color(0xFF222832)

// =========================================================================
// Dignified Financial & Operational Status Colors (Accessible, no pink amounts)
// =========================================================================

@Immutable
data class FinancialColors(
    val income: Color,              // Cash sales, debt collections, positive balance
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    val debit: Color,               // Customer debts, expenses, supplier payables (serious wine/brick, NOT pink)
    val debitContainer: Color,
    val onDebitContainer: Color,
    val warning: Color,             // Low stock, pending receipts
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LightFinancialColors = FinancialColors(
    income = Color(0xFF1B6B46),             // Rich pine emerald
    incomeContainer = Color(0xFFE8F6EF),
    onIncomeContainer = Color(0xFF0B3822),
    debit = Color(0xFF9E2A2B),              // Serious deep brick/crimson
    debitContainer = Color(0xFFFDE8E8),
    onDebitContainer = Color(0xFF5A1416),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F)
)

val DarkFinancialColors = FinancialColors(
    income = Color(0xFF4ADE80),             // Crisp calm green for dark backgrounds
    incomeContainer = Color(0xFF123824),
    onIncomeContainer = Color(0xFFB6F2CE),
    debit = Color(0xFFF87171),              // Warm coral-crimson, clearly readable, not bubblegum pink
    debitContainer = Color(0xFF451919),
    onDebitContainer = Color(0xFFFDCACA),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF452E0B),
    onWarningContainer = Color(0xFFFDE68A)
)

val LocalFinancialColors = staticCompositionLocalOf { LightFinancialColors }

// Backward-compatible adaptors for existing call-sites
val SuccessGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalFinancialColors.current.income

val SuccessGreenContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalFinancialColors.current.incomeContainer

val WarningAmber: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalFinancialColors.current.warning

val WarningAmberContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalFinancialColors.current.warningContainer

// Thermal paper remains an authentic off-white receipt simulation
val ThermalPaperCream = Color(0xFFFFFDF8)
val ThermalPaperInk = Color(0xFF1A1F1D)
val ThermalPaperMutedInk = Color(0xFF5A635E)
