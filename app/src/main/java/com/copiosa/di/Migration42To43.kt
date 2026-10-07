package com.copiosa.di


/**
 * The `flashcards` view as schema 43 defined it. [MIGRATION_42_43_SQL] creates this one, not the live
 * [FLASHCARDS_VIEW_SQL]: at 43 `notes` has no source-book or routine-run column yet, and a migration
 * must keep meaning what it meant when it shipped. 44 -> 45 recreates the view with the new text.
 */
internal const val FLASHCARDS_VIEW_SQL_V43 =
    "SELECT c.id AS id, n.term AS front, n.translation AS back, n.contextSentence AS contextSentence, " +
        "n.sourceConversationId AS sourceConversationId, n.explanation AS explanation, " +
        "n.usageNote AS usageNote, n.partOfSpeech AS partOfSpeech, n.cefrLevel AS cefrLevel, " +
        "n.cardKind AS cardKind, n.gender AS gender, n.region AS region, n.country AS country, " +
        "n.altForms AS altForms, n.inflectedForms AS inflectedForms, 1 AS frontIsTarget, " +
        "c.direction AS reviewDirection, n.targetLanguage AS targetLanguage, " +
        "n.nativeLanguage AS nativeLanguage, n.createdAt AS createdAt, c.dueAt AS dueAt, " +
        "c.intervalDays AS intervalDays, c.easeFactor AS easeFactor, c.repetitions AS repetitions, " +
        "c.lapses AS lapses, c.lastReviewedAt AS lastReviewedAt, c.state AS state, " +
        "c.stability AS stability, c.difficulty AS difficulty, c.fsrsStep AS fsrsStep, " +
        "c.suspended AS suspended, n.marked AS marked, n.autoSource AS autoSource, " +
        "n.ankiNoteGuid AS ankiNoteGuid, c.ankiOrd AS ankiOrd, n.ankiNoteTypeId AS ankiNoteTypeId, " +
        "n.ankiFieldsJson AS ankiFieldsJson, n.ankiTags AS ankiTags, n.ankiDeckName AS ankiDeckName, " +
        "n.imageFile AS imageFile, n.audioFile AS audioFile, c.noteId AS noteId " +
        "FROM cards c INNER JOIN notes n ON n.id = c.noteId"

/**
 * 42 -> 43: splits `flashcards` into `notes` (what a word *is*) and `cards` (one direction of it, with
 * its own schedule), and puts a `flashcards` **view** over the pair so every existing query still
 * reads what it read before. Anki's architecture, adopted because the old one let content drift:
 * an imported note was two whole rows, each with its own copy of the text.
 *
 * What the statements below decide, in order:
 *
 * - **Which rows are one note.** Exactly two rows sharing an `ankiNoteGuid`, one facing each way
 *   (`_pairs`). Anything else — a single card, or a guid with three rows — becomes a note per row
 *   rather than being guessed at.
 * - **Ids.** A card keeps its flashcard id, so `reviews.cardId` needs no rewriting. A note takes the
 *   id of its term-first row, which is unique for free and needs no mapping table afterwards.
 * - **What a merged note says.** The term-first row wins, field by field, falling back to the other
 *   row where its own value is blank — so a card that only ever held a picture takes its text from
 *   its twin, and a part of speech of "unknown" loses to a real one. Marked if either was.
 * - **`BOTH` goes away.** A single row that alternated sides becomes two cards, the second starting
 *   on a copy of the first's schedule: that schedule was earned in both directions. Only word,
 *   phrase and sentence notes; a cloze or conjugation card has one direction by nature.
 * - **Every word is learned both ways.** A word, phrase or sentence note left with one card gains a
 *   new one for the missing direction, frozen if its sibling is, and released ten a day so a few
 *   hundred don't arrive in one morning.
 *
 * `reviews` is rebuilt rather than altered because its foreign key has to name `cards`, and SQLite
 * cannot repoint one in place. The old table is kept as `flashcards_v42` for one release: every row
 * of it is superseded, but it costs a few megabytes and is the difference between a bad merge rule
 * being a bug and being a loss.
 *
 * No window functions — `minSdk` 26 ships SQLite 3.18 — so the ten-a-day stagger counts earlier rows
 * with a correlated subquery.
 *
 * Exposed as a statement list so a JVM test can replay it against plain SQLite — see
 * `Migration42To43Test`. The table and index definitions must stay in step with what Room generates
 * for [com.copiosa.data.local.NoteEntity], [com.copiosa.data.local.CardEntity] and
 * [com.copiosa.data.local.ReviewLogEntity]; the view's text must match [FLASHCARDS_VIEW_SQL_V43] exactly.
 */
