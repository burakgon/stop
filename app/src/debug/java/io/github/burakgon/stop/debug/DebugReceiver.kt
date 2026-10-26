package io.github.burakgon.stop.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.model.RuleMode
import kotlinx.coroutines.launch

/**
 * Lets scripts drive a debug build over adb, for example:
 *
 *     adb shell am broadcast -n io.github.burakgon.stop/.debug.DebugReceiver -a onboard
 *     adb shell am broadcast -n io.github.burakgon.stop/.debug.DebugReceiver -a rule --es pkg com.example --es mode BLOCK
 *     adb shell am broadcast -n io.github.burakgon.stop/.debug.DebugReceiver -a settings --ei grace 5 --ez enabled true
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                when (intent.action) {
                    "onboard" -> graph.settings.update { it.copy(onboarded = true) }
                    "rule" -> {
                        val pkg = intent.getStringExtra("pkg") ?: return@launch
                        graph.rules.decide(listOf(pkg), RuleMode.valueOf(intent.getStringExtra("mode") ?: "BLOCK"))
                    }
                    "settings" -> graph.settings.update { s ->
                        s.copy(
                            graceSeconds = intent.getIntExtra("grace", s.graceSeconds),
                            protectionEnabled = intent.getBooleanExtra("enabled", s.protectionEnabled),
                            allowOngoing = intent.getBooleanExtra("ongoing", s.allowOngoing),
                            extraRestrictions = intent.getBooleanExtra("extra", s.extraRestrictions),
                            askOnInstall = intent.getBooleanExtra("ask", s.askOnInstall),
                        )
                    }
                    "sync" -> graph.syncEvents()
                    "permission" -> graph.shizuku.requestPermission()
                    "info" -> {
                        graph.daemon.refreshInfo()
                        Log.i(TAG, "running blocked: " + graph.daemon.call { it.runningBlocked })
                    }
                }
                val inSync = graph.sync.awaitInSync(8_000)
                Log.i(TAG, "${intent.action}: inSync=$inSync daemon=${graph.daemon.state.value} settings=${graph.settings.current()}")
            } catch (e: Exception) {
                Log.e(TAG, "${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "StopDebug"
    }
}
