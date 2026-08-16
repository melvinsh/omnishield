package io.omnishield.ui

import io.omnishield.R
import io.omnishield.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The resolver field only became editable in this pass, so this is the first thing standing
 * between the user and a tunnel that comes up and then resolves nothing.
 *
 * A hostname is the trap. `core/src/doh.rs` addresses the resolver by IP literal deliberately —
 * resolving the resolver's own name would require the DNS being configured — so
 * `https://dns.google/dns-query` looks perfectly reasonable and cannot work.
 */
@RunWith(RobolectricTestRunner::class)
class ResolverValidationTest {

    @Test
    fun `the shipped defaults validate`() {
        assertNull(validateResolver(Settings.DEFAULT_DOH_URL, doh = true))
        assertNull(validateResolver(Settings.DEFAULT_UDP_RESOLVER, doh = false))
    }

    @Test
    fun `a hostname DoH endpoint is rejected`() {
        assertEquals(
            R.string.settings_resolver_invalid_url,
            validateResolver("https://dns.google/dns-query", doh = true),
        )
    }

    @Test
    fun `plain http is rejected even with an IP host`() {
        assertEquals(
            R.string.settings_resolver_invalid_url,
            validateResolver("http://1.1.1.1/dns-query", doh = true),
        )
    }

    @Test
    fun `an IPv6 literal endpoint is accepted`() {
        assertNull(validateResolver("https://[2606:4700:4700::1111]/dns-query", doh = true))
    }

    @Test
    fun `UDP mode wants a bare address, not a URL`() {
        assertNull(validateResolver("9.9.9.9", doh = false))
        assertEquals(
            R.string.settings_resolver_invalid_ip,
            validateResolver("https://9.9.9.9/dns-query", doh = false),
        )
        assertEquals(
            R.string.settings_resolver_invalid_ip,
            validateResolver("dns.quad9.net", doh = false),
        )
    }

    @Test
    fun `octets outside 0-255 are rejected`() {
        assertEquals(
            R.string.settings_resolver_invalid_ip,
            validateResolver("1.1.1.256", doh = false),
        )
        assertEquals(
            R.string.settings_resolver_invalid_ip,
            validateResolver("1.1.1", doh = false),
        )
    }

    @Test
    fun `an empty field is not an error, it is just incomplete`() {
        // The dialog disables Save on blank input; flagging it red as the user clears the
        // field to type a new value would be noise.
        assertNull(validateResolver("", doh = true))
        assertNull(validateResolver("   ", doh = false))
    }
}
