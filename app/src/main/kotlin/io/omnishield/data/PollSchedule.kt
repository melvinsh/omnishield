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

    /** Above this per-drain batch the interval halves — the ring is filling briskly. */
    const val SHRINK_THRESHOLD = RING_CAPACITY / 4

    /** Below this per-drain batch the interval keeps doubling — the ring is in no danger. */
    const val GROW_THRESHOLD = RING_CAPACITY / 16

    /**
     * The next wait, given the last one and what that drain returned.
     *
     * With a screen showing tunnel state, any event snaps back to the floor so the log stays
     * live to the eye. With nothing on screen, the cadence follows *volume*, not activity: the
     * only hard requirement is that the ring cannot overflow between drains, so the interval
     * doubles while batches are small, holds while they are moderate, and halves once a batch
     * says the ring is filling. The old rule — any event at all resets to the floor — meant
     * every allowed DNS query (each one is an event) restarted the ~30 s climb back to the
     * ceiling, and ordinary background traffic kept the loop at 2 Hz all night with the
     * screen off.
     *
     * The precedence, screen off: pressure < shrink < hold < grow. At the 30 s ceiling the
     * hold band tops out at [RING_PRESSURE] per drain (~50 events/s) with the snap-to-250 ms
     * rule above it, the same overflow margin the old schedule had.
     */
    fun next(current: Long, drained: Int, uiVisible: Boolean): Long {
        val ceiling = if (uiVisible) MAX_UI_MS else MAX_BACKGROUND_MS
        return when {
            drained >= RING_PRESSURE -> MIN_MS / 2
            uiVisible && drained > 0 -> MIN_MS
            drained >= SHRINK_THRESHOLD -> (current / 2).coerceIn(MIN_MS, ceiling)
            drained > GROW_THRESHOLD -> current.coerceIn(MIN_MS, ceiling)
            else -> (current * 2).coerceIn(MIN_MS, ceiling)
        }
    }
}
