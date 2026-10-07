package com.copiosa.data.maintenance.tasks

import com.copiosa.data.cloze.ClozeTranslator
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives banked sentences the translation the local miners had none to give.
 *
 * Ten sentences per run on a six-hour floor, which is a deliberate trickle rather than a sweep: a
 * back-fill is not urgent — an untranslated sentence is still answerable — and a bank that fills from
 * the learner's own reading grows continuously, so this is a standing drip rather than a one-off
 * migration that finishes. Faster passes would spend a day's budget clearing a backlog nobody is
 * waiting on.
 *
 * Ordered after [ClozeGenerateTask] for the same reason it is slow: writing a sentence for a word
 * with none beats explaining one that already works.
 */
@Singleton
class ClozeTranslateTask @Inject constructor(
    private val translator: ClozeTranslator,
) : MaintenanceTask {

    override val id = "cloze_translate"

    override val scope = TaskScope.ACTIVE_PROFILE

    override val needsLlm = true

    override val minIntervalMs: Long = TimeUnit.HOURS.toMillis(6)

    override suspend fun run(ctx: TaskContext) {
        translator.run(ctx.profileTag)
    }
}
