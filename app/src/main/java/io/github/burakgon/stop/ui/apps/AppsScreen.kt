package io.github.burakgon.stop.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.burakgon.stop.R
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.ui.apps.AppsViewModel.Filter
import io.github.burakgon.stop.ui.components.AppIcon
import io.github.burakgon.stop.ui.graphViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    contentPadding: PaddingValues,
    requestedFilter: Filter?,
    onFilterHandled: () -> Unit,
) {
    val viewModel = graphViewModel { AppsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var bulkMode by remember { mutableStateOf<RuleMode?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(requestedFilter) {
        if (requestedFilter != null) {
            viewModel.filter.value = requestedFilter
            onFilterHandled()
        }
    }
    BackHandler(enabled = searching) {
        searching = false
        viewModel.query.value = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; viewModel.query.value = "" }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_close_search))
                        }
                    }
                },
                title = {
                    if (searching) {
                        SearchField(query = query, onQueryChange = { viewModel.query.value = it })
                    } else {
                        Text(stringResource(R.string.apps_title))
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.action_search))
                        }
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.query.value = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close_search))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_show_system)) },
                                trailingIcon = { Checkbox(checked = state.showSystem, onCheckedChange = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.setShowSystem(!state.showSystem)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_block_shown)) },
                                onClick = { menuOpen = false; bulkMode = RuleMode.BLOCK },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_allow_shown)) },
                                onClick = { menuOpen = false; bulkMode = RuleMode.ALLOW },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "filters") {
                FilterRow(filter = filter, counts = state.counts, onSelect = { viewModel.filter.value = it })
            }
            item(key = "hint") {
                Text(
                    stringResource(R.string.apps_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            when {
                state.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    Text(
                        if (query.isNotBlank()) stringResource(R.string.apps_empty_search, query) else stringResource(R.string.apps_empty_filter),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                    )
                }
                else -> items(state.items, key = { it.app.packageName }) { item ->
                    AppRow(
                        item = item,
                        onToggle = { viewModel.setBlocked(item.app.packageName, it) },
                        onClick = { selected = item.app.packageName },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    val selectedItem = selected?.let { state.all[it] }
    if (selectedItem != null) {
        AppDetailSheet(
            item = selectedItem,
            defaultGraceSeconds = state.defaultGraceSeconds,
            viewModel = viewModel,
            onDismiss = { selected = null },
        )
    }
    // The app went away (uninstalled) while its details were open.
    LaunchedEffect(selected, state.loading, selectedItem) {
        if (selected != null && !state.loading && selectedItem == null) selected = null
    }

    bulkMode?.let { mode ->
        val targets = state.items.filter { !it.isProtected && it.blocked != (mode == RuleMode.BLOCK) }.map { it.app.packageName }
        AlertDialog(
            onDismissRequest = { bulkMode = null },
            title = {
                Text(
                    if (mode == RuleMode.BLOCK) {
                        pluralStringResource(R.plurals.apps_bulk_confirm_block, targets.size, targets.size)
                    } else {
                        pluralStringResource(R.plurals.apps_bulk_confirm_allow, targets.size, targets.size)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = targets.isNotEmpty(),
                    onClick = {
                        viewModel.decide(targets, mode)
                        bulkMode = null
                    },
                ) { Text(stringResource(R.string.apps_bulk_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { bulkMode = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.apps_search_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun FilterRow(filter: Filter, counts: Map<Filter, Int>, onSelect: (Filter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in Filter.entries) {
            val count = counts[option] ?: 0
            if (option == Filter.NEW && count == 0 && filter != Filter.NEW) continue
            val label = stringResource(
                when (option) {
                    Filter.ALL -> R.string.filter_all
                    Filter.BLOCKED -> R.string.filter_blocked
                    Filter.ALLOWED -> R.string.filter_allowed
                    Filter.NEW -> R.string.filter_new
                }
            )
            FilterChip(
                selected = filter == option,
                onClick = { onSelect(option) },
                label = { Text("$label  $count") },
                leadingIcon = if (filter == option) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun AppRow(
    item: AppsViewModel.Item,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        item.app.packageName == SystemRoles.SHIZUKU_PACKAGE -> stringResource(R.string.app_status_needed_by_stop)
        item.isProtected -> stringResource(R.string.app_status_protected)
        item.isNew && item.blocked -> stringResource(R.string.app_status_new_blocked)
        item.isNew -> stringResource(R.string.app_status_new_allowed)
        item.blocked && item.stopsToday > 0 ->
            pluralStringResource(R.plurals.app_status_stops_today, item.stopsToday, item.stopsToday)
        item.blocked -> stringResource(R.string.app_status_blocked)
        else -> stringResource(R.string.app_status_allowed)
    }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { AppIcon(item.app.packageName, size = 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!item.app.isUserFacing) {
                    Spacer(Modifier.width(8.dp))
                    Badge(stringResource(R.string.app_system_badge))
                }
            }
        },
        supportingContent = {
            Text(
                status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    item.isNew -> MaterialTheme.colorScheme.tertiary
                    item.blocked -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = {
            Switch(checked = item.blocked, onCheckedChange = onToggle, enabled = !item.isProtected)
        },
    )
}

@Composable
fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
