package io.github.burakgon.stop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.burakgon.stop.AppGraph
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Creates a [ViewModel] that gets the app's singletons handed in. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.graph
    return viewModel(factory = viewModelFactory { initializer { create(graph) } })
}

/** State shared by every screen: settings and whether protection works. */
class MainViewModel(private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<AppSettings?> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val protection: StateFlow<Protection> = combine(
        graph.settings.settings,
        graph.shizuku.status,
        graph.daemon.state,
        Protection::of,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, Protection.Starting)

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch { graph.settings.update { it.copy(protectionEnabled = enabled) } }
    }

    fun requestShizukuPermission() {
        graph.shizuku.requestPermission()
    }

    fun refresh() {
        graph.shizuku.refresh()
        graph.daemon.connect()
    }
}
