package io.omnishield.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.security.KeyChain
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R

/**
 * Layer 2 controls: root CA installation and per-app interception opt-in.
 *
 * The copy here is deliberately blunt about the limitation rather than hiding it. Since
 * Android 7, apps ignore user-installed CAs unless they opt in via their own network security
 * config, so this feature reaches Chrome-family browsers and almost nothing else. Presenting
 * it as general-purpose HTTPS filtering would be a lie the user discovers the hard way.
 */
@Composable
fun SecurityScreen(modifier: Modifier = Modifier, viewModel: SecurityViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browsers = remember { browserApps(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.https_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // tertiaryContainer is the right role for an advisory that must be read but is not an
        // error; it carries a guaranteed contrast pair with onTertiaryContainer at any tone,
        // including whatever the wallpaper-derived dynamic palette produces.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.https_caveat_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.https_caveat_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.https_caveat_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.https_ca_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (state.caPem.isEmpty()) {
                        stringResource(R.string.https_ca_pending)
                    } else {
                        stringResource(R.string.https_ca_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = state.caPem.isNotEmpty(),
                        onClick = { installCertificate(context, state.caPem) },
                    ) { Text(stringResource(R.string.https_install)) }
                    Button(onClick = { openSecuritySettings(context) }) {
                        Text(stringResource(R.string.https_settings))
                    }
                }
            }
        }

        Card {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.https_intercept),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.https_rules_loaded, state.contentRules),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val label = stringResource(R.string.https_intercept)
                Switch(
                    checked = state.settings.mitmEnabled,
                    onCheckedChange = viewModel::setMitmEnabled,
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }

        Text(
            stringResource(R.string.https_apps_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.https_apps_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card {
            Column {
                if (browsers.isEmpty()) {
                    Text(
                        stringResource(R.string.https_no_browsers),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                browsers.forEachIndexed { index, app ->
                    if (index > 0) HorizontalDivider()
                    val pinned = app.uid in state.settings.pinnedUids
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (pinned) {
                                    stringResource(R.string.https_pinned)
                                } else {
                                    app.packageName
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pinned) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Switch(
                            checked = app.uid in state.settings.mitmUids,
                            onCheckedChange = { on ->
                                val next = if (on) {
                                    state.settings.mitmUids + app.uid
                                } else {
                                    state.settings.mitmUids - app.uid
                                }
                                viewModel.setInterceptedUids(next)
                            },
                            modifier = Modifier.semantics { contentDescription = app.label },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hands the certificate to the system installer.
 *
 * `KeyChain.createInstallIntent` is tried first because it lands the user directly on the
 * naming dialog. Android 11+ increasingly routes CA installation through Settings instead, so
 * a failure here falls back rather than dead-ends.
 */
private fun installCertificate(context: Context, pem: String) {
    val der = runCatching {
        val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        Base64.decode(body, Base64.DEFAULT)
    }.getOrNull()

    if (der != null) {
        val intent = KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, der)
            putExtra(KeyChain.EXTRA_NAME, "OmniShield Root CA")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    openSecuritySettings(context)
}

private fun openSecuritySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Browsers are the only apps where interception realistically works, so only those listed. */
private fun browserApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    return runCatching {
        pm.queryIntentActivities(browserIntent, 0)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
                InstalledApp(
                    uid = info.uid,
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = null,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
