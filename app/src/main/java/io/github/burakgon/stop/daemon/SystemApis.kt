package io.github.burakgon.stop.daemon

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.app.IActivityManager
import android.app.IUidObserver
import android.app.usage.IUsageStatsManager
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.IPackageManager
import android.os.Build
import android.os.IBinder
import android.os.IDeviceIdleController
import android.os.IPowerManager
import android.os.ServiceManager
import android.util.Log
import com.android.internal.app.IAppOpsService
import java.lang.reflect.Method

/**
 * Thin wrappers around the system services the daemon talks to. Calls that changed shape between
 * Android releases go through reflection; everything degrades to null/false instead of throwing.
 * They catch Throwable on purpose: a hidden method that changes shape in a future release throws
 * NoSuchMethodError, which should cost a feature, not the daemon.
 */
internal class SystemApis {
    private val am: IActivityManager = IActivityManager.Stub.asInterface(service("activity"))
    private val pm: IPackageManager = IPackageManager.Stub.asInterface(service("package"))
    private val appOps: IAppOpsService = IAppOpsService.Stub.asInterface(service("appops"))
    private val usageStats: IUsageStatsManager? =
        ServiceManager.getService("usagestats")?.let { IUsageStatsManager.Stub.asInterface(it) }
    private val power: IPowerManager = IPowerManager.Stub.asInterface(service("power"))
    private val deviceIdle: IDeviceIdleController? =
        ServiceManager.getService("deviceidle")?.let { IDeviceIdleController.Stub.asInterface(it) }

    private val isStoppedMethod: Method? = pm.javaClass.methods.firstOrNull {
        it.name == "isPackageStoppedForUser" && it.parameterTypes.size == 2
    }
    private val applicationInfoMethod: Method? = pm.javaClass.methods.firstOrNull {
        it.name == "getApplicationInfo" && it.parameterTypes.size == 3
    }
    private val getTasksMethod: Method? = am.javaClass.methods.firstOrNull {
        it.name == "getTasks" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
    }
    private val exitReasonsMethod: Method? = am.javaClass.methods.firstOrNull {
        it.name == "getHistoricalProcessExitReasons" && it.parameterTypes.size == 4
    }

