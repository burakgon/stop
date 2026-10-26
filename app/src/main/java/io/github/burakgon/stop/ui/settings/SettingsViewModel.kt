package io.github.burakgon.stop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.burakgon.stop.AppGraph
import io.github.burakgon.stop.core.DaemonClient
import io.github.burakgon.stop.core.ShizukuMonitor
import io.github.burakgon.stop.model.AppSettings
import io.github.burakgon.stop.model.RuleMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<AppSettings?> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val shizuku: StateFlow<ShizukuMonitor.Status> = graph.shizuku.status
    val daemon: StateFlow<DaemonClient.State> = graph.daemon.state
    val shizukuVersion: String? = graph.shizuku.managerVersion()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    fun setNewAppMode(mode: RuleMode) {
        viewModelScope.launch {
            graph.settings.update { it.copy(newAppMode = mode) }
            graph.rules.updateUndecided(mode)
        }
    }

    /** Lets every app run again and removes all restrictions the daemon applied. */
    fun undoAll(onDone: () -> Unit) {
        viewModelScope.launch {
            graph.daemon.call { it.resetRestrictions() }
            graph.decide(graph.rules.all().keys, RuleMode.ALLOW)
            onDone()
        }
    }

    fun clearHistory(onDone: () -> Unit) {
        viewModelScope.launch {
            graph.events.clear()
            onDone()
        }
    }

    fun requestShizukuPermission() {
        graph.shizuku.requestPermission()
    }

    fun refreshDaemon() {
        viewModelScope.launch { graph.daemon.refreshInfo() }
    }
}
