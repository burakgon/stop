package io.github.burakgon.stop.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.burakgon.stop.R
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick Settings tile that stops every blocked app running in the background right now. */
class StopTileService : TileService() {
    private var listening: Job? = null

    override fun onStartListening() {
        val daemon = graph.daemon
        daemon.connect()
        // The process may have just been started for the tile, so follow the connection as it comes up.
        listening = graph.scope.launch(Dispatchers.Main) {
            daemon.service.collect { service -> render(ready = service != null) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val daemon = graph.daemon
        graph.scope.launch {
            if (daemon.awaitService(CONNECT_TIMEOUT_MS) == null) {
                withContext(Dispatchers.Main) { openApp() }
                return@launch
            }
            val stopped = daemon.call { it.stopAllBlocked() }
            withContext(Dispatchers.Main) {
                val text = when (stopped) {
                    null -> getString(R.string.stop_all_unavailable)
                    0 -> getString(R.string.stop_all_none)
                    else -> resources.getQuantityString(R.plurals.stop_all_result, stopped, stopped)
                }
                Toast.makeText(this@StopTileService, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render(ready: Boolean) {
        val tile = qsTile ?: return
        // Never STATE_UNAVAILABLE: the system ignores taps on it, and a tap is what opens Stop.
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (ready) R.string.tile_subtitle_ready else R.string.tile_subtitle_paused)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = MainActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000L
    }
}
