# Offline dictionary packs

Copiosa shows offline definitions (monolingual meaning + part of speech, plus an
inflected-form → lemma index for conjugations) from a prebuilt SQLite pack per language. The app
([`LocalDictionary`](../app/src/main/java/com/copiosa/data/translate/LocalDictionary.kt))
downloads one `dict_<lang>.sqlite.gz` **once** per language, then queries it fully offline.

`build_dictionary.py` turns a [Wiktextract / kaikki.org](https://kaikki.org) extract into that pack.

## Build a pack (Spanish example)

1. Download a source extract:
   - **Definitions (Spanish-language):** the Spanish-edition "Español" JSONL from
     <https://kaikki.org/eswiktionary/> (glosses are in Spanish).
   - *Optional, richer conjugations:* the English-edition Spanish JSONL from
     <https://kaikki.org/dictionary/Spanish/> — pass via `--forms-input` (its glosses are ignored,
     only its inflection tables are merged).

2. Run the generator:
   ```sh
   python build_dictionary.py --input eswiktionary-es.jsonl.gz --lang es --output dist/
   # optionally:
   python build_dictionary.py --input eswiktionary-es.jsonl.gz --lang es \
       --forms-input enwiktionary-Spanish.jsonl.gz --output dist/
   ```
   Output: `dist/dict_es.sqlite` (inspect it) and `dist/dict_es.sqlite.gz` (ship this).

The `--lang` code is the Wiktextract `lang_code` (`es`, `fr`, `de`, …) **and** the BCP-47 tag the
app stores in settings, so they must match. Repeat per language you want to support.

## Publish + wire up

1. Create a GitHub Release (e.g. tag `dict-v1`) and upload each `dict_<lang>.sqlite.gz` as an asset.
2. Set `DICTIONARY_BASE_URL` in
   [`LocalDictionary.kt`](../app/src/main/java/com/copiosa/data/translate/LocalDictionary.kt)
   to the asset folder, e.g.
   `https://github.com/<owner>/Copiosa/releases/download/dict-v1/`.

Until that constant no longer contains the `<owner>` placeholder, `isConfigured()` is false and the
app simply omits the definition section (translation/save/explain keep working).

## Schema (kept in sync with `LocalDictionary.kt`)

```sql
lemmas(id INTEGER PRIMARY KEY, key TEXT, lemma TEXT, pos TEXT)  -- one row per headword+pos
senses(lemma_id INTEGER, seq INTEGER, gloss TEXT)              -- one row per sense, in order
forms(key TEXT, lemma_id INTEGER, tags TEXT)                   -- inflected form -> lemma
meta(key TEXT PRIMARY KEY, value TEXT)                         -- lang, schema, built, counts
```

`key` is the lowercased, punctuation-trimmed headword/form (matches `LocalDictionary.normalize`).
Senses are capped per headword (`MAX_SENSES`, default 6) to keep the long tail of rare meanings
from bloating the pack. Forms are resolved to a `lemma_id` (preferring a matching part of speech)
and de-duplicated.

---

# Conjugation drill lexicon (`build_conjugations.py`)

Generates the bundled verb data for the conjugation drill: the 200 most frequent Spanish verbs, every
cell of seven paradigms, and a classification of *how* each irregular cell deviates.

```sh
python build_conjugations.py --check-only     # report; writes nothing
python build_conjugations.py                  # regenerate everything
```

| Input | |
|---|---|
| `data/eswiktionary-raw.jsonl.gz` | the same dump `build_dictionary.py` uses; supplies the forms |
| `data/es_frequency_50k.txt` | OpenSubtitles counts ([hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords), MIT). Gitignored — falls back to the committed `es_top2000.tsv`, so a rebuild needs no download |
| `conjugation_overrides.yaml` | everything that needs a human: glosses, damping, exclusions, restrictions, hand-supplied paradigms, class corrections |

| Output | |
|---|---|
| `../app/src/main/java/com/copiosa/domain/conjugation/data/VerbLexiconData.kt` | the lexicon, chunked into blocks of 40 verbs |
| `../app/src/test/resources/conjugation_golden.csv` | all 8,400 cells, replayed by `ConjugationGoldenTest` |
| `es_top2000.tsv` | committed frequency slice |
| `_verify/conjugations.json` | human-readable dump |

**Only deviations are stored.** Regular cells are reproduced at runtime by `RegularConjugator.kt`,
which this script mirrors in Python — and `ConjugationGoldenTest` replays every cell through the
Kotlin engine, so the two implementations cannot drift apart unnoticed. The imperfect subjunctive is
never stored at all: it derives from the third-plural preterite, so it inherits that cell's
irregularity for free, and the script rejects any `sub_imp` override.

The script is loud rather than lossy. It reports unclassified deviations, verbs whose paradigm the
dump doesn't carry (estar's entry has none — its table is hand-supplied here), rival forms it had to
choose between, and missing glosses. Each is a prompt to adjudicate in the YAML, not a warning to
ignore; a clean run prints none of them.

Ranking sums corpus counts over each lemma's surface forms, skipping any form some non-verb also
spells — without that, `escolar`, `solar` and `estrellar` ride their noun homographs straight into the
top 200. Verb-verb collisions survive (`vengar` vs `venir`'s subjunctive) and are handled by the
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
