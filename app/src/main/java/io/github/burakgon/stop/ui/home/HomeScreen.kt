package io.github.burakgon.stop.ui.home

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.burakgon.stop.R
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.ui.MainViewModel
import io.github.burakgon.stop.ui.components.AppIcon
import io.github.burakgon.stop.ui.components.GroupCard
import io.github.burakgon.stop.ui.components.GroupDivider
import io.github.burakgon.stop.ui.components.SectionHeader
import io.github.burakgon.stop.ui.components.StatusCard
import io.github.burakgon.stop.ui.countdownLabel
import io.github.burakgon.stop.ui.graphViewModel
import io.github.burakgon.stop.ui.reasonLabel
import io.github.burakgon.stop.ui.relativeTime
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    main: MainViewModel,
    contentPadding: PaddingValues,
    onOpenNewApps: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val viewModel = graphViewModel { HomeViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val protection by main.protection.collectAsStateWithLifecycle()

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBar + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        item(key = "status") {
            StatusCard(
                protection = protection,
                blockedCount = state.blockedCount,
                onPause = { main.setProtectionEnabled(false) },
                onResume = { main.setProtectionEnabled(true) },
                onGrantAccess = main::requestShizukuPermission,
                onRetry = main::refresh,
            )
        }
        item(key = "stats") {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(state.stopsToday, stringResource(R.string.stat_stops_today), Modifier.weight(1f).fillMaxHeight())
                StatTile(state.wakeUpsToday, stringResource(R.string.stat_wakeups_today), Modifier.weight(1f).fillMaxHeight())
                StatTile(state.blockedCount, stringResource(R.string.stat_blocked_apps), Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (state.undecided.isNotEmpty()) {
            item(key = "undecided-header") {
                SectionHeader(
                    stringResource(R.string.home_needs_decision),
                    action = if (state.undecided.size > MAX_UNDECIDED) stringResource(R.string.action_see_all) else null,
                    onAction = onOpenNewApps,
                )
            }
            item(key = "undecided") {
                GroupCard(Modifier.animateContentSize()) {
                    state.undecided.take(MAX_UNDECIDED).forEachIndexed { index, app ->
                        if (index > 0) GroupDivider()
                        UndecidedRow(
                            packageName = app.packageName,
                            label = app.label,
                            onBlock = { viewModel.decide(app.packageName, RuleMode.BLOCK) },
                            onAllow = { viewModel.decide(app.packageName, RuleMode.ALLOW) },
                        )
                    }
                }
            }
        }
        if (protection.isActive || state.running.isNotEmpty()) {
            item(key = "running-header") {
                SectionHeader(
                    stringResource(R.string.home_running),
                    action = if (state.running.any { !it.inUse }) stringResource(R.string.action_stop_all) else null,
                    onAction = viewModel::stopAll,
                )
            }
            item(key = "running") {
                GroupCard(Modifier.animateContentSize()) {
                    if (state.running.isEmpty()) {
                        EmptyRow(stringResource(R.string.home_running_empty))
                    } else {
                        val now by produceState(SystemClock.elapsedRealtime()) {
                            while (true) {
                                delay(1_000)
                                value = SystemClock.elapsedRealtime()
                            }
                        }
                        state.running.forEachIndexed { index, running ->
                            if (index > 0) GroupDivider()
                            RunningRow(running, now, onStop = { viewModel.stopNow(running.packageName) })
                        }
                    }
                }
            }
        }
        item(key = "recent-header") {
            SectionHeader(
                stringResource(R.string.home_recent),
                action = if (state.recent.isNotEmpty()) stringResource(R.string.action_see_all) else null,
                onAction = onOpenActivity,
            )
        }
        item(key = "recent") {
            GroupCard(Modifier.animateContentSize()) {
                if (state.recent.isEmpty()) {
                    EmptyRow(stringResource(R.string.home_recent_empty), Icons.Rounded.Bedtime)
                } else {
                    val now = System.currentTimeMillis()
                    state.recent.forEachIndexed { index, recent ->
                        if (index > 0) GroupDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            leadingContent = { AppIcon(recent.event.packageName, size = 40.dp) },
                            headlineContent = { Text(recent.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(reasonLabel(recent.event.reason)) },
                            trailingContent = {
                                Text(
                                    relativeTime(recent.event.time, now),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "stat",
            ) { number ->
                Text(number.toString(), style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun UndecidedRow(packageName: String, label: String, onBlock: () -> Unit, onAllow: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.home_new_app),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onBlock) { Text(stringResource(R.string.action_stop_in_background)) }
            OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.action_let_it_run)) }
        }
    }
}

@Composable
private fun RunningRow(running: HomeViewModel.Running, now: Long, onStop: () -> Unit) {
    val status = when {
        running.inUse -> stringResource(R.string.running_in_use)
        running.stopsAt != null && running.stopsAt > now -> stringResource(R.string.running_stops_in, countdownLabel(running.stopsAt - now))
        running.stopsAt != null -> stringResource(R.string.running_stopping)
        else -> stringResource(R.string.running_in_use)
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { AppIcon(running.packageName, size = 40.dp) },
        headlineContent = {
            Text(running.app?.label ?: running.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(status, color = if (running.inUse) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            IconButton(onClick = onStop) {
                Icon(Icons.Rounded.Block, contentDescription = stringResource(R.string.action_stop_now))
            }
        },
    )
}

@Composable
private fun EmptyRow(text: String, icon: ImageVector = Icons.Rounded.CheckCircle) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val MAX_UNDECIDED = 3
