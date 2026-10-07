package com.copiosa.domain.course

import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense

/**
 * The authored skeleton of a lesson — what the course author wrote, before a session is built from
 * it. Not serialized: the catalog is code, and a session stores the materialized [LessonSegment]s
 * instead, so this hierarchy is free to change shape without a frozen-name discipline.
 */
sealed interface AuthoredSegment {

    /** A teaching beat: prose, live paradigm tables, and worked examples, in reading order. */
    data class Didactic(
        val title: String? = null,
        val blocks: List<DidacticBlock>,
    ) : AuthoredSegment

    /**
     * A practice beat built offline from the lexicon, narrowed to the lesson's scope (or
     * [scopeOverride] when one beat drills tighter than the lesson — a strong-preterites lesson
     * closing on just the j-stems).
     */
    data class EngineDrill(
        val itemCount: Int,
        val irregularEmphasis: Double = 0.6,
        val scopeOverride: LessonScope? = null,
    ) : AuthoredSegment

    /**
     * A practice beat the LLM fills at session time. [brief] is this slot's request line — what to
     * generate, in the same register as an `ActivityDescriptor.requestLine`. The slot is dropped
     * (and engine drills widened) when the model is unreachable or its output fails validation.
     */
    data class LlmSlot(
        val kind: LlmSlotKind,
        val itemCount: Int,
        val brief: String,
    ) : AuthoredSegment

    /**
     * A practice beat written out in full, beside the teaching it follows.
     *
     * The engine can generate a conjugation but not a preposition choice, and an [LlmSlot] is
     * garnish that vanishes the moment the device is offline — so a lesson about anything other
     * than morphology carries its own items, and they are the floor a sitting can never fall
     * below. `CourseCatalogTest` checks their shape the way it checks an [AuthoredExample]'s
     * morphology, and `CourseTextDumpTest` exports them, so a bank is reworkable outside the
     * Kotlin like the prose beside it.
     */
    data class AuthoredDrill(val items: List<AuthoredChoiceItem>) : AuthoredSegment {
        val itemCount: Int get() = items.size
    }
}

/**
 * One authored multiple-choice item. [sentence] carries exactly one
 * [com.copiosa.domain.routine.ClozeItem.BLANK], [options] are the candidate answers in the order
 * the learner sees them, and [explanation] is the one-line reason shown on reveal.
 */
data class AuthoredChoiceItem(
    val sentence: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
) {
    val answer: String get() = options[answerIndex]
}

/** What shape of items an [AuthoredSegment.LlmSlot] asks for. */
enum class LlmSlotKind {
    /** Fill-the-blank sentences with two or three options, [LessonScope.contrastCategory]-bucketed. */
    CLOZE,

    /** Referential-PI style: a conjugated, pro-dropped sentence; who/when is the question. */
    PI,
}

/** One block inside a [AuthoredSegment.Didactic], in reading order. */
sealed interface DidacticBlock {

    /** Authored explanation. Plain text with `**bold**` emphasis, split into paragraphs on blank lines. */
    data class Prose(val text: String) : DidacticBlock

    /**
     * A paradigm table rendered live from the spec at play time — never authored, so it can't
     * disagree with what a drill grades. [persons] narrows the rows (a third-person-raise lesson
     * shows just él/ellos); null shows the language's full paradigm.
     */
    data class TableRef(
        val tense: Tense,
        val verbs: List<String>,
        val persons: List<Person>? = null,
    ) : DidacticBlock

    /** Worked examples, each verifiable against the engine. */
    data class Examples(val items: List<AuthoredExample>) : DidacticBlock
}

/**
 * One authored example sentence. When [cell] is set, [sentence] must contain the engine's form for
 * `(infinitive, cell)` verbatim — `CourseCatalogTest` replays every one, which is what lets prose be
 * authored freely while the morphology in it stays machine-checked. A contrast example comparing two
 * whole sentences leaves [cell] null and is taken on faith.
 */
data class AuthoredExample(
    val infinitive: String,
    val cell: Cell?,
    val sentence: String,
    val gloss: String,
    val note: String? = null,
)
