package io.github.burakgon.stop.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.github.burakgon.stop.R
import io.github.burakgon.stop.core.ShizukuMonitor
import io.github.burakgon.stop.ui.apps.Badge
import io.github.burakgon.stop.ui.components.AppIcon
import io.github.burakgon.stop.ui.components.StopEmblem
import io.github.burakgon.stop.ui.components.getShizuku
import io.github.burakgon.stop.ui.components.openShizuku
import io.github.burakgon.stop.ui.graphViewModel
import io.github.burakgon.stop.ui.onboarding.OnboardingViewModel.Step

@Composable
fun OnboardingScreen() {
    val viewModel = graphViewModel { OnboardingViewModel(it) }
    val step by viewModel.step.collectAsStateWithLifecycle()
    BackHandler(enabled = step != Step.WELCOME) { viewModel.back() }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            slideInHorizontally { if (forward) it else -it } togetherWith slideOutHorizontally { if (forward) -it else it }
        },
        label = "onboarding",
    ) { current ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            when (current) {
                Step.WELCOME -> WelcomeStep(onNext = viewModel::next)
                Step.SHIZUKU -> ShizukuStep(viewModel)
                Step.NOTIFICATIONS -> NotificationsStep(viewModel)
                Step.CHOOSE -> ChooseStep(viewModel)
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepLayout(
        bottom = {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.action_get_started), style = MaterialTheme.typography.titleMedium)
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        StopEmblem(
            color = MaterialTheme.colorScheme.primary,
            barColor = MaterialTheme.colorScheme.onPrimary,
            active = true,
            modifier = Modifier.size(132.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium.copy(lineBreak = LineBreak.Heading),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))
        Feature(Icons.Rounded.Bedtime, R.string.onboarding_feature_sleep_title, R.string.onboarding_feature_sleep_body)
        Feature(Icons.Rounded.Timer, R.string.onboarding_feature_leave_title, R.string.onboarding_feature_leave_body)
        Feature(Icons.Rounded.SystemUpdate, R.string.onboarding_feature_updates_title, R.string.onboarding_feature_updates_body)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, body: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShizukuStep(viewModel: OnboardingViewModel) {
    val status by viewModel.shizuku.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) viewModel.refreshShizuku() }
    val ready = status is ShizukuMonitor.Status.Ready

    StepLayout(
        progress = 1 to viewModel.stepCount,
        bottom = {
            Button(onClick = viewModel::next, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.titleMedium)
            }
            if (!ready) {
                TextButton(onClick = viewModel::next, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_skip))
                }
            }
        },
    ) {
        StepTitle(R.string.onboarding_shizuku_title, R.string.onboarding_shizuku_body)
        Spacer(Modifier.height(24.dp))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ready) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                when (status) {
                                    is ShizukuMonitor.Status.Ready -> R.string.onboarding_shizuku_ready
                                    ShizukuMonitor.Status.NotInstalled -> R.string.status_not_installed_title
                                    ShizukuMonitor.Status.NotRunning -> R.string.status_not_running_title
                                    ShizukuMonitor.Status.Outdated -> R.string.status_outdated_title
                                    else -> R.string.status_permission_title
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                when (val s = status) {
                                    is ShizukuMonitor.Status.Ready -> if (s.isRoot) R.string.shizuku_connected_root else R.string.shizuku_connected_adb
                                    ShizukuMonitor.Status.NotInstalled -> R.string.status_not_installed_body
                                    ShizukuMonitor.Status.NotRunning -> R.string.onboarding_shizuku_how
                                    ShizukuMonitor.Status.Outdated -> R.string.status_outdated_body
                                    ShizukuMonitor.Status.PermissionDenied -> R.string.status_permission_denied_body
                                    ShizukuMonitor.Status.NeedsPermission -> R.string.status_permission_body
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!ready) {
                    Spacer(Modifier.height(16.dp))
                    when (status) {
                        ShizukuMonitor.Status.NotInstalled, ShizukuMonitor.Status.Outdated ->
                            Button(onClick = { getShizuku(context) }) { Text(stringResource(R.string.action_get_shizuku)) }
                        ShizukuMonitor.Status.NeedsPermission ->
                            Button(onClick = viewModel::requestShizukuPermission) { Text(stringResource(R.string.action_grant_access)) }
                        else -> Button(onClick = { openShizuku(context) }) { Text(stringResource(R.string.action_open_shizuku)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_shizuku_uses), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Use(Icons.Rounded.Visibility, R.string.onboarding_shizuku_use_running)
        Use(Icons.Rounded.Block, R.string.onboarding_shizuku_use_stop)
        Use(Icons.Rounded.InstallMobile, R.string.onboarding_shizuku_use_installs)
        if (!ready) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { helpOpen = !helpOpen }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.onboarding_shizuku_how), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (helpOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(helpOpen) {
                Text(
                    stringResource(R.string.onboarding_shizuku_steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotificationsStep(viewModel: OnboardingViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.next() }
    StepLayout(
        progress = 2 to viewModel.stepCount,
        bottom = {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.next()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.action_allow_notifications), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = viewModel::next, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_skip)) }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        StepTitle(R.string.onboarding_notifications_title, R.string.onboarding_notifications_body, center = true)
        Spacer(Modifier.height(32.dp))
        NotificationPreview()
    }
}

/** A drawing of the notification Stop posts for a new app, so the user knows what to expect. */
@Composable
private fun NotificationPreview() {
    val app = stringResource(R.string.onboarding_notification_example_app)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StopEmblem(
                    color = MaterialTheme.colorScheme.primary,
                    barColor = MaterialTheme.colorScheme.onPrimary,
                    active = false,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.app_name) + " · " + stringResource(R.string.onboarding_notification_example_time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.new_app_notification_title, app), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.new_app_notification_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.action_stop_in_background), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.action_let_it_run), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Use(icon: ImageVector, text: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChooseStep(viewModel: OnboardingViewModel) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val list = candidates
    val shown = remember(list, query) {
        list.orEmpty().filter { query.isBlank() || it.app.label.contains(query.trim(), ignoreCase = true) }
    }
    val blockedCount = list.orEmpty().count { it.app.packageName !in allowed }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(16.dp))
            StepProgress(viewModel.stepCount, viewModel.stepCount)
            Spacer(Modifier.height(24.dp))
            StepTitle(R.string.onboarding_choose_title, R.string.onboarding_choose_body)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val searching = query.isNotBlank()
                AssistChip(
                    onClick = { viewModel.setAll(shown.map { it.app.packageName }, allow = true) },
                    label = { Text(stringResource(if (searching) R.string.onboarding_allow_shown else R.string.onboarding_allow_all)) },
                    leadingIcon = { Icon(Icons.Rounded.DoneAll, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                    enabled = shown.isNotEmpty(),
                )
                AssistChip(
                    onClick = { viewModel.setAll(shown.map { it.app.packageName }, allow = false) },
                    label = { Text(stringResource(if (searching) R.string.onboarding_block_shown else R.string.onboarding_block_all)) },
                    leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                    enabled = shown.isNotEmpty(),
                )
            }
        }
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(shown, key = { it.app.packageName }) { candidate ->
                    val checked = candidate.app.packageName in allowed
                    ListItem(
                        modifier = Modifier.clickable { viewModel.toggle(candidate.app.packageName) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { AppIcon(candidate.app.packageName, size = 40.dp) },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(candidate.app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (candidate.suggested) {
                                    Spacer(Modifier.width(8.dp))
                                    Badge(stringResource(R.string.onboarding_suggested))
                                }
                            }
                        },
                        supportingContent = {
                            Text(stringResource(if (checked) R.string.app_status_allowed else R.string.app_status_blocked))
                        },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = { viewModel.toggle(candidate.app.packageName) }) },
                    )
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    pluralStringResource(R.plurals.onboarding_summary, blockedCount, blockedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::finish,
                    enabled = list != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(stringResource(R.string.action_done), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun StepLayout(
    progress: Pair<Int, Int>? = null,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            if (progress != null) {
                StepProgress(progress.first, progress.second)
                Spacer(Modifier.height(24.dp))
            }
            content()
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = bottom)
    }
}

@Composable
private fun StepProgress(step: Int, count: Int) {
    Column {
        Text(
            stringResource(R.string.onboarding_step, step, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { step.toFloat() / count },
            modifier = Modifier.fillMaxWidth(),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun StepTitle(title: Int, body: Int, center: Boolean = false) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineMedium.copy(lineBreak = LineBreak.Heading),
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
}
