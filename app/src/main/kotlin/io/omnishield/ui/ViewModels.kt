package io.omnishield.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.omnishield.data.AppRule
import io.omnishield.data.FilterRepository
import io.omnishield.data.LogEntry
import io.omnishield.data.LogRepository
import io.omnishield.data.RulesRepository
import io.omnishield.data.Settings
import io.omnishield.data.SettingsRepository
import io.omnishield.data.Stats
import io.omnishield.data.TunnelRepository
import io.omnishield.data.TunnelStatus
import io.omnishield.data.UpstreamMode
import io.omnishield.data.UserRule
import io.omnishield.vpn.OmniShieldVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared base for the screen ViewModels.
 *
 * Repositories are constructed here rather than injected because the app has no DI container
 * and adding one for four call sites would be ceremony. If a third consumer of any repository
 * appears, promote them to a container held by the Application.
 */
abstract class BaseViewModel(app: Application) : AndroidViewModel(app) {
    protected val settingsRepo = SettingsRepository(app)
    protected val rulesRepo = RulesRepository(app)
    protected val logRepo = LogRepository(app)

    protected fun sendToService(action: String, configure: Intent.() -> Unit = {}) {
        val context = getApplication<Application>()
        val intent = Intent(context, OmniShieldVpnService::class.java)
            .setAction(action)
            .apply(configure)
        runCatching {
            if (action == OmniShieldVpnService.ACTION_START) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

// ---------------------------------------------------------------------------

data class DashboardUiState(
    val status: TunnelStatus = TunnelStatus.Stopped,
    val stats: Stats = Stats(),
    val lifetime: Stats = Stats(),
    val filterRules: Int = 0,
    val settings: Settings = Settings(),
    val dohDegraded: Boolean = false,
) {
    val isRunning: Boolean get() = status is TunnelStatus.Running
    val isPaused: Boolean get() = settings.isPaused(System.currentTimeMillis())
}

class DashboardViewModel(app: Application) : BaseViewModel(app) {

    val uiState: StateFlow<DashboardUiState> = combine(
        TunnelRepository.status,
        TunnelRepository.sessionStats,
        logRepo.lifetimeStats,
        settingsRepo.settings,
        combine(TunnelRepository.filterRules, TunnelRepository.dohDegraded, ::Pair),
    ) { status, stats, lifetime, settings, (rules, degraded) ->
        DashboardUiState(
            status = status,
            // Session counters plus everything already rolled into history, so the headline
            // number does not drop to zero every time the tunnel restarts.
            stats = stats + lifetime,
            lifetime = lifetime,
            filterRules = rules,
            settings = settings,
            dohDegraded = degraded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    // There is deliberately no toggleTunnel() here. Starting the tunnel needs an Activity to
    // host the VPN consent dialog and the notification permission request, so the connect
    // action belongs to MainActivity; the version that lived here was unreachable and would
    // have failed silently if anything had ever called it.

    fun setFiltering(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setFiltering(enabled)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    fun setBlockQuic(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setBlockQuic(enabled)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    fun pause(minutes: Int) = sendToService(OmniShieldVpnService.ACTION_PAUSE) {
        putExtra(OmniShieldVpnService.EXTRA_PAUSE_MINUTES, minutes)
    }

    fun resume() = sendToService(OmniShieldVpnService.ACTION_RESUME)
}

// ---------------------------------------------------------------------------

data class LogFilter(
    val blockedOnly: Boolean = false,
    val query: String = "",
    val kind: String = "",
)

class LogViewModel(app: Application) : BaseViewModel(app) {

    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter

    /**
     * The text field reads [filter] directly so typing stays immediate; the database sees a
     * debounced copy. Every keystroke used to open a new `LIKE '%…%'` query over the whole
     * table and throw away the previous one — a table scan per character.
     *
     * Only the query is debounced. The chips are single taps, and delaying those would make the
     * screen feel broken rather than efficient.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val entries: StateFlow<List<LogEntry>> = _filter
        .debounce { if (it.query.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { f -> logRepo.observe(f.blockedOnly, f.query, f.kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val overrides: StateFlow<Map<String, Boolean>> = rulesRepo.allUserRules
        .map { rules -> rules.associate { it.domain to it.allow } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setBlockedOnly(value: Boolean) {
        _filter.value = _filter.value.copy(blockedOnly = value)
    }

    fun setQuery(value: String) {
        _filter.value = _filter.value.copy(query = value)
    }

    fun setKind(value: String) {
        _filter.value = _filter.value.copy(kind = value)
    }

    fun allow(domain: String) = applyOverride { rulesRepo.allow(domain) }

    fun block(domain: String) = applyOverride { rulesRepo.block(domain) }

    fun clearOverride(domain: String) = applyOverride { rulesRepo.clearOverride(domain) }

    fun clearLog() = viewModelScope.launch {
        logRepo.clearLog()
    }

    private fun applyOverride(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        // Push the new override set into the running core straight away — an allowlist entry
        // the user just added is worthless if it only takes effect on the next connect.
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    companion object {
        /** Long enough to swallow a burst of typing, short enough not to feel laggy. */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

// ---------------------------------------------------------------------------

class FirewallViewModel(app: Application) : BaseViewModel(app) {

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps

    val rules: StateFlow<Map<Int, AppRule>> = rulesRepo.allAppRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            _apps.value = loadInstalledApps(getApplication())
        }
    }

    fun setRule(rule: AppRule) = viewModelScope.launch {
        rulesRepo.setAppRule(rule)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }
}

// ---------------------------------------------------------------------------

data class SecurityUiState(
    val caPem: String = "",
    val contentRules: Int = 0,
    val settings: Settings = Settings(),
    /** Whether this device's trust store actually contains our CA. */
    val caInstalled: Boolean = false,
)

class SecurityViewModel(app: Application) : BaseViewModel(app) {

    private val caInstalled = MutableStateFlow(false)

    val uiState: StateFlow<SecurityUiState> = combine(
        TunnelRepository.caPem,
        TunnelRepository.contentRules,
        settingsRepo.settings,
        caInstalled,
    ) { pem, rules, settings, installed ->
        SecurityUiState(
            caPem = pem,
            contentRules = rules,
            settings = settings,
            caInstalled = installed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())

    /**
     * Re-reads the system trust store. Called on every resume, because the user leaves the app
     * to install the certificate and comes back expecting the screen to know.
     */
    fun refreshCaTrust() = viewModelScope.launch {
        val pem = TunnelRepository.caPem.value
        caInstalled.value = withContext(Dispatchers.IO) { isCaInstalled(pem) }
    }

    fun setMitmEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setMitmEnabled(enabled)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    fun setInterceptedUids(uids: Set<Int>) = viewModelScope.launch {
        settingsRepo.setMitmUids(uids)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    /** Clears the "rejected our certificate" mark so the app is tried again. */
    fun unpin(uid: Int) = viewModelScope.launch {
        settingsRepo.removePinnedUid(uid)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }
}

// ---------------------------------------------------------------------------

/** Outcome of a manual list refresh, so the button can report rather than just stop spinning. */
sealed interface RefreshState {
    data object Idle : RefreshState
    data object Running : RefreshState
    data class Done(val count: Int) : RefreshState
    /** Some lists updated, others could not be reached — the failed names are named to the user. */
    data class Partial(val count: Int, val failed: List<String>) : RefreshState
    data object Failed : RefreshState
}

/** Per-list state during a refresh, so each row can show what is happening to it right now. */
enum class ListProgress { Downloading, Done, Failed }

class SettingsViewModel(app: Application) : BaseViewModel(app) {

    private val filterRepo = FilterRepository(app)

    val settings: StateFlow<Settings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _lists = MutableStateFlow<List<FilterRepository.ListStatus>>(emptyList())
    val lists: StateFlow<List<FilterRepository.ListStatus>> = _lists

    private val _refresh = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refresh

    /** Live per-list state while a refresh runs, keyed by list file name. Empty when idle. */
    private val _progress = MutableStateFlow<Map<String, ListProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ListProgress>> = _progress

    val filterRules: StateFlow<Int> = TunnelRepository.filterRules

    init {
        reloadLists()
    }

    fun reloadLists() = viewModelScope.launch {
        _lists.value = withContext(Dispatchers.IO) { filterRepo.status() }
    }

    /**
     * Downloads every list now.
     *
     * `FilterRefreshWorker` still owns the scheduled refresh; this is the manual path, which
     * did not exist. A source that could not be reached is named to the user rather than hidden
     * behind the count of the ones that succeeded — otherwise a list that quietly failed to
     * update looked exactly like a full refresh.
     *
     * Deliberately does **not** push the new lists into a running core. `FilterRefreshWorker`
     * documents why: swapping ~430k rules out from under the packet thread mid-session is not
     * worth the risk for rules that change daily. The result message says so rather than
     * letting the user assume otherwise.
     */
    fun refreshLists() = viewModelScope.launch {
        if (_refresh.value == RefreshState.Running) return@launch
        _refresh.value = RefreshState.Running
        // Every list starts as downloading so the rows show progress immediately, then each
        // flips to Done or Failed the moment its own fetch lands — the user watches the set
        // resolve rather than staring at one opaque spinner.
        val names = (FilterRepository.DNS_SOURCES + FilterRepository.CONTENT_SOURCES).map { it.name }
        _progress.value = names.associateWith { ListProgress.Downloading }

        val result = runCatching {
            filterRepo.refreshEverything { name, ok ->
                _progress.update { it + (name to if (ok) ListProgress.Done else ListProgress.Failed) }
            }
        }.getOrDefault(FilterRepository.RefreshResult(emptyList(), names))

        reloadLists()
        val fetched = result.bodies.size
        _refresh.value = when {
            fetched == 0 -> RefreshState.Failed
            result.failed.isEmpty() -> RefreshState.Done(fetched)
            else -> RefreshState.Partial(fetched, result.failed)
        }
    }

    fun refreshHandled() {
        _refresh.value = RefreshState.Idle
        // The per-list states are kept, not cleared: a row that could not be reached keeps
        // saying so until the next refresh replaces the whole map, rather than snapping back to
        // its plain status the moment the snackbar is dismissed.
    }

    /**
     * Null until DataStore has produced its first value.
     *
     * Needed because `Settings()` defaults `onboardingComplete` to false: rendering against
     * that default would flash the onboarding flow at every launch, including for users who
     * finished it months ago.
     *
     * Deliberately `Eagerly` and left that way. It is the only eager collector here, so it
     * stands out in an efficiency audit — but it exists precisely so the first value is ready
     * before the first composition, and it is owned by the activity's ViewModel, so it costs
     * nothing once the UI is gone. Making it lazy would trade a real user-visible flash for no
     * background saving at all.
     */
    val settingsOrNull: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allowlist: StateFlow<List<UserRule>> = rulesRepo.allUserRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun completeOnboarding() = viewModelScope.launch {
        settingsRepo.setOnboardingComplete(true)
    }

    fun setUpstreamMode(mode: UpstreamMode) = update { setUpstreamMode(mode) }
    fun setDohUrl(url: String) = update { setDohUrl(url) }
    fun setUpstreamUdp(server: String) = update { setUpstreamUdp(server) }
    fun setBlockDot(enabled: Boolean) = update { setBlockDot(enabled) }
    fun setStartOnBoot(enabled: Boolean) = update { setStartOnBoot(enabled) }

    /** Sends the user back through the introduction from Settings. */
    fun replayOnboarding() = viewModelScope.launch {
        settingsRepo.setOnboardingComplete(false)
    }

    fun clearOverride(domain: String) = viewModelScope.launch {
        rulesRepo.clearOverride(domain)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    fun clearHistory() = viewModelScope.launch { logRepo.clearAll() }

    private fun update(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch {
        settingsRepo.block()
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }
}
