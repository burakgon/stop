package io.github.burakgon.stop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.burakgon.stop.core.ShizukuCheck
import io.github.burakgon.stop.daemon.DaemonProtocol
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.model.RuleMode
import kotlinx.coroutines.launch

/** Woken by the daemon when apps are installed or removed, even while Stop isn't running. */
class DaemonEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DaemonProtocol.ACTION_PACKAGES_CHANGED) return
        val packages = intent.getStringArrayExtra(DaemonProtocol.EXTRA_PACKAGES)?.toList() ?: return
        val graph = context.graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.packageEvents.onPackagesChanged(packages)
                graph.sync.awaitInSync(SYNC_TIMEOUT_MS)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Handles the "Stop in background" and "Let it run" buttons of the new-app notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { RuleMode.valueOf(it) }.getOrNull() } ?: return
        val graph = context.graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.decide(listOf(packageName), mode)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_MODE = "mode"

        fun intent(context: Context, packageName: String, mode: RuleMode): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_MODE, mode.name)
    }
}

/**
 * After a reboot, checks back once Shizuku had time to start. After an update of Stop itself,
 * starting the process is enough: the app reconnects and Shizuku swaps in the new daemon.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ShizukuCheck.schedule(context, ShizukuCheck.AFTER_BOOT_MS)
        }
        context.graph.daemon.connect()
    }
}

private const val SYNC_TIMEOUT_MS = 8_000L
