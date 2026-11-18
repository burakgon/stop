package io.github.burakgon.stop.ui.shortcut

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.burakgon.stop.R
import io.github.burakgon.stop.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible entry point of a freeze shortcut. Toggles one app between frozen and usable, repaints
 * the pinned icon and gets out of the way: no UI, just a toast.
 */
class FreezeShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent?.getStringExtra(FreezeShortcuts.EXTRA_PACKAGE)
        if (packageName == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val app = applicationContext
            val pm = app.packageManager
            val info = withContext(Dispatchers.IO) {
                runCatching { pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA) }.getOrNull()
            }
            // Disabled and suspended both count: suspend keeps the icon, disable removes it.
            val frozenNow = info != null &&
                (!info.enabled || info.flags and ApplicationInfo.FLAG_SUSPENDED != 0)
            val target = !frozenNow
            val label = withContext(Dispatchers.IO) {
                info?.loadLabel(pm)?.toString()?.ifBlank { null } ?: packageName
            }
            // The daemon may still be waking up with Shizuku; wait a little before giving up.
            val daemon = app.graph.daemon
            if (daemon.awaitService(4_000) == null) {
                toast(getString(R.string.shortcut_need_shizuku))
                finish()
                return@launch
            }
            val done = daemon.call { it.setFrozen(packageName, target) } ?: false
            if (done) {
                FreezeShortcuts.update(app, packageName, label, target)
                // The app list caches ApplicationInfo, so repaint it before the UI comes back.
                withContext(Dispatchers.IO) { runCatching { app.graph.apps.refresh() } }
                toast(getString(if (target) R.string.shortcut_frozen else R.string.shortcut_thawed, label))
            } else {
                toast(getString(R.string.shortcut_failed, label))
            }
            finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
