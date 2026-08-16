package io.omnishield.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("omnishield.settings")

/** How upstream DNS queries leave the device. */
enum class UpstreamMode { DOH, UDP }

data class Settings(
    val filteringEnabled: Boolean = true,
    val blockQuic: Boolean = true,
    val blockDot: Boolean = true,
    val mitmEnabled: Boolean = false,
    val mitmUids: Set<Int> = emptySet(),
    /** UIDs observed rejecting our certificate; permanently bypassed. */
    val pinnedUids: Set<Int> = emptySet(),
    val startOnBoot: Boolean = false,
    val onboardingComplete: Boolean = false,
    /**
     * Whether the user last left the tunnel on. Consulted when the system recreates the
     * service with a null intent so a START_STICKY restart cannot resurrect a tunnel the user
     * had deliberately stopped.
     */
    val tunnelDesired: Boolean = false,
    val upstreamMode: UpstreamMode = UpstreamMode.DOH,
    val dohUrl: String = DEFAULT_DOH_URL,
    val upstreamUdp: String = DEFAULT_UDP_RESOLVER,
    /** Epoch millis until which filtering is snoozed; 0 when not paused. */
    val pausedUntil: Long = 0,
    val mtu: Int = 1500,
) {
    fun isPaused(now: Long): Boolean = pausedUntil > now

    /** Filtering is only actually on when enabled *and* not snoozed. */
    fun filteringActive(now: Long): Boolean = filteringEnabled && !isPaused(now)

    companion object {
        /**
         * Addressed by IP literal deliberately. Resolving the resolver's own hostname would
         * need DNS, which is the thing being set up — see `core/src/doh.rs`.
         */
        const val DEFAULT_DOH_URL = "https://1.1.1.1/dns-query"
        const val DEFAULT_UDP_RESOLVER = "1.1.1.1"
    }
}

/**
 * Scalar app settings, backed by Preferences DataStore.
 *
 * Replaces the earlier `RulesStore`, which mixed settings and per-app rules into one
 * `SharedPreferences` blob and exposed them as blocking properties. Per-app rules and the
 * domain allowlist now live in Room ([io.omnishield.data.db]); this holds only scalars, and
 * exposes them as a `Flow` so the service and the UI observe the same source rather than
 * re-reading on their own schedules.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data
        // DataStore surfaces read failures through the flow; falling back to defaults keeps a
        // corrupt preferences file from bricking the app on launch.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setFiltering(enabled: Boolean) = put(Keys.FILTERING, enabled)
    suspend fun setBlockQuic(enabled: Boolean) = put(Keys.BLOCK_QUIC, enabled)
    suspend fun setBlockDot(enabled: Boolean) = put(Keys.BLOCK_DOT, enabled)
    suspend fun setMitmEnabled(enabled: Boolean) = put(Keys.MITM, enabled)
    suspend fun setStartOnBoot(enabled: Boolean) = put(Keys.START_ON_BOOT, enabled)
    suspend fun setOnboardingComplete(done: Boolean) = put(Keys.ONBOARDING, done)
    suspend fun setTunnelDesired(desired: Boolean) = put(Keys.TUNNEL_DESIRED, desired)
    suspend fun setMtu(mtu: Int) = put(Keys.MTU, mtu)
    suspend fun setDohUrl(url: String) = put(Keys.DOH_URL, url)
    suspend fun setUpstreamUdp(server: String) = put(Keys.UPSTREAM_UDP, server)

    suspend fun setUpstreamMode(mode: UpstreamMode) = put(Keys.UPSTREAM_MODE, mode.name)

    /** [until] is an absolute epoch-millis deadline; 0 clears the snooze. */
    suspend fun setPausedUntil(until: Long) = put(Keys.PAUSED_UNTIL, until)

    suspend fun setMitmUids(uids: Set<Int>) = putIntSet(Keys.MITM_UIDS, uids)

    suspend fun addPinnedUid(uid: Int) {
        store.edit { prefs ->
            val existing = prefs[Keys.PINNED_UIDS].orEmpty()
            prefs[Keys.PINNED_UIDS] = existing + uid.toString()
        }
    }

    suspend fun clearPinnedUids() = putIntSet(Keys.PINNED_UIDS, emptySet())

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private suspend fun putIntSet(key: Preferences.Key<Set<String>>, values: Set<Int>) {
        store.edit { it[key] = values.map(Int::toString).toSet() }
    }

    private fun Preferences.toSettings() = Settings(
        filteringEnabled = this[Keys.FILTERING] ?: true,
        blockQuic = this[Keys.BLOCK_QUIC] ?: true,
        blockDot = this[Keys.BLOCK_DOT] ?: true,
        mitmEnabled = this[Keys.MITM] ?: false,
        mitmUids = this[Keys.MITM_UIDS].toIntSet(),
        pinnedUids = this[Keys.PINNED_UIDS].toIntSet(),
        startOnBoot = this[Keys.START_ON_BOOT] ?: false,
        onboardingComplete = this[Keys.ONBOARDING] ?: false,
        tunnelDesired = this[Keys.TUNNEL_DESIRED] ?: false,
        upstreamMode = runCatching {
            UpstreamMode.valueOf(this[Keys.UPSTREAM_MODE] ?: UpstreamMode.DOH.name)
        }.getOrDefault(UpstreamMode.DOH),
        dohUrl = this[Keys.DOH_URL] ?: Settings.DEFAULT_DOH_URL,
        upstreamUdp = this[Keys.UPSTREAM_UDP] ?: Settings.DEFAULT_UDP_RESOLVER,
        pausedUntil = this[Keys.PAUSED_UNTIL] ?: 0L,
        mtu = this[Keys.MTU] ?: 1500,
    )

    private fun Set<String>?.toIntSet(): Set<Int> =
        this?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

    private object Keys {
        val FILTERING = booleanPreferencesKey("filtering_enabled")
        val BLOCK_QUIC = booleanPreferencesKey("block_quic")
        val BLOCK_DOT = booleanPreferencesKey("block_dot")
        val MITM = booleanPreferencesKey("mitm_enabled")
        val MITM_UIDS = stringSetPreferencesKey("mitm_uids")
        val PINNED_UIDS = stringSetPreferencesKey("pinned_uids")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val TUNNEL_DESIRED = booleanPreferencesKey("tunnel_desired")
        val UPSTREAM_MODE = stringPreferencesKey("upstream_mode")
        val DOH_URL = stringPreferencesKey("doh_url")
        val UPSTREAM_UDP = stringPreferencesKey("upstream_udp")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val MTU = intPreferencesKey("mtu")
    }
}
