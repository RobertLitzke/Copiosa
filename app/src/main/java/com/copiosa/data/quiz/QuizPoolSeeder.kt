package com.copiosa.data.quiz

import android.util.Log
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.Role
import com.copiosa.domain.quiz.CueGenerators
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.CueValue
import com.copiosa.domain.quiz.GeneratedCue
import com.copiosa.domain.quiz.NumberSpeller
import com.copiosa.domain.quiz.QuizPoolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Produces the seeded cue texts a pool build renders: samples values per cue type, phrases what it can
 * locally (numbers up to 1000; numeric-style times), and sends the rest — colloquial time phrasings and
 * larger numbers, the part an LLM does far better than templates — to the active provider in batched
 * calls. A cue with no usable LLM phrasing (no key, or a failed/invalid response) is dropped rather than
 * approximated, so a bad phrasing never pollutes the pool; [SeedResult.skippedCount]/[SeedResult.noKey]
 * let the UI say how many were dropped.
 */
@Singleton
class QuizPoolSeeder @Inject constructor(
    private val providers: ProviderRegistry,
    private val settingsRepo: SettingsRepository,
    private val speller: NumberSpeller,
) {
    data class SeedResult(
        val cues: List<GeneratedCue>,
        /**
         * Cues that needed LLM phrasing but were dropped — no key, or the model returned nothing
         * usable. They are skipped rather than approximated locally, so a bad phrasing can never
         * pollute the pool permanently.
         */
        val skippedCount: Int,
        /** True when no API key was configured, so LLM-only cues were dropped without an attempt. */
        val noKey: Boolean,
    )

    suspend fun seed(config: QuizPoolConfig, random: Random = Random.Default): SeedResult {
        // One pool = one cue type; the whole pool budget goes to it.
        val type = config.cueType
        val phrased = mutableListOf<GeneratedCue>()
        val needLlm = mutableListOf<Pair<CueType, CueValue>>()

        val generator = CueGenerators.forType(type)
        for (value in generator.generateValues(config.poolSize, config, random)) {
            val local = generator.localSpokenText(value, config, speller, random)
            if (local != null) {
                phrased += GeneratedCue(type, value, local, generator.displayText(value))
            } else {
                needLlm += type to value
            }
        }
        if (needLlm.isEmpty()) return SeedResult(phrased, skippedCount = 0, noKey = false)

        val settings = settingsRepo.settings.first()
        val hasKey = settingsRepo.hasApiKey(settings.provider)
        // No key: drop the LLM-only cues wholesale rather than seed the pool with a local approximation.
        if (!hasKey) return SeedResult(phrased.shuffled(random), skippedCount = needLlm.size, noKey = true)

        var skipped = 0
        val languageName =
            Locale.forLanguageTag(config.langCode).getDisplayLanguage(Locale.ENGLISH).ifBlank { config.langCode }
        // Batch per cue type: colloquial times and large numbers each need their own prompt.
        for ((type, group) in needLlm.groupBy { it.first }) {
            for (batch in group.chunked(BATCH_SIZE)) {
                val byKey = batch.associate { (_, value) -> value.key() to value }
                val spoken = runCatching { requestPhrasings(type, languageName, byKey.keys, settings.activeModel) }
                    .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Phrasing batch failed", it) }
                    .getOrDefault(emptyMap())
                val generator = CueGenerators.forType(type)
                for ((key, value) in byKey) {
                    val text = spoken[key]?.takeIf { valid(it) }
                    // A cue with no usable phrasing is dropped, never approximated.
                    if (text != null) phrased += GeneratedCue(type, value, text, generator.displayText(value)) else skipped++
                }
            }
        }
        return SeedResult(phrased.shuffled(random), skippedCount = skipped, noKey = false)
    }

    private suspend fun requestPhrasings(
        type: CueType,
        languageName: String,
        keys: Set<String>,
        model: String,
    ): Map<String, String> {
        val raw = providers.active().chat(
            LlmRequest(
                systemPrompt = systemPrompt(type, languageName),
                messages = listOf(LlmMessage(Role.USER, "${type.label}: ${keys.joinToString(", ")}")),
                model = model,
                maxTokens = BATCH_MAX_TOKENS,
            ),
        ).text
        return PhrasingParser.parse(raw)
    }

    private fun systemPrompt(type: CueType, languageName: String): String = when (type) {
        CueType.TIME -> """
            You are a native $languageName speaker. For each 12-hour clock time given as HH:MM, write
            exactly how a native would casually SAY that time aloud in $languageName — the everyday
            colloquial form (quarter/half/to idioms where natural), not a formal reading. Rules:
            - Words only. NEVER use digits, and no am/pm or day-part qualifiers.
            - One short phrase per time, exactly as it would be spoken.
            Output ONLY a JSON object, no other text:
            {"phrasings":[{"key":"06:15","spoken":"<how a native says 6:15>"}, ...]}
            Include every requested time under its exact key.
        """.trimIndent()

        CueType.NUMBER -> """
            You are a native $languageName speaker. For each whole number given as digits, write exactly
            how a native would SAY that cardinal number aloud in $languageName. Rules:
            - Words only. NEVER use digits, and no currency, ordinals, or "and"-padding beyond the norm.
            - One phrase per number, exactly as it would be spoken.
            Output ONLY a JSON object, no other text:
            {"phrasings":[{"key":"1234","spoken":"<how a native says 1234>"}, ...]}
            Include every requested number under its exact key (the digits).
        """.trimIndent()
    }

    /** A usable phrasing: non-blank, contains no digits, plausibly short. */
    private fun valid(spoken: String): Boolean =
        spoken.isNotBlank() && spoken.length <= MAX_PHRASE_CHARS && spoken.none { it.isDigit() }

    private companion object {
        const val TAG = "QuizPoolSeeder"
        const val BATCH_SIZE = 40
        const val BATCH_MAX_TOKENS = 2048
        // Generous enough for a spelled-out large number ("neunhundert…tausend…") as well as a time.
        const val MAX_PHRASE_CHARS = 140
    }
}

/**
 * Parses the phrasing batch response — same forgiving spirit as RoutineContentParser: isolate the
 * outer object, tolerate light garbling, never throw.
 */
internal object PhrasingParser {

    @Serializable
    private data class PhraseDto(val key: String = "", val spoken: String = "")

    @Serializable
    private data class PhrasingsDto(val phrasings: List<PhraseDto> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): Map<String, String> {
        val obj = isolateObject(raw) ?: return emptyMap()
        val dto = listOf(obj, repairJson(obj)).firstNotNullOfOrNull { tryDecode(it) } ?: return emptyMap()
        return dto.phrasings
            .filter { it.key.isNotBlank() && it.spoken.isNotBlank() }
            .associate { it.key to it.spoken.trim() }
    }

    private fun isolateObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    private fun tryDecode(jsonText: String): PhrasingsDto? =
        runCatching { json.decodeFromString(PhrasingsDto.serializer(), jsonText) }
            .getOrNull()
            ?.takeIf { it.phrasings.isNotEmpty() }

    private fun repairJson(jsonText: String): String =
        jsonText
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace(Regex(""",(\s*[}\]])"""), "$1")
}
