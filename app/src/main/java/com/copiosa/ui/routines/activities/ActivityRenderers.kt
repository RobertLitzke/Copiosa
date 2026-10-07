package com.copiosa.ui.routines.activities

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.AffectivePiContent
import com.copiosa.domain.routine.BedtimeStoryContent
import com.copiosa.domain.routine.CognateHuntContent
import com.copiosa.domain.routine.ConjugationDrillContent
import com.copiosa.domain.routine.ContrastClozeContent
import com.copiosa.domain.routine.FrequentWordDrillContent
import com.copiosa.domain.routine.GuessThenFlipContent
import com.copiosa.domain.routine.ListenRebuildContent
import com.copiosa.domain.routine.ClozeBankContent
import com.copiosa.domain.routine.MultipleChoiceClozeContent
import com.copiosa.domain.routine.NarrowReadingContent
import com.copiosa.domain.routine.RapidFireContent
import com.copiosa.domain.routine.RecognitionCardsContent
import com.copiosa.domain.routine.ReferentialPiContent
import com.copiosa.domain.routine.SerialStoryContent
import com.copiosa.domain.routine.ShortTextContent
import com.copiosa.domain.routine.SwipeSortContent
import com.copiosa.domain.routine.TextFlavor
import com.copiosa.ui.listening.ListenRebuildActivity
import com.copiosa.ui.routines.RoutinePlayerViewModel
import kotlin.reflect.KClass

/** Everything a renderer needs to draw one activity step and report its outcomes. */
class ActivityRenderContext(
    val activity: ActivityInstance,
    /** The run's target language, for the renderers whose presentation is language data. */
    val lang: String,
    val index: Int,
    val total: Int,
    val viewModel: RoutinePlayerViewModel,
    /** Moves on to the next step. Finishing a step and skipping it are the same call. */
    val advance: () -> Unit,
    val onExit: () -> Unit,
) {
    /**
     * The player's step chrome — exit, progress, skip. The player draws it above a framed step; a
     * full-screen step owns its whole layout and so places this itself.
     */
    @Composable
    fun Header(showExit: Boolean = true) =
        RoutineStepHeader(index, total, onExit = onExit, onSkip = advance, showExit = showExit)
}

fun interface ActivityRenderer {
    @Composable
    fun Render(ctx: ActivityRenderContext)
}

/**
 * A renderer plus how the player frames it: [fullScreen] renderers own the whole screen (and place
 * [ActivityRenderContext.Header] themselves); the rest are wrapped in the player's header + padding
 * scaffold.
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
                (ctx.activity.content as GuessThenFlipContent).cardLimit,
                onExit = ctx.onExit, onComplete = ctx.advance,
                header = { ctx.Header(showExit = false) },
            )
        },
        FrequentWordDrillContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as FrequentWordDrillContent
            RoutineReviewStep(
                content.cardLimit,
                onExit = ctx.onExit, onComplete = ctx.advance,
                header = { ctx.Header(showExit = false) },
                freqTop = content.maxRank,
            )
        },
        NarrowReadingContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as NarrowReadingContent
            RoutineReadingStep(
                content.title, content.paragraphs, ctx.viewModel,
                R.string.routine_continue, ctx.advance,
                header = { ctx.Header() },
            )
        },
        ShortTextContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as ShortTextContent
            // A bedtime story settles better as one unbroken block; everything else keeps its paragraphs.
            val blocks =
                if (content.flavor == TextFlavor.BEDTIME_STORY) listOf(content.blocks.joinToString(" "))
                else content.blocks
            RoutineReadingStep(
                content.title, blocks, ctx.viewModel,
                if (ctx.index == ctx.total - 1) R.string.routine_finish else R.string.routine_continue,
                ctx.advance,
                header = { ctx.Header() },
            )
        },

        // Superseded by ShortTextContent; kept so routines planned before the merge still play out.
        @Suppress("DEPRECATION")
        BedtimeStoryContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as BedtimeStoryContent
            RoutineReadingStep(
                content.title, listOf(content.sentences.joinToString(" ")),
                ctx.viewModel, R.string.routine_finish, ctx.advance,
                header = { ctx.Header() },
            )
        },
        @Suppress("DEPRECATION")
        SerialStoryContent::class to RendererEntry(fullScreen = true) { ctx ->
            val content = ctx.activity.content as SerialStoryContent
            RoutineReadingStep(
                content.title, content.paragraphs, ctx.viewModel,
                R.string.routine_continue, ctx.advance,
                header = { ctx.Header() },
            )
        },
        RecognitionCardsContent::class to RendererEntry(fullScreen = true) { ctx ->
            RoutineRecognitionStep(
                ctx.activity.content as RecognitionCardsContent,
                ctx.viewModel, onComplete = ctx.advance,
                header = { ctx.Header() },
            )
        },

        // Framed steps: the player wraps these in its header + padding.
        ReferentialPiContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as ReferentialPiContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.routine_pi_prompt),
                items = content.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
                onAnswer = { _, correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
                contentPadding = 0.dp,
            )
        },
        ContrastClozeContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as ContrastClozeContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.routine_cloze_prompt),
                items = content.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
                onAnswer = { _, correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
                contentPadding = 0.dp,
            )
        },
        MultipleChoiceClozeContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as MultipleChoiceClozeContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.routine_mc_cloze_prompt),
                items = content.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
                onAnswer = { _, correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
                contentPadding = 0.dp,
            )
        },
        ClozeBankContent::class to RendererEntry(fullScreen = false) { ctx ->
            val content = ctx.activity.content as ClozeBankContent
            MultipleChoiceActivity(
                prompt = stringResource(R.string.cloze_prompt),
                items = content.items.map {
                    ChoiceItem(it.item.sentence, it.item.options, it.item.answerIndex, it.item.explanation)
                },
                // Two sinks, deliberately: the routine's own proficiency bucket as every activity
                // does, and the sentence's own FSRS schedule in the bank, so practising here counts
                // exactly as it would on the standalone screen.
                onAnswer = { index, correct, ms ->
                    ctx.viewModel.recordAttempt(ctx.activity, correct, ms)
                    content.items.getOrNull(index)?.let { ctx.viewModel.recordClozeBank(it.sentenceId, correct, ms) }
                },
                onComplete = ctx.advance,
                contentPadding = 0.dp,
            )
        },
        // One sink only: the routine's bucket. Rebuilding keeps no schedule of its own and doesn't
        // write to the cloze bank's — hearing a sentence is not recalling its missing word.
        ListenRebuildContent::class to RendererEntry(fullScreen = false) { ctx ->
            ListenRebuildActivity(
                items = (ctx.activity.content as ListenRebuildContent).items,
                onAnswer = { _, correct, ms -> ctx.viewModel.recordAttempt(ctx.activity, correct, ms) },
                onComplete = ctx.advance,
                contentPadding = 0.dp,
            )
        },
        SwipeSortContent::class to RendererEntry(fullScreen = false) { ctx ->
            SwipeSortActivity(ctx.activity.content as SwipeSortContent, ctx.lang, onComplete = ctx.advance)
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
        ConjugationDrillContent::class to RendererEntry(fullScreen = false) { ctx ->
            RoutineConjugationStep(
                content = ctx.activity.content as ConjugationDrillContent,
                viewModel = ctx.viewModel,
                activity = ctx.activity,
                onComplete = ctx.advance,
            )
        },
    )

    fun forContent(content: ActivityContent): RendererEntry? = byContent[content::class]
}
