package io.github.burakgon.stop.daemon

import org.json.JSONArray
import org.json.JSONObject

/**
 * Contract between the app and the daemon. Both ship in the same APK and Shizuku restarts the
 * daemon whenever the version code changes, so the two sides always agree on these shapes.
 */
object DaemonProtocol {
    const val ACTION_PACKAGES_CHANGED = "io.github.burakgon.stop.action.PACKAGES_CHANGED"
    const val EXTRA_PACKAGES = "packages"
    const val RECEIVER_CLASS = "io.github.burakgon.stop.receiver.DaemonEventReceiver"
}

/** Why the daemon stopped an app. */
object StopReasons {
    /** Started in the background without the user opening it. */
    const val WOKE = "woke"

    /** Kept running after the user left it and the grace period ran out. */
    const val LEFT = "left"

    /** Was already running in the background when it got blocked or protection started. */
    const val RUNNING = "running"

    /** Stopped on request, from the app, the tile or the shortcut. */
    const val MANUAL = "manual"
}

/** Everything the daemon enforces, pushed by the app as one document. */
data class DaemonConfig(
    val enabled: Boolean,
    val graceMs: Long,
    val allowOngoing: Boolean,
    val restrictBackground: Boolean,
    val restrictStandby: Boolean,
    val userId: Int,
    /** Every package the app has a rule for. Packages missing here are treated as new installs. */
    val rules: Map<String, Rule>,
    val protectedPackages: Set<String>,
) {
    data class Rule(val uid: Int, val blocked: Boolean, val graceMs: Long? = null)

    fun toJson(): String = JSONObject().apply {
        put("enabled", enabled)
        put("graceMs", graceMs)
        put("allowOngoing", allowOngoing)
        put("restrictBackground", restrictBackground)
        put("restrictStandby", restrictStandby)
        put("userId", userId)
        put("rules", JSONObject().apply {
            for ((pkg, rule) in rules) {
                put(pkg, JSONObject().apply {
                    put("uid", rule.uid)
                    put("blocked", rule.blocked)
                    rule.graceMs?.let { put("graceMs", it) }
                })
            }
        })
        put("protected", JSONArray(protectedPackages.toList()))
    }.toString()

    companion object {
        fun fromJson(json: String): DaemonConfig {
            val o = JSONObject(json)
            val rulesJson = o.optJSONObject("rules") ?: JSONObject()
            val rules = HashMap<String, Rule>(rulesJson.length())
            for (pkg in rulesJson.keys()) {
                val r = rulesJson.getJSONObject(pkg)
                rules[pkg] = Rule(
                    uid = r.getInt("uid"),
                    blocked = r.getBoolean("blocked"),
                    graceMs = if (r.has("graceMs")) r.getLong("graceMs") else null,
                )
            }
            val protectedJson = o.optJSONArray("protected") ?: JSONArray()
            return DaemonConfig(
                enabled = o.optBoolean("enabled", false),
                graceMs = o.optLong("graceMs", 60_000),
                allowOngoing = o.optBoolean("allowOngoing", false),
                restrictBackground = o.optBoolean("restrictBackground", false),
                restrictStandby = o.optBoolean("restrictStandby", false),
                userId = o.optInt("userId", 0),
                rules = rules,
                protectedPackages = (0 until protectedJson.length()).mapTo(HashSet()) { protectedJson.getString(it) },
            )
        }
    }
}

/** A stop the daemon performed. [id] grows monotonically, also across daemon restarts. */
data class DaemonEvent(
    val id: Long,
    val time: Long,
    val packageName: String,
    val reason: String,
    /** Milliseconds between the user leaving the app and the stop, or -1 if unknown. */
    val sinceLeftMs: Long,
    val procState: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("pkg", packageName)
        put("reason", reason)
        put("sinceLeftMs", sinceLeftMs)
        put("procState", procState)
    }

    companion object {
        fun listFromJson(json: String): List<DaemonEvent> {
            val a = JSONArray(json)
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                DaemonEvent(
                    id = o.getLong("id"),
                    time = o.getLong("time"),
                    packageName = o.getString("pkg"),
                    reason = o.getString("reason"),
                    sinceLeftMs = o.optLong("sinceLeftMs", -1),
                    procState = o.optInt("procState", -1),
                )
            }
        }

        fun listToJson(events: List<DaemonEvent>): String =
            JSONArray().apply { events.forEach { put(it.toJson()) } }.toString()
    }
}

/** A blocked app that currently has processes. */
data class RunningApp(
    val packageName: String,
    val procState: Int,
    val inUse: Boolean,
    /** Milliseconds until the daemon stops it, or -1 when no stop is scheduled. */
    val stopInMs: Long,
    val reason: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", packageName)
        put("procState", procState)
        put("inUse", inUse)
        put("stopInMs", stopInMs)
        reason?.let { put("reason", it) }
    }

    companion object {
        fun listFromJson(json: String): List<RunningApp> {
            val a = JSONArray(json)
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                RunningApp(
                    packageName = o.getString("pkg"),
                    procState = o.optInt("procState", -1),
                    inUse = o.optBoolean("inUse"),
                    stopInMs = o.optLong("stopInMs", -1),
                    reason = if (o.has("reason")) o.getString("reason") else null,
                )
            }
        }

        fun listToJson(apps: List<RunningApp>): String =
            JSONArray().apply { apps.forEach { put(it.toJson()) } }.toString()
    }
}

/** Diagnostics about the daemon process. */
data class DaemonInfo(
    val uid: Int,
    val pid: Int,
    val versionCode: Int,
    val sdk: Int,
    val startedAt: Long,
    /** "observer" when process changes are pushed by the system, "polling" as a fallback. */
    val watchMode: String,
    val stops: Int,
) {
    val isRoot: Boolean get() = uid == 0

    fun toJson(): String = JSONObject().apply {
        put("uid", uid)
        put("pid", pid)
        put("versionCode", versionCode)
        put("sdk", sdk)
        put("startedAt", startedAt)
        put("watchMode", watchMode)
        put("stops", stops)
    }.toString()

    companion object {
        fun fromJson(json: String): DaemonInfo {
            val o = JSONObject(json)
            return DaemonInfo(
                uid = o.getInt("uid"),
                pid = o.getInt("pid"),
                versionCode = o.getInt("versionCode"),
                sdk = o.getInt("sdk"),
                startedAt = o.getLong("startedAt"),
                watchMode = o.getString("watchMode"),
                stops = o.optInt("stops"),
            )
        }
    }
}
