package io.github.burakgon.stop.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.burakgon.stop.R
import io.github.burakgon.stop.ui.activity.ActivityScreen
import io.github.burakgon.stop.ui.apps.AppsScreen
import io.github.burakgon.stop.ui.apps.AppsViewModel
import io.github.burakgon.stop.ui.home.HomeScreen
import io.github.burakgon.stop.ui.settings.SettingsScreen

enum class Tab(@StringRes val label: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Rounded.Home, Icons.Outlined.Home),
    APPS(R.string.nav_apps, Icons.Rounded.Apps, Icons.Outlined.Apps),
    ACTIVITY(R.string.nav_activity, Icons.Rounded.History, Icons.Outlined.History),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

@Composable
fun MainScreen(
    main: MainViewModel,
    darkTheme: Boolean,
    showNewApps: Boolean,
    onNewAppsShown: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var appsFilter by remember { mutableStateOf<AppsViewModel.Filter?>(null) }

    LaunchedEffect(showNewApps) {
        if (showNewApps) {
            tab = Tab.APPS
            appsFilter = AppsViewModel.Filter.NEW
            onNewAppsShown()
        }
    }
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                for (item in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(if (tab == item) item.selectedIcon else item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "tab",
        ) { current ->
            when (current) {
                Tab.HOME -> HomeScreen(
                    main = main,
                    contentPadding = padding,
                    onOpenNewApps = {
                        appsFilter = AppsViewModel.Filter.NEW
                        tab = Tab.APPS
                    },
                    onOpenActivity = { tab = Tab.ACTIVITY },
                )
                Tab.APPS -> AppsScreen(
                    contentPadding = padding,
                    requestedFilter = appsFilter,
                    onFilterHandled = { appsFilter = null },
                )
                Tab.ACTIVITY -> ActivityScreen(contentPadding = padding, darkTheme = darkTheme)
                Tab.SETTINGS -> SettingsScreen(contentPadding = padding)
            }
        }
    }
}
