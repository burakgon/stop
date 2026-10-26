package io.github.burakgon.stop.daemon

import io.github.burakgon.stop.daemon.ProcStateTable.Usage

/**
 * Decides when a blocked app gets force-stopped. The daemon feeds it process-state changes and
 * carries out what it asks for through [Host]; nothing here touches Android, so it's unit-tested.
 *
 * A blocked app is left alone while the user uses it, while something they rely on holds it (see
 * [ProcStateTable.classify]), and while it hosts the task the user is in, for example a camera or
 * payment page it opened. After the user leaves it, it may keep running for the grace period. If
 * it starts in the background on its own, or comes back after the user swiped it away from
 * Recents, it's stopped after a short debounce. Force-stopping also puts the app in Android's
 * stopped state, so its alarms, jobs and push messages are dropped until the user opens it again.
 */
internal class Enforcer(private val host: Host, private val states: ProcStateTable) {

    interface Host {
        /** A monotonic clock that keeps counting during deep sleep (elapsedRealtime). */
        fun now(): Long

        /** Requests a call to [tick] after [delayMs] at the latest. */
        fun scheduleTick(delayMs: Long)

        fun packagesForUid(uid: Int): Array<String>?

        /** The uid's current state straight from the system, or null when it can't be read. */
        fun currentProcState(uid: Int): Int?

        /** Whether the task the user is in was started by one of [packages]. */
        fun isTopTaskOwnedBy(packages: List<String>): Boolean

        /** Apps the phone needs right now (keyboard, launcher, …), never to be stopped. */
        fun isEssential(packageName: String): Boolean

        /** Whether Android has the package in its stopped state, or null if unknown. */
        fun isStopped(packageName: String): Boolean?

        /**
         * Whether one of [packages] had its process ended on request (swiped away from Recents,
         * force-stopped) at or after [sinceMs], on the [now] clock.
         */
        fun closedByUserSince(packages: List<String>, sinceMs: Long): Boolean

        fun forceStop(packageName: String): Boolean

        fun onStopped(packageName: String, reason: String, sinceLeftMs: Long, procState: Int)
    }

    data class Policy(
        val enabled: Boolean = false,
        val graceMs: Long = 60_000,
        val allowOngoing: Boolean = false,
        /** Blocked packages and their rules. Allowed and protected packages are absent. */
        val blocked: Map<String, DaemonConfig.Rule> = emptyMap(),
    )

    private class Track {
        var procState = -1
        /** On screen (or on top while locked) at the last update. */
        var visible = false
        /** Visible or held, so not to be stopped. */
        var inUse = false
        /** When the user last left it, or [NEVER]. */
        var leftAt = NEVER
        /** Whether the process kept running without interruption since the user left it. */
        var aliveSinceLeft = false
        var deadline = NONE
        var reason = StopReasons.WOKE
        val recentStops = ArrayDeque<Long>()

        /**
         * After the user stopped the app by hand, a grace period from earlier use no longer
         * applies: if anything restarts it, that counts as waking up on its own.
         */
        fun forgetUse() {
            visible = false
            inUse = false
            leftAt = NEVER
            aliveSinceLeft = false
            deadline = NONE
        }
    }

    var policy = Policy()
        private set

    private val tracks = HashMap<Int, Track>()

    /**
     * Switches to [newPolicy]. [running] maps each running uid that has a blocked package to its
     * current process state; null when the system couldn't tell, in which case nothing that
     * depends on it (stopping apps that aren't running, re-evaluating) is done.
     */
    fun setPolicy(newPolicy: Policy, running: Map<Int, Int>?) {
        val previous = policy
        policy = newPolicy
        if (!newPolicy.enabled) {
            tracks.clear()
            return
        }
        tracks.keys.removeAll { blockedPackages(it).isEmpty() }
        val now = host.now()
        for ((uid, track) in tracks) {
            if (track.deadline != NONE && track.reason == StopReasons.LEFT) {
                track.deadline = maxOf(track.leftAt + graceFor(uid), now + MIN_DELAY_MS)
            }
        }
        if (running == null) {
            tick()
            return
        }
        // Re-evaluate everything that runs: a changed setting (say, ongoing tasks no longer
        // allowed) can turn an app that was fine a moment ago into one that has to stop.
        for (uid in tracks.keys.toList()) {
            if (uid !in running) observe(uid, states.nonexistent, StopReasons.WOKE)
        }
        for ((uid, state) in running) {
            observe(uid, state, if (uid in tracks) StopReasons.WOKE else StopReasons.RUNNING)
        }
        // Blocked apps that aren't running go straight into the stopped state, so whatever they
        // scheduled can't wake them up later.
        for ((pkg, rule) in newPolicy.blocked) {
            val newlyBlocked = !previous.enabled || pkg !in previous.blocked
            if (newlyBlocked && rule.uid !in running) stopQuietly(pkg)
        }
        tick()
    }

