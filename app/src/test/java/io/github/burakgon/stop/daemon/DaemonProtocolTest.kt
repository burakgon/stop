package io.github.burakgon.stop.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonProtocolTest {

    @Test
    fun `config survives a round trip`() {
        val config = DaemonConfig(
            enabled = true,
            graceMs = 30_000,
            allowOngoing = true,
            restrictBackground = false,
            restrictStandby = true,
            userId = 0,
            rules = mapOf(
                "com.example.blocked" to DaemonConfig.Rule(uid = 10_123, blocked = true, graceMs = 5_000),
                "com.example.allowed" to DaemonConfig.Rule(uid = 10_124, blocked = false),
            ),
            protectedPackages = setOf("com.android.systemui", "moe.shizuku.privileged.api"),
        )
        assertEquals(config, DaemonConfig.fromJson(config.toJson()))
    }

    @Test
    fun `events and running apps survive a round trip`() {
        val events = listOf(
            DaemonEvent(1, 1_700_000_000_000, "com.example.a", StopReasons.WOKE, -1, 11),
            DaemonEvent(2, 1_700_000_100_000, "com.example.b", StopReasons.LEFT, 60_000, 16),
        )
        assertEquals(events, DaemonEvent.listFromJson(DaemonEvent.listToJson(events)))

        val running = listOf(
            RunningApp("com.example.a", 16, inUse = false, stopInMs = 42_000, reason = StopReasons.LEFT),
            RunningApp("com.example.b", 2, inUse = true, stopInMs = -1, reason = null),
        )
        assertEquals(running, RunningApp.listFromJson(RunningApp.listToJson(running)))
    }
}
