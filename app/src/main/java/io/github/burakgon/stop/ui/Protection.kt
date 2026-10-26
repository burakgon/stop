package io.github.burakgon.stop.ui

import io.github.burakgon.stop.core.DaemonClient
import io.github.burakgon.stop.core.ShizukuMonitor
import io.github.burakgon.stop.model.AppSettings

/** Everything the UI needs to know about whether protection is working, in one value. */
sealed interface Protection {
    data class Active(val root: Boolean) : Protection
    data object Starting : Protection
    data object Paused : Protection
    data object ShizukuNotInstalled : Protection
    data object ShizukuNotRunning : Protection
    data object NeedsPermission : Protection
    data object PermissionDenied : Protection
    data object ShizukuOutdated : Protection
    data class Failed(val message: String) : Protection

    val isActive: Boolean get() = this is Active

    companion object {
        fun of(settings: AppSettings, shizuku: ShizukuMonitor.Status, daemon: DaemonClient.State): Protection = when (shizuku) {
            ShizukuMonitor.Status.NotInstalled -> ShizukuNotInstalled
            ShizukuMonitor.Status.NotRunning -> ShizukuNotRunning
            ShizukuMonitor.Status.Outdated -> ShizukuOutdated
            ShizukuMonitor.Status.NeedsPermission -> NeedsPermission
            ShizukuMonitor.Status.PermissionDenied -> PermissionDenied
            is ShizukuMonitor.Status.Ready -> when (daemon) {
                is DaemonClient.State.Connected -> if (settings.protectionEnabled) Active(shizuku.isRoot) else Paused
                is DaemonClient.State.Failed -> Failed(daemon.message)
                else -> Starting
            }
        }
    }
}
