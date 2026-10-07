package com.copiosa.data.maintenance

import com.copiosa.data.maintenance.tasks.AudioPurgeTask
import com.copiosa.data.maintenance.tasks.BookAudioTask
import com.copiosa.data.maintenance.tasks.BookTranslateTask
import com.copiosa.data.maintenance.tasks.ChatRetryTask
import com.copiosa.data.maintenance.tasks.ClozeGenerateTask
import com.copiosa.data.maintenance.tasks.ClozeMineTask
import com.copiosa.data.maintenance.tasks.ClozeTranslateTask
import com.copiosa.data.maintenance.tasks.EnrichVocabTask
import com.copiosa.data.maintenance.tasks.FocusStatsRollupTask
import com.copiosa.data.maintenance.tasks.ReviewReminderTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The maintenance task registry — the one place a new task is wired in.
 *
 * An explicit list rather than Hilt multibindings, matching how `ActivityRegistry` enumerates
 * its activities: no `@Binds`, and the run order is readable in one glance.
 */
@Singleton
class MaintenanceTasks private constructor(
    /** Ordered: cheap local upkeep first, LLM-spending work last. */
    val all: List<MaintenanceTask>,
) {
    @Inject
    constructor(
        focusStatsRollup: FocusStatsRollupTask,
        audioPurge: AudioPurgeTask,
        reviewReminder: ReviewReminderTask,
        chatRetry: ChatRetryTask,
        clozeMine: ClozeMineTask,
        enrichVocab: EnrichVocabTask,
        clozeGenerate: ClozeGenerateTask,
        clozeTranslate: ClozeTranslateTask,
        bookTranslate: BookTranslateTask,
        bookAudio: BookAudioTask,
    ) : this(
        listOf(
            focusStatsRollup,
            audioPurge,
            reviewReminder,
            chatRetry,
            // Local-only, so it belongs with the cheap upkeep above the LLM-spending tasks.
            clozeMine,
            enrichVocab,
            clozeGenerate,
            // After clozeGenerate: a sentence that exists but is unexplained can wait behind a word
            // that has no sentence at all.
            clozeTranslate,
            bookTranslate,
            // After bookTranslate: a frontier that just moved is a chapter this can queue right away.
            bookAudio,
        ),
    )

    internal companion object {
        /** Test-only: run the runner against arbitrary fakes. */
        internal fun of(tasks: List<MaintenanceTask>) = MaintenanceTasks(tasks)
    }
}
