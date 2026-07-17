# Copiosa architecture

A technical map of the codebase: how the layers fit together, how each subsystem works, where the
data lives, and how to extend the parts designed for extension. Companion to
[docs/LEARNING_SCIENCE.md](docs/LEARNING_SCIENCE.md), which explains *why* the features exist.

## Shape of the project

Single Gradle module (`:app`), single activity, Compose everywhere. ~25k lines of Kotlin in four
layers that only point downward:

```
ui/        Compose screens + ViewModels (15), navigation, theming/skins
domain/    Pure Kotlin logic: prompts, FSRS, routine planning, quiz generation, parsing
data/      Room (DB v22), repositories, LLM providers, TTS, Anki interop, generation queues
di/        Hilt modules (DatabaseModule, NetworkModule, QuizModule)
```

Key dependencies: Jetpack Compose + Material3, Hilt (DI), Room, Retrofit + kotlinx.serialization,
Media3 (playback), ML Kit Translate (on-device), an FSRS engine, Readability4j (article
extraction), commons-compress (`.apkg` zip handling), and a vendored sherpa-onnx AAR
(auto-downloaded by the `downloadSherpaOnnxAar` Gradle task — never committed).

### Top-level navigation

`MainActivity` hosts `CopiosaNavGraph` with five tabs — **Library** (conversations + imported
texts), **Vocab** (flashcards/review), **Audio** (TTS playlist), **Routines**, **Settings** — plus
full-screen routes (conversation, reader, card detail, routine player, rapid-fire quiz) and shared
modal sheets (batch generation, text/URL import).

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

### Flashcards, FSRS, and Anki interop

- **Scheduling**: `domain/srs/SpacedRepetitionScheduler` wraps the FSRS engine (target retention
  0.9). Cards persist stability/difficulty/state; every grade appends to the `reviews` log.
- **Review UX**: `ReviewScreen` does Again/Hard/Good/Easy with re-queue-on-Again, bidirectional
  and cloze rendering (`domain/model/Cloze` parses Anki-style `{{c1::…}}`), images, and audio.
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
  model being `Ready`, status persisted on the Room row so relaunches resume. It backs both
  `AudioGenerationManager` (playlist) and `QuizAudioGenerationManager` (quiz pools).
- **Model store**: `SupertonicModelStore` downloads the ~100 MB voice model only on explicit user
  request; everything degrades gracefully (items stay PENDING) until it is installed.
- **Playback**: Media3 via `PlaybackController` + `AudioPlaybackService` (background playback,
  playlist ordering with played-items-sink semantics, drag reorder). An optional retention setting
  auto-deletes played items after N days.

### Rapid-fire listening quizzes

`domain/quiz/` generates cues (numbers, clock times) with language-aware spelling
(`WordNumberSpeller` on ICU; a JVM twin keeps parity tests honest), difficulty-aware distractors
(`QuizSessionBuilder`), and per-type pools persisted in `quiz_pools`/`quiz_cues` with pre-rendered
audio. Adaptive playback speed is derived from per-bucket mastery.

### The routines engine

The most structured subsystem. A nightly routine flows through four stages, honoring a
≤5-LLM-calls budget (target: **1**):

1. **Plan** (`domain/routine/RoutinePlanner`, no LLM): splits the time budget into arousal bands
   (Engage → Settle → Drift), weighted-samples activities per band using the adaptation engine's
   per-bucket selection weights, and guarantees a monotonically de-escalating arousal curve with
   the bedtime story pinned last.
2. **Synthesize** (`data/routine/RoutineSynthesisManager`): one consolidated LLM request generates
   content for every slot that needs text; `RoutineContentParser` decodes it forgivingly and
   `RoutineContentValidator` checks grammar items against the dictionary forms table. Offline
   activities (flashcards, swipe-sort from the learner's own gendered nouns, rapid-fire from the
   quiz pool) build locally. The result is a `RoutinePlan` serialized into `routine_runs.planJson`
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

To add activity #13:

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

### Settings and language profiles

`data/settings/SettingsRepository` stores per-profile settings in DataStore keyed by target
language tag (`p.<tag>.<field>`); switching profiles switches everything (provider/model, prompts,
theme, voice, playlists filter by language). API keys are global and encrypted.

## Data model

Room database `copiosa.db`, schema **v22**, migrations in `di/DatabaseModule.kt` (append-only; the
only destructive one rebuilt the quiz tables at v21→22). Highlights:

| Table | Purpose | Notes |
|---|---|---|
| `conversations` | chats + imported texts | `mode` distinguishes; original text stored for texts |
| `messages` | chat history | `metadata` = structured-reply JSON per message |
| `flashcards` | vocabulary | FSRS state + lossless Anki pass-through fields |
| `reviews` | review log | append-only; fuel for future FSRS parameter fitting |
| `audio_items` | TTS playlist | `filePath` absolute; status drives the render queue |
| `quiz_pools` / `quiz_cues` | rapid-fire pools | unique per (lang, cueType); pre-rendered audio |
| `routine_runs` | routine sessions | `planJson` = serialized `RoutinePlan` |
| `activity_attempts` | per-answer log | feeds adaptation |
| `activity_proficiency` | mastery per bucket | (bucketKey, language) unique |
| `topics` / `story_threads` | routine seeds, serial-story recap | deduped / one per language |

On-device storage outside the DB: `files/audio/` (playlist `.m4a`), `files/quiz_audio/` (cue
renders, content-hash-named), `files/anki_media/`, `files/tts_supertonic/` (opt-in model),
`files/dict_<lang>.sqlite` (opt-in dictionary pack).

## Testing

Unit tests (`app/src/test/`, ~25 files) concentrate on the domain layer: FSRS scheduling, routine
planning/validation/parsing, prompt parsing, cue generation with ICU parity, Anki round-trip
against real SQLite (sqlite-jdbc), WAV parsing, and the three registry-completeness tests. Run
with `.\gradlew.bat :app:testDebugUnitTest`. There are no instrumented UI tests; device smoke
testing is manual.

## Conventions and invariants

- **Frozen identifiers**: `ActivityType.name` values (attempt logs), `ActivityContent`
  `@SerialName`s + the `"kind"` discriminator (stored plans), proficiency bucket slugs, and Room
  entity/column names (append-only migrations).
- **LLM budget**: routine synthesis is one consolidated call; anything new should batch into it
  rather than adding calls.
- **Graceful degradation**: no dictionary → structural validation only; no TTS model → items wait
  as PENDING; unusable LLM content → the slot is dropped and the routine rebalances; unknown
  content kinds in a stored plan are skipped, not crashed on.
- **Media paths**: prefer filenames resolved against an app directory (Anki media style) over
  absolute paths; the audio tables predate this and `AudioCompactor.resolve` papers over it.
- App-lifetime work (generation, synthesis, render queues) lives in `@Singleton` managers with
  their own `SupervisorJob` scopes, not in ViewModels, so navigation never cancels it.
