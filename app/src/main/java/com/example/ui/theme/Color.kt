package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Stitch Design System Canonical Color Scheme for Small Trader (التاجر الصغير).
 *
 * Grounded in Stitch Project 15641450350510420845 and DESIGN_LOCK.md:
 * 1. Primary Brand: Vibrant Retail Blue (#255FD6), pressed state (#1F52BD), pale container (#EEF4FF).
 * 2. Canvas Background: Cool, crisp ground (#F7F9FC).
 * 3. Elevated Cards & Sheets: Pure White (#FFFFFF) with subtle outline (#E4E9F1).
 * 4. Secondary Wells & Input BG: Soft surface (#F5F7FB).
 * 5. High-Contrast Text: Primary ink (#172033), Secondary slate (#475467), Muted hint (#526174).
 * 6. Financial Semantics:
 *    - Success (Paid): #14804A, Container #E8F5E9
 *    - Warning (Partial / Low Stock): #A15C00, Container #FFF5DF
 *    - Danger / Debt (Receivables / Removal): #B42318, Container #FFF0EE
 * 7. Accessible Dark Theme: High contrast #7EB3F7 with #0B2545 text (no white on 7EB3F7),
 *    graded slate surfaces (#161B22 -> #1E2530 -> #283140).
 */

// ============================================================================
// 1. LIGHT THEME PALETTE — Canonical Stitch Brand
// ============================================================================

val PrimaryBlueLight = Color(0xFF255FD6)              // Canonical Stitch Blue
val OnPrimaryBlueLight = Color(0xFFFFFFFF)
val PrimaryBlueContainerLight = Color(0xFFEEF4FF)     // Soft blue active container
val OnPrimaryBlueContainerLight = Color(0xFF0E2A4D)   // Deep navy ink
val PrimaryInverseLight = Color(0xFF9BC3FA)

val SecondarySlateLight = Color(0xFF475467)           // Balanced slate
val OnSecondarySlateLight = Color(0xFFFFFFFF)
val SecondarySlateContainerLight = Color(0xFFF5F7FB)  // Neutral soft surface
val OnSecondarySlateContainerLight = Color(0xFF172033)

val TertiarySteelLight = Color(0xFF285E61)            // Restrained teal accent
val OnTertiarySteelLight = Color(0xFFFFFFFF)
val TertiarySteelContainerLight = Color(0xFFE4F0F1)
val OnTertiarySteelContainerLight = Color(0xFF123436)

val CanvasBackgroundLight = Color(0xFFF7F9FC)         // Canonical Stitch Canvas
val OnCanvasBackgroundLight = Color(0xFF172033)       // Primary ink
val CardSurfaceLight = Color(0xFFFFFFFF)              // Pure white cards & sheets
val OnCardSurfaceLight = Color(0xFF172033)
val SurfaceVariantLight = Color(0xFFF5F7FB)           // Soft input / well surface
val OnSurfaceVariantLight = Color(0xFF475467)         // Secondary text

val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF7F9FC)
val SurfaceContainerLight = Color(0xFFF5F7FB)
val SurfaceContainerHighLight = Color(0xFFEEF2F6)
val SurfaceContainerHighestLight = Color(0xFFE4E9F1)
val SurfaceBrightLight = Color(0xFFFFFFFF)
val SurfaceDimLight = Color(0xFFDCE3EC)

val OutlineLight = Color(0xFFE4E9F1)                  // Card outline & row dividers
val OutlineVariantLight = Color(0xFFE4E9F1)
val InverseSurfaceLight = Color(0xFF172033)
val InverseOnSurfaceLight = Color(0xFFF7F9FC)

val ErrorLight = Color(0xFFB42318)                    // Danger / Debt Accent
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFF0EE)           // Danger Pale Container
val OnErrorContainerLight = Color(0xFFB42318)

// Backward compatibility aliases for existing callers
val CobaltPrimaryLight = PrimaryBlueLight
val OnCobaltPrimaryLight = OnPrimaryBlueLight
val CobaltPrimaryContainerLight = PrimaryBlueContainerLight
val OnCobaltPrimaryContainerLight = OnPrimaryBlueContainerLight
val CobaltInversePrimaryLight = PrimaryInverseLight

val SlateSecondaryLight = SecondarySlateLight
val OnSlateSecondaryLight = OnSecondarySlateLight
val SlateSecondaryContainerLight = SecondarySlateContainerLight
val OnSlateSecondaryContainerLight = OnSecondarySlateContainerLight

val SteelTertiaryLight = TertiarySteelLight
val OnSteelTertiaryLight = OnTertiarySteelLight
val SteelTertiaryContainerLight = TertiarySteelContainerLight
val OnSteelTertiaryContainerLight = OnTertiarySteelContainerLight

// ============================================================================
// 2. DARK THEME PALETTE — Graded Architectural Slate (Never pitch black)
// ============================================================================

val CobaltPrimaryDark = Color(0xFF7EB3F7)             // Sky-cobalt for dark surfaces
val OnCobaltPrimaryDark = Color(0xFF0B2545)           // Deep navy ink (strict contrast, never white on 7EB3F7)
val CobaltPrimaryContainerDark = Color(0xFF213C61)    // Elevated cobalt container
val OnCobaltPrimaryContainerDark = Color(0xFFDCEAFB)  // Crisp ice-blue text
val CobaltInversePrimaryDark = Color(0xFF255FD6)

val SlateSecondaryDark = Color(0xFF94A3B8)            // Refined cool slate
val OnSlateSecondaryDark = Color(0xFF0F172A)
val SlateSecondaryContainerDark = Color(0xFF2A3340)   // Graded tonal button/chip surface
val OnSlateSecondaryContainerDark = Color(0xFFE2E8F0)

val SteelTertiaryDark = Color(0xFF6FB8BC)             // Muted teal
val OnSteelTertiaryDark = Color(0xFF0D2E30)
val SteelTertiaryContainerDark = Color(0xFF1E3F42)
val OnSteelTertiaryContainerDark = Color(0xFFCBECEE)

val CanvasBackgroundDark = Color(0xFF161B22)          // Deep slate canvas
val OnCanvasBackgroundDark = Color(0xFFF0F4F8)        // Off-white primary ink
val CardSurfaceDark = Color(0xFF1E2530)               // First elevation tier: cards & sheets
val OnCardSurfaceDark = Color(0xFFF0F4F8)
val SurfaceVariantDark = Color(0xFF283140)            // Second elevation tier: inputs & chips
val OnSurfaceVariantDark = Color(0xFFA6B4C6)          // Accessible secondary text

val SurfaceContainerLowestDark = Color(0xFF13171D)
val SurfaceContainerLowDark = Color(0xFF1A2029)
val SurfaceContainerDark = Color(0xFF1E2530)
val SurfaceContainerHighDark = Color(0xFF252E3B)
val SurfaceContainerHighestDark = Color(0xFF2E3846)
val SurfaceBrightDark = Color(0xFF344050)
val SurfaceDimDark = Color(0xFF161B22)

val OutlineDark = Color(0xFF3E4C5E)                   // Structural borders in dark theme
val OutlineVariantDark = Color(0xFF2C3646)            // Subtle dividers
val InverseSurfaceDark = Color(0xFFE2E8F0)
val InverseOnSurfaceDark = Color(0xFF161B22)

val ErrorDark = Color(0xFFF0755E)                     // Warm vermilion red
val OnErrorDark = Color(0xFF3B0D07)
val ErrorContainerDark = Color(0xFF3D1F1D)
val OnErrorContainerDark = Color(0xFFFAD4CC)

// ============================================================================
// 3. SEMANTIC FINANCIAL & OPERATIONAL STATUS TOKENS
// ============================================================================

@Immutable
data class FinancialColors(
    val income: Color,              // Cash inflows, collections, fully settled status
    val incomeContainer: Color,
    val onIncomeContainer: Color,
    val debit: Color,               // Receivables, debts, deductions
    val debitContainer: Color,
    val onDebitContainer: Color,
    val warning: Color,             // Low stock, partial payment status
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LightFinancialColors = FinancialColors(
    income = Color(0xFF14804A),             // Canonical Success Accent
    incomeContainer = Color(0xFFE8F5E9),    // Success Pale Container
    onIncomeContainer = Color(0xFF14804A),
    debit = Color(0xFFB42318),              // Danger / Debt Accent
    debitContainer = Color(0xFFFFF0EE),     // Danger Pale Container
    onDebitContainer = Color(0xFFB42318),
    warning = Color(0xFFA15C00),            // Warning / Partial Accent
    warningContainer = Color(0xFFFFF5DF),   // Warning Pale Container
    onWarningContainer = Color(0xFFA15C00)
)

val DarkFinancialColors = FinancialColors(
    income = Color(0xFF5CC894),             // High-contrast jade
    incomeContainer = Color(0xFF173629),
    onIncomeContainer = Color(0xFFC4F0DA),
    debit = Color(0xFFF0755E),              // Warm vermilion
    debitContainer = Color(0xFF3D1F1D),
    onDebitContainer = Color(0xFFFAD4CC),
    warning = Color(0xFFF2B249),            // Clear amber
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

// Thermal paper preview tokens
val ThermalPaperCream = Color(0xFFFAFBFD)
val ThermalPaperInk = Color(0xFF172033)
val ThermalPaperMutedInk = Color(0xFF475467)
