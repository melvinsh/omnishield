package io.omnishield.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Type tokens.
 *
 * The app deliberately does **not** pass a custom [Typography] to the theme: `MaterialExpressiveTheme`
 * installs the Expressive type scale by default, and handing it a plain `Typography()` would quietly
 * swap that for the baseline scale — losing exactly the weights and tracking that make the type feel
 * Expressive. So the roles (`displayLarge`, `titleMedium`, `bodySmall`, …) stay the Expressive
 * defaults, consumed through `MaterialTheme.typography`.
 *
 * What is formalised here is the one thing the screens were each re-deriving by hand: monospace. Log
 * rows, IP/port labels, resolver fields and rule counts all want tabular monospace, and each site was
 * copying a role and setting `FontFamily.Monospace` inline. That lives here now as [OmniMonoFamily] and
 * the [monoBody] / [monoLabel] accessors, so the treatment is defined once.
 */
val OmniMonoFamily: FontFamily = FontFamily.Monospace

/** Monospace body text — for log hostnames, addresses and other data the user reads value-by-value. */
val monoBody: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodyMedium.copy(fontFamily = OmniMonoFamily)

/** Monospace label text — for compact data cells (ports, counts) in a denser row. */
val monoLabel: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.labelLarge.copy(fontFamily = OmniMonoFamily)
