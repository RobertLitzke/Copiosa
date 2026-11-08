package com.copiosa.data.maintenance.tasks

import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.cloze.sources.BookSentenceSource
import com.copiosa.data.cloze.sources.ContextSentenceSource
import com.copiosa.data.cloze.sources.ConversationSentenceSource
import com.copiosa.data.cloze.sources.DictionaryExampleSource
import com.copiosa.data.maintenance.MaintenanceTask
import com.copiosa.data.maintenance.TaskContext
import com.copiosa.data.maintenance.TaskScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the cloze bank stocked from material the learner already owns — the pages of their books
 * they have actually read, their imported texts and chats, the dictionary pack's examples, and their
 * cards' own example sentences.
 *
 * Entirely local: no network, no model, no tokens. That is why it can run on a short cadence where
 * the LLM-spending tasks cannot, and why it sits before `enrichVocab` in the registry, which orders
 * cheap local upkeep first.
 *
 * Reading comes first in the source order on purpose. A card's own example is the one sentence the
 * learner has already seen that word in, so it is the weakest practice the bank can offer; a sentence
 * from last night's chapter is the strongest, and when a run's room is short it should go to those.
 *
 * Idempotent by construction rather than by bookkeeping: banking inserts with IGNORE against the
 * unique (language, lemma, textKey) index, so re-reading the same chapter every few hours costs one
 * index probe per candidate and adds nothing. Nothing needs to remember how far it got. A full bank
 * does not stop it — the repository rotates settled words' sentences out to make room.
 */
@Singleton
class ClozeMineTask @Inject constructor(
    private val cloze: ClozeRepository,
    private val contextSentences: ContextSentenceSource,
    private val dictionaryExamples: DictionaryExampleSource,
    private val bookSentences: BookSentenceSource,
    private val conversationSentences: ConversationSentenceSource,
) : MaintenanceTask {

    override val id = "cloze_mine"

    /**
     * The bank is per language and only the foreground profile's is ever looked at, so mining every
     * profile would multiply the work for banks nobody opens.
     */
    override val scope = TaskScope.ACTIVE_PROFILE

    override val needsLlm = false

    override val minIntervalMs: Long = TimeUnit.HOURS.toMillis(6)

    override suspend fun run(ctx: TaskContext) {
        val mineContext = cloze.buildContext(ctx.profileTag, ctx.now)
        // Nothing to hang a sentence on, and nothing to judge one against.
        if (mineContext.formToCard.isEmpty()) return

        cloze.mine(
            sources = listOf(bookSentences, conversationSentences, dictionaryExamples, contextSentences),
            ctx = mineContext,
            limitPerSource = PER_SOURCE_PER_RUN,
            now = ctx.now,
        )
        // Sentences banked before the translator was part of mining, or while it was unavailable.
        cloze.translateLocally(ctx.profileTag, TRANSLATIONS_PER_RUN)
    }

    private companion object {
        /**
         * Candidates each source may offer per run. Deliberately a trickle: the bank is a pool to dip
         * into, not a queue to work off, and a slow fill spreads across the learner's reading instead
         * of freezing a snapshot of it.
         */
        const val PER_SOURCE_PER_RUN = 20

        /** On-device translations back-filled per run; each is a few tens of milliseconds. */
        const val TRANSLATIONS_PER_RUN = 80
    }
}
