package io.omnishield.data

/** What the tunnel is doing. */
sealed interface TunnelStatus {
    data object Stopped : TunnelStatus
    data object Starting : TunnelStatus
    data class Running(val since: Long) : TunnelStatus

    /**
     * The tunnel could not be established, or the core refused to start.
     *
     * This case exists because the previous implementation logged such failures and then
     * rendered as plain "Not protected", which is indistinguishable from the user having
     * simply not connected. A privacy tool that is silently off is worse than one that is
     * loudly broken.
     */
    data class Failed(val reason: String) : TunnelStatus
}

data class LogEntry(
    /** Process-unique sequence from the core; the UI list key. */
    val seq: Long,
    val ts: Long,
    val kind: String,
    val name: String,
    val uid: Int,
    val app: String,
    val blocked: Boolean,
    val rule: String,
)

data class Stats(
    val dnsTotal: Long = 0,
    val dnsBlocked: Long = 0,
    val connsTotal: Long = 0,
    val connsBlocked: Long = 0,
    val bytesSaved: Long = 0,
    val filterRules: Long = 0,
    /** Heap held by the core's compact domain sets, so memory can be shown, not guessed. */
    val filterBytes: Long = 0,
    /** Compiled ABP content rules currently loaded in the core. */
    val contentRules: Long = 0,
    /** DNS answers the core served from its own cache instead of asking upstream. */
    val dnsCached: Long = 0,
    /** True when DoH was requested but the core had to fall back to plaintext UDP. */
    val dohDegraded: Boolean = false,
) {
    /** Share of DNS queries refused, as a percentage. */
    val blockRate: Int
        get() = if (dnsTotal == 0L) 0 else ((dnsBlocked * 100) / dnsTotal).toInt()

    operator fun plus(other: Stats) = Stats(
        dnsTotal = dnsTotal + other.dnsTotal,
        dnsBlocked = dnsBlocked + other.dnsBlocked,
        connsTotal = connsTotal + other.connsTotal,
        connsBlocked = connsBlocked + other.connsBlocked,
        bytesSaved = bytesSaved + other.bytesSaved,
        dnsCached = dnsCached + other.dnsCached,
        // Not additive: these describe the current filter or transport, not accumulated
        // activity.
        filterRules = maxOf(filterRules, other.filterRules),
        filterBytes = maxOf(filterBytes, other.filterBytes),
        contentRules = maxOf(contentRules, other.contentRules),
        dohDegraded = dohDegraded || other.dohDegraded,
    )
}

/** A domain override the user set from the log. */
data class UserRule(val domain: String, val allow: Boolean, val createdAt: Long)

data class AppRule(
    val uid: Int,
    val packageName: String = "",
    val blockWifi: Boolean = false,
    val blockMobile: Boolean = false,
    val excludeFromTunnel: Boolean = false,
) {
    val isEmpty: Boolean get() = !blockWifi && !blockMobile && !excludeFromTunnel
}
