package com.copiosa.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.copiosa.data.local.ActivityAttemptDao
import com.copiosa.data.local.ActivityProficiencyDao
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.AudioItemDao
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.ClozeDao
import com.copiosa.data.local.ConjugationDrillDao
import com.copiosa.data.local.ConversationDao
import com.copiosa.data.local.CourseDao
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.PreMigrationSnapshot
import com.copiosa.data.local.FocusStatsDao
import com.copiosa.data.local.MessageDao
import com.copiosa.data.local.FLASHCARDS_VIEW_SQL
import com.copiosa.data.local.LlmUsageDao
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

/**
 * Phrase-based rapid-fire (Restaurant, …) stores its pre-seeded answer options on the cue row. Purely
 * additive — a nullable column existing Numbers/Times cues leave null — so no pool rebuild is needed.
 */
private val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE quiz_cues ADD COLUMN optionsJson TEXT")
    }
}

/**
 * Background-maintenance state, in one migration: a real played-at timestamp for audio retention
 * (previously proxied by createdAt), per-conversation auto-retry bookkeeping, and the
 * suggested-topics table.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration23To24Test`.
 */
internal val MIGRATION_23_24_SQL = listOf(
    "ALTER TABLE audio_items ADD COLUMN playedAt INTEGER",
    // One-time backfill: already-played rows adopt createdAt as their played-at proxy, so the
    // retention sweep needs no COALESCE and no legacy row is immortal.
    "UPDATE audio_items SET playedAt = createdAt WHERE played = 1",
    "ALTER TABLE conversations ADD COLUMN autoRetryAttempts INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE conversations ADD COLUMN lastAutoRetryAt INTEGER",
    "CREATE TABLE IF NOT EXISTS `suggested_topics` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`phrase` TEXT NOT NULL, " +
        "`rationale` TEXT, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`score` REAL NOT NULL, " +
        "`generatedAt` INTEGER NOT NULL, " +
        "`dismissedAt` INTEGER)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_suggested_topics_phrase_targetLanguage` " +
        "ON `suggested_topics` (`phrase`, `targetLanguage`)",
)

private val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_23_24_SQL.forEach(db::execSQL)
    }
}

/**
 * Focus-time stats: the raw `focus_sessions` log plus the `focus_daily_stats` daily rollups
 * (see `FocusStatsDao` for the union semantics between the two).
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration24To25Test`.
 */
internal val MIGRATION_24_25_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `focus_sessions` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`langTag` TEXT NOT NULL, " +
        "`activityType` TEXT NOT NULL, " +
        "`startedAt` INTEGER NOT NULL, " +
        "`durationMs` INTEGER NOT NULL, " +
        "`epochDay` INTEGER NOT NULL, " +
        "`sourceId` INTEGER, " +
        "`rolledUp` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_focus_sessions_langTag_epochDay` " +
        "ON `focus_sessions` (`langTag`, `epochDay`)",
    "CREATE INDEX IF NOT EXISTS `index_focus_sessions_rolledUp` ON `focus_sessions` (`rolledUp`)",
    "CREATE TABLE IF NOT EXISTS `focus_daily_stats` (" +
        "`langTag` TEXT NOT NULL, " +
        "`epochDay` INTEGER NOT NULL, " +
        "`activityType` TEXT NOT NULL, " +
        "`totalDurationMs` INTEGER NOT NULL, " +
        "`sessionCount` INTEGER NOT NULL, " +
        "PRIMARY KEY(`langTag`, `epochDay`, `activityType`))",
)

private val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_24_25_SQL.forEach(db::execSQL)
    }
}

/** Adds the per-card usage-note column (kept separate from the translation field). */
private val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN usageNote TEXT")
    }
}

/**
 * Adds the per-card user-set [FlashcardEntity.country] (the country a word is specific to, shown below
 * the language flag). Supersedes the retired auto-imported `region` slug, which is left in place.
 */
private val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flashcards ADD COLUMN country TEXT")
    }
}

