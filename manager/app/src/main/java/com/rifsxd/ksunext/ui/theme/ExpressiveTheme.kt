package com.rifsxd.ksunext.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark, expressive-feeling palette. These tokens are a step darker than the
// stock Material-3 dark palette so a bottom-sheet surface reads as "dark" even
// on devices whose system theme is light. The primary / tertiary accents pick
// up Expressive's purple family so Grant/Deny pills still pop.
private val EXPRESSIVE_PRIMARY = Color(0xFFD0BCFF)
private val EXPRESSIVE_ON_PRIMARY = Color(0xFF381E72)
private val EXPRESSIVE_PRIMARY_CONTAINER = Color(0xFF4F378B)
private val EXPRESSIVE_ON_PRIMARY_CONTAINER = Color(0xFFEADDFF)

private val EXPRESSIVE_SECONDARY = Color(0xFFCCC2DC)
private val EXPRESSIVE_ON_SECONDARY = Color(0xFF332D41)
private val EXPRESSIVE_SECONDARY_CONTAINER = Color(0xFF4A4458)
private val EXPRESSIVE_ON_SECONDARY_CONTAINER = Color(0xFFE8DEF8)

private val EXPRESSIVE_TERTIARY = Color(0xFFEFB8C8)
private val EXPRESSIVE_ON_TERTIARY = Color(0xFF492532)
private val EXPRESSIVE_TERTIARY_CONTAINER = Color(0xFF633B48)
private val EXPRESSIVE_ON_TERTIARY_CONTAINER = Color(0xFFFFD8E4)

private val EXPRESSIVE_ERROR = Color(0xFFF2B8B5)
private val EXPRESSIVE_ON_ERROR = Color(0xFF601410)
private val EXPRESSIVE_ERROR_CONTAINER = Color(0xFF8C1D18)
private val EXPRESSIVE_ON_ERROR_CONTAINER = Color(0xFFF9DEDC)

private val EXPRESSIVE_BACKGROUND = Color(0xFF141218)
private val EXPRESSIVE_ON_BACKGROUND = Color(0xFFE6E0E9)
private val EXPRESSIVE_SURFACE = Color(0xFF141218)
private val EXPRESSIVE_ON_SURFACE = Color(0xFFE6E0E9)
private val EXPRESSIVE_SURFACE_VARIANT = Color(0xFF49454F)
private val EXPRESSIVE_ON_SURFACE_VARIANT = Color(0xFFCAC4D0)
private val EXPRESSIVE_OUTLINE = Color(0xFF938F99)
private val EXPRESSIVE_OUTLINE_VARIANT = Color(0xFF49454F)

private val EXPRESSIVE_SURFACE_CONTAINER_LOWEST = Color(0xFF0F0D13)
private val EXPRESSIVE_SURFACE_CONTAINER_LOW = Color(0xFF1D1B20)
private val EXPRESSIVE_SURFACE_CONTAINER = Color(0xFF211F26)
private val EXPRESSIVE_SURFACE_CONTAINER_HIGH = Color(0xFF2B2930)
private val EXPRESSIVE_SURFACE_CONTAINER_HIGHEST = Color(0xFF36343B)

