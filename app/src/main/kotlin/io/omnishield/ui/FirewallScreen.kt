@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.AppRule
import io.omnishield.ui.components.LocalSnackbar
import io.omnishield.ui.components.ScreenScaffold
import io.omnishield.ui.components.rememberCollapsingBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    /**
     * Decoded on the IO dispatcher in [loadInstalledApps], not in composition. The rows used to
     * call `Drawable.toBitmap` inside a `remember`, which put a bitmap rasterisation on the
     * composition thread for every row the first time it scrolled into view.
     */
    val icon: ImageBitmap?,
)

/** Width of a switch column. Fixed so the header labels sit over the switches they name. */
private val SwitchCell = 60.dp

/**
 * Per-app firewall.
 *
 * The screen this replaced consisted of a `App | Wi-Fi | Data` header and a list of bare
 * switches. It never said what it did, and — worse — nothing said which way the switches ran.
 * They block, which is the opposite of the usual "on means enabled" reading, and there is no
 * safe way to guess: getting it backwards either cuts an app off or leaves one running that the
 * user meant to stop. So the state is now written out per row in words, and the switch is a
 * second representation of it rather than the only one.
 *
 * The Wi-Fi and mobile toggles stay independent because a rule only bites on the transport it
 * names — blocking mobile data must leave the app working on Wi-Fi.
 */
