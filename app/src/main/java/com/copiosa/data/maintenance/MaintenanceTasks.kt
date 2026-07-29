package com.copiosa.data.maintenance

import com.copiosa.data.maintenance.tasks.AudioPurgeTask
import com.copiosa.data.maintenance.tasks.BookTranslateTask
import com.copiosa.data.maintenance.tasks.ChatRetryTask
import com.copiosa.data.maintenance.tasks.EnrichVocabTask
import com.copiosa.data.maintenance.tasks.FocusStatsRollupTask
import com.copiosa.data.maintenance.tasks.ReviewReminderTask
import com.copiosa.data.maintenance.tasks.TopicSuggestionTask
import com.copiosa.data.maintenance.tasks.VocabDueSnapshotTask
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
        vocabDueSnapshot: VocabDueSnapshotTask,
        reviewReminder: ReviewReminderTask,
        chatRetry: ChatRetryTask,
        topicSuggestions: TopicSuggestionTask,
        enrichVocab: EnrichVocabTask,
        bookTranslate: BookTranslateTask,
    ) : this(
        listOf(
            focusStatsRollup,
            audioPurge,
            vocabDueSnapshot,
            reviewReminder,
            chatRetry,
            topicSuggestions,
            enrichVocab,
            bookTranslate,
        ),
    )

    internal companion object {
        /** Test-only: run the runner against arbitrary fakes. */
        internal fun of(tasks: List<MaintenanceTask>) = MaintenanceTasks(tasks)
    }
}