/** Adds the raw model-output column on messages (a debug aid, kept only for the 30 most recent chats). */
internal val MIGRATION_27_28_SQL = listOf(
    "ALTER TABLE messages ADD COLUMN rawResponse TEXT",
)

private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_27_28_SQL.forEach(db::execSQL)
    }
}

/**
 * The conjugation drill's own store: `conjugation_item_stats` is the per-item aggregate the selector
 * reads, `conjugation_attempts` the append-only per-answer log the stats screen aggregates.
 *
 * The drill can't reuse `activity_attempts` — its `routineRunId` is NOT NULL, so it only holds
 * attempts made inside a routine — and it feeds `activity_proficiency` through
 * `RoutineAdaptation.recordStandalone` instead, exactly as the standalone quiz does.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration28To29Test`.
 */
internal val MIGRATION_28_29_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `conjugation_item_stats` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`itemKey` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`attempts` INTEGER NOT NULL, " +
        "`correct` INTEGER NOT NULL, " +
        "`mastery` REAL NOT NULL, " +
        "`avgLatencyMs` INTEGER NOT NULL, " +
        "`lastLatencyMs` INTEGER, " +
        "`lastSeenAt` INTEGER)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_conjugation_item_stats_itemKey_targetLanguage` " +
        "ON `conjugation_item_stats` (`itemKey`, `targetLanguage`)",
    "CREATE TABLE IF NOT EXISTS `conjugation_attempts` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`itemKey` TEXT NOT NULL, " +
        "`infinitive` TEXT NOT NULL, " +
        "`tense` TEXT NOT NULL, " +
        "`person` TEXT NOT NULL, " +
        "`direction` TEXT NOT NULL, " +
        "`family` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`grade` TEXT NOT NULL, " +
        "`correct` INTEGER NOT NULL, " +
        "`latencyMs` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`epochDay` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_conjugation_attempts_targetLanguage_epochDay` " +
        "ON `conjugation_attempts` (`targetLanguage`, `epochDay`)",
    "CREATE INDEX IF NOT EXISTS `index_conjugation_attempts_itemKey` " +
        "ON `conjugation_attempts` (`itemKey`)",
)

private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_28_29_SQL.forEach(db::execSQL)
    }
}

/**
 * Imported EPUB books: the `books` header table plus per-paragraph `book_paragraphs` rows (a novel
 * is never one TEXT column the way imported texts are). The unique (bookId, ord) index is the
 * reader's paging key; (bookId, sectionIndex) serves the section-translation queries.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration29To30Test`.
 */
internal val MIGRATION_29_30_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `books` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`title` TEXT NOT NULL, " +
        "`originalTitle` TEXT NOT NULL, " +
        "`author` TEXT, " +
        "`coverFile` TEXT, " +
        "`sourceFileName` TEXT, " +
        "`level` TEXT NOT NULL, " +
        "`country` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`nativeLanguage` TEXT NOT NULL, " +
        "`provider` TEXT NOT NULL, " +
        "`model` TEXT NOT NULL, " +
        "`totalParagraphs` INTEGER NOT NULL, " +
        "`wordCount` INTEGER NOT NULL, " +
        "`totalSections` INTEGER NOT NULL, " +
        "`translatedSections` INTEGER NOT NULL, " +
        "`autoTranslate` INTEGER NOT NULL, " +
        "`posParagraph` INTEGER NOT NULL, " +
        "`posOffset` INTEGER NOT NULL, " +
        "`finishedReading` INTEGER NOT NULL, " +
        "`starred` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, " +
        "`autoRetryAttempts` INTEGER NOT NULL, " +
        "`lastAutoRetryAt` INTEGER)",
    "CREATE INDEX IF NOT EXISTS `index_books_targetLanguage` ON `books` (`targetLanguage`)",
    "CREATE TABLE IF NOT EXISTS `book_paragraphs` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`bookId` INTEGER NOT NULL, " +
        "`ord` INTEGER NOT NULL, " +
        "`chapterIndex` INTEGER NOT NULL, " +
        "`sectionIndex` INTEGER NOT NULL, " +
        "`kind` TEXT NOT NULL, " +
        "`originalText` TEXT NOT NULL, " +
        "`translatedText` TEXT, " +
        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_book_paragraphs_bookId_ord` " +
        "ON `book_paragraphs` (`bookId`, `ord`)",
    "CREATE INDEX IF NOT EXISTS `index_book_paragraphs_bookId_sectionIndex` " +
        "ON `book_paragraphs` (`bookId`, `sectionIndex`)",
)

private val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_29_30_SQL.forEach(db::execSQL)
    }
}

/**
 * Clears the cached highlight forms on reverse cards (frontIsTarget = 0), whose front holds the native
 * gloss: they were previously expanded from that front, so reading text was matched against native
 * words. NULL means "not computed yet", so FlashcardRepository.ensureFormsBackfilled recomputes them —
 * now from the target side (see HighlightTargetProvider) — on the next reader open.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration30To31Test`.
 */
