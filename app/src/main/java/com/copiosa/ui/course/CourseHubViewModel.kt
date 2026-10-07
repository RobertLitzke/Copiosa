package com.copiosa.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.course.CourseOverview
import com.copiosa.data.course.CourseRepository
import com.copiosa.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CourseHubUi(
    val loading: Boolean = true,
    val overviews: List<CourseOverview> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseHubViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    courseRepo: CourseRepository,
) : ViewModel() {

    val state: StateFlow<CourseHubUi> = settingsRepo.settings
        .flatMapLatest { courseRepo.observeOverviews(it.targetLanguage) }
        .map { CourseHubUi(loading = false, overviews = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseHubUi())
}
