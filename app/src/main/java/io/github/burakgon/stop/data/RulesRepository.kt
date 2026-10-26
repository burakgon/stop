package io.github.burakgon.stop.data

import io.github.burakgon.stop.data.db.RuleDao
import io.github.burakgon.stop.data.db.RuleEntity
import io.github.burakgon.stop.model.AppRule
import io.github.burakgon.stop.model.RuleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RulesRepository(private val dao: RuleDao) {

    /** All rules, keyed by package name. */
    val rules: Flow<Map<String, AppRule>> =
        dao.observeAll().map { list -> list.associate { it.packageName to it.toModel() } }

    suspend fun all(): Map<String, AppRule> = dao.all().associate { it.packageName to it.toModel() }

    suspend fun get(packageName: String): AppRule? = dao.get(packageName)?.toModel()

    /** Records the user's choice for [packageNames], keeping any per-app grace period. */
    suspend fun decide(packageNames: Collection<String>, mode: RuleMode) {
        if (packageNames.isEmpty()) return
        val existing = dao.all().associateBy { it.packageName }
        val now = System.currentTimeMillis()
        dao.upsert(packageNames.map { pkg ->
            RuleEntity(pkg, mode.name, decided = true, graceSeconds = existing[pkg]?.graceSeconds, updatedAt = now)
        })
    }

    /** Adds rules for newly installed apps that still wait for a decision. */
    suspend fun addUndecided(packageNames: Collection<String>, mode: RuleMode) {
        if (packageNames.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.upsert(packageNames.map { RuleEntity(it, mode.name, decided = false, graceSeconds = null, updatedAt = now) })
    }

    suspend fun setGrace(packageName: String, graceSeconds: Int?) {
        val rule = dao.get(packageName) ?: return
        dao.upsert(listOf(rule.copy(graceSeconds = graceSeconds, updatedAt = System.currentTimeMillis())))
    }

    /** Applies the default mode to every rule that is still undecided (when that default changes). */
    suspend fun updateUndecided(mode: RuleMode) {
        val undecided = dao.all().filter { !it.decided && it.mode != mode.name }
        if (undecided.isNotEmpty()) dao.upsert(undecided.map { it.copy(mode = mode.name) })
    }

    suspend fun delete(packageNames: Collection<String>) {
        if (packageNames.isNotEmpty()) dao.delete(packageNames.toList())
    }

    private fun RuleEntity.toModel() = AppRule(
        packageName = packageName,
        mode = runCatching { RuleMode.valueOf(mode) }.getOrDefault(RuleMode.BLOCK),
        decided = decided,
        graceSeconds = graceSeconds,
        updatedAt = updatedAt,
    )
}
