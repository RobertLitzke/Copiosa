package com.copiosa.domain.routine

/**
 * Lifecycle of a single synthesized routine run. [SYNTHESIZING] while content is being generated;
 * [READY] once the plan is fully built and playable; [IN_PROGRESS] once the user starts stepping
 * through it; [COMPLETED] at the end; [FAILED] if synthesis errored (retriable).
 */
enum class RoutineRunStatus {
    SYNTHESIZING,
    READY,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}
