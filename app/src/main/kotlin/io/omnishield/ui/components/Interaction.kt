@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui.components

import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Tactile press feedback: the element scales down slightly while held, on the theme's Expressive
 * spring, and springs back on release.
 *
 * Pass the *same* [InteractionSource] you give the element's `clickable`/`toggleable`, so the
 * scale tracks the real press rather than a second, invented one:
 *
 * ```
 * val press = remember { MutableInteractionSource() }
 * Modifier.pressScale(press).clickable(interactionSource = press, indication = ..., onClick = ...)
 * ```
 *
 * The scale spring comes from [MaterialTheme.motionScheme] so it matches the rest of the app's
 * motion. When the system's animation scale is 0 (the "remove animations" accessibility setting),
 * this is a no-op — the element still presses, it just does not move.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    if (!animationsEnabled()) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Whether decorative animations should run, read once from the system animator duration scale.
 *
 * A user who has turned animations off (Accessibility → Remove animations, or Developer options)
 * sets this to 0; honouring it keeps the expressive flourishes from fighting that choice. Read in
 * a `remember` so it is a single `ContentResolver` lookup, never a per-frame call.
 */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
