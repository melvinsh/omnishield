@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.TunnelStatus

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onConnectRequested: () -> Unit,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        ShieldBadge(
            running = state.isRunning && !state.isPaused,
            blocked = state.stats.dnsBlocked,
        )

        Text(
            text = when {
                state.status is TunnelStatus.Failed -> stringResource(R.string.status_failed)
                state.isPaused -> stringResource(R.string.status_paused)
                state.isRunning -> stringResource(R.string.status_protected)
                state.status is TunnelStatus.Starting -> stringResource(R.string.status_starting)
                else -> stringResource(R.string.status_stopped)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.stats.dnsTotal > 0) {
            Text(
                text = stringResource(
                    R.string.block_rate,
                    state.stats.blockRate,
                    state.stats.dnsTotal,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // A failure that renders as plain "not protected" is indistinguishable from the user
        // simply not having connected, so the reason is shown explicitly.
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

        TonalToggleButton(
            checked = state.isRunning,
            onCheckedChange = { onConnectRequested() },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 4.dp),
        ) {
            Text(
                text = if (state.isRunning) {
                    stringResource(R.string.action_disconnect)
                } else {
                    stringResource(R.string.action_connect)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (state.isRunning) {
            PauseControls(
                paused = state.isPaused,
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
            StatTile(
                label = stringResource(R.string.stat_firewalled),
                value = formatCount(state.stats.connsBlocked),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.stat_filter_rules),
                value = formatCount(state.filterRules.toLong()),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stat_dns_queries),
                value = formatCount(state.stats.dnsTotal),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        SettingRow(
            title = stringResource(R.string.setting_filtering),
            subtitle = stringResource(R.string.setting_filtering_sub),
            checked = state.settings.filteringEnabled,
            onChange = viewModel::setFiltering,
        )
        SettingRow(
            title = stringResource(R.string.setting_quic),
            subtitle = stringResource(R.string.setting_quic_sub),
            checked = state.settings.blockQuic,
            onChange = viewModel::setBlockQuic,
        )
    }
}

@Composable
private fun PauseControls(paused: Boolean, onPause: (Int) -> Unit, onResume: () -> Unit) {
    if (paused) {
        TextButton(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        listOf(5 to "5m", 30 to "30m", 60 to "1h").forEach { (minutes, label) ->
            OutlinedButton(onClick = { onPause(minutes) }) { Text(label) }
        }
    }
    Text(
        text = stringResource(R.string.pause_title),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
 * [MaterialShapes] as protection turns on and off.
 *
 * This is the defining gesture of Material 3 Expressive. The system ships a catalogue of
 * non-rectangular shapes and a `Morph` that interpolates between any two of them, so state
 * changes are carried by geometry rather than by a colour swap on yet another rounded
 * rectangle. Interpolation runs on a low-stiffness spring so it visibly overshoots and settles.
 */
@Composable
private fun ShieldBadge(running: Boolean, blocked: Long) {
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }
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
    val description = stringResource(R.string.status_protected)

    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(240.dp)
            .clip(MorphShape(morph, progress))
            .background(container)
            .semantics { contentDescription = "$blocked $description" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatCount(blocked),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = onContainer,
        )
    }
}

/**
 * Adapts a [Morph] to a Compose [Shape].
 *
 * The morph path is produced in the polygon's own coordinate space, so it is measured and then
 * uniformly scaled and centred into whatever box the caller gave us. Deriving the scale from
 * the path's actual bounds — rather than assuming a normalised 0..1 box — keeps the shape
 * correct for every entry in the catalogue, including the asymmetric ones.
 *
 * A `data class` on purpose. `Modifier.clip` caches the outline it last built and only rebuilds
 * when the `Shape` compares unequal; as a plain class every recomposition produced an instance
 * that was never equal to the previous one, so a fresh `Morph.toPath` plus a `Matrix` plus a
 * full path transform ran on every stats tick even when `progress` had not moved at all.
 */
private data class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = morph.toPath(progress)
        val bounds = path.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) return Outline.Generic(path)

        val scale = minOf(size.width / bounds.width, size.height / bounds.height)
        val matrix = Matrix().apply {
            translate(
                x = (size.width - bounds.width * scale) / 2f - bounds.left * scale,
                y = (size.height - bounds.height * scale) / 2f - bounds.top * scale,
            )
            scale(scale, scale)
        }
        path.transform(matrix)
        return Outline.Generic(path)
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
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
}

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
