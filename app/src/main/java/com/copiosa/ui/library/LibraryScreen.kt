package com.copiosa.ui.library

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.CircleAvatar
import com.copiosa.ui.components.EmptyState
import com.copiosa.ui.components.ListHeader
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.theme.SourceSerif
import com.copiosa.ui.theme.skin.LocalAppSkin
import com.copiosa.ui.theme.skin.personaTexture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun LibraryScreen(
    onOpenConversation: (Long) -> Unit,
    onOpenText: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val moreCount by viewModel.moreCount.collectAsState()
    val pending by viewModel.pendingCount.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LibraryEntry?>(null) }

    // A committed batch inserts its queued rows at the top, but the keyed LazyColumn stays anchored
    // to whatever row was visible — jump to the top so the new topics are in view. The rows land via
    // Room a beat after the event, so pin now and re-pin once the top row actually changes.
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.batchStarted.collect {
            val topBefore = viewModel.entries.value.firstOrNull()?.id
            listState.scrollToItem(0)
            withTimeoutOrNull(2_000) {
                viewModel.entries.first { it.firstOrNull()?.id != topBefore }
            }
            listState.scrollToItem(0)
        }
    }

    // "Generate audio" (long-press menu): queue now if the voice is installed, else prompt to download.
    val context = LocalContext.current
    val addedMsg = stringResource(R.string.audio_added)
    var pendingAudio by remember { mutableStateOf<LibraryEntry?>(null) }
    fun requestAudio(entry: LibraryEntry) {
        if (viewModel.isTtsReady()) {
            viewModel.sendToAudio(entry)
            Toast.makeText(context, addedMsg, Toast.LENGTH_SHORT).show()
        } else {
            pendingAudio = entry
        }
    }

    val skin = LocalAppSkin.current
    Column(modifier = Modifier.fillMaxSize().personaTexture(skin)) {
        ListHeader(
            title = stringResource(R.string.library_title),
            searching = searching,
            query = query,
            onQueryChange = viewModel::setQuery,
            onToggleSearch = {
                searching = !searching
                if (!searching) viewModel.setQuery("")
            },
        )

        SegmentedToggle(
            selected = filter.ordinal,
            labels = listOf(
                stringResource(R.string.filter_all),
                stringResource(R.string.filter_chats),
                stringResource(R.string.filter_translations),
            ),
            onSelect = { viewModel.setFilter(LibraryFilter.entries[it]) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (pending > 0) BatchProgressBanner(pending)

        if (entries.isEmpty()) {
            EmptyState(stringResource(emptyMessageFor(filter)))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Each row is its own solid card; the gap + edge padding lets the list background
                // (and any persona texture) show through between them.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Chats and translations share the conversations table, so ids are unique across both.
                items(entries, key = { it.id }) { entry ->
                    when (entry) {
                        is LibraryEntry.Chat -> ConversationRowItem(
                            row = entry.row,
                            onClick = { onOpenConversation(entry.id) },
                            onToggleStar = {
                                viewModel.setStarred(entry.id, !entry.row.conversation.starred)
                            },
                            onToggleRead = {
                                viewModel.setRead(entry.id, !entry.row.conversation.read)
                            },
                            onRetry = { viewModel.retryChat(entry.id) },
                            onGenerateAudio = { requestAudio(entry) },
                            onDelete = { pendingDelete = entry },
                        )
                        is LibraryEntry.Translation -> TextRowItem(
                            item = entry.item,
                            onClick = { onOpenText(entry.id) },
                            onToggleStar = { viewModel.setStarred(entry.id, !entry.item.starred) },
                            onRetry = { viewModel.retryTranslation(entry.id) },
                            onGenerateAudio = { requestAudio(entry) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
                if (moreCount > 0) {
                    item(key = "show_more") {
                        ShowMoreRow(count = moreCount, onClick = viewModel::showMore)
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        val isChat = target is LibraryEntry.Chat
        val title = when (target) {
            is LibraryEntry.Chat -> target.row.conversation.title
            is LibraryEntry.Translation -> target.item.title
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = {
                Text(stringResource(if (isChat) R.string.delete_conversation_title else R.string.delete_text_title))
            },
            text = {
                Text(stringResource(if (isChat) R.string.delete_conversation_message else R.string.delete_text_message, title))
            },
        )
    }

    pendingAudio?.let { entry ->
        TtsDownloadPrompt(
            onConfirm = {
                viewModel.startTtsDownload()
                viewModel.sendToAudio(entry)
                Toast.makeText(context, addedMsg, Toast.LENGTH_SHORT).show()
                pendingAudio = null
            },
            onDismiss = { pendingAudio = null },
        )
    }
}

/** Dispatches "Generate audio" to the right per-type renderer. */
private fun LibraryViewModel.sendToAudio(entry: LibraryEntry) {
    when (entry) {
        is LibraryEntry.Chat -> sendChatToAudio(entry.id)
        is LibraryEntry.Translation -> sendTextToAudio(entry.id)
    }
}

private fun emptyMessageFor(filter: LibraryFilter): Int = when (filter) {
    LibraryFilter.ALL -> R.string.library_empty_all
    LibraryFilter.CHATS -> R.string.library_empty
    LibraryFilter.TRANSLATIONS -> R.string.texts_empty
}

// region Chat row

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRowItem(
    row: ConversationRow,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit,
    onRetry: () -> Unit,
    onGenerateAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    val failed = row.status == RowStatus.FAILED
    val unread = !row.conversation.read
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                // Tapping always opens the chat — even a failed one, so the user can read it; retry is
                // confined to the trailing icon (and the long-press menu).
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAvatar(icon = Icons.Filled.ChatBubbleOutline, highlighted = unread)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        row.conversation.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.conversation.starred) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.cd_starred),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeTime(row.conversation.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ChatRowSubtitle(row)
            }
            if (failed) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_retry),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        ConversationRowMenu(
            expanded = menuOpen,
            starred = row.conversation.starred,
            read = row.conversation.read,
            failed = failed,
            onDismiss = { menuOpen = false },
            onToggleStar = onToggleStar,
            onToggleRead = onToggleRead,
            onRetry = onRetry,
            onGenerateAudio = onGenerateAudio,
            onDelete = onDelete,
        )
    }
}

/** Long-press context menu for a chat row: star, read-status, retry (if failed), generate audio, delete. */
@Composable
private fun ConversationRowMenu(
    expanded: Boolean,
    starred: Boolean,
    read: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit,
    onRetry: () -> Unit,
    onGenerateAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (starred) R.string.action_unstar else R.string.action_star)) },
            leadingIcon = {
                Icon(if (starred) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null)
            },
            onClick = { onToggleStar(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (read) R.string.action_mark_unread else R.string.action_mark_read)) },
            leadingIcon = {
                Icon(if (read) Icons.Filled.MarkChatUnread else Icons.Filled.MarkChatRead, contentDescription = null)
            },
            onClick = { onToggleRead(); onDismiss() },
        )
        if (failed) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_retry)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = { onRetry(); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_generate_audio)) },
            leadingIcon = { Icon(Icons.Filled.Headphones, contentDescription = null) },
            onClick = { onGenerateAudio(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDelete(); onDismiss() },
        )
    }
}

