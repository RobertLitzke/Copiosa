package com.copiosa.ui.audio

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.copiosa.R
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsModelState
import com.copiosa.playback.Pronouncer
import com.copiosa.playback.Speaking
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs every [PronounceButton] on a screen. The buttons share it (it is resolved from the screen's
 * own store), which is what lets one show "stop" while the others stay "play", and what stops the
 * voice when the screen is left.
 */
@HiltViewModel
class PronounceViewModel @Inject constructor(
    private val pronouncer: Pronouncer,
    private val modelStore: SupertonicModelStore,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val speaking: StateFlow<Speaking?> = pronouncer.speaking
    val voice: StateFlow<TtsModelState> = modelStore.state

    /** Says [text], or stops it if it is what is being said. A null [langCode] is the language being learned. */
    fun toggle(text: String, langCode: String?) {
        viewModelScope.launch {
            pronouncer.toggle(text, langCode ?: settingsRepo.settings.first().targetLanguage)
        }
    }

    /** Starts the (opt-in) voice download — only ever called from the button's own prompt. */
    fun downloadVoice() = modelStore.download()

    override fun onCleared() = pronouncer.stop()
}

/**
 * A play button that says [text] aloud with the on-device voice, there and then — as opposed to
 * "Send to audio", which queues it for the Audio tab. While its text is being said it is a stop
 * button; while the voice is still working on the first sentence, a spinner.
 *
 * Self-contained, so it can be dropped next to any target-language text: the voice is opt-in, and
 * a tap before it is installed offers the download rather than doing nothing. [langCode] is the
 * language of [text] when the caller knows better than "the one being learned".
 */
@Composable
fun PronounceButton(
    text: String,
    modifier: Modifier = Modifier,
    langCode: String? = null,
    iconSize: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    viewModel: PronounceViewModel = hiltViewModel(),
) {
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askVoice by remember { mutableStateOf(false) }
    val downloadingText = stringResource(R.string.quiz_model_downloading)
    val mine = speaking?.takeIf { it.text == text }

    IconButton(
        onClick = {
            when (voice) {
                TtsModelState.Ready -> viewModel.toggle(text, langCode)
                is TtsModelState.Downloading ->
                    Toast.makeText(context, downloadingText, Toast.LENGTH_SHORT).show()
                TtsModelState.NotDownloaded, is TtsModelState.Failed -> askVoice = true
            }
        },
        modifier = modifier.size(36.dp),
    ) {
        when {
            mine == null -> Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.action_play_audio),
                modifier = Modifier.size(iconSize),
                tint = tint,
            )
            mine.audible -> Icon(
                Icons.Filled.Stop,
                contentDescription = stringResource(R.string.action_stop),
                modifier = Modifier.size(iconSize),
                tint = tint,
            )
            else -> CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp,
                color = tint,
            )
        }
    }

    if (askVoice) {
        TtsDownloadPrompt(
            onConfirm = { askVoice = false; viewModel.downloadVoice() },
            onDismiss = { askVoice = false },
            body = R.string.pronounce_tts_prompt_body,
        )
    }
}
