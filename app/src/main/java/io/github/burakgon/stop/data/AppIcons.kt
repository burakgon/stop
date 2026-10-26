package io.github.burakgon.stop.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads app icons off the main thread and keeps recently used ones in memory. */
class AppIcons(context: Context) {
    private val pm = context.packageManager
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    fun cached(packageName: String, sizePx: Int): Bitmap? = cache.get(key(packageName, sizePx))

    suspend fun load(packageName: String, sizePx: Int): Bitmap? {
        cached(packageName, sizePx)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).also { cache.put(key(packageName, sizePx), it) }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun loadBlocking(packageName: String, sizePx: Int): Bitmap? = cached(packageName, sizePx) ?: try {
        pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).also { cache.put(key(packageName, sizePx), it) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun key(packageName: String, sizePx: Int) = "$packageName@$sizePx"

    private companion object {
        const val CACHE_KB = 24 * 1024
    }
}
