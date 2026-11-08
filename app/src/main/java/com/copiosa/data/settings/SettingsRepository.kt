package com.copiosa.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.ModelOption
import com.copiosa.domain.model.OpenRouterModels
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeCountry
import com.copiosa.domain.model.ThemeMode
import com.copiosa.data.maintenance.ProfileSettingsSource
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(ProfileKeyMigration) },
)

/**
 * Reads/writes non-secret settings via DataStore and API keys via EncryptedSharedPreferences.
 *
 * Settings are stored per language profile. A profile is identified by its target-language
 * tag ("es", "fr", ...); each profile's fields live under "p.{tag}.{field}" keys, with two
 * global keys tracking the profile set and the active selection. API keys are global.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProfileSettingsSource {
    internal object Keys {
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val PROFILES = stringSetPreferencesKey("profiles")

        fun provider(tag: String) = stringPreferencesKey("p.$tag.provider")
        fun geminiModel(tag: String) = stringPreferencesKey("p.$tag.gemini_model")
        fun openRouterModel(tag: String) = stringPreferencesKey("p.$tag.openrouter_model")

        /** The configured OpenRouter roster, JSON-encoded; absent on a profile that predates it. */
        fun openRouterModels(tag: String) = stringPreferencesKey("p.$tag.openrouter_models")

        /** The background slot's provider; blank once "same as main" has been chosen deliberately. */
        fun backgroundProvider(tag: String) = stringPreferencesKey("p.$tag.background_provider")
        fun backgroundModel(tag: String) = stringPreferencesKey("p.$tag.background_model")

        /**
         * Retired in favour of [backgroundProvider] + [backgroundModel], from when the slot was one
         * model per provider; still read once, to carry the one that was in effect over.
         */
        fun legacyBackgroundModel(tag: String, provider: ProviderId) = stringPreferencesKey(
            when (provider) {
                ProviderId.GEMINI -> "p.$tag.gemini_background_model"
                ProviderId.OPENROUTER -> "p.$tag.openrouter_background_model"
            },
        )
        fun nativeLang(tag: String) = stringPreferencesKey("p.$tag.native_lang")
        fun defaultLevel(tag: String) = stringPreferencesKey("p.$tag.default_level")
        fun defaultCountry(tag: String) = stringPreferencesKey("p.$tag.default_country")
        fun corePrompt(tag: String) = stringPreferencesKey("p.$tag.core_prompt")
        fun customInstructions(tag: String) = stringPreferencesKey("p.$tag.custom_instructions")
        fun appLanguage(tag: String) = stringPreferencesKey("p.$tag.app_language")
        fun themeMode(tag: String) = stringPreferencesKey("p.$tag.theme_mode")
        fun persona(tag: String) = stringPreferencesKey("p.$tag.persona")
        fun themeCountry(tag: String) = stringPreferencesKey("p.$tag.theme_country")
        fun readingFont(tag: String) = stringPreferencesKey("p.$tag.reading_font")
        fun readingTextSize(tag: String) = stringPreferencesKey("p.$tag.reading_text_size")
        fun ttsVoiceSid(tag: String) = intPreferencesKey("p.$tag.tts_voice_sid")
        fun ttsSpeed(tag: String) = floatPreferencesKey("p.$tag.tts_speed")
        fun autoDeletePlayedAfterDays(tag: String) = intPreferencesKey("p.$tag.auto_delete_played_days")
        fun backgroundLlmTasks(tag: String) = booleanPreferencesKey("p.$tag.background_llm_tasks")
        fun reviewReminders(tag: String) = booleanPreferencesKey("p.$tag.review_reminders")
        fun highlightSavedWords(tag: String) = booleanPreferencesKey("p.$tag.highlight_saved_words")
        fun bookDefaultDownloadMode(tag: String) = stringPreferencesKey("p.$tag.book_default_download_mode")

        /** Retired in favour of [bookDefaultDownloadMode]; still read once, to carry the intent over. */
        fun bookAutoReadAhead(tag: String) = booleanPreferencesKey("p.$tag.book_auto_read_ahead")
        fun bookFrontierHintDismissed(tag: String) = booleanPreferencesKey("p.$tag.book_frontier_hint_dismissed")
        fun desiredRetention(tag: String) = doublePreferencesKey("p.$tag.desired_retention")
        fun newCardsPerDay(tag: String) = intPreferencesKey("p.$tag.new_cards_per_day")
        fun dailyGoalMinutes(tag: String) = intPreferencesKey("p.$tag.daily_goal_minutes")
        fun conjugationDrill(tag: String) = stringPreferencesKey("p.$tag.conjugation_drill")
    }

    /**
     * The encrypted API-key store. Its file is excluded from Auto Backup (res/xml/backup_rules.xml),
     * but a copy restored before that exclusion — or a keystore that otherwise lost its master key —
     * leaves a file nothing on this device can decrypt, and opening it throws. Keys can't be
     * recovered from that state, so wipe the store and start empty: the user re-enters their keys,
     * instead of the app crashing on every key read.
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            openSecurePrefs()
        } catch (e: Exception) {
            Log.w(TAG, "API-key store unreadable on this device; resetting it", e)
            resetSecurePrefs()
            openSecurePrefs()
        }
    }

    private fun openSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Deletes the key file and its keystore master key, so the next open builds both fresh. */
    private fun resetSecurePrefs() {
        context.deleteSharedPreferences(SECURE_PREFS_NAME)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.onFailure { Log.w(TAG, "Could not delete the API-key master key", it) }
    }

    /** Target-language tag of the active profile. */
    val activeProfileTag: Flow<String> = context.dataStore.data
        .map { it[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE }
        .distinctUntilChanged()

    /** All profile tags, sorted; always contains the active profile. */
    val profiles: Flow<List<String>> = context.dataStore.data
        .map { p ->
            val active = p[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE
            ((p[Keys.PROFILES] ?: emptySet()) + active).sorted()
        }
        .distinctUntilChanged()

    /** Settings of the active profile. */
    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        readProfile(p, p[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE)
    }

    /**
     * Read-modify-write into the active profile so callers can mutate a single field
     * ergonomically. Changes to [AppSettings.targetLanguage] are ignored: the target
     * language is the profile's identity and changes only via [switchProfile].
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val tag = prefs[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE
            prefs.writeProfile(tag, transform(readProfile(prefs, tag)))
        }
    }

    /** Make [tag] the active profile. No-op if it isn't a known profile. */
    suspend fun switchProfile(tag: String) {
        context.dataStore.edit { prefs ->
            val known = (prefs[Keys.PROFILES] ?: emptySet()) +
                (prefs[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE)
            if (tag in known) prefs[Keys.ACTIVE_PROFILE] = tag
        }
    }

    /**
     * Create a profile for [tag], seeding every field from the current active profile,
     * and switch to it. If the profile already exists, just switches.
     */
    suspend fun createProfile(tag: String) {
        context.dataStore.edit { prefs ->
            val known = (prefs[Keys.PROFILES] ?: emptySet()) +
                (prefs[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE)
            if (tag !in known) {
                val current = readProfile(prefs, prefs[Keys.ACTIVE_PROFILE] ?: DEFAULT_PROFILE)
                // Everything else is worth inheriting (provider, model, prompts, level), but the
                // country is not: a Portuguese profile seeded from a Spanish one would otherwise
                // start out teaching Portuguese as spoken in Mexico.
                prefs.writeProfile(
                    tag,
                    current.copy(targetLanguage = tag, defaultCountry = defaultCountryFor(tag)),
                )
            }
            prefs[Keys.PROFILES] = known + tag
            prefs[Keys.ACTIVE_PROFILE] = tag
        }
    }

    private fun readProfile(p: Preferences, tag: String): AppSettings {
        val d = AppSettings()
        // A profile written before the roster existed has only the single model key; it becomes that
        // profile's one-entry roster. normalize() also guarantees the active model is in the list,
        // so nothing downstream can be pointed at a model the pickers don't offer.
        val activeOpenRouter = p[Keys.openRouterModel(tag)] ?: d.openRouterModel
        val openRouterRoster = OpenRouterModels.normalize(
            OpenRouterModels.decode(p[Keys.openRouterModels(tag)]) ?: emptyList(),
            activeOpenRouter,
        )
        val provider = p[Keys.provider(tag)]?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
            ?: d.provider
        val background = backgroundChoiceOf(
            storedProvider = p[Keys.backgroundProvider(tag)],
            storedModel = p[Keys.backgroundModel(tag)],
            active = provider,
            legacyModel = p[Keys.legacyBackgroundModel(tag, provider)],
        )
        return AppSettings(
            provider = provider,
            geminiModel = p[Keys.geminiModel(tag)] ?: d.geminiModel,
            openRouterModel = activeOpenRouter,
            openRouterModels = openRouterRoster,
            nativeLanguage = p[Keys.nativeLang(tag)] ?: d.nativeLanguage,
            targetLanguage = tag,
            defaultLevel = p[Keys.defaultLevel(tag)]?.let { runCatching { Cefr.valueOf(it) }.getOrNull() }
                ?: d.defaultLevel,
            defaultCountry = p[Keys.defaultCountry(tag)] ?: d.defaultCountry,
            conjugationDrillConfigJson = p[Keys.conjugationDrill(tag)] ?: d.conjugationDrillConfigJson,
            corePrompt = p[Keys.corePrompt(tag)] ?: d.corePrompt,
            customInstructions = p[Keys.customInstructions(tag)] ?: d.customInstructions,
            appLanguage = p[Keys.appLanguage(tag)] ?: d.appLanguage,
            themeMode = p[Keys.themeMode(tag)]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: d.themeMode,
            persona = p[Keys.persona(tag)]?.let { runCatching { Persona.valueOf(it) }.getOrNull() }
                ?: d.persona,
            themeCountry = p[Keys.themeCountry(tag)]?.let { runCatching { ThemeCountry.valueOf(it) }.getOrNull() }
                ?: d.themeCountry,
            readingFont = p[Keys.readingFont(tag)]?.let { runCatching { ReadingFont.valueOf(it) }.getOrNull() }
                ?: d.readingFont,
            readingTextSize = p[Keys.readingTextSize(tag)]?.let { runCatching { ReadingTextSize.valueOf(it) }.getOrNull() }
                ?: d.readingTextSize,
            ttsVoiceSid = p[Keys.ttsVoiceSid(tag)] ?: d.ttsVoiceSid,
            ttsSpeed = p[Keys.ttsSpeed(tag)] ?: d.ttsSpeed,
            autoDeletePlayedAfterDays = p[Keys.autoDeletePlayedAfterDays(tag)] ?: d.autoDeletePlayedAfterDays,
            backgroundLlmTasks = p[Keys.backgroundLlmTasks(tag)] ?: d.backgroundLlmTasks,
            reviewReminders = p[Keys.reviewReminders(tag)] ?: d.reviewReminders,
            highlightSavedWords = p[Keys.highlightSavedWords(tag)] ?: d.highlightSavedWords,
            // Falls back to the boolean this replaced, so a profile that had read-ahead switched
            // off keeps importing books that translate nothing until asked.
            bookDefaultDownloadMode = p[Keys.bookDefaultDownloadMode(tag)]
                ?.let { runCatching { BookDownloadMode.valueOf(it) }.getOrNull() }
                ?: p[Keys.bookAutoReadAhead(tag)]
                    ?.let { if (it) BookDownloadMode.WHILE_READING else BookDownloadMode.OFF }
                ?: d.bookDefaultDownloadMode,
            bookFrontierHintDismissed =
                p[Keys.bookFrontierHintDismissed(tag)] ?: d.bookFrontierHintDismissed,
            desiredRetention = (p[Keys.desiredRetention(tag)] ?: d.desiredRetention)
                .coerceIn(SpacedRepetitionScheduler.MIN_RETENTION, SpacedRepetitionScheduler.MAX_RETENTION),
            newCardsPerDay = p[Keys.newCardsPerDay(tag)] ?: d.newCardsPerDay,
            dailyGoalMinutes = (p[Keys.dailyGoalMinutes(tag)] ?: d.dailyGoalMinutes)
                .coerceIn(AppSettings.DAILY_GOAL_MIN, AppSettings.DAILY_GOAL_MAX),
        ).let { s ->
            // A slot naming a model that has since left its list falls back to "same as main"
            // rather than sending background work to an id nothing offers any more.
            s.copy(backgroundChoice = background?.takeIf(s::offers))
        }
    }

    private fun MutablePreferences.writeProfile(tag: String, s: AppSettings) {
        this[Keys.provider(tag)] = s.provider.name
        this[Keys.geminiModel(tag)] = s.geminiModel
        this[Keys.openRouterModel(tag)] = s.openRouterModel
        this[Keys.openRouterModels(tag)] =
            OpenRouterModels.encode(OpenRouterModels.normalize(s.openRouterModels, s.openRouterModel))
        this[Keys.backgroundProvider(tag)] = s.backgroundChoice?.provider?.name.orEmpty()
        this[Keys.backgroundModel(tag)] = s.backgroundChoice?.model.orEmpty()
        ProviderId.entries.forEach { remove(Keys.legacyBackgroundModel(tag, it)) }
        this[Keys.nativeLang(tag)] = s.nativeLanguage
        // No target-language key: the tag itself is the profile identity.
        this[Keys.defaultLevel(tag)] = s.defaultLevel.name
        this[Keys.defaultCountry(tag)] = s.defaultCountry
        s.conjugationDrillConfigJson?.let { this[Keys.conjugationDrill(tag)] = it }
        this[Keys.corePrompt(tag)] = s.corePrompt
        this[Keys.customInstructions(tag)] = s.customInstructions
        this[Keys.appLanguage(tag)] = s.appLanguage
        this[Keys.themeMode(tag)] = s.themeMode.name
        this[Keys.persona(tag)] = s.persona.name
        this[Keys.themeCountry(tag)] = s.themeCountry.name
        this[Keys.readingFont(tag)] = s.readingFont.name
        this[Keys.readingTextSize(tag)] = s.readingTextSize.name
        this[Keys.ttsVoiceSid(tag)] = s.ttsVoiceSid
        this[Keys.ttsSpeed(tag)] = s.ttsSpeed
        this[Keys.autoDeletePlayedAfterDays(tag)] = s.autoDeletePlayedAfterDays
        this[Keys.backgroundLlmTasks(tag)] = s.backgroundLlmTasks
        this[Keys.reviewReminders(tag)] = s.reviewReminders
        this[Keys.highlightSavedWords(tag)] = s.highlightSavedWords
        this[Keys.bookDefaultDownloadMode(tag)] = s.bookDefaultDownloadMode.name
        this[Keys.bookFrontierHintDismissed(tag)] = s.bookFrontierHintDismissed
        this[Keys.desiredRetention(tag)] =
            s.desiredRetention.coerceIn(SpacedRepetitionScheduler.MIN_RETENTION, SpacedRepetitionScheduler.MAX_RETENTION)
        this[Keys.newCardsPerDay(tag)] = s.newCardsPerDay
        this[Keys.dailyGoalMinutes(tag)] =
            s.dailyGoalMinutes.coerceIn(AppSettings.DAILY_GOAL_MIN, AppSettings.DAILY_GOAL_MAX)
    }

    // --- ProfileSettingsSource: point reads for background maintenance ---

    override suspend fun activeTag(): String = activeProfileTag.first()

    override suspend fun allTags(): List<String> = profiles.first()

    /** Settings of an arbitrary profile — maintenance runs for profiles that aren't active. */
    override suspend fun settingsFor(tag: String): AppSettings =
        readProfile(context.dataStore.data.first(), tag)

    // --- API keys (encrypted, global across profiles) ---

    /**
     * The stored key, or null. A single value that no longer decrypts (the store opened, but this
     * entry is damaged) is dropped and reads as missing rather than throwing into every LLM call.
     */
    fun getApiKey(provider: ProviderId): String? {
        val name = keyName(provider)
        return try {
            securePrefs.getString(name, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Stored ${provider.name} API key unreadable; removing it", e)
            runCatching { securePrefs.edit().remove(name).apply() }
            null
        }
    }

    fun setApiKey(provider: ProviderId, key: String) {
        securePrefs.edit().putString(keyName(provider), key.trim()).apply()
    }

    override fun hasApiKey(provider: ProviderId): Boolean = !getApiKey(provider).isNullOrBlank()

    /**
     * Where a background call for [settings] should actually be sent: [AppSettings.backgroundFor],
     * unless the slot's provider has no key. The slot is on its own provider, so its key can be
     * missing while the fallback's is there (settings travel in a backup; keys do not) — and a book
     * section should not fail over a title that its own model could have translated.
     */
    fun backgroundTarget(settings: AppSettings, fallback: ModelOption = settings.main): ModelOption =
        settings.backgroundFor(fallback).takeIf { hasApiKey(it.provider) } ?: fallback

    private fun keyName(provider: ProviderId) = "api_key_${provider.name}"

    companion object {
        const val DEFAULT_PROFILE = "es"
        private const val TAG = "SettingsRepository"
        private const val SECURE_PREFS_NAME = "secure_keys" // backup_rules.xml excludes this file

        /**
         * The background slot as stored. [storedProvider] is blank for a deliberate "same as main",
         * and null on a profile last written when the slot was one model per provider — only the
         * [active] provider's was ever in effect, so [legacyModel] (that one) is what carries over.
         */
        internal fun backgroundChoiceOf(
            storedProvider: String?,
            storedModel: String?,
            active: ProviderId,
            legacyModel: String?,
        ): ModelOption? {
            if (storedProvider == null) {
                return legacyModel?.takeIf { it.isNotBlank() }?.let { ModelOption(active, it) }
            }
            val provider = runCatching { ProviderId.valueOf(storedProvider) }.getOrNull() ?: return null
            return storedModel?.takeIf { it.isNotBlank() }?.let { ModelOption(provider, it) }
        }

        /**
         * Where a new profile's teacher is from, before the learner changes it. Only a starting
         * point — every one of these languages is spoken in several countries, and the setting is a
         * dropdown for exactly that reason.
         */
        fun defaultCountryFor(tag: String): String = when (tag.substringBefore('-').lowercase()) {
            "es" -> "Mexico"
            "pt" -> "Portugal"
            "fr" -> "France"
            "de" -> "Germany"
            "it" -> "Italy"
            "en" -> "the United Kingdom"
            else -> AppSettings().defaultCountry
        }
    }
}

/**
 * One-time migration from flat legacy keys ("target_lang", "theme_mode", ...) to
 * profile-prefixed keys. The legacy target language becomes the first profile; fresh
 * installs just get the default profile registered with no per-field keys.
 */
internal object ProfileKeyMigration : DataMigration<Preferences> {
    private val LEGACY_FIELDS = listOf(
        "provider", "gemini_model", "openrouter_model", "native_lang", "default_level",
        "default_country", "core_prompt", "custom_instructions", "app_language",
        "theme_mode", "reading_font", "reading_text_size",
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SettingsRepository.Keys.ACTIVE_PROFILE] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val tag = prefs[stringPreferencesKey("target_lang")] ?: SettingsRepository.DEFAULT_PROFILE

        for (field in LEGACY_FIELDS) {
            val legacy = stringPreferencesKey(field)
            prefs[legacy]?.let { prefs[stringPreferencesKey("p.$tag.$field")] = it }
            prefs.remove(legacy)
        }
        val legacySid = intPreferencesKey("tts_voice_sid")
        prefs[legacySid]?.let { prefs[intPreferencesKey("p.$tag.tts_voice_sid")] = it }
        prefs.remove(legacySid)
        val legacySpeed = floatPreferencesKey("tts_speed")
        prefs[legacySpeed]?.let { prefs[floatPreferencesKey("p.$tag.tts_speed")] = it }
        prefs.remove(legacySpeed)
        prefs.remove(stringPreferencesKey("target_lang"))

        prefs[SettingsRepository.Keys.ACTIVE_PROFILE] = tag
        prefs[SettingsRepository.Keys.PROFILES] = setOf(tag)
        return prefs
    }

    override suspend fun cleanUp() {}
}
