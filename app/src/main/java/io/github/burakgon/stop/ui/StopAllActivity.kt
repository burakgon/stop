package io.github.burakgon.stop.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.burakgon.stop.R
import io.github.burakgon.stop.graph
import kotlinx.coroutines.launch

/** Target of the launcher shortcut: stops every blocked background app, then disappears. */
class StopAllActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val daemon = graph.daemon
            val stopped = if (daemon.awaitService(TIMEOUT_MS) != null) daemon.call { it.stopAllBlocked() } else null
            val text = when (stopped) {
                null -> getString(R.string.stop_all_unavailable)
                0 -> getString(R.string.stop_all_none)
                else -> resources.getQuantityString(R.plurals.stop_all_result, stopped, stopped)
            }
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
