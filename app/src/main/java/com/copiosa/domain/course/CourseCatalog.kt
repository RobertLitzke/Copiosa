package com.copiosa.domain.course

import com.copiosa.domain.course.data.spanishPastCourses
import com.copiosa.domain.course.data.spanishPorParaCourse

/**
 * The courses the app ships, resolved per language the way [com.copiosa.domain.conjugation.ConjugationSpecs]
 * resolves specs: by bare subtag, region ignored. Definitions are built lazily — course prose is
 * sizeable and a profile that never opens the hub should cost nothing.
 */
object CourseCatalog {

    private val spanish: List<CourseDefinition> by lazy {
        spanishPastCourses() + spanishPorParaCourse()
    }

    fun forTag(tag: String): List<CourseDefinition> = when (tag.substringBefore('-').lowercase()) {
        "es" -> spanish
        else -> emptyList()
    }

    fun course(tag: String, courseId: String): CourseDefinition? =
        forTag(tag).firstOrNull { it.id == courseId }
}
