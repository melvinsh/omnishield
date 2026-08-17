@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.TunnelStatus
import io.omnishield.ui.components.AnimatedCount
import io.omnishield.ui.components.BarStyle
import io.omnishield.ui.components.GroupedColumn
import io.omnishield.ui.components.ScreenScaffold
import io.omnishield.ui.components.animationsEnabled
import io.omnishield.ui.components.pressScale
import io.omnishield.ui.theme.MorphShape
import io.omnishield.ui.theme.ShieldMorph
import java.text.DateFormat
import java.util.Date

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onConnectRequested: () -> Unit,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = stringResource(R.string.title_shield),
        subtitle = stringResource(R.string.subtitle_shield),
        // Medium, not Large: this screen's hero is the badge in the content, and the header
        // must defer to it rather than push the connect button below the fold.
        barStyle = BarStyle.Medium,
        modifier = modifier,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroZone(
                status = state.status,
                running = state.isRunning,
                paused = state.isPaused,
                blocked = state.stats.dnsBlocked,
                rateText = if (state.stats.dnsTotal > 0) {
                    stringResource(
                        R.string.block_rate,
                        state.stats.blockRate,
                        state.stats.dnsTotal,
                    )
                } else {
                    null
                },
            )

            // A failure that renders as plain "not protected" is indistinguishable from the
            // user simply not having connected, so the reason is shown explicitly.
            (state.status as? TunnelStatus.Failed)?.let { failed ->
                Banner(
                    text = failed.reason,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onConnectRequested,
                )
            }

            AnimatedVisibility(visible = state.dohDegraded) {
                Banner(
                    text = stringResource(R.string.doh_degraded),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            ConnectButton(
                running = state.isRunning,
                starting = state.status is TunnelStatus.Starting,
                onClick = onConnectRequested,
            )

            if (state.isRunning) {
                PauseControls(
                    paused = state.isPaused,
                    pausedUntil = state.settings.pausedUntil,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = stringResource(R.string.stat_connections),
                    value = formatCount(state.stats.connsTotal),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                // The error role only when there is something to report. A permanently red
                // tile reading zero spends the strongest colour in the palette on the absence
                // of an event.
                val anyFirewalled = state.stats.connsBlocked > 0
                StatTile(
                    label = stringResource(R.string.stat_firewalled),
                    value = formatCount(state.stats.connsBlocked),
                    container = if (anyFirewalled) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    content = if (anyFirewalled) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = stringResource(R.string.stat_dns_queries),
                    value = formatCount(state.stats.dnsTotal),
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Replaces the filter-rule count, which described the loaded filter rather
                // than any activity and now lives with the lists in Settings. This one is
                // traffic the tunnel answered without going upstream at all.
                StatTile(
                    label = stringResource(R.string.stat_dns_cached),
                    value = formatCount(state.stats.dnsCached),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }

            GroupedColumn {
                item {
                    SettingRow(
                        title = stringResource(R.string.setting_filtering),
                        subtitle = stringResource(R.string.setting_filtering_sub),
                        checked = state.settings.filteringEnabled,
                        onChange = viewModel::setFiltering,
                    )
                }
                item {
                    SettingRow(
                        title = stringResource(R.string.setting_quic),
                        subtitle = stringResource(R.string.setting_quic_sub),
                        checked = state.settings.blockQuic,
                        onChange = viewModel::setBlockQuic,
                    )
                }
            }
        }
    }
}

/**
 * The tinted state zone: the badge, the status line and the block rate share one surface whose
 * *container role* carries the tunnel state — primaryContainer while protecting,
 * tertiaryContainer paused, secondaryContainer starting, plain surfaceContainerLow otherwise.
 * State changes recolour the whole zone rather than only the badge, which is what makes them
 * legible even under a muted wallpaper palette. Both colours animate on the theme's motion so
 * the change reads as one event.
 *
 * The connect button deliberately sits *outside* this zone: the tonal variant's whole rationale
 * (see [ConnectButton]) is how it reads against a bare surface, and container-on-container
 * pairings under arbitrary dynamic palettes are not guaranteed to keep it distinct.
 */
@Composable
private fun HeroZone(
    status: TunnelStatus,
    running: Boolean,
    paused: Boolean,
    blocked: Long,
    rateText: String?,
) {
    val failed = status is TunnelStatus.Failed
    val starting = status is TunnelStatus.Starting
    val protecting = running && !paused
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        targetValue = when {
            protecting -> MaterialTheme.colorScheme.primaryContainer
            running && paused -> MaterialTheme.colorScheme.tertiaryContainer
            starting -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spec,
        label = "heroContainer",
    )
    // Animated alongside the container: Surface's own contentColorFor only resolves exact
    // token values, so mid-animation it would fall back to the outer content colour.
    val content by animateColorAsState(
        targetValue = when {
            protecting -> MaterialTheme.colorScheme.onPrimaryContainer
            running && paused -> MaterialTheme.colorScheme.onTertiaryContainer
            starting -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spec,
        label = "heroContent",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShieldBadge(running = protecting, blocked = blocked)
            // A failure that renders in the zone's quiet colours would read as merely "not
            // connected", so the words get the error role — the reason itself is the banner
            // below the zone.
            Text(
                text = when {
                    failed -> stringResource(R.string.status_failed)
                    paused -> stringResource(R.string.status_paused)
                    running -> stringResource(R.string.status_protected)
                    starting -> stringResource(R.string.status_starting)
                    else -> stringResource(R.string.status_stopped)
                },
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (rateText != null) {
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The primary action.
 *
 * `Starting` is a state of its own rather than an early "Disconnect": establishing the
 * interface and loading filters takes long enough on a cold cache to look like nothing
 * happened, and a button that has already changed its label gives the user nothing to wait on.
 *
 * Sized with the Expressive Large button tokens — the matched `shapesFor` set is what gives the
 * toggle its full square-to-round morph between unchecked, checked and pressed at this scale.
 */
@Composable
private fun ConnectButton(running: Boolean, starting: Boolean, onClick: () -> Unit) {
    TonalToggleButton(
        checked = running,
        onCheckedChange = { onClick() },
        enabled = !starting,
        shapes = ToggleButtonDefaults.shapesFor(ButtonDefaults.LargeContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .heightIn(min = ButtonDefaults.LargeContainerHeight),
    ) {
        if (starting) {
            ContainedLoadingIndicator(Modifier.size(40.dp))
            Text(
                text = stringResource(R.string.action_connecting),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            Text(
                text = if (running) {
                    stringResource(R.string.action_disconnect)
                } else {
                    stringResource(R.string.action_connect)
                },
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        }
    }
}

@Composable
private fun PauseControls(
    paused: Boolean,
    pausedUntil: Long,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
) {
    if (paused) {
        // The deadline is already persisted, so saying when it ends costs nothing and turns
        // "filtering paused" from a state into something the user can plan around.
        val time = remember(pausedUntil) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil))
        }
        Text(
            text = stringResource(R.string.pause_until, time),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
        return
    }

    // Label first: these three buttons used to appear above the only text that said what they
    // were for, so the user met the choices before the question.
    Text(
        text = stringResource(R.string.pause_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // A connected button group rather than three loose OutlinedButtons: the three durations are
    // one related set, and the group gives them the Expressive press interaction where the
    // pressed button swells and its neighbours yield. They are actions, not a selection — each
    // one pauses immediately and the control then swaps to the "paused until…" view above — so a
    // group of clickable items is the honest shape, not a segmented single-select.
    // The item labels are read here, not inside the builder: the ButtonGroup content lambda is a
    // plain DSL scope, not a composable one, so stringResource cannot run in it.
    val label5 = stringResource(R.string.pause_5m)
    val label30 = stringResource(R.string.pause_30m)
    val label60 = stringResource(R.string.pause_1h)
    val moreLabel = stringResource(R.string.pause_more)
    ButtonGroup(
        modifier = Modifier.fillMaxWidth(),
        overflowIndicator = { menuState ->
            FilledIconButton(onClick = { menuState.show() }) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = moreLabel)
            }
        },
    ) {
        // Equal weights, so the three durations divide the row into a full-width band of
        // three rather than hugging their labels at the start edge.
        clickableItem(onClick = { onPause(5) }, label = label5, weight = 1f)
        clickableItem(onClick = { onPause(30) }, label = label30, weight = 1f)
        clickableItem(onClick = { onPause(60) }, label = label60, weight = 1f)
    }
}

@Composable
private fun Banner(
    text: String,
    container: Color,
    content: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * The hero element: the blocked count inside a shape that **morphs** between two
 * [androidx.compose.material3.MaterialShapes] as protection turns on and off — the
 * [ShieldMorph] token, rendered through the shared [MorphShape].
 *
 * This is the defining gesture of Material 3 Expressive. The system ships a catalogue of
 * non-rectangular shapes and a `Morph` that interpolates between any two of them, so state
 * changes are carried by geometry rather than by a colour swap on yet another rounded
 * rectangle. Interpolation runs on a low-stiffness spring so it visibly overshoots and settles.
 *
 * While protection is on, the sun turns — one revolution every 90 seconds, too slow to watch
 * but enough that a glance reads "on". Only the shape layer rotates; the count stays upright.
 * Gated on [animationsEnabled] like every decorative motion here.
 *
 * The number is now labelled. On its own it was a large figure with no unit whose only caption
 * was the status line beneath it — which, when the tunnel was off, read "Not protected" and
 * explained nothing about the number above it.
 */
@Composable
private fun ShieldBadge(running: Boolean, blocked: Long) {
    val progress by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow),
        label = "shieldMorph",
    )
    val container by animateColorAsState(
        targetValue = if (running) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "shieldContainer",
    )
    val onContainer = if (running) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Was `status_protected` regardless of state, so a stopped tunnel announced itself as
    // protected to anyone using TalkBack.
    val description = if (running) {
        stringResource(R.string.cd_shield_running, blocked)
    } else {
        stringResource(R.string.cd_shield_stopped, blocked)
    }

    val angle: Float
    if (running && animationsEnabled()) {
        val idle = rememberInfiniteTransition(label = "shieldIdle")
        angle = idle.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 90_000, easing = LinearEasing)
            ),
            label = "shieldRotation",
        ).value
    } else {
        angle = 0f
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = angle }
                    .clip(MorphShape(ShieldMorph, progress))
                    .background(container)
            )
            AnimatedCount(
                value = formatCount(blocked),
                style = MaterialTheme.typography.displayLargeEmphasized,
                color = onContainer,
            )
        }
        Text(
            text = stringResource(R.string.shield_total_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AnimatedCount(
                value = value,
                style = MaterialTheme.typography.headlineMediumEmphasized,
            )
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A row of a [GroupedColumn] segment — the group supplies the container and the shape. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(press)
            .clickable(
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = { onChange(!checked) },
            )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
