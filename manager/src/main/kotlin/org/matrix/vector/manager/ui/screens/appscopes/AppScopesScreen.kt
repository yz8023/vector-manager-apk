package org.matrix.vector.manager.ui.screens.appscopes

import android.content.pm.PackageInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import org.matrix.vector.ui.AppIcon
import org.matrix.vector.ui.ModuleRow
import org.matrix.vector.ui.PanelHeader
import org.matrix.vector.ui.REACH_ICON_SIZE
import org.matrix.vector.ui.SearchField
import org.matrix.vector.ui.SheetHeading

/** One module acting on a target app, as the reverse view names it. */
data class ModuleRef(
    val packageName: String,
    val label: String,
    val applicationInfo: android.content.pm.ApplicationInfo,
)

/** One target app, its presentation facts, and every module whose scope names it. */
data class AppScopeEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val applicationInfo: android.content.pm.ApplicationInfo?,
    val modules: List<ModuleRef>,
)

sealed interface AppScopesState {
    data object Loading : AppScopesState

    /** The framework is unreachable, so no scope table can be read. */
    data object Unavailable : AppScopesState

    data class Ready(
        val entries: List<AppScopeEntry>,
        val moduleCount: Int,
        val unreadable: Int,
    ) : AppScopesState
}

/**
 * The scope table read the other way round, drawn exactly like the module list.
 *
 * The daemon stores scope per module — module to targets — because that is how it is written and
 * how the scope editor edits it. The question a reader actually asks, though, is about an app:
 * "what is acting on this one?". The daemon offers no reverse index, and it needs none — the
 * reverse map is every module's scope folded together, which is what this view model builds.
 * One daemon read per module, on demand; rows sort so the app acted on by the most modules leads.
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
            val built = build()
            _state.value =
                when {
                    built == null -> AppScopesState.Unavailable
                    // A device whose modules' scopes are all unreadable has the same answer to
                    // every row: nothing. That is a transport failure wearing an empty list's
                    // clothes, so it is reported as one rather than drawn as an empty state.
                    built.second == 0 && built.first.isNotEmpty() -> AppScopesState.Unavailable
                    else ->
                        AppScopesState.Ready(
                            entries = built.first,
                            moduleCount = built.second,
                            unreadable = built.third,
                        )
                }
        }
    }

    /** @return the rows, how many module scopes were actually read, and how many were not. */
    private suspend fun build(): Triple<List<AppScopeEntry>, Int, Int>? =
        withContext(Dispatchers.IO) {
            val pm = ServiceLocator.context.packageManager
            val flags = android.content.pm.PackageManager.GET_META_DATA
            val installed =
                runCatching { pm.getInstalledPackages(flags) }
                    .getOrElse { e ->
                        logE("appscopes: package enumeration failed", e)
                        return@withContext null
                    }
            // One enumeration feeds both sides of the map: the modules to read, and the label,
            // version and icon of every target the rows draw.
            val byPackage = installed.associateBy { it.packageName }
            val modules =
                installed
                    .mapNotNull { pkg ->
                        val info = pkg.applicationInfo ?: return@mapNotNull null
                        if (info.metaData?.getBoolean("xposedmodule") != true) return@mapNotNull null
                        ModuleRef(
                            packageName = pkg.packageName,
                            label = info.loadLabel(pm).toString(),
                            applicationInfo = info,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            if (modules.isEmpty()) return@withContext Triple(emptyList(), 0, 0)

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

            val entries =
                targets.map { (pkg, mods) ->
                    val info = byPackage[pkg]
                    AppScopeEntry(
                        packageName = pkg,
                        label =
                            info?.applicationInfo?.loadLabel(pm)?.toString() ?: pkg,
                        versionName = info?.versionName ?: "",
                        applicationInfo = info?.applicationInfo,
                        modules = mods.distinct().sortedBy { it.label.lowercase() },
                    )
                }
            Triple(entries, reads, failures)
        }
}

class AppScopesViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppScopesViewModel(daemonClient = ServiceLocator.daemon) as T
}

