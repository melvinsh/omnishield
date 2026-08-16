package io.omnishield.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.BuildConfig
import io.omnishield.R
import io.omnishield.data.FilterRepository
import io.omnishield.data.TunnelStatus
import io.omnishield.data.TunnelRepository
import io.omnishield.data.UpstreamMode
import io.omnishield.ui.components.ConfirmDialog
import io.omnishield.ui.components.LocalSnackbar
import io.omnishield.ui.components.OnResume
import io.omnishield.ui.components.ScreenScaffold
import io.omnishield.ui.components.TextInputDialog
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overrides by viewModel.allowlist.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val refresh by viewModel.refreshState.collectAsStateWithLifecycle()
    val filterRules by viewModel.filterRules.collectAsStateWithLifecycle()
    val status by TunnelRepository.status.collectAsStateWithLifecycle()

    // Granting the exemption happens in system Settings, so the answer can change while this
    // screen is in the background. It used to be read once per composition scope and cached
    // for the life of the process.
    var batteryExempt by remember { mutableStateOf(false) }
    OnResume {
        batteryExempt = isIgnoringBatteryOptimizations(context)
        viewModel.reloadLists()
    }

    var confirmClear by remember { mutableStateOf(false) }
    var editResolver by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.title_settings),
        modifier = modifier,
    ) { inner ->
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()

        // Resolved in composition — a message built from a Context captured inside the effect
        // would not follow a configuration change — then delivered once and handed back, so a
        // rotation does not replay it.
        val refreshMessage: String? = when (val r = refresh) {
            is RefreshState.Done -> pluralStringResource(
                if (status is TunnelStatus.Running) {
                    R.plurals.settings_lists_result_running
                } else {
                    R.plurals.settings_lists_result
                },
                r.count,
                r.count,
            )

            RefreshState.Failed -> stringResource(R.string.settings_lists_failed)
            else -> null
        }
        LaunchedEffect(refreshMessage) {
            if (refreshMessage != null) {
                snackbar.showSnackbar(refreshMessage)
                viewModel.refreshHandled()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
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
                // Was a static label. The repository has always had setters for both fields,
                // so the resolver was configurable everywhere except in the interface.
                ActionRow(
                    title = stringResource(R.string.settings_resolver),
                    subtitle = if (settings.upstreamMode == UpstreamMode.DOH) {
                        settings.dohUrl
                    } else {
                        settings.upstreamUdp
                    },
                    subtitleMonospace = true,
                    enabled = true,
                    onClick = { editResolver = true },
                )
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_block_dot),
                    subtitle = stringResource(R.string.settings_block_dot_sub),
                    checked = settings.blockDot,
                    onChange = viewModel::setBlockDot,
                )
            }

            FilterListSection(
                lists = lists,
                ruleCount = filterRules,
                refreshing = refresh == RefreshState.Running,
                onRefresh = viewModel::refreshLists,
            )

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
                    subtitle = if (batteryExempt) {
                        stringResource(R.string.settings_battery_done)
                    } else {
                        stringResource(R.string.settings_battery_sub)
                    },
                    enabled = !batteryExempt,
                    onClick = { requestIgnoreBatteryOptimizations(context) },
                )
            }

            // Shown even when empty. Hiding it entirely meant the feature was discoverable
            // only by tapping a log row and noticing what happened.
            Section(stringResource(R.string.settings_overrides)) {
                if (overrides.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_overrides_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                overrides.forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider()
                    val removed = stringResource(R.string.settings_override_removed, rule.domain)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.domain,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = if (rule.allow) {
                                    stringResource(R.string.settings_override_allowed)
                                } else {
                                    stringResource(R.string.settings_override_blocked)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rule.allow) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        TextButton(onClick = {
                            viewModel.clearOverride(rule.domain)
                            scope.launch { snackbar.showSnackbar(removed) }
                        }) {
                            Text(stringResource(R.string.domain_clear_override))
                        }
                    }
                }
            }

            Section(stringResource(R.string.settings_data)) {
                ActionRow(
                    title = stringResource(R.string.settings_clear_log),
                    subtitle = null,
                    enabled = true,
                    onClick = { confirmClear = true },
                )
            }

            AboutSection(onShowIntro = viewModel::replayOnboarding, context = context)
        }

        if (confirmClear) {
            val cleared = stringResource(R.string.log_cleared)
            ConfirmDialog(
                title = stringResource(R.string.log_clear_title),
                body = stringResource(R.string.settings_clear_log_body),
                confirmLabel = stringResource(R.string.log_clear),
                onConfirm = {
                    viewModel.clearHistory()
                    scope.launch { snackbar.showSnackbar(cleared) }
                },
                onDismiss = { confirmClear = false },
            )
        }

        if (editResolver) {
            val doh = settings.upstreamMode == UpstreamMode.DOH
            val saved = stringResource(R.string.settings_resolver_saved)
            ResolverDialog(
                doh = doh,
                initial = if (doh) settings.dohUrl else settings.upstreamUdp,
                onSave = { value ->
                    if (doh) viewModel.setDohUrl(value) else viewModel.setUpstreamUdp(value)
                    scope.launch { snackbar.showSnackbar(saved) }
                },
                onDismiss = { editResolver = false },
            )
        }
    }
}

