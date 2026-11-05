package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Contemporary Retail-Business Visual Identity & Color System for Small Trader (التاجر الصغير).
 *
 * Design Principles:
 * 1. Light Theme First: Crisp white cards (`#FFFFFF`) layered over a calm architectural
 *    stone-alabaster canvas (`#F4F6F9`), anchored by one disciplined Cobalt-Navy primary accent (`#1D4E89`).
 * 2. Properly Graded Dark Theme: Replaces near-black voids (`#101317`) with a stepped dark-slate
 *    surface hierarchy (`#161B22` canvas -> `#1E2530` card surface -> `#283140` interactive container)
 *    that preserves depth and legibility without eye strain.
 * 3. Functional Status Colors: Replaces neon mint, muddy orange/brown, and pastel pink figures
 *    with high-contrast emerald-sage (`income`), warm vermilion-crimson (`debit`), and deep ochre (`warning`).
 */

// ============================================================================
// 1. LIGHT THEME PALETTE — Crisp Neutral Surfaces & Controlled Cobalt Accent
// ============================================================================

val CobaltPrimaryLight = Color(0xFF1D4E89)            // Authoritative retail cobalt-navy (7.9:1 on white)
val OnCobaltPrimaryLight = Color(0xFFFFFFFF)
val CobaltPrimaryContainerLight = Color(0xFFE7EFF9)   // Soft sky-slate active highlight
val OnCobaltPrimaryContainerLight = Color(0xFF0E2A4D) // Deep navy ink (11.5:1 on container)
val CobaltInversePrimaryLight = Color(0xFF9BC3FA)

val SlateSecondaryLight = Color(0xFF334155)           // Balanced architectural slate
val OnSlateSecondaryLight = Color(0xFFFFFFFF)
val SlateSecondaryContainerLight = Color(0xFFEAEFF5)  // Tactile neutral tonal button / chip fill
val OnSlateSecondaryContainerLight = Color(0xFF1E293B)

val SteelTertiaryLight = Color(0xFF285E61)            // Restrained steel-teal accent
val OnSteelTertiaryLight = Color(0xFFFFFFFF)
val SteelTertiaryContainerLight = Color(0xFFE4F0F1)
val OnSteelTertiaryContainerLight = Color(0xFF123436)

val CanvasBackgroundLight = Color(0xFFF4F6F9)         // Calm stone-alabaster background canvas
val OnCanvasBackgroundLight = Color(0xFF111827)       // Crisp deep charcoal ink (16.5:1)
val CardSurfaceLight = Color(0xFFFFFFFF)              // Pure white elevated card & bar surface
val OnCardSurfaceLight = Color(0xFF111827)
val SurfaceVariantLight = Color(0xFFEEF2F6)           // Subtle container for pills, inputs & headers
val OnSurfaceVariantLight = Color(0xFF475569)         // High-contrast secondary text (7.2:1 on white)

val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF8FAFC)
val SurfaceContainerLight = Color(0xFFF1F5F9)
val SurfaceContainerHighLight = Color(0xFFE9EEF5)
val SurfaceContainerHighestLight = Color(0xFFE2E8F0)
val SurfaceBrightLight = Color(0xFFFFFFFF)
val SurfaceDimLight = Color(0xFFDCE3EC)

val OutlineLight = Color(0xFFCBD5E1)                  // Accessible control & input border
val OutlineVariantLight = Color(0xFFE2E8F0)           // Delicate card border & hairline divider
val InverseSurfaceLight = Color(0xFF1E293B)
val InverseOnSurfaceLight = Color(0xFFF8FAFC)

val ErrorLight = Color(0xFFB42318)                    // Dignified vermilion-crimson
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFEF0EF)
val OnErrorContainerLight = Color(0xFF69150E)

// ============================================================================
// 2. DARK THEME PALETTE — Graded Architectural Slate (Never an Almost-Black Void)
// ============================================================================

val CobaltPrimaryDark = Color(0xFF7EB3F7)             // Calm, high-contrast sky-cobalt (7.4:1 on surface)
val OnCobaltPrimaryDark = Color(0xFF0B2545)           // Deep navy ink on primary buttons (9.2:1)
val CobaltPrimaryContainerDark = Color(0xFF213C61)    // Rich elevated cobalt container
val OnCobaltPrimaryContainerDark = Color(0xFFDCEAFB)  // Crisp ice-white text
val CobaltInversePrimaryDark = Color(0xFF1D4E89)

