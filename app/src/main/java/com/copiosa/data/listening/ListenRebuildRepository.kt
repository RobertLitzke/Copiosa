package com.copiosa.data.listening

import com.copiosa.data.local.ClozeDao
import com.copiosa.domain.listening.ListenRebuild
import com.copiosa.domain.listening.RebuildItem
import com.copiosa.domain.model.Cloze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Listen-and-rebuild's data half: picking banked sentences and rendering them to speech.
 *
 * The sentences are the cloze bank's — already mined, gated for length and comprehensibility, and
 * drawn from the learner's own reading — so this activity costs no generation at all. It reads the
 * bank and never writes to it: hearing a sentence is a different skill from recalling its missing
 * word, and crediting one to the other's FSRS schedule would make both schedules lie.
 *
 * The clips themselves are [SentenceClipStore]'s, shared with the cloze bank's listening mode.
 */
@Singleton
class ListenRebuildRepository @Inject constructor(
    private val dao: ClozeDao,
    private val clips: SentenceClipStore,
) {
    /** Whether the on-device voice is installed — without it there is nothing to listen to. */
    fun voiceReady(): Boolean = clips.voiceReady()

    /** Up to [size] puzzles from the bank, clips not yet rendered. */
    suspend fun pick(
        lang: String,
        size: Int,
        seed: Long = System.currentTimeMillis(),
    ): List<RebuildItem> = withContext(Dispatchers.IO) {
        // Over-read: the tile gate refuses long sentences the cloze gate let through.
        dao.rebuildPool(lang, size * OVER_READ)
            .asSequence()
            .mapNotNull { row ->
                ListenRebuild.itemFor(row.id, Cloze.answer(row.text), row.translation, Random(seed xor row.id))
            }
            .take(size)
            .toList()
    }

    /**
     * [item] with its clip rendered (or found in the cache). Throws if synthesis fails; callers
     * decide whether a missing clip drops the item or the whole block.
     */
    suspend fun withClip(item: RebuildItem, lang: String): RebuildItem =
        item.copy(audioPath = clips.clipFor(item.sentenceId, item.sentence, lang).absolutePath)

    private companion object {
        const val OVER_READ = 4
    }
}
