package com.copiosa.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.copiosa.data.local.AudioItemEntity
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.stats.FocusTimeTracker
import com.copiosa.domain.stats.FocusActivityType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the Audio tab needs to render the transport bar and highlight the now-playing row. */
data class PlaybackUiState(
    val currentId: Long? = null,
    val isPlaying: Boolean = false,
)

/**
 * App-side handle to [AudioPlaybackService]. Connects a [MediaController] lazily (on the main thread,
 * as Media3 requires) and exposes a small [state] plus transport actions for the UI. When a track
 * finishes — auto-advancing to the next or ending the queue — its item is marked played, which sinks
 * it to the bottom of the playlist (greyed) via [AudioRepository].
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioRepo: AudioRepository,
    private val focusTracker: FocusTimeTracker,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** The track currently loaded, tracked so we know which one just finished on a transition. */
    private var currentId: Long? = null

    /** Language per queued item, snapshotted at [play] so listening time lands in the right profile. */
    private var langById: Map<Long, String> = emptyMap()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // An automatic advance means the previous track played to its end → mark it listened.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                currentId?.let { markPlayed(it) }
            }
            currentId = mediaItem?.mediaId?.toLongOrNull()
            // Restart the listening session under the new track's language (a no-op split for
            // same-language queues; playlists are per-profile anyway).
            if (controller?.isPlaying == true) startListening()
            pushState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The final track in the queue ends without a transition; mark it here.
            if (playbackState == Player.STATE_ENDED) {
                currentId?.let { markPlayed(it) }
            }
            pushState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startListening() else focusTracker.stop(FocusTimeTracker.Channel.PLAYBACK)
            pushState()
        }
    }

    /**
     * Opens (or re-opens under a new language) the LISTENING focus session for the current track.
     * Counts while the app is backgrounded — this controller and the tracker are process singletons
     * kept alive by [AudioPlaybackService]. Falls back to a DB lookup for an id missing from the
     * snapshot (e.g. a session restored after process death).
     */
    private fun startListening() {
        val id = currentId ?: return
        val lang = langById[id]
        if (lang != null) {
            focusTracker.start(FocusTimeTracker.Channel.PLAYBACK, FocusActivityType.LISTENING, lang)
            return
        }
        ioScope.launch {
            val item = audioRepo.getById(id) ?: return@launch
            langById = langById + (id to item.langCode)
            // Only still relevant if this track is still the one playing.
            if (currentId == id && controller?.isPlaying == true) {
                focusTracker.start(FocusTimeTracker.Channel.PLAYBACK, FocusActivityType.LISTENING, item.langCode)
            }
        }
    }

    /** Plays [items] as a playlist, starting at [startId] (or the first item). */
    fun play(items: List<AudioItemEntity>, startId: Long?) {
        if (items.isEmpty()) return
        langById = items.associate { it.id to it.langCode }
        withController { c ->
            val startIndex = items.indexOfFirst { it.id == startId }.takeIf { it >= 0 } ?: 0
            c.setMediaItems(items.map { it.toMediaItem() }, startIndex, 0L)
            currentId = items[startIndex].id
            c.prepare()
            c.play()
            pushState()
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else if (c.mediaItemCount > 0) c.play()
    }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { it.seekToPreviousMediaItem() }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        val future = controllerFuture ?: run {
            val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
            MediaController.Builder(context, token).buildAsync().also { controllerFuture = it }
        }
        // Media3 completes the future on the app's main thread, so the controller is touched there.
        future.addListener({
            val c = future.get()
            if (controller == null) {
                controller = c
                c.addListener(listener)
            }
            action(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun pushState() {
        val c = controller ?: return
        _state.value = PlaybackUiState(
            currentId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
        )
    }

    private fun markPlayed(id: Long) {
        ioScope.launch { audioRepo.markPlayed(id) }
    }

    private fun AudioItemEntity.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(File(filePath!!).toUri())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
}
