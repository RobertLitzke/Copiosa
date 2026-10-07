package com.copiosa.ui.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.course.CourseOverview
import com.copiosa.data.course.CourseRepository
import com.copiosa.data.course.CourseSessionManager
import com.copiosa.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One course as its own screen. [overview] is null while loading, and stays null when the route
 * names a course the profile's language doesn't ship — the screen then just offers a way back.
 */
data class CourseDetailUi(
    val loading: Boolean = true,
    val overview: CourseOverview? = null,
    /** The other courses, only for resolving prerequisite titles. */
    val all: List<CourseOverview> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    settingsRepo: SettingsRepository,
    courseRepo: CourseRepository,
    private val sessionManager: CourseSessionManager,
) : ViewModel() {

    private val courseId: String = savedStateHandle.get<String>("courseId").orEmpty()

    val state: StateFlow<CourseDetailUi> = settingsRepo.settings
        .flatMapLatest { courseRepo.observeOverviews(it.targetLanguage) }
        .map { overviews ->
            CourseDetailUi(
                loading = false,
                overview = overviews.firstOrNull { it.definition.id == courseId },
                all = overviews,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseDetailUi())

    suspend fun startLesson(lessonId: String): Long = sessionManager.startLesson(courseId, lessonId)

    suspend fun startMicroLesson(lessonId: String): Long =
        sessionManager.startMicroLesson(courseId, lessonId)

    suspend fun startCheckup(): Long = sessionManager.startCheckup(courseId)
}