@Composable
fun FirewallScreen(modifier: Modifier = Modifier, viewModel: FirewallViewModel = viewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val bar = rememberCollapsingBar()

    ScreenScaffold(
        title = stringResource(R.string.title_firewall),
        subtitle = stringResource(R.string.subtitle_firewall),
        modifier = modifier,
        scrollBehavior = bar,
    ) { inner ->
        val list = apps
        when {
            list == null -> LoadingApps(Modifier.padding(inner))

            list.isEmpty() -> Centered(Modifier.padding(inner)) {
                Text(
                    text = stringResource(R.string.apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> FirewallList(
                apps = list,
                rules = rules,
                query = query,
                onQueryChange = { query = it },
                onChange = viewModel::setRule,
                modifier = Modifier.padding(inner),
            )
        }
    }
}

/** Internal rather than private so the instrumented tests can drive it without a ViewModel. */
@Composable
internal fun FirewallList(
    apps: List<InstalledApp>,
    rules: Map<Int, AppRule>,
    query: String,
    onQueryChange: (String) -> Unit,
    onChange: (AppRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val keyboard = LocalSoftwareKeyboardController.current

    // Resolved up front. The toggle callback runs outside composition, so it cannot look a
    // string resource up at the moment it needs one.
    val describe = rememberRuleDescriber()

    fun ruleFor(app: InstalledApp) =
        rules[app.uid] ?: AppRule(uid = app.uid, packageName = app.packageName)

    // Applying a rule and telling the user what it did are the same event, so they share one
    // handler; the snackbar carries the undo because a mistap on a system app is otherwise
    // only discoverable when something stops working.
    val onToggle: (InstalledApp, AppRule, AppRule) -> Unit = { app, updated, previous ->
        onChange(updated)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = describe(app.label, updated),
                actionLabel = undoLabel,
            )
            if (result == SnackbarResult.ActionPerformed) onChange(previous)
        }
    }

    // A filter, deliberately not a sort.
    //
    // The first version of this screen hoisted blocked apps into a section at the top. It read
    // well in a screenshot and was wrong in the hand: toggling a switch moved that row to a
    // different part of the list, and because a LazyColumn holds its scroll offset while items
    // are inserted above it, the row the user had just touched slid out of view. The order is
    // now always alphabetical, and finding the apps you have acted on is a chip.
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    val blockedCount = remember(apps, rules) {
        apps.count { rules[it.uid]?.isEmpty == false }
    }
    val visible = remember(apps, query, rules, blockedOnly) {
        apps.asSequence()
            .filter { query.isBlank() || it.matches(query) }
            .filter { !blockedOnly || rules[it.uid]?.isEmpty == false }
            .toList()
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.firewall_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange(""); keyboard?.hide() }) {
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

        if (blockedCount > 0) {
            FilterChip(
                selected = blockedOnly,
                onClick = { blockedOnly = !blockedOnly },
                label = {
                    Text(
                        pluralStringResource(
                            R.plurals.firewall_section_blocked,
                            blockedCount,
                            blockedCount,
                        )
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Text(
            text = stringResource(R.string.firewall_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        ColumnHeaders()
        HorizontalDivider()

        if (visible.isEmpty()) {
            Centered(Modifier.fillMaxSize()) {
                Text(
                    text = if (query.isBlank()) {
                        stringResource(R.string.firewall_none_blocked)
                    } else {
                        stringResource(R.string.apps_empty_search, query)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.packageName }) { app ->
                AppRow(app, ruleFor(app)) { updated, previous ->
                    onToggle(app, updated, previous)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

@Composable
private fun ColumnHeaders() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = HeaderRowEndPadding, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f))
        HeaderCell(stringResource(R.string.firewall_header_wifi))
        HeaderCell(stringResource(R.string.firewall_header_data))
    }
}

/** Matches [AppRow]'s trailing padding so the headers land over the switches, not beside them. */
private val HeaderRowEndPadding = 12.dp

@Composable
private fun HeaderCell(text: String) {
    Box(Modifier.width(SwitchCell), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    rule: AppRule,
    onChange: (updated: AppRule, previous: AppRule) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(BitmapPainter(icon), contentDescription = null, Modifier.size(36.dp))
        } else {
            Box(Modifier.size(36.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A row with a rule says so in words — the switch positions alone do not, unless
            // you already know which way they run. A row without one shows its package name
            // instead, which is what actually separates the four things called "Android
            // System"; repeating "Allowed" down two hundred rows would say nothing.
            Text(
                text = if (rule.isEmpty) {
                    app.packageName
                } else {
                    stringResource(ruleSummary(rule))
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (rule.isEmpty) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val wifiLabel = stringResource(R.string.cd_block_wifi, app.label)
        val dataLabel = stringResource(R.string.cd_block_mobile, app.label)
        Box(Modifier.width(SwitchCell), contentAlignment = Alignment.Center) {
            Switch(
                checked = rule.blockWifi,
                onCheckedChange = {
                    onChange(rule.copy(packageName = app.packageName, blockWifi = it), rule)
                },
                modifier = Modifier.semantics { contentDescription = wifiLabel },
            )
        }
        Box(Modifier.width(SwitchCell), contentAlignment = Alignment.Center) {
            Switch(
                checked = rule.blockMobile,
                onCheckedChange = {
                    onChange(rule.copy(packageName = app.packageName, blockMobile = it), rule)
                },
                modifier = Modifier.semantics { contentDescription = dataLabel },
            )
        }
    }
}

@Composable
private fun LoadingApps(modifier: Modifier = Modifier) {
    Centered(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The Expressive loading indicator: a morphing sequence of shapes rather than
            // a spinning arc.
            ContainedLoadingIndicator()
            Text(
                text = stringResource(R.string.apps_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * The rule in words.
 *
 * Extracted and returning a resource id so the mapping can be asserted in a unit test — the
 * four cases are exactly where an inverted condition would be invisible on screen but wrong.
 */
internal fun ruleSummary(rule: AppRule): Int = when {
    rule.blockWifi && rule.blockMobile -> R.string.firewall_rule_both
    rule.blockWifi -> R.string.firewall_rule_wifi
    rule.blockMobile -> R.string.firewall_rule_mobile
    else -> R.string.firewall_rule_none
}

/**
 * Pre-resolves the rule wording so a non-composable callback can still describe what it just
 * did. Returns `"Chrome — Blocked on mobile data"`.
 */
@Composable
private fun rememberRuleDescriber(): (String, AppRule) -> String {
    // Every piece comes from `stringResource`, which is configuration-aware — resolving through
    // a captured `Context` inside the callback instead would keep serving the strings of
    // whatever locale or theme was current when the row was first composed.
    val none = stringResource(R.string.firewall_rule_none)
    val wifi = stringResource(R.string.firewall_rule_wifi)
    val mobile = stringResource(R.string.firewall_rule_mobile)
    val both = stringResource(R.string.firewall_rule_both)
    // Fetched unformatted: the label and the wording are only known when a switch is tapped.
    val template = stringResource(R.string.firewall_rule_changed)

    return remember(none, wifi, mobile, both, template) {
        { label, rule ->
            val words = when (ruleSummary(rule)) {
                R.string.firewall_rule_both -> both
                R.string.firewall_rule_wifi -> wifi
                R.string.firewall_rule_mobile -> mobile
                else -> none
            }
            String.format(template, label, words)
        }
    }
}

private fun InstalledApp.matches(query: String): Boolean {
    val q = query.trim()
    return label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}

/**
 * Lists apps that can actually reach the network.
 *
 * Enumerating every installed package — including the several hundred system packages with no
 * INTERNET permission — would bury the handful the user cares about.
 */
internal suspend fun loadInstalledApps(context: Context): List<InstalledApp> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())

        packages
            .filter { pkg ->
                pkg.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
            }
            .filter { it.packageName != context.packageName }
            .mapNotNull { pkg ->
                val info: ApplicationInfo = pkg.applicationInfo ?: return@mapNotNull null
                InstalledApp(
                    uid = info.uid,
                    packageName = pkg.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(pkg.packageName),
                    icon = runCatching {
                        pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
                    }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }
