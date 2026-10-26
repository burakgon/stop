package io.github.burakgon.stop.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.burakgon.stop.R
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.ui.Protection

/** The big card on top of Home: what protection is doing and, if needed, how to fix it. */
@Composable
fun StatusCard(
    protection: Protection,
    blockedCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onGrantAccess: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (protection) {
        is Protection.Active -> colors.primaryContainer to colors.onPrimaryContainer
        Protection.Starting, Protection.Paused -> colors.surfaceContainerHigh to colors.onSurface
        Protection.ShizukuNotRunning, is Protection.Failed -> colors.errorContainer to colors.onErrorContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val containerColor by animateColorAsState(container, label = "statusContainer")
    val contentColor by animateColorAsState(content, label = "statusContent")
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        AnimatedContent(
            targetState = protection,
            contentKey = { it.javaClass },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "status",
        ) { state ->
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StopEmblem(
                        color = if (state.isActive) colors.primary else contentColor.copy(alpha = 0.72f),
                        barColor = colors.onPrimary,
                        active = state.isActive,
                        modifier = Modifier.size(76.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title(state), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            body(state, blockedCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(Modifier.padding(top = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state) {
                        is Protection.Active -> ModePill(state.root, contentColor)
                        Protection.Paused -> Unit
                        Protection.Starting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
                        else -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    when (state) {
                        is Protection.Active -> FilledTonalButton(
                            onClick = onPause,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surface.copy(alpha = 0.6f),
                                contentColor = contentColor,
                            ),
                        ) { Text(stringResource(R.string.action_pause)) }
                        Protection.Paused -> Button(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
                        Protection.ShizukuNotRunning, Protection.PermissionDenied ->
                            Button(onClick = { openShizuku(context) }, colors = actionColors(state)) {
                                Text(stringResource(R.string.action_open_shizuku))
                            }
                        Protection.ShizukuNotInstalled, Protection.ShizukuOutdated ->
                            Button(onClick = { getShizuku(context) }) { Text(stringResource(R.string.action_get_shizuku)) }
                        Protection.NeedsPermission -> Button(onClick = onGrantAccess) { Text(stringResource(R.string.action_grant_access)) }
                        is Protection.Failed -> Button(onClick = onRetry, colors = actionColors(state)) { Text(stringResource(R.string.action_retry)) }
                        Protection.Starting -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun actionColors(state: Protection) = if (state is Protection.Failed || state == Protection.ShizukuNotRunning) {
    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
} else {
    ButtonDefaults.buttonColors()
}

@Composable
private fun ModePill(root: Boolean, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
    ) {
        Text(
            stringResource(if (root) R.string.mode_root else R.string.mode_adb),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun title(state: Protection): String = stringResource(
    when (state) {
        is Protection.Active -> R.string.status_active_title
        Protection.Starting -> R.string.status_starting_title
        Protection.Paused -> R.string.status_paused_title
        Protection.ShizukuNotRunning -> R.string.status_not_running_title
        Protection.ShizukuNotInstalled -> R.string.status_not_installed_title
        Protection.NeedsPermission, Protection.PermissionDenied -> R.string.status_permission_title
        Protection.ShizukuOutdated -> R.string.status_outdated_title
        is Protection.Failed -> R.string.status_error_title
    }
)

@Composable
private fun body(state: Protection, blockedCount: Int): String = when (state) {
    is Protection.Active -> if (blockedCount == 0) {
        stringResource(R.string.status_active_none)
    } else {
        pluralStringResource(R.plurals.status_active_body, blockedCount, blockedCount)
    }
    Protection.Starting -> stringResource(R.string.status_starting_body)
    Protection.Paused -> stringResource(R.string.status_paused_body)
    Protection.ShizukuNotRunning -> stringResource(R.string.status_not_running_body)
    Protection.ShizukuNotInstalled -> stringResource(R.string.status_not_installed_body)
    Protection.NeedsPermission -> stringResource(R.string.status_permission_body)
    Protection.PermissionDenied -> stringResource(R.string.status_permission_denied_body)
    Protection.ShizukuOutdated -> stringResource(R.string.status_outdated_body)
    is Protection.Failed -> state.message
}

fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(SystemRoles.SHIZUKU_PACKAGE)
    if (intent != null) context.startActivity(intent) else getShizuku(context)
}

fun getShizuku(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=${SystemRoles.SHIZUKU_PACKAGE}".toUri())
    val web = Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(web)
        } catch (_: ActivityNotFoundException) {
        }
    }
}
