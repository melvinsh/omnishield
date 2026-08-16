package io.omnishield.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LogEntryEntity::class,
        DailyStatEntity::class,
        AppRuleEntity::class,
        UserRuleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OmniShieldDatabase : RoomDatabase() {

    abstract fun logDao(): LogDao
    abstract fun statsDao(): StatsDao
    abstract fun appRuleDao(): AppRuleDao
    abstract fun userRuleDao(): UserRuleDao

    companion object {
        @Volatile
        private var instance: OmniShieldDatabase? = null

        fun get(context: Context): OmniShieldDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): OmniShieldDatabase =
            Room.databaseBuilder(context, OmniShieldDatabase::class.java, "omnishield.db")
                // WAL keeps the poll loop's batch inserts from blocking UI reads. The log is
                // the highest-write table in the app and the UI observes it continuously.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // No destructive fallback: silently wiping a user's history on a schema
                // mistake is worse than a loud failure. Migrations are written explicitly and
                // exercised by the schema-backed migration tests.
                .build()
    }
}