internal val MIGRATION_30_31_SQL = listOf(
    "UPDATE flashcards SET inflectedForms = NULL WHERE frontIsTarget = 0",
)

private val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_30_31_SQL.forEach(db::execSQL)
    }
}

/**
 * Adds the book translation direction. Every book that existed before this column was translated
 * *into* the target language, which is exactly what the FORWARD default says — so no backfill.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration31To32Test`.
 */
internal val MIGRATION_31_32_SQL = listOf(
    "ALTER TABLE books ADD COLUMN direction TEXT NOT NULL DEFAULT 'FORWARD'",
)

private val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_31_32_SQL.forEach(db::execSQL)
    }
}

/**
 * Clears every cached highlight form so they are recomputed with the part-of-speech filter added to
 * HighlightTargetProvider. Without it a homograph headword hopped into the wrong lemma's paradigm —
 * the noun "son" (the music genre) collected soy/eres/es/… from the verb "ser", so tapping "es" in a
 * text landed on the noun card. The bad forms are already baked into the column, and the backfill
 * only visits NULL rows, so a full clear is what forces the repair.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration32To33Test`.
 */
internal val MIGRATION_32_33_SQL = listOf(
    "UPDATE flashcards SET inflectedForms = NULL",
)

private val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_32_33_SQL.forEach(db::execSQL)
    }
}

/**
 * Per-item override of the language's "highlight saved words" setting. Nullable on purpose: NULL
 * means "follow the setting", which is what every existing row must stay on — a boolean with a
 * default would freeze the whole library at whatever the flag happened to be at upgrade time.
 */
private val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN highlightWords INTEGER")
        db.execSQL("ALTER TABLE books ADD COLUMN highlightWords INTEGER")
    }
}

/**
 * Replaces the per-book `autoTranslate` boolean with the four-way `downloadMode`, and starts
 * recording which model translated each section.
 *
 * `books` is rebuilt rather than altered because SQLite before 3.35 (i.e. most of the minSdk range)
 * has no DROP COLUMN, and leaving the retired boolean behind would fail Room's schema check. The
 * CREATE TABLE is [MIGRATION_29_30_SQL]'s proven statement plus the columns 31→32 and 33→34 added,
 * minus `autoTranslate`, so the wording is copied rather than re-derived.
 *
 * The backfill is where the old two-flag world maps onto the new one:
 *  - `autoTranslate = 1` was exactly "keep translating in the background" -> BACKGROUND.
 *  - a REVERSE book without it never opted into its native gloss and costs nothing today -> OFF,
 *    which is the only value that keeps it free.
 *  - everything else is a forward book that translated as it was read -> WHILE_READING.
 *
 * The profile-wide `bookAutoReadAhead` preference lived in DataStore and is invisible from here, so
 * a profile that had turned it *off* comes out with its forward books in WHILE_READING; the new
 * dialog is where those go back to OFF.
 *
 * `book_sections` is backfilled from the book's own provider/model snapshot, which is what every
 * existing translation was in fact produced with — so the dialog opens with nothing stale to redo.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration34To35Test`.
 */
