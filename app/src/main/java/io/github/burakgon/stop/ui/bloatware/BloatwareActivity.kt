package io.github.burakgon.stop.ui.bloatware

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.burakgon.stop.R
import io.github.burakgon.stop.data.BloatwareCatalog
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.ui.theme.StopTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Batch freeze of preinstalled apps, driven by the tier list bundled with the app. */
class BloatwareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StopTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BloatwareScreen(onClose = ::finish)
                }
            }
        }
    }
}

private data class Row(
    val entry: BloatwareCatalog.Entry,
    val label: String,
    val frozen: Boolean,
)

@Composable
private fun BloatwareScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { context.applicationContext.graph }
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<Row>?>(null) }
    var tier by remember { mutableStateOf(BloatwareCatalog.Tier.A) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load(): List<Row> {
        val installed = graph.apps.get()
        return BloatwareCatalog.load(context).mapNotNull { entry ->
            val app = installed[entry.packageName] ?: return@mapNotNull null
            // A suspended app keeps its icon, so BOTH disable and suspend must show as frozen.
            Row(entry, app.label, frozen = !app.enabled)
        }
    }

    LaunchedEffect(Unit) {
        val loaded = load()
        rows = loaded
        selected = loaded.filter { it.entry.tier == BloatwareCatalog.Tier.A && !it.frozen }
            .mapTo(HashSet()) { it.entry.packageName }
    }

    fun toggle(packageName: String) {
        selected = if (packageName in selected) selected - packageName else selected + packageName
    }

    fun apply(freeze: Boolean) {
        val targets = selected.toList()
        if (targets.isEmpty()) return
        scope.launch {
            busy = true
            message = null
            val daemon = graph.daemon
            if (daemon.awaitService(4_000) == null) {
                busy = false
                message = context.getString(R.string.bloatware_need_shizuku)
                return@launch
            }
            var done = 0
            for (target in targets) {
                if (daemon.call { it.setFrozen(target, freeze) } == true) done++
            }
            withContext(Dispatchers.IO) { runCatching { graph.apps.refresh() } }
            rows = rows?.map { row ->
                if (row.entry.packageName in targets) row.copy(frozen = freeze) else row
            }
            selected = emptySet()
            busy = false
            message = context.getString(
                if (freeze) R.string.bloatware_done_frozen else R.string.bloatware_done_thawed,
                done,
            )
        }
    }

    val visible = rows?.filter { it.entry.tier == tier } ?: emptyList()
    val selectedRows = rows.orEmpty().filter { it.entry.packageName in selected }
    val canThaw = selectedRows.any { it.frozen }
    val canFreeze = selectedRows.any { !it.frozen }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bloatware_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.bloatware_close))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { apply(false) },
                            enabled = !busy && canThaw,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.bloatware_thaw_selected, selectedRows.count { it.frozen }))
                        }
                        Button(
                            onClick = { apply(true) },
                            enabled = !busy && canFreeze,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.bloatware_freeze_selected, selectedRows.count { !it.frozen }))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(R.string.bloatware_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in BloatwareCatalog.Tier.entries) {
                    val count = rows?.count { it.entry.tier == option } ?: 0
                    FilterChip(
                        selected = tier == option,
                        onClick = { tier = option },
                        label = { Text("${tierLabel(option)} $count") },
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            if (rows == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            } else if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.bloatware_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.entry.packageName }) { row ->
                        val selectable = row.entry.tier != BloatwareCatalog.Tier.C
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = row.entry.packageName in selected,
                                onCheckedChange = { toggle(row.entry.packageName) },
                                enabled = selectable && !busy,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (row.frozen) {
                                        stringResource(R.string.bloatware_frozen_note, row.entry.note)
                                    } else {
                                        row.entry.note
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (row.frozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = row.entry.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tierLabel(tier: BloatwareCatalog.Tier): String = stringResource(
    when (tier) {
        BloatwareCatalog.Tier.A -> R.string.bloatware_tier_a
        BloatwareCatalog.Tier.B -> R.string.bloatware_tier_b
        BloatwareCatalog.Tier.C -> R.string.bloatware_tier_c
    },
)
