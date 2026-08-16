package io.omnishield.data

/**
 * How often the service drains events and counters from the core.
 *
 * Extracted from the service so the schedule can be tested without an emulator, a tunnel or a
 * running coroutine — the decision is pure arithmetic and is much easier to get wrong than it
 * looks. It replaced a fixed 500 ms tick that ran for the entire life of the tunnel: with the
 * screen off, with no UI bound, and for the full duration of a filtering snooze.
 */
object PollSchedule {

    /** Floor, and the interval used whenever events are flowing. */
    const val MIN_MS = 500L

    /** Ceiling while a screen is showing tunnel state — still live to the eye. */
    const val MAX_UI_MS = 2_000L

    /**
     * Ceiling with nothing on screen. Nothing is rendering the data, so the only hard
     * requirement is that the core's event ring cannot overflow between drains.
     */
    const val MAX_BACKGROUND_MS = 30_000L

    /** The core's ring capacity, mirrored from `EventLog::new(2000)` in `core/src/runtime.rs`. */
    const val RING_CAPACITY = 2_000

    /** Batch size treated as ring pressure: three quarters full in a single drain. */
    const val RING_PRESSURE = RING_CAPACITY * 3 / 4

    /**
     * The next wait, given the last one and what that drain returned.
     *
     * Three rules, in precedence order:
     *
     *  1. A nearly-full drain means the ring is filling faster than it is being emptied, and an
     *     overflow silently discards log entries. Go below the floor — losing rows would be a
     *     functional regression, not a cheaper one.
     *  2. Anything at all to report resets to the floor, so the log stays live while browsing.
     *  3. Nothing to report doubles the wait, up to a ceiling that depends on whether anyone is
     *     actually looking.
     */
    fun next(current: Long, drained: Int, uiVisible: Boolean): Long {
        val ceiling = if (uiVisible) MAX_UI_MS else MAX_BACKGROUND_MS
        return when {
            drained >= RING_PRESSURE -> MIN_MS / 2
            drained > 0 -> MIN_MS
            else -> (current * 2).coerceIn(MIN_MS, ceiling)
        }
    }
}
