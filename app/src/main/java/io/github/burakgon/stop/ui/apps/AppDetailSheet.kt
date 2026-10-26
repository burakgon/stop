package io.github.burakgon.stop.ui.apps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.burakgon.stop.R
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.graph
import io.github.burakgon.stop.model.GracePeriods
import io.github.burakgon.stop.ui.durationLabel
import io.github.burakgon.stop.ui.reasonLabel
import io.github.burakgon.stop.ui.relativeTime
import io.github.burakgon.stop.ui.startOfDay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDetailSheet(
    item: AppsViewModel.Item,
    defaultGraceSeconds: Int,
    viewModel: AppsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val packageName = item.app.packageName
    val history by remember(packageName) { viewModel.history(packageName) }.collectAsStateWithLifecycle(emptyList())
    val versionName = remember(packageName) { context.graph.apps.versionName(packageName) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.github.burakgon.stop.ui.components.AppIcon(packageName, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.app.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(packageName, versionName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            if (item.isProtected) {
                val reason = item.integration?.takeIf { it.keepsRunning }?.let { integrationText(it) }
                Notice(Icons.Rounded.Shield, reason ?: stringResource(R.string.detail_protected))
            } else {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = item.blocked,
                        onClick = { viewModel.setBlocked(packageName, true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.detail_mode_block), maxLines = 1) }
                    SegmentedButton(
                        selected = !item.blocked,
                        onClick = { viewModel.setBlocked(packageName, false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.detail_mode_allow), maxLines = 1) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (item.blocked) R.string.detail_mode_block_hint else R.string.detail_mode_allow_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.integration?.let { integration ->
                    Spacer(Modifier.height(16.dp))
                    Notice(Icons.Rounded.WarningAmber, integrationText(integration), warning = true)
                }
                if (item.blocked) {
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.detail_grace_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val current = item.rule?.graceSeconds
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = current == null,
                            onClick = { viewModel.setGrace(packageName, null) },
                            label = { Text(stringResource(R.string.detail_grace_default, durationLabel(defaultGraceSeconds))) },
                        )
                        for (seconds in GracePeriods.options) {
                            FilterChip(
                                selected = current == seconds,
                                onClick = { viewModel.setGrace(packageName, seconds) },
                                label = { Text(durationLabel(seconds)) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.detail_activity), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val now = System.currentTimeMillis()
            val today = startOfDay(now)
            val week = viewModel.weekStart()
            val todayCount = history.count { it.time >= today }
            val weekCount = history.count { it.time >= week }
            if (history.isEmpty()) {
                Text(
                    stringResource(R.string.detail_never_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (todayCount > 0) {
                    Text(pluralStringResource(R.plurals.detail_stops_today, todayCount, todayCount), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    pluralStringResource(R.plurals.detail_stops_week, weekCount, weekCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (event in history.take(5)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(reasonLabel(event.reason), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            relativeTime(event.time, now),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!item.isProtected) {
                    FilledTonalButton(
                        onClick = { viewModel.stopNow(packageName) },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_stop_now), maxLines = 1)
                    }
                }
                val launch = remember(packageName) { context.graph.apps.launchIntent(packageName) }
                if (launch != null) {
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(launch)
                            } catch (_: ActivityNotFoundException) {
                            }
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open), maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        )
                    },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_app_info), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, warning: Boolean = false) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun integrationText(integration: SystemRoles.Integration): String = stringResource(
    when (integration) {
        SystemRoles.Integration.ACCESSIBILITY -> R.string.detail_integration_accessibility
        SystemRoles.Integration.NOTIFICATION_LISTENER -> R.string.detail_integration_notification_listener
        SystemRoles.Integration.DEVICE_ADMIN -> R.string.detail_integration_device_admin
        SystemRoles.Integration.AUTOFILL -> R.string.detail_integration_autofill
        SystemRoles.Integration.ALARM_CLOCK -> R.string.detail_integration_alarm_clock
        SystemRoles.Integration.WALLPAPER -> R.string.detail_integration_wallpaper
    }
)
