@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package io.omnishield.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.LogEntry
import io.omnishield.ui.components.ConfirmDialog
import io.omnishield.ui.components.EmptyState
import io.omnishield.ui.components.GroupedColumn
import io.omnishield.ui.components.LocalSnackbar
import io.omnishield.ui.components.animationsEnabled
import io.omnishield.ui.components.ScreenScaffold
import io.omnishield.ui.components.pressScale
import io.omnishield.ui.theme.monoBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Live request log, backed by Room so history survives a restart.
 *
 * Tapping a row opens the override sheet — this is the app's escape hatch. Without it, a filter
 * rule that breaks a site leaves the user with no option except turning filtering off entirely.
 */
@Composable
fun LogScreen(modifier: Modifier = Modifier, viewModel: LogViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    ScreenScaffold(
        title = stringResource(R.string.title_log),
        subtitle = stringResource(R.string.subtitle_log),
        modifier = modifier,
        actions = {
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text(stringResource(R.string.log_clear))
            }
        },
    ) { inner ->
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()
        val cleared = stringResource(R.string.log_cleared)

        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.log_search)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery(""); keyboard?.hide() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear_text),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter.blockedOnly,
                    onClick = { viewModel.setBlockedOnly(!filter.blockedOnly) },
                    label = { Text(stringResource(R.string.log_blocked_only)) },
                )
                // `setKind` has existed on the ViewModel from the start with nothing calling
                // it, so filtering by DNS or by connection was implemented but unreachable.
                KindChip(KIND_DNS, R.string.log_kind_dns, filter.kind, viewModel::setKind)
                KindChip(KIND_TCP, R.string.log_kind_tcp, filter.kind, viewModel::setKind)
            }

            Text(
                text = pluralStringResource(R.plurals.log_count, entries.size, entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        text = if (filter.query.isBlank() &&
                            !filter.blockedOnly &&
                            filter.kind.isEmpty()
                        ) {
                            stringResource(R.string.log_empty)
                        } else {
                            stringResource(R.string.log_empty_filtered)
                        },
                    )
                }
            } else {
                // Timestamps are wall-clock only, so without a day break a row from yesterday
                // is indistinguishable from one a minute old.
                val days = remember(entries) { groupByDay(entries) }
                // Fade-in only, no placement animation: rows arrive in batches at the top at
                // up to 2 Hz while someone is watching, and animating every visible row's
                // position on each drain is a permanent wobble, not liveliness.
                val fadeSpec = if (animationsEnabled()) {
                    MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                } else {
                    null
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    days.forEach { (day, rows) ->
                        item(key = "day-$day") { DayHeader(day) }
                        items(rows, key = { it.seq }) { entry ->
                            Column(
                                Modifier.animateItem(
                                    fadeInSpec = fadeSpec,
                                    placementSpec = null,
                                    fadeOutSpec = null,
                                )
                            ) {
                                LogRow(
                                    entry = entry,
                                    override = overrideTarget(entry.name)?.let { overrides[it] },
                                    onClick = { selected = entry },
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                            }
                        }
                    }
                }
            }
        }

        if (confirmClear) {
            ConfirmDialog(
                title = stringResource(R.string.log_clear_title),
                body = pluralStringResource(
                    R.plurals.log_clear_body,
                    entries.size,
                    entries.size,
                ),
                confirmLabel = stringResource(R.string.log_clear),
                onConfirm = {
                    viewModel.clearLog()
                    scope.launch { snackbar.showSnackbar(cleared) }
                },
                onDismiss = { confirmClear = false },
            )
        }

        selected?.let { entry ->
            // The override applies to a *domain*, so it is keyed on the host extracted from the
            // row rather than on the row's label. A TCP row is labelled with an address and has
            // no domain at all; offering to allow "142.250.102.188:5228" would have written a
            // user rule that nothing can ever match and reported success.
            val target = overrideTarget(entry.name)
            DomainOverrideSheet(
                entry = entry,
                target = target,
                override = target?.let { overrides[it] },
                onAllow = { target?.let(viewModel::allow); selected = null },
                onBlock = { target?.let(viewModel::block); selected = null },
                onClear = { target?.let(viewModel::clearOverride); selected = null },
                onDismiss = { selected = null },
            )
        }
    }
}

internal const val KIND_DNS = "dns"
internal const val KIND_TCP = "tcp"

