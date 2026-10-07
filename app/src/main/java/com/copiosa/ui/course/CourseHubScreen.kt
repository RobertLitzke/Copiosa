package com.copiosa.ui.course

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.course.CourseOverview
import com.copiosa.ui.components.ScreenHeader

/**
 * The course hub: one card per course the profile's language ships, each showing where the learner
 * stands and opening onto [CourseDetailScreen] for the lessons themselves. Everything here is
 * **soft** — prerequisites are a notice, nothing locks, and the cards stay individually openable.
 */
@Composable
fun CourseHubScreen(
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
    viewModel: CourseHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = stringResource(R.string.course_hub_title),
            onBack = onBack,
        )

        state.overviews.forEach { overview ->
            CourseCard(
                overview = overview,
                all = state.overviews,
                onClick = { onOpenCourse(overview.definition.id) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CourseCard(
    overview: CourseOverview,
    all: List<CourseOverview>,
    onClick: () -> Unit,
) {
    val course = overview.definition

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        course.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        course.blurb,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.course_open_course),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
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
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.course_prereq_notice, missing.joinToString { it.title }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // The checkup and review affordances live on the course screen, next to the lessons they
            // send the learner back to; the hub only says which courses have something waiting.
            val waiting = buildList {
                if (overview.checkupDue(System.currentTimeMillis())) {
                    add(stringResource(R.string.course_checkup_due))
                }
                if (overview.flaggedLessons.isNotEmpty()) {
                    add(
                        stringResource(
                            R.string.course_review_flagged,
                            overview.flaggedLessons.first().title,
                        ),
                    )
                }
            }
            if (waiting.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    waiting.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (overview.graduated) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.course_graduated),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