@Composable
private fun FilterListSection(
    lists: List<FilterRepository.ListStatus>,
    ruleCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    Section(stringResource(R.string.settings_lists)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_lists_sub, formatCount(ruleCount.toLong())),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(20.dp))
            } else {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.settings_refresh_lists))
                }
            }
        }
        lists.forEach { list ->
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Text(list.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (list.downloaded) {
                        stringResource(
                            R.string.settings_lists_updated,
                            Formatter.formatShortFileSize(context, list.bytes),
                            relativeTime(list.updatedAt),
                        )
                    } else {
                        stringResource(R.string.settings_lists_never)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "just now" under a minute, the platform's relative wording after that.
 *
 * `DateUtils.getRelativeTimeSpanString` renders anything under a minute as "0 minutes ago",
 * which reads like a bug immediately after a refresh — the one moment a user is most likely to
 * be looking at this row.
 */
@Composable
private fun relativeTime(at: Long): CharSequence {
    val age = System.currentTimeMillis() - at
    return if (age < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.settings_lists_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(at)
    }
}

@Composable
private fun AboutSection(onShowIntro: () -> Unit, context: Context) {
    Section(stringResource(R.string.settings_about)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_version),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        val url = stringResource(R.string.settings_source_url)
        ActionRow(
            title = stringResource(R.string.settings_source),
            subtitle = url,
            enabled = true,
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
        HorizontalDivider()
        // The honest limits were documented in the source and the README and nowhere the user
        // could see them, which is the wrong way round for a tool whose value is trust.
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_limits_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.settings_limits_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        ActionRow(
            title = stringResource(R.string.settings_show_intro),
            subtitle = null,
            enabled = true,
            onClick = onShowIntro,
        )
    }
}

/**
 * Resolver editor.
 *
 * Both forms reject a hostname. `core/src/doh.rs` addresses the resolver by IP literal on
 * purpose — resolving the resolver's own name would need the DNS that is being set up — so a
 * hostname here would produce a tunnel that comes up and then cannot resolve anything.
 */
@Composable
private fun ResolverDialog(
    doh: Boolean,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val error = validateResolver(value, doh)?.let { stringResource(it) }

    TextInputDialog(
        title = stringResource(R.string.settings_resolver_edit),
        label = stringResource(
            if (doh) R.string.settings_resolver_doh_hint else R.string.settings_resolver_udp_hint
        ),
        supporting = stringResource(R.string.settings_resolver_note),
        value = value,
        error = if (value.isBlank()) null else error,
        onValueChange = { value = it },
        onConfirm = { onSave(value.trim()); onDismiss() },
        onDismiss = onDismiss,
    )
}

/**
 * Null when [value] is usable, otherwise the string resource explaining why not.
 *
 * Pure, so the accept/reject boundary can be asserted without a device.
 */
internal fun validateResolver(value: String, doh: Boolean): Int? {
    val v = value.trim()
    if (v.isEmpty()) return null
    return if (doh) {
        val host = runCatching { Uri.parse(v) }.getOrNull()?.takeIf { it.scheme == "https" }?.host
        if (host != null && isIpLiteral(host)) null else R.string.settings_resolver_invalid_url
    } else {
        if (isIpLiteral(v)) null else R.string.settings_resolver_invalid_ip
    }
}

/** IPv4 dotted quad or anything with a colon that parses as IPv6. Deliberately strict. */
private fun isIpLiteral(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare.contains(':')) {
        return bare.all { it.isDigit() || it in "abcdefABCDEF:." } && bare.count { it == ':' } >= 2
    }
    val parts = bare.split('.')
    return parts.size == 4 && parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
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
    subtitleMonospace: Boolean = false,
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
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (subtitleMonospace) FontFamily.Monospace else null,
                )
            }
        }
        // Without this a tappable row is indistinguishable from a label, which is what the
        // resolver was: a row that looked purely informational and opened an editor.
        if (enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
