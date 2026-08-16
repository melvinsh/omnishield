@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.data.AppRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/**
 * Per-app firewall. Toggling a switch rewrites the rule store and pushes the new UID set into
 * the running core, which drops matching connections at the TUN.
 *
 * The Wi-Fi and mobile toggles are independent because a rule only bites on the transport it
 * names — blocking mobile data must leave the app working on Wi-Fi.
 */
@Composable
fun AppsScreen(modifier: Modifier = Modifier, viewModel: AppsViewModel = viewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    val list = apps
    if (list == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        return
    }

    if (list.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.apps_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.apps_header_app),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.apps_header_wifi),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "  ${stringResource(R.string.apps_header_data)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.packageName }) { app ->
                val rule = rules[app.uid]
                    ?: AppRule(uid = app.uid, packageName = app.packageName)
                AppRow(app = app, rule = rule, onChange = viewModel::setRule)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, rule: AppRule, onChange: (AppRule) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = app.icon
        val bitmap = remember(app.packageName) {
            icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
        }
        if (bitmap != null) {
            Image(BitmapPainter(bitmap), contentDescription = null, Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        val wifiLabel = stringResource(R.string.cd_block_wifi, app.label)
        val dataLabel = stringResource(R.string.cd_block_mobile, app.label)
        Switch(
            checked = rule.blockWifi,
            onCheckedChange = {
                onChange(rule.copy(packageName = app.packageName, blockWifi = it))
            },
            modifier = Modifier.semantics { contentDescription = wifiLabel },
        )
        Switch(
            checked = rule.blockMobile,
            onCheckedChange = {
                onChange(rule.copy(packageName = app.packageName, blockMobile = it))
            },
            modifier = Modifier.semantics { contentDescription = dataLabel },
        )
    }
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
                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }
