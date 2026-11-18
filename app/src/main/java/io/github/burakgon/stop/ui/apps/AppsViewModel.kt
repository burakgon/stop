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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppsViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Filter { ALL, BLOCKED, FROZEN, SUSPENDED, ALLOWED, NEW }

    data class Item(
        val app: InstalledApp,
        val rule: AppRule?,
        val isProtected: Boolean,
        val integration: SystemRoles.Integration?,
        val stopsToday: Int,
        /** The daemon froze it permanently, so restoring it is an explicit decision. */
        val recordedFrozen: Boolean = false,
        /** The framework reports it as suspended right now. */
        val recordedSuspended: Boolean = false,
    ) {
        val blocked: Boolean get() = blockable && rule?.blocked == true

        private val blockable: Boolean get() = !isProtected

        /** Disabled or frozen on purpose: it cannot be launched until it is restored. */
        val frozen: Boolean get() = blockable && (recordedFrozen || !app.enabled)

        /** Suspended without being frozen for good ("挂起" on the home screen). */
        val suspended: Boolean get() = blockable && (recordedSuspended || app.suspended) && !frozen
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

    /** Package names the daemon has frozen permanently, as opposed to a plain suspend. */
    private val frozenRecorded = MutableStateFlow<Set<String>>(emptySet())

    /** Package names the framework currently reports as suspended, including plain suspends. */
    private val suspendedRecorded = MutableStateFlow<Set<String>>(emptySet())

    init {
        refreshFrozen()
        refreshSuspended()
    }

    /** Re-reads freeze/suspend state every time the list comes back into view. */
    fun reload() {
        refreshFrozen()
        refreshSuspended()
        viewModelScope.launch { graph.apps.refresh() }
    }

    private val roles = combine(graph.apps.apps.filterNotNull(), graph.rolesChanged) { _, _ -> }
        .map { Roles(graph.roles.protectedPackages(), graph.roles.integrations()) }
        .flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val countsToday = dayStarts().flatMapLatest { graph.events.countsSince(it) }

    val state: StateFlow<State> = combine(
        combine(
            graph.apps.apps.filterNotNull(),
            graph.rules.rules,
            roles,
            frozenRecorded,
            suspendedRecorded,
        ) { apps, rules, roles, frozen, suspended -> Quint(apps, rules, roles, frozen, suspended) },
        countsToday,
        graph.settings.settings,
        query,
        filter,
    ) { (apps, rules, roles, frozen, suspended), counts, settings, query, filter ->
        val all = apps.values
            .filter { it.packageName != graph.context.packageName }
            .filter { settings.showSystemApps || it.isUserFacing || rules[it.packageName]?.blocked == true }
            .map { app -> item(app, rules, roles, counts, frozen, suspended) }
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

    /** Undoes a permanent freeze, then reloads the list so the app is usable again right away. */
    fun thaw(packageName: String) {
        viewModelScope.launch {
            graph.daemon.call { it.setFrozen(packageName, false) }
            graph.apps.refresh()
            refreshFrozen()
            refreshSuspended()
        }
    }

    /** Lifts a plain suspend made from the home screen ("解挂"). */
    fun unsuspend(packageName: String) = thaw(packageName)

    private fun refreshFrozen() {
        viewModelScope.launch {
            val names = graph.daemon.call { it.getFrozen() }.orEmpty()
            frozenRecorded.value = names.split(',').filter { it.isNotBlank() }.toSet()
        }
    }

    /**
     * Asks the daemon which of our packages are suspended. The framework's own per-app flag is not
     * reliable here (it misses suspends made through the shell and through other tools), so the
     * daemon checks the real package state and only reports what it can see.
     */
    private fun refreshSuspended() {
        viewModelScope.launch {
            val names = graph.apps.apps.filterNotNull().first().values.map { it.packageName }
            if (names.isEmpty()) return@launch
            val suspended = graph.daemon.call { it.getSuspended(names.joinToString(",")) }.orEmpty()
            suspendedRecorded.value = suspended.split(',').filter { it.isNotBlank() }.toSet()
        }
    }

    /** Stops per day for one app, for the details sheet. */
    fun history(packageName: String) = graph.events.forPackage(packageName, HISTORY_LIMIT)

    fun weekStart(): Long = daysBefore(System.currentTimeMillis(), 6)

    private fun item(
        app: InstalledApp,
        rules: Map<String, AppRule>,
        roles: Roles,
        counts: Map<String, PackageCount>,
        frozen: Set<String>,
        suspended: Set<String>,
    ) = Item(
        app = app,
        rule = rules[app.packageName],
        isProtected = app.packageName in roles.protected,
        integration = roles.integrations[app.packageName],
        stopsToday = counts[app.packageName]?.count ?: 0,
        recordedFrozen = app.packageName in frozen,
        recordedSuspended = app.packageName in suspended,
    )

    private data class Quint(
        val apps: Map<String, InstalledApp>,
        val rules: Map<String, AppRule>,
        val roles: Roles,
        val frozen: Set<String>,
        val suspended: Set<String>,
    )

    private fun Item.matches(filter: Filter) = when (filter) {
        Filter.ALL -> true
        Filter.BLOCKED -> blocked
        Filter.FROZEN -> frozen
        Filter.SUSPENDED -> suspended
        Filter.ALLOWED -> !blocked && !frozen && !suspended
        Filter.NEW -> isNew
    }

    private companion object {
        const val HISTORY_LIMIT = 200
    }
}
