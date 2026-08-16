package io.omnishield.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One filtered request. This is a firehose table — a busy device produces thousands of rows an
 * hour — so it is indexed for the queries the UI actually runs and pruned on a retention
 * window rather than kept forever.
 */
@Entity(
    tableName = "log_entries",
    indices = [Index("ts"), Index("host"), Index("uid"), Index("blocked")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Milliseconds since epoch. */
    val ts: Long,
    /** `dns`, `tcp`, `tls` or `http`. */
    val kind: String,
    val host: String,
    val uid: Int,
    val app: String,
    val blocked: Boolean,
    /** Rule or reason behind the verdict; empty when allowed. */
    val rule: String,
)

/**
 * Rolled-up counters, one row per day.
 *
 * The core's counters are in-memory and reset with the tunnel, so aggregate history has to be
 * accumulated here. Keyed on epoch-day rather than a timestamp so a day's row can be upserted
 * cheaply as the tunnel runs.
 */
@Entity(tableName = "daily_stats")
data class DailyStatEntity(
    @PrimaryKey val epochDay: Long,
    val dnsTotal: Long = 0,
    val dnsBlocked: Long = 0,
    val connsTotal: Long = 0,
    val connsBlocked: Long = 0,
)

/**
 * Per-app policy.
 *
 * [blockWifi]/[blockMobile] are the firewall — the app keeps running but its connections are
 * dropped. [excludeFromTunnel] is different in kind: it removes the app from the VPN entirely
 * via `addDisallowedApplication`, which is the escape hatch for anything the userspace stack
 * or the MITM upsets.
 */
@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey val uid: Int,
    val packageName: String,
    val blockWifi: Boolean = false,
    val blockMobile: Boolean = false,
    val excludeFromTunnel: Boolean = false,
)

/**
 * A user override for a single domain.
 *
 * One table with an [allow] flag rather than separate allow/block tables: the two are mutually
 * exclusive for a given domain, and a single primary key on `domain` makes that impossible to
 * violate.
 */
@Entity(tableName = "user_rules")
data class UserRuleEntity(
    @PrimaryKey val domain: String,
    /** true = never block this domain; false = always block it. */
    val allow: Boolean,
    val createdAt: Long,
)
