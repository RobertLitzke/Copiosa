# Copiosa architecture

A technical map of the codebase: how the layers fit together, how each subsystem works, where the
data lives, and how to extend the parts designed for extension. Companion to
[docs/LEARNING_SCIENCE.md](docs/LEARNING_SCIENCE.md), which explains *why* the features exist.

## Shape of the project

Single Gradle module (`:app`), single activity, Compose everywhere. ~38k lines of Kotlin across 258
files in four layers that only point downward:

```
ui/        Compose screens + ViewModels (23), navigation, theming/skins
domain/    Pure Kotlin logic: prompts, FSRS, routine planning, quiz generation, conjugation, courses, parsing
data/      Room (DB v47), repositories, LLM providers, TTS, Anki interop, EPUB import, generation queues, maintenance, backup
di/        Hilt modules (DatabaseModule, NetworkModule, QuizModule, MaintenanceModule, CoroutineModule)
```

Key dependencies: Jetpack Compose + Material3, Hilt (DI), Room, Retrofit + kotlinx.serialization,
Media3 (playback), ML Kit Translate (on-device), an FSRS engine, Readability4j (article
extraction), commons-compress (`.apkg` zip handling), and a vendored sherpa-onnx AAR
(auto-downloaded by the `downloadSherpaOnnxAar` Gradle task — never committed).

### Names

One name per concept, so that code, routes, strings and this document agree:

| Concept | Name | Notes |
|---|---|---|
| The learner's saved words, and everything about them on screen | **Vocab** | the tab, `Routes.VOCAB`, `ui/vocab/`, `VocabScreen`, `VocabDetailScreen`; Spanish UI says *Léxico* |
| One word in the data model | **note** (content) and **card** (one direction of it) | see the schema section; "flashcard" survives only in identifiers tied to the database: the `flashcards` view, `FlashcardEntity`, `FlashcardDao`, `FlashcardRepository` |
| An article the learner imported, pasted or opened from a link | **text** | `Routes.TEXT_READING`, `ui/texts/`, `LibraryEntry.Text`, `LibraryFilter.TEXTS`, the Library filter "Texts". Its translation is one *step* in making a text, not another name for it |
| A book | **book** | `ui/books/`, `LibraryEntry.Book` |

Two persisted identifiers keep their old spelling because renaming them would need a data migration:
`Mode.URL_TRANSLATION` (the mode of a conversation row that is a text) and the `flashcards` view name.
"Lexicon" now means only the bundled verb dictionary (`VerbLexicon`), never the Vocab tab.

### Top-level navigation

`MainActivity` hosts `CopiosaNavGraph`, which lands on a **Home** dashboard (due-review count,
daily-goal tracker, contextual suggestions beside fixed session formats) alongside four tabs — **Library** (one merged list of conversations,
imported texts and books, with the audio playlist as a filter pane), **Vocab** (flashcards/review),
**Routines**, **Settings** — plus full-screen routes (conversation, text reader, book reader, card
detail, routine player, rapid-fire quiz, conjugation drill, cloze bank, listen-and-rebuild, the course hub and lesson player, the
reference section, stats) and shared modal sheets (batch generation, text/URL import). A share (`ACTION_SEND` text) or a
launcher shortcut is parsed in `MainActivity` into a `ui/navigation/LaunchAction` — from the launching
intent, and from `onNewIntent` when the app is already running (the activity is `singleTop`) — and
queued for the nav graph, which performs each once — a shared URL/text imports as a reading text;
shortcuts jump to review, a new chat, or routines.

## Subsystems

### Conversations and the LLM pipeline

- **Providers**: `data/remote/` defines an `LlmProvider` interface with Gemini and OpenRouter
  implementations behind `ProviderRegistry`; the active provider/model comes from settings, and API
  keys live in `EncryptedSharedPreferences` (never in the Room DB). `RetryInterceptor` adds
  exponential backoff. OpenRouter carries a *roster* of models (`domain/model/OpenRouterModel`,
  stored per profile as JSON) rather than a single id — all on the one key, each with an optional
  nickname; one is the profile default and any book can be pointed at another.
- **The request contract** (`Llm.kt`): `LlmRequest.maxTokens` is the size of the *reply* a caller
  wants, and is not what goes on the wire. Both providers count thinking against the output
  ceiling, so each adds `THINKING_HEADROOM` where a model may think: `GeminiProvider.outputCapFor`
  sends the ceiling exactly only where thinking is known to be off; `OpenRouterProvider.withHeadroom`
  adds it to every request (a model id doesn't say whether the model reasons) and falls back to the
  bare ceiling if a small model rejects the lifted one.
  `LlmRequest.json` asks for the provider's JSON mode and names the top-level shape, because
  OpenAI-style `json_object` can only promise an object: an `ARRAY` request gets the mode from
  Gemini and plain prompting on OpenRouter. `LlmResponse.truncated` reports a reply that stopped at
  the ceiling — a caller that retries malformed output must check it first, since asking again for
  the same thing is cut at the same place.
- **Background model**: each profile has one optional second model
  (`AppSettings.backgroundChoice`, null = same as main) for work nobody is waiting on — batched
  card enrichment, the cloze bank's three passes, topic suggestions, and title translations (which
  fall back to the *item's* model, not the profile's). It is a provider *and* a model, independent
  of the main provider, so a call site must send to `providers.forId(target.provider)` — never
  `providers.active()` — with `target` from `SettingsRepository.backgroundTarget`, which also falls
  back when the slot's provider has no key. A single tapped "Explain" — on a card's detail screen or
  a reader's lookup card — stays on the main model, as does everything the learner reads or plays.
- **Prompts**: `domain/prompt/PromptBuilder` renders the conversation system prompt from an
  editable template (placeholders for level, country, native/target language). **The system prompt
  is identical on every ordinary turn of a chat**, so the prompt plus the history ahead of the new
  message is a prefix the provider can serve from cache. What changes per turn — the rotating
  sample of due vocabulary, and the previous reply's distinctive words to recur — goes in
  `turnNote`, which `ConversationRepository.withTurnNote` appends to the learner's latest message
  in the request only. The stored message is never altered, which is what keeps the next turn's
  history byte-identical to what was sent.
- **Structured replies**: every assistant reply may end with a `<<<COPIOSA_META>>>` JSON block
  (next-topic suggestions, distinctive words, vocabulary actually used).
  `domain/prompt/StructuredReply` parses it forgivingly (regex-anchored sentinel, fence stripping)
  and strips it from the visible text; the metadata is stored per-message and feeds topic seeding
  for routines.
- **Flow**: `ConversationRepository` owns message history, generation (`sendMessage`,
  `generateFirstReply`, batch generation via `GenerationManager`), and text imports. LLM context is
  capped at the most recent 60 messages.

### The usage meter

Both providers say what each call consumed; `domain/usage/` and `data/usage/LlmUsageRepository`
keep it, and Settings → Models shows the month to date.

- **Capture is in the provider, not at the call sites.** `GeminiProvider` and `OpenRouterProvider`
  record straight off the response, before the reply is looked at — the one place every call passes
  through, including the ones whose text is then thrown away as a refusal or a misaligned section.
  Those were billed like any other. `LlmUsageRepository.record` never throws and runs
  `NonCancellable`: the call is already paid for, and a meter must not be able to fail a reply.
- **Usage blocks are read as raw JSON** (`geminiUsageOf` / `openRouterUsageOf` in
  `ProviderSupport.kt`), not as serializable classes. A typed field meeting an unexpected shape
  would fail the decode of the whole response; read by hand, anything odd is simply not counted.
- **Every request names its feature.** `LlmRequest.feature` (`LlmFeature`) has no default, so a new
  call site does not compile until it says which line of the bill it is. Book calls also carry
  `bookId`, which is what lets a book's download dialog say what that book has cost.
