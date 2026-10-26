package io.github.burakgon.stop.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.burakgon.stop.R
import io.github.burakgon.stop.data.AppIcons
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.model.InstalledApp
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.receiver.NotificationActionReceiver
import io.github.burakgon.stop.ui.MainActivity
import io.github.burakgon.stop.ui.newapp.NewAppActivity

class Notifications(private val context: Context, private val icons: AppIcons) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_NEW_APPS,
                    context.getString(R.string.channel_new_apps),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_new_apps_description) },
                NotificationChannel(
                    CHANNEL_STATUS,
                    context.getString(R.string.channel_status),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_status_description)
                    setShowBadge(false)
                },
            )
        )
    }

    fun canPost(): Boolean = manager.areNotificationsEnabled()

    fun showNewApp(app: InstalledApp) {
        val id = newAppId(app.packageName)
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_APPS)
            .setSmallIcon(R.drawable.ic_stat_stop)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setLargeIcon(icons.loadBlocking(app.packageName, largeIconSize()))
            .setContentTitle(context.getString(R.string.new_app_notification_title, app.label))
            .setContentText(context.getString(R.string.new_app_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.new_app_notification_long)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, id, NewAppActivity.intent(context, app.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(0, context.getString(R.string.action_stop_in_background), decision(app.packageName, RuleMode.BLOCK, id))
            .addAction(0, context.getString(R.string.action_let_it_run), decision(app.packageName, RuleMode.ALLOW, id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(GROUP_NEW_APPS)
            .build()
        post(id, notification)
    }

    fun showNewAppsSummary(count: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_APPS)
            .setSmallIcon(R.drawable.ic_stat_stop)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.resources.getQuantityString(R.plurals.new_apps_summary_title, count, count))
            .setContentText(context.getString(R.string.new_apps_summary_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, ID_NEW_APPS_SUMMARY, MainActivity.newAppsIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(ID_NEW_APPS_SUMMARY, notification)
    }

    fun cancelNewApp(packageName: String) {
        manager.cancel(newAppId(packageName))
    }

    fun cancelNewAppsSummary() = manager.cancel(ID_NEW_APPS_SUMMARY)

    fun showPaused() {
        val open = context.packageManager.getLaunchIntentForPackage(SystemRoles.SHIZUKU_PACKAGE)
            ?: MainActivity.intent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_stop)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.paused_notification_title))
            .setContentText(context.getString(R.string.paused_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.paused_notification_text)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, ID_PAUSED, MainActivity.intent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .addAction(
                0,
                context.getString(R.string.action_open_shizuku),
                PendingIntent.getActivity(
                    context, ID_PAUSED + 1, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        post(ID_PAUSED, notification)
    }

    fun cancelPaused() = manager.cancel(ID_PAUSED)

    private fun decision(packageName: String, mode: RuleMode, notificationId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId * 2 + mode.ordinal,
            NotificationActionReceiver.intent(context, packageName, mode),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) manager.notify(id, notification)
    }

    private fun largeIconSize() = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)

    private fun newAppId(packageName: String) = ID_NEW_APP_BASE + (packageName.hashCode() and 0xFFFFF)

    companion object {
        const val CHANNEL_NEW_APPS = "new_apps"
        const val CHANNEL_STATUS = "status"
        private const val GROUP_NEW_APPS = "new_apps"
        private const val ID_PAUSED = 10
        private const val ID_NEW_APPS_SUMMARY = 20
        private const val ID_NEW_APP_BASE = 1_000
    }
}
