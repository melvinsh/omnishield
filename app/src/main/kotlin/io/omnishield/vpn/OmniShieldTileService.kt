package io.omnishield.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.omnishield.R
import io.omnishield.data.TunnelRepository
import io.omnishield.data.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Quick Settings tile — the entry point users expect from anything VPN-shaped, and the one
 * that makes toggling protection a one-swipe action rather than a launch-and-tap.
 */
class OmniShieldTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        // Observed rather than read once: the tunnel can be stopped from the notification or
        // the app while the shade is open, and a stale tile is worse than no tile.
        newScope.launch {
            TunnelRepository.status.collectLatest { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val running = TunnelRepository.status.value is TunnelStatus.Running

        if (running) {
            startService(
                Intent(this, OmniShieldVpnService::class.java)
                    .setAction(OmniShieldVpnService.ACTION_STOP)
            )
            return
        }

        // Consent cannot be granted from a tile — there is no Activity to host the system
        // dialog — so an unconsented tap opens the app rather than failing silently.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }

        startForegroundService(
            Intent(this, OmniShieldVpnService::class.java)
                .setAction(OmniShieldVpnService.ACTION_START)
        )
    }

    /**
     * Opens the app from the tile, collapsing the shade.
     *
     * The `Intent` overload is not merely deprecated on Android 14 — it **throws
     * `UnsupportedOperationException`**, and `targetSdk` is 34. So the one path a first-time
     * user takes from the tile (tap it before VPN consent has ever been granted) crashed the
     * tile service instead of opening the app. The `PendingIntent` overload exists from API 34
     * only, hence the split.
     */
    // Lint flags the call regardless of the version guard around it; the guard is the fix, and
    // minSdk 29 means the older overload still has to exist for the devices that only have it.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    private fun render(status: TunnelStatus) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            is TunnelStatus.Running -> Tile.STATE_ACTIVE
            is TunnelStatus.Starting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.contentDescription = getString(
            if (status is TunnelStatus.Running) R.string.status_protected
            else R.string.status_stopped
        )
        tile.updateTile()
    }
}
