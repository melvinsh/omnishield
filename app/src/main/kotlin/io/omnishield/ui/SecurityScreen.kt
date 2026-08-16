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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.ui.components.LocalSnackbar
import io.omnishield.ui.components.OnResume
import io.omnishield.ui.components.ScreenScaffold
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.launch

/**
 * Layer 2 controls: root CA installation and per-app interception opt-in.
 *
 * The copy is deliberately blunt about the limitation rather than hiding it. Since Android 7,
 * apps ignore user-installed CAs unless they opt in via their own network security config, so
 * this feature reaches Chrome-family browsers and almost nothing else. Presenting it as
 * general-purpose HTTPS filtering would be a lie the user discovers the hard way.
 *
 * What changed here is the order. The screen used to open with three paragraphs of caveat, so
 * the first thing a reader learned about the feature was everything it cannot do — before
 * anything had told them what it was for. The benefit now comes first and the caveat second,
 * which is the same information without asking the user to hold a negation in mind.
 */
@Composable
fun SecurityScreen(modifier: Modifier = Modifier, viewModel: SecurityViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browsers = remember { browserApps(context) }

    OnResume { viewModel.refreshCaTrust() }

    ScreenScaffold(
        title = stringResource(R.string.title_web),
        subtitle = stringResource(R.string.subtitle_web),
        modifier = modifier,
    ) { inner ->
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InfoCard(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.web_lead_title),
                body = stringResource(R.string.web_lead_body),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            // tertiaryContainer is the right role for an advisory that must be read but is not
            // an error; it carries a guaranteed contrast pair with onTertiaryContainer at any
            // tone, including whatever the wallpaper-derived dynamic palette produces.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Text(
                            stringResource(R.string.https_caveat_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
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

            CertificateCard(
                caReady = state.caPem.isNotEmpty(),
                installed = state.caInstalled,
                onInstall = { installCertificate(context, state.caPem) },
                onSettings = { openSecuritySettings(context) },
            )

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
                            stringResource(R.string.https_intercept_sub),
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

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.https_apps_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (state.settings.mitmEnabled) {
                        stringResource(R.string.https_apps_sub)
                    } else {
                        // The per-app switches used to stay live with the master switch off,
                        // so flipping one changed a stored set and produced no observable
                        // effect whatsoever.
                        stringResource(R.string.https_apps_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                        // Resolved in composition rather than from a captured Context inside
                        // the callback, so it follows a locale or configuration change.
                        val unpinned = stringResource(R.string.https_unpin_done, app.label)
                        BrowserRow(
                            app = app,
                            enabled = state.settings.mitmEnabled,
                            intercepted = app.uid in state.settings.mitmUids,
                            pinned = app.uid in state.settings.pinnedUids,
                            onToggle = { on ->
                                val next = if (on) {
                                    state.settings.mitmUids + app.uid
                                } else {
                                    state.settings.mitmUids - app.uid
                                }
                                viewModel.setInterceptedUids(next)
                            },
                            onUnpin = {
                                viewModel.unpin(app.uid)
                                scope.launch { snackbar.showSnackbar(unpinned) }
                            },
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.https_rules_loaded, state.contentRules),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    body: String,
    container: Color,
    content: Color,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Certificate state, reported rather than assumed.
 *
 * This used to offer "Install certificate" indefinitely with no way of telling whether the
 * install had ever happened — and with interception silently doing nothing until it had.
 */
@Composable
private fun CertificateCard(
    caReady: Boolean,
    installed: Boolean,
    onInstall: () -> Unit,
    onSettings: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.https_ca_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (caReady) {
                    Icon(
                        imageVector = if (installed) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.Warning
                        },
                        contentDescription = null,
                        tint = if (installed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Text(
                    text = when {
                        !caReady -> stringResource(R.string.https_ca_pending)
                        installed -> stringResource(R.string.https_ca_trusted)
                        else -> stringResource(R.string.https_ca_untrusted)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (caReady) {
                Text(
                    stringResource(R.string.https_ca_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = caReady && !installed, onClick = onInstall) {
                    Text(stringResource(R.string.https_install))
                }
                OutlinedButton(onClick = onSettings) {
                    Text(stringResource(R.string.https_settings))
                }
            }
        }
    }
}

/** Internal so the instrumented tests can assert the gating without a ViewModel. */
@Composable
internal fun BrowserRow(
    app: InstalledApp,
    enabled: Boolean,
    intercepted: Boolean,
    pinned: Boolean,
    onToggle: (Boolean) -> Unit,
    onUnpin: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (pinned) stringResource(R.string.https_pinned) else app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = if (pinned) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (pinned) {
            // Being pinned was a terminal state: the repository has always had a way to clear
            // it and nothing ever called it, so an app that rejected the certificate once was
            // bypassed for good.
            TextButton(onClick = onUnpin) { Text(stringResource(R.string.https_unpin)) }
        }
        Switch(
            checked = intercepted,
            enabled = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = app.label },
        )
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
    val der = pemToDer(pem)

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

private fun pemToDer(pem: String): ByteArray? = runCatching {
    val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
    Base64.decode(body, Base64.DEFAULT)
}.getOrNull()

/**
 * Whether this device actually trusts our CA.
 *
 * Reads `AndroidCAStore` — the platform's combined system and user trust store — rather than
 * asking a `TrustManagerFactory`. A trust manager reflects *this app's* network security
 * config, and `network_security_config.xml` deliberately restricts release builds to system
 * anchors, so it would report "not trusted" no matter what the user had installed.
 *
 * Blocking I/O: callers run it off the main thread.
 */
internal fun isCaInstalled(pem: String): Boolean {
    if (pem.isBlank()) return false
    val ours = runCatching {
        val der = pemToDer(pem) ?: return false
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }.getOrNull() ?: return false

    return runCatching {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        store.aliases().asSequence().any { alias ->
            val cert = store.getCertificate(alias) as? X509Certificate
            cert != null && cert.encoded.contentEquals(ours.encoded)
        }
    }.getOrDefault(false)
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
