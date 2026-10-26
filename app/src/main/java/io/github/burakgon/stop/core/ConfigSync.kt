package io.github.burakgon.stop.core

import android.util.Log
import io.github.burakgon.stop.daemon.DaemonConfig
import io.github.burakgon.stop.data.InstalledApps
import io.github.burakgon.stop.data.RulesRepository
import io.github.burakgon.stop.data.SettingsRepository
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.model.AppRule
import io.github.burakgon.stop.model.AppSettings
import io.github.burakgon.stop.model.InstalledApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Turns settings and rules into a [DaemonConfig] and pushes it whenever either changes. */
class ConfigSync(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val rules: RulesRepository,
    private val apps: InstalledApps,
    private val roles: SystemRoles,
    private val daemon: DaemonClient,
    rolesChanged: Flow<Int>,
) {
    private val _pushed = MutableStateFlow<String?>(null)

    /** The last configuration the daemon accepted. */
    val pushed: StateFlow<String?> = _pushed.asStateFlow()

    private val protectedPackages: Flow<Set<String>> = combine(apps.apps.filterNotNull(), rolesChanged) { _, _ -> }
        .map { withContext(Dispatchers.IO) { roles.protectedPackages() } }
        .distinctUntilChanged()

    val config: Flow<DaemonConfig?> = combine(
        settings.settings,
        rules.rules,
        apps.apps.filterNotNull(),
        protectedPackages,
    ) { s, r, installed, protected -> build(s, r, installed, protected) }
        .distinctUntilChanged()

    fun start() {
        scope.launch {
            combine(daemon.service, config) { service, config -> service to config }.collectLatest { (service, config) ->
                if (service == null || config == null) return@collectLatest
                val json = config.toJson()
                val accepted = withContext(Dispatchers.IO) {
                    try {
                        service.applyConfig(json)
                        true
                    } catch (e: Exception) {
                        Log.w("Stop", "pushing config failed", e)
                        false
                    }
                }
                if (accepted) _pushed.value = json
            }
        }
    }

    /** Waits until the daemon has the latest configuration, e.g. before a receiver finishes. */
    suspend fun awaitInSync(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        daemon.connect()
        val latest = config.first() ?: return@withTimeoutOrNull true
        val json = latest.toJson()
        pushed.first { it == json }
        true
    } ?: false

    private fun build(
        settings: AppSettings,
        rules: Map<String, AppRule>,
        installed: Map<String, InstalledApp>,
        protected: Set<String>,
    ): DaemonConfig? {
        if (!settings.onboarded) return null
        return DaemonConfig(
            enabled = settings.protectionEnabled,
            graceMs = settings.graceSeconds * 1_000L,
            allowOngoing = settings.allowOngoing,
            restrictBackground = settings.extraRestrictions,
            restrictStandby = settings.extraRestrictions,
            userId = apps.userId,
            rules = rules.values.mapNotNull { rule ->
                val app = installed[rule.packageName] ?: return@mapNotNull null
                rule.packageName to DaemonConfig.Rule(
                    uid = app.uid,
                    blocked = rule.blocked && rule.packageName !in protected,
                    graceMs = rule.graceSeconds?.let { it * 1_000L },
                )
            }.toMap(),
            protectedPackages = protected,
        )
    }
}