    fun registerUidObserver(observer: IUidObserver): Boolean {
        val which = amConstant("UID_OBSERVER_PROCSTATE", 1) or amConstant("UID_OBSERVER_GONE", 2)
        return try {
            am.registerUidObserver(observer, which, amConstant("PROCESS_STATE_UNKNOWN", -1), SHELL_PACKAGE)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "registerUidObserver failed, falling back to polling", e)
            false
        }
    }

    fun unregisterUidObserver(observer: IUidObserver) {
        try {
            am.unregisterUidObserver(observer)
        } catch (_: Throwable) {
        }
    }

    fun uidProcessState(uid: Int): Int? = try {
        am.getUidProcessState(uid, SHELL_PACKAGE)
    } catch (e: Throwable) {
        Log.w(TAG, "getUidProcessState($uid) failed", e)
        null
    }

    /** Uids in [userId] that currently have at least one app process, or null if unknown. */
    fun runningAppUids(userId: Int): Set<Int>? = try {
        am.runningAppProcesses.orEmpty()
            .map { it.uid }
            .filterTo(HashSet()) { it / PER_USER_RANGE == userId && it % PER_USER_RANGE in APP_UID_RANGE }
    } catch (e: Throwable) {
        Log.w(TAG, "getRunningAppProcesses failed", e)
        null
    }

    fun forceStop(packageName: String, userId: Int): Boolean = try {
        am.forceStopPackage(packageName, userId)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "forceStopPackage($packageName) failed", e)
        false
    }

    fun isStopped(packageName: String, userId: Int): Boolean? = try {
        isStoppedMethod?.invoke(pm, packageName, userId) as Boolean?
    } catch (_: Throwable) {
        null
    }

    /** Package names of the base and top activity of the task the user is in. */
    fun topTask(): Pair<String?, String?>? = try {
        val tasks = getTasksMethod?.invoke(am, 1) as List<*>?
        (tasks?.firstOrNull() as ActivityManager.RunningTaskInfo?)?.let {
            it.baseActivity?.packageName to it.topActivity?.packageName
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Wall-clock time the package's process last ended on request (swiped away from Recents,
     * force-stopped), or null if none of its recent exits were or Android doesn't keep records.
     */
    fun lastClosedByUser(packageName: String, userId: Int): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val slice = exitReasonsMethod?.invoke(am, packageName, 0, RECENT_EXITS, userId) ?: return null
            val exits = slice.javaClass.getMethod("getList").invoke(slice) as List<*>
            exits.filterIsInstance<ApplicationExitInfo>()
                .filter { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED && subReason(it) != SUBREASON_KILL_BACKGROUND }
                .maxOfOrNull { it.timestamp }
        } catch (_: Throwable) {
            null
        }
    }

    /** Tells a cleanup of background apps (killBackgroundProcesses) apart from the user closing the app. */
    private fun subReason(exit: ApplicationExitInfo): Int? = try {
        exit.javaClass.getMethod("getSubReason").invoke(exit) as Int?
    } catch (_: Throwable) {
        null
    }

    fun packagesForUid(uid: Int): Array<String>? = try {
        pm.getPackagesForUid(uid)
    } catch (_: Throwable) {
        null
    }

    /** Null when the package manager couldn't be asked. */
    fun isPackageAvailable(packageName: String, userId: Int): Boolean? = try {
        pm.isPackageAvailable(packageName, userId)
    } catch (_: Throwable) {
        null
    }

    fun applicationInfo(packageName: String, userId: Int): ApplicationInfo? = try {
        val method = applicationInfoMethod
        val flags: Any = if (method?.parameterTypes?.get(1) == Long::class.javaPrimitiveType) 0L else 0
        method?.invoke(pm, packageName, flags, userId) as ApplicationInfo?
    } catch (_: Throwable) {
        null
    }

    fun changedPackages(sequenceNumber: Int, userId: Int): ChangedPackages? = try {
        pm.getChangedPackages(sequenceNumber, userId)
    } catch (e: Throwable) {
        Log.w(TAG, "getChangedPackages failed", e)
        null
    }

    fun isInteractive(): Boolean = try {
        power.isInteractive
    } catch (_: Throwable) {
        true
    }

    fun opMode(op: Int, uid: Int, packageName: String): Int? = try {
        appOps.checkOperation(op, uid, packageName)
    } catch (_: Throwable) {
        null
    }

    fun setOpMode(op: Int, uid: Int, packageName: String, mode: Int): Boolean = try {
        if (opMode(op, uid, packageName) != mode) appOps.setMode(op, uid, packageName, mode)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "setMode($op, $packageName, $mode) failed", e)
        false
    }

    fun setStandbyBucket(packageName: String, bucket: Int, userId: Int): Boolean = try {
        val current = usageStats?.getAppStandbyBucket(packageName, SHELL_PACKAGE, userId)
        if (current != bucket) usageStats?.setAppStandbyBucket(packageName, bucket, userId)
        usageStats != null
    } catch (e: Throwable) {
        Log.w(TAG, "setAppStandbyBucket($packageName, $bucket) failed", e)
        false
    }

    fun ensurePowerWhitelisted(packageName: String) {
        try {
            val controller = deviceIdle ?: return
            if (!controller.isPowerSaveWhitelistApp(packageName)) controller.addPowerSaveWhitelistApp(packageName)
        } catch (e: Throwable) {
            Log.w(TAG, "could not exempt $packageName from battery optimization", e)
        }
    }

    companion object {
        private const val TAG = "StopDaemon"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val RECENT_EXITS = 5
        private const val SUBREASON_KILL_BACKGROUND = 24
        const val PER_USER_RANGE = 100_000
        private val APP_UID_RANGE = 10_000..19_999

        val OP_RUN_IN_BACKGROUND = opConstant("OP_RUN_IN_BACKGROUND", 63)
        val OP_RUN_ANY_IN_BACKGROUND = opConstant("OP_RUN_ANY_IN_BACKGROUND", 70)
        val OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED = opConstant("OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED", -1)

        const val MODE_ALLOWED = AppOpsManager.MODE_ALLOWED
        const val MODE_IGNORED = AppOpsManager.MODE_IGNORED

        const val BUCKET_ACTIVE = 10
        const val BUCKET_RESTRICTED = 45

        private fun service(name: String): IBinder =
            requireNotNull(ServiceManager.getService(name)) { "system service '$name' is not available" }

        private fun amConstant(name: String, fallback: Int): Int = staticInt(ActivityManager::class.java, name, fallback)

        private fun opConstant(name: String, fallback: Int): Int = staticInt(AppOpsManager::class.java, name, fallback)

        private fun staticInt(type: Class<*>, name: String, fallback: Int): Int = try {
            type.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
        } catch (_: ReflectiveOperationException) {
            fallback
        }
    }
}
