package com.copiosa.domain.course

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import java.io.File

/**
 * Dumps one course's authored text to JSON so it can be reworked outside the Kotlin, then read back
 * against the source when the edits are folded in.
 *
 * Not a test — a utility that lives in the test source set because that is the only place with the
 * catalog on its classpath and no Android runtime in the way. It is **inert unless told where to
 * write**, so a normal `testDebugUnitTest` run does nothing:
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*CourseTextDumpTest*" "-DcourseDump=../preterite-lessons.json"
 * ```
 *
 * `-DcourseId` picks the course (default `es_preterite`) and `-DcourseLang` its language. A relative
 * `courseDump` resolves against `app/`, the forked test JVM's working directory, so the `../` above
 * is what lands the file beside its siblings at the repo root. Add `--rerun-tasks` when the sources
 * have not changed since the last dump, or Gradle skips the task as up to date and you quietly read
 * yesterday's file.
 *
 * The tense courses dump prose only, because their practice is generated. A course that
 * carries its own items ([AuthoredSegment.AuthoredDrill]) dumps those too, so its whole
 * content — teaching and practice alike — is reworkable from the JSON.
 *
 * Going the other way is deliberately manual: the JSON carries every authored string with the
 * indices that locate it in [CourseDefinition], but nothing here writes Kotlin, because prose is
 * authored beside the scope it describes and a generator would happily separate the two.
 */
class CourseTextDumpTest {

