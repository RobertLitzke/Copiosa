package com.copiosa.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.copiosa.data.local.ActivityAttemptDao
import com.copiosa.data.local.ActivityProficiencyDao
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.AudioItemDao
import com.copiosa.data.local.ConversationDao
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.MessageDao
import com.copiosa.data.local.QuizDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.local.RoutineRunDao
import com.copiosa.data.local.StoryThreadDao
import com.copiosa.data.local.TopicDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Adds per-message metadata columns (model/level/country) captured at response time. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN level TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN country TEXT")
    }
}

/** Adds LLM enrichment columns (explanation/part-of-speech/CEFR) to flashcards. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN explanation TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN partOfSpeech TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN cefrLevel TEXT")
    }
}

/** Adds the imported-text source columns (original title/body) used by Texts mode. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN originalTitle TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN originalText TEXT")
    }
}

/** Adds chunked-translation progress columns so a failed text translation can resume. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN translatedChunks INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN totalChunks INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds read-status (existing rows default to read) and starred flags to conversations. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN read INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE conversations ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds reading-position (scroll) + finished-reading tracking for imported texts. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN scrollIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN scrollOffset INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN finishedReading INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds the structured-metadata blob (next topics, distinctive words, vocab used) to messages. */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN metadata TEXT")
    }
}

/** Adds the per-review history table that backs Anki revlog round-trips and FSRS re-optimisation. */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reviews` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`cardId` INTEGER NOT NULL, " +
                "`reviewedAt` INTEGER NOT NULL, " +
                "`rating` TEXT NOT NULL, " +
                "`lastIntervalDays` REAL NOT NULL, " +
                "`intervalDays` REAL NOT NULL, " +
                "`stabilityAfter` REAL, " +
                "`difficultyAfter` REAL, " +
                "`easeFactor` REAL NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`reviewDurationMs` INTEGER, " +
                "FOREIGN KEY(`cardId`) REFERENCES `flashcards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reviews_cardId` ON `reviews` (`cardId`)")
    }
}

/** Adds FSRS memory-state columns (stability/difficulty/step) to flashcards; null = still new to FSRS. */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN stability REAL")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN difficulty REAL")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN fsrsStep INTEGER")
    }
}

/** Adds Anki provenance + passthrough + media columns for .apkg round-tripping (all null otherwise). */
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN ankiNoteGuid TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN ankiOrd INTEGER")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN ankiNoteTypeId INTEGER")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN ankiFieldsJson TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN ankiTags TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN ankiDeckName TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN imageFile TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN audioFile TEXT")
    }
}

/** Adds the per-card "frozen"/suspended flag (existing rows default to active). */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN suspended INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Adds structured-vocab columns: [cardKind] (defaults existing rows to VOCAB), gender/region/altForms
 * for cleaned-deck display + highlighting, and frontIsTarget (existing rows assumed target-front).
 */
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN cardKind TEXT NOT NULL DEFAULT 'VOCAB'")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN gender TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN region TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN altForms TEXT")
        db.execSQL("ALTER TABLE flashcards ADD COLUMN frontIsTarget INTEGER NOT NULL DEFAULT 1")
    }
}

/** Adds the audio_items table backing the listening playlist (generated on-device TTS). */
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `audio_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`langCode` TEXT NOT NULL, " +
                "`sid` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`filePath` TEXT, " +
                "`durationMs` INTEGER, " +
                "`played` INTEGER NOT NULL, " +
                "`sortOrder` REAL NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`sourceConversationId` INTEGER, " +
                "`sourceMessageId` INTEGER, " +
                "`error` TEXT )",
        )
    }
}

/**
 * Adds the routines feature: synthesized routine runs, the silent per-attempt log, the adaptation
 * engine's per-bucket proficiency, and the topic/interest table that seeds routine content.
 */
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `routine_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`routineType` TEXT NOT NULL, " +
                "`durationMinutes` INTEGER NOT NULL, " +
                "`topic` TEXT, " +
                "`topicContext` TEXT, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`level` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`planJson` TEXT, " +
                "`currentIndex` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`startedAt` INTEGER, " +
                "`completedAt` INTEGER, " +
                "`error` TEXT )",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_attempts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`routineRunId` INTEGER NOT NULL, " +
                "`activityType` TEXT NOT NULL, " +
                "`category` TEXT, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`correct` INTEGER, " +
                "`latencyMs` INTEGER, " +
                "`difficulty` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_activity_attempts_routineRunId` " +
                "ON `activity_attempts` (`routineRunId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_proficiency` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bucketKey` TEXT NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`mastery` REAL NOT NULL, " +
                "`difficultyLevel` INTEGER NOT NULL, " +
                "`selectionWeight` REAL NOT NULL, " +
                "`attempts` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_activity_proficiency_bucketKey_targetLanguage` " +
                "ON `activity_proficiency` (`bucketKey`, `targetLanguage`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `topics` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`phrase` TEXT NOT NULL, " +
                "`description` TEXT, " +
                "`source` TEXT NOT NULL, " +
                "`sourceConversationId` INTEGER, " +
                "`sourceMessageId` INTEGER, " +
                "`calmScore` REAL NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`useCount` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastUsedAt` INTEGER )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_topics_phrase_targetLanguage` " +
                "ON `topics` (`phrase`, `targetLanguage`)",
        )
    }
}

