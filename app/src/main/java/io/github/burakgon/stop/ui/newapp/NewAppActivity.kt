package io.github.burakgon.stop.ui.newapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.burakgon.stop.R
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.model.ThemeMode
import io.github.burakgon.stop.ui.components.AppIcon
import io.github.burakgon.stop.ui.theme.StopTheme
import io.github.burakgon.stop.ui.theme.isDarkTheme
import kotlinx.coroutines.launch

/** Opened from the new-app notification: a focused card to decide about one app. */
class NewAppActivity : ComponentActivity() {
    /** The app being decided on; replaced when another notification is tapped. */
    private var target by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val graph = graph

        setContent {
            val settings by graph.settings.settings.collectAsState(initial = null)
            val current = target ?: return@setContent
            val label = remember(current) { labelOf(current) }
            StopTheme(
                darkTheme = isDarkTheme(settings?.themeMode ?: ThemeMode.SYSTEM),
                dynamicColor = settings?.dynamicColor ?: false,
            ) {
                NewAppCard(
                    packageName = current,
                    label = label,
                    onDecide = { mode ->
                        lifecycleScope.launch {
                            graph.decide(listOf(current), mode)
                            finish()
                        }
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PACKAGE)?.let { target = it }
    }

    private fun labelOf(packageName: String): String? =
        graph.apps.apps.value?.get(packageName)?.label
            ?: runCatching { packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString() }.getOrNull()

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, NewAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PACKAGE, packageName)
    }
}

@Composable
private fun NewAppCard(
    packageName: String,
    label: String?,
    onDecide: (RuleMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                // Swallow taps inside the card so they don't dismiss it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (label == null) {
                    Text(stringResource(R.string.new_app_missing), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                    return@Column
                }
                AppIcon(packageName, size = 72.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.new_app_title, label),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.new_app_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onDecide(RuleMode.BLOCK) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.action_stop_in_background))
                }
                Text(
                    stringResource(R.string.detail_mode_block_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onDecide(RuleMode.ALLOW) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.action_let_it_run))
                }
                Text(
                    stringResource(R.string.detail_mode_allow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_decide_later)) }
            }
        }
    }
}