internal val MIGRATION_34_35_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `books_new` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`title` TEXT NOT NULL, " +
        "`originalTitle` TEXT NOT NULL, " +
        "`author` TEXT, " +
        "`coverFile` TEXT, " +
        "`sourceFileName` TEXT, " +
        "`level` TEXT NOT NULL, " +
        "`direction` TEXT NOT NULL, " +
        "`country` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`nativeLanguage` TEXT NOT NULL, " +
        "`provider` TEXT NOT NULL, " +
        "`model` TEXT NOT NULL, " +
        "`totalParagraphs` INTEGER NOT NULL, " +
        "`wordCount` INTEGER NOT NULL, " +
        "`totalSections` INTEGER NOT NULL, " +
        "`translatedSections` INTEGER NOT NULL, " +
        "`downloadMode` TEXT NOT NULL, " +
        "`posParagraph` INTEGER NOT NULL, " +
        "`posOffset` INTEGER NOT NULL, " +
        "`finishedReading` INTEGER NOT NULL, " +
        "`starred` INTEGER NOT NULL, " +
        "`highlightWords` INTEGER, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`updatedAt` INTEGER NOT NULL, " +
        "`autoRetryAttempts` INTEGER NOT NULL, " +
        "`lastAutoRetryAt` INTEGER)",
    "INSERT INTO `books_new` (" +
        "`id`, `title`, `originalTitle`, `author`, `coverFile`, `sourceFileName`, `level`, " +
        "`direction`, `country`, `targetLanguage`, `nativeLanguage`, `provider`, `model`, " +
        "`totalParagraphs`, `wordCount`, `totalSections`, `translatedSections`, `downloadMode`, " +
        "`posParagraph`, `posOffset`, `finishedReading`, `starred`, `highlightWords`, " +
        "`createdAt`, `updatedAt`, `autoRetryAttempts`, `lastAutoRetryAt`) " +
        "SELECT `id`, `title`, `originalTitle`, `author`, `coverFile`, `sourceFileName`, `level`, " +
        "`direction`, `country`, `targetLanguage`, `nativeLanguage`, `provider`, `model`, " +
        "`totalParagraphs`, `wordCount`, `totalSections`, `translatedSections`, " +
        "CASE WHEN `autoTranslate` <> 0 THEN 'BACKGROUND' " +
        "WHEN `direction` = 'REVERSE' THEN 'OFF' ELSE 'WHILE_READING' END, " +
        "`posParagraph`, `posOffset`, `finishedReading`, `starred`, `highlightWords`, " +
        "`createdAt`, `updatedAt`, `autoRetryAttempts`, `lastAutoRetryAt` FROM `books`",
    "DROP TABLE `books`",
    "ALTER TABLE `books_new` RENAME TO `books`",
    "CREATE INDEX IF NOT EXISTS `index_books_targetLanguage` ON `books` (`targetLanguage`)",
    "CREATE TABLE IF NOT EXISTS `book_sections` (" +
        "`bookId` INTEGER NOT NULL, " +
        "`sectionIndex` INTEGER NOT NULL, " +
        "`provider` TEXT NOT NULL, " +
        "`model` TEXT NOT NULL, " +
        "`translatedAt` INTEGER NOT NULL, " +
        "PRIMARY KEY(`bookId`, `sectionIndex`), " +
        "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "INSERT INTO `book_sections` (`bookId`, `sectionIndex`, `provider`, `model`, `translatedAt`) " +
        "SELECT b.`id`, p.`sectionIndex`, b.`provider`, b.`model`, b.`updatedAt` " +
        "FROM `books` b JOIN `book_paragraphs` p ON p.`bookId` = b.`id` " +
        "WHERE p.`translatedText` IS NOT NULL GROUP BY b.`id`, p.`sectionIndex`",
)

