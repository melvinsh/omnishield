package io.omnishield.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    /** Inserted in batches from the poll loop; individual inserts would thrash the journal. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<LogEntryEntity>)

    @Query(
        """
        SELECT * FROM log_entries
        WHERE (:blockedOnly = 0 OR blocked = 1)
          AND (:query = '' OR host LIKE '%' || :query || '%' OR app LIKE '%' || :query || '%')
          AND (:kind = '' OR kind = :kind)
        ORDER BY ts DESC, id DESC
        LIMIT :limit
        """
    )
    fun observe(
        blockedOnly: Boolean,
        query: String,
        kind: String,
        limit: Int,
    ): Flow<List<LogEntryEntity>>

    @Query("SELECT COUNT(*) FROM log_entries")
    fun count(): Flow<Long>

    @Query("DELETE FROM log_entries")
    suspend fun clear()

    /** Retention pruning. Called after each batch insert. */
    @Query("DELETE FROM log_entries WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Newest row id, or null on an empty table. The cheap half of the hard cap. */
    @Query("SELECT MAX(id) FROM log_entries")
    suspend fun maxId(): Long?

    /**
     * Hard cap independent of age, so a single very busy hour cannot blow up the database.
     *
     * This was `DELETE ... WHERE id NOT IN (SELECT id ... LIMIT :keep)`, which materialises a
     * 50,000-row subquery and was run after *every* batch insert — almost always to delete
     * nothing at all. Ids are monotonic, so the same cap is a single indexed range delete.
     */
    @Query("DELETE FROM log_entries WHERE id <= :oldestKeptId")
    suspend fun deleteUpTo(oldestKeptId: Long)
}

@Dao
interface StatsDao {

    @Upsert
    suspend fun upsert(stat: DailyStatEntity)

    @Query("SELECT * FROM daily_stats WHERE epochDay = :day")
    suspend fun forDay(day: Long): DailyStatEntity?

    @Query("SELECT * FROM daily_stats ORDER BY epochDay DESC LIMIT :days")
    fun recent(days: Int): Flow<List<DailyStatEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(dnsTotal), 0)     AS dnsTotal,
               COALESCE(SUM(dnsBlocked), 0)   AS dnsBlocked,
               COALESCE(SUM(connsTotal), 0)   AS connsTotal,
               COALESCE(SUM(connsBlocked), 0) AS connsBlocked
        FROM daily_stats
        """
    )
    fun lifetime(): Flow<StatTotals>

    @Query("DELETE FROM daily_stats")
    suspend fun clear()
}

data class StatTotals(
    val dnsTotal: Long = 0,
    val dnsBlocked: Long = 0,
    val connsTotal: Long = 0,
    val connsBlocked: Long = 0,
)

@Dao
interface AppRuleDao {

    @Query("SELECT * FROM app_rules")
    fun observeAll(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    suspend fun all(): List<AppRuleEntity>

    @Upsert
    suspend fun upsert(rule: AppRuleEntity)

    @Delete
    suspend fun delete(rule: AppRuleEntity)

    /** A rule with every flag false is absence of policy, not policy — drop the row. */
    @Query(
        "DELETE FROM app_rules WHERE blockWifi = 0 AND blockMobile = 0 AND excludeFromTunnel = 0"
    )
    suspend fun pruneEmpty()
}

@Dao
interface UserRuleDao {

    @Query("SELECT * FROM user_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserRuleEntity>>

    @Query("SELECT * FROM user_rules WHERE allow = :allow ORDER BY domain")
    fun observeByKind(allow: Boolean): Flow<List<UserRuleEntity>>

    @Query("SELECT * FROM user_rules")
    suspend fun all(): List<UserRuleEntity>

    @Upsert
    suspend fun upsert(rule: UserRuleEntity)

    @Query("DELETE FROM user_rules WHERE domain = :domain")
    suspend fun delete(domain: String)
}
