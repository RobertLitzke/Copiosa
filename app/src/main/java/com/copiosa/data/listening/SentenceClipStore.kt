package com.copiosa.data.listening

import android.content.Context
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.SupertonicVoices
import com.copiosa.data.tts.TtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Banked sentences as speech: one short clip per sentence, rendered on demand and kept.
 *
 * Shared by everything that plays a cloze-bank sentence aloud — listen-and-rebuild and the cloze
 * bank's own listening mode — so a sentence heard in one is already rendered for the other.
 *
 * Clips live in the cache directory, keyed on text, language and voice, so a sentence heard twice is
 * synthesized once. The cache is regenerable and bounded ([MAX_CLIPS]); the OS may also clear it,
 * which costs a re-render and nothing else.
 */
@Singleton
class SentenceClipStore @Inject constructor(
    @ApplicationContext context: Context,
    private val tts: TtsEngine,
    private val modelStore: SupertonicModelStore,
) {
    private val clipDir: File by lazy { File(context.cacheDir, "rebuild_audio").apply { mkdirs() } }

    /** Whether the on-device voice is installed — without it there is nothing to listen to. */
    fun voiceReady(): Boolean = modelStore.isReady()

    /**
     * The clip for [sentence], rendered now or found in the cache. Throws if synthesis fails; callers
     * decide whether a missing clip drops the item or the whole block.
     */
    suspend fun clipFor(sentenceId: Long, sentence: String, lang: String): File {
        // A voice per sentence rather than the learner's chosen one: hearing several speakers is part
        // of what is being trained. Derived from the id, so a re-heard sentence hits the cache.
        val sid = (sentenceId % SupertonicVoices.NAMES.size).toInt()
        val out = File(clipDir, "${sha1("$sentence|$lang|$sid").take(24)}.m4a")
        if (out.exists() && out.length() > 0) {
            out.setLastModified(System.currentTimeMillis())
        } else {
            // Rendered to a side file and renamed, so a cancelled render never leaves a truncated
            // clip that the next sitting would mistake for a finished one.
            val partial = File(clipDir, out.name + ".part")
            tts.synthesizeToFile(sentence, lang, sid, partial)
            if (!partial.renameTo(out)) {
                partial.delete()
                error("Could not store clip for sentence $sentenceId")
            }
            trim()
        }
        return out
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
        /** A few dozen sittings' worth; at ~30 KB a clip this is single-digit megabytes. */
        const val MAX_CLIPS = 300
    }
}
