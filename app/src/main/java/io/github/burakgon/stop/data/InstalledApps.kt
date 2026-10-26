package io.github.burakgon.stop.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.burakgon.stop.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cached list of installed apps for the current user, reloaded when packages change. */
class InstalledApps(private val context: Context) {
    private val pm = context.packageManager
    private val mutex = Mutex()
    private val _apps = MutableStateFlow<Map<String, InstalledApp>?>(null)

    /** Null until the first load finishes. */
    val apps: StateFlow<Map<String, InstalledApp>?> = _apps.asStateFlow()

    suspend fun get(): Map<String, InstalledApp> = _apps.value ?: refresh()

    suspend fun refresh(): Map<String, InstalledApp> = mutex.withLock {
        withContext(Dispatchers.IO) { load() }.also { _apps.value = it }
    }

    fun versionName(packageName: String): String? = try {
        packageInfo(packageName).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun load(): Map<String, InstalledApp> {
        val launchable = launchablePackages()
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return packages.mapNotNull { pkg ->
            val info = pkg.applicationInfo ?: return@mapNotNull null
            pkg.packageName to InstalledApp(
                packageName = pkg.packageName,
                label = info.loadLabel(pm).toString().ifBlank { pkg.packageName },
                uid = info.uid,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isLaunchable = pkg.packageName in launchable,
                category = info.category,
                firstInstallTime = pkg.firstInstallTime,
            )
        }.toMap()
    }

    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return activities.mapTo(HashSet()) { it.activityInfo.packageName }
    }

    private fun packageInfo(packageName: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, 0)
    }

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    fun isInstalled(packageName: String): Boolean = try {
        packageInfo(packageName)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    val userId: Int get() = context.applicationInfo.uid / PER_USER_RANGE

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
