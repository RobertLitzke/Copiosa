# Copiosa architecture

A technical map of the codebase: how the layers fit together, how each subsystem works, where the
data lives, and how to extend the parts designed for extension. Companion to
[docs/LEARNING_SCIENCE.md](docs/LEARNING_SCIENCE.md), which explains *why* the features exist.

## Shape of the project

Single Gradle module (`:app`), single activity, Compose everywhere. ~38k lines of Kotlin across 258
files in four layers that only point downward:

```
ui/        Compose screens + ViewModels (21), navigation, theming/skins
domain/    Pure Kotlin logic: prompts, FSRS, routine planning, quiz generation, conjugation, parsing
data/      Room (DB v33), repositories, LLM providers, TTS, Anki interop, EPUB import, generation queues, maintenance, backup
di/        Hilt modules (DatabaseModule, NetworkModule, QuizModule, MaintenanceModule, CoroutineModule)
```

Key dependencies: Jetpack Compose + Material3, Hilt (DI), Room, Retrofit + kotlinx.serialization,
Media3 (playback), ML Kit Translate (on-device), an FSRS engine, Readability4j (article
extraction), commons-compress (`.apkg` zip handling), and a vendored sherpa-onnx AAR
(auto-downloaded by the `downloadSherpaOnnxAar` Gradle task — never committed).

### Top-level navigation

`MainActivity` hosts `CopiosaNavGraph`, which lands on a **Home** dashboard (due-review count,
daily-goal tracker, jump-offs) alongside four tabs — **Library** (one merged list of conversations,
imported texts and books, with the audio playlist as a filter pane), **Vocab** (flashcards/review),
**Routines**, **Settings** — plus full-screen routes (conversation, text reader, book reader, card
detail, routine player, rapid-fire quiz, conjugation drill and reference, stats)
and shared modal sheets (batch generation, text/URL import). A share (`ACTION_SEND` text) or a
launcher shortcut is parsed once in `MainActivity` into a `ui/navigation/LaunchAction`, which the
nav graph performs after start-up — a shared URL/text imports as a reading text; shortcuts jump to
review, a new chat, or routines.

## Subsystems

### Conversations and the LLM pipeline

- **Providers**: `data/remote/` defines an `LlmProvider` interface with Gemini and OpenRouter
  implementations behind `ProviderRegistry`; the active provider/model comes from settings, and API
  keys live in `EncryptedSharedPreferences` (never in the Room DB). `RetryInterceptor` adds
  exponential backoff.
- **Prompts**: `domain/prompt/PromptBuilder` renders the conversation system prompt from an
  editable template (placeholders for level, country, native/target language) and injects a
  rotating sample of due vocabulary so replies recycle what the learner is studying.
- **Structured replies**: every assistant reply may end with a `<<<COPIOSA_META>>>` JSON block
  (next-topic suggestions, distinctive words, vocabulary actually used).
  `domain/prompt/StructuredReply` parses it forgivingly (regex-anchored sentinel, fence stripping)
  and strips it from the visible text; the metadata is stored per-message and feeds topic seeding
  for routines.
- **Flow**: `ConversationRepository` owns message history, generation (`sendMessage`,
  `generateFirstReply`, batch generation via `GenerationManager`), and text imports. LLM context is
  capped at the most recent 60 messages.

### Texts, translation, and the offline dictionary

- **Import**: paste text or a URL (`ArticleExtractor` + Readability4j). Translation runs chunked
  and resumable — a mid-article failure keeps finished chunks and continues later.
- **Tap-to-define**: the shared `SelectableReader` + `ReadingSelectionController` power reading
  surfaces everywhere (texts, chat replies, routine reading steps). A tap or drag resolves through
  `data/translate/LocalDictionary` — a downloaded SQLite pack (`lemmas`/`senses`/`forms`) built by
  `tools/build_dictionary.py` from Wiktextract, so "vivimos" resolves to "vivir" offline — with
  ML Kit `OnDeviceTranslator` as the translation fallback. Saving a selection creates a flashcard.
- **Highlighting**: `domain/highlight/HighlightTargetProvider` maps saved lemmas + inflections so
  known words are tinted in any reader, and encountering them grants passive review credit.

### Books (EPUB import)

