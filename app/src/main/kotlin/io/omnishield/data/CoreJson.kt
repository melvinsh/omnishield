package io.omnishield.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decoding for the JSON the Rust core hands back across the JNI boundary.
 *
 * Kept free of Android and of any repository state so it can be unit-tested directly — this is
 * the layer where a core-side field rename would otherwise fail silently at runtime.
 *
 * Every parse is total: malformed input yields empty/default values rather than throwing. The
 * caller is a polling loop on the service, and an exception there would kill the coroutine and
 * silently stop all UI updates while the tunnel kept running.
 */
object CoreJson {

    fun parseEvents(json: String): List<LogEntry> = runCatching {
        val array = JSONArray(json)
        buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    LogEntry(
                        seq = o.optLong("seq"),
                        ts = o.optLong("ts"),
                        kind = o.optString("kind"),
                        name = o.optString("name"),
                        uid = o.optInt("uid", -1),
                        app = o.optString("app"),
                        blocked = o.optBoolean("blocked"),
                        rule = o.optString("rule"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun parseStats(json: String): Stats = runCatching {
        val o = JSONObject(json)
        Stats(
            dnsTotal = o.optLong("dns_total"),
            dnsBlocked = o.optLong("dns_blocked"),
            connsTotal = o.optLong("conns_total"),
            connsBlocked = o.optLong("conns_blocked"),
            bytesSaved = o.optLong("bytes_saved"),
            filterRules = o.optLong("filter_rules"),
            filterBytes = o.optLong("filter_bytes"),
            contentRules = o.optLong("content_rules"),
            dnsCached = o.optLong("dns_cached"),
            dohDegraded = o.optBoolean("doh_degraded"),
        )
    }.getOrDefault(Stats())

    /** Serialises per-domain overrides for `nativeSetUserRules`. */
    fun buildUserRules(rules: List<UserRule>): String {
        val array = JSONArray()
        for (rule in rules) {
            array.put(JSONObject().put("domain", rule.domain).put("allow", rule.allow))
        }
        return array.toString()
    }

    /**
     * Builds the config blob consumed by `Config::from_json` in `core/src/config.rs`.
     *
     * Field names here must match the `serde` field names on the Rust side exactly; a
     * mismatch is not a compile error, it silently falls back to that field's default.
     */
    fun buildConfig(
        mtu: Int,
        dnsSentinel: String,
        upstreamDns: List<String>,
        upstreamMode: UpstreamMode,
        dohUrl: String,
        blockQuic: Boolean,
        blockDot: Boolean,
        filteringEnabled: Boolean,
        mitmEnabled: Boolean,
        mitmUids: Collection<Int>,
        blockedUids: Collection<Int>,
        dataDir: String,
        cacheDir: String,
    ): String = JSONObject().apply {
        put("mtu", mtu)
        put("dns_sentinel", dnsSentinel)
        put("upstream_dns", JSONArray(upstreamDns))
        put("upstream_mode", if (upstreamMode == UpstreamMode.DOH) "doh" else "udp")
        put("doh_url", dohUrl)
        put("block_quic", blockQuic)
        put("block_dot", blockDot)
        put("filtering_enabled", filteringEnabled)
        put("mitm_enabled", mitmEnabled)
        put("mitm_uids", JSONArray(mitmUids.toList()))
        put("blocked_uids", JSONArray(blockedUids.toList()))
        put("data_dir", dataDir)
        put("cache_dir", cacheDir)
    }.toString()
}
