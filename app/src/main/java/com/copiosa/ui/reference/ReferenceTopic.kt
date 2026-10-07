package com.copiosa.ui.reference

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import com.copiosa.R

/**
 * The catalogue of the reference section: one entry per thing a learner can look up.
 *
 * Adding a topic is meant to cost an entry here, a screen, and a branch in the nav graph — nothing
 * else. [slug] is what travels in the route, so it is stable once shipped (a stale deep link should
 * land on the hub, not on the wrong topic); everything else is presentation.
 *
 * [needsConjugation] is the availability seam. Both of today's topics are built out of
 * `ConjugationSpec`, so they are only offered for a language that has one — but a topic that isn't
 * (numbers, pronouns, an alphabet) clears the flag and shows for every profile.
 */
enum class ReferenceTopic(
    val slug: String,
    @StringRes val titleRes: Int,
    @StringRes val blurbRes: Int,
    val icon: ImageVector,
    val needsConjugation: Boolean = true,
) {
    /** The conceptual half: what each paradigm is *for*. Deliberately ahead of the tables. */
    TENSES("tenses", R.string.ref_topic_tenses, R.string.ref_topic_tenses_desc, Icons.Filled.Schedule),

    /** The lookup half — and the drill's own top-right shortcut. */
    CONJUGATION(
        "conjugation",
        R.string.ref_topic_conjugation,
        R.string.ref_topic_conjugation_desc,
        Icons.AutoMirrored.Filled.MenuBook,
    ),
    ;

    companion object {
        fun fromSlug(slug: String?): ReferenceTopic? = entries.firstOrNull { it.slug == slug }
    }
}