- **Tokens are stored; cost is derived.** `llm_usage` holds running totals per local day for each
  (provider, model, feature, book). OpenRouter reports the charge, and that figure is stored and
  shown as billed (`cost` plus, for a bring-your-own-key call, `upstream_inference_cost`). Gemini
  reports tokens only, so its cost is *estimated when read* from `LlmPricing` — paid-tier list
  prices, which a free-tier key pays none of — and a corrected price therefore reprices history.
  The UI keeps the three cases apart: `$0.42` billed, `≈ $0.42` estimated, a trailing `+` when
  some calls went to a model with no known price, whose tokens are counted and whose cost is
  admitted missing rather than shown as zero.
- **The meter is global, not per profile**, unlike nearly everything else in Settings: the keys are
  shared, so the bill is. It also only knows what happened after it was installed, so while the
  first recorded day falls inside a month on show the card says when counting began, and a book's
  figure is always "since <date>".
- **Adding a Gemini model** to `AppSettings.GEMINI_MODELS` needs a row in `LlmPricing`;
  `UsageSummaryTest` fails until it has one.

### Texts, translation, and the offline dictionary

- **Import**: paste text or a URL (`ArticleExtractor` + Readability4j). Translation runs chunked
  and resumable — a mid-article failure keeps finished chunks and continues later.
- **Reading**: the text reader is built on the same bilingual row model as the book reader
  (`domain/reading/ReadMode` — target / native / both — with `ui/components/BilingualReading.kt`
  supplying the row renderer and the correspondence tint, plus the shared swipe and progress rule).
  The chosen mode is persisted per item (`conversations.readMode`, `books.readMode`) alongside the
  reading position, so an item reopens in the version it was left in. Articles have no per-paragraph
  marker contract the way book sections do, so `domain/reading/BilingualBlocks.align` reconstructs
  the pairing: rows are anchored on the *source* blocks (complete from import, so the list can't
  shift under the reader), matched in order while the translation is still arriving and
  order-proportionally once it is complete and the counts disagree.
- **Selecting**: a long-press-drag selects word-by-word (`SelectableMessage`). The selection itself
  lives in a `ReadingSelectionSpan` shared by every paragraph of one continuous text, which is what
  lets a drag run past the end of one paragraph into the next: each block registers its text layout
  and window position, so a drag position is lifted into window space, hit-tested against the
  registered blocks, and dropped back into whichever block the finger is over. Sharing a span also
  makes "one highlight at a time" fall out for free. Surfaces whose blocks are separate texts (chat
  messages) give each block its own span — the default. The card's copy action copies the
  *selection*; it is the only way to lift text out of a reader.
- **Tap-to-define**: `ReadingSelectionController` powers reading surfaces everywhere (texts, books,
  chat replies, routine reading steps — the last two via `SelectableReader`). A tap or drag resolves through
  `data/translate/LocalDictionary` — a downloaded SQLite pack (`lemmas`/`senses`/`en_senses`/`forms`)
  built by `tools/build_dictionary.py` from two Wiktextract editions, so "vivimos" resolves to "vivir"
  offline and each headword carries both English meanings and its target-language senses with their
  register/dialect labels and examples. ML Kit `OnDeviceTranslator` is the fallback for what the pack
  can't answer — phrases, unknown words, a pack not downloaded — and steps aside when the dictionary
  has English of its own. One pack per language, fetched on demand (`es` ~120 MB, `pt` ~55 MB; sizes in
  `LocalDictionary.PACK_SIZE_MB` so the button can say what it will cost). A pack older than
  `REQUIRED_SCHEMA` reads as not-downloaded, so a schema bump surfaces as that same download button.
- **Saving from the card**: one rule — the back is the meaning of the headword on the front.
  `ui/components/SaveHeadword` picks the default headword (the selection itself when it is a
  headword with English of its own, else the base form the pack can gloss) and its back (that
  headword's English). When the pack has no English for the base form, the controller translates
  the *base form* (`translateBaseForm`), never the form in the sentence, and withdraws the
  suggestion if that fails too — otherwise *madrigueras* is saved as *madriguera* / "burrows".
  The pack has a row for every inflected form as well as every headword; only rows with English
  count as headwords here. Among base forms a verb wins, except a second-person or participle
  reading, which loses to a noun (*matices* -> *matiz*, not *matizar*). A save whose headword is
  already a card's front shows that card instead of adding a second note. A card found only
  through its cached forms gives way when the dictionary's default is another word it can gloss
  (*tratados* is treaties, not *tratar*); the card stays reachable as a link.
- **Explain**: the lookup card's "Explain" has two answers. In a chat it sends a turn
  (`ConversationViewModel.explain`), so the reply joins the conversation. Everywhere else there is
  no conversation to ask in, so `ReadingSelectionController.explain` calls
  `ConversationRepository.explainSelection` with the selection and the sentence around it and shows
  the reply in the card (`TranslateState.explanation`). Main model, `LlmFeature.EXPLAIN`, nothing
  stored — a saved card's own explanation is about the word, this one about a sentence.
  Saving a selection creates a flashcard, backed by the dictionary's English when it has any.
- **Highlighting**: `domain/highlight/HighlightTargetProvider` maps saved lemmas + inflections so
  known words are tinted in any reader, and encountering them grants passive review credit. Only
  words due within seven days highlight, and of those only the 1,000 due soonest
  (`HighlightTargetProvider.eligible`, counted per note) — with a large overdue backlog the window
  alone covers a third of a page. The tint
  is opt-out: `AppSettings.highlightSavedWords` is the per-language default and each chat, text or
  book carries a nullable `highlightWords` override (null = follow the setting), toggled from its
  overflow menu. Readers gate it by emptying the target list, so the colorizer and the persona
  decoration go quiet together; passive review is unaffected either way.

### Books (EPUB import)

- **Import**: `data/epub/` — `EpubParser` (jsoup; container.xml → OPF spine → per-chapter XHTML,
  front/backmatter skipped by `epub:type` with a filename fallback and a keep-everything safety
  net) flattens chapters to markdown blocks preserving `*italic*`/`**bold**`/`## headings`.
  `BookSectioner` assigns ~4,000-char translation sections that never span a chapter.
  `EpubImportService` persists a `books` row plus one `book_paragraphs` row per paragraph and
  stores a downscaled cover in `files/book_covers/`.
- **Block structure**: everything is *markdown in a string*, one block per stored row — no tree.
  `BookParagraphEntity.kind` is a plain TEXT slug (`PARA`, `HEADING`, `LIST`, `TABLE`, `NOTE`,
  `IMAGE`), so new kinds need no migration and an unknown one renders as a paragraph rather than
  crashing. Lists carry two spaces of indent per nesting level then `- `/`N. `; a table is one
  block of GFM pipe rows (`PipeTable`, the single place that form is parsed or written, by both
  the reader and the exporter); a footnote body is a `[^3]: …` row parked at the end of the chapter
  that references it, numbered from 1 per chapter so `(chapter, label)` identifies one note; an
  image is `![](2.jpg)` naming a file in `files/book_images/<bookId>/`.
  **Structure is read only from `originalText`, via `BlockMarkup`** — `BookSectionAligner` trims
  every translated body, so a list item's indent cannot survive in `translatedText`, and the model
  may renumber an ordinal or drop a `[^3]:` prefix however firmly the contract asks it not to.
  Image rows never reach the LLM: they are stored already "translated" at import and filtered out
  of the request, and a section holding nothing but images skips its call entirely.
- **Fidelity limits**: images are downscaled to ~800px under a per-book byte ceiling — past it the
  row remains (dropping it would leave a hole in the dense `ord` sequence the reader's list index
  depends on) but renders and exports as nothing. A footnote marker with no harvested body is
  removed rather than left as a stray digit. `colspan` is flattened by repetition and `rowspan`
  ignored. Books imported before these kinds existed are unaffected and are not migrated — the
  source EPUB is deleted at import, so there is nothing to re-parse; re-importing correctly creates
  a *new* library entry, since `findMatching` keys on a paragraph count the richer parser changes.
- **Direction** (`BookEntity.direction`, asked by `ui/books/BookImportDialog` before parsing):
  FORWARD is a book in another language, translated *into* the target language and graded to the
  chosen CEFR level. REVERSE is a book already in the target language — it is readable in full the
  moment it is imported, so its translation pass is an ungraded native-language gloss that is
  opt-in (any download mode above `OFF`, which is where a reverse book is imported). A reverse
  book can therefore cost no LLM calls at all while still giving tap-to-translate, saved-word
  highlighting and passive review. `domain/book/BookToc` maps the direction onto `targetText` /
  `nativeText`, and the reader is written purely in those terms — a Target/Native/Both control,
  with the target side always carrying the selection machinery.
- **Translation**: one section per call — level-adapted for FORWARD, a faithful ungraded gloss for
  REVERSE (which also skips the title call, its title already being in the target language).
  Paragraphs are sent with `[[P<ord>]]`
  markers the model must echo (`PromptBuilder.bookSectionSystemPrompt`), parsed by
  `BookSectionAligner` (perfect echo → zip-by-order → order-proportional best effort, with one
  corrective retry), so original and translated paragraphs stay aligned **on the same row**. A
  reply that ran out of output room is a different failure and skips that retry: the paragraphs it
  finished are kept (`completedPrefix`) and one call asks for only the rest. The
  previous section rides along as a user/assistant exchange for continuity of names and recurring
  vocabulary. Content is stored before `translatedSections` advances, so a crash never over-claims.
- **Single writer**: `BookTranslationManager` (`@AppScope`) runs one loop per book that only ever
  translates the frontier section; read-ahead, "translate to end", retry, and the maintenance task
  all just raise a per-book target, so a section is never translated twice by accident. Its one
  deliberate exception is `retranslate(from, to)`, which walks a fixed range after a model change —
  safe because `setTranslatedSections` only ever raises the frontier, so a book stays as readable
  as it was while its text is replaced section by section.
- **Reading**: `ui/books/` pages `book_paragraphs` through Paging 3 (placeholders on, so list index
  == paragraph ord) — bounded RAM on a long novel, exact scroll restore. A Target/Native/Both
  control swaps text on the same items (Both renders the pair — target text, native gloss beneath —
  inside the one item, so the index↔ord identity holds); on a switch, near-viewport rows are tinted
  from the theme's reading palette (`CountryPalette.tints` — two core hues × three variations,
  interleaved) cycled by ord, so a paragraph keeps its colour across the switch. The tint eases in, holds to 8s, then fades over
  ~3s — except in Both, where it pairs the two languages and stays lit (and so covers every row,
  not just the captured window) until the reader leaves the mode. It is painted in `drawBehind` —
  reading the animation in the draw phase, not composition, so the fade never recomposes paragraph
  text. A horizontal swipe (`ui/components/ReadingModeSwipe`) steps the same control, clamped at
  both ends and previewed by a half-disc swelling from the edge the swipe came from. Crossing
  horizontal slop only makes it a *candidate*: the drag is abandoned once it has covered more ground
  vertically than horizontally (past a slop that tolerates the arc of a real flick), so a scroll that
  started sideways can neither arm the cue nor land a step; and once the step is certain the swipe
  consumes its movement, so the list can't fling on the gesture that flipped the page. A 2dp
  `ReadingProgressRule` sits between the text and the control. The mode enum, the row renderer and
  the tint all live in `ui/components/BilingualReading.kt`, shared with the article reader.
