package io.github.burakgon.stop.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.github.burakgon.stop.BuildConfig
import io.github.burakgon.stop.R
import io.github.burakgon.stop.core.DaemonClient
import io.github.burakgon.stop.core.ShizukuMonitor
import io.github.burakgon.stop.model.GracePeriods
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.model.ThemeMode
import io.github.burakgon.stop.ui.components.openShizuku
import io.github.burakgon.stop.ui.durationLabel
import io.github.burakgon.stop.ui.graphViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val viewModel = graphViewModel { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
    val daemon by viewModel.daemon.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    // Re-read when coming back from the system's notification settings.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val notificationsOn = remember(lifecycle) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val historyCleared = stringResource(R.string.settings_clear_history_done)
    val restrictionsUndone = stringResource(R.string.settings_reset_done)
    val s = settings ?: return

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = contentPadding.calculateBottomPadding())) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item { Header(stringResource(R.string.settings_section_protection)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_protection),
                    summary = stringResource(if (s.protectionEnabled) R.string.settings_protection_on else R.string.settings_protection_off),
                    checked = s.protectionEnabled,
                    onCheckedChange = { on -> viewModel.update { it.copy(protectionEnabled = on) } },
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_grace),
                    summary = if (s.graceSeconds == 0) {
                        stringResource(R.string.settings_grace_summary_none)
                    } else {
                        stringResource(R.string.settings_grace_summary, durationLabel(s.graceSeconds))
                    },
                    onClick = { dialog = Dialog.GRACE },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_ongoing),
                    summary = stringResource(R.string.settings_ongoing_summary),
                    checked = s.allowOngoing,
                    onCheckedChange = { on -> viewModel.update { it.copy(allowOngoing = on) } },
                )
            }

            item { Header(stringResource(R.string.settings_section_new_apps)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_ask),
                    summary = stringResource(R.string.settings_ask_summary),
                    checked = s.askOnInstall,
                    onCheckedChange = { on -> viewModel.update { it.copy(askOnInstall = on) } },
                )
            }
            if (s.askOnInstall && !notificationsOn) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { openNotificationSettings(context) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(stringResource(R.string.settings_notifications_off), color = MaterialTheme.colorScheme.error) },
                    )
                }
            }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_new_default),
                    summary = stringResource(
                        if (s.newAppMode == RuleMode.BLOCK) R.string.settings_new_default_block else R.string.settings_new_default_allow
                    ),
                    onClick = { dialog = Dialog.NEW_APPS },
                )
            }

            item { Header(stringResource(R.string.settings_section_extra)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_extra),
                    summary = stringResource(R.string.settings_extra_summary),
                    checked = s.extraRestrictions,
                    onCheckedChange = { on -> viewModel.update { it.copy(extraRestrictions = on) } },
                )
            }

            item { Header(stringResource(R.string.settings_section_appearance)) }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_theme),
                    summary = stringResource(themeLabel(s.themeMode)),
                    onClick = { dialog = Dialog.THEME },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        summary = stringResource(R.string.settings_dynamic_color_summary),
                        checked = s.dynamicColor,
                        onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } },
                    )
                }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.menu_show_system),
                    summary = null,
                    checked = s.showSystemApps,
                    onCheckedChange = { on -> viewModel.update { it.copy(showSystemApps = on) } },
                )
            }

            item { Header(stringResource(R.string.settings_section_shizuku)) }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_shizuku_status),
                    summary = shizukuSummary(shizuku, viewModel.shizukuVersion),
                    onClick = {
                        if (shizuku == ShizukuMonitor.Status.NeedsPermission) viewModel.requestShizukuPermission() else openShizuku(context)
                    },
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_service),
                    summary = when (val d = daemon) {
                        is DaemonClient.State.Connected -> stringResource(
                            R.string.settings_service_running,
                            DateUtils.getRelativeTimeSpanString(d.info.startedAt).toString(),
                        )
                        is DaemonClient.State.Failed -> d.message
                        else -> stringResource(R.string.settings_service_not_running)
                    },
                    onClick = viewModel::refreshDaemon,
                )
            }

            item { Header(stringResource(R.string.settings_section_maintenance)) }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_reset),
                    summary = stringResource(R.string.settings_reset_summary),
                    onClick = { dialog = Dialog.RESET },
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_clear_history),
                    summary = null,
                    onClick = { viewModel.clearHistory { scope.launch { snackbar.showSnackbar(historyCleared) } } },
                )
            }

            item { Header(stringResource(R.string.settings_section_about)) }
            item { SettingRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME, onClick = null) }
            item { SettingRow(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_summary), onClick = null) }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_source),
                    summary = stringResource(R.string.settings_source_summary),
                    onClick = { openUrl(context, SOURCE_URL) },
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_license),
                    summary = stringResource(R.string.settings_license_summary),
                    onClick = { openUrl(context, "$SOURCE_URL/blob/main/LICENSE") },
                )
            }
        }
    }

    when (dialog) {
        Dialog.GRACE -> ChoiceDialog(
            title = stringResource(R.string.settings_grace),
            message = stringResource(R.string.settings_grace_dialog_body),
            options = GracePeriods.options,
            selected = s.graceSeconds,
            label = { durationLabel(it) },
            onSelect = { seconds -> viewModel.update { it.copy(graceSeconds = seconds) } },
            onDismiss = { dialog = null },
        )
        Dialog.NEW_APPS -> ChoiceDialog(
            title = stringResource(R.string.settings_new_default),
            message = null,
            options = listOf(RuleMode.BLOCK, RuleMode.ALLOW),
            selected = s.newAppMode,
            label = { stringResource(if (it == RuleMode.BLOCK) R.string.settings_new_default_block else R.string.settings_new_default_allow) },
            onSelect = viewModel::setNewAppMode,
            onDismiss = { dialog = null },
        )
        Dialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            message = null,
            options = ThemeMode.entries,
            selected = s.themeMode,
            label = { stringResource(themeLabel(it)) },
            onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) } },
            onDismiss = { dialog = null },
        )
        Dialog.RESET -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    viewModel.undoAll { scope.launch { snackbar.showSnackbar(restrictionsUndone) } }
                }) { Text(stringResource(R.string.settings_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }
}

private enum class Dialog { GRACE, NEW_APPS, THEME, RESET }

private const val SOURCE_URL = "https://github.com/burakgon/stop"

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, summary: String?, onClick: (() -> Unit)?) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
    )
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    message: String?,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                }
                for (option in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton) {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun shizukuSummary(status: ShizukuMonitor.Status, version: String?): String {
    val text = when (status) {
        is ShizukuMonitor.Status.Ready -> stringResource(if (status.isRoot) R.string.shizuku_connected_root else R.string.shizuku_connected_adb)
        ShizukuMonitor.Status.NotRunning -> stringResource(R.string.status_not_running_title)
        ShizukuMonitor.Status.NotInstalled -> stringResource(R.string.status_not_installed_title)
        ShizukuMonitor.Status.NeedsPermission -> stringResource(R.string.status_permission_body)
        ShizukuMonitor.Status.PermissionDenied -> stringResource(R.string.status_permission_denied_body)
        ShizukuMonitor.Status.Outdated -> stringResource(R.string.status_outdated_body)
    }
    return if (version != null) "$text · Shizuku $version" else text
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
    }
}
