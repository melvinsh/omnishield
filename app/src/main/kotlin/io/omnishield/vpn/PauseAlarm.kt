package io.omnishield.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Wakes the service when a filtering snooze expires.
 *
 * A snooze must end on time even if the app is never opened again, so it is an alarm rather
 * than an in-process timer — a coroutine `delay` would die with the process and leave
 * filtering off indefinitely, which is the one failure mode a snooze must not have.
 */
object PauseAlarm {

    private const val TAG = "PauseAlarm"
    private const val REQUEST_CODE = 7001

    fun schedule(context: Context, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)

        // setExactAndAllowWhileIdle needs SCHEDULE_EXACT_ALARM on API 31+, which we do not
        // request — a snooze ending a few minutes late is acceptable, a permission prompt for
        // it is not. The inexact variant still fires through Doze.
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }.onFailure { Log.w(TAG, "could not schedule resume alarm: ${it.message}") }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PauseExpiredReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires when a snooze expires and tells the service to resume filtering. */
class PauseExpiredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            context.startService(
                Intent(context, OmniShieldVpnService::class.java)
                    .setAction(OmniShieldVpnService.ACTION_RESUME)
            )
        }
    }
}
