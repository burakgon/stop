package io.github.burakgon.stop.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.burakgon.stop.AppGraph
import io.github.burakgon.stop.core.ShizukuMonitor
import io.github.burakgon.stop.model.InstalledApp
import io.github.burakgon.stop.model.RuleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Step { WELCOME, SHIZUKU, NOTIFICATIONS, CHOOSE }

    data class Candidate(val app: InstalledApp, val suggested: Boolean)

    private val _step = MutableStateFlow(Step.WELCOME)
    val step: StateFlow<Step> = _step.asStateFlow()

    val shizuku: StateFlow<ShizukuMonitor.Status> = graph.shizuku.status

    private val _allowed = MutableStateFlow<Set<String>>(emptySet())

    /** Apps the user lets run in the background; everything else in [candidates] gets blocked. */
    val allowed: StateFlow<Set<String>> = _allowed.asStateFlow()

    val query = MutableStateFlow("")

    private var initialized = false

    val candidates: StateFlow<List<Candidate>?> = graph.apps.apps.filterNotNull()
        .map { apps ->
            val protected = graph.roles.protectedPackages()
            val list = apps.values.filter { it.isUserFacing && it.packageName !in protected }
            val suggested = graph.roles.suggestedAllowed(list, graph.roles.integrations())
            if (!initialized) {
                initialized = true
                // Keep earlier choices (e.g. restored from a backup) and suggest the rest.
                val rules = graph.rules.all()
                _allowed.value = list.mapNotNullTo(HashSet()) { app ->
                    val rule = rules[app.packageName]
                    app.packageName.takeIf { if (rule != null) !rule.blocked else it in suggested }
                }
            }
            list.map { Candidate(it, it.packageName in suggested) }
                .sortedWith(compareByDescending<Candidate> { it.suggested }.thenBy { it.app.label.lowercase() })
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val needsNotificationStep: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(graph.context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** Fixed when onboarding starts, so granting notifications doesn't renumber the steps. */
    val stepCount: Int = if (needsNotificationStep) 3 else 2

    fun next() {
        _step.value = when (_step.value) {
            Step.WELCOME -> Step.SHIZUKU
            Step.SHIZUKU -> if (needsNotificationStep) Step.NOTIFICATIONS else Step.CHOOSE
            Step.NOTIFICATIONS, Step.CHOOSE -> Step.CHOOSE
        }
    }

    fun back(): Boolean {
        _step.value = when (_step.value) {
            Step.WELCOME -> return false
            Step.SHIZUKU -> Step.WELCOME
            Step.NOTIFICATIONS -> Step.SHIZUKU
            Step.CHOOSE -> if (needsNotificationStep) Step.NOTIFICATIONS else Step.SHIZUKU
        }
        return true
    }

    fun requestShizukuPermission() {
        graph.shizuku.requestPermission()
    }

    fun refreshShizuku() {
        graph.shizuku.refresh()
    }

    fun toggle(packageName: String) {
        _allowed.value = _allowed.value.let { if (packageName in it) it - packageName else it + packageName }
    }

    /** Allows or blocks all of [packages] at once, for example every app a search found. */
    fun setAll(packages: Collection<String>, allow: Boolean) {
        _allowed.value = if (allow) _allowed.value + packages else _allowed.value - packages.toSet()
    }

    fun finish() {
        val candidates = candidates.value ?: return
        val allowed = _allowed.value
        viewModelScope.launch {
            val all = candidates.map { it.app.packageName }
            graph.rules.decide(all.filter { it in allowed }, RuleMode.ALLOW)
            graph.rules.decide(all.filter { it !in allowed }, RuleMode.BLOCK)
            graph.settings.update { it.copy(onboarded = true, protectionEnabled = true) }
        }
    }
}