- **Download mode** (`BookEntity.downloadMode`, one per book, set in `BookDownloadModeDialog` from
  the reader's or library row's menu and at import): `OFF` (only the explicit "continue"),
  `WHILE_READING` (read-ahead keeps two sections past the reading position), `BACKGROUND`
  (read-ahead plus `BookTranslateTask`'s ~90-minute trickle), `RAPID` (a whole-book run in
  `BookBulkTranslateWorker`, a `dataSync` foreground worker with a cancellable progress
  notification, since a novel is 50–150 sequential calls and the app-lifetime scope dies with the
  process). `AppSettings.bookDefaultDownloadMode` is only the default for new imports.
  `BookDownloadController` is the single place a choice turns into work. The same dialog picks the
  book's model; `book_sections` records which model produced each section, so switching models
  offers to redo the sections an earlier one translated.
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

- **Notes and cards**: the vocabulary follows Anki's architecture. A **note** (`notes`,
  `NoteEntity`) is one thing being learned and everything true of it whichever way it is asked —
  both sides of its text (`term` is always the target language, `translation` the native one), its
  grammar, example, image, provenance. A **card** (`cards`, `CardEntity`) is one *direction* of a
  note — `TARGET_FIRST` (recognise it) or `NATIVE_FIRST` (produce it), at most one of each — and
  holds nothing but that direction's schedule and frozen flag. A word, phrase or sentence is
  learned both ways, so it has two cards; a cloze, grammar or conjugation card has the one
  direction it was written in. Content has exactly one place to live, which is the point: before
  the split each direction was a whole row with its own copy of the text, and the copies drifted.
  The two schedules stay separate because they are different memories — on the deck this was
  designed against, the produce-it cards had lapsed about twice as often as the recognise-it ones.
- **`flashcards` is a view.** `FlashcardEntity` is a `@DatabaseView` joining a card to its note,
  with the name and columns the single table used to have, so every `SELECT … FROM flashcards`
  still reads one row per card and most consumers did not change. Nothing can be written through
  it. `FlashcardDao` splits writes by owner: `updateContent` goes to the note (and is seen from
  both directions), `updateSchedule` to the one card. A review writes the schedule *only* — a
  session holds a card for minutes, and writing its stale copy of the note back would undo an
  edit that landed meanwhile. Ids handed around the app are **card** ids; a note-level write
  (`setMarked`, `setText`, `delete`) takes a card id and reaches the note through it. Queries that
  mean "per word" say `GROUP BY noteId`. Two view columns are fixed points kept for old readers:
  `frontIsTarget` is always true, and `reviewDirection` is the card's direction, never `BOTH`
  (which survives only as "one card each way, please" on a row about to be inserted).
- **Scheduling**: `domain/srs/SpacedRepetitionScheduler` wraps the FSRS engine (target retention
  0.9). Cards persist stability/difficulty/state; every grade appends to the `reviews` log, keyed
  by card id.
- **One review of a note per day.** Seeing one direction of a word hands you the answer to the
  other, so `FlashcardRepository.review` moves any sibling that was due (or would come due later
  that day) to the start of tomorrow — a calendar day, not 24 hours — and `undoReview` puts it
  back. Frozen siblings are left where they are. Due *counts* are therefore counts of notes
  (`COUNT(DISTINCT noteId)`): two due cards of one word are one review on offer.
- **Session composition**: `domain/srs/ReviewOrdering` is a pure object deciding *which* cards a
  session holds and in what order — due-first with new cards interleaved, study-ahead when short,
  a frequency tilt, and **at most one card of a note**, never topped back up. It also drops any
  card whose sibling was reviewed today (`withoutSiblingsOfReviewed`), which the due date alone
  can't do: study-ahead would reach straight for the card that review just moved to tomorrow.
  `FlashcardRepository` reads the deck and delegates, which is what keeps the ordering rules
  testable without a database.
- **Daily new-card limit** (`domain/srs/DueToday.kt`): `AppSettings.newCardsPerDay` (per profile,
  default 20, negative = no limit, 0 = none for now) caps how many never-studied cards a day's
  reviews take on. Nothing is rescheduled or hidden — a held card keeps its date and waits for a
  later day's allowance. Usage is read off the review log (`countFirstReviewedSince` midnight), not
  kept as a counter, so an undo hands its place back. Two things follow from the limit and both
  live in one place each: `FlashcardRepository.observeDueToday` is the due count every screen and
  the reminder use — due reviews, which are never held back, plus only the new words still
  admitted — and `ReviewOrdering.buildSession(newAllowance)` spreads the admitted cards through
  the sitting being built rather than through the whole due pile, and never studies ahead into a
  new card. The frequent-word drill is exempt: it is asked for by name.
