package com.copiosa.ui.settings

import android.content.Context
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsEngine
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.model.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class KeyPresence(val gemini: Boolean, val openRouter: Boolean)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val ttsModelStore: SupertonicModelStore,
    private val ttsEngine: TtsEngine,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** All language-profile tags, for the profile selector. */
    val profiles: StateFlow<List<String>> =
        repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tag of the active profile (also mirrored in [settings].targetLanguage). */
    val activeProfile: StateFlow<String> =
        repo.activeProfileTag.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_PROFILE,
        )

    /** Switches to an existing profile, then applies its app-UI locale. */
    fun switchProfile(tag: String) {
        viewModelScope.launch {
            repo.switchProfile(tag)
            applyProfileLocale()
        }
    }

    /** Creates a profile for [tag] (seeded from the current one), switches to it. */
    fun addProfile(tag: String) {
        viewModelScope.launch {
            repo.createProfile(tag)
            applyProfileLocale()
        }
    }

    /**
     * Applies the now-active profile's app language, skipping the call when the locale is
     * unchanged — setApplicationLocales recreates the activity, which we avoid when possible.
     */
    private suspend fun applyProfileLocale() {
        val wanted = repo.settings.first().appLanguage
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (current != wanted) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(wanted))
        }
    }

    /** State of the (opt-in) on-device TTS voice download, for the Speech settings card. */
    val ttsModelState: StateFlow<TtsModelState> = ttsModelStore.state

    private val _ttsVoiceCount = MutableStateFlow(TtsEngine.VOICE_COUNT)
    /** Number of selectable voices (sid 0 until this); refined from the model once it's installed. */
    val ttsVoiceCount: StateFlow<Int> = _ttsVoiceCount.asStateFlow()

    init {
        // Once the voice is installed, ask the model how many speakers it actually has, so the picker
        // shows them all even if the count differs from the built-in default.
        viewModelScope.launch {
            ttsModelStore.state.collect { state ->
                if (state is TtsModelState.Ready) {
                    runCatching { _ttsVoiceCount.value = ttsEngine.voiceCount().coerceAtLeast(1) }
                }
            }
        }
    }

    private val _previewing = MutableStateFlow(false)
    /** True while a voice preview is being synthesized, so the button can show progress. */
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

    private var previewPlayer: MediaPlayer? = null

    fun downloadTts() = ttsModelStore.download()

    fun removeTts() = ttsModelStore.remove()

    /** Persists the chosen voice; applies to audio generated afterwards. */
    fun setTtsVoice(sid: Int) {
        viewModelScope.launch { repo.update { it.copy(ttsVoiceSid = sid) } }
    }

    /** Persists the playback speed (1.0 = normal); applies to audio rendered afterwards. */
    fun setTtsSpeed(speed: Float) {
        val clamped = speed.coerceIn(AppSettings.TTS_SPEED_MIN, AppSettings.TTS_SPEED_MAX)
        viewModelScope.launch { repo.update { it.copy(ttsSpeed = clamped) } }
    }

    fun setAutoDeletePlayedDays(days: Int) {
        viewModelScope.launch { repo.update { it.copy(autoDeletePlayedAfterDays = days.coerceAtLeast(0)) } }
    }

    /** Synthesizes a short sample in the target language with [sid] and plays it, so voices can be auditioned. */
    fun previewVoice(sid: Int) {
        if (!ttsModelStore.isReady() || _previewing.value) return
        viewModelScope.launch {
            _previewing.value = true
            try {
                val lang = settings.value.targetLanguage
                val out = File(context.cacheDir, "tts_preview.m4a")
                ttsEngine.synthesizeToFile(sampleFor(lang), lang, sid, out, settings.value.ttsSpeed)
                playPreview(out)
            } catch (_: Exception) {
                // A failed preview is non-fatal; the button just stops spinning.
            } finally {
                _previewing.value = false
            }
        }
    }

    private fun playPreview(file: File) {
        runCatching { previewPlayer?.release() }
        previewPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
            setOnErrorListener { mp, _, _ -> mp.release(); true }
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    override fun onCleared() {
        runCatching { previewPlayer?.release() }
        previewPlayer = null
    }

    /** A short, language-appropriate sample sentence for auditioning a voice. */
    private fun sampleFor(lang: String): String = when (lang.substringBefore('-').lowercase()) {
        "es" -> "Hola, esta es una muestra de la voz."
        "fr" -> "Bonjour, ceci est un échantillon de cette voix."
        "de" -> "Hallo, dies ist eine Hörprobe dieser Stimme."
        "it" -> "Ciao, questo è un esempio di questa voce."
        "pt" -> "Olá, esta é uma amostra desta voz."
        else -> "Hello, this is a sample of this voice."
    }

    private val _keys = MutableStateFlow(currentKeyPresence())
    val keys: StateFlow<KeyPresence> = _keys.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun setApiKey(provider: ProviderId, key: String) {
        repo.setApiKey(provider, key)
        _keys.value = currentKeyPresence()
    }

    /** The stored key for [provider], so the field can prefill and reveal it on demand. */
    fun apiKey(provider: ProviderId): String = repo.getApiKey(provider) ?: ""

    fun setAppLanguage(tag: String) {
        viewModelScope.launch { repo.update { it.copy(appLanguage = tag) } }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun currentKeyPresence() =
        KeyPresence(repo.hasApiKey(ProviderId.GEMINI), repo.hasApiKey(ProviderId.OPENROUTER))
}
