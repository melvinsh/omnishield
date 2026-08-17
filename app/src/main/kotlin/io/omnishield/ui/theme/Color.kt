@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme

/**
 * The static colour palettes, used only below API 31.
 *
 * On Android 12+ the palette is Material You — extracted from the user's wallpaper by the
 * platform — and neither of these is consulted. That is deliberate and is the whole point of
 * Material You: the app inherits the system's tonal palettes rather than imposing a brand
 * colour. Hard-coding a palette on a device that supports dynamic colour is the most common way
 * apps get M3 wrong, so these exist purely as the pre-12 fallback.
 *
 * Both are Google-authored schemes rather than hand-picked role colours: every
 * container/on-container pair and surface tint arrives with its contrast already guaranteed, and
 * eyeballing the 40-odd roles by hand reliably produces contrast failures.
 *
 * The asymmetry is intentional and unavoidable: light uses `expressiveLightColorScheme()`, but
 * Material still ships no `expressiveDarkColorScheme()` (verified absent in material3
 * 1.5.0-alpha26), so dark falls back to the baseline `darkColorScheme()`. Devices on API 31+
 * never see either, so this only affects Android 10–11.
 */
fun omniLightColorScheme(): ColorScheme = expressiveLightColorScheme()

fun omniDarkColorScheme(): ColorScheme = darkColorScheme()
