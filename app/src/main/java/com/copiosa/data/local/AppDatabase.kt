package com.copiosa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FlashcardEntity::class,
        ReviewLogEntity::class,
        AudioItemEntity::class,
        RoutineRunEntity::class,
        ActivityAttemptEntity::class,
        ActivityProficiencyEntity::class,
        TopicEntity::class,
        StoryThreadEntity::class,
        QuizPoolEntity::class,
        QuizCueEntity::class,
    ],
    version = 22,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun flashcardDao(): FlashcardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun audioItemDao(): AudioItemDao
    abstract fun routineRunDao(): RoutineRunDao
    abstract fun activityAttemptDao(): ActivityAttemptDao
    abstract fun activityProficiencyDao(): ActivityProficiencyDao
    abstract fun topicDao(): TopicDao
    abstract fun storyThreadDao(): StoryThreadDao
    abstract fun quizDao(): QuizDao
}
