package com.copiosa.data.generation

import android.util.Log
import com.copiosa.data.local.AudioItemDao
import com.copiosa.data.local.QuizDao
import com.copiosa.data.quiz.QuizPoolRepository
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.tts.M4aWriter
import com.copiosa.data.tts.WavPcm
import com.copiosa.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time migration: re-encodes the uncompressed WAVs that TTS produced before [M4aWriter]
 * existed into AAC (roughly 12x smaller) and repoints their rows. Runs once per app start from
 * [com.copiosa.CopiosaApp]; after everything is converted the DB queries come back empty and
 * this is a no-op.
 *
 * Stored paths are absolute, so rows restored from another install (e.g. the com.languageaid ->
 * com.copiosa data migration) can point into a package directory that no longer exists; a source
 * WAV that's missing at its stored path is re-resolved by filename in this install's own directory
 * before giving up on the row.
 */
@Singleton
class AudioCompactor @Inject constructor(
    private val audioItemDao: AudioItemDao,
    private val quizDao: QuizDao,
    private val audioRepository: AudioRepository,
    private val quizPoolRepository: QuizPoolRepository,
    @AppScope private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            var savedBytes = 0L
            var converted = 0
            for (item in audioItemDao.legacyWavItems()) {
                val wav = resolve(item.filePath ?: continue, audioRepository.audioDir) ?: continue
                savedBytes += convert(wav) { newPath ->
                    audioItemDao.setResult(item.id, item.status, newPath, item.durationMs, null)
                    converted++
                } ?: continue
            }
            for (cue in quizDao.legacyWavCues()) {
                val wav = resolve(cue.filePath ?: continue, quizPoolRepository.quizAudioDir) ?: continue
                savedBytes += convert(wav) { newPath ->
                    quizDao.setResult(cue.id, cue.status, newPath, cue.durationMs, null)
                    converted++
                } ?: continue
            }
            if (converted > 0) {
                Log.i(TAG, "Re-encoded $converted legacy WAVs to AAC, saved ${savedBytes / 1_000_000}MB")
            }
        }
    }

    /** The stored file if it exists, else the same filename under this install's [dir], else null. */
    private fun resolve(storedPath: String, dir: File): File? {
        val stored = File(storedPath)
        if (stored.exists()) return stored
        return File(dir, stored.name).takeIf { it.exists() }
    }

    /**
     * Transcodes [wav] to a sibling .m4a, invokes [update] with the new path, then deletes the
     * WAV. Returns bytes saved, or null when the source is unreadable (the row is left alone;
     * playback already tolerates missing files).
     */
    private suspend fun convert(wav: File, update: suspend (String) -> Unit): Long? {
        val audio = WavPcm.read(wav) ?: return null
        val m4a = File(wav.parentFile, wav.name.removeSuffix(".wav") + ".m4a")
        try {
            M4aWriter.write(m4a, listOf(audio.samples), audio.sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-encode ${wav.name}", e)
            m4a.delete()
            return null
        }
        val saved = wav.length() - m4a.length()
        update(m4a.absolutePath)
        wav.delete()
        return saved
    }

    private companion object {
        const val TAG = "AudioCompactor"
    }
}