- **Frequency-ranked vocabulary**: `domain/vocab/freq/` bundles the top 2,000 content lemmas per
  language (`FrequencyLists.forTag`, generated by `tools/build_frequency.py`, packed as lines and
  parsed lazily so an unselected language costs nothing). `VocabCoverage` joins cards to the list by
  reusing each card's cached `inflectedForms` rather than lemmatising afresh — it feeds the stats
  coverage bars, the rank chips, the review tilt and the frequent-word drill. The drill itself is a
  narrowed review session (`ReviewOrdering.buildFrequencySession`), reached from the Routines tab and
  planned into routines as the `FREQUENT_WORD_DRILL` activity. `data/vocab/FrequencySeeder` fills
  gaps as notes via on-device translation, staggering `dueAt` so a large seed arrives over weeks;
  seeded notes carry `autoSource = "freq"`, which is both the cleanup handle and the one case where
  LLM enrichment may replace a note's translation (a bare lemma glosses poorly — *tener* and
  *haber* both come back as "to have").
- **Vocab screens** (`ui/vocab/`): the list is one row per note (`leadCard`: the soonest-due unfrozen
  card speaks for the word, and a word reads as frozen only when every direction is). Edit, mark
  and delete act on the note. **Freezing is per direction** — each direction has its own schedule
  box and snowflake on the detail screen — with the list's long-press as the whole-word shortcut.
  Passive credit for meeting a word while reading goes to the term-first card only: that is
  recognition, and says nothing about production.
- **Leeches** (`domain/srs/Leech`): a card forgotten eight times that has still not reached a
  mature interval. Derived from the schedule a card already carries — no column, nothing to keep in
  step — and per card, like freezing. The maturity clause is what makes it usable on an imported
  deck, whose lifetime lapse counts would otherwise flag words that settled years ago. A leech is
  only ever *pointed out*: the Vocab filter ("keeps slipping", matched per note through
  `leechNotesIn`, since a list row is only the lead card), a line on the detail screen, and one
  dismissible strip in review when a grade crosses the threshold (`Leech.crossedInto`: at eight and
  every fourth lapse after). Nothing is frozen on the learner's behalf.
- **History and moving a schedule by hand**: each direction's box on the detail screen folds out its
  review log (`ReviewLogDao.observeForCard`) and carries two actions. *Set due date* writes `dueAt`
  alone — FSRS grades the next answer on the time actually elapsed, so no interval is fixed up.
  *Start over* (`FlashcardRepository.resetSchedule`) returns the card to new with its lapse and
  repetition counts zeroed, and keeps the log.
- **Review UX**: `ReviewScreen` does Again/Hard/Good/Easy with re-queue-on-Again, bidirectional
  and cloze rendering (`domain/model/Cloze` parses Anki-style `{{c1::…}}`), images, audio, and an
  undo stack (restores the pre-review schedule, deletes its review-log entry, and returns any
  sibling the review had deferred).
- **Anki round-trip**: `data/anki/` imports and exports `.apkg` — original note fields ride along
  in `ankiFieldsJson`, review history is converted in both directions, and media files are stored
  by filename under `files/anki_media/` (filenames, not absolute paths, so backups restore
  cleanly). The mapping is now direct: Anki note ↔ note (by guid), Anki card ↔ card (by template
  ordinal, or failing that by direction). `AnkiImporter` still emits a row per Anki card;
  `AnkiImportService` gathers them by guid and lets the term-first card speak for the note's
  content, the rest contributing only schedules and histories. On export a card made in Copiosa
  takes the template that faces its way, and an app-made note keeps the `la-<id>` guid it exported
  under when it was a single row, so a deck already in Anki still matches.
- **JSON round-trip**: `data/cardjson/` is the *legible* counterpart to the `.apkg` — a file for a
  person or an agent to read, edit and hand back. It is an export of **notes**: `CardJsonExporter`
  writes one note per line with sides named by language, omits everything empty, leaves out what
  nobody edits by hand (explanation, media, FSRS internals, Anki pass-through), and nests the
  directions inside the note as a `cards` object keyed `target-first` / `native-first`, each with
  only its own schedule. `CardJsonImporter` plans the way back as a **merge by note id, keyed on
  presence**: a key the file carries is applied, a key it doesn't is left alone, an explicit
  `null` clears — which is what lets an image or a schedule survive a file that never mentions
  it. `frozen` inside a card sets that one direction aside; naming a direction the note has no
  card for starts studying it. An entry with no id is a new note (`autoSource = "json"`, studied
  both ways unless it says otherwise, skipped if the deck already teaches the word); a note
  missing from the file is never deleted. An id is trusted only when the entry's `created` date
  agrees with the note's, since ids are row numbers and a file from another install would
  otherwise overwrite unrelated notes. Files from format versions 1–2, where an entry was a card,
  still import: each id is resolved to the note that card now belongs to. The plan is pure and is
  shown as counts before `CardJsonImportService` writes anything; the file's own `about` header
  states these rules for whatever edits it next.
- **Bulk add**: `data/vocab/BulkCardCreator` + `ui/vocab/BulkAddSheet` turn a typed,
  comma-separated list in the learner's own language into drafted cards to tick, correct and
  confirm — nothing is written before the confirm. One model call per 25 items
  (`domain/prompt/BulkCards`) supplies the headword a speaker from the profile's country would
  use, plus part of speech, gender and an example; with no key or a failed call the on-device
  translator stands in with a bare word pair. Drafts the deck already teaches arrive unticked.
  Each becomes a note with a card each way, stamped `autoSource = "bulk"`, and only a batch of ten
  or fewer is queued for immediate enrichment — a longer one waits for `EnrichVocabTask`.
- **Images**: `ImageSearchRepository` (Wikimedia Commons; the 500px search thumbnail is also what gets
  attached, so picking one costs no second download) with a bulk "add images" stepping flow.

### Audio: TTS synthesis and playback

- **Engine**: `data/tts/TtsEngine` wraps sherpa-onnx's Supertonic model (multi-voice,
  multilingual). It synthesizes sentence-by-sentence with explicit inter-sentence gaps (the model
  under-pauses on periods in some languages), adds an emphasized spoken title and a closing chime
  for playlist articles.
- **Encoding**: output is AAC-LC mono 80 kbps in `.m4a` (MediaCodec + MediaMuxer) — ~12× smaller
  than the WAVs the app originally wrote. `M4aEncoder` is the streaming form and the one synthesis
  actually uses: each sentence is encoded as it is generated, so peak memory is one buffer rather
  than the whole render. That is what makes a book chapter possible at all — ~5.8 MB of float PCM
  per minute of audio means a twenty-minute track would otherwise need ~115 MB before encoding
  started. `M4aWriter.write` is the whole-input wrapper, left for the WAV compaction migration
  (`WavPcm` + `AudioCompactor`, a one-time startup pass that re-resolves stale absolute paths by
  filename and then becomes a no-op).
- **Queues**: `TtsRenderQueue` is the shared drain machinery — single consumer, gated on the voice
  model being `Ready`, status persisted on the Room row so relaunches resume. Owners hand it a
  small `RenderQueueStore` (unrendered ids + status writes) and a render callback; the queue itself
  owns the GENERATING/PENDING/FAILED envelope and orphan repair. It backs both
  `AudioGenerationManager` (playlist) and `QuizAudioGenerationManager` (quiz pools). `unrendered`
  orders untagged items first, so a whole book's backlog can never starve a chat reply.
- **Model store**: `SupertonicModelStore` downloads the ~100 MB voice model only on explicit user
  request; everything degrades gracefully (items stay PENDING) until it is installed.
- **Play now**: `ui/audio/PronounceButton` is the self-contained play button on the lookup card,
  the target side of a review card, and each chat reply. It goes through `playback/Pronouncer`,
  which keeps nothing: `TtsEngine.speech` emits one sentence of PCM at a time and each is written
  to a streaming `AudioTrack`, so a long reply starts after one sentence's synthesis rather than
  after all of it. One utterance at a time, transient audio focus, and it stops when the activity
  does (no foreground service — a process frozen mid-sentence would keep the focus it took). Text is
  reduced to prose first by `domain/text/SpeechText`. "Send to audio" (headphones) is the other
  path: it queues a file for the Audio tab.