@Composable
private fun AppIconOrPlaceholder(
    applicationInfo: android.content.pm.ApplicationInfo?,
    contentDescription: String?,
    size: androidx.compose.ui.unit.Dp,
) {
    if (applicationInfo != null) {
        AppIcon(
            applicationInfo = applicationInfo,
            contentDescription = contentDescription,
            size = size,
        )
    } else {
        // A scope target that is not installed — a stale row, or a package another profile holds —
        // keeps an empty slot, so the row's rhythm survives what the icon cannot.
        Box(Modifier.size(size))
    }
}

/**
 * The reverse view in the module list's own clothes: the same header with a search field inside
 * it, and the same row — an app's icon, name and package where a module's name and description
 * sit, and the modules acting on it drawn as the reach icons a module's scope preview uses.
 * Tapping a row opens the sheet of modules; a module's name there opens its scope editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScopesScreen(
    onNavigateBack: () -> Unit,
    onOpenScope: (String, Int) -> Unit,
    viewModel: AppScopesViewModel = viewModel(factory = AppScopesViewModelFactory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<AppScopeEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(
            title = stringResource(R.string.appscopes_title),
            actions = {
                IconButton(onClick = viewModel::load) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.appscopes_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            description = {
                Text(
                    text =
                        when (val s = state) {
                            is AppScopesState.Ready ->
                                if (s.unreadable > 0)
                                    stringResource(
                                        R.string.appscopes_summary_unreadable,
                                        s.entries.size,
                                        s.moduleCount,
                                        s.unreadable,
                                    )
                                else
                                    stringResource(
                                        R.string.appscopes_summary,
                                        s.entries.size,
                                        s.moduleCount,
                                    )
                            else -> stringResource(R.string.appscopes_reading)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            search = {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.appscopes_search_hint),
                )
            },
        )

        when (val s = state) {
            AppScopesState.Loading,
            AppScopesState.Unavailable -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (s is AppScopesState.Unavailable) R.string.appscopes_no_daemon
                            else R.string.appscopes_reading
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            is AppScopesState.Ready -> {
                val filtered =
                    remember(s, query) {
                        if (query.isBlank()) s.entries
                        else
                            s.entries.filter { entry ->
                                entry.label.contains(query, ignoreCase = true) ||
                                    entry.packageName.contains(query, ignoreCase = true) ||
                                    entry.modules.any { it.label.contains(query, ignoreCase = true) }
                            }
                    }
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (s.entries.isEmpty()) R.string.appscopes_empty
                                else R.string.modules_no_match
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { entry ->
                            ModuleRow(
                                icon = {
                                    AppIconOrPlaceholder(
                                        applicationInfo = entry.applicationInfo,
                                        contentDescription = null,
                                        size = 48.dp,
                                    )
                                },
                                name = entry.label,
                                versionName = entry.versionName,
                                description = entry.packageName,
                                apiBadge = {},
                                onClick = { sheetFor = entry },
                                reachIcons =
                                    entry.modules.map { module ->
                                        {
                                            AppIcon(
                                                applicationInfo = module.applicationInfo,
                                                contentDescription = module.label,
                                                size = REACH_ICON_SIZE,
                                            )
                                        }
                                    },
                                reachCount = entry.modules.size,
                            )
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { entry ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { sheetFor = null },
            sheetState = sheetState,
        ) {
            SheetHeading(
                text = stringResource(R.string.appscopes_sheet_title, entry.label),
                icon = Icons.Rounded.Extension,
            )
            for (module in entry.modules) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable {
                                sheetFor = null
                                onOpenScope(module.packageName, 0)
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        applicationInfo = module.applicationInfo,
                        contentDescription = null,
                        size = 32.dp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                    )
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(
                            module.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            module.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.height(28.dp))
        }
    }
}
