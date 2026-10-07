package com.copiosa.data.generation

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues (and cancels) [BookAudioWorker].
 *
 * Unique work per book, KEEP: re-tapping "Generate audio" on a book already rendering must not start
 * a second notification, and the worker re-enqueues whatever is newly ready on its own anyway.
 *
 * No network constraint — unlike the translation download this is entirely on-device — but
 * battery-not-low still applies, because hours of CPU-bound synthesis is exactly the work a phone
 * running low should not be doing.
 */
@Singleton
class BookAudioScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(bookId: Long, title: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(bookId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookAudioWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(BookAudioWorker.KEY_BOOK_ID, bookId)
                        .putString(BookAudioWorker.KEY_TITLE, title)
                        .build(),
                )
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    /** Stops holding the foreground notification; rows already rendered stay, the rest stay PENDING. */
    fun cancel(bookId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
    }

    private fun workName(bookId: Long) = "copiosa-book-audio-$bookId"
}