- **Folders**: `audio_items.tag` is a free-text grouping label, folded into folder rows by
  `ui/audio/groupByTag` (in Kotlin, over the same flat query — no second query, no second ordering
  rule) and opened as `Routes.AUDIO_TAG`. Tagged items are exempt from three of the playlist's
  behaviours, all for the same reason — a folder is a collection the learner created and deletes as
  a unit: `markPlayed` leaves their `sortOrder` alone (a book stays in chapter order), and both the
  "delete played" sweep and the retention pass skip them.
- **Book audio**: `BookAudioManager` turns an imported EPUB into one track per chapter, split at
  ~18,000 chars (`BookAudioChunker`, never splitting a section) and tagged with the book's title.
  Chapter text is markdown-stripped by `domain/book/speechTextOf`. It only enqueues — rendering
  stays on the one shared queue. A forward book can only be spoken as far as its translation
  frontier, so `BookAudioTask` queues each chapter as the frontier passes it, and `BookAudioWorker`
  (foreground `dataSync`, mirroring `BookBulkTranslateWorker`) holds the process alive for what is
  hours of on-device synthesis.
- **Playback**: Media3 via `PlaybackController` + `AudioPlaybackService` (background playback,
  playlist ordering with played-items-sink semantics, drag reorder). `positionMs` is the per-track
  resume point, persisted on pause, on manual skip and every 15 s — a twenty-minute chapter is too
  long to restart, and a backgrounded process can be killed with no callback. An optional retention
  setting auto-deletes played *loose* items after N days.

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

### The cloze bank

Fill-the-blank practice on sentences the learner already owns (`domain/cloze/`, `data/cloze/`,
`ui/cloze/`). One table of sentences, `cloze_sentences`, each stored with Anki `{{c1::…}}` markup
and usually linked to the card whose word it blanks.

- **The deck decides what a sitting holds.** `ClozeSelector` (pure) ranks the bank by how much the
  word behind each sentence needs practice — asked for by name, missed in a review today, in
  relearning, a leech, due, due this week — and takes one sentence per word. The bank was first
  built as a queue of its own, soonest-due sentence first; with every sentence due at once that was
  an arbitrary order and a second backlog beside the deck. A sentence still keeps an FSRS record
  (`ClozeRepository.record`), but it only breaks ties: a sentence answered easily last week waits
  behind one never seen. `Routes.clozeFor(cardIds)` opens the screen straight into a sitting on
  given words — the end of a review session offers it for that session's misses, and a word's
  detail screen for that word.
- **Sources**, all held to one `ClozeGate`: read book pages, imported texts and chat replies
  (`BookSentenceSource`, `ConversationSentenceSource`, both choosing the blank through
  `SentenceBlanks`), dictionary examples, the card's own example sentence, and — one call a day,
  for words nothing local serves or that keep slipping — the model. Reading goes first: a card's
  own example is the one sentence the learner has already seen that word in. Reading sources take
  only sentences of twelve words or fewer, which is also what the listening modes can use.
- **The bank rotates.** `ClozeMineContext.BANK_MAX_PER_LANGUAGE` is a ceiling, not a stop: a full
  bank gives up sentences whose card has *settled* (`ClozeSelector.isSettled` — mature and not due
  for a week), never one pinned, flagged or converted from Anki. The same line stops anything being
  mined for a settled card, so a sentence is not evicted and mined straight back.
