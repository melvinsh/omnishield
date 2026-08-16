package io.omnishield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drain cadence. Worth testing directly because the failure modes are asymmetric: too slow
 * and the core's event ring overflows and log rows are lost silently; too fast and the tunnel
 * costs battery for the entire time it is connected.
 */
class PollScheduleTest {

    @Test
    fun `events reset the interval to the floor`() {
        assertEquals(PollSchedule.MIN_MS, PollSchedule.next(PollSchedule.MAX_UI_MS, 1, true))
        assertEquals(
            PollSchedule.MIN_MS,
            PollSchedule.next(PollSchedule.MAX_BACKGROUND_MS, 42, false),
        )
    }

    @Test
    fun `an idle tunnel backs off geometrically`() {
        var i = PollSchedule.MIN_MS
        val seen = mutableListOf(i)
        repeat(8) {
            i = PollSchedule.next(i, 0, false)
            seen.add(i)
        }
        assertEquals(
            listOf(500L, 1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L),
            seen,
        )
    }

    @Test
    fun `the ceiling depends on whether anyone is looking`() {
        var visible = PollSchedule.MIN_MS
        var hidden = PollSchedule.MIN_MS
        repeat(10) {
            visible = PollSchedule.next(visible, 0, true)
            hidden = PollSchedule.next(hidden, 0, false)
        }
        assertEquals(PollSchedule.MAX_UI_MS, visible)
        assertEquals(PollSchedule.MAX_BACKGROUND_MS, hidden)
        assertTrue("a visible UI must be sampled more often", visible < hidden)
    }

    @Test
    fun `ring pressure polls faster than the floor`() {
        val under = PollSchedule.next(PollSchedule.MAX_BACKGROUND_MS, PollSchedule.RING_PRESSURE, false)
        assertTrue(
            "a nearly-full ring must be drained harder than the normal floor",
            under < PollSchedule.MIN_MS,
        )
    }

    @Test
    fun `a full ring drained repeatedly never settles above the floor`() {
        // The scenario that would lose log rows: sustained heavy traffic. The interval must not
        // creep upward while the core keeps handing back large batches.
        var i = PollSchedule.MAX_BACKGROUND_MS
        repeat(20) { i = PollSchedule.next(i, PollSchedule.RING_CAPACITY, false) }
        assertTrue(i <= PollSchedule.MIN_MS)
    }

    @Test
    fun `the interval never drops below the floor while idle`() {
        // Guards the coerceIn: doubling from a sub-floor pressure interval must climb back to
        // the floor rather than stick at 250 ms forever once traffic stops.
        val afterPressure = PollSchedule.next(1000, PollSchedule.RING_CAPACITY, false)
        val thenIdle = PollSchedule.next(afterPressure, 0, false)
        assertTrue("idle must not stay below the floor", thenIdle >= PollSchedule.MIN_MS)
    }
}
