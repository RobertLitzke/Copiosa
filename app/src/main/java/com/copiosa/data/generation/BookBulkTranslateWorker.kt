package com.copiosa.data.generation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.copiosa.data.repository.BookRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The "rapid" download mode: walks one book to the end in a single sequential run.
 *
 * A foreground worker rather than a plain background job because the run is long — a novel is 50–150
 * calls, tens of minutes — and the app-lifetime scope behind [BookTranslationManager] dies with the
 * process. The notification is what buys that time, and it carries a Cancel action so a run the
 * learner regrets is one tap away.
 *
 * It drives the manager rather than the repository, exactly as BookTranslateTask does: the manager
 * stays the single writer (no second loop can translate the same section), and the reader and
 * library show live progress if the app is reopened mid-run.
 */
@HiltWorker
class BookBulkTranslateWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val bookRepo: BookRepository,
    private val books: BookTranslationManager,
) : CoroutineWorker(appContext, params) {

    private val bookId: Long get() = inputData.getLong(KEY_BOOK_ID, -1L)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result {
        val book = bookRepo.getById(bookId) ?: return Result.success()
        if (book.translatedSections >= book.totalSections) return Result.success()

        setForeground(foregroundInfo(book.translatedSections to book.totalSections))
        coroutineScope {
            // The notification is the only progress this run has while the app is closed, so keep
            // it live off the manager's own flow rather than re-querying the book.
            val ticker = launch {
                books.progress.collectLatest { all ->
                    all[bookId]?.let { runCatching { setForeground(foregroundInfo(it)) } }
                }
            }
            try {
                books.translateToEnd(bookId).join()
            } finally {
                ticker.cancel()
            }
        }

        val after = bookRepo.getById(bookId) ?: return Result.success()
        if (after.translatedSections >= after.totalSections) return Result.success()
        // Short of the end means the manager's loop broke on an LLM error. WorkManager's backoff is
        // the retry — bounded, so a permanently broken book stops costing calls and the reader's
        // status bar (which already surfaces the error) becomes the way back in.
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    /** [progress] is (section in flight, total sections); null before the first one is known. */
    private fun foregroundInfo(progress: Pair<Int, Int>?): ForegroundInfo {
        ensureChannel()
        val title = inputData.getString(KEY_TITLE) ?: appContext.getString(R.string.book_source)
        val cancel = WorkManager.getInstance(appContext).createCancelPendingIntent(id)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_book)
            .setContentTitle(appContext.getString(R.string.book_download_notification_title, title))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, appContext.getString(R.string.action_cancel), cancel)
        progress?.let { (done, total) ->
            builder
                .setContentText(
                    appContext.getString(
                        R.string.book_translating_progress,
                        (done + 1).coerceAtMost(total),
                        total,
                    ),
                )
                .setProgress(total, done, false)
        }

        // dataSync: this is network work on the user's behalf, started from a foreground tap.
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
                appContext.getString(R.string.book_download_channel),
                // LOW: a progress bar that ticks for half an hour must never make a sound.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TITLE = "title"

        private const val CHANNEL_ID = "book_download"

        /** One rapid download at a time, so the id is fixed. */
        private const val NOTIFICATION_ID = 4711

        /** WorkManager retries with exponential backoff; past this the book has a real problem. */
        private const val MAX_ATTEMPTS = 4
    }
}
