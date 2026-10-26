package io.github.burakgon.stop.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.burakgon.stop.AppGraph
import io.github.burakgon.stop.data.EventsRepository
import io.github.burakgon.stop.model.StopEvent
import io.github.burakgon.stop.model.StopReason
import io.github.burakgon.stop.ui.daysBefore
import io.github.burakgon.stop.ui.startOfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivityViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Filter { ALL, WOKE, LEFT, MANUAL }

    /** One line in the list; consecutive identical stops of the same app are folded together. */
    data class Row(val event: StopEvent, val label: String, val count: Int)

    data class Day(val start: Long, val rows: List<Row>)

    data class ChartDay(val start: Long, val stops: Int)

    data class State(
        val loading: Boolean = true,
        val days: List<Day> = emptyList(),
        val week: List<ChartDay> = emptyList(),
        val weekTotal: Int = 0,
        val isEmpty: Boolean = true,
    )

    val filter = MutableStateFlow(Filter.ALL)

    private val since = System.currentTimeMillis() - EventsRepository.RETENTION_MS

    val state: StateFlow<State> = combine(
        graph.events.since(since),
        graph.apps.apps.filterNotNull(),
        filter,
    ) { events, apps, filter ->
        val now = System.currentTimeMillis()
        val weekStart = daysBefore(now, 6)
        val week = (6 downTo 0).map { daysAgo ->
            val start = daysBefore(now, daysAgo)
            ChartDay(start, events.count { startOfDay(it.time) == start })
        }
        val shown = events.filter { it.matches(filter) }
        State(
            loading = false,
            days = shown.groupBy { startOfDay(it.time) }
                .map { (start, dayEvents) -> Day(start, fold(dayEvents) { apps[it]?.label ?: it }) }
                .sortedByDescending { it.start },
            week = week,
            weekTotal = events.count { it.time >= weekStart },
            isEmpty = events.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun clearHistory() {
        viewModelScope.launch { graph.events.clear() }
    }

    private fun StopEvent.matches(filter: Filter) = when (filter) {
        Filter.ALL -> true
        Filter.WOKE -> reason == StopReason.WOKE
        Filter.LEFT -> reason == StopReason.LEFT
        Filter.MANUAL -> reason == StopReason.MANUAL || reason == StopReason.RUNNING
    }

    /** [events] are newest first; runs of the same app and reason within an hour become one row. */
    private fun fold(events: List<StopEvent>, label: (String) -> String): List<Row> {
        val rows = ArrayList<Row>()
        for (event in events) {
            val last = rows.lastOrNull()
            if (last != null &&
                last.event.packageName == event.packageName &&
                last.event.reason == event.reason &&
                last.event.time - event.time < FOLD_WINDOW_MS
            ) {
                rows[rows.lastIndex] = last.copy(count = last.count + 1)
            } else {
                rows += Row(event, label(event.packageName), 1)
            }
        }
        return rows
    }

    private companion object {
        const val FOLD_WINDOW_MS = 60 * 60_000L
    }
}
