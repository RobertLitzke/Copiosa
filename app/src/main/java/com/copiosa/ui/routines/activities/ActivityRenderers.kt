package com.copiosa.ui.routines.activities

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.AffectivePiContent
import com.copiosa.domain.routine.BedtimeStoryContent
import com.copiosa.domain.routine.CognateHuntContent
import com.copiosa.domain.routine.ContrastClozeContent
import com.copiosa.domain.routine.GuessThenFlipContent
import com.copiosa.domain.routine.MultipleChoiceClozeContent
import com.copiosa.domain.routine.NarrowReadingContent
import com.copiosa.domain.routine.RapidFireContent
import com.copiosa.domain.routine.RecognitionCardsContent
import com.copiosa.domain.routine.ReferentialPiContent
import com.copiosa.domain.routine.SerialStoryContent
import com.copiosa.domain.routine.SwipeSortContent
import com.copiosa.ui.routines.RoutinePlayerViewModel
import kotlin.reflect.KClass

/** Everything a renderer needs to draw one activity step and report its outcomes. */
class ActivityRenderContext(
    val activity: ActivityInstance,
    val index: Int,
    val total: Int,
    val viewModel: RoutinePlayerViewModel,
    val advance: () -> Unit,
    val onExit: () -> Unit,
)

fun interface ActivityRenderer {
    @Composable
    fun Render(ctx: ActivityRenderContext)
}

/**
 * A renderer plus how the player frames it: [fullScreen] renderers own the whole screen (they draw
 * their own progress framing); the rest are wrapped in the player's progress-bar + padding scaffold.
 */
data class RendererEntry(val fullScreen: Boolean, val renderer: ActivityRenderer)

/**
 * Maps each [ActivityContent] kind to its renderer — the UI half of the activity registry. To add an
 * activity: write its renderer composable in this package and register it here. Registration
 * completeness is guarded by test (ActivityRenderersTest), which replaced the player's exhaustive
 * when-over-content.
 */
object ActivityRenderers {

    val byContent: Map<KClass<out ActivityContent>, RendererEntry> = mapOf(
        // Full-screen steps: real review, the shared reader, recognition cards.
        GuessThenFlipContent::class to RendererEntry(fullScreen = true) { ctx ->
            RoutineReviewStep(
                ctx.activity.content as GuessThenFlipContent,
                ctx.index, ctx.total, onExit = ctx.onExit, onComplete = ctx.advance,
            )
        },
        NarrowReadingContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as NarrowReadingContent
            RoutineReadingStep(
                content.title, content.paragraphs, ctx.index, ctx.total, ctx.viewModel,
                R.string.routine_continue, ctx.advance,
            )
        },
        BedtimeStoryContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as BedtimeStoryContent
            RoutineReadingStep(
                content.title, listOf(content.sentences.joinToString(" ")), ctx.index, ctx.total,
                ctx.viewModel, R.string.routine_finish, ctx.advance,
            )
        },
        SerialStoryContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as SerialStoryContent
            RoutineReadingStep(
                content.title, content.paragraphs, ctx.index, ctx.total, ctx.viewModel,
                R.string.routine_continue, ctx.advance,
            )
        },
        RecognitionCardsContent::class to RendererEntry(fullScreen = true) { ctx ->
            RoutineRecognitionStep(
                ctx.activity.content as RecognitionCardsContent,
                ctx.index, ctx.total, ctx.viewModel, onComplete = ctx.advance,
            )
        },

        // Framed steps: the player wraps these in its progress bar + padding.
        ReferentialPiContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as ReferentialPiContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.routine_pi_prompt),
                items = content.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
                onAnswer = { correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
            )
        },
        ContrastClozeContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as ContrastClozeContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.routine_cloze_prompt),
                items = content.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
                onAnswer = { correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
            )
        },
        MultipleChoiceClozeContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as MultipleChoiceClozeContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.routine_mc_cloze_prompt),
                items = content.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
                onAnswer = { correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
            )
        },
        SwipeSortContent::class to RendererEntry(fullScreen = false) { ctx ->
            SwipeSortActivity(ctx.activity.content as SwipeSortContent, onComplete = ctx.advance)
        },
        CognateHuntContent::class to RendererEntry(fullScreen = false) { ctx ->
            CognateHuntActivity(ctx.activity.content as CognateHuntContent, onComplete = ctx.advance)
        },
        AffectivePiContent::class to RendererEntry(fullScreen = false) { ctx ->
            AffectivePiActivity(
                ctx.activity.content as AffectivePiContent,
                onPick = { ctx.viewModel.recordAttempt(ctx.activity, null, null) },
                onComplete = ctx.advance,
            )
        },
        RapidFireContent::class to RendererEntry(fullScreen = false) { ctx ->
            RoutineRapidFireStep(
                content = ctx.activity.content as RapidFireContent,
                onAnswer = { correct, ms, speed -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms, speed) },
                onComplete = ctx.advance,
            )
        },
    )

    fun forContent(content: ActivityContent): RendererEntry? = byContent[content::class]
}
