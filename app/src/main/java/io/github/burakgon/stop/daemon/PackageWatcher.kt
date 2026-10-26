package io.github.burakgon.stop.daemon

import android.os.Handler

/**
 * Notices installs, updates and removals by polling the package manager's change counter.
 * The daemon can't register broadcast receivers, and the call is cheap: it returns null until
 * something actually changes.
 */
internal class PackageWatcher(
    private val handler: Handler,
    private val system: SystemApis,
    private val userId: () -> Int,
    private val onChanged: (List<String>) -> Unit,
) {
    private var sequence = 0
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, if (system.isInteractive()) INTERVAL_MS else IDLE_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        // Start from the current point; everything that changed before is the app's business.
        sequence = system.changedPackages(0, userId())?.sequenceNumber ?: 0
        handler.postDelayed(poll, INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
    }

    private fun check() {
        val changes = system.changedPackages(sequence, userId()) ?: return
        sequence = changes.sequenceNumber
        val names = changes.packageNames
        if (names.isNotEmpty()) onChanged(names)
    }

    private companion object {
        const val INTERVAL_MS = 3_000L
        const val IDLE_INTERVAL_MS = 30_000L
    }
}