    fun onProcState(uid: Int, procState: Int) {
        if (!policy.enabled) return
        observe(uid, procState, StopReasons.WOKE)
        tick()
    }

    /** Brings tracking in line with a full scan of running uids that have blocked packages. */
    fun reconcile(running: Map<Int, Int>?) {
        if (!policy.enabled || running == null) return
        for (uid in tracks.keys.toList()) {
            if (uid !in running) observe(uid, states.nonexistent, StopReasons.WOKE)
        }
        for ((uid, state) in running) observe(uid, state, StopReasons.WOKE)
        tick()
    }

    /** Re-checks a blocked package after it was updated, keeping it asleep if it isn't in use. */
    fun recheck(packageName: String) {
        if (!policy.enabled) return
        val uid = policy.blocked[packageName]?.uid ?: return
        val state = host.currentProcState(uid) ?: return
        if (states.classify(state, policy.allowOngoing) == Usage.GONE) {
            stopQuietly(packageName)
        } else {
            observe(uid, state, StopReasons.WOKE)
            tick()
        }
    }

    fun tick() {
        if (!policy.enabled) return
        val now = host.now()
        var next = NONE
        for ((uid, track) in tracks.entries.toList()) {
            if (track.deadline == NONE) continue
            if (track.deadline > now) {
                next = minOf(next, track.deadline)
                continue
            }
            val packages = blockedPackages(uid).filterNot(host::isEssential)
            if (packages.isEmpty()) {
                track.deadline = NONE
                continue
            }
            // Missed updates happen (and the observer can lag); trust the system's current view.
            val state = host.currentProcState(uid) ?: track.procState
            if (state != track.procState) {
                observe(uid, state, track.reason)
                if (track.deadline == NONE) continue
                if (track.deadline > now) {
                    next = minOf(next, track.deadline)
                    continue
                }
            }
            if (states.classify(state, policy.allowOngoing) != Usage.BACKGROUND) {
                track.deadline = NONE
                continue
            }
            if (host.isTopTaskOwnedBy(packages)) {
                track.deadline = now + TOP_TASK_RECHECK_MS
                next = minOf(next, track.deadline)
                continue
            }
            val sinceLeft = if (track.leftAt == NEVER) -1L else now - track.leftAt
            for (pkg in packages) {
                if (host.forceStop(pkg)) host.onStopped(pkg, track.reason, sinceLeft, state)
            }
            track.deadline = NONE
            track.recentStops.addLast(now)
        }
        if (next != NONE) host.scheduleTick(next - now)
    }

    /** Stops [packages] right away. Returns how many of them were running. */
    fun stopNow(packages: Collection<String>): Int {
        var stopped = 0
        for (pkg in packages) {
            if (host.isEssential(pkg)) continue
            val uid = policy.blocked[pkg]?.uid
            val state = uid?.let { host.currentProcState(it) } ?: -1
            val running = states.classify(state, allowOngoing = false) != Usage.GONE
            if (host.forceStop(pkg) && running) {
                stopped++
                host.onStopped(pkg, StopReasons.MANUAL, -1, state)
            }
            uid?.let { tracks[it]?.forgetUse() }
        }
        return stopped
    }

    /** Stops every blocked app that isn't on screen, ignoring grace periods and ongoing tasks. */
    fun stopAllInBackground(): Int {
        if (!policy.enabled) return 0
        var stopped = 0
        for ((uid, track) in tracks.entries.toList()) {
            val state = host.currentProcState(uid) ?: track.procState
            if (states.classify(state, allowOngoing = false) != Usage.BACKGROUND) continue
            for (pkg in blockedPackages(uid).filterNot(host::isEssential)) {
                if (host.forceStop(pkg)) {
                    stopped++
                    host.onStopped(pkg, StopReasons.MANUAL, -1, state)
                }
            }
            track.forgetUse()
        }
        return stopped
    }

