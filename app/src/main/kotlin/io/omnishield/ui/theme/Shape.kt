@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

/**
 * Shape tokens.
 *
 * Two layers, matching the two shape systems Material 3 Expressive uses:
 *
 * 1. [OmniShapes] is the corner-based [Shapes] set that every `Card`, `Button`, text field and
 *    sheet resolves through `MaterialTheme.shapes`. The radii are pulled rounder than the M3
 *    baseline (4/8/12/16/28 dp) toward the softer, more organic Expressive feel — the containers
 *    read as pebbles rather than boxes — while staying a continuous scale so nested surfaces still
 *    step correctly.
 *
 * 2. The [MaterialShapes]-based tokens below are the *polygon* shape system — the 40-odd organic
 *    shapes and the morphs between them. The shield hero and the onboarding badge both draw from
 *    here; defining the shapes and the morph once keeps the two screens in agreement and gives the
 *    morph a single name instead of an inline `Morph(...)` at each call site.
 */
val OmniShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * The shield's shape morph: a nine-sided cookie at rest, opening into a sun when protection is on.
 * State is carried by geometry rather than a colour swap — see `ShieldBadge` in DashboardScreen.
 */
val ShieldMorph: Morph = Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny)

/** The onboarding hero badge — the same cookie the shield rests as, so the two read as one family. */
val HeroPolygon = MaterialShapes.Cookie9Sided
