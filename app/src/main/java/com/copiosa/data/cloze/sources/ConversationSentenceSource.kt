package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.ClozeDao
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.model.Mode
import com.copiosa.domain.text.Sentences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mines the learner's other reading: imported texts and the tutor's replies in their chats.
 *
 * Books were the only reading the bank drew on, which left out most of what is actually read in the
 * app day to day. Both of these are stored the same way — target-language prose in an assistant
 * message — so one source covers them, labelled `text:<id>` or `chat:<id>` by where the sentence
 * came from.
 *
 * Only conversations the learner has opened: a batch-generated chat still waiting to be read is not
 * material they have met. There is no translation to carry — a text's original is aligned to it only
 * loosely, a chat reply has none — so these are banked without, and the on-device translator fills
 * the gap at the point of banking.
 */
@Singleton
class ConversationSentenceSource @Inject constructor(
    private val dao: ClozeDao,
) : ClozeSource {

    override val id = "chat"

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        val carded = ArrayList<ClozeCandidate>(limit)
        val uncarded = ArrayList<ClozeCandidate>()
        val proposed = HashSet<String>()
        // Asked for separately: chats are written daily and texts imported now and then, so one
        // newest-first list would be all chat and no text would ever be reached.
        val replies = dao.readReplies(ctx.lang, Mode.URL_TRANSLATION, TEXTS_PER_RUN) +
            dao.readReplies(ctx.lang, Mode.CONVERSATION, REPLIES_PER_RUN)
        for (reply in replies) {
            if (carded.size >= limit) break
            val source = (if (reply.mode == Mode.URL_TRANSLATION) "text" else "chat") + ":" + reply.conversationId
            var scanned = 0
            for (sentence in sentencesOf(reply.content)) {
                if (carded.size >= limit || scanned++ >= SENTENCES_PER_REPLY) break
                if (!SentenceBlanks.comfortable(sentence)) continue
                val picked = SentenceBlanks.pick(sentence, ctx, source, translation = null, proposed) ?: continue
                if (picked.carded) carded += picked.candidate else uncarded += picked.candidate
            }
        }
        return carded.take(limit) + uncarded.take(maxOf(1, limit / UNCARDED_SHARE))
    }

    /**
     * Prose sentences out of a markdown message. Headings, tables and code are skipped whole —
     * a heading is not a sentence and a table row is not prose — and inline emphasis is removed,
     * because a blank must not arrive wearing the asterisks that marked it as the word to notice.
     */
    private fun sentencesOf(markdown: String): Sequence<String> =
        markdown.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("|") && !it.startsWith("```") }
            .map { line -> line.replace(LIST_MARKER, "").replace(EMPHASIS, "") }
            .flatMap { Sentences.split(it).asSequence() }
            .map(SentenceBlanks::clean)

    private companion object {
        /** Recent chat replies to scan per pass. */
        const val REPLIES_PER_RUN = 60

        /** Recent imported texts to scan per pass; each is one long message. */
        const val TEXTS_PER_RUN = 10

        /** A long imported article must not take a whole pass to itself. */
        const val SENTENCES_PER_REPLY = 150

        const val UNCARDED_SHARE = 4

        val LIST_MARKER = Regex("^(?:[-*+>]|\\d+[.)])\\s+")
        val EMPHASIS = Regex("[*_`]+")
    }
}
