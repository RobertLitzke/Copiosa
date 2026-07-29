package com.copiosa.data.maintenance.tasks

import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.data.repository.FlashcardRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backstop for the auto-enrichment that runs when a card is saved: fills in the explanation,
 * gender, CEFR level and example for cards that missed it — saved offline, saved while background
 * LLM tasks were switched off, or imported from a deck that carried none.
 *
 * Without this a card that slips through stays bare forever unless the learner finds the bulk
 * "fill" button, which quietly costs them article tinting and the gender swipe-sort pool.
 *
 * Capped at [MAX_BATCHES] calls per run so a large backlog is worked off over several days rather
 * than in one burst; the runner's cadence and budget gates do the rest.
 */
@Singleton
class EnrichVocabTask @Inject constructor(
    private val flashcards: FlashcardRepository,
) : MaintenanceTask {

    override val id = "enrich_vocab"
    override val scope = TaskScope.ACTIVE_PROFILE
    override val needsLlm = true
    override val minIntervalMs: Long = TimeUnit.DAYS.toMillis(1)

    override suspend fun run(ctx: TaskContext) {
        flashcards.fillExplanations(maxBatches = MAX_BATCHES)
    }

    private companion object {
        const val MAX_BATCHES = 2
    }
}
