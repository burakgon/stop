package io.github.burakgon.stop.daemon

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.github.burakgon.stop.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The privileged side of Stop. Runs as shell (or root) in a process Shizuku starts, keeps running
 * after the app's own process dies, and enforces whatever configuration the app last pushed.
 * All state lives on one handler thread; binder calls hop onto it.
 */
internal class Daemon(private val selfPackage: String) {
    private val thread = HandlerThread("stop-daemon").apply { start() }
    private val handler = Handler(thread.looper)
    private val shell = Executors.newSingleThreadExecutor()
    private val system = SystemApis()
    private val states = ProcStateTable.fromFramework()
    private val log = EventLog(capacity = 2_000)
    private val restrictions = RestrictionStore(File("/data/local/tmp/$selfPackage/restrictions.json"))
    private val startedAt = System.currentTimeMillis()
    private val restrictedBucket =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) SystemApis.BUCKET_RESTRICTED else BUCKET_RARE

    @Volatile
    private var config: DaemonConfig? = null

    @Volatile
    private var listener: IDaemonListener? = null
    private var pushedEventId = 0L
    private var observer: UidObserver? = null
    private var tickAt = Long.MAX_VALUE
    private val uidPackages = HashMap<Int, Array<String>?>()
    private val trustedUids = HashSet<Int>()
    private val selfUid: Int by lazy { system.applicationInfo(selfPackage, userId)?.uid ?: -1 }
    private val essentials = EssentialApps(::output) { userId }

    private val userId: Int get() = config?.userId ?: 0

    private val host: Enforcer.Host = object : Enforcer.Host {
        override fun now(): Long = SystemClock.elapsedRealtime()

        override fun scheduleTick(delayMs: Long) {
            val now = SystemClock.uptimeMillis()
            val at = now + delayMs.coerceAtLeast(0)
            if (tickAt in now..at) return
            handler.removeCallbacks(tick)
            tickAt = at
            handler.postAtTime(tick, at)
        }

        override fun packagesForUid(uid: Int): Array<String>? {
            if (!uidPackages.containsKey(uid)) uidPackages[uid] = system.packagesForUid(uid)
            return uidPackages[uid]
        }

        override fun currentProcState(uid: Int): Int? = system.uidProcessState(uid)

        override fun isTopTaskOwnedBy(packages: List<String>): Boolean {
            val (base, top) = system.topTask() ?: return false
            return base in packages || top in packages
        }

        override fun isEssential(packageName: String): Boolean = packageName in essentials.get()

        override fun isStopped(packageName: String): Boolean? = system.isStopped(packageName, userId)

        override fun closedByUserSince(packages: List<String>, sinceMs: Long): Boolean {
            // Exit records carry wall-clock times; sinceMs is on the elapsedRealtime clock.
            val since = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sinceMs)
            return packages.any { pkg -> system.lastClosedByUser(pkg, userId)?.let { it >= since } == true }
        }

        override fun forceStop(packageName: String): Boolean {
            val stopped = system.forceStop(packageName, userId)
            if (stopped) reapplyRestrictions(packageName)
            return stopped
        }

        override fun onStopped(packageName: String, reason: String, sinceLeftMs: Long, procState: Int) {
            log.add(packageName, reason, sinceLeftMs, procState)
            Log.i(TAG, "stopped $packageName ($reason, state $procState)")
            handler.removeCallbacks(pushEvents)
            handler.postDelayed(pushEvents, PUSH_DELAY_MS)
        }
    }

    private val enforcer: Enforcer = Enforcer(host, states)
    private val watcher: PackageWatcher = PackageWatcher(handler, system, { userId }, ::onPackagesChanged)

    private val tick: Runnable = Runnable {
        tickAt = Long.MAX_VALUE
        enforcer.tick()
    }

    private val sweep: Runnable = object : Runnable {
        override fun run() {
            sweepNow()
            handler.postDelayed(this, sweepInterval())
        }
    }

    private val pushEvents: Runnable = Runnable {
        val target = listener ?: return@Runnable
        // A frozen app would only queue these up; it pulls missed events when it comes back.
        if (!isAppInForeground()) return@Runnable
        val events = log.after(pushedEventId)
        if (events.isEmpty()) return@Runnable
        try {
            target.onEvents(DaemonEvent.listToJson(events))
            pushedEventId = events.last().id
        } catch (_: RemoteException) {
            listener = null
        }
    }

    fun start() {
        handler.post {
            val uidObserver = UidObserver(states.nonexistent, ::onUidState)
            if (system.registerUidObserver(uidObserver)) observer = uidObserver
            watcher.start()
            handler.postDelayed(sweep, sweepInterval())
            Log.i(TAG, "started as uid ${Process.myUid()}, watching via ${watchMode()}")
        }
    }

    fun shutdown() {
        runOnHandler {
            observer?.let(system::unregisterUidObserver)
            watcher.stop()
        }
    }

    fun applyConfig(json: String) {
        val newConfig = try {
            DaemonConfig.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "ignoring malformed config", e)
            return
        }
        handler.post {
            config = newConfig
            uidPackages.clear()
            exemptSelf()
            val blocked = newConfig.rules.filter { (pkg, rule) ->
                rule.blocked && pkg != selfPackage && pkg !in newConfig.protectedPackages && pkg !in ALWAYS_PROTECTED
            }
            updateRestrictions(newConfig, if (newConfig.enabled) blocked else emptyMap())
            val policy = Enforcer.Policy(newConfig.enabled, newConfig.graceMs, newConfig.allowOngoing, blocked)
            enforcer.setPolicy(policy, if (newConfig.enabled) runningBlockedStates(blocked.values) else emptyMap())
            Log.i(TAG, "config: enabled=${newConfig.enabled} blocked=${blocked.size} grace=${newConfig.graceMs}ms")
        }
    }

    fun setListener(newListener: IDaemonListener?) {
        handler.post {
            listener = newListener
            // The app pulls everything it missed right after registering; push only what's new.
            pushedEventId = log.after(0).lastOrNull()?.id ?: 0
            try {
                newListener?.asBinder()?.linkToDeath(object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        if (listener === newListener) listener = null
                    }
                }, 0)
            } catch (_: RemoteException) {
                listener = null
            }
        }
    }

    fun events(afterId: Long): String = DaemonEvent.listToJson(log.after(afterId))

    fun runningBlocked(): String = runOnHandler {
        sweepNow()
        RunningApp.listToJson(enforcer.snapshot())
    } ?: "[]"

    fun stopNow(packages: List<String>): Int = runOnHandler {
        val cfg = config
        enforcer.stopNow(packages.filter { it != selfPackage && it !in ALWAYS_PROTECTED && cfg?.protectedPackages?.contains(it) != true })
    } ?: 0

    fun stopAll(): Int = runOnHandler {
        sweepNow()
        enforcer.stopAllInBackground()
    } ?: 0

    fun resetRestrictions() {
        runOnHandler {
            for (pkg in restrictions.background) uidOf(pkg)?.let { unrestrictBackground(pkg, it) }
            for (pkg in restrictions.standby) system.setStandbyBucket(pkg, SystemApis.BUCKET_ACTIVE, userId)
            restrictions.background.clear()
            restrictions.standby.clear()
            restrictions.save()
        }
    }

    fun info(): String = DaemonInfo(
        uid = Process.myUid(),
        pid = Process.myPid(),
        versionCode = BuildConfig.VERSION_CODE,
        sdk = Build.VERSION.SDK_INT,
        startedAt = startedAt,
        watchMode = watchMode(),
        stops = log.total,
    ).toJson()

    fun isTrustedCaller(uid: Int): Boolean {
        if (uid == 0 || uid == SHELL_UID || uid == Process.myUid()) return true
        synchronized(trustedUids) {
            if (uid in trustedUids) return true
            val trusted = system.packagesForUid(uid)?.contains(selfPackage) == true
            if (trusted) trustedUids += uid
            return trusted
        }
    }

    private fun onUidState(uid: Int, procState: Int) {
        val cfg = config ?: return
        if (!cfg.enabled) return
        if (uid / SystemApis.PER_USER_RANGE != cfg.userId) return
        if (uid % SystemApis.PER_USER_RANGE !in APP_UID_RANGE) return
        handler.post { enforcer.onProcState(uid, procState) }
    }

    private fun sweepNow() {
        val cfg = config ?: return
        if (!cfg.enabled) return
        enforcer.reconcile(runningBlockedStates(enforcer.policy.blocked.values))
    }

    /** Current process state of every running uid that has a blocked package; null if unknown. */
    private fun runningBlockedStates(rules: Collection<DaemonConfig.Rule>): Map<Int, Int>? {
        val blockedUids = rules.mapTo(HashSet()) { it.uid }
        if (blockedUids.isEmpty()) return emptyMap()
        return system.runningAppUids(userId)
            ?.filter { it in blockedUids }
            ?.associateWith { system.uidProcessState(it) ?: states.nonexistent }
    }

    private fun sweepInterval(): Long = when {
        observer != null -> SWEEP_INTERVAL_MS
        system.isInteractive() -> POLL_INTERVAL_MS
        else -> IDLE_POLL_INTERVAL_MS
    }

    private fun watchMode() = if (observer != null) "observer" else "polling"

    private fun onPackagesChanged(names: List<String>) {
        uidPackages.clear()
        val cfg = config ?: return
        if (system.isPackageAvailable(selfPackage, cfg.userId) == false) {
            onSelfRemoved()
            return
        }
        val forApp = ArrayList<String>()
        for (pkg in names) {
            if (pkg == selfPackage) continue
            val available = system.isPackageAvailable(pkg, cfg.userId) ?: continue
            val rule = cfg.rules[pkg]
            if (rule == null) {
                val info = if (available) system.applicationInfo(pkg, cfg.userId) else null
                if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM == 0) forApp += pkg
            } else if (!available) {
                forApp += pkg
            } else {
                // Reinstalled apps get a new uid; the app has to refresh its rules.
                val uid = system.applicationInfo(pkg, cfg.userId)?.uid
                if (uid != null && uid != rule.uid) forApp += pkg
                if (rule.blocked) handler.postDelayed({ enforcer.recheck(pkg) }, RECHECK_AFTER_UPDATE_MS)
            }
        }
        if (forApp.isNotEmpty()) notifyApp(forApp)
    }

    /**
     * Wakes the app with an explicit broadcast. Even a live listener isn't enough here: a cached
     * app is frozen and would only see a binder call once the user opens it again.
     */
    private fun notifyApp(packages: List<String>) {
        val user = userId.toString()
        val flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES.toString()
        val args = listOf(
            "broadcast", "--user", user,
            "-a", DaemonProtocol.ACTION_PACKAGES_CHANGED,
            "-n", "$selfPackage/${DaemonProtocol.RECEIVER_CLASS}",
            "-f", flags,
            "--esa", DaemonProtocol.EXTRA_PACKAGES, packages.joinToString(","),
        )
        shell.execute {
            if (output(listOf("cmd", "activity") + args) == null && output(listOf("am") + args) == null) {
                Log.w(TAG, "could not notify the app about $packages")
            }
        }
    }

    /** Runs a shell command as the daemon; its output, or null if it failed. */
    private fun output(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
    } catch (e: Exception) {
        Log.w(TAG, "${command.first()} failed", e)
        null
    }

    private fun onSelfRemoved() {
        Log.i(TAG, "app was uninstalled, undoing restrictions and exiting")
        resetRestrictions()
        observer?.let(system::unregisterUidObserver)
        exitProcess(0)
    }

    private fun exemptSelf() {
        system.ensurePowerWhitelisted(selfPackage)
        val uid = selfUid
        if (uid < 0) return
        system.setOpMode(SystemApis.OP_RUN_ANY_IN_BACKGROUND, uid, selfPackage, SystemApis.MODE_ALLOWED)
        if (SystemApis.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED >= 0) {
            system.setOpMode(SystemApis.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, uid, selfPackage, SystemApis.MODE_IGNORED)
        }
    }

    private fun updateRestrictions(cfg: DaemonConfig, blocked: Map<String, DaemonConfig.Rule>) {
        val wantBackground = if (cfg.restrictBackground) blocked.keys else emptySet()
        val wantStandby = if (cfg.restrictStandby) blocked.keys else emptySet()
        var changed = false
        for (pkg in wantBackground) {
            if (restrictBackground(pkg, blocked.getValue(pkg).uid)) changed = restrictions.background.add(pkg) || changed
        }
        for (pkg in restrictions.background.toList()) {
            if (pkg in wantBackground) continue
            uidOf(pkg)?.let { unrestrictBackground(pkg, it) }
            restrictions.background.remove(pkg)
            changed = true
        }
        for (pkg in wantStandby) {
            if (system.setStandbyBucket(pkg, restrictedBucket, cfg.userId)) changed = restrictions.standby.add(pkg) || changed
        }
        for (pkg in restrictions.standby.toList()) {
            if (pkg in wantStandby) continue
            if (system.isPackageAvailable(pkg, cfg.userId) == true) system.setStandbyBucket(pkg, SystemApis.BUCKET_ACTIVE, cfg.userId)
            restrictions.standby.remove(pkg)
            changed = true
        }
        if (changed) restrictions.save()
    }

    /** Using an app lifts its standby bucket, so restrictions are re-applied after each stop. */
    private fun reapplyRestrictions(packageName: String) {
        if (packageName in restrictions.standby) system.setStandbyBucket(packageName, restrictedBucket, userId)
        if (packageName in restrictions.background) uidOf(packageName)?.let { restrictBackground(packageName, it) }
    }

    private fun restrictBackground(packageName: String, uid: Int): Boolean =
        system.setOpMode(SystemApis.OP_RUN_ANY_IN_BACKGROUND, uid, packageName, SystemApis.MODE_IGNORED) and
            system.setOpMode(SystemApis.OP_RUN_IN_BACKGROUND, uid, packageName, SystemApis.MODE_IGNORED)

    private fun unrestrictBackground(packageName: String, uid: Int) {
        system.setOpMode(SystemApis.OP_RUN_ANY_IN_BACKGROUND, uid, packageName, SystemApis.MODE_ALLOWED)
        system.setOpMode(SystemApis.OP_RUN_IN_BACKGROUND, uid, packageName, SystemApis.MODE_ALLOWED)
    }

    private fun uidOf(packageName: String): Int? =
        config?.rules?.get(packageName)?.uid ?: system.applicationInfo(packageName, userId)?.uid

    private fun isAppInForeground(): Boolean {
        val state = system.uidProcessState(selfUid) ?: return false
        return states.classify(state, allowOngoing = true).let { it == ProcStateTable.Usage.USER || it == ProcStateTable.Usage.HELD }
    }

    /** Runs [block] on the daemon thread and waits for the result (null on timeout). */
    private fun <T> runOnHandler(block: () -> T): T? {
        if (Thread.currentThread() === thread) return block()
        val task = FutureTask(block)
        handler.post(task)
        return try {
            task.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "daemon thread call failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "StopDaemon"
        const val SHELL_UID = 2000
        const val BUCKET_RARE = 40
        val APP_UID_RANGE = 10_000..19_999

        /** Never touched, whatever the app sends. */
        val ALWAYS_PROTECTED = setOf(
            "android",
            "com.android.systemui",
            "com.android.shell",
            "com.android.phone",
            "moe.shizuku.privileged.api",
        )

        const val SWEEP_INTERVAL_MS = 3 * 60_000L
        const val POLL_INTERVAL_MS = 5_000L
        const val IDLE_POLL_INTERVAL_MS = 30_000L
        const val PUSH_DELAY_MS = 300L
        const val RECHECK_AFTER_UPDATE_MS = 5_000L
    }
}