private val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_34_35_SQL.forEach(db::execSQL)
    }
}

/**
 * Book audio: groups the flat playlist into folders and gives a track a resume point.
 *
 * `tag` is the grouping label (a book's chapters share the book's title) and is what the Audio pane
 * folds on; `sourceBookId`/`sourceChapterIndex` are provenance, so a later pass can tell which
 * chapters already have a track without matching on a name the user can see change.
 *
 * Plain ALTERs, with no foreign key on `sourceBookId`: `audio_items` has never had one, and adding a
 * constraint would mean the whole create/INSERT-SELECT/DROP/RENAME rebuild that 34->35 needed for
 * `books`. Deleting a book removes its audio explicitly in BookRepository instead — the rows outlive
 * a cascade deliberately nowhere, and the files have to be unlinked in code regardless.
 *
 * `positionMs` defaults to 0, which is exactly the old behaviour (every track started at the top),
 * so existing rows need no backfill.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration35To36Test`.
 */
internal val MIGRATION_35_36_SQL = listOf(
    "ALTER TABLE `audio_items` ADD COLUMN `tag` TEXT",
    "ALTER TABLE `audio_items` ADD COLUMN `sourceBookId` INTEGER",
    "ALTER TABLE `audio_items` ADD COLUMN `sourceChapterIndex` INTEGER",
    "ALTER TABLE `audio_items` ADD COLUMN `positionMs` INTEGER NOT NULL DEFAULT 0",
    "CREATE INDEX IF NOT EXISTS `index_audio_items_tag` ON `audio_items` (`tag`)",
)

private val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_35_36_SQL.forEach(db::execSQL)
    }
}

/**
 * 36 -> 37: records how an auto-generated flashcard came to exist.
 *
 * The frequency-list seeder can add hundreds of cards in one tap. Without a marker, a batch produced
 * by a bad translation pass or a wrong list would be indistinguishable from words the user saved
 * themselves, and the only way to undo it would be date-guessing. `autoSource` names the mechanism
 * ("freq"), so such a batch can be counted and removed exactly.
 *
 * A nullable column with no default: every existing row is user-authored, and NULL is precisely what
 * that means, so there is nothing to backfill.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration36To37Test`.
 */
internal val MIGRATION_36_37_SQL = listOf(
    "ALTER TABLE `flashcards` ADD COLUMN `autoSource` TEXT",
)

private val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_36_37_SQL.forEach(db::execSQL)
    }
}

/**
 * 37 -> 38: the tense-course tables — enrolment/checkup standing per course, per-lesson results,
 * and course sittings (`planJson` + `currentIndex`, mirroring `routine_runs`). Status/kind columns
 * hold enum *names* as plain TEXT (`CourseStatus`, `SessionKind`, `CourseSessionStatus` — frozen),
 * the same trade-off `conjugation_attempts` makes.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration37To38Test`.
 */
internal val MIGRATION_37_38_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `course_progress` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`courseId` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`status` TEXT NOT NULL, " +
        "`startedAt` INTEGER NOT NULL, " +
        "`graduatedAt` INTEGER, " +
        "`checkupStage` INTEGER NOT NULL, " +
        "`nextCheckupAt` INTEGER, " +
        "`lastCheckupAt` INTEGER)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_course_progress_courseId_targetLanguage` " +
        "ON `course_progress` (`courseId`, `targetLanguage`)",
    "CREATE TABLE IF NOT EXISTS `course_lesson_state` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`courseId` TEXT NOT NULL, " +
        "`lessonId` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`completedAt` INTEGER, " +
        "`passes` INTEGER NOT NULL, " +
        "`lastAccuracy` REAL, " +
        "`needsReview` INTEGER NOT NULL, " +
        "`generatedItemsJson` TEXT)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_course_lesson_state_courseId_lessonId_targetLanguage` " +
        "ON `course_lesson_state` (`courseId`, `lessonId`, `targetLanguage`)",
    "CREATE TABLE IF NOT EXISTS `course_sessions` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`courseId` TEXT NOT NULL, " +
        "`lessonId` TEXT, " +
        "`kind` TEXT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`status` TEXT NOT NULL, " +
        "`planJson` TEXT, " +
        "`currentIndex` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, " +
        "`completedAt` INTEGER, " +
        "`error` TEXT)",
    "CREATE INDEX IF NOT EXISTS `index_course_sessions_courseId_targetLanguage` " +
        "ON `course_sessions` (`courseId`, `targetLanguage`)",
)

