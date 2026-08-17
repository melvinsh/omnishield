@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset

/**
 * A live number: the old value slides out upward as the new one slides in from below, on the
 * theme's Expressive motion. Values that merely recompose without changing do not animate —
 * [AnimatedContent] keys on the string itself.
 *
 * When the system animation scale is 0, the swap is a snap, matching the tab transition in
 * `MainActivity`; the number is still live, it just does not travel.
 */
@Composable
fun AnimatedCount(
    value: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val animate = animationsEnabled()
    // Read in composition: transitionSpec runs outside it.
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val slide = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            if (animate) {
                (slideInVertically(slide) { it / 2 } + fadeIn(fade))
                    .togetherWith(slideOutVertically(slide) { -it / 2 } + fadeOut(fade))
                    // The digits may change width; let neighbours reflow instead of clipping
                    // the travelling glyphs.
                    .using(SizeTransform(clip = false))
            } else {
                fadeIn(snap()) togetherWith fadeOut(snap())
            }
        },
        label = "count",
        modifier = modifier,
    ) { current ->
        Text(text = current, style = style, color = color)
    }
}
