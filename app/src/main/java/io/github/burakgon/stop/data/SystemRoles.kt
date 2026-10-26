package io.github.burakgon.stop.data

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat
import io.github.burakgon.stop.model.InstalledApp

/** Knows which apps fill a system role and must keep running, or should at least be allowed. */
class SystemRoles(private val context: Context) {

    enum class Integration(val keepsRunning: Boolean) {
        ACCESSIBILITY(true),
        NOTIFICATION_LISTENER(true),
        AUTOFILL(true),
        ALARM_CLOCK(true),
        DEVICE_ADMIN(false),
        WALLPAPER(false),
    }

    /**
     * Apps Stop never blocks: the system's own plumbing, Shizuku, the current default apps, and
     * apps providing a service the user turned on. The daemon double-checks the roles that can
     * change while Stop isn't running, such as the keyboard.
     */
    fun protectedPackages(): Set<String> = buildSet {
        addAll(ALWAYS_PROTECTED)
        add(context.packageName)
        defaultHome()?.let(::add)
        addAll(enabledInputMethods())
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(::add)
        // Every app that shows web content runs the WebView provider's code.
        runCatching { WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()?.let(::add)
        integrations().filterValues { it.keepsRunning }.keys.let(::addAll)
    }

    /** Apps hooked into the system in a way that breaks when they can't run in the background. */
    fun integrations(): Map<String, Integration> = buildMap {
        runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.forEach { put(it.resolveInfo.serviceInfo.packageName, Integration.ACCESSIBILITY) }
        }
        runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).forEach { putIfAbsent(it, Integration.NOTIFICATION_LISTENER) }
        }
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins
                ?.forEach { putIfAbsent(it.packageName, Integration.DEVICE_ADMIN) }
        }
        runCatching {
            Settings.Secure.getString(context.contentResolver, "autofill_service")
                ?.let(ComponentName::unflattenFromString)
                ?.let { putIfAbsent(it.packageName, Integration.AUTOFILL) }
        }
        // Force-stopping an app deletes its alarms, so a stopped clock app wouldn't ring.
        runCatching {
            activityHandlers(AlarmClock.ACTION_SHOW_ALARMS).forEach { putIfAbsent(it, Integration.ALARM_CLOCK) }
        }
        runCatching {
            WallpaperManager.getInstance(context).wallpaperInfo?.packageName?.let { putIfAbsent(it, Integration.WALLPAPER) }
        }
    }

    /**
     * Apps that most people want to keep running in the background: messengers, mail, music,
     * maps, authenticators, VPNs, wearables, apps that set alarms or reminders, plus anything with
     * a system integration.
     */
    fun suggestedAllowed(apps: Collection<InstalledApp>, integrations: Map<String, Integration>): Set<String> {
        val setsAlarms = runCatching { activityHandlers(AlarmClock.ACTION_SET_ALARM) }.getOrDefault(emptySet())
        return apps.filterTo(HashSet()) { app ->
            app.packageName in integrations ||
                app.packageName in KNOWN_BACKGROUND_APPS ||
                app.packageName in setsAlarms ||
                app.category == ApplicationInfo.CATEGORY_AUDIO ||
                app.category == ApplicationInfo.CATEGORY_MAPS
        }.mapTo(HashSet()) { it.packageName }
    }

    private fun defaultHome(): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    private fun enabledInputMethods(): List<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }
    }.getOrNull().orEmpty()

    private fun activityHandlers(action: String): Set<String> {
        val intent = Intent(action)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return matches.mapTo(HashSet()) { it.activityInfo.packageName }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        private val ALWAYS_PROTECTED = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.shell",
            "com.android.server.telecom",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.se",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.providers.contacts",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.providers.calendar",
            "com.google.android.ext.services",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.sec.android.app.samsungapps",
            "com.samsung.android.lool",
            "com.samsung.android.sm.devicesecurity",
            SHIZUKU_PACKAGE,
        )

        private val KNOWN_BACKGROUND_APPS = setOf(
            // Messaging and calls
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", "org.thoughtcrime.securesms", "im.vector.app", "com.facebook.orca",
            "com.discord", "com.Slack", "com.microsoft.teams", "com.viber.voip", "jp.naver.line.android",
            "com.tencent.mm", "com.skype.raider", "us.zoom.videomeetings", "com.google.android.apps.tachyon",
            "com.google.android.apps.messaging", "com.google.android.apps.dynamite", "chat.simplex.app",
            "com.wire", "ch.threema.app", "com.bip.bipmessenger", "com.turkcell.bip",
            // Mail and calendars
            "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android", "com.fsck.k9",
            "net.thunderbird.android", "com.google.android.calendar", "me.proton.android.calendar",
            // Music, podcasts, navigation
            "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app",
            "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android", "au.com.shiftyjelly.pocketcasts",
            "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "net.osmand", "net.osmand.plus",
            "com.google.android.projection.gearhead",
            // Authenticators, passwords, VPNs
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
            "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "proton.android.pass",
            "com.wireguard.android", "net.openvpn.openvpn", "de.blinkt.openvpn", "ch.protonvpn.android",
            "com.nordvpn.android", "com.expressvpn.vpn", "com.cloudflare.onedotonedotonedotone",
            // Wearables, health, smart home, phone link
            "com.samsung.android.app.watchmanager", "com.samsung.android.waterplugin", "com.samsung.android.geargplugin",
            "com.google.android.apps.wear.companion", "com.google.android.wearable.app", "com.fitbit.FitbitMobile",
            "com.garmin.android.apps.connectmobile", "com.mi.health", "com.xiaomi.wearable", "com.huawei.health",
            "com.samsung.android.oneconnect", "com.google.android.apps.chromecast.app", "com.microsoft.appmanager",
            // Clocks and alarms
            "com.google.android.deskclock", "com.sec.android.app.clockpackage",
        )
    }
}
