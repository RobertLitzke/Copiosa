package com.copiosa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.stats.FocusTimeTracker
import com.copiosa.domain.stats.FocusActivityType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Hands [TrackFocusTime] the tracker plus the active profile tag as its langTag fallback. */
@HiltViewModel
class FocusTimerViewModel @Inject constructor(
    val tracker: FocusTimeTracker,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val activeTag: StateFlow<String?> = settingsRepo.activeProfileTag
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * Records the time this screen spends foregrounded as [type] focus time.
 *
 * Runs while the screen is resumed and [enabled]; attributes time to [langTag], defaulting to the
 * active profile when null (which also covers entities that haven't loaded yet). Drop it anywhere
 * in a screen's composition — sub-threshold visits are discarded by the tracker.
 */
@Composable
fun TrackFocusTime(
    type: FocusActivityType,
    langTag: String? = null,
    enabled: Boolean = true,
) {
    val viewModel: FocusTimerViewModel = hiltViewModel()
    val activeTag by viewModel.activeTag.collectAsStateWithLifecycle()
    val tag = langTag ?: activeTag
    val tracker = viewModel.tracker
    val owner = remember { Any() }

    var resumed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }

    LaunchedEffect(resumed, tag, type, enabled) {
        if (resumed && enabled && tag != null) {
            tracker.start(FocusTimeTracker.Channel.FOREGROUND, type, tag, owner)
        } else {
            tracker.stop(FocusTimeTracker.Channel.FOREGROUND, owner)
        }
    }
    DisposableEffect(owner) {
        onDispose { tracker.stop(FocusTimeTracker.Channel.FOREGROUND, owner) }
    }
}
