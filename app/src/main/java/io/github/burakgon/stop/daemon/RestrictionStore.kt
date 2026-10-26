package io.github.burakgon.stop.daemon

import android.system.Os
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remembers which packages the daemon restricted beyond force-stopping (background app op and
 * standby bucket), so it can undo exactly those changes later, even after a restart.
 */
internal class RestrictionStore(private val file: File) {
    val background = HashSet<String>()
    val standby = HashSet<String>()

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.optJSONArray("background")?.let { a -> (0 until a.length()).mapTo(background) { a.getString(it) } }
                o.optJSONArray("standby")?.let { a -> (0 until a.length()).mapTo(standby) { a.getString(it) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${file.path}", e)
        }
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                JSONObject()
                    .put("background", JSONArray(background.sorted()))
                    .put("standby", JSONArray(standby.sorted()))
                    .toString()
            )
            if (!tmp.renameTo(file)) error("rename failed")
            // Keep the state usable when the daemon later runs as shell instead of root.
            if (android.os.Process.myUid() == 0) {
                file.parentFile?.let { Os.chown(it.path, SHELL_UID, SHELL_UID) }
                Os.chown(file.path, SHELL_UID, SHELL_UID)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write ${file.path}", e)
        }
    }

    private companion object {
        const val TAG = "StopDaemon"
        const val SHELL_UID = 2000
    }
}
