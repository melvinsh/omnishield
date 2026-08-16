package io.omnishield.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which log rows can actually be overridden.
 *
 * The core labels events with whatever identifies them, and only some of those are domains:
 * `dns` and `tls` rows carry a hostname, a plain `tcp` row carries `address:port`, and a
 * content-filter `http` row carries a whole URL. User rules match domains.
 *
 * Before this, the sheet offered "Always allow" on every row and stored the label verbatim, so
 * allowing a TCP row wrote a rule that could never match, listed it in Settings as though it
 * were in force, and left the user believing they had unblocked something.
 */
class OverrideTargetTest {

    @Test
    fun `a hostname is its own target`() {
        assertEquals("ads.example.com", overrideTarget("ads.example.com"))
    }

    @Test
    fun `a url is reduced to its host`() {
        assertEquals(
            "pagead2.googlesyndication.com",
            overrideTarget("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"),
        )
        assertEquals("tracker.example.org", overrideTarget("http://tracker.example.org/px?id=1"))
    }

    @Test
    fun `an IPv4 endpoint has no domain to override`() {
        assertNull(overrideTarget("142.250.102.188:5228"))
        assertNull(overrideTarget("142.250.102.188"))
    }

    @Test
    fun `a bracketed IPv6 endpoint has no domain to override`() {
        assertNull(overrideTarget("[2606:4700:4700::1111]:443"))
    }

    @Test
    fun `a port is stripped from a hostname`() {
        assertEquals("example.com", overrideTarget("example.com:443"))
    }

    @Test
    fun `case and a trailing dot are normalised`() {
        assertEquals("example.com", overrideTarget("Example.COM."))
    }

    @Test
    fun `a bare label is not a domain`() {
        // Single-label names are not something the DNS filter matches against.
        assertNull(overrideTarget("localhost"))
        assertNull(overrideTarget(""))
    }

    @Test
    fun `junk is rejected rather than stored`() {
        assertNull(overrideTarget("not a domain.com"))
        assertNull(overrideTarget("weird_host.example"))
    }
}