private val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_37_38_SQL.forEach(db::execSQL)
    }
}

/** Why a book's last translation attempt failed, so a background stall can explain itself. */
internal val MIGRATION_38_39_SQL = listOf(
    "ALTER TABLE books ADD COLUMN lastError TEXT",
)

private val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_38_39_SQL.forEach(db::execSQL)
    }
}

/**
 * The cloze practice bank: sentences the learner has already met, one word blanked, each carrying
 * its own FSRS schedule, plus the append-only log of answers on them.
 *
 * The unique (targetLanguage, lemma, textKey) index is what makes the background miners idempotent —
 * they insert with IGNORE, so rescanning the same book costs nothing — and what makes a suspended
 * row a tombstone that stops a rejected sentence being re-added.
 *
 * `cardId` is intentionally an unconstrained column rather than a foreign key: deleting a vocab card
 * must not delete a good sentence, it must only stop it nudging.
 *
 * No column carries a SQL DEFAULT, matching every other new-table migration here: the Kotlin
 * entity's defaults construct new rows, and Room compares declared defaults when it validates the
 * schema on open — a DEFAULT the entity doesn't declare fails that check on the first launch after
 * upgrade.
 */
internal val MIGRATION_39_40_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `cloze_sentences` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`targetLanguage` TEXT NOT NULL, " +
        "`text` TEXT NOT NULL, " +
        "`answer` TEXT NOT NULL, " +
        "`lemma` TEXT NOT NULL, " +
        "`textKey` TEXT NOT NULL, " +
        "`cardId` INTEGER, " +
        "`translation` TEXT, " +
        "`distractors` TEXT, " +
        "`source` TEXT NOT NULL, " +
        "`suspended` INTEGER NOT NULL, " +
        "`flaggedReason` TEXT, " +
        "`dueAt` INTEGER NOT NULL, " +
        "`intervalDays` REAL NOT NULL, " +
        "`stability` REAL, " +
        "`difficulty` REAL, " +
        "`fsrsStep` INTEGER, " +
        "`repetitions` INTEGER NOT NULL, " +
        "`lapses` INTEGER NOT NULL, " +
        "`lastReviewedAt` INTEGER, " +
        "`state` TEXT NOT NULL, " +
        "`ankiNoteGuid` TEXT, " +
        "`ankiOrd` INTEGER, " +
        "`createdAt` INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS `index_cloze_sentences_targetLanguage_dueAt` " +
        "ON `cloze_sentences` (`targetLanguage`, `dueAt`)",
    "CREATE INDEX IF NOT EXISTS `index_cloze_sentences_cardId` ON `cloze_sentences` (`cardId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_cloze_sentences_targetLanguage_lemma_textKey` " +
        "ON `cloze_sentences` (`targetLanguage`, `lemma`, `textKey`)",
    "CREATE TABLE IF NOT EXISTS `cloze_reviews` (" +
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`clozeId` INTEGER NOT NULL, " +
        "`reviewedAt` INTEGER NOT NULL, " +
        "`rating` TEXT NOT NULL, " +
        "`lastIntervalDays` REAL NOT NULL, " +
        "`intervalDays` REAL NOT NULL, " +
        "`stabilityAfter` REAL, " +
        "`difficultyAfter` REAL, " +
        "`state` TEXT NOT NULL, " +
        "`latencyMs` INTEGER, " +
        "FOREIGN KEY(`clozeId`) REFERENCES `cloze_sentences`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_cloze_reviews_clozeId` ON `cloze_reviews` (`clozeId`)",
)

