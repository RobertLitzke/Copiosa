package com.copiosa.ui.audio

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.local.AudioItemEntity
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.model.AudioStatus
import com.copiosa.ui.components.EmptyState
import com.copiosa.ui.components.ProgressBar
import com.copiosa.ui.theme.SourceSerif
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The audio queue. Hosted as a segment of the Library screen (it has no chrome of its own), so the
 * caller supplies the height constraint and the persona texture.
 */
@Composable
fun AudioPane(
    modifier: Modifier = Modifier,
    onOpenTag: (String) -> Unit = {},
    viewModel: AudioViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    // The Media3 notification / lock-screen controls need POST_NOTIFICATIONS on API 33+. Ask once,
    // when the user first starts playback; playback still works (just without the notification) if denied.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val startPlayback: (() -> Unit) -> Unit = remember(notifPermission) {
        { action ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            action()
        }
    }

    // Folders are exempt from the sweep (see AudioItemDao.playedItems), so the button only appears
    // when it would actually delete something.
    val hasPlayed = items.any { it.played && it.tag == null }
    var confirmDeletePlayed by remember { mutableStateOf(false) }

    Column(modifier) {
        // The Library header sits above the segment toggle, so this row carries only the queue's own
        // action; it collapses away entirely when there's nothing played to sweep.
        if (hasPlayed) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = { confirmDeletePlayed = true }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.audio_delete_played))
                }
            }
        }

        ModelBanner(modelState = modelState, onDownload = viewModel::downloadModel)

        if (entries.isEmpty()) {
            EmptyState(stringResource(R.string.audio_empty))
        } else {
            AudioList(
                entries = entries,
                modelReady = modelState is TtsModelState.Ready,
                playingId = playback.currentId,
                onPlay = { id -> startPlayback { viewModel.play(id) } },
                onOpenTag = onOpenTag,
                onRetry = viewModel::retry,
                onMarkUnlistened = viewModel::markUnplayed,
                onDelete = viewModel::delete,
                onPersistOrder = viewModel::persistOrder,
                modifier = Modifier.weight(1f),
            )
            // The transport drives the loose queue; a folder plays from its own screen.
            if (items.any { it.status == AudioStatus.READY && it.tag == null }) {
                TransportBar(
                    title = items.firstOrNull { it.id == playback.currentId }?.title,
                    isPlaying = playback.isPlaying,
                    onPlayPause = { startPlayback { viewModel.onPlayPause() } },
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                )
            }
        }
    }

    if (confirmDeletePlayed) {
        AlertDialog(
            onDismissRequest = { confirmDeletePlayed = false },
            title = { Text(stringResource(R.string.audio_delete_all_played_q)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAllPlayed(); confirmDeletePlayed = false }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePlayed = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun ModelBanner(modelState: TtsModelState, onDownload: () -> Unit) {
    when (modelState) {
        is TtsModelState.Ready -> Unit
        is TtsModelState.Downloading -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pct = (modelState.progress.coerceIn(0f, 1f) * 100).toInt()
                Text(
                    if (modelState.progress >= 0f) stringResource(R.string.audio_downloading, pct)
                    else stringResource(R.string.audio_downloading_indeterminate),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (modelState.progress >= 0f) {
                    ProgressBar(progress = { modelState.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        else -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.audio_tts_needed_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    (modelState as? TtsModelState.Failed)?.message
                        ?: stringResource(R.string.audio_tts_needed_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = onDownload) { Text(stringResource(R.string.audio_download_tts)) }
            }
        }
    }
}

@Composable
private fun AudioList(
    entries: List<AudioListEntry>,
    modelReady: Boolean,
    playingId: Long?,
    onPlay: (Long) -> Unit,
    onOpenTag: (String) -> Unit,
    onRetry: (Long) -> Unit,
    onMarkUnlistened: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onPersistOrder: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    // Local working order so a drag can rearrange rows smoothly; re-synced from the DB when idle.
    var working by remember { mutableStateOf(entries) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(entries, dragging) { if (!dragging) working = entries }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromItem = (working.getOrNull(from.index) as? AudioListEntry.Loose)?.item
        val toItem = (working.getOrNull(to.index) as? AudioListEntry.Loose)?.item
        // Only the unplayed loose items are rearrangeable: played ones stay pinned at the bottom,
        // and a folder's place is decided by its tracks' own order, not by dragging.
        if (fromItem != null && toItem != null && !fromItem.played && !toItem.played) {
            working = working.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        // Solid cards spaced over the list background, like the Chats/Texts list.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(working, key = { it.listKey }) { entry ->
            ReorderableItem(reorderState, key = entry.listKey) { _ ->
                when (entry) {
                    is AudioListEntry.Group -> FolderRow(
                        group = entry,
                        modelReady = modelReady,
                        onClick = { onOpenTag(entry.tag) },
                    )
                    is AudioListEntry.Loose -> {
                        val item = entry.item
                        AudioRow(
                            item = item,
                            modelReady = modelReady,
                            isPlaying = item.id == playingId,
                            dragHandle = if (!item.played) {
                                {
                                    IconButton(
                                        modifier = Modifier.longPressDraggableHandle(
                                            onDragStarted = { dragging = true },
                                            onDragStopped = {
                                                onPersistOrder(
                                                    working
                                                        .filterIsInstance<AudioListEntry.Loose>()
                                                        .map { it.item }
                                                        .filter { !it.played }
                                                        .map { it.id },
                                                )
                                                dragging = false
                                            },
                                        ),
                                        onClick = {},
                                    ) {
                                        Icon(
                                            Icons.Filled.DragHandle,
                                            contentDescription = stringResource(R.string.audio_reorder),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            onPlay = { onPlay(item.id) },
                            onRetry = { onRetry(item.id) },
                            onMarkUnlistened = { onMarkUnlistened(item.id) },
                            onDelete = { onDelete(item.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Stable key: tags and row ids share a list, so each is namespaced. */
private val AudioListEntry.listKey: String
    get() = when (this) {
        is AudioListEntry.Group -> "tag:$tag"
        is AudioListEntry.Loose -> "item:${item.id}"
    }

/**
 * A folder standing in for all of one tag's tracks. Tapping it opens the folder rather than playing:
 * a book is hours of audio, and which chapter to start on is the whole question.
 */
@Composable
private fun FolderRow(group: AudioListEntry.Group, modelReady: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.tag,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                folderSubtitle(group, modelReady),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif, fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "31 tracks · 4h 12m · 12 unheard", with whatever is still rendering said first. */
@Composable
private fun folderSubtitle(group: AudioListEntry.Group, modelReady: Boolean): String {
    val parts = buildList {
        add(stringResource(R.string.audio_tag_tracks, group.tracks.size))
        if (group.pending > 0) {
            add(
                if (!modelReady) stringResource(R.string.audio_status_waiting)
                else stringResource(R.string.book_audio_progress, group.ready + 1, group.tracks.size),
            )
        }
        if (group.ready > 0) add(listeningTime(group.totalMs))
        if (group.failed > 0) add(stringResource(R.string.audio_status_failed))
        add(
            if (group.unheard > 0) stringResource(R.string.audio_tag_unheard, group.unheard)
            else stringResource(R.string.audio_tag_all_heard),
        )
    }
    return parts.joinToString(" · ")
}

/** One track. Shared with the folder screen, which lays its tracks out the same way. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AudioRow(
    item: AudioItemEntity,
    modelReady: Boolean,
    isPlaying: Boolean,
    dragHandle: (@Composable () -> Unit)?,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onMarkUnlistened: () -> Unit,
    onDelete: () -> Unit,
) {
    val clickable = when (item.status) {
        AudioStatus.READY -> onPlay
        AudioStatus.FAILED -> onRetry
        else -> null
    }
    // Tap plays/retries (as before); long-press opens the row menu — same interaction as the
    // Chats/Texts list, replacing the old overflow button.
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { clickable?.invoke() },
                    onLongClick = { menuOpen = true },
                )
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .alpha(if (item.played) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragHandle != null) dragHandle() else Spacer(Modifier.width(48.dp))

            StatusIcon(status = item.status, isPlaying = isPlaying)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLabel(item, modelReady),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif, fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AudioRowMenu(
            expanded = menuOpen,
            played = item.played,
            onDismiss = { menuOpen = false },
            onMarkUnlistened = onMarkUnlistened,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun StatusIcon(status: AudioStatus, isPlaying: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            AudioStatus.GENERATING, AudioStatus.PENDING ->
                if (status == AudioStatus.GENERATING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            AudioStatus.READY -> Icon(
                if (isPlaying) Icons.Filled.VolumeUp else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            AudioStatus.FAILED -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Long-press context menu for an audio row: mark unlistened (if played), delete. */
@Composable
private fun AudioRowMenu(
    expanded: Boolean,
    played: Boolean,
    onDismiss: () -> Unit,
    onMarkUnlistened: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (played) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_mark_unlistened)) },
                leadingIcon = { Icon(Icons.Filled.Replay, contentDescription = null) },
                onClick = { onDismiss(); onMarkUnlistened() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
internal fun TransportBar(
    title: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title ?: stringResource(R.string.audio_play_all),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.audio_previous))
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.audio_pause else R.string.audio_play),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.audio_next))
            }
        }
    }
}

@Composable
internal fun statusLabel(item: AudioItemEntity, modelReady: Boolean): String = when (item.status) {
    AudioStatus.PENDING ->
        if (!modelReady) stringResource(R.string.audio_status_waiting) else stringResource(R.string.audio_status_queued)
    AudioStatus.GENERATING -> stringResource(R.string.audio_status_generating)
    AudioStatus.FAILED -> stringResource(R.string.audio_status_failed)
    AudioStatus.READY -> trackDuration(item.durationMs)
}
