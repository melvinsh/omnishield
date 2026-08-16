package io.omnishield.data

import android.content.Context
import io.omnishield.data.db.AppRuleEntity
import io.omnishield.data.db.OmniShieldDatabase
import io.omnishield.data.db.UserRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-app policy and per-domain overrides.
 *
 * Both are "rules the user set", so they share a repository even though they land in different
 * tables and are enforced by different layers of the core — app rules by the firewall at
 * connection setup, domain rules by the DNS filter.
 */
class RulesRepository(context: Context) {

    private val db = OmniShieldDatabase.get(context)
    private val appRules = db.appRuleDao()
    private val userRules = db.userRuleDao()

    // -- per-app -------------------------------------------------------------

    val allAppRules: Flow<Map<Int, AppRule>> = appRules.observeAll().map { rows ->
        rows.associate { it.uid to it.toModel() }
    }

    suspend fun setAppRule(rule: AppRule) {
        if (rule.isEmpty) {
            appRules.delete(rule.toEntity())
        } else {
            appRules.upsert(rule.toEntity())
        }
    }

    /**
     * UIDs to drop on the currently active transport.
     *
     * A rule only bites on the transport it names — "block mobile data" must leave the app
     * working on Wi-Fi — so the active transport has to be resolved before this is asked.
     */
    suspend fun blockedUidsFor(onWifi: Boolean): List<Int> =
        appRules.all()
            .filter { if (onWifi) it.blockWifi else it.blockMobile }
            .map { it.uid }

    /** Packages excluded from the tunnel entirely, via `addDisallowedApplication`. */
    suspend fun excludedPackages(): List<String> =
        appRules.all().filter { it.excludeFromTunnel }.map { it.packageName }
            .filter { it.isNotEmpty() }

    // -- per-domain ----------------------------------------------------------

    val allUserRules: Flow<List<UserRule>> =
        userRules.observeAll().map { rows -> rows.map { it.toModel() } }

    val allowlist: Flow<List<UserRule>> =
        userRules.observeByKind(allow = true).map { rows -> rows.map { it.toModel() } }

    val blocklist: Flow<List<UserRule>> =
        userRules.observeByKind(allow = false).map { rows -> rows.map { it.toModel() } }

    suspend fun allow(domain: String) = upsertDomain(domain, allow = true)

    suspend fun block(domain: String) = upsertDomain(domain, allow = false)

    suspend fun clearOverride(domain: String) = userRules.delete(normalise(domain))

    suspend fun snapshot(): List<UserRule> = userRules.all().map { it.toModel() }

    private suspend fun upsertDomain(domain: String, allow: Boolean) {
        val normalised = normalise(domain)
        if (normalised.isEmpty()) return
        userRules.upsert(
            UserRuleEntity(
                domain = normalised,
                allow = allow,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Matches the core's own normalisation in `core/src/filter.rs` — lowercase, trailing dot
     * stripped, and deliberately *no* `www.` stripping, since the suffix walk already covers
     * subdomains and stripping it would widen a rule written for `www.x.com` to all of `x.com`.
     */
    private fun normalise(domain: String): String =
        domain.trim().trimEnd('.').lowercase()
}

private fun AppRuleEntity.toModel() = AppRule(
    uid = uid,
    packageName = packageName,
    blockWifi = blockWifi,
    blockMobile = blockMobile,
    excludeFromTunnel = excludeFromTunnel,
)

private fun AppRule.toEntity() = AppRuleEntity(
    uid = uid,
    packageName = packageName,
    blockWifi = blockWifi,
    blockMobile = blockMobile,
    excludeFromTunnel = excludeFromTunnel,
)

private fun UserRuleEntity.toModel() = UserRule(domain = domain, allow = allow, createdAt = createdAt)
