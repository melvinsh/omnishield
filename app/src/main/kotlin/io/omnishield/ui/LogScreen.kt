@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package io.omnishield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.log_search)) },
            singleLine = true,
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
            ToggleButton(
                checked = filter.blockedOnly,
                onCheckedChange = viewModel::setBlockedOnly,
            ) {
                Text(stringResource(R.string.log_blocked_only))
            }
            Text(
                text = "${entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::clearLog) {
                Text(stringResource(R.string.log_clear))
            }
        }
        HorizontalDivider()

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (filter.query.isBlank() && !filter.blockedOnly) {
                        stringResource(R.string.log_empty)
                    } else {
                        stringResource(R.string.log_empty_filtered)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.seq }) { entry ->
                    LogRow(
                        entry = entry,
                        override = overrides[entry.name],
                        onClick = { selected = entry },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            }
        }
    }

    selected?.let { entry ->
        DomainOverrideSheet(
            entry = entry,
            override = overrides[entry.name],
            onAllow = { viewModel.allow(entry.name); selected = null },
            onBlock = { viewModel.block(entry.name); selected = null },
            onClear = { viewModel.clearOverride(entry.name); selected = null },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun DomainOverrideSheet(
    entry: LogEntry,
    override: Boolean?,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = {
                    Text(entry.name, fontFamily = FontFamily.Monospace)
                },
                supportingContent = {
                    Text(
                        buildString {
                            append(entry.kind.uppercase())
                            if (entry.app.isNotEmpty()) append(" · ${entry.app}")
                            if (entry.rule.isNotEmpty()) append(" · ${entry.rule}")
                        }
                    )
                },
            )
            HorizontalDivider()
            if (override != true) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.domain_allow)) },
                    modifier = Modifier.clickable(onClick = onAllow),
                )
            }
            if (override != false) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.domain_block)) },
                    modifier = Modifier.clickable(onClick = onBlock),
                )
            }
            if (override != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.domain_clear_override)) },
                    modifier = Modifier.clickable(onClick = onClear),
                )
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun LogRow(entry: LogEntry, override: Boolean?, onClick: () -> Unit) {
    val verdict = if (entry.blocked) "blocked" else "allowed"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "${entry.name}, $verdict" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (entry.blocked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append(entry.kind.uppercase())
                    if (entry.app.isNotEmpty()) append(" · ${entry.app}")
                    when (override) {
                        true -> append(" · allowlisted")
                        false -> append(" · user-blocked")
                        null -> if (entry.blocked && entry.rule.isNotEmpty()) {
                            append(" · ${entry.rule}")
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = timeFormat.format(Date(entry.ts)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
