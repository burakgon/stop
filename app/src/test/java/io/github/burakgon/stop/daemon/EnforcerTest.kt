package io.github.burakgon.stop.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnforcerTest {
    private val states = ProcStateTable.DEFAULT
    private val top = states.top
    private val receiver = 11
    private val cached = 16
    private val gone = states.nonexistent

    private class FakeHost : Enforcer.Host {
        var clock = 0L
        val packages = mutableMapOf<Int, Array<String>>()
        val procStates = mutableMapOf<Int, Int>()
        val stoppedState = mutableSetOf<String>()
        var topTask: Set<String> = emptySet()
        val essential = mutableSetOf<String>()
        /** When each package was last swiped away or force-stopped by the user. */
        val closedByUser = mutableMapOf<String, Long>()
        val forceStops = mutableListOf<String>()
        val events = mutableListOf<Pair<String, String>>()
        var nextTickIn: Long? = null

        override fun now() = clock
        override fun scheduleTick(delayMs: Long) {
            nextTickIn = minOf(nextTickIn ?: Long.MAX_VALUE, delayMs)
        }
        override fun packagesForUid(uid: Int) = packages[uid]
        override fun currentProcState(uid: Int) = procStates[uid]
        override fun isTopTaskOwnedBy(packages: List<String>) = packages.any { it in topTask }
        override fun isEssential(packageName: String) = packageName in essential
        override fun isStopped(packageName: String) = packageName in stoppedState
        override fun closedByUserSince(packages: List<String>, sinceMs: Long) =
            packages.any { (closedByUser[it] ?: Long.MIN_VALUE) >= sinceMs }
        override fun forceStop(packageName: String): Boolean {
            forceStops += packageName
            stoppedState += packageName
            return true
        }
        override fun onStopped(packageName: String, reason: String, sinceLeftMs: Long, procState: Int) {
            events += packageName to reason
        }
    }

    private lateinit var host: FakeHost
    private lateinit var enforcer: Enforcer

    private val appUid = 10_100
    private val app = "com.example.charger"

    @Before
    fun setUp() {
        host = FakeHost()
        host.packages[appUid] = arrayOf(app)
        host.stoppedState += app
        enforcer = Enforcer(host, states)
        block(graceMs = 60_000)
    }

    private fun block(
        graceMs: Long = 60_000,
        allowOngoing: Boolean = false,
        perAppGraceMs: Long? = null,
        running: Map<Int, Int> = emptyMap(),
    ) {
        enforcer.setPolicy(
            Enforcer.Policy(
                enabled = true,
                graceMs = graceMs,
                allowOngoing = allowOngoing,
                blocked = mapOf(app to DaemonConfig.Rule(appUid, blocked = true, graceMs = perAppGraceMs)),
            ),
            running,
        )
    }

    /** The app reports [state], and the system agrees when asked again later. */
    private fun report(state: Int) {
        host.procStates[appUid] = state
        enforcer.onProcState(appUid, state)
    }

    private fun advanceTo(time: Long) {
        host.clock = time
        enforcer.tick()
    }

    @Test
    fun `app left in the background is stopped once the grace period is over`() {
        report(top)
        host.clock = 1_000
        report(cached)

        advanceTo(60_999)
        assertTrue(host.events.isEmpty())

        advanceTo(61_000)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `app that starts in the background on its own is stopped after a short debounce`() {
        report(receiver)

        advanceTo(Enforcer.WAKE_DELAY_MS - 1)
        assertTrue(host.events.isEmpty())

        advanceTo(Enforcer.WAKE_DELAY_MS)
        assertEquals(listOf(app to StopReasons.WOKE), host.events)
    }

    @Test
    fun `app opened again before the deadline is left alone`() {
        report(top)
        report(cached)
        host.procStates[appUid] = top

        advanceTo(120_000)
        assertTrue(host.forceStops.isEmpty())
    }

    @Test
    fun `app hosting the task the user is in is not stopped`() {
        report(top)
        report(cached)
        host.topTask = setOf(app)

        advanceTo(61_000)
        assertTrue(host.events.isEmpty())

        host.topTask = emptySet()
        advanceTo(61_000 + Enforcer.TOP_TASK_RECHECK_MS)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `ongoing foreground service is stopped unless ongoing tasks are allowed`() {
        report(top)
        report(states.foregroundService)
        advanceTo(61_000)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)

        host.events.clear()
        host.clock = 100_000
        block(allowOngoing = true)
        report(top)
        report(states.foregroundService)
        advanceTo(1_000_000)
        assertTrue(host.events.isEmpty())
    }

    @Test
    fun `disallowing ongoing tasks re-evaluates an app that is already running`() {
        block(allowOngoing = true)
        report(top)
        report(states.foregroundService)

        host.clock = 10_000
        block(allowOngoing = false, running = mapOf(appUid to states.foregroundService))
        advanceTo(10_000 + 60_000)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `apps used by something on screen or left on top while locked count as in use`() {
        for (inUse in listOf(states.boundTop, states.boundForegroundService, states.importantForeground, states.topSleeping, states.backup)) {
            report(inUse)
            advanceTo(host.clock + 10 * 60_000)
        }
        assertTrue(host.events.isEmpty())
    }

    @Test
    fun `a restart within the grace period waits for the grace period`() {
        report(top)
        host.clock = 1_000
        report(gone)
        host.clock = 5_000
        report(receiver)

        advanceTo(60_999)
        assertTrue(host.events.isEmpty())
        advanceTo(61_000)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `an app swiped away from recents gets no grace period when something restarts it`() {
        report(top)
        host.clock = 1_000
        report(cached)
        host.clock = 2_000
        host.closedByUser[app] = 2_000
        report(gone)
        host.clock = 5_000
        report(receiver)

        advanceTo(5_000 + Enforcer.WAKE_DELAY_MS)
        assertEquals(listOf(app to StopReasons.WOKE), host.events)
    }

    @Test
    fun `per-app grace period overrides the default`() {
        block(graceMs = 60_000, perAppGraceMs = 5_000)
        report(top)
        report(cached)
        advanceTo(5_000)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `no grace period still leaves a moment to switch back`() {
        block(graceMs = 0)
        report(top)
        report(cached)
        advanceTo(Enforcer.MIN_DELAY_MS - 1)
        assertTrue(host.events.isEmpty())
        advanceTo(Enforcer.MIN_DELAY_MS)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `blocking an app that is not running puts it in the stopped state without an event`() {
        host.stoppedState.clear()
        enforcer = Enforcer(host, states)
        block()
        assertEquals(listOf(app), host.forceStops)
        assertTrue(host.events.isEmpty())
    }

    @Test
    fun `blocking an app that runs in the background stops it right away`() {
        enforcer = Enforcer(host, states)
        host.procStates[appUid] = cached
        block(running = mapOf(appUid to cached))
        advanceTo(Enforcer.WAKE_DELAY_MS)
        assertEquals(listOf(app to StopReasons.RUNNING), host.events)
    }

    @Test
    fun `allowed apps are never touched`() {
        enforcer.setPolicy(Enforcer.Policy(enabled = true, blocked = emptyMap()), emptyMap())
        report(receiver)
        advanceTo(10 * 60_000)
        assertTrue(host.forceStops.isEmpty())
    }

    @Test
    fun `nothing happens while protection is paused`() {
        enforcer.setPolicy(Enforcer.Policy(enabled = false), emptyMap())
        report(receiver)
        advanceTo(10 * 60_000)
        assertTrue(host.forceStops.isEmpty())
    }

    @Test
    fun `an app that keeps getting restarted is stopped with growing delays`() {
        repeat(3) {
            report(receiver)
            advanceTo(host.clock + Enforcer.WAKE_DELAY_MS)
            report(gone)
        }
        assertEquals(3, host.events.size)

        report(receiver)
        advanceTo(host.clock + Enforcer.WAKE_DELAY_MS)
        assertEquals(3, host.events.size)
        advanceTo(host.clock + 5_000)
        assertEquals(4, host.events.size)
    }

    @Test
    fun `stop all ignores grace periods but spares what is on screen`() {
        val visibleUid = 10_200
        val visibleApp = "com.example.visible"
        host.packages[visibleUid] = arrayOf(visibleApp)
        enforcer.setPolicy(
            Enforcer.Policy(
                enabled = true,
                blocked = mapOf(
                    app to DaemonConfig.Rule(appUid, blocked = true),
                    visibleApp to DaemonConfig.Rule(visibleUid, blocked = true),
                ),
            ),
            emptyMap(),
        )
        report(top)
        report(cached)
        host.procStates[visibleUid] = top
        enforcer.onProcState(visibleUid, top)

        assertEquals(1, enforcer.stopAllInBackground())
        assertEquals(listOf(app to StopReasons.MANUAL), host.events)
    }

    @Test
    fun `snapshot reports running apps and when they will be stopped`() {
        report(top)
        host.clock = 1_000
        report(cached)
        val running = enforcer.snapshot().single()
        assertEquals(app, running.packageName)
        assertEquals(60_000, running.stopInMs)
        assertEquals(StopReasons.LEFT, running.reason)
    }

    @Test
    fun `apps the phone relies on are never stopped`() {
        host.essential += app
        report(receiver)
        advanceTo(10 * 60_000)
        assertTrue(host.forceStops.isEmpty())
    }

    @Test
    fun `an app held by something else gets the short debounce once it is let go`() {
        report(states.importantForeground)
        host.clock = 30_000
        report(cached)

        advanceTo(30_000 + Enforcer.WAKE_DELAY_MS)
        assertEquals(listOf(app to StopReasons.WOKE), host.events)
    }

    @Test
    fun `an app kept running by an ongoing task is stopped right after it ends`() {
        block(allowOngoing = true)
        report(top)
        report(states.foregroundService)
        host.clock = 10 * 60_000
        report(cached)

        advanceTo(host.clock + Enforcer.MIN_DELAY_MS)
        assertEquals(listOf(app to StopReasons.LEFT), host.events)
    }

    @Test
    fun `nothing is stopped when the system can't say what is running`() {
        host.stoppedState.clear()
        enforcer = Enforcer(host, states)
        enforcer.setPolicy(
            Enforcer.Policy(enabled = true, blocked = mapOf(app to DaemonConfig.Rule(appUid, blocked = true))),
            running = null,
        )
        assertTrue(host.forceStops.isEmpty())
    }

    @Test
    fun `an app restarted right after a manual stop gets no grace period`() {
        report(top)
        host.clock = 1_000
        report(cached)
        host.clock = 5_000
        assertEquals(1, enforcer.stopAllInBackground())

        // Something (a job that was just being dispatched, say) brings it straight back.
        report(receiver)
        advanceTo(5_000 + Enforcer.WAKE_DELAY_MS)
        assertEquals(listOf(app to StopReasons.MANUAL, app to StopReasons.WOKE), host.events)
    }
}