- **Import**: `data/epub/` — `EpubParser` (jsoup; container.xml → OPF spine → per-chapter XHTML,
  front/backmatter skipped by `epub:type` with a filename fallback and a keep-everything safety
  net) flattens chapters to markdown blocks preserving `*italic*`/`**bold**`/`## headings`.
  `BookSectioner` assigns ~4,000-char translation sections that never span a chapter.
  `EpubImportService` persists a `books` row plus one `book_paragraphs` row per paragraph and
  stores a downscaled cover in `files/book_covers/`.
- **Direction** (`BookEntity.direction`, asked by `ui/books/BookImportDialog` before parsing):
  FORWARD is a book in another language, translated *into* the target language and graded to the
  chosen CEFR level. REVERSE is a book already in the target language — it is readable in full the
  moment it is imported, so its translation pass is an ungraded native-language gloss that is
  opt-in (`autoTranslate`, the same flag the background task uses) and off by default. A reverse
  book can therefore cost no LLM calls at all while still giving tap-to-translate, saved-word
  highlighting and passive review. `domain/book/BookToc` maps the direction onto `targetText` /
  `nativeText`, and the reader is written purely in those terms — a Target/Native/Both control,
  with the target side always carrying the selection machinery.
- **Translation**: one section per call — level-adapted for FORWARD, a faithful ungraded gloss for
  REVERSE (which also skips the title call, its title already being in the target language).
  Paragraphs are sent with `[[P<ord>]]`
  markers the model must echo (`PromptBuilder.bookSectionSystemPrompt`), parsed by
  `BookSectionAligner` (perfect echo → zip-by-order → order-proportional best effort, with one
  corrective retry), so original and translated paragraphs stay aligned **on the same row**. The
  previous section rides along as a user/assistant exchange for continuity of names and recurring
  vocabulary. Content is stored before `translatedSections` advances, so a crash never over-claims.
- **Single writer**: `BookTranslationManager` (`@AppScope`) runs one loop per book that only ever
  translates the frontier section; read-ahead, "translate to end", retry, and the maintenance task
  all just raise a per-book target, so a section can never be translated twice.
- **Reading**: `ui/books/` pages `book_paragraphs` through Paging 3 (placeholders on, so list index
  == paragraph ord) — bounded RAM on a long novel, exact scroll restore. A Target/Native/Both
  control swaps text on the same items (Both renders the pair — target text, native gloss beneath —
  inside the one item, so the index↔ord identity holds); on a switch, near-viewport rows are tinted
  from the theme's reading palette (`CountryPalette.tints` — two core hues × three variations,
  interleaved) cycled by ord, so a paragraph keeps its colour across the switch. The tint eases in, holds to 8s, then fades over
  ~3s — except in Both, where it pairs the two languages and stays lit (and so covers every row,
  not just the captured window) until the reader leaves the mode. It is painted in `drawBehind` —
  reading the animation in the draw phase, not composition, so the fade never recomposes paragraph
  text. Auto read-ahead (global
  `bookAutoReadAhead` setting) keeps two sections ahead of the reading position; the per-book
  `autoTranslate` flag lets `BookTranslateTask` finish the book in the background.
- **Table of contents**: derived, not stored. `BookDao.chapterSpans` groups paragraph rows by
  `chapterIndex` for each chapter's first ord and section range; `headingRows` supplies titles (the
  chapter's first `HEADING` block, in whichever language the direction calls for).
  `domain/book/buildToc` compares each span against the frontier — FULL / PARTIAL / NONE. The sheet
  shows the whole book: locked chapters stay visible, because "how far have I got" is half of what
  a reader opens it for, and only readable chapters jump. The jump is
  `scrollToItem(ord + HEADER_ITEMS)` on the same index↔ord identity, claiming the scroll-restore
  gate first so a jump made before restore isn't yanked back.
- **Re-import is idempotent**: `EpubImportService` matches an existing book on
  (original title, author, language, paragraph count, direction) and returns it, so opening the same
  file twice keeps its translated sections and reading position instead of paying for them again.

### Flashcards, FSRS, and Anki interop

- **Scheduling**: `domain/srs/SpacedRepetitionScheduler` wraps the FSRS engine (target retention
  0.9). Cards persist stability/difficulty/state; every grade appends to the `reviews` log.
- **Review UX**: `ReviewScreen` does Again/Hard/Good/Easy with re-queue-on-Again, bidirectional
  and cloze rendering (`domain/model/Cloze` parses Anki-style `{{c1::…}}`), images, audio, and an
  undo stack (restores the pre-review card row and deletes its review-log entry).
