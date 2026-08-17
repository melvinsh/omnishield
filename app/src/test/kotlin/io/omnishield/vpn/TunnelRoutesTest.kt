package io.omnishield.vpn

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the production constant: `BigInteger.TWO` is API 33 and `minSdk` is 29. */
private val TWO: BigInteger = BigInteger.valueOf(2)

/**
 * What the tunnel claims, and — more importantly — what it does not.
 *
 * Two failure modes sit on either side of this, and both are silent. Claim too much and every
 * inbound LAN connection dies, which is the bug this replaced. Claim too little and traffic
 * escapes the tunnel unfiltered, which is worse and would look like nothing at all.
 */
class TunnelRoutesTest {

    private fun v4Covers(routes: List<Route>, address: String): Boolean {
        val target = TunnelRoutes.parseV4(address)
        return routes.any { route ->
            if (route.address.contains(':')) return@any false
            val base = TunnelRoutes.parseV4(route.address)
            val size = TWO.pow(32 - route.prefix)
            target >= base && target < base + size
        }
    }

    private fun v6Covers(routes: List<Route>, address: String): Boolean {
        val target = TunnelRoutes.parseV6(address)
        return routes.any { route ->
            val base = TunnelRoutes.parseV6(route.address)
            val size = TWO.pow(128 - route.prefix)
            target >= base && target < base + size
        }
    }

    @Test
    fun `public addresses stay in the tunnel`() {
        val routes = TunnelRoutes.ipv4()
        listOf(
            "1.1.1.1",          // the default DoH resolver
            "8.8.8.8",
            "142.250.102.188",  // a Google front-end seen in the log
            "0.0.0.1",          // first usable address, guards an off-by-one at the bottom
            "223.255.255.255",  // last address below the multicast block
        ).forEach {
            assertTrue("$it must be routed into the tunnel", v4Covers(routes, it))
        }
    }

    @Test
    fun `private ranges are left to the system`() {
        val routes = TunnelRoutes.ipv4()
        listOf(
            "192.168.178.199",  // the LocalSend peer that could not be reached
            "192.168.0.1",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "169.254.1.1",
            "224.0.0.251",      // mDNS
            "239.255.255.250",  // SSDP
            "100.100.100.100",  // shared/CGNAT range
        ).forEach {
            assertFalse("$it must not be routed into the tunnel", v4Covers(routes, it))
        }
    }

    @Test
    fun `172_32 is public and must not be swept up with 172_16`() {
        // The /12 boundary is the classic place to get this wrong: 172.16-172.31 is private,
        // 172.32 is not.
        val routes = TunnelRoutes.ipv4()
        assertTrue(v4Covers(routes, "172.32.0.1"))
        assertTrue(v4Covers(routes, "172.15.255.255"))
        assertFalse(v4Covers(routes, "172.16.0.0"))
    }

    @Test
    fun `the DNS sentinel keeps its route despite living inside 10_8`() {
        // Without this the system resolver every app is handed points at a host reachable only
        // through a route that no longer exists, and name resolution stops entirely.
        val routes = TunnelRoutes.ipv4()
        assertTrue(v4Covers(routes, "10.0.0.53"))
        assertTrue(v4Covers(routes, "10.0.0.2"))
        assertTrue(v4Covers(routes, "10.0.0.1"))
        // Only the tunnel's own /24, not the rest of 10/8.
        assertFalse(v4Covers(routes, "10.0.1.1"))
    }

    @Test
    fun `ipv6 keeps the public internet and drops link-local and ULA`() {
        val routes = TunnelRoutes.ipv6()
        assertTrue(v6Covers(routes, "2606:4700:4700:0:0:0:0:1111"))
        assertTrue(v6Covers(routes, "2001:4860:4860:0:0:0:0:8888"))
        assertFalse(v6Covers(routes, "fe80:0:0:0:0:0:0:1"))
        assertFalse(v6Covers(routes, "ff02:0:0:0:0:0:0:fb"))
        assertFalse(v6Covers(routes, "fc00:0:0:0:0:0:0:1"))
    }

    @Test
    fun `the ipv6 tunnel prefix survives the ULA exclusion`() {
        // fd00::/8 is inside the excluded fc00::/7, so the peer address needs claiming back.
        assertTrue(v6Covers(TunnelRoutes.ipv6(), "fd00:0:0:1:0:0:0:2"))
    }

    @Test
    fun `the route set stays small enough to hand to the system`() {
        // A wrong complement explodes combinatorially; a few dozen is the expected shape.
        // Carving the single broadcast address out of the top of the space would add 24 routes
        // on its own, which is why it is left in.
        assertTrue("${TunnelRoutes.ipv4().size} v4 routes", TunnelRoutes.ipv4().size <= 48)
        assertTrue("${TunnelRoutes.ipv6().size} v6 routes", TunnelRoutes.ipv6().size <= 48)
    }

    @Test
    fun `the complement is exhaustive and disjoint`() {
        // Every address is either excluded or routed, never both, and never neither. Checked
        // over the boundaries of each excluded block rather than all four billion addresses.
        val routes = TunnelRoutes.ipv4().filterNot { it.address == TunnelRoutes.TUN_SUBNET_V4 }
        val probes = listOf(
            "9.255.255.255", "10.0.0.0", "10.255.255.255", "11.0.0.0",
            "172.15.255.255", "172.16.0.0", "172.31.255.255", "172.32.0.0",
            "192.167.255.255", "192.168.0.0", "192.168.255.255", "192.169.0.0",
            "169.253.255.255", "169.254.0.0", "169.254.255.255", "169.255.0.0",
            "223.255.255.255", "224.0.0.0", "255.255.255.255",
        )
        val excluded = setOf(
            "10.0.0.0", "10.255.255.255",
            "172.16.0.0", "172.31.255.255",
            "192.168.0.0", "192.168.255.255",
            "169.254.0.0", "169.254.255.255",
            "224.0.0.0",
        )
        probes.forEach { address ->
            val covered = v4Covers(routes, address)
            assertEquals(
                "$address should ${if (address in excluded) "not " else ""}be routed",
                address !in excluded,
                covered,
            )
        }
    }

    @Test
    fun `address parsing round-trips`() {
        listOf("0.0.0.0", "10.0.0.53", "192.168.178.199", "255.255.255.255").forEach {
            assertEquals(it, TunnelRoutes.formatV4(TunnelRoutes.parseV4(it)))
        }
        assertEquals(
            "fe80:0:0:0:0:0:0:0",
            TunnelRoutes.formatV6(TunnelRoutes.parseV6("fe80::")),
        )
    }
}
