package org.matrix.vector.manager.ui.screens.applog

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.matrix.vector.manager.AppLogStore
import org.matrix.vector.manager.R
import org.matrix.vector.manager.logI
import org.matrix.vector.ui.theme.Mono
import org.matrix.vector.manager.ui.Mono

/**
 * Which entries the pane shows.
 *
 * Three, because a reader comes here for one of two questions and never for browsing: "why did
 * the scope I tried to apply get refused" ([SCOPE], the lines whose `area` is `scope`), "what
 * went wrong" ([PROBLEMS], warnings and errors), or "everything" ([ALL]).
 */
enum class AppLogFilter(val label: Int) {
    ALL(R.string.applog_filter_all),
    PROBLEMS(R.string.applog_filter_problems),
    SCOPE(R.string.applog_filter_scope),
}

/**
 * The manager's own log, on a screen of its own.
 *
 * The shared Logs screen reads the daemon's two streams and needs the daemon — alive, and
 * accepting this manager's signature — for both. Everything the manager logs about itself is
 * also kept by [AppLogStore] with neither requirement, and this screen reads that: the answer to
 * "the manager said something and I missed it" on a device where the shared screen shows nothing
 * at all.
 *
 * Newest at the bottom, like the streams the reader already knows; the pane follows the tail
 * until the reader scrolls up, and a bar scrolls back down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(
    onNavigateBack: () -> Unit,
    onOpenTrace: (String) -> Unit,
    initialFilter: AppLogFilter = AppLogFilter.ALL,
) {
    val revision by AppLogStore.revision.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var query by rememberSaveable { mutableStateOf("") }
    var clearAsked by remember { mutableStateOf(false) }

    val all = remember(revision) { AppLogStore.snapshot() }
    val shown =
        remember(all, filter, query) {
            all
                .asSequence()
                .filter { entry ->
                    when (filter) {
                        AppLogFilter.ALL -> true
                        AppLogFilter.PROBLEMS -> entry.priority >= 5 // WARN and above
                        AppLogFilter.SCOPE -> entry.text.startsWith("scope:")
                    }
                }
                .filter { entry -> query.isBlank() || entry.text.contains(query, ignoreCase = true) }
                .toList()
        }

    val listState = rememberLazyListState()
    val atTail by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
    } }
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty() && atTail) listState.animateScrollToItem(shown.size - 1)
    }

    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val allText =
        remember(shown) {
            shown.joinToString("\n") { entry ->
                "${timeFormat.format(Date(entry.timeMs))} ${levelLetter(entry.priority)} ${entry.text}"
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.applog_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(allText))
                            logI("logs: copied ${shown.size} app-log entries")
                        },
                        enabled = shown.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.applog_copy),
                        )
                    }
                    IconButton(
                        onClick = {
                            val send =
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "Vector Manager log")
                                    .putExtra(Intent.EXTRA_TEXT, allText)
                            context.startActivity(
                                Intent.createChooser(send, null)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        enabled = shown.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.applog_share),
                        )
                    }
                    IconButton(onClick = { clearAsked = true }) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.applog_clear),
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.applog_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLogFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { filter = candidate },
                        label = { Text(stringResource(candidate.label)) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor =
                                    MaterialTheme.colorScheme.secondaryContainer,
                            ),
                    )
                }
            }
            HorizontalDivider()
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.applog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown) { entry ->
                        AppLogRow(entry, timeFormat, onClick = { onOpenTrace(entry.text) })
                    }
                }
            }
        }
    }

    if (clearAsked) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { clearAsked = false },
            title = { Text(stringResource(R.string.applog_clear)) },
            text = { Text(stringResource(R.string.applog_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLogStore.clear()
                        logI("logs: app log cleared")
                        clearAsked = false
                    }
                ) {
                    Text(
                        stringResource(R.string.applog_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAsked = false }) {
                    Text(stringResource(R.string.applog_cancel))
                }
            },
        )
    }
}

@Composable
private fun AppLogRow(
    entry: AppLogStore.Entry,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                timeFormat.format(Date(entry.timeMs)),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.padding(start = 8.dp)
                    .background(
                        levelColor(entry.priority).copy(alpha = 0.15f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    levelLetter(entry.priority),
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor(entry.priority),
                )
            }
        }
        Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(Modifier.fillMaxWidth(), thickness = 0.5.dp)
}

private fun levelLetter(priority: Int): String =
    when {
        priority >= 6 -> "E"
        priority >= 5 -> "W"
        priority >= 4 -> "I"
        else -> "V"
    }

/** Error and warning colours come from the theme so both light and dark schemes stay legible. */
@Composable
private fun levelColor(priority: Int) =
    when {
        priority >= 6 -> MaterialTheme.colorScheme.error
        priority >= 5 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
