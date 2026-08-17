@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.omnishield.ui.components.GroupedColumn
import io.omnishield.ui.components.LocalSnackbar
import io.omnishield.ui.components.OnResume
import io.omnishield.ui.components.ScreenScaffold
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Enumerating browsers is a sweep over every installed package (see browserApps), so it runs
    // off the main thread and the list fills in a frame later rather than blocking composition.
    val browsers by produceState(initialValue = emptyList(), context) {
        value = withContext(Dispatchers.IO) { browserApps(context) }
    }

    OnResume { viewModel.refreshCaTrust() }

    ScreenScaffold(
        title = stringResource(R.string.title_web),
        subtitle = stringResource(R.string.subtitle_web),
        modifier = modifier,
    ) { inner ->
        val snackbar = LocalSnackbar.current
        val scope = rememberCoroutineScope()

        // Since Android 11 an app can no longer hand the system the certificate bytes to
        // install; the CA-install path in `KeyChain.createInstallIntent` is a silent no-op on
        // modern devices. The only route left is to write the certificate to a file the user
        // picks, then have them import it by hand through Settings — so that is what the button
        // does, and the card spells out the manual steps.
        val savePem = state.caPem
        // Resolved in composition so the callback does not query resources off a captured
        // Context, which lint flags as not configuration-aware.
        val savedMessage = stringResource(R.string.https_saved)
        val saveFailedMessage = stringResource(R.string.https_save_failed)
        val saveCertificate = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-x509-ca-cert"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) { writeCertificate(context, uri, savePem) }
                snackbar.showSnackbar(if (ok) savedMessage else saveFailedMessage)
            }
        }

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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(MaterialShapes.Sunny.toShape())
                                .background(MaterialTheme.colorScheme.tertiary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
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
                onSave = { saveCertificate.launch("omnishield-ca.crt") },
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
                    style = MaterialTheme.typography.titleSmallEmphasized,
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

            GroupedColumn {
                if (browsers.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.https_no_browsers),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                browsers.forEach { app ->
                    item {
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The icon in a small shaped container — an echo of the shield's polygon
                // family. `secondary` on secondaryContainer is a guaranteed-contrast pairing
                // under any dynamic palette. Decorative: the title carries the meaning.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
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
    onSave: () -> Unit,
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
            // The manual steps, shown only when there is a certificate to import and it is not
            // trusted yet. Android gives an app no way to install a CA directly, so these steps
            // are the feature — spelled out because the Settings path is buried and renamed by
            // several vendors.
            if (caReady && !installed) {
                Text(
                    stringResource(R.string.https_ca_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = caReady && !installed, onClick = onSave) {
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
 * Writes the CA to a file the user chose, as DER-encoded bytes with a `.crt` name.
 *
 * DER with a `.crt` extension is what Android's certificate installer accepts from the Storage
 * Access Framework picker on every version and vendor. The bytes go to a user-picked location
 * (via `CreateDocument`) rather than app-private storage, because the whole point is that the
 * user can then reach the file from the Settings importer.
 */
private fun writeCertificate(context: Context, uri: Uri, pem: String): Boolean = runCatching {
    val der = pemToDer(pem) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { it.write(der) } ?: return false
    true
}.getOrDefault(false)

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
    // A scheme-only "https:" probe with CATEGORY_BROWSABLE matches only real browsers — apps that
    // handle a bare web scheme rather than one specific verified host — and MATCH_ALL returns every
    // one of them. Querying a full URL with flags 0 (what this did before) collapses to the single
    // default browser on Android 12+, so only the default ever appeared: a user with Firefox set
    // as default never saw Chrome, and vice versa.
    val browserProbe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    return runCatching {
        // A plain queryIntentActivities collapses a web intent to the single default browser on
        // Android 12+, even with MATCH_ALL — so a user whose default is Firefox never saw Chrome,
        // and vice versa. Resolving the probe *scoped to each installed package* (setPackage)
        // sidesteps that collapse: each app is asked on its own whether it is a browser, so every
        // installed browser is found. Needs QUERY_ALL_PACKAGES, which the app already holds.
        pm.getInstalledApplications(0)
            .filter {
                pm.queryIntentActivities(Intent(browserProbe).setPackage(it.packageName), 0)
                    .isNotEmpty()
            }
            .map { info ->
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
