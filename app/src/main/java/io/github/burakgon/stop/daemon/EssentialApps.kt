package io.github.burakgon.stop.daemon

import android.os.SystemClock
import android.util.Log

/**
 * Apps the phone relies on right now and that the daemon therefore never stops, whatever the
 * app's rules say: the home screen, keyboards, SMS and phone apps, the assistant, and apps the
 * user enabled as accessibility service, notification listener or autofill provider.
 *
 * The app protects these in its UI too, but only learns about changes when it runs. Checking here
 * as well means switching keyboards or launchers can never get the new one killed.
 */
internal class EssentialApps(
    private val run: (List<String>) -> String?,
    private val userId: () -> Int,
) {
    private var cached: Set<String> = emptySet()
    private var refreshedAt = Long.MIN_VALUE / 2

    fun get(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - refreshedAt > MAX_AGE_MS) {
            val loaded = load()
            if (loaded != cached) Log.i("StopDaemon", "apps the phone relies on: ${loaded.sorted()}")
            cached = loaded
            refreshedAt = now
        }
        return cached
    }

    private fun load(): Set<String> {
        val user = userId().toString()
        val result = HashSet<String>()
        for (role in ROLES) {
            run(listOf("cmd", "role", "get-role-holders", "--user", user, role))
                ?.split(SEPARATORS)
                ?.mapNotNullTo(result) { it.trim().takeIf(::isPackageName) }
        }
        for (key in COMPONENT_SETTINGS) {
            val value = run(listOf("settings", "get", "--user", user, "secure", key))?.trim() ?: continue
            if (value == "null") continue
            value.split(':').mapNotNullTo(result) { entry ->
                entry.substringBefore(';').substringBefore('/').trim().takeIf(::isPackageName)
            }
        }
        return result
    }

    private fun isPackageName(value: String) = value.isNotEmpty() && value.contains('.') && !value.contains(' ')

    private companion object {
        const val MAX_AGE_MS = 60_000L
        val SEPARATORS = Regex("[\\s;,]+")
        val ROLES = listOf(
            "android.app.role.HOME",
            "android.app.role.SMS",
            "android.app.role.DIALER",
            "android.app.role.ASSISTANT",
        )
        val COMPONENT_SETTINGS = listOf(
            "default_input_method",
            "enabled_input_methods",
            "enabled_accessibility_services",
            "enabled_notification_listeners",
            "autofill_service",
        )
    }
}
