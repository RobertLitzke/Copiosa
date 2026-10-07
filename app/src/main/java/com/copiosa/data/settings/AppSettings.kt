package com.copiosa.data.settings

import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.OpenRouterModel
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeCountry
import com.copiosa.domain.model.ThemeMode
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.domain.srs.NewCardLimit
import com.copiosa.domain.srs.SpacedRepetitionScheduler

/** Non-secret user settings. API keys are stored separately (encrypted). */
data class AppSettings(
    val provider: ProviderId = ProviderId.GEMINI,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    /** The OpenRouter model new work goes to; always one of [openRouterModels]. */
    val openRouterModel: String = DEFAULT_OPENROUTER_MODEL,
    /**
     * Every OpenRouter model the learner has configured, in the order they entered them. They share
     * the one OpenRouter key, so this is purely a shortlist to pick from — per book (the download
     * dialog) or as the profile default ([openRouterModel]).
     */
    val openRouterModels: List<OpenRouterModel> = listOf(OpenRouterModel(DEFAULT_OPENROUTER_MODEL)),
    /**
     * The model for work nobody is waiting on — card enrichment, the cloze bank's upkeep, topic
     * suggestions, title translations — one per provider, like the main model. Blank means "the
     * same as the main model", which is how every profile starts: these calls are short and
     * structured, so a cheaper model does them as well, but only the learner knows which one that is.
     */
    val geminiBackgroundModel: String = "",
    /** As [geminiBackgroundModel]; when set, always one of [openRouterModels]. */
    val openRouterBackgroundModel: String = "",
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
    /** Days after playback that playlist audio is auto-deleted by maintenance; 0 = keep forever. */
    val autoDeletePlayedAfterDays: Int = 14,
    /**
     * FSRS target recall probability for this language's cards: the share of reviews you want to get
     * right. Higher means shorter intervals and more reviews. Clamped to
     * [SpacedRepetitionScheduler.MIN_RETENTION]..[SpacedRepetitionScheduler.MAX_RETENTION].
     */
    val desiredRetention: Double = SpacedRepetitionScheduler.DEFAULT_RETENTION,
    /**
     * How many never-studied cards this language's reviews take on per day; see [NewCardLimit].
     * Negative means no limit. Cards past the limit are not hidden or rescheduled — they keep
     * their place in the deck and simply wait for a later day's allowance.
     */
    val newCardsPerDay: Int = NewCardLimit.DEFAULT,
    /**
     * Whether background maintenance may spend LLM calls for this profile (topic suggestions,
     * auto-retrying failed chats). Local-only upkeep runs either way.
     */
    val backgroundLlmTasks: Boolean = true,
    /**
     * The conjugation drill's setup for this profile, as serialized
     * [com.copiosa.domain.conjugation.ConjugationDrillConfig]. Null until the learner opens the drill
     * — the screen then seeds a default from [defaultCountry], since vosotros is only worth drilling
     * in Spain.
     */
    val conjugationDrillConfigJson: String? = null,
    /**
     * Daily study-time goal in minutes for this profile. Drives the home tracker's target line;
     * days that meet or beat it are highlighted. Clamped to [DAILY_GOAL_MIN]..[DAILY_GOAL_MAX].
     */
    val dailyGoalMinutes: Int = DEFAULT_DAILY_GOAL_MINUTES,
    /**
     * Whether background maintenance may post a "cards are due" reminder notification for this
     * profile (at most ~daily). Off by default — turning it on prompts for notification permission.
     */
    val reviewReminders: Boolean = false,
    /**
     * Whether saved vocabulary due for review is tinted in reading text (chats, texts, books,
     * routine reading steps) for this language. The per-language default; an individual chat, text
     * or book can override it from its overflow menu (`highlightWords` on the row).
     */
    val highlightSavedWords: Boolean = true,
    /**
     * The download mode a newly imported book starts in. Only a *default* — every book carries its
     * own [com.copiosa.domain.model.BookDownloadMode] from then on, changed from its menu, so this
     * never overrides a book that has been set deliberately.
     */
    val bookDefaultDownloadMode: BookDownloadMode = BookDownloadMode.WHILE_READING,
    /**
     * Whether the reader's "N of M sections translated" banner has been dismissed. Once waved away
     * it stays gone for good — "Continue translating" remains in the reader's overflow menu.
     */
    val bookFrontierHintDismissed: Boolean = false,
) {
    /** The model string to send for [p]. */
    fun modelFor(p: ProviderId): String = when (p) {
        ProviderId.GEMINI -> geminiModel
        ProviderId.OPENROUTER -> openRouterModel
    }

    /** The model string to send for the currently selected provider. */
    val activeModel: String get() = modelFor(provider)

    /** The background slot as stored for [p]: blank when it follows the main model. */
    fun backgroundChoiceFor(p: ProviderId): String = when (p) {
        ProviderId.GEMINI -> geminiBackgroundModel
        ProviderId.OPENROUTER -> openRouterBackgroundModel
    }

    /**
     * The model background work on [p] should use: the slot when it is filled, otherwise [fallback].
     * The fallback is a parameter because a book or a text carries its own model, and that — not
     * the profile's — is what its title should fall back to.
     */
    fun backgroundModelFor(p: ProviderId, fallback: String = modelFor(p)): String =
        backgroundChoiceFor(p).ifBlank { fallback }

    /** The background model for the currently selected provider. */
    val backgroundModel: String get() = backgroundModelFor(provider)

    /**
     * How [model] should be named in the UI: the nickname the learner gave it, if any, else the
     * prettified id. Prefer this over [displayName] anywhere an [AppSettings] is already at hand —
     * a roster of OpenRouter ids is exactly the case nicknames exist for.
     */
    fun label(model: String): String =
        openRouterModels.firstOrNull { it.id == model }?.name?.takeIf { it.isNotBlank() }
            ?: displayName(model)

    companion object {
        // Speech-speed slider bounds: 50%–150%, defaulting to 100%.
        const val TTS_SPEED_MIN = 0.5f
        const val TTS_SPEED_MAX = 1.5f

        // Home-tracker daily study goal: bounds for the slider, defaulting to 10 minutes.
        const val DAILY_GOAL_MIN = 5
        const val DAILY_GOAL_MAX = 60
        const val DEFAULT_DAILY_GOAL_MINUTES = 10

        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
        // OpenRouter is free-text in the UI; this is just a cheap, stable starting default.
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"

        val GEMINI_MODELS = listOf(
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            // The cheap one: what the background slot is for on a provider with a fixed list.
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        )

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
