package com.copiosa.data.settings

import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeCountry
import com.copiosa.domain.model.ThemeMode
import com.copiosa.domain.prompt.PromptBuilder

/** Non-secret user settings. API keys are stored separately (encrypted). */
data class AppSettings(
    val provider: ProviderId = ProviderId.GEMINI,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val openRouterModel: String = DEFAULT_OPENROUTER_MODEL,
    val nativeLanguage: String = "en",
    val targetLanguage: String = "es",
    val defaultLevel: Cefr = Cefr.B2,
    val defaultCountry: String = "Mexico",
    /** Editable conversation system-prompt template (supports placeholders). */
    val corePrompt: String = PromptBuilder.DEFAULT_CORE_PROMPT,
    /** Extra instructions appended to the core prompt; placeholders are substituted here too. */
    val customInstructions: String = "",
    /** BCP-47 tag driving the app's own UI language; follows native language by default. */
    val appLanguage: String = "en",
    /** Light/dark theme preference; SYSTEM follows the OS setting. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Visual persona skin (chat layout, fonts, chrome); UNSTYLED = stock look. */
    val persona: Persona = Persona.UNSTYLED,
    /** Country palette/motif for the theme system; visual only, independent of [defaultCountry]. */
    val themeCountry: ThemeCountry = ThemeCountry.NONE,
    /** Typeface for reading text (chat replies, definitions). */
    val readingFont: ReadingFont = ReadingFont.SERIF,
    /** Size of reading text. */
    val readingTextSize: ReadingTextSize = ReadingTextSize.MEDIUM,
    /** Supertonic speaker id (0-based) for generated audio; auditioned in Settings. */
    val ttsVoiceSid: Int = 0,
    /** Playback speed for generated audio (1.0 = normal); clamped to [TTS_SPEED_MIN]..[TTS_SPEED_MAX]. */
    val ttsSpeed: Float = 1.0f,
    /** Days after which played playlist audio is auto-deleted at launch; 0 = keep forever. */
    val autoDeletePlayedAfterDays: Int = 0,
) {
    /** The model string to send for [p]. */
    fun modelFor(p: ProviderId): String = when (p) {
        ProviderId.GEMINI -> geminiModel
        ProviderId.OPENROUTER -> openRouterModel
    }

    /** The model string to send for the currently selected provider. */
    val activeModel: String get() = modelFor(provider)

    companion object {
        // Speech-speed slider bounds: 50%–150%, defaulting to 100%.
        const val TTS_SPEED_MIN = 0.5f
        const val TTS_SPEED_MAX = 1.5f

        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
        // OpenRouter is free-text in the UI; this is just a cheap, stable starting default.
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"

        val GEMINI_MODELS = listOf("gemini-3.5-flash", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")

        /**
         * Human-readable model name for chips/pills, e.g. "gemini-3.5-flash" -> "Gemini 3.5 Flash".
         * OpenRouter ids are `vendor/model`, which the prettifier would mangle, so they're shown raw.
         */
        fun displayName(model: String): String {
            if ('/' in model) return model
            val parts = model.split('-', '.')
            return buildString {
                parts.forEachIndexed { i, part ->
                    val isNum = part.isNotEmpty() && part.all { it.isDigit() }
                    val prevNum = i > 0 && parts[i - 1].all { it.isDigit() }
                    if (i > 0) append(if (isNum && prevNum) "." else " ")
                    append(part.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}
