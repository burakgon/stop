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
import io.github.burakgon.stop.model.WorkMode
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

        override fun isStopped(packageName: String): Boolean? =
            if (packageName in restrictions.frozen) true else system.isStopped(packageName, userId)

        override fun closedByUserSince(packages: List<String>, sinceMs: Long): Boolean {
            // Exit records carry wall-clock times; sinceMs is on the elapsedRealtime clock.
            val since = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sinceMs)
            return packages.any { pkg -> system.lastClosedByUser(pkg, userId)?.let { it >= since } == true }
        }

        override fun forceStop(packageName: String): Boolean {
            val stopped = block(packageName)
            if (stopped) reapplyRestrictions(packageName)
            return stopped
        }

        override fun onStopped(packageName: String, reason: String, sinceLeftMs: Long, procState: Int) {
            Log.i(TAG, "stopped $packageName ($reason, state $procState)")
            recordEvent(packageName, reason, sinceLeftMs, procState)
        }
    }

    private val debug = DebugDump(File("/data/local/tmp/$selfPackage"))

    private val enforcer: Enforcer = Enforcer(host, states).also { it.trace = debug::write }
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

    /**
     * Records one event and schedules the debounced push. The daemon mostly runs while the app is
     * asleep, so [pushEvents] drops the push unless the app is in front; the app pulls the backlog
     * when it comes back.
     */
    private fun recordEvent(packageName: String, reason: String, sinceLeftMs: Long = -1L, procState: Int = -1) {
        log.add(packageName, reason, sinceLeftMs, procState)
        handler.removeCallbacks(pushEvents)
        handler.postDelayed(pushEvents, PUSH_DELAY_MS)
    }

    /** Last "tapped while frozen" event per package, so re-polling the same screen logs once. */
    private val tappedAt = HashMap<String, Long>()

    /**
     * Watches activity starts in the system log. A frozen app cannot start, and the dialog the system
     * shows instead carries no hint of which app was tapped — but ActivityTaskManager still logs the
     * original `START ... cmp=<pkg>/<activity>` line. That line is the only reliable signal, so it is
     * used to treat a tap as intent to use the app right now: the freeze is lifted and the app is
     * launched, so the user gets "tap and it opens" instead of a dead dialog.
     */
    private var activityLog: java.lang.Process? = null
    private var watcherStopped = false

    private fun startActivityWatcher() {
        if (activityLog != null || watcherStopped) return
        runCatching {
            val process = ProcessBuilder("/system/bin/logcat", "-v", "brief", "-s", "ActivityTaskManager:I")
                .redirectErrorStream(true)
                .start()
            activityLog = process
            Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine(::onStartLogged)
                }
                activityLog = null
                // logcat exits when the buffer is recreated (crash, restart of the system server),
                // so the watcher has to come back on its own or tap-to-open silently stops working.
                if (!watcherStopped) handler.postDelayed({ startActivityWatcher() }, WATCHER_RESTART_MS)
                debug.write("activity watcher ended")
            }, "stop-logcat").apply { isDaemon = true }.start()
            debug.write("activity watcher started")
        }.onFailure {
            Log.w(TAG, "cannot watch activity starts", it)
            debug.write("activity watcher failed: ${it.message}")
        }
    }

    private fun onStartLogged(line: String) {
        if (!line.contains("START u")) return
        val match = START_PATTERN.find(line) ?: return
        val packageName = match.groupValues[1]
        val activity = match.groupValues[2]
        if (packageName == selfPackage) return
        handler.post {
            if (packageName !in restrictions.frozen && packageName !in restrictions.auto) return@post
            val now = SystemClock.elapsedRealtime()
            // Only guards against the same start being echoed twice; a second real tap must work
            // again, and by then the app has usually been re-frozen by the background rules.
            if (now - (tappedAt[packageName] ?: 0L) <= ACTION_DEDUPE_MS) return@post
            tappedAt[packageName] = now
            Log.i(TAG, "frozen app $packageName was opened; restoring and launching it")
            recordEvent(packageName, StopReasons.TAPPED)
            restore(packageName, shellFallback = true)
            if (!launch(packageName, activity)) {
                Log.w(TAG, "could not relaunch $packageName after restoring it")
            }
            // The app is allowed to run now, so the background rules have to judge it again from
            // scratch the moment the user leaves it.
            enforcer.recheck(packageName)
        }
    }

    /**
     * Shizuku user services are ordinary processes: replacing the app leaves the old ones alive
     * and the app can end up talking to a daemon built from a previous APK. Anything still running
     * the same `:daemon` class therefore gets killed before this one starts watching.
     */
    private fun reapOrphans() {
        val self = Process.myPid()
        val uid = Process.myUid()
        val marker = "$selfPackage:daemon"
        val uptimeTicks = runCatching {
            File("/proc/uptime").readText().substringBefore(' ').toDouble()
        }.getOrDefault(0.0)
        var killed = 0
        runCatching {
            File("/proc").listFiles()?.forEach { entry ->
                val pid = entry.name.toIntOrNull() ?: return@forEach
                if (pid == self) return@forEach
                val cmdline = runCatching { File(entry, "cmdline").readText() }
                    .getOrNull()?.trimEnd('\u0000') ?: return@forEach
                if (!cmdline.contains(marker)) return@forEach
                val owner = runCatching {
                    File(entry, "status").readLines()
                        .firstOrNull { it.startsWith("Uid:") }
                        ?.split('\t', ' ')?.filter { it.isNotBlank() }?.getOrNull(1)?.toIntOrNull()
                }.getOrNull()
                if (owner != null && owner != uid) return@forEach
                val started = runCatching { startTicks(entry) }.getOrNull()
                if (started != null && uptimeTicks > 0.0 &&
                    uptimeTicks - started / 100.0 < SIBLING_GRACE_SECONDS
                ) {
                    return@forEach
                }
                runCatching { Process.killProcess(pid) }.onSuccess { killed++ }
            }
        }
        if (killed > 0) Log.i(TAG, "reaped $killed stale daemon process(es)")
        debug.write("reap self=$self killed=$killed")
    }

    private fun startTicks(entry: File): Double? {
        val stat = File(entry, "stat").readText()
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        // Field 22 (starttime); the fields after "pid (comm)" restart at index 0 == state (field 3).
        return stat.substring(close + 1).trim().split(' ').getOrNull(19)?.toDoubleOrNull()
    }

    fun start() {
        handler.post {
            reapOrphans()
            val uidObserver = UidObserver(states.nonexistent, ::onUidState)
            if (system.registerUidObserver(uidObserver)) observer = uidObserver
            watcher.start()
            handler.postDelayed(sweep, sweepInterval())
            startActivityWatcher()
            Log.i(TAG, "started as uid ${Process.myUid()}, watching via ${watchMode()}")
            debug.write("start uid=${Process.myUid()} watch=${watchMode()} observer=${observer != null}")
        }
    }

    fun shutdown() {
        runOnHandler {
            observer?.let(system::unregisterUidObserver)
            watcher.stop()
            watcherStopped = true
            runCatching { activityLog?.destroy() }
            activityLog = null
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
            val mode = WorkMode.fromCode(newConfig.mode)
            // 空闲模式什么都不冻结，只保留额外限制（后台运行/待机分组）。
            val enforcing = newConfig.enabled && mode != WorkMode.IDLE
            updateRestrictions(newConfig, if (newConfig.enabled) blocked else emptyMap())
            // 切换工作模式、解除限制或关闭保护时，把之前用停用/隐藏/暂停冻住的应用放出来。
            syncFrozen(mode, if (enforcing) blocked.keys else emptySet())
            val policy = Enforcer.Policy(enforcing, newConfig.graceMs, newConfig.allowOngoing, blocked)
            enforcer.setPolicy(policy, if (enforcing) runningBlockedStates(blocked.values) else emptyMap())
            Log.i(TAG, "config: enabled=${newConfig.enabled} mode=${mode.code} blocked=${blocked.size} grace=${newConfig.graceMs}ms")
            debug.write(
                "config enabled=${newConfig.enabled} mode=${mode.code} enforcing=$enforcing " +
                    "grace=${newConfig.graceMs} allowOngoing=${newConfig.allowOngoing} " +
                    "blocked=${blocked.keys.joinToString(",")}",
            )
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

    /** Freezes or restores one package on request (desktop shortcut toggle). */
    fun setFrozen(packageName: String, frozen: Boolean): Boolean = runOnHandler<Boolean> {
        if (frozen) {
            if (restrictions.frozen.containsKey(packageName)) {
                true
            } else {
                val mechanism = freeze(packageName, shellFallback = true)
                if (mechanism == null) {
                    false
                } else {
                    restrictions.frozen[packageName] = mechanism
                    restrictions.manual.add(packageName)
                    system.forceStop(packageName, userId)
                    recordEvent(packageName, StopReasons.FREEZE)
                    true
                }
            }
        } else {
            // The daemon may not know the tool: an app suspended from the home screen ("挂起") is
            // frozen in the system without being recorded, so always undo instead of trusting the map.
            restore(packageName, shellFallback = true)
            true
        }
    } ?: false

    /** Force-stops one package now, whatever the rules or work mode say (home "强制停止"). */
    fun forceStopNow(packageName: String): Boolean = runOnHandler<Boolean> {
        if (packageName in ALWAYS_PROTECTED) {
            false
        } else {
            Log.i(TAG, "force stop $packageName on request")
            system.forceStop(packageName, userId).also { if (it) recordEvent(packageName, StopReasons.MANUAL) }
        }
    } ?: false

    /**
     * Suspends one package now without recording it as a permanent freeze (home "挂起"). The app
     * stays suspended until the user restores it, so a later [restore] with no recorded tool undoes
     * suspension first.
     */
    fun suspendNow(packageName: String): Boolean = runOnHandler<Boolean> {
        if (packageName in ALWAYS_PROTECTED) {
            false
        } else {
            val dialog = config?.suspendDialog?.ifBlank { null }
            val suspended = system.setSuspended(packageName, true, userId, dialog) ||
                suspendViaShell(packageName, true)
            if (suspended) {
                // Suspending keeps the process; the user asked for the app to stop running.
                system.forceStop(packageName, userId)
                Log.i(TAG, "suspended $packageName on request")
            }
            suspended
        }
    } ?: false

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
            for (pkg in restrictions.frozen.keys.toList()) restore(pkg, shellFallback = true)
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

    /** Packages Stop froze permanently, comma separated: the app uses it to tell them apart from
     *  a plain suspend made from the home screen. */
    fun frozenPackages(): String = runOnHandler {
        restrictions.frozen.keys.filter { it.isNotEmpty() }.joinToString(",")
    } ?: ""

    /** Of the comma-separated packages, the ones the framework currently reports as suspended. */
    fun suspendedPackages(packages: String): String = runOnHandler {
        pruneFrozen()
        packages.split(',')
            .filter { it.isNotBlank() && system.isSuspended(it, userId) }
            .joinToString(",")
    } ?: ""

    fun isTrustedCaller(uid: Int): Boolean {
        if (uid == 0 || uid == SHELL_UID || uid == Process.myUid()) return true
        synchronized(trustedUids) {
            if (uid in trustedUids) return true
            val trusted = system.packagesForUid(uid)?.contains(selfPackage) == true
            if (trusted) trustedUids += uid
            return trusted
        }
    }

    /**
     * Drops freeze records the framework no longer agrees with. A suspend can be lifted without
     * going through Stop (the system dialog's unfreeze button, or another tool), and keeping the
     * stale record would both mislabel the app and make "解冻" restore the wrong mechanism.
     */
    private fun pruneFrozen() {
        if (restrictions.frozen.isEmpty()) return
        val released = restrictions.frozen.keys.filter { packageName ->
            val info = system.applicationInfo(packageName, userId)
            info?.enabled != false && !system.isSuspended(packageName, userId)
        }
        if (released.isEmpty()) return
        for (packageName in released) {
            restrictions.frozen.remove(packageName)
            restrictions.manual.remove(packageName)
            recordEvent(packageName, StopReasons.THAW)
        }
        restrictions.save()
        debug.write("frozen released externally: ${released.joinToString(",")}")
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
        // The user can lift a freeze from the system's own dialog, so the record may be stale.
        pruneFrozen()
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
            // 被停用/隐藏/暂停的应用本来就会以“不可用”的形式出现，不要再报给应用造成刷新循环。
            if (pkg in restrictions.frozen) continue
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

    /**
     * Freezes [packageName] with the current work mode. Force stop is the default; [WorkMode.FREEZE]
     * also makes the app unusable until it is restored, so the tool used is remembered.
     */
    private fun block(packageName: String): Boolean {
        val mode = WorkMode.fromCode(config?.mode)
        if (mode == WorkMode.SUSPEND_STOP) return blockAndSuspend(packageName)
        val mechanism = if (mode == WorkMode.FREEZE) freeze(packageName) else null
        if (mode == WorkMode.FREEZE && mechanism == null) {
            Log.w(TAG, "could not freeze $packageName: no tool worked")
            return false
        }
        val stopped = system.forceStop(packageName, userId)
        if (mechanism != null) {
            if (restrictions.frozen.put(packageName, mechanism) != mechanism) restrictions.save()
            recordEvent(packageName, StopReasons.FREEZE)
        }
        if (!stopped && mechanism == null) Log.w(TAG, "could not stop $packageName")
        return stopped || mechanism != null
    }

    /**
     * Stops a blocked app and suspends it: it cannot come back in the background on its own, while
     * the icon stays and the system screen explains why. Unlike [freeze] nothing is treated as a
     * permanent decision, so lifting the restriction restores the app again.
     */
    private fun blockAndSuspend(packageName: String): Boolean {
        val dialog = config?.suspendDialog?.ifBlank { null }
        val suspended = system.setSuspended(packageName, true, userId, dialog)
        if (suspended && restrictions.auto.add(packageName)) restrictions.save()
        val stopped = system.forceStop(packageName, userId)
        if (!suspended) Log.w(TAG, "could not suspend $packageName in ${WorkMode.SUSPEND_STOP.code} mode")
        return suspended || stopped
    }

    /**
     * Permanently freezes one package with the first tool that works.
     *
     * Suspend goes first: `pm suspend` keeps the icon in the launcher (greyed out), the app cannot
     * start, and tapping the icon shows the system screen with our explanation — no shortcut needed
     * to find the app again. Disable (icon disappears) and hide are fallbacks for ROMs that refuse
     * to suspend. The tool that actually worked is recorded so [restore] can undo exactly that one.
     */
    private fun freeze(packageName: String, shellFallback: Boolean = false): String? {
        val dialog = config?.suspendDialog?.ifBlank { null }
        if (system.setSuspended(packageName, true, userId, dialog)) return FROZEN_SUSPEND
        // Some releases refuse the binder call from an app process; the shell command is the same
        // framework entry point `pm` uses, and the daemon already runs as shell.
        if (shellFallback && suspendViaShell(packageName, true)) return FROZEN_SUSPEND
        if (system.setEnabled(packageName, false, userId)) return FROZEN_DISABLE
        if (shellFallback && disableViaShell(packageName, true)) return FROZEN_DISABLE
        if (system.setHidden(packageName, true, userId)) return FROZEN_HIDE
        return null
    }

    private fun suspendViaShell(packageName: String, suspend: Boolean): Boolean =
        output(listOf("pm", if (suspend) "suspend" else "unsuspend", "--user", userId.toString(), packageName)) != null

    /** Restarts exactly the activity the user aimed at, over the system's "suspended" dialog. */
    private fun launch(packageName: String, activity: String): Boolean {
        val result = output(
            listOf(
                "am", "start",
                "--user", userId.toString(),
                "-n", "$packageName/$activity",
            )
        ) ?: return false
        return !result.contains("Error")
    }

    private fun disableViaShell(packageName: String, disable: Boolean): Boolean =
        output(listOf("pm", if (disable) "disable-user" else "enable", "--user", userId.toString(), packageName)) != null

    /** Undoes whatever [block] did, so the app can be launched again. */
    private fun restore(packageName: String, shellFallback: Boolean = false) {
        when (restrictions.frozen[packageName]) {
            FROZEN_DISABLE -> if (!system.setEnabled(packageName, true, userId) && shellFallback) {
                disableViaShell(packageName, false)
            }
            FROZEN_HIDE -> system.setHidden(packageName, false, userId)
            FROZEN_SUSPEND -> if (!system.setSuspended(packageName, false, userId, null) && shellFallback) {
                suspendViaShell(packageName, false)
            }
            // Suspended from the home screen, which doesn't track the tool: undo both candidates.
            null -> {
                if (!system.setSuspended(packageName, false, userId, null) && shellFallback) {
                    suspendViaShell(packageName, false)
                }
                system.setEnabled(packageName, true, userId)
            }
        }
        restrictions.manual.remove(packageName)
        // A restriction-store entry is only present for mode-based suspensions.
        if (restrictions.auto.remove(packageName)) restrictions.save()
        if (restrictions.frozen.remove(packageName) != null) restrictions.save()
        // Untracked suspensions are undone here too, so they still belong in the event log.
        recordEvent(packageName, StopReasons.THAW)
    }

    /** Restores apps frozen under another mode, or that are no longer blocked. */
    private fun syncFrozen(mode: WorkMode, wanted: Set<String>) {
        // Suspensions made by the forced-stop-plus-suspend mode are lifted as soon as they no
        // longer apply, so switching modes must not leave apps stuck in the suspended state.
        for (pkg in restrictions.auto.toList()) {
            if (pkg in wanted && mode == WorkMode.SUSPEND_STOP) continue
            restore(pkg)
        }
        for ((pkg, frozenMode) in restrictions.frozen.toMap()) {
            // Hand-frozen apps stay frozen while they are still limited, whatever the work mode.
            if (pkg in wanted && pkg in restrictions.manual) continue
            if (pkg in wanted && WorkMode.fromCode(frozenMode) == mode) continue
            restore(pkg)
        }
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

        /** How a package was frozen, stored in [io.github.burakgon.stop.daemon.RestrictionStore.frozen]. */
        const val FROZEN_DISABLE = "disable"
        const val FROZEN_HIDE = "hide"
        const val FROZEN_SUSPEND = "suspend"

        /** A daemon younger than this is most likely the sibling that Shizuku is still starting. */
        const val SIBLING_GRACE_SECONDS = 10.0

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

        /** How often the daemon looks for the system's "app suspended" screen while freezing. */
        /** `I/ActivityTaskManager: START u0 {flg=0x10200000 cmp=com.foo/.Main} from uid 10123`. */
        val START_PATTERN = Regex("""START u\d* \{.*?cmp=([A-Za-z0-9_.]+)/([^\s}]+)""")

        /** One "tapped while frozen" event per package per window, however long the screen stays. */
        /** Guards against the same activity start being seen twice; a later tap must work again. */
        const val ACTION_DEDUPE_MS = 2_000L

        /** logcat exits when the log buffers are recreated; the watcher re-arms itself. */
        const val WATCHER_RESTART_MS = 5_000L
    }
}
