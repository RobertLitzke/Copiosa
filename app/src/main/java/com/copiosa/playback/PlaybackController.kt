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
import com.copiosa.di.AppScope
import com.copiosa.domain.stats.FocusActivityType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    @AppScope private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** The track currently loaded, tracked so we know which one just finished on a transition. */
    private var currentId: Long? = null

    /** Language per queued item, snapshotted at [play] so listening time lands in the right profile. */
    private var langById: Map<Long, String> = emptyMap()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** Ticks the resume point into Room while a track plays; cancelled whenever playback stops. */
    private var positionTicker: Job? = null

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // An automatic advance means the previous track played to its end → mark it listened
            // (which also clears its resume point, so a re-listen starts at the top).
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                currentId?.let { markPlayed(it) }
            } else {
                // A manual skip abandons the old track partway; keep where it got to.
                savePosition()
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
            if (isPlaying) {
                startListening()
                startPositionTicker()
            } else {
                focusTracker.stop(FocusTimeTracker.Channel.PLAYBACK)
                stopPositionTicker()
                savePosition()
            }
            pushState()
        }
    }

    /**
     * Persists the resume point periodically as well as on every stop.
     *
     * A book chapter is twenty minutes long, and the process can be killed while backgrounded with
     * no callback at all — pausing is not the only way listening ends. The tick bounds how much of a
     * chapter a kill can cost to [SAVE_INTERVAL_MS].
     */
    private fun startPositionTicker() {
        if (positionTicker?.isActive == true) return
        positionTicker = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                savePosition()
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }

    /**
     * Snapshots the playing track's position. `currentPosition` is main-thread confined (Media3
     * builds the controller there), so it is read on the main executor and only the resulting number
     * crosses to the IO scope.
     */
    private fun savePosition() {
        val c = controller ?: return
        ContextCompat.getMainExecutor(context).execute {
            val id = c.currentMediaItem?.mediaId?.toLongOrNull() ?: return@execute
            val position = c.currentPosition
            val duration = c.duration
            val resume = when {
                // Near the very start there is nothing worth resuming to.
                position <= MIN_RESUME_MS -> 0L
                // Near the end there is nothing left to resume *into* — and this is the case that
                // races markPlayed, which zeroes the position as the track finishes. Writing 0 here
                // means whichever of the two lands last still leaves the track ready to replay.
                duration > 0 && position >= duration - END_MARGIN_MS -> 0L
                else -> position
            }
            scope.launch { audioRepo.setPosition(id, resume) }
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
        scope.launch {
            val item = audioRepo.getById(id) ?: return@launch
            langById = langById + (id to item.langCode)
            // Only still relevant if this track is still the one playing.
            if (currentId == id && controller?.isPlaying == true) {
                focusTracker.start(FocusTimeTracker.Channel.PLAYBACK, FocusActivityType.LISTENING, item.langCode)
            }
        }
    }

    /**
     * Plays [items] as a playlist, starting at [startId] (or the first item), resuming that track
     * where it was left. Only the starting track resumes — the ones after it are being reached for
     * the first time in this queue and start at the top.
     */
    fun play(items: List<AudioItemEntity>, startId: Long?) {
        if (items.isEmpty()) return
        langById = items.associate { it.id to it.langCode }
        withController { c ->
            val startIndex = items.indexOfFirst { it.id == startId }.takeIf { it >= 0 } ?: 0
            c.setMediaItems(items.map { it.toMediaItem() }, startIndex, items[startIndex].positionMs)
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
        scope.launch { audioRepo.markPlayed(id) }
    }

    private fun AudioItemEntity.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(File(filePath!!).toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    // The folder name, so the lock screen shows "Chapter 4" under the book it is from
                    // rather than a chapter number with no book attached.
                    .setArtist(tag)
                    .setAlbumTitle(tag)
                    .build(),
            )
            .build()

    private companion object {
        const val SAVE_INTERVAL_MS = 15_000L

        /** Below this a "resume" would just replay the opening; treat it as the start. */
        const val MIN_RESUME_MS = 5_000L

        /** Within this of the end, the track is finished rather than paused partway. */
        const val END_MARGIN_MS = 5_000L
    }
}
