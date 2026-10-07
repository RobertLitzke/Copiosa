package com.copiosa.domain.prompt

/**
 * The two forgiving-parse steps every consolidated-response parser needs: find the JSON inside
 * whatever prose or fencing the model wrapped it in, and repair the garbling models reliably
 * produce (smart quotes, trailing commas). Shared by [RoutineContentParser] and
 * [com.copiosa.domain.course.CourseContentParser] so both degrade identically.
 */
internal object LenientJson {

    /** The outermost `{...}` in [raw], or null when there isn't one. */
    fun isolateObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    fun repair(jsonText: String): String =
        jsonText
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace(Regex(""",(\s*[}\]])"""), "$1")
}
