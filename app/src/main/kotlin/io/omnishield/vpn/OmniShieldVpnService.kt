package io.omnishield.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import io.omnishield.R
import io.omnishield.bridge.NativeBridge
import io.omnishield.data.CoreJson
import io.omnishield.data.PollSchedule
import io.omnishield.data.FilterRepository
import io.omnishield.data.LogRepository
import io.omnishield.data.RulesRepository
import io.omnishield.data.Settings
import io.omnishield.data.SettingsRepository
import io.omnishield.data.Stats
import io.omnishield.data.TunnelRepository
import io.omnishield.data.TunnelStatus
import io.omnishield.ui.MainActivity
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The local loopback tunnel.
 *
 * Owns lifecycle only. Once [ParcelFileDescriptor.detachFd] hands the TUN descriptor to the
 * Rust core, every packet is processed natively and Kotlin never touches one. The three
 * methods the core calls back into ([protect], [lookupUid], [packageForUid]) are invoked per
 * *connection*, never per packet.
 */
class OmniShieldVpnService : VpnService() {

    companion object {
        private const val TAG = "OmniShieldVpn"

        const val ACTION_START = "io.omnishield.action.START"
        const val ACTION_STOP = "io.omnishield.action.STOP"

        /** Re-reads settings into the running core without tearing the tunnel down. */
        const val ACTION_REFRESH = "io.omnishield.action.REFRESH"

        /** Snooze filtering; carries [EXTRA_PAUSE_MINUTES]. */
        const val ACTION_PAUSE = "io.omnishield.action.PAUSE"
        const val ACTION_RESUME = "io.omnishield.action.RESUME"
        const val EXTRA_PAUSE_MINUTES = "pause_minutes"

        /** Floor between notification republishes. */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 2_000L

        private const val CHANNEL_ID = "omnishield.tunnel"
        private const val NOTIFICATION_ID = 1001

        /**
         * The Android side of the link. The core assigns itself 10.0.0.1/24 so this peer is
         * on-link — no ARP/NDP is possible on a TUN, so both ends must consider each other
         * directly reachable.
         */
        private const val TUN_ADDR_V4 = "10.0.0.2"
        private const val TUN_ADDR_V6 = "fd00:0:0:1::2"

        /** Advertised as the system resolver. Never a real host; queries to it are answered. */
        private const val DNS_SENTINEL = "10.0.0.53"
    }

    private var nativeHandle: Long = 0
    private var scope: CoroutineScope? = null
    private var lastNotificationAt = 0L

    private lateinit var settings: SettingsRepository
    private lateinit var filters: FilterRepository
    private lateinit var logs: LogRepository
    private lateinit var rules: RulesRepository

