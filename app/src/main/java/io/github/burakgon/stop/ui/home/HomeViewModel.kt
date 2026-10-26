package io.github.burakgon.stop.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.burakgon.stop.AppGraph
import io.github.burakgon.stop.daemon.RunningApp
import io.github.burakgon.stop.model.InstalledApp
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.model.StopEvent
import io.github.burakgon.stop.model.StopReason
import io.github.burakgon.stop.ui.dayStarts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    data class Running(
        val app: InstalledApp?,
        val packageName: String,
        val inUse: Boolean,
        /** elapsedRealtime at which the daemon stops it, or null. */
        val stopsAt: Long?,
    )

    data class Recent(val event: StopEvent, val label: String)

    data class State(
        val loading: Boolean = true,
        val blockedCount: Int = 0,
        val stopsToday: Int = 0,
        val wakeUpsToday: Int = 0,
        val undecided: List<InstalledApp> = emptyList(),
        val newAppMode: RuleMode = RuleMode.BLOCK,
        val running: List<Running> = emptyList(),
        val recent: List<Recent> = emptyList(),
    )

    /** Polls the daemon while Home is on screen; stops when nobody collects. */
    private val running = flow {
        while (true) {
            val json = graph.daemon.call { it.runningBlocked }
            emit(json?.let(RunningApp::listFromJson).orEmpty() to SystemClock.elapsedRealtime())
            delay(POLL_MS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val today = dayStarts().flatMapLatest { graph.events.since(it) }

    val state: StateFlow<State> = combine(
        graph.apps.apps.filterNotNull(),
        graph.rules.rules,
        graph.settings.settings,
        today,
        combine(graph.events.recent(RECENT_COUNT), running) { recent, run -> recent to run },
    ) { apps, rules, settings, todayEvents, (recent, run) ->
        val (runningApps, polledAt) = run
        State(
            loading = false,
            blockedCount = rules.values.count { it.blocked && it.packageName in apps },
            stopsToday = todayEvents.size,
            wakeUpsToday = todayEvents.count { it.reason == StopReason.WOKE },
            undecided = rules.values.filter { !it.decided }.mapNotNull { apps[it.packageName] }.sortedBy { it.label.lowercase() },
            newAppMode = settings.newAppMode,
            running = runningApps.map { app ->
                Running(
                    app = apps[app.packageName],
                    packageName = app.packageName,
                    inUse = app.inUse,
                    stopsAt = if (app.stopInMs >= 0) polledAt + app.stopInMs else null,
                )
            }.sortedWith(compareBy({ it.stopsAt == null }, { it.stopsAt })),
            recent = recent.map { Recent(it, apps[it.packageName]?.label ?: it.packageName) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun decide(packageName: String, mode: RuleMode) {
        viewModelScope.launch { graph.decide(listOf(packageName), mode) }
    }

    fun stopNow(packageName: String) {
        viewModelScope.launch { graph.daemon.call { it.stopNow(arrayOf(packageName)) } }
    }

    fun stopAll() {
        viewModelScope.launch { graph.daemon.call { it.stopAllBlocked() } }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val RECENT_COUNT = 5
    }
}
