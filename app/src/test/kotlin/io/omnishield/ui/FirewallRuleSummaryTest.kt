package io.omnishield.ui

import io.omnishield.R
import io.omnishield.data.AppRule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four states of a firewall row, in words.
 *
 * Worth its own test because this mapping is the only thing on the screen that says which way
 * the switches run. An inverted branch here would render a plausible sentence describing the
 * opposite of the rule actually stored, and nothing else in the UI would contradict it.
 */
class FirewallRuleSummaryTest {

    private fun rule(wifi: Boolean = false, mobile: Boolean = false) =
        AppRule(uid = 10_042, packageName = "com.example", blockWifi = wifi, blockMobile = mobile)

    @Test
    fun `no rule reads as allowed on both transports`() {
        assertEquals(R.string.firewall_rule_none, ruleSummary(rule()))
    }

    @Test
    fun `each transport is named on its own`() {
        assertEquals(R.string.firewall_rule_wifi, ruleSummary(rule(wifi = true)))
        assertEquals(R.string.firewall_rule_mobile, ruleSummary(rule(mobile = true)))
    }

    @Test
    fun `both blocked is its own wording, not the wifi one`() {
        val both = ruleSummary(rule(wifi = true, mobile = true))
        assertEquals(R.string.firewall_rule_both, both)
    }

    @Test
    fun `every distinct rule gets a distinct string`() {
        val ids = listOf(
            rule(),
            rule(wifi = true),
            rule(mobile = true),
            rule(wifi = true, mobile = true),
        ).map(::ruleSummary)
        assertEquals(4, ids.toSet().size)
    }

    @Test
    fun `excludeFromTunnel does not change the transport wording`() {
        // A separate concept: it routes the app around the tunnel rather than blocking it, and
        // must not be described as a Wi-Fi or mobile block.
        val excluded = rule().copy(excludeFromTunnel = true)
        assertEquals(R.string.firewall_rule_none, ruleSummary(excluded))
    }
}
