package com.copiosa.data.maintenance.tasks

import com.copiosa.data.cloze.ClozeAdjudicator
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.cloze.sources.LlmClozeSource
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import com.copiosa.domain.cloze.ClozeMineContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cloze bank's only LLM spend, and it makes **at most one call per run**.
 *
 * The call does one of two jobs, never both, which is what keeps that promise:
 *
 * 1. **Adjudication first.** Items the learner flagged as having more than one answer are checked,
 *    and either get replacement options or are set aside. A question that has actually annoyed
 *    someone is worth more than a new one.
 * 2. **Generation otherwise.** Words with no sentence at all — nothing in their card's context
 *    field, nothing in the dictionary, nothing in the books they've read — get one written.
 *
 * Gated behind the per-profile background-LLM toggle like every other spending task, and on a day
 * cadence, because the local miner is what actually keeps the bank stocked.
 */
@Singleton
class ClozeGenerateTask @Inject constructor(
    private val cloze: ClozeRepository,
    private val adjudicator: ClozeAdjudicator,
    private val llmSource: LlmClozeSource,
) : MaintenanceTask {

    override val id = "cloze_generate"

    override val scope = TaskScope.ACTIVE_PROFILE

    override val needsLlm = true

    override val minIntervalMs: Long = TimeUnit.DAYS.toMillis(1)

    override suspend fun run(ctx: TaskContext) {
        // Spend the run's one call on a real complaint before a speculative sentence.
        if (adjudicator.run(ctx.profileTag) > 0) return

        val mineContext = cloze.buildContext(ctx.profileTag)
        if (mineContext.formToCard.isEmpty()) return
        if (mineContext.bankSize >= ClozeMineContext.BANK_MAX_PER_LANGUAGE) return

        cloze.mine(
            sources = listOf(llmSource),
            ctx = mineContext,
            limitPerSource = PER_RUN,
            now = ctx.now,
        )
    }

    private companion object {
        /** Matches the source's own batch size: one request, ten words. */
        const val PER_RUN = 10
    }
}
