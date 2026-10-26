package io.github.burakgon.stop.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.burakgon.stop.data.SystemRoles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Tracks whether Shizuku is installed, running and has granted Stop access. */
class ShizukuMonitor(private val context: Context) {

    sealed interface Status {
        data object NotInstalled : Status
        data object NotRunning : Status
        data object Outdated : Status
        data object NeedsPermission : Status
        data object PermissionDenied : Status
        data class Ready(val uid: Int, val apiVersion: Int) : Status {
            val isRoot: Boolean get() = uid == 0
        }
    }

    private val _status = MutableStateFlow(compute())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        _status.value = compute()
    }

    /** Shows Shizuku's own permission dialog. Returns false when Shizuku isn't running. */
    fun requestPermission(): Boolean {
        if (!isAlive()) return false
        return try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun openShizukuIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(SystemRoles.SHIZUKU_PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Version of the Shizuku app, for display. */
    fun managerVersion(): String? = try {
        context.packageManager.getPackageInfo(SystemRoles.SHIZUKU_PACKAGE, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun isAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SystemRoles.SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun compute(): Status {
        if (!isAlive()) return if (isInstalled()) Status.NotRunning else Status.NotInstalled
        return try {
            when {
                Shizuku.isPreV11() -> Status.Outdated
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    Status.Ready(Shizuku.getUid(), Shizuku.getVersion())
                Shizuku.shouldShowRequestPermissionRationale() -> Status.PermissionDenied
                else -> Status.NeedsPermission
            }
        } catch (_: Throwable) {
            Status.NotRunning
        }
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 42
    }
}
