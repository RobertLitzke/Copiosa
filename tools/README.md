# Offline dictionary packs

Copiosa shows offline definitions — English meanings, the target-language senses with their
register/dialect labels and example sentences, part of speech, and an inflected-form → lemma index
for conjugations — from a prebuilt SQLite pack per language. The app
([`LocalDictionary`](../app/src/main/java/com/copiosa/data/translate/LocalDictionary.kt))
downloads one `dict_<lang>.sqlite.gz` **once** per language, then queries it fully offline.

`build_dictionary.py` turns [Wiktextract / kaikki.org](https://kaikki.org) extracts into that pack.

## Build a pack

1. Download **two** source extracts per language — no single one carries both sides:
   - the **target-language edition**, for monolingual glosses with their labels and examples: the
     "Español" JSONL from <https://kaikki.org/eswiktionary/>, the "Português" JSONL from
     <https://kaikki.org/ptwiktionary/>, and so on.
   - the **English edition** for the same language, for the English glosses and the much better
     inflection tables: <https://kaikki.org/dictionary/Spanish/>,
     <https://kaikki.org/dictionary/Portuguese/>, … Take the `.jsonl.gz` (~90 MB for Spanish), not
     the uncompressed `.jsonl` the page links first (~1 GB).

2. Run the generator:
   ```sh
   python build_dictionary.py --input data/eswiktionary-raw.jsonl.gz        --en-input data/enwiktionary-Spanish.jsonl.gz --lang es --output dist/
   python build_dictionary.py --input data/ptwiktionary-raw.jsonl.gz        --en-input data/enwiktionary-Portuguese.jsonl.gz --lang pt --output dist/
   ```
   Output: `dist/dict_<lang>.sqlite` (inspect it) and `dist/dict_<lang>.sqlite.gz` (ship this).

| pack | lemmas | glosses | English | forms | genders | unpacked | download |
|---|---|---|---|---|---|---|---|
| `es` | 844k | 1.02M | 78k on 50k headwords | 3.78M | 40k of 74k nouns | 518 MB | 120 MB |
| `pt` | 396k | 561k | 68k on 43k headwords | 1.60M | 36k of 61k nouns | 203 MB | 55 MB |

The nouns without a gender are almost all plural-form rows ("Forma del plural de …"), which get
none by design — the gender lives on the singular headword.

English coverage is thin across the whole headword set and thick where it is actually read: **63% of
the 2000 most frequent Spanish words** carry English glosses, 35% a register/dialect label, 28% an
example sentence. The pack is anchored on the target-language edition's headwords — a word only the
English edition knows is skipped rather than added, which keeps the size tied to the edition
supplying the primary definitions.

Keep `LocalDictionary.PACK_SIZE_MB` in step with the download column — it is what the in-app button
quotes before it fetches anything.


The `--lang` code is the Wiktextract `lang_code` (`es`, `fr`, `de`, …) **and** the BCP-47 tag the
app stores in settings, so they must match. Repeat per language you want to support.

## Publish + wire up

1. Upload each `dict_<lang>.sqlite.gz` as an asset on the `dict-v<N>` GitHub Release for the
   pack's schema (schema 4 → `dict-v3`; `gh release create dict-v3 dist/dict_es.sqlite.gz
   dist/dict_pt.sqlite.gz --repo RobertLitzke/Copiosa`). **A new pack is
   not live until this is done** — the app requests `dict_<lang>.sqlite.gz` blindly and treats a 404
   as a silent failure, so the definition section simply stays empty.
2. Set `DICTIONARY_BASE_URL` in
   [`LocalDictionary.kt`](../app/src/main/java/com/copiosa/data/translate/LocalDictionary.kt)
   to the asset folder, e.g.
   `https://github.com/<owner>/Copiosa/releases/download/dict-v1/`.

Until that constant no longer contains the `<owner>` placeholder, `isConfigured()` is false and the
app simply omits the definition section (translation/save/explain keep working).

## Schema (kept in sync with `LocalDictionary.kt`)

```sql
lemmas(id INTEGER PRIMARY KEY, key TEXT, lemma TEXT, pos TEXT,  -- one row per headword+pos
       gender TEXT)                                             -- M/F/MF/MPL/FPL on nouns
senses(lemma_id, seq, gloss TEXT, labels TEXT, example TEXT)    -- one row per sense, in order
en_senses(lemma_id INTEGER, seq INTEGER, gloss TEXT)            -- English meanings of the headword
forms(key TEXT, lemma_id INTEGER, tags TEXT)                    -- inflected form -> lemma
meta(key TEXT PRIMARY KEY, value TEXT)                          -- lang, schema, built, counts
```

`key` is the lowercased, punctuation-trimmed headword/form (matches `LocalDictionary.normalize`).
Senses are capped per headword (`MAX_SENSES`, default 6) to keep the long tail of rare meanings
from bloating the pack. Forms are resolved to a `lemma_id` (preferring a matching part of speech)
and de-duplicated.

`en_senses` is its own table rather than a column on `senses` on purpose: the two editions split
meanings differently, so there is no honest sense-for-sense mapping between them. English glosses
are matched to a headword by `(key, pos)`, falling back to a headword's sole entry when the editions
disagree about the part of speech — except across a verb boundary, where "to run, to flow" beside a
noun's senses is plainly wrong rather than merely approximate.

`lemmas.gender` is the same vote `cardtool/wiktionary.py` applies to the deck (`gender_of`): sense
counts from both editions — the Spanish edition tags the entry, the English edition each sense — so
a rare regional reading can't flip a word (*mano* is F, not MF). Plural-only (MPL/FPL) is the
English edition's `plural-only` tag alone, since the Spanish edition never marks number. Only
headword senses of noun rows count; an adjective's masculine/feminine tags describe its forms.
The app stamps it on a new card at save time (`LocalDictionary.nounGender`).

`senses.labels` holds register/dialect/domain markers ("colloquial Argentina", "nautical"). Purely
grammatical tags are filtered out (`GRAMMATICAL_TAGS`), and inflection entries get none at all — with
hundreds of thousands of conjugated forms in the pack, "form-of" would otherwise be by far the most
common label in it.

Bumping the schema means bumping `LocalDictionary.REQUIRED_SCHEMA` **and** the release tag in
`DICTIONARY_BASE_URL`: a pack on disk whose schema is older reads as not-downloaded, so the card
offers the upgrade through the same download button a first-time reader sees.

---

# Conjugation drill lexicon (`build_conjugations.py`)

Generates the bundled verb data for the conjugation drill: the 200 most frequent verbs of a language,
every cell of every paradigm it drills, and a classification of *how* each irregular cell deviates.

```sh
python build_conjugations.py --lang es --check-only   # report; writes nothing
python build_conjugations.py --lang es                # regenerate Spanish
python build_conjugations.py --lang pt                # regenerate European Portuguese
```

The pipeline — reading the dump, ranking by frequency, classifying, emitting Kotlin — is
language-agnostic. Each language's morphology lives in `tools/conj/<lang>.py`: paradigm tables,
Wiktextract tag signatures, the irregularity cascade, and two hooks for dump quirks (`prefer_rival`
when a cell lists two spellings, `clean_form` when it packs them into one string). `--lang` picks a
module and installs its names as the generator's own globals, so the pipeline reads as it did when
there was only Spanish.

**Adding a language:** write `tools/conj/<lang>.py` mirroring the Kotlin `ConjugationSpec`, add it to
`LANGUAGES`, create `conjugation_overrides_<lang>.yaml`, and write the golden replay test. Expect the
first run to be loud — unclassified cells, dump gaps, missing glosses — and adjudicate each into the
YAML rather than silencing it.

Paths are per language and declared in `tools/conj/<lang>.py`; `es` and `pt` shown together below.

| Input | |
|---|---|
| `data/{es,pt}wiktionary-raw.jsonl.gz` | the same dump `build_dictionary.py` uses; supplies the forms |
| `data/{es,pt}_frequency_50k.txt` | OpenSubtitles counts ([hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), MIT). Gitignored — falls back to the committed `{es,pt}_top2000.tsv`, so a rebuild needs no download |
| `conjugation_overrides{,_pt}.yaml` | everything that needs a human: glosses, damping, exclusions, restrictions, hand-supplied paradigms, class corrections |

| Output | |
|---|---|
| `../app/.../conjugation/data/VerbLexiconData{,Pt}.kt` | the lexicon, chunked into blocks of 40 verbs |
| `../app/src/test/resources/conjugation_golden{,_pt}.csv` | every cell (8,400 es / 10,800 pt), replayed by the golden test |
| `{es,pt}_top2000.tsv` | committed frequency slice |
| `_verify/conjugations{,_pt}.json` | human-readable dump |

**Only deviations are stored.** Regular cells are reproduced at runtime by `Conjugator.kt` from the
language's `ConjugationSpec`, which the matching `tools/conj/<lang>.py` mirrors in Python — and the
golden test replays every cell through the Kotlin engine, so the two cannot drift apart unnoticed.

A **derived** paradigm is not stored either: Spanish's imperfect subjunctive comes off the
third-plural preterite, Portuguese's future subjunctive off the same cell and its personal infinitive
off the infinitive, so each inherits its source's irregularity for free. A derived cell is written out
only where the dump disagrees with the derivation — Portuguese's `tivéssemos` takes an acute where the
regular `comêssemos` takes a circumflex, and no orthographic rule predicts which. The golden test
asserts nothing is stored that the rule would have produced anyway.

The script is loud rather than lossy. It reports unclassified deviations, verbs whose paradigm the
dump doesn't carry (Spanish estar's entry has none — its table is hand-supplied here), rival forms it
had to choose between, and missing glosses. Each is a prompt to adjudicate in the YAML, not a warning to
ignore; a clean run prints none of them.

