package com.copiosa.data.generation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.copiosa.R
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.model.AudioStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.takeWhile

/**
 * Holds a foreground notification while a book's chapters render.
 *
 * The synthesis itself is not here — it stays on the one shared
 * [com.copiosa.data.tts.TtsRenderQueue] behind [AudioGenerationManager], for the same reason
 * [BookBulkTranslateWorker] drives the translation manager rather than the repository: one writer,
 * one consumer, no second loop racing it. What this worker adds is *time*. A novel is tens of
 * chapters and hours of on-device synthesis, and the app-lifetime scope the queue runs on dies with
 * the process, so without a foreground service a long book would only ever progress while the app
 * happened to be open.
 *
 * It therefore watches the book's rows and exits when none are left waiting — the notification is
 * both the progress readout and the thing keeping the process alive.
 */
@HiltWorker
class BookAudioWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val audioRepo: AudioRepository,
    private val bookAudio: BookAudioManager,
    private val modelStore: SupertonicModelStore,
) : CoroutineWorker(appContext, params) {

    private val bookId: Long get() = inputData.getLong(KEY_BOOK_ID, -1L)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        // Enqueueing here as well as at the tap makes the worker safe to schedule from anywhere, and
        // picks up chapters the translation frontier passed since it was scheduled.
        bookAudio.enqueueReady(bookId)

        // Without the voice model every row would sit at PENDING forever, and this would hold a
        // foreground notification for a queue that cannot move. The queue drains itself the moment
        // the download finishes, so there is nothing to keep alive for.
        if (!modelStore.isReady()) return Result.success()

        audioRepo.observeForBook(bookId)
            .takeWhile { items -> items.any { it.status.pending } }
            .collect { items ->
                // setForeground throws once the worker is stopped (cancelled, or the OS reclaimed the
                // service); the collection ends on the next emission either way.
                runCatching { setForeground(foregroundInfo(items.count { !it.status.pending }, items.size)) }
            }

        return Result.success()
    }

    private val AudioStatus.pending: Boolean
        get() = this == AudioStatus.PENDING || this == AudioStatus.GENERATING

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val title = inputData.getString(KEY_TITLE) ?: appContext.getString(R.string.book_audio_untitled)
        val cancel = WorkManager.getInstance(appContext).createCancelPendingIntent(id)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_book)
            .setContentTitle(appContext.getString(R.string.book_audio_generating, title))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, appContext.getString(R.string.action_cancel), cancel)
        if (total > 0) {
            builder
                .setContentText(
                    appContext.getString(
                        R.string.book_audio_progress,
                        (done + 1).coerceAtMost(total),
                        total,
                    ),
                )
                .setProgress(total, done, false)
        }

        // dataSync rather than mediaPlayback: nothing is being played, this is a long local job the
        // learner asked for by tapping Generate audio.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, builder.build())
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.book_audio_channel),
                // LOW: a progress bar that ticks for hours must never make a sound.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TITLE = "title"

        private const val CHANNEL_ID = "book_audio"

        /** One book rendering at a time (the queue is single-consumer), so the id is fixed. */
        private const val NOTIFICATION_ID = 4712
    }
}
