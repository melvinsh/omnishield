@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The snackbar host of the screen currently in composition.
 *
 * A composition local rather than a parameter on every screen: acknowledging an action is
 * something almost every control here needs to do, and threading a `SnackbarHostState` through
 * five screens and their row composables would put plumbing in every signature. Reading it is
 * an error outside [ScreenScaffold], which is deliberate — a snackbar with no host silently
 * does nothing, and silence is the failure this whole pass is trying to remove.
 */
val LocalSnackbar = compositionLocalOf<SnackbarHostState> {
    error("No SnackbarHostState — wrap the screen in ScreenScaffold")
}

/**
 * How much vertical presence the screen's title bar starts with. Both collapse to the same
 * one-line bar as the content scrolls.
 */
enum class BarStyle {
    /** For screens whose hero lives in the content — the bar defers to it (the dashboard). */
    Medium,

    /** The default: the large Expressive header that carries the screen's identity itself. */
    Large,
}

/**
 * Standard chrome for a top-level screen: a title, a one-line statement of what the screen is
 * for, and somewhere for feedback to appear.
 *
 * Every screen used to be dropped straight into the navigation `Scaffold`'s content slot with
 * no bar of any kind, so no screen said what it was — the reported symptom on the firewall
 * page, but true of all five.
 *
 * The bar is a *flexible* app bar, not the one-line `TopAppBar`: the expanded display-scale
 * title is the Expressive header treatment, and the subtitle rides in the bar's own subtitle
 * slot so the two collapse together. The scroll behavior is owned here —
 * `exitUntilCollapsedScrollBehavior`, the M3 pairing for large bars (enter-always would
 * re-expand a header this tall on any upward fling mid-list). `verticalScroll` columns
 * participate through nested scroll exactly like `LazyColumn`s, and content shorter than the
 * viewport simply leaves the bar expanded.
 *
 * The nested `Scaffold` is intentional. The outer one in `MainActivity` owns the navigation bar
 * and the window insets; this one owns only the title bar and the snackbar, so its own insets
 * are zeroed to avoid padding the same system bars twice.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    barStyle: BarStyle = BarStyle.Large,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                val titleSlot: @Composable () -> Unit = { Text(title) }
                // Null when absent, not an empty lambda: the flexible bars size their expanded
                // height from whether a subtitle exists at all.
                val subtitleSlot: (@Composable () -> Unit)? = subtitle?.let {
                    { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                when (barStyle) {
                    BarStyle.Medium -> MediumFlexibleTopAppBar(
                        title = titleSlot,
                        subtitle = subtitleSlot,
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )

                    BarStyle.Large -> LargeFlexibleTopAppBar(
                        title = titleSlot,
                        subtitle = subtitleSlot,
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            content = content,
        )
    }
}

/**
 * Runs [block] each time the screen returns to the foreground.
 *
 * For state that lives outside the app and can change while the user is away in Settings —
 * whether our CA has been installed, whether the battery exemption has been granted. Both were
 * read once and cached for the life of the composition, so the answer stayed wrong until the
 * process restarted. A `remember` cannot fix that and neither can reading it per recomposition,
 * which is a binder call on the frame path.
 */
@Composable
fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
