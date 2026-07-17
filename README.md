# Copiosa

**An AI language tutor that lives on your phone, works your way, and never sees your data.**

Copiosa is a native Android app (Kotlin + Jetpack Compose) for adult second-language learners who want more than a streak counter. It pairs an LLM conversation partner with the best ideas from second-language-acquisition research — FSRS spaced repetition, comprehensible input, processing instruction, narrow reading — and runs everything it can on-device: translation, dictionary lookup, neural text-to-speech, and all of your data.

*Copiosa* — Spanish for "abundant." Bring one API key; everything else is included.

## What it does

**Conversations that meet you at your level.** Chat with an AI tutor in your target language, graded to your CEFR level, themed by country, and styled by persona. Every reply quietly recycles vocabulary you're studying and suggests where the conversation could go next. Batch-generate a shelf of conversations on topics you choose, ready to read like graded readers.

**Read anything.** Paste a URL or any text and get a clean, chunked, resumable translation. Tap any word — or drag across a phrase — for an instant definition and translation, powered by a fully offline Wiktionary-derived dictionary and on-device ML Kit translation. One more tap saves it as a flashcard.

**Flashcards that respect your history.** A full FSRS scheduler (the algorithm modern Anki uses) with bidirectional review, cloze cards, images, and audio. Round-trip `.apkg` import/export means your Anki decks — including review history — move in and out losslessly. Cards you merely *encounter* while reading get gentle passive-review credit.

**Listen while you live.** Turn any conversation, text, or selection into a playlist item, rendered by a neural TTS voice (Supertonic via sherpa-onnx) entirely on-device. Compact AAC files, background playback, per-item voices, adjustable speed. The ~100 MB voice model downloads only when you ask.

**Rapid-fire listening drills.** Pre-rendered audio quizzes for the things that only come with reps — numbers and clock times — with adaptive difficulty, distractor generation, and playback speed that rises as you improve.

**Bedtime routines.** A nightly wind-down that plans a de-escalating arc of activities — flashcard review, referential drills, contrast clozes, swipe-sorting, cognate hunts, narrow reading, a serial story that continues night after night, and a closing bedtime story — synthesized in a **single LLM call** and adapted to your per-skill mastery. Twelve activity types today; the registry architecture makes the thirteenth a two-file change.

**A look you'll want to open.** Country-themed palettes, persona skins with their own typography and textures, dark mode, adjustable reading fonts — and per-language profiles that keep everything (settings, decks, playlists, progress) separate.

## Screenshots

| Conversations                             | Tap-to-define                                    | Vocabulary                                |
| ----------------------------------------- | ------------------------------------------------ | ----------------------------------------- |
| ![Conversations](screenshots/library.png) | ![Tap-to-define](screenshots/tap_to_define.png)  | ![Vocabulary](screenshots/vocabulary.png) |

| Reading                             | Audio playlist                                | Routines                            |
| ----------------------------------- | --------------------------------------------- | ----------------------------------- |
| ![Reading](screenshots/reading.png) | ![Audio playlist](screenshots/audio_playlist.png) | ![Routines](screenshots/routines.png) |

## Private by architecture

- **Bring your own key.** Copiosa talks to Gemini or OpenRouter (any model they route, including Claude and GPT) with *your* key. There is no backend, no account, no telemetry, no middleman.
- **On-device everything else.** Dictionary lookups, word-form resolution, translation of taps and selections, TTS synthesis, FSRS scheduling, and the adaptation engine all run locally. Airplane mode breaks nothing but new generation.
- **Your data is a file.** One Room database plus media folders in app storage. Export your vocabulary as a standard Anki `.apkg` anytime.

## The science

Copiosa is unusually explicit about *why* each feature exists — spacing, retrieval practice, desirable difficulties, dual coding, input processing, affective filters. The full tour of what's implemented (and what isn't yet) lives in **[docs/LEARNING_SCIENCE.md](docs/LEARNING_SCIENCE.md)**. The technical map of how it's built is in **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## Building

Requirements: Android Studio (or its JBR) with JDK 17, Android SDK 34+.

```powershell
# Windows (PowerShell); use ./gradlew on macOS/Linux
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

- The sherpa-onnx TTS AAR (~55 MB) is **downloaded automatically** by the `downloadSherpaOnnxAar` Gradle task on first build — no manual step.
- The offline dictionary pack (`dict_es.sqlite.gz`) is built by [tools/build_dictionary.py](tools/build_dictionary.py) from a [kaikki.org](https://kaikki.org) Wiktextract dump and downloaded in-app on demand — it is not in the repo.
- Language support: conversations, texts, flashcards, and audio work for any language your LLM speaks; the grammar-drill routines are currently Spanish-first, and rapid-fire quizzes cover en/es/fr/de/it/pt.

Set your API key in **Settings → Models** on first launch.

## Status

Personal project, actively developed, pre-1.0. It is genuinely daily-driven by its author — expect opinionated defaults and a Spanish-leaning feature set. Issues and ideas welcome.

Font licenses for the bundled typefaces are in [licenses/](licenses/).