private val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_39_40_SQL.forEach(db::execSQL)
    }
}

/**
 * 40 -> 41: the language version each reader was left in, stored beside the reading position it
 * already restores (`conversations.scrollIndex`, `books.posParagraph`).
 *
 * Enum *name* as plain TEXT, the `downloadMode` trade-off, with `Converters.stringToReadMode`
 * falling back to TARGET on anything it can't parse. The SQL default is TARGET too, which is
 * precisely the old behaviour — every reader opened in the target language — so nothing needs
 * backfilling and no existing text or book changes how it looks on first open after the upgrade.
 *
 * The column goes on `conversations` as a whole even though only imported texts (URL_TRANSLATION)
 * have a reader to restore; chats simply carry an unread TARGET, as they already do for
 * `scrollIndex`.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration40To41Test`.
 */
internal val MIGRATION_40_41_SQL = listOf(
    "ALTER TABLE `conversations` ADD COLUMN `readMode` TEXT NOT NULL DEFAULT 'TARGET'",
    "ALTER TABLE `books` ADD COLUMN `readMode` TEXT NOT NULL DEFAULT 'TARGET'",
)

private val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_40_41_SQL.forEach(db::execSQL)
    }
}

/**
 * 41 -> 42: when a book's reading position last moved, so Home can offer the book actually being
 * read. Until now it ordered by `updatedAt`, which only the translation pipeline writes — a book
 * downloading in the background outranked the one open on the nightstand.
 *
 * Nullable with no default and no backfill: nothing recorded when the existing books were last
 * read, and copying `updatedAt` across would bake in exactly the wrong answer this column exists to
 * replace. A NULL sorts behind any real reading time and falls back to the old order among
 * themselves, so the first scroll after the upgrade puts the right book on top.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration41To42Test`.
 */
internal val MIGRATION_41_42_SQL = listOf(
    "ALTER TABLE `books` ADD COLUMN `lastReadAt` INTEGER",
)

private val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_41_42_SQL.forEach(db::execSQL)
    }
}

/** 42 -> 43: the notes/cards split. The statements, and the reasoning, are in `Migration42To43.kt`. */
private val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_42_43_SQL.forEach(db::execSQL)
    }
}

/**
 * 43 -> 44: drops `suggested_topics`, the table `TopicSuggestionTask` refilled every few days for a
 * surface that was never built. Nothing read it. Its unique index goes with the table.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration43To44Test`.
 */
internal val MIGRATION_43_44_SQL = listOf(
    "DROP TABLE IF EXISTS `suggested_topics`",
)

private val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_43_44_SQL.forEach(db::execSQL)
    }
}

/**
 * 44 -> 45: where a saved word came from, beyond a conversation. `notes` gains `sourceBookId` and
 * `sourceRoutineRunId`, so a word saved in a book or a routine's reading step records its source the
 * way one saved in a chat or text always has, and each reader can list the words it produced.
 *
 * Nothing to backfill: those saves were never recorded, and a guess from timestamps would be worse
 * than the gap. The `flashcards` view has to be dropped and recreated, since a view's column list is
 * fixed when it is made; its text is [FLASHCARDS_VIEW_SQL], which Room checks character for character.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration44To45Test`.
 */
internal val MIGRATION_44_45_SQL = listOf(
    "ALTER TABLE `notes` ADD COLUMN `sourceBookId` INTEGER",
    "ALTER TABLE `notes` ADD COLUMN `sourceRoutineRunId` INTEGER",
    "DROP VIEW IF EXISTS `flashcards`",
    "CREATE VIEW `flashcards` AS $FLASHCARDS_VIEW_SQL",
)

private val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_44_45_SQL.forEach(db::execSQL)
    }
}