/** The second line of a chat row: a generation-status hint, or the latest-message snippet. */
@Composable
private fun ChatRowSubtitle(row: ConversationRow) {
    when (row.status) {
        RowStatus.GENERATING -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.batch_generating),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowStatus.QUEUED -> Text(
            stringResource(R.string.batch_queued),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowStatus.FAILED -> Text(
            stringResource(R.string.batch_failed),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.error,
        )
        null -> row.snippet?.let { snippet ->
            Text(
                snippet,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif, fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Footer row revealing the next page of the list; [count] is how many rows are still hidden. */
@Composable
private fun ShowMoreRow(count: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick) {
            Text(pluralStringResource(R.plurals.library_show_more, count, count))
        }
    }
}

/** Slim banner shown above the list while chats are queued/generating in the background. */
@Composable
private fun BatchProgressBanner(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            pluralStringResource(R.plurals.batch_in_progress, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

// endregion

// region Translation row

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextRowItem(
    item: TextItem,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onRetry: () -> Unit,
    onGenerateAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    val failed = item.status == TextStatus.Failed
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                // Tapping always opens the reader (even a failed import shows what was fetched); retry is
                // confined to the trailing icon (and the long-press menu).
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Unfinished texts (not yet read to the end) get the accent avatar + bold title, like
            // unread chats; once the reader reaches the bottom they fade to the muted "read" style.
            val unfinished = !item.finishedReading
            CircleAvatar(icon = Icons.Filled.Description, highlighted = unfinished)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                        fontWeight = if (unfinished) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.starred) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.cd_starred),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CefrBadge(item.level)
                    TextSubtitle(item)
                }
            }
            if (failed) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_retry),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        TextRowMenu(
            expanded = menuOpen,
            starred = item.starred,
            failed = failed,
            onDismiss = { menuOpen = false },
            onToggleStar = onToggleStar,
            onRetry = onRetry,
            onGenerateAudio = onGenerateAudio,
            onDelete = onDelete,
        )
    }
}

/** Long-press context menu for a translation row: star, retry (if failed), generate audio, delete. */
@Composable
private fun TextRowMenu(
    expanded: Boolean,
    starred: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onToggleStar: () -> Unit,
    onRetry: () -> Unit,
    onGenerateAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (starred) R.string.action_unstar else R.string.action_star)) },
            leadingIcon = {
                Icon(if (starred) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null)
            },
            onClick = { onToggleStar(); onDismiss() },
        )
        if (failed) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_retry)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = { onRetry(); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_generate_audio)) },
            leadingIcon = { Icon(Icons.Filled.Headphones, contentDescription = null) },
            onClick = { onGenerateAudio(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDelete(); onDismiss() },
        )
    }
}

/** Second line of a translation row: a generation-status hint, or the source + reading time when ready. */
@Composable
private fun TextSubtitle(item: TextItem) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)
    when (val status = item.status) {
        TextStatus.Importing -> StatusRow(stringResource(R.string.text_status_importing))
        is TextStatus.Translating -> StatusRow(
            stringResource(R.string.text_translating_progress, status.done, status.total),
        )
        TextStatus.Failed -> Text(
            stringResource(R.string.text_failed),
            style = labelStyle,
            color = MaterialTheme.colorScheme.error,
        )
        null -> {
            val source = item.source ?: stringResource(R.string.text_source_pasted)
            val text = if (item.readingMinutes > 0) {
                stringResource(R.string.text_reading_minutes, source, item.readingMinutes)
            } else {
                source
            }
            Text(text, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion
