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
import com.copiosa.data.local.ConjugationDrillDao
import com.copiosa.data.local.ConversationDao
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FocusStatsDao
import com.copiosa.data.local.MessageDao
import com.copiosa.data.local.QuizDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.local.RoutineRunDao
import com.copiosa.data.local.StoryThreadDao
import com.copiosa.data.local.SuggestedTopicDao
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
                MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27,
                MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32,
                MIGRATION_32_33,
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
    fun provideSuggestedTopicDao(db: AppDatabase): SuggestedTopicDao = db.suggestedTopicDao()

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
}