@Composable
private fun KindChip(kind: String, labelRes: Int, current: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = current == kind,
        onClick = { onSelect(if (current == kind) "" else kind) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun DayHeader(dayStart: Long) {
    Text(
        text = dayLabel(dayStart),
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DomainOverrideSheet(
    entry: LogEntry,
    target: String?,
    override: Boolean?,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.log_copied)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            ListItem(
                overlineContent = {
                    if (target != null) Text(stringResource(R.string.log_sheet_title))
                },
                headlineContent = { Text(entry.name, fontFamily = FontFamily.Monospace) },
                supportingContent = {
                    Text(
                        buildString {
                            append(entry.kind.uppercase())
                            if (entry.app.isNotEmpty()) append(" · ${entry.app}")
                            if (entry.rule.isNotEmpty()) append(" · ${entry.rule}")
                        }
                    )
                },
                trailingContent = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(entry.name))
                        scope.launch { snackbar.showSnackbar(copied) }
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                        )
                    }
                },
            )
            Text(
                text = if (target != null) {
                    stringResource(R.string.log_sheet_body, target)
                } else {
                    stringResource(R.string.log_sheet_no_domain)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (target == null) return@Column
            // The actions as one segmented group; transparent items so the group's own
            // surface shows through rather than each ListItem painting the sheet colour.
            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            GroupedColumn(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (override != true) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.domain_allow)) },
                            leadingContent = {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                            },
                            colors = itemColors,
                            modifier = Modifier.clickable(onClick = onAllow),
                        )
                    }
                }
                if (override != false) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.domain_block)) },
                            leadingContent = {
                                Icon(Icons.Filled.Block, contentDescription = null)
                            },
                            colors = itemColors,
                            modifier = Modifier.clickable(onClick = onBlock),
                        )
                    }
                }
                if (override != null) {
                    item {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.domain_clear_override))
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            },
                            colors = itemColors,
                            modifier = Modifier.clickable(onClick = onClear),
                        )
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** Internal so the instrumented tests can assert a row without a database behind it. */
@Composable
internal fun LogRow(entry: LogEntry, override: Boolean?, onClick: () -> Unit) {
    val verdict = if (entry.blocked) {
        stringResource(R.string.log_verdict_blocked)
    } else {
        stringResource(R.string.log_verdict_allowed)
    }
    val rowDescription = stringResource(R.string.cd_log_row, entry.name, verdict)

    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(press)
            .clickable(
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Shape as well as colour. A 10 dp coloured dot was the only thing separating a
        // blocked row from an allowed one, which is invisible to a red-green colour deficit.
        // Blocked is the row worth noticing, so it gets the filled glyph and the error tint
        // while "allowed" stays quiet — the previous solid blue tick made every ordinary row
        // shout.
        Icon(
            imageVector = if (entry.blocked) {
                Icons.Filled.Block
            } else {
                Icons.Outlined.CheckCircle
            },
            contentDescription = null,
            tint = if (entry.blocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = monoBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val overrideLabel = when (override) {
                true -> stringResource(R.string.log_override_allow)
                false -> stringResource(R.string.log_override_block)
                null -> null
            }
            Text(
                text = buildString {
                    append(entry.kind.uppercase())
                    if (entry.app.isNotEmpty()) append(" · ${entry.app}")
                    if (overrideLabel != null) {
                        append(" · $overrideLabel")
                    } else if (entry.blocked && entry.rule.isNotEmpty()) {
                        append(" · ${entry.rule}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = timeFormat.format(Date(entry.ts)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Buckets entries into day sections, preserving the newest-first order they arrive in.
 *
 * Extracted and pure so the boundary cases — an entry from just before midnight next to one
 * from just after — can be asserted without a device.
 */
internal fun groupByDay(entries: List<LogEntry>): List<Pair<Long, List<LogEntry>>> =
    entries
        .groupBy { startOfDay(it.ts) }
        .toList()
        .sortedByDescending { it.first }

/** Today and yesterday by name; anything older by date. */
@Composable
private fun dayLabel(dayStart: Long): String {
    val today = remember { startOfDay(System.currentTimeMillis()) }
    return when (dayStart) {
        today -> stringResource(R.string.log_today)
        today - DAY_MILLIS -> stringResource(R.string.log_yesterday)
        else -> remember(dayStart) {
            SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(dayStart))
        }
    }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * The domain an override would apply to, or null when the row does not name one.
 *
 * Log rows are labelled with whatever identifies the event, and that is not always a hostname:
 * a plain TCP connection is labelled with `address:port`, and a blocked request from the
 * content filter with a full URL. User rules match domains, so an override taken from either
 * of those labels verbatim would be stored, shown in Settings, and never match anything.
 *
 * A URL is reduced to its host, which is genuinely useful. An address is rejected — the
 * distinguishing test is that a hostname contains a letter, which no IPv4 literal does.
 */
internal fun overrideTarget(name: String): String? {
    val withoutScheme = name.substringAfter("://", name)
    val hostAndPort = withoutScheme.substringBefore('/')
    // Bracketed IPv6, e.g. [2606:4700::1111]:443. Never a domain.
    if (hostAndPort.startsWith("[")) return null
    val host = hostAndPort.substringBefore(':').trim().trimEnd('.')

    if (!host.contains('.')) return null
    if (host.none { it.isLetter() }) return null
    if (host.any { !it.isLetterOrDigit() && it != '-' && it != '.' }) return null
    return host.lowercase()
}

internal fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