    /** Blocked apps that currently have processes. */
    fun snapshot(): List<RunningApp> {
        val now = host.now()
        return tracks.entries
            .filter { (_, track) -> states.classify(track.procState, policy.allowOngoing) != Usage.GONE }
            .flatMap { (uid, track) ->
                blockedPackages(uid).map { pkg ->
                    RunningApp(
                        packageName = pkg,
                        procState = track.procState,
                        inUse = track.inUse,
                        stopInMs = if (track.deadline == NONE) -1 else maxOf(0, track.deadline - now),
                        reason = if (track.deadline == NONE) null else track.reason,
                    )
                }
            }
    }

    private fun observe(uid: Int, procState: Int, backgroundReason: String) {
        if (blockedPackages(uid).isEmpty()) {
            tracks.remove(uid)
            return
        }
        val track = tracks.getOrPut(uid) { Track() }
        val now = host.now()
        track.procState = procState
        val usage = states.classify(procState, policy.allowOngoing)
        // Only leaving the screen starts a grace period. Something else letting go of the app
        // (a binding ending, a backup finishing) is treated like the app waking up on its own.
        val justLeft = track.visible && usage != Usage.USER
        if (justLeft) {
            track.leftAt = now
            track.aliveSinceLeft = true
        }
        track.visible = usage == Usage.USER
        when (usage) {
            Usage.USER, Usage.HELD -> {
                track.inUse = true
                track.deadline = NONE
            }
            Usage.GONE -> {
                track.inUse = false
                track.aliveSinceLeft = false
                track.deadline = NONE
            }
            Usage.BACKGROUND -> {
                track.inUse = false
                if (track.deadline != NONE) return
                val grace = graceFor(uid)
                var withinGrace = track.leftAt != NEVER && now - track.leftAt < grace
                // Swiping the app away from Recents means the user is done with it, so a restart
                // after that counts as waking up on its own.
                if (withinGrace && !track.aliveSinceLeft && host.closedByUserSince(blockedPackages(uid), track.leftAt)) {
                    track.leftAt = NEVER
                    withinGrace = false
                }
                if (withinGrace) {
                    track.reason = StopReasons.LEFT
                    track.deadline = maxOf(track.leftAt + grace, now + MIN_DELAY_MS)
                } else {
                    track.reason = if (track.aliveSinceLeft) StopReasons.LEFT else backgroundReason
                    track.deadline = now + if (track.aliveSinceLeft) MIN_DELAY_MS else wakeDelay(track, now)
                }
                host.scheduleTick(track.deadline - now)
            }
        }
    }

    private fun stopQuietly(packageName: String) {
        if (host.isEssential(packageName)) return
        if (host.isStopped(packageName) != true) host.forceStop(packageName)
    }

    private fun blockedPackages(uid: Int): List<String> {
        val packages = host.packagesForUid(uid) ?: return emptyList()
        return packages.filter { it in policy.blocked }
    }

    private fun graceFor(uid: Int): Long =
        blockedPackages(uid).mapNotNull { policy.blocked[it]?.graceMs }.minOrNull() ?: policy.graceMs

    /**
     * Something that keeps restarting an app (another app binding to it, say) would otherwise
     * make us kill it in a tight loop, which costs more battery than it saves. Back off instead.
     */
    private fun wakeDelay(track: Track, now: Long): Long {
        while (track.recentStops.isNotEmpty() && now - track.recentStops.first() > LOOP_WINDOW_MS) {
            track.recentStops.removeFirst()
        }
        return when (track.recentStops.size) {
            in 0..2 -> WAKE_DELAY_MS
            in 3..5 -> 5_000
            in 6..9 -> 15_000
            else -> 60_000
        }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        private const val NONE = Long.MAX_VALUE

        /** How long an app that started by itself may run before it's stopped. */
        const val WAKE_DELAY_MS = 1_000L

        /** Shortest time between leaving an app and stopping it, even with no grace period. */
        const val MIN_DELAY_MS = 1_500L

        const val TOP_TASK_RECHECK_MS = 10_000L
        const val LOOP_WINDOW_MS = 10 * 60_000L
    }
}
