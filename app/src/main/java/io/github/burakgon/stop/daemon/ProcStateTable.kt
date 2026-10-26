package io.github.burakgon.stop.daemon

import android.app.ActivityManager

/**
 * The framework's process-state constants. They are hidden and were renumbered across releases
 * (Android 10 inserted BOUND_TOP and, for one release, FOREGROUND_SERVICE_LOCATION), so the
 * daemon reads them from the running system.
 */
internal data class ProcStateTable(
    val top: Int,
    /** Missing before Android 10. */
    val boundTop: Int,
    /** Only exists on Android 10. */
    val foregroundServiceLocation: Int,
    val foregroundService: Int,
    val boundForegroundService: Int,
    val importantForeground: Int,
    val backup: Int,
    val topSleeping: Int,
    val nonexistent: Int,
) {
    enum class Usage {
        /** On screen, or on top while the screen is off: the user is using it. */
        USER,

        /**
         * Kept alive by something the user relies on: an app on screen or Android Auto bound to
         * it, an overlay, a backup, or (if allowed) its own ongoing foreground service.
         */
        HELD,

        /** Running, but nothing needs it. */
        BACKGROUND,

        /** No process left. */
        GONE,
    }

    fun classify(state: Int, allowOngoing: Boolean): Usage = when {
        state < 0 || state >= nonexistent -> Usage.GONE
        state <= top || state == topSleeping -> Usage.USER
        state == boundTop ||
            state == boundForegroundService ||
            state == importantForeground ||
            state == backup -> Usage.HELD
        state == foregroundService || state == foregroundServiceLocation ->
            if (allowOngoing) Usage.HELD else Usage.BACKGROUND
        else -> Usage.BACKGROUND
    }

    companion object {
        private const val ABSENT = Int.MIN_VALUE

        /** Values used from Android 11 through 17. */
        val DEFAULT = ProcStateTable(
            top = 2,
            boundTop = 3,
            foregroundServiceLocation = ABSENT,
            foregroundService = 4,
            boundForegroundService = 5,
            importantForeground = 6,
            backup = 9,
            topSleeping = 12,
            nonexistent = 20,
        )

        fun fromFramework(): ProcStateTable {
            fun read(name: String, fallback: Int): Int = try {
                ActivityManager::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
            } catch (_: ReflectiveOperationException) {
                fallback
            }
            return ProcStateTable(
                top = read("PROCESS_STATE_TOP", DEFAULT.top),
                boundTop = read("PROCESS_STATE_BOUND_TOP", ABSENT),
                foregroundServiceLocation = read("PROCESS_STATE_FOREGROUND_SERVICE_LOCATION", ABSENT),
                foregroundService = read("PROCESS_STATE_FOREGROUND_SERVICE", DEFAULT.foregroundService),
                boundForegroundService = read("PROCESS_STATE_BOUND_FOREGROUND_SERVICE", DEFAULT.boundForegroundService),
                importantForeground = read("PROCESS_STATE_IMPORTANT_FOREGROUND", DEFAULT.importantForeground),
                backup = read("PROCESS_STATE_BACKUP", DEFAULT.backup),
                topSleeping = read("PROCESS_STATE_TOP_SLEEPING", DEFAULT.topSleeping),
                nonexistent = read("PROCESS_STATE_NONEXISTENT", DEFAULT.nonexistent),
            )
        }
    }
}