/**
 * 45 -> 46: the usage meter. Both providers report what each call consumed and until now it was
 * dropped on the floor; `llm_usage` keeps it as a running total per local day for each (provider,
 * model, feature, book). See `LlmUsageEntity` for why `costReported` is in the key and why `bookId`
 * is not a foreign key.
 *
 * Nothing to backfill: usage that was never recorded cannot be recovered, and an estimate from the
 * stored text would put invented numbers under a heading that promises measured ones.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration45To46Test`.
 */
internal val MIGRATION_45_46_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `llm_usage` (" +
        "`day` INTEGER NOT NULL, " +
        "`provider` TEXT NOT NULL, " +
        "`model` TEXT NOT NULL, " +
        "`feature` TEXT NOT NULL, " +
        "`bookId` INTEGER NOT NULL, " +
        "`costReported` INTEGER NOT NULL, " +
        "`calls` INTEGER NOT NULL, " +
        "`promptTokens` INTEGER NOT NULL, " +
        "`cachedTokens` INTEGER NOT NULL, " +
        "`outputTokens` INTEGER NOT NULL, " +
        "`costUsd` REAL NOT NULL, " +
        "PRIMARY KEY(`day`, `provider`, `model`, `feature`, `bookId`, `costReported`))",
)

private val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_45_46_SQL.forEach(db::execSQL)
    }
}

/**
 * 46 -> 47: a playlist row says what its text is.
 *
 * Until now every `audio_items` row was prose to be read through. A cloze radio track is a script —
 * sentences with their translations, spoken in two languages with pauses between — and the render
 * queue needs to tell the two apart. `kind` is a plain TEXT slug, as on `book_paragraphs`, so the
 * next kind is not another migration.
 *
 * The default is the old behaviour, so existing rows need no backfill.
 *
 * The same step gives a banked cloze sentence two flags. `pinned` is the learner keeping one: it is
 * favoured in a sitting and exempt from the bank's rotation. `machineTranslated` marks a translation
 * the on-device translator supplied, so the background model pass knows it may replace it. Both
 * default to off, which is what every existing row is.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration46To47Test`.
 */
internal val MIGRATION_46_47_SQL = listOf(
    "ALTER TABLE `audio_items` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'ARTICLE'",
    "ALTER TABLE `cloze_sentences` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `cloze_sentences` ADD COLUMN `machineTranslated` INTEGER NOT NULL DEFAULT 0",
)

private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_46_47_SQL.forEach(db::execSQL)
    }
}

/**
 * 47 -> 48: repairs the cached highlight forms of terms that carry a hint in brackets.
 *
 * A note saved as "pasa (fruta)" cached the literal string "pasa (fruta" as its only form — the
 * normalizer trimmed the closing bracket and nothing else — so the word never lit up in a text.
 * HighlightTargetProvider now drops the hint before computing forms, but the backfill only visits
 * NULL rows, so the bad ones are cleared here. A stored form containing an opening bracket can only
 * have come from such a term (a form is a single word), so that is what selects them.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration47To48Test`.
 */
internal val MIGRATION_47_48_SQL = listOf(
    "UPDATE notes SET inflectedForms = NULL WHERE inflectedForms LIKE '%(%'",
)

private val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_47_48_SQL.forEach(db::execSQL)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "copiosa.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        // 43 restructures the vocabulary instead of adding to it, so the file is copied aside first.
        PreMigrationSnapshot.takeIfOlderThan(context, DATABASE_NAME, beforeVersion = 43)
        return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27,
                MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32,
                MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36,
                MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40,
                MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45,
                MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48,
            )
            .build()
    }

    @Provides
    fun provideClozeDao(db: AppDatabase): ClozeDao = db.clozeDao()

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

    @Provides
    fun provideFocusStatsDao(db: AppDatabase): FocusStatsDao = db.focusStatsDao()

    @Provides
    fun provideConjugationDrillDao(db: AppDatabase): ConjugationDrillDao = db.conjugationDrillDao()

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()

    @Provides
    fun provideLlmUsageDao(db: AppDatabase): LlmUsageDao = db.llmUsageDao()
}
