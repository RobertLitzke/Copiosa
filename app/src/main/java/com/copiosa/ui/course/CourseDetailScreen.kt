package com.copiosa.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.IncompleteCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.course.CourseOverview
import com.copiosa.data.course.LessonStanding
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.CoursePart
import com.copiosa.domain.course.CourseSection
import com.copiosa.domain.course.LessonDefinition
import com.copiosa.ui.theme.SourceSerif
import kotlinx.coroutines.launch

/**
 * One course, full screen: what it teaches, how far in the learner is, and every lesson it contains
 * grouped under its part headings.
 *
 * The hero is pinned and only the lesson list scrolls, because the continue button is the thing the
 * screen exists for and scrolling it off the top would bury it. The lesson the course is currently
 * *on* is the one row that opens up — its tagline and its shape — so the next step is legible
 * without tapping, while the rest of the list stays scannable at one line each.
 */
@Composable
fun CourseDetailScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    viewModel: CourseDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val overview = state.overview

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.course_detail_overline),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (overview == null) {
            if (!state.loading) {
                Text(
                    stringResource(R.string.course_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }

        CourseHero(
            overview = overview,
            all = state.all,
            onStartLesson = { lessonId ->
                scope.launch { onOpenSession(viewModel.startLesson(lessonId)) }
            },
            onStartMicroLesson = { lessonId ->
                scope.launch { onOpenSession(viewModel.startMicroLesson(lessonId)) }
            },
            onStartCheckup = { scope.launch { onOpenSession(viewModel.startCheckup()) } },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LessonList(
            overview = overview,
            modifier = Modifier.weight(1f),
            onStartLesson = { lessonId ->
                scope.launch { onOpenSession(viewModel.startLesson(lessonId)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseHero(
    overview: CourseOverview,
    all: List<CourseOverview>,
    onStartLesson: (String) -> Unit,
    onStartMicroLesson: (String) -> Unit,
    onStartCheckup: () -> Unit,
) {
    val course = overview.definition
    Column(Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(4.dp))
        Text(
            course.title,
            fontFamily = SourceSerif,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            course.blurb,
            fontFamily = SourceSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { overview.progressFraction },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(
                    R.string.course_progress_summary,
                    overview.completedLessons,
                    course.lessons.size,
                    overview.percentComplete,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val missing = overview.missingPrerequisites(all)
        if (missing.isNotEmpty() && !overview.started) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.course_prereq_notice, missing.joinToString { it.title }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        val next = overview.nextLesson
        if (next != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onStartLesson(next.id) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    if (overview.completedLessons == 0) {
                        stringResource(R.string.course_start_format, next.title)
                    } else {
                        stringResource(R.string.course_continue_format, next.title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        } else if (overview.graduated) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.course_graduated),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val chips = buildList<Pair<String, () -> Unit>> {
            if (overview.checkupDue(System.currentTimeMillis())) {
                add(stringResource(R.string.course_checkup_due) to onStartCheckup)
            }
            overview.flaggedLessons.forEach { lesson ->
                add(
                    stringResource(R.string.course_review_flagged, lesson.title) to
                        { onStartMicroLesson(lesson.id) },
                )
            }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (label, action) ->
                    AssistChip(onClick = action, label = { Text(label) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The lessons, grouped into the course's parts. A course that declares no parts renders as one flat
 * run — [com.copiosa.domain.course.CourseDefinition.sections] returns nothing rather than inventing
 * a grouping, and the numbering stays course-wide either way so "lesson 11" means the same thing in
 * the list as it does in the hub.
 */
@Composable
private fun LessonList(
    overview: CourseOverview,
    modifier: Modifier = Modifier,
    onStartLesson: (String) -> Unit,
) {
    val course = overview.definition
    val continueAt = overview.nextLesson?.id
    val numbers = course.lessons.withIndex().associate { (i, lesson) -> lesson.id to i + 1 }
    val sections = course.sections()

    Box(modifier.fillMaxWidth()) {
        LessonLazyList(
            course = course,
            overview = overview,
            sections = sections,
            numbers = numbers,
            continueAt = continueAt,
            onStartLesson = onStartLesson,
        )
        // The hero is pinned, so rows pass under it. Without this the topmost row is sliced through
        // the middle of its letters and reads as a rendering fault rather than as scrolled content.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(14.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent),
                    ),
                ),
        )
    }
}

@Composable
private fun LessonLazyList(
    course: CourseDefinition,
    overview: CourseOverview,
    sections: List<CourseSection>,
    numbers: Map<String, Int>,
    continueAt: String?,
    onStartLesson: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        if (sections.isEmpty()) {
            items(course.lessons) { lesson ->
                LessonRow(
                    number = numbers.getValue(lesson.id),
                    lesson = lesson,
                    standing = overview.lessonStanding(lesson.id),
                    current = lesson.id == continueAt,
                    onClick = { onStartLesson(lesson.id) },
                )
            }
        } else {
            sections.forEachIndexed { index, section ->
                item(key = "part-${section.part.firstLessonId}") {
                    PartHeader(index = index, part = section.part)
                }
                items(section.lessons, key = { "lesson-${it.id}" }) { lesson ->
                    LessonRow(
                        number = numbers.getValue(lesson.id),
                        lesson = lesson,
                        standing = overview.lessonStanding(lesson.id),
                        current = lesson.id == continueAt,
                        onClick = { onStartLesson(lesson.id) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PartHeader(index: Int, part: CoursePart) {
    val ordinals = stringArrayResource(R.array.course_part_ordinals)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
    ) {
        Text(
            ordinals.getOrNull(index) ?: stringResource(R.string.course_part_label, index + 1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            part.title,
            fontFamily = SourceSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One lesson. Two things are shown at once and they are not the same thing: [standing] is how far
 * the learner got with *this* lesson, drawn on the leading mark, and [current] is whether the course
 * as a whole is sitting here, drawn as the tinted, opened-up row. They usually coincide, and when a
 * learner jumps ahead and leaves something half-finished behind them, they usefully do not.
 */
@Composable
private fun LessonRow(
    number: Int,
    lesson: LessonDefinition,
    standing: LessonStanding,
    current: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (standing) {
        LessonStanding.DONE -> Icons.Filled.CheckCircle
        LessonStanding.IN_PROGRESS -> Icons.Filled.IncompleteCircle
        LessonStanding.NOT_STARTED -> Icons.Filled.RadioButtonUnchecked
    }
    val tint = when {
        standing == LessonStanding.DONE -> scheme.primary
        standing == LessonStanding.IN_PROGRESS -> scheme.tertiary
        current -> scheme.primary
        else -> scheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (current) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$number · ${lesson.title}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) scheme.primary else scheme.onSurface,
            )
            if (current && lesson.tagline.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    lesson.tagline,
                    fontFamily = SourceSerif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = scheme.primary.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.course_lesson_meta,
                        pluralStringResource(
                            R.plurals.course_teaching_steps,
                            lesson.teachingSteps,
                            lesson.teachingSteps,
                        ),
                        pluralStringResource(
                            R.plurals.course_practice_cards,
                            lesson.practiceItems,
                            lesson.practiceItems,
                        ),
                        stringResource(R.string.course_lesson_minutes, lesson.estimatedMinutes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (current) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
        } else {
            Text(
                if (standing == LessonStanding.IN_PROGRESS) {
                    stringResource(R.string.course_lesson_in_progress)
                } else {
                    stringResource(R.string.course_lesson_minutes, lesson.estimatedMinutes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (standing == LessonStanding.IN_PROGRESS) {
                    scheme.tertiary
                } else {
                    scheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
