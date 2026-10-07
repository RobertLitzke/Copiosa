package com.copiosa.data.quiz

import android.util.Log
import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.remote.tryLlm
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.Role
import com.copiosa.domain.quiz.CueGenerators
import com.copiosa.domain.quiz.CueKind
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.CueValue
import com.copiosa.domain.quiz.GeneratedCue
import com.copiosa.domain.quiz.NumberSpeller
import com.copiosa.domain.quiz.PhraseOptions
import com.copiosa.domain.quiz.PhraseScenario
import com.copiosa.domain.quiz.PhraseScenarios
import com.copiosa.domain.quiz.QuizPoolConfig
import com.copiosa.domain.usage.LlmFeature
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
        // Phrase scenarios (Restaurant, …) are LLM-generated end to end — their pool and answer options
        // come from the model, not an algorithmic generator — so they take a different seed path.
        if (type.kind == CueKind.PHRASE) return seedPhrases(config, random)
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
                val spoken = tryLlm({ Log.w(TAG, "Phrasing batch failed", it) }) {
                    requestPhrasings(type, languageName, byKey.keys, settings.activeModel)
                } ?: emptyMap()
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
                feature = LlmFeature.QUIZ_POOL,
                maxTokens = BATCH_MAX_TOKENS,
                json = JsonShape.OBJECT,
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

        // Phrase scenarios seed through seedPhrases(), never this algorithmic phrasing path.
        CueType.RESTAURANT -> error("phrase cue types do not use algorithmic phrasing prompts")
    }

    /**
     * Seeds a phrase-scenario pool (Restaurant, …) by asking the LLM for whole target-language
     * sentences plus their native answer options, paginated across several calls until [poolSize]
     * unique phrases are collected (or a call cap is hit). Duplicates — the model drifts toward the
     * same handful of intents — are dropped by normalized phrase text, and each call is told which
     * phrases it has already produced so later pages explore new ground. Phrase pools are LLM-only:
     * with no API key there is nothing to fall back to, so the pool comes back empty.
     */
    private suspend fun seedPhrases(config: QuizPoolConfig, random: Random): SeedResult {
        val scenario = PhraseScenarios.forType(config.cueType)
            ?: return SeedResult(emptyList(), skippedCount = 0, noKey = false)

        val settings = settingsRepo.settings.first()
        if (!settingsRepo.hasApiKey(settings.provider)) {
            return SeedResult(emptyList(), skippedCount = config.poolSize, noKey = true)
        }

        val targetName = displayLanguage(config.langCode)
        val nativeName = displayLanguage(settings.nativeLanguage)
        val system = phraseSystemPrompt(scenario, targetName, nativeName)

        val cues = mutableListOf<GeneratedCue>()
        val seen = HashSet<String>()
        var skipped = 0
        var calls = 0
        while (cues.size < config.poolSize && calls < PHRASE_MAX_CALLS) {
            calls++
            val avoid = cues.takeLast(PHRASE_AVOID_SAMPLE).map { it.spokenText }
            val batch = tryLlm({ Log.w(TAG, "Phrase batch failed", it) }) {
                requestPhrases(system, avoid, PHRASE_BATCH_SIZE, settings.activeModel)
            } ?: emptyList()
            // A page that yields nothing new (parse failure, or the model only echoed avoided phrases)
            // ends the run rather than spinning out the remaining call budget on empty responses.
            var addedThisCall = 0
            for (entry in batch) {
                if (cues.size >= config.poolSize) break
                val phrase = entry.phrase.trim()
                val correct = entry.correct.trim()
                val distractors = entry.distractors.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                if (!validPhrase(phrase, correct, distractors) || !seen.add(normalize(phrase))) {
                    if (phrase.isNotBlank()) skipped++
                    continue
                }
                cues += GeneratedCue(
                    type = config.cueType,
                    value = CueValue.Phrase(phraseId(phrase)),
                    spokenText = phrase,
                    displayText = correct,
                    options = PhraseOptions(correct, distractors.take(PHRASE_DISTRACTORS)),
                )
                addedThisCall++
            }
            if (addedThisCall == 0) break
        }
        return SeedResult(cues.shuffled(random), skippedCount = skipped, noKey = false)
    }

    private suspend fun requestPhrases(
        system: String,
        avoid: List<String>,
        count: Int,
        model: String,
    ): List<PhraseBatchParser.Entry> {
        val user = buildString {
            append("Generate $count NEW phrases now.")
            if (avoid.isNotEmpty()) {
                append(" Do NOT repeat any of these already-generated phrases:\n")
                avoid.forEach { append("- ").append(it).append('\n') }
            }
        }
        val raw = providers.active().chat(
            LlmRequest(
                systemPrompt = system,
                messages = listOf(LlmMessage(Role.USER, user)),
                model = model,
                feature = LlmFeature.QUIZ_POOL,
                maxTokens = PHRASE_MAX_TOKENS,
                json = JsonShape.OBJECT,
            ),
        ).text
        return PhraseBatchParser.parse(raw)
    }

    private fun phraseSystemPrompt(scenario: PhraseScenario, targetName: String, nativeName: String): String = """
        You write listening-quiz items for a $targetName learner whose native language is $nativeName.
        Each item is one short spoken phrase the learner would HEAR, plus one correct $nativeName
        translation and four wrong ones.

        The phrases are ${scenario.settingPrompt}

        Rules for each item:
        - "phrase": the line as a native $targetName speaker would actually say it, one sentence, no digits.
        - "correct": its faithful $nativeName translation.
        - "distractors": exactly four DIFFERENT $nativeName phrases. Each must be a plausible translation
          of a DIFFERENT thing that sounds similar or would be said at a similar moment — never a
          paraphrase or synonym of the correct one. A learner who mishears should find them tempting.
        - Keep every phrase distinct from the others in the batch; vary intent, wording, and formality.

        Output ONLY a JSON object, no other text:
        {"phrases":[{"phrase":"<$targetName sentence>","correct":"<$nativeName>","distractors":["<$nativeName>","<$nativeName>","<$nativeName>","<$nativeName>"]}, ...]}
    """.trimIndent()

    /** A usable phrase item: non-blank phrase and correct answer, and at least four distinct distractors. */
    private fun validPhrase(phrase: String, correct: String, distractors: List<String>): Boolean =
        phrase.isNotBlank() && phrase.length <= MAX_SENTENCE_CHARS &&
            correct.isNotBlank() && distractors.size >= PHRASE_DISTRACTORS

    private fun displayLanguage(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    /** Case/space-insensitive key for dedup across pages. */
    private fun normalize(phrase: String): String = phrase.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Stable per-phrase id for the cue's valueKey (pool identity/dedup). */
    private fun phraseId(phrase: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(normalize(phrase).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** A usable phrasing: non-blank, contains no digits, plausibly short. */
    private fun valid(spoken: String): Boolean =
        spoken.isNotBlank() && spoken.length <= MAX_PHRASE_CHARS && spoken.none { it.isDigit() }

    private companion object {
        const val TAG = "QuizPoolSeeder"
        const val BATCH_SIZE = 40
        const val BATCH_MAX_TOKENS = 2048
        // Generous enough for a spelled-out large number ("neunhundert…tausend…") as well as a time.
        const val MAX_PHRASE_CHARS = 140

        // Phrase-scenario seeding (Restaurant, …).
        const val PHRASE_BATCH_SIZE = 25
        const val PHRASE_MAX_CALLS = 20
        const val PHRASE_MAX_TOKENS = 4096
        const val PHRASE_DISTRACTORS = 4
        // How many recent phrases to echo back as "don't repeat these" (keeps the prompt bounded).
        const val PHRASE_AVOID_SAMPLE = 40
        // A full spoken sentence — longer than a spelled number, but still bounded.
        const val MAX_SENTENCE_CHARS = 200
    }
}

/**
 * Parses the phrase-batch response — same forgiving spirit as [PhrasingParser]: isolate the outer
 * object, tolerate light garbling, never throw. Malformed entries are simply absent from the result.
 */
internal object PhraseBatchParser {

    data class Entry(val phrase: String, val correct: String, val distractors: List<String>)

    @Serializable
    private data class EntryDto(
        val phrase: String = "",
        val correct: String = "",
        val distractors: List<String> = emptyList(),
    )

    @Serializable
    private data class PhrasesDto(val phrases: List<EntryDto> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<Entry> {
        val obj = isolateObject(raw) ?: return emptyList()
        val dto = listOf(obj, repairJson(obj)).firstNotNullOfOrNull { tryDecode(it) } ?: return emptyList()
        return dto.phrases
            .map { Entry(it.phrase.trim(), it.correct.trim(), it.distractors.map(String::trim)) }
            .filter { it.phrase.isNotBlank() && it.correct.isNotBlank() }
    }

    private fun isolateObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    private fun tryDecode(jsonText: String): PhrasesDto? =
        runCatching { json.decodeFromString(PhrasesDto.serializer(), jsonText) }
            .getOrNull()
            ?.takeIf { it.phrases.isNotEmpty() }

    private fun repairJson(jsonText: String): String =
        jsonText
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace(Regex(""",(\s*[}\]])"""), "$1")
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
