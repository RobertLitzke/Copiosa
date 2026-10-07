package com.copiosa.ui.audio

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.model.AudioStatus
import com.copiosa.ui.components.EmptyState
import com.copiosa.ui.theme.SourceSerif

/**
 * One audio folder: a book's chapters, in chapter order, played straight through.
 *
 * Its own destination rather than an expandable row in the queue, because a novel is thirty-odd
 * tracks and inlining them would bury the loose playlist the Audio pane exists for. The rows are the
 * queue's own [AudioRow], so a track looks and behaves the same wherever it is listed.
 */
@Composable
fun AudioTagScreen(onBack: () -> Unit, viewModel: AudioTagViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val startPlayback: (() -> Unit) -> Unit = remember(notifPermission) {
        { action ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            action()
        }
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val continueTrack = viewModel.continueTrack()
    val hasReady = tracks.any { it.status == AudioStatus.READY }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                viewModel.tag,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Box, so the menu anchors to the button rather than to the whole header row.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.audio_tag_delete))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.audio_tag_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }

        ModelBanner(modelState = modelState, onDownload = viewModel::downloadModel)

        if (hasReady) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "Continue" is the primary action — a folder is something you return to partway.
                OutlinedButton(
                    onClick = { startPlayback { viewModel.playContinue() } },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(
                        stringResource(R.string.audio_tag_continue),
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                    )
                }
                // Only worth offering once "continue" would land somewhere other than the start.
                if (continueTrack != null && continueTrack.id != tracks.firstOrNull { it.status == AudioStatus.READY }?.id) {
                    OutlinedButton(
                        onClick = { startPlayback { viewModel.playFromStart() } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Replay, contentDescription = null)
                        Text(
                            stringResource(R.string.audio_tag_play_all),
                            modifier = Modifier.padding(start = 6.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            EmptyState(stringResource(R.string.audio_empty))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tracks, key = { it.id }) { item ->
                    AudioRow(
                        item = item,
                        modelReady = modelState is TtsModelState.Ready,
                        isPlaying = item.id == playback.currentId,
                        // Chapter order is the book's order; there is nothing to rearrange.
                        dragHandle = null,
                        onPlay = { startPlayback { viewModel.playFrom(item.id) } },
                        onRetry = { viewModel.retry(item.id) },
                        onMarkUnlistened = { viewModel.markUnplayed(item.id) },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
            }
            if (hasReady) {
                TransportBar(
                    title = tracks.firstOrNull { it.id == playback.currentId }?.title,
                    isPlaying = playback.isPlaying,
                    onPlayPause = { startPlayback { viewModel.onPlayPause() } },
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.audio_tag_delete_q, viewModel.tag)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteTag()
                    onBack()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
