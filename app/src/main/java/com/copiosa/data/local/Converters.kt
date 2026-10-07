package com.copiosa.data.local

import androidx.room.TypeConverter
import com.copiosa.domain.model.AudioStatus
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.model.Role
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.routine.TopicSource

/** Stores enums as their stable [Enum.name] string. */
class Converters {
    @TypeConverter fun cefrToString(v: Cefr): String = v.name
    @TypeConverter fun stringToCefr(v: String): Cefr = Cefr.valueOf(v)

    @TypeConverter fun bookDirectionToString(v: BookDirection): String = v.name
    // Falls back to FORWARD (the only direction books had before the column existed).
    @TypeConverter fun stringToBookDirection(v: String): BookDirection =
        runCatching { BookDirection.valueOf(v) }.getOrDefault(BookDirection.FORWARD)

    @TypeConverter fun bookDownloadModeToString(v: BookDownloadMode): String = v.name
    // Falls back to WHILE_READING, which is what the column's backfill gave every book that had no
    // explicit choice — an unknown value must never make a book stop translating silently.
    @TypeConverter fun stringToBookDownloadMode(v: String): BookDownloadMode =
        runCatching { BookDownloadMode.valueOf(v) }.getOrDefault(BookDownloadMode.WHILE_READING)

    @TypeConverter fun modeToString(v: Mode): String = v.name
    @TypeConverter fun stringToMode(v: String): Mode = Mode.valueOf(v)

    @TypeConverter fun providerToString(v: ProviderId): String = v.name
    // Falls back to GEMINI for unknown values (e.g. conversations saved under the removed CLAUDE
    // provider), so older rows keep opening instead of crashing on read.
    @TypeConverter fun stringToProvider(v: String): ProviderId =
        runCatching { ProviderId.valueOf(v) }.getOrDefault(ProviderId.GEMINI)

    @TypeConverter fun roleToString(v: Role): String = v.name
    @TypeConverter fun stringToRole(v: String): Role = Role.valueOf(v)

    @TypeConverter fun stateToString(v: FlashcardState): String = v.name
    @TypeConverter fun stringToState(v: String): FlashcardState = FlashcardState.valueOf(v)

    @TypeConverter fun ratingToString(v: ReviewRating): String = v.name
    @TypeConverter fun stringToRating(v: String): ReviewRating = ReviewRating.valueOf(v)

    @TypeConverter fun cardKindToString(v: CardKind): String = v.name
    @TypeConverter fun stringToCardKind(v: String): CardKind = CardKind.valueOf(v)

    @TypeConverter fun reviewDirectionToString(v: ReviewDirection): String = v.name
    // Falls back to TARGET_FIRST (the app's historical fixed direction) for unknown values.
    @TypeConverter fun stringToReviewDirection(v: String): ReviewDirection =
        runCatching { ReviewDirection.valueOf(v) }.getOrDefault(ReviewDirection.TARGET_FIRST)

    @TypeConverter fun audioStatusToString(v: AudioStatus): String = v.name
    @TypeConverter fun stringToAudioStatus(v: String): AudioStatus = AudioStatus.valueOf(v)

    @TypeConverter fun routineTypeToString(v: RoutineType): String = v.name
    @TypeConverter fun stringToRoutineType(v: String): RoutineType = RoutineType.valueOf(v)

    @TypeConverter fun routineStatusToString(v: RoutineRunStatus): String = v.name
    @TypeConverter fun stringToRoutineStatus(v: String): RoutineRunStatus = RoutineRunStatus.valueOf(v)

    @TypeConverter fun activityTypeToString(v: ActivityType): String = v.name

    // Resolves names retired by an activity merge, so an old attempt row can't crash a read.
    @TypeConverter fun stringToActivityType(v: String): ActivityType =
        ActivityType.fromNameOrNull(v) ?: ActivityType.NARROW_READING

    @TypeConverter fun readModeToString(v: ReadMode): String = v.name
    // Falls back to TARGET, which is where every reader started before the column existed — a value
    // this can't parse must never stop a book opening.
    @TypeConverter fun stringToReadMode(v: String): ReadMode =
        runCatching { ReadMode.valueOf(v) }.getOrDefault(ReadMode.TARGET)

    @TypeConverter fun topicSourceToString(v: TopicSource): String = v.name
    @TypeConverter fun stringToTopicSource(v: String): TopicSource = TopicSource.valueOf(v)

    @TypeConverter fun cueTypeToString(v: CueType): String = v.name
    @TypeConverter fun stringToCueType(v: String): CueType = CueType.valueOf(v)

    @TypeConverter fun focusActivityToString(v: FocusActivityType): String = v.name
    @TypeConverter fun stringToFocusActivity(v: String): FocusActivityType = FocusActivityType.valueOf(v)
}
