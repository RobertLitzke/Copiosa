package com.copiosa.domain.conjugation

/**
 * Which display zone a slice of a conjugated form belongs to. Only [IRREGULAR] is colour-coded; the
 * other two render as ordinary text.
 *
 * For a regular form [STEM] and [ENDING] are the true morphological split. For an irregular one they
 * are simply the leading and trailing regular material either side of the deviation — "p|ie|nso"
 * calls "nso" an ending even though "ns" belongs to the stem. That approximation is invisible on
 * screen (both zones render identically) and keeps the model to three zones.
 */
enum class SegmentRole { STEM, IRREGULAR, ENDING }

/** One display slice of a conjugated form. [irregularity] is set only on [SegmentRole.IRREGULAR]. */
data class FormSegment(
    val text: String,
    val role: SegmentRole,
    val irregularity: IrregularityClass? = null,
)

/**
 * A conjugated cell, ready to display: the correct form broken into colour-codeable segments, and
 * the naive counterpart the rules alone would have produced.
 */
data class ConjugatedForm(
    val infinitive: String,
    val tense: Tense,
    val person: Person,
    val form: String,
    /** What [Conjugator] alone produces. Equal to [form] for a regular cell. */
    val naiveForm: String,
    /**
     * Whether the naive form is worth showing struck through. It earns its place only when it is the
     * error a learner would plausibly make — "sabimos" for "supimos" teaches something, "o" for "voy"
     * looks like a rendering bug. See [ConjugationEngine.naivePlausible].
     */
    val showsNaive: Boolean,
    val segments: List<FormSegment>,
    val classes: List<IrregularityClass>,
) {
    val isIrregular: Boolean get() = classes.isNotEmpty()

    val families: Set<IrregularityFamily> get() = classes.mapTo(mutableSetOf()) { it.family }

    /** The single bucket this cell trains, for proficiency and stats. */
    val family: IrregularityFamily
        get() = FAMILY_PRIORITY.firstOrNull { it in families } ?: IrregularityFamily.REGULAR

    private companion object {
        /**
         * When a cell deviates in more than one way, the more structural deviation names the bucket —
         * a stem change that also takes a written accent is a stem change, not a spelling change.
         */
        val FAMILY_PRIORITY = listOf(
            IrregularityFamily.SUPPLETIVE,
            IrregularityFamily.STRONG_PRETERITE,
            IrregularityFamily.FUTURE_STEM,
            IrregularityFamily.YO_FORM,
            IrregularityFamily.STEM_CHANGE,
            IrregularityFamily.ORTHOGRAPHIC,
        )
    }
}

/**
 * Turns a [VerbEntry] plus a paradigm cell into a displayable [ConjugatedForm], for one language's
 * [ConjugationSpec]. Reach one through [ConjugationSpec.engine] rather than constructing it.
 *
 * The key move is that nothing about the *highlight* is authored: the lexicon stores only the correct
 * string and its irregularity class, and the span to colour is recovered by diffing that string
 * against [Conjugator]'s output. So there are no character indices to keep in sync, and the
 * struck-through naive form the learner sees is by construction the same string the diff was taken
 * against.
 */
class ConjugationEngine(private val spec: ConjugationSpec) {

    private val conjugator get() = spec.conjugator

    fun conjugate(verb: VerbEntry, tense: Tense, person: Person): ConjugatedForm? {
        val naive = conjugator.regular(verb.infinitive, tense, person) ?: return null
        val cls = conjugator.classOf(verb.infinitive) ?: return null

        val derived = spec.derived[tense]
        val stored = verb.overrideAt(tense, person)
        val resolved = if (derived != null && stored == null) {
            // Normally never stored: a derived tense is built off its source cell, so it inherits that
            // cell's irregularity (and its suppression) for free — supieron -> supiera, fizeram ->
            // fizer. An explicit override still wins, which is how Portuguese carries the handful of
            // imperfect-subjunctive stems whose written accent the general rule can't predict
            // (tivéssemos takes an acute where the regular comêssemos takes a circumflex).
            val sourceCell = derived.source
            val sourceOverride = sourceCell?.let { verb.overrideAt(it.tense, it.person) }
            val sourceForm = sourceOverride?.form
                ?: sourceCell?.let { conjugator.regular(verb.infinitive, it.tense, it.person) }
                ?: verb.infinitive
            val form = conjugator.derive(sourceForm, derived, person) ?: return null
            Resolved(form, sourceOverride?.classes.orEmpty(), sourceOverride?.suppressNaive == true)
        } else {
            Resolved(stored?.form ?: naive, stored?.classes.orEmpty(), stored?.suppressNaive == true)
        }

        val deviates = resolved.form != naive
        return ConjugatedForm(
            infinitive = verb.infinitive,
            tense = tense,
            person = person,
            form = resolved.form,
            naiveForm = naive,
            showsNaive = deviates && !resolved.suppressNaive && naivePlausible(verb, resolved.classes),
            segments = segment(
                form = resolved.form,
                naive = naive,
                classes = resolved.classes,
                regularEndingLength = regularEndingLength(cls, tense, person, naive),
            ),
            classes = resolved.classes,
        )
    }

    private data class Resolved(val form: String, val classes: List<IrregularityClass>, val suppressNaive: Boolean)

