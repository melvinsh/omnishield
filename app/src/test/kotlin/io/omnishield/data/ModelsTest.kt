package io.omnishield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {

    @Test
    fun `block rate is a percentage of total queries`() {
        assertEquals(73, Stats(dnsTotal = 150, dnsBlocked = 110).blockRate)
        assertEquals(100, Stats(dnsTotal = 10, dnsBlocked = 10).blockRate)
    }

    @Test
    fun `block rate does not divide by zero before any traffic`() {
        assertEquals(0, Stats().blockRate)
    }

    @Test
    fun `adding session to lifetime sums activity but not filter size`() {
        val lifetime = Stats(dnsTotal = 100, dnsBlocked = 60, filterRules = 431_506, filterBytes = 900)
        val session = Stats(dnsTotal = 20, dnsBlocked = 15, filterRules = 431_506, filterBytes = 900)

        val total = session + lifetime
        assertEquals(120, total.dnsTotal)
        assertEquals(75, total.dnsBlocked)
        // Rule count describes the current filter, not accumulated work — summing it would
        // report double the rules that are actually loaded.
        assertEquals(431_506, total.filterRules)
        assertEquals(900, total.filterBytes)
    }
}

class SettingsTest {

    private val now = 1_000_000L

    @Test
    fun `paused while the deadline is in the future`() {
        val s = Settings(pausedUntil = now + 60_000)
        assertTrue(s.isPaused(now))
        assertFalse(s.filteringActive(now))
    }

    @Test
    fun `snooze expires on its own`() {
        val s = Settings(pausedUntil = now - 1)
        assertFalse(s.isPaused(now))
        assertTrue(s.filteringActive(now))
    }

    @Test
    fun `zero deadline means never paused`() {
        assertFalse(Settings(pausedUntil = 0).isPaused(now))
    }

    @Test
    fun `filtering switched off beats an expired snooze`() {
        val s = Settings(filteringEnabled = false, pausedUntil = 0)
        assertFalse(s.filteringActive(now))
    }

    @Test
    fun `encrypted DNS is the default`() {
        // A privacy app defaulting to plaintext DNS would undercut its own premise.
        assertEquals(UpstreamMode.DOH, Settings().upstreamMode)
        assertTrue(Settings().dohUrl.startsWith("https://"))
    }
}

class AppRuleTest {

    @Test
    fun `a rule with no flags set is empty and should not be stored`() {
        assertTrue(AppRule(uid = 10146).isEmpty)
        assertFalse(AppRule(uid = 10146, blockWifi = true).isEmpty)
        assertFalse(AppRule(uid = 10146, excludeFromTunnel = true).isEmpty)
    }
}