    @Test
    fun `dump authored course text when asked`() {
        val target = System.getProperty("courseDump").orEmpty()
        if (target.isBlank()) return

        val lang = System.getProperty("courseLang").orEmpty().ifBlank { "es" }
        val courseId = System.getProperty("courseId").orEmpty().ifBlank { "es_preterite" }
        val course = requireNotNull(CourseCatalog.course(lang, courseId)) {
            "no course '$courseId' for '$lang'"
        }

        val file = File(target).absoluteFile
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), dump(course)))
        println("wrote ${course.lessons.size} lessons to $file")
    }

    private fun dump(course: CourseDefinition): JsonObject = buildJsonObject {
        put(
            "_readme",
            "Authored text for one course. 'teach' segments and the lesson title/tagline carry " +
                "the prose, and 'authored' segments carry the hand-written practice items in " +
                "full — both are yours to rework. 'drill' and 'llm' segments are generated at " +
                "play time and appear unedited, so the rhythm of a sitting stays visible while " +
                "you write. Prose keeps the app's inline markup: **bold** and *italic*. " +
                "Paragraphs are one string each, unwrapped. An item's 'answer' is the option at " +
                "'answerIndex', repeated so the JSON reads without counting. Indices ('i') " +
                "locate each item in the Kotlin source.",
        )
        put("courseId", course.id)
        put("language", course.language)
        put("title", course.title)
        put("blurb", course.blurb)
        putJsonArray("parts") {
            course.parts.forEach { part ->
                addJsonObject {
                    put("title", part.title)
                    put("firstLessonId", part.firstLessonId)
                }
            }
        }
        putJsonArray("lessons") {
            course.lessons.forEachIndexed { index, lesson ->
                addJsonObject {
                    put("i", index)
                    put("number", index + 1)
                    put("id", lesson.id)
                    put("title", lesson.title)
                    put("tagline", lesson.tagline)
                    put("estimatedMinutes", lesson.estimatedMinutes)
                    put("teachingSteps", lesson.teachingSteps)
                    put("practiceItems", lesson.practiceItems)
                    // What the lesson may ask for. Prose that promises more than this is the
                    // authoring bug the scopes exist to prevent, so it travels with the text.
                    put("scope", scopeSummary(lesson.scope))
                    put("segments", segments(lesson))
                }
            }
        }
    }

    private fun segments(lesson: LessonDefinition): JsonArray = buildJsonArray {
        lesson.segments.forEachIndexed { index, segment ->
            addJsonObject {
                put("i", index)
                when (segment) {
                    is AuthoredSegment.Didactic -> {
                        put("kind", "teach")
                        put("title", segment.title ?: "")
                        put("blocks", blocks(segment))
                    }

                    is AuthoredSegment.EngineDrill -> {
                        put("kind", "drill")
                        put("itemCount", segment.itemCount)
                        put("irregularEmphasis", segment.irregularEmphasis)
                        put(
                            "scopeOverride",
                            segment.scopeOverride?.let { JsonPrimitive(scopeSummary(it)) }
                                ?: JsonNull,
                        )
                    }

                    is AuthoredSegment.LlmSlot -> {
                        put("kind", "llm")
                        put("slot", segment.kind.name.lowercase())
                        put("itemCount", segment.itemCount)
                        put("brief", segment.brief)
                    }

                    is AuthoredSegment.AuthoredDrill -> {
                        put("kind", "authored")
                        put("itemCount", segment.itemCount)
                        put("items", items(segment))
                    }
                }
            }
        }
    }

    /**
     * A bank's items in full. Unlike a drill or an LLM slot, this is not context around the prose —
     * it *is* the lesson's practice, written by hand, so every string of it travels.
     */
    private fun items(segment: AuthoredSegment.AuthoredDrill): JsonArray = buildJsonArray {
        segment.items.forEachIndexed { index, item ->
            addJsonObject {
                put("i", index)
                put("sentence", item.sentence)
                putJsonArray("options") { item.options.forEach { add(it) } }
                put("answerIndex", item.answerIndex)
                put("answer", item.answer)
                put("explanation", item.explanation)
            }
        }
    }

    private fun blocks(segment: AuthoredSegment.Didactic): JsonArray = buildJsonArray {
        segment.blocks.forEachIndexed { index, block ->
            addJsonObject {
                put("i", index)
                when (block) {
                    is DidacticBlock.Prose -> {
                        put("kind", "prose")
                        putJsonArray("paragraphs") {
                            ProseText.paragraphs(block.text).forEach { add(it) }
                        }
                    }

                    is DidacticBlock.TableRef -> {
                        put("kind", "table")
                        put("tense", block.tense.slug)
                        putJsonArray("verbs") { block.verbs.forEach { add(it) } }
                        put(
                            "persons",
                            block.persons?.let { list ->
                                buildJsonArray { list.forEach { add(it.slug) } }
                            } ?: JsonNull,
                        )
                    }

                    is DidacticBlock.Examples -> {
                        put("kind", "examples")
                        putJsonArray("items") {
                            block.items.forEach { example ->
                                addJsonObject {
                                    put("infinitive", example.infinitive)
                                    put(
                                        "cell",
                                        example.cell?.slug?.let { JsonPrimitive(it) } ?: JsonNull,
                                    )
                                    put("sentence", example.sentence)
                                    put("gloss", example.gloss)
                                    put("note", example.note?.let { JsonPrimitive(it) } ?: JsonNull)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A one-line reading of a scope: what a drill built from it is allowed to ask. */
    private fun scopeSummary(scope: LessonScope): String = buildList {
        if (scope.tenses.isNotEmpty()) add(scope.tenses.joinToString("+") { it.slug })
        if (scope.directions.isNotEmpty()) add(scope.directions.joinToString("+") { it.slug })
        scope.verbs?.let { add(it.sorted().joinToString(",")) }
        scope.verbClasses?.let { classes -> add(classes.joinToString("+") { "-${it.name.lowercase()}" }) }
        scope.irregularityClasses?.let { classes -> add(classes.joinToString("+") { it.slug }) }
        if (scope.regularOnly) add("regular only")
        scope.persons?.let { people -> add(people.sorted().joinToString(",") { it.slug }) }
        scope.contrastCategory?.let { add("bucket:$it") }
    }.joinToString(" · ")

    private companion object {
        val json = Json { prettyPrint = true }
    }
}
