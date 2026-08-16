@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.omnishield.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.omnishield.R
import io.omnishield.bridge.NativeBridge
import io.omnishield.data.TunnelRepository
import io.omnishield.data.TunnelStatus
import io.omnishield.ui.theme.OmniShieldTheme
import io.omnishield.vpn.OmniShieldVpnService

class MainActivity : ComponentActivity() {

    // Tells the service whether anything is actually on screen. The core drain backs off hard
    // when nothing is, so this is what keeps the live log feeling live while the user is
    // looking at it — on any tab, not just the one that collects tunnel status.
    override fun onStart() {
        super.onStart()
        TunnelRepository.setUiVisible(true)
    }

    override fun onStop() {
        super.onStop()
        TunnelRepository.setUiVisible(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Must precede super.onCreate so the window is laid out edge-to-edge from the first
        // frame; the theme then inverts the system bar icons to match the palette.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NativeBridge.ensureLoaded()

        // Hold the splash until DataStore has produced a value. Without this the first
        // composition renders an empty Surface — a blank flash between the splash and the UI —
        // because settings are still null and onboarding cannot yet be ruled out.
        var settingsReady = false
        splash.setKeepOnScreenCondition { !settingsReady }

        setContent {
            OmniShieldTheme {
                OmniShieldApp(onSettingsLoaded = { settingsReady = true })
            }
        }
    }
}

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    Dashboard(R.string.tab_shield, Icons.Filled.Shield),
    Log(R.string.tab_log, Icons.AutoMirrored.Filled.List),
    Firewall(R.string.tab_firewall, Icons.Filled.AppBlocking),
    Web(R.string.tab_web, Icons.Filled.Lock),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
private fun OmniShieldApp(onSettingsLoaded: () -> Unit) {
    val settingsViewModel: SettingsViewModel = viewModel()
    val settings by settingsViewModel.settingsOrNull.collectAsStateWithLifecycle()

    val ready = settings != null
    LaunchedEffect(ready) { if (ready) onSettingsLoaded() }

    // Set when onboarding ends on its primary action. Onboarding deliberately owns no
    // permission plumbing of its own, so it records the intent and the main scaffold — which
    // holds the consent launchers — acts on it.
    var pendingConnect by rememberSaveable { mutableStateOf(false) }

    when {
        // Still loading from DataStore — the splash window is what the user sees, held there
        // by the keep-on-screen condition above.
        settings == null -> Surface(Modifier.fillMaxSize()) {}

        !settings!!.onboardingComplete -> OnboardingScreen(
            onFinished = { connect ->
                pendingConnect = connect
                settingsViewModel.completeOnboarding()
            }
        )

        else -> MainScaffold(
            autoConnect = pendingConnect,
            onAutoConnectConsumed = { pendingConnect = false },
        )
    }
}

@Composable
private fun MainScaffold(autoConnect: Boolean, onAutoConnectConsumed: () -> Unit) {
    Surface {
        var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
        val context = LocalContext.current
        val status by TunnelRepository.status.collectAsStateWithLifecycle()

        // Back returns to the shield rather than leaving the app. Without this, back from any
        // of the four other tabs exits outright, which reads as the app closing itself.
        BackHandler(enabled = tab != Tab.Dashboard) { tab = Tab.Dashboard }

        // Granting VPN consent returns an Activity result; only RESULT_OK may start the
        // service. Where consent was already granted, prepare() returns null.
        val vpnConsent = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) startService(context)
        }

        // Requested *before* the tunnel starts, not alongside it. Firing both together put the
        // permission dialog on top of an already-running tunnel, which read as an error.
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            val consent = VpnService.prepare(context)
            if (consent != null) vpnConsent.launch(consent) else startService(context)
        }

        val onConnectRequested: () -> Unit = remember(context, status) {
            {
                if (status is TunnelStatus.Running) {
                    context.startService(
                        Intent(context, OmniShieldVpnService::class.java)
                            .setAction(OmniShieldVpnService.ACTION_STOP)
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // The launcher's callback continues into consent, so the two dialogs are
                    // sequential rather than racing.
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val consent = VpnService.prepare(context)
                    if (consent != null) vpnConsent.launch(consent) else startService(context)
                }
            }
        }

        LaunchedEffect(autoConnect) {
            if (autoConnect) {
                onAutoConnectConsumed()
                onConnectRequested()
            }
        }

        Scaffold(
            // The screens supply their own title bars via ScreenScaffold, and a TopAppBar
            // applies the status-bar inset itself. Zeroing this one leaves `insets` holding
            // only the navigation bar, so nothing is padded twice.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // ShortNavigationBar is the Expressive navigation bar: a shorter container
                // with the label tucked beside the icon rather than beneath it.
                ShortNavigationBar {
                    Tab.entries.forEach { entry ->
                        val label = stringResource(entry.labelRes)
                        ShortNavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        ) { insets ->
            val inner = Modifier.padding(insets)
            when (tab) {
                Tab.Dashboard -> DashboardScreen(inner, onConnectRequested)
                Tab.Log -> LogScreen(inner)
                Tab.Firewall -> FirewallScreen(inner)
                Tab.Web -> SecurityScreen(inner)
                Tab.Settings -> SettingsScreen(inner)
            }
        }
    }
}

private fun startService(context: Context) {
    context.startForegroundService(
        Intent(context, OmniShieldVpnService::class.java)
            .setAction(OmniShieldVpnService.ACTION_START)
    )
}
