package com.copiosa.domain.course

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * (De)serializes a [LessonPlan] to/from the JSON stored in `course_sessions.planJson`.
 *
 * Decoding is deliberately **element-wise**: the segment array is walked one entry at a time and an
 * entry that fails to decode — an unknown `"kind"`, a malformed payload — is dropped rather than
 * failing the plan. This is the lesson learned from `RoutinePlanCodec`, where an unrecognised kind
 * fails the whole plan and strands the run on a permanent spinner; here a retired segment kind needs
 * no deprecated stub, old sessions just get shorter. A plan whose fixed header is unreadable, or
 * that loses *every* segment, still decodes to null — there is nothing left to play.
 */
object LessonPlanCodec {

    private val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(plan: LessonPlan): String = json.encodeToString(LessonPlan.serializer(), plan)

    fun decode(stored: String?): LessonPlan? {
        if (stored.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(stored).jsonObject
            val segments = (root["segments"]?.jsonArray ?: return null).mapNotNull { element ->
                runCatching {
                    json.decodeFromJsonElement(LessonSegment.serializer(), element)
                }.getOrNull()
            }
            if (segments.isEmpty()) return null
            LessonPlan(
                courseId = root.getValue("courseId").jsonPrimitive.content,
                lessonId = root["lessonId"]
                    ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                targetLanguage = root.getValue("targetLanguage").jsonPrimitive.content,
                sessionKind = SessionKind.fromName(root["sessionKind"]?.jsonPrimitive?.content)
                    ?: SessionKind.LESSON,
                segments = segments,
            )
        }.getOrNull()
    }
}