- **Anki round-trip**: `data/anki/` imports and exports `.apkg` losslessly — original note fields
  ride along in `ankiFieldsJson`, review history is converted in both directions, and media files
  are stored by filename under `files/anki_media/` (filenames, not absolute paths, so backups
  restore cleanly).
- **Images**: `ImageSearchRepository` (Openverse/Wikimedia) with a bulk "add images" stepping flow.

### Audio: TTS synthesis and playback

- **Engine**: `data/tts/TtsEngine` wraps sherpa-onnx's Supertonic model (multi-voice,
  multilingual). It synthesizes sentence-by-sentence with explicit inter-sentence gaps (the model
  under-pauses on periods in some languages), adds an emphasized spoken title and a closing chime
  for playlist articles.
- **Encoding**: output is AAC-LC mono 80 kbps in `.m4a` via `M4aWriter`
  (MediaCodec + MediaMuxer) — ~12× smaller than the WAVs the app originally wrote. `WavPcm` +
  `AudioCompactor` perform a one-time startup migration of any legacy WAVs (re-resolving stale
  absolute paths by filename), then become a no-op.
- **Queues**: `TtsRenderQueue` is the shared drain machinery — single consumer, gated on the voice
  model being `Ready`, status persisted on the Room row so relaunches resume. Owners hand it a
  small `RenderQueueStore` (unrendered ids + status writes) and a render callback; the queue itself
  owns the GENERATING/PENDING/FAILED envelope and orphan repair. It backs both
  `AudioGenerationManager` (playlist) and `QuizAudioGenerationManager` (quiz pools).
- **Model store**: `SupertonicModelStore` downloads the ~100 MB voice model only on explicit user
  request; everything degrades gracefully (items stay PENDING) until it is installed.
- **Playback**: Media3 via `PlaybackController` + `AudioPlaybackService` (background playback,
  playlist ordering with played-items-sink semantics, drag reorder). An optional retention setting
  auto-deletes played items after N days.

### Rapid-fire listening quizzes

`domain/quiz/` generates cues with language-aware spelling (`WordNumberSpeller`, en/es/fr/de/it/pt,
on ICU; a JVM twin keeps parity tests honest), difficulty-aware distractors (`QuizSessionBuilder`),
and per-type pools persisted in `quiz_pools`/`quiz_cues` with pre-rendered audio. Adaptive playback
speed is derived from per-bucket mastery.

`CueType.kind` splits the two seeding paths: `ALGORITHMIC` cues (numbers, clock times) sample values
and re-derive confusable distractors at session time from the stored `valueKey`, so a pool costs no
LLM calls. `PHRASE` cues (`RESTAURANT`, see `PhraseScenario`) are whole generated sentences whose
answer options are seeded once and stored on the cue row. Slugs are prefixed `rf_` so they can't
collide with the contrast-cloze category slugs sharing the proficiency table.

### Conjugation drill

`domain/conjugation/` holds a bundled 200-verb Spanish lexicon (seven paradigms) generated by
[tools/build_conjugations.py](tools/build_conjugations.py). **Only deviations are stored** —
`RegularConjugator` reproduces regular cells at runtime, and `ConjugationGoldenTest` replays all
8,400 cells from `app/src/test/resources/conjugation_golden.csv` so the Python generator and the
Kotlin engine cannot drift apart. It backs both the `CONJUGATION_DRILL` routine activity and a
standalone drill (`ui/conjugation/`) with a reference table.

### The routines engine

The most structured subsystem. A nightly routine flows through four stages, honoring a
≤5-LLM-calls budget (target: **1**):

1. **Plan** (`domain/routine/RoutinePlanner`, no LLM): splits the time budget into arousal bands
   (Engage → Settle → Drift), weighted-samples activities per band using the adaptation engine's
   per-bucket selection weights, and guarantees a monotonically de-escalating arousal curve with
   the bedtime story pinned last. A routine draws freely up to its `RoutineType.maxArousal` and
   admits a small quota above it (bedtime: one, Engage-only) so a genuinely activating drill can
   open the wind-down without derailing it; short routines skip Engage entirely and so stay
   within the cap.
2. **Synthesize** (`data/routine/RoutineSynthesisManager`): one consolidated LLM request generates
   content for every slot that needs text; `RoutineContentParser` decodes it forgivingly and
   `RoutineContentValidator` checks grammar items against the dictionary forms table. Offline
   activities (flashcards, swipe-sort from the learner's own gendered nouns, rapid-fire from the
   quiz pool, conjugation from the bundled verb lexicon) build locally. The result is a
   `RoutinePlan` serialized into `routine_runs.planJson`
   (kotlinx polymorphic JSON, `"kind"` discriminator — treat `@SerialName`s as frozen).
