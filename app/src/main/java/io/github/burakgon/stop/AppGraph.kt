package io.github.burakgon.stop

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.burakgon.stop.core.ConfigSync
import io.github.burakgon.stop.core.DaemonClient
import io.github.burakgon.stop.core.PackageEvents
import io.github.burakgon.stop.core.ShizukuCheck
import io.github.burakgon.stop.core.ShizukuMonitor
import io.github.burakgon.stop.daemon.DaemonEvent
import io.github.burakgon.stop.daemon.IDaemonListener
import io.github.burakgon.stop.data.AppIcons
import io.github.burakgon.stop.data.EventsRepository
import io.github.burakgon.stop.data.InstalledApps
import io.github.burakgon.stop.data.RulesRepository
import io.github.burakgon.stop.data.SettingsRepository
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.data.db.StopDatabase
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Process-wide singletons. Created once in [StopApp]. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    private val database = StopDatabase.create(context)
    val rules = RulesRepository(database.rules())
    val events = EventsRepository(context, database.events())
    val apps = InstalledApps(context)
    val icons = AppIcons(context)
    val roles = SystemRoles(context)
    val notifications = Notifications(context, icons)
    val shizuku = ShizukuMonitor(context)
    val daemon = DaemonClient(context, scope, shizuku)
    val packageEvents = PackageEvents(settings, rules, apps, roles, notifications)
    /** Bumped whenever the app comes to the foreground, so default apps are looked up again. */
    val rolesChanged = MutableStateFlow(0)
    val sync = ConfigSync(scope, settings, rules, apps, roles, daemon, rolesChanged)

    private val daemonListener = object : IDaemonListener.Stub() {
        override fun onEvents(events: String) {
            scope.launch { this@AppGraph.events.add(DaemonEvent.listFromJson(events)) }
        }

        override fun onPackagesChanged(packages: Array<String>?) {
            scope.launch { packageEvents.onPackagesChanged(packages?.toList().orEmpty()) }
        }
    }

    fun start() {
        notifications.createChannels()
        daemon.listener = daemonListener
        shizuku.start()
        daemon.start()
        sync.start()
        scope.launch { apps.refresh() }
        scope.launch { events.prune() }
        scope.launch {
            daemon.state.collect { state ->
                if (state is DaemonClient.State.Connected) {
                    notifications.cancelPaused()
                    ShizukuCheck.cancel(context)
                    syncEvents()
                    packageEvents.reconcile()
                }
            }
        }
        scope.launch {
            var wasReady = false
            shizuku.status.collect { status ->
                val ready = status is ShizukuMonitor.Status.Ready
                // Shizuku went away while we were protecting: remind the user if it doesn't come back.
                if (wasReady && !ready) ShizukuCheck.schedule(context, ShizukuCheck.AFTER_LOSS_MS)
                wasReady = ready
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                rolesChanged.value++
                scope.launch {
                    shizuku.refresh()
                    syncEvents()
                }
            }
        })
    }

    /** Waits up to [timeoutMs] for Shizuku to be running with access granted. */
    suspend fun awaitShizuku(timeoutMs: Long): Boolean {
        shizuku.refresh()
        return withTimeoutOrNull(timeoutMs) {
            shizuku.status.first { it is ShizukuMonitor.Status.Ready }
            true
        } ?: false
    }

    /** Copies events the daemon recorded while the app wasn't listening. */
    suspend fun syncEvents() {
        val after = events.latestId()
        val json = daemon.call { it.getEvents(after) } ?: return
        events.add(DaemonEvent.listFromJson(json))
    }

    /** Records a decision and waits briefly until the daemon enforces it. */
    suspend fun decide(packageNames: Collection<String>, mode: RuleMode) {
        rules.decide(packageNames, mode)
        packageNames.forEach(notifications::cancelNewApp)
        sync.awaitInSync(SYNC_TIMEOUT_MS)
    }

    private companion object {
        const val SYNC_TIMEOUT_MS = 8_000L
    }
}

val Context.graph: AppGraph get() = (applicationContext as StopApp).graph