- **Blanks** are one content word, or a phrase card's whole term when the sentence spells it
  literally. A form the card's cached inflections don't list (a stem-changing verb's *duerme*) is
  found by asking the dictionary pack which headword it belongs to.
- **Wrong options** (`ClozeDistractors`) agree with the answer's ending so grammar can't eliminate
  them, and come from two pools: the frequency list, and the learner's own vocabulary. A rare answer
  draws two of its three from the deck — beside three common words it was simply the odd one out —
  and the list always supplies at least one when it can.
- **Translations** are filled by the on-device translator as a sentence is banked
  (`machineTranslated`), so nothing waits on the model; the model's translation pass then replaces
  those as it reaches them.
- **Ways to sit**: pick from four; **type** the word (`ClozeTyped` — a missing accent is right and
  shown corrected, a miss can be overruled); **listen** (below); a **reading passage**
  (`ClozePassage` — several of the learner's words taken out of a page they have read and offered
  back as a word bank; made fresh each time, never banked, and credited to the cards as cloze
  nudges); and the **common-words track** (`ClozeSelector.selectTrack` — the frequency list's
  words in rank order, including ones never carded, which the reading sources bank one sentence
  each for).
- **Browse** (`Routes.CLOZE_BROWSE`): the whole bank as a searchable list — pin, edit, set aside,
  delete.

### Listen and rebuild

The first activity that trains listening rather than playing audio beside a task: hear a banked
sentence, tap its shuffled words back into order, and only then see it written. It draws on the
cloze bank (`cloze_sentences`) and the on-device voice — no LLM, no new table.

- **Domain** (`domain/listening/ListenRebuild`): cuts a sentence into tiles (4–12 words), stripping
  punctuation and the sentence-initial capital because both give the order away; shuffles so the bank
  never arrives solved; grades by case-insensitive word, so repeated words are interchangeable.
- **Data** (`data/listening/ListenRebuildRepository`): `ClozeDao.rebuildPool` prefers sentences
  already met in a cloze sitting, then random. Clips come from `SentenceClipStore`, which renders on
  demand into `cacheDir/rebuild_audio`, content-keyed, with the voice derived from the sentence id
  (speaker variety, cache hits), capped at 300 files. It **reads the bank and never writes it**:
  hearing a sentence is not recalling its blank, and crediting the cloze FSRS schedule would make it
  lie.
- **UI**: one runner, `ui/listening/ListenRebuildActivity`, shared by the standalone screen
  (`Routes.LISTEN_REBUILD`, Routines tab) and the routine step. A full arrangement is checked at once;
  on a miss the correct leading words lock and the rest return to the bank, and the item counts as
  missed. Play and a 0.7× slow replay go through `QuizClipPlayer`. The standalone screen starts as soon
  as its first clip exists and renders the rest behind it.
- **Routine** (`ListenAndRebuild` descriptor, `LISTEN_REBUILD`, settle band): clips are rendered at
  build time so the player never waits on the model; the planner marks it unavailable without the
  voice model or a bank of at least three sentences. Attempts feed only the routine's own bucket.

### The cloze bank by ear

Two more ways to use the bank's sentences, both built on that clip store and that rule.

- **Listening sitting** (`ClozeViewModel.startListening`): the bank's ordinary items, but each
  sentence is played and not written out until an answer is picked. `MultipleChoiceActivity` takes
  the clips as an `audio` list *beside* its items rather than on them: clips land one at a time while
  the sitting runs, and the runner's state is keyed on the item list, so a path arriving on an item
  would start the sitting over. The clip says the missing word aloud, so an answer here scores the
  sitting and **writes nothing** — no `cloze_reviews` row, no card nudge. The sentences are chosen
  by the same `ClozeSelector` as any other sitting.
- **Radio** (`domain/listening/RadioScript`, `TtsEngine.synthesizeRadio`): a hands-free pass over
  the bank, made as **one ordinary playlist track** rather than played by the cloze screen, so
  background playback, speed, the resume point and the folder view are the playlist's own. The row
  has `kind = RADIO`, its `text` is the script (a sentence per line, a tab before its translation),
  and `AudioGenerationManager.render` branches on the kind — still on the one shared render queue.
  Each sentence is spoken, left a pause to work out, glossed in the native language by a second
  voice, and spoken again; the repeat reuses the first rendering. Tracks go in a tagged folder, so
  the played-item sweeps leave them until the folder is deleted.

### Conjugation drill

`domain/conjugation/` is **spec-driven**: a `ConjugationSpec` carries one language's ending tables,
paradigm list, persons and pronouns, derived tenses, transform rules and verb lexicon, and
`Conjugator` / `ConjugationEngine` / `ConjugationReference` are instances over it holding no language
data of their own. `ConjugationSpecs.forTag` resolves one; **Spanish** (200 verbs, seven paradigms)
and **European Portuguese** (200 verbs, nine) ship today.

A `DerivedTense` models a paradigm built from another cell rather than from a stem plus an ending —
Spanish's imperfect subjunctive off the third-plural preterite, and Portuguese's future subjunctive
off the same cell plus its personal infinitive off the infinitive. A derived cell is never stored:
it inherits its source's irregularity for free (`supieron → supiera`, `fizeram → fizer`). The one
exception is a stored override on a derived cell, which wins — that is how Portuguese carries the
handful of imperfect-subjunctive plurals whose written accent is open where the rule predicts closed
(`tivéssemos`, not `tivêssemos`).

**Only deviations are stored** — the conjugator reproduces regular cells at runtime, and a golden
replay per language (`ConjugationGoldenTest`, `ConjugationGoldenPtTest`) runs every cell of
`conjugation_golden*.csv` through the Kotlin engine so the Python generator and the Kotlin engine
cannot drift apart. `PortugueseConjugationTest` is the independent check the two mirrors can't be:
hand-written paradigms, so a shared typo fails rather than being reproduced at both ends.

`Person`'s constant names are Spanish for historical reasons and are frozen; its *slugs* are not
(`1s`…`3p`), which is what lets Portuguese reuse the enum. The pronoun a learner reads comes from
`ConjugationSpec.pronouns`. `IrregularityFamily` is shared across languages (proficiency rows are
unique on `(bucketKey, targetLanguage)`); `IrregularityClass` is per-language, Portuguese's under a
`pt_` prefix.

It backs both the `CONJUGATION_DRILL` routine activity and a standalone drill (`ui/conjugation/`),
the tense courses below, and — through `ConjugationReference` — the reference section.

`DrillVerbFilter` is the seam the courses added: an optional per-*session* narrowing (infinitives,
verb classes, irregularity classes, regular-only, **persons**) passed to
`ConjugationDrillSelector.select`, as against `DrillRestriction`'s per-*verb* rules. `regularOnly` is
scoped to the session's tenses, not `VerbEntry.isRegular` — *pensar* is a fine regular-preterite
example despite its present-tense stem change. `persons` is the one member that narrows *cells*
rather than verbs, and it is what lets a course teach a paradigm two endings at a time; it
intersects with the config's own person list, so it can never re-admit vosotros.

### Grammar courses

`domain/course/` is the app's taught half: authored curricula that teach one grammar point from
zero, then hand the material to FSRS. Spanish ships four — `es_preterite` (17 lessons),
`es_imperfect` (9), `es_past_contrast` (5, soft-prerequisite on both), and `es_por_para` (15, no
prerequisites). `CourseCatalog.forTag` resolves them like `ConjugationSpecs.forTag` does specs.

**Lessons are deliberately small in scope and long in practice.** A paradigm is six endings and a
learner meeting a tense for the first time can hold two, so the form lessons advance a couple of
persons at a time (`LessonScope.persons`) with a consolidation lesson putting the paradigm back
together before the irregulars start; teaching and practice interleave inside a sitting rather than
stacking prose in front of cards. `CourseCatalogTest` holds the pacing to that shape: every drill
must be fillable to its authored length from its own scope, a lesson with most of the paradigm in
play carries at least twenty practice items, and a long lesson with two drills needs two teaching
beats.

The division of labour is the invariant worth keeping, and it mirrors the reference section's:
**what a paradigm is** comes off the spec (a `TableBlock` is rendered live by the engine at play
time, so a lesson's table cannot disagree with what the same session grades), while **what it means
and when to reach for it** is authored in `domain/course/data/SpanishPastCourses.kt` and
`SpanishPorParaCourse.kt`. Prose lives in
Kotlin rather than string resources, deliberately: it is content data like `VerbLexiconData`, and
keeping it in `domain` is what lets `CourseCatalogTest` replay every authored example — and every
graduation card's cloze answer — through the conjugator, so a mistyped verb form fails the build
instead of being taught.

A `LessonScope` states a lesson's coverage in engine coordinates (tenses, directions, verb and
person narrowing, an optional `ContrastCategory` slug), which is what lets one declaration drive
the drill selection, the LLM request, the generated-item validation, and a checkup's per-lesson
attribution. `LessonScope.union` — behind `CourseDefinition.cumulativeScope`, which the checkup and
the routine bias read — merges **all-or-nothing**: a narrowing survives only where every scope
carries one, since a null member means "no restriction" and no restriction wins. Four lessons of
regular verbs therefore still union to "regular verbs only", and nothing built on that can ambush a
learner with *dijeron*. `contrastCategory` is the one member that merges by *agreement* rather than
by union — it is a label, not a narrowing, so a union spanning two buckets names neither.

**A course need not be about morphology.** `es_por_para` teaches a preposition choice, which has no
paradigm to narrow, so its scopes name **no tenses at all** (`LessonScope.isMorphological` is the
switch) and carry only the `por_para` bucket. Nothing can be generated for such a lesson, so it
carries its own practice: `AuthoredSegment.AuthoredDrill` holds hand-written `AuthoredChoiceItem`s
beside the prose they follow, and `LessonSegmentBuilder` materializes them into the same
`ClozeSegment` the LLM path produces. Those banks *are* the course — its offline floor, and the pool
its warm-up, checkup sweep and micro-lesson sample instead of running the drill selector. An
unfilled `LlmSlot` widens nothing here, because the bank already played in full. `CourseCatalogTest`
gives the banks the check it gives the tense courses' morphology: one blank per item, an answer
among the options, an explanation, no sentence opening on the blank (the option text is lower case,
so it would render uncapitalised), no answer visible outside the blank, and no bank leaning more
than 70% on one side — a contrast that can be scored by tapping the same word every time is
teaching a habit rather than a discrimination.

The **warm-up** does not use the union at all. `LessonSegmentBuilder.warmUpItems` samples the last
few lessons *each through its own scope*, most recent first, because merging loses the correlations
that make a gradual course gradual: "-ar verbs, every person" and "-er verbs, yo and tú" blend into a
licence to ask for *comimos* three lessons early. Sampling per scope can only produce cells some
lesson actually taught, which `CourseCatalogTest` asserts card by card. `warmUpAuthored` applies
the same rule to banks, per lesson rather than from one pooled draw, and on both paths a warm-up's
answers are exempt from the sitting's own accuracy.

A sitting is assembled by `LessonSegmentBuilder` (pure, given a `DrillSelector`) into a `LessonPlan`
stored in `course_sessions.planJson`, with `currentIndex` as the resume point — `routine_runs`'
shape. **`LessonPlanCodec` decodes element-wise**: an unrecognised segment kind is dropped rather
than failing the plan, which is the one place this deliberately diverges from `RoutinePlanCodec`
(whose all-or-nothing decode is why a retired `ActivityContent` needs a `@Deprecated` stub). A
retired segment kind therefore costs nothing.

Lessons open in **any order** and prerequisites are a notice, never a lock. `ui/course/` is the hub
plus the lesson player, which hosts `ConjugationDrillActivity` as a third host and the routine's
`MultipleChoiceActivity` for cloze/PI beats. The player's **restart** discards the session row and
opens a fresh one (`CourseSessionManager.restart`), rebuilding rather than rewinding: a lesson
re-taken with the identical twenty cards would measure recognition of that sitting. It is offered in
every state, including the failure screen, which is the only way out of a sitting whose build died.
The row is the plan, not the record — graded answers are already in the conjugation stores, so
discarding it loses a position and nothing else.

- **LLM role** (≤2 calls per lesson, 0 offline): one consolidated call fills the authored `LlmSlot`s
  with contextual cloze/PI items; an optional second diagnoses the sitting's mistakes on the wrap-up
  screen. `CourseContentValidator` over-requests 1.5× and validates down, and `ScopeFormIndex` gives
  it a check the routine validator can't have — the engine adjudicates whether an answer belongs to
  the paradigm being taught. Its verdict is deliberately three-valued: a form the 200-verb lexicon
  has never seen is *allowed* (a generated sentence may legitimately use another verb), and only a
  form positively placed in the **wrong tense** is rejected. Every failure path — no key, offline,
  unparseable, rejected — drops the slot and widens an engine drill instead, so a lesson always
  plays.
- **Retention**, and deliberately not a second scheduler: graduation mints `GRAMMAR` cloze cards
  (authored) and `CONJUGATION` paradigm cards chosen from the learner's *own* weakest
  `conjugation_item_stats` cells, `autoSource = "course:<id>"`, `dueAt` staggered over two weeks —
  from there FSRS owns the material. What the `CheckupScheduler` ladder (3/7/14/30/60 days) adds is
  the thing FSRS can't: a failed checkup steps the ladder back one rung *and* `CheckupBucketing`
  attributes the misses to the lessons that taught them, so the hub reopens those lessons'
  micro-lessons. Checkup due-ness is computed on read from `nextCheckupAt` — no background task.
  Accepted gap: `CheckupBucketing` attributes by conjugation coordinates, which a preposition item
  does not have, so `es_por_para` graduates its authored cloze cards (and no paradigm cards, its
  cumulative scope naming no tenses) and drives the ladder, but flags no individual lesson.
- **Routine hook**: `buildConjugationDrill` steers half its block onto the active course's
  cumulative scope, so routines reinforce what has been taught without running ahead of it.
  `ConjugationDrillContent` is unchanged, so there is no codec or registry impact.

`CourseTextDumpTest` is a utility rather than a test: given `-DcourseDump=<path>` (and optionally
`-DcourseId`) it writes one course's authored strings to JSON — prose, taglines, and, for a course
that carries them, every practice item — so the content can be reworked outside the Kotlin. Paths
resolve against `app/`, the forked test JVM's working directory. Going back is deliberately manual:
the `i` indices locate each string in the source, and nothing writes Kotlin, because prose is
authored beside the scope it describes and a generator would separate the two.

Answers reuse the existing stores rather than adding one: drill grades go through
`ConjugationDrillRepository.record` (per-item mastery + `activity_proficiency`), and cloze/PI answers
call `RoutineAdaptation.recordStandalone` on the contrast slug. New tables hold only enrolment,
per-lesson standing, and sittings. Accepted v1 gap: course cloze/PI answers move proficiency but
leave no append-only log (`activity_attempts.routineRunId` is non-null — the same reason the drill
has its own tables).

### The reference section

`ui/reference/` is the app's look-it-up half, reached from the Home header and shaped like stats: a
hub you enter, read, and leave, not a tab. `ReferenceHubScreen` lists `ReferenceTopic`, one enum
constant per thing the app can explain; `Routes.REFERENCE_TOPIC` (`reference/{topic}?focus={focus}`)
is the single route behind all of them, so a new topic costs a constant, a screen, and a `when`
branch. A topic declares `needsConjugation` when its content is built out of `ConjugationSpec`, which
is what hides both of today's topics from a profile with no verb data; `ReferenceViewModel` resolves
the profile's spec once off the main thread and every screen in the section reads it.

Two topics ship. **Verb conjugation** is `ui/conjugation/ConjugationReferenceScreen`, also reachable
from the drill's top-right icon with `focus` carrying the verb the card was asking about. **Tenses
and moods** (`TenseReferenceScreen`) groups the language's paradigms by `Tense.mood` and gives each
one a sentence on what it expresses, a line on how it is formed, and the model verbs conjugated
across it.

The split inside that topic is the invariant worth keeping: what a paradigm *is* — what it is built
from (`ConjugationSpec.derived`, `infinitiveStemTenses`), which persons it has, how it spells out —
is read off the spec, so it cannot disagree with what a session just graded. Only what a paradigm is
*for* is authored, in `TenseNotes` and `R.string.ref_use_*`, because no ending table encodes it.

### Language profiles

`domain/language/LanguageProfile` is the single description of a target language's *grammar*: which
contrasts are worth drilling, the subject pronouns and time adverbs a pro-dropped sentence must not
contain, real false-friend examples, the gender bins nouns sort into, and (resolved from
`ConjugationSpecs`) its verb paradigm. `LanguageProfiles.forTag` resolves one, ignoring the region
subtag. Spanish and European Portuguese ship today.

This replaced seven activities carrying `supportedLanguages = setOf("es")` with Spanish specifics
inlined at each use — a pronoun list in one prompt, `embarazada` in another, `el (m.)` in a string
resource, `ser vs estar` as a hardcoded fallback. An activity now declares what it *needs*
(`ActivityDefinition.requires`: `GRAMMAR`, `GENDER`, `CONJUGATION`) and a language declares what it
*has*, so neither is edited when the other changes. `supportedLanguages` survives only for the one
case that really is a fixed tag list — rapid-fire, gated by `WordNumberSpeller` coverage.

`ContrastCategory` is the catalog across every language, not one language's set; a profile picks its
own. Slugs are frozen (they are proficiency-bucket keys) and may be shared, since proficiency rows
are unique on `(bucketKey, targetLanguage)` — ser/estar is a real contrast in both languages and
keeps separate mastery in each. Portuguese adds three of its own, of which the future subjunctive
(*se fizer*) is the one a Spanish speaker most reliably gets wrong.

Adding a third language is: write one `LanguageProfile`, and — for the conjugation drill — a
`ConjugationSpec` plus a `tools/conj/<lang>.py` and one generator run. Nothing in
`domain/routine/activities/` changes. `LanguageProfileTest` enforces the completeness rules the way
`ActivityRegistryTest` does for the registry.

### The routines engine

The most structured subsystem. A routine flows through four stages, honoring a
≤5-LLM-calls budget (target: **1**):

1. **Plan** (`domain/routine/RoutinePlanner`, no LLM): splits the time budget across the arousal
   bands, weighted-samples activities per band using the adaptation engine's per-bucket selection
   weights, and guarantees a monotonic arousal curve. The planner itself is routine-agnostic —
   **`RoutineType` owns the shape of the session**: the band order (`bandOrder`), the within-band
   sort direction (`ascending`), which type is pinned last (`closerType`), what synthesis must
   produce to count (`requiredType`), and the register the generator writes in. A routine draws
   freely up to its `maxArousal` and admits a small quota above it, confined to one band.
   - **Bedtime wind-down**: Engage → Settle → Drift, descending, closing on the pinned short text.
     Draws freely to `MEDIUM` plus one `HIGH` in Engage, so a genuinely activating drill can open
     the wind-down without derailing it; a ≤5-minute routine skips Engage and stays within the cap.
   - **Daytime activation**: the mirror image — Drift → Settle → Engage, ascending, no closer, and
     no ceiling, so the session ends on its most activating slot. A ≤5-minute routine skips Drift
     rather than Engage: it drops the warm-up, not the peak.
2. **Synthesize** (`data/routine/RoutineSynthesisManager`): one consolidated LLM request generates
   content for every slot that needs text; `RoutineContentParser` decodes it forgivingly and
   `RoutineContentValidator` checks grammar items against the dictionary forms table. Offline
   activities (flashcards, swipe-sort from the learner's own gendered nouns, rapid-fire from the
   quiz pool, conjugation from the bundled verb lexicon) build locally. The result is a
   `RoutinePlan` serialized into `routine_runs.planJson`
   (kotlinx polymorphic JSON, `"kind"` discriminator — treat `@SerialName`s as frozen).

   > **Retiring an activity is not free.** `RoutinePlanCodec.decode` fails the *whole* plan on an
   > unrecognised `"kind"` or `ActivityType` name, not just the one activity, and a run whose plan
   > no longer decodes sits on a permanent "preparing" spinner with no retry path. So a superseded
   > content class stays `@Deprecated` in the sealed hierarchy *and* registered in
   > `ActivityRenderers`, and its old type name goes in `ActivityType.RETIRED`. See
   > `ShortTextContent` and its two predecessors for the worked example.
3. **Play** (`ui/routines/RoutinePlayerScreen`): steps through activities, recording attempts
   (correct/latency/listening-speed) via `RoutinePlayerViewModel`. Every step sits under
   `RoutineStepHeader` — exit, progress, skip — which the player draws above a framed step and a
   full-screen step places itself (`ActivityRenderContext.Header`). Skipping is `advance`, the
   same call as finishing; it records no attempt, so it costs the adaptation estimate nothing.
   **Leaving keeps the run**: `currentIndex` is the resume point, and
   `RoutineRepository.observeResumable` offers the newest unfinished run (under a day old) back
   from the Routines tab and Home. At most one unfinished run exists per language — starting a
   routine discards the others. Synthesis jobs are tracked per run in `RoutineSynthesisManager`,
   which is what makes Cancel on the preparing screen possible (`discard`: cancel the job, delete
   the run), stops a second request from paying for the same plan twice, and lets a run left
   "preparing" by a killed process be picked up again (`resumeIfStalled`).
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

`ui/home/` is the landing dashboard: cached due count, the daily study-goal tracker, a "continue
reading" callout, and a **Keep going** section split in two. The left column is *contextual* —
cards for what is actually waiting (due words, unheard audio, unread chats), so it shrinks to
nothing on a clear day. The right column is *session formats* — 10 cards, 50 cards, rapid fire,
conjugation (gated on `ConjugationDrillRepository.supports`), a new chat — at half the height,
fixed and stateless, so the fastest way into a session never depends on the app having something
to suggest. With the left column empty the formats take the full width. The card counts are
deliberately not the suggestion's cap: they say how long a sitting you want, and
`buildReviewSession` studies ahead to fill them. Behind it, `data/stats/FocusTimeTracker` accrues per-activity
focus time (screens opt in via `TrackFocusTime`), `FocusStatsRollupTask` rolls sessions into daily
rows, and `ui/stats/StatsScreen` charts totals, accuracy, and streaks (`domain/stats/Streak`).

### Settings and language profiles

`data/settings/SettingsRepository` stores per-profile settings in DataStore keyed by target
language tag (`p.<tag>.<field>`); switching profiles switches everything (provider/model, prompts,
theme, voice, playlists filter by language). API keys are global and encrypted.

## Data model

Room database `copiosa.db`, schema **v47**, migrations in `di/DatabaseModule.kt` (append-only; the
only destructive one rebuilt the quiz tables at v21→22). Highlights:

| Table | Purpose | Notes |
|---|---|---|
| `conversations` | chats + imported texts | `mode` distinguishes; original text stored for texts |
| `messages` | chat history | `metadata` = structured-reply JSON per message |
| `notes` | vocabulary content | one per word: text, grammar, media, provenance (source conversation, book or routine run — at most one), Anki note pass-through |
| `cards` | one direction of a note | FSRS state + frozen flag; unique on (noteId, direction); ids are the old flashcard ids |
| `flashcards` | **view**: a card joined to its note | read-only; the shape the old table had, so existing queries still work |
| `flashcards_v42` | the pre-split table | superseded by 42→43, kept for one release as a way back; drop it next |
| `reviews` | review log | append-only, keyed by card id; fuel for future FSRS parameter fitting |
| `audio_items` | TTS playlist | `filePath` absolute; status drives the render queue; `playedAt` drives retention; `tag` groups a book's chapters into a folder; `kind` says what `text` is (`ARTICLE` prose, `RADIO` a sentence script) |
| `quiz_pools` / `quiz_cues` | rapid-fire pools | unique per (lang, cueType); pre-rendered audio |
| `cloze_sentences` / `cloze_reviews` | the cloze bank and its answer log | unique on (language, lemma, textKey), which is what makes mining idempotent; `cardId` has no foreign key, so a sentence outlives its card; `pinned` exempts a row from rotation, `machineTranslated` marks a translation the model may replace |
| `routine_runs` | routine sessions | `planJson` = serialized `RoutinePlan` |
| `activity_attempts` | per-answer log | **write-only**: nothing reads it back. Adaptation reads `activity_proficiency`, which each attempt updates as it is recorded |
| `activity_proficiency` | mastery per bucket | (bucketKey, language) unique |
| `topics` / `story_threads` | routine seeds, serial-story recap | deduped / one per language |
| `books` / `book_paragraphs` | imported EPUBs | one row per paragraph holding both languages; `translatedSections` is the frontier |
| `course_progress` | course enrolment + checkup ladder | (courseId, language) unique; `nextCheckupAt` drives the passive nudge |
| `course_lesson_state` | per-lesson standing | `needsReview` = a checkup flagged it; `generatedItemsJson` keeps validated LLM items |
| `course_sessions` | course sittings | `planJson` = serialized `LessonPlan`; `currentIndex` is the resume point |
| `llm_usage` | the usage meter | running totals per (local day, provider, model, feature, book, costReported); tokens always, cost only where the provider billed it; `bookId` is 0 for none and deliberately not a foreign key, so deleting a book keeps the record of what it cost |

On-device storage outside the DB: `files/audio/` (playlist and book-chapter `.m4a`),
`files/quiz_audio/` (cue renders, content-hash-named), `files/anki_media/`, `files/book_covers/`,
`files/tts_supertonic/` (opt-in model), `files/dict_<lang>.sqlite` (opt-in dictionary pack).
Book audio lives in the same `files/audio/` as the playlist, so nothing about paths or backups
changes — but a novel read aloud is roughly 11 hours and ~330 MB, which the backup zip inherits.

**Backup/restore** (`data/backup/BackupService`): one zip with the checkpointed DB, the media
folders, and the DataStore preference files — excluding the re-downloadable TTS model and
dictionary packs and the device-bound API keys. Restore stages to a temp dir first, swaps the live
files, then the app kills its own process (Room/DataStore hold pre-restore state).

## Testing

Unit tests (`app/src/test/`, ~78 files, ~708 tests) concentrate on the domain layer: FSRS scheduling,
routine planning/validation/parsing, prompt parsing, cue generation with ICU parity, the per-language
conjugation golden replays (8,400 Spanish cells + 10,800 Portuguese) plus the hand-written Portuguese
paradigm check, EPUB parsing and section alignment, Anki round-trip against real SQLite (sqlite-jdbc),
WAV parsing, focus-time/streak stats, the registry- and language-profile-completeness tests, the
course catalog's completeness (every scope resolvable against its spec, every authored example and
graduation-card answer replayed through the conjugator, every drill fillable to its authored length,
and every warm-up card traceable to a lesson that taught it) with its plan codec / session builder /
scope union / content validator / checkup ladder, and the
maintenance runner's gating/failure-isolation (fakes, no Android). Migrations are replayed against
real SQLite too — their SQL is exposed as statement lists for exactly that. The one that
restructures data, 42→43 (the notes/cards split, `di/Migration42To43.kt`), also has
`Migration42To43DryRunTest`: given `-DmigrationDb=<path>` it replays the migration over a *copy* of
a pulled device database and checks that no card, schedule or review went missing, which is worth
doing before that build reaches a phone. `PreMigrationSnapshot` copies the database file aside on
the device before that migration runs. Run with `.\gradlew.bat :app:testDebugUnitTest`. There
are no instrumented UI tests; device smoke testing is manual.