/** Adds the serialized-story thread table (one running story per language). */
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `story_threads` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`targetLanguage` TEXT NOT NULL, " +
                "`chapter` INTEGER NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_story_threads_targetLanguage` " +
                "ON `story_threads` (`targetLanguage`)",
        )
    }
}

/**
 * Adds per-card [reviewDirection] (existing rows keep their behavior: TARGET_FIRST, or NATIVE_FIRST
 * for reverse imports) and reclassifies any card still carrying Anki cloze markup (`{{c1::…}}`) as
 * CLOZE so it renders as a fill-in-the-blank and drops out of the VOCAB/PHRASE conversation pool.
 */
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN reviewDirection TEXT NOT NULL DEFAULT 'TARGET_FIRST'")
        db.execSQL("UPDATE flashcards SET reviewDirection = 'NATIVE_FIRST' WHERE frontIsTarget = 0")
        db.execSQL(
            "UPDATE flashcards SET cardKind = 'CLOZE' " +
                "WHERE cardKind = 'VOCAB' AND (front LIKE '%{{%' OR back LIKE '%{{%')",
        )
    }
}

/**
 * Adds [FlashcardEntity.inflectedForms], a per-card cache of the word's plural/conjugated surface forms
 * used for in-text highlighting. Left NULL on existing rows ("not computed yet") so a one-time
 * background backfill (FlashcardRepository.ensureFormsBackfilled) fills them from the dictionary pack,
 * keeping the dictionary off the render path afterward.
 */
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN inflectedForms TEXT")
    }
}

/** Adds the per-card "marked" flag (Anki's term for a flagged card); existing rows default to unmarked. */
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN marked INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Language-profile scoping: the main list/queue queries now filter by targetLanguage/langCode,
 * so index those columns (flashcards especially can hold thousands of imported Anki cards).
 */
private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_targetLanguage` ON `conversations` (`targetLanguage`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_flashcards_targetLanguage` ON `flashcards` (`targetLanguage`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_items_langCode` ON `audio_items` (`langCode`)")
    }
}

/** Adds the rapid-fire listening quiz: one pool per language plus its pre-rendered audio cues. */
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quiz_pools` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`langCode` TEXT NOT NULL, " +
                "`configJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_quiz_pools_langCode` ON `quiz_pools` (`langCode`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quiz_cues` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`poolId` INTEGER NOT NULL, " +
                "`langCode` TEXT NOT NULL, " +
                "`cueType` TEXT NOT NULL, " +
                "`valueKey` TEXT NOT NULL, " +
                "`displayText` TEXT NOT NULL, " +
                "`spokenText` TEXT NOT NULL, " +
                "`sid` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`filePath` TEXT, " +
                "`durationMs` INTEGER, " +
                "`error` TEXT, " +
                "FOREIGN KEY(`poolId`) REFERENCES `quiz_pools`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_cues_poolId` ON `quiz_cues` (`poolId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_quiz_cues_poolId_status` ON `quiz_cues` (`poolId`, `status`)",
        )
    }
}

/**
 * Rapid-fire pools become per-(language, cue type): Numbers and Times are now separate top-level
 * activities, each with its own independent pool, so regenerating one never touches the other. The old
 * single shared pool row mixed both types under one config and can't be split cleanly, so the quiz
 * tables are dropped and recreated with the new key. The content-keyed WAVs survive on disk and are
 * re-adopted (READY, not re-synthesized) the next time each pool is generated.
 */
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `quiz_cues`")
        db.execSQL("DROP TABLE IF EXISTS `quiz_pools`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quiz_pools` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`langCode` TEXT NOT NULL, " +
                "`cueType` TEXT NOT NULL, " +
                "`configJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL )",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_quiz_pools_langCode_cueType` " +
                "ON `quiz_pools` (`langCode`, `cueType`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `quiz_cues` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`poolId` INTEGER NOT NULL, " +
                "`langCode` TEXT NOT NULL, " +
                "`cueType` TEXT NOT NULL, " +
                "`valueKey` TEXT NOT NULL, " +
                "`displayText` TEXT NOT NULL, " +
                "`spokenText` TEXT NOT NULL, " +
                "`sid` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`filePath` TEXT, " +
                "`durationMs` INTEGER, " +
                "`error` TEXT, " +
                "FOREIGN KEY(`poolId`) REFERENCES `quiz_pools`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_cues_poolId` ON `quiz_cues` (`poolId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_quiz_cues_poolId_status` ON `quiz_cues` (`poolId`, `status`)",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "copiosa.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            )
            .build()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideFlashcardDao(db: AppDatabase): FlashcardDao = db.flashcardDao()

    @Provides
    fun provideReviewLogDao(db: AppDatabase): ReviewLogDao = db.reviewLogDao()

    @Provides
    fun provideAudioItemDao(db: AppDatabase): AudioItemDao = db.audioItemDao()

    @Provides
    fun provideRoutineRunDao(db: AppDatabase): RoutineRunDao = db.routineRunDao()

    @Provides
    fun provideActivityAttemptDao(db: AppDatabase): ActivityAttemptDao = db.activityAttemptDao()

    @Provides
    fun provideActivityProficiencyDao(db: AppDatabase): ActivityProficiencyDao = db.activityProficiencyDao()

    @Provides
    fun provideTopicDao(db: AppDatabase): TopicDao = db.topicDao()

    @Provides
    fun provideStoryThreadDao(db: AppDatabase): StoryThreadDao = db.storyThreadDao()

    @Provides
    fun provideQuizDao(db: AppDatabase): QuizDao = db.quizDao()
}
