package io.omnishield.data

import android.content.Context
import io.omnishield.data.db.DailyStatEntity
import io.omnishield.data.db.LogEntryEntity
import io.omnishield.data.db.OmniShieldDatabase
import androidx.room.withTransaction
import io.omnishield.data.db.StatTotals
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable request history and rolled-up counters.
 *
 * The core's counters live in memory and vanish with the tunnel, so anything the user should
 * still see tomorrow has to be accumulated here.
 */
class LogRepository(context: Context) {

    private val db = OmniShieldDatabase.get(context)
    private val logDao = db.logDao()
    private val statsDao = db.statsDao()

    val lifetimeStats: Flow<Stats> = statsDao.lifetime().map { it.toStats() }

    val entryCount: Flow<Long> = logDao.count()

    fun observe(
        blockedOnly: Boolean,
        query: String,
        kind: String,
        limit: Int = DEFAULT_PAGE,
    ): Flow<List<LogEntry>> =
        logDao.observe(blockedOnly, query.trim(), kind, limit).map { rows ->
            rows.map { it.toModel() }
        }

    fun recentDays(days: Int): Flow<List<DailyStatEntity>> = statsDao.recent(days)

    /**
     * Persists a drained batch, pruning only when pruning could plausibly do something.
     *
     * All three statements used to run on every batch — twice a second during any traffic —
     * as three separate implicit transactions, and the hard-cap one materialised a
     * 50,000-row subquery each time to delete, almost always, nothing. Retention windows are
     * measured in days and the cap in tens of thousands of rows, so checking them on a
     * five-minute timer enforces exactly the same limits at a tiny fraction of the cost.
     *
     * The insert itself still happens every batch; only the pruning is deferred.
     */
    suspend fun persist(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        db.withTransaction {
            logDao.insertAll(entries.map { it.toEntity() })
        }

        val now = System.currentTimeMillis()
        if (now - lastPrune < PRUNE_INTERVAL_MILLIS) return
        lastPrune = now
        db.withTransaction {
            logDao.deleteOlderThan(now - RETENTION_MILLIS)
            // Ids are monotonic, so "keep the newest MAX_ROWS" is a range delete rather than
            // an anti-join against a materialised subquery.
            val newest = logDao.maxId()
            if (newest != null && newest > MAX_ROWS) {
                logDao.deleteUpTo(newest - MAX_ROWS)
            }
        }
    }

    /**
     * Folds a core stats snapshot into today's row.
     *
     * The core reports cumulative totals for the current session, so the delta since the last
     * snapshot is what gets added. A snapshot that goes *backwards* means the core restarted
     * and its counters reset, so the new value is treated as the whole delta.
     */
    suspend fun rollUp(previous: Stats, current: Stats) {
        val delta = if (current.dnsTotal < previous.dnsTotal) current else current.minus(previous)
        if (delta.isZero()) return

        // Accumulate in memory and write periodically. A read plus a write per poll tick is a
        // lot of journal traffic for a counter whose only consumer is a number on a dashboard;
        // batching it loses at most PENDING_FLUSH_MILLIS of history if the process is killed,
        // and `flushPending` is called on tunnel stop for the orderly case.
        pending = pending + delta
        val now = System.currentTimeMillis()
        if (now - lastRollUp < PENDING_FLUSH_MILLIS) return
        lastRollUp = now
        flushPending()
    }

    /** Writes any accumulated counter deltas. Called on a timer and at tunnel stop. */
    suspend fun flushPending() {
        val delta = pending
        if (delta.isZero()) return
        pending = Stats()

        val day = epochDay()
        val existing = statsDao.forDay(day) ?: DailyStatEntity(epochDay = day)
        statsDao.upsert(
            existing.copy(
                dnsTotal = existing.dnsTotal + delta.dnsTotal,
                dnsBlocked = existing.dnsBlocked + delta.dnsBlocked,
                connsTotal = existing.connsTotal + delta.connsTotal,
                connsBlocked = existing.connsBlocked + delta.connsBlocked,
            )
        )
    }

    suspend fun clearLog() = logDao.clear()

    suspend fun clearAll() {
        logDao.clear()
        statsDao.clear()
    }

    private fun epochDay(): Long =
        Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private var pending = Stats()
    private var lastRollUp = 0L
    private var lastPrune = 0L

    private companion object {
        const val DEFAULT_PAGE = 500
        const val MAX_ROWS = 50_000
        val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val PRUNE_INTERVAL_MILLIS = 5L * 60 * 1000
        const val PENDING_FLUSH_MILLIS = 30L * 1000
    }
}

private fun Stats.minus(other: Stats) = Stats(
    dnsTotal = (dnsTotal - other.dnsTotal).coerceAtLeast(0),
    dnsBlocked = (dnsBlocked - other.dnsBlocked).coerceAtLeast(0),
    connsTotal = (connsTotal - other.connsTotal).coerceAtLeast(0),
    connsBlocked = (connsBlocked - other.connsBlocked).coerceAtLeast(0),
    bytesSaved = (bytesSaved - other.bytesSaved).coerceAtLeast(0),
    filterRules = filterRules,
)

private fun Stats.isZero() =
    dnsTotal == 0L && dnsBlocked == 0L && connsTotal == 0L && connsBlocked == 0L

private fun StatTotals.toStats() = Stats(
    dnsTotal = dnsTotal,
    dnsBlocked = dnsBlocked,
    connsTotal = connsTotal,
    connsBlocked = connsBlocked,
)

private fun LogEntryEntity.toModel() = LogEntry(
    seq = id,
    ts = ts,
    kind = kind,
    name = host,
    uid = uid,
    app = app,
    blocked = blocked,
    rule = rule,
)

private fun LogEntry.toEntity() = LogEntryEntity(
    ts = ts,
    kind = kind,
    host = name,
    uid = uid,
    app = app,
    blocked = blocked,
    rule = rule,
)
