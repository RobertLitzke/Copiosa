package com.copiosa.data.generation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues (and cancels) the rapid whole-book download, [BookBulkTranslateWorker].
 *
 * Unique work per book, KEEP: opening a book already downloading must not restart it, and the
 * worker resumes from the frontier anyway. The same call is therefore safe from both the
 * download-mode dialog and the reader's "this book is RAPID but nothing is running" re-kick.
 */
@Singleton
class BookBulkTranslateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(bookId: Long, title: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(bookId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookBulkTranslateWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(BookBulkTranslateWorker.KEY_BOOK_ID, bookId)
                        .putString(BookBulkTranslateWorker.KEY_TITLE, title)
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                // Minutes, not seconds: a retry here means the provider just refused a call, and
                // coming straight back at it is how a rate limit turns into a spent key.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Stops a download; the manager's own job is cancelled separately (completed sections stay). */
    fun cancel(bookId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: Long) = "copiosa-book-download-$bookId"
}