internal val MIGRATION_42_43_SQL: List<String> = run {
    val lexical = "('VOCAB', 'PHRASE', 'SENTENCE')"
    val paired = "(SELECT fid FROM `_pairs` UNION ALL SELECT rid FROM `_pairs`)"
    val noteColumns =
        "`id`, `term`, `translation`, `contextSentence`, `sourceConversationId`, `explanation`, `usageNote`, " +
            "`partOfSpeech`, `cefrLevel`, `cardKind`, `gender`, `region`, `country`, `altForms`, `inflectedForms`, " +
            "`targetLanguage`, `nativeLanguage`, `createdAt`, `marked`, `autoSource`, `ankiNoteGuid`, " +
            "`ankiNoteTypeId`, `ankiFieldsJson`, `ankiTags`, `ankiDeckName`, `imageFile`, `audioFile`"
    val scheduleColumns =
        "`dueAt`, `intervalDays`, `easeFactor`, `repetitions`, `lapses`, `lastReviewedAt`, `state`, " +
            "`stability`, `difficulty`, `fsrsStep`, `suspended`"
    // The same columns read off an old row aliased `c`.
    val sourceSchedule =
        "c.`dueAt`, c.`intervalDays`, c.`easeFactor`, c.`repetitions`, c.`lapses`, c.`lastReviewedAt`, c.`state`, " +
            "c.`stability`, c.`difficulty`, c.`fsrsStep`, c.`suspended`"
    val reviewColumns =
        "`id`, `cardId`, `reviewedAt`, `rating`, `lastIntervalDays`, `intervalDays`, `stabilityAfter`, " +
            "`difficultyAfter`, `easeFactor`, `state`, `reviewDurationMs`"

    /** `f`'s value unless it is blank, then `r`'s. */
    fun either(column: String) = "COALESCE(NULLIF(TRIM(f.`$column`), ''), r.`$column`)"

    // A word, phrase or sentence note that would otherwise be left with a single card.
    val oneWay = "c.`cardKind` IN $lexical AND c.`reviewDirection` <> 'BOTH' AND c.`id` NOT IN $paired"

    listOf(
        "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `term` TEXT NOT NULL, " +
            "`translation` TEXT NOT NULL, `contextSentence` TEXT, `sourceConversationId` INTEGER, `explanation` TEXT, " +
            "`usageNote` TEXT, `partOfSpeech` TEXT, `cefrLevel` TEXT, `cardKind` TEXT NOT NULL, `gender` TEXT, " +
            "`region` TEXT, `country` TEXT, `altForms` TEXT, `inflectedForms` TEXT, `targetLanguage` TEXT NOT NULL, " +
            "`nativeLanguage` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `marked` INTEGER NOT NULL, `autoSource` TEXT, " +
            "`ankiNoteGuid` TEXT, `ankiNoteTypeId` INTEGER, `ankiFieldsJson` TEXT, `ankiTags` TEXT, `ankiDeckName` TEXT, " +
            "`imageFile` TEXT, `audioFile` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_notes_targetLanguage` ON `notes` (`targetLanguage`)",
        "CREATE INDEX IF NOT EXISTS `index_notes_ankiNoteGuid` ON `notes` (`ankiNoteGuid`)",
        "CREATE TABLE IF NOT EXISTS `cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
            "`direction` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `intervalDays` REAL NOT NULL, `easeFactor` REAL NOT NULL, " +
            "`repetitions` INTEGER NOT NULL, `lapses` INTEGER NOT NULL, `lastReviewedAt` INTEGER, `state` TEXT NOT NULL, " +
            "`stability` REAL, `difficulty` REAL, `fsrsStep` INTEGER, `suspended` INTEGER NOT NULL, `ankiOrd` INTEGER, " +
            "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_cards_noteId_direction` ON `cards` (`noteId`, `direction`)",

        // --- which rows are one note ---
        "CREATE INDEX `_flashcards_guid` ON `flashcards` (`ankiNoteGuid`)",
        "CREATE TABLE `_pairs` AS SELECT f.`id` AS fid, r.`id` AS rid FROM `flashcards` f JOIN `flashcards` r " +
            "ON r.`ankiNoteGuid` = f.`ankiNoteGuid` AND r.`targetLanguage` = f.`targetLanguage` " +
            "AND f.`frontIsTarget` = 1 AND r.`frontIsTarget` = 0 " +
            "WHERE (SELECT COUNT(*) FROM `flashcards` x WHERE x.`ankiNoteGuid` = f.`ankiNoteGuid` " +
            "AND x.`targetLanguage` = f.`targetLanguage`) = 2",

        // --- notes: a merged one per pair, then one per remaining row, turned the right way round ---
        "INSERT INTO `notes` ($noteColumns) SELECT f.`id`, " +
            "COALESCE(NULLIF(TRIM(f.`front`), ''), r.`back`), COALESCE(NULLIF(TRIM(f.`back`), ''), r.`front`), " +
            "${either("contextSentence")}, COALESCE(f.`sourceConversationId`, r.`sourceConversationId`), " +
            "${either("explanation")}, ${either("usageNote")}, " +
            "CASE WHEN f.`partOfSpeech` IS NULL OR LOWER(TRIM(f.`partOfSpeech`)) IN ('', 'unknown') " +
            "THEN COALESCE(NULLIF(TRIM(r.`partOfSpeech`), ''), f.`partOfSpeech`) ELSE f.`partOfSpeech` END, " +
            "${either("cefrLevel")}, f.`cardKind`, ${either("gender")}, COALESCE(f.`region`, r.`region`), " +
            "${either("country")}, ${either("altForms")}, f.`inflectedForms`, f.`targetLanguage`, f.`nativeLanguage`, " +
            "MIN(f.`createdAt`, r.`createdAt`), MAX(f.`marked`, r.`marked`), COALESCE(f.`autoSource`, r.`autoSource`), " +
            "f.`ankiNoteGuid`, COALESCE(f.`ankiNoteTypeId`, r.`ankiNoteTypeId`), " +
            "COALESCE(f.`ankiFieldsJson`, r.`ankiFieldsJson`), COALESCE(f.`ankiTags`, r.`ankiTags`), " +
            "COALESCE(f.`ankiDeckName`, r.`ankiDeckName`), COALESCE(f.`imageFile`, r.`imageFile`), " +
            "COALESCE(f.`audioFile`, r.`audioFile`) " +
            "FROM `_pairs` p JOIN `flashcards` f ON f.`id` = p.fid JOIN `flashcards` r ON r.`id` = p.rid",
        "INSERT INTO `notes` ($noteColumns) SELECT c.`id`, " +
            "CASE WHEN c.`frontIsTarget` = 1 THEN c.`front` ELSE c.`back` END, " +
            "CASE WHEN c.`frontIsTarget` = 1 THEN c.`back` ELSE c.`front` END, " +
            "c.`contextSentence`, c.`sourceConversationId`, c.`explanation`, c.`usageNote`, c.`partOfSpeech`, " +
            "c.`cefrLevel`, c.`cardKind`, c.`gender`, c.`region`, c.`country`, c.`altForms`, c.`inflectedForms`, " +
            "c.`targetLanguage`, c.`nativeLanguage`, c.`createdAt`, c.`marked`, c.`autoSource`, c.`ankiNoteGuid`, " +
            "c.`ankiNoteTypeId`, c.`ankiFieldsJson`, c.`ankiTags`, c.`ankiDeckName`, c.`imageFile`, c.`audioFile` " +
            "FROM `flashcards` c WHERE c.`id` NOT IN $paired",

        // --- cards: every old row, under its old id ---
        "INSERT INTO `cards` (`id`, `noteId`, `direction`, $scheduleColumns, `ankiOrd`) SELECT c.`id`, " +
            "COALESCE((SELECT fid FROM `_pairs` WHERE rid = c.`id`), c.`id`), " +
            // A pair's direction is which way the row faced, whatever its direction column says: the
            // two must differ, and a paired row that had been set to BOTH would otherwise collide.
            "CASE WHEN c.`id` IN (SELECT rid FROM `_pairs`) THEN 'NATIVE_FIRST' " +
            "WHEN c.`id` IN (SELECT fid FROM `_pairs`) THEN 'TARGET_FIRST' " +
            "WHEN c.`reviewDirection` = 'NATIVE_FIRST' THEN 'NATIVE_FIRST' ELSE 'TARGET_FIRST' END, " +
            "$sourceSchedule, " +
            "c.`ankiOrd` FROM `flashcards` c",

        // --- a BOTH card becomes two, the second on a copy of the schedule both directions earned ---
        "INSERT INTO `cards` (`noteId`, `direction`, $scheduleColumns, `ankiOrd`) SELECT c.`id`, 'NATIVE_FIRST', " +
            "$sourceSchedule, " +
            "CASE WHEN c.`ankiNoteGuid` IS NULL THEN NULL WHEN c.`ankiOrd` = 0 THEN 1 ELSE 0 END " +
            "FROM `flashcards` c WHERE c.`reviewDirection` = 'BOTH' AND c.`cardKind` IN $lexical AND c.`id` NOT IN $paired",

        // --- a one-way word gains its missing direction as a new card, ten released a day ---
        "INSERT INTO `cards` (`noteId`, `direction`, $scheduleColumns, `ankiOrd`) SELECT c.`id`, " +
            "CASE WHEN c.`reviewDirection` = 'NATIVE_FIRST' THEN 'TARGET_FIRST' ELSE 'NATIVE_FIRST' END, " +
            "CAST(strftime('%s', 'now') AS INTEGER) * 1000 + " +
            "((SELECT COUNT(*) FROM `flashcards` o WHERE ${oneWay.replace("c.`", "o.`")} AND o.`id` < c.`id`) / 10) * 86400000, " +
            "0.0, 2.5, 0, 0, NULL, 'NEW', NULL, NULL, NULL, c.`suspended`, " +
            "CASE WHEN c.`ankiNoteGuid` IS NULL THEN NULL WHEN c.`ankiOrd` = 0 THEN 1 ELSE 0 END " +
            "FROM `flashcards` c WHERE $oneWay",

        // --- reviews: same rows, foreign key repointed at cards ---
        "CREATE TABLE IF NOT EXISTS `reviews_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, " +
            "`reviewedAt` INTEGER NOT NULL, `rating` TEXT NOT NULL, `lastIntervalDays` REAL NOT NULL, " +
            "`intervalDays` REAL NOT NULL, `stabilityAfter` REAL, `difficultyAfter` REAL, `easeFactor` REAL NOT NULL, " +
            "`state` TEXT NOT NULL, `reviewDurationMs` INTEGER, " +
            "FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `reviews_new` ($reviewColumns) SELECT $reviewColumns FROM `reviews`",
        "DROP TABLE `reviews`",
        "ALTER TABLE `reviews_new` RENAME TO `reviews`",
        "CREATE INDEX IF NOT EXISTS `index_reviews_cardId` ON `reviews` (`cardId`)",

        // --- the old table steps aside, and the view takes its name ---
        "DROP TABLE `_pairs`",
        "DROP INDEX `_flashcards_guid`",
        "DROP INDEX IF EXISTS `index_flashcards_targetLanguage`",
        "ALTER TABLE `flashcards` RENAME TO `flashcards_v42`",
        "CREATE VIEW `flashcards` AS $FLASHCARDS_VIEW_SQL_V43",
    )
}
