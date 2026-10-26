package io.github.burakgon.stop.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.burakgon.stop.AppGraph
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.data.db.PackageCount
import io.github.burakgon.stop.model.AppRule
import io.github.burakgon.stop.model.InstalledApp
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.ui.daysBefore
import io.github.burakgon.stop.ui.dayStarts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppsViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Filter { ALL, BLOCKED, ALLOWED, NEW }

    data class Item(
        val app: InstalledApp,
        val rule: AppRule?,
        val isProtected: Boolean,
        val integration: SystemRoles.Integration?,
        val stopsToday: Int,
    ) {
        val blocked: Boolean get() = !isProtected && rule?.blocked == true
        val isNew: Boolean get() = rule != null && !rule.decided
    }

    data class State(
        val loading: Boolean = true,
        val items: List<Item> = emptyList(),
        /** Every listed app regardless of search and filter, for the details sheet. */
        val all: Map<String, Item> = emptyMap(),
        val counts: Map<Filter, Int> = emptyMap(),
        val showSystem: Boolean = false,
        val defaultGraceSeconds: Int = 60,
    )

    private data class Roles(val protected: Set<String>, val integrations: Map<String, SystemRoles.Integration>)

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(Filter.ALL)

    private val roles = combine(graph.apps.apps.filterNotNull(), graph.rolesChanged) { _, _ -> }
        .map { Roles(graph.roles.protectedPackages(), graph.roles.integrations()) }
        .flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val countsToday = dayStarts().flatMapLatest { graph.events.countsSince(it) }

    val state: StateFlow<State> = combine(
        combine(graph.apps.apps.filterNotNull(), graph.rules.rules, roles) { apps, rules, roles -> Triple(apps, rules, roles) },
        countsToday,
        graph.settings.settings,
        query,
        filter,
    ) { (apps, rules, roles), counts, settings, query, filter ->
        val all = apps.values
            .filter { it.packageName != graph.context.packageName }
            .filter { settings.showSystemApps || it.isUserFacing || rules[it.packageName]?.blocked == true }
            .map { app -> item(app, rules, roles, counts) }
        val matching = if (query.isBlank()) all else {
            val q = query.trim()
            all.filter { it.app.label.contains(q, ignoreCase = true) || it.app.packageName.contains(q, ignoreCase = true) }
        }
        State(
            loading = false,
            all = all.associateBy { it.app.packageName },
            items = matching.filter { it.matches(filter) }
                .sortedWith(compareByDescending<Item> { it.isNew }.thenBy { it.app.label.lowercase() }),
            counts = Filter.entries.associateWith { f -> matching.count { it.matches(f) } },
            showSystem = settings.showSystemApps,
            defaultGraceSeconds = settings.graceSeconds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun setBlocked(packageName: String, blocked: Boolean) = decide(listOf(packageName), if (blocked) RuleMode.BLOCK else RuleMode.ALLOW)

    fun decide(packageNames: List<String>, mode: RuleMode) {
        viewModelScope.launch { graph.decide(packageNames, mode) }
    }

    fun setGrace(packageName: String, seconds: Int?) {
        viewModelScope.launch { graph.rules.setGrace(packageName, seconds) }
    }

    fun setShowSystem(show: Boolean) {
        viewModelScope.launch { graph.settings.update { it.copy(showSystemApps = show) } }
    }

    fun stopNow(packageName: String) {
        viewModelScope.launch { graph.daemon.call { it.stopNow(arrayOf(packageName)) } }
    }

    /** Stops per day for one app, for the details sheet. */
    fun history(packageName: String) = graph.events.forPackage(packageName, HISTORY_LIMIT)

    fun weekStart(): Long = daysBefore(System.currentTimeMillis(), 6)

    private fun item(app: InstalledApp, rules: Map<String, AppRule>, roles: Roles, counts: Map<String, PackageCount>) = Item(
        app = app,
        rule = rules[app.packageName],
        isProtected = app.packageName in roles.protected,
        integration = roles.integrations[app.packageName],
        stopsToday = counts[app.packageName]?.count ?: 0,
    )

    private fun Item.matches(filter: Filter) = when (filter) {
        Filter.ALL -> true
        Filter.BLOCKED -> blocked
        Filter.ALLOWED -> !blocked
        Filter.NEW -> isNew
    }

    private companion object {
        const val HISTORY_LIMIT = 200
    }
}
