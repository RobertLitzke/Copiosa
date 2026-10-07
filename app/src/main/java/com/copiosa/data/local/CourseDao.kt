package com.copiosa.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    // --- progress ---

    @Query("SELECT * FROM course_progress WHERE courseId = :courseId AND targetLanguage = :lang LIMIT 1")
    suspend fun progress(courseId: String, lang: String): CourseProgressEntity?

    @Query("SELECT * FROM course_progress WHERE targetLanguage = :lang")
    fun observeProgress(lang: String): Flow<List<CourseProgressEntity>>

    @Query("SELECT * FROM course_progress WHERE targetLanguage = :lang AND status = :status")
    suspend fun progressWithStatus(lang: String, status: String): List<CourseProgressEntity>

    @Insert
    suspend fun insertProgress(row: CourseProgressEntity): Long

    @Update
    suspend fun updateProgress(row: CourseProgressEntity)

    // --- lesson state ---

    @Query("SELECT * FROM course_lesson_state WHERE courseId = :courseId AND targetLanguage = :lang")
    suspend fun lessonStates(courseId: String, lang: String): List<CourseLessonStateEntity>

    @Query("SELECT * FROM course_lesson_state WHERE targetLanguage = :lang")
    fun observeLessonStates(lang: String): Flow<List<CourseLessonStateEntity>>

    @Query(
        "SELECT * FROM course_lesson_state " +
            "WHERE courseId = :courseId AND lessonId = :lessonId AND targetLanguage = :lang LIMIT 1",
    )
    suspend fun lessonState(courseId: String, lessonId: String, lang: String): CourseLessonStateEntity?

    @Insert
    suspend fun insertLessonState(row: CourseLessonStateEntity): Long

    @Update
    suspend fun updateLessonState(row: CourseLessonStateEntity)

    // --- sessions ---

    @Query("SELECT * FROM course_sessions WHERE id = :id LIMIT 1")
    suspend fun session(id: Long): CourseSessionEntity?

    @Query("SELECT * FROM course_sessions WHERE id = :id LIMIT 1")
    fun observeSession(id: Long): Flow<CourseSessionEntity?>

    /**
     * Every lesson sitting the learner has opened and not finished, for the hub's part-done marker.
     * IN_PROGRESS only: a row still SYNTHESIZING or merely READY was created by a tap that may never
     * have got past the first screen, and marking a lesson part-done for that would be a lie.
     */
    @Query(
        "SELECT * FROM course_sessions WHERE targetLanguage = :lang " +
            "AND lessonId IS NOT NULL AND status = 'IN_PROGRESS'",
    )
    fun observeUnfinishedLessonSessions(lang: String): Flow<List<CourseSessionEntity>>

    /** The newest unfinished sitting for a course, for the hub's "continue" affordance. */
    @Query(
        "SELECT * FROM course_sessions WHERE courseId = :courseId AND targetLanguage = :lang " +
            "AND status IN ('SYNTHESIZING', 'READY', 'IN_PROGRESS') ORDER BY id DESC LIMIT 1",
    )
    suspend fun openSession(courseId: String, lang: String): CourseSessionEntity?

    @Insert
    suspend fun insertSession(row: CourseSessionEntity): Long

    @Query("UPDATE course_sessions SET status = :status, error = :error WHERE id = :id")
    suspend fun setSessionStatus(id: Long, status: String, error: String? = null)

    @Query("UPDATE course_sessions SET planJson = :planJson, status = :status WHERE id = :id")
    suspend fun savePlan(id: Long, planJson: String, status: String)

    @Query("UPDATE course_sessions SET currentIndex = :index, status = 'IN_PROGRESS' WHERE id = :id")
    suspend fun setCurrentIndex(id: Long, index: Int)

    @Query("UPDATE course_sessions SET status = 'COMPLETED', completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: Long, completedAt: Long)

    /**
     * Discards one sitting outright — what "restart" means. The row is the *plan*, not the record of
     * what was practised: every graded answer has already been written to the conjugation stores, so
     * deleting it loses a position in a lesson and nothing else.
     */
    @Query("DELETE FROM course_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    /** Stale never-started sittings, swept so an abandoned synthesis can't pile up rows. */
    @Query(
        "DELETE FROM course_sessions WHERE status IN ('SYNTHESIZING', 'READY') " +
            "AND createdAt < :olderThan",
    )
    suspend fun pruneUnstarted(olderThan: Long)
}
