package io.omnishield.vpn

import java.math.BigInteger

/** One CIDR block, in the form `VpnService.Builder.addRoute` wants. */
data class Route(val address: String, val prefix: Int)

/**
 * Which destinations the tunnel claims.
 *
 * The tunnel used to take `0.0.0.0/0` and `::/0` — literally everything — and that broke every
 * inbound connection from the local network. The inbound SYN still reaches the device on the
 * physical interface, because a VPN cannot intercept that, but the *reply* is routed by
 * destination, matches the default route, and is handed to the TUN. The core only ever creates
 * a socket when it peeks a SYN (see `core/src/runtime.rs`), so a SYN-ACK for a 4-tuple it has
 * never seen matches nothing and is dropped. The peer waits and gives up. Anything that listens
 * — a file transfer app, a media server, `adb connect`, a desktop reaching the phone — simply
 * did not work while OmniShield was on, and nothing in the app said so.
 *
 * So the private ranges are left to the system and the tunnel takes the rest. Two consequences
 * worth stating plainly:
 *
 * 1. **LAN traffic is no longer filtered.** That is the intended trade and it is what every
 *    VPN-based blocker does; ad and tracker domains do not live on `192.168.0.0/16`. The one
 *    real loss is an app that hardcodes a LAN DNS server, which now bypasses filtering — apps
 *    using the system resolver are unaffected, because that is the sentinel below.
 * 2. **The tunnel's own subnet is claimed back explicitly.** [TUN_SUBNET_V4] sits inside
 *    `10.0.0.0/8`, and it carries the DNS sentinel every app on the device is told to use.
 *    Excluding it along with the rest of `10/8` would send every DNS query out of the physical
 *    interface addressed to a host that does not exist, and resolution would stop dead.
 *
 * `Builder.excludeRoute` would express this directly but is API 33+, and `minSdk` is 29. The
 * complement is computed instead, which works everywhere and — unlike mixing included and
 * excluded routes — leaves no question about which of two overlapping rules wins.
 */
object TunnelRoutes {

    /** The core assigns itself 10.0.0.1/24; the sentinel and the peer address live here too. */
    const val TUN_SUBNET_V4 = "10.0.0.0"
    const val TUN_SUBNET_V4_PREFIX = 24

    /** ULA prefix carrying the IPv6 peer address. */
    const val TUN_SUBNET_V6 = "fd00:0:0:1::"
    const val TUN_SUBNET_V6_PREFIX = 64

    /**
     * Left to the system.
     *
     * RFC 1918 private space, link-local (169.254/16, what a device falls back to with no
     * DHCP), the 100.64/10 shared range — carriers and Tailscale both use it, and a peer there
     * is reachable the same way a LAN peer is — and multicast, because mDNS and SSDP live at
     * 224.0.0.251 and 239.255.255.250 and device discovery needs them.
     *
     * The limited broadcast address is deliberately **not** excluded: carving out a single
     * address from the top of the space costs 24 extra routes, and a broadcast datagram that
     * reaches the core is dropped there anyway.
     */
    private val LAN_V4 = listOf(
        "10.0.0.0" to 8,
        "172.16.0.0" to 12,
        "192.168.0.0" to 16,
        "169.254.0.0" to 16,
        "100.64.0.0" to 10,
        "224.0.0.0" to 4,
    )

    /** Link-local (fe80::/10), unique local (fc00::/7) and multicast (ff00::/8). */
    private val LAN_V6 = listOf(
        "fe80::" to 10,
        "fc00::" to 7,
        "ff00::" to 8,
    )

    /** Everything except [LAN_V4], plus the tunnel's own subnet. */
    fun ipv4(): List<Route> =
        complement(32, LAN_V4.map { (a, p) -> parseV4(a) to p }).map { (n, p) ->
            Route(formatV4(n), p)
        } + Route(TUN_SUBNET_V4, TUN_SUBNET_V4_PREFIX)

    /** Everything except [LAN_V6], plus the tunnel's own prefix. */
    fun ipv6(): List<Route> =
        complement(128, LAN_V6.map { (a, p) -> parseV6(a) to p }).map { (n, p) ->
            Route(formatV6(n), p)
        } + Route(TUN_SUBNET_V6, TUN_SUBNET_V6_PREFIX)

    /**
     * The largest CIDR blocks that cover the whole address space without touching [excluded].
     *
     * Recursive halving: a block clear of every exclusion is kept whole, a block wholly inside
     * one is dropped, and anything straddling a boundary is split and reconsidered. Recursion
     * only follows blocks that straddle, so this terminates in a few dozen steps and yields a
     * handful of routes rather than thousands.
     */
    internal fun complement(
        bits: Int,
        excluded: List<Pair<BigInteger, Int>>,
    ): List<Pair<BigInteger, Int>> {
        val ranges = excluded.map { (base, prefix) ->
            base to base + span(bits, prefix) - BigInteger.ONE
        }
        val out = mutableListOf<Pair<BigInteger, Int>>()

        fun walk(base: BigInteger, prefix: Int) {
            val last = base + span(bits, prefix) - BigInteger.ONE
            if (ranges.any { base >= it.first && last <= it.second }) return
            if (ranges.none { base <= it.second && last >= it.first }) {
                out += base to prefix
                return
            }
            // Straddles a boundary: split. A single address cannot be split further, and one
            // that reaches here overlaps an exclusion, so it is dropped.
            if (prefix == bits) return
            val half = span(bits, prefix + 1)
            walk(base, prefix + 1)
            walk(base + half, prefix + 1)
        }

        walk(BigInteger.ZERO, 0)
        return out
    }

    private fun span(bits: Int, prefix: Int): BigInteger = TWO.pow(bits - prefix)

    /**
     * `BigInteger.TWO` is API 33 and `minSdk` is 29, so referencing it would have thrown
     * `NoSuchFieldError` the moment the tunnel started on Android 10 through 12. The unit
     * tests run on a desktop JVM, where the field exists, and passed regardless — lint caught
     * it, which is the whole reason lint gates the build.
     */
    private val TWO: BigInteger = BigInteger.valueOf(2)

    internal fun parseV4(address: String): BigInteger =
        address.split('.').fold(BigInteger.ZERO) { acc, octet ->
            acc.shiftLeft(8) + BigInteger.valueOf(octet.toLong())
        }

    internal fun formatV4(value: BigInteger): String {
        var v = value
        val octets = IntArray(4)
        for (i in 3 downTo 0) {
            octets[i] = v.and(BigInteger.valueOf(255)).toInt()
            v = v.shiftRight(8)
        }
        return octets.joinToString(".")
    }

    /** Only has to handle the literals above, so `::` expansion is the only shorthand needed. */
    internal fun parseV6(address: String): BigInteger {
        val (head, tail) = address.split("::").let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }
        val headGroups = head.split(':').filter { it.isNotEmpty() }
        val tailGroups = tail.split(':').filter { it.isNotEmpty() }
        val fill = 8 - headGroups.size - tailGroups.size
        val groups = headGroups + List(fill) { "0" } + tailGroups
        return groups.fold(BigInteger.ZERO) { acc, group ->
            acc.shiftLeft(16) + BigInteger(group, 16)
        }
    }

    internal fun formatV6(value: BigInteger): String {
        var v = value
        val groups = Array(8) { "" }
        for (i in 7 downTo 0) {
            groups[i] = v.and(BigInteger.valueOf(0xFFFF)).toString(16)
            v = v.shiftRight(16)
        }
        // Full form. Android parses it fine and it keeps this function trivial to verify.
        return groups.joinToString(":")
    }
}
