package io.omnishield.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.UpstreamMode

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overrides by viewModel.allowlist.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.settings_dns_title)) {
            ToggleRow(
                title = stringResource(R.string.settings_doh),
                subtitle = stringResource(R.string.settings_doh_sub),
                checked = settings.upstreamMode == UpstreamMode.DOH,
                onChange = { on ->
                    viewModel.setUpstreamMode(if (on) UpstreamMode.DOH else UpstreamMode.UDP)
                },
            )
            HorizontalDivider()
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_resolver),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (settings.upstreamMode == UpstreamMode.DOH) {
                        settings.dohUrl
                    } else {
                        settings.upstreamUdp
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            ToggleRow(
                title = stringResource(R.string.settings_block_dot),
                subtitle = stringResource(R.string.settings_block_dot_sub),
                checked = settings.blockDot,
                onChange = viewModel::setBlockDot,
            )
        }

        Section(stringResource(R.string.settings_general)) {
            ToggleRow(
                title = stringResource(R.string.settings_start_on_boot),
                subtitle = stringResource(R.string.settings_start_on_boot_sub),
                checked = settings.startOnBoot,
                onChange = viewModel::setStartOnBoot,
            )
            HorizontalDivider()
            ActionRow(
                title = stringResource(R.string.settings_battery),
                subtitle = stringResource(R.string.settings_battery_sub),
                // Remembered per composition rather than called inline: this is a binder
                // round trip to PowerManager, and it was running on every recomposition of a
                // screen that recomposes whenever any setting changes.
                enabled = !remember(context) { isIgnoringBatteryOptimizations(context) },
                onClick = { requestIgnoreBatteryOptimizations(context) },
            )
        }

        if (overrides.isNotEmpty()) {
            Section("Domain overrides") {
                overrides.forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.domain,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = if (rule.allow) "always allowed" else "always blocked",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rule.allow) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        TextButton(onClick = { viewModel.clearOverride(rule.domain) }) {
                            Text(stringResource(R.string.domain_clear_override))
                        }
                    }
                }
            }
        }

        Section(stringResource(R.string.settings_data)) {
            ActionRow(
                title = stringResource(R.string.settings_clear_log),
                subtitle = null,
                enabled = true,
                onClick = viewModel::clearHistory,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(content = { content() })
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
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

@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

/**
 * The documented mitigation for the system suspending a long-running foreground service.
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared in the manifest and was previously
 * never used.
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