    /**
     * Whether the naive form is a plausible learner error, and so worth showing struck through.
     *
     * Two rules cover it. A suppletive verb's regular counterpart is not a form anyone would reach
     * for. And a verb whose stem is under two characters (ir, ser, ver, dar) produces naive forms
     * that aren't well-formed words at all — "o" for "voy", "so" for "soy" — so they read as a bug
     * rather than a correction. Everything else, including "sabimos" for "supimos" and "habo" for
     * "he", is exactly the mistake the drill wants to pre-empt.
     */
    internal fun naivePlausible(verb: VerbEntry, classes: List<IrregularityClass>): Boolean {
        if (classes.any { it.family == IrregularityFamily.SUPPLETIVE }) return false
        return conjugator.stem(verb.infinitive).length >= MIN_PLAUSIBLE_STEM
    }

    /**
     * Splits [form] into display zones. With no [classes] the split is the true stem/ending boundary;
     * otherwise the deviation is located by diffing against [naive] and the class's
     * [IrregularityClass.spanPolicy] decides how much of the word it claims.
     */
    internal fun segment(
        form: String,
        naive: String,
        classes: List<IrregularityClass>,
        regularEndingLength: Int,
    ): List<FormSegment> {
        if (classes.isEmpty() || form == naive) {
            val cut = (form.length - regularEndingLength).coerceIn(0, form.length)
            return buildList {
                if (cut > 0) add(FormSegment(form.substring(0, cut), SegmentRole.STEM))
                if (cut < form.length) add(FormSegment(form.substring(cut), SegmentRole.ENDING))
            }
        }

        val policy = classes.map { it.spanPolicy }.let { policies ->
            when {
                SpanPolicy.WHOLE_FORM in policies -> SpanPolicy.WHOLE_FORM
                SpanPolicy.SPLIT_STEM_ENDING in policies -> SpanPolicy.SPLIT_STEM_ENDING
                else -> SpanPolicy.DIFF
            }
        }
        val primary = classes.first()
        if (policy == SpanPolicy.WHOLE_FORM) {
            return listOf(FormSegment(form, SegmentRole.IRREGULAR, primary))
        }

        var (start, end) = diffSpan(form, naive)
        if (isAllNucleus(form, start, end)) {
            while (start > 0 && form.isNucleus(start - 1)) start--
            while (end < form.length && form.isNucleus(end)) end++
        }

        return buildList {
            if (start > 0) add(FormSegment(form.substring(0, start), SegmentRole.STEM))
            val split = if (policy == SpanPolicy.SPLIT_STEM_ENDING) strongPreteriteSplit(form, start, end) else null
            if (split != null) {
                val stemClass = classes.firstOrNull { it.spanPolicy == SpanPolicy.SPLIT_STEM_ENDING } ?: primary
                add(FormSegment(form.substring(start, split), SegmentRole.IRREGULAR, stemClass))
                add(FormSegment(form.substring(split, end), SegmentRole.IRREGULAR, spec.strongPreteriteEndingClass))
            } else {
                add(FormSegment(form.substring(start, end), SegmentRole.IRREGULAR, primary))
            }
            if (end < form.length) add(FormSegment(form.substring(end), SegmentRole.ENDING))
        }
    }

    /**
     * The half-open range of [form] that differs from [naive], found by trimming the shared prefix and
     * suffix. Never empty when the two differ: a pure deletion (naive longer, no distinct characters
     * left in [form]) claims the character at the point of divergence instead.
     */
    private fun diffSpan(form: String, naive: String): Pair<Int, Int> {
        val max = minOf(form.length, naive.length)
        var prefix = 0
        while (prefix < max && form[prefix] == naive[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && form[form.length - 1 - suffix] == naive[naive.length - 1 - suffix]) suffix++

        var start = prefix
        var end = form.length - suffix
        if (start >= end) {
            start = start.coerceAtMost(form.length - 1).coerceAtLeast(0)
            end = (start + 1).coerceAtMost(form.length)
        }
        return start to end
    }

    /**
     * Where the borrowed unstressed ending begins inside a strong preterite's deviating span, or null
     * when the span doesn't reach the end of the word (tuviste's "uv" stops before "iste" already).
     */
    private fun strongPreteriteSplit(form: String, start: Int, end: Int): Int? {
        if (end != form.length) return null
        return spec.strongPreteriteEndings
            .sortedByDescending { it.length }
            .firstOrNull { form.endsWith(it) && end - start > it.length }
            ?.let { form.length - it.length }
    }

    /**
     * The length of the ending the rules would have attached, used for the regular stem/ending split.
     * Future and conditional attach to the whole infinitive, so their "stem" zone is the infinitive
     * itself; that falls out of measuring the ending rather than the stem.
     */
    private fun regularEndingLength(cls: VerbClass, tense: Tense, person: Person, naive: String): Int =
        conjugator.ending(cls, tense, person).length.coerceAtMost(naive.length)

    private fun isAllNucleus(text: String, start: Int, end: Int): Boolean =
        start < end && (start until end).all { text.isNucleus(it) }

    /**
     * Whether the character at [index] belongs to the syllable's vowel nucleus.
     *
     * The u of "qu", and of "gu" before e/i, is spelling rather than sound. Counting it as a vowel
     * would widen querer's highlight from "ie" to "uie", implying the u changed too — it didn't.
     * Mirrors `is_nucleus` in tools/build_conjugations.py, which uses the same rule to tell a stem
     * change from something else.
     */
    private fun String.isNucleus(index: Int): Boolean {
        val c = this[index]
        if (c !in VOWELS) return false
        if (c == 'u' && index > 0) {
            val prev = this[index - 1]
            val next = if (index + 1 < length) this[index + 1] else ' '
            if (prev == 'q') return false
            if (prev == 'g' && (next == 'e' || next == 'i' || next == 'é' || next == 'í')) return false
        }
        return true
    }

    private companion object {
        const val VOWELS = "aeiouáéíóúüâêôãõà"
        const val MIN_PLAUSIBLE_STEM = 2
    }
}
