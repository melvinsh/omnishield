package io.omnishield.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {

    private lateinit var db: OmniShieldDatabase
    private lateinit var logs: LogDao
    private lateinit var stats: StatsDao
    private lateinit var appRules: AppRuleDao
    private lateinit var userRules: UserRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OmniShieldDatabase::class.java,
        ).build()
        logs = db.logDao()
        stats = db.statsDao()
        appRules = db.appRuleDao()
        userRules = db.userRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entry(
        ts: Long,
        host: String,
        blocked: Boolean = true,
        kind: String = "dns",
        app: String = "com.android.chrome",
    ) = LogEntryEntity(ts = ts, kind = kind, host = host, uid = 10146, app = app, blocked = blocked, rule = "")

    // -- log ----------------------------------------------------------------

    @Test
    fun newest_entries_come_back_first() = runBlocking {
        logs.insertAll(
            listOf(
                entry(1_000, "old.example"),
                entry(3_000, "new.example"),
                entry(2_000, "mid.example"),
            )
        )
        val rows = logs.observe(blockedOnly = false, query = "", kind = "", limit = 10).first()
        assertEquals(listOf("new.example", "mid.example", "old.example"), rows.map { it.host })
    }

    @Test
    fun blocked_only_filters_out_allowed_traffic() = runBlocking {
        logs.insertAll(
            listOf(entry(1, "blocked.example"), entry(2, "allowed.example", blocked = false))
        )
        val rows = logs.observe(blockedOnly = true, query = "", kind = "", limit = 10).first()
        assertEquals(listOf("blocked.example"), rows.map { it.host })
    }

    @Test
    fun search_matches_host_or_app() = runBlocking {
        logs.insertAll(
            listOf(
                entry(1, "ads.example.com"),
                entry(2, "cdn.other.org", app = "org.mozilla.firefox"),
            )
        )
        assertEquals(
            listOf("ads.example.com"),
            logs.observe(false, "example.com", "", 10).first().map { it.host },
        )
        assertEquals(
            listOf("cdn.other.org"),
            logs.observe(false, "firefox", "", 10).first().map { it.host },
        )
    }

    @Test
    fun kind_filter_narrows_to_one_protocol() = runBlocking {
        logs.insertAll(listOf(entry(1, "a.example"), entry(2, "b.example", kind = "tls")))
        assertEquals(
            listOf("b.example"),
            logs.observe(false, "", "tls", 10).first().map { it.host },
        )
    }

    @Test
    fun retention_deletes_entries_older_than_the_window() = runBlocking {
        logs.insertAll(listOf(entry(1_000, "ancient.example"), entry(9_000, "recent.example")))
        logs.deleteOlderThan(5_000)
        val rows = logs.observe(false, "", "", 10).first()
        assertEquals(listOf("recent.example"), rows.map { it.host })
    }

    @Test
    fun hard_cap_keeps_only_the_newest_rows() = runBlocking {
        // A single busy hour must not be able to grow the table without bound, independently
        // of the age-based window.
        //
        // Expressed as a range delete against the newest id rather than the old
        // `DELETE ... WHERE id NOT IN (SELECT ... LIMIT n)`, which materialised a 50,000-row
        // subquery every time it ran. Ids are monotonic, so the two are equivalent — this test
        // is what says so.
        logs.insertAll((1..50).map { entry(it.toLong(), "host$it.example") })
        val newest = logs.maxId()!!
        logs.deleteUpTo(newest - 10)
        val rows = logs.observe(false, "", "", 100).first()
        assertEquals(10, rows.size)
        assertEquals("host50.example", rows.first().host)
        assertEquals("host41.example", rows.last().host)
    }

    @Test
    fun the_hard_cap_is_a_no_op_below_the_limit() = runBlocking {
        // The common case by far: pruning runs on a timer and almost always has nothing to do.
        logs.insertAll((1..5).map { entry(it.toLong(), "host$it.example") })
        val newest = logs.maxId()!!
        if (newest > 10) logs.deleteUpTo(newest - 10)
        assertEquals(5L, logs.count().first())
    }

    @Test
    fun max_id_is_null_on_an_empty_table() = runBlocking {
        // Guards the null check in LogRepository.persist: without it, pruning an empty log
        // would throw on the very first drain.
        assertEquals(null, logs.maxId())
    }

    @Test
    fun clearing_the_log_empties_it() = runBlocking {
        logs.insertAll(listOf(entry(1, "a.example")))
        logs.clear()
        assertEquals(0L, logs.count().first())
    }

    // -- stats --------------------------------------------------------------

    @Test
    fun daily_stats_upsert_by_day() = runBlocking {
        stats.upsert(DailyStatEntity(epochDay = 20_000, dnsTotal = 10, dnsBlocked = 4))
        stats.upsert(DailyStatEntity(epochDay = 20_000, dnsTotal = 25, dnsBlocked = 9))

        val row = stats.forDay(20_000)
        assertNotNull(row)
        assertEquals(25, row!!.dnsTotal)
        assertEquals(9, row.dnsBlocked)
    }

    @Test
    fun lifetime_sums_every_day() = runBlocking {
        stats.upsert(DailyStatEntity(epochDay = 1, dnsTotal = 10, dnsBlocked = 4, connsTotal = 2))
        stats.upsert(DailyStatEntity(epochDay = 2, dnsTotal = 5, dnsBlocked = 1, connsTotal = 3))

        val totals = stats.lifetime().first()
        assertEquals(15, totals.dnsTotal)
        assertEquals(5, totals.dnsBlocked)
        assertEquals(5, totals.connsTotal)
    }

    @Test
    fun lifetime_of_an_empty_table_is_zero_not_null() = runBlocking {
        // COALESCE matters here: a bare SUM over no rows returns NULL and would fail to map.
        val totals = stats.lifetime().first()
        assertEquals(0, totals.dnsTotal)
    }

    // -- rules --------------------------------------------------------------

    @Test
    fun app_rules_upsert_and_prune() = runBlocking {
        appRules.upsert(AppRuleEntity(uid = 2000, packageName = "com.android.shell", blockWifi = true))
        assertEquals(1, appRules.all().size)

        appRules.upsert(AppRuleEntity(uid = 2000, packageName = "com.android.shell"))
        appRules.pruneEmpty()
        assertTrue("a rule with no flags is absence of policy", appRules.all().isEmpty())
    }

    @Test
    fun a_domain_cannot_be_both_allowed_and_blocked() = runBlocking {
        // Enforced by the primary key on `domain` rather than by convention.
        userRules.upsert(UserRuleEntity("example.com", allow = true, createdAt = 1))
        userRules.upsert(UserRuleEntity("example.com", allow = false, createdAt = 2))

        val all = userRules.all()
        assertEquals(1, all.size)
        assertEquals(false, all.first().allow)
    }

    @Test
    fun user_rules_split_by_kind() = runBlocking {
        userRules.upsert(UserRuleEntity("good.example", allow = true, createdAt = 1))
        userRules.upsert(UserRuleEntity("bad.example", allow = false, createdAt = 2))

        assertEquals(listOf("good.example"), userRules.observeByKind(true).first().map { it.domain })
        assertEquals(listOf("bad.example"), userRules.observeByKind(false).first().map { it.domain })
    }

    @Test
    fun deleting_an_override_removes_it() = runBlocking {
        userRules.upsert(UserRuleEntity("example.com", allow = true, createdAt = 1))
        userRules.delete("example.com")
        assertTrue(userRules.all().isEmpty())
        assertNull(stats.forDay(999))
    }
}