val SlateSecondaryDark = Color(0xFF94A3B8)            // Refined cool slate
val OnSlateSecondaryDark = Color(0xFF0F172A)
val SlateSecondaryContainerDark = Color(0xFF2A3340)   // Clearly graded tonal button & chip surface
val OnSlateSecondaryContainerDark = Color(0xFFE2E8F0)

val SteelTertiaryDark = Color(0xFF6FB8BC)             // Calm muted sea-steel
val OnSteelTertiaryDark = Color(0xFF0D2E30)
val SteelTertiaryContainerDark = Color(0xFF1E3F42)
val OnSteelTertiaryContainerDark = Color(0xFFCBECEE)

val CanvasBackgroundDark = Color(0xFF161B22)          // Balanced deep slate canvas (not pitch black)
val OnCanvasBackgroundDark = Color(0xFFF0F4F8)        // Crisp off-white primary ink (15.2:1)
val CardSurfaceDark = Color(0xFF1E2530)               // First elevation tier: cards, top bar, bottom bar
val OnCardSurfaceDark = Color(0xFFF0F4F8)
val SurfaceVariantDark = Color(0xFF283140)            // Second elevation tier: inputs, chips, badges
val OnSurfaceVariantDark = Color(0xFFA6B4C6)          // Accessible secondary text (6.5:1 on surface)

val SurfaceContainerLowestDark = Color(0xFF13171D)
val SurfaceContainerLowDark = Color(0xFF1A2029)
val SurfaceContainerDark = Color(0xFF1E2530)
val SurfaceContainerHighDark = Color(0xFF252E3B)
val SurfaceContainerHighestDark = Color(0xFF2E3846)
val SurfaceBrightDark = Color(0xFF344050)
val SurfaceDimDark = Color(0xFF161B22)

val OutlineDark = Color(0xFF3E4C5E)                   // Clear structural border for inputs & chips
val OutlineVariantDark = Color(0xFF2C3646)            // Subtle card border & divider on dark slate
val InverseSurfaceDark = Color(0xFFE2E8F0)
val InverseOnSurfaceDark = Color(0xFF161B22)

val ErrorDark = Color(0xFFF0755E)                     // Warm vermilion red (never pastel pink)
val OnErrorDark = Color(0xFF3B0D07)
val ErrorContainerDark = Color(0xFF3D1F1D)
val OnErrorContainerDark = Color(0xFFFAD4CC)

// ============================================================================
// 3. SEMANTIC FINANCIAL & OPERATIONAL STATUS TOKENS
// ============================================================================

@Immutable
data class FinancialColors(
    val income: Color,              // Cash inflows, collections, settled status
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    val debit: Color,               // Receivables, payables, deductions (vermilion-crimson, never pink)
    val debitContainer: Color,
    val onDebitContainer: Color,
    val warning: Color,             // Low stock, pending attention (ochre-gold, never muddy brown)
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LightFinancialColors = FinancialColors(
    income = Color(0xFF156D49),             // Deep forest emerald (6.4:1 on white)
    incomeContainer = Color(0xFFE6F4ED),
    onIncomeContainer = Color(0xFF0C422B),
    debit = Color(0xFFB42318),              // Authoritative crimson-vermilion (6.3:1 on white)
    debitContainer = Color(0xFFFEF0EF),
    onDebitContainer = Color(0xFF69150E),
    warning = Color(0xFF9A5208),            // Accessible deep ochre-amber (5.7:1 on white)
    warningContainer = Color(0xFFFEF5E7),
    onWarningContainer = Color(0xFF5C3004)
)

val DarkFinancialColors = FinancialColors(
    income = Color(0xFF5CC894),             // Calm jade-emerald (7.8:1 on dark card, never neon mint)
    incomeContainer = Color(0xFF173629),
    onIncomeContainer = Color(0xFFC4F0DA),
    debit = Color(0xFFF0755E),              // Warm vermilion-coral (6.6:1 on dark card, never bubblegum pink)
    debitContainer = Color(0xFF3D1F1D),
    onDebitContainer = Color(0xFFFAD4CC),
    warning = Color(0xFFF2B249),            // Clear warm amber-gold (8.9:1 on dark card)
    warningContainer = Color(0xFF3B2B13),
    onWarningContainer = Color(0xFFFCE3B4)
)

val LocalFinancialColors = staticCompositionLocalOf { LightFinancialColors }

// Backward-compatible semantic accessors
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

// Authentic paper tokens for thermal receipt preview when needed
val ThermalPaperCream = Color(0xFFFAFBFD)
val ThermalPaperInk = Color(0xFF111827)
val ThermalPaperMutedInk = Color(0xFF475569)
