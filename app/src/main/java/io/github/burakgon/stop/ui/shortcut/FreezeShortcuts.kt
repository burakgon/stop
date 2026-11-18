package io.github.burakgon.stop.ui.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.burakgon.stop.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desktop shortcuts that toggle a single app's permanent freeze. The pinned icon carries the app's
 * own icon plus a state badge, because a frozen app disappears from the launcher: the shortcut is
 * the way back.
 */
object FreezeShortcuts {
    const val ACTION_TOGGLE_FREEZE = "io.github.burakgon.stop.action.TOGGLE_FREEZE"
    const val EXTRA_PACKAGE = "io.github.burakgon.stop.extra.PACKAGE"

    private const val TAG = "StopShortcuts"

    fun shortcutId(packageName: String) = "freeze:$packageName"

    /** True when the launcher can pin shortcuts at all. */
    fun isPinSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin the shortcut; the user still has to confirm. */
    fun pin(context: Context, packageName: String, label: String, frozen: Boolean): Boolean {
        if (!isPinSupported(context)) return false
        return try {
            ShortcutManagerCompat.requestPinShortcut(context, build(context, packageName, label, frozen), null)
        } catch (e: Exception) {
            Log.w(TAG, "could not pin shortcut for $packageName", e)
            false
        }
    }

    /** Repaints an already pinned shortcut after the freeze state changed. */
    fun update(context: Context, packageName: String, label: String, frozen: Boolean) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val pinned = runCatching { manager.pinnedShortcuts }.getOrNull().orEmpty()
        val id = shortcutId(packageName)
        if (pinned.none { it.id == id }) return
        try {
            manager.updateShortcuts(listOf(build(context, packageName, label, frozen).toShortcutInfo()))
        } catch (e: Exception) {
            Log.w(TAG, "could not update shortcut for $packageName", e)
        }
    }

    private fun build(context: Context, packageName: String, label: String, frozen: Boolean): ShortcutInfoCompat {
        val intent = Intent(ACTION_TOGGLE_FREEZE)
            .setComponent(ComponentName(context, FreezeShortcutActivity::class.java))
            .putExtra(EXTRA_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ShortcutInfoCompat.Builder(context, shortcutId(packageName))
            .setShortLabel(label)
            .setLongLabel(context.getString(if (frozen) R.string.shortcut_long_frozen else R.string.shortcut_long_active, label))
            .setIcon(icon(context, packageName, frozen))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    /**
     * The app's own icon with a badge in the lower right: a snowflake when frozen, a check when it
     * can still run. Everything is kept inside the adaptive icon's safe zone so no launcher mask
     * cuts the badge off.
     */
    private fun icon(context: Context, packageName: String, frozen: Boolean): IconCompat {
        val size = 288
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base: Drawable? = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (base != null) {
            base.setBounds(0, 0, size, size)
            base.draw(canvas)
        } else {
            canvas.drawColor(0xFF3A3A3A.toInt())
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size * 0.70f
        val cy = size * 0.70f
        val radius = size * 0.145f

        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius * 1.24f, paint)
        paint.color = if (frozen) 0xFF2F6FED.toInt() else 0xFF2E9E5B.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.26f
        paint.strokeCap = Paint.Cap.ROUND
        if (frozen) {
            for (i in 0 until 3) {
                val angle = Math.toRadians(60.0 * i)
                val dx = (cos(angle) * radius * 0.6).toFloat()
                val dy = (sin(angle) * radius * 0.6).toFloat()
                canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
            }
        } else {
            val path = Path().apply {
                moveTo(cx - radius * 0.42f, cy + radius * 0.02f)
                lineTo(cx - radius * 0.08f, cy + radius * 0.36f)
                lineTo(cx + radius * 0.46f, cy - radius * 0.34f)
            }
            canvas.drawPath(path, paint)
        }
        return IconCompat.createWithBitmap(bitmap)
    }
}
