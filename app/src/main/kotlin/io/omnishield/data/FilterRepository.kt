package io.omnishield.data

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * Downloads [DNS_SOURCES] and caches them. Returns the bodies that were fetched
     * successfully; a source that fails is skipped rather than failing the whole refresh,
     * since partial coverage beats none.
     */
    suspend fun refresh(): List<String> = withContext(Dispatchers.IO) {
        DNS_SOURCES.mapNotNull { source ->
            runCatching { download(source) }
                .onFailure { Log.w(TAG, "failed to fetch ${source.name}: ${it.message}") }
                .getOrNull()
        }
    }

    /** Downloads the ABP-syntax lists used by the Layer 3 content filter. */
    suspend fun refreshContentRules(): List<String> = withContext(Dispatchers.IO) {
        CONTENT_SOURCES.mapNotNull { source ->
            runCatching { download(source) }
                .onFailure { Log.w(TAG, "failed to fetch ${source.name}: ${it.message}") }
                .getOrNull()
        }
    }

    fun cachedContentRules(): List<String> = CONTENT_SOURCES.mapNotNull { source ->
        File(cacheDir, source.name).takeIf { it.isFile && it.length() > 0 }?.readText()
    }

    /** True when at least one DNS list has been downloaded already. */
    fun hasCachedDnsLists(): Boolean =
        DNS_SOURCES.any { File(cacheDir, it.name).let { f -> f.isFile && f.length() > 0 } }

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

    private fun download(source: Source): String {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "OmniShield/0.1")
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
