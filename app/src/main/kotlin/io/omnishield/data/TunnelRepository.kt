package io.omnishield.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable tunnel state, shared between the VPN service and the UI.
 *
 * There used to be a `liveLog` here as well: the service copied up to 300 entries into it twice
 * a second, and nothing anywhere read it — the log screen reads Room. It was removed rather
 * than left as a cheap-looking write, because "cheap" twice a second forever is not cheap.
 *
 * A process-level singleton by necessity rather than by preference: the service and the
 * activity have no lifecycle relationship, and the UI must render correct state after the
 * activity is recreated or the service restarts. What changed from the previous design is that
 * screens no longer read this directly — they go through ViewModels, and this object is only
 * written by the service and the repositories.
 */
object TunnelRepository {

    private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    /** Counters from the current core session. Resets when the tunnel restarts. */
    private val _sessionStats = MutableStateFlow(Stats())
    val sessionStats: StateFlow<Stats> = _sessionStats.asStateFlow()

    private val _filterRules = MutableStateFlow(0)
    val filterRules: StateFlow<Int> = _filterRules.asStateFlow()

    private val _contentRules = MutableStateFlow(0)
    val contentRules: StateFlow<Int> = _contentRules.asStateFlow()

    private val _caPem = MutableStateFlow("")
    val caPem: StateFlow<String> = _caPem.asStateFlow()

    /**
     * True when DNS-over-HTTPS was requested but the core had to fall back to plaintext UDP.
     *
     * Surfaced rather than swallowed: silently downgrading the transport would leave the user
     * believing their queries are encrypted when they are not.
     */
    private val _dohDegraded = MutableStateFlow(false)
    val dohDegraded: StateFlow<Boolean> = _dohDegraded.asStateFlow()

    fun setStatus(status: TunnelStatus) {
        _status.value = status
    }

    fun setStats(stats: Stats) {
        _sessionStats.value = stats
    }

    fun setFilterRules(count: Int) {
        _filterRules.value = count
    }

    fun setContentRules(count: Int) {
        _contentRules.value = count
    }

    fun setCaPem(pem: String) {
        _caPem.value = pem
    }

    fun setDohDegraded(degraded: Boolean) {
        _dohDegraded.value = degraded
    }

    /**
     * True while the activity is started, i.e. some screen is on-screen.
     *
     * The service uses this to decide how hard to poll the core. Driven by the activity's
     * lifecycle rather than by a flow's subscriber count: only the dashboard collects
     * [status], so a subscriber-count derivation would report "no UI" while the user was
     * sitting on the log tab watching rows arrive — and the log would then update on the
     * 30-second background cadence.
     */
    private val _uiVisible = MutableStateFlow(false)
    // A StateFlow already conflates and dedupes, so no distinctUntilChanged is needed here.
    val uiActive: StateFlow<Boolean> = _uiVisible.asStateFlow()

    fun setUiVisible(visible: Boolean) {
        _uiVisible.value = visible
    }

    /** Called when a tunnel session ends so a stale session's counters are not shown. */
    fun resetSession() {
        _sessionStats.value = Stats()
        _dohDegraded.value = false
    }
}