## Conventions and invariants

- **Frozen identifiers**: `ActivityType.name` values (attempt logs), `ActivityContent`
  `@SerialName`s + the `"kind"` discriminator (stored plans), proficiency bucket slugs
  (`ContrastCategory`, `IrregularityFamily`/`Class`, rapid-fire cue types — globally unique, which
  `ActivityRegistryTest` asserts), the conjugation `Tense`/`Person`/`DrillDirection` slugs *and*
  `Tense`/`DrillDirection` constant **names** (`ConjugationDrillConfig` serializes them by name into
  settings), course and lesson ids plus `LessonSegment` `@SerialName`s and the `CourseStatus` /
  `SessionKind` / `CourseSessionStatus` names (stored as TEXT), `LlmFeature` constant names
  (`llm_usage.feature`; an unknown one reads back as "other"), and Room entity/column names
  (append-only migrations). All are append-only. Lesson ids are *names*, not ordinals, so a lesson
  can be inserted mid-course without re-keying stored progress.
- **LLM budget**: routine synthesis is one consolidated call; anything new should batch into it
  rather than adding calls. A course lesson is one call for all its slots, plus an optional second
  for the wrap-up diagnosis. The one exception is background maintenance, which may make at most one
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
- **Background maintenance**: `data/maintenance/` runs periodic upkeep — audio retention, the opt-in
  due-review reminder notification, retrying chats whose reply never arrived, and back-filling vocab
  enrichment that the save-time pass missed (see below).
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