3. **Play** (`ui/routines/RoutinePlayerScreen`): steps through activities, recording attempts
   (correct/latency/listening-speed) via `RoutinePlayerViewModel`.
4. **Adapt** (`data/routine/RoutineAdaptation` + `domain/routine/AdaptationMath`): each scored
   attempt updates a per-bucket mastery EMA that drives difficulty, selection weight, and listening
   speed for the *next* routine. Buckets are per-type, except categorized types (contrast-cloze
   categories, rapid-fire cue types) which bucket per category slug.

#### The activity registry (how to add an activity)

Everything an activity type needs is bundled in one **`ActivityDescriptor`**
(`domain/routine/ActivityDescriptor.kt`): planner metadata (`ActivityDefinition`), its system-rule
and request-line prompt fragments, its content builder (DTO → `ActivityContent`), and its
status-screen buckets. `ActivityRegistry` is the single list of descriptors; the prompt builder,
synthesizer, and status screen iterate it instead of switching over `ActivityType`. The UI half is
`ui/routines/activities/ActivityRenderers.kt`, mapping each content class to its renderer
composable (full-screen or progress-framed).

To add the next activity:

1. Add an `ActivityType` enum constant (its `.name` is persisted in attempt logs — never rename)
   and an `@Serializable @SerialName("...") data class ... : ActivityContent` in `RoutinePlan.kt`.
2. Write `domain/routine/activities/YourActivity.kt` — the descriptor (metadata + prompt fragments
   + `buildContent`; add `statusCategories` if it buckets per category; use `RoutineBuildServices`
   if it needs data-layer input).
3. Write its renderer composable in `ui/routines/activities/` .
4. Register it: one line in `ActivityRegistry.all`, one entry in `ActivityRenderers.byContent`.

`ActivityRegistryTest`, `ActivityRenderersTest`, and `RoutinePlanCodecTest` enforce completeness
(every type has a descriptor and renderer; every content kind round-trips with a stable
discriminator) — they are the replacement for the exhaustive `when`s this design removed.

### Theming and personas

`ui/theme/skin/` maps a persona + country to a full visual identity: `CountryThemes`/
`CountryPalette` (palettes), `PersonaSpecs` (avatars/metadata), `SkinTypography`, `Textures`,
`Motifs`, `MessageMotion`. `AppSkin` composes them; conversation chrome (`ui/conversation/skin/`)
consumes them. Fonts are bundled with OFL licenses in `licenses/`.

### Home and focus stats

`ui/home/` is the landing dashboard: cached due count, the daily study-goal tracker, and jump-offs
into review/rapid-fire/audio/stats. Behind it, `data/stats/FocusTimeTracker` accrues per-activity
focus time (screens opt in via `TrackFocusTime`), `FocusStatsRollupTask` rolls sessions into daily
rows, and `ui/stats/StatsScreen` charts totals, accuracy, and streaks (`domain/stats/Streak`).

### Settings and language profiles

`data/settings/SettingsRepository` stores per-profile settings in DataStore keyed by target
language tag (`p.<tag>.<field>`); switching profiles switches everything (provider/model, prompts,
theme, voice, playlists filter by language). API keys are global and encrypted.

## Data model

Room database `copiosa.db`, schema **v33**, migrations in `di/DatabaseModule.kt` (append-only; the
only destructive one rebuilt the quiz tables at v21→22). Highlights:

| Table | Purpose | Notes |
|---|---|---|
| `conversations` | chats + imported texts | `mode` distinguishes; original text stored for texts |
| `messages` | chat history | `metadata` = structured-reply JSON per message |
| `flashcards` | vocabulary | FSRS state + lossless Anki pass-through fields |
| `reviews` | review log | append-only; fuel for future FSRS parameter fitting |
| `audio_items` | TTS playlist | `filePath` absolute; status drives the render queue; `playedAt` drives retention |
| `quiz_pools` / `quiz_cues` | rapid-fire pools | unique per (lang, cueType); pre-rendered audio |
| `routine_runs` | routine sessions | `planJson` = serialized `RoutinePlan` |
| `activity_attempts` | per-answer log | feeds adaptation |
| `activity_proficiency` | mastery per bucket | (bucketKey, language) unique |
| `topics` / `story_threads` | routine seeds, serial-story recap | deduped / one per language |
| `suggested_topics` | topics proposed *to* the learner | written by background maintenance; replaced wholesale per refresh |
| `books` / `book_paragraphs` | imported EPUBs | one row per paragraph holding both languages; `translatedSections` is the frontier |

