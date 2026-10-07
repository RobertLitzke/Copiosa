package com.copiosa.domain.course

/**
 * Lifecycle of one course sitting, mirroring `RoutineRunStatus`. Names are persisted in
 * `course_sessions.status` — frozen.
 */
enum class CourseSessionStatus {
    SYNTHESIZING,
    READY,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    ;

    companion object {
        fun fromName(name: String?): CourseSessionStatus? = entries.firstOrNull { it.name == name }
    }
}
