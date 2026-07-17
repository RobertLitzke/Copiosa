package com.copiosa.ui.quiz

import android.media.MediaPlayer

/**
 * Minimal player for the quiz's short local WAVs. A plain [MediaPlayer] (the app's existing
 * short-clip pattern — see ReviewScreen's pronunciation playback) rather than the foreground
 * ExoPlayer service: the load-bearing part is the completion callback, which is what reveals the
 * answer options the instant the cue ends.
 *
 * Two extras beyond bare playback: it can [prewarm] the next clip (prepare it during the current
 * answer window so the next question starts with no load gap), and it plays at a caller-chosen
 * [speed] via pitch-preserving [MediaPlayer.setPlaybackParams].
 *
 * All calls happen on the main thread (Compose effects + MediaPlayer's own callbacks share the main
 * looper), so the identity guards below are about event *ordering*, not concurrency: rapid-fire tears
 * one clip down and starts the next back-to-back, and a torn-down player's prepared/completion event
 * can still be queued — every callback checks it still owns the current/pending slot before acting.
 */
class QuizClipPlayer {
    private var player: MediaPlayer? = null
    private var completed = false
    private var activeSpeed = 1.0f

    private var pending: MediaPlayer? = null
    private var pendingPath: String? = null
    private var pendingPrepared = false

    /**
     * Plays [path] from the start at [speed] (1.0 = normal); [onComplete] fires exactly once when the
     * clip ends or fails. Adopts a matching [prewarm]ed player when one is ready, else builds fresh.
     */
    fun play(path: String, speed: Float = 1.0f, onComplete: () -> Unit) {
        val prewarmed = pending
        if (prewarmed != null && pendingPath == path) {
            adoptPrewarmed(prewarmed, speed, onComplete)
        } else {
            playFresh(path, speed, onComplete)
        }
    }

    /**
     * Prepares [path] ahead of time (no-op for null / an already-in-flight prewarm) so a later [play]
     * of the same path starts instantly. The prepared listener does NOT auto-start — it just marks the
     * clip ready (or, if [play] already adopted it, starts it).
     */
    fun prewarm(path: String?) {
        if (path == null || (pending != null && pendingPath == path)) return
        releasePending()
        val mp = MediaPlayer()
        pending = mp
        pendingPath = path
        pendingPrepared = false
        mp.setOnPreparedListener {
            when {
                player === mp -> startWithSpeed(mp, activeSpeed) // adopted before prepare finished
                pending === mp -> pendingPrepared = true
                else -> Unit // released/replaced
            }
        }
        // Before adoption a prepare error just drops the prewarm so play() falls back to a fresh player.
        mp.setOnErrorListener { _, _, _ ->
            if (pending === mp) releasePending()
            true
        }
        try {
            mp.setDataSource(path)
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePending()
        }
    }

    private fun adoptPrewarmed(mp: MediaPlayer, speed: Float, onComplete: () -> Unit) {
        releaseActive() // tear down the previous clip, keep this prewarmed one
        pending = null
        pendingPath = null
        val wasPrepared = pendingPrepared
        pendingPrepared = false
        completed = false
        activeSpeed = speed
        attachCompletion(mp, onComplete)
        player = mp
        // If it's already prepared, start now; otherwise the prewarm prepared listener (which sees
        // player === mp) starts it.
        if (wasPrepared) startWithSpeed(mp, speed)
    }

    private fun playFresh(path: String, speed: Float, onComplete: () -> Unit) {
        release()
        val mp = MediaPlayer()
        player = mp
        completed = false
        activeSpeed = speed
        attachCompletion(mp, onComplete)
        mp.setOnPreparedListener { if (player === mp) startWithSpeed(mp, speed) }
        try {
            mp.setDataSource(path)
            mp.prepareAsync()
        } catch (e: Exception) {
            finishOnce(mp)
        }
    }

    /** Replays the current clip from the start at the same speed (no-op when nothing is loaded). */
    fun replay() {
        val mp = player ?: return
        runCatching {
            mp.seekTo(0)
            startWithSpeed(mp, activeSpeed)
        }
    }

    fun release() {
        releaseActive()
        releasePending()
    }

    /** Pins the completion/error callbacks to [mp] and routes both through the fire-once guard. */
    private fun attachCompletion(mp: MediaPlayer, onComplete: () -> Unit) {
        this.onComplete = onComplete
        mp.setOnCompletionListener { finishOnce(mp) }
        mp.setOnErrorListener { _, _, _ -> finishOnce(mp); true }
    }

    private var onComplete: (() -> Unit)? = null

    private fun finishOnce(mp: MediaPlayer) {
        if (player === mp && !completed) {
            completed = true
            onComplete?.invoke()
        }
    }

    /**
     * Starts [mp] at [speed]. For a non-normal rate, setting a non-zero speed on a prepared (not-yet
     * started) player both applies the rate and transitions it to Started, so playback runs at the
     * right speed from the first sample (the isPlaying guard covers devices that don't auto-start).
     */
    private fun startWithSpeed(mp: MediaPlayer, speed: Float) {
        if (speed == 1.0f) {
            runCatching { mp.start() }
        } else {
            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
            runCatching { if (!mp.isPlaying) mp.start() }
        }
    }

    private fun releaseActive() {
        player?.let { detachAndRelease(it) }
        player = null
        onComplete = null
    }

    private fun releasePending() {
        pending?.let { detachAndRelease(it) }
        pending = null
        pendingPath = null
        pendingPrepared = false
    }

    private fun detachAndRelease(mp: MediaPlayer) {
        // Detach listeners first so a queued prepared/completion event can't touch a dead player.
        mp.setOnPreparedListener(null)
        mp.setOnCompletionListener(null)
        mp.setOnErrorListener(null)
        runCatching { mp.release() }
    }
}
