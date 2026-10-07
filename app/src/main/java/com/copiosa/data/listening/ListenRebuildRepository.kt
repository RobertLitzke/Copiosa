package com.copiosa.data.listening

import android.content.Context
import com.copiosa.data.local.ClozeDao
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.SupertonicVoices
import com.copiosa.data.tts.TtsEngine
import com.copiosa.domain.listening.ListenRebuild
import com.copiosa.domain.listening.RebuildItem
import com.copiosa.domain.model.Cloze
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
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
 * Clips are rendered on demand into the cache directory, keyed on text, language and voice, so a
 * sentence heard twice is synthesized once. The cache is regenerable and bounded ([MAX_CLIPS]); the
 * OS may also clear it, which costs a re-render and nothing else.
 */
@Singleton
class ListenRebuildRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: ClozeDao,
    private val tts: TtsEngine,
    private val modelStore: SupertonicModelStore,
) {
    private val clipDir: File by lazy { File(context.cacheDir, "rebuild_audio").apply { mkdirs() } }

    /** Whether the on-device voice is installed — without it there is nothing to listen to. */
    fun voiceReady(): Boolean = modelStore.isReady()

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
    suspend fun withClip(item: RebuildItem, lang: String): RebuildItem {
        // A voice per sentence rather than the learner's chosen one: hearing several speakers is part
        // of what is being trained. Derived from the id, so a re-heard sentence hits the cache.
        val sid = (item.sentenceId % SupertonicVoices.NAMES.size).toInt()
        val out = File(clipDir, "${sha1("${item.sentence}|$lang|$sid").take(24)}.m4a")
        if (out.exists() && out.length() > 0) {
            out.setLastModified(System.currentTimeMillis())
        } else {
            // Rendered to a side file and renamed, so a cancelled render never leaves a truncated
            // clip that the next sitting would mistake for a finished one.
            val partial = File(clipDir, out.name + ".part")
            tts.synthesizeToFile(item.sentence, lang, sid, partial)
            if (!partial.renameTo(out)) {
                partial.delete()
                error("Could not store clip for sentence ${item.sentenceId}")
            }
            trim()
        }
        return item.copy(audioPath = out.absolutePath)
    }

    /** Drops the least recently heard clips past [MAX_CLIPS]. */
    private fun trim() {
        val clips = clipDir.listFiles { f -> f.extension == "m4a" } ?: return
        if (clips.size <= MAX_CLIPS) return
        clips.sortedBy { it.lastModified() }
            .take(clips.size - MAX_CLIPS)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val OVER_READ = 4

        /** A few dozen sittings' worth; at ~30 KB a clip this is single-digit megabytes. */
        const val MAX_CLIPS = 300
    }
}
