package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Typography Scale & Font Architecture for Small Trader (التاجر الصغير).
 *
 * Canonical Font: IBM Plex Sans Arabic (400 Regular, 500 Medium, 600 SemiBold, 700 Bold).
 *
 * Design Constraints:
 * 1. Primary Font: Official IBM Plex Sans Arabic distribution, licensed under SIL OFL 1.1.
 * 2. Arabic Microtype Guard: Minimum font size 12sp; never render Arabic text below 12sp
 *    to preserve diacritics, open glyph counters, and legibility on mobile viewports.
 * 3. Tabular Lining Numerals: fontFeatureSettings = "tnum" aligns monetary figures, quantities,
 *    and balances vertically in Western European digits (0–9).
 * 4. Technical Identifiers: MonoFontFamily (JetBrains Mono) is strictly reserved for technical
 *    barcodes (EAN-13), voucher codes, and thermal column receipts.
 * 5. Caller Compatibility: CairoFontFamily and MonoFontFamily are preserved as public symbols
 *    so existing callers and tests remain fully compatible.
 */

const val TabularNumsFeature = "tnum"

val IbmPlexSansArabicFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold)
)

// Compatibility for the initial prototype spelling.
val IbmpPlexSansArabicFontFamily = IbmPlexSansArabicFontFamily
val DefaultAppFontFamily = IbmPlexSansArabicFontFamily

// Public compatibility aliases for legacy callers and tests
val CairoFontFamily = FontFamily(
    Font(R.font.cairo, FontWeight.Normal),
    Font(R.font.cairo, FontWeight.Medium),
    Font(R.font.cairo, FontWeight.SemiBold),
    Font(R.font.cairo, FontWeight.Bold)
)

val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    displayMedium = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    headlineLarge = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    headlineMedium = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    headlineSmall = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    titleLarge = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    titleMedium = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    titleSmall = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    bodyLarge = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    bodyMedium = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    bodySmall = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    labelLarge = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    labelMedium = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TabularNumsFeature
    ),
    labelSmall = TextStyle(
        fontFamily = DefaultAppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TabularNumsFeature
    )
)
