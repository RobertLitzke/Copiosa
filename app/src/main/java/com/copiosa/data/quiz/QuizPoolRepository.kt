package com.copiosa.data.quiz

import android.content.Context
import com.copiosa.data.local.QuizCueEntity
import com.copiosa.data.local.QuizDao
import com.copiosa.data.local.QuizPoolEntity
import com.copiosa.data.tts.SupertonicVoices
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.GeneratedCue
import com.copiosa.domain.quiz.PhraseOptions
import com.copiosa.domain.quiz.QuizPoolConfig
import com.copiosa.domain.quiz.ReadyCue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Live progress of a language's pool, driving the management screen and the quiz's start gate. */
data class PoolProgress(
    val poolId: Long,
    val config: QuizPoolConfig?,
    val ready: Int,
    val failed: Int,
    val total: Int,
    val createdAt: Long,
)

/**
 * Storage for the rapid-fire listening pools. Wraps [QuizDao] and owns the rendered WAVs under
 * `filesDir/quiz_audio` (its own directory, separate from the playlist's). Files are content-keyed
 * (text+lang+voice+speed hash), so a rebuild whose config overlaps the old pool re-adopts matching
 * clips instead of re-synthesizing them; orphans are swept after every rebuild/delete.
 */
@Singleton
class QuizPoolRepository @Inject constructor(
    private val dao: QuizDao,
    @ApplicationContext context: Context,
) {
    val quizAudioDir: File by lazy { File(context.filesDir, "quiz_audio").apply { mkdirs() } }

    suspend fun poolFor(lang: String, type: CueType): QuizPoolEntity? = dao.pool(lang, type)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeProgress(lang: String, type: CueType): Flow<PoolProgress?> =
        dao.observePool(lang, type).flatMapLatest { pool ->
            if (pool == null) {
                flowOf(null)
            } else {
                combine(
                    dao.observeReadyCount(pool.id),
                    dao.observeFailedCount(pool.id),
                    dao.observeTotalCount(pool.id),
                ) { ready, failed, total ->
                    PoolProgress(pool.id, QuizPoolConfig.decode(pool.configJson), ready, failed, total, pool.createdAt)
                }
            }
        }

    suspend fun readyCues(lang: String, type: CueType): List<QuizCueEntity> =
        dao.pool(lang, type)?.let { dao.readyCues(it.id) } ?: emptyList()

    suspend fun readyCueCount(lang: String, type: CueType): Int =
        dao.pool(lang, type)?.let { dao.readyCount(it.id) } ?: 0

    /** Ready cues across both type pools, for the bedtime routine's single-topic rapid-fire block. */
    suspend fun readyCuesAllTypes(lang: String): List<QuizCueEntity> =
        CueType.entries.flatMap { readyCues(lang, it) }

    /** Total ready cues across a language's type pools, for the routine planner's availability gate. */
    suspend fun readyCueCountAllTypes(lang: String): Int =
        CueType.entries.sumOf { readyCueCount(lang, it) }

    suspend fun getCue(id: Long): QuizCueEntity? = dao.getCue(id)

    suspend fun unrendered(): List<QuizCueEntity> = dao.unrendered()

    suspend fun setStatus(id: Long, status: AudioStatus) = dao.setStatus(id, status)

    suspend fun setReady(id: Long, file: File, durationMs: Long?) =
        dao.setResult(id, AudioStatus.READY, file.absolutePath, durationMs, null)

    suspend fun setFailed(id: Long, error: String?) =
        dao.setResult(id, AudioStatus.FAILED, null, null, error)

    suspend fun retryFailed(lang: String, type: CueType) {
        dao.pool(lang, type)?.let { dao.retryFailed(it.id) }
    }

    /**
     * Replaces the [config] type's pool wholesale (the other type's pool is untouched): the old pool
     * row goes (cues cascade), the new seeded cues come in PENDING with a random voice each — except
     * cues whose content-keyed WAV already exists on disk, which enter READY directly. Returns the id.
     */
    suspend fun rebuildPool(config: QuizPoolConfig, cues: List<GeneratedCue>, random: Random = Random.Default): Long {
        dao.deletePool(config.langCode, config.cueType)
        val poolId = dao.insertPool(
            QuizPoolEntity(
                langCode = config.langCode,
                cueType = config.cueType,
                configJson = config.encode(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        dao.insertCues(
            cues.map { cue ->
                val sid = random.nextInt(SupertonicVoices.NAMES.size)
                val cached = cacheFile(cue.spokenText, config.langCode, sid)
                    .takeIf { it.exists() && it.length() > 0 }
                QuizCueEntity(
                    poolId = poolId,
                    langCode = config.langCode,
                    cueType = cue.type,
                    valueKey = cue.value.key(),
                    displayText = cue.displayText,
                    spokenText = cue.spokenText,
                    sid = sid,
                    status = if (cached != null) AudioStatus.READY else AudioStatus.PENDING,
                    filePath = cached?.absolutePath,
                    optionsJson = cue.options?.let { PhraseOptionsCodec.encode(it) },
                )
            },
        )
        sweepOrphans()
        return poolId
    }

    suspend fun deletePool(lang: String, type: CueType) {
        dao.deletePool(lang, type)
        sweepOrphans()
    }

    /** The content-keyed cache path for one rendering; stable across rebuilds. */
    fun cacheFile(spokenText: String, lang: String, sid: Int): File =
        File(quizAudioDir, "${sha1("$spokenText|$lang|$sid|$RENDER_SPEED").take(24)}.m4a")

    fun diskUsageBytes(): Long = quizAudioDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes rendered files no surviving cue row references. */
    private suspend fun sweepOrphans() {
        val referenced = dao.allFilePaths().toSet()
        quizAudioDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in referenced) runCatching { file.delete() }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * Quiz clips always render at natural speed — training rapid parsing is the point, and a
         * fixed speed keeps the content-keyed cache valid regardless of the playlist speed setting.
         */
        const val RENDER_SPEED = 1.0f
    }
}

/** Projection for the domain-layer session builder (which never sees Room types). */
fun QuizCueEntity.toReadyCue(): ReadyCue? {
    val path = filePath ?: return null
    return ReadyCue(
        id = id,
        type = cueType,
        valueKey = valueKey,
        displayText = displayText,
        filePath = path,
        durationMs = durationMs,
        options = optionsJson?.let { PhraseOptionsCodec.decode(it) },
    )
}

/** Serializes [PhraseOptions] for the quiz_cues.optionsJson column. Lenient decode never throws. */
object PhraseOptionsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(options: PhraseOptions): String = json.encodeToString(PhraseOptions.serializer(), options)

    fun decode(stored: String): PhraseOptions? =
        runCatching { json.decodeFromString(PhraseOptions.serializer(), stored) }.getOrNull()
}
