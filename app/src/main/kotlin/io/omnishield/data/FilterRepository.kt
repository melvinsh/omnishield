package io.omnishield.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Supplies blocklists to the native filter.
 *
 * A curated list ships in `assets/filters/default.txt` so filtering works offline and on
 * first launch with no network round-trip. Full upstream lists are fetched on demand and
 * cached in `filesDir`, then merged on top.
 *
 * Note these are all *DNS* lists — hosts format and AdGuard-DNS format. EasyList is ABP
 * browser syntax and is not usable here; it belongs to the Phase 5 content filter.
 */
class FilterRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "filters").apply { mkdirs() }

    /** The list bundled in the APK. Always available. */
    fun bundled(): String = runCatching {
        appContext.assets.open("filters/default.txt").bufferedReader().use { it.readText() }
    }.getOrElse {
        Log.e(TAG, "bundled blocklist missing", it)
        ""
    }

    /**
     * Previously downloaded *DNS* lists, if any.
     *
     * Deliberately enumerates [DNS_SOURCES] rather than listing the cache directory: the same
     * directory also holds ABP content lists, and feeding EasyList into the DNS filter would
     * silently produce garbage domain rules.
     */
    fun cached(): List<String> = DNS_SOURCES.mapNotNull { source ->
        File(cacheDir, source.name).takeIf { it.isFile && it.length() > 0 }?.readText()
    }

    /**
     * The outcome of a refresh: the bodies fetched, and the names of the sources that could not
     * be reached. A source that fails is skipped rather than failing the whole refresh — partial
     * coverage beats none — but the failed names are carried out so the caller can tell the user
     * exactly which list did not update instead of reporting a silent partial success.
     */
    data class RefreshResult(val bodies: List<String>, val failed: List<String>)

    /**
     * How hard to try, per source.
     *
     * The timeouts bound *reachability*, not download speed: [readTimeoutMs] is the socket read
     * timeout, i.e. the longest the connection may go silent between bytes, so a slow but alive
     * download keeps resetting it and only an unresponsive host trips it. That is why it can be
     * short without penalising a big list on a slow link. [INTERACTIVE] fails fast so the manual
     * refresh does not leave the user watching a spinner; [BACKGROUND] retries because the
     * scheduled worker runs with nobody waiting.
     */
    data class FetchProfile(val attempts: Int, val connectTimeoutMs: Int, val readTimeoutMs: Int)

    /** Downloads [DNS_SOURCES] and caches them. */
    suspend fun refresh(
        profile: FetchProfile = BACKGROUND,
        onEach: (name: String, ok: Boolean) -> Unit = { _, _ -> },
    ): RefreshResult = fetchAll(DNS_SOURCES, profile, onEach)

    /** Downloads the ABP-syntax lists used by the Layer 3 content filter. */
    suspend fun refreshContentRules(
        profile: FetchProfile = BACKGROUND,
        onEach: (name: String, ok: Boolean) -> Unit = { _, _ -> },
    ): RefreshResult = fetchAll(CONTENT_SOURCES, profile, onEach)

    /**
     * Every list at once, for the manual refresh. Fetches DNS and content sources together so
     * the UI can show them all downloading in parallel and report each as it lands.
     */
    suspend fun refreshEverything(
        onEach: (name: String, ok: Boolean) -> Unit,
    ): RefreshResult = fetchAll(DNS_SOURCES + CONTENT_SOURCES, INTERACTIVE, onEach)

    /**
     * Fetches every source concurrently. Sequential fetches meant one slow or unreachable
     * source — and the largest DNS list is served by a host that rate-limits — held up all the
     * others behind its full retry budget, so the refresh spinner could run for minutes. Running
     * them together bounds the wait to the single slowest source, and [onEach] fires the moment
     * each one lands so the UI can report progress rather than waiting for the whole set.
     */
    private suspend fun fetchAll(
        sources: List<Source>,
        profile: FetchProfile,
        onEach: (name: String, ok: Boolean) -> Unit,
    ): RefreshResult = coroutineScope {
        val results = sources.map { source ->
            async(Dispatchers.IO) {
                val body = runCatching { download(source, profile) }
                    .onFailure { Log.w(TAG, "failed to fetch ${source.name}: ${it.message}") }
                    .getOrNull()
                onEach(source.name, body != null)
                source.name to body
            }
        }.awaitAll()
        RefreshResult(
            bodies = results.mapNotNull { it.second },
            failed = results.filter { it.second == null }.map { it.first },
        )
    }

    fun cachedContentRules(): List<String> = CONTENT_SOURCES.mapNotNull { source ->
        File(cacheDir, source.name).takeIf { it.isFile && it.length() > 0 }?.readText()
    }

    /** True when at least one DNS list has been downloaded already. */
    fun hasCachedDnsLists(): Boolean =
        DNS_SOURCES.any { File(cacheDir, it.name).let { f -> f.isFile && f.length() > 0 } }

    /**
     * What is on disk, for display.
     *
     * The lists were entirely invisible in the UI: no way to see which ones were in use, when
     * they were last fetched, or to fetch them on demand — only a daily worker the user could
     * neither observe nor trigger. Costs a `stat` per source, not a read.
     */
    fun status(): List<ListStatus> = (DNS_SOURCES + CONTENT_SOURCES).map { source ->
        val file = File(cacheDir, source.name)
        val present = file.isFile && file.length() > 0
        ListStatus(
            name = source.name,
            content = source in CONTENT_SOURCES,
            bytes = if (present) file.length() else 0,
            updatedAt = if (present) file.lastModified() else 0,
        )
    }

    data class ListStatus(
        val name: String,
        /** ABP browser-syntax list (Layer 3) rather than a DNS list (Layer 1). */
        val content: Boolean,
        val bytes: Long,
        /** Epoch millis of the last successful download, or 0 if never fetched. */
        val updatedAt: Long,
    ) {
        val downloaded: Boolean get() = updatedAt > 0
    }

    /**
     * Identifies the exact set of list files currently on disk, without reading them.
     *
     * Name, size and modification time of each cached list, plus the bundled asset. That is
     * enough to notice any refresh — the downloader always rewrites a whole file — while
     * costing a handful of `stat` calls instead of 13 MB of I/O. It is the key the native side
     * uses to decide whether its prebuilt filter cache is still valid, and the reason a warm
     * start need not read the lists at all.
     */
    fun sourceKey(): String = buildString {
        append("v1;bundled:").append(bundledSize()).append(';')
        for (source in DNS_SOURCES + CONTENT_SOURCES) {
            val f = File(cacheDir, source.name)
            append(source.name).append(':')
                .append(if (f.isFile) f.length() else -1L).append(':')
                .append(if (f.isFile) f.lastModified() else 0L).append(';')
        }
    }

    private fun bundledSize(): Long = runCatching {
        appContext.assets.openFd("filters/default.txt").use { it.length }
    }.getOrElse {
        // A compressed asset has no direct fd. Falling back to reading it is acceptable
        // because this is the small bundled list, never the 13 MB of downloads.
        runCatching {
            appContext.assets.open("filters/default.txt").use { it.readBytes().size.toLong() }
        }.getOrDefault(0L)
    }

    /**
     * Fetches a source, retrying a few times before giving up.
     *
     * One transient failure used to lose a whole list until the next daily cycle — and the
     * host serving the largest DNS list rate-limits, so that list was the one that reliably
     * failed while the rest succeeded. Retrying with a short backoff turns a flaky fetch into a
     * slow one. Throws only once every attempt has failed, so the caller can tell the user
     * which list did not update rather than silently reporting the others as a full success.
     */
    private fun download(source: Source, profile: FetchProfile): String {
        var lastError: Exception? = null
        repeat(profile.attempts) { attempt ->
            try {
                return fetchOnce(source, profile)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "fetch ${source.name} attempt ${attempt + 1}/${profile.attempts}: ${e.message}")
                if (attempt < profile.attempts - 1) {
                    Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("could not fetch ${source.name}")
    }

    private fun fetchOnce(source: Source, profile: FetchProfile): String {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = profile.connectTimeoutMs
            readTimeout = profile.readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OmniShield/0.2")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            File(cacheDir, source.name).writeText(body)
            Log.i(TAG, "fetched ${source.name} (${body.length} bytes)")
            return body
        } finally {
            connection.disconnect()
        }
    }

    data class Source(val name: String, val url: String)

    companion object {
        private const val TAG = "FilterRepo"

        /** Base backoff between attempts; grows linearly with the attempt number. */
        private const val RETRY_BACKOFF_MS = 1_000L

        /**
         * Manual refresh: one attempt, short timeouts. A dead host is reported as unreachable in
         * about [FetchProfile.readTimeoutMs] rather than leaving the user on a spinner, and the
         * user can simply tap refresh again — that is a retry they control.
         */
        val INTERACTIVE = FetchProfile(attempts = 1, connectTimeoutMs = 8_000, readTimeoutMs = 15_000)

        /** Scheduled worker: retries, since nothing is waiting on it. */
        val BACKGROUND = FetchProfile(attempts = 3, connectTimeoutMs = 10_000, readTimeoutMs = 20_000)

        /**
         * Layer 1 — hosts- and AdGuard-DNS-format sources. StevenBlack is the broad hosts
         * consolidation; the AdGuard DNS filter adds ABP-style `||domain^` rules that hosts
         * files cannot express.
         */
        val DNS_SOURCES = listOf(
            Source(
                "stevenblack-hosts.txt",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            ),
            Source(
                "adguard-dns.txt",
                "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            ),
            // OISD covers telemetry endpoints the other two miss — Sentry, Yandex Metrica,
            // TikTok's business API, Yahoo logging. Measured offline against a 128-host
            // probe, it lifts coverage from 120/128 to 127/128. Costs ~180k extra rules.
            Source("oisd-big.txt", "https://big.oisd.nl/"),
        )

        /**
         * Layer 3 — full ABP syntax, including per-URL network rules and cosmetic
         * (element-hiding) rules. Only meaningful once HTTPS interception is decrypting
         * traffic, so these are loaded but inert until Layer 2 is enabled.
         */
        val CONTENT_SOURCES = listOf(
            Source("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
            Source("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
        )
    }
}
