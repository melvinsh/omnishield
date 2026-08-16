package io.omnishield.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import io.omnishield.data.SettingsRepository
import kotlinx.coroutines.runBlocking

/**
 * Brings the tunnel back up after a restart, when the user asked for that.
 *
 * Gives the manifest's `RECEIVE_BOOT_COMPLETED` an actual purpose — it was previously declared
 * and wired to nothing, which is both a wasted permission and something a reviewer notices.
 *
 * Two conditions have to hold, and failing either is silent by design rather than by
 * oversight:
 *
 * - **Consent must already exist.** `VpnService.prepare` returns non-null when it does not,
 *   and there is no UI at boot to grant it. Starting anyway would throw.
 * - **The start-on-boot setting must be on.** Resurrecting a tunnel the user deliberately
 *   stopped would be worse than not starting at all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val settings = SettingsRepository(context)
        val enabled = runCatching { runBlocking { settings.current().startOnBoot } }
            .getOrDefault(false)
        if (!enabled) return

        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "start-on-boot requested but VPN consent is not granted")
            return
        }

        // Android 12+ restricts starting a foreground service from the background. Boot is an
        // allowed exemption, but OEM builds vary, so a failure here is logged rather than
        // allowed to crash the receiver.
        runCatching {
            context.startForegroundService(
                Intent(context, OmniShieldVpnService::class.java)
                    .setAction(OmniShieldVpnService.ACTION_START)
            )
        }.onFailure { Log.e(TAG, "could not start tunnel on boot", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
