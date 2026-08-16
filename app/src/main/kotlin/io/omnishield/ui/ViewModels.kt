package io.omnishield.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.omnishield.data.AppRule
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun toggleTunnel() {
        if (uiState.value.isRunning) {
            sendToService(OmniShieldVpnService.ACTION_STOP)
        } else {
            sendToService(OmniShieldVpnService.ACTION_START)
        }
    }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<LogEntry>> = _filter
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
}

// ---------------------------------------------------------------------------

class AppsViewModel(app: Application) : BaseViewModel(app) {

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
)

class SecurityViewModel(app: Application) : BaseViewModel(app) {

    val uiState: StateFlow<SecurityUiState> = combine(
        TunnelRepository.caPem,
        TunnelRepository.contentRules,
        settingsRepo.settings,
    ) { pem, rules, settings ->
        SecurityUiState(caPem = pem, contentRules = rules, settings = settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())

    fun setMitmEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setMitmEnabled(enabled)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }

    fun setInterceptedUids(uids: Set<Int>) = viewModelScope.launch {
        settingsRepo.setMitmUids(uids)
        sendToService(OmniShieldVpnService.ACTION_REFRESH)
    }
}

// ---------------------------------------------------------------------------

class SettingsViewModel(app: Application) : BaseViewModel(app) {

    val settings: StateFlow<Settings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

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
    fun setBlockDot(enabled: Boolean) = update { setBlockDot(enabled) }
    fun setStartOnBoot(enabled: Boolean) = update { setStartOnBoot(enabled) }

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
