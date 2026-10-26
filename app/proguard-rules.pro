# The daemon runs from this APK inside a process Shizuku starts, which instantiates
# StopDaemonService by class name. UidObserver also declares overloads for older framework
# signatures that look unused at compile time but are called by the device's IUidObserver.Stub.
-keep class io.github.burakgon.stop.daemon.** { *; }

# Hidden framework interfaces from :hidden-api only exist on the device.
-dontwarn android.app.IActivityManager
-dontwarn android.app.IActivityManager$**
-dontwarn android.app.IUidObserver
-dontwarn android.app.IUidObserver$**
-dontwarn android.app.usage.IUsageStatsManager
-dontwarn android.app.usage.IUsageStatsManager$**
-dontwarn android.content.pm.IPackageManager
-dontwarn android.content.pm.IPackageManager$**
-dontwarn android.os.IDeviceIdleController
-dontwarn android.os.IDeviceIdleController$**
-dontwarn android.os.IPowerManager
-dontwarn android.os.IPowerManager$**
-dontwarn android.os.ServiceManager
-dontwarn com.android.internal.app.IAppOpsService
-dontwarn com.android.internal.app.IAppOpsService$**