On-device storage outside the DB: `files/audio/` (playlist `.m4a`), `files/quiz_audio/` (cue
renders, content-hash-named), `files/anki_media/`, `files/book_covers/`,
`files/tts_supertonic/` (opt-in model), `files/dict_<lang>.sqlite` (opt-in dictionary pack).

**Backup/restore** (`data/backup/BackupService`): one zip with the checkpointed DB, the media
folders, and the DataStore preference files — excluding the re-downloadable TTS model and
dictionary packs and the device-bound API keys. Restore stages to a temp dir first, swaps the live
files, then the app kills its own process (Room/DataStore hold pre-restore state).

## Testing

Unit tests (`app/src/test/`, ~60 files) concentrate on the domain layer: FSRS scheduling, routine
planning/validation/parsing, prompt parsing, cue generation with ICU parity, the conjugation golden
replay, EPUB parsing and section alignment, Anki round-trip against real SQLite (sqlite-jdbc), WAV
parsing, focus-time/streak stats, the three registry-completeness tests, and the maintenance
runner's gating/failure-isolation (fakes, no Android). The v23→24 and v24→25 migrations are replayed
against real SQLite too — their SQL is
exposed as statement lists for exactly that. Run with `.\gradlew.bat :app:testDebugUnitTest`. There
are no instrumented UI tests; device smoke testing is manual.

## Conventions and invariants

- **Frozen identifiers**: `ActivityType.name` values (attempt logs), `ActivityContent`
  `@SerialName`s + the `"kind"` discriminator (stored plans), proficiency bucket slugs, and Room
  entity/column names (append-only migrations).
- **LLM budget**: routine synthesis is one consolidated call; anything new should batch into it
  rather than adding calls. The one exception is background maintenance, which may make at most one
  call per run and only behind the per-profile background-tasks toggle.
- **Graceful degradation**: no dictionary → structural validation only; no TTS model → items wait
  as PENDING; unusable LLM content → the slot is dropped and the routine rebalances; unknown
  content kinds in a stored plan are skipped, not crashed on.
- **Media paths**: prefer filenames resolved against an app directory (Anki media style) over
  absolute paths; the audio tables predate this and `AudioCompactor.resolve` papers over it.
- App-lifetime work (generation, synthesis, render queues) lives in `@Singleton` managers on the
  shared `@AppScope` coroutine scope (`di/CoroutineModule` — one `SupervisorJob` scope injected
  everywhere instead of hand-rolled copies), not in ViewModels, so navigation never cancels it.
  Work that must survive the process being gone is the exception: `data/maintenance/` schedules a
  WorkManager worker (see below).
- **Background maintenance**: `data/maintenance/` runs periodic upkeep — audio retention, the vocab
  due-count snapshot, the opt-in due-review reminder notification, retrying chats whose reply never
  arrived, refreshing topic suggestions, and back-filling vocab enrichment that the save-time pass
  missed (see below).
  `MaintenanceScheduler` enqueues a 12-hour periodic worker plus a one-shot kick from
  `CopiosaApp.onCreate()`; `MaintenanceWorker` is a thin delegate so all logic stays JVM-testable in
  `MaintenanceRunner`. Adding a task means implementing `MaintenanceTask` and listing it in
  `MaintenanceTasks` — the runner applies scope, LLM-budget and cadence gates and isolates failures.
  Cadence bookkeeping lives in its own `maintenance` DataStore, keyed by `(taskId, profileTag)`.
- **Vocab enrichment**: saving a card is entirely local (ML Kit translation + dictionary POS), so it
  can't supply gender, explanation or CEFR. `FlashcardRepository.add` therefore hands the new id to a
  `domain/vocab/EnrichmentQueue` — a debounce-and-batch queue on `@AppScope`, so a burst of saves
  costs one model call — behind the same background-tasks + API-key gate maintenance uses. Failures
  are swallowed (the save already succeeded); `EnrichVocabTask` retries what was missed. Enrichment
  only ever fills blanks: an imported deck's gender wins over the model's.
