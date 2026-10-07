package com.copiosa.ui.routines

import com.copiosa.domain.routine.ActivityContent
import com.copiosa.domain.routine.AffectivePiContent
import com.copiosa.domain.routine.BedtimeStoryContent
import com.copiosa.domain.routine.CognateHuntContent
import com.copiosa.domain.routine.ConjugationDrillContent
import com.copiosa.domain.routine.ContrastClozeContent
import com.copiosa.domain.routine.ClozeBankContent
import com.copiosa.domain.routine.FrequentWordDrillContent
import com.copiosa.domain.routine.GuessThenFlipContent
import com.copiosa.domain.routine.ListenRebuildContent
import com.copiosa.domain.routine.MultipleChoiceClozeContent
import com.copiosa.domain.routine.NarrowReadingContent
import com.copiosa.domain.routine.RapidFireContent
import com.copiosa.domain.routine.RecognitionCardsContent
import com.copiosa.domain.routine.ReferentialPiContent
import com.copiosa.domain.routine.SerialStoryContent
import com.copiosa.domain.routine.ShortTextContent
import com.copiosa.domain.routine.SwipeSortContent
import com.copiosa.ui.routines.activities.ActivityRenderers
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.KClass

/**
 * The player dispatches on this registry instead of an exhaustive when-over-content, so this test is
 * what catches a content kind that was added without a renderer (formerly a compile error).
 *
 * Superseded kinds must stay registered too: a plan saved before an activity merge still decodes into
 * the old content class, and a run left unfinished across the upgrade has to keep playing.
 */
@Suppress("DEPRECATION")
class ActivityRenderersTest {

    @Test
    fun `every content kind has a registered renderer`() {
        val allContentClasses: Set<KClass<out ActivityContent>> = setOf(
            GuessThenFlipContent::class,
            ReferentialPiContent::class,
            ContrastClozeContent::class,
            NarrowReadingContent::class,
            AffectivePiContent::class,
            ShortTextContent::class,
            RecognitionCardsContent::class,
            MultipleChoiceClozeContent::class,
            SwipeSortContent::class,
            CognateHuntContent::class,
            RapidFireContent::class,
            ConjugationDrillContent::class,
            FrequentWordDrillContent::class,
            ClozeBankContent::class,
            ListenRebuildContent::class,
            // Superseded by ShortTextContent, retained for legacy plan playback.
            BedtimeStoryContent::class,
            SerialStoryContent::class,
        )
        assertEquals(allContentClasses, ActivityRenderers.byContent.keys)
    }
}