Ranking sums corpus counts over each lemma's surface forms, skipping any form some non-verb also
spells — without that, `escolar`, `solar` and `estrellar` ride their noun homographs straight into the
top 200. Verb-verb collisions survive (`vengar` vs `venir`'s subjunctive; Portuguese `for` and `tiver`,
which are inflections of ser/ir and ter that happen to end like infinitives) and are handled by the
`exclude:` list.

---

# One-off deck cleanup (`clean_spanish_apkg.py`)

A single-use script that rewrites one specific hand-built Spanish deck (`Spanish.apkg`) into a form
suited to Copiosa's highlighting / LLM-vocab features, **without touching review scheduling or
history**. It edits only `notes` (`flds`/`tags`/`sfld`/`csum`/`mod`) and the `col.tags` registry,
and asserts `cards` + `revlog` + `col.crt` are byte-identical before writing (else it aborts).

```sh
python clean_spanish_apkg.py --input ../Spanish.apkg --report                 # dry run, prints samples
python clean_spanish_apkg.py --input ../Spanish.apkg --output ../Spanish.clean.apkg
```

Per note it picks the Spanish (target) side, classifies the card, and strips display cruft into
namespaced **Anki tags** that Copiosa reads on import (`AnkiImporter`):

| tag | values |
|---|---|
| `la:kind:` | `vocab` `phrase` `sentence` `conjugation` `grammar` `cloze` (only `vocab`/`phrase` feed the LLM / highlight) |
| `la:gender:` | `m` `f` `mf` `mpl` `fpl` (from `(el)`/`(la)`/`(nf)`/`@`/euphonic `(feminina)`) |
| `la:pos:` | `noun` `verb` `adjective` … (from inline `(adj)`/`(v)` markers) |
| `la:region:` | `mx` `centroamerica` `sudamerica` … (allow-list; ignores English-side `[gloss]`s) |
| `la:tgt:` | `0`/`1` — which projected field is the Spanish term (resolves the deck's inconsistent direction) |

Only `vocab`/`phrase` fronts are rewritten (article/POS/region/epicene stripped; multi-term
`(el) cerdo, (el) chancho` → `cerdo; chancho`, which Copiosa splits into front + altForms).
Conjugation/grammar/cloze/sentence fields are left untouched — only tagged. This deck's quirks are
unlikely to recur, so the parsing lives here (Python) rather than in the app.

---

# Offline deck polish (`cards_*.py`, `cardtool/`)

A one-time pass over the *installed* deck: backfill `partOfSpeech` / `gender`, add an example
sentence to every card, and put a picture on the ones that want one. Everything works on a copy of
the device database pulled over adb — **the app is not involved and needs no changes**.

The phone has no `sqlite3` binary, so edits round-trip as a whole-file swap. That gives one rule
that matters more than the rest:

> **Don't use the app between `pull` and `push`.** The swap discards anything reviewed in that
> window. `push` refuses when the device's newest review is younger than the pull; `--force`
> overrides, throwing those reviews away.

```sh
python cards_sync.py pull                       # force-stop, copy copiosa.db into ../dbpull/
python cards_normalize.py                       # dry run -> ../dbpull/reports/normalize.csv
python cards_normalize.py --apply a b           # write both tiers
python cards_sentences.py export                # -> ../dbpull/sentences/cards_NNN.json + PROMPT.md
python cards_sentences.py import ../dbpull/sentences/*.reply.json
python cards_images.py                          # http://127.0.0.1:8765
python cards_sync.py push                       # swap back, carrying ../dbpull/anki_media/
```

`cards_sync.py` finds adb via `%LOCALAPPDATA%\Android\Sdk\platform-tools`, or `$ADB`. Every tool
refuses to open a database whose `PRAGMA user_version` isn't `cardtool.db.EXPECTED_USER_VERSION`
(40, from `AppDatabase.kt`) — bump it deliberately when the app's migrations move on.

## The two invariants

Any write that changes `front`, `back`, `partOfSpeech` or `gender` must also set
`inflectedForms = NULL`, because the app only recomputes that cache where it is NULL
(`FlashcardRepository.ensureFormsBackfilled`); migrations 30→31 and 32→33 exist because it went
stale. `cardtool.db.set_fields` does this for you, and is the only sanctioned way to write a card.

`partOfSpeech` and `gender` are validated against the editor's own vocabulary
(`EditCardDialog.kt`, `VocabChips.kt`) — a value outside it renders as a raw string. Nothing here
ever writes `cardKind` or `state`: `Converters` reads those with a bare `valueOf`, so an
out-of-vocabulary value would crash the app on read.

## `cards_normalize.py` — parts of speech and genders

Two tiers, both gap-fillers: an existing part of speech or gender is never overwritten.

**Tier A** parses the markers a structured schema replaces — a trailing `(adj)`/`(nf)`, a leading
`(el)`/`(la)` — into the columns and deletes them from the text. The patterns descend from
`clean_spanish_apkg.py` (above), which did this once against the original .apkg; `cardtool/markers.py`
is the database-side re-derivation, emitting column values rather than `la:*` tag values.

Glosses reading `(to) fall` / `(the) smoke` are treated as *hints*, not markers: read for the part
of speech, left in the text. They are natural English, and deleting them would change 300 cards'
wording for nothing.

**Tier B** looks the target word up in `data/enwiktionary-Spanish.jsonl.gz` (`cardtool/wiktionary.py`),
whose noun entries carry the gender the built pack drops. Three things it deliberately refuses:

- **Accent-blind matching**, except for a query with no accents of its own. *cantó* is a verb and
  *canto* is a noun; folding them turned a shelf of conjugation cards into masculine nouns.
- **Noun-vs-adjective ties.** Spanish adjectives nominalise freely, so sense counts can't separate
  them — *flaco*, *plana* and *chico* all read as dominant nouns and are adjectives on the card.
- **A gender for anything another part of speech also claims**, or for a participle/diminutive
  ending. A wrong article chip ("el plana") is worse than a missing one.

`CONJUGATION` cards skip the dictionary entirely and are stamped `verb` — *traje*, *vino* and
*tomé* are all real nouns, and the lookup gets them wrong every time.

## `cards_sentences.py` — example sentences

`contextSentence` is the cloze bank's cheapest source (`ContextSentenceSource`), so filling it is
what lifts the bank off its floor. Export writes 200-item JSON batches plus a `PROMPT.md` to hand
an LLM along with them; save each answer as `cards_NNN.reply.json` and import it. `--batch-size` changes the split if 200 is still too much for the model you're using.

The deck stores each item twice, once per review direction, so the export groups by (target, gloss)
and asks for one sentence per *item* — about half the rows — then writes the answer to every card
in the group. Items still missing a part of speech or a noun's gender ask for those too.

Import validates before writing: the sentence has to be 4–20 words and actually use the term
(allowing inflection), and any `pos`/`gender` has to be in vocabulary. Everything else lands in
`../dbpull/reports/sentences_rejected.csv`.

## `cards_cloze.py` — filling the cloze bank

`ContextSentenceSource.mine` walks `flashcardDao.allActive` from the top and stops at
`limitPerSource` (`ClozeViewModel.STOCK_PER_RUN` = 60) with **no progress cursor**, so every run —
the "Find more sentences" button and the six-hourly `ClozeMineTask` alike — re-offers the same head
of the deck. Once those are banked or refused the bank stops growing, however many example
sentences exist. Measured 2026-09-02: 4,226 sentences in the deck, bank stuck at 55, every further
run reporting *Added 0*.

This walks the whole deck once and writes `cloze_sentences` rows directly:

```sh
python cards_cloze.py            # dry run: what would be banked, and why the rest wasn't
python cards_cloze.py --apply
```

`cardtool/clozegate.py` is a **faithful port** of `domain/cloze/ClozeGate.kt` — same constants, same
order, same rejection reasons — so this banks exactly what the app would have banked had it got
that far, no looser and no stricter. `cardtool/functionwords.py` is extracted verbatim from
`FunctionWords.kt`, and the frequency index is read from `es_lemmas2000.tsv`, the same data behind
`FrequencyListEsData.kt`. **If `ClozeGate` gains a rule, the port is wrong until it does too.**

It honours the app's own caps: `PER_LEMMA_CAP` = 3 and `BANK_MAX_PER_LANGUAGE` = 1,500
(`--limit` overrides). `distractors` is deliberately left NULL — the app resolves options at
session time in `ClozeRepository.itemFor`, and a banked option set would go stale.

Both direction-halves of an item carry the same sentence, so the second collides on the unique
`(targetLanguage, lemma, textKey)` index exactly as the app's `INSERT OR IGNORE` would; the report
counts those separately from real refusals.

## `cards_images.py` — the picture picker

A loopback-only web app over the pulled database. Searches Wikimedia Commons through the same
query shape as the in-app picker (`CommonsApi.kt`), and writes images the way
`ImageSearchRepository.attachImage` writes them — longest edge 384px, JPEG quality 85, named
`la_img_<cardId>_<epochMs>.jpg` — so a card imaged here is indistinguishable from one imaged in the
app and travels through .apkg export the same way. Commons is proxied server-side, which sidesteps
CORS and keeps the descriptive User-Agent MediaWiki asks for. Its contact is `copiosa.contact` in the
gitignored `local.properties`, the key the app build reads too; absent, the project URL stands in.

Queue is `imageFile IS NULL AND suspended = 0 AND cardKind IN ('VOCAB','PHRASE')` ordered by
`dueAt` (matching `FlashcardDao.imagelessCards`), grouped like the sentence export so a
direction-pair is imaged once. One keystroke per card: `1`–`8` picks, `s` skips, `/` refocuses the
search, and a drop zone takes a dragged or pasted image when Commons has nothing usable. Most
cards should be skipped — abstract nouns don't want a photo. Progress is written immediately, so
stopping and restarting is free.

| Working files (all in gitignored `../dbpull/`) | |
|---|---|
| `copiosa.db` / `copiosa.db.orig` | the working copy, and the untouched snapshot taken at pull |
| `anki_media/` | images added here, pushed into `files/anki_media/` |
| `sentences/` | the JSON batches, `PROMPT.md`, and your `*.reply.json` |
| `reports/` | `normalize.csv`, `sentences_rejected.csv` |
| `es_pos_gender.json` | cached part-of-speech/gender index, rebuilt when the dump changes |

---

# Frequency-ranked vocabulary (`build_frequency.py`)

Generates the bundled frequency list behind vocabulary coverage, the rank chips, the review-order
tilt and the frequent-word drill: the top 2,000 **content lemmas** of a language, each with the
surface forms the corpus spells it with.

```sh
python build_frequency.py                      # regenerate every language
python build_frequency.py --lang es             # Spanish only
python build_frequency.py --lang es --check-only  # report + _verify dump; writes no committed files
```

The corpus counts *surface forms*, which is not what a vocabulary list wants: `es`, `está`, `estoy`
and `estás` are four rows, and roughly a sixth of the head is grammar words nobody makes a card of.
So every form is looked up in the dictionary pack, rolled up onto its headword, and filtered.

The one genuinely hard part is telling a base form from an inflection, because the pack gives every
inflected form its own `lemmas` row — `tengo` looks exactly like `tener`. `resolve()` decides in a
fixed order (function-word drop → nominal plural collapse → is-it-its-own-headword → collapse onto a
parent), with two safeguards worth knowing about:

- **`MIN_PARADIGM`** — an entry counts as a base form only if it owns several inflections of its own.
  This is what separates `tener` from `tengo`, and it has to be a count rather than "owns any",
  because the packs give some inflections a one-child stub paradigm.
- **`SELF_BIAS`** — a word that *is* a headword keeps itself unless a parent is much better
  supported. Without it `tema` (topic) is swallowed by `temer` (to fear); with too much of it, `era`
  stays an epoch instead of being the imperfect of `ser`. Support is measured over three passes: the
  first adjudicates on raw surface counts, later ones on the rolled-up lemma totals, which is what
  finally lets `ser` — scattered across fifty forms — outweigh the rare nouns `era`, `son` and `ere`
  that happen to spell three of them.

Everything the rules still get wrong goes in `frequency_overrides_<lang>.yaml`, which has a
`stoplist` (closed-class grammar the part-of-speech filter can't see — Wiktionary tags possessives
and demonstratives as adjectives), a `drop` list (corpus artefacts, filler sounds, letter names) and
`remap` (surface form → the headword it belongs to).

**Read `tools/_verify/frequency_<lang>.json` after any change.** A wrong list teaches the wrong
words, which is worse than having no list; the generator can only assert that no surface form is
claimed by two headwords, not that the headwords are right.

**Adding a language:** add it to `LANGUAGES`, create `frequency_overrides_<lang>.yaml`, register the
generated object in `domain/vocab/freq/FrequencyLists.kt`, and extend `FrequencyListTest`. It needs
both a `data/<lang>_frequency_50k.txt` and a built `dist/dict_<lang>.sqlite`.

| Input | |
|---|---|
| `data/{es,pt}_frequency_50k.txt` | OpenSubtitles counts ([hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), MIT). Gitignored |
| `dist/dict_{es,pt}.sqlite` | the dictionary pack from `build_dictionary.py`; supplies lemmas, forms and parts of speech. Gitignored |
| `frequency_overrides_{es,pt}.yaml` | the curated stoplist / drops / remaps |

| Output | |
|---|---|
| `{es,pt}_lemmas2000.tsv` | committed derived list, so a rebuild or audit needs nothing from `data/` |
| `../app/.../domain/vocab/freq/data/FrequencyList{Es,Pt}Data.kt` | the shipped list, packed as `lemma\|pos\|forms` lines in 500-line blocks |
| `../app/src/test/resources/frequency_{es,pt}.tsv` | the copy `FrequencyListTest` replays against the Kotlin |
| `_verify/frequency_{es,pt}.json` | human-readable dump for eyeballing (gitignored) |
