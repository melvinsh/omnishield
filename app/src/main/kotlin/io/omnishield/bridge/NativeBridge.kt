package io.omnishield.bridge

/**
 * The single JNI seam between Kotlin and the Rust core.
 *
 * Symbol names in `core/src/android.rs` are mangled from this class's fully-qualified name,
 * so renaming this object or its package requires renaming the Rust exports in lockstep.
 *
 * Note the package is `bridge`, not `native` — `native` is a Java reserved keyword and a
 * Kotlin modifier, which makes it unusable as a package segment.
 *
 * Design constraint carried through the whole project: nothing on the packet hot path crosses
 * this boundary. Per-packet JNI calls would negate the reason for writing the core in Rust.
 * Events flow back by polling [nativeDrainEvents] on a timer rather than by native-to-JVM
 * callbacks, and the only Rust→Kotlin calls are per *connection* (protect, UID attribution).
 */
object NativeBridge {

    @Volatile
    private var loaded = false

    /** Loads `libomnishield_core.so` and initialises native logging. Idempotent. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("omnishield_core")
        nativeInit()
        loaded = true
    }

    private external fun nativeInit()

    /** Version string reported by the Rust core. */
    external fun nativeVersion(): String

    /**
     * Takes ownership of [tunFd] and starts the tunnel thread. Returns an opaque handle, or
     * 0 on failure — the caller must close the descriptor itself in that case.
     *
     * [service] must be the live `OmniShieldVpnService`; the core calls back into it for
     * `protect()`, `lookupUid()` and `packageForUid()`.
     */
    external fun nativeStart(service: Any, tunFd: Int, configJson: String): Long

    /** Stops the tunnel, joins its thread and frees the handle. */
    external fun nativeStop(handle: Long)

    /** Replaces the live config: filtering toggle, firewall UIDs, MITM opt-ins. */
    external fun nativeUpdateConfig(handle: Long, configJson: String)

    /**
     * Stages a DNS blocklist. Nothing is searchable until [nativeCommitFilters] runs.
     *
     * Returns the number of lines accepted from this list, not a running total — the total is
     * whatever the commit returns.
     */
    external fun nativeLoadFilters(handle: Long, list: String): Int

    /**
     * Restores both filters from the prebuilt cache identified by [key].
     *
     * Returns the DNS rule count on a hit, or -1 on a miss. On a miss the caller must read and
     * stage the lists and then commit; on a hit it must not, and specifically must not open the
     * ~13 MB of list files at all — avoiding that read is most of the point.
     */
    external fun nativeLoadCachedFilters(handle: Long, key: String): Int

    /** Builds everything staged, persists it under [key], and returns the DNS rule count. */
    external fun nativeCommitFilters(handle: Long, key: String): Int

    external fun nativeClearFilters(handle: Long)

    /**
     * Loads ABP-syntax lists (EasyList and friends) into the Layer 3 content filter. These are
     * *not* interchangeable with the DNS lists passed to [nativeLoadFilters] — ABP syntax
     * expresses per-URL and cosmetic rules that a hosts file cannot.
     */
    external fun nativeLoadContentRules(handle: Long, list: String): Int

    /** PEM of the device-unique root CA. Empty when Layer 2 is unavailable. */
    external fun nativeCaPem(handle: Long): String

    /**
     * Replaces the user's per-domain overrides. Takes a JSON array of
     * `{"domain": "...", "allow": true|false}` and returns the resulting rule count.
     *
     * Replaces rather than merges, so an override removed in the UI is genuinely removed.
     */
    external fun nativeSetUserRules(handle: Long, rulesJson: String): Int

    /** Removes and returns buffered events as a JSON array. */
    external fun nativeDrainEvents(handle: Long): String

    /** Current counters as a JSON object. */
    external fun nativeStats(handle: Long): String
}
