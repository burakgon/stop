package io.github.burakgon.stop.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.burakgon.stop.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings protection back after a reboot or after Shizuku restarted, and tells the user when it
 * stays paused. Blocked apps remain asleep in the meantime: Android keeps their stopped state
 * across reboots; only apps opened since then aren't stopped again until Shizuku is back.
 *
 * Shizuku hands its binder to an app when the app's process starts or comes to the foreground,
 * but on some Android versions not to an app that was already running when Shizuku started.
 * So when the binder still hasn't arrived, the check ends Stop's background process: the next
 * attempt starts a fresh one, which Shizuku does notice.
 *
 * An alarm drives this rather than a job: newer Android versions defer jobs without
 * constraints for a long time, while an inexact alarm still fires within a few minutes.
 */
class ShizukuCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val attempt = intent.getIntExtra(ShizukuCheck.EXTRA_ATTEMPT, 0)
        val pending = goAsync()
        graph.scope.launch {
            var restart = false
            try {
                val settings = graph.settings.current()
                if (!settings.onboarded) return@launch
                if (graph.awaitShizuku(BINDER_WAIT_MS)) {
                    graph.daemon.connect()
                    return@launch
                }
                if (settings.protectionEnabled) graph.notifications.showPaused()
                ShizukuCheck.schedule(context, ShizukuCheck.retryDelay(attempt), attempt + 1)
                restart = withContext(Dispatchers.Main) {
                    !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
            } finally {
                pending.finish()
            }
            if (restart) Process.killProcess(Process.myPid())
        }
    }

    private companion object {
        const val BINDER_WAIT_MS = 8_000L
    }
}

object ShizukuCheck {
    internal const val EXTRA_ATTEMPT = "attempt"
    const val AFTER_BOOT_MS = 2 * 60_000L
    const val AFTER_LOSS_MS = 60_000L

    internal fun retryDelay(attempt: Int): Long = when {
        attempt < 2 -> 60_000L
        attempt < 4 -> 5 * 60_000L
        attempt < 10 -> 15 * 60_000L
        else -> 60 * 60_000L
    }

    fun schedule(context: Context, delayMs: Long, attempt: Int = 0) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent(context, attempt),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, 0))
    }

    private fun pendingIntent(context: Context, attempt: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ShizukuCheckReceiver::class.java).putExtra(EXTRA_ATTEMPT, attempt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
