package io.github.burakgon.stop.daemon

/**
 * In-memory ring buffer of stops. The app copies events into its own database whenever it runs,
 * so the daemon only has to hold on to what happened while the app was asleep.
 */
internal class EventLog(private val capacity: Int) {
    private val events = ArrayDeque<DaemonEvent>()

    // Seeded from the wall clock so ids keep growing when the daemon restarts.
    private var nextId = System.currentTimeMillis() * 1_000

    var total = 0
        private set

    @Synchronized
    fun add(packageName: String, reason: String, sinceLeftMs: Long, procState: Int): DaemonEvent {
        val event = DaemonEvent(nextId++, System.currentTimeMillis(), packageName, reason, sinceLeftMs, procState)
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        total++
        return event
    }

    @Synchronized
    fun after(id: Long): List<DaemonEvent> = events.filter { it.id > id }
}