    override fun onCreate() {
        super.onCreate()
        NativeBridge.ensureLoaded()
        settings = SettingsRepository(this)
        filters = FilterRepository(this)
        logs = LogRepository(this)
        rules = RulesRepository(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel(userInitiated = true)
                return START_NOT_STICKY
            }

            ACTION_REFRESH -> {
                scope?.launch { pushConfig() }
                return START_STICKY
            }

            ACTION_PAUSE -> {
                val minutes = intent.getIntExtra(EXTRA_PAUSE_MINUTES, 5)
                scope?.launch { pause(minutes) }
                return START_STICKY
            }

            ACTION_RESUME -> {
                scope?.launch { resume() }
                return START_STICKY
            }

            ACTION_START -> startTunnel()

            else -> {
                // A null intent means the system recreated us under START_STICKY. Only resume
                // if the user actually left the tunnel on — blindly re-establishing is how the
                // UI previously ended up reading "Not protected" while the service was still
                // running in the foreground.
                val desired = runBlocking { settings.current().tunnelDesired }
                if (desired) {
                    Log.i(TAG, "restarted by the system; resuming (tunnel was desired)")
                    startTunnel()
                } else {
                    Log.i(TAG, "restarted by the system but tunnel was not desired; stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by the system")
        stopTunnel(userInitiated = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(userInitiated = false)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    private fun startTunnel() {
        if (TunnelRepository.status.value is TunnelStatus.Running) return
        TunnelRepository.setStatus(TunnelStatus.Starting)

        createNotificationChannel()
        promoteToForeground(blockedCount = 0)

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            val current = settings.current()

            val pfd = runCatching { buildTunnel(current) }
                .onFailure { Log.e(TAG, "failed to establish tunnel", it) }
                .getOrNull()

            if (pfd == null) {
                fail("Could not establish the VPN interface. Another VPN may be active.")
                return@launch
            }

            // The core takes ownership of the descriptor and closes it when the loop exits.
            val fd = pfd.detachFd()
            val handle = NativeBridge.nativeStart(
                this@OmniShieldVpnService,
                fd,
                buildConfig(current),
            )

            if (handle == 0L) {
                Log.e(TAG, "native start failed; reclaiming descriptor")
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                fail("The filtering engine failed to start.")
                return@launch
            }

            nativeHandle = handle
            settings.setTunnelDesired(true)

            TunnelRepository.resetSession()
            TunnelRepository.setCaPem(NativeBridge.nativeCaPem(handle))
            TunnelRepository.setStatus(TunnelStatus.Running(System.currentTimeMillis()))
            Log.i(TAG, "tunnel established; core = ${NativeBridge.nativeVersion()}")

            launch { loadFilters() }
            launch { pollCore() }
        }
    }

    private suspend fun fail(reason: String) {
        TunnelRepository.setStatus(TunnelStatus.Failed(reason))
        settings.setTunnelDesired(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun buildTunnel(current: Settings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("OmniShield")
            .setMtu(current.mtu)
            .addAddress(TUN_ADDR_V4, 32)
            .addDnsServer(DNS_SENTINEL)
            .addRoute("0.0.0.0", 0)

        // IPv6 is routed as well as addressed. Adding the route without an address would
        // leave IPv6 traffic escaping the tunnel unfiltered — a silent DNS leak.
        runCatching {
            builder.addAddress(TUN_ADDR_V6, 128)
            builder.addRoute("::", 0)
        }.onFailure { Log.w(TAG, "IPv6 unavailable on this device: ${it.message}") }

        // Belt and braces against the routing loop: our own traffic is excluded here, and
        // every upstream socket the core opens is additionally protect()ed.
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.e(TAG, "cannot exclude self from tunnel", it) }

        // User-chosen exclusions: apps the userspace stack or the MITM upsets.
        for (pkg in rules.excludedPackages()) {
            runCatching { builder.addDisallowedApplication(pkg) }
                .onFailure { Log.w(TAG, "cannot exclude $pkg: ${it.message}") }
        }

        return builder.establish()
    }

    private fun stopTunnel(userInitiated: Boolean) {
        val hadHandle = nativeHandle != 0L
        if (!hadHandle && TunnelRepository.status.value is TunnelStatus.Stopped) return

        scope?.cancel()
        scope = null

        if (nativeHandle != 0L) {
            // Joins the tunnel thread, which closes the TUN descriptor on its way out.
            NativeBridge.nativeStop(nativeHandle)
            nativeHandle = 0
        }

        // Counter deltas are accumulated in memory between periodic writes, so an orderly
        // stop has to flush them or the last few minutes of the session are simply lost.
        // Detached scope: ours is already cancelled above.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { logs.flushPending() }
                .onFailure { Log.w(TAG, "could not flush pending stats: ${it.message}") }
        }

        if (userInitiated) {
            // Fire-and-forget on a detached scope: our own scope is already cancelled, and
            // this flag must survive the service dying.
            CoroutineScope(Dispatchers.IO).launch { settings.setTunnelDesired(false) }
        }

        if (TunnelRepository.status.value !is TunnelStatus.Failed) {
            TunnelRepository.setStatus(TunnelStatus.Stopped)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "tunnel stopped")
    }

    // -----------------------------------------------------------------------
    // Pause / resume
    // -----------------------------------------------------------------------

    private suspend fun pause(minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        settings.setPausedUntil(until)
        pushConfig()
        PauseAlarm.schedule(this, until)
        Log.i(TAG, "filtering paused for $minutes min")
    }

    private suspend fun resume() {
        settings.setPausedUntil(0)
        PauseAlarm.cancel(this)
        pushConfig()
        Log.i(TAG, "filtering resumed")
    }

    // -----------------------------------------------------------------------
    // Called from Rust — see core/src/jvm.rs
    // -----------------------------------------------------------------------

    /**
     * Resolves the app that owns a connection.
     *
     * Backed by `ConnectivityManager.getConnectionOwnerUid` — note this lives on
     * ConnectivityManager, *not* on VpnService. It is API 29+, which is why `minSdk` is 29:
     * there is no fallback, since `/proc/net` scraping was blocked in the same release. The
     * call is permitted only because we are the active VPN app.
     */
    @Keep
    fun lookupUid(
        protocol: Int,
        localIp: String,
        localPort: Int,
        remoteIp: String,
        remotePort: Int,
    ): Int = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm?.getConnectionOwnerUid(
            protocol,
            InetSocketAddress(InetAddress.getByName(localIp), localPort),
            InetSocketAddress(InetAddress.getByName(remoteIp), remotePort),
        ) ?: -1
    } catch (t: Throwable) {
        -1
    }

    @Keep
    fun packageForUid(uid: Int): String = try {
        packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    // -----------------------------------------------------------------------
    // Core plumbing
    // -----------------------------------------------------------------------

    private suspend fun buildConfig(current: Settings): String {
        val now = System.currentTimeMillis()
        return CoreJson.buildConfig(
            mtu = current.mtu,
            dnsSentinel = DNS_SENTINEL,
            upstreamDns = listOf(current.upstreamUdp, "1.0.0.1"),
            upstreamMode = current.upstreamMode,
            dohUrl = current.dohUrl,
            blockQuic = current.blockQuic,
            blockDot = current.blockDot,
            // A snooze is expressed as filtering-off rather than a tunnel teardown, so there
            // is no reconnect flicker and no re-prompt for VPN consent.
            filteringEnabled = current.filteringActive(now),
            mitmEnabled = current.mitmEnabled,
            mitmUids = current.mitmUids - current.pinnedUids,
            blockedUids = rules.blockedUidsFor(isOnWifi()),
            // App-private storage for the root CA. Must never be world-readable: the key
            // signs certificates every app on the device may be asked to trust.
            dataDir = File(filesDir, "ca").absolutePath,
            // Its own directory, not inside the CA's: the prebuilt filter caches are
            // disposable, and nesting them under the CA would mix throwaway data in with a
            // root certificate the user may have installed on the device.
            cacheDir = File(filesDir, "filtercache").absolutePath,
        )
    }

    /**
     * Pushes current settings and per-domain overrides into the running core without
     * restarting the tunnel.
     *
     * The overrides go with every refresh because an allowlist entry the user just added is
     * worthless if it only takes effect on the next connect.
     */
    private suspend fun pushConfig() {
        val handle = nativeHandle
        if (handle == 0L) return
        NativeBridge.nativeUpdateConfig(handle, buildConfig(settings.current()))
        NativeBridge.nativeSetUserRules(handle, CoreJson.buildUserRules(rules.snapshot()))
    }

    /**
     * Brings both filter layers up, from the prebuilt cache when possible.
     *
     * A warm start now costs a handful of `stat` calls and one cache read. The previous version
     * read ~13 MB of list text off disk, rebuilt the domain index once per list, compiled the
     * ABP engine from ~135k rules — and then *downloaded the same three lists again* and fed
     * them straight back in, on every single connect. The scheduled [FilterRefreshWorker] owns
     * refreshing; a tunnel start only loads.
     */
    private suspend fun loadFilters() {
        val handle = nativeHandle
        if (handle == 0L) return

        // The very first run has nothing cached and no lists downloaded, so it is the one case
        // that must fetch inline — otherwise the user's first session runs on the bundled list
        // alone until a WorkManager job happens to fire.
        if (!filters.hasCachedDnsLists()) {
            Log.i(TAG, "no lists on disk; fetching before first load")
            filters.refresh()
            filters.refreshContentRules()
        }

        val key = filters.sourceKey()
        var total = NativeBridge.nativeLoadCachedFilters(handle, key)
        if (total >= 0) {
            Log.i(TAG, "restored $total rules from the prebuilt cache")
        } else {
            NativeBridge.nativeLoadFilters(handle, filters.bundled())
            for (list in filters.cached()) {
                NativeBridge.nativeLoadFilters(handle, list)
            }
            stageContentRules()
            total = NativeBridge.nativeCommitFilters(handle, key)
            Log.i(TAG, "built and cached $total rules from lists")
        }
        TunnelRepository.setFilterRules(total)

        // Applied after the lists so a user override is never briefly shadowed by a
        // downloaded rule during startup.
        val userRules = rules.snapshot()
        if (userRules.isNotEmpty()) {
            val applied = NativeBridge.nativeSetUserRules(handle, CoreJson.buildUserRules(userRules))
            Log.i(TAG, "applied $applied user domain overrides")
        }
    }

    /**
     * Hands the ABP lists to the core without compiling them yet.
     *
     * Loaded regardless of whether interception is on — they are inert until Layer 2 starts
     * decrypting, and having them ready avoids a stall the first time a user enables HTTPS
     * filtering.
     */
    private suspend fun stageContentRules() {
        val handle = nativeHandle
        if (handle == 0L) return
        var lists = filters.cachedContentRules()
        if (lists.isEmpty()) lists = filters.refreshContentRules()
        // Streamed one at a time rather than joined: the previous version built a single string
        // the size of every ABP list combined, while still holding the originals.
        for (list in lists) {
            NativeBridge.nativeLoadContentRules(handle, list)
        }
    }


    /**
     * Drains events and counters, persisting as it goes.
     *
     * Polling rather than native callbacks keeps the JVM off the packet path entirely — that
     * part is deliberate and unchanged. What changed is the cadence. This ran at a fixed 2 Hz
     * for the entire life of the tunnel: with the screen off, with no UI bound, and even for
     * the full duration of a filtering snooze. Every tick cost two JNI round trips, three JSON
     * parses and five SQLite statements, almost always to discover that nothing had happened.
     *
     * The interval now follows the traffic. It backs off while the core has nothing to report
     * and snaps back the moment it does, so an idle tunnel is nearly free while a busy one is
     * still sampled promptly.
     */
    private suspend fun pollCore() {
        var lastBlocked = -1L
        var lastStats = Stats()
        var interval = PollSchedule.MIN_MS
        var uiBound = true
        val uiWatch = scope?.launch {
            TunnelRepository.uiActive.collect { uiBound = it }
        }

        while (scope?.isActive == true) {
            val handle = nativeHandle
            if (handle == 0L) break

            val events = CoreJson.parseEvents(NativeBridge.nativeDrainEvents(handle))
            if (events.isNotEmpty()) {
                runCatching { logs.persist(events) }
                    .onFailure { Log.w(TAG, "could not persist log batch: ${it.message}") }
            }

            val statsJson = NativeBridge.nativeStats(handle)
            val stats = CoreJson.parseStats(statsJson)
            TunnelRepository.setStats(stats)
            TunnelRepository.setContentRules(stats.contentRules.toInt())
            TunnelRepository.setDohDegraded(stats.dohDegraded)
            runCatching { logs.rollUp(lastStats, stats) }
                .onFailure { Log.w(TAG, "could not roll up stats: ${it.message}") }
            lastStats = stats

            val blocked = stats.dnsBlocked + stats.connsBlocked
            if (blocked != lastBlocked) {
                lastBlocked = blocked
                updateNotification(blocked)
            }

            interval = PollSchedule.next(interval, events.size, uiBound)
            delay(interval)
        }
        uiWatch?.cancel()
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // -----------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------

    private fun promoteToForeground(blockedCount: Long) {
        val notification = buildNotification(blockedCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // VPN apps are eligible for specialUse without exception; the subtype property is
            // declared alongside the <service> element in the manifest.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Republishes the notification, at most once per [NOTIFICATION_MIN_INTERVAL_MS].
     *
     * The count changes on most ticks under any real browsing, so this was firing at the full
     * poll rate. Each call is a `notify` binder transaction plus, before the caching below,
     * three more to mint `PendingIntent`s. The number on a persistent notification does not
     * need to be correct to the half-second.
     */
    private fun updateNotification(blockedCount: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationAt = now
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(blockedCount))
        }
    }

    // The three intents never vary, so they are minted once instead of on every rebuild.
    // `PendingIntent.get*` is a binder round trip to ActivityManagerService each time.
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, OmniShieldVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val pauseIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            2,
            Intent(this, OmniShieldVpnService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_PAUSE_MINUTES, 5),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(blockedCount: Long): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_active, blockedCount))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.action_pause_5m),
                    pauseIntent,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(null, getString(R.string.action_stop), stopIntent)
                    .build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // LOW keeps the persistent tunnel notification silent and un-intrusive.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
