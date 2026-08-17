@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Material 3 Expressive theme with Material You dynamic colour.
 *
 * Three deliberate choices:
 *
 * 1. **Dynamic colour is the default on Android 12+.** That is what Material You *is* — the
 *    palette is extracted from the user's wallpaper by the platform, so the app inherits the
 *    system's tonal palettes rather than imposing a brand colour. Hard-coding a palette on a
 *    device that supports dynamic colour is the single most common way apps get M3 wrong.
 *
 * 2. **The static fallbacks are Google-authored.** Below API 31 the palettes come from
 *    [omniLightColorScheme]/[omniDarkColorScheme] — see `Color.kt` for why they are the
 *    Expressive/baseline schemes rather than hand-picked roles, and for the light/dark asymmetry.
 *
 * 3. **The token layer.** Typography stays the Expressive default (see `Type.kt`); shapes come
 *    from [OmniShapes] (see `Shape.kt`), which pulls corners rounder than the baseline for the
 *    Expressive feel.
 *
 * 4. **Expressive motion.** [MotionScheme.expressive] swaps the standard spring set for the
 *    faster, springier one that defines the Expressive look; it is what makes state changes
 *    feel elastic rather than linear.
 */
@Composable
fun OmniShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Set false to force the static fallback palette, e.g. for screenshot tests. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> omniDarkColorScheme()
        else -> omniLightColorScheme()
    }

    // Edge-to-edge is enabled by the Activity; the bar *icons* still have to be inverted to
    // stay legible against whichever surface ends up behind them.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = OmniShapes,
        content = content,
    )
}
