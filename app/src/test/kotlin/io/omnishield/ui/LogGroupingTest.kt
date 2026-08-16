package io.omnishield.ui

import io.omnishield.data.LogEntry
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Day bucketing for the log.
 *
 * The reason this exists at all: rows are stamped `HH:mm:ss` only, so without a day break a
 * row from last week looks exactly like one from a minute ago. The reason it is *tested* is
 * that the obvious implementation — comparing formatted date strings, or dividing the epoch by
 * 86 400 000 — silently disagrees with the local calendar either side of midnight and across a
 * DST change.
 */
@RunWith(RobolectricTestRunner::class)
class LogGroupingTest {

    private var seq = 0L

    private fun at(cal: Calendar) = LogEntry(
        seq = seq++,
        ts = cal.timeInMillis,
        kind = "dns",
        name = "example.com",
        uid = 0,
        app = "",
        blocked = false,
        rule = "",
    )

    private fun today(hour: Int, minute: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `entries from one day form a single group`() {
        val groups = groupByDay(listOf(at(today(23)), at(today(9)), at(today(1))))
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().second.size)
    }

    @Test
    fun `midnight splits two adjacent entries into separate days`() {
        val justBefore = today(23, 59)
        val justAfter = (justBefore.clone() as Calendar).apply { add(Calendar.MINUTE, 2) }

        val groups = groupByDay(listOf(at(justAfter), at(justBefore)))

        assertEquals(2, groups.size)
        assertEquals(1, groups[0].second.size)
        assertEquals(1, groups[1].second.size)
    }

    @Test
    fun `groups run newest day first`() {
        val now = today(12)
        val earlier = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -3) }

        val groups = groupByDay(listOf(at(earlier), at(now)))

        assertEquals(2, groups.size)
        assertTrue("newest day must lead", groups[0].first > groups[1].first)
    }

    @Test
    fun `the day key is local midnight, not an epoch division`() {
        val noon = today(12)
        val key = startOfDay(noon.timeInMillis)
        val asCalendar = Calendar.getInstance().apply { timeInMillis = key }

        assertEquals(0, asCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, asCalendar.get(Calendar.MINUTE))
        assertEquals(0, asCalendar.get(Calendar.SECOND))
        assertEquals(0, asCalendar.get(Calendar.MILLISECOND))
        assertEquals(noon.get(Calendar.DAY_OF_YEAR), asCalendar.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun `an empty log produces no groups`() {
        assertEquals(emptyList<Pair<Long, List<LogEntry>>>(), groupByDay(emptyList()))
    }
}
