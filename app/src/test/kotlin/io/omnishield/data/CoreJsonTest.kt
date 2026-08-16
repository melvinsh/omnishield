package io.omnishield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the JNI decoding seam.
 *
 * A field renamed on the Rust side does not break the build — it silently decodes as zero or
 * empty at runtime — so this is the layer where such a mistake has to be caught. The field
 * names asserted here must match the `serde` names in `core/src/events.rs` and
 * `core/src/config.rs`.
 *
 * Robolectric supplies the real `org.json`; the stub in plain unit tests throws on every call.
 */
@RunWith(RobolectricTestRunner::class)
class CoreJsonTest {

    @Test
    fun `parses an event batch`() {
        val json = """
            [
              {"seq":1,"ts":1000,"kind":"dns","name":"ads.example.com","uid":10146,
               "app":"com.android.chrome","blocked":true,"rule":"example.com"},
              {"seq":2,"ts":1001,"kind":"tcp","name":"1.2.3.4:443","uid":-1,
               "app":"","blocked":false,"rule":""}
            ]
        """.trimIndent()

        val events = CoreJson.parseEvents(json)
        assertEquals(2, events.size)
        assertEquals(1L, events[0].seq)
        assertEquals("ads.example.com", events[0].name)
        assertEquals(10146, events[0].uid)
        assertTrue(events[0].blocked)
        assertEquals("example.com", events[0].rule)
        assertFalse(events[1].blocked)
        assertEquals(-1, events[1].uid)
    }

    @Test
    fun `malformed event json yields an empty list rather than throwing`() {
        // The caller is a polling loop; an exception there would silently stop all UI updates
        // while the tunnel kept running.
        assertTrue(CoreJson.parseEvents("not json").isEmpty())
        assertTrue(CoreJson.parseEvents("").isEmpty())
        assertTrue(CoreJson.parseEvents("{}").isEmpty())
    }

    @Test
    fun `skips malformed entries but keeps the good ones`() {
        val events = CoreJson.parseEvents("""[null,{"seq":7,"name":"a.com"}]""")
        assertEquals(1, events.size)
        assertEquals(7L, events[0].seq)
    }

    @Test
    fun `parses stats using the core's snake_case field names`() {
        val json = """
            {"dns_total":150,"dns_blocked":110,"conns_total":17,"conns_blocked":1,
             "bytes_saved":42,"filter_rules":431506,"filter_bytes":9000000,
             "dns_cached":64,"doh_degraded":true}
        """.trimIndent()

        val stats = CoreJson.parseStats(json)
        assertEquals(150L, stats.dnsTotal)
        assertEquals(110L, stats.dnsBlocked)
        assertEquals(17L, stats.connsTotal)
        assertEquals(1L, stats.connsBlocked)
        assertEquals(431506L, stats.filterRules)
        assertEquals(9_000_000L, stats.filterBytes)
        assertEquals(64L, stats.dnsCached)
        // Folded into the same parse. This used to be a second full JSONObject() over the very
        // same string, twice a second, for one boolean.
        assertTrue(stats.dohDegraded)
    }

    @Test
    fun `absent doh flag is not treated as degraded`() {
        assertFalse(CoreJson.parseStats("""{"dns_total":1}""").dohDegraded)
        assertFalse(CoreJson.parseStats("garbage").dohDegraded)
    }

    @Test
    fun `builds a config the core will accept`() {
        val json = CoreJson.buildConfig(
            mtu = 1500,
            dnsSentinel = "10.0.0.53",
            upstreamDns = listOf("1.1.1.1", "1.0.0.1"),
            upstreamMode = UpstreamMode.DOH,
            dohUrl = "https://1.1.1.1/dns-query",
            blockQuic = true,
            blockDot = true,
            filteringEnabled = true,
            mitmEnabled = false,
            mitmUids = listOf(10146),
            blockedUids = listOf(2000),
            dataDir = "/data/data/io.omnishield/files/ca",
            cacheDir = "/data/data/io.omnishield/files/filtercache",
        )

        // Field names are the contract with serde on the Rust side.
        for (field in listOf(
            "\"mtu\"", "\"dns_sentinel\"", "\"upstream_dns\"", "\"upstream_mode\"",
            "\"doh_url\"", "\"block_quic\"", "\"block_dot\"", "\"filtering_enabled\"",
            "\"mitm_enabled\"", "\"mitm_uids\"", "\"blocked_uids\"", "\"data_dir\"",
            "\"cache_dir\"",
        )) {
            assertTrue("config is missing $field", json.contains(field))
        }
        assertTrue(json.contains("\"upstream_mode\":\"doh\""))
    }

    @Test
    fun `udp mode serialises as udp`() {
        val json = CoreJson.buildConfig(
            mtu = 1500,
            dnsSentinel = "10.0.0.53",
            upstreamDns = listOf("9.9.9.9"),
            upstreamMode = UpstreamMode.UDP,
            dohUrl = "",
            blockQuic = false,
            blockDot = false,
            filteringEnabled = false,
            mitmEnabled = false,
            mitmUids = emptyList(),
            blockedUids = emptyList(),
            dataDir = "",
            cacheDir = "",
        )
        assertTrue(json.contains("\"upstream_mode\":\"udp\""))
    }

    @Test
    fun `serialises user rules for the core`() {
        val json = CoreJson.buildUserRules(
            listOf(
                UserRule("good.example.com", allow = true, createdAt = 1),
                UserRule("bad.example.com", allow = false, createdAt = 2),
            )
        )
        assertTrue(json.contains("\"domain\":\"good.example.com\""))
        assertTrue(json.contains("\"allow\":true"))
        assertTrue(json.contains("\"allow\":false"))
    }

    @Test
    fun `empty override list serialises as an empty array`() {
        // Must not be null or "[]"-adjacent: the core replaces its rule set with whatever it
        // is handed, so this is how the last override gets removed.
        assertEquals("[]", CoreJson.buildUserRules(emptyList()))
    }
}
