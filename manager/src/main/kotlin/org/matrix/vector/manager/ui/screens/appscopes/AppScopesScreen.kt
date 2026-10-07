package org.matrix.vector.manager.ui.screens.appscopes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.logE
import org.matrix.vector.manager.logI
import org.matrix.vector.ui.theme.Mono

/** One module acting on a target app, as the reverse view names it. */
data class ModuleRef(val packageName: String, val label: String)

/** One target app and every module whose scope names it. */
data class AppScopeEntry(
    val packageName: String,
    val label: String,
    val modules: List<ModuleRef>,
)

sealed interface AppScopesState {
    data object Loading : AppScopesState

    /** The framework is unreachable, so no scope table can be read. */
    data object Unavailable : AppScopesState

    data class Ready(val entries: List<AppScopeEntry>) : AppScopesState
}

/**
 * The scope table read the other way round.
 *
 * The daemon stores scope per module — module to targets — because that is how it is written and
 * how the scope editor edits it. The question a reader actually asks, though, is about an app:
 * "what is acting on this one?". The daemon offers no reverse index, and it needs none — the
 * reverse map is every module's scope folded together, which is what this view model builds.
 * One daemon read per module, on demand, sorted so the app hooked by the most modules leads.
 */
class AppScopesViewModel(
    private val daemonClient: DaemonClient,
) : ViewModel() {

    private val _state = MutableStateFlow<AppScopesState>(AppScopesState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
        // A scope write or a package change anywhere in the app lands here too, so the view
        // rebuilds instead of showing the table as it was when the reader left.
        ServiceLocator.modules.scopeRevision
            .onEach { load() }
            .launchIn(viewModelScope)
        ServiceLocator.modules.packageRevision
            .onEach { load() }
            .launchIn(viewModelScope)
    }

    /** Module scopes are edited elsewhere, so the reverse view re-reads when it reappears. */
    fun load() {
        if (!daemonClient.isAlive) {
            _state.value = AppScopesState.Unavailable
            return
        }
        viewModelScope.launch {
            val entries = build()
            _state.value =
                if (entries == null) AppScopesState.Unavailable else AppScopesState.Ready(entries)
        }
    }

    private suspend fun build(): List<AppScopeEntry>? =
        withContext(Dispatchers.IO) {
            val pm = ServiceLocator.context.packageManager
            val flags = android.content.pm.PackageManager.GET_META_DATA
            val modules =
                runCatching { pm.getInstalledPackages(flags) }
                    .getOrElse { e ->
                        logE("appscopes: module enumeration failed", e)
                        return@withContext null
                    }
                    .filter { pkg ->
                        val info = pkg.applicationInfo
                        info != null && info.metaData?.getBoolean("xposedmodule") == true
                    }
                    .map { ModuleRef(it.packageName, it.applicationInfo!!.loadLabel(pm).toString()) }
                    .sortedBy { it.label.lowercase() }
            if (modules.isEmpty()) return@withContext emptyList()

            val targets = mutableMapOf<String, MutableList<ModuleRef>>()
            var reads = 0
            var failures = 0
            for (module in modules) {
                val scope = daemonClient.getModuleScope(module.packageName)
                if (scope.isFailure) failures++
                val rows = scope.getOrNull() ?: continue
                reads++
                for (row in rows) {
                    targets.getOrPut(row.packageName) { mutableListOf() }.add(module)
                }
            }
            logI(
                "appscopes: $reads module scope(s) read from ${modules.size} module(s)" +
                    if (failures > 0) ", $failures unreadable" else ""
            )
            if (reads == 0) return@withContext null

            targets
                .map { (pkg, mods) ->
                    val label =
                        runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }
                            .getOrElse { pkg }
                    AppScopeEntry(pkg, label, mods.distinct().sortedBy { it.label.lowercase() })
                }
                .sortedWith(
                    compareByDescending<AppScopeEntry> { it.modules.size }
                        .thenBy { it.label.lowercase() }
                )
        }
}

class AppScopesViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppScopesViewModel(daemonClient = ServiceLocator.daemon) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScopesScreen(
    onNavigateBack: () -> Unit,
    onOpenScope: (String, Int) -> Unit,
    viewModel: AppScopesViewModel = viewModel(factory = AppScopesViewModelFactory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appscopes_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.applog_cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            AppScopesState.Loading,
            AppScopesState.Unavailable -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (s is AppScopesState.Unavailable) R.string.appscopes_no_daemon
                            else R.string.applog_empty
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is AppScopesState.Ready -> {
                if (s.entries.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.appscopes_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(s.entries, key = { it.packageName }) { entry ->
                            AppScopeRow(entry = entry, onOpenScope = onOpenScope)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppScopeRow(entry: AppScopeEntry, onOpenScope: (String, Int) -> Unit) {
    var open by rememberSaveable(entry.packageName) { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    entry.packageName,
                    style = Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.appscopes_module_count, entry.modules.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (open) {
                    // Each name is the module's own scope editor, one tap away.
                    for (module in entry.modules) {
                        Text(
                            module.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.padding(top = 4.dp)
                                    .clickable {
                                        onOpenScope(module.packageName, 0)
                                    },
                        )
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = { open = !open }) {
                Icon(
                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