private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = EXPRESSIVE_PRIMARY,
    onPrimary = EXPRESSIVE_ON_PRIMARY,
    primaryContainer = EXPRESSIVE_PRIMARY_CONTAINER,
    onPrimaryContainer = EXPRESSIVE_ON_PRIMARY_CONTAINER,
    secondary = EXPRESSIVE_SECONDARY,
    onSecondary = EXPRESSIVE_ON_SECONDARY,
    secondaryContainer = EXPRESSIVE_SECONDARY_CONTAINER,
    onSecondaryContainer = EXPRESSIVE_ON_SECONDARY_CONTAINER,
    tertiary = EXPRESSIVE_TERTIARY,
    onTertiary = EXPRESSIVE_ON_TERTIARY,
    tertiaryContainer = EXPRESSIVE_TERTIARY_CONTAINER,
    onTertiaryContainer = EXPRESSIVE_ON_TERTIARY_CONTAINER,
    error = EXPRESSIVE_ERROR,
    onError = EXPRESSIVE_ON_ERROR,
    errorContainer = EXPRESSIVE_ERROR_CONTAINER,
    onErrorContainer = EXPRESSIVE_ON_ERROR_CONTAINER,
    background = EXPRESSIVE_BACKGROUND,
    onBackground = EXPRESSIVE_ON_BACKGROUND,
    surface = EXPRESSIVE_SURFACE,
    onSurface = EXPRESSIVE_ON_SURFACE,
    surfaceVariant = EXPRESSIVE_SURFACE_VARIANT,
    onSurfaceVariant = EXPRESSIVE_ON_SURFACE_VARIANT,
    outline = EXPRESSIVE_OUTLINE,
    outlineVariant = EXPRESSIVE_OUTLINE_VARIANT,
    surfaceContainerLowest = EXPRESSIVE_SURFACE_CONTAINER_LOWEST,
    surfaceContainerLow = EXPRESSIVE_SURFACE_CONTAINER_LOW,
    surfaceContainer = EXPRESSIVE_SURFACE_CONTAINER,
    surfaceContainerHigh = EXPRESSIVE_SURFACE_CONTAINER_HIGH,
    surfaceContainerHighest = EXPRESSIVE_SURFACE_CONTAINER_HIGHEST,
)

// Expressive-feeling type scale. The real material3-expressive artifact adds
// *Emphasized variants; while we stay on compose-bom 2025.07.00 the closest
// legal substitute is bumping weights on the type styles the SU-request sheet
// uses (headline, title, label).
private val ExpressiveTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

// Expressive shape set: notably larger corner radii than stock M3.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Force-dark Material-3 theme used by SuRequestActivity.
 *
 * The regular [KernelSUTheme] follows the system dark-mode flag, which makes
 * the SU-request sheet render as a near-white bottom sheet on devices in system
 * light mode — the "too bright" popup. This wrapper hard-codes the dark
 * palette, nudges every surface tone one step darker, and swaps in an
 * Expressive-feeling type scale and shape set so the sheet reads as the "M3
 * Expressive dark" look the user asked for.
 *
 * When [dynamicColor] is true and the device supports it (Android 12+) the
 * palette is dynamically derived from the wallpaper (always dark variant);
 * otherwise the static Expressive dark palette is used.
 *
 * When [amoledMode] is true, pure-black is substituted into every surface so
 * OLED panels can pixel-off the background.
 */
@Composable
fun KernelSUExpressiveDarkTheme(
    dynamicColor: Boolean = true,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(LocalContext.current)
        }
        else -> ExpressiveDarkColorScheme
    }

    // Nudge every surface tone a step darker than the default dark scheme so
    // the bottom sheet reads as DARK, even against the dynamic-color's lighter
    // surfaceContainerLow.
    val tuned = base.copy(
        surface = base.surfaceContainerLow,
        surfaceContainerLowest = base.surface,
        surfaceContainerLow = base.surfaceContainer,
        surfaceContainer = base.surfaceContainerHigh,
        surfaceContainerHigh = base.surfaceContainerHighest,
        surfaceContainerHighest = base.surfaceContainerHighest.blend(Color.Black, 0.25f),
    )

    val finalScheme = if (amoledMode) {
        tuned.copy(
            background = AMOLED_BLACK,
            surface = AMOLED_BLACK,
            surfaceContainerLowest = AMOLED_BLACK,
            surfaceContainerLow = tuned.surfaceContainerLow.blend(AMOLED_BLACK, 0.75f),
            surfaceContainer = tuned.surfaceContainer.blend(AMOLED_BLACK, 0.75f),
            surfaceContainerHigh = tuned.surfaceContainerHigh.blend(AMOLED_BLACK, 0.6f),
            surfaceContainerHighest = tuned.surfaceContainerHighest.blend(AMOLED_BLACK, 0.6f),
        )
    } else {
        tuned
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
