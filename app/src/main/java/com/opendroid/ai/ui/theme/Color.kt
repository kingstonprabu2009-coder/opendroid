package com.opendroid.ai.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Jarvis color palette — resolved via CompositionLocal so all screens
 * automatically adapt to the active theme (light / dark).
 */
data class OpenDroidColors(
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentNeonGreen: Color,
    /** Calmer green for large filled button surfaces (neon is for thin marks only). */
    val accentGreenButton: Color,
    val accentPurple: Color,
    val accentCyan: Color,
    val accentRed: Color,
    val accentOrange: Color = Color(0xFFFF9500),
    val isDark: Boolean
)

// ── Dark palette: Jarvis neon-blue on black ───────────────
val DarkPalette = OpenDroidColors(
    background = Color(0xFF02060D),      // Deep space black-blue
    surface = Color(0xFF07121F),         // Dark navy surface
    cardBackground = Color(0xFF0B1B2E),  // Elevated navy card
    borderColor = Color(0xFF0E4F7A),     // Blue glow border
    textPrimary = Color(0xFFD6F6FF),     // Icy white-blue text
    textSecondary = Color(0xFF6FA8C7),   // Soft steel blue
    accentNeonGreen = Color(0xFF00D4FF), // Neon blue (Jarvis)
    accentGreenButton = Color(0xFF00B8E6), // Neon blue buttons
    accentPurple = Color(0xFF7C6CFF),    // Soft electric violet
    accentCyan = Color(0xFF22E5FF),      // Bright cyan glow
    accentRed = Color(0xFFFF4D4D),       // Alert red
    accentOrange = Color(0xFFFF9500),
    isDark = true
)

// ── Light palette: Classic Pure White (#FFFFFF) ─────────────
val LightPalette = OpenDroidColors(
    background = Color(0xFFFFFFFF),      // Classic pure white
    surface = Color(0xFFF8F9FA),         // Crisp light surface
    cardBackground = Color(0xFFFFFFFF),  // Pure white card
    borderColor = Color(0xFFE4E4E7),     // Zinc 200 hairline border
    textPrimary = Color(0xFF09090B),     // Deep obsidian black
    textSecondary = Color(0xFF71717A),   // Zinc 500 slate
    accentNeonGreen = Color(0xFF09090B), // High-contrast monochrome (replaces green)
    accentGreenButton = Color(0xFF09090B), // Crisp black button
    accentPurple = Color(0xFF7E22CE),    // Purple
    accentCyan = Color(0xFF0284C7),      // Ocean sapphire
    accentRed = Color(0xFFDC2626),       // Clean alert red
    accentOrange = Color(0xFFD97706),
    isDark = false
)

val LocalOpenDroidColors = compositionLocalOf { DarkPalette }

/** Access the active palette from any @Composable */
object AppTheme {
    val colors: OpenDroidColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenDroidColors.current
}

// ── Dynamic Composable top-level aliases ────────────────────
// These allow all existing screens and composables to automatically
// adapt to Light / Dark theme dynamically without hardcoding DarkPalette!

val DarkBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.background

val DarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.surface

val CardBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.cardBackground

val BorderColor: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.borderColor

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textPrimary

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.textSecondary

val AccentNeonGreen: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentNeonGreen

val AccentGreenButton: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentGreenButton

val AccentPurple: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentPurple

val AccentCyan: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentCyan

val AccentRed: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentRed

val AccentOrange: Color
    @Composable
    @ReadOnlyComposable
    get() = AppTheme.colors.accentOrange
// END
